package com.m3.veil.ui.auth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.m3.veil.R;

public class RecoveryPhraseActivity extends AppCompatActivity {

    private String recoveryPhrase;
    private TextView phraseTextView;
    private Button copyButton, continueButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recovery_phrase);

        recoveryPhrase = getIntent().getStringExtra("recovery_phrase");
        boolean isViewOnly = getIntent().getBooleanExtra("is_view_only", false);

        if (recoveryPhrase == null || recoveryPhrase.isEmpty()) {
            try {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
                android.content.SharedPreferences securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "veil_encrypted_prefs",
                        masterKeyAlias,
                        this,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                recoveryPhrase = securePrefs.getString("recovery_phrase", null);
                if (recoveryPhrase == null || recoveryPhrase.isEmpty()) {
                    recoveryPhrase = com.m3.veil.utils.AnonymousAuthManager.generateRecoveryPhrase();
                    securePrefs.edit().putString("recovery_phrase", recoveryPhrase).apply();
                }
            } catch (Exception ignored) {
                recoveryPhrase = com.m3.veil.utils.AnonymousAuthManager.generateRecoveryPhrase();
            }
        }

        initializeViews(isViewOnly);
        handleWindowInsets();
    }
    
    private void handleWindowInsets() {
        // Adds the system-bar insets on top of the layout's own padding. The previous
        // implementation replaced the padding outright, silently erasing the horizontal
        // padding these screens declare in XML.
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.recovery_root));
    }

    private void initializeViews(boolean isViewOnly) {
        TextView phraseText = findViewById(R.id.phraseText);
        phraseText.setText(recoveryPhrase);

        Button copyButton = findViewById(R.id.copyButton);
        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Veil Recovery Phrase", recoveryPhrase);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });

        Button confirmButton = findViewById(R.id.confirmButton);
        if (isViewOnly) {
            confirmButton.setText("Done");
            confirmButton.setOnClickListener(v -> finish());
        } else {
            confirmButton.setText("Continue");
            confirmButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, UsernameActivity.class);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            });
        }
    }
}
