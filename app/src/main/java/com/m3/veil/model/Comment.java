package com.m3.veil.model;

import com.google.firebase.Timestamp;

public class Comment {
    private String id;
    private String postId;
    private String authorId;
    private String authorUsername;
    private String text;
    private Timestamp timestamp;
    private String parentCommentId;
    private int depth = 0;

    public Comment() {
        // Default constructor for Firestore
    }

    public Comment(String id, String postId, String authorId, String authorUsername, String text, Timestamp timestamp) {
        this.id = id;
        this.postId = postId;
        this.authorId = authorId;
        this.authorUsername = authorUsername;
        this.text = text;
        this.timestamp = timestamp;
    }

    public Comment(String id, String postId, String authorId, String authorUsername, String text, Timestamp timestamp, String parentCommentId, int depth) {
        this(id, postId, authorId, authorUsername, text, timestamp);
        this.parentCommentId = parentCommentId;
        this.depth = depth;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getAuthorUsername() { return authorUsername; }
    public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    public String getParentCommentId() { return parentCommentId; }
    public void setParentCommentId(String parentCommentId) { this.parentCommentId = parentCommentId; }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
}