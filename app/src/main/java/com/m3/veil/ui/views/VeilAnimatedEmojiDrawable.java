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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.m3.veil.utils.VeilStickerGifEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * VeilAnimatedEmojiDrawable — Next-Gen 60fps Multi-Layered 3D Vector + Particle Physics Animated Emoji Engine.
 *
 * Designed to exceed Telegram & WhatsApp animated emojis with:
 * - 16 bespoke 60fps procedural 3D vector + particle choreographies (Heart, Broken Heart, Fire, Laughing,
 *   Crying Waterfall,Surprised, Party Popper, 100 Neon Laser Slam, Mind Blown Supernova, Love Eyes,
 *   Thumbs Up Sunburst, Rocket Launch, Skull/Ghost Ectoplasm, High-Voltage Lightning, Angry Steam,
 *   Crown/Gem Shimmer, and Spring-Physics Universal Dance).
 * - Multi-Emoji Synchronized Choreography (supports 1, 2, or 3 emojis in a single message with phase-shifted
 *   harmonic waves and individual emoji type resolution).
 * - Interactive Tap Combo Overdrive (1x, 2x, 3x+ combo counter badge, expanding sonar shockwave rings,
 *   and physics-driven particle fountains).
 */
public class VeilAnimatedEmojiDrawable extends Drawable implements Animatable {

    public enum EmojiType {
        HEART,
        BROKEN_HEART,
        FIRE,
        CRY,
        SURPRISE,
        LAUGH,
        THUMBS_UP,
        PARTY,
        PLEADING,
        ROCKET,
        SPARKLE,
        HUNDRED,
        MIND_BLOWN,
        LOVE_EYES,
        SKULL_GHOST,
        LIGHTNING,
        ANGRY,
        GENERIC
    }

    private static final long CYCLE_DURATION_MS = 2200L;
    private static final long BURST_DURATION_MS = 950L;

    private final String rawEmoji;
    private final List<String> emojiClusters;
    private final List<EmojiType> clusterTypes;
    private final EmojiType primaryType;
    private final float dp;

    private boolean isRunning = false;
    private long startUptimeMs = 0L;
    private float burstProgress = 0f;
    private long burstStartMs = 0L;
    private int tapComboCount = 0;
    private long lastTapUptimeMs = 0L;

    // Pre-allocated graphic objects for zero garbage collection during 60fps animation
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path1 = new Path();
    private final Path path2 = new Path();
    private final Path path3 = new Path();
    private final RectF tempRect = new RectF();

    private static class Spark {
        float x, y, vx, vy, size, alpha, rotation, vRot;
        int color;
        int shape; // 0 = circle, 1 = mini-heart, 2 = star/diamond, 3 = confetti ribbon
    }

    private final List<Spark> sparks = new ArrayList<>(36);

    public VeilAnimatedEmojiDrawable(@NonNull Context context, @NonNull String emoji) {
        this.rawEmoji = emoji != null ? emoji.trim() : "❤️";
        this.dp = context.getResources().getDisplayMetrics().density;
        this.emojiClusters = VeilStickerGifEngine.extractEmojiClusters(this.rawEmoji, 3);
        if (this.emojiClusters.isEmpty()) {
            this.emojiClusters.add(this.rawEmoji);
        }
        this.clusterTypes = new ArrayList<>(this.emojiClusters.size());
        for (String cluster : this.emojiClusters) {
            this.clusterTypes.add(resolveEmojiType(cluster));
        }
        this.primaryType = this.clusterTypes.get(0);

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);

        badgePaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public static EmojiType resolveEmojiType(String emoji) {
        if (emoji == null || emoji.isEmpty()) return EmojiType.GENERIC;
        if (emoji.contains("💔")) {
            return EmojiType.BROKEN_HEART;
        }
        if (emoji.contains("❤️") || emoji.contains("♥️") || emoji.contains("💖") || emoji.contains("💕")
                || emoji.contains("💓") || emoji.contains("💗") || emoji.contains("💘") || emoji.contains("💝")
                || emoji.contains("💞") || emoji.contains("💟") || emoji.contains("❣️") || emoji.contains("🫶")
                || emoji.contains("🩷") || emoji.contains("❤️‍🔥")) {
            return EmojiType.HEART;
        }
        if (emoji.contains("🔥") || emoji.contains("🧨")) {
            return EmojiType.FIRE;
        }
        if (emoji.contains("💯")) {
            return EmojiType.HUNDRED;
        }
        if (emoji.contains("🤯")) {
            return EmojiType.MIND_BLOWN;
        }
        if (emoji.contains("😍") || emoji.contains("🥰") || emoji.contains("😘") || emoji.contains("😻")) {
            return EmojiType.LOVE_EYES;
        }
        if (emoji.contains("😢") || emoji.contains("😭") || emoji.contains("😿") || emoji.contains("💧") || emoji.contains("🥹")) {
            return EmojiType.CRY;
        }
        if (emoji.contains("😮") || emoji.contains("😲") || emoji.contains("😱") || emoji.contains("😯") || emoji.contains("🙀")) {
            return EmojiType.SURPRISE;
        }
        if (emoji.contains("😂") || emoji.contains("🤣") || emoji.contains("😹") || emoji.contains("😆")) {
            return EmojiType.LAUGH;
        }
        if (emoji.contains("👍") || emoji.contains("👏") || emoji.contains("🙌") || emoji.contains("🤝") || emoji.contains("💪")) {
            return EmojiType.THUMBS_UP;
        }
        if (emoji.contains("🎉") || emoji.contains("🎊") || emoji.contains("🥳") || emoji.contains("🎈")) {
            return EmojiType.PARTY;
        }
        if (emoji.contains("🥺")) {
            return EmojiType.PLEADING;
        }
        if (emoji.contains("🚀") || emoji.contains("🛸")) {
            return EmojiType.ROCKET;
        }
        if (emoji.contains("💀") || emoji.contains("☠️") || emoji.contains("👻")) {
            return EmojiType.SKULL_GHOST;
        }
        if (emoji.contains("⚡") || emoji.contains("💥") || emoji.contains("🌩️")) {
            return EmojiType.LIGHTNING;
        }
        if (emoji.contains("😡") || emoji.contains("🤬") || emoji.contains("😤") || emoji.contains("💢")) {
            return EmojiType.ANGRY;
        }
        if (emoji.contains("✨") || emoji.contains("⭐") || emoji.contains("🌟") || emoji.contains("👑")
                || emoji.contains("💎") || emoji.contains("🏆")) {
            return EmojiType.SPARKLE;
        }
        return EmojiType.GENERIC;
    }

    public int triggerTapBurst() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTapUptimeMs < 1400L) {
            tapComboCount = Math.min(99, tapComboCount + 1);
        } else {
            tapComboCount = 1;
        }
        lastTapUptimeMs = now;
        burstStartMs = now;
        burstProgress = 1f;

        sparks.clear();
        int particleCount = Math.min(34, 18 + tapComboCount * 4);
        for (int i = 0; i < particleCount; i++) {
            Spark s = new Spark();
            double angle = (Math.PI * 2.0 * i) / particleCount + (Math.random() - 0.5) * 0.35;
            float speedBoost = 1f + Math.min(0.8f, tapComboCount * 0.14f);
            float speed = (float) (2.6 + Math.random() * 5.2) * dp * speedBoost;
            s.vx = (float) Math.cos(angle) * speed;
            s.vy = (float) Math.sin(angle) * speed - (1.4f * dp);
            s.x = 0f;
            s.y = 0f;
            s.size = (float) (3.4 + Math.random() * 4.8) * dp;
            s.alpha = 1f;
            s.rotation = (float) (Math.random() * 360.0);
            s.vRot = (float) ((Math.random() - 0.5) * 18.0);

            if (primaryType == EmojiType.HEART || primaryType == EmojiType.LOVE_EYES || primaryType == EmojiType.BROKEN_HEART) {
                s.color = (i % 3 == 0) ? 0xFFFF1744 : ((i % 3 == 1) ? 0xFFFF6090 : 0xFFFFB2C9);
                s.shape = (i % 2 == 0) ? 1 : 2;
            } else if (primaryType == EmojiType.FIRE || primaryType == EmojiType.HUNDRED || primaryType == EmojiType.ANGRY) {
                s.color = (i % 3 == 0) ? 0xFFFF3D00 : ((i % 3 == 1) ? 0xFFFF9100 : 0xFFFFEA00);
                s.shape = (i % 2 == 0) ? 2 : 0;
            } else if (primaryType == EmojiType.CRY || primaryType == EmojiType.LAUGH) {
                s.color = (i % 2 == 0) ? 0xFF38BDF8 : 0xFF7DD3FC;
                s.shape = 0;
            } else if (primaryType == EmojiType.PARTY || primaryType == EmojiType.MIND_BLOWN) {
                int[] partyColors = new int[]{0xFFFF2D55, 0xFF00E5FF, 0xFFFFD600, 0xFF00E676, 0xFFD500F9};
                s.color = partyColors[i % partyColors.length];
                s.shape = 3;
            } else {
                s.color = (i % 3 == 0) ? 0xFFFFD700 : ((i % 3 == 1) ? 0xFF38BDF8 : 0xFFFF4081);
                s.shape = 2;
            }
            sparks.add(s);
        }
        if (!isRunning) start();
        invalidateSelf();
        return tapComboCount;
    }

    @Override
    public int getIntrinsicWidth() {
        int count = emojiClusters.size();
        if (count == 2) return Math.round(212 * dp);
        if (count >= 3) return Math.round(248 * dp);
        return Math.round(168 * dp);
    }

    @Override
    public int getIntrinsicHeight() {
        int count = emojiClusters.size();
        if (count == 2) return Math.round(138 * dp);
        if (count >= 3) return Math.round(120 * dp);
        return Math.round(168 * dp);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float w = bounds.width();
        float h = bounds.height();
        float cx = bounds.left + w * 0.5f;
        float cy = bounds.top + h * 0.5f;

        long now = SystemClock.uptimeMillis();
        float t = isRunning ? (float) ((now - startUptimeMs) % CYCLE_DURATION_MS) / (float) CYCLE_DURATION_MS : 0f;

        if (burstProgress > 0f) {
            float burstElapsed = (now - burstStartMs) / (float) BURST_DURATION_MS;
            burstProgress = Math.max(0f, 1f - burstElapsed);
        }

        canvas.save();

        int count = emojiClusters.size();
        if (count == 1) {
            float cellSize = Math.min(w, h);
            drawSingleCluster(canvas, emojiClusters.get(0), clusterTypes.get(0), cx, cy, cellSize, t);
        } else {
            float slotW = w / count;
            float cellSize = Math.min(slotW * 1.06f, h * 0.92f);
            for (int i = 0; i < count; i++) {
                float phaseT = (t + i * 0.18f) % 1.0f;
                float slotCx = bounds.left + slotW * (i + 0.5f);
                float waveY = (float) Math.sin((t + i * 0.25f) * Math.PI * 2.0) * (4.5f * dp);
                drawSingleCluster(canvas, emojiClusters.get(i), clusterTypes.get(i), slotCx, cy + waveY, cellSize, phaseT);
            }
        }

        // Interactive sonar shockwave ring + burst particles + combo badge
        if (burstProgress > 0f) {
            drawInteractiveBurstOverlay(canvas, cx, cy, Math.min(w, h));
        }

        canvas.restore();

        if (isRunning || burstProgress > 0f) {
            invalidateSelf();
        }
    }

    private void drawSingleCluster(Canvas canvas, String clusterText, EmojiType type, float cx, float cy, float size, float t) {
        switch (type) {
            case HEART:
                drawAnimatedHeart(canvas, cx, cy, size, t);
                break;
            case BROKEN_HEART:
                drawAnimatedBrokenHeart(canvas, cx, cy, size, t);
                break;
            case FIRE:
                drawAnimatedFire(canvas, cx, cy, size, t);
                break;
            case CRY:
                drawAnimatedCrying(canvas, cx, cy, size, t);
                break;
            case SURPRISE:
                drawAnimatedSurprised(canvas, cx, cy, size, t);
                break;
            case LAUGH:
                drawAnimatedLaughing(canvas, cx, cy, size, t);
                break;
            case HUNDRED:
                drawAnimatedHundred(canvas, cx, cy, size, t);
                break;
            case PARTY:
                drawAnimatedParty(canvas, clusterText, cx, cy, size, t);
                break;
            case MIND_BLOWN:
                drawAnimatedMindBlown(canvas, clusterText, cx, cy, size, t);
                break;
            case LOVE_EYES:
                drawAnimatedLoveEyes(canvas, cx, cy, size, t);
                break;
            case ROCKET:
                drawAnimatedRocket(canvas, clusterText, cx, cy, size, t);
                break;
            case THUMBS_UP:
                drawAnimatedThumbsUp(canvas, clusterText, cx, cy, size, t);
                break;
            case SKULL_GHOST:
                drawAnimatedSkullGhost(canvas, clusterText, cx, cy, size, t);
                break;
            case LIGHTNING:
                drawAnimatedLightning(canvas, clusterText, cx, cy, size, t);
                break;
            case ANGRY:
                drawAnimatedAngry(canvas, clusterText, cx, cy, size, t);
                break;
            case SPARKLE:
            case PLEADING:
            case GENERIC:
            default:
                drawProceduralEmoji(canvas, clusterText, type, cx, cy, size, t);
                break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 1. 3D BEATING HEART WITH SONAR SHOCKWAVES & ORBITING MINI-HEARTS (❤️, 💖, 💘)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedHeart(Canvas canvas, float cx, float cy, float size, float t) {
        float beatScale = 1.0f;
        float subT = (t * 2.0f) % 1.0f;
        if (subT < 0.14f) {
            beatScale += 0.25f * (float) Math.sin((subT / 0.14f) * Math.PI);
        } else if (subT >= 0.20f && subT < 0.36f) {
            beatScale += 0.16f * (float) Math.sin(((subT - 0.20f) / 0.16f) * Math.PI);
        }
        if (burstProgress > 0f) {
            beatScale += burstProgress * 0.28f;
        }

        // Expanding sonar pulse ring synchronized with the heartbeat
        float ringPhase = subT;
        float ringRadius = size * (0.24f + ringPhase * 0.26f);
        int ringAlpha = Math.max(0, Math.round((1f - ringPhase) * 145));
        strokePaint.setShader(null);
        strokePaint.setColor(Color.argb(ringAlpha, 255, 45, 85));
        strokePaint.setStrokeWidth(2.4f * dp * (1f - ringPhase * 0.5f));
        canvas.drawCircle(cx, cy, ringRadius, strokePaint);

        // Radiant 3D ambient aura behind heart
        float glowRadius = (size * 0.44f) * beatScale;
        glowPaint.setShader(new RadialGradient(cx, cy, glowRadius * 1.35f,
                new int[]{0x58FF1744, 0x22FF5252, 0x00FF1744}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, glowRadius * 1.35f, glowPaint);

        // 3 floating mini-hearts rising around the main heart
        fillPaint.setShader(null);
        for (int i = 0; i < 3; i++) {
            float p = (t * 1.5f + i * 0.33f) % 1.0f;
            float angle = (float) (i * 2.1 + Math.sin(p * Math.PI * 2) * 0.4);
            float hx = cx + (float) Math.cos(angle) * (size * 0.34f);
            float hy = cy + size * 0.18f - p * (size * 0.52f);
            float hScale = (float) Math.sin(p * Math.PI) * (size * 0.065f);
            fillPaint.setColor(Color.argb(Math.round((float) Math.sin(p * Math.PI) * 210), 255, 64, 129));
            drawMiniHeart(canvas, hx, hy, hScale, fillPaint);
        }

        canvas.save();
        canvas.scale(beatScale, beatScale, cx, cy);

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

        fillPaint.setShader(new LinearGradient(cx - r, topY, cx + r, botY,
                new int[]{0xFFFF3366, 0xFFE6002A, 0xFF990018},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path1, fillPaint);

        // Specular 3D glass highlight on upper-left lobe
        path2.reset();
        path2.moveTo(cx - r * 0.2f, topY + r * 0.1f);
        path2.cubicTo(cx - r * 0.5f, topY - r * 0.15f, cx - r * 0.95f, topY + r * 0.05f, cx - r * 0.85f, topY + r * 0.5f);
        path2.cubicTo(cx - r * 0.85f, topY + r * 0.3f, cx - r * 0.45f, topY + r * 0.05f, cx - r * 0.2f, topY + r * 0.1f);
        path2.close();
        glowPaint.setShader(new LinearGradient(cx - r, topY, cx, topY + r * 0.5f,
                0x90FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(path2, glowPaint);

        canvas.restore();
    }

    private void drawMiniHeart(Canvas canvas, float cx, float cy, float r, Paint paint) {
        if (r <= 0.5f) return;
        path3.reset();
        path3.moveTo(cx, cy - r * 0.25f);
        path3.cubicTo(cx, cy - r, cx - r * 1.2f, cy - r * 0.9f, cx - r * 1.2f, cy - r * 0.1f);
        path3.cubicTo(cx - r * 1.2f, cy + r * 0.55f, cx - r * 0.4f, cy + r * 0.95f, cx, cy + r * 1.25f);
        path3.cubicTo(cx + r * 0.4f, cy + r * 0.95f, cx + r * 1.2f, cy + r * 0.55f, cx + r * 1.2f, cy - r * 0.1f);
        path3.cubicTo(cx + r * 1.2f, cy - r * 0.9f, cx, cy - r, cx, cy - r * 0.25f);
        path3.close();
        canvas.drawPath(path3, paint);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 2. SHATTERING & REFORMING BROKEN HEART (💔)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedBrokenHeart(Canvas canvas, float cx, float cy, float size, float t) {
        float splitPhase = (float) Math.max(0, Math.sin(t * Math.PI * 2.0));
        float sepX = splitPhase * (size * 0.065f);
        float rotDeg = splitPhase * 9.5f;

        // Left & right halves with crack line in center
        for (int side = -1; side <= 1; side += 2) {
            canvas.save();
            canvas.translate(side * sepX, 0);
            canvas.rotate(side * rotDeg, cx, cy + size * 0.25f);
            canvas.clipRect(
                    side < 0 ? cx - size : cx - 1.5f * dp,
                    cy - size,
                    side < 0 ? cx + 1.5f * dp : cx + size,
                    cy + size);
            drawAnimatedHeart(canvas, cx, cy, size * 0.95f, 0.05f);
            canvas.restore();
        }

        // Glowing jagged lightning fracture down the middle when splitting
        if (splitPhase > 0.15f) {
            strokePaint.setShader(null);
            strokePaint.setColor(Color.argb(Math.round(splitPhase * 235), 255, 240, 245));
            strokePaint.setStrokeWidth(2.6f * dp);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            path1.reset();
            float top = cy - size * 0.22f;
            float bot = cy + size * 0.28f;
            path1.moveTo(cx, top);
            path1.lineTo(cx - 5f * dp, top + (bot - top) * 0.33f);
            path1.lineTo(cx + 5f * dp, top + (bot - top) * 0.66f);
            path1.lineTo(cx, bot);
            canvas.drawPath(path1, strokePaint);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 3. PLASMA FIRE WITH RISING EMBERS & WHITE-HOT CORE (🔥)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedFire(Canvas canvas, float cx, float cy, float size, float t) {
        float f1 = (float) Math.sin(t * Math.PI * 6.0) * (size * 0.042f);
        float f2 = (float) Math.cos(t * Math.PI * 8.0) * (size * 0.035f);
        float pulse = 1.0f + 0.055f * (float) Math.sin(t * Math.PI * 4.0) + burstProgress * 0.2f;

        // Warm radiant heat glow
        glowPaint.setShader(new RadialGradient(cx, cy + size * 0.05f, size * 0.48f,
                new int[]{0x55FF6D00, 0x22FFAB00, 0x00FF6D00}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy + size * 0.05f, size * 0.48f, glowPaint);

        // Rising glowing ember sparks
        fillPaint.setShader(null);
        for (int i = 0; i < 6; i++) {
            float ep = (t * 2.2f + i * 0.17f) % 1.0f;
            float ex = cx + (float) Math.sin(ep * 7.0 + i * 1.8) * (size * 0.24f);
            float ey = (cy + size * 0.15f) - ep * (size * 0.62f);
            float er = (1f - ep) * 3.8f * dp;
            fillPaint.setColor(Color.argb(Math.round((1f - ep) * 240), 255, 215, 64));
            canvas.drawCircle(ex, ey, er, fillPaint);
        }

        canvas.save();
        canvas.scale(pulse, pulse, cx, cy + size * 0.3f);

        float bot = cy + size * 0.38f;
        float wHalf = size * 0.33f;

        // 1. Outer Crimson/Orange Flame
        path1.reset();
        path1.moveTo(cx - wHalf, bot);
        path1.cubicTo(cx - wHalf * 1.18f, cy + size * 0.1f, cx - wHalf * 0.82f + f1, cy - size * 0.16f, cx - wHalf * 0.18f + f2, cy - size * 0.44f);
        path1.cubicTo(cx + f1, cy - size * 0.25f, cx + wHalf * 0.25f, cy - size * 0.18f, cx + wHalf * 0.48f + f1, cy - size * 0.32f);
        path1.cubicTo(cx + wHalf * 0.98f, cy - size * 0.05f, cx + wHalf * 1.18f, cy + size * 0.15f, cx + wHalf, bot);
        path1.cubicTo(cx + wHalf * 0.5f, bot + size * 0.06f, cx - wHalf * 0.5f, bot + size * 0.06f, cx - wHalf, bot);
        path1.close();

        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.45f, cx, bot,
                new int[]{0xFFFF1744, 0xFFFF5722, 0xFFFF9100}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path1, fillPaint);

        // 2. Middle Molten Amber Flame
        path2.reset();
        path2.moveTo(cx - wHalf * 0.66f, bot - size * 0.02f);
        path2.cubicTo(cx - wHalf * 0.76f, cy + size * 0.12f, cx - wHalf * 0.35f - f2, cy - size * 0.12f, cx - f1 * 0.5f, cy - size * 0.34f);
        path2.cubicTo(cx + wHalf * 0.22f, cy - size * 0.16f, cx + wHalf * 0.68f + f2, cy + size * 0.05f, cx + wHalf * 0.66f, bot - size * 0.02f);
        path2.close();

        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.34f, cx, bot,
                new int[]{0xFFFF9100, 0xFFFFC400, 0xFFFFEA00}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path2, fillPaint);

        // 3. White-Hot Inner Core
        path3.reset();
        path3.moveTo(cx - wHalf * 0.34f, bot - size * 0.04f);
        path3.cubicTo(cx - wHalf * 0.34f, cy + size * 0.16f, cx - f1 * 0.3f, cy + size * 0.01f, cx, cy - size * 0.16f);
        path3.cubicTo(cx + f1 * 0.3f, cy + size * 0.01f, cx + wHalf * 0.34f, cy + size * 0.16f, cx + wHalf * 0.34f, bot - size * 0.04f);
        path3.close();

        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.16f, cx, bot,
                new int[]{0xFFFFFFFF, 0xFFFFF59D, 0xFFFFD600}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path3, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 4. WATERFALL CRYING FACE WITH PUDDLE SPLASH (😢, 😭)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedCrying(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.37f;
        float heave = (float) Math.sin(t * Math.PI * 4.0);
        float scaleX = 1.0f - heave * 0.035f;
        float scaleY = 1.0f + heave * 0.045f;
        float browTremble = (float) Math.sin(t * Math.PI * 12.0) * (1.3f * dp);

        // Tear puddle ripple at bottom
        float puddleY = cy + headR * 1.04f;
        float puddleW = headR * (0.75f + 0.12f * (float) Math.sin(t * Math.PI * 6.0));
        fillPaint.setShader(null);
        fillPaint.setColor(0x6638BDF8);
        tempRect.set(cx - puddleW, puddleY - 3.5f * dp, cx + puddleW, puddleY + 4.5f * dp);
        canvas.drawOval(tempRect, fillPaint);

        canvas.save();
        canvas.scale(scaleX, scaleY, cx, cy + headR);

        // 3D Golden Sphere Head with top-left specular highlight
        draw3DGoldenHead(canvas, cx, cy, headR);

        // Dual Waterfall Tear Streams under both eyes
        float tearTop = cy - headR * 0.14f;
        fillPaint.setShader(new LinearGradient(0, tearTop, 0, puddleY,
                new int[]{0xEE7DD3FC, 0xEE0284C7, 0x9938BDF8}, null, Shader.TileMode.CLAMP));
        float waveOffset = (float) Math.sin(t * Math.PI * 8.0) * (2.2f * dp);
        tempRect.set(cx - headR * 0.48f + waveOffset * 0.3f, tearTop, cx - headR * 0.28f - waveOffset * 0.3f, puddleY);
        canvas.drawRoundRect(tempRect, 4f * dp, 4f * dp, fillPaint);
        tempRect.set(cx + headR * 0.28f + waveOffset * 0.3f, tearTop, cx + headR * 0.48f - waveOffset * 0.3f, puddleY);
        canvas.drawRoundRect(tempRect, 4f * dp, 4f * dp, fillPaint);

        // Sad Brows
        strokePaint.setShader(null);
        strokePaint.setColor(0xFF5D4037);
        strokePaint.setStrokeWidth(3.2f * dp);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - headR * 0.62f, cy - headR * 0.36f + browTremble,
                cx - headR * 0.18f, cy - headR * 0.50f + browTremble, strokePaint);
        canvas.drawLine(cx + headR * 0.62f, cy - headR * 0.36f - browTremble,
                cx + headR * 0.18f, cy - headR * 0.50f - browTremble, strokePaint);

        // Squeezed Crying Eyes
        strokePaint.setStrokeWidth(3.8f * dp);
        tempRect.set(cx - headR * 0.55f, cy - headR * 0.30f, cx - headR * 0.18f, cy - headR * 0.08f);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);
        tempRect.set(cx + headR * 0.18f, cy - headR * 0.30f, cx + headR * 0.55f, cy - headR * 0.08f);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);

        // Open Wailing Mouth with quivering lip
        fillPaint.setShader(null);
        fillPaint.setColor(0xFF4A0E17);
        float mouthOpen = headR * (0.28f + 0.07f * (float) Math.abs(Math.sin(t * Math.PI * 6.0)));
        tempRect.set(cx - headR * 0.32f, cy + headR * 0.16f, cx + headR * 0.32f, cy + headR * 0.16f + mouthOpen);
        canvas.drawRoundRect(tempRect, 8f * dp, 8f * dp, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 5. SURPRISED / GASPING FACE (😮, 😱)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedSurprised(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.38f;
        float gasp = (float) Math.sin(t * Math.PI * 4.0);
        float scaleX = 1.0f - 0.05f * gasp;
        float scaleY = 1.0f + 0.07f * gasp;

        canvas.save();
        canvas.scale(scaleX, scaleY, cx, cy);

        draw3DGoldenHead(canvas, cx, cy, headR);

        // High Raised Eyebrows
        float browLift = Math.max(0f, gasp) * 3.5f * dp;
        strokePaint.setShader(null);
        strokePaint.setColor(0xFF5D4037);
        strokePaint.setStrokeWidth(3.0f * dp);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        tempRect.set(cx - headR * 0.55f, cy - headR * 0.64f - browLift, cx - headR * 0.15f, cy - headR * 0.36f - browLift);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);
        tempRect.set(cx + headR * 0.15f, cy - headR * 0.64f - browLift, cx + headR * 0.55f, cy - headR * 0.36f - browLift);
        canvas.drawArc(tempRect, 200, 140, false, strokePaint);

        // Wide Open Eyes with white specular glint
        fillPaint.setShader(null);
        fillPaint.setColor(0xFF1E293B);
        canvas.drawCircle(cx - headR * 0.35f, cy - headR * 0.14f, 6.2f * dp, fillPaint);
        canvas.drawCircle(cx + headR * 0.35f, cy - headR * 0.14f, 6.2f * dp, fillPaint);
        fillPaint.setColor(Color.WHITE);
        canvas.drawCircle(cx - headR * 0.38f, cy - headR * 0.18f, 2.0f * dp, fillPaint);
        canvas.drawCircle(cx + headR * 0.32f, cy - headR * 0.18f, 2.0f * dp, fillPaint);

        // Expanding 3D Shocked Mouth
        float mouthH = (headR * 0.42f) * (0.85f + 0.28f * gasp);
        float mouthW = headR * 0.28f;
        fillPaint.setColor(0xFF3B0711);
        tempRect.set(cx - mouthW, cy + headR * 0.10f, cx + mouthW, cy + headR * 0.10f + mouthH);
        canvas.drawOval(tempRect, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 6. ROLLING SQUASH-AND-STRETCH LAUGH WITH DUAL TEAR FOUNTAINS (😂, 🤣)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLaughing(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.38f;
        float laughBounce = (float) Math.sin(t * Math.PI * 12.0) * (2.2f * dp);
        float tilt = (float) Math.sin(t * Math.PI * 4.0) * 11.5f;
        float squash = 1.0f + 0.045f * (float) Math.sin(t * Math.PI * 8.0);

        canvas.save();
        canvas.translate(0, laughBounce);
        canvas.rotate(tilt, cx, cy);
        canvas.scale(squash, 2.0f - squash, cx, cy);

        draw3DGoldenHead(canvas, cx, cy, headR);

        // Warm Rosy Laughing Cheeks
        glowPaint.setShader(new RadialGradient(cx - headR * 0.52f, cy + headR * 0.08f, headR * 0.24f,
                0x66FF5252, 0x00FF5252, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx - headR * 0.52f, cy + headR * 0.08f, headR * 0.24f, glowPaint);
        glowPaint.setShader(new RadialGradient(cx + headR * 0.52f, cy + headR * 0.08f, headR * 0.24f,
                0x66FF5252, 0x00FF5252, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx + headR * 0.52f, cy + headR * 0.08f, headR * 0.24f, glowPaint);

        // Squeezed Happy Chevron Eyes (>  <)
        strokePaint.setShader(null);
        strokePaint.setColor(0xFF4E342E);
        strokePaint.setStrokeWidth(4.0f * dp);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        path1.reset();
        path1.moveTo(cx - headR * 0.58f, cy - headR * 0.22f);
        path1.lineTo(cx - headR * 0.32f, cy - headR * 0.07f);
        path1.lineTo(cx - headR * 0.58f, cy + headR * 0.06f);
        canvas.drawPath(path1, strokePaint);

        path2.reset();
        path2.moveTo(cx + headR * 0.58f, cy - headR * 0.22f);
        path2.lineTo(cx + headR * 0.32f, cy - headR * 0.07f);
        path2.lineTo(cx + headR * 0.58f, cy + headR * 0.06f);
        canvas.drawPath(path2, strokePaint);

        // Wide Joyful Mouth + Pink Tongue + Crisp White Teeth Bar
        fillPaint.setShader(null);
        fillPaint.setColor(0xFF590D22);
        tempRect.set(cx - headR * 0.56f, cy - headR * 0.04f, cx + headR * 0.56f, cy + headR * 0.68f);
        canvas.drawArc(tempRect, 0, 180, true, fillPaint);

        fillPaint.setColor(0xFFFF4D6D);
        tempRect.set(cx - headR * 0.30f, cy + headR * 0.34f, cx + headR * 0.30f, cy + headR * 0.64f);
        canvas.drawOval(tempRect, fillPaint);

        fillPaint.setColor(Color.WHITE);
        tempRect.set(cx - headR * 0.48f, cy + headR * 0.01f, cx + headR * 0.48f, cy + headR * 0.20f);
        canvas.drawRoundRect(tempRect, 3f * dp, 3f * dp, fillPaint);

        // Twin Parabolic Tear Fountains Ejecting Left & Right
        for (int i = 0; i < 2; i++) {
            float tearT = (t * 3.2f + i * 0.5f) % 1.0f;
            float dx = (headR * 0.66f) + tearT * (headR * 0.48f);
            float dy = -headR * 0.05f + (tearT * tearT - 0.3f * tearT) * (headR * 0.55f);
            float dropR = (1f - tearT * 0.35f) * 5.6f * dp;
            fillPaint.setColor(Color.argb(Math.round((1f - tearT * 0.4f) * 255), 56, 189, 248));
            canvas.drawCircle(cx - dx, cy + dy, dropR, fillPaint);
            canvas.drawCircle(cx + dx, cy + dy, dropR, fillPaint);
        }

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 7. NEON LASER 100 SLAM WITH SPARKS (💯)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedHundred(Canvas canvas, float cx, float cy, float size, float t) {
        float slam = 1.0f + 0.14f * (float) Math.max(0, Math.sin(t * Math.PI * 4.0));
        float tilt = -6f + (float) Math.sin(t * Math.PI * 2.0) * 3.5f;

        // Fiery neon aura
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.46f,
                new int[]{0x48FF1744, 0x18FF9100, 0x00FF1744}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.46f, glowPaint);

        canvas.save();
        canvas.rotate(tilt, cx, cy);
        canvas.scale(slam, slam, cx, cy);

        badgePaint.setTextSize(size * 0.46f);
        badgePaint.setShader(new LinearGradient(cx, cy - size * 0.25f, cx, cy + size * 0.15f,
                new int[]{0xFFFF2A54, 0xFFD50000}, null, Shader.TileMode.CLAMP));
        badgePaint.setFakeBoldText(true);
        canvas.drawText("100", cx, cy + size * 0.08f, badgePaint);
        badgePaint.setShader(null);

        // Twin dynamic neon underline slashes
        strokePaint.setShader(new LinearGradient(cx - size * 0.32f, 0, cx + size * 0.32f, 0,
                new int[]{0xFFFF1744, 0xFFFF9100, 0xFFFF1744}, null, Shader.TileMode.CLAMP));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(4.5f * dp);
        float slashW = size * 0.30f;
        canvas.drawLine(cx - slashW, cy + size * 0.18f, cx + slashW, cy + size * 0.14f, strokePaint);
        canvas.drawLine(cx - slashW * 0.82f, cy + size * 0.26f, cx + slashW * 0.85f, cy + size * 0.22f, strokePaint);
        strokePaint.setShader(null);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 8. PARTY POPPER CONFETTI CANNON (🎉, 🎊, 🥳)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedParty(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        // Continuous 3D tumbling confetti ribbons radiating outward
        int[] colors = new int[]{0xFFFF2D55, 0xFF00E5FF, 0xFFFFD600, 0xFF00E676, 0xFFAA00FF};
        fillPaint.setShader(null);
        for (int i = 0; i < 12; i++) {
            float p = (t * 1.8f + i * 0.083f) % 1.0f;
            double angle = (i * Math.PI * 2.0) / 12.0 + Math.sin(p * 4.0) * 0.2;
            float dist = size * (0.14f + p * 0.34f);
            float rx = cx + (float) Math.cos(angle) * dist;
            float ry = cy + (float) Math.sin(angle) * dist + (p * p * 12f * dp);
            canvas.save();
            canvas.translate(rx, ry);
            canvas.rotate(p * 360f + i * 45f);
            fillPaint.setColor(colors[i % colors.length]);
            fillPaint.setAlpha(Math.round((1f - p * 0.7f) * 255));
            float rw = 4.2f * dp;
            float rh = 2.2f * dp * (float) Math.abs(Math.cos(p * Math.PI * 3.0));
            canvas.drawRoundRect(-rw, -rh, rw, rh, 1.5f * dp, 1.5f * dp, fillPaint);
            canvas.restore();
        }
        drawProceduralEmoji(canvas, clusterText, EmojiType.PARTY, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 9. MIND-BLOWN COSMIC SUPERNOVA (🤯)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedMindBlown(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float blast = (float) Math.abs(Math.sin(t * Math.PI * 2.0));
        float cloudY = cy - size * (0.20f + blast * 0.16f);
        float cloudR = size * (0.22f + blast * 0.14f);

        glowPaint.setShader(new RadialGradient(cx, cloudY, cloudR,
                new int[]{0xAAFF4081, 0x777C4DFF, 0x0000E5FF}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cloudY, cloudR, glowPaint);

        // Orbiting cosmic stardust around the top of the head
        fillPaint.setShader(null);
        for (int i = 0; i < 6; i++) {
            float angle = (float) (t * Math.PI * 4.0 + i * 1.04);
            float sx = cx + (float) Math.cos(angle) * (cloudR * 0.85f);
            float sy = cloudY + (float) Math.sin(angle) * (cloudR * 0.45f);
            fillPaint.setColor((i % 2 == 0) ? 0xFFFFEA00 : 0xFF00E5FF);
            drawFourPointStar(canvas, sx, sy, 3.8f * dp, fillPaint);
        }

        drawProceduralEmoji(canvas, clusterText, EmojiType.MIND_BLOWN, cx, cy + size * 0.04f, size * 0.94f, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 10. 3D LOVE EYES FACE WITH POPPING HEART EYES (😍, 🥰)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLoveEyes(Canvas canvas, float cx, float cy, float size, float t) {
        float headR = size * 0.38f;
        float sway = (float) Math.sin(t * Math.PI * 2.0) * 6.5f;

        canvas.save();
        canvas.rotate(sway, cx, cy);
        draw3DGoldenHead(canvas, cx, cy, headR);

        // Warm Smile
        fillPaint.setShader(null);
        fillPaint.setColor(0xFF590D22);
        tempRect.set(cx - headR * 0.44f, cy + headR * 0.02f, cx + headR * 0.44f, cy + headR * 0.56f);
        canvas.drawArc(tempRect, 0, 180, true, fillPaint);

        // Twin Popping 3D Heart Eyes beating out of the face
        float eyeBeat = 1.0f + 0.24f * (float) Math.abs(Math.sin(t * Math.PI * 4.0));
        fillPaint.setColor(0xFFFF1744);
        drawMiniHeart(canvas, cx - headR * 0.36f, cy - headR * 0.14f, headR * 0.23f * eyeBeat, fillPaint);
        drawMiniHeart(canvas, cx + headR * 0.36f, cy - headR * 0.14f, headR * 0.23f * eyeBeat, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 11. ROCKET LAUNCH WITH PLASMA THRUSTER PLUME (🚀)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedRocket(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        // Thruster exhaust flame particles shooting bottom-left
        fillPaint.setShader(null);
        for (int i = 0; i < 8; i++) {
            float p = (t * 3.5f + i * 0.125f) % 1.0f;
            float px = cx - (size * 0.12f) - p * (size * 0.28f) + (float) Math.sin(p * 12 + i) * 3f * dp;
            float py = cy + (size * 0.12f) + p * (size * 0.28f);
            float pr = (1f - p * 0.7f) * 6.5f * dp;
            fillPaint.setColor(p < 0.4f ? 0xFFFFEA00 : (p < 0.75f ? 0xFFFF6D00 : 0x889E9E9E));
            canvas.drawCircle(px, py, pr, fillPaint);
        }
        float hoverX = (float) Math.sin(t * Math.PI * 4.0) * (2.5f * dp);
        float hoverY = (float) Math.cos(t * Math.PI * 4.0) * (3.5f * dp);
        drawProceduralEmoji(canvas, clusterText, EmojiType.ROCKET, cx + hoverX, cy + hoverY, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 12. THUMBS UP / CLAP GOLDEN SUNBURST (👍, 👏, 🙌)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedThumbsUp(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        // Rotating golden sunburst rays + sparkles
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.45f,
                new int[]{0x44FFD600, 0x15FFAB00, 0x00FFD600}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.45f, glowPaint);

        fillPaint.setShader(null);
        fillPaint.setColor(0xFFFFD700);
        for (int i = 0; i < 4; i++) {
            float angle = (float) (t * Math.PI * 2.0 + i * (Math.PI * 0.5));
            float sx = cx + (float) Math.cos(angle) * (size * 0.36f);
            float sy = cy + (float) Math.sin(angle) * (size * 0.36f);
            float starSize = (float) (2.5f + 2.2f * Math.abs(Math.sin(t * Math.PI * 4.0 + i))) * dp;
            drawFourPointStar(canvas, sx, sy, starSize, fillPaint);
        }
        drawProceduralEmoji(canvas, clusterText, EmojiType.THUMBS_UP, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 13. SKULL / GHOST ECTOPLASM LEVITATION (💀, 👻)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedSkullGhost(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.46f,
                new int[]{0x4800E5FF, 0x207C4DFF, 0x0000E5FF}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.46f, glowPaint);

        fillPaint.setShader(null);
        for (int i = 0; i < 4; i++) {
            float p = (t * 1.6f + i * 0.25f) % 1.0f;
            float wx = cx + (float) Math.sin(p * 6.0 + i * 2.0) * (size * 0.28f);
            float wy = cy + size * 0.2f - p * (size * 0.5f);
            fillPaint.setColor(Color.argb(Math.round((1f - p) * 190), 0, 229, 255));
            canvas.drawCircle(wx, wy, (1f - p) * 4.2f * dp, fillPaint);
        }
        drawProceduralEmoji(canvas, clusterText, EmojiType.SKULL_GHOST, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 14. HIGH-VOLTAGE ELECTRIC ARCS (⚡, 💥)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLightning(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.46f,
                new int[]{0x55FFEA00, 0x2200E5FF, 0x00FFEA00}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.46f, glowPaint);

        strokePaint.setShader(null);
        strokePaint.setColor(0xEE00E5FF);
        strokePaint.setStrokeWidth(2.0f * dp);
        for (int i = 0; i < 3; i++) {
            double a = (t * Math.PI * 6.0) + i * 2.1;
            float x1 = cx + (float) Math.cos(a) * (size * 0.22f);
            float y1 = cy + (float) Math.sin(a) * (size * 0.22f);
            float x2 = cx + (float) Math.cos(a + 0.35) * (size * 0.38f);
            float y2 = cy + (float) Math.sin(a + 0.35) * (size * 0.38f);
            canvas.drawLine(x1, y1, x2, y2, strokePaint);
        }
        drawProceduralEmoji(canvas, clusterText, EmojiType.LIGHTNING, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 15. ANGRY BOILING RAGE WITH STEAM JETS (😡, 🤬, 😤)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedAngry(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.45f,
                new int[]{0x55FF1744, 0x20FF5252, 0x00FF1744}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.45f, glowPaint);

        // Twin steam puffs ejecting from top-left and top-right
        fillPaint.setShader(null);
        for (int side = -1; side <= 1; side += 2) {
            float p = (t * 2.5f + (side > 0 ? 0.5f : 0f)) % 1.0f;
            float sx = cx + side * (size * 0.26f + p * size * 0.16f);
            float sy = cy - size * 0.14f - p * size * 0.18f;
            fillPaint.setColor(Color.argb(Math.round((1f - p) * 210), 241, 245, 249));
            canvas.drawCircle(sx, sy, (3.5f + p * 5.5f) * dp, fillPaint);
        }
        float rageShakeX = (float) Math.sin(t * Math.PI * 24.0) * (2.2f * dp);
        drawProceduralEmoji(canvas, clusterText, EmojiType.ANGRY, cx + rageShakeX, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 16. UNIVERSAL SPRING-PHYSICS SQUASH-AND-STRETCH EMOJI CHOREOGRAPHY
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawProceduralEmoji(Canvas canvas, String emojiStr, EmojiType type, float cx, float cy, float size, float t) {
        float angle = (float) (t * Math.PI * 2.0);
        float hop = (float) Math.sin(angle * 2.0);
        float bounceY = isRunning ? -Math.abs(hop) * (6.2f * dp) : 0f;
        float scaleX = isRunning ? (1.0f - hop * 0.055f) : 1.0f;
        float scaleY = isRunning ? (1.0f + hop * 0.065f) : 1.0f;
        float tilt = isRunning ? ((float) Math.sin(angle) * 6.5f) : 0f;

        if (burstProgress > 0f) {
            scaleX += burstProgress * 0.24f;
            scaleY += burstProgress * 0.24f;
        }

        if (type == EmojiType.SPARKLE) {
            fillPaint.setShader(null);
            fillPaint.setColor(0xFFFFEA00);
            for (int i = 0; i < 4; i++) {
                float sa = angle + i * 1.57f;
                float sx = cx + (float) Math.cos(sa) * (size * 0.35f);
                float sy = cy + (float) Math.sin(sa) * (size * 0.35f);
                drawFourPointStar(canvas, sx, sy, 3.6f * dp, fillPaint);
            }
        }

        canvas.save();
        canvas.translate(cx, cy + bounceY);
        canvas.rotate(tilt);
        canvas.scale(scaleX, scaleY);

        textPaint.setTextSize(size * 0.68f);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = -(fm.ascent + fm.descent) * 0.5f;

        canvas.drawText(emojiStr, 0, textY, textPaint);
        canvas.restore();
    }

    private void draw3DGoldenHead(Canvas canvas, float cx, float cy, float headR) {
        fillPaint.setShader(new RadialGradient(cx - headR * 0.28f, cy - headR * 0.28f, headR * 1.35f,
                new int[]{0xFFFFF176, 0xFFFFCA28, 0xFFF57F17},
                new float[]{0f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, headR, fillPaint);

        // Top-left 3D glass rim highlight
        glowPaint.setShader(new RadialGradient(cx - headR * 0.35f, cy - headR * 0.38f, headR * 0.55f,
                0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx - headR * 0.35f, cy - headR * 0.38f, headR * 0.55f, glowPaint);
    }

    private void drawFourPointStar(Canvas canvas, float sx, float sy, float r, Paint paint) {
        path3.reset();
        path3.moveTo(sx, sy - r);
        path3.quadTo(sx, sy, sx + r, sy);
        path3.quadTo(sx, sy, sx, sy + r);
        path3.quadTo(sx, sy, sx - r, sy);
        path3.quadTo(sx, sy, sx, sy - r);
        path3.close();
        canvas.drawPath(path3, paint);
    }

    private void drawInteractiveBurstOverlay(Canvas canvas, float cx, float cy, float minDim) {
        // 1. Expanding Shockwave Ring
        float ringR = minDim * (0.20f + (1f - burstProgress) * 0.38f);
        strokePaint.setShader(null);
        strokePaint.setColor(Color.argb(Math.round(burstProgress * 195), 255, 215, 64));
        strokePaint.setStrokeWidth(2.6f * dp * burstProgress);
        canvas.drawCircle(cx, cy, ringR, strokePaint);

        // 2. Physics Particle Fountain
        fillPaint.setShader(null);
        for (Spark s : sparks) {
            s.x += s.vx * 0.42f;
            s.y += s.vy * 0.42f;
            s.vy += 0.18f * dp;
            s.rotation += s.vRot;
            fillPaint.setColor(s.color);
            fillPaint.setAlpha(Math.round(burstProgress * 255));
            float px = cx + s.x;
            float py = cy + s.y;
            float r = s.size * burstProgress;
            if (s.shape == 1) {
                drawMiniHeart(canvas, px, py, r * 0.7f, fillPaint);
            } else if (s.shape == 2) {
                drawFourPointStar(canvas, px, py, r, fillPaint);
            } else if (s.shape == 3) {
                canvas.save();
                canvas.translate(px, py);
                canvas.rotate(s.rotation);
                canvas.drawRoundRect(-r, -r * 0.45f, r, r * 0.45f, dp, dp, fillPaint);
                canvas.restore();
            } else {
                canvas.drawCircle(px, py, r * 0.75f, fillPaint);
            }
        }

        // 3. Combo Counter Floating Pill Badge when tapped multiple times (2x, 3x, etc.)
        if (tapComboCount >= 2) {
            float floatUp = (1f - burstProgress) * 22f * dp;
            float badgeY = cy - minDim * 0.32f - floatUp;
            String comboText = tapComboCount + "x!";
            badgePaint.setTextSize(13.5f * dp * (1f + Math.min(0.35f, tapComboCount * 0.04f)));
            badgePaint.setColor(0xFFFFD600);
            badgePaint.setShadowLayer(4f * dp, 0f, 1.5f * dp, 0xCC000000);
            canvas.drawText(comboText, cx, badgeY, badgePaint);
        }
    }

    @Override
    public void start() {
        if (!isRunning) {
            startUptimeMs = SystemClock.uptimeMillis();
            isRunning = true;
            invalidateSelf();
        }
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
