package com.m3.veil.ui.main.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilBoardController;
import com.m3.veil.manager.VeilFeatureManager;

import java.util.List;

/**
 * Modern & Minimal Decentralized Community Moderation & Transparency Log.
 * Matching WhatsApp / Telegram-grade Material 3 standards.
 * Features:
 * 1. Active Community Jury Cases with anonymous consensus voting and XP/Karma rewards.
 * 2. Immutable Transparency Log receipts with cryptographic SHA-256 hashes and HMAC signatures.
 * 3. Verifiable Live Network Abuse Statistics & Spam Reduction metrics.
 */
public class ModerationTransparencyBottomSheet extends BottomSheetDialogFragment {

    private int mSelectedTab = 0; // 0 = Jury Cases, 1 = Transparency Log, 2 = Abuse Stats
    private LinearLayout mContentContainer;
    private TextView mTabJury;
    private TextView mTabTransparency;
    private TextView mTabStats;

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_moderation_transparency_sheet, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context context = requireContext();

        View btnClose = view.findViewById(R.id.btnCloseModeration);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }

        mContentContainer = view.findViewById(R.id.contentModerationContainer);
        mTabJury = view.findViewById(R.id.tabJuryCases);
        mTabTransparency = view.findViewById(R.id.tabTransparencyLog);
        mTabStats = view.findViewById(R.id.tabAbuseStats);

        if (mTabJury != null) {
            mTabJury.setOnClickListener(v -> selectTab(0));
        }
        if (mTabTransparency != null) {
            mTabTransparency.setOnClickListener(v -> selectTab(1));
        }
        if (mTabStats != null) {
            mTabStats.setOnClickListener(v -> selectTab(2));
        }

        updateTabStyles();
        renderTabContent(context);
    }

    private void selectTab(int index) {
        if (mSelectedTab == index) return;
        mSelectedTab = index;
        updateTabStyles();
        if (getContext() != null) {
            renderTabContent(requireContext());
        }
    }

    private void updateTabStyles() {
        if (getContext() == null) return;
        int activeBg = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
        int textColor = ContextCompat.getColor(requireContext(), R.color.textPrimary);
        int subTextColor = ContextCompat.getColor(requireContext(), R.color.textSecondary);

        applyTabStyle(mTabJury, mSelectedTab == 0, activeBg, textColor, subTextColor);
        applyTabStyle(mTabTransparency, mSelectedTab == 1, activeBg, textColor, subTextColor);
        applyTabStyle(mTabStats, mSelectedTab == 2, activeBg, textColor, subTextColor);
    }

    private void applyTabStyle(TextView tab, boolean isSelected, int activeBg, int textColor, int subTextColor) {
        if (tab == null) return;
        float dp = getResources().getDisplayMetrics().density;
        if (isSelected) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(17 * dp);
            gd.setColor(activeBg);
            tab.setBackground(gd);
            tab.setTextColor(Color.WHITE);
        } else {
            tab.setBackgroundResource(R.drawable.bg_filter_chip);
            tab.setTextColor(subTextColor);
        }
    }

    private void renderTabContent(Context context) {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        if (mSelectedTab == 0) {
            renderJuryCasesTab(context, dp);
        } else if (mSelectedTab == 1) {
            renderTransparencyLogTab(context, dp);
        } else {
            renderAbuseStatsTab(context, dp);
        }
    }

    // =========================================================================
    // TAB 1: JURY CASES
    // =========================================================================

    private void renderJuryCasesTab(Context context, float dp) {
        VeilBoardController vbc = VeilBoardController.getInstance(context);
        VeilFeatureManager fm = VeilFeatureManager.getInstance(context);

        // Juror Qualification Card
        MaterialCardView qualificationCard = createModernCard(context, dp);
        LinearLayout qContent = new LinearLayout(context);
        qContent.setOrientation(LinearLayout.VERTICAL);
        qContent.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ivShield = new ImageView(context);
        ivShield.setLayoutParams(new LinearLayout.LayoutParams((int) (20 * dp), (int) (20 * dp)));
        ivShield.setImageResource(R.drawable.ic_shield);
        ivShield.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary)));
        topRow.addView(ivShield);

        TextView tvJurorTitle = new TextView(context);
        LinearLayout.LayoutParams jtl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jtl.leftMargin = (int) (8 * dp);
        tvJurorTitle.setLayoutParams(jtl);
        tvJurorTitle.setText("Verified Juror Node • Karma " + fm.getKarma());
        tvJurorTitle.setTextSize(13.5f);
        tvJurorTitle.setTypeface(null, Typeface.BOLD);
        tvJurorTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        topRow.addView(tvJurorTitle);
        qContent.addView(topRow);

        TextView tvRewardNotice = new TextView(context);
        tvRewardNotice.setText("+10 XP & +2 Karma awarded for each consensus verdict cast. 3 independent peer signatures establish mesh consensus.");
        tvRewardNotice.setTextSize(12.5f);
        tvRewardNotice.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        tvRewardNotice.setLineSpacing(dp * 2, 1.0f);
        LinearLayout.LayoutParams rnl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rnl.topMargin = (int) (6 * dp);
        tvRewardNotice.setLayoutParams(rnl);
        qContent.addView(tvRewardNotice);

        qualificationCard.addView(qContent);
        mContentContainer.addView(qualificationCard);

        List<VeilBoardController.JuryCase> cases = vbc.getActiveJuryCases();
        if (cases.isEmpty()) {
            mContentContainer.addView(createEmptyState(context, dp, "All Cases Resolved", "There are no pending moderation cases across your active mesh peers."));
            return;
        }

        for (VeilBoardController.JuryCase jc : cases) {
            MaterialCardView caseCard = createModernCard(context, dp);
            LinearLayout cardContent = new LinearLayout(context);
            cardContent.setOrientation(LinearLayout.VERTICAL);
            cardContent.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));

            // Flag reason badge
            LinearLayout badgeRow = new LinearLayout(context);
            badgeRow.setOrientation(LinearLayout.HORIZONTAL);
            badgeRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvBadge = new TextView(context);
            tvBadge.setText("Flagged: " + (jc.reason != null ? jc.reason : "Community Report"));
            tvBadge.setTextSize(11.5f);
            tvBadge.setTypeface(null, Typeface.BOLD);
            tvBadge.setTextColor(Color.parseColor("#EF4444"));
            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setCornerRadius(10 * dp);
            badgeBg.setColor(Color.parseColor("#15EF4444"));
            tvBadge.setBackground(badgeBg);
            tvBadge.setPadding((int) (10 * dp), (int) (3 * dp), (int) (10 * dp), (int) (3 * dp));
            badgeRow.addView(tvBadge);
            cardContent.addView(badgeRow);

            // Post content preview snippet
            TextView snippet = new TextView(context);
            snippet.setText(jc.postSnippet != null ? "\"" + jc.postSnippet + "\"" : "\"[Media Content]\"");
            snippet.setTextSize(13f);
            snippet.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
            android.graphics.drawable.GradientDrawable snipBg = new android.graphics.drawable.GradientDrawable();
            snipBg.setCornerRadius(10 * dp);
            snipBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));
            snippet.setBackground(snipBg);
            snippet.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            LinearLayout.LayoutParams snipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            snipLp.topMargin = (int) (10 * dp);
            snipLp.bottomMargin = (int) (10 * dp);
            snippet.setLayoutParams(snipLp);
            cardContent.addView(snippet);

            // Consensus progress
            TextView tally = new TextView(context);
            tally.setText("Consensus: " + jc.votesRemove + " Quarantine / " + jc.votesKeep + " Keep (Target: 3)");
            tally.setTextSize(12f);
            tally.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            cardContent.addView(tally);

            // Action Buttons
            LinearLayout actionsRow = new LinearLayout(context);
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actLp.topMargin = (int) (12 * dp);
            actionsRow.setLayoutParams(actLp);

            MaterialButton btnKeep = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnKeep.setText("Dismiss & Keep");
            btnKeep.setTextSize(12f);
            btnKeep.setTextColor(Color.parseColor("#10B981"));
            btnKeep.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#3310B981")));
            btnKeep.setCornerRadius((int) (18 * dp));
            LinearLayout.LayoutParams keepLp = new LinearLayout.LayoutParams(0, (int) (40 * dp), 1.0f);
            keepLp.setMarginEnd((int) (8 * dp));
            btnKeep.setLayoutParams(keepLp);
            btnKeep.setOnClickListener(v -> {
                boolean voted = vbc.castJuryVote(jc.postId, false);
                if (voted) {
                    Toast.makeText(context, "✅ Verdict recorded! +10 XP & +2 Karma awarded", Toast.LENGTH_SHORT).show();
                    renderTabContent(context);
                } else {
                    Toast.makeText(context, "Already voted on this case", Toast.LENGTH_SHORT).show();
                }
            });

            MaterialButton btnQuarantine = new MaterialButton(context);
            btnQuarantine.setText("Quarantine");
            btnQuarantine.setTextSize(12f);
            btnQuarantine.setTextColor(Color.WHITE);
            btnQuarantine.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#EF4444")));
            btnQuarantine.setCornerRadius((int) (18 * dp));
            LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(0, (int) (40 * dp), 1.0f);
            btnQuarantine.setLayoutParams(remLp);
            btnQuarantine.setOnClickListener(v -> {
                boolean voted = vbc.castJuryVote(jc.postId, true);
                if (voted) {
                    Toast.makeText(context, "🚫 Verdict recorded! +10 XP & +2 Karma awarded", Toast.LENGTH_SHORT).show();
                    renderTabContent(context);
                } else {
                    Toast.makeText(context, "Already voted on this case", Toast.LENGTH_SHORT).show();
                }
            });

            actionsRow.addView(btnKeep);
            actionsRow.addView(btnQuarantine);
            cardContent.addView(actionsRow);

            caseCard.addView(cardContent);
            mContentContainer.addView(caseCard);
        }
    }

    // =========================================================================
    // TAB 2: TRANSPARENCY LOG RECEIPTS
    // =========================================================================

    private void renderTransparencyLogTab(Context context, float dp) {
        VeilBoardController vbc = VeilBoardController.getInstance(context);
        List<EncryptedChatDatabase.ModerationTransparencyLog> logs = vbc.getTransparencyLogs();

        // Info Banner Card
        MaterialCardView infoCard = createModernCard(context, dp);
        LinearLayout infoContent = new LinearLayout(context);
        infoContent.setOrientation(LinearLayout.VERTICAL);
        infoContent.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

        TextView tvTitle = new TextView(context);
        tvTitle.setText("Cryptographic Audit Log");
        tvTitle.setTextSize(13.5f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        infoContent.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("Every moderation consensus produces an immutable SHA-256 hash receipt verified across peer nodes.");
        tvSub.setTextSize(12f);
        tvSub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = (int) (4 * dp);
        tvSub.setLayoutParams(subLp);
        infoContent.addView(tvSub);

        infoCard.addView(infoContent);
        mContentContainer.addView(infoCard);

        if (logs.isEmpty()) {
            mContentContainer.addView(createEmptyState(context, dp, "No Audit Receipts", "No consensus actions have been logged yet."));
            return;
        }

        for (EncryptedChatDatabase.ModerationTransparencyLog log : logs) {
            MaterialCardView card = createModernCard(context, dp);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));

            // Verdict Header Row
            LinearLayout headerRow = new LinearLayout(context);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            boolean isQuarantine = "QUARANTINED".equalsIgnoreCase(log.verdict) || "REMOVED".equalsIgnoreCase(log.verdict);
            TextView verdictPill = new TextView(context);
            verdictPill.setText(isQuarantine ? "QUARANTINED" : "DISMISSED");
            verdictPill.setTextSize(11f);
            verdictPill.setTypeface(null, Typeface.BOLD);
            verdictPill.setTextColor(isQuarantine ? Color.parseColor("#EF4444") : Color.parseColor("#10B981"));
            android.graphics.drawable.GradientDrawable vBg = new android.graphics.drawable.GradientDrawable();
            vBg.setCornerRadius(10 * dp);
            vBg.setColor(isQuarantine ? Color.parseColor("#15EF4444") : Color.parseColor("#1510B981"));
            verdictPill.setBackground(vBg);
            verdictPill.setPadding((int) (10 * dp), (int) (3 * dp), (int) (10 * dp), (int) (3 * dp));
            headerRow.addView(verdictPill);

            TextView time = new TextView(context);
            time.setText(DateUtils.getRelativeTimeSpanString(log.timestampMs));
            time.setTextSize(12f);
            time.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.leftMargin = (int) (10 * dp);
            time.setLayoutParams(tLp);
            headerRow.addView(time);
            content.addView(headerRow);

            // Reason
            TextView reason = new TextView(context);
            reason.setText("Reason: " + (log.reason != null ? log.reason : "General Violation"));
            reason.setTextSize(13f);
            reason.setTypeface(null, Typeface.BOLD);
            reason.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = (int) (8 * dp);
            reason.setLayoutParams(rLp);
            content.addView(reason);

            // SHA-256 Hash box (Clickable to copy)
            LinearLayout hashBox = new LinearLayout(context);
            hashBox.setOrientation(LinearLayout.HORIZONTAL);
            hashBox.setGravity(Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable hBg = new android.graphics.drawable.GradientDrawable();
            hBg.setCornerRadius(8 * dp);
            hBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));
            hashBox.setBackground(hBg);
            hashBox.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hLp.topMargin = (int) (8 * dp);
            hLp.bottomMargin = (int) (8 * dp);
            hashBox.setLayoutParams(hLp);

            TextView hash = new TextView(context);
            String fullHash = log.contentHash != null ? log.contentHash : "sha256:unknown";
            String hashShort = fullHash.length() > 28 ? fullHash.substring(0, 28) + "..." : fullHash;
            hash.setText("SHA-256: " + hashShort);
            hash.setTextSize(11f);
            hash.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
            hash.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            hash.setLayoutParams(hl);
            hashBox.addView(hash);

            hashBox.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Moderation Hash", fullHash));
                    Toast.makeText(context, "SHA-256 receipt copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });
            content.addView(hashBox);

            // Jurors
            TextView jurors = new TextView(context);
            jurors.setText("Validated by " + log.jurorCount + " peer nodes • HMAC Verified");
            jurors.setTextSize(11.5f);
            jurors.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            content.addView(jurors);

            card.addView(content);
            mContentContainer.addView(card);
        }
    }

    // =========================================================================
    // TAB 3: ABUSE STATISTICS DASHBOARD
    // =========================================================================

    private void renderAbuseStatsTab(Context context, float dp) {
        VeilBoardController vbc = VeilBoardController.getInstance(context);
        VeilBoardController.AbuseStatistics stats = vbc.getAbuseStatistics();

        // 6 Modern Metric Cards
        addModernStatCard(context, dp, "Total Cases Reviewed", String.valueOf(stats.totalCasesReviewed), ContextCompat.getColor(context, R.color.colorPrimary));
        addModernStatCard(context, dp, "Malicious Content Quarantined", String.valueOf(stats.totalQuarantined), Color.parseColor("#EF4444"));
        addModernStatCard(context, dp, "False-Positives Dismissed", String.valueOf(stats.totalDismissed), Color.parseColor("#10B981"));
        addModernStatCard(context, dp, "Active Juror Peer Nodes", String.valueOf(stats.activeJurorsCount), Color.parseColor("#8B5CF6"));
        addModernStatCard(context, dp, "Mean Resolution Time", stats.avgResolutionTimeMin + " min", Color.parseColor("#F59E0B"));
        addModernStatCard(context, dp, "Spam Protection Rate", stats.spamReductionRate + "%", Color.parseColor("#06B6D4"));

        // Explanatory Governance Card
        MaterialCardView infoCard = createModernCard(context, dp);
        LinearLayout infoBox = new LinearLayout(context);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));

        TextView infoTitle = new TextView(context);
        infoTitle.setText("Community Governance Standard");
        infoTitle.setTextSize(13.5f);
        infoTitle.setTypeface(null, Typeface.BOLD);
        infoTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        infoBox.addView(infoTitle);

        TextView infoDesc = new TextView(context);
        infoDesc.setText("VEIL operates with zero centralized censorship. Content moderation decisions emerge directly from high-reputation peer nodes gossiping cryptographically signed consensus votes over WebRTC DataChannels.");
        infoDesc.setTextSize(12f);
        infoDesc.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        infoDesc.setLineSpacing(dp * 2, 1.0f);
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        idLp.topMargin = (int) (6 * dp);
        infoDesc.setLayoutParams(idLp);
        infoBox.addView(infoDesc);

        infoCard.addView(infoBox);
        mContentContainer.addView(infoCard);
    }

    private void addModernStatCard(Context context, float dp, String title, String value, int accentColor) {
        MaterialCardView card = createModernCard(context, dp);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(13f);
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(tLp);
        row.addView(tvTitle);

        TextView tvVal = new TextView(context);
        tvVal.setText(value);
        tvVal.setTextSize(16f);
        tvVal.setTypeface(null, Typeface.BOLD);
        tvVal.setTextColor(accentColor);
        row.addView(tvVal);

        card.addView(row);
        mContentContainer.addView(card);
    }

    private MaterialCardView createModernCard(Context context, float dp) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(14 * dp);
        card.setCardElevation(0);
        card.setStrokeWidth((int) (1 * dp));
        card.setStrokeColor(ContextCompat.getColor(context, R.color.colorOutline));
        card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSurfaceContainerLow));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * dp);
        card.setLayoutParams(lp);
        return card;
    }

    private View createEmptyState(Context context, float dp, String title, String subtitle) {
        LinearLayout empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (36 * dp), 0, (int) (36 * dp));

        FrameLayout iconFrame = new FrameLayout(context);
        iconFrame.setLayoutParams(new LinearLayout.LayoutParams((int) (56 * dp), (int) (56 * dp)));
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBg.setColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant));
        iconFrame.setBackground(iconBg);

        ImageView iv = new ImageView(context);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams((int) (26 * dp), (int) (26 * dp), Gravity.CENTER);
        iv.setLayoutParams(ivLp);
        iv.setImageResource(R.drawable.ic_shield);
        iv.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary)));
        iconFrame.addView(iv);
        empty.addView(iconFrame);

        TextView tvH = new TextView(context);
        tvH.setText(title);
        tvH.setTextSize(15.5f);
        tvH.setTypeface(null, Typeface.BOLD);
        tvH.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = (int) (12 * dp);
        tvH.setLayoutParams(hl);
        empty.addView(tvH);

        TextView tvSub = new TextView(context);
        tvSub.setText(subtitle);
        tvSub.setTextSize(12.5f);
        tvSub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        tvSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = (int) (4 * dp);
        tvSub.setLayoutParams(sl);
        empty.addView(tvSub);

        return empty;
    }
}
