package com.m3.veil.ui.chat;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.m3.veil.R;
import com.m3.veil.ui.views.ZoomableImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * WhatsApp & Telegram style in-app full-screen media viewer.
 * Provides high-res photo lightbox with pinch-to-zoom and pan,
 * and immersive video player with custom playback controls, scrub seeking,
 * caption overlays, one-click sharing, and offline gallery saving.
 */
public class MediaViewerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "extra_file_path";
    public static final String EXTRA_MEDIA_TYPE = "extra_media_type"; // "image" or "video"
    public static final String EXTRA_SENDER_NAME = "extra_sender_name";
    public static final String EXTRA_TIMESTAMP = "extra_timestamp";
    public static final String EXTRA_CAPTION = "extra_caption";
    public static final String EXTRA_IS_VIEW_ONCE = "extra_is_view_once";
    public static final String EXTRA_MESSAGE_ID = "extra_message_id";
    public static final String EXTRA_CHAT_ID = "extra_chat_id";
    public static final String EXTRA_PEER_ID = "extra_peer_id";

    private boolean isViewOnce = false;
    private String messageId;
    private String chatId;
    private String peerId;
    private boolean hasDestroyedViewOnce = false;

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

    private Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable videoProgressRunnable;
    private boolean isControlsVisible = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        // Hide navigation and status bar for full immersive feeling
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        initViews();
        extractExtras();
        setupActions();
        bindMedia();
    }

    private void initViews() {
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

        if (caption != null && !caption.trim().isEmpty()) {
            tvCaption.setText(caption);
            bottomCaptionBar.setVisibility(View.VISIBLE);
        } else {
            bottomCaptionBar.setVisibility(View.GONE);
        }
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

    private final java.util.List<File> siblingMediaFiles = new java.util.ArrayList<>();
    private int currentMediaIndex = -1;
    private MediaPlayer activeMediaPlayer;
    private float currentVideoSpeed = 1.0f;

    private void loadSiblingMediaList() {
        siblingMediaFiles.clear();
        if (isViewOnce || filePath == null) return;
        File current = new File(filePath);
        File parentDir = current.getParentFile();
        if (parentDir == null || !parentDir.exists()) return;
        File[] files = parentDir.listFiles((dir, name) ->
                (name.startsWith("dec_") || name.startsWith("edited_") || name.startsWith("pick_"))
                        && (name.endsWith(".jpg") || name.endsWith(".mp4")));
        if (files != null && files.length > 1) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length; i++) {
                siblingMediaFiles.add(files[i]);
                if (files[i].getAbsolutePath().equals(current.getAbsolutePath())) {
                    currentMediaIndex = i;
                }
            }
        }
    }

    private void navigateMedia(int delta) {
        if (siblingMediaFiles.size() <= 1 || currentMediaIndex == -1) return;
        int nextIdx = (currentMediaIndex + delta + siblingMediaFiles.size()) % siblingMediaFiles.size();
        currentMediaIndex = nextIdx;
        File nextFile = siblingMediaFiles.get(nextIdx);
        filePath = nextFile.getAbsolutePath();
        mediaType = nextFile.getName().endsWith(".mp4") ? "video" : "image";
        tvSubtitle.setVisibility(View.VISIBLE);
        tvSubtitle.setText("Media " + (currentMediaIndex + 1) + " of " + siblingMediaFiles.size());
        bindMedia();
    }

    private void setupActions() {
        btnBack.setOnClickListener(v -> finish());

        btnShare.setOnClickListener(v -> shareFile());
        btnSave.setOnClickListener(v -> saveToGallery());

        loadSiblingMediaList();

        // Inject Prev / Next Media Carousel & Video Speed / PiP controls into topBar
        if (topBar != null && !isViewOnce) {
            float dp = getResources().getDisplayMetrics().density;
            if (siblingMediaFiles.size() > 1) {
                TextView btnPrev = new TextView(this);
                btnPrev.setText("◀");
                btnPrev.setTextColor(android.graphics.Color.WHITE);
                btnPrev.setTextSize(15f);
                btnPrev.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
                btnPrev.setOnClickListener(v -> navigateMedia(-1));

                TextView btnNext = new TextView(this);
                btnNext.setText("▶");
                btnNext.setTextColor(android.graphics.Color.WHITE);
                btnNext.setTextSize(15f);
                btnNext.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
                btnNext.setOnClickListener(v -> navigateMedia(1));

                topBar.addView(btnPrev, Math.max(1, topBar.getChildCount() - 2));
                topBar.addView(btnNext, Math.max(2, topBar.getChildCount() - 2));
            }

            TextView btnSpeed = new TextView(this);
            btnSpeed.setText("1x");
            btnSpeed.setTextColor(android.graphics.Color.WHITE);
            btnSpeed.setTextSize(12.5f);
            btnSpeed.setTypeface(null, android.graphics.Typeface.BOLD);
            btnSpeed.setBackgroundResource(R.drawable.bg_filter_chip);
            btnSpeed.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
            btnSpeed.setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
            btnSpeed.setOnClickListener(v -> {
                if (currentVideoSpeed == 1.0f) currentVideoSpeed = 1.5f;
                else if (currentVideoSpeed == 1.5f) currentVideoSpeed = 2.0f;
                else currentVideoSpeed = 1.0f;
                btnSpeed.setText(String.format(Locale.US, "%.1fx", currentVideoSpeed).replace(".0", ""));
                if (activeMediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        activeMediaPlayer.setPlaybackParams(activeMediaPlayer.getPlaybackParams().setSpeed(currentVideoSpeed));
                    } catch (Exception ignored) {}
                }
            });
            topBar.addView(btnSpeed, Math.max(1, topBar.getChildCount() - 2));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                TextView btnPip = new TextView(this);
                btnPip.setText("📺 PiP");
                btnPip.setTextColor(android.graphics.Color.WHITE);
                btnPip.setTextSize(12f);
                btnPip.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
                btnPip.setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
                btnPip.setOnClickListener(v -> {
                    try {
                        enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
                    } catch (Exception ignored) {}
                });
                topBar.addView(btnPip, Math.max(1, topBar.getChildCount() - 2));
            }
        }

        // Tap root to toggle overlay controls
        findViewById(R.id.mediaViewerRoot).setOnClickListener(v -> toggleControls());
        if (photoViewer != null) {
            photoViewer.setOnClickListener(v -> toggleControls());
        }
    }

    private void toggleControls() {
        isControlsVisible = !isControlsVisible;
        int vis = isControlsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        if (caption != null && !caption.trim().isEmpty()) {
            bottomCaptionBar.setVisibility(vis);
        }
        if ("video".equals(mediaType)) {
            videoControlsBar.setVisibility(vis);
            videoCenterPlayBtn.setVisibility(isControlsVisible && !videoViewer.isPlaying() ? View.VISIBLE : View.GONE);
        }
    }

    private void bindMedia() {
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

    private void bindPhoto(File file) {
        photoViewer.setVisibility(View.VISIBLE);
        videoContainer.setVisibility(View.GONE);

        try {
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

            // Auto-play initially
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

            // Fallback for pre-Q
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

            // Broadcast media scan
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
        stopVideoTracker();
    }

    @Override
    protected void onDestroy() {
        handleViewOnceDestruction();
        stopVideoTracker();
        if (videoViewer != null) {
            videoViewer.stopPlayback();
        }
        super.onDestroy();
    }
}
