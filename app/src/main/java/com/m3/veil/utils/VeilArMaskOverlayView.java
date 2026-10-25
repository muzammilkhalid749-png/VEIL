package com.m3.veil.utils;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

/**
 * Top-level view wrapper for VeilArMaskRenderer.OverlayView to allow clean XML layout inflation
 * without ClassNotFoundException during Android LayoutInflater inflation.
 */
public class VeilArMaskOverlayView extends VeilArMaskRenderer.OverlayView {

    public VeilArMaskOverlayView(Context context) {
        super(context);
    }

    public VeilArMaskOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public VeilArMaskOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
