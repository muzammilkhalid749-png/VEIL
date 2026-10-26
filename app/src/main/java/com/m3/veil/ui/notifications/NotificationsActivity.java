package com.m3.veil.ui.notifications;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.CallRecord;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.settings.NotificationSettingsActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;

public class NotificationsActivity extends AppCompatActivity {

    public enum NotificationType {
        MESSAGE,
        MENTION,
        REPLY,
        MISSED_CALL,
        SYSTEM
    }

    public static class NotificationItem {
        public String id;
        public String chatId;
        public String title;
        public String body;
        public String avatarUrl;
        public long timestamp;
        public NotificationType type;
        public boolean isUnread;
        public boolean isGroup;
        public boolean isChannel;
        public boolean isLocked;

        public NotificationItem(String id, String chatId, String title, String body, String avatarUrl,
                                long timestamp, NotificationType type, boolean isUnread, boolean isGroup,
                                boolean isChannel, boolean isLocked) {
            this.id = id;
            this.chatId = chatId;
            this.title = title;
            this.body = body;
            this.avatarUrl = avatarUrl;
            this.timestamp = timestamp;
            this.type = type;
            this.isUnread = isUnread;
            this.isGroup = isGroup;
            this.isChannel = isChannel;
            this.isLocked = isLocked;
        }
    }

    private TabLayout tabLayout;
    private RecyclerView rvNotifications;
    private LinearLayout layoutEmptyState;
    private TextView tvEmptyTitle;
    private TextView tvEmptySubtitle;
    private TextView btnMarkAllRead;

    private NotificationAdapter adapter;
    private final List<NotificationItem> allNotifications = new ArrayList<>();
    private final List<NotificationItem> filteredNotifications = new ArrayList<>();

    private EncryptedChatDatabase chatDb;
    private VeilFeatureManager featureManager;
    private String currentUserId;
    private String activePersonaName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_notifications);
        com.m3.veil.utils.UiAnimator.enableEdgeToEdge(this, findViewById(android.R.id.content));

        chatDb = EncryptedChatDatabase.getInstance(this);
        featureManager = VeilFeatureManager.getInstance(this);
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null) currentUserId = "me";
        activePersonaName = featureManager.getActivePersona().getDisplayName();

        bindViews();
        setupListeners();
        loadNotifications();
    }

    private void bindViews() {
        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        ImageView btnSettings = findViewById(R.id.btnNotificationSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, NotificationSettingsActivity.class);
            startActivity(intent);
        });

        btnMarkAllRead = findViewById(R.id.btnMarkAllRead);
        tabLayout = findViewById(R.id.tabLayoutNotifications);
        rvNotifications = findViewById(R.id.rvNotifications);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);

        rvNotifications.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NotificationAdapter(filteredNotifications, this::onNotificationClick);
        rvNotifications.setAdapter(adapter);
    }

    private void setupListeners() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applyFilter(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnMarkAllRead.setOnClickListener(v -> {
            List<Chat> chats = chatDb.getAllChats(true);
            for (Chat c : chats) {
                if (c.getUnreadCount() > 0) {
                    chatDb.markMessagesAsRead(c.getId());
                }
            }
            for (NotificationItem item : allNotifications) {
                item.isUnread = false;
            }
            adapter.notifyDataSetChanged();
            Toast.makeText(this, "All notifications marked as read", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadNotifications() {
        allNotifications.clear();

        List<Chat> chats = chatDb.getAllChats(true);
        Map<String, Chat> chatMap = new HashMap<>();
        for (Chat c : chats) {
            chatMap.put(c.getId(), c);
        }

        // 1. Gather recent unread & mentioned messages
        for (Chat chat : chats) {
            List<Message> messages = chatDb.getMessages(chat.getId(), 25, 0);
            boolean isChatLocked = (chat.getChatLockPin() != null && !chat.getChatLockPin().isEmpty()) || chat.isSecret();

            for (Message msg : messages) {
                // Skip outgoing
                if (currentUserId.equals(msg.getSenderId())) continue;

                NotificationType type = NotificationType.MESSAGE;
                boolean isMention = false;
                boolean isReply = false;

                String text = msg.getText() != null ? msg.getText() : "";
                if (activePersonaName != null && text.toLowerCase(Locale.ROOT).contains(activePersonaName.toLowerCase(Locale.ROOT))) {
                    type = NotificationType.MENTION;
                    isMention = true;
                } else if (msg.getReplyToMessageId() != null && !msg.getReplyToMessageId().isEmpty()) {
                    type = NotificationType.REPLY;
                    isReply = true;
                }

                if (!msg.isRead() || isMention || isReply) {
                    String title = isChatLocked ? "🔒 Encrypted Contact" : chat.getName();
                    String body = isChatLocked ? "Encrypted message content hidden" :
                            (isMention ? "Mentioned you: " + text :
                                    (isReply ? "Replied to you: " + text : text));

                    long msgTime = msg.getTimestamp() != null ? msg.getTimestamp().toDate().getTime() : System.currentTimeMillis();

                    allNotifications.add(new NotificationItem(
                            msg.getId(),
                            chat.getId(),
                            title,
                            body,
                            isChatLocked ? null : chat.getAvatar(),
                            msgTime,
                            type,
                            !msg.isRead(),
                            chat.isGroup(),
                            chat.isChannel(),
                            isChatLocked
                    ));
                }
            }
        }

        // 2. Gather Call history (missed calls)
        try {
            List<CallRecord> calls = chatDb.getAllCallRecords();
            if (calls != null) {
                for (CallRecord cr : calls) {
                    boolean isMissed = "missed".equalsIgnoreCase(cr.getStatus()) || (!"outgoing".equalsIgnoreCase(cr.getStatus()) && cr.getDurationSeconds() == 0);
                    if (isMissed) {
                        long callTime = cr.getTimestamp() != null ? cr.getTimestamp().toDate().getTime() : System.currentTimeMillis();
                        allNotifications.add(new NotificationItem(
                                cr.getId(),
                                cr.getPeerId(),
                                "Missed " + (cr.getCallType() != null && cr.getCallType().contains("video") ? "Video" : "Voice") + " Call",
                                "From " + (cr.getPeerName() != null ? cr.getPeerName() : "Veil Contact"),
                                null,
                                callTime,
                                NotificationType.MISSED_CALL,
                                true,
                                false,
                                false,
                                false
                        ));
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Sort descending by timestamp
        Collections.sort(allNotifications, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        applyFilter(tabLayout.getSelectedTabPosition());
    }

    private void applyFilter(int tabPosition) {
        filteredNotifications.clear();

        for (NotificationItem item : allNotifications) {
            switch (tabPosition) {
                case 1: // Mentions
                    if (item.type == NotificationType.MENTION) filteredNotifications.add(item);
                    break;
                case 2: // Replies
                    if (item.type == NotificationType.REPLY) filteredNotifications.add(item);
                    break;
                case 3: // Calls & System
                    if (item.type == NotificationType.MISSED_CALL || item.type == NotificationType.SYSTEM) {
                        filteredNotifications.add(item);
                    }
                    break;
                case 0: // All
                default:
                    filteredNotifications.add(item);
                    break;
            }
        }

        adapter.notifyDataSetChanged();

        if (filteredNotifications.isEmpty()) {
            layoutEmptyState.setVisibility(View.VISIBLE);
            rvNotifications.setVisibility(View.GONE);
            if (tabPosition == 1) {
                tvEmptyTitle.setText("No Mentions");
                tvEmptySubtitle.setText("When someone tags your @" + activePersonaName + " in a group, it appears here.");
            } else if (tabPosition == 2) {
                tvEmptyTitle.setText("No Replies");
                tvEmptySubtitle.setText("Direct replies to your messages will appear here.");
            } else if (tabPosition == 3) {
                tvEmptyTitle.setText("No Missed Calls");
                tvEmptySubtitle.setText("Incoming voice and video call logs will appear here.");
            } else {
                tvEmptyTitle.setText("No Notifications");
                tvEmptySubtitle.setText("Mentions, replies, missed calls and zero-knowledge alerts will appear here.");
            }
        } else {
            layoutEmptyState.setVisibility(View.GONE);
            rvNotifications.setVisibility(View.VISIBLE);
        }
    }

    private void onNotificationClick(NotificationItem item) {
        if (item.chatId != null && !item.chatId.isEmpty()) {
            Intent intent = new Intent(this, ChatActivity.class);
            if (item.type == NotificationType.MISSED_CALL && !item.isGroup && !item.isChannel) {
                String peerId = item.chatId;
                String resolvedChatId = peerId.contains("_")
                        ? peerId
                        : com.m3.veil.webrtc.WebRtcManager.getChatId(currentUserId, peerId);
                intent.putExtra("chatId", resolvedChatId);
                intent.putExtra("recipientId", peerId);
                if (item.body != null && item.body.startsWith("From ")) {
                    intent.putExtra("recipientName", item.body.substring(5).trim());
                }
            } else {
                intent.putExtra("chatId", item.chatId);
                if (!item.isGroup && !item.isChannel && item.chatId.contains("_")) {
                    String[] parts = item.chatId.split("_");
                    if (parts.length == 2) {
                        String peerId = parts[0].equals(currentUserId) ? parts[1] : parts[0];
                        intent.putExtra("recipientId", peerId);
                    }
                }
            }
            intent.putExtra("isGroup", item.isGroup);
            intent.putExtra("isChannel", item.isChannel);
            startActivity(intent);
        }
    }

    // ==========================================
    // NOTIFICATION ADAPTER
    // ==========================================

    private static class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.VH> {
        private final List<NotificationItem> list;
        private final OnItemClickListener listener;
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());

        interface OnItemClickListener {
            void onItemClick(NotificationItem item);
        }

        NotificationAdapter(List<NotificationItem> list, OnItemClickListener listener) {
            this.list = list;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notification_entry, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            NotificationItem item = list.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvBody.setText(item.body);

            long now = System.currentTimeMillis();
            if (now - item.timestamp < 24 * 3600 * 1000) {
                holder.tvTime.setText(timeFormat.format(new Date(item.timestamp)));
            } else {
                holder.tvTime.setText(dateFormat.format(new Date(item.timestamp)));
            }

            holder.viewUnreadDot.setVisibility(item.isUnread ? View.VISIBLE : View.GONE);

            // Icon & badge
            if (item.type == NotificationType.MISSED_CALL) {
                holder.ivBadge.setImageResource(R.drawable.ic_call);
                holder.ivAvatar.setImageResource(R.drawable.ic_call);
            } else if (item.type == NotificationType.MENTION) {
                holder.ivBadge.setImageResource(R.drawable.ic_mention);
            } else if (item.type == NotificationType.REPLY) {
                holder.ivBadge.setImageResource(R.drawable.ic_reply);
            } else {
                holder.ivBadge.setImageResource(R.drawable.ic_chat);
            }

            if (item.avatarUrl != null && !item.avatarUrl.isEmpty() && !item.isLocked) {
                if (item.avatarUrl.startsWith("content://") || item.avatarUrl.startsWith("file://")) {
                    holder.ivAvatar.setImageURI(android.net.Uri.parse(item.avatarUrl));
                } else {
                    try {
                        byte[] decoded = android.util.Base64.decode(item.avatarUrl, android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                        if (bmp != null) holder.ivAvatar.setImageBitmap(bmp);
                        else holder.ivAvatar.setImageResource(item.isGroup ? R.drawable.ic_group : (item.isChannel ? R.drawable.ic_channel : R.drawable.ic_person));
                    } catch (Exception e) {
                        holder.ivAvatar.setImageResource(item.isGroup ? R.drawable.ic_group : (item.isChannel ? R.drawable.ic_channel : R.drawable.ic_person));
                    }
                }
            } else {
                holder.ivAvatar.setImageResource(item.isGroup ? R.drawable.ic_group : (item.isChannel ? R.drawable.ic_channel : R.drawable.ic_person));
            }

            holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            ImageView ivBadge;
            TextView tvTitle;
            TextView tvTime;
            TextView tvBody;
            View viewUnreadDot;

            VH(@NonNull View itemView) {
                super(itemView);
                ivAvatar = itemView.findViewById(R.id.ivNotifAvatar);
                ivBadge = itemView.findViewById(R.id.ivNotifBadge);
                tvTitle = itemView.findViewById(R.id.tvNotifTitle);
                tvTime = itemView.findViewById(R.id.tvNotifTime);
                tvBody = itemView.findViewById(R.id.tvNotifBody);
                viewUnreadDot = itemView.findViewById(R.id.viewUnreadDot);
            }
        }
    }
}
