package com.m3.veil.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Renders a WhatsApp-style interactive/thumbnail vector map canvas with streets, blocks,
 * parks, waterways, GPS accuracy halo, and a crisp WhatsApp red drop-pin marker.
 */
public class WhatsAppMapPreviewView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint parkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minorRoadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorRoadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highwayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracyStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double latitude = 37.7749;
    private double longitude = -122.4194;
    private boolean isLiveLocation = false;
    private float accuracyMeters = 12f;

    public WhatsAppMapPreviewView(Context context) {
        super(context);
        init();
    }

    public WhatsAppMapPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WhatsAppMapPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateThemePaints();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateThemePaints();
        invalidate();
    }

    public void updateThemePaints() {
        boolean isNight = ChatMessageCell.resolveIsNightMode(getContext());
        if (isNight) {
            bgPaint.setColor(Color.parseColor("#151D2A"));
            parkPaint.setColor(Color.parseColor("#143324"));
            waterPaint.setColor(Color.parseColor("#162E44"));
            minorRoadPaint.setColor(Color.parseColor("#253245"));
            majorRoadPaint.setColor(Color.parseColor("#2E3D52"));
            highwayPaint.setColor(Color.parseColor("#5A4518"));
            labelPaint.setColor(Color.parseColor("#94A3B8"));
            shadowPaint.setColor(Color.parseColor("#66000000"));
        } else {
            bgPaint.setColor(Color.parseColor("#EFECE6"));
            parkPaint.setColor(Color.parseColor("#C8E6C9"));
            waterPaint.setColor(Color.parseColor("#AEDFF7"));
            minorRoadPaint.setColor(Color.WHITE);
            majorRoadPaint.setColor(Color.WHITE);
            highwayPaint.setColor(Color.parseColor("#FFE082"));
            labelPaint.setColor(Color.parseColor("#607D8B"));
            shadowPaint.setColor(Color.parseColor("#33000000"));
        }
    }

    private void init() {
        float dp = getResources().getDisplayMetrics().density;

        updateThemePaints();

        minorRoadPaint.setStyle(Paint.Style.STROKE);
        minorRoadPaint.setStrokeWidth(4.5f * dp);
        minorRoadPaint.setStrokeCap(Paint.Cap.ROUND);

        majorRoadPaint.setStyle(Paint.Style.STROKE);
        majorRoadPaint.setStrokeWidth(7.5f * dp);

        highwayPaint.setStyle(Paint.Style.STROKE);
        highwayPaint.setStrokeWidth(8.5f * dp);

        accuracyFillPaint.setColor(Color.parseColor("#2600A884"));
        accuracyFillPaint.setStyle(Paint.Style.FILL);

        accuracyStrokePaint.setColor(Color.parseColor("#8800A884"));
        accuracyStrokePaint.setStyle(Paint.Style.STROKE);
        accuracyStrokePaint.setStrokeWidth(1.5f * dp);

        pinPaint.setColor(Color.parseColor("#EA4335"));
        pinPaint.setStyle(Paint.Style.FILL);

        pinCenterPaint.setColor(Color.WHITE);
        pinCenterPaint.setStyle(Paint.Style.FILL);

        shadowPaint.setStyle(Paint.Style.FILL);

        labelPaint.setTextSize(9.5f * dp);
        labelPaint.setFakeBoldText(true);
    }

    public void setLocationData(double lat, double lng, boolean live, float accuracy) {
        this.latitude = lat;
        this.longitude = lng;
        this.isLiveLocation = live;
        this.accuracyMeters = accuracy;
        updateThemePaints();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float dp = getResources().getDisplayMetrics().density;

        // 1. Map land background
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Deterministic offset from coordinates so different locations look distinct
        float seedX = (float) ((Math.abs(latitude * 1000) % 40) - 20) * dp * 0.4f;
        float seedY = (float) ((Math.abs(longitude * 1000) % 40) - 20) * dp * 0.4f;

        // 2. Green park zones
        RectF park1 = new RectF(w * 0.06f + seedX, h * 0.1f + seedY, w * 0.36f + seedX, h * 0.46f + seedY);
        canvas.drawRoundRect(park1, 6 * dp, 6 * dp, parkPaint);

        RectF park2 = new RectF(w * 0.65f - seedX, h * 0.55f - seedY, w * 0.94f - seedX, h * 0.88f - seedY);
        canvas.drawRoundRect(park2, 6 * dp, 6 * dp, parkPaint);

        // 3. Waterway curve
        Path water = new Path();
        water.moveTo(w * 0.78f, 0);
        water.cubicTo(w * 0.70f, h * 0.35f, w * 0.90f, h * 0.65f, w * 0.82f, h);
        water.lineTo(w, h);
        water.lineTo(w, 0);
        water.close();
        canvas.drawPath(water, waterPaint);

        // 4. Minor street grid
        canvas.drawLine(0, h * 0.28f + seedY, w, h * 0.24f + seedY, minorRoadPaint);
        canvas.drawLine(0, h * 0.62f, w, h * 0.66f, minorRoadPaint);
        canvas.drawLine(0, h * 0.84f, w, h * 0.80f, minorRoadPaint);
        canvas.drawLine(w * 0.22f + seedX, 0, w * 0.18f + seedX, h, minorRoadPaint);
        canvas.drawLine(w * 0.48f, 0, w * 0.52f, h, minorRoadPaint);
        canvas.drawLine(w * 0.72f, 0, w * 0.68f, h, minorRoadPaint);

        // 5. Major arterial road & highway
        canvas.drawLine(0, h * 0.48f, w, h * 0.42f, majorRoadPaint);
        canvas.drawLine(w * 0.35f, 0, w * 0.60f, h, highwayPaint);

        // 6. Center GPS accuracy circle
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = isLiveLocation ? 32 * dp : 24 * dp;
        canvas.drawCircle(cx, cy, radius, accuracyFillPaint);
        canvas.drawCircle(cx, cy, radius, accuracyStrokePaint);

        // 7. Pin drop shadow
        RectF shadowOval = new RectF(cx - 6 * dp, cy - 2 * dp, cx + 6 * dp, cy + 3 * dp);
        canvas.drawOval(shadowOval, shadowPaint);

        // 8. WhatsApp Red Drop Pin
        float pinRadius = 11 * dp;
        float pinCenterY = cy - 16 * dp;
        Path pinPath = new Path();
        pinPath.moveTo(cx, cy);
        pinPath.lineTo(cx - pinRadius * 0.85f, pinCenterY + pinRadius * 0.35f);
        pinPath.lineTo(cx + pinRadius * 0.85f, pinCenterY + pinRadius * 0.35f);
        pinPath.close();
        canvas.drawPath(pinPath, pinPaint);
        canvas.drawCircle(cx, pinCenterY, pinRadius, pinPaint);
        canvas.drawCircle(cx, pinCenterY, 4.2f * dp, pinCenterPaint);
    }
}
