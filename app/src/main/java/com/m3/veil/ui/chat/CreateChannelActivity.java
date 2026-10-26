package com.m3.veil.ui.chat;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.m3.veil.model.Timestamp;
import com.m3.veil.bridge.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Dedicated Telegram-Grade Broadcast Channel Creation Wizard.
 * Supports UCrop 1:1 circular channel avatars, channel description/bio,
 * Public vs Private channel mode with live debounced @handle availability checking,
 * and Firestore public directory publication.
 */
public class CreateChannelActivity extends AppCompatActivity {

    private CircleImageView ivChannelAvatar;
    private TextInputEditText etChannelName, etChannelDesc, etChannelHandle;
    private RadioGroup radioGroupChannelType;
    private RadioButton rbPublic, rbPrivate;
    private LinearLayout publicLinkContainer, privateLinkContainer;
    private TextView tvHandleStatus, tvPrivateInviteLink;
    private MaterialButton btnCreateChannel;
    private ImageView btnDone;
    private ProgressBar progressBar;

    private Uri avatarUri = null;
    private Uri tempCameraUri = null;
    private String generatedChannelId;
    private String currentUserId;
    private String currentUserName = "Channel Admin";

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable checkHandleRunnable;
    private boolean isHandleAvailable = true;
    private boolean isCheckingHandle = false;

    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<Intent> cropLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_create_channel);
        com.m3.veil.utils.UiAnimator.enableEdgeToEdge(this, findViewById(android.R.id.content));

        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        generatedChannelId = "channel_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);

        initViews();
        setupLaunchers();
        setupListeners();
    }

    private void initViews() {
        ivChannelAvatar = findViewById(R.id.ivChannelAvatar);
        etChannelName = findViewById(R.id.etChannelName);
        etChannelDesc = findViewById(R.id.etChannelDesc);
        etChannelHandle = findViewById(R.id.etChannelHandle);
        radioGroupChannelType = findViewById(R.id.radioGroupChannelType);
        rbPublic = findViewById(R.id.rbPublic);
        rbPrivate = findViewById(R.id.rbPrivate);
        publicLinkContainer = findViewById(R.id.publicLinkContainer);
        privateLinkContainer = findViewById(R.id.privateLinkContainer);
        tvHandleStatus = findViewById(R.id.tvHandleStatus);
        tvPrivateInviteLink = findViewById(R.id.tvPrivateInviteLink);
        btnCreateChannel = findViewById(R.id.btnCreateChannel);
        btnDone = findViewById(R.id.btnDone);
        progressBar = findViewById(R.id.progressBar);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        tvPrivateInviteLink.setText("veil://channel/join?id=" + generatedChannelId);
    }

    private void setupLaunchers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) startCrop(uri);
        });

        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && tempCameraUri != null) startCrop(tempCameraUri);
        });

        cropLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri resultUri = UCrop.getOutput(result.getData());
                if (resultUri != null) {
                    avatarUri = resultUri;
                    ivChannelAvatar.setImageURI(resultUri);
                }
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.avatarContainer).setOnClickListener(v -> showAvatarSourceDialog());

        radioGroupChannelType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbPublic) {
                publicLinkContainer.setVisibility(View.VISIBLE);
                privateLinkContainer.setVisibility(View.GONE);
            } else {
                publicLinkContainer.setVisibility(View.GONE);
                privateLinkContainer.setVisibility(View.VISIBLE);
            }
        });

        etChannelHandle.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                debounceHandler.removeCallbacks(checkHandleRunnable);
                String handle = s != null ? s.toString().trim() : "";
                if (handle.isEmpty()) {
                    tvHandleStatus.setText("People can find and join your channel with this link.");
                    tvHandleStatus.setTextColor(ContextCompat.getColor(CreateChannelActivity.this, R.color.textSecondary));
                    isHandleAvailable = true;
                    return;
                }

                if (handle.length() < 4) {
                    tvHandleStatus.setText("Link must be at least 4 characters long.");
                    tvHandleStatus.setTextColor(ContextCompat.getColor(CreateChannelActivity.this, R.color.colorError));
                    isHandleAvailable = false;
                    return;
                }

                if (!handle.matches("^[a-zA-Z0-9_]+$")) {
                    tvHandleStatus.setText("Only letters (a-z), numbers (0-9) and underscores (_) are allowed.");
                    tvHandleStatus.setTextColor(ContextCompat.getColor(CreateChannelActivity.this, R.color.colorError));
                    isHandleAvailable = false;
                    return;
                }

                tvHandleStatus.setText("Checking availability...");
                tvHandleStatus.setTextColor(ContextCompat.getColor(CreateChannelActivity.this, R.color.textSecondary));
                isCheckingHandle = true;

                checkHandleRunnable = () -> checkHandleAvailability(handle);
                debounceHandler.postDelayed(checkHandleRunnable, 350);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnCreateChannel.setOnClickListener(v -> createChannel());
        btnDone.setOnClickListener(v -> createChannel());
    }

    private void checkHandleAvailability(String handle) {
        FirebaseFirestore.getInstance().collection("groups")
                .whereEqualTo("handle", handle.toLowerCase(Locale.ROOT))
                .limit(1)
                .get()
                .addOnSuccessListener(snapshot -> {
                    isCheckingHandle = false;
                    if (snapshot == null || snapshot.isEmpty()) {
                        isHandleAvailable = true;
                        tvHandleStatus.setText("✓ veil.me/" + handle + " is available");
                        tvHandleStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
                    } else {
                        isHandleAvailable = false;
                        tvHandleStatus.setText("✗ veil.me/" + handle + " is already taken");
                        tvHandleStatus.setTextColor(ContextCompat.getColor(this, R.color.colorError));
                    }
                })
                .addOnFailureListener(e -> {
                    isCheckingHandle = false;
                    isHandleAvailable = true;
                    tvHandleStatus.setText("Link is format-valid");
                    tvHandleStatus.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                });
    }

    private void showAvatarSourceDialog() {
        String[] options = avatarUri != null
                ? new String[]{"ðŸ“· Take Photo with Camera", "ðŸ–¼ï¸ Choose from Gallery", "ðŸ—‘ï¸ Remove Photo"}
                : new String[]{"ðŸ“· Take Photo with Camera", "ðŸ–¼ï¸ Choose from Gallery"};

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Channel Photo")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        File tempFile = new File(getCacheDir(), "camera_channel_" + System.currentTimeMillis() + ".jpg");
                        tempCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", tempFile);
                        cameraLauncher.launch(tempCameraUri);
                    } else if (which == 1) {
                        galleryLauncher.launch("image/*");
                    } else if (which == 2) {
                        avatarUri = null;
                        ivChannelAvatar.setImageResource(R.drawable.ic_channel);
                    }
                })
                .show();
    }

    private void startCrop(Uri sourceUri) {
        Uri destinationUri = Uri.fromFile(new File(getCacheDir(), "cropped_channel_" + System.currentTimeMillis() + ".jpg"));
        UCrop.Options options = new UCrop.Options();
        options.setCircleDimmedLayer(true);
        options.setShowCropGrid(false);
        options.setCompressionQuality(90);
        options.setToolbarColor(ContextCompat.getColor(this, R.color.colorSurface));
        options.setStatusBarColor(ContextCompat.getColor(this, R.color.colorSurface));
        options.setActiveControlsWidgetColor(ContextCompat.getColor(this, R.color.colorPrimary));

        Intent uCropIntent = UCrop.of(sourceUri, destinationUri)
                .withAspectRatio(1, 1)
                .withMaxResultSize(600, 600)
                .withOptions(options)
                .getIntent(this);
        cropLauncher.launch(uCropIntent);
    }

    private void createChannel() {
        String name = etChannelName.getText() != null ? etChannelName.getText().toString().trim() : "";
        if (TextUtils.isEmpty(name)) {
            etChannelName.setError("Channel name is required");
            etChannelName.requestFocus();
            return;
        }

        boolean isPublic = rbPublic.isChecked();
        String handle = etChannelHandle.getText() != null ? etChannelHandle.getText().toString().trim() : "";

        if (isPublic) {
            if (TextUtils.isEmpty(handle)) {
                etChannelHandle.setError("Public channel requires a link");
                etChannelHandle.requestFocus();
                return;
            }
            if (!isHandleAvailable || isCheckingHandle) {
                Toast.makeText(this, "Please choose an available public link", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        progressBar.setVisibility(View.VISIBLE);
        btnCreateChannel.setEnabled(false);
        btnDone.setEnabled(false);

        String description = etChannelDesc.getText() != null ? etChannelDesc.getText().toString().trim() : "";
        List<String> participants = new ArrayList<>();
        participants.add(currentUserId);

        // 1. Create Local Chat object
        Chat channelChat = new Chat();
        channelChat.setId(generatedChannelId);
        channelChat.setName(name);
        channelChat.setLastMessage("Broadcast Channel created by " + currentUserName);
        channelChat.setLastMessageTime(Timestamp.now());
        channelChat.setAvatar(avatarUri != null ? avatarUri.toString() : null);
        channelChat.setUnreadCount(0);
        channelChat.setGroup(true);
        channelChat.setType("channel");
        channelChat.setAdminId(currentUserId);
        channelChat.setParticipants(participants);
        channelChat.setDescription(description);
        channelChat.setHandle(isPublic ? handle : "");
        channelChat.setPublic(isPublic);

        // Save into local SQLCipher database
        EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(channelChat);

        // 2. Publish metadata to Firestore collection "groups"
        Map<String, Object> channelData = new HashMap<>();
        channelData.put("id", generatedChannelId);
        channelData.put("name", name);
        channelData.put("type", "channel");
        channelData.put("adminId", currentUserId);
        channelData.put("description", description);
        channelData.put("isPublic", isPublic);
        channelData.put("handle", isPublic ? handle.toLowerCase(Locale.ROOT) : "");
        channelData.put("inviteLink", "veil://channel/join?id=" + generatedChannelId);
        channelData.put("participants", participants);
        channelData.put("subscriberCount", 1);
        channelData.put("avatar", avatarUri != null ? avatarUri.toString() : "");
        channelData.put("createdAt", System.currentTimeMillis());

        FirebaseFirestore.getInstance().collection("groups").document(generatedChannelId)
                .set(channelData)
                .addOnSuccessListener(aVoid -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Broadcast Channel created!", Toast.LENGTH_SHORT).show();

                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.putExtra("chatId", generatedChannelId);
                    intent.putExtra("recipientName", name);
                    intent.putExtra("isGroup", true);
                    intent.putExtra("isChannel", true);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    progressBar.setVisibility(View.GONE);
                    // Still open chat locally even if Firestore is slow/offline
                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.putExtra("chatId", generatedChannelId);
                    intent.putExtra("recipientName", name);
                    intent.putExtra("isGroup", true);
                    intent.putExtra("isChannel", true);
                    startActivity(intent);
                    finish();
                });
    }
}
