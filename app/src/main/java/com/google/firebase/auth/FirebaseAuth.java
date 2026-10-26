package com.google.firebase.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class FirebaseAuth {
    private static final FirebaseAuth INSTANCE = new FirebaseAuth();
    private volatile FirebaseUser currentUser = null;

    public static FirebaseAuth getInstance() {
        return INSTANCE;
    }

    @Nullable
    public FirebaseUser getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(@Nullable FirebaseUser user) {
        this.currentUser = user;
    }

    public void setCurrentUserUid(@Nullable String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            this.currentUser = null;
        } else {
            this.currentUser = new FirebaseUser(uid.trim(), "", "", uid.trim() + "@veil.local");
        }
    }

    public void signOut() {
        currentUser = null;
    }

    public Task<AuthResult> signInWithEmailAndPassword(@NonNull String email, @NonNull String password) {
        String uid = deriveUidFromEmail(email);
        final FirebaseUser user = new FirebaseUser(uid, "", "", email);
        this.currentUser = user;
        return Tasks.forResult(() -> user);
    }

    public Task<AuthResult> createUserWithEmailAndPassword(@NonNull String email, @NonNull String password) {
        String uid = deriveUidFromEmail(email);
        final FirebaseUser user = new FirebaseUser(uid, "", "", email);
        this.currentUser = user;
        return Tasks.forResult(() -> user);
    }

    public Task<AuthResult> signInWithCredential(@NonNull PhoneAuthCredential credential) {
        final FirebaseUser user = this.currentUser != null
                ? this.currentUser
                : new FirebaseUser("phone_verified", "", "", "");
        this.currentUser = user;
        return Tasks.forResult(() -> user);
    }

    private static String deriveUidFromEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "veil_anonymous";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(email.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return "veil_" + sb.substring(0, 28);
        } catch (Exception e) {
            return "veil_" + Math.abs(email.hashCode());
        }
    }
}
