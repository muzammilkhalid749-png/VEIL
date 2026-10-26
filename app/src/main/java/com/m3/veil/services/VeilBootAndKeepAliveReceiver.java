package com.m3.veil.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.m3.veil.calling.CallEngine;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.webrtc.WebRtcManager;

/**
 * High-Priority System Boot & Keep-Alive Broadcast Receiver for VEIL.
 *
 * Listens for:
 * - Intent.ACTION_BOOT_COMPLETED
 * - Intent.ACTION_MY_PACKAGE_REPLACED
 * - "android.intent.action.QUICKBOOT_POWERON"
 * - "com.htc.intent.action.QUICKBOOT_POWERON"
 *
 * On device boot or package upgrade:
 * 1. Verifies sovereign or authenticated session existence.
 * 2. Starts VeilMeshDaemonService background daemon.
 * 3. Brings up WebRtcManager and CallEngine WebSocket signaling listeners
 *    so incoming peer calls, messages, and mesh wake-knocks are received instantly.
 */
public class VeilBootAndKeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "VeilBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast intent: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            try {
                Context appContext = context.getApplicationContext();
                AuthManager authManager = AuthManager.getInstance();

                if (authManager.isLoggedIn(appContext)) {
                    String uid = authManager.getCurrentUserId(appContext);
                    Log.i(TAG, "Bootstrapping background mesh daemon and signaling for user: " + uid);

                    // 1. Start background Mesh Daemon Service
                    VeilMeshDaemonService.start(appContext);

                    // 2. Start Scheduler Daemon
                    try {
                        VeilSchedulerDaemon.getInstance(appContext).start();
                    } catch (Throwable t) {
                        Log.w(TAG, "VeilSchedulerDaemon notice on boot: " + t.getMessage());
                    }

                    // 3. Bring up WebRTC & Call Signaling
                    if (uid != null && !uid.isEmpty()) {
                        try {
                            WebRtcManager.getInstance(appContext).init(uid);
                        } catch (Throwable t) {
                            Log.w(TAG, "WebRtcManager notice on boot: " + t.getMessage());
                        }

                        try {
                            CallEngine.getInstance(appContext).init(uid);
                        } catch (Throwable t) {
                            Log.w(TAG, "CallEngine notice on boot: " + t.getMessage());
                        }

                        // 4. Ensure WebSocket signaling listeners attached
                        try {
                            SovereignIdentityAndDirectoryEngine.getInstance(appContext)
                                    .subscribeToIncomingSignals(uid, (type, fromUid, payload) -> {
                                        Log.d(TAG, "Boot receiver signal channel active: " + type);
                                    });
                        } catch (Throwable t) {
                            Log.w(TAG, "Signal channel notice on boot: " + t.getMessage());
                        }
                    }
                } else {
                    Log.d(TAG, "No active user session detected on boot - skipping daemon auto-start");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in VeilBootAndKeepAliveReceiver", t);
            }
        }
    }
}
