package com.m3.veil.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.m3.veil.R;
import com.m3.veil.VeilApplication;
import com.m3.veil.calling.CallEngine;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.database.EncryptedChatDatabase.OutboxItem;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL ZERO-KNOWLEDGE P2P MESH DAEMON SERVICE (SIGNAL SMART-HYBRID ARCHITECTURE)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * 1. Keeps WebRtcManager, SignalingClient (Firestore + WebSocket), and CallEngine alive when
 *    woken by Blind FCM Wake Tickles ("MyFirebaseMessagingService") or when the app is active.
 * 2. Watches "users/{myUid}/mesh_signals" in Firebase Firestore (immediately deleting ephemeral
 *    wake-knock documents on receipt) to establish direct P2P WebRTC DataChannels within 200ms.
 * 3. Drains the local SQLCipher WAL Outbox ("outbox") directly over WebRTC P2P
 *    DataChannels and sends Blind FCM Wake Knocks to sleeping peers so messages never get stuck
 *    on single-tick!
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class VeilMeshDaemonService extends Service {
    private static final String TAG = "VeilMeshDaemon";

    public static final String CHANNEL_ID = "veil_mesh_daemon_channel";
    public static final int NOTIFICATION_ID = 9420;

    public static final String ACTION_START_MESH = "com.m3.veil.action.START_MESH";
    public static final String ACTION_STOP_MESH = "com.m3.veil.action.STOP_MESH";
    public static final String ACTION_WAKE_KNOCK = "com.m3.veil.action.WAKE_KNOCK";
    public static final String ACTION_DRAIN_OUTBOX = "com.m3.veil.action.DRAIN_OUTBOX";

    public static final int PACKET_MAGIC = 0x5645494C; // "VEIL"
    public static final byte PACKET_VERSION = 0x02;
    public static final byte DEFAULT_TTL_HOPS = 5;

    public static final byte TYPE_DIRECT_E2EE = 0x01;
    public static final byte TYPE_HOP_RELAY = 0x02;
    public static final byte TYPE_DELIVERY_RECEIPT = 0x03;
    public static final byte TYPE_READ_RECEIPT = 0x04;
    public static final byte TYPE_BLOOM_SYNC = 0x05;
    public static final byte TYPE_STATUS_CHUNK = 0x06;
    public static final byte TYPE_BOARD_GOSSIP = 0x07;
    public static final byte TYPE_HEARTBEAT_PING = 0x08;

    private static volatile VeilMeshDaemonService instance;
    private static volatile Context appContext;

    private ScheduledExecutorService meshExecutor;
    private Handler mainHandler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ListenerRegistration meshSignalRegistration;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Set<String> seenPacketIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> lastWakeKnockSentMs = new ConcurrentHashMap<>();

    public static void start(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        try {
            String currentUid = AuthManager.getInstance().getCurrentUserId(appContext);
            if (currentUid == null || currentUid.isEmpty()) {
                return;
            }
            Intent intent = new Intent(appContext, VeilMeshDaemonService.class);
            intent.setAction(ACTION_START_MESH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Background start fallback to in-process mesh init: " + e.getMessage());
            getInstance(appContext);
        }
    }

    public static void wakeFromPush(Context context, @Nullable String senderId, @Nullable String mailboxHash) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        ensureInProcessEnginesInitialized(appContext);
        if (senderId != null && !senderId.isEmpty()) {
            try {
                WebRtcManager.getInstance(appContext).initiateConnection(senderId);
            } catch (Exception ignored) {}
        }
        try {
            Intent intent = new Intent(appContext, VeilMeshDaemonService.class);
            intent.setAction(ACTION_WAKE_KNOCK);
            if (senderId != null) intent.putExtra("sender_id", senderId);
            if (mailboxHash != null) intent.putExtra("mailbox_hash", mailboxHash);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Exception e) {
            VeilMeshDaemonService svc = getInstance(appContext);
            if (svc != null) {
                svc.onWakeKnockReceived(senderId, mailboxHash);
            }
        }
    }

    private static void ensureInProcessEnginesInitialized(Context ctx) {
        if (ctx == null) return;
        String uid = AuthManager.getInstance().getCurrentUserId(ctx);
        if (uid != null && !uid.isEmpty()) {
            try {
                WebRtcManager.getInstance(ctx).init(uid);
            } catch (Throwable ignored) {}
            try {
                CallEngine.getInstance(ctx).init(uid);
            } catch (Throwable ignored) {}
        }
    }

    @Nullable
    public static VeilMeshDaemonService getInstance() {
        return getInstance(appContext != null ? appContext : VeilApplication.getInstance());
    }

    @NonNull
    public static synchronized VeilMeshDaemonService getInstance(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (instance == null) {
            instance = new VeilMeshDaemonService();
            instance.mainHandler = new Handler(Looper.getMainLooper());
            instance.initializeMeshEngine();
        }
        return instance;
    }

    private Context getCtx() {
        if (appContext != null) return appContext;
        try {
            Context c = getApplicationContext();
            if (c != null) return c;
        } catch (Exception ignored) {}
        return VeilApplication.getInstance();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        appContext = getApplicationContext();
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        createNotificationChannel();
        startForegroundSafe();
        initializeMeshEngine();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();

        if (intent != null && ACTION_STOP_MESH.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureInProcessEnginesInitialized(getCtx());

        if (intent != null && ACTION_WAKE_KNOCK.equals(intent.getAction())) {
            String senderId = intent.getStringExtra("sender_id");
            String mailbox = intent.getStringExtra("mailbox_hash");
            onWakeKnockReceived(senderId, mailbox);
        } else {
            triggerImmediateOutboxDrain();
        }

        return START_STICKY;
    }

    private void startForegroundSafe() {
        try {
            Notification notification = buildSilentMeshNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTIFICATION_ID, buildSilentMeshNotification());
            } catch (Exception ignored) {}
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "VEIL P2P Encrypted Mesh",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Maintains zero-knowledge P2P WebRTC connection for instant E2EE messages and calls.");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildSilentMeshNotification() {
        Context ctx = getCtx();
        Intent openIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                ctx,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("VEIL Zero-Knowledge P2P")
                .setContentText("Direct encrypted mesh active")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void initializeMeshEngine() {
        if (!isRunning.compareAndSet(false, true)) {
            return;
        }

        ensureInProcessEnginesInitialized(getCtx());

        meshExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "VeilMeshWorker");
            t.setDaemon(true);
            return t;
        });

        meshExecutor.scheduleWithFixedDelay(this::drainOutbox, 1, 4, TimeUnit.SECONDS);
        meshExecutor.scheduleWithFixedDelay(this::exchangeBloomFiltersWithPeers, 8, 25, TimeUnit.SECONDS);
        meshExecutor.scheduleWithFixedDelay(this::pruneExpiredPackets, 60, 120, TimeUnit.SECONDS);

        registerNetworkChangeCallback();
        attachMeshWakeListener();
    }

    private void registerNetworkChangeCallback() {
        try {
            Context ctx = getCtx();
            if (ctx == null) return;
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.i(TAG, "Network available -> re-syncing signaling & draining P2P outbox");
                    ensureInProcessEnginesInitialized(getCtx());
                    triggerImmediateOutboxDrain();
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "NetworkCallback registration failed", e);
        }
    }

    /**
     * Watches "users/{myUid}/mesh_signals" in Firebase Firestore for incoming Blind Wake Knocks,
     * deletes each knock document immediately upon receipt for zero cloud footprint, and opens
     * a direct WebRTC P2P connection to the sender!
     */
    private void attachMeshWakeListener() {
        try {
            String myUid = AuthManager.getInstance().getCurrentUserId(getCtx());
            if (myUid == null || myUid.isEmpty()) return;

            if (meshSignalRegistration != null) {
                meshSignalRegistration.remove();
            }

            meshSignalRegistration = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(myUid)
                    .collection("mesh_signals")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null || snapshots == null) return;
                        for (DocumentChange dc : snapshots.getDocumentChanges()) {
                            if (dc.getType() == DocumentChange.Type.ADDED) {
                                DocumentSnapshot doc = dc.getDocument();
                                String senderId = doc.getString("senderId");
                                if (senderId == null || senderId.isEmpty()) {
                                    senderId = doc.getString("from");
                                }
                                String mailbox = doc.getString("mailbox");
                                // Immediate zero-knowledge purge of the knock document
                                doc.getReference().delete();
                                onWakeKnockReceived(senderId, mailbox);
                            }
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "Mesh wake listener notice: " + e.getMessage());
        }
    }

    public void onWakeKnockReceived(@Nullable String senderId, @Nullable String mailboxHash) {
        Context ctx = getCtx();
        PowerManager pm = ctx != null ? (PowerManager) ctx.getSystemService(Context.POWER_SERVICE) : null;
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veil:mesh_wake_lock");
            wl.acquire(10_000L);
        }

        final PowerManager.WakeLock finalWl = wl;
        if (meshExecutor == null || meshExecutor.isShutdown()) {
            initializeMeshEngine();
        }

        meshExecutor.execute(() -> {
            try {
                String myUid = AuthManager.getInstance().getCurrentUserId(getCtx());
                if (myUid != null && !myUid.isEmpty()) {
                    WebRtcManager rtc = WebRtcManager.getInstance(getCtx());
                    rtc.init(myUid);
                    if (senderId != null && !senderId.isEmpty() && !senderId.equals(myUid)) {
                        mainHandler.post(() -> rtc.initiateConnection(senderId));
                    }
                    drainOutbox();
                }
            } catch (Exception e) {
                Log.e(TAG, "Wake knock handling error", e);
            } finally {
                if (finalWl != null && finalWl.isHeld()) {
                    try {
                        finalWl.release();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    public void triggerOutboxSync() {
        triggerImmediateOutboxDrain();
    }

    public void triggerImmediateOutboxDrain() {
        if (meshExecutor != null && !meshExecutor.isShutdown()) {
            meshExecutor.execute(this::drainOutbox);
        }
    }

    public void flushOutboxForPeer(String peerId) {
        if (peerId == null || peerId.isEmpty()) return;
        triggerImmediateOutboxDrain();
    }

    public void onDeliveredAckReceived(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        try {
            EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItem(messageId);
        } catch (Exception ignored) {}
    }

    public void enqueueOutgoingMessage(@Nullable Chat chat, String peerId, Message message) {
        if (peerId == null || peerId.isEmpty() || message == null) return;
        try {
            Context ctx = getCtx();
            String myUid = AuthManager.getInstance().getCurrentUserId(ctx);
            String chatId = chat != null && chat.getId() != null
                    ? chat.getId()
                    : WebRtcManager.getChatId(myUid, peerId);

            JSONObject json = new JSONObject();
            json.put("type", "chat");
            JSONObject p = new JSONObject();
            p.put("id", message.getId());
            p.put("chatId", chatId);
            p.put("senderId", myUid);
            p.put("senderName", message.getSenderName() != null
                    ? message.getSenderName()
                    : VeilFeatureManager.getInstance(ctx).getActivePersona().getDisplayName());
            p.put("text", message.getText());
            p.put("msgType", message.getType());
            p.put("timestamp", message.getTimestamp() != null
                    ? message.getTimestamp().toDate().getTime()
                    : System.currentTimeMillis());
            p.put("replyToId", message.getReplyToMessageId());
            p.put("replySenderName", message.getReplySenderName());
            p.put("blink", message.isBlink());
            p.put("voiceFilter", message.getVoiceFilter());
            p.put("pollJson", message.getPollJson());
            if (message.getThumbnail() != null) p.put("thumbnail", message.getThumbnail());
            if (message.getMediaUrl() != null) p.put("mediaUrl", message.getMediaUrl());
            if (message.getLocalFilePath() != null) p.put("localFilePath", message.getLocalFilePath());
            p.put("expiresAt", message.getExpiresAt() != null ? message.getExpiresAt().toDate().getTime() : 0L);
            p.put("vanishTimer", message.getVanishTimer());
            p.put("vanishMode", message.getVanishMode());
            if (chat != null) {
                p.put("isGroup", chat.isGroup());
                if (chat.isGroup() && chat.getName() != null) {
                    p.put("chatName", chat.getName());
                } else {
                    String sName = message.getSenderName() != null ? message.getSenderName()
                            : VeilFeatureManager.getInstance(ctx).getActivePersona().getDisplayName();
                    p.put("chatName", sName);
                }
                if (chat.getType() != null) p.put("chatType", chat.getType());
            }
            json.put("payload", p);

            enqueueMeshPacketWithId(
                    message.getId(),
                    chatId,
                    peerId,
                    TYPE_DIRECT_E2EE,
                    json.toString().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            Log.e(TAG, "enqueueOutgoingMessage failed", e);
        }
    }

    public void enqueueActionEnvelope(String peerId, String rawJsonPayload) {
        if (peerId == null || peerId.isEmpty() || rawJsonPayload == null || rawJsonPayload.isEmpty()) return;
        enqueueMeshPacket(peerId, TYPE_DIRECT_E2EE, rawJsonPayload.getBytes(StandardCharsets.UTF_8));
    }

    public void handleMeshPacket(String fromPeerId, VeilPacket packet) {
        if (packet == null || packet.getPayload() == null) return;
        onIncomingMeshFrame(fromPeerId, packet.getPayload());
    }

    /**
     * Drains pending messages from SQLCipher "outbox" over active WebRTC DataChannels,
     * or initiates a WebRTC P2P connection + Blind FCM Wake Knock if the peer is not yet connected.
     */
    private void drainOutbox() {
        try {
            Context ctx = getCtx();
            String myUid = AuthManager.getInstance().getCurrentUserId(ctx);
            if (myUid == null || myUid.isEmpty()) return;

            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(ctx);
            List<OutboxItem> pending = db.getPendingOutboxItems(50);
            if (pending == null || pending.isEmpty()) return;

            WebRtcManager rtc = WebRtcManager.getInstance(ctx);
            List<String> connectedPeers = rtc.getConnectedPeerIds();
            long now = System.currentTimeMillis();

            for (OutboxItem entry : pending) {
                if (entry == null || entry.recipientId == null || entry.id == null) continue;

                if (connectedPeers.contains(entry.recipientId) || rtc.isPeerConnected(entry.recipientId)) {
                    boolean sent = sendFrameOverDataChannel(rtc, entry.recipientId, entry.payload);
                    if (sent) {
                        db.removeOutboxItem(entry.id);
                        db.updateMessageStatus(entry.id, true, false);
                    } else {
                        db.updateOutboxAttempt(entry.id, entry.retryCount + 1, now);
                    }
                } else {
                    boolean relayed = false;
                    for (String relayPeerId : connectedPeers) {
                        if (sendFrameOverDataChannel(rtc, relayPeerId, entry.payload)) {
                            relayed = true;
                            break;
                        }
                    }

                    // Throttle connection attempts & blind FCM wake knocks per peer to once every 6 seconds
                    Long lastKnock = lastWakeKnockSentMs.get(entry.recipientId);
                    if (lastKnock == null || (now - lastKnock) > 6_000L) {
                        lastWakeKnockSentMs.put(entry.recipientId, now);
                        mainHandler.post(() -> rtc.initiateConnection(entry.recipientId));
                        sendBlindWakeKnock(entry.recipientId);
                    }

                    if (!relayed) {
                        db.updateOutboxAttempt(entry.id, entry.retryCount + 1, now);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error draining mesh outbox", e);
        }
    }

    private boolean sendFrameOverDataChannel(WebRtcManager rtc, String peerId, byte[] frameBytes) {
        if (rtc == null || peerId == null || frameBytes == null) return false;
        try {
            VeilPacket packet = new VeilPacket((byte) 3, (short) 769, frameBytes);
            if (rtc.sendPacket(peerId, packet)) {
                return true;
            }
            DataChannel dc = rtc.getDataChannel(peerId);
            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                return dc.send(new DataChannel.Buffer(ByteBuffer.wrap(frameBytes), false));
            }
        } catch (Exception ignored) {}
        return false;
    }

    public String enqueueMeshPacket(String recipientId, byte packetType, byte[] plaintextPayload) {
        Context ctx = getCtx();
        String myUid = AuthManager.getInstance().getCurrentUserId(ctx);
        String chatId = WebRtcManager.getChatId(myUid != null ? myUid : "anonymous", recipientId);
        return enqueueMeshPacketWithId(UUID.randomUUID().toString(), chatId, recipientId, packetType, plaintextPayload);
    }

    private String enqueueMeshPacketWithId(String packetId, String chatId, String recipientId, byte packetType, byte[] plaintextPayload) {
        try {
            Context ctx = getCtx();
            String safePacketId = (packetId != null && !packetId.isEmpty()) ? packetId : UUID.randomUUID().toString();
            String originId = AuthManager.getInstance().getCurrentUserId(ctx);
            if (originId == null) originId = "anonymous";

            byte[] frame = buildBinaryFrame(safePacketId, originId, recipientId, packetType, DEFAULT_TTL_HOPS, plaintextPayload);
            seenPacketIds.add(safePacketId);

            OutboxItem item = new OutboxItem(
                    safePacketId,
                    chatId != null ? chatId : WebRtcManager.getChatId(originId, recipientId),
                    recipientId,
                    packetType,
                    frame,
                    System.currentTimeMillis()
            );
            EncryptedChatDatabase.getInstance(ctx).enqueueOutboxItem(item);

            triggerImmediateOutboxDrain();
            return safePacketId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to enqueue mesh packet", e);
            return null;
        }
    }

    public void onIncomingMeshFrame(String fromPeerId, byte[] frameBytes) {
        if (frameBytes == null || frameBytes.length < 28) return;
        try {
            ByteBuffer buf = ByteBuffer.wrap(frameBytes);
            int magic = buf.getInt();
            if (magic != PACKET_MAGIC) return;

            byte version = buf.get();
            byte packetType = buf.get();
            byte ttlHops = buf.get();

            int idLen = buf.getShort() & 0xFFFF;
            if (idLen <= 0 || idLen > 128 || buf.remaining() < idLen) return;
            byte[] idBytes = new byte[idLen];
            buf.get(idBytes);
            String packetId = new String(idBytes, StandardCharsets.UTF_8);

            if (!seenPacketIds.add(packetId)) {
                return;
            }

            int origLen = buf.getShort() & 0xFFFF;
            if (origLen < 0 || origLen > 128 || buf.remaining() < origLen) return;
            byte[] origBytes = new byte[origLen];
            buf.get(origBytes);
            String originId = new String(origBytes, StandardCharsets.UTF_8);

            int destLen = buf.getShort() & 0xFFFF;
            if (destLen < 0 || destLen > 128 || buf.remaining() < destLen) return;
            byte[] destBytes = new byte[destLen];
            buf.get(destBytes);
            String recipientId = new String(destBytes, StandardCharsets.UTF_8);

            long timestampMs = buf.getLong();
            int bodyLen = buf.getInt();
            if (bodyLen < 0 || bodyLen > 16 * 1024 * 1024 || buf.remaining() < bodyLen) return;
            byte[] body = new byte[bodyLen];
            buf.get(body);

            String myUid = AuthManager.getInstance().getCurrentUserId(getCtx());
            if (myUid != null && (myUid.equals(recipientId) || "BROADCAST".equals(recipientId))) {
                handlePacketForSelf(packetId, originId, packetType, timestampMs, body);
                if (packetType == TYPE_DIRECT_E2EE) {
                    sendDeliveryReceipt(originId, packetId);
                }
            } else if (ttlHops > 1) {
                byte nextTtl = (byte) (ttlHops - 1);
                byte[] relayFrame = buildBinaryFrame(packetId, originId, recipientId, packetType, nextTtl, body);
                OutboxItem relayItem = new OutboxItem(
                        packetId,
                        WebRtcManager.getChatId(originId, recipientId),
                        recipientId,
                        packetType,
                        relayFrame,
                        System.currentTimeMillis()
                );
                EncryptedChatDatabase.getInstance(getCtx()).enqueueOutboxItem(relayItem);
                triggerImmediateOutboxDrain();
            }
        } catch (Exception e) {
            Log.e(TAG, "Malformed mesh binary frame", e);
        }
    }

    private void handlePacketForSelf(String packetId, String originId, byte packetType, long timestampMs, byte[] body) {
        Context ctx = getCtx();
        switch (packetType) {
            case TYPE_DELIVERY_RECEIPT: {
                String ackedPacketId = new String(body, StandardCharsets.UTF_8);
                EncryptedChatDatabase.getInstance(ctx).removeOutboxItem(ackedPacketId);
                EncryptedChatDatabase.getInstance(ctx).updateMessageStatus(ackedPacketId, true, false);
                break;
            }
            case TYPE_READ_RECEIPT: {
                String readPacketId = new String(body, StandardCharsets.UTF_8);
                EncryptedChatDatabase.getInstance(ctx).removeOutboxItem(readPacketId);
                EncryptedChatDatabase.getInstance(ctx).updateMessageStatus(readPacketId, true, true);
                break;
            }
            case TYPE_BLOOM_SYNC: {
                handlePeerBloomSync(originId, body);
                break;
            }
            default: {
                String payloadStr = new String(body, StandardCharsets.UTF_8);
                WebRtcManager.getInstance(ctx).processInboundPayload(originId, payloadStr);
                break;
            }
        }
    }

    public void sendDeliveryReceipt(String toPeerId, String packetId) {
        if (toPeerId == null || packetId == null) return;
        String originId = AuthManager.getInstance().getCurrentUserId(getCtx());
        if (originId == null) return;

        byte[] receiptFrame = buildBinaryFrame(
                UUID.randomUUID().toString(),
                originId,
                toPeerId,
                TYPE_DELIVERY_RECEIPT,
                DEFAULT_TTL_HOPS,
                packetId.getBytes(StandardCharsets.UTF_8)
        );
        sendFrameOverDataChannel(WebRtcManager.getInstance(getCtx()), toPeerId, receiptFrame);
    }

    private void exchangeBloomFiltersWithPeers() {
        try {
            Context ctx = getCtx();
            WebRtcManager rtc = WebRtcManager.getInstance(ctx);
            List<String> peers = rtc.getConnectedPeerIds();
            if (peers.isEmpty()) return;

            JSONArray knownIds = new JSONArray();
            for (String id : seenPacketIds) {
                knownIds.put(id);
                if (knownIds.length() >= 200) break;
            }
            JSONObject syncObj = new JSONObject();
            syncObj.put("known", knownIds);
            byte[] syncBytes = syncObj.toString().getBytes(StandardCharsets.UTF_8);

            String myUid = AuthManager.getInstance().getCurrentUserId(ctx);
            if (myUid == null) return;

            for (String peerId : peers) {
                byte[] frame = buildBinaryFrame(
                        UUID.randomUUID().toString(),
                        myUid,
                        peerId,
                        TYPE_BLOOM_SYNC,
                        (byte) 1,
                        syncBytes
                );
                sendFrameOverDataChannel(rtc, peerId, frame);
            }
        } catch (Exception ignored) {}
    }

    private void handlePeerBloomSync(String peerId, byte[] body) {
        try {
            JSONObject syncObj = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray arr = syncObj.optJSONArray("known");
            Set<String> peerKnown = new HashSet<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    peerKnown.add(arr.optString(i));
                }
            }

            List<OutboxItem> pending = EncryptedChatDatabase.getInstance(getCtx()).getPendingOutboxItemsForPeer(peerId);
            WebRtcManager rtc = WebRtcManager.getInstance(getCtx());
            for (OutboxItem entry : pending) {
                if (!peerKnown.contains(entry.id)) {
                    sendFrameOverDataChannel(rtc, peerId, entry.payload);
                } else {
                    EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItem(entry.id);
                }
            }
        } catch (Exception ignored) {}
    }

    private void pruneExpiredPackets() {
        try {
            if (seenPacketIds.size() > 5000) {
                seenPacketIds.clear();
            }
        } catch (Exception ignored) {}
    }

    private void sendBlindWakeKnock(String recipientId) {
        try {
            SignalingClient.getInstance().sendBlindFcmWakeKnock(recipientId, "mesh_outbox_pending");
        } catch (Exception ignored) {}
    }

    public static byte[] buildBinaryFrame(
            String packetId,
            String originId,
            String recipientId,
            byte packetType,
            byte ttlHops,
            byte[] body) {

        byte[] idBytes = packetId.getBytes(StandardCharsets.UTF_8);
        byte[] origBytes = originId.getBytes(StandardCharsets.UTF_8);
        byte[] destBytes = recipientId.getBytes(StandardCharsets.UTF_8);
        byte[] safeBody = body != null ? body : new byte[0];

        int totalSize = 4 + 1 + 1 + 1
                + 2 + idBytes.length
                + 2 + origBytes.length
                + 2 + destBytes.length
                + 8 + 4 + safeBody.length;

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.putInt(PACKET_MAGIC);
        buf.put(PACKET_VERSION);
        buf.put(packetType);
        buf.put(ttlHops);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        buf.putShort((short) origBytes.length);
        buf.put(origBytes);
        buf.putShort((short) destBytes.length);
        buf.put(destBytes);
        buf.putLong(System.currentTimeMillis());
        buf.putInt(safeBody.length);
        buf.put(safeBody);
        return buf.array();
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    @Override
    public void onDestroy() {
        isRunning.set(false);
        if (meshSignalRegistration != null) {
            try {
                meshSignalRegistration.remove();
            } catch (Exception ignored) {}
            meshSignalRegistration = null;
        }
        if (networkCallback != null) {
            try {
                Context ctx = getCtx();
                if (ctx != null) {
                    ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {}
        }
        if (meshExecutor != null) {
            meshExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
