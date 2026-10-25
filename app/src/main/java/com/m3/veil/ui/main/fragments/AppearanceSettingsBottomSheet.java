package com.m3.veil.ui.main.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.m3.veil.R;
import com.yalantis.ucrop.UCrop;

import java.io.File;

public class AppearanceSettingsBottomSheet extends BottomSheetDialogFragment {

    private FrameLayout previewWallpaperContainer;
    private ImageView ivPreviewWallpaper;
    private ImageView ivPreviewDoodleOverlay;
    private View viewPreviewDimOverlay;
    private LinearLayout previewIncomingBubble;
    private LinearLayout previewOutgoingBubble;
    private TextView tvPreviewIncomingText;
    private TextView tvPreviewOutgoingText;

    private RadioGroup rgThemeMode;
    private RadioButton rbThemeSystem, rbThemeLight, rbThemeDark;
    private MaterialSwitch switchAmoledBlack;

    private MaterialButton btnPickPhotoWallpaper;
    private MaterialSwitch switchDoodlePattern;
    private Slider sliderWallpaperDim;
    private TextView tvDimPercent;

    private RadioGroup rgBubbleCornerStyle;
    private RadioButton rbCornerTelegram, rbCornerModern, rbCornerWhatsApp;

    private Slider sliderChatTextSize;
    private TextView tvChatTextSize;

    private MaterialButton btnResetDefaultWallpaper;

    private SharedPreferences prefs;

    // Pick gallery photo launcher
    private final ActivityResultLauncher<Intent> galleryPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        launchUCrop(selectedImageUri);
                    }
                }
            }
    );

    // UCrop launcher for Wallpaper (9:16 aspect ratio)
    private final ActivityResultLauncher<Intent> uCropLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri resultUri = UCrop.getOutput(result.getData());
                    if (resultUri != null) {
                        applyCustomWallpaper(resultUri.toString());
                    }
                } else if (result.getResultCode() == UCrop.RESULT_ERROR && result.getData() != null) {
                    Throwable cropError = UCrop.getError(result.getData());
                    Toast.makeText(requireContext(), "Wallpaper crop error: " + (cropError != null ? cropError.getMessage() : "unknown"), Toast.LENGTH_SHORT).show();
                }
            }
    );

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_appearance_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);

        bindViews(view);
        loadCurrentSettings();
        setupListeners(view);
    }

    private void bindViews(View view) {
        previewWallpaperContainer = view.findViewById(R.id.previewWallpaperContainer);
        ivPreviewWallpaper = view.findViewById(R.id.ivPreviewWallpaper);
        ivPreviewDoodleOverlay = view.findViewById(R.id.ivPreviewDoodleOverlay);
        viewPreviewDimOverlay = view.findViewById(R.id.viewPreviewDimOverlay);
        previewIncomingBubble = view.findViewById(R.id.previewIncomingBubble);
        previewOutgoingBubble = view.findViewById(R.id.previewOutgoingBubble);
        tvPreviewIncomingText = view.findViewById(R.id.tvPreviewIncomingText);
        tvPreviewOutgoingText = view.findViewById(R.id.tvPreviewOutgoingText);

        rgThemeMode = view.findViewById(R.id.rgThemeMode);
        rbThemeSystem = view.findViewById(R.id.rbThemeSystem);
        rbThemeLight = view.findViewById(R.id.rbThemeLight);
        rbThemeDark = view.findViewById(R.id.rbThemeDark);
        switchAmoledBlack = view.findViewById(R.id.switchAmoledBlack);

        btnPickPhotoWallpaper = view.findViewById(R.id.btnPickPhotoWallpaper);
        switchDoodlePattern = view.findViewById(R.id.switchDoodlePattern);
        sliderWallpaperDim = view.findViewById(R.id.sliderWallpaperDim);
        tvDimPercent = view.findViewById(R.id.tvDimPercent);

        rgBubbleCornerStyle = view.findViewById(R.id.rgBubbleCornerStyle);
        rbCornerTelegram = view.findViewById(R.id.rbCornerTelegram);
        rbCornerModern = view.findViewById(R.id.rbCornerModern);
        rbCornerWhatsApp = view.findViewById(R.id.rbCornerWhatsApp);

        sliderChatTextSize = view.findViewById(R.id.sliderChatTextSize);
        tvChatTextSize = view.findViewById(R.id.tvChatTextSize);

        btnResetDefaultWallpaper = view.findViewById(R.id.btnResetDefaultWallpaper);
    }

    private void loadCurrentSettings() {
        // Theme mode
        int currentMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (currentMode == AppCompatDelegate.MODE_NIGHT_NO) {
            rbThemeLight.setChecked(true);
        } else if (currentMode == AppCompatDelegate.MODE_NIGHT_YES) {
            rbThemeDark.setChecked(true);
        } else {
            rbThemeSystem.setChecked(true);
        }

        // AMOLED
        boolean isAmoled = prefs.getBoolean("amoled_pure_black", false);
        switchAmoledBlack.setChecked(isAmoled);

        // Doodle Pattern Overlay
        boolean isDoodlePattern = prefs.getBoolean("doodle_pattern_enabled", false);
        switchDoodlePattern.setChecked(isDoodlePattern);
        if (ivPreviewDoodleOverlay != null) {
            ivPreviewDoodleOverlay.setVisibility(isDoodlePattern ? View.VISIBLE : View.GONE);
        }

        // Wallpaper
        String wpType = prefs.getString("wallpaper_type", "default");
        String wpValue = prefs.getString("wallpaper_value", "#0D1117");
        int dimLevel = prefs.getInt("wallpaper_dim", 0);

        sliderWallpaperDim.setValue(dimLevel);
        tvDimPercent.setText(dimLevel + "%");
        updateDimOverlay(dimLevel);
        renderWallpaperPreview(wpType, wpValue);

        // Bubble Accent
        String accent = prefs.getString("chat_bubble_accent", "#4F46E5");
        applyBubbleAccent(accent);

        // Corner Style
        float cornerRadius = prefs.getFloat("chat_bubble_corner_radius_dp", 18.0f);
        if (cornerRadius <= 9.0f) {
            rbCornerWhatsApp.setChecked(true);
        } else if (cornerRadius <= 15.0f) {
            rbCornerModern.setChecked(true);
        } else {
            rbCornerTelegram.setChecked(true);
        }

        // Text Size
        float textSize = prefs.getFloat("chat_text_size_sp", 15.5f);
        sliderChatTextSize.setValue(Math.max(12.0f, Math.min(22.0f, textSize)));
        tvChatTextSize.setText(String.format(java.util.Locale.US, "%.1f sp", textSize));
        applyPreviewTextSize(textSize);
    }

    private void renderWallpaperPreview(String wpType, String wpValue) {
        if ("image".equals(wpType) && wpValue != null && !wpValue.isEmpty()) {
            ivPreviewWallpaper.setVisibility(View.VISIBLE);
            try {
                ivPreviewWallpaper.setImageURI(Uri.parse(wpValue));
            } catch (Exception ignored) {}
        } else if ("gradient".equals(wpType) && wpValue != null && wpValue.startsWith("gradient:")) {
            ivPreviewWallpaper.setVisibility(View.GONE);
            try {
                String[] parts = wpValue.substring("gradient:".length()).split(":");
                int[] colors = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    colors[i] = Color.parseColor(parts[i]);
                }
                GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
                previewWallpaperContainer.setBackground(gd);
            } catch (Exception e) {
                previewWallpaperContainer.setBackgroundColor(Color.parseColor("#0D1117"));
            }
        } else if ("color".equals(wpType) && wpValue != null && !wpValue.isEmpty()) {
            ivPreviewWallpaper.setVisibility(View.GONE);
            try {
                previewWallpaperContainer.setBackgroundColor(Color.parseColor(wpValue));
            } catch (Exception e) {
                previewWallpaperContainer.setBackgroundColor(Color.parseColor("#0D1117"));
            }
        } else {
            ivPreviewWallpaper.setVisibility(View.GONE);
            boolean isNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            boolean amoled = prefs.getBoolean("amoled_pure_black", false);
            if (isNight) {
                previewWallpaperContainer.setBackgroundColor(amoled ? Color.BLACK : Color.parseColor("#0B0F17"));
            } else {
                previewWallpaperContainer.setBackgroundColor(Color.parseColor("#F8FAFC"));
            }
        }
    }

    private void applyBubbleAccent(String hexAccent) {
        try {
            int parsedColor = Color.parseColor(hexAccent);
            if (previewOutgoingBubble != null) {
                previewOutgoingBubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(parsedColor));
            }
        } catch (Exception ignored) {}
    }

    private void applyPreviewTextSize(float sp) {
        if (tvPreviewIncomingText != null) {
            tvPreviewIncomingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
        if (tvPreviewOutgoingText != null) {
            tvPreviewOutgoingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    private void setupListeners(View view) {
        // Theme mode
        rgThemeMode.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (checkedId == R.id.rbThemeLight) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rbThemeDark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            }
            prefs.edit().putInt("theme_mode", newMode).apply();
            AppCompatDelegate.setDefaultNightMode(newMode);
            renderWallpaperPreview(prefs.getString("wallpaper_type", "default"), prefs.getString("wallpaper_value", null));
        });

        // AMOLED
        switchAmoledBlack.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("amoled_pure_black", isChecked).apply();
            renderWallpaperPreview(prefs.getString("wallpaper_type", "default"), prefs.getString("wallpaper_value", null));
            Toast.makeText(requireContext(), isChecked ? "🌑 Pure AMOLED Black Active" : "Pure Black Disabled", Toast.LENGTH_SHORT).show();
        });

        // Doodle Pattern
        switchDoodlePattern.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("doodle_pattern_enabled", isChecked).apply();
            if (ivPreviewDoodleOverlay != null) {
                ivPreviewDoodleOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
            Toast.makeText(requireContext(), isChecked ? "✨ Doodle Artwork Pattern Enabled" : "Doodle Artwork Disabled", Toast.LENGTH_SHORT).show();
        });

        // Photo picker
        btnPickPhotoWallpaper.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            galleryPickerLauncher.launch(Intent.createChooser(intent, "Choose Wallpaper"));
        });

        // Dim slider
        sliderWallpaperDim.addOnChangeListener((slider, value, fromUser) -> {
            int intVal = (int) value;
            tvDimPercent.setText(intVal + "%");
            updateDimOverlay(intVal);
            prefs.edit().putInt("wallpaper_dim", intVal).apply();
        });

        // Light Presets
        setupWpPreset(view.findViewById(R.id.wpPresetLightWarm), "#FAF7F2", "Warm Cream");
        setupWpPreset(view.findViewById(R.id.wpPresetLightMint), "#F0FDF4", "Mint Foam");
        setupWpPreset(view.findViewById(R.id.wpPresetLightSky), "#F0F9FF", "Pastel Sky");
        setupWpPreset(view.findViewById(R.id.wpPresetLightRose), "#FFF1F2", "Rose Whisper");
        setupWpPreset(view.findViewById(R.id.wpPresetLightSlate), "#F8FAFC", "Clean Slate");

        // Dark Presets
        setupWpPreset(view.findViewById(R.id.wpPresetDefault), "#0D1117", "Midnight Abyss");
        setupWpPreset(view.findViewById(R.id.wpPresetIndigo), "#121026", "Cyber Indigo");
        setupWpPreset(view.findViewById(R.id.wpPresetEmerald), "#091E1A", "Emerald Matrix");
        setupWpPreset(view.findViewById(R.id.wpPresetCrimson), "#240B15", "Crimson Velvet");
        setupWpPreset(view.findViewById(R.id.wpPresetOcean), "#0A192F", "Deep Ocean");

        // Gradient Presets
        setupGradientPreset(view.findViewById(R.id.wpPresetGradSunset), "gradient:#FF512F:#DD2476", "Sunset Horizon");
        setupGradientPreset(view.findViewById(R.id.wpPresetGradOcean), "gradient:#00C9FF:#92FE9D", "Oceanic Aqua");
        setupGradientPreset(view.findViewById(R.id.wpPresetGradAurora), "gradient:#8A2387:#E94057:#F27121", "Cyber Aurora");
        setupGradientPreset(view.findViewById(R.id.wpPresetGradMidnight), "gradient:#0F2027:#203A43:#2C5364", "Midnight Nebula");

        // Bubble Accent Colors
        setupColorSwatch(view.findViewById(R.id.colorIndigo), "#4F46E5", "Veil Indigo");
        setupColorSwatch(view.findViewById(R.id.colorBlue), "#2563EB", "Cobalt Blue");
        setupColorSwatch(view.findViewById(R.id.colorCyan), "#00E5FF", "Cyber Cyan");
        setupColorSwatch(view.findViewById(R.id.colorEmerald), "#00E676", "Matrix Emerald");
        setupColorSwatch(view.findViewById(R.id.colorViolet), "#D500F9", "Cyberpunk Violet");
        setupColorSwatch(view.findViewById(R.id.colorAmber), "#FFB300", "Solar Amber");
        setupColorSwatch(view.findViewById(R.id.colorCrimson), "#E11D48", "Crimson Red");

        // Corner Style
        rgBubbleCornerStyle.setOnCheckedChangeListener((group, checkedId) -> {
            float radius = 18.0f;
            String label = "Telegram (18dp)";
            if (checkedId == R.id.rbCornerWhatsApp) {
                radius = 8.0f;
                label = "WhatsApp (8dp)";
            } else if (checkedId == R.id.rbCornerModern) {
                radius = 14.0f;
                label = "Soft Modern (14dp)";
            }
            prefs.edit().putFloat("chat_bubble_corner_radius_dp", radius).apply();
            Toast.makeText(requireContext(), "Bubble Style: " + label, Toast.LENGTH_SHORT).show();
        });

        // Chat Text Size
        sliderChatTextSize.addOnChangeListener((slider, value, fromUser) -> {
            tvChatTextSize.setText(String.format(java.util.Locale.US, "%.1f sp", value));
            applyPreviewTextSize(value);
            prefs.edit().putFloat("chat_text_size_sp", value).apply();
        });

        // Reset All
        btnResetDefaultWallpaper.setOnClickListener(v -> {
            prefs.edit()
                    .putString("wallpaper_type", "default")
                    .remove("wallpaper_value")
                    .putInt("wallpaper_dim", 0)
                    .putBoolean("doodle_pattern_enabled", false)
                    .putBoolean("amoled_pure_black", false)
                    .putString("chat_bubble_accent", "#4F46E5")
                    .putFloat("chat_bubble_corner_radius_dp", 18.0f)
                    .putFloat("chat_text_size_sp", 15.5f)
                    .putInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    .apply();

            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            loadCurrentSettings();
            Toast.makeText(requireContext(), "All appearance settings reset to default", Toast.LENGTH_SHORT).show();
        });
    }

    private void launchUCrop(Uri sourceUri) {
        File dir = new File(requireContext().getFilesDir(), "app_wallpapers");
        if (!dir.exists()) dir.mkdirs();
        File destFile = new File(dir, "custom_wallpaper_" + System.currentTimeMillis() + ".jpg");
        Uri destUri = Uri.fromFile(destFile);

        UCrop.Options options = new UCrop.Options();
        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
        options.setCompressionQuality(92);
        options.setToolbarColor(requireContext().getColor(R.color.colorSurface));
        options.setStatusBarColor(requireContext().getColor(R.color.colorSurface));
        options.setToolbarWidgetColor(requireContext().getColor(R.color.textPrimary));
        options.setToolbarTitle("Crop Chat Wallpaper");

        UCrop uCrop = UCrop.of(sourceUri, destUri)
                .withAspectRatio(9, 16)
                .withMaxResultSize(1080, 1920)
                .withOptions(options);

        uCropLauncher.launch(uCrop.getIntent(requireContext()));
    }

    private void applyCustomWallpaper(String uriString) {
        prefs.edit()
                .putString("wallpaper_type", "image")
                .putString("wallpaper_value", uriString)
                .apply();

        renderWallpaperPreview("image", uriString);
        Toast.makeText(requireContext(), "Custom wallpaper applied", Toast.LENGTH_SHORT).show();
    }

    private void setupWpPreset(View swatch, String hexColor, String name) {
        if (swatch == null) return;
        swatch.setOnClickListener(v -> {
            prefs.edit()
                    .putString("wallpaper_type", "color")
                    .putString("wallpaper_value", hexColor)
                    .apply();

            renderWallpaperPreview("color", hexColor);
            Toast.makeText(requireContext(), "Wallpaper preset: " + name, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupGradientPreset(View swatch, String gradientSpec, String name) {
        if (swatch == null) return;
        swatch.setOnClickListener(v -> {
            prefs.edit()
                    .putString("wallpaper_type", "gradient")
                    .putString("wallpaper_value", gradientSpec)
                    .apply();

            renderWallpaperPreview("gradient", gradientSpec);
            Toast.makeText(requireContext(), "Gradient preset: " + name, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupColorSwatch(View swatch, String hexColor, String name) {
        if (swatch == null) return;
        swatch.setOnClickListener(v -> {
            prefs.edit().putString("chat_bubble_accent", hexColor).apply();
            applyBubbleAccent(hexColor);
            Toast.makeText(requireContext(), "✨ Chat Accent: " + name, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDimOverlay(int percent) {
        int alpha = (int) (percent * 2.55f);
        if (viewPreviewDimOverlay != null) {
            viewPreviewDimOverlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        }
    }
}
