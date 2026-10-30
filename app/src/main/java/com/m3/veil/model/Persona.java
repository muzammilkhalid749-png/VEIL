package com.m3.veil.model;

import java.util.Locale;

public class Persona {
    private String personaId;
    private String username;
    private String displayName;
    private String bio;
    private String photoUrl;
    private String mood;
    private boolean isTemporary;
    private long expiresAt; // 0 = permanent, >0 = timestamp when persona vault is shredded

    public Persona() {
    }

    public Persona(String personaId, String displayName, String bio, String photoUrl, String mood, boolean isTemporary) {
        this(personaId, null, displayName, bio, photoUrl, mood, isTemporary, 0L);
    }

    public Persona(String personaId, String displayName, String bio, String photoUrl, String mood, boolean isTemporary, long expiresAt) {
        this(personaId, null, displayName, bio, photoUrl, mood, isTemporary, expiresAt);
    }

    public Persona(String personaId, String username, String displayName, String bio, String photoUrl, String mood, boolean isTemporary, long expiresAt) {
        this.personaId = personaId;
        this.displayName = normalizeDisplayName(displayName, username, personaId);
        this.username = normalizeUsername(username, this.displayName, personaId);
        this.bio = bio;
        this.photoUrl = photoUrl;
        this.mood = mood;
        this.isTemporary = isTemporary;
        this.expiresAt = expiresAt;
    }

    private static String normalizeDisplayName(String displayName, String username, String personaId) {
        if (displayName != null && !displayName.trim().isEmpty()) {
            String clean = displayName.trim();
            if (clean.startsWith("@") && clean.length() > 1) {
                clean = clean.substring(1);
            }
            return clean;
        }
        if (username != null && !username.trim().isEmpty()) {
            String clean = username.trim();
            return clean.startsWith("@") ? clean.substring(1) : clean;
        }
        if ("persona_ghost".equals(personaId) || "persona_secondary".equals(personaId)) {
            return "Midnight Drifter";
        }
        if ("persona_neon".equals(personaId)) {
            return "Cyber Pulse";
        }
        return "Anonymous";
    }

    private static String normalizeUsername(String username, String displayName, String personaId) {
        String candidate = (username != null && !username.trim().isEmpty()) ? username.trim() : displayName;
        if (candidate == null || candidate.trim().isEmpty()) {
            if ("persona_ghost".equals(personaId) || "persona_secondary".equals(personaId)) {
                candidate = "midnight_drifter";
            } else if ("persona_neon".equals(personaId)) {
                candidate = "cyber_pulse";
            } else {
                candidate = "user";
            }
        }
        String clean = candidate.trim().replaceFirst("^@+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.]", "_").replaceAll("_+", "_");
        if (clean.isEmpty()) clean = "persona";
        return "@" + clean;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public String getUsername() {
        if (username == null || username.trim().isEmpty()) {
            username = normalizeUsername(null, displayName, personaId);
        }
        return username.startsWith("@") ? username : "@" + username;
    }

    public String getCleanUsername() {
        return getUsername().replaceFirst("^@+", "");
    }

    public void setUsername(String username) {
        this.username = normalizeUsername(username, this.displayName, this.personaId);
    }

    public String getDisplayName() {
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = normalizeDisplayName(null, username, personaId);
        }
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = normalizeDisplayName(displayName, this.username, this.personaId);
    }

    public String getBio() {
        return bio != null ? bio : "";
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public String getAvatar() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public void setAvatar(String avatar) {
        this.photoUrl = avatar;
    }

    public String getMood() {
        return mood != null ? mood : "✨ Active";
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
