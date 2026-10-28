package com.m3.veil.ui.chat.sheets;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilMessagesController;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.MediaViewerActivity;
import com.m3.veil.utils.FileMediaManager;
import com.m3.veil.utils.VoicePlayerManager;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WhatsApp / Telegram-grade Shared Media, Links & Docs Console (`SharedMediaGalleryBottomSheet`).
 * Features:
 * - 4 dedicated tabs matching WhatsApp/Telegram: Media (Photos/Videos Grid), Docs (Original Filenames), Links (URL Preview), Voice
 * - 3-column photo & video grid with zero-delay thumbnail rendering and duration badges
 * - Tapping photo/video opens fullscreen MediaViewerActivity
 * - Tapping document opens external file viewer with exact original filename
 * - Tapping link opens browser directly
 */
public class SharedMediaGalleryBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_CHAT_ID = "chatId";
    private static final String ARG_CHAT_NAME = "chatName";

    public static SharedMediaGalleryBottomSheet newInstance(String chatId, String chatName) {
        SharedMediaGalleryBottomSheet sheet = new SharedMediaGalleryBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CHAT_ID, chatId);
        args.putString(ARG_CHAT_NAME, chatName);
        sheet.setArguments(args);
        return sheet;
    }

    private String chatId;
    private String chatName;

    private static final int TAB_MEDIA = 0;
    private static final int TAB_DOCS = 1;
    private static final int TAB_LINKS = 2;
    private static final int TAB_VOICE = 3;

    private int activeTab = TAB_MEDIA;

    private TextView tabMediaBtn;
    private TextView tabDocsBtn;
    private TextView tabLinksBtn;
    private TextView tabVoiceBtn;

    private LinearLayout containerContent;
    private ProgressBar loadingBar;
    private TextView tvEmptyState;

    private final List<Message> allMessages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault());
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            chatId = getArguments().getString(ARG_CHAT_ID);
            chatName = getArguments().getString(ARG_CHAT_NAME);
        }
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet_modern);
        int padH = (int) (18 * dp);
        root.setPadding(padH, (int) (12 * dp), padH, (int) (18 * dp));

        // Drag handle
        View handle = new View(ctx);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (4 * dp));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = (int) (12 * dp);
        handle.setLayoutParams(hLp);
        handle.setBackgroundResource(R.drawable.bg_voice_circle_incoming);
        root.addView(handle);

        // Header Title
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(chatName != null ? chatName : "Media, Links & Docs");
        tvTitle.setTextSize(17.5f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(ctx);
        tvSubtitle.setText("End-to-End Encrypted Vault Storage");
        tvSubtitle.setTextSize(12f);
        tvSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        tvSubtitle.setPadding(0, 0, 0, (int) (12 * dp));
        root.addView(tvSubtitle);

        // Segmented Tabs Row (Media | Docs | Links | Voice)
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundResource(R.drawable.bg_filter_chip);
        tabRow.setPadding((int) (4 * dp), (int) (4 * dp), (int) (4 * dp), (int) (4 * dp));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * dp));
        tLp.bottomMargin = (int) (14 * dp);
        tabRow.setLayoutParams(tLp);

        tabMediaBtn = createTabButton("Media", TAB_MEDIA, dp);
        tabDocsBtn = createTabButton("Docs", TAB_DOCS, dp);
        tabLinksBtn = createTabButton("Links", TAB_LINKS, dp);
        tabVoiceBtn = createTabButton("Voice", TAB_VOICE, dp);

        tabRow.addView(tabMediaBtn);
        tabRow.addView(tabDocsBtn);
        tabRow.addView(tabLinksBtn);
        tabRow.addView(tabVoiceBtn);
        root.addView(tabRow);

        loadingBar = new ProgressBar(ctx);
        loadingBar.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = Gravity.CENTER;
        loadingBar.setLayoutParams(pbLp);
        root.addView(loadingBar);

        tvEmptyState = new TextView(ctx);
        tvEmptyState.setTextSize(14f);
        tvEmptyState.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        tvEmptyState.setGravity(Gravity.CENTER);
        tvEmptyState.setPadding(0, (int) (40 * dp), 0, (int) (40 * dp));
        tvEmptyState.setVisibility(View.GONE);
        root.addView(tvEmptyState);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (440 * dp)));

        containerContent = new LinearLayout(ctx);
        containerContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(containerContent);
        root.addView(scroll);

        loadVaultMessages();
        return root;
    }

    private TextView createTabButton(String label, int tabIndex, float dp) {
        TextView tv = new TextView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tv.setLayoutParams(lp);
        tv.setGravity(Gravity.CENTER);
        tv.setText(label);
        tv.setTextSize(13f);
        boolean sel = (tabIndex == activeTab);
        tv.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(sel ? Color.WHITE : ContextCompat.getColor(requireContext(), R.color.textSecondary));
        if (sel) {
            GradientDrawable selBg = new GradientDrawable();
            selBg.setCornerRadius(8 * dp);
            selBg.setColor(Color.parseColor("#00A884"));
            tv.setBackground(selBg);
        } else {
            tv.setBackground(null);
        }

        tv.setOnClickListener(v -> {
            activeTab = tabIndex;
            updateTabButtonStyles(dp);
            renderActiveTab();
        });
        return tv;
    }

    private void updateTabButtonStyles(float dp) {
        TextView[] tabs = {tabMediaBtn, tabDocsBtn, tabLinksBtn, tabVoiceBtn};
        for (int i = 0; i < tabs.length; i++) {
            TextView t = tabs[i];
            if (t == null) continue;
            boolean sel = (i == activeTab);
            t.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(sel ? Color.WHITE : ContextCompat.getColor(requireContext(), R.color.textSecondary));
            if (sel) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setCornerRadius(8 * dp);
                selBg.setColor(Color.parseColor("#00A884"));
                t.setBackground(selBg);
            } else {
                t.setBackground(null);
            }
        }
    }

    private void loadVaultMessages() {
        if (chatId == null) return;
        executor.execute(() -> {
            List<Message> msgs = EncryptedChatDatabase.getInstance(requireContext()).getMessagesForChat(chatId);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                loadingBar.setVisibility(View.GONE);
                allMessages.clear();
                if (msgs != null) allMessages.addAll(msgs);
                renderActiveTab();
            });
        });
    }

    private void renderActiveTab() {
        if (containerContent == null || !isAdded()) return;
        containerContent.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        switch (activeTab) {
            case TAB_MEDIA:
                renderMediaTab(dp);
                break;
            case TAB_DOCS:
                renderDocsTab(dp);
                break;
            case TAB_LINKS:
                renderLinksTab(dp);
                break;
            case TAB_VOICE:
                renderVoiceTab(dp);
                break;
        }
    }

    /**
     * Tab 1: MEDIA (3-Column Photo & Video Gallery Grid)
     */
    private void renderMediaTab(float dp) {
        List<Message> mediaList = new ArrayList<>();
        for (Message m : allMessages) {
            if ("image".equals(m.getType()) || "video".equals(m.getType()) || "doodle".equals(m.getType())) {
                mediaList.add(m);
            }
        }

        if (mediaList.isEmpty()) {
            tvEmptyState.setText("No photos or videos shared yet in this chat");
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        tvEmptyState.setVisibility(View.GONE);

        // Group into rows of 3
        LinearLayout currentRow = null;
        for (int i = 0; i < mediaList.size(); i++) {
            if (i % 3 == 0) {
                currentRow = new LinearLayout(requireContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.bottomMargin = (int) (4 * dp);
                currentRow.setLayoutParams(rLp);
                containerContent.addView(currentRow);
            }

            final Message m = mediaList.get(i);
            FrameLayout cell = new FrameLayout(requireContext());
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, (int) (110 * dp), 1f);
            if (i % 3 != 0) cLp.leftMargin = (int) (4 * dp);
            cell.setLayoutParams(cLp);
            cell.setBackgroundColor(Color.parseColor("#1C1E24"));

            ImageView ivThumb = new ImageView(requireContext());
            ivThumb.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(ivThumb);

            // Load thumbnail asynchronously
            loadThumbnailAsync(m, ivThumb);

            // Video indicator badge
            if ("video".equals(m.getType())) {
                LinearLayout vidBadge = new LinearLayout(requireContext());
                vidBadge.setOrientation(LinearLayout.HORIZONTAL);
                vidBadge.setGravity(Gravity.CENTER_VERTICAL);
                vidBadge.setPadding((int) (6 * dp), (int) (3 * dp), (int) (6 * dp), (int) (3 * dp));
                GradientDrawable vBg = new GradientDrawable();
                vBg.setColor(Color.parseColor("#AA000000"));
                vBg.setCornerRadius(6 * dp);
                vidBadge.setBackground(vBg);

                FrameLayout.LayoutParams vbLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END);
                vbLp.setMargins(0, 0, (int) (4 * dp), (int) (4 * dp));
                vidBadge.setLayoutParams(vbLp);

                TextView tvPlay = new TextView(requireContext());
                tvPlay.setText("▶ Video");
                tvPlay.setTextColor(Color.WHITE);
                tvPlay.setTextSize(10f);
                tvPlay.setTypeface(null, Typeface.BOLD);
                vidBadge.addView(tvPlay);

                cell.addView(vidBadge);
            }

            // Click to open in MediaViewerActivity
            cell.setOnClickListener(v -> {
                String path = m.getLocalFilePath();
                if (path != null && new File(path).exists()) {
                    Intent viewerIntent = new Intent(requireContext(), MediaViewerActivity.class);
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, path);
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "video".equals(m.getType()) ? "video" : "image");
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, m.getSenderName() != null ? m.getSenderName() : "Media");
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_MESSAGE_ID, m.getId());
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_CHAT_ID, chatId);
                    startActivity(viewerIntent);
                } else {
                    Toast.makeText(requireContext(), "Downloading media...", Toast.LENGTH_SHORT).show();
                }
            });

            if (currentRow != null) {
                currentRow.addView(cell);
            }
        }

        // Pad last row if fewer than 3 items
        if (currentRow != null && currentRow.getChildCount() < 3) {
            int missing = 3 - currentRow.getChildCount();
            for (int k = 0; k < missing; k++) {
                View placeholder = new View(requireContext());
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, (int) (110 * dp), 1f);
                pLp.leftMargin = (int) (4 * dp);
                placeholder.setLayoutParams(pLp);
                currentRow.addView(placeholder);
            }
        }
    }

    private void loadThumbnailAsync(Message m, ImageView iv) {
        executor.execute(() -> {
            Bitmap thumb = null;
            if (m.getLocalFilePath() != null) {
                File f = new File(m.getLocalFilePath());
                if (f.exists()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    thumb = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                }
            }
            if (thumb == null && m.getThumbnail() != null && !m.getThumbnail().isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(m.getThumbnail(), Base64.DEFAULT);
                    thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                } catch (Exception ignored) {}
            }
            final Bitmap finalThumb = thumb;
            mainHandler.post(() -> {
                if (isAdded() && iv != null) {
                    if (finalThumb != null) {
                        iv.setImageBitmap(finalThumb);
                    } else {
                        iv.setImageResource(R.drawable.ic_camera);
                        iv.setColorFilter(Color.parseColor("#44FFFFFF"));
                    }
                }
            });
        });
    }

    /**
     * Tab 2: DOCS (Clean original filenames with colored extension badges)
     */
    private void renderDocsTab(float dp) {
        List<Message> docList = new ArrayList<>();
        for (Message m : allMessages) {
            if ("document".equals(m.getType())) {
                docList.add(m);
            }
        }

        if (docList.isEmpty()) {
            tvEmptyState.setText("No documents or files shared yet in this chat");
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        tvEmptyState.setVisibility(View.GONE);

        for (Message m : docList) {
            String rawContent = m.getContent() != null ? m.getContent() : "";
            String rawFileName = rawContent;
            String fileSizeStr = "";
            if (rawContent.contains("|")) {
                int pipe = rawContent.indexOf("|");
                rawFileName = rawContent.substring(0, pipe);
                fileSizeStr = rawContent.substring(pipe + 1);
            }

            final String cleanFileName = VeilMessagesController.cleanDocumentFileName(rawFileName);
            String detectedExt = "FILE";
            int dot = cleanFileName.lastIndexOf('.');
            if (dot != -1 && dot < cleanFileName.length() - 1) {
                detectedExt = cleanFileName.substring(dot + 1).toUpperCase(Locale.ROOT);
            }
            final String ext = detectedExt;

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
            row.setClickable(true);
            row.setFocusable(true);

            // Colored Document Icon Badge
            FrameLayout badge = new FrameLayout(requireContext());
            badge.setLayoutParams(new LinearLayout.LayoutParams((int) (44 * dp), (int) (44 * dp)));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(10 * dp);
            badgeBg.setColor(FileMediaManager.getDocumentBadgeColor(ext));
            badge.setBackground(badgeBg);

            TextView tvExt = new TextView(requireContext());
            FrameLayout.LayoutParams extLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            tvExt.setLayoutParams(extLp);
            tvExt.setText(ext.length() > 4 ? ext.substring(0, 4) : ext);
            tvExt.setTextColor(Color.WHITE);
            tvExt.setTextSize(11f);
            tvExt.setTypeface(null, Typeface.BOLD);
            badge.addView(tvExt);
            row.addView(badge);

            // Document Metadata
            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = (int) (12 * dp);
            colLp.rightMargin = (int) (8 * dp);
            col.setLayoutParams(colLp);

            TextView tvName = new TextView(requireContext());
            tvName.setText(cleanFileName);
            tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
            tvName.setTextSize(14.5f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setMaxLines(1);
            col.addView(tvName);

            String dateStr = m.getTimestamp() != null ? dateFormat.format(m.getTimestamp().toDate()) : "";
            TextView tvSub = new TextView(requireContext());
            tvSub.setText((fileSizeStr.isEmpty() ? "" : fileSizeStr + " • ") + dateStr);
            tvSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvSub.setTextSize(12f);
            col.addView(tvSub);

            row.addView(col);

            row.setOnClickListener(v -> {
                File f = FileMediaManager.getDecryptedFile(requireContext(), m, ext.toLowerCase(Locale.ROOT));
                if (f != null && f.exists()) {
                    FileMediaManager.openDocumentWithOriginalName(requireContext(), m, f);
                } else {
                    Toast.makeText(requireContext(), "File is staged or downloading", Toast.LENGTH_SHORT).show();
                }
            });

            containerContent.addView(row);

            View div = new View(requireContext());
            div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(Color.parseColor("#15FFFFFF"));
            containerContent.addView(div);
        }
    }

    /**
     * Tab 3: LINKS (Extracted web links with instant browser launcher)
     */
    private void renderLinksTab(float dp) {
        List<LinkItem> linkList = new ArrayList<>();
        for (Message m : allMessages) {
            String text = m.getText();
            if (text != null && (text.contains("http://") || text.contains("https://") || text.contains("www."))) {
                Matcher matcher = URL_PATTERN.matcher(text);
                while (matcher.find()) {
                    String url = matcher.group();
                    linkList.add(new LinkItem(url, text, m.getTimestamp()));
                }
            }
        }

        if (linkList.isEmpty()) {
            tvEmptyState.setText("No web links shared yet in this chat");
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        tvEmptyState.setVisibility(View.GONE);

        for (LinkItem item : linkList) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
            row.setClickable(true);
            row.setFocusable(true);

            // Globe/Link Icon Badge
            FrameLayout badge = new FrameLayout(requireContext());
            badge.setLayoutParams(new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp)));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(10 * dp);
            badgeBg.setColor(Color.parseColor("#007AFF"));
            badge.setBackground(badgeBg);

            TextView tvGlobe = new TextView(requireContext());
            FrameLayout.LayoutParams gLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            tvGlobe.setLayoutParams(gLp);
            tvGlobe.setText("🌐");
            tvGlobe.setTextSize(16f);
            badge.addView(tvGlobe);
            row.addView(badge);

            // Metadata Column
            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = (int) (12 * dp);
            col.setLayoutParams(colLp);

            TextView tvUrl = new TextView(requireContext());
            tvUrl.setText(item.url);
            tvUrl.setTextColor(Color.parseColor("#29B6F6"));
            tvUrl.setTextSize(14f);
            tvUrl.setTypeface(null, Typeface.BOLD);
            tvUrl.setMaxLines(1);
            col.addView(tvUrl);

            TextView tvSnippet = new TextView(requireContext());
            tvSnippet.setText(item.contextSnippet);
            tvSnippet.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvSnippet.setTextSize(12f);
            tvSnippet.setMaxLines(2);
            col.addView(tvSnippet);

            row.addView(col);

            row.setOnClickListener(v -> {
                try {
                    String openUrl = item.url;
                    if (!openUrl.startsWith("http://") && !openUrl.startsWith("https://")) {
                        openUrl = "https://" + openUrl;
                    }
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(openUrl));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Could not open link: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

            containerContent.addView(row);

            View div = new View(requireContext());
            div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(Color.parseColor("#15FFFFFF"));
            containerContent.addView(div);
        }
    }

    /**
     * Tab 4: VOICE (Voice notes & audio clips)
     */
    private void renderVoiceTab(float dp) {
        List<Message> voiceList = new ArrayList<>();
        for (Message m : allMessages) {
            if ("voice".equals(m.getType()) || "audio".equals(m.getType())) {
                voiceList.add(m);
            }
        }

        if (voiceList.isEmpty()) {
            tvEmptyState.setText("No voice messages shared yet in this chat");
            tvEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        tvEmptyState.setVisibility(View.GONE);

        for (Message m : voiceList) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));

            // Mic Icon Badge
            FrameLayout badge = new FrameLayout(requireContext());
            badge.setLayoutParams(new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp)));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(21 * dp);
            badgeBg.setColor(Color.parseColor("#8B5CF6"));
            badge.setBackground(badgeBg);

            TextView tvMic = new TextView(requireContext());
            FrameLayout.LayoutParams gLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            tvMic.setLayoutParams(gLp);
            tvMic.setText("🎤");
            tvMic.setTextSize(16f);
            badge.addView(tvMic);
            row.addView(badge);

            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = (int) (12 * dp);
            col.setLayoutParams(colLp);

            int durMs = VoicePlayerManager.getInstance().getAudioDuration(requireContext(), m);
            String durFormatted = VoicePlayerManager.formatDuration(durMs);

            TextView tvVoiceTitle = new TextView(requireContext());
            tvVoiceTitle.setText("Voice Note • " + durFormatted);
            tvVoiceTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
            tvVoiceTitle.setTextSize(14f);
            tvVoiceTitle.setTypeface(null, Typeface.BOLD);
            col.addView(tvVoiceTitle);

            String dateStr = m.getTimestamp() != null ? dateFormat.format(m.getTimestamp().toDate()) : "";
            TextView tvSub = new TextView(requireContext());
            tvSub.setText(dateStr + (m.getSenderName() != null ? " • " + m.getSenderName() : ""));
            tvSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvSub.setTextSize(12f);
            col.addView(tvSub);

            row.addView(col);

            row.setOnClickListener(v -> {
                String path = m.getLocalFilePath();
                if (path != null && new File(path).exists()) {
                    VoicePlayerManager.getInstance().togglePlay(requireContext(), m, new VoicePlayerManager.PlaybackListener() {
                        @Override
                        public void onStateChanged(boolean isPlaying) {
                            if (isAdded()) {
                                row.post(() -> tvMic.setText(isPlaying ? "⏸️" : "🎤"));
                            }
                        }

                        @Override
                        public void onProgressUpdate(int currentPosition, int maxDuration) {}
                    });
                } else {
                    Toast.makeText(requireContext(), "Voice note downloading...", Toast.LENGTH_SHORT).show();
                }
            });

            containerContent.addView(row);

            View div = new View(requireContext());
            div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(Color.parseColor("#15FFFFFF"));
            containerContent.addView(div);
        }
    }

    private static class LinkItem {
        final String url;
        final String contextSnippet;
        final com.google.firebase.Timestamp timestamp;

        LinkItem(String url, String contextSnippet, com.google.firebase.Timestamp timestamp) {
            this.url = url;
            this.contextSnippet = contextSnippet;
            this.timestamp = timestamp;
        }
    }
}
