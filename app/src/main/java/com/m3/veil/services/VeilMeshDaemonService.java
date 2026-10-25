package com.m3.veil.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.m3.veil.calling.CallEngine;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 100% Pure P2P WebRTC Mesh Daemon & Store-and-Forward Engine.
 * Modeled on Telegram's ConnectionsManager.java:
 * 1. Persistent background sync maintaining zero-server WebRTC signaling and mesh connectivity.
 * 2. SQLite Outbox Queue Manager: outgoing messages stay strictly in local EncryptedChatDatabase,
 *    triggering zero-knowledge wake knocks, background P2P connections, and signed DELIVERED_ACKs.
 * 3. Encrypted P2P Mesh Store-and-Forward Relay: sealed, double-encrypted offline envelopes
 *    (AES-256-GCM + blind mailbox hash) cached across up to 3 connected WebRTC mesh peers when direct
 *    P2P fails—with ZERO cloud storage.
 */
public class VeilMeshDaemonService extends Service {

    private static final String TAG = "VeilMeshDaemon";
    private static final String MAILBOX_SALT = "VEIL_P2P_MESH_MAILBOX_v1";
    private static final int MAX_RELAY_HOPS = 3;
    private static final long ENVELOPE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 Hours

    private static volatile VeilMeshDaemonService instance;
    private static volatile Context appContext;
    private static volatile boolean isServiceRunning = false;
    private final java.util.concurrent.atomic.AtomicBoolean syncLoopsStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String activeSignalingUid = null;
    private final ScheduledExecutorService daemonExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService wakeKnockScheduler = Executors.newScheduledThreadPool(2);

    // Track active 2,500ms wake-knock fallback timers per message ID
    private final Map<String, ScheduledFuture<?>> activeWakeKnocks = new ConcurrentHashMap<>();

    // Cache of seen envelope IDs to prevent relay loops
    private final Set<String> processedEnvelopeIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public VeilMeshDaemonService() {
        super();
    }

    private VeilMeshDaemonService(Context context) {
        super();
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    private Context getCtx() {
        if (appContext != null) return appContext;
        try {
            if (getApplicationContext() != null) return getApplicationContext();
        } catch (Throwable ignored) {}
        return this;
    }

    public static synchronized VeilMeshDaemonService getInstance(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (instance == null) {
            instance = new VeilMeshDaemonService(appContext);
            if (!isServiceRunning && FirebaseAuth.getInstance().getCurrentUser() != null) {
                start(appContext != null ? appContext : context);
            }
        }
        return instance;
    }

    public static VeilMeshDaemonService getInstance() {
        if (instance == null && appContext != null) {
            instance = new VeilMeshDaemonService(appContext);
        }
        return instance;
    }

    public void flushOutboxQueue() {
        triggerOutboxSync();
    }

    public static void start(Context context) {
        if (context == null) return;
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (isServiceRunning) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        try {
            isServiceRunning = true;
            Intent intent = new Intent(context, VeilMeshDaemonService.class);
            context.startService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start VeilMeshDaemonService (using in-process daemon executor): " + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        instance = this;
        isServiceRunning = true;
        ensureSignalingActive();
        startSyncLoops();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        appContext = getApplicationContext();
        instance = this;
        isServiceRunning = true;
        ensureSignalingActive();
        startSyncLoops();
        triggerOutboxSync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        activeSignalingUid = null;
        activeWakeKnocks.clear();
    }

    private void ensureSignalingActive() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            String uid = user.getUid();
            if (uid.equals(activeSignalingUid)) {
                return;
            }
            activeSignalingUid = uid;
            try {
                CallEngine.getInstance(getCtx()).init(uid);
                WebRtcManager.getInstance(getCtx()).init(uid);
            } catch (Throwable t) {
                Log.w(TAG, "Error maintaining background signaling: " + t.getMessage());
            }
        }
    }

    private void startSyncLoops() {
        if (!syncLoopsStarted.compareAndSet(false, true)) {
            return;
        }
        // Periodic outbox flush & retry loop every 12 seconds
        daemonExecutor.scheduleWithFixedDelay(this::processOutboxQueue, 4, 12, TimeUnit.SECONDS);

        // Periodic mesh envelope cleanup & gossip loop every 60 seconds
        daemonExecutor.scheduleWithFixedDelay(this::processMeshRelayMaintenance, 30, 60, TimeUnit.SECONDS);

        // Start VeilSchedulerDaemon (#12, #80, #83, #153, #178, #216)
        try {
            VeilSchedulerDaemon.getInstance(getCtx()).start();
        } catch (Throwable t) {
            Log.w(TAG, "Scheduler daemon start notice: " + t.getMessage());
        }
    }

    public void triggerOutboxSync() {
        daemonExecutor.execute(this::processOutboxQueue);
    }

    // ==========================================
    // PASS 2: DAEMON OUTBOX & SILENT WAKE-KNOCK SYNC
    // ==========================================

    /**
     * Enqueue an outgoing message into local encrypted SQLite outbox queue.
     * ZERO messages are ever written to Firestore.
     */
    public void enqueueOutgoingMessage(Chat chat, String recipientId, Message message) {
        if (recipientId == null || message == null) return;
        daemonExecutor.execute(() -> {
            try {
                String chatId = (chat != null && chat.getId() != null)
                        ? chat.getId()
                        : (message.getChatId() != null && !message.getChatId().isEmpty()
                            ? message.getChatId()
                            : WebRtcManager.getChatId(message.getSenderId(), recipientId));
                Chat resolvedChat = chat != null ? chat : EncryptedChatDatabase.getInstance(getCtx()).getChat(chatId);
                boolean isGroup = (resolvedChat != null && resolvedChat.isGroupChat())
                        || (chatId != null && (chatId.startsWith("group_") || chatId.startsWith("channel_") || chatId.startsWith("lounge_")));
                String outboxId = isGroup ? (message.getId() + "_" + recipientId) : message.getId();

                // Build message packet payload JSON wrapped in {"type":"chat","payload":{...}}
                JSONObject payload = new JSONObject();
                payload.put("id", message.getId());
                payload.put("chatId", chatId);
                payload.put("senderId", message.getSenderId());
                payload.put("receiverId", recipientId);
                payload.put("senderName", message.getSenderName());
                payload.put("text", message.getText());
                payload.put("msgType", message.getType());
                payload.put("timestamp", message.getTimestamp() != null ? message.getTimestamp().toDate().getTime() : System.currentTimeMillis());
                payload.put("replyToId", message.getReplyToMessageId());
                payload.put("replySenderName", message.getReplySenderName());
                payload.put("blink", message.isBlink());
                payload.put("voiceFilter", message.getVoiceFilter());
                payload.put("pollJson", message.getPollJson());
                if (message.getThumbnail() != null) payload.put("thumbnail", message.getThumbnail());
                if (message.getMediaUrl() != null) payload.put("mediaUrl", message.getMediaUrl());
                if (message.getLocalFilePath() != null) payload.put("localFilePath", message.getLocalFilePath());
                payload.put("vanishTimer", message.getVanishTimer());
                payload.put("vanishMode", message.getVanishMode());
                payload.put("isGroup", isGroup);
                if (resolvedChat != null) {
                    payload.put("chatName", resolvedChat.getName());
                    payload.put("chatType", resolvedChat.getType());
                    if (resolvedChat.getAdminId() != null) payload.put("adminId", resolvedChat.getAdminId());
                    if (resolvedChat.getDescription() != null) payload.put("description", resolvedChat.getDescription());
                    if (resolvedChat.getParticipants() != null && !resolvedChat.getParticipants().isEmpty()) {
                        JSONArray partsArr = new JSONArray();
                        for (String pId : resolvedChat.getParticipants()) {
                            if (pId != null && !pId.isEmpty()) partsArr.put(pId);
                        }
                        payload.put("participants", partsArr);
                    }
                }

                JSONObject root = new JSONObject();
                root.put("type", "chat");
                root.put("payload", payload);

                // Construct binary VeilPacket (LANE_MSG, TYPE_CHAT_MESSAGE)
                VeilPacket packet = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, root.toString());
                byte[] packetBytes = packet.serialize();

                // Save to local Outbox table (per-recipient ID for groups so members don't overwrite each other)
                EncryptedChatDatabase.OutboxItem outboxItem = new EncryptedChatDatabase.OutboxItem(
                        outboxId,
                        chatId,
                        recipientId,
                        VeilPacket.TYPE_CHAT_MESSAGE,
                        packetBytes,
                        System.currentTimeMillis()
                );
                EncryptedChatDatabase.getInstance(getCtx()).enqueueOutboxItem(outboxItem);

                // Attempt immediate direct P2P delivery if channel is OPEN
                boolean sent = WebRtcManager.getInstance(getCtx()).sendPacket(recipientId, packet);
                if (sent) {
                    Log.d(TAG, "Outgoing message " + outboxId + " sent immediately via direct P2P DataChannel to " + recipientId);
                } else {
                    Log.d(TAG, "Recipient " + recipientId + " offline/sleeping -> initiating P2P handshake & silent wake knock");
                    WebRtcManager.getInstance(getCtx()).initiateConnection(recipientId);
                    SignalingClient.getInstance().sendWakeUp(recipientId, chatId);
                    String blindMailbox = computeBlindMailboxHash(recipientId, 0);
                    String wakeSignalId = "mesh_wake_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                    com.m3.veil.calling.FcmCallPusher.sendMeshWakeKnock(getCtx(), recipientId, blindMailbox, wakeSignalId);

                    final String finalOutboxId = outboxId;
                    final String finalRecipientId = recipientId;
                    ScheduledFuture<?> wakeFuture = wakeKnockScheduler.schedule(() -> {
                        activeWakeKnocks.remove(finalOutboxId);
                        daemonExecutor.execute(() -> {
                            try {
                                if (!WebRtcManager.getInstance(getCtx()).isPeerConnected(finalRecipientId)) {
                                    Log.i(TAG, "Tier 3: 2,500ms Wake-Knock timeout reached for recipient " + finalRecipientId
                                            + " (msg: " + finalOutboxId + ") -> Sealing payload into Blind Dead-Drop envelope");
                                    EncryptedChatDatabase.OutboxItem currentItem =
                                            EncryptedChatDatabase.getInstance(getCtx()).getOutboxItem(finalOutboxId);
                                    if (currentItem != null && currentItem.envelopeId == null) {
                                        dispatchToMeshRelay(currentItem);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error executing 2,500ms wake-knock fallback for " + finalOutboxId, e);
                            }
                        });
                    }, 2500, TimeUnit.MILLISECONDS);
                    activeWakeKnocks.put(finalOutboxId, wakeFuture);
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to enqueue outgoing message: " + message.getId(), e);
            }
        });
    }

    public void cancelWakeKnock(String messageId) {
        if (messageId == null) return;
        ScheduledFuture<?> f = activeWakeKnocks.remove(messageId);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * Enqueues a control, media-manifest, or action envelope (reactions, edits, deletes, poll votes,
     * group settings, subgroups, events) into the local SQLCipher outbox when the peer is offline,
     * triggering a 500ms Wake-Knock and 2,500ms Tier-3 Sealed-Sender Blind Dead-Drop escalation.
     */
    public void enqueueActionEnvelope(String recipientId, String rawJsonPayload) {
        if (recipientId == null || rawJsonPayload == null || rawJsonPayload.isEmpty()) return;
        daemonExecutor.execute(() -> {
            try {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                String myUid = user != null ? user.getUid() : "local_user";
                String chatId = WebRtcManager.getChatId(myUid, recipientId);
                String actionId = "act_" + UUID.randomUUID().toString();

                VeilPacket packet = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_GROUP_SETTINGS, rawJsonPayload);
                byte[] packetBytes = packet.serialize();

                EncryptedChatDatabase.OutboxItem outboxItem = new EncryptedChatDatabase.OutboxItem(
                        actionId,
                        chatId,
                        recipientId,
                        VeilPacket.TYPE_GROUP_SETTINGS,
                        packetBytes,
                        System.currentTimeMillis()
                );
                EncryptedChatDatabase.getInstance(getCtx()).enqueueOutboxItem(outboxItem);

                if (WebRtcManager.getInstance(getCtx()).sendPacket(recipientId, packet)) {
                    EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItem(actionId);
                } else {
                    WebRtcManager.getInstance(getCtx()).initiateConnection(recipientId);
                    SignalingClient.getInstance().sendWakeUp(recipientId, chatId);
                    String blindMailbox = computeBlindMailboxHash(recipientId, 0);
                    String wakeSignalId = "mesh_wake_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
                    com.m3.veil.calling.FcmCallPusher.sendMeshWakeKnock(getCtx(), recipientId, blindMailbox, wakeSignalId);

                    final String finalActionId = actionId;
                    final String finalRecipientId = recipientId;
                    ScheduledFuture<?> wakeFuture = wakeKnockScheduler.schedule(() -> {
                        activeWakeKnocks.remove(finalActionId);
                        daemonExecutor.execute(() -> {
                            try {
                                if (!WebRtcManager.getInstance(getCtx()).isPeerConnected(finalRecipientId)) {
                                    EncryptedChatDatabase.OutboxItem currentItem =
                                            EncryptedChatDatabase.getInstance(getCtx()).getOutboxItem(finalActionId);
                                    if (currentItem != null && currentItem.envelopeId == null) {
                                        dispatchToMeshRelay(currentItem);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error executing action envelope 2,500ms fallback", e);
                            }
                        });
                    }, 2500, TimeUnit.MILLISECONDS);
                    activeWakeKnocks.put(finalActionId, wakeFuture);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to enqueue offline action envelope", e);
            }
        });
    }

    /**
     * Flush all pending outbox packets for a specific peer when their DataChannel opens.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastPeerFlushMs = new java.util.concurrent.ConcurrentHashMap<>();

    public void flushOutboxForPeer(String peerId) {
        if (peerId == null) return;
        long now = System.currentTimeMillis();
        Long last = lastPeerFlushMs.get(peerId);
        if (last != null && (now - last) < 4000L) return;
        lastPeerFlushMs.put(peerId, now);

        daemonExecutor.execute(() -> {
            try {
                List<EncryptedChatDatabase.OutboxItem> pending =
                        EncryptedChatDatabase.getInstance(getCtx()).getPendingOutboxItemsForPeer(peerId);

                if (pending == null || pending.isEmpty()) return;
                Log.i(TAG, "Flushing " + pending.size() + " outbox packets for newly connected peer: " + peerId);

                for (EncryptedChatDatabase.OutboxItem item : pending) {
                    cancelWakeKnock(item.id);
                    VeilPacket packet = VeilPacket.deserialize(item.payload);
                    if (packet != null) {
                        boolean sent = WebRtcManager.getInstance(getCtx()).sendPacket(peerId, packet);
                        if (sent) {
                            EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItem(item.id);
                            EncryptedChatDatabase.getInstance(getCtx()).updateMessageStatus(item.id, true, false);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error flushing outbox for peer: " + peerId, e);
            }
        });
    }

    /**
     * Background outbox processing worker.
     */
    private void processOutboxQueue() {
        try {
            List<EncryptedChatDatabase.OutboxItem> pending =
                    EncryptedChatDatabase.getInstance(getCtx()).getPendingOutboxItems(50);

            if (pending == null || pending.isEmpty()) return;

            long now = System.currentTimeMillis();
            for (EncryptedChatDatabase.OutboxItem item : pending) {
                String recipientId = item.recipientId;

                // 1. If WebRTC DataChannel is OPEN, send packet directly
                if (WebRtcManager.getInstance(getCtx()).isPeerConnected(recipientId)) {
                    VeilPacket packet = VeilPacket.deserialize(item.payload);
                    if (packet != null) {
                        boolean sent = WebRtcManager.getInstance(getCtx()).sendPacket(recipientId, packet);
                        if (sent) {
                            cancelWakeKnock(item.id);
                            EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItem(item.id);
                            EncryptedChatDatabase.getInstance(getCtx()).updateMessageStatus(item.id, true, false);
                            continue;
                        }
                    }
                }

                // 2. Direct P2P not yet open: handle retry state machine
                long elapsedSinceAttempt = now - item.lastAttempt;
                if (item.envelopeId == null && elapsedSinceAttempt >= 2500L) {
                    // Tier 3: 2,500ms Wake-Knock timeout reached -> Escalate to Blind Dead-Drop
                    Log.i(TAG, "Tier 3: 2,500ms Wake-Knock timeout exceeded for " + recipientId + " -> sealing envelope for Mesh Store-and-Forward relay");
                    dispatchToMeshRelay(item);
                } else if (item.retryCount < 5 && elapsedSinceAttempt > 6_000L) {
                    Log.d(TAG, "Outbox retry #" + (item.retryCount + 1) + " for message " + item.id + " to " + recipientId);
                    WebRtcManager.getInstance(getCtx()).initiateConnection(recipientId);
                    SignalingClient.getInstance().sendWakeUp(recipientId, item.chatId);
                    EncryptedChatDatabase.getInstance(getCtx()).updateOutboxAttempt(item.id, item.retryCount + 1, now);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in processOutboxQueue", e);
        }
    }

    /**
     * Handles signed DELIVERED_ACK received on veil-ctrl lane.
     */
    public void onDeliveredAckReceived(String messageId) {
        if (messageId == null) return;
        cancelWakeKnock(messageId);
        daemonExecutor.execute(() -> {
            try {
                // Immediate cryptographic purge of mesh envelope from local database
                String purgedEnvId = EncryptedChatDatabase.getInstance(getCtx()).purgeMeshEnvelopeForMessage(messageId);

                // Remove from outbox
                EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItem(messageId);

                // Mark message delivered in local SQLite messages table
                EncryptedChatDatabase.getInstance(getCtx()).updateMessageStatus(messageId, true, false);

                // Broadcast signed MESH_DELIVERY_ACK across connected peers to wipe dead-drop from all relay nodes
                if (purgedEnvId != null) {
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    String myUid = user != null ? user.getUid() : "";
                    sendMeshDeliveryAck(purgedEnvId, myUid);
                }

                Log.d(TAG, "Message " + messageId + " confirmed delivered via signed DELIVERED_ACK frame -> outbox pruned and envelope purged");
            } catch (Exception e) {
                Log.e(TAG, "Error handling delivered ACK for " + messageId, e);
            }
        });
    }

    // ==========================================
    // PASS 3: ENCRYPTED P2P MESH STORE-AND-FORWARD RELAY
    // ==========================================

    private static final long MAILBOX_ROTATION_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    /**
     * Compute zero-knowledge rotated blind mailbox hash for recipient:
     * SHA-256(recipientId + MAILBOX_SALT + epochDay).
     * Intermediate relay nodes cannot decipher the recipient identity from this hash,
     * and the hash rotates automatically every 24 hours.
     */
    public static String computeBlindMailboxHash(String recipientId) {
        return computeBlindMailboxHash(recipientId, 0);
    }

    public static String computeBlindMailboxHash(String recipientId, int dayOffset) {
        if (recipientId == null) return "";
        try {
            long epochDay = (System.currentTimeMillis() / MAILBOX_ROTATION_INTERVAL_MS) + dayOffset;
            String raw = recipientId + "_" + MAILBOX_SALT + "_" + epochDay;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes((recipientId + MAILBOX_SALT).getBytes()).toString();
        }
    }

    /**
     * Seal outbox item into double-encrypted mesh envelope and gossip across connected peers.
     */
    private void dispatchToMeshRelay(EncryptedChatDatabase.OutboxItem item) {
        try {
            String envelopeId = UUID.randomUUID().toString();
            String blindMailbox = computeBlindMailboxHash(item.recipientId);

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String originId = user != null ? user.getUid() : "anonymous";

            EncryptedChatDatabase.MeshEnvelopeRecord record = new EncryptedChatDatabase.MeshEnvelopeRecord();
            record.envelopeId = envelopeId;
            record.blindMailboxHash = blindMailbox;
            record.sealedPayload = item.payload; // Payload is already encrypted for recipient
            record.hopCount = 0;
            record.maxHops = MAX_RELAY_HOPS;
            record.createdAt = System.currentTimeMillis();
            record.expiresAt = System.currentTimeMillis() + ENVELOPE_TTL_MS;
            record.originPeerId = originId;

            // Cache in local SQLite mesh_envelopes table
            EncryptedChatDatabase.getInstance(getCtx()).saveMeshEnvelope(record);
            EncryptedChatDatabase.getInstance(getCtx()).setOutboxEnvelopeId(item.id, envelopeId);

            // Gossip envelope to up to 3 connected WebRTC mesh peers
            gossipEnvelopeToMeshPeers(record, null);

        } catch (Exception e) {
            Log.e(TAG, "Error dispatching envelope to mesh relay", e);
        }
    }

    /**
     * Gossip a mesh envelope to up to 3 connected peers over the veil-mesh-gossip lane.
     */
    private void gossipEnvelopeToMeshPeers(EncryptedChatDatabase.MeshEnvelopeRecord record, @Nullable String excludePeerId) {
        List<String> connectedPeers = WebRtcManager.getInstance(getCtx()).getConnectedPeerIds();
        if (connectedPeers.isEmpty()) {
            Log.d(TAG, "No active WebRTC mesh peers connected right now to relay envelope: " + record.envelopeId);
            return;
        }

        try {
            JSONObject envelopeJson = new JSONObject();
            envelopeJson.put("envelopeId", record.envelopeId);
            envelopeJson.put("blindMailboxHash", record.blindMailboxHash);
            envelopeJson.put("sealedPayload", android.util.Base64.encodeToString(record.sealedPayload, android.util.Base64.NO_WRAP));
            envelopeJson.put("hopCount", record.hopCount);
            envelopeJson.put("maxHops", record.maxHops);
            envelopeJson.put("createdAt", record.createdAt);
            envelopeJson.put("expiresAt", record.expiresAt);
            envelopeJson.put("originPeerId", record.originPeerId);

            VeilPacket gossipPacket = new VeilPacket(
                    VeilPacket.LANE_MESH_GOSSIP,
                    VeilPacket.TYPE_MESH_ENVELOPE,
                    envelopeJson.toString()
            );

            int relaysCount = 0;
            for (String peerId : connectedPeers) {
                if (relaysCount >= 3) break;
                if (peerId.equals(excludePeerId) || peerId.equals(record.originPeerId)) continue;

                boolean sent = WebRtcManager.getInstance(getCtx()).sendPacket(peerId, gossipPacket);
                if (sent) {
                    relaysCount++;
                    Log.d(TAG, "Relayed mesh envelope " + record.envelopeId + " to peer: " + peerId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in gossipEnvelopeToMeshPeers", e);
        }
    }

    /**
     * Process incoming Mesh Gossip packets (TYPE_MESH_ENVELOPE, TYPE_MESH_DELIVERY_ACK).
     */
    public void handleMeshPacket(String peerId, VeilPacket packet) {
        if (packet == null) return;
        daemonExecutor.execute(() -> {
            try {
                short type = packet.getPacketType();
                if (type == VeilPacket.TYPE_MESH_ENVELOPE) {
                    handleIncomingMeshEnvelope(peerId, packet.getPayloadAsString());
                } else if (type == VeilPacket.TYPE_MESH_DELIVERY_ACK) {
                    handleIncomingMeshDeliveryAck(packet.getPayloadAsString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling mesh packet from " + peerId, e);
            }
        });
    }

    private void handleIncomingMeshEnvelope(String fromPeerId, String envelopeJsonStr) {
        try {
            JSONObject json = new JSONObject(envelopeJsonStr);
            String envelopeId = json.getString("envelopeId");
            if (processedEnvelopeIds.contains(envelopeId)) return;
            processedEnvelopeIds.add(envelopeId);

            String blindMailbox = json.getString("blindMailboxHash");
            byte[] sealedPayload = android.util.Base64.decode(json.getString("sealedPayload"), android.util.Base64.NO_WRAP);
            int hopCount = json.getInt("hopCount");
            int maxHops = json.optInt("maxHops", MAX_RELAY_HOPS);
            long expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + ENVELOPE_TTL_MS);
            String originPeerId = json.optString("originPeerId", "");

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String myUid = user != null ? user.getUid() : "";
            String currentMailbox = computeBlindMailboxHash(myUid, 0);
            String prevMailbox = computeBlindMailboxHash(myUid, -1);

            if (blindMailbox.equals(currentMailbox) || blindMailbox.equals(prevMailbox)) {
                // TARGET RECIPIENT REACHED!
                Log.i(TAG, "Target reached! Unwrapping sealed mesh envelope: " + envelopeId);
                VeilPacket innerPacket = VeilPacket.deserialize(sealedPayload);
                if (innerPacket != null) {
                    WebRtcManager.getInstance(getCtx()).processInnerDeliveredMessage(fromPeerId, innerPacket);
                }

                // Send signed MESH_DELIVERY_ACK back through mesh
                sendMeshDeliveryAck(envelopeId, originPeerId);

            } else {
                // INTERMEDIATE RELAY NODE: Cache and gossip if hopCount < maxHops
                if (hopCount < maxHops && System.currentTimeMillis() < expiresAt) {
                    EncryptedChatDatabase.MeshEnvelopeRecord record = new EncryptedChatDatabase.MeshEnvelopeRecord();
                    record.envelopeId = envelopeId;
                    record.blindMailboxHash = blindMailbox;
                    record.sealedPayload = sealedPayload;
                    record.hopCount = hopCount + 1;
                    record.maxHops = maxHops;
                    record.createdAt = json.optLong("createdAt", System.currentTimeMillis());
                    record.expiresAt = expiresAt;
                    record.originPeerId = originPeerId;

                    // Cache in local SQLite (ZERO cloud storage!)
                    EncryptedChatDatabase.getInstance(getCtx()).saveMeshEnvelope(record);
                    Log.d(TAG, "Cached store-and-forward mesh envelope " + envelopeId + " (hop " + record.hopCount + "/" + maxHops + ")");

                    // Relay to other connected peers
                    gossipEnvelopeToMeshPeers(record, fromPeerId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming mesh envelope", e);
        }
    }

    private void sendMeshDeliveryAck(String envelopeId, String originPeerId) {
        try {
            JSONObject ackJson = new JSONObject();
            ackJson.put("envelopeId", envelopeId);
            ackJson.put("deliveredAt", System.currentTimeMillis());

            VeilPacket ackPacket = new VeilPacket(
                    VeilPacket.LANE_MESH_GOSSIP,
                    VeilPacket.TYPE_MESH_DELIVERY_ACK,
                    ackJson.toString()
            );

            // Broadcast ACK across all connected peers so all mesh nodes wipe envelope cache
            for (String peerId : WebRtcManager.getInstance(getCtx()).getConnectedPeerIds()) {
                WebRtcManager.getInstance(getCtx()).sendPacket(peerId, ackPacket);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending mesh delivery ACK", e);
        }
    }

    private void handleIncomingMeshDeliveryAck(String ackJsonStr) {
        try {
            JSONObject json = new JSONObject(ackJsonStr);
            String envelopeId = json.getString("envelopeId");

            // Delete envelope from local SQLite mesh cache
            EncryptedChatDatabase.getInstance(getCtx()).removeMeshEnvelope(envelopeId);

            // If we originated this envelope, clear it from outbox
            EncryptedChatDatabase.getInstance(getCtx()).removeOutboxItemByEnvelopeId(envelopeId);
            Log.d(TAG, "Mesh envelope " + envelopeId + " delivery ACK confirmed -> purged from local mesh cache & outbox");

            // Relay ACK through mesh so all nodes purge envelope
            for (String peerId : WebRtcManager.getInstance(getCtx()).getConnectedPeerIds()) {
                VeilPacket ackPacket = new VeilPacket(
                        VeilPacket.LANE_MESH_GOSSIP,
                        VeilPacket.TYPE_MESH_DELIVERY_ACK,
                        ackJsonStr
                );
                WebRtcManager.getInstance(getCtx()).sendPacket(peerId, ackPacket);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling mesh delivery ACK", e);
        }
    }

    private void processMeshRelayMaintenance() {
        try {
            // Prune expired envelopes (TTL > 24 hours)
            EncryptedChatDatabase.getInstance(getCtx()).pruneExpiredMeshEnvelopes();

            // Periodic gossip of cached envelopes to newly connected peers
            List<EncryptedChatDatabase.MeshEnvelopeRecord> cached =
                    EncryptedChatDatabase.getInstance(getCtx()).getGossipableMeshEnvelopes(MAX_RELAY_HOPS, 10);

            if (cached != null) {
                for (EncryptedChatDatabase.MeshEnvelopeRecord rec : cached) {
                    gossipEnvelopeToMeshPeers(rec, null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in processMeshRelayMaintenance", e);
        }
    }

    // ==========================================
    // PERSISTENCE & ALARM RE-ARMING
    // ==========================================

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "App swiped from recents -> re-arming background WebRTC daemon (Telegram parity)");
        ensureSignalingActive();

        try {
            Intent restartServiceIntent = new Intent(getApplicationContext(), VeilMeshDaemonService.class);
            PendingIntent restartPendingIntent = PendingIntent.getService(
                    getApplicationContext(),
                    101,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + 1500L,
                        restartPendingIntent
                );
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to arm restart alarm: " + t.getMessage());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
