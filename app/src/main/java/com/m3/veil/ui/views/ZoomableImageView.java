package com.m3.veil.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Lightweight, gesture-driven Zoomable ImageView for full-screen media previews.
 * Features:
 * - Automatic aspect-fit & centering on image load and view resize (accurate view bounds)
 * - Smooth pinch-to-zoom (1.0x to 5.0x) around focal point
 * - Instant double-tap zoom toggle
 * - Fluid drag panning when zoomed
 * - Automatic reset to fitCenter when zoom is released below base scale
 */
public class ZoomableImageView extends AppCompatImageView {

    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private PointF start = new PointF();
    private float currentScale = 1.0f;
    private float baseScale = 1.0f;
    private float minScale = 0.8f;
    private float maxScale = 5.0f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private boolean isFitted = false;

    public ZoomableImageView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        isFitted = false;
        post(this::fitImageToView);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        isFitted = false;
        post(this::fitImageToView);
    }

    @Override
    public void setImageResource(int resId) {
        super.setImageResource(resId);
        isFitted = false;
        post(this::fitImageToView);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            fitImageToView();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isFitted && getDrawable() != null && getWidth() > 0 && getHeight() > 0) {
            fitImageToView();
        }
        super.onDraw(canvas);
    }

    public void fitImageToView() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;

        int dWidth = drawable.getIntrinsicWidth();
        int dHeight = drawable.getIntrinsicHeight();
        if (dWidth <= 0 || dHeight <= 0) return;

        float viewWidth = (float) getWidth();
        float viewHeight = (float) getHeight();

        float scaleX = viewWidth / dWidth;
        float scaleY = viewHeight / dHeight;
        float scale = Math.min(scaleX, scaleY);

        float dx = (viewWidth - (dWidth * scale)) / 2f;
        float dy = (viewHeight - (dHeight * scale)) / 2f;

        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);

        baseScale = scale;
        currentScale = scale;
        minScale = scale * 0.75f;
        maxScale = scale * 5.0f;
        isFitted = true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float newScale = currentScale * scaleFactor;

            if (newScale >= minScale && newScale <= maxScale) {
                currentScale = newScale;
                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                setImageMatrix(matrix);
            }
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (currentScale > baseScale * 1.25f) {
                // Reset to fitted scale
                fitImageToView();
            } else {
                // Zoom in 2.5x around touch point
                float zoomRatio = 2.5f;
                currentScale = baseScale * zoomRatio;
                matrix.postScale(zoomRatio, zoomRatio, e.getX(), e.getY());
                setImageMatrix(matrix);
            }
            return true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        PointF curr = new PointF(event.getX(), event.getY());

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                savedMatrix.set(matrix);
                start.set(event.getX(), event.getY());
                mode = DRAG;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG && currentScale > baseScale * 1.05f) {
                    float dx = curr.x - start.x;
                    float dy = curr.y - start.y;
                    matrix.set(savedMatrix);
                    matrix.postTranslate(dx, dy);
                    setImageMatrix(matrix);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
                // If pinched smaller than base scale, reset back cleanly
                if (currentScale < baseScale) {
                    fitImageToView();
                }
                break;
        }

        return true;
    }

    public void resetZoom() {
        fitImageToView();
    }
}
