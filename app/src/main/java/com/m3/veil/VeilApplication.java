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

        // Offload heavy SQLCipher, WebRTC, and CallEngine initialization to background boot worker
        // so Application.onCreate (AppBindData) completes in <15ms instead of blocking the main thread for 3,031ms.
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            try {
                com.m3.veil.ui.chat.adapters.MessagesAdapter.prewarmViewPool(VeilApplication.this);
                com.m3.veil.database.EncryptedChatDatabase.getInstance(VeilApplication.this);
                com.m3.veil.manager.VeilMessagesController.getInstance(VeilApplication.this);
                com.m3.veil.services.VeilSchedulerDaemon.getInstance(VeilApplication.this).start();
            } catch (Exception e) {
                android.util.Log.e("VeilApplication", "SQLCipher init error", e);
            }

            try {
                if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(VeilApplication.this)) {
                    String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(VeilApplication.this);
                    if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                        com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(VeilApplication.this,
                                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser());
                    } else {
                        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(VeilApplication.this).getLocalManifest();
                        if (manifest != null) {
                            com.m3.veil.manager.AuthManager.getInstance().onSovereignSessionStarted(VeilApplication.this, manifest);
                        } else if (uid != null) {
                            com.m3.veil.webrtc.WebRtcManager.getInstance(VeilApplication.this).init(uid);
                            try {
                                com.m3.veil.calling.CallEngine.getInstance(VeilApplication.this).init(uid);
                            } catch (Throwable ignored) {}
                        }
                    }
                    com.m3.veil.services.VeilMeshDaemonService.start(VeilApplication.this);
                }
            } catch (Throwable t) {
                android.util.Log.w("VeilApplication", "Background session boot warning: " + t.getMessage());
            }
        }, "VeilBootWorker").start();
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
    private static final androidx.recyclerview.widget.RecyclerView.RecycledViewPool sharedMessagesRecycledViewPool = new androidx.recyclerview.widget.RecyclerView.RecycledViewPool();
    private static volatile boolean messagesPoolPrewarmed = false;

    static {
        sharedMessagesRecycledViewPool.setMaxRecycledViews(com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_NOTICE, 10);
        sharedMessagesRecycledViewPool.setMaxRecycledViews(com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_SENT, 40);
        sharedMessagesRecycledViewPool.setMaxRecycledViews(com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_RECEIVED, 40);
        sharedMessagesRecycledViewPool.setMaxRecycledViews(com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_TYPING, 5);
    }

    public static androidx.recyclerview.widget.RecyclerView.RecycledViewPool getSharedRecycledViewPool() {
        return sharedRecycledViewPool;
    }

    public static androidx.recyclerview.widget.RecyclerView.RecycledViewPool getSharedMessagesRecycledViewPool() {
        return sharedMessagesRecycledViewPool;
    }

    /**
     * Pre-inflates MessagesAdapter ViewHolders during UI idle frames (Telegram RecyclerListView pool warm-up)
     * so opening ChatActivity requires 0ms XML layout inflation on Frame 1.
     */
    public static void prewarmMessageViewHolders(final android.content.Context context) {
        if (context == null) return;
        com.m3.veil.ui.chat.adapters.MessagesAdapter.prewarmViewPool(context);
    }

    public static VeilApplication getInstance() {
        return instance;
    }

    public UserPreferences getUserPreferences() {
        return userPreferences;
    }
}