package com.m3.veil.utils;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4: Live WYSIWYG Markdown TextWatcher for message input EditText.
 *
 * <p>Applies live inline formatting spans while typing without mutating characters
 * or disrupting the user's cursor selection:
 * <ul>
 *     <li>{@code *bold*} or {@code **bold**} &rarr; {@link StyleSpan}({@link Typeface#BOLD})</li>
 *     <li>{@code _italic_} &rarr; {@link StyleSpan}({@link Typeface#ITALIC})</li>
 *     <li>{@code ~strikethrough~} &rarr; {@link StrikethroughSpan}</li>
 *     <li>{@code `monospace`} &rarr; {@link TypefaceSpan}("monospace") + subtle code background</li>
 * </ul>
 * Syntax delimiter characters ({@code *}, {@code _}, {@code ~}, {@code `}) are visually
 * subdued using a low-opacity {@link ForegroundColorSpan} so the user sees clean WYSIWYG
 * formatting in real time while retaining the raw markdown characters for transmission.
 */
public class MarkdownTextWatcher implements TextWatcher {

    // Marker wrapper so we only remove spans created by this watcher (preserving system/IME spans)
    private static final class MarkdownSpanHolder {
        final Object span;

        MarkdownSpanHolder(Object span) {
            this.span = span;
        }
    }

    // Patterns for inline markdown tokens:
    // 1. `code`
    private static final Pattern PATTERN_CODE =
            Pattern.compile("(`)([^`\\n]+)(`)");
    // 2. **bold** or *bold*
    private static final Pattern PATTERN_BOLD =
            Pattern.compile("(\\*\\*|\\*)(?=\\S)([^\\*\\n]*?\\S)(\\1)");
    // 3. _italic_
    private static final Pattern PATTERN_ITALIC =
            Pattern.compile("(_)(?=\\S)([^_\\n]*?\\S)(_)");
    // 4. ~strikethrough~
    private static final Pattern PATTERN_STRIKE =
            Pattern.compile("(~)(?=\\S)([^~\\n]*?\\S)(~)");

    private static final int DELIMITER_COLOR = Color.argb(105, 148, 163, 184);
    private static final int CODE_BG_COLOR = Color.argb(45, 0, 229, 255);
    private static final int CODE_FG_COLOR = Color.rgb(125, 249, 255);

    @Nullable
    private final EditText targetEditText;
    private boolean isFormatting = false;
    private final List<Object> activeSpans = new ArrayList<>();

    public MarkdownTextWatcher() {
        this(null);
    }

    public MarkdownTextWatcher(@Nullable EditText targetEditText) {
        this.targetEditText = targetEditText;
    }

    /**
     * Convenience helper to attach live WYSIWYG markdown styling to an {@link EditText}.
     */
    @NonNull
    public static MarkdownTextWatcher attachTo(@NonNull EditText editText) {
        MarkdownTextWatcher watcher = new MarkdownTextWatcher(editText);
        editText.addTextChangedListener(watcher);
        if (editText.getText() != null && editText.getText().length() > 0) {
            watcher.applyMarkdownSpans(editText.getText());
        }
        return watcher;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // No-op
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // No-op
    }

    @Override
    public void afterTextChanged(Editable editable) {
        if (isFormatting || editable == null) {
            return;
        }
        isFormatting = true;
        try {
            int selStart = targetEditText != null ? targetEditText.getSelectionStart() : -1;
            int selEnd = targetEditText != null ? targetEditText.getSelectionEnd() : -1;

            applyMarkdownSpans(editable);

            if (targetEditText != null && selStart >= 0 && selEnd >= 0) {
                int len = editable.length();
                int safeStart = Math.min(selStart, len);
                int safeEnd = Math.min(selEnd, len);
                if (targetEditText.getSelectionStart() != safeStart
                        || targetEditText.getSelectionEnd() != safeEnd) {
                    targetEditText.setSelection(safeStart, safeEnd);
                }
            }
        } finally {
            isFormatting = false;
        }
    }

    public void applyMarkdownSpans(@NonNull Spannable spannable) {
        // 1. Remove only previously applied MarkdownTextWatcher spans (never touch IME composing spans)
        for (int i = 0, n = activeSpans.size(); i < n; i++) {
            spannable.removeSpan(activeSpans.get(i));
        }
        activeSpans.clear();

        MarkdownSpanHolder[] holders = spannable.getSpans(0, spannable.length(), MarkdownSpanHolder.class);
        if (holders != null) {
            for (MarkdownSpanHolder h : holders) {
                spannable.removeSpan(h.span);
                spannable.removeSpan(h);
            }
        }

        if (spannable.length() < 3) {
            return;
        }

        String text = spannable.toString();

        // Track code ranges so we don't apply bold/italic/strike inside inline `code`
        List<int[]> codeRanges = new ArrayList<>();

        // 1. Inline `monospace` code spans
        Matcher codeMatcher = PATTERN_CODE.matcher(text);
        while (codeMatcher.find()) {
            int fullStart = codeMatcher.start();
            int fullEnd = codeMatcher.end();
            int openEnd = codeMatcher.end(1);
            int closeStart = codeMatcher.start(3);

            codeRanges.add(new int[]{fullStart, fullEnd});

            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), fullStart, openEnd);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), closeStart, fullEnd);
            addTrackedSpan(spannable, new TypefaceSpan("monospace"), openEnd, closeStart);
            addTrackedSpan(spannable, new BackgroundColorSpan(CODE_BG_COLOR), openEnd, closeStart);
            addTrackedSpan(spannable, new ForegroundColorSpan(CODE_FG_COLOR), openEnd, closeStart);
        }

        // 2. *bold* or **bold**
        Matcher boldMatcher = PATTERN_BOLD.matcher(text);
        while (boldMatcher.find()) {
            int fullStart = boldMatcher.start();
            int fullEnd = boldMatcher.end();
            if (overlapsCode(fullStart, fullEnd, codeRanges)) continue;

            int openEnd = boldMatcher.end(1);
            int closeStart = boldMatcher.start(3);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), fullStart, openEnd);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), closeStart, fullEnd);
            addTrackedSpan(spannable, new StyleSpan(Typeface.BOLD), openEnd, closeStart);
        }

        // 3. _italic_
        Matcher italicMatcher = PATTERN_ITALIC.matcher(text);
        while (italicMatcher.find()) {
            int fullStart = italicMatcher.start();
            int fullEnd = italicMatcher.end();
            if (overlapsCode(fullStart, fullEnd, codeRanges)) continue;

            int openEnd = italicMatcher.end(1);
            int closeStart = italicMatcher.start(3);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), fullStart, openEnd);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), closeStart, fullEnd);
            addTrackedSpan(spannable, new StyleSpan(Typeface.ITALIC), openEnd, closeStart);
        }

        // 4. ~strikethrough~
        Matcher strikeMatcher = PATTERN_STRIKE.matcher(text);
        while (strikeMatcher.find()) {
            int fullStart = strikeMatcher.start();
            int fullEnd = strikeMatcher.end();
            if (overlapsCode(fullStart, fullEnd, codeRanges)) continue;

            int openEnd = strikeMatcher.end(1);
            int closeStart = strikeMatcher.start(3);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), fullStart, openEnd);
            addTrackedSpan(spannable, new ForegroundColorSpan(DELIMITER_COLOR), closeStart, fullEnd);
            addTrackedSpan(spannable, new StrikethroughSpan(), openEnd, closeStart);
        }
    }

    private void addTrackedSpan(@NonNull Spannable spannable, @NonNull Object span, int start, int end) {
        if (start < 0 || end <= start || end > spannable.length()) return;
        spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        MarkdownSpanHolder holder = new MarkdownSpanHolder(span);
        spannable.setSpan(holder, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        activeSpans.add(span);
        activeSpans.add(holder);
    }

    private static boolean overlapsCode(int start, int end, List<int[]> codeRanges) {
        for (int i = 0, n = codeRanges.size(); i < n; i++) {
            int[] range = codeRanges.get(i);
            if (start < range[1] && end > range[0]) {
                return true;
            }
        }
        return false;
    }
}
