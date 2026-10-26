package com.m3.veil.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.m3.veil.bridge.firestore.FirebaseFirestore;
import com.m3.veil.bridge.messaging.FirebaseMessagingService;
import com.m3.veil.bridge.messaging.RemoteMessage;
import com.m3.veil.R;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.webrtc.WebRtcManager;

import java.util.Map;

/**
 * 100% Zero-Knowledge Push Notification Service.
 * Follows the privacy architecture:
 * FCM is used STRICTLY as a wake-up signal to notify this device that an offline peer
 * has sent messages or is initiating a WebRTC session.
 * NO plaintext message content or media URLs are ever included in the FCM payload.
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "VeilFCMService";
    private static final String CHANNEL_ID = "veil_privacy_wake_channel";

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        String userId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (userId != null && !userId.isEmpty()) {
            try {
                FirebaseFirestore.getInstance().collection("users").document(userId)
                        .update("fcmToken", token);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        String signalType = data.get("type");
        String senderId = data.get("senderId");
        String chatId = data.get("chatId");
        String mailbox = data.get("mailbox");

        Log.d(TAG, "Wake-up signal received from peer: " + senderId + " | type: " + signalType + " | mailbox: " + mailbox);

        // Zero-Knowledge Mesh Wake Knock: Wake daemon within 500ms to open WebRTC DataChannel & flush outbox
        if ("mesh_wake".equals(signalType)) {
            Log.i(TAG, "Zero-Knowledge Mesh Wake Knock received! Waking VeilMeshDaemonService within 500ms.");
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                try {
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "veil:fcm_mesh_wake_lock");
                    wl.acquire(15_000L);
                } catch (Throwable ignored) { }
            }

            VeilMeshDaemonService.start(this);

            String currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
            if (currentUid != null && !currentUid.isEmpty()) {
                WebRtcManager.getInstance(this).init(currentUid);
                WebRtcManager.getInstance(this).fetchAndProcessOfflineInboxOnce(currentUid);
                VeilMeshDaemonService daemon = VeilMeshDaemonService.getInstance();
                if (daemon != null) {
                    daemon.flushOutboxQueue();
                }
            }
            return;
        }

        // Call lifecycle pushes are owned by the call engine, which raises the
        // full-screen incoming-call UI. They never go through the chat path.
        if ("call_end".equals(signalType)) {
            // The caller gave up / declined while we were backgrounded: tear the
            // ring down instead of leaving a dead incoming-call notification.
            try {
                com.m3.veil.calling.CallNotificationHelper.cancelAll(this);
                com.m3.veil.calling.VeilCallService.stop(this);
                com.m3.veil.calling.CallEngine.getInstance(this).hangup();
            } catch (Throwable t) {
                Log.w(TAG, "call_end cleanup failed", t);
            }
            Log.d(TAG, "call_end push received; cancelled call notifications");
            return;
        }
        if ("call_wake".equals(signalType) || "call_upgrade".equals(signalType)) {
            // 1. Acquire CPU WakeLock so Android OS does not put the device back to sleep
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                try {
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "veil:fcm_call_wake_lock");
                    wl.acquire(25_000L);
                } catch (Throwable ignored) { }
            }

            // 2. Start Foreground Service so Android background limits don't freeze/kill the process
            com.m3.veil.calling.VeilCallService.start(this);

            // 3. Initialize CallEngine
            String currentUid = null;
            currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
            if (currentUid != null && !currentUid.isEmpty()) {
                try {
                    com.m3.veil.calling.CallEngine.getInstance(this).init(currentUid);
                } catch (Throwable t) {
                    Log.w(TAG, "call engine wake failed", t);
                }
            }

            // 4. Extract call parameters from FCM push data
            String callId = data.get("callId");
            String sender = data.get("senderId");
            String senderName = data.get("senderName");
            if (senderName == null || senderName.isEmpty()) senderName = "Incoming Call";
            String callTypeStr = data.get("callType");
            com.m3.veil.calling.CallType callType = "video".equalsIgnoreCase(callTypeStr)
                    ? com.m3.veil.calling.CallType.VIDEO : com.m3.veil.calling.CallType.AUDIO;

            // 5. Post full-screen incoming call UI immediately with high-priority ringtone & vibration
            if (callId != null && !callId.isEmpty() && sender != null && !sender.isEmpty()) {
                com.m3.veil.calling.CallInfo info = com.m3.veil.calling.CallInfo.builder()
                        .callId(callId)
                        .peerId(sender)
                        .peerName(senderName)
                        .type(callType)
                        .caller(false)
                        .build();
                com.m3.veil.calling.CallNotificationHelper.showIncomingCall(this, info);
            }
            return;
        }

        // 1. Wake the appropriate stack.
        String currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUid != null && !currentUid.isEmpty()) {
            // Wake up WebRTC P2P manager in background & immediately ingest any offline mailbox packets
            WebRtcManager.getInstance(this).init(currentUid);
            WebRtcManager.getInstance(this).fetchAndProcessOfflineInboxOnce(currentUid);
            if (senderId != null && !senderId.isEmpty()) {
                WebRtcManager.getInstance(this).initiateConnection(senderId);
            }
        }

        // 2. Post a privacy-first wake notification without leaking any plaintext message content
        String title = "Secure Encrypted Message";
        String body = "Tap to decrypt and view via P2P connection";

        if ("call_wake".equals(signalType)) {
            title = "Incoming Secure Call";
            body = "E2EE Call session requested";
        }

        sendPrivacyNotification(title, body, chatId, senderId);
    }

    private void sendPrivacyNotification(String title, String messageBody, String chatId, String senderId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (chatId != null) intent.putExtra("openChatId", chatId);
        if (senderId != null) intent.putExtra("openRecipientId", senderId);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Secure Messages",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Zero-knowledge wake-up notifications for peer-to-peer messages");
            channel.enableVibration(true);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_chat)
                        .setContentTitle(title)
                        .setContentText(messageBody)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setContentIntent(pendingIntent);

        notificationManager.notify((int) System.currentTimeMillis(), notificationBuilder.build());
    }
}
