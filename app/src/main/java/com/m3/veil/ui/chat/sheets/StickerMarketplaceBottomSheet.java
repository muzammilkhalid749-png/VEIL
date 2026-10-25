package com.m3.veil.ui.chat.sheets;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.utils.UiAnimator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * StickerMarketplaceBottomSheet: P2P Encrypted Sticker Marketplace, Collectible Avatar Skins
 * and Mystery Achievement Box Hub (#160, #163, #165, #167, #170, #171).
 */
public class StickerMarketplaceBottomSheet extends BottomSheetDialogFragment {

    private VeilFeatureManager featureManager;
    private TextView tvCoinsHeader;
    private LinearLayout contentContainer;
    private int currentTab = 0; // 0: Store, 1: My Packs, 2: Creator, 3: Mystery Box & Skins

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        featureManager = VeilFeatureManager.getInstance(context);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * dp);
        int padV = (int) (14 * dp);
        root.setPadding(padH, padV, padH, padV * 2);

        // Drag Handle
        View handle = new View(context);
        int handleW = (int) (36 * dp);
        int handleH = (int) (4 * dp);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Header Title Row (Title + Balance)
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, (int) (12 * dp));

        TextView tvTitle = new TextView(context);
        tvTitle.setText("🛍️ P2P Sticker & Collectibles");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(tvTitle);

        tvCoinsHeader = new TextView(context);
        tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
        tvCoinsHeader.setTextSize(14f);
        tvCoinsHeader.setTypeface(null, Typeface.BOLD);
        tvCoinsHeader.setTextColor(ContextCompat.getColor(context, R.color.veil_accent));
        tvCoinsHeader.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
        tvCoinsHeader.setBackgroundResource(R.drawable.bg_filter_chip);
        headerRow.addView(tvCoinsHeader);

        root.addView(headerRow);

        // Horizontal Segmented Tabs
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabStrip = new LinearLayout(context);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(0, 0, 0, (int) (14 * dp));

        String[] tabNames = {"🛒 Store", "📦 My Packs", "🎨 Creator", "🎁 Mystery Box & Skins"};
        for (int i = 0; i < tabNames.length; i++) {
            final int tabIndex = i;
            TextView tabBtn = new TextView(context);
            tabBtn.setText(tabNames[i]);
            tabBtn.setTextSize(13f);
            tabBtn.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            tabBtn.setBackgroundResource(currentTab == tabIndex ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            tabBtn.setTextColor(currentTab == tabIndex ? Color.WHITE : ContextCompat.getColor(context, R.color.textPrimary));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, (int) (6 * dp), 0);
            tabBtn.setLayoutParams(lp);

            tabBtn.setOnClickListener(v -> {
                currentTab = tabIndex;
                updateTabDisplay(root);
            });

            tabStrip.addView(tabBtn);
        }
        tabScroll.addView(tabStrip);
        root.addView(tabScroll);

        // Scrollable Content
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentContainer);
        root.addView(scrollView);

        renderActiveTab();
        return root;
    }

    private void updateTabDisplay(View root) {
        tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
        renderActiveTab();
    }

    private void renderActiveTab() {
        if (contentContainer == null || getContext() == null) return;
        contentContainer.removeAllViews();

        switch (currentTab) {
            case 0:
                renderStoreTab();
                break;
            case 1:
                renderMyPacksTab();
                break;
            case 2:
                renderCreatorTab();
                break;
            case 3:
                renderMysteryBoxAndSkinsTab();
                break;
        }
    }

    // Tab 1: Marketplace Store
    private void renderStoreTab() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;
        List<VeilFeatureManager.StickerPack> allPacks = featureManager.getAllStickerPacks();
        List<VeilFeatureManager.StickerPack> installed = featureManager.getInstalledStickerPacks();

        for (VeilFeatureManager.StickerPack pack : allPacks) {
            boolean isInstalled = false;
            for (VeilFeatureManager.StickerPack ins : installed) {
                if (ins.id.equals(pack.id)) {
                    isInstalled = true;
                    break;
                }
            }

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_reply_preview);
            card.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, 0, (int) (10 * dp));
            card.setLayoutParams(clp);

            // Title + Price / Status
            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setText(pack.name);
            title.setTextSize(15f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
            title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(title);

            TextView price = new TextView(ctx);
            price.setText(pack.priceVc == 0 ? "FREE" : pack.priceVc + " VC");
            price.setTextSize(13f);
            price.setTypeface(null, Typeface.BOLD);
            price.setTextColor(ContextCompat.getColor(ctx, R.color.veil_accent));
            top.addView(price);

            card.addView(top);

            // Author subtitle
            TextView author = new TextView(ctx);
            author.setText("Created by " + pack.author);
            author.setTextSize(12f);
            author.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
            author.setPadding(0, 0, 0, (int) (8 * dp));
            card.addView(author);

            // Sticker Preview Strip
            TextView preview = new TextView(ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, pack.stickers.size()); i++) {
                sb.append(pack.stickers.get(i)).append("  ");
            }
            preview.setText(sb.toString());
            preview.setTextSize(20f);
            preview.setPadding(0, 0, 0, (int) (10 * dp));
            card.addView(preview);

            // Action Button
            Button actionBtn = new Button(ctx);
            actionBtn.setTextSize(13f);
            if (isInstalled) {
                actionBtn.setText("✓ Installed");
                actionBtn.setEnabled(false);
                actionBtn.setBackgroundColor(Color.parseColor("#1E293B"));
                actionBtn.setTextColor(Color.parseColor("#94A3B8"));
            } else {
                actionBtn.setText(pack.priceVc == 0 ? "Install Free Pack" : "Buy & Install (" + pack.priceVc + " VC)");
                actionBtn.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_primary));
                actionBtn.setTextColor(Color.WHITE);
                final boolean finalIsInstalled = isInstalled;
                actionBtn.setOnClickListener(v -> {
                    if (pack.priceVc > 0 && featureManager.getVeilCoins() < pack.priceVc) {
                        Toast.makeText(ctx, "Not enough VeilCoins (Need " + pack.priceVc + " VC)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = featureManager.buyStickerPack(pack.id);
                    if (ok) {
                        Toast.makeText(ctx, "🎉 Installed " + pack.name + "!", Toast.LENGTH_SHORT).show();
                        tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
                        renderStoreTab();
                    }
                });
            }
            card.addView(actionBtn);

            contentContainer.addView(card);
        }
    }

    // Tab 2: My Installed Packs
    private void renderMyPacksTab() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;
        List<VeilFeatureManager.StickerPack> installed = featureManager.getInstalledStickerPacks();

        if (installed.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No sticker packs installed yet.");
            empty.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, (int) (32 * dp), 0, (int) (32 * dp));
            contentContainer.addView(empty);
            return;
        }

        for (VeilFeatureManager.StickerPack pack : installed) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_reply_preview);
            card.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, 0, (int) (10 * dp));
            card.setLayoutParams(clp);

            TextView title = new TextView(ctx);
            title.setText(pack.name);
            title.setTextSize(15f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
            card.addView(title);

            TextView preview = new TextView(ctx);
            StringBuilder sb = new StringBuilder();
            for (String s : pack.stickers) {
                sb.append(s).append(" ");
            }
            preview.setText(sb.toString());
            preview.setTextSize(18f);
            preview.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
            card.addView(preview);

            LinearLayout btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnSeed = new Button(ctx);
            btnSeed.setText("📡 P2P Share Pack");
            btnSeed.setTextSize(12f);
            btnSeed.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_primary));
            btnSeed.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMargins(0, 0, (int) (6 * dp), 0);
            btnSeed.setLayoutParams(blp);
            btnSeed.setOnClickListener(v -> {
                Toast.makeText(ctx, "P2P Seeding: Shared " + pack.name + " over mesh swarms!", Toast.LENGTH_SHORT).show();
            });
            btnRow.addView(btnSeed);

            Button btnRemove = new Button(ctx);
            btnRemove.setText("Uninstall");
            btnRemove.setTextSize(12f);
            btnRemove.setBackgroundColor(Color.parseColor("#334155"));
            btnRemove.setTextColor(Color.WHITE);
            btnRemove.setOnClickListener(v -> {
                featureManager.uninstallStickerPack(pack.id);
                Toast.makeText(ctx, "Uninstalled " + pack.name, Toast.LENGTH_SHORT).show();
                renderMyPacksTab();
            });
            btnRow.addView(btnRemove);

            card.addView(btnRow);
            contentContainer.addView(card);
        }
    }

    // Tab 3: Pack Creator (#170)
    private void renderCreatorTab() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        TextView info = new TextView(ctx);
        info.setText("Create custom encrypted sticker packs that seed directly across local P2P WebRTC channels.");
        info.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        info.setTextSize(13f);
        info.setPadding(0, 0, 0, (int) (12 * dp));
        contentContainer.addView(info);

        EditText etName = new EditText(ctx);
        etName.setHint("Pack Name (e.g. Cyber Ninjas)");
        etName.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        etName.setHintTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        etName.setBackgroundResource(R.drawable.bg_chat_input);
        etName.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        contentContainer.addView(etName);

        View div = new View(ctx);
        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (8 * dp)));
        contentContainer.addView(div);

        EditText etEmojis = new EditText(ctx);
        etEmojis.setHint("Stickers / Emojis (e.g. 🥷 🗡️ 🌑 ⚡ 🦾 ⛩️ 🐉)");
        etEmojis.setText("🥷 🗡️ 🌑 ⚡ 🦾 ⛩️ 🐉 💥 🛡️ 🥋");
        etEmojis.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        etEmojis.setHintTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        etEmojis.setBackgroundResource(R.drawable.bg_chat_input);
        etEmojis.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        contentContainer.addView(etEmojis);

        View div2 = new View(ctx);
        div2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (14 * dp)));
        contentContainer.addView(div2);

        Button btnSave = new Button(ctx);
        btnSave.setText("✨ Create & Seed Pack (+30 XP)");
        btnSave.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String rawEmojis = etEmojis.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(ctx, "Please enter a pack name", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> list = new ArrayList<>();
            for (String part : rawEmojis.split("\\s+")) {
                if (!part.trim().isEmpty()) {
                    list.add(part.trim());
                }
            }
            if (list.isEmpty()) {
                Toast.makeText(ctx, "Please enter at least one sticker", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean success = featureManager.createCustomStickerPack(name, list);
            if (success) {
                Toast.makeText(ctx, "🎉 Pack created & installed! +30 XP", Toast.LENGTH_SHORT).show();
                currentTab = 1;
                renderActiveTab();
            }
        });
        contentContainer.addView(btnSave);
    }

    // Tab 4: Mystery Box & Collectible Avatar Skins (#160, #163)
    private void renderMysteryBoxAndSkinsTab() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        // Section A: Mystery Achievement Box (#163)
        LinearLayout boxCard = new LinearLayout(ctx);
        boxCard.setOrientation(LinearLayout.VERTICAL);
        boxCard.setBackgroundResource(R.drawable.bg_reply_preview);
        boxCard.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));

        TextView tvBoxTitle = new TextView(ctx);
        tvBoxTitle.setText("🎁 Mystery Achievement Box");
        tvBoxTitle.setTextSize(16f);
        tvBoxTitle.setTypeface(null, Typeface.BOLD);
        tvBoxTitle.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        boxCard.addView(tvBoxTitle);

        TextView tvBoxDesc = new TextView(ctx);
        tvBoxDesc.setText("Open a box for 40 VC to win random XP, Karma, Rare Avatar Skins, or the 150 VC Jackpot!");
        tvBoxDesc.setTextSize(13f);
        tvBoxDesc.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        tvBoxDesc.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        boxCard.addView(tvBoxDesc);

        Button btnOpenBox = new Button(ctx);
        btnOpenBox.setText("🎲 Open Box (40 VC)");
        btnOpenBox.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        btnOpenBox.setTextColor(Color.WHITE);
        btnOpenBox.setOnClickListener(v -> {
            if (featureManager.getVeilCoins() < 40) {
                Toast.makeText(ctx, "Insufficient VeilCoins (Need 40 VC)", Toast.LENGTH_SHORT).show();
                return;
            }
            VeilFeatureManager.MysteryBoxReward reward = featureManager.openMysteryBox();
            if (reward != null) {
                tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
                new AlertDialog.Builder(ctx)
                        .setTitle(reward.tier.equals("LEGENDARY") ? "🔥 LEGENDARY WIN!" : "🎁 Mystery Box Opened!")
                        .setMessage(reward.message)
                        .setPositiveButton("Awesome!", (d, w) -> renderMysteryBoxAndSkinsTab())
                        .show();
            }
        });
        boxCard.addView(btnOpenBox);
        contentContainer.addView(boxCard);

        View div = new View(ctx);
        div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (16 * dp)));
        contentContainer.addView(div);

        // Section B: Collectible Avatar Skins (#160)
        TextView tvSkinsTitle = new TextView(ctx);
        tvSkinsTitle.setText("🎭 Collectible Avatar Skins (#160)");
        tvSkinsTitle.setTextSize(16f);
        tvSkinsTitle.setTypeface(null, Typeface.BOLD);
        tvSkinsTitle.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        tvSkinsTitle.setPadding(0, 0, 0, (int) (8 * dp));
        contentContainer.addView(tvSkinsTitle);

        List<VeilFeatureManager.AvatarSkin> allSkins = featureManager.getAllAvatarSkins();
        List<String> unlocked = featureManager.getUnlockedAvatarSkins();
        String active = featureManager.getActiveAvatarSkin();

        for (VeilFeatureManager.AvatarSkin skin : allSkins) {
            boolean isUnlocked = unlocked.contains(skin.id);
            boolean isActive = active.equals(skin.id);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_reply_preview);
            row.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, (int) (8 * dp));
            row.setLayoutParams(rlp);

            TextView icon = new TextView(ctx);
            icon.setText(skin.icon);
            icon.setTextSize(22f);
            icon.setPadding(0, 0, (int) (12 * dp), 0);
            row.addView(icon);

            LinearLayout infoCol = new LinearLayout(ctx);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView sName = new TextView(ctx);
            sName.setText(skin.name);
            sName.setTextSize(14.5f);
            sName.setTypeface(null, Typeface.BOLD);
            sName.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
            infoCol.addView(sName);

            TextView sPrice = new TextView(ctx);
            sPrice.setText(skin.priceVc == 0 ? "Default" : (isUnlocked ? "Unlocked" : skin.priceVc + " VC"));
            sPrice.setTextSize(12f);
            sPrice.setTextColor(ContextCompat.getColor(ctx, R.color.veil_accent));
            infoCol.addView(sPrice);
            row.addView(infoCol);

            Button btnSkin = new Button(ctx);
            btnSkin.setTextSize(12f);
            if (isActive) {
                btnSkin.setText("Active ✓");
                btnSkin.setEnabled(false);
                btnSkin.setBackgroundColor(Color.parseColor("#1E293B"));
                btnSkin.setTextColor(Color.parseColor("#94A3B8"));
            } else if (isUnlocked) {
                btnSkin.setText("Equip");
                btnSkin.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_primary));
                btnSkin.setTextColor(Color.WHITE);
                btnSkin.setOnClickListener(v -> {
                    featureManager.setActiveAvatarSkin(skin.id);
                    Toast.makeText(ctx, "Equipped " + skin.name + "!", Toast.LENGTH_SHORT).show();
                    renderMysteryBoxAndSkinsTab();
                });
            } else {
                btnSkin.setText("Buy (" + skin.priceVc + " VC)");
                btnSkin.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_accent));
                btnSkin.setTextColor(Color.WHITE);
                btnSkin.setOnClickListener(v -> {
                    if (featureManager.getVeilCoins() < skin.priceVc) {
                        Toast.makeText(ctx, "Insufficient VeilCoins", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (featureManager.buyAvatarSkin(skin.id)) {
                        tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
                        Toast.makeText(ctx, "🎉 Unlocked & Equipped " + skin.name + "!", Toast.LENGTH_SHORT).show();
                        renderMysteryBoxAndSkinsTab();
                    }
                });
            }
            row.addView(btnSkin);

            contentContainer.addView(row);
        }
    }
}
