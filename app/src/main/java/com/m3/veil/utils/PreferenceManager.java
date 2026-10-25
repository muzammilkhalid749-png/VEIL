package com.m3.veil.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "veil_preferences";
    private static final String KEY_ONBOARDING_COMPLETED = "onboarding_completed";
    private static final String KEY_FIRST_TIME = "first_time";
    private static final String KEY_STEALTH_MODE = "stealth_mode";


    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor editor;

    public PreferenceManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = sharedPreferences.edit();
    }

    // Onboarding methods
    public void setOnboardingCompleted(boolean completed) {
        editor.putBoolean(KEY_ONBOARDING_COMPLETED, completed);
        editor.apply();
    }

    public boolean isOnboardingCompleted() {
        return sharedPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false);
    }

    // First time app launch
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


    // Clear all preferences (logout)
    public void clearAll() {
        editor.clear();
        editor.apply();
    }
}