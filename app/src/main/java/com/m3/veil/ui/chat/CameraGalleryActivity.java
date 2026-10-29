package com.m3.veil.ui.chat;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.FileOutputStream;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;
import com.m3.veil.model.MediaAlbum;
import com.m3.veil.ui.chat.adapters.FullGalleryGridAdapter;
import com.m3.veil.ui.chat.adapters.RecentMediaHorizontalAdapter;
import com.m3.veil.utils.UiAnimator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen in-app Camera & Gallery Activity built natively using Android Jetpack CameraX
 * and MediaStore Buckets, exactly matching WhatsApp and Signal.
 * Features:
 * - Live CameraX viewfinder with tap-to-focus and pinch-to-zoom.
 * - Dual-action Shutter: Single tap to snap photo, hold to record video.
 * - Lens flip with 180° rotation and multi-mode flash toggle.
 * - Bottom recent media horizontal carousel.
 * - Swipe-up / tap expandable 3-column media gallery with album selector and multi-selection badges (1, 2, 3...).
 * - Seamless integration with MediaEditActivity and P2P messaging pipeline.
 */
public class CameraGalleryActivity extends AppCompatActivity {

    private static final String TAG = "CameraGalleryActivity";
    private static final int PERMISSION_REQ_CODE = 301;
    private static final int REQ_EDIT_MEDIA = 302;

    public static final String RESULT_MEDIA_PATH = "result_media_path";
    public static final String RESULT_MEDIA_TYPE = "result_media_type";
    public static final String RESULT_MEDIA_CAPTION = "result_media_caption";
    public static final String RESULT_BATCH_PATHS = "result_batch_paths";
    public static final String RESULT_BATCH_TYPES = "result_batch_types";
    public static final String EXTRA_START_IN_GALLERY = "extra_start_in_gallery";
    private boolean startedDirectlyInGallery = false;
    private final ArrayList<String> pendingBatchPaths = new ArrayList<>();
    private final ArrayList<String> pendingBatchTypes = new ArrayList<>();

    // Camera Views
    private PreviewView cameraPreviewView;
    private com.m3.veil.utils.VeilArMaskOverlayView arMaskOverlayView;
    private View viewFocusRing;
    private ImageView btnCloseCamera;
    private ImageView btnFlashToggle;
    private TextView btnArMaskToggle;
    private LinearLayout layoutRecordingTimer;
    private TextView tvRecordingTimer;
    private FrameLayout shutterButton;
    private View shutterInnerCircle;
    private ImageView btnFlipLens;
    private ImageView btnOpenFullGallery;
    private LinearLayout btnSwipeUpGallery;
    private RecyclerView rvRecentMediaHorizontal;
    private View viewFlashScrim;

    // Full Gallery Views
    private LinearLayout layoutFullGallery;
    private ImageView btnBackFromGallery;
    private LinearLayout btnAlbumDropdown;
    private TextView tvCurrentAlbumName;
    private TextView tvGallerySelectCount;
    private RecyclerView rvFullGalleryGrid;
    private ProgressBar galleryLoaderProgressBar;
    private LinearLayout layoutGalleryEmptyState;
    private TextView tvGalleryEmptyMessage;
    private FrameLayout layoutFloatingSend;
    private ExtendedFloatingActionButton fabSendSelected;

    // Telegram-Grade Camera Members
    private static final int MODE_PHOTO = 0;
    private static final int MODE_VIDEO = 1;
    private int currentCameraMode = MODE_PHOTO;
    private boolean isVideoLocked = false;
    private float touchDownY = 0f;

    // Zoom Pills
    private LinearLayout layoutZoomPills;
    private TextView btnZoom05x;
    private TextView btnZoom1x;
    private TextView btnZoom2x;
    private TextView btnZoom5x;

    // Mode Switcher
    private LinearLayout layoutCameraModes;
    private TextView btnModePhoto;
    private TextView btnModeVideo;

    // Shutter Lock Indicator
    private LinearLayout layoutShutterLock;
    private ImageView ivShutterLockIcon;
    private TextView tvShutterLockText;

    // CameraX Members
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private int currentMaskMode = com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE;

    // Gestures
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector tapGestureDetector;

    // Recording State
    private boolean isRecording = false;
    private long recordingStartTime = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                long elapsedSeconds = (SystemClock.elapsedRealtime() - recordingStartTime) / 1000;
                long minutes = elapsedSeconds / 60;
                long seconds = elapsedSeconds % 60;
                tvRecordingTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 500);
            }
        }
    };

    // Gallery Members
    private final List<GalleryMediaItem> allMediaItems = new ArrayList<>();
    private final List<GalleryMediaItem> displayedMediaItems = new ArrayList<>();
    private final List<GalleryMediaItem> recentMediaItems = new ArrayList<>();
    private final Map<String, MediaAlbum> albumMap = new HashMap<>();
    private FullGalleryGridAdapter fullGalleryAdapter;
    private RecentMediaHorizontalAdapter recentMediaAdapter;
    private final ExecutorService mediaScanExecutor = Executors.newSingleThreadExecutor();
    private String currentSelectedBucketId = "all";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera_gallery);

        initViews();
        handleWindowInsets();
        setupGestures();
        setupActions();

        startedDirectlyInGallery = getIntent().getBooleanExtra(EXTRA_START_IN_GALLERY, false);
        if (startedDirectlyInGallery && layoutFullGallery != null) {
            layoutFullGallery.setVisibility(View.VISIBLE);
            layoutFullGallery.setTranslationY(0f);
        }

        if (!startedDirectlyInGallery && hasCameraPermission()) {
            startCamera();
        }
        if (hasStoragePermission()) {
            loadMediaLibrary();
        }
        if ((!startedDirectlyInGallery && !hasCameraPermission()) || !hasStoragePermission()) {
            requestPermissions();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStoragePermission() && allMediaItems.isEmpty()) {
            loadMediaLibrary();
        }
        boolean galleryCoveringScreen = layoutFullGallery != null && layoutFullGallery.getVisibility() == View.VISIBLE;
        if (!galleryCoveringScreen && hasCameraPermission()) {
            if (cameraProvider == null) {
                startCamera();
            } else if (camera == null) {
                bindCameraUseCases();
            }
        }
    }

    @Override
    protected void onPause() {
        stopRecording();
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                camera = null;
            } catch (Throwable ignored) {}
        }
        super.onPause();
    }

    private void handleWindowInsets() {
        View root = findViewById(R.id.cameraGalleryRoot);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            View cameraSpacer = findViewById(R.id.statusInsetSpacerCamera);
            if (cameraSpacer != null) {
                ViewGroup.LayoutParams lp = cameraSpacer.getLayoutParams();
                if (lp != null && lp.height != systemBars.top) {
                    lp.height = systemBars.top;
                    cameraSpacer.setLayoutParams(lp);
                }
            }

            View gallerySpacer = findViewById(R.id.statusInsetSpacerGallery);
            if (gallerySpacer != null) {
                ViewGroup.LayoutParams lp = gallerySpacer.getLayoutParams();
                if (lp != null && lp.height != systemBars.top) {
                    lp.height = systemBars.top;
                    gallerySpacer.setLayoutParams(lp);
                }
            }

            View bottomBar = findViewById(R.id.bottomCameraControls);
            if (bottomBar != null) {
                bottomBar.setPadding(
                        bottomBar.getPaddingStart(),
                        bottomBar.getPaddingTop(),
                        bottomBar.getPaddingEnd(),
                        systemBars.bottom + (int) (16 * getResources().getDisplayMetrics().density)
                );
            }
            if (layoutFloatingSend != null) {
                layoutFloatingSend.setPadding(
                        layoutFloatingSend.getPaddingStart(),
                        layoutFloatingSend.getPaddingTop(),
                        layoutFloatingSend.getPaddingEnd(),
                        systemBars.bottom + (int) (16 * getResources().getDisplayMetrics().density)
                );
            }
            return insets;
        });
    }

    private void initViews() {
        cameraPreviewView = findViewById(R.id.cameraPreviewView);
        arMaskOverlayView = findViewById(R.id.arMaskOverlayView);
        viewFocusRing = findViewById(R.id.viewFocusRing);
        btnCloseCamera = findViewById(R.id.btnCloseCamera);
        btnFlashToggle = findViewById(R.id.btnFlashToggle);
        btnArMaskToggle = findViewById(R.id.btnArMaskToggle);
        layoutRecordingTimer = findViewById(R.id.layoutRecordingTimer);
        tvRecordingTimer = findViewById(R.id.tvRecordingTimer);
        shutterButton = findViewById(R.id.shutterButton);
        shutterInnerCircle = findViewById(R.id.shutterInnerCircle);
        btnFlipLens = findViewById(R.id.btnFlipLens);
        btnOpenFullGallery = findViewById(R.id.btnOpenFullGallery);
        btnSwipeUpGallery = findViewById(R.id.btnSwipeUpGallery);
        rvRecentMediaHorizontal = findViewById(R.id.rvRecentMediaHorizontal);
        viewFlashScrim = findViewById(R.id.viewFlashScrim);

        layoutFullGallery = findViewById(R.id.layoutFullGallery);
        btnBackFromGallery = findViewById(R.id.btnBackFromGallery);
        btnAlbumDropdown = findViewById(R.id.btnAlbumDropdown);
        tvCurrentAlbumName = findViewById(R.id.tvCurrentAlbumName);
        tvGallerySelectCount = findViewById(R.id.tvGallerySelectCount);
        rvFullGalleryGrid = findViewById(R.id.rvFullGalleryGrid);
        galleryLoaderProgressBar = findViewById(R.id.galleryLoaderProgressBar);
        layoutGalleryEmptyState = findViewById(R.id.layoutGalleryEmptyState);
        tvGalleryEmptyMessage = findViewById(R.id.tvGalleryEmptyMessage);
        layoutFloatingSend = findViewById(R.id.layoutFloatingSend);
        fabSendSelected = findViewById(R.id.fabSendSelected);

        layoutZoomPills = findViewById(R.id.layoutZoomPills);
        btnZoom05x = findViewById(R.id.btnZoom05x);
        btnZoom1x = findViewById(R.id.btnZoom1x);
        btnZoom2x = findViewById(R.id.btnZoom2x);
        btnZoom5x = findViewById(R.id.btnZoom5x);

        layoutCameraModes = findViewById(R.id.layoutCameraModes);
        btnModePhoto = findViewById(R.id.btnModePhoto);
        btnModeVideo = findViewById(R.id.btnModeVideo);

        layoutShutterLock = findViewById(R.id.layoutShutterLock);
        ivShutterLockIcon = findViewById(R.id.ivShutterLockIcon);
        tvShutterLockText = findViewById(R.id.tvShutterLockText);

        // Adapters setup
        recentMediaAdapter = new RecentMediaHorizontalAdapter(recentMediaItems, item -> openMediaEditor(item.getUri(), item.isVideo() ? "video" : "image"));
        rvRecentMediaHorizontal.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvRecentMediaHorizontal.setAdapter(recentMediaAdapter);

        fullGalleryAdapter = new FullGalleryGridAdapter(displayedMediaItems, new FullGalleryGridAdapter.OnGalleryInteractionListener() {
            @Override
            public void onMediaItemClicked(GalleryMediaItem item) {
                openMediaEditor(item.getUri(), item.isVideo() ? "video" : "image");
            }

            @Override
            public void onSelectionChanged(List<GalleryMediaItem> selectedItems) {
                updateSelectionUI(selectedItems);
            }
        });
        rvFullGalleryGrid.setLayoutManager(new GridLayoutManager(this, 3));
        rvFullGalleryGrid.setAdapter(fullGalleryAdapter);
    }

    private void setupActions() {
        btnCloseCamera.setOnClickListener(v -> finish());

        // AR Mask Toggle (#211)
        if (btnArMaskToggle != null) {
            btnArMaskToggle.setOnClickListener(v -> showArMaskPickerBottomSheet());
        }

        // Flash Toggle: OFF -> AUTO -> ON -> OFF
        btnFlashToggle.setOnClickListener(v -> {
            if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                flashMode = ImageCapture.FLASH_MODE_AUTO;
                btnFlashToggle.setImageResource(R.drawable.ic_flash_auto);
            } else if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
                flashMode = ImageCapture.FLASH_MODE_ON;
                btnFlashToggle.setImageResource(R.drawable.ic_flash_on);
            } else {
                flashMode = ImageCapture.FLASH_MODE_OFF;
                btnFlashToggle.setImageResource(R.drawable.ic_flash_off);
            }
            if (imageCapture != null) {
                imageCapture.setFlashMode(flashMode);
            }
        });

        // Lens Flip (Front / Back) with 180° rotation
        btnFlipLens.setOnClickListener(v -> flipLens());

        // Quick Zoom Pills (0.5x, 1x, 2x, 5x - Telegram Parity)
        if (btnZoom05x != null) btnZoom05x.setOnClickListener(v -> setZoomRatio(0.5f, btnZoom05x));
        if (btnZoom1x != null) btnZoom1x.setOnClickListener(v -> setZoomRatio(1.0f, btnZoom1x));
        if (btnZoom2x != null) btnZoom2x.setOnClickListener(v -> setZoomRatio(2.0f, btnZoom2x));
        if (btnZoom5x != null) btnZoom5x.setOnClickListener(v -> setZoomRatio(5.0f, btnZoom5x));

        // Camera Mode Switcher (PHOTO | VIDEO)
        if (btnModePhoto != null) btnModePhoto.setOnClickListener(v -> setCameraMode(MODE_PHOTO));
        if (btnModeVideo != null) btnModeVideo.setOnClickListener(v -> setCameraMode(MODE_VIDEO));

        // Shutter Lock Click to Stop
        if (layoutShutterLock != null) {
            layoutShutterLock.setOnClickListener(v -> {
                if (isRecording) {
                    stopRecording();
                }
            });
        }

        // Telegram-Grade Shutter (Tap Photo / Tap Video Toggle / Hold to Record with Slide-Up Lock)
        shutterButton.setOnTouchListener(new View.OnTouchListener() {
            private boolean isLongPressed = false;
            private final Handler longPressHandler = new Handler(Looper.getMainLooper());
            private final Runnable longPressRunnable = () -> {
                isLongPressed = true;
                startRecording();
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (currentCameraMode == MODE_VIDEO) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        animateShutterPress(true);
                        return true;
                    } else if (event.getAction() == MotionEvent.ACTION_UP) {
                        animateShutterPress(false);
                        if (isRecording) {
                            stopRecording();
                        } else {
                            startRecording();
                        }
                        return true;
                    }
                    return false;
                }

                // MODE_PHOTO
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        isLongPressed = false;
                        touchDownY = event.getRawY();
                        isVideoLocked = false;
                        longPressHandler.postDelayed(longPressRunnable, 350);
                        animateShutterPress(true);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (isRecording && !isVideoLocked) {
                            float deltaY = touchDownY - event.getRawY();
                            if (deltaY > 100) { // user slid finger up
                                lockRecording();
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacks(longPressRunnable);
                        animateShutterPress(false);
                        if (isVideoLocked) {
                            // Video is locked hands-free! Keep recording!
                            return true;
                        }
                        if (isLongPressed) {
                            stopRecording();
                        } else {
                            takePhoto();
                        }
                        return true;
                }
                return false;
            }
        });

        // Swipe up & button to open full gallery (works for both tap and upward swipe gesture)
        btnOpenFullGallery.setOnClickListener(v -> showFullGallery(true));
        btnSwipeUpGallery.setOnClickListener(v -> showFullGallery(true));

        View.OnTouchListener swipeUpBarTouchListener = new View.OnTouchListener() {
            private float downRawY = 0f;
            private float downRawX = 0f;
            private boolean triggered = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                float dp = getResources().getDisplayMetrics().density;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        downRawX = event.getRawX();
                        triggered = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = downRawY - event.getRawY();
                        float dx = Math.abs(downRawX - event.getRawX());
                        if (!triggered && dy > 22 * dp && dy > dx) {
                            triggered = true;
                            showFullGallery(true);
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!triggered) {
                            float totalDy = downRawY - event.getRawY();
                            if (totalDy > 16 * dp || Math.abs(totalDy) < 12 * dp) {
                                v.performClick();
                                showFullGallery(true);
                            }
                        }
                        return true;
                }
                return false;
            }
        };
        btnSwipeUpGallery.setOnTouchListener(swipeUpBarTouchListener);
        View bottomCameraControls = findViewById(R.id.bottomCameraControls);
        if (bottomCameraControls != null) {
            bottomCameraControls.setOnTouchListener((v, event) -> {
                swipeUpBarTouchListener.onTouch(v, event);
                return false;
            });
        }
        View galleryHeader = findViewById(R.id.layoutGalleryHeader);
        if (galleryHeader != null) {
            galleryHeader.setOnTouchListener(new View.OnTouchListener() {
                private float startRawY = 0f;
                private boolean closed = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    float dp = getResources().getDisplayMetrics().density;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawY = event.getRawY();
                            closed = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (!closed && event.getRawY() - startRawY > 36 * dp && !startedDirectlyInGallery) {
                                closed = true;
                                showFullGallery(false);
                                return true;
                            }
                            break;
                    }
                    return false;
                }
            });
        }

        // Back from full gallery
        btnBackFromGallery.setOnClickListener(v -> {
            if (fullGalleryAdapter.getSelectedItems().size() > 0) {
                fullGalleryAdapter.clearSelection();
            } else if (startedDirectlyInGallery) {
                finish();
            } else {
                showFullGallery(false);
            }
        });

        // Album Dropdown Selector
        btnAlbumDropdown.setOnClickListener(v -> showAlbumMenu());

        // Send Selected FAB -> Open MediaEditActivity with ALL selected photos/videos so user can swipe left/right
        fabSendSelected.setOnClickListener(v -> {
            List<GalleryMediaItem> selected = fullGalleryAdapter.getSelectedItems();
            if (selected.isEmpty()) return;
            pendingBatchPaths.clear();
            pendingBatchTypes.clear();
            ArrayList<String> batchUris = new ArrayList<>();
            ArrayList<String> batchTypes = new ArrayList<>();
            for (int i = 0; i < selected.size(); i++) {
                GalleryMediaItem extra = selected.get(i);
                String typeStr = extra.isVideo() ? "video" : "image";
                if (extra.getUri() != null) {
                    batchUris.add(extra.getUri().toString());
                    batchTypes.add(typeStr);
                } else {
                    String resolvedPath = resolveGalleryItemPath(extra);
                    if (resolvedPath != null) {
                        batchUris.add(resolvedPath);
                        batchTypes.add(typeStr);
                    }
                }
            }
            GalleryMediaItem item = selected.get(0);
            openMediaEditorBatch(item.getUri(), item.isVideo() ? "video" : "image", batchUris, batchTypes);
        });
    }

    private String resolveGalleryItemPath(GalleryMediaItem item) {
        if (item == null) return null;
        if (item.getPath() != null && new File(item.getPath()).exists()) {
            return item.getPath();
        }
        if (item.getUri() == null) return null;
        try {
            String ext = item.isVideo() ? ".mp4" : ".jpg";
            File outFile = new File(getCacheDir(), "batch_" + System.currentTimeMillis() + "_" + item.getId() + ext);
            try (java.io.InputStream in = getContentResolver().openInputStream(item.getUri());
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
            }
            return outFile.exists() ? outFile.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void animateShutterPress(boolean pressed) {
        float scale = pressed ? 1.25f : 1.0f;
        shutterButton.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
    }

    private void setupGestures() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    CameraInfo cameraInfo = camera.getCameraInfo();
                    CameraControl cameraControl = camera.getCameraControl();
                    float currentZoom = cameraInfo.getZoomState().getValue() != null
                            ? cameraInfo.getZoomState().getValue().getZoomRatio()
                            : 1.0f;
                    float delta = detector.getScaleFactor();
                    cameraControl.setZoomRatio(currentZoom * delta);
                }
                return true;
            }
        });

        tapGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                focusOnPoint(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                flipLens();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                float dp = getResources().getDisplayMetrics().density;
                if (e1 != null && e2 != null && (e1.getY() - e2.getY()) > 56 * dp
                        && Math.abs(e1.getY() - e2.getY()) > Math.abs(e1.getX() - e2.getX()) * 1.25f) {
                    showFullGallery(true);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float dp = getResources().getDisplayMetrics().density;
                if (e1 != null && e2 != null && (e1.getY() - e2.getY()) > 42 * dp && Math.abs(velocityY) > 80) {
                    showFullGallery(true);
                    return true;
                }
                return false;
            }
        });

        cameraPreviewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            tapGestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void focusOnPoint(float x, float y) {
        if (camera == null) return;
        MeteringPointFactory factory = cameraPreviewView.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
        camera.getCameraControl().startFocusAndMetering(action);

        // Animate focus ring
        viewFocusRing.setX(x - (viewFocusRing.getWidth() / 2f));
        viewFocusRing.setY(y - (viewFocusRing.getHeight() / 2f));
        viewFocusRing.setScaleX(1.4f);
        viewFocusRing.setScaleY(1.4f);
        viewFocusRing.setAlpha(1.0f);
        viewFocusRing.setVisibility(View.VISIBLE);

        viewFocusRing.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(250)
                .withEndAction(() -> viewFocusRing.animate().alpha(0f).setDuration(250).start())
                .start();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Failed to initialize CameraX", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        cameraProvider.unbindAll();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        // 1. Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreviewView.getSurfaceProvider());

        // 2. Image Capture
        imageCapture = new ImageCapture.Builder()
                .setFlashMode(flashMode)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        try {
            if (currentCameraMode == MODE_VIDEO) {
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture);
            } else {
                videoCapture = null;
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            }
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        File photoFile = new File(getExternalCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // White shutter flash scrim
        viewFlashScrim.setVisibility(View.VISIBLE);
        viewFlashScrim.setAlpha(0.9f);
        viewFlashScrim.animate().alpha(0f).setDuration(180).withEndAction(() -> viewFlashScrim.setVisibility(View.GONE)).start();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                if (currentMaskMode != com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE) {
                    try {
                        Bitmap raw = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                        if (raw != null) {
                            Bitmap masked = com.m3.veil.utils.VeilArMaskRenderer.getInstance()
                                    .applyMaskToBitmap(raw, currentMaskMode, 0.0f);
                            FileOutputStream fos = new FileOutputStream(photoFile);
                            masked.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                            fos.flush();
                            fos.close();
                        }
                    } catch (Exception ignored) {}
                }
                Uri photoUri = Uri.fromFile(photoFile);
                openMediaEditor(photoUri, "image");
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Toast.makeText(CameraGalleryActivity.this, "Capture failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showArMaskPickerBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        float dp = getResources().getDisplayMetrics().density;
        int padH = (int) (24 * dp);
        int padV = (int) (16 * dp);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * dp);
        int handleH = (int) (4 * dp);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView title = new TextView(this);
        title.setText("🎭 AR Anonymous Camera Masks");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (4 * dp));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Real-time procedural face masking for 100% anonymous capture.");
        sub.setTextSize(13f);
        sub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        sub.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(sub);

        for (int i = 0; i < com.m3.veil.utils.VeilArMaskRenderer.MASK_NAMES.length; i++) {
            final int mode = i;
            final String name = com.m3.veil.utils.VeilArMaskRenderer.MASK_NAMES[i];
            final String icon = com.m3.veil.utils.VeilArMaskRenderer.MASK_ICONS[i];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
            row.setClickable(true);
            row.setFocusable(true);

            TextView tvIcon = new TextView(this);
            tvIcon.setText(icon);
            tvIcon.setTextSize(20f);
            tvIcon.setPadding(0, 0, (int) (14 * dp), 0);
            row.addView(tvIcon);

            TextView tvName = new TextView(this);
            tvName.setText(name);
            tvName.setTextSize(15.5f);
            tvName.setTextColor(currentMaskMode == mode
                    ? ContextCompat.getColor(this, R.color.veil_accent)
                    : ContextCompat.getColor(this, R.color.textPrimary));
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvName);

            if (currentMaskMode == mode) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextColor(ContextCompat.getColor(this, R.color.veil_accent));
                check.setTextSize(17f);
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                sheet.dismiss();
                currentMaskMode = mode;
                if (arMaskOverlayView != null) {
                    arMaskOverlayView.setMaskMode(currentMaskMode);
                }
                if (btnArMaskToggle != null) {
                    btnArMaskToggle.setText(icon);
                }
                Toast.makeText(this, "AR Mask: " + name, Toast.LENGTH_SHORT).show();
            });

            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void startRecording() {
        if (videoCapture == null) {
            if (cameraProvider != null) {
                int prevMode = currentCameraMode;
                currentCameraMode = MODE_VIDEO;
                bindCameraUseCases();
                currentCameraMode = prevMode;
            }
            if (videoCapture == null) return;
        }

        File videoFile = new File(getExternalCacheDir(), "vid_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(videoFile).build();

        isRecording = true;
        recordingStartTime = SystemClock.elapsedRealtime();
        layoutRecordingTimer.setVisibility(View.VISIBLE);
        tvRecordingTimer.setText("00:00");
        timerHandler.postDelayed(timerRunnable, 500);

        // Turn shutter button red
        shutterButton.setBackgroundResource(R.drawable.bg_shutter_recording);
        shutterInnerCircle.setBackgroundColor(Color.parseColor("#EF4444"));
        if (currentCameraMode == MODE_PHOTO && layoutShutterLock != null) {
            layoutShutterLock.setVisibility(View.VISIBLE);
            if (tvShutterLockText != null) {
                tvShutterLockText.setText("Slide up to lock");
            }
        }

        androidx.camera.video.PendingRecording pendingRecording = videoCapture.getOutput().prepareRecording(this, outputOptions);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording = pendingRecording.withAudioEnabled();
        }

        currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                        if (!finalize.hasError()) {
                            Uri videoUri = Uri.fromFile(videoFile);
                            openMediaEditor(videoUri, "video");
                        } else {
                            Toast.makeText(this, "Recording error: " + finalize.getError(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        timerHandler.removeCallbacks(timerRunnable);
        layoutRecordingTimer.setVisibility(View.GONE);

        isVideoLocked = false;
        if (layoutShutterLock != null) {
            layoutShutterLock.setVisibility(View.GONE);
            if (tvShutterLockText != null) {
                tvShutterLockText.setText("Slide up to lock");
            }
        }
        // Restore shutter button
        shutterButton.setBackgroundResource(R.drawable.bg_shutter_outer);
        if (currentCameraMode == MODE_VIDEO) {
            shutterInnerCircle.setBackgroundResource(R.drawable.bg_shutter_recording);
        } else {
            shutterInnerCircle.setBackgroundResource(R.drawable.bg_shutter_inner);
        }

        if (currentRecording != null) {
            currentRecording.stop();
            currentRecording = null;
        }
    }

    private void openMediaEditor(Uri mediaUri, String mediaType) {
        openMediaEditorBatch(mediaUri, mediaType, null, null);
    }

    private void openMediaEditorBatch(Uri mediaUri, String mediaType, ArrayList<String> batchUris, ArrayList<String> batchTypes) {
        if (mediaUri == null && (batchUris == null || batchUris.isEmpty())) return;
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                camera = null;
            } catch (Throwable ignored) {}
        }
        Intent intent = new Intent(this, MediaEditActivity.class);
        if (mediaUri != null) {
            intent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, mediaUri);
            intent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE, mediaType);
        }
        if (batchUris != null && !batchUris.isEmpty()) {
            intent.putStringArrayListExtra(MediaEditActivity.EXTRA_BATCH_URIS, batchUris);
            intent.putStringArrayListExtra(MediaEditActivity.EXTRA_BATCH_TYPES, batchTypes);
        }
        startActivityForResult(intent, REQ_EDIT_MEDIA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT_MEDIA && resultCode == RESULT_OK && data != null) {
            ArrayList<String> editedBatchPaths = data.getStringArrayListExtra(MediaEditActivity.RESULT_EDITED_BATCH_PATHS);
            ArrayList<String> editedBatchTypes = data.getStringArrayListExtra(MediaEditActivity.RESULT_EDITED_BATCH_TYPES);
            if (editedBatchPaths != null && editedBatchPaths.size() > 1) {
                ArrayList<String> extraPaths = new ArrayList<>(editedBatchPaths.subList(1, editedBatchPaths.size()));
                ArrayList<String> extraTypes = new ArrayList<>();
                for (int i = 1; i < editedBatchPaths.size(); i++) {
                    extraTypes.add((editedBatchTypes != null && i < editedBatchTypes.size()) ? editedBatchTypes.get(i) : "image");
                }
                data.putStringArrayListExtra(RESULT_BATCH_PATHS, extraPaths);
                data.putStringArrayListExtra(RESULT_BATCH_TYPES, extraTypes);
            } else if (!pendingBatchPaths.isEmpty()) {
                data.putStringArrayListExtra(RESULT_BATCH_PATHS, new ArrayList<>(pendingBatchPaths));
                data.putStringArrayListExtra(RESULT_BATCH_TYPES, new ArrayList<>(pendingBatchTypes));
                pendingBatchPaths.clear();
                pendingBatchTypes.clear();
            }
            // Forward result back to ChatActivity / HomeFragment
            setResult(RESULT_OK, data);
            finish();
        }
    }

    private void showFullGallery(boolean show) {
        if (show) {
            if (layoutFullGallery.getVisibility() == View.VISIBLE && layoutFullGallery.getTranslationY() == 0f) {
                return;
            }
            layoutFullGallery.setVisibility(View.VISIBLE);
            float startY = layoutFullGallery.getHeight() > 0
                    ? layoutFullGallery.getHeight()
                    : getResources().getDisplayMetrics().heightPixels;
            layoutFullGallery.setTranslationY(startY);
            layoutFullGallery.animate()
                    .translationY(0f)
                    .setInterpolator(new OvershootInterpolator(0.55f))
                    .setDuration(260)
                    .start();
        } else {
            if (hasCameraPermission()) {
                if (cameraProvider == null) {
                    startCamera();
                } else if (camera == null) {
                    bindCameraUseCases();
                }
            }
            layoutFullGallery.animate()
                    .translationY(layoutFullGallery.getHeight())
                    .setDuration(220)
                    .withEndAction(() -> layoutFullGallery.setVisibility(View.GONE))
                    .start();
        }
    }

    private void showAlbumMenu() {
        if (albumMap.isEmpty()) {
            Toast.makeText(this, "No media albums found", Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu popup = new PopupMenu(this, btnAlbumDropdown);
        popup.getMenu().add(0, 0, 0, "All Media (" + allMediaItems.size() + ")");
        int index = 1;
        for (MediaAlbum album : albumMap.values()) {
            popup.getMenu().add(0, index++, index, album.getName() + " (" + album.getCount() + ")");
        }
        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            int paren = title.indexOf(" (");
            String cleanName = paren != -1 ? title.substring(0, paren) : title;
            tvCurrentAlbumName.setText(cleanName);

            if (item.getItemId() == 0) {
                currentSelectedBucketId = "all";
                filterGalleryByBucket("all");
            } else {
                for (Map.Entry<String, MediaAlbum> entry : albumMap.entrySet()) {
                    if (entry.getValue().getName().equals(cleanName)) {
                        currentSelectedBucketId = entry.getKey();
                        filterGalleryByBucket(entry.getKey());
                        break;
                    }
                }
            }
            return true;
        });
        popup.show();
    }

    private void filterGalleryByBucket(String bucketId) {
        displayedMediaItems.clear();
        if ("all".equals(bucketId)) {
            displayedMediaItems.addAll(allMediaItems);
        } else {
            for (GalleryMediaItem it : allMediaItems) {
                if (bucketId.equals(it.getBucketId())) {
                    displayedMediaItems.add(it);
                }
            }
        }
        fullGalleryAdapter.notifyDataSetChanged();
        if (layoutGalleryEmptyState != null) {
            layoutGalleryEmptyState.setVisibility(displayedMediaItems.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSelectionUI(List<GalleryMediaItem> selectedItems) {
        if (selectedItems.isEmpty()) {
            tvGallerySelectCount.setVisibility(View.GONE);
            layoutFloatingSend.setVisibility(View.GONE);
        } else {
            tvGallerySelectCount.setText(selectedItems.size() + " selected");
            tvGallerySelectCount.setVisibility(View.VISIBLE);
            fabSendSelected.setText("Send (" + selectedItems.size() + ")");
            layoutFloatingSend.setVisibility(View.VISIBLE);
        }
    }

    private void loadMediaLibrary() {
        if (!hasStoragePermission()) {
            if (layoutGalleryEmptyState != null) {
                layoutGalleryEmptyState.setVisibility(View.VISIBLE);
                if (tvGalleryEmptyMessage != null) {
                    tvGalleryEmptyMessage.setText("Storage permission required to view media");
                }
            }
            return;
        }
        galleryLoaderProgressBar.setVisibility(View.VISIBLE);
        if (layoutGalleryEmptyState != null) {
            layoutGalleryEmptyState.setVisibility(View.GONE);
        }
        mediaScanExecutor.execute(() -> {
            List<GalleryMediaItem> queried = new ArrayList<>();
            Map<String, MediaAlbum> albums = new HashMap<>();
            ContentResolver cr = getContentResolver();

            // 1. Photos
            String[] imgCols = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
            };
            try (Cursor c = cr.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imgCols, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
                    int bIdCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                    int bNameCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                    int dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);

                    int count = 0;
                    while (c.moveToNext() && count < 300) {
                        count++;
                        long id = c.getLong(idCol);
                        String path = dataCol != -1 ? c.getString(dataCol) : null;
                        String bucketId = bIdCol != -1 ? c.getString(bIdCol) : "other";
                        String bucketName = bNameCol != -1 ? c.getString(bNameCol) : "Other";
                        long date = dateCol != -1 ? c.getLong(dateCol) : 0;
                        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                        queried.add(new GalleryMediaItem(id, uri, path, false, 0, bucketId, bucketName, date));

                        if (bucketId == null) bucketId = "other";
                        if (bucketName == null) bucketName = "Other";
                        if (!albums.containsKey(bucketId)) {
                            albums.put(bucketId, new MediaAlbum(bucketId, bucketName, uri, 1));
                        } else {
                            albums.get(bucketId).incrementCount();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying images from MediaStore", e);
            }

            // 2. Videos
            String[] vidCols = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED
            };
            try (Cursor c = cr.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    vidCols, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA);
                    int bIdCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                    int bNameCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    int durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED);

                    int count = 0;
                    while (c.moveToNext() && count < 150) {
                        count++;
                        long id = c.getLong(idCol);
                        String path = dataCol != -1 ? c.getString(dataCol) : null;
                        String bucketId = bIdCol != -1 ? c.getString(bIdCol) : "other";
                        String bucketName = bNameCol != -1 ? c.getString(bNameCol) : "Other";
                        long dur = durCol != -1 ? c.getLong(durCol) : 0;
                        long date = dateCol != -1 ? c.getLong(dateCol) : 0;
                        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                        queried.add(new GalleryMediaItem(id, uri, path, true, dur, bucketId, bucketName, date));

                        if (bucketId == null) bucketId = "other";
                        if (bucketName == null) bucketName = "Other";
                        if (!albums.containsKey(bucketId)) {
                            albums.put(bucketId, new MediaAlbum(bucketId, bucketName, uri, 1));
                        } else {
                            albums.get(bucketId).incrementCount();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying videos from MediaStore", e);
            }

            // Sort all by date descending
            Collections.sort(queried, (a, b) -> Long.compare(b.getDateAdded(), a.getDateAdded()));

            List<GalleryMediaItem> recentSub = new ArrayList<>();
            for (int i = 0; i < Math.min(queried.size(), 20); i++) {
                recentSub.add(queried.get(i));
            }

            runOnUiThread(() -> {
                galleryLoaderProgressBar.setVisibility(View.GONE);
                allMediaItems.clear();
                allMediaItems.addAll(queried);

                albumMap.clear();
                albumMap.putAll(albums);

                filterGalleryByBucket(currentSelectedBucketId);

                recentMediaItems.clear();
                recentMediaItems.addAll(recentSub);
                recentMediaAdapter.notifyDataSetChanged();
            });
        });
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkPermissions() {
        return hasCameraPermission() && hasAudioPermission() && hasStoragePermission();
    }

    private void requestPermissions() {
        List<String> list = new ArrayList<>();
        if (!hasCameraPermission()) {
            list.add(Manifest.permission.CAMERA);
        }
        if (!hasAudioPermission()) {
            list.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES);
            list.add(Manifest.permission.READ_MEDIA_VIDEO);
            list.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES);
            list.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        ActivityCompat.requestPermissions(this, list.toArray(new String[0]), PERMISSION_REQ_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            if (hasCameraPermission() && cameraProvider == null) {
                startCamera();
            }
            if (hasStoragePermission()) {
                loadMediaLibrary();
            } else {
                if (layoutGalleryEmptyState != null) {
                    layoutGalleryEmptyState.setVisibility(View.VISIBLE);
                    if (tvGalleryEmptyMessage != null) {
                        tvGalleryEmptyMessage.setText("Storage permission required to view media");
                    }
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (layoutFullGallery.getVisibility() == View.VISIBLE) {
            if (fullGalleryAdapter.getSelectedItems().size() > 0) {
                fullGalleryAdapter.clearSelection();
            } else if (startedDirectlyInGallery) {
                super.onBackPressed();
            } else {
                showFullGallery(false);
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopRecording();
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Throwable ignored) {}
        }
        mediaScanExecutor.shutdown();
        super.onDestroy();
    }

    private void flipLens() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        if (btnFlipLens != null) {
            ObjectAnimator.ofFloat(btnFlipLens, "rotation", 0f, 180f).setDuration(250).start();
        }
        bindCameraUseCases();
    }

    private void setCameraMode(int mode) {
        boolean modeChanged = (currentCameraMode != mode);
        currentCameraMode = mode;
        if (mode == MODE_PHOTO) {
            if (btnModePhoto != null) btnModePhoto.setTextColor(Color.parseColor("#FFCC00"));
            if (btnModeVideo != null) btnModeVideo.setTextColor(Color.parseColor("#88FFFFFF"));
            if (!isRecording && shutterInnerCircle != null) {
                shutterInnerCircle.setBackgroundResource(R.drawable.bg_shutter_inner);
            }
        } else {
            if (btnModeVideo != null) btnModeVideo.setTextColor(Color.parseColor("#FFCC00"));
            if (btnModePhoto != null) btnModePhoto.setTextColor(Color.parseColor("#88FFFFFF"));
            if (!isRecording && shutterInnerCircle != null) {
                shutterInnerCircle.setBackgroundResource(R.drawable.bg_shutter_recording);
            }
        }
        if (modeChanged && !isRecording && cameraProvider != null) {
            bindCameraUseCases();
        }
    }

    private void setZoomRatio(float ratio, TextView activePill) {
        if (camera == null) return;
        CameraInfo info = camera.getCameraInfo();
        if (info.getZoomState().getValue() == null) return;
        float minZoom = info.getZoomState().getValue().getMinZoomRatio();
        float maxZoom = info.getZoomState().getValue().getMaxZoomRatio();
        float target = Math.max(minZoom, Math.min(ratio, maxZoom));
        camera.getCameraControl().setZoomRatio(target);

        updateZoomPillUI(activePill);
    }

    private void updateZoomPillUI(TextView activePill) {
        TextView[] pills = new TextView[]{btnZoom05x, btnZoom1x, btnZoom2x, btnZoom5x};
        for (TextView pill : pills) {
            if (pill == null) continue;
            if (pill == activePill) {
                pill.setBackgroundResource(R.drawable.bg_zoom_pill_active);
                pill.setTextColor(Color.BLACK);
            } else {
                pill.setBackgroundResource(R.drawable.bg_zoom_pill_inactive);
                pill.setTextColor(Color.WHITE);
            }
        }
    }

    private void lockRecording() {
        isVideoLocked = true;
        if (layoutShutterLock != null) {
            layoutShutterLock.setVisibility(View.VISIBLE);
            if (tvShutterLockText != null) {
                tvShutterLockText.setText("Recording Locked • Tap to Stop");
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (layoutFullGallery != null && layoutFullGallery.getVisibility() == View.VISIBLE) {
                return super.onKeyDown(keyCode, event);
            }
            if (currentCameraMode == MODE_VIDEO) {
                if (isRecording) {
                    stopRecording();
                } else {
                    startRecording();
                }
            } else {
                takePhoto();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
