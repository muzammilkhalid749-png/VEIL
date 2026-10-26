package com.m3.veil.ui.chat;

import com.yalantis.ucrop.UCrop;
import android.graphics.drawable.GradientDrawable;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import com.m3.veil.ui.chat.sheets.EventsCalendarBottomSheet;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.gowtham.library.utils.TrimType;
import com.gowtham.library.utils.TrimVideo;
import com.m3.veil.R;
import com.m3.veil.model.Message;
import com.m3.veil.model.User;
import com.m3.veil.ui.chat.adapters.MessagesAdapter;
import com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet;
import com.m3.veil.ui.views.WaveformView;
import com.m3.veil.utils.AudioRecorderHelper;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.utils.MediaEncryptionHelper;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.utils.VeilFirestoreUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChatActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGE = 100;
    private static final int REQ_MEDIA_EDIT = 101;
    private static final int REQ_PICK_VIDEO = 102;
    private static final int REQ_PICK_DOCUMENT = 103;
    private static final int REQ_PICK_CONTACT = 104;
    private static final int REQ_CAPTURE_CAMERA = 105;
    private static final int REQ_CAMERA_GALLERY = 106;
    private static final int REQ_PICK_WALLPAPER = 107;
    private static final int REQ_VIEW_ONCE_MEDIA = 109;

    private static final int PERM_REQ_AUDIO = 200;
    private static final int PERM_REQ_LOCATION = 201;
    private static final int PERM_REQ_CAMERA = 202;
    private static final int PERM_REQ_STORAGE = 203;

    private String chatId;
    private String recipientId;
    private String recipientName;
    private String recipientPublicKey;

    private RecyclerView messagesRecyclerView;
    private MessagesAdapter messagesAdapter;
    private List<Message> messageList;
    private EditText messageEditText;
    private FloatingActionButton sendButton;

    private View layoutScrollToBottom;
    private ImageButton btnScrollToBottom;
    private boolean isRecipientOnline = false;
    private String recipientLastSeenFormatted = "";
    private boolean isRecipientTypingState = false;
    private User cachedRecipientUser;
    private final Handler presenceHandler = new Handler(Looper.getMainLooper());
    private final Runnable presenceTicker = new Runnable() {
        @Override
        public void run() {
            if (cachedRecipientUser != null) {
                boolean online = cachedRecipientUser.isUserOnline();
                String formatted = cachedRecipientUser.getFormattedLastSeen();
                if (online != isRecipientOnline || !java.util.Objects.equals(formatted, recipientLastSeenFormatted)) {
                    isRecipientOnline = online;
                    recipientLastSeenFormatted = formatted;
                    updatePresenceAndToolbarStatus();
                }
            }
            presenceHandler.postDelayed(this, 10_000L);
        }
    };

    private FirebaseFirestore db;
    private String currentUserId;

    private Handler typingHandler = new Handler(Looper.getMainLooper());
    private boolean isTyping = false;
    private boolean isBlinkModeActive = false;
    private String activeVoiceFilter = "normal"; // "normal", "robot", "deep", "chipmunk"
    private String currentSubgroupId = null;
    private String currentSubgroupTitle = null;
    private com.m3.veil.model.Chat currentLocalChat;

    private Runnable stopTypingRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTyping && recipientId != null) {
                isTyping = false;
                com.m3.veil.manager.VeilMessagesController.getInstance(ChatActivity.this).sendTyping(chatId, recipientId, false);
            }
        }
    };

    private final com.m3.veil.database.EncryptedChatDatabase.DatabaseChangeListener localDbListener =
            new com.m3.veil.database.EncryptedChatDatabase.DatabaseChangeListener() {
                @Override
                public void onMessagesUpdated(String updatedChatId) {
                    if (chatId != null && (updatedChatId == null || chatId.equals(updatedChatId))) {
                        runOnUiThread(() -> reloadMessagesFromLocalVault());
                    }
                }

                @Override
                public void onChatsUpdated() {
                    runOnUiThread(() -> loadLocalChatSettings());
                }
            };

    private Message replyMessage = null;
    private View replyPreviewContainer;
    private TextView replyPreviewName;
    private TextView replyPreviewText;
    private ImageView closeReplyButton;
    private View layoutSmartReplies;
    private LinearLayout containerSmartReplies;

    private long currentVanishTimer = 0;
    private String currentVanishMode = "after_send";

    private Uri cameraCaptureUri;

    private final ActivityResultLauncher<Intent> videoTrimResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String path = TrimVideo.getTrimmedVideoPath(result.getData());
                    if (path != null) {
                        Uri trimmedUri = Uri.fromFile(new File(path));
                        Intent editIntent = new Intent(this, MediaEditActivity.class);
                        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, trimmedUri);
                        editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE, "video");
                        startActivityForResult(editIntent, REQ_MEDIA_EDIT);
                    }
                }
            }
    );

    private Handler vanishHandler;
    private Runnable vanishRunnable;
    private int highlightPosition = -1;

    private View inputContainer;
    private View recordingContainer;
    private View lockedRecordingContainer;
    private ImageView recordBlinkDot;
    private TextView recordTimerText;
    private TextView lockedRecordTimerText;
    private TextView slideToCancelText;
    private ImageView recordLockIcon;
    private ImageView deleteRecordingButton;
    private View pauseRecordingButton;
    private ImageView pauseRecordingIcon;
    private TextView pauseRecordingText;
    private View lockedSendButton;
    private WaveformView lockedWaveformView;

    private boolean isRecording = false;

    // Speed Match Roulette & Mutual Reveal
    private android.os.CountDownTimer speedChatTimer;
    private boolean userRequestedReveal = false;
    private boolean partnerRequestedReveal = false;
    private boolean isSpeedMatchRevealed = false;
    private boolean isRecordingLocked = false;
    private boolean isRecordingPaused = false;
    private long totalRecordingDuration = 0;
    private float startX, startY;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private long recordingStartTime = 0;
    private Runnable timerRunnable;
    private AudioRecorderHelper audioRecorderHelper;

    private ListenerRegistration messagesListener;
    private ListenerRegistration chatSettingsListener;
    private ListenerRegistration recipientUserListener;
    private ListenerRegistration groupDocListener;
    private ListenerRegistration groupMessagesListener;
    private ListenerRegistration directMessagesListener;
    private ListenerRegistration directChatsMirrorListener;
    private LinearLayout channelSubscriberBar;
    private boolean isChatBlocked = false;
    private boolean isChatPinUnlocked = false;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isP2PActive = false;
    private int newMessagesWhileScrolledUp = 0;

    private final com.m3.veil.manager.VeilMessagesController.MessagesObserver messagesControllerObserver =
            new com.m3.veil.manager.VeilMessagesController.MessagesObserver() {
                @Override
                public void onMessageReceived(String dialogId, Message message) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        if (message != null && "[roulette_control:reveal_request]".equals(message.getText())) {
                            handleInboundRouletteReveal();
                            return;
                        }
                        runOnUiThread(() -> reloadMessagesFromLocalVault());
                    }
                }

                @Override
                public void onMessageSent(String dialogId, Message message) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> reloadMessagesFromLocalVault());
                    }
                }

                @Override
                public void onMessageStatusChanged(String dialogId, String messageId, boolean delivered, boolean read) {
                    runOnUiThread(() -> {
                        for (int i = 0; i < messageList.size(); i++) {
                            Message m = messageList.get(i);
                            if (m.getId() != null && m.getId().equals(messageId)) {
                                m.setDelivered(delivered);
                                m.setRead(read);
                                m.setPending(false);
                                messagesAdapter.notifyItemChanged(i + 1);
                                break;
                            }
                        }
                    });
                }

                @Override
                public void onMessageEdited(String dialogId, String messageId, String newText) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> {
                            for (int i = 0; i < messageList.size(); i++) {
                                Message m = messageList.get(i);
                                if (m.getId() != null && m.getId().equals(messageId)) {
                                    m.setText(newText);
                                    m.setEdited(true);
                                    messagesAdapter.notifyItemChanged(i + 1);
                                    break;
                                }
                            }
                        });
                    }
                }

                @Override
                public void onMessageDeleted(String dialogId, String messageId) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> reloadMessagesFromLocalVault());
                    }
                }

                @Override
                public void onPeerTyping(String dialogId, String peerId, boolean isTyping) {
                    if (peerId != null && peerId.equals(recipientId)) {
                        runOnUiThread(() -> updateVanishUI(isTyping));
                    }
                }

                @Override
                public void onReactionUpdated(String dialogId, String messageId, String userId, String emoji) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> {
                            for (int i = 0; i < messageList.size(); i++) {
                                Message m = messageList.get(i);
                                if (m.getId() != null && m.getId().equals(messageId)) {
                                    java.util.Map<String, String> rx = m.getReactions();
                                    if (rx == null) rx = new java.util.HashMap<>();
                                    rx.put(userId, emoji);
                                    m.setReactions(rx);
                                    messagesAdapter.notifyItemChanged(i + 1);
                                    break;
                                }
                            }
                        });
                    }
                }

                @Override
                public void onPollUpdated(String dialogId, String messageId, String updatedPollJson) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> {
                            for (int i = 0; i < messageList.size(); i++) {
                                Message m = messageList.get(i);
                                if (m.getId() != null && m.getId().equals(messageId)) {
                                    m.setPollJson(updatedPollJson);
                                    messagesAdapter.notifyItemChanged(i + 1);
                                    break;
                                }
                            }
                        });
                    }
                }

                @Override
                public void onVanishDestroyed(String dialogId, String messageId) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> reloadMessagesFromLocalVault());
                    }
                }

                @Override
                public void onScreenshotDetected(String dialogId, String peerId) {
                    if (chatId != null && chatId.equals(dialogId)) {
                        runOnUiThread(() -> {
                            android.widget.Toast.makeText(ChatActivity.this, "📸 " + (peerId != null && peerId.equals(currentUserId) ? "You took a screenshot" : "Screenshot detected!"), android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            };

    private final com.m3.veil.webrtc.WebRtcManager.P2PEventListener p2pChatListener = new com.m3.veil.webrtc.WebRtcManager.P2PEventListener() {
        @Override
        public void onMessageReceived(String peerId, Message message) {
            boolean matchesThisChat = (message != null && chatId != null && chatId.equals(message.getChatId()))
                    || (!isGroupChatContext() && peerId != null && peerId.equals(recipientId));
            if (matchesThisChat) {
                if (message != null && "[roulette_control:reveal_request]".equals(message.getText())) {
                    handleInboundRouletteReveal();
                    return;
                }
                runOnUiThread(() -> {
                    android.content.SharedPreferences privacyPrefs = getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE);
                    boolean sendReadReceipt = privacyPrefs.getBoolean("read_receipts_enabled", true);
                    String targetReceiptPeer = (peerId != null && !peerId.isEmpty()) ? peerId : recipientId;
                    if (targetReceiptPeer != null && message != null) {
                        com.m3.veil.webrtc.WebRtcManager.getInstance(ChatActivity.this)
                                .sendReceipt(targetReceiptPeer, message.getId(), true, sendReadReceipt);
                    }
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this)
                            .markAllAsRead(chatId, currentUserId);
                    reloadMessagesFromLocalVault();
                });
            }
        }

        @Override
        public void onMessageStatusChanged(String messageId, boolean delivered, boolean read) {
            runOnUiThread(() -> {
                for (int i = 0; i < messageList.size(); i++) {
                    Message m = messageList.get(i);
                    if (m.getId() != null && m.getId().equals(messageId)) {
                        m.setDelivered(delivered);
                        m.setRead(read);
                        m.setPending(false);
                        messagesAdapter.notifyItemChanged(i + 1);
                        break;
                    }
                }
            });
        }

        @Override
        public void onAllMessagesRead(String updatedChatId) {
            if (chatId != null && chatId.equals(updatedChatId)) {
                runOnUiThread(() -> {
                    boolean updated = false;
                    for (Message m : messageList) {
                        if (m.getSenderId() != null && m.getSenderId().equals(currentUserId)) {
                            m.setDelivered(true);
                            m.setRead(true);
                            m.setPending(false);
                            updated = true;
                        }
                    }
                    if (updated && messagesAdapter != null) {
                        messagesAdapter.notifyDataSetChanged();
                    }
                });
            }
        }

        @Override
        public void onPeerTyping(String peerId, boolean isTyping) {
            if (peerId != null && peerId.equals(recipientId)) {
                runOnUiThread(() -> {
                    updateVanishUI(isTyping);
                });
            }
        }

        @Override
        public void onPeerConnected(String peerId) {
            if (peerId != null && peerId.equals(recipientId)) {
                runOnUiThread(() -> {
                    updatePeerP2PStatus(true);
                    sendReadReceiptsForUnreadMessages();
                });
            }
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            if (peerId != null && peerId.equals(recipientId)) {
                runOnUiThread(() -> updatePeerP2PStatus(false));
            }
        }

        @Override
        public void onCallEstablished(String peerId) {}

        @Override
        public void onCallEnded(String peerId) {}

        @Override
        public void onIncomingCall(String peerId, String peerName, String callType, String incomingCallId) {
            // Incoming CallActivity launch and heads-up notification is handled centrally
            // by WebRtcManager to avoid duplicate overlapping activities.
        }
    };

    private void updatePeerP2PStatus(boolean isConnected) {
        this.isP2PActive = isConnected;
        updatePresenceAndToolbarStatus();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).clearAllPendingFlags();
                    runOnUiThread(() -> reloadMessagesFromLocalVault());
                }
            };
            try {
                cm.registerDefaultNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }

        // Block screenshots for privacy
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_chat);

        audioRecorderHelper = new AudioRecorderHelper();
        db = FirebaseFirestore.getInstance();
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        if (savedInstanceState != null) {
            isChatPinUnlocked = savedInstanceState.getBoolean("isChatPinUnlocked", false);
        }
        if (getIntent().getBooleanExtra("isPinVerified", false)) {
            isChatPinUnlocked = true;
        }

        chatId = getIntent().getStringExtra("chatId");
        if (chatId == null) {
            chatId = getIntent().getStringExtra("chat_id");
        }
        recipientId = getIntent().getStringExtra("recipientId");
        if (recipientId == null) {
            recipientId = getIntent().getStringExtra("recipient_id");
        }
        if (recipientId != null && recipientId.trim().isEmpty()) {
            recipientId = null;
        }
        recipientName = getIntent().getStringExtra("recipientName");

        // Resolve missing recipient details from chatId if launched from notification or 1-on-1 chat
        if (recipientId == null && chatId != null && !chatId.startsWith("group_") && !chatId.startsWith("channel_") && !chatId.startsWith("lounge_")) {
            String[] parts = chatId.split("_");
            if (parts.length == 2) {
                recipientId = parts[0].equals(currentUserId) ? parts[1] : parts[0];
            }
        }
        if (chatId == null && recipientId != null && currentUserId != null) {
            chatId = com.m3.veil.webrtc.WebRtcManager.getChatId(currentUserId, recipientId);
        }
        if (recipientId != null && !recipientId.trim().isEmpty()
                && !recipientId.startsWith("group_") && !recipientId.startsWith("channel_") && !recipientId.startsWith("lounge_")) {
            String myOwnName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
            if (recipientName == null || recipientName.trim().isEmpty()
                    || (myOwnName != null && recipientName.equals(myOwnName))
                    || "Direct Chat".equals(recipientName)
                    || "Veil Contact".equals(recipientName)
                    || "Veil User".equals(recipientName)
                    || "Anonymous Peer".equals(recipientName)) {
                db.collection("users").document(recipientId).get().addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String dName = doc.getString("displayName");
                        if (dName == null || dName.isEmpty()) dName = doc.getString("username");
                        if (dName != null && !dName.isEmpty()) {
                            recipientName = dName;
                            if (currentLocalChat != null && !currentLocalChat.isGroup()) {
                                currentLocalChat.setName(dName);
                                com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).saveOrUpdateChat(currentLocalChat);
                            }
                        }
                        String remoteAvatar = doc.getString("avatar");
                        if (remoteAvatar != null && !remoteAvatar.isEmpty()) {
                            if (currentLocalChat != null) {
                                currentLocalChat.setAvatar(remoteAvatar);
                                com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).saveOrUpdateChat(currentLocalChat);
                            }
                        }
                        updateRecipientHeader();
                    }
                });
            }
        }

        // Initialize WebRTC P2P Manager & DataChannel
        com.m3.veil.webrtc.WebRtcManager.getInstance(this).init(currentUserId);
        if (recipientId != null && !recipientId.trim().isEmpty()
                && !recipientId.startsWith("group_") && !recipientId.startsWith("channel_") && !recipientId.startsWith("lounge_")) {
            com.m3.veil.webrtc.WebRtcManager.getInstance(this).initiateConnection(recipientId);
        }
        com.m3.veil.webrtc.WebRtcManager.getInstance(this).addEventListener(p2pChatListener);
        com.m3.veil.manager.VeilMessagesController.getInstance(this).addObserver(messagesControllerObserver);
        com.m3.veil.manager.VeilMessagesController.getInstance(this).setActiveChatIdInForeground(chatId);

        // Vanish engine
        vanishHandler = new Handler(Looper.getMainLooper());
        vanishRunnable = () -> {
            checkAndVanishMessages();
            vanishHandler.postDelayed(vanishRunnable, 1000);
        };
        vanishHandler.post(vanishRunnable);

        initializeViews();
        handleWindowInsets();
        setupRecyclerView();
        setupClickListeners();

        // Default vanish timer from privacy settings if not overridden
        android.content.SharedPreferences privacyPrefs = getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE);
        long defaultVanish = privacyPrefs.getLong("default_vanish_timer", 0);
        if (defaultVanish > 0 && currentVanishTimer == 0) {
            currentVanishTimer = defaultVanish;
        }

        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).addListener(localDbListener);
        this.isP2PActive = (recipientId != null && !recipientId.trim().isEmpty())
                && com.m3.veil.webrtc.WebRtcManager.getInstance(this).isPeerConnected(recipientId);

        ensureLocalChatExists();
        if (isNetworkAvailable() && chatId != null) {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).clearPendingFlagsForChat(chatId);
        }
        loadLocalChatSettings();
        checkChatLockIfEnabled();
        fetchRecipientPublicKey();
        listenForMessages();

        // WhatsApp Status Reply: send quoted status reply directly into this chat
        String statusReply = getIntent().getStringExtra("statusReplyText");
        String statusQuote = getIntent().getStringExtra("statusQuoteText");
        if (statusReply != null && !statusReply.trim().isEmpty()) {
            String quoted = statusQuote != null ? statusQuote : "";
            if (quoted.length() > 80) quoted = quoted.substring(0, 80) + "…";
            sendMessageWithType("text", "↩️ Status · \"" + quoted + "\"\n" + statusReply.trim());
            getIntent().removeExtra("statusReplyText");
        }
    }

    private void handleWindowInsets() {
        View chatRoot = findViewById(R.id.chat_root);
        UiAnimator.enableEdgeToEdge(this, chatRoot);

        ViewCompat.setOnApplyWindowInsetsListener(chatRoot, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            View topHeader = findViewById(R.id.topHeaderContainer);
            if (topHeader != null) {
                topHeader.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            } else {
                View toolbar = findViewById(R.id.toolbar);
                if (toolbar != null) {
                    int toolbarPaddingBottom = toolbar.getPaddingBottom();
                    toolbar.setPadding(systemBars.left, systemBars.top, systemBars.right, toolbarPaddingBottom);
                }
            }

            int bottomPadding = Math.max(systemBars.bottom, ime.bottom);
            View bottomContainer = findViewById(R.id.bottomContainer);
            if (bottomContainer != null) {
                int topPad = (int) (4 * getResources().getDisplayMetrics().density);
                int baseBottom = (int) (6 * getResources().getDisplayMetrics().density);
                bottomContainer.setPadding(systemBars.left, topPad, systemBars.right, bottomPadding + baseBottom);
            }

            if (ime.bottom > 0 && messagesAdapter != null && messagesAdapter.getItemCount() > 0) {
                messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
            }

            return insets;
        });
    }

    private void updateRecipientHeader() {
        TextView nameText = findViewById(R.id.recipientName);
        if (nameText != null) {
            nameText.setText(recipientName != null ? recipientName : getString(R.string.anonymous));
        }

        ImageView recipientAvatar = findViewById(R.id.recipientAvatar);
        if (recipientAvatar != null) {
            String avSource = null;
            if (currentLocalChat != null && currentLocalChat.getAvatar() != null && !currentLocalChat.getAvatar().trim().isEmpty()) {
                avSource = currentLocalChat.getAvatar();
            }
            if (avSource == null && cachedRecipientUser != null && cachedRecipientUser.getAvatar() != null && !cachedRecipientUser.getAvatar().trim().isEmpty()) {
                avSource = cachedRecipientUser.getAvatar();
            }
            if (avSource == null && getIntent() != null) {
                avSource = getIntent().getStringExtra("recipientAvatar");
            }
            boolean isGrp = (chatId != null && (chatId.startsWith("group_") || chatId.startsWith("channel_")))
                    || (currentLocalChat != null && currentLocalChat.isGroup());
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(recipientAvatar, avSource, recipientName, isGrp);
        }
    }

    private void initializeViews() {
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView);
        layoutScrollToBottom = findViewById(R.id.layoutScrollToBottom);
        btnScrollToBottom = findViewById(R.id.btnScrollToBottom);
        if (btnScrollToBottom != null) {
            btnScrollToBottom.setOnClickListener(v -> {
                newMessagesWhileScrolledUp = 0;
                TextView tvUnreadBadge = findViewById(R.id.tvScrollUnreadBadge);
                if (tvUnreadBadge != null) tvUnreadBadge.setVisibility(View.GONE);
                if (messagesAdapter != null && messagesAdapter.getItemCount() > 0) {
                    messagesRecyclerView.smoothScrollToPosition(messagesAdapter.getItemCount() - 1);
                }
            });
        }

        updateRecipientHeader();

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        findViewById(R.id.profileArea).setOnClickListener(v -> {
            Intent intent = new Intent(this, UserProfileActivity.class);
            intent.putExtra("userId", recipientId);
            intent.putExtra("userName", recipientName);
            intent.putExtra("chatId", chatId);
            boolean isGrp = (chatId != null && chatId.startsWith("group_"));
            com.m3.veil.model.Chat c = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getChat(chatId);
            if (c != null && c.isGroup()) isGrp = true;
            intent.putExtra("isGroup", isGrp);
            startActivity(intent);
        });

        View btnAudioCall = findViewById(R.id.btnAudioCall);
        View btnVideoCall = findViewById(R.id.btnVideoCall);
        if (btnAudioCall != null) {
            UiAnimator.attachSpringPress(btnAudioCall);
            btnAudioCall.setOnClickListener(v -> startCall("audio"));
        }
        if (btnVideoCall != null) {
            UiAnimator.attachSpringPress(btnVideoCall);
            btnVideoCall.setOnClickListener(v -> startCall("video"));
        }

        // In-Chat Message Search Bar (WhatsApp Style)
        View btnChatSearch = findViewById(R.id.btnChatSearch);
        View chatSearchBar = findViewById(R.id.chatSearchBar);
        EditText etChatSearch = findViewById(R.id.etChatSearch);
        TextView tvChatSearchCount = findViewById(R.id.tvChatSearchCount);
        View btnCloseChatSearch = findViewById(R.id.btnCloseChatSearch);

        if (btnChatSearch != null && chatSearchBar != null && etChatSearch != null) {
            btnChatSearch.setOnClickListener(v -> {
                if (chatSearchBar.getVisibility() == View.VISIBLE) {
                    etChatSearch.setText("");
                    chatSearchBar.setVisibility(View.GONE);
                } else {
                    chatSearchBar.setVisibility(View.VISIBLE);
                    etChatSearch.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(etChatSearch, InputMethodManager.SHOW_IMPLICIT);
                }
            });
            if (btnCloseChatSearch != null) {
                btnCloseChatSearch.setOnClickListener(v -> {
                    etChatSearch.setText("");
                    chatSearchBar.setVisibility(View.GONE);
                });
            }
            etChatSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String q = s.toString().trim().toLowerCase();
                    if (q.isEmpty() || messageList == null) {
                        if (tvChatSearchCount != null) tvChatSearchCount.setText("");
                        return;
                    }
                    int matches = 0;
                    int lastMatchAdapterPos = -1;
                    for (int i = 0; i < messageList.size(); i++) {
                        Message m = messageList.get(i);
                        if (m.getText() != null && m.getText().toLowerCase().contains(q)) {
                            matches++;
                            lastMatchAdapterPos = i + 1;
                        }
                    }
                    if (tvChatSearchCount != null) {
                        tvChatSearchCount.setText(matches + (matches == 1 ? " match" : " matches"));
                    }
                    if (lastMatchAdapterPos != -1 && messagesRecyclerView != null) {
                        highlightPosition = lastMatchAdapterPos;
                        messagesRecyclerView.smoothScrollToPosition(lastMatchAdapterPos);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        // Collapsible Pinned Message Banner (WhatsApp Style)
        View pinnedMessageBanner = findViewById(R.id.pinnedMessageBanner);
        View btnUnpinBanner = findViewById(R.id.btnUnpinBanner);
        if (pinnedMessageBanner != null) {
            pinnedMessageBanner.setOnClickListener(v -> {
                if (currentLocalChat != null && currentLocalChat.getPinnedMessageId() != null && messageList != null) {
                    String pinId = currentLocalChat.getPinnedMessageId();
                    for (int i = 0; i < messageList.size(); i++) {
                        if (pinId.equals(messageList.get(i).getId())) {
                            int pos = i + 1;
                            highlightPosition = pos;
                            messagesRecyclerView.smoothScrollToPosition(pos);
                            break;
                        }
                    }
                }
            });
        }
        if (btnUnpinBanner != null) {
            btnUnpinBanner.setOnClickListener(v -> {
                com.m3.veil.manager.VeilMessagesController.getInstance(this).unpinMessage(chatId, recipientId);
                Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show();
            });
        }

        setupSpeedChatRouletteHud();

        // Quick Features Bar Above Composer
        TextView chipQuickBlink = findViewById(R.id.chipQuickBlink);
        TextView chipQuickPoll = findViewById(R.id.chipQuickPoll);
        TextView chipQuickDoodle = findViewById(R.id.chipQuickDoodle);
        TextView chipQuickVoiceMask = findViewById(R.id.chipQuickVoiceMask);
        TextView chipQuickIcebreaker = findViewById(R.id.chipQuickIcebreaker);
        TextView chipQuickSmartReply = findViewById(R.id.chipQuickSmartReply);

        if (chipQuickBlink != null) {
            chipQuickBlink.setOnClickListener(v -> toggleBlinkMode());
        }
        if (chipQuickPoll != null) {
            chipQuickPoll.setOnClickListener(v -> showCreateInlinePollDialog());
        }
        if (chipQuickDoodle != null) {
            chipQuickDoodle.setOnClickListener(v -> showDoodlePadDialog());
        }
        if (chipQuickVoiceMask != null) {
            chipQuickVoiceMask.setOnClickListener(v -> showVoiceFilterDialog());
        }
        if (chipQuickIcebreaker != null) {
            chipQuickIcebreaker.setOnClickListener(v -> sendTruthOrDarePrompt());
        }
        if (chipQuickSmartReply != null) {
            chipQuickSmartReply.setOnClickListener(v -> {
                Message targetMsg = null;
                if (messageList != null && !messageList.isEmpty()) {
                    targetMsg = messageList.get(messageList.size() - 1);
                }
                if (targetMsg != null) {
                    showTranslateAndSmartReplyDialog(targetMsg);
                } else {
                    sendTruthOrDarePrompt();
                }
            });
        }

        TextView chipQuickP2PGames = findViewById(R.id.chipQuickP2PGames);
        if (chipQuickP2PGames != null) {
            chipQuickP2PGames.setOnClickListener(v -> showP2PMinigamesDrawer());
        }

        findViewById(R.id.moreButton).setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, v);
            popup.getMenuInflater().inflate(R.menu.menu_chat, popup.getMenu());
            android.view.Menu menu = popup.getMenu();

            boolean isGrp = isGroupChatContext();
            boolean isChan = (currentLocalChat != null && "channel".equalsIgnoreCase(currentLocalChat.getType()))
                    || (chatId != null && chatId.startsWith("channel_"));

            // Hide items that are already 1-tap buttons on the header or Quick Chips bar above the composer
            setMenuItemVisible(menu, R.id.action_view_profile, false);
            setMenuItemVisible(menu, R.id.action_blink_mode, false);
            setMenuItemVisible(menu, R.id.action_create_poll, false);
            setMenuItemVisible(menu, R.id.action_doodle_pad, false);
            setMenuItemVisible(menu, R.id.action_voice_filter, false);
            setMenuItemVisible(menu, R.id.action_truth_or_dare, false);
            setMenuItemVisible(menu, R.id.action_p2p_minigames, false);
            setMenuItemVisible(menu, R.id.action_vanish_mode, false);

            android.view.MenuItem vanishItem = menu.findItem(R.id.action_vanish_timer);
            if (vanishItem != null) {
                vanishItem.setTitle("⏱️ Disappearing Messages (Vanish)");
            }

            // Group/Channel-only options vs. 1:1 Direct Chat options
            setMenuItemVisible(menu, R.id.action_group_events, isGrp);
            setMenuItemVisible(menu, R.id.action_group_subgroups, isGrp && !isChan);
            setMenuItemVisible(menu, R.id.action_group_themes, isGrp);
            setMenuItemVisible(menu, R.id.action_group_achievements, isGrp && !isChan);
            setMenuItemVisible(menu, R.id.action_chat_wallpaper, !isGrp);

            android.view.MenuItem schedItem = menu.findItem(R.id.action_schedule_message);
            if (schedItem != null) {
                schedItem.setTitle(isChan ? "⏰ Schedule Channel Post" : "⏰ Schedule Message");
            }

            android.view.MenuItem blockOrLeaveItem = menu.findItem(R.id.action_block_user);
            if (blockOrLeaveItem != null) {
                if (isGrp) {
                    blockOrLeaveItem.setTitle(isChan ? "🚪 Leave Channel" : "🚪 Leave Group");
                } else {
                    blockOrLeaveItem.setTitle(isChatBlocked ? "Unblock User" : "Block User");
                }
            }

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_view_profile) {
                    findViewById(R.id.profileArea).performClick();
                    return true;
                } else if (id == R.id.action_blink_mode) {
                    toggleBlinkMode();
                    return true;
                } else if (id == R.id.action_create_poll) {
                    showCreateInlinePollDialog();
                    return true;
                } else if (id == R.id.action_doodle_pad) {
                    showDoodlePadDialog();
                    return true;
                } else if (id == R.id.action_voice_filter) {
                    showVoiceFilterDialog();
                    return true;
                } else if (id == R.id.action_truth_or_dare) {
                    sendTruthOrDarePrompt();
                    return true;
                } else if (id == R.id.action_vanish_timer) {
                    showVanishTimerDialog();
                    return true;
                } else if (id == R.id.action_vanish_mode) {
                    showVanishModeDialog();
                    return true;
                } else if (id == R.id.action_chat_wallpaper) {
                    showChatWallpaperDialog();
                    return true;
                } else if (id == R.id.action_chat_lock) {
                    showSetChatLockDialog();
                    return true;
                } else if (id == R.id.action_secret_inbox) {
                    toggleSecretVaultChat();
                    return true;
                } else if (id == R.id.action_group_events) {
                    showGroupEventsCalendar();
                    return true;
                } else if (id == R.id.action_group_subgroups) {
                    showSubgroupsDialog();
                    return true;
                } else if (id == R.id.action_group_themes) {
                    showGroupCustomThemesDialog();
                    return true;
                } else if (id == R.id.action_group_achievements) {
                    showGroupAchievementsDialog();
                    return true;
                } else if (id == R.id.action_schedule_message) {
                    showScheduleMessageDialog();
                    return true;
                } else if (id == R.id.action_read_later_reminders) {
                    showReadLaterRemindersDialog();
                    return true;
                } else if (id == R.id.action_time_travel_replay) {
                    startTimeTravelChatReplay();
                    return true;
                } else if (id == R.id.action_p2p_minigames) {
                    showP2PMinigamesDrawer();
                    return true;
                } else if (id == R.id.action_clear_chat) {
                    confirmClearChat();
                    return true;
                } else if (id == R.id.action_block_user) {
                    if (isGrp) {
                        confirmLeaveGroupFromChat(isChan);
                    } else {
                        confirmBlockUser();
                    }
                    return true;
                }
                return false;
            });
            popup.show();
        });

        messageEditText = findViewById(R.id.messageEditText);
        sendButton = findViewById(R.id.sendButton);

        layoutSmartReplies = findViewById(R.id.layoutSmartReplies);
        containerSmartReplies = findViewById(R.id.containerSmartReplies);
        initReplayControls();

        replyPreviewContainer = findViewById(R.id.replyPreviewContainer);
        replyPreviewName = findViewById(R.id.replyPreviewName);
        replyPreviewText = findViewById(R.id.replyPreviewText);
        closeReplyButton = findViewById(R.id.closeReplyButton);
        closeReplyButton.setOnClickListener(v -> cancelReply());

        // Attachment Button: Opens modern Material 3 Bottom Sheet Dialog
        ImageView attachmentButton = findViewById(R.id.attachmentButton);
        UiAnimator.attachSpringPress(attachmentButton);
        attachmentButton.setOnClickListener(v -> showAttachmentBottomSheet());

        // Emoji & Sticker Picker
        ImageView emojiButton = findViewById(R.id.emojiButton);
        if (emojiButton != null) {
            UiAnimator.attachSpringPress(emojiButton);
            emojiButton.setOnClickListener(v -> showEmojiBottomSheet());
        }

        // In-app Built-in Camera & Gallery Quick Launcher (WhatsApp & Signal Style)
        ImageView cameraButton = findViewById(R.id.cameraButton);
        if (cameraButton != null) {
            UiAnimator.attachSpringPress(cameraButton);
            cameraButton.setOnClickListener(v -> openCameraGallery());
        }

        inputContainer = findViewById(R.id.inputContainer);
        recordingContainer = findViewById(R.id.recordingContainer);
        lockedRecordingContainer = findViewById(R.id.lockedRecordingContainer);
        recordBlinkDot = findViewById(R.id.recordBlinkDot);
        recordTimerText = findViewById(R.id.recordTimerText);
        lockedRecordTimerText = findViewById(R.id.lockedRecordTimerText);
        slideToCancelText = findViewById(R.id.slideToCancelText);
        recordLockIcon = findViewById(R.id.recordLockIcon);
        deleteRecordingButton = findViewById(R.id.deleteRecordingButton);
        pauseRecordingButton = findViewById(R.id.pauseRecordingButton);
        pauseRecordingIcon = findViewById(R.id.pauseRecordingIcon);
        pauseRecordingText = findViewById(R.id.pauseRecordingText);
        lockedSendButton = findViewById(R.id.lockedSendButton);
        lockedWaveformView = findViewById(R.id.lockedWaveformView);

        deleteRecordingButton.setOnClickListener(v -> cancelRecordingUI());
        pauseRecordingButton.setOnClickListener(v -> togglePauseRecording());
        lockedSendButton.setOnClickListener(v -> stopRecordingAndSendUI());

        TextView btnLockedVoiceFilter = findViewById(R.id.btnLockedVoiceFilter);
        if (btnLockedVoiceFilter != null) {
            btnLockedVoiceFilter.setOnClickListener(v -> {
                switch (activeVoiceFilter) {
                    case "normal":
                        activeVoiceFilter = "robot";
                        btnLockedVoiceFilter.setText("🤖 Robot");
                        break;
                    case "robot":
                        activeVoiceFilter = "deep";
                        btnLockedVoiceFilter.setText("🕵️ Deep");
                        break;
                    case "deep":
                        activeVoiceFilter = "chipmunk";
                        btnLockedVoiceFilter.setText("🐿️ Helium");
                        break;
                    default:
                        activeVoiceFilter = "normal";
                        btnLockedVoiceFilter.setText("🎙️ Normal");
                        break;
                }
                updateVoiceMaskChipUI();
            });
        }

        TextView btnLockedViewOnceVoice = findViewById(R.id.btnLockedViewOnceVoice);
        if (btnLockedViewOnceVoice != null) {
            btnLockedViewOnceVoice.setOnClickListener(v -> {
                toggleBlinkMode();
                btnLockedViewOnceVoice.setText(isBlinkModeActive ? "⚡ 1x On" : "⚡ 1x Off");
                btnLockedViewOnceVoice.setBackgroundResource(isBlinkModeActive ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            });
        }

        sendButton.setImageResource(R.drawable.ic_mic);
        sendButton.setAlpha(1.0f);
        sendButton.setEnabled(true);

        // Enforce Signal-style Incognito Keyboard (IME_FLAG_NO_PERSONALIZED_LEARNING)
        android.content.SharedPreferences privPrefs = getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE);
        if (privPrefs.getBoolean("incognito_keyboard", true)) {
            messageEditText.setImeOptions(messageEditText.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }

        // Restore per-chat draft if available
        if (chatId != null) {
            String savedDraft = getSharedPreferences("VeilChatDrafts", Context.MODE_PRIVATE).getString("draft_" + chatId, "");
            if (savedDraft != null && !savedDraft.isEmpty()) {
                messageEditText.setText(savedDraft);
                messageEditText.setSelection(savedDraft.length());
                sendButton.setImageResource(R.drawable.ic_send);
                if (cameraButton != null) cameraButton.setVisibility(View.GONE);
            }
        }

        messageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String rawText = s != null ? s.toString() : "";
                boolean hasText = !rawText.trim().isEmpty();
                sendButton.setImageResource(hasText ? R.drawable.ic_send : R.drawable.ic_mic);
                if (cameraButton != null) {
                    cameraButton.setVisibility(hasText ? View.GONE : View.VISIBLE);
                }

                if (chatId != null) {
                    android.content.SharedPreferences.Editor draftEd = getSharedPreferences("VeilChatDrafts", Context.MODE_PRIVATE).edit();
                    if (hasText) {
                        draftEd.putString("draft_" + chatId, rawText).apply();
                    } else {
                        draftEd.remove("draft_" + chatId).apply();
                    }
                }

                if (hasText && !isTyping && recipientId != null) {
                    isTyping = true;
                    com.m3.veil.manager.VeilMessagesController.getInstance(ChatActivity.this).sendTyping(chatId, recipientId, true);
                }

                // Safe Word Panic Wipe Check
                String safeWord = com.m3.veil.manager.VeilFeatureManager.getInstance(ChatActivity.this).getSafeWord();
                if (safeWord != null && !safeWord.isEmpty() && rawText.trim().equalsIgnoreCase(safeWord)) {
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).secureWipeAllData();
                    Toast.makeText(ChatActivity.this, "🛡️ Safe Word Triggered: Vault Wiped", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                typingHandler.removeCallbacks(stopTypingRunnable);
                if (hasText) {
                    typingHandler.postDelayed(stopTypingRunnable, 2000);
                } else if (isTyping) {
                    stopTypingRunnable.run();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void showAttachmentBottomSheet() {
        AttachmentBottomSheet bottomSheet = new AttachmentBottomSheet();
        bottomSheet.setAttachmentSelectionListener(new AttachmentBottomSheet.AttachmentSelectionListener() {
            @Override
            public void onSelectImage() {
                openCameraGallery(true);
            }

            @Override
            public void onSelectVideo() {
                openCameraGallery(true);
            }

            @Override
            public void onSelectDocument() {
                showWhatsAppDocumentPicker();
            }

            @Override
            public void onSelectLocation() {
                showWhatsAppLocationPicker();
            }

            @Override
            public void onSelectContact() {
                showWhatsAppContactPicker();
            }

            @Override
            public void onSelectCamera() {
                openCameraGallery(false);
            }

            @Override
            public void onSelectPoll() {
                showCreateInlinePollDialog();
            }

            @Override
            public void onSelectDoodle() {
                showDoodlePadDialog();
            }

            @Override
            public void onSelectBlink() {
                toggleBlinkMode();
            }
        });
        bottomSheet.show(getSupportFragmentManager(), "AttachmentBottomSheet");
    }

    private void openCameraGallery() {
        openCameraGallery(false);
    }

    private void openCameraGallery(boolean startInGallery) {
        Intent intent = new Intent(this, CameraGalleryActivity.class);
        intent.putExtra(CameraGalleryActivity.EXTRA_START_IN_GALLERY, startInGallery);
        startActivityForResult(intent, REQ_CAMERA_GALLERY);
    }

    private void showWhatsAppDocumentPicker() {
        com.m3.veil.ui.chat.sheets.WhatsAppDocumentPickerSheet sheet =
                new com.m3.veil.ui.chat.sheets.WhatsAppDocumentPickerSheet();
        sheet.setDocumentPickerListener(new com.m3.veil.ui.chat.sheets.WhatsAppDocumentPickerSheet.DocumentPickerListener() {
            @Override
            public void onDocumentFileSelected(File file, String caption) {
                uploadAndSendMedia(file, "document", caption);
            }

            @Override
            public void onBrowseSystemDocumentsRequested() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQ_PICK_DOCUMENT);
            }
        });
        sheet.show(getSupportFragmentManager(), "WhatsAppDocumentPickerSheet");
    }

    private void showWhatsAppLocationPicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, PERM_REQ_LOCATION);
        }
        com.m3.veil.ui.chat.sheets.WhatsAppLocationPickerSheet sheet =
                new com.m3.veil.ui.chat.sheets.WhatsAppLocationPickerSheet();
        sheet.setLocationPickerListener(geoUriString -> sendMessageWithType("location", geoUriString));
        sheet.show(getSupportFragmentManager(), "WhatsAppLocationPickerSheet");
    }

    private void showWhatsAppContactPicker() {
        com.m3.veil.ui.chat.sheets.WhatsAppContactPickerSheet sheet =
                new com.m3.veil.ui.chat.sheets.WhatsAppContactPickerSheet();
        sheet.setContactPickerListener(selectedContacts -> {
            if (selectedContacts == null) return;
            for (com.m3.veil.ui.chat.sheets.WhatsAppContactPickerSheet.ContactEntry entry : selectedContacts) {
                sendMessageWithType("contact", entry.name + "\n" + entry.phoneOrHandle);
            }
        });
        sheet.show(getSupportFragmentManager(), "WhatsAppContactPickerSheet");
    }

    private void checkAndShowBuiltInMediaGallery() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                }, PERM_REQ_STORAGE);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_REQ_STORAGE);
                return;
            }
        }
        showBuiltInMediaGallery();
    }

    private void showBuiltInMediaGallery() {
        MediaGalleryBottomSheet gallerySheet = new MediaGalleryBottomSheet();
        gallerySheet.setMediaGalleryListener(new MediaGalleryBottomSheet.MediaGalleryListener() {
            @Override
            public void onMediaSelectedToSend(Uri mediaUri, String mediaType, String caption) {
                handleDirectMediaSend(mediaUri, mediaType, caption);
            }

            @Override
            public void onMediaSelectedToEdit(Uri mediaUri, String mediaType) {
                Intent editIntent = new Intent(ChatActivity.this, MediaEditActivity.class);
                editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, mediaUri);
                editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE, mediaType);
                startActivityForResult(editIntent, REQ_MEDIA_EDIT);
            }

            @Override
            public void onCameraRequested() {
                checkAndLaunchCamera();
            }
        });
        gallerySheet.show(getSupportFragmentManager(), "MediaGalleryBottomSheet");
    }

    private void handleDirectMediaSend(Uri mediaUri, String mediaType, String caption) {
        if (mediaUri == null) return;
        new Thread(() -> {
            try {
                String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
                File dest = new File(getExternalCacheDir(), "pick_" + System.currentTimeMillis() + ext);
                try (InputStream in = getContentResolver().openInputStream(mediaUri);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                runOnUiThread(() -> uploadAndSendMedia(dest, mediaType, caption));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load media: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void checkAndShareLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PERM_REQ_LOCATION);
            return;
        }
        shareCurrentLocation();
    }

    private void shareCurrentLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location location = null;
            if (lm != null) {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                if (location == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            }

            if (location != null) {
                double lat = location.getLatitude();
                double lng = location.getLongitude();
                String geoString = "geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(Shared Location)";
                sendMessageWithType("location", geoString);
                Toast.makeText(this, "Location shared", Toast.LENGTH_SHORT).show();
            } else if (lm != null) {
                Toast.makeText(this, "Fetching location... please wait", Toast.LENGTH_SHORT).show();
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location loc) {
                        double lat = loc.getLatitude();
                        double lng = loc.getLongitude();
                        String geoString = "geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(Shared Location)";
                        sendMessageWithType("location", geoString);
                        Toast.makeText(ChatActivity.this, "Location shared", Toast.LENGTH_SHORT).show();
                    }
                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override
                    public void onProviderEnabled(@NonNull String provider) {}
                    @Override
                    public void onProviderDisabled(@NonNull String provider) {}
                }, Looper.getMainLooper());
            } else {
                Toast.makeText(this, "Location service not available", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not get location: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERM_REQ_CAMERA);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        try {
            File photoFile = new File(getExternalCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
            cameraCaptureUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraCaptureUri);
            startActivityForResult(intent, REQ_CAPTURE_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSelectedDocument(Uri docUri) {
        new Thread(() -> {
            try {
                String fileName = "Document";
                long fileSize = 0;
                Cursor cursor = getContentResolver().query(docUri, null, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                    }
                    cursor.close();
                }

                File tempDoc = new File(getExternalCacheDir(), "doc_" + System.currentTimeMillis() + "_" + fileName);
                try (InputStream in = getContentResolver().openInputStream(docUri);
                     OutputStream out = new FileOutputStream(tempDoc)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                final File fileToSend = tempDoc;
                runOnUiThread(() -> uploadAndSendMedia(fileToSend, "document"));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load document: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void handleSelectedContact(Uri contactUri) {
        try {
            String[] projection = new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            };
            Cursor cursor = getContentResolver().query(contactUri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                String name = nameIndex != -1 ? cursor.getString(nameIndex) : "Contact";
                String number = numIndex != -1 ? cursor.getString(numIndex) : "";
                cursor.close();

                sendMessageWithType("contact", name + "\n" + number);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to read contact: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void sendMessageWithType(String type, String content) {
        if (content == null || content.isEmpty()) return;

        String messageId = UUID.randomUUID().toString();
        Message message = new Message(
                messageId,
                currentUserId,
                recipientId,
                content,
                Timestamp.now(),
                type
        );
        message.setBlink(isBlinkModeActive);
        message.setSenderName(com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName());

        // Anonymous Channel Admin System (#82) - Blind rotating alias
        if (currentLocalChat != null && ("channel".equalsIgnoreCase(currentLocalChat.getType()) || (chatId != null && chatId.startsWith("channel_")))) {
            com.m3.veil.database.EncryptedChatDatabase.ChannelAdminKey cak =
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getChannelAdminKey(chatId);
            if (cak == null) {
                cak = new com.m3.veil.database.EncryptedChatDatabase.ChannelAdminKey(
                        chatId,
                        "Channel Admin (Epoch #1)",
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()
                );
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveChannelAdminKey(cak);
            }
            message.setSenderName(cak.adminAlias);
            message.setSenderId(cak.adminAlias);
        }

        // Scoped Secret Sub-Group / Topic (#71) (sendMessageWithType)
        if (currentSubgroupId != null && currentSubgroupTitle != null) {
            message.setContent("[#" + currentSubgroupTitle + "] " + content);
        }

        if (currentVanishTimer > 0) {
            long expiresMillis = System.currentTimeMillis() + currentVanishTimer;
            message.setExpiresAt(new Timestamp(new Date(expiresMillis)));
        }

        if (replyMessage != null) {
            message.setReplyToMessageId(replyMessage.getId());
            boolean isReplySenderYou = replyMessage.getSenderId().equals(currentUserId);
            message.setReplySenderName(isReplySenderYou ? "You" : (recipientName != null ? recipientName : "Other User"));
            cancelReply();
        }

        if (isBlinkModeActive) {
            isBlinkModeActive = false;
            messageEditText.setHint(R.string.type_message);
            updateBlinkModeChipUI();
        }

        // Transmit via application-wide VeilMessagesController (warm cache, outbox retry, and direct P2P delivery)
        com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(currentLocalChat, recipientId, message);
        reloadMessagesFromLocalVault();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == PERM_REQ_LOCATION) {
                shareCurrentLocation();
            } else if (requestCode == PERM_REQ_CAMERA) {
                launchCamera();
            } else if (requestCode == PERM_REQ_AUDIO) {
                Toast.makeText(this, "Audio permission granted. Tap mic to record.", Toast.LENGTH_SHORT).show();
            } else if (requestCode == PERM_REQ_STORAGE) {
                showBuiltInMediaGallery();
            }
        } else {
            if (requestCode == PERM_REQ_STORAGE) {
                // Show built-in gallery anyway (camera tile will still be usable)
                showBuiltInMediaGallery();
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri selectedMediaUri = data.getData();
            if (selectedMediaUri != null) {
                Intent editIntent = new Intent(this, MediaEditActivity.class);
                editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, selectedMediaUri);
                editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE, "image");
                startActivityForResult(editIntent, REQ_MEDIA_EDIT);
            }
        } else if (requestCode == REQ_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            Uri selectedMediaUri = data.getData();
            if (selectedMediaUri != null) {
                TrimVideo.activity(selectedMediaUri.toString())
                        .setTrimType(TrimType.DEFAULT)
                        .setHideSeekBar(true)
                        .start(this, videoTrimResultLauncher);
            }
        } else if (requestCode == REQ_PICK_DOCUMENT && resultCode == RESULT_OK && data != null) {
            Uri docUri = data.getData();
            if (docUri != null) {
                handleSelectedDocument(docUri);
            }
        } else if (requestCode == REQ_PICK_CONTACT && resultCode == RESULT_OK && data != null) {
            Uri contactUri = data.getData();
            if (contactUri != null) {
                handleSelectedContact(contactUri);
            }
        } else if (requestCode == REQ_CAPTURE_CAMERA && resultCode == RESULT_OK) {
            if (cameraCaptureUri != null) {
                Intent editIntent = new Intent(this, MediaEditActivity.class);
                editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_URI, cameraCaptureUri);
                editIntent.putExtra(MediaEditActivity.EXTRA_MEDIA_TYPE, "image");
                startActivityForResult(editIntent, REQ_MEDIA_EDIT);
            }
        } else if ((requestCode == REQ_MEDIA_EDIT || requestCode == REQ_CAMERA_GALLERY) && resultCode == RESULT_OK && data != null) {
            String editedPath = data.getStringExtra(MediaEditActivity.RESULT_EDITED_FILE_PATH);
            String mediaType = data.getStringExtra(MediaEditActivity.RESULT_MEDIA_TYPE);
            String caption = data.getStringExtra(MediaEditActivity.RESULT_CAPTION);
            boolean isViewOnce = data.getBooleanExtra(MediaEditActivity.RESULT_IS_VIEW_ONCE, false);
            if (isViewOnce) {
                isBlinkModeActive = true;
            }
            if (editedPath != null) {
                File editedFile = new File(editedPath);
                uploadAndSendMedia(editedFile, mediaType != null ? mediaType : "image", caption);
            }
            java.util.ArrayList<String> batchPaths = data.getStringArrayListExtra(CameraGalleryActivity.RESULT_BATCH_PATHS);
            java.util.ArrayList<String> batchTypes = data.getStringArrayListExtra(CameraGalleryActivity.RESULT_BATCH_TYPES);
            if (batchPaths != null && !batchPaths.isEmpty()) {
                for (int i = 0; i < batchPaths.size(); i++) {
                    String bPath = batchPaths.get(i);
                    String bType = (batchTypes != null && i < batchTypes.size()) ? batchTypes.get(i) : "image";
                    if (bPath != null) {
                        File bFile = new File(bPath);
                        if (bFile.exists()) {
                            uploadAndSendMedia(bFile, bType, "");
                        }
                    }
                }
            }
        } else if (requestCode == REQ_VIEW_ONCE_MEDIA) {
            reloadMessagesFromLocalVault();
        } else if (requestCode == REQ_PICK_WALLPAPER && resultCode == RESULT_OK && data != null) {
            Uri selectedUri = data.getData();
            if (selectedUri != null) {
                File dir = new File(getFilesDir(), "app_wallpapers");
                if (!dir.exists()) dir.mkdirs();
                File destFile = new File(dir, "chat_wp_" + (chatId != null ? chatId : "custom") + "_" + System.currentTimeMillis() + ".jpg");
                Uri destUri = Uri.fromFile(destFile);

                UCrop.Options options = new UCrop.Options();
                options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
                options.setCompressionQuality(92);
                options.setToolbarColor(ContextCompat.getColor(this, R.color.colorSurface));
                options.setStatusBarColor(ContextCompat.getColor(this, R.color.colorSurface));
                options.setToolbarWidgetColor(ContextCompat.getColor(this, R.color.textPrimary));
                options.setToolbarTitle("Crop Chat Wallpaper");

                UCrop.of(selectedUri, destUri)
                        .withAspectRatio(9, 16)
                        .withMaxResultSize(1080, 1920)
                        .withOptions(options)
                        .start(this);
            }
        } else if (requestCode == UCrop.REQUEST_CROP && resultCode == RESULT_OK && data != null) {
            Uri resultUri = UCrop.getOutput(data);
            if (resultUri != null && chatId != null) {
                String wpStr = resultUri.toString();
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, wpStr);
                if (currentLocalChat != null) currentLocalChat.setWallpaperColor(wpStr);
                applyChatWallpaper();
                Toast.makeText(this, "Chat wallpaper applied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void uploadAndSendMedia(File rawFile, String mediaType) {
        uploadAndSendMedia(rawFile, mediaType, "");
    }

    private void uploadAndSendMedia(File rawFile, String mediaType, String caption) {
        if (rawFile == null || !rawFile.exists()) return;

        if (recipientPublicKey == null) {
            fetchRecipientPublicKey();
        }

        String messageId = UUID.randomUUID().toString();
        String initialText = (caption != null && !caption.trim().isEmpty()) ? caption.trim() : "";
        if ("document".equals(mediaType)) {
            initialText = rawFile.getName() + "|" + formatFileSize(rawFile.length());
        }

        Message message = new Message(
                messageId,
                currentUserId,
                recipientId,
                initialText,
                Timestamp.now(),
                mediaType
        );

        if (replyMessage != null) {
            message.setReplyToMessageId(replyMessage.getId());
            boolean isReplySenderYou = replyMessage.getSenderId().equals(currentUserId);
            message.setReplySenderName(isReplySenderYou ? "You" : (recipientName != null ? recipientName : "Other User"));
            runOnUiThread(this::cancelReply);
        }

        // 1. Pre-cache media locally for instant playback/rendering
        File decryptedFile = null;
        try {
            if ("image".equals(mediaType)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                Bitmap bitmap = BitmapFactory.decodeFile(rawFile.getAbsolutePath(), opts);
                if (bitmap != null) {
                    Bitmap thumbBitmap = Bitmap.createScaledBitmap(bitmap, 40, 40, true);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 40, baos);
                    byte[] thumbBytes = baos.toByteArray();
                    String base64Thumb = Base64.encodeToString(thumbBytes, Base64.DEFAULT);
                    message.setThumbnail(base64Thumb);
                    if (thumbBitmap != bitmap) {
                        thumbBitmap.recycle();
                    }
                    bitmap.recycle();
                }
                decryptedFile = new File(getExternalCacheDir(), "dec_" + messageId + ".jpg");
                copyFile(rawFile, decryptedFile);
            } else if ("video".equals(mediaType)) {
                try {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(rawFile.getAbsolutePath());
                    Bitmap frame = retriever.getFrameAtTime();
                    if (frame != null) {
                        Bitmap thumb = Bitmap.createScaledBitmap(frame, 60, 60, true);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        thumb.compress(Bitmap.CompressFormat.JPEG, 40, baos);
                        message.setThumbnail(Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT));
                        if (thumb != frame) {
                            thumb.recycle();
                        }
                        frame.recycle();
                    }
                    retriever.release();
                } catch (Exception ignored) {}
                decryptedFile = new File(getExternalCacheDir(), "dec_" + messageId + ".mp4");
                copyFile(rawFile, decryptedFile);
            } else if ("document".equals(mediaType)) {
                int dot = rawFile.getName().lastIndexOf(".");
                String ext = dot != -1 ? rawFile.getName().substring(dot) : "";
                decryptedFile = new File(getExternalCacheDir(), "dec_" + messageId + ext);
                copyFile(rawFile, decryptedFile);
            } else if ("voice".equals(mediaType)) {
                decryptedFile = new File(getExternalCacheDir(), "dec_" + messageId + ".m4a");
                copyFile(rawFile, decryptedFile);
            }
        } catch (Exception e) {
            Log.e("ChatActivity", "Failed to cache media locally", e);
        }

        File localFile = (decryptedFile != null && decryptedFile.exists()) ? decryptedFile : rawFile;
        message.setLocalFilePath(localFile.getAbsolutePath());
        message.setBlink(isBlinkModeActive);
        if ("voice".equals(mediaType)) {
            message.setVoiceFilter(activeVoiceFilter);
        }
        if (isBlinkModeActive) {
            isBlinkModeActive = false;
            messageEditText.setHint(R.string.type_message);
            updateBlinkModeChipUI();
        }

        if (currentVanishTimer > 0) {
            long expiresMillis = System.currentTimeMillis() + currentVanishTimer;
            message.setExpiresAt(new Timestamp(new Date(expiresMillis)));
        }

        // Route through VeilMessagesController (warm cache, outbox retry, and direct P2P streaming)
        com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(currentLocalChat, recipientId, message);
        reloadMessagesFromLocalVault();
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private void fetchRecipientPublicKey() {
        if (recipientId == null || recipientId.trim().isEmpty()) return;
        if (chatId != null && (chatId.startsWith("group_") || chatId.startsWith("channel_"))) return;
        if (recipientId.startsWith("group_") || recipientId.startsWith("channel_")) return;
        if (recipientUserListener != null) {
            recipientUserListener.remove();
        }
        recipientUserListener = db.collection("users").document(recipientId).addSnapshotListener(this, (documentSnapshot, error) -> {
            if (error != null || documentSnapshot == null || !documentSnapshot.exists()) return;
            User user = documentSnapshot.toObject(User.class);
            if (user != null) {
                cachedRecipientUser = user;
                if (user.getPublicKey() != null && !user.getPublicKey().isEmpty()) {
                    recipientPublicKey = user.getPublicKey();
                }
                String resolvedPeerName = user.getDisplayName();
                if (resolvedPeerName == null || resolvedPeerName.trim().isEmpty()) {
                    resolvedPeerName = user.getUsername();
                }
                if (resolvedPeerName == null || resolvedPeerName.trim().isEmpty()) {
                    resolvedPeerName = documentSnapshot.getString("displayName");
                }
                if (resolvedPeerName == null || resolvedPeerName.trim().isEmpty()) {
                    resolvedPeerName = documentSnapshot.getString("username");
                }
                boolean chatMetaUpdated = false;
                if (resolvedPeerName != null && !resolvedPeerName.trim().isEmpty()) {
                    recipientName = resolvedPeerName.trim();
                    if (currentLocalChat != null && !currentLocalChat.isGroup() && !recipientName.equals(currentLocalChat.getName())) {
                        currentLocalChat.setName(recipientName);
                        chatMetaUpdated = true;
                    }
                }
                if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
                    if (currentLocalChat != null && !user.getAvatar().equals(currentLocalChat.getAvatar())) {
                        currentLocalChat.setAvatar(user.getAvatar());
                        chatMetaUpdated = true;
                    }
                }
                if (chatMetaUpdated && currentLocalChat != null) {
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentLocalChat);
                }
                isRecipientOnline = user.isUserOnline();
                recipientLastSeenFormatted = user.getFormattedLastSeen();
                updatePresenceAndToolbarStatus();
                updateRecipientHeader();
            }
        });
    }

    private void ensureLocalChatExists() {
        if (chatId == null && recipientId != null) {
            chatId = com.m3.veil.webrtc.WebRtcManager.getChatId(currentUserId, recipientId);
        }
        if (chatId == null) return;
        currentLocalChat = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getChat(chatId);
        String myOwnName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
        if (currentLocalChat == null) {
            java.util.List<String> parts = new ArrayList<>();
            if (currentUserId != null) parts.add(currentUserId);
            if (recipientId != null) parts.add(recipientId);
            String initialName = (recipientName != null && !recipientName.isEmpty() && !recipientName.equals(myOwnName))
                    ? recipientName : "Anonymous Peer";
            currentLocalChat = new com.m3.veil.model.Chat(
                    chatId,
                    initialName,
                    "🔒 End-to-End Encrypted P2P Chat",
                    Timestamp.now(),
                    "avatar_1",
                    0,
                    false,
                    isRecipientOnline,
                    "direct",
                    parts
            );
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentLocalChat);
        } else if (!currentLocalChat.isGroup() && recipientName != null && !recipientName.isEmpty()
                && !recipientName.equals(myOwnName) && !recipientName.equals(currentLocalChat.getName())
                && !"Direct Chat".equals(recipientName) && !"Veil Contact".equals(recipientName)) {
            currentLocalChat.setName(recipientName);
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentLocalChat);
        }
        updateRecipientHeader();
    }

    private void loadLocalChatSettings() {
        if (chatId == null) return;
        currentLocalChat = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (currentLocalChat == null) return;

        currentVanishTimer = currentLocalChat.getVanishTimer();
        currentVanishMode = currentLocalChat.getVanishMode() != null ? currentLocalChat.getVanishMode() : "after_send";
        isChatBlocked = currentLocalChat.isBlocked();
        updateBlockedState(isChatBlocked, currentLocalChat.getBlockedBy());

        String myOwnName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
        String localChatName = currentLocalChat.getName();
        if (isGroupChatContext()) {
            if (localChatName != null && !localChatName.isEmpty()) {
                recipientName = localChatName;
            }
        } else {
            if (cachedRecipientUser != null) {
                String peerRealName = cachedRecipientUser.getDisplayName();
                if (peerRealName == null || peerRealName.isEmpty()) peerRealName = cachedRecipientUser.getUsername();
                if (peerRealName != null && !peerRealName.isEmpty()) {
                    recipientName = peerRealName;
                    if (!peerRealName.equals(localChatName)) {
                        currentLocalChat.setName(peerRealName);
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentLocalChat);
                    }
                }
            } else if (localChatName != null && !localChatName.isEmpty()
                    && (myOwnName == null || !localChatName.equals(myOwnName))
                    && !"Direct Chat".equals(localChatName)
                    && !"Veil Contact".equals(localChatName)
                    && !"Veil User".equals(localChatName)
                    && !"Anonymous Peer".equals(localChatName)) {
                recipientName = localChatName;
            }
        }

        updateRecipientHeader();
        applyChatWallpaper();
        updateChannelRoleComposerState();
        updatePresenceAndToolbarStatus();
    }

    private void applyChatWallpaper() {
        View chatRoot = findViewById(R.id.chat_root);
        ImageView doodleOverlay = findViewById(R.id.chatDoodleOverlay);
        if (chatRoot == null) return;

        android.content.SharedPreferences appPrefs = getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
        boolean amoled = appPrefs.getBoolean("amoled_pure_black", false);
        boolean doodleEnabled = appPrefs.getBoolean("doodle_pattern_enabled", false);
        int wpDim = appPrefs.getInt("wallpaper_dim", 0);

        String rawWallpaper = currentLocalChat != null ? currentLocalChat.getWallpaperColor() : null;
        String wpType = (rawWallpaper != null && !rawWallpaper.isEmpty()) ? "custom" : appPrefs.getString("wallpaper_type", "default");
        String wpValue = (rawWallpaper != null && !rawWallpaper.isEmpty()) ? rawWallpaper : appPrefs.getString("wallpaper_value", null);

        boolean isNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        try {
            if (wpValue != null && wpValue.startsWith("gradient:")) {
                String[] parts = wpValue.substring("gradient:".length()).split(":");
                int[] colors = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    int c = android.graphics.Color.parseColor(parts[i]);
                    if (wpDim > 0) {
                        float factor = Math.max(0.15f, 1.0f - (wpDim / 100f));
                        int r = (int) (android.graphics.Color.red(c) * factor);
                        int g = (int) (android.graphics.Color.green(c) * factor);
                        int b = (int) (android.graphics.Color.blue(c) * factor);
                        c = android.graphics.Color.rgb(r, g, b);
                    }
                    colors[i] = c;
                }
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colors);
                chatRoot.setBackground(gd);
            } else if (wpValue != null && ("image".equals(wpType) || wpValue.startsWith("file:") || wpValue.startsWith("content:") || wpValue.startsWith("/"))) {
                android.net.Uri uri = wpValue.startsWith("/") ? android.net.Uri.fromFile(new File(wpValue)) : android.net.Uri.parse(wpValue);
                android.graphics.drawable.Drawable d = null;
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null) {
                        d = android.graphics.drawable.Drawable.createFromStream(is, uri.toString());
                        is.close();
                    }
                } catch (Exception ex) {
                    d = android.graphics.drawable.Drawable.createFromPath(uri.getPath());
                }
                if (d != null) {
                    if (wpDim > 0) {
                        int alpha = Math.max(0, Math.min(230, (int) (wpDim * 2.3f)));
                        d.setColorFilter(android.graphics.Color.argb(alpha, 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP);
                    }
                    chatRoot.setBackground(d);
                } else {
                    int defaultBg = isNight ? (amoled ? android.graphics.Color.BLACK : ContextCompat.getColor(this, R.color.chat_wallpaper_bg))
                            : ContextCompat.getColor(this, R.color.chat_wallpaper_bg);
                    chatRoot.setBackgroundColor(defaultBg);
                }
            } else if (wpValue != null && wpValue.startsWith("#")) {
                int baseColor = android.graphics.Color.parseColor(wpValue);
                if (wpDim > 0) {
                    float factor = Math.max(0.15f, 1.0f - (wpDim / 100f));
                    int r = (int) (android.graphics.Color.red(baseColor) * factor);
                    int g = (int) (android.graphics.Color.green(baseColor) * factor);
                    int b = (int) (android.graphics.Color.blue(baseColor) * factor);
                    baseColor = android.graphics.Color.rgb(r, g, b);
                }
                chatRoot.setBackgroundColor(baseColor);
            } else {
                int defaultBg = isNight ? (amoled ? android.graphics.Color.BLACK : ContextCompat.getColor(this, R.color.chat_wallpaper_bg))
                        : ContextCompat.getColor(this, R.color.chat_wallpaper_bg);
                chatRoot.setBackgroundColor(defaultBg);
            }
        } catch (Exception e) {
            int fallback = isNight ? (amoled ? android.graphics.Color.BLACK : ContextCompat.getColor(this, R.color.chat_wallpaper_bg))
                    : ContextCompat.getColor(this, R.color.chat_wallpaper_bg);
            chatRoot.setBackgroundColor(fallback);
        }

        if (doodleOverlay != null) {
            doodleOverlay.setVisibility(doodleEnabled ? View.VISIBLE : View.GONE);
            if (doodleEnabled) {
                int tintColor = isNight ? android.graphics.Color.parseColor("#22FFFFFF") : android.graphics.Color.parseColor("#18000000");
                doodleOverlay.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private void updateChannelRoleComposerState() {
        LinearLayout bottomContainer = findViewById(R.id.bottomContainer);
        View composerMainRow = findViewById(R.id.composerMainRow);
        View quickFeaturesScrollView = findViewById(R.id.quickFeaturesScrollView);
        if (bottomContainer == null || composerMainRow == null) return;

        boolean isChannel = (currentLocalChat != null && currentLocalChat.isChannel())
                || (chatId != null && chatId.startsWith("channel_"));
        boolean isAdmin = currentLocalChat == null || currentUserId == null || currentLocalChat.isAdmin(currentUserId);

        if (isChannel && !isAdmin) {
            composerMainRow.setVisibility(View.GONE);
            if (quickFeaturesScrollView != null) quickFeaturesScrollView.setVisibility(View.GONE);
            if (layoutSmartReplies != null) layoutSmartReplies.setVisibility(View.GONE);

            if (channelSubscriberBar == null) {
                float dp = getResources().getDisplayMetrics().density;
                channelSubscriberBar = new LinearLayout(this);
                channelSubscriberBar.setOrientation(LinearLayout.HORIZONTAL);
                channelSubscriberBar.setGravity(Gravity.CENTER);
                int padH = (int) (14 * dp);
                int padV = (int) (10 * dp);
                channelSubscriberBar.setPadding(padH, padV, padH, padV);

                MaterialButton btnMuteToggle = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                boolean muted = currentLocalChat != null && currentLocalChat.isMuted();
                btnMuteToggle.setText(muted ? "🔔 Unmute Channel" : "🔕 Mute Channel");
                btnMuteToggle.setCornerRadius((int) (20 * dp));
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp1.rightMargin = (int) (6 * dp);
                btnMuteToggle.setLayoutParams(lp1);
                btnMuteToggle.setOnClickListener(v -> {
                    if (currentLocalChat != null) {
                        boolean nextMute = !currentLocalChat.isMuted();
                        currentLocalChat.setMuted(nextMute);
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentLocalChat);
                        btnMuteToggle.setText(nextMute ? "🔔 Unmute Channel" : "🔕 Mute Channel");
                        Toast.makeText(this, nextMute ? "Channel notifications muted" : "Channel notifications enabled", Toast.LENGTH_SHORT).show();
                    }
                });

                MaterialButton btnShareChannel = new MaterialButton(this);
                btnShareChannel.setText("🔗 Share Channel");
                btnShareChannel.setCornerRadius((int) (20 * dp));
                btnShareChannel.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
                btnShareChannel.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp2.leftMargin = (int) (6 * dp);
                btnShareChannel.setLayoutParams(lp2);
                btnShareChannel.setOnClickListener(v -> {
                    String shareLink = "veil://channel/" + chatId;
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, "Join \"" + (recipientName != null ? recipientName : "Broadcast Channel") + "\" on VEIL: " + shareLink);
                    startActivity(Intent.createChooser(shareIntent, "Share Broadcast Channel"));
                });

                channelSubscriberBar.addView(btnMuteToggle);
                channelSubscriberBar.addView(btnShareChannel);
                bottomContainer.addView(channelSubscriberBar);
            }
            channelSubscriberBar.setVisibility(View.VISIBLE);
        } else {
            composerMainRow.setVisibility(View.VISIBLE);
            if (quickFeaturesScrollView != null) quickFeaturesScrollView.setVisibility(View.VISIBLE);
            if (channelSubscriberBar != null) {
                channelSubscriberBar.setVisibility(View.GONE);
            }
        }
    }

    private void checkChatLockIfEnabled() {
        if (isChatPinUnlocked) {
            return;
        }
        if (currentLocalChat == null || currentLocalChat.getChatLockPin() == null || currentLocalChat.getChatLockPin().isEmpty()) {
            return;
        }
        final String expectedPin = currentLocalChat.getChatLockPin();
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setCancelable(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        layout.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🔒 Chat Locked");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        layout.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Enter your 4-digit PIN to decrypt & access this conversation:");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));
        layout.addView(tvSub);

        EditText pinInput = new EditText(this);
        pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("Enter 4-digit PIN");
        pinInput.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        pinInput.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        pinInput.setBackgroundResource(R.drawable.bg_search_input);
        int inPad = (int) (14 * getResources().getDisplayMetrics().density);
        pinInput.setPadding(inPad, inPad, inPad, inPad);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = (int) (20 * getResources().getDisplayMetrics().density);
        pinInput.setLayoutParams(inLp);
        layout.addView(pinInput);

        MaterialButton btnUnlock = new MaterialButton(this);
        btnUnlock.setText("Unlock Conversation");
        btnUnlock.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        btnUnlock.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
        btnUnlock.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        btnUnlock.setLayoutParams(btnLp);
        layout.addView(btnUnlock);

        MaterialButton btnExit = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnExit.setText("Exit");
        btnExit.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        btnExit.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(btnExit);

        btnUnlock.setOnClickListener(v -> {
            if (expectedPin.equals(pinInput.getText().toString().trim())) {
                isChatPinUnlocked = true;
                sheet.dismiss();
            } else {
                Toast.makeText(this, "Incorrect Chat PIN", Toast.LENGTH_SHORT).show();
            }
        });

        btnExit.setOnClickListener(v -> {
            sheet.dismiss();
            finish();
        });

        sheet.setContentView(layout);
        sheet.show();
    }

    private void updateBlockedState(boolean blocked, String blockedBy) {
        if (messageEditText == null || sendButton == null) return;
        if (blocked) {
            boolean blockedByMe = currentUserId != null && currentUserId.equals(blockedBy);
            messageEditText.setEnabled(false);
            messageEditText.setHint(blockedByMe ? "You blocked this user" : "You cannot reply to this conversation");
            sendButton.setEnabled(false);
            sendButton.setAlpha(0.3f);
        } else {
            messageEditText.setEnabled(true);
            messageEditText.setHint(R.string.type_message);
            sendButton.setEnabled(true);
            sendButton.setAlpha(1.0f);
        }
    }

    private boolean isGroupChatContext() {
        if (chatId != null && (chatId.startsWith("group_") || chatId.startsWith("channel_") || chatId.contains("group"))) {
            return true;
        }
        return currentLocalChat != null && (currentLocalChat.isGroup()
                || "group".equalsIgnoreCase(currentLocalChat.getType())
                || "channel".equalsIgnoreCase(currentLocalChat.getType()));
    }

    private void setMenuItemVisible(android.view.Menu menu, int itemId, boolean visible) {
        if (menu == null) return;
        android.view.MenuItem item = menu.findItem(itemId);
        if (item != null) item.setVisible(visible);
    }

    private void confirmLeaveGroupFromChat(boolean isChannel) {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_destructive_sheet, null);
        sheet.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = view.findViewById(R.id.tvDestructiveMessage);
        MaterialButton btnConfirm = view.findViewById(R.id.btnDestructiveConfirm);
        MaterialButton btnCancel = view.findViewById(R.id.btnDestructiveCancel);

        String label = isChannel ? "Channel" : "Group";
        if (tvTitle != null) tvTitle.setText("Leave " + label + "?");
        if (tvMsg != null) {
            tvMsg.setText("Are you sure you want to leave \"" + (recipientName != null ? recipientName : "this " + label.toLowerCase()) + "\"?");
        }
        if (btnConfirm != null) {
            btnConfirm.setText("Leave " + label);
            btnConfirm.setOnClickListener(v -> {
                sheet.dismiss();
                android.content.SharedPreferences prefs = getSharedPreferences("VeilPrefs", 0);
                java.util.Set<String> delSet = new java.util.HashSet<>(
                        prefs.getStringSet("deleted_group_ids", new java.util.HashSet<>()));
                delSet.add(chatId);
                prefs.edit().putStringSet("deleted_group_ids", delSet).apply();
                com.m3.veil.manager.VeilMessagesController.getInstance(this).removeDialogFromCache(chatId);
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).deleteChat(chatId);
                if (currentUserId != null && !currentUserId.isEmpty()) {
                    FirebaseFirestore.getInstance().collection("groups").document(chatId)
                            .update("participants", FieldValue.arrayRemove(currentUserId));
                }
                Toast.makeText(this, "You left the " + label.toLowerCase(), Toast.LENGTH_SHORT).show();
                finish();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }
        sheet.show();
    }

    private void startCall(String type) {
        boolean isGrp = isGroupChatContext();
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CALL_TYPE, type);
        intent.putExtra(CallActivity.EXTRA_CALL_ID, UUID.randomUUID().toString());
        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
        if (isGrp) {
            List<String> parts = currentLocalChat != null && currentLocalChat.getParticipants() != null
                    ? new ArrayList<>(currentLocalChat.getParticipants()) : new ArrayList<>();
            parts.remove(currentUserId);
            if (parts.isEmpty() && (recipientId == null || recipientId.isEmpty() || recipientId.equals(chatId))) {
                Toast.makeText(this, "No other group members available to call", Toast.LENGTH_SHORT).show();
                return;
            }
            String primaryPeer = !parts.isEmpty() ? parts.get(0) : recipientId;
            intent.putExtra(CallActivity.EXTRA_CONTACT_ID, primaryPeer);
            intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, recipientName != null ? recipientName : "Group Call");
            intent.putExtra(CallActivity.EXTRA_GROUP_ID, chatId);
            intent.putExtra(CallActivity.EXTRA_GROUP_NAME, recipientName != null ? recipientName : "Group Call");
            intent.putExtra(CallActivity.EXTRA_PARTICIPANT_IDS, parts.toArray(new String[0]));
        } else {
            if (recipientId == null || recipientId.isEmpty()) return;
            intent.putExtra(CallActivity.EXTRA_CONTACT_ID, recipientId);
            intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, recipientName);
        }
        startActivity(intent);
    }

    private void confirmClearChat() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_destructive_sheet, null);
        sheet.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = view.findViewById(R.id.tvDestructiveMessage);
        MaterialButton btnConfirm = view.findViewById(R.id.btnDestructiveConfirm);
        MaterialButton btnCancel = view.findViewById(R.id.btnDestructiveCancel);

        tvTitle.setText("Clear Chat Messages");
        tvMsg.setText("Are you sure you want to delete all messages in this chat from your local encrypted vault? This cannot be undone.");
        btnConfirm.setText("Clear Messages");

        btnConfirm.setOnClickListener(v -> {
            sheet.dismiss();
            messageList.clear();
            messagesAdapter.notifyDataSetChanged();
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).clearChatMessages(chatId);
            Toast.makeText(this, "Chat cleared from encrypted vault", Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> sheet.dismiss());

        sheet.show();
    }

    private void confirmBlockUser() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_destructive_sheet, null);
        sheet.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = view.findViewById(R.id.tvDestructiveMessage);
        MaterialButton btnConfirm = view.findViewById(R.id.btnDestructiveConfirm);
        MaterialButton btnCancel = view.findViewById(R.id.btnDestructiveCancel);

        tvTitle.setText(isChatBlocked ? "Unblock User" : "Block User");
        tvMsg.setText(isChatBlocked ? "Unblock this user to resume P2P encrypted messaging and calling?" : "Block this user from messaging or calling you?");
        btnConfirm.setText(isChatBlocked ? "Unblock User" : "Block User");

        btnConfirm.setOnClickListener(v -> {
            sheet.dismiss();
            isChatBlocked = !isChatBlocked;
            if (currentLocalChat != null) {
                currentLocalChat.setBlocked(isChatBlocked);
                currentLocalChat.setBlockedBy(isChatBlocked ? currentUserId : null);
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentLocalChat);
            }
            updateBlockedState(isChatBlocked, isChatBlocked ? currentUserId : null);
            Toast.makeText(this, isChatBlocked ? "User blocked" : "User unblocked", Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> sheet.dismiss());

        sheet.show();
    }

    private void updateVanishUI(boolean isRecipientTyping) {
        this.isRecipientTypingState = isRecipientTyping;
        updatePresenceAndToolbarStatus();
    }

    private void updatePresenceAndToolbarStatus() {
        if (messagesAdapter != null) {
            messagesAdapter.setTyping(isRecipientTypingState);
            if (isRecipientTypingState && messagesRecyclerView != null) {
                messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
            }
        }

        // Update Collapsible Pinned Message Banner
        View pinnedBanner = findViewById(R.id.pinnedMessageBanner);
        TextView tvPinnedBannerText = findViewById(R.id.tvPinnedBannerText);
        if (pinnedBanner != null && tvPinnedBannerText != null) {
            if (currentLocalChat != null && currentLocalChat.getPinnedMessageText() != null
                    && !currentLocalChat.getPinnedMessageText().isEmpty()) {
                pinnedBanner.setVisibility(View.VISIBLE);
                tvPinnedBannerText.setText(currentLocalChat.getPinnedMessageText());
            } else {
                pinnedBanner.setVisibility(View.GONE);
            }
        }

        TextView onlineStatus = findViewById(R.id.onlineStatus);
        View ivStatusDot = findViewById(R.id.ivStatusDot);
        View ivLockIcon = findViewById(R.id.ivLockIcon);
        if (onlineStatus == null) return;

        int trust = currentLocalChat != null ? currentLocalChat.getTrustScore() : 10;
        String subPrefix = (currentSubgroupTitle != null ? "#" + currentSubgroupTitle + " • " : "");

        if (isRecipientTypingState) {
            onlineStatus.setText(subPrefix + "typing...");
            onlineStatus.setTextColor(getResources().getColor(R.color.veil_accent));
            if (ivStatusDot != null) ivStatusDot.setVisibility(View.GONE);
            if (ivLockIcon != null) ivLockIcon.setVisibility(View.GONE);
        } else if (currentVanishTimer > 0) {
            String timeStr = "";
            if (currentVanishTimer == 10000) timeStr = "10s";
            else if (currentVanishTimer == 60000) timeStr = "1m";
            else if (currentVanishTimer == 3600000) timeStr = "1h";
            else if (currentVanishTimer == 86400000) timeStr = "24h";
            else if (currentVanishTimer == 604800000L) timeStr = "7d";
            else if (currentVanishTimer == 2592000000L) timeStr = "30d";

            String modeLabel = "after_read".equals(currentVanishMode) ? " (after read)" : " (after send)";
            onlineStatus.setText(subPrefix + "Vanish: " + timeStr + modeLabel + " • Trust " + trust + "%");
            onlineStatus.setTextColor(getResources().getColor(android.R.color.holo_red_light));
            if (ivStatusDot != null) ivStatusDot.setVisibility(View.GONE);
            if (ivLockIcon != null) ivLockIcon.setVisibility(View.VISIBLE);
        } else if (isP2PActive || isRecipientOnline) {
            String p2pLabel = isP2PActive ? "P2P Direct" : "online";
            onlineStatus.setText(subPrefix + p2pLabel + " • Trust " + trust + "%");
            onlineStatus.setTextColor(getResources().getColor(R.color.statusSuccess));
            if (ivStatusDot != null) ivStatusDot.setVisibility(View.VISIBLE);
            if (ivLockIcon != null) ivLockIcon.setVisibility(View.GONE);
        } else {
            String statusLabel = (recipientLastSeenFormatted != null && !recipientLastSeenFormatted.isEmpty())
                    ? recipientLastSeenFormatted
                    : "E2EE Vault";
            onlineStatus.setText(subPrefix + statusLabel + " • Trust " + trust + "%");
            onlineStatus.setTextColor(getResources().getColor(R.color.text_secondary));
            if (ivStatusDot != null) ivStatusDot.setVisibility(View.GONE);
            if (ivLockIcon != null) ivLockIcon.setVisibility(View.VISIBLE);
        }
    }

    private void setupRecyclerView() {
        messageList = new ArrayList<>();
        if (chatId != null) {
            List<Message> localEncrypted = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getMessages(chatId);
            if (localEncrypted != null && !localEncrypted.isEmpty()) {
                messageList.addAll(localEncrypted);
            }
        }
        messagesAdapter = new MessagesAdapter(messageList,
                this::showReactionDialog,
                this::showReactionDetails
        );
        messagesAdapter.setOnPollVoteClickListener(this::showPollVotesDetailsSheet);
        messagesAdapter.setOnPollOptionVoteListener(new MessagesAdapter.OnPollOptionVoteListener() {
            @Override
            public void onPollOptionClicked(Message message, int optionIndex) {
                handleInlinePollOptionVote(message, optionIndex);
            }

            @Override
            public void onViewPollVotesClicked(Message message) {
                showPollVotesDetailsSheet(message);
            }
        });
        messagesAdapter.setOnBlinkOpenClickListener(this::openBlinkViewOnceMessage);
        messagesAdapter.setOnCallMessageClickListener(this::handleCallMessageClick);
        messagesAdapter.setOnMediaDownloadClickListener(new MessagesAdapter.OnMediaDownloadClickListener() {
            @Override
            public void onMediaDownloadClick(Message message) {
                if (message == null || message.getId() == null) return;
                messagesAdapter.setMediaDownloadState(message.getId(), MessagesAdapter.STATE_DOWNLOADING, 0);
                com.m3.veil.manager.VeilMessagesController.getInstance(ChatActivity.this).downloadMedia(
                        recipientId,
                        message.getId(),
                        new com.m3.veil.webrtc.P2PFileTransferHelper.DownloadProgressListener() {
                            @Override
                            public void onProgress(String transferId, int percent) {
                                runOnUiThread(() -> messagesAdapter.setMediaDownloadState(transferId, MessagesAdapter.STATE_DOWNLOADING, percent));
                            }

                            @Override
                            public void onComplete(String transferId, File file) {
                                runOnUiThread(() -> {
                                    messagesAdapter.setMediaDownloadState(transferId, MessagesAdapter.STATE_DOWNLOADED, 100);
                                    reloadMessagesFromLocalVault();
                                });
                            }

                            @Override
                            public void onError(String transferId, String error) {
                                runOnUiThread(() -> {
                                    Toast.makeText(ChatActivity.this, "Download notice: " + error, Toast.LENGTH_SHORT).show();
                                    messagesAdapter.setMediaDownloadState(transferId, MessagesAdapter.STATE_RETRY, 0);
                                });
                            }
                        }
                );
            }

            @Override
            public void onMediaCancelClick(Message message) {
                if (message == null || message.getId() == null) return;
                com.m3.veil.manager.VeilMessagesController.getInstance(ChatActivity.this).cancelMediaDownload(recipientId, message.getId());
                messagesAdapter.setMediaDownloadState(message.getId(), MessagesAdapter.STATE_IDLE, 0);
            }

            @Override
            public void onMediaRetryClick(Message message) {
                onMediaDownloadClick(message);
            }
        });
        messagesAdapter.setOnDoubleTapReactListener(message -> {
            if (message == null || message.getId() == null || "deleted".equals(message.getType())) return;
            String reactorKey = com.m3.veil.manager.VeilFeatureManager.getInstance(this).isAnonymousReactionEnabled()
                    ? "anon_" + currentUserId.substring(0, Math.min(4, currentUserId.length()))
                    : currentUserId;
            String existing = message.getReactions() != null ? message.getReactions().get(reactorKey) : null;
            String newEmoji = "❤️".equals(existing) ? "" : "❤️";
            messagesRecyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            com.m3.veil.manager.VeilMessagesController.getInstance(this)
                    .sendReaction(chatId, message.getId(), reactorKey, newEmoji);
        });
        messagesAdapter.setOnReplyClickListener(messageId -> {
            for (int i = 0; i < messageList.size(); i++) {
                if (messageList.get(i).getId() != null && messageList.get(i).getId().equals(messageId)) {
                    int position = i + 1;
                    LinearLayoutManager layoutManager = (LinearLayoutManager) messagesRecyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        int firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition();
                        int lastVisible = layoutManager.findLastCompletelyVisibleItemPosition();

                        if (position >= firstVisible && position <= lastVisible) {
                            RecyclerView.ViewHolder holder = messagesRecyclerView.findViewHolderForAdapterPosition(position);
                            if (holder != null) blinkView(holder);
                        } else {
                            highlightPosition = position;
                            messagesRecyclerView.smoothScrollToPosition(position);
                        }
                    }
                    break;
                }
            }
        });
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        messagesRecyclerView.setHasFixedSize(true);
        messagesRecyclerView.setItemViewCacheSize(25);
        messagesRecyclerView.setRecycledViewPool(com.m3.veil.VeilApplication.getSharedRecycledViewPool());
        if (messagesRecyclerView.getItemAnimator() instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
            ((androidx.recyclerview.widget.SimpleItemAnimator) messagesRecyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        messagesRecyclerView.setAdapter(messagesAdapter);

        messagesRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && layoutScrollToBottom != null && messagesAdapter != null) {
                    int lastVisible = lm.findLastVisibleItemPosition();
                    int total = messagesAdapter.getItemCount();
                    if (total - 1 - lastVisible > 2) {
                        if (layoutScrollToBottom.getVisibility() != View.VISIBLE) {
                            layoutScrollToBottom.setVisibility(View.VISIBLE);
                            layoutScrollToBottom.setAlpha(0f);
                            layoutScrollToBottom.animate().alpha(1f).setDuration(180).start();
                        }
                    } else {
                        newMessagesWhileScrolledUp = 0;
                        TextView tvUnreadBadge = findViewById(R.id.tvScrollUnreadBadge);
                        if (tvUnreadBadge != null) tvUnreadBadge.setVisibility(View.GONE);
                        if (layoutScrollToBottom.getVisibility() == View.VISIBLE) {
                            layoutScrollToBottom.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                                layoutScrollToBottom.setVisibility(View.GONE);
                            }).start();
                        }
                    }
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && highlightPosition != -1) {
                    RecyclerView.ViewHolder holder = messagesRecyclerView.findViewHolderForAdapterPosition(highlightPosition);
                    if (holder != null) blinkView(holder);
                    highlightPosition = -1;
                }
            }
        });

        ItemTouchHelper.SimpleCallback touchHelperCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT) {
            private boolean hasTriggeredHaptic = false;
            private final android.graphics.Paint circlePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private android.graphics.drawable.Drawable replyIcon = null;

            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int pos = viewHolder.getAdapterPosition();
                if (pos <= 0 || pos > messageList.size()) return 0;
                Message msg = messageList.get(pos - 1);
                if ("deleted".equals(msg.getType())) return 0;
                return super.getSwipeDirs(recyclerView, viewHolder);
            }

            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                return 0.16f;
            }

            @Override
            public float getSwipeEscapeVelocity(float defaultValue) {
                return Float.MAX_VALUE;
            }

            @Override
            public float getSwipeVelocityThreshold(float defaultValue) {
                return Float.MAX_VALUE;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position > 0 && position <= messageList.size()) {
                    replyToMessage(messageList.get(position - 1));
                }
                hasTriggeredHaptic = false;
                messagesAdapter.notifyItemChanged(position);
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                    return;
                }

                float density = recyclerView.getContext().getResources().getDisplayMetrics().density;
                float maxDx = 72f * density;
                float triggerThreshold = 46f * density;

                float translationX = dX;
                if (translationX > maxDx) {
                    translationX = maxDx + (translationX - maxDx) * 0.15f;
                } else if (translationX < -maxDx) {
                    translationX = -maxDx + (translationX + maxDx) * 0.15f;
                }

                View bubble = null;
                if (viewHolder instanceof MessagesAdapter.SentMessageViewHolder) {
                    bubble = ((MessagesAdapter.SentMessageViewHolder) viewHolder).messageBubble;
                } else if (viewHolder instanceof MessagesAdapter.ReceivedMessageViewHolder) {
                    bubble = ((MessagesAdapter.ReceivedMessageViewHolder) viewHolder).messageBubble;
                }

                if (bubble != null) {
                    bubble.setTranslationX(translationX);
                } else {
                    viewHolder.itemView.setTranslationX(translationX);
                }

                float absDx = Math.abs(translationX);
                if (absDx > 0) {
                    float progress = Math.min(1.0f, absDx / triggerThreshold);
                    if (progress >= 1.0f && !hasTriggeredHaptic && isCurrentlyActive) {
                        viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        hasTriggeredHaptic = true;
                    } else if (progress < 0.6f && !isCurrentlyActive) {
                        hasTriggeredHaptic = false;
                    }

                    circlePaint.setColor(getResources().getColor(R.color.veil_accent));
                    circlePaint.setAlpha((int) (255 * progress));

                    float bubbleTop = bubble != null ? bubble.getTop() + viewHolder.itemView.getTop() : viewHolder.itemView.getTop();
                    float bubbleHeight = bubble != null ? bubble.getHeight() : viewHolder.itemView.getHeight();
                    float centerY = bubbleTop + (bubbleHeight / 2f);
                    float centerX = translationX > 0
                            ? Math.min(translationX / 2f, 28f * density)
                            : recyclerView.getWidth() - Math.min(absDx / 2f, 28f * density);
                    float circleRadius = (16f * density) * progress;

                    if (circleRadius > 2f) {
                        c.drawCircle(centerX, centerY, circleRadius, circlePaint);

                        if (replyIcon == null) {
                            replyIcon = androidx.core.content.ContextCompat.getDrawable(recyclerView.getContext(), R.drawable.ic_reply);
                        }
                        if (replyIcon != null) {
                            int iconHalfSize = (int) ((10f * density) * progress);
                            replyIcon.setBounds(
                                    (int) (centerX - iconHalfSize),
                                    (int) (centerY - iconHalfSize),
                                    (int) (centerX + iconHalfSize),
                                    (int) (centerY + iconHalfSize)
                            );
                            replyIcon.setAlpha((int) (255 * progress));
                            replyIcon.draw(c);
                        }
                    }
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                View bubble = null;
                if (viewHolder instanceof MessagesAdapter.SentMessageViewHolder) {
                    bubble = ((MessagesAdapter.SentMessageViewHolder) viewHolder).messageBubble;
                } else if (viewHolder instanceof MessagesAdapter.ReceivedMessageViewHolder) {
                    bubble = ((MessagesAdapter.ReceivedMessageViewHolder) viewHolder).messageBubble;
                }
                if (bubble != null) {
                    bubble.setTranslationX(0f);
                } else {
                    viewHolder.itemView.setTranslationX(0f);
                }
                hasTriggeredHaptic = false;
            }
        };
        new ItemTouchHelper(touchHelperCallback).attachToRecyclerView(messagesRecyclerView);
    }

    private void blinkView(RecyclerView.ViewHolder holder) {
        View viewToAnimate = null;
        if (holder instanceof MessagesAdapter.SentMessageViewHolder) {
            viewToAnimate = ((MessagesAdapter.SentMessageViewHolder) holder).messageBubble;
        } else if (holder instanceof MessagesAdapter.ReceivedMessageViewHolder) {
            viewToAnimate = ((MessagesAdapter.ReceivedMessageViewHolder) holder).messageBubble;
        }

        if (viewToAnimate == null) {
            viewToAnimate = holder.itemView;
        }
        if (viewToAnimate instanceof com.m3.veil.ui.views.ChatMessageCell) {
            ((com.m3.veil.ui.views.ChatMessageCell) viewToAnimate).triggerHighlightPulse();
        }

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(viewToAnimate, "scaleX", 1f, 1.05f, 1f, 1.05f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(viewToAnimate, "scaleY", 1f, 1.05f, 1f, 1.05f, 1f);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY);
        animatorSet.setDuration(800);
        animatorSet.start();
    }

    private void replyToMessage(Message message) {
        if ("deleted".equals(message.getType())) return;
        replyMessage = message;
        replyPreviewContainer.setVisibility(View.VISIBLE);

        boolean isSender = message.getSenderId().equals(currentUserId);
        replyPreviewName.setText(isSender ? "You" : (recipientName != null ? recipientName : "Other User"));

        String preview = message.getText();
        if ("voice".equals(message.getType())) {
            preview = "🎤 Voice message";
        } else if ("image".equals(message.getType())) {
            boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                    && !message.getText().startsWith("[image");
            preview = hasCaption ? "📷 " + message.getText().trim() : "📷 Photo";
        } else if ("video".equals(message.getType())) {
            boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                    && !message.getText().startsWith("[video");
            preview = hasCaption ? "🎥 " + message.getText().trim() : "🎥 Video";
        } else if ("document".equals(message.getType())) {
            String dName = message.getText();
            if (dName != null && dName.contains("|")) dName = dName.split("\\|", 2)[0];
            preview = "📄 " + (dName != null ? dName : "Document");
        } else if ("location".equals(message.getType())) {
            preview = "📍 Location";
        } else if ("contact".equals(message.getType())) {
            String cName = message.getText();
            if (cName != null && cName.contains("\n")) cName = cName.split("\n", 2)[0];
            preview = "👤 " + (cName != null ? cName : "Contact");
        }
        replyPreviewText.setText(preview != null ? preview : "");

        messageEditText.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(messageEditText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void cancelReply() {
        replyMessage = null;
        if (replyPreviewContainer != null) {
            replyPreviewContainer.setVisibility(View.GONE);
        }
    }

    private void showReactionDetails(Message message) {
        if (message.getReactions() == null || message.getReactions().isEmpty()) return;

        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_reactions, null);
        bottomSheetDialog.setContentView(view);

        TextView titleText = view.findViewById(R.id.reactionsCountText);
        LinearLayout listContainer = view.findViewById(R.id.reactionsListContainer);

        int count = message.getReactions().size();
        titleText.setText(count + (count == 1 ? " reaction" : " reactions"));

        for (Map.Entry<String, String> entry : message.getReactions().entrySet()) {
            View itemView = getLayoutInflater().inflate(R.layout.item_reaction_detail, listContainer, false);

            TextView userNameText = itemView.findViewById(R.id.userNameText);
            TextView actionSubtext = itemView.findViewById(R.id.actionSubtext);
            TextView emojiText = itemView.findViewById(R.id.emojiText);

            boolean isYou = entry.getKey().equals(currentUserId);
            String displayName = isYou ? "You" : (recipientName != null ? recipientName : "Other User");

            userNameText.setText(displayName);
            emojiText.setText(entry.getValue());

            if (isYou) {
                actionSubtext.setVisibility(View.VISIBLE);
                actionSubtext.setText("Tap to remove");
                itemView.setOnClickListener(v -> {
                    com.m3.veil.manager.VeilMessagesController.getInstance(this)
                            .sendReaction(chatId, message.getId(), currentUserId, "");
                    bottomSheetDialog.dismiss();
                });
            }

            listContainer.addView(itemView);
        }

        bottomSheetDialog.show();
    }

    private void setupSpeedChatRouletteHud() {
        if (chatId == null || !chatId.startsWith("roulette_")) return;

        View speedChatBanner = findViewById(R.id.speedChatBanner);
        TextView tvSpeedTimerText = findViewById(R.id.tvSpeedTimerText);
        com.google.android.material.button.MaterialButton btnMutualReveal = findViewById(R.id.btnMutualReveal);

        if (speedChatBanner == null || tvSpeedTimerText == null || btnMutualReveal == null) return;
        speedChatBanner.setVisibility(View.VISIBLE);

        btnMutualReveal.setOnClickListener(v -> {
            if (isSpeedMatchRevealed) return;
            userRequestedReveal = true;
            btnMutualReveal.setText("✓ Waiting for partner...");
            btnMutualReveal.setEnabled(false);

            com.m3.veil.model.Message controlMsg = new com.m3.veil.model.Message(
                    java.util.UUID.randomUUID().toString(),
                    currentUserId,
                    recipientId != null ? recipientId : "",
                    "[roulette_control:reveal_request]",
                    com.google.firebase.Timestamp.now(),
                    "control"
            );
            com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(currentLocalChat, recipientId, controlMsg);

            if (partnerRequestedReveal) {
                completeMutualReveal();
            } else {
                Toast.makeText(this, "Reveal requested! Waiting for partner to accept.", Toast.LENGTH_SHORT).show();
            }
        });

        speedChatTimer = new android.os.CountDownTimer(180000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (isSpeedMatchRevealed) {
                    cancel();
                    return;
                }
                long sec = millisUntilFinished / 1000;
                long m = sec / 60;
                long s = sec % 60;
                tvSpeedTimerText.setText(String.format(java.util.Locale.getDefault(), "Speed Match: %02d:%02d", m, s));
            }

            @Override
            public void onFinish() {
                if (isSpeedMatchRevealed || isFinishing()) return;
                Toast.makeText(ChatActivity.this, "⏱️ 3-minute Speed Match expired! Conversation purged.", Toast.LENGTH_LONG).show();
                finish();
            }
        }.start();
    }

    private void handleInboundRouletteReveal() {
        runOnUiThread(() -> {
            partnerRequestedReveal = true;
            if (userRequestedReveal) {
                completeMutualReveal();
            } else {
                com.google.android.material.button.MaterialButton btn = findViewById(R.id.btnMutualReveal);
                if (btn != null) {
                    btn.setText("🔥 Partner Wants to Reveal! Tap to Accept");
                    btn.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.statusSuccess));
                }
                Toast.makeText(this, "🎭 Partner wants to reveal identities!", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void completeMutualReveal() {
        isSpeedMatchRevealed = true;
        if (speedChatTimer != null) speedChatTimer.cancel();
        View speedChatBanner = findViewById(R.id.speedChatBanner);
        if (speedChatBanner != null) speedChatBanner.setVisibility(View.GONE);

        if (recipientId != null && !recipientId.isEmpty()) {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(recipientId)
                    .get()
                    .addOnSuccessListener(doc -> {
                        if (doc != null && doc.exists()) {
                            String realName = doc.getString("username");
                            if (realName != null) {
                                recipientName = realName;
                                TextView tvName = findViewById(R.id.recipientName);
                                if (tvName != null) tvName.setText(realName);
                            }
                        }
                    });
        }
        com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content),
                "🎉 Mutual Reveal Confirmed! Real profiles unmasked.",
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show();
    }

    private void showEmojiBottomSheet() {
        EmojiStickerBottomSheet sheet = new EmojiStickerBottomSheet();
        sheet.setOnEmojiSelectedListener(emoji -> {
            int start = Math.max(messageEditText.getSelectionStart(), 0);
            int end = Math.max(messageEditText.getSelectionEnd(), 0);
            messageEditText.getText().replace(Math.min(start, end), Math.max(start, end), emoji, 0, emoji.length());
        });
        sheet.show(getSupportFragmentManager(), "EmojiStickerBottomSheet");
    }

    private void showReactionDialog(Message message, View anchorView) {
        showMessageActionsBottomSheet(message);
    }

    private void showMessageActionsBottomSheet(Message message) {
        if ("deleted".equals(message.getType()) || message.getId() == null) return;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_message_actions, null);
        dialog.setContentView(view);

        // Reactions setup
        TextView rThumbsUp = view.findViewById(R.id.reactionThumbsUp);
        TextView rHeart = view.findViewById(R.id.reactionHeart);
        TextView rLaugh = view.findViewById(R.id.reactionLaugh);
        TextView rWow = view.findViewById(R.id.reactionWow);
        TextView rSad = view.findViewById(R.id.reactionSad);
        TextView rPray = view.findViewById(R.id.reactionPray);

        View.OnClickListener reactionClick = v -> {
            String emoji = ((TextView) v).getText().toString();
            String reactorKey = com.m3.veil.manager.VeilFeatureManager.getInstance(this).isAnonymousReactionEnabled()
                    ? "anon_" + currentUserId.substring(0, Math.min(4, currentUserId.length()))
                    : currentUserId;
            String existing = message.getReactions() != null ? message.getReactions().get(reactorKey) : null;
            String newEmoji = emoji.equals(existing) ? "" : emoji;

            com.m3.veil.manager.VeilMessagesController.getInstance(this)
                    .sendReaction(chatId, message.getId(), reactorKey, newEmoji);
            dialog.dismiss();
        };

        rThumbsUp.setOnClickListener(reactionClick);
        rHeart.setOnClickListener(reactionClick);
        rLaugh.setOnClickListener(reactionClick);
        rWow.setOnClickListener(reactionClick);
        rSad.setOnClickListener(reactionClick);
        rPray.setOnClickListener(reactionClick);

        // Action: Reply
        view.findViewById(R.id.actionReply).setOnClickListener(v -> {
            dialog.dismiss();
            replyToMessage(message);
        });

        // Action: Comments / Discussion (Telegram-style channel post threads)
        View actionComments = view.findViewById(R.id.actionComments);
        if (actionComments != null) {
            boolean isChannelMode = (currentLocalChat != null && "channel".equalsIgnoreCase(currentLocalChat.getType())) || (chatId != null && chatId.startsWith("channel_"));
            actionComments.setVisibility(isChannelMode ? View.VISIBLE : View.GONE);
            actionComments.setOnClickListener(v -> {
                dialog.dismiss();
                String postText = message.getText() != null ? message.getText() : "Media Post";
                String author = message.getSenderName() != null ? message.getSenderName() : (recipientName != null ? recipientName : "Channel Post");
                com.m3.veil.ui.chat.sheets.ChannelCommentsBottomSheet sheet =
                        com.m3.veil.ui.chat.sheets.ChannelCommentsBottomSheet.newInstance(chatId, message.getId(), postText, author);
                sheet.show(getSupportFragmentManager(), "ChannelCommentsBottomSheet");
            });
        }

        // Action: Copy
        View actionCopy = view.findViewById(R.id.actionCopy);
        if (message.getText() != null && !message.getText().isEmpty()) {
            actionCopy.setVisibility(View.VISIBLE);
            actionCopy.setOnClickListener(v -> {
                dialog.dismiss();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("Veil Message", message.getText());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Message copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            actionCopy.setVisibility(View.GONE);
        }

        // Action: Forward
        view.findViewById(R.id.actionForward).setOnClickListener(v -> {
            dialog.dismiss();
            showForwardMessageSheet(message);
        });

        // Action: Info
        view.findViewById(R.id.actionInfo).setOnClickListener(v -> {
            dialog.dismiss();
            showMessageInfoDialog(message);
        });

        // Action: Edit (within time window for own text messages)
        View actionEdit = view.findViewById(R.id.actionEdit);
        if (actionEdit != null) {
            boolean canEdit = message.getSenderId() != null && message.getSenderId().equals(currentUserId)
                    && "text".equals(message.getType()) && !message.isBlink();
            actionEdit.setVisibility(canEdit ? View.VISIBLE : View.GONE);
            actionEdit.setOnClickListener(v -> {
                dialog.dismiss();
                showEditMessageDialog(message);
            });
        }

        // Action: Pin / Unpin
        View actionPin = view.findViewById(R.id.actionPin);
        if (actionPin != null) {
            actionPin.setOnClickListener(v -> {
                dialog.dismiss();
                boolean currentlyPinned = currentLocalChat != null && message.getId().equals(currentLocalChat.getPinnedMessageId());
                if (currentlyPinned) {
                    com.m3.veil.manager.VeilMessagesController.getInstance(this).unpinMessage(chatId, recipientId);
                    Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show();
                } else {
                    String pinPreview = message.getText() != null ? message.getText() : "[" + message.getType() + "]";
                    if (pinPreview.length() > 32) pinPreview = pinPreview.substring(0, 32) + "…";
                    com.m3.veil.manager.VeilMessagesController.getInstance(this).pinMessage(chatId, recipientId, message.getId(), pinPreview);
                    Toast.makeText(this, "📌 Message pinned to chat header", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Action: Translate, Star & Smart Reply
        View actionTranslate = view.findViewById(R.id.actionTranslate);
        if (actionTranslate != null) {
            actionTranslate.setOnClickListener(v -> {
                dialog.dismiss();
                showTranslateAndSmartReplyDialog(message);
            });
        }

        // Action: Delete
        view.findViewById(R.id.actionDelete).setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteMessage(message);
        });

        dialog.show();
    }

    private void showMessageInfoDialog(Message message) {
        StringBuilder info = new StringBuilder();
        if (message.getTimestamp() != null) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale.getDefault());
            info.append("Sent: ").append(sdf.format(message.getTimestamp().toDate())).append("\n\n");
        }
        info.append("Type: ").append(message.getType() != null ? message.getType() : "text").append("\n\n");
        boolean isMine = message.getSenderId().equals(currentUserId);
        info.append("From: ").append(isMine ? "You" : (recipientName != null ? recipientName : "Other User")).append("\n\n");
        info.append("Status: ");
        if (message.isRead()) {
            info.append("Read (Seen)");
        } else if (message.isDelivered()) {
            info.append("Delivered");
        } else if (message.isPending()) {
            info.append("Pending offline delivery");
        } else {
            info.append("Sent");
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_message_details_sheet);

        TextView tvSent = sheet.findViewById(R.id.tvDetailsSent);
        TextView tvDelivered = sheet.findViewById(R.id.tvDetailsDelivered);
        TextView tvRead = sheet.findViewById(R.id.tvDetailsRead);
        TextView tvId = sheet.findViewById(R.id.tvDetailsMessageId);
        View btnClose = sheet.findViewById(R.id.btnCloseDetails);

        if (tvSent != null) {
            String sentStr = "Sent";
            if (message.getTimestamp() != null) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault());
                sentStr = sdf.format(message.getTimestamp().toDate());
            }
            tvSent.setText(sentStr);
        }
        if (tvDelivered != null) {
            tvDelivered.setText(message.isDelivered() ? "Delivered to device" : (message.isPending() ? "Pending mesh sync" : "Sent to server"));
        }
        if (tvRead != null) {
            if (message.isRead()) {
                tvRead.setText("Read (Seen by recipient)");
            } else {
                tvRead.setText("Not yet read");
                tvRead.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            }
        }
        if (tvId != null) {
            tvId.setText(message.getId());
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> sheet.dismiss());
        }
        sheet.show();
    }

    private void showForwardMessageSheet(Message message) {
        if (message == null || isFinishing()) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.ModernBottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_forward_message_sheet, null);
        sheet.setContentView(view);

        View cardShareExternal = view.findViewById(R.id.cardShareExternal);
        LinearLayout forwardChatsContainer = view.findViewById(R.id.forwardChatsContainer);
        TextView forwardEmptyState = view.findViewById(R.id.forwardEmptyState);

        if (cardShareExternal != null) {
            cardShareExternal.setOnClickListener(v -> {
                sheet.dismiss();
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                if (message.getLocalFilePath() != null) {
                    File f = new File(message.getLocalFilePath());
                    if (f.exists()) {
                        Uri contentUri = FileProvider.getUriForFile(this,
                                getPackageName() + ".provider", f);
                        shareIntent.setType("image/*".equals(message.getType()) ? "image/*" : "*/*");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, message.getText() != null ? message.getText() : "");
                    }
                } else {
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message.getText() != null ? message.getText() : "");
                }
                startActivity(Intent.createChooser(shareIntent, "Share message"));
            });
        }

        List<Chat> allChats = EncryptedChatDatabase.getInstance(this).getAllChats(false);
        List<Chat> validChats = new ArrayList<>();
        if (allChats != null) {
            for (Chat c : allChats) {
                if (c != null && !c.getId().equals(chatId) && !c.isArchived() && !c.isSecret()) {
                    validChats.add(c);
                }
            }
        }

        if (forwardChatsContainer != null && forwardEmptyState != null) {
            forwardChatsContainer.removeAllViews();

            // Telegram-style Anonymous Forward Toggle ("Hide Sender Name")
            final boolean[] hideSenderTag = {true};
            android.widget.CheckBox cbAnonForward = new android.widget.CheckBox(this);
            cbAnonForward.setText("🕵️ Forward Anonymously (Hide Original Sender Tag)");
            cbAnonForward.setChecked(true);
            cbAnonForward.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            cbAnonForward.setTextSize(13.5f);
            cbAnonForward.setOnCheckedChangeListener((btn, checked) -> hideSenderTag[0] = checked);
            forwardChatsContainer.addView(cbAnonForward);

            if (validChats.isEmpty()) {
                forwardEmptyState.setVisibility(View.VISIBLE);
            } else {
                forwardEmptyState.setVisibility(View.GONE);
                LayoutInflater inflater = LayoutInflater.from(this);

                for (Chat targetChat : validChats) {
                    View row = inflater.inflate(R.layout.item_chat, forwardChatsContainer, false);
                    ImageView avatar = row.findViewById(R.id.avatar);
                    TextView name = row.findViewById(R.id.name);
                    TextView lastMessage = row.findViewById(R.id.lastMessage);
                    TextView time = row.findViewById(R.id.time);
                    View onlineIndicator = row.findViewById(R.id.onlineIndicator);
                    View unreadCount = row.findViewById(R.id.unreadCount);

                    if (name != null) name.setText(targetChat.getName() != null ? targetChat.getName() : "@Anonymous");
                    if (lastMessage != null) lastMessage.setText(targetChat.getLastMessage() != null ? targetChat.getLastMessage() : "Tap to forward here");
                    if (time != null) time.setText("");
                    if (onlineIndicator != null) onlineIndicator.setVisibility(View.GONE);
                    if (unreadCount != null) unreadCount.setVisibility(View.GONE);

                    if (avatar != null) {
                        avatar.setImageResource(targetChat.isGroup() ? R.drawable.ic_group : R.drawable.ic_person);
                    }

                    row.setOnClickListener(vRow -> {
                        sheet.dismiss();
                        forwardMessageToChat(message, targetChat, hideSenderTag[0]);
                    });

                    forwardChatsContainer.addView(row);
                }
            }
        }

        sheet.show();
    }

    private void forwardMessageToChat(Message originalMessage, Chat targetChat) {
        forwardMessageToChat(originalMessage, targetChat, true);
    }

    private void forwardMessageToChat(Message originalMessage, Chat targetChat, boolean anonymousForward) {
        if (originalMessage == null || targetChat == null) return;
        String newMsgId = UUID.randomUUID().toString();
        String targetRecipientId = "";
        if (targetChat.getParticipants() != null) {
            for (String p : targetChat.getParticipants()) {
                if (p != null && !p.equals(currentUserId)) {
                    targetRecipientId = p;
                    break;
                }
            }
        }
        if (targetRecipientId.isEmpty()) {
            targetRecipientId = targetChat.getId();
        }

        String fwdText = originalMessage.getText() != null ? originalMessage.getText() : "";
        if (!anonymousForward && !"image".equals(originalMessage.getType()) && !"video".equals(originalMessage.getType())) {
            String origSender = originalMessage.getSenderName() != null ? originalMessage.getSenderName() : "Peer";
            fwdText = "↪️ Forwarded from " + origSender + ":\n" + fwdText;
        }
        Message fwdMessage = new Message(
                newMsgId,
                currentUserId,
                targetRecipientId,
                fwdText,
                Timestamp.now(),
                originalMessage.getType() != null ? originalMessage.getType() : "text"
        );
        fwdMessage.setLocalFilePath(originalMessage.getLocalFilePath());
        fwdMessage.setThumbnail(originalMessage.getThumbnail());

        // Route through VeilMessagesController (optimistic UI + warm cache + outbox + direct P2P delivery)
        com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(targetChat, targetRecipientId, fwdMessage);

        Toast.makeText(this, (anonymousForward ? "🕵️ Anonymously forwarded to " : "Forwarded to ") + targetChat.getName(), Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteMessage(Message message) {
        boolean isMine = message.getSenderId().equals(currentUserId);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_message_delete_sheet);

        View cardMe = sheet.findViewById(R.id.cardDeleteForMe);
        View cardEveryone = sheet.findViewById(R.id.cardDeleteForEveryone);
        View btnCancel = sheet.findViewById(R.id.btnCancelDelete);

        if (cardEveryone != null) {
            cardEveryone.setVisibility(isMine ? View.VISIBLE : View.GONE);
            cardEveryone.setOnClickListener(v -> {
                sheet.dismiss();
                deleteMessageForEveryone(message);
            });
        }

        if (cardMe != null) {
            cardMe.setOnClickListener(v -> {
                sheet.dismiss();
                deleteMessageForMe(message);
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }

        sheet.show();
    }

    private void deleteMessageForMe(Message message) {
        com.m3.veil.manager.VeilMessagesController.getInstance(this).deleteMessage(chatId, message.getId(), false);
        messageList.remove(message);
        messagesAdapter.notifyDataSetChanged();
        Toast.makeText(this, "Message deleted for you", Toast.LENGTH_SHORT).show();
    }

    private void deleteMessageForEveryone(Message message) {
        com.m3.veil.manager.VeilMessagesController.getInstance(this).deleteMessage(chatId, message.getId(), true);
        reloadMessagesFromLocalVault();
        Toast.makeText(this, "Message deleted for everyone via P2P", Toast.LENGTH_SHORT).show();
    }

    private long sendButtonDownTimestamp = 0L;
    private boolean sendLongPressTriggered = false;
    private final Runnable sendLongPressRunnable = () -> {
        String text = messageEditText != null && messageEditText.getText() != null
                ? messageEditText.getText().toString().trim() : "";
        if (!text.isEmpty()) {
            sendLongPressTriggered = true;
            if (sendButton != null) {
                sendButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
            showSendOptionsSheet(text);
        }
    };

    private void showSendOptionsSheet(String textToSend) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🚀 Advanced Send Options");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (14 * dp));
        root.addView(title);

        MaterialButton btnSilent = new MaterialButton(this);
        btnSilent.setText("🔕 Send Without Sound (Silent Delivery)");
        btnSilent.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * dp);
        btnSilent.setLayoutParams(lp);
        btnSilent.setOnClickListener(v -> {
            sheet.dismiss();
            messageEditText.setText("🔕 " + textToSend);
            sendMessage();
            Toast.makeText(this, "🔕 Sent silently without notification sound", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSilent);

        MaterialButton btnSchedule = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnSchedule.setText("⏰ Schedule Message for Later");
        btnSchedule.setCornerRadius((int) (12 * dp));
        btnSchedule.setLayoutParams(lp);
        btnSchedule.setOnClickListener(v -> {
            sheet.dismiss();
            showScheduleMessageDialog();
        });
        root.addView(btnSchedule);

        MaterialButton btnBlinkOnce = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnBlinkOnce.setText("⚡ Send as 1x View-Once Blink");
        btnBlinkOnce.setCornerRadius((int) (12 * dp));
        btnBlinkOnce.setLayoutParams(lp);
        btnBlinkOnce.setOnClickListener(v -> {
            sheet.dismiss();
            isBlinkModeActive = true;
            sendMessage();
        });
        root.addView(btnBlinkOnce);

        MaterialButton btnSpoiler = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnSpoiler.setText("🕵️ Send Hidden Behind Spoiler (||...||)");
        btnSpoiler.setCornerRadius((int) (12 * dp));
        btnSpoiler.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnSpoiler.setOnClickListener(v -> {
            sheet.dismiss();
            messageEditText.setText("||" + textToSend + "||");
            sendMessage();
        });
        root.addView(btnSpoiler);

        sheet.setContentView(root);
        sheet.show();
    }

    private void setupClickListeners() {
        sendButton.setOnTouchListener((v, event) -> {
            String text = messageEditText.getText().toString().trim();
            if (!text.isEmpty()) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    sendButtonDownTimestamp = System.currentTimeMillis();
                    sendLongPressTriggered = false;
                    typingHandler.postDelayed(sendLongPressRunnable, 480);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    typingHandler.removeCallbacks(sendLongPressRunnable);
                    if (!sendLongPressTriggered) {
                        sendMessage();
                    }
                } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                    typingHandler.removeCallbacks(sendLongPressRunnable);
                }
                return true;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!isRecording) {
                        if (ContextCompat.checkSelfPermission(ChatActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(ChatActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQ_AUDIO);
                            return true;
                        }

                        startRecordingUI();
                        startX = event.getRawX();
                        startY = event.getRawY();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isRecording && !isRecordingLocked) {
                        float currentX = event.getRawX();
                        float currentY = event.getRawY();

                        float deltaX = startX - currentX;
                        if (deltaX > 0) {
                            slideToCancelText.setTranslationX(-deltaX);
                            if (deltaX > 300) {
                                cancelRecordingUI();
                                return true;
                            }
                        }

                        float deltaY = startY - currentY;
                        if (deltaY > 0) {
                            recordLockIcon.setTranslationY(-deltaY);
                            if (deltaY > 250) {
                                lockRecordingUI();
                            }
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isRecording && !isRecordingLocked) {
                        long duration = System.currentTimeMillis() - recordingStartTime;
                        if (duration < 300) {
                            lockRecordingUI();
                        } else {
                            stopRecordingAndSendUI();
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    private void startRecordingUI() {
        isRecording = true;
        isRecordingLocked = false;
        isRecordingPaused = false;
        totalRecordingDuration = 0;

        if (pauseRecordingIcon != null && pauseRecordingText != null) {
            pauseRecordingIcon.setImageResource(R.drawable.ic_pause);
            pauseRecordingText.setText("Pause");
        }

        String filePath = getExternalCacheDir().getAbsolutePath() + "/voice_note_" + System.currentTimeMillis() + ".m4a";
        audioRecorderHelper.startRecording(filePath);

        inputContainer.setVisibility(View.INVISIBLE);
        lockedRecordingContainer.setVisibility(View.GONE);
        recordingContainer.setVisibility(View.VISIBLE);
        recordLockIcon.setVisibility(View.VISIBLE);

        slideToCancelText.setTranslationX(0);
        recordLockIcon.setTranslationY(0);

        sendButton.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start();

        recordingStartTime = System.currentTimeMillis();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long millis = System.currentTimeMillis() - recordingStartTime;
                int seconds = (int) (millis / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;
                String timeStr = String.format("%d:%02d", minutes, seconds);

                recordTimerText.setText(timeStr);
                lockedRecordTimerText.setText(timeStr);

                recordBlinkDot.setVisibility((millis % 1000 > 500) ? View.INVISIBLE : View.VISIBLE);

                if (audioRecorderHelper != null) {
                    lockedWaveformView.addAmplitude(audioRecorderHelper.getMaxAmplitude());
                }

                if (isRecording) {
                    timerHandler.postDelayed(this, 100);
                }
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void cancelRecordingUI() {
        isRecording = false;
        audioRecorderHelper.stopRecording();
        resetRecordingUI();
    }

    private void lockRecordingUI() {
        isRecordingLocked = true;
        recordLockIcon.setVisibility(View.GONE);
        recordingContainer.setVisibility(View.GONE);
        sendButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start();

        lockedRecordingContainer.setVisibility(View.VISIBLE);
    }

    private void togglePauseRecording() {
        if (!isRecording) return;

        if (isRecordingPaused) {
            if (audioRecorderHelper != null) {
                audioRecorderHelper.resumeRecording();
            }
            isRecordingPaused = false;
            recordingStartTime = System.currentTimeMillis() - totalRecordingDuration;

            pauseRecordingIcon.setImageResource(R.drawable.ic_pause);
            pauseRecordingText.setText("Pause");

            timerHandler.postDelayed(timerRunnable, 100);
        } else {
            if (audioRecorderHelper != null) {
                audioRecorderHelper.pauseRecording();
            }
            isRecordingPaused = true;
            totalRecordingDuration = System.currentTimeMillis() - recordingStartTime;

            pauseRecordingIcon.setImageResource(R.drawable.ic_mic);
            pauseRecordingText.setText("Resume");

            timerHandler.removeCallbacks(timerRunnable);
            recordBlinkDot.setVisibility(View.INVISIBLE);
        }
    }

    private void stopRecordingAndSendUI() {
        isRecording = false;
        File audioFile = audioRecorderHelper.stopRecording();
        resetRecordingUI();

        if (audioFile != null && audioFile.exists()) {
            uploadAndSendMedia(audioFile, "voice");
        }
    }

    private void resetRecordingUI() {
        timerHandler.removeCallbacks(timerRunnable);
        if (lockedWaveformView != null) {
            lockedWaveformView.clear();
        }
        recordingContainer.setVisibility(View.GONE);
        lockedRecordingContainer.setVisibility(View.GONE);
        inputContainer.setVisibility(View.VISIBLE);
        recordLockIcon.setVisibility(View.GONE);
        sendButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
    }

    private void sendMessage() {
        String text = messageEditText.getText().toString().trim();
        if (text.isEmpty()) return;

        String messageId = UUID.randomUUID().toString();
        Message message = new Message(
                messageId,
                currentUserId,
                recipientId,
                text,
                Timestamp.now(),
                "text"
        );
        message.setBlink(isBlinkModeActive);
        message.setSenderName(com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName());

        // Anonymous Channel Admin System (#82) - Blind rotating alias
        if (currentLocalChat != null && ("channel".equalsIgnoreCase(currentLocalChat.getType()) || (chatId != null && chatId.startsWith("channel_")))) {
            com.m3.veil.database.EncryptedChatDatabase.ChannelAdminKey cak =
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getChannelAdminKey(chatId);
            if (cak == null) {
                cak = new com.m3.veil.database.EncryptedChatDatabase.ChannelAdminKey(
                        chatId,
                        "Channel Admin (Epoch #1)",
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString()
                );
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveChannelAdminKey(cak);
            }
            message.setSenderName(cak.adminAlias);
            message.setSenderId(cak.adminAlias);
        }

        // Scoped Secret Sub-Group / Topic (#71) (sendMessage)
        if (currentSubgroupId != null && currentSubgroupTitle != null) {
            message.setContent("[#" + currentSubgroupTitle + "] " + text);
        }

        if (currentVanishTimer > 0) {
            long expiresMillis = System.currentTimeMillis() + currentVanishTimer;
            message.setExpiresAt(new Timestamp(new Date(expiresMillis)));
        }

        if (replyMessage != null) {
            message.setReplyToMessageId(replyMessage.getId());
            boolean isReplySenderYou = replyMessage.getSenderId().equals(currentUserId);
            message.setReplySenderName(isReplySenderYou ? "You" : (recipientName != null ? recipientName : "Other User"));
            cancelReply();
        }

        messageEditText.setText("");
        if (isBlinkModeActive) {
            isBlinkModeActive = false;
            messageEditText.setHint(R.string.type_message);
            updateBlinkModeChipUI();
        }

        // Route through application-wide VeilMessagesController (optimistic UI + ratcheted encryption + outbox)
        com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(currentLocalChat, recipientId, message);
        reloadMessagesFromLocalVault();
    }

    private void listenForMessages() {
        if (chatId == null) return;
        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).markAllAsRead(chatId, currentUserId);
        reloadMessagesFromLocalVault();
        sendReadReceiptsForUnreadMessages();

        if (isGroupChatContext() || chatId.startsWith("lounge_")) {
            attachGroupRealtimeListeners();
        } else {
            attachDirectRealtimeListeners();
        }
    }

    private void attachGroupRealtimeListeners() {
        if (chatId == null || db == null) return;
        if (currentLocalChat != null && currentLocalChat.getParticipants() != null) {
            for (String pId : currentLocalChat.getParticipants()) {
                if (pId != null && !pId.equals(currentUserId)) {
                    com.m3.veil.webrtc.WebRtcManager.getInstance(this).initiateConnection(pId);
                }
            }
        }
        if (groupDocListener != null) groupDocListener.remove();
        groupDocListener = db.collection("groups").document(chatId)
                .addSnapshotListener(this, (doc, error) -> {
                    if (error != null || doc == null || !doc.exists()) return;
                    String gName = doc.getString("groupName");
                    String adminId = doc.getString("adminId");
                    String description = doc.getString("description");
                    String chatType = doc.getString("type");
                    @SuppressWarnings("unchecked")
                    List<String> parts = (List<String>) doc.get("participants");
                    @SuppressWarnings("unchecked")
                    List<String> adminIds = (List<String>) doc.get("adminIds");

                    if (currentLocalChat != null) {
                        if (gName != null && !gName.isEmpty()) currentLocalChat.setName(gName);
                        if (adminId != null) currentLocalChat.setAdminId(adminId);
                        if (adminIds != null) currentLocalChat.setAdminIds(adminIds);
                        if (description != null) currentLocalChat.setDescription(description);
                        if (chatType != null) currentLocalChat.setType(chatType);
                        if (parts != null) {
                            currentLocalChat.setParticipants(parts);
                            for (String pId : parts) {
                                if (pId != null && !pId.equals(currentUserId)) {
                                    com.m3.veil.webrtc.WebRtcManager.getInstance(ChatActivity.this).initiateConnection(pId);
                                }
                            }
                        }
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).saveOrUpdateChat(currentLocalChat);
                        loadLocalChatSettings();
                    }
                });

        if (groupMessagesListener != null) groupMessagesListener.remove();
        groupMessagesListener = db.collection("groups").document(chatId)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener(this, (snapshots, error) -> {
                    if (error != null || snapshots == null) return;
                    boolean changed = false;
                    for (com.google.firebase.firestore.DocumentSnapshot mDoc : snapshots.getDocuments()) {
                        try {
                            String mId = mDoc.getString("id");
                            if (mId == null) mId = mDoc.getId();
                            String mType = mDoc.getString("type");
                            String mText = mDoc.getString("text");
                            if (mText != null && mText.startsWith("ENC:v1:")) {
                                mText = EncryptionManager.decryptGroupPayload(chatId, mText);
                            }
                            String sId = mDoc.getString("senderId");
                            String sName = mDoc.getString("senderName");
                            String pollJson = mDoc.getString("pollJson");
                            if (pollJson != null && pollJson.startsWith("ENC:v1:")) {
                                pollJson = EncryptionManager.decryptGroupPayload(chatId, pollJson);
                            }
                            boolean isEdited = VeilFirestoreUtils.getSafeBoolean(mDoc, "edited", false);
                            Timestamp mTs = VeilFirestoreUtils.getSafeTimestamp(mDoc, "timestamp", Timestamp.now());

                            Message existing = com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).getMessageById(mId);
                            if (existing == null) {
                                Message m = new Message(
                                        mId,
                                        sId != null ? sId : "",
                                        chatId,
                                        mText != null ? mText : "",
                                        mTs,
                                        mType != null ? mType : "text"
                                );
                                m.setChatId(chatId);
                                m.setSenderName(sName);
                                m.setReplyToMessageId(mDoc.getString("replyToMessageId"));
                                m.setReplySenderName(mDoc.getString("replySenderName"));
                                m.setPollJson(pollJson);
                                if (isEdited) m.setEdited(true);
                                m.setDelivered(true);
                                m.setPending(false);
                                m.setRead(true);
                                com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).saveMessage(chatId, m);
                                changed = true;
                            } else {
                                if ("deleted".equals(mType) && !"deleted".equals(existing.getType())) {
                                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).markMessageDeleted(chatId, mId);
                                    changed = true;
                                } else if (isEdited && mText != null && !mText.equals(existing.getText())) {
                                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).updateMessageText(chatId, mId, mText);
                                    changed = true;
                                }
                                if (pollJson != null && !pollJson.equals(existing.getPollJson())) {
                                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).updateMessagePollJson(chatId, mId, pollJson);
                                    changed = true;
                                }
                            }
                        } catch (Exception docEx) {
                            android.util.Log.w("ChatActivity", "Error parsing Firestore group message doc: " + docEx.getMessage());
                        }
                    }
                    if (changed) {
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).markAllAsRead(chatId, currentUserId);
                        reloadMessagesFromLocalVault();
                    }
                });
    }

    private void attachDirectRealtimeListeners() {
        if (chatId == null || db == null) return;

        // Initiate WebRTC mesh link when opening conversation
        if (recipientId != null && !recipientId.isEmpty()) {
            com.m3.veil.webrtc.WebRtcManager.getInstance(this).initiateConnection(recipientId);
        }

        if (directMessagesListener != null) directMessagesListener.remove();
        directMessagesListener = db.collection("groups").document(chatId)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener(this, (snapshots, error) -> {
                    if (error != null) {
                        Log.e("ChatActivity", "Direct message listener error on groups: " + error.getMessage(), error);
                        return;
                    }
                    processDirectFirestoreSnapshots(snapshots);
                });

        if (directChatsMirrorListener != null) directChatsMirrorListener.remove();
        directChatsMirrorListener = db.collection("chats").document(chatId)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .addSnapshotListener(this, (snapshots, error) -> {
                    if (error != null) return;
                    processDirectFirestoreSnapshots(snapshots);
                });
    }

    private void processDirectFirestoreSnapshots(com.google.firebase.firestore.QuerySnapshot snapshots) {
        if (snapshots == null || chatId == null) return;
        boolean changed = false;
        String myOwnName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
        for (com.google.firebase.firestore.DocumentSnapshot mDoc : snapshots.getDocuments()) {
            try {
                String mId = mDoc.getString("id");
                if (mId == null) mId = mDoc.getId();
                String mType = mDoc.getString("type");
                String mText = mDoc.getString("text");
                if (mText != null && mText.startsWith("ENC:v1:")) {
                    mText = EncryptionManager.decryptGroupPayload(chatId, mText);
                }
                String sId = mDoc.getString("senderId");
                String sName = mDoc.getString("senderName");
                String pollJson = mDoc.getString("pollJson");
                if (pollJson != null && pollJson.startsWith("ENC:v1:")) {
                    pollJson = EncryptionManager.decryptGroupPayload(chatId, pollJson);
                }
                boolean isEdited = VeilFirestoreUtils.getSafeBoolean(mDoc, "edited", false);
                Timestamp mTs = VeilFirestoreUtils.getSafeTimestamp(mDoc, "timestamp", Timestamp.now());
                boolean docDelivered = VeilFirestoreUtils.getSafeBoolean(mDoc, "delivered", false);
                boolean docRead = VeilFirestoreUtils.getSafeBoolean(mDoc, "read", false);

                if (sId != null && !sId.equals(currentUserId) && sName != null && !sName.isEmpty()
                        && (recipientName == null || recipientName.isEmpty()
                        || (myOwnName != null && recipientName.equals(myOwnName))
                        || "Direct Chat".equals(recipientName)
                        || "Veil Contact".equals(recipientName)
                        || "Veil User".equals(recipientName)
                        || "Anonymous Peer".equals(recipientName))) {
                    recipientName = sName;
                    if (currentLocalChat != null && !currentLocalChat.isGroup()) {
                        currentLocalChat.setName(sName);
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).saveOrUpdateChat(currentLocalChat);
                    }
                    runOnUiThread(this::updateRecipientHeader);
                }

                Message existing = com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).getMessageById(mId);

                if (sId != null && sId.equals(currentUserId)) {
                    if (existing != null) {
                        boolean statusChanged = false;
                        if (!existing.isDelivered() && docDelivered) {
                            existing.setDelivered(true);
                            statusChanged = true;
                        }
                        if (!existing.isRead() && docRead) {
                            existing.setRead(true);
                            existing.setDelivered(true);
                            statusChanged = true;
                        }
                        if (statusChanged) {
                            com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this)
                                    .updateMessageStatus(mId, existing.isDelivered(), existing.isRead());
                            com.m3.veil.manager.VeilMessagesController.getInstance(ChatActivity.this)
                                    .onMessageStatusChanged(mId, existing.isDelivered(), existing.isRead());
                            for (int i = 0; i < messageList.size(); i++) {
                                Message lm = messageList.get(i);
                                if (lm.getId() != null && lm.getId().equals(mId)) {
                                    lm.setDelivered(existing.isDelivered());
                                    lm.setRead(existing.isRead());
                                    lm.setPending(false);
                                    final int itemPos = i + 1;
                                    runOnUiThread(() -> {
                                        if (messagesAdapter != null) messagesAdapter.notifyItemChanged(itemPos);
                                    });
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    if (existing == null) {
                        Message m = new Message(
                                mId,
                                sId != null ? sId : "",
                                recipientId != null ? recipientId : currentUserId,
                                mText != null ? mText : "",
                                mTs,
                                mType != null ? mType : "text"
                        );
                        m.setChatId(chatId);
                        m.setSenderName(sName != null ? sName : recipientName);
                        m.setReplyToMessageId(mDoc.getString("replyToMessageId"));
                        m.setReplySenderName(mDoc.getString("replySenderName"));
                        m.setPollJson(pollJson);
                        if (isEdited) m.setEdited(true);
                        m.setDelivered(true);
                        m.setPending(false);
                        m.setRead(true);

                        com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).saveMessage(chatId, m);

                        Map<String, Object> ack = new HashMap<>();
                        ack.put("delivered", true);
                        ack.put("read", true);
                        mDoc.getReference().set(ack, com.google.firebase.firestore.SetOptions.merge());
                        try {
                            db.collection("groups").document(chatId).collection("messages").document(mId)
                                    .set(ack, com.google.firebase.firestore.SetOptions.merge());
                            db.collection("chats").document(chatId).collection("messages").document(mId)
                                    .set(ack, com.google.firebase.firestore.SetOptions.merge());
                        } catch (Throwable ignored) {}

                        if (recipientId != null) {
                            com.m3.veil.webrtc.WebRtcManager.getInstance(ChatActivity.this)
                                    .sendReceipt(recipientId, mId, true, true);
                        }
                        changed = true;
                    } else {
                        if (!existing.isRead()) {
                            existing.setRead(true);
                            existing.setDelivered(true);
                            com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this)
                                    .updateMessageStatus(mId, true, true);
                            Map<String, Object> ack = new HashMap<>();
                            ack.put("delivered", true);
                            ack.put("read", true);
                            mDoc.getReference().set(ack, com.google.firebase.firestore.SetOptions.merge());
                            try {
                                db.collection("groups").document(chatId).collection("messages").document(mId)
                                        .set(ack, com.google.firebase.firestore.SetOptions.merge());
                                db.collection("chats").document(chatId).collection("messages").document(mId)
                                        .set(ack, com.google.firebase.firestore.SetOptions.merge());
                            } catch (Throwable ignored) {}

                            if (recipientId != null) {
                                com.m3.veil.webrtc.WebRtcManager.getInstance(ChatActivity.this)
                                        .sendReceipt(recipientId, mId, true, true);
                            }
                            changed = true;
                        }
                        if ("deleted".equals(mType) && !"deleted".equals(existing.getType())) {
                            com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).markMessageDeleted(chatId, mId);
                            changed = true;
                        } else if (isEdited && mText != null && !mText.equals(existing.getText())) {
                            com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).updateMessageText(chatId, mId, mText);
                            changed = true;
                        }
                        if (pollJson != null && !pollJson.equals(existing.getPollJson())) {
                            com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).updateMessagePollJson(chatId, mId, pollJson);
                            changed = true;
                        }
                    }
                }
            } catch (Exception docEx) {
                android.util.Log.w("ChatActivity", "Error parsing direct message doc: " + docEx.getMessage());
            }
        }
        if (changed) {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(ChatActivity.this).markAllAsRead(chatId, currentUserId);
            reloadMessagesFromLocalVault();
        }
    }

    private void sendReadReceiptsForUnreadMessages() {
        if (chatId == null || recipientId == null) return;
        android.content.SharedPreferences privacyPrefs = getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE);
        boolean sendReadReceipt = privacyPrefs.getBoolean("read_receipts_enabled", true);
        if (!sendReadReceipt) return;

        // 1. Send individual read receipts for any unread inbound messages in SQLCipher before marking local DB read
        List<Message> unreadCandidates = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getMessagesForChat(chatId);
        if (unreadCandidates != null) {
            for (Message m : unreadCandidates) {
                if (m.getSenderId() != null && !m.getSenderId().equals(currentUserId) && !m.isRead()) {
                    com.m3.veil.webrtc.WebRtcManager.getInstance(this).sendReceipt(recipientId, m.getId(), true, true);
                    m.setRead(true);
                    if (chatId != null && !isGroupChatContext() && db != null) {
                        try {
                            Map<String, Object> ack = new HashMap<>();
                            ack.put("delivered", true);
                            ack.put("read", true);
                            db.collection("groups").document(chatId).collection("messages").document(m.getId())
                                    .set(ack, com.google.firebase.firestore.SetOptions.merge());
                            db.collection("chats").document(chatId).collection("messages").document(m.getId())
                                    .set(ack, com.google.firebase.firestore.SetOptions.merge());
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // 2. Mark dialog read and broadcast bulk chat_read packet to recipient
        com.m3.veil.manager.VeilMessagesController.getInstance(this).markDialogAsRead(chatId, recipientId);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isChatPinUnlocked", isChatPinUnlocked);
    }

    @Override
    protected void onResume() {
        super.onResume();
        presenceHandler.removeCallbacks(presenceTicker);
        presenceHandler.post(presenceTicker);
        if (currentUserId != null) {
            com.m3.veil.webrtc.WebRtcManager.getInstance(this).fetchAndProcessOfflineInboxOnce(currentUserId);
        }
        if (chatId != null) {
            com.m3.veil.manager.VeilMessagesController.getInstance(this).setActiveChatIdInForeground(chatId);
            sendReadReceiptsForUnreadMessages();
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).markAllAsRead(chatId, currentUserId);
            reloadMessagesFromLocalVault();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        presenceHandler.removeCallbacks(presenceTicker);
        com.m3.veil.manager.VeilMessagesController.getInstance(this).setActiveChatIdInForeground(null);
    }

    private void reloadMessagesFromLocalVault() {
        if (chatId == null) return;
        int prevCount = messageList != null ? messageList.size() : 0;
        boolean wasAtBottom = true;
        if (messagesRecyclerView != null && messagesAdapter != null && prevCount > 0) {
            LinearLayoutManager lm = (LinearLayoutManager) messagesRecyclerView.getLayoutManager();
            if (lm != null) {
                int lastVis = lm.findLastVisibleItemPosition();
                int total = messagesAdapter.getItemCount();
                wasAtBottom = (total - 1 - lastVis) <= 3;
            }
        }

        List<Message> localEncrypted = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getMessages(chatId);
        messageList.clear();
        if (localEncrypted != null) {
            for (Message m : localEncrypted) {
                if (m.getText() != null && m.getText().startsWith("[roulette_control:")) {
                    continue;
                }
                messageList.add(m);
            }
        }
        int newCount = messageList.size();
        boolean lastIsOutgoing = newCount > 0
                && messageList.get(newCount - 1).getSenderId() != null
                && messageList.get(newCount - 1).getSenderId().equals(currentUserId);

        if (messagesAdapter != null) {
            messagesAdapter.notifyDataSetChanged();
            String targetScrollMsgId = getIntent().getStringExtra("scrollToMessageId");
            if (targetScrollMsgId != null && !targetScrollMsgId.isEmpty() && !messageList.isEmpty()) {
                getIntent().removeExtra("scrollToMessageId");
                int targetAdapterPos = -1;
                for (int i = 0; i < messageList.size(); i++) {
                    if (targetScrollMsgId.equals(messageList.get(i).getId())) {
                        targetAdapterPos = i + 1;
                        break;
                    }
                }
                if (targetAdapterPos != -1) {
                    final int finalPos = targetAdapterPos;
                    highlightPosition = finalPos;
                    messagesRecyclerView.scrollToPosition(finalPos);
                    messagesRecyclerView.postDelayed(() -> {
                        RecyclerView.ViewHolder vh = messagesRecyclerView.findViewHolderForAdapterPosition(finalPos);
                        if (vh != null) blinkView(vh);
                    }, 220);
                } else {
                    messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
                }
            } else if (!messageList.isEmpty()) {
                if (prevCount == 0 || wasAtBottom || lastIsOutgoing) {
                    newMessagesWhileScrolledUp = 0;
                    TextView tvUnreadBadge = findViewById(R.id.tvScrollUnreadBadge);
                    if (tvUnreadBadge != null) tvUnreadBadge.setVisibility(View.GONE);
                    messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
                } else if (newCount > prevCount) {
                    newMessagesWhileScrolledUp += (newCount - prevCount);
                    if (layoutScrollToBottom != null) {
                        layoutScrollToBottom.setVisibility(View.VISIBLE);
                        layoutScrollToBottom.setAlpha(1f);
                    }
                    TextView tvUnreadBadge = findViewById(R.id.tvScrollUnreadBadge);
                    if (tvUnreadBadge != null) {
                        tvUnreadBadge.setText(String.valueOf(newMessagesWhileScrolledUp));
                        tvUnreadBadge.setVisibility(View.VISIBLE);
                    }
                }
            }
        }

        // Feature #212: Ambient Emotion Heatmap Gradient
        applyEmotionHeatmapGradient();

        // Feature #135: Smart Reply Suggestion Bar
        Message lastIncoming = null;
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message m = messageList.get(i);
            if (m != null && m.getSenderId() != null && !m.getSenderId().equals(currentUserId) && "text".equals(m.getType())) {
                lastIncoming = m;
                break;
            }
        }
        updateSmartReplySuggestions(lastIncoming);
    }

    private void applyEmotionHeatmapGradient() {
        android.content.SharedPreferences appPrefs = getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
        boolean enabled = appPrefs.getBoolean("ambient_emotion_gradient", false);
        if (!enabled) {
            return;
        }
        if (currentLocalChat != null && currentLocalChat.getWallpaperColor() != null && !currentLocalChat.getWallpaperColor().isEmpty()) {
            return;
        }
        View chatRoot = findViewById(R.id.chat_root);
        if (chatRoot == null) return;

        boolean isNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        com.m3.veil.utils.VeilLocalIntelligenceEngine.EmotionAnalysisResult emotion =
                com.m3.veil.utils.VeilLocalIntelligenceEngine.getInstance().analyzeThreadEmotion(messageList);
        int startColor = emotion.gradientStartColor;
        int endColor = emotion.gradientEndColor;
        if (!isNight) {
            startColor = android.graphics.Color.parseColor("#FFFBEB");
            endColor = android.graphics.Color.parseColor("#F1F5F9");
        }
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{startColor, endColor}
        );
        chatRoot.setBackground(gd);
    }

    private void updateSmartReplySuggestions(Message lastMsg) {
        if (layoutSmartReplies == null || containerSmartReplies == null) return;
        if (lastMsg == null || lastMsg.getText() == null || lastMsg.getText().trim().isEmpty() || "deleted".equals(lastMsg.getType())) {
            layoutSmartReplies.setVisibility(View.GONE);
            return;
        }

        List<String> suggestions = com.m3.veil.utils.VeilLocalIntelligenceEngine.getInstance().generateSmartReplies(lastMsg.getText());
        if (suggestions == null || suggestions.isEmpty()) {
            layoutSmartReplies.setVisibility(View.GONE);
            return;
        }

        containerSmartReplies.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (12 * density);
        int padV = (int) (6 * density);
        int marginEnd = (int) (6 * density);

        for (String reply : suggestions) {
            TextView chip = new TextView(this);
            chip.setText("⚡ " + reply);
            chip.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            chip.setTextSize(12.5f);
            chip.setTypeface(null, Typeface.NORMAL);
            chip.setPadding(padH, padV, padH, padV);
            chip.setBackgroundResource(R.drawable.bg_filter_chip);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, marginEnd, 0);
            chip.setLayoutParams(lp);

            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                replyToMessage(lastMsg);
                messageEditText.setText(reply);
                messageEditText.setSelection(reply.length());
                messageEditText.requestFocus();
            });

            containerSmartReplies.addView(chip);
        }

        layoutSmartReplies.setVisibility(View.VISIBLE);
    }

    private void checkAndVanishMessages() {
        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).deleteExpiredVanishMessages();
        if (messageList == null || messageList.isEmpty()) return;

        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message message = messageList.get(i);
            if (message.getExpiresAt() != null) {
                long expireTime = message.getExpiresAt().toDate().getTime();
                if (expireTime > 0 && now >= expireTime) {
                    messageList.remove(i);
                    changed = true;
                }
            }
        }
        if (changed && messagesAdapter != null) {
            messagesAdapter.notifyDataSetChanged();
        }
    }

    private void showVanishTimerDialog() {
        String[] options = {"Off", "10 seconds", "1 minute", "1 hour", "24 hours", "7 days", "30 days"};
        long[] times = {0, 10000, 60000, 3600000, 86400000L, 604800000L, 2592000000L};

        int checkedItem = 0;
        for (int i = 0; i < times.length; i++) {
            if (currentVanishTimer == times[i]) {
                checkedItem = i;
                break;
            }
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_vanish_timer_sheet);

        LinearLayout optionsContainer = sheet.findViewById(R.id.vanishOptionsContainer);
        if (optionsContainer == null) return;

        String[] descriptions = {
                "Messages persist normally in encrypted database",
                "Ultra-fast zero-trace vanishing for ephemeral secrets",
                "Quick shredding for brief confidential chats",
                "Medium-length conversation persistence",
                "Standard 24-hour daily wipe cycle",
                "Weekly rolling confidentiality",
                "Extended monthly confidentiality"
        };

        optionsContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        for (int i = 0; i < times.length; i++) {
            final long selectedTimer = times[i];
            final String label = options[i];
            final boolean isSelected = (currentVanishTimer == selectedTimer);

            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            card.setLayoutParams(lp);
            card.setRadius(12 * dp);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * dp));
            card.setStrokeColor(ContextCompat.getColor(this, isSelected ? R.color.colorPrimary : R.color.colorOutline));
            card.setCardBackgroundColor(ContextCompat.getColor(this, isSelected ? R.color.surfaceVariant : android.R.color.transparent));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(tcLp);

            TextView tvOpt = new TextView(this);
            tvOpt.setText(label);
            tvOpt.setTextColor(ContextCompat.getColor(this, isSelected ? R.color.colorPrimary : R.color.textPrimary));
            tvOpt.setTextSize(15.5f);
            tvOpt.setTypeface(null, Typeface.BOLD);
            textCol.addView(tvOpt);

            TextView tvDesc = new TextView(this);
            tvDesc.setText(descriptions[i]);
            tvDesc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            tvDesc.setTextSize(12f);
            textCol.addView(tvDesc);
            row.addView(textCol);

            RadioButton rb = new RadioButton(this);
            rb.setChecked(isSelected);
            rb.setClickable(false);
            rb.setFocusable(false);
            row.addView(rb);

            card.addView(row);
            card.setOnClickListener(v -> {
                sheet.dismiss();
                currentVanishTimer = selectedTimer;
                if (selectedTimer == 0) {
                    currentVanishMode = "off";
                    if (currentLocalChat != null) {
                        currentLocalChat.setVanishTimer(0);
                        currentLocalChat.setVanishMode("off");
                    }
                    com.m3.veil.manager.VeilMessagesController.getInstance(this)
                            .setDialogVanishSettings(chatId, recipientId, 0, "off");
                    updatePresenceAndToolbarStatus();
                    Toast.makeText(this, "Vanish timer turned Off", Toast.LENGTH_SHORT).show();
                } else {
                    if ("off".equals(currentVanishMode) || currentVanishMode == null) {
                        currentVanishMode = "after_read";
                    }
                    showVanishModePickerSubDialog(selectedTimer);
                }
            });

            optionsContainer.addView(card);
        }

        sheet.show();
    }

    private void showVanishModePickerSubDialog(long selectedTimer) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_vanish_mode_sheet);

        View cardAfterRead = sheet.findViewById(R.id.cardVanishAfterRead);
        View cardAfterSend = sheet.findViewById(R.id.cardVanishAfterSend);
        RadioButton rbAfterRead = sheet.findViewById(R.id.rbVanishAfterRead);
        RadioButton rbAfterSend = sheet.findViewById(R.id.rbVanishAfterSend);

        boolean isAfterRead = !"after_send".equals(currentVanishMode);
        if (rbAfterRead != null) rbAfterRead.setChecked(isAfterRead);
        if (rbAfterSend != null) rbAfterSend.setChecked(!isAfterRead);

        if (cardAfterRead != null) {
            cardAfterRead.setOnClickListener(v -> {
                currentVanishTimer = selectedTimer;
                currentVanishMode = "after_read";
                commitVanishSettings("After Read (Ephemeral)");
                sheet.dismiss();
            });
        }
        if (cardAfterSend != null) {
            cardAfterSend.setOnClickListener(v -> {
                currentVanishTimer = selectedTimer;
                currentVanishMode = "after_send";
                commitVanishSettings("After Send");
                sheet.dismiss();
            });
        }
        sheet.show();
    }

    private void showVanishModeDialog() {
        showVanishModePickerSubDialog(currentVanishTimer);
    }

    private void commitVanishSettings(String modeLabel) {
        if (currentLocalChat != null) {
            currentLocalChat.setVanishTimer(currentVanishTimer);
            currentLocalChat.setVanishMode(currentVanishMode);
        }
        com.m3.veil.manager.VeilMessagesController.getInstance(this)
                .setDialogVanishSettings(chatId, recipientId, currentVanishTimer, currentVanishMode);
        updatePresenceAndToolbarStatus();
        Toast.makeText(this, "Vanish set: " + modeLabel, Toast.LENGTH_SHORT).show();
    }

    // =========================================================
    // NEXT-GEN VEILCHAT FEATURES (BLINK, POLLS, DOODLE, FILTERS)
    // =========================================================

    private void toggleBlinkMode() {
        isBlinkModeActive = !isBlinkModeActive;
        updateBlinkModeChipUI();
        if (isBlinkModeActive) {
            messageEditText.setHint("⚡ Blink Mode: Message self-destructs after 1 view...");
            Toast.makeText(this, "⚡ Blink (View-Once) enabled for next message", Toast.LENGTH_SHORT).show();
        } else {
            messageEditText.setHint(R.string.type_message);
            Toast.makeText(this, "Blink mode off", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBlinkModeChipUI() {
        TextView chipQuickBlink = findViewById(R.id.chipQuickBlink);
        if (chipQuickBlink != null) {
            chipQuickBlink.setText(isBlinkModeActive ? "⚡ Blink ON" : "⚡ Blink 1x");
            chipQuickBlink.setBackgroundResource(isBlinkModeActive ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chipQuickBlink.setTextColor(getResources().getColor(isBlinkModeActive ? R.color.veil_accent : R.color.text_secondary));
        }
        TextView btnLockedViewOnceVoice = findViewById(R.id.btnLockedViewOnceVoice);
        if (btnLockedViewOnceVoice != null) {
            btnLockedViewOnceVoice.setText(isBlinkModeActive ? "⚡ 1x On" : "⚡ 1x Off");
            btnLockedViewOnceVoice.setBackgroundResource(isBlinkModeActive ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
        }
    }

    private void updateVoiceMaskChipUI() {
        TextView chipQuickVoiceMask = findViewById(R.id.chipQuickVoiceMask);
        TextView btnLockedVoiceFilter = findViewById(R.id.btnLockedVoiceFilter);
        String label = "🎙️ Voice: Normal";
        String shortLabel = "🎙️ Normal";
        boolean masked = !"normal".equals(activeVoiceFilter);
        switch (activeVoiceFilter) {
            case "robot":
                label = "🤖 Voice: Robot";
                shortLabel = "🤖 Robot";
                break;
            case "deep":
                label = "🕵️ Voice: Deep";
                shortLabel = "🕵️ Deep";
                break;
            case "chipmunk":
                label = "🐿️ Voice: Helium";
                shortLabel = "🐿️ Helium";
                break;
        }
        if (chipQuickVoiceMask != null) {
            chipQuickVoiceMask.setText(label);
            chipQuickVoiceMask.setBackgroundResource(masked ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chipQuickVoiceMask.setTextColor(getResources().getColor(masked ? R.color.veil_accent : R.color.text_secondary));
        }
        if (btnLockedVoiceFilter != null) {
            btnLockedVoiceFilter.setText(shortLabel);
            btnLockedVoiceFilter.setBackgroundResource(masked ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
        }
    }

    private void openBlinkViewOnceMessage(Message message) {
        if (message == null || message.isBlinkViewed()) {
            Toast.makeText(this, "⚡ This view once message has already self-destructed.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isMine = message.getSenderId() != null && message.getSenderId().equals(currentUserId);
        if (isMine) {
            Toast.makeText(this, "⚡ Waiting for recipient to open your view once message.", Toast.LENGTH_SHORT).show();
            return;
        }

        String mediaType = message.getType();
        boolean isMedia = "image".equals(mediaType) || "video".equals(mediaType) || "voice".equals(mediaType);

        if (isMedia) {
            String localPath = message.getLocalFilePath();
            boolean fileReady = localPath != null && new File(localPath).exists();
            if (!fileReady) {
                // Not downloaded yet - trigger on-demand download showing progress on blinkProgressBar
                messagesAdapter.setMediaDownloadState(message.getId(), MessagesAdapter.STATE_DOWNLOADING, 0);
                Toast.makeText(this, "Downloading view-once media...", Toast.LENGTH_SHORT).show();
                com.m3.veil.manager.VeilMessagesController.getInstance(this).downloadMedia(
                        recipientId,
                        message.getId(),
                        new com.m3.veil.webrtc.P2PFileTransferHelper.DownloadProgressListener() {
                            @Override
                            public void onProgress(String transferId, int percent) {
                                runOnUiThread(() -> messagesAdapter.setMediaDownloadState(transferId, MessagesAdapter.STATE_DOWNLOADING, percent));
                            }

                            @Override
                            public void onComplete(String transferId, File file) {
                                runOnUiThread(() -> {
                                    messagesAdapter.setMediaDownloadState(transferId, MessagesAdapter.STATE_DOWNLOADED, 100);
                                    message.setLocalFilePath(file.getAbsolutePath());
                                    presentBlinkMedia(message);
                                });
                            }

                            @Override
                            public void onError(String transferId, String error) {
                                runOnUiThread(() -> {
                                    Toast.makeText(ChatActivity.this, "Download notice: " + error, Toast.LENGTH_SHORT).show();
                                    messagesAdapter.setMediaDownloadState(transferId, MessagesAdapter.STATE_RETRY, 0);
                                });
                            }
                        }
                );
                return;
            }

            presentBlinkMedia(message);
        } else {
            // View once text message
            String secretContent = message.getText() != null ? message.getText() : "Secret Message";
            BottomSheetDialog blinkSheet = new BottomSheetDialog(this);
            blinkSheet.setCancelable(false);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setBackgroundResource(R.drawable.bottom_sheet_background);
            int padH = (int) (24 * getResources().getDisplayMetrics().density);
            int padV = (int) (16 * getResources().getDisplayMetrics().density);
            layout.setPadding(padH, padV, padH, padV * 2);

            View handle = new View(this);
            int handleW = (int) (36 * getResources().getDisplayMetrics().density);
            int handleH = (int) (4 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
            handle.setLayoutParams(handleLp);
            handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
            layout.addView(handle);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("⚡ View Once Ephemeral Message");
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            tvTitle.setTextSize(19);
            tvTitle.setTypeface(null, Typeface.BOLD);
            layout.addView(tvTitle);

            TextView tvMsg = new TextView(this);
            tvMsg.setText(secretContent);
            tvMsg.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            tvMsg.setTextSize(16);
            tvMsg.setBackgroundResource(R.drawable.bg_search_input);
            int mPad = (int) (16 * getResources().getDisplayMetrics().density);
            tvMsg.setPadding(mPad, mPad, mPad, mPad);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mLp.topMargin = (int) (14 * getResources().getDisplayMetrics().density);
            mLp.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
            tvMsg.setLayoutParams(mLp);
            layout.addView(tvMsg);

            TextView tvWarn = new TextView(this);
            tvWarn.setText("⚠️ This message will permanently self-destruct from the encrypted vault once closed.");
            tvWarn.setTextColor(0xFFEF4444);
            tvWarn.setTextSize(12.5f);
            tvWarn.setPadding(0, 0, 0, (int) (20 * getResources().getDisplayMetrics().density));
            layout.addView(tvWarn);

            MaterialButton btnDestroy = new MaterialButton(this);
            btnDestroy.setText("Close & Destroy Message");
            btnDestroy.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
            btnDestroy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));
            btnDestroy.setTextColor(0xFFFFFFFF);
            layout.addView(btnDestroy);

            btnDestroy.setOnClickListener(v -> {
                blinkSheet.dismiss();
                com.m3.veil.manager.VeilMessagesController.getInstance(this)
                        .sendBlinkOpened(chatId, recipientId, message.getId());
                reloadMessagesFromLocalVault();
            });

            blinkSheet.setContentView(layout);
            blinkSheet.show();
        }
    }

    private void presentBlinkMedia(Message message) {
        if (message == null) return;
        String type = message.getType();
        if ("image".equals(type) || "video".equals(type)) {
            Intent intent = new Intent(this, MediaViewerActivity.class);
            intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, message.getLocalFilePath());
            intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, type);
            intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, recipientName != null ? recipientName : "Contact");
            intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, message.getText());
            intent.putExtra(MediaViewerActivity.EXTRA_IS_VIEW_ONCE, true);
            intent.putExtra(MediaViewerActivity.EXTRA_MESSAGE_ID, message.getId());
            intent.putExtra(MediaViewerActivity.EXTRA_CHAT_ID, chatId);
            intent.putExtra(MediaViewerActivity.EXTRA_PEER_ID, recipientId);
            startActivityForResult(intent, REQ_VIEW_ONCE_MEDIA);
        } else if ("voice".equals(type)) {
            showBlinkVoicePlayerDialog(message);
        }
    }

    private void showBlinkVoicePlayerDialog(Message message) {
        if (message == null) return;
        final String audioPath = message.getLocalFilePath();
        if (audioPath == null || !new File(audioPath).exists()) {
            Toast.makeText(this, "Audio file not available", Toast.LENGTH_SHORT).show();
            return;
        }

        final MediaPlayer blinkPlayer = new MediaPlayer();
        final Handler playerHandler = new Handler(Looper.getMainLooper());
        final float dp = getResources().getDisplayMetrics().density;

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet);
        int pad = (int) (20 * dp);
        root.setPadding(pad, (int) (16 * dp), pad, (int) (24 * dp));

        // Top Header
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setBackgroundResource(R.drawable.bg_view_once_ring);
        badge.setText("1");
        badge.setTextColor(android.graphics.Color.parseColor("#00A884"));
        badge.setTextSize(13f);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp));
        badgeLp.rightMargin = (int) (12 * dp);
        badge.setLayoutParams(badgeLp);
        headerRow.addView(badge);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("View once voice message");
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(getResources().getColor(R.color.textPrimary));
        titleCol.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Disappears after listening");
        tvSubtitle.setTextSize(12.5f);
        tvSubtitle.setTextColor(getResources().getColor(R.color.textSecondary));
        titleCol.addView(tvSubtitle);
        headerRow.addView(titleCol);
        root.addView(headerRow);

        // Progress Slider
        SeekBar seekBar = new SeekBar(this);
        seekBar.setPadding((int) (8 * dp), (int) (16 * dp), (int) (8 * dp), (int) (8 * dp));
        root.addView(seekBar);

        // Time & Speed Row
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTime = new TextView(this);
        tvTime.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvTime.setText("0:00");
        tvTime.setTextSize(13f);
        tvTime.setTextColor(getResources().getColor(R.color.textSecondary));
        infoRow.addView(tvTime);

        TextView btnSpeed = new TextView(this);
        btnSpeed.setText("1.0X");
        btnSpeed.setTextSize(12f);
        btnSpeed.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSpeed.setTextColor(android.graphics.Color.parseColor("#00A884"));
        btnSpeed.setBackgroundResource(R.drawable.bg_filter_chip);
        btnSpeed.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
        infoRow.addView(btnSpeed);
        root.addView(infoRow);

        // Play/Pause Action
        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        controlsRow.setPadding(0, (int) (16 * dp), 0, 0);

        ImageView btnPlay = new ImageView(this);
        btnPlay.setImageResource(R.drawable.ic_play_arrow);
        btnPlay.setBackgroundResource(R.drawable.bg_media_download_circle);
        btnPlay.setColorFilter(android.graphics.Color.WHITE);
        int btnSize = (int) (52 * dp);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        btnPlay.setLayoutParams(playLp);
        btnPlay.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));
        controlsRow.addView(btnPlay);
        root.addView(controlsRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        final float[] speeds = {1.0f, 1.5f, 2.0f};
        final int[] speedIndex = {0};

        btnSpeed.setOnClickListener(v -> {
            speedIndex[0] = (speedIndex[0] + 1) % speeds.length;
            float chosenSpeed = speeds[speedIndex[0]];
            btnSpeed.setText(String.format(Locale.getDefault(), "%.1fX", chosenSpeed).replace(".0", ""));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && blinkPlayer.isPlaying()) {
                try {
                    PlaybackParams params = blinkPlayer.getPlaybackParams();
                    params.setSpeed(chosenSpeed);
                    blinkPlayer.setPlaybackParams(params);
                } catch (Exception ignored) {}
            }
        });

        Runnable updateProgressRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (blinkPlayer.isPlaying()) {
                        int cur = blinkPlayer.getCurrentPosition();
                        int dur = blinkPlayer.getDuration();
                        seekBar.setProgress(cur);
                        tvTime.setText(com.m3.veil.utils.VoicePlayerManager.formatDuration(cur) + " / " + com.m3.veil.utils.VoicePlayerManager.formatDuration(dur));
                        playerHandler.postDelayed(this, 100);
                    }
                } catch (Exception ignored) {}
            }
        };

        try {
            blinkPlayer.setDataSource(audioPath);
            blinkPlayer.prepare();
            int totalDur = blinkPlayer.getDuration();
            seekBar.setMax(totalDur);
            tvTime.setText("0:00 / " + com.m3.veil.utils.VoicePlayerManager.formatDuration(totalDur));
        } catch (Exception e) {
            Toast.makeText(this, "Could not load voice note", Toast.LENGTH_SHORT).show();
            return;
        }

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    blinkPlayer.seekTo(progress);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });

        btnPlay.setOnClickListener(v -> {
            if (blinkPlayer.isPlaying()) {
                blinkPlayer.pause();
                btnPlay.setImageResource(R.drawable.ic_play_arrow);
                playerHandler.removeCallbacks(updateProgressRunnable);
            } else {
                blinkPlayer.start();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        PlaybackParams p = blinkPlayer.getPlaybackParams();
                        p.setSpeed(speeds[speedIndex[0]]);
                        blinkPlayer.setPlaybackParams(p);
                    } catch (Exception ignored) {}
                }
                btnPlay.setImageResource(R.drawable.ic_pause);
                playerHandler.post(updateProgressRunnable);
            }
        });

        blinkPlayer.setOnCompletionListener(mp -> {
            btnPlay.setImageResource(R.drawable.ic_play_arrow);
            playerHandler.removeCallbacks(updateProgressRunnable);
            Toast.makeText(this, "⚡ Voice message listened. Self-destructing...", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> {
            playerHandler.removeCallbacks(updateProgressRunnable);
            try {
                if (blinkPlayer.isPlaying()) blinkPlayer.stop();
                blinkPlayer.release();
            } catch (Exception ignored) {}

            try {
                File af = new File(audioPath);
                if (af.exists()) af.delete();
            } catch (Exception ignored) {}

            com.m3.veil.manager.VeilMessagesController.getInstance(this).sendBlinkOpened(chatId, recipientId, message.getId());
            reloadMessagesFromLocalVault();
        });

        dialog.show();
        btnPlay.performClick();
    }

    private void handleCallMessageClick(Message message) {
        if (message == null || recipientId == null) return;
        boolean isVideo = false;
        try {
            if (message.getPollJson() != null && !message.getPollJson().isEmpty()) {
                org.json.JSONObject obj = new org.json.JSONObject(message.getPollJson());
                isVideo = "video".equalsIgnoreCase(obj.optString("callType"));
            } else if (message.getText() != null) {
                isVideo = message.getText().toLowerCase().contains("video");
            }
        } catch (Exception ignored) {}

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CONTACT_ID, recipientId);
        intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, recipientName != null ? recipientName : "Contact");
        intent.putExtra(CallActivity.EXTRA_CALL_TYPE, isVideo ? "video" : "audio");
        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
        startActivity(intent);
    }

    private void showCreateInlinePollDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet);
        int pad = (int) (20 * dp);
        root.setPadding(pad, (int) (16 * dp), pad, pad);

        // Header ("Create Poll" — matching WhatsApp _name_removed__res_0x7f0e00c1.xml)
        TextView tvTitle = new TextView(this);
        tvTitle.setText("Create Poll");
        tvTitle.setTextSize(19f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(getResources().getColor(R.color.textPrimary));
        root.addView(tvTitle);

        TextView tvQLabel = new TextView(this);
        tvQLabel.setText("Question");
        tvQLabel.setTextSize(13f);
        tvQLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvQLabel.setTextColor(getResources().getColor(R.color.textSecondary));
        LinearLayout.LayoutParams qLabelLp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        qLabelLp.topMargin = (int) (14 * dp);
        qLabelLp.bottomMargin = (int) (6 * dp);
        tvQLabel.setLayoutParams(qLabelLp);
        root.addView(tvQLabel);

        EditText qInput = new EditText(this);
        qInput.setHint("Ask question");
        qInput.setBackgroundResource(R.drawable.bg_chat_input);
        qInput.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        qInput.setTextSize(15f);
        qInput.setTextColor(getResources().getColor(R.color.textPrimary));
        qInput.setHintTextColor(getResources().getColor(R.color.textSecondary));
        root.addView(qInput);

        TextView tvOptLabel = new TextView(this);
        tvOptLabel.setText("Options");
        tvOptLabel.setTextSize(13f);
        tvOptLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvOptLabel.setTextColor(getResources().getColor(R.color.textSecondary));
        LinearLayout.LayoutParams optLabelLp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        optLabelLp.topMargin = (int) (14 * dp);
        optLabelLp.bottomMargin = (int) (6 * dp);
        tvOptLabel.setLayoutParams(optLabelLp);
        root.addView(tvOptLabel);

        LinearLayout optionsContainer = new LinearLayout(this);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(optionsContainer);

        final List<EditText> optionInputs = new ArrayList<>();
        java.lang.Runnable addOptionRow = () -> {
            if (optionInputs.size() >= 12) return;
            EditText optInput = new EditText(this);
            optInput.setHint("+ Add");
            optInput.setBackgroundResource(R.drawable.bg_chat_input);
            optInput.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
            optInput.setTextSize(14.5f);
            optInput.setSingleLine(true);
            optInput.setTextColor(getResources().getColor(R.color.textPrimary));
            optInput.setHintTextColor(getResources().getColor(R.color.textSecondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            optInput.setLayoutParams(lp);
            optionInputs.add(optInput);
            optionsContainer.addView(optInput);
        };
        addOptionRow.run();
        addOptionRow.run();

        TextView btnAddMoreOption = new TextView(this);
        btnAddMoreOption.setText("➕ Add option");
        btnAddMoreOption.setTextSize(13.5f);
        btnAddMoreOption.setTypeface(null, android.graphics.Typeface.BOLD);
        btnAddMoreOption.setTextColor(android.graphics.Color.parseColor("#00A884"));
        btnAddMoreOption.setPadding(0, (int) (4 * dp), 0, (int) (10 * dp));
        btnAddMoreOption.setOnClickListener(v -> addOptionRow.run());
        root.addView(btnAddMoreOption);

        // WhatsApp "Allow multiple answers" switch row (Defaults to FALSE -> single choice!)
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, (int) (6 * dp), 0, (int) (14 * dp));

        TextView tvMultiLabel = new TextView(this);
        tvMultiLabel.setLayoutParams(new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvMultiLabel.setText("Allow multiple answers");
        tvMultiLabel.setTextSize(15f);
        tvMultiLabel.setTextColor(getResources().getColor(R.color.textPrimary));
        switchRow.addView(tvMultiLabel);

        androidx.appcompat.widget.SwitchCompat switchAllowMultiple = new androidx.appcompat.widget.SwitchCompat(this);
        switchAllowMultiple.setChecked(false);
        switchRow.addView(switchAllowMultiple);
        root.addView(switchRow);

        // Send Poll Button
        TextView btnSendPoll = new TextView(this);
        btnSendPoll.setText("Send Poll");
        btnSendPoll.setGravity(android.view.Gravity.CENTER);
        btnSendPoll.setTextSize(15.5f);
        btnSendPoll.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSendPoll.setTextColor(android.graphics.Color.WHITE);
        android.graphics.drawable.GradientDrawable sendBg = new android.graphics.drawable.GradientDrawable();
        sendBg.setCornerRadius(24 * dp);
        sendBg.setColor(android.graphics.Color.parseColor("#00A884"));
        btnSendPoll.setBackground(sendBg);
        btnSendPoll.setLayoutParams(new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * dp)));

        btnSendPoll.setOnClickListener(v -> {
            String q = qInput.getText().toString().trim();
            List<String> validOpts = new ArrayList<>();
            for (EditText et : optionInputs) {
                String val = et.getText().toString().trim();
                if (!val.isEmpty()) validOpts.add(val);
            }
            if (q.isEmpty() || validOpts.size() < 2) {
                Toast.makeText(this, "Please enter a question and at least 2 options", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                org.json.JSONObject poll = new org.json.JSONObject();
                poll.put("question", q);
                poll.put("allowMultiple", switchAllowMultiple.isChecked());
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String optStr : validOpts) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("text", optStr);
                    o.put("votes", 0);
                    o.put("voters", new org.json.JSONArray());
                    arr.put(o);
                }
                poll.put("options", arr);

                Message pollMsg = new Message(UUID.randomUUID().toString(), currentUserId, recipientId, q, Timestamp.now(), "poll");
                pollMsg.setPollJson(poll.toString());
                pollMsg.setSenderName(com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName());
                if (currentVanishTimer > 0) {
                    long expiresMillis = System.currentTimeMillis() + currentVanishTimer;
                    pollMsg.setExpiresAt(new Timestamp(new Date(expiresMillis)));
                }
                com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(currentLocalChat, recipientId, pollMsg);
                reloadMessagesFromLocalVault();
                sheet.dismiss();
            } catch (Exception ignored) {}
        });
        root.addView(btnSendPoll);

        sheet.setContentView(root);
        sheet.show();
    }

    /**
     * Direct inline WhatsApp poll option vote handler.
     * - Casts or toggles the current user's vote immediately without opening any dialog.
     * - Enforces single-choice when allowMultiple == false (removing the user's vote from all other options).
     * - Prevents a single user from voting multiple times on the same option.
     */
    private void handleInlinePollOptionVote(Message message, int optionIndex) {
        if (message == null || message.getPollJson() == null) return;
        try {
            org.json.JSONObject poll = new org.json.JSONObject(message.getPollJson());
            boolean allowMultiple = poll.optBoolean("allowMultiple", false);
            org.json.JSONArray opts = poll.optJSONArray("options");
            if (opts == null || optionIndex < 0 || optionIndex >= opts.length()) return;

            String voterId = (currentUserId != null && !currentUserId.isEmpty()) ? currentUserId : "local_user";

            // Ensure every option has a normalized "voters" JSONArray
            for (int i = 0; i < opts.length(); i++) {
                org.json.JSONObject opt = opts.getJSONObject(i);
                org.json.JSONArray voters = opt.optJSONArray("voters");
                if (voters == null) {
                    voters = new org.json.JSONArray();
                    opt.put("voters", voters);
                }
            }

            org.json.JSONObject targetOpt = opts.getJSONObject(optionIndex);
            org.json.JSONArray targetVoters = targetOpt.getJSONArray("voters");

            boolean alreadyVotedTarget = false;
            for (int i = 0; i < targetVoters.length(); i++) {
                if (voterId.equals(targetVoters.optString(i))) {
                    alreadyVotedTarget = true;
                    break;
                }
            }

            if (alreadyVotedTarget) {
                // Toggle OFF: remove voterId from target option
                org.json.JSONArray updatedTargetVoters = new org.json.JSONArray();
                for (int i = 0; i < targetVoters.length(); i++) {
                    String id = targetVoters.optString(i);
                    if (!voterId.equals(id)) {
                        updatedTargetVoters.put(id);
                    }
                }
                targetOpt.put("voters", updatedTargetVoters);
            } else {
                // If single-choice mode (!allowMultiple), remove voterId from ALL other options first!
                if (!allowMultiple) {
                    for (int i = 0; i < opts.length(); i++) {
                        org.json.JSONObject otherOpt = opts.getJSONObject(i);
                        org.json.JSONArray otherVoters = otherOpt.getJSONArray("voters");
                        org.json.JSONArray cleaned = new org.json.JSONArray();
                        for (int j = 0; j < otherVoters.length(); j++) {
                            String id = otherVoters.optString(j);
                            if (!voterId.equals(id)) {
                                cleaned.put(id);
                            }
                        }
                        otherOpt.put("voters", cleaned);
                    }
                }
                // Add voterId to target option
                org.json.JSONArray refreshedTarget = targetOpt.getJSONArray("voters");
                refreshedTarget.put(voterId);
            }

            // Strictly sync "votes" count to the unique voters count for every option
            for (int i = 0; i < opts.length(); i++) {
                org.json.JSONObject opt = opts.getJSONObject(i);
                org.json.JSONArray voters = opt.getJSONArray("voters");
                opt.put("votes", voters.length());
            }

            String updatedPollJson = poll.toString();
            message.setPollJson(updatedPollJson);
            if (messagesRecyclerView != null) {
                messagesRecyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
            com.m3.veil.manager.VeilMessagesController.getInstance(this)
                    .sendPollVote(chatId, recipientId, message.getId(), updatedPollJson);
            reloadMessagesFromLocalVault();
        } catch (Exception ignored) {}
    }

    /**
     * Opens the WhatsApp-style "Poll details" BottomSheet when tapping "View votes" at the bottom of a poll bubble.
     */
    private void showPollVotesDetailsSheet(Message message) {
        if (message == null || message.getPollJson() == null) return;
        try {
            org.json.JSONObject poll = new org.json.JSONObject(message.getPollJson());
            String q = poll.optString("question", "Poll");
            org.json.JSONArray opts = poll.optJSONArray("options");
            if (opts == null) return;

            BottomSheetDialog sheet = new BottomSheetDialog(this);
            float dp = getResources().getDisplayMetrics().density;

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.bg_bottom_sheet);
            int pad = (int) (20 * dp);
            root.setPadding(pad, (int) (16 * dp), pad, pad);

            TextView tvHeader = new TextView(this);
            tvHeader.setText("Poll details");
            tvHeader.setTextSize(14f);
            tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            tvHeader.setTextColor(getResources().getColor(R.color.textSecondary));
            root.addView(tvHeader);

            TextView tvQuestion = new TextView(this);
            tvQuestion.setText(q);
            tvQuestion.setTextSize(19f);
            tvQuestion.setTypeface(null, android.graphics.Typeface.BOLD);
            tvQuestion.setTextColor(getResources().getColor(R.color.textPrimary));
            tvQuestion.setPadding(0, (int) (4 * dp), 0, (int) (14 * dp));
            root.addView(tvQuestion);

            for (int i = 0; i < opts.length(); i++) {
                org.json.JSONObject o = opts.getJSONObject(i);
                String optText = o.optString("text", "Option " + (i + 1));
                org.json.JSONArray voters = o.optJSONArray("voters");
                int votes = voters != null ? voters.length() : o.optInt("votes", 0);

                LinearLayout optBox = new LinearLayout(this);
                optBox.setOrientation(LinearLayout.VERTICAL);
                optBox.setBackgroundResource(R.drawable.bg_doc_inner_card);
                optBox.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int) (8 * dp);
                optBox.setLayoutParams(lp);

                LinearLayout topRow = new LinearLayout(this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                TextView tvName = new TextView(this);
                tvName.setLayoutParams(new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                tvName.setText(optText);
                tvName.setTextSize(15f);
                tvName.setTypeface(null, android.graphics.Typeface.BOLD);
                tvName.setTextColor(getResources().getColor(R.color.textPrimary));
                topRow.addView(tvName);

                TextView tvCountBadge = new TextView(this);
                tvCountBadge.setText(votes + (votes == 1 ? " vote" : " votes"));
                tvCountBadge.setTextSize(12.5f);
                tvCountBadge.setTypeface(null, android.graphics.Typeface.BOLD);
                tvCountBadge.setTextColor(android.graphics.Color.parseColor("#00A884"));
                topRow.addView(tvCountBadge);
                optBox.addView(topRow);

                TextView tvVotersList = new TextView(this);
                tvVotersList.setTextSize(12.5f);
                tvVotersList.setPadding(0, (int) (4 * dp), 0, 0);
                tvVotersList.setTextColor(getResources().getColor(R.color.textSecondary));
                if (voters != null && voters.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int vIdx = 0; vIdx < voters.length(); vIdx++) {
                        String uid = voters.optString(vIdx);
                        if (sb.length() > 0) sb.append(" • ");
                        if (uid.equals(currentUserId) || "local_user".equals(uid)) {
                            sb.append("✓ You");
                        } else {
                            sb.append("✓ ").append(recipientName != null ? recipientName : "Peer");
                        }
                    }
                    tvVotersList.setText(sb.toString());
                } else {
                    tvVotersList.setText("No votes yet");
                }
                optBox.addView(tvVotersList);

                root.addView(optBox);
            }

            sheet.setContentView(root);
            sheet.show();
        } catch (Exception ignored) {}
    }

    private void showDoodlePadDialog() {
        final int sizePx = (int) (300 * getResources().getDisplayMetrics().density);
        final Bitmap doodleBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        final android.graphics.Canvas doodleCanvas = new android.graphics.Canvas(doodleBitmap);
        doodleCanvas.drawColor(0xFF121318);

        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF00E5FF);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(10f);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);

        final android.graphics.Path path = new android.graphics.Path();

        View padView = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawBitmap(doodleBitmap, 0, 0, null);
                canvas.drawPath(path, paint);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                float x = event.getX();
                float y = event.getY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        path.moveTo(x, y);
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        path.lineTo(x, y);
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_UP:
                        doodleCanvas.drawPath(path, paint);
                        path.reset();
                        invalidate();
                        return true;
                }
                return false;
            }
        };
        padView.setLayoutParams(new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, sizePx));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(padView);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(android.view.Gravity.CENTER);
        int[] colors = {0xFF00E5FF, 0xFFFF4081, 0xFF76FF03, 0xFFFFD740, 0xFFFFFFFF};
        String[] cNames = {"Cyan", "Pink", "Lime", "Gold", "White"};
        for (int i = 0; i < colors.length; i++) {
            final int c = colors[i];
            TextView chip = new TextView(this);
            chip.setText(cNames[i]);
            chip.setTextColor(c);
            chip.setPadding(24, 16, 24, 16);
            chip.setOnClickListener(v -> paint.setColor(c));
            colorRow.addView(chip);
        }
        wrapper.addView(colorRow);

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (16 * getResources().getDisplayMetrics().density);
        int padV = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🎨 Anonymous P2P Doodle Pad");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (10 * getResources().getDisplayMetrics().density));
        root.addView(tvTitle);

        root.addView(wrapper);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);

        MaterialButton btnClear = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnClear.setText("Clear");
        btnClear.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clLp.rightMargin = (int) (6 * getResources().getDisplayMetrics().density);
        btnClear.setLayoutParams(clLp);
        btnClear.setOnClickListener(v -> {
            doodleCanvas.drawColor(0xFF121318);
            padView.invalidate();
        });
        btnRow.addView(btnClear);

        MaterialButton btnSend = new MaterialButton(this);
        btnSend.setText("Send Sketch");
        btnSend.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        btnSend.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
        btnSend.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams sndLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        sndLp.leftMargin = (int) (6 * getResources().getDisplayMetrics().density);
        btnSend.setLayoutParams(sndLp);
        btnSend.setOnClickListener(v -> {
            try {
                File sketchFile = new File(getExternalCacheDir(), "doodle_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(sketchFile)) {
                    doodleBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                }
                uploadAndSendMedia(sketchFile, "image", "🎨 Anonymous Doodle");
                sheet.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "Could not send doodle", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSend);

        root.addView(btnRow);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showVoiceFilterDialog() {
        String[] labels = {"🎙️ Normal Voice", "🤖 Robot Vocoder", "🕵️ Deep Anonymous Mask", "🐿️ Helium / Chipmunk"};
        String[] values = {"normal", "robot", "deep", "chipmunk"};

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🎙️ Voice Note Mask Filter");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (16 * getResources().getDisplayMetrics().density));
        root.addView(tvTitle);

        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            final String lab = labels[i];
            boolean selected = val.equals(activeVoiceFilter);

            TextView row = new TextView(this);
            row.setText((selected ? "✓  " : "    ") + lab);
            row.setTextColor(selected ? 0xFF6366F1 : ContextCompat.getColor(this, R.color.textPrimary));
            row.setTextSize(16);
            row.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                activeVoiceFilter = val;
                updateVoiceMaskChipUI();
                Toast.makeText(this, "Voice filter set to: " + lab, Toast.LENGTH_SHORT).show();
                sheet.dismiss();
            });
            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void sendTruthOrDarePrompt() {
        String prompt = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getRandomTruthOrDarePrompt();
        sendMessageWithType("text", prompt);
    }

    private void showEditMessageDialog(Message message) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        layout.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("✏️ Edit Message");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        layout.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Edits are cryptographically distributed to peer nodes in this chat thread.");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));
        layout.addView(tvSub);

        EditText editInput = new EditText(this);
        editInput.setText(message.getText());
        editInput.setSelection(editInput.getText().length());
        editInput.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        editInput.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        editInput.setBackgroundResource(R.drawable.bg_search_input);
        int inPad = (int) (14 * getResources().getDisplayMetrics().density);
        editInput.setPadding(inPad, inPad, inPad, inPad);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = (int) (20 * getResources().getDisplayMetrics().density);
        editInput.setLayoutParams(inLp);
        layout.addView(editInput);

        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText("Save Changes");
        btnSave.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
        btnSave.setTextColor(0xFFFFFFFF);
        layout.addView(btnSave);

        btnSave.setOnClickListener(v -> {
            String updated = editInput.getText().toString().trim();
            if (!updated.isEmpty()) {
                com.m3.veil.manager.VeilMessagesController.getInstance(this)
                        .editMessage(chatId, message.getId(), updated);
                reloadMessagesFromLocalVault();
                sheet.dismiss();
            }
        });

        sheet.setContentView(layout);
        sheet.show();
    }

    private void showTranslateAndSmartReplyDialog(Message message) {
        String text = message.getText() != null ? message.getText() : "";
        List<String> suggestions = com.m3.veil.utils.VeilLocalIntelligenceEngine.getInstance().generateSmartReplies(text);

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("⚡ Smart Actions & Offline Intelligence");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (16 * getResources().getDisplayMetrics().density));
        root.addView(tvTitle);

        // Feature #46: Offline In-Place Translation
        if (message.getTranslatedText() != null && !message.getTranslatedText().isEmpty()) {
            addSmartActionItem(root, "🔄 Revert Translation to Original", v -> {
                sheet.dismiss();
                message.setTranslatedText(null);
                message.setTranslatedLang(null);
                if (messagesAdapter != null) messagesAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Translation cleared", Toast.LENGTH_SHORT).show();
            });
        }
        addSmartActionItem(root, "🌐 Offline Translate (#46 Zero-Cloud)", v -> {
            sheet.dismiss();
            showOfflineTranslateLanguagePicker(message);
        });

        String starLabel = message.isStarred() ? "⭐ Unstar Message" : "⭐ Star / Bookmark Message";
        addSmartActionItem(root, starLabel, v -> {
            sheet.dismiss();
            message.setStarred(!message.isStarred());
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveMessage(chatId, message);
            reloadMessagesFromLocalVault();
        });

        addSmartActionItem(root, "🌐 Markdown Formatting (*bold*, _italic_, `code`)", v -> {
            sheet.dismiss();
            Toast.makeText(this, "Tip: Wrap words in *asterisks* for bold, _underscores_ for italic, or `backticks` for code!", Toast.LENGTH_LONG).show();
        });

        if (suggestions != null) {
            for (String reply : suggestions) {
                addSmartActionItem(root, "⚡ Quick Reply: " + reply, v -> {
                    sheet.dismiss();
                    replyToMessage(message);
                    messageEditText.setText(reply);
                    messageEditText.setSelection(reply.length());
                    messageEditText.requestFocus();
                });
            }
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void showOfflineTranslateLanguagePicker(Message message) {
        if (message == null || message.getText() == null) return;
        String[] langNames = {
                "English (EN)",
                "Spanish (Español)",
                "French (Français)",
                "German (Deutsch)",
                "Russian (Русский)",
                "Chinese (中文)",
                "Arabic (العربية)",
                "Japanese (日本語)",
                "Hindi (हिन्दी)",
                "Italian (Italiano)",
                "Portuguese (Português)"
        };
        String[] langCodes = {"en", "es", "fr", "de", "ru", "zh", "ar", "ja", "hi", "it", "pt"};

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🌐 Translate In-Place (Zero-Cloud #46)");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Runs 100% locally on-device. Zero data sent to cloud.");
        tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSubtitle.setTextSize(13);
        tvSubtitle.setPadding(0, 0, 0, (int) (16 * getResources().getDisplayMetrics().density));
        root.addView(tvSubtitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < langNames.length; i++) {
            final String code = langCodes[i];
            final String name = langNames[i];
            addSmartActionItem(list, "Translate to " + name, v -> {
                sheet.dismiss();
                com.m3.veil.utils.VeilLocalIntelligenceEngine.TranslationResult res =
                        com.m3.veil.utils.VeilLocalIntelligenceEngine.getInstance()
                                .translateOffline(message.getText(), code);
                message.setTranslatedText(res != null ? res.translatedText : message.getText());
                message.setTranslatedLang(code.toUpperCase(Locale.ROOT));
                if (messagesAdapter != null) {
                    messagesAdapter.notifyDataSetChanged();
                }
                Toast.makeText(this, "Translated to " + name + " (On-Device)", Toast.LENGTH_SHORT).show();
            });
        }
        scroll.addView(list);
        root.addView(scroll);

        sheet.setContentView(root);
        sheet.show();
    }

    private void addSmartActionItem(LinearLayout parent, String title, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tv.setTextSize(15);
        tv.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(listener);

        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(0x1A888888);

        parent.addView(tv);
        parent.addView(div);
    }

    // ---------------------------------------------------------------------------------------------
    // In-Chat P2P Minigames Drawer (#219)
    // ---------------------------------------------------------------------------------------------
    private void showP2PMinigamesDrawer() {
        com.m3.veil.ui.chat.sheets.P2PGameDrawerBottomSheet sheet =
                com.m3.veil.ui.chat.sheets.P2PGameDrawerBottomSheet.newInstance(recipientId, chatId);
        sheet.setOnGamePromptSendListener(prompt -> {
            if (messageEditText != null) {
                messageEditText.setText(prompt);
                sendMessage();
            }
        });
        sheet.show(getSupportFragmentManager(), "P2PGameDrawerBottomSheet");
    }

    // ---------------------------------------------------------------------------------------------
    // Time-Travel Chat Replay Controller (#210)
    // ---------------------------------------------------------------------------------------------
    private boolean isReplayActive = false;
    private boolean isReplayPaused = false;
    private int replaySpeedMultiplier = 1;
    private int currentReplayIndex = 0;
    private List<Message> fullChatHistorySnapshot = null;
    private final Handler replayHandler = new Handler(Looper.getMainLooper());
    private Runnable replayStepRunnable;

    private View layoutReplayControls;
    private TextView tvReplayStatus;
    private Button btnReplayPlayPause;
    private Button btnReplaySpeed;
    private Button btnReplayExit;

    private void initReplayControls() {
        layoutReplayControls = findViewById(R.id.layoutReplayControls);
        tvReplayStatus = findViewById(R.id.tvReplayStatus);
        btnReplayPlayPause = findViewById(R.id.btnReplayPlayPause);
        btnReplaySpeed = findViewById(R.id.btnReplaySpeed);
        btnReplayExit = findViewById(R.id.btnReplayExit);

        if (btnReplayPlayPause != null) {
            btnReplayPlayPause.setOnClickListener(v -> {
                isReplayPaused = !isReplayPaused;
                btnReplayPlayPause.setText(isReplayPaused ? "▶ Resume" : "⏸ Pause");
                if (!isReplayPaused) {
                    postNextReplayMessage();
                }
            });
        }
        if (btnReplaySpeed != null) {
            btnReplaySpeed.setOnClickListener(v -> {
                if (replaySpeedMultiplier == 1) replaySpeedMultiplier = 2;
                else if (replaySpeedMultiplier == 2) replaySpeedMultiplier = 4;
                else replaySpeedMultiplier = 1;
                btnReplaySpeed.setText(replaySpeedMultiplier + "x");
            });
        }
        if (btnReplayExit != null) {
            btnReplayExit.setOnClickListener(v -> stopChatReplay(true));
        }
    }

    private void startTimeTravelChatReplay() {
        if (messageList == null || messageList.isEmpty()) {
            Toast.makeText(this, "No messages available to replay in this chat", Toast.LENGTH_SHORT).show();
            return;
        }

        fullChatHistorySnapshot = new ArrayList<>(messageList);
        messageList.clear();
        if (messagesAdapter != null) {
            messagesAdapter.notifyDataSetChanged();
        }

        isReplayActive = true;
        isReplayPaused = false;
        replaySpeedMultiplier = 1;
        currentReplayIndex = 0;

        if (layoutReplayControls != null) {
            layoutReplayControls.setVisibility(View.VISIBLE);
            if (btnReplayPlayPause != null) btnReplayPlayPause.setText("⏸ Pause");
            if (btnReplaySpeed != null) btnReplaySpeed.setText("1x");
            if (tvReplayStatus != null) {
                tvReplayStatus.setText("⏳ Replaying 0/" + fullChatHistorySnapshot.size());
            }
        }

        Toast.makeText(this, "⏳ Time-Travel Replay Started (#210)", Toast.LENGTH_SHORT).show();
        postNextReplayMessage();
    }

    private void postNextReplayMessage() {
        if (!isReplayActive || isReplayPaused || fullChatHistorySnapshot == null) return;

        if (currentReplayIndex >= fullChatHistorySnapshot.size()) {
            if (tvReplayStatus != null) {
                tvReplayStatus.setText("✓ Replay Complete (" + fullChatHistorySnapshot.size() + " msgs)");
            }
            Toast.makeText(this, "🎉 Time-Travel Chat Replay Finished!", Toast.LENGTH_SHORT).show();
            return;
        }

        Message msg = fullChatHistorySnapshot.get(currentReplayIndex);
        boolean isPeer = msg.getSenderId() != null && !msg.getSenderId().equals(currentUserId);

        long typingDelay = Math.min(1000, Math.max(250, msg.getText() != null ? msg.getText().length() * 20 : 350)) / replaySpeedMultiplier;

        if (isPeer && messagesAdapter != null) {
            messagesAdapter.setTyping(true);
        }

        replayStepRunnable = () -> {
            if (!isReplayActive || isReplayPaused) return;

            if (isPeer && messagesAdapter != null) {
                messagesAdapter.setTyping(false);
            }

            messageList.add(msg);
            if (messagesAdapter != null) {
                messagesAdapter.notifyItemInserted(messageList.size());
                messagesRecyclerView.scrollToPosition(messageList.size() - 1);
            }

            currentReplayIndex++;
            if (tvReplayStatus != null) {
                tvReplayStatus.setText("⏳ Replaying " + currentReplayIndex + "/" + fullChatHistorySnapshot.size() + " (" + replaySpeedMultiplier + "x)");
            }

            long pauseBetweenMs = 500 / replaySpeedMultiplier;
            replayHandler.postDelayed(this::postNextReplayMessage, pauseBetweenMs);
        };

        replayHandler.postDelayed(replayStepRunnable, typingDelay);
    }

    private void stopChatReplay(boolean restoreHistory) {
        isReplayActive = false;
        isReplayPaused = false;
        replayHandler.removeCallbacksAndMessages(null);

        if (layoutReplayControls != null) {
            layoutReplayControls.setVisibility(View.GONE);
        }
        if (messagesAdapter != null) {
            messagesAdapter.setTyping(false);
        }

        if (restoreHistory && fullChatHistorySnapshot != null) {
            messageList.clear();
            messageList.addAll(fullChatHistorySnapshot);
            if (messagesAdapter != null) {
                messagesAdapter.notifyDataSetChanged();
                if (!messageList.isEmpty()) {
                    messagesRecyclerView.scrollToPosition(messagesAdapter.getItemCount() - 1);
                }
            }
        }
    }

    private void showChatWallpaperDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this, R.style.ModernBottomSheetDialogTheme);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet_modern);
        int padH = (int) (22 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (38 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🌈 Chat Wallpaper");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (12 * getResources().getDisplayMetrics().density));
        root.addView(tvTitle);

        // Pick Photo from Gallery Button
        com.google.android.material.button.MaterialButton btnGallery = new com.google.android.material.button.MaterialButton(this);
        btnGallery.setText("🖼️ Choose from Gallery (Crop 9:16)");
        btnGallery.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        btnGallery.setOnClickListener(v -> {
            sheet.dismiss();
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, "Choose Chat Wallpaper"), REQ_PICK_WALLPAPER);
        });
        root.addView(btnGallery);

        // Reset to Theme Default Button
        com.google.android.material.button.MaterialButton btnDefault = new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDefault.setText("✨ Use Default Theme Wallpaper");
        btnDefault.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams defLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        defLp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        defLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        btnDefault.setLayoutParams(defLp);
        btnDefault.setOnClickListener(v -> {
            sheet.dismiss();
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, "");
            if (currentLocalChat != null) currentLocalChat.setWallpaperColor("");
            applyChatWallpaper();
            Toast.makeText(this, "Wallpaper reset to theme default", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnDefault);

        // Solid & Gradient Preset Rows
        String[] sectionTitles = {
                "Light Presets (Clean & Soft):",
                "Dark Presets (Deep Obsidian):",
                "Gradient Presets:"
        };

        String[][] presetNames = {
                {"Warm Cream", "Mint Foam", "Pastel Sky", "Rose Whisper", "Clean Slate"},
                {"Midnight Abyss", "Cyber Indigo", "Emerald Matrix", "Crimson Velvet", "Deep Ocean"},
                {"Sunset Horizon", "Oceanic Aqua", "Cyber Aurora", "Midnight Nebula"}
        };

        String[][] presetValues = {
                {"#FAF7F2", "#F0FDF4", "#F0F9FF", "#FFF1F2", "#F8FAFC"},
                {"#0D1117", "#121026", "#091E1A", "#240B15", "#0A192F"},
                {"gradient:#FF512F:#DD2476", "gradient:#00C9FF:#92FE9D", "gradient:#8A2387:#E94057:#F27121", "gradient:#0F2027:#203A43:#2C5364"}
        };

        for (int s = 0; s < sectionTitles.length; s++) {
            TextView secTitle = new TextView(this);
            secTitle.setText(sectionTitles[s]);
            secTitle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            secTitle.setTextSize(12.5f);
            secTitle.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, (int) (8 * getResources().getDisplayMetrics().density));
            root.addView(secTitle);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);
            row.setLayoutParams(rowLp);

            for (int p = 0; p < presetNames[s].length; p++) {
                final String val = presetValues[s][p];
                final String name = presetNames[s][p];

                View swatch = new View(this);
                int size = (int) (38 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(size, size);
                swLp.rightMargin = (int) (10 * getResources().getDisplayMetrics().density);
                swatch.setLayoutParams(swLp);

                if (val.startsWith("gradient:")) {
                    String[] parts = val.substring("gradient:".length()).split(":");
                    int[] colors = new int[parts.length];
                    for (int c = 0; c < parts.length; c++) colors[c] = Color.parseColor(parts[c]);
                    swatch.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors));
                } else {
                    swatch.setBackgroundColor(Color.parseColor(val));
                }

                swatch.setClickable(true);
                swatch.setFocusable(true);
                swatch.setOnClickListener(v -> {
                    sheet.dismiss();
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, val);
                    if (currentLocalChat != null) currentLocalChat.setWallpaperColor(val);
                    applyChatWallpaper();
                    Toast.makeText(this, "Wallpaper: " + name, Toast.LENGTH_SHORT).show();
                });
                row.addView(swatch);
            }
            root.addView(row);
        }

        scroll.addView(root);
        sheet.setContentView(scroll);
        sheet.show();
    }

    private void showSetChatLockDialog() {
        boolean isCurrentlyLocked = currentLocalChat != null
                && currentLocalChat.getChatLockPin() != null
                && !currentLocalChat.getChatLockPin().isEmpty();

        if (isCurrentlyLocked) {
            // Require current PIN verification before modifying or removing
            BottomSheetDialog verifySheet = new BottomSheetDialog(this);
            View vView = getLayoutInflater().inflate(R.layout.dialog_pin_auth, null);
            verifySheet.setContentView(vView);

            TextView tvTitle = vView.findViewById(R.id.tvPinDialogTitle);
            TextView tvSub = vView.findViewById(R.id.tvPinDialogSubtitle);
            com.google.android.material.textfield.TextInputLayout inputLayout = vView.findViewById(R.id.pinInputLayout);
            com.google.android.material.textfield.TextInputEditText etPin = vView.findViewById(R.id.etPin);
            View btnCancel = vView.findViewById(R.id.btnCancelPin);
            View btnConfirm = vView.findViewById(R.id.btnConfirmPin);

            if (tvTitle != null) tvTitle.setText("🔒 Verify Current PIN");
            if (tvSub != null) tvSub.setText("Enter current 4-digit PIN to modify or remove Chat Lock:");
            if (btnCancel != null) btnCancel.setOnClickListener(v -> verifySheet.dismiss());

            if (btnConfirm != null && etPin != null) {
                btnConfirm.setOnClickListener(v -> {
                    String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
                    if (currentLocalChat.getChatLockPin().equals(entered)) {
                        verifySheet.dismiss();
                        presentSetChatLockDialog(true);
                    } else {
                        if (inputLayout != null) inputLayout.setError("Incorrect Chat PIN");
                        else Toast.makeText(this, "Incorrect Chat PIN", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            verifySheet.show();
        } else {
            presentSetChatLockDialog(false);
        }
    }

    private void presentSetChatLockDialog(boolean hasExistingPin) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        layout.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(hasExistingPin ? "🔒 Change or Remove Chat PIN" : "🔒 Set 4-Digit Chat PIN");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        layout.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(hasExistingPin
                ? "Enter new 4-digit PIN, or tap Remove PIN to unlock completely."
                : "Set a 4-digit PIN required to open this conversation in your local encrypted vault.");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));
        layout.addView(tvSub);

        EditText pinInput = new EditText(this);
        pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("Enter 4-digit PIN");
        pinInput.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        pinInput.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        pinInput.setBackgroundResource(R.drawable.bg_search_input);
        int inPad = (int) (14 * getResources().getDisplayMetrics().density);
        pinInput.setPadding(inPad, inPad, inPad, inPad);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        pinInput.setLayoutParams(inLp);
        layout.addView(pinInput);

        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText(hasExistingPin ? "Save New PIN" : "Lock Chat with PIN");
        btnSave.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
        btnSave.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        btnSave.setLayoutParams(saveLp);
        layout.addView(btnSave);

        btnSave.setOnClickListener(v -> {
            String pin = pinInput.getText().toString().trim();
            if (pin.length() < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentLocalChat != null) {
                currentLocalChat.setChatLockPin(pin);
            }
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatLockPin(chatId, pin);
            sheet.dismiss();
            Toast.makeText(this, "🔒 Chat locked with PIN", Toast.LENGTH_SHORT).show();
        });

        if (hasExistingPin) {
            MaterialButton btnRemove = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnRemove.setText("🔓 Remove PIN Lock");
            btnRemove.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
            btnRemove.setTextColor(ContextCompat.getColor(this, R.color.colorError));
            btnRemove.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFEF4444));
            LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnRemove.setLayoutParams(remLp);
            btnRemove.setOnClickListener(v -> {
                if (currentLocalChat != null) {
                    currentLocalChat.setChatLockPin(null);
                }
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatLockPin(chatId, null);
                sheet.dismiss();
                Toast.makeText(this, "🔓 Chat lock removed", Toast.LENGTH_SHORT).show();
            });
            layout.addView(btnRemove);
        }

        sheet.setContentView(layout);
        sheet.show();
    }

    private void toggleSecretVaultChat() {
        if (currentLocalChat == null) return;
        boolean next = !currentLocalChat.isSecret();
        currentLocalChat.setSecret(next);
        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatSecretStatus(chatId, next);
        Toast.makeText(this, next ? "🕵️ Moved to Secret Vault (accessible via Secret Vault chip or Locked chats folder)" : "Moved back to main chats", Toast.LENGTH_LONG).show();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // GROUP EVENTS & CALENDAR (#68, #225)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void showGroupEventsCalendar() {
        EventsCalendarBottomSheet sheet = EventsCalendarBottomSheet.newInstance(chatId, recipientName != null ? recipientName : "Group");
        sheet.show(getSupportFragmentManager(), "EventsCalendarBottomSheet");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // SECRET SUB-GROUPS & TOPICS (#71)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void showSubgroupsDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📁 Secret Sub-Groups & Topics");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Zero-trace scoped channels inside this group (#71)");
        tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSubtitle.setTextSize(13);
        tvSubtitle.setPadding(0, 4, 0, (int) (14 * getResources().getDisplayMetrics().density));
        root.addView(tvSubtitle);

        com.google.android.material.button.MaterialButton btnCreateTopic = new com.google.android.material.button.MaterialButton(this);
        btnCreateTopic.setText("+ Create Sub-Group / Topic");
        btnCreateTopic.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnCreateTopic.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnCreateTopic.setTextColor(Color.WHITE);
        btnCreateTopic.setOnClickListener(v -> {
            sheet.dismiss();
            showCreateSubgroupDialog();
        });
        root.addView(btnCreateTopic);

        // Reset to all messages option
        if (currentSubgroupId != null) {
            TextView tvReset = new TextView(this);
            tvReset.setText("🔄 View All Group Messages (Exit #" + currentSubgroupTitle + ")");
            tvReset.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            tvReset.setTextSize(14);
            tvReset.setTypeface(null, Typeface.BOLD);
            tvReset.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (8 * getResources().getDisplayMetrics().density));
            tvReset.setOnClickListener(v -> {
                currentSubgroupId = null;
                currentSubgroupTitle = null;
                updatePresenceAndToolbarStatus();
                sheet.dismiss();
                Toast.makeText(this, "Showing all group topics", Toast.LENGTH_SHORT).show();
            });
            root.addView(tvReset);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);

        List<com.m3.veil.database.EncryptedChatDatabase.GroupSubgroup> subgroups =
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getSubgroupsForGroup(chatId);

        if (subgroups.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No sub-groups created yet. Tap the button above to start an encrypted topic thread.");
            emptyTv.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            emptyTv.setTextSize(13.5f);
            emptyTv.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 0);
            listContainer.addView(emptyTv);
        } else {
            for (com.m3.veil.database.EncryptedChatDatabase.GroupSubgroup sg : subgroups) {
                com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
                card.setRadius(24f);
                card.setCardElevation(0f);
                card.setStrokeWidth(2);
                boolean isActive = sg.subgroupId.equals(currentSubgroupId);
                card.setStrokeColor(isActive ? ContextCompat.getColor(this, R.color.colorPrimary) : Color.parseColor("#263238"));
                card.setCardBackgroundColor(Color.parseColor("#1A202C"));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
                card.setLayoutParams(cardLp);

                LinearLayout itemLayout = new LinearLayout(this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setPadding((int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density));

                TextView tvSgTitle = new TextView(this);
                String icon = sg.isSecret ? "🔒 #" : "💬 #";
                tvSgTitle.setText(icon + sg.title + (isActive ? " (Active)" : ""));
                tvSgTitle.setTextColor(isActive ? ContextCompat.getColor(this, R.color.colorPrimary) : Color.WHITE);
                tvSgTitle.setTextSize(15.5f);
                tvSgTitle.setTypeface(null, Typeface.BOLD);
                itemLayout.addView(tvSgTitle);

                if (sg.description != null && !sg.description.isEmpty()) {
                    TextView tvSgDesc = new TextView(this);
                    tvSgDesc.setText(sg.description);
                    tvSgDesc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                    tvSgDesc.setTextSize(13);
                    tvSgDesc.setPadding(0, 4, 0, 0);
                    itemLayout.addView(tvSgDesc);
                }

                card.addView(itemLayout);
                card.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (sg.isSecret && sg.inviteKey != null && !sg.inviteKey.isEmpty()) {
                        promptUnlockSecretSubgroup(sg);
                    } else {
                        currentSubgroupId = sg.subgroupId;
                        currentSubgroupTitle = sg.title;
                        updatePresenceAndToolbarStatus();
                        Toast.makeText(this, "Active topic: #" + sg.title, Toast.LENGTH_SHORT).show();
                    }
                });
                listContainer.addView(card);
            }
        }

        scroll.addView(listContainer);
        root.addView(scroll);
        sheet.setContentView(root);
        sheet.show();
    }

    private void promptUnlockSecretSubgroup(com.m3.veil.database.EncryptedChatDatabase.GroupSubgroup sg) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad * 2);

        TextView title = new TextView(this);
        title.setText("🔒 Secret Sub-Group Key Required");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView msg = new TextView(this);
        msg.setText("Enter the cryptographic secret invite key or passphrase to unlock topic #" + sg.title);
        msg.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        msg.setTextSize(13);
        msg.setPadding(0, 8, 0, 16);
        layout.addView(msg);

        EditText etKey = new EditText(this);
        etKey.setHint("Enter invite key / passphrase");
        etKey.setTextColor(Color.WHITE);
        etKey.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        layout.addView(etKey);

        com.google.android.material.button.MaterialButton btnUnlock = new com.google.android.material.button.MaterialButton(this);
        btnUnlock.setText("Unlock & Join Sub-Group");
        btnUnlock.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnUnlock.setOnClickListener(v -> {
            String input = etKey.getText().toString().trim();
            if (input.equals(sg.inviteKey)) {
                dialog.dismiss();
                currentSubgroupId = sg.subgroupId;
                currentSubgroupTitle = sg.title;
                updatePresenceAndToolbarStatus();
                Toast.makeText(this, "🔓 Sub-Group #" + sg.title + " unlocked!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Invalid invite key / passphrase", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnUnlock);
        dialog.setContentView(layout);
        dialog.show();
    }

    private void showCreateSubgroupDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad * 2);

        TextView title = new TextView(this);
        title.setText("✨ Create Secret Sub-Group / Topic");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        EditText etName = new EditText(this);
        etName.setHint("Topic Name (e.g. Announcements, Off-Topic)");
        etName.setTextColor(Color.WHITE);
        etName.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        layout.addView(etName);

        EditText etDesc = new EditText(this);
        etDesc.setHint("Description (optional)");
        etDesc.setTextColor(Color.WHITE);
        etDesc.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        layout.addView(etDesc);

        android.widget.CheckBox cbSecret = new android.widget.CheckBox(this);
        cbSecret.setText("🔒 Require Secret Invite Key / Passphrase");
        cbSecret.setTextColor(Color.WHITE);
        layout.addView(cbSecret);

        EditText etKey = new EditText(this);
        etKey.setHint("Secret Invite Key (e.g. veil-key-123)");
        etKey.setTextColor(Color.WHITE);
        etKey.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etKey.setVisibility(View.GONE);
        layout.addView(etKey);

        cbSecret.setOnCheckedChangeListener((btn, isChecked) -> {
            etKey.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        com.google.android.material.button.MaterialButton btnCreate = new com.google.android.material.button.MaterialButton(this);
        btnCreate.setText("Create & Sync to Group");
        btnCreate.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnCreate.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("Topic name required");
                return;
            }
            String desc = etDesc.getText().toString().trim();
            boolean isSecret = cbSecret.isChecked();
            String key = isSecret ? etKey.getText().toString().trim() : null;

            com.m3.veil.database.EncryptedChatDatabase.GroupSubgroup sg = new com.m3.veil.database.EncryptedChatDatabase.GroupSubgroup(
                    UUID.randomUUID().toString(),
                    chatId,
                    name,
                    desc,
                    key,
                    "MEMBER",
                    isSecret
            );
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveSubgroup(sg);

            // Sync packet to mesh
            try {
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("action", "subgroup_created");
                payload.put("groupId", chatId);
                payload.put("subgroupId", sg.subgroupId);
                payload.put("title", sg.title);
                payload.put("description", sg.description);
                payload.put("isSecret", sg.isSecret);
                com.m3.veil.webrtc.VeilPacket packet = new com.m3.veil.webrtc.VeilPacket(
                        com.m3.veil.webrtc.VeilPacket.LANE_CTRL,
                        com.m3.veil.webrtc.VeilPacket.TYPE_GROUP_SETTINGS,
                        payload.toString()
                );
                com.m3.veil.webrtc.WebRtcManager.getInstance(this).broadcastPacket(packet);
            } catch (Throwable ignored) {}

            dialog.dismiss();
            Toast.makeText(this, "Sub-group created!", Toast.LENGTH_SHORT).show();
            showSubgroupsDialog();
        });
        layout.addView(btnCreate);
        dialog.setContentView(layout);
        dialog.show();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // GROUP CUSTOM THEMES & WALLPAPER (#73)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void showGroupCustomThemesDialog() {
        String[] names = {
                "Default Night (#121212)",
                "Midnight Abyss (#0D1117)",
                "Cyber Indigo (#121026)",
                "Emerald Matrix (#091E1A)",
                "Crimson Velvet (#240B15)",
                "Royal Gold (#1F1905)",
                "Solar Amber (#2A1B0A)",
                "Deep Ocean (#081D2E)"
        };
        String[] hexes = {
                "#121212",
                "#0D1117",
                "#121026",
                "#091E1A",
                "#240B15",
                "#1F1905",
                "#2A1B0A",
                "#081D2E"
        };

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🎨 Group Themes & Wallpaper (#73)");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Select a theme to synchronize across all peers in this group");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, 4, 0, (int) (16 * getResources().getDisplayMetrics().density));
        root.addView(tvSub);

        for (int i = 0; i < names.length; i++) {
            final String hex = hexes[i];
            final String name = names[i];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, (int) (10 * getResources().getDisplayMetrics().density));

            View swatch = new View(this);
            int sDim = (int) (24 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(sDim, sDim);
            swatchLp.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
            swatch.setLayoutParams(swatchLp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(Color.parseColor(hex));
            gd.setStroke(2, Color.WHITE);
            swatch.setBackground(gd);
            row.addView(swatch);

            TextView tvName = new TextView(this);
            tvName.setText(name);
            tvName.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            tvName.setTextSize(15.5f);
            row.addView(tvName);

            row.setOnClickListener(v -> {
                sheet.dismiss();
                com.m3.veil.database.EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, hex);
                loadLocalChatSettings();

                // Broadcast theme sync packet to group peers
                try {
                    org.json.JSONObject payload = new org.json.JSONObject();
                    payload.put("action", "group_theme_sync");
                    payload.put("groupId", chatId);
                    payload.put("themeHex", hex);
                    payload.put("themeName", name);
                    com.m3.veil.webrtc.VeilPacket packet = new com.m3.veil.webrtc.VeilPacket(
                            com.m3.veil.webrtc.VeilPacket.LANE_CTRL,
                            com.m3.veil.webrtc.VeilPacket.TYPE_GROUP_SETTINGS,
                            payload.toString()
                    );
                    com.m3.veil.webrtc.WebRtcManager.getInstance(this).broadcastPacket(packet);
                } catch (Throwable ignored) {}

                Toast.makeText(this, "Group theme updated & synced across mesh!", Toast.LENGTH_SHORT).show();
            });
            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // GROUP ACHIEVEMENTS (#74)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void showGroupAchievementsDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(this);
        int handleW = (int) (36 * getResources().getDisplayMetrics().density);
        int handleH = (int) (4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🏆 Group Member Achievements (#74)");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Cryptographic badges unlocked via active participation in this group");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, 4, 0, (int) (14 * getResources().getDisplayMetrics().density));
        root.addView(tvSub);

        int msgCount = messageList != null ? messageList.size() : 0;
        int eventCount = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getEventsForGroup(chatId).size();
        int subgroupCount = com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getSubgroupsForGroup(chatId).size();

        String[][] badges = {
                {"👑 Founding Architect", "Created or joined as one of the inaugural peers", "true"},
                {"⚡ Mesh Relay Master", "Exchanged 20+ encrypted P2P frames", String.valueOf(msgCount >= 20)},
                {"🛡️ Cryptographic Guardian", "E2EE session verified with forward secrecy", "true"},
                {"🎙️ Voice Maverick", "Participated in live voice room or call", "true"},
                {"📅 Master Event Host", "Created or joined group events", String.valueOf(eventCount > 0)},
                {"📁 Topic Pioneer", "Created or unlocked group sub-topics", String.valueOf(subgroupCount > 0)},
                {"🔥 Conversation Catalyst", "Active contributor in group discussion", String.valueOf(msgCount >= 50)}
        };

        ScrollView scroll = new ScrollView(this);
        LinearLayout badgeList = new LinearLayout(this);
        badgeList.setOrientation(LinearLayout.VERTICAL);

        for (String[] b : badges) {
            String bTitle = b[0];
            String bDesc = b[1];
            boolean unlocked = Boolean.parseBoolean(b[2]);

            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
            card.setRadius(24f);
            card.setCardElevation(0f);
            card.setStrokeWidth(2);
            card.setStrokeColor(unlocked ? Color.parseColor("#FFD700") : Color.parseColor("#333333"));
            card.setCardBackgroundColor(unlocked ? Color.parseColor("#1C1E24") : Color.parseColor("#14161B"));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            card.setLayoutParams(cardLp);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding((int) (14 * getResources().getDisplayMetrics().density), (int) (10 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (10 * getResources().getDisplayMetrics().density));

            TextView tvBTitle = new TextView(this);
            tvBTitle.setText(bTitle + (unlocked ? "  [UNLOCKED ✅]" : "  [LOCKED 🔒]"));
            tvBTitle.setTextColor(unlocked ? Color.parseColor("#FFD700") : Color.GRAY);
            tvBTitle.setTextSize(15);
            tvBTitle.setTypeface(null, Typeface.BOLD);
            item.addView(tvBTitle);

            TextView tvBDesc = new TextView(this);
            tvBDesc.setText(bDesc);
            tvBDesc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            tvBDesc.setTextSize(12.5f);
            item.addView(tvBDesc);

            card.addView(item);
            badgeList.addView(card);
        }

        scroll.addView(badgeList);
        root.addView(scroll);
        sheet.setContentView(root);
        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // SCHEDULED MESSAGE / CHANNEL POST (#80)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void showScheduleMessageDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad * 2);

        TextView title = new TextView(this);
        title.setText("⏰ Schedule Message / Channel Post (#80)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Post will automatically dispatch via VeilSchedulerDaemon at scheduled time");
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, 4, 0, 16);
        layout.addView(subtitle);

        EditText etContent = new EditText(this);
        String prefill = messageEditText.getText().toString().trim();
        etContent.setText(prefill);
        etContent.setHint("Write message or channel post content...");
        etContent.setTextColor(Color.WHITE);
        etContent.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        layout.addView(etContent);

        final Calendar scheduleCal = Calendar.getInstance();
        scheduleCal.add(Calendar.HOUR_OF_DAY, 1);

        TextView tvTimeChosen = new TextView(this);
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale.getDefault());
        tvTimeChosen.setText("Scheduled for: " + sdf.format(scheduleCal.getTime()));
        tvTimeChosen.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        tvTimeChosen.setTextSize(14);
        tvTimeChosen.setPadding(0, 12, 0, 12);
        layout.addView(tvTimeChosen);

        com.google.android.material.button.MaterialButton btnPickTime = new com.google.android.material.button.MaterialButton(this);
        btnPickTime.setText("📅 Pick Date & Time");
        btnPickTime.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnPickTime.setOnClickListener(v -> {
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                scheduleCal.set(Calendar.YEAR, year);
                scheduleCal.set(Calendar.MONTH, month);
                scheduleCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                new TimePickerDialog(this, (view1, hourOfDay, minute) -> {
                    scheduleCal.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    scheduleCal.set(Calendar.MINUTE, minute);
                    scheduleCal.set(Calendar.SECOND, 0);
                    tvTimeChosen.setText("Scheduled for: " + sdf.format(scheduleCal.getTime()));
                }, scheduleCal.get(Calendar.HOUR_OF_DAY), scheduleCal.get(Calendar.MINUTE), true).show();
            }, scheduleCal.get(Calendar.YEAR), scheduleCal.get(Calendar.MONTH), scheduleCal.get(Calendar.DAY_OF_MONTH)).show();
        });
        layout.addView(btnPickTime);

        boolean isChannel = currentLocalChat != null && "channel".equalsIgnoreCase(currentLocalChat.getType());

        com.google.android.material.button.MaterialButton btnConfirm = new com.google.android.material.button.MaterialButton(this);
        btnConfirm.setText("Schedule Broadcast");
        btnConfirm.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnConfirm.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setOnClickListener(v -> {
            String text = etContent.getText().toString().trim();
            if (text.isEmpty()) {
                etContent.setError("Message text required");
                return;
            }
            long scheduledTime = scheduleCal.getTimeInMillis();
            if (scheduledTime <= System.currentTimeMillis()) {
                Toast.makeText(this, "Scheduled time must be in the future", Toast.LENGTH_SHORT).show();
                return;
            }

            String activePersonaName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
            String senderName = activePersonaName;
            String senderId = currentUserId;

            if (isChannel) {
                // Anonymous Channel Admin (#82)
                com.m3.veil.database.EncryptedChatDatabase.ChannelAdminKey cak =
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getChannelAdminKey(chatId);
                if (cak == null) {
                    cak = new com.m3.veil.database.EncryptedChatDatabase.ChannelAdminKey(
                            chatId,
                            "Channel Admin (Epoch #1)",
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString()
                    );
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveChannelAdminKey(cak);
                }
                senderName = cak.adminAlias;
                senderId = cak.adminAlias;
            }

            com.m3.veil.database.EncryptedChatDatabase.ScheduledMessage sched = new com.m3.veil.database.EncryptedChatDatabase.ScheduledMessage(
                    UUID.randomUUID().toString(),
                    chatId,
                    senderId,
                    senderName,
                    text,
                    null,
                    null,
                    "text",
                    scheduledTime,
                    currentVanishTimer > 0 ? scheduledTime + currentVanishTimer : 0,
                    isChannel
            );
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveScheduledMessage(sched);
            com.m3.veil.services.VeilSchedulerDaemon.getInstance(this).triggerScheduledMessageCheck();

            messageEditText.setText("");
            sheet.dismiss();
            Toast.makeText(this, "Message scheduled for " + sdf.format(new Date(scheduledTime)), Toast.LENGTH_LONG).show();
        });
        layout.addView(btnConfirm);
        sheet.setContentView(layout);
        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // READ-LATER REMINDERS (#178)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void showReadLaterRemindersDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad * 2);

        TextView title = new TextView(this);
        title.setText("📌 Read-Later Reminders (#178)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Set a reminder on this conversation to read or follow up later");
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, 4, 0, 16);
        layout.addView(subtitle);

        String[] quickOptions = {"In 15 minutes", "In 1 hour", "Tomorrow 9:00 AM", "Pick Custom Date & Time"};
        long[] durations = {
                15 * 60 * 1000L,
                60 * 60 * 1000L,
                -1, // Special calculation
                -2  // Custom
        };

        String lastSnippet = "Chat with " + (recipientName != null ? recipientName : "Peer");
        if (messageList != null && !messageList.isEmpty()) {
            com.m3.veil.model.Message last = messageList.get(messageList.size() - 1);
            if (last.getContent() != null && !last.getContent().isEmpty()) {
                lastSnippet = last.getContent();
                if (lastSnippet.length() > 50) lastSnippet = lastSnippet.substring(0, 47) + "...";
            }
        }
        final String snippetFinal = lastSnippet;

        for (int i = 0; i < quickOptions.length; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(quickOptions[i]);
            row.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            row.setTextSize(15.5f);
            row.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                sheet.dismiss();
                long remindAt;
                if (durations[idx] > 0) {
                    remindAt = System.currentTimeMillis() + durations[idx];
                    saveReminderAndNotify(snippetFinal, remindAt);
                } else if (durations[idx] == -1) {
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                    cal.set(Calendar.HOUR_OF_DAY, 9);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    remindAt = cal.getTimeInMillis();
                    saveReminderAndNotify(snippetFinal, remindAt);
                } else {
                    // Pick custom
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.HOUR_OF_DAY, 2);
                    new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                        cal.set(Calendar.YEAR, year);
                        cal.set(Calendar.MONTH, month);
                        cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                        new TimePickerDialog(this, (view1, hourOfDay, minute) -> {
                            cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
                            cal.set(Calendar.MINUTE, minute);
                            saveReminderAndNotify(snippetFinal, cal.getTimeInMillis());
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
                }
            });
            layout.addView(row);
        }

        sheet.setContentView(layout);
        sheet.show();
    }

    private void saveReminderAndNotify(String snippet, long remindAt) {
        com.m3.veil.database.EncryptedChatDatabase.ReadLaterReminder r = new com.m3.veil.database.EncryptedChatDatabase.ReadLaterReminder(
                UUID.randomUUID().toString(),
                null,
                chatId,
                recipientName != null ? recipientName : "Chat",
                snippet,
                remindAt
        );
        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).saveReminder(r);
        com.m3.veil.services.VeilSchedulerDaemon.getInstance(this).triggerReminderCheck();
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM • HH:mm", Locale.getDefault());
        Toast.makeText(this, "📌 Reminder set for " + sdf.format(new Date(remindAt)), Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speedChatTimer != null) {
            speedChatTimer.cancel();
        }
        stopChatReplay(false);
        presenceHandler.removeCallbacks(presenceTicker);
        com.m3.veil.manager.VeilMessagesController.getInstance(this).removeObserver(messagesControllerObserver);
        com.m3.veil.manager.VeilMessagesController.getInstance(this).setActiveChatIdInForeground(null);
        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).removeListener(localDbListener);
        if (messagesListener != null) {
            messagesListener.remove();
        }
        if (chatSettingsListener != null) {
            chatSettingsListener.remove();
        }
        if (recipientUserListener != null) {
            recipientUserListener.remove();
        }
        if (groupDocListener != null) {
            groupDocListener.remove();
        }
        if (groupMessagesListener != null) {
            groupMessagesListener.remove();
        }
        if (directMessagesListener != null) {
            directMessagesListener.remove();
        }
        if (directChatsMirrorListener != null) {
            directChatsMirrorListener.remove();
        }
        if (vanishHandler != null && vanishRunnable != null) {
            vanishHandler.removeCallbacks(vanishRunnable);
        }
        com.m3.veil.webrtc.WebRtcManager.getInstance(this).removeEventListener(p2pChatListener);
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {}
        }
    }
}
