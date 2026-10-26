package com.m3.veil.ui.chat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
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
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Dedicated Per-Conversation Chat Settings & Privacy Console (`ChatSettingsActivity`).
 * Provides granular per-chat controls:
 * - Read Receipts (`✓✓` Blue Tick) & Typing Indicator toggle for this conversation
 * - Per-Chat Screenshot & Screen Recording Shield (`FLAG_SECURE`)
 * - Per-Chat 4-Digit Vault PIN Lock
 * - Custom Chat Theme & Wallpaper Synchronizer
 * - Encrypted Conversation Backup / JSON Transcript Export
 */
public class ChatSettingsActivity extends AppCompatActivity {

    private String chatId;
    private String peerUserId;
    private String peerUserName;
    private Chat currentChat;

    private CheckBox cbSendReadReceipts;
    private CheckBox cbSendTypingIndicators;
    private CheckBox cbScreenshotShield;
    private CheckBox cbAutoDownloadMedia;
    private EditText etPinLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);

        chatId = getIntent().getStringExtra("chatId");
        peerUserId = getIntent().getStringExtra("userId");
        peerUserName = getIntent().getStringExtra("userName");
        if (chatId != null) {
            currentChat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        }

        buildChatSettingsUi();
    }

    private void buildChatSettingsUi() {
        float dp = getResources().getDisplayMetrics().density;
        SharedPreferences prefs = getSharedPreferences("VeilChatSpecificPrefs", MODE_PRIVATE);

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
        tvHeader.setText("⚙️ Chat Settings • " + (peerUserName != null ? peerUserName : "Conversation"));
        tvHeader.setTextSize(17f);
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

        // 1. Privacy & Receipts Section
        MaterialCardView privacyCard = createSectionCard(dp);
        LinearLayout privacyBox = createCardInnerLayout(dp);
        privacyBox.addView(createSectionHeader("🛡️ Conversation Privacy & Receipts", dp));

        cbSendReadReceipts = createCheckRow("✓✓ Send Blue Read Receipts in this chat",
                prefs.getBoolean("read_receipts_" + chatId, true));
        privacyBox.addView(cbSendReadReceipts);

        cbSendTypingIndicators = createCheckRow("⌨️ Send Live Typing & Recording Indicators",
                prefs.getBoolean("typing_ind_" + chatId, true));
        privacyBox.addView(cbSendTypingIndicators);

        cbScreenshotShield = createCheckRow("📵 Enforce Screenshot & Screen-Record Shield (FLAG_SECURE)",
                prefs.getBoolean("screenshot_shield_" + chatId, true));
        privacyBox.addView(cbScreenshotShield);

        cbAutoDownloadMedia = createCheckRow("📥 Auto-Accept P2P Media Chunks over WebRTC",
                prefs.getBoolean("auto_media_" + chatId, true));
        privacyBox.addView(cbAutoDownloadMedia);

        privacyCard.addView(privacyBox);
        content.addView(privacyCard);

        // 2. Per-Chat Vault PIN Lock
        MaterialCardView pinCard = createSectionCard(dp);
        LinearLayout pinBox = createCardInnerLayout(dp);
        pinBox.addView(createSectionHeader("🔐 Per-Chat Vault PIN Lock", dp));

        boolean isChatLocked = currentChat != null && currentChat.getChatLockPin() != null && !currentChat.getChatLockPin().isEmpty();
        etPinLock = new EditText(this);
        etPinLock.setHint(isChatLocked
                ? "Chat is LOCKED • Enter new 4-digit PIN or leave blank"
                : "Set optional 4-digit PIN for this chat...");
        etPinLock.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPinLock.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        etPinLock.setHintTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        etPinLock.setBackgroundResource(R.drawable.bg_search_input);
        int boxPad = (int) (12 * dp);
        etPinLock.setPadding(boxPad, boxPad, boxPad, boxPad);
        pinBox.addView(etPinLock);

        if (isChatLocked) {
            MaterialButton btnRemovePin = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnRemovePin.setText("🔓 Remove Per-Chat PIN Lock");
            btnRemovePin.setCornerRadius((int) (12 * dp));
            btnRemovePin.setOnClickListener(v -> {
                if (currentChat != null) {
                    currentChat.setChatLockPin(null);
                    EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentChat);
                }
                Toast.makeText(this, "Per-chat PIN lock removed", Toast.LENGTH_SHORT).show();
            });
            pinBox.addView(btnRemovePin);
        }

        pinCard.addView(pinBox);
        content.addView(pinCard);

        // 3. Custom Chat Theme Swatches
        MaterialCardView themeCard = createSectionCard(dp);
        LinearLayout themeBox = createCardInnerLayout(dp);
        themeBox.addView(createSectionHeader("🎨 Synchronized Chat Wallpaper & Theme", dp));

        String[] themeNames = {"Midnight", "Cyber Indigo", "Emerald Matrix", "Crimson", "Deep Ocean"};
        String[] themeHexes = {"#0D1117", "#121026", "#091E1A", "#240B15", "#081D2E"};

        LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < themeHexes.length; i++) {
            final String hex = themeHexes[i];
            final String tName = themeNames[i];
            View swatch = new View(this);
            int dim = (int) (40 * dp);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dim, dim);
            sLp.rightMargin = (int) (12 * dp);
            swatch.setLayoutParams(sLp);

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(Color.parseColor(hex));
            gd.setStroke((int) (2 * dp), Color.WHITE);
            swatch.setBackground(gd);

            swatch.setOnClickListener(v -> {
                if (chatId != null) {
                    EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, hex);
                    if (peerUserId != null && !peerUserId.isEmpty()) {
                        WebRtcManager.getInstance(this).sendChatSettingPacket(
                                peerUserId, chatId,
                                currentChat != null ? currentChat.getVanishTimer() : 0L,
                                currentChat != null ? currentChat.getVanishMode() : "after_send",
                                tName, hex);
                    }
                    Toast.makeText(this, "🎨 Applied & synced " + tName + " theme!", Toast.LENGTH_SHORT).show();
                }
            });
            swatchRow.addView(swatch);
        }
        themeBox.addView(swatchRow);
        themeCard.addView(themeBox);
        content.addView(themeCard);

        // 4. Encrypted Transcript Export
        MaterialCardView exportCard = createSectionCard(dp);
        LinearLayout exportBox = createCardInnerLayout(dp);
        exportBox.addView(createSectionHeader("📦 Encrypted Vault Export", dp));

        MaterialButton btnExport = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnExport.setText("📤 Export Chat Transcript (JSON)");
        btnExport.setCornerRadius((int) (12 * dp));
        btnExport.setOnClickListener(v -> exportChatTranscript());
        exportBox.addView(btnExport);

        exportCard.addView(exportBox);
        content.addView(exportCard);

        // Save Button
        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText("Save Chat Settings");
        btnSave.setCornerRadius((int) (14 * dp));
        btnSave.setOnClickListener(v -> saveChatSettings());
        content.addView(btnSave);

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

    private CheckBox createCheckRow(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        cb.setChecked(checked);
        return cb;
    }

    private void exportChatTranscript() {
        if (chatId == null) return;
        try {
            List<Message> messages = EncryptedChatDatabase.getInstance(this).getMessagesForChat(chatId);
            JSONArray arr = new JSONArray();
            for (Message m : messages) {
                JSONObject o = new JSONObject();
                o.put("id", m.getId());
                o.put("senderId", m.getSenderId());
                o.put("content", m.getContent());
                o.put("type", m.getType());
                o.put("delivered", m.isDelivered());
                o.put("read", m.isRead());
                arr.put(o);
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, arr.toString(2));
            startActivity(Intent.createChooser(share, "Export Encrypted Chat Transcript"));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveChatSettings() {
        if (chatId == null) return;
        SharedPreferences prefs = getSharedPreferences("VeilChatSpecificPrefs", MODE_PRIVATE);
        prefs.edit()
                .putBoolean("read_receipts_" + chatId, cbSendReadReceipts.isChecked())
                .putBoolean("typing_ind_" + chatId, cbSendTypingIndicators.isChecked())
                .putBoolean("screenshot_shield_" + chatId, cbScreenshotShield.isChecked())
                .putBoolean("auto_media_" + chatId, cbAutoDownloadMedia.isChecked())
                .apply();

        String newPin = etPinLock.getText().toString().trim();
        if (!newPin.isEmpty()) {
            if (newPin.length() < 4) {
                etPinLock.setError("PIN must be at least 4 digits");
                return;
            }
            if (currentChat != null) {
                currentChat.setChatLockPin(newPin);
                EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(currentChat);
            }
        }

        Toast.makeText(this, "✅ Chat settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
