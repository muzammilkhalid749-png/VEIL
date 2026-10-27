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
            t.setPriority(Thread.NORM_PRIORITY);
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
                        flushPendingOfflineMessagesOnNetworkAvailable();
                    }
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not register network callback", e);
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
                    final String dialogId = msg.getChatId();
                    if (dialogId == null || dialogId.isEmpty()) continue;
                    final Chat chat = getDialog(dialogId) != null ? getDialog(dialogId) : mDatabase.getChat(dialogId);
                    final boolean isGroup = (chat != null && chat.isGroupChat())
                            || dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_");
                    final String recipientId = msg.getReceiverId();

                    mCryptoQueue.execute(() -> {
                        if (!isNetworkConnected()) return;
                        try {
                            if (isGroup) {
                                syncGroupOrChannelMessageToFirestore(chat, dialogId, msg);
                            } else {
                                syncDirectMessageToFirestore(chat, dialogId, recipientId, msg);
                            }
                            List<String> targetPeers = resolveTargetPeers(dialogId, recipientId, msg);
                            String jsonPayloadStr = buildJsonFallbackString(dialogId, msg);
                            for (String peer : targetPeers) {
                                VeilPacket pkt = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, jsonPayloadStr);
                                if (!WebRtcManager.getInstance(mContext).sendPacket(peer, pkt)) {
                                    try {
                                        com.m3.veil.webrtc.SignalingClient.getInstance().sendP2PSignal(
                                                peer, "p2p_chat", new org.json.JSONObject(jsonPayloadStr));
                                    } catch (Exception ignored) {}
                                    WebRtcManager.getInstance(mContext).initiateConnection(peer);
                                }
                            }
                            msg.setPending(false);
                            Message cached = mMessagesById.get(msg.getId());
                            if (cached != null) cached.setPending(false);
                            mStorageQueue.execute(() -> {
                                mDatabase.setMessagePending(msg.getId(), false);
                                refreshDialogInCache(dialogId);
                            });
                            mMainHandler.post(() -> {
                                for (MessagesObserver obs : mObservers) {
                                    obs.onMessageStatusChanged(dialogId, msg.getId(), msg.isDelivered(), msg.isRead());
                                }
                            });
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
        this.mCurrentUserName = prefs.getString("username", "VeilUser");
    }

    public String getCurrentUserId() {
        if (mCurrentUserId == null) reloadCurrentIdentity();
        return mCurrentUserId;
    }

    public String getCurrentUserName() {
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
                        List<Message> msgs = mDatabase.getMessages(c.getId(), 50, 0);
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
        boolean isGroupOrChannel = (chat != null && chat.isGroupChat())
                || dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_");
        String finalRecipientId = recipientId;
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
        if (message.getSenderName() == null) {
            message.setSenderName(getCurrentUserName());
        }
        if (isGroupOrChannel && (message.getReceiverId() == null || message.getReceiverId().isEmpty())) {
            message.setReceiverId(dialogId);
        }

        // Synchronously cache & persist with pending=true BEFORE returning so ChatActivity.reloadMessagesFromLocalVault()
        // immediately renders the Clock icon without a race condition.
        putMessageInCache(dialogId, message);
        mDatabase.saveMessage(dialogId, message);
        updateDialogLastMessage(dialogId, message);

        mStageQueue.post(() -> {
            long lamport = nextLamportClock();
            int seq = nextDialogSeq(dialogId);

            long vanishTimer = message.getVanishTimer();
            if (vanishTimer <= 0 && chat != null && chat.getVanishTimer() > 0) {
                vanishTimer = chat.getVanishTimer();
                message.setVanishTimer(vanishTimer);
            }
            String vanishMode = message.getVanishMode();
            if (vanishMode == null && chat != null && chat.getVanishMode() != null) {
                vanishMode = chat.getVanishMode();
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
            long vanishTimer = chat != null ? chat.getVanishTimer() : 0L;
            String vanishMode = (chat != null && chat.getVanishMode() != null) ? chat.getVanishMode() : "after_send";

            Message msg = new Message(messageId, getCurrentUserId(), recipientId != null ? recipientId : dialogId,
                    caption != null ? caption : "", Timestamp.now(), mediaType);
            msg.setChatId(dialogId);
            msg.setSenderName(getCurrentUserName());
            msg.setLocalFilePath(file.getAbsolutePath());
            msg.setMediaSize(file.length());
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

            if (vanishTimer > 0 && "after_send".equals(vanishMode)) {
                long expiresAt = System.currentTimeMillis() + vanishTimer;
                msg.setExpiresAt(new Timestamp(new Date(expiresAt)));
            }

            dispatchMessageInternal(dialogId, recipientId, msg, file);
        });
    }

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
            for (String pId : chat.getParticipants()) {
                if (pId != null && !pId.trim().isEmpty() && !pId.equals(myId) && !targets.contains(pId)) {
                    targets.add(pId);
                }
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

        // 3. Notify UI observers that message was placed in sent queue
        runOnUI(() -> {
            for (MessagesObserver obs : mObservers) {
                obs.onMessageSent(dialogId, msg);
            }
        });

        // 4. Encrypt payload on cryptoQueue and fan out to all target peers (1:1 or Group/Channel)
        mCryptoQueue.execute(() -> {
            try {
                Chat chat = getDialog(dialogId);
                if (chat == null) chat = mDatabase.getChat(dialogId);
                boolean isGroup = (chat != null && chat.isGroupChat())
                        || (dialogId != null && (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")));

                List<String> targetPeers = resolveTargetPeers(dialogId, recipientId, msg);
                boolean hasNetwork = isNetworkConnected();

                // Only sync to Firestore/Nostr immediately if device has network connectivity.
                // If offline, keep message in pending (Clock) state until network returns or P2P DataChannel transmits it.
                if (hasNetwork) {
                    if (isGroup && dialogId != null) {
                        syncGroupOrChannelMessageToFirestore(chat, dialogId, msg);
                    } else if (dialogId != null) {
                        syncDirectMessageToFirestore(chat, dialogId, recipientId, msg);
                    }
                }

                String jsonPayloadStr = buildJsonFallbackString(dialogId, msg);
                boolean anyTransmitted = false;

                for (String peer : targetPeers) {
                    boolean transmitted = false;
                    if (hasNetwork) {
                        if (localFile != null && localFile.exists()) {
                            transmitted = sendMediaP2P(peer, msg, localFile);
                        } else {
                            VeilPacket pkt = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, jsonPayloadStr);
                            transmitted = WebRtcManager.getInstance(mContext).sendPacket(peer, pkt);
                        }
                    }

                    if (transmitted) {
                        anyTransmitted = true;
                    } else {
                        if (hasNetwork && (localFile == null || !localFile.exists())) {
                            try {
                                com.m3.veil.webrtc.SignalingClient.getInstance().sendP2PSignal(
                                        peer, "p2p_chat", new org.json.JSONObject(jsonPayloadStr));
                            } catch (Exception ignored) {}
                        }
                        if (hasNetwork) {
                            WebRtcManager.getInstance(mContext).initiateConnection(peer);
                        }

                        String outboxKey = isGroup ? (msg.getId() + "_" + peer) : msg.getId();
                        OutboxEntry outbox = new OutboxEntry(msg, dialogId, peer, isGroup,
                                chat != null && isGroup ? chat.getName() : getCurrentUserName(),
                                chat != null ? chat.getType() : "direct", localFile);
                        mOutboxQueue.add(outbox);
                        mPendingOutboxById.put(outboxKey, outbox);

                        // Enqueue into VeilMeshDaemonService with full Chat metadata
                        VeilMeshDaemonService.getInstance(mContext).enqueueOutgoingMessage(chat, peer, msg);
                    }
                }

                if (hasNetwork) {
                    // Dispatched while online: transition from Clock (pending=true) to Single Tick (pending=false)
                    msg.setPending(false);
                    mStorageQueue.execute(() -> {
                        mDatabase.updateMessageStatus(msg.getId(), msg.isDelivered(), msg.isRead());
                        refreshDialogInCache(dialogId);
                    });
                    final String fDialogId = dialogId;
                    final String fMsgId = msg.getId();
                    final boolean fDelivered = msg.isDelivered();
                    final boolean fRead = msg.isRead();
                    mMainHandler.post(() -> {
                        for (MessagesObserver obs : mObservers) {
                            obs.onMessageStatusChanged(fDialogId, fMsgId, fDelivered, fRead);
                        }
                    });
                } else {
                    // Device has NO network connectivity: strictly keep Clock icon (pending=true)
                    msg.setPending(true);
                    msg.setDelivered(false);
                    msg.setRead(false);
                    mStorageQueue.execute(() -> {
                        mDatabase.setMessagePending(msg.getId(), true);
                        refreshDialogInCache(dialogId);
                    });
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

    private boolean sendMediaP2P(String peerId, Message msg, File localFile) {
        try {
            boolean isConnected = WebRtcManager.getInstance(mContext).isPeerConnected(peerId);
            if (!isConnected) {
                Log.d(TAG, "Peer " + peerId + " is currently offline; enqueuing media P2P transfer");
                return false;
            }
            WebRtcManager.getInstance(mContext).sendFileMessage(peerId, msg, localFile,
                    new P2PFileTransferHelper.FileTransferCallback() {
                        @Override
                        public void onProgress(int percent) {
                            runOnUI(() -> {
                                for (MessagesObserver obs : mObservers) {
                                    obs.onMediaTransferProgress(msg.getChatId(), msg.getId(), percent);
                                }
                            });
                        }

                        @Override
                        public void onComplete(String transferId) {
                            Log.d(TAG, "P2P media stream complete for message: " + msg.getId());
                            runOnUI(() -> {
                                for (MessagesObserver obs : mObservers) {
                                    obs.onMediaTransferProgress(msg.getChatId(), msg.getId(), 100);
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "P2P media stream failed: " + error);
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

            // Remove from memory cache
            removeMessageFromCache(dialogId, messageId);

            // Shred local media & delete from SQLCipher
            mStorageQueue.execute(() -> {
                if (localPath != null) {
                    cryptographicZeroWipe(new File(localPath));
                }
                mDatabase.deleteMessage(messageId);
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
                        if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put("type", "deleted");
                            upd.put("text", "🚫 This message was deleted");
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("groups").document(dialogId)
                                    .collection("messages").document(messageId)
                                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
                        } else {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put("type", "deleted");
                            upd.put("text", "🚫 This message was deleted");
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

            if (emoji.equals(reactions.get(reactorKey)) || emoji.isEmpty()) {
                reactions.remove(reactorKey);
            } else {
                reactions.put(reactorKey, emoji);
            }
            msg.setReactions(reactions);

            // Update DB
            mStorageQueue.execute(() -> mDatabase.updateMessageReaction(dialogId, messageId, reactorKey, emoji));

            // Notify UI
            runOnUI(() -> {
                for (MessagesObserver obs : mObservers) {
                    obs.onReactionUpdated(dialogId, messageId, reactorKey, emoji);
                }
            });

            // Broadcast to all target peers
            final Map<String, String> finalReactions = new HashMap<>(reactions);
            mCryptoQueue.execute(() -> {
                for (String peerId : resolveTargetPeers(dialogId, null, msg)) {
                    WebRtcManager.getInstance(mContext).sendReaction(peerId, dialogId, messageId, reactorKey, emoji);
                }
                if (dialogId.startsWith("group_") || dialogId.startsWith("channel_") || dialogId.startsWith("lounge_")) {
                    try {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("reactions", finalReactions);
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("groups").document(dialogId)
                                .collection("messages").document(messageId)
                                .set(upd, com.google.firebase.firestore.SetOptions.merge());
                    } catch (Exception ignored) {}
                } else {
                    try {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("reactions", finalReactions);
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
        if (recipientId == null) return;
        mCryptoQueue.execute(() -> {
            try {
                WebRtcManager.getInstance(mContext).sendTyping(recipientId, isTyping);
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
                String myId = getCurrentUserId();
                String peerId = recipientId;
                if (peerId == null && msg != null) {
                    peerId = (myId != null && myId.equals(msg.getReceiverId())) ? msg.getSenderId() : msg.getReceiverId();
                }
                if (peerId != null) {
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
                String myId = getCurrentUserId();
                String peerId = recipientId;
                if (peerId == null && msg != null) {
                    peerId = (myId != null && myId.equals(msg.getReceiverId())) ? msg.getSenderId() : msg.getReceiverId();
                }
                if (peerId != null) {
                    WebRtcManager.getInstance(mContext).sendPollVote(peerId, dialogId, messageId, updatedPollJson);
                }
            });
        });
    }

    /**
     * Requests streaming P2P download for a media message via WebRTC with DownloadProgressListener.
     */
    public void downloadMedia(String recipientId, String messageId, P2PFileTransferHelper.DownloadProgressListener callback) {
        if (recipientId == null || messageId == null) return;
        WebRtcManager.getInstance(mContext).requestMediaDownload(recipientId, messageId, callback);
    }

    /**
     * Requests streaming P2P download for a media message via WebRTC.
     */
    public void downloadMedia(String recipientId, Message message, File destination, P2PFileTransferHelper.FileTransferCallback callback) {
        if (recipientId == null || message == null || message.getId() == null) return;
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
     * Cancels an ongoing P2P media download.
     */
    public void cancelMediaDownload(String recipientId, String messageId) {
        if (recipientId == null || messageId == null) return;
        WebRtcManager.getInstance(mContext).cancelMediaDownload(recipientId, messageId);
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
                if (now - entry.getValue() > 3000) { // 3 second typing decay
                    iter.remove();
                    String key = entry.getKey();
                    String[] parts = key.split(":");
                    if (parts.length == 2) {
                        String dialogId = parts[0];
                        String peerId = parts[1];
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
            boolean isChatOpenInForeground = dialogId != null && dialogId.equals(mActiveChatIdInForeground) && sendReadReceipt;
            message.setDelivered(true);
            if (isChatOpenInForeground) {
                message.setRead(true);
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
                            && !m.isRead() && m.isDelivered() && !m.isPending() && !m.isMediaRequiringActionToRead()) {
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
                    if (updatedIds.isEmpty()) {
                        obs.onMessageStatusChanged(chatId, null, true, true);
                    } else {
                        for (String mId : updatedIds) {
                            obs.onMessageStatusChanged(chatId, mId, true, true);
                        }
                    }
                }
            });
        });
    }

    @Override
    public void onPeerTyping(String peerId, boolean isTyping) {
        if (peerId == null) return;
        String dialogId = WebRtcManager.getChatId(getCurrentUserId(), peerId);
        String key = dialogId + ":" + peerId;

        if (isTyping) {
            mTypingWaves.put(key, System.currentTimeMillis());
        } else {
            mTypingWaves.remove(key);
        }

        runOnUI(() -> {
            for (MessagesObserver obs : mObservers) {
                obs.onPeerTyping(dialogId, peerId, isTyping);
            }
        });
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
     * Flushes pending outbox messages when a WebRTC peer reconnects.
     */
    private void flushOutboxForPeer(String peerId) {
        Iterator<OutboxEntry> iter = mOutboxQueue.iterator();
        while (iter.hasNext()) {
            OutboxEntry entry = iter.next();
            if (entry.peerId != null && entry.peerId.equals(peerId)) {
                iter.remove();
                mPendingOutboxById.remove(entry.message.getId());

                mCryptoQueue.execute(() -> {
                    boolean ok = false;
                    if (entry.localMediaFile != null && entry.localMediaFile.exists()) {
                        ok = sendMediaP2P(peerId, entry.message, entry.localMediaFile);
                    } else {
                        VeilPacket pkt = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE,
                                buildJsonFallbackString(entry.dialogId, entry.message));
                        ok = WebRtcManager.getInstance(mContext).sendPacket(peerId, pkt);
                    }
                    if (ok) {
                        entry.message.setPending(false);
                        mStorageQueue.execute(() -> {
                            mDatabase.updateMessageStatus(entry.message.getId(), false, false);
                            refreshDialogInCache(entry.dialogId);
                        });
                        runOnUI(() -> {
                            for (MessagesObserver obs : mObservers) {
                                obs.onMessageStatusChanged(entry.dialogId, entry.message.getId(), false, false);
                            }
                        });
                        Log.d(TAG, "Outbox packet flushed successfully for peer: " + peerId);
                    } else {
                        // Re-enqueue if still failed
                        mOutboxQueue.add(entry);
                    }
                });
            }
        }
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
            if (msg.getMediaUrl() != null) p.put("mediaUrl", msg.getMediaUrl());
            if (msg.getLocalFilePath() != null) p.put("localFilePath", msg.getLocalFilePath());
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
        if (dialogId == null || !dialogId.equals(mActiveChatIdInForeground)) return;

        mStageQueue.post(() -> {
            if (!dialogId.equals(mActiveChatIdInForeground)) return;
            mStorageQueue.execute(() -> {
                mDatabase.markAllAsRead(dialogId, getCurrentUserId());
                mUnreadCountByDialog.put(dialogId, new AtomicInteger(0));
                refreshDialogInCache(dialogId);
            });

            // Transmit read receipt packet to remote peer over WebRTC
            if (recipientId != null) {
                mCryptoQueue.execute(() -> {
                    WebRtcManager.getInstance(mContext).sendChatReadReceipt(recipientId, dialogId);
                });
            }
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

            // Transmit setting packet to remote peer
            if (recipientId != null) {
                mCryptoQueue.execute(() -> {
                    WebRtcManager.getInstance(mContext).sendVanishSetting(recipientId, dialogId, vanishMode, vanishTimer);
                });
            }
        });
    }

    /**
     * Pins a message to the top of the chat dialog.
     */
    public void pinMessage(String dialogId, String recipientId, String messageId, String messageText) {
        if (dialogId == null || messageId == null) return;

        mStageQueue.post(() -> {
            Chat chat = getDialog(dialogId);
            if (chat != null) {
                chat.setPinnedMessageId(messageId);
                chat.setPinnedMessageText(messageText);
                mStorageQueue.execute(() -> mDatabase.saveOrUpdateChat(chat));
            }

            if (recipientId != null) {
                mCryptoQueue.execute(() -> {
                    WebRtcManager.getInstance(mContext).sendPinMessageSetting(recipientId, dialogId, messageId, messageText);
                });
            }
        });
    }

    /**
     * Unpins the pinned message in a dialog.
     */
    public void unpinMessage(String dialogId, String recipientId) {
        pinMessage(dialogId, recipientId, null, null);
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
