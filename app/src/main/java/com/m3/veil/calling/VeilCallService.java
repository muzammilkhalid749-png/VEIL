package com.m3.veil.calling;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.m3.veil.R;
import com.m3.veil.ui.chat.CallActivity;

import java.util.Locale;

/**
 * Production-grade VoIP Foreground Service ported from Telegram
 * (org.telegram.messenger.voip.VoIPService).
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Owns the background call lifecycle and keeps the process active on Android 14+</li>
 *   <li>Holds a CPU {@link PowerManager.WakeLock} so audio never drops during screen off</li>
 *   <li>Updates status-bar notification with live call duration and quick action buttons (Hang Up, Mute, Speaker)</li>
 *   <li>Bridges Android Telecom ConnectionService integration</li>
 * </ul>
 */
public class VeilCallService extends Service implements CallEngine.Listener {

    private static final String TAG = "VeilCallService";
    private static final int NOTIFICATION_ID = CallNotificationHelper.ID_ONGOING;

    public static final String ACTION_START = "com.m3.veil.calling.action.START";
    public static final String ACTION_STOP = "com.m3.veil.calling.action.STOP";
    public static final String ACTION_HANGUP = "com.m3.veil.calling.action.HANGUP";
    public static final String ACTION_TOGGLE_MUTE = "com.m3.veil.calling.action.TOGGLE_MUTE";
    public static final String ACTION_TOGGLE_SPEAKER = "com.m3.veil.calling.action.TOGGLE_SPEAKER";

    private static volatile VeilCallService sharedInstance;

    private PowerManager.WakeLock wakeLock;
    private CallEngine engine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isTickerRunning = false;

    public static VeilCallService getSharedInstance() {
        return sharedInstance;
    }

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, VeilCallService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, VeilCallService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Throwable ignored) { }
    }

    public static void update(Context context, CallInfo info) {
        VeilCallService svc = sharedInstance;
        if (svc != null) {
            svc.updateOngoingNotification(info);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sharedInstance = this;
        acquireWakeLock();
        VeilConnectionService.registerAccount(this);

        engine = CallEngine.getInstance(this);
        if (engine != null) {
            engine.addListener(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CallNotificationHelper.ensureChannels(this);

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_HANGUP:
                    if (engine != null) engine.hangup();
                    stopSelf();
                    return START_NOT_STICKY;
                case ACTION_TOGGLE_MUTE:
                    if (engine != null) engine.setMuted(!engine.isMuted());
                    updateNotificationFromEngine();
                    return START_NOT_STICKY;
                case ACTION_TOGGLE_SPEAKER:
                    if (engine != null) engine.setSpeakerOn(!engine.isSpeakerOn());
                    updateNotificationFromEngine();
                    return START_NOT_STICKY;
                case ACTION_STOP:
                    stopForegroundAndService();
                    return START_NOT_STICKY;
                default:
                    break;
            }
        }

        startForegroundWithNotification();
        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification() {
        try {
            Notification notification = buildNotification(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                boolean isVideo = engine != null && engine.isVideoEnabled();
                int type = computeForegroundServiceType(isVideo);
                if (type != 0) {
                    startForeground(NOTIFICATION_ID, notification, type);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground primary failed, trying fallback: " + t.getMessage());
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification(null));
                }
            } catch (Throwable ignored) {}
        }
    }

    private int computeForegroundServiceType(boolean isVideo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0;
        int type = 0;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isVideo) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
        }
        if (engine != null && engine.isScreenSharing()) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        if (type == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        return type;
    }

    private Notification buildNotification(@Nullable CallInfo info) {
        String title = "Veil Call";
        String statusText = "Call in progress";
        long durationMs = 0L;
        boolean isE2ee = false;

        if (info != null) {
            title = info.isGroup() && info.getGroupName() != null
                    ? info.getGroupName() : (info.getPeerName() != null ? info.getPeerName() : "Veil Call");
            durationMs = info.getDurationMs();
            isE2ee = info.isE2eeEnabled();
        } else if (engine != null && engine.getInfo() != null) {
            CallInfo current = engine.getInfo();
            title = current.isGroup() && current.getGroupName() != null
                    ? current.getGroupName() : (current.getPeerName() != null ? current.getPeerName() : "Veil Call");
            durationMs = current.getDurationMs();
            isE2ee = current.isE2eeEnabled();
        }

        if (durationMs > 0) {
            long seconds = durationMs / 1000L;
            statusText = String.format(Locale.US, "%02d:%02d • %s",
                    seconds / 60, seconds % 60, isE2ee ? "E2EE" : "Protected");
        }

        // Tap notification to return to CallActivity
        Intent callIntent = new Intent(this, CallActivity.class);
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(this, 201, callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        // Action: Hang Up
        Intent hangupIntent = new Intent(this, VeilCallService.class).setAction(ACTION_HANGUP);
        PendingIntent hangupPending = PendingIntent.getService(this, 202, hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        // Action: Toggle Mute
        Intent muteIntent = new Intent(this, VeilCallService.class).setAction(ACTION_TOGGLE_MUTE);
        PendingIntent mutePending = PendingIntent.getService(this, 203, muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        boolean isMuted = engine != null && engine.isMuted();

        return new NotificationCompat.Builder(this, CallNotificationHelper.CHANNEL_ONGOING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(statusText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(contentPending)
                .addAction(0, isMuted ? "Unmute" : "Mute", mutePending)
                .addAction(0, "End Call", hangupPending)
                .build();
    }

    public void updateOngoingNotification(CallInfo info) {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(info));
            }
        } catch (Throwable ignored) { }
    }

    private void updateNotificationFromEngine() {
        if (engine != null) {
            updateOngoingNotification(engine.getInfo());
        }
    }

    private final Runnable tickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTickerRunning) {
                updateNotificationFromEngine();
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private void startDurationTicker() {
        if (isTickerRunning) return;
        isTickerRunning = true;
        mainHandler.post(tickerRunnable);
    }

    private void stopDurationTicker() {
        isTickerRunning = false;
        mainHandler.removeCallbacks(tickerRunnable);
    }

    // ---- CallEngine.Listener -------------------------------------------------

    @Override
    public void onStateChanged(CallState state, CallInfo info) {
        if (state == CallState.ACTIVE) {
            startDurationTicker();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    boolean isVideo = (info != null ? info.getType() == CallType.VIDEO : (engine != null && engine.isVideoEnabled()));
                    int type = computeForegroundServiceType(isVideo);
                    startForeground(NOTIFICATION_ID, buildNotification(info), type);
                } catch (Throwable ignored) { }
            }
        } else if (state.isEnded()) {
            stopDurationTicker();
            stopForegroundAndService();
        }
        updateOngoingNotification(info);
    }

    @Override
    public void onCallInfoChanged(CallInfo info) {
        updateOngoingNotification(info);
    }

    @Override
    public void onCallEnded(CallEndReason reason, CallInfo info) {
        stopDurationTicker();
        stopForegroundAndService();
    }

    private void stopForegroundAndService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) { }
        stopSelf();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veil:call_service_wake");
                    wakeLock.acquire(60 * 60 * 1000L); // 1 hour max safeguard
                }
            } catch (Throwable t) {
                Log.w(TAG, "acquireWakeLock failed: " + t.getMessage());
            }
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) { }
        wakeLock = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDurationTicker();
        if (engine != null) {
            engine.removeListener(this);
        }
        releaseWakeLock();
        sharedInstance = null;
        CallNotificationHelper.cancelAll(this);
    }
}
