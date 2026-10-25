package com.m3.veil.model;

import com.google.firebase.Timestamp;
import java.util.List;

public class Post {
    private String id;
    private String authorId;
    private String authorUsername;
    private String authorAvatar;
    private String content;
    private Timestamp timestamp;
    private int likesCount;
    private int commentsCount;
    private List<String> hashtags;
    private String type; // "text", "image", "poll", "confession", "moment"
    private String imageUrl;
    private List<String> likedBy = new java.util.ArrayList<>();
    private String moodBadge; // e.g. "✨ Curious", "💭 Confession", "🔥 Hyped"
    private String category; // "feed", "confession", "poll", "moment", "jar"
    private String pollJson; // JSON for poll posts
    private long expiresAtMillis; // >0 for 24h Moments or timed Confession Jar reveal
    private String commentsJson; // JSON array of comments synced via P2P Gossip

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
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public List<String> getLikedBy() {
        if (likedBy == null) likedBy = new java.util.ArrayList<>();
        return likedBy;
    }
    public void setLikedBy(List<String> likedBy) {
        this.likedBy = likedBy != null ? likedBy : new java.util.ArrayList<>();
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

    public boolean isMomentActive() {
        return expiresAtMillis > System.currentTimeMillis();
    }
}