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
            String phrase = phraseEditText.getText().toString().trim();
            if (phrase.isEmpty()) {
                showSnackbar("Please enter your recovery phrase");
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
                    showLoading(false);
                    if (task.isSuccessful()) {
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
                        } catch (Exception ignored) {}
                        navigateToSplash();
                    } else {
                        showSnackbar("Invalid recovery phrase. Account not found.");
                    }
                });
    }

    private void navigateToSplash() {
        Intent intent = new Intent(this, com.m3.veil.ui.splash.SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        restoreButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        phraseEditText.setEnabled(!show);
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
    }
}
