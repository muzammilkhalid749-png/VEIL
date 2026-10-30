package com.m3.veil.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.JavaI420Buffer;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * VeilArMaskRenderer: Real-Time On-Device AR Anonymous Camera & Video Masks (#211, #220).
 * Provides zero-cloud, procedural face obfuscation filters:
 *  1. CYBERPUNK_NEON_VISOR: High-tech glowing cyan/magenta eye-shield with dynamic laser scanlines & telemetry.
 *  2. PHANTOM_GEOMETRIC_MASK: Stylized faceted obsidian face shield with sharp gold wireframe edges.
 *  3. PIXELATED_GLITCH_MASK: Dynamic mosaic block pixelation with chromatic aberration (RGB displacement).
 *  4. SHADOW_SILHOUETTE: Deep matte obsidian face silhouette with piercing glowing phantom eyes.
 *
 * Supports Canvas overlays (CameraX / Viewfinder / Moments), Bitmap baking (Photos / MediaEditActivity),
 * and direct WebRTC I420 YUV buffer transformation (Zero-latency P2P Video Calls).
 */
public class VeilArMaskRenderer {

    public static final int MASK_NONE = 0;
    public static final int MASK_CYBERPUNK = 1;
    public static final int MASK_PHANTOM = 2;
    public static final int MASK_GLITCH = 3;
    public static final int MASK_SILHOUETTE = 4;

    public static final String[] MASK_NAMES = {
            "Off",
            "Cyber Visor",
            "Phantom Shield",
            "Pixel Glitch",
            "Void Silhouette"
    };

    public static final String[] MASK_ICONS = {
            "🚫",
            "⚡",
            "🎭",
            "👾",
            "👤"
    };

    private static volatile VeilArMaskRenderer sInstance;

    public static VeilArMaskRenderer getInstance() {
        if (sInstance == null) {
            synchronized (VeilArMaskRenderer.class) {
                if (sInstance == null) {
                    sInstance = new VeilArMaskRenderer();
                }
            }
        }
        return sInstance;
    }

    // Reusable Paints for zero-allocation rendering at 60 FPS
    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGlitch = new Paint();
    private final Path tempPath = new Path();
    private final RectF tempRect = new RectF();
    private final Random random = new Random(42);

    private VeilArMaskRenderer() {
        paintFill.setStyle(Paint.Style.FILL);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeCap(Paint.Cap.ROUND);
        paintStroke.setStrokeJoin(Paint.Join.ROUND);
        paintGlow.setStyle(Paint.Style.STROKE);
        paintText.setColor(Color.WHITE);
        paintText.setTextSize(24f);
        paintText.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /**
     * Render the active procedural AR mask on a 2D Canvas pinned to live facial landmarks
     * and 3D head pose (yaw, pitch, roll, scale).
     *
     * @param canvas       Target Canvas (Viewfinder overlay or Bitmap canvas)
     * @param faceBounds   Fallback bounding box of the face if no live TrackedFace is explicitly passed
     * @param maskMode     MASK_NONE, MASK_CYBERPUNK, MASK_PHANTOM, MASK_GLITCH, MASK_SILHOUETTE
     * @param trustReveal  Trust level [0.0 = full obfuscation, 1.0 = fully revealed / transparent]
     * @param animTimeMs   System animation clock in milliseconds
     */
    public synchronized void renderMaskOnCanvas(@NonNull Canvas canvas, @NonNull RectF faceBounds,
                                               int maskMode, float trustReveal, long animTimeMs) {
        if (maskMode == MASK_NONE || trustReveal >= 0.98f) {
            return;
        }

        com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace trackedFace =
                com.m3.veil.calling.VeilFaceDetectionPipeline.getInstance().getLatestPrimaryFace();

        int canvasW = canvas.getWidth();
        int canvasH = canvas.getHeight();
        RectF activeBounds = new RectF(faceBounds);
        float rollDeg = 0f;
        float yawDeg = 0f;
        float pitchDeg = 0f;

        if (trackedFace != null && canvasW > 0 && canvasH > 0) {
            float cx = trackedFace.centerX * canvasW;
            float cy = trackedFace.centerY * canvasH;
            float rx = trackedFace.radiusX * canvasW * 1.08f;
            float ry = trackedFace.radiusY * canvasH * 1.10f;
            activeBounds.set(cx - rx, cy - ry, cx + rx, cy + ry);
            rollDeg = trackedFace.rollDeg;
            yawDeg = trackedFace.yawDeg;
            pitchDeg = trackedFace.pitchDeg;
        }

        float maskAlpha = Math.max(0.0f, Math.min(1.0f, 1.0f - trustReveal));
        int alpha255 = Math.round(maskAlpha * 255f);

        int saveCount = canvas.save();
        float pivotX = activeBounds.centerX();
        float pivotY = activeBounds.centerY();

        // Apply 3D head rotation (roll around Z + perspective yaw/pitch foreshortening & translation)
        if (Math.abs(rollDeg) > 0.2f) {
            canvas.rotate(rollDeg, pivotX, pivotY);
        }
        float yawScaleX = Math.max(0.76f, (float) Math.cos(Math.toRadians(yawDeg * 0.85f)));
        float pitchScaleY = Math.max(0.80f, (float) Math.cos(Math.toRadians(pitchDeg * 0.80f)));
        float yawShiftX = (yawDeg / 45.0f) * activeBounds.width() * 0.08f;
        float pitchShiftY = (pitchDeg / 45.0f) * activeBounds.height() * 0.06f;
        canvas.translate(yawShiftX, pitchShiftY);
        canvas.scale(yawScaleX, pitchScaleY, pivotX, pivotY);

        switch (maskMode) {
            case MASK_CYBERPUNK:
                drawCyberpunkVisor(canvas, activeBounds, trackedFace, canvasW, canvasH, alpha255, animTimeMs);
                break;
            case MASK_PHANTOM:
                drawPhantomGeometricMask(canvas, activeBounds, trackedFace, canvasW, canvasH, alpha255, animTimeMs);
                break;
            case MASK_GLITCH:
                drawPixelatedGlitch(canvas, activeBounds, alpha255, animTimeMs);
                break;
            case MASK_SILHOUETTE:
                drawShadowSilhouette(canvas, activeBounds, trackedFace, canvasW, canvasH, alpha255, animTimeMs);
                break;
        }

        canvas.restoreToCount(saveCount);
    }

    // ---------------------------------------------------------------------------------------------
    // MASK 1: CYBERPUNK NEON VISOR (#211) - Pinned to Left/Right Eye & Nose Bridge Landmarks
    // ---------------------------------------------------------------------------------------------
    private void drawCyberpunkVisor(Canvas canvas, RectF face,
                                    @Nullable com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace trackedFace,
                                    int canvasW, int canvasH, int alpha, long time) {
        float eyeCenterY = face.top + face.height() * 0.38f;
        if (trackedFace != null && canvasH > 0) {
            eyeCenterY = ((trackedFace.leftEye.y + trackedFace.rightEye.y) * 0.5f) * canvasH;
        }
        float visorHeight = face.height() * 0.24f;
        float visorTop = eyeCenterY - visorHeight * 0.5f;
        float visorBottom = eyeCenterY + visorHeight * 0.5f;
        float visorLeft = face.left - face.width() * 0.06f;
        float visorRight = face.right + face.width() * 0.06f;

        // Dark translucent glass base
        paintFill.setColor(Color.argb((int) (alpha * 0.92f), 10, 14, 28));
        tempRect.set(visorLeft, visorTop, visorRight, visorBottom);
        canvas.drawRoundRect(tempRect, 18f, 18f, paintFill);

        // Neon Cyan/Magenta horizontal border lines
        paintStroke.setStrokeWidth(6f);
        paintStroke.setColor(Color.argb(alpha, 0, 240, 255)); // Neon Cyan
        canvas.drawRoundRect(tempRect, 18f, 18f, paintStroke);

        // Outer glow accent line
        paintGlow.setStrokeWidth(12f);
        paintGlow.setColor(Color.argb((int) (alpha * 0.45f), 255, 0, 128)); // Magenta Glow
        canvas.drawRoundRect(tempRect, 18f, 18f, paintGlow);

        // Animated horizontal laser scanline
        float scanProgress = (float) ((time % 1200) / 1200.0);
        float scanY = visorTop + visorHeight * scanProgress;
        paintStroke.setStrokeWidth(3f);
        paintStroke.setColor(Color.argb((int) (alpha * 0.85f), 255, 255, 255));
        canvas.drawLine(visorLeft + 12f, scanY, visorRight - 12f, scanY, paintStroke);

        // Cybernetic Telemetry / HUD details pinned to nose bridge & eye centers
        paintStroke.setStrokeWidth(2f);
        paintStroke.setColor(Color.argb((int) (alpha * 0.65f), 0, 240, 255));
        float cx = face.centerX();
        canvas.drawLine(cx, eyeCenterY - 10f, cx, eyeCenterY + 10f, paintStroke);
        canvas.drawLine(cx - 10f, eyeCenterY, cx + 10f, eyeCenterY, paintStroke);

        paintText.setTextSize(Math.max(15f, visorHeight * 0.21f));
        paintText.setColor(Color.argb((int) (alpha * 0.82f), 0, 255, 200));
        canvas.drawText("VEIL::ANON", visorLeft + 22f, eyeCenterY + 5f, paintText);

        String hexCoord = "0x" + Integer.toHexString((int) (time & 0xFFFF)).toUpperCase();
        canvas.drawText(hexCoord, visorRight - 96f, eyeCenterY + 5f, paintText);
    }

    // ---------------------------------------------------------------------------------------------
    // MASK 2: PHANTOM GEOMETRIC MASK (#211) - Pinned to Face Oval Contour, Eyes, Nose & Mouth
    // ---------------------------------------------------------------------------------------------
    private void drawPhantomGeometricMask(Canvas canvas, RectF face,
                                          @Nullable com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace trackedFace,
                                          int canvasW, int canvasH, int alpha, long time) {
        float cx = face.centerX();
        float top = face.top + face.height() * 0.04f;
        float bottom = face.bottom - face.height() * 0.02f;
        float left = face.left + face.width() * 0.04f;
        float right = face.right - face.width() * 0.04f;
        float midY = face.centerY();
        float noseY = midY + face.height() * 0.04f;
        float mouthY = face.top + face.height() * 0.74f;

        if (trackedFace != null && canvasW > 0 && canvasH > 0) {
            noseY = trackedFace.noseBase.y * canvasH;
            mouthY = trackedFace.mouthCenter.y * canvasH;
        }

        // Faceted Angular Shield Base (Low-poly obsidian faceplate)
        tempPath.reset();
        tempPath.moveTo(cx, top);                           // Forehead apex
        tempPath.lineTo(right, top + face.height() * 0.25f);// Right temple
        tempPath.lineTo(right + face.width() * 0.04f, midY);// Right cheek
        tempPath.lineTo(cx + face.width() * 0.18f, bottom - face.height() * 0.10f); // Right jaw
        tempPath.lineTo(cx, bottom);                        // Chiseled chin
        tempPath.lineTo(cx - face.width() * 0.18f, bottom - face.height() * 0.10f); // Left jaw
        tempPath.lineTo(left - face.width() * 0.04f, midY); // Left cheek
        tempPath.lineTo(left, top + face.height() * 0.25f); // Left temple
        tempPath.close();

        // Obsidian fill
        paintFill.setColor(Color.argb((int) (alpha * 0.94f), 14, 18, 24));
        canvas.drawPath(tempPath, paintFill);

        // Geometric Facet Crease Lines (Faceted Low-Poly Wireframe pinned to nose & mouth landmarks)
        paintStroke.setStrokeWidth(3.5f);
        paintStroke.setColor(Color.argb((int) (alpha * 0.85f), 220, 180, 80)); // Metallic Gold wireframe
        canvas.drawPath(tempPath, paintStroke);

        // Internal faceted chords
        canvas.drawLine(cx, top, cx, bottom, paintStroke); // Vertical symmetry crest
        canvas.drawLine(left, midY, cx, mouthY, paintStroke);
        canvas.drawLine(right, midY, cx, mouthY, paintStroke);
        canvas.drawLine(left, top + face.height() * 0.25f, cx, noseY, paintStroke);
        canvas.drawLine(right, top + face.height() * 0.25f, cx, noseY, paintStroke);

        // Sharp Slit Eye Apertures pinned to Left/Right Eye Landmarks
        float eyeY = (trackedFace != null && canvasH > 0)
                ? ((trackedFace.leftEye.y + trackedFace.rightEye.y) * 0.5f * canvasH)
                : (face.top + face.height() * 0.38f);
        float eyeH = face.height() * 0.04f;

        paintFill.setColor(Color.argb(alpha, 255, 60, 60)); // Crimson inner slit glow
        tempRect.set(cx - face.width() * 0.27f, eyeY - eyeH, cx - face.width() * 0.08f, eyeY + eyeH);
        canvas.drawRoundRect(tempRect, 6f, 6f, paintFill);

        tempRect.set(cx + face.width() * 0.08f, eyeY - eyeH, cx + face.width() * 0.27f, eyeY + eyeH);
        canvas.drawRoundRect(tempRect, 6f, 6f, paintFill);
    }

    // ---------------------------------------------------------------------------------------------
    // MASK 3: PIXELATED GLITCH MASK (#211)
    // ---------------------------------------------------------------------------------------------
    private void drawPixelatedGlitch(Canvas canvas, RectF face, int alpha, long time) {
        int cols = 12;
        int rows = 14;
        float cellW = face.width() / cols;
        float cellH = face.height() / rows;

        // Glitch chromatic displacement pulse
        float jitterX = (float) Math.sin(time / 150.0) * (cellW * 0.35f);

        // Mosaic Blocks strictly within face oval contour
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float normX = (c + 0.5f) / cols - 0.5f;
                float normY = (r + 0.5f) / rows - 0.5f;
                if ((normX * normX * 4f + normY * normY * 3.8f) > 1.0f) {
                    continue; // Skip outside face oval
                }

                float left = face.left + c * cellW;
                float top = face.top + r * cellH;

                int noise = (int) (Math.abs(Math.sin((c * 17 + r * 31 + time / 250f))) * 80);
                int baseR = Math.min(255, 20 + noise);
                int baseG = Math.min(255, 30 + noise);
                int baseB = Math.min(255, 50 + noise * 2);

                // Main mosaic block
                paintFill.setColor(Color.argb((int) (alpha * 0.90f), baseR, baseG, baseB));
                canvas.drawRect(left, top, left + cellW - 1f, top + cellH - 1f, paintFill);

                // Chromatic Aberration fringe (Cyan & Magenta offset slivers)
                if ((c + r) % 3 == 0) {
                    paintGlitch.setColor(Color.argb((int) (alpha * 0.40f), 0, 255, 255));
                    canvas.drawRect(left + jitterX, top, left + cellW * 0.4f + jitterX, top + cellH, paintGlitch);

                    paintGlitch.setColor(Color.argb((int) (alpha * 0.40f), 255, 0, 80));
                    canvas.drawRect(left + cellW * 0.6f - jitterX, top, left + cellW - jitterX, top + cellH, paintGlitch);
                }
            }
        }

        // Horizontal glitch scan slices
        if ((time / 300) % 2 == 0) {
            float sliceY = face.top + (face.height() * ((time % 1000) / 1000f));
            paintFill.setColor(Color.argb((int) (alpha * 0.70f), 0, 240, 255));
            canvas.drawRect(face.left - cellW * 0.5f, sliceY, face.right + cellW * 0.5f, sliceY + cellH * 0.6f, paintFill);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MASK 4: SHADOW SILHOUETTE (#211) - Pinned to Face Oval & Eye Landmarks
    // ---------------------------------------------------------------------------------------------
    private void drawShadowSilhouette(Canvas canvas, RectF face,
                                      @Nullable com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace trackedFace,
                                      int canvasW, int canvasH, int alpha, long time) {
        float cx = face.centerX();

        // Deep void silhouette oval
        paintFill.setColor(Color.argb((int) (alpha * 0.96f), 5, 8, 14));
        canvas.drawOval(face, paintFill);

        // Soft outer feather border
        paintStroke.setStrokeWidth(14f);
        paintStroke.setColor(Color.argb((int) (alpha * 0.50f), 12, 18, 30));
        canvas.drawOval(face, paintStroke);

        // Glowing Phantom Eyes pinned to detected eye landmarks
        float eyeY = (trackedFace != null && canvasH > 0)
                ? ((trackedFace.leftEye.y + trackedFace.rightEye.y) * 0.5f * canvasH)
                : (face.top + face.height() * 0.38f);
        float eyeSpacing = face.width() * 0.20f;
        float eyeLeftX = cx - eyeSpacing;
        float eyeRightX = cx + eyeSpacing;
        float pulse = (float) (0.85f + 0.15f * Math.sin(time / 250.0));
        float eyeRadiusX = face.width() * 0.055f * pulse;
        float eyeRadiusY = face.height() * 0.028f * pulse;

        // Eye Outer Glow (Ice Cyan)
        paintGlow.setStrokeWidth(10f);
        paintGlow.setColor(Color.argb((int) (alpha * 0.75f), 0, 229, 255));
        tempRect.set(eyeLeftX - eyeRadiusX * 1.5f, eyeY - eyeRadiusY * 1.5f, eyeLeftX + eyeRadiusX * 1.5f, eyeY + eyeRadiusY * 1.5f);
        canvas.drawOval(tempRect, paintGlow);
        tempRect.set(eyeRightX - eyeRadiusX * 1.5f, eyeY - eyeRadiusY * 1.5f, eyeRightX + eyeRadiusX * 1.5f, eyeY + eyeRadiusY * 1.5f);
        canvas.drawOval(tempRect, paintGlow);

        // Eye Inner White Core
        paintFill.setColor(Color.argb(alpha, 255, 255, 255));
        tempRect.set(eyeLeftX - eyeRadiusX, eyeY - eyeRadiusY, eyeLeftX + eyeRadiusX, eyeY + eyeRadiusY);
        canvas.drawOval(tempRect, paintFill);
        tempRect.set(eyeRightX - eyeRadiusX, eyeY - eyeRadiusY, eyeRightX + eyeRadiusX, eyeY + eyeRadiusY);
        canvas.drawOval(tempRect, paintFill);
    }

    // ---------------------------------------------------------------------------------------------
    // BITMAP PROCESSING: Bakes AR Mask on Captured Photos (#211)
    // ---------------------------------------------------------------------------------------------
    @NonNull
    public Bitmap applyMaskToBitmap(@NonNull Bitmap source, int maskMode, float trustReveal) {
        if (maskMode == MASK_NONE || trustReveal >= 0.98f) {
            return source;
        }

        Bitmap output = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);

        float w = output.getWidth();
        float h = output.getHeight();
        com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace face =
                com.m3.veil.calling.VeilFaceDetectionPipeline.getInstance().getLatestPrimaryFace();
        RectF bounds;
        if (face != null) {
            float cx = face.centerX * w;
            float cy = face.centerY * h;
            float rx = face.radiusX * w * 1.08f;
            float ry = face.radiusY * h * 1.10f;
            bounds = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
        } else {
            float faceW = w * 0.52f;
            float faceH = h * 0.42f;
            float faceLeft = (w - faceW) * 0.5f;
            float faceTop = h * 0.16f;
            bounds = new RectF(faceLeft, faceTop, faceLeft + faceW, faceTop + faceH);
        }
        renderMaskOnCanvas(canvas, bounds, maskMode, trustReveal, SystemClock.uptimeMillis());
        return output;
    }

    // ---------------------------------------------------------------------------------------------
    // WEBRTC YUV DSP: Real-Time Direct Frame Manipulation Pinned to Live Landmarks & 3D Head Pose
    // ---------------------------------------------------------------------------------------------
    public void applyMaskToI420Yuv(@NonNull JavaI420Buffer src, @NonNull JavaI420Buffer dst,
                                  int width, int height, int maskMode, float trustReveal, long timeMs) {
        if (maskMode == MASK_NONE || trustReveal >= 0.98f) {
            return;
        }

        ByteBuffer yDst = dst.getDataY();
        int yStrideDst = dst.getStrideY();
        ByteBuffer uDst = dst.getDataU();
        int uStrideDst = dst.getStrideU();
        ByteBuffer vDst = dst.getDataV();
        int vStrideDst = dst.getStrideV();

        com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace face =
                com.m3.veil.calling.VeilFaceDetectionPipeline.getInstance().getLatestPrimaryFace();

        float cx = face.centerX * width;
        float cy = face.centerY * height;
        float rx = Math.max(width * 0.14f, face.radiusX * width * 1.10f);
        float ry = Math.max(height * 0.18f, face.radiusY * height * 1.14f);
        double rad = -Math.toRadians(face.rollDeg);
        float cosR = (float) Math.cos(rad);
        float sinR = (float) Math.sin(rad);

        int roiLeft = Math.max(0, (int) (cx - rx * 1.25f));
        int roiRight = Math.min(width - 1, (int) (cx + rx * 1.25f));
        int roiTop = Math.max(0, (int) (cy - ry * 1.25f));
        int roiBottom = Math.min(height - 1, (int) (cy + ry * 1.25f));

        float maskStrength = Math.max(0.0f, Math.min(1.0f, 1.0f - trustReveal));

        switch (maskMode) {
            case MASK_CYBERPUNK: {
                // Eye-visor band rotated with head roll and pinned to left/right eye landmarks
                float scanPhase = (float) ((timeMs % 800) / 800.0) * 2.0f - 1.0f; // [-1..1] inside visor
                for (int y = roiTop; y <= roiBottom; y++) {
                    for (int x = roiLeft; x <= roiRight; x++) {
                        float dx = x - cx;
                        float dy = y - cy;
                        float localX = (dx * cosR - dy * sinR) / rx;
                        float localY = (dx * sinR + dy * cosR) / ry;

                        // Visor spans localX in [-1.05..1.05], localY around eyes [-0.46..-0.02]
                        if (Math.abs(localX) <= 1.05f && localY >= -0.46f && localY <= -0.02f) {
                            float visorNormY = (localY - (-0.24f)) / 0.22f; // [-1..1]
                            int pos = y * yStrideDst + x;
                            boolean isBorder = Math.abs(visorNormY) > 0.85f || Math.abs(localX) > 0.96f;
                            boolean isLaser = Math.abs(visorNormY - scanPhase) < 0.08f;

                            if (isLaser) {
                                yDst.put(pos, (byte) 245);
                            } else if (isBorder) {
                                yDst.put(pos, (byte) 210);
                                int uvPosU = (y / 2) * uStrideDst + (x / 2);
                                int uvPosV = (y / 2) * vStrideDst + (x / 2);
                                uDst.put(uvPosU, (byte) 190); // Cyan tint
                                vDst.put(uvPosV, (byte) 75);
                            } else {
                                int orig = yDst.get(pos) & 0xFF;
                                int dimmed = Math.round(orig * (1.0f - 0.82f * maskStrength) + 18 * maskStrength);
                                yDst.put(pos, (byte) dimmed);
                            }
                        }
                    }
                }
                break;
            }
            case MASK_PHANTOM: {
                // Rotated faceted polygonal shield with golden wireframe edges & crimson eye slits
                for (int y = roiTop; y <= roiBottom; y++) {
                    for (int x = roiLeft; x <= roiRight; x++) {
                        float dx = x - cx;
                        float dy = y - cy;
                        float localX = (dx * cosR - dy * sinR) / rx;
                        float localY = (dx * sinR + dy * cosR) / ry;

                        // Polygonal tapered jaw shield envelope
                        float maxSpanX = localY < 0.25f ? 0.96f : Math.max(0.0f, 0.96f * (1.0f - (localY - 0.25f) * 1.15f));
                        if (localY >= -0.92f && localY <= 0.98f && Math.abs(localX) <= maxSpanX) {
                            int pos = y * yStrideDst + x;
                            boolean inEyeSlit = (localY >= -0.30f && localY <= -0.18f)
                                    && (Math.abs(localX) >= 0.18f && Math.abs(localX) <= 0.58f);
                            boolean onCrestOrEdge = Math.abs(localX) < 0.03f
                                    || Math.abs(Math.abs(localX) - maxSpanX) < 0.045f;

                            if (inEyeSlit) {
                                yDst.put(pos, (byte) 210);
                                int uvPosV = (y / 2) * vStrideDst + (x / 2);
                                vDst.put(uvPosV, (byte) 205); // Crimson glow
                            } else if (onCrestOrEdge) {
                                yDst.put(pos, (byte) 220); // Gold wireframe highlight
                            } else {
                                int orig = yDst.get(pos) & 0xFF;
                                int masked = Math.round(orig * (1.0f - 0.88f * maskStrength) + 16 * maskStrength);
                                yDst.put(pos, (byte) masked);
                            }
                        }
                    }
                }
                break;
            }
            case MASK_GLITCH: {
                // Real-time 16x16 macroblock mosaic pixelation strictly inside the rotated face oval
                int blockSize = 16;
                for (int by = roiTop; by <= roiBottom; by += blockSize) {
                    for (int bx = roiLeft; bx <= roiRight; bx += blockSize) {
                        float bcx = bx + blockSize * 0.5f - cx;
                        float bcy = by + blockSize * 0.5f - cy;
                        float localX = (bcx * cosR - bcy * sinR) / rx;
                        float localY = (bcx * sinR + bcy * cosR) / ry;
                        if (localX * localX + localY * localY > 1.08f) {
                            continue;
                        }

                        int sum = 0;
                        int count = 0;
                        int maxBy = Math.min(by + blockSize, roiBottom);
                        int maxBx = Math.min(bx + blockSize, roiRight);

                        for (int y = by; y < maxBy; y++) {
                            for (int x = bx; x < maxBx; x++) {
                                sum += (yDst.get(y * yStrideDst + x) & 0xFF);
                                count++;
                            }
                        }
                        int avg = count > 0 ? (sum / count) : 128;

                        for (int y = by; y < maxBy; y++) {
                            for (int x = bx; x < maxBx; x++) {
                                int pos = y * yStrideDst + x;
                                int orig = yDst.get(pos) & 0xFF;
                                int blended = Math.round(orig * (1.0f - maskStrength) + avg * maskStrength);
                                yDst.put(pos, (byte) blended);
                            }
                        }
                    }
                }
                break;
            }
            case MASK_SILHOUETTE: {
                // Matte shadow blackout + glowing phantom eyes pinned to rotated face coordinates
                for (int y = roiTop; y <= roiBottom; y++) {
                    for (int x = roiLeft; x <= roiRight; x++) {
                        float dx = x - cx;
                        float dy = y - cy;
                        float localX = (dx * cosR - dy * sinR) / rx;
                        float localY = (dx * sinR + dy * cosR) / ry;
                        float distSq = localX * localX + localY * localY;
                        if (distSq <= 1.0f) {
                            int pos = y * yStrideDst + x;
                            boolean inEye = (Math.abs(localY - (-0.24f)) < 0.08f)
                                    && (Math.abs(Math.abs(localX) - 0.38f) < 0.12f);
                            if (inEye) {
                                yDst.put(pos, (byte) 250);
                            } else {
                                int orig = yDst.get(pos) & 0xFF;
                                int sil = Math.round(orig * (1.0f - 0.92f * maskStrength) + 12 * maskStrength);
                                yDst.put(pos, (byte) sil);
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // VIEW COMPONENT: VeilArMaskOverlayView for Live CameraX / Call Viewfinder
    // ---------------------------------------------------------------------------------------------
    public static class OverlayView extends View {

        private int maskMode = MASK_NONE;
        private float trustReveal = 0.0f; // 0.0f = full mask
        private final RectF faceBounds = new RectF();

        public OverlayView(Context context) {
            super(context);
            init();
        }

        public OverlayView(Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        public OverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
            init();
        }

        private void init() {
            setWillNotDraw(false);
        }

        public void setMaskMode(int mode) {
            this.maskMode = mode;
            invalidate();
        }

        public int getMaskMode() {
            return maskMode;
        }

        public void setTrustReveal(float trust) {
            this.trustReveal = Math.max(0.0f, Math.min(1.0f, trust));
            invalidate();
        }

        public float getTrustReveal() {
            return trustReveal;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (maskMode == MASK_NONE || trustReveal >= 0.98f) {
                return;
            }

            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            com.m3.veil.calling.VeilFaceDetectionPipeline.TrackedFace face =
                    com.m3.veil.calling.VeilFaceDetectionPipeline.getInstance().getLatestPrimaryFace();
            if (face != null) {
                float cx = face.centerX * w;
                float cy = face.centerY * h;
                float rx = face.radiusX * w * 1.08f;
                float ry = face.radiusY * h * 1.10f;
                faceBounds.set(cx - rx, cy - ry, cx + rx, cy + ry);
            } else {
                float faceW = w * 0.54f;
                float faceH = h * 0.40f;
                float faceL = (w - faceW) * 0.5f;
                float faceT = h * 0.20f;
                faceBounds.set(faceL, faceT, faceL + faceW, faceT + faceH);
            }

            long now = SystemClock.uptimeMillis();
            VeilArMaskRenderer.getInstance().renderMaskOnCanvas(canvas, faceBounds, maskMode, trustReveal, now);

            // Continuously loop animation at 60 FPS
            postInvalidateOnAnimation();
        }
    }
}
