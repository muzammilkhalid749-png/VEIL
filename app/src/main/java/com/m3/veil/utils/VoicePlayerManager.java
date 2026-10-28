package com.m3.veil.utils;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.m3.veil.model.Message;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Professional WhatsApp & Telegram style Voice Player Manager.
 * Features:
 * - Deterministic, non-overlapping single-audio playback.
 * - Multi-speed playback (1.0x, 1.5x, 2.0x) with seamless runtime switching.
 * - Precision waveform seek-to-fraction and position tracking.
 * - Metadata duration cache for instant, zero-latency duration display.
 */
public class VoicePlayerManager {
    private static final String TAG = "VoicePlayerManager";
    private static VoicePlayerManager instance;
    private MediaPlayer mediaPlayer;
    private String currentlyPlayingMessageId = null;
    private float currentSpeed = 1.0f; // 1.0f, 1.5f, 2.0f

    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    // Callbacks to update UI in adapter
    private Map<String, PlaybackListener> listeners = new HashMap<>();
    private Map<String, Integer> durationCache = new HashMap<>();
    private OnVoiceCompletionQueueListener completionQueueListener;

    public interface PlaybackListener {
        void onStateChanged(boolean isPlaying);
        void onProgressUpdate(int currentPosition, int maxDuration);
        default void onSpeedChanged(float speed) {}
    }

    public interface OnVoiceCompletionQueueListener {
        void onVoiceNoteCompleted(String completedMessageId);
    }

    public void setOnVoiceCompletionQueueListener(OnVoiceCompletionQueueListener listener) {
        this.completionQueueListener = listener;
    }

    public static synchronized VoicePlayerManager getInstance() {
        if (instance == null) {
            instance = new VoicePlayerManager();
        }
        return instance;
    }

    private VoicePlayerManager() {
        mediaPlayer = new MediaPlayer();
    }

    public void registerListener(String messageId, PlaybackListener listener) {
        listeners.put(messageId, listener);
    }

    public void unregisterListener(String messageId) {
        listeners.remove(messageId);
    }

    public float getSpeed() {
        return currentSpeed;
    }

    public float cycleSpeed() {
        if (currentSpeed == 1.0f) {
            currentSpeed = 1.5f;
        } else if (currentSpeed == 1.5f) {
            currentSpeed = 2.0f;
        } else {
            currentSpeed = 1.0f;
        }

        applySpeedToPlayer();
        notifySpeedChanged(currentSpeed);
        return currentSpeed;
    }

    private void applySpeedToPlayer() {
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (mediaPlayer.isPlaying()) {
                    PlaybackParams params = mediaPlayer.getPlaybackParams();
                    params.setSpeed(currentSpeed);
                    mediaPlayer.setPlaybackParams(params);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to apply speed to player: " + e.getMessage());
            }
        }
    }

    private void notifySpeedChanged(float speed) {
        for (PlaybackListener listener : listeners.values()) {
            if (listener != null) {
                listener.onSpeedChanged(speed);
            }
        }
    }

    public void cacheDuration(String messageId, int durationMs) {
        if (messageId != null && durationMs > 0) {
            durationCache.put(messageId, durationMs);
        }
    }

    public static String extractBaseVoiceFilter(String rawFilter) {
        if (rawFilter == null || rawFilter.isEmpty()) return "normal";
        int pipeIdx = rawFilter.indexOf('|');
        return pipeIdx >= 0 ? rawFilter.substring(0, pipeIdx) : rawFilter;
    }

    public static int extractEmbeddedDurationMs(Message message) {
        if (message == null) return 0;
        String vf = message.getVoiceFilter();
        if (vf != null && vf.contains("|dur=")) {
            try {
                int idx = vf.indexOf("|dur=") + 5;
                int end = vf.indexOf('|', idx);
                String num = end >= 0 ? vf.substring(idx, end) : vf.substring(idx);
                int parsed = Integer.parseInt(num.trim());
                if (parsed > 0) return parsed;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public void playVoiceNote(Context context, Message message, String rsaPrivateKeyString, String currentUserId) {
        if (currentlyPlayingMessageId != null && currentlyPlayingMessageId.equals(message.getId()) && mediaPlayer.isPlaying()) {
            pausePlayback();
            return;
        }

        File localAudio = null;
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File f = new File(message.getLocalFilePath());
            if (f.exists() && f.length() >= 64) localAudio = f;
        }
        if (localAudio == null && context != null) {
            File cacheDir = context.getExternalCacheDir();
            File decryptedFile = new File(cacheDir, "dec_" + message.getId() + ".m4a");
            if (decryptedFile.exists() && decryptedFile.length() >= 64) localAudio = decryptedFile;
        }
        if (localAudio == null && context != null) {
            localAudio = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(context, message.getChatId(), message);
        }

        if (localAudio != null && localAudio.exists() && localAudio.length() >= 64) {
            boolean started = startPlaying(context, message, localAudio.getAbsolutePath(), message.getVoiceFilter());
            if (started) {
                try {
                    com.m3.veil.manager.VeilMessagesController.getInstance(context).markMessageAsReadExplicitly(message);
                } catch (Throwable ignored) {}
                return;
            }
        }

        if (context != null && message != null && message.getId() != null) {
            Toast.makeText(context, "Downloading voice note...", Toast.LENGTH_SHORT).show();
            String peerId = message.getSenderId();
            com.m3.veil.webrtc.WebRtcManager.getInstance(context).requestMediaDownload(
                    peerId,
                    message.getId(),
                    new com.m3.veil.webrtc.P2PFileTransferHelper.DownloadProgressListener() {
                        @Override
                        public void onProgress(String transferId, int percent) {}

                        @Override
                        public void onComplete(String transferId, File file) {
                            if (file != null && file.exists() && file.length() >= 64) {
                                message.setLocalFilePath(file.getAbsolutePath());
                                progressHandler.post(() -> {
                                    startPlaying(context, message, file.getAbsolutePath(), message.getVoiceFilter());
                                    try {
                                        com.m3.veil.manager.VeilMessagesController.getInstance(context).markMessageAsReadExplicitly(message);
                                    } catch (Throwable ignored) {}
                                });
                            }
                        }

                        @Override
                        public void onError(String transferId, String error) {}
                    }
            );
        }
    }

    private boolean startPlaying(Context context, Message message, String filePath, String rawVoiceFilter) {
        String messageId = message != null ? message.getId() : null;
        String voiceFilter = extractBaseVoiceFilter(rawVoiceFilter);
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                notifyStateChanged(currentlyPlayingMessageId, false);
            }
            mediaPlayer.reset();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();

            int actualDur = mediaPlayer.getDuration();
            if (messageId != null && actualDur > 0) {
                durationCache.put(messageId, actualDur);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    PlaybackParams params = mediaPlayer.getPlaybackParams();
                    params.setSpeed(currentSpeed);
                    float pitch = 1.0f;
                    if ("deep".equalsIgnoreCase(voiceFilter)) pitch = 0.72f;
                    else if ("chipmunk".equalsIgnoreCase(voiceFilter)) pitch = 1.48f;
                    else if ("robot".equalsIgnoreCase(voiceFilter)) pitch = 0.84f;
                    params.setPitch(pitch);
                    mediaPlayer.setPlaybackParams(params);
                } catch (Exception ignored) {}
            }

            mediaPlayer.start();

            currentlyPlayingMessageId = messageId;
            notifyStateChanged(messageId, true);

            mediaPlayer.setOnCompletionListener(mp -> {
                String completedId = currentlyPlayingMessageId;
                notifyStateChanged(completedId, false);
                currentlyPlayingMessageId = null;
                if (progressHandler != null && progressRunnable != null) {
                    progressHandler.removeCallbacks(progressRunnable);
                }
                if (completedId != null && completionQueueListener != null) {
                    completionQueueListener.onVoiceNoteCompleted(completedId);
                }
            });

            startProgressUpdates();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Playback failed for " + filePath + ", self-healing corrupt cache file", e);
            try {
                mediaPlayer.reset();
            } catch (Exception ignored) {}
            // Delete corrupt dec_ cache file so it can be re-decoded or re-downloaded cleanly
            if (filePath != null && filePath.contains("dec_")) {
                try {
                    File badFile = new File(filePath);
                    if (badFile.exists()) badFile.delete();
                } catch (Exception ignored) {}
            }
            if (message != null) {
                message.setLocalFilePath(null);
            }
            return false;
        }
    }

    public void pausePlayback() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            notifyStateChanged(currentlyPlayingMessageId, false);
            if (progressHandler != null && progressRunnable != null) {
                progressHandler.removeCallbacks(progressRunnable);
            }
        }
    }

    public void stopPlayback() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            notifyStateChanged(currentlyPlayingMessageId, false);
            currentlyPlayingMessageId = null;
            if (progressHandler != null && progressRunnable != null) {
                progressHandler.removeCallbacks(progressRunnable);
            }
        }
    }

    private void startProgressUpdates() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }

        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying() && currentlyPlayingMessageId != null) {
                    PlaybackListener listener = listeners.get(currentlyPlayingMessageId);
                    if (listener != null) {
                        listener.onProgressUpdate(mediaPlayer.getCurrentPosition(), mediaPlayer.getDuration());
                    }
                    progressHandler.postDelayed(this, 50);
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private void notifyStateChanged(String messageId, boolean isPlaying) {
        if (messageId != null && listeners.containsKey(messageId)) {
            listeners.get(messageId).onStateChanged(isPlaying);
        }
    }

    public boolean isPlaying(String messageId) {
        return messageId != null && messageId.equals(currentlyPlayingMessageId) && mediaPlayer.isPlaying();
    }

    public String getCurrentlyPlayingMessageId() {
        return currentlyPlayingMessageId;
    }

    public void seekTo(int position) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(position);
        }
    }

    public void seekToFraction(float fraction) {
        if (mediaPlayer != null) {
            int duration = mediaPlayer.getDuration();
            if (duration > 0) {
                int target = (int) (fraction * duration);
                mediaPlayer.seekTo(target);
            }
        }
    }

    private final java.util.concurrent.ExecutorService durationExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "VeilAudioDurationPool");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.setDaemon(true);
                return t;
            });
    private final java.util.Set<String> pendingDurationLookups =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public interface DurationCallback {
        void onDurationLoaded(int durationMs);
    }

    /**
     * 0ms lock-free main-thread duration lookup. Never opens files or creates MediaMetadataRetriever.
     */
    public int getCachedAudioDuration(Message message) {
        if (message == null || message.getId() == null) return 0;
        Integer cached = durationCache.get(message.getId());
        if (cached != null && cached > 0) {
            return cached;
        }
        int embeddedDur = extractEmbeddedDurationMs(message);
        if (embeddedDur > 0) {
            durationCache.put(message.getId(), embeddedDur);
            return embeddedDur;
        }
        return 0;
    }

    /**
     * Asynchronously extracts audio/video duration on a background worker thread so RecyclerView
     * binding never blocks the UI thread on MediaMetadataRetriever Binder IPC.
     */
    public void loadAudioDurationAsync(final Context context, final Message message, final DurationCallback callback) {
        if (message == null || message.getId() == null) return;
        int fast = getCachedAudioDuration(message);
        if (fast > 0) {
            if (callback != null) callback.onDurationLoaded(fast);
            return;
        }
        final String msgId = message.getId();
        if (!pendingDurationLookups.add(msgId)) {
            return;
        }
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        durationExecutor.execute(() -> {
            try {
                int dur = getAudioDuration(appCtx, message);
                if (dur > 0 && callback != null) {
                    progressHandler.post(() -> callback.onDurationLoaded(dur));
                }
            } finally {
                pendingDurationLookups.remove(msgId);
            }
        });
    }

    public int getAudioDuration(Context context, Message message) {
        if (message == null || message.getId() == null) return 0;
        Integer cached = durationCache.get(message.getId());
        if (cached != null && cached > 0) {
            return cached;
        }

        int embeddedDur = extractEmbeddedDurationMs(message);
        if (embeddedDur > 0) {
            durationCache.put(message.getId(), embeddedDur);
            return embeddedDur;
        }

        File local = null;
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File f = new File(message.getLocalFilePath());
            if (f.exists() && f.length() >= 64) local = f;
        }
        if (local == null && context != null) {
            File cacheDir = context.getExternalCacheDir();
            String ext = "video".equals(message.getType()) ? ".mp4" : ".m4a";
            File dec = new File(cacheDir, "dec_" + message.getId() + ext);
            if (dec.exists() && dec.length() >= 64) local = dec;
        }

        if (local != null && local.exists()) {
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(local.getAbsolutePath());
                String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durStr != null) {
                    int duration = Integer.parseInt(durStr);
                    if (duration > 0) {
                        durationCache.put(message.getId(), duration);
                        return duration;
                    }
                }
            } catch (Exception e) {
                // Corrupt local dec_ file: remove so it can be re-fetched cleanly
                if (local.getName().startsWith("dec_")) {
                    try { local.delete(); } catch (Exception ignored) {}
                }
            } finally {
                if (mmr != null) {
                    try {
                        mmr.release();
                    } catch (Exception ignored) {}
                }
            }
        }
        return 0;
    }

    public static String formatDuration(int millis) {
        if (millis < 0) millis = 0;
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }
}
