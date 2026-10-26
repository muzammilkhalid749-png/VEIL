package com.m3.veil.ui.chat;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.User;
import com.m3.veil.ui.main.adapters.ChatsAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SearchActivity extends AppCompatActivity implements ChatsAdapter.OnChatClickListener {

    private RecyclerView resultsRecyclerView;
    private ChatsAdapter chatsAdapter;
    private List<Chat> allChats;
    private List<Chat> filteredChats;
    private EditText searchEditText;
    private ProgressBar loadingBar;
    private View emptyState;

    private FirebaseFirestore db;
    private String currentUserId;
    private String activeCategory = "all"; // all, unread, media, links, docs, voice, groups
    private final Map<String, String> matchedMessageIdByChatId = new HashMap<>();
    private final List<TextView> categoryChips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_search);

        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }
        try {
            db = FirebaseFirestore.getInstance();
        } catch (Throwable ignored) {}

        initializeViews();
        handleWindowInsets();
        setupRecyclerView();
        loadAllChats();
        setupSearchListener();
    }

    private void initializeViews() {
        resultsRecyclerView = findViewById(R.id.resultsRecyclerView);
        searchEditText = findViewById(R.id.searchEditText);
        loadingBar = findViewById(R.id.loadingBar);
        emptyState = findViewById(R.id.emptyState);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // Inject WhatsApp/Telegram-style Universal Search Filter Chips bar above resultsRecyclerView
        if (resultsRecyclerView != null && resultsRecyclerView.getParent() instanceof LinearLayout) {
            LinearLayout root = (LinearLayout) resultsRecyclerView.getParent();
            float dp = getResources().getDisplayMetrics().density;

            HorizontalScrollView chipScroll = new HorizontalScrollView(this);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipScroll.setPadding((int) (14 * dp), (int) (6 * dp), (int) (14 * dp), (int) (8 * dp));

            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);

            String[][] cats = {
                    {"all", "🔍 All"},
                    {"unread", "🔵 Unread"},
                    {"media", "📷 Photos & Video"},
                    {"links", "🔗 Links"},
                    {"docs", "📄 Documents"},
                    {"voice", "🎙️ Voice"},
                    {"groups", "👥 Groups"}
            };

            for (String[] cat : cats) {
                final String key = cat[0];
                TextView chip = new TextView(this);
                chip.setTag(key);
                chip.setText(cat[1]);
                chip.setTextSize(12.5f);
                chip.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
                boolean sel = key.equals(activeCategory);
                chip.setBackgroundResource(sel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                chip.setTextColor(sel ? Color.WHITE : ContextCompat.getColor(this, R.color.textPrimary));
                chip.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = (int) (8 * dp);
                chip.setLayoutParams(lp);

                chip.setOnClickListener(v -> {
                    activeCategory = key;
                    updateCategoryChipStyles();
                    filter(searchEditText != null && searchEditText.getText() != null
                            ? searchEditText.getText().toString().trim() : "");
                });
                categoryChips.add(chip);
                chipRow.addView(chip);
            }

            chipScroll.addView(chipRow);
            int rvIdx = root.indexOfChild(resultsRecyclerView);
            root.addView(chipScroll, Math.max(0, rvIdx));
        }
    }

    private void updateCategoryChipStyles() {
        for (TextView chip : categoryChips) {
            boolean sel = activeCategory.equals(chip.getTag());
            chip.setBackgroundResource(sel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(sel ? Color.WHITE : ContextCompat.getColor(this, R.color.textPrimary));
            chip.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.search_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomPad = Math.max(systemBars.bottom, ime.bottom);
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPad);
            return insets;
        });
    }

    private void setupRecyclerView() {
        allChats = new ArrayList<>();
        filteredChats = new ArrayList<>();
        chatsAdapter = new ChatsAdapter(filteredChats, this);
        resultsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        resultsRecyclerView.setAdapter(chatsAdapter);
    }

    private void loadAllChats() {
        List<Chat> local = EncryptedChatDatabase.getInstance(this).getAllChats();
        allChats.clear();
        if (local != null && !local.isEmpty()) {
            for (Chat c : local) {
                if (c != null && !c.isSecret()) {
                    allChats.add(c);
                }
            }
        }
        filter(searchEditText != null && searchEditText.getText() != null
                ? searchEditText.getText().toString().trim() : "");
    }

    private void setupSearchListener() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void filter(String query) {
        filteredChats.clear();
        matchedMessageIdByChatId.clear();
        String lower = query != null ? query.toLowerCase(Locale.ROOT) : "";

        // 1. Check category-specific message filters (media, links, docs, voice) or text query
        boolean isMessageTypeFilter = "media".equals(activeCategory)
                || "links".equals(activeCategory)
                || "docs".equals(activeCategory)
                || "voice".equals(activeCategory);

        for (Chat chat : allChats) {
            if ("unread".equals(activeCategory) && chat.getUnreadCount() <= 0) continue;
            if ("groups".equals(activeCategory) && !chat.isGroup()) continue;

            if (isMessageTypeFilter) {
                List<Message> chatMsgs = EncryptedChatDatabase.getInstance(this).getMessages(chat.getId());
                if (chatMsgs != null) {
                    for (int i = chatMsgs.size() - 1; i >= 0; i--) {
                        Message m = chatMsgs.get(i);
                        if (m == null) continue;
                        String type = m.getType() != null ? m.getType() : "text";
                        String text = m.getText() != null ? m.getText() : "";
                        boolean typeMatch = false;
                        if ("media".equals(activeCategory) && ("image".equals(type) || "video".equals(type))) typeMatch = true;
                        else if ("docs".equals(activeCategory) && "document".equals(type)) typeMatch = true;
                        else if ("voice".equals(activeCategory) && "voice".equals(type)) typeMatch = true;
                        else if ("links".equals(activeCategory) && (text.contains("http://") || text.contains("https://") || text.contains("veil://"))) typeMatch = true;

                        boolean queryMatch = lower.isEmpty() || text.toLowerCase(Locale.ROOT).contains(lower)
                                || (chat.getName() != null && chat.getName().toLowerCase(Locale.ROOT).contains(lower));

                        if (typeMatch && queryMatch) {
                            Chat copy = cloneChatWithSnippet(chat, formatMessageMatchPreview(m));
                            filteredChats.add(copy);
                            matchedMessageIdByChatId.put(chat.getId(), m.getId());
                            break;
                        }
                    }
                }
            } else if (lower.isEmpty()) {
                filteredChats.add(chat);
            } else {
                boolean matchName = chat.getName() != null && chat.getName().toLowerCase(Locale.ROOT).contains(lower);
                boolean matchMsg = chat.getLastMessage() != null && chat.getLastMessage().toLowerCase(Locale.ROOT).contains(lower);
                if (matchName || matchMsg) {
                    filteredChats.add(chat);
                }
            }
        }

        // 2. Universal full-text search across all decrypted SQLCipher message histories
        if (!lower.isEmpty() && !isMessageTypeFilter) {
            List<Message> matchingMsgs = EncryptedChatDatabase.getInstance(this).searchMessages(query, 40);
            for (Message m : matchingMsgs) {
                String cId = m.getChatId();
                if ((cId == null || cId.isEmpty()) && m.getReceiverId() != null) {
                    Chat groupChat = EncryptedChatDatabase.getInstance(this).getChat(m.getReceiverId());
                    if (groupChat != null && groupChat.isGroup()) {
                        cId = m.getReceiverId();
                    } else if (m.getSenderId() != null) {
                        cId = com.m3.veil.webrtc.WebRtcManager.getChatId(m.getSenderId(), m.getReceiverId());
                    }
                }
                if (cId != null && !cId.isEmpty()) {
                    matchedMessageIdByChatId.put(cId, m.getId());
                    Chat existingInFiltered = null;
                    for (Chat c : filteredChats) {
                        if (cId.equals(c.getId())) {
                            existingInFiltered = c;
                            break;
                        }
                    }
                    String snippet = "💬 Match: \"" + (m.getText() != null ? m.getText() : "") + "\"";
                    if (existingInFiltered != null) {
                        existingInFiltered.setLastMessage(snippet);
                    } else {
                        Chat c = EncryptedChatDatabase.getInstance(this).getChat(cId);
                        if (c != null && !c.isSecret()) {
                            if ("unread".equals(activeCategory) && c.getUnreadCount() <= 0) continue;
                            if ("groups".equals(activeCategory) && !c.isGroup()) continue;
                            filteredChats.add(cloneChatWithSnippet(c, snippet));
                        }
                    }
                }
            }
        }

        chatsAdapter.notifyDataSetChanged();
        updateEmptyState();

        // 3. Ephemeral user directory lookup via Sovereign Nostr & SQLCipher Directory Engine
        if (!lower.isEmpty() && "all".equals(activeCategory)) {
            String cleanQuery = lower.startsWith("@") ? lower.substring(1) : lower;
            loadingBar.setVisibility(View.VISIBLE);
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).searchUsersByUsername(cleanQuery, users -> {
                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    boolean addedNew = false;
                    if (users != null) {
                        for (User user : users) {
                            if (user != null && user.getUserId() != null && !user.getUserId().equals(currentUserId)) {
                                boolean exists = false;
                                for (Chat c : filteredChats) {
                                    if (c.getParticipants() != null && c.getParticipants().contains(user.getUserId())) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    List<String> ids = Arrays.asList(currentUserId, user.getUserId());
                                    Collections.sort(ids);
                                    String chatId = ids.get(0) + "_" + ids.get(1);

                                    Chat fakeChat = new Chat(
                                            chatId,
                                            user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                                            "Tap to start P2P E2EE chat",
                                            null,
                                            user.getPhotoUrl(),
                                            0,
                                            false,
                                            false,
                                            "direct",
                                            Arrays.asList(currentUserId, user.getUserId())
                                    );
                                    filteredChats.add(fakeChat);
                                    addedNew = true;
                                }
                            }
                        }
                    }
                    if (addedNew) {
                        chatsAdapter.notifyDataSetChanged();
                        updateEmptyState();
                    }
                });
            });
        }
    }

    private Chat cloneChatWithSnippet(Chat src, String customSnippet) {
        Chat copy = new Chat(
                src.getId(),
                src.getName(),
                customSnippet,
                src.getLastMessageTime(),
                src.getAvatar(),
                src.getUnreadCount(),
                src.isGroup(),
                src.isArchived(),
                src.getType(),
                src.getParticipants()
        );
        copy.setPinned(src.isPinned());
        copy.setMuted(src.isMuted());
        copy.setSecret(src.isSecret());
        copy.setChatLockPin(src.getChatLockPin());
        copy.setVanishTimer(src.getVanishTimer());
        copy.setVanishMode(src.getVanishMode());
        return copy;
    }

    private String formatMessageMatchPreview(Message m) {
        String type = m.getType() != null ? m.getType() : "text";
        String txt = m.getText() != null ? m.getText() : "";
        if ("image".equals(type)) return "📷 Photo" + (txt.isEmpty() ? "" : ": " + txt);
        if ("video".equals(type)) return "🎥 Video" + (txt.isEmpty() ? "" : ": " + txt);
        if ("voice".equals(type)) return "🎙️ Voice Note";
        if ("document".equals(type)) return "📄 Document: " + txt;
        return "🔗 " + txt;
    }

    private void updateEmptyState() {
        if (filteredChats.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            resultsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            resultsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onChatClick(Chat chat) {
        if (chat == null) return;
        if (chat.getChatLockPin() != null && !chat.getChatLockPin().isEmpty()) {
            promptChatLockPin(chat);
            return;
        }
        openChat(chat, false);
    }

    private void promptChatLockPin(Chat chat) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_pin_auth);
        sheet.setCancelable(false);

        TextView tvTitle = sheet.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = sheet.findViewById(R.id.tvPinDialogSubtitle);
        com.google.android.material.textfield.TextInputEditText etPin = sheet.findViewById(R.id.etPin);
        MaterialButton btnCancel = sheet.findViewById(R.id.btnCancelPin);
        MaterialButton btnUnlock = sheet.findViewById(R.id.btnConfirmPin);

        if (tvTitle != null) tvTitle.setText("🔒 Unlock " + (chat.getName() != null ? chat.getName() : "Chat"));
        if (tvSubtitle != null) tvSubtitle.setText("This conversation is encrypted & PIN protected");

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }

        if (btnUnlock != null && etPin != null) {
            btnUnlock.setOnClickListener(v -> {
                String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
                if (chat.getChatLockPin().equals(entered)) {
                    sheet.dismiss();
                    openChat(chat, true);
                } else {
                    Toast.makeText(this, "Incorrect Chat PIN", Toast.LENGTH_SHORT).show();
                    etPin.setText("");
                }
            });
        }
        sheet.show();
    }

    private void openChat(Chat chat, boolean isPinVerified) {
        Chat existing = EncryptedChatDatabase.getInstance(this).getChat(chat.getId());
        if (existing == null) {
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chat.getId());
        intent.putExtra("recipientName", chat.getName());
        intent.putExtra("isPinVerified", isPinVerified);

        String recipientId = null;
        if (chat.getParticipants() != null) {
            for (String id : chat.getParticipants()) {
                if (id != null && !id.equals(currentUserId)) {
                    recipientId = id;
                    break;
                }
            }
        }
        if ((recipientId == null || recipientId.trim().isEmpty()) && chat.getId() != null && !chat.isGroup()) {
            String[] parts = chat.getId().split("_");
            if (parts.length == 2) {
                recipientId = parts[0].equals(currentUserId) ? parts[1] : parts[0];
            }
        }
        if (recipientId != null && !recipientId.trim().isEmpty()) {
            intent.putExtra("recipientId", recipientId);
        }

        String matchedMsgId = matchedMessageIdByChatId.get(chat.getId());
        if (matchedMsgId != null && !matchedMsgId.isEmpty()) {
            intent.putExtra("scrollToMessageId", matchedMsgId);
        }

        startActivity(intent);
        finish();
    }

    @Override
    public void onChatLongClick(Chat chat) {
        if (chat == null || chat.getId() == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_chat_actions, null);

        TextView tvTitle = sheetView.findViewById(R.id.tvSheetChatTitle);
        TextView tvSubtitle = sheetView.findViewById(R.id.tvSheetChatSubtitle);
        TextView actionMarkReadUnread = sheetView.findViewById(R.id.actionMarkReadUnread);
        TextView actionPin = sheetView.findViewById(R.id.actionPin);
        TextView actionMute = sheetView.findViewById(R.id.actionMute);
        TextView actionVanishTimer = sheetView.findViewById(R.id.actionVanishTimer);
        TextView actionLockChat = sheetView.findViewById(R.id.actionLockChat);
        TextView actionSecretVault = sheetView.findViewById(R.id.actionSecretVault);
        TextView actionArchive = sheetView.findViewById(R.id.actionArchive);
        TextView actionClearChat = sheetView.findViewById(R.id.actionClearChat);
        TextView actionDelete = sheetView.findViewById(R.id.actionDelete);

        if (tvTitle != null) tvTitle.setText(chat.getName() != null ? chat.getName() : "@Anonymous");
        if (tvSubtitle != null) {
            tvSubtitle.setText("👁️ Tap here to Peek Last 6 Messages without marking read");
            tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            tvSubtitle.setOnClickListener(v -> {
                sheet.dismiss();
                showChatPreviewPeekDialog(chat);
            });
        }

        if (actionVanishTimer != null) actionVanishTimer.setVisibility(View.GONE);
        if (actionLockChat != null) actionLockChat.setVisibility(View.GONE);
        if (actionClearChat != null) actionClearChat.setVisibility(View.GONE);

        if (actionMarkReadUnread != null) {
            actionMarkReadUnread.setText(chat.getUnreadCount() > 0 ? "✓✓  Mark as read" : "🔵  Mark as unread");
            actionMarkReadUnread.setOnClickListener(v -> {
                sheet.dismiss();
                int next = chat.getUnreadCount() > 0 ? 0 : 1;
                EncryptedChatDatabase.getInstance(this).updateChatUnreadCount(chat.getId(), next);
                loadAllChats();
            });
        }

        if (actionPin != null) {
            actionPin.setText(chat.isPinned() ? "📌  Unpin chat" : "📌  Pin to top");
            actionPin.setOnClickListener(v -> {
                sheet.dismiss();
                boolean next = !chat.isPinned();
                EncryptedChatDatabase.getInstance(this).updateChatPinStatus(chat.getId(), next);
                loadAllChats();
                Toast.makeText(this, next ? "Chat pinned" : "Chat unpinned", Toast.LENGTH_SHORT).show();
            });
        }

        if (actionMute != null) {
            actionMute.setText(chat.isMuted() ? "🔔  Unmute notifications" : "🔕  Mute notifications");
            actionMute.setOnClickListener(v -> {
                sheet.dismiss();
                boolean next = !chat.isMuted();
                EncryptedChatDatabase.getInstance(this).updateChatMuteStatus(chat.getId(), next);
                loadAllChats();
                Toast.makeText(this, next ? "Notifications muted" : "Notifications unmuted", Toast.LENGTH_SHORT).show();
            });
        }

        if (actionSecretVault != null) {
            actionSecretVault.setText(chat.isSecret() ? "👁️  Move out of Secret Vault" : "🕵️  Move to Secret Vault");
            actionSecretVault.setOnClickListener(v -> {
                sheet.dismiss();
                boolean next = !chat.isSecret();
                EncryptedChatDatabase.getInstance(this).updateChatSecretStatus(chat.getId(), next);
                loadAllChats();
                Toast.makeText(this, next ? "Moved to Secret Vault" : "Moved back to main chats", Toast.LENGTH_SHORT).show();
            });
        }

        if (actionArchive != null) {
            actionArchive.setText(chat.isArchived() ? "📤  Unarchive chat" : "📥  Archive chat");
            actionArchive.setOnClickListener(v -> {
                sheet.dismiss();
                boolean next = !chat.isArchived();
                EncryptedChatDatabase.getInstance(this).updateChatArchiveStatus(chat.getId(), next);
                loadAllChats();
                Toast.makeText(this, next ? "Chat archived" : "Chat unarchived", Toast.LENGTH_SHORT).show();
            });
        }

        if (actionDelete != null) {
            actionDelete.setOnClickListener(v -> {
                sheet.dismiss();
                EncryptedChatDatabase.getInstance(this).deleteChat(chat.getId());
                loadAllChats();
                Toast.makeText(this, "Chat deleted from vault", Toast.LENGTH_SHORT).show();
            });
        }

        sheet.setContentView(sheetView);
        sheet.show();
    }

    private void showChatPreviewPeekDialog(Chat chat) {
        BottomSheetDialog peekSheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, (int) (16 * dp), pad, pad);

        TextView tvHeader = new TextView(this);
        tvHeader.setText("👁️ Stealth Peek: " + (chat.getName() != null ? chat.getName() : "Chat"));
        tvHeader.setTextSize(17.5f);
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(tvHeader);

        TextView tvSub = new TextView(this);
        tvSub.setText("Zero read-receipts sent • Unread badge preserved");
        tvSub.setTextSize(12f);
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        tvSub.setPadding(0, (int) (2 * dp), 0, (int) (12 * dp));
        root.addView(tvSub);

        List<Message> msgs = EncryptedChatDatabase.getInstance(this).getMessages(chat.getId());
        if (msgs == null || msgs.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("No messages in this conversation yet.");
            tvEmpty.setTextSize(13.5f);
            tvEmpty.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            root.addView(tvEmpty);
        } else {
            int start = Math.max(0, msgs.size() - 6);
            for (int i = start; i < msgs.size(); i++) {
                Message m = msgs.get(i);
                boolean isMe = m.getSenderId() != null && m.getSenderId().equals(currentUserId);
                TextView bubble = new TextView(this);
                String prefix = isMe ? "You: " : (chat.getName() != null ? chat.getName() + ": " : "Peer: ");
                bubble.setText(prefix + (m.getText() != null ? m.getText() : "[" + m.getType() + "]"));
                bubble.setTextSize(13.5f);
                bubble.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
                bubble.setBackgroundResource(R.drawable.bg_search_input);
                int bPad = (int) (10 * dp);
                bubble.setPadding(bPad, (int) (8 * dp), bPad, (int) (8 * dp));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int) (6 * dp);
                bubble.setLayoutParams(lp);
                root.addView(bubble);
            }
        }

        MaterialButton btnOpen = new MaterialButton(this);
        btnOpen.setText("Open Full Conversation");
        btnOpen.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (10 * dp);
        btnOpen.setLayoutParams(btnLp);
        btnOpen.setOnClickListener(v -> {
            peekSheet.dismiss();
            onChatClick(chat);
        });
        root.addView(btnOpen);

        peekSheet.setContentView(root);
        peekSheet.show();
    }
}

