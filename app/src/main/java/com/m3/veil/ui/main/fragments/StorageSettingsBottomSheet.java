package com.m3.veil.ui.main.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.m3.veil.R;
import com.m3.veil.utils.UiAnimator;

import java.io.File;

public class StorageSettingsBottomSheet extends BottomSheetDialogFragment {

    private TextView tvCacheSize;
    private MaterialButton btnClearCache;
    private MaterialSwitch switchAutoDownloadWifi, switchAutoDownloadCellular;
    private SharedPreferences prefs;

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_storage_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences("veil_storage_prefs", Context.MODE_PRIVATE);

        tvCacheSize = view.findViewById(R.id.tvCacheSize);
        btnClearCache = view.findViewById(R.id.btnClearCache);
        switchAutoDownloadWifi = view.findViewById(R.id.switchAutoDownloadWifi);
        switchAutoDownloadCellular = view.findViewById(R.id.switchAutoDownloadCellular);

        UiAnimator.attachSpringPress(btnClearCache);
        calculateCacheSize();

        btnClearCache.setOnClickListener(v -> clearCache());

        switchAutoDownloadWifi.setChecked(prefs.getBoolean("auto_download_wifi", true));
        switchAutoDownloadWifi.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean("auto_download_wifi", isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "Wi-Fi auto-download enabled" : "Wi-Fi auto-download disabled", Toast.LENGTH_SHORT).show();
        });

        switchAutoDownloadCellular.setChecked(prefs.getBoolean("auto_download_cellular", false));
        switchAutoDownloadCellular.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean("auto_download_cellular", isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "Cellular auto-download enabled" : "Cellular auto-download disabled", Toast.LENGTH_SHORT).show();
        });
    }

    private void calculateCacheSize() {
        new Thread(() -> {
            long size = getDirSize(requireContext().getCacheDir());
            File extCache = requireContext().getExternalCacheDir();
            if (extCache != null) size += getDirSize(extCache);
            String formatted = Formatter.formatFileSize(requireContext(), size);
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> tvCacheSize.setText(formatted));
            }
        }).start();
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        size += file.length();
                    } else {
                        size += getDirSize(file);
                    }
                }
            }
        } else if (dir != null && dir.isFile()) {
            size += dir.length();
        }
        return size;
    }

    private void clearCache() {
        new Thread(() -> {
            deleteDir(requireContext().getCacheDir());
            File extCache = requireContext().getExternalCacheDir();
            if (extCache != null) deleteDir(extCache);
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show();
                    calculateCacheSize();
                });
            }
        }).start();
    }

    private boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    deleteDir(new File(dir, child));
                }
            }
        }
        return dir != null && dir.delete();
    }
}
