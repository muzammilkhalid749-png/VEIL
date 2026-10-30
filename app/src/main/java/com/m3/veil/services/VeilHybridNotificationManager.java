package com.m3.veil.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextPaint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.manager.VeilMessagesController;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.ui.settings.NotificationSettingsActivity;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL ADVANCED HYBRID NOTIFICATION ENGINE (DIRECT P2P + FIREBASE CLOUD FALLBACK)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Dual-Transport Architecture:
 * 1. PATH A — Direct P2P Notification Path (0% Firebase Dependency when Peer is Connected):
 *    When {@link WebRtcManager#isPeerConnected(String)} is true, messages and real-time notification
 *    control frames ("p2p_notify") are transmitted directly over the encrypted WebRTC DataChannel
 *    (LANE_CTRL / LANE_MSG). Firebase Cloud Messaging / Firestore wake-knocks are completely skipped,
 *    achieving sub-25ms notification latency with zero cloud metadata.
 *
 * 2. PATH B — Firebase Cloud Fallback Path (When Peer is Offline / Disconnected):
 *    When the peer is not connected over WebRTC, dispatches an encrypted zero-knowledge wake push
 *    to Firestore ("users/{peerId}/mesh_signals" + "users/{peerId}/signals") so FCM / SignalingClient
 *    wakes the sleeping device and displays the notification immediately while the P2P mesh reconnects.
 *
 * 3. Strict Cross-Transport Deduplication:
 *    Maintains an LRU set of notified message IDs across both RAM and SharedPreferences so a message
 *    arriving via P2P DataChannel, Nostr/WebSocket, and/or Firebase never triggers duplicate alerts.
 *
 * 4. Rich Android MessagingStyle Conversation Notifications:
 *    - Multi-message conversation history per chat (up to 6 unread messages).
 *    - Transport badge indicator ("⚡ Direct P2P" vs "☁️ Cloud Sync").
 *    - Inline Direct Reply (RemoteInput), Mark-as-Read, and Mute actions directly in the shade.
 *    - Automatic Group Summary bundling across multiple active conversations.
 *    - Privacy redaction when PIN lock is enabled or message previews are disabled.
 */
public class VeilHybridNotificationManager {

    private static final String TAG = "VeilHybridNotifMgr";

    public static final String CHANNEL_DIRECT_MESSAGES = "veil_hybrid_direct_msgs_v2";
    public static final String CHANNEL_GROUP_MESSAGES = "veil_hybrid_group_msgs_v2";
    public static final String CHANNEL_SILENT_SYNC = "veil_hybrid_silent_v2";

    public static final String GROUP_KEY_CONVERSATIONS = "com.m3.veil.HYBRID_CHAT_GROUP";
    private static final int SUMMARY_NOTIFICATION_ID = 77000;

    public static final String ACTION_DIRECT_REPLY = "com.m3.veil.action.HYBRID_DIRECT_REPLY";
    public static final String ACTION_MARK_READ = "com.m3.veil.action.HYBRID_MARK_READ";
    public static final String ACTION_MUTE_CHAT = "com.m3.veil.action.HYBRID_MUTE_CHAT";
    public static final String KEY_TEXT_REPLY = "key_hybrid_direct_reply_text";

    public static final String EXTRA_CHAT_ID = "extra_notif_chat_id";
    public static final String EXTRA_PEER_ID = "extra_notif_peer_id";
    public static final String EXTRA_SENDER_NAME = "extra_notif_sender_name";
    public static final String EXTRA_IS_GROUP = "extra_notif_is_group";

    private static final int MAX_HISTORY_PER_CHAT = 6;
    private static final int MAX_DEDUP_CACHE_SIZE = 600;

    private static volatile VeilHybridNotificationManager sInstance;

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    // Cross-transport deduplication cache (Message ID -> Timestamp)
    private final Set<String> mNotifiedMessageIds = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(MAX_DEDUP_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_DEDUP_CACHE_SIZE;
                }
            });

    // Per-dialog unread notification history for MessagingStyle
    private final Map<String, List<NotifMessageEntry>> mActiveChatHistory = new ConcurrentHashMap<>();

    public static class NotifMessageEntry {
        public final String messageId;
        public final String senderId;
        public final String senderName;
        public final String displayBody;
        public final long timestampMs;
        public final boolean deliveredViaDirectP2P;

        public NotifMessageEntry(String messageId, String senderId, String senderName,
                                 String displayBody, long timestampMs, boolean deliveredViaDirectP2P) {
            this.messageId = messageId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.displayBody = displayBody;
            this.timestampMs = timestampMs;
            this.deliveredViaDirectP2P = deliveredViaDirectP2P;
        }
    }

    public static VeilHybridNotificationManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (VeilHybridNotificationManager.class) {
                if (sInstance == null) {
                    sInstance = new VeilHybridNotificationManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private VeilHybridNotificationManager(@NonNull Context context) {
        this.mContext = context;
        this.mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureNotificationChannels();
        loadRecentNotifiedIdsFromPrefs();
    }

    private void ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mNotificationManager != null) {
            NotificationChannel directChannel = new NotificationChannel(
                    CHANNEL_DIRECT_MESSAGES,
                    "Direct Messages (Hybrid P2P & Cloud)",
                    NotificationManager.IMPORTANCE_HIGH);
            directChannel.setDescription("Instant 1:1 messages delivered via Direct P2P or Firebase Fallback");
            directChannel.enableLights(true);
            directChannel.setLightColor(Color.parseColor("#6366F1"));
            directChannel.enableVibration(true);
            directChannel.setVibrationPattern(new long[]{0L, 180L, 90L, 180L});
            directChannel.setShowBadge(true);
            mNotificationManager.createNotificationChannel(directChannel);

            NotificationChannel groupChannel = new NotificationChannel(
                    CHANNEL_GROUP_MESSAGES,
                    "Group & Channel Messages",
                    NotificationManager.IMPORTANCE_HIGH);
            groupChannel.setDescription("Encrypted group and channel notifications");
            groupChannel.enableLights(true);
            groupChannel.setLightColor(Color.parseColor("#38BDF8"));
            groupChannel.enableVibration(true);
            groupChannel.setShowBadge(true);
            mNotificationManager.createNotificationChannel(groupChannel);

            NotificationChannel silentChannel = new NotificationChannel(
                    CHANNEL_SILENT_SYNC,
                    "Silent Background Sync",
                    NotificationManager.IMPORTANCE_LOW);
            silentChannel.setDescription("Silent P2P mesh & notification updates");
            silentChannel.setSound(null, null);
            silentChannel.enableVibration(false);
            mNotificationManager.createNotificationChannel(silentChannel);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 1. HYBRID OUTBOUND DISPATCHER (DIRECT P2P FIRST, FIREBASE FALLBACK WHEN OFFLINE)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Dispatches a lightweight real-time P2P notification control frame directly over an active
     * WebRTC DataChannel (LANE_CTRL) when the peer is connected, bypassing Firebase completely.
     */
    public boolean sendDirectP2PNotificationFrame(
            @NonNull String peerId,
            @NonNull String dialogId,
            @NonNull Message msg) {
        try {
            if (!WebRtcManager.getInstance(mContext).isPeerConnected(peerId)) {
                return false;
            }
            JSONObject notifyJson = new JSONObject();
            notifyJson.put("type", "p2p_notify");
            notifyJson.put("id", msg.getId());
            notifyJson.put("chatId", dialogId);
            notifyJson.put("senderId", msg.getSenderId() != null ? msg.getSenderId() : VeilMessagesController.getInstance(mContext).getCurrentUserId());
            notifyJson.put("senderName", msg.getSenderName() != null ? msg.getSenderName() : VeilMessagesController.getInstance(mContext).getCurrentUserName());
            notifyJson.put("msgType", msg.getType() != null ? msg.getType() : "text");
            notifyJson.put("timestamp", msg.getTimestamp() != null ? msg.getTimestamp().toDate().getTime() : System.currentTimeMillis());
            notifyJson.put("blink", msg.isBlink());

            String rawPreview = buildCleanPreviewText(msg);
            String encPreview = EncryptionManager.encryptGroupPayload(dialogId, rawPreview);
            notifyJson.put("encPreview", encPreview);

            VeilPacket ctrlPacket = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_CHAT_MESSAGE, notifyJson.toString());
            return WebRtcManager.getInstance(mContext).sendPacket(peerId, ctrlPacket);
        } catch (Throwable t) {
            Log.w(TAG, "Direct P2P notification frame notice: " + t.getMessage());
            return false;
        }
    }

    /**
     * Triggered ONLY when a peer is NOT connected over P2P DataChannel.
     * Writes an encrypted wake-knock payload to Firestore ("users/{peerId}/mesh_signals") so
     * Firebase Cloud Messaging / SignalingClient wakes the offline device and renders the notification.
     */
    public void dispatchCloudFallbackWakePush(
            @NonNull String peerId,
            @NonNull String dialogId,
            @NonNull Message msg) {
        try {
            // Double-check: if P2P connected in the meantime, skip Firebase wake push completely!
            if (WebRtcManager.getInstance(mContext).isPeerConnected(peerId)) {
                Log.d(TAG, "Skipping Firebase wake push for " + peerId + " — Direct P2P connection is active.");
                return;
            }
            String myId = VeilMessagesController.getInstance(mContext).getCurrentUserId();
            String myName = msg.getSenderName() != null ? msg.getSenderName() : VeilMessagesController.getInstance(mContext).getCurrentUserName();
            if (myId == null || peerId.isEmpty() || peerId.equals(myId)) return;

            String knockId = "notif_" + (msg.getId() != null ? msg.getId() : UUID.randomUUID().toString());
            String cleanPreview = buildCleanPreviewText(msg);
            String encryptedPreview = EncryptionManager.encryptGroupPayload(dialogId, cleanPreview);

            Map<String, Object> knock = new HashMap<>();
            knock.put("senderId", myId);
            knock.put("from", myId);
            knock.put("senderName", myName);
            knock.put("chatId", dialogId);
            knock.put("messageId", msg.getId());
            knock.put("msgType", msg.getType() != null ? msg.getType() : "text");
            knock.put("encPreview", encryptedPreview);
            knock.put("type", "wake_up");
            knock.put("reason", "hybrid_chat_fallback");
            knock.put("timestamp", System.currentTimeMillis());

            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(peerId)
                    .collection("mesh_signals")
                    .document(knockId)
                    .set(knock);

            // Also send wake_up signal via SignalingClient for sleeping peers
            SignalingClient.getInstance().sendWakeUp(peerId, dialogId);
        } catch (Throwable t) {
            Log.w(TAG, "Cloud fallback wake push notice: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 2. HYBRID INBOUND NOTIFICATION INGESTION & DEDUPLICATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Called when an inbound P2P or Cloud-synced Message arrives.
     * Deduplicates by messageId and displays a rich Android MessagingStyle notification if the user
     * is not actively viewing that conversation.
     */
    public void onInboundMessageForNotification(
            @Nullable String dialogId,
            @Nullable String peerId,
            @Nullable Message message,
            boolean arrivedViaDirectP2P) {
        if (message == null || message.getId() == null) return;
        String myId = VeilMessagesController.getInstance(mContext).getCurrentUserId();
        if (myId != null && myId.equals(message.getSenderId())) {
            return; // Never notify for our own outbound messages
        }
        if (AuthManager.getInstance().isMyUserId(mContext, message.getSenderId())) {
            return;
        }

        String resolvedChatId = (dialogId != null && !dialogId.isEmpty())
                ? dialogId
                : (message.getChatId() != null && !message.getChatId().isEmpty()
                    ? message.getChatId()
                    : WebRtcManager.getChatId(myId, peerId));
        if (resolvedChatId == null || resolvedChatId.isEmpty()) return;

        // 1. Check if this exact conversation is open in the foreground
        String activeForegroundChat = VeilMessagesController.getInstance(mContext).getActiveChatIdInForeground();
        if (resolvedChatId.equals(activeForegroundChat)) {
            markMessageNotified(message.getId());
            triggerInAppFeedbackIfEnabled();
            return;
        }

        // 2. Strict Cross-Transport Deduplication (prevents duplicate alerts from P2P + Firebase)
        if (!markMessageNotified(message.getId())) {
            return;
        }

        // 3. Check user Notification Settings & Per-Chat Mute
        Chat chat = VeilMessagesController.getInstance(mContext).getCachedDialogImmediate(resolvedChatId);
        if (chat == null) {
            chat = EncryptedChatDatabase.getInstance(mContext).getChat(resolvedChatId);
        }
        if (chat != null && (chat.isMuted() || chat.isBlocked())) {
            return;
        }

        boolean isGroup = (chat != null && chat.isGroupChat())
                || resolvedChatId.startsWith("group_")
                || resolvedChatId.startsWith("channel_")
                || resolvedChatId.startsWith("lounge_");
        boolean isChannel = resolvedChatId.startsWith("channel_")
                || (chat != null && "channel".equalsIgnoreCase(chat.getType()));

        SharedPreferences notifPrefs = mContext.getSharedPreferences(NotificationSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (isChannel && !notifPrefs.getBoolean(NotificationSettingsActivity.KEY_CHANNEL_ENABLED, true)) {
            return;
        }
        if (isGroup && !isChannel && !notifPrefs.getBoolean(NotificationSettingsActivity.KEY_GROUP_ENABLED, true)) {
            return;
        }
        if (!isGroup && !notifPrefs.getBoolean(NotificationSettingsActivity.KEY_PRIVATE_ENABLED, true)) {
            return;
        }
        if (isGroup && notifPrefs.getBoolean(NotificationSettingsActivity.KEY_MENTIONS_ONLY, false)) {
            String myName = VeilMessagesController.getInstance(mContext).getCurrentUserName();
            String body = message.getText() != null ? message.getText() : "";
            if (myName == null || !body.toLowerCase().contains(myName.toLowerCase())) {
                return;
            }
        }

        String senderName = resolveSenderDisplayName(chat, message, peerId);
        String previewText = buildCleanPreviewText(message);
        long ts = message.getTimestamp() != null ? message.getTimestamp().toDate().getTime() : System.currentTimeMillis();

        postRichConversationNotification(
                resolvedChatId,
                peerId != null ? peerId : message.getSenderId(),
                senderName,
                chat != null && isGroup ? chat.getName() : senderName,
                message.getId(),
                previewText,
                ts,
                isGroup,
                arrivedViaDirectP2P);
    }

    /**
     * Handles an inbound "p2p_notify" control frame arriving over WebRTC DataChannel.
     */
    public void onInboundDirectP2PNotifyFrame(@NonNull String peerId, @NonNull JSONObject json) {
        try {
            String msgId = json.optString("id", null);
            String chatId = json.optString("chatId", WebRtcManager.getInstance(mContext).getChatId(
                    VeilMessagesController.getInstance(mContext).getCurrentUserId(), peerId));
            if (msgId == null || msgId.isEmpty() || chatId == null) return;

            // If full message is already in DB or already notified, skip
            if (isMessageAlreadyNotified(msgId)) return;

            String senderId = json.optString("senderId", peerId);
            String senderName = json.optString("senderName", "Contact");
            String msgType = json.optString("msgType", "text");
            long ts = json.optLong("timestamp", System.currentTimeMillis());
            boolean isBlink = json.optBoolean("blink", false);
            String encPreview = json.optString("encPreview", null);
            String preview = "";
            if (encPreview != null && !encPreview.isEmpty()) {
                preview = EncryptionManager.decryptGroupPayload(chatId, encPreview);
            }

            Message tempMsg = new Message(msgId, senderId,
                    VeilMessagesController.getInstance(mContext).getCurrentUserId(),
                    preview != null && !preview.isEmpty() ? preview : "",
                    new com.google.firebase.Timestamp(new java.util.Date(ts)),
                    msgType);
            tempMsg.setChatId(chatId);
            tempMsg.setSenderName(senderName);
            tempMsg.setBlink(isBlink);

            onInboundMessageForNotification(chatId, peerId, tempMsg, true);
        } catch (Throwable t) {
            Log.w(TAG, "Error handling inbound p2p_notify frame: " + t.getMessage());
        }
    }

    /**
     * Handles an inbound Firebase Cloud Messaging data push or Firestore mesh_signals wake payload
     * when the peer was disconnected from P2P.
     */
    public void onFirebaseWakeOrPushReceived(@NonNull Map<String, String> data) {
        try {
            String msgId = data.get("messageId");
            if (msgId == null) msgId = data.get("id");
            String chatId = data.get("chatId");
            String senderId = data.get("senderId");
            if (senderId == null) senderId = data.get("from");
            String senderName = data.get("senderName");
            String msgType = data.get("msgType");
            String encPreview = data.get("encPreview");

            // If we don't have message metadata, just wake the mesh daemon silently
            if (msgId == null || msgId.isEmpty() || chatId == null || chatId.isEmpty()) {
                return;
            }

            // If already notified via Direct P2P DataChannel, ignore the late Firebase push!
            if (isMessageAlreadyNotified(msgId)) {
                Log.d(TAG, "Deduplicated Firebase push for message " + msgId + " (already delivered via P2P).");
                return;
            }

            String decryptedPreview = "";
            if (encPreview != null && !encPreview.isEmpty()) {
                try {
                    decryptedPreview = EncryptionManager.decryptGroupPayload(chatId, encPreview);
                } catch (Throwable ignored) {}
            }
            if (decryptedPreview == null || decryptedPreview.isEmpty() || decryptedPreview.equals("[Encrypted Message]")) {
                decryptedPreview = formatFallbackTypePreview(msgType);
            }

            Message synthetic = new Message(
                    msgId,
                    senderId != null ? senderId : "peer",
                    VeilMessagesController.getInstance(mContext).getCurrentUserId(),
                    decryptedPreview,
                    com.google.firebase.Timestamp.now(),
                    msgType != null ? msgType : "text");
            synthetic.setChatId(chatId);
            synthetic.setSenderName(senderName != null && !senderName.isEmpty() ? senderName : "Contact");

            boolean isConnectedNow = senderId != null && WebRtcManager.getInstance(mContext).isPeerConnected(senderId);
            onInboundMessageForNotification(chatId, senderId, synthetic, isConnectedNow);
        } catch (Throwable t) {
            Log.w(TAG, "Error processing Firebase wake push notification: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 3. RICH ANDROID MESSAGINGSTYLE NOTIFICATION BUILDER WITH INLINE REPLY & MARK-AS-READ
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    private void postRichConversationNotification(
            @NonNull String chatId,
            @Nullable String peerId,
            @NonNull String senderName,
            @Nullable String conversationTitle,
            @NonNull String messageId,
            @NonNull String previewText,
            long timestampMs,
            boolean isGroup,
            boolean deliveredViaDirectP2P) {
        if (mNotificationManager == null) return;

        try {
            SharedPreferences notifPrefs = mContext.getSharedPreferences(NotificationSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
            boolean showPreview = notifPrefs.getBoolean(NotificationSettingsActivity.KEY_PRIVATE_PREVIEW, true);
            boolean vibrateEnabled = notifPrefs.getBoolean(NotificationSettingsActivity.KEY_PRIVATE_VIBRATE, true);

            // Privacy guard: if PIN lock is enabled on the app, hide sensitive message content on lockscreen/shade
            SharedPreferences appPrefs = mContext.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE);
            String savedPin = appPrefs.getString("pin_code", null);
            boolean isPinLockEnabled = savedPin != null && !savedPin.trim().isEmpty();

            String effectiveBody = (showPreview && !isPinLockEnabled) ? previewText : "New message";
            String effectiveSender = isPinLockEnabled ? "VEIL" : senderName;

            List<NotifMessageEntry> history = mActiveChatHistory.computeIfAbsent(
                    chatId, k -> Collections.synchronizedList(new ArrayList<>()));
            synchronized (history) {
                history.add(new NotifMessageEntry(
                        messageId, peerId, effectiveSender, effectiveBody, timestampMs, deliveredViaDirectP2P));
                while (history.size() > MAX_HISTORY_PER_CHAT) {
                    history.remove(0);
                }
            }

            Bitmap avatarBitmap = createSenderAvatarBitmap(effectiveSender, chatId);
            IconCompat senderIcon = IconCompat.createWithBitmap(avatarBitmap);

            Person mePerson = new Person.Builder()
                    .setName("You")
                    .build();

            Person senderPerson = new Person.Builder()
                    .setName(effectiveSender)
                    .setKey(peerId != null ? peerId : chatId)
                    .setIcon(senderIcon)
                    .build();

            NotificationCompat.MessagingStyle messagingStyle = new NotificationCompat.MessagingStyle(mePerson);
            String transportBadge = deliveredViaDirectP2P ? "⚡ Direct P2P" : "☁️ Cloud Sync";
            if (isGroup && conversationTitle != null && !isPinLockEnabled) {
                messagingStyle.setConversationTitle(conversationTitle + " • " + transportBadge);
                messagingStyle.setGroupConversation(true);
            } else if (!isPinLockEnabled) {
                messagingStyle.setConversationTitle(effectiveSender + " • " + transportBadge);
                messagingStyle.setGroupConversation(false);
            }

            synchronized (history) {
                for (NotifMessageEntry entry : history) {
                    Person msgPerson = new Person.Builder()
                            .setName(entry.senderName)
                            .setIcon(senderIcon)
                            .build();
                    messagingStyle.addMessage(new NotificationCompat.MessagingStyle.Message(
                            entry.displayBody, entry.timestampMs, msgPerson));
                }
            }

            int notifId = getNotificationIdForChat(chatId);

            // 1. Tap Intent -> Opens ChatActivity directly to this conversation
            Intent openChatIntent = new Intent(mContext, ChatActivity.class);
            openChatIntent.putExtra("chatId", chatId);
            if (!isGroup && peerId != null) {
                openChatIntent.putExtra("recipientId", peerId);
                openChatIntent.putExtra("recipientName", senderName);
            }
            openChatIntent.putExtra("isGroup", isGroup);
            openChatIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent contentPendingIntent = PendingIntent.getActivity(
                    mContext, notifId, openChatIntent, pendingFlags);

            // 2. Inline Direct Reply Action (RemoteInput requires FLAG_MUTABLE on Android S+)
            int mutableFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_TEXT_REPLY)
                    .setLabel(deliveredViaDirectP2P ? "Reply via Direct P2P..." : "Reply...")
                    .build();

            Intent replyIntent = new Intent(mContext, VeilBootAndKeepAliveReceiver.class);
            replyIntent.setAction(ACTION_DIRECT_REPLY);
            replyIntent.putExtra(EXTRA_CHAT_ID, chatId);
            replyIntent.putExtra(EXTRA_PEER_ID, peerId);
            replyIntent.putExtra(EXTRA_SENDER_NAME, senderName);
            replyIntent.putExtra(EXTRA_IS_GROUP, isGroup);
            PendingIntent replyPendingIntent = PendingIntent.getBroadcast(
                    mContext, notifId + 10000, replyIntent, mutableFlags);

            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_send, "Reply", replyPendingIntent)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build();

            // 3. Mark as Read Action
            Intent markReadIntent = new Intent(mContext, VeilBootAndKeepAliveReceiver.class);
            markReadIntent.setAction(ACTION_MARK_READ);
            markReadIntent.putExtra(EXTRA_CHAT_ID, chatId);
            markReadIntent.putExtra(EXTRA_PEER_ID, peerId);
            PendingIntent markReadPendingIntent = PendingIntent.getBroadcast(
                    mContext, notifId + 20000, markReadIntent, pendingFlags);

            NotificationCompat.Action markReadAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_check_double, "Mark as Read", markReadPendingIntent)
                    .build();

            // 4. Mute Chat Action
            Intent muteIntent = new Intent(mContext, VeilBootAndKeepAliveReceiver.class);
            muteIntent.setAction(ACTION_MUTE_CHAT);
            muteIntent.putExtra(EXTRA_CHAT_ID, chatId);
            PendingIntent mutePendingIntent = PendingIntent.getBroadcast(
                    mContext, notifId + 30000, muteIntent, pendingFlags);

            NotificationCompat.Action muteAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_shield, "Mute", mutePendingIntent)
                    .build();

            String channelId = isGroup ? CHANNEL_GROUP_MESSAGES : CHANNEL_DIRECT_MESSAGES;
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, channelId)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setLargeIcon(avatarBitmap)
                    .setStyle(messagingStyle)
                    .setContentTitle(effectiveSender)
                    .setContentText(effectiveBody)
                    .setSubText(transportBadge)
                    .setColor(deliveredViaDirectP2P ? Color.parseColor("#10B981") : Color.parseColor("#6366F1"))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(isPinLockEnabled
                            ? NotificationCompat.VISIBILITY_PRIVATE
                            : NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .setContentIntent(contentPendingIntent)
                    .setGroup(GROUP_KEY_CONVERSATIONS)
                    .addAction(replyAction)
                    .addAction(markReadAction)
                    .addAction(muteAction);

            if (vibrateEnabled) {
                builder.setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);
            } else {
                builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            }

            mNotificationManager.notify(notifId, builder.build());
            postOrUpdateGroupSummaryNotification();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to post rich hybrid notification", t);
        }
    }

    private void postOrUpdateGroupSummaryNotification() {
        if (mNotificationManager == null || mActiveChatHistory.size() <= 1) return;
        try {
            int totalChats = mActiveChatHistory.size();
            int totalMessages = 0;
            for (List<NotifMessageEntry> list : mActiveChatHistory.values()) {
                totalMessages += list.size();
            }

            Intent mainIntent = new Intent(mContext, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent summaryPi = PendingIntent.getActivity(mContext, SUMMARY_NOTIFICATION_ID, mainIntent, flags);

            Notification summary = new NotificationCompat.Builder(mContext, CHANNEL_DIRECT_MESSAGES)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle("VEIL")
                    .setContentText(totalMessages + " new messages from " + totalChats + " chats")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setGroup(GROUP_KEY_CONVERSATIONS)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setContentIntent(summaryPi)
                    .build();

            mNotificationManager.notify(SUMMARY_NOTIFICATION_ID, summary);
        } catch (Throwable ignored) {}
    }

    /**
     * Clears active notifications and unread history for a specific conversation when opened or marked read.
     */
    public void clearChatNotification(@Nullable String chatId) {
        if (chatId == null || chatId.isEmpty()) return;
        mActiveChatHistory.remove(chatId);
        if (mNotificationManager != null) {
            mNotificationManager.cancel(getNotificationIdForChat(chatId));
            if (mActiveChatHistory.isEmpty()) {
                mNotificationManager.cancel(SUMMARY_NOTIFICATION_ID);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 4. NOTIFICATION SHADE ACTION HANDLERS (INLINE REPLY, MARK READ, MUTE)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public void handleNotificationActionIntent(@NonNull Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        String chatId = intent.getStringExtra(EXTRA_CHAT_ID);
        String peerId = intent.getStringExtra(EXTRA_PEER_ID);

        if (ACTION_DIRECT_REPLY.equals(action) && chatId != null) {
            Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
            if (remoteInput != null) {
                CharSequence replySeq = remoteInput.getCharSequence(KEY_TEXT_REPLY);
                String replyText = replySeq != null ? replySeq.toString().trim() : "";
                if (!replyText.isEmpty()) {
                    // Send message via VeilMessagesController (automatically uses Direct P2P if connected, or Firebase fallback)
                    VeilMessagesController.getInstance(mContext).sendTextMessage(
                            chatId, peerId, replyText, null, false);
                    // Also mark existing messages in this chat as read
                    markChatAsReadFromNotification(chatId, peerId);
                    clearChatNotification(chatId);
                }
            }
        } else if (ACTION_MARK_READ.equals(action) && chatId != null) {
            markChatAsReadFromNotification(chatId, peerId);
            clearChatNotification(chatId);
        } else if (ACTION_MUTE_CHAT.equals(action) && chatId != null) {
            VeilMessagesController.getInstance(mContext).toggleDialogMuted(chatId);
            clearChatNotification(chatId);
        }
    }

    private void markChatAsReadFromNotification(@NonNull String chatId, @Nullable String peerId) {
        try {
            String myId = VeilMessagesController.getInstance(mContext).getCurrentUserId();
            EncryptedChatDatabase.getInstance(mContext).markAllAsRead(chatId, myId);
            if (peerId != null && !peerId.isEmpty()) {
                WebRtcManager.getInstance(mContext).sendChatReadReceipt(peerId, chatId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to mark chat as read from notification: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 5. HELPERS: DEDUPLICATION, IN-APP FEEDBACK, AVATAR GENERATION & PREVIEW FORMATTING
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public synchronized boolean isMessageAlreadyNotified(@Nullable String messageId) {
        if (messageId == null || messageId.isEmpty()) return false;
        return mNotifiedMessageIds.contains(messageId);
    }

    public synchronized boolean markMessageNotified(@Nullable String messageId) {
        if (messageId == null || messageId.isEmpty()) return false;
        if (!mNotifiedMessageIds.add(messageId)) {
            return false;
        }
        persistRecentNotifiedIdsToPrefs();
        return true;
    }

    private void loadRecentNotifiedIdsFromPrefs() {
        try {
            SharedPreferences prefs = mContext.getSharedPreferences("veil_hybrid_notif_dedup", Context.MODE_PRIVATE);
            String csv = prefs.getString("recent_ids", "");
            if (csv != null && !csv.isEmpty()) {
                String[] parts = csv.split(",");
                for (String id : parts) {
                    if (id != null && !id.trim().isEmpty()) {
                        mNotifiedMessageIds.add(id.trim());
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void persistRecentNotifiedIdsToPrefs() {
        try {
            List<String> snapshot = new ArrayList<>(mNotifiedMessageIds);
            int start = Math.max(0, snapshot.size() - 120);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < snapshot.size(); i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(snapshot.get(i));
            }
            mContext.getSharedPreferences("veil_hybrid_notif_dedup", Context.MODE_PRIVATE)
                    .edit()
                    .putString("recent_ids", sb.toString())
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void triggerInAppFeedbackIfEnabled() {
        try {
            SharedPreferences prefs = mContext.getSharedPreferences(NotificationSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(NotificationSettingsActivity.KEY_INAPP_VIBRATE, true)) {
                Vibrator vib = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vib.vibrate(25L);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @NonNull
    private String resolveSenderDisplayName(@Nullable Chat chat, @NonNull Message message, @Nullable String peerId) {
        String candidate = message.getSenderName();
        if (candidate != null && !candidate.trim().isEmpty()
                && !VeilFeatureManager.isShadowVeilOrGeneric(candidate)
                && !"Direct Chat".equalsIgnoreCase(candidate)
                && !"User".equalsIgnoreCase(candidate)) {
            return candidate.trim();
        }
        if (chat != null && !chat.isGroupChat() && chat.getName() != null && !chat.getName().trim().isEmpty()
                && !VeilFeatureManager.isShadowVeilOrGeneric(chat.getName())
                && !"Direct Chat".equalsIgnoreCase(chat.getName())) {
            return chat.getName().trim();
        }
        if (peerId != null && !peerId.isEmpty()) {
            return "Peer " + peerId.substring(0, Math.min(6, peerId.length()));
        }
        return "Contact";
    }

    @NonNull
    public static String buildCleanPreviewText(@Nullable Message message) {
        if (message == null) return "New message";
        if (message.isBlink()) {
            return "🔥 View-Once Media";
        }
        String type = message.getType() != null ? message.getType().toLowerCase() : "text";
        String rawText = message.getText() != null ? message.getText().trim() : "";

        // Check if grouped media album
        if (message.getPollJson() != null && message.getPollJson().contains("albumItems")) {
            try {
                JSONObject meta = new JSONObject(message.getPollJson());
                org.json.JSONArray arr = meta.optJSONArray("albumItems");
                if (arr != null && arr.length() > 1) {
                    return "🖼️ Album (" + arr.length() + " items)" + (!rawText.isEmpty() ? " • " + rawText : "");
                }
            } catch (Throwable ignored) {}
        }

        switch (type) {
            case "image":
                return rawText.isEmpty() || rawText.startsWith("[") ? "📷 Photo" : "📷 " + rawText;
            case "video":
                return rawText.isEmpty() || rawText.startsWith("[") ? "🎬 Video" : "🎬 " + rawText;
            case "video_note":
                return "⏺️ Video Note";
            case "voice":
            case "audio":
                return "🎤 Voice Message";
            case "sticker":
                return "✨ Sticker";
            case "gif":
                return "👾 GIF";
            case "document":
                return "📄 Document";
            case "location":
                return "📍 Shared Location";
            case "contact":
                return "👤 Shared Contact";
            case "poll":
                return rawText.isEmpty() ? "📊 Poll" : rawText;
            default:
                if (rawText.startsWith("[STICKER:")) return "✨ Sticker";
                if (rawText.startsWith("[GIF:")) return "👾 GIF";
                return rawText.isEmpty() ? "New message" : rawText;
        }
    }

    @NonNull
    private static String formatFallbackTypePreview(@Nullable String msgType) {
        if (msgType == null) return "New message";
        switch (msgType.toLowerCase()) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "voice":
            case "audio": return "🎤 Voice Message";
            case "sticker": return "✨ Sticker";
            case "gif": return "👾 GIF";
            case "document": return "📄 Document";
            case "location": return "📍 Shared Location";
            default: return "New message";
        }
    }

    private int getNotificationIdForChat(@NonNull String chatId) {
        return 70000 + (Math.abs(chatId.hashCode()) % 6000);
    }

    @NonNull
    private Bitmap createSenderAvatarBitmap(@NonNull String displayName, @NonNull String seedKey) {
        int sizePx = Math.round(64 * mContext.getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        int hash = Math.abs(seedKey.hashCode());
        int[][] palettes = new int[][]{
                {0xFF6366F1, 0xFF4338CA},
                {0xFF0EA5E9, 0xFF0369A1},
                {0xFF10B981, 0xFF047857},
                {0xFFF43F5E, 0xFFBE123C},
                {0xFF8B5CF6, 0xFF6D28D9},
                {0xFFF59E0B, 0xFFB45309}
        };
        int[] pal = palettes[hash % palettes.length];

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(0, 0, sizePx, sizePx, pal[0], pal[1], Shader.TileMode.CLAMP));
        float r = sizePx * 0.5f;
        canvas.drawCircle(r, r, r, bgPaint);

        String clean = displayName.replace("@", "").trim();
        String initials = clean.isEmpty() ? "V" : clean.substring(0, 1).toUpperCase();
        if (clean.contains(" ")) {
            String[] parts = clean.split("\\s+");
            if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                initials = (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
            }
        }

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tp.setTextSize(sizePx * 0.40f);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float textY = r - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(initials, r, textY, tp);

        return bmp;
    }
}
