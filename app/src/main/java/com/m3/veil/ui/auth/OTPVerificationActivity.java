package com.m3.veil.ui.auth;



import android.content.Intent;

import android.os.Bundle;

import android.os.CountDownTimer;

import android.view.View;

import android.widget.Button;

import android.widget.ProgressBar;

import android.widget.TextView;



import androidx.activity.EdgeToEdge;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;

import androidx.core.graphics.Insets;

import androidx.core.view.ViewCompat;

import androidx.core.view.WindowInsetsCompat;



import com.google.android.material.snackbar.Snackbar;

import com.m3.veil.bridge.FirebaseException;

import com.m3.veil.bridge.firestore.FirebaseFirestore;

import com.m3.veil.bridge.firestore.WriteBatch;

import com.m3.veil.ui.main.MainActivity;

import com.m3.veil.model.User;

import com.m3.veil.model.UserKeys;

import com.m3.veil.model.UserPrivacy;

import com.m3.veil.model.UserSettings;

import com.mukeshsolanki.OtpView;

import com.m3.veil.R;

import java.util.Locale;

import java.util.concurrent.TimeUnit;



public class OTPVerificationActivity extends AppCompatActivity {



    private OtpView otpView;

    private Button verifyButton;

    private ProgressBar progressBar;

    private TextView phoneNumberText, timerText, resendCodeText;




    private String verificationId;

    private String phoneNumber;




    private CountDownTimer countDownTimer;

    private boolean isResendEnabled = false;

    private boolean isAutoVerifying = false;



    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_otp_verification);




        initializeViews();

        handleWindowInsets();

        getIntentData();

        setupClickListeners();

        setupOtpListener();

        startOTPTimer();

        displayPhoneNumber();

    }



    private void handleWindowInsets() {

        // Adds the system-bar insets on top of the layout's own padding. The previous

        // implementation replaced the padding outright, silently erasing the horizontal

        // padding these screens declare in XML.

        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.otp_root));

    }



    private void initializeViews() {

        otpView = findViewById(R.id.otpView);

        verifyButton = findViewById(R.id.verifyButton);

        progressBar = findViewById(R.id.progressBar);

        phoneNumberText = findViewById(R.id.phoneNumberText);

        timerText = findViewById(R.id.timerText);

        resendCodeText = findViewById(R.id.resendCodeText);



        findViewById(R.id.backButton).setOnClickListener(v -> onBackPressed());



        // Initially disable verify button until OTP is complete

        verifyButton.setEnabled(false);

        verifyButton.setAlpha(0.6f);

    }



    private void getIntentData() {

        Intent intent = getIntent();

        verificationId = intent.getStringExtra("verification_id");

        phoneNumber = intent.getStringExtra("phone_number");


    }



    private void displayPhoneNumber() {

        if (phoneNumber != null) {

            String maskedNumber = maskPhoneNumber(phoneNumber);

            phoneNumberText.setText(maskedNumber);

        }

    }



    private String maskPhoneNumber(String phoneNumber) {

        if (phoneNumber.length() < 6) return phoneNumber;



        String countryCode = phoneNumber.substring(0, phoneNumber.length() - 10);

        String lastFour = phoneNumber.substring(phoneNumber.length() - 4);

        return countryCode + " XXX-XXX-" + lastFour;

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

        otpView.setOtpCompletionListener(otp -> {

            // OTP automatically completed - enable verify button

            verifyButton.setEnabled(true);

            verifyButton.setAlpha(1.0f);



            // Auto-verify if not already verifying

            if (!isAutoVerifying) {

                isAutoVerifying = true;

                verifyOTP();

            }

        });

    }



    private void startOTPTimer() {

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

                resendCodeText.setTextColor(getResources().getColor(R.color.veil_accent));

                resendCodeText.setClickable(true);

            }

        }.start();

    }



    private void verifyOTP() {

        String otp = otpView.getText().toString().trim();



        if (otp.length() != 6) {

            showSnackbar(getString(R.string.enter_valid_otp));

            isAutoVerifying = false;

            return;

        }



        showLoading(true);



        com.m3.veil.utils.SovereignPhoneAuthBridge.getInstance().verifyCode(

                this,

                verificationId != null ? verificationId : "",

                phoneNumber != null ? phoneNumber : "",

                otp,

                new com.m3.veil.utils.SovereignPhoneAuthBridge.VerifyCallback() {

                    @Override

                    public void onSuccess(@NonNull String uid, boolean hasExistingUsername) {

                        showLoading(false);

                        if (hasExistingUsername) {

                            navigateToMain();

                        } else {

                            navigateToUsername();

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







    private void shakeOtpView() {

        // Simple shake animation for error indication

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

        resendCodeText.setTextColor(getResources().getColor(R.color.text_secondary));

        resendCodeText.setClickable(false);

        timerText.setVisibility(View.VISIBLE);



        // Clear previous OTP

        otpView.setText("");

        verifyButton.setEnabled(false);

        verifyButton.setAlpha(0.6f);



        com.m3.veil.utils.SovereignPhoneAuthBridge.getInstance().sendVerificationCode(

                this,

                phoneNumber != null ? phoneNumber : "",

                new com.m3.veil.utils.SovereignPhoneAuthBridge.SendCallback() {

                    @Override

                    public void onCodeSent(@NonNull String newVerificationId) {

                        verificationId = newVerificationId;

                        startOTPTimer();

                        showSnackbar(getString(R.string.code_sent_success));

                    }



                    @Override

                    public void onFailure(@NonNull String errorMessage) {

                        showSnackbar(getString(R.string.fail_resend_code, errorMessage));

                        isResendEnabled = true;

                        resendCodeText.setTextColor(getResources().getColor(R.color.veil_accent));

                        resendCodeText.setClickable(true);

                    }

                }

        );

    }



    private void navigateAfterVerification() {
        String currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null) {
            navigateToUsername();
            return;
        }

        // 1. Check local UserPreferences
        com.m3.veil.utils.UserPreferences prefs = new com.m3.veil.utils.UserPreferences(this);
        User localUser = prefs.getCurrentUser();
        if (localUser != null && localUser.getUsername() != null && !localUser.getUsername().trim().isEmpty()) {
            navigateToMain();
            return;
        }

        // 2. Check local SQLCipher profile manifest
        try {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getSovereignProfileManifest(currentUserId);
            if (manifest != null && manifest.username != null && !manifest.username.trim().isEmpty()) {
                navigateToMain();
                return;
            }
        } catch (Throwable ignored) {}

        // User doesn't have username yet, create local user record and navigate to UsernameActivity
        createUserDocumentAndNavigate();
    }

    private void createUserDocumentAndNavigate() {
        String userId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (userId == null) {
            navigateToUsername();
            return;
        }
        String phoneNumber = this.phoneNumber != null ? this.phoneNumber : "";

        // Extract country code from phone number
        String countryCode = extractCountryCode(phoneNumber);

        // Create sovereign user record locally
        User newUser = new User(userId, phoneNumber, countryCode);
        new com.m3.veil.utils.UserPreferences(this).saveUser(newUser);

        navigateToUsername();
    }

    private void createAdditionalUserDocuments(String userId) {
        navigateToUsername();

    }



    private String extractCountryCode(String phoneNumber) {

        // Simple country code extraction - you might want to use a library for this

        if (phoneNumber != null && phoneNumber.startsWith("+92")) {

            return "PK";

        } else if (phoneNumber != null && phoneNumber.startsWith("+1")) {

            return "US";

        } else if (phoneNumber != null && phoneNumber.startsWith("+44")) {

            return "GB";

        }

        return "US"; // Default

    }

    private void navigateToUsername() {

        Intent intent = new Intent(this, UsernameActivity.class);

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