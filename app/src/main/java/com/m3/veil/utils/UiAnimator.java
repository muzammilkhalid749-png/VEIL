package com.m3.veil.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

public final class UiAnimator {

    private static final FastOutSlowInInterpolator EASE = new FastOutSlowInInterpolator();

    private UiAnimator() {}

    /**
     * Configure status and navigation bar icon contrast for Light/Dark mode.
     */
    public static void configureSystemBars(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        Window window = activity.getWindow();
        boolean isNight = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean darkIcons = !isNight; // Dark icons for Light Mode, White icons for Night Mode

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(darkIcons);
            controller.setAppearanceLightNavigationBars(darkIcons);
        }
    }

    /**
     * 1. Clean Edge-to-Edge Window Insets with Dynamic Night Mode Status Bar (Call in Activity.onCreate)
     */
    public static void enableEdgeToEdge(Activity activity, View rootView) {
        if (activity == null || activity.getWindow() == null || rootView == null) return;
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        configureSystemBars(activity);

        final int pL = rootView.getPaddingLeft();
        final int pT = rootView.getPaddingTop();
        final int pR = rootView.getPaddingRight();
        final int pB = rootView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(pL + bars.left, pT + bars.top, pR + bars.right, pB + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /**
     * 2. Featherweight Tactile Press (Zero extra libraries required)
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void attachSpringPress(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110L).setInterpolator(EASE).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(EASE).start();
            }
            return false;
        });
    }

    /**
     * 3. Snappy, Subtle Screen Fade-In (220ms, 12dp micro-slide)
     */
    public static void playStaggeredEntry(ViewGroup container) {
        if (container == null) return;
        float offsetPx = 12f * container.getResources().getDisplayMetrics().density;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(offsetPx);
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * 25L)
                .setDuration(220L)
                .setInterpolator(EASE)
                .start();
        }
    }

    /**
     * 4. Smooth Visibility Transition
     */
    public static void beginSmoothMorph(ViewGroup sceneRoot) {
        if (sceneRoot == null) return;
        AutoTransition transition = new AutoTransition();
        transition.setDuration(180L);
        transition.setInterpolator(EASE);
        TransitionManager.beginDelayedTransition(sceneRoot, transition);
    }

    // =========================================================================
    // 5. Large Emoji Rendering for Input EditTexts & Status Captions
    // =========================================================================

    public static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || cp == 0x200D
                || cp == 0xFE0F
                || cp == 0x20E3
                || (cp >= 0x1F000 && cp <= 0x1F02F)
                || (cp >= 0x1F0A0 && cp <= 0x1F0FF);
    }

    private static final class EmojiRelativeSizeSpan extends android.text.style.RelativeSizeSpan {
        EmojiRelativeSizeSpan(float proportion) {
            super(proportion);
        }
    }

    public static void applyEmojiSizeSpans(android.text.Editable editable, float relativeScale) {
        if (editable == null || editable.length() == 0) return;
        EmojiRelativeSizeSpan[] existing = editable.getSpans(0, editable.length(), EmojiRelativeSizeSpan.class);
        if (existing != null) {
            for (EmojiRelativeSizeSpan span : existing) {
                editable.removeSpan(span);
            }
        }
        final int len = editable.length();
        int i = 0;
        while (i < len) {
            int cp = Character.codePointAt(editable, i);
            int charCount = Character.charCount(cp);
            if (isEmojiCodePoint(cp)) {
                int runStart = i;
                int runEnd = i + charCount;
                while (runEnd < len) {
                    int nextCp = Character.codePointAt(editable, runEnd);
                    if (isEmojiCodePoint(nextCp)) {
                        runEnd += Character.charCount(nextCp);
                    } else {
                        break;
                    }
                }
                editable.setSpan(
                        new EmojiRelativeSizeSpan(relativeScale),
                        runStart,
                        runEnd,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                i = runEnd;
            } else {
                i += charCount;
            }
        }
    }

    public static void attachLargeEmojiInputWatcher(final android.widget.EditText editText, final float relativeScale) {
        if (editText == null) return;
        if (editText.getText() != null && editText.getText().length() > 0) {
            applyEmojiSizeSpans(editText.getText(), relativeScale);
        }
        editText.addTextChangedListener(new android.text.TextWatcher() {
            private boolean isFormatting = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (isFormatting || s == null) return;
                isFormatting = true;
                try {
                    applyEmojiSizeSpans(s, relativeScale);
                } finally {
                    isFormatting = false;
                }
            }
        });
    }

    public static CharSequence formatTextWithLargeEmojis(CharSequence text, float relativeScale) {
        if (text == null || text.length() == 0) return "";
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(text);
        applyEmojiSizeSpans(ssb, relativeScale);
        return ssb;
    }
}
