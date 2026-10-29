package com.m3.veil.ui.auth;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.R;
import com.m3.veil.utils.EncryptionManager;

public class PinSetupActivity extends AppCompatActivity {

    private enum SetupState {
        ENTER_PIN,
        CONFIRM_PIN
    }

    private SetupState currentState = SetupState.ENTER_PIN;
    private StringBuilder enteredPin = new StringBuilder();
    private String initialPin = "";

    // Encryption constants
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS_MEK = "veil_mek_key";
    private static final String PREFS_NAME = "veil_encrypted_prefs";

    private TextView titleText, subtitleText, errorText;
    private Button confirmButton, skipButton;
    private ProgressBar progressBar;
    private LinearLayout pinDotsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pin_setup);

        initializeViews();
        handleWindowInsets();
        setupClickListeners();
        updateUI();
    }

    private void handleWindowInsets() {
        // Adds the system-bar insets on top of the layout's own padding. The previous
        // implementation replaced the padding outright, silently erasing the horizontal
        // padding these screens declare in XML.
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.pin_root));
    }

    private void initializeViews() {
        titleText = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        errorText = findViewById(R.id.errorText);
        confirmButton = findViewById(R.id.confirmButton);
        skipButton = findViewById(R.id.skipButton);
        progressBar = findViewById(R.id.progressBar);
        pinDotsContainer = findViewById(R.id.pinDotsContainer);

        if (getIntent().getBooleanExtra("is_change_pin", false) && skipButton != null) {
            skipButton.setVisibility(View.GONE);
        }

        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());
    }

    private void setupClickListeners() {
        // Number buttons
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

        // Control buttons
        findViewById(R.id.buttonDelete).setOnClickListener(v -> handleDelete());
        findViewById(R.id.buttonClear).setOnClickListener(v -> handleClear());

        skipButton.setOnClickListener(v -> handleSkip());
        confirmButton.setOnClickListener(v -> handleConfirm());
    }

    private void handleNumberInput(String number) {
        if (enteredPin.length() < 6) {
            enteredPin.append(number);
            updatePinDots();

            if (enteredPin.length() >= 4) {
                confirmButton.setEnabled(true);
            }

            // Clear any previous errors
            errorText.setVisibility(View.GONE);
        }
    }

    private void handleDelete() {
        if (enteredPin.length() > 0) {
            enteredPin.setLength(enteredPin.length() - 1);
            updatePinDots();

            if (enteredPin.length() < 4) {
                confirmButton.setEnabled(false);
            }
        }
    }

    private void handleClear() {
        enteredPin.setLength(0);
        updatePinDots();
        confirmButton.setEnabled(false);
        errorText.setVisibility(View.GONE);
    }

    private void handleConfirm() {
        if (currentState == SetupState.ENTER_PIN) {
            // First PIN entry
            if (enteredPin.length() >= 4 && enteredPin.length() <= 6) {
                initialPin = enteredPin.toString();
                enteredPin.setLength(0);
                currentState = SetupState.CONFIRM_PIN;
                updateUI();
            } else {
                showError("PIN must be 4-6 digits");
            }
        } else {
            // PIN confirmation
            if (enteredPin.toString().equals(initialPin)) {
                savePinAndGenerateKeys(initialPin);
            } else {
                showError("PINs don't match. Please try again.");
                resetPinEntry();
            }
        }
    }

    private void handleSkip() {
        // Store MEK in Android Keystore without PIN
        generateAndStoreMEKWithoutPin();
    }

    private final java.util.concurrent.ExecutorService setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void savePinAndGenerateKeys(String pin) {
        showLoading(true);

        setupExecutor.execute(() -> {
            try {
                // Step 1: Generate MEK (Master Encryption Key)
                SecretKey mek = generateMEK();

                // Step 2: Generate salt and derive KEK
                byte[] salt = generateSalt();
                SecretKey kek = deriveKEK(pin, salt);

                // Step 3: Encrypt MEK with KEK
                byte[] iv = generateIV();
                byte[] encryptedMEK = encryptMEK(mek, kek, iv);

                // Step 4: Store encrypted MEK and salt
                storeEncryptedData(encryptedMEK, salt, iv);

                // Step 5: Store PIN hash in Firestore for recovery
                storePINHash(pin);

                // Step 6: Upload public key to Firestore
                uploadPublicKey();

                runOnUiThread(this::navigateToMain);

            } catch (Exception e) {
                runOnUiThread(() -> {
                    showError("Failed to set up security: " + e.getMessage());
                    showLoading(false);
                });
            }
        });
    }

    private void generateAndStoreMEKWithoutPin() {
        showLoading(true);

        setupExecutor.execute(() -> {
            try {
                // Generate MEK and store in Android Keystore
                SecretKey mek = generateMEK();
                storeMEKInKeystore(mek);

                // Upload public key
                uploadPublicKey();

                runOnUiThread(this::navigateToMain);

            } catch (Exception e) {
                runOnUiThread(() -> {
                    showError("Failed to set up security: " + e.getMessage());
                    showLoading(false);
                });
            }
        });
    }

    // Encryption Methods
    private SecretKey generateMEK() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        return keyGenerator.generateKey();
    }

    private byte[] generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private byte[] generateIV() {
        byte[] iv = new byte[12]; // GCM recommended IV length
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private SecretKey deriveKEK(String pin, byte[] salt) throws Exception {
        // Using PBKDF2 for key derivation
        javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                pin.toCharArray(), salt, 100000, 256
        );
        return factory.generateSecret(spec);
    }

    private byte[] encryptMEK(SecretKey mek, SecretKey kek, byte[] iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, kek, spec);
        return cipher.doFinal(mek.getEncoded());
    }

    private void storeEncryptedData(byte[] encryptedMEK, byte[] salt, byte[] iv) {
        SharedPreferences sharedPreferences = null;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Self-heal if Android Keystore master key was invalidated after clearing app data
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    deleteSharedPreferences(PREFS_NAME);
                } else {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().commit();
                }
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                sharedPreferences = EncryptedSharedPreferences.create(
                        PREFS_NAME,
                        masterKeyAlias,
                        this,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception fallbackEx) {
                sharedPreferences = getSharedPreferences(PREFS_NAME + "_fallback", MODE_PRIVATE);
            }
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("encrypted_mek", Base64.encodeToString(encryptedMEK, Base64.DEFAULT));
        editor.putString("salt", Base64.encodeToString(salt, Base64.DEFAULT));
        editor.putString("iv", Base64.encodeToString(iv, Base64.DEFAULT));
        editor.putInt("pin_length", initialPin.length() > 0 ? initialPin.length() : 6);
        editor.putBoolean("pin_set", true);
        editor.apply();

        com.m3.veil.manager.VeilFeatureManager.getInstance(this).setMasterPin(initialPin);
        com.m3.veil.manager.PresenceManager.getInstance().markSessionPinUnlocked(true);
    }

    private void storeMEKInKeystore(SecretKey mek) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        // Generate a key in Android Keystore
        KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS_MEK,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        keyGenerator.init(keyGenParameterSpec);
        keyGenerator.generateKey();

        // Store pin_set = false in both encrypted prefs and feature manager
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences encPrefs = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            encPrefs.edit().putBoolean("pin_set", false).apply();
        } catch (Exception ignored) {
            getSharedPreferences(PREFS_NAME + "_fallback", MODE_PRIVATE).edit().putBoolean("pin_set", false).apply();
        }
        SharedPreferences prefs = getSharedPreferences("veil_secure", MODE_PRIVATE);
        prefs.edit().putBoolean("pin_set", false).apply();
        com.m3.veil.manager.VeilFeatureManager.getInstance(this).setMasterPin(null);
    }

    private void storePINHash(String pin) {
        try {
            // Generate server salt (in production, this should come from server)
            byte[] serverSalt = new byte[16];
            new SecureRandom().nextBytes(serverSalt);

            // Create HMAC-SHA256 of PIN
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    serverSalt, "HmacSHA256");
            mac.init(keySpec);
            byte[] pinHash = mac.doFinal(pin.getBytes());

            // Store in Firestore if available
            String userId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
            if (userId == null && FirebaseAuth.getInstance().getCurrentUser() != null) {
                userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            }
            if (userId != null) {
                Map<String, Object> pinData = new HashMap<>();
                pinData.put("pin_hash", Base64.encodeToString(pinHash, Base64.DEFAULT));
                pinData.put("server_salt", Base64.encodeToString(serverSalt, Base64.DEFAULT));
                pinData.put("created_at", com.google.firebase.Timestamp.now());

                try {
                    FirebaseFirestore.getInstance()
                            .collection("userSecurity")
                            .document(userId)
                            .set(pinData);
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            // Log error but don't fail the setup
            android.util.Log.e("PinSetup", "Failed to store PIN hash: " + e.getMessage());
        }
    }

    private void uploadPublicKey() {
        // Phase 3: Generate and upload E2EE public key to Firestore
        EncryptionManager.generateKeyPair();
        String publicKey = EncryptionManager.getPublicKeyString();
        
        String userId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (userId == null && FirebaseAuth.getInstance().getCurrentUser() != null) {
            userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
        if (userId != null) {
            Map<String, Object> keyData = new HashMap<>();
            keyData.put("publicKey", publicKey);
            keyData.put("uploaded_at", com.google.firebase.Timestamp.now());

            try {
                FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(userId)
                        .set(keyData, com.google.firebase.firestore.SetOptions.merge());
            } catch (Exception ignored) {}
        }
    }

    private void updateUI() {
        if (currentState == SetupState.ENTER_PIN) {
            titleText.setText(R.string.setup_secure_pin);
            subtitleText.setText(R.string.enter_pin_protect_hint);
            confirmButton.setText(R.string.continue_text);
        } else {
            titleText.setText(R.string.confirm_your_pin);
            subtitleText.setText(R.string.reenter_pin_hint);
            confirmButton.setText(R.string.confirm_pin);
        }

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

    private void resetPinEntry() {
        enteredPin.setLength(0);
        initialPin = "";
        currentState = SetupState.ENTER_PIN;
        updateUI();
        confirmButton.setEnabled(false);
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        skipButton.setEnabled(!show);
        confirmButton.setEnabled(!show && enteredPin.length() >= 4);
    }

    private void navigateToMain() {
        if (getIntent().getBooleanExtra("is_change_pin", false)) {
            android.widget.Toast.makeText(this, "Security PIN updated successfully", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}