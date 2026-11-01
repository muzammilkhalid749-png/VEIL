package com.m3.veil.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Manages battery optimization exemptions for VEIL.
 * Ensures serverless P2P WebRTC data channels and background call signaling
 * remain active and responsive across aggressive OEM battery managers.
 */
public class VeilBatteryOptimizationHelper {

    private static final String TAG = "VeilBatteryOpt";
    private static final String PREF_NAME = "veil_battery_prefs";
    private static final String KEY_PROMPTED = "battery_opt_prompted";

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || context == null) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "Failed checking battery optimization status: " + t.getMessage());
            return true;
        }
    }

    public static void promptExemptionIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PROMPTED, false)) {
            return;
        }

        if (isIgnoringBatteryOptimizations(activity)) {
            prefs.edit().putBoolean(KEY_PROMPTED, true).apply();
            return;
        }

        showPromptDialog(activity, false);
    }

    public static void showPromptDialog(Activity activity, boolean force) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Unrestricted Background Activity")
                .setMessage("VEIL relies on serverless P2P WebRTC data channels and real-time mesh routing. To ensure instant message delivery and incoming call ringing when your screen is off, please allow VEIL to run in the background without battery restrictions.")
                .setPositiveButton("Allow Unrestricted", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_PROMPTED, true).apply();
                    requestExemption(activity);
                })
                .setNegativeButton("Not Now", (dialog, which) -> {
                    prefs.edit().putBoolean(KEY_PROMPTED, true).apply();
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    public static void requestExemption(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Direct exemption request failed, opening battery settings: " + t.getMessage());
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                if (!(context instanceof Activity)) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(fallback);
            } catch (Throwable ignored) { }
        }
    }
}
