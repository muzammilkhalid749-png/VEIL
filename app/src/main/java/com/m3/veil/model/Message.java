package com.m3.veil.model;

import com.m3.veil.model.Timestamp;

public class Message {
    private String id;
    private String chatId;
    private String senderId;
    private String receiverId;
    private String text;
    private Timestamp timestamp;
    private String type; // "text", "image", "voice"
    private boolean read;
    private boolean delivered;
    private String aesKeyForSender;
    private String aesKeyForRecipient;
    private String iv;
    private Timestamp expiresAt;
    private java.util.Map<String, String> reactions;
    private String replyToMessageId;
    private String replySenderName;
    
    private String mediaUrl;
    private String aesKeyForSenderMedia;
    private String aesKeyForRecipientMedia;
    private String thumbnail;
    
    @com.m3.veil.bridge.firestore.Exclude
    private String localFilePath;
    
    @com.m3.veil.bridge.firestore.Exclude
    private boolean pending;

    private boolean edited;
    private boolean blink; // One-time "blink" view-once message
    private boolean blinkViewed;
    private boolean starred;
    private boolean pinned;
    private String voiceFilter; // "normal", "robot", "deep", "chipmunk"
    private String pollJson; // JSON object for inline polls
    private String senderName;
    private long vanishTimer; // Vanish duration in milliseconds
    private String vanishMode; // "after_send" or "after_read"
    private int viewCount;
    private int commentCount;

    public Message() {
        // Default constructor
    }

    public Message(String id, String senderId, String receiverId, String text, Timestamp timestamp, String type) {
        this.id = id;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.text = text;
        this.timestamp = timestamp;
        this.type = type;
        this.read = false;
        this.delivered = false;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public int getCommentCount() { return commentCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getContent() { return text; }
    public void setContent(String content) { this.text = content; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }

    public String getAesKeyForSender() { return aesKeyForSender; }
    public void setAesKeyForSender(String aesKeyForSender) { this.aesKeyForSender = aesKeyForSender; }

    public String getAesKeyForRecipient() { return aesKeyForRecipient; }
    public void setAesKeyForRecipient(String aesKeyForRecipient) { this.aesKeyForRecipient = aesKeyForRecipient; }

    public String getIv() { return iv; }
    public void setIv(String iv) { this.iv = iv; }

    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }

    public java.util.Map<String, String> getReactions() { return reactions; }
    public void setReactions(java.util.Map<String, String> reactions) { this.reactions = reactions; }

    public String getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(String replyToMessageId) { this.replyToMessageId = replyToMessageId; }

    public String getReplySenderName() { return replySenderName; }
    public void setReplySenderName(String replySenderName) { this.replySenderName = replySenderName; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }

    public void setMediaSize(long bytes) {
        if (bytes > 0) {
            this.mediaUrl = String.valueOf(bytes);
        }
    }

    public String getFormattedMediaSize() {
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()) {
            try {
                long bytes = Long.parseLong(mediaUrl.trim());
                return formatFileSize(bytes);
            } catch (Exception e) {
                return mediaUrl.trim();
            }
        }
        return "";
    }

    public static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    public String getAesKeyForSenderMedia() { return aesKeyForSenderMedia; }
    public void setAesKeyForSenderMedia(String aesKeyForSenderMedia) { this.aesKeyForSenderMedia = aesKeyForSenderMedia; }

    public String getAesKeyForRecipientMedia() { return aesKeyForRecipientMedia; }
    public void setAesKeyForRecipientMedia(String aesKeyForRecipientMedia) { this.aesKeyForRecipientMedia = aesKeyForRecipientMedia; }
    
    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }

    @com.m3.veil.bridge.firestore.Exclude
    public String getLocalFilePath() { return localFilePath; }
    @com.m3.veil.bridge.firestore.Exclude
    public void setLocalFilePath(String localFilePath) { this.localFilePath = localFilePath; }
    
    @com.m3.veil.bridge.firestore.Exclude
    public boolean isPending() { return pending; }
    @com.m3.veil.bridge.firestore.Exclude
    public void setPending(boolean pending) { this.pending = pending; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public boolean isBlink() { return blink; }
    public void setBlink(boolean blink) { this.blink = blink; }

    public boolean isBlinkViewed() { return blinkViewed; }
    public void setBlinkViewed(boolean blinkViewed) { this.blinkViewed = blinkViewed; }

    public boolean isStarred() { return starred; }
    public void setStarred(boolean starred) { this.starred = starred; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public String getVoiceFilter() { return voiceFilter; }
    public void setVoiceFilter(String voiceFilter) { this.voiceFilter = voiceFilter; }

    public String getPollJson() { return pollJson; }
    public void setPollJson(String pollJson) { this.pollJson = pollJson; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public long getVanishTimer() { return vanishTimer; }
    public void setVanishTimer(long vanishTimer) { this.vanishTimer = vanishTimer; }

    public String getVanishMode() { return vanishMode; }
    public void setVanishMode(String vanishMode) { this.vanishMode = vanishMode; }

    @com.m3.veil.bridge.firestore.Exclude
    private String translatedText;
    @com.m3.veil.bridge.firestore.Exclude
    private String translatedLang;

    @com.m3.veil.bridge.firestore.Exclude
    public String getTranslatedText() { return translatedText; }
    @com.m3.veil.bridge.firestore.Exclude
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }

    @com.m3.veil.bridge.firestore.Exclude
    public String getTranslatedLang() { return translatedLang; }
    @com.m3.veil.bridge.firestore.Exclude
    public void setTranslatedLang(String translatedLang) { this.translatedLang = translatedLang; }
}