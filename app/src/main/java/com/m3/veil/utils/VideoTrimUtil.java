package com.m3.veil.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;

public final class VideoTrimUtil {
    private static final String TAG = "VideoTrimUtil";
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // 1MB

    private VideoTrimUtil() {}

    /**
     * Losslessly trims a video between startMs and endMs without re-encoding.
     * @param context Android context
     * @param srcUri Source video URI
     * @param destFile Destination output File (.mp4)
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @return true if successful, false otherwise
     */
    public static boolean trimVideo(Context context, Uri srcUri, File destFile, long startMs, long endMs) {
        if (context == null || srcUri == null || destFile == null || startMs >= endMs) {
            return false;
        }

        long startUs = startMs * 1000L;
        long endUs = endMs * 1000L;

        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;

        try {
            extractor.setDataSource(context, srcUri, null);
            int trackCount = extractor.getTrackCount();

            muxer = new MediaMuxer(destFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            HashMap<Integer, Integer> indexMap = new HashMap<>(trackCount);
            int bufferSize = DEFAULT_BUFFER_SIZE;

            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i);
                    int dstIndex = muxer.addTrack(format);
                    indexMap.put(i, dstIndex);

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        int newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                        if (newSize > bufferSize) {
                            bufferSize = newSize;
                        }
                    }
                }
            }

            if (indexMap.isEmpty()) {
                return false;
            }

            muxer.start();

            // Seek to start position
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int trackIndex = extractor.getSampleTrackIndex();
                if (trackIndex < 0) {
                    break;
                }

                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs > endUs) {
                    break;
                }

                if (sampleTimeUs >= startUs) {
                    bufferInfo.offset = 0;
                    bufferInfo.size = extractor.readSampleData(buffer, 0);
                    if (bufferInfo.size < 0) {
                        break;
                    }

                    bufferInfo.presentationTimeUs = Math.max(0, sampleTimeUs - startUs);
                    bufferInfo.flags = extractor.getSampleFlags();

                    Integer dstIndex = indexMap.get(trackIndex);
                    if (dstIndex != null) {
                        muxer.writeSampleData(dstIndex, buffer, bufferInfo);
                    }
                }

                extractor.advance();
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to trim video", e);
            return false;
        } finally {
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {}
            extractor.release();
        }
    }
}
