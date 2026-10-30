package com.m3.veil.model;

public class UserPrivacy {
    private String phoneVisible;
    private String lastSeenVisible;
    private String profilePhotoVisible;
    private boolean readReceipts;
    private boolean screenshotBlock;
    private boolean antiForward;
    private boolean stealthMode;
    private boolean ghostMode;
    private boolean incognitoTyping;
    private boolean hideOnlineStatus;
    private boolean twoFactorEnabled;
    private boolean ephemeralChats;
    private int autoDeleteMessagesDays;
    private int autoDeleteStoriesHours;
    private boolean dataCollectionConsent;
    private boolean showUsernameInGroup = false;
    private String allowMentions = "everyone";
    private String whoCanMessageMe = "everyone"; // "everyone", "contacts", "nobody"

    public UserPrivacy() {
        // Default constructor for Firestore
    }

    public UserPrivacy(String userId) {
        // Default privacy settings
        this.phoneVisible = "nobody";
        this.lastSeenVisible = "contacts";
        this.profilePhotoVisible = "contacts";
        this.readReceipts = true;
        this.screenshotBlock = true;
        this.antiForward = true;
        this.stealthMode = false;
        this.ghostMode = false;
        this.incognitoTyping = true;
        this.hideOnlineStatus = true;
        this.twoFactorEnabled = false;
        this.ephemeralChats = true;
        this.autoDeleteMessagesDays = 7;
        this.autoDeleteStoriesHours = 24;
        this.dataCollectionConsent = false;
    }

    // Getters and setters
    public String getPhoneVisible() { return phoneVisible; }
    public void setPhoneVisible(String phoneVisible) { this.phoneVisible = phoneVisible; }

    public String getLastSeenVisible() { return lastSeenVisible; }
    public void setLastSeenVisible(String lastSeenVisible) { this.lastSeenVisible = lastSeenVisible; }

    public String getProfilePhotoVisible() { return profilePhotoVisible; }
    public void setProfilePhotoVisible(String profilePhotoVisible) { this.profilePhotoVisible = profilePhotoVisible; }

    public boolean isReadReceipts() { return readReceipts; }
    public void setReadReceipts(boolean readReceipts) { this.readReceipts = readReceipts; }

    public boolean isScreenshotBlock() { return screenshotBlock; }
    public void setScreenshotBlock(boolean screenshotBlock) { this.screenshotBlock = screenshotBlock; }

    public boolean isAntiForward() { return antiForward; }
    public void setAntiForward(boolean antiForward) { this.antiForward = antiForward; }

    public boolean isStealthMode() { return stealthMode; }
    public void setStealthMode(boolean stealthMode) { this.stealthMode = stealthMode; }

    public boolean isGhostMode() { return ghostMode; }
    public void setGhostMode(boolean ghostMode) { this.ghostMode = ghostMode; }

    public boolean isIncognitoTyping() { return incognitoTyping; }
    public void setIncognitoTyping(boolean incognitoTyping) { this.incognitoTyping = incognitoTyping; }

    public boolean isHideOnlineStatus() { return hideOnlineStatus; }
    public void setHideOnlineStatus(boolean hideOnlineStatus) { this.hideOnlineStatus = hideOnlineStatus; }

    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public void setTwoFactorEnabled(boolean twoFactorEnabled) { this.twoFactorEnabled = twoFactorEnabled; }

    public boolean isEphemeralChats() { return ephemeralChats; }
    public void setEphemeralChats(boolean ephemeralChats) { this.ephemeralChats = ephemeralChats; }

    public int getAutoDeleteMessagesDays() { return autoDeleteMessagesDays; }
    public void setAutoDeleteMessagesDays(int autoDeleteMessagesDays) { this.autoDeleteMessagesDays = autoDeleteMessagesDays; }

    public int getAutoDeleteStoriesHours() { return autoDeleteStoriesHours; }
    public void setAutoDeleteStoriesHours(int autoDeleteStoriesHours) { this.autoDeleteStoriesHours = autoDeleteStoriesHours; }

    public boolean isDataCollectionConsent() { return dataCollectionConsent; }
    public void setDataCollectionConsent(boolean dataCollectionConsent) { this.dataCollectionConsent = dataCollectionConsent; }

    public boolean isShowUsernameInGroup() { return showUsernameInGroup; }
    public void setShowUsernameInGroup(boolean showUsernameInGroup) { this.showUsernameInGroup = showUsernameInGroup; }

    public String getAllowMentions() { return allowMentions; }
    public void setAllowMentions(String allowMentions) { this.allowMentions = allowMentions; }

    public String getWhoCanMessageMe() { return whoCanMessageMe != null ? whoCanMessageMe : "everyone"; }
    public void setWhoCanMessageMe(String whoCanMessageMe) { this.whoCanMessageMe = whoCanMessageMe; }
}