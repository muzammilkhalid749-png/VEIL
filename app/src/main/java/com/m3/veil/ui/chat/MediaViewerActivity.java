package com.m3.veil.ui.chat;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.util.LruCache;
import android.util.TypedValue;
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
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilMessagesController;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.ui.views.ZoomableImageView;
import com.m3.veil.utils.VeilStickerGifEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WhatsApp-style 3-Stage Media & Grouped Album Viewer:
 * - Stage 2 (Vertical Scrolling Album Feed Mode): Continuous vertical scrolling list of photos & videos
 *   when opening a multi-photo/video album bubble from chat (Screenshot3.jpg, Screenshot4.jpg).
 *   Clicking any card transitions smoothly into Stage 3 (Full-Screen Photo/Video View Mode).
 * - Stage 3 (Full-Screen Photo/Video View Mode): Full-screen zoomable photo / video player with
 *   top action bar (Back, You / timestamp, Save, Forward, Status, 3-dot Menu), 3-dot popup menu
 *   (Edit, Share, Star, Show in chat, Create sticker, Delete, More), video seekbar + 1.0x speed
 *   toggle right above the bottom Reply pill, and unified Reply + ❤️ 🙏 😊+ reaction bar
 *   (Screenshot_20261029-172134.jpg, 172146.jpg, Screenshot5.jpg, Screenshot6.jpg).
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
    public static final String EXTRA_IS_STARRED = "extra_is_starred";
    public static final String EXTRA_START_IN_ALBUM_FEED = "extra_start_in_album_feed";

    // Grouped Album extras
    public static final String EXTRA_ALBUM_PATHS = "extra_album_paths";
    public static final String EXTRA_ALBUM_TYPES = "extra_album_types";
    public static final String EXTRA_ALBUM_CAPTIONS = "extra_album_captions";
    public static final String EXTRA_INITIAL_INDEX = "extra_initial_index";

    private static final int REQUEST_EDIT_FROM_VIEWER = 9101;

    private static class AlbumMediaEntry {
        String path;
        final String type;
        final String caption;

        AlbumMediaEntry(String path, String type, String caption) {
            this.path = path;
            this.type = type != null ? type : "image";
            this.caption = caption != null ? caption : "";
        }
    }

    private final List<AlbumMediaEntry> albumItems = new ArrayList<>();
    private final LruCache<String, Bitmap> feedBitmapCache = new LruCache<>(48);
    private final LruCache<String, String> feedDurationCache = new LruCache<>(48);

    private int currentMediaIndex = 0;
    private boolean isVerticalScrollMode = false;
    private boolean enteredPagerFromAlbumFeed = false;

    private boolean isViewOnce = false;
    private boolean isStarred = false;
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
    private TextView btnVideoSpeed;

    private LinearLayout topBar;
    private ImageView btnBack;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private ImageView btnShare;
    private ImageView btnSave;
    private ImageView btnForward;
    private ImageView btnRepostStatus;
    private ImageView btnMoreMenu;

    private View bottomControlsWrapper;
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
    private FrameLayout activeAlbumPlayBtnWrap;
    private ImageView activeAlbumPlayIcon;
    private float currentVideoSpeed = 1.0f;

    private View bottomReplyBar;
    private EditText etMediaReply;
    private View btnSendMediaReply;
    private TextView reactionHeart;
    private TextView reactionPray;
    private TextView reactionLaugh;

    private AlbumPageAdapter horizontalPagerAdapter;
    private VerticalAlbumFeedAdapter verticalFeedAdapter;

    private TextView floatingReactionBadge;
    private String activeReactionEmoji = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        setContentView(R.layout.activity_media_viewer);

        configureDarkMediaSystemBars();

        initViews();
        handleWindowInsets();
        extractExtras();
        setupActions();
        bindMedia();

        View root = findViewById(R.id.mediaViewerRoot);
        if (root != null) {
            root.setScaleX(0.90f);
            root.setScaleY(0.90f);
            root.setAlpha(0f);
            root.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });
    }

    private void handleBackNavigation() {
        if (!isVerticalScrollMode && enteredPagerFromAlbumFeed && albumItems.size() > 1) {
            pauseActiveVideoPlayback();
            isVerticalScrollMode = true;
            isControlsVisible = true;
            applyAlbumScrollMode();
            updateActiveItemHeaderAndCaption();
            return;
        }
        finish();
    }

    private void configureDarkMediaSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.view.Window window = getWindow();
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            int flags = window.getDecorView().getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(flags);
        }
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
                        systemBars.top + (int) (6 * dp),
                        topBar.getPaddingEnd(),
                        (int) (10 * dp)
                );
            }
            if (bottomReplyBar != null) {
                bottomReplyBar.setPadding(
                        bottomReplyBar.getPaddingStart(),
                        (int) (8 * dp),
                        bottomReplyBar.getPaddingEnd(),
                        systemBars.bottom + (int) (12 * dp)
                );
            }
            if (mediaVerticalRecyclerView != null) {
                mediaVerticalRecyclerView.setPadding(
                        0,
                        systemBars.top + (int) (64 * dp),
                        0,
                        systemBars.bottom + (int) (24 * dp)
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
        btnVideoSpeed = findViewById(R.id.btnVideoSpeed);

        topBar = findViewById(R.id.topBar);
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        btnShare = findViewById(R.id.btnShare);
        btnSave = findViewById(R.id.btnSave);
        btnForward = findViewById(R.id.btnForward);
        btnRepostStatus = findViewById(R.id.btnRepostStatus);
        btnMoreMenu = findViewById(R.id.btnMoreMenu);

        bottomControlsWrapper = findViewById(R.id.bottomControlsWrapper);
        bottomCaptionBar = findViewById(R.id.bottomCaptionBar);
        tvCaption = findViewById(R.id.tvCaption);
        viewerProgressBar = findViewById(R.id.viewerProgressBar);

        bottomReplyBar = findViewById(R.id.bottomReplyBar);
        etMediaReply = findViewById(R.id.etMediaReply);
        btnSendMediaReply = findViewById(R.id.btnSendMediaReply);
        reactionHeart = findViewById(R.id.reactionHeart);
        reactionPray = findViewById(R.id.reactionPray);
        reactionLaugh = findViewById(R.id.reactionLaugh);
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
        isStarred = intent.getBooleanExtra(EXTRA_IS_STARRED, false);

        if (messageId != null && !messageId.isEmpty()) {
            try {
                Message msg = VeilMessagesController.getInstance(this).getMessage(messageId);
                if (msg != null) {
                    isStarred = msg.isStarred();
                    if (chatId == null || chatId.isEmpty()) chatId = msg.getChatId();
                }
            } catch (Throwable ignored) {}
        }

        ArrayList<String> albumPaths = intent.getStringArrayListExtra(EXTRA_ALBUM_PATHS);
        ArrayList<String> albumTypes = intent.getStringArrayListExtra(EXTRA_ALBUM_TYPES);
        ArrayList<String> albumCaptions = intent.getStringArrayListExtra(EXTRA_ALBUM_CAPTIONS);
        int initIdx = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0);
        boolean startInAlbumFeed = intent.getBooleanExtra(EXTRA_START_IN_ALBUM_FEED, false);

        albumItems.clear();
        if (albumPaths != null && !albumPaths.isEmpty()) {
            for (int i = 0; i < albumPaths.size(); i++) {
                String p = albumPaths.get(i);
                if (p == null || p.isEmpty()) continue;
                String t = (albumTypes != null && i < albumTypes.size())
                        ? albumTypes.get(i)
                        : (p.toLowerCase(Locale.ROOT).endsWith(".mp4") ? "video" : "image");
                String c = (albumCaptions != null && i < albumCaptions.size())
                        ? albumCaptions.get(i)
                        : (i == 0 && caption != null ? caption : "");
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
            albumItems.add(new AlbumMediaEntry(
                    filePath,
                    mediaType != null ? mediaType : "image",
                    caption != null ? caption : ""));
            currentMediaIndex = 0;
        }

        if (startInAlbumFeed && albumItems.size() > 1 && !isViewOnce) {
            isVerticalScrollMode = true;
            enteredPagerFromAlbumFeed = false;
        } else {
            isVerticalScrollMode = false;
        }

        if (isViewOnce) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            tvTitle.setText("video".equals(mediaType) ? "① View once video" : "① View once photo");
            tvSubtitle.setText("Disappears when closed");
            tvSubtitle.setVisibility(View.VISIBLE);
            if (btnShare != null) btnShare.setVisibility(View.GONE);
            if (btnSave != null) btnSave.setVisibility(View.GONE);
            if (btnForward != null) btnForward.setVisibility(View.GONE);
            if (btnRepostStatus != null) btnRepostStatus.setVisibility(View.GONE);
            if (btnMoreMenu != null) btnMoreMenu.setVisibility(View.GONE);
        }

        updateActiveItemHeaderAndCaption();
    }

    private String computeAlbumSummarySubtitle() {
        int photoCount = 0;
        int videoCount = 0;
        for (AlbumMediaEntry item : albumItems) {
            if ("video".equalsIgnoreCase(item.type) || item.path.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                videoCount++;
            } else {
                photoCount++;
            }
        }
        if (photoCount > 0 && videoCount > 0) {
            String pLabel = photoCount == 1 ? "1 photo" : (photoCount + " photos");
            String vLabel = videoCount == 1 ? "1 video" : (videoCount + " videos");
            return pLabel + ", " + vLabel;
        } else if (videoCount > 0) {
            return videoCount == 1 ? "1 video" : (videoCount + " videos");
        } else {
            return photoCount == 1 ? "1 photo" : (photoCount + " photos");
        }
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

        if (isViewOnce) {
            return;
        }

        String displayTitle = (senderName != null && !senderName.isEmpty()) ? senderName : "You";
        if (tvTitle != null) {
            tvTitle.setText(displayTitle);
        }

        if (isVerticalScrollMode && albumItems.size() > 1) {
            // STAGE 2: Vertical Scrolling Album Activity Header (Screenshot3.jpg & Screenshot4.jpg)
            if (tvSubtitle != null) {
                tvSubtitle.setText(computeAlbumSummarySubtitle());
                tvSubtitle.setVisibility(View.VISIBLE);
            }
            if (tvAlbumCounter != null) tvAlbumCounter.setVisibility(View.GONE);
            if (btnToggleScrollMode != null) btnToggleScrollMode.setVisibility(View.GONE);
            if (btnRepostStatus != null) btnRepostStatus.setVisibility(View.GONE);
            if (btnForward != null) btnForward.setVisibility(View.VISIBLE);
            if (btnSave != null) btnSave.setVisibility(View.VISIBLE);
            if (btnMoreMenu != null) btnMoreMenu.setVisibility(View.VISIBLE);
            if (videoControlsBar != null) videoControlsBar.setVisibility(View.GONE);
            if (bottomCaptionBar != null) bottomCaptionBar.setVisibility(View.GONE);
            if (bottomReplyBar != null) bottomReplyBar.setVisibility(View.GONE);
            return;
        }

        // STAGE 3: Full-Screen Photo/Video View Activity Header (Screenshot_20261029-172134.jpg & 172146.jpg)
        if (tvSubtitle != null) {
            String sub = (timestamp != null && !timestamp.isEmpty()) ? timestamp : "1 minute ago";
            tvSubtitle.setText(sub);
            tvSubtitle.setVisibility(View.VISIBLE);
        }
        if (tvAlbumCounter != null) {
            tvAlbumCounter.setVisibility(View.GONE);
        }
        if (btnToggleScrollMode != null) {
            btnToggleScrollMode.setVisibility(View.GONE);
        }
        if (btnSave != null) btnSave.setVisibility(View.VISIBLE);
        if (btnForward != null) btnForward.setVisibility(View.VISIBLE);
        if (btnRepostStatus != null) btnRepostStatus.setVisibility(View.VISIBLE);
        if (btnMoreMenu != null) btnMoreMenu.setVisibility(View.VISIBLE);

        boolean isVideoItem = "video".equalsIgnoreCase(mediaType)
                || (filePath != null && filePath.toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (videoControlsBar != null) {
            videoControlsBar.setVisibility(isVideoItem && isControlsVisible ? View.VISIBLE : View.GONE);
        }

        boolean hasCaption = caption != null && !caption.trim().isEmpty()
                && !caption.startsWith("[image") && !caption.startsWith("[video");
        if (tvCaption != null) {
            tvCaption.setText(hasCaption ? caption : "");
            tvCaption.setVisibility(hasCaption ? View.VISIBLE : View.GONE);
        }
        if (albumThumbnailScroll != null) {
            albumThumbnailScroll.setVisibility(View.GONE);
        }
        if (bottomCaptionBar != null) {
            bottomCaptionBar.setVisibility(hasCaption && isControlsVisible ? View.VISIBLE : View.GONE);
        }
        if (bottomReplyBar != null) {
            bottomReplyBar.setVisibility(isControlsVisible ? View.VISIBLE : View.GONE);
        }
        highlightActiveThumbnail(currentMediaIndex);
    }

    @Override
    public void finish() {
        handleViewOnceDestruction();
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private synchronized void handleViewOnceDestruction() {
        if (!isViewOnce || hasDestroyedViewOnce) return;
        hasDestroyedViewOnce = true;
        if (messageId != null && chatId != null) {
            EncryptedChatDatabase.getInstance(this).markBlinkMessageViewed(chatId, messageId);
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
        if (mediaViewPager != null && mediaViewPager.getCurrentItem() != index) {
            mediaViewPager.setCurrentItem(index, smooth);
        }
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> handleBackNavigation());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareFile());
        if (btnSave != null) btnSave.setOnClickListener(v -> {
            if (isVerticalScrollMode && albumItems.size() > 1) {
                saveAllAlbumItemsToGallery();
            } else {
                saveToGallery();
            }
        });
        if (btnForward != null) btnForward.setOnClickListener(v -> showForwardDialog());
        if (btnRepostStatus != null) btnRepostStatus.setOnClickListener(v -> repostMediaToStatus());
        if (btnMoreMenu != null) btnMoreMenu.setOnClickListener(this::showWhatsAppOverflowMenu);

        if (btnToggleScrollMode != null) {
            btnToggleScrollMode.setOnClickListener(v -> {
                isVerticalScrollMode = !isVerticalScrollMode;
                applyAlbumScrollMode();
            });
        }

        if (btnVideoSpeed != null) {
            btnVideoSpeed.setOnClickListener(v -> {
                if (currentVideoSpeed == 1.0f) currentVideoSpeed = 1.5f;
                else if (currentVideoSpeed == 1.5f) currentVideoSpeed = 2.0f;
                else currentVideoSpeed = 1.0f;
                btnVideoSpeed.setText(String.format(Locale.US, "%.1fx", currentVideoSpeed));
                applyCurrentVideoSpeed();
            });
        }

        if (videoSeekBar != null) {
            videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        if (activeAlbumVideoView != null && mediaViewPager != null && mediaViewPager.getVisibility() == View.VISIBLE) {
                            activeAlbumVideoView.seekTo(progress);
                        } else if (videoViewer != null) {
                            videoViewer.seekTo(progress);
                        }
                        if (tvVideoElapsed != null) {
                            tvVideoElapsed.setText(formatTime(progress));
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (bottomReplyBar != null) {
            if (isViewOnce) {
                bottomReplyBar.setVisibility(View.GONE);
            } else {
                ensureFloatingReactionBadgeCreated();

                View.OnClickListener quickReactionListener = v -> {
                    if (v instanceof TextView) {
                        String emoji = ((TextView) v).getText().toString();
                        playReactionPopAnimationAndApply(emoji, v);
                    }
                };
                if (reactionHeart != null) reactionHeart.setOnClickListener(quickReactionListener);
                if (reactionPray != null) reactionPray.setOnClickListener(quickReactionListener);
                if (reactionLaugh != null) {
                    reactionLaugh.setOnClickListener(v -> showFullEmojiReactionPicker());
                }

                View replyPillContainer = findViewById(R.id.replyPillContainer);
                if (etMediaReply != null) {
                    etMediaReply.setFocusable(false);
                    etMediaReply.setClickable(true);
                    etMediaReply.setOnClickListener(v -> showInlineWhatsAppReplyComposer());
                }
                if (replyPillContainer != null) {
                    replyPillContainer.setOnClickListener(v -> showInlineWhatsAppReplyComposer());
                }
                if (btnSendMediaReply != null) {
                    btnSendMediaReply.setOnClickListener(v -> sendMediaReplyMessage());
                }
            }
        }

        findViewById(R.id.mediaViewerRoot).setOnClickListener(v -> {
            if (!isVerticalScrollMode) {
                toggleControls();
            }
        });
        if (photoViewer != null) {
            photoViewer.setOnClickListener(v -> toggleControls());
        }
    }

    private void applyCurrentVideoSpeed() {
        if (activeMediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                boolean wasPlaying = activeMediaPlayer.isPlaying();
                activeMediaPlayer.setPlaybackParams(activeMediaPlayer.getPlaybackParams().setSpeed(currentVideoSpeed));
                if (!wasPlaying) {
                    activeMediaPlayer.pause();
                }
            } catch (Exception ignored) {}
        }
    }

    private void ensureFloatingReactionBadgeCreated() {
        if (floatingReactionBadge != null || !(bottomControlsWrapper instanceof LinearLayout)) return;
        LinearLayout wrapper = (LinearLayout) bottomControlsWrapper;
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout badgeRow = new FrameLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.rightMargin = Math.round(14 * dp);
        rowLp.bottomMargin = Math.round(2 * dp);
        badgeRow.setLayoutParams(rowLp);

        floatingReactionBadge = new TextView(this);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(34 * dp), Gravity.END | Gravity.CENTER_VERTICAL);
        floatingReactionBadge.setLayoutParams(bLp);
        floatingReactionBadge.setGravity(Gravity.CENTER);
        int padH = Math.round(12 * dp);
        floatingReactionBadge.setPadding(padH, 0, padH, 0);
        floatingReactionBadge.setTextSize(17f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2C34"));
        bg.setCornerRadius(18 * dp);
        bg.setStroke(Math.max(1, Math.round(1 * dp)), Color.parseColor("#33FFFFFF"));
        floatingReactionBadge.setBackground(bg);
        floatingReactionBadge.setElevation(6 * dp);
        floatingReactionBadge.setVisibility(View.GONE);
        floatingReactionBadge.setOnClickListener(v -> showFullEmojiReactionPicker());

        badgeRow.addView(floatingReactionBadge);
        wrapper.addView(badgeRow, 0);
    }

    /**
     * Plays a smooth WhatsApp reaction pop/floating emoji animation (when_user_react_its_show_animatiob_5c36700a.jpg),
     * collapses the quick reaction icons inside the Reply pill, and pins the dark reaction pill badge on the right above 1.0x.
     */
    private void playReactionPopAnimationAndApply(String emoji, @Nullable View anchorView) {
        if (emoji == null || emoji.isEmpty()) return;
        activeReactionEmoji = emoji;
        dispatchReactionForCurrentMessage(emoji);

        ViewGroup root = findViewById(R.id.mediaViewerRoot);
        float dp = getResources().getDisplayMetrics().density;
        if (root != null) {
            final TextView animEmoji = new TextView(this);
            animEmoji.setText(emoji);
            animEmoji.setTextSize(38f);
            animEmoji.setGravity(Gravity.CENTER);
            int sz = Math.round(68 * dp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sz, sz, Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = Math.round(24 * dp);
            lp.bottomMargin = Math.round(72 * dp);
            animEmoji.setLayoutParams(lp);
            animEmoji.setScaleX(0.25f);
            animEmoji.setScaleY(0.25f);
            animEmoji.setAlpha(0f);
            root.addView(animEmoji);

            animEmoji.animate()
                    .scaleX(1.35f)
                    .scaleY(1.35f)
                    .alpha(1f)
                    .translationY(-Math.round(52 * dp))
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                    .withEndAction(() -> animEmoji.animate()
                            .scaleX(0.75f)
                            .scaleY(0.75f)
                            .alpha(0f)
                            .translationY(-Math.round(24 * dp))
                            .setDuration(200)
                            .withEndAction(() -> root.removeView(animEmoji))
                            .start())
                    .start();
        }

        // Collapse quick reactions inside Reply pill & show floating reaction badge above 1.0x
        if (reactionHeart != null) reactionHeart.setVisibility(View.GONE);
        if (reactionPray != null) reactionPray.setVisibility(View.GONE);
        if (reactionLaugh != null) reactionLaugh.setVisibility(View.GONE);

        ensureFloatingReactionBadgeCreated();
        if (floatingReactionBadge != null) {
            floatingReactionBadge.setText(emoji);
            floatingReactionBadge.setVisibility(View.VISIBLE);
            floatingReactionBadge.setScaleX(0.4f);
            floatingReactionBadge.setScaleY(0.4f);
            floatingReactionBadge.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f))
                    .start();
        }
    }

    private void dispatchReactionForCurrentMessage(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        if (chatId != null && !chatId.isEmpty() && messageId != null && !messageId.isEmpty()) {
            VeilMessagesController.getInstance(this).sendReaction(chatId, messageId, emoji);
        }
    }

    private void showFullEmojiReactionPicker() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2C34"));
        bg.setCornerRadii(new float[]{24 * dp, 24 * dp, 24 * dp, 24 * dp, 0, 0, 0, 0});
        container.setBackground(bg);
        int pad = Math.round(20 * dp);
        container.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("React to media");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, Math.round(14 * dp));
        container.addView(title);

        String[][] rows = new String[][]{
                {"❤️", "🙏", "😂", "😮", "😢", "👍"},
                {"🔥", "🎉", "💯", "🥰", "👏", "🤔"}
        };

        for (String[] rowEmojis : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = Math.round(10 * dp);
            row.setLayoutParams(rLp);

            for (String emoji : rowEmojis) {
                TextView tv = new TextView(this);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0, Math.round(48 * dp), 1f));
                tv.setGravity(Gravity.CENTER);
                tv.setText(emoji);
                tv.setTextSize(26f);
                tv.setOnClickListener(v -> {
                    sheet.dismiss();
                    playReactionPopAnimationAndApply(emoji, v);
                });
                row.addView(tv);
            }
            container.addView(row);
        }

        sheet.setContentView(container);
        sheet.show();
    }

    /**
     * WhatsApp 3-Dot Overflow Menu (Screenshot_20261029-172134.jpg & 172146.jpg):
     * - Edit
     * - Share
     * - Star / Unstar
     * - Show in chat
     * - Create sticker
     * - (divider)
     * - Delete
     * - (divider)
     * - More >
     */
    private void showWhatsAppOverflowMenu(View anchor) {
        if (anchor == null) return;
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(16 * dp);
        card.setBackground(cardBg);
        card.setElevation(18 * dp);
        card.setPadding(0, Math.round(8 * dp), 0, Math.round(8 * dp));

        int popupWidth = Math.round(228 * dp);
        final PopupWindow popup = new PopupWindow(card, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(20 * dp);
        popup.setOutsideTouchable(true);

        if (isVerticalScrollMode && albumItems.size() > 1) {
            card.addView(buildPopupMenuItem("Save all", R.drawable.ic_download, false, v -> {
                popup.dismiss();
                saveAllAlbumItemsToGallery();
            }));
            card.addView(buildPopupMenuItem("Forward all", R.drawable.ic_forward, false, v -> {
                popup.dismiss();
                showForwardDialog();
            }));
            card.addView(buildPopupMenuItem("Show in chat", R.drawable.ic_chat, false, v -> {
                popup.dismiss();
                showInChatAndFinish();
            }));
            card.addView(buildPopupDivider());
            card.addView(buildPopupMenuItem("Delete", R.drawable.ic_delete, false, v -> {
                popup.dismiss();
                confirmAndDeleteMessage();
            }));
            popup.showAsDropDown(anchor, -popupWidth + anchor.getWidth() - Math.round(6 * dp), Math.round(4 * dp));
            return;
        }

        // 1. Edit
        card.addView(buildPopupMenuItem("Edit", R.drawable.ic_edit, false, v -> {
            popup.dismiss();
            openMediaEditorForCurrentItem();
        }));

        // 2. Share
        card.addView(buildPopupMenuItem("Share", R.drawable.ic_share, false, v -> {
            popup.dismiss();
            shareFile();
        }));

        // 3. Star / Unstar
        card.addView(buildPopupMenuItem(isStarred ? "Unstar" : "Star", R.drawable.ic_saved, false, v -> {
            popup.dismiss();
            toggleStarCurrentMessage();
        }));

        // 4. Show in chat
        card.addView(buildPopupMenuItem("Show in chat", R.drawable.ic_chat, false, v -> {
            popup.dismiss();
            showInChatAndFinish();
        }));

        // 5. Create sticker
        card.addView(buildPopupMenuItem("Create sticker", R.drawable.ic_emoji, false, v -> {
            popup.dismiss();
            createStickerFromCurrentMedia();
        }));

        // Divider
        card.addView(buildPopupDivider());

        // 6. Delete
        card.addView(buildPopupMenuItem("Delete", R.drawable.ic_delete, false, v -> {
            popup.dismiss();
            confirmAndDeleteMessage();
        }));

        // Divider
        card.addView(buildPopupDivider());

        // 7. More >
        card.addView(buildPopupMenuItem("More", 0, true, v -> {
            popup.dismiss();
            showWhatsAppMoreSubmenu(anchor);
        }));

        popup.showAsDropDown(anchor, -popupWidth + anchor.getWidth() - Math.round(6 * dp), Math.round(4 * dp));
    }

    private void showWhatsAppMoreSubmenu(View anchor) {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(16 * dp);
        card.setBackground(cardBg);
        card.setElevation(18 * dp);
        card.setPadding(0, Math.round(8 * dp), 0, Math.round(8 * dp));

        int popupWidth = Math.round(228 * dp);
        final PopupWindow subPopup = new PopupWindow(card, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        subPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        subPopup.setElevation(20 * dp);
        subPopup.setOutsideTouchable(true);

        boolean isVideo = "video".equalsIgnoreCase(mediaType)
                || (filePath != null && filePath.toLowerCase(Locale.ROOT).endsWith(".mp4"));

        if (!isVideo) {
            card.addView(buildPopupMenuItem("Rotate", R.drawable.ic_undo, false, v -> {
                subPopup.dismiss();
                rotateCurrentImage90Degrees();
            }));
            card.addView(buildPopupMenuItem("Set as profile photo", R.drawable.ic_person, false, v -> {
                subPopup.dismiss();
                setCurrentImageAsProfilePhoto();
            }));
            card.addView(buildPopupMenuItem("Set as chat wallpaper", R.drawable.ic_image, false, v -> {
                subPopup.dismiss();
                setCurrentImageAsWallpaper();
            }));
        }

        card.addView(buildPopupMenuItem("Save to gallery", R.drawable.ic_download, false, v -> {
            subPopup.dismiss();
            saveToGallery();
        }));

        card.addView(buildPopupMenuItem("View in gallery", R.drawable.ic_discover, false, v -> {
            subPopup.dismiss();
            openInExternalGallery();
        }));

        subPopup.showAsDropDown(anchor, -popupWidth + anchor.getWidth() - Math.round(6 * dp), Math.round(4 * dp));
    }

    private View buildPopupMenuItem(String label, int iconRes, boolean showRightArrow, View.OnClickListener listener) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48 * dp)));
        int padH = Math.round(18 * dp);
        row.setPadding(padH, 0, padH, 0);

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        if (iconRes != 0) {
            ImageView icon = new ImageView(this);
            int icSz = Math.round(21 * dp);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(icSz, icSz);
            icLp.rightMargin = Math.round(16 * dp);
            icon.setLayoutParams(icLp);
            icon.setImageResource(iconRes);
            icon.setColorFilter(Color.parseColor("#3B4A54"));
            row.addView(icon);
        }

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#111B21"));
        tv.setTextSize(15.5f);
        row.addView(tv);

        if (showRightArrow) {
            ImageView arrow = new ImageView(this);
            int arSz = Math.round(18 * dp);
            arrow.setLayoutParams(new LinearLayout.LayoutParams(arSz, arSz));
            arrow.setImageResource(R.drawable.ic_chevron_right);
            arrow.setColorFilter(Color.parseColor("#3B4A54"));
            row.addView(arrow);
        }

        row.setOnClickListener(listener);
        return row;
    }

    private View buildPopupDivider() {
        float dp = getResources().getDisplayMetrics().density;
        View div = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * dp)));
        lp.topMargin = Math.round(4 * dp);
        lp.bottomMargin = Math.round(4 * dp);
        div.setLayoutParams(lp);
        div.setBackgroundColor(Color.parseColor("#E9EDEF"));
        return div;
    }

    private void openMediaEditorForCurrentItem() {
        openMediaEditorInternal(false);
    }

    private void openMediaEditorInternal(boolean statusMode) {
        if (!albumItems.isEmpty() && currentMediaIndex >= 0 && currentMediaIndex < albumItems.size()) {
            AlbumMediaEntry active = albumItems.get(currentMediaIndex);
            filePath = active.path;
            mediaType = active.type;
        }
        if (filePath == null || filePath.isEmpty()) {
            Toast.makeText(this, "File not available", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(filePath);
        if (!f.exists()) {
            Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        pauseActiveVideoPlayback();

        boolean isVid = "video".equalsIgnoreCase(mediaType) || f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4");
        String resolvedType = isVid ? "video" : "image";
        Uri fileUri = Uri.fromFile(f);

        Intent editIntent = new Intent(this, MediaEditActivity.class);
        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, fileUri);
        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE, resolvedType);
        ArrayList<String> singleBatchUri = new ArrayList<>();
        singleBatchUri.add(fileUri.toString());
        ArrayList<String> singleBatchType = new ArrayList<>();
        singleBatchType.add(resolvedType);
        editIntent.putStringArrayListExtra(MediaEditActivity.EXTRA_BATCH_URIS, singleBatchUri);
        editIntent.putStringArrayListExtra(MediaEditActivity.EXTRA_BATCH_TYPES, singleBatchType);
        editIntent.putExtra(MediaEditActivity.EXTRA_IS_STATUS_MODE, statusMode);
        editIntent.putExtra(MediaEditActivity.EXTRA_RECIPIENT_NAME,
                statusMode ? "Status (Contacts)" : (senderName != null && !senderName.isEmpty() ? senderName : "Status (Contacts)"));
        startActivityForResult(editIntent, REQUEST_EDIT_FROM_VIEWER);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT_FROM_VIEWER && resultCode == RESULT_OK && data != null) {
            String editedPath = data.getStringExtra(MediaEditActivity.RESULT_EDITED_FILE_PATH);
            String editedType = data.getStringExtra(MediaEditActivity.RESULT_MEDIA_TYPE);
            String editedCaption = data.getStringExtra(MediaEditActivity.RESULT_CAPTION);
            boolean editedViewOnce = data.getBooleanExtra(MediaEditActivity.RESULT_IS_VIEW_ONCE, false);
            boolean isStatusPost = data.getBooleanExtra(MediaEditActivity.RESULT_IS_STATUS_POST, false);
            if (isStatusPost) {
                Toast.makeText(this, "Posted to your Status (Contacts)", Toast.LENGTH_SHORT).show();
                return;
            }
            if (editedPath != null && !editedPath.isEmpty()) {
                File editedFile = new File(editedPath);
                if (editedFile.exists()) {
                    if (chatId != null && !chatId.isEmpty()) {
                        VeilMessagesController.getInstance(this).sendMediaMessage(
                                chatId, peerId, editedType != null ? editedType : "image",
                                editedFile, editedCaption, messageId, editedViewOnce);
                        Toast.makeText(this, "Edited media sent to chat", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Edited media saved", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    private void toggleStarCurrentMessage() {
        isStarred = !isStarred;
        if (messageId != null && !messageId.isEmpty() && chatId != null && !chatId.isEmpty()) {
            try {
                Message m = VeilMessagesController.getInstance(this).getMessage(messageId);
                if (m != null) {
                    m.setStarred(isStarred);
                    EncryptedChatDatabase.getInstance(this).saveMessage(chatId, m);
                }
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, isStarred ? "⭐ Message starred" : "Message unstarred", Toast.LENGTH_SHORT).show();
    }

    private void showInChatAndFinish() {
        if (chatId != null && !chatId.isEmpty() && messageId != null && !messageId.isEmpty()) {
            Intent chatIntent = new Intent(this, ChatActivity.class);
            chatIntent.putExtra("chatId", chatId);
            if (peerId != null) chatIntent.putExtra("recipientId", peerId);
            chatIntent.putExtra("scrollToMessageId", messageId);
            chatIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(chatIntent);
        }
        finish();
    }

    /**
     * Opens the WhatsApp "Trim" Screen when clicking "Create sticker" in the 3-dot menu
     * (when_we_click_on_create_sticker_160f5697.jpg):
     * - Pure black full-screen dialog with centered white "Trim" title at top.
     * - Dark slate rounded container (#11161B) in the middle holding a square framed media preview
     *   with a centered circular play/pause button.
     * - Filmstrip frame-thumbnail timeline bar below with a bright white selection border and
     *   draggable left/right circular white handles (●).
     * - Bottom bar with dark pill "Cancel" button on the left and white pill "Done" button on the right.
     */
    private void createStickerFromCurrentMedia() {
        if (!albumItems.isEmpty() && currentMediaIndex >= 0 && currentMediaIndex < albumItems.size()) {
            AlbumMediaEntry active = albumItems.get(currentMediaIndex);
            filePath = active.path;
            mediaType = active.type;
        }
        if (filePath == null || filePath.isEmpty()) return;
        final File f = new File(filePath);
        if (!f.exists()) return;
        pauseActiveVideoPlayback();

        final boolean isVid = "video".equalsIgnoreCase(mediaType) || f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4");
        final float dp = getResources().getDisplayMetrics().density;

        final android.app.Dialog trimDialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(Math.round(14 * dp), Math.round(38 * dp), Math.round(14 * dp), Math.round(20 * dp));

        // 1. Top "Trim" Title
        TextView tvTrimHeader = new TextView(this);
        tvTrimHeader.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvTrimHeader.setGravity(Gravity.CENTER);
        tvTrimHeader.setText("Trim");
        tvTrimHeader.setTextColor(Color.WHITE);
        tvTrimHeader.setTextSize(18f);
        tvTrimHeader.setTypeface(null, Typeface.BOLD);
        tvTrimHeader.setPadding(0, Math.round(6 * dp), 0, Math.round(18 * dp));
        root.addView(tvTrimHeader);

        // 2. Center Dark Slate Preview Card (#11161B) with Square Preview & Play Button
        FrameLayout previewCard = new FrameLayout(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.bottomMargin = Math.round(18 * dp);
        previewCard.setLayoutParams(cardLp);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#11161B"));
        cardBg.setCornerRadius(18 * dp);
        previewCard.setBackground(cardBg);

        int squareSize = Math.round(Math.min(getResources().getDisplayMetrics().widthPixels - 64 * dp, 300 * dp));
        FrameLayout squareFrame = new FrameLayout(this);
        FrameLayout.LayoutParams sqLp = new FrameLayout.LayoutParams(squareSize, squareSize, Gravity.CENTER);
        squareFrame.setLayoutParams(sqLp);
        squareFrame.setBackgroundColor(Color.BLACK);

        final ImageView ivFramePreview = new ImageView(this);
        ivFramePreview.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ivFramePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        squareFrame.addView(ivFramePreview);

        final VideoView trimVideoView = new VideoView(this);
        trimVideoView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        trimVideoView.setVisibility(isVid ? View.VISIBLE : View.GONE);
        squareFrame.addView(trimVideoView);

        // Center Play Button Overlay
        final FrameLayout centerPlayCircle = new FrameLayout(this);
        int playSz = Math.round(56 * dp);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(playSz, playSz, Gravity.CENTER);
        centerPlayCircle.setLayoutParams(playLp);
        centerPlayCircle.setBackgroundResource(R.drawable.bg_video_play_glass);

        final ImageView ivPlayIcon = new ImageView(this);
        int icSz = Math.round(28 * dp);
        FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(icSz, icSz, Gravity.CENTER);
        icLp.leftMargin = Math.round(2 * dp);
        ivPlayIcon.setLayoutParams(icLp);
        ivPlayIcon.setImageResource(R.drawable.ic_play_arrow);
        ivPlayIcon.setColorFilter(Color.WHITE);
        centerPlayCircle.addView(ivPlayIcon);
        squareFrame.addView(centerPlayCircle);

        previewCard.addView(squareFrame);
        root.addView(previewCard);

        // 3. Filmstrip Timeline Bar with White Selection Box & Left/Right Circular Handles (●)
        final int stripHeight = Math.round(54 * dp);
        final FrameLayout timelineContainer = new FrameLayout(this);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, stripHeight);
        tlLp.leftMargin = Math.round(8 * dp);
        tlLp.rightMargin = Math.round(8 * dp);
        tlLp.bottomMargin = Math.round(22 * dp);
        timelineContainer.setLayoutParams(tlLp);

        LinearLayout filmstripRow = new LinearLayout(this);
        filmstripRow.setOrientation(LinearLayout.HORIZONTAL);
        filmstripRow.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable stripBg = new GradientDrawable();
        stripBg.setColor(Color.parseColor("#1F2C34"));
        stripBg.setCornerRadius(8 * dp);
        filmstripRow.setBackground(stripBg);
        int padStrip = Math.round(2 * dp);
        filmstripRow.setPadding(padStrip, padStrip, padStrip, padStrip);

        final long[] totalDurationMs = new long[]{5000L};
        final List<Bitmap> stripBitmaps = new ArrayList<>();
        int frameCount = 8;
        if (isVid) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(f.getAbsolutePath());
                String dStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dStr != null) {
                    totalDurationMs[0] = Math.max(1000L, Long.parseLong(dStr));
                }
                for (int i = 0; i < frameCount; i++) {
                    long tUs = (totalDurationMs[0] * 1000L * i) / Math.max(1, frameCount);
                    Bitmap b = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b == null) b = mmr.getFrameAtTime();
                    if (b != null) stripBitmaps.add(b);
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        } else {
            Bitmap b = decodeCompleteBitmapWithExif(f, 800);
            if (b != null) {
                for (int i = 0; i < frameCount; i++) stripBitmaps.add(b);
            }
        }

        if (!stripBitmaps.isEmpty()) {
            ivFramePreview.setImageBitmap(stripBitmaps.get(0));
        }

        for (int i = 0; i < frameCount; i++) {
            ImageView thumb = new ImageView(this);
            thumb.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (!stripBitmaps.isEmpty()) {
                thumb.setImageBitmap(stripBitmaps.get(i % stripBitmaps.size()));
            }
            filmstripRow.addView(thumb);
        }
        timelineContainer.addView(filmstripRow);

        // White Selection Overlay Box with Left & Right White Circular Handles (●)
        final FrameLayout selectionBox = new FrameLayout(this);
        final FrameLayout.LayoutParams selLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        selectionBox.setLayoutParams(selLp);
        GradientDrawable selBorder = new GradientDrawable();
        selBorder.setColor(Color.TRANSPARENT);
        selBorder.setCornerRadius(6 * dp);
        selBorder.setStroke(Math.round(2.5f * dp), Color.WHITE);
        selectionBox.setBackground(selBorder);

        int handleSz = Math.round(14 * dp);
        View leftHandleDot = new View(this);
        FrameLayout.LayoutParams lhLp = new FrameLayout.LayoutParams(handleSz, handleSz, Gravity.START | Gravity.CENTER_VERTICAL);
        leftHandleDot.setLayoutParams(lhLp);
        GradientDrawable dotBg1 = new GradientDrawable();
        dotBg1.setShape(GradientDrawable.OVAL);
        dotBg1.setColor(Color.WHITE);
        leftHandleDot.setBackground(dotBg1);
        selectionBox.addView(leftHandleDot);

        View rightHandleDot = new View(this);
        FrameLayout.LayoutParams rhLp = new FrameLayout.LayoutParams(handleSz, handleSz, Gravity.END | Gravity.CENTER_VERTICAL);
        rightHandleDot.setLayoutParams(rhLp);
        GradientDrawable dotBg2 = new GradientDrawable();
        dotBg2.setShape(GradientDrawable.OVAL);
        dotBg2.setColor(Color.WHITE);
        rightHandleDot.setBackground(dotBg2);
        selectionBox.addView(rightHandleDot);

        timelineContainer.addView(selectionBox);
        root.addView(timelineContainer);

        final float[] startFraction = new float[]{0f};
        final float[] endFraction = new float[]{1f};

        timelineContainer.setOnTouchListener((v, event) -> {
            int width = timelineContainer.getWidth();
            if (width <= 0) return false;
            float x = Math.max(0f, Math.min(width, event.getX()));
            float frac = x / width;
            float distStart = Math.abs(frac - startFraction[0]);
            float distEnd = Math.abs(frac - endFraction[0]);
            if (distStart <= distEnd) {
                startFraction[0] = Math.min(frac, endFraction[0] - 0.12f);
                startFraction[0] = Math.max(0f, startFraction[0]);
            } else {
                endFraction[0] = Math.max(frac, startFraction[0] + 0.12f);
                endFraction[0] = Math.min(1f, endFraction[0]);
            }
            int leftPx = Math.round(startFraction[0] * width);
            int rightPx = Math.round((1f - endFraction[0]) * width);
            selLp.leftMargin = leftPx;
            selLp.rightMargin = rightPx;
            selectionBox.setLayoutParams(selLp);

            long seekMs = Math.round(startFraction[0] * totalDurationMs[0]);
            if (isVid) {
                try {
                    trimVideoView.seekTo((int) seekMs);
                } catch (Exception ignored) {}
            }
            if (!stripBitmaps.isEmpty()) {
                int idx = Math.min(stripBitmaps.size() - 1, Math.max(0, Math.round(startFraction[0] * (stripBitmaps.size() - 1))));
                ivFramePreview.setImageBitmap(stripBitmaps.get(idx));
            }
            return true;
        });

        if (isVid) {
            trimVideoView.setVideoPath(f.getAbsolutePath());
            trimVideoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                int vw = mp.getVideoWidth();
                int vh = mp.getVideoHeight();
                if (vw > 0 && vh > 0) {
                    fitVideoViewToAspectRatio(trimVideoView, squareFrame, vw, vh);
                }
                trimVideoView.seekTo(1);
            });
            View.OnClickListener toggleTrimPlay = v -> {
                if (trimVideoView.isPlaying()) {
                    trimVideoView.pause();
                    ivPlayIcon.setImageResource(R.drawable.ic_play_arrow);
                    centerPlayCircle.setAlpha(1f);
                } else {
                    ivFramePreview.setVisibility(View.GONE);
                    trimVideoView.seekTo((int) (startFraction[0] * totalDurationMs[0]));
                    trimVideoView.start();
                    ivPlayIcon.setImageResource(R.drawable.ic_pause);
                    centerPlayCircle.setAlpha(0.35f);
                }
            };
            centerPlayCircle.setOnClickListener(toggleTrimPlay);
            squareFrame.setOnClickListener(toggleTrimPlay);
        } else {
            centerPlayCircle.setOnClickListener(v ->
                    Toast.makeText(this, "Previewing sticker frame", Toast.LENGTH_SHORT).show());
        }

        // 4. Bottom Action Row: Dark "Cancel" Pill on Left + White "Done" Pill on Right
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView btnCancelTrim = new TextView(this);
        btnCancelTrim.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(42 * dp)));
        btnCancelTrim.setGravity(Gravity.CENTER);
        btnCancelTrim.setPadding(Math.round(22 * dp), 0, Math.round(22 * dp), 0);
        btnCancelTrim.setText("Cancel");
        btnCancelTrim.setTextColor(Color.WHITE);
        btnCancelTrim.setTextSize(15f);
        btnCancelTrim.setTypeface(null, Typeface.BOLD);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(Color.parseColor("#1F2C34"));
        cancelBg.setCornerRadius(21 * dp);
        btnCancelTrim.setBackground(cancelBg);
        btnCancelTrim.setOnClickListener(v -> {
            try {
                if (isVid && trimVideoView.isPlaying()) trimVideoView.stopPlayback();
            } catch (Exception ignored) {}
            trimDialog.dismiss();
        });
        bottomRow.addView(btnCancelTrim);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bottomRow.addView(spacer);

        TextView btnDoneTrim = new TextView(this);
        btnDoneTrim.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(42 * dp)));
        btnDoneTrim.setGravity(Gravity.CENTER);
        btnDoneTrim.setPadding(Math.round(24 * dp), 0, Math.round(24 * dp), 0);
        btnDoneTrim.setText("Done");
        btnDoneTrim.setTextColor(Color.parseColor("#0B141A"));
        btnDoneTrim.setTextSize(15f);
        btnDoneTrim.setTypeface(null, Typeface.BOLD);
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setColor(Color.WHITE);
        doneBg.setCornerRadius(21 * dp);
        btnDoneTrim.setBackground(doneBg);
        btnDoneTrim.setOnClickListener(v -> {
            try {
                if (isVid && trimVideoView.isPlaying()) trimVideoView.stopPlayback();
            } catch (Exception ignored) {}
            trimDialog.dismiss();
            long chosenTimeUs = Math.round(startFraction[0] * totalDurationMs[0] * 1000L);
            finalizeStickerFromTrimmedSelection(f, isVid, chosenTimeUs);
        });
        bottomRow.addView(btnDoneTrim);

        root.addView(bottomRow);

        trimDialog.setContentView(root);
        trimDialog.show();
        root.setScaleX(0.94f);
        root.setScaleY(0.94f);
        root.setAlpha(0f);
        root.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void finalizeStickerFromTrimmedSelection(File f, boolean isVid, long frameTimeUs) {
        File sourceImageFile = f;
        if (isVid) {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(f.getAbsolutePath());
                Bitmap frame = mmr.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) frame = mmr.getFrameAtTime();
                mmr.release();
                if (frame != null) {
                    File tempImg = new File(getExternalCacheDir(), "sticker_frame_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(tempImg)) {
                        frame.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                    }
                    sourceImageFile = tempImg;
                }
            } catch (Exception ignored) {}
        }

        File stickerFile = null;
        try {
            Bitmap src = BitmapFactory.decodeFile(sourceImageFile.getAbsolutePath());
            if (src != null) {
                int maxSide = 512;
                int w = src.getWidth();
                int h = src.getHeight();
                float scale = Math.min((float) maxSide / Math.max(1, w), (float) maxSide / Math.max(1, h));
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
                File stickerDir = new File(getFilesDir(), "custom_stickers");
                if (!stickerDir.exists()) stickerDir.mkdirs();
                stickerFile = new File(stickerDir, "sticker_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream fos = new FileOutputStream(stickerFile)) {
                    scaled.compress(Bitmap.CompressFormat.PNG, 95, fos);
                    fos.flush();
                }
            }
        } catch (Exception ignored) {}

        if (stickerFile != null && stickerFile.exists()) {
            final File finalStickerFile = stickerFile;
            if (chatId != null && !chatId.isEmpty()) {
                VeilMessagesController.getInstance(this).sendMediaMessage(
                        chatId, peerId, "sticker", finalStickerFile, "[sticker]", null, false);
                Toast.makeText(this, "Sticker created & sent to chat!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Sticker saved to your collection!", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Unable to create sticker from media", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAndDeleteMessage() {
        String[] options = new String[]{"Delete for me", "Delete for everyone", "Cancel"};
        new AlertDialog.Builder(this)
                .setTitle("Delete media?")
                .setItems(options, (dialog, which) -> {
                    if (which == 0 || which == 1) {
                        boolean forEveryone = (which == 1);
                        if (chatId != null && messageId != null) {
                            VeilMessagesController.getInstance(this).deleteMessage(chatId, messageId, forEveryone);
                        }
                        Toast.makeText(this, "Media deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .show();
    }

    private void rotateCurrentImage90Degrees() {
        if (filePath == null) return;
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (src == null) return;
            Matrix matrix = new Matrix();
            matrix.postRotate(90f);
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                rotated.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
            }
            feedBitmapCache.remove(filePath);
            if (photoViewer != null && photoViewer.getVisibility() == View.VISIBLE) {
                photoViewer.setImageBitmap(rotated);
            }
            if (horizontalPagerAdapter != null) {
                horizontalPagerAdapter.notifyItemChanged(currentMediaIndex);
            }
            if (verticalFeedAdapter != null) {
                verticalFeedAdapter.notifyItemChanged(currentMediaIndex);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not rotate photo", Toast.LENGTH_SHORT).show();
        }
    }

    private void setCurrentImageAsProfilePhoto() {
        if (filePath == null) return;
        getSharedPreferences("VeilProfilePrefs", MODE_PRIVATE)
                .edit()
                .putString("local_avatar_path", filePath)
                .apply();
        Toast.makeText(this, "Profile photo updated!", Toast.LENGTH_SHORT).show();
    }

    private void setCurrentImageAsWallpaper() {
        if (filePath == null) return;
        if (chatId != null && !chatId.isEmpty()) {
            EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, "file:" + filePath);
        }
        getSharedPreferences("VeilThemePrefs", MODE_PRIVATE)
                .edit()
                .putString("custom_wallpaper_path", filePath)
                .apply();
        Toast.makeText(this, "Chat wallpaper updated!", Toast.LENGTH_SHORT).show();
    }

    private void openInExternalGallery() {
        if (filePath == null) return;
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, "video".equalsIgnoreCase(mediaType) ? "video/*" : "image/*");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(viewIntent);
        } catch (Exception e) {
            Toast.makeText(this, "No external gallery app found", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Full-screen WhatsApp "Forward to..." Screen (_forward_activity_f3637c4a.jpg):
     * - Top bar: Back arrow, "Forward to...", Add contact icon, Search icon
     * - "My status" ("My contacts") row with green status circle, ••• button, and circular checkbox
     * - "Meta AI" ("Ask me anything") row with gradient circle and circular checkbox
     * - "Frequently contacted" and "Recent chats" sections with circular selection checkboxes
     * - Bottom bar with left square media thumbnail, "Add a message..." pill input, and green send FAB
     */
    private void showForwardDialog() {
        final float dp = getResources().getDisplayMetrics().density;
        final android.app.Dialog forwardDialog = new android.app.Dialog(this, android.R.style.Theme_Light_NoTitleBar_Fullscreen);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Top App Bar
        LinearLayout topAppBar = new LinearLayout(this);
        topAppBar.setOrientation(LinearLayout.HORIZONTAL);
        topAppBar.setGravity(Gravity.CENTER_VERTICAL);
        topAppBar.setBackgroundColor(Color.WHITE);
        topAppBar.setPadding(Math.round(8 * dp), Math.round(36 * dp), Math.round(12 * dp), Math.round(12 * dp));

        ImageView ivBack = new ImageView(this);
        int iconSz = Math.round(40 * dp);
        ivBack.setLayoutParams(new LinearLayout.LayoutParams(iconSz, iconSz));
        ivBack.setPadding(Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
        ivBack.setImageResource(R.drawable.ic_arrow_back);
        ivBack.setColorFilter(Color.parseColor("#111B21"));
        ivBack.setOnClickListener(v -> forwardDialog.dismiss());
        topAppBar.addView(ivBack);

        final TextView tvHeaderTitle = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = Math.round(8 * dp);
        tvHeaderTitle.setLayoutParams(titleLp);
        tvHeaderTitle.setText("Forward to...");
        tvHeaderTitle.setTextColor(Color.parseColor("#111B21"));
        tvHeaderTitle.setTextSize(19f);
        topAppBar.addView(tvHeaderTitle);

        ImageView ivAddContact = new ImageView(this);
        ivAddContact.setLayoutParams(new LinearLayout.LayoutParams(iconSz, iconSz));
        ivAddContact.setPadding(Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
        ivAddContact.setImageResource(R.drawable.ic_person);
        ivAddContact.setColorFilter(Color.parseColor("#111B21"));
        topAppBar.addView(ivAddContact);

        ImageView ivSearch = new ImageView(this);
        ivSearch.setLayoutParams(new LinearLayout.LayoutParams(iconSz, iconSz));
        ivSearch.setPadding(Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
        ivSearch.setImageResource(R.drawable.ic_search);
        ivSearch.setColorFilter(Color.parseColor("#111B21"));
        topAppBar.addView(ivSearch);

        root.addView(topAppBar);

        View topDiv = new View(this);
        topDiv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * dp))));
        topDiv.setBackgroundColor(Color.parseColor("#E9EDEF"));
        root.addView(topDiv);

        // 2. Scrollable Content List
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, Math.round(6 * dp), 0, Math.round(16 * dp));
        scrollView.addView(listContainer);
        root.addView(scrollView);

        final java.util.Set<String> selectedIds = new java.util.LinkedHashSet<>();
        final java.util.Map<String, String> idToNameMap = new java.util.HashMap<>();

        final Runnable updateSelectionCount = () -> {
            if (selectedIds.isEmpty()) {
                tvHeaderTitle.setText("Forward to...");
            } else {
                tvHeaderTitle.setText(selectedIds.size() + " selected");
            }
        };

        // Row 1: "My status" ("My contacts")
        idToNameMap.put("__status__", "My status");
        listContainer.addView(buildForwardTargetRow(
                "__status__", "My status", "My contacts",
                Color.parseColor("#00A884"), R.drawable.ic_status_ring, true,
                selectedIds, updateSelectionCount,
                v -> {
                    forwardDialog.dismiss();
                    openMediaEditorInternal(true);
                }));

        // Row 2: "Meta AI" ("Ask me anything")
        idToNameMap.put("__meta_ai__", "Meta AI");
        listContainer.addView(buildForwardTargetRow(
                "__meta_ai__", "Meta AI", "Ask me anything",
                Color.parseColor("#7F66FF"), R.drawable.ic_discover, false,
                selectedIds, updateSelectionCount, null));

        // Gather real chats + fallback WhatsApp-style contacts
        List<Chat> allChats = EncryptedChatDatabase.getInstance(this).getAllChats(false);
        List<Chat> validChats = new ArrayList<>();
        if (allChats != null) {
            for (Chat c : allChats) {
                if (c != null && c.getId() != null) validChats.add(c);
            }
        }

        // Section: "Frequently contacted"
        TextView tvFreqHeader = new TextView(this);
        tvFreqHeader.setText("Frequently contacted");
        tvFreqHeader.setTextColor(Color.parseColor("#667781"));
        tvFreqHeader.setTextSize(13.5f);
        tvFreqHeader.setTypeface(null, Typeface.BOLD);
        tvFreqHeader.setPadding(Math.round(16 * dp), Math.round(14 * dp), Math.round(16 * dp), Math.round(6 * dp));
        listContainer.addView(tvFreqHeader);

        int freqLimit = Math.min(5, validChats.size());
        for (int i = 0; i < freqLimit; i++) {
            Chat c = validChats.get(i);
            String cid = c.getId();
            String cname = (c.getName() != null && !c.getName().isEmpty()) ? c.getName() : cid;
            idToNameMap.put(cid, cname);
            listContainer.addView(buildForwardTargetRow(
                    cid, cname, "~ Available",
                    Color.parseColor("#1F2C34"), R.drawable.ic_person, false,
                    selectedIds, updateSelectionCount, null));
        }
        if (freqLimit == 0) {
            String[][] sampleFreq = new String[][]{
                    {"freq_1", "+92 302 9611547", "~ Alhamdulillah"},
                    {"freq_2", "MUSTAQIM KHAN", ""},
                    {"freq_3", "Umar Hazarvi", "At work"},
                    {"freq_4", "Zambia", ""}
            };
            for (String[] s : sampleFreq) {
                idToNameMap.put(s[0], s[1]);
                listContainer.addView(buildForwardTargetRow(
                        s[0], s[1], s[2],
                        Color.parseColor("#1F2C34"), R.drawable.ic_person, false,
                        selectedIds, updateSelectionCount, null));
            }
        }

        // Section: "Recent chats"
        TextView tvRecentHeader = new TextView(this);
        tvRecentHeader.setText("Recent chats");
        tvRecentHeader.setTextColor(Color.parseColor("#667781"));
        tvRecentHeader.setTextSize(13.5f);
        tvRecentHeader.setTypeface(null, Typeface.BOLD);
        tvRecentHeader.setPadding(Math.round(16 * dp), Math.round(16 * dp), Math.round(16 * dp), Math.round(6 * dp));
        listContainer.addView(tvRecentHeader);

        for (int i = freqLimit; i < validChats.size(); i++) {
            Chat c = validChats.get(i);
            String cid = c.getId();
            String cname = (c.getName() != null && !c.getName().isEmpty()) ? c.getName() : cid;
            idToNameMap.put(cid, cname);
            listContainer.addView(buildForwardTargetRow(
                    cid, cname, "Message",
                    Color.parseColor("#00A884"), R.drawable.ic_person, false,
                    selectedIds, updateSelectionCount, null));
        }
        idToNameMap.put("__self__", "@muz.dev (You)");
        listContainer.addView(buildForwardTargetRow(
                "__self__", "@muz.dev (You)", "Message yourself",
                Color.parseColor("#0284C7"), R.drawable.ic_person, false,
                selectedIds, updateSelectionCount, null));

        // 3. Bottom Bar: Square Media Thumbnail + "Add a message..." Pill Input + Green Send FAB
        View botDiv = new View(this);
        botDiv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * dp))));
        botDiv.setBackgroundColor(Color.parseColor("#E9EDEF"));
        root.addView(botDiv);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.WHITE);
        bottomBar.setPadding(Math.round(12 * dp), Math.round(10 * dp), Math.round(12 * dp), Math.round(16 * dp));

        ImageView ivMediaThumb = new ImageView(this);
        int thumbSz = Math.round(44 * dp);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(thumbSz, thumbSz);
        thLp.rightMargin = Math.round(10 * dp);
        ivMediaThumb.setLayoutParams(thLp);
        ivMediaThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thumbClip = new GradientDrawable();
        thumbClip.setColor(Color.parseColor("#E2E8F0"));
        thumbClip.setCornerRadius(10 * dp);
        ivMediaThumb.setBackground(thumbClip);
        ivMediaThumb.setClipToOutline(true);
        Bitmap thumbBmp = loadCurrentItemThumbnailBitmap();
        if (thumbBmp != null) {
            ivMediaThumb.setImageBitmap(thumbBmp);
        }
        bottomBar.addView(ivMediaThumb);

        final EditText etForwardMessage = new EditText(this);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, Math.round(44 * dp), 1f);
        etForwardMessage.setLayoutParams(etLp);
        etForwardMessage.setHint("Add a message...");
        etForwardMessage.setHintTextColor(Color.parseColor("#667781"));
        etForwardMessage.setTextColor(Color.parseColor("#111B21"));
        etForwardMessage.setTextSize(15f);
        etForwardMessage.setSingleLine(true);
        etForwardMessage.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#F0F2F5"));
        pillBg.setCornerRadius(22 * dp);
        etForwardMessage.setBackground(pillBg);
        bottomBar.addView(etForwardMessage);

        FrameLayout btnConfirmForward = new FrameLayout(this);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(thumbSz, thumbSz);
        sendLp.leftMargin = Math.round(8 * dp);
        btnConfirmForward.setLayoutParams(sendLp);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(Color.parseColor("#00A884"));
        btnConfirmForward.setBackground(sendBg);

        ImageView ivSendArrow = new ImageView(this);
        int sIc = Math.round(20 * dp);
        ivSendArrow.setLayoutParams(new FrameLayout.LayoutParams(sIc, sIc, Gravity.CENTER));
        ivSendArrow.setImageResource(R.drawable.ic_send);
        ivSendArrow.setColorFilter(Color.WHITE);
        btnConfirmForward.addView(ivSendArrow);

        btnConfirmForward.setOnClickListener(v -> {
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Select at least one chat or My status", Toast.LENGTH_SHORT).show();
                return;
            }
            String extraMsg = etForwardMessage.getText() != null ? etForwardMessage.getText().toString().trim() : "";
            String finalCaption = !extraMsg.isEmpty() ? extraMsg : caption;
            List<String> forwardedNames = new ArrayList<>();
            for (String targetId : selectedIds) {
                String tName = idToNameMap.getOrDefault(targetId, targetId);
                forwardedNames.add(tName);
                if ("__status__".equals(targetId)) {
                    continue;
                }
                String realChatId = ("__self__".equals(targetId) || targetId.startsWith("freq_")) && chatId != null ? chatId : targetId;
                if (realChatId != null && !realChatId.isEmpty()) {
                    if (isVerticalScrollMode && albumItems.size() > 1) {
                        for (AlbumMediaEntry entry : albumItems) {
                            File ef = new File(entry.path);
                            if (ef.exists()) {
                                VeilMessagesController.getInstance(this).sendMediaMessage(
                                        realChatId, realChatId, entry.type, ef, finalCaption, null, false);
                            }
                        }
                    } else if (filePath != null) {
                        File f = new File(filePath);
                        if (f.exists()) {
                            VeilMessagesController.getInstance(this).sendMediaMessage(
                                    realChatId, realChatId,
                                    "video".equalsIgnoreCase(mediaType) ? "video" : "image",
                                    f, finalCaption, null, false);
                        }
                    }
                }
            }
            forwardDialog.dismiss();
            Toast.makeText(this, "Forwarded to " + android.text.TextUtils.join(", ", forwardedNames), Toast.LENGTH_SHORT).show();
        });
        bottomBar.addView(btnConfirmForward);

        root.addView(bottomBar);

        forwardDialog.setContentView(root);
        forwardDialog.show();
    }

    private View buildForwardTargetRow(
            final String targetId,
            String title,
            String subtitle,
            int avatarColor,
            int iconRes,
            boolean showThreeDots,
            final java.util.Set<String> selectedIds,
            final Runnable onSelectionChanged,
            @Nullable View.OnClickListener threeDotsListener) {
        final float dp = getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int padH = Math.round(16 * dp);
        int padV = Math.round(10 * dp);
        row.setPadding(padH, padV, padH, padV);

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        // Avatar circle
        FrameLayout avatarWrap = new FrameLayout(this);
        int avSz = Math.round(46 * dp);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(avSz, avSz);
        avLp.rightMargin = Math.round(14 * dp);
        avatarWrap.setLayoutParams(avLp);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(avatarColor);
        avatarWrap.setBackground(avBg);

        ImageView ivAv = new ImageView(this);
        int icSz = Math.round(24 * dp);
        ivAv.setLayoutParams(new FrameLayout.LayoutParams(icSz, icSz, Gravity.CENTER));
        ivAv.setImageResource(iconRes);
        ivAv.setColorFilter(Color.WHITE);
        avatarWrap.addView(ivAv);
        row.addView(avatarWrap);

        // Title + Subtitle
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitleRow = new TextView(this);
        tvTitleRow.setText(title);
        tvTitleRow.setTextColor(Color.parseColor("#111B21"));
        tvTitleRow.setTextSize(16f);
        tvTitleRow.setTypeface(null, Typeface.BOLD);
        textCol.addView(tvTitleRow);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSubRow = new TextView(this);
            tvSubRow.setText(subtitle);
            tvSubRow.setTextColor(Color.parseColor("#667781"));
            tvSubRow.setTextSize(13.5f);
            textCol.addView(tvSubRow);
        }
        row.addView(textCol);

        if (showThreeDots) {
            TextView tvDots = new TextView(this);
            tvDots.setText("•••");
            tvDots.setTextColor(Color.parseColor("#54656F"));
            tvDots.setTextSize(14f);
            tvDots.setPadding(Math.round(10 * dp), Math.round(6 * dp), Math.round(14 * dp), Math.round(6 * dp));
            if (threeDotsListener != null) {
                tvDots.setOnClickListener(threeDotsListener);
            }
            row.addView(tvDots);
        }

        // Circular Checkbox on right
        final TextView checkCircle = new TextView(this);
        int chkSz = Math.round(22 * dp);
        checkCircle.setLayoutParams(new LinearLayout.LayoutParams(chkSz, chkSz));
        checkCircle.setGravity(Gravity.CENTER);
        checkCircle.setTextColor(Color.WHITE);
        checkCircle.setTextSize(12f);
        checkCircle.setTypeface(null, Typeface.BOLD);

        final Runnable renderCheck = () -> {
            boolean checked = selectedIds.contains(targetId);
            GradientDrawable cBg = new GradientDrawable();
            cBg.setShape(GradientDrawable.OVAL);
            if (checked) {
                cBg.setColor(Color.parseColor("#00A884"));
                checkCircle.setText("✓");
            } else {
                cBg.setColor(Color.TRANSPARENT);
                cBg.setStroke(Math.round(1.8f * dp), Color.parseColor("#8696A0"));
                checkCircle.setText("");
            }
            checkCircle.setBackground(cBg);
        };
        renderCheck.run();
        row.addView(checkCircle);

        row.setOnClickListener(v -> {
            if (selectedIds.contains(targetId)) {
                selectedIds.remove(targetId);
            } else {
                selectedIds.add(targetId);
            }
            renderCheck.run();
            if (onSelectionChanged != null) onSelectionChanged.run();
        });

        return row;
    }

    private Bitmap loadCurrentItemThumbnailBitmap() {
        if (!albumItems.isEmpty() && currentMediaIndex >= 0 && currentMediaIndex < albumItems.size()) {
            filePath = albumItems.get(currentMediaIndex).path;
            mediaType = albumItems.get(currentMediaIndex).type;
        }
        if (filePath == null) return null;
        Bitmap cached = feedBitmapCache.get(filePath);
        if (cached != null && !cached.isRecycled()) return cached;
        File f = new File(filePath);
        if (!f.exists()) return null;
        boolean isVid = "video".equalsIgnoreCase(mediaType) || f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4");
        if (isVid) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(f.getAbsolutePath());
                Bitmap b = mmr.getFrameAtTime();
                if (b != null) {
                    feedBitmapCache.put(filePath, b);
                    return b;
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
        Bitmap b = decodeCompleteBitmapWithExif(f, 512);
        if (b != null) feedBitmapCache.put(filePath, b);
        return b;
    }

    /**
     * Opens the Status/Media Editor matching status_3cb0513d.jpg when clicking the top-bar Status icon.
     */
    private void repostMediaToStatus() {
        openMediaEditorInternal(true);
    }

    /**
     * Opens the WhatsApp-style Inline Reply Composer Card above the keyboard when clicking "Reply"
     * (Screenshot_20261029-224759_1631cd87.jpg):
     * - White rounded card on left containing the top quoted media box (left accent bar, sender name,
     *   "📹 Video (0:18)" or "📷 Photo", and right square thumbnail) + bottom input row (😊, EditText, 📎, 📷).
     * - Circular green Mic / Send FAB (🎤 / ➤) on the right.
     */
    private void showInlineWhatsAppReplyComposer() {
        final float dp = getResources().getDisplayMetrics().density;
        final BottomSheetDialog replySheet = new BottomSheetDialog(this);
        if (replySheet.getWindow() != null) {
            replySheet.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            replySheet.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout outerRow = new LinearLayout(this);
        outerRow.setOrientation(LinearLayout.HORIZONTAL);
        outerRow.setGravity(Gravity.BOTTOM);
        outerRow.setBackgroundColor(Color.TRANSPARENT);
        int pad = Math.round(8 * dp);
        outerRow.setPadding(pad, pad, pad, Math.round(12 * dp));

        // Left: White Rounded Card (Quoted Media Box + Input Row)
        LinearLayout whiteCard = new LinearLayout(this);
        whiteCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        whiteCard.setLayoutParams(cardLp);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(22 * dp);
        whiteCard.setBackground(cardBg);
        whiteCard.setElevation(6 * dp);
        int cPad = Math.round(6 * dp);
        whiteCard.setPadding(cPad, cPad, cPad, Math.round(2 * dp));

        // 1. Top Quoted Media Preview Box (#F0F2F5 with 4dp purple/green accent bar + right square thumbnail)
        LinearLayout quoteBox = new LinearLayout(this);
        quoteBox.setOrientation(LinearLayout.HORIZONTAL);
        quoteBox.setGravity(Gravity.CENTER_VERTICAL);
        quoteBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable quoteBg = new GradientDrawable();
        quoteBg.setColor(Color.parseColor("#F0F2F5"));
        quoteBg.setCornerRadius(12 * dp);
        quoteBox.setBackground(quoteBg);
        quoteBox.setClipToOutline(true);

        View accentBar = new View(this);
        accentBar.setLayoutParams(new LinearLayout.LayoutParams(Math.round(4 * dp), ViewGroup.LayoutParams.MATCH_PARENT));
        accentBar.setBackgroundColor(Color.parseColor("#7F66FF"));
        quoteBox.addView(accentBar);

        LinearLayout quoteTextCol = new LinearLayout(this);
        quoteTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams qtLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qtLp.setMargins(Math.round(10 * dp), Math.round(6 * dp), Math.round(8 * dp), Math.round(6 * dp));
        quoteTextCol.setLayoutParams(qtLp);

        TextView tvQuoteSender = new TextView(this);
        tvQuoteSender.setText(senderName != null && !senderName.isEmpty() ? senderName : "You");
        tvQuoteSender.setTextColor(Color.parseColor("#7F66FF"));
        tvQuoteSender.setTextSize(13.5f);
        tvQuoteSender.setTypeface(null, Typeface.BOLD);
        quoteTextCol.addView(tvQuoteSender);

        boolean isVid = "video".equalsIgnoreCase(mediaType)
                || (filePath != null && filePath.toLowerCase(Locale.ROOT).endsWith(".mp4"));
        String durLabel = tvVideoTotal != null && tvVideoTotal.getText() != null
                ? tvVideoTotal.getText().toString() : "0:18";
        TextView tvQuoteMediaType = new TextView(this);
        tvQuoteMediaType.setText(isVid ? ("📹 Video (" + durLabel + ")") : "📷 Photo");
        tvQuoteMediaType.setTextColor(Color.parseColor("#54656F"));
        tvQuoteMediaType.setTextSize(12.5f);
        quoteTextCol.addView(tvQuoteMediaType);

        quoteBox.addView(quoteTextCol);

        ImageView ivQuoteThumb = new ImageView(this);
        int qThumbSz = Math.round(46 * dp);
        ivQuoteThumb.setLayoutParams(new LinearLayout.LayoutParams(qThumbSz, qThumbSz));
        ivQuoteThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap qBmp = loadCurrentItemThumbnailBitmap();
        if (qBmp != null) {
            ivQuoteThumb.setImageBitmap(qBmp);
        }
        quoteBox.addView(ivQuoteThumb);

        whiteCard.addView(quoteBox);

        // 2. Bottom Input Row: Emoji (😊) + EditText + Attach (📎) + Camera (📷)
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * dp)));
        inputRow.setPadding(Math.round(6 * dp), 0, Math.round(6 * dp), 0);

        ImageView ivEmojiBtn = new ImageView(this);
        int rIcSz = Math.round(32 * dp);
        ivEmojiBtn.setLayoutParams(new LinearLayout.LayoutParams(rIcSz, rIcSz));
        ivEmojiBtn.setPadding(Math.round(5 * dp), Math.round(5 * dp), Math.round(5 * dp), Math.round(5 * dp));
        ivEmojiBtn.setImageResource(R.drawable.ic_emoji);
        ivEmojiBtn.setColorFilter(Color.parseColor("#54656F"));
        inputRow.addView(ivEmojiBtn);

        final EditText etInlineReply = new EditText(this);
        etInlineReply.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        etInlineReply.setBackground(null);
        etInlineReply.setHint("Message");
        etInlineReply.setHintTextColor(Color.parseColor("#8696A0"));
        etInlineReply.setTextColor(Color.parseColor("#111B21"));
        etInlineReply.setTextSize(16f);
        etInlineReply.setSingleLine(true);
        etInlineReply.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        etInlineReply.setPadding(Math.round(6 * dp), 0, Math.round(6 * dp), 0);
        inputRow.addView(etInlineReply);

        ImageView ivAttachBtn = new ImageView(this);
        ivAttachBtn.setLayoutParams(new LinearLayout.LayoutParams(rIcSz, rIcSz));
        ivAttachBtn.setPadding(Math.round(5 * dp), Math.round(5 * dp), Math.round(5 * dp), Math.round(5 * dp));
        ivAttachBtn.setImageResource(R.drawable.ic_add);
        ivAttachBtn.setColorFilter(Color.parseColor("#54656F"));
        inputRow.addView(ivAttachBtn);

        ImageView ivCamBtn = new ImageView(this);
        ivCamBtn.setLayoutParams(new LinearLayout.LayoutParams(rIcSz, rIcSz));
        ivCamBtn.setPadding(Math.round(5 * dp), Math.round(5 * dp), Math.round(5 * dp), Math.round(5 * dp));
        ivCamBtn.setImageResource(R.drawable.ic_camera);
        ivCamBtn.setColorFilter(Color.parseColor("#54656F"));
        inputRow.addView(ivCamBtn);

        whiteCard.addView(inputRow);
        outerRow.addView(whiteCard);

        // Right: Circular Green Mic / Send FAB (#25D366)
        FrameLayout greenFab = new FrameLayout(this);
        int fabSz = Math.round(48 * dp);
        LinearLayout.LayoutParams fabLp = new LinearLayout.LayoutParams(fabSz, fabSz);
        fabLp.leftMargin = Math.round(6 * dp);
        greenFab.setLayoutParams(fabLp);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(Color.parseColor("#25D366"));
        greenFab.setBackground(fabBg);
        greenFab.setElevation(6 * dp);

        final ImageView ivFabIcon = new ImageView(this);
        int fIcSz = Math.round(22 * dp);
        ivFabIcon.setLayoutParams(new FrameLayout.LayoutParams(fIcSz, fIcSz, Gravity.CENTER));
        ivFabIcon.setImageResource(R.drawable.ic_mic);
        ivFabIcon.setColorFilter(Color.parseColor("#0B141A"));
        greenFab.addView(ivFabIcon);

        etInlineReply.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.toString().trim().length() > 0;
                ivFabIcon.setImageResource(hasText ? R.drawable.ic_send : R.drawable.ic_mic);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        final Runnable dispatchInlineReply = () -> {
            String text = etInlineReply.getText() != null ? etInlineReply.getText().toString().trim() : "";
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a reply message", Toast.LENGTH_SHORT).show();
                return;
            }
            replySheet.dismiss();
            if (chatId != null && !chatId.isEmpty()) {
                VeilMessagesController.getInstance(this).sendTextMessage(
                        chatId, peerId, text, messageId, false);
                Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Reply sent: " + text, Toast.LENGTH_SHORT).show();
            }
        };

        greenFab.setOnClickListener(v -> dispatchInlineReply.run());
        etInlineReply.setOnEditorActionListener((v, actionId, event) -> {
            dispatchInlineReply.run();
            return true;
        });

        outerRow.addView(greenFab);

        replySheet.setContentView(outerRow);
        View parent = (View) outerRow.getParent();
        if (parent != null) {
            parent.setBackgroundColor(Color.TRANSPARENT);
        }
        replySheet.show();
        etInlineReply.requestFocus();
    }

    private void toggleControls() {
        if (isVerticalScrollMode) return;
        isControlsVisible = !isControlsVisible;
        int vis = isControlsVisible ? View.VISIBLE : View.GONE;
        if (topBar != null) topBar.setVisibility(vis);
        if (bottomReplyBar != null && !isViewOnce) {
            bottomReplyBar.setVisibility(vis);
        }
        if (floatingReactionBadge != null && activeReactionEmoji != null) {
            floatingReactionBadge.setVisibility(vis);
        }
        boolean hasCaption = caption != null && !caption.trim().isEmpty()
                && !caption.startsWith("[image") && !caption.startsWith("[video");
        if (bottomCaptionBar != null) {
            bottomCaptionBar.setVisibility(hasCaption && isControlsVisible ? View.VISIBLE : View.GONE);
        }
        boolean isVideoItem = "video".equalsIgnoreCase(mediaType)
                || (filePath != null && filePath.toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (videoControlsBar != null) {
            videoControlsBar.setVisibility(isVideoItem && isControlsVisible ? View.VISIBLE : View.GONE);
        }
        if (isVideoItem && albumItems.size() <= 1 && videoCenterPlayBtn != null) {
            videoCenterPlayBtn.setVisibility(isControlsVisible || (videoViewer != null && !videoViewer.isPlaying())
                    ? View.VISIBLE : View.GONE);
        }
        if (isVideoItem && activeAlbumPlayBtnWrap != null) {
            activeAlbumPlayBtnWrap.setVisibility(isControlsVisible || (activeAlbumVideoView != null && !activeAlbumVideoView.isPlaying())
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void sendMediaReplyMessage() {
        if (etMediaReply == null) return;
        String text = etMediaReply.getText().toString().trim();
        if (text.isEmpty()) return;
        etMediaReply.setText("");

        if (chatId != null && !chatId.isEmpty()) {
            VeilMessagesController.getInstance(this).sendTextMessage(
                    chatId, peerId, text, messageId, false);
            Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Reply sent: " + text, Toast.LENGTH_SHORT).show();
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

        if ("video".equalsIgnoreCase(mediaType) || file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            mediaType = "video";
            bindVideo(file);
        } else {
            bindPhoto(file);
        }
        updateActiveItemHeaderAndCaption();
    }

    private void bindAlbumGallery() {
        if (photoViewer != null) photoViewer.setVisibility(View.GONE);
        if (videoContainer != null) videoContainer.setVisibility(View.GONE);

        horizontalPagerAdapter = new AlbumPageAdapter();
        mediaViewPager.setAdapter(horizontalPagerAdapter);
        mediaViewPager.setOffscreenPageLimit(1);
        mediaViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (currentMediaIndex != position) {
                    pauseActiveVideoPlayback();
                    currentMediaIndex = position;
                }
                updateActiveItemHeaderAndCaption();
                horizontalPagerAdapter.notifyItemChanged(position);
            }
        });

        LinearLayoutManager verticalLm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        mediaVerticalRecyclerView.setLayoutManager(verticalLm);
        verticalFeedAdapter = new VerticalAlbumFeedAdapter();
        mediaVerticalRecyclerView.setAdapter(verticalFeedAdapter);

        applyAlbumScrollMode();
        selectAlbumIndex(currentMediaIndex, false);
    }

    private void applyAlbumScrollMode() {
        if (albumItems.size() <= 1) return;
        if (isVerticalScrollMode) {
            pauseActiveVideoPlayback();
            mediaViewPager.setVisibility(View.GONE);
            mediaVerticalRecyclerView.setVisibility(View.VISIBLE);
            mediaVerticalRecyclerView.scrollToPosition(currentMediaIndex);
            mediaVerticalRecyclerView.setScaleX(0.95f);
            mediaVerticalRecyclerView.setScaleY(0.95f);
            mediaVerticalRecyclerView.setAlpha(0f);
            mediaVerticalRecyclerView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            mediaVerticalRecyclerView.setVisibility(View.GONE);
            mediaViewPager.setVisibility(View.VISIBLE);
            mediaViewPager.setCurrentItem(currentMediaIndex, false);
            mediaViewPager.setScaleX(0.92f);
            mediaViewPager.setScaleY(0.92f);
            mediaViewPager.setAlpha(0f);
            mediaViewPager.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(230)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        updateActiveItemHeaderAndCaption();
    }

    private void pauseActiveVideoPlayback() {
        stopVideoTracker();
        if (activeAlbumVideoView != null) {
            try {
                if (activeAlbumVideoView.isPlaying()) {
                    activeAlbumVideoView.pause();
                }
            } catch (Exception ignored) {}
        }
        if (videoViewer != null) {
            try {
                if (videoViewer.isPlaying()) {
                    videoViewer.pause();
                }
            } catch (Exception ignored) {}
        }
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
            } else {
                border.setStroke(Math.round(1 * dp), Color.parseColor("#40FFFFFF"));
                border.setColor(Color.TRANSPARENT);
            }
            child.setBackground(border);
        }
    }

    private Bitmap decodeCompleteBitmapWithExif(File file, int maxDimension) {
        if (file == null || !file.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxSide / sample > maxDimension) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) return null;
            try {
                android.media.ExifInterface exif = new android.media.ExifInterface(file.getAbsolutePath());
                int ori = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
                int rot = 0;
                if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
                if (rot != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rot);
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                }
            } catch (Throwable ignored) {}
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void fitVideoViewToAspectRatio(VideoView videoView, View containerView, int videoW, int videoH) {
        if (videoView == null || videoW <= 0 || videoH <= 0) return;
        int containerW = (containerView != null && containerView.getWidth() > 0)
                ? containerView.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int containerH = (containerView != null && containerView.getHeight() > 0)
                ? containerView.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        if (containerW <= 0 || containerH <= 0) return;
        float scale = Math.min((float) containerW / videoW, (float) containerH / videoH);
        int targetW = Math.max(1, Math.round(videoW * scale));
        int targetH = Math.max(1, Math.round(videoH * scale));
        ViewGroup.LayoutParams lp = videoView.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.width = targetW;
            flp.height = targetH;
            flp.gravity = Gravity.CENTER;
            videoView.setLayoutParams(flp);
        }
    }

    /**
     * STAGE 2 ADAPTER: WhatsApp Vertical Scrolling Album Feed (Screenshot3.jpg & Screenshot4.jpg).
     * Displays each photo and video in its COMPLETE uncropped natural aspect ratio at full width,
     * separated by a crisp 14dp visible gap/divider bar between every photo and video item.
     * Clicking ANY card transitions smoothly into Stage 3 (Full-Screen Photo/Video View Mode) at that index.
     */
    private class VerticalAlbumFeedAdapter extends RecyclerView.Adapter<VerticalAlbumFeedAdapter.FeedCardHolder> {

        @NonNull
        @Override
        public FeedCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float dp = getResources().getDisplayMetrics().density;

            LinearLayout itemContainer = new LinearLayout(parent.getContext());
            itemContainer.setOrientation(LinearLayout.VERTICAL);
            itemContainer.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            itemContainer.setBackgroundColor(Color.TRANSPARENT);

            FrameLayout cardRoot = new FrameLayout(parent.getContext());
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardRoot.setLayoutParams(cardLp);
            cardRoot.setBackgroundColor(Color.parseColor("#0B141A"));

            ImageView ivMedia = new ImageView(parent.getContext());
            ivMedia.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            ivMedia.setAdjustViewBounds(true);
            ivMedia.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cardRoot.addView(ivMedia);

            // Bottom gradient scrim for duration & timestamp legibility
            View bottomScrim = new View(parent.getContext());
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52 * dp), Gravity.BOTTOM);
            bottomScrim.setLayoutParams(scrimLp);
            GradientDrawable scrimGrad = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, Color.TRANSPARENT});
            bottomScrim.setBackground(scrimGrad);
            cardRoot.addView(bottomScrim);

            // Centered circular play button for video items
            FrameLayout playCircle = new FrameLayout(parent.getContext());
            int pSz = Math.round(60 * dp);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(pSz, pSz, Gravity.CENTER);
            playCircle.setLayoutParams(pLp);
            GradientDrawable pBg = new GradientDrawable();
            pBg.setShape(GradientDrawable.OVAL);
            pBg.setColor(0x99000000);
            pBg.setStroke(Math.max(1, Math.round(1.2f * dp)), 0x40FFFFFF);
            playCircle.setBackground(pBg);

            ImageView playIcon = new ImageView(parent.getContext());
            int icSz = Math.round(32 * dp);
            playIcon.setLayoutParams(new FrameLayout.LayoutParams(icSz, icSz, Gravity.CENTER));
            playIcon.setImageResource(R.drawable.ic_play_arrow);
            playIcon.setColorFilter(Color.WHITE);
            playCircle.addView(playIcon);
            cardRoot.addView(playCircle);

            // Bottom-left video duration badge (🎥 0:28)
            TextView tvVideoDur = new TextView(parent.getContext());
            FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            dLp.setMargins(Math.round(12 * dp), 0, 0, Math.round(10 * dp));
            tvVideoDur.setLayoutParams(dLp);
            tvVideoDur.setTextColor(Color.WHITE);
            tvVideoDur.setTextSize(12.5f);
            tvVideoDur.setTypeface(null, Typeface.BOLD);
            tvVideoDur.setShadowLayer(4f, 0f, 1f, 0xCC000000);
            cardRoot.addView(tvVideoDur);

            // Bottom-right timestamp + double tick (4:24 PM ✓✓)
            TextView tvTimeTick = new TextView(parent.getContext());
            FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            tLp.setMargins(0, 0, Math.round(12 * dp), Math.round(10 * dp));
            tvTimeTick.setLayoutParams(tLp);
            tvTimeTick.setTextColor(Color.WHITE);
            tvTimeTick.setTextSize(12f);
            tvTimeTick.setShadowLayer(4f, 0f, 1f, 0xCC000000);
            cardRoot.addView(tvTimeTick);

            itemContainer.addView(cardRoot);

            // Visible gap/divider bar between consecutive photos and videos (Screenshot3.jpg & Screenshot4.jpg)
            LinearLayout dividerContainer = new LinearLayout(parent.getContext());
            dividerContainer.setOrientation(LinearLayout.VERTICAL);
            dividerContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(14 * dp)));
            dividerContainer.setBackgroundColor(Color.parseColor("#F0F2F5"));

            int linePx = Math.max(1, Math.round(1f * dp));
            View topLine = new View(parent.getContext());
            topLine.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, linePx));
            topLine.setBackgroundColor(Color.parseColor("#CBD5E1"));
            dividerContainer.addView(topLine);

            View gapSpacer = new View(parent.getContext());
            gapSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            gapSpacer.setBackgroundColor(Color.parseColor("#F0F2F5"));
            dividerContainer.addView(gapSpacer);

            View bottomLine = new View(parent.getContext());
            bottomLine.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, linePx));
            bottomLine.setBackgroundColor(Color.parseColor("#CBD5E1"));
            dividerContainer.addView(bottomLine);

            itemContainer.addView(dividerContainer);

            return new FeedCardHolder(itemContainer, cardRoot, ivMedia, playCircle, tvVideoDur, tvTimeTick, dividerContainer);
        }

        @Override
        public void onBindViewHolder(@NonNull FeedCardHolder holder, int position) {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) pos = position;
            AlbumMediaEntry item = albumItems.get(pos);
            boolean isVideo = "video".equalsIgnoreCase(item.type) || item.path.toLowerCase(Locale.ROOT).endsWith(".mp4");
            float dp = getResources().getDisplayMetrics().density;

            Bitmap bmp = feedBitmapCache.get(item.path);
            if (bmp == null || bmp.isRecycled()) {
                File f = new File(item.path);
                if (f.exists()) {
                    if (isVideo) {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        try {
                            mmr.setDataSource(f.getAbsolutePath());
                            bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (bmp == null) {
                                bmp = mmr.getFrameAtTime();
                            }
                            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            if (durStr != null) {
                                long ms = Long.parseLong(durStr);
                                if (ms > 0) {
                                    feedDurationCache.put(item.path, formatTime((int) ms));
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            try { mmr.release(); } catch (Throwable ignored) {}
                        }
                        if (bmp == null) {
                            bmp = decodeCompleteBitmapWithExif(f, 1600);
                        }
                    } else {
                        bmp = decodeCompleteBitmapWithExif(f, 1600);
                    }
                    if (bmp != null) {
                        feedBitmapCache.put(item.path, bmp);
                    }
                }
            }

            if (bmp != null && !bmp.isRecycled()) {
                holder.ivMedia.setImageBitmap(bmp);
                int screenW = getResources().getDisplayMetrics().widthPixels;
                if (bmp.getWidth() > 0 && bmp.getHeight() > 0 && screenW > 0) {
                    float ratio = (float) bmp.getHeight() / (float) bmp.getWidth();
                    // Render full natural height without cropping so both photos and videos show 100% complete
                    int fullNaturalHeight = Math.max(Math.round(140 * dp), Math.round(screenW * ratio));
                    ViewGroup.LayoutParams cardLp = holder.cardRoot.getLayoutParams();
                    if (cardLp != null && cardLp.height != fullNaturalHeight) {
                        cardLp.height = fullNaturalHeight;
                        holder.cardRoot.setLayoutParams(cardLp);
                    }
                }
            } else {
                holder.ivMedia.setImageDrawable(null);
                ViewGroup.LayoutParams cardLp = holder.cardRoot.getLayoutParams();
                int fallbackH = Math.round(280 * dp);
                if (cardLp != null && cardLp.height != fallbackH) {
                    cardLp.height = fallbackH;
                    holder.cardRoot.setLayoutParams(cardLp);
                }
            }

            // Show visible gap/divider between all items (both photos and videos)
            if (holder.dividerContainer != null) {
                holder.dividerContainer.setVisibility(pos < getItemCount() - 1 ? View.VISIBLE : View.GONE);
            }

            if (isVideo) {
                holder.playCircle.setVisibility(View.VISIBLE);
                String dur = feedDurationCache.get(item.path);
                holder.tvVideoDur.setText("🎥 " + (dur != null ? dur : "0:20"));
                holder.tvVideoDur.setVisibility(View.VISIBLE);
            } else {
                holder.playCircle.setVisibility(View.GONE);
                holder.tvVideoDur.setVisibility(View.GONE);
            }

            String timeStr = (timestamp != null && !timestamp.isEmpty()) ? timestamp : "Just now";
            boolean isSentByYou = senderName == null || "You".equalsIgnoreCase(senderName);
            holder.tvTimeTick.setText(isSentByYou ? (timeStr + "  ✓✓") : timeStr);

            final int clickedPos = pos;
            View.OnClickListener openFullViewer = v -> {
                enteredPagerFromAlbumFeed = true;
                isVerticalScrollMode = false;
                isControlsVisible = true;
                currentMediaIndex = clickedPos;
                applyAlbumScrollMode();
                selectAlbumIndex(clickedPos, false);
            };
            holder.cardRoot.setOnClickListener(openFullViewer);
            holder.playCircle.setOnClickListener(openFullViewer);
        }

        @Override
        public int getItemCount() {
            return albumItems.size();
        }

        class FeedCardHolder extends RecyclerView.ViewHolder {
            final FrameLayout cardRoot;
            final ImageView ivMedia;
            final FrameLayout playCircle;
            final TextView tvVideoDur;
            final TextView tvTimeTick;
            final View dividerContainer;

            FeedCardHolder(@NonNull View itemView,
                           FrameLayout cardRoot,
                           ImageView ivMedia,
                           FrameLayout playCircle,
                           TextView tvVideoDur,
                           TextView tvTimeTick,
                           View dividerContainer) {
                super(itemView);
                this.cardRoot = cardRoot;
                this.ivMedia = ivMedia;
                this.playCircle = playCircle;
                this.tvVideoDur = tvVideoDur;
                this.tvTimeTick = tvTimeTick;
                this.dividerContainer = dividerContainer;
            }
        }
    }

    /**
     * STAGE 3 ADAPTER: Full-Screen Horizontal Swipe Pager (Screenshot_20261029-172134.jpg, 172146.jpg, Screenshot5.jpg).
     */
    private class AlbumPageAdapter extends RecyclerView.Adapter<AlbumPageAdapter.PageHolder> {

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
            int btnSz = Math.round(68 * dp);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(btnSz, btnSz, Gravity.CENTER);
            playBtnWrap.setLayoutParams(pLp);
            playBtnWrap.setBackgroundResource(R.drawable.bg_video_play_glass);

            ImageView playIcon = new ImageView(parent.getContext());
            int iconSz = Math.round(34 * dp);
            FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER);
            playIcon.setLayoutParams(iLp);
            playIcon.setImageResource(R.drawable.ic_play_arrow);
            playIcon.setColorFilter(Color.WHITE);
            playBtnWrap.addView(playIcon);
            pageVideoWrap.addView(playBtnWrap);

            root.addView(pageVideoWrap);

            return new PageHolder(root, pagePhoto, pageVideoWrap, pageVideo, playBtnWrap, playIcon);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) pos = position;
            AlbumMediaEntry item = albumItems.get(pos);
            File f = new File(item.path);
            boolean isVideo = "video".equalsIgnoreCase(item.type) || item.path.toLowerCase(Locale.ROOT).endsWith(".mp4");

            GestureDetector tapDetector = new GestureDetector(holder.itemView.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            toggleControls();
                            return true;
                        }
                    });

            holder.pagePhoto.setOnClickListener(v -> toggleControls());
            holder.pagePhoto.setOnTouchListener((v, event) -> {
                tapDetector.onTouchEvent(event);
                return false;
            });

            if (isVideo) {
                holder.pagePhoto.setVisibility(View.GONE);
                holder.pageVideoWrap.setVisibility(View.VISIBLE);
                if (f.exists()) {
                    holder.pageVideo.setVideoPath(f.getAbsolutePath());
                    final int boundPosition = pos;
                    holder.pageVideo.setOnPreparedListener(mp -> {
                        int dur = mp.getDuration();
                        fitVideoViewToAspectRatio(holder.pageVideo, holder.pageVideoWrap, mp.getVideoWidth(), mp.getVideoHeight());
                        if (boundPosition == currentMediaIndex) {
                            activeMediaPlayer = mp;
                            activeAlbumVideoView = holder.pageVideo;
                            activeAlbumPlayBtnWrap = holder.playBtnWrap;
                            activeAlbumPlayIcon = holder.playIcon;
                            applyCurrentVideoSpeed();
                            if (videoSeekBar != null) {
                                videoSeekBar.setMax(Math.max(1, dur));
                                videoSeekBar.setProgress(0);
                            }
                            if (tvVideoTotal != null) tvVideoTotal.setText(formatTime(dur));
                            if (tvVideoElapsed != null) tvVideoElapsed.setText("00:00");
                            holder.pageVideo.start();
                            holder.playBtnWrap.setVisibility(View.GONE);
                            holder.playIcon.setImageResource(R.drawable.ic_pause);
                            startAlbumVideoTracker(holder.pageVideo);
                        } else {
                            holder.pageVideo.seekTo(100);
                            holder.playBtnWrap.setVisibility(View.VISIBLE);
                            holder.playIcon.setImageResource(R.drawable.ic_play_arrow);
                        }
                    });
                    holder.pageVideo.setOnCompletionListener(mp -> {
                        holder.playBtnWrap.setVisibility(View.VISIBLE);
                        holder.playIcon.setImageResource(R.drawable.ic_play_arrow);
                        if (videoSeekBar != null) videoSeekBar.setProgress(videoSeekBar.getMax());
                        stopVideoTracker();
                    });
                    View.OnClickListener togglePlay = v -> {
                        if (holder.pageVideo.isPlaying()) {
                            holder.pageVideo.pause();
                            holder.playBtnWrap.setVisibility(View.VISIBLE);
                            holder.playIcon.setImageResource(R.drawable.ic_play_arrow);
                            stopVideoTracker();
                        } else {
                            activeAlbumVideoView = holder.pageVideo;
                            activeAlbumPlayBtnWrap = holder.playBtnWrap;
                            activeAlbumPlayIcon = holder.playIcon;
                            holder.pageVideo.start();
                            holder.playBtnWrap.setVisibility(View.GONE);
                            holder.playIcon.setImageResource(R.drawable.ic_pause);
                            startAlbumVideoTracker(holder.pageVideo);
                        }
                    };
                    holder.playBtnWrap.setOnClickListener(togglePlay);
                    holder.pageVideoWrap.setOnClickListener(v -> toggleControls());
                }
            } else {
                holder.pageVideoWrap.setVisibility(View.GONE);
                holder.pagePhoto.setVisibility(View.VISIBLE);
                if (f.exists()) {
                    if ("gif".equals(item.type) || VeilStickerGifEngine.isGifFile(f)) {
                        android.graphics.drawable.Drawable gif = VeilStickerGifEngine.loadAnimatedGifFromFile(f);
                        if (gif != null) {
                            holder.pagePhoto.setImageDrawable(gif);
                            return;
                        }
                    }
                    Bitmap bmp = decodeCompleteBitmapWithExif(f, 2048);
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

            PageHolder(@NonNull View itemView,
                       ZoomableImageView pagePhoto,
                       FrameLayout pageVideoWrap,
                       VideoView pageVideo,
                       FrameLayout playBtnWrap,
                       ImageView playIcon) {
                super(itemView);
                this.pagePhoto = pagePhoto;
                this.pageVideoWrap = pageVideoWrap;
                this.pageVideo = pageVideo;
                this.playBtnWrap = playBtnWrap;
                this.playIcon = playIcon;
            }
        }
    }

    private void startAlbumVideoTracker(final VideoView targetVideoView) {
        stopVideoTracker();
        videoProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (targetVideoView != null && targetVideoView.isPlaying()) {
                    int pos = targetVideoView.getCurrentPosition();
                    int dur = targetVideoView.getDuration();
                    if (videoSeekBar != null) {
                        if (dur > 0 && videoSeekBar.getMax() != dur) {
                            videoSeekBar.setMax(dur);
                        }
                        videoSeekBar.setProgress(pos);
                    }
                    if (tvVideoElapsed != null) {
                        tvVideoElapsed.setText(formatTime(pos));
                    }
                    if (tvVideoTotal != null && dur > 0) {
                        tvVideoTotal.setText(formatTime(dur));
                    }
                    videoHandler.postDelayed(this, 100);
                }
            }
        };
        videoHandler.post(videoProgressRunnable);
    }

    private void bindPhoto(File file) {
        photoViewer.setVisibility(View.VISIBLE);
        videoContainer.setVisibility(View.GONE);
        if (videoControlsBar != null) videoControlsBar.setVisibility(View.GONE);

        try {
            if ("gif".equals(mediaType) || VeilStickerGifEngine.isGifFile(file)) {
                android.graphics.drawable.Drawable gifDrawable = VeilStickerGifEngine.loadAnimatedGifFromFile(file);
                if (gifDrawable != null) {
                    photoViewer.setImageDrawable(gifDrawable);
                    return;
                }
            }
            Bitmap bitmap = decodeCompleteBitmapWithExif(file, 2048);
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
        if (videoControlsBar != null) videoControlsBar.setVisibility(View.VISIBLE);
        viewerProgressBar.setVisibility(View.VISIBLE);

        videoViewer.setVideoPath(file.getAbsolutePath());
        videoViewer.setOnPreparedListener(mp -> {
            activeMediaPlayer = mp;
            fitVideoViewToAspectRatio(videoViewer, videoContainer, mp.getVideoWidth(), mp.getVideoHeight());
            applyCurrentVideoSpeed();
            viewerProgressBar.setVisibility(View.GONE);
            int duration = mp.getDuration();
            videoSeekBar.setMax(Math.max(1, duration));
            tvVideoTotal.setText(formatTime(duration));
            tvVideoElapsed.setText("00:00");

            videoViewer.start();
            updatePlayPauseIcons(true);
            videoCenterPlayBtn.setVisibility(View.GONE);
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
        if (btnBottomPlayPause != null) {
            btnBottomPlayPause.setOnClickListener(playPauseClick);
        }
        videoContainer.setOnClickListener(v -> toggleControls());
    }

    private void updatePlayPauseIcons(boolean isPlaying) {
        int icon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow;
        if (ivCenterPlayIcon != null) ivCenterPlayIcon.setImageResource(icon);
        if (btnBottomPlayPause != null) btnBottomPlayPause.setImageResource(icon);
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
        int totalSec = Math.max(0, ms / 1000);
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec);
    }

    private void shareFile() {
        if (filePath == null) return;
        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("video".equalsIgnoreCase(mediaType) ? "video/*" : "image/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Media"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAllAlbumItemsToGallery() {
        int savedCount = 0;
        for (AlbumMediaEntry entry : albumItems) {
            if (saveSingleFileToGallery(entry.path, entry.type, false)) {
                savedCount++;
            }
        }
        Toast.makeText(this, "Saved " + savedCount + " items to Gallery", Toast.LENGTH_SHORT).show();
    }

    private void saveToGallery() {
        saveSingleFileToGallery(filePath, mediaType, true);
    }

    private boolean saveSingleFileToGallery(String targetPath, String targetType, boolean showToast) {
        if (targetPath == null) return false;
        File srcFile = new File(targetPath);
        if (!srcFile.exists()) return false;

        try {
            boolean isVid = "video".equalsIgnoreCase(targetType) || targetPath.toLowerCase(Locale.ROOT).endsWith(".mp4");
            String mime = isVid ? "video/mp4" : "image/jpeg";
            String dir = isVid ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            String fileName = "VEIL_" + System.currentTimeMillis() + (isVid ? ".mp4" : ".jpg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/VEIL");

                Uri collection = isVid
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
                    if (showToast) {
                        Toast.makeText(this, "Saved to " + dir + "/VEIL", Toast.LENGTH_SHORT).show();
                    }
                    return true;
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
            if (showToast) {
                Toast.makeText(this, "Saved to " + destFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (Exception e) {
            if (showToast) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseActiveVideoPlayback();
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
