package com.m3.veil.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.auth.PhoneAuthActivity;

import java.util.List;

public class PrivacySettingsActivity extends AppCompatActivity {

    private ImageView btnBack;
    private TextView tvPrivacyScoreVal;
    private ProgressBar pbPrivacyScore;
    private TextView tvPrivacyScoreSummary;

    private LinearLayout rowMasterPin;
    private TextView tvMasterPinStatus;

    private LinearLayout rowSecretVaultPassword;
    private TextView tvVaultPasswordStatus;

    private LinearLayout rowDecoyPin;
    private TextView tvDecoyPinStatus;

    private LinearLayout rowSafeWord;
    private TextView tvSafeWordStatus;

    private SwitchCompat switchFlagSecure;
    private SwitchCompat switchStealthTyping;
    private SwitchCompat switchFakeOffline;

    private LinearLayout rowBlockedUsers;
    private TextView tvBlockedCount;

    private MaterialButton btnInstantSecureWipe;

    private VeilFeatureManager featureManager;
    private EncryptedChatDatabase chatDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        featureManager = VeilFeatureManager.getInstance(this);
        chatDb = EncryptedChatDatabase.getInstance(this);

        if (featureManager.isFlagSecureEnabled()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_privacy_settings);
        com.m3.veil.utils.UiAnimator.enableEdgeToEdge(this, findViewById(android.R.id.content));
        bindViews();
        setupListeners();
        refreshUi();
    }

    private void bindViews() {
        btnBack = findViewById(R.id.btnBack);
        tvPrivacyScoreVal = findViewById(R.id.tvPrivacyScoreVal);
        pbPrivacyScore = findViewById(R.id.pbPrivacyScore);
        tvPrivacyScoreSummary = findViewById(R.id.tvPrivacyScoreSummary);

        rowMasterPin = findViewById(R.id.rowMasterPin);
        tvMasterPinStatus = findViewById(R.id.tvMasterPinStatus);

        rowSecretVaultPassword = findViewById(R.id.rowSecretVaultPassword);
        tvVaultPasswordStatus = findViewById(R.id.tvVaultPasswordStatus);

        rowDecoyPin = findViewById(R.id.rowDecoyPin);
        tvDecoyPinStatus = findViewById(R.id.tvDecoyPinStatus);

        rowSafeWord = findViewById(R.id.rowSafeWord);
        tvSafeWordStatus = findViewById(R.id.tvSafeWordStatus);

        switchFlagSecure = findViewById(R.id.switchFlagSecure);
        switchStealthTyping = findViewById(R.id.switchStealthTyping);
        switchFakeOffline = findViewById(R.id.switchFakeOffline);

        rowBlockedUsers = findViewById(R.id.rowBlockedUsers);
        tvBlockedCount = findViewById(R.id.tvBlockedCount);

        btnInstantSecureWipe = findViewById(R.id.btnInstantSecureWipe);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        rowMasterPin.setOnClickListener(v -> handleMasterPinClick());
        rowSecretVaultPassword.setOnClickListener(v -> handleSecretVaultPasswordClick());
        rowDecoyPin.setOnClickListener(v -> handleDecoyPinClick());
        rowSafeWord.setOnClickListener(v -> handleSafeWordClick());

        switchFlagSecure.setOnCheckedChangeListener((btn, isChecked) -> {
            featureManager.setFlagSecureEnabled(isChecked);
            if (isChecked) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            refreshUi();
        });

        switchStealthTyping.setOnCheckedChangeListener((btn, isChecked) -> {
            featureManager.setStealthTypingEnabled(isChecked);
            refreshUi();
        });

        switchFakeOffline.setOnCheckedChangeListener((btn, isChecked) -> {
            featureManager.setFakeOfflineEnabled(isChecked);
            refreshUi();
        });

        rowBlockedUsers.setOnClickListener(v -> showBlockedUsersSheet());
        btnInstantSecureWipe.setOnClickListener(v -> showInstantSecureWipeDialog());

        appendAutoLockAndDeadManSwitchControls();
    }

    private void appendAutoLockAndDeadManSwitchControls() {
        if (rowSafeWord == null || !(rowSafeWord.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) rowSafeWord.getParent();
        float dp = getResources().getDisplayMetrics().density;
        android.content.SharedPreferences prefs = getSharedPreferences("veil_privacy_prefs", MODE_PRIVATE);

        LinearLayout rowAutoLock = new LinearLayout(this);
        rowAutoLock.setOrientation(LinearLayout.VERTICAL);
        rowAutoLock.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

        TextView tvAutoLockTitle = new TextView(this);
        tvAutoLockTitle.setText("⏱️ Biometric / PIN Auto-Lock Timeout");
        tvAutoLockTitle.setTextSize(15f);
        tvAutoLockTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAutoLockTitle.setTextColor(getColor(R.color.textPrimary));
        rowAutoLock.addView(tvAutoLockTitle);

        TextView tvAutoLockSub = new TextView(this);
        tvAutoLockSub.setText("Lock after: " + prefs.getString("auto_lock_timeout_label", "1 Minute"));
        tvAutoLockSub.setTextSize(13f);
        tvAutoLockSub.setTextColor(getColor(R.color.colorPrimary));
        rowAutoLock.addView(tvAutoLockSub);

        rowAutoLock.setOnClickListener(v -> {
            String[] opts = {"Immediate", "1 Minute", "5 Minutes", "15 Minutes", "30 Minutes"};
            new MaterialAlertDialogBuilder(this)
                    .setTitle("⏱️ Auto-Lock Timeout")
                    .setItems(opts, (d, which) -> {
                        prefs.edit().putString("auto_lock_timeout_label", opts[which]).apply();
                        tvAutoLockSub.setText("Lock after: " + opts[which]);
                        Toast.makeText(this, "Auto-lock timeout set to " + opts[which], Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });

        LinearLayout rowDeadMan = new LinearLayout(this);
        rowDeadMan.setOrientation(LinearLayout.VERTICAL);
        rowDeadMan.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

        TextView tvDeadManTitle = new TextView(this);
        tvDeadManTitle.setText("💀 Dead-Man's Switch (Inactivity Auto-Shred)");
        tvDeadManTitle.setTextSize(15f);
        tvDeadManTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvDeadManTitle.setTextColor(getColor(R.color.textPrimary));
        rowDeadMan.addView(tvDeadManTitle);

        TextView tvDeadManSub = new TextView(this);
        tvDeadManSub.setText("Auto-wipe local vault if app not unlocked for: " + prefs.getString("dead_man_switch_label", "Off"));
        tvDeadManSub.setTextSize(13f);
        tvDeadManSub.setTextColor(getColor(R.color.textSecondary));
        rowDeadMan.addView(tvDeadManSub);

        rowDeadMan.setOnClickListener(v -> {
            String[] opts = {"Off", "7 Days of Inactivity", "30 Days of Inactivity", "90 Days of Inactivity"};
            new MaterialAlertDialogBuilder(this)
                    .setTitle("💀 Dead-Man's Switch")
                    .setItems(opts, (d, which) -> {
                        prefs.edit().putString("dead_man_switch_label", opts[which]).apply();
                        tvDeadManSub.setText("Auto-wipe local vault if app not unlocked for: " + opts[which]);
                        Toast.makeText(this, "Dead-Man's Switch: " + opts[which], Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });

        int idx = parent.indexOfChild(rowSafeWord);
        parent.addView(rowAutoLock, idx + 1);
        parent.addView(rowDeadMan, idx + 2);
    }

    private void refreshUi() {
        VeilFeatureManager.PrivacyScoreBreakdown score = featureManager.getPrivacyScoreBreakdown();
        tvPrivacyScoreVal.setText(score.totalScore + "/100");
        pbPrivacyScore.setProgress(score.totalScore);

        StringBuilder summary = new StringBuilder();
        if (score.hardwareKeystoreActive) summary.append("Keystore ✓ ");
        if (score.pinConfigured) summary.append("Master PIN ✓ ");
        if (score.decoyConfigured) summary.append("Decoy ✓ ");
        if (score.torRelayActive) summary.append("Tor/Relay ✓ ");
        if (score.flagSecureActive) summary.append("FLAG_SECURE ✓ ");
        if (summary.length() == 0) summary.append("Standard Protection");
        tvPrivacyScoreSummary.setText(summary.toString());

        if (featureManager.hasMasterPin()) {
            tvMasterPinStatus.setText("Active (4-digit PIN)");
            tvMasterPinStatus.setTextColor(getColor(R.color.colorPrimary));
        } else {
            tvMasterPinStatus.setText("Not configured (Recommended)");
            tvMasterPinStatus.setTextColor(getColor(R.color.textSecondary));
        }

        if (featureManager.hasSecretVaultPassword()) {
            tvVaultPasswordStatus.setText("Dedicated Password Active");
            tvVaultPasswordStatus.setTextColor(getColor(R.color.colorPrimary));
        } else {
            tvVaultPasswordStatus.setText("Uses Master PIN as fallback");
            tvVaultPasswordStatus.setTextColor(getColor(R.color.textSecondary));
        }

        String decoy = featureManager.getDecoyPin();
        if (decoy != null && !decoy.isEmpty()) {
            tvDecoyPinStatus.setText("Decoy session configured");
            tvDecoyPinStatus.setTextColor(getColor(R.color.colorPrimary));
        } else {
            tvDecoyPinStatus.setText("Not configured");
            tvDecoyPinStatus.setTextColor(getColor(R.color.textSecondary));
        }

        String safeWord = featureManager.getSafeWord();
        tvSafeWordStatus.setText("Trigger: \"" + (safeWord != null ? safeWord : "!veilwipe") + "\"");

        switchFlagSecure.setChecked(featureManager.isFlagSecureEnabled());
        switchStealthTyping.setChecked(featureManager.isStealthTypingEnabled());
        switchFakeOffline.setChecked(featureManager.isFakeOfflineEnabled());

        int blockedCount = chatDb.getBlockedChatsCount();
        tvBlockedCount.setText(blockedCount + (blockedCount == 1 ? " contact" : " contacts"));
    }

    // ==========================================
    // MASTER PIN MANAGEMENT
    // ==========================================

    private void handleMasterPinClick() {
        if (!featureManager.hasMasterPin()) {
            promptSetNewPin("Set Master PIN", "Enter a 4-digit PIN for VEIL:", pin -> {
                featureManager.setMasterPin(pin);
                Toast.makeText(this, "Master PIN set successfully", Toast.LENGTH_SHORT).show();
                refreshUi();
            });
        } else {
            promptVerifyPin("Authenticate", "Enter current Master PIN to modify:", () -> {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Master PIN Options")
                        .setItems(new CharSequence[]{"Change Master PIN", "Remove Master PIN"}, (dialog, which) -> {
                            if (which == 0) {
                                promptSetNewPin("Change Master PIN", "Enter new 4-digit PIN:", newPin -> {
                                    featureManager.setMasterPin(newPin);
                                    Toast.makeText(this, "Master PIN updated", Toast.LENGTH_SHORT).show();
                                    refreshUi();
                                });
                            } else {
                                featureManager.setMasterPin(null);
                                Toast.makeText(this, "Master PIN removed", Toast.LENGTH_SHORT).show();
                                refreshUi();
                            }
                        })
                        .show();
            });
        }
    }

    // ==========================================
    // SECRET VAULT PASSWORD MANAGEMENT
    // ==========================================

    private void handleSecretVaultPasswordClick() {
        if (!featureManager.hasSecretVaultPassword()) {
            promptSetTextPassword("Set Secret Vault Password", "Enter password for secret conversations:", pwd -> {
                featureManager.setSecretVaultPassword(pwd);
                Toast.makeText(this, "Secret Vault password saved", Toast.LENGTH_SHORT).show();
                refreshUi();
            });
        } else {
            promptVerifyVaultPassword(() -> {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Secret Vault Password")
                        .setItems(new CharSequence[]{"Change Password", "Remove Dedicated Password (Use Master PIN)"}, (dialog, which) -> {
                            if (which == 0) {
                                promptSetTextPassword("Change Vault Password", "Enter new password:", newPwd -> {
                                    featureManager.setSecretVaultPassword(newPwd);
                                    Toast.makeText(this, "Vault password changed", Toast.LENGTH_SHORT).show();
                                    refreshUi();
                                });
                            } else {
                                featureManager.setSecretVaultPassword(null);
                                Toast.makeText(this, "Dedicated vault password removed", Toast.LENGTH_SHORT).show();
                                refreshUi();
                            }
                        })
                        .show();
            });
        }
    }

    // ==========================================
    // DECOY PIN MANAGEMENT
    // ==========================================

    private void handleDecoyPinClick() {
        String existing = featureManager.getDecoyPin();
        if (existing == null || existing.isEmpty()) {
            promptSetNewPin("Set Decoy PIN", "Enter Decoy PIN (opens fake harmless session under duress):", pin -> {
                if (pin.equals(featureManager.getMasterPin())) {
                    Toast.makeText(this, "Decoy PIN must differ from your Master PIN", Toast.LENGTH_LONG).show();
                    return;
                }
                featureManager.setDecoyPin(pin);
                Toast.makeText(this, "Decoy PIN configured", Toast.LENGTH_SHORT).show();
                refreshUi();
            });
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Decoy PIN Management")
                    .setItems(new CharSequence[]{"Change Decoy PIN", "Remove Decoy PIN"}, (dialog, which) -> {
                        if (which == 0) {
                            promptSetNewPin("Change Decoy PIN", "Enter new Decoy PIN:", newPin -> {
                                if (newPin.equals(featureManager.getMasterPin())) {
                                    Toast.makeText(this, "Decoy PIN must differ from Master PIN", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                featureManager.setDecoyPin(newPin);
                                Toast.makeText(this, "Decoy PIN updated", Toast.LENGTH_SHORT).show();
                                refreshUi();
                            });
                        } else {
                            featureManager.setDecoyPin(null);
                            Toast.makeText(this, "Decoy PIN disabled", Toast.LENGTH_SHORT).show();
                            refreshUi();
                        }
                    })
                    .show();
        }
    }

    // ==========================================
    // PANIC SAFE WORD MANAGEMENT
    // ==========================================

    private void handleSafeWordClick() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_auth, null);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(dialogView);

        TextView tvTitle = dialogView.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = dialogView.findViewById(R.id.tvPinDialogSubtitle);
        TextInputEditText etPin = dialogView.findViewById(R.id.etPin);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btnCancelPin);
        MaterialButton btnConfirm = dialogView.findViewById(R.id.btnConfirmPin);

        tvTitle.setText("🚨 Panic Safe-Word Trigger");
        tvSubtitle.setText("Typing this exact string in any chat immediately executes a multi-pass cryptographic zero-wipe of the local database:");
        etPin.setInputType(InputType.TYPE_CLASS_TEXT);
        etPin.setLetterSpacing(0f);
        etPin.setText(featureManager.getSafeWord());
        etPin.setSelection(etPin.getText().length());
        btnConfirm.setText("Save Safe Word");

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String word = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (TextUtils.isEmpty(word)) {
                Toast.makeText(this, "Safe word cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            featureManager.setSafeWord(word);
            Toast.makeText(this, "Panic safe-word updated to: " + word, Toast.LENGTH_LONG).show();
            dialog.dismiss();
            refreshUi();
        });
        dialog.show();
    }

    // ==========================================
    // BLOCKED CONTACTS SHEET
    // ==========================================

    private void showBlockedUsersSheet() {
        List<Chat> blockedList = chatDb.getBlockedChats();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_pin_auth, null);
        dialog.setContentView(sheet);

        TextView tvTitle = sheet.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = sheet.findViewById(R.id.tvPinDialogSubtitle);
        sheet.findViewById(R.id.pinInputLayout).setVisibility(View.GONE);
        sheet.findViewById(R.id.btnConfirmPin).setVisibility(View.GONE);

        tvTitle.setText("Blocked Contacts (" + blockedList.size() + ")");
        if (blockedList.isEmpty()) {
            tvSubtitle.setText("No blocked contacts. You can block any contact from their chat profile.");
        } else {
            tvSubtitle.setText("Tap 'Unblock' to restore messaging and calls:");
        }

        MaterialButton btnCancel = sheet.findViewById(R.id.btnCancelPin);
        btnCancel.setText("Close");
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (!blockedList.isEmpty()) {
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new BlockedAdapter(blockedList, chat -> {
                chatDb.setChatBlocked(chat.getId(), false, null);
                Toast.makeText(this, "Unblocked " + chat.getName(), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                refreshUi();
            }));
            ViewGroup parent = (ViewGroup) sheet.findViewById(R.id.pinInputLayout).getParent();
            int index = parent.indexOfChild(sheet.findViewById(R.id.pinInputLayout));
            parent.addView(rv, index, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 500));
        }

        dialog.show();
    }

    // ==========================================
    // INSTANT CRYPTOGRAPHIC SECURE WIPE
    // ==========================================

    private void showInstantSecureWipeDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("🚨 Cryptographic Instant Wipe")
                .setMessage("DANGER: This action is completely IRREVERSIBLE.\n\nAll encrypted chats, vault keys, media files, and local credentials will be immediately shredded and purged using multi-pass zero-wipe.")
                .setPositiveButton("I UNDERSTAND, WIPE ALL", (d, w) -> {
                    // Double check with PIN if configured
                    if (featureManager.hasMasterPin()) {
                        promptVerifyPin("Authorize Wipe", "Enter Master PIN to authorize destructive shredding:", () -> executeTotalWipe());
                    } else {
                        executeTotalWipe();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executeTotalWipe() {
        try {
            com.m3.veil.manager.AuthManager.getInstance().signOut(this);
        } catch (Throwable ignored) {}
        chatDb.secureWipeAllData();
        getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("VeilPrefs", Context.MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("VeilUserPrefs", Context.MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("VeilPreferences", Context.MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("veil_sovereign_identity_prefs", Context.MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE).edit().clear().apply();
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                deleteSharedPreferences("veil_encrypted_prefs");
            }
        } catch (Throwable ignored) {}

        Toast.makeText(this, "All cryptographic local data has been shredded.", Toast.LENGTH_LONG).show();

        Intent intent = new Intent(this, PhoneAuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
    }

    // ==========================================
    // AUTH HELPER PROMPTS
    // ==========================================

    private interface OnPinEnteredListener {
        void onPinEntered(String pin);
    }

    private void promptSetNewPin(String title, String subtitle, OnPinEnteredListener listener) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_pin_auth, null);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(v);

        TextView tvTitle = v.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvPinDialogSubtitle);
        TextInputEditText etPin = v.findViewById(R.id.etPin);
        MaterialButton btnCancel = v.findViewById(R.id.btnCancelPin);
        MaterialButton btnConfirm = v.findViewById(R.id.btnConfirmPin);

        tvTitle.setText(title);
        tvSubtitle.setText(subtitle);
        btnConfirm.setText("Set PIN");

        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (entered.length() < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            listener.onPinEntered(entered);
        });
        dialog.show();
    }

    private void promptSetTextPassword(String title, String subtitle, OnPinEnteredListener listener) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_pin_auth, null);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(v);

        TextView tvTitle = v.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvPinDialogSubtitle);
        TextInputEditText etPin = v.findViewById(R.id.etPin);
        MaterialButton btnCancel = v.findViewById(R.id.btnCancelPin);
        MaterialButton btnConfirm = v.findViewById(R.id.btnConfirmPin);

        tvTitle.setText(title);
        tvSubtitle.setText(subtitle);
        etPin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPin.setLetterSpacing(0f);
        etPin.setHint("Enter strong password");
        btnConfirm.setText("Save");

        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (entered.length() < 4) {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            listener.onPinEntered(entered);
        });
        dialog.show();
    }

    private void promptVerifyPin(String title, String subtitle, Runnable onSuccess) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_pin_auth, null);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(v);

        TextView tvTitle = v.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvPinDialogSubtitle);
        TextInputEditText etPin = v.findViewById(R.id.etPin);
        MaterialButton btnCancel = v.findViewById(R.id.btnCancelPin);
        MaterialButton btnConfirm = v.findViewById(R.id.btnConfirmPin);

        tvTitle.setText(title);
        tvSubtitle.setText(subtitle);
        btnConfirm.setText("Verify");

        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (featureManager.verifyMasterPin(entered)) {
                dialog.dismiss();
                onSuccess.run();
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show();
                etPin.setText("");
            }
        });
        dialog.show();
    }

    private void promptVerifyVaultPassword(Runnable onSuccess) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_pin_auth, null);
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(v);

        TextView tvTitle = v.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvPinDialogSubtitle);
        TextInputEditText etPin = v.findViewById(R.id.etPin);
        MaterialButton btnCancel = v.findViewById(R.id.btnCancelPin);
        MaterialButton btnConfirm = v.findViewById(R.id.btnConfirmPin);

        tvTitle.setText("Verify Vault Password");
        tvSubtitle.setText("Enter current dedicated password to proceed:");
        etPin.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPin.setLetterSpacing(0f);
        btnConfirm.setText("Verify");

        btnCancel.setOnClickListener(view -> dialog.dismiss());
        btnConfirm.setOnClickListener(view -> {
            String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (featureManager.verifySecretVaultPassword(entered)) {
                dialog.dismiss();
                onSuccess.run();
            } else {
                Toast.makeText(this, "Incorrect Vault Password", Toast.LENGTH_SHORT).show();
                etPin.setText("");
            }
        });
        dialog.show();
    }

    // ==========================================
    // BLOCKED CONTACTS ADAPTER
    // ==========================================

    private static class BlockedAdapter extends RecyclerView.Adapter<BlockedAdapter.VH> {
        private final List<Chat> list;
        private final OnUnblockListener listener;

        interface OnUnblockListener {
            void onUnblock(Chat chat);
        }

        BlockedAdapter(List<Chat> list, OnUnblockListener listener) {
            this.list = list;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(parent.getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(16, 16, 16, 16);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvName = new TextView(parent.getContext());
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tvName.setTextSize(15f);
            tvName.setTextColor(parent.getContext().getColor(R.color.textPrimary));
            item.addView(tvName);

            MaterialButton btnUnblock = new MaterialButton(parent.getContext());
            btnUnblock.setText("Unblock");
            btnUnblock.setTextColor(parent.getContext().getColor(R.color.colorPrimary));
            item.addView(btnUnblock);

            return new VH(item, tvName, btnUnblock);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Chat c = list.get(position);
            holder.tvName.setText(c.getName() != null ? c.getName() : "Unknown Contact");
            holder.btnUnblock.setOnClickListener(v -> listener.onUnblock(c));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            MaterialButton btnUnblock;

            VH(@NonNull View itemView, TextView tvName, MaterialButton btnUnblock) {
                super(itemView);
                this.tvName = tvName;
                this.btnUnblock = btnUnblock;
            }
        }
    }
}
