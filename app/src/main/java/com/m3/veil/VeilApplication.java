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

        // Preload encrypted SQLCipher database vault & warm VeilMessagesController RAM cache
        try {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this);
            com.m3.veil.manager.VeilMessagesController.getInstance(this);
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
        if (messagesPoolPrewarmed || context == null) return;
        messagesPoolPrewarmed = true;
        android.os.Looper.myQueue().addIdleHandler(new android.os.MessageQueue.IdleHandler() {
            private int step = 0;

            @Override
            public boolean queueIdle() {
                try {
                    android.widget.FrameLayout dummyParent = new android.widget.FrameLayout(context);
                    com.m3.veil.ui.chat.adapters.MessagesAdapter dummyAdapter =
                            new com.m3.veil.ui.chat.adapters.MessagesAdapter(new java.util.ArrayList<>(), null, null);
                    if (step == 0) {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder noticeVh =
                                dummyAdapter.createViewHolder(dummyParent, com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_NOTICE);
                        sharedMessagesRecycledViewPool.putRecycledView(noticeVh);
                    } else if (step <= 6) {
                        androidx.recyclerview.widget.RecyclerView.ViewHolder sentVh =
                                dummyAdapter.createViewHolder(dummyParent, com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_SENT);
                        sharedMessagesRecycledViewPool.putRecycledView(sentVh);
                        androidx.recyclerview.widget.RecyclerView.ViewHolder recvVh =
                                dummyAdapter.createViewHolder(dummyParent, com.m3.veil.ui.chat.adapters.MessagesAdapter.VIEW_TYPE_RECEIVED);
                        sharedMessagesRecycledViewPool.putRecycledView(recvVh);
                    } else {
                        return false;
                    }
                    step++;
                    return step <= 6;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        });
    }

    public static VeilApplication getInstance() {
        return instance;
    }

    public UserPreferences getUserPreferences() {
        return userPreferences;
    }
}