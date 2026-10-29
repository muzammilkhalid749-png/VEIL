package com.m3.veil.ui.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.Random;

/**
 * Frameless hardware-accelerated View for VEIL's "The Cloaked Whisper" logo.
 * Implements the 5-phase "Whisper Awakening" animation concept + interactive touch sonar.
 */
public class CloakedWhisperView extends View {

    private static final float VIEWPORT_SIZE = 200f;
    private static final float SHIELD_CX = 126f;
    private static final float SHIELD_CY = 95f;

    // 5-Phase Choreography Progress (0f -> 1f)
    private float voidProgress = 0f;
    private float pleatsProgress = 0f;
    private float cowlProgress = 0f;
    private float rimTraceProgress = 0f;
    private float shieldIgnitionProgress = 0f;
    private float shockwaveProgress = 0f;
    private float idlePhase = 0f;

    // Frameless Emblem Paths
    private final Path hoodAbyssPath = new Path();
    private final Path rightCollarBodyPath = new Path();
    private final Path rightCollarCyanBladePath = new Path();
    private final Path outerShroudDomePath = new Path();
    private final Path deepPleatValleyPath = new Path();
    private final Path midPleatRidgePath = new Path();
    private final Path innerBounceCreasePath = new Path();
    private final Path leftCowlBodyPath = new Path();
    private final Path leftCowlBevelPath = new Path();
    private final Path taperedNeonRimPath = new Path();
    private final Path rimCenterlinePath = new Path();
    private final Path collarCenterlinePath = new Path();
    private final Path tracedRimPath = new Path();
    private final Path tracedCollarPath = new Path();
    private final Path shieldBasePath = new Path();
    private final Path shieldLeftFacetPath = new Path();
    private final Path shieldRightFacetPath = new Path();

    private final PathMeasure rimMeasure = new PathMeasure();
    private final PathMeasure collarMeasure = new PathMeasure();
    private final float[] sparkCoords = new float[2];
    private final RectF shieldBounds = new RectF();

    // Pre-allocated Paints
    private final Paint abyssPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint collarBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint collarCyanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shroudDomePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pleatValleyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pleatRidgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bounceCreasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cowlBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cowlBevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cowlRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint neonBloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint taperedCyanFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laserTraceStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldLeftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shockwavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private LinearGradient shimmerShader;
    private final Matrix shimmerMatrix = new Matrix();

    // Floating Quantum Whisper Motes (Frameless)
    private static final int MOTE_COUNT = 16;
    private final float[] moteAngle = new float[MOTE_COUNT];
    private final float[] moteDist = new float[MOTE_COUNT];
    private final float[] moteSize = new float[MOTE_COUNT];
    private final float[] moteSpeed = new float[MOTE_COUNT];
    private final int[] moteColor = new int[MOTE_COUNT];

    private ValueAnimator introAnimator;
    private ValueAnimator idleAnimator;
    private ValueAnimator touchPulseAnimator;

    public CloakedWhisperView(Context context) {
        super(context);
        init();
    }

    public CloakedWhisperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CloakedWhisperView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        buildFramelessPaths();
        initPaints();
        initWhisperMotes();
    }

    private void initWhisperMotes() {
        Random rng = new Random(77L);
        for (int i = 0; i < MOTE_COUNT; i++) {
            moteAngle[i] = rng.nextFloat() * (float) (Math.PI * 2);
            moteDist[i] = 28f + rng.nextFloat() * 58f;
            moteSize[i] = 0.8f + rng.nextFloat() * 1.5f;
            moteSpeed[i] = 0.25f + rng.nextFloat() * 0.45f;
            moteColor[i] = (i % 2 == 0) ? 0xFF00F5FF : 0xFF818CF8;
        }
    }

    private void buildFramelessPaths() {
        // 1. Deep Hood Cavity Void
        hoodAbyssPath.reset();
        hoodAbyssPath.moveTo(78, 30);
        hoodAbyssPath.cubicTo(112, 18, 148, 28, 162, 54);
        hoodAbyssPath.cubicTo(174, 76, 176, 104, 166, 128);
        hoodAbyssPath.cubicTo(154, 152, 132, 168, 108, 176);
        hoodAbyssPath.cubicTo(90, 158, 70, 128, 66, 90);
        hoodAbyssPath.cubicTo(64, 62, 68, 40, 78, 30);
        hoodAbyssPath.close();

        // 2. Lower-Right Collar Blade & Tapered Cyan Rim
        rightCollarBodyPath.reset();
        rightCollarBodyPath.moveTo(170, 118);
        rightCollarBodyPath.cubicTo(166, 144, 146, 165, 114, 176);
        rightCollarBodyPath.cubicTo(124, 160, 134, 145, 142, 134);
        rightCollarBodyPath.cubicTo(152, 129, 162, 124, 170, 118);
        rightCollarBodyPath.close();

        rightCollarCyanBladePath.reset();
        rightCollarCyanBladePath.moveTo(170, 118);
        rightCollarCyanBladePath.cubicTo(166, 144, 146, 165, 114, 176);
        rightCollarCyanBladePath.cubicTo(118, 172, 122, 168, 126, 164);
        rightCollarCyanBladePath.cubicTo(148, 153, 162, 137, 167, 120);
        rightCollarCyanBladePath.close();

        // 3. Three Concentric Sculpted Shroud Pleats
        outerShroudDomePath.reset();
        outerShroudDomePath.moveTo(62, 48);
        outerShroudDomePath.cubicTo(78, 24, 110, 16, 138, 24);
        outerShroudDomePath.cubicTo(156, 30, 167, 44, 171, 68);
        outerShroudDomePath.cubicTo(174, 88, 176, 104, 171, 114);
        outerShroudDomePath.cubicTo(165, 126, 146, 138, 126, 152);
        outerShroudDomePath.cubicTo(142, 134, 156, 118, 160, 102);
        outerShroudDomePath.cubicTo(164, 84, 158, 56, 146, 42);
        outerShroudDomePath.cubicTo(124, 46, 102, 64, 94, 88);
        outerShroudDomePath.cubicTo(86, 110, 98, 132, 114, 150);
        outerShroudDomePath.cubicTo(120, 158, 120, 168, 112, 176);
        outerShroudDomePath.cubicTo(116, 162, 108, 148, 92, 134);
        outerShroudDomePath.cubicTo(70, 114, 56, 86, 62, 48);
        outerShroudDomePath.close();

        deepPleatValleyPath.reset();
        deepPleatValleyPath.moveTo(88, 30);
        deepPleatValleyPath.cubicTo(66, 48, 60, 82, 74, 112);
        deepPleatValleyPath.cubicTo(84, 132, 104, 146, 110, 168);
        deepPleatValleyPath.cubicTo(96, 152, 74, 136, 60, 114);
        deepPleatValleyPath.cubicTo(46, 88, 52, 50, 88, 30);
        deepPleatValleyPath.close();

        midPleatRidgePath.reset();
        midPleatRidgePath.moveTo(94, 30);
        midPleatRidgePath.cubicTo(72, 50, 66, 84, 80, 112);
        midPleatRidgePath.cubicTo(90, 132, 108, 146, 113, 166);
        midPleatRidgePath.cubicTo(106, 148, 86, 132, 76, 112);
        midPleatRidgePath.cubicTo(62, 84, 68, 50, 94, 30);
        midPleatRidgePath.close();

        innerBounceCreasePath.reset();
        innerBounceCreasePath.moveTo(146, 42);
        innerBounceCreasePath.cubicTo(120, 48, 98, 68, 92, 94);
        innerBounceCreasePath.cubicTo(88, 112, 98, 130, 114, 146);
        innerBounceCreasePath.cubicTo(102, 132, 94, 114, 96, 94);
        innerBounceCreasePath.cubicTo(102, 70, 122, 52, 146, 42);
        innerBounceCreasePath.close();

        // 4. Seamless Left Speech-Bubble Crescent Wing
        leftCowlBodyPath.reset();
        leftCowlBodyPath.moveTo(90, 22);
        leftCowlBodyPath.cubicTo(48, 28, 20, 62, 22, 108);
        leftCowlBodyPath.cubicTo(23, 122, 29, 135, 37, 145);
        leftCowlBodyPath.cubicTo(35, 153, 29, 160, 22, 166);
        leftCowlBodyPath.cubicTo(36, 166, 50, 162, 60, 155);
        leftCowlBodyPath.cubicTo(72, 162, 86, 166, 97, 164);
        leftCowlBodyPath.cubicTo(106, 162, 108, 150, 100, 141);
        leftCowlBodyPath.cubicTo(90, 131, 72, 122, 60, 108);
        leftCowlBodyPath.cubicTo(46, 92, 44, 68, 54, 48);
        leftCowlBodyPath.cubicTo(62, 33, 75, 25, 90, 22);
        leftCowlBodyPath.close();

        leftCowlBevelPath.reset();
        leftCowlBevelPath.moveTo(90, 22);
        leftCowlBevelPath.cubicTo(48, 28, 20, 62, 22, 108);
        leftCowlBevelPath.cubicTo(23, 122, 29, 135, 37, 145);
        leftCowlBevelPath.cubicTo(35, 153, 29, 160, 22, 166);
        leftCowlBevelPath.cubicTo(30, 165, 38, 163, 45, 159);
        leftCowlBevelPath.cubicTo(42, 148, 37, 134, 35, 116);
        leftCowlBevelPath.cubicTo(30, 78, 50, 38, 90, 22);
        leftCowlBevelPath.close();

        // 5. Variable-Width Calligraphic Neon Cyan Hood Rim
        taperedNeonRimPath.reset();
        taperedNeonRimPath.moveTo(72, 36);
        taperedNeonRimPath.cubicTo(92, 20, 120, 16, 144, 25);
        taperedNeonRimPath.cubicTo(160, 31, 169, 45, 172, 68);
        taperedNeonRimPath.cubicTo(175, 86, 177, 102, 173, 113);
        taperedNeonRimPath.cubicTo(168, 125, 148, 138, 122, 155);
        taperedNeonRimPath.cubicTo(144, 136, 162, 122, 167, 111);
        taperedNeonRimPath.cubicTo(171, 101, 169, 85, 166, 68);
        taperedNeonRimPath.cubicTo(163, 48, 155, 35, 141, 29);
        taperedNeonRimPath.cubicTo(119, 21, 93, 24, 72, 36);
        taperedNeonRimPath.close();

        rimCenterlinePath.reset();
        rimCenterlinePath.moveTo(72, 36);
        rimCenterlinePath.cubicTo(93, 22, 120, 18, 143, 27);
        rimCenterlinePath.cubicTo(158, 33, 166, 46, 169, 68);
        rimCenterlinePath.cubicTo(172, 86, 174, 102, 170, 112);
        rimCenterlinePath.cubicTo(165, 124, 146, 137, 122, 155);
        rimMeasure.setPath(rimCenterlinePath, false);

        collarCenterlinePath.reset();
        collarCenterlinePath.moveTo(170, 118);
        collarCenterlinePath.cubicTo(166, 144, 146, 165, 114, 176);
        collarMeasure.setPath(collarCenterlinePath, false);

        // 6. Split-Facet Cryptographic Shield
        shieldBasePath.reset();
        shieldBasePath.moveTo(126, 67.2f);
        shieldBasePath.cubicTo(118.8f, 71.8f, 110.6f, 74.2f, 104.6f, 75.2f);
        shieldBasePath.lineTo(104.6f, 95f);
        shieldBasePath.cubicTo(104.6f, 109.2f, 114.2f, 118.8f, 126f, 124.6f);
        shieldBasePath.cubicTo(137.8f, 118.8f, 147.4f, 109.2f, 147.4f, 95f);
        shieldBasePath.lineTo(147.4f, 75.2f);
        shieldBasePath.cubicTo(141.4f, 74.2f, 133.2f, 71.8f, 126f, 67.2f);
        shieldBasePath.close();
        shieldBasePath.computeBounds(shieldBounds, true);

        shieldLeftFacetPath.reset();
        shieldLeftFacetPath.moveTo(126, 69f);
        shieldLeftFacetPath.cubicTo(119.2f, 73.4f, 111.6f, 75.6f, 106.2f, 76.5f);
        shieldLeftFacetPath.lineTo(106.2f, 94.8f);
        shieldLeftFacetPath.cubicTo(106.2f, 108.2f, 115f, 117.2f, 126f, 122.8f);
        shieldLeftFacetPath.close();

        shieldRightFacetPath.reset();
        shieldRightFacetPath.moveTo(126, 69f);
        shieldRightFacetPath.lineTo(126, 122.8f);
        shieldRightFacetPath.cubicTo(137f, 117.2f, 145.8f, 108.2f, 145.8f, 94.8f);
        shieldRightFacetPath.lineTo(145.8f, 76.5f);
        shieldRightFacetPath.cubicTo(140.4f, 75.6f, 132.8f, 73.4f, 126f, 69f);
        shieldRightFacetPath.close();
    }

    private void initPaints() {
        abyssPaint.setShader(new RadialGradient(
                SHIELD_CX, SHIELD_CY, 58f,
                new int[]{0xFF173A64, 0xFF081226, 0xFF03050B},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));

        collarBodyPaint.setShader(new LinearGradient(
                170, 118, 114, 176,
                new int[]{0xFF38BDF8, 0xFF1D4ED8, 0xFF0F172A},
                null, Shader.TileMode.CLAMP));

        collarCyanPaint.setShader(new LinearGradient(
                170, 118, 114, 176,
                new int[]{0xFFA5F3FC, 0xFF00F5FF, 0xFF0284C7},
                null, Shader.TileMode.CLAMP));

        shroudDomePaint.setShader(new LinearGradient(
                68, 20, 164, 168,
                new int[]{0xFF3F4788, 0xFF1E234A, 0xFF0B0F24},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        pleatValleyPaint.setShader(new LinearGradient(
                56, 30, 110, 168,
                0xFF151934, 0xFF070914, Shader.TileMode.CLAMP));

        pleatRidgePaint.setShader(new LinearGradient(
                68, 30, 113, 166,
                new int[]{0x806366F1, 0x954F46E5, 0x4038BDF8},
                null, Shader.TileMode.CLAMP));

        bounceCreasePaint.setShader(new LinearGradient(
                146, 42, 94, 146,
                new int[]{0xE038BDF8, 0x856366F1, 0x151E1B4B},
                null, Shader.TileMode.CLAMP));

        cowlBodyPaint.setShader(new LinearGradient(
                24, 24, 104, 165,
                new int[]{0xFF60A5FA, 0xFF5B54F2, 0xFF3730A3},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));

        cowlBevelPaint.setShader(new LinearGradient(
                22, 22, 68, 164,
                new int[]{0xFF93C5FD, 0xFF6366F1, 0xFF4338CA},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        cowlRimPaint.setStyle(Paint.Style.STROKE);
        cowlRimPaint.setStrokeWidth(1.6f);
        cowlRimPaint.setStrokeCap(Paint.Cap.ROUND);
        cowlRimPaint.setStrokeJoin(Paint.Join.ROUND);
        cowlRimPaint.setShader(new LinearGradient(
                22, 22, 104, 165,
                new int[]{0xFFE0F2FE, 0xFF818CF8, 0xFF4F46E5},
                null, Shader.TileMode.CLAMP));

        // GPU Neon Cyan Bloom
        neonBloomPaint.setStyle(Paint.Style.STROKE);
        neonBloomPaint.setStrokeWidth(8f);
        neonBloomPaint.setStrokeCap(Paint.Cap.ROUND);
        neonBloomPaint.setStrokeJoin(Paint.Join.ROUND);
        neonBloomPaint.setColor(0xAA00F5FF);
        neonBloomPaint.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));

        taperedCyanFillPaint.setStyle(Paint.Style.FILL);
        taperedCyanFillPaint.setShader(new LinearGradient(
                72, 20, 174, 155,
                new int[]{0xFF38BDF8, 0xFFE0F2FE, 0xFF00F5FF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        laserTraceStrokePaint.setStyle(Paint.Style.STROKE);
        laserTraceStrokePaint.setStrokeWidth(2.6f);
        laserTraceStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        laserTraceStrokePaint.setShader(new LinearGradient(
                72, 20, 174, 155,
                new int[]{0xFF38BDF8, 0xFFFFFFFF, 0xFF00F5FF},
                null, Shader.TileMode.CLAMP));

        // Shield Paints
        shieldHaloPaint.setShader(new RadialGradient(
                SHIELD_CX, SHIELD_CY, 38f,
                new int[]{0xB000F5FF, 0x450284C7, 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        shieldLeftPaint.setShader(new LinearGradient(
                106, 69, 126, 123,
                new int[]{0xFFE0F2FE, 0xFF67E8F9, 0xFF06B6D4},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        shieldRightPaint.setShader(new LinearGradient(
                126, 69, 146, 123,
                new int[]{0xFF38BDF8, 0xFF0284C7, 0xFF0369A1},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

        shieldRimPaint.setStyle(Paint.Style.STROKE);
        shieldRimPaint.setStrokeWidth(1.8f);
        shieldRimPaint.setStrokeJoin(Paint.Join.ROUND);
        shieldRimPaint.setShader(new LinearGradient(
                104, 67, 148, 125,
                new int[]{0xFFFFFFFF, 0xFFA5F3FC, 0xFF00F5FF},
                null, Shader.TileMode.CLAMP));

        scanlinePaint.setStyle(Paint.Style.STROKE);
        scanlinePaint.setStrokeWidth(2.4f);
        scanlinePaint.setColor(0xFFFFFFFF);
        scanlinePaint.setMaskFilter(new BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL));

        shockwavePaint.setStyle(Paint.Style.STROKE);
        shockwavePaint.setColor(0xFF00F5FF);

        sparkPaint.setStyle(Paint.Style.FILL);
        sparkPaint.setColor(0xFFFFFFFF);
        sparkPaint.setMaskFilter(new BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL));

        shimmerShader = new LinearGradient(
                -50, -50, 0, 0,
                new int[]{0x00FFFFFF, 0x55A5F3FC, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        shimmerPaint.setStyle(Paint.Style.STROKE);
        shimmerPaint.setStrokeWidth(2.6f);
        shimmerPaint.setStrokeCap(Paint.Cap.ROUND);
        shimmerPaint.setShader(shimmerShader);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startWhisperAwakening();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (introAnimator != null) introAnimator.cancel();
        if (idleAnimator != null) idleAnimator.cancel();
        if (touchPulseAnimator != null) touchPulseAnimator.cancel();
        super.onDetachedFromWindow();
    }

    public void startWhisperAwakening() {
        if (introAnimator != null) introAnimator.cancel();
        if (idleAnimator != null) idleAnimator.cancel();

        OvershootInterpolator spring = new OvershootInterpolator(1.18f);
        DecelerateInterpolator smoothOut = new DecelerateInterpolator(1.9f);

        introAnimator = ValueAnimator.ofFloat(0f, 1f);
        introAnimator.setDuration(1650L);
        introAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();

            // Phase 1: Shadow Void & Concentric Pleats Unfurl (0.00 - 0.55)
            voidProgress = smoothOut.getInterpolation(clamp01(t / 0.38f));
            pleatsProgress = smoothOut.getInterpolation(clamp01((t - 0.08f) / 0.48f));

            // Phase 2: Left Crescent Speech-Bubble Wing Sweeps In (0.14 - 0.68)
            cowlProgress = spring.getInterpolation(clamp01((t - 0.14f) / 0.54f));

            // Phase 3: Laser Rim-Light Ignition (0.24 - 0.82)
            rimTraceProgress = smoothOut.getInterpolation(clamp01((t - 0.24f) / 0.58f));

            // Phase 4: Holographic Shield Crystallization & Shockwave (0.48 - 1.00)
            shieldIgnitionProgress = smoothOut.getInterpolation(clamp01((t - 0.48f) / 0.40f));
            shockwaveProgress = smoothOut.getInterpolation(clamp01((t - 0.62f) / 0.38f));

            invalidate();
        });

        introAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startPhantomIdleLoop();
            }
        });
        introAnimator.start();
    }

    private void startPhantomIdleLoop() {
        idleAnimator = ValueAnimator.ofFloat(0f, 1f);
        idleAnimator.setDuration(4000L);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.setInterpolator(new LinearInterpolator());
        idleAnimator.addUpdateListener(anim -> {
            idlePhase = (float) anim.getAnimatedValue();
            invalidate();
        });
        idleAnimator.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            triggerSonarShockwave();
            return true;
        }
        return super.onTouchEvent(event);
    }

    public void triggerSonarShockwave() {
        if (touchPulseAnimator != null) touchPulseAnimator.cancel();
        touchPulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        touchPulseAnimator.setDuration(750L);
        touchPulseAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        touchPulseAnimator.addUpdateListener(anim -> {
            shockwaveProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        touchPulseAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        float scale = Math.min(getWidth(), getHeight()) / VIEWPORT_SIZE;
        canvas.save();
        canvas.scale(scale, scale);

        // 0. Draw Frameless Orbiting Whisper Motes
        if (pleatsProgress > 0.25f) {
            drawWhisperMotes(canvas);
        }

        // 1. Phase 1: Deep Hood Cavity Void & Concentric Shroud Pleats
        if (voidProgress > 0f) {
            canvas.save();
            float voidScale = 0.78f + 0.22f * voidProgress;
            canvas.scale(voidScale, voidScale, 116f, 102f);
            abyssPaint.setAlpha((int) (255 * voidProgress));
            canvas.drawPath(hoodAbyssPath, abyssPaint);
            canvas.restore();
        }

        if (pleatsProgress > 0f) {
            canvas.save();
            float pleatScale = 0.84f + 0.16f * pleatsProgress;
            canvas.scale(pleatScale, pleatScale, 114f, 104f);
            int alpha = (int) (255 * clamp01(pleatsProgress * 1.2f));

            collarBodyPaint.setAlpha(alpha);
            shroudDomePaint.setAlpha(alpha);
            pleatValleyPaint.setAlpha(alpha);
            pleatRidgePaint.setAlpha(alpha);

            canvas.drawPath(rightCollarBodyPath, collarBodyPaint);
            canvas.drawPath(outerShroudDomePath, shroudDomePaint);
            canvas.drawPath(deepPleatValleyPath, pleatValleyPaint);
            canvas.drawPath(midPleatRidgePath, pleatRidgePaint);

            // Inner hood crease brightens dynamically as the shield ignites!
            int bounceAlpha = (int) (alpha * (0.35f + 0.65f * shieldIgnitionProgress));
            bounceCreasePaint.setAlpha(bounceAlpha);
            canvas.drawPath(innerBounceCreasePath, bounceCreasePaint);
            canvas.restore();
        }

        // 2. Phase 2: Sweeping Left Speech-Bubble Crescent Wing
        if (cowlProgress > 0f) {
            canvas.save();
            float offsetX = (1f - cowlProgress) * -22f;
            float offsetY = (1f - cowlProgress) * 14f;
            float rot = (1f - cowlProgress) * -14f;
            canvas.translate(offsetX, offsetY);
            canvas.rotate(rot, 78f, 108f);

            int cowlAlpha = (int) (255 * clamp01(cowlProgress));
            cowlBodyPaint.setAlpha(cowlAlpha);
            cowlBevelPaint.setAlpha(cowlAlpha);
            cowlRimPaint.setAlpha(cowlAlpha);

            canvas.drawPath(leftCowlBodyPath, cowlBodyPaint);
            canvas.drawPath(leftCowlBevelPath, cowlBevelPaint);
            canvas.drawPath(leftCowlBodyPath, cowlRimPaint);
            canvas.restore();
        }

        // 3. Phase 3: Variable-Width Tapered Neon Cyan Hood Rim + Laser Spark
        if (rimTraceProgress > 0f) {
            tracedRimPath.reset();
            float rimLen = rimMeasure.getLength();
            rimMeasure.getSegment(0f, rimLen * rimTraceProgress, tracedRimPath, true);

            float pulse = 0.80f + 0.20f * (float) Math.sin(idlePhase * Math.PI * 2f);
            neonBloomPaint.setAlpha((int) (200 * pulse));

            // GPU Bloom along traced rim
            canvas.drawPath(tracedRimPath, neonBloomPaint);

            if (rimTraceProgress < 0.99f) {
                canvas.drawPath(tracedRimPath, laserTraceStrokePaint);
                rimMeasure.getPosTan(rimLen * rimTraceProgress, sparkCoords, null);
                canvas.drawCircle(sparkCoords[0], sparkCoords[1], 4.2f, sparkPaint);
            } else {
                // Full variable-width calligraphic blade once trace finishes
                canvas.drawPath(taperedNeonRimPath, taperedCyanFillPaint);
                canvas.drawPath(rightCollarCyanBladePath, collarCyanPaint);

                // Travelling specular light wave across hood rim & crescent wing
                float shimmerShift = -50f + idlePhase * 300f;
                shimmerMatrix.setTranslate(shimmerShift, shimmerShift * 0.75f);
                shimmerShader.setLocalMatrix(shimmerMatrix);
                canvas.drawPath(rimCenterlinePath, shimmerPaint);
                canvas.drawPath(leftCowlBodyPath, shimmerPaint);
            }

            // Trace lower-right collar edge in tandem
            if (rimTraceProgress < 0.99f) {
                tracedCollarPath.reset();
                float collarLen = collarMeasure.getLength();
                collarMeasure.getSegment(0f, collarLen * rimTraceProgress, tracedCollarPath, true);
                canvas.drawPath(tracedCollarPath, laserTraceStrokePaint);
            }
        }

        // 4. Phase 4 & 5: Holographic Shield Crystallization & Levitation
        if (shieldIgnitionProgress > 0f) {
            float bobY = (float) Math.sin(idlePhase * Math.PI * 2f) * 2.5f;
            float auraScale = 0.92f + 0.12f * (float) Math.sin(idlePhase * Math.PI * 2f);

            canvas.save();
            canvas.translate(0f, bobY);

            // Breathing cyan aura inside hood cavity
            canvas.save();
            canvas.scale(auraScale, auraScale, SHIELD_CX, SHIELD_CY);
            shieldHaloPaint.setAlpha((int) (255 * shieldIgnitionProgress));
            canvas.drawCircle(SHIELD_CX, SHIELD_CY, 36f, shieldHaloPaint);
            canvas.restore();

            // Vertical holographic scanline clip
            canvas.save();
            float clipBottom = shieldBounds.top + shieldBounds.height() * shieldIgnitionProgress;
            canvas.clipRect(shieldBounds.left - 6f, shieldBounds.top - 6f,
                    shieldBounds.right + 6f, clipBottom);

            canvas.drawPath(shieldBasePath, neonBloomPaint);
            canvas.drawPath(shieldLeftFacetPath, shieldLeftPaint);
            canvas.drawPath(shieldRightFacetPath, shieldRightPaint);
            canvas.drawPath(shieldBasePath, shieldRimPaint);
            canvas.restore();

            // Laser scanline bar while shield is materializing
            if (shieldIgnitionProgress < 0.98f) {
                canvas.drawLine(100f, clipBottom, 152f, clipBottom, scanlinePaint);
            }
            canvas.restore();
        }

        // 5. Expanding Cryptographic Shockwave Ring
        if (shockwaveProgress > 0f && shockwaveProgress < 1f) {
            float radius = 16f + shockwaveProgress * 72f;
            int alpha = (int) ((1f - shockwaveProgress) * 210f);
            shockwavePaint.setAlpha(alpha);
            shockwavePaint.setStrokeWidth(3.0f * (1f - shockwaveProgress) + 0.6f);
            canvas.drawCircle(SHIELD_CX, SHIELD_CY, radius, shockwavePaint);
        }

        canvas.restore();
    }

    private void drawWhisperMotes(Canvas canvas) {
        for (int i = 0; i < MOTE_COUNT; i++) {
            float currentAngle = moteAngle[i] + idlePhase * (float) (Math.PI * 2) * moteSpeed[i];
            float dist = moteDist[i] + (float) Math.sin(idlePhase * Math.PI * 2 + i) * 5f;
            float x = 104f + (float) Math.cos(currentAngle) * dist;
            float y = 98f + (float) Math.sin(currentAngle) * dist;

            float pulse = 0.4f + 0.6f * (float) Math.abs(Math.sin(idlePhase * Math.PI * 2 + i));
            particlePaint.setColor(moteColor[i]);
            particlePaint.setAlpha((int) (95f * pulse * pleatsProgress));
            canvas.drawCircle(x, y, moteSize[i], particlePaint);
        }
    }

    private static float clamp01(float val) {
        return Math.max(0f, Math.min(1f, val));
    }
}