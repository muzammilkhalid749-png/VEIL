package com.m3.veil.ui.main.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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
    private View viewPreviewDimOverlay;
    private LinearLayout previewOutgoingBubble;

    private RadioGroup rgThemeMode;
    private RadioButton rbThemeSystem, rbThemeLight, rbThemeDark;
    private MaterialSwitch switchAmoledBlack;

    private MaterialButton btnPickPhotoWallpaper;
    private Slider sliderWallpaperDim;
    private TextView tvDimPercent;
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
        viewPreviewDimOverlay = view.findViewById(R.id.viewPreviewDimOverlay);
        previewOutgoingBubble = view.findViewById(R.id.previewOutgoingBubble);

        rgThemeMode = view.findViewById(R.id.rgThemeMode);
        rbThemeSystem = view.findViewById(R.id.rbThemeSystem);
        rbThemeLight = view.findViewById(R.id.rbThemeLight);
        rbThemeDark = view.findViewById(R.id.rbThemeDark);
        switchAmoledBlack = view.findViewById(R.id.switchAmoledBlack);

        btnPickPhotoWallpaper = view.findViewById(R.id.btnPickPhotoWallpaper);
        sliderWallpaperDim = view.findViewById(R.id.sliderWallpaperDim);
        tvDimPercent = view.findViewById(R.id.tvDimPercent);
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

        // Wallpaper
        String wpType = prefs.getString("wallpaper_type", "default");
        String wpValue = prefs.getString("wallpaper_value", "#0D1117");
        int dimLevel = prefs.getInt("wallpaper_dim", 0);

        sliderWallpaperDim.setValue(dimLevel);
        tvDimPercent.setText(dimLevel + "%");
        updateDimOverlay(dimLevel);

        if ("image".equals(wpType) && wpValue != null && !wpValue.isEmpty()) {
            ivPreviewWallpaper.setVisibility(View.VISIBLE);
            try {
                ivPreviewWallpaper.setImageURI(Uri.parse(wpValue));
            } catch (Exception ignored) {}
        } else {
            ivPreviewWallpaper.setVisibility(View.GONE);
            try {
                previewWallpaperContainer.setBackgroundColor(Color.parseColor(wpValue));
            } catch (Exception e) {
                previewWallpaperContainer.setBackgroundColor(Color.parseColor("#0D1117"));
            }
        }

        // Bubble Accent
        String accent = prefs.getString("chat_bubble_accent", "#0B57CF");
        try {
            previewOutgoingBubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(accent)));
        } catch (Exception ignored) {}
    }

    private void setupListeners(View view) {
        rgThemeMode.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (checkedId == R.id.rbThemeLight) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rbThemeDark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            }
            prefs.edit().putInt("theme_mode", newMode).apply();
            AppCompatDelegate.setDefaultNightMode(newMode);
        });

        switchAmoledBlack.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("amoled_pure_black", isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "🌑 Pure AMOLED Black Active" : "Pure Black Disabled", Toast.LENGTH_SHORT).show();
        });

        btnPickPhotoWallpaper.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            galleryPickerLauncher.launch(Intent.createChooser(intent, "Choose Wallpaper"));
        });

        sliderWallpaperDim.addOnChangeListener((slider, value, fromUser) -> {
            int intVal = (int) value;
            tvDimPercent.setText(intVal + "%");
            updateDimOverlay(intVal);
            prefs.edit().putInt("wallpaper_dim", intVal).apply();
        });

        // Wallpaper Presets
        setupWpPreset(view.findViewById(R.id.wpPresetDefault), "#0D1117", "Midnight Abyss");
        setupWpPreset(view.findViewById(R.id.wpPresetIndigo), "#121026", "Cyber Indigo");
        setupWpPreset(view.findViewById(R.id.wpPresetEmerald), "#091E1A", "Emerald Matrix");
        setupWpPreset(view.findViewById(R.id.wpPresetCrimson), "#240B15", "Crimson Velvet");
        setupWpPreset(view.findViewById(R.id.wpPresetOcean), "#0A192F", "Deep Ocean");

        // Accent Colors
        setupColorSwatch(view.findViewById(R.id.colorCyan), "#00E5FF", "Veil Cyan");
        setupColorSwatch(view.findViewById(R.id.colorEmerald), "#00E676", "Matrix Emerald");
        setupColorSwatch(view.findViewById(R.id.colorViolet), "#D500F9", "Cyberpunk Violet");
        setupColorSwatch(view.findViewById(R.id.colorAmber), "#FFD600", "Solar Amber");
        setupColorSwatch(view.findViewById(R.id.colorBlue), "#2979FF", "Cobalt Blue");

        btnResetDefaultWallpaper.setOnClickListener(v -> {
            prefs.edit()
                    .putString("wallpaper_type", "default")
                    .putString("wallpaper_value", "#0D1117")
                    .putInt("wallpaper_dim", 0)
                    .putString("chat_bubble_accent", "#0B57CF")
                    .apply();

            loadCurrentSettings();
            Toast.makeText(requireContext(), "Wallpaper & Accent reset to default", Toast.LENGTH_SHORT).show();
        });
    }

    private void launchUCrop(Uri sourceUri) {
        File destFile = new File(requireContext().getCacheDir(), "wallpaper_" + System.currentTimeMillis() + ".jpg");
        Uri destUri = Uri.fromFile(destFile);

        UCrop.Options options = new UCrop.Options();
        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
        options.setCompressionQuality(90);
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

        ivPreviewWallpaper.setVisibility(View.VISIBLE);
        try {
            ivPreviewWallpaper.setImageURI(Uri.parse(uriString));
        } catch (Exception ignored) {}
        Toast.makeText(requireContext(), "Custom wallpaper applied", Toast.LENGTH_SHORT).show();
    }

    private void setupWpPreset(View swatch, String hexColor, String name) {
        if (swatch == null) return;
        swatch.setOnClickListener(v -> {
            prefs.edit()
                    .putString("wallpaper_type", "color")
                    .putString("wallpaper_value", hexColor)
                    .apply();

            ivPreviewWallpaper.setVisibility(View.GONE);
            previewWallpaperContainer.setBackgroundColor(Color.parseColor(hexColor));
            Toast.makeText(requireContext(), "Wallpaper preset: " + name, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupColorSwatch(View swatch, String hexColor, String name) {
        if (swatch == null) return;
        swatch.setOnClickListener(v -> {
            prefs.edit().putString("chat_bubble_accent", hexColor).apply();
            try {
                previewOutgoingBubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(hexColor)));
            } catch (Exception ignored) {}
            Toast.makeText(requireContext(), "✨ Chat Accent: " + name, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDimOverlay(int percent) {
        int alpha = (int) (percent * 2.55f);
        viewPreviewDimOverlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
    }
}
