package com.m3.veil.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.m3.veil.model.User;

import java.util.Date;

public class UserPreferences {
    private static final String TAG = "UserPreferences";
    private static final String PREF_NAME = "UserPrefs";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_PHONE_NUMBER = "phoneNumber";
    private static final String KEY_COUNTRY_CODE = "countryCode";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "displayName";
    private static final String KEY_PHOTO_URL = "photoUrl";
    private static final String KEY_AVATAR = "avatar";
    private static final String KEY_AVATAR_PATH = "avatarPath";
    private static final String KEY_STATUS = "status";
    private static final String KEY_BIO = "bio";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_LAST_SEEN = "lastSeen";
    private static final String KEY_IS_ONLINE = "isOnline";
    private static final String KEY_HAS_PREMIUM = "hasPremium";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_LAST_UPDATED = "lastUpdated";

    private final SharedPreferences prefs;
    private final Context appContext;

    public UserPreferences(Context context) {
        this.appContext = context.getApplicationContext();
        SharedPreferences resolvedPrefs = null;
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            resolvedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "encrypted_" + PREF_NAME,
                    masterKeyAlias,
                    this.appContext,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard", e);
            resolvedPrefs = this.appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
        this.prefs = resolvedPrefs;
    }

    public void saveUser(User user) {
        if (user == null) return;

        SharedPreferences.Editor editor = prefs.edit();
        if (user.getUserId() != null && !user.getUserId().isEmpty()) {
            editor.putString(KEY_USER_ID, user.getUserId());
        }
        if (user.getPhoneNumber() != null) {
            editor.putString(KEY_PHONE_NUMBER, user.getPhoneNumber());
        }
        if (user.getCountryCode() != null) {
            editor.putString(KEY_COUNTRY_CODE, user.getCountryCode());
        }
        if (user.getUsername() != null) {
            editor.putString(KEY_USERNAME, user.getUsername());
        }
        if (user.getDisplayName() != null) {
            editor.putString(KEY_DISPLAY_NAME, user.getDisplayName());
        }

        String avatarVal = user.getAvatar();
        String photoUrlVal = user.getPhotoUrl();
        if (photoUrlVal != null && !photoUrlVal.isEmpty()) {
            editor.putString(KEY_PHOTO_URL, photoUrlVal);
        } else if (avatarVal != null && !avatarVal.isEmpty()) {
            editor.putString(KEY_PHOTO_URL, avatarVal);
        }
        if (avatarVal != null && !avatarVal.isEmpty()) {
            editor.putString(KEY_AVATAR, avatarVal);
        } else if (photoUrlVal != null && !photoUrlVal.isEmpty()) {
            editor.putString(KEY_AVATAR, photoUrlVal);
        }

        if (user.getStatus() != null) {
            editor.putString(KEY_STATUS, user.getStatus());
        }
        if (user.getBio() != null) {
            editor.putString(KEY_BIO, user.getBio());
        }

        if (user.getCreatedAt() != null) {
            editor.putLong(KEY_CREATED_AT, user.getCreatedAt().toDate().getTime());
        }
        if (user.getLastSeen() != null) {
            editor.putLong(KEY_LAST_SEEN, user.getLastSeen().toDate().getTime());
        }

        editor.putBoolean(KEY_IS_ONLINE, user.isOnline());
        editor.putBoolean(KEY_HAS_PREMIUM, user.isHasPremium());
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putLong(KEY_LAST_UPDATED, System.currentTimeMillis());
        editor.apply();

        syncToPreferenceManager(
                user.getUserId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                photoUrlVal != null && !photoUrlVal.isEmpty() ? photoUrlVal : avatarVal
        );
    }

    public User getCurrentUser() {
        if (!isLoggedIn()) return null;

        String uid = prefs.getString(KEY_USER_ID, null);
        if (uid == null || uid.trim().isEmpty()) {
            PreferenceManager pm = new PreferenceManager(appContext);
            uid = pm.getString("sovereign_uid", null);
            if (uid == null || uid.trim().isEmpty()) {
                return null;
            }
        }

        User user = new User();
        user.setUserId(uid);
        user.setPhoneNumber(prefs.getString(KEY_PHONE_NUMBER, ""));
        user.setCountryCode(prefs.getString(KEY_COUNTRY_CODE, "US"));
        user.setUsername(prefs.getString(KEY_USERNAME, null));
        user.setDisplayName(prefs.getString(KEY_DISPLAY_NAME, null));

        String photoUrl = prefs.getString(KEY_PHOTO_URL, null);
        String avatar = prefs.getString(KEY_AVATAR, null);
        String avatarPath = prefs.getString(KEY_AVATAR_PATH, null);

        String resolvedPhoto = photoUrl != null && !photoUrl.isEmpty()
                ? photoUrl
                : (avatarPath != null && !avatarPath.isEmpty() ? avatarPath : avatar);
        String resolvedAvatar = avatar != null && !avatar.isEmpty()
                ? avatar
                : resolvedPhoto;

        user.setPhotoUrl(resolvedPhoto);
        user.setAvatar(resolvedAvatar);
        user.setStatus(prefs.getString(KEY_STATUS, "Hey there! I am using Veil"));
        user.setBio(prefs.getString(KEY_BIO, ""));

        long createdAtMillis = prefs.getLong(KEY_CREATED_AT, 0);
        if (createdAtMillis > 0) {
            user.setCreatedAt(new Timestamp(new Date(createdAtMillis)));
        }

        long lastSeenMillis = prefs.getLong(KEY_LAST_SEEN, 0);
        if (lastSeenMillis > 0) {
            user.setLastSeen(new Timestamp(new Date(lastSeenMillis)));
        }

        user.setOnline(prefs.getBoolean(KEY_IS_ONLINE, false));
        user.setHasPremium(prefs.getBoolean(KEY_HAS_PREMIUM, false));

        return user;
    }

    public void updateUserField(String field, Object value) {
        if (field == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        PreferenceManager pm = new PreferenceManager(appContext);

        switch (field) {
            case "userId":
                String uid = value != null ? String.valueOf(value) : null;
                editor.putString(KEY_USER_ID, uid);
                if (uid != null) {
                    editor.putBoolean(KEY_IS_LOGGED_IN, true);
                    pm.putString("sovereign_uid", uid);
                    pm.putString("userId", uid);
                }
                break;
            case "username":
                String uname = value != null ? String.valueOf(value) : null;
                editor.putString(KEY_USERNAME, uname);
                if (uname != null) {
                    pm.putString("username", uname);
                }
                break;
            case "displayName":
                String dname = value != null ? String.valueOf(value) : null;
                editor.putString(KEY_DISPLAY_NAME, dname);
                if (dname != null) {
                    pm.putString("user_name", dname);
                    pm.putString("displayName", dname);
                }
                break;
            case "photoUrl":
                String purl = value != null ? String.valueOf(value) : null;
                editor.putString(KEY_PHOTO_URL, purl);
                if (purl != null && !purl.isEmpty()) {
                    editor.putString(KEY_AVATAR, purl);
                    pm.putString("avatar_path", purl);
                }
                break;
            case "avatar":
                String av = value != null ? String.valueOf(value) : null;
                editor.putString(KEY_AVATAR, av);
                if (av != null && !av.isEmpty()) {
                    editor.putString(KEY_PHOTO_URL, av);
                    pm.putString("avatar", av);
                    if (av.startsWith("/") || av.startsWith("file://")) {
                        editor.putString(KEY_AVATAR_PATH, av);
                        pm.putString("avatar_path", av);
                    }
                }
                break;
            case "avatarPath":
                String ap = value != null ? String.valueOf(value) : null;
                editor.putString(KEY_AVATAR_PATH, ap);
                if (ap != null && !ap.isEmpty()) {
                    editor.putString(KEY_PHOTO_URL, ap);
                    pm.putString("avatar_path", ap);
                }
                break;
            case "status":
                editor.putString(KEY_STATUS, value != null ? String.valueOf(value) : null);
                break;
            case "bio":
                String bioVal = value != null ? String.valueOf(value) : "";
                editor.putString(KEY_BIO, bioVal);
                pm.putString("bio", bioVal);
                break;
            case "hasPremium":
                if (value instanceof Boolean) {
                    editor.putBoolean(KEY_HAS_PREMIUM, (Boolean) value);
                }
                break;
            case "isOnline":
                if (value instanceof Boolean) {
                    editor.putBoolean(KEY_IS_ONLINE, (Boolean) value);
                }
                break;
        }

        editor.putLong(KEY_LAST_UPDATED, System.currentTimeMillis());
        editor.apply();
    }

    public void saveProfileFields(String userId, String username, String displayName, String bio, String avatarRef) {
        String path = (avatarRef != null && (avatarRef.startsWith("/") || avatarRef.startsWith("file://"))) ? avatarRef : null;
        String base64 = (path == null) ? avatarRef : null;
        saveProfileFields(userId, username, displayName, bio, path, base64);
    }

    public void saveProfileFields(String userId, String username, String displayName, String bio, String avatarPath, String avatarBase64) {
        SharedPreferences.Editor editor = prefs.edit();
        if (userId != null && !userId.isEmpty()) {
            editor.putString(KEY_USER_ID, userId);
            editor.putBoolean(KEY_IS_LOGGED_IN, true);
        }
        if (username != null && !username.isEmpty()) {
            editor.putString(KEY_USERNAME, username);
        }
        if (displayName != null && !displayName.isEmpty()) {
            editor.putString(KEY_DISPLAY_NAME, displayName);
        }
        if (bio != null) {
            editor.putString(KEY_BIO, bio);
        }
        if (avatarPath != null && !avatarPath.isEmpty()) {
            editor.putString(KEY_AVATAR_PATH, avatarPath);
            editor.putString(KEY_PHOTO_URL, avatarPath);
        }
        if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            editor.putString(KEY_AVATAR, avatarBase64);
            if (avatarPath == null || avatarPath.isEmpty()) {
                editor.putString(KEY_PHOTO_URL, avatarBase64);
            }
        }
        editor.putLong(KEY_LAST_UPDATED, System.currentTimeMillis());
        editor.apply();

        syncToPreferenceManager(
                userId,
                username,
                displayName,
                bio,
                avatarPath != null && !avatarPath.isEmpty() ? avatarPath : avatarBase64
        );
    }

    private void syncToPreferenceManager(String userId, String username, String displayName, String bio, String avatarRef) {
        try {
            PreferenceManager pm = new PreferenceManager(appContext);
            if (userId != null && !userId.isEmpty()) {
                pm.putString("sovereign_uid", userId);
                pm.putString("userId", userId);
                pm.putBoolean("sovereign_logged_in", true);
            }
            if (username != null && !username.isEmpty()) {
                pm.putString("username", username);
            }
            if (displayName != null && !displayName.isEmpty()) {
                pm.putString("user_name", displayName);
                pm.putString("displayName", displayName);
            } else if (username != null && !username.isEmpty()) {
                pm.putString("user_name", username);
            }
            if (bio != null) {
                pm.putString("bio", bio);
            }
            if (avatarRef != null && !avatarRef.isEmpty()) {
                pm.putString("avatar_path", avatarRef);
            }
        } catch (Exception ignored) {
        }
    }

    public boolean isLoggedIn() {
        boolean flag = prefs.getBoolean(KEY_IS_LOGGED_IN, false);
        if (flag) return true;
        String uid = prefs.getString(KEY_USER_ID, null);
        return uid != null && !uid.trim().isEmpty();
    }

    public boolean hasUsername() {
        String username = getUsername();
        return username != null && !username.trim().isEmpty();
    }

    public boolean isCacheStale() {
        long lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0);
        long thirtyMinutesInMillis = 30 * 60 * 1000L;
        return (System.currentTimeMillis() - lastUpdated) > thirtyMinutesInMillis;
    }

    public void clearUser() {
        prefs.edit().clear().apply();
    }

    public void clearUserData() {
        clearUser();
    }

    public String getPhone() {
        return prefs.getString(KEY_PHONE_NUMBER, "");
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public String getDisplayName() {
        String dn = prefs.getString(KEY_DISPLAY_NAME, null);
        if (dn != null && !dn.trim().isEmpty()) {
            return dn;
        }
        return getUsername();
    }

    public String getBio() {
        return prefs.getString(KEY_BIO, "");
    }

    public String getAvatarPath() {
        String ap = prefs.getString(KEY_AVATAR_PATH, null);
        if (ap != null && !ap.isEmpty()) return ap;
        return prefs.getString(KEY_PHOTO_URL, null);
    }

    public String getAvatar() {
        String av = prefs.getString(KEY_AVATAR, null);
        if (av != null && !av.isEmpty()) return av;
        return prefs.getString(KEY_PHOTO_URL, null);
    }

    public String getUserId() {
        String uid = prefs.getString(KEY_USER_ID, null);
        if (uid != null && !uid.trim().isEmpty()) {
            return uid;
        }
        try {
            PreferenceManager pm = new PreferenceManager(appContext);
            return pm.getString("sovereign_uid", null);
        } catch (Exception e) {
            return null;
        }
    }
}