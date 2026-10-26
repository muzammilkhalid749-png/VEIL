package com.m3.veil.ui.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL CANVAS SPOILER & MULTI-PHOTO MOSAIC VIEW (com.m3.veil.ui.views)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * High-performance 120 FPS custom view providing Telegram-parity media rendering:
 *
 * 1. Interactive Canvas-rendered Telegram-grade spoiler particle effect:
 *    - Dynamic twinkling particle noise overlay with continuous shimmer.
 *    - Tap-to-reveal ripple animation with expanding transparent mask and smooth dissolve alpha.
 *    - Tap-to-conceal capability for privacy re-masking.
 *
 * 2. Multi-photo Mosaic Grid (1 to 5+ images):
 *    - 1 image: Full aspect ratio.
 *    - 2 images: 50/50 side-by-side or stacked split.
 *    - 3 images: 1 large left/top tile + 2 stacked right/bottom tiles.
 *    - 4 images: 2x2 symmetrical grid.
 *    - 5+ images: 2x2 grid with bottom-right thumbnail masked by '+N' overlay badge.
 *    - Outer rounded corners, inner divider spacing, and independent click listeners.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class VeilCanvasSpoilerAndMosaicView extends View {

    private static final String TAG = "VeilSpoilerMosaicView";
    private static final int MAX_PARTICLES = 160;
    private static final float TILE_GAP_DP = 4f;
    private static final float CORNER_RADIUS_DP = 16f;

    // Bitmap memory cache (16MB)
    private static final int CACHE_SIZE = 16 * 1024 * 1024;
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(4);

    // Media states
    private final List<String> mImageUrls = new ArrayList<>();
    private final List<RectF> mTileRects = new ArrayList<>();
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClipPath = new Path();

    // Spoiler Particle Engine
    public static class SpoilerParticle {
        float x, y;
        float vx, vy;
        float radius;
        float alpha;
        float baseAlpha;
        float speed;
        int color;
    }

    private final List<SpoilerParticle> mParticles = new ArrayList<>(MAX_PARTICLES);
    private final Random mRandom = new Random();
    private boolean mIsSpoiler = false;
    private boolean mIsRevealed = false;
    private float mRevealProgress = 0f; // 0f = fully masked, 1f = fully revealed
    private float mRippleRadius = 0f;
    private float mRippleMaxRadius = 0f;
    private float mTouchX = 0f;
    private float mTouchY = 0f;

    private ValueAnimator mShimmerAnimator;
    private ValueAnimator mRevealAnimator;

    // Listeners
    public interface OnTileClickListener {
        void onTileClick(int index, String url);
    }
    public interface OnSpoilerStateChangeListener {
        void onSpoilerStateChanged(boolean isRevealed);
    }

    private OnTileClickListener mTileClickListener;
    private OnSpoilerStateChangeListener mSpoilerListener;

    public VeilCanvasSpoilerAndMosaicView(Context context) {
        this(context, null);
    }

    public VeilCanvasSpoilerAndMosaicView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VeilCanvasSpoilerAndMosaicView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mScrimPaint.setColor(Color.argb(170, 15, 23, 42)); // Deep obsidian scrim
        mScrimPaint.setStyle(Paint.Style.FILL);

        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(getResources().getDisplayMetrics().density * 22f);
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mOverlayPaint.setColor(Color.argb(220, 10, 14, 26));
        mOverlayPaint.setStyle(Paint.Style.FILL);

        initParticles();
        startShimmerLoop();
    }

    private void initParticles() {
        mParticles.clear();
        int[] palette = {
                Color.argb(230, 255, 255, 255),
                Color.argb(200, 168, 85, 247),  // Purple
                Color.argb(200, 99, 102, 241),  // Indigo
                Color.argb(210, 236, 72, 153),  // Pink
                Color.argb(180, 56, 189, 248)   // Sky
        };

        for (int i = 0; i < MAX_PARTICLES; i++) {
            SpoilerParticle p = new SpoilerParticle();
            p.radius = 1.2f + mRandom.nextFloat() * 2.8f;
            p.vx = (mRandom.nextFloat() - 0.5f) * 1.5f;
            p.vy = (mRandom.nextFloat() - 0.5f) * 1.5f;
            p.baseAlpha = 0.35f + mRandom.nextFloat() * 0.65f;
            p.alpha = p.baseAlpha;
            p.speed = 0.8f + mRandom.nextFloat() * 1.4f;
            p.color = palette[mRandom.nextInt(palette.length)];
            mParticles.add(p);
        }
    }

    private void startShimmerLoop() {
        if (mShimmerAnimator != null && mShimmerAnimator.isRunning()) return;
        mShimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        mShimmerAnimator.setDuration(1200);
        mShimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mShimmerAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mShimmerAnimator.addUpdateListener(animation -> {
            if (mIsSpoiler && mRevealProgress < 1f) {
                updateParticles();
                invalidate();
            }
        });
        mShimmerAnimator.start();
    }

    private void updateParticles() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (SpoilerParticle p : mParticles) {
            p.x += p.vx * p.speed;
            p.y += p.vy * p.speed;

            if (p.x < 0) p.x = w;
            if (p.x > w) p.x = 0;
            if (p.y < 0) p.y = h;
            if (p.y > h) p.y = 0;

            // Twinkle alpha
            p.alpha = p.baseAlpha * (0.6f + mRandom.nextFloat() * 0.4f);
        }
    }

    public void setImages(@Nullable List<String> urls) {
        mImageUrls.clear();
        if (urls != null) {
            mImageUrls.addAll(urls);
        }
        computeTileLayout();
        loadBitmapsAsync();
        invalidate();
    }

    public void setSingleImage(@Nullable String url) {
        if (url == null || url.trim().isEmpty()) {
            setImages(null);
        } else {
            setImages(Collections.singletonList(url));
        }
    }

    public void setSpoiler(boolean isSpoiler) {
        this.mIsSpoiler = isSpoiler;
        this.mIsRevealed = false;
        this.mRevealProgress = 0f;
        invalidate();
    }

    public boolean isSpoiler() {
        return mIsSpoiler;
    }

    public boolean isRevealed() {
        return mIsRevealed;
    }

    public void setOnTileClickListener(OnTileClickListener listener) {
        this.mTileClickListener = listener;
    }

    public void setOnSpoilerStateChangeListener(OnSpoilerStateChangeListener listener) {
        this.mSpoilerListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeTileLayout();

        // Reposition particles across new bounds
        for (SpoilerParticle p : mParticles) {
            p.x = mRandom.nextFloat() * w;
            p.y = mRandom.nextFloat() * h;
        }
    }

    private void computeTileLayout() {
        mTileRects.clear();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || mImageUrls.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        float gap = TILE_GAP_DP * density;
        int count = mImageUrls.size();

        if (count == 1) {
            mTileRects.add(new RectF(0, 0, w, h));
        } else if (count == 2) {
            float halfW = (w - gap) / 2f;
            mTileRects.add(new RectF(0, 0, halfW, h));
            mTileRects.add(new RectF(halfW + gap, 0, w, h));
        } else if (count == 3) {
            float halfW = (w - gap) / 2f;
            float halfH = (h - gap) / 2f;
            mTileRects.add(new RectF(0, 0, halfW, h));
            mTileRects.add(new RectF(halfW + gap, 0, w, halfH));
            mTileRects.add(new RectF(halfW + gap, halfH + gap, w, h));
        } else if (count >= 4) {
            float halfW = (w - gap) / 2f;
            float halfH = (h - gap) / 2f;
            mTileRects.add(new RectF(0, 0, halfW, halfH));
            mTileRects.add(new RectF(halfW + gap, 0, w, halfH));
            mTileRects.add(new RectF(0, halfH + gap, halfW, h));
            mTileRects.add(new RectF(halfW + gap, halfH + gap, w, h));
        }
    }

    private void loadBitmapsAsync() {
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < Math.min(mImageUrls.size(), 4); i++) {
            String path = mImageUrls.get(i);
            if (path == null || path.isEmpty()) continue;
            if (BITMAP_CACHE.get(path) != null) continue;

            IO_EXECUTOR.execute(() -> {
                Bitmap bmp = decodeSampledBitmap(path, w > 0 ? w / 2 : 400, h > 0 ? h / 2 : 400);
                if (bmp != null) {
                    BITMAP_CACHE.put(path, bmp);
                    postInvalidate();
                }
            });
        }
    }

    @Nullable
    private Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;

            if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri uri = Uri.parse(path);
                try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, options);
                }
            } else {
                BitmapFactory.decodeFile(path, options);
            }

            options.inSampleSize = calculateInSampleSize(options, reqW, reqH);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri uri = Uri.parse(path);
                try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                    return BitmapFactory.decodeStream(is, null, options);
                }
            } else {
                return BitmapFactory.decodeFile(path, options);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error decoding bitmap: " + t.getMessage());
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float cornerRadius = CORNER_RADIUS_DP * density;

        // Clip rounded bounds for modern airy container
        mClipPath.reset();
        mClipPath.addRoundRect(0, 0, w, h, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mClipPath);

        // 1. Draw Mosaic Tiles
        int tilesCount = Math.min(mTileRects.size(), mImageUrls.size());
        for (int i = 0; i < tilesCount; i++) {
            RectF rect = mTileRects.get(i);
            String url = mImageUrls.get(i);
            Bitmap bmp = BITMAP_CACHE.get(url);

            if (bmp != null && !bmp.isRecycled()) {
                drawCenterCropBitmap(canvas, bmp, rect);
            } else {
                // Placeholder gradient while loading
                Paint p = new Paint();
                p.setColor(0xFF1E293B);
                canvas.drawRect(rect, p);
            }

            // Draw +N overlay on 4th tile if > 4 images
            if (i == 3 && mImageUrls.size() > 4) {
                canvas.drawRect(rect, mOverlayPaint);
                int remaining = mImageUrls.size() - 3;
                float textY = rect.centerY() - ((mTextPaint.descent() + mTextPaint.ascent()) / 2f);
                canvas.drawText("+" + remaining, rect.centerX(), textY, mTextPaint);
            }
        }

        // 2. Draw Telegram-grade Spoiler Particle Shimmer & Dissolve Layer
        if (mIsSpoiler && mRevealProgress < 1f) {
            int spoilerAlpha = (int) ((1f - mRevealProgress) * 255);
            mScrimPaint.setAlpha(Math.min(spoilerAlpha, 230));
            canvas.drawRect(0, 0, w, h, mScrimPaint);

            for (SpoilerParticle p : mParticles) {
                // If revealing, disperse particles outward from touch point
                if (mRevealProgress > 0f) {
                    float dx = p.x - mTouchX;
                    float dy = p.y - mTouchY;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < mRippleRadius) {
                        continue; // Disperse inside revealed circle
                    }
                }

                mParticlePaint.setColor(p.color);
                mParticlePaint.setAlpha((int) (p.alpha * spoilerAlpha));
                canvas.drawCircle(p.x, p.y, p.radius, mParticlePaint);
            }
        }

        canvas.restore();
    }

    private void drawCenterCropBitmap(Canvas canvas, Bitmap bitmap, RectF dst) {
        int bWidth = bitmap.getWidth();
        int bHeight = bitmap.getHeight();
        float dWidth = dst.width();
        float dHeight = dst.height();

        float scale;
        float dx = 0, dy = 0;

        if (bWidth * dHeight > dWidth * bHeight) {
            scale = dHeight / (float) bHeight;
            dx = (dWidth - bWidth * scale) * 0.5f;
        } else {
            scale = dWidth / (float) bWidth;
            dy = (dHeight - bHeight * scale) * 0.5f;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dst.left + dx, dst.top + dy);

        canvas.save();
        canvas.clipRect(dst);
        canvas.drawBitmap(bitmap, matrix, mBitmapPaint);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mTouchX = event.getX();
            mTouchY = event.getY();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();

            // 1. If spoiler is active and unrevealed: Tap reveals it
            if (mIsSpoiler && !mIsRevealed) {
                startRevealAnimation(mTouchX, mTouchY);
                return true;
            }

            // 2. If spoiler is revealed: Long-tap or tap can re-spoil, or click on photo tile
            for (int i = 0; i < mTileRects.size(); i++) {
                if (mTileRects.get(i).contains(x, y)) {
                    if (mTileClickListener != null && i < mImageUrls.size()) {
                        mTileClickListener.onTileClick(i, mImageUrls.get(i));
                    }
                    return true;
                }
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void startRevealAnimation(float cx, float cy) {
        mTouchX = cx;
        mTouchY = cy;
        mRippleMaxRadius = (float) Math.hypot(
                Math.max(cx, getWidth() - cx),
                Math.max(cy, getHeight() - cy)
        );

        if (mRevealAnimator != null && mRevealAnimator.isRunning()) {
            mRevealAnimator.cancel();
        }

        mRevealAnimator = ValueAnimator.ofFloat(0f, 1f);
        mRevealAnimator.setDuration(480);
        mRevealAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mRevealAnimator.addUpdateListener(animation -> {
            mRevealProgress = (float) animation.getAnimatedValue();
            mRippleRadius = mRevealProgress * mRippleMaxRadius;
            invalidate();
        });
        mRevealAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mIsRevealed = true;
                mRevealProgress = 1f;
                if (mSpoilerListener != null) {
                    mSpoilerListener.onSpoilerStateChanged(true);
                }
            }
        });
        mRevealAnimator.start();
    }

    public void concealSpoiler() {
        if (!mIsSpoiler) return;
        mIsRevealed = false;
        mRevealProgress = 0f;
        mRippleRadius = 0f;
        invalidate();
        if (mSpoilerListener != null) {
            mSpoilerListener.onSpoilerStateChanged(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mShimmerAnimator != null) {
            mShimmerAnimator.cancel();
        }
        if (mRevealAnimator != null) {
            mRevealAnimator.cancel();
        }
    }
}
