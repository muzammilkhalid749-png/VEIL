package com.m3.veil.utils;

import android.content.ComponentCallbacks2;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-GC Slab Allocator & Recycler for High-Throughput P2P Chunks and Media IO.
 *
 * <p>Modeled directly on Telegram's {@code NativeByteBuffer} and slab memory pools.
 * Low-RAM Android devices (2GB–3GB RAM) suffer severe UI stutter and audio frame
 * drops when file transfers or Status streaming repeatedly allocate 16KB byte arrays
 * (e.g. 10MB file = 640 allocations per transfer, saturating Android ART's GC).
 *
 * <p>Features:
 * <ul>
 *   <li>Lock-free slab pooling with {@link ConcurrentLinkedQueue}</li>
 *   <li>Dedicated 16 KB and 4 KB pools for both direct {@link ByteBuffer} and {@code byte[]}</li>
 *   <li>Memory-aware capacity budgeting capped by {@link VeilPerformanceController}</li>
 *   <li>{@link AutoCloseable} wrappers for idiomatic {@code try-with-resources} borrow & recycle</li>
 *   <li>Automatic heap purging during {@link ComponentCallbacks2#TRIM_MEMORY_RUNNING_CRITICAL}</li>
 * </ul>
 */
public class VeilBufferPool {

    private static final String TAG = "VeilBufferPool";

    public static final int CHUNK_SIZE_16K = 16384; // 16 KB standard WebRTC chunk
    public static final int CHUNK_SIZE_4K = 4096;   // 4 KB standard IO block

    // Sized queues for 16KB slabs
    private static final Queue<byte[]> BYTE_ARRAY_16K_POOL = new ConcurrentLinkedQueue<>();
    private static final Queue<ByteBuffer> DIRECT_BUF_16K_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger COUNT_BYTES_16K = new AtomicInteger(0);
    private static final AtomicInteger COUNT_DIRECT_16K = new AtomicInteger(0);

    // Sized queues for 4KB slabs
    private static final Queue<byte[]> BYTE_ARRAY_4K_POOL = new ConcurrentLinkedQueue<>();
    private static final Queue<ByteBuffer> DIRECT_BUF_4K_POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger COUNT_BYTES_4K = new AtomicInteger(0);
    private static final AtomicInteger COUNT_DIRECT_4K = new AtomicInteger(0);

    // Monitoring metrics
    private static final AtomicLong HIT_COUNT = new AtomicLong(0);
    private static final AtomicLong MISS_COUNT = new AtomicLong(0);

    private VeilBufferPool() { }

    private static int getMaxCapacity() {
        try {
            VeilPerformanceController controller = VeilPerformanceController.getInstance();
            if (controller.isLowEndDevice()) {
                return 16;  // 256 KB max pool footprint on 2GB RAM phones
            } else if (controller.isHighEndDevice()) {
                return 128; // 2 MB max pool footprint on flagships
            } else {
                return 64;  // 1 MB max pool footprint on mid-tier
            }
        } catch (Throwable t) {
            return 32;
        }
    }

    // ---- 16 KB Primitive Byte Array Pooling ----------------------------------

    @NonNull
    public static byte[] borrowByteArray16k() {
        byte[] buffer = BYTE_ARRAY_16K_POOL.poll();
        if (buffer != null) {
            COUNT_BYTES_16K.decrementAndGet();
            HIT_COUNT.incrementAndGet();
            return buffer;
        }
        MISS_COUNT.incrementAndGet();
        return new byte[CHUNK_SIZE_16K];
    }

    public static void recycleByteArray(byte[] buffer) {
        if (buffer == null) return;
        if (buffer.length == CHUNK_SIZE_16K) {
            if (COUNT_BYTES_16K.get() < getMaxCapacity()) {
                BYTE_ARRAY_16K_POOL.offer(buffer);
                COUNT_BYTES_16K.incrementAndGet();
            }
        } else if (buffer.length == CHUNK_SIZE_4K) {
            recycleByteArray4k(buffer);
        }
    }

    // ---- 16 KB Direct ByteBuffer Pooling ------------------------------------

    @NonNull
    public static ByteBuffer borrowByteBuffer16k() {
        ByteBuffer buf = DIRECT_BUF_16K_POOL.poll();
        if (buf != null) {
            COUNT_DIRECT_16K.decrementAndGet();
            buf.clear();
            HIT_COUNT.incrementAndGet();
            return buf;
        }
        MISS_COUNT.incrementAndGet();
        ByteBuffer direct = ByteBuffer.allocateDirect(CHUNK_SIZE_16K);
        direct.order(ByteOrder.LITTLE_ENDIAN);
        return direct;
    }

    public static void recycleByteBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        if (buffer.capacity() == CHUNK_SIZE_16K) {
            if (COUNT_DIRECT_16K.get() < getMaxCapacity()) {
                buffer.clear();
                DIRECT_BUF_16K_POOL.offer(buffer);
                COUNT_DIRECT_16K.incrementAndGet();
            }
        } else if (buffer.capacity() == CHUNK_SIZE_4K) {
            recycleByteBuffer4k(buffer);
        }
    }

    // ---- 4 KB Primitive Byte Array Pooling -----------------------------------

    @NonNull
    public static byte[] borrowByteArray4k() {
        byte[] buffer = BYTE_ARRAY_4K_POOL.poll();
        if (buffer != null) {
            COUNT_BYTES_4K.decrementAndGet();
            HIT_COUNT.incrementAndGet();
            return buffer;
        }
        MISS_COUNT.incrementAndGet();
        return new byte[CHUNK_SIZE_4K];
    }

    public static void recycleByteArray4k(byte[] buffer) {
        if (buffer != null && buffer.length == CHUNK_SIZE_4K) {
            if (COUNT_BYTES_4K.get() < getMaxCapacity()) {
                BYTE_ARRAY_4K_POOL.offer(buffer);
                COUNT_BYTES_4K.incrementAndGet();
            }
        }
    }

    // ---- 4 KB Direct ByteBuffer Pooling -------------------------------------

    @NonNull
    public static ByteBuffer borrowByteBuffer4k() {
        ByteBuffer buf = DIRECT_BUF_4K_POOL.poll();
        if (buf != null) {
            COUNT_DIRECT_4K.decrementAndGet();
            buf.clear();
            HIT_COUNT.incrementAndGet();
            return buf;
        }
        MISS_COUNT.incrementAndGet();
        ByteBuffer direct = ByteBuffer.allocateDirect(CHUNK_SIZE_4K);
        direct.order(ByteOrder.LITTLE_ENDIAN);
        return direct;
    }

    public static void recycleByteBuffer4k(ByteBuffer buffer) {
        if (buffer != null && buffer.isDirect() && buffer.capacity() == CHUNK_SIZE_4K) {
            if (COUNT_DIRECT_4K.get() < getMaxCapacity()) {
                buffer.clear();
                DIRECT_BUF_4K_POOL.offer(buffer);
                COUNT_DIRECT_4K.incrementAndGet();
            }
        }
    }

    // ---- AutoCloseable Wrappers for Try-With-Resources -----------------------

    public static final class PooledByteArray implements AutoCloseable {
        private byte[] array;

        PooledByteArray(byte[] array) {
            this.array = array;
        }

        public byte[] array() {
            return array;
        }

        @Override
        public void close() {
            if (array != null) {
                recycleByteArray(array);
                array = null;
            }
        }
    }

    public static final class PooledByteBuffer implements AutoCloseable {
        private ByteBuffer buffer;

        PooledByteBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        public ByteBuffer buffer() {
            return buffer;
        }

        @Override
        public void close() {
            if (buffer != null) {
                recycleByteBuffer(buffer);
                buffer = null;
            }
        }
    }

    public static PooledByteArray borrowByteArray16kAuto() {
        return new PooledByteArray(borrowByteArray16k());
    }

    public static PooledByteArray borrowByteArray4kAuto() {
        return new PooledByteArray(borrowByteArray4k());
    }

    public static PooledByteBuffer borrowByteBuffer16kAuto() {
        return new PooledByteBuffer(borrowByteBuffer16k());
    }

    public static PooledByteBuffer borrowByteBuffer4kAuto() {
        return new PooledByteBuffer(borrowByteBuffer4k());
    }

    // ---- Cache Eviction & Low Memory Trim ------------------------------------

    /**
     * Purges pooled slabs when the operating system warns of memory pressure.
     */
    public static void trimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            int purged = 0;
            purged += BYTE_ARRAY_16K_POOL.size();
            BYTE_ARRAY_16K_POOL.clear();
            COUNT_BYTES_16K.set(0);

            purged += DIRECT_BUF_16K_POOL.size();
            DIRECT_BUF_16K_POOL.clear();
            COUNT_DIRECT_16K.set(0);

            purged += BYTE_ARRAY_4K_POOL.size();
            BYTE_ARRAY_4K_POOL.clear();
            COUNT_BYTES_4K.set(0);

            purged += DIRECT_BUF_4K_POOL.size();
            DIRECT_BUF_4K_POOL.clear();
            COUNT_DIRECT_4K.set(0);

            Log.i(TAG, "Trimmed " + purged + " buffer slabs on memory level " + level);
        }
    }

    public static void clear() {
        BYTE_ARRAY_16K_POOL.clear();
        COUNT_BYTES_16K.set(0);
        DIRECT_BUF_16K_POOL.clear();
        COUNT_DIRECT_16K.set(0);
        BYTE_ARRAY_4K_POOL.clear();
        COUNT_BYTES_4K.set(0);
        DIRECT_BUF_4K_POOL.clear();
        COUNT_DIRECT_4K.set(0);
    }

    // ---- Metrics -------------------------------------------------------------

    public static long getHitCount() {
        return HIT_COUNT.get();
    }

    public static long getMissCount() {
        return MISS_COUNT.get();
    }

    public static int getActivePooledSlabs() {
        return COUNT_BYTES_16K.get() + COUNT_DIRECT_16K.get()
                + COUNT_BYTES_4K.get() + COUNT_DIRECT_4K.get();
    }
}
