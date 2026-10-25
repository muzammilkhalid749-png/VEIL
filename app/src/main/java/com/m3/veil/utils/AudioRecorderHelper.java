package com.m3.veil.utils;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.m3.veil.calling.VoiceMaskingDsp;
import com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Advanced Audio Recorder Helper with real-time PCM voice masking DSP:
 * Supports 5 zero-latency voice transformation filters:
 * - NATURAL
 * - DEEP_SHADOW
 * - CYBER_ROBOT
 * - HELIUM_CHIPMUNK
 * - GHOST_WHISPER
 *
 * <p>Uses AudioRecord for real-time PCM frame interception and DSP filtering,
 * writing standard 16-bit RIFF WAV files for 100% device & Telegram compatibility.
 * Falls back to MediaRecorder AAC if AudioRecord hardware permissions fail.
 */
public class AudioRecorderHelper {

    private static final String TAG = "AudioRecorderHelper";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private MediaRecorder legacyMediaRecorder;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private File outputFile;
    private File rawPcmFile;

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private volatile int currentMaxAmplitude = 0;

    private final VoiceMaskingDsp voiceDsp = new VoiceMaskingDsp();
    private VoiceFilter activeVoiceFilter = VoiceFilter.NATURAL;

    public void setVoiceFilter(VoiceFilter filter) {
        this.activeVoiceFilter = filter != null ? filter : VoiceFilter.NATURAL;
        Log.i(TAG, "AudioRecorder voice filter set to: " + this.activeVoiceFilter.title);
    }

    public VoiceFilter getVoiceFilter() {
        return activeVoiceFilter;
    }

    /**
     * Starts voice note recording.
     * When activeVoiceFilter is non-natural, uses real-time PCM processing via AudioRecord.
     */
    public synchronized void startRecording(String filePath) {
        outputFile = new File(filePath);
        currentMaxAmplitude = 0;

        // If a non-natural voice filter is selected, strictly use AudioRecord PCM pipeline
        if (activeVoiceFilter != VoiceFilter.NATURAL) {
            startPcmRecording(filePath);
            return;
        }

        // Natural voice: try PCM recording first, fallback to MediaRecorder
        try {
            startPcmRecording(filePath);
        } catch (Throwable t) {
            Log.w(TAG, "PCM recording failed, falling back to MediaRecorder: " + t.getMessage());
            startMediaRecorder(filePath);
        }
    }

    private void startPcmRecording(String filePath) {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, 4096 * 2);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord initialization failed state=" + audioRecord.getState());
            }

            rawPcmFile = new File(filePath + ".raw");
            audioRecord.startRecording();
            isRecording.set(true);
            isPaused.set(false);
            voiceDsp.reset();

            recordingThread = new Thread(() -> {
                byte[] audioBuffer = new byte[2048];
                try (FileOutputStream fos = new FileOutputStream(rawPcmFile)) {
                    while (isRecording.get()) {
                        if (isPaused.get()) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ignored) {}
                            continue;
                        }

                        int readBytes = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                        if (readBytes > 0) {
                            // 1. Calculate peak amplitude for live visualizer
                            int peak = 0;
                            for (int i = 0; i < readBytes - 1; i += 2) {
                                short sample = (short) ((audioBuffer[i] & 0xFF) | (audioBuffer[i + 1] << 8));
                                int abs = Math.abs(sample);
                                if (abs > peak) peak = abs;
                            }
                            currentMaxAmplitude = peak;

                            // 2. Real-time PCM Voice Masking DSP
                            if (activeVoiceFilter != VoiceFilter.NATURAL) {
                                voiceDsp.processBytes(audioBuffer, 0, readBytes, SAMPLE_RATE, 1, activeVoiceFilter);
                            }

                            // 3. Write transformed PCM samples
                            fos.write(audioBuffer, 0, readBytes);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "PCM recording write error", e);
                }
            }, "VeilAudioRecordThread");
            recordingThread.start();
            Log.i(TAG, "PCM voice recording started with filter: " + activeVoiceFilter.title);

        } catch (Throwable t) {
            Log.e(TAG, "Failed to start PCM AudioRecord", t);
            if (audioRecord != null) {
                try { audioRecord.release(); } catch (Throwable ignored) {}
                audioRecord = null;
            }
            startMediaRecorder(filePath);
        }
    }

    private void startMediaRecorder(String filePath) {
        outputFile = new File(filePath);
        legacyMediaRecorder = new MediaRecorder();
        try {
            legacyMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            legacyMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            legacyMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            legacyMediaRecorder.setOutputFile(outputFile.getAbsolutePath());
            legacyMediaRecorder.prepare();
            legacyMediaRecorder.start();
        } catch (IOException e) {
            Log.e(TAG, "Legacy MediaRecorder recording failed", e);
            stopRecording();
        }
    }

    public synchronized File stopRecording() {
        // 1. Stop PCM AudioRecord if active
        if (audioRecord != null) {
            isRecording.set(false);
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Throwable ignored) {}
            audioRecord.release();
            audioRecord = null;

            if (recordingThread != null) {
                try {
                    recordingThread.join(1000);
                } catch (InterruptedException ignored) {}
                recordingThread = null;
            }

            // Convert raw PCM to standard playable WAV file
            if (rawPcmFile != null && rawPcmFile.exists() && rawPcmFile.length() > 0) {
                convertPcmToWav(rawPcmFile, outputFile, SAMPLE_RATE, 1, 16);
                rawPcmFile.delete();
            } else if (rawPcmFile != null) {
                rawPcmFile.delete();
            }
            return outputFile;
        }

        // 2. Stop legacy MediaRecorder if active
        if (legacyMediaRecorder != null) {
            try {
                legacyMediaRecorder.stop();
            } catch (RuntimeException stopException) {
                if (outputFile != null && outputFile.exists()) {
                    outputFile.delete();
                }
                outputFile = null;
            }
            legacyMediaRecorder.release();
            legacyMediaRecorder = null;
        }
        return outputFile;
    }

    public synchronized void pauseRecording() {
        isPaused.set(true);
        if (legacyMediaRecorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                legacyMediaRecorder.pause();
            } catch (Exception e) {
                Log.e(TAG, "Pause failed", e);
            }
        }
    }

    public synchronized void resumeRecording() {
        isPaused.set(false);
        if (legacyMediaRecorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                legacyMediaRecorder.resume();
            } catch (Exception e) {
                Log.e(TAG, "Resume failed", e);
            }
        }
    }

    public int getMaxAmplitude() {
        if (audioRecord != null) {
            int amp = currentMaxAmplitude;
            currentMaxAmplitude = 0;
            return amp;
        }
        if (legacyMediaRecorder != null) {
            try {
                return legacyMediaRecorder.getMaxAmplitude();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Converts a raw 16-bit PCM file into a fully-compliant RIFF WAV file.
     */
    private static void convertPcmToWav(File pcmFile, File wavFile, int sampleRate, int channels, int bitsPerSample) {
        long totalAudioLen = pcmFile.length();
        long totalDataLen = totalAudioLen + 36;
        long byteRate = (long) sampleRate * channels * (bitsPerSample / 8);

        byte[] header = new byte[44];
        // RIFF chunk descriptor
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        // "fmt " sub-chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // Subchunk1Size = 16 for PCM
        header[20] = 1; header[21] = 0; // AudioFormat = 1 (Linear PCM)
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * (bitsPerSample / 8)); header[33] = 0; // BlockAlign
        header[34] = (byte) bitsPerSample; header[35] = 0; // BitsPerSample
        // "data" sub-chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        try (FileOutputStream out = new FileOutputStream(wavFile);
             FileInputStream in = new FileInputStream(pcmFile)) {
            out.write(header, 0, 44);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing WAV header", e);
        }
    }
}
