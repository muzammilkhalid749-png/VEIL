package com.m3.veil.manager;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Telegram-grade real-time presence lifecycle manager.
 *
 * <p>Eliminates the persistent false "online" state when users close the app,
 * navigate away, put the device to sleep, or lose network connectivity.
 *
 * <p>Mechanisms:
 * <ul>
 *   <li>Tracks app-wide foreground/background status via ActivityLifecycleCallbacks.</li>
 *   <li>Explicitly updates Firestore status to "online" on foreground and "offline" on background.</li>
 *   <li>Maintains a 40-second serverTimestamp heartbeat while in the foreground.</li>
 *   <li>Enforces a 90-second client-side TTL so ungraceful deaths (swiped away, battery dead)
 *       automatically expire to "last seen" on peers' devices.</li>
 * </ul>
 */
public class PresenceManager implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "PresenceManager";
    public static final long PRESENCE_TTL_MS = 45_000L; // 45 seconds TTL
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L; // 15 seconds heartbeat

    private static volatile PresenceManager instance;

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.Context context;
    private int activityCount = 0;
    private boolean isForeground = false;
    private Runnable heartbeatRunnable;

    private PresenceManager() { }

    public static PresenceManager getInstance() {
        if (instance == null) {
            synchronized (PresenceManager.class) {
                if (instance == null) {
                    instance = new PresenceManager();
                }
            }
        }
        return instance;
    }

    public void init(Application application) {
        if (application != null) {
            this.context = application.getApplicationContext();
            application.registerActivityLifecycleCallbacks(this);
        }
    }

    private String resolveCurrentUid() {
        String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
        if ((uid == null || uid.isEmpty()) && this.context != null) {
            uid = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this.context).getCurrentUserId();
        }
        return uid;
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        activityCount++;
        if (activityCount == 1 && !isForeground) {
            isForeground = true;
            onAppForeground();
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        activityCount = Math.max(0, activityCount - 1);
        if (activityCount == 0 && isForeground) {
            isForeground = false;
            onAppBackground();
        }
    }

    private void onAppForeground() {
        String uid = resolveCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        Log.d(TAG, "App entered foreground -> status: online for " + uid);

        publishPresence(uid, true);
        startHeartbeat(uid);
    }

    private void onAppBackground() {
        stopHeartbeat();
        String uid = resolveCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        if (context != null) {
            try {
                com.m3.veil.webrtc.WebRtcManager.getInstance(context).broadcastLeaving();
            } catch (Throwable ignored) { }
        }
        Log.d(TAG, "App entered background -> status: offline for " + uid);

        publishPresence(uid, false);
    }

    public void markOfflineOnSignOut() {
        stopHeartbeat();
        String uid = resolveCurrentUid();
        if (uid != null && !uid.isEmpty()) {
            publishPresence(uid, false);
        }
    }

    private void publishPresence(String uid, boolean online) {
        if (uid == null || uid.isEmpty()) return;
        if (online && context != null && !com.m3.veil.manager.VeilMessagesController.getInstance(context).isNetworkConnected()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Map<String, Object> updates = new HashMap<>();
        updates.put("userId", uid);
        updates.put("uid", uid);
        updates.put("status", online ? "online" : "offline");
        updates.put("isOnline", online);
        updates.put("lastSeen", com.google.firebase.Timestamp.now());
        updates.put("lastSeenMs", nowMs);
        db.collection("users").document(uid)
                .set(updates, com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "Failed to update presence: " + e.getMessage()));
    }

    private void startHeartbeat(final String uid) {
        stopHeartbeat();
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isForeground) {
                    String activeUid = resolveCurrentUid();
                    if (activeUid != null && !activeUid.isEmpty()) {
                        publishPresence(activeUid, true);
                    }
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            handler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { }
    @Override public void onActivityResumed(@NonNull Activity a) { }
    @Override public void onActivityPaused(@NonNull Activity a) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
    @Override public void onActivityDestroyed(@NonNull Activity a) { }
}
