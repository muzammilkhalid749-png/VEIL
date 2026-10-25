package com.m3.veil.ui.views;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.Nullable;

/**
 * WaveformScrubberView
 * Telegram & WhatsApp parity interactive audio waveform scrubber.
 * Provides fine touch scrubbing, progress tracking, and amplitude rendering.
 */
public class WaveformScrubberView extends VoiceWaveformView {

    public interface OnScrubListener {
        void onScrub(float fraction, boolean fromUser);
    }

    private OnScrubListener scrubListener;

    public WaveformScrubberView(Context context) {
        super(context);
        initScrubber();
    }

    public WaveformScrubberView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initScrubber();
    }

    public WaveformScrubberView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initScrubber();
    }

    private void initScrubber() {
        setOnSeekListener((progress, fromUser) -> {
            if (scrubListener != null) {
                scrubListener.onScrub(progress, fromUser);
            }
        });
    }

    public void setOnScrubListener(OnScrubListener listener) {
        this.scrubListener = listener;
    }
}
