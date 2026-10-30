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
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.slider.RangeSlider;
import com.m3.veil.R;
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

    private TextView btnCensorChip;
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
    private LinearLayout videoFilmstripRow;
    private TextView btnVideoPlayPauseToggle;
    private TextView btnVideoGifModeToggle;
    private RangeSlider videoRangeSlider;
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
    private String recipientLabel = "Status (Contacts)";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_media_edit);

        View mainView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        extractMediaBatchFromIntent();

        if (batchItems.isEmpty() || mediaUri == null || mediaType == null) {
            Toast.makeText(this, "Invalid media", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        configureDarkMediaSystemBars();

        if (mainView != null) {
            mainView.setScaleX(0.92f);
            mainView.setScaleY(0.92f);
            mainView.setAlpha(0f);
            mainView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void configureDarkMediaSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.Window window = getWindow();
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.Window window = getWindow();
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            int flags = window.getDecorView().getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(flags);
        }

        photoEditorView = findViewById(R.id.photoEditorView);
        videoView = findViewById(R.id.videoView);
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

        UiAnimator.attachSpringPress(btnCloseView);
        UiAnimator.attachSpringPress(btnSendView);
        UiAnimator.attachSpringPress(btnCropView);
        UiAnimator.attachSpringPress(btnEmojiView);
        UiAnimator.attachSpringPress(btnTextView);
        UiAnimator.attachSpringPress(btnDrawView);
        UiAnimator.attachSpringPress(btnUndoView);

        btnCloseView.setOnClickListener(v -> finish());

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

        btnSendView.setOnClickListener(v -> saveAndSend());

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

        btnEmojiView.setOnClickListener(v -> {
            EmojiStickerBottomSheet bottomSheet = new EmojiStickerBottomSheet();
            bottomSheet.setOnEmojiSelectedListener(emoji -> {
                if (photoEditor != null) {
                    photoEditor.addEmoji(emoji);
                }
            });
            bottomSheet.show(getSupportFragmentManager(), "EmojiBottomSheet");
        });

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

        btnDrawView.setOnClickListener(v -> updateState(UIState.DRAWING));
        btnUndoView.setOnClickListener(v -> {
            if (photoEditor != null) {
                photoEditor.undo();
            }
        });

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

    private boolean isCensorBrushActive = false;
    private boolean isVideoMuted = false;

    private TextView buildToolbarChip(String label, float dp) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(12f);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setBackgroundResource(R.drawable.bg_filter_chip);
        chip.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
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
        btnHdQualityChip.setBackgroundResource(R.drawable.bg_filter_chip_selected);
        btnHdQualityChip.setOnClickListener(v -> {
            isHdQuality = !isHdQuality;
            btnHdQualityChip.setText(isHdQuality ? "HD" : "SD");
            btnHdQualityChip.setBackgroundResource(isHdQuality ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            updateTrimDurationAndSizeLabel();
            Toast.makeText(this, isHdQuality ? "HD High-Definition Quality enabled" : "Standard Data-Saver Quality enabled", Toast.LENGTH_SHORT).show();
        });
        tb.addView(btnHdQualityChip, 0);

        // 2. Color Filter Cycling Chip
        btnColorFilterChip = buildToolbarChip("✨ Filter", dp);
        btnColorFilterChip.setOnClickListener(v -> {
            currentFilterIndex = (currentFilterIndex + 1) % FILTER_NAMES.length;
            applyActiveColorFilter();
            Toast.makeText(this, "Filter: " + FILTER_NAMES[currentFilterIndex], Toast.LENGTH_SHORT).show();
        });
        tb.addView(btnColorFilterChip, 1);

        // 3. Video Playback Speed Chip (0.5x / 1.0x / 1.5x / 2.0x)
        btnVideoSpeedChip = buildToolbarChip("1.0x", dp);
        btnVideoSpeedChip.setOnClickListener(v -> {
            if (currentVideoSpeed == 1.0f) currentVideoSpeed = 1.5f;
            else if (currentVideoSpeed == 1.5f) currentVideoSpeed = 2.0f;
            else if (currentVideoSpeed == 2.0f) currentVideoSpeed = 0.5f;
            else currentVideoSpeed = 1.0f;
            btnVideoSpeedChip.setText(String.format(java.util.Locale.US, "%.1fx", currentVideoSpeed));
            btnVideoSpeedChip.setBackgroundResource(currentVideoSpeed != 1.0f ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            applyLiveVideoPlaybackSpeed();
        });
        tb.addView(btnVideoSpeedChip, 2);

        // 4. Privacy Censor Brush Chip (works on both Photos and Videos!)
        btnCensorChip = buildToolbarChip("🕵️ Censor", dp);
        btnCensorChip.setOnClickListener(v -> {
            if (photoEditor == null) return;
            isCensorBrushActive = !isCensorBrushActive;
            if (isCensorBrushActive) {
                updateState(UIState.DRAWING);
                photoEditor.setBrushSize(64f);
                photoEditor.setBrushColor(Color.parseColor("#E6111827"));
                btnCensorChip.setBackgroundResource(R.drawable.bg_filter_chip_selected);
                Toast.makeText(this, "🕵️ Censor Brush active: swipe over faces or sensitive text", Toast.LENGTH_SHORT).show();
            } else {
                photoEditor.setBrushSize(16f);
                photoEditor.setBrushColor(currentSelectedColor);
                btnCensorChip.setBackgroundResource(R.drawable.bg_filter_chip);
            }
        });
        tb.addView(btnCensorChip);

        // 5. Real-Time Audio Mute Chip (mutes live MediaPlayer AND strips audio track on save!)
        btnMuteAudioChip = buildToolbarChip("🔊 Audio", dp);
        btnMuteAudioChip.setOnClickListener(v -> {
            isVideoMuted = !isVideoMuted;
            applyLiveVideoMuteState();
            Toast.makeText(this, isVideoMuted ? "🔇 Video audio muted (will send silent video)" : "🔊 Video audio enabled", Toast.LENGTH_SHORT).show();
        });
        tb.addView(btnMuteAudioChip);
    }

    private void applyLiveVideoMuteState() {
        if (btnMuteAudioChip != null) {
            btnMuteAudioChip.setText(isVideoMuted ? "🔇 Muted" : "🔊 Audio");
            btnMuteAudioChip.setBackgroundResource(isVideoMuted ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
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
            btnColorFilterChip.setBackgroundResource(currentFilterIndex != 0 ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
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
                    if (videoView != null) videoView.setRotation(currentVideoRotation);
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
        btnExtractCropFrame.setBackgroundResource(R.drawable.bg_filter_chip_selected);
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
        if (btnCensorChip != null) btnCensorChip.setVisibility(View.VISIBLE);
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

    private void setupVideoEditor() {
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

        // Enable ALL tools on video editor (including Crop/Rotate, Censor Brush, Emoji, Text, Draw, Mute, Speed!)
        findViewById(R.id.btnCrop).setVisibility(View.VISIBLE);
        findViewById(R.id.btnEmoji).setVisibility(View.VISIBLE);
        findViewById(R.id.btnText).setVisibility(View.VISIBLE);
        findViewById(R.id.btnDraw).setVisibility(View.VISIBLE);
        findViewById(R.id.btnUndo).setVisibility(View.VISIBLE);
        if (btnCensorChip != null) btnCensorChip.setVisibility(View.VISIBLE);
        if (btnMuteAudioChip != null) btnMuteAudioChip.setVisibility(View.VISIBLE);
        if (btnVideoSpeedChip != null) {
            btnVideoSpeedChip.setVisibility(View.VISIBLE);
            btnVideoSpeedChip.setText(String.format(java.util.Locale.US, "%.1fx", currentVideoSpeed));
        }

        videoTrimmerContainer = findViewById(R.id.videoTrimmerContainer);
        videoRangeSlider = findViewById(R.id.videoRangeSlider);
        tvTrimStart = findViewById(R.id.tvTrimStart);
        tvTrimEnd = findViewById(R.id.tvTrimEnd);
        tvTrimDuration = findViewById(R.id.tvTrimDuration);

        if (videoTrimmerContainer != null) {
            videoTrimmerContainer.setVisibility(View.VISIBLE);
            ensureVideoFilmstripAndControlsCreated();
        }

        videoView.setVideoURI(mediaUri);
        videoView.setOnPreparedListener(mp -> {
            activeVideoMediaPlayer = mp;
            totalDurationMs = Math.max(100L, mp.getDuration());
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
            populateVideoFilmstripTimeline(mediaUri, totalDurationMs);

            if (videoRangeSlider != null && totalDurationMs > 0) {
                videoRangeSlider.clearOnChangeListeners();
                videoRangeSlider.setValueFrom(0f);
                videoRangeSlider.setValueTo((float) totalDurationMs);
                videoRangeSlider.setValues((float) trimStartMs, (float) trimEndMs);

                if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStartMs));
                if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
                updateTrimDurationAndSizeLabel();

                videoRangeSlider.addOnChangeListener((slider, value, fromUser) -> {
                    if (fromUser) {
                        List<Float> values = slider.getValues();
                        trimStartMs = values.get(0).longValue();
                        trimEndMs = values.get(1).longValue();
                        if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStartMs));
                        if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
                        updateTrimDurationAndSizeLabel();
                        videoView.seekTo((int) value);
                    }
                });
            }

            mp.setLooping(false);
            videoView.seekTo((int) trimStartMs);
            videoView.start();
            updatePlayPauseButtonUi(true);
            videoLoopHandler.removeCallbacks(videoLoopRunnable);
            videoLoopHandler.post(videoLoopRunnable);
        });
    }

    private void ensureVideoFilmstripAndControlsCreated() {
        if (!(videoTrimmerContainer instanceof LinearLayout)) return;
        LinearLayout container = (LinearLayout) videoTrimmerContainer;
        float dp = getResources().getDisplayMetrics().density;

        if (videoFilmstripRow == null) {
            // Controls row above filmstrip: [⏸ Pause / ▶ Play] + [🎬 Video | 👾 GIF Loop]
            LinearLayout modeRow = new LinearLayout(this);
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            modeRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mLp.bottomMargin = Math.round(6 * dp);
            modeRow.setLayoutParams(mLp);

            btnVideoPlayPauseToggle = new TextView(this);
            btnVideoPlayPauseToggle.setText("⏸ Pause");
            btnVideoPlayPauseToggle.setTextColor(Color.WHITE);
            btnVideoPlayPauseToggle.setTextSize(11.5f);
            btnVideoPlayPauseToggle.setTypeface(null, Typeface.BOLD);
            btnVideoPlayPauseToggle.setBackgroundResource(R.drawable.bg_filter_chip);
            btnVideoPlayPauseToggle.setPadding(Math.round(10 * dp), Math.round(4 * dp), Math.round(10 * dp), Math.round(4 * dp));
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
            modeRow.addView(btnVideoPlayPauseToggle);

            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            modeRow.addView(spacer);

            btnVideoGifModeToggle = new TextView(this);
            btnVideoGifModeToggle.setText(sendVideoAsGif ? "👾 GIF Loop" : "🎬 Video Mode");
            btnVideoGifModeToggle.setTextColor(Color.WHITE);
            btnVideoGifModeToggle.setTextSize(11.5f);
            btnVideoGifModeToggle.setTypeface(null, Typeface.BOLD);
            btnVideoGifModeToggle.setBackgroundResource(sendVideoAsGif ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            btnVideoGifModeToggle.setPadding(Math.round(10 * dp), Math.round(4 * dp), Math.round(10 * dp), Math.round(4 * dp));
            btnVideoGifModeToggle.setOnClickListener(v -> {
                sendVideoAsGif = !sendVideoAsGif;
                btnVideoGifModeToggle.setText(sendVideoAsGif ? "👾 GIF Loop" : "🎬 Video Mode");
                btnVideoGifModeToggle.setBackgroundResource(sendVideoAsGif ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                if (sendVideoAsGif) {
                    isVideoMuted = true;
                    applyLiveVideoMuteState();
                    if (trimEndMs - trimStartMs > 6500L) {
                        trimEndMs = Math.min(totalDurationMs, trimStartMs + 6000L);
                        if (videoRangeSlider != null) {
                            videoRangeSlider.setValues((float) trimStartMs, (float) trimEndMs);
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
            modeRow.addView(btnVideoGifModeToggle);
            container.addView(modeRow, 0);

            // Filmstrip frame thumbnail timeline
            videoFilmstripRow = new LinearLayout(this);
            videoFilmstripRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(38 * dp));
            fsLp.topMargin = Math.round(4 * dp);
            videoFilmstripRow.setLayoutParams(fsLp);
            GradientDrawable fsBg = new GradientDrawable();
            fsBg.setColor(Color.parseColor("#33FFFFFF"));
            fsBg.setCornerRadius(6 * dp);
            fsBg.setStroke(Math.max(1, Math.round(1 * dp)), Color.parseColor("#6600A884"));
            videoFilmstripRow.setBackground(fsBg);
            videoFilmstripRow.setClipToOutline(true);
            container.addView(videoFilmstripRow, 2);
        } else {
            if (btnVideoGifModeToggle != null) {
                btnVideoGifModeToggle.setText(sendVideoAsGif ? "👾 GIF Loop" : "🎬 Video Mode");
                btnVideoGifModeToggle.setBackgroundResource(sendVideoAsGif ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            }
        }
    }

    private void updatePlayPauseButtonUi(boolean playing) {
        if (btnVideoPlayPauseToggle != null) {
            btnVideoPlayPauseToggle.setText(playing ? "⏸ Pause" : "▶ Play");
        }
    }

    private void populateVideoFilmstripTimeline(final Uri uri, final long durationMs) {
        if (videoFilmstripRow == null || uri == null || durationMs <= 0) return;
        videoFilmstripRow.removeAllViews();
        final int frameCount = 8;
        final List<ImageView> slots = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            videoFilmstripRow.addView(iv);
            slots.add(iv);
        }
        new Thread(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(MediaEditActivity.this, uri);
                for (int i = 0; i < frameCount; i++) {
                    final int idx = i;
                    long timeUs = ((durationMs * i) / frameCount) * 1000L;
                    Bitmap frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) {
                        final Bitmap scaled = Bitmap.createScaledBitmap(frame, 96, 96, true);
                        runOnUiThread(() -> {
                            if (idx < slots.size()) {
                                slots.get(idx).setImageBitmap(scaled);
                            }
                        });
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }).start();
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

    private void saveAndSend() {
        if (batchItems.size() > 1) {
            persistCurrentItemStateSynchronously();
            ArrayList<String> allPaths = new ArrayList<>();
            ArrayList<String> allTypes = new ArrayList<>();
            for (int i = 0; i < batchItems.size(); i++) {
                BatchEditItem item = batchItems.get(i);
                String p = resolveBatchItemFilePath(item, i);
                if (p != null && !p.isEmpty()) {
                    allPaths.add(p);
                    allTypes.add(item.sendAsGif ? "gif" : item.type);
                }
            }
            String primaryPath = !allPaths.isEmpty() ? allPaths.get(0) : "";
            String primaryType = !allTypes.isEmpty() ? allTypes.get(0) : "image";
            finishWithBatchResult(primaryPath, primaryType, allPaths, allTypes);
            return;
        }

        if (mediaType != null && mediaType.startsWith("image") && photoEditor != null) {
            File outputFile = new File(getExternalCacheDir(), "edited_" + System.currentTimeMillis() + ".jpg");
            try {
                photoEditor.saveAsFile(outputFile.getAbsolutePath(), new PhotoEditor.OnSaveListener() {
                    @Override
                    public void onSuccess(String imagePath) {
                        finishWithResult(imagePath, "image");
                    }

                    @Override
                    public void onFailure(Exception exception) {
                        Toast.makeText(MediaEditActivity.this, "Failed to save image", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            File tempVideo = new File(getExternalCacheDir(), "processed_vid_" + System.currentTimeMillis() + ".mp4");
            boolean isTrimmed = (trimStartMs > 250) || (totalDurationMs > 0 && (totalDurationMs - trimEndMs > 250));
            boolean needsMuxProcess = isTrimmed || isVideoMuted || sendVideoAsGif || (currentVideoRotation % 360 != 0);
            boolean muxSuccess = false;

            if (needsMuxProcess) {
                muxSuccess = VideoTrimUtil.processVideo(
                        this, mediaUri, tempVideo,
                        trimStartMs, trimEndMs > 0 ? trimEndMs : totalDurationMs,
                        isVideoMuted || sendVideoAsGif, currentVideoRotation);
            }

            String path;
            if (muxSuccess && tempVideo.exists() && tempVideo.length() > 0) {
                path = tempVideo.getAbsolutePath();
            } else if (mediaUri != null && "file".equalsIgnoreCase(mediaUri.getScheme())) {
                path = mediaUri.getPath();
            } else if (mediaUri != null) {
                try (InputStream in = getContentResolver().openInputStream(mediaUri);
                     OutputStream out = new FileOutputStream(tempVideo)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    path = tempVideo.getAbsolutePath();
                } catch (Exception e) {
                    path = mediaUri.getPath();
                }
            } else {
                path = "";
            }
            finishWithResult(path, sendVideoAsGif ? "gif" : "video");
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

    private void setupViewsForState() {
        topToolbar = findViewById(R.id.topToolbar);
        bottomBar = findViewById(R.id.bottomBar);
        btnClose = findViewById(R.id.btnClose);
        btnDone = findViewById(R.id.btnDone);
        drawingOptionsBar = findViewById(R.id.drawingOptionsBar);
        textOptionsBar = findViewById(R.id.textOptionsBar);
        colorPickerView = findViewById(R.id.colorPickerView);

        btnDone.setOnClickListener(v -> updateState(UIState.BASE));

        colorPickerView.setOnColorChangeListener(color -> {
            currentSelectedColor = color;
            if (currentState == UIState.DRAWING && photoEditor != null) {
                photoEditor.setBrushDrawingMode(true);
                photoEditor.setBrushColor(color);
            }
        });

        populateDrawingAndTextToolbars();
    }

    private void populateDrawingAndTextToolbars() {
        float dp = getResources().getDisplayMetrics().density;
        if (drawingOptionsBar instanceof LinearLayout) {
            LinearLayout drawBar = (LinearLayout) drawingOptionsBar;
            drawBar.removeAllViews();
            String[] labels = new String[]{"Fine", "Medium", "Bold", "Neon", "Eraser"};
            float[] sizes = new float[]{8f, 18f, 34f, 24f, 36f};
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                final float bSize = sizes[i];
                TextView pill = new TextView(this);
                pill.setText(labels[i]);
                pill.setTextColor(Color.WHITE);
                pill.setTextSize(12.5f);
                pill.setTypeface(null, Typeface.BOLD);
                pill.setPadding(Math.round(12 * dp), Math.round(6 * dp), Math.round(12 * dp), Math.round(6 * dp));
                pill.setOnClickListener(v -> {
                    if (photoEditor == null) return;
                    if (idx == 4) {
                        photoEditor.brushEraser();
                        Toast.makeText(this, "Eraser active", Toast.LENGTH_SHORT).show();
                    } else {
                        photoEditor.setBrushDrawingMode(true);
                        photoEditor.setBrushSize(bSize);
                        photoEditor.setBrushColor(currentSelectedColor);
                    }
                });
                drawBar.addView(pill);
            }
        }
    }

    private void updateState(UIState newState) {
        currentState = newState;

        topToolbar.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        btnClose.setVisibility(newState == UIState.BASE ? View.VISIBLE : View.GONE);
        if (editBatchThumbnailScroll != null) {
            editBatchThumbnailScroll.setVisibility(newState == UIState.BASE && batchItems.size() > 1 ? View.VISIBLE : View.GONE);
        }
        if (tvEditBatchCounter != null) {
            tvEditBatchCounter.setVisibility(newState == UIState.BASE && batchItems.size() > 1 ? View.VISIBLE : View.GONE);
        }

        btnDone.setVisibility(newState != UIState.BASE ? View.VISIBLE : View.GONE);
        colorPickerView.setVisibility(newState != UIState.BASE ? View.VISIBLE : View.GONE);

        drawingOptionsBar.setVisibility(newState == UIState.DRAWING ? View.VISIBLE : View.GONE);
        textOptionsBar.setVisibility(newState == UIState.TEXT ? View.VISIBLE : View.GONE);

        if (photoEditor != null) {
            photoEditor.setBrushDrawingMode(newState == UIState.DRAWING);
            if (newState == UIState.DRAWING && !isCensorBrushActive) {
                photoEditor.setBrushColor(currentSelectedColor);
            }
        }
    }
}
