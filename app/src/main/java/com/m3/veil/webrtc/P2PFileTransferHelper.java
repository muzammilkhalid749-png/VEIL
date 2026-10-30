package com.m3.veil.webrtc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.utils.VeilBufferPool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telegram-Grade Resumable Peer-to-Peer file transfer engine over WebRTC DataChannels.
 * Modeled on Telegram's FileLoadOperation.java & FileLoader.java:
 * 1. RandomAccessFile chunk assembly with pre-allocation (raf.setLength).
 * 2. Persistent BitSet chunk tracking in EncryptedChatDatabase (resumes interrupted transfers from exact missing chunks).
 * 3. Sliding-window backpressure matching DataChannel buffer capacity.
 * 4. Binary ByteBuffer packet framing with fallback to legacy JSON.
 */
public class P2PFileTransferHelper {

    private static final String TAG = "P2PFileTransferHelper";
    public static final int CHUNK_SIZE = 16384; // 16 KB per chunk (Telegram parity)
    private static final long MAX_BUFFER_AMOUNT = 1024 * 1024; // 1 MB high-speed sliding window ceiling
    private static final long RESUME_BUFFER_AMOUNT = 256 * 1024; // 256 KB resume threshold

    private static long getMaxBufferAmount() {
        try {
            int parallelChunks = com.m3.veil.utils.VeilPerformanceController.getInstance().getMaxParallelFileTransferChunks();
            return Math.max(MAX_BUFFER_AMOUNT, (long) parallelChunks * CHUNK_SIZE * 8L);
        } catch (Throwable t) {
            return MAX_BUFFER_AMOUNT;
        }
    }

    public interface FileTransferCallback {
        void onProgress(int percent);
        void onComplete(String transferId);
        void onError(String error);
    }

    public interface DownloadProgressListener {
        void onProgress(String transferId, int percent);
        void onComplete(String transferId, File file);
        void onError(String transferId, String error);
    }

    public interface OnFileReceivedListener {
        void onFileReceived(String transferId, String fileName, String mediaType, String caption, File file);
    }

    public interface OnFileRequestListener {
        void onFileRequested(String transferId);
        default void onFileRequestedResumable(String transferId, BitSet missingChunks) {
            onFileRequested(transferId);
        }
        void onFileTransferCanceled(String transferId);
    }

    private static final ExecutorService transferExecutor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Map<String, DownloadProgressListener> downloadListeners = new ConcurrentHashMap<>();
    private static OnFileRequestListener fileRequestListener;

    public static void registerDownloadListener(String transferId, DownloadProgressListener listener) {
        if (transferId != null && listener != null) {
            downloadListeners.put(transferId, listener);
        }
    }

    public static void unregisterDownloadListener(String transferId) {
        if (transferId != null) {
            downloadListeners.remove(transferId);
        }
    }

    public static void setOnFileRequestListener(OnFileRequestListener listener) {
        fileRequestListener = listener;
    }

    // Inbound Transfer State
    public static class InboundTransfer {
        public String transferId;
        public String fileName;
        public String mediaType;
        public String caption;
        public String blurHash;
        public long totalBytes;
        public int totalChunks;
        public int lastReportedPercent = -1;
        public int tailRetryCount = 0;
        public BitSet receivedBitSet;
        public File tempFile;
        public RandomAccessFile raf;
        public String peerId;
        public AtomicBoolean isCompleted = new AtomicBoolean(false);
    }

    private static final Map<String, InboundTransfer> activeInboundTransfers = new ConcurrentHashMap<>();

    /**
     * Generates a compact ~200-byte micro-BlurHash inline preview header from image or video media.
     * Sent in FILE_START so the recipient can render 0ms blurred previews before chunk 0 arrives.
     */
    public static String generateMicroBlurHash(File file, String mediaType) {
        if (file == null || !file.exists()) return "";
        try {
            Bitmap thumb = null;
            if (mediaType != null && (mediaType.startsWith("video") || mediaType.contains("video"))) {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(file.getAbsolutePath());
                thumb = mmr.getFrameAtTime(1000000);
                mmr.release();
            } else if (mediaType != null && (mediaType.startsWith("image") || mediaType.contains("image"))) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                thumb = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            }
            if (thumb != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(thumb, 16, 16, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 35, baos);
                scaled.recycle();
                thumb.recycle();
                byte[] bytes = baos.toByteArray();
                if (bytes.length <= 256) {
                    return Base64.encodeToString(bytes, Base64.NO_WRAP);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error generating micro BlurHash preview: " + t.getMessage());
        }
        return "";
    }

    /**
     * Progressive Cut-Through Streaming Input Stream modeled on Telegram's FileLoadOperation.
     * Allows media players (ExoPlayer, MediaPlayer, AudioTrack) to begin progressive
     * playback from offset 0 while remaining chunks stream over RTCDataChannel in the background.
     */
    public static class ProgressiveMediaInputStream extends InputStream {
        private final InboundTransfer transfer;
        private final RandomAccessFile streamRaf;
        private long currentPosition = 0;
        private final long totalLength;

        public ProgressiveMediaInputStream(InboundTransfer transfer) throws IOException {
            this.transfer = transfer;
            this.totalLength = transfer.totalBytes;
            this.streamRaf = new RandomAccessFile(transfer.tempFile, "r");
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int r = read(single, 0, 1);
            return (r == -1) ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            if (currentPosition >= totalLength) return -1;

            int requiredChunk = (int) (currentPosition / CHUNK_SIZE);

            // Wait for this chunk to arrive if not yet received
            long startTime = System.currentTimeMillis();
            while (!transfer.receivedBitSet.get(requiredChunk)) {
                if (transfer.isCompleted.get() && !transfer.receivedBitSet.get(requiredChunk)) {
                    return -1; // Transfer ended or aborted
                }
                if (System.currentTimeMillis() - startTime > 15_000L) {
                    throw new IOException("Progressive stream timed out waiting for chunk " + requiredChunk);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Progressive stream interrupted");
                }
            }

            // Read available contiguous slice within the chunk
            synchronized (transfer) {
                streamRaf.seek(currentPosition);
                long endOfChunk = (long) (requiredChunk + 1) * CHUNK_SIZE;
                long availableInChunk = endOfChunk - currentPosition;
                long remainingTotal = totalLength - currentPosition;
                int maxToRead = (int) Math.min(len, Math.min(availableInChunk, remainingTotal));

                int bytesRead = streamRaf.read(b, off, maxToRead);
                if (bytesRead > 0) {
                    currentPosition += bytesRead;
                }
                return bytesRead;
            }
        }

        @Override
        public int available() {
            int chunk = (int) (currentPosition / CHUNK_SIZE);
            int contiguousChunks = 0;
            while (chunk < transfer.totalChunks && transfer.receivedBitSet.get(chunk)) {
                contiguousChunks++;
                chunk++;
            }
            long availableBytes = ((long) contiguousChunks * CHUNK_SIZE);
            return (int) Math.min(availableBytes, Integer.MAX_VALUE);
        }

        @Override
        public void close() throws IOException {
            try {
                streamRaf.close();
            } catch (Exception ignored) {}
        }
    }

    public static InputStream getProgressiveStream(String transferId) {
        if (transferId == null) return null;
        InboundTransfer transfer = activeInboundTransfers.get(transferId);
        if (transfer == null || transfer.tempFile == null || !transfer.tempFile.exists()) {
            return null;
        }
        try {
            return new ProgressiveMediaInputStream(transfer);
        } catch (Exception e) {
            Log.e(TAG, "Failed creating ProgressiveMediaInputStream for " + transferId, e);
            return null;
        }
    }

    public static boolean isProgressivePlaybackReady(String transferId) {
        InboundTransfer transfer = activeInboundTransfers.get(transferId);
        if (transfer == null) return false;
        // Chunk 0 is required for container header metadata
        return transfer.receivedBitSet.get(0);
    }

    // ==========================================
    // OUTBOUND FILE TRANSMISSION
    // ==========================================

    public static void sendFileOverDataChannel(DataChannel dataChannel, File file, String mediaType, FileTransferCallback callback) {
        sendFileOverDataChannel(dataChannel, null, file, mediaType, "", callback);
    }

    public static void sendFileOverDataChannel(DataChannel dataChannel, String messageId, File file, String mediaType, FileTransferCallback callback) {
        sendFileOverDataChannel(dataChannel, messageId, file, mediaType, "", callback);
    }

    public static void sendFileOverDataChannel(DataChannel dataChannel, String messageId, File file, String mediaType, String caption, FileTransferCallback callback) {
        sendFileOverDataChannel(dataChannel, messageId, file, mediaType, caption, false, null, null, callback);
    }

    public static void sendFileOverDataChannel(DataChannel dataChannel, String messageId, File file, String mediaType,
                                              String caption, boolean isBlink, String thumbnail, String voiceFilter,
                                              FileTransferCallback callback) {
        sendFileOverDataChannelResumable(dataChannel, messageId, file, mediaType, caption, isBlink, thumbnail, voiceFilter, null, callback);
    }

    /**
     * Resumable file sender supporting missing chunk BitSet masks.
     */
    public static void sendFileOverDataChannelResumable(DataChannel dataChannel, String messageId, File file,
                                                       String mediaType, String caption, boolean isBlink,
                                                       String thumbnail, String voiceFilter, BitSet requestedMissingChunks,
                                                       FileTransferCallback callback) {
        if (dataChannel == null || file == null || !file.exists()) {
            if (callback != null) callback.onError("Invalid file or data channel");
            return;
        }

        transferExecutor.execute(() -> {
            RandomAccessFile raf = null;
            try {
                String transferId = (messageId != null && !messageId.isEmpty()) ? messageId : UUID.randomUUID().toString();
                long totalBytes = file.length();
                int totalChunks = (int) Math.ceil((double) totalBytes / CHUNK_SIZE);
                if (totalChunks == 0) totalChunks = 1;

                // Inline ~200-byte micro BlurHash preview header
                String microBlurHash = thumbnail;
                if ((microBlurHash == null || microBlurHash.isEmpty()) && mediaType != null &&
                        (mediaType.startsWith("image") || mediaType.startsWith("video"))) {
                    microBlurHash = generateMicroBlurHash(file, mediaType);
                }

                // 1. Send FILE_START Header (JSON + binary meta)
                JSONObject startJson = new JSONObject();
                startJson.put("action", "FILE_START");
                startJson.put("transferId", transferId);
                startJson.put("fileName", file.getName());
                startJson.put("mediaType", mediaType);
                startJson.put("caption", caption != null ? caption : "");
                startJson.put("totalBytes", totalBytes);
                startJson.put("totalChunks", totalChunks);
                startJson.put("blink", isBlink);
                startJson.put("thumbnail", microBlurHash != null ? microBlurHash : "");
                startJson.put("blurHash", microBlurHash != null ? microBlurHash : "");
                startJson.put("voiceFilter", voiceFilter != null ? voiceFilter : "");

                VeilPacket metaPacket = new VeilPacket(VeilPacket.LANE_MEDIA, VeilPacket.TYPE_FILE_META, startJson.toString());
                sendPacketOrFallback(dataChannel, metaPacket, startJson.toString());

                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress(1);
                });

                // 2. Stream Chunks using RandomAccessFile (supports selective resumption)
                raf = new RandomAccessFile(file, "r");
                byte[] buffer = VeilBufferPool.borrowByteArray16k();
                int chunksSent = 0;

                int lastReportedProgress = 1;
                try {
                    for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                        // If resuming, skip chunks already possessed by recipient
                        if (requestedMissingChunks != null && !requestedMissingChunks.get(chunkIndex)) {
                            continue;
                        }

                        // Sliding-window backpressure: wait until buffer drains below resume threshold
                        long maxBuf = getMaxBufferAmount();
                        long resumeBuf = Math.max(CHUNK_SIZE * 4L, maxBuf / 2);
                        if (dataChannel.bufferedAmount() > maxBuf) {
                            while (dataChannel.state() == DataChannel.State.OPEN && dataChannel.bufferedAmount() > resumeBuf) {
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException ignored) {}
                            }
                        }

                        if (dataChannel.state() != DataChannel.State.OPEN) {
                            throw new IOException("DataChannel closed during P2P file stream");
                        }

                        long offset = (long) chunkIndex * CHUNK_SIZE;
                        raf.seek(offset);
                        int bytesRead = raf.read(buffer);
                        if (bytesRead <= 0) break;

                        // Encode chunk into binary VeilPacket without extra array or Base64/JSON copy
                        byte[] chunkPayload = buildBinaryChunkPayload(transferId, chunkIndex, buffer, 0, bytesRead);
                        VeilPacket chunkPacket = new VeilPacket(VeilPacket.LANE_MEDIA, VeilPacket.TYPE_FILE_CHUNK, chunkPayload);
                        sendPacketOrFallback(dataChannel, chunkPacket, null);
                        chunksSent++;

                        final int progress = Math.max(1, (int) (((double) (chunkIndex + 1) / totalChunks) * 100));
                        if (progress > lastReportedProgress) {
                            lastReportedProgress = progress;
                            mainHandler.post(() -> {
                                if (callback != null) callback.onProgress(progress);
                            });
                        }
                    }
                } finally {
                    VeilBufferPool.recycleByteArray(buffer);
                }

                // 3. Send FILE_END Header
                JSONObject endJson = new JSONObject();
                endJson.put("action", "FILE_END");
                endJson.put("transferId", transferId);
                VeilPacket endPacket = new VeilPacket(VeilPacket.LANE_MEDIA, VeilPacket.TYPE_FILE_END, endJson.toString());
                sendPacketOrFallback(dataChannel, endPacket, endJson.toString());

                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onProgress(100);
                        callback.onComplete(transferId);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "File transfer transmission error", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            } finally {
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private static void sendPacketOrFallback(DataChannel dataChannel, VeilPacket packet, String fallbackJson) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return;
        try {
            // First attempt binary packet delivery
            byte[] bytes = packet.serialize();
            dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
        } catch (Throwable t) {
            // Fallback to text JSON if provided
            if (fallbackJson != null) {
                byte[] fallbackBytes = fallbackJson.getBytes(StandardCharsets.UTF_8);
                dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(fallbackBytes), false));
            }
        }
    }

    private static byte[] buildBinaryChunkPayload(String transferId, int chunkIndex, byte[] chunkData) {
        return buildBinaryChunkPayload(transferId, chunkIndex, chunkData, 0, chunkData.length);
    }

    private static byte[] buildBinaryChunkPayload(String transferId, int chunkIndex, byte[] chunkData, int offset, int length) {
        byte[] idBytes = transferId.getBytes(StandardCharsets.UTF_8);
        int totalLen = 2 + idBytes.length + 4 + 4 + length;
        byte[] payload = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        buf.putInt(chunkIndex);
        buf.putInt(length);
        buf.put(chunkData, offset, length);
        return payload;
    }

    // ==========================================
    // INBOUND RECEIVER (Telegram-Grade RAF & BitSet)
    // ==========================================

    public static void handleIncomingPacket(Context context, String peerId, VeilPacket packet, OnFileReceivedListener listener) {
        if (packet == null) return;
        short type = packet.getPacketType();

        if (type == VeilPacket.TYPE_FILE_META) {
            handleFileStart(context, peerId, packet.getPayloadAsString());
        } else if (type == VeilPacket.TYPE_FILE_CHUNK) {
            handleBinaryFileChunk(context, peerId, packet.getPayload(), listener);
        } else if (type == VeilPacket.TYPE_FILE_END) {
            handleFileEnd(context, peerId, packet.getPayloadAsString(), listener);
        } else if (type == VeilPacket.TYPE_FILE_REQUEST) {
            handleFileRequest(context, packet.getPayloadAsString());
        } else if (type == VeilPacket.TYPE_FILE_CANCEL) {
            handleFileCancel(packet.getPayloadAsString());
        }
    }

    public static void handleIncomingPayload(Context context, String payload, OnFileReceivedListener listener) {
        try {
            JSONObject json = new JSONObject(payload);
            String action = json.optString("action");

            if ("FILE_START".equals(action)) {
                handleFileStart(context, null, payload);
            } else if ("FILE_CHUNK".equals(action)) {
                handleJsonFileChunk(context, json, listener);
            } else if ("FILE_END".equals(action)) {
                handleFileEnd(context, null, payload, listener);
            } else if ("FILE_REQUEST".equals(action)) {
                handleFileRequest(context, payload);
            } else if ("FILE_CANCEL".equals(action)) {
                handleFileCancel(payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming payload", e);
        }
    }

    private static void handleFileStart(Context context, String peerId, String metaPayload) {
        try {
            JSONObject json = new JSONObject(metaPayload);
            String transferId = json.getString("transferId");
            String fileName = json.getString("fileName");
            String mediaType = json.getString("mediaType");
            String caption = json.optString("caption", "");
            long totalBytes = json.getLong("totalBytes");
            int totalChunks = json.getInt("totalChunks");
            if (totalChunks <= 0) totalChunks = 1;

            InboundTransfer transfer = activeInboundTransfers.get(transferId);
            if (transfer == null) {
                transfer = new InboundTransfer();
                transfer.transferId = transferId;
                transfer.fileName = fileName;
                transfer.mediaType = mediaType;
                transfer.caption = caption;
                transfer.totalBytes = totalBytes;
                transfer.totalChunks = totalChunks;
                transfer.peerId = peerId;

                File cacheDir = new File(context.getCacheDir(), "p2p_incoming");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                transfer.tempFile = new File(cacheDir, "p2p_" + transferId + "_" + fileName);

                // Resume existing partial transfer if recorded in database
                BitSet savedBitSet = EncryptedChatDatabase.getInstance(context).getFileChunkBitmap(transferId);
                if (savedBitSet != null && savedBitSet.cardinality() > 0 && transfer.tempFile.exists()) {
                    transfer.receivedBitSet = savedBitSet;
                } else {
                    EncryptedChatDatabase.FileTransferRecord existingRecord =
                            EncryptedChatDatabase.getInstance(context).getFileTransfer(transferId);
                    if (existingRecord != null && existingRecord.bitsetBytes != null && transfer.tempFile.exists()) {
                        transfer.receivedBitSet = BitSet.valueOf(existingRecord.bitsetBytes);
                    } else {
                        transfer.receivedBitSet = new BitSet(totalChunks);
                    }
                }

                transfer.raf = new RandomAccessFile(transfer.tempFile, "rw");
                // Pre-allocate full file length (Telegram style)
                if (transfer.raf.length() < totalBytes) {
                    transfer.raf.setLength(totalBytes);
                }

                activeInboundTransfers.put(transferId, transfer);

                // Persist transfer record
                EncryptedChatDatabase.FileTransferRecord record = new EncryptedChatDatabase.FileTransferRecord();
                record.transferId = transferId;
                record.messageId = transferId;
                record.filePath = transfer.tempFile.getAbsolutePath();
                record.fileName = fileName;
                record.mimeType = mediaType;
                record.totalBytes = totalBytes;
                record.chunkSize = CHUNK_SIZE;
                record.totalChunks = totalChunks;
                record.bitsetBytes = transfer.receivedBitSet.toByteArray();
                record.isUpload = false;
                record.status = "IN_PROGRESS";
                record.peerId = peerId;
                EncryptedChatDatabase.getInstance(context).saveOrUpdateFileTransfer(record);

                try {
                    com.m3.veil.model.Message cMsg = com.m3.veil.manager.VeilMessagesController.getInstance(context).getMessage(transferId);
                    String cDialogId = cMsg != null ? cMsg.getChatId() : null;
                    if (downloadListeners.containsKey(transferId)
                            || com.m3.veil.manager.VeilMessagesController.shouldAutoDownloadMedia(context, cDialogId, mediaType)) {
                        com.m3.veil.manager.VeilMessagesController.getInstance(context)
                                .notifyGlobalMediaTransferProgress(cDialogId, transferId, 1);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling FILE_START", e);
        }
    }

    private static void handleBinaryFileChunk(Context context, String peerId, byte[] payload, OnFileReceivedListener listener) {
        if (payload == null || payload.length < 10) return;
        try {
            ByteBuffer buf = ByteBuffer.wrap(payload);
            buf.order(ByteOrder.BIG_ENDIAN);

            short idLen = buf.getShort();
            if (idLen <= 0 || buf.remaining() < idLen + 8) return;

            byte[] idBytes = new byte[idLen];
            buf.get(idBytes);
            String transferId = new String(idBytes, StandardCharsets.UTF_8);

            int chunkIndex = buf.getInt();
            int chunkLen = buf.getInt();
            if (chunkLen <= 0 || buf.remaining() < chunkLen) return;

            int chunkOffset = buf.position();
            // Zero-GC: stream directly from binary payload slice without allocating new byte[chunkLen]
            writeChunkToTransfer(context, transferId, chunkIndex, payload, chunkOffset, chunkLen, listener);
        } catch (Exception e) {
            Log.e(TAG, "Error handling binary file chunk", e);
        }
    }

    private static void handleJsonFileChunk(Context context, JSONObject json, OnFileReceivedListener listener) {
        try {
            String transferId = json.getString("transferId");
            int chunkIndex = json.getInt("chunkIndex");
            String base64Data = json.getString("data");
            byte[] chunkData = Base64.decode(base64Data, Base64.NO_WRAP);

            writeChunkToTransfer(context, transferId, chunkIndex, chunkData, 0, chunkData.length, listener);
        } catch (Exception e) {
            Log.e(TAG, "Error handling JSON file chunk", e);
        }
    }

    private static void writeChunkToTransfer(Context context, String transferId, int chunkIndex, byte[] chunkData, OnFileReceivedListener listener) {
        writeChunkToTransfer(context, transferId, chunkIndex, chunkData, 0, chunkData.length, listener);
    }

    private static void writeChunkToTransfer(Context context, String transferId, int chunkIndex, byte[] chunkData, int offset, int length, OnFileReceivedListener listener) {
        InboundTransfer transfer = activeInboundTransfers.get(transferId);
        if (transfer == null || transfer.raf == null) return;

        try {
            synchronized (transfer) {
                if (transfer.isCompleted.get()) return;

                long seekOffset = (long) chunkIndex * CHUNK_SIZE;
                transfer.raf.seek(seekOffset);
                transfer.raf.write(chunkData, offset, length);
                transfer.receivedBitSet.set(chunkIndex);

                int receivedCount = transfer.receivedBitSet.cardinality();
                final int percent = Math.max(1, (int) (((double) receivedCount / transfer.totalChunks) * 100));

                // Update BitSet in DB periodically every 8 chunks or on progress update
                if (chunkIndex % 8 == 0 || receivedCount == transfer.totalChunks) {
                    EncryptedChatDatabase.getInstance(context).updateFileTransferBitSet(
                            transferId,
                            transfer.receivedBitSet.toByteArray(),
                            receivedCount == transfer.totalChunks ? "COMPLETED" : "IN_PROGRESS"
                    );
                    EncryptedChatDatabase.getInstance(context).saveFileChunkBitmap(
                            transferId,
                            transfer.receivedBitSet,
                            receivedCount,
                            transfer.totalChunks
                    );
                }

                if (percent > transfer.lastReportedPercent) {
                    transfer.lastReportedPercent = percent;
                    DownloadProgressListener dl = downloadListeners.get(transferId);
                    if (dl != null) {
                        mainHandler.post(() -> dl.onProgress(transferId, percent));
                    }
                    try {
                        com.m3.veil.model.Message cMsg = com.m3.veil.manager.VeilMessagesController.getInstance(context).getMessage(transferId);
                        String cDialogId = cMsg != null ? cMsg.getChatId() : null;
                        if (dl != null || com.m3.veil.manager.VeilMessagesController.shouldAutoDownloadMedia(context, cDialogId, transfer.mediaType)) {
                            com.m3.veil.manager.VeilMessagesController.getInstance(context)
                                    .notifyGlobalMediaTransferProgress(cDialogId, transferId, percent);
                        }
                    } catch (Throwable ignored) {}
                }

                if (receivedCount >= transfer.totalChunks) {
                    completeTransfer(context, transfer, listener);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing chunk " + chunkIndex + " for transfer " + transferId, e);
        }
    }

    private static void handleFileEnd(Context context, String peerId, String endPayload, OnFileReceivedListener listener) {
        try {
            JSONObject json = new JSONObject(endPayload);
            String transferId = json.getString("transferId");
            InboundTransfer transfer = activeInboundTransfers.get(transferId);
            if (transfer != null) {
                synchronized (transfer) {
                    if (transfer.receivedBitSet.cardinality() >= transfer.totalChunks) {
                        completeTransfer(context, transfer, listener);
                    } else if (transfer.tailRetryCount < 3) {
                        transfer.tailRetryCount++;
                        String targetPeer = peerId != null ? peerId : transfer.peerId;
                        if (targetPeer != null && context != null) {
                            DataChannel dc = WebRtcManager.getInstance(context).getChannelForLane(targetPeer, VeilPacket.LANE_MEDIA);
                            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                                Log.i(TAG, "Tail recovery (#" + transfer.tailRetryCount + "): requesting "
                                        + (transfer.totalChunks - transfer.receivedBitSet.cardinality())
                                        + " missing chunks for " + transferId);
                                requestFileWithResume(context, dc, transferId);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling FILE_END", e);
        }
    }

    private static void completeTransfer(Context context, InboundTransfer transfer, OnFileReceivedListener listener) {
        if (!transfer.isCompleted.compareAndSet(false, true)) return;
        activeInboundTransfers.remove(transfer.transferId);

        try {
            if (transfer.raf != null) {
                transfer.raf.close();
            }
        } catch (Exception ignored) {}

        String ext = "";
        if ("image".equals(transfer.mediaType)) {
            ext = ".jpg";
        } else if ("video".equals(transfer.mediaType)) {
            ext = ".mp4";
        } else if ("voice".equals(transfer.mediaType) || "audio".equals(transfer.mediaType)) {
            ext = ".m4a";
        } else if (transfer.fileName != null) {
            String cleanName = com.m3.veil.manager.VeilMessagesController.cleanDocumentFileName(transfer.fileName);
            int dot = cleanName.lastIndexOf('.');
            if (dot != -1 && dot < cleanName.length() - 1) {
                ext = cleanName.substring(dot);
            }
        }

        com.m3.veil.model.Message cachedMsg = null;
        try {
            cachedMsg = com.m3.veil.manager.VeilMessagesController.getInstance(context).getMessage(transfer.transferId);
            if (cachedMsg == null) {
                cachedMsg = EncryptedChatDatabase.getInstance(context).getMessageById(transfer.transferId);
            }
        } catch (Throwable ignored) {}
        String resolvedChatId = cachedMsg != null ? cachedMsg.getChatId() : null;
        boolean hasExplicitUserDownload = downloadListeners.containsKey(transfer.transferId);
        boolean allowAutoSave = hasExplicitUserDownload
                || com.m3.veil.manager.VeilMessagesController.shouldAutoDownloadMedia(context, resolvedChatId, transfer.mediaType);

        String targetPrefix = allowAutoSave ? "dec_" : "staged_";
        File targetDest = new File(context.getExternalCacheDir(), targetPrefix + transfer.transferId + ext);
        try {
            if (transfer.tempFile.exists()) {
                if (targetDest.exists()) targetDest.delete();
                if (!transfer.tempFile.renameTo(targetDest)) {
                    try (FileInputStream in = new FileInputStream(transfer.tempFile);
                         FileOutputStream out = new FileOutputStream(targetDest)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                        }
                    }
                    transfer.tempFile.delete();
                }
            }
        } catch (Exception ignored) {}

        final File finalFile = targetDest.exists() ? targetDest : transfer.tempFile;

        // Update database record to COMPLETED
        EncryptedChatDatabase.getInstance(context).updateFileTransferBitSet(
                transfer.transferId,
                transfer.receivedBitSet.toByteArray(),
                "COMPLETED"
        );
        EncryptedChatDatabase.getInstance(context).saveFileChunkBitmap(
                transfer.transferId,
                transfer.receivedBitSet,
                transfer.totalChunks,
                transfer.totalChunks
        );

        try {
            if (cachedMsg != null && finalFile != null && finalFile.exists()) {
                cachedMsg.setMediaSize(finalFile.length());
                if (allowAutoSave) {
                    cachedMsg.setLocalFilePath(finalFile.getAbsolutePath());
                    com.m3.veil.manager.VeilMessagesController.exportMediaToPublicStorageIfAllowed(
                            context, resolvedChatId, cachedMsg, finalFile);
                }
            }
            if (allowAutoSave) {
                com.m3.veil.manager.VeilMessagesController.getInstance(context)
                        .notifyGlobalMediaTransferProgress(resolvedChatId, transfer.transferId, 100);
            }
        } catch (Throwable ignored) {}

        DownloadProgressListener dl = downloadListeners.remove(transfer.transferId);
        if (dl != null) {
            mainHandler.post(() -> dl.onComplete(transfer.transferId, finalFile));
        }

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onFileReceived(transfer.transferId, transfer.fileName, transfer.mediaType, transfer.caption, finalFile);
            }
        });
    }

    private static void handleFileRequest(Context context, String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String transferId = json.optString("transferId", json.optString("messageId"));
            String receivedB64 = json.optString("receivedBitSetBase64", "");
            int totalChunks = json.optInt("totalChunks", 0);

            BitSet missingChunks = null;
            if (receivedB64 != null && !receivedB64.isEmpty() && totalChunks > 0) {
                try {
                    byte[] rawBits = Base64.decode(receivedB64, Base64.NO_WRAP);
                    BitSet received = BitSet.valueOf(rawBits);
                    missingChunks = new BitSet(totalChunks);
                    for (int i = 0; i < totalChunks; i++) {
                        if (!received.get(i)) {
                            missingChunks.set(i);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            final BitSet finalMissing = missingChunks;
            if (fileRequestListener != null) {
                mainHandler.post(() -> {
                    if (finalMissing != null && !finalMissing.isEmpty()) {
                        fileRequestListener.onFileRequestedResumable(transferId, finalMissing);
                    } else {
                        fileRequestListener.onFileRequested(transferId);
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private static void handleFileCancel(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String transferId = json.optString("transferId", json.optString("messageId"));
            InboundTransfer transfer = activeInboundTransfers.remove(transferId);
            if (transfer != null) {
                try {
                    if (transfer.raf != null) transfer.raf.close();
                    if (transfer.tempFile != null && transfer.tempFile.exists()) transfer.tempFile.delete();
                } catch (Exception ignored) {}
            }
            DownloadProgressListener dl = downloadListeners.remove(transferId);
            if (dl != null) {
                mainHandler.post(() -> dl.onError(transferId, "Canceled by peer"));
            }
            if (fileRequestListener != null) {
                mainHandler.post(() -> fileRequestListener.onFileTransferCanceled(transferId));
            }
        } catch (Exception ignored) {}
    }

    public static void requestFile(DataChannel dataChannel, String messageId) {
        requestFileWithResume(com.m3.veil.VeilApplication.getInstance(), dataChannel, messageId);
    }

    /**
     * Requests a file from the remote peer, automatically attaching a received-chunk BitSet mask
     * if partial chunks already exist on disk so interrupted large transfers resume from the exact offset.
     */
    public static void requestFileWithResume(Context context, DataChannel dataChannel, String messageId) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN || messageId == null) return;
        try {
            JSONObject req = new JSONObject();
            req.put("action", "FILE_REQUEST");
            req.put("transferId", messageId);
            req.put("messageId", messageId);

            InboundTransfer active = activeInboundTransfers.get(messageId);
            if (active != null && active.receivedBitSet != null && active.receivedBitSet.cardinality() > 0
                    && active.tempFile != null && active.tempFile.exists() && active.totalChunks > 0) {
                String b64 = Base64.encodeToString(active.receivedBitSet.toByteArray(), Base64.NO_WRAP);
                req.put("receivedBitSetBase64", b64);
                req.put("totalChunks", active.totalChunks);
            } else if (context != null) {
                EncryptedChatDatabase.FileTransferRecord rec =
                        EncryptedChatDatabase.getInstance(context).getFileTransfer(messageId);
                if (rec != null && rec.bitsetBytes != null && rec.bitsetBytes.length > 0
                        && rec.filePath != null && new File(rec.filePath).exists() && rec.totalChunks > 0) {
                    BitSet saved = BitSet.valueOf(rec.bitsetBytes);
                    if (saved.cardinality() > 0 && saved.cardinality() < rec.totalChunks) {
                        String b64 = Base64.encodeToString(rec.bitsetBytes, Base64.NO_WRAP);
                        req.put("receivedBitSetBase64", b64);
                        req.put("totalChunks", rec.totalChunks);
                    }
                }
            }

            VeilPacket packet = new VeilPacket(VeilPacket.LANE_MEDIA, VeilPacket.TYPE_FILE_REQUEST, req.toString());
            sendPacketOrFallback(dataChannel, packet, req.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending resumable FILE_REQUEST", e);
        }
    }

    /**
     * Automatically resumes any incomplete inbound transfers with a peer when the P2P DataChannel
     * reconnects (e.g., after a Wi-Fi <-> 5G handoff).
     */
    public static void resumeIncompleteTransfersForPeer(Context context, String peerId, DataChannel dataChannel) {
        if (peerId == null || dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return;
        transferExecutor.execute(() -> {
            try {
                for (InboundTransfer transfer : activeInboundTransfers.values()) {
                    if (transfer != null && !transfer.isCompleted.get()
                            && peerId.equals(transfer.peerId)
                            && transfer.receivedBitSet != null
                            && transfer.receivedBitSet.cardinality() < transfer.totalChunks) {
                        Log.i(TAG, "Auto-resuming interrupted inbound file transfer " + transfer.transferId
                                + " from chunk progress " + transfer.receivedBitSet.cardinality() + "/" + transfer.totalChunks);
                        requestFileWithResume(context, dataChannel, transfer.transferId);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error resuming incomplete transfers for peer " + peerId + ": " + t.getMessage());
            }
        });
    }

    public static void cancelFileTransfer(DataChannel dataChannel, String messageId) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return;
        try {
            JSONObject cancel = new JSONObject();
            cancel.put("action", "FILE_CANCEL");
            cancel.put("transferId", messageId);
            VeilPacket packet = new VeilPacket(VeilPacket.LANE_MEDIA, VeilPacket.TYPE_FILE_CANCEL, cancel.toString());
            sendPacketOrFallback(dataChannel, packet, cancel.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending FILE_CANCEL", e);
        }
    }
}
