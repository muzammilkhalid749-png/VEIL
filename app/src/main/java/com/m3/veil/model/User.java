package com.m3.veil.model;

import com.m3.veil.model.Timestamp;
import com.m3.veil.bridge.firestore.IgnoreExtraProperties;
import java.util.List;
import java.util.ArrayList;

@IgnoreExtraProperties
public class User {
    private String userId;
    private String phone;
    private boolean phoneVisible;
    private String username;
    private String displayName;
    private String bio;
    private String photoUrl;
    private String avatar;
    private String status;
    private Timestamp lastSeen;
    private String countryCode;
    private String appVersion;
    private String language;
    private Timestamp joinedAt;
    private boolean verified;
    private List<String> blockedUsers;
    private int contactsCount;
    private String fcmToken;

    // Multi-persona fields
    private List<Persona> personas;
    private String activePersonaId;

    // Premium fields
    private boolean hasPremium;
    private String premiumTier;
    private Timestamp premiumExpiry;
    private List<String> premiumFeatures;
    private String reservedUsername;
    private String publicKey;

    public User() {
        // Default constructor for Firestore
    }

    public User(String userId, String phone, String countryCode) {
        this.userId = userId;
        this.phone = phone;
        this.phoneVisible = false;
        this.username = null;
        this.displayName = null;
        this.bio = "";
        this.photoUrl = null;
        this.status = "online";
        this.lastSeen = Timestamp.now();
        this.countryCode = countryCode;
        this.appVersion = "1.0.0";
        this.language = "en";
        this.joinedAt = Timestamp.now();
        this.verified = false;
        this.blockedUsers = new ArrayList<>();
        this.contactsCount = 0;

        // Multi-persona defaults
        this.personas = new ArrayList<>();
        this.activePersonaId = null;

        // Premium defaults
        this.hasPremium = false;
        this.premiumTier = "free";
        this.premiumExpiry = null;
        this.premiumFeatures = new ArrayList<>();
        this.reservedUsername = null;
    }

    // Getters and setters for all fields
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public boolean isPhoneVisible() { return phoneVisible; }
    public void setPhoneVisible(boolean phoneVisible) { this.phoneVisible = phoneVisible; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Timestamp getLastSeen() { return lastSeen; }
    public void setLastSeen(Timestamp lastSeen) { this.lastSeen = lastSeen; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Timestamp getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Timestamp joinedAt) { this.joinedAt = joinedAt; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public List<String> getBlockedUsers() { return blockedUsers; }
    public void setBlockedUsers(List<String> blockedUsers) { this.blockedUsers = blockedUsers; }

    public int getContactsCount() { return contactsCount; }
    public void setContactsCount(int contactsCount) { this.contactsCount = contactsCount; }

    public List<Persona> getPersonas() { return personas; }
    public void setPersonas(List<Persona> personas) { this.personas = personas; }

    public String getActivePersonaId() { return activePersonaId; }
    public void setActivePersonaId(String activePersonaId) { this.activePersonaId = activePersonaId; }

    public boolean isHasPremium() { return hasPremium; }
    public void setHasPremium(boolean hasPremium) { this.hasPremium = hasPremium; }

    public String getPremiumTier() { return premiumTier; }
    public void setPremiumTier(String premiumTier) { this.premiumTier = premiumTier; }

    public Timestamp getPremiumExpiry() { return premiumExpiry; }
    public void setPremiumExpiry(Timestamp premiumExpiry) { this.premiumExpiry = premiumExpiry; }

    public List<String> getPremiumFeatures() { return premiumFeatures; }
    public void setPremiumFeatures(List<String> premiumFeatures) { this.premiumFeatures = premiumFeatures; }

    public String getReservedUsername() { return reservedUsername; }
    public void setReservedUsername(String reservedUsername) { this.reservedUsername = reservedUsername; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public String getAvatar() { return avatar != null ? avatar : photoUrl; }
    public void setAvatar(String avatar) { this.avatar = avatar; this.photoUrl = avatar; }

    /**
     * Telegram-grade presence verification.
     * Evaluates true IF AND ONLY IF status is "online" AND lastSeen is within TTL.
     */
    public boolean isUserOnline() {
        if (!"online".equalsIgnoreCase(status)) return false;
        if (lastSeen == null) return false;
        long diff = System.currentTimeMillis() - lastSeen.toDate().getTime();
        if (diff < 0) {
            return Math.abs(diff) < 60_000L;
        }
        return diff < com.m3.veil.manager.PresenceManager.PRESENCE_TTL_MS;
    }

    /**
     * Formats lastSeen timestamp into human-readable Telegram format:
     * "online", "last seen just now", "last seen 5m ago", "last seen 2h ago", etc.
     */
    public String getFormattedLastSeen() {
        if (isUserOnline()) return "online";
        if (lastSeen == null) return "offline";
        long diff = System.currentTimeMillis() - lastSeen.toDate().getTime();
        if (diff < 0 || diff < 60_000L) return "last seen just now";
        long minutes = diff / 60_000L;
        if (minutes < 60) return "last seen " + minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return "last seen " + hours + "h ago";
        long days = hours / 24;
        if (days == 1) return "last seen yesterday";
        if (days < 7) return "last seen " + days + "d ago";
        return "last seen recently";
    }
}
