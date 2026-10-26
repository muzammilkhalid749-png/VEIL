package com.m3.veil.ui.main.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Persona;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.utils.VeilFirestoreUtils;

import java.util.Locale;

public class WalletBottomSheet extends BottomSheetDialogFragment {

    private Button tabSend, tabReceive, btnConfirmSend, btnCopyAddress, btnClaimDailyBonus;
    private LinearLayout sendContainer, receiveContainer;
    private EditText walletRecipientInput, walletAmountInput;
    private TextView walletAddressText, walletBalanceText, walletSubtitleText;

    private VeilFeatureManager featureManager;
    private FirebaseFirestore db;
    private String currentUserId;
    private String currentUsername;

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_wallet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        featureManager = VeilFeatureManager.getInstance(requireContext());
        db = FirebaseFirestore.getInstance();

        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        Persona activePersona = featureManager.getActivePersona();
        currentUsername = (activePersona != null && activePersona.getDisplayName() != null)
                ? activePersona.getDisplayName().toLowerCase().replace(" ", "_")
                : "anonymous";

        tabSend = view.findViewById(R.id.tabSend);
        tabReceive = view.findViewById(R.id.tabReceive);
        btnConfirmSend = view.findViewById(R.id.btnConfirmSend);
        btnCopyAddress = view.findViewById(R.id.btnCopyAddress);
        btnClaimDailyBonus = view.findViewById(R.id.btnClaimDailyBonus);

        sendContainer = view.findViewById(R.id.sendContainer);
        receiveContainer = view.findViewById(R.id.receiveContainer);

        walletRecipientInput = view.findViewById(R.id.walletRecipientInput);
        walletAmountInput = view.findViewById(R.id.walletAmountInput);
        walletAddressText = view.findViewById(R.id.walletAddressText);
        walletBalanceText = view.findViewById(R.id.walletBalanceText);
        walletSubtitleText = view.findViewById(R.id.walletSubtitleText);

        updateBalanceDisplay();

        walletAddressText.setText("@" + currentUsername + "\nUID: " + currentUserId);

        UiAnimator.attachSpringPress(tabSend);
        UiAnimator.attachSpringPress(tabReceive);
        UiAnimator.attachSpringPress(btnConfirmSend);
        UiAnimator.attachSpringPress(btnCopyAddress);
        if (btnClaimDailyBonus != null) {
            UiAnimator.attachSpringPress(btnClaimDailyBonus);
            btnClaimDailyBonus.setOnClickListener(v -> claimStreakReward());
        }

        tabSend.setOnClickListener(v -> showSendTab());
        tabReceive.setOnClickListener(v -> showReceiveTab());

        btnConfirmSend.setOnClickListener(v -> handleSend());
        btnCopyAddress.setOnClickListener(v -> copyAddress());

        // Sync coin balance with remote Firestore user profile
        if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(getContext())) {
            db.collection("users").document(currentUserId).get().addOnSuccessListener(doc -> {
                if (getContext() == null || !isAdded()) return;
                if (doc.exists() && doc.contains("veilCoins")) {
                    long remoteCoins = VeilFirestoreUtils.getSafeLong(doc, "veilCoins", 0);
                    if (remoteCoins > featureManager.getVeilCoins()) {
                        int diff = (int) (remoteCoins - featureManager.getVeilCoins());
                        featureManager.addCoins(diff);
                        updateBalanceDisplay();
                    }
                }
            });
        }
    }

    private void updateBalanceDisplay() {
        int coins = featureManager.getVeilCoins();
        walletBalanceText.setText(coins + " VC");
        if (walletSubtitleText != null) {
            walletSubtitleText.setText(String.format(Locale.US,
                    "LVL %d • %d XP • ⭐ %d Karma • 🔥 %d-Day Streak",
                    featureManager.getLevel(),
                    featureManager.getXp(),
                    featureManager.getKarma(),
                    featureManager.getDailyStreak()));
        }
    }

    private void showSendTab() {
        sendContainer.setVisibility(View.VISIBLE);
        receiveContainer.setVisibility(View.GONE);
        tabSend.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorPrimary));
        tabSend.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        tabReceive.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorSurfaceVariant));
        tabReceive.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
    }

    private void showReceiveTab() {
        sendContainer.setVisibility(View.GONE);
        receiveContainer.setVisibility(View.VISIBLE);
        tabReceive.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorPrimary));
        tabReceive.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        tabSend.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorSurfaceVariant));
        tabSend.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
    }

    private void claimStreakReward() {
        featureManager.updateDailyStreak();
        featureManager.addCoins(25);
        featureManager.addXp(15);
        featureManager.addKarma(1);

        if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(getContext())) {
            db.collection("users").document(currentUserId)
                    .update("veilCoins", FieldValue.increment(25));
        }

        updateBalanceDisplay();
        Toast.makeText(requireContext(), "🎉 Claimed Daily Reward! +25 VeilCoins & +15 XP", Toast.LENGTH_SHORT).show();
    }

    private void handleSend() {
        String recipient = walletRecipientInput.getText().toString().trim();
        String amountStr = walletAmountInput.getText().toString().trim();

        if (TextUtils.isEmpty(recipient)) {
            Toast.makeText(requireContext(), "Enter recipient @username", Toast.LENGTH_SHORT).show();
            return;
        }

        if (recipient.startsWith("@")) {
            recipient = recipient.substring(1);
        }

        if (TextUtils.isEmpty(amountStr)) {
            Toast.makeText(requireContext(), "Enter amount of VeilCoins", Toast.LENGTH_SHORT).show();
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                Toast.makeText(requireContext(), "Amount must be at least 1 VC", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
            return;
        }

        if (amount > featureManager.getVeilCoins()) {
            Toast.makeText(requireContext(), "Insufficient VeilCoins balance (You have " + featureManager.getVeilCoins() + " VC)", Toast.LENGTH_SHORT).show();
            return;
        }

        btnConfirmSend.setEnabled(false);
        final String targetUsername = recipient;
        final int transferAmount = amount;

        // Query Firestore for recipient user by username
        db.collection("users")
                .whereEqualTo("username", targetUsername.toLowerCase())
                .limit(1)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (getContext() == null || !isAdded()) return;
                    btnConfirmSend.setEnabled(true);

                    if (queryDocumentSnapshots.isEmpty()) {
                        Toast.makeText(requireContext(), "User @" + targetUsername + " not found on VEIL network", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    DocumentSnapshot targetDoc = queryDocumentSnapshots.getDocuments().get(0);
                    String targetUid = targetDoc.getId();

                    if (targetUid.equals(currentUserId)) {
                        Toast.makeText(requireContext(), "Cannot transfer VeilCoins to yourself", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Deduct from sender
                    featureManager.spendCoins(transferAmount);
                    featureManager.addKarma(1);
                    featureManager.addXp(10);
                    updateBalanceDisplay();

                    // Atomically update sender and recipient balances in Firestore
                    if (com.m3.veil.manager.AuthManager.getInstance().isLoggedIn(getContext())) {
                        db.collection("users").document(currentUserId)
                                .update("veilCoins", FieldValue.increment(-transferAmount));
                    }
                    db.collection("users").document(targetUid)
                            .update("veilCoins", FieldValue.increment(transferAmount));

                    walletRecipientInput.setText("");
                    walletAmountInput.setText("");
                    Toast.makeText(requireContext(), "🪙 Sent " + transferAmount + " VeilCoins anonymously to @" + targetUsername + "!", Toast.LENGTH_LONG).show();
                })
                .addOnFailureListener(e -> {
                    if (getContext() == null || !isAdded()) return;
                    btnConfirmSend.setEnabled(true);
                    Toast.makeText(requireContext(), "Transfer failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void copyAddress() {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Veil User Handle", "@" + currentUsername);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "Copied @" + currentUsername + " to clipboard", Toast.LENGTH_SHORT).show();
        }
    }
}
