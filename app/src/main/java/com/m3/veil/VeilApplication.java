package com.m3.veil;

import android.app.Application;

import com.m3.veil.utils.UserPreferences;
import com.m3.veil.utils.VeilBufferPool;
import com.m3.veil.utils.VeilPerformanceController;

public class VeilApplication extends Application {

    private static VeilApplication instance;
    private UserPreferences userPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        userPreferences = new UserPreferences(this);

        // Initialize user-configured Night / Light / System Theme Mode
        try {
            android.content.SharedPreferences appPrefs = getSharedPreferences("veil_appearance_prefs", MODE_PRIVATE);
            int themeMode = appPrefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode);
        } catch (Throwable ignored) {}

        // Telegram-grade Hardware Profiler & Auto-Adaptive Performance Controller
        VeilPerformanceController.init(this);

        // Runtime Anti-Tamper, Signature & Hooking Guard
        com.m3.veil.utils.VeilRuntimeIntegrityGuard.verifyRuntimeIntegrity(this);

        // Telegram-grade real-time presence & lifecycle tracker
        com.m3.veil.manager.PresenceManager.getInstance().init(this);

        // Preload encrypted SQLCipher database vault
        try {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this);
            com.m3.veil.services.VeilSchedulerDaemon.getInstance(this).start();
        } catch (Exception e) {
            android.util.Log.e("VeilApplication", "SQLCipher init error", e);
        }

        // Initialize WebRTC, Call Engine & Mesh Daemon session if user is logged in (Firebase or Sovereign)
        if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(this)) {
            String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
            if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(this,
                        com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser());
            } else {
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getLocalManifest();
                if (manifest != null) {
                    com.m3.veil.manager.AuthManager.getInstance().onSovereignSessionStarted(this, manifest);
                } else if (uid != null) {
                    com.m3.veil.webrtc.WebRtcManager.getInstance(this).init(uid);
                    try {
                        com.m3.veil.calling.CallEngine.getInstance(this).init(uid);
                    } catch (Throwable ignored) {}
                }
            }
            com.m3.veil.services.VeilMeshDaemonService.start(this);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        VeilBufferPool.trimMemory(level);
        com.m3.veil.utils.BlurUtils.clearCache();
        com.m3.veil.ui.views.VeilDialogCell.clearAvatarCache();
        com.m3.veil.manager.VeilMessagesController.clearStaticLayoutCache();
        try {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).trimMemory(level);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        VeilBufferPool.clear();
        com.m3.veil.utils.BlurUtils.clearCache();
        com.m3.veil.ui.views.VeilDialogCell.clearAvatarCache();
        com.m3.veil.manager.VeilMessagesController.clearStaticLayoutCache();
        try {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).trimMemory(TRIM_MEMORY_COMPLETE);
        } catch (Throwable ignored) { }
    }

    private static final androidx.recyclerview.widget.RecyclerView.RecycledViewPool sharedRecycledViewPool = new androidx.recyclerview.widget.RecyclerView.RecycledViewPool();

    public static androidx.recyclerview.widget.RecyclerView.RecycledViewPool getSharedRecycledViewPool() {
        return sharedRecycledViewPool;
    }

    public static VeilApplication getInstance() {
        return instance;
    }

    public UserPreferences getUserPreferences() {
        return userPreferences;
    }
}