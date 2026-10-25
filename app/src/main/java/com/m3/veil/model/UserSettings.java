package com.m3.veil.model;

import java.util.HashMap;
import java.util.Map;

public class UserSettings {
    private String theme;
    private Map<String, Boolean> notifications;
    private String language;
    private String fontSize;
    private Map<String, Object> mediaDownload;
    private String chatWallpaper;

    public UserSettings() {
        // Default constructor for Firestore
    }

    public UserSettings(String userId) {
        // Default app preferences
        this.theme = "system";

        this.notifications = new HashMap<>();
        this.notifications.put("message", true);
        this.notifications.put("mention", true);
        this.notifications.put("story", true);
        this.notifications.put("call", true);

        this.language = "en";
        this.fontSize = "medium";

        this.mediaDownload = new HashMap<>();
        this.mediaDownload.put("wifiOnly", true);
        this.mediaDownload.put("autoDownloadPhotos", false);

        this.chatWallpaper = "default";
    }

    // Getters and setters
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public Map<String, Boolean> getNotifications() { return notifications; }
    public void setNotifications(Map<String, Boolean> notifications) { this.notifications = notifications; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getFontSize() { return fontSize; }
    public void setFontSize(String fontSize) { this.fontSize = fontSize; }

    public Map<String, Object> getMediaDownload() { return mediaDownload; }
    public void setMediaDownload(Map<String, Object> mediaDownload) { this.mediaDownload = mediaDownload; }

    public String getChatWallpaper() { return chatWallpaper; }
    public void setChatWallpaper(String chatWallpaper) { this.chatWallpaper = chatWallpaper; }
}