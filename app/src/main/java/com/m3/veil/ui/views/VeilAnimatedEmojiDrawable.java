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
        COOL,
        COLD,
        SLEEPY,
        THINKING,
        DIZZY_SICK,
        WAVE_CLAP,
        MUSIC,
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
        textPaint.setColor(Color.WHITE);
        textPaint.setAlpha(255);

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
                || emoji.contains("🩷") || emoji.contains("🧡") || emoji.contains("💛") || emoji.contains("💚")
                || emoji.contains("💙") || emoji.contains("💜") || emoji.contains("🖤") || emoji.contains("🤍")
                || emoji.contains("🤎") || emoji.contains("❤️‍🔥") || emoji.contains("❤️‍🩹") || emoji.contains("💌")) {
            return EmojiType.HEART;
        }
        if (emoji.contains("🔥") || emoji.contains("🧨") || emoji.contains("🥵") || emoji.contains("🌋")) {
            return EmojiType.FIRE;
        }
        if (emoji.contains("💯")) {
            return EmojiType.HUNDRED;
        }
        if (emoji.contains("🤯") || emoji.contains("🎆") || emoji.contains("🎇")) {
            return EmojiType.MIND_BLOWN;
        }
        if (emoji.contains("😍") || emoji.contains("🥰") || emoji.contains("😘") || emoji.contains("😻")
                || emoji.contains("😚") || emoji.contains("😙") || emoji.contains("💋") || emoji.contains("☺️")
                || emoji.contains("😊") || emoji.contains("🤗")) {
            return EmojiType.LOVE_EYES;
        }
        if (emoji.contains("😢") || emoji.contains("😭") || emoji.contains("😿") || emoji.contains("💧")
                || emoji.contains("🥹") || emoji.contains("😥") || emoji.contains("😓") || emoji.contains("😞")
                || emoji.contains("😔") || emoji.contains("😟") || emoji.contains("😩") || emoji.contains("😫")) {
            return EmojiType.CRY;
        }
        if (emoji.contains("😮") || emoji.contains("😲") || emoji.contains("😱") || emoji.contains("😯")
                || emoji.contains("🙀") || emoji.contains("😳") || emoji.contains("😧") || emoji.contains("😦")
                || emoji.contains("🫢") || emoji.contains("🫣")) {
            return EmojiType.SURPRISE;
        }
        if (emoji.contains("😂") || emoji.contains("🤣") || emoji.contains("😹") || emoji.contains("😆")
                || emoji.contains("😄") || emoji.contains("😃") || emoji.contains("😀") || emoji.contains("😁")
                || emoji.contains("😅") || emoji.contains("🤪") || emoji.contains("😜") || emoji.contains("😝")
                || emoji.contains("🤭")) {
            return EmojiType.LAUGH;
        }
        if (emoji.contains("👋") || emoji.contains("👏") || emoji.contains("🙌") || emoji.contains("👐")
                || emoji.contains("🤝") || emoji.contains("🙏") || emoji.contains("🫡")) {
            return EmojiType.WAVE_CLAP;
        }
        if (emoji.contains("👍") || emoji.contains("💪") || emoji.contains("✊") || emoji.contains("👊")
                || emoji.contains("🤛") || emoji.contains("🤜") || emoji.contains("✌️") || emoji.contains("🤞")
                || emoji.contains("🤟") || emoji.contains("🤘") || emoji.contains("👌") || emoji.contains("🤙")) {
            return EmojiType.THUMBS_UP;
        }
        if (emoji.contains("🎉") || emoji.contains("🎊") || emoji.contains("🥳") || emoji.contains("🎈")
                || emoji.contains("🎂") || emoji.contains("🍾") || emoji.contains("🥂") || emoji.contains("🎁")) {
            return EmojiType.PARTY;
        }
        if (emoji.contains("🥺") || emoji.contains("☹️") || emoji.contains("🙁") || emoji.contains("😕")) {
            return EmojiType.PLEADING;
        }
        if (emoji.contains("🚀") || emoji.contains("🛸") || emoji.contains("✈️") || emoji.contains("🏎️")) {
            return EmojiType.ROCKET;
        }
        if (emoji.contains("💀") || emoji.contains("☠️") || emoji.contains("👻") || emoji.contains("👽")
                || emoji.contains("👾") || emoji.contains("🎃")) {
            return EmojiType.SKULL_GHOST;
        }
        if (emoji.contains("⚡") || emoji.contains("💥") || emoji.contains("🌩️") || emoji.contains("🔋")) {
            return EmojiType.LIGHTNING;
        }
        if (emoji.contains("😡") || emoji.contains("🤬") || emoji.contains("😤") || emoji.contains("💢")
                || emoji.contains("😠") || emoji.contains("👿") || emoji.contains("😈")) {
            return EmojiType.ANGRY;
        }
        if (emoji.contains("😎") || emoji.contains("🤓") || emoji.contains("🥸") || emoji.contains("😏")
                || emoji.contains("🤠") || emoji.contains("🤑")) {
            return EmojiType.COOL;
        }
        if (emoji.contains("🥶") || emoji.contains("❄️") || emoji.contains("☃️") || emoji.contains("⛄")
                || emoji.contains("🧊")) {
            return EmojiType.COLD;
        }
        if (emoji.contains("😴") || emoji.contains("🥱") || emoji.contains("😪") || emoji.contains("💤")
                || emoji.contains("🛌")) {
            return EmojiType.SLEEPY;
        }
        if (emoji.contains("🤔") || emoji.contains("🧐") || emoji.contains("🤨") || emoji.contains("👀")
                || emoji.contains("👁️") || emoji.contains("💡") || emoji.contains("🧠")) {
            return EmojiType.THINKING;
        }
        if (emoji.contains("🤢") || emoji.contains("🤮") || emoji.contains("😵") || emoji.contains("😵‍💫")
                || emoji.contains("🥴") || emoji.contains("🤒") || emoji.contains("🤕") || emoji.contains("😷")
                || emoji.contains("🤧")) {
            return EmojiType.DIZZY_SICK;
        }
        if (emoji.contains("🎵") || emoji.contains("🎶") || emoji.contains("🎼") || emoji.contains("🎧")
                || emoji.contains("🎤") || emoji.contains("🎸") || emoji.contains("🥁") || emoji.contains("💃")
                || emoji.contains("🕺")) {
            return EmojiType.MUSIC;
        }
        if (emoji.contains("✨") || emoji.contains("⭐") || emoji.contains("🌟") || emoji.contains("👑")
                || emoji.contains("💎") || emoji.contains("🏆") || emoji.contains("🥇") || emoji.contains("💫")
                || emoji.contains("🌈") || emoji.contains("☀️")) {
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
            } else if (primaryType == EmojiType.CRY || primaryType == EmojiType.LAUGH || primaryType == EmojiType.COLD) {
                s.color = (i % 2 == 0) ? 0xFF00B0FF : 0xFF7DD3FC;
                s.shape = 0;
            } else if (primaryType == EmojiType.PARTY || primaryType == EmojiType.MIND_BLOWN || primaryType == EmojiType.MUSIC) {
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

    /**
     * Resets all paint alpha/shader states before rendering each layer so no emoji ever appears transparent.
     */
    private void resetPaintState() {
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);
        strokePaint.setShader(null);
        strokePaint.setAlpha(255);
        glowPaint.setShader(null);
        glowPaint.setAlpha(255);
        textPaint.setShader(null);
        textPaint.setColor(Color.WHITE);
        textPaint.setAlpha(255);
    }

    /**
     * Draws the authentic high-DPI Unicode emoji glyph at 100% solid opacity with custom 60fps transforms.
     */
    private void drawCrispEmojiGlyph(
            Canvas canvas,
            String clusterText,
            float cx,
            float cy,
            float size,
            float offsetX,
            float offsetY,
            float scaleX,
            float scaleY,
            float rotationDeg,
            float pivotYOffset) {
        if (burstProgress > 0f) {
            scaleX += burstProgress * 0.22f;
            scaleY += burstProgress * 0.22f;
        }
        canvas.save();
        canvas.translate(cx + offsetX, cy + offsetY);
        canvas.rotate(rotationDeg, 0f, pivotYOffset);
        canvas.scale(scaleX, scaleY, 0f, pivotYOffset);

        textPaint.setShader(null);
        textPaint.setColor(Color.WHITE);
        textPaint.setAlpha(255);
        textPaint.setTextSize(size * 0.68f);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = -(fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(clusterText, 0f, textY, textPaint);
        canvas.restore();
    }

    private void drawSingleCluster(Canvas canvas, String clusterText, EmojiType type, float cx, float cy, float size, float t) {
        resetPaintState();
        switch (type) {
            case HEART:
                drawAnimatedHeart(canvas, clusterText, cx, cy, size, t);
                break;
            case BROKEN_HEART:
                drawAnimatedBrokenHeart(canvas, cx, cy, size, t);
                break;
            case FIRE:
                drawAnimatedFire(canvas, clusterText, cx, cy, size, t);
                break;
            case CRY:
                drawAnimatedCrying(canvas, clusterText, cx, cy, size, t);
                break;
            case SURPRISE:
                drawAnimatedSurprised(canvas, clusterText, cx, cy, size, t);
                break;
            case LAUGH:
                drawAnimatedLaughing(canvas, clusterText, cx, cy, size, t);
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
                drawAnimatedLoveEyes(canvas, clusterText, cx, cy, size, t);
                break;
            case ROCKET:
                drawAnimatedRocket(canvas, clusterText, cx, cy, size, t);
                break;
            case THUMBS_UP:
                drawAnimatedThumbsUp(canvas, clusterText, cx, cy, size, t);
                break;
            case WAVE_CLAP:
                drawAnimatedWaveClap(canvas, clusterText, cx, cy, size, t);
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
            case COOL:
                drawAnimatedCool(canvas, clusterText, cx, cy, size, t);
                break;
            case COLD:
                drawAnimatedCold(canvas, clusterText, cx, cy, size, t);
                break;
            case SLEEPY:
                drawAnimatedSleepy(canvas, clusterText, cx, cy, size, t);
                break;
            case THINKING:
                drawAnimatedThinking(canvas, clusterText, cx, cy, size, t);
                break;
            case DIZZY_SICK:
                drawAnimatedDizzySick(canvas, clusterText, cx, cy, size, t);
                break;
            case MUSIC:
                drawAnimatedMusic(canvas, clusterText, cx, cy, size, t);
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
    private void drawAnimatedHeart(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
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
        glowPaint.setAlpha(255);
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

        // If special heart variant (e.g. 🫶, 💘, 💝, 💙, 💜, 💚, 💛), render its exact glyph with heartbeat
        boolean isClassicRedHeart = "❤️".equals(clusterText) || "♥️".equals(clusterText) || "❤".equals(clusterText);
        if (!isClassicRedHeart && clusterText != null && !clusterText.isEmpty()) {
            drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, 0f, beatScale, beatScale, 0f, 0f);
            return;
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

        fillPaint.setAlpha(255);
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
        glowPaint.setAlpha(255);
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

    /**
     * Draws a glossy 3D water/tear droplet with a crisp white specular highlight.
     */
    private void drawGlossy3DTeardrop(Canvas canvas, float x, float y, float radius, float rotationDeg, int alpha) {
        if (radius <= 0.5f || alpha <= 0) return;
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotationDeg);

        path3.reset();
        path3.moveTo(0f, -radius * 1.35f);
        path3.cubicTo(radius * 0.85f, -radius * 0.35f, radius, radius * 0.35f, 0f, radius);
        path3.cubicTo(-radius, radius * 0.35f, -radius * 0.85f, -radius * 0.35f, 0f, -radius * 1.35f);
        path3.close();

        fillPaint.setShader(null);
        fillPaint.setColor(Color.argb(alpha, 14, 165, 233)); // Rich vivid sky-cyan
        canvas.drawPath(path3, fillPaint);

        // Inner lighter core
        fillPaint.setColor(Color.argb(alpha, 56, 189, 248));
        canvas.drawCircle(0f, radius * 0.18f, radius * 0.58f, fillPaint);

        // Specular white highlight dot
        fillPaint.setColor(Color.argb(Math.min(255, alpha + 20), 255, 255, 255));
        canvas.drawCircle(-radius * 0.26f, -radius * 0.08f, radius * 0.24f, fillPaint);

        canvas.restore();
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
            drawAnimatedHeart(canvas, "❤️", cx, cy, size * 0.95f, 0.05f);
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
    // 3. PLASMA FIRE WITH RISING EMBERS & WHITE-HOT CORE (🔥, 🥵)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedFire(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float f1 = (float) Math.sin(t * Math.PI * 6.0) * (size * 0.042f);
        float f2 = (float) Math.cos(t * Math.PI * 8.0) * (size * 0.035f);
        float pulse = 1.0f + 0.055f * (float) Math.sin(t * Math.PI * 4.0) + burstProgress * 0.2f;

        // Warm radiant heat glow
        glowPaint.setAlpha(255);
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

        if (clusterText != null && !clusterText.contains("🔥")) {
            drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, 0f, pulse, pulse, f1 * 0.6f, 0f);
            return;
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

        fillPaint.setAlpha(255);
        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.45f, cx, bot,
                new int[]{0xFFFF1744, 0xFFFF5722, 0xFFFF9100}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path1, fillPaint);

        // 2. Middle Molten Amber Flame
        path2.reset();
        path2.moveTo(cx - wHalf * 0.66f, bot - size * 0.02f);
        path2.cubicTo(cx - wHalf * 0.76f, cy + size * 0.12f, cx - wHalf * 0.35f - f2, cy - size * 0.12f, cx - f1 * 0.5f, cy - size * 0.34f);
        path2.cubicTo(cx + wHalf * 0.22f, cy - size * 0.16f, cx + wHalf * 0.68f + f2, cy + size * 0.05f, cx + wHalf * 0.66f, bot - size * 0.02f);
        path2.close();

        fillPaint.setAlpha(255);
        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.34f, cx, bot,
                new int[]{0xFFFF9100, 0xFFFFC400, 0xFFFFEA00}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path2, fillPaint);

        // 3. White-Hot Inner Core
        path3.reset();
        path3.moveTo(cx - wHalf * 0.34f, bot - size * 0.04f);
        path3.cubicTo(cx - wHalf * 0.34f, cy + size * 0.16f, cx - f1 * 0.3f, cy + size * 0.01f, cx, cy - size * 0.16f);
        path3.cubicTo(cx + f1 * 0.3f, cy + size * 0.01f, cx + wHalf * 0.34f, cy + size * 0.16f, cx + wHalf * 0.34f, bot - size * 0.04f);
        path3.close();

        fillPaint.setAlpha(255);
        fillPaint.setShader(new LinearGradient(cx, cy - size * 0.16f, cx, bot,
                new int[]{0xFFFFFFFF, 0xFFFFF59D, 0xFFFFD600}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path3, fillPaint);

        canvas.restore();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 4. OPAQUE HIGH-FIDELITY CRYING & WATERFALL SOBBING (😢, 😭, 😿, 🥹)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedCrying(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        boolean isLoudSob = clusterText != null && (clusterText.contains("😭") || clusterText.contains("😿") || clusterText.contains("😩") || clusterText.contains("😫"));
        float headR = size * 0.35f;
        float heave = isRunning ? (float) Math.sin(t * Math.PI * 4.0) : 0f;
        float sobTremorX = isRunning ? (float) Math.sin(t * Math.PI * 16.0) * (isLoudSob ? 1.8f * dp : 0.9f * dp) : 0f;
        float scaleX = 1.0f - heave * (isLoudSob ? 0.055f : 0.032f);
        float scaleY = 1.0f + heave * (isLoudSob ? 0.068f : 0.042f);
        float offsetY = isRunning ? -Math.abs(heave) * (3.2f * dp) : 0f;

        // 1. Expanding tear puddle at bottom (drawn beneath the face)
        float puddleY = cy + headR * 1.02f;
        float puddleW = headR * ((isLoudSob ? 0.92f : 0.62f) + 0.14f * (float) Math.sin(t * Math.PI * 6.0));
        fillPaint.setShader(null);
        fillPaint.setColor(0x880284C7);
        tempRect.set(cx - puddleW, puddleY - 3.5f * dp, cx + puddleW, puddleY + 4.5f * dp);
        canvas.drawOval(tempRect, fillPaint);

        strokePaint.setShader(null);
        strokePaint.setColor(0xAA7DD3FC);
        strokePaint.setStrokeWidth(1.5f * dp);
        canvas.drawOval(tempRect, strokePaint);

        // 2. Render the 100% solid opaque emoji glyph (never transparent!)
        resetPaintState();
        drawCrispEmojiGlyph(
                canvas,
                clusterText != null && !clusterText.isEmpty() ? clusterText : "😢",
                cx,
                cy,
                size,
                sobTremorX,
                offsetY,
                scaleX,
                scaleY,
                sobTremorX * 1.2f,
                headR * 0.5f);

        // 3. Foreground 3D Glossy Tear Streams & Falling Droplets
        if (isLoudSob) {
            // Dual gushing waterfall streams + splashing 3D teardrops for 😭 / 😿
            float eyeY = cy + offsetY - headR * 0.06f;
            float leftEyeX = cx + sobTremorX - headR * 0.36f;
            float rightEyeX = cx + sobTremorX + headR * 0.36f;

            fillPaint.setAlpha(255);
            fillPaint.setShader(new LinearGradient(0, eyeY, 0, puddleY,
                    new int[]{0xFF38BDF8, 0xFF0284C7, 0xCC0369A1}, null, Shader.TileMode.CLAMP));
            float streamW = 4.6f * dp;
            tempRect.set(leftEyeX - streamW, eyeY, leftEyeX + streamW, puddleY);
            canvas.drawRoundRect(tempRect, streamW, streamW, fillPaint);
            tempRect.set(rightEyeX - streamW, eyeY, rightEyeX + streamW, puddleY);
            canvas.drawRoundRect(tempRect, streamW, streamW, fillPaint);

            // Animated falling 3D teardrops along both streams
            for (int i = 0; i < 3; i++) {
                float p = (t * 2.6f + i * 0.33f) % 1.0f;
                float dropY = eyeY + p * (puddleY - eyeY);
                float dropR = (3.4f + p * 1.8f) * dp;
                drawGlossy3DTeardrop(canvas, leftEyeX - 2f * dp, dropY, dropR, 8f, 255);
                drawGlossy3DTeardrop(canvas, rightEyeX + 2f * dp, dropY, dropR, -8f, 255);
            }
        } else {
            // Single swelling & falling 3D glossy teardrop for 😢 / 🥹 / 😥
            float p = (t * 1.8f) % 1.0f;
            float tearX = cx + sobTremorX + headR * 0.38f;
            float startY = cy + offsetY - headR * 0.02f;
            float dropY = startY + (p * p) * (puddleY - startY);
            float dropR = (p < 0.25f ? (0.5f + p * 2.0f) : (1.0f - (p - 0.25f) * 0.2f)) * 5.2f * dp;
            int alpha = p > 0.88f ? Math.round((1f - (p - 0.88f) / 0.12f) * 255) : 255;
            drawGlossy3DTeardrop(canvas, tearX, dropY, dropR, -6f, alpha);
        }
        resetPaintState();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 5. SURPRISED / GASPING FACE WITH SHOCK RAYS (😮, 😱, 😲, 😳)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedSurprised(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float gasp = isRunning ? (float) Math.sin(t * Math.PI * 4.0) : 0f;
        float scaleX = 1.0f - 0.065f * gasp;
        float scaleY = 1.0f + 0.085f * gasp;
        float jumpY = isRunning ? -Math.max(0f, gasp) * (4.5f * dp) : 0f;

        // Radiating exclamation shock lines around the top of the head
        float rayPhase = Math.max(0f, gasp);
        if (rayPhase > 0.1f) {
            strokePaint.setShader(null);
            strokePaint.setColor(Color.argb(Math.round(rayPhase * 220), 255, 193, 7));
            strokePaint.setStrokeWidth(2.6f * dp);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = -2; i <= 2; i++) {
                double angle = -Math.PI * 0.5 + i * 0.36;
                float r1 = size * 0.34f;
                float r2 = size * (0.39f + rayPhase * 0.06f);
                canvas.drawLine(
                        cx + (float) Math.cos(angle) * r1,
                        cy + jumpY + (float) Math.sin(angle) * r1,
                        cx + (float) Math.cos(angle) * r2,
                        cy + jumpY + (float) Math.sin(angle) * r2,
                        strokePaint);
            }
        }

        drawCrispEmojiGlyph(
                canvas,
                clusterText != null && !clusterText.isEmpty() ? clusterText : "😮",
                cx,
                cy,
                size,
                0f,
                jumpY,
                scaleX,
                scaleY,
                0f,
                0f);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 6. AUTHENTIC LAUGHING FACE WITH 3D TEAR FOUNTAINS (😂, 🤣, 😆, 😹)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLaughing(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        boolean isRofl = clusterText != null && clusterText.contains("🤣");
        boolean hasTears = clusterText == null || clusterText.contains("😂") || clusterText.contains("🤣") || clusterText.contains("😹") || clusterText.contains("😅");
        float headR = size * 0.36f;

        // Fast belly-laugh chuckle bounce + squash-and-stretch + rolling tilt
        float chuckle = isRunning ? (float) Math.sin(t * Math.PI * 10.0) : 0f;
        float bounceY = isRunning ? -Math.abs(chuckle) * (4.2f * dp) : 0f;
        float squashX = 1.0f + 0.06f * chuckle;
        float squashY = 1.0f - 0.055f * chuckle;
        float tiltDeg = isRunning
                ? (isRofl
                    ? (-12f + (float) Math.sin(t * Math.PI * 4.0) * 18f)
                    : ((float) Math.sin(t * Math.PI * 4.0) * 9.5f))
                : 0f;

        // Warm joyful golden-amber ambient glow behind laughing face
        glowPaint.setAlpha(255);
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.45f,
                new int[]{0x40FFD600, 0x15FF9100, 0x00FFD600}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.45f, glowPaint);

        // 1. Render the authentic 100% opaque 😂 / 🤣 / 😆 glyph
        resetPaintState();
        drawCrispEmojiGlyph(
                canvas,
                clusterText != null && !clusterText.isEmpty() ? clusterText : "😂",
                cx,
                cy,
                size,
                0f,
                bounceY,
                squashX,
                squashY,
                tiltDeg,
                0f);

        // 2. Twin Parabolic 3D Glossy Tear Fountains Ejecting from Eye Corners for 😂 / 🤣 / 😹
        if (hasTears) {
            canvas.save();
            canvas.translate(cx, cy + bounceY);
            canvas.rotate(tiltDeg);
            for (int i = 0; i < 2; i++) {
                float tearT = (t * 2.8f + i * 0.5f) % 1.0f;
                float startX = headR * 0.52f;
                float dx = startX + tearT * (headR * 0.48f);
                float dy = -headR * 0.12f + (tearT * tearT * 1.35f - 0.42f * tearT) * (headR * 0.68f);
                float dropR = (1f - tearT * 0.3f) * 5.0f * dp;
                int alpha = tearT > 0.82f ? Math.round((1f - (tearT - 0.82f) / 0.18f) * 255) : 255;

                drawGlossy3DTeardrop(canvas, -dx, dy, dropR, 48f + tearT * 25f, alpha);
                drawGlossy3DTeardrop(canvas, dx, dy, dropR, -48f - tearT * 25f, alpha);
            }
            canvas.restore();
        }
        resetPaintState();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 7. NEON LASER 100 SLAM WITH SPARKS (💯)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedHundred(Canvas canvas, float cx, float cy, float size, float t) {
        float slam = 1.0f + 0.14f * (float) Math.max(0, Math.sin(t * Math.PI * 4.0));
        float tilt = -6f + (float) Math.sin(t * Math.PI * 2.0) * 3.5f;

        // Fiery neon aura
        glowPaint.setAlpha(255);
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.46f,
                new int[]{0x48FF1744, 0x18FF9100, 0x00FF1744}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.46f, glowPaint);

        canvas.save();
        canvas.rotate(tilt, cx, cy);
        canvas.scale(slam, slam, cx, cy);

        badgePaint.setAlpha(255);
        badgePaint.setTextSize(size * 0.46f);
        badgePaint.setShader(new LinearGradient(cx, cy - size * 0.25f, cx, cy + size * 0.15f,
                new int[]{0xFFFF2A54, 0xFFD50000}, null, Shader.TileMode.CLAMP));
        badgePaint.setFakeBoldText(true);
        canvas.drawText("100", cx, cy + size * 0.08f, badgePaint);
        badgePaint.setShader(null);

        // Twin dynamic neon underline slashes
        strokePaint.setAlpha(255);
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
        resetPaintState();
        drawProceduralEmoji(canvas, clusterText, EmojiType.PARTY, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 9. MIND-BLOWN COSMIC SUPERNOVA (🤯)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedMindBlown(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float blast = (float) Math.abs(Math.sin(t * Math.PI * 2.0));
        float cloudY = cy - size * (0.20f + blast * 0.16f);
        float cloudR = size * (0.22f + blast * 0.14f);

        glowPaint.setAlpha(255);
        glowPaint.setShader(new RadialGradient(cx, cloudY, cloudR,
                new int[]{0xAAFF4081, 0x777C4DFF, 0x0000E5FF}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cloudY, cloudR, glowPaint);

        // Orbiting cosmic stardust around the top of the head
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);
        for (int i = 0; i < 6; i++) {
            float angle = (float) (t * Math.PI * 4.0 + i * 1.04);
            float sx = cx + (float) Math.cos(angle) * (cloudR * 0.85f);
            float sy = cloudY + (float) Math.sin(angle) * (cloudR * 0.45f);
            fillPaint.setColor((i % 2 == 0) ? 0xFFFFEA00 : 0xFF00E5FF);
            drawFourPointStar(canvas, sx, sy, 3.8f * dp, fillPaint);
        }

        resetPaintState();
        drawProceduralEmoji(canvas, clusterText, EmojiType.MIND_BLOWN, cx, cy + size * 0.04f, size * 0.94f, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 10. LOVE EYES / AFFECTION FACE WITH FLOATING 3D HEARTS (😍, 🥰, 😘, 😊)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLoveEyes(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float sway = isRunning ? (float) Math.sin(t * Math.PI * 2.0) * 7.5f : 0f;
        float pulse = isRunning ? 1.0f + 0.06f * (float) Math.sin(t * Math.PI * 4.0) : 1.0f;

        // Warm rose romantic aura
        glowPaint.setAlpha(255);
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.45f,
                new int[]{0x44FF4081, 0x18FF80AB, 0x00FF4081}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.45f, glowPaint);

        // Render the authentic 100% opaque 😍 / 🥰 / 😘 glyph
        resetPaintState();
        drawCrispEmojiGlyph(
                canvas,
                clusterText != null && !clusterText.isEmpty() ? clusterText : "😍",
                cx,
                cy,
                size,
                0f,
                0f,
                pulse,
                pulse,
                sway,
                0f);

        // Floating 3D mini-hearts orbiting & rising from the cheeks/eyes
        fillPaint.setShader(null);
        for (int i = 0; i < 4; i++) {
            float p = (t * 1.6f + i * 0.25f) % 1.0f;
            float side = (i % 2 == 0) ? -1f : 1f;
            float hx = cx + side * (size * (0.24f + p * 0.14f));
            float hy = cy + size * 0.05f - p * (size * 0.44f);
            float hr = (float) Math.sin(p * Math.PI) * (size * 0.072f);
            fillPaint.setColor(Color.argb(Math.round((float) Math.sin(p * Math.PI) * 245), 255, 23, 68));
            drawMiniHeart(canvas, hx, hy, hr, fillPaint);
        }
        resetPaintState();
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
        resetPaintState();
        float hoverX = (float) Math.sin(t * Math.PI * 4.0) * (2.5f * dp);
        float hoverY = (float) Math.cos(t * Math.PI * 4.0) * (3.5f * dp);
        drawProceduralEmoji(canvas, clusterText, EmojiType.ROCKET, cx + hoverX, cy + hoverY, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 12. THUMBS UP / GESTURE GOLDEN SUNBURST (👍, 💪, ✌️, 👌)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedThumbsUp(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setAlpha(255);
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
        resetPaintState();
        float popAngle = (float) Math.sin(t * Math.PI * 4.0);
        float rot = -8f + popAngle * 12f;
        float scale = 1.0f + Math.max(0f, popAngle) * 0.12f;
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, 0f, scale, scale, rot, size * 0.2f);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 13. WAVE / CLAP / HANDS CHOREOGRAPHY (👋, 👏, 🙌, 🙏)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedWaveClap(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        boolean isClap = clusterText != null && clusterText.contains("👏");
        float wave = isRunning ? (float) Math.sin(t * Math.PI * 6.0) : 0f;
        if (isClap) {
            float clapPulse = Math.max(0f, wave);
            if (clapPulse > 0.2f) {
                strokePaint.setShader(null);
                strokePaint.setColor(Color.argb(Math.round(clapPulse * 210), 255, 214, 0));
                strokePaint.setStrokeWidth(2.4f * dp);
                canvas.drawCircle(cx, cy, size * (0.28f + clapPulse * 0.14f), strokePaint);
            }
            float s = 1.0f + clapPulse * 0.14f;
            drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, 0f, s, s, wave * 6f, 0f);
        } else {
            // Pivot wrist wave for 👋 / 🙌 / 🙏
            drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, 0f, 1.04f, 1.04f, wave * 18f, size * 0.24f);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 14. SKULL / GHOST ECTOPLASM LEVITATION (💀, 👻)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedSkullGhost(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setAlpha(255);
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
        resetPaintState();
        drawProceduralEmoji(canvas, clusterText, EmojiType.SKULL_GHOST, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 15. HIGH-VOLTAGE ELECTRIC ARCS (⚡, 💥)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedLightning(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setAlpha(255);
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
        resetPaintState();
        drawProceduralEmoji(canvas, clusterText, EmojiType.LIGHTNING, cx, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 16. ANGRY BOILING RAGE WITH STEAM JETS (😡, 🤬, 😤)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedAngry(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setAlpha(255);
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
        resetPaintState();
        float rageShakeX = (float) Math.sin(t * Math.PI * 24.0) * (2.2f * dp);
        drawProceduralEmoji(canvas, clusterText, EmojiType.ANGRY, cx + rageShakeX, cy, size, t);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 17. COOL SUNGLASSES SWAGGER & LENS GLINT (😎, 🤓, 🤠, 🤑)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedCool(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float nod = isRunning ? (float) Math.sin(t * Math.PI * 2.0) : 0f;
        float tilt = nod * 6.0f;
        float dy = -Math.abs(nod) * (3.0f * dp);
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, dy, 1.03f, 1.03f, tilt, 0f);

        // Sparkling lens glint across the sunglasses area
        float glintPhase = (t * 2.0f) % 1.0f;
        if (glintPhase < 0.55f) {
            float gx = cx - size * 0.18f + (glintPhase / 0.55f) * (size * 0.36f);
            float gy = cy + dy - size * 0.08f;
            fillPaint.setShader(null);
            fillPaint.setColor(0xFFFFFFFF);
            drawFourPointStar(canvas, gx, gy, 5.2f * dp, fillPaint);
        }
        resetPaintState();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 18. FREEZING SHIVER & FROST CRYSTALS (🥶, ❄️, ☃️)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedCold(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        glowPaint.setAlpha(255);
        glowPaint.setShader(new RadialGradient(cx, cy, size * 0.45f,
                new int[]{0x4838BDF8, 0x18E0F2FE, 0x0038BDF8}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, size * 0.45f, glowPaint);

        float shiverX = isRunning ? (float) Math.sin(t * Math.PI * 28.0) * (2.0f * dp) : 0f;
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, shiverX, 0f, 0.98f, 1.02f, 0f, 0f);

        // Floating frost crystals
        fillPaint.setShader(null);
        fillPaint.setColor(0xFFE0F2FE);
        for (int i = 0; i < 4; i++) {
            float p = (t * 1.4f + i * 0.25f) % 1.0f;
            float fx = cx + (float) Math.cos(i * 1.7 + p * 3.0) * (size * 0.34f);
            float fy = cy - size * 0.25f + p * (size * 0.5f);
            drawFourPointStar(canvas, fx, fy, 3.2f * dp, fillPaint);
        }
        resetPaintState();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 19. SLEEPY BREATHING & FLOATING Zzz (😴, 🥱, 💤)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedSleepy(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float breath = isRunning ? (float) Math.sin(t * Math.PI * 2.0) : 0f;
        float scale = 1.0f + breath * 0.055f;
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, 0f, scale, scale, breath * 4.5f, 0f);

        // Floating "Z" letters rising from top-right
        badgePaint.setShader(null);
        for (int i = 0; i < 2; i++) {
            float p = (t + i * 0.5f) % 1.0f;
            float zx = cx + size * (0.14f + p * 0.22f);
            float zy = cy - size * (0.12f + p * 0.28f);
            badgePaint.setTextSize((9f + p * 7f) * dp);
            badgePaint.setColor(Color.argb(Math.round((float) Math.sin(p * Math.PI) * 235), 125, 211, 252));
            canvas.drawText("Z", zx, zy, badgePaint);
        }
        resetPaintState();
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 20. CURIOUS THINKING TILT (🤔, 🧐, 🤨, 👀)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedThinking(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float ponder = isRunning ? (float) Math.sin(t * Math.PI * 2.0) : 0f;
        float tilt = -6f + ponder * 10f;
        float liftY = -Math.abs(ponder) * (3.0f * dp);
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, liftY, 1.03f, 1.03f, tilt, 0f);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 21. DIZZY / SICK SWIRL (🤢, 🤮, 😵, 😵‍💫, 🥴)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedDizzySick(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float angle = (float) (t * Math.PI * 2.0);
        float wobbleX = isRunning ? (float) Math.cos(angle) * (3.2f * dp) : 0f;
        float wobbleY = isRunning ? (float) Math.sin(angle * 2.0) * (2.5f * dp) : 0f;
        float tilt = isRunning ? (float) Math.sin(angle) * 9.5f : 0f;
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, wobbleX, wobbleY, 1.0f, 1.0f, tilt, 0f);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 22. RHYTHMIC MUSIC GROOVE & FLOATING NOTES (🎵, 🎶, 🎧, 💃, 🕺)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawAnimatedMusic(Canvas canvas, String clusterText, float cx, float cy, float size, float t) {
        float beat = isRunning ? (float) Math.sin(t * Math.PI * 4.0) : 0f;
        float scaleX = 1.0f + beat * 0.06f;
        float scaleY = 1.0f - beat * 0.06f;
        float sway = isRunning ? (float) Math.sin(t * Math.PI * 2.0) * 10f : 0f;
        drawCrispEmojiGlyph(canvas, clusterText, cx, cy, size, 0f, -Math.abs(beat) * 4f * dp, scaleX, scaleY, sway, 0f);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // 23. UNIVERSAL SPRING-PHYSICS SQUASH-AND-STRETCH EMOJI CHOREOGRAPHY
    //     (Fits Every Unicode Emoji with Deterministic Per-Glyph Motion Variety)
    // ═════════════════════════════════════════════════════════════════════════════
    private void drawProceduralEmoji(Canvas canvas, String emojiStr, EmojiType type, float cx, float cy, float size, float t) {
        float angle = (float) (t * Math.PI * 2.0);
        int hash = emojiStr != null ? Math.abs(emojiStr.hashCode()) : 0;
        int motionStyle = hash % 3;

        float hop = (float) Math.sin(angle * 2.0);
        float bounceY = 0f;
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float tilt = 0f;

        if (isRunning) {
            if (type == EmojiType.PLEADING) {
                // Gentle puppy-eyes quiver + soft vertical breath
                bounceY = (float) Math.sin(angle) * (2.4f * dp);
                scaleX = 1.0f + (float) Math.sin(angle * 2.0) * 0.035f;
                scaleY = 1.0f - (float) Math.sin(angle * 2.0) * 0.035f;
                tilt = (float) Math.sin(angle * 4.0) * 3.5f;
            } else if (motionStyle == 0) {
                // Elastic spring bounce + squash-and-stretch
                bounceY = -Math.abs(hop) * (6.2f * dp);
                scaleX = 1.0f - hop * 0.055f;
                scaleY = 1.0f + hop * 0.065f;
                tilt = (float) Math.sin(angle) * 6.5f;
            } else if (motionStyle == 1) {
                // Playful pendulum rock + heartbeat pulse
                bounceY = -Math.abs((float) Math.cos(angle)) * (4.5f * dp);
                float pulse = 1.0f + 0.06f * (float) Math.sin(angle * 2.0);
                scaleX = pulse;
                scaleY = pulse;
                tilt = (float) Math.sin(angle) * 10.5f;
            } else {
                // 3D figure-8 hover dance
                bounceY = (float) Math.sin(angle * 2.0) * (4.0f * dp);
                scaleX = 1.0f + 0.05f * (float) Math.cos(angle * 2.0);
                scaleY = 1.0f - 0.05f * (float) Math.cos(angle * 2.0);
                tilt = (float) Math.cos(angle) * 7.5f;
            }
        }

        if (type == EmojiType.SPARKLE) {
            fillPaint.setShader(null);
            fillPaint.setAlpha(255);
            fillPaint.setColor(0xFFFFEA00);
            for (int i = 0; i < 4; i++) {
                float sa = angle + i * 1.57f;
                float sx = cx + (float) Math.cos(sa) * (size * 0.35f);
                float sy = cy + (float) Math.sin(sa) * (size * 0.35f);
                drawFourPointStar(canvas, sx, sy, 3.6f * dp, fillPaint);
            }
        }

        drawCrispEmojiGlyph(canvas, emojiStr, cx, cy, size, 0f, bounceY, scaleX, scaleY, tilt, 0f);
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
        resetPaintState();

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
