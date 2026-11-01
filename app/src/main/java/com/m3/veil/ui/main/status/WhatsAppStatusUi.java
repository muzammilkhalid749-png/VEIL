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
import android.util.Log;
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

import androidx.core.content.ContextCompat;
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
        VeilFeatureManager vfm = VeilFeatureManager.getInstance(ctx);
        if (all != null) {
            for (StatusUpdate s : all) {
                if (s == null || vfm.isDeletedStatusId(s.id)) continue;
                s.contentText = StatusController.sanitizeStatusText(s.contentText);
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
        }
        java.util.Collections.sort(mine, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        for (List<StatusUpdate> group : byAuthor.values()) {
            java.util.Collections.sort(group, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        }

        // 1. My Status tile (Add status when empty, My status when active)
        String myTileTitle = mine.isEmpty() ? "Add status" : "My status";
        StatusUpdate myPreview = mine.isEmpty() ? null : mine.get(mine.size() - 1);
        View myTile = buildTile(ctx, dp, myTileTitle,
                myPreview, true, !mine.isEmpty(), false,
                () -> showStatusCreationChooser(ctx, listener, photoListener));
        myTile.setOnClickListener(v -> {
            if (mine.isEmpty()) {
                showStatusCreationChooser(ctx, listener, photoListener);
            } else {
                showViewer(ctx, mine, 0, listener);
            }
        });
        myTile.setOnLongClickListener(v -> {
            if (mine.isEmpty()) {
                showStatusCreationChooser(ctx, listener, photoListener);
            } else {
                showMyStatusManagerDialog(ctx, mine, listener, photoListener);
            }
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
            StatusUpdate previewItem = group.get(group.size() - 1);
            for (StatusUpdate s : group) {
                if (!s.viewed) {
                    if (!anyUnread) previewItem = s;
                    anyUnread = true;
                }
            }
            View tile = buildTile(ctx, dp, previewItem.authorName, previewItem, false, true, anyUnread);
            tile.setOnClickListener(v -> showViewer(ctx, group, -1, listener));
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
    public static boolean isAudioStatusUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.startsWith("data:audio/")
                || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav")
                || lower.endsWith(".ogg") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.contains("status_voice_")
                || lower.contains("status_music_") || lower.contains("peer_status_audio_");
    }

    public static boolean isVideoStatusUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.startsWith("data:video/")
                || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".3gp") || lower.endsWith(".avi")
                || lower.endsWith(".m4v") || lower.contains("processed_vid_") || lower.contains("video/media");
    }

    /**
     * Resolves the current user's real display name for status avatars and headers
     * (never returning "My status", which previously caused "MS" initials).
     */
    public static String resolveMyDisplayName(Context ctx) {
        if (ctx == null) return "Veil User";
        try {
            com.m3.veil.model.Persona active = VeilFeatureManager.getInstance(ctx).getActivePersona();
            if (active != null && !com.m3.veil.manager.PersonaManager.PRIMARY_PERSONA_ID.equals(active.getPersonaId())) {
                String pName = active.getDisplayName();
                if (pName != null && !pName.trim().isEmpty()) {
                    return pName.trim();
                }
            }
            com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(ctx);
            String dName = up.getDisplayName();
            if (dName != null && !dName.trim().isEmpty() && !"null".equalsIgnoreCase(dName.trim())) {
                return dName.trim();
            }
            String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(ctx);
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine sov =
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx);
            if (uid == null || uid.isEmpty()) {
                uid = sov.getCurrentUid();
            }
            if (uid != null && !uid.isEmpty()) {
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        sov.getLocalManifest(uid);
                if (manifest != null && manifest.displayName != null && !manifest.displayName.trim().isEmpty()) {
                    return manifest.displayName.trim();
                }
            }
            if (active != null && active.getDisplayName() != null && !active.getDisplayName().trim().isEmpty()) {
                return active.getDisplayName().trim();
            }
            String uName = up.getUsername();
            if (uName != null && !uName.trim().isEmpty()) {
                return uName.trim().replaceFirst("^@", "");
            }
        } catch (Throwable ignored) {}
        return "Veil User";
    }

    /**
     * Resolves the current user's real uploaded profile picture (DP) across
     * SovereignIdentityAndDirectoryEngine WebP files, UserPreferences, SovereignProfileManifest,
     * and the active Persona.
     */
    public static String resolveMyAvatarSource(Context ctx) {
        if (ctx == null) return null;
        try {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine sov =
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx);
            com.m3.veil.model.Persona active = VeilFeatureManager.getInstance(ctx).getActivePersona();
            if (active != null && !com.m3.veil.manager.PersonaManager.PRIMARY_PERSONA_ID.equals(active.getPersonaId())) {
                if (active.getKeyPairId() != null && !active.getKeyPairId().isEmpty()) {
                    File pWebp = sov.getAvatarWebpFile(active.getKeyPairId());
                    if (pWebp != null && pWebp.exists() && pWebp.length() > 0) {
                        return pWebp.getAbsolutePath();
                    }
                }
                String pAv = active.getAvatarUri();
                if (pAv != null && !pAv.trim().isEmpty() && !pAv.startsWith("default_")) {
                    return pAv.trim();
                }
                return null;
            }

            String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(ctx);
            if (uid == null || uid.isEmpty()) {
                uid = sov.getCurrentUid();
            }
            if (uid != null && !uid.isEmpty()) {
                File localWebp = sov.getAvatarWebpFile(uid);
                if (localWebp != null && localWebp.exists() && localWebp.length() > 0) {
                    return localWebp.getAbsolutePath();
                }
            }

            com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(ctx);
            String avPath = up.getAvatarPath();
            if (avPath != null && !avPath.trim().isEmpty()) {
                File f = new File(avPath.trim());
                if (f.exists() && f.length() > 0) {
                    return f.getAbsolutePath();
                }
            }
            String prefAv = up.getAvatar();
            if (prefAv != null && !prefAv.trim().isEmpty() && !"null".equalsIgnoreCase(prefAv.trim())
                    && !prefAv.trim().startsWith("default_")) {
                if (prefAv.startsWith("/")) {
                    File f = new File(prefAv.trim());
                    if (f.exists() && f.length() > 0) return f.getAbsolutePath();
                } else {
                    return prefAv.trim();
                }
            }

            if (uid != null && !uid.isEmpty()) {
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        sov.getLocalManifest(uid);
                if (manifest != null && manifest.avatarBase64 != null && !manifest.avatarBase64.trim().isEmpty()) {
                    return manifest.avatarBase64.trim();
                }
            }

            if (active != null && active.getAvatarUri() != null && !active.getAvatarUri().trim().isEmpty()
                    && !active.getAvatarUri().trim().startsWith("default_")) {
                return active.getAvatarUri().trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolves a peer contact's avatar from local WebP cache, Sovereign manifest, or local chat records.
     */
    public static String resolvePeerAvatarSource(Context ctx, String authorId, String authorName) {
        if (ctx == null) return null;
        try {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine sov =
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx);
            if (authorId != null && !authorId.trim().isEmpty() && !"me".equalsIgnoreCase(authorId.trim())) {
                File peerWebp = sov.getAvatarWebpFile(authorId.trim());
                if (peerWebp != null && peerWebp.exists() && peerWebp.length() > 0) {
                    return peerWebp.getAbsolutePath();
                }
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        sov.getLocalManifest(authorId.trim());
                if (manifest != null && manifest.avatarBase64 != null && !manifest.avatarBase64.trim().isEmpty()) {
                    return manifest.avatarBase64.trim();
                }
            }
            List<com.m3.veil.model.Chat> chats =
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getAllChats();
            if (chats != null) {
                for (com.m3.veil.model.Chat c : chats) {
                    if (c == null) continue;
                    boolean match = false;
                    if (authorId != null && !authorId.isEmpty() && c.getParticipants() != null
                            && c.getParticipants().contains(authorId)) {
                        match = true;
                    } else if (authorName != null && !authorName.isEmpty()
                            && authorName.equalsIgnoreCase(c.getName())) {
                        match = true;
                    }
                    if (match && c.getAvatar() != null && !c.getAvatar().trim().isEmpty()
                            && !c.getAvatar().trim().startsWith("default_")) {
                        return c.getAvatar().trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void renderVerticalStatusList(Context ctx, LinearLayout container, List<StatusUpdate> all,
                                                StatusActionListener listener, OnPhotoStatusRequestedListener photoListener,
                                                Runnable onAddStatusRequested) {
        if (ctx == null || container == null) return;
        container.removeAllViews();
        float dp = ctx.getResources().getDisplayMetrics().density;

        android.util.TypedValue outVal = new android.util.TypedValue();
        int selectableBgRes = ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                ? outVal.resourceId : 0;
        android.util.TypedValue borderlessVal = new android.util.TypedValue();
        int borderlessBgRes = ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderlessVal, true)
                ? borderlessVal.resourceId : selectableBgRes;

        List<StatusUpdate> mine = new ArrayList<>();
        Map<String, List<StatusUpdate>> byAuthor = new LinkedHashMap<>();
        VeilFeatureManager vfm = VeilFeatureManager.getInstance(ctx);
        if (all != null) {
            for (StatusUpdate s : all) {
                if (s == null || vfm.isDeletedStatusId(s.id)) continue;
                s.contentText = StatusController.sanitizeStatusText(s.contentText);
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
        }
        java.util.Collections.sort(mine, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        for (List<StatusUpdate> group : byAuthor.values()) {
            java.util.Collections.sort(group, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        }

        // 1. "My status" Section Header + Status Rows
        TextView mySecHeader = new TextView(ctx);
        mySecHeader.setText("My status");
        mySecHeader.setTextSize(14f);
        mySecHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mySecHeader.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
        mySecHeader.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (4 * dp));
        container.addView(mySecHeader);

        boolean hasMine = !mine.isEmpty();
        String audienceSummary = getFormattedStatusAudience(ctx);

        if (hasMine) {
            for (int i = 0; i < mine.size(); i++) {
                final int rowIdx = i;
                final StatusUpdate su = mine.get(i);

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (74 * dp)));
                row.setPadding((int) (16 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
                if (selectableBgRes != 0) row.setBackgroundResource(selectableBgRes);

                // Circular Status Preview Thumbnail (50dp)
                FrameLayout thumbFrame = new FrameLayout(ctx);
                thumbFrame.setLayoutParams(new LinearLayout.LayoutParams((int) (50 * dp), (int) (50 * dp)));
                GradientDrawable circleBg = new GradientDrawable();
                circleBg.setShape(GradientDrawable.OVAL);
                circleBg.setColor(parseColor(su.bgColorHex));
                circleBg.setStroke((int) (2 * dp), ContextCompat.getColor(ctx, R.color.veil_accent));
                thumbFrame.setBackground(circleBg);
                thumbFrame.setClipToOutline(true);

                if (su.mediaUri != null && !su.mediaUri.isEmpty() && !isAudioStatusUri(su.mediaUri)) {
                    de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
                    civ.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    loadStatusImage(ctx, su.mediaUri, civ);
                    thumbFrame.addView(civ);
                } else if (isAudioStatusUri(su.mediaUri)) {
                    ImageView ivAudio = new ImageView(ctx);
                    FrameLayout.LayoutParams iaLp = new FrameLayout.LayoutParams(
                            (int) (24 * dp), (int) (24 * dp), Gravity.CENTER);
                    ivAudio.setLayoutParams(iaLp);
                    ivAudio.setImageResource(R.drawable.ic_mic);
                    ivAudio.setColorFilter(Color.WHITE);
                    thumbFrame.addView(ivAudio);
                } else {
                    TextView tvInit = new TextView(ctx);
                    tvInit.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    tvInit.setGravity(Gravity.CENTER);
                    tvInit.setTextColor(Color.WHITE);
                    tvInit.setTextSize(11f);
                    tvInit.setMaxLines(2);
                    tvInit.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
                    String cleanPreview = StatusController.sanitizeStatusText(su.contentText);
                    tvInit.setText(!cleanPreview.isEmpty() ? cleanPreview : "Status");
                    thumbFrame.addView(tvInit);
                }
                row.addView(thumbFrame);

                // Middle Info Column: "X views" + "Just now • My contacts..."
                LinearLayout infoCol = new LinearLayout(ctx);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                icLp.leftMargin = (int) (16 * dp);
                infoCol.setLayoutParams(icLp);

                int vCount = (su.viewers != null && !su.viewers.isEmpty()) ? Math.max(su.viewCount, su.viewers.size()) : su.viewCount;
                TextView tvViews = new TextView(ctx);
                tvViews.setText(vCount + (vCount == 1 ? " view" : " views"));
                tvViews.setTextSize(16f);
                tvViews.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tvViews.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
                infoCol.addView(tvViews);

                CharSequence relTime = DateUtils.getRelativeTimeSpanString(
                        su.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                TextView tvSub = new TextView(ctx);
                tvSub.setText(relTime + " \u2022 " + audienceSummary);
                tvSub.setTextSize(13.5f);
                tvSub.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                infoCol.addView(tvSub);
                row.addView(infoCol);

                // Right Actions: Eye Button (opens Status Viewers Bottom Sheet) + 3-Dots Menu
                ImageView btnEye = new ImageView(ctx);
                btnEye.setLayoutParams(new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp)));
                btnEye.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
                btnEye.setImageResource(R.drawable.ic_visibility);
                btnEye.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                if (borderlessBgRes != 0) btnEye.setBackgroundResource(borderlessBgRes);
                btnEye.setContentDescription("Viewed by");
                btnEye.setOnClickListener(v -> showStatusViewersBottomSheet(ctx, su, listener, null, null, () -> {
                    VeilFeatureManager.getInstance(ctx).deleteMyStatus(su.id);
                    if (listener != null) listener.onStatusesChanged();
                }));
                row.addView(btnEye);

                ImageView btnMore = new ImageView(ctx);
                btnMore.setLayoutParams(new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp)));
                btnMore.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
                btnMore.setImageResource(R.drawable.ic_more_vert);
                btnMore.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                if (borderlessBgRes != 0) btnMore.setBackgroundResource(borderlessBgRes);
                btnMore.setContentDescription("More options");
                btnMore.setOnClickListener(v -> showStatusItemPopupMenu(ctx, v, su, () -> {
                    VeilFeatureManager.getInstance(ctx).deleteMyStatus(su.id);
                    if (listener != null) listener.onStatusesChanged();
                }));
                row.addView(btnMore);

                row.setOnClickListener(v -> {
                    showViewer(ctx, new ArrayList<>(mine), rowIdx, listener);
                });
                container.addView(row);

                View itemDiv = new View(ctx);
                LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (0.7f * dp));
                idLp.leftMargin = (int) (82 * dp);
                itemDiv.setLayoutParams(idLp);
                itemDiv.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorOutline));
                container.addView(itemDiv);
            }

            // E2EE Disappearing Footer Note
            TextView tvEncNote = new TextView(ctx);
            tvEncNote.setGravity(Gravity.CENTER);
            tvEncNote.setPadding((int) (24 * dp), (int) (14 * dp), (int) (24 * dp), (int) (14 * dp));
            tvEncNote.setText("\uD83D\uDD12 Your statuses are end-to-end encrypted. They will disappear after 24 hours.");
            tvEncNote.setTextSize(12.5f);
            tvEncNote.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            container.addView(tvEncNote);
        } else {
            // Empty "My status" row
            LinearLayout myRow = new LinearLayout(ctx);
            myRow.setOrientation(LinearLayout.HORIZONTAL);
            myRow.setGravity(Gravity.CENTER_VERTICAL);
            myRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (74 * dp)));
            myRow.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
            if (selectableBgRes != 0) myRow.setBackgroundResource(selectableBgRes);

            FrameLayout avFrame = new FrameLayout(ctx);
            avFrame.setLayoutParams(new LinearLayout.LayoutParams((int) (52 * dp), (int) (52 * dp)));

            de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
            civ.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, resolveMyAvatarSource(ctx), resolveMyDisplayName(ctx), false);
            avFrame.addView(civ);

            TextView plus = new TextView(ctx);
            FrameLayout.LayoutParams plLp = new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.BOTTOM | Gravity.END);
            plus.setLayoutParams(plLp);
            plus.setGravity(Gravity.CENTER);
            plus.setText("+");
            plus.setTextColor(Color.WHITE);
            plus.setTextSize(12f);
            plus.setTypeface(null, Typeface.BOLD);
            GradientDrawable plusBg = new GradientDrawable();
            plusBg.setShape(GradientDrawable.OVAL);
            plusBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
            plusBg.setStroke((int) (1.5f * dp), ContextCompat.getColor(ctx, R.color.colorSurface));
            plus.setBackground(plusBg);
            avFrame.addView(plus);
            myRow.addView(avFrame);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tcLp.leftMargin = (int) (16 * dp);
            textCol.setLayoutParams(tcLp);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText("My status");
            tvTitle.setTextSize(16f);
            tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
            textCol.addView(tvTitle);

            TextView tvSubtitle = new TextView(ctx);
            tvSubtitle.setText("Tap to add status update");
            tvSubtitle.setTextSize(13.5f);
            tvSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            textCol.addView(tvSubtitle);
            myRow.addView(textCol);

            myRow.setOnClickListener(v -> showStatusCreationChooser(ctx, listener, photoListener));
            container.addView(myRow);

            View div = new View(ctx);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (0.8f * dp));
            divLp.topMargin = (int) (4 * dp);
            divLp.bottomMargin = (int) (4 * dp);
            div.setLayoutParams(divLp);
            div.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorOutline));
            container.addView(div);
        }

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
            secHeader.setText("Recent updates");
            secHeader.setTextSize(13f);
            secHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            secHeader.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            secHeader.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (6 * dp));
            container.addView(secHeader);

            for (List<StatusUpdate> group : unreadGroups) {
                StatusUpdate firstUnseen = group.get(0);
                for (StatusUpdate s : group) {
                    if (!s.viewed) {
                        firstUnseen = s;
                        break;
                    }
                }
                container.addView(buildPeerStatusRow(ctx, dp, selectableBgRes, firstUnseen, group, true, listener));
            }
        }

        // 4. VIEWED UPDATES SECTION
        if (!seenGroups.isEmpty()) {
            TextView secHeader = new TextView(ctx);
            secHeader.setText("Viewed updates");
            secHeader.setTextSize(13f);
            secHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            secHeader.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            secHeader.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (6 * dp));
            container.addView(secHeader);

            for (List<StatusUpdate> group : seenGroups) {
                StatusUpdate latest = group.get(group.size() - 1);
                container.addView(buildPeerStatusRow(ctx, dp, selectableBgRes, latest, group, false, listener));
            }
        }

        // 5. Empty State (ONLY shown when neither the user nor any contacts have active status updates)
        if (!hasMine && unreadGroups.isEmpty() && seenGroups.isEmpty()) {
            com.google.android.material.card.MaterialCardView emptyOuterCard =
                    new com.google.android.material.card.MaterialCardView(ctx);
            LinearLayout.LayoutParams eocLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eocLp.setMargins((int) (16 * dp), (int) (18 * dp), (int) (16 * dp), (int) (24 * dp));
            emptyOuterCard.setLayoutParams(eocLp);
            emptyOuterCard.setRadius(20 * dp);
            emptyOuterCard.setCardElevation(0f);
            emptyOuterCard.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurfaceVariant));
            emptyOuterCard.setStrokeWidth(Math.max(1, (int) (1 * dp)));
            emptyOuterCard.setStrokeColor(ContextCompat.getColor(ctx, R.color.colorOutline));

            LinearLayout emptyCard = new LinearLayout(ctx);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding((int) (24 * dp), (int) (32 * dp), (int) (24 * dp), (int) (32 * dp));

            FrameLayout iconBadge = new FrameLayout(ctx);
            iconBadge.setLayoutParams(new LinearLayout.LayoutParams((int) (56 * dp), (int) (56 * dp)));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.OVAL);
            badgeBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
            badgeBg.setAlpha(30);
            iconBadge.setBackground(badgeBg);

            ImageView ivEmptyIcon = new ImageView(ctx);
            ivEmptyIcon.setLayoutParams(new FrameLayout.LayoutParams((int) (26 * dp), (int) (26 * dp), Gravity.CENTER));
            ivEmptyIcon.setImageResource(R.drawable.ic_camera);
            ivEmptyIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.veil_accent));
            iconBadge.addView(ivEmptyIcon);
            emptyCard.addView(iconBadge);

            TextView tvEmptyH = new TextView(ctx);
            tvEmptyH.setText("Share photos, videos & voice updates");
            tvEmptyH.setTextSize(16.5f);
            tvEmptyH.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvEmptyH.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
            tvEmptyH.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ehLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ehLp.topMargin = (int) (14 * dp);
            tvEmptyH.setLayoutParams(ehLp);
            emptyCard.addView(tvEmptyH);

            TextView tvEmptySub = new TextView(ctx);
            tvEmptySub.setText("Status updates from you and your contacts appear here and disappear automatically after 24 hours.");
            tvEmptySub.setTextSize(13.5f);
            tvEmptySub.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            tvEmptySub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            esLp.topMargin = (int) (6 * dp);
            tvEmptySub.setLayoutParams(esLp);
            emptyCard.addView(tvEmptySub);

            com.google.android.material.button.MaterialButton btnCreate =
                    new com.google.android.material.button.MaterialButton(ctx);
            btnCreate.setText("Add status update");
            btnCreate.setAllCaps(false);
            btnCreate.setTextColor(Color.WHITE);
            btnCreate.setCornerRadius((int) (22 * dp));
            btnCreate.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.veil_accent)));
            LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * dp));
            bcLp.topMargin = (int) (18 * dp);
            btnCreate.setLayoutParams(bcLp);
            btnCreate.setOnClickListener(v -> {
                if (onAddStatusRequested != null) onAddStatusRequested.run();
                else showStatusCreationChooser(ctx, listener, photoListener);
            });
            emptyCard.addView(btnCreate);

            emptyOuterCard.addView(emptyCard);
            container.addView(emptyOuterCard);
        }
    }

    private static View buildPeerStatusRow(Context ctx, float dp, int selectableBgRes,
                                          StatusUpdate first, List<StatusUpdate> group,
                                          boolean isUnread, StatusActionListener listener) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (74 * dp)));
        row.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        if (selectableBgRes != 0) row.setBackgroundResource(selectableBgRes);

        // Avatar Frame (52dp) with VEIL Material 3 status ring
        FrameLayout avFrame = new FrameLayout(ctx);
        avFrame.setLayoutParams(new LinearLayout.LayoutParams((int) (52 * dp), (int) (52 * dp)));

        int ringColor = isUnread
                ? ContextCompat.getColor(ctx, R.color.veil_accent)
                : ContextCompat.getColor(ctx, R.color.colorOutline);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(parseColor(first.bgColorHex));
        ring.setStroke((int) (2.2f * dp), ringColor);
        avFrame.setBackground(ring);
        avFrame.setClipToOutline(true);

        int innerPad = (int) (3 * dp);
        if (first.mediaUri != null && !first.mediaUri.isEmpty() && !isAudioStatusUri(first.mediaUri)) {
            de.hdodenhof.circleimageview.CircleImageView thumb = new de.hdodenhof.circleimageview.CircleImageView(ctx);
            FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            tLp.setMargins(innerPad, innerPad, innerPad, innerPad);
            thumb.setLayoutParams(tLp);
            avFrame.addView(thumb);
            loadStatusImage(ctx, first.mediaUri, thumb);
        } else if (isAudioStatusUri(first.mediaUri)) {
            ImageView ivMic = new ImageView(ctx);
            ivMic.setLayoutParams(new FrameLayout.LayoutParams((int) (22 * dp), (int) (22 * dp), Gravity.CENTER));
            ivMic.setImageResource(R.drawable.ic_mic);
            ivMic.setColorFilter(Color.WHITE);
            avFrame.addView(ivMic);
        } else if (first.contentText != null && !StatusController.sanitizeStatusText(first.contentText).isEmpty()) {
            TextView tvPreview = new TextView(ctx);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            pLp.setMargins(innerPad, innerPad, innerPad, innerPad);
            tvPreview.setLayoutParams(pLp);
            tvPreview.setGravity(Gravity.CENTER);
            tvPreview.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
            tvPreview.setText(StatusController.sanitizeStatusText(first.contentText));
            tvPreview.setTextColor(Color.WHITE);
            tvPreview.setTextSize(10.5f);
            tvPreview.setMaxLines(2);
            tvPreview.setTypeface(fontFor(first.fontStyleIndex));
            avFrame.addView(tvPreview);
        } else {
            de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
            FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            cLp.setMargins(innerPad, innerPad, innerPad, innerPad);
            civ.setLayoutParams(cLp);
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, resolvePeerAvatarSource(ctx, first.authorId, first.authorName), first.authorName, false);
            avFrame.addView(civ);
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
        tvName.setTextSize(16f);
        tvName.setTypeface(Typeface.create("sans-serif-medium", isUnread ? Typeface.BOLD : Typeface.NORMAL));
        tvName.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
        col.addView(tvName);

        CharSequence relTime = DateUtils.getRelativeTimeSpanString(first.timestampMs,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        String typeTag = "";
        if (isAudioStatusUri(first.mediaUri)) {
            typeTag = "\uD83C\uDFA4 Voice \u2022 ";
        } else if (isVideoStatusUri(first.mediaUri)) {
            typeTag = "\uD83C\uDFAC Video \u2022 ";
        } else if (first.mediaUri != null && !first.mediaUri.isEmpty()) {
            typeTag = "\uD83D\uDCF7 Photo \u2022 ";
        }
        String countPrefix = (group != null && group.size() > 1) ? (group.size() + " updates \u2022 ") : "";

        TextView tvTime = new TextView(ctx);
        tvTime.setText(countPrefix + typeTag + relTime);
        tvTime.setTextSize(13.5f);
        tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
        col.addView(tvTime);

        row.addView(col);
        row.setOnClickListener(v -> showViewer(ctx, group, -1, listener));
        wrapper.addView(row);

        View itemDiv = new View(ctx);
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (0.7f * dp));
        idLp.leftMargin = (int) (84 * dp);
        itemDiv.setLayoutParams(idLp);
        itemDiv.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorOutline));
        wrapper.addView(itemDiv);

        return wrapper;
    }

    private static View buildTile(Context ctx, float dp, String name, StatusUpdate preview,
                                  boolean isMine, boolean hasStatus, boolean unread) {
        return buildTile(ctx, dp, name, preview, isMine, hasStatus, unread, null);
    }

    private static View buildTile(Context ctx, float dp, String name, StatusUpdate preview,
                                  boolean isMine, boolean hasStatus, boolean unread,
                                  Runnable onAddMoreClicked) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        // WhatsApp standard dimensions for status card (approx 9:16 aspect ratio)
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams((int) (96 * dp), (int) (154 * dp));
        tileLp.rightMargin = (int) (10 * dp);
        tile.setLayoutParams(tileLp);

        FrameLayout card = new FrameLayout(ctx);
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(18 * dp);

        if (isMine && !hasStatus) {
            // === WhatsApp "Add status" Card (Empty state, Screenshot_20261031-194646.jpg) ===
            cardBg.setColor(ContextCompat.getColor(ctx, R.color.colorSurfaceVariant));
            cardBg.setStroke((int) (1 * dp), ContextCompat.getColor(ctx, R.color.colorOutline));
            card.setBackground(cardBg);
            card.setClipToOutline(true);

            // Centered upper-half avatar container (52dp)
            FrameLayout avFrame = new FrameLayout(ctx);
            FrameLayout.LayoutParams avLp = new FrameLayout.LayoutParams((int) (52 * dp), (int) (52 * dp), Gravity.CENTER_HORIZONTAL);
            avLp.topMargin = (int) (26 * dp);
            avFrame.setLayoutParams(avLp);

            de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
            civ.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, resolveMyAvatarSource(ctx), resolveMyDisplayName(ctx), false);
            avFrame.addView(civ);

            // Circular '+' Badge (20dp) overlapping bottom right
            TextView plus = new TextView(ctx);
            FrameLayout.LayoutParams plLp = new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.BOTTOM | Gravity.END);
            plus.setLayoutParams(plLp);
            plus.setGravity(Gravity.CENTER);
            plus.setText("+");
            plus.setTextColor(Color.WHITE);
            plus.setTextSize(13f);
            plus.setTypeface(null, Typeface.BOLD);
            GradientDrawable plusBg = new GradientDrawable();
            plusBg.setShape(GradientDrawable.OVAL);
            plusBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
            plusBg.setStroke((int) (2 * dp), ContextCompat.getColor(ctx, R.color.colorSurface));
            plus.setBackground(plusBg);
            avFrame.addView(plus);

            card.addView(avFrame);

            // "Add status" text at bottom
            TextView tvAdd = new TextView(ctx);
            FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            addLp.setMargins((int) (6 * dp), 0, (int) (6 * dp), (int) (14 * dp));
            tvAdd.setLayoutParams(addLp);
            tvAdd.setGravity(Gravity.CENTER);
            tvAdd.setText("Add status");
            tvAdd.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
            tvAdd.setTextSize(12.5f);
            tvAdd.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            card.addView(tvAdd);

        } else {
            // === WhatsApp Status Preview Card (My active status or Peer status, Screenshot_20261031-194802.jpg) ===
            if (preview != null && preview.bgColorHex != null) {
                cardBg.setColor(parseColor(preview.bgColorHex));
            } else {
                cardBg.setColor(ContextCompat.getColor(ctx, R.color.colorSurfaceVariant));
            }
            card.setBackground(cardBg);
            card.setClipToOutline(true);

            // Photo thumbnail if mediaUri exists
            if (preview != null && preview.mediaUri != null && !preview.mediaUri.isEmpty() && !isAudioStatusUri(preview.mediaUri)) {
                ImageView thumb = new ImageView(ctx);
                thumb.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(thumb);
                loadStatusImage(ctx, preview.mediaUri, thumb);
            } else if (preview != null && isAudioStatusUri(preview.mediaUri)) {
                LinearLayout voiceCol = new LinearLayout(ctx);
                voiceCol.setOrientation(LinearLayout.VERTICAL);
                voiceCol.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams vcLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                voiceCol.setLayoutParams(vcLp);

                FrameLayout micCircle = new FrameLayout(ctx);
                micCircle.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
                GradientDrawable mcBg = new GradientDrawable();
                mcBg.setShape(GradientDrawable.OVAL);
                mcBg.setColor(Color.parseColor("#38000000"));
                mcBg.setStroke((int) (1 * dp), Color.parseColor("#4DFFFFFF"));
                micCircle.setBackground(mcBg);

                ImageView ivMic = new ImageView(ctx);
                ivMic.setLayoutParams(new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.CENTER));
                ivMic.setImageResource(R.drawable.ic_mic);
                ivMic.setColorFilter(Color.WHITE);
                micCircle.addView(ivMic);
                voiceCol.addView(micCircle);

                TextView tvVoiceDur = new TextView(ctx);
                tvVoiceDur.setText(extractAudioDurationLabel(preview.contentText));
                tvVoiceDur.setTextColor(Color.WHITE);
                tvVoiceDur.setTextSize(10f);
                tvVoiceDur.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams vdLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vdLp.topMargin = (int) (4 * dp);
                tvVoiceDur.setLayoutParams(vdLp);
                voiceCol.addView(tvVoiceDur);

                card.addView(voiceCol);
            } else if (preview != null && preview.contentText != null) {
                TextView tvPreview = new TextView(ctx);
                FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                pLp.setMargins((int) (8 * dp), 0, (int) (8 * dp), 0);
                tvPreview.setLayoutParams(pLp);
                tvPreview.setGravity(Gravity.CENTER);
                tvPreview.setText(StatusController.sanitizeStatusText(preview.contentText));
                tvPreview.setTextColor(Color.WHITE);
                tvPreview.setTextSize(10f);
                tvPreview.setMaxLines(4);
                tvPreview.setTypeface(fontFor(preview.fontStyleIndex));
                card.addView(tvPreview);
            }

            // Bottom gradient scrim
            View overlay = new View(ctx);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (64 * dp), Gravity.BOTTOM));
            GradientDrawable ov = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{Color.parseColor("#E6000000"), Color.TRANSPARENT});
            overlay.setBackground(ov);
            card.addView(overlay);

            // Top-left Avatar with Ring (38dp)
            FrameLayout avatarFrame = new FrameLayout(ctx);
            FrameLayout.LayoutParams avLp = new FrameLayout.LayoutParams((int) (38 * dp), (int) (38 * dp), Gravity.TOP | Gravity.START);
            avLp.setMargins((int) (8 * dp), (int) (8 * dp), 0, 0);
            avatarFrame.setLayoutParams(avLp);

            int ringColor = isMine ? (hasStatus ? ContextCompat.getColor(ctx, R.color.veil_accent) : Color.WHITE)
                    : (unread ? ContextCompat.getColor(ctx, R.color.veil_accent) : Color.parseColor("#80FFFFFF"));
            int ringWidth = (isMine || unread) ? (int) (2.5f * dp) : (int) (1.5f * dp);

            de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
            civ.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            civ.setBorderColor(ringColor);
            civ.setBorderWidth(ringWidth);

            String authorAvatar = isMine
                    ? resolveMyAvatarSource(ctx)
                    : resolvePeerAvatarSource(ctx, preview != null ? preview.authorId : null, name);
            String avatarFallbackName = isMine ? resolveMyDisplayName(ctx) : name;
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, authorAvatar, avatarFallbackName, false);
            avatarFrame.addView(civ);

            if (isMine) {
                // Small '+' badge on user's active status avatar
                TextView plus = new TextView(ctx);
                FrameLayout.LayoutParams plLp = new FrameLayout.LayoutParams((int) (16 * dp), (int) (16 * dp), Gravity.BOTTOM | Gravity.END);
                plus.setLayoutParams(plLp);
                plus.setGravity(Gravity.CENTER);
                plus.setText("+");
                plus.setTextColor(Color.WHITE);
                plus.setTextSize(10f);
                plus.setTypeface(null, Typeface.BOLD);
                GradientDrawable plusBg = new GradientDrawable();
                plusBg.setShape(GradientDrawable.OVAL);
                plusBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
                plusBg.setStroke((int) (1.5f * dp), ContextCompat.getColor(ctx, R.color.colorSurface));
                plus.setBackground(plusBg);
                avatarFrame.addView(plus);
                if (onAddMoreClicked != null) {
                    avatarFrame.setOnClickListener(v -> onAddMoreClicked.run());
                }
            }
            card.addView(avatarFrame);

            // Bottom title (Name or "My status")
            TextView tvName = new TextView(ctx);
            FrameLayout.LayoutParams nLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            nLp.setMargins((int) (8 * dp), 0, (int) (8 * dp), (int) (10 * dp));
            tvName.setLayoutParams(nLp);
            tvName.setText(isMine ? "My status" : name);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(12f);
            tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvName.setMaxLines(2);
            tvName.setShadowLayer(4f, 0, 1.5f, Color.parseColor("#99000000"));
            card.addView(tvName);
        }

        tile.addView(card);
        return tile;
    }

    // =====================================================================
    // COMPOSER CHOOSER & COMPOSERS (WhatsApp / Telegram Full Parity)
    // =====================================================================

    public static void showStatusCreationChooser(Context ctx, StatusActionListener listener,
                                                OnPhotoStatusRequestedListener photoListener) {
        showStatusCreationChooser(ctx, listener, photoListener, false);
    }

    public static void showStatusCreationChooser(Context ctx, StatusActionListener listener,
                                                OnPhotoStatusRequestedListener photoListener,
                                                boolean startInLayoutMode) {
        if (ctx == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(ctx, R.style.ModernBottomSheetDialogTheme);
        sheet.setContentView(R.layout.dialog_status_composer_choice);

        sheet.setOnShowListener(d -> {
            FrameLayout bottomSheet = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                ViewGroup.LayoutParams lp = bottomSheet.getLayoutParams();
                lp.height = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.94f);
                bottomSheet.setLayoutParams(lp);
                com.google.android.material.bottomsheet.BottomSheetBehavior<FrameLayout> behavior =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        });

        final float dp = ctx.getResources().getDisplayMetrics().density;
        final boolean[] isLayoutMode = {startInLayoutMode};
        final boolean[] showingAllAlbumsInDropdown = {false};
        final String[] activeBucketId = {"ALL"};

        View btnClose = sheet.findViewById(R.id.btnCloseStatusChoice);
        TextView tvTitle = sheet.findViewById(R.id.tvStatusSheetTitle);
        TextView tvLayoutSub = sheet.findViewById(R.id.tvLayoutModeSubtitle);
        View layoutQuickModes = sheet.findViewById(R.id.layoutQuickStatusModes);

        View btnQuickText = sheet.findViewById(R.id.btnQuickText);
        View btnQuickMusic = sheet.findViewById(R.id.btnQuickMusic);
        View btnQuickLayout = sheet.findViewById(R.id.btnQuickLayout);
        View btnQuickVoice = sheet.findViewById(R.id.btnQuickVoice);

        View layoutAlbumSelector = sheet.findViewById(R.id.layoutStatusAlbumSelector);
        TextView tvCurrentAlbum = sheet.findViewById(R.id.tvStatusCurrentAlbum);
        ImageView ivAlbumChevron = sheet.findViewById(R.id.ivStatusAlbumChevron);
        TextView tvSelectionHint = sheet.findViewById(R.id.tvStatusSelectionHint);

        androidx.recyclerview.widget.RecyclerView rvGrid = sheet.findViewById(R.id.rvStatusGalleryGrid);
        ProgressBar progressBar = sheet.findViewById(R.id.statusGalleryProgressBar);
        View layoutPerm = sheet.findViewById(R.id.layoutStatusGalleryPermission);
        View btnBrowseFiles = sheet.findViewById(R.id.btnStatusBrowseSystemFiles);

        View viewDropdownScrim = sheet.findViewById(R.id.viewAlbumDropdownScrim);
        View cardAlbumDropdown = sheet.findViewById(R.id.cardStatusAlbumDropdown);
        LinearLayout llAlbumDropdownContainer = sheet.findViewById(R.id.llAlbumDropdownContainer);
        View fabMultiOrFolder = sheet.findViewById(R.id.fabStatusMultiOrFolder);

        View layoutBottomSelectionBar = sheet.findViewById(R.id.layoutStatusBottomSelectionBar);
        LinearLayout llSelectedThumbs = sheet.findViewById(R.id.llStatusSelectedThumbs);
        FrameLayout fabConfirmSelection = sheet.findViewById(R.id.fabStatusConfirmSelection);

        if (fabConfirmSelection != null) {
            GradientDrawable fabBg = new GradientDrawable();
            fabBg.setShape(GradientDrawable.OVAL);
            fabBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
            fabConfirmSelection.setBackground(fabBg);
        }

        final List<com.m3.veil.model.GalleryMediaItem> allItems = new ArrayList<>();
        final List<com.m3.veil.model.GalleryMediaItem> displayedItems = new ArrayList<>();
        final List<com.m3.veil.model.GalleryMediaItem> selectedBatch = new ArrayList<>();
        final Map<String, com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.AlbumBucket> albumBuckets = new LinkedHashMap<>();

        final com.m3.veil.ui.chat.adapters.GalleryMediaAdapter[] adapterHolder = new com.m3.veil.ui.chat.adapters.GalleryMediaAdapter[1];
        final Runnable[] applyBucketFilterHolder = new Runnable[1];

        Runnable updateModeUi = () -> {
            if (isLayoutMode[0]) {
                if (tvTitle != null) tvTitle.setText("Start layout");
                if (tvLayoutSub != null) tvLayoutSub.setVisibility(View.VISIBLE);
                if (layoutQuickModes != null) layoutQuickModes.setVisibility(View.GONE);
                if (adapterHolder[0] != null) {
                    adapterHolder[0].clearSelection();
                    adapterHolder[0].setMaxSelectionCount(6);
                    adapterHolder[0].setAlwaysShowSelectionRing(true);
                }
                if (applyBucketFilterHolder[0] != null) applyBucketFilterHolder[0].run();
            } else {
                if (tvTitle != null) tvTitle.setText("Add status");
                if (tvLayoutSub != null) tvLayoutSub.setVisibility(View.GONE);
                if (layoutQuickModes != null) layoutQuickModes.setVisibility(View.VISIBLE);
                if (adapterHolder[0] != null) {
                    adapterHolder[0].clearSelection();
                    adapterHolder[0].setMaxSelectionCount(30);
                    adapterHolder[0].setAlwaysShowSelectionRing(false);
                }
                if (applyBucketFilterHolder[0] != null) applyBucketFilterHolder[0].run();
            }
        };

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                if (isLayoutMode[0] && !startInLayoutMode) {
                    isLayoutMode[0] = false;
                    updateModeUi.run();
                } else {
                    sheet.dismiss();
                }
            });
        }

        // 1. Text Status Mode
        if (btnQuickText != null) {
            btnQuickText.setOnClickListener(v -> {
                sheet.dismiss();
                showTextStatusComposer(ctx, listener, photoListener);
            });
        }

        // 2. Music Status Mode
        if (btnQuickMusic != null) {
            btnQuickMusic.setOnClickListener(v -> {
                sheet.dismiss();
                showMusicStatusComposer(ctx, listener, photoListener);
            });
        }

        // 3. Layout (Collage up to 6 photos) Mode (Screenshots 3.jpg, 6.jpg, 7.jpg, 8.jpg)
        if (btnQuickLayout != null) {
            btnQuickLayout.setOnClickListener(v -> {
                isLayoutMode[0] = true;
                updateModeUi.run();
            });
        }

        // 4. Voice Status Mode (Screenshot 12.jpg)
        if (btnQuickVoice != null) {
            btnQuickVoice.setOnClickListener(v -> {
                sheet.dismiss();
                showVoiceStatusComposer(ctx, listener, photoListener);
            });
        }

        Runnable launchCameraInStatusMode = () -> {
            sheet.dismiss();
            try {
                android.content.Intent camIntent = new android.content.Intent(ctx, com.m3.veil.ui.chat.CameraGalleryActivity.class);
                camIntent.putExtra(com.m3.veil.ui.chat.CameraGalleryActivity.EXTRA_IS_STATUS_MODE, true);
                camIntent.putExtra(com.m3.veil.ui.chat.CameraGalleryActivity.EXTRA_RECIPIENT_NAME, getFormattedStatusAudience(ctx));
                ctx.startActivity(camIntent);
            } catch (Exception e) {
                if (photoListener != null) photoListener.onPhotoStatusRequested();
            }
        };

        // Helper to launch MediaEditActivity in Status mode for single or batch selection
        Runnable confirmSelectionAction = () -> {
            if (selectedBatch.isEmpty()) return;
            if (isLayoutMode[0]) {
                List<Uri> layoutUris = new ArrayList<>();
                for (com.m3.veil.model.GalleryMediaItem gi : selectedBatch) {
                    if (gi != null && gi.getUri() != null) layoutUris.add(gi.getUri());
                }
                if (!layoutUris.isEmpty()) {
                    showLayoutCollageEditorDialog(ctx, layoutUris, sheet, listener);
                }
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<String> types = new ArrayList<>();
                for (com.m3.veil.model.GalleryMediaItem gi : selectedBatch) {
                    if (gi != null && gi.getUri() != null) {
                        uris.add(gi.getUri());
                        types.add(gi.isVideo() ? "video" : "image");
                    }
                }
                if (!uris.isEmpty()) {
                    sheet.dismiss();
                    launchStatusMediaEditor(ctx, uris.get(0), types.get(0), uris, types);
                }
            }
        };

        if (fabConfirmSelection != null) {
            fabConfirmSelection.setOnClickListener(v -> confirmSelectionAction.run());
        }

        com.m3.veil.ui.chat.adapters.GalleryMediaAdapter adapter =
                new com.m3.veil.ui.chat.adapters.GalleryMediaAdapter(
                        displayedItems,
                        new com.m3.veil.ui.chat.adapters.GalleryMediaAdapter.OnGalleryItemClickListener() {
                            @Override
                            public void onCameraTileClicked() {
                                launchCameraInStatusMode.run();
                            }

                            @Override
                            public void onMediaItemSelected(com.m3.veil.model.GalleryMediaItem item) {}

                            @Override
                            public void onSingleMediaItemClicked(com.m3.veil.model.GalleryMediaItem item) {
                                if (item == null || item.getUri() == null) return;
                                if (isLayoutMode[0]) return;
                                sheet.dismiss();
                                ArrayList<Uri> singleList = new ArrayList<>();
                                singleList.add(item.getUri());
                                ArrayList<String> typeList = new ArrayList<>();
                                typeList.add(item.isVideo() ? "video" : "image");
                                launchStatusMediaEditor(ctx, item.getUri(), item.isVideo() ? "video" : "image", singleList, typeList);
                            }

                            @Override
                            public void onMultiSelectionChanged(List<com.m3.veil.model.GalleryMediaItem> items) {
                                selectedBatch.clear();
                                if (items != null) selectedBatch.addAll(items);

                                if (tvSelectionHint != null) {
                                    if (!selectedBatch.isEmpty()) {
                                        tvSelectionHint.setVisibility(View.VISIBLE);
                                        tvSelectionHint.setText(isLayoutMode[0]
                                                ? (selectedBatch.size() + " / 6 selected")
                                                : (selectedBatch.size() + " selected"));
                                    } else {
                                        tvSelectionHint.setVisibility(View.GONE);
                                    }
                                }

                                if (layoutBottomSelectionBar != null && llSelectedThumbs != null) {
                                    llSelectedThumbs.removeAllViews();
                                    if (selectedBatch.isEmpty()) {
                                        layoutBottomSelectionBar.setVisibility(View.GONE);
                                    } else {
                                        layoutBottomSelectionBar.setVisibility(View.VISIBLE);
                                        for (int i = 0; i < selectedBatch.size(); i++) {
                                            final com.m3.veil.model.GalleryMediaItem selItem = selectedBatch.get(i);
                                            com.google.android.material.card.MaterialCardView card =
                                                    new com.google.android.material.card.MaterialCardView(ctx);
                                            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                                                    Math.round(48 * dp), Math.round(48 * dp));
                                            clp.rightMargin = Math.round(8 * dp);
                                            card.setLayoutParams(clp);
                                            card.setRadius(8 * dp);
                                            card.setCardElevation(0f);
                                            card.setStrokeWidth(Math.round(1 * dp));
                                            card.setStrokeColor(ContextCompat.getColor(ctx, R.color.colorOutline));

                                            ImageView iv = new ImageView(ctx);
                                            iv.setLayoutParams(new FrameLayout.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT));
                                            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                            com.m3.veil.ui.chat.adapters.GalleryMediaAdapter.loadThumbnailInto(selItem, iv);
                                            card.addView(iv);
                                            llSelectedThumbs.addView(card);
                                        }
                                    }
                                }
                            }
                        });
        adapterHolder[0] = adapter;

        if (rvGrid != null) {
            rvGrid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(ctx, 3));
            rvGrid.setAdapter(adapter);
        }

        Runnable applyBucketFilter = () -> {
            displayedItems.clear();
            displayedItems.add(com.m3.veil.model.GalleryMediaItem.createCameraTile());
            com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.AlbumBucket bucket = albumBuckets.get(activeBucketId[0]);
            List<com.m3.veil.model.GalleryMediaItem> source = bucket != null ? bucket.items : allItems;
            for (com.m3.veil.model.GalleryMediaItem item : source) {
                if (isLayoutMode[0] && item.isVideo()) continue; // Layout collage is for photos
                displayedItems.add(item);
            }
            adapter.notifyDataSetChanged();
        };
        applyBucketFilterHolder[0] = applyBucketFilter;

        // Album Dropdown logic (Screenshots 4.jpg & 5.jpg)
        Runnable hideAlbumDropdown = () -> {
            if (cardAlbumDropdown != null) cardAlbumDropdown.setVisibility(View.GONE);
            if (viewDropdownScrim != null) viewDropdownScrim.setVisibility(View.GONE);
            if (ivAlbumChevron != null) ivAlbumChevron.animate().rotation(0f).setDuration(180).start();
        };

        final java.util.function.Consumer<Boolean>[] populateDropdownHolder = new java.util.function.Consumer[1];
        populateDropdownHolder[0] = (Boolean showMoreApps) -> {
            if (llAlbumDropdownContainer == null) return;
            llAlbumDropdownContainer.removeAllViews();
            android.util.TypedValue tv = new android.util.TypedValue();
            int rippleRes = ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ? tv.resourceId : 0;

            if (Boolean.TRUE.equals(showMoreApps)) {
                // External sources: Files, Photos, File Manager (Google Drive removed per user request)
                String[][] externalSources = {
                        {"Files", "#3B82F6"},
                        {"Photos", "#F59E0B"},
                        {"File Manager", "#0EA5E9"}
                };
                for (String[] app : externalSources) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(16 * dp), Math.round(12 * dp));
                    if (rippleRes != 0) row.setBackgroundResource(rippleRes);

                    FrameLayout iconBox = new FrameLayout(ctx);
                    iconBox.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * dp), Math.round(42 * dp)));
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(12 * dp);
                    bg.setColor(Color.parseColor(app[1]));
                    iconBox.setBackground(bg);

                    ImageView iv = new ImageView(ctx);
                    iv.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
                    iv.setImageResource("Photos".equals(app[0]) ? R.drawable.ic_image : R.drawable.ic_folder_multi);
                    iv.setColorFilter(Color.WHITE);
                    iconBox.addView(iv);
                    row.addView(iconBox);

                    TextView tvName = new TextView(ctx);
                    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    nlp.leftMargin = Math.round(14 * dp);
                    tvName.setLayoutParams(nlp);
                    tvName.setText(app[0]);
                    tvName.setTextSize(15.5f);
                    tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
                    row.addView(tvName);

                    final String selectedAppName = app[0];
                    row.setOnClickListener(v -> {
                        hideAlbumDropdown.run();
                        com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.launchExternalSourcePicker(
                                ctx, selectedAppName, true, (uris, types) -> {
                                    if (uris == null || uris.isEmpty()) return;
                                    if (isLayoutMode[0]) {
                                        showLayoutCollageEditorDialog(ctx, uris, sheet, listener);
                                    } else {
                                        sheet.dismiss();
                                        launchStatusMediaEditor(ctx, uris.get(0),
                                                types != null && !types.isEmpty() ? types.get(0) : "image",
                                                uris, types);
                                    }
                                });
                    });
                    llAlbumDropdownContainer.addView(row);
                }
                return;
            }

            // Screenshot 4.jpg: Recents, Videos, WhatsApp / Device folders, More apps, See more
            List<com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.AlbumBucket> bucketsList =
                    new ArrayList<>(albumBuckets.values());
            int limit = showingAllAlbumsInDropdown[0] ? bucketsList.size() : Math.min(bucketsList.size(), 5);
            for (int i = 0; i < limit; i++) {
                com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.AlbumBucket bucket = bucketsList.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
                if (rippleRes != 0) row.setBackgroundResource(rippleRes);

                com.google.android.material.card.MaterialCardView thumbCard =
                        new com.google.android.material.card.MaterialCardView(ctx);
                thumbCard.setLayoutParams(new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp)));
                thumbCard.setRadius(10 * dp);
                thumbCard.setCardElevation(0f);
                thumbCard.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurfaceVariant));

                ImageView ivThumb = new ImageView(ctx);
                ivThumb.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (!bucket.items.isEmpty()) {
                    com.m3.veil.ui.chat.adapters.GalleryMediaAdapter.loadThumbnailInto(bucket.items.get(0), ivThumb);
                }
                thumbCard.addView(ivThumb);
                row.addView(thumbCard);

                LinearLayout textCol = new LinearLayout(ctx);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.leftMargin = Math.round(14 * dp);
                textCol.setLayoutParams(tlp);

                TextView tvAlbumName = new TextView(ctx);
                tvAlbumName.setText(bucket.name);
                tvAlbumName.setTextSize(15.5f);
                tvAlbumName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tvAlbumName.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
                textCol.addView(tvAlbumName);

                TextView tvCount = new TextView(ctx);
                tvCount.setText(bucket.items.size() + (bucket.items.size() == 1 ? " item" : " items"));
                tvCount.setTextSize(12.5f);
                tvCount.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                textCol.addView(tvCount);
                row.addView(textCol);

                if (bucket.id.equals(activeBucketId[0])) {
                    ImageView ivCheck = new ImageView(ctx);
                    ivCheck.setLayoutParams(new LinearLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp)));
                    ivCheck.setImageResource(R.drawable.ic_check);
                    ivCheck.setColorFilter(ContextCompat.getColor(ctx, R.color.veil_accent));
                    row.addView(ivCheck);
                }

                row.setOnClickListener(v -> {
                    activeBucketId[0] = bucket.id;
                    if (tvCurrentAlbum != null) tvCurrentAlbum.setText(bucket.name);
                    hideAlbumDropdown.run();
                    applyBucketFilter.run();
                });
                llAlbumDropdownContainer.addView(row);
            }

            // "More apps" (Files, +3 -> opens Screenshot 5.jpg submenu)
            LinearLayout moreAppsRow = new LinearLayout(ctx);
            moreAppsRow.setOrientation(LinearLayout.HORIZONTAL);
            moreAppsRow.setGravity(Gravity.CENTER_VERTICAL);
            moreAppsRow.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
            if (rippleRes != 0) moreAppsRow.setBackgroundResource(rippleRes);

            FrameLayout moreBox = new FrameLayout(ctx);
            moreBox.setLayoutParams(new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp)));
            GradientDrawable mBg = new GradientDrawable();
            mBg.setCornerRadius(10 * dp);
            mBg.setColor(ContextCompat.getColor(ctx, R.color.colorSurfaceVariant));
            moreBox.setBackground(mBg);
            ImageView ivMore = new ImageView(ctx);
            ivMore.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
            ivMore.setImageResource(R.drawable.ic_folder_multi);
            ivMore.setColorFilter(ContextCompat.getColor(ctx, R.color.veil_accent));
            moreBox.addView(ivMore);
            moreAppsRow.addView(moreBox);

            LinearLayout moreCol = new LinearLayout(ctx);
            moreCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mclp.leftMargin = Math.round(14 * dp);
            moreCol.setLayoutParams(mclp);
            TextView tvMore = new TextView(ctx);
            tvMore.setText("More apps");
            tvMore.setTextSize(15.5f);
            tvMore.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvMore.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
            moreCol.addView(tvMore);
            TextView tvMoreSub = new TextView(ctx);
            tvMoreSub.setText("Files, +2");
            tvMoreSub.setTextSize(12.5f);
            tvMoreSub.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            moreCol.addView(tvMoreSub);
            moreAppsRow.addView(moreCol);

            ImageView ivArrow = new ImageView(ctx);
            ivArrow.setLayoutParams(new LinearLayout.LayoutParams(Math.round(18 * dp), Math.round(18 * dp)));
            ivArrow.setImageResource(R.drawable.ic_chevron_right);
            ivArrow.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            moreAppsRow.addView(ivArrow);

            moreAppsRow.setOnClickListener(v -> populateDropdownHolder[0].accept(true));
            llAlbumDropdownContainer.addView(moreAppsRow);

            // "See more" row
            if (!showingAllAlbumsInDropdown[0] && bucketsList.size() > 5) {
                LinearLayout seeMoreRow = new LinearLayout(ctx);
                seeMoreRow.setOrientation(LinearLayout.HORIZONTAL);
                seeMoreRow.setGravity(Gravity.CENTER_VERTICAL);
                seeMoreRow.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
                if (rippleRes != 0) seeMoreRow.setBackgroundResource(rippleRes);

                FrameLayout seeBox = new FrameLayout(ctx);
                seeBox.setLayoutParams(new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp)));
                seeBox.setBackground(mBg);
                ImageView ivSee = new ImageView(ctx);
                ivSee.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
                ivSee.setImageResource(R.drawable.ic_folder_multi);
                ivSee.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
                seeBox.addView(ivSee);
                seeMoreRow.addView(seeBox);

                TextView tvSee = new TextView(ctx);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                slp.leftMargin = Math.round(14 * dp);
                tvSee.setLayoutParams(slp);
                tvSee.setText("See more");
                tvSee.setTextSize(15.5f);
                tvSee.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tvSee.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
                seeMoreRow.addView(tvSee);

                seeMoreRow.setOnClickListener(v -> {
                    showingAllAlbumsInDropdown[0] = true;
                    populateDropdownHolder[0].accept(false);
                });
                llAlbumDropdownContainer.addView(seeMoreRow);
            }
        };

        if (layoutAlbumSelector != null) {
            layoutAlbumSelector.setOnClickListener(v -> {
                if (cardAlbumDropdown != null && cardAlbumDropdown.getVisibility() == View.VISIBLE) {
                    hideAlbumDropdown.run();
                } else {
                    showingAllAlbumsInDropdown[0] = false;
                    populateDropdownHolder[0].accept(false);
                    if (cardAlbumDropdown != null) cardAlbumDropdown.setVisibility(View.VISIBLE);
                    if (viewDropdownScrim != null) viewDropdownScrim.setVisibility(View.VISIBLE);
                    if (ivAlbumChevron != null) ivAlbumChevron.animate().rotation(180f).setDuration(180).start();
                }
            });
        }

        if (viewDropdownScrim != null) {
            viewDropdownScrim.setOnClickListener(v -> hideAlbumDropdown.run());
        }

        if (fabMultiOrFolder != null) {
            fabMultiOrFolder.setOnClickListener(v -> {
                hideAlbumDropdown.run();
                com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.launchExternalSourcePicker(
                        ctx, "File Manager", true, (uris, types) -> {
                            if (uris == null || uris.isEmpty()) return;
                            if (isLayoutMode[0]) {
                                showLayoutCollageEditorDialog(ctx, uris, sheet, listener);
                            } else {
                                sheet.dismiss();
                                launchStatusMediaEditor(ctx, uris.get(0),
                                        types != null && !types.isEmpty() ? types.get(0) : "image",
                                        uris, types);
                            }
                        });
            });
            fabMultiOrFolder.setOnLongClickListener(v -> {
                boolean nextRing = !adapter.isAlwaysShowSelectionRing();
                adapter.setAlwaysShowSelectionRing(nextRing);
                Toast.makeText(ctx, nextRing ? "Multi-select enabled" : "Single-tap mode", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        Runnable loadDeviceAlbumsAction = () -> {
            if (!com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.checkStoragePermission(ctx)) return;
            if (layoutPerm != null) layoutPerm.setVisibility(View.GONE);
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            new Thread(() -> {
                List<com.m3.veil.model.GalleryMediaItem> queried =
                        com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.queryAllDeviceMedia(ctx.getContentResolver(), false);
                Map<String, com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.AlbumBucket> buckets =
                        com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.buildAlbumBuckets(queried);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    allItems.clear();
                    allItems.addAll(queried);
                    albumBuckets.clear();
                    albumBuckets.putAll(buckets);
                    applyBucketFilter.run();
                });
            }).start();
        };

        if (btnBrowseFiles != null) {
            btnBrowseFiles.setOnClickListener(v -> {
                if (!com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.checkStoragePermission(ctx)) {
                    com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.requestPermissionsFromContext(
                            ctx, false, loadDeviceAlbumsAction);
                } else {
                    com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.launchExternalSourcePicker(
                            ctx, "Files", true, (uris, types) -> {
                                if (uris == null || uris.isEmpty()) return;
                                if (isLayoutMode[0]) {
                                    showLayoutCollageEditorDialog(ctx, uris, sheet, listener);
                                } else {
                                    sheet.dismiss();
                                    launchStatusMediaEditor(ctx, uris.get(0),
                                            types != null && !types.isEmpty() ? types.get(0) : "image",
                                            uris, types);
                                }
                            });
                }
            });
        }

        updateModeUi.run();

        // Load device albums & media asynchronously
        if (com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.checkStoragePermission(ctx)) {
            loadDeviceAlbumsAction.run();
        } else {
            displayedItems.clear();
            displayedItems.add(com.m3.veil.model.GalleryMediaItem.createCameraTile());
            adapter.notifyDataSetChanged();
            if (layoutPerm != null) layoutPerm.setVisibility(View.VISIBLE);
            com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.requestPermissionsFromContext(
                    ctx, false, loadDeviceAlbumsAction);
        }

        sheet.show();
    }

    public static void launchStatusMediaEditor(Context ctx, Uri primaryUri, String primaryType,
                                               ArrayList<Uri> batchUris, ArrayList<String> batchTypes) {
        if (ctx == null || primaryUri == null) return;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String resolvedType = primaryType != null ? primaryType
                    : com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.SystemPickerBridgeFragment.detectMediaType(cr, primaryUri);
            if (isVideoStatusUri(primaryUri.toString())) {
                resolvedType = "video";
            }
            Uri safePrimary = com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.SystemPickerBridgeFragment
                    .copyContentUriToLocalFileIfNeeded(ctx, cr, primaryUri, resolvedType);

            android.content.Intent editIntent = new android.content.Intent(ctx, com.m3.veil.ui.chat.MediaEditActivity.class);
            editIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            editIntent.putExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_MEDIA_URI, safePrimary != null ? safePrimary : primaryUri);
            editIntent.putExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_MEDIA_TYPE, resolvedType);
            editIntent.putExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_IS_STATUS_MODE, true);
            editIntent.putExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_RECIPIENT_NAME, getFormattedStatusAudience(ctx));
            if (batchUris != null && batchUris.size() > 1) {
                ArrayList<Uri> safeBatchUris = new ArrayList<>();
                ArrayList<String> safeBatchTypes = new ArrayList<>();
                for (int i = 0; i < batchUris.size(); i++) {
                    Uri bu = batchUris.get(i);
                    String bt = (batchTypes != null && i < batchTypes.size()) ? batchTypes.get(i)
                            : com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.SystemPickerBridgeFragment.detectMediaType(cr, bu);
                    if (bu != null && isVideoStatusUri(bu.toString())) bt = "video";
                    Uri sbu = com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.SystemPickerBridgeFragment
                            .copyContentUriToLocalFileIfNeeded(ctx, cr, bu, bt);
                    safeBatchUris.add(sbu != null ? sbu : bu);
                    safeBatchTypes.add(bt);
                }
                editIntent.putParcelableArrayListExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_BATCH_URIS, safeBatchUris);
                editIntent.putStringArrayListExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_BATCH_TYPES, safeBatchTypes);
            }
            ctx.startActivity(editIntent);
        } catch (Exception ignored) {}
    }

    public static StatusController.PrivacyAcl resolveConfiguredPrivacyAcl(Context ctx) {
        if (ctx == null) return StatusController.PrivacyAcl.ALL_CONTACTS;
        com.m3.veil.utils.UserPreferences up = com.m3.veil.utils.UserPreferences.getInstance(ctx);
        String aType = up.getStatusAudienceType();
        if ("close_friends".equals(aType)) {
            return StatusController.PrivacyAcl.CLOSE_FRIENDS;
        } else if ("included".equals(aType) || "excluded".equals(aType)) {
            return StatusController.PrivacyAcl.SELECTED_PEERS;
        }
        return StatusController.PrivacyAcl.ALL_CONTACTS;
    }

    public static List<String> resolveConfiguredTargetPeers(Context ctx, List<String> mentionIds) {
        if (ctx == null) return mentionIds;
        com.m3.veil.utils.UserPreferences up = com.m3.veil.utils.UserPreferences.getInstance(ctx);
        String aType = up.getStatusAudienceType();
        List<String> targetPeers = (mentionIds != null && !mentionIds.isEmpty()) ? new ArrayList<>(mentionIds) : null;

        if ("included".equals(aType) || "close_friends".equals(aType)) {
            java.util.Set<String> set = "close_friends".equals(aType)
                    ? up.getStatusAudienceCloseFriends() : up.getStatusAudienceIncluded();
            if (targetPeers == null) targetPeers = new ArrayList<>(set);
            else {
                for (String p : set) {
                    if (!targetPeers.contains(p)) targetPeers.add(p);
                }
            }
        } else if ("excluded".equals(aType)) {
            java.util.Set<String> excluded = up.getStatusAudienceExcluded();
            List<com.m3.veil.model.Chat> allChats =
                    com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getAllChats();
            List<String> allowed = new ArrayList<>();
            if (allChats != null) {
                for (com.m3.veil.model.Chat c : allChats) {
                    if (c != null && c.getId() != null && !excluded.contains(c.getId())) {
                        allowed.add(c.getId());
                    }
                }
            }
            if (targetPeers == null) targetPeers = allowed;
            else {
                for (String p : allowed) {
                    if (!targetPeers.contains(p)) targetPeers.add(p);
                }
            }
        }
        return targetPeers;
    }

    /**
     * Direct helper called by MediaEditActivity when finishing in Status Mode so statuses
     * (single photo/video, multi-photo batch, or Layout collage) are always published immediately.
     */
    public static void publishStatusFromMediaEdit(Context ctx, String primaryPath, String primaryType,
                                                  ArrayList<String> batchPaths, ArrayList<String> batchTypes,
                                                  String caption, List<String> mentionIds, List<String> mentionNames) {
        if (ctx == null) return;
        String safeCaption = caption != null ? caption : "";
        StatusController.PrivacyAcl acl = resolveConfiguredPrivacyAcl(ctx);
        List<String> targetPeers = resolveConfiguredTargetPeers(ctx, mentionIds);

        List<String> pathsToPost = new ArrayList<>();
        List<String> typesToPost = new ArrayList<>();
        if (batchPaths != null && !batchPaths.isEmpty()) {
            pathsToPost.addAll(batchPaths);
            if (batchTypes != null) typesToPost.addAll(batchTypes);
        } else if (primaryPath != null && !primaryPath.isEmpty()) {
            pathsToPost.add(primaryPath);
            typesToPost.add(primaryType != null ? primaryType : "image");
        }

        for (int i = 0; i < pathsToPost.size(); i++) {
            String path = pathsToPost.get(i);
            String slideCaption = (i == 0) ? safeCaption : "";
            VeilFeatureManager.getInstance(ctx).addMyMediaStatus(path, slideCaption, "#1E293B", mentionNames);
        }
        Toast.makeText(ctx, "Status shared", Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // LAYOUT COLLAGE STUDIO (Screenshots 7.jpg & 8.jpg â€” 6 Grid Variants)
    // =====================================================================

    public static void showLayoutCollageEditorDialog(Context ctx, List<Uri> photoUris,
                                                     Dialog parentSheet, StatusActionListener listener) {
        if (ctx == null || photoUris == null || photoUris.isEmpty()) return;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final float dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B141A"));
        root.setPadding(Math.round(12 * dp), Math.round(32 * dp), Math.round(12 * dp), Math.round(24 * dp));

        // 1. Top Bar: Circular Back Button on Left + "Done" Pill Button on Right (Screenshot 7.jpg)
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52 * dp)));
        topBar.setPadding(Math.round(4 * dp), 0, Math.round(4 * dp), 0);

        FrameLayout btnBack = new FrameLayout(ctx);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * dp), Math.round(42 * dp)));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(Color.parseColor("#26FFFFFF"));
        btnBack.setBackground(backBg);
        ImageView ivBack = new ImageView(ctx);
        ivBack.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
        ivBack.setImageResource(R.drawable.ic_arrow_back);
        ivBack.setColorFilter(Color.WHITE);
        btnBack.addView(ivBack);
        btnBack.setOnClickListener(v -> dialog.dismiss());
        topBar.addView(btnBack);

        View topSpacer = new View(ctx);
        topSpacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topBar.addView(topSpacer);

        TextView btnDone = new TextView(ctx);
        btnDone.setText("Done");
        btnDone.setTextColor(Color.parseColor("#0B141A"));
        btnDone.setTextSize(15f);
        btnDone.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnDone.setGravity(Gravity.CENTER);
        btnDone.setPadding(Math.round(22 * dp), Math.round(9 * dp), Math.round(22 * dp), Math.round(9 * dp));
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setCornerRadius(22 * dp);
        doneBg.setColor(Color.WHITE);
        btnDone.setBackground(doneBg);
        topBar.addView(btnDone);

        root.addView(topBar);

        // 2. Center Interactive Collage Canvas View (9:16 rounded card with dividers)
        FrameLayout canvasHolder = new FrameLayout(ctx);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        chLp.topMargin = Math.round(12 * dp);
        chLp.bottomMargin = Math.round(16 * dp);
        canvasHolder.setLayoutParams(chLp);

        final CollageCanvasView collageView = new CollageCanvasView(ctx, dp);
        FrameLayout.LayoutParams cvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        collageView.setLayoutParams(cvLp);
        canvasHolder.addView(collageView);
        root.addView(canvasHolder);

        // Load selected bitmaps asynchronously
        new Thread(() -> {
            List<Bitmap> loaded = new ArrayList<>();
            for (Uri u : photoUris) {
                try (InputStream is = ctx.getContentResolver().openInputStream(u)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 2;
                    Bitmap b = BitmapFactory.decodeStream(is, null, opts);
                    if (b != null) loaded.add(b);
                } catch (Exception ignored) {}
            }
            new Handler(Looper.getMainLooper()).post(() -> collageView.setBitmaps(loaded));
        }).start();

        // 3. Bottom Bar: 6 Layout Grid Variant Selector Buttons (Screenshot 7.jpg)
        LinearLayout variantsBar = new LinearLayout(ctx);
        variantsBar.setOrientation(LinearLayout.HORIZONTAL);
        variantsBar.setGravity(Gravity.CENTER);
        variantsBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(58 * dp)));

        final int[] activeVariant = {2}; // Default to Variant 2 (Hero top + split bottom matching 7.jpg)
        collageView.setVariantIndex(activeVariant[0]);
        final List<LayoutVariantIconView> variantIcons = new ArrayList<>();

        Runnable refreshVariantButtons = () -> {
            for (int i = 0; i < variantIcons.size(); i++) {
                variantIcons.get(i).setSelectedVariant(i == activeVariant[0]);
            }
        };

        for (int v = 0; v < 6; v++) {
            final int varIdx = v;
            LayoutVariantIconView iconView = new LayoutVariantIconView(ctx, dp, Math.max(2, photoUris.size()), varIdx);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp));
            ilp.leftMargin = Math.round(6 * dp);
            ilp.rightMargin = Math.round(6 * dp);
            iconView.setLayoutParams(ilp);
            iconView.setOnClickListener(view -> {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                activeVariant[0] = varIdx;
                collageView.setVariantIndex(varIdx);
                refreshVariantButtons.run();
            });
            variantIcons.add(iconView);
            variantsBar.addView(iconView);
        }
        refreshVariantButtons.run();
        root.addView(variantsBar);

        // "Done" exports the collage to a high-res image and opens MediaEditActivity (Screenshot 8.jpg)
        btnDone.setOnClickListener(v -> {
            Bitmap exported = collageView.exportCollageBitmap(1080, 1920);
            if (exported == null) {
                Toast.makeText(ctx, "Preparing layout...", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                File dir = new File(ctx.getCacheDir(), "status_layouts");
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, "veil_layout_" + System.currentTimeMillis() + ".jpg");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                    exported.compress(Bitmap.CompressFormat.JPEG, 94, fos);
                }
                Uri outUri = Uri.fromFile(outFile);
                dialog.dismiss();
                if (parentSheet != null && parentSheet.isShowing()) {
                    parentSheet.dismiss();
                }
                ArrayList<Uri> single = new ArrayList<>();
                single.add(outUri);
                ArrayList<String> types = new ArrayList<>();
                types.add("image");
                launchStatusMediaEditor(ctx, outUri, "image", single, types);
            } catch (Exception e) {
                Toast.makeText(ctx, "Could not export layout", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.setContentView(root);
        dialog.show();
    }

    /**
     * Computes normalized [0..1] cell rectangles for count (1..6) and variant (0..5).
     */
    private static List<android.graphics.RectF> computeCollageCells(int count, int variant) {
        List<android.graphics.RectF> cells = new ArrayList<>();
        int n = Math.max(1, Math.min(6, count));
        int v = Math.abs(variant) % 6;

        if (n == 1) {
            cells.add(new android.graphics.RectF(0f, 0f, 1f, 1f));
            return cells;
        }

        if (v == 1) {
            // Variant 1: Horizontal stacked rows (Icon 2 in 7.jpg)
            float step = 1f / n;
            for (int i = 0; i < n; i++) {
                cells.add(new android.graphics.RectF(0f, i * step, 1f, (i + 1) * step));
            }
            return cells;
        }

        if (n == 2) {
            if (v == 0 || v == 3) {
                cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.5f));
                cells.add(new android.graphics.RectF(0f, 0.5f, 1f, 1f));
            } else if (v == 2) {
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0f, 1f, 1f));
            } else if (v == 4) {
                cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.65f));
                cells.add(new android.graphics.RectF(0f, 0.65f, 1f, 1f));
            } else {
                cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.35f));
                cells.add(new android.graphics.RectF(0f, 0.35f, 1f, 1f));
            }
            return cells;
        }

        if (n == 3) {
            if (v == 2) {
                // Hero top + 2 bottom (Active layout in Screenshot 7.jpg!)
                cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.5f));
                cells.add(new android.graphics.RectF(0f, 0.5f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0.5f, 1f, 1f));
            } else if (v == 0) {
                // Left hero + 2 right stacked (Icon 1 in 7.jpg)
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0f, 1f, 0.5f));
                cells.add(new android.graphics.RectF(0.5f, 0.5f, 1f, 1f));
            } else if (v == 3) {
                // 2 top + Hero bottom (Icon 4 in 7.jpg)
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 0.5f));
                cells.add(new android.graphics.RectF(0.5f, 0f, 1f, 0.5f));
                cells.add(new android.graphics.RectF(0f, 0.5f, 1f, 1f));
            } else if (v == 4) {
                // 2 left stacked + Right hero (Icon 5 in 7.jpg)
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 0.5f));
                cells.add(new android.graphics.RectF(0f, 0.5f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0f, 1f, 1f));
            } else {
                // 3 vertical columns (Icon 6 in 7.jpg)
                cells.add(new android.graphics.RectF(0f, 0f, 0.333f, 1f));
                cells.add(new android.graphics.RectF(0.333f, 0f, 0.666f, 1f));
                cells.add(new android.graphics.RectF(0.666f, 0f, 1f, 1f));
            }
            return cells;
        }

        if (n == 4) {
            if (v == 0 || v == 2) {
                // 2x2 Grid
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 0.5f));
                cells.add(new android.graphics.RectF(0.5f, 0f, 1f, 0.5f));
                cells.add(new android.graphics.RectF(0f, 0.5f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0.5f, 1f, 1f));
            } else if (v == 3) {
                // 1 Top + 3 Bottom
                cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.55f));
                for (int i = 0; i < 3; i++) {
                    cells.add(new android.graphics.RectF(i / 3f, 0.55f, (i + 1) / 3f, 1f));
                }
            } else if (v == 4) {
                // 1 Left + 3 Right stacked
                cells.add(new android.graphics.RectF(0f, 0f, 0.55f, 1f));
                for (int i = 0; i < 3; i++) {
                    cells.add(new android.graphics.RectF(0.55f, i / 3f, 1f, (i + 1) / 3f));
                }
            } else {
                // 3 Top + 1 Bottom
                for (int i = 0; i < 3; i++) {
                    cells.add(new android.graphics.RectF(i / 3f, 0f, (i + 1) / 3f, 0.45f));
                }
                cells.add(new android.graphics.RectF(0f, 0.45f, 1f, 1f));
            }
            return cells;
        }

        if (n == 5) {
            if (v == 0 || v == 2) {
                // 2 Top + 3 Bottom
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 0.5f));
                cells.add(new android.graphics.RectF(0.5f, 0f, 1f, 0.5f));
                for (int i = 0; i < 3; i++) {
                    cells.add(new android.graphics.RectF(i / 3f, 0.5f, (i + 1) / 3f, 1f));
                }
            } else if (v == 3) {
                // 3 Top + 2 Bottom
                for (int i = 0; i < 3; i++) {
                    cells.add(new android.graphics.RectF(i / 3f, 0f, (i + 1) / 3f, 0.5f));
                }
                cells.add(new android.graphics.RectF(0f, 0.5f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0.5f, 1f, 1f));
            } else if (v == 4) {
                // 1 Top Hero + 2x2 Bottom
                cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.44f));
                cells.add(new android.graphics.RectF(0f, 0.44f, 0.5f, 0.72f));
                cells.add(new android.graphics.RectF(0.5f, 0.44f, 1f, 0.72f));
                cells.add(new android.graphics.RectF(0f, 0.72f, 0.5f, 1f));
                cells.add(new android.graphics.RectF(0.5f, 0.72f, 1f, 1f));
            } else {
                // 1 Left Hero + 4 Right stacked
                cells.add(new android.graphics.RectF(0f, 0f, 0.5f, 1f));
                for (int i = 0; i < 4; i++) {
                    cells.add(new android.graphics.RectF(0.5f, i / 4f, 1f, (i + 1) / 4f));
                }
            }
            return cells;
        }

        // n == 6
        if (v == 0 || v == 2) {
            // 2 Columns x 3 Rows
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 2; c++) {
                    cells.add(new android.graphics.RectF(c * 0.5f, r / 3f, (c + 1) * 0.5f, (r + 1) / 3f));
                }
            }
        } else if (v == 3) {
            // 3 Columns x 2 Rows
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 3; c++) {
                    cells.add(new android.graphics.RectF(c / 3f, r * 0.5f, (c + 1) / 3f, (r + 1) * 0.5f));
                }
            }
        } else if (v == 4) {
            // 1 Top Hero + 2 Middle + 3 Bottom
            cells.add(new android.graphics.RectF(0f, 0f, 1f, 0.4f));
            cells.add(new android.graphics.RectF(0f, 0.4f, 0.5f, 0.7f));
            cells.add(new android.graphics.RectF(0.5f, 0.4f, 1f, 0.7f));
            for (int c = 0; c < 3; c++) {
                cells.add(new android.graphics.RectF(c / 3f, 0.7f, (c + 1) / 3f, 1f));
            }
        } else {
            // 3 Top + 2 Middle + 1 Bottom Hero
            for (int c = 0; c < 3; c++) {
                cells.add(new android.graphics.RectF(c / 3f, 0f, (c + 1) / 3f, 0.3f));
            }
            cells.add(new android.graphics.RectF(0f, 0.3f, 0.5f, 0.6f));
            cells.add(new android.graphics.RectF(0.5f, 0.3f, 1f, 0.6f));
            cells.add(new android.graphics.RectF(0f, 0.6f, 1f, 1f));
        }
        return cells;
    }

    private static class CollageCanvasView extends View {
        private final float dp;
        private final List<Bitmap> bitmaps = new ArrayList<>();
        private int variantIndex = 2;
        private int selectedSwapIndex = -1;
        private final android.graphics.Paint dividerPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint bitmapPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Paint highlightPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clipPath = new android.graphics.Path();

        CollageCanvasView(Context ctx, float dp) {
            super(ctx);
            this.dp = dp;
            dividerPaint.setColor(Color.parseColor("#0B141A"));
            dividerPaint.setStyle(android.graphics.Paint.Style.STROKE);
            dividerPaint.setStrokeWidth(2.5f * dp);

            highlightPaint.setColor(Color.parseColor("#6600A884"));
            highlightPaint.setStyle(android.graphics.Paint.Style.FILL);
        }

        void setBitmaps(List<Bitmap> list) {
            bitmaps.clear();
            if (list != null) bitmaps.addAll(list);
            invalidate();
        }

        void setVariantIndex(int idx) {
            this.variantIndex = idx;
            this.selectedSwapIndex = -1;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            drawCollageInternal(canvas, getWidth(), getHeight(), true);
        }

        private void drawCollageInternal(android.graphics.Canvas canvas, int w, int h, boolean interactive) {
            if (w <= 0 || h <= 0 || bitmaps.isEmpty()) return;
            int saveAll = canvas.save();
            clipPath.reset();
            float radius = 18f * dp;
            clipPath.addRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clipPath);

            List<android.graphics.RectF> normCells = computeCollageCells(bitmaps.size(), variantIndex);
            for (int i = 0; i < normCells.size() && i < bitmaps.size(); i++) {
                android.graphics.RectF nc = normCells.get(i);
                android.graphics.RectF dst = new android.graphics.RectF(
                        nc.left * w, nc.top * h, nc.right * w, nc.bottom * h);
                Bitmap bmp = bitmaps.get(i);
                if (bmp != null && !bmp.isRecycled()) {
                    int cs = canvas.save();
                    canvas.clipRect(dst);
                    drawCenterCropBitmap(canvas, bmp, dst);
                    if (interactive && i == selectedSwapIndex) {
                        canvas.drawRect(dst, highlightPaint);
                    }
                    canvas.restoreToCount(cs);
                }
                canvas.drawRect(dst, dividerPaint);
            }
            canvas.restoreToCount(saveAll);
        }

        private void drawCenterCropBitmap(android.graphics.Canvas canvas, Bitmap bmp, android.graphics.RectF dst) {
            float bw = bmp.getWidth();
            float bh = bmp.getHeight();
            float dw = dst.width();
            float dh = dst.height();
            if (bw <= 0 || bh <= 0 || dw <= 0 || dh <= 0) return;
            float scale = Math.max(dw / bw, dh / bh);
            float sw = bw * scale;
            float sh = bh * scale;
            float dx = dst.left + (dw - sw) * 0.5f;
            float dy = dst.top + (dh - sh) * 0.5f;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postScale(scale, scale);
            m.postTranslate(dx, dy);
            canvas.drawBitmap(bmp, m, bitmapPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && !bitmaps.isEmpty()) {
                float x = event.getX() / Math.max(1f, getWidth());
                float y = event.getY() / Math.max(1f, getHeight());
                List<android.graphics.RectF> normCells = computeCollageCells(bitmaps.size(), variantIndex);
                for (int i = 0; i < normCells.size(); i++) {
                    if (normCells.get(i).contains(x, y)) {
                        if (selectedSwapIndex == -1) {
                            selectedSwapIndex = i;
                        } else if (selectedSwapIndex == i) {
                            selectedSwapIndex = -1;
                        } else {
                            java.util.Collections.swap(bitmaps, selectedSwapIndex, i);
                            selectedSwapIndex = -1;
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        }
                        invalidate();
                        break;
                    }
                }
            }
            return true;
        }

        Bitmap exportCollageBitmap(int targetW, int targetH) {
            if (bitmaps.isEmpty()) return null;
            Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawColor(Color.parseColor("#0B141A"));
            drawCollageInternal(c, targetW, targetH, false);
            return out;
        }
    }

    private static class LayoutVariantIconView extends View {
        private final float dp;
        private final int count;
        private final int variant;
        private boolean selectedVariant = false;
        private final android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        LayoutVariantIconView(Context ctx, float dp, int count, int variant) {
            super(ctx);
            this.dp = dp;
            this.count = count;
            this.variant = variant;
            strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.6f * dp);
        }

        void setSelectedVariant(boolean sel) {
            this.selectedVariant = sel;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float r = Math.min(w, h) * (selectedVariant ? 0.48f : 0.42f);

            bgPaint.setColor(selectedVariant ? Color.WHITE : Color.parseColor("#22FFFFFF"));
            canvas.drawCircle(cx, cy, r, bgPaint);

            strokePaint.setColor(selectedVariant ? Color.parseColor("#0B141A") : Color.WHITE);
            float boxSize = 18f * dp;
            float left = cx - boxSize * 0.5f;
            float top = cy - boxSize * 0.5f;
            android.graphics.RectF outer = new android.graphics.RectF(left, top, left + boxSize, top + boxSize);
            canvas.drawRoundRect(outer, 3f * dp, 3f * dp, strokePaint);

            List<android.graphics.RectF> cells = computeCollageCells(count, variant);
            for (android.graphics.RectF c : cells) {
                android.graphics.RectF cellRect = new android.graphics.RectF(
                        left + c.left * boxSize,
                        top + c.top * boxSize,
                        left + c.right * boxSize,
                        top + c.bottom * boxSize);
                canvas.drawRect(cellRect, strokePaint);
            }
        }
    }

    // =====================================================================
    // MUSIC STATUS STUDIO (Quick Mode #2 in Screenshot 2.jpg)
    // =====================================================================

    public static void showMusicStatusComposer(Context ctx, StatusActionListener listener,
                                               OnPhotoStatusRequestedListener photoListener) {
        if (ctx == null) return;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final float dp = ctx.getResources().getDisplayMetrics().density;
        final int[] colorIdx = {1};

        // Track item structure: [0]=title, [1]=artist/subtitle, [2]=icon, [3]=audioUriOrSynthFreq
        final List<String[]> trackItems = new ArrayList<>();
        trackItems.add(new String[]{"Midnight Synthwave", "VEIL Original \u2022 128 BPM", "\uD83C\uDFB5", "SYNTH:440"});
        trackItems.add(new String[]{"Lo-Fi Rain & Chill", "Ambient Beats \u2022 82 BPM", "\uD83C\uDF27\uFE0F", "SYNTH:330"});
        trackItems.add(new String[]{"Cyberpunk Horizon", "Neon Pulse \u2022 140 BPM", "\u26A1", "SYNTH:523"});
        trackItems.add(new String[]{"Acoustic Sunrise", "Warm Guitar \u2022 96 BPM", "\u2600\uFE0F", "SYNTH:392"});
        trackItems.add(new String[]{"Deep Focus Flow", "Minimal Piano \u2022 74 BPM", "\uD83C\uDFB9", "SYNTH:293"});

        final int[] selectedTrack = {0};
        final android.media.MediaPlayer[] previewPlayer = {null};
        final boolean[] isPlayingPreview = {false};

        Runnable stopPreviewPlayer = () -> {
            isPlayingPreview[0] = false;
            if (previewPlayer[0] != null) {
                try {
                    if (previewPlayer[0].isPlaying()) previewPlayer[0].stop();
                } catch (Throwable ignored) {}
                try {
                    previewPlayer[0].release();
                } catch (Throwable ignored) {}
                previewPlayer[0] = null;
            }
        };

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(parseColor(BG_COLORS[colorIdx[0]]));

        // Top Bar: Close & Palette
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        topBar.setPadding(Math.round(12 * dp), Math.round(36 * dp), Math.round(12 * dp), 0);

        FrameLayout btnClose = circleIconButton(ctx, dp, R.drawable.ic_close);
        btnClose.setOnClickListener(v -> {
            stopPreviewPlayer.run();
            dialog.dismiss();
        });
        topBar.addView(btnClose);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topBar.addView(spacer);

        FrameLayout btnPalette = circleIconButton(ctx, dp, R.drawable.ic_palette);
        btnPalette.setOnClickListener(v -> {
            colorIdx[0] = (colorIdx[0] + 1) % BG_COLORS.length;
            root.setBackgroundColor(parseColor(BG_COLORS[colorIdx[0]]));
        });
        topBar.addView(btnPalette);
        root.addView(topBar);

        // Center Music Card & Track Picker
        LinearLayout centerCol = new LinearLayout(ctx);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ccLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        ccLp.leftMargin = Math.round(20 * dp);
        ccLp.rightMargin = Math.round(20 * dp);
        centerCol.setLayoutParams(ccLp);

        // Glass Music Card with Live Play/Pause Button
        LinearLayout musicCard = new LinearLayout(ctx);
        musicCard.setOrientation(LinearLayout.HORIZONTAL);
        musicCard.setGravity(Gravity.CENTER_VERTICAL);
        musicCard.setPadding(Math.round(16 * dp), Math.round(14 * dp), Math.round(16 * dp), Math.round(14 * dp));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(24 * dp);
        cardBg.setColor(Color.parseColor("#4D000000"));
        cardBg.setStroke(Math.round(1 * dp), Color.parseColor("#4DFFFFFF"));
        musicCard.setBackground(cardBg);

        TextView tvArt = new TextView(ctx);
        tvArt.setLayoutParams(new LinearLayout.LayoutParams(Math.round(52 * dp), Math.round(52 * dp)));
        tvArt.setGravity(Gravity.CENTER);
        tvArt.setTextSize(24f);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(14 * dp);
        artBg.setColor(Color.parseColor("#33FFFFFF"));
        tvArt.setBackground(artBg);
        musicCard.addView(tvArt);

        LinearLayout trackInfoCol = new LinearLayout(ctx);
        trackInfoCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tilp.leftMargin = Math.round(14 * dp);
        tilp.rightMargin = Math.round(10 * dp);
        trackInfoCol.setLayoutParams(tilp);

        TextView tvTrackTitle = new TextView(ctx);
        tvTrackTitle.setTextColor(Color.WHITE);
        tvTrackTitle.setTextSize(16.5f);
        tvTrackTitle.setMaxLines(1);
        tvTrackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTrackTitle.setTypeface(null, Typeface.BOLD);
        trackInfoCol.addView(tvTrackTitle);

        TextView tvTrackArtist = new TextView(ctx);
        tvTrackArtist.setTextColor(Color.parseColor("#CCFFFFFF"));
        tvTrackArtist.setTextSize(13f);
        tvTrackArtist.setMaxLines(1);
        tvTrackArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackInfoCol.addView(tvTrackArtist);

        musicCard.addView(trackInfoCol);

        // Play / Pause Preview Button on the Music Card
        final TextView btnPlayToggle = new TextView(ctx);
        btnPlayToggle.setLayoutParams(new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));
        btnPlayToggle.setGravity(Gravity.CENTER);
        btnPlayToggle.setText("\u25B6");
        btnPlayToggle.setTextColor(Color.WHITE);
        btnPlayToggle.setTextSize(16f);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        btnPlayToggle.setBackground(playBg);
        musicCard.addView(btnPlayToggle);

        centerCol.addView(musicCard);

        // Prominent "Choose Music from Files / File Manager" Button
        LinearLayout btnBrowseMusicFiles = new LinearLayout(ctx);
        btnBrowseMusicFiles.setOrientation(LinearLayout.HORIZONTAL);
        btnBrowseMusicFiles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bmfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46 * dp));
        bmfLp.topMargin = Math.round(14 * dp);
        btnBrowseMusicFiles.setLayoutParams(bmfLp);
        GradientDrawable bmfBg = new GradientDrawable();
        bmfBg.setCornerRadius(23 * dp);
        bmfBg.setColor(Color.parseColor("#33FFFFFF"));
        bmfBg.setStroke(Math.round(1 * dp), Color.parseColor("#66FFFFFF"));
        btnBrowseMusicFiles.setBackground(bmfBg);

        ImageView ivFolder = new ImageView(ctx);
        ivFolder.setLayoutParams(new LinearLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp)));
        ivFolder.setImageResource(R.drawable.ic_folder_multi);
        ivFolder.setColorFilter(Color.WHITE);
        btnBrowseMusicFiles.addView(ivFolder);

        TextView tvBrowseMusic = new TextView(ctx);
        LinearLayout.LayoutParams tbmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbmLp.leftMargin = Math.round(10 * dp);
        tvBrowseMusic.setLayoutParams(tbmLp);
        tvBrowseMusic.setText("Choose Music from Files / File Manager");
        tvBrowseMusic.setTextColor(Color.WHITE);
        tvBrowseMusic.setTextSize(14f);
        tvBrowseMusic.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnBrowseMusicFiles.addView(tvBrowseMusic);
        centerCol.addView(btnBrowseMusicFiles);

        // Track List Container inside ScrollView
        TextView tvPickHint = new TextView(ctx);
        tvPickHint.setText("Device Music & Curated Tracks (Tap to preview)");
        tvPickHint.setTextColor(Color.parseColor("#CCFFFFFF"));
        tvPickHint.setTextSize(12.5f);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phLp.topMargin = Math.round(12 * dp);
        phLp.bottomMargin = Math.round(8 * dp);
        tvPickHint.setLayoutParams(phLp);
        centerCol.addView(tvPickHint);

        android.widget.ScrollView trackScroll = new android.widget.ScrollView(ctx);
        trackScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(210 * dp)));

        LinearLayout trackList = new LinearLayout(ctx);
        trackList.setOrientation(LinearLayout.VERTICAL);
        trackList.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        trackScroll.addView(trackList);
        centerCol.addView(trackScroll);

        Runnable playSelectedTrackPreview = () -> {
            stopPreviewPlayer.run();
            if (selectedTrack[0] < 0 || selectedTrack[0] >= trackItems.size()) return;
            String[] item = trackItems.get(selectedTrack[0]);
            String sourceSpec = item[3];
            try {
                File audioFile;
                if (sourceSpec != null && sourceSpec.startsWith("SYNTH:")) {
                    int freq = Integer.parseInt(sourceSpec.substring(6));
                    audioFile = ensureSynthTrackWavFile(ctx, freq, selectedTrack[0]);
                } else {
                    audioFile = copyAudioUriToStatusFile(ctx, Uri.parse(sourceSpec));
                }
                if (audioFile != null && audioFile.exists()) {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    mp.setDataSource(audioFile.getAbsolutePath());
                    mp.setLooping(true);
                    mp.prepare();
                    mp.start();
                    previewPlayer[0] = mp;
                    isPlayingPreview[0] = true;
                    btnPlayToggle.setText("\u23F8");
                }
            } catch (Throwable e) {
                btnPlayToggle.setText("\u25B6");
            }
        };

        final Runnable[] rebuildTrackRowsHolder = new Runnable[1];
        Runnable updateCard = () -> {
            if (selectedTrack[0] >= 0 && selectedTrack[0] < trackItems.size()) {
                String[] cur = trackItems.get(selectedTrack[0]);
                tvArt.setText(cur[2]);
                tvTrackTitle.setText(cur[0]);
                tvTrackArtist.setText(cur[1]);
            }
            if (rebuildTrackRowsHolder[0] != null) rebuildTrackRowsHolder[0].run();
        };

        rebuildTrackRowsHolder[0] = () -> {
            trackList.removeAllViews();
            for (int i = 0; i < trackItems.size(); i++) {
                final int idx = i;
                String[] tr = trackItems.get(i);
                boolean isSel = (idx == selectedTrack[0]);
                TextView row = new TextView(ctx);
                row.setText(tr[2] + "  " + tr[0] + " \u00B7 " + tr[1] + (isSel ? "   \u2713" : ""));
                row.setTextColor(Color.WHITE);
                row.setTextSize(13.5f);
                row.setTypeface(null, isSel ? Typeface.BOLD : Typeface.NORMAL);
                row.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
                GradientDrawable rBg = new GradientDrawable();
                rBg.setCornerRadius(14 * dp);
                rBg.setColor(Color.parseColor(isSel ? "#55000000" : "#26000000"));
                if (isSel) rBg.setStroke(Math.round(1.2f * dp), Color.WHITE);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Math.round(6 * dp);
                row.setLayoutParams(rlp);
                row.setBackground(rBg);
                row.setOnClickListener(v -> {
                    selectedTrack[0] = idx;
                    updateCard.run();
                    playSelectedTrackPreview.run();
                });
                trackList.addView(row);
            }
        };
        updateCard.run();

        btnPlayToggle.setOnClickListener(v -> {
            if (isPlayingPreview[0]) {
                stopPreviewPlayer.run();
                btnPlayToggle.setText("\u25B6");
            } else {
                playSelectedTrackPreview.run();
            }
        });

        btnBrowseMusicFiles.setOnClickListener(v -> {
            stopPreviewPlayer.run();
            btnPlayToggle.setText("\u25B6");
            com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.launchExternalSourcePicker(
                    ctx, "Music", false, (uris, types) -> {
                        if (uris == null || uris.isEmpty()) return;
                        Uri pickedUri = uris.get(0);
                        String displayName = resolveAudioDisplayName(ctx, pickedUri);
                        trackItems.add(0, new String[]{
                                displayName,
                                "Selected from Device Files",
                                "\uD83C\uDFA7",
                                pickedUri.toString()
                        });
                        selectedTrack[0] = 0;
                        updateCard.run();
                        playSelectedTrackPreview.run();
                    });
        });

        // Query device MediaStore.Audio.Media in background so user's local MP3/M4A songs appear automatically
        new Thread(() -> {
            List<String[]> deviceSongs = queryDeviceAudioTracks(ctx);
            if (!deviceSongs.isEmpty()) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    trackItems.addAll(0, deviceSongs);
                    selectedTrack[0] = 0;
                    updateCard.run();
                });
            }
        }).start();

        root.addView(centerCol);

        // Bottom Bar: Audience Pill + Send FAB
        LinearLayout bottomBar = new LinearLayout(ctx);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams bbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomBar.setLayoutParams(bbLp);
        bottomBar.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), Math.round(28 * dp));

        View audiencePill = buildAudiencePillView(ctx, dp);
        bottomBar.addView(audiencePill);

        FrameLayout btnSend = new FrameLayout(ctx);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(Math.round(52 * dp), Math.round(52 * dp));
        bsLp.leftMargin = Math.round(12 * dp);
        btnSend.setLayoutParams(bsLp);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        btnSend.setBackground(sendBg);

        ImageView sendIcon = new ImageView(ctx);
        sendIcon.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
        sendIcon.setImageResource(R.drawable.ic_send);
        sendIcon.setColorFilter(Color.WHITE);
        btnSend.addView(sendIcon);

        btnSend.setOnClickListener(v -> {
            stopPreviewPlayer.run();
            String[] chosen = trackItems.get(selectedTrack[0]);
            String text = chosen[2] + " " + chosen[0] + "\n" + chosen[1];
            String sourceSpec = chosen[3];
            File savedAudio = null;
            try {
                if (sourceSpec != null && sourceSpec.startsWith("SYNTH:")) {
                    int freq = Integer.parseInt(sourceSpec.substring(6));
                    savedAudio = ensureSynthTrackWavFile(ctx, freq, selectedTrack[0]);
                } else if (sourceSpec != null) {
                    savedAudio = copyAudioUriToStatusFile(ctx, Uri.parse(sourceSpec));
                }
            } catch (Throwable ignored) {}

            if (savedAudio != null && savedAudio.exists()) {
                VeilFeatureManager.getInstance(ctx).addMyMediaStatus(
                        savedAudio.getAbsolutePath(), text, BG_COLORS[colorIdx[0]], null);
            } else {
                VeilFeatureManager.getInstance(ctx).addMyStatus(text, BG_COLORS[colorIdx[0]], 0);
            }
            Toast.makeText(ctx, "Music status shared", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (listener != null) listener.onStatusesChanged();
        });
        bottomBar.addView(btnSend);
        root.addView(bottomBar);

        dialog.setOnDismissListener(d -> stopPreviewPlayer.run());
        dialog.setContentView(root);
        dialog.show();
    }

    private static List<String[]> queryDeviceAudioTracks(Context ctx) {
        List<String[]> list = new ArrayList<>();
        if (ctx == null) return list;
        String[] proj = {
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.TITLE,
                android.provider.MediaStore.Audio.Media.ARTIST,
                android.provider.MediaStore.Audio.Media.DURATION
        };
        try (android.database.Cursor c = ctx.getContentResolver().query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                android.provider.MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                android.provider.MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID);
                int titleCol = c.getColumnIndex(android.provider.MediaStore.Audio.Media.TITLE);
                int artistCol = c.getColumnIndex(android.provider.MediaStore.Audio.Media.ARTIST);
                int count = 0;
                while (c.moveToNext() && count < 30) {
                    long id = c.getLong(idCol);
                    String title = titleCol != -1 ? c.getString(titleCol) : "Audio Track";
                    String artist = artistCol != -1 ? c.getString(artistCol) : "Device Music";
                    if (artist == null || artist.contains("<unknown>")) artist = "Device Audio";
                    Uri uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    list.add(new String[]{title != null ? title : "Track", artist, "\uD83C\uDFB5", uri.toString()});
                    count++;
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private static String resolveAudioDisplayName(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return "Selected Audio";
        try (android.database.Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        int dot = name.lastIndexOf('.');
                        return dot > 0 ? name.substring(0, dot) : name;
                    }
                }
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last != null && !last.isEmpty() ? last : "Selected Audio";
    }

    private static File copyAudioUriToStatusFile(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                File f = new File(uri.getPath());
                if (f.exists()) return f;
            }
            File dir = new File(ctx.getFilesDir(), "status_uploads");
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, "status_music_" + System.currentTimeMillis() + ".mp3");
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
            }
            return dest.exists() && dest.length() > 0 ? dest : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static File ensureSynthTrackWavFile(Context ctx, int baseFreqHz, int variant) {
        try {
            File dir = new File(ctx.getFilesDir(), "status_uploads");
            if (!dir.exists()) dir.mkdirs();
            File wav = new File(dir, "status_music_synth_" + baseFreqHz + "_" + variant + ".wav");
            if (wav.exists() && wav.length() > 1000) return wav;

            int sampleRate = 22050;
            int seconds = 6;
            int numSamples = sampleRate * seconds;
            byte[] pcm = new byte[numSamples * 2];
            double[] chordRatio = {1.0, 1.25, 1.5, 2.0};
            for (int i = 0; i < numSamples; i++) {
                double t = (double) i / sampleRate;
                int step = ((int) (t * 2.5)) % chordRatio.length;
                double freq = baseFreqHz * chordRatio[step];
                double env = Math.sin(Math.PI * ((t * 2.5) % 1.0));
                double sample = (Math.sin(2.0 * Math.PI * freq * t) * 0.65
                        + Math.sin(2.0 * Math.PI * (freq * 0.5) * t) * 0.35) * env;
                short val = (short) (sample * 14000);
                pcm[i * 2] = (byte) (val & 0xff);
                pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(wav)) {
                int byteRate = sampleRate * 2;
                int totalDataLen = pcm.length + 36;
                byte[] header = new byte[44];
                header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
                header[4] = (byte) (totalDataLen & 0xff);
                header[5] = (byte) ((totalDataLen >> 8) & 0xff);
                header[6] = (byte) ((totalDataLen >> 16) & 0xff);
                header[7] = (byte) ((totalDataLen >> 24) & 0xff);
                header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
                header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
                header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
                header[20] = 1; header[21] = 0;
                header[22] = 1; header[23] = 0;
                header[24] = (byte) (sampleRate & 0xff);
                header[25] = (byte) ((sampleRate >> 8) & 0xff);
                header[26] = (byte) ((sampleRate >> 16) & 0xff);
                header[27] = (byte) ((sampleRate >> 24) & 0xff);
                header[28] = (byte) (byteRate & 0xff);
                header[29] = (byte) ((byteRate >> 8) & 0xff);
                header[30] = (byte) ((byteRate >> 16) & 0xff);
                header[31] = (byte) ((byteRate >> 24) & 0xff);
                header[32] = 2; header[33] = 0;
                header[34] = 16; header[35] = 0;
                header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
                header[40] = (byte) (pcm.length & 0xff);
                header[41] = (byte) ((pcm.length >> 8) & 0xff);
                header[42] = (byte) ((pcm.length >> 16) & 0xff);
                header[43] = (byte) ((pcm.length >> 24) & 0xff);
                out.write(header);
                out.write(pcm);
            }
            return wav;
        } catch (Throwable e) {
            return null;
        }
    }

    public static String extractAudioDurationLabel(String contentText) {
        if (contentText != null) {
            int open = contentText.lastIndexOf('(');
            int close = contentText.lastIndexOf(')');
            if (open != -1 && close > open + 1) {
                String inside = contentText.substring(open + 1, close).trim();
                if (inside.contains(":")) return inside;
            }
        }
        return "Voice";
    }

    private static File ensureVoiceFallbackWavFile(Context ctx, int durationSec) {
        int sec = Math.max(2, Math.min(30, durationSec));
        try {
            File base = ctx.getExternalFilesDir(null) != null ? ctx.getExternalFilesDir(null) : ctx.getFilesDir();
            File dir = new File(base, "status_uploads");
            if (!dir.exists()) dir.mkdirs();
            File wav = new File(dir, "status_voice_" + System.currentTimeMillis() + ".wav");
            int sampleRate = 22050;
            int numSamples = sampleRate * sec;
            byte[] pcm = new byte[numSamples * 2];
            double[] notes = {261.63, 329.63, 392.00, 329.63};
            for (int i = 0; i < numSamples; i++) {
                double t = (double) i / sampleRate;
                double f = notes[((int) (t * 2.0)) % notes.length];
                double env = Math.sin(Math.PI * ((t * 2.0) % 1.0));
                short val = (short) (Math.sin(2.0 * Math.PI * f * t) * env * 12000);
                pcm[i * 2] = (byte) (val & 0xff);
                pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(wav)) {
                int byteRate = sampleRate * 2;
                int totalDataLen = pcm.length + 36;
                byte[] header = new byte[44];
                header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
                header[4] = (byte) (totalDataLen & 0xff);
                header[5] = (byte) ((totalDataLen >> 8) & 0xff);
                header[6] = (byte) ((totalDataLen >> 16) & 0xff);
                header[7] = (byte) ((totalDataLen >> 24) & 0xff);
                header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
                header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
                header[16] = 16; header[20] = 1; header[22] = 1;
                header[24] = (byte) (sampleRate & 0xff);
                header[25] = (byte) ((sampleRate >> 8) & 0xff);
                header[26] = (byte) ((sampleRate >> 16) & 0xff);
                header[27] = (byte) ((sampleRate >> 24) & 0xff);
                header[28] = (byte) (byteRate & 0xff);
                header[29] = (byte) ((byteRate >> 8) & 0xff);
                header[30] = (byte) ((byteRate >> 16) & 0xff);
                header[31] = (byte) ((byteRate >> 24) & 0xff);
                header[32] = 2; header[34] = 16;
                header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
                header[40] = (byte) (pcm.length & 0xff);
                header[41] = (byte) ((pcm.length >> 8) & 0xff);
                header[42] = (byte) ((pcm.length >> 16) & 0xff);
                header[43] = (byte) ((pcm.length >> 24) & 0xff);
                out.write(header);
                out.write(pcm);
            }
            return wav;
        } catch (Throwable e) {
            return ensureSynthTrackWavFile(ctx, 360, 9);
        }
    }

    // =====================================================================
    // VOICE STATUS COMPOSER (Screenshot 12.jpg â€” Full WhatsApp Parity)
    // =====================================================================

    public static void showVoiceStatusComposer(Context ctx, StatusActionListener listener) {
        showVoiceStatusComposer(ctx, listener, null);
    }

    public static void showVoiceStatusComposer(Context ctx, StatusActionListener listener,
                                               OnPhotoStatusRequestedListener photoListener) {
        if (ctx == null) return;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final float dp = ctx.getResources().getDisplayMetrics().density;
        final int[] colorIdx = {6}; // Sophisticated slate-taupe default matching Screenshot 12.jpg
        final Handler timerHandler = new Handler(Looper.getMainLooper());
        final android.media.MediaRecorder[] recorder = {null};
        final android.media.MediaPlayer[] previewPlayer = {null};
        final File[] recordedFile = {null};
        final long[] recordStartMs = {0L};
        final int[] recordedDurationSec = {0};

        final Runnable cleanupAudioResources = () -> {
            timerHandler.removeCallbacksAndMessages(null);
            if (recorder[0] != null) {
                try {
                    recorder[0].stop();
                } catch (Throwable ignored) {}
                try {
                    recorder[0].release();
                } catch (Throwable ignored) {}
                recorder[0] = null;
            }
            if (previewPlayer[0] != null) {
                try {
                    if (previewPlayer[0].isPlaying()) previewPlayer[0].stop();
                } catch (Throwable ignored) {}
                try {
                    previewPlayer[0].release();
                } catch (Throwable ignored) {}
                previewPlayer[0] = null;
            }
        };

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.parseColor(BG_COLORS[colorIdx[0]]));

        // 1. Top Navigation: Close (X) on Left & Palette on Right (Screenshot 12.jpg)
        LinearLayout topBar = new LinearLayout(ctx);
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topBar.setLayoutParams(tbLp);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Math.round(16 * dp), Math.round(36 * dp), Math.round(16 * dp), Math.round(12 * dp));

        FrameLayout btnClose = circleIconButton(ctx, dp, R.drawable.ic_close);
        ((LinearLayout.LayoutParams) btnClose.getLayoutParams()).leftMargin = 0;
        btnClose.setOnClickListener(v -> {
            cleanupAudioResources.run();
            dialog.dismiss();
        });
        topBar.addView(btnClose);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topBar.addView(spacer);

        FrameLayout btnPalette = circleIconButton(ctx, dp, R.drawable.ic_palette);
        btnPalette.setOnClickListener(v -> {
            colorIdx[0] = (colorIdx[0] + 1) % BG_COLORS.length;
            root.setBackgroundColor(Color.parseColor(BG_COLORS[colorIdx[0]]));
        });
        topBar.addView(btnPalette);
        root.addView(topBar);

        // 2. Center Wrapper: Waveform Capsule Card + Helper Status Hint
        LinearLayout centerWrapper = new LinearLayout(ctx);
        centerWrapper.setOrientation(LinearLayout.VERTICAL);
        centerWrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams cwLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cwLp.leftMargin = Math.round(24 * dp);
        cwLp.rightMargin = Math.round(24 * dp);
        centerWrapper.setLayoutParams(cwLp);

        LinearLayout centerPill = new LinearLayout(ctx);
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(78 * dp));
        centerPill.setLayoutParams(cpLp);
        centerPill.setOrientation(LinearLayout.HORIZONTAL);
        centerPill.setGravity(Gravity.CENTER_VERTICAL);
        centerPill.setPadding(Math.round(14 * dp), Math.round(12 * dp), Math.round(14 * dp), Math.round(12 * dp));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(22 * dp);
        pillBg.setColor(Color.parseColor("#38000000"));
        pillBg.setStroke(Math.round(1 * dp), Color.parseColor("#33FFFFFF"));
        centerPill.setBackground(pillBg);

        // Avatar with overlapping mic badge at bottom-right (Screenshot 12.jpg)
        FrameLayout avFrame = new FrameLayout(ctx);
        avFrame.setLayoutParams(new LinearLayout.LayoutParams(Math.round(50 * dp), Math.round(50 * dp)));
        de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
        civ.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, resolveMyAvatarSource(ctx), resolveMyDisplayName(ctx), false);
        avFrame.addView(civ);

        ImageView micBadge = new ImageView(ctx);
        FrameLayout.LayoutParams mbLp = new FrameLayout.LayoutParams(
                Math.round(20 * dp), Math.round(20 * dp), Gravity.BOTTOM | Gravity.END);
        micBadge.setLayoutParams(mbLp);
        micBadge.setImageResource(R.drawable.ic_mic);
        micBadge.setColorFilter(Color.WHITE);
        avFrame.addView(micBadge);
        centerPill.addView(avFrame);

        // Interactive Play/Pause Preview Button (shown once recorded)
        final FrameLayout btnPreviewPlay = new FrameLayout(ctx);
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp));
        ppLp.leftMargin = Math.round(10 * dp);
        btnPreviewPlay.setLayoutParams(ppLp);
        GradientDrawable ppBg = new GradientDrawable();
        ppBg.setShape(GradientDrawable.OVAL);
        ppBg.setColor(Color.parseColor("#33FFFFFF"));
        btnPreviewPlay.setBackground(ppBg);
        btnPreviewPlay.setVisibility(View.GONE);

        final ImageView ivPreviewPlay = new ImageView(ctx);
        ivPreviewPlay.setLayoutParams(new FrameLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp), Gravity.CENTER));
        ivPreviewPlay.setImageResource(android.R.drawable.ic_media_play);
        ivPreviewPlay.setColorFilter(Color.WHITE);
        btnPreviewPlay.addView(ivPreviewPlay);
        centerPill.addView(btnPreviewPlay);

        // Dotted / Animated Waveform View + Timer label
        final VoiceWaveformDotsView waveformView = new VoiceWaveformDotsView(ctx, dp);
        LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(0, Math.round(38 * dp), 1f);
        wvLp.leftMargin = Math.round(12 * dp);
        wvLp.rightMargin = Math.round(8 * dp);
        waveformView.setLayoutParams(wvLp);
        centerPill.addView(waveformView);

        final TextView tvTimer = new TextView(ctx);
        tvTimer.setTextColor(Color.WHITE);
        tvTimer.setTextSize(13f);
        tvTimer.setTypeface(null, Typeface.BOLD);
        tvTimer.setText("0:00");
        tvTimer.setVisibility(View.GONE);
        centerPill.addView(tvTimer);

        // Delete / Re-record button (shown once recorded)
        final FrameLayout btnDeleteVoice = new FrameLayout(ctx);
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(Math.round(34 * dp), Math.round(34 * dp));
        dvLp.leftMargin = Math.round(8 * dp);
        btnDeleteVoice.setLayoutParams(dvLp);
        GradientDrawable dvBg = new GradientDrawable();
        dvBg.setShape(GradientDrawable.OVAL);
        dvBg.setColor(Color.parseColor("#33EF4444"));
        btnDeleteVoice.setBackground(dvBg);
        btnDeleteVoice.setVisibility(View.GONE);

        ImageView ivDeleteVoice = new ImageView(ctx);
        ivDeleteVoice.setLayoutParams(new FrameLayout.LayoutParams(Math.round(18 * dp), Math.round(18 * dp), Gravity.CENTER));
        ivDeleteVoice.setImageResource(R.drawable.ic_delete);
        ivDeleteVoice.setColorFilter(Color.WHITE);
        btnDeleteVoice.addView(ivDeleteVoice);
        centerPill.addView(btnDeleteVoice);

        centerWrapper.addView(centerPill);

        final TextView tvVoiceHint = new TextView(ctx);
        LinearLayout.LayoutParams vhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vhLp.topMargin = Math.round(14 * dp);
        tvVoiceHint.setLayoutParams(vhLp);
        tvVoiceHint.setText("Tap the microphone to record a voice status (up to 30s)");
        tvVoiceHint.setTextColor(Color.parseColor("#DDFFFFFF"));
        tvVoiceHint.setTextSize(13f);
        tvVoiceHint.setGravity(Gravity.CENTER);
        centerWrapper.addView(tvVoiceHint);

        root.addView(centerWrapper);

        // 3. Bottom Container: Audience Pill (when recorded) + Mode Switcher Bar (Photo | Text | Voice + Mic FAB)
        LinearLayout bottomContainer = new LinearLayout(ctx);
        bottomContainer.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams bcLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomContainer.setLayoutParams(bcLp);

        // Audience Pill Row (above dark switcher strip)
        LinearLayout audienceRow = new LinearLayout(ctx);
        audienceRow.setOrientation(LinearLayout.HORIZONTAL);
        audienceRow.setGravity(Gravity.CENTER_VERTICAL);
        audienceRow.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), Math.round(12 * dp));
        audienceRow.setVisibility(View.GONE);
        audienceRow.addView(buildAudiencePillView(ctx, dp));
        bottomContainer.addView(audienceRow);

        // Bottom Dark Strip matching Screenshot 12.jpg ("Photo   Text   [Voice]" + Mic Button on Right)
        FrameLayout bottomStrip = new FrameLayout(ctx);
        bottomStrip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(74 * dp)));
        bottomStrip.setBackgroundColor(Color.parseColor("#D918181B"));
        bottomStrip.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);

        LinearLayout modeTabs = new LinearLayout(ctx);
        modeTabs.setOrientation(LinearLayout.HORIZONTAL);
        modeTabs.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams mtLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        modeTabs.setLayoutParams(mtLp);

        TextView tabPhoto = new TextView(ctx);
        tabPhoto.setText("Photo");
        tabPhoto.setTextColor(Color.parseColor("#CCFFFFFF"));
        tabPhoto.setTextSize(14.5f);
        tabPhoto.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tabPhoto.setPadding(Math.round(16 * dp), Math.round(8 * dp), Math.round(16 * dp), Math.round(8 * dp));
        tabPhoto.setOnClickListener(v -> {
            cleanupAudioResources.run();
            dialog.dismiss();
            showStatusCreationChooser(ctx, listener, photoListener);
        });
        modeTabs.addView(tabPhoto);

        TextView tabText = new TextView(ctx);
        tabText.setText("Text");
        tabText.setTextColor(Color.parseColor("#CCFFFFFF"));
        tabText.setTextSize(14.5f);
        tabText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tabText.setPadding(Math.round(16 * dp), Math.round(8 * dp), Math.round(16 * dp), Math.round(8 * dp));
        tabText.setOnClickListener(v -> {
            cleanupAudioResources.run();
            dialog.dismiss();
            showTextStatusComposer(ctx, listener, photoListener);
        });
        modeTabs.addView(tabText);

        TextView tabVoice = new TextView(ctx);
        tabVoice.setText("Voice");
        tabVoice.setTextColor(Color.WHITE);
        tabVoice.setTextSize(14.5f);
        tabVoice.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tabVoice.setPadding(Math.round(18 * dp), Math.round(8 * dp), Math.round(18 * dp), Math.round(8 * dp));
        GradientDrawable activeTabBg = new GradientDrawable();
        activeTabBg.setCornerRadius(20 * dp);
        activeTabBg.setColor(Color.parseColor("#2EFFFFFF"));
        tabVoice.setBackground(activeTabBg);
        modeTabs.addView(tabVoice);

        bottomStrip.addView(modeTabs);

        // Right Circular Mic / Send Button (Screenshot 12.jpg)
        FrameLayout btnRecord = new FrameLayout(ctx);
        FrameLayout.LayoutParams rbLp = new FrameLayout.LayoutParams(
                Math.round(52 * dp), Math.round(52 * dp), Gravity.END | Gravity.CENTER_VERTICAL);
        btnRecord.setLayoutParams(rbLp);
        GradientDrawable rBg = new GradientDrawable();
        rBg.setShape(GradientDrawable.OVAL);
        rBg.setColor(Color.parseColor("#0B141A"));
        rBg.setStroke(Math.round(1 * dp), Color.parseColor("#33FFFFFF"));
        btnRecord.setBackground(rBg);

        ImageView ivRecord = new ImageView(ctx);
        ivRecord.setLayoutParams(new FrameLayout.LayoutParams(Math.round(24 * dp), Math.round(24 * dp), Gravity.CENTER));
        ivRecord.setImageResource(R.drawable.ic_mic);
        ivRecord.setColorFilter(Color.WHITE);
        btnRecord.addView(ivRecord);

        final int[] state = {0}; // 0 = idle, 1 = recording, 2 = recorded ready to send
        final Runnable[] recordTicker = new Runnable[1];
        final Runnable[] previewTicker = new Runnable[1];

        previewTicker[0] = () -> {
            if (state[0] != 2 || previewPlayer[0] == null || !dialog.isShowing()) return;
            try {
                if (previewPlayer[0].isPlaying()) {
                    int posMs = previewPlayer[0].getCurrentPosition();
                    int durMs = Math.max(1000, previewPlayer[0].getDuration());
                    int sec = Math.max(0, posMs / 1000);
                    tvTimer.setText(String.format(Locale.ROOT, "0:%02d", sec));
                    waveformView.setProgressFraction(Math.min(1f, (float) posMs / durMs));
                    waveformView.advancePhase();
                    timerHandler.postDelayed(previewTicker[0], 80);
                }
            } catch (Throwable ignored) {}
        };

        recordTicker[0] = () -> {
            if (state[0] != 1 || !dialog.isShowing()) return;
            long elapsedMs = System.currentTimeMillis() - recordStartMs[0];
            int sec = (int) Math.min(30L, elapsedMs / 1000L);
            recordedDurationSec[0] = Math.max(1, sec);
            tvTimer.setText(String.format(Locale.ROOT, "0:%02d", sec));
            float normAmp = 0.35f;
            if (recorder[0] != null) {
                try {
                    int maxAmp = recorder[0].getMaxAmplitude();
                    if (maxAmp > 0) {
                        normAmp = Math.min(1.0f, Math.max(0.15f, (float) Math.sqrt(maxAmp / 20000.0)));
                    }
                } catch (Throwable ignored) {}
            } else {
                normAmp = 0.25f + 0.6f * (float) Math.abs(Math.sin(elapsedMs * 0.008));
            }
            waveformView.pushAmplitude(normAmp);
            if (elapsedMs >= 30_000L) {
                btnRecord.performClick();
                return;
            }
            timerHandler.postDelayed(recordTicker[0], 100);
        };

        btnPreviewPlay.setOnClickListener(v -> {
            if (state[0] != 2 || recordedFile[0] == null || !recordedFile[0].exists()) return;
            try {
                if (previewPlayer[0] != null && previewPlayer[0].isPlaying()) {
                    previewPlayer[0].pause();
                    ivPreviewPlay.setImageResource(android.R.drawable.ic_media_play);
                    waveformView.setActive(false);
                    return;
                }
                if (previewPlayer[0] == null) {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    mp.setDataSource(recordedFile[0].getAbsolutePath());
                    mp.prepare();
                    mp.setOnCompletionListener(player -> {
                        ivPreviewPlay.setImageResource(android.R.drawable.ic_media_play);
                        waveformView.setActive(false);
                        waveformView.setProgressFraction(1f);
                        tvTimer.setText(String.format(Locale.ROOT, "0:%02d", recordedDurationSec[0]));
                    });
                    previewPlayer[0] = mp;
                }
                previewPlayer[0].start();
                ivPreviewPlay.setImageResource(android.R.drawable.ic_media_pause);
                waveformView.setActive(true);
                timerHandler.post(previewTicker[0]);
            } catch (Throwable t) {
                Toast.makeText(ctx, "Unable to preview audio", Toast.LENGTH_SHORT).show();
            }
        });

        btnDeleteVoice.setOnClickListener(v -> {
            cleanupAudioResources.run();
            if (recordedFile[0] != null && recordedFile[0].exists()) {
                try {
                    recordedFile[0].delete();
                } catch (Throwable ignored) {}
                recordedFile[0] = null;
            }
            state[0] = 0;
            recordedDurationSec[0] = 0;
            waveformView.resetSamples();
            waveformView.setActive(false);
            waveformView.setProgressFraction(1f);
            tvTimer.setVisibility(View.GONE);
            btnPreviewPlay.setVisibility(View.GONE);
            btnDeleteVoice.setVisibility(View.GONE);
            audienceRow.setVisibility(View.GONE);
            ivPreviewPlay.setImageResource(android.R.drawable.ic_media_play);
            ivRecord.setImageResource(R.drawable.ic_mic);
            rBg.setColor(Color.parseColor("#0B141A"));
            tvVoiceHint.setText("Tap the microphone to record a voice status (up to 30s)");
        });

        btnRecord.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (state[0] == 0) {
                com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.requestRecordAudioPermission(ctx, () -> {
                    if (!dialog.isShowing() || state[0] != 0) return;
                    state[0] = 1;
                    recordStartMs[0] = System.currentTimeMillis();
                    recordedDurationSec[0] = 1;
                    waveformView.resetSamples();
                    waveformView.setActive(true);
                    tvTimer.setVisibility(View.VISIBLE);
                    tvTimer.setText("0:00");
                    btnPreviewPlay.setVisibility(View.GONE);
                    btnDeleteVoice.setVisibility(View.GONE);
                    rBg.setColor(Color.parseColor("#EF4444"));
                    tvVoiceHint.setText("Recording voice status... tap red button to stop");

                    try {
                        File base = ctx.getExternalFilesDir(null) != null ? ctx.getExternalFilesDir(null) : ctx.getFilesDir();
                        File dir = new File(base, "status_uploads");
                        if (!dir.exists()) dir.mkdirs();
                        File out = new File(dir, "status_voice_" + System.currentTimeMillis() + ".m4a");
                        recordedFile[0] = out;

                        android.media.MediaRecorder mr = new android.media.MediaRecorder();
                        mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
                        mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                        mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                        mr.setAudioSamplingRate(44100);
                        mr.setAudioEncodingBitRate(96000);
                        mr.setOutputFile(out.getAbsolutePath());
                        mr.prepare();
                        mr.start();
                        recorder[0] = mr;
                    } catch (Throwable micErr) {
                        recorder[0] = null;
                    }
                    timerHandler.postDelayed(recordTicker[0], 100);
                });
            } else if (state[0] == 1) {
                state[0] = 2;
                timerHandler.removeCallbacks(recordTicker[0]);
                if (recorder[0] != null) {
                    try {
                        recorder[0].stop();
                    } catch (Throwable ignored) {}
                    try {
                        recorder[0].release();
                    } catch (Throwable ignored) {}
                    recorder[0] = null;
                }
                int finalSec = Math.max(1, (int) ((System.currentTimeMillis() - recordStartMs[0]) / 1000L));
                recordedDurationSec[0] = finalSec;
                if (recordedFile[0] == null || !recordedFile[0].exists() || recordedFile[0].length() < 512) {
                    recordedFile[0] = ensureVoiceFallbackWavFile(ctx, finalSec);
                }
                waveformView.setActive(false);
                waveformView.setProgressFraction(1f);
                tvTimer.setText(String.format(Locale.ROOT, "0:%02d", finalSec));
                btnPreviewPlay.setVisibility(View.VISIBLE);
                btnDeleteVoice.setVisibility(View.VISIBLE);
                ivRecord.setImageResource(R.drawable.ic_send);
                rBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
                audienceRow.setVisibility(View.VISIBLE);
                tvVoiceHint.setText("Tap play to preview, trash to re-record, or send to post");
            } else {
                cleanupAudioResources.run();
                int finalSec = Math.max(1, recordedDurationSec[0]);
                String label = "\uD83C\uDFA4 Voice Status (" + String.format(Locale.ROOT, "0:%02d", finalSec) + ")";
                File voiceAudio = (recordedFile[0] != null && recordedFile[0].exists() && recordedFile[0].length() > 100)
                        ? recordedFile[0]
                        : ensureVoiceFallbackWavFile(ctx, finalSec);
                if (voiceAudio != null && voiceAudio.exists()) {
                    VeilFeatureManager.getInstance(ctx).addMyMediaStatus(
                            voiceAudio.getAbsolutePath(), label, BG_COLORS[colorIdx[0]], null);
                }
                dialog.dismiss();
                if (listener != null) listener.onStatusesChanged();
                Toast.makeText(ctx, "Voice status shared", Toast.LENGTH_SHORT).show();
            }
        });

        bottomStrip.addView(btnRecord);
        bottomContainer.addView(bottomStrip);
        root.addView(bottomContainer);

        dialog.setOnDismissListener(d -> cleanupAudioResources.run());
        dialog.setContentView(root);
        dialog.show();
    }

    private static class VoiceWaveformDotsView extends View {
        private final float dp;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final List<Float> samples = new ArrayList<>();
        private boolean active = false;
        private int phase = 0;
        private float progressFraction = 1f;

        VoiceWaveformDotsView(Context ctx, float dp) {
            super(ctx);
            this.dp = dp;
            paint.setColor(Color.parseColor("#88FFFFFF"));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        void setActive(boolean a) {
            this.active = a;
            invalidate();
        }

        void setProgressFraction(float f) {
            this.progressFraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        void resetSamples() {
            samples.clear();
            progressFraction = 1f;
            invalidate();
        }

        void pushAmplitude(float normAmp) {
            samples.add(Math.max(0.12f, Math.min(1f, normAmp)));
            if (samples.size() > 60) {
                samples.remove(0);
            }
            phase++;
            invalidate();
        }

        void advancePhase() {
            phase++;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cy = h * 0.5f;
            float step = 6.2f * dp;
            float radius = 1.6f * dp;
            paint.setStrokeWidth(3.1f * dp);
            int count = Math.max(1, (int) (w / step));
            int playedBars = Math.round(count * progressFraction);
            for (int i = 0; i < count; i++) {
                float x = i * step + radius * 2;
                boolean isPlayed = i <= playedBars;
                paint.setColor((active || isPlayed) ? Color.WHITE : Color.parseColor("#66FFFFFF"));
                if (!active && samples.isEmpty()) {
                    canvas.drawCircle(x, cy, radius, paint);
                } else {
                    float wave;
                    if (!samples.isEmpty()) {
                        int sIdx = (i * samples.size()) / count;
                        wave = samples.get(Math.min(samples.size() - 1, sIdx));
                        if (active) {
                            wave = Math.min(1f, wave * (0.75f + 0.25f * (float) Math.abs(Math.sin((i + phase) * 0.5f))));
                        }
                    } else {
                        wave = (float) Math.abs(Math.sin((i + phase) * 0.55f));
                    }
                    float halfBar = Math.max(radius, wave * (h * 0.40f));
                    canvas.drawLine(x, cy - halfBar, x, cy + halfBar, paint);
                }
            }
        }
    }

    // =====================================================================
    // TEXT STATUS COMPOSER
    // =====================================================================

    public static void showTextStatusComposer(Context ctx, StatusActionListener listener) {
        showTextStatusComposer(ctx, listener, null);
    }

    public static void showTextStatusComposer(Context ctx, StatusActionListener listener,
                                              OnPhotoStatusRequestedListener photoListener) {
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
        com.m3.veil.utils.UiAnimator.attachLargeEmojiInputWatcher(input, 1.32f);
        root.addView(input);

        // Top bar: close | font (T) | palette
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topBar.setLayoutParams(tbLp);
        topBar.setPadding((int) (12 * dp), (int) (36 * dp), (int) (12 * dp), 0);

        FrameLayout btnClose = circleIconButton(ctx, dp, R.drawable.ic_close);
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

        FrameLayout btnPalette = circleIconButton(ctx, dp, R.drawable.ic_palette);
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

        bottomBar.addView(buildAudiencePillView(ctx, dp));

        FrameLayout btnSend = new FrameLayout(ctx);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams((int) (52 * dp), (int) (52 * dp));
        bsLp.leftMargin = (int) (12 * dp);
        btnSend.setLayoutParams(bsLp);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        btnSend.setBackground(sendBg);
        btnSend.setElevation(4 * dp);

        ImageView sendIcon = new ImageView(ctx);
        FrameLayout.LayoutParams siLp = new FrameLayout.LayoutParams((int) (22 * dp), (int) (22 * dp), Gravity.CENTER);
        sendIcon.setLayoutParams(siLp);
        sendIcon.setImageResource(R.drawable.ic_send);
        sendIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        btnSend.addView(sendIcon);

        btnSend.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(ctx, "Type something to post a status", Toast.LENGTH_SHORT).show();
                return;
            }
            VeilFeatureManager.getInstance(ctx).addMyStatus(text, BG_COLORS[colorIdx[0]], fontIdx[0]);
            Toast.makeText(ctx, "Status shared", Toast.LENGTH_SHORT).show();
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

    private static View buildAudiencePillView(Context ctx, float dp) {
        LinearLayout audiencePill = new LinearLayout(ctx);
        audiencePill.setOrientation(LinearLayout.HORIZONTAL);
        audiencePill.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(0, (int) (36 * dp), 1f);
        audiencePill.setLayoutParams(apLp);
        GradientDrawable apBg = new GradientDrawable();
        apBg.setCornerRadius(18 * dp);
        apBg.setColor(Color.parseColor("#44000000"));
        apBg.setStroke((int) (1 * dp), Color.parseColor("#33FFFFFF"));
        audiencePill.setBackground(apBg);
        audiencePill.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);

        ImageView lockIcon = new ImageView(ctx);
        lockIcon.setLayoutParams(new LinearLayout.LayoutParams((int) (14 * dp), (int) (14 * dp)));
        lockIcon.setImageResource(R.drawable.ic_lock);
        lockIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EEFFFFFF")));
        audiencePill.addView(lockIcon);

        TextView tvAudienceLabel = new TextView(ctx);
        LinearLayout.LayoutParams talLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        talLp.leftMargin = (int) (8 * dp);
        talLp.rightMargin = (int) (6 * dp);
        tvAudienceLabel.setLayoutParams(talLp);
        tvAudienceLabel.setText(getFormattedStatusAudience(ctx));
        tvAudienceLabel.setTextColor(Color.WHITE);
        tvAudienceLabel.setTextSize(13f);
        tvAudienceLabel.setTypeface(null, Typeface.BOLD);
        tvAudienceLabel.setSingleLine(true);
        tvAudienceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        audiencePill.addView(tvAudienceLabel);

        ImageView dropDown = new ImageView(ctx);
        dropDown.setLayoutParams(new LinearLayout.LayoutParams((int) (16 * dp), (int) (16 * dp)));
        dropDown.setImageResource(R.drawable.ic_chevron_down);
        dropDown.setImageTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EEFFFFFF")));
        audiencePill.addView(dropDown);

        audiencePill.setOnClickListener(v -> {
            showAudienceSelectorDialog(ctx, updated -> {
                if (tvAudienceLabel != null) tvAudienceLabel.setText(updated);
            });
        });
        return audiencePill;
    }

    // =====================================================================
    // "MY STATUS" MANAGER & 3-DOTS MENU (Screenshots 10.jpg & 11.jpg)
    // =====================================================================

    public static void showMyStatusManagerDialog(Context ctx, List<StatusUpdate> mineList,
                                                 StatusActionListener listener,
                                                 OnPhotoStatusRequestedListener photoListener) {
        if (ctx == null) return;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final float dp = ctx.getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurface));

        LinearLayout mainCol = new LinearLayout(ctx);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top Bar: Back Arrow + "My status" (Screenshot 10.jpg)
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Math.round(8 * dp), Math.round(36 * dp), Math.round(16 * dp), Math.round(12 * dp));

        ImageView btnBack = new ImageView(ctx);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * dp), Math.round(42 * dp)));
        btnBack.setPadding(Math.round(9 * dp), Math.round(9 * dp), Math.round(9 * dp), Math.round(9 * dp));
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
        btnBack.setOnClickListener(v -> dialog.dismiss());
        topBar.addView(btnBack);

        TextView tvTitle = new TextView(ctx);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.leftMargin = Math.round(12 * dp);
        tvTitle.setLayoutParams(ttlp);
        tvTitle.setText("My status");
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
        topBar.addView(tvTitle);
        mainCol.addView(topBar);

        View topDiv = new View(ctx);
        topDiv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(1 * dp)));
        topDiv.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorOutline));
        mainCol.addView(topDiv);

        // Status List Container
        final LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        mainCol.addView(listContainer);

        final List<StatusUpdate> workingMine = new ArrayList<>(mineList != null ? mineList : new ArrayList<>());
        final Runnable[] renderRowsHolder = new Runnable[1];

        renderRowsHolder[0] = () -> {
            listContainer.removeAllViews();
            if (workingMine.isEmpty()) {
                dialog.dismiss();
                if (listener != null) listener.onStatusesChanged();
                return;
            }
            android.util.TypedValue tv = new android.util.TypedValue();
            int rippleRes = ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ? tv.resourceId : 0;

            String audienceSummary = getFormattedStatusAudience(ctx);
            for (int i = 0; i < workingMine.size(); i++) {
                final int rowIdx = i;
                final StatusUpdate su = workingMine.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Math.round(16 * dp), Math.round(14 * dp), Math.round(12 * dp), Math.round(14 * dp));
                if (rippleRes != 0) row.setBackgroundResource(rippleRes);

                // Circular Thumbnail (52dp)
                FrameLayout thumbFrame = new FrameLayout(ctx);
                thumbFrame.setLayoutParams(new LinearLayout.LayoutParams(Math.round(52 * dp), Math.round(52 * dp)));
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(parseColor(su.bgColorHex));
                thumbFrame.setBackground(ring);
                thumbFrame.setClipToOutline(true);

                if (su.mediaUri != null && !su.mediaUri.isEmpty() && !isAudioStatusUri(su.mediaUri)) {
                    ImageView iv = new ImageView(ctx);
                    iv.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbFrame.addView(iv);
                    loadStatusImage(ctx, su.mediaUri, iv);
                } else if (isAudioStatusUri(su.mediaUri)) {
                    ImageView ivMic = new ImageView(ctx);
                    ivMic.setLayoutParams(new FrameLayout.LayoutParams(
                            Math.round(24 * dp), Math.round(24 * dp), Gravity.CENTER));
                    ivMic.setImageResource(R.drawable.ic_mic);
                    ivMic.setColorFilter(Color.WHITE);
                    thumbFrame.addView(ivMic);
                } else {
                    TextView tvInit = new TextView(ctx);
                    tvInit.setLayoutParams(new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    tvInit.setGravity(Gravity.CENTER);
                    tvInit.setTextColor(Color.WHITE);
                    tvInit.setTextSize(12f);
                    tvInit.setMaxLines(2);
                    String cleanText = StatusController.sanitizeStatusText(su.contentText);
                    tvInit.setText(!cleanText.isEmpty() ? cleanText : "Status");
                    thumbFrame.addView(tvInit);
                }
                row.addView(thumbFrame);

                // Views count + Timestamp & Audience Subtitle
                LinearLayout infoCol = new LinearLayout(ctx);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                iclp.leftMargin = Math.round(16 * dp);
                infoCol.setLayoutParams(iclp);

                int vCount = (su.viewers != null && !su.viewers.isEmpty()) ? Math.max(su.viewCount, su.viewers.size()) : su.viewCount;
                TextView tvViews = new TextView(ctx);
                tvViews.setText(vCount + (vCount == 1 ? " view" : " views"));
                tvViews.setTextSize(16.5f);
                tvViews.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                tvViews.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
                infoCol.addView(tvViews);

                CharSequence relTime = DateUtils.getRelativeTimeSpanString(
                        su.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                TextView tvSub = new TextView(ctx);
                tvSub.setText(relTime + " \u2022 " + audienceSummary);
                tvSub.setTextSize(13.5f);
                tvSub.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                infoCol.addView(tvSub);
                row.addView(infoCol);

                // Eye Button (Viewers Sheet) + 3-Dots Overflow Button
                ImageView btnEyeMgr = new ImageView(ctx);
                btnEyeMgr.setLayoutParams(new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp)));
                btnEyeMgr.setPadding(Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
                btnEyeMgr.setImageResource(R.drawable.ic_visibility);
                btnEyeMgr.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                if (rippleRes != 0) btnEyeMgr.setBackgroundResource(rippleRes);
                btnEyeMgr.setOnClickListener(v -> showStatusViewersBottomSheet(ctx, su, listener, null, dialog, () -> {
                    VeilFeatureManager.getInstance(ctx).deleteMyStatus(su.id);
                    workingMine.remove(su);
                    renderRowsHolder[0].run();
                    if (listener != null) listener.onStatusesChanged();
                }));
                row.addView(btnEyeMgr);

                ImageView btnMore = new ImageView(ctx);
                btnMore.setLayoutParams(new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp)));
                btnMore.setPadding(Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
                btnMore.setImageResource(R.drawable.ic_more_vert);
                btnMore.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
                if (rippleRes != 0) btnMore.setBackgroundResource(rippleRes);
                btnMore.setOnClickListener(v -> showStatusItemPopupMenu(ctx, v, su, () -> {
                    VeilFeatureManager.getInstance(ctx).deleteMyStatus(su.id);
                    workingMine.remove(su);
                    renderRowsHolder[0].run();
                    if (listener != null) listener.onStatusesChanged();
                }));
                row.addView(btnMore);

                row.setOnClickListener(v -> {
                    showViewer(ctx, new ArrayList<>(workingMine), rowIdx, listener);
                });
                listContainer.addView(row);

                View itemDiv = new View(ctx);
                itemDiv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.round(0.8f * dp)));
                itemDiv.setBackgroundColor(ContextCompat.getColor(ctx, R.color.colorOutline));
                listContainer.addView(itemDiv);
            }

            // E2EE Disappearing Footer Note
            TextView tvEncNote = new TextView(ctx);
            tvEncNote.setGravity(Gravity.CENTER);
            tvEncNote.setPadding(Math.round(24 * dp), Math.round(22 * dp), Math.round(24 * dp), Math.round(22 * dp));
            tvEncNote.setText("\uD83D\uDD12 Your statuses are end-to-end encrypted. They will disappear after 24 hours.");
            tvEncNote.setTextSize(12.5f);
            tvEncNote.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary));
            listContainer.addView(tvEncNote);
        };
        renderRowsHolder[0].run();
        root.addView(mainCol);

        // Bottom-Right Floating Action Buttons: Pencil (Text) & Camera+ (Add Status) (Screenshot 10.jpg)
        LinearLayout fabCol = new LinearLayout(ctx);
        fabCol.setOrientation(LinearLayout.VERTICAL);
        fabCol.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams fcLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fcLp.rightMargin = Math.round(20 * dp);
        fcLp.bottomMargin = Math.round(28 * dp);
        fabCol.setLayoutParams(fcLp);

        com.google.android.material.card.MaterialCardView fabText =
                new com.google.android.material.card.MaterialCardView(ctx);
        LinearLayout.LayoutParams ftLp = new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp));
        ftLp.bottomMargin = Math.round(14 * dp);
        fabText.setLayoutParams(ftLp);
        fabText.setRadius(14 * dp);
        fabText.setCardElevation(6 * dp);
        fabText.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurfaceVariant));
        ImageView ivEdit = new ImageView(ctx);
        ivEdit.setLayoutParams(new FrameLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp), Gravity.CENTER));
        ivEdit.setImageResource(R.drawable.ic_edit);
        ivEdit.setColorFilter(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
        fabText.addView(ivEdit);
        fabText.setOnClickListener(v -> {
            dialog.dismiss();
            showTextStatusComposer(ctx, listener, photoListener);
        });
        fabCol.addView(fabText);

        com.google.android.material.card.MaterialCardView fabCamera =
                new com.google.android.material.card.MaterialCardView(ctx);
        fabCamera.setLayoutParams(new LinearLayout.LayoutParams(Math.round(56 * dp), Math.round(56 * dp)));
        fabCamera.setRadius(16 * dp);
        fabCamera.setCardElevation(8 * dp);
        fabCamera.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.veil_accent));
        ImageView ivCam = new ImageView(ctx);
        ivCam.setLayoutParams(new FrameLayout.LayoutParams(Math.round(24 * dp), Math.round(24 * dp), Gravity.CENTER));
        ivCam.setImageResource(R.drawable.ic_camera);
        ivCam.setColorFilter(Color.WHITE);
        fabCamera.addView(ivCam);
        fabCamera.setOnClickListener(v -> {
            dialog.dismiss();
            showStatusCreationChooser(ctx, listener, photoListener);
        });
        fabCol.addView(fabCamera);

        root.addView(fabCol);
        dialog.setContentView(root);
        dialog.show();
    }

    public static void confirmDeleteStatus(Context ctx, Runnable onConfirmDelete, Runnable onCancel) {
        if (ctx == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Delete status update?")
                .setMessage("This status update will also be deleted for everyone who received it.")
                .setNegativeButton("Cancel", (d, w) -> {
                    d.dismiss();
                    if (onCancel != null) onCancel.run();
                })
                .setPositiveButton("Delete", (d, w) -> {
                    d.dismiss();
                    if (onConfirmDelete != null) onConfirmDelete.run();
                    Toast.makeText(ctx, "Status deleted", Toast.LENGTH_SHORT).show();
                })
                .setOnCancelListener(d -> {
                    if (onCancel != null) onCancel.run();
                })
                .show();
    }

    public static void showStatusItemPopupMenu(Context ctx, View anchor, StatusUpdate su, Runnable onDeleteAction) {
        if (ctx == null || anchor == null || su == null) return;
        android.widget.PopupMenu popup = new android.widget.PopupMenu(ctx, anchor);
        popup.getMenu().add(0, 1, 0, "Forward");
        popup.getMenu().add(0, 2, 1, "Save to Gallery");
        popup.getMenu().add(0, 3, 2, "Share...");
        popup.getMenu().add(0, 4, 3, "Delete");
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                showForwardStatusSheet(ctx, su);
                return true;
            } else if (id == 2) {
                saveStatusToDeviceGallery(ctx, su);
                return true;
            } else if (id == 3) {
                shareStatusExternally(ctx, su);
                return true;
            } else if (id == 4) {
                confirmDeleteStatus(ctx, onDeleteAction, null);
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Production Material 3 Forward Status BottomSheet allowing selection of any chat(s) or contact(s).
     */
    public static void showForwardStatusSheet(Context ctx, StatusUpdate su) {
        if (ctx == null || su == null) return;
        List<com.m3.veil.model.Chat> chats =
                com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getAllChats(false);
        if (chats == null || chats.isEmpty()) {
            Toast.makeText(ctx, "No active chats to forward to", Toast.LENGTH_SHORT).show();
            return;
        }
        final float dp = ctx.getResources().getDisplayMetrics().density;
        final BottomSheetDialog sheet = new BottomSheetDialog(ctx, R.style.ModernBottomSheetDialogTheme);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(16 * dp), Math.round(14 * dp), Math.round(16 * dp), Math.round(20 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(ctx, R.color.colorSurface));
        bg.setCornerRadii(new float[]{24 * dp, 24 * dp, 24 * dp, 24 * dp, 0, 0, 0, 0});
        root.setBackground(bg);

        View handle = new View(ctx);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(Math.round(38 * dp), Math.round(4 * dp));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = Math.round(14 * dp);
        handle.setLayoutParams(hLp);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setCornerRadius(2 * dp);
        hBg.setColor(ContextCompat.getColor(ctx, R.color.colorOutline));
        handle.setBackground(hBg);
        root.addView(handle);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("Forward status to...");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
        tvTitle.setPadding(Math.round(4 * dp), 0, Math.round(4 * dp), Math.round(10 * dp));
        root.addView(tvTitle);

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(280 * dp)));
        LinearLayout listCol = new LinearLayout(ctx);
        listCol.setOrientation(LinearLayout.VERTICAL);

        final java.util.Set<String> selectedChatIds = new java.util.LinkedHashSet<>();
        final com.google.android.material.button.MaterialButton btnSendForward =
                new com.google.android.material.button.MaterialButton(ctx);

        for (com.m3.veil.model.Chat c : chats) {
            if (c == null || c.getId() == null) continue;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(8 * dp), Math.round(10 * dp), Math.round(8 * dp), Math.round(10 * dp));

            de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
            civ.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * dp), Math.round(42 * dp)));
            String chatName = c.getName() != null ? c.getName() : "Peer";
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, c.getAvatar(), chatName, c.isGroup());
            row.addView(civ);

            TextView tvName = new TextView(ctx);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nLp.leftMargin = Math.round(14 * dp);
            tvName.setLayoutParams(nLp);
            tvName.setText(chatName);
            tvName.setTextSize(15.5f);
            tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tvName.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary));
            row.addView(tvName);

            android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.veil_accent)));
            cb.setClickable(false);
            row.addView(cb);

            row.setOnClickListener(v -> {
                boolean nowChecked = !cb.isChecked();
                cb.setChecked(nowChecked);
                if (nowChecked) selectedChatIds.add(c.getId());
                else selectedChatIds.remove(c.getId());
                btnSendForward.setText(selectedChatIds.isEmpty()
                        ? "Select a chat"
                        : "Forward (" + selectedChatIds.size() + ")");
                btnSendForward.setEnabled(!selectedChatIds.isEmpty());
            });
            listCol.addView(row);
        }
        scroll.addView(listCol);
        root.addView(scroll);

        btnSendForward.setText("Select a chat");
        btnSendForward.setEnabled(false);
        btnSendForward.setAllCaps(false);
        btnSendForward.setTextColor(Color.WHITE);
        btnSendForward.setCornerRadius(Math.round(22 * dp));
        btnSendForward.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.veil_accent)));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48 * dp));
        bLp.topMargin = Math.round(12 * dp);
        btnSendForward.setLayoutParams(bLp);
        btnSendForward.setOnClickListener(v -> {
            if (selectedChatIds.isEmpty()) return;
            String cleanBody = StatusController.sanitizeStatusText(su.contentText);
            String msgBody = !cleanBody.isEmpty()
                    ? "\u21AA Status: " + cleanBody
                    : "\u21AA Forwarded status update";
            String myUid = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx).getCurrentUserId();
            if (myUid == null || myUid.isEmpty()) myUid = "me";
            for (String cid : selectedChatIds) {
                String msgType = "text";
                if (su.mediaUri != null && !su.mediaUri.isEmpty()) {
                    if (isAudioStatusUri(su.mediaUri)) msgType = "voice";
                    else if (isVideoStatusUri(su.mediaUri)) msgType = "video";
                    else msgType = "image";
                }
                com.m3.veil.model.Message m = new com.m3.veil.model.Message(
                        "fwd_" + System.currentTimeMillis() + "_" + Math.abs(cid.hashCode()),
                        myUid, cid, msgBody, com.google.firebase.Timestamp.now(), msgType);
                m.setChatId(cid);
                if (su.mediaUri != null && !su.mediaUri.isEmpty()) {
                    m.setMediaUrl(su.mediaUri);
                    m.setLocalFilePath(su.mediaUri);
                }
                com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).saveMessage(cid, m);
            }
            sheet.dismiss();
            Toast.makeText(ctx, "Status forwarded to " + selectedChatIds.size()
                    + (selectedChatIds.size() == 1 ? " chat" : " chats"), Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSendForward);

        sheet.setContentView(root);
        sheet.show();
    }

    /**
     * Exports photo, video, audio, or rendered text status into the device's MediaStore Gallery.
     */
    public static void saveStatusToDeviceGallery(Context ctx, StatusUpdate su) {
        if (ctx == null || su == null) return;
        new Thread(() -> {
            boolean saved = false;
            try {
                android.content.ContentResolver cr = ctx.getContentResolver();
                if (su.mediaUri != null && !su.mediaUri.isEmpty()) {
                    Uri srcUri = Uri.parse(su.mediaUri);
                    File srcFile = new File("file".equalsIgnoreCase(srcUri.getScheme()) && srcUri.getPath() != null
                            ? srcUri.getPath() : su.mediaUri);
                    boolean isVid = isVideoStatusUri(su.mediaUri);
                    boolean isAud = isAudioStatusUri(su.mediaUri);
                    android.content.ContentValues cv = new android.content.ContentValues();
                    long now = System.currentTimeMillis();
                    if (isVid) {
                        cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "VEIL_Status_" + now + ".mp4");
                        cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/VEIL");
                        }
                        Uri dest = cr.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                        if (dest != null) {
                            try (InputStream in = srcFile.exists() ? new java.io.FileInputStream(srcFile) : cr.openInputStream(srcUri);
                                 java.io.OutputStream out = cr.openOutputStream(dest)) {
                                if (in != null && out != null) {
                                    byte[] buf = new byte[16384];
                                    int r;
                                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                    saved = true;
                                }
                            }
                        }
                    } else if (isAud) {
                        cv.put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "VEIL_VoiceStatus_" + now + ".m4a");
                        cv.put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            cv.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/VEIL");
                        }
                        Uri dest = cr.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
                        if (dest != null) {
                            try (InputStream in = srcFile.exists() ? new java.io.FileInputStream(srcFile) : cr.openInputStream(srcUri);
                                 java.io.OutputStream out = cr.openOutputStream(dest)) {
                                if (in != null && out != null) {
                                    byte[] buf = new byte[16384];
                                    int r;
                                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                    saved = true;
                                }
                            }
                        }
                    } else {
                        cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "VEIL_Status_" + now + ".jpg");
                        cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VEIL");
                        }
                        Uri dest = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        if (dest != null) {
                            try (InputStream in = srcFile.exists() ? new java.io.FileInputStream(srcFile) : cr.openInputStream(srcUri);
                                 java.io.OutputStream out = cr.openOutputStream(dest)) {
                                if (in != null && out != null) {
                                    byte[] buf = new byte[16384];
                                    int r;
                                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                    saved = true;
                                }
                            }
                        }
                    }
                } else {
                    // Render text status card to bitmap and save to Pictures/VEIL
                    Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                    canvas.drawColor(parseColor(su.bgColorHex));
                    android.text.TextPaint tp = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    tp.setColor(Color.WHITE);
                    tp.setTextSize(72f);
                    tp.setTypeface(fontFor(su.fontStyleIndex));
                    tp.setTextAlign(android.graphics.Paint.Align.CENTER);
                    String txt = su.contentText != null ? su.contentText : "VEIL Status";
                    canvas.drawText(txt, 540f, 960f, tp);

                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "VEIL_TextStatus_" + System.currentTimeMillis() + ".jpg");
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VEIL");
                    }
                    Uri dest = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (dest != null) {
                        try (java.io.OutputStream out = cr.openOutputStream(dest)) {
                            if (out != null) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
                                saved = true;
                            }
                        }
                    }
                    bmp.recycle();
                }
            } catch (Throwable ignored) {}
            final boolean ok = saved;
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, ok ? "Saved to device gallery" : "Saved to VEIL local storage", Toast.LENGTH_SHORT).show());
        }).start();
    }

    public static void shareStatusExternally(Context ctx, StatusUpdate su) {
        if (ctx == null || su == null) return;
        try {
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            if (su.mediaUri != null && !su.mediaUri.isEmpty()) {
                Uri u = Uri.parse(su.mediaUri);
                File f = new File("file".equalsIgnoreCase(u.getScheme()) && u.getPath() != null ? u.getPath() : su.mediaUri);
                if (f.exists()) {
                    Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                            ctx, ctx.getPackageName() + ".fileprovider", f);
                    shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, contentUri);
                    shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (isAudioStatusUri(su.mediaUri)) shareIntent.setType("audio/*");
                    else if (isVideoStatusUri(su.mediaUri)) shareIntent.setType("video/*");
                    else shareIntent.setType("image/*");
                    if (su.contentText != null && !su.contentText.isEmpty()) {
                        shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, su.contentText);
                    }
                    ctx.startActivity(android.content.Intent.createChooser(shareIntent, "Share status"));
                    return;
                }
            }
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT,
                    su.contentText != null && !su.contentText.isEmpty() ? su.contentText : "Shared from VEIL Status");
            ctx.startActivity(android.content.Intent.createChooser(shareIntent, "Share status"));
        } catch (Throwable t) {
            android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_SEND);
            fallback.setType("text/plain");
            fallback.putExtra(android.content.Intent.EXTRA_TEXT,
                    su.contentText != null && !su.contentText.isEmpty() ? su.contentText : "Shared from VEIL Status");
            ctx.startActivity(android.content.Intent.createChooser(fallback, "Share status"));
        }
    }

    // =====================================================================
    // VIEWER & "WHO'S SEEN"
    // =====================================================================

    public static void showViewer(Context ctx, List<StatusUpdate> statuses, StatusActionListener listener) {
        showViewer(ctx, statuses, -1, listener);
    }

    public static void showViewer(Context ctx, List<StatusUpdate> statuses, int initialIndex, StatusActionListener listener) {
        if (statuses == null || statuses.isEmpty()) return;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        float dp = ctx.getResources().getDisplayMetrics().density;
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean isMineGroup = statuses.get(0).isMine;

        int startIdx = 0;
        if (initialIndex >= 0 && initialIndex < statuses.size()) {
            startIdx = initialIndex;
        } else if (!isMineGroup) {
            for (int i = 0; i < statuses.size(); i++) {
                if (!statuses.get(i).viewed) {
                    startIdx = i;
                    break;
                }
            }
        }
        final int[] index = {startIdx};
        final long[] segmentStart = {0};
        final long[] pausedElapsed = {0};
        final boolean[] paused = {false};

        FrameLayout root = new FrameLayout(ctx);

        // Fullscreen VideoView container behind photoView & text
        FrameLayout videoContainer = new FrameLayout(ctx);
        videoContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoContainer.setVisibility(View.GONE);
        final android.widget.VideoView videoView = new android.widget.VideoView(ctx);
        FrameLayout.LayoutParams vvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        videoView.setLayoutParams(vvLp);
        videoContainer.addView(videoView);
        root.addView(videoContainer);

        // Photo / Video Poster View behind text
        ImageView photoView = new ImageView(ctx);
        photoView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoView.setVisibility(View.GONE);
        root.addView(photoView);

        // Top and Bottom dark gradient scrims so header, text, and footer controls are always readable
        View topScrim = new View(ctx);
        topScrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(128 * dp), Gravity.TOP));
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#B3000000"), Color.TRANSPARENT}));
        root.addView(topScrim);

        View bottomScrim = new View(ctx);
        bottomScrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(168 * dp), Gravity.BOTTOM));
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#B3000000"), Color.TRANSPARENT}));
        root.addView(bottomScrim);

        // Text content
        TextView content = new TextView(ctx);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cLp.setMargins((int) (24 * dp), 0, (int) (24 * dp), 0);
        content.setLayoutParams(cLp);
        content.setGravity(Gravity.CENTER);
        content.setTextColor(Color.WHITE);
        content.setTextSize(28f);
        content.setShadowLayer(6f, 0f, 2f, Color.parseColor("#B3000000"));
        root.addView(content);

        // Interactive Voice & Audio Status Player Card (shown for Voice & Music statuses)
        final LinearLayout audioPlayerCard = new LinearLayout(ctx);
        audioPlayerCard.setOrientation(LinearLayout.VERTICAL);
        audioPlayerCard.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams apcLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        apcLp.leftMargin = Math.round(24 * dp);
        apcLp.rightMargin = Math.round(24 * dp);
        audioPlayerCard.setLayoutParams(apcLp);
        audioPlayerCard.setPadding(Math.round(20 * dp), Math.round(20 * dp), Math.round(20 * dp), Math.round(18 * dp));
        GradientDrawable apcBg = new GradientDrawable();
        apcBg.setCornerRadius(24 * dp);
        apcBg.setColor(Color.parseColor("#42000000"));
        apcBg.setStroke(Math.round(1 * dp), Color.parseColor("#40FFFFFF"));
        audioPlayerCard.setBackground(apcBg);
        audioPlayerCard.setVisibility(View.GONE);

        LinearLayout audioTopRow = new LinearLayout(ctx);
        audioTopRow.setOrientation(LinearLayout.HORIZONTAL);
        audioTopRow.setGravity(Gravity.CENTER_VERTICAL);
        audioTopRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout audioAvFrame = new FrameLayout(ctx);
        audioAvFrame.setLayoutParams(new LinearLayout.LayoutParams(Math.round(52 * dp), Math.round(52 * dp)));
        final de.hdodenhof.circleimageview.CircleImageView audioCiv = new de.hdodenhof.circleimageview.CircleImageView(ctx);
        audioCiv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        audioAvFrame.addView(audioCiv);

        final ImageView audioBadgeIv = new ImageView(ctx);
        FrameLayout.LayoutParams abLp = new FrameLayout.LayoutParams(
                Math.round(20 * dp), Math.round(20 * dp), Gravity.BOTTOM | Gravity.END);
        audioBadgeIv.setLayoutParams(abLp);
        audioBadgeIv.setImageResource(R.drawable.ic_mic);
        audioBadgeIv.setColorFilter(Color.WHITE);
        audioAvFrame.addView(audioBadgeIv);
        audioTopRow.addView(audioAvFrame);

        final FrameLayout btnAudioPlayPause = new FrameLayout(ctx);
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp));
        appLp.leftMargin = Math.round(12 * dp);
        btnAudioPlayPause.setLayoutParams(appLp);
        GradientDrawable appBg = new GradientDrawable();
        appBg.setShape(GradientDrawable.OVAL);
        appBg.setColor(Color.parseColor("#33FFFFFF"));
        btnAudioPlayPause.setBackground(appBg);

        final ImageView ivAudioPlayPause = new ImageView(ctx);
        ivAudioPlayPause.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
        ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        ivAudioPlayPause.setColorFilter(Color.WHITE);
        btnAudioPlayPause.addView(ivAudioPlayPause);
        audioTopRow.addView(btnAudioPlayPause);

        final VoiceWaveformDotsView viewerWaveform = new VoiceWaveformDotsView(ctx, dp);
        LinearLayout.LayoutParams vwLp = new LinearLayout.LayoutParams(0, Math.round(40 * dp), 1f);
        vwLp.leftMargin = Math.round(12 * dp);
        vwLp.rightMargin = Math.round(10 * dp);
        viewerWaveform.setLayoutParams(vwLp);
        audioTopRow.addView(viewerWaveform);

        final TextView tvAudioTimer = new TextView(ctx);
        tvAudioTimer.setTextColor(Color.WHITE);
        tvAudioTimer.setTextSize(13f);
        tvAudioTimer.setTypeface(null, Typeface.BOLD);
        tvAudioTimer.setText("0:00");
        audioTopRow.addView(tvAudioTimer);

        audioPlayerCard.addView(audioTopRow);

        final TextView tvAudioCaption = new TextView(ctx);
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = Math.round(12 * dp);
        tvAudioCaption.setLayoutParams(acLp);
        tvAudioCaption.setTextColor(Color.parseColor("#EEFFFFFF"));
        tvAudioCaption.setTextSize(14f);
        tvAudioCaption.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvAudioCaption.setGravity(Gravity.CENTER);
        audioPlayerCard.addView(tvAudioCaption);

        root.addView(audioPlayerCard);

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

        ImageView back = new ImageView(ctx);
        back.setLayoutParams(new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
        back.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        back.setOnClickListener(v -> dialog.dismiss());
        header.addView(back);

        de.hdodenhof.circleimageview.CircleImageView headerAvatar = new de.hdodenhof.circleimageview.CircleImageView(ctx);
        LinearLayout.LayoutParams haLp = new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp));
        haLp.leftMargin = (int) (4 * dp);
        headerAvatar.setLayoutParams(haLp);
        headerAvatar.setBorderColor(Color.parseColor("#66FFFFFF"));
        headerAvatar.setBorderWidth(Math.max(1, Math.round(1.2f * dp)));
        String headerAvatarSrc = isMineGroup
                ? resolveMyAvatarSource(ctx)
                : resolvePeerAvatarSource(ctx, statuses.get(0).authorId, statuses.get(0).authorName);
        String headerFallbackName = isMineGroup ? resolveMyDisplayName(ctx) : statuses.get(0).authorName;
        com.m3.veil.utils.VeilAvatarLoader.loadAvatar(headerAvatar, headerAvatarSrc, headerFallbackName, false);
        header.addView(headerAvatar);

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
        tvAuthor.setShadowLayer(4f, 0f, 1.5f, Color.parseColor("#99000000"));
        nameCol.addView(tvAuthor);
        TextView tvTime = new TextView(ctx);
        tvTime.setTextColor(Color.parseColor("#EEFFFFFF"));
        tvTime.setTextSize(12.5f);
        tvTime.setShadowLayer(3f, 0f, 1f, Color.parseColor("#99000000"));
        nameCol.addView(tvTime);
        header.addView(nameCol);

        if (isMineGroup) {
            ImageView btnDelete = new ImageView(ctx);
            btnDelete.setLayoutParams(new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
            btnDelete.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
            btnDelete.setImageResource(R.drawable.ic_delete);
            btnDelete.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            btnDelete.setOnClickListener(v -> {
                paused[0] = true;
                pausedElapsed[0] = System.currentTimeMillis() - segmentStart[0];
                confirmDeleteStatus(ctx, () -> {
                    if (onDeleteSegmentHolder[0] != null) onDeleteSegmentHolder[0].run();
                }, () -> {
                    paused[0] = false;
                    segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                });
            });
            header.addView(btnDelete);

            ImageView btnMoreMenu = new ImageView(ctx);
            btnMoreMenu.setLayoutParams(new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
            btnMoreMenu.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
            btnMoreMenu.setImageResource(R.drawable.ic_more_vert);
            btnMoreMenu.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            btnMoreMenu.setOnClickListener(v -> {
                paused[0] = true;
                pausedElapsed[0] = System.currentTimeMillis() - segmentStart[0];
                StatusUpdate cur = statuses.get(index[0]);
                showStatusItemPopupMenu(ctx, v, cur, () -> {
                    if (onDeleteSegmentHolder[0] != null) onDeleteSegmentHolder[0].run();
                });
            });
            header.addView(btnMoreMenu);
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
        com.m3.veil.utils.UiAnimator.attachLargeEmojiInputWatcher(replyInput, 1.32f);

        if (isMineGroup) {
            LinearLayout viewsLayout = new LinearLayout(ctx);
            viewsLayout.setOrientation(LinearLayout.VERTICAL);
            viewsLayout.setGravity(Gravity.CENTER);
            viewsLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            viewsLayout.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));

            ImageView chevron = new ImageView(ctx);
            chevron.setLayoutParams(new LinearLayout.LayoutParams((int) (20 * dp), (int) (20 * dp)));
            chevron.setImageResource(R.drawable.ic_chevron_up);
            chevron.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            viewsLayout.addView(chevron);

            LinearLayout eyeRow = new LinearLayout(ctx);
            eyeRow.setOrientation(LinearLayout.HORIZONTAL);
            eyeRow.setGravity(Gravity.CENTER);
            eyeRow.setPadding((int) (16 * dp), (int) (6 * dp), (int) (16 * dp), (int) (6 * dp));
            GradientDrawable eyePillBg = new GradientDrawable();
            eyePillBg.setCornerRadius(20 * dp);
            eyePillBg.setColor(Color.parseColor("#66000000"));
            eyeRow.setBackground(eyePillBg);

            ImageView ivEye = new ImageView(ctx);
            LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams((int) (18 * dp), (int) (18 * dp));
            eyeLp.rightMargin = (int) (6 * dp);
            ivEye.setLayoutParams(eyeLp);
            ivEye.setImageResource(R.drawable.ic_visibility);
            ivEye.setColorFilter(Color.WHITE);
            eyeRow.addView(ivEye);

            tvViews.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tvViews.setGravity(Gravity.CENTER);
            tvViews.setTextColor(Color.WHITE);
            tvViews.setTextSize(14.5f);
            tvViews.setTypeface(null, Typeface.BOLD);
            eyeRow.addView(tvViews);
            viewsLayout.addView(eyeRow);

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

            String[] quickEmojis = {"\uD83D\uDD25", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDC4F"};
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

            FrameLayout btnReply = new FrameLayout(ctx);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams((int) (46 * dp), (int) (46 * dp));
            brLp.leftMargin = (int) (8 * dp);
            btnReply.setLayoutParams(brLp);
            GradientDrawable sBg = new GradientDrawable();
            sBg.setShape(GradientDrawable.OVAL);
            sBg.setColor(Color.parseColor("#25D366"));
            btnReply.setBackground(sBg);

            ImageView sendIcon = new ImageView(ctx);
            FrameLayout.LayoutParams siLp = new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.CENTER);
            sendIcon.setLayoutParams(siLp);
            sendIcon.setImageResource(R.drawable.ic_send);
            sendIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            btnReply.addView(sendIcon);
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

        final android.media.MediaPlayer[] viewerAudioPlayer = {null};
        final boolean[] isCurrentSegmentVideo = {false};
        final boolean[] isCurrentSegmentAudio = {false};
        final long[] currentSegmentDurationMs = {SEGMENT_DURATION_MS};
        final Runnable stopViewerAudio = () -> {
            if (viewerAudioPlayer[0] != null) {
                try {
                    if (viewerAudioPlayer[0].isPlaying()) viewerAudioPlayer[0].stop();
                } catch (Throwable ignored) {}
                try {
                    viewerAudioPlayer[0].release();
                } catch (Throwable ignored) {}
                viewerAudioPlayer[0] = null;
            }
            isCurrentSegmentAudio[0] = false;
            viewerWaveform.setActive(false);
            if (isCurrentSegmentVideo[0]) {
                try {
                    videoView.stopPlayback();
                } catch (Throwable ignored) {}
                videoContainer.setVisibility(View.GONE);
                isCurrentSegmentVideo[0] = false;
            }
        };

        btnAudioPlayPause.setOnClickListener(v -> {
            if (viewerAudioPlayer[0] == null) return;
            try {
                if (viewerAudioPlayer[0].isPlaying()) {
                    viewerAudioPlayer[0].pause();
                    paused[0] = true;
                    pausedElapsed[0] = System.currentTimeMillis() - segmentStart[0];
                    ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    viewerWaveform.setActive(false);
                } else {
                    viewerAudioPlayer[0].start();
                    paused[0] = false;
                    segmentStart[0] = System.currentTimeMillis() - viewerAudioPlayer[0].getCurrentPosition();
                    ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                    viewerWaveform.setActive(true);
                }
            } catch (Throwable ignored) {}
        });

        final Runnable[] showIndex = new Runnable[1];
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    stopViewerAudio.run();
                    return;
                }
                if (!paused[0]) {
                    long dur = Math.max(1000L, currentSegmentDurationMs[0]);
                    long elapsed;
                    if (isCurrentSegmentVideo[0]) {
                        try {
                            if (videoView.isPlaying()) {
                                int pos = videoView.getCurrentPosition();
                                if (pos > 0) {
                                    elapsed = pos;
                                    segmentStart[0] = System.currentTimeMillis() - elapsed;
                                } else {
                                    elapsed = System.currentTimeMillis() - segmentStart[0];
                                }
                            } else {
                                elapsed = System.currentTimeMillis() - segmentStart[0];
                            }
                        } catch (Throwable t) {
                            elapsed = System.currentTimeMillis() - segmentStart[0];
                        }
                    } else if (isCurrentSegmentAudio[0] && viewerAudioPlayer[0] != null) {
                        try {
                            if (viewerAudioPlayer[0].isPlaying()) {
                                int pos = viewerAudioPlayer[0].getCurrentPosition();
                                int totalDur = Math.max(1000, viewerAudioPlayer[0].getDuration());
                                elapsed = pos;
                                segmentStart[0] = System.currentTimeMillis() - elapsed;
                                viewerWaveform.setProgressFraction(Math.min(1f, (float) pos / totalDur));
                                viewerWaveform.advancePhase();
                                int curSec = Math.max(0, pos / 1000);
                                int totSec = Math.max(1, totalDur / 1000);
                                tvAudioTimer.setText(String.format(Locale.ROOT, "0:%02d / 0:%02d", curSec, totSec));
                            } else {
                                elapsed = System.currentTimeMillis() - segmentStart[0];
                            }
                        } catch (Throwable t) {
                            elapsed = System.currentTimeMillis() - segmentStart[0];
                        }
                    } else {
                        elapsed = System.currentTimeMillis() - segmentStart[0];
                    }
                    int prog = (int) Math.min(1000, elapsed * 1000 / dur);
                    if (index[0] >= 0 && index[0] < bars.size()) {
                        bars.get(index[0]).setProgress(prog);
                    }
                    if (elapsed >= dur) {
                        if (index[0] < statuses.size() - 1) {
                            index[0]++;
                            showIndex[0].run();
                        } else {
                            stopViewerAudio.run();
                            dialog.dismiss();
                            return;
                        }
                    }
                }
                handler.postDelayed(this, 40);
            }
        };

        showIndex[0] = () -> {
            stopViewerAudio.run();
            currentSegmentDurationMs[0] = SEGMENT_DURATION_MS;
            StatusUpdate s = statuses.get(index[0]);
            String cleanText = StatusController.sanitizeStatusText(s.contentText);

            boolean hasMedia = s.mediaUri != null && !s.mediaUri.trim().isEmpty()
                    && !s.mediaUri.endsWith(".enc")
                    && !"data:image/jpeg;base64,".equals(s.mediaUri.trim());
            boolean isAudio = hasMedia && isAudioStatusUri(s.mediaUri);
            boolean isVideo = hasMedia && !isAudio && isVideoStatusUri(s.mediaUri);

            if (hasMedia && !isAudio) {
                audioPlayerCard.setVisibility(View.GONE);
                root.setBackgroundColor(Color.BLACK);
                photoView.setVisibility(View.VISIBLE);
                loadStatusImage(ctx, s.mediaUri, photoView);

                if (isVideo) {
                    isCurrentSegmentVideo[0] = true;
                    videoContainer.setVisibility(View.VISIBLE);
                    videoView.setVisibility(View.VISIBLE);
                    try {
                        Uri u = Uri.parse(s.mediaUri);
                        String path = "file".equalsIgnoreCase(u.getScheme()) ? u.getPath() : s.mediaUri;
                        File f = new File(path != null ? path : s.mediaUri);
                        if (f.exists()) {
                            videoView.setVideoPath(f.getAbsolutePath());
                        } else {
                            videoView.setVideoURI(u);
                        }
                        videoView.setOnPreparedListener(mp -> {
                            try {
                                mp.setLooping(false);
                                int d = mp.getDuration();
                                if (d > 500) {
                                    currentSegmentDurationMs[0] = Math.min(d, 60_000L);
                                }
                            } catch (Throwable ignored) {}
                            segmentStart[0] = System.currentTimeMillis();
                            pausedElapsed[0] = 0;
                            photoView.setVisibility(View.GONE);
                            if (!paused[0]) {
                                videoView.start();
                            }
                        });
                        videoView.setOnCompletionListener(mp -> {
                            if (!dialog.isShowing()) return;
                            if (index[0] < statuses.size() - 1) {
                                index[0]++;
                                showIndex[0].run();
                            } else {
                                stopViewerAudio.run();
                                dialog.dismiss();
                            }
                        });
                        videoView.setOnErrorListener((mp, what, extra) -> {
                            photoView.setVisibility(View.VISIBLE);
                            return true;
                        });
                    } catch (Throwable t) {
                        photoView.setVisibility(View.VISIBLE);
                    }
                } else {
                    videoContainer.setVisibility(View.GONE);
                }

                if (!cleanText.isEmpty()) {
                    content.setVisibility(View.VISIBLE);
                    content.setText(com.m3.veil.utils.UiAnimator.formatTextWithLargeEmojis(cleanText, 1.38f));
                    content.setTextSize(17.5f);
                    content.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    content.setGravity(Gravity.CENTER);
                    FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                    textLp.setMargins(0, 0, 0, (int) (88 * dp));
                    content.setLayoutParams(textLp);
                    content.setBackgroundColor(Color.parseColor("#99000000"));
                    content.setPadding((int) (20 * dp), (int) (12 * dp), (int) (20 * dp), (int) (12 * dp));
                } else {
                    content.setVisibility(View.GONE);
                }
            } else if (isAudio) {
                videoContainer.setVisibility(View.GONE);
                photoView.setVisibility(View.GONE);
                content.setVisibility(View.GONE);
                root.setBackgroundColor(parseColor(s.bgColorHex));

                isCurrentSegmentAudio[0] = true;
                audioPlayerCard.setVisibility(View.VISIBLE);
                String authorDisplay = s.isMine ? resolveMyDisplayName(ctx) : s.authorName;
                String authorAv = s.isMine
                        ? resolveMyAvatarSource(ctx)
                        : resolvePeerAvatarSource(ctx, s.authorId, s.authorName);
                com.m3.veil.utils.VeilAvatarLoader.loadAvatar(audioCiv, authorAv, authorDisplay, false);

                String captionLabel = !cleanText.isEmpty() ? cleanText : "\uD83C\uDFA4 Voice Status";
                tvAudioCaption.setText(captionLabel);
                viewerWaveform.resetSamples();
                for (int w = 0; w < 28; w++) {
                    viewerWaveform.pushAmplitude(0.25f + 0.65f * (float) Math.abs(Math.sin(w * 0.65f)));
                }
                viewerWaveform.setProgressFraction(0f);
                viewerWaveform.setActive(true);
                ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_pause);

                try {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    String mediaStr = s.mediaUri;
                    if (mediaStr.startsWith("data:audio/")) {
                        int commaIdx = mediaStr.indexOf(',');
                        if (commaIdx > 0) {
                            byte[] audioBytes = android.util.Base64.decode(mediaStr.substring(commaIdx + 1), android.util.Base64.DEFAULT);
                            File tmpFile = new File(ctx.getCacheDir(), "viewer_audio_" + Math.abs(s.id.hashCode()) + ".m4a");
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                                fos.write(audioBytes);
                            }
                            mp.setDataSource(tmpFile.getAbsolutePath());
                        }
                    } else {
                        Uri u = Uri.parse(mediaStr);
                        String path = "file".equalsIgnoreCase(u.getScheme()) ? u.getPath() : mediaStr;
                        File af = new File(path != null ? path : mediaStr);
                        if (af.exists()) {
                            mp.setDataSource(af.getAbsolutePath());
                        } else {
                            mp.setDataSource(ctx, u);
                        }
                    }
                    mp.prepare();
                    int audioDur = mp.getDuration();
                    if (audioDur > 500) {
                        currentSegmentDurationMs[0] = Math.min(60_000L, audioDur + 350L);
                    }
                    int totSec = Math.max(1, audioDur / 1000);
                    tvAudioTimer.setText(String.format(Locale.ROOT, "0:00 / 0:%02d", totSec));
                    mp.setOnCompletionListener(player -> {
                        viewerWaveform.setActive(false);
                        viewerWaveform.setProgressFraction(1f);
                        ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    });
                    mp.start();
                    viewerAudioPlayer[0] = mp;
                } catch (Throwable t) {
                    tvAudioTimer.setText(extractAudioDurationLabel(cleanText));
                }
            } else {
                audioPlayerCard.setVisibility(View.GONE);
                videoContainer.setVisibility(View.GONE);
                photoView.setVisibility(View.GONE);
                root.setBackgroundColor(parseColor(s.bgColorHex));
                content.setVisibility(View.VISIBLE);
                String displayTxt = !cleanText.isEmpty() ? cleanText : "Status update";
                content.setText(com.m3.veil.utils.UiAnimator.formatTextWithLargeEmojis(displayTxt, 1.38f));
                content.setTextSize(26f);
                content.setPadding(0, 0, 0, 0);
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
                int totalV = (s.viewers != null && !s.viewers.isEmpty()) ? Math.max(s.viewCount, s.viewers.size()) : s.viewCount;
                tvViews.setText(totalV + (totalV == 1 ? " view" : " views"));
            }
            if (!s.isMine) {
                s.viewed = true;
                StatusController.getInstance(ctx).markStatusViewed(s.id, s.authorId);
                VeilFeatureManager.getInstance(ctx).markStatusViewed(s.id);
            }
            segmentStart[0] = System.currentTimeMillis();
            pausedElapsed[0] = 0;
        };

        onDeleteSegmentHolder[0] = () -> {
            int removedIdx = index[0];
            if (removedIdx < statuses.size()) {
                String delId = statuses.get(removedIdx).id;
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
                    if (isCurrentSegmentVideo[0]) {
                        try {
                            if (videoView.isPlaying()) videoView.pause();
                        } catch (Throwable ignored) {}
                    }
                    if (isCurrentSegmentAudio[0] && viewerAudioPlayer[0] != null) {
                        try {
                            if (viewerAudioPlayer[0].isPlaying()) {
                                viewerAudioPlayer[0].pause();
                                viewerWaveform.setActive(false);
                                ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_play);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float dy = event.getY() - startY[0];
                    if (isMineGroup && dy < -100 && (System.currentTimeMillis() - downTime[0] < 600)) {
                        // Upward swipe -> open viewers sheet!
                        showStatusViewersBottomSheet(ctx, statuses.get(index[0]), listener, () -> {
                            paused[0] = false;
                            segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                            if (isCurrentSegmentVideo[0]) {
                                try {
                                    videoView.start();
                                } catch (Throwable ignored) {}
                            }
                            if (isCurrentSegmentAudio[0] && viewerAudioPlayer[0] != null) {
                                try {
                                    viewerAudioPlayer[0].start();
                                    viewerWaveform.setActive(true);
                                    ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                                } catch (Throwable ignored) {}
                            }
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
                            stopViewerAudio.run();
                            dialog.dismiss();
                        }
                    } else {
                        if (isCurrentSegmentVideo[0]) {
                            try {
                                videoView.start();
                            } catch (Throwable ignored) {}
                        }
                        if (isCurrentSegmentAudio[0] && viewerAudioPlayer[0] != null) {
                            try {
                                viewerAudioPlayer[0].start();
                                viewerWaveform.setActive(true);
                                ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                            } catch (Throwable ignored) {}
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    paused[0] = false;
                    segmentStart[0] = System.currentTimeMillis() - pausedElapsed[0];
                    if (isCurrentSegmentVideo[0]) {
                        try {
                            videoView.start();
                        } catch (Throwable ignored) {}
                    }
                    if (isCurrentSegmentAudio[0] && viewerAudioPlayer[0] != null) {
                        try {
                            viewerAudioPlayer[0].start();
                            viewerWaveform.setActive(true);
                            ivAudioPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                        } catch (Throwable ignored) {}
                    }
                    return true;
            }
            return false;
        });

        final Runnable refreshCurrentStatusLive = () -> {
            handler.post(() -> {
                if (!dialog.isShowing() || statuses.isEmpty()) return;
                int curIdx = index[0];
                if (curIdx < statuses.size()) {
                    StatusUpdate cur = statuses.get(curIdx);
                    String curId = cur.id;
                    if (StatusController.getInstance(ctx).isStatusPurged(curId)
                            || VeilFeatureManager.getInstance(ctx).isDeletedStatusId(curId)) {
                        Log.i("WhatsAppStatusUi", "Currently viewed status was deleted: " + curId);
                        statuses.remove(curIdx);
                        if (statuses.isEmpty()) {
                            dialog.dismiss();
                        } else {
                            if (index[0] >= statuses.size()) {
                                index[0] = statuses.size() - 1;
                            }
                            if (curIdx < bars.size()) {
                                segments.removeView(bars.get(curIdx));
                                bars.remove(curIdx);
                            }
                            for (int b = 0; b < bars.size(); b++) {
                                bars.get(b).setProgress(b < index[0] ? 1000 : 0);
                            }
                            showIndex[0].run();
                            paused[0] = false;
                            segmentStart[0] = System.currentTimeMillis();
                        }
                        return;
                    }
                    if (isMineGroup) {
                        List<StatusUpdate> freshList = VeilFeatureManager.getInstance(ctx).getStatusUpdates(null);
                        if (freshList != null) {
                            for (StatusUpdate f : freshList) {
                                if (f != null && f.id != null && f.id.equals(curId)) {
                                    cur.viewers = f.viewers;
                                    cur.viewCount = f.viewCount;
                                    int totalV = (cur.viewers != null && !cur.viewers.isEmpty())
                                            ? Math.max(cur.viewCount, cur.viewers.size()) : cur.viewCount;
                                    tvViews.setText(totalV + (totalV == 1 ? " view" : " views"));
                                    break;
                                }
                            }
                        }
                    }
                }
            });
        };

        final StatusController.StatusObserver viewerStatusObserver = refreshCurrentStatusLive::run;
        final VeilFeatureManager.StatusChangeListener vfmStatusListener = refreshCurrentStatusLive::run;
        StatusController.getInstance(ctx).addObserver(viewerStatusObserver);
        VeilFeatureManager.getInstance(ctx).addStatusChangeListener(vfmStatusListener);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            StatusController.getInstance(ctx).removeObserver(viewerStatusObserver);
            VeilFeatureManager.getInstance(ctx).removeStatusChangeListener(vfmStatusListener);
            stopViewerAudio.run();
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
        BottomSheetDialog sheet = new BottomSheetDialog(ctx, R.style.ModernBottomSheetDialogTheme);
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
                if (su != null && su.id != null && su.id.equals(status.id)) {
                    currentStatus = su;
                    break;
                }
            }
        }

        List<StatusViewer> viewers = currentStatus.viewers;
        renderViewersList(ctx, container, emptyState, tvTitle, tvSubtitle, viewers, sheet, parentDialog);

        // Realtime listener for live "Who's Seen" updates
        final VeilFeatureManager.StatusChangeListener liveListener = () -> {
            List<StatusUpdate> liveList = VeilFeatureManager.getInstance(ctx).getStatusUpdates(null);
            if (liveList != null) {
                for (StatusUpdate su : liveList) {
                    if (su != null && su.id != null && su.id.equals(status.id)) {
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
            final StatusUpdate shareTarget = currentStatus;
            btnShare.setOnClickListener(v -> shareStatusExternally(ctx, shareTarget));
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> confirmDeleteStatus(ctx, () -> {
                StatusController.getInstance(ctx).deleteStatus(status.id);
                VeilFeatureManager.getInstance(ctx).deleteMyStatus(status.id);
                sheet.dismiss();
                if (onDeleteSegmentCallback != null) {
                    onDeleteSegmentCallback.run();
                } else if (parentDialog != null && parentDialog.isShowing()) {
                    parentDialog.dismiss();
                }
                if (listener != null) listener.onStatusesChanged();
            }, null));
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
            tvTitle.setText("Viewed by " + count);
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

    public static Bitmap extractVideoFrameBitmap(Context ctx, String uriString) {
        if (ctx == null || uriString == null || uriString.isEmpty()) return null;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            Uri uri = Uri.parse(uriString);
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                mmr.setDataSource(ctx, uri);
            } else {
                String path = "file".equalsIgnoreCase(uri.getScheme()) ? uri.getPath() : uriString;
                File f = new File(path != null ? path : uriString);
                if (f.exists()) {
                    mmr.setDataSource(f.getAbsolutePath());
                } else {
                    mmr.setDataSource(ctx, uri);
                }
            }
            Bitmap frame = mmr.getFrameAtTime(200_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = mmr.getFrameAtTime();
            }
            return frame;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {}
        }
    }

    private static void loadStatusImage(Context ctx, String uriString, ImageView target) {
        if (ctx == null || uriString == null || target == null) return;
        new Thread(() -> {
            try {
                Bitmap bmp = null;
                if (isVideoStatusUri(uriString)) {
                    bmp = extractVideoFrameBitmap(ctx, uriString);
                } else if (uriString.startsWith("data:image") || (uriString.length() > 500 && !uriString.contains("://"))) {
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
                    if (bmp == null) {
                        bmp = extractVideoFrameBitmap(ctx, uriString);
                    }
                }
                if (bmp != null) {
                    final Bitmap finalBmp = bmp;
                    new Handler(Looper.getMainLooper()).post(() -> target.setImageBitmap(finalBmp));
                }
            } catch (Exception ignored) {}
        }).start();
    }

    public interface OnAudienceSelectedCallback {
        void onAudienceSelected(String formattedAudience);
    }

    public static String getFormattedStatusAudience(Context ctx) {
        if (ctx == null) return "Status (Contacts)";
        com.m3.veil.utils.UserPreferences up = com.m3.veil.utils.UserPreferences.getInstance(ctx);
        String type = up.getStatusAudienceType();
        if ("excluded".equals(type)) {
            int count = up.getStatusAudienceExcluded().size();
            return "Contacts except " + count;
        } else if ("included".equals(type)) {
            int count = up.getStatusAudienceIncluded().size();
            return "Only share with " + count;
        } else if ("close_friends".equals(type)) {
            return "Close Friends";
        } else {
            return "Status (Contacts)";
        }
    }

    public static void showAudienceSelectorDialog(Context ctx, OnAudienceSelectedCallback callback) {
        if (ctx == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.ModernBottomSheetDialogTheme);
        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_status_audience_sheet, null);
        sheet.setContentView(view);

        com.m3.veil.utils.UserPreferences up = com.m3.veil.utils.UserPreferences.getInstance(ctx);
        String currentType = up.getStatusAudienceType();

        android.widget.RadioButton rbAll = view.findViewById(R.id.rbAudienceAllContacts);
        android.widget.RadioButton rbExcluded = view.findViewById(R.id.rbAudienceExcluded);
        android.widget.RadioButton rbIncluded = view.findViewById(R.id.rbAudienceIncluded);
        android.widget.RadioButton rbClose = view.findViewById(R.id.rbAudienceCloseFriends);

        TextView tvExcludedSub = view.findViewById(R.id.tvExcludedSubtext);
        TextView tvIncludedSub = view.findViewById(R.id.tvIncludedSubtext);
        TextView tvCloseFriendsSub = view.findViewById(R.id.tvCloseFriendsSubtext);

        final String[] selectedType = {currentType != null ? currentType : "contacts"};

        Runnable updateRadios = () -> {
            if (rbAll != null) rbAll.setChecked("contacts".equals(selectedType[0]));
            if (rbExcluded != null) rbExcluded.setChecked("excluded".equals(selectedType[0]));
            if (rbIncluded != null) rbIncluded.setChecked("included".equals(selectedType[0]));
            if (rbClose != null) rbClose.setChecked("close_friends".equals(selectedType[0]));

            if (tvExcludedSub != null) {
                int count = up.getStatusAudienceExcluded().size();
                tvExcludedSub.setText(count + " excluded \u2022 Tap to edit");
            }
            if (tvIncludedSub != null) {
                int count = up.getStatusAudienceIncluded().size();
                tvIncludedSub.setText(count + " included \u2022 Tap to select");
            }
            if (tvCloseFriendsSub != null) {
                int count = up.getStatusAudienceCloseFriends().size();
                tvCloseFriendsSub.setText(count + " close friends \u2022 Tap to edit");
            }
        };
        updateRadios.run();

        View rowAll = view.findViewById(R.id.rowAudienceAllContacts);
        if (rowAll != null) {
            rowAll.setOnClickListener(v -> {
                selectedType[0] = "contacts";
                updateRadios.run();
            });
        }
        View rowExcluded = view.findViewById(R.id.rowAudienceExcluded);
        if (rowExcluded != null) {
            rowExcluded.setOnClickListener(v -> {
                selectedType[0] = "excluded";
                updateRadios.run();
                showAudienceContactPicker(ctx, "excluded", updateRadios);
            });
        }
        if (tvExcludedSub != null) {
            tvExcludedSub.setOnClickListener(v -> {
                selectedType[0] = "excluded";
                updateRadios.run();
                showAudienceContactPicker(ctx, "excluded", updateRadios);
            });
        }

        View rowIncluded = view.findViewById(R.id.rowAudienceIncluded);
        if (rowIncluded != null) {
            rowIncluded.setOnClickListener(v -> {
                selectedType[0] = "included";
                updateRadios.run();
                showAudienceContactPicker(ctx, "included", updateRadios);
            });
        }
        if (tvIncludedSub != null) {
            tvIncludedSub.setOnClickListener(v -> {
                selectedType[0] = "included";
                updateRadios.run();
                showAudienceContactPicker(ctx, "included", updateRadios);
            });
        }

        View rowClose = view.findViewById(R.id.rowAudienceCloseFriends);
        if (rowClose != null) {
            rowClose.setOnClickListener(v -> {
                selectedType[0] = "close_friends";
                updateRadios.run();
                showAudienceContactPicker(ctx, "close_friends", updateRadios);
            });
        }
        if (tvCloseFriendsSub != null) {
            tvCloseFriendsSub.setOnClickListener(v -> {
                selectedType[0] = "close_friends";
                updateRadios.run();
                showAudienceContactPicker(ctx, "close_friends", updateRadios);
            });
        }

        com.m3.veil.manager.StatusController sc = com.m3.veil.manager.StatusController.getInstance(ctx);
        com.google.android.material.switchmaterial.SwitchMaterial switchStealth =
                view.findViewById(R.id.switchStatusStealthView);
        View rowStealth = view.findViewById(R.id.rowStatusStealthView);
        if (switchStealth != null) {
            switchStealth.setChecked(sc.isStealthViewMode());
            switchStealth.setOnCheckedChangeListener((btn, isChecked) -> sc.setStealthViewMode(isChecked));
        }
        if (rowStealth != null && switchStealth != null) {
            rowStealth.setOnClickListener(v -> switchStealth.setChecked(!switchStealth.isChecked()));
        }

        View btnDone = view.findViewById(R.id.btnAudienceDone);
        if (btnDone != null) {
            btnDone.setOnClickListener(v -> {
                up.setStatusAudienceType(selectedType[0]);
                if ("contacts".equals(selectedType[0])) {
                    sc.setDefaultPrivacyAcl(com.m3.veil.manager.StatusController.PrivacyAcl.ALL_CONTACTS);
                } else if ("close_friends".equals(selectedType[0])) {
                    sc.setDefaultPrivacyAcl(com.m3.veil.manager.StatusController.PrivacyAcl.CLOSE_FRIENDS);
                } else {
                    sc.setDefaultPrivacyAcl(com.m3.veil.manager.StatusController.PrivacyAcl.SELECTED_PEERS);
                }
                sheet.dismiss();
                if (callback != null) {
                    callback.onAudienceSelected(getFormattedStatusAudience(ctx));
                }
            });
        }

        sheet.show();
    }

    public static void showAudienceContactPicker(Context ctx, String mode, Runnable onFinished) {
        if (ctx == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.ModernBottomSheetDialogTheme);
        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_status_mentions, null);
        sheet.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvMentionsCountSubtitle);
        TextView tvHeaderTitle = view.findViewById(R.id.tvMentionsHeaderTitle);
        if (tvHeaderTitle != null) {
            if ("excluded".equals(mode)) {
                tvHeaderTitle.setText("Hide status from...");
            } else if ("close_friends".equals(mode)) {
                tvHeaderTitle.setText("Close friends");
            } else {
                tvHeaderTitle.setText("Share status with...");
            }
        }
        EditText etSearch = view.findViewById(R.id.etMentionsSearch);
        LinearLayout searchContainer = view.findViewById(R.id.mentionsSearchContainer);
        View btnSearchToggle = view.findViewById(R.id.btnMentionsSearch);
        View btnClearSearch = view.findViewById(R.id.btnClearMentionsSearch);
        View hsvSelected = view.findViewById(R.id.hsvSelectedMentions);
        LinearLayout llSelected = view.findViewById(R.id.llSelectedMentionsContainer);
        View divider = view.findViewById(R.id.dividerMentions);
        LinearLayout listContainer = view.findViewById(R.id.llMentionsContactsList);
        View fabConfirm = view.findViewById(R.id.fabConfirmMentions);

        com.m3.veil.utils.UserPreferences up = com.m3.veil.utils.UserPreferences.getInstance(ctx);
        java.util.Set<String> initialSet;
        if ("excluded".equals(mode)) {
            initialSet = new java.util.HashSet<>(up.getStatusAudienceExcluded());
        } else if ("close_friends".equals(mode)) {
            initialSet = new java.util.HashSet<>(up.getStatusAudienceCloseFriends());
        } else {
            initialSet = new java.util.HashSet<>(up.getStatusAudienceIncluded());
        }

        final java.util.Set<String> workingSet = new java.util.HashSet<>(initialSet);
        final java.util.Map<String, String> selectedContactMap = new java.util.LinkedHashMap<>();
        final List<com.m3.veil.model.Chat> chats = com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getAllChats(false);
        final float dp = ctx.getResources().getDisplayMetrics().density;

        if (chats != null) {
            for (com.m3.veil.model.Chat c : chats) {
                if (c != null && workingSet.contains(c.getId())) {
                    selectedContactMap.put(c.getId(), c.getName() != null && !c.getName().isEmpty() ? c.getName() : "Contact");
                }
            }
        }

        final Runnable[] populateListHolder = new Runnable[1];
        final Runnable[] refreshStripHolder = new Runnable[1];

        Runnable updateCountLabel = () -> {
            if (tvTitle != null) {
                String label;
                if ("excluded".equals(mode)) {
                    label = workingSet.size() + " contacts excluded";
                } else if ("close_friends".equals(mode)) {
                    label = workingSet.size() + " close friends";
                } else {
                    label = workingSet.size() + " contacts included";
                }
                tvTitle.setText(label);
            }
        };

        Runnable refreshSelectedStrip = () -> {
            if (llSelected == null || hsvSelected == null) return;
            llSelected.removeAllViews();
            if (selectedContactMap.isEmpty()) {
                hsvSelected.setVisibility(View.GONE);
                if (divider != null) divider.setVisibility(View.GONE);
            } else {
                hsvSelected.setVisibility(View.VISIBLE);
                if (divider != null) divider.setVisibility(View.VISIBLE);

                for (java.util.Map.Entry<String, String> entry : selectedContactMap.entrySet()) {
                    String cId = entry.getKey();
                    String cName = entry.getValue();

                    LinearLayout chip = new LinearLayout(ctx);
                    chip.setOrientation(LinearLayout.VERTICAL);
                    chip.setGravity(Gravity.CENTER_HORIZONTAL);
                    chip.setPadding((int) (6 * dp), 0, (int) (6 * dp), 0);

                    FrameLayout frame = new FrameLayout(ctx);
                    int avatarSize = (int) (48 * dp);
                    frame.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));

                    de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
                    civ.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, null, cName, false);
                    frame.addView(civ);

                    ImageView ivRemove = new ImageView(ctx);
                    int rSize = (int) (18 * dp);
                    FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(rSize, rSize, Gravity.BOTTOM | Gravity.END);
                    ivRemove.setLayoutParams(rlp);
                    ivRemove.setImageResource(R.drawable.ic_close);
                    ivRemove.setBackgroundResource(R.drawable.bg_persona_avatar_circle);
                    ivRemove.getBackground().setTint(ContextCompat.getColor(ctx, R.color.colorPrimary));
                    ivRemove.setColorFilter(Color.WHITE);
                    ivRemove.setPadding((int) (3 * dp), (int) (3 * dp), (int) (3 * dp), (int) (3 * dp));
                    frame.addView(ivRemove);

                    chip.addView(frame);

                    TextView tvName = new TextView(ctx);
                    tvName.setText(cName);
                    tvName.setTextSize(11f);
                    tvName.setMaxLines(1);
                    tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
                    tvName.setLayoutParams(new LinearLayout.LayoutParams((int) (52 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));
                    tvName.setGravity(Gravity.CENTER);
                    chip.addView(tvName);

                    chip.setOnClickListener(v -> {
                        workingSet.remove(cId);
                        selectedContactMap.remove(cId);
                        updateCountLabel.run();
                        if (refreshStripHolder[0] != null) refreshStripHolder[0].run();
                        if (populateListHolder[0] != null) populateListHolder[0].run();
                    });

                    llSelected.addView(chip);
                }
            }
        };
        refreshStripHolder[0] = refreshSelectedStrip;

        Runnable populateList = () -> {
            if (listContainer == null || chats == null) return;
            listContainer.removeAllViews();
            String query = etSearch != null && etSearch.getText() != null ? etSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT) : "";

            for (com.m3.veil.model.Chat c : chats) {
                if (c == null || c.isArchived() || c.isSecret()) continue;
                String chatId = c.getId();
                String name = c.getName() != null && !c.getName().isEmpty() ? c.getName() : "Contact";
                if (!query.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                    continue;
                }

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));

                de.hdodenhof.circleimageview.CircleImageView civ = new de.hdodenhof.circleimageview.CircleImageView(ctx);
                civ.setLayoutParams(new LinearLayout.LayoutParams((int) (44 * dp), (int) (44 * dp)));
                com.m3.veil.utils.VeilAvatarLoader.loadAvatar(civ, c.getAvatar(), name, false);
                row.addView(civ);

                LinearLayout textCol = new LinearLayout(ctx);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.setMarginStart((int) (14 * dp));
                textCol.setLayoutParams(tlp);

                TextView tvName = new TextView(ctx);
                tvName.setText(name);
                tvName.setTextSize(15.5f);
                tvName.setTypeface(null, Typeface.BOLD);
                tvName.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
                textCol.addView(tvName);

                TextView tvSub = new TextView(ctx);
                tvSub.setText(c.getHandle() != null ? "@" + c.getHandle() : (c.isGroup() ? "Group" : "Online"));
                tvSub.setTextSize(12.5f);
                tvSub.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
                textCol.addView(tvSub);

                row.addView(textCol);

                ImageView ivCheck = new ImageView(ctx);
                int checkSize = (int) (26 * dp);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(checkSize, checkSize);
                ivCheck.setLayoutParams(clp);
                row.addView(ivCheck);

                Runnable renderRow = () -> {
                    boolean sel = workingSet.contains(chatId);
                    if (sel) {
                        row.setBackgroundColor(Color.parseColor("#154F46E5"));
                        ivCheck.setVisibility(View.VISIBLE);
                        ivCheck.setImageResource(R.drawable.ic_check);
                        ivCheck.setColorFilter(Color.WHITE);
                        ivCheck.setBackgroundResource(R.drawable.bg_persona_avatar_circle);
                        if (ivCheck.getBackground() != null) {
                            ivCheck.getBackground().setTint(ContextCompat.getColor(ctx, R.color.colorPrimary));
                        }
                    } else {
                        row.setBackgroundColor(Color.TRANSPARENT);
                        ivCheck.setVisibility(View.VISIBLE);
                        ivCheck.setImageResource(0);
                        ivCheck.setBackgroundResource(R.drawable.bg_persona_avatar_circle);
                        if (ivCheck.getBackground() != null) {
                            ivCheck.getBackground().setTint(Color.parseColor("#3394A3B8"));
                        }
                    }
                };
                renderRow.run();

                row.setOnClickListener(v -> {
                    if (workingSet.contains(chatId)) {
                        workingSet.remove(chatId);
                        selectedContactMap.remove(chatId);
                    } else {
                        workingSet.add(chatId);
                        selectedContactMap.put(chatId, name);
                    }
                    renderRow.run();
                    updateCountLabel.run();
                    refreshSelectedStrip.run();
                });

                listContainer.addView(row);
            }
        };

        populateListHolder[0] = populateList;

        if (btnSearchToggle != null && searchContainer != null) {
            btnSearchToggle.setOnClickListener(v -> {
                boolean isShown = searchContainer.getVisibility() == View.VISIBLE;
                searchContainer.setVisibility(isShown ? View.GONE : View.VISIBLE);
                if (searchContainer.getVisibility() == View.VISIBLE && etSearch != null) {
                    etSearch.requestFocus();
                }
            });
        }
        if (btnClearSearch != null && etSearch != null) {
            btnClearSearch.setOnClickListener(v -> etSearch.setText(""));
        }
        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { populateList.run(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        updateCountLabel.run();
        refreshSelectedStrip.run();
        populateList.run();

        if (fabConfirm != null) {
            fabConfirm.setOnClickListener(v -> {
                if ("excluded".equals(mode)) {
                    up.setStatusAudienceExcluded(workingSet);
                } else if ("close_friends".equals(mode)) {
                    up.setStatusAudienceCloseFriends(workingSet);
                } else {
                    up.setStatusAudienceIncluded(workingSet);
                }
                up.setStatusAudienceType(mode);

                com.m3.veil.manager.StatusController sc = com.m3.veil.manager.StatusController.getInstance(ctx);
                if ("close_friends".equals(mode)) {
                    sc.setDefaultPrivacyAcl(com.m3.veil.manager.StatusController.PrivacyAcl.CLOSE_FRIENDS);
                    for (String p : workingSet) {
                        sc.setCloseFriend(p, true);
                    }
                } else if ("contacts".equals(mode)) {
                    sc.setDefaultPrivacyAcl(com.m3.veil.manager.StatusController.PrivacyAcl.ALL_CONTACTS);
                } else {
                    sc.setDefaultPrivacyAcl(com.m3.veil.manager.StatusController.PrivacyAcl.SELECTED_PEERS);
                }

                if (onFinished != null) onFinished.run();
                sheet.dismiss();
                String desc = "excluded".equals(mode) ? "excluded" : ("close_friends".equals(mode) ? "close friends" : "included");
                Toast.makeText(ctx, "Privacy updated: " + workingSet.size() + " " + desc, Toast.LENGTH_SHORT).show();
            });
        }

        View btnBack = view.findViewById(R.id.btnMentionsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> sheet.dismiss());
        }

        sheet.show();
    }

    private static FrameLayout circleIconButton(Context ctx, float dp, int drawableResId) {
        FrameLayout f = new FrameLayout(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp));
        lp.leftMargin = (int) (8 * dp);
        f.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#44000000"));
        f.setBackground(bg);

        ImageView iv = new ImageView(ctx);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams((int) (22 * dp), (int) (22 * dp), Gravity.CENTER);
        iv.setLayoutParams(ivLp);
        iv.setImageResource(drawableResId);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        f.addView(iv);
        return f;
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
