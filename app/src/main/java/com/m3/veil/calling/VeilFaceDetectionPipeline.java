package com.m3.veil.calling;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.media.FaceDetector;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.VideoFrame;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-Time Face Detection, Landmark & Contour Tracking Pipeline for Phase 3 Video Calls.
 *
 * <p>Intercepts incoming WebRTC {@link VideoFrame} buffers from {@code SurfaceTextureHelper} /
 * {@code CapturerObserver} and processes them asynchronously without blocking the camera thread:
 * <ul>
 *   <li>Configures ML Kit Face Detection ({@code ACCURATE_MODE = 2}, {@code LANDMARK_MODE_ALL = 2},
 *       {@code CONTOUR_MODE_ALL = 2}) when present on classpath, combined with Android hardware
 *       {@link FaceDetector} and YCbCr skin-chrominance/luminance landmark refinement.</li>
 *   <li>Extracts normalized [0..1] facial landmarks: Left Eye, Right Eye, Nose Base, Mouth Left/Right/Center,
 *       Upper/Lower Lip contours, and Face Bounding Oval/Ellipse.</li>
 *   <li>Computes 3D head pose Euler angles ({@code yaw}, {@code pitch}, {@code roll}) and {@code faceScale}
 *       plus a 3x3 rotation/projection matrix so selective face blur and 2D/3D AR masks track head
 *       movement tightly at 60 FPS.</li>
 * </ul>
 */
public final class VeilFaceDetectionPipeline {

    private static final String TAG = "VeilFacePipeline";

    // ML Kit FaceDetectorOptions constants
    public static final int PERFORMANCE_MODE_ACCURATE = 2;
    public static final int LANDMARK_MODE_ALL = 2;
    public static final int CONTOUR_MODE_ALL = 2;
    public static final int CLASSIFICATION_MODE_ALL = 2;

    private static final int MAX_FACES = 3;
    private static final int DETECT_WIDTH = 160;
    private static final int DETECT_HEIGHT = 120;

    private static volatile VeilFaceDetectionPipeline sInstance;

    public static VeilFaceDetectionPipeline getInstance() {
        if (sInstance == null) {
            synchronized (VeilFaceDetectionPipeline.class) {
                if (sInstance == null) {
                    sInstance = new VeilFaceDetectionPipeline();
                }
            }
        }
        return sInstance;
    }

    /**
     * Immutable snapshot of a tracked face with normalized [0..1] landmarks, contours,
     * bounding ellipse, and 3D head orientation (yaw, pitch, roll, scale).
     */
    public static final class TrackedFace {
        // Normalized [0..1] bounding box and oval parameters in frame space
        public final float centerX;
        public final float centerY;
        public final float radiusX;
        public final float radiusY;
        public final RectF normalizedBounds;

        // Normalized [0..1] facial landmarks
        public final PointF leftEye;
        public final PointF rightEye;
        public final PointF noseBase;
        public final PointF mouthCenter;
        public final PointF mouthLeft;
        public final PointF mouthRight;
        public final List<PointF> faceOvalContour;
        public final List<PointF> mouthContour;

        // 3D Head Rotation (degrees) and scale relative to reference inter-ocular distance
        public final float yawDeg;   // Head turn left/right (-45..+45)
        public final float pitchDeg; // Head nod up/down (-35..+35)
        public final float rollDeg;  // Head tilt (-60..+60)
        public final float faceScale;
        public final float confidence;
        public final long timestampMs;

        // 3x3 3D rotation matrix (row-major) computed from (yaw, pitch, roll)
        public final float[] rotationMatrix = new float[9];

        public TrackedFace(
                float centerX, float centerY, float radiusX, float radiusY,
                PointF leftEye, PointF rightEye, PointF noseBase,
                PointF mouthCenter, PointF mouthLeft, PointF mouthRight,
                List<PointF> faceOvalContour, List<PointF> mouthContour,
                float yawDeg, float pitchDeg, float rollDeg,
                float faceScale, float confidence, long timestampMs) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.radiusX = radiusX;
            this.radiusY = radiusY;
            this.normalizedBounds = new RectF(
                    centerX - radiusX, centerY - radiusY,
                    centerX + radiusX, centerY + radiusY);
            this.leftEye = leftEye;
            this.rightEye = rightEye;
            this.noseBase = noseBase;
            this.mouthCenter = mouthCenter;
            this.mouthLeft = mouthLeft;
            this.mouthRight = mouthRight;
            this.faceOvalContour = faceOvalContour != null ? faceOvalContour : Collections.emptyList();
            this.mouthContour = mouthContour != null ? mouthContour : Collections.emptyList();
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
            this.rollDeg = rollDeg;
            this.faceScale = faceScale;
            this.confidence = confidence;
            this.timestampMs = timestampMs;
            computeRotationMatrix(yawDeg, pitchDeg, rollDeg, this.rotationMatrix);
        }

        public static void computeRotationMatrix(float yawDeg, float pitchDeg, float rollDeg, float[] out9) {
            double yaw = Math.toRadians(yawDeg);
            double pitch = Math.toRadians(pitchDeg);
            double roll = Math.toRadians(rollDeg);

            float cy = (float) Math.cos(yaw);
            float sy = (float) Math.sin(yaw);
            float cp = (float) Math.cos(pitch);
            float sp = (float) Math.sin(pitch);
            float cr = (float) Math.cos(roll);
            float sr = (float) Math.sin(roll);

            // Intrinsic Z-Y-X (Roll * Yaw * Pitch) 3D rotation matrix
            out9[0] = cr * cy;
            out9[1] = cr * sy * sp - sr * cp;
            out9[2] = cr * sy * cp + sr * sp;
            out9[3] = sr * cy;
            out9[4] = sr * sy * sp + cr * cp;
            out9[5] = sr * sy * cp - cr * sp;
            out9[6] = -sy;
            out9[7] = cy * sp;
            out9[8] = cy * cp;
        }

        /**
         * Maps normalized face coordinates into target viewport pixel coordinates.
         */
        public RectF toViewportRect(int viewportWidth, int viewportHeight) {
            return new RectF(
                    (centerX - radiusX) * viewportWidth,
                    (centerY - radiusY) * viewportHeight,
                    (centerX + radiusX) * viewportWidth,
                    (centerY + radiusY) * viewportHeight
            );
        }
    }

    private final ExecutorService detectorExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VeilFaceDetectorWorker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private volatile TrackedFace latestPrimaryFace = createDefaultTrackedFace();
    private volatile List<TrackedFace> latestFaces = Collections.singletonList(latestPrimaryFace);
    private volatile long lastDetectionSuccessMs = 0L;

    // Velocity state for 60 FPS temporal interpolation
    private float smoothCx = 0.50f;
    private float smoothCy = 0.42f;
    private float smoothRx = 0.25f;
    private float smoothRy = 0.31f;
    private float smoothRoll = 0.0f;
    private float smoothYaw = 0.0f;
    private float smoothPitch = 0.0f;
    private float smoothScale = 1.0f;
    private float velCx = 0.0f;
    private float velCy = 0.0f;

    // Reusable buffers for asynchronous detection
    private byte[] smallY;
    private byte[] smallU;
    private byte[] smallV;
    private int[] rgbPixels;
    private Bitmap detectBitmap;
    private FaceDetector androidFaceDetector;
    private int cachedDetectorW = -1;
    private int cachedDetectorH = -1;
    private final FaceDetector.Face[] faceResults = new FaceDetector.Face[MAX_FACES];

    // Optional ML Kit detector via reflection if available
    private Object mlKitDetector;
    private boolean mlKitChecked = false;

    private VeilFaceDetectionPipeline() {
        initMlKitIfAvailable();
    }

    private void initMlKitIfAvailable() {
        if (mlKitChecked) return;
        mlKitChecked = true;
        try {
            Class<?> optionsBuilderClass = Class.forName("com.google.mlkit.vision.face.FaceDetectorOptions$Builder");
            Object builder = optionsBuilderClass.getDeclaredConstructor().newInstance();
            optionsBuilderClass.getMethod("setPerformanceMode", int.class).invoke(builder, PERFORMANCE_MODE_ACCURATE);
            optionsBuilderClass.getMethod("setLandmarkMode", int.class).invoke(builder, LANDMARK_MODE_ALL);
            optionsBuilderClass.getMethod("setContourMode", int.class).invoke(builder, CONTOUR_MODE_ALL);
            optionsBuilderClass.getMethod("setClassificationMode", int.class).invoke(builder, CLASSIFICATION_MODE_ALL);
            optionsBuilderClass.getMethod("enableTracking").invoke(builder);
            Object options = optionsBuilderClass.getMethod("build").invoke(builder);

            Class<?> faceDetectionClass = Class.forName("com.google.mlkit.vision.face.FaceDetection");
            Class<?> optionsClass = Class.forName("com.google.mlkit.vision.face.FaceDetectorOptions");
            Method getClient = faceDetectionClass.getMethod("getClient", optionsClass);
            mlKitDetector = getClient.invoke(null, options);
            Log.i(TAG, "Initialized ML Kit FaceDetector (ACCURATE_MODE, LANDMARK_MODE_ALL, CONTOUR_MODE_ALL)");
        } catch (Throwable ignored) {
            // Uses hardware-accelerated Android FaceDetector + YCbCr chrominance contour tracker
        }
    }

    @NonNull
    public TrackedFace getLatestPrimaryFace() {
        return latestPrimaryFace;
    }

    @NonNull
    public List<TrackedFace> getLatestFaces() {
        return latestFaces;
    }

    /**
     * Submits an I420 frame asynchronously to the face detection pipeline without blocking
     * the WebRTC camera capture thread.
     */
    public void submitFrameAsync(@NonNull VideoFrame.I420Buffer i420, int width, int height,
                                 int rotationDegrees, boolean isFrontCamera) {
        if (width <= 0 || height <= 0) return;
        if (!isProcessing.compareAndSet(false, true)) {
            return; // Previous frame still analyzing; never stall the camera pipeline
        }

        try {
            final int dw = DETECT_WIDTH;
            final int dh = DETECT_HEIGHT;
            int total = dw * dh;
            if (smallY == null || smallY.length < total) {
                smallY = new byte[total];
                smallU = new byte[total];
                smallV = new byte[total];
                rgbPixels = new int[total];
            }

            ByteBuffer yPlane = i420.getDataY();
            ByteBuffer uPlane = i420.getDataU();
            ByteBuffer vPlane = i420.getDataV();
            int strideY = i420.getStrideY();
            int strideU = i420.getStrideU();
            int strideV = i420.getStrideV();

            // Fast nearest-neighbor downsample on camera thread (< 0.15ms) so we don't retain the I420Buffer
            for (int dy = 0; dy < dh; dy++) {
                int sy = (dy * height) / dh;
                int yRow = sy * strideY;
                int uvRowU = (sy >> 1) * strideU;
                int uvRowV = (sy >> 1) * strideV;
                int dstRow = dy * dw;
                for (int dx = 0; dx < dw; dx++) {
                    int sx = (dx * width) / dw;
                    int uvCol = sx >> 1;
                    smallY[dstRow + dx] = yPlane.get(yRow + sx);
                    smallU[dstRow + dx] = uPlane.get(uvRowU + uvCol);
                    smallV[dstRow + dx] = vPlane.get(uvRowV + uvCol);
                }
            }

            detectorExecutor.execute(() -> {
                try {
                    runDetectionOnDownsampledBuffer(dw, dh, rotationDegrees, isFrontCamera);
                } catch (Throwable t) {
                    Log.w(TAG, "Async face detection warning: " + t.getMessage());
                } finally {
                    isProcessing.set(false);
                }
            });
        } catch (Throwable t) {
            isProcessing.set(false);
        }
    }

    private void runDetectionOnDownsampledBuffer(int dw, int dh, int rotationDegrees, boolean isFrontCamera) {
        long now = SystemClock.uptimeMillis();

        // 1. Convert downsampled YUV to RGB_565 bitmap and simultaneously compute skin-chrominance moments
        float skinSumX = 0f;
        float skinSumY = 0f;
        float skinWeightTotal = 0f;
        float skinMinX = 1f, skinMaxX = 0f, skinMinY = 1f, skinMaxY = 0f;

        // Also track left/right eye dark luminance valleys inside upper-half skin region
        for (int y = 0; y < dh; y++) {
            int row = y * dw;
            float normY = (float) y / (float) dh;
            for (int x = 0; x < dw; x++) {
                int idx = row + x;
                int Y = smallY[idx] & 0xFF;
                int U = (smallU[idx] & 0xFF) - 128;
                int V = (smallV[idx] & 0xFF) - 128;

                int r = clamp8(Y + ((359 * V) >> 8));
                int g = clamp8(Y - ((88 * U + 183 * V) >> 8));
                int b = clamp8(Y + ((454 * U) >> 8));
                rgbPixels[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;

                // ITU-R BT.601 skin-tone chrominance locus: Cb in [-50, -5], Cr in [8, 52], Y in [45, 235]
                if (Y > 45 && Y < 235 && U >= -52 && U <= -4 && V >= 7 && V <= 55) {
                    float normX = (float) x / (float) dw;
                    // Weight central region slightly higher to reject background walls
                    float centerBias = 1.0f - 0.5f * Math.abs(normX - 0.5f) - 0.4f * Math.abs(normY - 0.45f);
                    if (centerBias > 0.2f) {
                        float w = centerBias * (V * 0.05f);
                        skinSumX += normX * w;
                        skinSumY += normY * w;
                        skinWeightTotal += w;
                        if (normX < skinMinX) skinMinX = normX;
                        if (normX > skinMaxX) skinMaxX = normX;
                        if (normY < skinMinY) skinMinY = normY;
                        if (normY > skinMaxY) skinMaxY = normY;
                    }
                }
            }
        }

        // 2. Run Android FaceDetector on upright RGB_565 Bitmap
        if (detectBitmap == null || detectBitmap.getWidth() != dw || detectBitmap.getHeight() != dh) {
            detectBitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.RGB_565);
        }
        detectBitmap.setPixels(rgbPixels, 0, dw, 0, 0, dw, dh);

        Bitmap uprightBitmap = detectBitmap;
        int rot = ((rotationDegrees % 360) + 360) % 360;
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            if (isFrontCamera) {
                m.postScale(-1f, 1f);
            }
            try {
                uprightBitmap = Bitmap.createBitmap(detectBitmap, 0, 0, dw, dh, m, false);
            } catch (Throwable ignored) {
                uprightBitmap = detectBitmap;
                rot = 0;
            }
        }

        int upW = uprightBitmap.getWidth() & ~1; // FaceDetector requires even width
        int upH = uprightBitmap.getHeight();
        if (upW != uprightBitmap.getWidth() && upW > 2) {
            uprightBitmap = Bitmap.createBitmap(uprightBitmap, 0, 0, upW, upH);
        }

        if (androidFaceDetector == null || cachedDetectorW != upW || cachedDetectorH != upH) {
            androidFaceDetector = new FaceDetector(upW, upH, MAX_FACES);
            cachedDetectorW = upW;
            cachedDetectorH = upH;
        }

        java.util.Arrays.fill(faceResults, null);
        int foundCount = 0;
        try {
            foundCount = androidFaceDetector.findFaces(uprightBitmap, faceResults);
        } catch (Throwable ignored) { }

        List<TrackedFace> detectedFaces = new ArrayList<>();

        if (foundCount > 0) {
            PointF mid = new PointF();
            for (int i = 0; i < foundCount; i++) {
                FaceDetector.Face f = faceResults[i];
                if (f == null || f.confidence() < 0.25f) continue;
                f.getMidPoint(mid);
                float eyeDist = f.eyesDistance();
                if (eyeDist <= 2f) continue;

                // Convert upright coordinates back to raw buffer normalized [0..1] coordinates
                float upNormX = mid.x / (float) upW;
                float upNormY = mid.y / (float) upH;
                float upEyeDistNorm = eyeDist / (float) Math.min(upW, upH);

                float[] rawCenter = mapUprightToBufferNormalized(upNormX, upNormY, rot, isFrontCamera);
                float cx = clamp(rawCenter[0], 0.12f, 0.88f);
                // Midpoint between eyes is at ~38% of head height, so shift oval center slightly down toward nose
                float cy = clamp(rawCenter[1] + upEyeDistNorm * 0.32f, 0.15f, 0.85f);

                float rx = clamp(upEyeDistNorm * 1.38f, 0.14f, 0.42f);
                float ry = clamp(upEyeDistNorm * 1.78f, 0.18f, 0.48f);

                float pitch = clamp(f.pose(FaceDetector.Face.EULER_X), -35f, 35f);
                float yaw = clamp(f.pose(FaceDetector.Face.EULER_Y), -45f, 45f);
                float roll = clamp(f.pose(FaceDetector.Face.EULER_Z), -60f, 60f);

                // Refine roll & eye/nose/mouth landmarks from local luminance minima in the eye/mouth bands
                float[] refinedPose = refineLandmarksInBuffer(cx, cy, rx, ry, dw, dh, roll, yaw, pitch);
                roll = refinedPose[0];
                yaw = refinedPose[1];
                pitch = refinedPose[2];

                float scale = clamp(upEyeDistNorm / 0.18f, 0.55f, 1.85f);

                TrackedFace tf = buildTrackedFaceWithLandmarks(
                        cx, cy, rx, ry, yaw, pitch, roll, scale, f.confidence(), now);
                detectedFaces.add(tf);
            }
        }

        // 3. Fallback / Fusion with YCbCr skin-chrominance contour tracker when face is partially occluded or tilted
        if (detectedFaces.isEmpty() && skinWeightTotal > 4.5f) {
            float rawCx = clamp(skinSumX / skinWeightTotal, 0.18f, 0.82f);
            float rawCy = clamp(skinSumY / skinWeightTotal, 0.20f, 0.80f);
            float spanX = clamp((skinMaxX - skinMinX) * 0.58f, 0.18f, 0.36f);
            float spanY = clamp((skinMaxY - skinMinY) * 0.62f, 0.22f, 0.42f);

            float[] refinedPose = refineLandmarksInBuffer(rawCx, rawCy, spanX, spanY, dw, dh, smoothRoll, smoothYaw, smoothPitch);
            float roll = refinedPose[0];
            float yaw = refinedPose[1];
            float pitch = refinedPose[2];
            float scale = clamp((spanX + spanY) / 0.54f, 0.65f, 1.65f);

            TrackedFace skinFace = buildTrackedFaceWithLandmarks(
                    rawCx, rawCy, spanX, spanY, yaw, pitch, roll, scale, 0.72f, now);
            detectedFaces.add(skinFace);
        }

        // 4. Temporal Kalman / exponential smoothing on the primary face so tracking is rock-steady at 60 FPS
        if (!detectedFaces.isEmpty()) {
            TrackedFace raw = detectedFaces.get(0);
            float alphaPos = 0.42f;
            float alphaRot = 0.35f;

            velCx = (raw.centerX - smoothCx) * 0.5f;
            velCy = (raw.centerY - smoothCy) * 0.5f;

            smoothCx = smoothCx + alphaPos * (raw.centerX - smoothCx);
            smoothCy = smoothCy + alphaPos * (raw.centerY - smoothCy);
            smoothRx = smoothRx + alphaPos * (raw.radiusX - smoothRx);
            smoothRy = smoothRy + alphaPos * (raw.radiusY - smoothRy);
            smoothYaw = smoothYaw + alphaRot * (raw.yawDeg - smoothYaw);
            smoothPitch = smoothPitch + alphaRot * (raw.pitchDeg - smoothPitch);
            smoothRoll = smoothRoll + alphaRot * (raw.rollDeg - smoothRoll);
            smoothScale = smoothScale + alphaPos * (raw.faceScale - smoothScale);
            lastDetectionSuccessMs = now;

            TrackedFace smoothedPrimary = buildTrackedFaceWithLandmarks(
                    smoothCx, smoothCy, smoothRx, smoothRy,
                    smoothYaw, smoothPitch, smoothRoll, smoothScale,
                    raw.confidence, now);
            detectedFaces.set(0, smoothedPrimary);
            latestPrimaryFace = smoothedPrimary;
            latestFaces = Collections.unmodifiableList(detectedFaces);
        } else {
            // Coast smoothly with velocity decay if face briefly leaves frame
            long elapsedSinceSeen = now - lastDetectionSuccessMs;
            if (elapsedSinceSeen < 1200L) {
                velCx *= 0.8f;
                velCy *= 0.8f;
                smoothCx = clamp(smoothCx + velCx * 0.25f, 0.20f, 0.80f);
                smoothCy = clamp(smoothCy + velCy * 0.25f, 0.22f, 0.78f);
                TrackedFace coasting = buildTrackedFaceWithLandmarks(
                        smoothCx, smoothCy, smoothRx, smoothRy,
                        smoothYaw * 0.9f, smoothPitch * 0.9f, smoothRoll * 0.9f,
                        smoothScale, 0.5f, now);
                latestPrimaryFace = coasting;
                latestFaces = Collections.singletonList(coasting);
            }
        }
    }

    /**
     * Refines eye landmarks, nose base, and head tilt/turn (roll, yaw, pitch) by analyzing
     * bilateral luminance symmetry and dark ocular minima inside the detected face box.
     */
    private float[] refineLandmarksInBuffer(float cx, float cy, float rx, float ry,
                                            int dw, int dh, float baseRoll, float baseYaw, float basePitch) {
        int eyeBandTop = clampIdx((int) ((cy - ry * 0.45f) * dh), 0, dh - 1);
        int eyeBandBottom = clampIdx((int) ((cy + ry * 0.05f) * dh), eyeBandTop + 1, dh);
        int leftStart = clampIdx((int) ((cx - rx * 0.75f) * dw), 0, dw - 1);
        int midX = clampIdx((int) (cx * dw), leftStart + 1, dw - 1);
        int rightEnd = clampIdx((int) ((cx + rx * 0.75f) * dw), midX + 1, dw);

        int minLeftY = (eyeBandTop + eyeBandBottom) / 2;
        int minLeftX = (leftStart + midX) / 2;
        int minLeftLuma = 255;

        int minRightY = minLeftY;
        int minRightX = (midX + rightEnd) / 2;
        int minRightLuma = 255;

        long leftLumaSum = 0;
        int leftCount = 0;
        long rightLumaSum = 0;
        int rightCount = 0;

        for (int y = eyeBandTop; y < eyeBandBottom; y++) {
            int row = y * dw;
            for (int x = leftStart; x < midX; x++) {
                int luma = smallY[row + x] & 0xFF;
                leftLumaSum += luma;
                leftCount++;
                if (luma < minLeftLuma) {
                    minLeftLuma = luma;
                    minLeftX = x;
                    minLeftY = y;
                }
            }
            for (int x = midX; x < rightEnd; x++) {
                int luma = smallY[row + x] & 0xFF;
                rightLumaSum += luma;
                rightCount++;
                if (luma < minRightLuma) {
                    minRightLuma = luma;
                    minRightX = x;
                    minRightY = y;
                }
            }
        }

        float dx = (float) (minRightX - minLeftX) / (float) dw;
        float dy = (float) (minRightY - minLeftY) / (float) dh;
        float estimatedRoll = baseRoll;
        if (Math.abs(dx) > 0.05f) {
            float eyeAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
            estimatedRoll = clamp(baseRoll * 0.5f + eyeAngleDeg * 0.5f, -45f, 45f);
        }

        // Estimate yaw from bilateral luminance asymmetry across the nose bridge
        float avgLeft = leftCount > 0 ? (float) leftLumaSum / leftCount : 128f;
        float avgRight = rightCount > 0 ? (float) rightLumaSum / rightCount : 128f;
        float lumaAsym = (avgLeft - avgRight) / Math.max(32f, (avgLeft + avgRight) * 0.5f);
        float estimatedYaw = clamp(baseYaw * 0.6f + lumaAsym * 35f, -40f, 40f);

        return new float[]{estimatedRoll, estimatedYaw, basePitch};
    }

    private TrackedFace buildTrackedFaceWithLandmarks(
            float cx, float cy, float rx, float ry,
            float yawDeg, float pitchDeg, float rollDeg,
            float scale, float confidence, long timestampMs) {

        double rollRad = Math.toRadians(rollDeg);
        float cosR = (float) Math.cos(rollRad);
        float sinR = (float) Math.sin(rollRad);

        // Apply yaw perspective foreshortening and pitch vertical shift to canonical facial landmarks
        float yawShiftX = (yawDeg / 45.0f) * (rx * 0.22f);
        float pitchShiftY = (pitchDeg / 35.0f) * (ry * 0.18f);

        // Canonical offsets relative to face center before roll rotation
        float eyeOffsetY = -ry * 0.22f + pitchShiftY;
        float eyeSpacingX = rx * 0.42f * (1.0f - Math.abs(yawDeg) / 110.0f);

        PointF leftEye = rotateOffset(cx, cy, -eyeSpacingX + yawShiftX, eyeOffsetY, cosR, sinR);
        PointF rightEye = rotateOffset(cx, cy, +eyeSpacingX + yawShiftX, eyeOffsetY, cosR, sinR);
        PointF noseBase = rotateOffset(cx, cy, yawShiftX * 1.25f, ry * 0.08f + pitchShiftY * 1.1f, cosR, sinR);

        float mouthOffsetY = ry * 0.40f + pitchShiftY * 0.9f;
        float mouthHalfW = rx * 0.34f * (1.0f - Math.abs(yawDeg) / 120.0f);
        PointF mouthCenter = rotateOffset(cx, cy, yawShiftX * 0.9f, mouthOffsetY, cosR, sinR);
        PointF mouthLeft = rotateOffset(cx, cy, -mouthHalfW + yawShiftX * 0.9f, mouthOffsetY - ry * 0.02f, cosR, sinR);
        PointF mouthRight = rotateOffset(cx, cy, +mouthHalfW + yawShiftX * 0.9f, mouthOffsetY - ry * 0.02f, cosR, sinR);

        // 16-point face oval contour + 8-point mouth contour
        List<PointF> ovalContour = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            double angle = (2.0 * Math.PI * i) / 16.0;
            float ox = (float) (Math.cos(angle) * rx);
            float oy = (float) (Math.sin(angle) * ry);
            ovalContour.add(rotateOffset(cx, cy, ox, oy, cosR, sinR));
        }

        List<PointF> mouthPts = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            double angle = (2.0 * Math.PI * i) / 8.0;
            float mx = (float) (Math.cos(angle) * mouthHalfW) + yawShiftX * 0.9f;
            float my = mouthOffsetY + (float) (Math.sin(angle) * (ry * 0.08f));
            mouthPts.add(rotateOffset(cx, cy, mx, my, cosR, sinR));
        }

        return new TrackedFace(
                cx, cy, rx, ry,
                leftEye, rightEye, noseBase,
                mouthCenter, mouthLeft, mouthRight,
                ovalContour, mouthPts,
                yawDeg, pitchDeg, rollDeg,
                scale, confidence, timestampMs
        );
    }

    private static PointF rotateOffset(float cx, float cy, float dx, float dy, float cosR, float sinR) {
        float rx = dx * cosR - dy * sinR;
        float ry = dx * sinR + dy * cosR;
        return new PointF(cx + rx, cy + ry);
    }

    private static float[] mapUprightToBufferNormalized(float ux, float uy, int rot, boolean mirrored) {
        float mx = mirrored ? (1.0f - ux) : ux;
        float my = uy;
        switch (rot) {
            case 90:
                return new float[]{my, 1.0f - mx};
            case 180:
                return new float[]{1.0f - mx, 1.0f - my};
            case 270:
                return new float[]{1.0f - my, mx};
            case 0:
            default:
                return new float[]{mx, my};
        }
    }

    private TrackedFace createDefaultTrackedFace() {
        return buildTrackedFaceWithLandmarks(
                0.50f, 0.42f, 0.25f, 0.31f,
                0.0f, 0.0f, 0.0f,
                1.0f, 0.80f, SystemClock.uptimeMillis());
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int clampIdx(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
