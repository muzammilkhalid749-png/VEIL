package com.m3.veil.ui.chat;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Typeface;
import androidx.core.content.ContextCompat;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.main.adapters.ChatsAdapter;

import java.util.ArrayList;
import java.util.List;

public class ArchivedChatsActivity extends AppCompatActivity implements ChatsAdapter.OnChatClickListener {

    private RecyclerView archivedRecyclerView;
    private View emptyState;
    private ChatsAdapter chatsAdapter;
    private List<Chat> archivedChats;
    private EncryptedChatDatabase encryptedDb;
    private String currentUserId;
    private boolean isSecretMode = false;
    private TextView tvTitle, tvNoticeText, tvEmptyTitle, tvEmptySubtitle;
    private android.widget.ImageView ivNoticeIcon, ivEmptyIcon;
    private View appBarLayout, archivedMultiSelectBar;
    private TextView tvArchivedSelectCount, btnArchivedSelectAll;
    private androidx.activity.OnBackPressedCallback selectionBackCallback;

    private final EncryptedChatDatabase.DatabaseChangeListener dbListener = new EncryptedChatDatabase.DatabaseChangeListener() {
        @Override
        public void onMessagesUpdated(String chatId) {
            runOnUiThread(() -> loadArchivedChats());
        }

        @Override
        public void onChatsUpdated() {
            runOnUiThread(() -> loadArchivedChats());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_archived_chats);

        handleWindowInsets();

        encryptedDb = EncryptedChatDatabase.getInstance(this);
        currentUserId = com.m3.veil.manager.PersonaManager.getInstance(this).getActivePersonaPeerId();
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        }
        if (currentUserId == null) currentUserId = "";

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvTitle = findViewById(R.id.tvTitle);
        ivNoticeIcon = findViewById(R.id.ivNoticeIcon);
        tvNoticeText = findViewById(R.id.tvNoticeText);
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);

        appBarLayout = findViewById(R.id.appBarLayout);
        archivedMultiSelectBar = findViewById(R.id.archivedMultiSelectBar);
        tvArchivedSelectCount = findViewById(R.id.tvArchivedSelectCount);
        btnArchivedSelectAll = findViewById(R.id.btnArchivedSelectAll);
        View btnArchivedSelectClose = findViewById(R.id.btnArchivedSelectClose);
        View btnArchivedSelectMute = findViewById(R.id.btnArchivedSelectMute);
        View btnArchivedSelectUnarchive = findViewById(R.id.btnArchivedSelectUnarchive);
        View btnArchivedSelectDelete = findViewById(R.id.btnArchivedSelectDelete);

        archivedRecyclerView = findViewById(R.id.archivedRecyclerView);
        emptyState = findViewById(R.id.emptyState);

        archivedChats = new ArrayList<>();
        chatsAdapter = new ChatsAdapter(archivedChats, this);
        chatsAdapter.setOnChatSelectionChangeListener((count, selectedChats) -> updateArchivedMultiSelectBar(count));
        archivedRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        archivedRecyclerView.setAdapter(chatsAdapter);

        if (btnArchivedSelectClose != null) {
            btnArchivedSelectClose.setOnClickListener(v -> chatsAdapter.clearSelection());
        }
        if (btnArchivedSelectAll != null) {
            btnArchivedSelectAll.setOnClickListener(v -> chatsAdapter.selectAllChats());
        }
        if (btnArchivedSelectMute != null) {
            btnArchivedSelectMute.setOnClickListener(v -> performBatchMuteToggle());
        }
        if (btnArchivedSelectUnarchive != null) {
            btnArchivedSelectUnarchive.setOnClickListener(v -> performBatchUnarchive());
        }
        if (btnArchivedSelectDelete != null) {
            btnArchivedSelectDelete.setOnClickListener(v -> {
                List<Chat> selected = chatsAdapter.getSelectedChats();
                if (!selected.isEmpty()) {
                    confirmDeleteSelectedArchivedChats(selected);
                }
            });
        }

        selectionBackCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (chatsAdapter != null && chatsAdapter.isSelectionMode()) {
                    chatsAdapter.clearSelection();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, selectionBackCallback);

        isSecretMode = getIntent().getBooleanExtra("is_secret", false)
                || "secret".equals(getIntent().getStringExtra("mode"));

        if (isSecretMode) {
            if (tvTitle != null) tvTitle.setText("Secret Vault ⚙️");
            if (ivNoticeIcon != null) ivNoticeIcon.setImageResource(R.drawable.ic_lock);
            if (tvNoticeText != null) tvNoticeText.setText("Conversations in your Secret Vault are hidden from your main chats and protected by passcode.");
            if (ivEmptyIcon != null) ivEmptyIcon.setImageResource(R.drawable.ic_lock);
            if (tvEmptyTitle != null) tvEmptyTitle.setText("Secret Vault is empty");
            if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Tap the header above or long-press any chat in your main list to move it to your hidden Secret Vault.");
            promptSecretVaultUnlock();
        } else {
            if (tvTitle != null) {
                tvTitle.setText("Archived Chats ⚙️");
                tvTitle.setOnClickListener(v -> showArchiveSettingsSheet());
            }
            if (tvNoticeText != null) {
                boolean keepArchived = getSharedPreferences("VeilPrefs", MODE_PRIVATE).getBoolean("keep_chats_archived", true);
                tvNoticeText.setText(keepArchived
                        ? "Archived chats stay archived when new messages arrive. Swipe left/right to unarchive, or tap header for settings."
                        : "Archived chats will pop back to main inbox when new messages arrive. Tap header to change.");
                tvNoticeText.setOnClickListener(v -> showArchiveSettingsSheet());
            }
            loadArchivedChats();
        }

        setupSwipeToUnarchive();
        encryptedDb.addListener(dbListener);
    }

    private void setupSwipeToUnarchive() {
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback swipeCallback =
                new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                        0, androidx.recyclerview.widget.ItemTouchHelper.LEFT | androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                    @Override
                    public boolean onMove(@androidx.annotation.NonNull RecyclerView recyclerView,
                                          @androidx.annotation.NonNull RecyclerView.ViewHolder viewHolder,
                                          @androidx.annotation.NonNull RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public void onSwiped(@androidx.annotation.NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                        int pos = viewHolder.getAdapterPosition();
                        Chat chat = chatsAdapter.getChatAt(pos);
                        if (chat == null) {
                            chatsAdapter.notifyItemChanged(pos);
                            return;
                        }
                        if (isSecretMode) {
                            chat.setSecret(false);
                            encryptedDb.updateChatSecretStatus(chat.getId(), false);
                            loadArchivedChats();
                            com.google.android.material.snackbar.Snackbar.make(archivedRecyclerView, "Moved out of Secret Vault", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    .setAction("Undo", v -> {
                                        chat.setSecret(true);
                                        encryptedDb.updateChatSecretStatus(chat.getId(), true);
                                        loadArchivedChats();
                                    }).show();
                        } else {
                            chat.setArchived(false);
                            encryptedDb.updateChatArchiveStatus(chat.getId(), false);
                            loadArchivedChats();
                            com.google.android.material.snackbar.Snackbar.make(archivedRecyclerView, "Chat unarchived", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    .setAction("Undo", v -> {
                                        chat.setArchived(true);
                                        encryptedDb.updateChatArchiveStatus(chat.getId(), true);
                                        loadArchivedChats();
                                    }).show();
                        }
                    }
                };
        new androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(archivedRecyclerView);
    }

    private void showArchiveSettingsSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("📥 Archive Settings");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (12 * dp));
        root.addView(title);

        android.content.SharedPreferences prefs = getSharedPreferences("VeilPrefs", MODE_PRIVATE);
        boolean keepArchived = prefs.getBoolean("keep_chats_archived", true);

        MaterialButton btnToggleKeep = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnToggleKeep.setText(keepArchived ? "✅ Keep Chats Archived (Enabled)" : "⬜ Keep Chats Archived (Disabled)");
        btnToggleKeep.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (10 * dp);
        btnToggleKeep.setLayoutParams(lp1);
        btnToggleKeep.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("keep_chats_archived", true);
            prefs.edit().putBoolean("keep_chats_archived", next).apply();
            if (tvNoticeText != null) {
                tvNoticeText.setText(next
                        ? "Archived chats stay archived when new messages arrive. Swipe left/right to unarchive, or tap header for settings."
                        : "Archived chats will pop back to main inbox when new messages arrive. Tap header to change.");
            }
            sheet.dismiss();
            Toast.makeText(this, next ? "Chats will stay archived on new messages" : "Chats will unarchive on new messages", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnToggleKeep);

        MaterialButton btnUnarchiveAll = new MaterialButton(this);
        btnUnarchiveAll.setText("📤 Unarchive All Chats (" + archivedChats.size() + ")");
        btnUnarchiveAll.setCornerRadius((int) (12 * dp));
        btnUnarchiveAll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnUnarchiveAll.setOnClickListener(v -> {
            for (Chat c : new ArrayList<>(archivedChats)) {
                c.setArchived(false);
                encryptedDb.updateChatArchiveStatus(c.getId(), false);
            }
            loadArchivedChats();
            sheet.dismiss();
            Toast.makeText(this, "All chats unarchived", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnUnarchiveAll);

        sheet.setContentView(root);
        sheet.show();
    }

    private void promptSecretVaultUnlock() {
        com.m3.veil.manager.VeilFeatureManager fm = com.m3.veil.manager.VeilFeatureManager.getInstance(this);
        boolean hasPassword = fm.hasSecretVaultPassword() || fm.hasMasterPin();

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_pin_auth);
        sheet.setCancelable(false);

        TextView tvTitle = sheet.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = sheet.findViewById(R.id.tvPinDialogSubtitle);
        TextInputEditText etPin = sheet.findViewById(R.id.etPin);
        MaterialButton btnCancel = sheet.findViewById(R.id.btnCancelPin);
        MaterialButton btnConfirm = sheet.findViewById(R.id.btnConfirmPin);

        if (!hasPassword) {
            if (tvTitle != null) tvTitle.setText("🔒 Set Secret Vault Password");
            if (tvSubtitle != null) tvSubtitle.setText("Create a dedicated password (minimum 4 characters) to secure your Secret Vault:");
            if (btnConfirm != null) btnConfirm.setText("Set Password & Unlock");
        } else {
            if (tvTitle != null) tvTitle.setText("🔒 Unlock Secret Vault");
            if (tvSubtitle != null) tvSubtitle.setText("Enter your Secret Vault password to reveal hidden conversations:");
            if (btnConfirm != null) btnConfirm.setText("Unlock Vault");
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                sheet.dismiss();
                finish();
            });
        }

        if (btnConfirm != null && etPin != null) {
            btnConfirm.setOnClickListener(v -> {
                String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
                if (!hasPassword) {
                    if (entered.length() < 4) {
                        Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fm.setSecretVaultPassword(entered);
                    sheet.dismiss();
                    loadArchivedChats();
                    setupVaultHeaderActions();
                    Toast.makeText(this, "🔐 Secret Vault password configured & unlocked", Toast.LENGTH_SHORT).show();
                } else {
                    if (fm.verifySecretVaultPassword(entered)) {
                        sheet.dismiss();
                        loadArchivedChats();
                        setupVaultHeaderActions();
                        Toast.makeText(this, "🔓 Secret Vault unlocked", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Incorrect password. Access denied.", Toast.LENGTH_SHORT).show();
                        etPin.setText("");
                    }
                }
            });
        }

        sheet.show();
    }

    private void setupVaultHeaderActions() {
        if (!isSecretMode) return;
        View headerView = findViewById(R.id.tvTitle);
        if (headerView != null) {
            headerView.setOnClickListener(v -> showVaultManagementSheet());
        }
    }

    private void showVaultManagementSheet() {
        com.m3.veil.manager.VeilFeatureManager fm = com.m3.veil.manager.VeilFeatureManager.getInstance(this);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("🕵️ Secret Vault Management");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(title);

        MaterialButton btnLockNow = new MaterialButton(this);
        btnLockNow.setText("🔒 Lock Vault Now");
        btnLockNow.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (10 * dp);
        btnLockNow.setLayoutParams(lp1);
        btnLockNow.setOnClickListener(v -> {
            sheet.dismiss();
            finish();
            Toast.makeText(this, "🔒 Secret Vault locked", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnLockNow);

        MaterialButton btnChangePwd = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnChangePwd.setText("🔑 Change Vault Password");
        btnChangePwd.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.bottomMargin = (int) (10 * dp);
        btnChangePwd.setLayoutParams(lp2);
        btnChangePwd.setOnClickListener(v -> {
            sheet.dismiss();
            promptChangeVaultPassword();
        });
        root.addView(btnChangePwd);

        MaterialButton btnStealth = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        boolean stealth = fm.isStealthVaultEnabled();
        btnStealth.setText(stealth ? "👁️ Reveal Secret Vault Folder" : "👻 Hide Secret Vault Folder (Stealth)");
        btnStealth.setCornerRadius((int) (12 * dp));
        btnStealth.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnStealth.setOnClickListener(v -> {
            boolean next = !fm.isStealthVaultEnabled();
            fm.setStealthVaultEnabled(next);
            sheet.dismiss();
            Toast.makeText(this, next ? "👻 Secret Vault folder hidden from main chats" : "👁️ Secret Vault folder visible", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnStealth);

        sheet.setContentView(root);
        sheet.show();
    }

    private void promptChangeVaultPassword() {
        com.m3.veil.manager.VeilFeatureManager fm = com.m3.veil.manager.VeilFeatureManager.getInstance(this);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("🔑 Change Secret Vault Password");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (14 * dp));
        root.addView(title);

        EditText etCurrent = new EditText(this);
        etCurrent.setHint("Current Password");
        etCurrent.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etCurrent.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        etCurrent.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etCurrent.setBackgroundResource(R.drawable.bg_search_input);
        int pad = (int) (12 * dp);
        etCurrent.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (10 * dp);
        etCurrent.setLayoutParams(lp1);
        root.addView(etCurrent);

        EditText etNew = new EditText(this);
        etNew.setHint("New Password (min 4 characters)");
        etNew.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etNew.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        etNew.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etNew.setBackgroundResource(R.drawable.bg_search_input);
        etNew.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.bottomMargin = (int) (16 * dp);
        etNew.setLayoutParams(lp2);
        root.addView(etNew);

        MaterialButton btnConfirm = new MaterialButton(this);
        btnConfirm.setText("Update Password");
        btnConfirm.setCornerRadius((int) (12 * dp));
        btnConfirm.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnConfirm.setOnClickListener(v -> {
            String current = etCurrent.getText().toString().trim();
            String newPwd = etNew.getText().toString().trim();
            if (!fm.verifySecretVaultPassword(current)) {
                Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newPwd.length() < 4) {
                Toast.makeText(this, "New password must be at least 4 characters", Toast.LENGTH_SHORT).show();
                return;
            }
            fm.setSecretVaultPassword(newPwd);
            sheet.dismiss();
            Toast.makeText(this, "🔐 Secret Vault password changed successfully", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnConfirm);

        sheet.setContentView(root);
        sheet.show();
    }

    private void handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.archived_chats_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void loadArchivedChats() {
        List<Chat> chats = isSecretMode ? encryptedDb.getSecretChats() : encryptedDb.getArchivedChats();
        archivedChats.clear();
        if (chats != null) {
            archivedChats.addAll(chats);
        }
        chatsAdapter.updateChats(archivedChats);

        if (archivedChats.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            archivedRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            archivedRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onChatClick(Chat chat) {
        if (chat == null) return;
        if (chat.getChatLockPin() != null && !chat.getChatLockPin().isEmpty()) {
            promptChatLockPin(chat);
            return;
        }
        openChat(chat, false);
    }

    private void promptChatLockPin(Chat chat) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_pin_auth);
        sheet.setCancelable(false);

        TextView tvTitle = sheet.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = sheet.findViewById(R.id.tvPinDialogSubtitle);
        com.google.android.material.textfield.TextInputEditText etPin = sheet.findViewById(R.id.etPin);
        MaterialButton btnCancel = sheet.findViewById(R.id.btnCancelPin);
        MaterialButton btnUnlock = sheet.findViewById(R.id.btnConfirmPin);

        if (tvTitle != null) tvTitle.setText("🔒 Unlock " + (chat.getName() != null ? chat.getName() : "Chat"));
        if (tvSubtitle != null) tvSubtitle.setText("This conversation is encrypted & PIN protected");

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }

        if (btnUnlock != null && etPin != null) {
            btnUnlock.setOnClickListener(v -> {
                String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
                if (chat.getChatLockPin().equals(entered)) {
                    sheet.dismiss();
                    openChat(chat, true);
                } else {
                    Toast.makeText(this, "Incorrect Chat PIN", Toast.LENGTH_SHORT).show();
                    etPin.setText("");
                }
            });
        }
        sheet.show();
    }

    private void openChat(Chat chat, boolean isPinVerified) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chat.getId());
        intent.putExtra("recipientName", chat.getName());
        intent.putExtra("isPinVerified", isPinVerified);

        String recipientId = null;
        if (chat.getParticipants() != null) {
            for (String id : chat.getParticipants()) {
                if (id != null && !id.equals(currentUserId)) {
                    recipientId = id;
                    break;
                }
            }
        }
        if ((recipientId == null || recipientId.trim().isEmpty()) && chat.getId() != null && !chat.isGroup()) {
            recipientId = com.m3.veil.webrtc.WebRtcManager.extractPeerIdFromChatId(chat.getId(), currentUserId);
            if (recipientId == null || recipientId.trim().isEmpty()) {
                String[] parts = chat.getId().split("_");
                if (parts.length == 2) {
                    recipientId = parts[0].equals(currentUserId) ? parts[1] : parts[0];
                }
            }
        }
        if (recipientId != null && !recipientId.trim().isEmpty()) {
            intent.putExtra("recipientId", recipientId);
        }
        startActivity(intent);
    }

    @Override
    public void onChatLongClick(Chat chat) {
        if (chat == null || chat.getId() == null) return;
        if (chatsAdapter != null) {
            chatsAdapter.startSelection(chat);
        }
    }

    private void updateArchivedMultiSelectBar(int count) {
        boolean active = count > 0 && chatsAdapter != null && chatsAdapter.isSelectionMode();
        if (selectionBackCallback != null) {
            selectionBackCallback.setEnabled(active);
        }
        if (archivedMultiSelectBar != null) {
            archivedMultiSelectBar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (appBarLayout != null) {
            appBarLayout.setVisibility(active ? View.GONE : View.VISIBLE);
        }
        if (active) {
            if (tvArchivedSelectCount != null) {
                tvArchivedSelectCount.setText(count + " selected");
            }
            if (btnArchivedSelectAll != null) {
                int total = archivedChats != null ? archivedChats.size() : 0;
                btnArchivedSelectAll.setText((total > 0 && count >= total) ? "Clear" : "All");
            }
        }
    }

    private void performBatchMuteToggle() {
        List<Chat> selected = chatsAdapter.getSelectedChats();
        if (selected.isEmpty()) return;
        boolean anyUnmuted = false;
        for (Chat c : selected) {
            if (c != null && !c.isMuted()) {
                anyUnmuted = true;
                break;
            }
        }
        boolean targetMuted = anyUnmuted;
        for (Chat c : selected) {
            if (c != null && c.getId() != null) {
                c.setMuted(targetMuted);
                encryptedDb.updateChatMuteStatus(c.getId(), targetMuted);
            }
        }
        int count = selected.size();
        chatsAdapter.clearSelection();
        loadArchivedChats();
        Toast.makeText(this,
                targetMuted ? ("🔕 Muted " + count + (count == 1 ? " chat" : " chats"))
                        : ("🔔 Unmuted " + count + (count == 1 ? " chat" : " chats")),
                Toast.LENGTH_SHORT).show();
    }

    private void performBatchUnarchive() {
        List<Chat> selected = chatsAdapter.getSelectedChats();
        if (selected.isEmpty()) return;
        for (Chat c : selected) {
            if (c == null || c.getId() == null) continue;
            if (isSecretMode) {
                c.setSecret(false);
                encryptedDb.updateChatSecretStatus(c.getId(), false);
            } else {
                c.setArchived(false);
                c.setSecret(false);
                encryptedDb.saveOrUpdateChat(c);
                encryptedDb.updateChatArchiveStatus(c.getId(), false);
            }
        }
        int count = selected.size();
        chatsAdapter.clearSelection();
        loadArchivedChats();
        Toast.makeText(this,
                isSecretMode ? ("Moved " + count + (count == 1 ? " chat" : " chats") + " back to main inbox")
                        : ("📤 Unarchived " + count + (count == 1 ? " chat" : " chats")),
                Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteSelectedArchivedChats(List<Chat> selectedChats) {
        if (selectedChats == null || selectedChats.isEmpty()) return;
        final List<Chat> targets = new ArrayList<>(selectedChats);
        BottomSheetDialog confirmSheet = new BottomSheetDialog(this);
        confirmSheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvConfirmTitle = confirmSheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = confirmSheet.findViewById(R.id.tvDestructiveMessage);
        MaterialButton btnConfirm = confirmSheet.findViewById(R.id.btnDestructiveConfirm);
        MaterialButton btnCancel = confirmSheet.findViewById(R.id.btnDestructiveCancel);

        int count = targets.size();
        if (tvConfirmTitle != null) {
            tvConfirmTitle.setText(count == 1 ? "Delete Chat?" : ("Delete " + count + " Chats?"));
        }
        if (tvMsg != null) {
            tvMsg.setText(count == 1
                    ? "Are you sure you want to permanently delete this conversation? All messages and attachments will be deleted."
                    : ("Are you sure you want to permanently delete these " + count + " conversations? All messages and attachments will be deleted."));
        }
        if (btnConfirm != null) {
            btnConfirm.setText(count == 1 ? "Delete Conversation" : ("Delete " + count + " Chats"));
            btnConfirm.setOnClickListener(cv -> {
                confirmSheet.dismiss();
                chatsAdapter.clearSelection();
                String activePersonaId = com.m3.veil.manager.PersonaManager.getInstance(this).getActivePersonaId();
                for (Chat c : targets) {
                    if (c == null || c.getId() == null) continue;
                    String targetPeerId = null;
                    if (!c.isGroup() && c.getParticipants() != null) {
                        for (String p : c.getParticipants()) {
                            if (p != null && !p.equals(currentUserId)) {
                                targetPeerId = p;
                                break;
                            }
                        }
                    }
                    com.m3.veil.manager.VeilMessagesController.getInstance(this)
                            .deleteConversation(c.getId(), targetPeerId, true);
                    encryptedDb.deleteConversationCascade(c.getId(), targetPeerId, activePersonaId);
                }
                loadArchivedChats();
                Toast.makeText(this,
                        count == 1 ? "Chat deleted from vault" : ("Deleted " + count + " chats from vault"),
                        Toast.LENGTH_SHORT).show();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(cv -> confirmSheet.dismiss());
        }
        confirmSheet.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        encryptedDb.removeListener(dbListener);
    }
}
