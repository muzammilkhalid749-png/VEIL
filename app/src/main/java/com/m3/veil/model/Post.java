package com.m3.veil.model;

import com.google.firebase.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class Post {

    // 10 Comprehensive Multi-Modal Post Types
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_PHOTO = "photo";
    public static final String TYPE_MOSAIC = "mosaic";
    public static final String TYPE_SPOILER = "spoiler";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_POLL = "poll";
    public static final String TYPE_MARKDOWN = "markdown";
    public static final String TYPE_MOMENT = "moment";

    private String id;
    private String authorId;
    private String authorUsername;
    private String authorAvatar;
    private String content;
    private Timestamp timestamp;
    private int likesCount;
    private int commentsCount;
    private List<String> hashtags;
    private String type; // one of the 10 post types above (also accepts "image", "voice", "confession")
    private String imageUrl;
    private List<String> mediaUrls = new ArrayList<>();
    private List<String> likedBy = new ArrayList<>();
    private String moodBadge; // e.g. "✨ Curious", "💭 Confession", "🔥 Hyped"
    private String category; // "feed", "confession", "poll", "moment", "jar"
    private String pollJson; // JSON for poll / rich metadata
    private long expiresAtMillis; // >0 for 24h Moments or timed Confession Jar reveal
    private String commentsJson; // JSON array of comments synced via P2P Gossip
    private boolean isSpoiler = false;

    public Post() {
        // Default constructor
    }

    public Post(String id, String authorId, String authorUsername, String authorAvatar, String content, Timestamp timestamp, String type) {
        this.id = id;
        this.authorId = authorId;
        this.authorUsername = authorUsername;
        this.authorAvatar = authorAvatar;
        this.content = content;
        this.timestamp = timestamp;
        this.type = type;
        this.likesCount = 0;
        this.commentsCount = 0;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getAuthorUsername() { return authorUsername; }
    public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }

    public String getAuthorAvatar() { return authorAvatar; }
    public void setAuthorAvatar(String authorAvatar) { this.authorAvatar = authorAvatar; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    public int getLikesCount() { return likesCount; }
    public void setLikesCount(int likesCount) { this.likesCount = likesCount; }

    public int getCommentsCount() { return commentsCount; }
    public void setCommentsCount(int commentsCount) { this.commentsCount = commentsCount; }

    public List<String> getHashtags() { return hashtags; }
    public void setHashtags(List<String> hashtags) { this.hashtags = hashtags; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
        if (imageUrl != null && !imageUrl.isEmpty() && (mediaUrls == null || mediaUrls.isEmpty())) {
            mediaUrls = new ArrayList<>();
            mediaUrls.add(imageUrl);
        }
    }

    public List<String> getMediaUrls() {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            if (imageUrl != null && !imageUrl.isEmpty()) {
                List<String> list = new ArrayList<>();
                list.add(imageUrl);
                return list;
            }
            return new ArrayList<>();
        }
        return mediaUrls;
    }

    public void setMediaUrls(List<String> mediaUrls) {
        this.mediaUrls = mediaUrls != null ? mediaUrls : new ArrayList<>();
        if (!this.mediaUrls.isEmpty() && (imageUrl == null || imageUrl.isEmpty())) {
            this.imageUrl = this.mediaUrls.get(0);
        }
    }

    public List<String> getLikedBy() {
        if (likedBy == null) likedBy = new ArrayList<>();
        return likedBy;
    }
    public void setLikedBy(List<String> likedBy) {
        this.likedBy = likedBy != null ? likedBy : new ArrayList<>();
    }

    public String getMoodBadge() { return moodBadge; }
    public void setMoodBadge(String moodBadge) { this.moodBadge = moodBadge; }

    public String getCategory() { return category != null ? category : "feed"; }
    public void setCategory(String category) { this.category = category; }

    public String getPollJson() { return pollJson; }
    public void setPollJson(String pollJson) { this.pollJson = pollJson; }

    public long getExpiresAtMillis() { return expiresAtMillis; }
    public void setExpiresAtMillis(long expiresAtMillis) { this.expiresAtMillis = expiresAtMillis; }

    public String getCommentsJson() { return commentsJson; }
    public void setCommentsJson(String commentsJson) { this.commentsJson = commentsJson; }

    public boolean isSpoiler() {
        return isSpoiler || TYPE_SPOILER.equalsIgnoreCase(type) || (content != null && content.contains("||"));
    }

    public void setSpoiler(boolean spoiler) {
        this.isSpoiler = spoiler;
    }

    public boolean isMomentActive() {
        return expiresAtMillis > System.currentTimeMillis();
    }
}
