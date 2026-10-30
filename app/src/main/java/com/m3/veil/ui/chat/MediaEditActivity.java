package com.m3.veil.ui.chat;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.m3.veil.R;
import com.m3.veil.ui.views.VeilVideoTrimmerFilmstripView;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.utils.VideoTrimUtil;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ja.burhanrashid52.photoeditor.PhotoEditor;
import ja.burhanrashid52.photoeditor.PhotoEditorView;

public class MediaEditActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_URI = "extra_media_uri";
    public static final String EXTRA_MEDIA_TYPE = "extra_media_type";
    public static final String EXTRA_BATCH_URIS = "extra_batch_uris";
    public static final String EXTRA_BATCH_TYPES = "extra_batch_types";
    public static final String EXTRA_IS_STATUS_MODE = "extra_is_status_mode";
    public static final String EXTRA_RECIPIENT_NAME = "extra_recipient_name";
    public static final String EXTRA_IS_STICKER_MODE = "extra_is_sticker_mode";
    public static final String EXTRA_IS_GIF_MODE = "extra_is_gif_mode";

    public static final String RESULT_EDITED_FILE_PATH = "result_edited_file_path";
    public static final String RESULT_MEDIA_TYPE = "result_media_type";
    public static final String RESULT_CAPTION = "result_caption";
    public static final String RESULT_IS_VIEW_ONCE = "result_is_view_once";
    public static final String RESULT_IS_STATUS_POST = "result_is_status_post";
    public static final String RESULT_EDITED_BATCH_PATHS = "result_edited_batch_paths";
    public static final String RESULT_EDITED_BATCH_TYPES = "result_edited_batch_types";

    private static final int REQ_ADD_MORE_MEDIA = 4091;

    private static class BatchEditItem {
        Uri uri;
        String type; // "image", "video", or "gif"
        String savedPath;
        long trimStartMs = 0;
        long trimEndMs = 0;
        long totalDurationMs = 0;
        boolean isMuted = false;
        boolean sendAsGif = false;
        int rotationDegrees = 0;
        boolean flipHorizontal = false;
        float playbackSpeed = 1.0f;
        int filterIndex = 0;

        BatchEditItem(Uri uri, String type) {
            this.uri = uri;
            this.type = (type != null && type.startsWith("video")) ? "video" : "image";
        }
    }

    private final List<BatchEditItem> batchItems = new ArrayList<>();
    private int currentBatchIndex = 0;

    private PhotoEditorView photoEditorView;
    private PhotoEditor photoEditor;
    private VideoView videoView;
    private android.media.MediaPlayer activeVideoMediaPlayer;
    private EditText captionEditText;
    private HorizontalScrollView editBatchThumbnailScroll;
    private LinearLayout editBatchThumbnailStrip;
    private TextView tvEditBatchCounter;
    private boolean isViewOnce = false;

    private FrameLayout videoContainer;
    private View drawingHeaderBar;
    private ImageView btnDrawUndo;
    private TextView btnMuteAudioChip;
    private TextView btnVideoSpeedChip;
    private TextView btnHdQualityChip;
    private TextView btnColorFilterChip;
    private View videoColorFilterOverlay;
    private boolean isHdQuality = true;
    private float currentVideoSpeed = 1.0f;
    private int currentVideoRotation = 0;
    private boolean currentVideoFlipH = false;
    private boolean sendVideoAsGif = false;
    private int currentFilterIndex = 0;
    private static final String[] FILTER_NAMES = new String[]{
            "Original", "Vivid", "Warm", "Cool", "Noir B&W", "Cyber"
    };
    private static final int[] FILTER_TINT_COLORS = new int[]{
            Color.TRANSPARENT,
            0x22FF4081,
            0x28FF9800,
            0x2800B0FF,
            0x55000000,
            0x2E7C4DFF
    };

    // Video Trimmer & Filmstrip members
    private View videoTrimmerContainer;
    private VeilVideoTrimmerFilmstripView videoFilmstripTrimmer;
    private TextView btnVideoPlayPauseToggle;
    private TextView btnVideoGifModeToggle;
    private ImageView btnMidPlayPause;
    private ImageView btnToolbarGifMode;
    private TextView tvTrimStart, tvTrimEnd, tvTrimDuration;
    private long trimStartMs = 0;
    private long trimEndMs = 0;
    private long totalDurationMs = 0;
    private final Handler videoLoopHandler = new Handler(Looper.getMainLooper());
    private final Runnable videoLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && videoView.isPlaying()) {
                int currentPos = videoView.getCurrentPosition();
                if (trimEndMs > 0 && currentPos >= trimEndMs) {
                    videoView.seekTo((int) trimStartMs);
                }
            }
            videoLoopHandler.postDelayed(this, 100);
        }
    };

    private String mediaType;
    private Uri mediaUri;
    private GestureDetector horizontalSwipeDetector;

    private boolean isStatusMode = false;
    private boolean isStickerMode = false;
    private boolean isGifMode = false;
    private String recipientLabel = "Status (Contacts)";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_media_edit);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            float dp = getResources().getDisplayMetrics().density;
            View topBar = findViewById(R.id.topHeaderBar);
            if (topBar != null) {
                topBar.setPadding(
                        Math.round(12 * dp) + systemBars.left,
                        systemBars.top + Math.round(8 * dp),
                        Math.round(12 * dp) + systemBars.right,
                        Math.round(8 * dp));
            }
            View drawBar = findViewById(R.id.drawingHeaderBar);
            if (drawBar != null) {
                drawBar.setPadding(
                        Math.round(12 * dp) + systemBars.left,
                        systemBars.top + Math.round(8 * dp),
                        Math.round(12 * dp) + systemBars.right,
                        Math.round(8 * dp));
            }
            View bottomBar = findViewById(R.id.bottomBar);
            if (bottomBar != null) {
                bottomBar.setPadding(
                        Math.round(12 * dp) + systemBars.left,
                        Math.round(10 * dp),
                        Math.round(12 * dp) + systemBars.right,
                        systemBars.bottom + Math.round(14 * dp));
            }
            return insets;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentState != UIState.BASE) {
                    updateState(UIState.BASE);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        extractMediaBatchFromIntent();

        if (batchItems.isEmpty() || mediaUri == null || mediaType == null) {
            Toast.makeText(this, "Invalid media", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        configureAdaptiveMediaSystemBarsAndTheme();

        View mainView = findViewById(android.R.id.content);
        if (mainView != null) {
            mainView.setScaleX(0.96f);
            mainView.setScaleY(0.96f);
            mainView.setAlpha(0f);
            mainView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(210)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentState != UIState.BASE) {
            updateState(UIState.BASE);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean isNight = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        try {
            android.view.Window window = getWindow();
            int surfaceColor = isNight ? Color.parseColor("#0B1017") : Color.parseColor("#F8FAFC");
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surfaceColor));
            window.setStatusBarColor(surfaceColor);
            window.setNavigationBarColor(surfaceColor);
            androidx.core.view.WindowInsetsControllerCompat insetsController =
                    androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView());
            if (insetsController != null) {
                insetsController.setAppearanceLightStatusBars(!isNight);
                insetsController.setAppearanceLightNavigationBars(!isNight);
            }
        } catch (Throwable ignored) {}
        applyAdaptiveThemeElements(isNight);
    }

    private void configureAdaptiveMediaSystemBarsAndTheme() {
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        try {
            android.view.Window window = getWindow();
            int surfaceColor = isNight ? Color.parseColor("#0B1017") : Color.parseColor("#F8FAFC");
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surfaceColor));
            window.setStatusBarColor(surfaceColor);
            window.setNavigationBarColor(surfaceColor);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
            androidx.core.view.WindowInsetsControllerCompat insetsController =
                    androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView());
            if (insetsController != null) {
                insetsController.setAppearanceLightStatusBars(!isNight);
                insetsController.setAppearanceLightNavigationBars(!isNight);
            }
        } catch (Throwable ignored) {}

        photoEditorView = findViewById(R.id.photoEditorView);
        videoContainer = findViewById(R.id.videoContainer);
        videoView = findViewById(R.id.videoView);
        btnMidPlayPause = findViewById(R.id.btnMidPlayPause);
        btnToolbarGifMode = findViewById(R.id.btnToolbarGifMode);
        if (btnMidPlayPause != null) {
            UiAnimator.attachSpringPress(btnMidPlayPause);
            btnMidPlayPause.setOnClickListener(v -> toggleVideoPlayback());
        }
        if (videoContainer != null) {
            videoContainer.setOnClickListener(v -> toggleVideoPlayback());
        }
        if (btnToolbarGifMode != null) {
            UiAnimator.attachSpringPress(btnToolbarGifMode);
            btnToolbarGifMode.setOnClickListener(v -> {
                sendVideoAsGif = !sendVideoAsGif;
                if (sendVideoAsGif) {
                    isVideoMuted = true;
                    applyLiveVideoMuteState();
                }
                updateToolbarGifModeUi();
                Toast.makeText(this, sendVideoAsGif ? "🎬 Looping GIF mode active" : "🎥 Standard Video mode active", Toast.LENGTH_SHORT).show();
            });
        }
        captionEditText = findViewById(R.id.captionEditText);
        editBatchThumbnailScroll = findViewById(R.id.editBatchThumbnailScroll);
        editBatchThumbnailStrip = findViewById(R.id.editBatchThumbnailStrip);
        tvEditBatchCounter = findViewById(R.id.tvEditBatchCounter);

        View btnCloseView = findViewById(R.id.btnClose);
        View btnSendView = findViewById(R.id.btnSend);
        View btnCropView = findViewById(R.id.btnCrop);
        View btnEmojiView = findViewById(R.id.btnEmoji);
        View btnTextView = findViewById(R.id.btnText);
        View btnDrawView = findViewById(R.id.btnDraw);
        View btnUndoView = findViewById(R.id.btnUndo);

        if (btnCloseView != null) {
            UiAnimator.attachSpringPress(btnCloseView);
            btnCloseView.setOnClickListener(v -> finish());
        }
        if (btnSendView != null) UiAnimator.attachSpringPress(btnSendView);
        if (btnCropView != null) UiAnimator.attachSpringPress(btnCropView);
        if (btnEmojiView != null) UiAnimator.attachSpringPress(btnEmojiView);
        if (btnTextView != null) UiAnimator.attachSpringPress(btnTextView);
        if (btnDrawView != null) UiAnimator.attachSpringPress(btnDrawView);
        if (btnUndoView != null) UiAnimator.attachSpringPress(btnUndoView);

        applyAdaptiveThemeElements(isNight);

        setupViewsForState();
        initToolbarDynamicChips();
        setupHorizontalSwipeNavigation();
        buildBatchThumbnailStrip();
        bindActiveBatchItem();

        View btnViewOnce = findViewById(R.id.btnViewOnce);
        TextView tvViewOnceBadge = findViewById(R.id.tvViewOnceBadge);
        if (btnViewOnce != null) {
            UiAnimator.attachSpringPress(btnViewOnce);
            btnViewOnce.setOnClickListener(v -> {
                isViewOnce = !isViewOnce;
                if (isViewOnce) {
                    if (tvViewOnceBadge != null) {
                        tvViewOnceBadge.setBackgroundResource(R.drawable.bg_view_once_ring_active);
                        tvViewOnceBadge.setTextColor(Color.WHITE);
                    }
                    String typeName = mediaType != null && mediaType.startsWith("video") ? "Video" : "Photo";
                    Toast.makeText(this, "⚡ " + typeName + " set to view once", Toast.LENGTH_SHORT).show();
                } else {
                    if (tvViewOnceBadge != null) {
                        tvViewOnceBadge.setBackgroundResource(R.drawable.bg_view_once_ring);
                        tvViewOnceBadge.setTextColor(Color.parseColor("#00A884"));
                    }
                    String typeName = mediaType != null && mediaType.startsWith("video") ? "Video" : "Photo";
                    Toast.makeText(this, typeName + " set to standard", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnSendView != null) {
            btnSendView.setOnClickListener(v -> saveAndSend());
        }

        if (btnCropView != null) {
            btnCropView.setOnClickListener(v -> {
                if (mediaUri != null && mediaType != null && mediaType.startsWith("video")) {
                    showVideoTransformAndCropSheet();
                } else if (mediaUri != null && mediaType != null && mediaType.startsWith("image") && photoEditor != null) {
                    photoEditorView.setDrawingCacheEnabled(true);
                    Bitmap bitmap = Bitmap.createBitmap(photoEditorView.getDrawingCache());
                    photoEditorView.setDrawingCacheEnabled(false);

                    File tempBake = new File(getExternalCacheDir(), "pre_crop_" + System.currentTimeMillis() + ".jpg");
                    try {
                        FileOutputStream out = new FileOutputStream(tempBake);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        out.flush();
                        out.close();
                        launchUCrop(Uri.fromFile(tempBake));
                    } catch (Exception e) {
                        e.printStackTrace();
                        launchUCrop(mediaUri);
                    }
                }
            });
        }

        if (btnEmojiView != null) {
            btnEmojiView.setOnClickListener(v -> {
                EmojiStickerBottomSheet bottomSheet = new EmojiStickerBottomSheet();
                bottomSheet.setOnEmojiSelectedListener(emoji -> {
                    if (photoEditor != null) {
                        photoEditor.addEmoji(emoji);
                    }
                });
                bottomSheet.show(getSupportFragmentManager(), "EmojiBottomSheet");
            });
        }

        if (btnTextView != null) {
            btnTextView.setOnClickListener(v -> {
                updateState(UIState.TEXT);
                TextEditorDialogFragment dialog = TextEditorDialogFragment.show(this, "", currentSelectedColor);
                dialog.setOnTextEditorListener((newText, colorCode) -> {
                    if (photoEditor != null && newText != null && !newText.trim().isEmpty()) {
                        photoEditor.addText(newText, colorCode);
                    }
                    updateState(UIState.BASE);
                });
            });
        }

        if (btnDrawView != null) {
            btnDrawView.setOnClickListener(v -> updateState(UIState.DRAWING));
        }
        if (btnUndoView != null) {
            btnUndoView.setOnClickListener(v -> {
                if (photoEditor != null) {
                    photoEditor.undo();
                }
            });
        }

        View btnAddMoreMedia = findViewById(R.id.btnAddMoreMedia);
        if (btnAddMoreMedia != null) {
            UiAnimator.attachSpringPress(btnAddMoreMedia);
            btnAddMoreMedia.setOnClickListener(v -> {
                Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
                pickIntent.setType("*/*");
                pickIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                pickIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(pickIntent, "Add media"), REQ_ADD_MORE_MEDIA);
            });
        }

        View recipientStatusPill = findViewById(R.id.recipientStatusPill);
        TextView tvRecipientStatusLabel = findViewById(R.id.tvRecipientStatusLabel);
        if (tvRecipientStatusLabel != null) {
            tvRecipientStatusLabel.setText(recipientLabel);
        }
        if (recipientStatusPill != null) {
            recipientStatusPill.setOnClickListener(v -> {
                String[] choices = new String[]{"Status (Contacts)", "Status (Close Friends)", recipientLabel != null && !recipientLabel.startsWith("Status") ? recipientLabel : "Current Chat"};
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Share audience")
                        .setItems(choices, (d, which) -> {
                            recipientLabel = choices[which];
                            isStatusMode = recipientLabel.startsWith("Status");
                            if (tvRecipientStatusLabel != null) {
                                tvRecipientStatusLabel.setText(recipientLabel);
                            }
                        })
                        .show();
            });
        }
        View btnMentionTag = findViewById(R.id.btnMentionTag);
        if (btnMentionTag != null) {
            btnMentionTag.setOnClickListener(v -> {
                if (captionEditText != null) {
                    int pos = Math.max(0, captionEditText.getSelectionStart());
                    captionEditText.getText().insert(pos, "@");
                    captionEditText.requestFocus();
                }
            });
        }
    }

    private Uri parseFlexibleUri(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("/")) {
            return Uri.fromFile(new File(trimmed));
        }
        return Uri.parse(trimmed);
    }

    private String inferMediaType(Uri uri, String explicitType) {
        if (explicitType != null && !explicitType.trim().isEmpty()) {
            return explicitType.toLowerCase(java.util.Locale.ROOT).startsWith("video") ? "video" : "image";
        }
        if (uri != null) {
            String s = uri.toString().toLowerCase(java.util.Locale.ROOT);
            if (s.endsWith(".mp4") || s.endsWith(".mkv") || s.endsWith(".mov") || s.endsWith(".webm") || s.contains("video")) {
                return "video";
            }
            try {
                String mime = getContentResolver().getType(uri);
                if (mime != null && mime.toLowerCase(java.util.Locale.ROOT).startsWith("video")) {
                    return "video";
                }
            } catch (Throwable ignored) {}
        }
        return "image";
    }

    private void extractMediaBatchFromIntent() {
        batchItems.clear();
        Intent intent = getIntent();
        if (intent == null) return;

        isStatusMode = intent.getBooleanExtra(EXTRA_IS_STATUS_MODE, false);
        isStickerMode = intent.getBooleanExtra(EXTRA_IS_STICKER_MODE, false);
        isGifMode = intent.getBooleanExtra(EXTRA_IS_GIF_MODE, false)
                || "gif".equalsIgnoreCase(intent.getStringExtra(EXTRA_MEDIA_TYPE));
        String customRecipient = intent.getStringExtra(EXTRA_RECIPIENT_NAME);
        if (customRecipient != null && !customRecipient.trim().isEmpty()) {
            recipientLabel = customRecipient;
        } else {
            recipientLabel = "Status (Contacts)";
        }

        ArrayList<String> rawTypes = intent.getStringArrayListExtra(EXTRA_BATCH_TYPES);
        Bundle extras = intent.getExtras();
        Object rawBatchObj = extras != null ? extras.get(EXTRA_BATCH_URIS) : null;
        if (rawBatchObj instanceof List<?>) {
            List<?> rawList = (List<?>) rawBatchObj;
            for (int i = 0; i < rawList.size(); i++) {
                Object elem = rawList.get(i);
                Uri u = null;
                if (elem instanceof Uri) {
                    u = (Uri) elem;
                } else if (elem instanceof String) {
                    u = parseFlexibleUri((String) elem);
                }
                if (u == null) continue;
                String t = (rawTypes != null && i < rawTypes.size()) ? rawTypes.get(i) : inferMediaType(u, null);
                batchItems.add(new BatchEditItem(u, t));
            }
        }

        if (batchItems.isEmpty()) {
            Uri singleUri = null;
            Object rawSingle = extras != null ? extras.get(EXTRA_MEDIA_URI) : null;
            if (rawSingle instanceof Uri) {
                singleUri = (Uri) rawSingle;
            } else if (rawSingle instanceof String) {
                singleUri = parseFlexibleUri((String) rawSingle);
            }
            if (singleUri == null) {
                singleUri = intent.getData();
            }
            String singleType = intent.getStringExtra(EXTRA_MEDIA_TYPE);
            if (singleUri != null) {
                batchItems.add(new BatchEditItem(singleUri, inferMediaType(singleUri, singleType)));
            }
        }
        currentBatchIndex = 0;
        if (!batchItems.isEmpty()) {
            mediaUri = batchItems.get(0).uri;
            mediaType = batchItems.get(0).type;
        }
    }

    private boolean isVideoMuted = false;

    private void applyAdaptiveThemeElements(boolean isNight) {
        float dp = getResources().getDisplayMetrics().density;

        View root = findViewById(R.id.mediaEditRoot);
        if (root != null) {
            root.setBackgroundColor(isNight ? Color.parseColor("#0B1017") : Color.parseColor("#F1F5F9"));
        }

        View topBar = findViewById(R.id.topHeaderBar);
        if (topBar != null) {
            topBar.setBackgroundColor(isNight ? Color.parseColor("#CC0B1017") : Color.parseColor("#F2F8FAFC"));
        }

        int iconTint = isNight ? Color.WHITE : Color.parseColor("#0F172A");
        int circleBgColor = isNight ? 0x33FFFFFF : 0x1F0F172A;

        ImageView btnCloseView = findViewById(R.id.btnClose);
        ImageView btnUndoView = findViewById(R.id.btnUndo);
        ImageView btnCropView = findViewById(R.id.btnCrop);
        ImageView btnEmojiView = findViewById(R.id.btnEmoji);
        ImageView btnTextView = findViewById(R.id.btnText);
        ImageView btnDrawView = findViewById(R.id.btnDraw);

        ImageView[] toolButtons = new ImageView[]{btnCloseView, btnUndoView, btnCropView, btnEmojiView, btnTextView, btnDrawView};
        for (ImageView iv : toolButtons) {
            if (iv != null) {
                iv.setColorFilter(iconTint);
                GradientDrawable cBg = new GradientDrawable();
                cBg.setShape(GradientDrawable.OVAL);
                cBg.setColor(circleBgColor);
                iv.setBackground(cBg);
            }
        }

        View bottomBar = findViewById(R.id.bottomBar);
        if (bottomBar != null) {
            bottomBar.setBackgroundColor(isNight ? Color.parseColor("#D90B1017") : Color.parseColor("#F2F8FAFC"));
        }

        View captionWrap = findViewById(R.id.captionPillWrap);
        if (captionWrap != null) {
            GradientDrawable cBg = new GradientDrawable();
            cBg.setCornerRadius(24 * dp);
            if (isNight) {
                cBg.setColor(Color.parseColor("#1E293B"));
                cBg.setStroke(Math.max(1, Math.round(dp)), Color.parseColor("#334155"));
            } else {
                cBg.setColor(Color.parseColor("#FFFFFF"));
                cBg.setStroke(Math.max(1, Math.round(dp)), Color.parseColor("#CBD5E1"));
            }
            captionWrap.setBackground(cBg);
        }

        EditText captionInput = findViewById(R.id.captionEditText);
        if (captionInput != null) {
            captionInput.setTextColor(isNight ? Color.WHITE : Color.parseColor("#0F172A"));
            captionInput.setHintTextColor(isNight ? 0x99FFFFFF : Color.parseColor("#94A3B8"));
        }

        View recipientPill = findViewById(R.id.recipientStatusPill);
        if (recipientPill != null) {
            GradientDrawable rBg = new GradientDrawable();
            rBg.setCornerRadius(18 * dp);
            if (isNight) {
                rBg.setColor(Color.parseColor("#1E293B"));
                rBg.setStroke(Math.max(1, Math.round(dp)), Color.parseColor("#334155"));
            } else {
                rBg.setColor(Color.parseColor("#E2E8F0"));
                rBg.setStroke(Math.max(1, Math.round(dp)), Color.parseColor("#CBD5E1"));
            }
            recipientPill.setBackground(rBg);
        }

        TextView tvRecipLabel = findViewById(R.id.tvRecipientStatusLabel);
        if (tvRecipLabel != null) {
            tvRecipLabel.setTextColor(isNight ? Color.WHITE : Color.parseColor("#0F172A"));
        }

        ImageView ivRecipIcon = findViewById(R.id.ivRecipientStatusIcon);
        if (ivRecipIcon != null) {
            ivRecipIcon.setColorFilter(isNight ? Color.parseColor("#CFD8DC") : Color.parseColor("#64748B"));
        }

        ImageView ivAddMore = findViewById(R.id.btnAddMoreMedia);
        if (ivAddMore != null) {
            ivAddMore.setColorFilter(isNight ? Color.parseColor("#CFD8DC") : Color.parseColor("#64748B"));
        }

        TextView tvMention = findViewById(R.id.btnMentionTag);
        if (tvMention != null) {
            tvMention.setTextColor(isNight ? Color.parseColor("#CFD8DC") : Color.parseColor("#64748B"));
        }

        if (btnHdQualityChip != null) updateChipTheme(btnHdQualityChip, isHdQuality);
        if (btnColorFilterChip != null) updateChipTheme(btnColorFilterChip, currentFilterIndex != 0);
        if (btnVideoSpeedChip != null) updateChipTheme(btnVideoSpeedChip, currentVideoSpeed != 1.0f);
        if (btnMuteAudioChip != null) updateChipTheme(btnMuteAudioChip, isVideoMuted);
        if (tvEditBatchCounter != null) updateChipTheme(tvEditBatchCounter, false);
    }

    private void updateChipTheme(TextView chip, boolean isSelected) {
        if (chip == null) return;
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        float dp = getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * dp);
        if (isSelected) {
            // Selected active state: vibrant royal indigo pill with crisp white text
            bg.setColor(Color.parseColor("#4F46E5"));
            bg.setStroke(Math.max(1, Math.round(1f * dp)), Color.parseColor("#818CF8"));
            chip.setTextColor(Color.WHITE);
        } else {
            if (isNight) {
                // Dark mode: dark slate pill with light text
                bg.setColor(Color.parseColor("#1E293B"));
                bg.setStroke(Math.max(1, Math.round(1f * dp)), Color.parseColor("#334155"));
                chip.setTextColor(Color.parseColor("#F8FAFC"));
            } else {
                // Light mode: clean light slate pill with dark high-contrast text
                bg.setColor(Color.parseColor("#E2E8F0"));
                bg.setStroke(Math.max(1, Math.round(1f * dp)), Color.parseColor("#CBD5E1"));
                chip.setTextColor(Color.parseColor("#0F172A"));
            }
        }
        chip.setBackground(bg);
    }

    private TextView buildToolbarChip(String label, float dp) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12f);
        chip.setTypeface(null, Typeface.BOLD);
        updateChipTheme(chip, false);
        chip.setPadding((int) (11 * dp), (int) (6 * dp), (int) (11 * dp), (int) (6 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * dp);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void initToolbarDynamicChips() {
        if (!(topToolbar instanceof ViewGroup)) return;
        ViewGroup tb = (ViewGroup) topToolbar;
        float dp = getResources().getDisplayMetrics().density;

        // 1. HD / Standard Quality Chip
        btnHdQualityChip = buildToolbarChip("HD", dp);
        updateChipTheme(btnHdQualityChip, isHdQuality);
        btnHdQualityChip.setOnClickListener(v -> {
            isHdQuality = !isHdQuality;
            btnHdQualityChip.setText(isHdQuality ? "HD" : "SD");
            updateChipTheme(btnHdQualityChip, isHdQuality);
            updateTrimDurationAndSizeLabel();
            Toast.makeText(this, isHdQuality ? "HD High-Definition Quality enabled" : "Standard Data-Saver Quality enabled", Toast.LENGTH_SHORT).show();
        });
        tb.addView(btnHdQualityChip, 0);

        // Move GIF button directly next to HD/SD quality button
        if (btnToolbarGifMode != null) {
            tb.removeView(btnToolbarGifMode);
            LinearLayout.LayoutParams gifLp = new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp));
            gifLp.setMarginEnd((int) (8 * dp));
            gifLp.gravity = Gravity.CENTER_VERTICAL;
            btnToolbarGifMode.setLayoutParams(gifLp);
            tb.addView(btnToolbarGifMode, 1);
        }

        // 2. Color Filter Cycling Chip
        btnColorFilterChip = buildToolbarChip("✨ Filter", dp);
        updateChipTheme(btnColorFilterChip, currentFilterIndex != 0);
        btnColorFilterChip.setOnClickListener(v -> {
            currentFilterIndex = (currentFilterIndex + 1) % FILTER_NAMES.length;
            applyActiveColorFilter();
            updateToolbarGifModeUi();
            Toast.makeText(this, "Filter: " + FILTER_NAMES[currentFilterIndex], Toast.LENGTH_SHORT).show();
        });
        int filterIdx = btnToolbarGifMode != null ? (tb.indexOfChild(btnToolbarGifMode) + 1) : 1;
        tb.addView(btnColorFilterChip, filterIdx);

        // 3. Real-Time Audio Mute Chip (placed directly next to Filter button!)
        btnMuteAudioChip = buildToolbarChip("🔊 Audio", dp);
        updateChipTheme(btnMuteAudioChip, isVideoMuted);
        btnMuteAudioChip.setOnClickListener(v -> {
            isVideoMuted = !isVideoMuted;
            applyLiveVideoMuteState();
            Toast.makeText(this, isVideoMuted ? "🔇 Video audio muted (will send silent video)" : "🔊 Video audio enabled", Toast.LENGTH_SHORT).show();
        });
        tb.addView(btnMuteAudioChip, tb.indexOfChild(btnColorFilterChip) + 1);

        // 4. Video Playback Speed Chip (0.5x / 1.0x / 1.5x / 2.0x)
        btnVideoSpeedChip = buildToolbarChip("1.0x", dp);
        updateChipTheme(btnVideoSpeedChip, currentVideoSpeed != 1.0f);
        btnVideoSpeedChip.setOnClickListener(v -> {
            if (currentVideoSpeed == 1.0f) currentVideoSpeed = 1.5f;
            else if (currentVideoSpeed == 1.5f) currentVideoSpeed = 2.0f;
            else if (currentVideoSpeed == 2.0f) currentVideoSpeed = 0.5f;
            else currentVideoSpeed = 1.0f;
            btnVideoSpeedChip.setText(String.format(java.util.Locale.US, "%.1fx", currentVideoSpeed));
            updateChipTheme(btnVideoSpeedChip, currentVideoSpeed != 1.0f);
            applyLiveVideoPlaybackSpeed();
        });
        tb.addView(btnVideoSpeedChip, tb.indexOfChild(btnMuteAudioChip) + 1);
    }

    private void applyLiveVideoMuteState() {
        if (btnMuteAudioChip != null) {
            btnMuteAudioChip.setText(isVideoMuted ? "🔇 Muted" : "🔊 Audio");
            updateChipTheme(btnMuteAudioChip, isVideoMuted);
        }
        if (activeVideoMediaPlayer != null) {
            try {
                float vol = isVideoMuted ? 0f : 1f;
                activeVideoMediaPlayer.setVolume(vol, vol);
            } catch (Exception ignored) {}
        }
        if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
            batchItems.get(currentBatchIndex).isMuted = isVideoMuted;
        }
        updateTrimDurationAndSizeLabel();
    }

    private void applyLiveVideoPlaybackSpeed() {
        if (activeVideoMediaPlayer != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                boolean wasPlaying = videoView != null && videoView.isPlaying();
                activeVideoMediaPlayer.setPlaybackParams(activeVideoMediaPlayer.getPlaybackParams().setSpeed(currentVideoSpeed));
                if (!wasPlaying && videoView != null) {
                    videoView.pause();
                }
            } catch (Exception ignored) {}
        }
        if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
            batchItems.get(currentBatchIndex).playbackSpeed = currentVideoSpeed;
        }
    }

    private void applyActiveColorFilter() {
        if (btnColorFilterChip != null) {
            btnColorFilterChip.setText(currentFilterIndex == 0 ? "✨ Filter" : ("✨ " + FILTER_NAMES[currentFilterIndex]));
            updateChipTheme(btnColorFilterChip, currentFilterIndex != 0);
        }
        int tint = FILTER_TINT_COLORS[currentFilterIndex % FILTER_TINT_COLORS.length];
        if (photoEditorView != null && photoEditorView.getSource() != null) {
            if (currentFilterIndex == 0) {
                photoEditorView.getSource().clearColorFilter();
            } else {
                photoEditorView.getSource().setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
        }
        if (videoColorFilterOverlay == null && photoEditorView != null && photoEditorView.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) photoEditorView.getParent();
            videoColorFilterOverlay = new View(this);
            videoColorFilterOverlay.setClickable(false);
            videoColorFilterOverlay.setFocusable(false);
            videoColorFilterOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            parent.addView(videoColorFilterOverlay, parent.indexOfChild(photoEditorView));
        }
        if (videoColorFilterOverlay != null) {
            videoColorFilterOverlay.setBackgroundColor(tint);
            videoColorFilterOverlay.setVisibility(currentFilterIndex == 0 ? View.GONE : View.VISIBLE);
        }
        if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
            batchItems.get(currentBatchIndex).filterIndex = currentFilterIndex;
        }
    }

    /**
     * Interactive Video Rotate / Flip / Aspect-Ratio & Frame-Crop Sheet when tapping Crop on a video.
     */
    private void showVideoTransformAndCropSheet() {
        final float dp = getResources().getDisplayMetrics().density;
        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2C34"));
        bg.setCornerRadii(new float[]{22 * dp, 22 * dp, 22 * dp, 22 * dp, 0, 0, 0, 0});
        root.setBackground(bg);
        int pad = Math.round(20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Video Crop, Rotate & Aspect Ratio");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16.5f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, Math.round(14 * dp));
        root.addView(title);

        // Row 1: Rotate 90° | Flip Horizontal | Reset Transform
        LinearLayout transformRow = new LinearLayout(this);
        transformRow.setOrientation(LinearLayout.HORIZONTAL);
        transformRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = Math.round(12 * dp);
        transformRow.setLayoutParams(rLp);

        String[] actions = new String[]{"↻ Rotate 90°", "⇄ Flip Mirror", "↺ Reset"};
        for (int i = 0; i < actions.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, Math.round(42 * dp), 1f);
            if (i > 0) bLp.leftMargin = Math.round(8 * dp);
            btn.setLayoutParams(bLp);
            btn.setGravity(Gravity.CENTER);
            btn.setText(actions[i]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(13f);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setBackgroundResource(R.drawable.bg_filter_chip);
            btn.setOnClickListener(v -> {
                if (idx == 0) {
                    currentVideoRotation = (currentVideoRotation + 90) % 360;
                    if (videoView != null) {
                        videoView.setRotation(currentVideoRotation);
                        if (activeVideoMediaPlayer != null) {
                            fitVideoViewToContainer(activeVideoMediaPlayer.getVideoWidth(), activeVideoMediaPlayer.getVideoHeight());
                        }
                    }
                    Toast.makeText(this, "Video rotated to " + currentVideoRotation + "°", Toast.LENGTH_SHORT).show();
                } else if (idx == 1) {
                    currentVideoFlipH = !currentVideoFlipH;
                    if (videoView != null) videoView.setScaleX(currentVideoFlipH ? -1f : 1f);
                    Toast.makeText(this, currentVideoFlipH ? "Mirrored horizontally" : "Normal orientation", Toast.LENGTH_SHORT).show();
                } else {
                    currentVideoRotation = 0;
                    currentVideoFlipH = false;
                    if (videoView != null) {
                        videoView.setRotation(0f);
                        videoView.setScaleX(1f);
                        videoView.setScaleY(1f);
                        if (activeVideoMediaPlayer != null) {
                            fitVideoViewToContainer(activeVideoMediaPlayer.getVideoWidth(), activeVideoMediaPlayer.getVideoHeight());
                        }
                    }
                }
                if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
                    batchItems.get(currentBatchIndex).rotationDegrees = currentVideoRotation;
                    batchItems.get(currentBatchIndex).flipHorizontal = currentVideoFlipH;
                }
            });
            transformRow.addView(btn);
        }
        root.addView(transformRow);

        // Row 2: Aspect Ratio Zoom Presets (Fit, 9:16 Fill, 1:1 Square, 16:9 Cinema)
        LinearLayout ratioRow = new LinearLayout(this);
        ratioRow.setOrientation(LinearLayout.HORIZONTAL);
        ratioRow.setGravity(Gravity.CENTER);
        ratioRow.setLayoutParams(rLp);
        final String[] ratios = new String[]{"Fit Original", "9:16 Full", "1:1 Zoom", "16:9 Wide"};
        final float[] scales = new float[]{1.0f, 1.28f, 1.18f, 1.12f};
        for (int i = 0; i < ratios.length; i++) {
            final float sc = scales[i];
            final String rLabel = ratios[i];
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, Math.round(38 * dp), 1f);
            if (i > 0) cLp.leftMargin = Math.round(6 * dp);
            chip.setLayoutParams(cLp);
            chip.setGravity(Gravity.CENTER);
            chip.setText(rLabel);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(11.5f);
            chip.setBackgroundResource(R.drawable.bg_filter_chip);
            chip.setOnClickListener(v -> {
                if (videoView != null) {
                    videoView.animate()
                            .scaleX((currentVideoFlipH ? -1f : 1f) * sc)
                            .scaleY(sc)
                            .setDuration(180)
                            .start();
                }
                Toast.makeText(this, "Aspect: " + rLabel, Toast.LENGTH_SHORT).show();
            });
            ratioRow.addView(chip);
        }
        root.addView(ratioRow);

        // Row 3: Extract & Crop Current Video Frame as Photo
        TextView btnExtractCropFrame = new TextView(this);
        btnExtractCropFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * dp)));
        btnExtractCropFrame.setGravity(Gravity.CENTER);
        btnExtractCropFrame.setText("✂️ Capture & Free-Crop Current Video Frame");
        btnExtractCropFrame.setTextColor(Color.WHITE);
        btnExtractCropFrame.setTextSize(13.5f);
        btnExtractCropFrame.setTypeface(null, Typeface.BOLD);
        updateChipTheme(btnExtractCropFrame, true);
        btnExtractCropFrame.setOnClickListener(v -> {
            sheet.dismiss();
            captureCurrentVideoFrameAndCrop();
        });
        root.addView(btnExtractCropFrame);

        sheet.setContentView(root);
        sheet.show();
    }

    private void captureCurrentVideoFrameAndCrop() {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(this, mediaUri);
            long posUs = (videoView != null ? videoView.getCurrentPosition() : trimStartMs) * 1000L;
            Bitmap frame = mmr.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) frame = mmr.getFrameAtTime();
            mmr.release();
            if (frame != null) {
                File frameFile = new File(getExternalCacheDir(), "video_frame_crop_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(frameFile)) {
                    frame.compress(Bitmap.CompressFormat.JPEG, 96, fos);
                }
                mediaType = "image";
                mediaUri = Uri.fromFile(frameFile);
                if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
                    BatchEditItem item = batchItems.get(currentBatchIndex);
                    item.type = "image";
                    item.uri = mediaUri;
                    item.savedPath = frameFile.getAbsolutePath();
                }
                bindActiveBatchItem();
                launchUCrop(mediaUri);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not capture video frame", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Left-to-right and right-to-left swipe gesture detection across the preview area
     * when multiple photos/videos are selected and the user is in BASE preview state.
     */
    private void setupHorizontalSwipeNavigation() {
        horizontalSwipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (currentState != UIState.BASE || batchItems.size() <= 1 || e1 == null || e2 == null) {
                    return false;
                }
                float dx = e1.getX() - e2.getX();
                float dy = e1.getY() - e2.getY();
                if (Math.abs(dx) > Math.abs(dy) * 1.25f && Math.abs(dx) > 95 && Math.abs(velocityX) > 140) {
                    if (dx > 0) {
                        // Swiped right-to-left -> next item
                        switchToBatchItem(currentBatchIndex + 1);
                    } else {
                        // Swiped left-to-right -> previous item
                        switchToBatchItem(currentBatchIndex - 1);
                    }
                    return true;
                }
                return false;
            }
        });

        View.OnTouchListener swipeListener = (v, event) -> {
            if (currentState == UIState.BASE && batchItems.size() > 1) {
                return horizontalSwipeDetector.onTouchEvent(event);
            }
            return false;
        };

        if (photoEditorView != null) {
            photoEditorView.setOnTouchListener(swipeListener);
            if (photoEditorView.getSource() != null) {
                photoEditorView.getSource().setOnTouchListener(swipeListener);
            }
        }
        if (videoView != null) {
            videoView.setOnTouchListener(swipeListener);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (currentState == UIState.BASE && batchItems.size() > 1 && horizontalSwipeDetector != null) {
            float y = ev.getY();
            int topLimit = topToolbar != null ? topToolbar.getBottom() : 160;
            int bottomLimit = editBatchThumbnailScroll != null && editBatchThumbnailScroll.getVisibility() == View.VISIBLE
                    ? editBatchThumbnailScroll.getTop()
                    : (bottomBar != null ? bottomBar.getTop() : getWindow().getDecorView().getHeight() - 220);
            if (y > topLimit && y < bottomLimit) {
                horizontalSwipeDetector.onTouchEvent(ev);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void buildBatchThumbnailStrip() {
        if (editBatchThumbnailScroll == null || editBatchThumbnailStrip == null) return;
        editBatchThumbnailStrip.removeAllViews();
        if (batchItems.size() <= 1) {
            editBatchThumbnailScroll.setVisibility(View.GONE);
            if (tvEditBatchCounter != null) tvEditBatchCounter.setVisibility(View.GONE);
            return;
        }

        editBatchThumbnailScroll.setVisibility(View.VISIBLE);
        if (tvEditBatchCounter != null) {
            tvEditBatchCounter.setVisibility(View.VISIBLE);
            tvEditBatchCounter.setText((currentBatchIndex + 1) + " / " + batchItems.size());
            updateChipTheme(tvEditBatchCounter, false);
        }

        float dp = getResources().getDisplayMetrics().density;
        int thumbSize = Math.round(52 * dp);
        int margin = Math.round(4 * dp);

        for (int i = 0; i < batchItems.size(); i++) {
            final int index = i;
            BatchEditItem item = batchItems.get(i);

            FrameLayout frame = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            lp.setMargins(margin, 0, margin, 0);
            frame.setLayoutParams(lp);
            int pad = Math.round(2 * dp);
            frame.setPadding(pad, pad, pad, pad);

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadThumbnailIntoView(item, iv);
            frame.addView(iv);

            if ("video".equals(item.type)) {
                ImageView badge = new ImageView(this);
                int bSz = Math.round(18 * dp);
                FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(bSz, bSz, Gravity.CENTER);
                badge.setLayoutParams(bLp);
                badge.setImageResource(R.drawable.ic_play_arrow);
                badge.setColorFilter(Color.WHITE);
                badge.setBackgroundResource(R.drawable.bg_circle_translucent);
                frame.addView(badge);
            }

            frame.setOnClickListener(v -> switchToBatchItem(index));
            editBatchThumbnailStrip.addView(frame);
        }

        highlightBatchThumbnail(currentBatchIndex);
    }

    private void loadThumbnailIntoView(BatchEditItem item, ImageView iv) {
        if (item == null || item.uri == null) return;
        try {
            if ("video".equals(item.type)) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(this, item.uri);
                Bitmap frame = retriever.getFrameAtTime();
                if (frame != null) iv.setImageBitmap(frame);
                retriever.release();
            } else {
                try (InputStream in = getContentResolver().openInputStream(item.uri)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                    if (bmp != null) iv.setImageBitmap(bmp);
                }
            }
        } catch (Exception e) {
            iv.setImageURI(item.uri);
        }
    }

    private void highlightBatchThumbnail(int activeIndex) {
        if (editBatchThumbnailStrip == null) return;
        float dp = getResources().getDisplayMetrics().density;
        if (tvEditBatchCounter != null && batchItems.size() > 1) {
            tvEditBatchCounter.setText((activeIndex + 1) + " / " + batchItems.size());
        }
        for (int i = 0; i < editBatchThumbnailStrip.getChildCount(); i++) {
            View child = editBatchThumbnailStrip.getChildAt(i);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(8 * dp);
            if (i == activeIndex) {
                border.setStroke(Math.round(2.5f * dp), Color.parseColor("#00A884"));
                border.setColor(Color.parseColor("#3300A884"));
                child.setScaleX(1.06f);
                child.setScaleY(1.06f);
                if (editBatchThumbnailScroll != null) {
                    final View target = child;
                    editBatchThumbnailScroll.post(() -> {
                        int scrollX = target.getLeft() - (editBatchThumbnailScroll.getWidth() - target.getWidth()) / 2;
                        editBatchThumbnailScroll.smoothScrollTo(Math.max(0, scrollX), 0);
                    });
                }
            } else {
                border.setStroke(Math.round(1 * dp), Color.parseColor("#40FFFFFF"));
                border.setColor(Color.TRANSPARENT);
                child.setScaleX(1.0f);
                child.setScaleY(1.0f);
            }
            child.setBackground(border);
        }
    }

    private void switchToBatchItem(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= batchItems.size() || targetIndex == currentBatchIndex) {
            return;
        }
        persistCurrentItemStateSynchronously();
        currentBatchIndex = targetIndex;
        BatchEditItem next = batchItems.get(currentBatchIndex);
        mediaUri = next.uri;
        mediaType = next.type;
        highlightBatchThumbnail(currentBatchIndex);
        bindActiveBatchItem();
    }

    private void persistCurrentItemStateSynchronously() {
        if (currentBatchIndex < 0 || currentBatchIndex >= batchItems.size()) return;
        BatchEditItem current = batchItems.get(currentBatchIndex);
        current.filterIndex = currentFilterIndex;
        if ("video".equals(current.type) || "gif".equals(current.type)) {
            current.trimStartMs = trimStartMs;
            current.trimEndMs = trimEndMs;
            current.totalDurationMs = totalDurationMs;
            current.isMuted = isVideoMuted;
            current.sendAsGif = sendVideoAsGif;
            current.rotationDegrees = currentVideoRotation;
            current.flipHorizontal = currentVideoFlipH;
            current.playbackSpeed = currentVideoSpeed;
            if (videoView != null && videoView.isPlaying()) {
                videoView.pause();
            }
        } else if (photoEditorView != null && photoEditor != null && !photoEditor.isCacheEmpty()) {
            try {
                photoEditorView.setDrawingCacheEnabled(true);
                Bitmap bmp = Bitmap.createBitmap(photoEditorView.getDrawingCache());
                photoEditorView.setDrawingCacheEnabled(false);
                if (bmp != null) {
                    File editedFile = new File(getExternalCacheDir(), "edited_batch_" + currentBatchIndex + "_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream out = new FileOutputStream(editedFile)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, isHdQuality ? 96 : 82, out);
                        out.flush();
                    }
                    current.savedPath = editedFile.getAbsolutePath();
                    current.uri = Uri.fromFile(editedFile);
                    if (editBatchThumbnailStrip != null && currentBatchIndex < editBatchThumbnailStrip.getChildCount()) {
                        View frame = editBatchThumbnailStrip.getChildAt(currentBatchIndex);
                        if (frame instanceof FrameLayout && ((FrameLayout) frame).getChildCount() > 0) {
                            View iv = ((FrameLayout) frame).getChildAt(0);
                            if (iv instanceof ImageView) {
                                ((ImageView) iv).setImageBitmap(bmp);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void bindActiveBatchItem() {
        videoLoopHandler.removeCallbacks(videoLoopRunnable);
        BatchEditItem item = (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size())
                ? batchItems.get(currentBatchIndex) : null;
        if (item != null) {
            isVideoMuted = item.isMuted;
            sendVideoAsGif = item.sendAsGif;
            currentVideoRotation = item.rotationDegrees;
            currentVideoFlipH = item.flipHorizontal;
            currentVideoSpeed = item.playbackSpeed > 0f ? item.playbackSpeed : 1.0f;
            currentFilterIndex = item.filterIndex;
        }
        applyActiveColorFilter();
        updateToolbarGifModeUi();
        if (mediaType != null && (mediaType.startsWith("video") || "gif".equals(mediaType))) {
            setupVideoEditor();
        } else {
            setupImageEditor();
        }
    }

    private void setupImageEditor() {
        activeVideoMediaPlayer = null;
        if (videoView != null && videoView.isPlaying()) {
            videoView.stopPlayback();
        }
        if (videoContainer != null) {
            videoContainer.setVisibility(View.GONE);
        }
        photoEditorView.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.GONE);
        if (videoTrimmerContainer != null) {
            videoTrimmerContainer.setVisibility(View.GONE);
        }

        findViewById(R.id.btnCrop).setVisibility(View.VISIBLE);
        findViewById(R.id.btnEmoji).setVisibility(View.VISIBLE);
        findViewById(R.id.btnText).setVisibility(View.VISIBLE);
        findViewById(R.id.btnDraw).setVisibility(View.VISIBLE);
        findViewById(R.id.btnUndo).setVisibility(View.VISIBLE);
        if (btnMuteAudioChip != null) btnMuteAudioChip.setVisibility(View.GONE);
        if (btnVideoSpeedChip != null) btnVideoSpeedChip.setVisibility(View.GONE);

        photoEditorView.getSource().setImageURI(null);
        photoEditorView.getSource().setImageURI(mediaUri);

        if (photoEditor == null) {
            photoEditor = new PhotoEditor.Builder(this, photoEditorView)
                    .setPinchTextScalable(true)
                    .build();
        } else {
            photoEditor.clearAllViews();
        }
    }

    private void launchUCrop(Uri sourceUri) {
        File destinationFile = new File(getExternalCacheDir(), "crop_" + System.currentTimeMillis() + ".jpg");
        Uri destinationUri = Uri.fromFile(destinationFile);

        UCrop.Options options = new UCrop.Options();
        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
        options.setCompressionQuality(isHdQuality ? 95 : 82);
        options.setHideBottomControls(false);
        options.setFreeStyleCropEnabled(true);
        options.setToolbarColor(ContextCompat.getColor(this, android.R.color.black));
        options.setStatusBarColor(ContextCompat.getColor(this, android.R.color.black));
        options.setToolbarWidgetColor(ContextCompat.getColor(this, android.R.color.white));
        options.setRootViewBackgroundColor(ContextCompat.getColor(this, android.R.color.black));
        options.setCropGridColor(ContextCompat.getColor(this, android.R.color.white));

        UCrop.of(sourceUri, destinationUri)
                .withOptions(options)
                .start(this);
    }

    private int[] resolveVideoNaturalDimensions(Uri uri, int fallbackW, int fallbackH) {
        int rawW = fallbackW;
        int rawH = fallbackH;
        int embeddedRot = 0;
        if (uri != null) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, uri);
                String wStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String rotStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (wStr != null && !wStr.isEmpty()) rawW = Integer.parseInt(wStr);
                if (hStr != null && !hStr.isEmpty()) rawH = Integer.parseInt(hStr);
                if (rotStr != null && !rotStr.isEmpty()) embeddedRot = Integer.parseInt(rotStr);
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
        int normalizedEmbedded = ((embeddedRot % 360) + 360) % 360;
        // Swap width and height if raw encoded stream has 90° or 270° rotation metadata
        // (unless fallback dimensions from MediaPlayer already swapped them)
        int naturalW = (normalizedEmbedded == 90 || normalizedEmbedded == 270) ? rawH : rawW;
        int naturalH = (normalizedEmbedded == 90 || normalizedEmbedded == 270) ? rawW : rawH;
        return new int[]{Math.max(1, naturalW), Math.max(1, naturalH), normalizedEmbedded};
    }

    private int getEmbeddedVideoRotation(Uri uri) {
        return resolveVideoNaturalDimensions(uri, 0, 0)[2];
    }

    private void fitVideoViewToContainer(int videoWidth, int videoHeight) {
        if (videoView == null || videoContainer == null || (videoWidth <= 0 && videoHeight <= 0)) return;
        videoContainer.post(() -> {
            int containerW = videoContainer.getWidth();
            int containerH = videoContainer.getHeight();
            if (containerW <= 0) {
                containerW = getResources().getDisplayMetrics().widthPixels;
            }
            if (containerH <= 0) {
                containerH = getResources().getDisplayMetrics().heightPixels;
            }
            if (containerW <= 0 || containerH <= 0) return;

            int[] natural = resolveVideoNaturalDimensions(mediaUri, videoWidth, videoHeight);
            int displayW = natural[0];
            int displayH = natural[1];

            int userRot = ((currentVideoRotation % 360) + 360) % 360;
            boolean userSwapped = (userRot == 90 || userRot == 270);
            int realW = userSwapped ? displayH : displayW;
            int realH = userSwapped ? displayW : displayH;

            // FIT_CENTER / RESIZE_MODE_FIT scaling so portrait and landscape videos never stretch
            float scale = Math.min((float) containerW / (float) realW, (float) containerH / (float) realH);
            int targetW = Math.max(1, Math.round(displayW * scale));
            int targetH = Math.max(1, Math.round(displayH * scale));

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER);
            } else {
                lp.width = targetW;
                lp.height = targetH;
                lp.gravity = Gravity.CENTER;
            }
            videoView.setLayoutParams(lp);
            videoView.setRotation(currentVideoRotation);
            videoView.requestLayout();
        });
    }

    private void setupVideoEditor() {
        if (videoContainer != null) videoContainer.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.VISIBLE);
        videoView.setRotation(currentVideoRotation);
        videoView.setScaleX(currentVideoFlipH ? -1f : 1f);
        photoEditorView.setVisibility(View.VISIBLE);
        photoEditorView.setBackgroundColor(Color.TRANSPARENT);
        if (photoEditorView.getSource() != null) {
            photoEditorView.getSource().setImageDrawable(null);
        }
        if (photoEditor == null) {
            photoEditor = new PhotoEditor.Builder(this, photoEditorView)
                    .setPinchTextScalable(true)
                    .build();
        } else {
            photoEditor.clearAllViews();
        }

        // Enable tools on video editor
        findViewById(R.id.btnCrop).setVisibility(View.VISIBLE);
        findViewById(R.id.btnEmoji).setVisibility(View.VISIBLE);
        findViewById(R.id.btnText).setVisibility(View.VISIBLE);
        findViewById(R.id.btnDraw).setVisibility(View.VISIBLE);
        findViewById(R.id.btnUndo).setVisibility(View.VISIBLE);
        if (btnMuteAudioChip != null) btnMuteAudioChip.setVisibility(View.VISIBLE);
        if (btnVideoSpeedChip != null) {
            btnVideoSpeedChip.setVisibility(View.VISIBLE);
            btnVideoSpeedChip.setText(String.format(java.util.Locale.US, "%.1fx", currentVideoSpeed));
        }

        videoTrimmerContainer = findViewById(R.id.videoTrimmerContainer);
        videoFilmstripTrimmer = findViewById(R.id.videoFilmstripTrimmer);
        btnVideoPlayPauseToggle = findViewById(R.id.btnVideoPlayPauseToggle);
        btnVideoGifModeToggle = findViewById(R.id.btnVideoGifModeToggle);
        tvTrimStart = findViewById(R.id.tvTrimStart);
        tvTrimEnd = findViewById(R.id.tvTrimEnd);
        tvTrimDuration = findViewById(R.id.tvTrimDuration);

        if (videoTrimmerContainer != null) {
            videoTrimmerContainer.setVisibility(View.VISIBLE);
        }

        if (btnVideoPlayPauseToggle != null) {
            btnVideoPlayPauseToggle.setOnClickListener(v -> {
                if (videoView == null) return;
                if (videoView.isPlaying()) {
                    videoView.pause();
                    updatePlayPauseButtonUi(false);
                } else {
                    videoView.start();
                    updatePlayPauseButtonUi(true);
                }
            });
        }

        if (btnVideoGifModeToggle != null) {
            btnVideoGifModeToggle.setText(sendVideoAsGif ? "👾 GIF Loop" : "🎬 Video Mode");
            updateChipTheme(btnVideoGifModeToggle, sendVideoAsGif);
            btnVideoGifModeToggle.setOnClickListener(v -> {
                sendVideoAsGif = !sendVideoAsGif;
                btnVideoGifModeToggle.setText(sendVideoAsGif ? "👾 GIF Loop" : "🎬 Video Mode");
                updateChipTheme(btnVideoGifModeToggle, sendVideoAsGif);
                if (sendVideoAsGif) {
                    isVideoMuted = true;
                    applyLiveVideoMuteState();
                    if (trimEndMs - trimStartMs > 6500L) {
                        trimEndMs = Math.min(totalDurationMs, trimStartMs + 6000L);
                        if (videoFilmstripTrimmer != null) {
                            videoFilmstripTrimmer.setTrimRange(trimStartMs, trimEndMs);
                        }
                        if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
                    }
                    Toast.makeText(this, "👾 GIF Mode: silent looping clip", Toast.LENGTH_SHORT).show();
                } else {
                    isVideoMuted = false;
                    applyLiveVideoMuteState();
                }
                if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
                    batchItems.get(currentBatchIndex).sendAsGif = sendVideoAsGif;
                }
                updateTrimDurationAndSizeLabel();
            });
        }

        videoView.setVideoURI(mediaUri);
        videoView.setOnPreparedListener(mp -> {
            activeVideoMediaPlayer = mp;
            totalDurationMs = Math.max(100L, mp.getDuration());
            fitVideoViewToContainer(mp.getVideoWidth(), mp.getVideoHeight());

            BatchEditItem item = (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size())
                    ? batchItems.get(currentBatchIndex) : null;
            if (item != null && item.trimEndMs > 0 && item.totalDurationMs == totalDurationMs) {
                trimStartMs = item.trimStartMs;
                trimEndMs = item.trimEndMs;
            } else {
                trimStartMs = 0;
                trimEndMs = totalDurationMs;
            }

            applyLiveVideoMuteState();
            applyLiveVideoPlaybackSpeed();

            if (videoFilmstripTrimmer != null && totalDurationMs > 0) {
                videoFilmstripTrimmer.setVideoUri(this, mediaUri, totalDurationMs);
                videoFilmstripTrimmer.setTrimRange(trimStartMs, trimEndMs);
                videoFilmstripTrimmer.setOnTrimChangeListener((startMs, endMs, fromUser, isDragging) -> {
                    trimStartMs = startMs;
                    trimEndMs = endMs;
                    if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStartMs));
                    if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
                    updateTrimDurationAndSizeLabel();
                    if (fromUser && videoView != null) {
                        videoView.seekTo((int) startMs);
                        if (isDragging) {
                            if (videoView.isPlaying()) videoView.pause();
                            updatePlayPauseButtonUi(false);
                        }
                    }
                });
            }

            if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStartMs));
            if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
            updateTrimDurationAndSizeLabel();

            mp.setLooping(false);
            videoView.seekTo((int) trimStartMs);
            videoView.start();
            updatePlayPauseButtonUi(true);
            videoLoopHandler.removeCallbacks(videoLoopRunnable);
            videoLoopHandler.post(videoLoopRunnable);
        });
    }

    private void toggleVideoPlayback() {
        if (videoView == null) return;
        if (videoView.isPlaying()) {
            videoView.pause();
            updatePlayPauseButtonUi(false);
        } else {
            videoView.start();
            updatePlayPauseButtonUi(true);
        }
    }

    private void updateToolbarGifModeUi() {
        if (btnToolbarGifMode == null) return;
        boolean isVideo = mediaType != null && mediaType.startsWith("video");
        btnToolbarGifMode.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        if (sendVideoAsGif) {
            btnToolbarGifMode.setBackgroundResource(R.drawable.bg_view_once_ring_active);
            btnToolbarGifMode.setColorFilter(Color.WHITE);
        } else {
            btnToolbarGifMode.setBackgroundResource(R.drawable.bg_circle_translucent);
            btnToolbarGifMode.setColorFilter(Color.WHITE);
        }
        updateTrimDurationAndSizeLabel();
    }

    private void updatePlayPauseButtonUi(boolean playing) {
        if (btnVideoPlayPauseToggle != null) {
            btnVideoPlayPauseToggle.setText(playing ? "⏸ Pause" : "▶ Play");
        }
        if (btnMidPlayPause != null) {
            btnMidPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            if (playing) {
                btnMidPlayPause.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                    if (videoView != null && videoView.isPlaying()) {
                        btnMidPlayPause.setVisibility(View.GONE);
                    }
                }).start();
            } else {
                btnMidPlayPause.animate().cancel();
                btnMidPlayPause.setAlpha(1f);
                btnMidPlayPause.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateTrimDurationAndSizeLabel() {
        if (tvTrimDuration == null) return;
        long rangeMs = Math.max(0L, trimEndMs - trimStartMs);
        // Estimate size based on bitrate (~1.2 MB/s for HD, ~0.55 MB/s for SD, minus 15% if muted)
        double mbPerSec = isHdQuality ? 0.38 : 0.18;
        if (isVideoMuted) mbPerSec *= 0.85;
        double estMb = Math.max(0.08, (rangeMs / 1000.0) * mbPerSec);
        String sizeStr = estMb >= 1.0
                ? String.format(java.util.Locale.US, "%.1f MB", estMb)
                : String.format(java.util.Locale.US, "%d KB", Math.max(40, (int) Math.round(estMb * 1024)));
        String modeTag = sendVideoAsGif ? "GIF • " : (isVideoMuted ? "🔇 • " : "");
        tvTrimDuration.setText(modeTag + formatTime(rangeMs) + " • " + sizeStr);
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        videoLoopHandler.removeCallbacks(videoLoopRunnable);
    }

    private String resolveBatchItemFilePath(BatchEditItem item, int idx) {
        if (item == null || item.uri == null) return "";
        if ("video".equals(item.type) || "gif".equals(item.type)) {
            File tempVideo = new File(getExternalCacheDir(), "processed_vid_" + idx + "_" + System.currentTimeMillis() + ".mp4");
            boolean isTrimmed = (item.trimStartMs > 250) || (item.totalDurationMs > 0 && (item.totalDurationMs - item.trimEndMs > 250));
            boolean needsMuxProcess = isTrimmed || item.isMuted || item.sendAsGif || (item.rotationDegrees % 360 != 0);
            if (needsMuxProcess && VideoTrimUtil.processVideo(
                    this, item.uri, tempVideo,
                    item.trimStartMs, item.trimEndMs > 0 ? item.trimEndMs : item.totalDurationMs,
                    item.isMuted || item.sendAsGif, item.rotationDegrees)
                    && tempVideo.exists() && tempVideo.length() > 0) {
                item.savedPath = tempVideo.getAbsolutePath();
                if (item.sendAsGif) item.type = "gif";
                return item.savedPath;
            }
        }
        if (item.savedPath != null && new File(item.savedPath).exists()) {
            return item.savedPath;
        }
        if ("file".equalsIgnoreCase(item.uri.getScheme()) && item.uri.getPath() != null) {
            File f = new File(item.uri.getPath());
            if (f.exists()) {
                item.savedPath = f.getAbsolutePath();
                return item.savedPath;
            }
        }
        String ext = ("video".equals(item.type) || "gif".equals(item.type)) ? ".mp4" : ".jpg";
        File outFile = new File(getExternalCacheDir(), "batch_send_" + idx + "_" + System.currentTimeMillis() + ext);
        try (InputStream in = getContentResolver().openInputStream(item.uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in != null) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            }
            if (outFile.exists() && outFile.length() > 0) {
                item.savedPath = outFile.getAbsolutePath();
                return item.savedPath;
            }
        } catch (Exception ignored) {}
        return item.uri.getPath() != null ? item.uri.getPath() : "";
    }

    private boolean isSavingMedia = false;
    private View sendProgressSpinner;
    private View mediaProcessingOverlay;
    private TextView tvMediaProcessingStatus;
    private View topHeaderBar;

    private void setMediaSavingState(boolean saving, String statusMessage) {
        isSavingMedia = saving;
        View btnSendView = findViewById(R.id.btnSend);
        if (btnSendView instanceof com.google.android.material.floatingactionbutton.FloatingActionButton) {
            com.google.android.material.floatingactionbutton.FloatingActionButton fab =
                    (com.google.android.material.floatingactionbutton.FloatingActionButton) btnSendView;
            fab.setEnabled(!saving);
            if (saving) {
                fab.setImageDrawable(null);
            } else {
                fab.setImageResource(R.drawable.ic_send);
            }
        } else if (btnSendView != null) {
            btnSendView.setEnabled(!saving);
        }

        if (sendProgressSpinner == null) {
            sendProgressSpinner = findViewById(R.id.sendProgressSpinner);
        }
        if (sendProgressSpinner != null) {
            sendProgressSpinner.setVisibility(saving ? View.VISIBLE : View.GONE);
        }

        if (mediaProcessingOverlay == null) {
            mediaProcessingOverlay = findViewById(R.id.mediaProcessingOverlay);
        }
        if (tvMediaProcessingStatus == null) {
            tvMediaProcessingStatus = findViewById(R.id.tvMediaProcessingStatus);
        }
        if (tvMediaProcessingStatus != null && statusMessage != null && !statusMessage.isEmpty()) {
            tvMediaProcessingStatus.setText(statusMessage);
        }
        if (mediaProcessingOverlay != null) {
            mediaProcessingOverlay.setVisibility(saving ? View.VISIBLE : View.GONE);
        }

        float disabledAlpha = saving ? 0.45f : 1.0f;
        int[] controlIds = new int[]{
                R.id.btnClose, R.id.btnUndo, R.id.btnCrop, R.id.btnEmoji, R.id.btnText, R.id.btnDraw,
                R.id.btnAddMoreMedia, R.id.captionEditText, R.id.btnMentionTag, R.id.btnViewOnce,
                R.id.recipientStatusPill, R.id.videoFilmstripTrimmer, R.id.editBatchThumbnailScroll
        };
        for (int id : controlIds) {
            View v = findViewById(id);
            if (v != null) {
                v.setEnabled(!saving);
                v.setAlpha(disabledAlpha);
            }
        }
        if (topToolbar instanceof ViewGroup) {
            ViewGroup tb = (ViewGroup) topToolbar;
            for (int i = 0; i < tb.getChildCount(); i++) {
                View child = tb.getChildAt(i);
                if (child != null) {
                    child.setEnabled(!saving);
                    child.setAlpha(disabledAlpha);
                }
            }
        }
    }

    private void saveAndSend() {
        if (isSavingMedia) return;

        if (batchItems.size() > 1) {
            persistCurrentItemStateSynchronously();
            setMediaSavingState(true, "Processing " + batchItems.size() + " media items...");
            new Thread(() -> {
                final ArrayList<String> allPaths = new ArrayList<>();
                final ArrayList<String> allTypes = new ArrayList<>();
                for (int i = 0; i < batchItems.size(); i++) {
                    BatchEditItem item = batchItems.get(i);
                    String p = resolveBatchItemFilePath(item, i);
                    if (p != null && !p.isEmpty()) {
                        allPaths.add(p);
                        allTypes.add(item.sendAsGif ? "gif" : item.type);
                    }
                }
                final String primaryPath = !allPaths.isEmpty() ? allPaths.get(0) : "";
                final String primaryType = !allTypes.isEmpty() ? allTypes.get(0) : "image";
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishWithBatchResult(primaryPath, primaryType, allPaths, allTypes);
                });
            }, "VeilBatchMediaSave").start();
            return;
        }

        if (mediaType != null && mediaType.startsWith("image") && photoEditor != null) {
            setMediaSavingState(true, isStickerMode ? "Saving custom sticker..." : (isGifMode ? "Saving GIF..." : "Saving edited photo..."));
            final boolean noOverlayEdits = photoEditor.isCacheEmpty();
            File outputFile = new File(getExternalCacheDir(), "edited_" + System.currentTimeMillis() + ".jpg");
            try {
                photoEditor.saveAsFile(outputFile.getAbsolutePath(), new PhotoEditor.OnSaveListener() {
                    @Override
                    public void onSuccess(final String imagePath) {
                        new Thread(() -> {
                            String finalOutPath = imagePath;
                            String finalOutType = "image";
                            try {
                                if (isStickerMode) {
                                    finalOutType = "sticker";
                                    if (noOverlayEdits && mediaUri != null && "file".equalsIgnoreCase(mediaUri.getScheme()) && mediaUri.getPath() != null) {
                                        File srcFile = new File(mediaUri.getPath());
                                        if (com.m3.veil.utils.VeilStickerGifEngine.isAnimatedStickerFile(srcFile)) {
                                            finalOutPath = srcFile.getAbsolutePath();
                                            final String outP = finalOutPath;
                                            final String outT = finalOutType;
                                            runOnUiThread(() -> finishWithResult(outP, outT));
                                            return;
                                        }
                                    }
                                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                                    opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
                                    Bitmap rawBmp = null;
                                    if (noOverlayEdits && mediaUri != null) {
                                        try (InputStream in = getContentResolver().openInputStream(mediaUri)) {
                                            if (in != null) rawBmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
                                        } catch (Throwable ignored) {}
                                    }
                                    if (rawBmp == null) {
                                        rawBmp = android.graphics.BitmapFactory.decodeFile(imagePath, opts);
                                    }
                                    if (rawBmp != null) {
                                        int maxSide = 512;
                                        int bw = Math.max(1, rawBmp.getWidth());
                                        int bh = Math.max(1, rawBmp.getHeight());
                                        float sc = Math.min(1f, Math.min((float) maxSide / bw, (float) maxSide / bh));
                                        Bitmap scaled = (sc < 0.99f)
                                                ? Bitmap.createScaledBitmap(rawBmp, Math.max(1, Math.round(bw * sc)), Math.max(1, Math.round(bh * sc)), true)
                                                : rawBmp;
                                        Bitmap crispCutout = com.m3.veil.utils.VeilStickerGifEngine.stripSolidBorderBackgroundIfPresent(scaled);
                                        // Enforce WhatsApp/Telegram-standard 512x512 (1:1 Square) transparent sticker canvas
                                        Bitmap crispSticker = com.m3.veil.utils.VeilStickerGifEngine.fitOrCenterCropToSquare(crispCutout, 512, false);
                                        File stickerOut = new File(getExternalCacheDir(), "edited_sticker_" + System.currentTimeMillis() + ".png");
                                        try (FileOutputStream fos = new FileOutputStream(stickerOut)) {
                                            crispSticker.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                            fos.flush();
                                        }
                                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions(
                                                stickerOut.getAbsolutePath(), crispSticker.getWidth(), crispSticker.getHeight());
                                        finalOutPath = stickerOut.getAbsolutePath();
                                    }
                                } else if (isGifMode) {
                                    finalOutType = "gif";
                                    if (noOverlayEdits && mediaUri != null && "file".equalsIgnoreCase(mediaUri.getScheme()) && mediaUri.getPath() != null) {
                                        File srcFile = new File(mediaUri.getPath());
                                        if (com.m3.veil.utils.VeilStickerGifEngine.isGifFile(srcFile)) {
                                            finalOutPath = srcFile.getAbsolutePath();
                                        }
                                    }
                                } else {
                                    // Standard photo edit: ensure true JPEG bytes and always return "image" (never sticker!)
                                    finalOutType = "image";
                                    Bitmap savedBmp = android.graphics.BitmapFactory.decodeFile(imagePath);
                                    if (savedBmp != null) {
                                        try (FileOutputStream fos = new FileOutputStream(imagePath)) {
                                            savedBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                                            fos.flush();
                                        }
                                        savedBmp.recycle();
                                    }
                                }
                            } catch (Throwable ignored) {}
                            final String resPath = finalOutPath;
                            final String resType = finalOutType;
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                finishWithResult(resPath, resType);
                            });
                        }, "VeilPhotoSaveThread").start();
                    }

                    @Override
                    public void onFailure(Exception exception) {
                        setMediaSavingState(false, null);
                        Toast.makeText(MediaEditActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                setMediaSavingState(false, null);
                e.printStackTrace();
            }
        } else {
            try {
                if (videoView != null && videoView.isPlaying()) {
                    videoView.pause();
                }
            } catch (Exception ignored) {}
            setMediaSavingState(true, sendVideoAsGif ? "Encoding GIF clip..." : "Processing video...");
            final long startSnapshot = trimStartMs;
            final long endSnapshot = trimEndMs > 0 ? trimEndMs : totalDurationMs;
            final long totalSnapshot = totalDurationMs;
            final boolean mutedSnapshot = isVideoMuted;
            final boolean gifSnapshot = sendVideoAsGif;
            final int rotSnapshot = currentVideoRotation;

            new Thread(() -> {
                File tempVideo = new File(getExternalCacheDir(), "processed_vid_" + System.currentTimeMillis() + ".mp4");
                boolean isTrimmed = (startSnapshot > 250) || (totalSnapshot > 0 && (totalSnapshot - endSnapshot > 250));
                boolean needsMuxProcess = isTrimmed || mutedSnapshot || gifSnapshot || (rotSnapshot % 360 != 0);
                boolean muxSuccess = false;

                if (needsMuxProcess) {
                    muxSuccess = VideoTrimUtil.processVideo(
                            MediaEditActivity.this, mediaUri, tempVideo,
                            startSnapshot, endSnapshot,
                            mutedSnapshot || gifSnapshot, rotSnapshot);
                }

                final String path;
                if (muxSuccess && tempVideo.exists() && tempVideo.length() > 0) {
                    path = tempVideo.getAbsolutePath();
                } else if (mediaUri != null && "file".equalsIgnoreCase(mediaUri.getScheme())) {
                    path = mediaUri.getPath();
                } else if (mediaUri != null) {
                    String copiedPath = "";
                    try (InputStream in = getContentResolver().openInputStream(mediaUri);
                         OutputStream out = new FileOutputStream(tempVideo)) {
                        if (in != null) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) != -1) {
                                out.write(buf, 0, len);
                            }
                            out.flush();
                            copiedPath = tempVideo.getAbsolutePath();
                        }
                    } catch (Exception e) {
                        copiedPath = mediaUri.getPath() != null ? mediaUri.getPath() : "";
                    }
                    path = copiedPath;
                } else {
                    path = "";
                }
                final String resultType = gifSnapshot ? "gif" : "video";
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishWithResult(path, resultType);
                });
            }, "VeilVideoSaveThread").start();
        }
    }

    private void finishWithResult(String filePath, String type) {
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> types = new ArrayList<>();
        if (filePath != null && !filePath.isEmpty()) {
            paths.add(filePath);
            types.add(type != null ? type : "image");
        }
        finishWithBatchResult(filePath, type, paths, types);
    }

    private void finishWithBatchResult(String filePath, String type, ArrayList<String> batchPaths, ArrayList<String> batchTypes) {
        Intent data = new Intent();
        data.putExtra(RESULT_EDITED_FILE_PATH, filePath);
        data.putExtra(RESULT_MEDIA_TYPE, type);
        data.putExtra(RESULT_IS_VIEW_ONCE, isViewOnce);
        data.putExtra(RESULT_IS_STATUS_POST, isStatusMode);
        if (batchPaths != null && !batchPaths.isEmpty()) {
            data.putStringArrayListExtra(RESULT_EDITED_BATCH_PATHS, batchPaths);
            data.putStringArrayListExtra(RESULT_EDITED_BATCH_TYPES, batchTypes);
        }
        String caption = (captionEditText != null && captionEditText.getText() != null)
                ? captionEditText.getText().toString().trim() : "";
        data.putExtra(RESULT_CAPTION, caption);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == UCrop.REQUEST_CROP && data != null) {
            final Uri resultUri = UCrop.getOutput(data);
            if (resultUri != null && photoEditorView != null) {
                mediaUri = resultUri;
                if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
                    batchItems.get(currentBatchIndex).uri = resultUri;
                    if (resultUri.getPath() != null) {
                        batchItems.get(currentBatchIndex).savedPath = resultUri.getPath();
                    }
                }
                photoEditor.clearAllViews();
                photoEditorView.getSource().setImageURI(mediaUri);
                buildBatchThumbnailStrip();
            }
        } else if (resultCode == RESULT_OK && requestCode == REQ_ADD_MORE_MEDIA && data != null) {
            persistCurrentItemStateSynchronously();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) {
                        batchItems.add(new BatchEditItem(u, inferMediaType(u, null)));
                    }
                }
            } else if (data.getData() != null) {
                Uri u = data.getData();
                batchItems.add(new BatchEditItem(u, inferMediaType(u, null)));
            }
            buildBatchThumbnailStrip();
            switchToBatchItem(batchItems.size() - 1);
        } else if (resultCode == UCrop.RESULT_ERROR && data != null) {
            final Throwable cropError = UCrop.getError(data);
            if (cropError != null) {
                cropError.printStackTrace();
            }
        }
    }

    // --- UI STATE MACHINE ---
    private enum UIState {
        BASE, DRAWING, TEXT
    }

    private UIState currentState = UIState.BASE;
    private View topToolbar, bottomBar, btnClose, btnDone, drawingOptionsBar, textOptionsBar;
    private ColorPickerView colorPickerView;
    private int currentSelectedColor = Color.WHITE;
    private int selectedBrushIndex = 0;
    private final List<ImageView> brushViews = new ArrayList<>();

    private void setupViewsForState() {
        topHeaderBar = findViewById(R.id.topHeaderBar);
        topToolbar = findViewById(R.id.topToolbar);
        bottomBar = findViewById(R.id.bottomBar);
        btnClose = findViewById(R.id.btnClose);
        drawingHeaderBar = findViewById(R.id.drawingHeaderBar);
        btnDone = findViewById(R.id.btnDone);
        btnDrawUndo = findViewById(R.id.btnDrawUndo);
        drawingOptionsBar = findViewById(R.id.drawingOptionsBar);
        textOptionsBar = findViewById(R.id.textOptionsBar);
        colorPickerView = findViewById(R.id.colorPickerView);
        sendProgressSpinner = findViewById(R.id.sendProgressSpinner);
        mediaProcessingOverlay = findViewById(R.id.mediaProcessingOverlay);
        tvMediaProcessingStatus = findViewById(R.id.tvMediaProcessingStatus);

        if (btnDone != null) {
            btnDone.setOnClickListener(v -> updateState(UIState.BASE));
        }
        if (btnDrawUndo != null) {
            btnDrawUndo.setOnClickListener(v -> {
                if (photoEditor != null) photoEditor.undo();
            });
        }

        colorPickerView.setOnColorChangeListener(color -> {
            currentSelectedColor = color;
            if (currentState == UIState.DRAWING && photoEditor != null) {
                if (selectedBrushIndex == 4) {
                    photoEditor.brushEraser();
                } else if (selectedBrushIndex == 2) {
                    int alphaColor = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color));
                    photoEditor.setBrushDrawingMode(true);
                    photoEditor.setBrushSize(32f);
                    photoEditor.setBrushColor(alphaColor);
                } else {
                    float[] sizes = new float[]{6f, 16f, 32f, 22f, 36f};
                    photoEditor.setBrushDrawingMode(true);
                    photoEditor.setBrushSize(sizes[selectedBrushIndex]);
                    photoEditor.setBrushColor(color);
                }
            }
        });

        populateDrawingAndTextToolbars();
    }

    private void populateDrawingAndTextToolbars() {
        float dp = getResources().getDisplayMetrics().density;
        if (drawingOptionsBar instanceof LinearLayout) {
            LinearLayout drawBar = (LinearLayout) drawingOptionsBar;
            drawBar.removeAllViews();
            brushViews.clear();

            int[] iconRes = new int[]{
                    R.drawable.ic_brush_pen,
                    R.drawable.ic_brush_marker,
                    R.drawable.ic_brush_highlighter,
                    R.drawable.ic_brush_neon,
                    R.drawable.ic_brush_eraser
            };
            float[] sizes = new float[]{6f, 16f, 32f, 22f, 36f};

            for (int i = 0; i < iconRes.length; i++) {
                final int idx = i;
                final float bSize = sizes[i];

                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(38 * dp), Math.round(38 * dp));
                lp.setMargins(Math.round(4 * dp), 0, Math.round(4 * dp), 0);
                iv.setLayoutParams(lp);
                iv.setImageResource(iconRes[i]);
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                iv.setPadding(Math.round(7 * dp), Math.round(7 * dp), Math.round(7 * dp), Math.round(7 * dp));
                UiAnimator.attachSpringPress(iv);

                iv.setOnClickListener(v -> {
                    selectedBrushIndex = idx;
                    updateBrushSelectionUi();
                    if (photoEditor == null) return;
                    if (idx == 4) {
                        photoEditor.brushEraser();
                        Toast.makeText(this, "Eraser active", Toast.LENGTH_SHORT).show();
                    } else if (idx == 2) {
                        photoEditor.setBrushDrawingMode(true);
                        photoEditor.setBrushSize(bSize);
                        int alphaColor = Color.argb(128, Color.red(currentSelectedColor), Color.green(currentSelectedColor), Color.blue(currentSelectedColor));
                        photoEditor.setBrushColor(alphaColor);
                    } else {
                        photoEditor.setBrushDrawingMode(true);
                        photoEditor.setBrushSize(bSize);
                        photoEditor.setBrushColor(currentSelectedColor);
                    }
                });

                brushViews.add(iv);
                drawBar.addView(iv);
            }
            updateBrushSelectionUi();
        }
    }

    private void updateBrushSelectionUi() {
        for (int i = 0; i < brushViews.size(); i++) {
            ImageView iv = brushViews.get(i);
            boolean isSelected = (i == selectedBrushIndex);
            if (isSelected) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setShape(GradientDrawable.OVAL);
                selBg.setColor(Color.parseColor("#00A884"));
                iv.setBackground(selBg);
                iv.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                iv.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).start();
            } else {
                GradientDrawable normalBg = new GradientDrawable();
                normalBg.setShape(GradientDrawable.OVAL);
                normalBg.setColor(Color.parseColor("#26FFFFFF"));
                iv.setBackground(normalBg);
                iv.setImageTintList(ColorStateList.valueOf(Color.parseColor("#B3FFFFFF")));
                iv.animate().scaleX(1.0f).scaleY(1.0f).setDuration(160).start();
            }
        }
    }

    private void updateState(UIState newState) {
        currentState = newState;

        if (topHeaderBar != null) {
            topHeaderBar.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        }
        if (drawingHeaderBar != null) {
            drawingHeaderBar.setVisibility(newState == UIState.DRAWING ? View.VISIBLE : View.GONE);
        }
        if (videoTrimmerContainer != null) {
            videoTrimmerContainer.setVisibility(newState == UIState.BASE && mediaType != null && mediaType.startsWith("video") ? View.VISIBLE : View.GONE);
        }
        if (topToolbar != null) topToolbar.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        if (btnClose != null) btnClose.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        if (editBatchThumbnailScroll != null) {
            editBatchThumbnailScroll.setVisibility(newState == UIState.BASE && batchItems.size() > 1 ? View.VISIBLE : View.GONE);
        }
        if (tvEditBatchCounter != null) {
            tvEditBatchCounter.setVisibility(newState == UIState.BASE && batchItems.size() > 1 ? View.VISIBLE : View.GONE);
        }

        if (colorPickerView != null) colorPickerView.setVisibility(newState != UIState.BASE ? View.VISIBLE : View.GONE);
        if (textOptionsBar != null) textOptionsBar.setVisibility(newState == UIState.TEXT ? View.VISIBLE : View.GONE);

        if (photoEditor != null) {
            photoEditor.setBrushDrawingMode(newState == UIState.DRAWING);
            if (newState == UIState.DRAWING) {
                if (selectedBrushIndex == 4) {
                    photoEditor.brushEraser();
                } else if (selectedBrushIndex == 2) {
                    int alphaColor = Color.argb(128, Color.red(currentSelectedColor), Color.green(currentSelectedColor), Color.blue(currentSelectedColor));
                    photoEditor.setBrushColor(alphaColor);
                } else {
                    float[] sizes = new float[]{6f, 16f, 32f, 22f, 36f};
                    photoEditor.setBrushSize(sizes[selectedBrushIndex]);
                    photoEditor.setBrushColor(currentSelectedColor);
                }
            }
        }
    }
}
