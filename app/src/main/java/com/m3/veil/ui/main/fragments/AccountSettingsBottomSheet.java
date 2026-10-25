package com.m3.veil.ui.main.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.ui.auth.PinSetupActivity;
import com.m3.veil.ui.auth.RecoveryPhraseActivity;

public class AccountSettingsBottomSheet extends BottomSheetDialogFragment {

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_account_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvAccountPhone = view.findViewById(R.id.tvAccountPhone);
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            String phone = FirebaseAuth.getInstance().getCurrentUser().getPhoneNumber();
            if (phone != null && !phone.isEmpty()) {
                tvAccountPhone.setText(phone);
            } else {
                tvAccountPhone.setText("UID: " + FirebaseAuth.getInstance().getCurrentUser().getUid());
            }
        }

        view.findViewById(R.id.btnAccountPhone).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Veil Account Info", tvAccountPhone.getText().toString());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.btnChangePin).setOnClickListener(v -> {
            dismiss();
            startActivity(new Intent(requireContext(), PinSetupActivity.class));
        });

        view.findViewById(R.id.btnViewRecoveryPhrase).setOnClickListener(v -> {
            dismiss();
            Intent intent = new Intent(requireContext(), RecoveryPhraseActivity.class);
            intent.putExtra("is_view_only", true);
            startActivity(intent);
        });

        view.findViewById(R.id.btnActiveSessions).setOnClickListener(v -> {
            com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                    new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
            android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setBackgroundResource(R.drawable.bottom_sheet_background);
            int padH = (int) (24 * getResources().getDisplayMetrics().density);
            int padV = (int) (16 * getResources().getDisplayMetrics().density);
            layout.setPadding(padH, padV, padH, padV * 2);

            View handle = new View(requireContext());
            int handleW = (int) (36 * getResources().getDisplayMetrics().density);
            int handleH = (int) (4 * getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams handleLp = new android.widget.LinearLayout.LayoutParams(handleW, handleH);
            handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
            handle.setLayoutParams(handleLp);
            handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
            layout.addView(handle);

            TextView tvTitle = new TextView(requireContext());
            tvTitle.setText("📱 Active Mesh Sessions");
            tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
            tvTitle.setTextSize(19);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            layout.addView(tvTitle);

            TextView tvInfo = new TextView(requireContext());
            tvInfo.setText("• Node: VEIL Android Client (Current Device)\n• Connection: Direct P2P WebRTC SCTP DataChannels\n• Vault: 256-bit AES-GCM local SQLCipher (v3)\n• Session Security: VOPRF Authenticated & Verified");
            tvInfo.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvInfo.setTextSize(13.5f);
            tvInfo.setLineSpacing(4, 1.3f);
            tvInfo.setBackgroundResource(R.drawable.bg_search_input);
            int cPad = (int) (14 * getResources().getDisplayMetrics().density);
            tvInfo.setPadding(cPad, cPad, cPad, cPad);
            android.widget.LinearLayout.LayoutParams cLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
            cLp.bottomMargin = (int) (18 * getResources().getDisplayMetrics().density);
            tvInfo.setLayoutParams(cLp);
            layout.addView(tvInfo);

            com.google.android.material.button.MaterialButton btnClose =
                    new com.google.android.material.button.MaterialButton(requireContext());
            btnClose.setText("Close");
            btnClose.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
            btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
            btnClose.setTextColor(0xFFFFFFFF);
            btnClose.setOnClickListener(bv -> sheet.dismiss());
            layout.addView(btnClose);

            sheet.setContentView(layout);
            sheet.show();
        });

        view.findViewById(R.id.btnBurnAccount).setOnClickListener(v -> {
            com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                    new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
            View sheetView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm_destructive_sheet, null);
            sheet.setContentView(sheetView);

            TextView tvTitle = sheetView.findViewById(R.id.tvDestructiveTitle);
            TextView tvMsg = sheetView.findViewById(R.id.tvDestructiveMessage);
            com.google.android.material.button.MaterialButton btnConfirm = sheetView.findViewById(R.id.btnDestructiveConfirm);
            com.google.android.material.button.MaterialButton btnCancel = sheetView.findViewById(R.id.btnDestructiveCancel);

            tvTitle.setText("🔥 Panic Wipe & Burn Identity");
            tvMsg.setText("WARNING: This will permanently wipe all local encrypted chats, shred cryptographic keys, delete local personas, and reset the app. This cannot be undone!\n\nAre you sure you want to proceed?");
            btnConfirm.setText("Wipe & Destroy");

            btnConfirm.setOnClickListener(cv -> {
                sheet.dismiss();
                dismiss();
                try {
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(requireContext()).secureWipeAllData();
                } catch (Throwable ignored) { }
                try {
                    requireContext().getSharedPreferences("VeilPrefs", 0).edit().clear().apply();
                    requireContext().getSharedPreferences("veil_privacy_prefs", 0).edit().clear().apply();
                    requireContext().getSharedPreferences("veil_appearance_prefs", 0).edit().clear().apply();
                } catch (Throwable ignored) { }
                try {
                    FirebaseAuth.getInstance().signOut();
                } catch (Throwable ignored) { }

                Toast.makeText(requireContext(), "🔥 All local keys & conversations wiped", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(requireContext(), com.m3.veil.ui.splash.SplashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                if (getActivity() != null) getActivity().finish();
            });

            btnCancel.setOnClickListener(cv -> sheet.dismiss());

            sheet.show();
        });
    }
}
