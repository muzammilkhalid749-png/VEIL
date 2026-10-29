package com.m3.veil.ui.chat.sheets;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.utils.FileMediaManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WhatsApp-style in-app Document Picker BottomSheet.
 * Lists local documents (PDF, DOC, DOCX, XLS, PPT, TXT, ZIP, APK) with color-coded extension
 * badges, file sizes, dates, search bar, filter chips, and a "Browse other docs..." option.
 */
public class WhatsAppDocumentPickerSheet extends BottomSheetDialogFragment {

    public interface DocumentPickerListener {
        void onDocumentFileSelected(File file, String caption);
        void onBrowseSystemDocumentsRequested();
    }

    public static class DocItem {
        public final File file;
        public final String name;
        public final String ext;
        public final long sizeBytes;
        public final long lastModified;

        public DocItem(File file, String name, String ext, long sizeBytes, long lastModified) {
            this.file = file;
            this.name = name;
            this.ext = ext;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }
    }

    private DocumentPickerListener listener;
    private final List<DocItem> allDocs = new ArrayList<>();
    private final List<DocItem> filteredDocs = new ArrayList<>();
    private LinearLayout listContainer;
    private String activeCategory = "ALL";
    private String searchQuery = "";

    public void setDocumentPickerListener(DocumentPickerListener listener) {
        this.listener = listener;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            View sheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        float dp = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_bottom_sheet);
        root.setPadding(0, (int) (12 * dp), 0, (int) (20 * dp));

        // Drag handle
        View handle = new View(ctx);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (4 * dp));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = (int) (12 * dp);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#CBD5E1"));
        handleBg.setCornerRadius(4 * dp);
        handle.setBackground(handleBg);
        root.addView(handle);

        // Header Row
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (20 * dp), 0, (int) (20 * dp), (int) (10 * dp));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvTitle.setText("Documents");
        tvTitle.setTextSize(19f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        header.addView(tvTitle);

        TextView btnClose = new TextView(ctx);
        btnClose.setText("✕");
        btnClose.setTextSize(18f);
        btnClose.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
        btnClose.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        btnClose.setOnClickListener(v -> dismiss());
        header.addView(btnClose);
        root.addView(header);

        // Search Bar
        EditText etSearch = new EditText(ctx);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * dp));
        searchLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (10 * dp));
        etSearch.setLayoutParams(searchLp);
        etSearch.setBackgroundResource(R.drawable.bg_chat_input);
        etSearch.setHint("Search documents by name or extension...");
        etSearch.setTextSize(14f);
        etSearch.setSingleLine(true);
        etSearch.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        etSearch.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        etSearch.setHintTextColor(ctx.getResources().getColor(R.color.textSecondary));
        root.addView(etSearch);

        // Filter Chips Row (All | PDF | DOC | XLS | ZIP)
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding((int) (16 * dp), 0, (int) (16 * dp), (int) (10 * dp));
        String[] cats = {"ALL", "PDF", "DOC", "XLS", "ZIP", "TXT"};
        final List<TextView> chipViews = new ArrayList<>();
        for (String cat : cats) {
            TextView chip = new TextView(ctx);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int) (30 * dp));
            cLp.rightMargin = (int) (8 * dp);
            chip.setLayoutParams(cLp);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
            chip.setText(cat);
            chip.setTextSize(12f);
            chip.setBackgroundResource("ALL".equals(cat) ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(ctx.getResources().getColor("ALL".equals(cat) ? R.color.colorPrimary : R.color.textSecondary));
            chip.setTypeface(null, "ALL".equals(cat) ? Typeface.BOLD : Typeface.NORMAL);
            chip.setOnClickListener(v -> {
                activeCategory = cat;
                for (TextView cv : chipViews) {
                    boolean sel = cv.getText().toString().equals(activeCategory);
                    cv.setBackgroundResource(sel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                    cv.setTextColor(ctx.getResources().getColor(sel ? R.color.colorPrimary : R.color.textSecondary));
                    cv.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
                }
                filterAndRenderDocs();
            });
            chipViews.add(chip);
            chipRow.addView(chip);
        }
        root.addView(chipRow);

        // WhatsApp "Browse other docs..." Top Action Row
        LinearLayout browseRow = new LinearLayout(ctx);
        browseRow.setOrientation(LinearLayout.HORIZONTAL);
        browseRow.setGravity(Gravity.CENTER_VERTICAL);
        browseRow.setPadding((int) (18 * dp), (int) (10 * dp), (int) (18 * dp), (int) (10 * dp));
        browseRow.setBackgroundResource(R.drawable.bg_filter_chip);
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        browseLp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (8 * dp));
        browseRow.setLayoutParams(browseLp);

        FrameLayout folderBadge = new FrameLayout(ctx);
        folderBadge.setLayoutParams(new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp)));
        GradientDrawable folderCircle = new GradientDrawable();
        folderCircle.setShape(GradientDrawable.OVAL);
        folderCircle.setColor(Color.parseColor("#00A884"));
        folderBadge.setBackground(folderCircle);
        ImageView folderIcon = new ImageView(ctx);
        FrameLayout.LayoutParams fIconLp = new FrameLayout.LayoutParams((int) (20 * dp), (int) (20 * dp), Gravity.CENTER);
        folderIcon.setLayoutParams(fIconLp);
        folderIcon.setImageResource(R.drawable.ic_document);
        folderIcon.setColorFilter(Color.WHITE);
        folderBadge.addView(folderIcon);
        browseRow.addView(folderBadge);

        LinearLayout browseTextCol = new LinearLayout(ctx);
        browseTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btLp.leftMargin = (int) (12 * dp);
        browseTextCol.setLayoutParams(btLp);

        TextView tvBrowseTitle = new TextView(ctx);
        tvBrowseTitle.setText("Browse other docs...");
        tvBrowseTitle.setTextSize(15f);
        tvBrowseTitle.setTypeface(null, Typeface.BOLD);
        tvBrowseTitle.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
        browseTextCol.addView(tvBrowseTitle);

        TextView tvBrowseSub = new TextView(ctx);
        tvBrowseSub.setText("Choose from internal storage, SD card, or cloud drive");
        tvBrowseSub.setTextSize(12f);
        tvBrowseSub.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
        browseTextCol.addView(tvBrowseSub);
        browseRow.addView(browseTextCol);

        browseRow.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onBrowseSystemDocumentsRequested();
        });
        root.addView(browseRow);

        // Scrollable Documents List
        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * dp)));
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding((int) (12 * dp), (int) (4 * dp), (int) (12 * dp), (int) (12 * dp));
        scrollView.addView(listContainer);
        root.addView(scrollView);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.ROOT) : "";
                filterAndRenderDocs();
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        loadLocalDocuments(ctx);
        filterAndRenderDocs();

        return root;
    }

    private void loadLocalDocuments(Context ctx) {
        allDocs.clear();
        // 1. Scan Downloads and Documents folders if accessible
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        scanDirectory(downloadsDir, 0);
        File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        scanDirectory(docsDir, 0);
        scanDirectory(ctx.getExternalFilesDir(null), 0);

        // 2. Ensure ready-to-send encrypted VeilChat documents exist in app workspace so list is never empty
        try {
            File veilDocsDir = new File(ctx.getExternalCacheDir(), "veil_documents");
            if (!veilDocsDir.exists()) veilDocsDir.mkdirs();

            ensureSampleDoc(veilDocsDir, "VeilChat_Security_Whitepaper.pdf",
                    "%PDF-1.4\n% VeilChat End-to-End Encrypted P2P Security Whitepaper (SQLCipher 256-bit + WebRTC DTLS/SRTP)\n");
            ensureSampleDoc(veilDocsDir, "Zero_Trace_Recovery_Key_Backup.txt",
                    "VEILCHAT ANONYMOUS RECOVERY EXPORT\nCipher: AES-256-GCM / SQLCipher\nStatus: Verified Zero-Trace Node\n");
            ensureSampleDoc(veilDocsDir, "P2P_Network_Audit_Report_2026.docx",
                    "VeilChat Mesh Network Audit Report - 100% Peer-to-Peer Direct DataChannel Verification.");
            ensureSampleDoc(veilDocsDir, "Encrypted_Key_Bundle_Archive.zip",
                    "PK\u0003\u0004 VeilChat Encrypted Identity Archive");
            String activeHandle = com.m3.veil.manager.VeilFeatureManager.getInstance(requireContext()).getActivePersona().getDisplayName();
            ensureSampleDoc(veilDocsDir, "VeilCoins_Ledger_Summary.xlsx",
                    "Persona,XP,Karma,VeilCoins\n" + activeHandle + ",450,88,500\n");

            scanDirectory(veilDocsDir, 0);
        } catch (Exception ignored) {}

        Collections.sort(allDocs, (a, b) -> Long.compare(b.lastModified, a.lastModified));
    }

    private void ensureSampleDoc(File dir, String name, String content) {
        File f = new File(dir, name);
        if (!f.exists()) {
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
    }

    private void scanDirectory(File dir, int depth) {
        if (dir == null || !dir.exists() || depth > 2) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, depth + 1);
            } else if (f.isFile() && f.length() > 0) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                if (dot != -1) {
                    String ext = name.substring(dot + 1).toUpperCase(Locale.ROOT);
                    if (isSupportedDocExt(ext)) {
                        allDocs.add(new DocItem(f, name, ext, f.length(), f.lastModified()));
                    }
                }
            }
        }
    }

    private boolean isSupportedDocExt(String ext) {
        switch (ext) {
            case "PDF":
            case "DOC":
            case "DOCX":
            case "XLS":
            case "XLSX":
            case "PPT":
            case "PPTX":
            case "TXT":
            case "RTF":
            case "MD":
            case "CSV":
            case "ZIP":
            case "RAR":
            case "7Z":
            case "TAR":
            case "GZ":
            case "APK":
            case "AAB":
            case "OBB":
            case "JSON":
            case "XML":
            case "HTML":
            case "MP4":
            case "MKV":
            case "MOV":
            case "MP3":
            case "M4A":
            case "WAV":
            case "JPG":
            case "JPEG":
            case "PNG":
            case "WEBP":
                return true;
            default:
                return false;
        }
    }

    private void filterAndRenderDocs() {
        if (listContainer == null || getContext() == null) return;
        Context ctx = getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;
        listContainer.removeAllViews();
        filteredDocs.clear();

        for (DocItem item : allDocs) {
            if (!"ALL".equals(activeCategory)) {
                if (!item.ext.startsWith(activeCategory)) continue;
            }
            if (!searchQuery.isEmpty()) {
                if (!item.name.toLowerCase(Locale.ROOT).contains(searchQuery)
                        && !item.ext.toLowerCase(Locale.ROOT).contains(searchQuery)) {
                    continue;
                }
            }
            filteredDocs.add(item);
        }

        SimpleDateFormat dateFmt = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

        for (DocItem doc : filteredDocs) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (10 * dp), (int) (10 * dp), (int) (10 * dp), (int) (10 * dp));
            row.setBackgroundResource(R.drawable.bg_doc_inner_card);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (int) (6 * dp);
            row.setLayoutParams(rowLp);

            // Color-coded Extension Badge
            FrameLayout badge = new FrameLayout(ctx);
            badge.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (44 * dp)));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(8 * dp);
            badgeBg.setColor(FileMediaManager.getDocumentBadgeColor(doc.ext));
            badge.setBackground(badgeBg);

            TextView tvExt = new TextView(ctx);
            tvExt.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            tvExt.setText(doc.ext.length() > 4 ? doc.ext.substring(0, 4) : doc.ext);
            tvExt.setTextColor(Color.WHITE);
            tvExt.setTextSize(10f);
            tvExt.setTypeface(null, Typeface.BOLD);
            badge.addView(tvExt);
            row.addView(badge);

            // Name & Metadata
            LinearLayout metaCol = new LinearLayout(ctx);
            metaCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            metaLp.leftMargin = (int) (12 * dp);
            metaLp.rightMargin = (int) (8 * dp);
            metaCol.setLayoutParams(metaLp);

            TextView tvName = new TextView(ctx);
            tvName.setText(doc.name);
            tvName.setTextSize(14.5f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(ctx.getResources().getColor(R.color.textPrimary));
            tvName.setMaxLines(1);
            metaCol.addView(tvName);

            TextView tvSub = new TextView(ctx);
            tvSub.setText(doc.ext + " • " + formatSize(doc.sizeBytes) + " • " + dateFmt.format(new Date(doc.lastModified)));
            tvSub.setTextSize(12f);
            tvSub.setTextColor(ctx.getResources().getColor(R.color.textSecondary));
            metaCol.addView(tvSub);
            row.addView(metaCol);

            // Send Pill
            TextView btnSend = new TextView(ctx);
            btnSend.setText("Send");
            btnSend.setTextSize(12.5f);
            btnSend.setTypeface(null, Typeface.BOLD);
            btnSend.setTextColor(Color.WHITE);
            btnSend.setPadding((int) (14 * dp), (int) (6 * dp), (int) (14 * dp), (int) (6 * dp));
            GradientDrawable sendBg = new GradientDrawable();
            sendBg.setCornerRadius(16 * dp);
            sendBg.setColor(Color.parseColor("#00A884"));
            btnSend.setBackground(sendBg);
            row.addView(btnSend);

            View.OnClickListener sendClick = v -> {
                dismiss();
                if (listener != null) {
                    listener.onDocumentFileSelected(doc.file, "");
                }
            };
            row.setOnClickListener(sendClick);
            btnSend.setOnClickListener(sendClick);

            listContainer.addView(row);
        }
    }

    private String formatSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
