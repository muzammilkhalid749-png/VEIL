package com.m3.veil.ui.auth;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthProvider;
import com.m3.veil.R;
import com.m3.veil.utils.PhoneNumberUtils;

import java.util.List;

public class PhoneAuthActivity extends AppCompatActivity implements CountryAdapter.OnCountryClickListener {

    private EditText phoneNumberEditText;
    private Button continueButton;
    private Button anonymousJoinButton;
    private ProgressBar progressBar;
    private ProgressBar anonymousProgressBar;
    private LinearLayout countryPickerContainer;
    private TextView countryCodeText, flagEmoji;

    private FirebaseAuth mAuth;
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;

    private List<Country> countries;
    private Country selectedCountry;
    private boolean isLoading = false;
    private final android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable timeoutRunnable;

    private final java.util.concurrent.ExecutorService authWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_phone_auth);

        mAuth = FirebaseAuth.getInstance();
        initializeViews();
        handleWindowInsets();
        initializeCountries();
        setupClickListeners();
        setupTextWatchers();

        setSelectedCountry(CountryData.detectCountry(this));
        setupPhoneNumberFormatting();
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.auth_root));
    }

    private void initializeViews() {
        phoneNumberEditText = findViewById(R.id.phoneNumberEditText);
        continueButton = findViewById(R.id.continueButton);
        anonymousJoinButton = findViewById(R.id.anonymousJoinButton);
        progressBar = findViewById(R.id.progressBar);
        anonymousProgressBar = findViewById(R.id.anonymousProgressBar);
        countryPickerContainer = findViewById(R.id.countryPickerContainer);
        countryCodeText = findViewById(R.id.countryCodeText);
        flagEmoji = findViewById(R.id.flagEmoji);
    }

    private void initializeCountries() {
        countries = CountryData.getAllCountries();
    }

    private void setupClickListeners() {
        continueButton.setOnClickListener(v -> validateAndSendOTP());
        anonymousJoinButton.setOnClickListener(v -> signInAnonymously());
        countryPickerContainer.setOnClickListener(v -> showCountryPickerDialog());

        TextView restoreText = findViewById(R.id.restoreAccountText);
        restoreText.setOnClickListener(v -> showRestoreDialog());

        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());
    }

    private void showRestoreDialog() {
        Intent intent = new Intent(this, RestoreAnonymousActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void signInAnonymously() {
        if (isLoading) return;
        showLoading(true, true);

        authWorker.execute(() -> {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine engine =
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(PhoneAuthActivity.this);
            final String phrase = engine.createNewAnonymousAccount();
            final String uid = engine.getCurrentUid();

            if (uid != null && !uid.isEmpty()) {
                mAuth.setCurrentUserUid(uid);
                com.m3.veil.manager.AuthManager.getInstance()
                        .onSovereignIdentityInitialized(PhoneAuthActivity.this, uid, false);
            }

            try {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
                android.content.SharedPreferences securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "veil_encrypted_prefs",
                        masterKeyAlias,
                        PhoneAuthActivity.this,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                securePrefs.edit().putString("recovery_phrase", phrase).apply();
            } catch (Exception ignored) {}

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showLoading(false, true);
                Intent intent = new Intent(PhoneAuthActivity.this, RecoveryPhraseActivity.class);
                intent.putExtra("recovery_phrase", phrase);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            });
        });
    }

    private void setupTextWatchers() {
        phoneNumberEditText.addTextChangedListener(new TextWatcher() {
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

    private void showCountryPickerDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_country_picker);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);

        RecyclerView recyclerView = dialog.findViewById(R.id.countriesRecyclerView);
        EditText searchEditText = dialog.findViewById(R.id.searchEditText);
        View closeButton = dialog.findViewById(R.id.closeButton);

        CountryAdapter adapter = new CountryAdapter(countries, country -> {
            setSelectedCountry(country);
            dialog.dismiss();
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        closeButton.setOnClickListener(v -> dialog.dismiss());

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterCountries(s.toString(), adapter);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        dialog.show();
    }

    private void filterCountries(String query, CountryAdapter adapter) {
        List<Country> filtered = CountryData.getAllCountries();
        if (!query.isEmpty()) {
            filtered.removeIf(country ->
                    !country.getName().toLowerCase().contains(query.toLowerCase()) &&
                            !country.getDialCode().contains(query)
            );
        }
        adapter.updateCountries(filtered);
    }

    @Override
    public void onCountryClick(Country country) {
        setSelectedCountry(country);
    }

    private void updateContinueButtonState() {
        if (isLoading) return;
        String phoneNumber = phoneNumberEditText.getText().toString().trim();
        boolean isValid = phoneNumber.length() >= 7 && selectedCountry != null;
        continueButton.setEnabled(isValid);
        continueButton.setAlpha(isValid ? 1.0f : 0.6f);
    }

    private void validateAndSendOTP() {
        if (selectedCountry == null) {
            showSnackbar(getString(R.string.select_country));
            return;
        }

        String countryCode = selectedCountry.getDialCode();
        String rawPhoneNumber = phoneNumberEditText.getText().toString().trim();
        String digitsOnly = rawPhoneNumber.replaceAll("[^0-9]", "");

        switch (countryCode) {
            case "+92":
                if (digitsOnly.startsWith("0") && digitsOnly.length() == 11) {
                    String withoutZero = digitsOnly.substring(1);
                    if (!withoutZero.startsWith("3")) {
                        showSnackbar("Pakistani mobile numbers should start with 3 after removing leading zero");
                        return;
                    }
                } else if (digitsOnly.startsWith("3") && digitsOnly.length() == 10) {
                    // Valid format
                } else if (!digitsOnly.isEmpty()) {
                    showSnackbar("Pakistani numbers: 10 digits starting with 3 (3123456789) or 11 digits with zero (03123456789)");
                    return;
                }
                break;

            case "+1":
                if (digitsOnly.length() == 10 && digitsOnly.charAt(0) >= '0' && digitsOnly.charAt(0) <= '1') {
                    showSnackbar("US/Canada area codes cannot start with 0 or 1");
                    return;
                }
                break;

            case "+91":
                if (digitsOnly.length() == 10 && !digitsOnly.matches("^[6-9].*")) {
                    showSnackbar("Indian mobile numbers must start with 6, 7, 8, or 9");
                    return;
                }
                break;
        }

        PhoneNumberUtils.ValidationResult result =
                PhoneNumberUtils.validatePhoneNumberForFirebase(countryCode, rawPhoneNumber);

        if (!result.isValid) {
            showSnackbar(result.message);
            return;
        }

        String firebaseNumber = result.formattedNumber;
        showLoading(true, false);
        sendVerificationCode(firebaseNumber);
    }

    private void setSelectedCountry(Country country) {
        selectedCountry = country;
        countryCodeText.setText(country.getDialCode());
        flagEmoji.setText(country.getFlag());

        String currentText = phoneNumberEditText.getText().toString().trim();
        if (!currentText.isEmpty()) {
            String formatted = PhoneNumberUtils.formatPhoneNumberForDisplay(
                    country.getDialCode(), currentText);
            phoneNumberEditText.setText(formatted);
        }

        phoneNumberEditText.setHint(PhoneNumberUtils.getPhoneNumberHint(country.getDialCode()));
        updateContinueButtonState();
    }

    private void setupPhoneNumberFormatting() {
        PhoneNumberUtils.addPhoneNumberFormatter(
                phoneNumberEditText,
                selectedCountry != null ? selectedCountry.getDialCode() : "+1"
        );
    }

    private void sendVerificationCode(String phoneNumber) {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = () -> {
            if (isLoading && !isFinishing() && !isDestroyed()) {
                showLoading(false, false);
                showSnackbar(getString(R.string.verification_failed, "Request timed out. Please try again."));
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, 20000);

        com.m3.veil.utils.SovereignPhoneAuthBridge.getInstance().sendVerificationCode(
                this,
                phoneNumber,
                new com.m3.veil.utils.SovereignPhoneAuthBridge.SendCallback() {
                    @Override
                    public void onCodeSent(@NonNull String verificationId) {
                        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);
                        showLoading(false, false);
                        PhoneAuthActivity.this.verificationId = verificationId;

                        Intent intent = new Intent(PhoneAuthActivity.this, OTPVerificationActivity.class);
                        intent.putExtra("verification_id", verificationId);
                        intent.putExtra("phone_number", phoneNumber);
                        startActivity(intent);
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }

                    @Override
                    public void onFailure(@NonNull String errorMessage) {
                        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);
                        showLoading(false, false);
                        showSnackbar(getString(R.string.verification_failed, errorMessage));
                    }
                }
        );
    }

    private void signInWithPhoneAuthCredential(PhoneAuthCredential credential) {
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            navigateToMain();
                        } else {
                            showSnackbar(getString(R.string.auth_failed));
                        }
                    }
                });
    }

    private void showLoading(boolean show) {
        showLoading(show, false);
    }

    private void showLoading(boolean show, boolean isAnonymous) {
        isLoading = show;

        if (show) {
            if (isAnonymous) {
                if (anonymousProgressBar != null) anonymousProgressBar.setVisibility(View.VISIBLE);
                if (anonymousJoinButton != null) {
                    anonymousJoinButton.setText("");
                    anonymousJoinButton.setEnabled(false);
                }
                if (continueButton != null) {
                    continueButton.setEnabled(false);
                    continueButton.setAlpha(0.6f);
                }
            } else {
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (continueButton != null) {
                    continueButton.setText("");
                    continueButton.setEnabled(false);
                    continueButton.setAlpha(0.85f);
                }
                if (anonymousJoinButton != null) {
                    anonymousJoinButton.setEnabled(false);
                    anonymousJoinButton.setAlpha(0.6f);
                }
            }
            if (phoneNumberEditText != null) phoneNumberEditText.setEnabled(false);
            if (countryPickerContainer != null) countryPickerContainer.setEnabled(false);
        } else {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (anonymousProgressBar != null) anonymousProgressBar.setVisibility(View.GONE);
            if (continueButton != null) {
                continueButton.setText(R.string.continue_text);
            }
            if (anonymousJoinButton != null) {
                anonymousJoinButton.setText(R.string.join_anonymously);
                anonymousJoinButton.setEnabled(true);
                anonymousJoinButton.setAlpha(1.0f);
            }
            if (phoneNumberEditText != null) phoneNumberEditText.setEnabled(true);
            if (countryPickerContainer != null) countryPickerContainer.setEnabled(true);
            updateContinueButtonState();
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, com.m3.veil.ui.splash.SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timeoutHandler != null && timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }
        authWorker.shutdown();
    }
}