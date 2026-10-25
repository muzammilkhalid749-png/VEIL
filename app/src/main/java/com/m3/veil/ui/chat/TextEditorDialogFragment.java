package com.m3.veil.ui.chat;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.m3.veil.R;

public class TextEditorDialogFragment extends DialogFragment {

    public interface TextEditorListener {
        void onDone(String inputText, int colorCode);
    }

    private EditText addTextEditText;
    private ColorPickerView colorPickerView;
    private int mColorCode = Color.WHITE;
    private TextEditorListener mTextEditorListener;
    private String initialText = "";
    private int initialColor = Color.WHITE;

    public void setOnTextEditorListener(TextEditorListener textEditorListener) {
        mTextEditorListener = textEditorListener;
    }

    public static TextEditorDialogFragment show(@NonNull androidx.appcompat.app.AppCompatActivity appCompatActivity,
                                                @NonNull String inputText,
                                                int colorCode) {
        Bundle args = new Bundle();
        args.putString("extra_input_text", inputText);
        args.putInt("extra_color_code", colorCode);
        TextEditorDialogFragment fragment = new TextEditorDialogFragment();
        fragment.setArguments(args);
        fragment.show(appCompatActivity.getSupportFragmentManager(), "TextEditorDialogFragment");
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (getArguments() != null) {
            initialText = getArguments().getString("extra_input_text");
            initialColor = getArguments().getInt("extra_color_code");
            mColorCode = initialColor;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_text_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        addTextEditText = view.findViewById(R.id.addTextEditText);
        colorPickerView = view.findViewById(R.id.colorPickerView);
        TextView btnDone = view.findViewById(R.id.btnDone);

        addTextEditText.setText(initialText);
        addTextEditText.setTextColor(initialColor);

        // Move cursor to end
        if (!TextUtils.isEmpty(initialText)) {
            addTextEditText.setSelection(initialText.length());
        }

        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        addTextEditText.requestFocus();

        colorPickerView.setOnColorChangeListener(color -> {
            mColorCode = color;
            addTextEditText.setTextColor(color);
        });

        btnDone.setOnClickListener(v -> {
            InputMethodManager inputMethodManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            
            dismiss();
            
            String inputText = addTextEditText.getText().toString();
            if (!TextUtils.isEmpty(inputText) && mTextEditorListener != null) {
                mTextEditorListener.onDone(inputText, mColorCode);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        }
    }
}
