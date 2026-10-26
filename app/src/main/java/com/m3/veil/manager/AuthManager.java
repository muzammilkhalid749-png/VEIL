package com.m3.veil.manager;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;
import com.m3.veil.utils.AnonymousAuthManager;
import com.m3.veil.webrtc.SignalingClient;
import com.m3.veil.webrtc.WebRtcManager;

/**
 * Unified Authentication & Session Manager.
 * Orchestrates Firebase Authentication sessions (Anonymous or Credential-based),
 * binds user credentials, and links FCM tokens and WebRTC signaling upon authentication.
 */
public class AuthManager {

    private static final String TAG = "AuthManager";

    public interface AuthCallback {
        void onSuccess(FirebaseUser user);
        void onFailure(Exception exception);
    }

    private static AuthManager instance;
    private final FirebaseAuth mAuth;
    private final FirebaseFirestore db;

    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    private AuthManager() {
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    public boolean isLoggedIn() {
        return mAuth.getCurrentUser() != null;
    }

    public boolean isLoggedIn(Context context) {
        if (mAuth.getCurrentUser() != null) return true;
        try {
            com.m3.veil.utils.UserPreferences prefs = new com.m3.veil.utils.UserPreferences(context);
            return prefs.isLoggedIn() && prefs.getUserId() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public FirebaseUser getCurrentUser() {
        return mAuth.getCurrentUser();
    }

    public String getCurrentUserId() {
        if (mAuth.getCurrentUser() != null) {
            return mAuth.getCurrentUser().getUid();
        }
        return null;
    }

    public String getCurrentUserId(Context context) {
        if (mAuth.getCurrentUser() != null) {
            return mAuth.getCurrentUser().getUid();
        }
        try {
            com.m3.veil.utils.UserPreferences prefs = new com.m3.veil.utils.UserPreferences(context);
            return prefs.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Signs in or restores an anonymous account using the 12-word BIP-39 recovery phrase.
     */
    public void signInWithRecoveryPhrase(Context context, String recoveryPhrase, AuthCallback callback) {
        String email = AnonymousAuthManager.getEmailFromPhrase(recoveryPhrase);
        String password = AnonymousAuthManager.getPasswordFromPhrase(recoveryPhrase);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        FirebaseUser user = task.getResult().getUser();
                        onSessionStarted(context, user);
                        if (callback != null) callback.onSuccess(user);
                    } else {
                        // If account does not exist, create new anonymous credential
                        mAuth.createUserWithEmailAndPassword(email, password)
                                .addOnCompleteListener(createTask -> {
                                    if (createTask.isSuccessful() && createTask.getResult() != null) {
                                        FirebaseUser user = createTask.getResult().getUser();
                                        onSessionStarted(context, user);
                                        if (callback != null) callback.onSuccess(user);
                                    } else {
                                        if (callback != null) callback.onFailure(createTask.getException());
                                    }
                                });
                    }
                });
    }

    /**
     * Standard email/password or phone session sign-in.
     */
    public void signInWithEmail(Context context, String email, String password, AuthCallback callback) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        FirebaseUser user = task.getResult().getUser();
                        onSessionStarted(context, user);
                        if (callback != null) callback.onSuccess(user);
                    } else {
                        if (callback != null) callback.onFailure(task.getException());
                    }
                });
    }

    public void onSessionStarted(Context context, FirebaseUser user) {
        if (user == null) return;
        String uid = user.getUid();

        // 1. Initialize WebRTC P2P Manager with authenticated UID
        WebRtcManager.getInstance(context).init(uid);

        // 1b. Bring up the next-generation call engine: incoming-call delivery,
        //     accept-gated signalling, perfect negotiation, ICE restart and
        //     frame-level call E2EE.
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

        // 2. Fetch and register FCM Token for background wake-up notifications
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                String token = task.getResult();
                java.util.Map<String, Object> tokenMap = new java.util.HashMap<>();
                tokenMap.put("userId", uid);
                tokenMap.put("fcmToken", token);
                db.collection("users").document(uid).set(tokenMap, com.google.firebase.firestore.SetOptions.merge());
                Log.d(TAG, "Registered FCM token for user: " + uid);
            }
        });

        // 3. Self-heal user directory document (userId, username, displayName, online status)
        java.util.Map<String, Object> presenceMap = new java.util.HashMap<>();
        presenceMap.put("userId", uid);
        presenceMap.put("status", "online");
        presenceMap.put("lastSeen", com.google.firebase.firestore.FieldValue.serverTimestamp());
        try {
            com.m3.veil.utils.UserPreferences prefs = new com.m3.veil.utils.UserPreferences(context);
            com.m3.veil.model.User localUser = prefs.getCurrentUser();
            if (localUser != null && localUser.getUsername() != null && !localUser.getUsername().isEmpty()) {
                presenceMap.put("username", localUser.getUsername().toLowerCase());
                if (localUser.getDisplayName() != null && !localUser.getDisplayName().isEmpty()) {
                    presenceMap.put("displayName", localUser.getDisplayName());
                }
            }
        } catch (Exception ignored) {}
        db.collection("users").document(uid).set(presenceMap, com.google.firebase.firestore.SetOptions.merge());

        // Ensure fallback username exists if document had no username field
        db.collection("users").document(uid).get().addOnSuccessListener(doc -> {
            if (doc != null) {
                String existingUsername = doc.getString("username");
                String existingId = doc.getString("userId");
                if (existingUsername == null || existingUsername.trim().isEmpty() || existingId == null) {
                    java.util.Map<String, Object> repair = new java.util.HashMap<>();
                    repair.put("userId", uid);
                    if (existingUsername == null || existingUsername.trim().isEmpty()) {
                        com.m3.veil.model.Persona p = com.m3.veil.manager.VeilFeatureManager.getInstance(context).getActivePersona();
                        String fallbackHandle = (p != null && p.getDisplayName() != null && !p.getDisplayName().isEmpty())
                                ? p.getDisplayName().toLowerCase().replace(" ", "_").replace("@", "")
                                : ("anon_" + uid.substring(0, Math.min(6, uid.length())).toLowerCase());
                        repair.put("username", fallbackHandle);
                        repair.put("displayName", p != null && p.getDisplayName() != null ? p.getDisplayName() : ("@" + fallbackHandle));
                    }
                    db.collection("users").document(uid).set(repair, com.google.firebase.firestore.SetOptions.merge());
                }
            }
        });

        // 4. Ensure E2EE keypair exists and public key is synced to Firestore
        try {
            com.m3.veil.utils.EncryptionManager.generateKeyPair();
            String pubKey = com.m3.veil.utils.EncryptionManager.getPublicKeyString();
            if (pubKey != null) {
                java.util.Map<String, Object> keyMap = new java.util.HashMap<>();
                keyMap.put("userId", uid);
                keyMap.put("publicKey", pubKey);
                db.collection("users").document(uid).set(keyMap, com.google.firebase.firestore.SetOptions.merge());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync user public key", e);
        }
    }

    public void onSovereignSessionStarted(Context context, com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest) {
        if (manifest == null || manifest.uid == null || manifest.uid.isEmpty()) return;
        String uid = manifest.uid;

        // 1. Initialize WebRTC P2P Manager with Sovereign UID
        WebRtcManager.getInstance(context).init(uid);

        // 2. Bring up Call Engine
        try {
            com.m3.veil.calling.CallEngine engine = com.m3.veil.calling.CallEngine.getInstance(context);
            engine.init(uid);
            if (manifest.displayName != null && !manifest.displayName.isEmpty()) {
                engine.setDisplayName(manifest.displayName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Call engine init failed for sovereign session", e);
        }

        // 3. Register FCM Token if Firebase is present
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    String token = task.getResult();
                    java.util.Map<String, Object> tokenMap = new java.util.HashMap<>();
                    tokenMap.put("userId", uid);
                    tokenMap.put("fcmToken", token);
                    db.collection("users").document(uid).set(tokenMap, com.google.firebase.firestore.SetOptions.merge());
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
        if (mAuth.getCurrentUser() != null) {
            String uid = mAuth.getCurrentUser().getUid();
            java.util.Map<String, Object> offlineMap = new java.util.HashMap<>();
            offlineMap.put("status", "offline");
            offlineMap.put("lastSeen", com.google.firebase.firestore.FieldValue.serverTimestamp());
            db.collection("users").document(uid).update(offlineMap);
        }
        SignalingClient.getInstance().disconnect();
        try {
            com.m3.veil.calling.CallEngine.getInstance(context).shutdown();
        } catch (Exception ignored) { }
        mAuth.signOut();
    }
}
