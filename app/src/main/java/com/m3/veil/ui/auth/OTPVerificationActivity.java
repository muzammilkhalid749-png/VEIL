package com.m3.veil.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import com.m3.veil.R;
import com.m3.veil.model.User;
import com.m3.veil.model.UserKeys;
import com.m3.veil.model.UserPrivacy;
import com.m3.veil.model.UserSettings;
import com.m3.veil.utils.SovereignPhoneAuthBridge;
import com.mukeshsolanki.OtpView;

import java.util.Locale;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * OTP VERIFICATION ACTIVITY (SIGNAL SMART-HYBRID PHONE VERIFICATION)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * - Safe phone number masking for all international E.164 lengths (prevents negative substring crash).
 * - Live TextWatcher + OtpCompletionListener for responsive button state.
 * - Includes 1-tap Sovereign Code quick-fill pill so local/emulator verification works in 1 tap.
 */
public class OTPVerificationActivity extends AppCompatActivity {

    private OtpView otpView;
    private Button verifyButton;
    private ProgressBar progressBar;
    private TextView phoneNumberText, timerText, resendCodeText, tvSovereignOtpHelper;

    private FirebaseAuth mAuth;
    private String verificationId;
    private String phoneNumber;
    private PhoneAuthProvider.ForceResendingToken resendToken;

    private CountDownTimer countDownTimer;
    private boolean isResendEnabled = false;
    private boolean isAutoVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_otp_verification);

        mAuth = FirebaseAuth.getInstance();
        initializeViews();
        handleWindowInsets();
        getIntentData();
        setupClickListeners();
        setupOtpListener();
        startOTPTimer();
        displayPhoneNumber();
        refreshSovereignHelperCode();
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.otp_root));
    }

    private void initializeViews() {
        otpView = findViewById(R.id.otpView);
        verifyButton = findViewById(R.id.verifyButton);
        progressBar = findViewById(R.id.progressBar);
        phoneNumberText = findViewById(R.id.phoneNumberText);
        timerText = findViewById(R.id.timerText);
        resendCodeText = findViewById(R.id.resendCodeText);
        tvSovereignOtpHelper = findViewById(R.id.tvSovereignOtpHelper);

        View backBtn = findViewById(R.id.backButton);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> onBackPressed());
        }

        verifyButton.setEnabled(false);
        verifyButton.setAlpha(0.6f);
    }

    private void getIntentData() {
        Intent intent = getIntent();
        verificationId = intent.getStringExtra("verification_id");
        phoneNumber = intent.getStringExtra("phone_number");
        resendToken = intent.getParcelableExtra("resend_token");
    }

    private void displayPhoneNumber() {
        if (phoneNumber != null && phoneNumberText != null) {
            phoneNumberText.setText(maskPhoneNumber(phoneNumber));
        }
    }

    /**
     * Safely masks international E.164 phone numbers of any length (7 to 15 digits)
     * without throwing StringIndexOutOfBoundsException.
     */
    private String maskPhoneNumber(String rawPhone) {
        if (rawPhone == null) return "";
        String trimmed = rawPhone.trim();
        if (trimmed.length() <= 6) return trimmed;

        String lastFour = trimmed.substring(trimmed.length() - 4);
        int prefixEnd = Math.max(1, trimmed.length() - 10);
        if (prefixEnd >= trimmed.length() - 4) {
            prefixEnd = Math.min(3, trimmed.length() - 4);
        }
        String prefix = trimmed.substring(0, prefixEnd);
        return prefix + " •••-•••-" + lastFour;
    }

    private void refreshSovereignHelperCode() {
        if (tvSovereignOtpHelper == null) return;
        final String activeCode = SovereignPhoneAuthBridge.getInstance()
                .getActiveTestCode(verificationId != null ? verificationId : "");
        tvSovereignOtpHelper.setText("🔐 Sovereign Code: " + activeCode + " (Tap to auto-fill)");
        tvSovereignOtpHelper.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (otpView != null) {
                otpView.setText(activeCode);
            }
        });
    }

    private void setupClickListeners() {
        verifyButton.setOnClickListener(v -> verifyOTP());

        resendCodeText.setOnClickListener(v -> {
            if (isResendEnabled) {
                resendOTP();
            }
        });
    }

    private void setupOtpListener() {
        otpView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean ready = s != null && s.toString().trim().length() == 6;
                verifyButton.setEnabled(ready);
                verifyButton.setAlpha(ready ? 1.0f : 0.6f);
                if (!ready) {
                    isAutoVerifying = false;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        otpView.setOtpCompletionListener(otp -> {
            verifyButton.setEnabled(true);
            verifyButton.setAlpha(1.0f);

            if (!isAutoVerifying) {
                isAutoVerifying = true;
                verifyOTP();
            }
        });
    }

    private void startOTPTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(120000, 1000) {
            public void onTick(long millisUntilFinished) {
                String time = String.format(Locale.getDefault(), "%02d:%02d",
                        millisUntilFinished / 60000,
                        (millisUntilFinished % 60000) / 1000);
                timerText.setText(time);
            }

            public void onFinish() {
                timerText.setVisibility(View.GONE);
                isResendEnabled = true;
                resendCodeText.setTextColor(ContextCompat.getColor(OTPVerificationActivity.this, R.color.veil_accent));
                resendCodeText.setClickable(true);
            }
        }.start();
    }

    private void verifyOTP() {
        String otp = otpView.getText() != null ? otpView.getText().toString().trim() : "";

        if (otp.length() != 6) {
            showSnackbar(getString(R.string.enter_valid_otp));
            isAutoVerifying = false;
            return;
        }

        showLoading(true);

        SovereignPhoneAuthBridge.getInstance().verifyCode(
                this,
                verificationId != null ? verificationId : "",
                phoneNumber != null ? phoneNumber : "",
                otp,
                new SovereignPhoneAuthBridge.VerifyCallback() {
                    @Override
                    public void onSuccess(@NonNull String uid, boolean hasExistingUsername) {
                        showLoading(false);
                        if (hasExistingUsername) {
                            navigateToMain();
                        } else {
                            navigateToUsername(uid);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull String errorMessage) {
                        showLoading(false);
                        isAutoVerifying = false;
                        shakeOtpView();
                        showSnackbar(errorMessage);
                    }
                }
        );
    }

    private void signInWithPhoneAuthCredential(PhoneAuthCredential credential) {
        navigateAfterVerification();
    }

    private void shakeOtpView() {
        otpView.animate()
                .translationXBy(20)
                .setDuration(50)
                .withEndAction(() -> otpView.animate()
                        .translationXBy(-40)
                        .setDuration(50)
                        .withEndAction(() -> otpView.animate()
                                .translationXBy(20)
                                .setDuration(50)
                                .start())
                        .start())
                .start();
    }

    private void resendOTP() {
        showSnackbar(getString(R.string.sending_new_code));
        isResendEnabled = false;
        resendCodeText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        resendCodeText.setClickable(false);
        timerText.setVisibility(View.VISIBLE);

        otpView.setText("");
        verifyButton.setEnabled(false);
        verifyButton.setAlpha(0.6f);

        SovereignPhoneAuthBridge.getInstance().sendVerificationCode(
                this,
                phoneNumber != null ? phoneNumber : "",
                new SovereignPhoneAuthBridge.SendCallback() {
                    @Override
                    public void onCodeSent(@NonNull String newVerificationId) {
                        verificationId = newVerificationId;
                        refreshSovereignHelperCode();
                        startOTPTimer();
                        showSnackbar(getString(R.string.code_sent_success));
                    }

                    @Override
                    public void onFailure(@NonNull String errorMessage) {
                        showSnackbar(getString(R.string.fail_resend_code, errorMessage));
                        isResendEnabled = true;
                        resendCodeText.setTextColor(ContextCompat.getColor(OTPVerificationActivity.this, R.color.veil_accent));
                        resendCodeText.setClickable(true);
                    }
                }
        );
    }

    private void navigateAfterVerification() {
        String currentUserId = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null) {
            navigateToUsername();
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(currentUserId)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult().exists()) {
                        User user = task.getResult().toObject(User.class);
                        if (user != null && user.getUsername() != null && !user.getUsername().isEmpty()) {
                            navigateToMain();
                        } else {
                            navigateToUsername(currentUserId);
                        }
                    } else {
                        createUserDocumentAndNavigate();
                    }
                });
    }

    private void createUserDocumentAndNavigate() {
        com.google.firebase.auth.FirebaseUser fUser = FirebaseAuth.getInstance().getCurrentUser();
        String userId = fUser != null
                ? fUser.getUid()
                : com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (userId == null) {
            navigateToUsername();
            return;
        }
        String phone = fUser != null && fUser.getPhoneNumber() != null
                ? fUser.getPhoneNumber()
                : (this.phoneNumber != null ? this.phoneNumber : "");

        String countryCode = extractCountryCode(phone);
        User newUser = new User(userId, phone, countryCode);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .set(newUser)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        createAdditionalUserDocuments(userId);
                    } else {
                        showSnackbar(getString(R.string.error_create_profile));
                    }
                });
    }

    private void createAdditionalUserDocuments(String userId) {
        WriteBatch batch = FirebaseFirestore.getInstance().batch();

        UserPrivacy privacy = new UserPrivacy(userId);
        batch.set(FirebaseFirestore.getInstance().collection("userPrivacy").document(userId), privacy);

        UserKeys keys = new UserKeys(userId);
        batch.set(FirebaseFirestore.getInstance().collection("userKeys").document(userId), keys);

        UserSettings settings = new UserSettings(userId);
        batch.set(FirebaseFirestore.getInstance().collection("userSettings").document(userId), settings);

        batch.commit().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                navigateToUsername(userId);
            } else {
                showSnackbar(getString(R.string.error_setup_prefs));
            }
        });
    }

    private String extractCountryCode(String phoneNumber) {
        if (phoneNumber != null && phoneNumber.startsWith("+92")) {
            return "PK";
        } else if (phoneNumber != null && phoneNumber.startsWith("+1")) {
            return "US";
        } else if (phoneNumber != null && phoneNumber.startsWith("+44")) {
            return "GB";
        }
        return "US";
    }

    private void navigateToUsername() {
        navigateToUsername(null);
    }

    private void navigateToUsername(String userId) {
        Intent intent = new Intent(this, UsernameActivity.class);
        if (userId != null && !userId.isEmpty()) {
            intent.putExtra("userId", userId);
        }
        intent.putExtra("is_restore", false);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, com.m3.veil.ui.splash.SplashActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (verifyButton != null) {
            verifyButton.setEnabled(!show);
            verifyButton.setText(show ? "" : getString(R.string.verify_continue));
            boolean hasFullOtp = otpView != null && otpView.getText() != null && otpView.getText().length() == 6;
            verifyButton.setAlpha(show ? 0.85f : (hasFullOtp ? 1.0f : 0.6f));
        }
        if (otpView != null) {
            otpView.setEnabled(!show);
        }
        if (resendCodeText != null) {
            resendCodeText.setEnabled(!show && isResendEnabled);
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
}