package com.m3.veil.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GroupMentionCandidate — Encapsulates a group participant eligible for @mention autocomplete,
 * including their cryptographic/privacy ACL permission.
 */
public class GroupMentionCandidate {
    private final String userId;
    private final String username;
    private final String displayName;
    private final String avatar;
    private final String allowMentions; // "everyone", "contacts", "nobody"
    private final boolean isAdmin;
    private final boolean isOwner;

    public GroupMentionCandidate(@NonNull String userId,
                                 @NonNull String username,
                                 @Nullable String displayName,
                                 @Nullable String avatar,
                                 @Nullable String allowMentions,
                                 boolean isAdmin,
                                 boolean isOwner) {
        this.userId = userId;
        this.username = username;
        this.displayName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : username;
        this.avatar = avatar;
        this.allowMentions = (allowMentions != null && !allowMentions.trim().isEmpty()) ? allowMentions.trim().toLowerCase() : "everyone";
        this.isAdmin = isAdmin;
        this.isOwner = isOwner;
    }

    @NonNull
    public String getUserId() {
        return userId;
    }

    @NonNull
    public String getUsername() {
        return username;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public String getAvatar() {
        return avatar;
    }

    @NonNull
    public String getAllowMentions() {
        return allowMentions;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public boolean isOwner() {
        return isOwner;
    }

    /**
     * Checks if this candidate is permitted to be mentioned by the current user
     * according to the candidate's privacy policy.
     */
    public boolean canBeMentionedBy(String currentUserId, boolean isContact) {
        if ("nobody".equalsIgnoreCase(allowMentions)) {
            return false;
        }
        if ("contacts".equalsIgnoreCase(allowMentions)) {
            return isContact;
        }
        return true; // default "everyone"
    }

    public boolean matchesQuery(String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String q = query.toLowerCase().trim();
        if (q.startsWith("@")) q = q.substring(1);
        if (username != null && username.toLowerCase().contains(q)) return true;
        if (displayName != null && displayName.toLowerCase().contains(q)) return true;
        return false;
    }

}
