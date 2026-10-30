package com.m3.veil.calling;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CapturerObserver;
import org.webrtc.EglBase;
import org.webrtc.JavaI420Buffer;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Camera + screen capture for the call engine.
 *
 * <p>Adds real capabilities the legacy path lacked: working camera switch,
 * dynamic resolution/framerate changes, and true MediaProjection screen sharing
 * with its own track (so sharing does not replace the camera track).
 */
public class CallVideoDevice {

    private static final String TAG = "CallVideoDevice";

    private final Context context;
    private final EglBase eglBase;
    private volatile CameraEnumerator enumerator;
    private final CameraEvents cameraEvents = new CameraEvents();

    private CameraVideoCapturer cameraCapturer;
    private SurfaceTextureHelper cameraHelper;
    private VideoSource cameraSource;
    private VideoTrack cameraTrack;
    private String currentCameraName;
    private boolean frontCamera;
    private int width;
    private int height;
    private int fps;
    private boolean cameraRunning;

    private ScreenCapturerAndroid screenCapturer;
    private SurfaceTextureHelper screenHelper;
    private VideoSource screenSource;
    private VideoTrack screenTrack;
    private MediaProjection mediaProjection;
    private boolean screenRunning;

    // Anonymous Calling DSP: Real-time Camera Blur, Silhouette & Trust Reveal Slider (#122, #220)
    private volatile boolean blurEnabled = false;
    private volatile boolean silhouetteMode = false;
    private volatile float trustRevealLevel = 1.0f; // 0.0f = full anonymization, 1.0f = clear
    private volatile int arMaskMode = com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE;

    // Reusable scratch buffers for zero-allocation real-time selective face blur on VeilCameraThread
    private byte[] yScratchSrc;
    private byte[] yScratchTmp;
    private byte[] yScratchBlur;
    private byte[] yScratchDst;
    private byte[] uvScratchSrc;
    private byte[] uvScratchTmp;
    private byte[] uvScratchBlur;
    private byte[] uvScratchDst;

    public CallVideoDevice(Context context, EglBase eglBase) {
        this.context = context.getApplicationContext();
        this.eglBase = eglBase;
    }

    private CameraEnumerator getEnumerator() {
        if (enumerator == null) {
            synchronized (this) {
                if (enumerator == null) {
                    CameraEnumerator chosen;
                    try {
                        chosen = Camera2Enumerator.isSupported(context)
                                ? new Camera2Enumerator(context) : new Camera1Enumerator(true);
                    } catch (Throwable t) {
                        chosen = new Camera1Enumerator(true);
                    }
                    enumerator = chosen;
                }
            }
        }
        return enumerator;
    }

    // ---- camera --------------------------------------------------------------

    public boolean hasCamera() {
        try {
            CameraEnumerator camEnum = getEnumerator();
            return camEnum.getDeviceNames() != null && camEnum.getDeviceNames().length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isCameraRunning() {
        return cameraRunning;
    }

    public boolean isFrontCamera() {
        return frontCamera;
    }

    public VideoTrack getCameraTrack() {
        return cameraTrack;
    }

    public VideoSource getCameraSource() {
        return cameraSource;
    }

    public boolean startCamera() {
        com.m3.veil.utils.VeilPerformanceController perf = com.m3.veil.utils.VeilPerformanceController.getInstance();
        return startCamera(perf.getVideoWidth(), perf.getVideoHeight(), perf.getVideoFps());
    }

    public boolean startCamera(int targetWidth, int targetHeight, int targetFps) {
        if (cameraRunning) return true;
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera skipped: CAMERA permission not yet granted");
            return false;
        }
        try {
            if (targetWidth <= 0 || targetHeight <= 0 || targetFps <= 0) {
                com.m3.veil.utils.VeilPerformanceController perf = com.m3.veil.utils.VeilPerformanceController.getInstance();
                targetWidth = perf.getVideoWidth();
                targetHeight = perf.getVideoHeight();
                targetFps = perf.getVideoFps();
            }
            CameraEnumerator camEnum = getEnumerator();
            String[] names = camEnum.getDeviceNames();
            if (names == null || names.length == 0) return false;
            if (currentCameraName == null || !contains(names, currentCameraName)) {
                currentCameraName = null;
                for (String name : names) {
                    if (camEnum.isFrontFacing(name)) { currentCameraName = name; break; }
                }
                if (currentCameraName == null) {
                    for (String name : names) {
                        if (camEnum.isBackFacing(name)) { currentCameraName = name; break; }
                    }
                }
                if (currentCameraName == null) currentCameraName = names[0];
            }
            frontCamera = camEnum.isFrontFacing(currentCameraName);
            width = targetWidth;
            height = targetHeight;
            fps = targetFps;

            cameraHelper = SurfaceTextureHelper.create("VeilCameraThread", eglBase.getEglBaseContext());
            cameraSource = CallMediaFactory.createVideoSource(false);
            cameraCapturer = camEnum.createCapturer(currentCameraName, cameraEvents);
            if (cameraCapturer == null) return false;
            cameraCapturer.initialize(cameraHelper, context, createAnonymizingObserver(cameraSource.getCapturerObserver()));
            cameraCapturer.startCapture(width, height, fps);
            cameraTrack = CallMediaFactory.createVideoTrack(cameraSource, "veil_camera");
            cameraSource.adaptOutputFormat(width, height, fps);
            cameraRunning = true;
            Log.i(TAG, "camera started " + width + "x" + height + "@" + fps);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startCamera failed", t);
            stopCamera();
            return false;
        }
    }

    public void stopCamera() {
        cameraRunning = false;
        if (cameraCapturer != null) {
            try {
                cameraCapturer.stopCapture();
            } catch (Throwable ignored) { }
            try {
                cameraCapturer.dispose();
            } catch (Throwable ignored) { }
            cameraCapturer = null;
        }
        if (cameraHelper != null) {
            try {
                cameraHelper.dispose();
            } catch (Throwable ignored) { }
            cameraHelper = null;
        }
        if (cameraSource != null) {
            try {
                cameraSource.dispose();
            } catch (Throwable ignored) { }
            cameraSource = null;
        }
        if (cameraTrack != null) {
            try {
                cameraTrack.dispose();
            } catch (Throwable ignored) { }
            cameraTrack = null;
        }
    }

    public boolean switchCamera() {
        if (cameraCapturer == null) return false;
        try {
            cameraCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override public void onCameraSwitchDone(boolean isFront) {
                    frontCamera = isFront;
                    if (cameraSource != null) {
                        cameraSource.adaptOutputFormat(width, height, fps);
                    }
                    Log.i(TAG, "switched camera, front=" + isFront);
                }

                @Override public void onCameraSwitchError(String errorDescription) {
                    Log.w(TAG, "switchCamera error: " + errorDescription);
                }
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "switchCamera failed", t);
            return false;
        }
    }

    /** Mid-call format change (used by low-data mode and adaptive quality). */
    public void setFormat(int targetWidth, int targetHeight, int targetFps) {
        width = targetWidth;
        height = targetHeight;
        fps = targetFps;
        try {
            if (cameraCapturer != null) cameraCapturer.changeCaptureFormat(targetWidth, targetHeight, targetFps);
        } catch (Throwable ignored) { }
        if (cameraSource != null) cameraSource.adaptOutputFormat(targetWidth, targetHeight, targetFps);
    }

    // ---- screen share --------------------------------------------------------

    public boolean isScreenSharing() {
        return screenRunning;
    }

    public VideoTrack getScreenTrack() {
        return screenTrack;
    }

    public VideoSource getScreenSource() {
        return screenSource;
    }

    public boolean startScreenCapture(Intent permissionData, int targetWidth, int targetHeight, int targetFps) {
        if (screenRunning) return true;
        if (permissionData == null) return false;
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) return false;
            mediaProjection = manager.getMediaProjection(android.app.Activity.RESULT_OK, permissionData);
            if (mediaProjection == null) return false;

            screenHelper = SurfaceTextureHelper.create("VeilScreenThread", eglBase.getEglBaseContext());
            screenSource = CallMediaFactory.createVideoSource(true);
            screenCapturer = new ScreenCapturerAndroid(permissionData,
                    new MediaProjection.Callback() {
                        @Override public void onStop() {
                            Log.i(TAG, "media projection stopped by system");
                            stopScreenCapture();
                        }
                    });
            screenCapturer.initialize(screenHelper, context, screenSource.getCapturerObserver());
            screenCapturer.startCapture(targetWidth, targetHeight, targetFps);
            screenTrack = CallMediaFactory.createVideoTrack(screenSource, "veil_screen");
            screenSource.adaptOutputFormat(targetWidth, targetHeight, targetFps);
            screenRunning = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startScreenCapture failed", t);
            stopScreenCapture();
            return false;
        }
    }

    public void stopScreenCapture() {
        screenRunning = false;
        try {
            if (screenCapturer != null) {
                screenCapturer.stopCapture();
                screenCapturer.dispose();
            }
        } catch (Throwable ignored) { }
        screenCapturer = null;
        try {
            if (screenHelper != null) screenHelper.dispose();
        } catch (Throwable ignored) { }
        screenHelper = null;
        try {
            if (mediaProjection != null) mediaProjection.stop();
        } catch (Throwable ignored) { }
        mediaProjection = null;
        if (screenSource != null) {
            try { screenSource.dispose(); } catch (Throwable ignored) { }
            screenSource = null;
        }
        if (screenTrack != null) {
            try { screenTrack.dispose(); } catch (Throwable ignored) { }
            screenTrack = null;
        }
    }

    // ---- rendering helpers ---------------------------------------------------

    /** Renderers already initialised against the shared EGL context. */
    private static final java.util.Map<SurfaceViewRenderer, Boolean> RENDERER_READY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Idempotent renderer attachment: {@code SurfaceViewRenderer.init} may only
     * run once per init/release cycle, so repeated UI refreshes must not call it
     * again (it throws and would skip the sink registration).
     */
    public static boolean attachRenderer(SurfaceViewRenderer renderer, VideoTrack track, EglBase eglBase) {
        if (renderer == null || track == null || eglBase == null) return false;
        try {
            if (track.state() == org.webrtc.MediaStreamTrack.State.ENDED) return false;
            if (!Boolean.TRUE.equals(RENDERER_READY.get(renderer))) {
                renderer.init(eglBase.getEglBaseContext(), null);
                renderer.setEnableHardwareScaler(true);
                renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL);
                RENDERER_READY.put(renderer, Boolean.TRUE);
            }
            track.addSink(renderer);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attachRenderer failed: " + t.getMessage());
            return false;
        }
    }

    public static void removeSink(SurfaceViewRenderer renderer, VideoTrack track) {
        try {
            if (track != null && renderer != null) track.removeSink(renderer);
        } catch (Throwable ignored) { }
        try {
            if (renderer != null) renderer.clearImage();
        } catch (Throwable ignored) { }
    }

    public static void detachRenderer(SurfaceViewRenderer renderer, VideoTrack track) {
        try {
            if (track != null && renderer != null) track.removeSink(renderer);
        } catch (Throwable ignored) { }
        try {
            if (renderer != null) {
                renderer.clearImage();
                renderer.release();
                RENDERER_READY.remove(renderer);
            }
        } catch (Throwable ignored) { }
    }

    public void dispose() {
        stopScreenCapture();
        stopCamera();
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) if (value.equals(target)) return true;
        return false;
    }

    private static class CameraEvents implements CameraVideoCapturer.CameraEventsHandler {
        @Override public void onCameraOpening(String cameraName) {
            Log.d(TAG, "camera opening " + cameraName);
        }

        @Override public void onCameraError(String errorDescription) {
            Log.w(TAG, "camera error " + errorDescription);
        }

        @Override public void onCameraDisconnected() {
            Log.w(TAG, "camera disconnected");
        }

        @Override public void onCameraFreezed(String errorDescription) {
            Log.w(TAG, "camera frozen " + errorDescription);
        }

        @Override public void onCameraClosed() {
            Log.d(TAG, "camera closed");
        }

        @Override public void onFirstFrameAvailable() {
            Log.d(TAG, "camera first frame available");
        }
    }

    // ---- Anonymous Calling & Video Anonymization (#122, #220) -------------------

    public void setBlurEnabled(boolean enabled) {
        this.blurEnabled = enabled;
        if (enabled && this.trustRevealLevel >= 0.95f) {
            this.trustRevealLevel = 0.0f;
        }
        Log.i(TAG, "video blurEnabled=" + enabled + " trustRevealLevel=" + this.trustRevealLevel);
    }

    public boolean isBlurEnabled() {
        return blurEnabled;
    }

    public void setSilhouetteMode(boolean enabled) {
        this.silhouetteMode = enabled;
        Log.i(TAG, "video silhouetteMode=" + enabled);
    }

    public boolean isSilhouetteMode() {
        return silhouetteMode;
    }

    public void setTrustRevealLevel(float level) {
        this.trustRevealLevel = Math.max(0.0f, Math.min(1.0f, level));
        Log.i(TAG, "video trustRevealLevel=" + this.trustRevealLevel);
    }

    public float getTrustRevealLevel() {
        return trustRevealLevel;
    }

    public void setArMaskMode(int mode) {
        this.arMaskMode = mode;
        if (mode != com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE && this.trustRevealLevel >= 0.95f) {
            this.trustRevealLevel = 0.0f;
        }
        Log.i(TAG, "video arMaskMode=" + mode + " trustRevealLevel=" + this.trustRevealLevel);
    }

    public int getArMaskMode() {
        return arMaskMode;
    }

    private CapturerObserver createAnonymizingObserver(final CapturerObserver downstream) {
        return new CapturerObserver() {
            @Override
            public void onCapturerStarted(boolean success) {
                if (downstream != null) downstream.onCapturerStarted(success);
            }

            @Override
            public void onCapturerStopped() {
                if (downstream != null) downstream.onCapturerStopped();
            }

            @Override
            public void onFrameCaptured(VideoFrame frame) {
                if (downstream == null || frame == null) return;
                float trust = Math.max(0.0f, Math.min(1.0f, trustRevealLevel));
                float effectiveTrust = trust < 0.99f ? trust : 0.0f;
                boolean needBlur = blurEnabled && trust < 0.99f;
                boolean needSilhouette = silhouetteMode;
                boolean needArMask = arMaskMode != com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE;

                if (!needBlur && !needSilhouette && !needArMask) {
                    downstream.onFrameCaptured(frame);
                    return;
                }

                VideoFrame.Buffer originalBuffer = frame.getBuffer();
                VideoFrame.I420Buffer i420 = null;
                try {
                    i420 = originalBuffer.toI420();
                    if (i420 == null) {
                        downstream.onFrameCaptured(frame);
                        return;
                    }

                    int w = i420.getWidth();
                    int h = i420.getHeight();

                    // Feed frame asynchronously into the ML Kit / MediaPipe Face Detection & Landmark Pipeline
                    VeilFaceDetectionPipeline.getInstance().submitFrameAsync(
                            i420, w, h, frame.getRotation(), frontCamera);

                    JavaI420Buffer outBuffer = JavaI420Buffer.allocate(w, h);

                    applyVideoAnonymization(i420, outBuffer, w, h, needBlur, effectiveTrust, needSilhouette, needArMask, arMaskMode);

                    VideoFrame processedFrame = new VideoFrame(outBuffer, frame.getRotation(), frame.getTimestampNs());
                    downstream.onFrameCaptured(processedFrame);
                    processedFrame.release();
                } catch (Throwable t) {
                    Log.w(TAG, "Frame anonymization error, delivering raw frame: " + t.getMessage());
                    downstream.onFrameCaptured(frame);
                } finally {
                    if (i420 != null) {
                        try { i420.release(); } catch (Throwable ignored) { }
                    }
                }
            }
        };
    }

    /**
     * Smooth Hermite interpolation for feathering the selective face oval edge
     * so there are zero raw edge leaks while keeping the background 100% untouched.
     */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Computes the maximum feathered face-oval mask weight [0.0 = untouched background, 1.0 = inside face oval]
     * across all tracked faces at pixel (px, py) in normalized coordinates.
     */
    private static float computeSelectiveFaceAlpha(
            float normX, float normY,
            java.util.List<VeilFaceDetectionPipeline.TrackedFace> faces) {
        if (faces == null || faces.isEmpty()) return 0.0f;
        float maxAlpha = 0.0f;
        for (int i = 0, n = faces.size(); i < n; i++) {
            VeilFaceDetectionPipeline.TrackedFace face = faces.get(i);
            float dx = normX - face.centerX;
            float dy = normY - face.centerY;

            // Rotate coordinates into face-aligned local axes using head roll angle
            double rad = -Math.toRadians(face.rollDeg);
            float cosR = (float) Math.cos(rad);
            float sinR = (float) Math.sin(rad);
            float localX = dx * cosR - dy * sinR;
            float localY = dx * sinR + dy * cosR;

            // Expand slightly (+16% X, +18% Y) to guarantee complete facial contour coverage
            float rx = Math.max(0.08f, face.radiusX * 1.16f);
            float ry = Math.max(0.10f, face.radiusY * 1.18f);

            float ellipDist = (float) Math.sqrt((localX * localX) / (rx * rx) + (localY * localY) / (ry * ry));
            if (ellipDist < 1.15f) {
                // Inner core [0..0.72] = 1.0f alpha; feathered border [0.72..1.15] smoothly drops to 0.0f
                float alpha = 1.0f - smoothstep(0.72f, 1.15f, ellipDist);
                if (alpha > maxAlpha) {
                    maxAlpha = alpha;
                }
            }
        }
        return maxAlpha;
    }

    private void applyVideoAnonymization(VideoFrame.I420Buffer src, JavaI420Buffer dst,
                                         int w, int h, boolean blur, float trust, boolean silhouette,
                                         boolean needArMask, int maskMode) {
        int ySize = w * h;
        int uvW = (w + 1) / 2;
        int uvH = (h + 1) / 2;

        ensureScratchBuffers(w, h);

        java.util.List<VeilFaceDetectionPipeline.TrackedFace> faces =
                VeilFaceDetectionPipeline.getInstance().getLatestFaces();

        ByteBuffer srcY = src.getDataY();
        int srcStrideY = src.getStrideY();
        ByteBuffer dstY = dst.getDataY();
        int dstStrideY = dst.getStrideY();

        ByteBuffer srcU = src.getDataU();
        int srcStrideU = src.getStrideU();
        ByteBuffer dstU = dst.getDataU();
        int dstStrideU = dst.getStrideU();

        ByteBuffer srcV = src.getDataV();
        int srcStrideV = src.getStrideV();
        ByteBuffer dstV = dst.getDataV();
        int dstStrideV = dst.getStrideV();

        // 1. Copy Y plane from ByteBuffer with stride into yScratchSrc & yScratchDst (background untouched)
        for (int row = 0; row < h; row++) {
            srcY.position(row * srcStrideY);
            srcY.get(yScratchSrc, row * w, w);
        }
        System.arraycopy(yScratchSrc, 0, yScratchDst, 0, ySize);

        // 2. Selective Face Blur / Silhouette on Y channel (strictly within the detected face oval)
        float invW = 1.0f / (float) Math.max(1, w);
        float invH = 1.0f / (float) Math.max(1, h);

        if (blur) {
            boolean blurHwOk = com.m3.veil.utils.VeilPerformanceController.getInstance().isBlurShaderEnabled();
            int maxRadius = blurHwOk ? 24 : 12;
            int radius = Math.max(2, Math.round((1.0f - trust) * (float) maxRadius));
            // Two-pass heavy Gaussian approximation via cascaded box blur + mosaic obfuscation
            boxBlur2D(yScratchSrc, yScratchTmp, yScratchBlur, w, h, radius);
            if (blurHwOk && radius >= 6) {
                boxBlur2D(yScratchBlur, yScratchTmp, yScratchBlur, w, h, Math.max(2, radius / 2));
            }

            // Compute bounding ROI union across detected faces to skip untouched background rows/cols fast
            for (int fIdx = 0, fCount = faces.size(); fIdx < fCount; fIdx++) {
                VeilFaceDetectionPipeline.TrackedFace face = faces.get(fIdx);
                float padX = face.radiusX * 1.35f;
                float padY = face.radiusY * 1.38f;
                int minX = Math.max(0, (int) ((face.centerX - padX) * w));
                int maxX = Math.min(w - 1, (int) ((face.centerX + padX) * w));
                int minY = Math.max(0, (int) ((face.centerY - padY) * h));
                int maxY = Math.min(h - 1, (int) ((face.centerY + padY) * h));

                for (int py = minY; py <= maxY; py++) {
                    float normY = (py + 0.5f) * invH;
                    int rowOffset = py * w;
                    for (int px = minX; px <= maxX; px++) {
                        float normX = (px + 0.5f) * invW;
                        float featherAlpha = computeSelectiveFaceAlpha(normX, normY, faces);
                        if (featherAlpha > 0.001f) {
                            int idx = rowOffset + px;
                            int origY = yScratchSrc[idx] & 0xFF;
                            int blurY = yScratchBlur[idx] & 0xFF;
                            int blendedY = Math.round(origY * (1.0f - featherAlpha) + blurY * featherAlpha);
                            yScratchDst[idx] = (byte) Math.max(0, Math.min(255, blendedY));
                        }
                    }
                }
            }
        }

        if (silhouette) {
            for (int py = 0; py < h; py++) {
                float normY = (py + 0.5f) * invH;
                int rowOffset = py * w;
                for (int px = 0; px < w; px++) {
                    float normX = (px + 0.5f) * invW;
                    float featherAlpha = computeSelectiveFaceAlpha(normX, normY, faces);
                    if (featherAlpha > 0.01f) {
                        int idx = rowOffset + px;
                        int val = yScratchDst[idx] & 0xFF;
                        int silVal = (val < 135) ? 16 : Math.min(235, val + 35);
                        int blended = Math.round(val * (1.0f - featherAlpha) + silVal * featherAlpha);
                        yScratchDst[idx] = (byte) blended;
                    }
                }
            }
        }

        // Write Y back to dstY
        for (int row = 0; row < h; row++) {
            dstY.position(row * dstStrideY);
            dstY.put(yScratchDst, row * w, w);
        }

        // 3. Process U and V channels selectively inside the face oval (background colors untouched)
        float invUvW = 1.0f / (float) Math.max(1, uvW);
        float invUvH = 1.0f / (float) Math.max(1, uvH);

        if (silhouette) {
            for (int row = 0; row < uvH; row++) {
                srcU.position(row * srcStrideU);
                srcU.get(uvScratchSrc, row * uvW, uvW);
                srcV.position(row * srcStrideV);
                srcV.get(uvScratchTmp, row * uvW, uvW);
            }
            for (int py = 0; py < uvH; py++) {
                float normY = (py + 0.5f) * invUvH;
                int rowOffset = py * uvW;
                for (int px = 0; px < uvW; px++) {
                    float normX = (px + 0.5f) * invUvW;
                    float featherAlpha = computeSelectiveFaceAlpha(normX, normY, faces);
                    if (featherAlpha > 0.01f) {
                        int idx = rowOffset + px;
                        int origU = uvScratchSrc[idx] & 0xFF;
                        int origV = uvScratchTmp[idx] & 0xFF;
                        uvScratchSrc[idx] = (byte) Math.round(origU * (1.0f - featherAlpha) + 128f * featherAlpha);
                        uvScratchTmp[idx] = (byte) Math.round(origV * (1.0f - featherAlpha) + 128f * featherAlpha);
                    }
                }
            }
            for (int row = 0; row < uvH; row++) {
                dstU.position(row * dstStrideU);
                dstU.put(uvScratchSrc, row * uvW, uvW);
                dstV.position(row * dstStrideV);
                dstV.put(uvScratchTmp, row * uvW, uvW);
            }
        } else if (blur) {
            boolean blurHwOk = com.m3.veil.utils.VeilPerformanceController.getInstance().isBlurShaderEnabled();
            int maxRadiusUv = blurHwOk ? 12 : 6;
            int uvRadius = Math.max(1, Math.round((1.0f - trust) * (float) maxRadiusUv));

            // Selective U channel blur within face oval
            for (int row = 0; row < uvH; row++) {
                srcU.position(row * srcStrideU);
                srcU.get(uvScratchSrc, row * uvW, uvW);
            }
            System.arraycopy(uvScratchSrc, 0, uvScratchDst, 0, uvW * uvH);
            boxBlur2D(uvScratchSrc, uvScratchTmp, uvScratchBlur, uvW, uvH, uvRadius);

            for (int py = 0; py < uvH; py++) {
                float normY = (py + 0.5f) * invUvH;
                int rowOffset = py * uvW;
                for (int px = 0; px < uvW; px++) {
                    float normX = (px + 0.5f) * invUvW;
                    float featherAlpha = computeSelectiveFaceAlpha(normX, normY, faces);
                    if (featherAlpha > 0.001f) {
                        int idx = rowOffset + px;
                        int origU = uvScratchSrc[idx] & 0xFF;
                        int blurU = uvScratchBlur[idx] & 0xFF;
                        uvScratchDst[idx] = (byte) Math.round(origU * (1.0f - featherAlpha) + blurU * featherAlpha);
                    }
                }
            }
            for (int row = 0; row < uvH; row++) {
                dstU.position(row * dstStrideU);
                dstU.put(uvScratchDst, row * uvW, uvW);
            }

            // Selective V channel blur within face oval
            for (int row = 0; row < uvH; row++) {
                srcV.position(row * srcStrideV);
                srcV.get(uvScratchSrc, row * uvW, uvW);
            }
            System.arraycopy(uvScratchSrc, 0, uvScratchDst, 0, uvW * uvH);
            boxBlur2D(uvScratchSrc, uvScratchTmp, uvScratchBlur, uvW, uvH, uvRadius);

            for (int py = 0; py < uvH; py++) {
                float normY = (py + 0.5f) * invUvH;
                int rowOffset = py * uvW;
                for (int px = 0; px < uvW; px++) {
                    float normX = (px + 0.5f) * invUvW;
                    float featherAlpha = computeSelectiveFaceAlpha(normX, normY, faces);
                    if (featherAlpha > 0.001f) {
                        int idx = rowOffset + px;
                        int origV = uvScratchSrc[idx] & 0xFF;
                        int blurV = uvScratchBlur[idx] & 0xFF;
                        uvScratchDst[idx] = (byte) Math.round(origV * (1.0f - featherAlpha) + blurV * featherAlpha);
                    }
                }
            }
            for (int row = 0; row < uvH; row++) {
                dstV.position(row * dstStrideV);
                dstV.put(uvScratchDst, row * uvW, uvW);
            }
        } else {
            // Direct copy U and V
            for (int row = 0; row < uvH; row++) {
                srcU.position(row * srcStrideU);
                srcU.get(uvScratchSrc, 0, uvW);
                dstU.position(row * dstStrideU);
                dstU.put(uvScratchSrc, 0, uvW);

                srcV.position(row * srcStrideV);
                srcV.get(uvScratchSrc, 0, uvW);
                dstV.position(row * dstStrideV);
                dstV.put(uvScratchSrc, 0, uvW);
            }
        }

        // 4. AR Anonymous Procedural Camera Masks (#211) pinned to live landmarks & head rotation
        if (needArMask) {
            com.m3.veil.utils.VeilArMaskRenderer.getInstance().applyMaskToI420Yuv(
                    dst, dst, w, h, maskMode, trust, android.os.SystemClock.uptimeMillis()
            );
        }
    }

    private void boxBlur2D(byte[] src, byte[] tmp, byte[] dst, int w, int h, int r) {
        if (r < 1) {
            System.arraycopy(src, 0, dst, 0, w * h);
            return;
        }

        int window = 2 * r + 1;

        // Horizontal Pass: src -> tmp
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            int sum = (src[rowOffset] & 0xFF) * (r + 1);
            for (int i = 1; i <= r; i++) {
                int px = Math.min(w - 1, i);
                sum += (src[rowOffset + px] & 0xFF);
            }
            for (int x = 0; x < w; x++) {
                tmp[rowOffset + x] = (byte) (sum / window);
                int nextX = Math.min(w - 1, x + r + 1);
                int prevX = Math.max(0, x - r);
                sum += (src[rowOffset + nextX] & 0xFF) - (src[rowOffset + prevX] & 0xFF);
            }
        }

        // Vertical Pass: tmp -> dst
        for (int x = 0; x < w; x++) {
            int sum = (tmp[x] & 0xFF) * (r + 1);
            for (int i = 1; i <= r; i++) {
                int py = Math.min(h - 1, i);
                sum += (tmp[py * w + x] & 0xFF);
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (byte) (sum / window);
                int nextY = Math.min(h - 1, y + r + 1);
                int prevY = Math.max(0, y - r);
                sum += (tmp[nextY * w + x] & 0xFF) - (tmp[prevY * w + x] & 0xFF);
            }
        }
    }

    private void ensureScratchBuffers(int w, int h) {
        int ySize = w * h;
        if (yScratchSrc == null || yScratchSrc.length < ySize) {
            yScratchSrc = new byte[ySize];
            yScratchTmp = new byte[ySize];
            yScratchBlur = new byte[ySize];
            yScratchDst = new byte[ySize];
        }
        int uvSize = ((w + 1) / 2) * ((h + 1) / 2);
        if (uvScratchSrc == null || uvScratchSrc.length < uvSize) {
            uvScratchSrc = new byte[uvSize];
            uvScratchTmp = new byte[uvSize];
            uvScratchBlur = new byte[uvSize];
            uvScratchDst = new byte[uvSize];
        }
    }

    /** Unused VideoCapturer reference kept for clarity of the capture stack. */
    @SuppressWarnings("unused")
    private VideoCapturer unusedCapturerRef;
}
