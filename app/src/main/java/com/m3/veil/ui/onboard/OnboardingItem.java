package com.m3.veil.ui.onboard;

import androidx.annotation.Nullable;

public class OnboardingItem {
    private final String title;
    private final String description;
    private final int imageResId;
    private final String topRightPill;
    @Nullable
    private final String bottomLeftPill;

    public OnboardingItem(String title, String description, int imageResId,
                          String topRightPill, @Nullable String bottomLeftPill) {
        this.title = title;
        this.description = description;
        this.imageResId = imageResId;
        this.topRightPill = topRightPill;
        this.bottomLeftPill = bottomLeftPill;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getImageResId() {
        return imageResId;
    }

    public String getTopRightPill() {
        return topRightPill;
    }

    @Nullable
    public String getBottomLeftPill() {
        return bottomLeftPill;
    }
}