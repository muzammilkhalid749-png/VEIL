package com.m3.veil.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.utils.UiAnimator;

public class AttachmentBottomSheet extends BottomSheetDialogFragment {

    public interface AttachmentSelectionListener {
        void onSelectImage();
        void onSelectVideo();
        void onSelectDocument();
        void onSelectLocation();
        void onSelectContact();
        void onSelectCamera();
        default void onSelectPoll() {}
        default void onSelectDoodle() {}
        default void onSelectBlink() {}
    }

    private AttachmentSelectionListener listener;

    public void setAttachmentSelectionListener(AttachmentSelectionListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_attachment_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View btnImage = view.findViewById(R.id.btnAttachImage);
        View btnVideo = view.findViewById(R.id.btnAttachVideo);
        View btnDocument = view.findViewById(R.id.btnAttachDocument);
        View btnLocation = view.findViewById(R.id.btnAttachLocation);
        View btnContact = view.findViewById(R.id.btnAttachContact);
        View btnCamera = view.findViewById(R.id.btnAttachCamera);
        View btnPoll = view.findViewById(R.id.btnAttachPoll);
        View btnDoodle = view.findViewById(R.id.btnAttachDoodle);
        View btnBlink = view.findViewById(R.id.btnAttachBlink);

        UiAnimator.attachSpringPress(btnImage);
        UiAnimator.attachSpringPress(btnVideo);
        UiAnimator.attachSpringPress(btnDocument);
        UiAnimator.attachSpringPress(btnLocation);
        UiAnimator.attachSpringPress(btnContact);
        if (btnCamera != null) UiAnimator.attachSpringPress(btnCamera);
        if (btnPoll != null) UiAnimator.attachSpringPress(btnPoll);
        if (btnDoodle != null) UiAnimator.attachSpringPress(btnDoodle);
        if (btnBlink != null) UiAnimator.attachSpringPress(btnBlink);

        btnImage.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectImage();
        });

        btnVideo.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectVideo();
        });

        btnDocument.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectDocument();
        });

        btnLocation.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectLocation();
        });

        btnContact.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectContact();
        });

        btnCamera.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onSelectCamera();
        });

        if (btnPoll != null) {
            btnPoll.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onSelectPoll();
            });
        }

        if (btnDoodle != null) {
            btnDoodle.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onSelectDoodle();
            });
        }

        if (btnBlink != null) {
            btnBlink.setOnClickListener(v -> {
                dismiss();
                if (listener != null) listener.onSelectBlink();
            });
        }
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }
}
