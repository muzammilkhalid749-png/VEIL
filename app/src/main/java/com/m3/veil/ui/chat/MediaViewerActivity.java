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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        configureDarkMediaSystemBars();

        initViews();
        handleWindowInsets();
        extractExtras();
        setupActions();
        bindMedia();

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
                View.OnClickListener quickReactionListener = v -> {
                    if (v instanceof TextView) {
                        String emoji = ((TextView) v).getText().toString();
                        dispatchReactionForCurrentMessage(emoji);
                    }
                };
                if (reactionHeart != null) reactionHeart.setOnClickListener(quickReactionListener);
                if (reactionPray != null) reactionPray.setOnClickListener(quickReactionListener);
                if (reactionLaugh != null) {
                    reactionLaugh.setOnClickListener(v -> showFullEmojiReactionPicker());
                }

                if (etMediaReply != null) {
                    etMediaReply.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                            boolean hasText = s != null && s.toString().trim().length() > 0;
                            if (btnSendMediaReply != null) {
                                btnSendMediaReply.setVisibility(hasText ? View.VISIBLE : View.GONE);
                            }
                            int reactVis = hasText ? View.GONE : View.VISIBLE;
                            if (reactionHeart != null) reactionHeart.setVisibility(reactVis);
                            if (reactionPray != null) reactionPray.setVisibility(reactVis);
                            if (reactionLaugh != null) reactionLaugh.setVisibility(reactVis);
                        }
                        @Override public void afterTextChanged(android.text.Editable s) {}
                    });
                    etMediaReply.setOnEditorActionListener((v, actionId, event) -> {
                        sendMediaReplyMessage();
                        return true;
                    });
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

    private void dispatchReactionForCurrentMessage(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        if (chatId != null && !chatId.isEmpty() && messageId != null && !messageId.isEmpty()) {
            VeilMessagesController.getInstance(this).sendReaction(chatId, messageId, emoji);
        }
        Toast.makeText(this, "Reacted with " + emoji, Toast.LENGTH_SHORT).show();
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
                    dispatchReactionForCurrentMessage(emoji);
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
        if (filePath == null || filePath.isEmpty()) return;
        File f = new File(filePath);
        if (!f.exists()) return;
        pauseActiveVideoPlayback();

        Intent editIntent = new Intent(this, MediaEditActivity.class);
        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, Uri.fromFile(f).toString());
        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE,
                "video".equalsIgnoreCase(mediaType) ? "video" : "image");
        startActivityForResult(editIntent, REQUEST_EDIT_FROM_VIEWER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT_FROM_VIEWER && resultCode == RESULT_OK && data != null) {
            String editedPath = data.getStringExtra(MediaEditActivity.RESULT_EDITED_FILE_PATH);
            String editedType = data.getStringExtra(MediaEditActivity.RESULT_MEDIA_TYPE);
            String editedCaption = data.getStringExtra(MediaEditActivity.RESULT_CAPTION);
            boolean editedViewOnce = data.getBooleanExtra(MediaEditActivity.RESULT_IS_VIEW_ONCE, false);
            if (editedPath != null && !editedPath.isEmpty()) {
                File editedFile = new File(editedPath);
                if (editedFile.exists() && chatId != null && !chatId.isEmpty()) {
                    VeilMessagesController.getInstance(this).sendMediaMessage(
                            chatId, peerId, editedType != null ? editedType : "image",
                            editedFile, editedCaption, messageId, editedViewOnce);
                    Toast.makeText(this, "Edited media sent to chat", Toast.LENGTH_SHORT).show();
                    finish();
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

    private void createStickerFromCurrentMedia() {
        if (filePath == null || filePath.isEmpty()) return;
        File f = new File(filePath);
        if (!f.exists()) return;

        File sourceImageFile = f;
        if ("video".equalsIgnoreCase(mediaType) || f.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(f.getAbsolutePath());
                Bitmap frame = mmr.getFrameAtTime();
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
                new AlertDialog.Builder(this)
                        .setTitle("Sticker Created")
                        .setMessage("Added to your custom stickers. Send this sticker to the chat now?")
                        .setPositiveButton("Send Sticker", (d, w) -> {
                            VeilMessagesController.getInstance(this).sendMediaMessage(
                                    chatId, peerId, "sticker", finalStickerFile, "[sticker]", null, false);
                            Toast.makeText(this, "Sticker sent!", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Keep in Stickers", null)
                        .show();
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

    private void showForwardDialog() {
        List<Chat> dialogs = EncryptedChatDatabase.getInstance(this).getAllChats(false);
        if (dialogs == null || dialogs.isEmpty()) {
            shareFile();
            return;
        }
        List<String> names = new ArrayList<>();
        final List<Chat> validChats = new ArrayList<>();
        for (Chat c : dialogs) {
            if (c == null || c.getId() == null) continue;
            validChats.add(c);
            names.add(c.getName() != null && !c.getName().isEmpty() ? c.getName() : c.getId());
        }
        if (validChats.isEmpty()) {
            shareFile();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Forward to...")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    Chat target = validChats.get(which);
                    if (isVerticalScrollMode && albumItems.size() > 1) {
                        for (AlbumMediaEntry entry : albumItems) {
                            File ef = new File(entry.path);
                            if (ef.exists()) {
                                VeilMessagesController.getInstance(this).sendMediaMessage(
                                        target.getId(), target.getId(), entry.type, ef, entry.caption, null, false);
                            }
                        }
                        Toast.makeText(this, "Album forwarded to " + names.get(which), Toast.LENGTH_SHORT).show();
                    } else if (filePath != null) {
                        File f = new File(filePath);
                        if (f.exists()) {
                            VeilMessagesController.getInstance(this).sendMediaMessage(
                                    target.getId(), target.getId(),
                                    "video".equalsIgnoreCase(mediaType) ? "video" : "image",
                                    f, caption, null, false);
                            Toast.makeText(this, "Forwarded to " + names.get(which), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void repostMediaToStatus() {
        Toast.makeText(this, "Reposted to your Status", Toast.LENGTH_SHORT).show();
    }

    private void toggleControls() {
        if (isVerticalScrollMode) return;
        isControlsVisible = !isControlsVisible;
        int vis = isControlsVisible ? View.VISIBLE : View.GONE;
        if (topBar != null) topBar.setVisibility(vis);
        if (bottomReplyBar != null && !isViewOnce) {
            bottomReplyBar.setVisibility(vis);
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
        } else {
            mediaVerticalRecyclerView.setVisibility(View.GONE);
            mediaViewPager.setVisibility(View.VISIBLE);
            mediaViewPager.setCurrentItem(currentMediaIndex, false);
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

    /**
     * STAGE 2 ADAPTER: WhatsApp Vertical Scrolling Album Feed (Screenshot3.jpg & Screenshot4.jpg).
     * Displays a continuous vertical feed of full-width photo/video cards separated by 12dp dark spacing.
     * Clicking ANY card transitions smoothly into Stage 3 (Full-Screen Photo/Video View Mode) at that index.
     */
    private class VerticalAlbumFeedAdapter extends RecyclerView.Adapter<VerticalAlbumFeedAdapter.FeedCardHolder> {

        @NonNull
        @Override
        public FeedCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float dp = getResources().getDisplayMetrics().density;
            FrameLayout cardRoot = new FrameLayout(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(320 * dp));
            lp.bottomMargin = Math.round(12 * dp);
            cardRoot.setLayoutParams(lp);
            cardRoot.setBackgroundColor(Color.parseColor("#111B21"));

            ImageView ivMedia = new ImageView(parent.getContext());
            ivMedia.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            ivMedia.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cardRoot.addView(ivMedia);

            // Bottom gradient scrim for duration & timestamp legibility
            View bottomScrim = new View(parent.getContext());
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(56 * dp), Gravity.BOTTOM);
            bottomScrim.setLayoutParams(scrimLp);
            GradientDrawable scrimGrad = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, Color.TRANSPARENT});
            bottomScrim.setBackground(scrimGrad);
            cardRoot.addView(bottomScrim);

            // Centered circular play button for video items
            FrameLayout playCircle = new FrameLayout(parent.getContext());
            int pSz = Math.round(58 * dp);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(pSz, pSz, Gravity.CENTER);
            playCircle.setLayoutParams(pLp);
            GradientDrawable pBg = new GradientDrawable();
            pBg.setShape(GradientDrawable.OVAL);
            pBg.setColor(0x99000000);
            playCircle.setBackground(pBg);

            ImageView playIcon = new ImageView(parent.getContext());
            int icSz = Math.round(30 * dp);
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

            return new FeedCardHolder(cardRoot, ivMedia, playCircle, tvVideoDur, tvTimeTick);
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
                        try {
                            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                            mmr.setDataSource(f.getAbsolutePath());
                            bmp = mmr.getFrameAtTime();
                            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            if (durStr != null) {
                                long ms = Long.parseLong(durStr);
                                if (ms > 0) {
                                    feedDurationCache.put(item.path, formatTime((int) ms));
                                }
                            }
                            mmr.release();
                        } catch (Throwable ignored) {}
                    } else {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
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
                    int targetH = Math.round(screenW * ratio);
                    int minH = Math.round(220 * dp);
                    int maxH = Math.round(440 * dp);
                    targetH = Math.max(minH, Math.min(maxH, targetH));
                    ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                    if (lp != null && lp.height != targetH) {
                        lp.height = targetH;
                        holder.itemView.setLayoutParams(lp);
                    }
                }
            } else {
                holder.ivMedia.setImageDrawable(null);
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
            holder.itemView.setOnClickListener(v -> {
                enteredPagerFromAlbumFeed = true;
                isVerticalScrollMode = false;
                isControlsVisible = true;
                currentMediaIndex = clickedPos;
                applyAlbumScrollMode();
                selectAlbumIndex(clickedPos, false);
            });
        }

        @Override
        public int getItemCount() {
            return albumItems.size();
        }

        class FeedCardHolder extends RecyclerView.ViewHolder {
            final ImageView ivMedia;
            final FrameLayout playCircle;
            final TextView tvVideoDur;
            final TextView tvTimeTick;

            FeedCardHolder(@NonNull View itemView,
                           ImageView ivMedia,
                           FrameLayout playCircle,
                           TextView tvVideoDur,
                           TextView tvTimeTick) {
                super(itemView);
                this.ivMedia = ivMedia;
                this.playCircle = playCircle;
                this.tvVideoDur = tvVideoDur;
                this.tvTimeTick = tvTimeTick;
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
        if (videoControlsBar != null) videoControlsBar.setVisibility(View.VISIBLE);
        viewerProgressBar.setVisibility(View.VISIBLE);

        videoViewer.setVideoPath(file.getAbsolutePath());
        videoViewer.setOnPreparedListener(mp -> {
            activeMediaPlayer = mp;
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
