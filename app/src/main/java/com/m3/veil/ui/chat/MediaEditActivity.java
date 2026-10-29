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

    public static final String RESULT_EDITED_FILE_PATH = "result_edited_file_path";
    public static final String RESULT_MEDIA_TYPE = "result_media_type";
    public static final String RESULT_CAPTION = "result_caption";
    public static final String RESULT_IS_VIEW_ONCE = "result_is_view_once";
    public static final String RESULT_EDITED_BATCH_PATHS = "result_edited_batch_paths";
    public static final String RESULT_EDITED_BATCH_TYPES = "result_edited_batch_types";

    private static class BatchEditItem {
        Uri uri;
        String type; // "image" or "video"
        String savedPath;
        long trimStartMs = 0;
        long trimEndMs = 0;
        long totalDurationMs = 0;

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
    private EditText captionEditText;
    private HorizontalScrollView editBatchThumbnailScroll;
    private LinearLayout editBatchThumbnailStrip;
    private TextView tvEditBatchCounter;
    private boolean isViewOnce = false;

    private TextView btnCensorChip;
    private TextView btnMuteAudioChip;

    // Video Trimmer members
    private View videoTrimmerContainer;
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        configureDarkMediaSystemBars();
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
            if (mediaUri != null && mediaType.startsWith("image") && photoEditor != null) {
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
            TextEditorDialogFragment dialog = TextEditorDialogFragment.show(this, "", Color.WHITE);
            dialog.setOnTextEditorListener((newText, colorCode) -> {
                if (photoEditor != null) {
                    photoEditor.addText(newText, colorCode);
                }
            });
        });

        btnDrawView.setOnClickListener(v -> updateState(UIState.DRAWING));
        btnUndoView.setOnClickListener(v -> {
            if (photoEditor != null) {
                photoEditor.undo();
            }
        });
    }

    private void extractMediaBatchFromIntent() {
        batchItems.clear();
        Intent intent = getIntent();
        ArrayList<String> rawUris = intent.getStringArrayListExtra(EXTRA_BATCH_URIS);
        ArrayList<String> rawTypes = intent.getStringArrayListExtra(EXTRA_BATCH_TYPES);
        if (rawUris != null && !rawUris.isEmpty()) {
            for (int i = 0; i < rawUris.size(); i++) {
                String uStr = rawUris.get(i);
                if (uStr == null || uStr.isEmpty()) continue;
                Uri u = uStr.startsWith("/") ? Uri.fromFile(new File(uStr)) : Uri.parse(uStr);
                String t = (rawTypes != null && i < rawTypes.size()) ? rawTypes.get(i) : "image";
                batchItems.add(new BatchEditItem(u, t));
            }
        }
        if (batchItems.isEmpty()) {
            Uri singleUri = intent.getParcelableExtra(EXTRA_MEDIA_URI);
            String singleType = intent.getStringExtra(EXTRA_MEDIA_TYPE);
            if (singleUri != null) {
                batchItems.add(new BatchEditItem(singleUri, singleType != null ? singleType : "image"));
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

    private void initToolbarDynamicChips() {
        if (!(topToolbar instanceof ViewGroup)) return;
        ViewGroup tb = (ViewGroup) topToolbar;
        float dp = getResources().getDisplayMetrics().density;

        btnCensorChip = new TextView(this);
        btnCensorChip.setText("🕵️ Censor");
        btnCensorChip.setTextColor(Color.WHITE);
        btnCensorChip.setTextSize(12.5f);
        btnCensorChip.setTypeface(null, Typeface.BOLD);
        btnCensorChip.setBackgroundResource(R.drawable.bg_filter_chip);
        btnCensorChip.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * dp);
        btnCensorChip.setLayoutParams(lp);
        btnCensorChip.setOnClickListener(v -> {
            if (photoEditor == null) return;
            isCensorBrushActive = !isCensorBrushActive;
            if (isCensorBrushActive) {
                updateState(UIState.DRAWING);
                photoEditor.setBrushSize(64f);
                photoEditor.setBrushColor(Color.parseColor("#E6111827"));
                btnCensorChip.setBackgroundResource(R.drawable.bg_filter_chip_selected);
                Toast.makeText(this, "🕵️ Censor Brush active: swipe over faces or text", Toast.LENGTH_SHORT).show();
            } else {
                photoEditor.setBrushSize(16f);
                photoEditor.setBrushColor(currentSelectedColor);
                btnCensorChip.setBackgroundResource(R.drawable.bg_filter_chip);
            }
        });
        tb.addView(btnCensorChip);

        btnMuteAudioChip = new TextView(this);
        btnMuteAudioChip.setText("🔊 Audio On");
        btnMuteAudioChip.setTextColor(Color.WHITE);
        btnMuteAudioChip.setTextSize(12.5f);
        btnMuteAudioChip.setTypeface(null, Typeface.BOLD);
        btnMuteAudioChip.setBackgroundResource(R.drawable.bg_filter_chip);
        btnMuteAudioChip.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
        btnMuteAudioChip.setOnClickListener(v -> {
            isVideoMuted = !isVideoMuted;
            btnMuteAudioChip.setText(isVideoMuted ? "🔇 Audio Muted" : "🔊 Audio On");
            btnMuteAudioChip.setBackgroundResource(isVideoMuted ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            Toast.makeText(this, isVideoMuted ? "🔇 Video audio muted" : "🔊 Video audio enabled", Toast.LENGTH_SHORT).show();
        });
        tb.addView(btnMuteAudioChip);
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
        if ("video".equals(current.type)) {
            current.trimStartMs = trimStartMs;
            current.trimEndMs = trimEndMs;
            current.totalDurationMs = totalDurationMs;
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
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
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
        if (mediaType != null && mediaType.startsWith("video")) {
            setupVideoEditor();
        } else {
            setupImageEditor();
        }
    }

    private void setupImageEditor() {
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
        options.setCompressionQuality(90);
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
        photoEditorView.setVisibility(View.GONE);

        findViewById(R.id.btnCrop).setVisibility(View.GONE);
        findViewById(R.id.btnEmoji).setVisibility(View.GONE);
        findViewById(R.id.btnText).setVisibility(View.GONE);
        findViewById(R.id.btnDraw).setVisibility(View.GONE);
        findViewById(R.id.btnUndo).setVisibility(View.GONE);
        if (btnCensorChip != null) btnCensorChip.setVisibility(View.GONE);
        if (btnMuteAudioChip != null) btnMuteAudioChip.setVisibility(View.VISIBLE);

        videoTrimmerContainer = findViewById(R.id.videoTrimmerContainer);
        videoRangeSlider = findViewById(R.id.videoRangeSlider);
        tvTrimStart = findViewById(R.id.tvTrimStart);
        tvTrimEnd = findViewById(R.id.tvTrimEnd);
        tvTrimDuration = findViewById(R.id.tvTrimDuration);

        if (videoTrimmerContainer != null) {
            videoTrimmerContainer.setVisibility(View.VISIBLE);
        }

        videoView.setVideoURI(mediaUri);
        videoView.setOnPreparedListener(mp -> {
            totalDurationMs = mp.getDuration();
            BatchEditItem item = (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size())
                    ? batchItems.get(currentBatchIndex) : null;
            if (item != null && item.trimEndMs > 0 && item.totalDurationMs == totalDurationMs) {
                trimStartMs = item.trimStartMs;
                trimEndMs = item.trimEndMs;
            } else {
                trimStartMs = 0;
                trimEndMs = totalDurationMs;
            }

            if (videoRangeSlider != null && totalDurationMs > 0) {
                videoRangeSlider.clearOnChangeListeners();
                videoRangeSlider.setValueFrom(0f);
                videoRangeSlider.setValueTo((float) totalDurationMs);
                videoRangeSlider.setValues((float) trimStartMs, (float) trimEndMs);

                if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStartMs));
                if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
                if (tvTrimDuration != null) tvTrimDuration.setText("Length: " + formatTime(Math.max(0, trimEndMs - trimStartMs)));

                videoRangeSlider.addOnChangeListener((slider, value, fromUser) -> {
                    if (fromUser) {
                        List<Float> values = slider.getValues();
                        trimStartMs = values.get(0).longValue();
                        trimEndMs = values.get(1).longValue();
                        if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStartMs));
                        if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEndMs));
                        long range = Math.max(0, trimEndMs - trimStartMs);
                        if (tvTrimDuration != null) tvTrimDuration.setText("Trimmed: " + formatTime(range));

                        videoView.seekTo((int) value);
                    }
                });
            }

            mp.setLooping(false);
            videoView.start();
            videoLoopHandler.post(videoLoopRunnable);
        });
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
        if (item.savedPath != null && new File(item.savedPath).exists()) {
            return item.savedPath;
        }
        if ("video".equals(item.type)) {
            File tempVideo = new File(getExternalCacheDir(), "trimmed_vid_" + idx + "_" + System.currentTimeMillis() + ".mp4");
            boolean isTrimmed = (item.trimStartMs > 500) || (item.totalDurationMs > 0 && (item.totalDurationMs - item.trimEndMs > 500));
            if (isTrimmed && VideoTrimUtil.trimVideo(this, item.uri, tempVideo, item.trimStartMs, item.trimEndMs)
                    && tempVideo.exists() && tempVideo.length() > 0) {
                item.savedPath = tempVideo.getAbsolutePath();
                return item.savedPath;
            }
        }
        if ("file".equalsIgnoreCase(item.uri.getScheme()) && item.uri.getPath() != null) {
            File f = new File(item.uri.getPath());
            if (f.exists()) {
                item.savedPath = f.getAbsolutePath();
                return item.savedPath;
            }
        }
        String ext = "video".equals(item.type) ? ".mp4" : ".jpg";
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
                    allTypes.add(item.type);
                }
            }
            String primaryPath = !allPaths.isEmpty() ? allPaths.get(0) : "";
            String primaryType = !allTypes.isEmpty() ? allTypes.get(0) : "image";
            finishWithBatchResult(primaryPath, primaryType, allPaths, allTypes);
            return;
        }

        if (mediaType.startsWith("image") && photoEditor != null) {
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
            File tempVideo = new File(getExternalCacheDir(), "trimmed_vid_" + System.currentTimeMillis() + ".mp4");
            boolean isTrimmed = (trimStartMs > 500) || (totalDurationMs - trimEndMs > 500);
            boolean trimSuccess = false;

            if (isTrimmed) {
                trimSuccess = VideoTrimUtil.trimVideo(this, mediaUri, tempVideo, trimStartMs, trimEndMs);
            }

            String path;
            if (trimSuccess && tempVideo.exists() && tempVideo.length() > 0) {
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
            finishWithResult(path, "video");
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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == UCrop.REQUEST_CROP && data != null) {
            final Uri resultUri = UCrop.getOutput(data);
            if (resultUri != null && photoEditorView != null) {
                mediaUri = resultUri;
                if (currentBatchIndex >= 0 && currentBatchIndex < batchItems.size()) {
                    batchItems.get(currentBatchIndex).uri = resultUri;
                    if ( resultUri.getPath() != null) {
                        batchItems.get(currentBatchIndex).savedPath = resultUri.getPath();
                    }
                }
                photoEditor.clearAllViews();
                photoEditorView.getSource().setImageURI(mediaUri);
                buildBatchThumbnailStrip();
            }
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
                photoEditor.setBrushColor(color);
            }
        });
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
        }
    }
}
