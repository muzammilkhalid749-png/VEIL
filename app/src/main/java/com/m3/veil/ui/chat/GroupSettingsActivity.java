package com.m3.veil.ui.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.manager.VeilMessagesController;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.chat.sheets.SharedMediaGalleryBottomSheet;
import com.m3.veil.ui.chat.sheets.ThemeWallpaperBottomSheet;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated Group & Channel Administrative Console (`GroupSettingsActivity`).
 * Features:
 * - Group Media Visibility: Toggle whether group media shows in mobile Gallery & File Manager
 * - Group Theme & Wallpaper: Full mesh-synchronized theme & wallpaper picker with live preview
 * - Shared Media, Links & Docs: Direct WhatsApp/Telegram-style 4-tab media console
 * - Group/Channel Identity (Title, @handle, Description, Public Directory listing)
 * - Member Permissions Matrix (Send messages, Send media, Create polls, Pin messages, Add members)
 * - Anti-Spam Slow Mode Cooldown (Off, 10s, 30s, 1m, 5m, 15m)
 * - Cryptographic Sender-Key Ratchet Rotation & Invite Link
 */
public class GroupSettingsActivity extends AppCompatActivity {

    private String chatId;
    private Chat currentChat;
    private String currentUserId;

    private EditText etGroupName;
    private EditText etGroupHandle;
    private EditText etGroupDesc;
    private CheckBox cbPublicDirectory;
    private CheckBox cbAdminsOnlySend;
    private CheckBox cbAllowMedia;
    private CheckBox cbAllowPolls;
    private CheckBox cbAllowPin;
    private CheckBox cbAllowMemberInvite;
    private CheckBox cbApproveNewMembers;
    private SwitchMaterial switchGroupMediaVisibility;
    private int selectedSlowModeSec = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);

        chatId = getIntent().getStringExtra("chatId");
        currentUserId = AuthManager.getInstance().getCurrentUserId(this);
        if (chatId != null) {
            currentChat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        }

        buildConsoleUi();
    }

    private void buildConsoleUi() {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBackground));

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // Header Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(ContextCompat.getColor(this, R.color.colorSurface));
        topBar.setPadding((int) (12 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

        ImageView btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setColorFilter(ContextCompat.getColor(this, R.color.colorTextPrimary));
        int iconPad = (int) (8 * dp);
        btnBack.setPadding(iconPad, iconPad, iconPad, iconPad);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));

        TextView tvHeader = new TextView(this);
        boolean isChannel = currentChat != null && currentChat.isChannel();
        tvHeader.setText(isChannel ? "⚙️ Channel Admin Console" : "⚙️ Group Settings & Permissions");
        tvHeader.setTextSize(17.5f);
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hLp.leftMargin = (int) (10 * dp);
        tvHeader.setLayoutParams(hLp);
        topBar.addView(tvHeader);
        root.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        content.setPadding(pad, pad, pad, (int) (32 * dp));

        SharedPreferences prefs = getSharedPreferences("VeilGroupPrefs", MODE_PRIVATE);

        // Section 0: Group Participants & Role Hierarchy (Phase 6)
        MaterialCardView membersCard = createSectionCard(dp);
        LinearLayout membersBox = createCardInnerLayout(dp);
        membersBox.addView(createSectionHeader("👥 Group Participants & Role Hierarchy", dp));

        EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(this);
        if (currentChat != null && currentChat.getParticipants() != null) {
            String ownerId = currentChat.getAdminId() != null ? currentChat.getAdminId() : currentUserId;
            db.saveGroupMembers(chatId, currentChat.getParticipants(), ownerId);
        }
        java.util.List<EncryptedChatDatabase.GroupMemberRecord> memberRecords =
                chatId != null ? db.getGroupMembers(chatId) : new java.util.ArrayList<>();
        String myRole = chatId != null ? db.getGroupMemberRole(chatId, currentUserId) : EncryptedChatDatabase.ROLE_MEMBER;
        boolean amIOwner = EncryptedChatDatabase.ROLE_OWNER.equals(myRole)
                || (currentChat != null && currentUserId != null && currentUserId.equals(currentChat.getAdminId()));
        boolean amIAdmin = amIOwner || EncryptedChatDatabase.ROLE_ADMIN.equals(myRole)
                || (currentChat != null && currentUserId != null && currentChat.isAdmin(currentUserId));

        TextView tvRoleSummary = new TextView(this);
        tvRoleSummary.setText("Your Role: " + myRole + " • Total Participants: " + memberRecords.size());
        tvRoleSummary.setTextSize(12.5f);
        tvRoleSummary.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvRoleSummary.setPadding(0, 0, 0, (int) (10 * dp));
        membersBox.addView(tvRoleSummary);

        for (EncryptedChatDatabase.GroupMemberRecord rec : memberRecords) {
            LinearLayout mRow = new LinearLayout(this);
            mRow.setOrientation(LinearLayout.HORIZONTAL);
            mRow.setGravity(Gravity.CENTER_VERTICAL);
            mRow.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));

            TextView tvPeer = new TextView(this);
            String label = rec.peerId.equals(currentUserId)
                    ? "You (" + (rec.peerId.length() > 6 ? rec.peerId.substring(0, 6) : rec.peerId) + ")"
                    : ("Peer #" + (rec.peerId.length() > 6 ? rec.peerId.substring(0, 6) : rec.peerId));
            tvPeer.setText(label);
            tvPeer.setTextSize(14f);
            tvPeer.setTypeface(null, Typeface.BOLD);
            tvPeer.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
            tvPeer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mRow.addView(tvPeer);

            TextView tvBadge = new TextView(this);
            String roleBadge = EncryptedChatDatabase.ROLE_OWNER.equals(rec.role)
                    ? "👑 OWNER" : (EncryptedChatDatabase.ROLE_ADMIN.equals(rec.role) ? "🛡️ ADMIN" : "👤 MEMBER");
            tvBadge.setText(roleBadge);
            tvBadge.setTextSize(11f);
            tvBadge.setTypeface(null, Typeface.BOLD);
            tvBadge.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            tvBadge.setBackgroundResource(R.drawable.bg_filter_chip);
            tvBadge.setPadding((int) (8 * dp), (int) (3 * dp), (int) (8 * dp), (int) (3 * dp));
            mRow.addView(tvBadge);

            if (amIAdmin && !rec.peerId.equals(currentUserId)) {
                mRow.setOnClickListener(v -> {
                    Intent manageIntent = new Intent(this, UserProfileActivity.class);
                    manageIntent.putExtra("chatId", chatId);
                    manageIntent.putExtra("isGroup", true);
                    manageIntent.putExtra("userName", currentChat != null ? currentChat.getName() : "Group Info");
                    startActivity(manageIntent);
                });
            }
            membersBox.addView(mRow);
        }

        MaterialButton btnManageParticipants = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnManageParticipants.setText("➕ Add / Manage Group Participants & Roles");
        btnManageParticipants.setCornerRadius((int) (12 * dp));
        btnManageParticipants.setOnClickListener(v -> {
            Intent manageIntent = new Intent(this, UserProfileActivity.class);
            manageIntent.putExtra("chatId", chatId);
            manageIntent.putExtra("isGroup", true);
            manageIntent.putExtra("userName", currentChat != null ? currentChat.getName() : "Group Info");
            startActivity(manageIntent);
        });
        membersBox.addView(btnManageParticipants);
        membersCard.addView(membersBox);
        content.addView(membersCard);

        // Section 1: Media Visibility in Gallery & File Manager (NEW)
        MaterialCardView visibilityCard = createSectionCard(dp);
        LinearLayout visibilityBox = createCardInnerLayout(dp);
        visibilityBox.addView(createSectionHeader("🖼️ Group Media Visibility", dp));

        boolean mediaVisEnabled = VeilMessagesController.isMediaVisibilityEnabled(this, chatId);
        switchGroupMediaVisibility = new SwitchMaterial(this);
        switchGroupMediaVisibility.setText("Show Group Media in Phone Gallery & Files");
        switchGroupMediaVisibility.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        switchGroupMediaVisibility.setChecked(mediaVisEnabled);
        visibilityBox.addView(switchGroupMediaVisibility);

        TextView tvVisHint = new TextView(this);
        tvVisHint.setText("When enabled, photos, videos, and files downloaded from this group will be visible in your phone's Gallery, Google Photos, and File Manager. When turned off, they remain in the encrypted vault.");
        tvVisHint.setTextSize(12.5f);
        tvVisHint.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvVisHint.setPadding(0, (int) (6 * dp), 0, (int) (4 * dp));
        visibilityBox.addView(tvVisHint);

        visibilityCard.addView(visibilityBox);
        content.addView(visibilityCard);

        // Section 2: Shared Media, Links & Docs Navigation Card
        MaterialCardView mediaNavCard = createSectionCard(dp);
        LinearLayout mediaNavBox = createCardInnerLayout(dp);
        mediaNavBox.addView(createSectionHeader("📁 Group Media, Links & Docs", dp));

        TextView tvMediaNavDesc = new TextView(this);
        tvMediaNavDesc.setText("Browse photos, videos, audio clips, documents, and shared web links exchanged in this group.");
        tvMediaNavDesc.setTextSize(12.5f);
        tvMediaNavDesc.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvMediaNavDesc.setPadding(0, 0, 0, (int) (10 * dp));
        mediaNavBox.addView(tvMediaNavDesc);

        MaterialButton btnOpenSharedMedia = new MaterialButton(this);
        btnOpenSharedMedia.setText("📂 Open Group Media Gallery");
        btnOpenSharedMedia.setCornerRadius((int) (12 * dp));
        btnOpenSharedMedia.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnOpenSharedMedia.setTextColor(Color.WHITE);
        btnOpenSharedMedia.setOnClickListener(v -> {
            if (chatId != null) {
                SharedMediaGalleryBottomSheet sheet = SharedMediaGalleryBottomSheet.newInstance(
                        chatId, currentChat != null ? currentChat.getName() : "Group Media"
                );
                sheet.show(getSupportFragmentManager(), "SharedMediaGalleryBottomSheet");
            }
        });
        mediaNavBox.addView(btnOpenSharedMedia);

        mediaNavCard.addView(mediaNavBox);
        content.addView(mediaNavCard);

        // Section 3: Group Theme & Wallpaper (Full synchronizer with live preview)
        MaterialCardView themeCard = createSectionCard(dp);
        LinearLayout themeBox = createCardInnerLayout(dp);
        themeBox.addView(createSectionHeader("🎨 Group Theme & Wallpaper", dp));

        TextView tvThemeDesc = new TextView(this);
        tvThemeDesc.setText("Synchronize custom wallpaper, colors, and doodle patterns across all group participants.");
        tvThemeDesc.setTextSize(12.5f);
        tvThemeDesc.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvThemeDesc.setPadding(0, 0, 0, (int) (10 * dp));
        themeBox.addView(tvThemeDesc);

        MaterialButton btnOpenThemePicker = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnOpenThemePicker.setText("🎨 Customize & Broadcast Group Theme");
        btnOpenThemePicker.setCornerRadius((int) (12 * dp));
        btnOpenThemePicker.setOnClickListener(v -> {
            ThemeWallpaperBottomSheet sheet = ThemeWallpaperBottomSheet.newInstance(chatId, null, true);
            sheet.show(getSupportFragmentManager(), "ThemeWallpaperBottomSheet");
        });
        themeBox.addView(btnOpenThemePicker);

        themeCard.addView(themeBox);
        content.addView(themeCard);

        // Section 4: Identity & Public Directory
        MaterialCardView idCard = createSectionCard(dp);
        LinearLayout idBox = createCardInnerLayout(dp);
        idBox.addView(createSectionHeader("🏛️ Identity & Public Directory", dp));

        etGroupName = createStyledInput("Group / Channel Name", currentChat != null ? currentChat.getName() : "", dp);
        idBox.addView(etGroupName);

        etGroupHandle = createStyledInput("@handle (for Public Directory search)", currentChat != null && currentChat.getHandle() != null ? currentChat.getHandle() : "", dp);
        idBox.addView(etGroupHandle);

        etGroupDesc = createStyledInput("Description, rules, or topic summary", currentChat != null && currentChat.getDescription() != null ? currentChat.getDescription() : "", dp);
        idBox.addView(etGroupDesc);

        cbPublicDirectory = new CheckBox(this);
        cbPublicDirectory.setText("🌐 List in Public Discovery Hub Directory");
        cbPublicDirectory.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        cbPublicDirectory.setChecked(currentChat != null && currentChat.isPublic());
        idBox.addView(cbPublicDirectory);

        idCard.addView(idBox);
        content.addView(idCard);

        // Section 5: Member Permissions Matrix
        MaterialCardView permCard = createSectionCard(dp);
        LinearLayout permBox = createCardInnerLayout(dp);
        permBox.addView(createSectionHeader("🛡️ Member Permissions Matrix", dp));

        cbAdminsOnlySend = createCheckRow("🔒 Only Admins Can Post Messages (Broadcast Mode)",
                prefs.getBoolean("admins_only_send_" + chatId, isChannel));
        permBox.addView(cbAdminsOnlySend);

        cbAllowMedia = createCheckRow("🖼️ Allow Members to Send Photos, Videos & Voice Notes",
                prefs.getBoolean("allow_media_" + chatId, true));
        permBox.addView(cbAllowMedia);

        cbAllowPolls = createCheckRow("📊 Allow Members to Create Polls & Quizzes",
                prefs.getBoolean("allow_polls_" + chatId, true));
        permBox.addView(cbAllowPolls);

        cbAllowPin = createCheckRow("📌 Allow Members to Pin Important Messages",
                prefs.getBoolean("members_can_pin_" + chatId, true));
        permBox.addView(cbAllowPin);

        cbAllowMemberInvite = createCheckRow("👥 Allow Members to Invite New Peers",
                prefs.getBoolean("members_can_invite_" + chatId, true));
        permBox.addView(cbAllowMemberInvite);

        cbApproveNewMembers = createCheckRow("🛂 Require Admin Approval for Invite Link Joins",
                prefs.getBoolean("require_admin_approval_" + chatId, false));
        permBox.addView(cbApproveNewMembers);

        permCard.addView(permBox);
        content.addView(permCard);

        // Section 6: Slow Mode Cooldown
        MaterialCardView slowCard = createSectionCard(dp);
        LinearLayout slowBox = createCardInnerLayout(dp);
        slowBox.addView(createSectionHeader("⏱️ Anti-Spam Slow Mode Cooldown", dp));

        selectedSlowModeSec = prefs.getInt("slow_mode_sec_" + chatId, 0);
        String[] slowLabels = {"Off", "10s", "30s", "1m", "5m", "15m"};
        int[] slowValues = {0, 10, 30, 60, 300, 900};

        LinearLayout slowRow = new LinearLayout(this);
        slowRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < slowLabels.length; i++) {
            final int val = slowValues[i];
            TextView chip = new TextView(this);
            chip.setText(slowLabels[i]);
            chip.setTextSize(12.5f);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setPadding((int) (11 * dp), (int) (7 * dp), (int) (11 * dp), (int) (7 * dp));
            boolean sel = (val == selectedSlowModeSec);
            chip.setBackgroundResource(sel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(sel ? Color.WHITE : ContextCompat.getColor(this, R.color.colorTextPrimary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                selectedSlowModeSec = val;
                for (int j = 0; j < slowRow.getChildCount(); j++) {
                    TextView c = (TextView) slowRow.getChildAt(j);
                    boolean isSel = (slowValues[j] == val);
                    c.setBackgroundResource(isSel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                    c.setTextColor(isSel ? Color.WHITE : ContextCompat.getColor(this, R.color.colorTextPrimary));
                }
            });
            slowRow.addView(chip);
        }
        slowBox.addView(slowRow);
        slowCard.addView(slowBox);
        content.addView(slowCard);

        // Section 7: Cryptographic Sender-Key Ratchet & Invite Link
        MaterialCardView cryptoCard = createSectionCard(dp);
        LinearLayout cryptoBox = createCardInnerLayout(dp);
        cryptoBox.addView(createSectionHeader("🔐 Cryptographic Group Security", dp));

        MaterialButton btnRotateKeys = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnRotateKeys.setText("🔄 Rotate Group Sender-Key Ratchet Now");
        btnRotateKeys.setCornerRadius((int) (12 * dp));
        btnRotateKeys.setOnClickListener(v -> {
            int newEpoch = com.m3.veil.utils.EncryptionManager.rotateGroupSenderKeyEpoch(chatId);
            try {
                JSONObject pkt = new JSONObject();
                pkt.put("action", "group_key_ratchet_rotate");
                pkt.put("groupId", chatId);
                pkt.put("epoch", newEpoch);
                WebRtcManager.getInstance(this).broadcastPacket(
                        new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_GROUP_SETTINGS, pkt.toString())
                );
            } catch (Exception ignored) {}
            if (chatId != null) {
                Map<String, Object> epochUpdate = new HashMap<>();
                epochUpdate.put("groupKeyEpoch", newEpoch);
                FirebaseFirestore.getInstance().collection("groups").document(chatId)
                        .set(epochUpdate, SetOptions.merge());
            }
            Toast.makeText(this, "🔐 Rotated to Sender-Key Epoch #" + newEpoch + " across all group peers!", Toast.LENGTH_SHORT).show();
        });
        cryptoBox.addView(btnRotateKeys);

        MaterialButton btnCopyLink = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCopyLink.setText("🔗 Copy Group Invite Link");
        btnCopyLink.setCornerRadius((int) (12 * dp));
        btnCopyLink.setOnClickListener(v -> {
            String token = prefs.getString("invite_token_" + chatId, chatId);
            String link = "veil://join/" + token;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("VEIL Group Invite", link));
                Toast.makeText(this, "Copied: " + link, Toast.LENGTH_SHORT).show();
            }
        });
        cryptoBox.addView(btnCopyLink);

        cryptoCard.addView(cryptoBox);
        content.addView(cryptoCard);

        // Save All Button
        MaterialButton btnSaveAll = new MaterialButton(this);
        btnSaveAll.setText("Save & Broadcast Group Settings");
        btnSaveAll.setCornerRadius((int) (14 * dp));
        btnSaveAll.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnSaveAll.setTextColor(Color.WHITE);
        btnSaveAll.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        btnSaveAll.setOnClickListener(v -> saveAndSyncGroupSettings());
        content.addView(btnSaveAll);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private MaterialCardView createSectionCard(float dp) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.colorSurface));
        card.setRadius(16 * dp);
        card.setCardElevation(0);
        card.setStrokeWidth((int) (1 * dp));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.colorOutline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (14 * dp);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout createCardInnerLayout(float dp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private TextView createSectionHeader(String text, float dp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        tv.setPadding(0, 0, 0, (int) (10 * dp));
        return tv;
    }

    private EditText createStyledInput(String hint, String initialValue, float dp) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(initialValue);
        et.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        et.setBackgroundResource(R.drawable.bg_search_input);
        int pad = (int) (12 * dp);
        et.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * dp);
        et.setLayoutParams(lp);
        return et;
    }

    private CheckBox createCheckRow(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        cb.setChecked(checked);
        return cb;
    }

    private void saveAndSyncGroupSettings() {
        if (chatId == null) return;
        String newName = etGroupName.getText().toString().trim();
        String newHandle = etGroupHandle.getText().toString().trim().replace("@", "");
        String newDesc = etGroupDesc.getText().toString().trim();

        if (newName.isEmpty()) {
            etGroupName.setError("Name cannot be empty");
            return;
        }

        // Save Media Visibility for this group
        if (switchGroupMediaVisibility != null) {
            VeilMessagesController.setMediaVisibilityEnabled(this, chatId, switchGroupMediaVisibility.isChecked());
        }

        String[] prefNamespaces = {"VeilGroupPrefs", "veil_group_perms"};
        for (String ns : prefNamespaces) {
            SharedPreferences p = getSharedPreferences(ns, MODE_PRIVATE);
            p.edit()
                    .putBoolean("admins_only_send_" + chatId, cbAdminsOnlySend.isChecked())
                    .putBoolean("allow_media_" + chatId, cbAllowMedia.isChecked())
                    .putBoolean("allow_polls_" + chatId, cbAllowPolls.isChecked())
                    .putBoolean("members_can_pin_" + chatId, cbAllowPin.isChecked())
                    .putBoolean("members_can_invite_" + chatId, cbAllowMemberInvite.isChecked())
                    .putBoolean("require_admin_approval_" + chatId, cbApproveNewMembers.isChecked())
                    .putInt("slow_mode_sec_" + chatId, selectedSlowModeSec)
                    .apply();
        }

        if (currentChat != null) {
            currentChat.setName(newName);
            currentChat.setHandle(newHandle);
            currentChat.setDescription(newDesc);
            currentChat.setPublic(cbPublicDirectory.isChecked());
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentChat);
            WebRtcManager.getInstance(this).sendGroupInviteToPeers(currentChat);
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("groupId", chatId);
        updates.put("groupName", newName);
        updates.put("handle", newHandle);
        updates.put("description", newDesc);
        updates.put("isPublic", cbPublicDirectory.isChecked());
        updates.put("adminsOnlySend", cbAdminsOnlySend.isChecked());
        updates.put("allowMedia", cbAllowMedia.isChecked());
        updates.put("allowPolls", cbAllowPolls.isChecked());
        updates.put("allowPin", cbAllowPin.isChecked());
        updates.put("allowMemberInvite", cbAllowMemberInvite.isChecked());
        updates.put("requireAdminApproval", cbApproveNewMembers.isChecked());
        updates.put("slowModeSec", selectedSlowModeSec);
        updates.put("groupKeyEpoch", com.m3.veil.utils.EncryptionManager.getGroupSenderKeyEpoch(chatId));

        FirebaseFirestore.getInstance().collection("groups").document(chatId)
                .set(updates, SetOptions.merge());

        Toast.makeText(this, "✅ Group settings saved & synced to mesh!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
