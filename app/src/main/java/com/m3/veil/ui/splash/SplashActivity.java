package com.m3.veil.ui.splash;



import android.animation.Animator;

import android.animation.AnimatorListenerAdapter;

import android.animation.ObjectAnimator;

import android.content.Intent;

import android.os.Bundle;

import android.os.Handler;

import android.util.Log;

import android.view.View;

import android.view.animation.AccelerateDecelerateInterpolator;

import android.widget.ImageView;

import android.widget.LinearLayout;

import android.widget.TextView;



import androidx.activity.EdgeToEdge;

import androidx.appcompat.app.AppCompatActivity;

import androidx.core.graphics.Insets;

import androidx.core.view.ViewCompat;

import androidx.core.view.WindowInsetsCompat;

import com.m3.veil.bridge.firestore.FirebaseFirestore;

import com.m3.veil.ui.main.MainActivity;

import com.m3.veil.model.User;

import com.m3.veil.ui.auth.PhoneAuthActivity;

import com.m3.veil.ui.auth.UsernameActivity;

import com.m3.veil.ui.onboard.OnboardingActivity;

import com.m3.veil.R;

import com.m3.veil.utils.PreferenceManager;



public class SplashActivity extends AppCompatActivity {



    private static final long SPLASH_DURATION = 2200L;

    private Handler handler;



    private ImageView splashLogo;

    private ImageView shieldGlow;

    private LinearLayout textContainer;

    private TextView appName;

    private TextView tagline;



    private PreferenceManager preferenceManager;




    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_splash);



        handler = new Handler();

        preferenceManager = new PreferenceManager(this);




        initializeViews();

        handleWindowInsets();

        startSplashAnimations();

    }



    private void handleWindowInsets() {

        // Adds the system-bar insets on top of the layout's own padding. The previous

        // implementation replaced the padding outright, silently erasing the horizontal

        // padding these screens declare in XML.

        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.splash_root));

    }



    private void initializeViews() {

        splashLogo = findViewById(R.id.splash_logo);

        shieldGlow = findViewById(R.id.shield_glow);

        textContainer = findViewById(R.id.text_container);

        appName = findViewById(R.id.app_name);

        tagline = findViewById(R.id.tagline);



        // Set initial states

        splashLogo.setAlpha(0.0f);

        splashLogo.setScaleX(0.7f);

        splashLogo.setScaleY(0.7f);



        shieldGlow.setVisibility(View.INVISIBLE);

        textContainer.setVisibility(View.INVISIBLE);

    }



    private void startSplashAnimations() {

        // Phase 1: Logo animation

        animateLogoEntrance();



        // Navigate to main activity after delay

        handler.postDelayed(new Runnable() {

            @Override

            public void run() {

                checkAuthAndNavigate();

            }

        }, SPLASH_DURATION);

    }



    private void animateLogoEntrance() {

        ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(splashLogo, "alpha", 0.0f, 1.0f);

        ObjectAnimator scaleXAnim = ObjectAnimator.ofFloat(splashLogo, "scaleX", 0.7f, 1.0f);

        ObjectAnimator scaleYAnim = ObjectAnimator.ofFloat(splashLogo, "scaleY", 0.7f, 1.0f);



        alphaAnim.setDuration(800);

        scaleXAnim.setDuration(800);

        scaleYAnim.setDuration(800);



        alphaAnim.setInterpolator(new AccelerateDecelerateInterpolator());

        scaleXAnim.setInterpolator(new AccelerateDecelerateInterpolator());

        scaleYAnim.setInterpolator(new AccelerateDecelerateInterpolator());



        alphaAnim.addListener(new AnimatorListenerAdapter() {

            @Override

            public void onAnimationEnd(Animator animation) {

                animateShieldGlow();

                animateTextReveal();

            }

        });



        alphaAnim.start();

        scaleXAnim.start();

        scaleYAnim.start();

    }



    private void animateShieldGlow() {

        shieldGlow.setVisibility(View.VISIBLE);

        shieldGlow.setAlpha(0.0f);

        shieldGlow.setScaleX(0.5f);

        shieldGlow.setScaleY(0.5f);



        ObjectAnimator alphaIn = ObjectAnimator.ofFloat(shieldGlow, "alpha", 0.0f, 1.0f);

        ObjectAnimator scaleXIn = ObjectAnimator.ofFloat(shieldGlow, "scaleX", 0.5f, 1.2f);

        ObjectAnimator scaleYIn = ObjectAnimator.ofFloat(shieldGlow, "scaleY", 0.5f, 1.2f);



        alphaIn.setDuration(400);

        scaleXIn.setDuration(400);

        scaleYIn.setDuration(400);



        alphaIn.addListener(new AnimatorListenerAdapter() {

            @Override

            public void onAnimationEnd(Animator animation) {

                ObjectAnimator alphaOut = ObjectAnimator.ofFloat(shieldGlow, "alpha", 1.0f, 0.0f);

                ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(shieldGlow, "scaleX", 1.2f, 1.5f);

                ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(shieldGlow, "scaleY", 1.2f, 1.5f);



                alphaOut.setDuration(600);

                scaleXOut.setDuration(600);

                scaleYOut.setDuration(600);



                alphaOut.start();

                scaleXOut.start();

                scaleYOut.start();

            }

        });



        alphaIn.start();

        scaleXIn.start();

        scaleYIn.start();

    }



    private void animateTextReveal() {

        textContainer.setVisibility(View.VISIBLE);

        textContainer.setAlpha(0.0f);

        textContainer.setTranslationY(30f);



        appName.setScaleX(0.8f);

        appName.setScaleY(0.8f);

        tagline.setAlpha(0.0f);

        tagline.setTranslationX(-20f);



        ObjectAnimator containerAlpha = ObjectAnimator.ofFloat(textContainer, "alpha", 0.0f, 1.0f);

        ObjectAnimator containerTranslate = ObjectAnimator.ofFloat(textContainer, "translationY", 30f, 0f);



        containerAlpha.setDuration(500);

        containerTranslate.setDuration(500);



        containerAlpha.start();

        containerTranslate.start();



        handler.postDelayed(new Runnable() {

            @Override

            public void run() {

                ObjectAnimator appNameScaleX = ObjectAnimator.ofFloat(appName, "scaleX", 0.8f, 1.0f);

                ObjectAnimator appNameScaleY = ObjectAnimator.ofFloat(appName, "scaleY", 0.8f, 1.0f);



                appNameScaleX.setDuration(400);

                appNameScaleY.setDuration(400);



                appNameScaleX.start();

                appNameScaleY.start();

            }

        }, 200);



        handler.postDelayed(new Runnable() {

            @Override

            public void run() {

                ObjectAnimator taglineAlpha = ObjectAnimator.ofFloat(tagline, "alpha", 0.0f, 1.0f);

                ObjectAnimator taglineTranslate = ObjectAnimator.ofFloat(tagline, "translationX", -20f, 0f);



                taglineAlpha.setDuration(500);

                taglineTranslate.setDuration(500);



                taglineAlpha.start();

                taglineTranslate.start();

            }

        }, 400);

    }





    private void checkAuthAndNavigate() {

        com.m3.veil.utils.UserPreferences userPrefs = new com.m3.veil.utils.UserPreferences(this);

        boolean isSovereignLoggedIn = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).isLoggedIn()

                || userPrefs.isLoggedIn();

        boolean hasProfile = userPrefs.hasCompletedProfile();



        if (isSovereignLoggedIn && hasProfile) {

            Log.d("SplashActivity", "Sovereign user is signed in locally: @" + userPrefs.getUsername());

            goToNextScreen();

            return;

        }



        if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(this)) {

            Log.d("SplashActivity", "User is signed in, navigating to main");

            navigateToMain();

        } else {

            Log.d("SplashActivity", "User is not signed in");

            if (preferenceManager.isOnboardingCompleted()) {

                navigateToAuth();

            } else {

                navigateToOnboarding();

            }

        }

    }



    private void navigateToMain() {

        com.m3.veil.utils.UserPreferences userPrefs = new com.m3.veil.utils.UserPreferences(this);

        if (userPrefs.hasCompletedProfile()) {

            goToNextScreen();

            return;

        }



        if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(this)) {
            String currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);

            // 1. Check local user preferences
            User localUser = userPrefs.getCurrentUser();
            if (localUser != null && localUser.getUsername() != null && !localUser.getUsername().trim().isEmpty()) {
                goToNextScreen();
                return;
            }

            // 2. Check local SQLCipher sovereign profile manifest
            try {
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        com.m3.veil.database.EncryptedChatDatabase.getInstance(this).getSovereignProfileManifest(currentUserId);
                if (manifest != null && manifest.username != null && !manifest.username.trim().isEmpty()) {
                    goToNextScreen();
                    return;
                }
            } catch (Throwable ignored) {}

            goToUsernameActivity();
        } else {

            goToPhoneAuth();

        }

    }



    private void goToNextScreen() {

        try {

            android.content.SharedPreferences sharedPreferences;

            try {

                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);

                sharedPreferences = androidx.security.crypto.EncryptedSharedPreferences.create(

                        "veil_encrypted_prefs",

                        masterKeyAlias,

                        this,

                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,

                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM

                );

            } catch (Exception secEx) {

                sharedPreferences = getSharedPreferences("veil_encrypted_prefs_fallback", MODE_PRIVATE);

            }



            boolean isPinSet = sharedPreferences.getBoolean("pin_set", false)

                    || com.m3.veil.manager.VeilFeatureManager.getInstance(this).hasMasterPin();

            

            if (isPinSet) {

                Intent intent = new Intent(SplashActivity.this, com.m3.veil.ui.auth.PinUnlockActivity.class);

                startActivity(intent);

            } else {

                Intent intent = new Intent(SplashActivity.this, MainActivity.class);

                startActivity(intent);

            }

            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

            finish();

        } catch (Exception e) {

            // Fallback to main

            Intent intent = new Intent(SplashActivity.this, MainActivity.class);

            startActivity(intent);

            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

            finish();

        }

    }



    private void goToUsernameActivity() {

        Intent intent = new Intent(SplashActivity.this, UsernameActivity.class);

        startActivity(intent);

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        finish();

    }



    private void goToPhoneAuth() {

        Intent intent = new Intent(SplashActivity.this, PhoneAuthActivity.class);

        startActivity(intent);

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        finish();

    }



    private void createUserDocumentAndGoToUsername() {
        String userId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (userId == null) {
            goToUsernameActivity();
            return;
        }
        com.m3.veil.manager.AuthManager.SessionUser sUser = com.m3.veil.manager.AuthManager.getInstance().getCurrentUser(this);
        String phoneNumber = sUser != null ? sUser.getPhoneNumber() : "";

        String localeCountry = java.util.Locale.getDefault().getCountry();

        String countryCode = (localeCountry != null && !localeCountry.isEmpty()) ? localeCountry : "US";



        User newUser = new User(userId, phoneNumber, countryCode);



        FirebaseFirestore.getInstance()

                .collection("users")

                .document(userId)

                .set(newUser)

                .addOnCompleteListener(task -> {

                    if (task.isSuccessful()) {

                        goToUsernameActivity();

                    } else {

                        // If creation fails, still go to username activity (it will handle the error)

                        goToUsernameActivity();

                    }

                });

    }



    private void navigateToOnboarding() {

        Intent intent = new Intent(SplashActivity.this, OnboardingActivity.class);

        startActivity(intent);

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        finish();

    }



    private void navigateToAuth() {

        Intent intent = new Intent(SplashActivity.this, PhoneAuthActivity.class);

        startActivity(intent);

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        finish();

    }



    @Override

    protected void onDestroy() {

        super.onDestroy();

        if (handler != null) {

            handler.removeCallbacksAndMessages(null);

        }

    }

}