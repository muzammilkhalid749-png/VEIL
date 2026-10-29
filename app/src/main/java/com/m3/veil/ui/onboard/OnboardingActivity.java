package com.m3.veil.ui.onboard;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.ui.auth.PhoneAuthActivity;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 onboardingViewPager;
    private LinearLayout dotsContainer;
    private AppCompatButton nextButton;
    private TextView skipText;
    private TextView stepCounterText;
    private OnboardingAdapter onboardingAdapter;
    private PreferenceManager preferenceManager;
    private FirebaseAuth firebaseAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_onboarding);

        preferenceManager = new PreferenceManager(this);
        firebaseAuth = FirebaseAuth.getInstance();

        initializeViews();
        handleWindowInsets();
        setupOnboardingItems();
        setupDotsIndicator();
        setupClickListeners();
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.onboarding_root));
    }

    private void initializeViews() {
        onboardingViewPager = findViewById(R.id.onboardingViewPager);
        dotsContainer = findViewById(R.id.dotsContainer);
        nextButton = findViewById(R.id.nextButton);
        skipText = findViewById(R.id.skipText);
        stepCounterText = findViewById(R.id.stepCounterText);
    }

    private void setupOnboardingItems() {
        List<OnboardingItem> onboardingItems = new ArrayList<>();

        onboardingItems.add(new OnboardingItem(
                getString(R.string.onboarding_title_1),
                getString(R.string.onboarding_desc_1),
                R.drawable.onboarding_secure,
                "AES-256",
                "Local Key"
        ));

        onboardingItems.add(new OnboardingItem(
                getString(R.string.onboarding_title_2),
                getString(R.string.onboarding_desc_2),
                R.drawable.onboarding_connect,
                "Serverless P2P",
                null
        ));

        onboardingItems.add(new OnboardingItem(
                getString(R.string.onboarding_title_3),
                getString(R.string.onboarding_desc_3),
                R.drawable.onboarding_privacy,
                "Anonymous",
                null
        ));

        onboardingAdapter = new OnboardingAdapter(onboardingItems);
        onboardingViewPager.setAdapter(onboardingAdapter);

        onboardingViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateHeader(position);
                updateDots(position);
                updateButtonStyle(position);
            }
        });
    }

    private void updateHeader(int position) {
        // Formats step counter as "01", "02", "03"
        stepCounterText.setText(String.format(Locale.US, "%02d", position + 1));

        // Hide "Skip" on the final page (03) just like the mockup
        if (position == onboardingAdapter.getItemCount() - 1) {
            skipText.animate().alpha(0f).setDuration(150).withEndAction(() ->
                    skipText.setVisibility(View.INVISIBLE)).start();
        } else {
            skipText.setVisibility(View.VISIBLE);
            skipText.animate().alpha(1f).setDuration(150).start();
        }
    }

    private void setupDotsIndicator() {
        int count = onboardingAdapter.getItemCount();
        dotsContainer.removeAllViews();

        int heightPx = dpToPx(7);
        int activeWidthPx = dpToPx(22);
        int inactiveWidthPx = dpToPx(7);
        int marginPx = dpToPx(5);

        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    i == 0 ? activeWidthPx : inactiveWidthPx,
                    heightPx
            );
            params.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(params);
            dot.setBackground(ContextCompat.getDrawable(
                    this,
                    i == 0 ? R.drawable.dot_active_onboard : R.drawable.dot_inactive_onboard
            ));
            dotsContainer.addView(dot);
        }
    }

    private void updateDots(int position) {
        int heightPx = dpToPx(7);
        int activeWidthPx = dpToPx(22);
        int inactiveWidthPx = dpToPx(7);
        int marginPx = dpToPx(5);

        for (int i = 0; i < dotsContainer.getChildCount(); i++) {
            View dot = dotsContainer.getChildAt(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    i == position ? activeWidthPx : inactiveWidthPx,
                    heightPx
            );
            params.setMargins(marginPx, 0, marginPx, 0);
            dot.setLayoutParams(params);
            dot.setBackground(ContextCompat.getDrawable(
                    this,
                    i == position ? R.drawable.dot_active_onboard : R.drawable.dot_inactive_onboard
            ));
        }
    }

    private void updateButtonStyle(int position) {
        if (position == 0) {
            nextButton.setText(R.string.next);
            nextButton.setTextColor(Color.parseColor("#8C93A4"));
            nextButton.setBackgroundResource(R.drawable.bg_btn_next_subtle);
        } else if (position == 1) {
            nextButton.setText(R.string.next);
            nextButton.setTextColor(Color.parseColor("#35396B"));
            nextButton.setBackgroundResource(R.drawable.bg_btn_next_outlined);
        } else {
            nextButton.setText(R.string.get_started);
            nextButton.setTextColor(Color.WHITE);
            nextButton.setBackgroundResource(R.drawable.bg_btn_get_started_gradient);
        }
    }

    private void setupClickListeners() {
        nextButton.setOnClickListener(v -> {
            if (onboardingViewPager.getCurrentItem() < onboardingAdapter.getItemCount() - 1) {
                onboardingViewPager.setCurrentItem(onboardingViewPager.getCurrentItem() + 1, true);
            } else {
                completeOnboarding();
            }
        });

        skipText.setOnClickListener(v -> completeOnboarding());
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void navigateToMain() {
        Intent intent = new Intent(OnboardingActivity.this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void navigateToAuth() {
        Intent intent = new Intent(OnboardingActivity.this, PhoneAuthActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void completeOnboarding() {
        preferenceManager.setOnboardingCompleted(true);
        preferenceManager.setFirstTime(false);

        if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(this)) {
            navigateToMain();
        } else {
            navigateToAuth();
        }
    }
}