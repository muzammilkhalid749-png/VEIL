package com.m3.veil;

import com.m3.veil.utils.VeilBufferPool;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Production Unit Tests for VeilBufferPool Zero-GC Slab Engine (#204).
 * Verifies 16KB and 4KB pooled byte array and direct ByteBuffer slab allocation,
 * recycling, state clearing, and AutoCloseable try-with-resources semantics.
 */
public class VeilBufferPoolTest {

    @Test
    public void testBorrowAndRecycleByteArray16k() {
        byte[] slab1 = VeilBufferPool.borrowByteArray16k();
        assertNotNull("Borrowed byte array must not be null", slab1);
        assertEquals(VeilBufferPool.CHUNK_SIZE_16K, slab1.length);

        // Fill with pattern
        slab1[0] = 0x42;
        slab1[VeilBufferPool.CHUNK_SIZE_16K - 1] = 0x7F;

        // Recycle slab back to pool
        VeilBufferPool.recycleByteArray(slab1);

        // Borrow again
        byte[] slab2 = VeilBufferPool.borrowByteArray16k();
        assertNotNull(slab2);
        assertEquals(VeilBufferPool.CHUNK_SIZE_16K, slab2.length);

        // Recycle to maintain clean pool
        VeilBufferPool.recycleByteArray(slab2);
    }

    @Test
    public void testBorrowAndRecycleByteBuffer16k() {
        ByteBuffer buf1 = VeilBufferPool.borrowByteBuffer16k();
        assertNotNull("Borrowed ByteBuffer must not be null", buf1);
        assertTrue("Slab allocator must return direct ByteBuffer for zero-copy JNI/WebRTC IO", buf1.isDirect());
        assertEquals(VeilBufferPool.CHUNK_SIZE_16K, buf1.capacity());
        assertEquals(0, buf1.position());
        assertEquals(VeilBufferPool.CHUNK_SIZE_16K, buf1.limit());

        // Mutate position and limit
        buf1.put((byte) 0xAA);
        buf1.put((byte) 0xBB);
        buf1.flip();
        assertEquals(2, buf1.limit());

        // Recycle ByteBuffer
        VeilBufferPool.recycleByteBuffer(buf1);

        // Borrow again: verify position and limit are completely reset
        ByteBuffer buf2 = VeilBufferPool.borrowByteBuffer16k();
        assertNotNull(buf2);
        assertTrue(buf2.isDirect());
        assertEquals(0, buf2.position());
        assertEquals(VeilBufferPool.CHUNK_SIZE_16K, buf2.limit());

        VeilBufferPool.recycleByteBuffer(buf2);
    }

    @Test
    public void testBorrowAndRecycle4kSlabs() {
        // 4KB Primitive Array
        byte[] array4k = VeilBufferPool.borrowByteArray4k();
        assertNotNull(array4k);
        assertEquals(VeilBufferPool.CHUNK_SIZE_4K, array4k.length);
        VeilBufferPool.recycleByteArray4k(array4k);

        // 4KB Direct ByteBuffer
        ByteBuffer direct4k = VeilBufferPool.borrowByteBuffer4k();
        assertNotNull(direct4k);
        assertTrue(direct4k.isDirect());
        assertEquals(VeilBufferPool.CHUNK_SIZE_4K, direct4k.capacity());
        VeilBufferPool.recycleByteBuffer4k(direct4k);
    }

    @Test
    public void testAutoCloseableTryWithResources() {
        // Test AutoCloseable byte array
        try (VeilBufferPool.PooledByteArray pooledArray = VeilBufferPool.borrowByteArray16kAuto()) {
            assertNotNull(pooledArray);
            assertNotNull(pooledArray.array());
            assertEquals(VeilBufferPool.CHUNK_SIZE_16K, pooledArray.array().length);
            pooledArray.array()[0] = 0x55;
        }

        // Test AutoCloseable direct ByteBuffer
        try (VeilBufferPool.PooledByteBuffer pooledBuffer = VeilBufferPool.borrowByteBuffer16kAuto()) {
            assertNotNull(pooledBuffer);
            assertNotNull(pooledBuffer.buffer());
            assertTrue(pooledBuffer.buffer().isDirect());
            assertEquals(VeilBufferPool.CHUNK_SIZE_16K, pooledBuffer.buffer().capacity());
        }
    }
}
