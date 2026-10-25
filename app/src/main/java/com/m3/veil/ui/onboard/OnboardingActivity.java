package com.m3.veil.ui.onboard;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.R;
import com.m3.veil.ui.auth.PhoneAuthActivity;
import com.m3.veil.utils.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 onboardingViewPager;
    private LinearLayout dotsContainer;
    private MaterialButton nextButton; // Changed to MaterialButton
    private TextView skipText;
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
        // Adds the system-bar insets on top of the layout's own padding. The previous
        // implementation replaced the padding outright, silently erasing the horizontal
        // padding these screens declare in XML.
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.onboarding_root));
    }

    private void initializeViews() {
        onboardingViewPager = findViewById(R.id.onboardingViewPager);
        dotsContainer = findViewById(R.id.dotsContainer);
        nextButton = findViewById(R.id.nextButton); // Now MaterialButton
        skipText = findViewById(R.id.skipText);
    }

    private void setupOnboardingItems() {
        List<OnboardingItem> onboardingItems = new ArrayList<>();

        onboardingItems.add(new OnboardingItem(
                getString(R.string.onboarding_title_1),
                getString(R.string.onboarding_desc_1),
                R.drawable.onboarding_secure
        ));

        onboardingItems.add(new OnboardingItem(
                getString(R.string.onboarding_title_2),
                getString(R.string.onboarding_desc_2),
                R.drawable.onboarding_connect
        ));

        onboardingItems.add(new OnboardingItem(
                getString(R.string.onboarding_title_3),
                getString(R.string.onboarding_desc_3),
                R.drawable.onboarding_privacy
        ));

        onboardingAdapter = new OnboardingAdapter(onboardingItems);
        onboardingViewPager.setAdapter(onboardingAdapter);

        // Add page change callback
        onboardingViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
                updateButtonText(position);
            }
        });
    }

    private void setupDotsIndicator() {
        int count = onboardingAdapter.getItemCount();
        dotsContainer.removeAllViews();

        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            int dotSize = getResources().getDimensionPixelSize(R.dimen.dot_size);
            int dotMargin = getResources().getDimensionPixelSize(R.dimen.dot_margin);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dotSize, dotSize);
            params.setMargins(dotMargin, 0, dotMargin, 0);
            dot.setLayoutParams(params);

            if (i == 0) {
                dot.setBackground(ContextCompat.getDrawable(this, R.drawable.dot_active));
            } else {
                dot.setBackground(ContextCompat.getDrawable(this, R.drawable.dot_inactive));
            }

            dotsContainer.addView(dot);
        }
    }

    private void updateDots(int position) {
        for (int i = 0; i < dotsContainer.getChildCount(); i++) {
            View dot = dotsContainer.getChildAt(i);
            if (i == position) {
                dot.setBackground(ContextCompat.getDrawable(this, R.drawable.dot_active));
                dot.animate().scaleX(1.4f).scaleY(1.4f).setDuration(300).start();
            } else {
                dot.setBackground(ContextCompat.getDrawable(this, R.drawable.dot_inactive));
                dot.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start();
            }
        }
    }

    private void updateButtonText(int position) {
        if (position == onboardingAdapter.getItemCount() - 1) {
            nextButton.setText(R.string.get_started);
            // Add celebration animation for final step
            nextButton.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(200)
                    .withEndAction(() -> nextButton.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(200)
                            .start())
                    .start();
        } else {
            nextButton.setText(R.string.next);
            nextButton.setScaleX(1.0f);
            nextButton.setScaleY(1.0f);
        }
    }

    private void setupClickListeners() {
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onboardingViewPager.getCurrentItem() < onboardingAdapter.getItemCount() - 1) {
                    onboardingViewPager.setCurrentItem(onboardingViewPager.getCurrentItem() + 1);
                } else {
                    completeOnboarding();
                }
            }
        });

        skipText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                completeOnboarding();
            }
        });
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
        // Mark onboarding as completed
        preferenceManager.setOnboardingCompleted(true);
        preferenceManager.setFirstTime(false);

        // Check if user is already signed in (might have signed in during onboarding)
        FirebaseUser currentUser = firebaseAuth.getCurrentUser();
        if (currentUser != null) {
            // User is signed in, go to MainActivity
            navigateToMain();
        } else {
            // User is not signed in, go to SignUpActivity
            navigateToAuth();
        }
    }
}