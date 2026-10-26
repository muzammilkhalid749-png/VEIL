package com.m3.veil.manager;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.m3.veil.bridge.tasks.OnCompleteListener;
import com.m3.veil.bridge.tasks.Task;
import com.m3.veil.bridge.firestore.FirebaseFirestore;
import com.m3.veil.bridge.messaging.FirebaseMessaging;
import com.m3.veil.utils.AnonymousAuthManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.UserPreferences;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.WebRtcManager;

/**
 * 100% Sovereign Authentication & Session Manager for VEIL.
 * Decoupled completely from FirebaseAuth. Manages deterministic cryptographic identities,
 * local encrypted preferences, WebRTC P2P mesh initialization, and Nostr/P2P signaling.
 */
public class AuthManager {

    private static final String TAG = "AuthManager";

    public static class SessionUser {
        private final String uid;
        private final String displayName;
        private final String email;
        private final String phoneNumber;

        public SessionUser(String uid, String displayName, String email, String phoneNumber) {
            this.uid = uid != null ? uid : "";
            this.displayName = displayName != null ? displayName : "";
            this.email = email != null ? email : "";
            this.phoneNumber = phoneNumber != null ? phoneNumber : "";
        }

        public String getUid() {
            return uid;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getEmail() {
            return email;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }
    }

    public interface AuthCallback {
        void onSuccess(SessionUser user);
        void onFailure(Exception exception);
    }

    private static AuthManager instance;
    private static volatile String sCurrentUserId = null;
    private static volatile SessionUser sCurrentUser = null;
    private final FirebaseFirestore db;

    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    private AuthManager() {
        db = FirebaseFirestore.getInstance();
    }

    public boolean isLoggedIn() {
        return sCurrentUserId != null && !sCurrentUserId.isEmpty();
    }

    public boolean isLoggedIn(Context context) {
        if (sCurrentUserId != null && !sCurrentUserId.isEmpty()) return true;
        if (context == null) return false;
        try {
            UserPreferences prefs = new UserPreferences(context);
            if (prefs.isLoggedIn() && prefs.getUserId() != null && !prefs.getUserId().isEmpty()) {
                sCurrentUserId = prefs.getUserId();
                return true;
            }
            if (SovereignIdentityAndDirectoryEngine.getInstance(context).isLoggedIn()) {
                sCurrentUserId = SovereignIdentityAndDirectoryEngine.getInstance(context).getCurrentUserId();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "isLoggedIn check failed", e);
        }
        return false;
    }

    @Nullable
    public SessionUser getCurrentUser() {
        if (sCurrentUser != null) return sCurrentUser;
        if (sCurrentUserId != null && !sCurrentUserId.isEmpty()) {
            return new SessionUser(sCurrentUserId, "@" + sCurrentUserId, "", "");
        }
        return null;
    }

    @Nullable
    public SessionUser getCurrentUser(Context context) {
        if (context == null) return getCurrentUser();
        try {
            UserPreferences prefs = new UserPreferences(context);
            com.m3.veil.model.User user = prefs.getCurrentUser();
            if (user != null && user.getUserId() != null) {
                sCurrentUserId = user.getUserId();
                sCurrentUser = new SessionUser(user.getUserId(), user.getDisplayName(), "", user.getPhone());
                return sCurrentUser;
            }
            SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                    SovereignIdentityAndDirectoryEngine.getInstance(context).getLocalManifest();
            if (manifest != null && manifest.uid != null) {
                sCurrentUserId = manifest.uid;
                sCurrentUser = new SessionUser(manifest.uid, manifest.displayName, "", "");
                return sCurrentUser;
            }
        } catch (Exception ignored) {}
        return getCurrentUser();
    }

    @Nullable
    public String getCurrentUserId() {
        return sCurrentUserId;
    }

    @Nullable
    public String getCurrentUserId(Context context) {
        if (context == null) return sCurrentUserId;
        try {
            UserPreferences prefs = new UserPreferences(context);
            String uid = prefs.getUserId();
            if (uid != null && !uid.trim().isEmpty()) {
                sCurrentUserId = uid;
                return uid;
            }
            String sovUid = SovereignIdentityAndDirectoryEngine.getInstance(context).getCurrentUserId();
            if (sovUid != null && !sovUid.trim().isEmpty()) {
                sCurrentUserId = sovUid;
                return sovUid;
            }
        } catch (Exception ignored) {}
        return sCurrentUserId;
    }

    @NonNull
    public String getCurrentUserName(Context context) {
        if (context != null) {
            try {
                UserPreferences prefs = new UserPreferences(context);
                com.m3.veil.model.User u = prefs.getCurrentUser();
                if (u != null && u.getDisplayName() != null && !u.getDisplayName().isEmpty()) {
                    return u.getDisplayName();
                }
                SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        SovereignIdentityAndDirectoryEngine.getInstance(context).getLocalManifest();
                if (manifest != null && manifest.displayName != null && !manifest.displayName.isEmpty()) {
                    return manifest.displayName;
                }
            } catch (Exception ignored) {}
        }
        return sCurrentUserId != null ? ("@" + sCurrentUserId) : "Anonymous";
    }

    /**
     * Signs in or restores an anonymous sovereign account using the 12-word BIP-39 recovery phrase.
     */
    public void signInWithRecoveryPhrase(Context context, String recoveryPhrase, AuthCallback callback) {
        SovereignIdentityAndDirectoryEngine.getInstance(context).restoreAccount(context, recoveryPhrase, null,
                new SovereignIdentityAndDirectoryEngine.RestoreCallback() {
                    @Override
                    public void onSuccess(SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest, boolean hadExistingProfile) {
                        onSovereignSessionStarted(context, manifest);
                        SessionUser user = new SessionUser(manifest.uid, manifest.displayName, "", "");
                        if (callback != null) callback.onSuccess(user);
                    }

                    @Override
                    public void onError(String error) {
                        if (callback != null) callback.onFailure(new Exception(error));
                    }
                });
    }

    public void onSessionStarted(Context context, String uid) {
        if (uid == null || uid.isEmpty()) return;
        sCurrentUserId = uid;
        sCurrentUser = new SessionUser(uid, "@" + uid, "", "");

        // 1. Initialize WebRTC P2P Manager with authenticated UID
        WebRtcManager.getInstance(context).init(uid);

        // 2. Bring up Call Engine
        try {
            com.m3.veil.calling.CallEngine engine = com.m3.veil.calling.CallEngine.getInstance(context);
            engine.init(uid);
            com.m3.veil.model.Persona persona =
                    com.m3.veil.manager.VeilFeatureManager.getInstance(context).getActivePersona();
            if (persona != null && persona.getDisplayName() != null) {
                engine.setDisplayName(persona.getDisplayName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Call engine init failed", e);
        }

        // 3. Register FCM Token if available
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    String token = task.getResult();
                    java.util.Map<String, Object> tokenMap = new java.util.HashMap<>();
                    tokenMap.put("userId", uid);
                    tokenMap.put("fcmToken", token);
                    db.collection("users").document(uid).set(tokenMap, com.m3.veil.bridge.firestore.SetOptions.merge());
                }
            });
        } catch (Exception ignored) {}

        // 4. Ensure E2EE keypair exists
        try {
            com.m3.veil.utils.EncryptionManager.generateKeyPair();
            String pubKey = com.m3.veil.utils.EncryptionManager.getPublicKeyString();
            if (pubKey != null) {
                java.util.Map<String, Object> keyMap = new java.util.HashMap<>();
                keyMap.put("userId", uid);
                keyMap.put("publicKey", pubKey);
                db.collection("users").document(uid).set(keyMap, com.m3.veil.bridge.firestore.SetOptions.merge());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync user public key", e);
        }
    }

    public void onSessionStarted(Context context, SessionUser user) {
        if (user == null) return;
        sCurrentUser = user;
        onSessionStarted(context, user.getUid());
    }

    public void onSovereignSessionStarted(Context context, SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest) {
        if (manifest == null || manifest.uid == null || manifest.uid.isEmpty()) return;
        sCurrentUserId = manifest.uid;
        sCurrentUser = new SessionUser(manifest.uid, manifest.displayName, "", "");

        // 1. Initialize WebRTC P2P Manager with Sovereign UID
        WebRtcManager.getInstance(context).init(manifest.uid);

        // 2. Bring up Call Engine
        try {
            com.m3.veil.calling.CallEngine engine = com.m3.veil.calling.CallEngine.getInstance(context);
            engine.init(manifest.uid);
            if (manifest.displayName != null && !manifest.displayName.isEmpty()) {
                engine.setDisplayName(manifest.displayName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Call engine init failed for sovereign session", e);
        }

        // 3. Register FCM Token if available
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    String token = task.getResult();
                    java.util.Map<String, Object> tokenMap = new java.util.HashMap<>();
                    tokenMap.put("userId", manifest.uid);
                    tokenMap.put("fcmToken", token);
                    db.collection("users").document(manifest.uid).set(tokenMap, com.m3.veil.bridge.firestore.SetOptions.merge());
                }
            });
        } catch (Exception ignored) {}

        // 4. Ensure E2EE keypair exists
        try {
            com.m3.veil.utils.EncryptionManager.generateKeyPair();
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure keypair for sovereign session", e);
        }
    }

    public void onSovereignPhoneSessionStarted(Context context, String uid, String phoneNumber) {
        if (uid == null || uid.isEmpty()) return;
        sCurrentUserId = uid;
        sCurrentUser = new SessionUser(uid, "@" + uid, "", phoneNumber);

        // 1. Initialize WebRTC P2P Manager
        WebRtcManager.getInstance(context).init(uid);

        // 2. Initialize Call Engine
        try {
            com.m3.veil.calling.CallEngine engine = com.m3.veil.calling.CallEngine.getInstance(context);
            engine.init(uid);
        } catch (Exception e) {
            Log.e(TAG, "Call engine init failed for phone session", e);
        }

        // 3. Ensure E2EE keypair exists
        try {
            com.m3.veil.utils.EncryptionManager.generateKeyPair();
        } catch (Exception ignored) {}
    }

    public void signOut(Context context) {
        com.m3.veil.manager.PresenceManager.getInstance().markOfflineOnSignOut();
        if (sCurrentUserId != null && !sCurrentUserId.isEmpty()) {
            java.util.Map<String, Object> offlineMap = new java.util.HashMap<>();
            offlineMap.put("status", "offline");
            offlineMap.put("lastSeen", com.m3.veil.bridge.firestore.FieldValue.serverTimestamp());
            try {
                db.collection("users").document(sCurrentUserId).update(offlineMap);
            } catch (Exception ignored) {}
        }
        SignalingClient.getInstance().disconnect();
        try {
            com.m3.veil.calling.CallEngine.getInstance(context).shutdown();
        } catch (Exception ignored) {}
        try {
            UserPreferences prefs = new UserPreferences(context);
            prefs.clearUser();
        } catch (Exception ignored) {}
        sCurrentUserId = null;
        sCurrentUser = null;
    }
}
