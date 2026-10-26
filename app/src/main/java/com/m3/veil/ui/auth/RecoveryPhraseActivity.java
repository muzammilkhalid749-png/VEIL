package com.m3.veil.ui.auth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.m3.veil.R;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * RECOVERY PHRASE ACTIVITY (BIP-39 12-WORD DETERMINISTIC VAULT KEY)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * - Protects screen with FLAG_SECURE against screenshots & screen-recording.
 * - Renders a numbered 2-column 12-word BIP-39 grid alongside the raw selectable seed phrase.
 * - Displays the live deterministically derived Sovereign UID (`veil_<28_hex>`) and SHA-256
 *   key fingerprint so the user can verify that the 12 words produce their exact identity.
 * - Includes a Shoulder-Surfing Blur toggle to mask/reveal the seed words on demand.
 */
public class RecoveryPhraseActivity extends AppCompatActivity {

    private String recoveryPhrase;
    private boolean isWordsMasked = false;
    private final List<TextView> wordValueViews = new ArrayList<>();
    private TextView phraseText;
    private TextView btnTogglePrivacyBlur;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enforce hardware screenshot protection while viewing master recovery phrase
        try {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
            );
        } catch (Throwable ignored) {}

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recovery_phrase);

        recoveryPhrase = getIntent().getStringExtra("recovery_phrase");
        boolean isViewOnly = getIntent().getBooleanExtra("is_view_only", false);

        SovereignIdentityAndDirectoryEngine engine = SovereignIdentityAndDirectoryEngine.getInstance(this);

        if (recoveryPhrase == null || recoveryPhrase.trim().isEmpty()) {
            recoveryPhrase = engine.loadRecoveryPhraseLocally(this);
            if (recoveryPhrase == null || recoveryPhrase.trim().isEmpty() || !engine.validatePhrase(recoveryPhrase)) {
                recoveryPhrase = engine.generateMnemonic();
                engine.saveRecoveryPhraseLocally(this, recoveryPhrase);
            }
        } else {
            recoveryPhrase = recoveryPhrase.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            engine.saveRecoveryPhraseLocally(this, recoveryPhrase);
        }

        initializeViews(isViewOnly, engine);
        handleWindowInsets();
    }

    private void handleWindowInsets() {
        com.m3.veil.utils.WindowInsetsHelper.applySystemBarsPadding(findViewById(R.id.recovery_root));
    }

    private void initializeViews(boolean isViewOnly, SovereignIdentityAndDirectoryEngine engine) {
        View backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        phraseText = findViewById(R.id.phraseText);
        if (phraseText != null) {
            phraseText.setText(recoveryPhrase);
        }

        // Display deterministic UID derived from these exact 12 words
        TextView tvDerivedIdentityBadge = findViewById(R.id.tvDerivedIdentityBadge);
        if (tvDerivedIdentityBadge != null) {
            String uid = engine.getCurrentUid();
            String checksum = SovereignIdentityAndDirectoryEngine
                    .sha256Hex(recoveryPhrase.getBytes(StandardCharsets.UTF_8))
                    .substring(0, 8)
                    .toUpperCase(Locale.ROOT);
            String shortUid = uid != null && uid.length() > 20
                    ? uid.substring(0, 14) + "…" + uid.substring(uid.length() - 6)
                    : (uid != null ? uid : "veil_sovereign");
            tvDerivedIdentityBadge.setText("🔐 UID: " + shortUid + "  •  CRC: " + checksum);
        }

        // Populate 2-column numbered 12-word BIP-39 grid
        LinearLayout wordsGridContainer = findViewById(R.id.wordsGridContainer);
        if (wordsGridContainer != null && recoveryPhrase != null) {
            populateWordsGrid(wordsGridContainer, recoveryPhrase.split("\\s+"));
        }

        btnTogglePrivacyBlur = findViewById(R.id.btnTogglePrivacyBlur);
        if (btnTogglePrivacyBlur != null) {
            btnTogglePrivacyBlur.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                isWordsMasked = !isWordsMasked;
                applyWordMaskState();
            });
        }

        Button copyButton = findViewById(R.id.copyButton);
        if (copyButton != null) {
            copyButton.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Veil Recovery Phrase", recoveryPhrase);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "12-word recovery phrase copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });
        }

        Button confirmButton = findViewById(R.id.confirmButton);
        if (confirmButton != null) {
            if (isViewOnly) {
                confirmButton.setText("Done");
                confirmButton.setOnClickListener(v -> finish());
            } else {
                confirmButton.setText("I've Saved It • Continue");
                confirmButton.setOnClickListener(v -> {
                    Intent intent = new Intent(this, UsernameActivity.class);
                    startActivity(intent);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finish();
                });
            }
        }
    }

    private void populateWordsGrid(LinearLayout container, String[] words) {
        container.removeAllViews();
        wordValueViews.clear();
        float dp = getResources().getDisplayMetrics().density;

        int total = Math.min(12, words.length);
        int rows = (total + 1) / 2;

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (r < rows - 1) {
                rowLp.bottomMargin = (int) (8 * dp);
            }
            row.setLayoutParams(rowLp);

            int leftIndex = r * 2;
            int rightIndex = leftIndex + 1;

            row.addView(createWordCell(leftIndex + 1, words[leftIndex], dp, true));
            if (rightIndex < total) {
                row.addView(createWordCell(rightIndex + 1, words[rightIndex], dp, false));
            } else {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                row.addView(spacer);
            }

            container.addView(row);
        }
    }

    private View createWordCell(int number, String word, float dp, boolean isLeft) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setBackgroundResource(R.drawable.bg_filter_chip);
        cell.setPadding((int) (12 * dp), (int) (9 * dp), (int) (12 * dp), (int) (9 * dp));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isLeft) {
            lp.rightMargin = (int) (5 * dp);
        } else {
            lp.leftMargin = (int) (5 * dp);
        }
        cell.setLayoutParams(lp);

        TextView tvNum = new TextView(this);
        tvNum.setText(String.format(Locale.US, "%02d.", number));
        tvNum.setTextSize(12f);
        tvNum.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvNum.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        cell.addView(tvNum);

        TextView tvWord = new TextView(this);
        tvWord.setText(word);
        tvWord.setTag(word);
        tvWord.setTextSize(14f);
        tvWord.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvWord.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        wLp.leftMargin = (int) (8 * dp);
        tvWord.setLayoutParams(wLp);
        cell.addView(tvWord);

        wordValueViews.add(tvWord);
        return cell;
    }

    private void applyWordMaskState() {
        if (btnTogglePrivacyBlur != null) {
            btnTogglePrivacyBlur.setText(isWordsMasked ? "👁️ Reveal Words" : "👁️ Hide Words");
        }
        for (TextView tv : wordValueViews) {
            String original = (String) tv.getTag();
            if (isWordsMasked) {
                tv.setText("••••••");
                tv.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            } else {
                tv.setText(original != null ? original : "");
                tv.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            }
        }
        if (phraseText != null) {
            if (isWordsMasked) {
                phraseText.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                phraseText.getPaint().setMaskFilter(new BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL));
            } else {
                phraseText.getPaint().setMaskFilter(null);
            }
            phraseText.invalidate();
        }
    }
}
