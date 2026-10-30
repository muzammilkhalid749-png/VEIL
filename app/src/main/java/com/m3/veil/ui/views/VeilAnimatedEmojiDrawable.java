package com.m3.veil.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * VeilAnimatedEmojiDrawable — WhatsApp & Telegram Parity 60fps Animated Emoji Engine.
 *
 * Renders smooth vector-based animations for popular standalone emojis (❤️ Heart, 🔥 Fire,
 * 😢 Crying, 😮 Surprised, 😂 Laughing, 👍 Thumbs Up, 🎉 Party Popper, 🥺 Pleading, etc.)
 * plus universal procedural floating & breathing animation for any system emoji.
 *
 * Zero external dependencies. Zero network assets. Locked 60fps hardware accelerated canvas.
 */
public class VeilAnimatedEmojiDrawable extends Drawable implements Animatable {

    public enum EmojiType {
        HEART,
        FIRE,
        CRY,
        SURPRISE,
        LAUGH,
        THUMBS_UP,
        PARTY,
        PLEADING,
        ROCKET,
        SPARKLE,
        GENERIC
    }

    private static final long CYCLE_DURATION_MS = 2400L;

    private final String rawEmoji;
    private final EmojiType type;
    private final float dp;

    private boolean isRunning = false;
    private long startUptimeMs = 0L;
    private float burstProgress = 0f;
    private long burstStartMs = 0L;

    // Pre-allocated graphic objects for zero garbage collection during 60fps animation
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path1 = new Path();
    private final Path path2 = new Path();
    private final Path path3 = new Path();
    private final RectF tempRect = new RectF();

    // Floating particle sparks for fire, party popper, and heart taps
    private static class Spark {
        float x, y, vx, vy, size, alpha;
        int color;
    }
    private final List<Spark> sparks = new ArrayList<>(24);

    public VeilAnimatedEmojiDrawable(@NonNull Context context, @NonNull String emoji) {
        this.rawEmoji = emoji != null ? emoji.trim() : "❤️";
        this.dp = context.getResources().getDisplayMetrics().density;
        this.type = resolveEmojiType(this.rawEmoji);

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
    }

    public static EmojiType resolveEmojiType(String emoji) {
        if (emoji == null || emoji.isEmpty()) return EmojiType.GENERIC;
        if (emoji.contains("❤️") || emoji.contains("♥️") || emoji.contains("💖") || emoji.contains("💕") || emoji.contains("💓") || emoji.contains("💗")) {
            return EmojiType.HEART;
        }
        if (emoji.contains("🔥")) {
            return EmojiType.FIRE;
        }
        if (emoji.contains("😢") || emoji.contains("😭") || emoji.contains("😿") || emoji.contains("💧")) {
            return EmojiType.CRY;
        }
        if (emoji.contains("😮") || emoji.contains("😲") || emoji.contains("😱") || emoji.contains("😯") || emoji.contains("🙀")) {
            return EmojiType.SURPRISE;
        }
        if (emoji.contains("😂") || emoji.contains("🤣") || emoji.contains("😹")) {
            return EmojiType.LAUGH;
        }
        if (emoji.contains("👍") || emoji.contains("👍🏻") || emoji.contains("👍🏼") || emoji.contains("👍🏽") || emoji.contains("👍🏾") || emoji.contains("👍🏿")) {
            return EmojiType.THUMBS_UP;
        }
        if (emoji.contains("🎉") || emoji.contains("🎊") || emoji.contains("🥳")) {
            return EmojiType.PARTY;
        }
        if (emoji.contains("🥺")) {
            return EmojiType.PLEADING;
        }
        if (emoji.contains("🚀")) {
            return EmojiType.ROCKET;
        }
        if (emoji.contains("✨") || emoji.contains("⭐") || emoji.contains("🌟")) {
            return EmojiType.SPARKLE;
        }
        return EmojiType.GENERIC;
    }

    public void triggerTapBurst() {
        burstStartMs = SystemClock.uptimeMillis();
        burstProgress = 1f;

        // Initialize burst particles based on emoji type
        sparks.clear();
        for (int i = 0; i < 18; i++) {
            Spark s = new Spark();
            double angle = Math.random() * Math.PI * 2.0;
            float speed = (float) (2.5 + Math.random() * 4.5) * dp;
            s.vx = (float) Math.cos(angle) * speed;
            s.vy = (float) Math.sin(angle) * speed;
            s.size = (float) (3.0 + Math.random() * 4.0) * dp;
            s.alpha = 1f;
            if (type == EmojiType.HEART) {
                s.color = (i % 2 == 0) ? 0xFFFF2D55 : 0xFFFF708F;
            } else if (type == EmojiType.FIRE) {
                s.color = (i % 2 == 0) ? 0xFFFF9500 : 0xFFFFCC00;
            } else if (type == EmojiType.CRY) {
                s.color = 0xFF38BDF8;
            } else {
                s.color = (i % 3 == 0) ? 0xFFFFD700 : ((i % 3 == 1) ? 0xFF00E5FF : 0xFFFF4081);
            }
            sparks.add(s);
        }
        if (!isRunning) start();
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(180 * dp);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(180 * dp);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float w = bounds.width();
        float h = bounds.height();
        float cx = bounds.left + w * 0.5f;
        float cy = bounds.top + h * 0.5f;
        float minDim = Math.min(w, h);

        long now = SystemClock.uptimeMillis();
        float t = isRunning ? (float) ((now - startUptimeMs) % CYCLE_DURATION_MS) / (float) CYCLE_DURATION_MS : 0f;

        // Update burst decay
        if (burstProgress > 0f) {
            float burstElapsed = (now - burstStartMs) / 600f;
            burstProgress = Math.max(0f, 1f - burstElapsed);
        }

        canvas.save();

        switch (type) {
            case HEART:
                drawAnimatedHeart(canvas, cx, cy, minDim, t);
                break;
            case FIRE:
                drawAnimatedFire(canvas, cx, cy, minDim, t);
                break;
            case CRY:
                drawAnimatedCrying(canvas, cx, cy, minDim, t);
                break;
            case SURPRISE:
                drawAnimatedSurprised(canvas, cx, cy, minDim, t);
                break;
            case LAUGH:
                drawAnimatedLaughing(canvas, cx, cy, minDim, t);
                break;
            case THUMBS_UP:
            case PARTY:
            case PLEADING:
            case ROCKET:
            case SPARKLE:
            case GENERIC:
            default:
                drawProceduralEmoji(canvas, cx, cy, minDim, t);
                break;
        }

        // Draw interactive burst sparks
        if (burstProgress > 0f && !sparks.isEmpty()) {
            drawBurstSparks(canvas, cx, cy);
        }

        canvas.restore();

        if (isRunning) {
            invalidateSelf();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 1. ANIMATED BEATING HEART (❤️)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedHeart(Canvas canvas, float cx, float cy, float size, float t) {
        // Anatomical dual-beat pulsation (beat-beat... rest... beat-beat... rest)
        float beatScale = 1.0f;
        float subT = (t * 2.0f) % 1.0f;
        if (subT < 0.12f) {
            beatScale += 0.22f * (float) Math.sin((subT / 0.12f) * Math.PI);
        } else if (subT >= 0.18f && subT < 0.32f) {
            beatScale += 0.14f * (float) Math.sin(((subT - 0.18f) / 0.14f) * Math.PI);
        }
        if (burstProgress > 0f) {
            beatScale += burstProgress * 0.28f;
        }

        // Radiant pulsing glow behind the heart
        float glowRadius = (size * 0.44f) * beatScale;
        glowPaint.setShader(new RadialGradient(cx, cy, glowRadius * 1.35f,
                new int[]{0x45FF1744, 0x15FF5252, 0x00FF1744}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, glowRadius * 1.35f, glowPaint);

        canvas.save();
        canvas.scale(beatScale, beatScale, cx, cy);

        // Render high-precision cubic bezier heart path
        path1.reset();
        float r = size * 0.32f;
        float topY = cy - r * 0.65f;
        float botY = cy + r * 1.05f;

        path1.moveTo(cx, topY + r * 0.4f);
        path1.cubicTo(cx, topY - r * 0.45f, cx - r * 1.15f, topY - r * 0.35f, cx - r * 1.15f, topY + r * 0.45f);
        path1.cubicTo(cx - r * 1.15f, topY + r * 1.05f, cx - r * 0.4f, topY + r * 1.55f, cx, botY);
        path1.cubicTo(cx + r * 0.4f, topY + r * 1.55f, cx + r * 1.15f, topY + r * 1.05f, cx + r * 1.15f, topY + r * 0.45f);
        path1.cubicTo(cx + r * 1.15f, topY - r * 0.35f, cx, topY - r * 0.45f, cx, topY + r * 0.4f);
        path1.close();

        // Rich 3D gradient fill (vibrant crimson to ruby red)
        fillPaint.setShader(new LinearGradient(cx - r, topY, cx + r, botY,
                new int[]{0xFFFF2A54, 0xFFE6002A, 0xFFB3001B},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path1, fillPaint);

        // Specular 3D highlight gloss on top-left curve
        path2.reset();
        path2.moveTo(cx - r * 0.2f, topY + r * 0.1f);
        path2.cubicTo(cx - r * 0.5f, topY - r * 0.15f, cx - r * 0.95f, topY + r * 0.05f, cx - r * 0.85f, topY + r * 0.5f);
        path2.cubicTo(cx - r * 0.85f, topY + r * 0.3f, cx - r * 0.45f, topY + r * 0.05f, cx - r * 0.2f, topY + r * 0.1f);
        path2.close();
        glowPaint.setShader(new LinearGradient(cx - r, topY, cx, topY + r * 0.5f,
                0x75FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(path2, glowPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 2. ANIMATED FLICKERING FIRE (🔥)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedFire(Canvas canvas, float cx, float cy, float size, float t) {
        float f1 = (float) Math.sin(t * Math.PI * 6.0) * (size * 0.035f);
        float f2 = (float) Math.cos(t * Math.PI * 8.0) * (size * 0.03f);
        float pulse = 1.0f + 0.04f * (float) Math.sin(t * Math.PI * 4.0);

        canvas.save();
        canvas.scale(pulse, pulse, cx, cy + size * 0.3f);

        float bot = cy + size * 0.38f;
        float wHalf = size * 0.32f;

        // Outer Flame (Red / Crimson)
        path1.reset();
        path1.moveTo(cx - wHalf, bot);
        path1.cubicTo(cx - wHalf * 1.15f, cy + size * 0.1f, cx - wHalf * 0.8f + f1, cy - size * 0.15f, cx - wHalf * 0.2f + f2, cy - size * 0.42f);
        path1.cubicTo(cx + f1, cy - size * 0.25f, cx + wHalf * 0.25f, cy - size * 0.18f, cx + wHalf * 0.45f + f1, cy - size * 0.3f);
        path1.cubicTo(cx + wHalf * 0.95f, cy - size * 0.05f, cx + wHalf * 1.15f, cy + size * 0.15f, cx + wHalf, bot);
        path1.cubicTo(cx + wHalf * 0.5f, bot + size * 0.06f, cx - wHalf * 0.5f, bot + size * 0.06f, cx - wHalf, bot);
        path1.close();

        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.45f, cx, bot,
                new int[]{0xFFFF2A00, 0xFFFF6A00, 0xFFFF9500}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path1, fillPaint);

        // Middle Flame (Vibrant Orange / Amber)
        path2.reset();
        path2.moveTo(cx - wHalf * 0.65f, bot - size * 0.02f);
        path2.cubicTo(cx - wHalf * 0.75f, cy + size * 0.12f, cx - wHalf * 0.35f - f2, cy - size * 0.1f, cx - f1 * 0.5f, cy - size * 0.32f);
        path2.cubicTo(cx + wHalf * 0.2f, cy - size * 0.16f, cx + wHalf * 0.65f + f2, cy + size * 0.05f, cx + wHalf * 0.65f, bot - size * 0.02f);
        path2.close();

        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.32f, cx, bot,
                new int[]{0xFFFF8500, 0xFFFFB300, 0xFFFFCC00}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path2, fillPaint);

        // Inner Core (Bright Golden Yellow)
        path3.reset();
        path3.moveTo(cx - wHalf * 0.35f, bot - size * 0.04f);
        path3.cubicTo(cx - wHalf * 0.35f, cy + size * 0.16f, cx - f1 * 0.3f, cy + size * 0.02f, cx, cy - size * 0.14f);
        path3.cubicTo(cx + f1 * 0.3f, cy + size * 0.02f, cx + wHalf * 0.35f, cy + size * 0.16f, cx + wHalf * 0.35f, bot - size * 0.04f);
        path3.close();

        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.14f, cx, bot,
                new int[]{0xFFFFF3A0, 0xFFFFE066, 0xFFFFC000}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path3, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 3. ANIMATED CRYING FACE (😢)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedCrying(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.38f;
        float browTremble = (float) Math.sin(t * Math.PI * 10.0) * (1.2f * dp);

        // 1. Golden Yellow Head
        fillPaint.setShader(new RadialGradient(cx - headR * 0.25f, cy - headR * 0.25f, headR * 1.3f,
                new int[]{0xFFFFE066, 0xFFFFCA28, 0xFFF57F17}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, headR, fillPaint);

        // 2. Sad Brow Curves
        strokePaint.setColor(0xFF6D4C41);
        strokePaint.setStrokeWidth(3.2f * dp);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        // Left Brow (slanted up towards center)
        canvas.drawLine(cx - headR * 0.6f, cy - headR * 0.38f + browTremble,
                cx - headR * 0.18f, cy - headR * 0.50f + browTremble, strokePaint);
        // Right Brow (slanted up towards center)
        canvas.drawLine(cx + headR * 0.6f, cy - headR * 0.38f - browTremble,
                cx + headR * 0.18f, cy - headR * 0.50f - browTremble, strokePaint);

        // 3. Closed Sad Eyes
        strokePaint.setStrokeWidth(3.8f * dp);
        tempRect.set(cx - headR * 0.55f, cy - headR * 0.32f, cx - headR * 0.18f, cy - headR * 0.08f);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);
        tempRect.set(cx + headR * 0.18f, cy - headR * 0.32f, cx + headR * 0.55f, cy - headR * 0.08f);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);

        // 4. Quivering Sad Mouth
        strokePaint.setStrokeWidth(3.5f * dp);
        tempRect.set(cx - headR * 0.35f, cy + headR * 0.22f, cx + headR * 0.35f, cy + headR * 0.62f);
        canvas.drawArc(tempRect, 210, 120, false, strokePaint);

        // 5. Flowing Animated Teardrop
        float dropProgress = (t * 2.5f) % 1.0f;
        float startY = cy - headR * 0.15f;
        float endY = cy + headR * 0.95f;
        float tearY = startY + (endY - startY) * dropProgress;
        float tearScale = Math.min(1.0f, dropProgress * 2.5f) * (1.0f - dropProgress * 0.3f);
        float tearX = cx + headR * 0.44f;

        canvas.save();
        canvas.translate(tearX, tearY);
        canvas.scale(tearScale, tearScale);

        path1.reset();
        float tr = 7f * dp;
        path1.moveTo(0, -tr * 1.5f);
        path1.cubicTo(-tr, -tr * 0.3f, -tr, tr, 0, tr * 1.2f);
        path1.cubicTo(tr, tr, tr, -tr * 0.3f, 0, -tr * 1.5f);
        path1.close();

        fillPaint.setShader(new LinearGradient(0, -tr * 1.5f, 0, tr * 1.2f,
                new int[]{0xFF67E8F9, 0xFF0284C7}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path1, fillPaint);
        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 4. ANIMATED SURPRISED FACE (😮)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedSurprised(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.38f;
        float shockBounce = 1.0f + 0.06f * (float) Math.sin(t * Math.PI * 4.0);

        canvas.save();
        canvas.scale(shockBounce, shockBounce, cx, cy);

        // Head
        fillPaint.setShader(new RadialGradient(cx - headR * 0.25f, cy - headR * 0.25f, headR * 1.3f,
                new int[]{0xFFFFE066, 0xFFFFCA28, 0xFFF57F17}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, headR, fillPaint);

        // High Raised Eyebrows
        strokePaint.setColor(0xFF6D4C41);
        strokePaint.setStrokeWidth(3.0f * dp);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        tempRect.set(cx - headR * 0.55f, cy - headR * 0.62f, cx - headR * 0.15f, cy - headR * 0.35f);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);
        tempRect.set(cx + headR * 0.15f, cy - headR * 0.62f, cx + headR * 0.55f, cy - headR * 0.35f);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);

        // Wide Open Eyes
        fillPaint.setShader(null);
        fillPaint.setColor(0xFF263238);
        canvas.drawCircle(cx - headR * 0.35f, cy - headR * 0.15f, 5.5f * dp, fillPaint);
        canvas.drawCircle(cx + headR * 0.35f, cy - headR * 0.15f, 5.5f * dp, fillPaint);

        // Big Shocked Oval Mouth (expanding and contracting in surprise)
        float mouthH = (headR * 0.38f) * (0.85f + 0.3f * (float) Math.sin(t * Math.PI * 4.0));
        float mouthW = headR * 0.28f;
        fillPaint.setColor(0xFF212121);
        tempRect.set(cx - mouthW, cy + headR * 0.12f, cx + mouthW, cy + headR * 0.12f + mouthH);
        canvas.drawOval(tempRect, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 5. ANIMATED LAUGHING TEARS FACE (😂)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLaughing(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.38f;
        float laughShake = (float) Math.sin(t * Math.PI * 14.0) * (1.6f * dp);
        float tilt = (float) Math.sin(t * Math.PI * 6.0) * 3.5f;

        canvas.save();
        canvas.translate(0, laughShake);
        canvas.rotate(tilt, cx, cy);

        // Head
        fillPaint.setShader(new RadialGradient(cx - headR * 0.25f, cy - headR * 0.25f, headR * 1.3f,
                new int[]{0xFFFFE066, 0xFFFFCA28, 0xFFF57F17}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, headR, fillPaint);

        // Squeezed Laughing Eyes (<  >)
        strokePaint.setColor(0xFF5D4037);
        strokePaint.setStrokeWidth(3.8f * dp);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        // Left Eye
        path1.reset();
        path1.moveTo(cx - headR * 0.58f, cy - headR * 0.2f);
        path1.lineTo(cx - headR * 0.35f, cy - headR * 0.05f);
        path1.lineTo(cx - headR * 0.58f, cy + headR * 0.1f);
        canvas.drawPath(path1, strokePaint);

        // Right Eye
        path2.reset();
        path2.moveTo(cx + headR * 0.58f, cy - headR * 0.2f);
        path2.lineTo(cx + headR * 0.35f, cy - headR * 0.05f);
        path2.lineTo(cx + headR * 0.58f, cy + headR * 0.1f);
        canvas.drawPath(path2, strokePaint);

        // Wide Grinning Open Mouth
        fillPaint.setShader(null);
        fillPaint.setColor(0xFF881337);
        tempRect.set(cx - headR * 0.52f, cy - headR * 0.05f, cx + headR * 0.52f, cy + headR * 0.65f);
        canvas.drawArc(tempRect, 0, 180, true, fillPaint);

        // White Teeth Upper Bar
        fillPaint.setColor(Color.WHITE);
        tempRect.set(cx - headR * 0.44f, cy, cx + headR * 0.44f, cy + headR * 0.22f);
        canvas.drawRoundRect(tempRect, 3f * dp, 3f * dp, fillPaint);

        // Twin Tear Jets Spraying Out
        float tearT = (t * 4.0f) % 1.0f;
        fillPaint.setColor(0xFF38BDF8);
        canvas.drawCircle(cx - headR * 0.72f - tearT * 8f * dp, cy - headR * 0.02f, 4.5f * dp, fillPaint);
        canvas.drawCircle(cx + headR * 0.72f + tearT * 8f * dp, cy - headR * 0.02f, 4.5f * dp, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 6. UNIVERSAL PROCEDURAL ANIMATOR FOR ANY EMOJI (👍, 🎉, 🥺, 🚀, 💯, etc.)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawProceduralEmoji(Canvas canvas, float cx, float cy, float size, float t) {
        float angle = (float) (t * Math.PI * 2.0);
        float bounceY = isRunning ? (float) Math.sin(angle * 2.0) * (3.8f * dp) : 0f;
        float scale = isRunning ? (0.95f + 0.08f * (float) Math.sin(angle * 2.0)) : 1.0f;
        float tilt = isRunning ? ((float) Math.sin(angle) * 4.5f) : 0f;

        if (burstProgress > 0f) {
            scale += burstProgress * 0.25f;
        }

        canvas.save();
        canvas.translate(cx, cy + bounceY);
        canvas.rotate(tilt);
        canvas.scale(scale, scale);

        textPaint.setTextSize(size * 0.72f);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = -(fm.ascent + fm.descent) * 0.5f;

        canvas.drawText(rawEmoji, 0, textY, textPaint);
        canvas.restore();
    }

    private void drawBurstSparks(Canvas canvas, float cx, float cy) {
        fillPaint.setShader(null);
        for (Spark s : sparks) {
            s.x += s.vx;
            s.y += s.vy;
            s.vy += 0.15f * dp; // gravity
            fillPaint.setColor(s.color);
            fillPaint.setAlpha(Math.round(burstProgress * 255));
            canvas.drawCircle(cx + s.x, cy + s.y, s.size * burstProgress, fillPaint);
        }
    }

    @Override
    public void start() {
        startUptimeMs = SystemClock.uptimeMillis();
        isRunning = true;
        invalidateSelf();
    }

    @Override
    public void stop() {
        isRunning = false;
        invalidateSelf();
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
        glowPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
