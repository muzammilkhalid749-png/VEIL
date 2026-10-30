package com.m3.veil.model;

public class Persona {
    private String personaId;
    private String displayName;
    private String username;
    private String avatarUri;
    private String bio;
    private String keyPairId;
    private boolean isActive;
    private String bubbleColor;
    private String mood;
    private boolean isTemporary;
    private long expiresAt; // 0 = permanent, >0 = timestamp when persona vault is shredded

    public Persona() {
    }

    public Persona(String personaId, String displayName, String bio, String photoUrl, String mood, boolean isTemporary) {
        this(personaId, displayName, bio, photoUrl, mood, isTemporary, 0L);
    }

    public Persona(String personaId, String displayName, String bio, String photoUrl, String mood, boolean isTemporary, long expiresAt) {
        this.personaId = personaId;
        this.displayName = displayName != null ? displayName : "";
        this.username = deriveDefaultUsername(personaId, displayName);
        this.bio = bio != null ? bio : "";
        if (photoUrl != null && photoUrl.startsWith("#")) {
            this.bubbleColor = photoUrl;
            this.avatarUri = "";
        } else {
            this.avatarUri = photoUrl != null ? photoUrl : "";
            this.bubbleColor = "#0B57CF";
        }
        this.keyPairId = "kp_" + (personaId != null ? personaId : "default");
        this.isActive = false;
        this.mood = mood != null ? mood : "🛡️ Encrypted";
        this.isTemporary = isTemporary;
        this.expiresAt = expiresAt;
    }

    public Persona(String personaId, String displayName, String username, String avatarUri,
                   String bio, String keyPairId, boolean isActive, String bubbleColor,
                   String mood, boolean isTemporary, long expiresAt) {
        this.personaId = personaId;
        this.displayName = displayName != null ? displayName : "";
        this.username = normalizeUsername(username != null && !username.isEmpty() ? username : deriveDefaultUsername(personaId, displayName));
        this.avatarUri = avatarUri != null ? avatarUri : "";
        this.bio = bio != null ? bio : "";
        this.keyPairId = (keyPairId != null && !keyPairId.isEmpty()) ? keyPairId : ("kp_" + (personaId != null ? personaId : "default"));
        this.isActive = isActive;
        this.bubbleColor = (bubbleColor != null && !bubbleColor.isEmpty()) ? bubbleColor : "#0B57CF";
        this.mood = (mood != null && !mood.isEmpty()) ? mood : "🛡️ Encrypted";
        this.isTemporary = isTemporary;
        this.expiresAt = expiresAt;
    }

    public Persona(String personaId, String displayName, String username, String avatarUri,
                   String bio, String keyPairId, boolean isActive, String mood,
                   int bubbleColorInt, boolean isTemporary, long expiresAt) {
        this(personaId, displayName, username, avatarUri, bio, keyPairId, isActive,
                String.format(java.util.Locale.US, "#%06X", (0xFFFFFF & bubbleColorInt)),
                mood, isTemporary, expiresAt);
    }

    public static String normalizeUsername(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().replaceFirst("^@", "").replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(java.util.Locale.ROOT);
        while (clean.contains("__")) {
            clean = clean.replace("__", "_");
        }
        if (clean.startsWith("_")) clean = clean.substring(1);
        if (clean.endsWith("_")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    private static String deriveDefaultUsername(String personaId, String displayName) {
        if (displayName != null && !displayName.trim().isEmpty()) {
            String norm = normalizeUsername(displayName);
            if (!norm.isEmpty()) return norm;
        }
        if (personaId != null && !personaId.trim().isEmpty()) {
            return normalizeUsername(personaId);
        }
        return "anon_persona";
    }

    public String getId() {
        return personaId;
    }

    public void setId(String id) {
        this.personaId = id;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        if (username != null && !username.trim().isEmpty()) {
            return "@" + normalizeUsername(username);
        }
        return "Anonymous";
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUsername() {
        if (username == null || username.trim().isEmpty()) {
            username = deriveDefaultUsername(personaId, displayName);
        }
        return normalizeUsername(username);
    }

    public String getFormattedUsername() {
        String u = getUsername();
        return u.isEmpty() ? "@anonymous" : "@" + u;
    }

    public void setUsername(String username) {
        this.username = normalizeUsername(username);
    }

    public String getAvatarUri() {
        return avatarUri != null ? avatarUri : "";
    }

    public void setAvatarUri(String avatarUri) {
        this.avatarUri = avatarUri;
    }

    public String getBio() {
        return bio != null ? bio : "";
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getKeyPairId() {
        if (keyPairId == null || keyPairId.trim().isEmpty()) {
            keyPairId = "kp_" + (personaId != null ? personaId : "default");
        }
        return keyPairId;
    }

    public void setKeyPairId(String keyPairId) {
        this.keyPairId = keyPairId;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public String getBubbleColor() {
        return (bubbleColor != null && bubbleColor.startsWith("#")) ? bubbleColor : "#0B57CF";
    }

    public void setBubbleColor(String bubbleColor) {
        this.bubbleColor = bubbleColor;
    }

    public String getPhotoUrl() {
        if (avatarUri != null && !avatarUri.isEmpty()) {
            return avatarUri;
        }
        return getBubbleColor();
    }

    public String getAvatar() {
        return getAvatarUri();
    }

    public void setPhotoUrl(String photoUrl) {
        if (photoUrl != null && photoUrl.startsWith("#")) {
            this.bubbleColor = photoUrl;
        } else {
            this.avatarUri = photoUrl;
        }
    }

    public void setAvatar(String avatar) {
        this.avatarUri = avatar;
    }

    public String getMood() {
        return mood != null ? mood : "🛡️ Encrypted";
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public boolean isTemporary() {
        return isTemporary;
    }

    public void setTemporary(boolean temporary) {
        isTemporary = temporary;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }
}

