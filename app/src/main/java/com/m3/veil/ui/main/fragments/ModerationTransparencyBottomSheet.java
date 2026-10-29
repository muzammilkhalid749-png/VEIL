package com.m3.veil.ui.main.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilBoardController;
import com.m3.veil.manager.VeilFeatureManager;

import java.util.List;

/**
 * ModerationTransparencyBottomSheet: Decentralized Community Moderation & Transparency Log (#193, #194, #196).
 * Features:
 * 1. Active Community Jury Cases with anonymous consensus voting and XP/Karma rewards (#193).
 * 2. Immutable Transparency Log receipts with cryptographic SHA-256 hashes and HMAC signatures (#196).
 * 3. Verifiable Abuse Statistics & Spam Reduction metrics (#194).
 */
public class ModerationTransparencyBottomSheet extends BottomSheetDialogFragment {

    private int mSelectedTab = 0; // 0 = Jury Cases, 1 = Transparency Log, 2 = Abuse Stats
    private LinearLayout mContentContainer;
    private Button mTabJury;
    private Button mTabTransparency;
    private Button mTabStats;

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * dp);
        int padV = (int) (14 * dp);
        root.setPadding(padH, padV, padH, padV * 2);

        // Handle
        View handle = new View(context);
        int handleW = (int) (38 * dp);
        int handleH = (int) (4 * dp);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Title Row
        TextView title = new TextView(context);
        title.setText("⚖️ Decentralized Moderation");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        root.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Verifiable P2P community jury & immutable transparency receipts");
        subtitle.setTextSize(12);
        subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = (int) (14 * dp);
        subtitle.setLayoutParams(subLp);
        root.addView(subtitle);

        // Tab Selector Row
        LinearLayout tabRow = new LinearLayout(context);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabRowLp.bottomMargin = (int) (14 * dp);
        tabRow.setLayoutParams(tabRowLp);

        mTabJury = createTabButton(context, "⚖️ Jury Cases", 0);
        mTabTransparency = createTabButton(context, "📜 Receipts", 1);
        mTabStats = createTabButton(context, "📊 Abuse Stats", 2);

        tabRow.addView(mTabJury);
        tabRow.addView(mTabTransparency);
        tabRow.addView(mTabStats);
        root.addView(tabRow);

        // Scrollable Content
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (420 * dp));
        scrollView.setLayoutParams(scrollLp);

        mContentContainer = new LinearLayout(context);
        mContentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mContentContainer);
        root.addView(scrollView);

        renderTabContent(context);
        updateTabStyles();

        return root;
    }

    private Button createTabButton(Context context, String title, int tabIndex) {
        float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(context, null, android.R.attr.borderlessButtonStyle);
        btn.setText(title);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (38 * dp), 1.0f);
        lp.setMarginEnd((int) (4 * dp));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> {
            mSelectedTab = tabIndex;
            updateTabStyles();
            renderTabContent(context);
        });
        return btn;
    }

    private void updateTabStyles() {
        int activeBg = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
        int inactiveBg = Color.parseColor("#1E293B");

        mTabJury.setBackgroundColor(mSelectedTab == 0 ? activeBg : inactiveBg);
        mTabJury.setTextColor(mSelectedTab == 0 ? Color.WHITE : Color.parseColor("#94A3B8"));

        mTabTransparency.setBackgroundColor(mSelectedTab == 1 ? activeBg : inactiveBg);
        mTabTransparency.setTextColor(mSelectedTab == 1 ? Color.WHITE : Color.parseColor("#94A3B8"));

        mTabStats.setBackgroundColor(mSelectedTab == 2 ? activeBg : inactiveBg);
        mTabStats.setTextColor(mSelectedTab == 2 ? Color.WHITE : Color.parseColor("#94A3B8"));
    }

    private void renderTabContent(Context context) {
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
    // TAB 1: JURY CASES (#193)
    // =========================================================================

    private void renderJuryCasesTab(Context context, float dp) {
        VeilBoardController vbc = VeilBoardController.getInstance(context);
        VeilFeatureManager fm = VeilFeatureManager.getInstance(context);

        // Juror Qualification Badge
        LinearLayout badgeBox = new LinearLayout(context);
        badgeBox.setOrientation(LinearLayout.VERTICAL);
        badgeBox.setBackgroundColor(Color.parseColor("#131D2E"));
        badgeBox.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.bottomMargin = (int) (14 * dp);
        badgeBox.setLayoutParams(badgeLp);

        TextView badgeText = new TextView(context);
        badgeText.setText("🛡️ Qualified Juror Node: Karma " + fm.getKarma() + " • +10 XP & +2 Karma / Verdict");
        badgeText.setTextSize(12);
        badgeText.setTypeface(null, Typeface.BOLD);
        badgeText.setTextColor(Color.parseColor("#38BDF8"));
        badgeBox.addView(badgeText);

        TextView badgeSub = new TextView(context);
        badgeSub.setText("Reports require 3 independent peer node votes to reach decentralized quarantine consensus.");
        badgeSub.setTextSize(11);
        badgeSub.setTextColor(Color.parseColor("#94A3B8"));
        badgeBox.addView(badgeSub);
        mContentContainer.addView(badgeBox);

        List<VeilBoardController.JuryCase> cases = vbc.getActiveJuryCases();
        if (cases.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("✨ All caught up!\nNo pending moderation cases currently flagged in the P2P mesh.");
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.parseColor("#64748B"));
            empty.setPadding(0, (int) (40 * dp), 0, (int) (40 * dp));
            mContentContainer.addView(empty);
            return;
        }

        for (VeilBoardController.JuryCase jc : cases) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor("#0F172A"));
            card.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (12 * dp);
            card.setLayoutParams(cardLp);

            // Flag reason
            TextView reason = new TextView(context);
            reason.setText("🚩 Flagged for: " + jc.reason);
            reason.setTextSize(13);
            reason.setTypeface(null, Typeface.BOLD);
            reason.setTextColor(Color.parseColor("#F87171"));
            card.addView(reason);

            // Post Snippet
            TextView snippet = new TextView(context);
            snippet.setText("\"" + jc.postSnippet + "\"");
            snippet.setTextSize(12);
            snippet.setTextColor(Color.parseColor("#E2E8F0"));
            snippet.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
            snippet.setBackgroundColor(Color.parseColor("#1E293B"));
            LinearLayout.LayoutParams snipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            snipLp.topMargin = (int) (6 * dp);
            snipLp.bottomMargin = (int) (8 * dp);
            snippet.setLayoutParams(snipLp);
            card.addView(snippet);

            // Tally
            TextView tally = new TextView(context);
            tally.setText("Current Tally: " + jc.votesRemove + " Remove / " + jc.votesKeep + " Keep (Threshold: 3)");
            tally.setTextSize(11);
            tally.setTextColor(Color.parseColor("#94A3B8"));
            card.addView(tally);

            // Action Buttons Row
            LinearLayout actionsRow = new LinearLayout(context);
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actLp.topMargin = (int) (8 * dp);
            actionsRow.setLayoutParams(actLp);

            Button btnKeep = new Button(context, null, android.R.attr.borderlessButtonStyle);
            btnKeep.setText("✅ Dismiss & Keep");
            btnKeep.setTextSize(11);
            btnKeep.setTextColor(Color.parseColor("#10B981"));
            btnKeep.setBackgroundColor(Color.parseColor("#1E293B"));
            LinearLayout.LayoutParams keepLp = new LinearLayout.LayoutParams(0, (int) (36 * dp), 1.0f);
            keepLp.setMarginEnd((int) (6 * dp));
            btnKeep.setLayoutParams(keepLp);
            btnKeep.setOnClickListener(v -> {
                boolean voted = vbc.castJuryVote(jc.postId, false);
                if (voted) {
                    Toast.makeText(context, "Verdict recorded! +10 XP & +2 Karma awarded", Toast.LENGTH_SHORT).show();
                    renderTabContent(context);
                } else {
                    Toast.makeText(context, "Already voted on this case", Toast.LENGTH_SHORT).show();
                }
            });

            Button btnRemove = new Button(context, null, android.R.attr.borderlessButtonStyle);
            btnRemove.setText("🚫 Vote Quarantine");
            btnRemove.setTextSize(11);
            btnRemove.setTextColor(Color.WHITE);
            btnRemove.setBackgroundColor(Color.parseColor("#DC2626"));
            LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(0, (int) (36 * dp), 1.0f);
            btnRemove.setLayoutParams(remLp);
            btnRemove.setOnClickListener(v -> {
                boolean voted = vbc.castJuryVote(jc.postId, true);
                if (voted) {
                    Toast.makeText(context, "Verdict recorded! +10 XP & +2 Karma awarded", Toast.LENGTH_SHORT).show();
                    renderTabContent(context);
                } else {
                    Toast.makeText(context, "Already voted on this case", Toast.LENGTH_SHORT).show();
                }
            });

            actionsRow.addView(btnKeep);
            actionsRow.addView(btnRemove);
            card.addView(actionsRow);

            mContentContainer.addView(card);
        }
    }

    // =========================================================================
    // TAB 2: TRANSPARENCY LOG RECEIPTS (#196)
    // =========================================================================

    private void renderTransparencyLogTab(Context context, float dp) {
        VeilBoardController vbc = VeilBoardController.getInstance(context);
        List<EncryptedChatDatabase.ModerationTransparencyLog> logs = vbc.getTransparencyLogs();

        TextView intro = new TextView(context);
        intro.setText("📜 Immutable Cryptographic Audit Log\nEvery moderation consensus generates a verifiable SHA-256 hash receipt.");
        intro.setTextSize(12);
        intro.setTextColor(Color.parseColor("#94A3B8"));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = (int) (12 * dp);
        intro.setLayoutParams(inLp);
        mContentContainer.addView(intro);

        for (EncryptedChatDatabase.ModerationTransparencyLog log : logs) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor("#0F172A"));
            card.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (10 * dp);
            card.setLayoutParams(cardLp);

            // Verdict Header Row
            LinearLayout vRow = new LinearLayout(context);
            vRow.setOrientation(LinearLayout.HORIZONTAL);
            vRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView verdict = new TextView(context);
            boolean isQuarantine = "QUARANTINED".equalsIgnoreCase(log.verdict) || "REMOVED".equalsIgnoreCase(log.verdict);
            verdict.setText(isQuarantine ? "🚫 QUARANTINED" : "✅ DISMISSED");
            verdict.setTextSize(12);
            verdict.setTypeface(null, Typeface.BOLD);
            verdict.setTextColor(isQuarantine ? Color.parseColor("#EF4444") : Color.parseColor("#10B981"));
            vRow.addView(verdict);

            TextView time = new TextView(context);
            time.setText(" • " + DateUtils.getRelativeTimeSpanString(log.timestampMs));
            time.setTextSize(11);
            time.setTextColor(Color.parseColor("#64748B"));
            vRow.addView(time);
            card.addView(vRow);

            // Reason
            TextView reason = new TextView(context);
            reason.setText("Reason: " + log.reason);
            reason.setTextSize(12);
            reason.setTextColor(Color.parseColor("#CBD5E1"));
            LinearLayout.LayoutParams reLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            reLp.topMargin = (int) (4 * dp);
            reason.setLayoutParams(reLp);
            card.addView(reason);

            // Content Hash
            TextView hash = new TextView(context);
            String hashShort = log.contentHash != null && log.contentHash.length() > 24
                    ? log.contentHash.substring(0, 24) + "..."
                    : log.contentHash;
            hash.setText("SHA-256: " + hashShort);
            hash.setTextSize(10);
            hash.setTextColor(Color.parseColor("#38BDF8"));
            hash.setTypeface(Typeface.MONOSPACE);
            card.addView(hash);

            // Jurors
            TextView jurors = new TextView(context);
            jurors.setText("Validated by " + log.jurorCount + " decentralized peer nodes • HMAC Verified");
            jurors.setTextSize(10);
            jurors.setTextColor(Color.parseColor("#64748B"));
            card.addView(jurors);

            mContentContainer.addView(card);
        }
    }

    // =========================================================================
    // TAB 3: ABUSE STATISTICS DASHBOARD (#194)
    // =========================================================================

    private void renderAbuseStatsTab(Context context, float dp) {
        VeilBoardController vbc = VeilBoardController.getInstance(context);
        VeilBoardController.AbuseStatistics stats = vbc.getAbuseStatistics();

        TextView intro = new TextView(context);
        intro.setText("📊 Live Network Abuse & Spam Transparency");
        intro.setTextSize(14);
        intro.setTypeface(null, Typeface.BOLD);
        intro.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = (int) (12 * dp);
        intro.setLayoutParams(inLp);
        mContentContainer.addView(intro);

        // 2x3 Stat Metrics Grid
        addStatCard(context, dp, "⚖️ Total Cases Reviewed", String.valueOf(stats.totalCasesReviewed), "#38BDF8");
        addStatCard(context, dp, "🚫 Malicious Content Quarantined", String.valueOf(stats.totalQuarantined), "#F87171");
        addStatCard(context, dp, "✅ False-Positives Dismissed", String.valueOf(stats.totalDismissed), "#34D399");
        addStatCard(context, dp, "👥 Active Juror Peer Nodes", String.valueOf(stats.activeJurorsCount), "#A78BFA");
        addStatCard(context, dp, "⏱️ Mean Resolution Time", stats.avgResolutionTimeMin + " min", "#FBBF24");
        addStatCard(context, dp, "🛡️ Mesh Spam Reduction Rate", stats.spamReductionRate + "%", "#10B981");

        // Explanatory Box
        LinearLayout infoBox = new LinearLayout(context);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setBackgroundColor(Color.parseColor("#0F172A"));
        infoBox.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ibLp.topMargin = (int) (12 * dp);
        infoBox.setLayoutParams(ibLp);

        TextView infoTitle = new TextView(context);
        infoTitle.setText("🔒 100% Zero-Cloud Mesh Governance");
        infoTitle.setTextSize(12);
        infoTitle.setTypeface(null, Typeface.BOLD);
        infoTitle.setTextColor(Color.WHITE);
        infoBox.addView(infoTitle);

        TextView infoDesc = new TextView(context);
        infoDesc.setText("VEIL operates with no central administrator or censor. Moderation relies entirely on high-reputation local nodes gossiping signed TYPE_MODERATION_VOTE packets over WebRTC DataChannels.");
        infoDesc.setTextSize(11);
        infoDesc.setTextColor(Color.parseColor("#94A3B8"));
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        idLp.topMargin = (int) (4 * dp);
        infoDesc.setLayoutParams(idLp);
        infoBox.addView(infoDesc);

        mContentContainer.addView(infoBox);
    }

    private void addStatCard(Context context, float dp, String title, String value, String colorHex) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.parseColor("#0F172A"));
        row.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * dp);
        row.setLayoutParams(lp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(12);
        tvTitle.setTextColor(Color.parseColor("#E2E8F0"));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(tLp);
        row.addView(tvTitle);

        TextView tvVal = new TextView(context);
        tvVal.setText(value);
        tvVal.setTextSize(15);
        tvVal.setTypeface(null, Typeface.BOLD);
        tvVal.setTextColor(Color.parseColor(colorHex));
        row.addView(tvVal);

        mContentContainer.addView(row);
    }
}
