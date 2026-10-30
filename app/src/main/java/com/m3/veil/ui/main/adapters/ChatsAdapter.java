package com.m3.veil.ui.main.adapters;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.PersonaManager;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.views.VeilDialogCell;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * High-performance 120 FPS Chats Adapter using Telegram-grade custom Canvas VeilDialogCell,
 * plus Dual-Level Global Search Sections (Section 1: Chats, Section 2: FTS4 Messages).
 */
public class ChatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_CHAT = 0;
    private static final int VIEW_TYPE_SECTION_HEADER = 1;
    private static final int VIEW_TYPE_MESSAGE_RESULT = 2;

    private List<Chat> chatList;
    private final List<EncryptedChatDatabase.FtsMessageSearchResult> messageSearchResults = new ArrayList<>();
    private boolean isSearchActive = false;
    private String activeSearchQuery = "";
    private final OnChatClickListener listener;
    private String currentUserId;

    public interface OnChatClickListener {
        void onChatClick(Chat chat);
        void onChatLongClick(Chat chat);
        default void onMessageSearchResultClick(EncryptedChatDatabase.FtsMessageSearchResult result) {}
    }

    public ChatsAdapter(List<Chat> chatList, OnChatClickListener listener) {
        this.chatList = chatList != null ? chatList : new ArrayList<>();
        this.listener = listener;
        refreshCurrentUserId();
        setHasStableIds(true);
    }

    public void refreshCurrentUserId() {
        try {
            Context ctx = com.m3.veil.VeilApplication.getInstance();
            if (ctx != null) {
                this.currentUserId = PersonaManager.getInstance(ctx).getActivePersonaPeerId();
                return;
            }
        } catch (Exception ignored) {}
        String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(com.m3.veil.VeilApplication.getInstance());
        this.currentUserId = uid != null ? uid : "";
    }

    public void setMessageSearchResults(String query, List<EncryptedChatDatabase.FtsMessageSearchResult> results) {
        this.activeSearchQuery = query != null ? query.trim() : "";
        this.isSearchActive = !this.activeSearchQuery.isEmpty();
        this.messageSearchResults.clear();
        if (results != null && this.isSearchActive) {
            this.messageSearchResults.addAll(results);
        }
        notifyDataSetChanged();
    }

    public boolean hasAnyDisplayItems() {
        int chats = chatList != null ? chatList.size() : 0;
        int msgs = isSearchActive ? messageSearchResults.size() : 0;
        return (chats + msgs) > 0;
    }

    private int getChatsHeaderOffset() {
        return (isSearchActive && chatList != null && !chatList.isEmpty()) ? 1 : 0;
    }

    private int getChatsCount() {
        return chatList != null ? chatList.size() : 0;
    }

    private int getMessagesHeaderPosition() {
        if (!isSearchActive || messageSearchResults.isEmpty()) return -1;
        return getChatsHeaderOffset() + getChatsCount();
    }

    @Override
    public int getItemViewType(int position) {
        if (!isSearchActive) {
            return VIEW_TYPE_CHAT;
        }
        int chatsHeader = getChatsHeaderOffset();
        int chatsCount = getChatsCount();
        if (chatsHeader == 1 && position == 0) {
            return VIEW_TYPE_SECTION_HEADER;
        }
        if (position >= chatsHeader && position < chatsHeader + chatsCount) {
            return VIEW_TYPE_CHAT;
        }
        int msgHeaderPos = getMessagesHeaderPosition();
        if (msgHeaderPos >= 0 && position == msgHeaderPos) {
            return VIEW_TYPE_SECTION_HEADER;
        }
        return VIEW_TYPE_MESSAGE_RESULT;
    }

    @Override
    public long getItemId(int position) {
        int type = getItemViewType(position);
        if (type == VIEW_TYPE_SECTION_HEADER) {
            return position == 0 ? -1001L : -1002L;
        }
        if (type == VIEW_TYPE_CHAT) {
            Chat c = getChatAt(position);
            return (c != null && c.getId() != null) ? (long) c.getId().hashCode() : position;
        }
        EncryptedChatDatabase.FtsMessageSearchResult res = getMessageResultAt(position);
        if (res != null && res.message != null && res.message.getId() != null) {
            return 0x4000000000000000L | (long) res.message.getId().hashCode();
        }
        return position;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        float dp = context.getResources().getDisplayMetrics().density;

        if (viewType == VIEW_TYPE_SECTION_HEADER) {
            TextView tv = new TextView(context);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            tv.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (6 * dp));
            tv.setTextSize(12f);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setLetterSpacing(0.08f);
            tv.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
            return new SectionHeaderViewHolder(tv);
        }

        if (viewType == VIEW_TYPE_MESSAGE_RESULT) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            row.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
            row.setBackgroundResource(R.drawable.bg_search_input);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) row.getLayoutParams();
            lp.setMargins((int) (12 * dp), (int) (4 * dp), (int) (12 * dp), (int) (4 * dp));

            LinearLayout topLine = new LinearLayout(context);
            topLine.setOrientation(LinearLayout.HORIZONTAL);
            topLine.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvChatName = new TextView(context);
            tvChatName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tvChatName.setTextSize(14.5f);
            tvChatName.setTypeface(null, Typeface.BOLD);
            tvChatName.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
            tvChatName.setSingleLine(true);
            tvChatName.setEllipsize(TextUtils.TruncateAt.END);

            TextView tvUnreadDot = new TextView(context);
            tvUnreadDot.setText("● UNREAD");
            tvUnreadDot.setTextSize(10f);
            tvUnreadDot.setTypeface(null, Typeface.BOLD);
            tvUnreadDot.setTextColor(0xFF10B981);
            tvUnreadDot.setPadding((int) (6 * dp), 0, (int) (6 * dp), 0);

            TextView tvTimestamp = new TextView(context);
            tvTimestamp.setTextSize(11.5f);
            tvTimestamp.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));

            topLine.addView(tvChatName);
            topLine.addView(tvUnreadDot);
            topLine.addView(tvTimestamp);

            TextView tvSnippet = new TextView(context);
            tvSnippet.setTextSize(13.5f);
            tvSnippet.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            tvSnippet.setMaxLines(2);
            tvSnippet.setEllipsize(TextUtils.TruncateAt.END);
            tvSnippet.setPadding(0, (int) (4 * dp), 0, 0);

            row.addView(topLine);
            row.addView(tvSnippet);
            return new MessageSearchResultViewHolder(row, tvChatName, tvUnreadDot, tvTimestamp, tvSnippet);
        }

        VeilDialogCell cell = new VeilDialogCell(context);
        cell.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return new ChatViewHolder(cell);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == VIEW_TYPE_SECTION_HEADER) {
            SectionHeaderViewHolder sh = (SectionHeaderViewHolder) holder;
            if (position == 0 && getChatsHeaderOffset() == 1) {
                sh.tvHeader.setText("SECTION 1 • CHATS (" + getChatsCount() + ")");
            } else {
                sh.tvHeader.setText("SECTION 2 • MESSAGES (" + messageSearchResults.size() + ")");
            }
            return;
        }

        if (type == VIEW_TYPE_MESSAGE_RESULT) {
            EncryptedChatDatabase.FtsMessageSearchResult res = getMessageResultAt(position);
            if (res == null) return;
            MessageSearchResultViewHolder mh = (MessageSearchResultViewHolder) holder;
            mh.bind(res, activeSearchQuery);
            mh.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onMessageSearchResultClick(res);
                }
            });
            return;
        }

        Chat chat = getChatAt(position);
        if (chat == null) return;
        ChatViewHolder ch = (ChatViewHolder) holder;
        ch.bind(chat, currentUserId);

        ch.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChatClick(chat);
            }
        });

        ch.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onChatLongClick(chat);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        int chatsCount = getChatsCount();
        if (!isSearchActive) {
            return chatsCount;
        }
        int total = getChatsHeaderOffset() + chatsCount;
        if (!messageSearchResults.isEmpty()) {
            total += 1 + messageSearchResults.size();
        }
        return total;
    }

    public Chat getChatAt(int position) {
        if (chatList == null) return null;
        int chatIndex = position - getChatsHeaderOffset();
        if (chatIndex < 0 || chatIndex >= chatList.size()) return null;
        return chatList.get(chatIndex);
    }

    private EncryptedChatDatabase.FtsMessageSearchResult getMessageResultAt(int position) {
        int msgHeaderPos = getMessagesHeaderPosition();
        if (msgHeaderPos < 0) return null;
        int msgIndex = position - (msgHeaderPos + 1);
        if (msgIndex < 0 || msgIndex >= messageSearchResults.size()) return null;
        return messageSearchResults.get(msgIndex);
    }

    public void updateChats(List<Chat> newChats) {
        refreshCurrentUserId();
        if (newChats != null) {
            newChats.sort((c1, c2) -> {
                if (c1.isPinned() != c2.isPinned()) {
                    return c1.isPinned() ? -1 : 1;
                }
                if (c1.getLastMessageTime() == null && c2.getLastMessageTime() == null) return 0;
                if (c1.getLastMessageTime() == null) return 1;
                if (c2.getLastMessageTime() == null) return -1;
                return c2.getLastMessageTime().compareTo(c1.getLastMessageTime());
            });
        }
        this.chatList = newChats != null ? newChats : new ArrayList<>();
        notifyDataSetChanged();
    }

    static class SectionHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvHeader;

        SectionHeaderViewHolder(@NonNull TextView itemView) {
            super(itemView);
            this.tvHeader = itemView;
        }
    }

    static class MessageSearchResultViewHolder extends RecyclerView.ViewHolder {
        final TextView tvChatName;
        final TextView tvUnreadDot;
        final TextView tvTimestamp;
        final TextView tvSnippet;
        private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

        MessageSearchResultViewHolder(@NonNull View itemView, TextView tvChatName,
                                      TextView tvUnreadDot, TextView tvTimestamp, TextView tvSnippet) {
            super(itemView);
            this.tvChatName = tvChatName;
            this.tvUnreadDot = tvUnreadDot;
            this.tvTimestamp = tvTimestamp;
            this.tvSnippet = tvSnippet;
        }

        void bind(EncryptedChatDatabase.FtsMessageSearchResult result, String query) {
            String title = !TextUtils.isEmpty(result.chatName) ? result.chatName : "Encrypted Chat";
            if (result.message != null && !TextUtils.isEmpty(result.message.getSenderName())) {
                title = title + " • " + result.message.getSenderName();
            }
            tvChatName.setText(title);
            tvUnreadDot.setVisibility(result.unread ? View.VISIBLE : View.GONE);

            long ts = result.timestampMillis;
            if (ts <= 0 && result.message != null && result.message.getTimestamp() != null) {
                ts = result.message.getTimestamp().toDate().getTime();
            }
            tvTimestamp.setText(ts > 0 ? TIME_FMT.format(new Date(ts)) : "");

            String rawSnippet = !TextUtils.isEmpty(result.snippet)
                    ? result.snippet
                    : (result.message != null && result.message.getText() != null ? result.message.getText() : "");
            if (rawSnippet.contains("<b>")) {
                Spanned spanned = Html.fromHtml(rawSnippet, Html.FROM_HTML_MODE_LEGACY);
                tvSnippet.setText(spanned);
            } else if (!TextUtils.isEmpty(query)) {
                SpannableStringBuilder ssb = new SpannableStringBuilder(rawSnippet);
                String lowerText = rawSnippet.toLowerCase(Locale.ROOT);
                String lowerQ = query.toLowerCase(Locale.ROOT);
                int idx = lowerText.indexOf(lowerQ);
                if (idx >= 0) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + lowerQ.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new ForegroundColorSpan(0xFF6366F1), idx, idx + lowerQ.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                tvSnippet.setText(ssb);
            } else {
                tvSnippet.setText(rawSnippet);
            }
        }
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        private final VeilDialogCell dialogCell;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            this.dialogCell = (VeilDialogCell) itemView;
        }

        public void bind(Chat chat, String currentUserId) {
            dialogCell.setChat(chat, currentUserId);
        }
    }
}
