package com.google.firebase.auth;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

public class FirebaseAuth {
    private static final FirebaseAuth INSTANCE = new FirebaseAuth();
    private FirebaseUser currentUser = null;

    public static FirebaseAuth getInstance() {
        return INSTANCE;
    }

    public FirebaseUser getCurrentUser() {
        return currentUser;
    }

    public void signOut() {
        currentUser = null;
    }

    public Task<AuthResult> signInWithEmailAndPassword(@NonNull String email, @NonNull String password) {
        return Tasks.forException(new Exception("Firebase is unlinked. Use Sovereign Identity."));
    }

    public Task<AuthResult> createUserWithEmailAndPassword(@NonNull String email, @NonNull String password) {
        return Tasks.forException(new Exception("Firebase is unlinked. Use Sovereign Identity."));
    }

    public Task<AuthResult> signInWithCredential(@NonNull PhoneAuthCredential credential) {
        return Tasks.forException(new Exception("Firebase is unlinked. Use Sovereign Identity."));
    }
}
