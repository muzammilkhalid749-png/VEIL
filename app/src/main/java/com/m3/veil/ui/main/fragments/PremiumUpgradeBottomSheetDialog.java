package com.m3.veil.ui.main.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.m3.veil.R;
import com.m3.veil.manager.BillingManager;
import com.m3.veil.manager.VeilFeatureManager;

import java.util.Locale;

/**
 * Veil Pro / Multi-Persona Paywall BottomSheetDialog.
 * Triggered when attempting to create or activate Persona 3+ on the Free tier.
 */
public class PremiumUpgradeBottomSheetDialog extends BottomSheetDialogFragment {

    public interface OnUpgradeSuccessListener {
        void onUpgradeCompleted();
    }

    private OnUpgradeSuccessListener upgradeSuccessListener;
    private String selectedPlan = BillingManager.PLAN_ANNUAL;

    public static void showPaywall(@NonNull Context context, @Nullable OnUpgradeSuccessListener listener) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.ModernBottomSheetDialogTheme);
        View content = buildPaywallView(context, dialog::dismiss, listener);
        dialog.setContentView(content);
        dialog.show();
    }

    public static void showPaywall(@NonNull Context context, @Nullable String reason, @Nullable OnUpgradeSuccessListener listener) {
        showPaywall(context, listener);
    }

    public static void showPaywall(@NonNull FragmentManager fm, @Nullable OnUpgradeSuccessListener listener) {
        PremiumUpgradeBottomSheetDialog fragment = new PremiumUpgradeBottomSheetDialog();
        fragment.setOnUpgradeSuccessListener(listener);
        fragment.show(fm, "PremiumUpgradeBottomSheetDialog");
    }

    public static void showPaywall(@NonNull FragmentManager fm, @Nullable String reason, @Nullable OnUpgradeSuccessListener listener) {
        showPaywall(fm, listener);
    }

    public void setOnUpgradeSuccessListener(OnUpgradeSuccessListener listener) {
        this.upgradeSuccessListener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ModernBottomSheetDialogTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildPaywallView(requireContext(), this::dismissAllowingStateLoss, upgradeSuccessListener);
    }

    private static View buildPaywallView(@NonNull Context context, @NonNull Runnable onDismiss, @Nullable OnUpgradeSuccessListener listener) {
        float dp = context.getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bottom_sheet_background);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (24 * dp);
        int padV = (int) (18 * dp);
        root.setPadding(padH, padV, padH, (int) (28 * dp));

        // Handle bar
        View handle = new View(context);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (38 * dp), (int) (4 * dp));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (18 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundColor(ContextCompat.getColor(context, R.color.colorOutline));
        root.addView(handle);

        // Badge Pill
        TextView tvBadge = new TextView(context);
        tvBadge.setText("💎 VEIL PRO • SOVEREIGN MULTI-VAULT");
        tvBadge.setTextSize(11.5f);
        tvBadge.setTypeface(null, Typeface.BOLD);
        tvBadge.setTextColor(Color.parseColor("#38BDF8"));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(14 * dp);
        badgeBg.setColor(Color.parseColor("#1E293B"));
        badgeBg.setStroke((int) (1 * dp), Color.parseColor("#0284C7"));
        tvBadge.setBackground(badgeBg);
        tvBadge.setPadding((int) (12 * dp), (int) (5 * dp), (int) (12 * dp), (int) (5 * dp));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.bottomMargin = (int) (12 * dp);
        tvBadge.setLayoutParams(badgeLp);
        root.addView(tvBadge);

        // Title
        TextView tvTitle = new TextView(context);
        tvTitle.setText("Unlock Persona 3+ & Unlimited Encrypted Vaults");
        tvTitle.setTextSize(21f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        root.addView(tvTitle);

        // Subtitle
        TextView tvSub = new TextView(context);
        tvSub.setText("Persona 1 (Main) and Persona 2 are included for free. Upgrade to Veil Pro to create and switch between 3 or more hardware-isolated cryptographic personas.");
        tvSub.setTextSize(13.5f);
        tvSub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        tvSub.setPadding(0, (int) (6 * dp), 0, (int) (18 * dp));
        root.addView(tvSub);

        // Feature list
        String[][] features = {
                {"🎭 Unlimited Isolated Personas", "Dedicated SQLCipher vault, unique @username, avatar, and ECDSA keypair per persona."},
                {"🔑 Independent P2P Routing", "Separate WebRTC signaling endpoints and zero correlation between identities."},
                {"⏳ Ephemeral Self-Destruct Personas", "Auto-shredding 1h / 24h / 7d burner identities with cryptographic zeroization."},
                {"🛡️ Priority TURN Mesh & AR Masks", "High-bandwidth relay priority and studio-grade real-time call privacy filters."}
        };

        for (String[] feat : features) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
            row.setBackgroundResource(R.drawable.bg_search_input);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (int) (8 * dp);
            row.setLayoutParams(rowLp);

            TextView fTitle = new TextView(context);
            fTitle.setText(feat[0]);
            fTitle.setTextSize(14f);
            fTitle.setTypeface(null, Typeface.BOLD);
            fTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
            row.addView(fTitle);

            TextView fDesc = new TextView(context);
            fDesc.setText(feat[1]);
            fDesc.setTextSize(12f);
            fDesc.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            fDesc.setPadding(0, (int) (2 * dp), 0, 0);
            row.addView(fDesc);

            root.addView(row);
        }

        // Plan Selector Section
        TextView tvPlansHeader = new TextView(context);
        tvPlansHeader.setText("CHOOSE YOUR TIER");
        tvPlansHeader.setTextSize(11.5f);
        tvPlansHeader.setTypeface(null, Typeface.BOLD);
        tvPlansHeader.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        tvPlansHeader.setPadding(0, (int) (10 * dp), 0, (int) (8 * dp));
        root.addView(tvPlansHeader);

        final String[] currentSelection = {BillingManager.PLAN_ANNUAL};
        final MaterialCardView[] planCards = new MaterialCardView[3];
        final String[] planIds = {
                BillingManager.PLAN_ANNUAL,
                BillingManager.PLAN_MONTHLY,
                BillingManager.PLAN_LIFETIME
        };
        final String[] planTitles = {
                "⚡ Veil Pro Annual (Save 45%)",
                "🌙 Veil Pro Monthly",
                "♾️ Sovereign Lifetime Vault"
        };
        final String[] planPrices = {
                "$29.99 / year  •  $2.49/mo",
                "$4.99 / month  •  Cancel anytime",
                "$79.99 one-time  •  Permanent unlock"
        };

        Runnable updateCardStyles = () -> {
            for (int i = 0; i < planCards.length; i++) {
                MaterialCardView c = planCards[i];
                if (c == null) continue;
                boolean sel = planIds[i].equals(currentSelection[0]);
                c.setStrokeColor(ContextCompat.getColor(context, sel ? R.color.colorPrimary : R.color.colorOutline));
                c.setStrokeWidth((int) ((sel ? 2.0f : 1.0f) * dp));
                c.setCardBackgroundColor(ContextCompat.getColor(context, sel ? R.color.colorSurfaceVariant : R.color.colorSurface));
            }
        };

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            MaterialCardView card = new MaterialCardView(context);
            planCards[i] = card;
            card.setRadius(14 * dp);
            card.setCardElevation(0f);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (8 * dp);
            card.setLayoutParams(cardLp);

            LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

            TextView pTitle = new TextView(context);
            pTitle.setText(planTitles[i]);
            pTitle.setTextSize(15f);
            pTitle.setTypeface(null, Typeface.BOLD);
            pTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
            inner.addView(pTitle);

            TextView pSub = new TextView(context);
            pSub.setText(planPrices[i]);
            pSub.setTextSize(12.5f);
            pSub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            inner.addView(pSub);

            card.addView(inner);
            card.setOnClickListener(v -> {
                currentSelection[0] = planIds[idx];
                updateCardStyles.run();
            });
            root.addView(card);
        }
        updateCardStyles.run();

        // Primary Upgrade CTA Button
        MaterialButton btnUpgrade = new MaterialButton(context);
        btnUpgrade.setText("Unlock Veil Pro & Persona 3+");
        btnUpgrade.setCornerRadius((int) (14 * dp));
        btnUpgrade.setTextSize(15f);
        btnUpgrade.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * dp));
        btnLp.topMargin = (int) (12 * dp);
        btnUpgrade.setLayoutParams(btnLp);
        btnUpgrade.setOnClickListener(v -> {
            long duration = BillingManager.PLAN_MONTHLY.equals(currentSelection[0])
                    ? 30L * 86400_000L
                    : (BillingManager.PLAN_ANNUAL.equals(currentSelection[0]) ? 365L * 86400_000L : 0L);
            BillingManager.getInstance(context).activatePremium(currentSelection[0], duration);
            Toast.makeText(context, "💎 Veil Pro Activated! Unlimited Personas Unlocked.", Toast.LENGTH_SHORT).show();
            onDismiss.run();
            if (listener != null) {
                listener.onUpgradeCompleted();
            }
        });
        root.addView(btnUpgrade);

        // Unlock with VeilCoins Option
        int userCoins = VeilFeatureManager.getInstance(context).getVeilCoins();
        MaterialButton btnCoins = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCoins.setText(String.format(Locale.US, "🪙 Redeem 150 VeilCoins (Balance: %d VC)", userCoins));
        btnCoins.setCornerRadius((int) (14 * dp));
        LinearLayout.LayoutParams coinsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * dp));
        coinsLp.topMargin = (int) (8 * dp);
        btnCoins.setLayoutParams(coinsLp);
        btnCoins.setOnClickListener(v -> {
            if (VeilFeatureManager.getInstance(context).spendCoins(150)) {
                BillingManager.getInstance(context).activatePremium(BillingManager.PLAN_COINS_UNLOCK, 0L);
                Toast.makeText(context, "🎭 Persona Master Unlocked with 150 VeilCoins!", Toast.LENGTH_SHORT).show();
                onDismiss.run();
                if (listener != null) {
                    listener.onUpgradeCompleted();
                }
            } else {
                Toast.makeText(context, "Need 150 VeilCoins (Current balance: " + userCoins + " VC)", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnCoins);

        // Maybe Later button
        TextView btnLater = new TextView(context);
        btnLater.setText("Keep Free Tier (2 Personas)");
        btnLater.setTextSize(13f);
        btnLater.setGravity(Gravity.CENTER);
        btnLater.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        btnLater.setPadding(0, (int) (14 * dp), 0, (int) (4 * dp));
        btnLater.setOnClickListener(v -> onDismiss.run());
        root.addView(btnLater);

        scroll.addView(root);
        return scroll;
    }
}
