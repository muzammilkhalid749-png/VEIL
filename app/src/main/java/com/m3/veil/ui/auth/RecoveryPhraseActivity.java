package com.m3.veil.ui.auth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.m3.veil.R;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;

public class RecoveryPhraseActivity extends AppCompatActivity {

    private String recoveryPhrase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recovery_phrase);

        recoveryPhrase = getIntent().getStringExtra("recovery_phrase");
        boolean isViewOnly = getIntent().getBooleanExtra("is_view_only", false);

        SovereignIdentityAndDirectoryEngine engine = SovereignIdentityAndDirectoryEngine.getInstance(this);

        if (recoveryPhrase == null || recoveryPhrase.isEmpty()) {
            recoveryPhrase = engine.loadRecoveryPhraseLocally(this);
            if (recoveryPhrase == null || recoveryPhrase.isEmpty() || !engine.validatePhrase(recoveryPhrase)) {
                recoveryPhrase = engine.generateMnemonic();
                engine.saveRecoveryPhraseLocally(this, recoveryPhrase);
            }
        } else {
            engine.saveRecoveryPhraseLocally(this, recoveryPhrase);
        }

        initializeViews(isViewOnly);
        handleWindowInsets();
    }
    
    private void handleWindowInsets() {
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
