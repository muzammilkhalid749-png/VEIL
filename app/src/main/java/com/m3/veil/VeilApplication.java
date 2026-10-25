package com.m3.veil;

import android.app.Application;

import com.m3.veil.utils.UserPreferences;
import com.m3.veil.utils.VeilBufferPool;
import com.m3.veil.utils.VeilPerformanceController;

public class VeilApplication extends Application {

    private static VeilApplication instance;
    private UserPreferences userPreferences;

    // Telegram-Grade Shared RecycledViewPool across MainActivity, HomeFragment, and ChatActivity
    private static final androidx.recyclerview.widget.RecyclerView.RecycledViewPool SHARED_RECYCLED_VIEW_POOL =
            new androidx.recyclerview.widget.RecyclerView.RecycledViewPool();

    static {
        SHARED_RECYCLED_VIEW_POOL.setMaxRecycledViews(0, 35); // Dialog cell / Sent message
        SHARED_RECYCLED_VIEW_POOL.setMaxRecycledViews(1, 35); // Received message
        SHARED_RECYCLED_VIEW_POOL.setMaxRecycledViews(2, 20); // Notice / Media
    }

    public static androidx.recyclerview.widget.RecyclerView.RecycledViewPool getSharedRecycledViewPool() {
        return SHARED_RECYCLED_VIEW_POOL;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        userPreferences = new UserPreferences(this);

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

        // Initialize WebRTC & FCM session if user is logged in
        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
            com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(this,
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser());
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
        SHARED_RECYCLED_VIEW_POOL.clear();
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
        SHARED_RECYCLED_VIEW_POOL.clear();
        try {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).trimMemory(TRIM_MEMORY_COMPLETE);
        } catch (Throwable ignored) { }
    }

    public static VeilApplication getInstance() {
        return instance;
    }

    public UserPreferences getUserPreferences() {
        return userPreferences;
    }
}