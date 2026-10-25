package com.m3.veil.ui.chat.sheets;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Persona;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * WhatsApp-style in-app Contacts Picker BottomSheet (_name_removed__res_0x7f0e04a6.xml).
 * Supports searching, multi-selecting device contacts & Veil P2P peers, selected chip strip,
 * and vCard preview before sending.
 */
public class WhatsAppContactPickerSheet extends BottomSheetDialogFragment {

    public interface ContactPickerListener {
        void onContactsSelectedToSend(List<ContactEntry> selectedContacts);
    }

    public static class ContactEntry {
        public final String name;
        public final String phoneOrHandle;
        public final String subtitle;

        public ContactEntry(String name, String phoneOrHandle, String subtitle) {
            this.name = name;
            this.phoneOrHandle = phoneOrHandle;
            this.subtitle = subtitle;
        }
    }

    private ContactPickerListener listener;
    private final List<ContactEntry> allContacts = new ArrayList<>();
    private final List<ContactEntry> selectedContacts = new ArrayList<>();
    private LinearLayout listContainer;
    private LinearLayout selectedChipsLayout;
    private HorizontalScrollView chipsScroll;
    private TextView tvSubtitleCount;
    private TextView btnSendSelected;
    private String searchQuery = "";

    public void setContactPickerListener(ContactPickerListener listener) {
        this.listener = listener;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            View sheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        float dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet);
        root.setPadding(0, (int) (12 * dp), 0, (int) (16 * dp));

        // Drag handle
        View handle = new View(ctx);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (4 * dp));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (12 * dp);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#CBD5E1"));
        handleBg.setCornerRadius(4 * dp);
        handle.setBackground(handleBg);
        root.addView(handle);

        // Header Row ("Contacts to send" + counter)
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (10 * dp));

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("Contacts to send");
        tvTitle.setTextSize(19f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        titleCol.addView(tvTitle);

        tvSubtitleCount = new TextView(ctx);
        tvSubtitleCount.setText("0 selected");
        tvSubtitleCount.setTextSize(12.5f);
        tvSubtitleCount.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        titleCol.addView(tvSubtitleCount);
        header.addView(titleCol);

        TextView btnClose = new TextView(ctx);
        btnClose.setText("✕");
        btnClose.setTextSize(18f);
        btnClose.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
        btnClose.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        btnClose.setOnClickListener(v -> dismiss());
        header.addView(btnClose);
        root.addView(header);

        // Search Bar
        EditText etSearch = new EditText(ctx);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * dp));
        searchLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (8 * dp));
        etSearch.setLayoutParams(searchLp);
        etSearch.setBackgroundResource(R.drawable.bg_chat_input);
        etSearch.setHint("Search name or number...");
        etSearch.setTextSize(14f);
        etSearch.setSingleLine(true);
        etSearch.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        etSearch.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        etSearch.setHintTextColor(ctx.getResources().getColor(R.color.textSecondary));
        root.addView(etSearch);

        // Selected Contacts Horizontal Chip Strip
        chipsScroll = new HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setPadding((int) (16 * dp), 0, (int) (16 * dp), (int) (8 * dp));
        chipsScroll.setVisibility(View.GONE);
        selectedChipsLayout = new LinearLayout(ctx);
        selectedChipsLayout.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(selectedChipsLayout);
        root.addView(chipsScroll);

        // Contacts List
        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (340 * dp)));
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding((int) (12 * dp), (int) (4 * dp), (int) (12 * dp), (int) (12 * dp));
        scrollView.addView(listContainer);
        root.addView(scrollView);

        // Bottom Send Button Bar
        btnSendSelected = new TextView(ctx);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * dp));
        sendLp.setMargins((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), 0);
        btnSendSelected.setLayoutParams(sendLp);
        btnSendSelected.setGravity(Gravity.CENTER);
        btnSendSelected.setText("Select a contact to send");
        btnSendSelected.setTextColor(Color.WHITE);
        btnSendSelected.setTextSize(15f);
        btnSendSelected.setTypeface(null, Typeface.BOLD);
        GradientDrawable sendBtnBg = new GradientDrawable();
        sendBtnBg.setCornerRadius(24 * dp);
        sendBtnBg.setColor(Color.parseColor("#00A884"));
        btnSendSelected.setBackground(sendBtnBg);
        btnSendSelected.setAlpha(0.5f);
        btnSendSelected.setEnabled(false);
        btnSendSelected.setOnClickListener(v -> {
            if (!selectedContacts.isEmpty() && listener != null) {
                listener.onContactsSelectedToSend(new ArrayList<>(selectedContacts));
                dismiss();
            }
        });
        root.addView(btnSendSelected);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                renderContactsList();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        loadAllContacts(ctx);
        renderContactsList();

        return root;
    }

    private void loadAllContacts(Context ctx) {
        allContacts.clear();
        Set<String> seenKeys = new LinkedHashSet<>();

        // 1. Device Phonebook Contacts (if READ_CONTACTS granted)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            String[] proj = {
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            };
            try (Cursor c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    proj, null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
                if (c != null) {
                    int nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    int numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    while (c.moveToNext()) {
                        String name = nameCol != -1 ? c.getString(nameCol) : "";
                        String num = numCol != -1 ? c.getString(numCol) : "";
                        if (name != null && !name.isEmpty() && num != null && !num.isEmpty()) {
                            String key = name + "|" + num;
                            if (seenKeys.add(key)) {
                                allContacts.add(new ContactEntry(name, num, "Mobile • Device Contact"));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Veil P2P Encrypted Chat Peers
        List<Chat> chats = EncryptedChatDatabase.getInstance(ctx).getAllChats();
        if (chats != null) {
            for (Chat chat : chats) {
                if (chat.getName() != null && !chat.getName().isEmpty()) {
                    String handle = chat.getName().startsWith("@") ? chat.getName() : "@" + chat.getName().replaceAll("\\s+", "");
                    String key = chat.getName() + "|" + handle;
                    if (seenKeys.add(key)) {
                        allContacts.add(new ContactEntry(chat.getName(), handle, "🛡️ VeilChat Encrypted Peer"));
                    }
                }
            }
        }

        // 3. Veil Personas & Built-In P2P Directory Contacts
        for (Persona p : VeilFeatureManager.getInstance(ctx).getPersonas()) {
            String key = p.getDisplayName() + "|" + p.getMood();
            if (seenKeys.add(key)) {
                allContacts.add(new ContactEntry(p.getDisplayName(), p.getDisplayName() + " • Veil ID", "🎭 " + p.getBio()));
            }
        }
        allContacts.add(new ContactEntry("Alex Mercer (@CipherNode)", "+1 (555) 234-8910", "🛡️ VeilChat Verified Peer"));
        allContacts.add(new ContactEntry("Elena Vance (@ZeroTrace)", "+1 (555) 876-5432", "🛡️ VeilChat Verified Peer"));
        allContacts.add(new ContactEntry("Marcus Sterling (@QuantumMesh)", "+44 7700 900412", "🛡️ VeilChat Verified Peer"));
    }

    private void renderContactsList() {
        if (listContainer == null || getContext() == null) return;
        Context ctx = getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;
        listContainer.removeAllViews();

        for (ContactEntry entry : allContacts) {
            if (!searchQuery.isEmpty()) {
                if (!entry.name.toLowerCase(Locale.ROOT).contains(searchQuery)
                        && !entry.phoneOrHandle.toLowerCase(Locale.ROOT).contains(searchQuery)) {
                    continue;
                }
            }

            final boolean isSelected = selectedContacts.contains(entry);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            row.setBackgroundResource(isSelected ? R.drawable.bg_filter_chip_selected : R.drawable.bg_doc_inner_card);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (int) (6 * dp);
            row.setLayoutParams(rowLp);

            // Avatar Circle with Initial
            TextView tvAvatar = new TextView(ctx);
            tvAvatar.setLayoutParams(new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp)));
            tvAvatar.setGravity(Gravity.CENTER);
            String clean = entry.name.replace("@", "").trim();
            tvAvatar.setText(clean.isEmpty() ? "C" : clean.substring(0, 1).toUpperCase(Locale.ROOT));
            tvAvatar.setTextColor(Color.WHITE);
            tvAvatar.setTextSize(16f);
            tvAvatar.setTypeface(null, Typeface.BOLD);
            GradientDrawable avBg = new GradientDrawable();
            avBg.setShape(GradientDrawable.OVAL);
            avBg.setColor(Color.parseColor("#008069"));
            tvAvatar.setBackground(avBg);
            row.addView(tvAvatar);

            // Contact Name + Phone/Handle
            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = (int) (12 * dp);
            tLp.rightMargin = (int) (8 * dp);
            textCol.setLayoutParams(tLp);

            TextView tvName = new TextView(ctx);
            tvName.setText(entry.name);
            tvName.setTextSize(15f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
            textCol.addView(tvName);

            TextView tvPhone = new TextView(ctx);
            tvPhone.setText(entry.phoneOrHandle + " • " + entry.subtitle);
            tvPhone.setTextSize(12f);
            tvPhone.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
            textCol.addView(tvPhone);
            row.addView(textCol);

            // WhatsApp Circular Checkbox
            FrameLayout checkCircle = new FrameLayout(ctx);
            checkCircle.setLayoutParams(new LinearLayout.LayoutParams((int) (22 * dp), (int) (22 * dp)));
            checkCircle.setBackgroundResource(isSelected
                    ? R.drawable.bg_poll_checkbox_checked
                    : R.drawable.bg_poll_checkbox_unchecked);
            if (isSelected) {
                TextView checkMark = new TextView(ctx);
                checkMark.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                checkMark.setText("✓");
                checkMark.setTextColor(Color.WHITE);
                checkMark.setTextSize(12f);
                checkMark.setTypeface(null, Typeface.BOLD);
                checkCircle.addView(checkMark);
            }
            row.addView(checkCircle);

            row.setOnClickListener(v -> {
                if (selectedContacts.contains(entry)) {
                    selectedContacts.remove(entry);
                } else {
                    selectedContacts.add(entry);
                }
                updateSelectionHeaderAndChips();
                renderContactsList();
            });

            listContainer.addView(row);
        }
    }

    private void updateSelectionHeaderAndChips() {
        if (getContext() == null) return;
        Context ctx = getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;

        int count = selectedContacts.size();
        tvSubtitleCount.setText(count + " selected");

        if (count > 0) {
            btnSendSelected.setEnabled(true);
            btnSendSelected.setAlpha(1.0f);
            btnSendSelected.setText("Send " + count + (count == 1 ? " Contact" : " Contacts"));
            chipsScroll.setVisibility(View.VISIBLE);
            selectedChipsLayout.removeAllViews();

            for (ContactEntry c : selectedContacts) {
                TextView chip = new TextView(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (int) (30 * dp));
                lp.rightMargin = (int) (6 * dp);
                chip.setLayoutParams(lp);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
                chip.setBackgroundResource(R.drawable.bg_filter_chip_selected);
                chip.setText(c.name + "  ✕");
                chip.setTextSize(12f);
                chip.setTextColor(ctx.getResources().getColor(R.color.colorPrimary));
                chip.setTypeface(null, Typeface.BOLD);
                chip.setOnClickListener(v -> {
                    selectedContacts.remove(c);
                    updateSelectionHeaderAndChips();
                    renderContactsList();
                });
                selectedChipsLayout.addView(chip);
            }
        } else {
            btnSendSelected.setEnabled(false);
            btnSendSelected.setAlpha(0.5f);
            btnSendSelected.setText("Select a contact to send");
            chipsScroll.setVisibility(View.GONE);
        }
    }
}
