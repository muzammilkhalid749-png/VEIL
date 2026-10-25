package com.m3.veil.ui.main.status;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.m3.veil.R;
import com.m3.veil.manager.StatusController;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.manager.VeilFeatureManager.StatusUpdate;
import com.m3.veil.manager.VeilFeatureManager.StatusViewer;
import com.m3.veil.webrtc.WebRtcManager;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WhatsApp / Telegram style Status (Updates) UI:
 * - Horizontal tray tiles with unread rings and photo previews
 * - Status Composer Chooser BottomSheet (Text status with font/color cyclers vs Photo status with caption)
 * - Full-screen viewer with segmented progress bars, tap-to-advance, and photo support
 * - "Who's Seen" tracking: bottom view pill with swipe-up & tap gesture opening Material 3 viewers sheet
 * - Realtime P2P & Firestore view receipt syncing
 */
public final class WhatsAppStatusUi {

    public interface StatusActionListener {
        void onReplyToStatus(StatusUpdate status, String replyText);
        void onStatusesChanged();
    }

    public interface OnPhotoStatusRequestedListener {
        void onPhotoStatusRequested();
    }

    private static final String[] BG_COLORS = {
            "#008069", "#4F46E5", "#BE123C", "#0F766E", "#7E22CE", "#C2410C", "#1E293B", "#0369A1"
    };
    private static final Typeface[] FONTS = {
            Typeface.create("sans-serif-medium", Typeface.NORMAL),
            Typeface.create(Typeface.SERIF, Typeface.BOLD),
            Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL),
            Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC),
            Typeface.create("cursive", Typeface.NORMAL)
    };

    private static final long SEGMENT_DURATION_MS = 5000L;

    private WhatsAppStatusUi() {}

    public static Typeface fontFor(int idx) {
        return FONTS[Math.abs(idx) % FONTS.length];
    }

    // =====================================================================
    // TRAY
    // =====================================================================

    public static void renderTray(Context ctx, LinearLayout container, List<StatusUpdate> all,
                                  StatusActionListener listener) {
        renderTray(ctx, container, all, listener, null);
    }

    /** Renders "My status" + grouped peer status tiles into the horizontal container. */
    public static void renderTray(Context ctx, LinearLayout container, List<StatusUpdate> all,
                                  StatusActionListener listener, OnPhotoStatusRequestedListener photoListener) {
        container.removeAllViews();
        float dp = ctx.getResources().getDisplayMetrics().density;

        List<StatusUpdate> mine = new ArrayList<>();
        Map<String, List<StatusUpdate>> byAuthor = new LinkedHashMap<>();
        for (StatusUpdate s : all) {
            if (s.isMine) {
                mine.add(s);
            } else {
                List<StatusUpdate> l = byAuthor.get(s.authorId);
                if (l == null) {
                    l = new ArrayList<>();
                    byAuthor.put(s.authorId, l);
                }
                l.add(s);
            }
        }

        // 1. My Status tile
        View myTile = buildTile(ctx, dp, "My status",
                mine.isEmpty() ? null : mine.get(0), true, !mine.isEmpty(), false);
        myTile.setOnClickListener(v -> {
            if (mine.isEmpty()) {
                showStatusCreationChooser(ctx, listener, photoListener);
            } else {
                showViewer(ctx, mine, listener);
            }
        });
        myTile.setOnLongClickListener(v -> {
            showStatusCreationChooser(ctx, listener, photoListener);
            return true;
        });
        container.addView(myTile);

        // 2. Peer tiles — unread first (like WhatsApp "Recent updates")
        List<List<StatusUpdate>> unread = new ArrayList<>();
        List<List<StatusUpdate>> seen = new ArrayList<>();
        for (List<StatusUpdate> group : byAuthor.values()) {
            boolean anyUnread = false;
            for (StatusUpdate s : group) if (!s.viewed) anyUnread = true;
            (anyUnread ? unread : seen).add(group);
        }
        List<List<StatusUpdate>> ordered = new ArrayList<>(unread);
        ordered.addAll(seen);

        for (List<StatusUpdate> group : ordered) {
            boolean anyUnread = false;
            for (StatusUpdate s : group) if (!s.viewed) anyUnread = true;
            StatusUpdate first = group.get(0);
            View tile = buildTile(ctx, dp, first.authorName, first, false, true, anyUnread);
            tile.setOnClickListener(v -> showViewer(ctx, group, listener));
            container.addView(tile);
        }
    }

    // =====================================================================
    // VERTICAL STATUS LIST (WhatsApp / Telegram Full Status Tab Parity)
    // =====================================================================

    /**
     * Renders WhatsApp / Telegram style full vertical Status tab:
     * - "My status" item with avatar, timestamp, viewer count, and 3-dots / view button
     * - "RECENT UPDATES" section with contacts who have unseen statuses
     * - "VIEWED UPDATES" section with contacts whose statuses have been viewed
     * - Empty card if no statuses exist
     */
    public static void renderVerticalStatusList(Context ctx, LinearLayout container, List<StatusUpdate> all,
                                                StatusActionListener listener, OnPhotoStatusRequestedListener photoListener,
                                                Runnable onAddStatusRequested) {
        if (ctx == null || container == null) return;
        container.removeAllViews();
        float dp = ctx.getResources().getDisplayMetrics().density;

        android.util.TypedValue outVal = new android.util.TypedValue();
        int selectableBgRes = ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                ? outVal.resourceId : 0;

        List<StatusUpdate> mine = new ArrayList<>();
        Map<String, List<StatusUpdate>> byAuthor = new LinkedHashMap<>();
        for (StatusUpdate s : all) {
            if (s.isMine) {
                mine.add(s);
            } else {
                List<StatusUpdate> l = byAuthor.get(s.authorId);
                if (l == null) {
                    l = new ArrayList<>();
                    byAuthor.put(s.authorId, l);
                }
                l.add(s);
            }
        }

        // 1. "My Status" Row
        boolean hasMine = !mine.isEmpty();
        StatusUpdate latestMine = hasMine ? mine.get(0) : null;

        LinearLayout myRow = new LinearLayout(ctx);
        myRow.setOrientation(LinearLayout.HORIZONTAL);
        myRow.setGravity(Gravity.CENTER_VERTICAL);
        myRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (76 * dp)));
        myRow.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        if (selectableBgRes != 0) myRow.setBackgroundResource(selectableBgRes);

        // Avatar Frame (52dp)
        FrameLayout avFrame = new FrameLayout(ctx);
        avFrame.setLayoutParams(new LinearLayout.LayoutParams((int) (52 * dp), (int) (52 * dp)));

        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(hasMine ? parseColor(latestMine.bgColorHex) : Color.parseColor("#334155"));
        ring.setStroke((int) (2.5f * dp), hasMine ? Color.parseColor("#25D366") : Color.TRANSPARENT);
        avFrame.setBackground(ring);
        avFrame.setClipToOutline(true);

        if (hasMine && latestMine.mediaUri != null && !latestMine.mediaUri.isEmpty()) {
            ImageView thumb = new ImageView(ctx);
            thumb.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avFrame.addView(thumb);
            loadStatusImage(ctx, latestMine.mediaUri, thumb);
        } else {
            TextView tvInitial = new TextView(ctx);
            tvInitial.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tvInitial.setGravity(Gravity.CENTER);
            String name = VeilFeatureManager.getInstance(ctx).getActivePersona().getDisplayName().replace("@", "").trim();
            tvInitial.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            tvInitial.setTextColor(Color.WHITE);
            tvInitial.setTextSize(18f);
            tvInitial.setTypeface(null, Typeface.BOLD);
            avFrame.addView(tvInitial);
        }

        if (!hasMine) {
            TextView plus = new TextView(ctx);
            FrameLayout.LayoutParams plLp = new FrameLayout.LayoutParams((int) (18 * dp), (int) (18 * dp), Gravity.BOTTOM | Gravity.END);
            plus.setLayoutParams(plLp);
            plus.setGravity(Gravity.CENTER);
            plus.setText("+");
            plus.setTextColor(Color.WHITE);
            plus.setTextSize(12f);
            plus.setTypeface(null, Typeface.BOLD);
            GradientDrawable plusBg = new GradientDrawable();
            plusBg.setShape(GradientDrawable.OVAL);
            plusBg.setColor(Color.parseColor("#25D366"));
            plusBg.setStroke((int) (1.5f * dp), Color.WHITE);
            plus.setBackground(plusBg);
            avFrame.addView(plus);
        }
        myRow.addView(avFrame);

        // Text details column
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcLp.leftMargin = (int) (16 * dp);
        textCol.setLayoutParams(tcLp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("My status");
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary));
        textCol.addView(tvTitle);

        TextView tvSubtitle = new TextView(ctx);
        tvSubtitle.setTextSize(13.5f);
        tvSubtitle.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary));
        if (hasMine) {
            CharSequence relTime = DateUtils.getRelativeTimeSpanString(latestMine.timestampMs,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            int vCount = (latestMine.viewers != null && !latestMine.viewers.isEmpty())
                    ? latestMine.viewers.size() : latestMine.viewCount;
            tvSubtitle.setText(relTime + "  •  👁 " + vCount + " view" + (vCount == 1 ? "" : "s"));
        } else {
            tvSubtitle.setText("Tap to add status update");
        }
        textCol.addView(tvSubtitle);
        myRow.addView(textCol);

        // Action buttons on right (Add another status & Viewers sheet)
        if (hasMine) {
            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);

            TextView btnAddMore = new TextView(ctx);
            btnAddMore.setLayoutParams(new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
            btnAddMore.setGravity(Gravity.CENTER);
            btnAddMore.setText("✏️");
            btnAddMore.setTextSize(16f);
            if (selectableBgRes != 0) btnAddMore.setBackgroundResource(selectableBgRes);
            btnAddMore.setOnClickListener(v -> showStatusCreationChooser(ctx, listener, photoListener));
            actions.addView(btnAddMore);

            TextView btnViewers = new TextView(ctx);
            btnViewers.setLayoutParams(new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
            btnViewers.setGravity(Gravity.CENTER);
            btnViewers.setText("👁");
            btnViewers.setTextSize(16f);
            if (selectableBgRes != 0) btnViewers.setBackgroundResource(selectableBgRes);
            btnViewers.setOnClickListener(v -> showStatusViewersBottomSheet(ctx, latestMine, listener, null, null));
            actions.addView(btnViewers);

            myRow.addView(actions);
        } else {
            TextView btnAdd = new TextView(ctx);
            btnAdd.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
            btnAdd.setGravity(Gravity.CENTER);
            btnAdd.setText("✏️");
            btnAdd.setTextSize(18f);
            if (selectableBgRes != 0) btnAdd.setBackgroundResource(selectableBgRes);
            btnAdd.setOnClickListener(v -> showStatusCreationChooser(ctx, listener, photoListener));
            myRow.addView(btnAdd);
        }

        myRow.setOnClickListener(v -> {
            if (hasMine) {
                showViewer(ctx, mine, listener);
            } else {
                showStatusCreationChooser(ctx, listener, photoListener);
            }
        });
        container.addView(myRow);

        // Divider
        View div = new View(ctx);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (0.8f * dp));
        divLp.topMargin = (int) (4 * dp);
        divLp.bottomMargin = (int) (4 * dp);
        div.setLayoutParams(divLp);
        div.setBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.colorOutline));
        container.addView(div);

        // 2. Separate Peer Updates into Unread vs Seen
        List<List<StatusUpdate>> unreadGroups = new ArrayList<>();
        List<List<StatusUpdate>> seenGroups = new ArrayList<>();
        for (List<StatusUpdate> group : byAuthor.values()) {
            boolean anyUnread = false;
            for (StatusUpdate s : group) if (!s.viewed) anyUnread = true;
            (anyUnread ? unreadGroups : seenGroups).add(group);
        }

        // 3. RECENT UPDATES SECTION
        if (!unreadGroups.isEmpty()) {
            TextView secHeader = new TextView(ctx);
            secHeader.setText("RECENT UPDATES");
            secHeader.setTextSize(12.5f);
            secHeader.setTypeface(null, Typeface.BOLD);
            secHeader.setTextColor(Color.parseColor("#008069"));
            secHeader.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (6 * dp));
            container.addView(secHeader);

            for (List<StatusUpdate> group : unreadGroups) {
                StatusUpdate first = group.get(0);
                container.addView(buildPeerStatusRow(ctx, dp, selectableBgRes, first, group, true, listener));
            }
        }

        // 4. VIEWED UPDATES SECTION
        if (!seenGroups.isEmpty()) {
            TextView secHeader = new TextView(ctx);
            secHeader.setText("VIEWED UPDATES");
            secHeader.setTextSize(12.5f);
            secHeader.setTypeface(null, Typeface.BOLD);
            secHeader.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary));
            secHeader.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (6 * dp));
            container.addView(secHeader);

            for (List<StatusUpdate> group : seenGroups) {
                StatusUpdate first = group.get(0);
                container.addView(buildPeerStatusRow(ctx, dp, selectableBgRes, first, group, false, listener));
            }
        }

        // 5. Empty State (if no contact updates)
        if (unreadGroups.isEmpty() && seenGroups.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(ctx);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding((int) (24 * dp), (int) (40 * dp), (int) (24 * dp), (int) (40 * dp));

            TextView tvEmptyIcon = new TextView(ctx);
            tvEmptyIcon.setText("🟢");
            tvEmptyIcon.setTextSize(38f);
            tvEmptyIcon.setGravity(Gravity.CENTER);
            emptyCard.addView(tvEmptyIcon);

            TextView tvEmptyH = new TextView(ctx);
            tvEmptyH.setText("No recent updates");
            tvEmptyH.setTextSize(17f);
            tvEmptyH.setTypeface(null, Typeface.BOLD);
            tvEmptyH.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary));
            tvEmptyH.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ehLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ehLp.topMargin = (int) (12 * dp);
            tvEmptyH.setLayoutParams(ehLp);
            emptyCard.addView(tvEmptyH);

            TextView tvEmptySub = new TextView(ctx);
            tvEmptySub.setText("Encrypted status updates from your contacts will appear here. Updates automatically disappear after 24 hours.");
            tvEmptySub.setTextSize(13.5f);
            tvEmptySub.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary));
            tvEmptySub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            esLp.topMargin = (int) (6 * dp);
            tvEmptySub.setLayoutParams(esLp);
            emptyCard.addView(tvEmptySub);

            com.google.android.material.button.MaterialButton btnCreate =
                    new com.google.android.material.button.MaterialButton(ctx);
            btnCreate.setText("Post a Status Update");
            btnCreate.setCornerRadius((int) (22 * dp));
            btnCreate.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#008069")));
            LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * dp));
            bcLp.topMargin = (int) (18 * dp);
            btnCreate.setLayoutParams(bcLp);
            btnCreate.setOnClickListener(v -> {
                if (onAddStatusRequested != null) onAddStatusRequested.run();
                else showStatusCreationChooser(ctx, listener, photoListener);
            });
            emptyCard.addView(btnCreate);

            container.addView(emptyCard);
        }
    }

    private static View buildPeerStatusRow(Context ctx, float dp, int selectableBgRes,
                                          StatusUpdate first, List<StatusUpdate> group,
                                          boolean isUnread, StatusActionListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (70 * dp)));
        row.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
        if (selectableBgRes != 0) row.setBackgroundResource(selectableBgRes);

        // Avatar Frame (50dp) with ring
        FrameLayout avFrame = new FrameLayout(ctx);
        avFrame.setLayoutParams(new LinearLayout.LayoutParams((int) (50 * dp), (int) (50 * dp)));

        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(parseColor(first.bgColorHex));
        int ringColor = isUnread ? Color.parseColor("#25D366") : Color.parseColor("#78909C");
        ring.setStroke((int) (2.5f * dp), ringColor);
        avFrame.setBackground(ring);
        avFrame.setClipToOutline(true);

        if (first.mediaUri != null && !first.mediaUri.isEmpty()) {
            ImageView thumb = new ImageView(ctx);
            thumb.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avFrame.addView(thumb);
            loadStatusImage(ctx, first.mediaUri, thumb);
        } else {
            TextView tvInitial = new TextView(ctx);
            tvInitial.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tvInitial.setGravity(Gravity.CENTER);
            String name = first.authorName.replace("@", "").trim();
            tvInitial.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            tvInitial.setTextColor(Color.WHITE);
            tvInitial.setTextSize(17f);
            tvInitial.setTypeface(null, Typeface.BOLD);
            avFrame.addView(tvInitial);
        }
        row.addView(avFrame);

        // Details column
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = (int) (16 * dp);
        col.setLayoutParams(colLp);

        TextView tvName = new TextView(ctx);
        tvName.setText(first.authorName);
        tvName.setTextSize(15.5f);
        tvName.setTypeface(null, isUnread ? Typeface.BOLD : Typeface.NORMAL);
        tvName.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textPrimary));
        col.addView(tvName);

        TextView tvTime = new TextView(ctx);
        tvTime.setText(DateUtils.getRelativeTimeSpanString(first.timestampMs,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        tvTime.setTextSize(13f);
        tvTime.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary));
        col.addView(tvTime);

        row.addView(col);

        row.setOnClickListener(v -> showViewer(ctx, group, listener));
        return row;
    }

    private static View buildTile(Context ctx, float dp, String name, StatusUpdate preview,
                                  boolean isMine, boolean hasStatus, boolean unread) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams((int) (86 * dp), (int) (132 * dp));
        tileLp.rightMargin = (int) (8 * dp);
        tile.setLayoutParams(tileLp);

        FrameLayout card = new FrameLayout(ctx);
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(14 * dp);
        if (preview != null) {
            cardBg.setColor(parseColor(preview.bgColorHex));
        } else {
            cardBg.setColor(Color.parseColor("#E2E8F0"));
        }
        card.setBackground(cardBg);
        card.setClipToOutline(true);

        // Photo thumbnail if mediaUri exists
        if (preview != null && preview.mediaUri != null && !preview.mediaUri.isEmpty()) {
            ImageView thumb = new ImageView(ctx);
            thumb.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(thumb);
            loadStatusImage(ctx, preview.mediaUri, thumb);
        } else if (preview != null) {
            // Text preview
            TextView tvPreview = new TextView(ctx);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            pLp.setMargins((int) (6 * dp), 0, (int) (6 * dp), 0);
            tvPreview.setLayoutParams(pLp);
            tvPreview.setGravity(Gravity.CENTER);
            tvPreview.setText(preview.contentText);
            tvPreview.setTextColor(Color.WHITE);
            tvPreview.setTextSize(9.5f);
            tvPreview.setMaxLines(4);
            tvPreview.setTypeface(fontFor(preview.fontStyleIndex));
            card.addView(tvPreview);
        }

        // bottom gradient overlay
        View overlay = new View(ctx);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * dp), Gravity.BOTTOM));
        GradientDrawable ov = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#CC000000"), Color.TRANSPARENT});
        overlay.setBackground(ov);
        card.addView(overlay);

        // wds_profile_picture with ring
        FrameLayout avatarFrame = new FrameLayout(ctx);
        FrameLayout.LayoutParams avLp = new FrameLayout.LayoutParams((int) (36 * dp), (int) (36 * dp), Gravity.TOP | Gravity.START);
        avLp.setMargins((int) (6 * dp), (int) (6 * dp), 0, 0);
        avatarFrame.setLayoutParams(avLp);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.parseColor("#008069"));
        int ringColor = isMine ? (hasStatus ? Color.parseColor("#25D366") : Color.WHITE)
                : (unread ? Color.parseColor("#25D366") : Color.parseColor("#B0BEC5"));
        ring.setStroke((int) (2.5f * dp), ringColor);
        avatarFrame.setBackground(ring);

        TextView initial = new TextView(ctx);
        initial.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        initial.setGravity(Gravity.CENTER);
        String clean = name.replace("@", "").trim();
        initial.setText(clean.isEmpty() ? "?" : clean.substring(0, 1).toUpperCase(Locale.ROOT));
        initial.setTextColor(Color.WHITE);
        initial.setTypeface(null, Typeface.BOLD);
        initial.setTextSize(14f);
        avatarFrame.addView(initial);

        if (isMine) {
            TextView plus = new TextView(ctx);
            FrameLayout.LayoutParams plLp = new FrameLayout.LayoutParams((int) (16 * dp), (int) (16 * dp), Gravity.BOTTOM | Gravity.END);
            plus.setLayoutParams(plLp);
            plus.setGravity(Gravity.CENTER);
            plus.setText("+");
            plus.setTextColor(Color.WHITE);
            plus.setTextSize(11f);
            plus.setTypeface(null, Typeface.BOLD);
            GradientDrawable plusBg = new GradientDrawable();
            plusBg.setShape(GradientDrawable.OVAL);
            plusBg.setColor(Color.parseColor("#25D366"));
            plusBg.setStroke((int) (1.5f * dp), Color.WHITE);
            plus.setBackground(plusBg);
            avatarFrame.addView(plus);
        }
        card.addView(avatarFrame);

        // contact_name
        TextView tvName = new TextView(ctx);
        FrameLayout.LayoutParams nLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        nLp.setMargins((int) (7 * dp), 0, (int) (7 * dp), (int) (7 * dp));
        tvName.setLayoutParams(nLp);
        tvName.setText(name);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(11.5f);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setMaxLines(2);
        card.addView(tvName);

        tile.addView(card);
        return tile;
    }

    // =====================================================================
    // COMPOSER CHOOSER & COMPOSERS
    // =====================================================================

    public static void showStatusCreationChooser(Context ctx, StatusActionListener listener,
                                                OnPhotoStatusRequestedListener photoListener) {
        if (ctx == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        sheet.setContentView(R.layout.dialog_status_composer_choice);

        View cardText = sheet.findViewById(R.id.cardTextStatusOption);
        View cardPhoto = sheet.findViewById(R.id.cardPhotoStatusOption);

        if (cardText != null) {
            cardText.setOnClickListener(v -> {
                sheet.dismiss();
                showTextStatusComposer(ctx, listener);
            });
        }

        if (cardPhoto != null) {
            cardPhoto.setOnClickListener(v -> {
                sheet.dismiss();
                if (photoListener != null) {
                    photoListener.onPhotoStatusRequested();
                } else {
                    showTextStatusComposer(ctx, listener);
                }
            });
        }

        sheet.show();
    }

    public static void showTextStatusComposer(Context ctx, StatusActionListener listener) {
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        float dp = ctx.getResources().getDisplayMetrics().density;
        final int[] colorIdx = {0};
        final int[] fontIdx = {0};

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(parseColor(BG_COLORS[0]));

        EditText input = new EditText(ctx);
        FrameLayout.LayoutParams inLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        inLp.setMargins((int) (24 * dp), 0, (int) (24 * dp), 0);
        input.setLayoutParams(inLp);
        input.setBackground(null);
        input.setGravity(Gravity.CENTER);
        input.setHint("Type a status");
        input.setHintTextColor(Color.parseColor("#AAFFFFFF"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(30f);
        input.setTypeface(FONTS[0]);
        input.setMaxLines(8);
        root.addView(input);

        // Top bar: close | font (T) | palette
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topBar.setLayoutParams(tbLp);
        topBar.setPadding((int) (12 * dp), (int) (36 * dp), (int) (12 * dp), 0);

        TextView btnClose = circleButton(ctx, dp, "✕");
        btnClose.setOnClickListener(v -> dialog.dismiss());
        topBar.addView(btnClose);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topBar.addView(spacer);

        TextView btnFont = circleButton(ctx, dp, "T");
        btnFont.setOnClickListener(v -> {
            fontIdx[0] = (fontIdx[0] + 1) % FONTS.length;
            input.setTypeface(FONTS[fontIdx[0]]);
        });
        topBar.addView(btnFont);

        TextView btnPalette = circleButton(ctx, dp, "🎨");
        btnPalette.setOnClickListener(v -> {
            colorIdx[0] = (colorIdx[0] + 1) % BG_COLORS.length;
            root.setBackgroundColor(parseColor(BG_COLORS[colorIdx[0]]));
        });
        topBar.addView(btnPalette);
        root.addView(topBar);

        // Bottom: privacy hint + send FAB
        LinearLayout bottomBar = new LinearLayout(ctx);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams bbLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomBar.setLayoutParams(bbLp);
        bottomBar.setPadding((int) (16 * dp), 0, (int) (16 * dp), (int) (28 * dp));

        TextView hint = new TextView(ctx);
        hint.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hint.setText("🔒 Status (contacts) · disappears after 24 hours");
        hint.setTextColor(Color.parseColor("#DDFFFFFF"));
        hint.setTextSize(12.5f);
        bottomBar.addView(hint);

        TextView btnSend = new TextView(ctx);
        btnSend.setLayoutParams(new LinearLayout.LayoutParams((int) (54 * dp), (int) (54 * dp)));
        btnSend.setGravity(Gravity.CENTER);
        btnSend.setText("➤");
        btnSend.setTextColor(Color.WHITE);
        btnSend.setTextSize(20f);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(Color.parseColor("#25D366"));
        btnSend.setBackground(sendBg);
        btnSend.setElevation(4 * dp);
        btnSend.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(ctx, "Type something to post a status", Toast.LENGTH_SHORT).show();
                return;
            }
            StatusController.getInstance(ctx).postTextStatus(text, BG_COLORS[colorIdx[0]], fontIdx[0]);
            VeilFeatureManager.getInstance(ctx).addMyStatus(text, BG_COLORS[colorIdx[0]], fontIdx[0]);
            Toast.makeText(ctx, "✅ Status posted · visible for 24h", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (listener != null) listener.onStatusesChanged();
        });
        bottomBar.addView(btnSend);
        root.addView(bottomBar);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    // =====================================================================
    // VIEWER & "WHO'S SEEN"
    // =====================================================================

    public static void showViewer(Context ctx, List<StatusUpdate> statuses, StatusActionListener listener) {
        if (statuses == null || statuses.isEmpty()) return;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        float dp = ctx.getResources().getDisplayMetrics().density;
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] index = {0};
        final long[] segmentStart = {0};
        final long[] pausedElapsed = {0};
        final boolean[] paused = {false};
        final boolean isMineGroup = statuses.get(0).isMine;

        FrameLayout root = new FrameLayout(ctx);

        // Photo / Video View behind text
        ImageView photoView = new ImageView(ctx);
        photoView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoView.setVisibility(View.GONE);
        root.addView(photoView);

        // Text content
        TextView content = new TextView(ctx);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cLp.setMargins((int) (24 * dp), 0, (int) (24 * dp), 0);
        content.setLayoutParams(cLp);
        content.setGravity(Gravity.CENTER);
        content.setTextColor(Color.WHITE);
        content.setTextSize(28f);
        root.addView(content);

        // Top: segmented progress bars + header
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        top.setPadding((int) (8 * dp), (int) (30 * dp), (int) (8 * dp), 0);

        LinearLayout segments = new LinearLayout(ctx);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        final List<ProgressBar> bars = new ArrayList<>();
        final Runnable[] onDeleteSegmentHolder = new Runnable[1];
        for (int i = 0; i < statuses.size(); i++) {
            ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, (int) (2.5f * dp), 1f);
            pLp.setMargins((int) (2 * dp), 0, (int) (2 * dp), 0);
            pb.setLayoutParams(pLp);
            pb.setMax(1000);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#66FFFFFF")));
            bars.add(pb);
            segments.addView(pb);
        }
        top.addView(segments);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (4 * dp), (int) (10 * dp), (int) (4 * dp), 0);

        TextView back = new TextView(ctx);
        back.setText("←");
        back.setTextColor(Color.WHITE);
        back.setTextSize(22f);
        back.setPadding((int) (4 * dp), 0, (int) (10 * dp), 0);
        back.setOnClickListener(v -> dialog.dismiss());
        header.addView(back);

        TextView avatar = new TextView(ctx);
        avatar.setLayoutParams(new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp)));
        avatar.setGravity(Gravity.CENTER);
        String nm = statuses.get(0).authorName.replace("@", "").trim();
        avatar.setText(nm.isEmpty() ? "?" : nm.substring(0, 1).toUpperCase(Locale.ROOT));
        avatar.setTextColor(Color.WHITE);
        avatar.setTypeface(null, Typeface.BOLD);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(Color.parseColor("#33FFFFFF"));
        avatar.setBackground(avBg);
        header.addView(avatar);

        LinearLayout nameCol = new LinearLayout(ctx);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ncLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ncLp.leftMargin = (int) (10 * dp);
        nameCol.setLayoutParams(ncLp);
        TextView tvAuthor = new TextView(ctx);
        tvAuthor.setText(isMineGroup ? "My status" : statuses.get(0).authorName);
        tvAuthor.setTextColor(Color.WHITE);
        tvAuthor.setTextSize(16f);
        tvAuthor.setTypeface(null, Typeface.BOLD);
        nameCol.addView(tvAuthor);
        TextView tvTime = new TextView(ctx);
        tvTime.setTextColor(Color.parseColor("#DDFFFFFF"));
        tvTime.setTextSize(12.5f);
        nameCol.addView(tvTime);
        header.addView(nameCol);

        if (isMineGroup) {
            TextView btnDelete = new TextView(ctx);
            btnDelete.setText("🗑");
            btnDelete.setTextSize(18f);
            btnDelete.setPadding((int) (10 * dp), 0, (int) (6 * dp), 0);
            btnDelete.setOnClickListener(v -> {
                if (onDeleteSegmentHolder[0] != null) onDeleteSegmentHolder[0].run();
                Toast.makeText(ctx, "Status deleted", Toast.LENGTH_SHORT).show();
            });
            header.addView(btnDelete);
        }
        top.addView(header);
        root.addView(top);

        // Bottom: view counter pill (mine) OR Reply & Quick Reactions bar (peer)
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        bottom.setPadding((int) (12 * dp), 0, (int) (12 * dp), (int) (26 * dp));

        final TextView tvViews = new TextView(ctx);
        final EditText replyInput = new EditText(ctx);

        if (isMineGroup) {
            LinearLayout viewsLayout = new LinearLayout(ctx);
            viewsLayout.setOrientation(LinearLayout.VERTICAL);
            viewsLayout.setGravity(Gravity.CENTER);
            viewsLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            viewsLayout.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));

            TextView chevron = new TextView(ctx);
            chevron.setText("▲");
            chevron.setTextColor(Color.WHITE);
            chevron.setTextSize(12f);
            chevron.setGravity(Gravity.CENTER);
            viewsLayout.addView(chevron);

            tvViews.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tvViews.setGravity(Gravity.CENTER);
            tvViews.setTextColor(Color.WHITE);
            tvViews.setTextSize(15f);
            tvViews.setTypeface(null, Typeface.BOLD);
            viewsLayout.addView(tvViews);

            TextView hint = new TextView(ctx);
            hint.setText("Swipe up or tap to see who viewed");
            hint.setTextColor(Color.parseColor("#CCFFFFFF"));
            hint.setTextSize(11.5f);
            hint.setGravity(Gravity.CENTER);
            viewsLayout.addView(hint);

            viewsLayout.setOnClickListener(v -> {
                paused[0] = true;
                pausedElapsed[0] = System.currentTimeMillis() - segmentStart[0];
                showStatusViewersBottomSheet(ctx, statuses.get(index[0]), listener, () -> {
                    paused[0] = false;
                    segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                }, dialog, onDeleteSegmentHolder[0]);
            });

            bottom.addView(viewsLayout);
        } else {
            // Telegram & WhatsApp style Quick Emoji Reactions Bar
            LinearLayout reactionsBar = new LinearLayout(ctx);
            reactionsBar.setOrientation(LinearLayout.HORIZONTAL);
            reactionsBar.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rbLp.bottomMargin = (int) (10 * dp);
            reactionsBar.setLayoutParams(rbLp);
            GradientDrawable rbBg = new GradientDrawable();
            rbBg.setColor(Color.parseColor("#55000000"));
            rbBg.setCornerRadius(20 * dp);
            rbBg.setStroke((int) (1 * dp), Color.parseColor("#33FFFFFF"));
            reactionsBar.setBackground(rbBg);
            reactionsBar.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));

            String[] quickEmojis = {"🔥", "❤️", "😂", "😮", "😢", "👏"};
            for (String emoji : quickEmojis) {
                TextView emojiBtn = new TextView(ctx);
                LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (36 * dp));
                ebLp.setMargins((int) (2 * dp), 0, (int) (2 * dp), 0);
                emojiBtn.setLayoutParams(ebLp);
                emojiBtn.setGravity(Gravity.CENTER);
                emojiBtn.setText(emoji);
                emojiBtn.setTextSize(20f);
                emojiBtn.setOnClickListener(v -> {
                    StatusUpdate current = statuses.get(index[0]);
                    dialog.dismiss();
                    if (listener != null) listener.onReplyToStatus(current, emoji);
                    Toast.makeText(ctx, "Sent " + emoji + " to " + current.authorName, Toast.LENGTH_SHORT).show();
                });
                reactionsBar.addView(emojiBtn);
            }
            bottom.addView(reactionsBar);

            // Input Row
            LinearLayout inputRow = new LinearLayout(ctx);
            inputRow.setOrientation(LinearLayout.HORIZONTAL);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            inputRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            replyInput.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (46 * dp), 1f));
            replyInput.setHint("Reply");
            replyInput.setHintTextColor(Color.parseColor("#BBFFFFFF"));
            replyInput.setTextColor(Color.WHITE);
            replyInput.setSingleLine(true);
            replyInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
            replyInput.setPadding((int) (18 * dp), 0, (int) (18 * dp), 0);
            GradientDrawable rBg = new GradientDrawable();
            rBg.setCornerRadius(23 * dp);
            rBg.setColor(Color.parseColor("#55000000"));
            rBg.setStroke((int) (1 * dp), Color.parseColor("#66FFFFFF"));
            replyInput.setBackground(rBg);
            inputRow.addView(replyInput);

            TextView btnReply = new TextView(ctx);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams((int) (46 * dp), (int) (46 * dp));
            brLp.leftMargin = (int) (8 * dp);
            btnReply.setLayoutParams(brLp);
            btnReply.setGravity(Gravity.CENTER);
            btnReply.setText("➤");
            btnReply.setTextColor(Color.WHITE);
            btnReply.setTextSize(18f);
            GradientDrawable sBg = new GradientDrawable();
            sBg.setShape(GradientDrawable.OVAL);
            sBg.setColor(Color.parseColor("#25D366"));
            btnReply.setBackground(sBg);
            inputRow.addView(btnReply);

            bottom.addView(inputRow);

            View.OnClickListener sendReply = v -> {
                String txt = replyInput.getText().toString().trim();
                if (txt.isEmpty()) return;
                StatusUpdate current = statuses.get(index[0]);
                dialog.dismiss();
                if (listener != null) listener.onReplyToStatus(current, txt);
            };
            btnReply.setOnClickListener(sendReply);
            replyInput.setOnEditorActionListener((tv, actionId, ev) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendReply.onClick(tv);
                    return true;
                }
                return false;
            });
            replyInput.setOnFocusChangeListener((v, hasFocus) -> {
                paused[0] = hasFocus;
                if (hasFocus) {
                    pausedElapsed[0] = System.currentTimeMillis() - segmentStart[0];
                } else {
                    segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                }
            });
        }
        root.addView(bottom);

        final Runnable[] showIndex = new Runnable[1];
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;
                if (!paused[0]) {
                    long elapsed = System.currentTimeMillis() - segmentStart[0];
                    int prog = (int) Math.min(1000, elapsed * 1000 / SEGMENT_DURATION_MS);
                    bars.get(index[0]).setProgress(prog);
                    if (elapsed >= SEGMENT_DURATION_MS) {
                        if (index[0] < statuses.size() - 1) {
                            index[0]++;
                            showIndex[0].run();
                        } else {
                            dialog.dismiss();
                            return;
                        }
                    }
                }
                handler.postDelayed(this, 40);
            }
        };

        showIndex[0] = () -> {
            StatusUpdate s = statuses.get(index[0]);

            if (s.mediaUri != null && !s.mediaUri.isEmpty()) {
                photoView.setVisibility(View.VISIBLE);
                root.setBackgroundColor(Color.BLACK);
                loadStatusImage(ctx, s.mediaUri, photoView);
                if (s.contentText != null && !s.contentText.isEmpty()) {
                    content.setVisibility(View.VISIBLE);
                    content.setText(s.contentText);
                    content.setTextSize(17f);
                    content.setGravity(Gravity.CENTER);
                    FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                    textLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (90 * dp));
                    content.setLayoutParams(textLp);
                    content.setBackgroundColor(Color.parseColor("#99000000"));
                    content.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
                } else {
                    content.setVisibility(View.GONE);
                }
            } else {
                photoView.setVisibility(View.GONE);
                root.setBackgroundColor(parseColor(s.bgColorHex));
                content.setVisibility(View.VISIBLE);
                content.setText(s.contentText);
                content.setTextSize(28f);
                content.setBackground(null);
                FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                textLp.setMargins((int) (24 * dp), 0, (int) (24 * dp), 0);
                content.setLayoutParams(textLp);
                content.setTypeface(fontFor(s.fontStyleIndex));
            }

            tvTime.setText(DateUtils.getRelativeTimeSpanString(s.timestampMs,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            for (int i = 0; i < bars.size(); i++) {
                bars.get(i).setProgress(i < index[0] ? 1000 : 0);
            }
            if (isMineGroup) {
                int totalV = (s.viewers != null && !s.viewers.isEmpty()) ? s.viewers.size() : s.viewCount;
                tvViews.setText("👁  " + totalV + " views");
            }
            if (!s.isMine) {
                s.viewed = true;
                // StatusController honors StealthView mode and suppresses receipt if active
                StatusController.getInstance(ctx).markStatusViewed(s.id);
                VeilFeatureManager.getInstance(ctx).markStatusViewed(s.id);
            }
            segmentStart[0] = System.currentTimeMillis();
            pausedElapsed[0] = 0;
        };

        onDeleteSegmentHolder[0] = () -> {
            int removedIdx = index[0];
            if (removedIdx < statuses.size()) {
                String delId = statuses.get(removedIdx).id;
                StatusController.getInstance(ctx).deleteStatus(delId);
                VeilFeatureManager.getInstance(ctx).deleteMyStatus(delId);
                statuses.remove(removedIdx);
            }
            if (statuses.isEmpty()) {
                dialog.dismiss();
            } else {
                if (index[0] >= statuses.size()) {
                    index[0] = statuses.size() - 1;
                }
                if (removedIdx < bars.size()) {
                    segments.removeView(bars.get(removedIdx));
                    bars.remove(removedIdx);
                }
                for (int b = 0; b < bars.size(); b++) {
                    bars.get(b).setProgress(b < index[0] ? 1000 : 0);
                }
                showIndex[0].run();
                paused[0] = false;
                segmentStart[0] = System.currentTimeMillis();
            }
            if (listener != null) listener.onStatusesChanged();
        };

        // Tap left/right to navigate, hold to pause, swipe up to view seen receipts
        final long[] downTime = {0};
        final float[] startY = {0};
        root.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downTime[0] = System.currentTimeMillis();
                    startY[0] = event.getY();
                    paused[0] = true;
                    pausedElapsed[0] = System.currentTimeMillis() - segmentStart[0];
                    return true;
                case MotionEvent.ACTION_UP:
                    float dy = event.getY() - startY[0];
                    if (isMineGroup && dy < -100 && (System.currentTimeMillis() - downTime[0] < 600)) {
                        // Upward swipe -> open viewers sheet!
                        showStatusViewersBottomSheet(ctx, statuses.get(index[0]), listener, () -> {
                            paused[0] = false;
                            segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                        }, dialog, onDeleteSegmentHolder[0]);
                        return true;
                    }
                    paused[0] = false;
                    segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                    if (System.currentTimeMillis() - downTime[0] < 250) {
                        boolean leftSide = event.getX() < v.getWidth() / 3f;
                        if (leftSide) {
                            if (index[0] > 0) index[0]--;
                            showIndex[0].run();
                        } else if (index[0] < statuses.size() - 1) {
                            index[0]++;
                            showIndex[0].run();
                        } else {
                            dialog.dismiss();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    paused[0] = false;
                    segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                    return true;
            }
            return false;
        });

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            if (listener != null) listener.onStatusesChanged();
        });
        dialog.show();
        showIndex[0].run();
        handler.post(ticker);
    }

    public static void showStatusViewersBottomSheet(Context ctx, StatusUpdate status,
                                                   StatusActionListener listener,
                                                   Runnable onDismissCallback,
                                                   Dialog parentDialog) {
        showStatusViewersBottomSheet(ctx, status, listener, onDismissCallback, parentDialog, null);
    }

    public static void showStatusViewersBottomSheet(Context ctx, StatusUpdate status,
                                                   StatusActionListener listener,
                                                   Runnable onDismissCallback,
                                                   Dialog parentDialog,
                                                   Runnable onDeleteSegmentCallback) {
        if (ctx == null || status == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        sheet.setContentView(R.layout.dialog_status_viewers_sheet);

        TextView tvTitle = sheet.findViewById(R.id.tvStatusViewersTitle);
        TextView tvSubtitle = sheet.findViewById(R.id.tvStatusViewersSubtitle);
        ImageView btnShare = sheet.findViewById(R.id.btnShareStatusSheet);
        ImageView btnDelete = sheet.findViewById(R.id.btnDeleteStatusSheet);
        View emptyState = sheet.findViewById(R.id.statusViewersEmptyState);
        LinearLayout container = sheet.findViewById(R.id.statusViewersContainer);

        // Fetch latest fresh status object to include recent real-time views
        StatusUpdate currentStatus = status;
        List<StatusUpdate> freshList = VeilFeatureManager.getInstance(ctx).getStatusUpdates(null);
        if (freshList != null) {
            for (StatusUpdate su : freshList) {
                if (su.id != null && su.id.equals(status.id)) {
                    currentStatus = su;
                    break;
                }
            }
        }

        List<StatusViewer> viewers = currentStatus.viewers;
        renderViewersList(ctx, container, emptyState, tvTitle, tvSubtitle, viewers, sheet, parentDialog);

        // Realtime local P2P mesh listener for live "Who's Seen" updates (ZERO cloud storage)
        final VeilFeatureManager.StatusChangeListener liveListener = () -> {
            List<StatusUpdate> liveList = VeilFeatureManager.getInstance(ctx).getStatusUpdates(null);
            if (liveList != null) {
                for (StatusUpdate su : liveList) {
                    if (su.id != null && su.id.equals(status.id)) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (sheet.isShowing()) {
                                renderViewersList(ctx, container, emptyState, tvTitle, tvSubtitle, su.viewers, sheet, parentDialog);
                            }
                        });
                        break;
                    }
                }
            }
        };
        VeilFeatureManager.getInstance(ctx).addStatusChangeListener(liveListener);

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, "VeilChat Status: " + status.contentText);
                ctx.startActivity(android.content.Intent.createChooser(shareIntent, "Share Status Update"));
            });
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                VeilFeatureManager.getInstance(ctx).deleteMyStatus(status.id);
                Toast.makeText(ctx, "Status deleted", Toast.LENGTH_SHORT).show();
                sheet.dismiss();
                if (onDeleteSegmentCallback != null) {
                    onDeleteSegmentCallback.run();
                } else if (parentDialog != null && parentDialog.isShowing()) {
                    parentDialog.dismiss();
                }
                if (listener != null) listener.onStatusesChanged();
            });
        }

        sheet.setOnDismissListener(dialog -> {
            VeilFeatureManager.getInstance(ctx).removeStatusChangeListener(liveListener);
            if (onDismissCallback != null) onDismissCallback.run();
        });

        sheet.show();
    }

    private static void renderViewersList(Context ctx, LinearLayout container, View emptyState,
                                          TextView tvTitle, TextView tvSubtitle, List<StatusViewer> viewers,
                                          BottomSheetDialog sheet, Dialog parentDialog) {
        int count = (viewers != null && !viewers.isEmpty()) ? viewers.size() : 0;
        if (tvTitle != null) {
            tvTitle.setText("👁 Viewed by " + count);
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText(count == 1 ? "1 contact viewed this update" : count + " contacts viewed this update");
        }

        if (container != null && emptyState != null) {
            container.removeAllViews();
            if (viewers == null || viewers.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                container.setVisibility(View.GONE);
            } else {
                emptyState.setVisibility(View.GONE);
                container.setVisibility(View.VISIBLE);
                LayoutInflater inflater = LayoutInflater.from(ctx);
                for (StatusViewer viewer : viewers) {
                    View row = inflater.inflate(R.layout.item_status_viewer, container, false);
                    TextView tvInitial = row.findViewById(R.id.tvViewerInitial);
                    TextView tvName = row.findViewById(R.id.tvViewerName);
                    TextView tvTime = row.findViewById(R.id.tvViewerTime);
                    FrameLayout avFrame = row.findViewById(R.id.viewerAvatarFrame);

                    if (tvName != null) tvName.setText(viewer.displayName != null ? viewer.displayName : "Anonymous Peer");
                    if (tvTime != null) {
                        tvTime.setText(DateUtils.getRelativeTimeSpanString(viewer.viewedAtMs,
                                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
                    }
                    if (tvInitial != null) {
                        String clean = (viewer.displayName != null ? viewer.displayName : "?").replace("@", "").trim();
                        tvInitial.setText(clean.isEmpty() ? "?" : clean.substring(0, 1).toUpperCase(Locale.ROOT));
                    }
                    if (avFrame != null) {
                        GradientDrawable ring = new GradientDrawable();
                        ring.setShape(GradientDrawable.OVAL);
                        ring.setColor(Color.parseColor("#4F46E5"));
                        avFrame.setBackground(ring);
                    }

                    // Tapping a viewer in the list opens a direct 1:1 chat with them (Telegram/WhatsApp style)
                    row.setOnClickListener(v -> {
                        if (viewer.userId != null && !viewer.userId.isEmpty() && !"me".equals(viewer.userId)) {
                            sheet.dismiss();
                            if (parentDialog != null && parentDialog.isShowing()) {
                                parentDialog.dismiss();
                            }
                            android.content.Intent intent = new android.content.Intent(ctx, com.m3.veil.ui.chat.ChatActivity.class);
                            intent.putExtra("recipientId", viewer.userId);
                            intent.putExtra("recipientName", viewer.displayName);
                            ctx.startActivity(intent);
                        }
                    });

                    container.addView(row);
                }
            }
        }
    }

    private static void loadStatusImage(Context ctx, String uriString, ImageView target) {
        if (ctx == null || uriString == null || target == null) return;
        new Thread(() -> {
            try {
                Bitmap bmp = null;
                if (uriString.startsWith("data:image") || (uriString.length() > 500 && !uriString.contains("://"))) {
                    String base64Data = uriString.contains(",") ? uriString.substring(uriString.indexOf(",") + 1) : uriString;
                    byte[] decoded = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 2;
                    bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length, opts);
                } else if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                    java.net.URL url = new java.net.URL(uriString);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    try (InputStream is = conn.getInputStream()) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        bmp = BitmapFactory.decodeStream(is, null, opts);
                    }
                } else {
                    Uri uri = Uri.parse(uriString);
                    if ("content".equalsIgnoreCase(uri.getScheme())) {
                        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = 2;
                            bmp = BitmapFactory.decodeStream(is, null, opts);
                        }
                    } else {
                        String path = "file".equalsIgnoreCase(uri.getScheme()) ? uri.getPath() : uriString;
                        File file = new File(path != null ? path : uriString);
                        if (file.exists()) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = 2;
                            bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                        }
                    }
                }
                if (bmp != null) {
                    final Bitmap finalBmp = bmp;
                    new Handler(Looper.getMainLooper()).post(() -> target.setImageBitmap(finalBmp));
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private static TextView circleButton(Context ctx, float dp, String label) {
        TextView b = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp));
        lp.leftMargin = (int) (8 * dp);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17f);
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#33000000"));
        b.setBackground(bg);
        return b;
    }

    private static int parseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return Color.parseColor("#008069");
        }
    }
}
