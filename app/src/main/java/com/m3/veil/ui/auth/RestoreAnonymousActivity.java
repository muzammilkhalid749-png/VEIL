package com.m3.veil.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;

public class RestoreAnonymousActivity extends AppCompatActivity {

    private EditText phraseEditText;
    private Button restoreButton;
    private ProgressBar progressBar;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_restore_anonymous);

        mAuth = FirebaseAuth.getInstance();

        initializeViews();
        handleWindowInsets();
    }

    private void handleWindowInsets() {
        // Adds the system-bar insets on top of the layout's own padding. The previous
        // implementation replaced the padding outright, silently erasing the horizontal
        // padding these screens declare in XML.
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.restore_root));
    }

    private void initializeViews() {
        phraseEditText = findViewById(R.id.phraseEditText);
        restoreButton = findViewById(R.id.restoreButton);
        progressBar = findViewById(R.id.progressBar);

        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());

        restoreButton.setOnClickListener(v -> {
            String raw = phraseEditText.getText().toString();
            String phrase = com.m3.veil.utils.AnonymousAuthManager.normalizePhrase(raw);
            String[] words = phrase.split("\\s+");
            if (phrase.isEmpty() || words.length < 12) {
                int count = phrase.isEmpty() ? 0 : words.length;
                showSnackbar("Please enter all 12 words of your recovery phrase (found " + count + ")");
            } else {
                restoreAnonymousAccount(phrase);
            }
        });
    }

    private void restoreAnonymousAccount(String phrase) {
        showLoading(true);
        String email = com.m3.veil.utils.AnonymousAuthManager.getEmailFromPhrase(phrase);
        String password = com.m3.veil.utils.AnonymousAuthManager.getPasswordFromPhrase(phrase);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        showLoading(false);
                        com.google.firebase.auth.FirebaseUser user = task.getResult().getUser();
                        com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(RestoreAnonymousActivity.this, user);
                        saveRecoveryPhrase(phrase);
                        navigateToSplash();
                    } else {
                        Exception signInEx = task.getException();
                        android.util.Log.w("RestoreAnonymous", "Sign in attempt failed for email: " + email, signInEx);

                        // If network error, stop and report immediately
                        if (signInEx instanceof com.google.firebase.FirebaseNetworkException) {
                            showLoading(false);
                            showSnackbar("Network error. Please check your internet connection.");
                            return;
                        }

                        // If user record is not yet in Firebase Auth, restore credential deterministically
                        mAuth.createUserWithEmailAndPassword(email, password)
                                .addOnCompleteListener(this, createTask -> {
                                    showLoading(false);
                                    if (createTask.isSuccessful() && createTask.getResult() != null) {
                                        com.google.firebase.auth.FirebaseUser newUser = createTask.getResult().getUser();
                                        com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(RestoreAnonymousActivity.this, newUser);
                                        saveRecoveryPhrase(phrase);
                                        navigateToSplash();
                                    } else {
                                        Exception createEx = createTask.getException();
                                        android.util.Log.e("RestoreAnonymous", "Failed to restore anonymous account", createEx);
                                        if (createEx instanceof com.google.firebase.FirebaseNetworkException) {
                                            showSnackbar("Network error. Please check your internet connection.");
                                        } else if (createEx instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                                            showSnackbar("Recovery phrase password mismatch. Please verify your words.");
                                        } else if (createEx != null && createEx.getMessage() != null) {
                                            showSnackbar("Restore failed: " + createEx.getMessage());
                                        } else {
                                            showSnackbar("Invalid recovery phrase. Account not found.");
                                        }
                                    }
                                });
                    }
                });
    }

    private void saveRecoveryPhrase(String phrase) {
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            android.content.SharedPreferences securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "veil_encrypted_prefs",
                    masterKeyAlias,
                    RestoreAnonymousActivity.this,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            securePrefs.edit().putString("recovery_phrase", phrase).apply();
        } catch (Exception ignored) {
            try {
                getSharedPreferences("veil_encrypted_prefs_fallback", MODE_PRIVATE)
                        .edit().putString("recovery_phrase", phrase).apply();
            } catch (Exception ignored2) {}
        }
    }

    private void navigateToSplash() {
        Intent intent = new Intent(this, com.m3.veil.ui.splash.SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (restoreButton != null) {
            restoreButton.setEnabled(!show);
            restoreButton.setText(show ? "" : "Restore");
            restoreButton.setAlpha(show ? 0.85f : 1.0f);
        }
        if (phraseEditText != null) {
            phraseEditText.setEnabled(!show);
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
    }
}
