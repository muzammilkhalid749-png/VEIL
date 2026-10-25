package com.m3.veil.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class WaveformView extends View {
    private Paint paint;
    private List<Float> amplitudes;
    private int maxBars = 30;
    private float barWidth = 8f;
    private float barSpacing = 4f;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(0xFF4CAF50); // green for voice
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(barWidth);
        paint.setAntiAlias(true);
        amplitudes = new ArrayList<>();
    }

    public void addAmplitude(float amplitude) {
        // Normalize amplitude (usually max is ~32767)
        float normalized = Math.min(amplitude / 32767f, 1.0f);
        amplitudes.add(normalized);
        if (amplitudes.size() > maxBars) {
            amplitudes.remove(0);
        }
        invalidate();
    }
    
    public void clear() {
        amplitudes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        int maxPossibleBars = (int) (width / (barWidth + barSpacing));
        maxBars = maxPossibleBars;
        
        float startX = width - (amplitudes.size() * (barWidth + barSpacing));
        
        for (int i = 0; i < amplitudes.size(); i++) {
            float amp = amplitudes.get(i);
            float barHeight = Math.max(height * amp, barWidth); // Minimum height is a dot
            
            float left = startX + i * (barWidth + barSpacing);
            float top = (height - barHeight) / 2f;
            float right = left + barWidth;
            float bottom = top + barHeight;
            
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint);
        }
    }
}
