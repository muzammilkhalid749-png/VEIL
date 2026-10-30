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
    public static final String CHANNEL_INCOMING = "veil_calls_incoming_v3";
    public static final String CHANNEL_ONGOING = "veil_calls_ongoing";
    public static final int ID_INCOMING = 8801;
    public static final int ID_ONGOING = 8802;
    public static final int ID_MISSED = 8803;
    public static final int ID_LEGACY_WEBRTC = 9991;

    public static final String ACTION_DECLINE = "com.m3.veil.calling.DECLINE";
    public static final String ACTION_ANSWER = "com.m3.veil.calling.ANSWER";

    private static android.media.MediaPlayer sRingtonePlayer;
    private static android.os.Vibrator sVibrator;

    private CallNotificationHelper() { }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        // Remove legacy insistent channels so system server never plays orphan ringtones
        try {
            manager.deleteNotificationChannel("veil_calls_incoming");
            manager.deleteNotificationChannel("veil_calls_incoming_v2");
            manager.deleteNotificationChannel("veil_call_channel");
        } catch (Throwable ignored) { }

        NotificationChannel incoming = new NotificationChannel(CHANNEL_INCOMING, "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH);
        incoming.setDescription("Incoming Veil audio and video calls");
        incoming.enableVibration(false);
        incoming.setSound(null, null); // Controlled deterministically via startRinging / stopRinging
        incoming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        incoming.setBypassDnd(true);
        manager.createNotificationChannel(incoming);

        NotificationChannel ongoing = new NotificationChannel(CHANNEL_ONGOING, "Ongoing calls",
                NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("Active Veil call status");
        ongoing.setSound(null, null);
        ongoing.enableVibration(false);
        ongoing.setShowBadge(false);
        manager.createNotificationChannel(ongoing);
    }

    public static synchronized void startRinging(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        try {
            if (sRingtonePlayer != null && sRingtonePlayer.isPlaying()) {
                return;
            }
            stopRinging(appCtx);
            android.net.Uri uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
            sRingtonePlayer = new android.media.MediaPlayer();
            sRingtonePlayer.setDataSource(appCtx, uri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                sRingtonePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                sRingtonePlayer.setAudioStreamType(android.media.AudioManager.STREAM_RING);
            }
            sRingtonePlayer.setLooping(true);
            sRingtonePlayer.prepare();
            sRingtonePlayer.start();
        } catch (Throwable t) {
            Log.w(TAG, "startRinging fallback: " + t.getMessage());
        }
        try {
            sVibrator = (android.os.Vibrator) appCtx.getSystemService(Context.VIBRATOR_SERVICE);
            if (sVibrator != null && sVibrator.hasVibrator()) {
                long[] pattern = new long[]{0L, 1000L, 800L, 1000L, 800L};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sVibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0));
                } else {
                    sVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Throwable ignored) { }
    }

    public static synchronized void stopRinging(Context context) {
        try {
            if (sRingtonePlayer != null) {
                try {
                    if (sRingtonePlayer.isPlaying()) {
                        sRingtonePlayer.stop();
                    }
                } catch (Throwable ignored) { }
                try {
                    sRingtonePlayer.release();
                } catch (Throwable ignored) { }
                sRingtonePlayer = null;
            }
        } catch (Throwable ignored) { }
        try {
            if (sVibrator != null) {
                sVibrator.cancel();
                sVibrator = null;
            } else if (context != null) {
                android.os.Vibrator vib = (android.os.Vibrator) context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null) vib.cancel();
            }
        } catch (Throwable ignored) { }
    }

    public static void showIncomingCall(Context context, CallInfo info) {
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || info == null) return;
        try {
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

            startRinging(context);

            Intent fullScreen = buildCallIntent(context, info, false, false);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 100, fullScreen,
                    pendingFlags());

            Intent decline = buildCallIntent(context, info, true, false);
            decline.setAction(ACTION_DECLINE);
            PendingIntent declineIntent = PendingIntent.getActivity(context, 101, decline,
                    pendingFlags());

            Intent answer = buildCallIntent(context, info, false, true);
            answer.setAction(ACTION_ANSWER);
            PendingIntent answerIntent = PendingIntent.getActivity(context, 102, answer,
                    pendingFlags());

            String title = info.isGroup() && info.getGroupName() != null
                    ? info.getGroupName() : info.getPeerName();
            String text = info.getType() == CallType.VIDEO
                    ? "Incoming video call\u2022 E2EE" : "Incoming voice call \u2022 E2EE";

            Notification notification = new NotificationCompat.Builder(context, CHANNEL_INCOMING)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setSound(null)
                    .setVibrate(new long[]{0L})
                    .setContentIntent(contentIntent)
                    .setFullScreenIntent(contentIntent, true)
                    .addAction(R.drawable.ic_check, "Answer", answerIntent)
                    .addAction(0, "Decline", declineIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build();
            manager.notify(ID_INCOMING, notification);
            if (!CallActivity.isCallActive) {
                launchCallActivity(context, info, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "showIncomingCall failed: " + t.getMessage());
        }
    }

    public static void showOngoingCall(Context context, CallInfo info) {
        stopRinging(context);
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || info == null) return;
        try {
            manager.cancel(ID_INCOMING);
            manager.cancel(ID_LEGACY_WEBRTC);
            PendingIntent contentIntent = PendingIntent.getActivity(context, 104,
                    buildCallIntent(context, info, false, false), pendingFlags());
            Intent hangupIntent = new Intent(context, VeilCallService.class)
                    .setAction(VeilCallService.ACTION_HANGUP);
            PendingIntent hangupPending = PendingIntent.getService(context, 105, hangupIntent, pendingFlags());
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
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent)
                    .addAction(0, "End Call", hangupPending)
                    .build();
            manager.notify(ID_ONGOING, notification);
        } catch (Throwable t) {
            Log.w(TAG, "showOngoingCall failed: " + t.getMessage());
        }
    }

    /** A call that rang while this device was offline. Never full-screen. */
    public static void showMissedCall(Context context, String peerName, boolean video) {
        stopRinging(context);
        ensureChannels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            manager.cancel(ID_INCOMING);
            manager.cancel(ID_LEGACY_WEBRTC);
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
        stopRinging(context);
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(ID_INCOMING);
                manager.cancel(ID_LEGACY_WEBRTC);
            }
        } catch (Throwable ignored) { }
    }

    public static void cancelAll(Context context) {
        stopRinging(context);
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(ID_INCOMING);
                manager.cancel(ID_ONGOING);
                manager.cancel(ID_LEGACY_WEBRTC);
            }
        } catch (Throwable ignored) { }
    }

    public static Intent buildCallIntent(Context context, CallInfo info, boolean decline) {
        return buildCallIntent(context, info, decline, false);
    }

    public static Intent buildCallIntent(Context context, CallInfo info, boolean decline, boolean answer) {
        Intent intent = new Intent(context, CallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (info != null) {
            intent.putExtra(CallActivity.EXTRA_CONTACT_ID, info.getPeerId());
            intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, info.getPeerName());
            intent.putExtra(CallActivity.EXTRA_CALL_TYPE,
                    info.getType() == CallType.VIDEO ? "video" : "audio");
            intent.putExtra(CallActivity.EXTRA_CALL_ID, info.getCallId());
            intent.putExtra(CallActivity.EXTRA_IS_INCOMING, !info.isCaller());
            intent.putExtra(CallActivity.EXTRA_FROM_NOTIFICATION, true);
        }
        if (decline) intent.putExtra(CallActivity.EXTRA_DECLINE_ON_START, true);
        if (answer) intent.putExtra(CallActivity.EXTRA_ANSWER_ON_START, true);
        return intent;
    }

    public static void launchCallActivity(Context context, CallInfo info, boolean decline) {
        try {
            context.startActivity(buildCallIntent(context, info, decline, false));
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
