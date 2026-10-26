package com.m3.veil.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.m3.veil.model.User;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class UserPreferences {

    private static final String PREFS_NAME = "veil_user_prefs";
    private static final String KEY_CURRENT_USER = "current_user";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_PHOTO_URL = "photo_url";
    private static final String KEY_STATUS = "status";
    private static final String KEY_HAS_PREMIUM = "has_premium";

    private SharedPreferences prefs;
    private Gson gson;

    public UserPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    // Save complete user object as JSON
    public void saveUser(User user) {
        if (user == null) {
            clearUser();
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();

        // Save complete user object as JSON
        String userJson = gson.toJson(user);
        editor.putString(KEY_CURRENT_USER, userJson);

        // Also save individual fields for quick access
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USER_ID, user.getUserId());
        editor.putString(KEY_USERNAME, user.getUsername());
        editor.putString(KEY_DISPLAY_NAME, user.getDisplayName());
        editor.putString(KEY_PHONE, user.getPhone());
        editor.putString(KEY_PHOTO_URL, user.getPhotoUrl());
        editor.putString(KEY_STATUS, user.getStatus());
        editor.putBoolean(KEY_HAS_PREMIUM, user.isHasPremium());

        editor.apply();
    }

    // Get complete user object
    public User getCurrentUser() {
        String userJson = prefs.getString(KEY_CURRENT_USER, null);
        if (userJson != null) {
            try {
                return gson.fromJson(userJson, User.class);
            } catch (Exception e) {
                e.printStackTrace();
                // If JSON parsing fails, fall back to individual fields
                return getCurrentUserFromIndividualFields();
            }
        }
        return null;
    }

    // Fallback method to build user from individual fields
    private User getCurrentUserFromIndividualFields() {
        if (!prefs.getBoolean(KEY_IS_LOGGED_IN, false)) {
            return null;
        }

        String userId = prefs.getString(KEY_USER_ID, null);
        if (userId == null) {
            return null;
        }

        User user = new User();
        user.setUserId(userId);
        user.setUsername(prefs.getString(KEY_USERNAME, null));
        user.setDisplayName(prefs.getString(KEY_DISPLAY_NAME, null));
        user.setPhone(prefs.getString(KEY_PHONE, null));
        user.setPhotoUrl(prefs.getString(KEY_PHOTO_URL, null));
        user.setStatus(prefs.getString(KEY_STATUS, "online"));
        user.setHasPremium(prefs.getBoolean(KEY_HAS_PREMIUM, false));

        return user;
    }

    // Update specific user fields
    public void updateUserField(String field, Object value) {
        User currentUser = getCurrentUser();
        if (currentUser == null) return;

        switch (field) {
            case "username":
                currentUser.setUsername((String) value);
                prefs.edit().putString(KEY_USERNAME, (String) value).apply();
                break;
            case "displayName":
                currentUser.setDisplayName((String) value);
                prefs.edit().putString(KEY_DISPLAY_NAME, (String) value).apply();
                break;
            case "photoUrl":
                currentUser.setPhotoUrl((String) value);
                prefs.edit().putString(KEY_PHOTO_URL, (String) value).apply();
                break;
            case "status":
                currentUser.setStatus((String) value);
                prefs.edit().putString(KEY_STATUS, (String) value).apply();
                break;
            case "bio":
                currentUser.setBio((String) value);
                break;
            case "hasPremium":
                currentUser.setHasPremium((Boolean) value);
                prefs.edit().putBoolean(KEY_HAS_PREMIUM, (Boolean) value).apply();
                break;
        }

        saveUser(currentUser);
    }

    // Quick access methods for commonly used fields
    public void saveUserId(String userId) {
        prefs.edit().putString(KEY_USER_ID, userId).putBoolean(KEY_IS_LOGGED_IN, true).apply();
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public String getDisplayName() {
        return prefs.getString(KEY_DISPLAY_NAME, null);
    }

    public String getPhone() {
        return prefs.getString(KEY_PHONE, null);
    }

    public String getPhotoUrl() {
        return prefs.getString(KEY_PHOTO_URL, null);
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public boolean hasPremium() {
        return prefs.getBoolean(KEY_HAS_PREMIUM, false);
    }

    // Clear user data (logout)
    public void clearUser() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(KEY_CURRENT_USER);
        editor.remove(KEY_IS_LOGGED_IN);
        editor.remove(KEY_USER_ID);
        editor.remove(KEY_USERNAME);
        editor.remove(KEY_DISPLAY_NAME);
        editor.remove(KEY_PHONE);
        editor.remove(KEY_PHOTO_URL);
        editor.remove(KEY_STATUS);
        editor.remove(KEY_HAS_PREMIUM);
        editor.apply();
    }

    // Check if user has completed profile setup (has username)
    public boolean hasCompletedProfile() {
        String username = getUsername();
        return username != null && !username.isEmpty();
    }
}