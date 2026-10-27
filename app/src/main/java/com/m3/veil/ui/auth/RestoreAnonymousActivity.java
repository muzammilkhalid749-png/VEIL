package com.m3.veil.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.services.VeilMeshDaemonService;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.utils.PreferenceManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.UserPreferences;
import com.m3.veil.utils.WindowInsetsHelper;

import java.io.File;

/**
 * 12-Word BIP-39 Deterministic Account Recovery Activity (Signal Smart-Hybrid Architecture).
 * Reconstructs the exact deterministic UID ("veil_<28_hex>") and keys from the 12-word phrase,
 * fetches the signed profile manifest from Firebase Firestore ("users/{uid}") first with
 * local SQLCipher/Documents/Nostr fallback, writes userId, username, displayName, bio, and
 * avatarPath/avatar into BOTH UserPreferences and PreferenceManager, and navigates straight
 * to MainActivity.
 */
public class RestoreAnonymousActivity extends AppCompatActivity {

    private EditText phraseEditText;
    private Button restoreButton;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_restore_anonymous);

        WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.restore_root));

        phraseEditText = findViewById(R.id.phraseEditText);
        restoreButton = findViewById(R.id.restoreButton);
        progressBar = findViewById(R.id.progressBar);

        View backBtn = findViewById(R.id.backButton);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        phraseEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String normalized = SovereignIdentityAndDirectoryEngine.normalizePhrase(s.toString());
                String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
                boolean valid = words.length == 12
                        && SovereignIdentityAndDirectoryEngine.getInstance(RestoreAnonymousActivity.this).validatePhrase(normalized);
                restoreButton.setEnabled(valid);
                restoreButton.setAlpha(valid ? 1.0f : 0.6f);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        restoreButton.setOnClickListener(v -> attemptRestore());
    }

    private void attemptRestore() {
        String raw = phraseEditText.getText() != null ? phraseEditText.getText().toString() : "";
        String phrase = SovereignIdentityAndDirectoryEngine.normalizePhrase(raw);
        SovereignIdentityAndDirectoryEngine engine = SovereignIdentityAndDirectoryEngine.getInstance(this);

        String reason = engine.validatePhraseWithReason(phrase);
        if (reason != null) {
            Snackbar.make(findViewById(android.R.id.content), reason, Snackbar.LENGTH_LONG).show();
            return;
        }

        showLoading(true);

        engine.restoreAccount(this, phrase, null, new SovereignIdentityAndDirectoryEngine.RestoreCallback() {
            @Override
            public void onSuccess(SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest, boolean hadExistingProfile) {
                if (isFinishing() || isDestroyed()) return;
                showLoading(false);

                String uid = manifest != null && manifest.uid != null ? manifest.uid : engine.getCurrentUid();
                if (uid == null || uid.isEmpty()) {
                    Snackbar.make(findViewById(android.R.id.content), "Failed to derive Sovereign UID.", Snackbar.LENGTH_LONG).show();
                    return;
                }

                // Keep FirebaseAuth in sync with the deterministic Sovereign UID
                FirebaseAuth.getInstance().setCurrentUserUid(uid);

                UserPreferences userPrefs = new UserPreferences(RestoreAnonymousActivity.this);
                PreferenceManager prefManager = new PreferenceManager(RestoreAnonymousActivity.this);
                prefManager.putBoolean("sovereign_logged_in", true);
                prefManager.putString("sovereign_uid", uid);
                prefManager.putString("userId", uid);

                if (hadExistingProfile && manifest != null && manifest.username != null && !manifest.username.trim().isEmpty()) {
                    String cleanUsername = manifest.username.trim().replaceFirst("^@", "");
                    String cleanDisplayName = (manifest.displayName != null && !manifest.displayName.trim().isEmpty())
                            ? manifest.displayName.trim()
                            : cleanUsername;
                    String cleanBio = manifest.bio != null ? manifest.bio : "";
                    String avatarB64 = manifest.avatarBase64;
                    String avatarPath = null;

                    if (avatarB64 != null && !avatarB64.isEmpty()) {
                        File avatarFile = engine.saveAvatarBase64ToLocalFile(uid, avatarB64);
                        if (avatarFile != null && avatarFile.exists()) {
                            avatarPath = avatarFile.getAbsolutePath();
                        }
                    } else {
                        File existingFile = engine.getAvatarWebpFile(uid);
                        if (existingFile.exists() && existingFile.length() > 0) {
                            avatarPath = existingFile.getAbsolutePath();
                        }
                    }

                    // Write userId, username, displayName, bio, and avatarPath into BOTH UserPreferences and PreferenceManager
                    userPrefs.saveProfileFields(uid, cleanUsername, cleanDisplayName, cleanBio, avatarPath, avatarB64);
                    AuthManager.getInstance().saveAndSyncUserProfile(
                            RestoreAnonymousActivity.this,
                            uid,
                            cleanUsername,
                            cleanDisplayName,
                            cleanBio,
                            avatarPath,
                            avatarB64
                    );
                    prefManager.setOnboardingCompleted(true);

                    try {
                        VeilMeshDaemonService.start(RestoreAnonymousActivity.this);
                    } catch (Exception ignored) {}

                    Toast.makeText(
                            RestoreAnonymousActivity.this,
                            "Welcome back, @" + cleanUsername + "!",
                            Toast.LENGTH_SHORT
                    ).show();

                    Intent intent = new Intent(RestoreAnonymousActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finish();
                } else {
                    // Valid 12-word phrase, but no @username has been claimed yet -> go to UsernameActivity
                    AuthManager.getInstance().onSovereignIdentityInitialized(RestoreAnonymousActivity.this, uid, false);
                    Intent intent = new Intent(RestoreAnonymousActivity.this, UsernameActivity.class);
                    intent.putExtra("userId", uid);
                    intent.putExtra("is_restore", true);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finish();
                }
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                showLoading(false);
                Snackbar.make(
                        findViewById(android.R.id.content),
                        message != null ? message : "Identity restoration failed",
                        Snackbar.LENGTH_LONG
                ).show();
            }
        });
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (restoreButton != null) {
            restoreButton.setEnabled(!show);
            restoreButton.setText(show ? "" : "Restore Identity");
            restoreButton.setAlpha(show ? 0.85f : 1.0f);
        }
        if (phraseEditText != null) {
            phraseEditText.setEnabled(!show);
        }
    }
}
