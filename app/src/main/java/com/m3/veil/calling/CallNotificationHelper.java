package com.m3.veil.calling;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.m3.veil.R;
import com.m3.veil.ui.chat.CallActivity;

/**
 * Call notifications: a full-screen incoming-call notification (works from the
 * background / lock screen) and a low-priority ongoing-call notification that is
 * kept alive by {@link VeilCallService}.
 */
public final class CallNotificationHelper {

    private static final String TAG = "CallNotifications";
    public static final String CHANNEL_INCOMING = "veil_calls_incoming_v2";
    public static final String CHANNEL_ONGOING = "veil_calls_ongoing";
    public static final int ID_INCOMING = 8801;
    public static final int ID_ONGOING = 8802;
    public static final int ID_MISSED = 8803;
    public static final int ID_LEGACY_INCOMING = 9991;

    public static final String ACTION_DECLINE = "com.m3.veil.calling.DECLINE";
    public static final String ACTION_ANSWER = "com.m3.veil.calling.ANSWER";

    private CallNotificationHelper() { }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel incoming = new NotificationChannel(CHANNEL_INCOMING, "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH);
        incoming.setDescription("Incoming Veil audio and video calls");
        incoming.enableVibration(true);
        incoming.setVibrationPattern(new long[]{0, 1000, 1000, 1000, 1000});
        incoming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        incoming.setBypassDnd(true);

        android.net.Uri ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
        android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        incoming.setSound(ringtoneUri, audioAttributes);
        manager.createNotificationChannel(incoming);

        NotificationChannel ongoing = new NotificationChannel(CHANNEL_ONGOING, "Ongoing calls",
                NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("Active Veil call status");
        ongoing.setShowBadge(false);
        manager.createNotificationChannel(ongoing);
    }

    public static void showIncomingCall(Context context, CallInfo info) {
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || info == null) return;
        try {
            // Cancel any legacy duplicate incoming notification first
            try {
                androidx.core.app.NotificationManagerCompat.from(context).cancel(ID_LEGACY_INCOMING);
                manager.cancel(ID_LEGACY_INCOMING);
            } catch (Throwable ignored) { }

            // Wake the device display immediately from sleep
            try {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                            android.os.PowerManager.FULL_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE,
                            "veil:call_notification_wake");
                    wl.acquire(20_000L);
                }
            } catch (Throwable ignored) { }

            // Start centralized ringtone and vibration in VeilCallService
            VeilCallService.startIncomingRinging(context, info);

            Intent fullScreen = buildCallIntent(context, info, false);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 100, fullScreen,
                    pendingFlags());

            // Route Decline button through VeilCallService so notification & ringtone are stopped immediately
            Intent declineServiceIntent = new Intent(context, VeilCallService.class);
            declineServiceIntent.setAction(VeilCallService.ACTION_DECLINE_CALL);
            populateCallExtras(declineServiceIntent, info);
            PendingIntent declineIntent = PendingIntent.getService(context, 101, declineServiceIntent,
                    pendingFlags());

            // Route Answer button through VeilCallService (ACTION_ANSWER_CALL) so notification & ringtone
            // are dismissed immediately and CallActivity transitions directly into the active call
            Intent answerServiceIntent = new Intent(context, VeilCallService.class);
            answerServiceIntent.setAction(VeilCallService.ACTION_ANSWER_CALL);
            populateCallExtras(answerServiceIntent, info);
            PendingIntent answerIntent = PendingIntent.getService(context, 102, answerServiceIntent,
                    pendingFlags());

            String title = info.isGroup() && info.getGroupName() != null
                    ? info.getGroupName() : info.getPeerName();
            String text = info.getType() == CallType.VIDEO
                    ? "Incoming video call\u2026" : "Incoming call\u2026";

            Notification notification = new NotificationCompat.Builder(context, CHANNEL_INCOMING)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setSilent(true) // Ringtone and vibration are centrally managed by VeilCallService
                    .setContentIntent(contentIntent)
                    .setFullScreenIntent(contentIntent, true)
                    .addAction(R.drawable.ic_check, "Answer", answerIntent)
                    .addAction(0, "Decline", declineIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build();
            manager.notify(ID_INCOMING, notification);
            launchCallActivity(context, info, false);
        } catch (Throwable t) {
            Log.w(TAG, "showIncomingCall failed: " + t.getMessage());
        }
    }

    private static void populateCallExtras(Intent intent, CallInfo info) {
        if (intent == null || info == null) return;
        intent.putExtra(CallActivity.EXTRA_CONTACT_ID, info.getPeerId());
        intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, info.getPeerName());
        intent.putExtra(CallActivity.EXTRA_CALL_TYPE,
                info.getType() == CallType.VIDEO ? "video" : "audio");
        intent.putExtra(CallActivity.EXTRA_CALL_ID, info.getCallId());
        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, true);
        if (info.getGroupId() != null) {
            intent.putExtra(CallActivity.EXTRA_GROUP_ID, info.getGroupId());
        }
        if (info.getGroupName() != null) {
            intent.putExtra(CallActivity.EXTRA_GROUP_NAME, info.getGroupName());
        }
    }

    public static void showOngoingCall(Context context, CallInfo info) {
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || info == null) return;
        try {
            cancelIncoming(context);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 102,
                    buildCallIntent(context, info, false), pendingFlags());
            String title = info.isGroup() && info.getGroupName() != null
                    ? info.getGroupName() : info.getPeerName();
            long seconds = info.getDurationMs() / 1000L;
            String text = String.format(java.util.Locale.US, "%02d:%02d \u2022 %s",
                    seconds / 60, seconds % 60, info.isE2eeEnabled() ? "E2EE" : "connected");
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ONGOING)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setContentIntent(contentIntent)
                    .build();
            manager.notify(ID_ONGOING, notification);
        } catch (Throwable t) {
            Log.w(TAG, "showOngoingCall failed: " + t.getMessage());
        }
    }

    /** A call that rang while this device was offline or was cancelled before answer. Never full-screen. */
    public static void showMissedCall(Context context, String peerName, boolean video) {
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            cancelIncoming(context);
            Intent intent = new Intent(context, com.m3.veil.ui.main.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 103, intent, pendingFlags());
            String title = (peerName != null && !peerName.isEmpty()) ? peerName : "Unknown";
            String text = video ? "Missed video call" : "Missed call";
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ONGOING)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build();
            manager.notify(ID_MISSED, notification);
        } catch (Throwable t) {
            Log.w(TAG, "showMissedCall failed: " + t.getMessage());
        }
    }

    public static void cancelIncoming(Context context) {
        if (context == null) return;
        try {
            androidx.core.app.NotificationManagerCompat compat = androidx.core.app.NotificationManagerCompat.from(context);
            compat.cancel(ID_INCOMING);
            compat.cancel(ID_LEGACY_INCOMING);
        } catch (Throwable ignored) { }
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(ID_INCOMING);
                manager.cancel(ID_LEGACY_INCOMING);
            }
        } catch (Throwable ignored) { }
    }

    public static void cancelAll(Context context) {
        if (context == null) return;
        cancelIncoming(context);
        try {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(ID_ONGOING);
        } catch (Throwable ignored) { }
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(ID_ONGOING);
            }
        } catch (Throwable ignored) { }
    }

    public static Intent buildCallIntent(Context context, CallInfo info, boolean decline) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (info != null) {
            populateCallExtras(intent, info);
        }
        intent.putExtra(CallActivity.EXTRA_FROM_NOTIFICATION, true);
        if (decline) intent.putExtra(CallActivity.EXTRA_DECLINE_ON_START, true);
        return intent;
    }

    public static void launchCallActivity(Context context, CallInfo info, boolean decline) {
        try {
            context.startActivity(buildCallIntent(context, info, decline));
        } catch (Throwable t) {
            Log.w(TAG, "could not launch CallActivity: " + t.getMessage());
        }
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }
}
