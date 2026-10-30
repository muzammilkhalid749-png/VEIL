package com.m3.veil.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.m3.veil.R;
import com.m3.veil.calling.CallEngine;
import com.m3.veil.calling.CallInfo;
import com.m3.veil.calling.CallNotificationHelper;
import com.m3.veil.calling.CallType;
import com.m3.veil.calling.VeilCallService;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.utils.PreferenceManager;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.WebRtcManager;

import java.util.HashMap;
import java.util.Map;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL BLIND FCM WAKE-UP SERVICE (SIGNAL SMART-HYBRID ARCHITECTURE)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Uses Firebase Cloud Messaging strictly as a Zero-Knowledge "Blind Wake Tickle":
 *   - Payloads ("wake_up", "mesh_wake", "call_invite", "call_wake") contain ZERO plaintext message
 *     content.
 *   - Acquires a short 8-second partial WakeLock so Xiaomi, Vivo, Samsung, and Infinix phones
 *     wake within 200ms when the screen is off.
 *   - Starts "VeilMeshDaemonService", initializes "WebRtcManager" + "SignalingClient" + "CallEngine",
 *     and establishes the direct E2EE WebRTC P2P DataChannel / Call session.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "VeilBlindFcmService";
    private static final String CHANNEL_ID = "veil_chat_notifications";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "Refreshed Blind FCM wake token");
        saveTokenToFirestore(token);
    }

    private void saveTokenToFirestore(String token) {
        if (token == null || token.isEmpty()) return;
        try {
            new PreferenceManager(this).putString("fcm_token", token);
        } catch (Exception ignored) {}

        String userId = AuthManager.getInstance().getCurrentUserId(this);
        if (userId != null && !userId.isEmpty()) {
            Map<String, Object> tokenUpdate = new HashMap<>();
            tokenUpdate.put("fcmToken", token);
            tokenUpdate.put("fcmUpdatedAt", System.currentTimeMillis());
            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .set(tokenUpdate, SetOptions.merge());
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veil:fcm_blind_wake");
            wakeLock.acquire(8_000L);
        }

        try {
            Map<String, String> data = remoteMessage.getData();
            if (data != null && !data.isEmpty()) {
                String type = data.get("type");
                String senderId = data.get("senderId");
                if (senderId == null || senderId.isEmpty()) {
                    senderId = data.get("from");
                }
                String chatId = data.get("chatId");
                String mailbox = data.get("mailbox");

                // 1. Incoming Call Wake Tickle ("call_wake" or "call_invite")
                if ("call_wake".equals(type) || "call_invite".equals(type)) {
                    handleIncomingCallPush(data);
                    return;
                }

                // 2. Call Cancellation / End Tickle
                if ("call_end".equals(type)) {
                    handleCallEndPush(data);
                    return;
                }

                // 3. Call Video Upgrade Tickle
                if ("call_upgrade".equals(type)) {
                    handleCallUpgradePush();
                    return;
                }

                // 4. Zero-Knowledge P2P Mesh Wake Tickle ("wake_up", "mesh_wake", "chat_message")
                if ("wake_up".equals(type) || "mesh_wake".equals(type) || "chat_message".equals(type) || type == null) {
                    VeilHybridNotificationManager.getInstance(this).onFirebaseWakeOrPushReceived(data);
                    handleBlindMeshWakeUp(senderId, chatId, mailbox);
                    return;
                }
            }

            // Fallback: even if data is empty, wake the P2P mesh daemon
            handleBlindMeshWakeUp(null, null, null);

            if (remoteMessage.getNotification() != null) {
                String title = remoteMessage.getNotification().getTitle();
                String body = remoteMessage.getNotification().getBody();
                showNotification(
                        title != null ? title : "VEIL",
                        body != null ? body : "New encrypted activity",
                        data
                );
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {}
            }
        }
    }

    private void handleBlindMeshWakeUp(String senderId, String chatId, String mailbox) {
        String currentUid = AuthManager.getInstance().getCurrentUserId(this);
        if (currentUid == null || currentUid.isEmpty()) {
            return;
        }

        // Start VeilMeshDaemonService and attach Firestore + WebSocket signaling immediately
        VeilMeshDaemonService.wakeFromPush(this, senderId, mailbox);

        WebRtcManager rtc = WebRtcManager.getInstance(this);
        rtc.init(currentUid);
        SignalingClient.getInstance().init(currentUid, rtc);

        if (senderId != null && !senderId.isEmpty() && !senderId.equals(currentUid)) {
            rtc.onWakeUpReceived(
                    senderId,
                    chatId != null ? chatId : WebRtcManager.getChatId(currentUid, senderId)
            );
            rtc.initiateConnection(senderId);
        }
    }

    private void handleIncomingCallPush(Map<String, String> data) {
        String callId = data.get("callId");
        String senderId = data.get("senderId");
        if (senderId == null || senderId.isEmpty()) {
            senderId = data.get("from");
        }
        String senderName = data.get("senderName");
        if (senderName == null || senderName.isEmpty()) {
            senderName = data.get("callerName");
        }
        String callTypeStr = data.get("callType");

        String currentUid = AuthManager.getInstance().getCurrentUserId(this);
        if (currentUid != null && !currentUid.isEmpty()) {
            CallEngine.getInstance(this).init(currentUid);
            WebRtcManager.getInstance(this).init(currentUid);
        }

        VeilCallService.start(this);

        if (callId != null && !callId.isEmpty() && senderId != null && !senderId.isEmpty()) {
            CallType ct = "video".equalsIgnoreCase(callTypeStr) ? CallType.VIDEO : CallType.AUDIO;
            CallInfo wakeInfo = CallInfo.builder()
                    .callId(callId)
                    .type(ct)
                    .caller(false)
                    .peerId(senderId)
                    .peerName(senderName != null && !senderName.isEmpty() ? senderName : "Incoming Call")
                    .group(false)
                    .state(com.m3.veil.calling.CallState.RECEIVED_CALL)
                    .build();
            CallNotificationHelper.showIncomingCall(this, wakeInfo);
        }
    }

    private void handleCallEndPush(Map<String, String> data) {
        try {
            String callId = data != null ? data.get("callId") : null;
            String senderId = data != null ? data.get("senderId") : null;
            if (senderId == null || senderId.isEmpty()) {
                senderId = data != null ? data.get("from") : null;
            }
            CallNotificationHelper.cancelAll(this);
            CallEngine.getInstance(this).onExternalCallEnded(senderId, callId, com.m3.veil.calling.CallEndReason.REMOTE_HANGUP);
            if (senderId != null && callId != null) {
                WebRtcManager.getInstance(this).onCallEndReceived(senderId, callId);
            }
            VeilCallService.stop(this);
        } catch (Throwable t) {
            Log.w(TAG, "handleCallEndPush warning", t);
        }
    }

    private void handleCallUpgradePush() {
        String currentUid = AuthManager.getInstance().getCurrentUserId(this);
        if (currentUid != null && !currentUid.isEmpty()) {
            CallEngine.getInstance(this).init(currentUid);
        }
    }

    private void showNotification(String title, String body, Map<String, String> data) {
        Intent intent;
        if (data != null && data.containsKey("chatId")) {
            intent = new Intent(this, ChatActivity.class);
            intent.putExtra("chatId", data.get("chatId"));
            intent.putExtra("recipientId", data.get("senderId"));
            intent.putExtra("recipientName", title);
        } else {
            intent = new Intent(this, MainActivity.class);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.veil_logo)
                        .setContentTitle(title != null ? title : "VEIL")
                        .setContentText(body != null ? body : "New encrypted message")
                        .setAutoCancel(true)
                        .setSound(defaultSoundUri)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Chat Messages",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Notifications for incoming encrypted messages");
                channel.enableVibration(true);
                notificationManager.createNotificationChannel(channel);
            }
            notificationManager.notify((int) System.currentTimeMillis(), notificationBuilder.build());
        }
    }
}
