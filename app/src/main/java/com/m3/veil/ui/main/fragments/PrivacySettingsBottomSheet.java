package com.m3.veil.ui.main.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;

import java.util.ArrayList;
import java.util.List;

public class PrivacySettingsBottomSheet extends BottomSheetDialogFragment {

    private TextView tvDefaultVanishStatus;
    private TextView tvLastSeenStatus;
    private TextView tvProfilePhotoStatus;
    private TextView tvBlockedCount;
    private TextView tvTorProxyStatus;
    private SwitchCompat switchReadReceipts;
    private SwitchCompat switchTorProxy;
    private SharedPreferences prefs;

    private String currentUserId;

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_privacy_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE);
        currentUserId = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "local";

        tvDefaultVanishStatus = view.findViewById(R.id.tvDefaultVanishStatus);
        tvLastSeenStatus = view.findViewById(R.id.tvLastSeenStatus);
        tvProfilePhotoStatus = view.findViewById(R.id.tvProfilePhotoStatus);
        tvBlockedCount = view.findViewById(R.id.tvBlockedCount);
        switchReadReceipts = view.findViewById(R.id.switchReadReceipts);

        // Read Receipts
        boolean readReceiptsEnabled = prefs.getBoolean("read_receipts_enabled", true);
        switchReadReceipts.setChecked(readReceiptsEnabled);
        switchReadReceipts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("read_receipts_enabled", isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "Read receipts enabled" : "Read receipts disabled", Toast.LENGTH_SHORT).show();
        });

        // Vanish Timer
        long defaultVanishTimer = prefs.getLong("default_vanish_timer", 0);
        updateTimerLabel(defaultVanishTimer);
        view.findViewById(R.id.btnDefaultVanishTimer).setOnClickListener(v -> showVanishTimerPicker());

        // Last Seen
        String lastSeen = prefs.getString("last_seen_visibility", "Everyone");
        tvLastSeenStatus.setText(lastSeen);
        view.findViewById(R.id.btnLastSeenPrivacy).setOnClickListener(v -> showVisibilityPicker("Last Seen & Online", "last_seen_visibility", tvLastSeenStatus));

        // Profile Photo
        String photoVis = prefs.getString("photo_visibility", "Everyone");
        tvProfilePhotoStatus.setText(photoVis);
        view.findViewById(R.id.btnProfilePhotoPrivacy).setOnClickListener(v -> showVisibilityPicker("Profile Photo Visibility", "photo_visibility", tvProfilePhotoStatus));

        // Blocked Contacts
        updateBlockedCount();
        view.findViewById(R.id.btnBlockedContacts).setOnClickListener(v -> showBlockedContactsDialog());

        // Tor / SOCKS5 Proxy Bridge (#116, #115)
        tvTorProxyStatus = view.findViewById(R.id.tvTorProxyStatus);
        switchTorProxy = view.findViewById(R.id.switchTorProxy);
        com.m3.veil.utils.VeilTorProxyBridge bridge = com.m3.veil.utils.VeilTorProxyBridge.getInstance(requireContext());

        boolean proxyActive = bridge.isProxyEnabled();
        switchTorProxy.setChecked(proxyActive);
        updateTorStatusLabel(bridge);

        switchTorProxy.setOnCheckedChangeListener((btn, isChecked) -> {
            bridge.setProxyEnabled(isChecked);
            updateTorStatusLabel(bridge);
            if (isChecked && !bridge.isOrbotInstalled()) {
                Toast.makeText(requireContext(), "Orbot not detected. Listening for SOCKS5 on 127.0.0.1:9050", Toast.LENGTH_LONG).show();
            } else if (isChecked) {
                Toast.makeText(requireContext(), "Tor Proxy Active • Strict TURN Relay Mode Enforced", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "Tor Proxy Disabled • Direct P2P Restored", Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.btnTorProxyBridge).setOnClickListener(v -> showTorProxyConfigDialog(bridge));
    }

    private void updateTorStatusLabel(com.m3.veil.utils.VeilTorProxyBridge bridge) {
        if (tvTorProxyStatus == null) return;
        if (bridge.isProxyEnabled()) {
            tvTorProxyStatus.setText("Active • " + bridge.getProxyHost() + ":" + bridge.getProxyPort() + " (Strict Relay)");
            tvTorProxyStatus.setTextColor(0xFF10B981); // Emerald green
        } else {
            tvTorProxyStatus.setText("Disabled • Direct P2P (No Proxy)");
            tvTorProxyStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
        }
    }

    private void showTorProxyConfigDialog(com.m3.veil.utils.VeilTorProxyBridge bridge) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 36, 48, 48);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet_modern);

        TextView title = new TextView(requireContext());
        title.setText("🧅 Tor / SOCKS5 Proxy Configuration");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
        root.addView(title);

        TextView desc = new TextView(requireContext());
        desc.setText("Route signaling and enforce strict TURN relay mode so zero public IP addresses leak via STUN.\n\n"
                + "Default Endpoint: 127.0.0.1:9050 (Orbot / Tor)\n"
                + "Orbot Installed: " + (bridge.isOrbotInstalled() ? "Yes ✅" : "No ❌"));
        desc.setTextSize(13);
        desc.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
        desc.setPadding(0, 12, 0, 24);
        root.addView(desc);

        if (!bridge.isOrbotInstalled()) {
            com.google.android.material.button.MaterialButton btnOrbot = new com.google.android.material.button.MaterialButton(requireContext());
            btnOrbot.setText("Get Orbot on Google Play");
            btnOrbot.setOnClickListener(v -> {
                startActivity(bridge.getOrbotLaunchIntent());
                sheet.dismiss();
            });
            root.addView(btnOrbot);
        } else {
            com.google.android.material.button.MaterialButton btnLaunch = new com.google.android.material.button.MaterialButton(requireContext());
            btnLaunch.setText("Open Orbot App");
            btnLaunch.setOnClickListener(v -> {
                startActivity(bridge.getOrbotLaunchIntent());
                sheet.dismiss();
            });
            root.addView(btnLaunch);
        }

        com.google.android.material.button.MaterialButton btnTest = new com.google.android.material.button.MaterialButton(requireContext());
        btnTest.setText("Test Proxy Connection");
        btnTest.setOnClickListener(v -> {
            bridge.checkReachability((reachable, latencyMs) -> {
                String msg = reachable
                        ? "Proxy reachable in " + latencyMs + "ms ✅"
                        : "Proxy unreachable at " + bridge.getProxyHost() + ":" + bridge.getProxyPort() + " ❌";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            });
        });
        root.addView(btnTest);

        sheet.setContentView(root);
        sheet.show();
    }

    private void updateTimerLabel(long timer) {
        if (timer == 10000) tvDefaultVanishStatus.setText("10 Seconds");
        else if (timer == 60000) tvDefaultVanishStatus.setText("1 Minute");
        else if (timer == 3600000) tvDefaultVanishStatus.setText("1 Hour");
        else if (timer == 86400000) tvDefaultVanishStatus.setText("1 Day");
        else tvDefaultVanishStatus.setText("Off (Manual only)");
    }

    private void showVanishTimerPicker() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        sheet.setContentView(R.layout.dialog_vanish_timer_sheet);

        LinearLayout optionsContainer = sheet.findViewById(R.id.vanishOptionsContainer);
        if (optionsContainer == null) return;

        String[] options = {"Off", "10 Seconds", "1 Minute", "1 Hour", "1 Day"};
        String[] descriptions = {
                "Messages persist normally in encrypted database",
                "Ultra-fast zero-trace vanishing for ephemeral secrets",
                "Quick shredding for brief confidential chats",
                "Medium-length conversation persistence",
                "Standard 24-hour daily wipe cycle"
        };
        long[] times = {0, 10000, 60000, 3600000, 86400000L};
        long current = prefs.getLong("default_vanish_timer", 0);

        optionsContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        for (int i = 0; i < times.length; i++) {
            final long selected = times[i];
            final String label = options[i];
            final boolean isSelected = (current == selected);

            MaterialCardView card = new MaterialCardView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            card.setLayoutParams(lp);
            card.setRadius(12 * dp);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * dp));
            card.setStrokeColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.colorPrimary : R.color.colorOutline));
            card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.surfaceVariant : android.R.color.transparent));

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(tcLp);

            TextView tvOpt = new TextView(requireContext());
            tvOpt.setText(label);
            tvOpt.setTextColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.colorPrimary : R.color.textPrimary));
            tvOpt.setTextSize(15.5f);
            tvOpt.setTypeface(null, Typeface.BOLD);
            textCol.addView(tvOpt);

            TextView tvDesc = new TextView(requireContext());
            tvDesc.setText(descriptions[i]);
            tvDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvDesc.setTextSize(12f);
            textCol.addView(tvDesc);
            row.addView(textCol);

            RadioButton rb = new RadioButton(requireContext());
            rb.setChecked(isSelected);
            rb.setClickable(false);
            rb.setFocusable(false);
            row.addView(rb);

            card.addView(row);
            card.setOnClickListener(v -> {
                sheet.dismiss();
                prefs.edit().putLong("default_vanish_timer", selected).apply();
                updateTimerLabel(selected);
                Toast.makeText(requireContext(), "Default vanish timer: " + label, Toast.LENGTH_SHORT).show();
            });

            optionsContainer.addView(card);
        }

        sheet.show();
    }

    private void showVisibilityPicker(String title, String prefKey, TextView statusView) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), (int) (28 * dp));

        View handle = new View(requireContext());
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams((int) (36 * dp), (int) (4 * dp));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = (int) (16 * dp);
        handle.setLayoutParams(hLp);
        handle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorOutline));
        root.addView(handle);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(title);
        tvTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setPadding(0, 0, 0, (int) (4 * dp));
        root.addView(tvTitle);

        TextView tvSub = new TextView(requireContext());
        tvSub.setText("Choose who can see this information on VeilChat:");
        tvSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        tvSub.setTextSize(13f);
        tvSub.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(tvSub);

        String[] options = {"Everyone", "Contacts Only", "Nobody"};
        String[] descriptions = {
                "Visible to all connected peers and incoming chats",
                "Visible exclusively to verified mutual contacts",
                "Completely hidden from everyone • Total stealth"
        };
        String current = prefs.getString(prefKey, "Everyone");

        for (int i = 0; i < options.length; i++) {
            final String selected = options[i];
            final boolean isSelected = selected.equalsIgnoreCase(current);

            MaterialCardView card = new MaterialCardView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (10 * dp);
            card.setLayoutParams(lp);
            card.setRadius(12 * dp);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * dp));
            card.setStrokeColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.colorPrimary : R.color.colorOutline));
            card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.surfaceVariant : android.R.color.transparent));

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (14 * dp), (int) (14 * dp), (int) (14 * dp));

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(tcLp);

            TextView tvOpt = new TextView(requireContext());
            tvOpt.setText(selected);
            tvOpt.setTextColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.colorPrimary : R.color.textPrimary));
            tvOpt.setTextSize(15.5f);
            tvOpt.setTypeface(null, Typeface.BOLD);
            textCol.addView(tvOpt);

            TextView tvDesc = new TextView(requireContext());
            tvDesc.setText(descriptions[i]);
            tvDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvDesc.setTextSize(12f);
            textCol.addView(tvDesc);
            row.addView(textCol);

            RadioButton rb = new RadioButton(requireContext());
            rb.setChecked(isSelected);
            rb.setClickable(false);
            rb.setFocusable(false);
            row.addView(rb);

            card.addView(row);
            card.setOnClickListener(v -> {
                sheet.dismiss();
                prefs.edit().putString(prefKey, selected).apply();
                statusView.setText(selected);

                if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                    FirebaseFirestore.getInstance().collection("users")
                            .document(currentUserId)
                            .update(prefKey, selected.toLowerCase().replace(" ", "_"));
                }

                Toast.makeText(requireContext(), title + ": " + selected, Toast.LENGTH_SHORT).show();
            });

            root.addView(card);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void updateBlockedCount() {
        List<Chat> allChats = EncryptedChatDatabase.getInstance(requireContext()).getAllChats();
        int count = 0;
        for (Chat c : allChats) {
            if (c.isBlocked() && currentUserId.equals(c.getBlockedBy())) {
                count++;
            }
        }
        tvBlockedCount.setText(count == 0 ? "None" : count + " contacts");
    }

    private void showBlockedContactsDialog() {
        List<Chat> allChats = EncryptedChatDatabase.getInstance(requireContext()).getAllChats();
        List<Chat> blockedList = new ArrayList<>();
        for (Chat c : allChats) {
            if (c.isBlocked() && currentUserId.equals(c.getBlockedBy())) {
                blockedList.add(c);
            }
        }

        if (blockedList.isEmpty()) {
            Toast.makeText(requireContext(), "No blocked contacts", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(requireContext());
        title.setText("Blocked Contacts");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(title);

        for (Chat chat : blockedList) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));

            TextView icon = new TextView(requireContext());
            icon.setText("🚫");
            icon.setTextSize(18f);
            icon.setPadding(0, 0, (int) (12 * dp), 0);
            row.addView(icon);

            TextView tvName = new TextView(requireContext());
            tvName.setText(chat.getName());
            tvName.setTextSize(15.5f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameLp);
            row.addView(tvName);

            com.google.android.material.button.MaterialButton btnUnblock =
                    new com.google.android.material.button.MaterialButton(requireContext());
            btnUnblock.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            btnUnblock.setElevation(0);
            btnUnblock.setStrokeWidth(0);
            btnUnblock.setText("Unblock");
            btnUnblock.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            btnUnblock.setOnClickListener(v -> {
                sheet.dismiss();
                promptUnblockChat(chat);
            });
            row.addView(btnUnblock);

            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void promptUnblockChat(Chat chat) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * dp), (int) (20 * dp), (int) (24 * dp), (int) (24 * dp));

        TextView title = new TextView(requireContext());
        title.setText("Unblock " + chat.getName() + "?");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
        root.addView(title);

        TextView msg = new TextView(requireContext());
        msg.setText("Unblocked contacts will be able to message and call you.");
        msg.setTextSize(14f);
        msg.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        msg.setPadding(0, (int) (8 * dp), 0, (int) (20 * dp));
        root.addView(msg);

        com.google.android.material.button.MaterialButton btnUnblock =
                new com.google.android.material.button.MaterialButton(requireContext());
        btnUnblock.setText("Unblock");
        btnUnblock.setCornerRadius((int) (12 * dp));
        btnUnblock.setOnClickListener(v -> {
            chat.setBlocked(false);
            chat.setBlockedBy(null);
            EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(chat);
            updateBlockedCount();
            Toast.makeText(requireContext(), chat.getName() + " unblocked", Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        root.addView(btnUnblock);

        com.google.android.material.button.MaterialButton btnCancel =
                new com.google.android.material.button.MaterialButton(requireContext());
        btnCancel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnCancel.setElevation(0);
        btnCancel.setStrokeWidth(0);
        btnCancel.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        btnCancel.setText("Cancel");
        btnCancel.setOnClickListener(v -> sheet.dismiss());
        root.addView(btnCancel);

        sheet.setContentView(root);
        sheet.show();
    }
}
