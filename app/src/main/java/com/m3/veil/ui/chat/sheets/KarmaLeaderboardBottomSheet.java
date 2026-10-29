package com.m3.veil.ui.chat.sheets;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;

import java.util.List;

/**
 * KarmaLeaderboardBottomSheet: Anonymous Reaction & Karma Leaderboard (#161).
 * Displays verified decentralized reputation scores, reaction metrics, and badges
 * with zero personal identifier exposure.
 */
public class KarmaLeaderboardBottomSheet extends BottomSheetDialogFragment {

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        VeilFeatureManager fm = VeilFeatureManager.getInstance(context);
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (22 * dp);
        int padV = (int) (16 * dp);
        root.setPadding(padH, padV, padH, padV * 2);

        // Handle
        View handle = new View(context);
        int handleW = (int) (36 * dp);
        int handleH = (int) (4 * dp);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(handleW, handleH);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (14 * dp);
        handle.setLayoutParams(handleLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Header Title
        TextView tvTitle = new TextView(context);
        tvTitle.setText("🏆 Anonymous Karma & Reaction Leaderboard");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        root.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("Decentralized reputation calculated from peer reactions, mesh forwarding, and community jury duty.");
        tvSub.setTextSize(13f);
        tvSub.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        tvSub.setPadding(0, (int) (4 * dp), 0, (int) (14 * dp));
        root.addView(tvSub);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        List<VeilFeatureManager.LeaderboardEntry> entries = fm.getKarmaLeaderboard();
        for (VeilFeatureManager.LeaderboardEntry entry : entries) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(entry.isCurrentUser ? R.drawable.bg_filter_chip : R.drawable.bg_reply_preview);
            row.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, (int) (8 * dp));
            row.setLayoutParams(rlp);

            // Rank
            TextView tvRank = new TextView(context);
            String rankEmoji = entry.rank == 1 ? "🥇" : (entry.rank == 2 ? "🥈" : (entry.rank == 3 ? "🥉" : "#" + entry.rank));
            tvRank.setText(rankEmoji);
            tvRank.setTextSize(16f);
            tvRank.setTypeface(null, Typeface.BOLD);
            tvRank.setTextColor(entry.rank <= 3 ? ContextCompat.getColor(context, R.color.veil_accent) : ContextCompat.getColor(context, R.color.textSecondary));
            tvRank.setPadding(0, 0, (int) (12 * dp), 0);
            row.addView(tvRank);

            // Details Col
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvAlias = new TextView(context);
            tvAlias.setText(entry.alias);
            tvAlias.setTextSize(14.5f);
            tvAlias.setTypeface(null, Typeface.BOLD);
            tvAlias.setTextColor(entry.isCurrentUser ? ContextCompat.getColor(context, R.color.veil_accent) : ContextCompat.getColor(context, R.color.textPrimary));
            col.addView(tvAlias);

            TextView tvDetails = new TextView(context);
            tvDetails.setText("LVL " + entry.level + " • " + entry.topBadge + " • ❤️ " + entry.reactionsReceived + " rx");
            tvDetails.setTextSize(12f);
            tvDetails.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            col.addView(tvDetails);
            row.addView(col);

            // Karma Badge
            TextView tvKarma = new TextView(context);
            tvKarma.setText("⭐ " + entry.karma);
            tvKarma.setTextSize(14f);
            tvKarma.setTypeface(null, Typeface.BOLD);
            tvKarma.setTextColor(ContextCompat.getColor(context, R.color.veil_accent));
            row.addView(tvKarma);

            list.addView(row);
        }

        scroll.addView(list);
        root.addView(scroll);
        return root;
    }
}
