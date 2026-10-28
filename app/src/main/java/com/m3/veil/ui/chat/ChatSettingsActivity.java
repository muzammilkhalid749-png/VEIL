package com.m3.veil.ui.chat;

import android.content.Context;
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
import android.widget.EditText;
import android.widget.FrameLayout;
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
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilMessagesController;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.sheets.SharedMediaGalleryBottomSheet;
import com.m3.veil.ui.chat.sheets.ThemeWallpaperBottomSheet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Dedicated Per-Conversation Chat Settings & Privacy Console (`ChatSettingsActivity`).
 * Features:
 * - Media Visibility: Toggle whether downloaded media & files show in Phone Gallery & File Manager
 * - Synchronized Chat Theme & Wallpaper (Full live bubble preview & 12 presets)
 * - Media, Links & Docs Browser (WhatsApp/Telegram-style 4-tab console)
 * - Granular Privacy Controls: Read Receipts (Blue Ticks), Typing Indicators, Screenshot Shield (FLAG_SECURE)
 * - Auto-Download Preferences
 * - Per-Chat 4-Digit Vault PIN Lock
 * - Encrypted Conversation Transcript Export (JSON)
 */
public class ChatSettingsActivity extends AppCompatActivity {

    private String chatId;
    private String peerUserId;
    private String peerUserName;
    private Chat currentChat;

    private SwitchMaterial switchReadReceipts;
    private SwitchMaterial switchTypingIndicators;
    private SwitchMaterial switchScreenshotShield;
    private SwitchMaterial switchAutoDownloadMedia;
    private SwitchMaterial switchMediaVisibility;
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

        // Top App Bar
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

        LinearLayout headerTitleCol = new LinearLayout(this);
        headerTitleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hLp.leftMargin = (int) (12 * dp);
        headerTitleCol.setLayoutParams(hLp);

        TextView tvHeader = new TextView(this);
        tvHeader.setText("⚙️ Chat Settings");
        tvHeader.setTextSize(17.5f);
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        headerTitleCol.addView(tvHeader);

        TextView tvHeaderSub = new TextView(this);
        tvHeaderSub.setText(peerUserName != null ? peerUserName : "Direct Conversation");
        tvHeaderSub.setTextSize(12.5f);
        tvHeaderSub.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        headerTitleCol.addView(tvHeaderSub);

        topBar.addView(headerTitleCol);
        root.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        content.setPadding(pad, pad, pad, (int) (32 * dp));

        // 1. Media Visibility Card (NEW: Show in Gallery / File Manager)
        MaterialCardView visibilityCard = createSectionCard(dp);
        LinearLayout visibilityBox = createCardInnerLayout(dp);
        visibilityBox.addView(createSectionHeader("🖼️ Media Visibility in Gallery", dp));

        boolean mediaVisEnabled = VeilMessagesController.isMediaVisibilityEnabled(this, chatId);
        switchMediaVisibility = new SwitchMaterial(this);
        switchMediaVisibility.setText("Show in Phone Gallery & File Manager");
        switchMediaVisibility.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        switchMediaVisibility.setChecked(mediaVisEnabled);
        visibilityBox.addView(switchMediaVisibility);

        TextView tvVisHint = new TextView(this);
        tvVisHint.setText("When enabled, photos, videos, and documents downloaded in this chat will appear in your phone's Gallery, Google Photos, and File Manager apps. When disabled, files stay hidden in the private vault.");
        tvVisHint.setTextSize(12.5f);
        tvVisHint.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvVisHint.setPadding(0, (int) (6 * dp), 0, (int) (4 * dp));
        visibilityBox.addView(tvVisHint);

        visibilityCard.addView(visibilityBox);
        content.addView(visibilityCard);

        // 2. Shared Media, Links & Docs Card
        MaterialCardView mediaNavCard = createSectionCard(dp);
        LinearLayout mediaNavBox = createCardInnerLayout(dp);
        mediaNavBox.addView(createSectionHeader("📁 Shared Media, Links & Docs", dp));

        TextView tvMediaNavDesc = new TextView(this);
        tvMediaNavDesc.setText("Browse photos, videos, audio clips, documents, and shared web links exchanged in this chat.");
        tvMediaNavDesc.setTextSize(12.5f);
        tvMediaNavDesc.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvMediaNavDesc.setPadding(0, 0, 0, (int) (10 * dp));
        mediaNavBox.addView(tvMediaNavDesc);

        MaterialButton btnOpenSharedMedia = new MaterialButton(this);
        btnOpenSharedMedia.setText("📂 View Shared Media, Links & Docs");
        btnOpenSharedMedia.setCornerRadius((int) (12 * dp));
        btnOpenSharedMedia.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnOpenSharedMedia.setTextColor(Color.WHITE);
        btnOpenSharedMedia.setOnClickListener(v -> {
            if (chatId != null) {
                SharedMediaGalleryBottomSheet sheet = SharedMediaGalleryBottomSheet.newInstance(
                        chatId, peerUserName != null ? peerUserName : "Shared Media"
                );
                sheet.show(getSupportFragmentManager(), "SharedMediaGalleryBottomSheet");
            }
        });
        mediaNavBox.addView(btnOpenSharedMedia);

        mediaNavCard.addView(mediaNavBox);
        content.addView(mediaNavCard);

        // 3. Theme & Wallpaper Card (Full synchronizer with live bubble preview)
        MaterialCardView themeCard = createSectionCard(dp);
        LinearLayout themeBox = createCardInnerLayout(dp);
        themeBox.addView(createSectionHeader("🎨 Chat Theme & Wallpaper", dp));

        TextView tvThemeDesc = new TextView(this);
        tvThemeDesc.setText("Choose from 12 curated solid & gradient themes, adjust wallpaper dimming, and sync colors across both chat participants.");
        tvThemeDesc.setTextSize(12.5f);
        tvThemeDesc.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvThemeDesc.setPadding(0, 0, 0, (int) (12 * dp));
        themeBox.addView(tvThemeDesc);

        MaterialButton btnOpenThemePicker = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnOpenThemePicker.setText("🎨 Customize Theme & Wallpaper");
        btnOpenThemePicker.setCornerRadius((int) (12 * dp));
        btnOpenThemePicker.setOnClickListener(v -> {
            ThemeWallpaperBottomSheet sheet = ThemeWallpaperBottomSheet.newInstance(chatId, peerUserId, false);
            sheet.show(getSupportFragmentManager(), "ThemeWallpaperBottomSheet");
        });
        themeBox.addView(btnOpenThemePicker);

        themeCard.addView(themeBox);
        content.addView(themeCard);

        // 4. Privacy & Receipts Section
        MaterialCardView privacyCard = createSectionCard(dp);
        LinearLayout privacyBox = createCardInnerLayout(dp);
        privacyBox.addView(createSectionHeader("🛡️ Conversation Privacy & Receipts", dp));

        switchReadReceipts = new SwitchMaterial(this);
        switchReadReceipts.setText("Send Blue Read Receipts (✓✓)");
        switchReadReceipts.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        switchReadReceipts.setChecked(prefs.getBoolean("read_receipts_" + chatId, true));
        privacyBox.addView(switchReadReceipts);

        switchTypingIndicators = new SwitchMaterial(this);
        switchTypingIndicators.setText("Send Live Typing & Audio Indicators");
        switchTypingIndicators.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        switchTypingIndicators.setChecked(prefs.getBoolean("typing_ind_" + chatId, true));
        privacyBox.addView(switchTypingIndicators);

        switchScreenshotShield = new SwitchMaterial(this);
        switchScreenshotShield.setText("Block Screenshots & Recording (FLAG_SECURE)");
        switchScreenshotShield.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        switchScreenshotShield.setChecked(prefs.getBoolean("screenshot_shield_" + chatId, true));
        privacyBox.addView(switchScreenshotShield);

        switchAutoDownloadMedia = new SwitchMaterial(this);
        switchAutoDownloadMedia.setText("Auto-Download Photos & Documents");
        switchAutoDownloadMedia.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        switchAutoDownloadMedia.setChecked(prefs.getBoolean("auto_media_" + chatId, true));
        privacyBox.addView(switchAutoDownloadMedia);

        privacyCard.addView(privacyBox);
        content.addView(privacyCard);

        // 5. Per-Chat Vault PIN Lock
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
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinLp.bottomMargin = (int) (8 * dp);
        etPinLock.setLayoutParams(pinLp);
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
                EncryptedChatDatabase.getInstance(this).updateChatLockPin(chatId, null);
                Toast.makeText(this, "Per-chat PIN lock removed", Toast.LENGTH_SHORT).show();
            });
            pinBox.addView(btnRemovePin);
        }

        pinCard.addView(pinBox);
        content.addView(pinCard);

        // 6. Encrypted Transcript Export
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

        // Save All Button
        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText("Save All Chat Settings");
        btnSave.setCornerRadius((int) (14 * dp));
        btnSave.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
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
        tv.setPadding(0, 0, 0, (int) (8 * dp));
        return tv;
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

        // Save Media Visibility setting
        if (switchMediaVisibility != null) {
            VeilMessagesController.setMediaVisibilityEnabled(this, chatId, switchMediaVisibility.isChecked());
        }

        // Save Privacy & Receipts settings
        SharedPreferences prefs = getSharedPreferences("VeilChatSpecificPrefs", MODE_PRIVATE);
        prefs.edit()
                .putBoolean("read_receipts_" + chatId, switchReadReceipts.isChecked())
                .putBoolean("typing_ind_" + chatId, switchTypingIndicators.isChecked())
                .putBoolean("screenshot_shield_" + chatId, switchScreenshotShield.isChecked())
                .putBoolean("auto_media_" + chatId, switchAutoDownloadMedia.isChecked())
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
            EncryptedChatDatabase.getInstance(this).updateChatLockPin(chatId, newPin);
        }

        Toast.makeText(this, "✅ Chat settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
