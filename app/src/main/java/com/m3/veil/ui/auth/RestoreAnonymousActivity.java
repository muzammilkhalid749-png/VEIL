package com.m3.veil.ui.auth;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.m3.veil.R;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.utils.AnonymousAuthManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;

public class RestoreAnonymousActivity extends AppCompatActivity {

    private EditText phraseEditText;
    private Button restoreButton;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_restore_anonymous);

        initializeViews();
        handleWindowInsets();
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.restore_root));
    }

    private void initializeViews() {
        phraseEditText = findViewById(R.id.phraseEditText);
        restoreButton = findViewById(R.id.restoreButton);
        progressBar = findViewById(R.id.progressBar);

        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());

        restoreButton.setOnClickListener(v -> {
            String raw = phraseEditText.getText().toString();
            String phrase = SovereignIdentityAndDirectoryEngine.normalizePhrase(raw);
            String reason = SovereignIdentityAndDirectoryEngine.getInstance(this).validatePhraseWithReason(phrase);
            if (reason != null) {
                showSnackbar(reason);
            } else {
                restoreSovereignAccount(phrase);
            }
        });
    }

    private void restoreSovereignAccount(String phrase) {
        showLoading(true);

        SovereignIdentityAndDirectoryEngine engine = SovereignIdentityAndDirectoryEngine.getInstance(this);
        engine.restoreAccount(this, phrase, null, new SovereignIdentityAndDirectoryEngine.RestoreCallback() {
            @Override
            public void onSuccess(SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest, boolean hadExistingProfile) {
                showLoading(false);



                // Lossless restore: If user had an existing username, go directly to Main or PinUnlock
                if (manifest != null && manifest.username != null && !manifest.username.trim().isEmpty()) {
                    navigateToNextScreen();
                } else {
                    // Brand-new phrase, proceed to Username setup
                    navigateToUsername();
                }
            }

            @Override
            public void onError(String message) {
                showLoading(false);
                showSnackbar(message != null ? message : "Invalid recovery phrase. Please check your words.");
            }
        });
    }

    private void navigateToNextScreen() {
        try {
            SharedPreferences sharedPreferences;
            try {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
                sharedPreferences = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "veil_encrypted_prefs",
                        masterKeyAlias,
                        this,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception secEx) {
                sharedPreferences = getSharedPreferences("veil_encrypted_prefs_fallback", MODE_PRIVATE);
            }

            boolean isPinSet = sharedPreferences.getBoolean("pin_set", false)
                    || com.m3.veil.manager.VeilFeatureManager.getInstance(this).hasMasterPin();

            Intent intent;
            if (isPinSet) {
                intent = new Intent(RestoreAnonymousActivity.this, com.m3.veil.ui.auth.PinUnlockActivity.class);
            } else {
                intent = new Intent(RestoreAnonymousActivity.this, MainActivity.class);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        } catch (Exception e) {
            Intent intent = new Intent(RestoreAnonymousActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    }

    private void navigateToUsername() {
        Intent intent = new Intent(this, UsernameActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private androidx.appcompat.app.AlertDialog restoreProgressDialog;

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
        if (show) {
            if (restoreProgressDialog == null) {
                android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
                layout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                float dp = getResources().getDisplayMetrics().density;
                layout.setPadding((int) (24 * dp), (int) (20 * dp), (int) (24 * dp), (int) (20 * dp));
                layout.setGravity(android.view.Gravity.CENTER_VERTICAL);

                ProgressBar pb = new ProgressBar(this);
                layout.addView(pb);

                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText("Restoring Sovereign Identity from Mesh...");
                tv.setTextSize(15f);
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textPrimary));
                tv.setPadding((int) (16 * dp), 0, 0, 0);
                layout.addView(tv);

                restoreProgressDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setView(layout)
                        .setCancelable(false)
                        .create();
            }
            if (!isFinishing() && !isDestroyed()) {
                try { restoreProgressDialog.show(); } catch (Exception ignored) {}
            }
        } else {
            if (restoreProgressDialog != null && restoreProgressDialog.isShowing()) {
                try { restoreProgressDialog.dismiss(); } catch (Exception ignored) {}
            }
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
    }
}
