package com.m3.veil.calling.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.m3.veil.R;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.utils.VeilAvatarLoader;

import java.io.File;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Professional minimal Material 3 call confirmation dialog.
 * Prevents accidental calls while providing a smooth, modern UI to verify
 * or switch between Voice Call and Video Call before placing the WebRTC session.
 */
public final class VeilCallConfirmationDialog {

    public interface OnCallConfirmedListener {
        void onCallConfirmed(String callType);
    }

    private VeilCallConfirmationDialog() {}

    public static void show(
            @NonNull Context context,
            @Nullable String contactId,
            @Nullable String contactName,
            @Nullable String avatarSource,
            @NonNull String initialCallType,
            boolean isGroup,
            @NonNull OnCallConfirmedListener listener) {

        if (context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed())) {
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.ModernBottomSheetDialogTheme);
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_call_confirmation_sheet, null);
        dialog.setContentView(root);

        CircleImageView ivAvatar = root.findViewById(R.id.ivCallConfirmAvatar);
        ImageView ivBadge = root.findViewById(R.id.ivCallConfirmBadge);
        TextView tvName = root.findViewById(R.id.tvCallConfirmName);
        TextView tvHint = root.findViewById(R.id.tvCallConfirmHint);

        LinearLayout btnSegmentAudio = root.findViewById(R.id.btnSegmentAudio);
        ImageView ivSegmentAudio = root.findViewById(R.id.ivSegmentAudio);
        TextView tvSegmentAudio = root.findViewById(R.id.tvSegmentAudio);

        LinearLayout btnSegmentVideo = root.findViewById(R.id.btnSegmentVideo);
        ImageView ivSegmentVideo = root.findViewById(R.id.ivSegmentVideo);
        TextView tvSegmentVideo = root.findViewById(R.id.tvSegmentVideo);

        TextView btnCancel = root.findViewById(R.id.btnCallConfirmCancel);
        LinearLayout btnStart = root.findViewById(R.id.btnCallConfirmStart);
        ImageView ivActionIcon = root.findViewById(R.id.ivCallConfirmActionIcon);
        TextView tvActionText = root.findViewById(R.id.tvCallConfirmActionText);

        // Display Name
        String displayName = (contactName != null && !contactName.trim().isEmpty())
                ? contactName.trim()
                : (isGroup ? "Group Call" : "Veil Contact");
        if (tvName != null) {
            tvName.setText(displayName);
        }

        // Avatar
        String resolvedAvatar = avatarSource;
        if ((resolvedAvatar == null || resolvedAvatar.isEmpty()) && contactId != null && !contactId.isEmpty()) {
            try {
                File webpFile = SovereignIdentityAndDirectoryEngine.getInstance(context).getAvatarWebpFile(contactId);
                if (webpFile != null && webpFile.exists() && webpFile.length() > 0) {
                    resolvedAvatar = webpFile.getAbsolutePath();
                }
            } catch (Throwable ignored) {}
        }
        if (ivAvatar != null) {
            VeilAvatarLoader.loadAvatar(ivAvatar, resolvedAvatar, displayName, isGroup);
        }

        // Hint Text
        if (tvHint != null) {
            if (isGroup) {
                tvHint.setText("End-to-end encrypted group call \u2022 Decentralized WebRTC mesh");
            } else {
                tvHint.setText("Direct peer-to-peer WebRTC connection with " + displayName);
            }
        }

        // State holder
        final boolean[] isVideo = new boolean[]{"video".equalsIgnoreCase(initialCallType)};

        Runnable updateSelectionUi = () -> {
            boolean video = isVideo[0];
            int activeColor = ContextCompat.getColor(context, R.color.colorPrimary);
            int onPrimaryColor = ContextCompat.getColor(context, R.color.colorOnPrimary);
            int inactiveColor = ContextCompat.getColor(context, R.color.textSecondary);

            if (video) {
                if (btnSegmentVideo != null) btnSegmentVideo.setBackgroundResource(R.drawable.bg_call_confirm_segment_selected);
                if (ivSegmentVideo != null) ivSegmentVideo.setColorFilter(onPrimaryColor);
                if (tvSegmentVideo != null) tvSegmentVideo.setTextColor(onPrimaryColor);

                if (btnSegmentAudio != null) btnSegmentAudio.setBackgroundColor(Color.TRANSPARENT);
                if (ivSegmentAudio != null) ivSegmentAudio.setColorFilter(inactiveColor);
                if (tvSegmentAudio != null) tvSegmentAudio.setTextColor(inactiveColor);

                if (ivBadge != null) {
                    ivBadge.setImageResource(R.drawable.ic_video);
                    ivBadge.setColorFilter(activeColor);
                }
                if (ivActionIcon != null) {
                    ivActionIcon.setImageResource(R.drawable.ic_video);
                    ivActionIcon.setColorFilter(onPrimaryColor);
                }
                if (tvActionText != null) {
                    tvActionText.setText(isGroup ? "Start Group Video" : "Start Video Call");
                }
            } else {
                if (btnSegmentAudio != null) btnSegmentAudio.setBackgroundResource(R.drawable.bg_call_confirm_segment_selected);
                if (ivSegmentAudio != null) ivSegmentAudio.setColorFilter(onPrimaryColor);
                if (tvSegmentAudio != null) tvSegmentAudio.setTextColor(onPrimaryColor);

                if (btnSegmentVideo != null) btnSegmentVideo.setBackgroundColor(Color.TRANSPARENT);
                if (ivSegmentVideo != null) ivSegmentVideo.setColorFilter(inactiveColor);
                if (tvSegmentVideo != null) tvSegmentVideo.setTextColor(inactiveColor);

                if (ivBadge != null) {
                    ivBadge.setImageResource(R.drawable.ic_call);
                    ivBadge.setColorFilter(activeColor);
                }
                if (ivActionIcon != null) {
                    ivActionIcon.setImageResource(R.drawable.ic_call);
                    ivActionIcon.setColorFilter(onPrimaryColor);
                }
                if (tvActionText != null) {
                    tvActionText.setText(isGroup ? "Start Group Call" : "Start Voice Call");
                }
            }
        };

        // Initialize UI selection
        updateSelectionUi.run();

        // Attach Spring Press Animations
        if (btnSegmentAudio != null) {
            UiAnimator.attachSpringPress(btnSegmentAudio);
            btnSegmentAudio.setOnClickListener(v -> {
                if (isVideo[0]) {
                    isVideo[0] = false;
                    updateSelectionUi.run();
                }
            });
        }

        if (btnSegmentVideo != null) {
            UiAnimator.attachSpringPress(btnSegmentVideo);
            btnSegmentVideo.setOnClickListener(v -> {
                if (!isVideo[0]) {
                    isVideo[0] = true;
                    updateSelectionUi.run();
                }
            });
        }

        if (btnCancel != null) {
            UiAnimator.attachSpringPress(btnCancel);
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnStart != null) {
            UiAnimator.attachSpringPress(btnStart);
            btnStart.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onCallConfirmed(isVideo[0] ? "video" : "audio");
            });
        }

        dialog.show();
    }
}
