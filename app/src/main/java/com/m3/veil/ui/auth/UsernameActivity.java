package com.m3.veil.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.R;
import com.m3.veil.VeilApplication;
import com.m3.veil.manager.PremiumUsernameManager;
import com.m3.veil.model.User;
import com.m3.veil.model.UserKeys;
import com.m3.veil.model.UserPrivacy;
import com.m3.veil.model.UserSettings;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.utils.UserPreferences;

public class UsernameActivity extends AppCompatActivity {

    private TextInputEditText usernameEditText, displayNameEditText;
    private TextInputLayout usernameInputLayout, displayNameInputLayout;
    private Button continueButton;
    private ProgressBar progressBar;
    private TextView usernameAvailabilityText, skipForNowText;

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private Handler validationHandler;
    private Runnable validationRunnable;
    private static final long VALIDATION_DELAY = 800;
    private Random random = new Random();

    // Track premium username selection
    private boolean isPremiumUsernameSelected = false;
    private String selectedPremiumUsername = "";

    // Username validation pattern
    private static final String USERNAME_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]{3,19}$";

    // Lists for random name generation
    private static final String[] ADJECTIVES = {
            "Swift", "Clever", "Brave", "Calm", "Witty", "Gentle", "Lucky", "Happy",
            "Bright", "Quick", "Bold", "Proud", "Wise", "Fair", "Grand", "Royal",
            "Silent", "Rapid", "Solid", "Vivid", "Noble", "True", "Wild", "Free",
            "Mighty", "Epic", "Cosmic", "Solar", "Lunar", "Urban", "Rural", "Alpha"
    };

    private static final String[] NOUNS = {
            "Phoenix", "Dragon", "Wolf", "Eagle", "Lion", "Tiger", "Bear", "Hawk",
            "Falcon", "Raven", "Fox", "Panther", "Leopard", "Falcon", "Hunter",
            "Warrior", "Guardian", "Explorer", "Traveler", "Seeker", "Walker",
            "Rider", "Knight", "Wizard", "Sage", "Mage", "Spirit", "Shadow", "Light",
            "Storm", "Thunder", "Blaze", "Ocean", "River", "Mountain", "Forest"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_username);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        validationHandler = new Handler();

        initializeViews();
        handleWindowInsets();
        setupClickListeners();
        setupTextWatchers();
        prefillDisplayName();
    }

    private void handleWindowInsets() {
        // Adds the system-bar insets on top of the layout's own padding. The previous
        // implementation replaced the padding outright, silently erasing the horizontal
        // padding these screens declare in XML.
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.username_root));
    }

    private void initializeViews() {
        usernameEditText = findViewById(R.id.usernameEditText);
        displayNameEditText = findViewById(R.id.displayNameEditText);
        usernameInputLayout = findViewById(R.id.usernameInputLayout);
        displayNameInputLayout = findViewById(R.id.displayNameInputLayout);
        continueButton = findViewById(R.id.continueButton);
        progressBar = findViewById(R.id.progressBar);
        usernameAvailabilityText = findViewById(R.id.usernameAvailabilityText);
        skipForNowText = findViewById(R.id.skipForNowText);

        // Update skip text
        skipForNowText.setText("Generate random profile");

        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());
    }

    private void prefillDisplayName() {
        String phoneNumber = mAuth.getCurrentUser().getPhoneNumber();
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            String lastFour = phoneNumber.length() > 4 ?
                    phoneNumber.substring(phoneNumber.length() - 4) : phoneNumber;
            displayNameEditText.setText("User" + lastFour);
        }
    }

    private void setupClickListeners() {
        continueButton.setOnClickListener(v -> {
            if (isPremiumUsernameSelected) {
                handlePremiumUsernameSelection();
            } else {
                saveUsernameAndContinue();
            }
        });

        skipForNowText.setOnClickListener(v -> generateRandomProfile());
    }

    private void setupTextWatchers() {
        usernameEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Reset premium selection state
                isPremiumUsernameSelected = false;
                selectedPremiumUsername = "";

                if (validationRunnable != null) {
                    validationHandler.removeCallbacks(validationRunnable);
                }

                validationRunnable = () -> validateUsername(s.toString());
                validationHandler.postDelayed(validationRunnable, VALIDATION_DELAY);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        displayNameEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateContinueButtonState();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void validateUsername(String username) {
        if (username.isEmpty()) {
            usernameAvailabilityText.setText("Enter your desired username");
            usernameAvailabilityText.setTextColor(getResources().getColor(R.color.text_secondary));
            usernameInputLayout.setError(null);
            updateContinueButtonState();
            return;
        }

        // Check for VEIL keyword restrictions FIRST
        if (PremiumUsernameManager.containsVeilKeyword(username)) {
            handleVeilRestrictedUsername(username);
            return;
        }

        // Check basic pattern first
        if (!username.matches(USERNAME_PATTERN)) {
            usernameAvailabilityText.setText("Invalid username format");
            usernameAvailabilityText.setTextColor(getResources().getColor(R.color.error_red));
            usernameInputLayout.setError("Must be 4-20 chars, start with letter, and contain only letters, numbers, underscores");
            updateContinueButtonState();
            return;
        }

        usernameInputLayout.setError(null);

        // Check if username is premium
        if (PremiumUsernameManager.isPremiumUsername(username)) {
            handlePremiumUsername(username);
            return;
        }

        // Check availability in Firestore for non-premium usernames
        checkUsernameAvailability(username);
    }

    private void handleVeilRestrictedUsername(String username) {
        isPremiumUsernameSelected = false;
        selectedPremiumUsername = "";

        usernameAvailabilityText.setText("🚫 Contains protected brand keyword");
        usernameAvailabilityText.setTextColor(getResources().getColor(R.color.error_red));
        usernameInputLayout.setError("Usernames with 'veil' are not allowed");

        // Show brand protection message
        showSnackbar(PremiumUsernameManager.getVeilRestrictionMessage());

        // Disable continue button
        continueButton.setText("Continue to VEIL");
        continueButton.setEnabled(false);
        continueButton.setAlpha(0.6f);
    }

    private void handlePremiumUsername(String username) {
        // Double-check it's not a VEIL keyword
        if (PremiumUsernameManager.containsVeilKeyword(username)) {
            handleVeilRestrictedUsername(username);
            return;
        }

        isPremiumUsernameSelected = true;
        selectedPremiumUsername = username;

        String premiumMessage = PremiumUsernameManager.getPremiumUsernameMessage(username);
        usernameAvailabilityText.setText("⭐ " + premiumMessage);
        usernameAvailabilityText.setTextColor(getResources().getColor(R.color.veil_accent));

        // Change button text for premium flow
        continueButton.setText("Get Premium →");
        continueButton.setEnabled(true);
        continueButton.setAlpha(1.0f);
    }

    private void checkUsernameAvailability(String username) {
        db.collection("users")
                .whereEqualTo("username", username.toLowerCase())
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        if (task.getResult().isEmpty()) {
                            usernameAvailabilityText.setText("✓ Username is available");
                            usernameAvailabilityText.setTextColor(getResources().getColor(R.color.veil_accent));
                        } else {
                            usernameAvailabilityText.setText("✗ Username is already taken");
                            usernameAvailabilityText.setTextColor(getResources().getColor(R.color.error_red));
                        }
                        updateContinueButtonState();
                    } else {
                        usernameAvailabilityText.setText("Error checking availability");
                        usernameAvailabilityText.setTextColor(getResources().getColor(R.color.error_red));
                    }
                });
    }

    private void updateContinueButtonState() {
        if (isPremiumUsernameSelected) {
            // Premium usernames always show enabled with special text
            continueButton.setText("Get Premium →");
            continueButton.setEnabled(true);
            continueButton.setAlpha(1.0f);
            return;
        }

        String username = usernameEditText.getText().toString().trim();
        boolean isValid = !username.isEmpty() &&
                username.matches(USERNAME_PATTERN) &&
                usernameAvailabilityText.getText().toString().contains("✓");

        continueButton.setText("Continue to VEIL");
        continueButton.setEnabled(isValid);
        continueButton.setAlpha(isValid ? 1.0f : 0.6f);
    }

    private void handlePremiumUsernameSelection() {
        // Show premium upsell dialog
        showPremiumUpsellDialog(selectedPremiumUsername);
    }

    private void showPremiumUpsellDialog(String premiumUsername) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(UsernameActivity.this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(UsernameActivity.this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        float dp = getResources().getDisplayMetrics().density;
        int padH = (int) (24 * dp);
        int padV = (int) (16 * dp);
        layout.setPadding(padH, padV, padH, padV * 2);

        View handle = new View(UsernameActivity.this);
        int handleW = (int) (36 * dp);
        int handleH = (int) (4 * dp);
        android.widget.LinearLayout.LayoutParams handleLp = new android.widget.LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (16 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        layout.addView(handle);

        TextView tvTitle = new TextView(UsernameActivity.this);
        tvTitle.setText("⭐ Unlock @" + premiumUsername);
        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(UsernameActivity.this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvTitle);

        com.m3.veil.manager.VeilFeatureManager fm = com.m3.veil.manager.VeilFeatureManager.getInstance(UsernameActivity.this);
        int balance = fm.getVeilCoins();
        int cost = 100;

        TextView tvSub = new TextView(UsernameActivity.this);
        tvSub.setText("\"" + premiumUsername + "\" is a rare Premium VEIL handle.\n\n"
                + "• Verified Cryptographic Privacy Badge\n"
                + "• High-priority WebRTC P2P signaling relay\n"
                + "• Your Wallet Balance: " + balance + " VeilCoins (Cost: " + cost + " VC)");
        tvSub.setTextColor(androidx.core.content.ContextCompat.getColor(UsernameActivity.this, R.color.textSecondary));
        tvSub.setTextSize(13.5f);
        tvSub.setLineSpacing(4, 1.25f);
        tvSub.setPadding(0, (int) (8 * dp), 0, (int) (20 * dp));
        layout.addView(tvSub);

        com.google.android.material.button.MaterialButton btnClaim =
                new com.google.android.material.button.MaterialButton(UsernameActivity.this);
        btnClaim.setText("🪙 Unlock @" + premiumUsername + " (" + cost + " VC)");
        btnClaim.setCornerRadius((int) (12 * dp));
        btnClaim.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
        btnClaim.setTextColor(0xFFFFFFFF);
        android.widget.LinearLayout.LayoutParams claimLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        claimLp.bottomMargin = (int) (10 * dp);
        btnClaim.setLayoutParams(claimLp);
        btnClaim.setOnClickListener(bv -> {
            if (!fm.spendCoins(cost)) {
                showSnackbar("Insufficient VeilCoins (" + balance + " VC). Using free handle instead.");
                sheet.dismiss();
                suggestFreeAlternative(premiumUsername);
                return;
            }
            fm.unlockBadge("⭐ Premium Handle Owner");
            sheet.dismiss();
            isPremiumUsernameSelected = false;
            saveUsernameAndContinue();
        });
        layout.addView(btnClaim);

        com.google.android.material.button.MaterialButton btnFree =
                new com.google.android.material.button.MaterialButton(
                        UsernameActivity.this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnFree.setText("✨ Use Free Alternative Instead");
        btnFree.setCornerRadius((int) (12 * dp));
        btnFree.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        btnFree.setOnClickListener(bv -> {
            sheet.dismiss();
            suggestFreeAlternative(premiumUsername);
        });
        layout.addView(btnFree);

        sheet.setContentView(layout);
        sheet.show();
    }

    private void suggestFreeAlternative(String premiumUsername) {
        String freeAlternative = generateFreeAlternative(premiumUsername);

        // Ensure the alternative doesn't contain VEIL
        while (PremiumUsernameManager.containsVeilKeyword(freeAlternative)) {
            freeAlternative = generateFreeAlternative(premiumUsername);
        }

        usernameEditText.setText(freeAlternative);
        usernameAvailabilityText.setText("✨ Suggested free alternative");
        usernameAvailabilityText.setTextColor(getResources().getColor(R.color.veil_accent));

        // Reset premium state since we're suggesting a free alternative
        isPremiumUsernameSelected = false;
        selectedPremiumUsername = "";
        updateContinueButtonState();
    }

    private String generateFreeAlternative(String premiumUsername) {
        // Remove any veil-related content first
        String cleanUsername = premiumUsername
                .replaceAll("(?i)veil", "") // Remove veil (case insensitive)
                .replaceAll("_+", "_") // Clean multiple underscores
                .replaceAll("^_|_$", ""); // Remove leading/trailing underscores

        if (cleanUsername.isEmpty() || cleanUsername.length() < 3) {
            // If removing veil makes it too short, use a different approach
            String[] modifiers = {"official", "real", "true", "the", "my", "your"};
            String[] suffixes = {"fan", "lover", "user", "world", "hub", "zone", "corner"};
            return modifiers[random.nextInt(modifiers.length)] + "_" + suffixes[random.nextInt(suffixes.length)];
        }

        // Add some modifiers to make it free
        String[] modifiers = {"official", "real", "true", "the", "my", "your"};
        String[] suffixes = {"fan", "lover", "user", "world", "hub", "zone", "corner"};

        if (premiumUsername.length() <= 5) {
            return cleanUsername + "_" + suffixes[random.nextInt(suffixes.length)];
        } else {
            return modifiers[random.nextInt(modifiers.length)] + "_" + cleanUsername;
        }
    }

    private void saveUsernameAndContinue() {
        String username = usernameEditText.getText().toString().trim();
        String displayName = displayNameEditText.getText().toString().trim();

        if (username.isEmpty() || !username.matches(USERNAME_PATTERN)) {
            showSnackbar("Please enter a valid username");
            return;
        }

        showLoading(true);

        db.collection("users")
                .whereEqualTo("username", username.toLowerCase())
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && !task.getResult().isEmpty()) {
                        showLoading(false);
                        usernameAvailabilityText.setText("✗ Username was just taken. Please choose another.");
                        usernameAvailabilityText.setTextColor(getResources().getColor(R.color.error_red));
                        return;
                    }

                    updateUserProfile(username, displayName);
                });
    }

    private void generateRandomProfile() {
        showLoading(true);
        skipForNowText.setEnabled(false);

        String randomDisplayName = generateRandomDisplayName();
        displayNameEditText.setText(randomDisplayName);

        // Ensure random username doesn't contain VEIL and is available
        generateAndCheckRandomUsername(randomDisplayName, 5);
    }

    private void generateAndCheckRandomUsername(String displayName, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            String fallbackUsername = "user" + System.currentTimeMillis();
            updateUserProfile(fallbackUsername, displayName);
            return;
        }

        String randomUsername = generateRandomUsername();

        // Skip premium usernames AND VEIL-related usernames
        if (PremiumUsernameManager.isPremiumUsername(randomUsername) ||
                PremiumUsernameManager.containsVeilKeyword(randomUsername)) {
            generateAndCheckRandomUsername(displayName, attemptsLeft - 1);
            return;
        }

        // Check availability in Firestore
        db.collection("users")
                .whereEqualTo("username", randomUsername.toLowerCase())
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult().isEmpty()) {
                        usernameEditText.setText(randomUsername);
                        usernameAvailabilityText.setText("✓ Generated random username");
                        usernameAvailabilityText.setTextColor(getResources().getColor(R.color.veil_accent));
                        updateUserProfile(randomUsername, displayName);
                    } else {
                        generateAndCheckRandomUsername(displayName, attemptsLeft - 1);
                    }
                });
    }
    private String generateRandomDisplayName() {
        String adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[random.nextInt(NOUNS.length)];
        return adjective + " " + noun;
    }

    private String generateRandomUsername() {
        // Ensure we don't generate VEIL-related usernames
        String adjective, noun;
        do {
            adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)].toLowerCase();
            noun = NOUNS[random.nextInt(NOUNS.length)].toLowerCase();
        } while (adjective.contains("veil") || noun.contains("veil"));

        int number = random.nextInt(999);

        String baseUsername = adjective + "_" + noun;

        if (baseUsername.length() > 15) {
            baseUsername = baseUsername.substring(0, 15);
        }

        return baseUsername + number;
    }
    private UserPreferences getUserPreferences() {
        return ((VeilApplication) getApplication()).getUserPreferences();
    }

    private void updateUserProfile(String username, String displayName) {
        String userId = mAuth.getCurrentUser().getUid();
        String phoneNumber = mAuth.getCurrentUser().getPhoneNumber();

        showLoading(true);

        // Generate E2E keys for this device
        EncryptionManager.generateKeyPair();
        String publicKey = EncryptionManager.getPublicKeyString();

        // Create all user documents in a batch write
        WriteBatch batch = db.batch();

        // 1. Update main user document
        Map<String, Object> userUpdates = new HashMap<>();
        userUpdates.put("userId", userId);
        userUpdates.put("username", username.toLowerCase());
        userUpdates.put("publicKey", publicKey);

        String finalDisplayName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : "VEIL User";
        userUpdates.put("displayName", finalDisplayName.trim());
        userUpdates.put("status", "online");
        userUpdates.put("lastSeen", FieldValue.serverTimestamp());

        batch.set(db.collection("users").document(userId), userUpdates, com.google.firebase.firestore.SetOptions.merge());

        // 2. Create userPrivacy document with default settings
        UserPrivacy userPrivacy = new UserPrivacy(userId);
        batch.set(db.collection("userPrivacy").document(userId), userPrivacy);

        // 3. Create userKeys document with security settings
        UserKeys userKeys = new UserKeys(userId);
        batch.set(db.collection("userKeys").document(userId), userKeys);

        // 4. Create userSettings document with app preferences
        UserSettings userSettings = new UserSettings(userId);
        batch.set(db.collection("userSettings").document(userId), userSettings);

        // Execute batch write
        String finalDisplayName1 = finalDisplayName;
        batch.commit()
                .addOnCompleteListener(task -> {
                    showLoading(false);
                    skipForNowText.setEnabled(true);
                    if (task.isSuccessful()) {
                        // Update local user preferences
                        updateLocalUserProfile(username, finalDisplayName1, phoneNumber, userId);

                        showSnackbar("Welcome to VEIL!");
                        navigateToPinSetup();
                    } else {
                        showSnackbar("Error saving profile. Please try again.");
                        Log.e("UsernameActivity", "Error creating user documents", task.getException());
                    }
                });
    }

    private void updateLocalUserProfile(String username, String displayName, String phoneNumber, String userId) {
        // Get current user or create new one
        User currentUser = getUserPreferences().getCurrentUser();
        if (currentUser == null) {
            currentUser = new User(userId, phoneNumber, "US"); // Default country code
        }

        // Update with new data
        currentUser.setUsername(username.toLowerCase());
        currentUser.setDisplayName(displayName);
        currentUser.setStatus("online");

        // Save to SharedPreferences
        getUserPreferences().saveUser(currentUser);

        Log.d("UsernameActivity", "User saved locally: " + username);
    }

    private void navigateToPinSetup() {
        Intent intent = new Intent(this, PinSetupActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        continueButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        if (show) {
            skipForNowText.setAlpha(0.6f);
        } else {
            skipForNowText.setAlpha(1.0f);
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (validationHandler != null && validationRunnable != null) {
            validationHandler.removeCallbacks(validationRunnable);
        }
    }
}