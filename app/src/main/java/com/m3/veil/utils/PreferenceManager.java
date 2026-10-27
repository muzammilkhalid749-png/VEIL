package com.m3.veil.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "veil_preferences";
    private static final String KEY_ONBOARDING_COMPLETED = "onboarding_completed";
    private static final String KEY_FIRST_TIME = "first_time";
    private static final String KEY_STEALTH_MODE = "stealth_mode";

    private final SharedPreferences sharedPreferences;
    private final SharedPreferences.Editor editor;

    public PreferenceManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = sharedPreferences.edit();
    }

    public void setOnboardingCompleted(boolean completed) {
        editor.putBoolean(KEY_ONBOARDING_COMPLETED, completed);
        editor.apply();
    }

    public boolean isOnboardingCompleted() {
        return sharedPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false);
    }

    public void setFirstTime(boolean firstTime) {
        editor.putBoolean(KEY_FIRST_TIME, firstTime);
        editor.apply();
    }

    public boolean isFirstTime() {
        return sharedPreferences.getBoolean(KEY_FIRST_TIME, true);
    }

    public void setStealthMode(boolean enabled) {
        editor.putBoolean(KEY_STEALTH_MODE, enabled);
        editor.apply();
    }

    public boolean isStealthMode() {
        return sharedPreferences.getBoolean(KEY_STEALTH_MODE, false);
    }

    public void putString(String key, String value) {
        if (key == null) return;
        editor.putString(key, value);
        editor.apply();
    }

    public String getString(String key, String defaultValue) {
        if (key == null) return defaultValue;
        return sharedPreferences.getString(key, defaultValue);
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public void putBoolean(String key, boolean value) {
        if (key == null) return;
        editor.putBoolean(key, value);
        editor.apply();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (key == null) return defaultValue;
        return sharedPreferences.getBoolean(key, defaultValue);
    }

    public void putLong(String key, long value) {
        if (key == null) return;
        editor.putLong(key, value);
        editor.apply();
    }

    public long getLong(String key, long defaultValue) {
        if (key == null) return defaultValue;
        return sharedPreferences.getLong(key, defaultValue);
    }

    public void putInt(String key, int value) {
        if (key == null) return;
        editor.putInt(key, value);
        editor.apply();
    }

    public int getInt(String key, int defaultValue) {
        if (key == null) return defaultValue;
        return sharedPreferences.getInt(key, defaultValue);
    }

    public void remove(String key) {
        if (key == null) return;
        editor.remove(key);
        editor.apply();
    }

    public void clearAll() {
        editor.clear();
        editor.apply();
    }
}