package com.m3.veil.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.ArchivedChatsActivity;
import com.m3.veil.ui.chat.CallActivity;
import com.m3.veil.ui.chat.NewChatActivity;
import com.m3.veil.ui.chat.NewGroupActivity;
import com.m3.veil.ui.chat.SearchActivity;
import com.m3.veil.ui.main.CreatePostActivity;
import com.m3.veil.ui.main.fragments.CallsFragment;
import com.m3.veil.ui.main.fragments.DiscoverFragment;
import com.m3.veil.ui.main.fragments.HomeFragment;
import com.m3.veil.ui.main.fragments.ProfileFragment;
import com.m3.veil.webrtc.WebRtcManager;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.widget.PopupMenu;
import android.animation.ObjectAnimator;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;
import com.m3.veil.calling.CallEngine;
import com.m3.veil.calling.CallInfo;
import com.m3.veil.calling.CallState;
import com.m3.veil.calling.CallEndReason;
import com.m3.veil.calling.CallNotificationHelper;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private FloatingActionButton fabNewMessage;
    private FloatingActionButton fabStatusPencil;
    private View bottomNavContainer;
    private boolean isBottomNavVisible = true;
    private boolean isStatusFilterActiveOnHome = false;

    private View appLogo;
    private TextView toolbarTitle;
    private ImageView searchButton;
    private ImageView newChatButton;
    private ImageView moreButton;

    private View appBarCallMicroPill;
    private View pillPulseDot;
    private TextView tvCallPillText;
    private ObjectAnimator dotPulseAnimator;
    private Handler callPillHandler;
    private Runnable callPillTicker;

    private final CallEngine.Listener callEngineListener = new CallEngine.Listener() {
        @Override
        public void onStateChanged(CallState state, CallInfo info) {
            runOnUiThread(() -> updateCallMicroPill());
        }

        @Override
        public void onCallInfoChanged(CallInfo info) {
            runOnUiThread(() -> updateCallMicroPill());
        }

        @Override
        public void onCallEnded(CallEndReason reason, CallInfo info) {
            runOnUiThread(() -> updateCallMicroPill());
        }
    };

    private final WebRtcManager.P2PEventListener globalCallListener = new WebRtcManager.P2PEventListener() {
        @Override
        public void onMessageReceived(String peerId, Message message) {}

        @Override
        public void onMessageStatusChanged(String messageId, boolean delivered, boolean read) {}

        @Override
        public void onPeerTyping(String peerId, boolean isTyping) {}

        @Override
        public void onPeerConnected(String peerId) {}

        @Override
        public void onPeerDisconnected(String peerId) {}

        @Override
        public void onCallEstablished(String peerId) {}

        @Override
        public void onCallEnded(String peerId) {}

        @Override
        public void onIncomingCall(String peerId, String peerName, String callType) {}

        @Override
        public void onIncomingCall(String peerId, String peerName, String callType, String callId) {
            // Incoming CallActivity launch and heads-up notification is handled centrally
            // by WebRtcManager to avoid duplicate overlapping activities.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        initializeViews();
        handleWindowInsets();
        setupViewPager();
        setupBottomNavigation();
        setupClickListeners();
        updateToolbarForTab(0);

        com.m3.veil.utils.UiAnimator.attachSpringPress(fabNewMessage);
        if (searchButton != null) com.m3.veil.utils.UiAnimator.attachSpringPress(searchButton);
        if (newChatButton != null) com.m3.veil.utils.UiAnimator.attachSpringPress(newChatButton);
        if (moreButton != null) com.m3.veil.utils.UiAnimator.attachSpringPress(moreButton);

        // Update daily login/activity streak
        VeilFeatureManager.getInstance(this).updateDailyStreak();

        // Request Notification Permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Defer P2P WebRTC & Auth Session initialization to a background worker after Frame 1 renders
        android.os.Looper.myQueue().addIdleHandler(() -> {
            com.m3.veil.utils.VeilBatteryOptimizationHelper.promptExemptionIfNeeded(MainActivity.this);
            com.m3.veil.utils.VeilMediaCacheManager.autoPruneCache(MainActivity.this);
            new Thread(() -> {
                try {
                    com.m3.veil.ui.chat.adapters.MessagesAdapter.prewarmViewPool(MainActivity.this);
                    if (!isDestroyed() && com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(MainActivity.this)) {
                        String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(MainActivity.this);
                        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                            com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(MainActivity.this,
                                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser());
                        } else {
                            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(MainActivity.this).getLocalManifest();
                            if (manifest != null) {
                                com.m3.veil.manager.AuthManager.getInstance().onSovereignSessionStarted(MainActivity.this, manifest);
                            } else if (uid != null) {
                                com.m3.veil.webrtc.WebRtcManager.getInstance(MainActivity.this).init(uid);
                            }
                        }
                        com.m3.veil.services.VeilMeshDaemonService.start(MainActivity.this);
                    }
                    WebRtcManager.getInstance(MainActivity.this).addEventListener(globalCallListener);
                } catch (Throwable ignored) {}
            }, "VeilMainSessionWorker").start();
            return false;
        });

        // Handle incoming notification intent
        handleNotificationIntent(getIntent());

        // Fetch and save FCM Token for wake-up signaling only
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) return;
                    String token = task.getResult();
                    if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                        String userId = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
                        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(userId)
                                .update("fcmToken", token);
                    }
                });

        try {
            CallEngine.getInstance(this).addListener(callEngineListener);
        } catch (Throwable ignored) {}
    }

    private final com.m3.veil.manager.PersonaManager.PersonaSwitchListener personaSwitchListener = newActivePersona -> {
        if (!isDestroyed() && viewPager != null) {
            updateToolbarForTab(viewPager.getCurrentItem());
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        com.m3.veil.manager.PersonaManager.getInstance(this).addPersonaSwitchListener(personaSwitchListener);
        if (viewPager != null) {
            updateToolbarForTab(viewPager.getCurrentItem());
        }
        applyAppearancePreferences();
        updateCallMicroPill();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopCallPillTicker();
    }

    private void applyAppearancePreferences() {
        try {
            android.content.SharedPreferences appPrefs = getSharedPreferences("veil_appearance_prefs", MODE_PRIVATE);
            boolean amoled = appPrefs.getBoolean("amoled_pure_black", false);
            boolean isNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            View mainContainer = findViewById(R.id.main_container);
            if (mainContainer != null) {
                if (amoled && isNight) {
                    mainContainer.setBackgroundColor(android.graphics.Color.BLACK);
                } else {
                    mainContainer.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorBackground));
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.m3.veil.manager.PersonaManager.getInstance(this).removePersonaSwitchListener(personaSwitchListener);
        WebRtcManager.getInstance(this).removeEventListener(globalCallListener);
        try {
            CallEngine.getInstance(this).removeListener(callEngineListener);
        } catch (Throwable ignored) {}
        stopCallPillTicker();
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.UiAnimator.configureSystemBars(this);

        if (bottomNavigation != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation, (v, insets) -> insets);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_container), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int bottomInset = Math.max(systemBars.bottom, navBars.bottom);

            findViewById(R.id.appBarLayout).setPadding(systemBars.left, systemBars.top, systemBars.right, 0);

            if (bottomNavContainer != null) {
                bottomNavContainer.setPadding(systemBars.left, 0, systemBars.right, bottomInset);
            } else if (bottomNavigation != null) {
                bottomNavigation.setPadding(systemBars.left, 0, systemBars.right, bottomInset);
            }

            if (fabNewMessage != null && fabNewMessage.getLayoutParams() instanceof androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                        (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) fabNewMessage.getLayoutParams();
                int navHeight = (int) (56 * getResources().getDisplayMetrics().density);
                int fabSpacing = (int) (12 * getResources().getDisplayMetrics().density);
                lp.bottomMargin = navHeight + fabSpacing + bottomInset;
                fabNewMessage.setLayoutParams(lp);
                if (fabStatusPencil != null && fabStatusPencil.getLayoutParams() instanceof androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                    androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams pLp =
                            (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) fabStatusPencil.getLayoutParams();
                    pLp.bottomMargin = lp.bottomMargin + (int) (68 * getResources().getDisplayMetrics().density);
                    fabStatusPencil.setLayoutParams(pLp);
                }
            }
            return insets;
        });
    }

    private void initializeViews() {
        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        bottomNavContainer = findViewById(R.id.bottomNavContainer);
        fabNewMessage = findViewById(R.id.fabNewMessage);
        fabStatusPencil = findViewById(R.id.fabStatusPencil);
        if (fabStatusPencil != null) {
            com.m3.veil.utils.UiAnimator.attachSpringPress(fabStatusPencil);
        }
        appLogo = findViewById(R.id.appLogo);
        toolbarTitle = findViewById(R.id.toolbarTitle);
        searchButton = findViewById(R.id.searchButton);
        newChatButton = findViewById(R.id.newChatButton);
        moreButton = findViewById(R.id.moreButton);

        appBarCallMicroPill = findViewById(R.id.appBarCallMicroPill);
        pillPulseDot = findViewById(R.id.pillPulseDot);
        tvCallPillText = findViewById(R.id.tvCallPillText);
    }

    private void updateCallMicroPill() {
        if (isDestroyed() || isFinishing()) return;
        CallEngine engine = CallEngine.getInstance(this);
        if (engine == null || !engine.isInCall()) {
            if (appBarCallMicroPill != null) {
                appBarCallMicroPill.setVisibility(View.GONE);
            }
            stopCallPillTicker();
            return;
        }

        CallInfo info = engine.getCallInfo();
        if (info == null || info.getState() == null || info.getState().isEnded()) {
            if (appBarCallMicroPill != null) {
                appBarCallMicroPill.setVisibility(View.GONE);
            }
            stopCallPillTicker();
            return;
        }

        if (appBarCallMicroPill != null) {
            appBarCallMicroPill.setVisibility(View.VISIBLE);
            appBarCallMicroPill.setOnClickListener(v -> {
                CallNotificationHelper.launchCallActivity(MainActivity.this, engine.getCallInfo(), false);
            });
        }

        renderCallPillText(info);
        startCallPillTicker();
    }

    private void renderCallPillText(CallInfo info) {
        if (tvCallPillText == null || info == null) return;
        CallState st = info.getState();
        if (st == null || st.isEnded()) return;

        if (st == CallState.CALLING) {
            long started = info.getStartedAtMs();
            long elapsed = started > 0 ? (System.currentTimeMillis() - started) : 0L;
            long sec = Math.max(0, elapsed / 1000L);
            String timeStr = String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60);
            tvCallPillText.setText("Calling (" + timeStr + ")");
        } else if (st == CallState.PRE_ACCEPT_RECEIVED) {
            long started = info.getStartedAtMs();
            long elapsed = started > 0 ? (System.currentTimeMillis() - started) : 0L;
            long sec = Math.max(0, elapsed / 1000L);
            String timeStr = String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60);
            tvCallPillText.setText("Ringing (" + timeStr + ")");
        } else if (st == CallState.RECEIVED_CALL) {
            long started = info.getStartedAtMs();
            long elapsed = started > 0 ? (System.currentTimeMillis() - started) : 0L;
            long sec = Math.max(0, elapsed / 1000L);
            String timeStr = String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60);
            tvCallPillText.setText("Incoming (" + timeStr + ")");
        } else if (st.isEstablished()) {
            long connected = info.getConnectedAtMs();
            long elapsed = connected > 0 ? (System.currentTimeMillis() - connected) : info.getDurationMs();
            long sec = Math.max(0, elapsed / 1000L);
            String timeStr = String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60);
            tvCallPillText.setText("Ongoing (" + timeStr + ")");
        } else {
            tvCallPillText.setText("Connecting\u2026");
        }
    }

    private void startCallPillTicker() {
        if (callPillHandler == null) {
            callPillHandler = new Handler(Looper.getMainLooper());
        }
        if (callPillTicker == null) {
            callPillTicker = new Runnable() {
                @Override
                public void run() {
                    CallEngine engine = CallEngine.getInstance(MainActivity.this);
                    if (engine != null && engine.isInCall()) {
                        renderCallPillText(engine.getCallInfo());
                        if (callPillHandler != null) {
                            callPillHandler.postDelayed(this, 1000L);
                        }
                    } else {
                        stopCallPillTicker();
                        if (appBarCallMicroPill != null) {
                            appBarCallMicroPill.setVisibility(View.GONE);
                        }
                    }
                }
            };
            callPillHandler.postDelayed(callPillTicker, 1000L);
        }

        if (dotPulseAnimator == null && pillPulseDot != null) {
            dotPulseAnimator = ObjectAnimator.ofFloat(pillPulseDot, "alpha", 1.0f, 0.25f, 1.0f);
            dotPulseAnimator.setDuration(1200);
            dotPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            dotPulseAnimator.setRepeatMode(ObjectAnimator.RESTART);
            dotPulseAnimator.start();
        }
    }

    private void stopCallPillTicker() {
        if (callPillHandler != null && callPillTicker != null) {
            callPillHandler.removeCallbacks(callPillTicker);
            callPillTicker = null;
        }
        if (dotPulseAnimator != null) {
            dotPulseAnimator.cancel();
            dotPulseAnimator = null;
        }
        if (pillPulseDot != null) {
            pillPulseDot.setAlpha(1.0f);
        }
    }

    private void setupViewPager() {
        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(1);
        viewPager.setUserInputEnabled(false);
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            int targetPos = 0;
            if (itemId == R.id.navigation_home) {
                targetPos = 0;
            } else if (itemId == R.id.navigation_discover) {
                targetPos = 1;
            } else if (itemId == R.id.navigation_calls) {
                targetPos = 2;
            } else if (itemId == R.id.navigation_profile) {
                targetPos = 3;
            }
            if (viewPager.getCurrentItem() != targetPos) {
                viewPager.setCurrentItem(targetPos, false);
            }
            updateToolbarForTab(targetPos);
            updateFabForTab(targetPos);
            return true;
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                int navId = R.id.navigation_home;
                if (position == 1) navId = R.id.navigation_discover;
                else if (position == 2) navId = R.id.navigation_calls;
                else if (position == 3) navId = R.id.navigation_profile;

                if (bottomNavigation.getSelectedItemId() != navId) {
                    bottomNavigation.getMenu().findItem(navId).setChecked(true);
                }
                updateFabForTab(position);
                updateToolbarForTab(position);
            }
        });
    }

    private void setupClickListeners() {
        updateFabForTab(0);

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (viewPager != null && viewPager.getCurrentItem() != 0) {
                    viewPager.setCurrentItem(0, true);
                    if (bottomNavigation != null) {
                        bottomNavigation.setSelectedItemId(R.id.navigation_home);
                    }
                    return;
                }
                try {
                    androidx.fragment.app.Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f0");
                    if (currentFragment instanceof com.m3.veil.ui.main.fragments.HomeFragment) {
                        if (((com.m3.veil.ui.main.fragments.HomeFragment) currentFragment).handleBackPressed()) {
                            return;
                        }
                    }
                } catch (Exception ignored) {}
                moveTaskToBack(true);
            }
        });

        // Feature #107: Secret Inbox Gesture â€” Long-press toolbar opens Hidden Secret Vault
        android.view.View toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setOnLongClickListener(v -> {
                Intent intent = new Intent(this, ArchivedChatsActivity.class);
                intent.putExtra("mode", "secret");
                startActivity(intent);
                return true;
            });
        }
    }

    private void setToolbarTitleSafely(CharSequence newTitle) {
        if (toolbarTitle == null || newTitle == null) return;
        CharSequence current = toolbarTitle.getText();
        if (current != null && current.toString().contentEquals(newTitle)) {
            return;
        }
        if (toolbarTitle.isInLayout()) {
            toolbarTitle.post(() -> {
                if (toolbarTitle != null) {
                    toolbarTitle.setText(newTitle);
                }
            });
        } else {
            toolbarTitle.setText(newTitle);
        }
    }

    public void refreshActivePersonaToolbarBadge() {
        if (toolbarTitle != null && viewPager != null && viewPager.getCurrentItem() == 0) {
            setToolbarTitleSafely(getString(R.string.app_name));
        }
    }

    public void setBottomNavAndFabVisible(boolean visible) {
        if (isBottomNavVisible == visible) return;
        isBottomNavVisible = visible;
        if (bottomNavContainer == null) {
            bottomNavContainer = findViewById(R.id.bottomNavContainer);
        }
        if (bottomNavContainer == null) return;

        float targetY = visible ? 0f : (float) (bottomNavContainer.getHeight() + 150);
        bottomNavContainer.animate()
                .translationY(targetY)
                .setDuration(220)
                .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .start();

        if (fabNewMessage != null) {
            if (visible) {
                fabNewMessage.animate()
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .withStartAction(fabNewMessage::show)
                        .start();
            } else {
                fabNewMessage.animate()
                        .translationY(targetY)
                        .setDuration(220)
                        .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .withEndAction(fabNewMessage::hide)
                        .start();
            }
        }
        if (fabStatusPencil != null && isStatusFilterActiveOnHome && viewPager != null && viewPager.getCurrentItem() == 0) {
            if (visible) {
                fabStatusPencil.animate()
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .withStartAction(fabStatusPencil::show)
                        .start();
            } else {
                fabStatusPencil.animate()
                        .translationY(targetY)
                        .setDuration(220)
                        .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                        .withEndAction(fabStatusPencil::hide)
                        .start();
            }
        }
    }

    public void updateFabForTab(int position) {
        if (fabNewMessage == null) return;
        switch (position) {
            case 0: // Chats / Home (or Status when chipFilterStatus is active)
                HomeFragment hf = getHomeFragment();
                boolean statusActive = isStatusFilterActiveOnHome || (hf != null && "status".equals(hf.getActiveFilter()));
                if (statusActive) {
                    if (fabStatusPencil != null) {
                        fabStatusPencil.setOnClickListener(v -> {
                            HomeFragment h = getHomeFragment();
                            if (h != null) h.openTextStatusComposer();
                        });
                        fabStatusPencil.show();
                    }
                    fabNewMessage.setImageResource(R.drawable.ic_camera);
                    fabNewMessage.setContentDescription("Add Status");
                    fabNewMessage.setOnClickListener(v -> {
                        HomeFragment h = getHomeFragment();
                        if (h != null) h.openStatusCreationChooser();
                    });
                    fabNewMessage.show();
                } else {
                    if (fabStatusPencil != null) fabStatusPencil.hide();
                    fabNewMessage.setImageResource(R.drawable.ic_edit);
                    fabNewMessage.setContentDescription("New Chat");
                    fabNewMessage.setOnClickListener(v -> startActivity(new Intent(this, NewChatActivity.class)));
                    fabNewMessage.show();
                }
                break;
            case 1: // Discover / Moments
                if (fabStatusPencil != null) fabStatusPencil.hide();
                fabNewMessage.setImageResource(R.drawable.ic_add);
                fabNewMessage.setContentDescription("Create Moment");
                fabNewMessage.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));
                fabNewMessage.show();
                break;
            case 2: // Calls
                if (fabStatusPencil != null) fabStatusPencil.hide();
                fabNewMessage.setImageResource(R.drawable.ic_call);
                fabNewMessage.setContentDescription("New Call");
                fabNewMessage.setOnClickListener(v -> {
                    CallsFragment cf = getCallsFragment();
                    if (cf != null) {
                        cf.showCallHubMenu();
                    }
                });
                fabNewMessage.show();
                break;
            case 3: // Settings & Profile
            default:
                if (fabStatusPencil != null) fabStatusPencil.hide();
                fabNewMessage.hide();
                break;
        }
    }

    private void updateToolbarForTab(int position) {
        if (toolbarTitle == null) return;
        switch (position) {
            case 0: // Chats (Home)
                HomeFragment hf = getHomeFragment();
                if (hf != null && "status".equals(hf.getActiveFilter())) {
                    setStatusFilterActive(true);
                    return;
                }
                if (appLogo != null) appLogo.setVisibility(android.view.View.VISIBLE);
                setToolbarTitleSafely(getString(R.string.app_name));
                if (searchButton != null) {
                    searchButton.setVisibility(android.view.View.GONE);
                }
                if (newChatButton != null) {
                    newChatButton.setVisibility(android.view.View.GONE);
                }
                if (moreButton != null) {
                    moreButton.setVisibility(android.view.View.VISIBLE);
                    moreButton.setOnClickListener(this::showChatsTabMenu);
                }
                break;

            case 1: // Discover (Moments & Confessions)
                if (appLogo != null) appLogo.setVisibility(android.view.View.GONE);
                setToolbarTitleSafely("Discover");
                if (searchButton != null) {
                    searchButton.setVisibility(android.view.View.VISIBLE);
                    searchButton.setOnClickListener(v -> {
                        DiscoverFragment df = getDiscoverFragment();
                        if (df != null) df.showSearchVeilBoardDialog();
                    });
                }
                if (newChatButton != null) {
                    newChatButton.setVisibility(android.view.View.GONE);
                }
                if (moreButton != null) {
                    moreButton.setVisibility(android.view.View.VISIBLE);
                    moreButton.setOnClickListener(this::showDiscoverTabMenu);
                }
                break;

            case 2: // Calls
                if (appLogo != null) appLogo.setVisibility(android.view.View.GONE);
                setToolbarTitleSafely("Calls");
                if (searchButton != null) {
                    searchButton.setVisibility(android.view.View.GONE);
                }
                if (newChatButton != null) {
                    newChatButton.setVisibility(android.view.View.GONE);
                }
                if (moreButton != null) {
                    moreButton.setVisibility(android.view.View.VISIBLE);
                    moreButton.setOnClickListener(this::showCallsTabMenu);
                }
                break;

            case 3: // Settings & Profile
                if (appLogo != null) appLogo.setVisibility(android.view.View.GONE);
                setToolbarTitleSafely("Settings & Profile");
                if (searchButton != null) {
                    searchButton.setVisibility(android.view.View.GONE);
                }
                if (newChatButton != null) {
                    newChatButton.setVisibility(android.view.View.GONE);
                }
                if (moreButton != null) {
                    moreButton.setVisibility(android.view.View.VISIBLE);
                    moreButton.setOnClickListener(this::showProfileTabMenu);
                }
                break;
        }
    }

    private void showChatsTabMenu(android.view.View anchor) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main_chats, popup.getMenu());
        android.view.Menu menu = popup.getMenu();
        if (menu.findItem(R.id.action_secret_inbox) != null) {
            menu.findItem(R.id.action_secret_inbox).setVisible(false);
        }
        if (menu.findItem(R.id.action_archived_chats) != null) {
            menu.findItem(R.id.action_archived_chats).setVisible(false);
        }
        popup.getMenu().add(0, 101, 3, "Change Vault Password");
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_notifications) {
                startActivity(new Intent(this, com.m3.veil.ui.notifications.NotificationsActivity.class));
                return true;
            } else if (id == 101) {
                HomeFragment hf = getHomeFragment();
                if (hf != null) {
                    hf.showChangeVaultPasswordDialog();
                } else {
                    startActivity(new Intent(this, com.m3.veil.ui.settings.PrivacySettingsActivity.class));
                }
                return true;
            } else if (id == R.id.action_archived_chats) {
                Intent intent = new Intent(this, ArchivedChatsActivity.class);
                intent.putExtra("mode", "archived");
                startActivity(intent);
                return true;
            } else if (id == R.id.action_secret_inbox) {
                Intent intent = new Intent(this, ArchivedChatsActivity.class);
                intent.putExtra("mode", "secret");
                startActivity(intent);
                return true;
            } else if (id == R.id.action_settings) {
                viewPager.setCurrentItem(3, true);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showDiscoverTabMenu(android.view.View anchor) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main_discover, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_create_post) {
                startActivity(new Intent(this, CreatePostActivity.class));
                return true;
            } else if (id == R.id.action_filter_confessions) {
                setDiscoverCategory("Confessions");
                return true;
            } else if (id == R.id.action_filter_polls) {
                setDiscoverCategory("Polls");
                return true;
            } else if (id == R.id.action_filter_trending) {
                setDiscoverCategory("Trending");
                return true;
            } else if (id == R.id.action_refresh_discover) {
                refreshDiscoverFeed();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showCallsTabMenu(android.view.View anchor) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main_calls, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_new_direct_call || id == R.id.action_call_roulette) {
                showCallsHubSheet();
                return true;
            } else if (id == R.id.action_clear_calls) {
                confirmClearCallsFromMain();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showProfileTabMenu(android.view.View anchor) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main_profile, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            ProfileFragment pf = getProfileFragment();
            if (id == R.id.action_privacy_checkup) {
                if (pf != null) {
                    pf.showPrivacyCheckupHub();
                } else {
                    Toast.makeText(this, "Privacy Checkup: All chats and media are securely encrypted", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (id == R.id.action_security_console) {
                if (pf != null) {
                    pf.openDeveloperAndSecurityConsole();
                } else {
                    Toast.makeText(this, "Security Console: Direct Connection Active â€¢ Zero Cloud Storage", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        });
        popup.show();
    }

    public HomeFragment getHomeFragment() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("f0");
        return (f instanceof HomeFragment) ? (HomeFragment) f : null;
    }

    public void setStatusFilterActive(boolean isStatus) {
        isStatusFilterActiveOnHome = isStatus;
        if (viewPager == null || viewPager.getCurrentItem() != 0) return;
        updateFabForTab(0);
        if (isStatus) {
            setBottomNavAndFabVisible(true);
            if (appLogo != null) appLogo.setVisibility(android.view.View.GONE);
            if (toolbarTitle != null) toolbarTitle.setText("Status");
            if (newChatButton != null) {
                newChatButton.setVisibility(android.view.View.GONE);
            }
            if (moreButton != null) {
                moreButton.setOnClickListener(this::showStatusTabMenu);
            }
        } else {
            updateToolbarForTab(0);
        }
    }

    private void showStatusTabMenu(android.view.View anchor) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenu().add(0, 3, 0, "Status privacy");
        popup.getMenu().add(0, 4, 1, "Settings");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 3:
                    showStatusPrivacySheet();
                    return true;
                case 4:
                    viewPager.setCurrentItem(3, true);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void showStatusPrivacySheet() {
        com.m3.veil.ui.main.status.WhatsAppStatusUi.showAudienceSelectorDialog(this, updatedAudience -> {
            Toast.makeText(this, "Status privacy updated: " + updatedAudience, Toast.LENGTH_SHORT).show();
            com.m3.veil.manager.VeilFeatureManager.getInstance(this).notifyStatusListeners();
        });
    }

    private DiscoverFragment getDiscoverFragment() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("f1");
        return (f instanceof DiscoverFragment) ? (DiscoverFragment) f : null;
    }

    private CallsFragment getCallsFragment() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("f2");
        return (f instanceof CallsFragment) ? (CallsFragment) f : null;
    }

    private ProfileFragment getProfileFragment() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("f3");
        return (f instanceof ProfileFragment) ? (ProfileFragment) f : null;
    }

    private void setDiscoverCategory(String category) {
        DiscoverFragment df = getDiscoverFragment();
        if (df != null) {
            df.setCategoryFilter(category);
        }
    }

    private void refreshDiscoverFeed() {
        DiscoverFragment df = getDiscoverFragment();
        if (df != null) {
            df.refreshFeed();
        }
    }

    private void showCallsHubSheet() {
        CallsFragment cf = getCallsFragment();
        if (cf != null) {
            cf.showCallHubMenu();
        } else {
            startActivity(new Intent(this, NewChatActivity.class));
        }
    }

    private void confirmClearCallsFromMain() {
        CallsFragment cf = getCallsFragment();
        if (cf != null) {
            cf.confirmClearAllCalls();
        } else {
            com.m3.veil.database.EncryptedChatDatabase.getInstance(this).clearAllCalls();
            Toast.makeText(this, "Call history cleared", Toast.LENGTH_SHORT).show();
        }
    }

    private static class MainPagerAdapter extends FragmentStateAdapter {
        public MainPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    return new HomeFragment();
                case 1:
                    return new DiscoverFragment();
                case 2:
                    return new CallsFragment();
                case 3:
                    return new ProfileFragment();
                default:
                    return new HomeFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent != null && intent.hasExtra("openChatId")) {
            String chatId = intent.getStringExtra("openChatId");
            String recipientId = intent.getStringExtra("openRecipientId");
            Intent chatIntent = new Intent(this, com.m3.veil.ui.chat.ChatActivity.class);
            chatIntent.putExtra("chatId", chatId);
            if (recipientId != null) {
                chatIntent.putExtra("recipientId", recipientId);
            }
            startActivity(chatIntent);
        }
    }

    @Override
    public void onBackPressed() {
        if (viewPager != null && viewPager.getCurrentItem() != 0) {
            viewPager.setCurrentItem(0, true);
            if (bottomNavigation != null) {
                bottomNavigation.setSelectedItemId(R.id.navigation_home);
            }
            return;
        }
        try {
            androidx.fragment.app.Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f0");
            if (currentFragment instanceof com.m3.veil.ui.main.fragments.HomeFragment) {
                if (((com.m3.veil.ui.main.fragments.HomeFragment) currentFragment).handleBackPressed()) {
                    return;
                }
            }
        } catch (Exception ignored) {}
        moveTaskToBack(true);
    }
}
