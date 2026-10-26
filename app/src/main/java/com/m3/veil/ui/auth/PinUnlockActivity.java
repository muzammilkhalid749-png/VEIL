package com.m3.veil.ui.auth;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.ui.main.MainActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * PIN UNLOCK ACTIVITY (HARDWARE-BACKED VAULT & DURESS DECOY PIN GATE)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * - Pre-caches target PIN length off the main UI thread so digit presses are 0ms zero-jank.
 * - Supports both Primary Master PIN (mounts primary/persona SQLCipher vault) and Duress Decoy PIN
 *   (seamlessly mounts isolated `veil_vault_decoy.db`).
 * - Dynamic 4-dot or 6-dot indicator with tactile haptics and error shake animation.
 */
public class PinUnlockActivity extends AppCompatActivity {

    private final StringBuilder enteredPin = new StringBuilder();
    private static final String PREFS_NAME = "veil_encrypted_prefs";

    private TextView errorText;
    private LinearLayout pinDotsContainer;
    private volatile int cachedTargetPinLength = 6;
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private boolean isVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pin_unlock);

        initializeViews();
        handleWindowInsets();
        resolveTargetPinLengthAsync();
        setupClickListeners();
        updateUI();
    }

    private void resolveTargetPinLengthAsync() {
        String masterPin = VeilFeatureManager.getInstance(this).getMasterPin();
        if (masterPin != null && !masterPin.isEmpty()) {
            cachedTargetPinLength = masterPin.length();
            updatePinDots();
            return;
        }
        authExecutor.execute(() -> {
            int resolved = 6;
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                        PREFS_NAME,
                        masterKeyAlias,
                        PinUnlockActivity.this,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                resolved = sharedPreferences.getInt("pin_length", 6);
            } catch (Exception ignored) {
                SharedPreferences fallback = getSharedPreferences(PREFS_NAME + "_fallback", MODE_PRIVATE);
                resolved = fallback.getInt("pin_length", 6);
            }
            final int finalLen = Math.max(4, Math.min(6, resolved));
            cachedTargetPinLength = finalLen;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    updatePinDots();
                }
            });
        });
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.unlock_root));
    }

    private void initializeViews() {
        errorText = findViewById(R.id.errorText);
        pinDotsContainer = findViewById(R.id.pinDotsContainer);
    }

    private void setupClickListeners() {
        int[] btnIds = {
                R.id.button0, R.id.button1, R.id.button2, R.id.button3, R.id.button4,
                R.id.button5, R.id.button6, R.id.button7, R.id.button8, R.id.button9
        };
        for (int i = 0; i < btnIds.length; i++) {
            final String digit = String.valueOf(i);
            View btn = findViewById(btnIds[i]);
            if (btn != null) {
                btn.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    handleNumberInput(digit);
                });
            }
        }

        View btnDelete = findViewById(R.id.buttonDelete);
        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                handleDelete();
            });
        }
        View btnClear = findViewById(R.id.buttonClear);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                handleClear();
            });
        }
    }

    private void handleNumberInput(String number) {
        if (isVerifying) return;
        int maxDigits = Math.max(4, Math.min(6, cachedTargetPinLength));
        if (enteredPin.length() < maxDigits) {
            enteredPin.append(number);
            updatePinDots();
            errorText.setVisibility(View.GONE);

            String decoyPin = VeilFeatureManager.getInstance(this).getDecoyPin();
            if (decoyPin != null && !decoyPin.isEmpty() && decoyPin.equals(enteredPin.toString())) {
                verifyPin();
                return;
            }

            if (enteredPin.length() >= maxDigits) {
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

        // 1. Check Duress / Decoy Vault PIN
        String decoyPin = VeilFeatureManager.getInstance(this).getDecoyPin();
        if (decoyPin != null && !decoyPin.isEmpty() && decoyPin.equals(pinAttempt)) {
            isVerifying = false;
            VeilFeatureManager.getInstance(this).activateDecoyVault(true);
            Toast.makeText(this, "Welcome back", Toast.LENGTH_SHORT).show();
            navigateToMain();
            return;
        }

        // 2. Check Master PIN in VeilFeatureManager
        if (VeilFeatureManager.getInstance(this).verifyMasterPin(pinAttempt)) {
            isVerifying = false;
            VeilFeatureManager.getInstance(this).activateDecoyVault(false);
            navigateToMain();
            return;
        }

        // 3. Verify against PBKDF2-HMAC-SHA256 KEK + AES-256-GCM MEK in EncryptedSharedPreferences
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
                    runOnUiThread(this::onIncorrectPin);
                    return;
                }

                byte[] salt = Base64.decode(saltStr, Base64.DEFAULT);
                byte[] iv = Base64.decode(ivStr, Base64.DEFAULT);
                byte[] encryptedMek = Base64.decode(encryptedMekStr, Base64.DEFAULT);

                javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                        pinAttempt.toCharArray(), salt, 100000, 256
                );
                SecretKey kek = factory.generateSecret(spec);

                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kek, gcmSpec);

                cipher.doFinal(encryptedMek);

                runOnUiThread(() -> {
                    isVerifying = false;
                    VeilFeatureManager.getInstance(PinUnlockActivity.this).setMasterPin(pinAttempt);
                    VeilFeatureManager.getInstance(PinUnlockActivity.this).activateDecoyVault(false);
                    navigateToMain();
                });
            } catch (Exception e) {
                runOnUiThread(this::onIncorrectPin);
            }
        });
    }

    private void onIncorrectPin() {
        isVerifying = false;
        showError("Incorrect PIN");
        shakePinDots();
        enteredPin.setLength(0);
        updatePinDots();
    }

    private void shakePinDots() {
        if (pinDotsContainer == null) return;
        pinDotsContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pinDotsContainer.animate()
                .translationXBy(18f)
                .setDuration(45)
                .withEndAction(() -> pinDotsContainer.animate()
                        .translationXBy(-36f)
                        .setDuration(45)
                        .withEndAction(() -> pinDotsContainer.animate()
                                .translationXBy(18f)
                                .setDuration(45)
                                .start())
                        .start())
                .start();
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
        if (pinDotsContainer == null) return;
        pinDotsContainer.removeAllViews();

        int totalDots = Math.max(4, Math.min(6, cachedTargetPinLength));
        int pinLength = enteredPin.length();
        for (int i = 0; i < totalDots; i++) {
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
        authExecutor.shutdown();
    }
}
