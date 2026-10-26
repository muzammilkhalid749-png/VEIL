package com.m3.veil.manager;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.m3.veil.calling.CallEngine;
import com.m3.veil.calling.VeilCallService;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.User;
import com.m3.veil.services.VeilMeshDaemonService;
import com.m3.veil.ui.splash.SplashActivity;
import com.m3.veil.utils.PreferenceManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest;
import com.m3.veil.utils.UserPreferences;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.WebRtcManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL CENTRALIZED AUTH & IDENTITY STATE MACHINE (SIGNAL SMART-HYBRID ARCHITECTURE)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Coordinates deterministic 12-word BIP-39 Sovereign Identity ("veil_<sha256_prefix>") and
 * Phone Auth ("phone_<sha256_prefix>") across:
 *   1. UserPreferences (EncryptedSharedPreferences)
 *   2. PreferenceManager ("VeilPrefs")
 *   3. EncryptedChatDatabase (SQLCipher WAL)
 *   4. Firebase Firestore ("users/{uid}" & "usernames/{username}") + Nostr Relay Backup
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class AuthManager {
    private static final String TAG = "AuthManager";

    private static final String KEY_SOVEREIGN_LOGGED_IN = "sovereign_logged_in";
    private static final String KEY_SOVEREIGN_UID = "sovereign_uid";

    private static volatile AuthManager instance;
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isLoggingOut = false;

    public interface ProfileRestoreCallback {
        void onResult(boolean hasCompleteProfile, @Nullable User restoredUser);
    }

    private AuthManager() {}

    public static AuthManager getInstance() {
        if (instance == null) {
            synchronized (AuthManager.class) {
                if (instance == null) {
                    instance = new AuthManager();
                }
            }
        }
        return instance;
    }

    /**
     * Authoritative check for whether an authenticated Sovereign or Phone session is active.
     */
    public boolean isLoggedIn(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        String uid = getCurrentUserId(appCtx);
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        PreferenceManager pm = new PreferenceManager(appCtx);
        UserPreferences up = new UserPreferences(appCtx);
        return Boolean.TRUE.equals(pm.getBoolean(KEY_SOVEREIGN_LOGGED_IN, false)) || up.isLoggedIn();
    }

    /**
     * Authoritative check for whether the current logged-in user has claimed a valid @username.
     * Filters out any legacy auto-generated placeholder handles unless explicitly confirmed.
     */
    public boolean hasCompleteProfile(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        UserPreferences up = new UserPreferences(appCtx);
        String uid = getCurrentUserId(appCtx);
        String username = up.getUsername();
        if (isValidClaimedUsername(username, uid)) {
            return true;
        }

        PreferenceManager pm = new PreferenceManager(appCtx);
        String pmUsername = pm.getString("username", null);
        if (isValidClaimedUsername(pmUsername, uid)) {
            up.updateUserField("username", pmUsername.trim());
            return true;
        }

        if (uid != null && !uid.isEmpty()) {
            try {
                SovereignProfileManifest manifest = EncryptedChatDatabase.getInstance(appCtx).getSovereignProfileManifest(uid);
                if (manifest != null && isValidClaimedUsername(manifest.username, uid)) {
                    SovereignIdentityAndDirectoryEngine.getInstance(appCtx).persistManifestLocally(manifest);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "SQLCipher manifest check failed: " + e.getMessage());
            }
        }
        return false;
    }

    public boolean isValidClaimedUsername(@Nullable String username, @Nullable String uid) {
        if (username == null) return false;
        String clean = username.trim();
        if (clean.isEmpty()) return false;
        if (uid != null && uid.startsWith("veil_") && uid.length() >= 13) {
            String autoSuffix = "veil_" + uid.substring(5, 13);
            if (clean.equalsIgnoreCase(autoSuffix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the deterministic user ID ("veil_<28_hex>" or "phone_<24_hex>").
     */
    @Nullable
    public String getCurrentUserId(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();

        PreferenceManager pm = new PreferenceManager(appCtx);
        String sovUid = pm.getString(KEY_SOVEREIGN_UID, null);
        if (sovUid != null && !sovUid.trim().isEmpty()) {
            FirebaseAuth.getInstance().setCurrentUserUid(sovUid.trim());
            return sovUid.trim();
        }

        UserPreferences up = new UserPreferences(appCtx);
        String upUid = up.getUserId();
        if (upUid != null && !upUid.trim().isEmpty()) {
            pm.putString(KEY_SOVEREIGN_UID, upUid.trim());
            pm.putBoolean(KEY_SOVEREIGN_LOGGED_IN, true);
            FirebaseAuth.getInstance().setCurrentUserUid(upUid.trim());
            return upUid.trim();
        }

        try {
            SovereignIdentityAndDirectoryEngine engine = SovereignIdentityAndDirectoryEngine.getInstance(appCtx);
            if (engine.isIdentityInitialized()) {
                String engineUid = engine.getCurrentUid();
                if (engineUid != null && !engineUid.trim().isEmpty()) {
                    pm.putString(KEY_SOVEREIGN_UID, engineUid.trim());
                    pm.putBoolean(KEY_SOVEREIGN_LOGGED_IN, true);
                    up.updateUserField("userId", engineUid.trim());
                    FirebaseAuth.getInstance().setCurrentUserUid(engineUid.trim());
                    return engineUid.trim();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Engine UID lookup failed: " + e.getMessage());
        }

        return null;
    }

    public void onSessionStarted(@NonNull Context context, @NonNull FirebaseUser firebaseUser) {
        String uid = getCurrentUserId(context);
        if (uid == null || uid.isEmpty()) {
            uid = firebaseUser.getUid();
        }
        if (uid != null && !uid.isEmpty()) {
            onSovereignIdentityInitialized(context, uid, hasCompleteProfile(context));
            initRealtimeEngines(context.getApplicationContext(), uid);
        }
    }

    public void onSovereignSessionStarted(@NonNull Context context, @NonNull SovereignProfileManifest manifest) {
        if (manifest.uid == null || manifest.uid.isEmpty()) return;
        Context appCtx = context.getApplicationContext();
        boolean hasValidUsername = isValidClaimedUsername(manifest.username, manifest.uid);
        onSovereignIdentityInitialized(appCtx, manifest.uid, hasValidUsername);
        if (hasValidUsername) {
            SovereignIdentityAndDirectoryEngine.getInstance(appCtx).persistManifestLocally(manifest);
        }
        initRealtimeEngines(appCtx, manifest.uid);
    }

    /**
     * Called when a 12-word Sovereign Identity is created or restored.
     */
    public void onSovereignIdentityInitialized(@NonNull Context context, @NonNull String uid, boolean hasProfile) {
        Context appCtx = context.getApplicationContext();
        PreferenceManager pm = new PreferenceManager(appCtx);
        pm.putBoolean(KEY_SOVEREIGN_LOGGED_IN, true);
        pm.putString(KEY_SOVEREIGN_UID, uid);
        pm.putString("userId", uid);

        FirebaseAuth.getInstance().setCurrentUserUid(uid);

        UserPreferences up = new UserPreferences(appCtx);
        User currentUser = up.getCurrentUser();
        if (currentUser == null || !uid.equals(currentUser.getUserId())) {
            User minimal = new User();
            minimal.setUserId(uid);
            minimal.setPhone("");
            minimal.setCountryCode("SOV");
            minimal.setOnline(true);
            up.saveUser(minimal);
        } else {
            up.updateUserField("userId", uid);
        }

        initRealtimeEngines(appCtx, uid);
        Log.i(TAG, "Sovereign session active for UID=" + uid + " (hasProfile=" + hasProfile + ")");
    }

    /**
     * Called when Phone OTP verification completes.
     */
    public void onSovereignPhoneSessionStarted(@NonNull Context context, @NonNull String uid, @NonNull String phoneNumber) {
        Context appCtx = context.getApplicationContext();
        PreferenceManager pm = new PreferenceManager(appCtx);
        pm.putBoolean(KEY_SOVEREIGN_LOGGED_IN, true);
        pm.putString(KEY_SOVEREIGN_UID, uid);
        pm.putString("userId", uid);
        pm.putString("phone_number", phoneNumber);

        FirebaseAuth.getInstance().setCurrentUserUid(uid);

        UserPreferences up = new UserPreferences(appCtx);
        User existing = up.getCurrentUser();
        if (existing == null || !uid.equals(existing.getUserId())) {
            User phoneUser = new User(uid, phoneNumber, "US");
            phoneUser.setOnline(true);
            up.saveUser(phoneUser);
        } else {
            up.updateUserField("userId", uid);
        }

        initRealtimeEngines(appCtx, uid);
        Log.i(TAG, "Phone session active for UID=" + uid);
    }

    private void initRealtimeEngines(@NonNull Context appCtx, @NonNull String uid) {
        try {
            WebRtcManager.getInstance(appCtx).init(uid);
        } catch (Throwable t) {
            Log.w(TAG, "WebRtcManager init notice: " + t.getMessage());
        }
        try {
            CallEngine.getInstance(appCtx).init(uid);
        } catch (Throwable t) {
            Log.w(TAG, "CallEngine init notice: " + t.getMessage());
        }
        try {
            VeilMeshDaemonService.start(appCtx);
        } catch (Throwable t) {
            Log.w(TAG, "VeilMeshDaemonService start notice: " + t.getMessage());
        }
    }

    /**
     * Synchronizes and persists the complete user profile across UserPreferences, PreferenceManager,
     * SQLCipher, and Firebase Firestore ("users/{uid}" and "usernames/{username}").
     */
    public void saveAndSyncUserProfile(
            @NonNull Context context,
            @NonNull String userId,
            @NonNull String username,
            @NonNull String displayName,
            @Nullable String bio,
            @Nullable String avatarPath,
            @Nullable String avatarBase64) {

        Context appCtx = context.getApplicationContext();
        String cleanUsername = username.trim().replaceFirst("^@", "");
        String cleanDisplay = displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : cleanUsername;
        String cleanBio = bio != null ? bio.trim() : "";

        UserPreferences up = new UserPreferences(appCtx);
        User user = up.getCurrentUser();
        if (user == null) {
            user = new User();
        }
        user.setUserId(userId);
        user.setUsername(cleanUsername);
        user.setDisplayName(cleanDisplay);
        user.setBio(cleanBio);
        if (avatarPath != null && !avatarPath.isEmpty()) {
            user.setPhotoUrl(avatarPath);
        } else if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            user.setPhotoUrl(avatarBase64);
        }
        if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            user.setAvatar(avatarBase64);
        }
        user.setOnline(true);
        user.setLastSeen(Timestamp.now());
        if (user.getJoinedAt() == null) {
            user.setJoinedAt(Timestamp.now());
        }
        up.saveUser(user);

        String resolvedAvatarPref = (avatarPath != null && !avatarPath.isEmpty())
                ? avatarPath
                : (avatarBase64 != null ? avatarBase64 : "");
        up.saveProfileFields(userId, cleanUsername, cleanDisplay, cleanBio, resolvedAvatarPref);

        PreferenceManager pm = new PreferenceManager(appCtx);
        pm.putString("userId", userId);
        pm.putString("username", cleanUsername);
        pm.putString("displayName", cleanDisplay);
        pm.putString("bio", cleanBio);
        if (avatarPath != null && !avatarPath.isEmpty()) {
            pm.putString("avatarPath", avatarPath);
        }
        if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            pm.putString("avatarBase64", avatarBase64);
        }

        SovereignIdentityAndDirectoryEngine.getInstance(appCtx).recordClaimedUsername(cleanUsername);

        Map<String, Object> userDoc = new HashMap<>();
        userDoc.put("userId", userId);
        userDoc.put("username", cleanUsername);
        userDoc.put("usernameLower", cleanUsername.toLowerCase());
        userDoc.put("displayName", cleanDisplay);
        userDoc.put("bio", cleanBio);
        userDoc.put("status", user.getStatus() != null ? user.getStatus() : "Hey there! I am using Veil");
        userDoc.put("isOnline", true);
        userDoc.put("lastSeen", Timestamp.now());
        if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            userDoc.put("avatar", avatarBase64);
            userDoc.put("photoUrl", avatarBase64);
        } else if (avatarPath != null && !avatarPath.isEmpty()) {
            userDoc.put("photoUrl", avatarPath);
        }
        try {
            String pubKey = SovereignIdentityAndDirectoryEngine.getInstance(appCtx).getCurrentPublicKeyBase64();
            if (pubKey != null && !pubKey.isEmpty()) {
                userDoc.put("publicKey", pubKey);
            }
        } catch (Exception ignored) {}

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").document(userId).set(userDoc, SetOptions.merge());

        Map<String, Object> usernameDoc = new HashMap<>();
        usernameDoc.put("userId", userId);
        usernameDoc.put("username", cleanUsername);
        usernameDoc.put("displayName", cleanDisplay);
        usernameDoc.put("updatedAt", System.currentTimeMillis());
        if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            usernameDoc.put("avatar", avatarBase64);
        }
        db.collection("usernames").document(cleanUsername.toLowerCase()).set(usernameDoc, SetOptions.merge());
    }

    /**
     * Restores or verifies user profile state on Splash or after 12-word recovery:
     * 1. Local UserPreferences / PreferenceManager / SQLCipher
     * 2. Firebase Firestore "users/{uid}" (instant 50ms restore)
     * 3. Surviving external backup / Nostr relay fallback
     */
    public void verifyOrRestoreProfile(@NonNull Context context, @NonNull ProfileRestoreCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final String uid = getCurrentUserId(appCtx);
        if (uid == null || uid.isEmpty()) {
            callback.onResult(false, null);
            return;
        }

        UserPreferences up = new UserPreferences(appCtx);
        if (hasCompleteProfile(appCtx)) {
            callback.onResult(true, up.getCurrentUser());
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                        DocumentSnapshot doc = task.getResult();
                        String username = doc.getString("username");
                        if (isValidClaimedUsername(username, uid)) {
                            String displayName = doc.getString("displayName");
                            String bio = doc.getString("bio");
                            String avatar = doc.getString("avatar");
                            String photoUrl = doc.getString("photoUrl");
                            String resolvedAvatar = (avatar != null && !avatar.isEmpty()) ? avatar : photoUrl;

                            String savedPath = null;
                            if (resolvedAvatar != null && !resolvedAvatar.isEmpty() && !resolvedAvatar.startsWith("/") && !resolvedAvatar.startsWith("http")) {
                                File f = SovereignIdentityAndDirectoryEngine.getInstance(appCtx).saveAvatarBase64ToLocalFile(uid, resolvedAvatar);
                                if (f != null) {
                                    savedPath = f.getAbsolutePath();
                                }
                            } else if (photoUrl != null && !photoUrl.isEmpty()) {
                                savedPath = photoUrl;
                            }

                            String chosenAvatarPref = (savedPath != null && !savedPath.isEmpty())
                                    ? savedPath
                                    : (resolvedAvatar != null ? resolvedAvatar : "");
                            up.saveProfileFields(
                                    uid,
                                    username.trim(),
                                    displayName != null && !displayName.isEmpty() ? displayName : username.trim(),
                                    bio != null ? bio : "",
                                    chosenAvatarPref
                            );

                            PreferenceManager pm = new PreferenceManager(appCtx);
                            pm.putString("userId", uid);
                            pm.putString("username", username.trim());
                            pm.putString("displayName", displayName != null && !displayName.isEmpty() ? displayName : username.trim());
                            pm.putString("bio", bio != null ? bio : "");
                            if (savedPath != null && !savedPath.isEmpty()) {
                                pm.putString("avatarPath", savedPath);
                            }

                            SovereignIdentityAndDirectoryEngine.getInstance(appCtx).recordClaimedUsername(username.trim());
                            callback.onResult(true, up.getCurrentUser());
                            return;
                        }
                    }

                    authExecutor.execute(() -> {
                        SovereignIdentityAndDirectoryEngine engine = SovereignIdentityAndDirectoryEngine.getInstance(appCtx);
                        SovereignProfileManifest manifest = engine.resolveProfileByUidBlocking(uid);
                        if (manifest != null && isValidClaimedUsername(manifest.username, uid)) {
                            engine.persistManifestLocally(manifest);
                            User restored = new UserPreferences(appCtx).getCurrentUser();
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                    callback.onResult(true, restored));
                        } else {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                    callback.onResult(false, null));
                        }
                    });
                });
    }

    public void signOut(@NonNull Context context) {
        logout(context);
    }

    public synchronized void logout(@NonNull Context context) {
        if (isLoggingOut) {
            return;
        }
        isLoggingOut = true;
        final Context appContext = context.getApplicationContext();

        authExecutor.execute(() -> {
            try {
                Log.i(TAG, "Starting atomic teardown of user session...");

                try {
                    SignalingClient.getInstance().disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error disconnecting SignalingClient", e);
                }

                try {
                    WebRtcManager.getInstance(appContext).disconnectAll();
                } catch (Exception e) {
                    Log.e(TAG, "Error disconnecting WebRtcManager", e);
                }

                try {
                    VeilCallService.stop(appContext);
                } catch (Exception ignored) {}

                try {
                    appContext.stopService(new Intent(appContext, VeilMeshDaemonService.class));
                } catch (Exception ignored) {}

                try {
                    EncryptedChatDatabase.getInstance(appContext).secureWipeAllData();
                } catch (Exception e) {
                    Log.e(TAG, "Error wiping EncryptedChatDatabase", e);
                }

                try {
                    SovereignIdentityAndDirectoryEngine.getInstance(appContext).wipeIdentity();
                } catch (Exception e) {
                    Log.e(TAG, "Error wiping SovereignIdentityAndDirectoryEngine", e);
                }

                try {
                    new UserPreferences(appContext).clearUserData();
                } catch (Exception ignored) {}

                try {
                    new PreferenceManager(appContext).clearAll();
                } catch (Exception ignored) {}

                try {
                    FirebaseAuth.getInstance().signOut();
                } catch (Exception ignored) {}

                Intent intent = new Intent(appContext, SplashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                appContext.startActivity(intent);
            } finally {
                isLoggingOut = false;
            }
        });
    }
}
