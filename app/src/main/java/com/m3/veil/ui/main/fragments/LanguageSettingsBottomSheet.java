package com.m3.veil.ui.main.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;

public class LanguageSettingsBottomSheet extends BottomSheetDialogFragment {

    public interface OnLanguageSelectedListener {
        void onLanguageSelected(String langCode, String langName);
    }

    private RadioGroup rgLanguages;
    private RadioButton rbLangEn, rbLangEs, rbLangFr, rbLangDe, rbLangAr, rbLangUr;
    private SharedPreferences prefs;
    private OnLanguageSelectedListener listener;

    public void setOnLanguageSelectedListener(OnLanguageSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_language_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences("veil_language_prefs", Context.MODE_PRIVATE);

        rgLanguages = view.findViewById(R.id.rgLanguages);
        rbLangEn = view.findViewById(R.id.rbLangEn);
        rbLangEs = view.findViewById(R.id.rbLangEs);
        rbLangFr = view.findViewById(R.id.rbLangFr);
        rbLangDe = view.findViewById(R.id.rbLangDe);
        rbLangAr = view.findViewById(R.id.rbLangAr);
        rbLangUr = view.findViewById(R.id.rbLangUr);

        String savedLang = prefs.getString("selected_language", "en");
        if ("es".equals(savedLang)) rbLangEs.setChecked(true);
        else if ("fr".equals(savedLang)) rbLangFr.setChecked(true);
        else if ("de".equals(savedLang)) rbLangDe.setChecked(true);
        else if ("ar".equals(savedLang)) rbLangAr.setChecked(true);
        else if ("ur".equals(savedLang)) rbLangUr.setChecked(true);
        else rbLangEn.setChecked(true);

        rgLanguages.setOnCheckedChangeListener((group, checkedId) -> {
            String code = "en";
            String name = "English";

            if (checkedId == R.id.rbLangEs) { code = "es"; name = "Español"; }
            else if (checkedId == R.id.rbLangFr) { code = "fr"; name = "Français"; }
            else if (checkedId == R.id.rbLangDe) { code = "de"; name = "Deutsch"; }
            else if (checkedId == R.id.rbLangAr) { code = "ar"; name = "العربية"; }
            else if (checkedId == R.id.rbLangUr) { code = "ur"; name = "اردو"; }

            prefs.edit().putString("selected_language", code).putString("selected_language_name", name).apply();
            try {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(code));
            } catch (Exception ignored) {}
            Toast.makeText(requireContext(), "Language set to " + name, Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onLanguageSelected(code, name);
            dismiss();
        });
    }
}
