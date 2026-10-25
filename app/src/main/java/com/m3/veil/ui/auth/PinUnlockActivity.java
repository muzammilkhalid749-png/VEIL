package com.m3.veil.ui.auth;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.m3.veil.R;
import com.m3.veil.ui.main.MainActivity;

import javax.crypto.SecretKey;
import java.security.SecureRandom;

public class PinUnlockActivity extends AppCompatActivity {

    private StringBuilder enteredPin = new StringBuilder();
    private static final String PREFS_NAME = "veil_encrypted_prefs";

    private TextView errorText;
    private LinearLayout pinDotsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pin_unlock);

        initializeViews();
        handleWindowInsets();
        setupClickListeners();
        updateUI();
    }

    private void handleWindowInsets() {
        // Adds the system-bar insets on top of the layout's own padding. The previous
        // implementation replaced the padding outright, silently erasing the horizontal
        // padding these screens declare in XML.
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.unlock_root));
    }

    private void initializeViews() {
        errorText = findViewById(R.id.errorText);
        pinDotsContainer = findViewById(R.id.pinDotsContainer);
    }

    private void setupClickListeners() {
        findViewById(R.id.button0).setOnClickListener(v -> handleNumberInput("0"));
        findViewById(R.id.button1).setOnClickListener(v -> handleNumberInput("1"));
        findViewById(R.id.button2).setOnClickListener(v -> handleNumberInput("2"));
        findViewById(R.id.button3).setOnClickListener(v -> handleNumberInput("3"));
        findViewById(R.id.button4).setOnClickListener(v -> handleNumberInput("4"));
        findViewById(R.id.button5).setOnClickListener(v -> handleNumberInput("5"));
        findViewById(R.id.button6).setOnClickListener(v -> handleNumberInput("6"));
        findViewById(R.id.button7).setOnClickListener(v -> handleNumberInput("7"));
        findViewById(R.id.button8).setOnClickListener(v -> handleNumberInput("8"));
        findViewById(R.id.button9).setOnClickListener(v -> handleNumberInput("9"));

        findViewById(R.id.buttonDelete).setOnClickListener(v -> handleDelete());
        findViewById(R.id.buttonClear).setOnClickListener(v -> handleClear());
    }

    private java.util.concurrent.ExecutorService authExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean isVerifying = false;

    private void handleNumberInput(String number) {
        if (isVerifying) return;
        if (enteredPin.length() < 6) {
            enteredPin.append(number);
            updatePinDots();
            errorText.setVisibility(View.GONE);

            // Read target PIN length from preferences (default 6 if not specified)
            int targetLength = 6;
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                        PREFS_NAME,
                        masterKeyAlias,
                        this,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                targetLength = sharedPreferences.getInt("pin_length", 6);
            } catch (Exception ignored) {}

            if (enteredPin.length() >= targetLength) {
                verifyPin();
            }
        }
    }

    private void handleDelete() {
        if (isVerifying) return;
        if (enteredPin.length() > 0) {
            enteredPin.setLength(enteredPin.length() - 1);
            updatePinDots();
        }
    }

    private void handleClear() {
        if (isVerifying) return;
        enteredPin.setLength(0);
        updatePinDots();
        errorText.setVisibility(View.GONE);
    }

    private void verifyPin() {
        if (isVerifying) return;
        isVerifying = true;
        final String pinAttempt = enteredPin.toString();

        // Check if user entered the configured Decoy PIN (Feature #104, #106)
        String decoyPin = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getDecoyPin();
        if (decoyPin != null && !decoyPin.isEmpty() && decoyPin.equals(pinAttempt)) {
            isVerifying = false;
            com.m3.veil.manager.VeilFeatureManager.getInstance(this).activateDecoyVault(true);
            android.widget.Toast.makeText(this, "Welcome back", android.widget.Toast.LENGTH_SHORT).show();
            navigateToMain();
            return;
        }

        authExecutor.execute(() -> {
            try {
                SharedPreferences sharedPreferences;
                try {
                    String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                    sharedPreferences = EncryptedSharedPreferences.create(
                            PREFS_NAME,
                            masterKeyAlias,
                            PinUnlockActivity.this,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    );
                } catch (Exception secEx) {
                    sharedPreferences = getSharedPreferences(PREFS_NAME + "_fallback", MODE_PRIVATE);
                }

                String encryptedMekStr = sharedPreferences.getString("encrypted_mek", null);
                String saltStr = sharedPreferences.getString("salt", null);
                String ivStr = sharedPreferences.getString("iv", null);

                if (encryptedMekStr == null || saltStr == null || ivStr == null) {
                    runOnUiThread(() -> {
                        isVerifying = false;
                        showError("Security data missing. Please reinstall.");
                    });
                    return;
                }

                byte[] salt = Base64.decode(saltStr, Base64.DEFAULT);
                byte[] iv = Base64.decode(ivStr, Base64.DEFAULT);
                byte[] encryptedMek = Base64.decode(encryptedMekStr, Base64.DEFAULT);

                // Derive KEK on worker thread
                javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                        pinAttempt.toCharArray(), salt, 100000, 256
                );
                SecretKey kek = factory.generateSecret(spec);

                // Try to decrypt
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kek, gcmSpec);

                cipher.doFinal(encryptedMek); // If this succeeds, PIN is correct

                // PIN is correct, navigate to main (deactivate decoy mode and mount primary/persona vault)
                runOnUiThread(() -> {
                    isVerifying = false;
                    com.m3.veil.manager.VeilFeatureManager.getInstance(PinUnlockActivity.this).activateDecoyVault(false);
                    navigateToMain();
                });
            } catch (Exception e) {
                // Decryption failed, PIN is incorrect
                runOnUiThread(() -> {
                    isVerifying = false;
                    showError("Incorrect PIN");
                    enteredPin.setLength(0);
                    updatePinDots();
                });
            }
        });
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void updateUI() {
        updatePinDots();
    }

    private void updatePinDots() {
        pinDotsContainer.removeAllViews();

        int pinLength = enteredPin.length();
        for (int i = 0; i < 6; i++) {
            View dot = new View(this);
            int dotSize = getResources().getDimensionPixelSize(R.dimen.pin_dot_size);
            int dotMargin = getResources().getDimensionPixelSize(R.dimen.pin_dot_margin);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(dotMargin, 0, dotMargin, 0);
            dot.setLayoutParams(params);

            if (i < pinLength) {
                dot.setBackgroundResource(R.drawable.pin_dot_filled);
            } else {
                dot.setBackgroundResource(R.drawable.pin_dot_empty);
            }

            pinDotsContainer.addView(dot);
        }
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }
    
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (authExecutor != null) {
            authExecutor.shutdown();
        }
    }
}
