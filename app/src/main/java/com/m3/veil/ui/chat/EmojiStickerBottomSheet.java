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

        // 3. Custom Sticker Creator Chip
        TextView btnCustom = createTabChip(context, "➕ Custom", false, v -> showCreateCustomStickerDialog(context));
        packStrip.addView(btnCustom);

        // 4. Store / Marketplace Button
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
                String val = getItem(position);
                textView.setTextSize(val != null && val.length() > 4 ? 14 : 26);
                textView.setGravity(Gravity.CENTER);
                return textView;
            }
        };
        gridView.setAdapter(adapter);

        gridView.setOnItemClickListener((parent, view1, position, id) -> {
            if (listener != null && position < currentItems.size()) {
                String chosen = currentItems.get(position);
                saveRecentEmoji(context, chosen);
                listener.onEmojiSelected(chosen);
            }
            dismiss();
        });

        root.addView(gridView);
        loadStandardEmojis();
        return root;
    }

    private void saveRecentEmoji(Context context, String item) {
        if (context == null || item == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("VeilEmojiPrefs", Context.MODE_PRIVATE);
        String existing = prefs.getString("recent_emojis", "");
        java.util.List<String> list = new ArrayList<>();
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
        title.setText("➕ Create Custom Sticker");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (10 * dp));
        root.addView(title);

        android.widget.EditText etSticker = new android.widget.EditText(context);
        etSticker.setHint("e.g. 🔥 ZERO TRACE 🔥 or 🕵️‍♂️");
        etSticker.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        etSticker.setHintTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        etSticker.setBackgroundResource(R.drawable.bg_search_input);
        int inPad = (int) (12 * dp);
        etSticker.setPadding(inPad, inPad, inPad, inPad);
        root.addView(etSticker);

        com.google.android.material.button.MaterialButton btnSave = new com.google.android.material.button.MaterialButton(context);
        btnSave.setText("Save & Send Custom Sticker");
        btnSave.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (14 * dp);
        btnSave.setLayoutParams(lp);
        btnSave.setOnClickListener(v -> {
            String custom = etSticker.getText().toString().trim();
            if (!custom.isEmpty()) {
                saveRecentEmoji(context, custom);
                sheet.dismiss();
                if (listener != null) listener.onEmojiSelected(custom);
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

    private void refreshTabs(LinearLayout strip, List<VeilFeatureManager.StickerPack> packs) {
        Context context = requireContext();
        for (int i = 0; i < strip.getChildCount() - 2; i++) {
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
        if (getContext() != null) {
            String rec = getContext().getSharedPreferences("VeilEmojiPrefs", Context.MODE_PRIVATE).getString("recent_emojis", "");
            if (rec != null && !rec.isEmpty()) {
                for (String s : rec.split(";;")) {
                    if (!s.isEmpty() && !currentItems.contains(s)) {
                        currentItems.add(s);
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
                0x1F512, 0x1F6E1, 0x26A1, 0x1F575, 0x1F47B
        };
        for (int codePoint : unicodeEmojis) {
            String em = new String(Character.toChars(codePoint));
            if (!currentItems.contains(em)) {
                currentItems.add(em);
            }
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
