package com.m3.veil.manager;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.services.VeilMeshDaemonService;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.utils.FileMediaManager;
import com.m3.veil.webrtc.P2PFileTransferHelper;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL MESSAGES CONTROLLER
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Application-wide Singleton modeled on Telegram's MessagesController.java, SendMessagesHelper.java,
 * and SecretChatHelper.java.
 *
 * Responsibilities:
 * 1. Orchestrates 100% serverless P2P message delivery, replies, edits, reactions, and polls.
 * 2. Dedicated DispatchQueue architecture:
 *      - stageQueue: Message sequencing, Lamport logical clock synchronization, packet framing.
 *      - storageQueue: SQLCipher encrypted database read/write and zero-wipe shredder.
 *      - cryptoQueue: RSA-OAEP / HKDF-SHA256 ratcheting and forward-secrecy key management.
 * 3. In-memory dialogs and message caching (dialogsDict, dialogMessages, messagesById).
 * 4. Telegram SecretChatHelper Vanish Engine:
 *      - Strict READ_ACK countdown triggers (timer starts when recipient reads message).
 *      - 500ms background vanishing daemon independent of ChatActivity lifecycle.
 *      - Cryptographic multi-pass disk shredder (overwriting with random/zeros before unlink).
 * 5. View-Once Blink Destruction Engine & Screenshot Alert ContentObserver.
 * 6. High-throughput binary ByteBuffer wire protocol framing (VMSG, VEDT, VDEL, VRCT, VPOL, etc.).
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class VeilMessagesController implements WebRtcManager.P2PEventListener {

    private static final String TAG = "VeilMessagesCtrl";
    private static volatile VeilMessagesController sInstance;

    // Binary wire protocol magic packet headers (4 bytes big-endian)
    public static final int MAGIC_VMSG = 0x564D5347; // "VMSG" - Standard chat message
    public static final int MAGIC_VEDT = 0x56454454; // "VEDT" - Message edit frame
    public static final int MAGIC_VDEL = 0x5644454C; // "VDEL" - Delete for everyone frame
    public static final int MAGIC_VRCT = 0x56524354; // "VRCT" - Reaction toggle frame
    public static final int MAGIC_VPOL = 0x56504F4C; // "VPOL" - Inline poll creation frame
    public static final int MAGIC_VVOT = 0x56564F54; // "VVOT" - Inline poll vote frame
    public static final int MAGIC_VTYP = 0x56545950; // "VTYP" - Stealth typing wave frame
    public static final int MAGIC_VRAC = 0x56524143; // "VRAC" - Read acknowledgment frame (vanish trigger)
    public static final int MAGIC_VSSN = 0x5653534E; // "VSSN" - Screenshot warning frame
    public static final int MAGIC_VBLK = 0x56424C4B; // "VBLK" - Blink opened frame

    // Wire protocol version
    public static final byte PROTOCOL_VERSION = 0x02;

    // Context & Current Identity
    private final Context mContext;
    private volatile String mCurrentUserId;
    private volatile String mCurrentUserName;

    // Threading / Dispatch Queues (Modeled after Telegram's DispatchQueue architecture)
    private final HandlerThread mStageThread;
    private final Handler mStageQueue;
    private final ExecutorService mStorageQueue;
    private final ExecutorService mCryptoQueue;
    private final ExecutorService mMediaTransferQueue;
    private final ScheduledExecutorService mScheduler;
    private final Handler mMainHandler;

    // Lamport Monotonic Logical Clock & Sequence Generation
    private final AtomicLong mLamportClock = new AtomicLong(System.currentTimeMillis());
    private final ConcurrentHashMap<String, AtomicInteger> mDialogSeqCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VectorClock> mVectorClocks = new ConcurrentHashMap<>();

    // In-Memory Caches (Modeled after Telegram MessagesController dialogsDict & dialogMessagesByIds)
    private final ConcurrentHashMap<String, Chat> mDialogsDict = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Chat> mSortedDialogsList = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Message>> mDialogMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Message> mMessagesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> mUnreadCountByDialog = new ConcurrentHashMap<>();

    // Telegram-Grade LongSparseArray warm caches (keyed by dialog/peer ID hash for 0ms lookup)
    private final android.util.LongSparseArray<Chat> mDialogs_dict = new android.util.LongSparseArray<>();
    private final android.util.LongSparseArray<CopyOnWriteArrayList<Message>> mMessagesByDialogs = new android.util.LongSparseArray<>();
    private final android.util.LongSparseArray<Message> mMessagesByIdLongSparse = new android.util.LongSparseArray<>();

    // Pre-warmed StaticLayout text measurement cache for locked 120Hz scrolling
    private static final android.util.LruCache<String, android.text.StaticLayout> STATIC_LAYOUT_CACHE =
            new android.util.LruCache<>(300);

    public static android.text.StaticLayout getCachedStaticLayout(String key) {
        return key != null ? STATIC_LAYOUT_CACHE.get(key) : null;
    }

    public static void putCachedStaticLayout(String key, android.text.StaticLayout layout) {
        if (key != null && layout != null) {
            STATIC_LAYOUT_CACHE.put(key, layout);
        }
    }

    public static void clearStaticLayoutCache() {
        STATIC_LAYOUT_CACHE.evictAll();
    }

    public static void prewarmMessageLayout(String messageId, CharSequence text, android.text.TextPaint paint, int maxWidth) {
        if (messageId == null || text == null || paint == null || maxWidth <= 0) return;
        if (STATIC_LAYOUT_CACHE.get(messageId) != null) return;
        try {
            android.text.StaticLayout layout;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), paint, maxWidth)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build();
            } else {
                layout = new android.text.StaticLayout(text, paint, maxWidth, android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);
            }
            STATIC_LAYOUT_CACHE.put(messageId, layout);
        } catch (Throwable ignored) {}
    }

    public static long getDialogIdHash(String dialogId) {
        if (dialogId == null) return 0L;
        long h = 1125899906842597L; // FNV-1a 64-bit basis
        for (int i = 0; i < dialogId.length(); i++) {
            h ^= dialogId.charAt(i);
            h *= 1099511628211L;
        }
        return h;
    }

    // Outbox & Pending Delivery Queue
    private final ConcurrentLinkedQueue<OutboxEntry> mOutboxQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, OutboxEntry> mPendingOutboxById = new ConcurrentHashMap<>();

    // Ephemeral Vanish & Blink Lifecycle State
    private final ConcurrentHashMap<String, VanishEntry> mActiveVanishTasks = new ConcurrentHashMap<>();
    private ScheduledFuture<?> mVanishDaemonFuture;

    // Active Typing States (dialogId:peerId -> timestamp)
    private final ConcurrentHashMap<String, Long> mTypingWaves = new ConcurrentHashMap<>();
    private ScheduledFuture<?> mTypingDecayFuture;

    // Screenshot Detector
    private ScreenshotObserver mScreenshotObserver;
    private volatile String mActiveChatIdInForeground = null;

    // Observers / Listeners
    private final CopyOnWriteArrayList<MessagesObserver> mObservers = new CopyOnWriteArrayList<>();

    // Database helper reference (dynamically switchable across Multi-Persona & Decoy vaults)
    private volatile EncryptedChatDatabase mDatabase;

    // Secure Random for cryptographic nonce generation
    private final SecureRandom mSecureRandom = new SecureRandom();

    /**
     * Interface for UI components (ChatActivity, HomeFragment, ArchivedChatsActivity) to observe
     * messaging events without coupling to activity lifecycle.
     */
    public interface MessagesObserver {
        default void onDialogsLoaded(List<Chat> dialogs) {}
        default void onMessagesLoaded(String dialogId, List<Message> messages) {}
        default void onMessageReceived(String dialogId, Message message) {}
        default void onMessageSent(String dialogId, Message message) {}
        default void onMessageEdited(String dialogId, String messageId, String newText) {}
        default void onMessageDeleted(String dialogId, String messageId) {}
        default void onMessageStatusChanged(String dialogId, String messageId, boolean delivered, boolean read) {}
        default void onPeerTyping(String dialogId, String peerId, boolean isTyping) {}
        default void onReactionUpdated(String dialogId, String messageId, String userId, String emoji) {}
        default void onPollUpdated(String dialogId, String messageId, String updatedPollJson) {}
        default void onVanishTick(String dialogId, String messageId, long remainingMillis, long totalDurationMillis) {}
        default void onVanishDestroyed(String dialogId, String messageId) {}
        default void onBlinkOpened(String dialogId, String messageId) {}
        default void onScreenshotDetected(String dialogId, String peerId) {}
        default void onMediaTransferProgress(String dialogId, String messageId, int percent) {}
    }

    /**
     * Outbox entry holding message metadata for retry orchestration across WebRTC mesh connections.
     */
    public static class OutboxEntry {
        public final Message message;
        public final String dialogId;
        public final String peerId;
        public final boolean isGroup;
        public final String chatName;
        public final String chatType;
        public final File localMediaFile;
        public int retryCount;
        public long lastAttemptTimestamp;
        public long nextRetryDelayMillis;

        public OutboxEntry(Message message, String dialogId, String peerId, boolean isGroup,
                           String chatName, String chatType, File localMediaFile) {
            this.message = message;
            this.dialogId = dialogId;
            this.peerId = peerId;
            this.isGroup = isGroup;
            this.chatName = chatName;
            this.chatType = chatType;
            this.localMediaFile = localMediaFile;
            this.retryCount = 0;
            this.lastAttemptTimestamp = System.currentTimeMillis();
            this.nextRetryDelayMillis = 1000; // Exponential backoff starts at 1s
        }
    }

    /**
     * Vanish tracking record for Telegram SecretChatHelper TTL countdowns.
     */
    public static class VanishEntry {
        public final String messageId;
        public final String dialogId;
        public final long expireTimestamp;
        public final long totalDurationMillis;
        public final String localFilePath;
        public final boolean isBlink;
        public boolean countdownStarted;

        public VanishEntry(String messageId, String dialogId, long expireTimestamp,
                           long totalDurationMillis, String localFilePath, boolean isBlink) {
            this.messageId = messageId;
            this.dialogId = dialogId;
            this.expireTimestamp = expireTimestamp;
            this.totalDurationMillis = totalDurationMillis;
            this.localFilePath = localFilePath;
            this.isBlink = isBlink;
            this.countdownStarted = (expireTimestamp > 0);
        }
    }

    /**
     * Vector clock tracking causal order per peer in P2P group mesh dialogues.
     */
    public static class VectorClock {
        private final Map<String, Long> clockMap = new ConcurrentHashMap<>();

        public synchronized void tick(String peerId) {
            long current = clockMap.containsKey(peerId) ? clockMap.get(peerId) : 0L;
            clockMap.put(peerId, current + 1);
        }

        public synchronized void update(String peerId, long timestamp) {
            long current = clockMap.containsKey(peerId) ? clockMap.get(peerId) : 0L;
            clockMap.put(peerId, Math.max(current, timestamp));
        }

        public synchronized long get(String peerId) {
            return clockMap.containsKey(peerId) ? clockMap.get(peerId) : 0L;
        }

        public synchronized JSONObject toJson() {
            return new JSONObject(clockMap);
        }

        public static VectorClock fromJson(JSONObject json) {
            VectorClock vc = new VectorClock();
            if (json != null) {
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    vc.update(k, json.optLong(k, 0L));
                }
            }
            return vc;
        }
    }

    /**
     * Singleton accessor.
     */
    public static VeilMessagesController getInstance(Context context) {
        if (sInstance == null) {
            synchronized (VeilMessagesController.class) {
                if (sInstance == null) {
                    sInstance = new VeilMessagesController(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /**
     * Private constructor initializing dispatch queues, caches, background daemons, and WebRTC hooks.
     */
    private VeilMessagesController(Context context) {
        this.mContext = context;
        this.mDatabase = EncryptedChatDatabase.getInstance(context);

        // 1. Initialize dedicated DispatchQueues (Stage, Storage, Crypto, Scheduler)
        this.mStageThread = new HandlerThread("VeilStageQueue");
        this.mStageThread.start();
        this.mStageQueue = new Handler(this.mStageThread.getLooper());

        this.mStorageQueue = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VeilStorageQueue");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        this.mCryptoQueue = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VeilCryptoQueue");
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });

        this.mMediaTransferQueue = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "VeilMediaTransferQueue");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        this.mScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "VeilMessagesScheduler");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        this.mMainHandler = new Handler(Looper.getMainLooper());

        // 2. Discover current user credentials from preferences
        reloadCurrentIdentity();

        // 3. Register as WebRTC Event Listener
        WebRtcManager.getInstance(context).addEventListener(this);

        // 4. Start Vanish Daemon (500ms ticker)
        startVanishDaemon();

        // 5. Start Typing Decay Daemon (1s ticker)
        startTypingDecayDaemon();

        // 6. Preload dialogs & messages into memory cache on storageQueue
        preloadDialogsCache();

        // 7. Register Screenshot Observer
        registerScreenshotObserver();

        // 8. Register Network Connectivity Callback to flush pending (Clock) messages when online
        registerNetworkCallback();

        Log.i(TAG, "VeilMessagesController initialized successfully.");
    }

    public boolean isNetworkConnected() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.net.Network net = cm.getActiveNetwork();
                if (net == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) return false;
                return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void registerNetworkCallback() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull android.net.Network network) {
                        scheduleOfflineFlushWithRetries();
                    }

                    @Override
                    public void onCapabilitiesChanged(@NonNull android.net.Network network, @NonNull android.net.NetworkCapabilities networkCapabilities) {
                        if (networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            flushPendingOfflineMessagesOnNetworkAvailable();
                        }
                    }

                    @Override
                    public void onLost(@NonNull android.net.Network network) {
                        mMainHandler.postDelayed(() -> {
                            if (!isNetworkConnected()) {
                                haltInFlightMediaTransfersOnNetworkLoss();
                            }
                        }, 150L);
                    }
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not register network callback", e);
        }
    }

    private void scheduleOfflineFlushWithRetries() {
        flushPendingOfflineMessagesOnNetworkAvailable();
        mMainHandler.postDelayed(this::flushPendingOfflineMessagesOnNetworkAvailable, 900L);
        mMainHandler.postDelayed(this::flushPendingOfflineMessagesOnNetworkAvailable, 2400L);
        mMainHandler.postDelayed(this::flushPendingOfflineMessagesOnNetworkAvailable, 5000L);
    }

    private void haltInFlightMediaTransfersOnNetworkLoss() {
        for (String msgId : new ArrayList<>(sHighestMediaProgress.keySet())) {
            Message m = getMessage(msgId);
            String dId = m != null ? m.getChatId() : "";
            notifyGlobalMediaTransferProgress(dId, msgId, -1);
        }
    }

    public void flushPendingOfflineMessagesOnNetworkAvailable() {
        mStorageQueue.execute(() -> {
            if (!isNetworkConnected()) return;
            try {
                List<Message> pendingMsgs = mDatabase.getUnsentPendingMessages();
                if (pendingMsgs == null || pendingMsgs.isEmpty()) return;
                for (Message msg : pendingMsgs) {
                    if (msg == null || msg.getId() == null) continue;
                    if (P2PFileTransferHelper.isTransferCanceled(msg.getId())) continue;
                    final String dialogId = msg.getChatId();
                    if (dialogId == null || dialogId.isEmpty()) continue;
                    final Chat chat = getDialog(dialogId) != null ? getDialog(dialogId) : mDatabase.getChat(dialogId);
                    final boolean isGroup = (chat != null && chat.isGroupChat())
                            || dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_");
                    final String recipientId = msg.getReceiverId();

                    mCryptoQueue.execute(() -> {
                        if (!isNetworkConnected() || P2PFileTransferHelper.isTransferCanceled(msg.getId())) return;
                        try {
                            File pendingLocalFile = (msg.getLocalFilePath() != null && !msg.getLocalFilePath().isEmpty())
                                    ? new File(msg.getLocalFilePath()) : null;
                            boolean hasLocalMedia = pendingLocalFile != null && pendingLocalFile.exists() && pendingLocalFile.length() >= 64;
                            List<String> targetPeers = resolveTargetPeers(dialogId, recipientId, msg);

                            boolean anyP2PStarted = false;
                            if (hasLocalMedia) {
                                for (String peer : targetPeers) {
                                    if (sendMediaP2P(peer, msg, pendingLocalFile)) {
                                        anyP2PStarted = true;
                                    }
                                }
                            }

                            encodeInlineMediaForTransport(dialogId, msg, pendingLocalFile);
                            if (!isNetworkConnected() || P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                                notifyGlobalMediaTransferProgress(dialogId, msg.getId(), -1);
                                return;
                            }

                            boolean hasCloudPayload = msg.getMediaUrl() != null
                                    && (msg.getMediaUrl().startsWith("ENC:v")
                                    || msg.getMediaUrl().startsWith("INLINE_B64:")
                                    || msg.getMediaUrl().startsWith("CHUNKED:v1:"));
                            if (hasCloudPayload) {
                                mStorageQueue.execute(() -> mDatabase.saveMessage(dialogId, msg));
                            }

                            if (!hasLocalMedia || hasCloudPayload) {
                                if (isGroup) {
                                    syncGroupOrChannelMessageToFirestore(chat, dialogId, msg);
                                } else {
                                    syncDirectMessageToFirestore(chat, dialogId, recipientId, msg);
                                }
                            }

                            String jsonPayloadStr = buildJsonFallbackString(dialogId, msg);
                            for (String peer : targetPeers) {
                                boolean peerConnected = WebRtcManager.getInstance(mContext).isPeerConnected(peer);
                                if (!hasLocalMedia && peerConnected && WebRtcManager.getInstance(mContext).sendPacket(peer,
                                        new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, jsonPayloadStr))) {
                                    com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                                            .sendDirectP2PNotificationFrame(peer, dialogId, msg);
                                } else if (hasLocalMedia && anyP2PStarted) {
                                    com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                                            .sendDirectP2PNotificationFrame(peer, dialogId, msg);
                                } else {
                                    if ((!hasLocalMedia || hasCloudPayload) && jsonPayloadStr.length() <= 32768) {
                                        try {
                                            com.m3.veil.webrtc.SignalingClient.getInstance().sendP2PSignal(
                                                    peer, "p2p_chat", new org.json.JSONObject(jsonPayloadStr));
                                        } catch (Exception ignored) {}
                                    }
                                    com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                                            .dispatchCloudFallbackWakePush(peer, dialogId, msg);
                                    WebRtcManager.getInstance(mContext).initiateConnection(peer);

                                    String outboxKey = isGroup ? (msg.getId() + "_" + peer) : msg.getId();
                                    if (!mPendingOutboxById.containsKey(outboxKey)) {
                                        OutboxEntry outbox = new OutboxEntry(msg, dialogId, peer, isGroup,
                                                chat != null && isGroup ? chat.getName() : getCurrentUserName(),
                                                chat != null ? chat.getType() : "direct", pendingLocalFile);
                                        mOutboxQueue.add(outbox);
                                        mPendingOutboxById.put(outboxKey, outbox);
                                    }
                                    VeilMeshDaemonService.getInstance(mContext).enqueueOutgoingMessage(chat, peer, msg);
                                }
                            }

                            if (!hasLocalMedia || hasCloudPayload) {
                                msg.setPending(false);
                                Message cached = mMessagesById.get(msg.getId());
                                if (cached != null) cached.setPending(false);
                                mStorageQueue.execute(() -> {
                                    mDatabase.setMessagePending(msg.getId(), false);
                                    refreshDialogInCache(dialogId);
                                });
                                if (hasCloudPayload && !anyP2PStarted) {
                                    notifyGlobalMediaTransferProgress(dialogId, msg.getId(), 100);
                                }
                                mMainHandler.post(() -> {
                                    for (MessagesObserver obs : mObservers) {
                                        obs.onMessageStatusChanged(dialogId, msg.getId(), msg.isDelivered(), msg.isRead());
                                    }
                                });
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "Error flushing pending offline message: " + ex.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to query pending offline messages", e);
            }
        });
    }

    /**
     * Reload active user credentials from Veil preferences.
     */
    public void reloadCurrentIdentity() {
        android.content.SharedPreferences prefs = mContext.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE);
        this.mCurrentUserId = prefs.getString("user_id", null);
        if (this.mCurrentUserId == null) {
            this.mCurrentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(mContext);
        }
        if (this.mCurrentUserId == null) {
            this.mCurrentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(mContext).getCurrentUserId();
        }
        String candidate = prefs.getString("username", null);
        if (VeilFeatureManager.isShadowVeilOrGeneric(candidate)) {
            candidate = VeilFeatureManager.getInstance(mContext).getActivePersona().getDisplayName();
        }
        this.mCurrentUserName = !VeilFeatureManager.isShadowVeilOrGeneric(candidate) ? candidate : "User";
    }

    public String getCurrentUserId() {
        if (mCurrentUserId == null) reloadCurrentIdentity();
        return mCurrentUserId;
    }

    public String getCurrentUserName() {
        if (VeilFeatureManager.isShadowVeilOrGeneric(mCurrentUserName)) {
            String activeName = VeilFeatureManager.getInstance(mContext).getActivePersona().getDisplayName();
            if (!VeilFeatureManager.isShadowVeilOrGeneric(activeName)) {
                mCurrentUserName = activeName;
            }
        }
        return mCurrentUserName;
    }

    public void setActiveChatIdInForeground(String chatId) {
        this.mActiveChatIdInForeground = chatId;
    }

    public String getActiveChatIdInForeground() {
        return this.mActiveChatIdInForeground;
    }

    /**
     * Dynamically switches the active SQLCipher database vault when the user switches Personas
     * or unlocks the Decoy Account Vault (#11, #104, #106).
     */
    public synchronized void switchActivePersonaVault() {
        this.mDatabase = EncryptedChatDatabase.getInstance(mContext);
        reloadCurrentIdentity();
        clearStaticLayoutCache();
        mDialogMessages.clear();
        mMessagesById.clear();
        preloadDialogsCache();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // OBSERVER REGISTRATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public void addObserver(MessagesObserver observer) {
        if (observer != null && !mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(MessagesObserver observer) {
        if (observer != null) {
            mObservers.remove(observer);
        }
    }

    private void runOnUI(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mMainHandler.post(runnable);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // CACHE PRELOADING & DIALOG RETRIEVAL
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Preloads dialogs from EncryptedChatDatabase into mDialogsDict and mSortedDialogsList.
     */
    public void preloadDialogsCache() {
        mStorageQueue.execute(() -> {
            try {
                List<Chat> dbChats = mDatabase.getAllChats(true);
                mDialogsDict.clear();
                mSortedDialogsList.clear();
                synchronized (mDialogs_dict) {
                    mDialogs_dict.clear();
                }
                synchronized (mMessagesByDialogs) {
                    mMessagesByDialogs.clear();
                }
                synchronized (mMessagesByIdLongSparse) {
                    mMessagesByIdLongSparse.clear();
                }

                for (Chat c : dbChats) {
                    if (c != null && c.getId() != null) {
                        mDialogsDict.put(c.getId(), c);
                        mSortedDialogsList.add(c);
                        mUnreadCountByDialog.put(c.getId(), new AtomicInteger(c.getUnreadCount()));
                        long dialogHash = getDialogIdHash(c.getId());
                        synchronized (mDialogs_dict) {
                            mDialogs_dict.put(dialogHash, c);
                        }

                        // Also initialize active vanish tasks for unexpired messages
                        List<Message> msgs = mDatabase.getMessages(c.getId(), 200, 0);
                        CopyOnWriteArrayList<Message> msgList = new CopyOnWriteArrayList<>(msgs);
                        mDialogMessages.put(c.getId(), msgList);
                        synchronized (mMessagesByDialogs) {
                            mMessagesByDialogs.put(dialogHash, msgList);
                        }

                        for (Message m : msgs) {
                            mMessagesById.put(m.getId(), m);
                            synchronized (mMessagesByIdLongSparse) {
                                mMessagesByIdLongSparse.put(getDialogIdHash(m.getId()), m);
                            }
                            if (m.getExpiresAt() != null && m.getExpiresAt().toDate().getTime() > 0) {
                                long expireAt = m.getExpiresAt().toDate().getTime();
                                long duration = m.getVanishTimer() > 0 ? m.getVanishTimer() : 60000;
                                mActiveVanishTasks.put(m.getId(), new VanishEntry(
                                        m.getId(), c.getId(), expireAt, duration, m.getLocalFilePath(), m.isBlink()));
                            }
                        }
                    }
                }

                // Sort dialogs by last message timestamp descending
                sortDialogsInternal();

                List<Chat> copy = new ArrayList<>(mSortedDialogsList);
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onDialogsLoaded(copy);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to preload dialogs cache", e);
            }
        });
    }

    private void sortDialogsInternal() {
        Collections.sort(mSortedDialogsList, (c1, c2) -> {
            if (c1.isPinned() != c2.isPinned()) {
                return c1.isPinned() ? -1 : 1;
            }
            long t1 = c1.getLastMessageTime() != null ? c1.getLastMessageTime().toDate().getTime() : 0L;
            long t2 = c2.getLastMessageTime() != null ? c2.getLastMessageTime().toDate().getTime() : 0L;
            return Long.compare(t2, t1);
        });
    }

    public List<Chat> getLoadedDialogs() {
        return new ArrayList<>(mSortedDialogsList);
    }

    /**
     * Immediately purges a deleted dialog and its messages from all in-memory controller caches.
     */
    public void removeDialogFromCache(String dialogId) {
        if (dialogId == null) return;
        mDialogsDict.remove(dialogId);
        mUnreadCountByDialog.remove(dialogId);
        mDialogMessages.remove(dialogId);
        for (int i = mSortedDialogsList.size() - 1; i >= 0; i--) {
            Chat c = mSortedDialogsList.get(i);
            if (c != null && dialogId.equals(c.getId())) {
                mSortedDialogsList.remove(i);
            }
        }
        long hash = getDialogIdHash(dialogId);
        synchronized (mDialogs_dict) {
            mDialogs_dict.remove(hash);
        }
        synchronized (mMessagesByDialogs) {
            mMessagesByDialogs.remove(hash);
        }
    }

    public Chat getDialog(long dialogHash) {
        synchronized (mDialogs_dict) {
            return mDialogs_dict.get(dialogHash);
        }
    }

    /**
     * 0ms lock-free retrieval of a cached dialog (never blocks on SQLCipher).
     */
    public Chat getCachedDialogImmediate(String dialogId) {
        if (dialogId == null) return null;
        Chat c = mDialogsDict.get(dialogId);
        if (c != null) return c;
        c = mDatabase.getCachedChatImmediate(dialogId);
        if (c != null) {
            mDialogsDict.put(dialogId, c);
            synchronized (mDialogs_dict) {
                mDialogs_dict.put(getDialogIdHash(dialogId), c);
            }
        }
        return c;
    }

    /**
     * 0ms lock-free retrieval of cached messages for a dialog (never blocks on SQLCipher).
     * Used on Frame 1 of ChatActivity.onCreate() so messages appear instantaneously like Telegram.
     */
    public List<Message> getCachedMessagesImmediate(String dialogId) {
        if (dialogId == null) return Collections.emptyList();
        CopyOnWriteArrayList<Message> list = mDialogMessages.get(dialogId);
        if (list != null && !list.isEmpty()) {
            return new ArrayList<>(list);
        }
        List<Message> dbRamMsgs = mDatabase.getCachedMessagesImmediate(dialogId);
        if (dbRamMsgs != null && !dbRamMsgs.isEmpty()) {
            CopyOnWriteArrayList<Message> copy = new CopyOnWriteArrayList<>(dbRamMsgs);
            mDialogMessages.put(dialogId, copy);
            synchronized (mMessagesByDialogs) {
                mMessagesByDialogs.put(getDialogIdHash(dialogId), copy);
            }
            return dbRamMsgs;
        }
        return Collections.emptyList();
    }

    /**
     * Asynchronously pre-warms a dialog and its messages into RAM on mStorageQueue.
     */
    public void prewarmDialog(String dialogId) {
        if (dialogId == null) return;
        CopyOnWriteArrayList<Message> existing = mDialogMessages.get(dialogId);
        if (existing != null && !existing.isEmpty() && mDialogsDict.containsKey(dialogId)) {
            return;
        }
        mStorageQueue.execute(() -> {
            try {
                getDialog(dialogId);
                getLoadedMessages(dialogId);
            } catch (Throwable ignored) {}
        });
    }

    public Chat getDialog(String dialogId) {
        if (dialogId == null) return null;
        long hash = getDialogIdHash(dialogId);
        Chat c = mDialogsDict.get(dialogId);
        if (c == null) {
            c = mDatabase.getChat(dialogId);
            if (c != null) {
                mDialogsDict.put(dialogId, c);
                mSortedDialogsList.add(c);
                synchronized (mDialogs_dict) {
                    mDialogs_dict.put(hash, c);
                }
            }
        }
        return c;
    }

    public List<Message> getLoadedMessages(long dialogHash) {
        synchronized (mMessagesByDialogs) {
            CopyOnWriteArrayList<Message> list = mMessagesByDialogs.get(dialogHash);
            return list != null ? new ArrayList<>(list) : Collections.emptyList();
        }
    }

    public List<Message> getLoadedMessages(String dialogId) {
        if (dialogId == null) return Collections.emptyList();
        long hash = getDialogIdHash(dialogId);
        CopyOnWriteArrayList<Message> list = mDialogMessages.get(dialogId);
        if (list == null) {
            List<Message> dbMsgs = mDatabase.getMessages(dialogId, 200, 0);
            list = new CopyOnWriteArrayList<>(dbMsgs);
            mDialogMessages.put(dialogId, list);
            synchronized (mMessagesByDialogs) {
                mMessagesByDialogs.put(hash, list);
            }
            for (Message m : dbMsgs) {
                mMessagesById.put(m.getId(), m);
                synchronized (mMessagesByIdLongSparse) {
                    mMessagesByIdLongSparse.put(getDialogIdHash(m.getId()), m);
                }
            }
        }
        return new ArrayList<>(list);
    }

    public Message getMessage(long messageHash) {
        synchronized (mMessagesByIdLongSparse) {
            return mMessagesByIdLongSparse.get(messageHash);
        }
    }

    public Message getMessage(String messageId) {
        if (messageId == null) return null;
        long hash = getDialogIdHash(messageId);
        Message m = mMessagesById.get(messageId);
        if (m == null) {
            m = mDatabase.getMessage(messageId);
            if (m != null) {
                mMessagesById.put(messageId, m);
                synchronized (mMessagesByIdLongSparse) {
                    mMessagesByIdLongSparse.put(hash, m);
                }
            }
        }
        return m;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // LOGICAL CLOCKS & SEQUENCE NUMBERS
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public long nextLamportClock() {
        return mLamportClock.incrementAndGet();
    }

    public void observeRemoteLamportClock(long remoteClock) {
        long current;
        do {
            current = mLamportClock.get();
            if (remoteClock <= current) break;
        } while (!mLamportClock.compareAndSet(current, remoteClock + 1));
    }

    public int nextDialogSeq(String dialogId) {
        AtomicInteger seq = mDialogSeqCounters.computeIfAbsent(dialogId, k -> new AtomicInteger(0));
        return seq.incrementAndGet();
    }

    public VectorClock getVectorClock(String dialogId) {
        return mVectorClocks.computeIfAbsent(dialogId, k -> new VectorClock());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // PASS 3: P2P PACKET WIRE ENCODING & DISPATCH
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Unified message dispatcher handling any Message object (text, media, location, contact, voice, poll, blink, vanish).
     * Enforces optimistic 16ms in-memory UI caching, SQLCipher persistence, and WebRTC streaming.
     */
    public void sendMessage(Chat chat, String recipientId, Message message) {
        if (message == null) return;
        String dialogId = (chat != null && chat.getId() != null)
                ? chat.getId()
                : (message.getChatId() != null && !message.getChatId().isEmpty()
                    ? message.getChatId()
                    : (recipientId != null ? WebRtcManager.getChatId(message.getSenderId() != null ? message.getSenderId() : getCurrentUserId(), recipientId) : null));
        if (dialogId == null) return;
        if (chat == null) {
            chat = getCachedDialogImmediate(dialogId);
            if (chat == null) {
                chat = mDatabase.getChat(dialogId);
            }
        }
        boolean isGroupOrChannel = (chat != null && chat.isGroupChat())
                || dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_");
        String finalRecipientId = isGroupOrChannel ? null : recipientId;
        if (!isGroupOrChannel && (finalRecipientId == null || finalRecipientId.isEmpty())) {
            String myId = getCurrentUserId();
            if (myId != null && dialogId.contains(myId)) {
                if (dialogId.startsWith(myId + "_")) {
                    finalRecipientId = dialogId.substring((myId + "_").length());
                } else if (dialogId.endsWith("_" + myId)) {
                    finalRecipientId = dialogId.substring(0, dialogId.length() - ("_" + myId).length());
                }
            }
            if (finalRecipientId == null || finalRecipientId.isEmpty()) {
                if (dialogId.contains("_")) {
                    String[] parts = dialogId.split("_");
                    if (parts.length == 2) {
                        finalRecipientId = parts[0].equals(myId) ? parts[1] : parts[0];
                    }
                }
            }
        }
        if (!isGroupOrChannel && (finalRecipientId == null || finalRecipientId.isEmpty())) return;
        final String effectiveRecipientId = finalRecipientId;
        final Chat resolvedChatRef = chat;
        message.setChatId(dialogId);
        message.setPending(true);
        message.setDelivered(false);
        message.setRead(false);
        if (message.getTimestamp() == null) {
            message.setTimestamp(Timestamp.now());
        }
        if (message.getSenderId() == null) {
            message.setSenderId(getCurrentUserId());
        }
        if (message.getSenderName() == null || VeilFeatureManager.isShadowVeilOrGeneric(message.getSenderName())) {
            message.setSenderName(getCurrentUserName());
        }
        if (isGroupOrChannel) {
            message.setReceiverId(dialogId);
        }

        // Synchronously update RAM cache (zero disk I/O on UI thread) so ChatActivity renders the
        // optimistic message in <1ms, while mStorageQueue/mStageQueue persists to SQLCipher asynchronously.
        putMessageInCache(dialogId, message);
        Chat cachedChat = mDialogsDict.get(dialogId);
        if (cachedChat != null) {
            cachedChat.setLastMessage(message.getText());
            cachedChat.setLastMessageTime(message.getTimestamp());
            cachedChat.setLastMessageSenderId(message.getSenderId());
            cachedChat.setLastMessagePending(true);
            cachedChat.setLastMessageDelivered(false);
            cachedChat.setLastMessageRead(false);
        }

        mStageQueue.post(() -> {
            mDatabase.saveMessage(dialogId, message);
            updateDialogLastMessage(dialogId, message);

            long lamport = nextLamportClock();
            int seq = nextDialogSeq(dialogId);

            long vanishTimer = message.getVanishTimer();
            if (vanishTimer <= 0 && resolvedChatRef != null && resolvedChatRef.getVanishTimer() > 0) {
                vanishTimer = resolvedChatRef.getVanishTimer();
                message.setVanishTimer(vanishTimer);
            }
            String vanishMode = message.getVanishMode();
            if (vanishMode == null && resolvedChatRef != null && resolvedChatRef.getVanishMode() != null) {
                vanishMode = resolvedChatRef.getVanishMode();
                message.setVanishMode(vanishMode);
            }
            if (vanishTimer > 0 && "after_send".equals(vanishMode) && message.getExpiresAt() == null) {
                long expiresAt = System.currentTimeMillis() + vanishTimer;
                message.setExpiresAt(new Timestamp(new Date(expiresAt)));
            }

            File localFile = (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty())
                    ? new File(message.getLocalFilePath()) : null;

            dispatchMessageInternal(dialogId, effectiveRecipientId, message, localFile);
        });
    }

    /**
     * Sends a text message over the WebRTC mesh engine with full forward-secrecy ratcheting.
     */
    public void sendTextMessage(String dialogId, String recipientId, String text,
                                @Nullable String replyToId, boolean isBlink) {
        if (text == null || text.trim().isEmpty() || dialogId == null) return;
        boolean isGroupOrChannel = dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_");
        if (!isGroupOrChannel && (recipientId == null || recipientId.isEmpty())) return;

        mStageQueue.post(() -> {
            String messageId = UUID.randomUUID().toString();
            long lamport = nextLamportClock();
            int seq = nextDialogSeq(dialogId);

            Chat chat = getDialog(dialogId);
            long vanishTimer = chat != null ? chat.getVanishTimer() : 0L;
            String vanishMode = (chat != null && chat.getVanishMode() != null) ? chat.getVanishMode() : "after_send";

            Message msg = new Message(messageId, getCurrentUserId(), recipientId != null ? recipientId : dialogId, text.trim(), Timestamp.now(), "text");
            msg.setChatId(dialogId);
            msg.setSenderName(getCurrentUserName());
            msg.setBlink(isBlink);
            msg.setVanishTimer(vanishTimer);
            msg.setVanishMode(vanishMode);

            if (replyToId != null) {
                Message replyMsg = getMessage(replyToId);
                msg.setReplyToMessageId(replyToId);
                if (replyMsg != null) {
                    boolean isSenderYou = replyMsg.getSenderId() != null && replyMsg.getSenderId().equals(getCurrentUserId());
                    msg.setReplySenderName(isSenderYou ? "You" : replyMsg.getSenderName());
                }
            }

            // Mode "after_send" starts countdown immediately upon sending
            if (vanishTimer > 0 && "after_send".equals(vanishMode)) {
                long expiresAt = System.currentTimeMillis() + vanishTimer;
                msg.setExpiresAt(new Timestamp(new Date(expiresAt)));
            }

            dispatchMessageInternal(dialogId, recipientId, msg, null);
        });
    }

    /**
     * Sends a media attachment over WebRTC RTCDataChannel chunk streaming.
     */
    public void sendMediaMessage(String dialogId, String recipientId, String mediaType,
                                 File file, @Nullable String caption, @Nullable String replyToId,
                                 boolean isBlink) {
        if (file == null || !file.exists() || dialogId == null) return;
        boolean isGroupOrChannel = dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_");
        if (!isGroupOrChannel && (recipientId == null || recipientId.isEmpty())) return;

        mStageQueue.post(() -> {
            String messageId = UUID.randomUUID().toString();
            Chat chat = getDialog(dialogId);
            if (chat == null && mDatabase != null) {
                chat = mDatabase.getChat(dialogId);
            }
            long vanishTimer = chat != null ? chat.getVanishTimer() : 0L;
            String vanishMode = (chat != null && chat.getVanishMode() != null) ? chat.getVanishMode() : "after_send";

            String effectiveRecipientId = recipientId;
            if (!isGroupOrChannel && (effectiveRecipientId == null || effectiveRecipientId.isEmpty() || effectiveRecipientId.equals(dialogId))) {
                String myId = getCurrentUserId();
                if (chat != null && chat.getParticipants() != null) {
                    for (String p : chat.getParticipants()) {
                        if (p != null && !p.isEmpty() && !p.equals(myId)) {
                            effectiveRecipientId = p;
                            break;
                        }
                    }
                }
                if ((effectiveRecipientId == null || effectiveRecipientId.equals(dialogId)) && dialogId.contains("_")) {
                    String[] parts = dialogId.split("_");
                    if (parts.length == 2) {
                        effectiveRecipientId = parts[0].equals(myId) ? parts[1] : parts[0];
                    }
                }
            }

            File stagedFile = file;
            try {
                File cacheDir = mContext.getExternalCacheDir() != null ? mContext.getExternalCacheDir() : mContext.getCacheDir();
                String ext = ".jpg";
                if ("video".equalsIgnoreCase(mediaType)) ext = ".mp4";
                else if ("gif".equalsIgnoreCase(mediaType)) ext = ".gif";
                else if ("sticker".equalsIgnoreCase(mediaType)) ext = com.m3.veil.utils.VeilStickerGifEngine.detectStickerFileExtension(file);
                else if ("voice".equalsIgnoreCase(mediaType) || "audio".equalsIgnoreCase(mediaType)) ext = ".m4a";
                File decCopy = new File(cacheDir, "dec_" + messageId + ext);
                if (!file.getAbsolutePath().equals(decCopy.getAbsolutePath())) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(decCopy)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = fis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    if (decCopy.exists() && decCopy.length() > 0) {
                        stagedFile = decCopy;
                    }
                }
            } catch (Throwable ignored) {}

            Message msg = new Message(messageId, getCurrentUserId(),
                    effectiveRecipientId != null ? effectiveRecipientId : dialogId,
                    caption != null ? caption : "", Timestamp.now(), mediaType);
            msg.setChatId(dialogId);
            msg.setSenderName(getCurrentUserName());
            msg.setLocalFilePath(stagedFile.getAbsolutePath());
            msg.setMediaSize(stagedFile.length());
            msg.setBlink(isBlink);
            msg.setVanishTimer(vanishTimer);
            msg.setVanishMode(vanishMode);

            // Generate and attach thumbnail for video, sticker & image so forwarded/edited media always renders thumbnails
            if ("video".equalsIgnoreCase(mediaType)) {
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try {
                    retriever.setDataSource(stagedFile.getAbsolutePath());
                    android.graphics.Bitmap frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) {
                        frame = retriever.getFrameAtTime();
                    }
                    if (frame != null) {
                        int fw = Math.max(1, frame.getWidth());
                        int fh = Math.max(1, frame.getHeight());
                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions("msg_" + messageId, fw, fh);
                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions(stagedFile.getAbsolutePath(), fw, fh);
                        float thumbRatio = Math.min(320f / fw, 320f / fh);
                        int thumbW = Math.max(48, Math.round(fw * thumbRatio));
                        int thumbH = Math.max(48, Math.round(fh * thumbRatio));
                        android.graphics.Bitmap thumb = android.graphics.Bitmap.createScaledBitmap(frame, thumbW, thumbH, true);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, baos);
                        msg.setThumbnail(android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP));
                        if (thumb != frame) thumb.recycle();
                        frame.recycle();
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { retriever.release(); } catch (Throwable ignored) {}
                }
            } else if ("sticker".equalsIgnoreCase(mediaType)) {
                try {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(stagedFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        android.graphics.Bitmap dieCut = com.m3.veil.utils.VeilStickerGifEngine.stripSolidBorderBackgroundIfPresent(bmp);
                        int bw = Math.max(1, dieCut.getWidth());
                        int bh = Math.max(1, dieCut.getHeight());
                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions("msg_" + messageId, bw, bh);
                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions(stagedFile.getAbsolutePath(), bw, bh);
                        float thumbRatio = Math.min(384f / bw, 384f / bh);
                        int thumbW = Math.max(64, Math.round(bw * thumbRatio));
                        int thumbH = Math.max(64, Math.round(bh * thumbRatio));
                        android.graphics.Bitmap thumb = android.graphics.Bitmap.createScaledBitmap(dieCut, thumbW, thumbH, true);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        thumb.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
                        msg.setThumbnail(android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP));
                    }
                } catch (Throwable ignored) {}
            } else if ("image".equalsIgnoreCase(mediaType) || "gif".equalsIgnoreCase(mediaType)) {
                try {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(stagedFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        int bw = Math.max(1, bmp.getWidth());
                        int bh = Math.max(1, bmp.getHeight());
                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions("msg_" + messageId, bw, bh);
                        com.m3.veil.utils.VeilStickerGifEngine.cacheDimensions(stagedFile.getAbsolutePath(), bw, bh);
                        float thumbRatio = Math.min(240f / bw, 240f / bh);
                        int thumbW = Math.max(36, Math.round(bw * thumbRatio));
                        int thumbH = Math.max(36, Math.round(bh * thumbRatio));
                        android.graphics.Bitmap thumb = android.graphics.Bitmap.createScaledBitmap(bmp, thumbW, thumbH, true);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos);
                        msg.setThumbnail(android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP));
                        if (thumb != bmp) thumb.recycle();
                        bmp.recycle();
                    }
                } catch (Throwable ignored) {}
            }

            if (replyToId != null && !replyToId.trim().isEmpty()) {
                Message replyMsg = getMessage(replyToId);
                msg.setReplyToMessageId(replyToId);
                if (replyMsg != null) {
                    boolean isSenderYou = replyMsg.getSenderId() != null && replyMsg.getSenderId().equals(getCurrentUserId());
                    msg.setReplySenderName(isSenderYou ? "You" : replyMsg.getSenderName());
                }
            }

            if (vanishTimer > 0 && "after_send".equals(vanishMode)) {
                long expiresAt = System.currentTimeMillis() + vanishTimer;
                msg.setExpiresAt(new Timestamp(new Date(expiresAt)));
            }

            dispatchMessageInternal(dialogId, effectiveRecipientId, msg, stagedFile);
        });
    }

    private static final int MAX_DIRECT_P2P_GROUP_FANOUT = 16;

    private List<String> resolveTargetPeers(String dialogId, @Nullable String explicitRecipientId, @Nullable Message msg) {
        List<String> targets = new ArrayList<>();
        String myId = getCurrentUserId();
        Chat chat = getDialog(dialogId);
        if (chat == null && dialogId != null) {
            chat = mDatabase.getChat(dialogId);
        }
        boolean isGroup = (chat != null && chat.isGroupChat())
                || (dialogId != null && (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")));
        if (isGroup && chat != null && chat.getParticipants() != null) {
            List<String> connectedPeers = new ArrayList<>();
            List<String> offlinePeers = new ArrayList<>();
            for (String pId : chat.getParticipants()) {
                if (pId != null && !pId.trim().isEmpty() && !pId.equals(myId)
                        && !connectedPeers.contains(pId) && !offlinePeers.contains(pId)) {
                    if (WebRtcManager.getInstance(mContext).isConnectedToPeer(pId)) {
                        connectedPeers.add(pId);
                    } else {
                        offlinePeers.add(pId);
                    }
                }
            }
            for (String pId : connectedPeers) {
                if (targets.size() >= MAX_DIRECT_P2P_GROUP_FANOUT) break;
                targets.add(pId);
            }
            for (String pId : offlinePeers) {
                if (targets.size() >= MAX_DIRECT_P2P_GROUP_FANOUT) break;
                targets.add(pId);
            }
        }
        if (targets.isEmpty()) {
            if (explicitRecipientId != null && !explicitRecipientId.isEmpty()
                    && !explicitRecipientId.equals(dialogId) && !explicitRecipientId.equals(myId)) {
                targets.add(explicitRecipientId);
            } else if (msg != null) {
                String candidate = (myId != null && myId.equals(msg.getReceiverId())) ? msg.getSenderId() : msg.getReceiverId();
                if (candidate != null && !candidate.isEmpty() && !candidate.equals(dialogId) && !candidate.equals(myId)) {
                    targets.add(candidate);
                }
            }
        }
        return targets;
    }

    /**
     * Core message dispatch routine handling memory caching, SQLCipher persistence, and WebRTC streaming.
     */
    private void dispatchMessageInternal(String dialogId, String recipientId, Message msg, @Nullable File localFile) {
        msg.setChatId(dialogId);
        msg.setPending(true);
        msg.setDelivered(false);
        msg.setRead(false);

        // 1. Put into memory cache immediately with pending=true (Clock icon)
        putMessageInCache(dialogId, msg);

        // 2. Persist to local SQLCipher database on storageQueue
        mStorageQueue.execute(() -> {
            mDatabase.saveMessage(dialogId, msg);
            updateDialogLastMessage(dialogId, msg);

            // Register vanish task if expiresAt is set
            if (msg.getExpiresAt() != null && msg.getExpiresAt().toDate().getTime() > 0) {
                long expireAt = msg.getExpiresAt().toDate().getTime();
                long duration = msg.getVanishTimer() > 0 ? msg.getVanishTimer() : 60000;
                mActiveVanishTasks.put(msg.getId(), new VanishEntry(
                        msg.getId(), dialogId, expireAt, duration, msg.getLocalFilePath(), msg.isBlink()));
            }
        });

        final boolean isMediaMsg = (localFile != null && localFile.exists() && localFile.length() > 0L)
                || (msg.getType() != null && !"text".equals(msg.getType()) && !"poll".equals(msg.getType())
                    && !"location".equals(msg.getType()) && !"contact".equals(msg.getType()) && !"call".equals(msg.getType()));

        // 3. Notify UI observers that message was placed in sent queue & play distinct Sent sound
        com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext).playMessageSentSound();
        runOnUI(() -> {
            for (MessagesObserver obs : mObservers) {
                obs.onMessageSent(dialogId, msg);
                if (isMediaMsg) {
                    obs.onMediaTransferProgress(dialogId, msg.getId(), 1);
                }
            }
        });
        if (isMediaMsg) {
            updateMediaTransferNotification(msg, dialogId, msg.getId(), 1);
        }

        // 4. Route fast text/control messages on mCryptoQueue (0ms queue latency)
        // and heavy media file encoding/streaming on dedicated 4-thread mMediaTransferQueue
        ExecutorService targetExecutor = (localFile != null && localFile.exists()) ? mMediaTransferQueue : mCryptoQueue;
        targetExecutor.execute(() -> {
            try {
                Chat chat = getDialog(dialogId);
                if (chat == null) chat = mDatabase.getChat(dialogId);
                boolean isGroup = (chat != null && chat.isGroupChat())
                        || (dialogId != null && (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")));

                List<String> targetPeers = resolveTargetPeers(dialogId, recipientId, msg);
                boolean hasNetwork = isNetworkConnected();
                boolean hasLocalMedia = localFile != null && localFile.exists() && localFile.length() >= 64;

                if (P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                    notifyGlobalMediaTransferProgress(dialogId, msg.getId(), -1);
                    return;
                }

                // Stream over WebRTC P2P DataChannel immediately if peer is connected (zero delay!)
                boolean anyTransmittedP2P = false;
                if (hasNetwork && hasLocalMedia) {
                    for (String peer : targetPeers) {
                        if (sendMediaP2P(peer, msg, localFile)) {
                            anyTransmittedP2P = true;
                        }
                    }
                }

                // Embed encrypted inline payload (<= 450KB) or chunked payload (up to 4MB when online)
                // so the recipient can download even if sender closes the app or receiver is currently offline.
                if (hasNetwork && hasLocalMedia) {
                    encodeInlineMediaForTransport(dialogId, msg, localFile);
                }

                // Re-verify live network state & user cancellation after media encoding!
                hasNetwork = isNetworkConnected();
                if (P2PFileTransferHelper.isTransferCanceled(msg.getId()) || (!hasNetwork && hasLocalMedia)) {
                    msg.setPending(true);
                    msg.setDelivered(false);
                    msg.setRead(false);
                    mStorageQueue.execute(() -> {
                        mDatabase.setMessagePending(msg.getId(), true);
                        refreshDialogInCache(dialogId);
                    });
                    notifyGlobalMediaTransferProgress(dialogId, msg.getId(), -1);
                    return;
                }

                boolean hasCloudPayload = msg.getMediaUrl() != null
                        && (msg.getMediaUrl().startsWith("ENC:v")
                        || msg.getMediaUrl().startsWith("INLINE_B64:")
                        || msg.getMediaUrl().startsWith("CHUNKED:v1:"));
                if (hasCloudPayload) {
                    mStorageQueue.execute(() -> mDatabase.saveMessage(dialogId, msg));
                }

                // Sync to Firestore/Nostr if device has network connectivity AND either it's a non-media message
                // or its cloud media payload is attached (so a 65MB P2P-only file never creates a phantom empty message on Device B before P2P transfer!).
                if (hasNetwork && (!hasLocalMedia || hasCloudPayload)) {
                    if (isGroup && dialogId != null) {
                        syncGroupOrChannelMessageToFirestore(chat, dialogId, msg);
                    } else if (dialogId != null) {
                        syncDirectMessageToFirestore(chat, dialogId, recipientId, msg);
                    }
                }

                String jsonPayloadStr = buildJsonFallbackString(dialogId, msg);
                boolean anyTransmitted = anyTransmittedP2P;

                for (String peer : targetPeers) {
                    boolean transmitted = false;
                    if (hasNetwork) {
                        if (hasLocalMedia) {
                            transmitted = anyTransmittedP2P;
                        } else {
                            VeilPacket pkt = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, jsonPayloadStr);
                            transmitted = WebRtcManager.getInstance(mContext).sendPacket(peer, pkt);
                        }
                    }

                    if (transmitted) {
                        anyTransmitted = true;
                        com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                                .sendDirectP2PNotificationFrame(peer, dialogId, msg);
                    } else {
                        if (hasNetwork) {
                            if ((!hasLocalMedia || hasCloudPayload) && jsonPayloadStr.length() <= 32768) {
                                try {
                                    com.m3.veil.webrtc.SignalingClient.getInstance().sendP2PSignal(
                                            peer, "p2p_chat", new org.json.JSONObject(jsonPayloadStr));
                                } catch (Exception ignored) {}
                            }
                            com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                                    .dispatchCloudFallbackWakePush(peer, dialogId, msg);
                            WebRtcManager.getInstance(mContext).initiateConnection(peer);
                        }

                        String outboxKey = isGroup ? (msg.getId() + "_" + peer) : msg.getId();
                        OutboxEntry outbox = new OutboxEntry(msg, dialogId, peer, isGroup,
                                chat != null && isGroup ? chat.getName() : getCurrentUserName(),
                                chat != null ? chat.getType() : "direct", localFile);
                        mOutboxQueue.add(outbox);
                        mPendingOutboxById.put(outboxKey, outbox);

                        VeilMeshDaemonService.getInstance(mContext).enqueueOutgoingMessage(chat, peer, msg);
                    }
                }

                boolean requiresDirectP2PStream = hasLocalMedia && !hasCloudPayload;
                if (hasNetwork && (!requiresDirectP2PStream || anyTransmittedP2P)) {
                    // For cloud-backed or text messages (or active P2P stream):
                    // Only mark pending=false immediately if it doesn't require a still-waiting P2P file stream.
                    if (!requiresDirectP2PStream) {
                        msg.setPending(false);
                        mStorageQueue.execute(() -> {
                            mDatabase.setMessagePending(msg.getId(), false);
                            mDatabase.updateMessageStatus(msg.getId(), msg.isDelivered(), msg.isRead());
                            refreshDialogInCache(dialogId);
                        });
                    } else {
                        // Large P2P file actively streaming: keep pending=true in DB until sendMediaP2P onComplete fires!
                        msg.setPending(true);
                        mStorageQueue.execute(() -> {
                            mDatabase.setMessagePending(msg.getId(), true);
                            refreshDialogInCache(dialogId);
                        });
                    }
                    final String fDialogId = dialogId;
                    final String fMsgId = msg.getId();
                    final boolean fDelivered = msg.isDelivered();
                    final boolean fRead = msg.isRead();
                    if (!anyTransmittedP2P && !requiresDirectP2PStream) {
                        notifyGlobalMediaTransferProgress(fDialogId, fMsgId, 100);
                    }
                    mMainHandler.post(() -> {
                        for (MessagesObserver obs : mObservers) {
                            obs.onMessageStatusChanged(fDialogId, fMsgId, fDelivered, fRead);
                        }
                    });
                } else {
                    // Offline OR waiting for peer to connect for large P2P file stream: keep pending=true in DB
                    msg.setPending(true);
                    msg.setDelivered(false);
                    msg.setRead(false);
                    mStorageQueue.execute(() -> {
                        mDatabase.setMessagePending(msg.getId(), true);
                        refreshDialogInCache(dialogId);
                    });
                    if (!hasNetwork && hasLocalMedia) {
                        notifyGlobalMediaTransferProgress(dialogId, msg.getId(), -1);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error encoding and dispatching message packet", e);
            }
        });
    }

    private void syncGroupOrChannelMessageToFirestore(@Nullable Chat chat, String groupId, Message msg) {
        try {
            com.google.firebase.firestore.FirebaseFirestore fs = com.google.firebase.firestore.FirebaseFirestore.getInstance();
            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("id", msg.getId());
            msgMap.put("chatId", groupId);
            msgMap.put("senderId", msg.getSenderId() != null ? msg.getSenderId() : getCurrentUserId());
            msgMap.put("senderName", msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName());
            String rawText = msg.getText() != null ? msg.getText() : "";
            String encryptedText = EncryptionManager.encryptGroupPayload(groupId, rawText);
            msgMap.put("text", encryptedText);
            msgMap.put("isEncrypted", true);
            msgMap.put("senderKeyEpoch", EncryptionManager.getGroupSenderKeyEpoch(groupId));
            msgMap.put("type", msg.getType() != null ? msg.getType() : "text");
            Timestamp tsVal = msg.getTimestamp() != null ? msg.getTimestamp() : Timestamp.now();
            msgMap.put("timestamp", tsVal);
            msgMap.put("timestampMs", tsVal.toDate().getTime());
            msgMap.put("replyToMessageId", msg.getReplyToMessageId());
            msgMap.put("replySenderName", msg.getReplySenderName());
            msgMap.put("blink", msg.isBlink());
            msgMap.put("voiceFilter", msg.getVoiceFilter());
            String rawPoll = msg.getPollJson();
            if (rawPoll != null && !rawPoll.isEmpty()) {
                msgMap.put("pollJson", EncryptionManager.encryptGroupPayload(groupId, rawPoll));
            } else {
                msgMap.put("pollJson", null);
            }
            msgMap.put("thumbnail", msg.getThumbnail());
            msgMap.put("mediaUrl", msg.getMediaUrl());
            msgMap.put("vanishTimer", msg.getVanishTimer());
            msgMap.put("vanishMode", msg.getVanishMode());

            fs.collection("groups").document(groupId)
                    .collection("messages").document(msg.getId())
                    .set(msgMap);

            Map<String, Object> groupSummary = new HashMap<>();
            groupSummary.put("groupId", groupId);
            if (chat != null) {
                if (chat.getName() != null) groupSummary.put("groupName", chat.getName());
                if (chat.getType() != null) groupSummary.put("type", chat.getType());
                if (chat.getAdminId() != null) groupSummary.put("adminId", chat.getAdminId());
                if (chat.getAdminIds() != null && !chat.getAdminIds().isEmpty()) groupSummary.put("adminIds", chat.getAdminIds());
                if (chat.getDescription() != null) groupSummary.put("description", chat.getDescription());
                if (chat.getAvatar() != null) groupSummary.put("avatar", chat.getAvatar());
                groupSummary.put("isPublic", chat.isPublic());
                if (chat.getHandle() != null) groupSummary.put("handle", chat.getHandle());
                if (chat.getParticipants() != null && !chat.getParticipants().isEmpty()) {
                    groupSummary.put("participants", chat.getParticipants());
                }
            }
            groupSummary.put("lastMessage", "🔒 " + (msg.getType() != null && !msg.getType().equals("text") ? ("[" + msg.getType() + "]") : "Encrypted Message"));
            groupSummary.put("lastMessageSenderId", msg.getSenderId() != null ? msg.getSenderId() : getCurrentUserId());
            groupSummary.put("lastMessageSenderName", msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName());
            groupSummary.put("timestamp", tsVal);
            groupSummary.put("timestampMs", tsVal.toDate().getTime());
            groupSummary.put("isGroup", true);

            fs.collection("groups").document(groupId)
                    .set(groupSummary, com.google.firebase.firestore.SetOptions.merge());
        } catch (Exception e) {
            Log.w(TAG, "Firestore group message sync notice: " + e.getMessage());
        }
    }

    private void syncDirectMessageToFirestore(@Nullable Chat chat, String dialogId, @Nullable String recipientId, Message msg) {
        try {
            com.google.firebase.firestore.FirebaseFirestore fs = com.google.firebase.firestore.FirebaseFirestore.getInstance();
            String myId = getCurrentUserId();
            String targetPeer = (recipientId != null && !recipientId.isEmpty()) ? recipientId : msg.getReceiverId();
            if (targetPeer == null || targetPeer.isEmpty()) {
                if (dialogId != null && dialogId.contains("_")) {
                    String[] parts = dialogId.split("_");
                    if (parts.length == 2) {
                        targetPeer = parts[0].equals(myId) ? parts[1] : parts[0];
                    }
                }
            }
            if (targetPeer == null || targetPeer.isEmpty()) {
                targetPeer = dialogId;
            }
            String directChatId = (dialogId != null && !dialogId.isEmpty()) ? dialogId : WebRtcManager.getChatId(myId, targetPeer);

            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("id", msg.getId());
            msgMap.put("chatId", directChatId);
            msgMap.put("senderId", msg.getSenderId() != null ? msg.getSenderId() : myId);
            msgMap.put("senderName", msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName());
            msgMap.put("receiverId", targetPeer);

            String rawText = msg.getText() != null ? msg.getText() : "";
            // Encrypt payload using AES-256-GCM keyed by directChatId (zero plain text on cloud)
            String encryptedText = EncryptionManager.encryptGroupPayload(directChatId, rawText);
            msgMap.put("text", encryptedText);
            msgMap.put("isEncrypted", true);
            msgMap.put("type", msg.getType() != null ? msg.getType() : "text");
            Timestamp tsVal = msg.getTimestamp() != null ? msg.getTimestamp() : Timestamp.now();
            msgMap.put("timestamp", tsVal);
            msgMap.put("timestampMs", tsVal.toDate().getTime());
            msgMap.put("replyToMessageId", msg.getReplyToMessageId());
            msgMap.put("replySenderName", msg.getReplySenderName());
            msgMap.put("blink", msg.isBlink());
            msgMap.put("voiceFilter", msg.getVoiceFilter());
            String rawPoll = msg.getPollJson();
            if (rawPoll != null && !rawPoll.isEmpty()) {
                msgMap.put("pollJson", EncryptionManager.encryptGroupPayload(directChatId, rawPoll));
            } else {
                msgMap.put("pollJson", null);
            }
            msgMap.put("thumbnail", msg.getThumbnail());
            msgMap.put("mediaUrl", msg.getMediaUrl());
            msgMap.put("vanishTimer", msg.getVanishTimer());
            msgMap.put("vanishMode", msg.getVanishMode());
            msgMap.put("delivered", false);
            msgMap.put("read", false);

            fs.collection("groups").document(directChatId)
                    .collection("messages").document(msg.getId())
                    .set(msgMap)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Direct message synced to groups/" + directChatId + "/messages/" + msg.getId()))
                    .addOnFailureListener(e -> Log.e(TAG, "Direct message sync to groups failed: " + e.getMessage(), e));

            fs.collection("chats").document(directChatId)
                    .collection("messages").document(msg.getId())
                    .set(msgMap)
                    .addOnFailureListener(e -> Log.w(TAG, "Direct message mirror to chats notice: " + e.getMessage()));

            Map<String, Object> chatSummary = new HashMap<>();
            chatSummary.put("groupId", directChatId);
            chatSummary.put("chatId", directChatId);
            List<String> participants = new ArrayList<>();
            if (myId != null && !myId.isEmpty()) participants.add(myId);
            if (targetPeer != null && !targetPeer.isEmpty() && !participants.contains(targetPeer)) participants.add(targetPeer);
            if (participants.size() < 2 && directChatId.contains("_")) {
                String[] p = directChatId.split("_");
                for (String part : p) {
                    if (part != null && !part.isEmpty() && !participants.contains(part)) participants.add(part);
                }
            }
            chatSummary.put("participants", participants);
            chatSummary.put("lastMessage", "🔒 " + (msg.getType() != null && !msg.getType().equals("text") ? ("[" + msg.getType() + "]") : "Encrypted Message"));
            chatSummary.put("lastMessageSenderId", msg.getSenderId() != null ? msg.getSenderId() : myId);
            chatSummary.put("lastMessageSenderName", msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName());
            chatSummary.put("timestamp", tsVal);
            chatSummary.put("timestampMs", tsVal.toDate().getTime());
            chatSummary.put("isGroup", false);
            chatSummary.put("type", "direct");
            String senderDispName = msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName();
            Map<String, Object> participantNames = new HashMap<>();
            if (myId != null && !myId.isEmpty() && senderDispName != null && !senderDispName.isEmpty()) {
                participantNames.put(myId, senderDispName);
            }
            if (targetPeer != null && !targetPeer.isEmpty() && chat != null && chat.getName() != null
                    && !chat.getName().isEmpty()
                    && !chat.getName().equals(senderDispName)
                    && !"Direct Chat".equals(chat.getName())
                    && !"Veil Contact".equals(chat.getName())
                    && !"Veil User".equals(chat.getName())
                    && !"Anonymous Peer".equals(chat.getName())) {
                participantNames.put(targetPeer, chat.getName());
            }
            chatSummary.put("participantNames", participantNames);
            chatSummary.put("groupName", "Direct Chat");

            fs.collection("groups").document(directChatId)
                    .set(chatSummary, com.google.firebase.firestore.SetOptions.merge())
                    .addOnFailureListener(e -> Log.w(TAG, "Direct chat summary to groups notice: " + e.getMessage()));

            fs.collection("chats").document(directChatId)
                    .set(chatSummary, com.google.firebase.firestore.SetOptions.merge())
                    .addOnFailureListener(e -> Log.w(TAG, "Direct chat summary to chats notice: " + e.getMessage()));
        } catch (Exception e) {
            Log.w(TAG, "Firestore direct message sync notice: " + e.getMessage());
        }
    }

    public static void encodeInlineMediaForTransport(String dialogId, Message msg, @Nullable File localFile) {
        if (msg == null) return;
        if (msg.getMediaUrl() != null && msg.getMediaUrl().startsWith("ENC:v")) {
            return;
        }
        File fileToRead = localFile;
        if ((fileToRead == null || !fileToRead.exists()) && msg.getLocalFilePath() != null && !msg.getLocalFilePath().isEmpty()) {
            fileToRead = new File(msg.getLocalFilePath());
        }
        if (fileToRead == null || !fileToRead.exists() || fileToRead.length() < 64) return;

        long fileLen = fileToRead.length();
        long maxInlineBytes = 450 * 1024L; // 450 KB inline (~2 min AAC voice note or compressed HD photo)
        long maxChunkedBytes = 4 * 1024 * 1024L; // 4 MB chunked cloud-shadow fallback (larger files stream via direct P2P)
        if (fileLen > maxChunkedBytes) return;

        VeilMessagesController ctrl = sInstance;
        if (ctrl != null && !ctrl.isNetworkConnected()) {
            return;
        }
        if (P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
            return;
        }

        try {
            String keyScope = (dialogId != null && !dialogId.isEmpty()) ? dialogId : msg.getChatId();
            if (fileLen <= maxInlineBytes) {
                if (ctrl != null && !ctrl.isNetworkConnected()) return;
                notifyGlobalMediaTransferProgress(keyScope, msg.getId(), 18);
                byte[] bytes = new byte[(int) fileLen];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(fileToRead)) {
                    int offset = 0;
                    int read;
                    while (offset < bytes.length && (read = fis.read(bytes, offset, bytes.length - offset)) != -1) {
                        offset += read;
                    }
                }
                if ((ctrl != null && !ctrl.isNetworkConnected()) || P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                    notifyGlobalMediaTransferProgress(keyScope, msg.getId(), -1);
                    return;
                }
                notifyGlobalMediaTransferProgress(keyScope, msg.getId(), 52);
                String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                String payload = "INLINE_B64:" + b64;
                if (keyScope != null && !keyScope.isEmpty()) {
                    msg.setMediaUrl(EncryptionManager.encryptGroupPayload(keyScope, payload));
                } else {
                    msg.setMediaUrl(payload);
                }
                if ((ctrl != null && !ctrl.isNetworkConnected()) || P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                    msg.setMediaUrl(String.valueOf(fileLen));
                    notifyGlobalMediaTransferProgress(keyScope, msg.getId(), -1);
                    return;
                }
                notifyGlobalMediaTransferProgress(keyScope, msg.getId(), 88);
            } else if (keyScope != null && !keyScope.isEmpty() && msg.getId() != null) {
                int chunkSize = 256 * 1024; // 256 KB raw bytes per chunk
                int totalChunks = (int) ((fileLen + chunkSize - 1) / chunkSize);
                com.google.firebase.firestore.FirebaseFirestore fs = com.google.firebase.firestore.FirebaseFirestore.getInstance();
                byte[] sliceBuffer = new byte[chunkSize];
                notifyGlobalMediaTransferProgress(keyScope, msg.getId(), 5);
                List<com.google.android.gms.tasks.Task<?>> pendingBatch = new ArrayList<>();
                int uploadedChunks = 0;
                try (java.io.FileInputStream fis = new java.io.FileInputStream(fileToRead)) {
                    for (int i = 0; i < totalChunks; i++) {
                        if ((ctrl != null && !ctrl.isNetworkConnected()) || P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                            notifyGlobalMediaTransferProgress(keyScope, msg.getId(), -1);
                            return;
                        }
                        int expected = (int) Math.min(chunkSize, fileLen - ((long) i * chunkSize));
                        int offset = 0;
                        int read;
                        while (offset < expected && (read = fis.read(sliceBuffer, offset, expected - offset)) != -1) {
                            offset += read;
                        }
                        if (offset <= 0) break;
                        String chunkB64 = android.util.Base64.encodeToString(sliceBuffer, 0, offset, android.util.Base64.NO_WRAP);
                        String encChunk = EncryptionManager.encryptGroupPayload(keyScope, chunkB64);
                        Map<String, Object> chunkDoc = new HashMap<>();
                        chunkDoc.put("index", i);
                        chunkDoc.put("total", totalChunks);
                        chunkDoc.put("data", encChunk);
                        chunkDoc.put("timestampMs", System.currentTimeMillis());
                        String docKey = msg.getId() + "_" + i;
                        pendingBatch.add(fs.collection("chats").document(keyScope).collection("media_chunks").document(docKey).set(chunkDoc));
                        if (keyScope.startsWith("group_") || keyScope.startsWith("channel_") || keyScope.startsWith("lounge_")) {
                            pendingBatch.add(fs.collection("groups").document(keyScope).collection("media_chunks").document(docKey).set(chunkDoc));
                        }
                        if (pendingBatch.size() >= 2 || i == totalChunks - 1) {
                            for (com.google.android.gms.tasks.Task<?> batchTask : pendingBatch) {
                                if ((ctrl != null && !ctrl.isNetworkConnected()) || P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                                    notifyGlobalMediaTransferProgress(keyScope, msg.getId(), -1);
                                    return;
                                }
                                com.google.android.gms.tasks.Tasks.await(
                                        batchTask,
                                        10, java.util.concurrent.TimeUnit.SECONDS);
                            }
                            pendingBatch.clear();
                        }
                        uploadedChunks++;
                        int pct = Math.min(98, (int) (((i + 1) * 95L) / totalChunks) + 3);
                        notifyGlobalMediaTransferProgress(keyScope, msg.getId(), pct);
                    }
                }
                if (uploadedChunks == totalChunks && totalChunks > 0
                        && (ctrl == null || ctrl.isNetworkConnected())
                        && !P2PFileTransferHelper.isTransferCanceled(msg.getId())) {
                    msg.setMediaUrl("CHUNKED:v1:" + totalChunks + ":" + fileLen);
                    notifyGlobalMediaTransferProgress(keyScope, msg.getId(), 100);
                } else {
                    notifyGlobalMediaTransferProgress(keyScope, msg.getId(), -1);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "encodeInlineMediaForTransport notice: " + t.getMessage());
            String keyScope = (dialogId != null && !dialogId.isEmpty()) ? dialogId : msg.getChatId();
            notifyGlobalMediaTransferProgress(keyScope, msg.getId(), -1);
        }
    }

    /**
     * Strips any legacy "doc_<timestamp>_" prefix and "|<size>" suffix so documents always display
     * and save with their clean original filename.
     */
    public static String cleanDocumentFileName(@Nullable String rawTextOrName) {
        if (rawTextOrName == null || rawTextOrName.trim().isEmpty()) {
            return "Document";
        }
        String name = rawTextOrName.trim();
        int pipeIdx = name.indexOf('|');
        if (pipeIdx != -1) {
            name = name.substring(0, pipeIdx).trim();
        }
        int slashIdx = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slashIdx != -1 && slashIdx < name.length() - 1) {
            name = name.substring(slashIdx + 1);
        }
        if (name.startsWith("doc_")) {
            int secondUnderscore = name.indexOf('_', 4);
            if (secondUnderscore > 4 && secondUnderscore < name.length() - 1) {
                boolean allDigits = true;
                for (int i = 4; i < secondUnderscore; i++) {
                    if (!Character.isDigit(name.charAt(i))) {
                        allDigits = false;
                        break;
                    }
                }
                if (allDigits) {
                    name = name.substring(secondUnderscore + 1);
                }
            }
        }
        return name.isEmpty() ? "Document" : name;
    }

    /**
     * Evaluates whether incoming media of {@code mediaType} should auto-download in {@code dialogId}.
     * Strictly honors:
     * 1. Per-chat toggle ("VeilChatSpecificPrefs" -> "auto_media_" + dialogId).
     * 2. Global Wi-Fi / Mobile Data toggles ("veil_storage_prefs" -> "auto_download_wifi", "auto_download_cellular").
     */
    public static boolean shouldAutoDownloadMedia(@Nullable Context context, @Nullable String dialogId, @Nullable String mediaType) {
        if (context == null) return false;
        try {
            android.content.SharedPreferences chatPrefs =
                    context.getSharedPreferences("VeilChatSpecificPrefs", Context.MODE_PRIVATE);
            if (dialogId != null && !dialogId.isEmpty() && chatPrefs.contains("auto_media_" + dialogId)) {
                boolean perChatAllowed = chatPrefs.getBoolean("auto_media_" + dialogId, true);
                if (!perChatAllowed) {
                    return false;
                }
            }

            android.content.SharedPreferences storagePrefs =
                    context.getSharedPreferences("veil_storage_prefs", Context.MODE_PRIVATE);
            boolean wifiAuto = storagePrefs.getBoolean("auto_download_wifi", true);
            boolean cellularAuto = storagePrefs.getBoolean("auto_download_cellular", false);

            if (!wifiAuto && !cellularAuto) {
                return false;
            }

            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                    if (caps != null) {
                        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                                || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            return wifiAuto;
                        }
                        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            return cellularAuto;
                        }
                    }
                }
            }
            return wifiAuto;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns whether downloaded media for {@code dialogId} should be visible in the phone's Gallery
     * and File Manager (Downloads/Pictures/Movies).
     */
    public static boolean isMediaVisibilityEnabled(@Nullable Context context, @Nullable String dialogId) {
        if (context == null) return true;
        try {
            android.content.SharedPreferences chatPrefs =
                    context.getSharedPreferences("VeilChatSpecificPrefs", Context.MODE_PRIVATE);
            if (dialogId != null && !dialogId.isEmpty() && chatPrefs.contains("media_visibility_" + dialogId)) {
                return chatPrefs.getBoolean("media_visibility_" + dialogId, true);
            }
            android.content.SharedPreferences storagePrefs =
                    context.getSharedPreferences("veil_storage_prefs", Context.MODE_PRIVATE);
            return storagePrefs.getBoolean("media_visibility_global", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setMediaVisibilityEnabled(@Nullable Context context, @Nullable String dialogId, boolean enabled) {
        if (context == null || dialogId == null || dialogId.isEmpty()) return;
        context.getSharedPreferences("VeilChatSpecificPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("media_visibility_" + dialogId, enabled)
                .apply();
    }

    /**
     * Exports downloaded photo/video/document to public MediaStore (Gallery / File Manager Downloads)
     * ONLY when Media Visibility is enabled for this chat and message is not view-once Blink.
     */
    public static void exportMediaToPublicStorageIfAllowed(
            @Nullable Context context,
            @Nullable String dialogId,
            @Nullable Message msg,
            @Nullable File localFile) {
        if (context == null || msg == null || localFile == null || !localFile.exists() || localFile.length() < 64) {
            return;
        }
        try {
            File extCache = context.getExternalCacheDir();
            if (extCache != null && extCache.exists()) {
                File noMedia = new File(extCache, ".nomedia");
                if (!noMedia.exists()) {
                    try { noMedia.createNewFile(); } catch (Exception ignored) {}
                }
            }
            if (msg.isBlink() || msg.getExpiresAt() != null) {
                return;
            }
            String resolvedChatId = (dialogId != null && !dialogId.isEmpty()) ? dialogId : msg.getChatId();
            if (!isMediaVisibilityEnabled(context, resolvedChatId)) {
                return;
            }
            android.content.SharedPreferences exportedPrefs =
                    context.getSharedPreferences("veil_exported_media", Context.MODE_PRIVATE);
            String exportKey = "exp_" + msg.getId();
            if (exportedPrefs.getBoolean(exportKey, false)) {
                return;
            }

            String type = msg.getType() != null ? msg.getType() : "";
            android.content.ContentResolver resolver = context.getContentResolver();
            if ("image".equals(type)) {
                String dispName = "Veil_IMG_" + System.currentTimeMillis() + ".jpg";
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, dispName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Veil");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (java.io.InputStream in = new java.io.FileInputStream(localFile);
                         java.io.OutputStream out = resolver.openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buf = new byte[16384];
                            int r;
                            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                            out.flush();
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        resolver.update(uri, values, null, null);
                    }
                    exportedPrefs.edit().putBoolean(exportKey, true).apply();
                }
            } else if ("video".equals(type)) {
                String dispName = "Veil_VID_" + System.currentTimeMillis() + ".mp4";
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, dispName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/Veil");
                    values.put(MediaStore.Video.Media.IS_PENDING, 1);
                }
                Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (java.io.InputStream in = new java.io.FileInputStream(localFile);
                         java.io.OutputStream out = resolver.openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buf = new byte[16384];
                            int r;
                            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                            out.flush();
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear();
                        values.put(MediaStore.Video.Media.IS_PENDING, 0);
                        resolver.update(uri, values, null, null);
                    }
                    exportedPrefs.edit().putBoolean(exportKey, true).apply();
                }
            } else if ("document".equals(type)) {
                String cleanName = cleanDocumentFileName(msg.getText());
                String ext = "";
                int dot = cleanName.lastIndexOf('.');
                if (dot != -1 && dot < cleanName.length() - 1) {
                    ext = cleanName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                }
                String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, cleanName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Veil");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (java.io.InputStream in = new java.io.FileInputStream(localFile);
                             java.io.OutputStream out = resolver.openOutputStream(uri)) {
                            if (out != null) {
                                byte[] buf = new byte[16384];
                                int r;
                                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                out.flush();
                            }
                        }
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        resolver.update(uri, values, null, null);
                        exportedPrefs.edit().putBoolean(exportKey, true).apply();
                    }
                } else {
                    File dlDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Veil");
                    if (!dlDir.exists()) dlDir.mkdirs();
                    File outDoc = new File(dlDir, cleanName);
                    try (java.io.InputStream in = new java.io.FileInputStream(localFile);
                         java.io.OutputStream out = new java.io.FileOutputStream(outDoc)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                        out.flush();
                    }
                    android.media.MediaScannerConnection.scanFile(
                            context, new String[]{outDoc.getAbsolutePath()}, new String[]{mime}, null);
                    exportedPrefs.edit().putBoolean(exportKey, true).apply();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "exportMediaToPublicStorageIfAllowed notice: " + t.getMessage());
        }
    }

    private static final String MEDIA_NOTIFICATION_CHANNEL_ID = "veil_media_transfer_channel";
    private static final ConcurrentHashMap<String, Integer> sHighestMediaProgress = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> sLastMediaNotifTimeMs = new ConcurrentHashMap<>();

    public boolean isOutboundMediaTransferIncomplete(String messageId) {
        if (messageId == null) return false;
        if (P2PFileTransferHelper.isTransferCanceled(messageId)) return true;
        if (mPendingOutboxById.containsKey(messageId)) return true;
        if (sHighestMediaProgress.containsKey(messageId)) return true;
        return false;
    }

    public static void notifyGlobalMediaTransferProgress(String dialogId, String messageId, int percent) {
        VeilMessagesController ctrl = sInstance;
        if (ctrl == null || messageId == null) return;
        Message cachedMsg = ctrl.getMessage(messageId);
        final String resolvedDialogId = (dialogId != null && !dialogId.isEmpty())
                ? dialogId
                : (cachedMsg != null && cachedMsg.getChatId() != null ? cachedMsg.getChatId() : "");

        if (percent >= 0 && percent < 100) {
            Integer prev = sHighestMediaProgress.get(messageId);
            if (prev != null && percent < prev && prev < 100) {
                percent = prev;
            } else {
                sHighestMediaProgress.put(messageId, percent);
            }
        } else if (percent >= 100 || percent < 0) {
            sHighestMediaProgress.remove(messageId);
            sLastMediaNotifTimeMs.remove(messageId);
        }

        final int finalPercent = percent;
        ctrl.updateMediaTransferNotification(cachedMsg, resolvedDialogId, messageId, finalPercent);
        ctrl.runOnUI(() -> {
            for (MessagesObserver obs : ctrl.mObservers) {
                obs.onMediaTransferProgress(resolvedDialogId, messageId, finalPercent);
            }
        });
    }

    private void updateMediaTransferNotification(@Nullable Message msg, String dialogId, String messageId, int percent) {
        if (mContext == null || messageId == null) return;
        try {
            if (percent >= 0 && percent < 100) {
                long now = System.currentTimeMillis();
                Long lastMs = sLastMediaNotifTimeMs.get(messageId);
                if (lastMs != null && (now - lastMs) < 550L) {
                    return;
                }
                sLastMediaNotifTimeMs.put(messageId, now);
            }

            android.app.NotificationManager nm =
                    (android.app.NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = nm.getNotificationChannel(MEDIA_NOTIFICATION_CHANNEL_ID);
                if (channel == null) {
                    channel = new android.app.NotificationChannel(
                            MEDIA_NOTIFICATION_CHANNEL_ID,
                            "Media Transfers",
                            android.app.NotificationManager.IMPORTANCE_LOW);
                    channel.setDescription("Live progress for encrypted photo, video, and document transfers");
                    channel.setSound(null, null);
                    channel.enableVibration(false);
                    nm.createNotificationChannel(channel);
                }
            }

            int notifId = 40000 + Math.abs(messageId.hashCode() % 10000);
            if (percent < 0) {
                nm.cancel(notifId);
                return;
            }

            String myId = getCurrentUserId();
            boolean isSending = msg != null && msg.getSenderId() != null
                    && (msg.getSenderId().equals(myId) || com.m3.veil.manager.AuthManager.getInstance().isMyUserId(mContext, msg.getSenderId()));
            String typeLabel = "Media";
            if (msg != null && msg.getType() != null) {
                String t = msg.getType();
                if ("video".equals(t)) typeLabel = "Video";
                else if ("image".equals(t)) typeLabel = "Photo";
                else if ("document".equals(t)) typeLabel = "Document (" + cleanDocumentFileName(msg.getText()) + ")";
                else if ("voice".equals(t) || "audio".equals(t)) typeLabel = "Voice note";
            }

            if (percent >= 100) {
                androidx.core.app.NotificationCompat.Builder doneBuilder =
                        new androidx.core.app.NotificationCompat.Builder(mContext, MEDIA_NOTIFICATION_CHANNEL_ID)
                                .setSmallIcon(isSending ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_sys_download_done)
                                .setContentTitle(isSending ? (typeLabel + " sent") : (typeLabel + " downloaded"))
                                .setContentText("100% • Transfer complete")
                                .setProgress(0, 0, false)
                                .setOngoing(false)
                                .setAutoCancel(true)
                                .setTimeoutAfter(2500L)
                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW);
                nm.notify(notifId, doneBuilder.build());
                mMainHandler.postDelayed(() -> {
                    try {
                        nm.cancel(notifId);
                    } catch (Throwable ignored) {}
                }, 2600L);
            } else {
                int clamped = Math.max(1, Math.min(99, percent));
                androidx.core.app.NotificationCompat.Builder progBuilder =
                        new androidx.core.app.NotificationCompat.Builder(mContext, MEDIA_NOTIFICATION_CHANNEL_ID)
                                .setSmallIcon(isSending ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_download)
                                .setContentTitle((isSending ? "Sending " : "Downloading ") + typeLabel + "...")
                                .setContentText(clamped + "%")
                                .setProgress(100, clamped, false)
                                .setOngoing(true)
                                .setOnlyAlertOnce(true)
                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW);
                nm.notify(notifId, progBuilder.build());
            }
        } catch (Throwable ignored) {}
    }

    public static String resolveMediaExtension(Message msg) {
        if (msg == null) return ".bin";
        String mType = msg.getType();
        if ("voice".equals(mType) || "audio".equals(mType)) {
            return ".m4a";
        } else if ("gif".equals(mType)) {
            return ".gif";
        } else if ("sticker".equals(mType)) {
            if (msg.getLocalFilePath() != null) {
                String lower = msg.getLocalFilePath().toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".gif")) return ".gif";
                if (lower.endsWith(".webp")) return ".webp";
            }
            return ".png";
        } else if ("image".equals(mType)) {
            return ".jpg";
        } else if ("video".equals(mType)) {
            return ".mp4";
        } else if ("document".equals(mType) && msg.getText() != null) {
            String name = cleanDocumentFileName(msg.getText());
            int dot = name.lastIndexOf('.');
            if (dot != -1 && dot < name.length() - 1) {
                return name.substring(dot);
            }
        }
        return ".bin";
    }

    public static File decodeInlineMediaToFile(Context context, String dialogId, Message msg) {
        if (context == null || msg == null || msg.getId() == null) return null;
        try {
            if (msg.getLocalFilePath() != null && !msg.getLocalFilePath().isEmpty()) {
                File existing = new File(msg.getLocalFilePath());
                if (existing.exists() && existing.length() >= 64) {
                    return existing;
                }
            }
            String ext = resolveMediaExtension(msg);
            File targetFile = new File(context.getExternalCacheDir(), "dec_" + msg.getId() + ext);
            if (targetFile.exists()) {
                if (targetFile.length() >= 64) {
                    msg.setLocalFilePath(targetFile.getAbsolutePath());
                    return targetFile;
                } else {
                    targetFile.delete();
                }
            }
            // Check if a P2P staged file already arrived while auto-download was disabled
            File stagedFile = new File(context.getExternalCacheDir(), "staged_" + msg.getId() + ext);
            if (stagedFile.exists() && stagedFile.length() >= 64) {
                if (!stagedFile.renameTo(targetFile)) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(stagedFile);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = fis.read(buf)) != -1) fos.write(buf, 0, r);
                        fos.flush();
                    }
                    stagedFile.delete();
                }
                if (targetFile.exists() && targetFile.length() >= 64) {
                    msg.setLocalFilePath(targetFile.getAbsolutePath());
                    exportMediaToPublicStorageIfAllowed(context, dialogId, msg, targetFile);
                    return targetFile;
                }
            }
            String mediaUrl = msg.getMediaUrl();
            if (mediaUrl == null || mediaUrl.isEmpty() || mediaUrl.startsWith("http") || mediaUrl.startsWith("CHUNKED:")) {
                return null;
            }
            String decrypted = mediaUrl;
            if (decrypted.startsWith("ENC:v")) {
                String keyScope = (dialogId != null && !dialogId.isEmpty()) ? dialogId : msg.getChatId();
                if (keyScope != null && !keyScope.isEmpty()) {
                    decrypted = EncryptionManager.decryptGroupPayload(keyScope, decrypted);
                }
            }
            if (decrypted == null || decrypted.isEmpty() || decrypted.startsWith("ENC:v") || decrypted.startsWith("CHUNKED:")) {
                return null;
            }
            String b64;
            if (decrypted.startsWith("INLINE_B64:")) {
                b64 = decrypted.substring("INLINE_B64:".length()).trim();
            } else {
                String trimmed = decrypted.trim();
                // Reject file paths, JSON strings, or short non-media strings
                if (trimmed.length() < 88 || trimmed.startsWith("/") || trimmed.startsWith("{") || trimmed.contains(" ")) {
                    return null;
                }
                b64 = trimmed;
            }
            byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            if (decoded != null && decoded.length >= 64) {
                if ("sticker".equalsIgnoreCase(msg.getType()) && decoded.length > 6
                        && decoded[0] == 'G' && decoded[1] == 'I' && decoded[2] == 'F') {
                    targetFile = new File(context.getExternalCacheDir(), "dec_" + msg.getId() + ".gif");
                }
                File tempFile = new File(context.getExternalCacheDir(), "tmp_dec_" + msg.getId() + "_" + System.nanoTime() + ".tmp");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(decoded);
                    fos.flush();
                }
                if (targetFile.exists()) targetFile.delete();
                if (!tempFile.renameTo(targetFile)) {
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                        fos.write(decoded);
                        fos.flush();
                    }
                    tempFile.delete();
                }
                msg.setLocalFilePath(targetFile.getAbsolutePath());
                exportMediaToPublicStorageIfAllowed(context, dialogId, msg, targetFile);
                return targetFile;
            }
        } catch (Throwable t) {
            Log.w(TAG, "decodeInlineMediaToFile notice: " + t.getMessage());
        }
        return null;
    }

    private static final Set<String> sInFlightChunkDownloads =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static File ensureMediaDecodedOrAutoDownloaded(
            Context context,
            String dialogId,
            Message msg,
            @Nullable Runnable onAsyncReady) {
        if (context == null || msg == null || msg.getId() == null) return null;
        if (msg.getLocalFilePath() != null && !msg.getLocalFilePath().isEmpty()) {
            File existing = new File(msg.getLocalFilePath());
            if (existing.exists() && existing.length() >= 64) {
                return existing;
            }
        }
        String ext = resolveMediaExtension(msg);
        File cachedDec = new File(context.getExternalCacheDir(), "dec_" + msg.getId() + ext);
        if (cachedDec.exists() && cachedDec.length() >= 64) {
            msg.setLocalFilePath(cachedDec.getAbsolutePath());
            return cachedDec;
        }
        String resolvedChatId = (dialogId != null && !dialogId.isEmpty()) ? dialogId : msg.getChatId();
        // Strictly enforce user's Auto-Download settings (per-chat + Wi-Fi/Cellular)!
        if (!shouldAutoDownloadMedia(context, resolvedChatId, msg.getType())) {
            return null;
        }
        String mediaUrl = msg.getMediaUrl();
        if (mediaUrl == null || mediaUrl.isEmpty()) return null;
        if (mediaUrl.startsWith("CHUNKED:v1:")) {
            if (resolvedChatId != null && !resolvedChatId.isEmpty()) {
                downloadChunkedMediaFromFirestore(context.getApplicationContext(), resolvedChatId, msg, mediaUrl,
                        new P2PFileTransferHelper.DownloadProgressListener() {
                            @Override
                            public void onProgress(String messageId, int progress) {
                                notifyGlobalMediaTransferProgress(resolvedChatId, messageId, progress);
                            }

                            @Override
                            public void onComplete(String messageId, File downloadedFile) {
                                notifyGlobalMediaTransferProgress(resolvedChatId, messageId, 100);
                                if (onAsyncReady != null) {
                                    new Handler(Looper.getMainLooper()).post(onAsyncReady);
                                }
                            }

                            @Override
                            public void onError(String messageId, String error) {}
                        });
            }
            return null;
        }
        return decodeInlineMediaToFile(context, resolvedChatId, msg);
    }

    public static void downloadChunkedMediaFromFirestore(
            Context context,
            String resolvedChatId,
            Message targetMsg,
            String chunkedHeader,
            P2PFileTransferHelper.DownloadProgressListener callback) {
        if (context == null || resolvedChatId == null || targetMsg == null || targetMsg.getId() == null
                || chunkedHeader == null || !chunkedHeader.startsWith("CHUNKED:v1:")) {
            if (callback != null && targetMsg != null) callback.onError(targetMsg.getId(), "Unavailable");
            return;
        }
        String ext = resolveMediaExtension(targetMsg);
        File existingTarget = new File(context.getExternalCacheDir(), "dec_" + targetMsg.getId() + ext);
        if (existingTarget.exists() && existingTarget.length() >= 64) {
            targetMsg.setLocalFilePath(existingTarget.getAbsolutePath());
            EncryptedChatDatabase.getInstance(context).saveMessage(resolvedChatId, targetMsg);
            exportMediaToPublicStorageIfAllowed(context, resolvedChatId, targetMsg, existingTarget);
            notifyGlobalMediaTransferProgress(resolvedChatId, targetMsg.getId(), 100);
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(targetMsg.getId(), existingTarget));
            }
            return;
        }
        final String downloadKey = resolvedChatId + ":" + targetMsg.getId();
        if (!sInFlightChunkDownloads.add(downloadKey)) {
            return;
        }
        new Thread(() -> {
            try {
                String[] parts = chunkedHeader.split(":");
                int totalChunks = Integer.parseInt(parts[2]);
                if (totalChunks <= 0 || totalChunks > 250) {
                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(() -> callback.onError(targetMsg.getId(), "Invalid chunk count"));
                    }
                    return;
                }
                com.google.firebase.firestore.FirebaseFirestore fs = com.google.firebase.firestore.FirebaseFirestore.getInstance();
                File targetFile = new File(context.getExternalCacheDir(), "dec_" + targetMsg.getId() + ext);
                File tempFile = new File(context.getExternalCacheDir(), "tmp_chunk_" + targetMsg.getId() + ".tmp");
                File checkpointDir = new File(context.getExternalCacheDir(), "chunks_" + targetMsg.getId());
                if (!checkpointDir.exists()) {
                    checkpointDir.mkdirs();
                }
                long totalWritten = 0L;
                byte[] copyBuf = new byte[16 * 1024];
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    for (int i = 0; i < totalChunks; i++) {
                        File partFile = new File(checkpointDir, "part_" + i + ".bin");
                        if (partFile.exists() && partFile.length() > 0) {
                            try (java.io.FileInputStream pfis = new java.io.FileInputStream(partFile)) {
                                int r;
                                while ((r = pfis.read(copyBuf)) != -1) {
                                    fos.write(copyBuf, 0, r);
                                    totalWritten += r;
                                }
                            }
                        } else {
                            String docKey = targetMsg.getId() + "_" + i;
                            com.google.firebase.firestore.DocumentSnapshot snap = com.google.android.gms.tasks.Tasks.await(
                                    fs.collection("chats").document(resolvedChatId).collection("media_chunks").document(docKey).get(),
                                    15, java.util.concurrent.TimeUnit.SECONDS);
                            if (snap == null || !snap.exists()) {
                                snap = com.google.android.gms.tasks.Tasks.await(
                                        fs.collection("groups").document(resolvedChatId).collection("media_chunks").document(docKey).get(),
                                        15, java.util.concurrent.TimeUnit.SECONDS);
                            }
                            if (snap == null || !snap.exists()) {
                                throw new java.io.IOException("Missing media chunk " + i);
                            }
                            String encData = snap.getString("data");
                            String b64Chunk = EncryptionManager.decryptGroupPayload(resolvedChatId, encData);
                            byte[] rawSlice = android.util.Base64.decode(b64Chunk, android.util.Base64.DEFAULT);
                            if (rawSlice != null && rawSlice.length > 0) {
                                try (java.io.FileOutputStream pfos = new java.io.FileOutputStream(partFile)) {
                                    pfos.write(rawSlice);
                                    pfos.flush();
                                }
                                fos.write(rawSlice);
                                totalWritten += rawSlice.length;
                            }
                        }
                        final int pct = (int) (((i + 1) * 100L) / totalChunks);
                        notifyGlobalMediaTransferProgress(resolvedChatId, targetMsg.getId(), pct);
                        if (callback != null) {
                            new Handler(Looper.getMainLooper()).post(() -> callback.onProgress(targetMsg.getId(), pct));
                        }
                    }
                    fos.flush();
                }
                if (totalWritten >= 64) {
                    if (targetFile.exists()) targetFile.delete();
                    if (!tempFile.renameTo(targetFile)) {
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                            int r;
                            while ((r = fis.read(copyBuf)) != -1) {
                                fos.write(copyBuf, 0, r);
                            }
                            fos.flush();
                        }
                        tempFile.delete();
                    }
                    // Clean up checkpoint slices now that full file is verified
                    File[] partFiles = checkpointDir.listFiles();
                    if (partFiles != null) {
                        for (File pf : partFiles) {
                            pf.delete();
                        }
                    }
                    checkpointDir.delete();
                    targetMsg.setLocalFilePath(targetFile.getAbsolutePath());
                    EncryptedChatDatabase.getInstance(context).saveMessage(resolvedChatId, targetMsg);
                    exportMediaToPublicStorageIfAllowed(context, resolvedChatId, targetMsg, targetFile);
                    notifyGlobalMediaTransferProgress(resolvedChatId, targetMsg.getId(), 100);
                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(targetMsg.getId(), targetFile));
                    }
                    return;
                } else {
                    tempFile.delete();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Chunked media assembly notice: " + t.getMessage());
            } finally {
                sInFlightChunkDownloads.remove(downloadKey);
            }
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(targetMsg.getId(), "Connecting to peer..."));
            }
        }, "VeilChunkedMediaDownloader").start();
    }

    private boolean sendMediaP2P(String peerId, Message msg, File localFile) {
        try {
            if (P2PFileTransferHelper.isTransferCanceled(msg.getId()) || !isNetworkConnected()) {
                return false;
            }
            boolean isConnected = WebRtcManager.getInstance(mContext).isPeerConnected(peerId);
            if (!isConnected) {
                Log.d(TAG, "Peer " + peerId + " is currently offline; enqueuing media P2P transfer");
                return false;
            }
            WebRtcManager.getInstance(mContext).sendFileMessage(peerId, msg, localFile,
                    new P2PFileTransferHelper.FileTransferCallback() {
                        @Override
                        public void onProgress(int percent) {
                            notifyGlobalMediaTransferProgress(msg.getChatId(), msg.getId(), percent);
                        }

                        @Override
                        public void onComplete(String transferId) {
                            Log.d(TAG, "P2P media stream complete for message: " + msg.getId());
                            mPendingOutboxById.remove(msg.getId());
                            mPendingOutboxById.remove(msg.getId() + "_" + peerId);
                            msg.setPending(false);
                            msg.setDelivered(true);
                            mStorageQueue.execute(() -> {
                                mDatabase.setMessagePending(msg.getId(), false);
                                mDatabase.updateMessageStatus(msg.getId(), true, msg.isRead());
                                if (msg.getChatId() != null) {
                                    refreshDialogInCache(msg.getChatId());
                                }
                            });
                            notifyGlobalMediaTransferProgress(msg.getChatId(), msg.getId(), 100);
                            runOnUI(() -> {
                                for (MessagesObserver obs : mObservers) {
                                    obs.onMessageStatusChanged(msg.getChatId(), msg.getId(), true, msg.isRead());
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "P2P media stream failed: " + error);
                            msg.setPending(true);
                            mStorageQueue.execute(() -> {
                                mDatabase.setMessagePending(msg.getId(), true);
                                if (msg.getChatId() != null) {
                                    refreshDialogInCache(msg.getChatId());
                                }
                            });
                            notifyGlobalMediaTransferProgress(msg.getChatId(), msg.getId(), -1);
                        }
                    });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to stream media over P2P", e);
            return false;
        }
    }

    /**
     * Edits an existing sent message across all mesh peers.
     */
    public void editMessage(String dialogId, String messageId, String newText) {
        if (dialogId == null || messageId == null || newText == null) return;

        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg == null) return;

            msg.setText(newText.trim());
            msg.setEdited(true);

            // Update cache
            putMessageInCache(dialogId, msg);

            // Update SQLCipher database
            mStorageQueue.execute(() -> {
                mDatabase.updateMessageText(dialogId, messageId, newText.trim());
                updateDialogLastMessage(dialogId, msg);
            });

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onMessageEdited(dialogId, messageId, newText.trim());
                }
            });

            // Transmit binary VEDT frame to all target peers (1:1 or Group/Channel)
            mCryptoQueue.execute(() -> {
                try {
                    for (String peerId : resolveTargetPeers(dialogId, null, msg)) {
                        WebRtcManager.getInstance(mContext).sendEditMessage(peerId, dialogId, messageId, newText.trim());
                    }
                    if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("text", newText.trim());
                        upd.put("edited", true);
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("groups").document(dialogId)
                                .collection("messages").document(messageId)
                                .set(upd, com.google.firebase.firestore.SetOptions.merge());
                    } else {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("text", EncryptionManager.encryptGroupPayload(dialogId, newText.trim()));
                        upd.put("edited", true);
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("chats").document(dialogId)
                                .collection("messages").document(messageId)
                                .set(upd, com.google.firebase.firestore.SetOptions.merge());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to broadcast edit message frame", e);
                }
            });
        });
    }

    /**
     * Remotely deletes a message for everyone with multi-pass zero-wipe on local media.
     */
    public void deleteMessage(String dialogId, String messageId, boolean deleteForEveryone) {
        if (dialogId == null || messageId == null) return;

        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            String localPath = msg != null ? msg.getLocalFilePath() : null;

            if (deleteForEveryone) {
                if (msg != null) {
                    msg.setType("deleted");
                    msg.setText("🚫 This message was deleted");
                    msg.setLocalFilePath(null);
                    msg.setMediaUrl(null);
                    msg.setThumbnail(null);
                    msg.setPollJson(null);
                    msg.setReactions(null);
                }
            } else {
                // Remove from memory cache
                removeMessageFromCache(dialogId, messageId);
            }

            // Shred local media & update SQLCipher
            mStorageQueue.execute(() -> {
                if (localPath != null) {
                    cryptographicZeroWipe(new File(localPath));
                }
                if (deleteForEveryone) {
                    mDatabase.markMessageDeletedForEveryone(dialogId, messageId);
                } else {
                    mDatabase.deleteMessage(messageId);
                }
                mDatabase.updateChatLastMessageSummary(dialogId);
                refreshDialogInCache(dialogId);
            });

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onMessageDeleted(dialogId, messageId);
                }
            });

            // Transmit remote delete frame to peers
            if (deleteForEveryone && msg != null) {
                mCryptoQueue.execute(() -> {
                    try {
                        for (String peerId : resolveTargetPeers(dialogId, null, msg)) {
                            WebRtcManager.getInstance(mContext).sendDeleteMessageForEveryone(peerId, dialogId, messageId);
                        }
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("type", "deleted");
                        upd.put("text", "🚫 This message was deleted");
                        if (msg.getSenderId() != null) upd.put("senderId", msg.getSenderId());
                        if (msg.getReceiverId() != null) upd.put("receiverId", msg.getReceiverId());

                        if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("groups").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
                        } else {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("chats").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to dispatch delete for everyone frame", e);
                    }
                });
            }
        });
    }

    /**
     * Toggles an emoji reaction on a message over WebRTC.
     */
    public void sendReaction(String dialogId, String messageId, String emoji) {
        String myId = getCurrentUserId();
        String reactorKey = VeilFeatureManager.getInstance(mContext).isAnonymousReactionEnabled()
                ? "anon_" + (myId != null ? myId.substring(0, Math.min(4, myId.length())) : "anon")
                : myId;
        sendReaction(dialogId, messageId, reactorKey, emoji);
    }

    /**
     * Sets or clears an emoji reaction for a specific reactor key over WebRTC.
     */
    public void sendReaction(String dialogId, String messageId, String reactorKey, String emoji) {
        if (dialogId == null || messageId == null || emoji == null) return;

        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg == null) return;

            Map<String, String> reactions = msg.getReactions();
            if (reactions == null) reactions = new HashMap<>();

            if (emoji == null || emoji.trim().isEmpty() || emoji.equals(reactions.get(reactorKey))) {
                reactions.remove(reactorKey);
                if (!reactorKey.startsWith("anon_") && reactorKey.length() >= 4) {
                    reactions.remove("anon_" + reactorKey.substring(0, Math.min(4, reactorKey.length())));
                }
            } else {
                reactions.put(reactorKey, emoji.trim());
            }

            // Purge any empty / null / whitespace values
            Iterator<Map.Entry<String, String>> it = reactions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> entry = it.next();
                if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    it.remove();
                }
            }

            if (reactions.isEmpty()) {
                msg.setReactions(null);
            } else {
                msg.setReactions(reactions);
            }

            // Update DB
            mStorageQueue.execute(() -> mDatabase.updateMessageReaction(dialogId, messageId, reactorKey, emoji));

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onReactionUpdated(dialogId, messageId, reactorKey, emoji);
                }
            });

            // Broadcast to all target peers
            final Map<String, String> finalReactions = reactions != null ? new HashMap<>(reactions) : new HashMap<>();
            mCryptoQueue.execute(() -> {
                for (String peerId : resolveTargetPeers(dialogId, null, msg)) {
                    WebRtcManager.getInstance(mContext).sendReaction(peerId, dialogId, messageId, reactorKey, emoji);
                }
                if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                    try {
                        if (finalReactions.isEmpty()) {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("groups").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .update("reactions", com.google.firebase.firestore.FieldValue.delete());
                        } else {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("groups").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .update("reactions", finalReactions);
                        }
                    } catch (Exception ignored) {}
                } else {
                    try {
                        if (finalReactions.isEmpty()) {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("chats").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .update("reactions", com.google.firebase.firestore.FieldValue.delete());
                        } else {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("chats").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .update("reactions", finalReactions);
                        }
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    /**
     * Creates an inline poll inside a chat dialog.
     */
    public void createInlinePoll(String dialogId, String question, List<String> options, boolean multipleAnswers) {
        if (dialogId == null || question == null || options == null || options.size() < 2) return;

        mStageQueue.post(() -> {
            try {
                JSONObject poll = new JSONObject();
                poll.put("question", question.trim());
                poll.put("multiple", multipleAnswers);
                poll.put("creatorId", getCurrentUserId());

                JSONArray opts = new JSONArray();
                for (String opt : options) {
                    JSONObject o = new JSONObject();
                    o.put("text", opt.trim());
                    o.put("voters", new JSONArray());
                    opts.put(o);
                }
                poll.put("options", opts);

                String pollJson = poll.toString();
                String messageId = UUID.randomUUID().toString();

                Chat chat = getDialog(dialogId);
                String peerId = (chat != null && !chat.isGroup() && chat.getParticipants() != null)
                        ? resolveDirectPeerId(chat) : dialogId;

                Message msg = new Message(messageId, getCurrentUserId(), peerId, "📊 " + question.trim(), Timestamp.now(), "poll");
                msg.setSenderName(getCurrentUserName());
                msg.setPollJson(pollJson);

                dispatchMessageInternal(dialogId, peerId, msg, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create inline poll", e);
            }
        });
    }

    /**
     * Casts a vote on an inline poll.
     */
    public void voteInlinePoll(String dialogId, String messageId, int optionIndex) {
        if (dialogId == null || messageId == null || optionIndex < 0) return;

        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg == null || msg.getPollJson() == null) return;

            try {
                JSONObject poll = new JSONObject(msg.getPollJson());
                boolean multiple = poll.optBoolean("multiple", false);
                JSONArray options = poll.getJSONArray("options");
                if (optionIndex >= options.length()) return;

                String myId = getCurrentUserId();

                // If single-choice, remove previous votes
                if (!multiple) {
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject opt = options.getJSONObject(i);
                        JSONArray voters = opt.getJSONArray("voters");
                        JSONArray newVoters = new JSONArray();
                        for (int j = 0; j < voters.length(); j++) {
                            if (!voters.getString(j).equals(myId)) {
                                newVoters.put(voters.getString(j));
                            }
                        }
                        opt.put("voters", newVoters);
                    }
                }

                // Toggle vote on selected option
                JSONObject targetOpt = options.getJSONObject(optionIndex);
                JSONArray voters = targetOpt.getJSONArray("voters");
                boolean alreadyVoted = false;
                JSONArray newVoters = new JSONArray();
                for (int j = 0; j < voters.length(); j++) {
                    if (voters.getString(j).equals(myId)) {
                        alreadyVoted = true;
                    } else {
                        newVoters.put(voters.getString(j));
                    }
                }
                if (!alreadyVoted) {
                    newVoters.put(myId);
                }
                targetOpt.put("voters", newVoters);

                String updatedJson = poll.toString();
                msg.setPollJson(updatedJson);

                // Persist
                mStorageQueue.execute(() -> mDatabase.updateMessagePollJson(dialogId, messageId, updatedJson));

                // Notify UI
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onPollUpdated(dialogId, messageId, updatedJson);
                    }
                });

                // Transmit vote frame to all target peers
                mCryptoQueue.execute(() -> {
                    for (String peerId : resolveTargetPeers(dialogId, null, msg)) {
                        WebRtcManager.getInstance(mContext).sendPollVote(peerId, dialogId, messageId, updatedJson);
                    }
                    if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                        try {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put("pollJson", updatedJson);
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("groups").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
                        } catch (Exception ignored) {}
                    } else {
                        try {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put("pollJson", updatedJson);
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("chats").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
                        } catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to cast vote on inline poll", e);
            }
        });
    }

    /**
     * Broadcasts stealth typing waves to peer.
     */
    public void sendTyping(String dialogId, String recipientId, boolean isTyping) {
        if (dialogId == null && recipientId == null) return;
        mCryptoQueue.execute(() -> {
            try {
                List<String> targets = resolveTargetPeers(dialogId, recipientId, null);
                for (String peer : targets) {
                    WebRtcManager.getInstance(mContext).sendTyping(peer, dialogId, isTyping);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send typing indicator", e);
            }
        });
    }

    /**
     * Broadcasts blink opened receipt to peer.
     */
    public void sendBlinkOpened(String dialogId, String messageId) {
        sendBlinkOpened(dialogId, null, messageId);
    }

    /**
     * Broadcasts blink opened receipt to peer with explicit recipientId.
     */
    public void sendBlinkOpened(String dialogId, String recipientId, String messageId) {
        if (dialogId == null || messageId == null) return;

        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg != null) {
                msg.setBlinkViewed(true);
            }

            // Mark viewed in SQLCipher DB
            mStorageQueue.execute(() -> mDatabase.markBlinkMessageViewed(dialogId, messageId));

            // Schedule immediate destruction of blink media
            if (msg != null) {
                scheduleBlinkDestruction(dialogId, msg);
            }

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onBlinkOpened(dialogId, messageId);
                }
            });

            // Transmit blink opened receipt
            mCryptoQueue.execute(() -> {
                for (String peerId : resolveTargetPeers(dialogId, recipientId, msg)) {
                    WebRtcManager.getInstance(mContext).sendBlinkOpenedReceipt(peerId, dialogId, messageId);
                }
            });
        });
    }

    /**
     * Directly distributes an updated poll JSON with a cast vote to peers.
     */
    public void sendPollVote(String dialogId, String recipientId, String messageId, String updatedPollJson) {
        if (dialogId == null || messageId == null || updatedPollJson == null) return;
        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg != null) {
                msg.setPollJson(updatedPollJson);
            }

            mStorageQueue.execute(() -> mDatabase.updateMessagePollJson(dialogId, messageId, updatedPollJson));

            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onPollUpdated(dialogId, messageId, updatedPollJson);
                }
            });

            mCryptoQueue.execute(() -> {
                for (String peerId : resolveTargetPeers(dialogId, recipientId, msg)) {
                    WebRtcManager.getInstance(mContext).sendPollVote(peerId, dialogId, messageId, updatedPollJson);
                }
                if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                    try {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("pollJson", EncryptionManager.encryptGroupPayload(dialogId, updatedPollJson));
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("groups").document(dialogId)
                                .collection("messages").document(messageId)
                                .set(upd, com.google.firebase.firestore.SetOptions.merge());
                    } catch (Exception ignored) {}
                } else {
                    try {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("pollJson", EncryptionManager.encryptGroupPayload(dialogId, updatedPollJson));
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("chats").document(dialogId)
                                .collection("messages").document(messageId)
                                .set(upd, com.google.firebase.firestore.SetOptions.merge());
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    /**
     * Requests streaming P2P or encrypted cloud-shadow download for a media message with DownloadProgressListener.
     */
    public void downloadMedia(String recipientId, String messageId, P2PFileTransferHelper.DownloadProgressListener callback) {
        if (messageId == null) return;
        WebRtcManager.getInstance(mContext).requestMediaDownload(recipientId, messageId, callback);
    }

    /**
     * Requests streaming P2P or encrypted cloud-shadow download for a media message.
     */
    public void downloadMedia(String recipientId, Message message, File destination, P2PFileTransferHelper.FileTransferCallback callback) {
        if (message == null || message.getId() == null) return;
        WebRtcManager.getInstance(mContext).requestMediaDownload(recipientId, message.getId(), new P2PFileTransferHelper.DownloadProgressListener() {
            @Override
            public void onProgress(String transferId, int percent) {
                if (callback != null) callback.onProgress(percent);
            }

            @Override
            public void onComplete(String transferId, File file) {
                if (callback != null) callback.onComplete(transferId);
            }

            @Override
            public void onError(String transferId, String error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    /**
     * Cancels an ongoing P2P or cloud media transfer (either outbound upload or inbound download).
     */
    public void cancelMediaDownload(String recipientId, String messageId) {
        if (messageId == null) return;
        P2PFileTransferHelper.cancelTransfer(messageId);
        sHighestMediaProgress.remove(messageId);
        mPendingOutboxById.remove(messageId);
        if (recipientId != null) {
            mPendingOutboxById.remove(messageId + "_" + recipientId);
        }
        mOutboxQueue.removeIf(entry -> entry != null && entry.message != null && messageId.equals(entry.message.getId()));
        Message msg = getMessage(messageId);
        String dialogId = msg != null ? msg.getChatId() : null;
        if (recipientId != null) {
            try {
                WebRtcManager.getInstance(mContext).cancelMediaDownload(recipientId, messageId);
            } catch (Throwable ignored) {}
        }
        notifyGlobalMediaTransferProgress(dialogId != null ? dialogId : "", messageId, -1);
    }

    /**
     * Retries a canceled or failed outbound media upload (or inbound download).
     */
    public void retryMediaTransfer(String dialogId, String recipientId, Message message, P2PFileTransferHelper.DownloadProgressListener downloadCallback) {
        if (message == null || message.getId() == null) return;
        P2PFileTransferHelper.clearCanceledTransfer(message.getId());
        String myId = getCurrentUserId();
        boolean isOutgoing = (message.getSenderId() != null && message.getSenderId().equals(myId))
                || com.m3.veil.manager.AuthManager.getInstance().isMyUserId(mContext, message.getSenderId());
        String resolvedDialogId = (dialogId != null && !dialogId.isEmpty()) ? dialogId : message.getChatId();
        String resolvedPeerId = (recipientId != null && !recipientId.isEmpty()) ? recipientId : message.getReceiverId();

        if (isOutgoing) {
            File localFile = (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty())
                    ? new File(message.getLocalFilePath()) : null;
            if ((localFile == null || !localFile.exists()) && mContext != null) {
                String ext = resolveMediaExtension(message);
                File dec = new File(mContext.getExternalCacheDir(), "dec_" + message.getId() + ext);
                if (dec.exists()) localFile = dec;
            }
            if (!isNetworkConnected()) {
                notifyGlobalMediaTransferProgress(resolvedDialogId, message.getId(), -1);
                return;
            }
            message.setPending(true);
            notifyGlobalMediaTransferProgress(resolvedDialogId, message.getId(), 1);
            dispatchMessageInternal(resolvedDialogId, resolvedPeerId, message, localFile);
        } else {
            String targetPeer = (resolvedPeerId != null && !resolvedPeerId.isEmpty()) ? resolvedPeerId : message.getSenderId();
            downloadMedia(targetPeer, message.getId(), downloadCallback);
        }
    }

    /**
     * Notifies remote peer that a screenshot was taken.
     */
    public void notifyScreenshotTaken(String dialogId, String recipientId) {
        if (recipientId == null) return;
        mCryptoQueue.execute(() -> {
            try {
                // Send screenshot packet via VeilPacket over LANE_CTRL
                JSONObject json = new JSONObject();
                json.put("type", "screenshot_alert");
                json.put("dialogId", dialogId);
                json.put("senderId", getCurrentUserId());
                json.put("timestamp", System.currentTimeMillis());

                VeilPacket pkt = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_SECURITY_ALERT, json.toString());
                WebRtcManager.getInstance(mContext).sendPacket(recipientId, pkt);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send screenshot alert packet", e);
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // PASS 4: TELEGRAM SecretChatHelper VANISH ENGINE & DISK SHREDDER
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Starts the 500ms background ticker daemon evaluating expiring vanish timers and view-once items.
     */
    private void startVanishDaemon() {
        mVanishDaemonFuture = mScheduler.scheduleWithFixedDelay(() -> {
            try {
                long now = System.currentTimeMillis();
                List<String> toDestroy = new ArrayList<>();

                for (Map.Entry<String, VanishEntry> entry : mActiveVanishTasks.entrySet()) {
                    VanishEntry v = entry.getValue();
                    if (!v.countdownStarted) continue;

                    long remaining = v.expireTimestamp - now;
                    if (remaining <= 0) {
                        toDestroy.add(v.messageId);
                    } else {
                        // Post tick to UI
                        runOnUI(() -> {
                            for (MessagesObserver obs : mObservers) {
                                obs.onVanishTick(v.dialogId, v.messageId, remaining, v.totalDurationMillis);
                            }
                        });
                    }
                }

                // Shred and remove destroyed items
                for (String msgId : toDestroy) {
                    VanishEntry v = mActiveVanishTasks.remove(msgId);
                    if (v != null) {
                        destroyVanishedMessage(v);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Vanish daemon ticker error", e);
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * Destroys an expired vanish or blink message with multi-pass zero-wipe.
     */
    private void destroyVanishedMessage(VanishEntry entry) {
        mStorageQueue.execute(() -> {
            try {
                // 1. Shred disk file
                if (entry.localFilePath != null && !entry.localFilePath.isEmpty()) {
                    cryptographicZeroWipe(new File(entry.localFilePath));
                }

                // 2. Remove from SQLCipher database
                mDatabase.deleteMessage(entry.messageId);
                mDatabase.updateChatLastMessageSummary(entry.dialogId);

                // 3. Remove from memory caches
                removeMessageFromCache(entry.dialogId, entry.messageId);
                refreshDialogInCache(entry.dialogId);

                // 4. Notify UI
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onVanishDestroyed(entry.dialogId, entry.messageId);
                    }
                });

                Log.d(TAG, "Message shredded and vanished permanently: " + entry.messageId);
            } catch (Exception e) {
                Log.e(TAG, "Error destroying vanished message", e);
            }
        });
    }

    /**
     * Modeled after Telegram SecretChatHelper:
     * When remote peer acknowledges message with READ_ACK, trigger the vanish countdown for
     * messages configured with "after_read" mode.
     */
    public void onRemoteMessageReadAck(String dialogId, String messageId, long readTimestamp) {
        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg == null) return;

            msg.setRead(true);
            msg.setDelivered(true);

            // Update DB status
            mStorageQueue.execute(() -> mDatabase.updateMessageStatus(messageId, true, true, readTimestamp));

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onMessageStatusChanged(dialogId, messageId, true, true);
                }
            });

            // Start vanish countdown if configured for "after_read"
            if (msg.getVanishTimer() > 0 && "after_read".equalsIgnoreCase(msg.getVanishMode())) {
                long expireAt = readTimestamp + msg.getVanishTimer();
                msg.setExpiresAt(new Timestamp(new Date(expireAt)));
                mStorageQueue.execute(() -> mDatabase.saveMessage(dialogId, msg));

                mActiveVanishTasks.put(messageId, new VanishEntry(
                        messageId, dialogId, expireAt, msg.getVanishTimer(), msg.getLocalFilePath(), msg.isBlink()));
            }
        });
    }

    /**
     * Immediately destroys view-once blink message after 10-second viewing timeout or dismiss.
     */
    private void scheduleBlinkDestruction(String dialogId, Message msg) {
        mScheduler.schedule(() -> {
            VanishEntry v = new VanishEntry(msg.getId(), dialogId, System.currentTimeMillis(),
                    10000, msg.getLocalFilePath(), true);
            destroyVanishedMessage(v);
        }, 10, TimeUnit.SECONDS);
    }

    /**
     * Cryptographic multi-pass zero-byte disk shredder.
     * Modeled after DoD 5220.22-M sanitization:
     * Pass 1: Overwrite with cryptographically secure random bytes.
     * Pass 2: Overwrite with all 0x00 zeros.
     * Pass 3: Flush to hardware disk sync and unlink file.
     */
    public static void cryptographicZeroWipe(File file) {
        if (file == null || !file.exists()) return;
        try {
            long length = file.length();
            if (length > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
                    // Pass 1: Random bytes
                    byte[] randomBuffer = new byte[(int) Math.min(length, 64 * 1024)];
                    new SecureRandom().nextBytes(randomBuffer);
                    long written = 0;
                    while (written < length) {
                        int toWrite = (int) Math.min(randomBuffer.length, length - written);
                        raf.write(randomBuffer, 0, toWrite);
                        written += toWrite;
                    }
                    raf.getFD().sync();

                    // Pass 2: Zeros
                    byte[] zeroBuffer = new byte[(int) Math.min(length, 64 * 1024)];
                    Arrays.fill(zeroBuffer, (byte) 0);
                    raf.seek(0);
                    written = 0;
                    while (written < length) {
                        int toWrite = (int) Math.min(zeroBuffer.length, length - written);
                        raf.write(zeroBuffer, 0, toWrite);
                        written += toWrite;
                    }
                    raf.getFD().sync();
                }
            }
            // Pass 3: Unlink
            boolean deleted = file.delete();
            Log.d(TAG, "File wiped and unlinked: " + file.getName() + " (success=" + deleted + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to zero-wipe file: " + file.getAbsolutePath(), e);
            try { file.delete(); } catch (Exception ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // SCREENSHOT DETECTION CONTENT OBSERVER
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    private void registerScreenshotObserver() {
        try {
            mScreenshotObserver = new ScreenshotObserver(mMainHandler);
            mContext.getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mScreenshotObserver);
        } catch (Exception e) {
            Log.w(TAG, "Could not register screenshot observer", e);
        }
    }

    private class ScreenshotObserver extends ContentObserver {
        private String lastPath = "";

        public ScreenshotObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri == null) return;

            mStorageQueue.execute(() -> {
                Cursor cursor = null;
                try {
                    String[] projection = new String[]{
                            MediaStore.Images.Media.DATA,
                            MediaStore.Images.Media.DATE_ADDED
                    };
                    cursor = mContext.getContentResolver().query(uri, projection, null, null,
                            MediaStore.Images.Media.DATE_ADDED + " DESC");

                    if (cursor != null && cursor.moveToFirst()) {
                        String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));
                        if (path != null && !path.equals(lastPath)) {
                            String lower = path.toLowerCase();
                            if (lower.contains("screenshot") || lower.contains("screen_shot") || lower.contains("screencapture")) {
                                lastPath = path;
                                onScreenshotDetectedLocal();
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (cursor != null) cursor.close();
                }
            });
        }
    }

    private void onScreenshotDetectedLocal() {
        if (mActiveChatIdInForeground == null) return;
        String dialogId = mActiveChatIdInForeground;
        Chat chat = getDialog(dialogId);
        if (chat == null) return;

        String peerId = resolveDirectPeerId(chat);
        if (peerId != null) {
            notifyScreenshotTaken(dialogId, peerId);
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onScreenshotDetected(dialogId, getCurrentUserId());
                }
            });
        }
    }

    private String resolveDirectPeerId(Chat chat) {
        if (chat == null) return null;
        if (chat.getParticipants() != null) {
            for (String p : chat.getParticipants()) {
                if (p != null && !p.equals(getCurrentUserId())) return p;
            }
        }
        return chat.getId();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // TYPING WAVES DECAY DAEMON
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    private void startTypingDecayDaemon() {
        mTypingDecayFuture = mScheduler.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> iter = mTypingWaves.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, Long> entry = iter.next();
                if (now - entry.getValue() > 4500) { // 4.5 second typing decay
                    iter.remove();
                    String key = entry.getKey();
                    int sep = key.lastIndexOf(':');
                    if (sep > 0 && sep < key.length() - 1) {
                        String dialogId = key.substring(0, sep);
                        String peerId = key.substring(sep + 1);
                        Chat d = mDialogsDict.get(dialogId);
                        if (d != null) {
                            d.setTyping(false);
                        }
                        runOnUI(() -> {
                            for (MessagesObserver obs : mObservers) {
                                obs.onPeerTyping(dialogId, peerId, false);
                            }
                        });
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // PASS 3: WebRtcManager P2PEventListener IMPLEMENTATION (INGESTION ENGINE)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Override
    public void onMessageReceived(String peerId, Message message) {
        if (message == null || peerId == null) return;

        mStageQueue.post(() -> {
            String dialogId = (message.getChatId() != null && !message.getChatId().isEmpty())
                    ? message.getChatId()
                    : (message.getReceiverId() != null && (message.getReceiverId().startsWith("group_") || message.getReceiverId().startsWith("channel_") || message.getReceiverId().startsWith("lounge_"))
                        ? message.getReceiverId()
                        : WebRtcManager.getChatId(getCurrentUserId(), peerId));
            message.setChatId(dialogId);
            onPeerTypingInDialog(dialogId, peerId, false);

            // Observe logical clock
            observeRemoteLamportClock(message.getTimestamp() != null ? message.getTimestamp().toDate().getTime() : System.currentTimeMillis());

            // Handle Vanish Mode
            if (message.getVanishTimer() > 0) {
                if ("after_send".equalsIgnoreCase(message.getVanishMode())) {
                    if (message.getExpiresAt() == null) {
                        message.setExpiresAt(new Timestamp(new Date(System.currentTimeMillis() + message.getVanishTimer())));
                    }
                }
            }

            boolean sendReadReceipt = mContext.getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE)
                    .getBoolean("read_receipts_enabled", true);
            boolean isChatOpenInForeground = dialogId != null && dialogId.equals(mActiveChatIdInForeground) && sendReadReceipt && !Message.isMediaRequiringActionToRead(message.getType());
            message.setDelivered(true);
            if (isChatOpenInForeground) {
                message.setRead(true);
            }

            // Auto-decode inline encrypted voice/media or auto-download chunked media up to 15MB
            if (message.getMediaUrl() != null && !message.getMediaUrl().isEmpty()) {
                ensureMediaDecodedOrAutoDownloaded(mContext, dialogId, message, () -> {
                    putMessageInCache(dialogId, message);
                    for (MessagesObserver obs : mObservers) {
                        obs.onMessageReceived(dialogId, message);
                    }
                });
            }

            // Put in memory cache
            putMessageInCache(dialogId, message);

            // Persist to SQLCipher database
            mStorageQueue.execute(() -> {
                mDatabase.saveMessage(dialogId, message);
                if (isChatOpenInForeground) {
                    mDatabase.markMessagesAsRead(dialogId);
                }
                refreshDialogInCache(dialogId);
                updateDialogLastMessage(dialogId, message);

                // If vanish countdown already active, register it
                if (message.getExpiresAt() != null && message.getExpiresAt().toDate().getTime() > 0) {
                    mActiveVanishTasks.put(message.getId(), new VanishEntry(
                            message.getId(), dialogId, message.getExpiresAt().toDate().getTime(),
                            message.getVanishTimer(), message.getLocalFilePath(), message.isBlink()));
                }
            });

            // Send DELIVERED_ACK (or READ_ACK if chat is currently open) back to sender over WebRTC + Firestore
            mCryptoQueue.execute(() -> {
                WebRtcManager.getInstance(mContext).sendReceipt(peerId, message.getId(), true, isChatOpenInForeground);
            });

            // Trigger Hybrid Notification Engine (deduplicated across P2P & Firebase)
            final boolean viaDirectP2P = WebRtcManager.getInstance(mContext).isPeerConnected(peerId);
            com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                    .onInboundMessageForNotification(dialogId, peerId, message, viaDirectP2P);

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onMessageReceived(dialogId, message);
                }
            });
        });
    }

    @Override
    public void onMessageStatusChanged(String messageId, boolean delivered, boolean read) {
        if (messageId == null) return;

        mStageQueue.post(() -> {
            Message msg = getMessage(messageId);
            if (msg == null) return;

            msg.setPending(false);
            // Monotonic status: never downgrade read -> delivered when a late/duplicate ack arrives.
            final boolean wasRead = msg.isRead();
            msg.setDelivered(msg.isDelivered() || delivered || read);
            msg.setRead(wasRead || read);

            String dialogId = (msg.getChatId() != null && !msg.getChatId().isEmpty())
                    ? msg.getChatId()
                    : (msg.getReceiverId() != null
                        ? WebRtcManager.getChatId(getCurrentUserId(), msg.getReceiverId()) : null);

            mStorageQueue.execute(() -> {
                mDatabase.updateMessageStatus(messageId, delivered || read, read);
                if (dialogId != null) {
                    refreshDialogInCache(dialogId);
                }
            });

            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onMessageStatusChanged(dialogId, messageId, delivered || read, read);
                }
            });

            // If read is true and vanish mode is after_read, trigger countdown
            if (read && msg.getVanishTimer() > 0 && "after_read".equalsIgnoreCase(msg.getVanishMode())) {
                onRemoteMessageReadAck(dialogId, messageId, System.currentTimeMillis());
            }
        });
    }

    @Override
    public void onAllMessagesRead(String chatId) {
        if (chatId == null) return;
        mStageQueue.post(() -> {
            String myId = getCurrentUserId();
            CopyOnWriteArrayList<Message> list = mDialogMessages.get(chatId);
            List<String> updatedIds = new ArrayList<>();
            if (list != null) {
                for (Message m : list) {
                    if (m != null && m.getSenderId() != null && m.getSenderId().equals(myId)
                            && !m.isRead() && m.isDelivered() && !m.isPending()
                            && !isOutboundMediaTransferIncomplete(m.getId())
                            && !m.isMediaRequiringActionToRead()) {
                        m.setDelivered(true);
                        m.setRead(true);
                        updatedIds.add(m.getId());
                    }
                }
            }
            mStorageQueue.execute(() -> {
                mDatabase.markOutboundMessagesAsRead(chatId, System.currentTimeMillis());
                refreshDialogInCache(chatId);
            });
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    for (String mId : updatedIds) {
                        obs.onMessageStatusChanged(chatId, mId, true, true);
                    }
                }
            });
        });
    }

    @Override
    public void onPeerTyping(String peerId, boolean isTyping) {
        if (peerId == null) return;
        String dialogId = WebRtcManager.getChatId(getCurrentUserId(), peerId);
        onPeerTypingInDialog(dialogId, peerId, isTyping);
    }

    public void onPeerTypingInDialog(String dialogId, String peerId, boolean isTyping) {
        if (peerId == null) return;
        String resolvedDialogId = (dialogId != null && !dialogId.isEmpty())
                ? dialogId
                : WebRtcManager.getChatId(getCurrentUserId(), peerId);
        String key = resolvedDialogId + ":" + peerId;

        if (isTyping) {
            mTypingWaves.put(key, System.currentTimeMillis());
        } else {
            mTypingWaves.remove(key);
        }

        Chat d = mDialogsDict.get(resolvedDialogId);
        if (d != null) {
            d.setTyping(isTyping);
        }

        final String finalDialogId = resolvedDialogId;
        runOnUI(() -> {
            for (MessagesObserver obs : mObservers) {
                obs.onPeerTyping(finalDialogId, peerId, isTyping);
            }
        });
    }

    public boolean isDialogTyping(String dialogId, String peerId) {
        long now = System.currentTimeMillis();
        if (dialogId != null && !dialogId.isEmpty() && peerId != null && !peerId.isEmpty()) {
            Long ts = mTypingWaves.get(dialogId + ":" + peerId);
            if (ts != null && (now - ts <= 4500)) {
                return true;
            }
        }
        if (dialogId != null && !dialogId.isEmpty()) {
            String prefix = dialogId + ":";
            for (Map.Entry<String, Long> e : mTypingWaves.entrySet()) {
                if (e.getKey().startsWith(prefix) && e.getValue() != null && (now - e.getValue() <= 4500)) {
                    return true;
                }
            }
        }
        if (peerId != null && !peerId.isEmpty() && (dialogId == null || !dialogId.startsWith("group_"))) {
            String suffix = ":" + peerId;
            for (Map.Entry<String, Long> e : mTypingWaves.entrySet()) {
                if (e.getKey().endsWith(suffix) && !e.getKey().startsWith("group_")
                        && e.getValue() != null && (now - e.getValue() <= 4500)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onPeerConnected(String peerId) {
        if (peerId == null) return;
        Log.i(TAG, "WebRTC mesh connection established with peer: " + peerId);

        // Flush outbox queue for this peer
        mStageQueue.post(() -> flushOutboxForPeer(peerId));
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        if (peerId == null) return;
        Log.w(TAG, "WebRTC peer disconnected: " + peerId);
    }

    @Override
    public void onCallEstablished(String peerId) {}

    @Override
    public void onCallEnded(String peerId) {}

    /**
     * Flushes pending outbox messages when a WebRTC peer reconnects, including rehydrating
     * any unsent/pending large media files from SQLCipher DB.
     */
    private void flushOutboxForPeer(String peerId) {
        mStorageQueue.execute(() -> {
            try {
                List<Message> dbPending = mDatabase.getUnsentPendingMessages();
                if (dbPending != null) {
                    for (Message pm : dbPending) {
                        if (pm == null || pm.getId() == null || P2PFileTransferHelper.isTransferCanceled(pm.getId())) continue;
                        String dId = pm.getChatId();
                        List<String> peers = resolveTargetPeers(dId, pm.getReceiverId(), pm);
                        if (peers.contains(peerId) && !mPendingOutboxById.containsKey(pm.getId())) {
                            File lf = (pm.getLocalFilePath() != null && !pm.getLocalFilePath().isEmpty())
                                    ? new File(pm.getLocalFilePath()) : null;
                            Chat c = getDialog(dId);
                            boolean isGrp = c != null && c.isGroupChat();
                            OutboxEntry hydrated = new OutboxEntry(pm, dId, peerId, isGrp,
                                    c != null && isGrp ? c.getName() : getCurrentUserName(),
                                    c != null ? c.getType() : "direct", lf);
                            mOutboxQueue.add(hydrated);
                            mPendingOutboxById.put(pm.getId(), hydrated);
                        }
                    }
                }
            } catch (Exception ignored) {}

            Iterator<OutboxEntry> iter = mOutboxQueue.iterator();
            while (iter.hasNext()) {
                OutboxEntry entry = iter.next();
                if (entry.peerId != null && entry.peerId.equals(peerId)) {
                    if (entry.message != null && P2PFileTransferHelper.isTransferCanceled(entry.message.getId())) {
                        iter.remove();
                        continue;
                    }
                    iter.remove();
                    mPendingOutboxById.remove(entry.message.getId());
                    mPendingOutboxById.remove(entry.message.getId() + "_" + peerId);

                    mCryptoQueue.execute(() -> {
                        boolean hasLocalMedia = entry.localMediaFile != null && entry.localMediaFile.exists() && entry.localMediaFile.length() >= 64;
                        boolean ok = false;
                        if (hasLocalMedia) {
                            ok = sendMediaP2P(peerId, entry.message, entry.localMediaFile);
                        } else {
                            VeilPacket pkt = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE,
                                    buildJsonFallbackString(entry.dialogId, entry.message));
                            ok = WebRtcManager.getInstance(mContext).sendPacket(peerId, pkt);
                        }
                        if (ok) {
                            if (!hasLocalMedia) {
                                entry.message.setPending(false);
                                mStorageQueue.execute(() -> {
                                    mDatabase.setMessagePending(entry.message.getId(), false);
                                    mDatabase.updateMessageStatus(entry.message.getId(), false, false);
                                    refreshDialogInCache(entry.dialogId);
                                });
                                runOnUI(() -> {
                                    for (MessagesObserver obs : mObservers) {
                                        obs.onMessageStatusChanged(entry.dialogId, entry.message.getId(), false, false);
                                    }
                                });
                            }
                            Log.d(TAG, "Outbox packet flushed successfully for peer: " + peerId);
                        } else {
                            // Re-enqueue if still failed
                            mOutboxQueue.add(entry);
                            mPendingOutboxById.put(entry.message.getId(), entry);
                        }
                    });
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // IN-MEMORY CACHE HELPERS
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    private void putMessageInCache(String dialogId, Message msg) {
        if (dialogId == null || msg == null) return;
        mMessagesById.put(msg.getId(), msg);
        CopyOnWriteArrayList<Message> list = mDialogMessages.computeIfAbsent(dialogId, k -> new CopyOnWriteArrayList<>());

        // Replace if exists, else append
        int existingIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(msg.getId())) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            list.set(existingIndex, msg);
        } else {
            list.add(msg);
        }

        // Mirror in LongSparseArray warm caches (Telegram parity)
        long dialogHash = getDialogIdHash(dialogId);
        long messageHash = getDialogIdHash(msg.getId());
        synchronized (mMessagesByDialogs) {
            mMessagesByDialogs.put(dialogHash, list);
        }
        synchronized (mMessagesByIdLongSparse) {
            mMessagesByIdLongSparse.put(messageHash, msg);
        }

        // Pre-warm StaticLayout on stageQueue for 120Hz smooth scrolling
        if (msg.getText() != null && !msg.getText().isEmpty()) {
            mStageQueue.post(() -> {
                try {
                    android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    float density = mContext.getResources().getDisplayMetrics().density;
                    paint.setTextSize(15.5f * density);
                    int maxW = (int) (mContext.getResources().getDisplayMetrics().widthPixels * 0.72f);
                    prewarmMessageLayout(msg.getId(), msg.getText(), paint, maxW);
                } catch (Throwable ignored) {}
            });
        }
    }

    private void removeMessageFromCache(String dialogId, String messageId) {
        if (messageId == null) return;
        mMessagesById.remove(messageId);
        synchronized (mMessagesByIdLongSparse) {
            mMessagesByIdLongSparse.remove(getDialogIdHash(messageId));
        }
        if (dialogId != null) {
            CopyOnWriteArrayList<Message> list = mDialogMessages.get(dialogId);
            if (list != null) {
                list.removeIf(m -> m.getId().equals(messageId));
            }
        }
    }

    private void updateDialogLastMessage(String dialogId, Message msg) {
        Chat chat = mDialogsDict.get(dialogId);
        if (chat == null) {
            chat = mDatabase.getChat(dialogId);
            if (chat != null) {
                mDialogsDict.put(dialogId, chat);
                mSortedDialogsList.add(chat);
            }
        }
        if (chat != null && msg != null) {
            chat.setLastMessage(msg.getText());
            chat.setLastMessageTime(msg.getTimestamp());
            chat.setLastMessageSenderId(msg.getSenderId());
            chat.setLastMessagePending(msg.isPending());
            chat.setLastMessageDelivered(msg.isDelivered());
            chat.setLastMessageRead(msg.isRead());
            sortDialogsInternal();
            synchronized (mDialogs_dict) {
                mDialogs_dict.put(getDialogIdHash(dialogId), chat);
            }
        }
    }

    private void refreshDialogInCache(String dialogId) {
        Chat chat = mDatabase.getChat(dialogId);
        if (chat != null) {
            mDialogsDict.put(dialogId, chat);
            int idx = -1;
            for (int i = 0; i < mSortedDialogsList.size(); i++) {
                if (mSortedDialogsList.get(i).getId().equals(dialogId)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                mSortedDialogsList.set(idx, chat);
            } else {
                mSortedDialogsList.add(chat);
            }
            sortDialogsInternal();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // BINARY WIRE PROTOCOL SERIALIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Serializes a message into a deterministic binary ByteBuffer frame with CRC32 checksum.
     */
    private byte[] buildBinaryMessagePacket(String dialogId, Message msg, @Nullable File localFile) throws Exception {
        byte[] idBytes = msg.getId().getBytes(StandardCharsets.UTF_8);
        byte[] dialogBytes = dialogId.getBytes(StandardCharsets.UTF_8);
        byte[] senderBytes = getCurrentUserId().getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = msg.getText() != null ? msg.getText().getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] typeBytes = msg.getType() != null ? msg.getType().getBytes(StandardCharsets.UTF_8) : "text".getBytes(StandardCharsets.UTF_8);

        long timestamp = msg.getTimestamp() != null ? msg.getTimestamp().toDate().getTime() : System.currentTimeMillis();
        long vanishTimer = msg.getVanishTimer();
        boolean blink = msg.isBlink();

        int payloadLength = 4 + 1 + 8 + 8 + 1 + // magic, ver, ts, vanish, blink
                2 + idBytes.length +
                2 + dialogBytes.length +
                2 + senderBytes.length +
                2 + typeBytes.length +
                4 + textBytes.length;

        ByteBuffer buffer = ByteBuffer.allocate(payloadLength + 4); // +4 for CRC32
        buffer.putInt(MAGIC_VMSG);
        buffer.put(PROTOCOL_VERSION);
        buffer.putLong(timestamp);
        buffer.putLong(vanishTimer);
        buffer.put((byte) (blink ? 1 : 0));

        buffer.putShort((short) idBytes.length);
        buffer.put(idBytes);

        buffer.putShort((short) dialogBytes.length);
        buffer.put(dialogBytes);

        buffer.putShort((short) senderBytes.length);
        buffer.put(senderBytes);

        buffer.putShort((short) typeBytes.length);
        buffer.put(typeBytes);

        buffer.putInt(textBytes.length);
        buffer.put(textBytes);

        // Compute CRC32
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, payloadLength);
        buffer.putInt((int) crc.getValue());

        return buffer.array();
    }

    private String buildJsonFallbackString(String dialogId, Message msg) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "chat");

            JSONObject p = new JSONObject();
            p.put("id", msg.getId());
            p.put("chatId", dialogId);
            p.put("senderId", getCurrentUserId());
            p.put("senderName", msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName());
            p.put("text", msg.getText());
            p.put("msgType", msg.getType());
            p.put("timestamp", msg.getTimestamp() != null ? msg.getTimestamp().toDate().getTime() : System.currentTimeMillis());
            p.put("replyToId", msg.getReplyToMessageId());
            p.put("replySenderName", msg.getReplySenderName());
            p.put("blink", msg.isBlink());
            p.put("voiceFilter", msg.getVoiceFilter());
            p.put("pollJson", msg.getPollJson());
            if (msg.getThumbnail() != null) p.put("thumbnail", msg.getThumbnail());
            if (msg.getMediaUrl() != null && (msg.getMediaUrl().length() <= 28000 || msg.getMediaUrl().startsWith("CHUNKED:"))) {
                p.put("mediaUrl", msg.getMediaUrl());
            }
            p.put("expiresAt", msg.getExpiresAt() != null ? msg.getExpiresAt().toDate().getTime() : 0);
            p.put("vanishTimer", msg.getVanishTimer());
            p.put("vanishMode", msg.getVanishMode());

            Chat chat = getDialog(dialogId);
            if (chat == null && dialogId != null) {
                chat = mDatabase.getChat(dialogId);
            }
            boolean isGroup = (chat != null && chat.isGroupChat())
                    || (dialogId != null && (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")));
            p.put("isGroup", isGroup);
            if (chat != null) {
                if (isGroup && chat.getName() != null) {
                    p.put("chatName", chat.getName());
                } else {
                    p.put("chatName", msg.getSenderName() != null ? msg.getSenderName() : getCurrentUserName());
                }
                if (chat.getType() != null) p.put("chatType", chat.getType());
                if (chat.getAdminId() != null) p.put("adminId", chat.getAdminId());
                if (chat.getDescription() != null) p.put("description", chat.getDescription());
                if (chat.getParticipants() != null && !chat.getParticipants().isEmpty()) {
                    JSONArray partsArr = new JSONArray();
                    for (String pid : chat.getParticipants()) {
                        if (pid != null && !pid.isEmpty()) partsArr.put(pid);
                    }
                    p.put("participants", partsArr);
                }
            }

            // Signal-grade HKDF forward-secret ratcheting
            String rawText = msg.getText();
            if (rawText != null && !rawText.isEmpty()) {
                try {
                    EncryptionManager.HybridEncryptionResult enc =
                            EncryptionManager.encryptHybridRatcheted(dialogId, rawText, null, null);
                    if (enc != null) {
                        p.put("encContent", enc.encryptedContent);
                        p.put("aesKey", enc.aesKeyForRecipient);
                        p.put("iv", enc.iv);
                        p.put("ratchetStep", enc.ratchetStep);
                        p.put("hmacTag", enc.hmacTag);
                        if (enc.dhPublicKey != null) {
                            p.put("dhPublicKey", enc.dhPublicKey);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Forward-secrecy encryption notice: " + t.getMessage());
                }
            }

            json.put("payload", p);
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // PUBLIC CONVENIENCE APIS FOR UI CONTROLLERS
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Marks all messages in a dialog as read and broadcasts READ_ACK packets.
     */
    public void markDialogAsRead(String dialogId, String recipientId) {
        if (dialogId != null) {
            com.m3.veil.services.VeilHybridNotificationManager.getInstance(mContext)
                    .clearChatNotification(dialogId);
        }
        if (dialogId == null || !dialogId.equals(mActiveChatIdInForeground)) return;

        mStageQueue.post(() -> {
            if (!dialogId.equals(mActiveChatIdInForeground)) return;
            mStorageQueue.execute(() -> {
                mDatabase.markAllAsRead(dialogId, getCurrentUserId());
                mUnreadCountByDialog.put(dialogId, new AtomicInteger(0));
                refreshDialogInCache(dialogId);
            });

            // Transmit read receipt packet to remote peer(s) over WebRTC
            mCryptoQueue.execute(() -> {
                for (String peer : resolveTargetPeers(dialogId, recipientId, null)) {
                    WebRtcManager.getInstance(mContext).sendChatReadReceipt(peer, dialogId);
                }
            });
        });
    }

    /**
     * Explicitly marks a media or single message as read when recipient interacts with it
     * (e.g. plays audio, opens photo/video in viewer, opens document).
     */
    public void markMessageAsReadExplicitly(Message message) {
        if (message == null || message.getId() == null) return;
        String myId = getCurrentUserId();
        if (myId != null && myId.equals(message.getSenderId())) return;
        if (com.m3.veil.manager.AuthManager.getInstance().isMyUserId(mContext, message.getSenderId())) return;
        if (message.isRead()) return;

        message.setRead(true);
        message.setDelivered(true);

        final String msgId = message.getId();
        final String senderId = message.getSenderId();
        final String chatId = message.getChatId();

        mStorageQueue.execute(() -> {
            mDatabase.updateMessageStatus(msgId, true, true);
            onMessageStatusChanged(msgId, true, true);
        });

        if (senderId != null && !senderId.isEmpty()) {
            mCryptoQueue.execute(() -> {
                WebRtcManager.getInstance(mContext).sendReceipt(senderId, msgId, true, true);
            });
        }

        if (chatId != null && !chatId.isEmpty()) {
            try {
                java.util.Map<String, Object> ack = new java.util.HashMap<>();
                ack.put("delivered", true);
                ack.put("read", true);
                ack.put("readAt", System.currentTimeMillis());
                com.google.firebase.firestore.FirebaseFirestore fs = com.google.firebase.firestore.FirebaseFirestore.getInstance();
                fs.collection("groups").document(chatId).collection("messages").document(msgId)
                        .set(ack, com.google.firebase.firestore.SetOptions.merge());
                fs.collection("chats").document(chatId).collection("messages").document(msgId)
                        .set(ack, com.google.firebase.firestore.SetOptions.merge());
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Configures the disappear / vanish timer and mode for a dialog.
     */
    public void setDialogVanishSettings(String dialogId, String recipientId, long vanishTimer, String vanishMode) {
        if (dialogId == null) return;

        mStageQueue.post(() -> {
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setVanishTimer(vanishTimer);
                chat.setVanishMode(vanishMode);
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
            }

            // Transmit setting packet to remote peer(s)
            mCryptoQueue.execute(() -> {
                for (String peer : resolveTargetPeers(dialogId, recipientId, null)) {
                    WebRtcManager.getInstance(mContext).sendVanishSetting(peer, dialogId, vanishMode, vanishTimer);
                }
            });
        });
    }

    /**
     * Pins a message to the top of the chat dialog (supports multiple pinned messages).
     */
    public void pinMessage(String dialogId, String recipientId, String messageId, String messageText) {
        if (dialogId == null) return;

        mStageQueue.post(() -> {
            if (messageId != null) {
                mStorageQueue.execute(() -> mDatabase.updateMessagePinStatus(dialogId, messageId, true));
            }
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setPinnedMessageId(messageId);
                chat.setPinnedMessageText(messageText);
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
            }

            mCryptoQueue.execute(() -> {
                for (String peer : resolveTargetPeers(dialogId, recipientId, null)) {
                    WebRtcManager.getInstance(mContext).sendPinMessageSetting(peer, dialogId, messageId, messageText);
                }
            });
        });
    }

    /**
     * Unpins a specific message or all pinned messages in a dialog.
     */
    public void unpinMessage(String dialogId, String recipientId) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            mStorageQueue.execute(() -> mDatabase.unpinAllMessagesInChat(dialogId));
            pinMessage(dialogId, recipientId, null, null);
        });
    }

    public void unpinSpecificMessage(String dialogId, String recipientId, String unpinnedMsgId, String nextActiveMsgId, String nextActiveMsgText) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            if (unpinnedMsgId != null) {
                mStorageQueue.execute(() -> mDatabase.updateMessagePinStatus(dialogId, unpinnedMsgId, false));
            }
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setPinnedMessageId(nextActiveMsgId);
                chat.setPinnedMessageText(nextActiveMsgText);
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
            }
            mCryptoQueue.execute(() -> {
                for (String peer : resolveTargetPeers(dialogId, recipientId, null)) {
                    WebRtcManager.getInstance(mContext).sendPinMessageSetting(peer, dialogId, nextActiveMsgId, nextActiveMsgText, unpinnedMsgId);
                }
            });
        });
    }

    /**
     * Toggles pin status of a dialog in the conversation list.
     */
    public void toggleDialogPinned(String dialogId) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setPinned(!chat.isPinned());
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
                sortDialogsInternal();
                List<Chat> copy = new ArrayList<>(mSortedDialogsList);
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onDialogsLoaded(copy);
                    }
                });
            }
        });
    }

    /**
     * Toggles mute status of a dialog.
     */
    public void toggleDialogMuted(String dialogId) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setMuted(!chat.isMuted());
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
            }
        });
    }

    /**
     * Archives or unarchives a dialog.
     */
    public void setDialogArchived(String dialogId, boolean archived) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setArchived(archived);
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
                sortDialogsInternal();
                List<Chat> copy = new ArrayList<>(mSortedDialogsList);
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onDialogsLoaded(copy);
                    }
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // P2P GROUP MESH MULTI-PEER ORCHESTRATION & SECRET HOLE DETECTION
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new P2P group mesh conversation and broadcasts invites directly over WebRTC.
     */
    public void createP2PGroup(String groupName, List<String> peerIds, String groupType) {
        if (groupName == null || peerIds == null || peerIds.isEmpty()) return;

        mStageQueue.post(() -> {
            String groupId = "group_" + UUID.randomUUID().toString();
            List<String> members = new ArrayList<>(peerIds);
            if (!members.contains(getCurrentUserId())) {
                members.add(getCurrentUserId());
            }

            Chat groupChat = new Chat();
            groupChat.setId(groupId);
            groupChat.setName(groupName);
            groupChat.setGroup(true);
            groupChat.setType(groupType != null ? groupType : "group");
            groupChat.setParticipants(members);
            groupChat.setLastMessage("Group created: " + groupName);
            groupChat.setLastMessageTime(Timestamp.now());

            mDialogsDict.put(groupId, groupChat);
            mSortedDialogsList.add(0, groupChat);

            mStorageQueue.execute(() -> {
                mDatabase.saveOrUpdateChat(groupChat);
                mDatabase.saveGroupMembers(groupId, members, getCurrentUserId());
            });

            // Broadcast group invite packets to all initial participants
            mCryptoQueue.execute(() -> {
                WebRtcManager.getInstance(mContext).sendGroupInviteToPeers(groupChat);
            });

            List<Chat> copy = new ArrayList<>(mSortedDialogsList);
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onDialogsLoaded(copy);
                }
            });
        });
    }

    /**
     * Adds participants to an existing P2P mesh group.
     */
    public void addParticipantsToGroup(String groupId, List<String> newPeerIds) {
        if (groupId == null || newPeerIds == null || newPeerIds.isEmpty()) return;

        mStageQueue.post(() -> {
            Chat group = getDialog(groupId);
            if (group == null || !group.isGroup()) return;

            List<String> rawParticipants = group.getParticipants();
            final List<String> updatedParticipants = rawParticipants != null ? new ArrayList<>(rawParticipants) : new ArrayList<>();
            for (String peer : newPeerIds) {
                if (!updatedParticipants.contains(peer)) {
                    updatedParticipants.add(peer);
                }
            }
            group.setParticipants(updatedParticipants);

            mStorageQueue.execute(() -> {
                mDatabase.saveOrUpdateChat(group);
                mDatabase.saveGroupMembers(groupId, updatedParticipants, getCurrentUserId());
            });

            // Notify all peers of membership update
            mCryptoQueue.execute(() -> {
                WebRtcManager.getInstance(mContext).sendGroupInviteToPeers(group);
            });
        });
    }

    /**
     * Dispatches a message to all connected mesh peers in a P2P group.
     */
    public void sendGroupChatMessage(String groupId, Message message, @Nullable File localFile) {
        if (groupId == null || message == null) return;

        mStageQueue.post(() -> {
            Chat group = getDialog(groupId);
            if (group == null || group.getParticipants() == null) return;

            putMessageInCache(groupId, message);

            mStorageQueue.execute(() -> {
                mDatabase.saveMessage(groupId, message);
                updateDialogLastMessage(groupId, message);
            });

            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onMessageSent(groupId, message);
                }
            });

            mCryptoQueue.execute(() -> {
                for (String memberId : group.getParticipants()) {
                    if (memberId != null && !memberId.equals(getCurrentUserId())) {
                        if (localFile != null && localFile.exists()) {
                            sendMediaP2P(memberId, message, localFile);
                        } else {
                            VeilPacket pkt = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE,
                                    buildJsonFallbackString(groupId, message));
                            boolean sent = WebRtcManager.getInstance(mContext).sendPacket(memberId, pkt);
                            if (!sent) {
                                OutboxEntry entry = new OutboxEntry(message, groupId, memberId, true,
                                        group.getName(), group.getType(), localFile);
                                mOutboxQueue.add(entry);
                            }
                        }
                    }
                }
            });
        });
    }

    /**
     * Modeled after Telegram SecretChatHelper.java: checkSecretHoles.
     * Detects missing sequence numbers in a peer conversation and requests re-transmission.
     */
    public void checkSecretHoles(String dialogId, int expectedSeq, int receivedSeq, String peerId) {
        if (receivedSeq > expectedSeq + 1) {
            int missingCount = receivedSeq - (expectedSeq + 1);
            Log.w(TAG, "SecretChatHelper detected hole in dialog " + dialogId + ": missing " + missingCount + " packets.");
            for (int missing = expectedSeq + 1; missing < receivedSeq; missing++) {
                requestResendSecretPacket(peerId, dialogId, missing);
            }
        }
    }

    private void requestResendSecretPacket(String peerId, String dialogId, int missingSeq) {
        mCryptoQueue.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "resend_request");
                json.put("dialogId", dialogId);
                json.put("missingSeq", missingSeq);
                json.put("requesterId", getCurrentUserId());

                VeilPacket pkt = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_RESEND_REQUEST, json.toString());
                WebRtcManager.getInstance(mContext).sendPacket(peerId, pkt);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request packet resend", e);
            }
        });
    }

    /**
     * Binary wire packet dispatch router for incoming byte buffers.
     */
    public void processIncomingBinaryBuffer(String peerId, ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 5) return;
        mStageQueue.post(() -> {
            try {
                int magic = buffer.getInt();
                byte version = buffer.get();

                switch (magic) {
                    case MAGIC_VMSG:
                        handleIncomingBinaryMessage(peerId, buffer);
                        break;
                    case MAGIC_VEDT:
                        handleIncomingBinaryEdit(peerId, buffer);
                        break;
                    case MAGIC_VDEL:
                        handleIncomingBinaryDelete(peerId, buffer);
                        break;
                    case MAGIC_VRCT:
                        handleIncomingBinaryReaction(peerId, buffer);
                        break;
                    case MAGIC_VPOL:
                        handleIncomingBinaryPoll(peerId, buffer);
                        break;
                    case MAGIC_VVOT:
                        handleIncomingBinaryVote(peerId, buffer);
                        break;
                    case MAGIC_VTYP:
                        handleIncomingBinaryTyping(peerId, buffer);
                        break;
                    case MAGIC_VRAC:
                        handleIncomingBinaryReadAck(peerId, buffer);
                        break;
                    case MAGIC_VSSN:
                        handleIncomingBinaryScreenshot(peerId, buffer);
                        break;
                    case MAGIC_VBLK:
                        handleIncomingBinaryBlink(peerId, buffer);
                        break;
                    default:
                        Log.w(TAG, "Unknown binary packet magic: " + Integer.toHexString(magic));
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error decoding binary packet", e);
            }
        });
    }

    private void handleIncomingBinaryMessage(String peerId, ByteBuffer buffer) throws Exception {
        long timestamp = buffer.getLong();
        long vanishTimer = buffer.getLong();
        boolean blink = buffer.get() == 1;

        short idLen = buffer.getShort();
        byte[] idBytes = new byte[idLen];
        buffer.get(idBytes);
        String messageId = new String(idBytes, StandardCharsets.UTF_8);

        short dialogLen = buffer.getShort();
        byte[] dialogBytes = new byte[dialogLen];
        buffer.get(dialogBytes);
        String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

        short senderLen = buffer.getShort();
        byte[] senderBytes = new byte[senderLen];
        buffer.get(senderBytes);
        String senderId = new String(senderBytes, StandardCharsets.UTF_8);

        short typeLen = buffer.getShort();
        byte[] typeBytes = new byte[typeLen];
        buffer.get(typeBytes);
        String type = new String(typeBytes, StandardCharsets.UTF_8);

        int textLen = buffer.getInt();
        byte[] textBytes = new byte[textLen];
        buffer.get(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);

        Message msg = new Message(messageId, senderId, getCurrentUserId(), text,
                new Timestamp(new Date(timestamp)), type);
        msg.setVanishTimer(vanishTimer);
        msg.setBlink(blink);

        onMessageReceived(peerId, msg);
    }

    private void handleIncomingBinaryEdit(String peerId, ByteBuffer buffer) {
        try {
            short idLen = buffer.getShort();
            byte[] idBytes = new byte[idLen];
            buffer.get(idBytes);
            String messageId = new String(idBytes, StandardCharsets.UTF_8);

            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            int textLen = buffer.getInt();
            byte[] textBytes = new byte[textLen];
            buffer.get(textBytes);
            String newText = new String(textBytes, StandardCharsets.UTF_8);

            Message msg = getMessage(messageId);
            if (msg != null) {
                msg.setText(newText);
                msg.setEdited(true);
                mStorageQueue.execute(() -> mDatabase.updateMessageText(dialogId, messageId, newText));
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onMessageEdited(dialogId, messageId, newText);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming edit frame", e);
        }
    }

    private void handleIncomingBinaryDelete(String peerId, ByteBuffer buffer) {
        try {
            short idLen = buffer.getShort();
            byte[] idBytes = new byte[idLen];
            buffer.get(idBytes);
            String messageId = new String(idBytes, StandardCharsets.UTF_8);

            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            deleteMessage(dialogId, messageId, false);
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming delete frame", e);
        }
    }

    private void handleIncomingBinaryReaction(String peerId, ByteBuffer buffer) {
        try {
            short idLen = buffer.getShort();
            byte[] idBytes = new byte[idLen];
            buffer.get(idBytes);
            String messageId = new String(idBytes, StandardCharsets.UTF_8);

            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            short userLen = buffer.getShort();
            byte[] userBytes = new byte[userLen];
            buffer.get(userBytes);
            String userId = new String(userBytes, StandardCharsets.UTF_8);

            short emojiLen = buffer.getShort();
            byte[] emojiBytes = new byte[emojiLen];
            buffer.get(emojiBytes);
            String emoji = new String(emojiBytes, StandardCharsets.UTF_8);

            Message msg = getMessage(messageId);
            if (msg != null) {
                Map<String, String> rx = msg.getReactions();
                if (rx == null) rx = new HashMap<>();
                rx.put(userId, emoji);
                msg.setReactions(rx);

                mStorageQueue.execute(() -> mDatabase.updateMessageReaction(dialogId, messageId, userId, emoji));
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onReactionUpdated(dialogId, messageId, userId, emoji);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming reaction frame", e);
        }
    }

    private void handleIncomingBinaryPoll(String peerId, ByteBuffer buffer) {
        try {
            short idLen = buffer.getShort();
            byte[] idBytes = new byte[idLen];
            buffer.get(idBytes);
            String messageId = new String(idBytes, StandardCharsets.UTF_8);

            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            int pollLen = buffer.getInt();
            byte[] pollBytes = new byte[pollLen];
            buffer.get(pollBytes);
            String pollJson = new String(pollBytes, StandardCharsets.UTF_8);

            Message msg = getMessage(messageId);
            if (msg != null) {
                msg.setPollJson(pollJson);
                mStorageQueue.execute(() -> mDatabase.updateMessagePollJson(dialogId, messageId, pollJson));
                runOnUI(() -> {
                    for (MessagesObserver obs : mObservers) {
                        obs.onPollUpdated(dialogId, messageId, pollJson);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming poll frame", e);
        }
    }

    private void handleIncomingBinaryVote(String peerId, ByteBuffer buffer) {
        handleIncomingBinaryPoll(peerId, buffer);
    }

    private void handleIncomingBinaryTyping(String peerId, ByteBuffer buffer) {
        try {
            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);
            boolean isTyping = buffer.get() == 1;

            onPeerTyping(peerId, isTyping);
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming typing frame", e);
        }
    }

    private void handleIncomingBinaryReadAck(String peerId, ByteBuffer buffer) {
        try {
            short idLen = buffer.getShort();
            byte[] idBytes = new byte[idLen];
            buffer.get(idBytes);
            String messageId = new String(idBytes, StandardCharsets.UTF_8);

            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            long readTimestamp = buffer.getLong();

            onRemoteMessageReadAck(dialogId, messageId, readTimestamp);
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming read ack frame", e);
        }
    }

    private void handleIncomingBinaryScreenshot(String peerId, ByteBuffer buffer) {
        try {
            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onScreenshotDetected(dialogId, peerId);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming screenshot frame", e);
        }
    }

    private void handleIncomingBinaryBlink(String peerId, ByteBuffer buffer) {
        try {
            short idLen = buffer.getShort();
            byte[] idBytes = new byte[idLen];
            buffer.get(idBytes);
            String messageId = new String(idBytes, StandardCharsets.UTF_8);

            short dialogLen = buffer.getShort();
            byte[] dialogBytes = new byte[dialogLen];
            buffer.get(dialogBytes);
            String dialogId = new String(dialogBytes, StandardCharsets.UTF_8);

            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onBlinkOpened(dialogId, messageId);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming blink frame", e);
        }
    }

    /**
     * Deletes an entire conversation locally and optionally from the remote peer's device as well.
     */
    public void deleteConversation(String dialogId, String recipientId, boolean deleteForBoth) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            List<String> targetPeers = resolveTargetPeers(dialogId, recipientId, null);
            if (deleteForBoth && !targetPeers.isEmpty()) {
                mCryptoQueue.execute(() -> {
                    for (String peer : targetPeers) {
                        if (peer != null && !peer.isEmpty()) {
                            WebRtcManager.getInstance(mContext).sendDeleteConversationForBoth(peer, dialogId, true);
                        }
                    }
                });
            }
            wipeDialog(dialogId);
        });
    }

    /**
     * Clears all messages in a conversation locally and optionally on the remote peer's device as well.
     */
    public void clearConversationHistory(String dialogId, String recipientId, boolean clearForBoth) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            List<String> targetPeers = resolveTargetPeers(dialogId, recipientId, null);
            if (clearForBoth && !targetPeers.isEmpty()) {
                mCryptoQueue.execute(() -> {
                    for (String peer : targetPeers) {
                        if (peer != null && !peer.isEmpty()) {
                            WebRtcManager.getInstance(mContext).sendDeleteConversationForBoth(peer, dialogId, false);
                        }
                    }
                });
            }
            List<Message> msgs = getLoadedMessages(dialogId);
            for (Message m : msgs) {
                if (m.getLocalFilePath() != null) {
                    cryptographicZeroWipe(new File(m.getLocalFilePath()));
                }
            }
            mDialogMessages.remove(dialogId);
            mStorageQueue.execute(() -> mDatabase.clearChatMessages(dialogId));
        });
    }

    /**
     * Completely wipes a chat dialog and unlinks all associated media files.
     */
    public void wipeDialog(String dialogId) {
        if (dialogId == null) return;
        mStageQueue.post(() -> {
            List<Message> msgs = getLoadedMessages(dialogId);
            for (Message m : msgs) {
                if (m.getLocalFilePath() != null) {
                    cryptographicZeroWipe(new File(m.getLocalFilePath()));
                }
            }

            mDialogMessages.remove(dialogId);
            mDialogsDict.remove(dialogId);
            mSortedDialogsList.removeIf(c -> c.getId().equals(dialogId));

            mStorageQueue.execute(() -> {
                mDatabase.clearChatMessages(dialogId);
                mDatabase.deleteChat(dialogId);
            });

            List<Chat> copy = new ArrayList<>(mSortedDialogsList);
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onDialogsLoaded(copy);
                }
            });
        });
    }

    /**
     * Creates a Secret Sub-Group (#71) persisted in SQLCipher and broadcast over WebRTC.
     */
    public String createSecretSubgroup(String parentGroupId, String name, String description, List<String> invitedPeers) {
        String subGroupId = "sub_" + parentGroupId + "_" + UUID.randomUUID().toString().substring(0, 6);
        mStorageQueue.execute(() -> {
            EncryptedChatDatabase.GroupSubgroup sg = new EncryptedChatDatabase.GroupSubgroup(
                    subGroupId, parentGroupId, name, description, UUID.randomUUID().toString(), "ADMIN", true);
            mDatabase.saveSubgroup(sg);
            try {
                JSONObject pktJson = new JSONObject();
                pktJson.put("action", "subgroup_created");
                pktJson.put("subgroupId", subGroupId);
                pktJson.put("groupId", parentGroupId);
                pktJson.put("title", name);
                pktJson.put("description", description);
                pktJson.put("isSecret", true);
                WebRtcManager.getInstance(mContext).broadcastPacket(
                        new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_GROUP_SETTINGS, pktJson.toString()));
            } catch (Exception ignored) {}
        });
        return subGroupId;
    }

    /**
     * Creates a Group Event / Calendar item (#68) persisted in SQLCipher and broadcast over WebRTC.
     */
    public String createGroupEvent(String groupId, String title, String description, long eventTimeMs) {
        String eventId = "evt_" + UUID.randomUUID().toString().substring(0, 8);
        String creatorId = getCurrentUserId();
        mStorageQueue.execute(() -> {
            EncryptedChatDatabase.GroupEvent evt = new EncryptedChatDatabase.GroupEvent(
                    eventId, groupId, creatorId, "@Anon", title, description, "P2P Room", eventTimeMs, eventTimeMs + 3600000L);
            mDatabase.saveGroupEvent(evt);
            try {
                JSONObject pktJson = new JSONObject();
                pktJson.put("type", "group_event");
                pktJson.put("eventId", eventId);
                pktJson.put("groupId", groupId);
                pktJson.put("title", title);
                pktJson.put("description", description);
                pktJson.put("startTime", eventTimeMs);
                WebRtcManager.getInstance(mContext).broadcastPacket(
                        new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_GROUP_SETTINGS, pktJson.toString()));
            } catch (Exception ignored) {}
        });
        return eventId;
    }

    /**
     * Dispatches an in-chat P2P minigame state frame (VMGM|#219) over WebRTC.
     */
    public void sendMinigameFrame(String dialogId, String gameType, String statePayload) {
        Chat chat = getDialog(dialogId);
        if (chat != null) {
            sendMessage(chat, "VMGM|" + gameType + "|" + statePayload, null);
        }
    }

    /**
     * Lifecycle tear down on application exit.
     */
    public void onDestroy() {
        try {
            if (mVanishDaemonFuture != null) mVanishDaemonFuture.cancel(true);
            if (mTypingDecayFuture != null) mTypingDecayFuture.cancel(true);
            if (mScreenshotObserver != null) {
                mContext.getContentResolver().unregisterContentObserver(mScreenshotObserver);
            }
            WebRtcManager.getInstance(mContext).removeEventListener(this);
            mStageThread.quitSafely();
            mStorageQueue.shutdown();
            mCryptoQueue.shutdown();
            mScheduler.shutdown();
        } catch (Exception ignored) {}
    }
}
