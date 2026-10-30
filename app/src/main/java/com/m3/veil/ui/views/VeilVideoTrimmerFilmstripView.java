package com.m3.veil.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * VeilVideoTrimmerFilmstripView — WhatsApp / Telegram Parity Video Trimmer.
 *
 * Visual architecture:
 * 1. Base Filmstrip Row: Horizontal strip of 8-10 real video frame thumbnails.
 * 2. Overlaid Crop Box:
 *    - Left & right dimmed unselected overlays (#99000000).
 *    - Thick solid white top/bottom rails.
 *    - Left & right vertical boundary bars.
 *    - Left & right white circular draggable handles (matching the WhatsApp design).
 * 3. Multi-Touch Drag Engine:
 *    - Drag left handle to change start time.
 *    - Drag right handle to change end time.
 *    - Drag inside frame to slide the whole window.
 */
public class VeilVideoTrimmerFilmstripView extends FrameLayout {

    public interface OnTrimChangeListener {
        void onTrimChange(long startMs, long endMs, boolean fromUser, boolean isDragging);
    }

    private static final int FRAME_COUNT = 9;
    private static final int DRAG_NONE = 0;
    private static final int DRAG_LEFT = 1;
    private static final int DRAG_RIGHT = 2;
    private static final int DRAG_WINDOW = 3;

    private LinearLayout mFilmstripRow;
    private List<ImageView> mFrameViews;
    private TrimOverlayView mOverlayView;

    private long mTotalDurationMs = 1000L;
    private float mStartFraction = 0f;
    private float mEndFraction = 1f;
    private float mMinFraction = 0.05f;

    private int mActiveDrag = DRAG_NONE;
    private float mLastTouchX = 0f;
    private float mTouchRadiusPx;

    private OnTrimChangeListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public VeilVideoTrimmerFilmstripView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public VeilVideoTrimmerFilmstripView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VeilVideoTrimmerFilmstripView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mTouchRadiusPx = dpToPx(24f);

        // 1. Filmstrip row of thumbnails
        mFilmstripRow = new LinearLayout(context);
        mFilmstripRow.setOrientation(LinearLayout.HORIZONTAL);
        mFilmstripRow.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mFilmstripRow.setBackgroundColor(Color.parseColor("#1F2C34"));

        mFrameViews = new ArrayList<>(FRAME_COUNT);
        for (int i = 0; i < FRAME_COUNT; i++) {
            ImageView iv = new ImageView(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mFilmstripRow.addView(iv);
            mFrameViews.add(iv);
        }
        addView(mFilmstripRow);

        // 2. Overlaid Crop Box
        mOverlayView = new TrimOverlayView(context);
        mOverlayView.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(mOverlayView);
    }

    public void setOnTrimChangeListener(@Nullable OnTrimChangeListener listener) {
        this.mListener = listener;
    }

    public void setTrimRange(long startMs, long endMs) {
        if (mTotalDurationMs <= 0) return;
        mStartFraction = Math.max(0f, Math.min(1f, (float) startMs / mTotalDurationMs));
        mEndFraction = Math.max(mStartFraction + mMinFraction, Math.min(1f, (float) endMs / mTotalDurationMs));
        mOverlayView.invalidate();
    }

    public long getTrimStartMs() {
        return Math.round(mStartFraction * mTotalDurationMs);
    }

    public long getTrimEndMs() {
        return Math.round(mEndFraction * mTotalDurationMs);
    }

    public void setVideoUri(final Context context, final Uri uri, final long durationMs) {
        if (durationMs > 0) {
            mTotalDurationMs = durationMs;
            mMinFraction = Math.min(0.2f, 500f / durationMs);
        }

        mStartFraction = 0f;
        mEndFraction = 1f;
        mOverlayView.invalidate();

        if (uri == null) return;

        new Thread(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(context, uri);
                for (int i = 0; i < FRAME_COUNT; i++) {
                    final int idx = i;
                    long timeUs = ((mTotalDurationMs * i) / FRAME_COUNT) * 1000L;
                    Bitmap frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        final Bitmap scaled = Bitmap.createScaledBitmap(frame, 120, 120, true);
                        mMainHandler.post(() -> {
                            if (idx < mFrameViews.size()) {
                                mFrameViews.get(idx).setImageBitmap(scaled);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }, "VeilTrimmerFilmstripLoader").start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int width = getWidth();
        if (width <= 0) return super.onTouchEvent(event);

        float x = Math.max(0f, Math.min(width, event.getX()));
        float leftHandleX = mStartFraction * width;
        float rightHandleX = mEndFraction * width;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mLastTouchX = x;
                float distLeft = Math.abs(x - leftHandleX);
                float distRight = Math.abs(x - rightHandleX);

                if (distLeft <= mTouchRadiusPx && distLeft <= distRight) {
                    mActiveDrag = DRAG_LEFT;
                } else if (distRight <= mTouchRadiusPx) {
                    mActiveDrag = DRAG_RIGHT;
                } else if (x > leftHandleX && x < rightHandleX) {
                    mActiveDrag = DRAG_WINDOW;
                } else {
                    if (distLeft < distRight) {
                        mActiveDrag = DRAG_LEFT;
                        mStartFraction = Math.max(0f, Math.min(x / width, mEndFraction - mMinFraction));
                    } else {
                        mActiveDrag = DRAG_RIGHT;
                        mEndFraction = Math.min(1f, Math.max(x / width, mStartFraction + mMinFraction));
                    }
                }

                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                mOverlayView.invalidate();
                notifyTrimChange(true);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mActiveDrag == DRAG_NONE) return super.onTouchEvent(event);

                float dx = x - mLastTouchX;
                mLastTouchX = x;

                if (mActiveDrag == DRAG_LEFT) {
                    mStartFraction = Math.max(0f, Math.min(x / width, mEndFraction - mMinFraction));
                } else if (mActiveDrag == DRAG_RIGHT) {
                    mEndFraction = Math.min(1f, Math.max(x / width, mStartFraction + mMinFraction));
                } else if (mActiveDrag == DRAG_WINDOW) {
                    float dFrac = dx / width;
                    float windowLen = mEndFraction - mStartFraction;
                    if (mStartFraction + dFrac < 0f) {
                        mStartFraction = 0f;
                        mEndFraction = windowLen;
                    } else if (mEndFraction + dFrac > 1f) {
                        mEndFraction = 1f;
                        mStartFraction = 1f - windowLen;
                    } else {
                        mStartFraction += dFrac;
                        mEndFraction += dFrac;
                    }
                }

                mOverlayView.invalidate();
                notifyTrimChange(true);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActiveDrag != DRAG_NONE) {
                    mActiveDrag = DRAG_NONE;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    mOverlayView.invalidate();
                    notifyTrimChange(false);
                    return true;
                }
                break;
            }
        }
        return super.onTouchEvent(event);
    }

    private void notifyTrimChange(boolean isDragging) {
        if (mListener != null) {
            long startMs = getTrimStartMs();
            long endMs = getTrimEndMs();
            mListener.onTrimChange(startMs, endMs, true, isDragging);
        }
    }

    private class TrimOverlayView extends View {
        private final Paint mScrimPaint;
        private final Paint mWhiteBorderPaint;
        private final Paint mHandleFillPaint;
        private final Paint mHandleStrokePaint;
        private final RectF mRect = new RectF();

        public TrimOverlayView(Context context) {
            super(context);
            mScrimPaint = new Paint();
            mScrimPaint.setColor(0x99000000); // 60% black scrim on unselected areas

            mWhiteBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mWhiteBorderPaint.setColor(Color.WHITE);
            mWhiteBorderPaint.setStyle(Paint.Style.STROKE);
            mWhiteBorderPaint.setStrokeWidth(dpToPx(2.5f));

            mHandleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mHandleFillPaint.setColor(Color.WHITE);
            mHandleFillPaint.setStyle(Paint.Style.FILL);

            mHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mHandleStrokePaint.setColor(0x40000000);
            mHandleStrokePaint.setStyle(Paint.Style.STROKE);
            mHandleStrokePaint.setStrokeWidth(dpToPx(1f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float leftX = mStartFraction * w;
            float rightX = mEndFraction * w;
            float strokeW = dpToPx(2.5f);
            float halfStroke = strokeW / 2f;
            float circleRadius = dpToPx(8.5f); // 17dp diameter circular knob
            float centerY = h / 2f;

            // 1. Left Dimmed Scrim
            if (leftX > 0) {
                mRect.set(0, 0, leftX, h);
                canvas.drawRect(mRect, mScrimPaint);
            }

            // 2. Right Dimmed Scrim
            if (rightX < w) {
                mRect.set(rightX, 0, w, h);
                canvas.drawRect(mRect, mScrimPaint);
            }

            // 3. Top & Bottom White Connecting Rails
            canvas.drawLine(leftX, halfStroke, rightX, halfStroke, mWhiteBorderPaint);
            canvas.drawLine(leftX, h - halfStroke, rightX, h - halfStroke, mWhiteBorderPaint);

            // 4. Left Vertical Bar & Circular Knob (Exact WhatsApp style)
            canvas.drawLine(leftX, 0, leftX, h, mWhiteBorderPaint);
            canvas.drawCircle(leftX, centerY, circleRadius, mHandleFillPaint);
            canvas.drawCircle(leftX, centerY, circleRadius, mHandleStrokePaint);

            // 5. Right Vertical Bar & Circular Knob (Exact WhatsApp style)
            canvas.drawLine(rightX, 0, rightX, h, mWhiteBorderPaint);
            canvas.drawCircle(rightX, centerY, circleRadius, mHandleFillPaint);
            canvas.drawCircle(rightX, centerY, circleRadius, mHandleStrokePaint);
        }
    }

    private float dpToPx(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
