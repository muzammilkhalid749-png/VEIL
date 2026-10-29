package com.m3.veil.ui.chat;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.m3.veil.R;
import com.m3.veil.ui.views.ZoomableImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WhatsApp & Telegram style in-app full-screen media & grouped album viewer.
 * Supports both horizontal swipe (ViewPager2) and vertical scroll (RecyclerView)
 * at the same time to view photos, animated GIFs, and play videos with full controls.
 */
public class MediaViewerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_MEDIA_TYPE = "extra_media_type"; // "image", "video", or "gif"
    public static final String EXTRA_SENDER_NAME = "extra_sender_name";
    public static final String EXTRA_TIMESTAMP = "extra_timestamp";
    public static final String EXTRA_CAPTION = "extra_caption";
    public static final String EXTRA_IS_VIEW_ONCE = "extra_is_view_once";
    public static final String EXTRA_MESSAGE_ID = "extra_message_id";
    public static final String EXTRA_CHAT_ID = "extra_chat_id";
    public static final String EXTRA_PEER_ID = "extra_peer_id";

    // Grouped Album extras
    public static final String EXTRA_ALBUM_PATHS = "extra_album_paths";
    public static final String EXTRA_ALBUM_TYPES = "extra_album_types";
    public static final String EXTRA_ALBUM_CAPTIONS = "extra_album_captions";
    public static final String EXTRA_INITIAL_INDEX = "extra_initial_index";

    private static class AlbumMediaEntry {
        final String path;
        final String type;
        final String caption;

        AlbumMediaEntry(String path, String type, String caption) {
            this.path = path;
            this.type = type != null ? type : "image";
            this.caption = caption != null ? caption : "";
        }
    }

    private final List<AlbumMediaEntry> albumItems = new ArrayList<>();
    private int currentMediaIndex = 0;
    private boolean isVerticalScrollMode = false;

    private boolean isViewOnce = false;
    private String messageId;
    private String chatId;
    private String peerId;
    private boolean hasDestroyedViewOnce = false;

    private ViewPager2 mediaViewPager;
    private RecyclerView mediaVerticalRecyclerView;
    private HorizontalScrollView albumThumbnailScroll;
    private LinearLayout albumThumbnailStrip;
    private TextView tvAlbumCounter;
    private TextView btnToggleScrollMode;

    private ZoomableImageView photoViewer;
    private FrameLayout videoContainer;
    private VideoView videoViewer;
    private FrameLayout videoCenterPlayBtn;
    private ImageView ivCenterPlayIcon;
    private LinearLayout videoControlsBar;
    private ImageView btnBottomPlayPause;
    private TextView tvVideoElapsed;
    private TextView tvVideoTotal;
    private SeekBar videoSeekBar;

    private LinearLayout topBar;
    private ImageView btnBack;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private ImageView btnShare;
    private ImageView btnSave;

    private LinearLayout bottomCaptionBar;
    private TextView tvCaption;
    private ProgressBar viewerProgressBar;

    private String filePath;
    private String mediaType;
    private String senderName;
    private String timestamp;
    private String caption;

    private final Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable videoProgressRunnable;
    private boolean isControlsVisible = true;
    private MediaPlayer activeMediaPlayer;
    private VideoView activeAlbumVideoView;
    private float currentVideoSpeed = 1.0f;
    private TextView btnSpeedChip;
    private TextView btnPipChip;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        initViews();
        handleWindowInsets();
        extractExtras();
        setupActions();
        bindMedia();
    }

    private void handleWindowInsets() {
        View root = findViewById(R.id.mediaViewerRoot);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            float dp = getResources().getDisplayMetrics().density;
            if (topBar != null) {
                topBar.setPadding(
                        topBar.getPaddingStart(),
                        systemBars.top + (int) (4 * dp),
                        topBar.getPaddingEnd(),
                        topBar.getPaddingBottom()
                );
            }
            if (bottomCaptionBar != null) {
                bottomCaptionBar.setPadding(
                        bottomCaptionBar.getPaddingStart(),
                        bottomCaptionBar.getPaddingTop(),
                        bottomCaptionBar.getPaddingEnd(),
                        systemBars.bottom + (int) (12 * dp)
                );
            }
            if (videoControlsBar != null) {
                int extraBottom = (albumItems.size() > 1) ? (int) (68 * dp) : (int) (12 * dp);
                videoControlsBar.setPadding(
                        videoControlsBar.getPaddingStart(),
                        videoControlsBar.getPaddingTop(),
                        videoControlsBar.getPaddingEnd(),
                        systemBars.bottom + extraBottom
                );
            }
            return insets;
        });
    }

    private void initViews() {
        mediaViewPager = findViewById(R.id.mediaViewPager);
        mediaVerticalRecyclerView = findViewById(R.id.mediaVerticalRecyclerView);
        albumThumbnailScroll = findViewById(R.id.albumThumbnailScroll);
        albumThumbnailStrip = findViewById(R.id.albumThumbnailStrip);
        tvAlbumCounter = findViewById(R.id.tvAlbumCounter);
        btnToggleScrollMode = findViewById(R.id.btnToggleScrollMode);

        photoViewer = findViewById(R.id.photoViewer);
        videoContainer = findViewById(R.id.videoContainer);
        videoViewer = findViewById(R.id.videoViewer);
        videoCenterPlayBtn = findViewById(R.id.videoCenterPlayBtn);
        ivCenterPlayIcon = findViewById(R.id.ivCenterPlayIcon);
        videoControlsBar = findViewById(R.id.videoControlsBar);
        btnBottomPlayPause = findViewById(R.id.btnBottomPlayPause);
        tvVideoElapsed = findViewById(R.id.tvVideoElapsed);
        tvVideoTotal = findViewById(R.id.tvVideoTotal);
        videoSeekBar = findViewById(R.id.videoSeekBar);

        topBar = findViewById(R.id.topBar);
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        btnShare = findViewById(R.id.btnShare);
        btnSave = findViewById(R.id.btnSave);

        bottomCaptionBar = findViewById(R.id.bottomCaptionBar);
        tvCaption = findViewById(R.id.tvCaption);
        viewerProgressBar = findViewById(R.id.viewerProgressBar);
    }

    private void extractExtras() {
        Intent intent = getIntent();
        filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE);
        senderName = intent.getStringExtra(EXTRA_SENDER_NAME);
        timestamp = intent.getStringExtra(EXTRA_TIMESTAMP);
        caption = intent.getStringExtra(EXTRA_CAPTION);
        isViewOnce = intent.getBooleanExtra(EXTRA_IS_VIEW_ONCE, false);
        messageId = intent.getStringExtra(EXTRA_MESSAGE_ID);
        chatId = intent.getStringExtra(EXTRA_CHAT_ID);
        peerId = intent.getStringExtra(EXTRA_PEER_ID);

        ArrayList<String> albumPaths = intent.getStringArrayListExtra(EXTRA_ALBUM_PATHS);
        ArrayList<String> albumTypes = intent.getStringArrayListExtra(EXTRA_ALBUM_TYPES);
        ArrayList<String> albumCaptions = intent.getStringArrayListExtra(EXTRA_ALBUM_CAPTIONS);
        int initIdx = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0);

        albumItems.clear();
        if (albumPaths != null && !albumPaths.isEmpty()) {
            for (int i = 0; i < albumPaths.size(); i++) {
                String p = albumPaths.get(i);
                if (p == null || p.isEmpty()) continue;
                String t = (albumTypes != null && i < albumTypes.size()) ? albumTypes.get(i) : (p.endsWith(".mp4") ? "video" : "image");
                String c = (albumCaptions != null && i < albumCaptions.size()) ? albumCaptions.get(i) : (i == 0 && caption != null ? caption : "");
                albumItems.add(new AlbumMediaEntry(p, t, c));
            }
            currentMediaIndex = Math.max(0, Math.min(initIdx, albumItems.size() - 1));
            if (!albumItems.isEmpty()) {
                AlbumMediaEntry active = albumItems.get(currentMediaIndex);
                filePath = active.path;
                mediaType = active.type;
                if (active.caption != null && !active.caption.isEmpty()) {
                    caption = active.caption;
                }
            }
        } else if (filePath != null && !filePath.isEmpty()) {
            albumItems.add(new AlbumMediaEntry(filePath, mediaType != null ? mediaType : "image", caption != null ? caption : ""));
            currentMediaIndex = 0;
        }

        if (isViewOnce) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            tvTitle.setText("video".equals(mediaType) ? "① View once video" : "① View once photo");
            tvSubtitle.setText("Disappears when closed");
            tvSubtitle.setVisibility(View.VISIBLE);
            if (btnShare != null) btnShare.setVisibility(View.GONE);
            if (btnSave != null) btnSave.setVisibility(View.GONE);
        } else {
            if (senderName != null && !senderName.isEmpty()) {
                tvTitle.setText(senderName);
            } else {
                tvTitle.setText("video".equals(mediaType) ? "Video" : "Photo");
            }

            if (timestamp != null && !timestamp.isEmpty()) {
                tvSubtitle.setText(timestamp);
                tvSubtitle.setVisibility(View.VISIBLE);
            } else {
                tvSubtitle.setVisibility(View.GONE);
            }
        }

        updateActiveItemHeaderAndCaption();
    }

    private void updateActiveItemHeaderAndCaption() {
        if (!albumItems.isEmpty() && currentMediaIndex >= 0 && currentMediaIndex < albumItems.size()) {
            AlbumMediaEntry entry = albumItems.get(currentMediaIndex);
            filePath = entry.path;
            mediaType = entry.type;
            if (entry.caption != null && !entry.caption.isEmpty()) {
                caption = entry.caption;
            }
        }

        if (albumItems.size() > 1) {
            if (tvAlbumCounter != null) {
                tvAlbumCounter.setVisibility(View.VISIBLE);
                tvAlbumCounter.setText((currentMediaIndex + 1) + " / " + albumItems.size());
            }
            if (btnToggleScrollMode != null) {
                btnToggleScrollMode.setVisibility(View.VISIBLE);
                btnToggleScrollMode.setText(isVerticalScrollMode ? "⇅ Scroll" : "⇄ Swipe");
            }
            if (!isViewOnce && tvSubtitle != null) {
                String baseTime = (timestamp != null && !timestamp.isEmpty()) ? (timestamp + " • ") : "";
                tvSubtitle.setText(baseTime + "Item " + (currentMediaIndex + 1) + " of " + albumItems.size());
                tvSubtitle.setVisibility(View.VISIBLE);
            }
        } else {
            if (tvAlbumCounter != null) tvAlbumCounter.setVisibility(View.GONE);
            if (btnToggleScrollMode != null) btnToggleScrollMode.setVisibility(View.GONE);
        }

        if (btnSpeedChip != null) {
            btnSpeedChip.setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
        }
        if (btnPipChip != null) {
            btnPipChip.setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
        }

        boolean hasCaption = caption != null && !caption.trim().isEmpty();
        boolean hasAlbumStrip = albumItems.size() > 1 && !isViewOnce;
        if (tvCaption != null) {
            tvCaption.setText(hasCaption ? caption : "");
            tvCaption.setVisibility(hasCaption ? View.VISIBLE : View.GONE);
        }
        if (bottomCaptionBar != null) {
            bottomCaptionBar.setVisibility((hasCaption || hasAlbumStrip) && isControlsVisible ? View.VISIBLE : View.GONE);
        }
        highlightActiveThumbnail(currentMediaIndex);
    }

    @Override
    public void finish() {
        handleViewOnceDestruction();
        super.finish();
    }

    private synchronized void handleViewOnceDestruction() {
        if (!isViewOnce || hasDestroyedViewOnce) return;
        hasDestroyedViewOnce = true;
        if (messageId != null && chatId != null) {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).markBlinkMessageViewed(chatId, messageId);
            if (peerId != null) {
                com.m3.veil.webrtc.WebRtcManager.getInstance(this).sendBlinkOpenedReceipt(peerId, chatId, messageId);
            }
        }
        if (filePath != null) {
            try {
                File f = new File(filePath);
                if (f.exists()) f.delete();
            } catch (Exception ignored) {}
        }
        setResult(RESULT_OK);
    }

    private void navigateMedia(int delta) {
        if (albumItems.size() <= 1) return;
        int nextIdx = Math.max(0, Math.min(albumItems.size() - 1, currentMediaIndex + delta));
        if (nextIdx == currentMediaIndex) return;
        selectAlbumIndex(nextIdx, true);
    }

    private void selectAlbumIndex(int index, boolean smooth) {
        if (index < 0 || index >= albumItems.size()) return;
        currentMediaIndex = index;
        updateActiveItemHeaderAndCaption();
        if (mediaViewPager != null && mediaViewPager.getVisibility() == View.VISIBLE) {
            if (mediaViewPager.getCurrentItem() != index) {
                mediaViewPager.setCurrentItem(index, smooth);
            }
        }
        if (mediaVerticalRecyclerView != null && mediaVerticalRecyclerView.getVisibility() == View.VISIBLE) {
            if (smooth) {
                mediaVerticalRecyclerView.smoothScrollToPosition(index);
            } else {
                mediaVerticalRecyclerView.scrollToPosition(index);
            }
        }
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> finish());
        btnShare.setOnClickListener(v -> shareFile());
        btnSave.setOnClickListener(v -> saveToGallery());

        if (btnToggleScrollMode != null) {
            btnToggleScrollMode.setOnClickListener(v -> {
                isVerticalScrollMode = !isVerticalScrollMode;
                applyAlbumScrollMode();
            });
        }

        if (topBar != null && !isViewOnce) {
            float dp = getResources().getDisplayMetrics().density;

            btnSpeedChip = new TextView(this);
            btnSpeedChip.setText("1x");
            btnSpeedChip.setTextColor(Color.WHITE);
            btnSpeedChip.setTextSize(12.5f);
            btnSpeedChip.setTypeface(null, Typeface.BOLD);
            btnSpeedChip.setBackgroundResource(R.drawable.bg_filter_chip);
            btnSpeedChip.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
            btnSpeedChip.setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
            btnSpeedChip.setOnClickListener(v -> {
                if (currentVideoSpeed == 1.0f) currentVideoSpeed = 1.5f;
                else if (currentVideoSpeed == 1.5f) currentVideoSpeed = 2.0f;
                else currentVideoSpeed = 1.0f;
                btnSpeedChip.setText(String.format(Locale.US, "%.1fx", currentVideoSpeed).replace(".0", ""));
                if (activeMediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        activeMediaPlayer.setPlaybackParams(activeMediaPlayer.getPlaybackParams().setSpeed(currentVideoSpeed));
                    } catch (Exception ignored) {}
                }
            });
            topBar.addView(btnSpeedChip, Math.max(1, topBar.getChildCount() - 2));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                btnPipChip = new TextView(this);
                btnPipChip.setText("PiP");
                btnPipChip.setTextColor(Color.WHITE);
                btnPipChip.setTextSize(12f);
                btnPipChip.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
                btnPipChip.setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
                btnPipChip.setOnClickListener(v -> {
                    try {
                        enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
                    } catch (Exception ignored) {}
                });
                topBar.addView(btnPipChip, Math.max(1, topBar.getChildCount() - 2));
            }
        }

        findViewById(R.id.mediaViewerRoot).setOnClickListener(v -> toggleControls());
        if (photoViewer != null) {
            photoViewer.setOnClickListener(v -> toggleControls());
        }
    }

    private void toggleControls() {
        isControlsVisible = !isControlsVisible;
        int vis = isControlsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        boolean hasCaption = caption != null && !caption.trim().isEmpty();
        boolean hasAlbumStrip = albumItems.size() > 1 && !isViewOnce;
        if (hasCaption || hasAlbumStrip) {
            bottomCaptionBar.setVisibility(vis);
        }
        if ("video".equals(mediaType) && albumItems.size() <= 1) {
            videoControlsBar.setVisibility(vis);
            videoCenterPlayBtn.setVisibility(isControlsVisible && (videoViewer == null || !videoViewer.isPlaying()) ? View.VISIBLE : View.GONE);
        }
    }

    private void bindMedia() {
        if (albumItems.size() > 1) {
            bindAlbumGallery();
            return;
        }

        if (mediaViewPager != null) mediaViewPager.setVisibility(View.GONE);
        if (mediaVerticalRecyclerView != null) mediaVerticalRecyclerView.setVisibility(View.GONE);
        if (albumThumbnailScroll != null) albumThumbnailScroll.setVisibility(View.GONE);

        if (filePath == null || filePath.isEmpty()) {
            Toast.makeText(this, "File not available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            Toast.makeText(this, "Media file does not exist", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if ("video".equals(mediaType)) {
            bindVideo(file);
        } else {
            bindPhoto(file);
        }
    }

    /**
     * Multi-media grouped album viewer supporting BOTH horizontal swipe (ViewPager2)
     * AND vertical scroll (RecyclerView + vertical fling/scroll gesture + thumbnail strip)
     * simultaneously so users can either swipe left/right or scroll up/down to browse photos & play videos.
     */
    private void bindAlbumGallery() {
        if (photoViewer != null) photoViewer.setVisibility(View.GONE);
        if (videoContainer != null) videoContainer.setVisibility(View.GONE);
        if (videoControlsBar != null) videoControlsBar.setVisibility(View.GONE);

        AlbumPageAdapter horizontalAdapter = new AlbumPageAdapter(false);
        mediaViewPager.setAdapter(horizontalAdapter);
        mediaViewPager.setOffscreenPageLimit(1);
        mediaViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentMediaIndex = position;
                updateActiveItemHeaderAndCaption();
            }
        });

        LinearLayoutManager verticalLm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        mediaVerticalRecyclerView.setLayoutManager(verticalLm);
        AlbumPageAdapter verticalAdapter = new AlbumPageAdapter(true);
        mediaVerticalRecyclerView.setAdapter(verticalAdapter);
        try {
            if (mediaVerticalRecyclerView.getOnFlingListener() == null) {
                new PagerSnapHelper().attachToRecyclerView(mediaVerticalRecyclerView);
            }
        } catch (Exception ignored) {}
        mediaVerticalRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = verticalLm.findFirstCompletelyVisibleItemPosition();
                    if (pos == RecyclerView.NO_POSITION) {
                        pos = verticalLm.findFirstVisibleItemPosition();
                    }
                    if (pos != RecyclerView.NO_POSITION && pos != currentMediaIndex) {
                        currentMediaIndex = pos;
                        updateActiveItemHeaderAndCaption();
                    }
                }
            }
        });

        buildAlbumThumbnailStrip();
        applyAlbumScrollMode();
        selectAlbumIndex(currentMediaIndex, false);
    }

    private void applyAlbumScrollMode() {
        if (albumItems.size() <= 1) return;
        if (btnToggleScrollMode != null) {
            btnToggleScrollMode.setText(isVerticalScrollMode ? "⇅ Scroll" : "⇄ Swipe");
        }
        if (isVerticalScrollMode) {
            mediaViewPager.setVisibility(View.GONE);
            mediaVerticalRecyclerView.setVisibility(View.VISIBLE);
            mediaVerticalRecyclerView.scrollToPosition(currentMediaIndex);
        } else {
            mediaVerticalRecyclerView.setVisibility(View.GONE);
            mediaViewPager.setVisibility(View.VISIBLE);
            mediaViewPager.setCurrentItem(currentMediaIndex, false);
        }
    }

    private void buildAlbumThumbnailStrip() {
        if (albumThumbnailScroll == null || albumThumbnailStrip == null) return;
        albumThumbnailStrip.removeAllViews();
        if (albumItems.size() <= 1 || isViewOnce) {
            albumThumbnailScroll.setVisibility(View.GONE);
            return;
        }
        albumThumbnailScroll.setVisibility(View.VISIBLE);
        if (bottomCaptionBar != null) {
            bottomCaptionBar.setVisibility(View.VISIBLE);
        }

        float dp = getResources().getDisplayMetrics().density;
        int thumbSize = Math.round(52 * dp);
        int margin = Math.round(4 * dp);

        for (int i = 0; i < albumItems.size(); i++) {
            final int idx = i;
            AlbumMediaEntry entry = albumItems.get(i);

            FrameLayout thumbFrame = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            lp.setMargins(margin, 0, margin, 0);
            thumbFrame.setLayoutParams(lp);
            thumbFrame.setPadding(Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp), Math.round(2 * dp));

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

            File f = new File(entry.path);
            if (f.exists()) {
                if ("video".equals(entry.type)) {
                    try {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        retriever.setDataSource(f.getAbsolutePath());
                        Bitmap frame = retriever.getFrameAtTime();
                        if (frame != null) iv.setImageBitmap(frame);
                        retriever.release();
                    } catch (Exception ignored) {}
                } else {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    if (bmp != null) iv.setImageBitmap(bmp);
                }
            }
            thumbFrame.addView(iv);

            if ("video".equals(entry.type)) {
                ImageView playBadge = new ImageView(this);
                int badgeSz = Math.round(18 * dp);
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSz, badgeSz, Gravity.CENTER);
                playBadge.setLayoutParams(badgeLp);
                playBadge.setImageResource(R.drawable.ic_play_arrow);
                playBadge.setColorFilter(Color.WHITE);
                playBadge.setBackgroundResource(R.drawable.bg_circle_translucent);
                thumbFrame.addView(playBadge);
            }

            thumbFrame.setOnClickListener(v -> selectAlbumIndex(idx, true));
            albumThumbnailStrip.addView(thumbFrame);
        }
        highlightActiveThumbnail(currentMediaIndex);
    }

    private void highlightActiveThumbnail(int activeIdx) {
        if (albumThumbnailStrip == null) return;
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < albumThumbnailStrip.getChildCount(); i++) {
            View child = albumThumbnailStrip.getChildAt(i);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(8 * dp);
            if (i == activeIdx) {
                border.setStroke(Math.round(2.5f * dp), Color.parseColor("#00A884"));
                border.setColor(Color.parseColor("#3300A884"));
                child.setScaleX(1.06f);
                child.setScaleY(1.06f);
                if (albumThumbnailScroll != null) {
                    final View target = child;
                    albumThumbnailScroll.post(() -> {
                        int scrollX = target.getLeft() - (albumThumbnailScroll.getWidth() - target.getWidth()) / 2;
                        albumThumbnailScroll.smoothScrollTo(Math.max(0, scrollX), 0);
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

    private class AlbumPageAdapter extends RecyclerView.Adapter<AlbumPageAdapter.PageHolder> {
        private final boolean verticalScrollableCard;

        AlbumPageAdapter(boolean verticalScrollableCard) {
            this.verticalScrollableCard = verticalScrollableCard;
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float dp = getResources().getDisplayMetrics().density;
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.setBackgroundColor(Color.BLACK);

            ZoomableImageView pagePhoto = new ZoomableImageView(parent.getContext());
            pagePhoto.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(pagePhoto);

            FrameLayout pageVideoWrap = new FrameLayout(parent.getContext());
            pageVideoWrap.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            pageVideoWrap.setVisibility(View.GONE);

            VideoView pageVideo = new VideoView(parent.getContext());
            FrameLayout.LayoutParams vLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            pageVideo.setLayoutParams(vLp);
            pageVideoWrap.addView(pageVideo);

            FrameLayout playBtnWrap = new FrameLayout(parent.getContext());
            int btnSz = Math.round(64 * dp);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(btnSz, btnSz, Gravity.CENTER);
            playBtnWrap.setLayoutParams(pLp);
            playBtnWrap.setBackgroundResource(R.drawable.bg_circle_translucent);

            ImageView playIcon = new ImageView(parent.getContext());
            int iconSz = Math.round(32 * dp);
            FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER);
            playIcon.setLayoutParams(iLp);
            playIcon.setImageResource(R.drawable.ic_play_arrow);
            playIcon.setColorFilter(Color.WHITE);
            playBtnWrap.addView(playIcon);
            pageVideoWrap.addView(playBtnWrap);

            // Bottom inline video seek controls for album video items
            LinearLayout inlineBar = new LinearLayout(parent.getContext());
            inlineBar.setOrientation(LinearLayout.HORIZONTAL);
            inlineBar.setGravity(Gravity.CENTER_VERTICAL);
            inlineBar.setBackgroundColor(Color.parseColor("#99000000"));
            int padH = Math.round(14 * dp);
            int padV = Math.round(8 * dp);
            inlineBar.setPadding(padH, padV, padH, padV);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            barLp.bottomMargin = Math.round(92 * dp);
            inlineBar.setLayoutParams(barLp);

            TextView tvPos = new TextView(parent.getContext());
            tvPos.setTextColor(Color.WHITE);
            tvPos.setTextSize(12f);
            tvPos.setText("0:00");
            inlineBar.addView(tvPos);

            SeekBar seekBar = new SeekBar(parent.getContext());
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sLp.setMargins(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
            seekBar.setLayoutParams(sLp);
            inlineBar.addView(seekBar);

            TextView tvDur = new TextView(parent.getContext());
            tvDur.setTextColor(Color.WHITE);
            tvDur.setTextSize(12f);
            tvDur.setText("0:00");
            inlineBar.addView(tvDur);

            pageVideoWrap.addView(inlineBar);
            root.addView(pageVideoWrap);

            return new PageHolder(root, pagePhoto, pageVideoWrap, pageVideo, playBtnWrap, playIcon, seekBar, tvPos, tvDur);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            AlbumMediaEntry item = albumItems.get(position);
            File f = new File(item.path);

            // Support simultaneous vertical fling/scroll even while in horizontal swipe mode (and vice versa)
            GestureDetector dualAxisDetector = new GestureDetector(holder.itemView.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            toggleControls();
                            return true;
                        }

                        @Override
                        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                            if (e1 == null || e2 == null) return false;
                            float dy = e1.getY() - e2.getY();
                            float dx = e1.getX() - e2.getX();
                            if (!verticalScrollableCard && Math.abs(dy) > Math.abs(dx) * 1.35f && Math.abs(dy) > 110 && Math.abs(velocityY) > 150) {
                                // User scrolled vertically while in horizontal swipe mode -> advance/previous album item smoothly
                                navigateMedia(dy > 0 ? 1 : -1);
                                return true;
                            } else if (verticalScrollableCard && Math.abs(dx) > Math.abs(dy) * 1.35f && Math.abs(dx) > 110 && Math.abs(velocityX) > 150) {
                                // User swiped horizontally while in vertical scroll mode -> advance/previous album item smoothly
                                navigateMedia(dx > 0 ? 1 : -1);
                                return true;
                            }
                            return false;
                        }
                    });

            holder.pagePhoto.setOnTouchListener((v, event) -> {
                dualAxisDetector.onTouchEvent(event);
                return false;
            });
            holder.pageVideoWrap.setOnTouchListener((v, event) -> {
                dualAxisDetector.onTouchEvent(event);
                return true;
            });

            if ("video".equals(item.type)) {
                holder.pagePhoto.setVisibility(View.GONE);
                holder.pageVideoWrap.setVisibility(View.VISIBLE);
                if (f.exists()) {
                    holder.pageVideo.setVideoPath(f.getAbsolutePath());
                    holder.pageVideo.setOnPreparedListener(mp -> {
                        activeMediaPlayer = mp;
                        activeAlbumVideoView = holder.pageVideo;
                        int dur = mp.getDuration();
                        holder.seekBar.setMax(dur);
                        holder.tvDur.setText(formatTime(dur));
                        holder.tvPos.setText("0:00");
                        if (position == currentMediaIndex) {
                            holder.pageVideo.start();
                            holder.playBtnWrap.setVisibility(View.GONE);
                            holder.playIcon.setImageResource(R.drawable.ic_pause);
                        } else {
                            holder.pageVideo.seekTo(100);
                            holder.playBtnWrap.setVisibility(View.VISIBLE);
                            holder.playIcon.setImageResource(R.drawable.ic_play_arrow);
                        }
                    });
                    holder.pageVideo.setOnCompletionListener(mp -> {
                        holder.playBtnWrap.setVisibility(View.VISIBLE);
                        holder.playIcon.setImageResource(R.drawable.ic_play_arrow);
                        holder.seekBar.setProgress(holder.seekBar.getMax());
                    });
                    View.OnClickListener togglePlay = v -> {
                        if (holder.pageVideo.isPlaying()) {
                            holder.pageVideo.pause();
                            holder.playBtnWrap.setVisibility(View.VISIBLE);
                            holder.playIcon.setImageResource(R.drawable.ic_play_arrow);
                        } else {
                            activeAlbumVideoView = holder.pageVideo;
                            holder.pageVideo.start();
                            holder.playBtnWrap.setVisibility(View.GONE);
                            holder.playIcon.setImageResource(R.drawable.ic_pause);
                            videoHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (holder.pageVideo.isPlaying()) {
                                        int pos = holder.pageVideo.getCurrentPosition();
                                        holder.seekBar.setProgress(pos);
                                        holder.tvPos.setText(formatTime(pos));
                                        videoHandler.postDelayed(this, 120);
                                    }
                                }
                            });
                        }
                    };
                    holder.playBtnWrap.setOnClickListener(togglePlay);
                    holder.pageVideo.setOnClickListener(togglePlay);
                    holder.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            if (fromUser) {
                                holder.pageVideo.seekTo(progress);
                                holder.tvPos.setText(formatTime(progress));
                            }
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {}
                    });
                }
            } else {
                holder.pageVideoWrap.setVisibility(View.GONE);
                holder.pagePhoto.setVisibility(View.VISIBLE);
                if (f.exists()) {
                    if ("gif".equals(item.type) || com.m3.veil.utils.VeilStickerGifEngine.isGifFile(f)) {
                        android.graphics.drawable.Drawable gif = com.m3.veil.utils.VeilStickerGifEngine.loadAnimatedGifFromFile(f);
                        if (gif != null) {
                            holder.pagePhoto.setImageDrawable(gif);
                            return;
                        }
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    if (bmp != null) {
                        holder.pagePhoto.setImageBitmap(bmp);
                    }
                }
            }
        }

        @Override
        public int getItemCount() {
            return albumItems.size();
        }

        class PageHolder extends RecyclerView.ViewHolder {
            final ZoomableImageView pagePhoto;
            final FrameLayout pageVideoWrap;
            final VideoView pageVideo;
            final FrameLayout playBtnWrap;
            final ImageView playIcon;
            final SeekBar seekBar;
            final TextView tvPos;
            final TextView tvDur;

            PageHolder(@NonNull View itemView,
                       ZoomableImageView pagePhoto,
                       FrameLayout pageVideoWrap,
                       VideoView pageVideo,
                       FrameLayout playBtnWrap,
                       ImageView playIcon,
                       SeekBar seekBar,
                       TextView tvPos,
                       TextView tvDur) {
                super(itemView);
                this.pagePhoto = pagePhoto;
                this.pageVideoWrap = pageVideoWrap;
                this.pageVideo = pageVideo;
                this.playBtnWrap = playBtnWrap;
                this.playIcon = playIcon;
                this.seekBar = seekBar;
                this.tvPos = tvPos;
                this.tvDur = tvDur;
            }
        }
    }

    private void bindPhoto(File file) {
        photoViewer.setVisibility(View.VISIBLE);
        videoContainer.setVisibility(View.GONE);

        try {
            if ("gif".equals(mediaType) || com.m3.veil.utils.VeilStickerGifEngine.isGifFile(file)) {
                android.graphics.drawable.Drawable gifDrawable = com.m3.veil.utils.VeilStickerGifEngine.loadAnimatedGifFromFile(file);
                if (gifDrawable != null) {
                    photoViewer.setImageDrawable(gifDrawable);
                    return;
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                photoViewer.setImageBitmap(bitmap);
            } else {
                Toast.makeText(this, "Unable to render image", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void bindVideo(File file) {
        photoViewer.setVisibility(View.GONE);
        videoContainer.setVisibility(View.VISIBLE);
        viewerProgressBar.setVisibility(View.VISIBLE);

        videoViewer.setVideoPath(file.getAbsolutePath());
        videoViewer.setOnPreparedListener(mp -> {
            activeMediaPlayer = mp;
            viewerProgressBar.setVisibility(View.GONE);
            int duration = mp.getDuration();
            videoSeekBar.setMax(duration);
            tvVideoTotal.setText(formatTime(duration));
            tvVideoElapsed.setText("0:00");

            videoViewer.start();
            updatePlayPauseIcons(true);
            startVideoTracker();
        });

        videoViewer.setOnCompletionListener(mp -> {
            updatePlayPauseIcons(false);
            videoSeekBar.setProgress(videoSeekBar.getMax());
            tvVideoElapsed.setText(tvVideoTotal.getText());
            videoCenterPlayBtn.setVisibility(View.VISIBLE);
            stopVideoTracker();
        });

        View.OnClickListener playPauseClick = v -> {
            if (videoViewer.isPlaying()) {
                videoViewer.pause();
                updatePlayPauseIcons(false);
                videoCenterPlayBtn.setVisibility(View.VISIBLE);
                stopVideoTracker();
            } else {
                videoViewer.start();
                updatePlayPauseIcons(true);
                videoCenterPlayBtn.setVisibility(View.GONE);
                startVideoTracker();
            }
        };

        videoCenterPlayBtn.setOnClickListener(playPauseClick);
        btnBottomPlayPause.setOnClickListener(playPauseClick);

        videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    videoViewer.seekTo(progress);
                    tvVideoElapsed.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updatePlayPauseIcons(boolean isPlaying) {
        int icon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow;
        ivCenterPlayIcon.setImageResource(icon);
        btnBottomPlayPause.setImageResource(icon);
    }

    private void startVideoTracker() {
        stopVideoTracker();
        videoProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (videoViewer != null && videoViewer.isPlaying()) {
                    int pos = videoViewer.getCurrentPosition();
                    videoSeekBar.setProgress(pos);
                    tvVideoElapsed.setText(formatTime(pos));
                    videoHandler.postDelayed(this, 100);
                }
            }
        };
        videoHandler.post(videoProgressRunnable);
    }

    private void stopVideoTracker() {
        if (videoProgressRunnable != null) {
            videoHandler.removeCallbacks(videoProgressRunnable);
        }
    }

    private String formatTime(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }

    private void shareFile() {
        if (filePath == null) return;
        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("video".equals(mediaType) ? "video/*" : "image/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Media"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToGallery() {
        if (filePath == null) return;
        File srcFile = new File(filePath);
        if (!srcFile.exists()) return;

        try {
            String mime = "video".equals(mediaType) ? "video/mp4" : "image/jpeg";
            String dir = "video".equals(mediaType) ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            String fileName = "VEIL_" + System.currentTimeMillis() + ("video".equals(mediaType) ? ".mp4" : ".jpg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/VEIL");

                Uri collection = "video".equals(mediaType)
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

                Uri uri = getContentResolver().insert(collection, cv);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(srcFile);
                         OutputStream out = getContentResolver().openOutputStream(uri)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    Toast.makeText(this, "Saved to " + dir + "/VEIL", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            File destDir = new File(Environment.getExternalStoragePublicDirectory(dir), "VEIL");
            if (!destDir.exists()) destDir.mkdirs();
            File destFile = new File(destDir, fileName);

            try (InputStream in = new FileInputStream(srcFile);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)));
            Toast.makeText(this, "Saved to " + destFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoViewer != null && videoViewer.isPlaying()) {
            videoViewer.pause();
        }
        if (activeAlbumVideoView != null && activeAlbumVideoView.isPlaying()) {
            activeAlbumVideoView.pause();
        }
        stopVideoTracker();
    }

    @Override
    protected void onDestroy() {
        handleViewOnceDestruction();
        stopVideoTracker();
        if (videoViewer != null) {
            videoViewer.stopPlayback();
        }
        if (activeAlbumVideoView != null) {
            activeAlbumVideoView.stopPlayback();
        }
        super.onDestroy();
    }
}
