package com.m3.veil.ui.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Legacy compatibility alias for {@link ChatMessageCell}.
 * Inherits 100% of Telegram's {@code ChatMessageCell} rendering, curved bezier tail geometry,
 * dynamic Light/Dark mode theme engine, vanish countdown arc, and swipe-to-reply physics.
 */
public class VeilMessageBubbleLayout extends ChatMessageCell {

    public VeilMessageBubbleLayout(@NonNull Context context) {
        super(context);
    }

    public VeilMessageBubbleLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public VeilMessageBubbleLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
