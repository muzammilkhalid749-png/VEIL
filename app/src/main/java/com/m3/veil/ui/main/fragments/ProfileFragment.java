package com.m3.veil.ui.main.fragments;



import android.content.ClipData;

import android.content.ClipboardManager;

import android.content.Context;

import android.content.Intent;

import android.content.SharedPreferences;

import android.graphics.Color;

import android.graphics.Typeface;

import android.os.Bundle;

import android.provider.Settings;

import android.text.TextUtils;

import android.view.Gravity;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.Button;

import android.widget.EditText;

import android.widget.ImageView;

import android.widget.LinearLayout;

import android.widget.ProgressBar;

import android.widget.ScrollView;

import android.widget.TextView;

import android.widget.Toast;



import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.appcompat.widget.SwitchCompat;

import androidx.fragment.app.Fragment;



import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.google.android.material.button.MaterialButton;

import com.google.firebase.auth.FirebaseAuth;

import com.google.firebase.firestore.FirebaseFirestore;

import com.m3.veil.R;

import com.m3.veil.database.EncryptedChatDatabase;

import com.m3.veil.manager.VeilFeatureManager;

import com.m3.veil.model.Chat;

import com.m3.veil.model.Persona;

import com.m3.veil.model.User;

import com.m3.veil.utils.PreferenceManager;

import com.m3.veil.utils.UiAnimator;



import java.text.SimpleDateFormat;

import java.util.ArrayList;

import java.util.Date;

import java.util.List;

import java.util.Locale;

import java.util.Set;

import java.util.UUID;



public class ProfileFragment extends Fragment {



    private TextView profileName, profileUsername, profileStatus;

    private de.hdodenhof.circleimageview.CircleImageView profileImage;

    private TextView walletBalance, walletCurrency;

    private TextView contactsCount, groupsCount, channelsCount;

    private TextView appVersion, currentLanguage;

    private Button editProfileButton, addFundsButton, sendMoneyButton, logoutButton;

    private SwitchCompat stealthModeSwitch;

    private ProgressBar loadingBar;

    private View profileContent, emptyState;



    private FirebaseAuth mAuth;

    private FirebaseFirestore db;

    private PreferenceManager preferenceManager;



    @Nullable

    @Override

    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_profile, container, false);



        mAuth = FirebaseAuth.getInstance();

        db = FirebaseFirestore.getInstance();

        preferenceManager = new PreferenceManager(requireContext());



        initializeViews(view);

        setupClickListeners(view);

        loadUserData();

        loadSettings();

        refreshGamificationAndVaultStats();

        setAppVersion();



        return view;

    }



    @Override

    public void onResume() {

        super.onResume();

        refreshGamificationAndVaultStats();

    }



    private void refreshGamificationAndVaultStats() {

        if (getContext() == null) return;

        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());



        if (walletBalance != null) {

            walletBalance.setText(String.format(Locale.US, "%d VC", fm.getVeilCoins()));

        }

        if (walletCurrency != null) {

            walletCurrency.setText("LVL " + fm.getLevel() + " • 🔥 " + fm.getDailyStreak() + "d Streak • ⭐ " + fm.getKarma() + " Karma");

        }



        List<Chat> allChats = EncryptedChatDatabase.getInstance(requireContext()).getAllChats();

        int direct = 0, groups = 0, channels = 0;

        for (Chat c : allChats) {

            if ("channel".equalsIgnoreCase(c.getType())) channels++;

            else if (c.isGroup() || "group".equalsIgnoreCase(c.getType())) groups++;

            else direct++;

        }

        if (contactsCount != null) contactsCount.setText(String.valueOf(direct));

        if (groupsCount != null) groupsCount.setText(String.valueOf(groups));

        if (channelsCount != null) channelsCount.setText(String.valueOf(channels));



        Persona active = fm.getActivePersona();

        if (active != null && profileStatus != null) {

            int score = fm.calculatePrivacyScore(true);

            profileStatus.setText("🎭 " + active.getDisplayName() + " (" + active.getMood() + ") • 🛡️ Privacy " + score + "/100");

        }

    }



    private void loadSettings() {

        stealthModeSwitch.setChecked(preferenceManager.isStealthMode());



        SharedPreferences langPrefs = requireContext().getSharedPreferences("veil_language_prefs", Context.MODE_PRIVATE);

        String langName = langPrefs.getString("selected_language_name", "English");

        if (currentLanguage != null) {

            currentLanguage.setText(langName);

        }

    }



    private void toggleStealthMode(boolean enabled) {

        preferenceManager.setStealthMode(enabled);

        VeilFeatureManager.getInstance(requireContext()).setStealthTyping(enabled);

        VeilFeatureManager.getInstance(requireContext()).setFakeOffline(enabled);

        showToast(enabled ? "Stealth Mode ON (Invisible Presence & Typing)" : "Stealth Mode OFF");



        String currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUid != null && db != null) {
            try {
                db.collection("users").document(currentUid)
                        .update("status", enabled ? "invisible" : "online");
            } catch (Exception ignored) {}
        }

        refreshGamificationAndVaultStats();

    }



    private void initializeViews(View view) {

        profileName = view.findViewById(R.id.profileName);

        profileUsername = view.findViewById(R.id.profileUsername);

        profileStatus = view.findViewById(R.id.profileStatus);

        profileImage = view.findViewById(R.id.profileImage);



        walletBalance = view.findViewById(R.id.walletBalance);

        walletCurrency = view.findViewById(R.id.walletCurrency);



        contactsCount = view.findViewById(R.id.contactsCount);

        groupsCount = view.findViewById(R.id.groupsCount);

        channelsCount = view.findViewById(R.id.channelsCount);



        appVersion = view.findViewById(R.id.appVersion);

        currentLanguage = view.findViewById(R.id.currentLanguage);



        editProfileButton = view.findViewById(R.id.editProfileButton);

        addFundsButton = view.findViewById(R.id.addFundsButton);

        sendMoneyButton = view.findViewById(R.id.sendMoneyButton);

        logoutButton = view.findViewById(R.id.logoutButton);

        stealthModeSwitch = view.findViewById(R.id.stealthModeSwitch);

        loadingBar = view.findViewById(R.id.loadingBar);

        profileContent = view.findViewById(R.id.profileContent);

        emptyState = view.findViewById(R.id.emptyState);



        UiAnimator.attachSpringPress(editProfileButton);

        UiAnimator.attachSpringPress(addFundsButton);

        UiAnimator.attachSpringPress(sendMoneyButton);

        UiAnimator.attachSpringPress(logoutButton);



        view.findViewById(R.id.retryButton).setOnClickListener(v -> loadUserData());

    }



    private void setupClickListeners(View view) {

        editProfileButton.setOnClickListener(v -> openEditProfile());

        addFundsButton.setOnClickListener(v -> showGamificationAndMarketplaceHub());

        sendMoneyButton.setOnClickListener(v -> {

            WalletBottomSheet sheet = new WalletBottomSheet();

            sheet.show(getChildFragmentManager(), "WalletBottomSheet");

        });

        if (walletCurrency != null) {

            walletCurrency.setOnClickListener(v -> {

                com.m3.veil.ui.chat.sheets.KarmaLeaderboardBottomSheet sheet =

                        new com.m3.veil.ui.chat.sheets.KarmaLeaderboardBottomSheet();

                sheet.show(getChildFragmentManager(), "KarmaLeaderboardBottomSheet");

            });

        }

        logoutButton.setOnClickListener(v -> logout());



        stealthModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {

            toggleStealthMode(isChecked);

        });



        view.findViewById(R.id.accountSettings).setOnClickListener(v -> openAccountSettings());

        view.findViewById(R.id.privacySettings).setOnClickListener(v -> startActivity(new Intent(requireContext(), com.m3.veil.ui.settings.PrivacySettingsActivity.class)));

        view.findViewById(R.id.notificationSettings).setOnClickListener(v -> openNotificationSettings());

        view.findViewById(R.id.appearanceSettings).setOnClickListener(v -> openAppearanceSettings());

        view.findViewById(R.id.storageSettings).setOnClickListener(v -> openStorageSettings());

        view.findViewById(R.id.languageSettings).setOnClickListener(v -> openLanguageSettings());



        View storageView = view.findViewById(R.id.storageSettings);

        if (storageView != null && storageView.getParent() instanceof ViewGroup) {

            ViewGroup parent = (ViewGroup) storageView.getParent();

            int idx = parent.indexOfChild(storageView);

            LinearLayout repostsRow = new LinearLayout(requireContext());

            repostsRow.setOrientation(LinearLayout.HORIZONTAL);

            repostsRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * getResources().getDisplayMetrics().density)));

            repostsRow.setGravity(Gravity.CENTER_VERTICAL);

            repostsRow.setPadding((int) (16 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density), 0);

            repostsRow.setBackgroundResource(R.drawable.button_primary_selector);

            repostsRow.setClickable(true);

            repostsRow.setFocusable(true);



            ImageView iv = new ImageView(requireContext());

            int dim = (int) (22 * getResources().getDisplayMetrics().density);

            iv.setLayoutParams(new LinearLayout.LayoutParams(dim, dim));

            iv.setImageResource(R.drawable.ic_share);

            iv.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));

            repostsRow.addView(iv);



            TextView tv = new TextView(requireContext());

            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

            tvLp.leftMargin = (int) (14 * getResources().getDisplayMetrics().density);

            tv.setLayoutParams(tvLp);

            tv.setText("My Reposts & Quotes (#92)");

            tv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

            tv.setTextSize(15f);

            repostsRow.addView(tv);



            ImageView ivArrow = new ImageView(requireContext());

            int arrowDim = (int) (16 * getResources().getDisplayMetrics().density);

            ivArrow.setLayoutParams(new LinearLayout.LayoutParams(arrowDim, arrowDim));

            ivArrow.setImageResource(R.drawable.ic_arrow_forward);

            ivArrow.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

            repostsRow.addView(ivArrow);



            repostsRow.setOnClickListener(v -> openRepostsDialog());

            parent.addView(repostsRow, idx + 1);



            // Community Jury & Transparency (#193, #194, #196)

            LinearLayout juryRow = new LinearLayout(requireContext());

            juryRow.setOrientation(LinearLayout.HORIZONTAL);

            juryRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * getResources().getDisplayMetrics().density)));

            juryRow.setGravity(Gravity.CENTER_VERTICAL);

            juryRow.setPadding((int) (16 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density), 0);

            juryRow.setBackgroundResource(R.drawable.button_primary_selector);

            juryRow.setClickable(true);

            juryRow.setFocusable(true);



            ImageView ivJury = new ImageView(requireContext());

            ivJury.setLayoutParams(new LinearLayout.LayoutParams(dim, dim));

            ivJury.setImageResource(R.drawable.ic_privacy);

            ivJury.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));

            juryRow.addView(ivJury);



            TextView tvJury = new TextView(requireContext());

            LinearLayout.LayoutParams tvJuryLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

            tvJuryLp.leftMargin = (int) (14 * getResources().getDisplayMetrics().density);

            tvJury.setLayoutParams(tvJuryLp);

            tvJury.setText("Community Jury & Transparency (#193, #196)");

            tvJury.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

            tvJury.setTextSize(15f);

            juryRow.addView(tvJury);



            ImageView ivArrowJ = new ImageView(requireContext());

            ivArrowJ.setLayoutParams(new LinearLayout.LayoutParams(arrowDim, arrowDim));

            ivArrowJ.setImageResource(R.drawable.ic_arrow_forward);

            ivArrowJ.setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

            juryRow.addView(ivArrowJ);



            juryRow.setOnClickListener(v -> {

                ModerationTransparencyBottomSheet sheet = new ModerationTransparencyBottomSheet();

                sheet.show(getChildFragmentManager(), "ModerationTransparencyBottomSheet");

            });

            parent.addView(juryRow, idx + 2);

        }



        view.findViewById(R.id.helpSettings).setOnClickListener(v -> openDeveloperAndSecurityConsole());

        view.findViewById(R.id.aboutSettings).setOnClickListener(v -> openAbout());



        view.findViewById(R.id.profileImage).setOnClickListener(v -> openPersonaManager());

        view.findViewById(R.id.profileName).setOnClickListener(v -> openPersonaManager());

        view.findViewById(R.id.profileStatus).setOnClickListener(v -> openPersonaManager());

    }



    /**

     * Multi-Persona System (#6, #7, #10, #11, #12, #129):

     * Switch instantaneously between multiple local encrypted personas, each with its own

     * display name, mood, bio, bubble color hex, and optional auto-expiry timer.

     */

    public void openPersonaManager() {

        if (getContext() == null) return;

        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());

        List<Persona> personas = fm.getPersonas();

        Persona current = fm.getActivePersona();



        com.google.android.material.bottomsheet.BottomSheetDialog dialog =

                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);

        View view = getLayoutInflater().inflate(R.layout.dialog_persona_switcher_sheet, null);

        dialog.setContentView(view);



        ViewGroup container = view.findViewById(R.id.personaListContainer);

        View btnAddNew = view.findViewById(R.id.btnAddNewPersona);

        float density = getResources().getDisplayMetrics().density;



        for (Persona p : personas) {

            boolean isSelected = current != null && p.getPersonaId().equals(current.getPersonaId());

            com.google.android.material.card.MaterialCardView card =

                    new com.google.android.material.card.MaterialCardView(requireContext());

            card.setCardElevation(0);

            card.setStrokeWidth((int) (1.2f * density));

            card.setStrokeColor(androidx.core.content.ContextCompat.getColor(requireContext(),

                    isSelected ? R.color.colorPrimary : R.color.colorOutline));

            card.setRadius(14 * density);

            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(),

                    isSelected ? R.color.colorSurfaceVariant : R.color.colorSurface));



            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(

                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            lp.bottomMargin = (int) (10 * density);

            card.setLayoutParams(lp);



            LinearLayout itemLayout = new LinearLayout(requireContext());

            itemLayout.setOrientation(LinearLayout.HORIZONTAL);

            itemLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

            itemLayout.setPadding((int) (16 * density), (int) (14 * density), (int) (16 * density), (int) (14 * density));



            LinearLayout textCol = new LinearLayout(requireContext());

            textCol.setOrientation(LinearLayout.VERTICAL);

            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));



            TextView tvName = new TextView(requireContext());

            tvName.setText((isSelected ? "✅ " : "🎭 ") + p.getDisplayName() + (p.isTemporary() ? " [Temp 24h]" : ""));

            tvName.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

            tvName.setTextSize(15);

            tvName.setTypeface(null, android.graphics.Typeface.BOLD);



            TextView tvMood = new TextView(requireContext());

            tvMood.setText(p.getMood() + " • " + p.getBio());

            tvMood.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

            tvMood.setTextSize(12);



            textCol.addView(tvName);

            textCol.addView(tvMood);

            itemLayout.addView(textCol);

            card.addView(itemLayout);



            card.setOnClickListener(vCard -> {

                fm.setActivePersona(p.getPersonaId());

                profileName.setText(p.getDisplayName());

                refreshGamificationAndVaultStats();

                showToast("Switched persona to " + p.getDisplayName());

                dialog.dismiss();

            });



            container.addView(card);

        }



        btnAddNew.setOnClickListener(v -> {

            dialog.dismiss();

            showCreatePersonaDialog(false);

        });



        dialog.show();

    }



    private void showCreatePersonaDialog(boolean autoExpire24h) {

        if (getContext() == null) return;

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =

                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);

        LinearLayout layout = new LinearLayout(getContext());

        layout.setOrientation(LinearLayout.VERTICAL);

        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        layout.setPadding(pad, pad, pad, pad);

        layout.setBackgroundResource(R.drawable.bottom_sheet_background);



        TextView tvTitle = new TextView(getContext());

        tvTitle.setText(autoExpire24h ? "⏳ Create 24h Temporary Persona" : "🎭 Create New Anonymous Persona");

        tvTitle.setTextSize(18);

        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.textPrimary));

        layout.addView(tvTitle);



        TextView tvSub = new TextView(getContext());

        tvSub.setText("Zero-trace identity for chatting and posting anonymously");

        tvSub.setTextSize(13);

        tvSub.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.textSecondary));

        tvSub.setPadding(0, 4, 0, (int) (16 * getResources().getDisplayMetrics().density));

        layout.addView(tvSub);



        EditText etName = new EditText(getContext());

        etName.setHint("Persona Handle (e.g. @ZeroCool)");

        etName.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.textPrimary));

        layout.addView(etName);



        EditText etMood = new EditText(getContext());

        etMood.setHint("Mood (e.g. Cypherpunk / Ghost / Chill)");

        etMood.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.textPrimary));

        layout.addView(etMood);



        com.google.android.material.button.MaterialButton btnCreate =

                new com.google.android.material.button.MaterialButton(getContext());

        btnCreate.setText("Create & Activate");

        btnCreate.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        btnLp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);

        btnCreate.setLayoutParams(btnLp);

        btnCreate.setOnClickListener(v -> {

            String name = etName.getText().toString().trim();

            String mood = etMood.getText().toString().trim();

            if (!name.isEmpty()) {

                String[] colors = {"#5C6BC0", "#26A69A", "#EC407A", "#7E57C2", "#FFA726"};

                String color = colors[Math.abs(name.hashCode()) % colors.length];

                VeilFeatureManager.getInstance(requireContext()).addPersona(

                        name,

                        "Zero-trace identity",

                        mood.isEmpty() ? "Mysterious" : mood,

                        color,

                        autoExpire24h

                );

                profileName.setText(name);

                refreshGamificationAndVaultStats();

                showToast("Persona '" + name + "' activated!");

                dialog.dismiss();

            } else {

                Toast.makeText(getContext(), "Please enter a handle", Toast.LENGTH_SHORT).show();

            }

        });

        layout.addView(btnCreate);



        dialog.setContentView(layout);

        dialog.show();

    }



    /**

     * Gamification, Badges & Economy Hub (#156-#172):

     * View XP, Level, Streaks, Karma, Unlocked Badges, and spend VeilCoins on themes/personas/sticker packs.

     */

    private void showGamificationAndMarketplaceHub() {

        if (getContext() == null) return;

        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());



        com.google.android.material.bottomsheet.BottomSheetDialog dialog =

                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);

        View view = getLayoutInflater().inflate(R.layout.dialog_gamification_hub_sheet, null);

        dialog.setContentView(view);



        TextView tvTitle = view.findViewById(R.id.tvHubLevelTitle);

        TextView tvCoins = view.findViewById(R.id.tvHubCoinsBadge);

        ProgressBar pbXp = view.findViewById(R.id.pbXpProgress);

        TextView tvStats = view.findViewById(R.id.tvHubStatsLine);



        View btnBonus = view.findViewById(R.id.btnClaimBonus);

        View btnNeon = view.findViewById(R.id.btnUnlockNeonTheme);

        View btnUnlimited = view.findViewById(R.id.btnUnlockUnlimitedPersonas);

        View btnWallet = view.findViewById(R.id.btnOpenWalletSheet);



        tvTitle.setText("🎖️ Cypherpunk Level " + fm.getLevel());

        tvCoins.setText("🪙 " + fm.getVeilCoins() + " VC");

        int xpInLevel = fm.getXp() % 100;

        pbXp.setProgress(xpInLevel);

        tvStats.setText("🔥 " + fm.getDailyStreak() + "-Day Streak  •  ⭐ " + fm.getKarma() + " Karma  •  " + xpInLevel + "/100 XP to Next Level");



        btnBonus.setOnClickListener(v -> {

            fm.addCoins(50);

            fm.addXp(25);

            refreshGamificationAndVaultStats();

            showToast("🎁 Claimed +50 VeilCoins & +25 XP!");

            dialog.dismiss();

        });



        btnNeon.setOnClickListener(v -> {

            if (fm.spendCoins(100)) {

                fm.unlockBadge("Neon Cypher Skin");

                refreshGamificationAndVaultStats();

                showToast("🎨 Unlocked Neon Cypher Skin!");

                dialog.dismiss();

            } else {

                showToast("Not enough VeilCoins (Need 100 VC)");

            }

        });



        btnUnlimited.setOnClickListener(v -> {

            if (fm.spendCoins(150)) {

                fm.unlockBadge("Persona Master");

                refreshGamificationAndVaultStats();

                showToast("🎭 Unlimited Personas Tier Unlocked!");

                dialog.dismiss();

            } else {

                showToast("Not enough VeilCoins (Need 150 VC)");

            }

        });



        btnWallet.setOnClickListener(v -> {

            dialog.dismiss();

            WalletBottomSheet sheet = new WalletBottomSheet();

            sheet.show(getChildFragmentManager(), "WalletBottomSheet");

        });



        dialog.show();

    }



    /**

     * Private Notes Vault & Message to Future Self (#114, #208, #216):

     * Stored strictly inside the SQLCipher 256-bit AES `notes_vault` table.

     */

    private void openPrivateNotesVaultDialog() {

        if (getContext() == null) return;

        List<EncryptedChatDatabase.VaultNote> notes = EncryptedChatDatabase.getInstance(requireContext()).getAllVaultNotes();



        com.google.android.material.bottomsheet.BottomSheetDialog dialog =

                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);

        View view = getLayoutInflater().inflate(R.layout.dialog_vault_notes_sheet, null);

        dialog.setContentView(view);



        ViewGroup container = view.findViewById(R.id.vaultNotesContainer);

        View btnAddNote = view.findViewById(R.id.btnAddVaultNote);

        View btnCapsule = view.findViewById(R.id.btnFutureCapsule);

        View btnExport = view.findViewById(R.id.btnExportVaultBackup);



        float density = getResources().getDisplayMetrics().density;

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());



        if (notes.isEmpty()) {

            TextView empty = new TextView(requireContext());

            empty.setText("No notes yet in your private SQLCipher vault.");

            empty.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

            empty.setTextSize(13);

            empty.setPadding(0, 16, 0, 16);

            container.addView(empty);

        } else {

            for (EncryptedChatDatabase.VaultNote note : notes) {

                boolean lockedFuture = note.unlockAtMillis > System.currentTimeMillis();

                com.google.android.material.card.MaterialCardView card =

                        new com.google.android.material.card.MaterialCardView(requireContext());

                card.setCardElevation(0);

                card.setStrokeWidth((int) (1.2f * density));

                card.setStrokeColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorOutline));

                card.setRadius(12 * density);

                card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorSurfaceVariant));



                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(

                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

                lp.bottomMargin = (int) (8 * density);

                card.setLayoutParams(lp);



                LinearLayout col = new LinearLayout(requireContext());

                col.setOrientation(LinearLayout.VERTICAL);

                col.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));



                TextView tvTitle = new TextView(requireContext());

                tvTitle.setText((lockedFuture ? "⏳ [Time-Locked] " : "🔐 ") + note.title);

                tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

                tvTitle.setTextSize(14);

                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);



                TextView tvBody = new TextView(requireContext());

                tvBody.setText(lockedFuture ? "Unlocks on " + sdf.format(new Date(note.unlockAtMillis)) : note.content);

                tvBody.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

                tvBody.setTextSize(12);



                col.addView(tvTitle);

                col.addView(tvBody);

                card.addView(col);



                card.setOnClickListener(v -> {

                    if (lockedFuture) {

                        showToast("⏳ This Future-Self capsule is time-locked until " + sdf.format(new Date(note.unlockAtMillis)));

                    } else {

                        BottomSheetDialog noteSheet = new BottomSheetDialog(requireContext());

                        View sv = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_destructive_sheet, null);

                        noteSheet.setContentView(sv);



                        TextView tvSheetTitle = sv.findViewById(R.id.tvDestructiveTitle);

                        TextView tvMsg = sv.findViewById(R.id.tvDestructiveMessage);

                        MaterialButton btnDelete = sv.findViewById(R.id.btnDestructiveConfirm);

                        MaterialButton btnClose = sv.findViewById(R.id.btnDestructiveCancel);

                        android.widget.ImageView ivIcon = sv.findViewById(R.id.ivDestructiveIcon);



                        ivIcon.setImageResource(R.drawable.ic_lock);

                        ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));

                        ivIcon.setBackgroundResource(R.drawable.bg_capsule_active);



                        tvSheetTitle.setText(note.title);

                        tvMsg.setText(note.content);

                        btnDelete.setText("Delete Vault Note");

                        btnClose.setText("Close");



                        btnDelete.setOnClickListener(delV -> {

                            EncryptedChatDatabase.getInstance(requireContext()).deleteVaultNote(note.id);

                            showToast("Note deleted from vault");

                            noteSheet.dismiss();

                            dialog.dismiss();

                            openPrivateNotesVaultDialog();

                        });

                        btnClose.setOnClickListener(closeV -> noteSheet.dismiss());



                        noteSheet.show();

                    }

                });



                container.addView(card);

            }

        }



        btnAddNote.setOnClickListener(v -> {

            dialog.dismiss();

            showCreateVaultNoteDialog(false);

        });



        btnCapsule.setOnClickListener(v -> {

            dialog.dismiss();

            showCreateVaultNoteDialog(true);

        });



        btnExport.setOnClickListener(v -> {

            String json = EncryptedChatDatabase.getInstance(requireContext()).exportVaultSummaryJson();

            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);

            if (cm != null) {

                cm.setPrimaryClip(ClipData.newPlainText("VEIL Backup", json));

            }

            showToast("📦 Encrypted Vault Backup metadata copied (" + json.length() + " bytes)");

        });



        dialog.show();

    }



    private void showCreateVaultNoteDialog(boolean futureTimeCapsule) {

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        LinearLayout layout = new LinearLayout(requireContext());

        layout.setOrientation(LinearLayout.VERTICAL);

        layout.setBackgroundResource(R.drawable.bottom_sheet_background);

        int padH = (int) (24 * getResources().getDisplayMetrics().density);

        int padV = (int) (16 * getResources().getDisplayMetrics().density);

        layout.setPadding(padH, padV, padH, padV * 2);



        View handle = new View(requireContext());

        int handleW = (int) (36 * getResources().getDisplayMetrics().density);

        int handleH = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);

        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (18 * getResources().getDisplayMetrics().density);

        handle.setLayoutParams(handleLp);

        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);

        layout.addView(handle);



        TextView tvTitle = new TextView(requireContext());

        tvTitle.setText(futureTimeCapsule ? "⏳ Message to Future Self" : "🔐 New SQLCipher Vault Note");

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        tvTitle.setTextSize(19);

        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        layout.addView(tvTitle);



        TextView tvSub = new TextView(requireContext());

        tvSub.setText(futureTimeCapsule ? "Sealed with AES-256 GCM. Automatically unlocks after 24 hours." : "Protected locally inside zero-knowledge SQLCipher 256-bit AES vault.");

        tvSub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        tvSub.setTextSize(13);

        tvSub.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));

        layout.addView(tvSub);



        EditText titleInput = new EditText(requireContext());

        titleInput.setHint(futureTimeCapsule ? "Capsule Title" : "Secret Note Title");

        titleInput.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        titleInput.setHintTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        titleInput.setBackgroundResource(R.drawable.bg_search_input);

        int inputPad = (int) (14 * getResources().getDisplayMetrics().density);

        titleInput.setPadding(inputPad, inputPad, inputPad, inputPad);

        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        tLp.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);

        titleInput.setLayoutParams(tLp);

        layout.addView(titleInput);



        EditText bodyInput = new EditText(requireContext());

        bodyInput.setHint("Encrypted note content...");

        bodyInput.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        bodyInput.setHintTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        bodyInput.setBackgroundResource(R.drawable.bg_search_input);

        bodyInput.setMinLines(3);

        bodyInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        bodyInput.setPadding(inputPad, inputPad, inputPad, inputPad);

        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        bLp.bottomMargin = (int) (20 * getResources().getDisplayMetrics().density);

        bodyInput.setLayoutParams(bLp);

        layout.addView(bodyInput);



        MaterialButton btnSave = new MaterialButton(requireContext());

        btnSave.setText(futureTimeCapsule ? "⏳ Seal Time Capsule" : "🔐 Encrypt & Save");

        btnSave.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));

        btnSave.setTextColor(0xFFFFFFFF);

        layout.addView(btnSave);



        btnSave.setOnClickListener(v -> {

            String t = titleInput.getText().toString().trim();

            String b = bodyInput.getText().toString().trim();

            if (!b.isEmpty()) {

                long unlockAt = futureTimeCapsule ? (System.currentTimeMillis() + 86400_000L) : 0L;

                EncryptedChatDatabase.getInstance(requireContext()).saveVaultNote(

                        UUID.randomUUID().toString(),

                        t.isEmpty() ? "Untitled Vault Note" : t,

                        b,

                        "🔮 Vault",

                        unlockAt

                );

                showToast(futureTimeCapsule ? "⏳ Time-capsule sealed in SQLCipher!" : "🔐 Saved to SQLCipher Notes Vault!");

                sheet.dismiss();

            } else {

                showToast("Note content cannot be empty");

            }

        });



        sheet.setContentView(layout);

        sheet.show();

    }



    /**

     * WhatsApp-inspired Privacy Checkup & Security Control Center (#104-#117):

     * Displays gamified Privacy Score (0-100), Screenshot Block toggle, Stealth Typing,

     * Anonymous Reactions, Decoy PIN setup, Safe Word Panic Wipe, and Instant Secure Wipe.

     */

    public void showPrivacyCheckupHub() {

        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());

        VeilFeatureManager.PrivacyScoreBreakdown b = fm.getPrivacyScoreBreakdown();

        int score = b.totalScore;



        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        LinearLayout root = new LinearLayout(requireContext());

        root.setOrientation(LinearLayout.VERTICAL);

        root.setBackgroundResource(R.drawable.bottom_sheet_background);

        int padH = (int) (24 * getResources().getDisplayMetrics().density);

        int padV = (int) (16 * getResources().getDisplayMetrics().density);

        root.setPadding(padH, padV, padH, padV * 2);



        View handle = new View(requireContext());

        int handleW = (int) (36 * getResources().getDisplayMetrics().density);

        int handleH = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);

        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);

        handle.setLayoutParams(handleLp);

        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);

        root.addView(handle);



        TextView tvTitle = new TextView(requireContext());

        tvTitle.setText("🛡️ Live Cryptographic Privacy Score (#117)");

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        tvTitle.setTextSize(19);

        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        root.addView(tvTitle);



        TextView tvScore = new TextView(requireContext());

        tvScore.setText("Score: " + score + "/100\n"

                + (b.hardwareKeystoreActive ? "✅ Hardware Keystore (+20)  " : "❌ Hardware Keystore (0)  ")

                + (b.pinConfigured ? "✅ PIN Lock (+15)\n" : "❌ PIN Lock (0)\n")

                + (b.decoyConfigured ? "✅ Decoy PIN (+15)  " : "❌ Decoy PIN (0)  ")

                + (b.torRelayActive ? "✅ Tor/Relay IP Hiding (+20)\n" : "❌ Tor/Relay IP Hiding (0)\n")

                + (b.vanishDefaultActive ? "✅ Vanish Default (+15)  " : "❌ Vanish Default (0)  ")

                + (b.flagSecureActive ? "✅ FLAG_SECURE (+15)" : "❌ FLAG_SECURE (0)"));

        tvScore.setTextColor(score >= 80 ? 0xFF10B981 : (score >= 50 ? 0xFFF59E0B : 0xFFEF4444));

        tvScore.setTextSize(13);

        tvScore.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));

        root.addView(tvScore);



        android.widget.ScrollView sv = new android.widget.ScrollView(requireContext());

        LinearLayout itemsContainer = new LinearLayout(requireContext());

        itemsContainer.setOrientation(LinearLayout.VERTICAL);



        addPrivacyActionRow(itemsContainer, "🧅 Tor / SOCKS5 Proxy Bridge (#116)",

                b.torRelayActive ? "Active • Strict Relay (No STUN IP Leaks)" : "Disabled • Direct P2P Mesh", v -> {

            sheet.dismiss();

            PrivacySettingsBottomSheet pSheet = new PrivacySettingsBottomSheet();

            pSheet.show(getChildFragmentManager(), "PrivacySettingsBottomSheet");

        });



        addPrivacyActionRow(itemsContainer, "📵 Block Screenshots (FLAG_SECURE)", fm.isFlagSecureEnabled() ? "Active (Enabled)" : "Disabled", v -> {

            fm.setFlagSecureEnabled(!fm.isFlagSecureEnabled());

            refreshGamificationAndVaultStats();

            sheet.dismiss();

            showToast("Screenshot Protection: " + (fm.isFlagSecureEnabled() ? "ON" : "OFF"));

        });



        addPrivacyActionRow(itemsContainer, "👻 Stealth Typing", fm.isStealthTyping() ? "Hiding typing indicator" : "Broadcasting typing", v -> {

            fm.setStealthTyping(!fm.isStealthTyping());

            refreshGamificationAndVaultStats();

            sheet.dismiss();

            showToast("Stealth Typing: " + (fm.isStealthTyping() ? "ON" : "OFF"));

        });



        addPrivacyActionRow(itemsContainer, "🎭 Anonymous Reactions", fm.isReactAnonymously() ? "Sender mask active" : "Default", v -> {

            fm.setReactAnonymously(!fm.isReactAnonymously());

            sheet.dismiss();

            showToast("Anonymous Reactions: " + (fm.isReactAnonymously() ? "ON" : "OFF"));

        });



        addPrivacyActionRow(itemsContainer, "🕵️ Configure Decoy PIN", "Opens fake account on forced unlock", v -> {

            sheet.dismiss();

            showConfigureDecoyPinDialog();

        });



        addPrivacyActionRow(itemsContainer, "🚨 Set Safe-Word Panic Trigger", "Safe word: " + fm.getPanicSafeWord(), v -> {

            sheet.dismiss();

            showConfigureSafeWordDialog();

        });



        addPrivacyActionRow(itemsContainer, "⏱️ Default Vanish Timer & Read Receipts", "Manage ephemeral message lifecycles", v -> {

            sheet.dismiss();

            PrivacySettingsBottomSheet pSheet = new PrivacySettingsBottomSheet();

            pSheet.show(getChildFragmentManager(), "PrivacySettingsBottomSheet");

        });



        addPrivacyActionRow(itemsContainer, "🔐 Encrypted Notes Vault", "Open zero-knowledge encrypted notes & capsules", v -> {

            sheet.dismiss();

            openPrivateNotesVaultDialog();

        });



        addPrivacyActionRow(itemsContainer, "💥 Instant Secure Wipe", "Cryptographically shred all local data", v -> {

            sheet.dismiss();

            confirmInstantSecureWipe();

        });



        sv.addView(itemsContainer);

        root.addView(sv);



        sheet.setContentView(root);

        sheet.show();

    }



    private void addPrivacyActionRow(LinearLayout parent, String title, String subtitle, View.OnClickListener listener) {

        LinearLayout row = new LinearLayout(requireContext());

        row.setOrientation(LinearLayout.VERTICAL);

        row.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, (int) (10 * getResources().getDisplayMetrics().density));

        row.setClickable(true);

        row.setFocusable(true);



        TextView t = new TextView(requireContext());

        t.setText(title);

        t.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        t.setTextSize(15);

        t.setTypeface(null, android.graphics.Typeface.BOLD);



        TextView s = new TextView(requireContext());

        s.setText(subtitle);

        s.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        s.setTextSize(12);



        row.addView(t);

        row.addView(s);

        row.setOnClickListener(listener);



        View div = new View(requireContext());

        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));

        div.setBackgroundColor(0x1A888888);



        parent.addView(row);

        parent.addView(div);

    }



    private void showConfigureDecoyPinDialog() {

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        LinearLayout layout = new LinearLayout(requireContext());

        layout.setOrientation(LinearLayout.VERTICAL);

        layout.setBackgroundResource(R.drawable.bottom_sheet_background);

        int padH = (int) (24 * getResources().getDisplayMetrics().density);

        int padV = (int) (16 * getResources().getDisplayMetrics().density);

        layout.setPadding(padH, padV, padH, padV * 2);



        View handle = new View(requireContext());

        int handleW = (int) (36 * getResources().getDisplayMetrics().density);

        int handleH = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);

        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);

        handle.setLayoutParams(handleLp);

        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);

        layout.addView(handle);



        TextView tvTitle = new TextView(requireContext());

        tvTitle.setText("🕵️ Decoy Account PIN");

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        tvTitle.setTextSize(19);

        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        layout.addView(tvTitle);



        TextView tvSub = new TextView(requireContext());

        tvSub.setText("If forced to unlock your phone, entering this Decoy PIN on the lock screen opens a harmless decoy mode.");

        tvSub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        tvSub.setTextSize(13);

        tvSub.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));

        layout.addView(tvSub);



        EditText input = new EditText(requireContext());

        input.setHint("6-digit Decoy PIN (e.g. 000000)");

        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        input.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        input.setBackgroundResource(R.drawable.bg_search_input);

        int inputPad = (int) (14 * getResources().getDisplayMetrics().density);

        input.setPadding(inputPad, inputPad, inputPad, inputPad);

        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        inLp.bottomMargin = (int) (20 * getResources().getDisplayMetrics().density);

        input.setLayoutParams(inLp);

        layout.addView(input);



        MaterialButton btnSave = new MaterialButton(requireContext());

        btnSave.setText("Save Decoy PIN");

        btnSave.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));

        btnSave.setTextColor(0xFFFFFFFF);

        layout.addView(btnSave);



        btnSave.setOnClickListener(v -> {

            String pin = input.getText().toString().trim();

            VeilFeatureManager.getInstance(requireContext()).setDecoyPin(pin);

            refreshGamificationAndVaultStats();

            sheet.dismiss();

            showToast("🕵️ Decoy PIN armed!");

        });



        sheet.setContentView(layout);

        sheet.show();

    }



    private void showConfigureSafeWordDialog() {

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        LinearLayout layout = new LinearLayout(requireContext());

        layout.setOrientation(LinearLayout.VERTICAL);

        layout.setBackgroundResource(R.drawable.bottom_sheet_background);

        int padH = (int) (24 * getResources().getDisplayMetrics().density);

        int padV = (int) (16 * getResources().getDisplayMetrics().density);

        layout.setPadding(padH, padV, padH, padV * 2);



        View handle = new View(requireContext());

        int handleW = (int) (36 * getResources().getDisplayMetrics().density);

        int handleH = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);

        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);

        handle.setLayoutParams(handleLp);

        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);

        layout.addView(handle);



        TextView tvTitle = new TextView(requireContext());

        tvTitle.setText("🚨 Safe-Word Panic Wipe");

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        tvTitle.setTextSize(19);

        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        layout.addView(tvTitle);



        TextView tvSub = new TextView(requireContext());

        tvSub.setText("Typing this exact safe word into any chat composer immediately wipes the local SQLCipher database and closes the session.");

        tvSub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        tvSub.setTextSize(13);

        tvSub.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0, (int) (16 * getResources().getDisplayMetrics().density));

        layout.addView(tvSub);



        EditText input = new EditText(requireContext());

        input.setText(VeilFeatureManager.getInstance(requireContext()).getPanicSafeWord());

        input.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        input.setBackgroundResource(R.drawable.bg_search_input);

        int inputPad = (int) (14 * getResources().getDisplayMetrics().density);

        input.setPadding(inputPad, inputPad, inputPad, inputPad);

        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        inLp.bottomMargin = (int) (20 * getResources().getDisplayMetrics().density);

        input.setLayoutParams(inLp);

        layout.addView(input);



        MaterialButton btnSave = new MaterialButton(requireContext());

        btnSave.setText("Save Safe Word");

        btnSave.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        btnSave.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));

        btnSave.setTextColor(0xFFFFFFFF);

        layout.addView(btnSave);



        btnSave.setOnClickListener(v -> {

            String word = input.getText().toString().trim();

            if (!word.isEmpty()) {

                VeilFeatureManager.getInstance(requireContext()).setPanicSafeWord(word);

                sheet.dismiss();

                showToast("🚨 Panic Safe Word updated!");

            }

        });



        sheet.setContentView(layout);

        sheet.show();

    }



    private void confirmInstantSecureWipe() {

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_destructive_sheet, null);

        sheet.setContentView(view);



        TextView tvTitle = view.findViewById(R.id.tvDestructiveTitle);

        TextView tvMsg = view.findViewById(R.id.tvDestructiveMessage);

        MaterialButton btnConfirm = view.findViewById(R.id.btnDestructiveConfirm);

        MaterialButton btnCancel = view.findViewById(R.id.btnDestructiveCancel);



        tvTitle.setText("💥 Instant Secure Wipe");

        tvMsg.setText("Are you sure you want to cryptographically shred all chats, messages, posts, call logs, and vault notes in your local SQLCipher database?");

        btnConfirm.setText("Shred All Data");



        btnConfirm.setOnClickListener(v -> {

            EncryptedChatDatabase.getInstance(requireContext()).secureWipeAllData();

            refreshGamificationAndVaultStats();

            sheet.dismiss();

            showToast("💥 Local SQLCipher vault wiped clean");

        });



        btnCancel.setOnClickListener(v -> sheet.dismiss());



        sheet.show();

    }



    public void openDeveloperAndSecurityConsole() {

        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());

        boolean integrityOk = com.m3.veil.utils.VeilRuntimeIntegrityGuard.verifyRuntimeIntegrity(requireContext());

        String diag = "=== VEIL P2P & CRYPTO DIAGNOSTIC CONSOLE ===\n"

                + "• Transport: 100% WebRTC P2P SCTP DataChannels\n"

                + "• Local DB: SQLCipher 256-bit AES (WAL + secure_delete=ON)\n"

                + "• Key Derivation: HKDF-SHA256 + ECDH secp256r1 Ratchet\n"

                + "• Runtime Guard: " + (integrityOk ? "VERIFIED (Clean)" : "ALERT (Debugger/Hook)") + "\n"

                + "• APK Cert SHA-256: " + com.m3.veil.utils.VeilRuntimeIntegrityGuard.getApkCertSha256() + "\n"

                + "• Active Persona: " + fm.getActivePersona().getDisplayName() + "\n"

                + "• Privacy Score: " + fm.calculatePrivacyScore(true) + "/100\n"

                + "• Client Word Filter: " + (fm.isWordFilterEnabled() ? "ENABLED" : "DISABLED");



        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        LinearLayout layout = new LinearLayout(requireContext());

        layout.setOrientation(LinearLayout.VERTICAL);

        layout.setBackgroundResource(R.drawable.bottom_sheet_background);

        int padH = (int) (24 * getResources().getDisplayMetrics().density);

        int padV = (int) (16 * getResources().getDisplayMetrics().density);

        layout.setPadding(padH, padV, padH, padV * 2);



        View handle = new View(requireContext());

        int handleW = (int) (36 * getResources().getDisplayMetrics().density);

        int handleH = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);

        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);

        handle.setLayoutParams(handleLp);

        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);

        layout.addView(handle);



        TextView tvTitle = new TextView(requireContext());

        tvTitle.setText("🛠️ Security & Developer Console");

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        tvTitle.setTextSize(19);

        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        layout.addView(tvTitle);



        TextView tvConsole = new TextView(requireContext());

        tvConsole.setText(diag);

        tvConsole.setTypeface(android.graphics.Typeface.MONOSPACE);

        tvConsole.setTextSize(12);

        tvConsole.setTextColor(0xFF38BDF8);

        tvConsole.setBackgroundResource(R.drawable.bg_search_input);

        int cPad = (int) (14 * getResources().getDisplayMetrics().density);

        tvConsole.setPadding(cPad, cPad, cPad, cPad);

        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        cLp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);

        cLp.bottomMargin = (int) (18 * getResources().getDisplayMetrics().density);

        tvConsole.setLayoutParams(cLp);

        layout.addView(tvConsole);



        MaterialButton btnToggleFilter = new MaterialButton(requireContext());

        btnToggleFilter.setText("Toggle Word Filter");

        btnToggleFilter.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        btnToggleFilter.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));

        btnToggleFilter.setTextColor(0xFFFFFFFF);

        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        bLp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);

        btnToggleFilter.setLayoutParams(bLp);

        layout.addView(btnToggleFilter);



        MaterialButton btnSeedDemo = new MaterialButton(requireContext());

        btnSeedDemo.setText("Seed Local Demo Vault (SampleDataManager)");

        btnSeedDemo.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        btnSeedDemo.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0F766E));

        btnSeedDemo.setTextColor(0xFFFFFFFF);

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        sLp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);

        btnSeedDemo.setLayoutParams(sLp);

        layout.addView(btnSeedDemo);



        MaterialButton btnClose = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);

        btnClose.setText("Close");

        btnClose.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));

        btnClose.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        layout.addView(btnClose);



        btnToggleFilter.setOnClickListener(v -> {

            fm.setWordFilterEnabled(!fm.isWordFilterEnabled());

            sheet.dismiss();

            showToast("Client ML-lite Word Filter: " + (fm.isWordFilterEnabled() ? "ON" : "OFF"));

        });

        btnSeedDemo.setOnClickListener(v -> {

            sheet.dismiss();

            com.m3.veil.utils.SampleDataManager.generateSampleData(requireContext(), new com.m3.veil.utils.SampleDataManager.OnDataGeneratedListener() {

                @Override

                public void onSuccess() {

                    refreshGamificationAndVaultStats();

                    showToast("Seeded local demo chats & VeilBoard posts!");

                }



                @Override

                public void onFailure(Exception e) {

                    showToast("Demo seed notice: " + e.getMessage());

                }

            });

        });

        btnClose.setOnClickListener(v -> sheet.dismiss());



        sheet.setContentView(layout);

        sheet.show();

    }



    private void loadUserData() {

        String currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null && mAuth != null && mAuth.getCurrentUser() != null) {
            currentUserId = mAuth.getCurrentUser().getUid();
        }



        com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(requireContext());

        User localUser = up.getCurrentUser();



        if (currentUserId == null && localUser == null) {

            emptyState.setVisibility(View.VISIBLE);

            return;

        }



        emptyState.setVisibility(View.GONE);

        loadingBar.setVisibility(View.GONE);

        profileContent.setVisibility(View.VISIBLE);



        if (localUser != null) {

            updateUIWithUserData(localUser);

        }



        if (currentUserId != null) {

            java.io.File avatarFile = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext()).getAvatarWebpFile(currentUserId);

            if (avatarFile.exists()) {

                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(avatarFile.getAbsolutePath());

                if (bmp != null && profileImage != null) {

                    profileImage.setImageBitmap(bmp);

                }

            }

        }



        refreshGamificationAndVaultStats();



        if (currentUserId != null && db != null) {

            try {

                db.collection("users").document(currentUserId)

                        .get()

                        .addOnCompleteListener(task -> {

                            if (isAdded()) {

                                if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {

                                    User user = task.getResult().toObject(User.class);

                                    if (user != null) {

                                        updateUIWithUserData(user);

                                    }

                                }

                                refreshGamificationAndVaultStats();

                            }

                        });

            } catch (Exception ignored) {}

        }

    }



    private void updateUIWithUserData(User user) {

        if (user.getDisplayName() != null && !user.getDisplayName().isEmpty()) {

            profileName.setText(user.getDisplayName());

        } else {

            profileName.setText(R.string.anonymous);

        }



        if (user.getUsername() != null && !user.getUsername().isEmpty()) {

            profileUsername.setText("@" + user.getUsername());

        } else {

            profileUsername.setText("@username");

        }



        if (profileImage != null) {

            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(profileImage, user.getAvatar(), user.getDisplayName(), false);

        }

    }



    private void setAppVersion() {

        try {

            String versionName = requireContext().getPackageManager()

                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;

            appVersion.setText("v" + versionName + " (P2P E2EE)");

        } catch (Exception e) {

            appVersion.setText("v2.0.0 (P2P E2EE)");

        }

    }



    public void openEditProfile() {

        EditProfileBottomSheet sheet = new EditProfileBottomSheet();

        sheet.setOnProfileUpdatedListener(this::loadUserData);

        sheet.show(getChildFragmentManager(), "EditProfileBottomSheet");

    }



    private void openAccountSettings() {

        AccountSettingsBottomSheet sheet = new AccountSettingsBottomSheet();

        sheet.show(getChildFragmentManager(), "AccountSettingsBottomSheet");

    }



    private void openNotificationSettings() {

        startActivity(new Intent(requireContext(), com.m3.veil.ui.settings.NotificationSettingsActivity.class));

    }



    private void openAppearanceSettings() {

        AppearanceSettingsBottomSheet sheet = new AppearanceSettingsBottomSheet();

        sheet.show(getChildFragmentManager(), "AppearanceSettingsBottomSheet");

    }



    private void openStorageSettings() {

        StorageSettingsBottomSheet sheet = new StorageSettingsBottomSheet();

        sheet.show(getChildFragmentManager(), "StorageSettingsBottomSheet");

    }



    private void openLanguageSettings() {

        LanguageSettingsBottomSheet sheet = new LanguageSettingsBottomSheet();

        sheet.setOnLanguageSelectedListener((code, name) -> {

            if (currentLanguage != null) currentLanguage.setText(name);

        });

        sheet.show(getChildFragmentManager(), "LanguageSettingsBottomSheet");

    }



    private void openAbout() {

        AboutBottomSheet sheet = new AboutBottomSheet();

        sheet.show(getChildFragmentManager(), "AboutBottomSheet");

    }



    public void logout() {

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());

        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_destructive_sheet, null);

        sheet.setContentView(view);



        TextView tvTitle = view.findViewById(R.id.tvDestructiveTitle);

        TextView tvMsg = view.findViewById(R.id.tvDestructiveMessage);

        MaterialButton btnConfirm = view.findViewById(R.id.btnDestructiveConfirm);

        MaterialButton btnCancel = view.findViewById(R.id.btnDestructiveCancel);



        tvTitle.setText("Log Out of VEIL");

        tvMsg.setText("Are you sure you want to log out of VEIL? Your encrypted local session will be closed.");

        btnConfirm.setText("Log Out");



        btnConfirm.setOnClickListener(v -> {

            sheet.dismiss();
            try {
                if (mAuth != null) mAuth.signOut();
            } catch (Exception ignored) {}
            com.m3.veil.manager.AuthManager.getInstance().signOut(requireContext());
            Intent intent = new Intent(requireContext(), com.m3.veil.ui.auth.PhoneAuthActivity.class);

            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            startActivity(intent);

            requireActivity().finish();

        });



        btnCancel.setOnClickListener(v -> sheet.dismiss());



        sheet.show();

    }



    public void openRepostsDialog() {

        if (getContext() == null) return;

        List<EncryptedChatDatabase.VeilBoardRepost> reposts =

                EncryptedChatDatabase.getInstance(requireContext()).getAllReposts();



        com.google.android.material.bottomsheet.BottomSheetDialog sheet =

                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());

        LinearLayout root = new LinearLayout(requireContext());

        root.setOrientation(LinearLayout.VERTICAL);

        root.setBackgroundResource(R.drawable.bottom_sheet_background);

        int padH = (int) (20 * getResources().getDisplayMetrics().density);

        int padV = (int) (16 * getResources().getDisplayMetrics().density);

        root.setPadding(padH, padV, padH, padV * 2);



        View handle = new View(requireContext());

        int handleW = (int) (36 * getResources().getDisplayMetrics().density);

        int handleH = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);

        handleLp.gravity = Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (14 * getResources().getDisplayMetrics().density);

        handle.setLayoutParams(handleLp);

        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);

        root.addView(handle);



        TextView tvTitle = new TextView(requireContext());

        tvTitle.setText("🔄 My Reposts & Quotes (#92)");

        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));

        tvTitle.setTextSize(18);

        tvTitle.setTypeface(null, Typeface.BOLD);

        root.addView(tvTitle);



        TextView tvSubtitle = new TextView(requireContext());

        tvSubtitle.setText(reposts.size() + " items quote-shared or reposted to your profile");

        tvSubtitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

        tvSubtitle.setTextSize(13);

        tvSubtitle.setPadding(0, 4, 0, (int) (14 * getResources().getDisplayMetrics().density));

        root.addView(tvSubtitle);



        ScrollView scroll = new ScrollView(requireContext());

        LinearLayout listContainer = new LinearLayout(requireContext());

        listContainer.setOrientation(LinearLayout.VERTICAL);



        if (reposts.isEmpty()) {

            TextView emptyTv = new TextView(requireContext());

            emptyTv.setText("You haven't reposted or quote-shared any VeilBoard posts yet. Tap the share button on any post in Discover to quote-share.");

            emptyTv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

            emptyTv.setTextSize(13.5f);

            emptyTv.setPadding(0, (int) (16 * getResources().getDisplayMetrics().density), 0, 0);

            listContainer.addView(emptyTv);

        } else {

            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault());

            for (EncryptedChatDatabase.VeilBoardRepost rep : reposts) {

                com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(requireContext());

                card.setRadius(24f);

                card.setCardElevation(0f);

                card.setStrokeWidth(2);

                card.setStrokeColor(Color.parseColor("#263238"));

                card.setCardBackgroundColor(Color.parseColor("#1A202C"));

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

                cardLp.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);

                card.setLayoutParams(cardLp);



                LinearLayout itemLayout = new LinearLayout(requireContext());

                itemLayout.setOrientation(LinearLayout.VERTICAL);

                itemLayout.setPadding((int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density));



                TextView tvHeader = new TextView(requireContext());

                tvHeader.setText("🔄 Reposted from @" + (rep.originalAuthorName != null ? rep.originalAuthorName : "anon"));

                tvHeader.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));

                tvHeader.setTextSize(14.5f);

                tvHeader.setTypeface(null, Typeface.BOLD);

                itemLayout.addView(tvHeader);



                if (rep.quoteText != null && !rep.quoteText.trim().isEmpty()) {

                    TextView tvQuote = new TextView(requireContext());

                    tvQuote.setText("\"" + rep.quoteText + "\"");

                    tvQuote.setTextColor(Color.WHITE);

                    tvQuote.setTextSize(14f);

                    tvQuote.setPadding(0, 4, 0, 4);

                    itemLayout.addView(tvQuote);

                }



                TextView tvTime = new TextView(requireContext());

                tvTime.setText(sdf.format(new Date(rep.timestamp)));

                tvTime.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));

                tvTime.setTextSize(12f);

                itemLayout.addView(tvTime);



                card.addView(itemLayout);

                listContainer.addView(card);

            }

        }



        scroll.addView(listContainer);

        root.addView(scroll);

        sheet.setContentView(root);

        sheet.show();

    }



    private void showToast(String message) {

        if (isAdded()) {

            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();

        }

    }

}

