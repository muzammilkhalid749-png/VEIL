package com.m3.veil.ui.chat;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.m3.veil.R;
import com.m3.veil.utils.UiAnimator;
import com.yalantis.ucrop.UCrop;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import com.google.android.material.slider.RangeSlider;
import com.m3.veil.utils.VideoTrimUtil;
import java.util.List;

import ja.burhanrashid52.photoeditor.PhotoEditor;
import ja.burhanrashid52.photoeditor.PhotoEditorView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MediaEditActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_URI = "extra_media_uri";
    public static final String EXTRA_MEDIA_TYPE = "extra_media_type";
    
    public static final String RESULT_EDITED_FILE_PATH = "result_edited_file_path";
    public static final String RESULT_MEDIA_TYPE = "result_media_type";
    public static final String RESULT_CAPTION = "result_caption";
    public static final String RESULT_IS_VIEW_ONCE = "result_is_view_once";

    private PhotoEditorView photoEditorView;
    private PhotoEditor photoEditor;
    private VideoView videoView;
    private EditText captionEditText;
    private boolean isViewOnce = false;

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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_media_edit);

        View mainView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mediaUri = getIntent().getParcelableExtra(EXTRA_MEDIA_URI);
        mediaType = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);

        if (mediaUri == null || mediaType == null) {
            Toast.makeText(this, "Invalid media", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        photoEditorView = findViewById(R.id.photoEditorView);
        videoView = findViewById(R.id.videoView);
        captionEditText = findViewById(R.id.captionEditText);

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

        if (mediaType.startsWith("image")) {
            setupImageEditor();
        } else if (mediaType.startsWith("video")) {
            setupVideoEditor();
        }

        View btnViewOnce = findViewById(R.id.btnViewOnce);
        TextView tvViewOnceBadge = findViewById(R.id.tvViewOnceBadge);
        if (btnViewOnce != null) {
            UiAnimator.attachSpringPress(btnViewOnce);
            btnViewOnce.setOnClickListener(v -> {
                isViewOnce = !isViewOnce;
                if (isViewOnce) {
                    if (tvViewOnceBadge != null) {
                        tvViewOnceBadge.setBackgroundResource(R.drawable.bg_view_once_ring_active);
                        tvViewOnceBadge.setTextColor(android.graphics.Color.WHITE);
                    }
                    String typeName = mediaType != null && mediaType.startsWith("video") ? "Video" : "Photo";
                    Toast.makeText(this, "⚡ " + typeName + " set to view once", Toast.LENGTH_SHORT).show();
                } else {
                    if (tvViewOnceBadge != null) {
                        tvViewOnceBadge.setBackgroundResource(R.drawable.bg_view_once_ring);
                        tvViewOnceBadge.setTextColor(android.graphics.Color.parseColor("#00A884"));
                    }
                    String typeName = mediaType != null && mediaType.startsWith("video") ? "Video" : "Photo";
                    Toast.makeText(this, typeName + " set to standard", Toast.LENGTH_SHORT).show();
                }
            });
        }

        btnSendView.setOnClickListener(v -> saveAndSend());
        
        // Toolbar buttons
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
            TextEditorDialogFragment dialog = TextEditorDialogFragment.show(this, "", android.graphics.Color.WHITE);
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

    private void setupImageEditor() {
        photoEditorView.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.GONE);
        photoEditorView.getSource().setImageURI(mediaUri);

        photoEditor = new PhotoEditor.Builder(this, photoEditorView)
                .setPinchTextScalable(true)
                .build();
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
        
        // Hide image-only editing tools
        findViewById(R.id.btnCrop).setVisibility(View.GONE);
        findViewById(R.id.btnEmoji).setVisibility(View.GONE);
        findViewById(R.id.btnText).setVisibility(View.GONE);
        findViewById(R.id.btnDraw).setVisibility(View.GONE);
        findViewById(R.id.btnUndo).setVisibility(View.GONE);

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
            trimStartMs = 0;
            trimEndMs = totalDurationMs;

            if (videoRangeSlider != null && totalDurationMs > 0) {
                videoRangeSlider.setValueFrom(0f);
                videoRangeSlider.setValueTo((float) totalDurationMs);
                videoRangeSlider.setValues(0f, (float) totalDurationMs);

                if (tvTrimStart != null) tvTrimStart.setText("00:00");
                if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(totalDurationMs));
                if (tvTrimDuration != null) tvTrimDuration.setText("Length: " + formatTime(totalDurationMs));

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

    private void saveAndSend() {
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
        Intent data = new Intent();
        data.putExtra(RESULT_EDITED_FILE_PATH, filePath);
        data.putExtra(RESULT_MEDIA_TYPE, type);
        data.putExtra(RESULT_IS_VIEW_ONCE, isViewOnce);
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
                photoEditor.clearAllViews();
                photoEditorView.getSource().setImageURI(mediaUri);
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
    private int currentSelectedColor = android.graphics.Color.WHITE;
    
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
        
        btnDone.setVisibility(newState != UIState.BASE ? View.VISIBLE : View.GONE);
        colorPickerView.setVisibility(newState != UIState.BASE ? View.VISIBLE : View.GONE);
        
        drawingOptionsBar.setVisibility(newState == UIState.DRAWING ? View.VISIBLE : View.GONE);
        textOptionsBar.setVisibility(newState == UIState.TEXT ? View.VISIBLE : View.GONE);
        
        if (photoEditor != null) {
            photoEditor.setBrushDrawingMode(newState == UIState.DRAWING);
        }
    }
}
