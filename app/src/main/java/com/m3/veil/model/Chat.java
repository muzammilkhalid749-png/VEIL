package com.m3.veil.model;

import java.util.Date;

public class Chat {
    private String id;
    private String name;
    private String lastMessage;
    private com.google.firebase.Timestamp lastMessageTime;
    private String avatar;
    private int unreadCount;
    private long vanishTimer; // 0 = off, else millis
    private boolean isGroup;
    private boolean isOnline;
    private String type; // "direct", "group", "channel"
    private java.util.List<String> participants;
    private String adminId;
    private java.util.List<String> adminIds;
    private String description;
    private boolean isPublic;
    private String handle;
    private String vanishMode;
    private boolean isBlocked;
    private String blockedBy;
    private boolean isPinned;
    private boolean isMuted;
    private boolean isArchived;
    private boolean isSecret; // Hidden in Secret Inbox Vault
    private String chatLockPin;
    private String wallpaperColor;
    private int slowModeSec;
    private String groupRules;
    private String pinnedMessageId;
    private String pinnedMessageText;
    private int trustScore; // 0..100 for Friendship Level & Fog of Anonymity
    private long groupExpiresAt; // 0 = permanent, >0 = temporary group auto-expire

    // Last message delivery/read metadata for WhatsApp-style chat list ticks & media icons
    private String lastMessageSenderId;
    private String lastMessageType;
    private boolean lastMessageDelivered;
    private boolean lastMessageRead;
    private boolean lastMessagePending;
    private boolean isTyping;

    public Chat() {}

    public Chat(String id, String name, String lastMessage, com.google.firebase.Timestamp lastMessageTime,
                String avatar, int unreadCount, boolean isGroup, boolean isOnline, String type, java.util.List<String> participants) {
        this.id = id;
        this.name = name;
        this.lastMessage = lastMessage;
        this.lastMessageTime = lastMessageTime;
        this.avatar = avatar;
        this.unreadCount = unreadCount;
        this.isGroup = isGroup;
        this.isOnline = isOnline;
        this.type = type;
        this.participants = participants;
    }

    // Getters and Setters
    public String getId() { return id; }
    public String getChatId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public com.google.firebase.Timestamp getLastMessageTime() { return lastMessageTime; }
    public void setLastMessageTime(com.google.firebase.Timestamp lastMessageTime) { this.lastMessageTime = lastMessageTime; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    public long getVanishTimer() { return vanishTimer; }
    public void setVanishTimer(long vanishTimer) { this.vanishTimer = vanishTimer; }

    public boolean isGroup() { return isGroup; }
    public void setGroup(boolean group) { isGroup = group; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public java.util.List<String> getParticipants() { return participants; }
    public void setParticipants(java.util.List<String> participants) { this.participants = participants; }

    public String getAdminId() { return adminId; }
    public void setAdminId(String adminId) {
        this.adminId = adminId;
        if (adminId != null && !adminId.isEmpty()) {
            addAdmin(adminId);
        }
    }

    public java.util.List<String> getAdminIds() {
        if (adminIds == null) adminIds = new java.util.ArrayList<>();
        return adminIds;
    }

    public void setAdminIds(java.util.List<String> adminIds) {
        this.adminIds = adminIds;
    }

    public void addAdmin(String userId) {
        if (userId == null || userId.isEmpty()) return;
        if (adminIds == null) adminIds = new java.util.ArrayList<>();
        if (!adminIds.contains(userId)) {
            adminIds.add(userId);
        }
        if (adminId == null || adminId.isEmpty()) {
            adminId = userId;
        }
    }

    public void removeAdmin(String userId) {
        if (userId == null || adminIds == null) return;
        adminIds.remove(userId);
        if (userId.equals(adminId)) {
            adminId = adminIds.isEmpty() ? null : adminIds.get(0);
        }
    }

    public boolean isAdmin(String userId) {
        if (userId == null || userId.isEmpty()) return false;
        if (userId.equals(adminId)) return true;
        if (adminIds != null && adminIds.contains(userId)) return true;
        // Fallback if no explicit admin was recorded: last participant in NewGroupActivity is creator
        if ((adminId == null || adminId.isEmpty()) && (adminIds == null || adminIds.isEmpty())) {
            if (participants != null && !participants.isEmpty()) {
                return userId.equals(participants.get(participants.size() - 1)) || userId.equals(participants.get(0));
            }
        }
        return false;
    }

    public boolean isChannel() {
        return "channel".equalsIgnoreCase(type) || (id != null && id.startsWith("channel_"));
    }

    public boolean isGroupChat() {
        return isGroup || "group".equalsIgnoreCase(type) || isChannel()
                || (id != null && (id.startsWith("group_") || id.startsWith("lounge_")));
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getVanishMode() { return vanishMode; }
    public void setVanishMode(String vanishMode) { this.vanishMode = vanishMode; }

    public boolean isBlocked() { return isBlocked; }
    public void setBlocked(boolean blocked) { isBlocked = blocked; }

    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public boolean isMuted() { return isMuted; }
    public void setMuted(boolean muted) { isMuted = muted; }

    public boolean isArchived() { return isArchived; }
    public void setArchived(boolean archived) { isArchived = archived; }

    public boolean isSecret() { return isSecret; }
    public void setSecret(boolean secret) { isSecret = secret; }

    public String getChatLockPin() { return chatLockPin; }
    public void setChatLockPin(String chatLockPin) { this.chatLockPin = chatLockPin; }

    public String getWallpaperColor() { return wallpaperColor; }
    public void setWallpaperColor(String wallpaperColor) { this.wallpaperColor = wallpaperColor; }

    public int getSlowModeSec() { return slowModeSec; }
    public void setSlowModeSec(int slowModeSec) { this.slowModeSec = slowModeSec; }

    public String getGroupRules() { return groupRules; }
    public void setGroupRules(String groupRules) { this.groupRules = groupRules; }

    public String getPinnedMessageId() { return pinnedMessageId; }
    public void setPinnedMessageId(String pinnedMessageId) { this.pinnedMessageId = pinnedMessageId; }

    public String getPinnedMessageText() { return pinnedMessageText; }
    public void setPinnedMessageText(String pinnedMessageText) { this.pinnedMessageText = pinnedMessageText; }

    public int getTrustScore() { return trustScore; }
    public void setTrustScore(int trustScore) { this.trustScore = trustScore; }

    public long getGroupExpiresAt() { return groupExpiresAt; }
    public void setGroupExpiresAt(long groupExpiresAt) { this.groupExpiresAt = groupExpiresAt; }

    public String getLastMessageSenderId() { return lastMessageSenderId; }
    public void setLastMessageSenderId(String lastMessageSenderId) { this.lastMessageSenderId = lastMessageSenderId; }

    public String getLastMessageType() { return lastMessageType; }
    public void setLastMessageType(String lastMessageType) { this.lastMessageType = lastMessageType; }

    public boolean isLastMessageDelivered() { return lastMessageDelivered; }
    public void setLastMessageDelivered(boolean lastMessageDelivered) { this.lastMessageDelivered = lastMessageDelivered; }

    public boolean isLastMessageRead() { return lastMessageRead; }
    public void setLastMessageRead(boolean lastMessageRead) { this.lastMessageRead = lastMessageRead; }

    public boolean isLastMessagePending() { return lastMessagePending; }
    public void setLastMessagePending(boolean lastMessagePending) { this.lastMessagePending = lastMessagePending; }

    public boolean isTyping() { return isTyping; }
    public void setTyping(boolean typing) { isTyping = typing; }
}
