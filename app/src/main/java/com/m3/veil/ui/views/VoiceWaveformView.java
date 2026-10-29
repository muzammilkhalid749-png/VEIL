package com.m3.veil.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Telegram & WhatsApp style audio waveform visualization view.
 * Features:
 * - Rounded vertical audio bars with proportional spacing.
 * - Dual-color rendering: played portion (accent/white) vs unplayed portion (muted).
 * - Deterministic, organic waveform generation based on message seed.
 * - Interactive touch & drag scrubbing/seeking with haptic feedback.
 */
public class VoiceWaveformView extends View {

    public interface OnSeekListener {
        void onSeek(float progress, boolean fromUser);
    }

    private Paint playedPaint;
    private Paint unplayedPaint;
    private RectF barRect = new RectF();

    private int barCount = 34;
    private float barWidth = 3f; // in dp, converted in init
    private float barSpacing = 2f; // in dp, converted in init
    private float[] barHeights = new float[barCount];

    private float progress = 0f; // 0.0 to 1.0
    private boolean isDragging = false;
    private OnSeekListener seekListener;

    private int playedColor = 0xFF4F46E5;
    private int unplayedColor = 0xFF94A3B8;

    public VoiceWaveformView(Context context) {
        super(context);
        init();
    }

    public VoiceWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VoiceWaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateThemeColors();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateThemeColors();
        invalidate();
    }

    public void updateThemeColors() {
        boolean isNight = (getResources().getConfiguration().uiMode 
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (isNight) {
            playedColor = 0xFF818CF8;
            unplayedColor = 0xFF64748B;
        } else {
            playedColor = 0xFF4F46E5;
            unplayedColor = 0xFF94A3B8;
        }
        if (playedPaint != null) playedPaint.setColor(playedColor);
        if (unplayedPaint != null) unplayedPaint.setColor(unplayedColor);
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        barWidth = Math.max(2f, 2.8f * density);
        barSpacing = Math.max(1.5f, 1.8f * density);

        updateThemeColors();

        playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playedPaint.setStyle(Paint.Style.FILL);
        playedPaint.setColor(playedColor);

        unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unplayedPaint.setStyle(Paint.Style.FILL);
        unplayedPaint.setColor(unplayedColor);

        generateDefaultWaveform("veil_voice_seed");
    }

    public void setColors(int played, int unplayed) {
        this.playedColor = played;
        this.unplayedColor = unplayed;
        playedPaint.setColor(played);
        unplayedPaint.setColor(unplayed);
        invalidate();
    }

    public void setWaveformSeed(String seed) {
        generateDefaultWaveform(seed != null ? seed : "veil_voice_seed");
        invalidate();
    }

    public void setAmplitudes(float[] amplitudes) {
        if (amplitudes != null && amplitudes.length > 0) {
            this.barCount = amplitudes.length;
            this.barHeights = new float[this.barCount];
            for (int i = 0; i < this.barCount; i++) {
                this.barHeights[i] = Math.max(0.12f, Math.min(1.0f, amplitudes[i]));
            }
            invalidate();
        }
    }

    public void setAmplitudes(byte[] amplitudes) {
        if (amplitudes != null && amplitudes.length > 0) {
            this.barCount = amplitudes.length;
            this.barHeights = new float[this.barCount];
            for (int i = 0; i < this.barCount; i++) {
                float val = (amplitudes[i] & 0xFF) / 255.0f;
                this.barHeights[i] = Math.max(0.12f, Math.min(1.0f, val));
            }
            invalidate();
        }
    }

    private void generateDefaultWaveform(String seed) {
        long seedVal = seed.hashCode();
        Random random = new Random(seedVal);

        float lastHeight = 0.4f;
        for (int i = 0; i < barCount; i++) {
            // Generate natural smooth speech-like fluctuations
            float delta = (random.nextFloat() - 0.48f) * 0.45f;
            float h = Math.max(0.18f, Math.min(1.0f, lastHeight + delta));
            // Add slight rhythmic peaks
            if (i % 4 == 0) {
                h = Math.min(1.0f, h + 0.25f);
            }
            barHeights[i] = h;
            lastHeight = h;
        }
    }

    public void setProgress(float prog) {
        if (!isDragging) {
            this.progress = Math.max(0f, Math.min(1f, prog));
            invalidate();
        }
    }

    public float getProgress() {
        return progress;
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.seekListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (32 * getResources().getDisplayMetrics().density);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int height = (heightMode == MeasureSpec.EXACTLY) ? heightSize : desiredHeight;

        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;

        // Dynamically compute bar spacing to fill width nicely
        float totalBarAndSpacing = barWidth + barSpacing;
        int maxBars = (int) (width / totalBarAndSpacing);
        int barsToDraw = Math.min(barCount, Math.max(10, maxBars));
        float effectiveSpacing = (width - (barsToDraw * barWidth)) / Math.max(1, (barsToDraw - 1));

        float currentProgressX = getPaddingLeft() + (width * progress);
        float centerY = getPaddingTop() + (height / 2f);
        float minBarHeight = barWidth; // minimum pill height

        for (int i = 0; i < barsToDraw; i++) {
            float x = getPaddingLeft() + i * (barWidth + effectiveSpacing);
            float hFraction = (i < barHeights.length) ? barHeights[i] : 0.3f;
            float barH = Math.max(minBarHeight, height * hFraction * 0.85f);

            float top = centerY - (barH / 2f);
            float bottom = centerY + (barH / 2f);
            float right = x + barWidth;

            barRect.set(x, top, right, bottom);
            float cornerRadius = barWidth / 2f;

            Paint paintToUse = (x + barWidth / 2f <= currentProgressX) ? playedPaint : unplayedPaint;
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paintToUse);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                updateProgressFromTouch(event.getX());
                if (seekListener != null) {
                    seekListener.onSeek(progress, true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                updateProgressFromTouch(event.getX());
                if (seekListener != null) {
                    seekListener.onSeek(progress, true);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                updateProgressFromTouch(event.getX());
                if (seekListener != null) {
                    seekListener.onSeek(progress, true);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateProgressFromTouch(float touchX) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return;

        float relativeX = touchX - getPaddingLeft();
        this.progress = Math.max(0f, Math.min(1f, relativeX / width));
        invalidate();
    }
}
