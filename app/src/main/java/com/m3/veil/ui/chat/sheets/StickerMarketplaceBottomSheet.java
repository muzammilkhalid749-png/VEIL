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

    public interface OnMarketplaceActionListener {
        void onStickerSelectedFromStore(String stickerCode);
        void onPacksChanged();
    }

    private VeilFeatureManager featureManager;
    private TextView tvCoinsHeader;
    private LinearLayout contentContainer;
    private LinearLayout tabStrip;
    private int currentTab = 0; // 0: Store, 1: My Packs, 2: Creator, 3: Mystery Box & Skins
    private String storeSearchQuery = "";
    private String storeCategory = "All";
    private OnMarketplaceActionListener marketplaceListener;

    public void setOnMarketplaceActionListener(OnMarketplaceActionListener listener) {
        this.marketplaceListener = listener;
    }

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
        tvTitle.setText("🛍️ Veil Sticker Store & Studio");
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
        tabStrip = new LinearLayout(context);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(0, 0, 0, (int) (14 * dp));

        String[] tabNames = {"🛒 Sticker Store", "📦 My Packs", "🎨 Pack Creator", "🎁 Mystery Box & Skins"};
        for (int i = 0; i < tabNames.length; i++) {
            final int tabIndex = i;
            TextView tabBtn = new TextView(context);
            tabBtn.setText(tabNames[i]);
            tabBtn.setTextSize(13f);
            tabBtn.setTypeface(null, Typeface.BOLD);
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
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (440 * dp)));
        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentContainer);
        root.addView(scrollView);

        renderActiveTab();
        return root;
    }

    private void updateTabDisplay(View root) {
        if (tvCoinsHeader != null) {
            tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
        }
        if (tabStrip != null && getContext() != null) {
            for (int i = 0; i < tabStrip.getChildCount(); i++) {
                View child = tabStrip.getChildAt(i);
                if (child instanceof TextView) {
                    boolean active = (i == currentTab);
                    child.setBackgroundResource(active ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                    ((TextView) child).setTextColor(active ? Color.WHITE : ContextCompat.getColor(requireContext(), R.color.textPrimary));
                }
            }
        }
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

    // Tab 1: Marketplace Store with Live Search, Category Filter & Interactive Animated Previews
    private void renderStoreTab() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        // 1. Search Input
        EditText etSearch = new EditText(ctx);
        etSearch.setHint("🔍 Search sticker packs or emotions (e.g. Sloth, Cyber, Hype)...");
        etSearch.setText(storeSearchQuery);
        etSearch.setSingleLine(true);
        etSearch.setTextSize(13.5f);
        etSearch.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        etSearch.setHintTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        etSearch.setBackgroundResource(R.drawable.bg_search_input);
        etSearch.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = (int) (10 * dp);
        etSearch.setLayoutParams(searchLp);
        contentContainer.addView(etSearch);

        // 2. Category Chips
        HorizontalScrollView catScroll = new HorizontalScrollView(ctx);
        catScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = new LinearLayout(ctx);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        catRow.setPadding(0, 0, 0, (int) (12 * dp));
        String[] categories = {"All", "Free", "Sloth & Chill", "Cyberpunk", "Reactions", "Memes", "Pets"};
        for (String cat : categories) {
            boolean selected = cat.equalsIgnoreCase(storeCategory);
            TextView chip = new TextView(ctx);
            chip.setText(cat);
            chip.setTextSize(12f);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            chip.setBackgroundResource(selected ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(selected ? Color.WHITE : ContextCompat.getColor(ctx, R.color.textPrimary));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = (int) (6 * dp);
            chip.setLayoutParams(chipLp);
            chip.setOnClickListener(v -> {
                storeCategory = cat;
                renderActiveTab();
            });
            catRow.addView(chip);
        }
        catScroll.addView(catRow);
        contentContainer.addView(catScroll);

        LinearLayout packListContainer = new LinearLayout(ctx);
        packListContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.addView(packListContainer);

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                storeSearchQuery = s != null ? s.toString().trim() : "";
                populateFilteredStorePacks(ctx, packListContainer);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        populateFilteredStorePacks(ctx, packListContainer);
    }

    private void populateFilteredStorePacks(Context ctx, LinearLayout packListContainer) {
        packListContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        List<VeilFeatureManager.StickerPack> allPacks = featureManager.getAllStickerPacks();
        List<VeilFeatureManager.StickerPack> installed = featureManager.getInstalledStickerPacks();
        String q = storeSearchQuery != null ? storeSearchQuery.toLowerCase(java.util.Locale.ROOT) : "";

        for (VeilFeatureManager.StickerPack pack : allPacks) {
            if (!"All".equalsIgnoreCase(storeCategory)) {
                String pNameLower = pack.name.toLowerCase(java.util.Locale.ROOT);
                if ("Free".equalsIgnoreCase(storeCategory) && pack.priceVc > 0) continue;
                if ("Sloth & Chill".equalsIgnoreCase(storeCategory) && !pNameLower.contains("sloth") && !pNameLower.contains("chill")) continue;
                if ("Cyberpunk".equalsIgnoreCase(storeCategory) && !pNameLower.contains("cypher") && !pNameLower.contains("whale") && !pNameLower.contains("phantom")) continue;
                if ("Reactions".equalsIgnoreCase(storeCategory) && !pNameLower.contains("hype") && !pNameLower.contains("reaction") && !pNameLower.contains("sloth")) continue;
                if ("Memes".equalsIgnoreCase(storeCategory) && !pNameLower.contains("meme")) continue;
                if ("Pets".equalsIgnoreCase(storeCategory) && !pNameLower.contains("pet") && !pNameLower.contains("sloth") && !pNameLower.contains("whale")) continue;
            }

            if (!q.isEmpty()) {
                boolean match = pack.name.toLowerCase(java.util.Locale.ROOT).contains(q)
                        || pack.author.toLowerCase(java.util.Locale.ROOT).contains(q);
                if (!match && pack.stickers != null) {
                    for (String st : pack.stickers) {
                        if (st.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                            match = true;
                            break;
                        }
                    }
                }
                if (!match) continue;
            }

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
            clp.setMargins(0, 0, 0, (int) (12 * dp));
            card.setLayoutParams(clp);

            // Title + Price / Status
            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setText(pack.name + " (" + (pack.stickers != null ? pack.stickers.size() : 0) + ")");
            title.setTextSize(15.5f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
            title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(title);

            TextView price = new TextView(ctx);
            price.setText(isInstalled ? "INSTALLED ✓" : (pack.priceVc == 0 ? "FREE" : pack.priceVc + " VC"));
            price.setTextSize(12f);
            price.setTypeface(null, Typeface.BOLD);
            price.setTextColor(isInstalled ? Color.parseColor("#10B981") : ContextCompat.getColor(ctx, R.color.veil_accent));
            top.addView(price);

            card.addView(top);

            // Author subtitle
            TextView author = new TextView(ctx);
            author.setText("By " + pack.author + " • Tap any sticker to send or tap card to preview all");
            author.setTextSize(11.5f);
            author.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
            author.setPadding(0, (int) (2 * dp), 0, (int) (8 * dp));
            card.addView(author);

            // Animated Sticker Preview Strip (HorizontalScrollView of real AnimatedStickerDrawables)
            HorizontalScrollView stripScroll = new HorizontalScrollView(ctx);
            stripScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout stripRow = new LinearLayout(ctx);
            stripRow.setOrientation(LinearLayout.HORIZONTAL);
            stripRow.setPadding(0, (int) (4 * dp), 0, (int) (10 * dp));
            if (pack.stickers != null) {
                for (int i = 0; i < Math.min(8, pack.stickers.size()); i++) {
                    final String sCode = pack.stickers.get(i);
                    com.m3.veil.utils.VeilStickerGifEngine.StickerPreset preset =
                            com.m3.veil.utils.VeilStickerGifEngine.resolveStickerPreset(sCode);
                    android.widget.ImageView iv = new android.widget.ImageView(ctx);
                    int size = (int) (64 * dp);
                    LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(size, size);
                    ivLp.rightMargin = (int) (8 * dp);
                    iv.setLayoutParams(ivLp);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    iv.setImageDrawable(new com.m3.veil.utils.VeilStickerGifEngine.AnimatedStickerDrawable(ctx, preset));
                    iv.setOnClickListener(v -> {
                        featureManager.recordRecentSticker(preset.code);
                        if (marketplaceListener != null) {
                            marketplaceListener.onStickerSelectedFromStore(preset.code);
                            dismiss();
                        } else {
                            showPackDetailSheet(pack);
                        }
                    });
                    stripRow.addView(iv);
                }
            }
            stripScroll.addView(stripRow);
            card.addView(stripScroll);

            // Action Buttons Row: [ Preview Pack ] [ Install / Uninstall ]
            LinearLayout actionRow = new LinearLayout(ctx);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnPreview = new Button(ctx);
            btnPreview.setText("👁️ Preview All (" + (pack.stickers != null ? pack.stickers.size() : 0) + ")");
            btnPreview.setTextSize(12f);
            btnPreview.setBackgroundColor(Color.parseColor("#1E293B"));
            btnPreview.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            pLp.rightMargin = (int) (8 * dp);
            btnPreview.setLayoutParams(pLp);
            btnPreview.setOnClickListener(v -> showPackDetailSheet(pack));
            actionRow.addView(btnPreview);

            Button actionBtn = new Button(ctx);
            actionBtn.setTextSize(12f);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            actionBtn.setLayoutParams(aLp);
            if (isInstalled) {
                actionBtn.setText("Remove Pack");
                actionBtn.setBackgroundColor(Color.parseColor("#334155"));
                actionBtn.setTextColor(Color.parseColor("#F8FAFC"));
                actionBtn.setOnClickListener(v -> {
                    featureManager.uninstallStickerPack(pack.id);
                    if (marketplaceListener != null) marketplaceListener.onPacksChanged();
                    Toast.makeText(ctx, "Removed " + pack.name, Toast.LENGTH_SHORT).show();
                    populateFilteredStorePacks(ctx, packListContainer);
                });
            } else {
                actionBtn.setText(pack.priceVc == 0 ? "⬇ Add Free Pack" : "Buy & Add (" + pack.priceVc + " VC)");
                actionBtn.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_primary));
                actionBtn.setTextColor(Color.WHITE);
                actionBtn.setOnClickListener(v -> {
                    if (pack.priceVc > 0 && featureManager.getVeilCoins() < pack.priceVc) {
                        Toast.makeText(ctx, "Not enough VeilCoins (Need " + pack.priceVc + " VC)", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = featureManager.buyStickerPack(pack.id);
                    if (ok) {
                        if (marketplaceListener != null) marketplaceListener.onPacksChanged();
                        Toast.makeText(ctx, "🎉 Added " + pack.name + " to your sticker tray!", Toast.LENGTH_SHORT).show();
                        tvCoinsHeader.setText("🪙 " + featureManager.getVeilCoins() + " VC");
                        populateFilteredStorePacks(ctx, packListContainer);
                    }
                });
            }
            actionRow.addView(actionBtn);
            card.addView(actionRow);

            card.setOnClickListener(v -> showPackDetailSheet(pack));
            packListContainer.addView(card);
        }
    }

    private void showPackDetailSheet(VeilFeatureManager.StickerPack pack) {
        if (getContext() == null || pack == null) return;
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.ModernBottomSheetDialogTheme);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, (int) (16 * dp), pad, pad);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(pack.name);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        root.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText("Created by " + pack.author + " • " + (pack.stickers != null ? pack.stickers.size() : 0)
                + " Borderless Stickers • Tap to Send, Long-press to ⭐ Favorite");
        tvSub.setTextSize(12.5f);
        tvSub.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        tvSub.setPadding(0, (int) (4 * dp), 0, (int) (14 * dp));
        root.addView(tvSub);

        android.widget.GridView grid = new android.widget.GridView(ctx);
        grid.setNumColumns(4);
        grid.setVerticalSpacing((int) (12 * dp));
        grid.setHorizontalSpacing((int) (12 * dp));
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (270 * dp)));

        final List<com.m3.veil.utils.VeilStickerGifEngine.StickerPreset> presets = new ArrayList<>();
        if (pack.stickers != null) {
            for (String s : pack.stickers) {
                presets.add(com.m3.veil.utils.VeilStickerGifEngine.resolveStickerPreset(s));
            }
        }

        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return presets.size();
            }

            @Override
            public Object getItem(int position) {
                return presets.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                android.widget.ImageView iv = (convertView instanceof android.widget.ImageView)
                        ? (android.widget.ImageView) convertView : new android.widget.ImageView(ctx);
                int size = (int) (74 * dp);
                iv.setLayoutParams(new android.widget.GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, size));
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setImageDrawable(new com.m3.veil.utils.VeilStickerGifEngine.AnimatedStickerDrawable(ctx, presets.get(position)));
                return iv;
            }
        });

        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < presets.size()) {
                com.m3.veil.utils.VeilStickerGifEngine.StickerPreset chosen = presets.get(position);
                featureManager.recordRecentSticker(chosen.code);
                sheet.dismiss();
                if (marketplaceListener != null) {
                    marketplaceListener.onStickerSelectedFromStore(chosen.code);
                    dismiss();
                } else {
                    Toast.makeText(ctx, "Sticker ready: " + chosen.label, Toast.LENGTH_SHORT).show();
                }
            }
        });

        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < presets.size()) {
                com.m3.veil.utils.VeilStickerGifEngine.StickerPreset chosen = presets.get(position);
                boolean fav = featureManager.toggleFavoriteSticker(chosen.code);
                Toast.makeText(ctx, fav ? "⭐ Added to Favorite Stickers" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        root.addView(grid);

        boolean installedNow = false;
        for (VeilFeatureManager.StickerPack ins : featureManager.getInstalledStickerPacks()) {
            if (ins.id.equals(pack.id)) {
                installedNow = true;
                break;
            }
        }
        final boolean finalInstalled = installedNow;
        Button btnAction = new Button(ctx);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (14 * dp);
        btnAction.setLayoutParams(btnLp);
        btnAction.setTextColor(Color.WHITE);
        btnAction.setBackgroundColor(finalInstalled ? Color.parseColor("#334155") : ContextCompat.getColor(ctx, R.color.veil_primary));
        btnAction.setText(finalInstalled ? "Remove Pack from Tray" : (pack.priceVc == 0 ? "⬇ Add Pack to Sticker Tray (Free)" : "Buy & Add Pack (" + pack.priceVc + " VC)"));
        btnAction.setOnClickListener(v -> {
            if (finalInstalled) {
                featureManager.uninstallStickerPack(pack.id);
                if (marketplaceListener != null) marketplaceListener.onPacksChanged();
                Toast.makeText(ctx, "Removed " + pack.name, Toast.LENGTH_SHORT).show();
                sheet.dismiss();
                renderActiveTab();
            } else {
                if (featureManager.buyStickerPack(pack.id)) {
                    if (marketplaceListener != null) marketplaceListener.onPacksChanged();
                    Toast.makeText(ctx, "🎉 Added " + pack.name + "!", Toast.LENGTH_SHORT).show();
                    sheet.dismiss();
                    renderActiveTab();
                } else {
                    Toast.makeText(ctx, "Insufficient VeilCoins", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(btnAction);

        sheet.setContentView(root);
        sheet.show();
    }

    // Tab 2: My Installed Packs
    private void renderMyPacksTab() {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;
        List<VeilFeatureManager.StickerPack> installed = featureManager.getInstalledStickerPacks();

        if (installed.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No sticker packs installed yet. Visit the Store tab to add packs!");
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
            title.setText(pack.name + " • " + (pack.stickers != null ? pack.stickers.size() : 0) + " stickers");
            title.setTextSize(15f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
            card.addView(title);

            HorizontalScrollView stripScroll = new HorizontalScrollView(ctx);
            stripScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout stripRow = new LinearLayout(ctx);
            stripRow.setOrientation(LinearLayout.HORIZONTAL);
            stripRow.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
            if (pack.stickers != null) {
                for (int i = 0; i < Math.min(8, pack.stickers.size()); i++) {
                    final String sCode = pack.stickers.get(i);
                    com.m3.veil.utils.VeilStickerGifEngine.StickerPreset preset =
                            com.m3.veil.utils.VeilStickerGifEngine.resolveStickerPreset(sCode);
                    android.widget.ImageView iv = new android.widget.ImageView(ctx);
                    int size = (int) (58 * dp);
                    LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(size, size);
                    ivLp.rightMargin = (int) (8 * dp);
                    iv.setLayoutParams(ivLp);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    iv.setImageDrawable(new com.m3.veil.utils.VeilStickerGifEngine.AnimatedStickerDrawable(ctx, preset));
                    iv.setOnClickListener(v -> {
                        featureManager.recordRecentSticker(preset.code);
                        if (marketplaceListener != null) {
                            marketplaceListener.onStickerSelectedFromStore(preset.code);
                            dismiss();
                        } else {
                            showPackDetailSheet(pack);
                        }
                    });
                    stripRow.addView(iv);
                }
            }
            stripScroll.addView(stripRow);
            card.addView(stripScroll);

            LinearLayout btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            Button btnSeed = new Button(ctx);
            btnSeed.setText("👁️ Open Pack");
            btnSeed.setTextSize(12f);
            btnSeed.setBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_primary));
            btnSeed.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMargins(0, 0, (int) (6 * dp), 0);
            btnSeed.setLayoutParams(blp);
            btnSeed.setOnClickListener(v -> showPackDetailSheet(pack));
            btnRow.addView(btnSeed);

            Button btnRemove = new Button(ctx);
            btnRemove.setText("Uninstall");
            btnRemove.setTextSize(12f);
            btnRemove.setBackgroundColor(Color.parseColor("#334155"));
            btnRemove.setTextColor(Color.WHITE);
            btnRemove.setOnClickListener(v -> {
                featureManager.uninstallStickerPack(pack.id);
                if (marketplaceListener != null) marketplaceListener.onPacksChanged();
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

        // Section B: Collectible Avatar Skins
        TextView tvSkinsTitle = new TextView(ctx);
        tvSkinsTitle.setText("🎭 Collectible Avatar Skins");
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
