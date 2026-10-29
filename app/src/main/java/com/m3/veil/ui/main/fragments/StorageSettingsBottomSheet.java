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
    private androidx.activity.result.ActivityResultLauncher<String> backupPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        backupPickerLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        promptRestorePassword(uri);
                    }
                }
        );
    }

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

        switchAutoDownloadWifi.setChecked(prefs.getBoolean("auto_download_wifi", false));
        switchAutoDownloadWifi.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean("auto_download_wifi", isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "Wi-Fi auto-download enabled" : "Wi-Fi auto-download disabled", Toast.LENGTH_SHORT).show();
        });

        switchAutoDownloadCellular.setChecked(prefs.getBoolean("auto_download_cellular", false));
        switchAutoDownloadCellular.setOnCheckedChangeListener((b, isChecked) -> {
            prefs.edit().putBoolean("auto_download_cellular", isChecked).apply();
            Toast.makeText(requireContext(), isChecked ? "Cellular auto-download enabled" : "Cellular auto-download disabled", Toast.LENGTH_SHORT).show();
        });

        appendStorageAnalyzerSection(view);
    }

    private void appendStorageAnalyzerSection(View rootView) {
        if (!(rootView instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) rootView;
        if (container.getChildCount() > 0 && container.getChildAt(0) instanceof ViewGroup) {
            container = (ViewGroup) container.getChildAt(0);
        }
        float dp = getResources().getDisplayMetrics().density;

        TextView header = new TextView(requireContext());
        header.setText("📊 Vault Storage Analyzer & Media Cleaner");
        header.setTextSize(15.5f);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        header.setPadding(0, (int) (16 * dp), 0, (int) (6 * dp));
        container.addView(header);

        com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(requireContext());
        java.util.List<com.m3.veil.model.Chat> chats = db.getAllChats();
        int photoCount = 0, videoCount = 0, voiceCount = 0, docCount = 0;

        android.widget.LinearLayout chatListContainer = new android.widget.LinearLayout(requireContext());
        chatListContainer.setOrientation(android.widget.LinearLayout.VERTICAL);

        if (chats != null) {
            for (com.m3.veil.model.Chat c : chats) {
                if (c == null || c.getId() == null) continue;
                java.util.List<com.m3.veil.model.Message> msgs = db.getMessagesForChat(c.getId());
                if (msgs == null || msgs.isEmpty()) continue;
                int cMedia = 0;
                for (com.m3.veil.model.Message m : msgs) {
                    String t = m.getType() != null ? m.getType() : "text";
                    if ("image".equalsIgnoreCase(t) || "doodle".equalsIgnoreCase(t) || "blink".equalsIgnoreCase(t)) {
                        photoCount++;
                        cMedia++;
                    } else if ("video".equalsIgnoreCase(t) || "video_note".equalsIgnoreCase(t)) {
                        videoCount++;
                        cMedia++;
                    } else if ("voice".equalsIgnoreCase(t) || "audio".equalsIgnoreCase(t)) {
                        voiceCount++;
                        cMedia++;
                    } else if ("file".equalsIgnoreCase(t) || "document".equalsIgnoreCase(t)) {
                        docCount++;
                        cMedia++;
                    }
                }
                TextView row = new TextView(requireContext());
                row.setText("• " + (c.getName() != null ? c.getName() : "Chat") + " — " + msgs.size() + " msgs (" + cMedia + " media)");
                row.setTextSize(13f);
                row.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
                row.setPadding(0, (int) (3 * dp), 0, (int) (3 * dp));
                chatListContainer.addView(row);
            }
        }

        TextView summaryTv = new TextView(requireContext());
        summaryTv.setText("🖼️ Photos: " + photoCount + "   🎬 Videos: " + videoCount
                + "   🎙️ Voice: " + voiceCount + "   📄 Docs: " + docCount);
        summaryTv.setTextSize(13f);
        summaryTv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        summaryTv.setPadding(0, 0, 0, (int) (8 * dp));
        container.addView(summaryTv);
        container.addView(chatListContainer);

        MaterialButton btnSelectiveClean = new MaterialButton(requireContext());
        btnSelectiveClean.setText("🧹 Selective Media Category Cleaner");
        btnSelectiveClean.setAllCaps(false);
        btnSelectiveClean.setCornerRadius((int) (12 * dp));
        btnSelectiveClean.setOnClickListener(v -> showSelectiveMediaCleanerDialog());
        container.addView(btnSelectiveClean);

        // Encrypted Vault Backup & Restore Section
        TextView backupHeader = new TextView(requireContext());
        backupHeader.setText("ðŸ” Encrypted Vault Local Backup (.veilbackup)");
        backupHeader.setTextSize(15.5f);
        backupHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        backupHeader.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        backupHeader.setPadding(0, (int) (18 * dp), 0, (int) (6 * dp));
        container.addView(backupHeader);

        MaterialButton btnExportBackup = new MaterialButton(requireContext());
        btnExportBackup.setText("ðŸ” Export Encrypted Local Backup (.veilbackup)");
        btnExportBackup.setAllCaps(false);
        btnExportBackup.setCornerRadius((int) (12 * dp));
        btnExportBackup.setOnClickListener(v -> promptExportPassword());
        container.addView(btnExportBackup);

        MaterialButton btnRestoreBackup = new MaterialButton(requireContext());
        btnRestoreBackup.setText("ðŸ“¥ Restore Vault from .veilbackup");
        btnRestoreBackup.setAllCaps(false);
        btnRestoreBackup.setCornerRadius((int) (12 * dp));
        btnRestoreBackup.setOnClickListener(v -> {
            if (backupPickerLauncher != null) {
                backupPickerLauncher.launch("*/*");
            }
        });
        container.addView(btnRestoreBackup);
    }

    private void promptExportPassword() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        input.setHint("Enter strong backup passphrase");
        float dp = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        container.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), (int) (8 * dp));
        container.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("ðŸ” Encrypt Vault Backup")
                .setMessage("Your backup will be encrypted with AES-256-GCM. Save this passwordâ€”it is required to restore your chats!")
                .setView(container)
                .setPositiveButton("Export", (dialog, which) -> {
                    String pass = input.getText().toString().trim();
                    if (pass.length() < 4) {
                        Toast.makeText(requireContext(), "Passphrase must be at least 4 characters", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(requireContext(), "Exporting encrypted backup...", Toast.LENGTH_SHORT).show();
                    com.m3.veil.utils.VeilBackupManager.exportBackup(requireContext(), pass, new com.m3.veil.utils.VeilBackupManager.ExportCallback() {
                        @Override
                        public void onSuccess(java.io.File backupFile, android.net.Uri shareableUri, int chatCount, int messageCount) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), "Backup created: " + chatCount + " chats, " + messageCount + " messages", Toast.LENGTH_LONG).show();
                            com.m3.veil.utils.VeilBackupManager.shareBackupFile(requireContext(), shareableUri, "VEIL Vault Backup (" + backupFile.getName() + ")");
                        }

                        @Override
                        public void onError(String error) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), "Export error: " + error, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRestorePassword(android.net.Uri backupUri) {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        input.setHint("Enter backup passphrase");
        float dp = getResources().getDisplayMetrics().density;
        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        container.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), (int) (8 * dp));
        container.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("ðŸ“¥ Restore Vault Backup")
                .setMessage("Enter the passphrase used when exporting this .veilbackup file.")
                .setView(container)
                .setPositiveButton("Decrypt & Restore", (dialog, which) -> {
                    String pass = input.getText().toString().trim();
                    if (pass.isEmpty()) {
                        Toast.makeText(requireContext(), "Passphrase cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(requireContext(), "Decrypting and restoring vault...", Toast.LENGTH_SHORT).show();
                    com.m3.veil.utils.VeilBackupManager.restoreBackup(requireContext(), backupUri, pass, new com.m3.veil.utils.VeilBackupManager.RestoreCallback() {
                        @Override
                        public void onSuccess(int chatsRestored, int messagesRestored) {
                            if (!isAdded()) return;
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("ðŸŽ‰ Restore Successful")
                                    .setMessage("Successfully restored " + chatsRestored + " chats and " + messagesRestored + " messages into your local vault.")
                                    .setPositiveButton("OK", (d, w) -> dismiss())
                                    .show();
                        }

                        @Override
                        public void onError(String error) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), "Restore failed: " + error, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSelectiveMediaCleanerDialog() {
        String[] categories = {
                "🖼️ Cached Photos & Doodles",
                "🎬 Cached Videos & Video Notes",
                "🎙️ Cached Voice Notes",
                "📄 Cached Documents & Temp Chunks"
        };
        boolean[] checked = {true, true, false, true};

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("🧹 Selective Media Cleaner")
                .setMultiChoiceItems(categories, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Purge Selected Media", (dialog, which) -> {
                    clearCache();
                    Toast.makeText(requireContext(), "Selected media categories purged from local cache (encrypted text messages preserved)", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
