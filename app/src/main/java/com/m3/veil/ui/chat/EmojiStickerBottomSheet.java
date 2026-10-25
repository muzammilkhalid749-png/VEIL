package com.m3.veil.ui.chat;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.ui.chat.sheets.StickerMarketplaceBottomSheet;

import java.util.ArrayList;
import java.util.List;

public class EmojiStickerBottomSheet extends BottomSheetDialogFragment {

    public interface OnEmojiSelectedListener {
        void onEmojiSelected(String emoji);
    }

    private OnEmojiSelectedListener listener;
    private int selectedPackIndex = 0; // 0 = standard emojis, 1+ = installed packs
    private GridView gridView;
    private final ArrayList<String> currentItems = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    public void setOnEmojiSelectedListener(OnEmojiSelectedListener listener) {
        this.listener = listener;
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

        // Drag Handle
        View handle = new View(context);
        int handleW = (int) (36 * dp);
        int handleH = (int) (4 * dp);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (10 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Pack Strip
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout packStrip = new LinearLayout(context);
        packStrip.setOrientation(LinearLayout.HORIZONTAL);
        packStrip.setPadding(0, 0, 0, (int) (10 * dp));

        VeilFeatureManager fm = VeilFeatureManager.getInstance(context);
        List<VeilFeatureManager.StickerPack> installedPacks = fm.getInstalledStickerPacks();

        // 1. Standard Emoji Tab
        TextView tabEmoji = createTabChip(context, "😀 Emojis", selectedPackIndex == 0, v -> {
            selectedPackIndex = 0;
            loadStandardEmojis();
            refreshTabs(packStrip, installedPacks);
        });
        packStrip.addView(tabEmoji);

        // 2. Installed Pack Tabs
        for (int i = 0; i < installedPacks.size(); i++) {
            final int pIdx = i + 1;
            final VeilFeatureManager.StickerPack pack = installedPacks.get(i);
            TextView pTab = createTabChip(context, pack.name, selectedPackIndex == pIdx, v -> {
                selectedPackIndex = pIdx;
                loadPackStickers(pack);
                refreshTabs(packStrip, installedPacks);
            });
            packStrip.addView(pTab);
        }

        // 3. Store / Marketplace Button
        TextView btnStore = createTabChip(context, "🛍️ Store", false, v -> {
            dismiss();
            StickerMarketplaceBottomSheet store = new StickerMarketplaceBottomSheet();
            store.show(getParentFragmentManager(), "StickerMarketplaceBottomSheet");
        });
        btnStore.setBackgroundResource(R.drawable.bg_filter_chip);
        btnStore.setTextColor(ContextCompat.getColor(context, R.color.veil_accent));
        packStrip.addView(btnStore);

        scroll.addView(packStrip);
        root.addView(scroll);

        // Grid View
        gridView = new GridView(context);
        gridView.setNumColumns(5);
        gridView.setVerticalSpacing((int) (16 * dp));
        gridView.setHorizontalSpacing((int) (16 * dp));
        gridView.setPadding(0, (int) (6 * dp), 0, (int) (16 * dp));
        gridView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (280 * dp)));

        adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, currentItems) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView textView = (TextView) super.getView(position, convertView, parent);
                textView.setTextSize(28);
                textView.setGravity(Gravity.CENTER);
                return textView;
            }
        };
        gridView.setAdapter(adapter);

        gridView.setOnItemClickListener((parent, view1, position, id) -> {
            if (listener != null && position < currentItems.size()) {
                listener.onEmojiSelected(currentItems.get(position));
            }
            dismiss();
        });

        root.addView(gridView);
        loadStandardEmojis();
        return root;
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

    private void refreshTabs(LinearLayout strip, List<VeilFeatureManager.StickerPack> packs) {
        Context context = requireContext();
        for (int i = 0; i < strip.getChildCount() - 1; i++) {
            View child = strip.getChildAt(i);
            if (child instanceof TextView) {
                boolean isSelected = (i == selectedPackIndex);
                child.setBackgroundResource(isSelected ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                ((TextView) child).setTextColor(isSelected ? Color.WHITE : ContextCompat.getColor(context, R.color.textPrimary));
            }
        }
    }

    private void loadStandardEmojis() {
        currentItems.clear();
        int[] unicodeEmojis = {
                0x1F600, 0x1F603, 0x1F604, 0x1F601, 0x1F606,
                0x1F605, 0x1F602, 0x1F923, 0x1F60A, 0x1F609,
                0x1F607, 0x1F642, 0x1F643, 0x1F60D, 0x1F618,
                0x1F617, 0x1F619, 0x1F61A, 0x1F60B, 0x1F914,
                0x1F910, 0x1F928, 0x1F610, 0x1F611, 0x1F929,
                0x1F973, 0x1F970, 0x1F92A, 0x1F974, 0x1F60E,
                0x2B62, 0x2B60, 0x2B06, 0x2B07, 0x25B6,
                0x25C0, 0x25B2, 0x25BC, 0x25A0, 0x25A1
        };
        for (int codePoint : unicodeEmojis) {
            currentItems.add(new String(Character.toChars(codePoint)));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadPackStickers(VeilFeatureManager.StickerPack pack) {
        currentItems.clear();
        if (pack != null && pack.stickers != null) {
            currentItems.addAll(pack.stickers);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }
}
