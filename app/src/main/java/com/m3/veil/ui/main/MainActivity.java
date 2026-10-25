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

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private FloatingActionButton fabNewMessage;
    private View bottomNavContainer;
    private boolean isBottomNavVisible = true;

    private View appLogo;
    private TextView toolbarTitle;
    private ImageView searchButton;
    private ImageView newChatButton;
    private ImageView moreButton;

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

        // Initialize P2P WebRTC & Auth Session
        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
            com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(this,
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser());
        }

        WebRtcManager.getInstance(this).addEventListener(globalCallListener);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewPager != null) {
            updateToolbarForTab(viewPager.getCurrentItem());
        }
        applyAppearancePreferences();
    }

    private void applyAppearancePreferences() {
        try {
            android.content.SharedPreferences appPrefs = getSharedPreferences("veil_appearance_prefs", MODE_PRIVATE);
            boolean amoled = appPrefs.getBoolean("amoled_pure_black", false);
            View mainContainer = findViewById(R.id.main_container);
            if (mainContainer != null) {
                if (amoled) {
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
        WebRtcManager.getInstance(this).removeEventListener(globalCallListener);
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.UiAnimator.configureSystemBars(this);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_container), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            findViewById(R.id.appBarLayout).setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            if (bottomNavContainer != null) {
                bottomNavContainer.setPadding(0, 0, 0, systemBars.bottom);
            } else if (bottomNavigation != null) {
                bottomNavigation.setPadding(0, 0, 0, systemBars.bottom);
            }
            if (fabNewMessage != null && fabNewMessage.getLayoutParams() instanceof androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                        (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) fabNewMessage.getLayoutParams();
                int navHeight = (int) (56 * getResources().getDisplayMetrics().density);
                int fabSpacing = (int) (16 * getResources().getDisplayMetrics().density);
                lp.bottomMargin = navHeight + fabSpacing + systemBars.bottom;
                fabNewMessage.setLayoutParams(lp);
            }
            return insets;
        });
    }

    private void initializeViews() {
        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        bottomNavContainer = findViewById(R.id.bottomNavContainer);
        fabNewMessage = findViewById(R.id.fabNewMessage);
        appLogo = findViewById(R.id.appLogo);
        toolbarTitle = findViewById(R.id.toolbarTitle);
        searchButton = findViewById(R.id.searchButton);
        newChatButton = findViewById(R.id.newChatButton);
        moreButton = findViewById(R.id.moreButton);
    }

    private void setupViewPager() {
        MainPagerAdapter pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(4);
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

        // Feature #107: Secret Inbox Gesture — Long-press toolbar opens Hidden Secret Vault
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

    public void refreshActivePersonaToolbarBadge() {
        if (toolbarTitle != null && viewPager != null && viewPager.getCurrentItem() == 0) {
            toolbarTitle.setText(R.string.app_name);
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
    }

    public void updateFabForTab(int position) {
        if (fabNewMessage == null) return;
        switch (position) {
            case 0: // Chats / Home
                fabNewMessage.setImageResource(R.drawable.ic_edit);
                fabNewMessage.setContentDescription("New Chat");
                fabNewMessage.setOnClickListener(v -> startActivity(new Intent(this, NewChatActivity.class)));
                fabNewMessage.show();
                break;
            case 1: // Discover / Moments
                fabNewMessage.setImageResource(R.drawable.ic_add);
                fabNewMessage.setContentDescription("Create Moment");
                fabNewMessage.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));
                fabNewMessage.show();
                break;
            case 2: // Calls
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
                toolbarTitle.setText(R.string.app_name);
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
                toolbarTitle.setText("Discover");
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
                toolbarTitle.setText("Calls");
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
                toolbarTitle.setText("Settings & Profile");
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
        // Secret Vault is already on the filter chip bar & toolbar long-press; Archived Chats has its own top row
        if (menu.findItem(R.id.action_secret_inbox) != null) {
            menu.findItem(R.id.action_secret_inbox).setVisible(false);
        }
        if (menu.findItem(R.id.action_archived_chats) != null) {
            menu.findItem(R.id.action_archived_chats).setVisible(false);
        }
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_new_group) {
                startActivity(new Intent(this, NewGroupActivity.class));
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
        android.view.Menu menu = popup.getMenu();
        // Create Post is on the FAB and category filters are on the horizontal chip bar
        if (menu.findItem(R.id.action_create_post) != null) menu.findItem(R.id.action_create_post).setVisible(false);
        if (menu.findItem(R.id.action_filter_confessions) != null) menu.findItem(R.id.action_filter_confessions).setVisible(false);
        if (menu.findItem(R.id.action_filter_polls) != null) menu.findItem(R.id.action_filter_polls).setVisible(false);
        if (menu.findItem(R.id.action_filter_trending) != null) menu.findItem(R.id.action_filter_trending).setVisible(false);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_create_post) {
                startActivity(new Intent(this, CreatePostActivity.class));
                return true;
            } else if (id == R.id.action_filter_confessions) {
                setDiscoverCategory("confession");
                return true;
            } else if (id == R.id.action_filter_polls) {
                setDiscoverCategory("poll");
                return true;
            } else if (id == R.id.action_filter_trending) {
                setDiscoverCategory("trending");
                return true;
            } else if (id == R.id.action_refresh_discover) {
                refreshDiscoverFeed();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showDiscoverFilterMenu() {
        if (searchButton == null) return;
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, searchButton);
        popup.getMenu().add(0, 1, 0, "🌐 All Moments");
        popup.getMenu().add(0, 2, 1, "🤐 Anonymous Confessions");
        popup.getMenu().add(0, 3, 2, "📊 Community Polls");
        popup.getMenu().add(0, 4, 3, "🔥 Trending");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: setDiscoverCategory("all"); return true;
                case 2: setDiscoverCategory("confession"); return true;
                case 3: setDiscoverCategory("poll"); return true;
                case 4: setDiscoverCategory("trending"); return true;
            }
            return false;
        });
        popup.show();
    }

    private void showCallsTabMenu(android.view.View anchor) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_main_calls, popup.getMenu());
        android.view.Menu menu = popup.getMenu();
        // New Direct Call & Call Roulette are already in the Calls FAB hub
        if (menu.findItem(R.id.action_new_direct_call) != null) menu.findItem(R.id.action_new_direct_call).setVisible(false);
        if (menu.findItem(R.id.action_call_roulette) != null) menu.findItem(R.id.action_call_roulette).setVisible(false);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_new_direct_call) {
                startActivity(new Intent(this, NewChatActivity.class));
                return true;
            } else if (id == R.id.action_call_roulette) {
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
            if (id == R.id.action_edit_profile) {
                if (pf != null) {
                    pf.openEditProfile();
                } else {
                    new com.m3.veil.ui.main.fragments.EditProfileBottomSheet().show(getSupportFragmentManager(), "edit_profile");
                }
                return true;
            } else if (id == R.id.action_switch_persona) {
                if (pf != null) {
                    pf.openPersonaManager();
                } else {
                    HomeFragment homeFrag = getHomeFragment();
                    if (homeFrag != null) homeFrag.showPersonaSwitcherDialog();
                }
                return true;
            } else if (id == R.id.action_privacy_checkup) {
                if (pf != null) {
                    pf.showPrivacyCheckupHub();
                } else {
                    Toast.makeText(this, "Privacy Checkup: All local data encrypted with AES-256-GCM & SQLCipher", Toast.LENGTH_LONG).show();
                }
                return true;
            } else if (id == R.id.action_security_console) {
                if (pf != null) {
                    pf.openDeveloperAndSecurityConsole();
                } else {
                    Toast.makeText(this, "Security Console: P2P WebRTC Mesh Active • Zero Cloud Storage", Toast.LENGTH_LONG).show();
                }
                return true;
            } else if (id == R.id.action_logout) {
                if (pf != null) {
                    pf.logout();
                } else {
                    com.m3.veil.manager.AuthManager.getInstance().signOut(this);
                    Intent intent = new Intent(this, com.m3.veil.ui.auth.PhoneAuthActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
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
        if (viewPager == null || viewPager.getCurrentItem() != 0) return;
        if (isStatus) {
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
        popup.getMenu().add(0, 3, 0, "🛡️ Status Privacy & Stealth Mode");
        popup.getMenu().add(0, 4, 1, "⚙️ Settings");
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
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.ModernBottomSheetDialogTheme);
        float dp = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding((int) (24 * dp), (int) (20 * dp), (int) (24 * dp), (int) (28 * dp));
        root.setBackgroundResource(R.drawable.bg_bottom_sheet_modern);

        TextView title = new TextView(this);
        title.setText("🛡️ P2P Status Privacy & ACL");
        title.setTextSize(19f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Control who receives your AES-256-GCM wrapped keys over the WebRTC mesh when you post a 24h status.");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textSecondary));
        subtitle.setPadding(0, (int) (6 * dp), 0, (int) (16 * dp));
        root.addView(subtitle);

        com.m3.veil.manager.StatusController sc = com.m3.veil.manager.StatusController.getInstance(this);
        com.m3.veil.manager.StatusController.PrivacyAcl currentAcl = sc.getDefaultPrivacyAcl();

        com.m3.veil.manager.StatusController.PrivacyAcl[] acls = {
                com.m3.veil.manager.StatusController.PrivacyAcl.ALL_CONTACTS,
                com.m3.veil.manager.StatusController.PrivacyAcl.CLOSE_FRIENDS,
                com.m3.veil.manager.StatusController.PrivacyAcl.PERSONA_ISOLATED,
                com.m3.veil.manager.StatusController.PrivacyAcl.SELECTED_PEERS
        };
        String[] labels = {
                "🌐 All P2P Contacts",
                "💚 Close Friends Only",
                "🎭 Active Persona Vault Only",
                "🔒 Selected Peers (Strict)"
        };
        String[] descs = {
                "Share status manifest with all verified contacts in your local database",
                "Only wrap decryption keys for contacts marked as Close Friends",
                "Isolate status updates strictly to the currently active Persona identity",
                "Restrict key wrapping to explicitly whitelisted peer sessions"
        };

        for (int i = 0; i < acls.length; i++) {
            final com.m3.veil.manager.StatusController.PrivacyAcl acl = acls[i];
            final String label = labels[i];
            boolean selected = (acl == currentAcl);

            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            card.setLayoutParams(lp);
            card.setRadius(12 * dp);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1.2f * dp));
            card.setStrokeColor(androidx.core.content.ContextCompat.getColor(this,
                    selected ? R.color.colorPrimary : R.color.colorOutline));
            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(this,
                    selected ? R.color.surfaceVariant : android.R.color.transparent));

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.VERTICAL);
            row.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));

            TextView tvLabel = new TextView(this);
            tvLabel.setText(label + (selected ? "  ✓" : ""));
            tvLabel.setTextSize(15f);
            tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLabel.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    selected ? R.color.colorPrimary : R.color.textPrimary));
            row.addView(tvLabel);

            TextView tvDesc = new TextView(this);
            tvDesc.setText(descs[i]);
            tvDesc.setTextSize(12.5f);
            tvDesc.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textSecondary));
            tvDesc.setPadding(0, (int) (2 * dp), 0, 0);
            row.addView(tvDesc);

            card.addView(row);
            card.setOnClickListener(v -> {
                sc.setDefaultPrivacyAcl(acl);
                Toast.makeText(this, "Status privacy updated: " + label, Toast.LENGTH_SHORT).show();
                sheet.dismiss();
            });
            root.addView(card);
        }

        // Stealth Status Viewing toggle
        android.widget.LinearLayout stealthRow = new android.widget.LinearLayout(this);
        stealthRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        stealthRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stealthRow.setPadding(0, (int) (12 * dp), 0, (int) (4 * dp));

        android.widget.LinearLayout stealthTextCol = new android.widget.LinearLayout(this);
        stealthTextCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        stealthTextCol.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvStealthTitle = new TextView(this);
        tvStealthTitle.setText("👻 Ghost / Stealth Status View");
        tvStealthTitle.setTextSize(15f);
        tvStealthTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvStealthTitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textPrimary));
        stealthTextCol.addView(tvStealthTitle);

        TextView tvStealthSub = new TextView(this);
        tvStealthSub.setText("View contact statuses without sending signed P2P view receipts");
        tvStealthSub.setTextSize(12.5f);
        tvStealthSub.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textSecondary));
        stealthTextCol.addView(tvStealthSub);

        androidx.appcompat.widget.SwitchCompat switchStealth = new androidx.appcompat.widget.SwitchCompat(this);
        switchStealth.setChecked(sc.isStealthViewMode());
        switchStealth.setOnCheckedChangeListener((btn, isChecked) -> {
            sc.setStealthViewMode(isChecked);
            Toast.makeText(this, isChecked ? "Ghost Status View enabled" : "Ghost Status View disabled", Toast.LENGTH_SHORT).show();
        });

        stealthRow.addView(stealthTextCol);
        stealthRow.addView(switchStealth);
        root.addView(stealthRow);

        sheet.setContentView(root);
        sheet.show();
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
        moveTaskToBack(true);
    }
}
