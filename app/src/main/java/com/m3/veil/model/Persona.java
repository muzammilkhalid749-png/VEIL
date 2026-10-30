package com.m3.veil.model;

public class Persona {
    private String personaId;
    private String displayName;
    private String bio;
    private String photoUrl;
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
        this.displayName = displayName;
        this.bio = bio;
        this.photoUrl = photoUrl;
        this.mood = mood;
        this.isTemporary = isTemporary;
        this.expiresAt = expiresAt;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBio() {
        return bio;
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
        return mood;
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
