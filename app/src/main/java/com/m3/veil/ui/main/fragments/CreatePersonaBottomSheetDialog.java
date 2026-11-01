package com.m3.veil.ui.main.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.m3.veil.R;
import com.m3.veil.manager.PersonaManager;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Persona;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;

import java.util.Locale;

/**
 * Minimal & Modern Material 3 Persona Creation BottomSheet.
 * Includes live debounced username verification (local SQLite + directory),
 * signature accent color picker, mood badges, and instant avatar monogram preview.
 */
public class CreatePersonaBottomSheetDialog extends BottomSheetDialogFragment {

    public interface OnPersonaCreatedListener {
        void onPersonaCreated(Persona persona);
    }

    private static final String ARG_IS_TEMPORARY = "arg_is_temporary";
    private static final long DEBOUNCE_DELAY_MS = 350L;

    private boolean isTemporary = false;
    private OnPersonaCreatedListener listener;

    private TextView tvPreviewMonogram;
    private View vPreviewAvatarBg;
    private TextView tvPreviewDisplayName;
    private TextView tvPreviewUsername;
    private TextView tvPreviewMoodBadge;
    private EditText etDisplayName;
    private EditText etUsername;
    private EditText etBio;
    private ProgressBar pbChecking;
    private TextView tvUsernameStatus;
    private MaterialButton btnSubmit;
    private LinearLayout llMoodContainer;
    private LinearLayout llColorContainer;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable checkUsernameRunnable;

    private String selectedMood = "🎭 Anonymous";
    private String selectedColorHex = "#4F46E5";
    private String verifiedUsername = null;
    private boolean isUsernameManuallyEdited = false;

    private static final String[] MOOD_OPTIONS = {
            "🎭 Anonymous", "🌙 Incognito", "⚡ Active", "🛡️ Private", "🪐 Explorer", "🔥 Hyped"
    };

    private static final String[] COLOR_PALETTE = {
            "#4F46E5", "#10B981", "#06B6D4", "#8B5CF6", "#F59E0B", "#EC4899"
    };

    public static void show(@NonNull FragmentManager fm, @Nullable OnPersonaCreatedListener listener) {
        show(fm, false, listener);
    }

    public static void show(@NonNull FragmentManager fm, boolean isTemporary, @Nullable OnPersonaCreatedListener listener) {
        CreatePersonaBottomSheetDialog sheet = new CreatePersonaBottomSheetDialog();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_TEMPORARY, isTemporary);
        sheet.setArguments(args);
        sheet.setOnPersonaCreatedListener(listener);
        sheet.show(fm, "CreatePersonaBottomSheetDialog");
    }

    public void setOnPersonaCreatedListener(OnPersonaCreatedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ModernBottomSheetDialogTheme);
        if (getArguments() != null) {
            isTemporary = getArguments().getBoolean(ARG_IS_TEMPORARY, false);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_create_persona_sheet, container, false);
        initViews(view);
        setupMoodChips();
        setupColorDots();
        setupListeners();
        updatePreview();
        return view;
    }

    private void initViews(View view) {
        TextView tvTitle = view.findViewById(R.id.tvCreatePersonaTitle);
        if (tvTitle != null && isTemporary) {
            tvTitle.setText("New 24h Temporary Persona");
        }

        tvPreviewMonogram = view.findViewById(R.id.tvPreviewMonogram);
        vPreviewAvatarBg = view.findViewById(R.id.vPreviewAvatarBg);
        tvPreviewDisplayName = view.findViewById(R.id.tvPreviewDisplayName);
        tvPreviewUsername = view.findViewById(R.id.tvPreviewUsername);
        tvPreviewMoodBadge = view.findViewById(R.id.tvPreviewMoodBadge);
        etDisplayName = view.findViewById(R.id.etPersonaDisplayName);
        etUsername = view.findViewById(R.id.etPersonaUsername);
        etBio = view.findViewById(R.id.etPersonaBio);
        pbChecking = view.findViewById(R.id.pbCheckingUsername);
        tvUsernameStatus = view.findViewById(R.id.tvUsernameStatus);
        btnSubmit = view.findViewById(R.id.btnSubmitCreatePersona);
        llMoodContainer = view.findViewById(R.id.llMoodChipsContainer);
        llColorContainer = view.findViewById(R.id.llColorDotsContainer);
    }

    private void setupMoodChips() {
        if (llMoodContainer == null || getContext() == null) return;
        llMoodContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        for (String mood : MOOD_OPTIONS) {
            TextView chip = new TextView(getContext());
            chip.setText(mood);
            chip.setTextSize(12f);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * dp));
            chip.setLayoutParams(lp);

            boolean isSelected = mood.equals(selectedMood);
            applyMoodChipStyle(chip, isSelected);

            chip.setOnClickListener(v -> {
                selectedMood = mood;
                setupMoodChips();
                updatePreview();
            });
            llMoodContainer.addView(chip);
        }
    }

    private void applyMoodChipStyle(TextView chip, boolean isSelected) {
        if (getContext() == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(getResources().getDisplayMetrics().density * 10f);
        if (isSelected) {
            bg.setColor(ContextCompat.getColor(requireContext(), R.color.colorSecondaryContainer));
            bg.setStroke((int) (1.5f * getResources().getDisplayMetrics().density),
                    ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        } else {
            bg.setColor(ContextCompat.getColor(requireContext(), R.color.colorSurfaceContainerLow));
            bg.setStroke((int) (1.0f * getResources().getDisplayMetrics().density),
                    ContextCompat.getColor(requireContext(), R.color.colorOutline));
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        }
        chip.setBackground(bg);
    }

    private void setupColorDots() {
        if (llColorContainer == null || getContext() == null) return;
        llColorContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        for (String colorHex : COLOR_PALETTE) {
            View dot = new View(getContext());
            int size = (int) (32 * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd((int) (12 * dp));
            dot.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor(colorHex));
            boolean isSelected = colorHex.equalsIgnoreCase(selectedColorHex);
            if (isSelected) {
                bg.setStroke((int) (3 * dp), ContextCompat.getColor(requireContext(), R.color.textPrimary));
            } else {
                bg.setStroke((int) (1 * dp), ContextCompat.getColor(requireContext(), R.color.colorOutline));
            }
            dot.setBackground(bg);

            dot.setOnClickListener(v -> {
                selectedColorHex = colorHex;
                setupColorDots();
                updatePreview();
            });
            llColorContainer.addView(dot);
        }
    }

    private void setupListeners() {
        etDisplayName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String input = s != null ? s.toString().trim() : "";
                if (!isUsernameManuallyEdited && !input.isEmpty()) {
                    String suggested = Persona.normalizeUsername(input);
                    etUsername.setText(suggested);
                }
                updatePreview();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        etUsername.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (etUsername.hasFocus()) {
                    isUsernameManuallyEdited = true;
                }
                String raw = s != null ? s.toString().trim() : "";
                scheduleUsernameCheck(raw);
                updatePreview();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnSubmit.setOnClickListener(v -> createPersonaAndActivate());
    }

    private void scheduleUsernameCheck(String raw) {
        if (checkUsernameRunnable != null) {
            debounceHandler.removeCallbacks(checkUsernameRunnable);
        }
        String clean = Persona.normalizeUsername(raw);
        if (clean.length() < 3) {
            verifiedUsername = null;
            if (pbChecking != null) pbChecking.setVisibility(View.GONE);
            if (tvUsernameStatus != null) {
                tvUsernameStatus.setText(clean.isEmpty() ? "Enter username" : "Minimum 3 characters");
                tvUsernameStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.textTertiary));
            }
            if (btnSubmit != null) btnSubmit.setEnabled(false);
            return;
        }

        if (pbChecking != null) pbChecking.setVisibility(View.VISIBLE);
        if (tvUsernameStatus != null) {
            tvUsernameStatus.setText("Checking availability...");
            tvUsernameStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        }

        checkUsernameRunnable = () -> executeUsernameCheck(clean);
        debounceHandler.postDelayed(checkUsernameRunnable, DEBOUNCE_DELAY_MS);
    }

    private void executeUsernameCheck(String username) {
        if (getContext() == null) return;
        Context ctx = requireContext();

        // 1. Check local Persona database
        boolean localTaken = PersonaManager.getInstance(ctx).isUsernameTaken(username, null);
        if (localTaken) {
            onUsernameCheckResult(username, true);
            return;
        }

        // 2. Authoritative check via SovereignIdentityAndDirectoryEngine
        SovereignIdentityAndDirectoryEngine.getInstance(ctx).isUsernameTaken(username, null, isTaken -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> onUsernameCheckResult(username, isTaken));
            }
        });
    }

    private void onUsernameCheckResult(String username, boolean isTaken) {
        if (getContext() == null || pbChecking == null) return;
        pbChecking.setVisibility(View.GONE);

        String currentInput = etUsername != null && etUsername.getText() != null
                ? Persona.normalizeUsername(etUsername.getText().toString())
                : "";

        if (!username.equalsIgnoreCase(currentInput)) {
            return;
        }

        if (isTaken) {
            verifiedUsername = null;
            tvUsernameStatus.setText("✗ @" + username + " is already taken");
            tvUsernameStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            btnSubmit.setEnabled(false);
        } else {
            verifiedUsername = username;
            tvUsernameStatus.setText("✓ @" + username + " is available");
            tvUsernameStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            btnSubmit.setEnabled(true);
        }
    }

    private void updatePreview() {
        if (getContext() == null) return;
        String dName = etDisplayName != null && etDisplayName.getText() != null && !TextUtils.isEmpty(etDisplayName.getText().toString().trim())
                ? etDisplayName.getText().toString().trim()
                : "Persona Name";
        String uName = etUsername != null && etUsername.getText() != null && !TextUtils.isEmpty(etUsername.getText().toString().trim())
                ? Persona.normalizeUsername(etUsername.getText().toString())
                : "username";

        if (tvPreviewDisplayName != null) tvPreviewDisplayName.setText(dName);
        if (tvPreviewUsername != null) tvPreviewUsername.setText("@" + uName);
        if (tvPreviewMoodBadge != null) tvPreviewMoodBadge.setText(selectedMood);

        String glyph = dName.substring(0, 1).toUpperCase(Locale.ROOT);
        if (tvPreviewMonogram != null) {
            tvPreviewMonogram.setText(glyph);
            try {
                tvPreviewMonogram.setTextColor(Color.parseColor(selectedColorHex));
            } catch (Exception ignored) {}
        }
        if (vPreviewAvatarBg != null) {
            try {
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                int parsed = Color.parseColor(selectedColorHex);
                // 15% opacity tint for avatar background
                int tint = Color.argb(38, Color.red(parsed), Color.green(parsed), Color.blue(parsed));
                circle.setColor(tint);
                vPreviewAvatarBg.setBackground(circle);
            } catch (Exception ignored) {}
        }
    }

    private void createPersonaAndActivate() {
        if (getContext() == null || TextUtils.isEmpty(verifiedUsername)) {
            Toast.makeText(getContext(), "Please select an available username", Toast.LENGTH_SHORT).show();
            return;
        }

        String dName = etDisplayName != null && etDisplayName.getText() != null
                ? etDisplayName.getText().toString().trim()
                : "";
        if (TextUtils.isEmpty(dName)) {
            dName = verifiedUsername;
        }

        String bio = etBio != null && etBio.getText() != null
                ? etBio.getText().toString().trim()
                : "";

        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());
        Persona created = fm.addPersona(
                dName,
                verifiedUsername,
                bio,
                selectedMood,
                selectedColorHex,
                isTemporary,
                isTemporary ? 86400000L : 0L
        );

        Toast.makeText(requireContext(), "Persona @" + verifiedUsername + " created & activated!", Toast.LENGTH_SHORT).show();
        dismissAllowingStateLoss();

        if (listener != null) {
            listener.onPersonaCreated(created);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (checkUsernameRunnable != null) {
            debounceHandler.removeCallbacks(checkUsernameRunnable);
        }
    }
}
