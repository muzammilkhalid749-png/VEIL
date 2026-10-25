package com.m3.veil.utils;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Window-inset helpers that cooperate with XML-declared padding.
 *
 * <p>Applying insets with a bare {@code setPadding(left, top, right, bottom)} replaces whatever
 * padding the layout declared, so any screen whose root has horizontal padding loses it as soon as
 * the system bars are dispatched. These helpers add the insets to the view's own padding instead.
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
    }

    /** Adds the system-bar insets to {@code root}'s existing padding and requests a first pass. */
    public static void applySystemBarsPadding(View root) {
        if (root == null) {
            return;
        }
        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    initialLeft + bars.left,
                    initialTop + bars.top,
                    initialRight + bars.right,
                    initialBottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** Enables or disables the IME (keyboard) inset on top of the view's own bottom padding. */
    public static void applyImeBottomPadding(View root) {
        if (root == null) {
            return;
        }
        final int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                    initialBottom + ime.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
