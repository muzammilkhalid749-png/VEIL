package com.m3.veil.ui.chat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.ui.chat.sheets.StickerMarketplaceBottomSheet;
import com.m3.veil.utils.VeilStickerGifEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram & WhatsApp Style 3-in-1 Rich Media Tray:
 * - Mode 0: 😀 Emojis (Recent + Unicode Emojis)
 * - Mode 1: ✨ Stickers (Animated Telegram-Style Stickers + Installed Packs + Custom Creator + Store)
 * - Mode 2: 🎬 GIFs (Looping Animated GIFs with Search + Category Chips + Import Device .GIF)
 */
public class EmojiStickerBottomSheet extends BottomSheetDialogFragment {

    public interface OnEmojiSelectedListener {
        void onEmojiSelected(String emoji);
    }

    public interface OnStickerOrGifSelectedListener {
        void onStickerSelected(String stickerCode);
        void onGifSelected(String gifCode);
        void onPickDeviceGifRequested();
    }

    private static final int MODE_EMOJIS = 0;
    private static final int MODE_STICKERS = 1;
    private static final int MODE_GIFS = 2;

    private OnEmojiSelectedListener listener;
    private OnStickerOrGifSelectedListener stickerGifListener;

    private int currentMode = MODE_EMOJIS;
    private int selectedStickerSubTab = 0; // 0 = Animated Stickers, 1+ = Installed Sticker Packs
    private String selectedGifCategory = "All";

    private LinearLayout modeSwitchBar;
    private HorizontalScrollView subTabScroll;
    private LinearLayout subTabStrip;
    private LinearLayout gifSearchContainer;
    private EditText etGifSearch;
    private GridView gridView;

    private final List<String> emojiItems = new ArrayList<>();
    private final List<VeilStickerGifEngine.StickerPreset> stickerItems = new ArrayList<>();
    private final List<VeilStickerGifEngine.GifPreset> gifItems = new ArrayList<>();

    private TrayGridAdapter gridAdapter;

    public void setOnEmojiSelectedListener(OnEmojiSelectedListener listener) {
        this.listener = listener;
    }

    public void setOnStickerOrGifSelectedListener(OnStickerOrGifSelectedListener stickerGifListener) {
        this.stickerGifListener = stickerGifListener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (16 * dp));

        // 1. Drag Handle
        View handle = new View(context);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (36 * dp), (int) (4 * dp));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (10 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // 2. Top Segmented Mode Bar: [ 😀 Emojis | ✨ Stickers | 🎬 GIFs ]
        modeSwitchBar = new LinearLayout(context);
        modeSwitchBar.setOrientation(LinearLayout.HORIZONTAL);
        modeSwitchBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        modeLp.bottomMargin = (int) (10 * dp);
        modeSwitchBar.setLayoutParams(modeLp);

        String[] modeTitles = {"😀 Emojis", "✨ Stickers", "🎬 GIFs"};
        for (int i = 0; i < modeTitles.length; i++) {
            final int modeIdx = i;
            TextView tab = new TextView(context);
            tab.setText(modeTitles[i]);
            tab.setTextSize(13.5f);
            tab.setTypeface(null, Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < modeTitles.length - 1) tlp.rightMargin = (int) (6 * dp);
            tab.setLayoutParams(tlp);
            tab.setOnClickListener(v -> switchMode(modeIdx));
            modeSwitchBar.addView(tab);
        }
        root.addView(modeSwitchBar);

        // 3. GIF Search & Device GIF Import Row (visible in GIF mode)
        gifSearchContainer = new LinearLayout(context);
        gifSearchContainer.setOrientation(LinearLayout.HORIZONTAL);
        gifSearchContainer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams searchRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchRowLp.bottomMargin = (int) (8 * dp);
        gifSearchContainer.setLayoutParams(searchRowLp);
        gifSearchContainer.setVisibility(View.GONE);

        etGifSearch = new EditText(context);
        etGifSearch.setHint("🔍 Search trending GIFs...");
        etGifSearch.setSingleLine(true);
        etGifSearch.setTextSize(13.5f);
        etGifSearch.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        etGifSearch.setHintTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        etGifSearch.setBackgroundResource(R.drawable.bg_search_input);
        etGifSearch.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etLp.rightMargin = (int) (8 * dp);
        etGifSearch.setLayoutParams(etLp);
        etGifSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                loadGifItems(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        gifSearchContainer.addView(etGifSearch);

        TextView btnDeviceGif = createTabChip(context, "📁 Device GIF", true, v -> {
            if (stickerGifListener != null) {
                stickerGifListener.onPickDeviceGifRequested();
            }
            dismiss();
        });
        gifSearchContainer.addView(btnDeviceGif);
        root.addView(gifSearchContainer);

        // 4. Horizontal Pack / Category Strip
        subTabScroll = new HorizontalScrollView(context);
        subTabScroll.setHorizontalScrollBarEnabled(false);
        subTabStrip = new LinearLayout(context);
        subTabStrip.setOrientation(LinearLayout.HORIZONTAL);
        subTabStrip.setPadding(0, 0, 0, (int) (10 * dp));
        subTabScroll.addView(subTabStrip);
        root.addView(subTabScroll);

        // 5. Adaptive GridView (5 cols for Emojis, 4 cols for Animated Stickers, 2 cols for Widescreen/Portrait GIFs)
        gridView = new GridView(context);
        gridView.setVerticalSpacing((int) (12 * dp));
        gridView.setHorizontalSpacing((int) (12 * dp));
        gridView.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        gridView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (290 * dp)));

        gridAdapter = new TrayGridAdapter(context);
        gridView.setAdapter(gridAdapter);

        gridView.setOnItemClickListener((parent, view1, position, id) -> {
            if (currentMode == MODE_EMOJIS) {
                if (position < emojiItems.size()) {
                    String chosen = emojiItems.get(position);
                    saveRecentEmoji(context, chosen);
                    if (listener != null) listener.onEmojiSelected(chosen);
                    dismiss();
                }
            } else if (currentMode == MODE_STICKERS) {
                if (position < stickerItems.size()) {
                    VeilStickerGifEngine.StickerPreset sticker = stickerItems.get(position);
                    saveRecentEmoji(context, sticker.emoji);
                    if (stickerGifListener != null) {
                        stickerGifListener.onStickerSelected(sticker.code);
                    } else if (listener != null) {
                        listener.onEmojiSelected(sticker.emoji);
                    }
                    dismiss();
                }
            } else if (currentMode == MODE_GIFS) {
                if (position < gifItems.size()) {
                    VeilStickerGifEngine.GifPreset gif = gifItems.get(position);
                    if (stickerGifListener != null) {
                        stickerGifListener.onGifSelected(gif.code);
                    } else if (listener != null) {
                        listener.onEmojiSelected(gif.centerEmoji);
                    }
                    dismiss();
                }
            }
        });

        root.addView(gridView);

        switchMode(MODE_EMOJIS);
        return root;
    }

    private void switchMode(int mode) {
        this.currentMode = mode;
        Context context = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        // Style top segmented mode tabs
        for (int i = 0; i < modeSwitchBar.getChildCount(); i++) {
            View child = modeSwitchBar.getChildAt(i);
            if (child instanceof TextView) {
                boolean active = (i == currentMode);
                child.setBackgroundResource(active ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                ((TextView) child).setTextColor(active ? Color.WHITE : ContextCompat.getColor(context, R.color.textPrimary));
            }
        }

        if (mode == MODE_EMOJIS) {
            gifSearchContainer.setVisibility(View.GONE);
            subTabScroll.setVisibility(View.GONE);
            gridView.setNumColumns(6);
            gridView.setVerticalSpacing((int) (12 * dp));
            gridView.setHorizontalSpacing((int) (10 * dp));
            loadStandardEmojis();
        } else if (mode == MODE_STICKERS) {
            gifSearchContainer.setVisibility(View.GONE);
            subTabScroll.setVisibility(View.VISIBLE);
            gridView.setNumColumns(4);
            gridView.setVerticalSpacing((int) (12 * dp));
            gridView.setHorizontalSpacing((int) (12 * dp));
            populateStickerSubTabs();
            loadStickerItems();
        } else if (mode == MODE_GIFS) {
            gifSearchContainer.setVisibility(View.VISIBLE);
            subTabScroll.setVisibility(View.VISIBLE);
            gridView.setNumColumns(2);
            gridView.setVerticalSpacing((int) (10 * dp));
            gridView.setHorizontalSpacing((int) (10 * dp));
            populateGifCategorySubTabs();
            String q = etGifSearch.getText() != null ? etGifSearch.getText().toString().trim() : "";
            loadGifItems(q.isEmpty() ? selectedGifCategory : q);
        }
    }

    private void populateStickerSubTabs() {
        Context context = requireContext();
        subTabStrip.removeAllViews();
        VeilFeatureManager fm = VeilFeatureManager.getInstance(context);
        List<VeilFeatureManager.StickerPack> installedPacks = fm.getInstalledStickerPacks();

        // Tab 0: Featured Animated Stickers
        TextView animTab = createTabChip(context, "🔥 Animated", selectedStickerSubTab == 0, v -> {
            selectedStickerSubTab = 0;
            populateStickerSubTabs();
            loadStickerItems();
        });
        subTabStrip.addView(animTab);

        // Installed Sticker Packs
        for (int i = 0; i < installedPacks.size(); i++) {
            final int tabIdx = i + 1;
            final VeilFeatureManager.StickerPack pack = installedPacks.get(i);
            TextView pTab = createTabChip(context, pack.name, selectedStickerSubTab == tabIdx, v -> {
                selectedStickerSubTab = tabIdx;
                populateStickerSubTabs();
                loadStickerItems();
            });
            subTabStrip.addView(pTab);
        }

        // Custom Sticker Creator
        TextView btnCustom = createTabChip(context, "➕ Custom", false, v -> showCreateCustomStickerDialog(context));
        subTabStrip.addView(btnCustom);

        // Sticker Store
        TextView btnStore = createTabChip(context, "🛍️ Store", false, v -> {
            dismiss();
            StickerMarketplaceBottomSheet store = new StickerMarketplaceBottomSheet();
            store.show(getParentFragmentManager(), "StickerMarketplaceBottomSheet");
        });
        btnStore.setTextColor(ContextCompat.getColor(context, R.color.veil_accent));
        subTabStrip.addView(btnStore);
    }

    private void populateGifCategorySubTabs() {
        Context context = requireContext();
        subTabStrip.removeAllViews();
        String[] categories = {"All", "Trending", "Reactions", "Celebrate", "Meme", "Love", "Tech"};
        for (String cat : categories) {
            boolean selected = cat.equalsIgnoreCase(selectedGifCategory);
            TextView chip = createTabChip(context, cat, selected, v -> {
                selectedGifCategory = cat;
                if (etGifSearch != null && etGifSearch.getText().length() > 0) {
                    etGifSearch.setText("");
                }
                populateGifCategorySubTabs();
                loadGifItems(cat);
            });
            subTabStrip.addView(chip);
        }
    }

    private void loadStandardEmojis() {
        emojiItems.clear();
        if (getContext() != null) {
            String rec = getContext().getSharedPreferences("VeilEmojiPrefs", Context.MODE_PRIVATE).getString("recent_emojis", "");
            if (rec != null && !rec.isEmpty()) {
                for (String s : rec.split(";;")) {
                    if (!s.isEmpty() && s.length() <= 4 && !emojiItems.contains(s)) {
                        emojiItems.add(s);
                    }
                }
            }
        }
        int[] unicodeEmojis = {
                0x1F600, 0x1F603, 0x1F604, 0x1F601, 0x1F606,
                0x1F605, 0x1F602, 0x1F923, 0x1F60A, 0x1F609,
                0x1F607, 0x1F642, 0x1F643, 0x1F60D, 0x1F618,
                0x1F617, 0x1F619, 0x1F61A, 0x1F60B, 0x1F914,
                0x1F910, 0x1F928, 0x1F610, 0x1F611, 0x1F929,
                0x1F973, 0x1F970, 0x1F92A, 0x1F974, 0x1F60E,
                0x1F525, 0x2764, 0x1F44D, 0x1F44F, 0x1F64F,
                0x1F680, 0x1F389, 0x26A1, 0x1F4AF, 0x1F47B,
                0x1F916, 0x1F40B, 0x1F48E, 0x1F31F, 0x1F355,
                0x1F37F, 0x1F3C6, 0x1F451
        };
        for (int codePoint : unicodeEmojis) {
            String em = new String(Character.toChars(codePoint));
            if (!emojiItems.contains(em)) {
                emojiItems.add(em);
            }
        }
        if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
    }

    private void loadStickerItems() {
        stickerItems.clear();
        Context context = getContext();
        if (context == null) return;
        if (selectedStickerSubTab == 0) {
            stickerItems.addAll(VeilStickerGifEngine.getBuiltinAnimatedStickers());
        } else {
            List<VeilFeatureManager.StickerPack> packs = VeilFeatureManager.getInstance(context).getInstalledStickerPacks();
            int packIdx = selectedStickerSubTab - 1;
            if (packIdx >= 0 && packIdx < packs.size()) {
                VeilFeatureManager.StickerPack pack = packs.get(packIdx);
                if (pack.stickers != null) {
                    for (String s : pack.stickers) {
                        stickerItems.add(VeilStickerGifEngine.resolveStickerPreset(s));
                    }
                }
            }
        }
        if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
    }

    private void loadGifItems(String queryOrCategory) {
        gifItems.clear();
        gifItems.addAll(VeilStickerGifEngine.getBuiltinGifs(queryOrCategory));
        if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
    }

    private void saveRecentEmoji(Context context, String item) {
        if (context == null || item == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("VeilEmojiPrefs", Context.MODE_PRIVATE);
        String existing = prefs.getString("recent_emojis", "");
        List<String> list = new ArrayList<>();
        list.add(item);
        if (existing != null && !existing.isEmpty()) {
            for (String s : existing.split(";;")) {
                if (!s.isEmpty() && !list.contains(s) && list.size() < 15) {
                    list.add(s);
                }
            }
        }
        prefs.edit().putString("recent_emojis", android.text.TextUtils.join(";;", list)).apply();
    }

    private void showCreateCustomStickerDialog(Context context) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText("➕ Create Custom Animated Sticker");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (10 * dp));
        root.addView(title);

        EditText etSticker = new EditText(context);
        etSticker.setHint("e.g. 🔥 LEGEND 🔥 or 🚀");
        etSticker.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        etSticker.setHintTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        etSticker.setBackgroundResource(R.drawable.bg_search_input);
        int inPad = (int) (12 * dp);
        etSticker.setPadding(inPad, inPad, inPad, inPad);
        root.addView(etSticker);

        com.google.android.material.button.MaterialButton btnSave = new com.google.android.material.button.MaterialButton(context);
        btnSave.setText("Send Animated Sticker");
        btnSave.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (14 * dp);
        btnSave.setLayoutParams(lp);
        btnSave.setOnClickListener(v -> {
            String custom = etSticker.getText().toString().trim();
            if (!custom.isEmpty()) {
                sheet.dismiss();
                if (stickerGifListener != null) {
                    stickerGifListener.onStickerSelected("[STICKER:" + custom + "]");
                } else if (listener != null) {
                    listener.onEmojiSelected(custom);
                }
                dismiss();
            }
        });
        root.addView(btnSave);

        sheet.setContentView(root);
        sheet.show();
    }

    private TextView createTabChip(Context context, String label, boolean selected, View.OnClickListener onClick) {
        float dp = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(12.5f);
        chip.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
        chip.setBackgroundResource(selected ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
        chip.setTextColor(selected ? Color.WHITE : ContextCompat.getColor(context, R.color.textPrimary));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, (int) (6 * dp), 0);
        chip.setLayoutParams(lp);

        chip.setOnClickListener(onClick);
        return chip;
    }

    // =========================================================================
    // ADAPTER FOR EMOJIS, ANIMATED STICKERS, AND ANIMATED GIF CARDS
    // =========================================================================

    private class TrayGridAdapter extends BaseAdapter {
        private final Context context;
        private final float dp;

        TrayGridAdapter(Context context) {
            this.context = context;
            this.dp = context.getResources().getDisplayMetrics().density;
        }

        @Override
        public int getCount() {
            if (currentMode == MODE_EMOJIS) return emojiItems.size();
            if (currentMode == MODE_STICKERS) return stickerItems.size();
            return gifItems.size();
        }

        @Override
        public Object getItem(int position) {
            if (currentMode == MODE_EMOJIS) return emojiItems.get(position);
            if (currentMode == MODE_STICKERS) return stickerItems.get(position);
            return gifItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (currentMode == MODE_EMOJIS) {
                TextView tv = (convertView instanceof TextView) ? (TextView) convertView : new TextView(context);
                String val = emojiItems.get(position);
                tv.setText(val);
                tv.setTextSize(26f);
                tv.setGravity(Gravity.CENTER);
                tv.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * dp)));
                return tv;
            } else if (currentMode == MODE_STICKERS) {
                VeilStickerGifEngine.StickerPreset preset = stickerItems.get(position);
                ImageView iv = new ImageView(context);
                int size = (int) (74 * dp);
                iv.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                VeilStickerGifEngine.AnimatedStickerDrawable drawable =
                        new VeilStickerGifEngine.AnimatedStickerDrawable(context, preset);
                iv.setImageDrawable(drawable);
                drawable.start();
                return iv;
            } else {
                // MODE_GIFS: Rounded animated GIF preview card with "GIF" badge
                VeilStickerGifEngine.GifPreset gif = gifItems.get(position);
                FrameLayout card = new FrameLayout(context);
                int cardH = (int) (96 * dp);
                card.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH));

                GradientDrawable clipBg = new GradientDrawable();
                clipBg.setCornerRadius(10f * dp);
                clipBg.setColor(gif.bgStartColor);
                card.setBackground(clipBg);
                card.setClipToOutline(true);

                ImageView iv = new ImageView(context);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                VeilStickerGifEngine.AnimatedGifSceneDrawable scene =
                        new VeilStickerGifEngine.AnimatedGifSceneDrawable(context, gif);
                iv.setImageDrawable(scene);
                scene.start();
                card.addView(iv);

                TextView badge = new TextView(context);
                badge.setText("GIF");
                badge.setTextSize(9f);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setTextColor(Color.WHITE);
                badge.setBackgroundResource(R.drawable.bg_media_overlay_pill);
                badge.setPadding((int) (5 * dp), (int) (2 * dp), (int) (5 * dp), (int) (2 * dp));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
                blp.setMargins((int) (6 * dp), (int) (6 * dp), 0, 0);
                badge.setLayoutParams(blp);
                card.addView(badge);

                return card;
            }
        }
    }
}
