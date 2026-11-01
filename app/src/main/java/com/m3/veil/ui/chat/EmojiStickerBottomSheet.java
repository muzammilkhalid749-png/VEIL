package com.m3.veil.ui.chat;



import android.app.Dialog;

import android.content.Context;

import android.graphics.Bitmap;

import android.graphics.BitmapFactory;

import android.graphics.Color;

import android.graphics.Typeface;

import android.graphics.drawable.ColorDrawable;

import android.graphics.drawable.GradientDrawable;

import android.os.Bundle;

import android.text.Editable;

import android.text.TextWatcher;

import android.view.Gravity;

import android.view.HapticFeedbackConstants;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.view.Window;

import android.widget.BaseAdapter;

import android.widget.EditText;

import android.widget.FrameLayout;

import android.widget.GridView;

import android.widget.HorizontalScrollView;

import android.widget.ImageView;

import android.widget.LinearLayout;

import android.widget.ScrollView;

import android.widget.TextView;

import android.widget.Toast;



import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.core.content.ContextCompat;



import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import com.google.android.material.button.MaterialButton;

import com.m3.veil.R;

import com.m3.veil.manager.StickerRepository;

import com.m3.veil.ui.chat.sheets.StickerMarketplaceBottomSheet;

import com.m3.veil.utils.VeilStickerGifEngine;



import java.io.File;

import java.util.ArrayList;

import java.util.List;



/**

 * Phase 8 Production-Ready 3-in-1 Rich Media & Sticker Store Tray:

 * - Mode 0: 😀 Emojis (Recent + Unicode Emojis)

 * - Mode 1: ✨ Stickers with 4-Section Sticker Navigation:

 *   1. Store / Discover (+ button): Browse pack previews, author, sticker count, and one-tap Add Pack / Remove Pack

 *   2. Recent (🕒 Clock icon): Automatically populated in SQLite when a sticker is sent

 *   3. Favorites (⭐ Star icon): Long-pressing any sticker shows a preview popup with "Add to Favorites"

 *   4. Installed Packs: Horizontal icon tabs for each added pack + Featured Animated pack

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



    // Emoji sub-tab constants
    private static final int EMOJI_TAB_FAVORITES = -1;
    private static final int EMOJI_TAB_ALL = 0;
    private static final int EMOJI_TAB_RECENT = 1;
    private static final int EMOJI_TAB_HEARTS = 2;
    private static final int EMOJI_TAB_GESTURES = 3;
    private static final int EMOJI_TAB_FIRE_OBJECTS = 4;

    private int selectedEmojiSubTab = EMOJI_TAB_ALL;

    // Sticker sub-tab constants

    private static final int STICKER_TAB_STORE = -3;

    private static final int STICKER_TAB_RECENT = -2;

    private static final int STICKER_TAB_FAVORITES = -1;

    private static final int STICKER_TAB_FEATURED = 0;

    // >= 1 corresponds to installedPacks.get(selectedStickerSubTab - 1)



    private OnEmojiSelectedListener listener;

    private OnStickerOrGifSelectedListener stickerGifListener;



    private int currentMode = MODE_EMOJIS;

    private int selectedStickerSubTab = STICKER_TAB_FEATURED;

    private String selectedGifCategory = "All";



    private LinearLayout modeSwitchBar;

    private HorizontalScrollView subTabScroll;

    private LinearLayout subTabStrip;

    private LinearLayout gifSearchContainer;

    private EditText etGifSearch;

    private FrameLayout contentSwapFrame;

    private GridView gridView;

    private ScrollView storeDiscoverScrollView;

    private LinearLayout storeDiscoverContainer;

    private TextView emptyStateView;



    private final List<String> emojiItems = new ArrayList<>();

    private final List<StickerRepository.StickerItem> stickerItems = new ArrayList<>();

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



        GradientDrawable pillContainerBg = new GradientDrawable();

        pillContainerBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

        pillContainerBg.setCornerRadius(22f * dp);

        modeSwitchBar.setBackground(pillContainerBg);

        int modePad = (int) (3 * dp);

        modeSwitchBar.setPadding(modePad, modePad, modePad, modePad);



        String[] modeTitles = {"😀 Emojis", "✨ Stickers", "🎬 GIFs"};

        for (int i = 0; i < modeTitles.length; i++) {

            final int modeIdx = i;

            TextView tab = new TextView(context);

            tab.setText(modeTitles[i]);

            tab.setTextSize(13f);

            tab.setGravity(Gravity.CENTER);

            tab.setPadding((int) (12 * dp), (int) (7 * dp), (int) (12 * dp), (int) (7 * dp));

            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

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



        // 4. Horizontal 4-Section Sticker Navigation / GIF Category Strip

        subTabScroll = new HorizontalScrollView(context);

        subTabScroll.setHorizontalScrollBarEnabled(false);

        subTabStrip = new LinearLayout(context);

        subTabStrip.setOrientation(LinearLayout.HORIZONTAL);

        subTabStrip.setGravity(Gravity.CENTER_VERTICAL);

        subTabStrip.setPadding(0, 0, 0, (int) (10 * dp));

        subTabScroll.addView(subTabStrip);

        root.addView(subTabScroll);



        // 5. Content Swap Frame (holds GridView, Empty State, and Store / Discover View)

        contentSwapFrame = new FrameLayout(context);

        contentSwapFrame.setLayoutParams(new LinearLayout.LayoutParams(

                LinearLayout.LayoutParams.MATCH_PARENT, (int) (310 * dp)));



        gridView = new GridView(context);

        gridView.setVerticalSpacing((int) (12 * dp));

        gridView.setHorizontalSpacing((int) (12 * dp));

        gridView.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));

        gridView.setLayoutParams(new FrameLayout.LayoutParams(

                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));



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

                    StickerRepository.StickerItem sticker = stickerItems.get(position);

                    dispatchStickerSelection(context, sticker);

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



        // Long-pressing any Emoji, Sticker, or GIF opens the Preview Popup with Add to / Remove from Favorites
        gridView.setOnItemLongClickListener((parent, view12, position, id) -> {
            if (currentMode == MODE_EMOJIS && position < emojiItems.size()) {
                view12.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showEmojiPreviewPopup(context, emojiItems.get(position));
                return true;
            } else if (currentMode == MODE_STICKERS && position < stickerItems.size()) {
                view12.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showStickerPreviewPopup(context, stickerItems.get(position));
                return true;
            } else if (currentMode == MODE_GIFS && position < gifItems.size()) {
                view12.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showGifPreviewPopup(context, gifItems.get(position));
                return true;
            }
            return false;
        });



        contentSwapFrame.addView(gridView);



        // Empty state for Favorites / Recent when empty

        emptyStateView = new TextView(context);

        emptyStateView.setGravity(Gravity.CENTER);

        emptyStateView.setTextSize(14f);

        emptyStateView.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        emptyStateView.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), (int) (24 * dp));

        emptyStateView.setVisibility(View.GONE);

        contentSwapFrame.addView(emptyStateView, new FrameLayout.LayoutParams(

                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));



        // Store / Discover Scrollable Container

        storeDiscoverScrollView = new ScrollView(context);

        storeDiscoverScrollView.setFillViewport(true);

        storeDiscoverScrollView.setVisibility(View.GONE);

        storeDiscoverContainer = new LinearLayout(context);

        storeDiscoverContainer.setOrientation(LinearLayout.VERTICAL);

        storeDiscoverContainer.setPadding(0, (int) (4 * dp), 0, (int) (16 * dp));

        storeDiscoverScrollView.addView(storeDiscoverContainer);

        contentSwapFrame.addView(storeDiscoverScrollView, new FrameLayout.LayoutParams(

                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));



        root.addView(contentSwapFrame);



        switchMode(MODE_EMOJIS);

        return root;

    }



    private void dispatchStickerSelection(@NonNull Context context, @NonNull StickerRepository.StickerItem sticker) {

        StickerRepository.getInstance(context).recordRecentSticker(sticker);

        saveRecentEmoji(context, sticker.emoji);

        if (stickerGifListener != null) {

            stickerGifListener.onStickerSelected(sticker.code);

        } else if (listener != null) {

            listener.onEmojiSelected(sticker.emoji);

        }

        dismiss();

    }



    private void switchMode(int mode) {

        this.currentMode = mode;

        Context context = requireContext();

        float dp = getResources().getDisplayMetrics().density;



        for (int i = 0; i < modeSwitchBar.getChildCount(); i++) {

            View child = modeSwitchBar.getChildAt(i);

            if (child instanceof TextView) {

                boolean active = (i == currentMode);

                TextView tv = (TextView) child;

                tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);

                if (active) {

                    GradientDrawable activeBg = new GradientDrawable();

                    activeBg.setColor(ContextCompat.getColor(context, R.color.colorPrimary));

                    activeBg.setCornerRadius(19f * dp);

                    tv.setBackground(activeBg);

                    tv.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary));

                } else {

                    tv.setBackground(null);

                    tv.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

                }

            }

        }



        if (mode == MODE_EMOJIS) {

            gifSearchContainer.setVisibility(View.GONE);

            subTabScroll.setVisibility(View.VISIBLE);

            storeDiscoverScrollView.setVisibility(View.GONE);

            gridView.setNumColumns(6);

            gridView.setVerticalSpacing((int) (12 * dp));

            gridView.setHorizontalSpacing((int) (10 * dp));

            populateEmojiSubTabs();

            loadEmojiItems();

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

            storeDiscoverScrollView.setVisibility(View.GONE);

            emptyStateView.setVisibility(View.GONE);

            gridView.setVisibility(View.VISIBLE);

            gridView.setNumColumns(2);

            gridView.setVerticalSpacing((int) (10 * dp));

            gridView.setHorizontalSpacing((int) (10 * dp));

            populateGifCategorySubTabs();

            String q = etGifSearch.getText() != null ? etGifSearch.getText().toString().trim() : "";

            loadGifItems(q.isEmpty() ? selectedGifCategory : q);

        }

    }



    /**

     * Populates the 4-tab Sticker Navigation Strip:

     * 1. Store / Discover (+ button)

     * 2. Recent (🕒 Clock icon)

     * 3. Favorites (⭐ Star icon)

     * 4. Installed Packs (Horizontal icon tabs for each installed pack)

     */

    private void populateStickerSubTabs() {

        Context context = requireContext();

        subTabStrip.removeAllViews();

        StickerRepository repo = StickerRepository.getInstance(context);

        List<StickerRepository.StickerPackMeta> installedPacks = repo.getInstalledPacks();



        // 1. Store / Discover (+ button)

        TextView storeTab = createTabChip(

                context,

                "➕ Store",

                selectedStickerSubTab == STICKER_TAB_STORE,

                v -> {

                    selectedStickerSubTab = STICKER_TAB_STORE;

                    populateStickerSubTabs();

                    loadStickerItems();

                });

        if (selectedStickerSubTab != STICKER_TAB_STORE) {

            storeTab.setTextColor(ContextCompat.getColor(context, R.color.veil_accent));

        }

        subTabStrip.addView(storeTab);



        // 2. Recent (Clock icon)

        TextView recentTab = createTabChip(

                context,

                "🕒 Recent",

                selectedStickerSubTab == STICKER_TAB_RECENT,

                v -> {

                    selectedStickerSubTab = STICKER_TAB_RECENT;

                    populateStickerSubTabs();

                    loadStickerItems();

                });

        subTabStrip.addView(recentTab);



        // 3. Favorites (Star icon)

        TextView favTab = createTabChip(

                context,

                "⭐ Favorites",

                selectedStickerSubTab == STICKER_TAB_FAVORITES,

                v -> {

                    selectedStickerSubTab = STICKER_TAB_FAVORITES;

                    populateStickerSubTabs();

                    loadStickerItems();

                });

        subTabStrip.addView(favTab);



        // 4A. Featured Animated Stickers

        TextView animTab = createTabChip(

                context,

                "🔥 Animated",

                selectedStickerSubTab == STICKER_TAB_FEATURED,

                v -> {

                    selectedStickerSubTab = STICKER_TAB_FEATURED;

                    populateStickerSubTabs();

                    loadStickerItems();

                });

        subTabStrip.addView(animTab);



        // 4B. Installed Sticker Packs (Horizontal icon tabs)

        for (int i = 0; i < installedPacks.size(); i++) {

            final int tabIdx = i + 1;

            final StickerRepository.StickerPackMeta pack = installedPacks.get(i);

            String label = (pack.iconEmoji != null ? pack.iconEmoji + " " : "") + pack.title;

            TextView pTab = createTabChip(context, label, selectedStickerSubTab == tabIdx, v -> {

                selectedStickerSubTab = tabIdx;

                populateStickerSubTabs();

                loadStickerItems();

            });

            subTabStrip.addView(pTab);

        }



        // Custom Sticker Creator chip

        TextView btnCustom = createTabChip(context, "🎨 Custom", false, v -> showCreateCustomStickerDialog(context));

        subTabStrip.addView(btnCustom);

    }



    private void populateGifCategorySubTabs() {

        Context context = requireContext();

        subTabStrip.removeAllViews();

        String[] categories = {"⭐ Favorites", "All", "Trending", "Reactions", "Memes", "Celebrate", "Love", "Tech", "Crypto", "Gaming", "Anime"};

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



    private void populateEmojiSubTabs() {
        Context context = requireContext();
        subTabStrip.removeAllViews();

        // 1. Favorites
        TextView favTab = createTabChip(context, "⭐ Favorites", selectedEmojiSubTab == EMOJI_TAB_FAVORITES, v -> {
            selectedEmojiSubTab = EMOJI_TAB_FAVORITES;
            populateEmojiSubTabs();
            loadEmojiItems();
        });
        subTabStrip.addView(favTab);

        // 2. All Smileys
        TextView allTab = createTabChip(context, "😀 All", selectedEmojiSubTab == EMOJI_TAB_ALL, v -> {
            selectedEmojiSubTab = EMOJI_TAB_ALL;
            populateEmojiSubTabs();
            loadEmojiItems();
        });
        subTabStrip.addView(allTab);

        // 3. Recent
        TextView recentTab = createTabChip(context, "🕒 Recent", selectedEmojiSubTab == EMOJI_TAB_RECENT, v -> {
            selectedEmojiSubTab = EMOJI_TAB_RECENT;
            populateEmojiSubTabs();
            loadEmojiItems();
        });
        subTabStrip.addView(recentTab);

        // 4. Love & Hearts
        TextView loveTab = createTabChip(context, "💖 Hearts", selectedEmojiSubTab == EMOJI_TAB_HEARTS, v -> {
            selectedEmojiSubTab = EMOJI_TAB_HEARTS;
            populateEmojiSubTabs();
            loadEmojiItems();
        });
        subTabStrip.addView(loveTab);

        // 5. Gestures
        TextView gestureTab = createTabChip(context, "👍 Gestures", selectedEmojiSubTab == EMOJI_TAB_GESTURES, v -> {
            selectedEmojiSubTab = EMOJI_TAB_GESTURES;
            populateEmojiSubTabs();
            loadEmojiItems();
        });
        subTabStrip.addView(gestureTab);

        // 6. Fire & Vibes
        TextView fireTab = createTabChip(context, "🔥 Vibes", selectedEmojiSubTab == EMOJI_TAB_FIRE_OBJECTS, v -> {
            selectedEmojiSubTab = EMOJI_TAB_FIRE_OBJECTS;
            populateEmojiSubTabs();
            loadEmojiItems();
        });
        subTabStrip.addView(fireTab);
    }

    private void loadEmojiItems() {
        emojiItems.clear();
        Context context = getContext();
        if (context == null) return;

        if (selectedEmojiSubTab == EMOJI_TAB_FAVORITES) {
            List<String> favs = StickerRepository.getInstance(context).getFavoriteEmojis();
            emojiItems.addAll(favs);
            if (emojiItems.isEmpty()) {
                gridView.setVisibility(View.GONE);
                emptyStateView.setVisibility(View.VISIBLE);
                emptyStateView.setText("⭐ No favorite emojis yet.\nLong-press any emoji to add it to Favorites!");
            } else {
                emptyStateView.setVisibility(View.GONE);
                gridView.setVisibility(View.VISIBLE);
            }
        } else if (selectedEmojiSubTab == EMOJI_TAB_RECENT) {
            String rec = context.getSharedPreferences("VeilEmojiPrefs", Context.MODE_PRIVATE).getString("recent_emojis", "");
            if (rec != null && !rec.isEmpty()) {
                for (String s : rec.split(";;")) {
                    if (!s.isEmpty() && s.length() <= 4 && !emojiItems.contains(s)) {
                        emojiItems.add(s);
                    }
                }
            }
            if (emojiItems.isEmpty()) {
                gridView.setVisibility(View.GONE);
                emptyStateView.setVisibility(View.VISIBLE);
                emptyStateView.setText("🕒 No recent emojis yet.\nTap any emoji to send!");
            } else {
                emptyStateView.setVisibility(View.GONE);
                gridView.setVisibility(View.VISIBLE);
            }
        } else if (selectedEmojiSubTab == EMOJI_TAB_HEARTS) {
            emptyStateView.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            String[] hearts = {
                    "❤️", "💖", "💘", "💝", "💗", "💓", "💞", "💕", "💌", "💟",
                    "❣️", "💔", "🧡", "💛", "💚", "💙", "💜", "🤎", "🖤", "🤍",
                    "😍", "🥰", "😘", "😻", "💋", "🫂", "🫶", "🌹", "💐", "🌺"
            };
            for (String h : hearts) {
                if (!emojiItems.contains(h)) emojiItems.add(h);
            }
        } else if (selectedEmojiSubTab == EMOJI_TAB_GESTURES) {
            emptyStateView.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            String[] gestures = {
                    "👍", "👎", "👏", "🙌", "🤝", "🙏", "✌️", "🤞", "🤟", "🤘",
                    "🤙", "👈", "👉", "👆", "👇", "✋", "🤚", "🖐️", "🖖", "👋",
                    "💪", "🦾", "👀", "🧠", "🫡", "🤝", "👊", "✊", "🤛", "🤜"
            };
            for (String g : gestures) {
                if (!emojiItems.contains(g)) emojiItems.add(g);
            }
        } else if (selectedEmojiSubTab == EMOJI_TAB_FIRE_OBJECTS) {
            emptyStateView.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            String[] objs = {
                    "🔥", "🚀", "⚡", "💯", "🎉", "💎", "👑", "🏆", "🎯", "🍕",
                    "☕", "🍿", "🍻", "🛸", "🤖", "👾", "👻", "✨", "🌟", "⭐",
                    "💥", "🧨", "🕶️", "🗝️", "🔐", "🛡️", "🔮", "🧿", "♾️", "🪙"
            };
            for (String o : objs) {
                if (!emojiItems.contains(o)) emojiItems.add(o);
            }
        } else {
            // EMOJI_TAB_ALL: Standard + recent
            emptyStateView.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            String rec = context.getSharedPreferences("VeilEmojiPrefs", Context.MODE_PRIVATE).getString("recent_emojis", "");
            if (rec != null && !rec.isEmpty()) {
                for (String s : rec.split(";;")) {
                    if (!s.isEmpty() && s.length() <= 4 && !emojiItems.contains(s)) {
                        emojiItems.add(s);
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
        }
        if (gridAdapter != null) gridAdapter.notifyDataSetChanged();
    }



    private void loadStickerItems() {

        stickerItems.clear();

        Context context = getContext();

        if (context == null) return;

        StickerRepository repo = StickerRepository.getInstance(context);



        if (selectedStickerSubTab == STICKER_TAB_STORE) {

            gridView.setVisibility(View.GONE);

            emptyStateView.setVisibility(View.GONE);

            storeDiscoverScrollView.setVisibility(View.VISIBLE);

            renderStoreDiscoverView(context, repo);

            return;

        }



        storeDiscoverScrollView.setVisibility(View.GONE);



        if (selectedStickerSubTab == STICKER_TAB_RECENT) {

            stickerItems.addAll(repo.getRecentStickers());

            if (stickerItems.isEmpty()) {

                gridView.setVisibility(View.GONE);

                emptyStateView.setVisibility(View.VISIBLE);

                emptyStateView.setText("🕒 No recent stickers yet.\nSend any sticker to see it here!");

            } else {

                emptyStateView.setVisibility(View.GONE);

                gridView.setVisibility(View.VISIBLE);

            }

        } else if (selectedStickerSubTab == STICKER_TAB_FAVORITES) {

            stickerItems.addAll(repo.getFavoriteStickers());

            if (stickerItems.isEmpty()) {

                gridView.setVisibility(View.GONE);

                emptyStateView.setVisibility(View.VISIBLE);

                emptyStateView.setText("⭐ No favorite stickers yet.\nLong-press any sticker to preview and add it to Favorites!");

            } else {

                emptyStateView.setVisibility(View.GONE);

                gridView.setVisibility(View.VISIBLE);

            }

        } else if (selectedStickerSubTab == STICKER_TAB_FEATURED) {

            emptyStateView.setVisibility(View.GONE);

            gridView.setVisibility(View.VISIBLE);

            for (VeilStickerGifEngine.StickerPreset p : VeilStickerGifEngine.getBuiltinAnimatedStickers()) {

                stickerItems.add(new StickerRepository.StickerItem(

                        p.code, "featured", p.code, p.emoji, p.label,

                        p.primaryColor, p.secondaryColor, p.styleType, null, true

                ));

            }

        } else {

            emptyStateView.setVisibility(View.GONE);

            gridView.setVisibility(View.VISIBLE);

            List<StickerRepository.StickerPackMeta> installedPacks = repo.getInstalledPacks();

            int packIdx = selectedStickerSubTab - 1;

            if (packIdx >= 0 && packIdx < installedPacks.size()) {

                StickerRepository.StickerPackMeta pack = installedPacks.get(packIdx);

                stickerItems.addAll(pack.stickers);

            }

        }

        if (gridAdapter != null) gridAdapter.notifyDataSetChanged();

    }



    /**

     * Renders the Store / Discover tab inside the Sticker Picker:

     * Browse pack previews, author, sticker count, and one-tap Add Pack / Remove Pack.

     */

    private void renderStoreDiscoverView(@NonNull Context context, @NonNull StickerRepository repo) {

        storeDiscoverContainer.removeAllViews();

        float dp = getResources().getDisplayMetrics().density;



        // Top banner row with Full P2P Marketplace button

        LinearLayout topBanner = new LinearLayout(context);

        topBanner.setOrientation(LinearLayout.HORIZONTAL);

        topBanner.setGravity(Gravity.CENTER_VERTICAL);

        topBanner.setPadding(0, 0, 0, (int) (10 * dp));



        TextView tvHeader = new TextView(context);

        tvHeader.setText("Discover Curated Sticker Packs");

        tvHeader.setTextSize(14.5f);

        tvHeader.setTypeface(null, Typeface.BOLD);

        tvHeader.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));

        tvHeader.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topBanner.addView(tvHeader);



        TextView btnFullStore = createTabChip(context, "🛍️ Store", false, v -> {

            dismiss();

            StickerMarketplaceBottomSheet store = new StickerMarketplaceBottomSheet();

            store.show(getParentFragmentManager(), "StickerMarketplaceBottomSheet");

        });

        btnFullStore.setTextColor(ContextCompat.getColor(context, R.color.veil_accent));

        topBanner.addView(btnFullStore);

        storeDiscoverContainer.addView(topBanner);



        List<StickerRepository.StickerPackMeta> allPacks = repo.getAllStorePacks();

        for (StickerRepository.StickerPackMeta pack : allPacks) {

            final boolean installed = repo.isPackInstalled(pack.packId);



            LinearLayout card = new LinearLayout(context);

            card.setOrientation(LinearLayout.VERTICAL);

            GradientDrawable cardBg = new GradientDrawable();

            cardBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

            cardBg.setCornerRadius(14f * dp);

            cardBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

            card.setBackground(cardBg);

            int pad = (int) (12 * dp);

            card.setPadding(pad, pad, pad, pad);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(

                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            cardLp.bottomMargin = (int) (10 * dp);

            card.setLayoutParams(cardLp);



            // Header Row: Pack Icon + Title/Author/Count + Add/Remove Pack button

            LinearLayout headerRow = new LinearLayout(context);

            headerRow.setOrientation(LinearLayout.HORIZONTAL);

            headerRow.setGravity(Gravity.CENTER_VERTICAL);



            LinearLayout titleCol = new LinearLayout(context);

            titleCol.setOrientation(LinearLayout.VERTICAL);

            titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));



            TextView tvTitle = new TextView(context);

            tvTitle.setText((pack.iconEmoji != null ? pack.iconEmoji + " " : "") + pack.title);

            tvTitle.setTextSize(15f);

            tvTitle.setTypeface(null, Typeface.BOLD);

            tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));

            titleCol.addView(tvTitle);



            TextView tvSub = new TextView(context);

            tvSub.setText(pack.author + " • " + pack.getStickerCount() + " stickers");

            tvSub.setTextSize(12f);

            tvSub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

            titleCol.addView(tvSub);

            headerRow.addView(titleCol);



            MaterialButton actionBtn = new MaterialButton(context);

            actionBtn.setAllCaps(false);

            actionBtn.setTextSize(12f);

            actionBtn.setCornerRadius((int) (16 * dp));

            if (installed) {

                actionBtn.setText("Remove Pack");

                actionBtn.setBackgroundColor(ContextCompat.getColor(context, R.color.colorSurfaceContainerHighest));

                actionBtn.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));

                actionBtn.setOnClickListener(v -> {

                    repo.removePack(pack.packId);

                    Toast.makeText(context, "Removed " + pack.title, Toast.LENGTH_SHORT).show();

                    populateStickerSubTabs();

                    renderStoreDiscoverView(context, repo);

                });

            } else {

                actionBtn.setText("+ Add Pack");

                actionBtn.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));

                actionBtn.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary));

                actionBtn.setOnClickListener(v -> {

                    actionBtn.setEnabled(false);

                    actionBtn.setText("Adding...");

                    repo.downloadAndAddPack(pack.packId, (success, addedPack) -> {

                        if (!isAdded()) return;

                        Toast.makeText(context, "Added " + addedPack.title + " (" + addedPack.getStickerCount() + " stickers)", Toast.LENGTH_SHORT).show();

                        populateStickerSubTabs();

                        renderStoreDiscoverView(context, repo);

                    });

                });

            }

            headerRow.addView(actionBtn);

            card.addView(headerRow);



            // Horizontal Sticker Preview Strip

            HorizontalScrollView previewScroll = new HorizontalScrollView(context);

            previewScroll.setHorizontalScrollBarEnabled(false);

            LinearLayout previewRow = new LinearLayout(context);

            previewRow.setOrientation(LinearLayout.HORIZONTAL);

            previewRow.setPadding(0, (int) (8 * dp), 0, 0);



            int previewCount = Math.min(8, pack.stickers.size());

            for (int i = 0; i < previewCount; i++) {

                final StickerRepository.StickerItem sItem = pack.stickers.get(i);

                ImageView iv = new ImageView(context);

                int sSize = (int) (62 * dp);

                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(sSize, sSize);

                ivLp.rightMargin = (int) (8 * dp);

                iv.setLayoutParams(ivLp);

                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

                bindStickerItemToImageView(context, sItem, iv, false);

                iv.setOnClickListener(v -> dispatchStickerSelection(context, sItem));

                iv.setOnLongClickListener(v -> {

                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                    showStickerPreviewPopup(context, sItem);

                    return true;

                });

                previewRow.addView(iv);

            }

            previewScroll.addView(previewRow);

            card.addView(previewScroll);



            storeDiscoverContainer.addView(card);

        }

    }



    /**

     * Shows a rich long-press Sticker Preview Popup with animated 150dp x 150dp preview,

     * "Add to Favorites" / "Remove from Favorites", and "Send Sticker".

     */

    private void showStickerPreviewPopup(@NonNull Context context, @NonNull StickerRepository.StickerItem item) {

        Dialog dialog = new Dialog(context);

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {

            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        }



        float dp = getResources().getDisplayMetrics().density;

        StickerRepository repo = StickerRepository.getInstance(context);

        boolean isFav = repo.isFavoriteSticker(item.code);



        LinearLayout card = new LinearLayout(context);

        card.setOrientation(LinearLayout.VERTICAL);

        card.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable cardBg = new GradientDrawable();

        cardBg.setColor(ContextCompat.getColor(context, R.color.colorSurface));

        cardBg.setCornerRadius(20f * dp);

        cardBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

        card.setBackground(cardBg);

        int pad = Math.round(20f * dp);

        card.setPadding(pad, pad, pad, pad);



        ImageView previewIv = new ImageView(context);

        int previewPx = Math.round(150f * dp);

        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(previewPx, previewPx);

        previewIv.setLayoutParams(ivLp);

        previewIv.setScaleType(ImageView.ScaleType.FIT_CENTER);

        bindStickerItemToImageView(context, item, previewIv, true);

        card.addView(previewIv);



        TextView tvLabel = new TextView(context);

        tvLabel.setText(item.emoji + "  " + item.label);

        tvLabel.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));

        tvLabel.setTextSize(15f);

        tvLabel.setTypeface(null, Typeface.BOLD);

        tvLabel.setPadding(0, Math.round(10f * dp), 0, Math.round(14f * dp));

        card.addView(tvLabel);



        MaterialButton btnFav = new MaterialButton(context);

        btnFav.setAllCaps(false);

        btnFav.setCornerRadius(Math.round(12f * dp));

        btnFav.setText(isFav ? "★ Remove from Favorites" : "⭐ Add to Favorites");

        btnFav.setBackgroundColor(ContextCompat.getColor(context, isFav ? R.color.colorSurfaceContainerHighest : R.color.colorPrimaryContainer));

        btnFav.setTextColor(ContextCompat.getColor(context, isFav ? R.color.textPrimary : R.color.colorOnPrimaryContainer));

        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(

                Math.round(230f * dp), ViewGroup.LayoutParams.WRAP_CONTENT);

        favLp.bottomMargin = Math.round(8f * dp);

        btnFav.setLayoutParams(favLp);

        btnFav.setOnClickListener(v -> {

            boolean nowFav = repo.toggleFavoriteSticker(item);

            Toast.makeText(context, nowFav ? "⭐ Added to Favorite Stickers" : "Removed from Favorites", Toast.LENGTH_SHORT).show();

            dialog.dismiss();

            if (selectedStickerSubTab == STICKER_TAB_FAVORITES) {

                loadStickerItems();

            }

        });

        card.addView(btnFav);






        MaterialButton btnSend = new MaterialButton(context);

        btnSend.setAllCaps(false);

        btnSend.setCornerRadius(Math.round(12f * dp));

        btnSend.setText("Send Sticker");

        btnSend.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));

        btnSend.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary));

        btnSend.setLayoutParams(new LinearLayout.LayoutParams(

                Math.round(230f * dp), ViewGroup.LayoutParams.WRAP_CONTENT));

        btnSend.setOnClickListener(v -> {

            dialog.dismiss();

            dispatchStickerSelection(context, item);

        });

        card.addView(btnSend);



        dialog.setContentView(card);

        dialog.show();

    }




    private void showEmojiPreviewPopup(@NonNull Context context, @NonNull String emoji) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        float dp = getResources().getDisplayMetrics().density;
        StickerRepository repo = StickerRepository.getInstance(context);
        boolean isFav = repo.isFavoriteEmoji(emoji);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(ContextCompat.getColor(context, R.color.colorSurface));
        cardBg.setCornerRadius(20f * dp);
        cardBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));
        card.setBackground(cardBg);
        int pad = Math.round(20f * dp);
        card.setPadding(pad, pad, pad, pad);

        TextView emojiTv = new TextView(context);
        emojiTv.setText(emoji);
        emojiTv.setTextSize(58f);
        emojiTv.setGravity(Gravity.CENTER);
        emojiTv.setPadding(0, Math.round(8f * dp), 0, Math.round(12f * dp));
        card.addView(emojiTv);

        MaterialButton btnFav = new MaterialButton(context);
        btnFav.setAllCaps(false);
        btnFav.setCornerRadius(Math.round(12f * dp));
        btnFav.setText(isFav ? "★ Remove from Favorites" : "⭐ Add to Favorites");
        btnFav.setBackgroundColor(ContextCompat.getColor(context, isFav ? R.color.colorSurfaceContainerHighest : R.color.colorPrimaryContainer));
        btnFav.setTextColor(ContextCompat.getColor(context, isFav ? R.color.textPrimary : R.color.colorOnPrimaryContainer));
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(
                Math.round(230f * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        favLp.bottomMargin = Math.round(8f * dp);
        btnFav.setLayoutParams(favLp);
        btnFav.setOnClickListener(v -> {
            boolean nowFav = repo.toggleFavoriteEmoji(emoji);
            Toast.makeText(context, nowFav ? "⭐ Added to Favorite Emojis" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (selectedEmojiSubTab == EMOJI_TAB_FAVORITES) {
                loadEmojiItems();
            }
        });
        card.addView(btnFav);

        MaterialButton btnSend = new MaterialButton(context);
        btnSend.setAllCaps(false);
        btnSend.setCornerRadius(Math.round(12f * dp));
        btnSend.setText("Send Emoji");
        btnSend.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));
        btnSend.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary));
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(230f * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        btnSend.setOnClickListener(v -> {
            dialog.dismiss();
            saveRecentEmoji(context, emoji);
            if (listener != null) listener.onEmojiSelected(emoji);
            dismiss();
        });
        card.addView(btnSend);

        dialog.setContentView(card);
        dialog.show();
    }

    private void showGifPreviewPopup(@NonNull Context context, @NonNull VeilStickerGifEngine.GifPreset gif) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        float dp = getResources().getDisplayMetrics().density;
        StickerRepository repo = StickerRepository.getInstance(context);
        boolean isFav = repo.isFavoriteGif(gif.code);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(ContextCompat.getColor(context, R.color.colorSurface));
        cardBg.setCornerRadius(20f * dp);
        cardBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));
        card.setBackground(cardBg);
        int pad = Math.round(20f * dp);
        card.setPadding(pad, pad, pad, pad);

        FrameLayout previewFrame = new FrameLayout(context);
        int previewW = Math.round(200f * dp);
        int previewH = Math.round(140f * dp);
        LinearLayout.LayoutParams pfLp = new LinearLayout.LayoutParams(previewW, previewH);
        previewFrame.setLayoutParams(pfLp);

        GradientDrawable pBg = new GradientDrawable();
        pBg.setCornerRadius(14f * dp);
        pBg.setColor(gif.bgStartColor);
        previewFrame.setBackground(pBg);
        previewFrame.setClipToOutline(true);

        ImageView previewIv = new ImageView(context);
        previewIv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String gifFilePath = VeilStickerGifEngine.extractFilePathFromCode(gif.code);
        boolean boundFileGif = false;
        if (gifFilePath != null) {
            File gf = new File(gifFilePath);
            if (gf.exists() && gf.length() > 16) {
                android.graphics.drawable.Drawable animGif =
                        VeilStickerGifEngine.loadSingleCycleAnimatedDrawable(gf, true, false, false);
                if (animGif != null) {
                    previewIv.setImageDrawable(animGif);
                    boundFileGif = true;
                } else {
                    Bitmap bmp = VeilStickerGifEngine.decodePhotoPreservingAspectRatio(gf, 512);
                    if (bmp != null) {
                        previewIv.setImageBitmap(bmp);
                        boundFileGif = true;
                    }
                }
            }
        }
        if (!boundFileGif) {
            VeilStickerGifEngine.AnimatedGifSceneDrawable scene =
                    new VeilStickerGifEngine.AnimatedGifSceneDrawable(context, gif);
            previewIv.setImageDrawable(scene);
            scene.start();
        }
        previewFrame.addView(previewIv);
        card.addView(previewFrame);

        TextView tvLabel = new TextView(context);
        tvLabel.setText(gif.title + (gif.category != null ? " • " + gif.category : ""));
        tvLabel.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        tvLabel.setTextSize(15f);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setPadding(0, Math.round(10f * dp), 0, Math.round(14f * dp));
        card.addView(tvLabel);

        MaterialButton btnFav = new MaterialButton(context);
        btnFav.setAllCaps(false);
        btnFav.setCornerRadius(Math.round(12f * dp));
        btnFav.setText(isFav ? "★ Remove from Favorites" : "⭐ Add to Favorites");
        btnFav.setBackgroundColor(ContextCompat.getColor(context, isFav ? R.color.colorSurfaceContainerHighest : R.color.colorPrimaryContainer));
        btnFav.setTextColor(ContextCompat.getColor(context, isFav ? R.color.textPrimary : R.color.colorOnPrimaryContainer));
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(
                Math.round(230f * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        favLp.bottomMargin = Math.round(8f * dp);
        btnFav.setLayoutParams(favLp);
        btnFav.setOnClickListener(v -> {
            boolean nowFav = repo.toggleFavoriteGif(gif);
            Toast.makeText(context, nowFav ? "⭐ Added to Favorite GIFs" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if ("⭐ Favorites".equalsIgnoreCase(selectedGifCategory)) {
                loadGifItems(selectedGifCategory);
            }
        });
        card.addView(btnFav);

        MaterialButton btnSend = new MaterialButton(context);
        btnSend.setAllCaps(false);
        btnSend.setCornerRadius(Math.round(12f * dp));
        btnSend.setText("Send GIF");
        btnSend.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));
        btnSend.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary));
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(230f * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
        btnSend.setOnClickListener(v -> {
            dialog.dismiss();
            if (stickerGifListener != null) {
                stickerGifListener.onGifSelected(gif.code);
            } else if (listener != null) {
                listener.onEmojiSelected(gif.centerEmoji);
            }
            dismiss();
        });
        card.addView(btnSend);

        dialog.setContentView(card);
        dialog.show();
    }


    private void bindStickerItemToImageView(
            @NonNull Context context,
            @NonNull StickerRepository.StickerItem item,
            @NonNull ImageView iv,
            boolean autoStartAnimation) {
        String candidatePath = item.localFilePath;
        if (candidatePath == null || candidatePath.isEmpty()) {
            candidatePath = VeilStickerGifEngine.extractFilePathFromCode(item.code);
        }
        boolean isCustomFileSticker = (item.code != null && (item.code.startsWith("[STICKER_FILE:") || item.code.startsWith("[FILE:")))
                || "favorites".equalsIgnoreCase(item.packId)
                || !item.isAnimated;
        if (candidatePath != null && isCustomFileSticker) {
            File f = new File(candidatePath);
            if (f.exists() && f.length() > 16) {
                if (VeilStickerGifEngine.isAnimatedStickerFile(f)) {
                    android.graphics.drawable.Drawable animDrawable =
                            VeilStickerGifEngine.loadSingleCycleAnimatedDrawable(f, autoStartAnimation, false, true);
                    if (animDrawable != null) {
                        iv.setImageDrawable(animDrawable);
                        return;
                    }
                }
                Bitmap bmp = VeilStickerGifEngine.decodePhotoPreservingAspectRatio(f, 512);
                if (bmp == null) {
                    bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                }
                if (bmp != null) {
                    iv.setImageBitmap(VeilStickerGifEngine.stripSolidBorderBackgroundIfPresent(bmp));
                    return;
                }
            }
        }
        VeilStickerGifEngine.AnimatedStickerDrawable drawable =
                new VeilStickerGifEngine.AnimatedStickerDrawable(context, item.toPreset());
        iv.setImageDrawable(drawable);
        if (autoStartAnimation) {
            drawable.start();
        }
    }



    private void loadGifItems(String queryOrCategory) {
        gifItems.clear();
        Context context = getContext();
        if ("⭐ Favorites".equalsIgnoreCase(queryOrCategory) && context != null) {
            List<VeilStickerGifEngine.GifPreset> favs =
                    StickerRepository.getInstance(context).getFavoriteGifs();
            gifItems.addAll(favs);
            if (gifItems.isEmpty()) {
                gridView.setVisibility(View.GONE);
                emptyStateView.setVisibility(View.VISIBLE);
                emptyStateView.setText("⭐ No favorite GIFs yet.\nLong-press any GIF to preview and add it to Favorites!");
            } else {
                emptyStateView.setVisibility(View.GONE);
                gridView.setVisibility(View.VISIBLE);
            }
        } else {
            emptyStateView.setVisibility(View.GONE);
            gridView.setVisibility(View.VISIBLE);
            gifItems.addAll(VeilStickerGifEngine.getBuiltinGifs(queryOrCategory));
        }
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



        ScrollView scroll = new ScrollView(context);

        scroll.setFillViewport(true);



        LinearLayout root = new LinearLayout(context);

        root.setOrientation(LinearLayout.VERTICAL);

        root.setBackgroundResource(R.drawable.bottom_sheet_background);

        int pad = (int) (18 * dp);

        root.setPadding(pad, (int) (12 * dp), pad, (int) (24 * dp));



        // Top drag handle

        View handle = new View(context);

        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (36 * dp), (int) (4 * dp));

        handleLp.gravity = Gravity.CENTER_HORIZONTAL;

        handleLp.bottomMargin = (int) (12 * dp);

        handle.setLayoutParams(handleLp);

        GradientDrawable handleBg = new GradientDrawable();

        handleBg.setColor(ContextCompat.getColor(context, R.color.colorOutline));

        handleBg.setCornerRadius(2f * dp);

        handle.setBackground(handleBg);

        root.addView(handle);



        // Header Title & Subtitle

        TextView title = new TextView(context);

        title.setText("✨ Animated Sticker Studio");

        title.setTextSize(17f);

        title.setTypeface(null, Typeface.BOLD);

        title.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));

        root.addView(title);



        TextView sub = new TextView(context);

        sub.setText("Craft procedural 60fps animated stickers with live physics");

        sub.setTextSize(12.5f);

        sub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        sub.setPadding(0, (int) (2 * dp), 0, (int) (12 * dp));

        root.addView(sub);



        // 1. Live Animated Preview Box (136dp x 136dp centered)

        FrameLayout previewContainer = new FrameLayout(context);

        int previewBoxPx = (int) (136 * dp);

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(previewBoxPx, previewBoxPx);

        pcLp.gravity = Gravity.CENTER_HORIZONTAL;

        pcLp.bottomMargin = (int) (14 * dp);

        previewContainer.setLayoutParams(pcLp);



        GradientDrawable boxBg = new GradientDrawable();

        boxBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

        boxBg.setCornerRadius(20f * dp);

        boxBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

        previewContainer.setBackground(boxBg);



        ImageView ivLivePreview = new ImageView(context);

        ivLivePreview.setLayoutParams(new FrameLayout.LayoutParams(

                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ivLivePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);

        int inPad = (int) (10 * dp);

        ivLivePreview.setPadding(inPad, inPad, inPad, inPad);

        previewContainer.addView(ivLivePreview);

        root.addView(previewContainer);



        // State variables for studio

        final String[] curEmoji = {"🔥"};

        final String[] curTagline = {"LIT"};

        final int[] curStyle = {1}; // 1 = Heartbeat Pulse

        final int[] curCol1 = {0xFFF97316};

        final int[] curCol2 = {0xFFEF4444};



        final Runnable refreshPreview = () -> {

            String label = curTagline[0].trim();

            VeilStickerGifEngine.StickerPreset tempPreset = new VeilStickerGifEngine.StickerPreset(

                    "[PREVIEW]", curEmoji[0], label, curCol1[0], curCol2[0], curStyle[0], 140, 140);

            VeilStickerGifEngine.AnimatedStickerDrawable ad =

                    new VeilStickerGifEngine.AnimatedStickerDrawable(context, tempPreset);

            ivLivePreview.setImageDrawable(ad);

            ad.start();

        };



        // 2. Quick Emoji Selector Strip

        TextView tvEmojiHeader = new TextView(context);

        tvEmojiHeader.setText("CHOOSE EMOJI");

        tvEmojiHeader.setTextSize(11f);

        tvEmojiHeader.setTypeface(null, Typeface.BOLD);

        tvEmojiHeader.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        tvEmojiHeader.setPadding(0, 0, 0, (int) (6 * dp));

        root.addView(tvEmojiHeader);



        HorizontalScrollView emojiScroll = new HorizontalScrollView(context);

        emojiScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout emojiStrip = new LinearLayout(context);

        emojiStrip.setOrientation(LinearLayout.HORIZONTAL);

        emojiStrip.setPadding(0, 0, 0, (int) (10 * dp));



        String[] quickEmojis = {"🔥", "🚀", "💎", "👑", "⚡", "🤯", "😂", "😎", "🐋", "🤖", "💯", "👻", "💖", "🍕", "🎯", "👾", "🏆"};

        final List<TextView> emojiViews = new ArrayList<>();

        for (String qe : quickEmojis) {

            TextView ev = new TextView(context);

            ev.setText(qe);

            ev.setTextSize(22f);

            ev.setGravity(Gravity.CENTER);

            int eSize = (int) (40 * dp);

            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(eSize, eSize);

            elp.rightMargin = (int) (6 * dp);

            ev.setLayoutParams(elp);



            boolean isDef = qe.equals(curEmoji[0]);

            GradientDrawable eBg = new GradientDrawable();

            eBg.setCornerRadius(12f * dp);

            eBg.setColor(ContextCompat.getColor(context, isDef ? R.color.colorPrimaryContainer : R.color.colorSurfaceVariant));

            if (isDef) {

                eBg.setStroke(Math.round(1.5f * dp), ContextCompat.getColor(context, R.color.colorPrimary));

            }

            ev.setBackground(eBg);



            ev.setOnClickListener(v -> {

                curEmoji[0] = qe;

                for (TextView other : emojiViews) {

                    GradientDrawable oBg = new GradientDrawable();

                    oBg.setCornerRadius(12f * dp);

                    boolean sel = other.getText().toString().equals(curEmoji[0]);

                    oBg.setColor(ContextCompat.getColor(context, sel ? R.color.colorPrimaryContainer : R.color.colorSurfaceVariant));

                    if (sel) {

                        oBg.setStroke(Math.round(1.5f * dp), ContextCompat.getColor(context, R.color.colorPrimary));

                    }

                    other.setBackground(oBg);

                }

                refreshPreview.run();

            });

            emojiViews.add(ev);

            emojiStrip.addView(ev);

        }

        emojiScroll.addView(emojiStrip);

        root.addView(emojiScroll);



        // 3. Tagline Input & Preset Chips

        TextView tvTaglineHeader = new TextView(context);

        tvTaglineHeader.setText("TAGLINE / TEXT");

        tvTaglineHeader.setTextSize(11f);

        tvTaglineHeader.setTypeface(null, Typeface.BOLD);

        tvTaglineHeader.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        tvTaglineHeader.setPadding(0, (int) (4 * dp), 0, (int) (6 * dp));

        root.addView(tvTaglineHeader);



        EditText etTagline = new EditText(context);

        etTagline.setHint("e.g. TO THE MOON");

        etTagline.setText(curTagline[0]);

        etTagline.setSingleLine(true);

        etTagline.setTextSize(14f);

        etTagline.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));

        etTagline.setHintTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        GradientDrawable inputBg = new GradientDrawable();

        inputBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

        inputBg.setCornerRadius(12f * dp);

        inputBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

        etTagline.setBackground(inputBg);

        int tPad = (int) (10 * dp);

        etTagline.setPadding(tPad, tPad, tPad, tPad);

        etTagline.addTextChangedListener(new TextWatcher() {

            @Override

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override

            public void onTextChanged(CharSequence s, int start, int before, int count) {

                curTagline[0] = s != null ? s.toString() : "";

                refreshPreview.run();

            }

            @Override

            public void afterTextChanged(Editable s) {}

        });

        root.addView(etTagline);



        // Quick Tagline Preset Chips

        HorizontalScrollView presetScroll = new HorizontalScrollView(context);

        presetScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout presetStrip = new LinearLayout(context);

        presetStrip.setOrientation(LinearLayout.HORIZONTAL);

        presetStrip.setPadding(0, (int) (6 * dp), 0, (int) (12 * dp));



        String[] quickPresets = {"LIT", "TO THE MOON", "LEGEND", "HODL", "CYBER", "VIBES", "GG", "BRAVO"};

        for (String qp : quickPresets) {

            TextView pt = createTabChip(context, qp, false, v -> {

                curTagline[0] = qp;

                etTagline.setText(qp);

                etTagline.setSelection(qp.length());

                refreshPreview.run();

            });

            presetStrip.addView(pt);

        }

        presetScroll.addView(presetStrip);

        root.addView(presetScroll);



        // 4. Animation Physics Selector (Styles 0 to 5)

        TextView tvPhysicsHeader = new TextView(context);

        tvPhysicsHeader.setText("ANIMATION DYNAMICS & PHYSICS");

        tvPhysicsHeader.setTextSize(11f);

        tvPhysicsHeader.setTypeface(null, Typeface.BOLD);

        tvPhysicsHeader.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        tvPhysicsHeader.setPadding(0, 0, 0, (int) (6 * dp));

        root.addView(tvPhysicsHeader);



        HorizontalScrollView physicsScroll = new HorizontalScrollView(context);

        physicsScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout physicsStrip = new LinearLayout(context);

        physicsStrip.setOrientation(LinearLayout.HORIZONTAL);

        physicsStrip.setPadding(0, 0, 0, (int) (12 * dp));



        String[] physicsNames = {"🌊 Float", "💓 Pulse", "🌀 Wobble", "🔄 Spin", "🎉 Hop", "⚡ Glitch"};

        final List<TextView> physicsChips = new ArrayList<>();

        for (int p = 0; p < physicsNames.length; p++) {

            final int pIdx = p;

            TextView pChip = createTabChip(context, physicsNames[p], curStyle[0] == pIdx, v -> {

                curStyle[0] = pIdx;

                for (int j = 0; j < physicsChips.size(); j++) {

                    TextView chip = physicsChips.get(j);

                    boolean active = (j == curStyle[0]);

                    GradientDrawable cBg = new GradientDrawable();

                    cBg.setCornerRadius(18f * dp);

                    if (active) {

                        cBg.setColor(ContextCompat.getColor(context, R.color.colorPrimaryContainer));

                        cBg.setStroke(Math.max(1, Math.round(1.2f * dp)), ContextCompat.getColor(context, R.color.colorPrimary));

                        chip.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimaryContainer));

                    } else {

                        cBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

                        cBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

                        chip.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

                    }

                    chip.setBackground(cBg);

                }

                refreshPreview.run();

            });

            physicsChips.add(pChip);

            physicsStrip.addView(pChip);

        }

        physicsScroll.addView(physicsStrip);

        root.addView(physicsScroll);



        // 5. Gradient Color Palette Selector

        TextView tvPaletteHeader = new TextView(context);

        tvPaletteHeader.setText("COLOR PALETTE");

        tvPaletteHeader.setTextSize(11f);

        tvPaletteHeader.setTypeface(null, Typeface.BOLD);

        tvPaletteHeader.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        tvPaletteHeader.setPadding(0, 0, 0, (int) (6 * dp));

        root.addView(tvPaletteHeader);



        HorizontalScrollView paletteScroll = new HorizontalScrollView(context);

        paletteScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout paletteStrip = new LinearLayout(context);

        paletteStrip.setOrientation(LinearLayout.HORIZONTAL);

        paletteStrip.setPadding(0, 0, 0, (int) (16 * dp));



        String[] paletteNames = {"Sunset Flame", "Neon Matrix", "Cyber Violet", "Royal Gold", "Ocean Cyan", "AMOLED Stealth"};

        int[][] paletteColors = {

                {0xFFF97316, 0xFFEF4444},

                {0xFF10B981, 0xFF06B6D4},

                {0xFF6366F1, 0xFFEC4899},

                {0xFFF59E0B, 0xFFD97706},

                {0xFF0EA5E9, 0xFF2563EB},

                {0xFF475569, 0xFF0F172A}

        };

        final List<TextView> paletteChips = new ArrayList<>();

        for (int c = 0; c < paletteNames.length; c++) {

            final int cIdx = c;

            TextView palChip = createTabChip(context, paletteNames[c], cIdx == 0, v -> {

                curCol1[0] = paletteColors[cIdx][0];

                curCol2[0] = paletteColors[cIdx][1];

                for (int j = 0; j < paletteChips.size(); j++) {

                    TextView chip = paletteChips.get(j);

                    boolean active = (j == cIdx);

                    GradientDrawable cBg = new GradientDrawable();

                    cBg.setCornerRadius(18f * dp);

                    if (active) {

                        cBg.setColor(ContextCompat.getColor(context, R.color.colorPrimaryContainer));

                        cBg.setStroke(Math.max(1, Math.round(1.2f * dp)), ContextCompat.getColor(context, R.color.colorPrimary));

                        chip.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimaryContainer));

                    } else {

                        cBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

                        cBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

                        chip.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

                    }

                    chip.setBackground(cBg);

                }

                refreshPreview.run();

            });

            paletteChips.add(palChip);

            paletteStrip.addView(palChip);

        }

        paletteScroll.addView(paletteStrip);

        root.addView(paletteScroll);



        // 6. Action Button: "Save & Send Sticker"

        MaterialButton btnSave = new MaterialButton(context);

        btnSave.setText("✨ Save & Send Animated Sticker");

        btnSave.setAllCaps(false);

        btnSave.setTextSize(14f);

        btnSave.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimary));

        btnSave.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));

        btnSave.setCornerRadius((int) (14 * dp));

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(

                LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * dp));

        btnSave.setLayoutParams(btnLp);

        btnSave.setOnClickListener(v -> {

            String emoji = curEmoji[0].trim();

            if (emoji.isEmpty()) emoji = "✨";

            String label = curTagline[0].trim();

            StickerRepository.StickerItem createdItem = StickerRepository.getInstance(context)

                    .createAndSaveCustomAnimatedSticker(emoji, label, curCol1[0], curCol2[0], curStyle[0]);



            Toast.makeText(context, "Saved animated sticker to My Stickers!", Toast.LENGTH_SHORT).show();

            sheet.dismiss();



            populateStickerSubTabs();

            loadStickerItems();



            dispatchStickerSelection(context, createdItem);

        });

        root.addView(btnSave);



        scroll.addView(root);

        sheet.setContentView(scroll);

        sheet.show();



        // Initial preview render

        refreshPreview.run();

    }



    private TextView createTabChip(Context context, String label, boolean selected, View.OnClickListener onClick) {

        float dp = getResources().getDisplayMetrics().density;

        TextView chip = new TextView(context);

        chip.setText(label);

        chip.setTextSize(12.5f);

        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);

        chip.setPadding((int) (12 * dp), (int) (7 * dp), (int) (12 * dp), (int) (7 * dp));



        GradientDrawable chipBg = new GradientDrawable();

        chipBg.setCornerRadius(18f * dp);

        if (selected) {

            chipBg.setColor(ContextCompat.getColor(context, R.color.colorPrimaryContainer));

            chipBg.setStroke(Math.max(1, Math.round(1.2f * dp)), ContextCompat.getColor(context, R.color.colorPrimary));

            chip.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimaryContainer));

        } else {

            chipBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));

            chipBg.setStroke(Math.max(1, Math.round(1f * dp)), ContextCompat.getColor(context, R.color.colorOutline));

            chip.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

        }

        chip.setBackground(chipBg);



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

                StickerRepository.StickerItem item = stickerItems.get(position);

                ImageView iv = new ImageView(context);

                int size = (int) (74 * dp);

                iv.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));

                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

                bindStickerItemToImageView(context, item, iv, false);

                return iv;

            } else {

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
                String gifFilePath = VeilStickerGifEngine.extractFilePathFromCode(gif.code);
                boolean boundFileGif = false;
                if (gifFilePath != null) {
                    File gf = new File(gifFilePath);
                    if (gf.exists() && gf.length() > 16) {
                        android.graphics.drawable.Drawable animGif =
                                VeilStickerGifEngine.loadSingleCycleAnimatedDrawable(gf, true, false, false);
                        if (animGif != null) {
                            iv.setImageDrawable(animGif);
                            boundFileGif = true;
                        } else {
                            Bitmap bmp = VeilStickerGifEngine.decodePhotoPreservingAspectRatio(gf, 512);
                            if (bmp != null) {
                                iv.setImageBitmap(bmp);
                                boundFileGif = true;
                            }
                        }
                    }
                }
                if (!boundFileGif) {
                    VeilStickerGifEngine.AnimatedGifSceneDrawable scene =
                            new VeilStickerGifEngine.AnimatedGifSceneDrawable(context, gif);
                    iv.setImageDrawable(scene);
                }
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

