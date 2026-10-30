package com.m3.veil.ui.views;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.UpdateAppearance;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VeilAdvancedTextView — Next-Gen Telegram/WhatsApp-Parity Chat Text Engine.
 *
 * Capabilities:
 * 1. Telegram/WhatsApp Jumboji sizing:
 *    - 1 emoji-only: 48sp
 *    - 2 emojis-only: 38sp
 *    - 3 emojis-only: 30sp
 *    - Mixed text with emojis: automatically boosts emoji scale by 1.22x so emojis
 *      match text cap-height and never appear shrunken.
 * 2. Multi-Entity Auto-Detection & Interactive Linking:
 *    - Web URLs (http, https, ftp, www, and top TLD domains)
 *    - Usernames / Mentions (@username)
 *    - International & National Phone Numbers (+92..., 03..., (555)...)
 *    - Hashtags (#tag)
 *    - Emails (user@domain.com)
 *    - Cashtags & Crypto Tickers ($BTC, $ETH, $SOL)
 * 3. Non-Conflicting Touch Dispatch Engine (Modeled after Telegram's ChatMessageCell):
 *    - Does NOT use Android's LinkMovementMethod (which breaks swipe-to-reply, double-tap
 *      heart reactions, long-press context menu, and parent RecyclerView touch dispatch).
 *    - Precise character and line hit-testing on ACTION_DOWN / ACTION_MOVE / ACTION_UP.
 *    - Sleek rounded pressed highlight drawn on Canvas behind the tapped entity.
 *    - Haptic feedback on link activation.
 *    - If unhandled or finger moves beyond touch slop, touch events seamlessly bubble
 *      to the parent ChatMessageCell / RecyclerView.
 */
public class VeilAdvancedTextView extends AppCompatTextView {

    public static final int ENTITY_URL = 1;
    public static final int ENTITY_MENTION = 2;
    public static final int ENTITY_PHONE = 3;
    public static final int ENTITY_HASHTAG = 4;
    public static final int ENTITY_EMAIL = 5;
    public static final int ENTITY_CASHTAG = 6;

    public interface OnEntityClickListener {
        void onUrlClick(String url);
        void onMentionClick(String username);
        void onPhoneClick(String phoneNumber);
        void onHashtagClick(String hashtag);
        void onEmailClick(String email);
        void onCashtagClick(String cashtag);
    }

    public static class SimpleEntityClickListener implements OnEntityClickListener {
        @Override public void onUrlClick(String url) {}
        @Override public void onMentionClick(String username) {}
        @Override public void onPhoneClick(String phoneNumber) {}
        @Override public void onHashtagClick(String hashtag) {}
        @Override public void onEmailClick(String email) {}
        @Override public void onCashtagClick(String cashtag) {}
    }

    // --- Entity Detection Patterns ---

    public static final Pattern URL_PATTERN = Pattern.compile(
            "\\b((?:https?|ftp)://[^\\s/$.?#].[^\\s]*|www\\.[^\\s/$.?#].[^\\s]*|[a-zA-Z0-9][-a-zA-Z0-9]*\\.(?:com|org|net|edu|gov|io|app|co|me|ai|dev|so|info|xyz|cc|tv|pk|uk|in|de|fr|ca|au|tech|online|store|site)(?:/[^\\s]*)?)",
            Pattern.CASE_INSENSITIVE
    );

    public static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );

    public static final Pattern MENTION_PATTERN = Pattern.compile(
            "(?<=^|[\\s(\\[{<])@([a-zA-Z0-9_]{3,32})\\b"
    );

    public static final Pattern HASHTAG_PATTERN = Pattern.compile(
            "(?<=^|[\\s(\\[{<])#([a-zA-Z0-9_\\u0080-\\uffff]{1,50})\\b"
    );

    public static final Pattern CASHTAG_PATTERN = Pattern.compile(
            "(?<=^|[\\s(\\[{<])\\$([A-Z]{2,6})\\b"
    );

    public static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?\\d{1,4}[-.\\s]?)?\\(?\\d{2,4}\\)?[-.\\s]?\\d{3,4}[-.\\s]?\\d{3,9}"
    );

    public static final Pattern EMOJI_REGEX = Pattern.compile(
            "(?:" +
                "\\uD83C[\\uDDE6-\\uDDFF]{2}" + // Flags (pair of regional indicators)
                "|[0-9#*]\\uFE0F?\\u20E3" + // Keycap sequence
                "|(?:[\\u203C\\u2049\\u2122\\u2139\\u2194-\\u2199\\u21A9-\\u21AA\\u231A-\\u231B\\u2328\\u23CF\\u23E9-\\u23F3\\u23F8-\\u23FA\\u24C2\\u25AA-\\u25AB\\u25B6\\u25C0\\u25FB-\\u25FE\\u2600-\\u27BF\\u2934-\\u2935\\u2B05-\\u2B07\\u2B1B-\\u2B1C\\u2B50\\u2B55\\u3030\\u303D\\u3297\\u3299]|\\uD83C[\\uDC00-\\uDFFF]|\\uD83D[\\uDC00-\\uDFFF]|\\uD83E[\\uDD00-\\uDFFF])" +
                "(?:\\uFE0F)?" +
                "(?:\\uD83C[\\uDFFB-\\uDFFF])?" +
                "(?:\\u200D(?:[\\u203C\\u2049\\u2122\\u2139\\u2194-\\u2199\\u21A9-\\u21AA\\u231A-\\u231B\\u2328\\u23CF\\u23E9-\\u23F3\\u23F8-\\u23FA\\u24C2\\u25AA-\\u25AB\\u25B6\\u25C0\\u25FB-\\u25FE\\u2600-\\u27BF\\u2934-\\u2935\\u2B05-\\u2B07\\u2B1B-\\u2B1C\\u2B50\\u2B55\\u3030\\u303D\\u3297\\u3299]|\\uD83C[\\uDC00-\\uDFFF]|\\uD83D[\\uDC00-\\uDFFF]|\\uD83E[\\uDD00-\\uDFFF])(?:\\uFE0F)?(?:\\uD83C[\\uDFFB-\\uDFFF])?)*" +
            ")"
    );

    // --- Span representation ---

    public static class EntitySpan extends CharacterStyle implements UpdateAppearance {
        public final int entityType;
        public final String value;
        public final int color;
        public final boolean isBold;

        public EntitySpan(int entityType, String value, int color, boolean isBold) {
            this.entityType = entityType;
            this.value = value;
            this.color = color;
            this.isBold = isBold;
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(false);
            if (isBold) {
                ds.setFakeBoldText(true);
            }
        }
    }

    public static class EmojiAnalysisResult {
        public final boolean isEmojiOnly;
        public final int emojiCount;
        public final List<int[]> emojiRanges;

        public EmojiAnalysisResult(boolean isEmojiOnly, int emojiCount, List<int[]> emojiRanges) {
            this.isEmojiOnly = isEmojiOnly;
            this.emojiCount = emojiCount;
            this.emojiRanges = emojiRanges;
        }
    }

    // --- State variables ---

    private int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private boolean mTouchMoved;
    private EntitySpan mPressedSpan;

    private Paint mPressedHighlightPaint;
    private RectF mHighlightRect;

    private boolean mIsOutgoing = false;
    private boolean mIsDarkTheme = false;
    private OnEntityClickListener mEntityClickListener;

    public VeilAdvancedTextView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public VeilAdvancedTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VeilAdvancedTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mPressedHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mHighlightRect = new RectF();
        setFocusable(false);
    }

    public void setOnEntityClickListener(@Nullable OnEntityClickListener listener) {
        this.mEntityClickListener = listener;
    }

    /**
     * Primary rendering method with Jumboji detection, mixed-emoji enhancement, and entity recognition.
     */
    public void renderAdvancedText(
            @Nullable CharSequence rawOrFormattedText,
            float baseTextSizeSp,
            boolean isOutgoing,
            boolean isDarkBubble,
            @Nullable OnEntityClickListener listener) {

        this.mIsOutgoing = isOutgoing;
        this.mIsDarkTheme = isDarkBubble;
        this.mEntityClickListener = listener;

        if (rawOrFormattedText == null || rawOrFormattedText.length() == 0) {
            setText("");
            setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp > 0 ? baseTextSizeSp : 15.5f);
            return;
        }

        EmojiAnalysisResult emojiInfo = analyzeEmojis(rawOrFormattedText);

        // Telegram / WhatsApp Jumboji sizing (For 2+ emojis inside Text Bubbles)
        if (emojiInfo.isEmojiOnly) {
            if (emojiInfo.emojiCount == 1) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 56.0f);
                setLineSpacing(0f, 1.0f);
            } else if (emojiInfo.emojiCount == 2) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 42.0f);
                setLineSpacing(0f, 1.0f);
            } else if (emojiInfo.emojiCount == 3) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 34.0f);
                setLineSpacing(0f, 1.0f);
            } else if (emojiInfo.emojiCount <= 6) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26.0f);
                setLineSpacing(dpToPx(1.5f), 1.0f);
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp > 0 ? baseTextSizeSp : 15.5f);
                setLineSpacing(dpToPx(1.5f), 1.0f);
            }
        } else {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp > 0 ? baseTextSizeSp : 15.5f);
            setLineSpacing(dpToPx(1.5f), 1.0f);
        }

        SpannableStringBuilder builder = new SpannableStringBuilder(rawOrFormattedText);

        // If mixed text contains emojis, boost their inline size by 1.40x so all inline emojis scale up prominently
        if (!emojiInfo.isEmojiOnly && !emojiInfo.emojiRanges.isEmpty()) {
            for (int[] range : emojiInfo.emojiRanges) {
                builder.setSpan(new RelativeSizeSpan(1.40f), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            setLineSpacing(dpToPx(2.5f), 1.0f);
        }

        // Apply Entity Spans (URLs, Mentions, Phones, Hashtags, Emails, Cashtags)
        int linkColor = getLinkColor(isOutgoing, isDarkBubble);
        applyEntitySpans(builder, linkColor);

        setText(builder, BufferType.SPANNABLE);
    }

    public static int getLinkColor(boolean isOutgoing, boolean isDarkBubble) {
        if (isOutgoing) {
            // Sent bubble: high-contrast celestial blue / cyan
            return isDarkBubble ? 0xFF93C5FD : 0xFF0284C7;
        } else {
            // Received bubble: Telegram signature electric sapphire / sky blue
            return isDarkBubble ? 0xFF38BDF8 : 0xFF0284C7;
        }
    }

    public static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F600 && cp <= 0x1F64F) // Emoticons
                || (cp >= 0x1F300 && cp <= 0x1F5FF) // Misc Symbols and Pictographs
                || (cp >= 0x1F680 && cp <= 0x1F6FF) // Transport and Map
                || (cp >= 0x1F700 && cp <= 0x1F77F) // Alchemical
                || (cp >= 0x1F780 && cp <= 0x1F7FF) // Geometric Extended
                || (cp >= 0x1F800 && cp <= 0x1F8FF) // Supplemental Arrows
                || (cp >= 0x1F900 && cp <= 0x1F9FF) // Supplemental Symbols & Pictographs
                || (cp >= 0x1FA00 && cp <= 0x1FA6F) // Chess Symbols
                || (cp >= 0x1FA70 && cp <= 0x1FAFF) // Symbols and Pictographs Extended-A
                || (cp >= 0x2600 && cp <= 0x26FF)   // Misc Symbols (❤️, ⚡, ⚽)
                || (cp >= 0x2700 && cp <= 0x27BF)   // Dingbats (✅, ✨, ❌)
                || (cp >= 0x2B00 && cp <= 0x2BFF)   // Various Symbols and Arrows (⭐)
                || (cp >= 0x2300 && cp <= 0x23FF)   // Misc Technical (⌚, ⏰)
                || (cp >= 0x2190 && cp <= 0x21FF)   // Arrows
                || (cp >= 0x2900 && cp <= 0x297F)   // Supplemental Arrows-B
                || (cp >= 0x3200 && cp <= 0x32FF)   // Enclosed CJK Letters
                || (cp >= 0x3000 && cp <= 0x303F)   // CJK Symbols
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // Regional Indicators (Flags)
                || (cp >= 0x1F004 && cp <= 0x1F0CF); // Mahjong, Cards
    }

    /**
     * Accurately analyzes text for emoji-only message status and individual emoji ranges
     * using code point scanning with surrogate, variation selector, and ZWJ support.
     */
    public static EmojiAnalysisResult analyzeEmojis(@NonNull CharSequence text) {
        if (text == null || text.length() == 0) {
            return new EmojiAnalysisResult(false, 0, new ArrayList<>());
        }

        List<int[]> emojiRanges = new ArrayList<>();
        int len = text.length();
        int emojiCharsCount = 0;
        int nonWhitespaceCount = 0;
        int i = 0;

        while (i < len) {
            int cp = Character.codePointAt(text, i);
            int charCount = Character.charCount(cp);

            if (isEmojiCodePoint(cp)) {
                int start = i;
                int end = i + charCount;

                // Slurp subsequent combining code points (Variation selectors, skin tones, ZWJ sequences)
                while (end < len) {
                    int nextCp = Character.codePointAt(text, end);
                    int nextLen = Character.charCount(nextCp);

                    if (nextCp == 0x200D) { // ZWJ (Zero-Width Joiner)
                        end += nextLen;
                        if (end < len) {
                            int joinedCp = Character.codePointAt(text, end);
                            end += Character.charCount(joinedCp);
                        }
                    } else if (nextCp == 0xFE0E || nextCp == 0xFE0F // Variation selectors
                            || (nextCp >= 0x1F3FB && nextCp <= 0x1F3FF) // Skin tone modifiers
                            || nextCp == 0x20E3) { // Keycap enclosing
                        end += nextLen;
                    } else {
                        break;
                    }
                }

                emojiRanges.add(new int[]{start, end});
                int clusterLen = end - start;
                emojiCharsCount += clusterLen;
                nonWhitespaceCount += clusterLen;
                i = end;
            } else {
                if (!Character.isWhitespace(cp)) {
                    nonWhitespaceCount += charCount;
                }
                i += charCount;
            }
        }

        boolean isEmojiOnly = nonWhitespaceCount > 0 && nonWhitespaceCount == emojiCharsCount;
        int emojiCount = emojiRanges.size();

        return new EmojiAnalysisResult(isEmojiOnly, emojiCount, emojiRanges);
    }

    /**
     * Auto-detects and attaches EntitySpan objects without collisions.
     */
    private static void applyEntitySpans(SpannableStringBuilder builder, int linkColor) {
        String str = builder.toString();
        List<int[]> occupiedRanges = new ArrayList<>();

        // 1. URLs (highest precedence)
        Matcher urlMatcher = URL_PATTERN.matcher(str);
        while (urlMatcher.find()) {
            int start = urlMatcher.start(1);
            int end = urlMatcher.end(1);
            if (!isRangeOccupied(occupiedRanges, start, end)) {
                String url = urlMatcher.group(1);
                builder.setSpan(new EntitySpan(ENTITY_URL, url, linkColor, false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                occupiedRanges.add(new int[]{start, end});
            }
        }

        // 2. Emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(str);
        while (emailMatcher.find()) {
            int start = emailMatcher.start();
            int end = emailMatcher.end();
            if (!isRangeOccupied(occupiedRanges, start, end)) {
                String email = emailMatcher.group();
                builder.setSpan(new EntitySpan(ENTITY_EMAIL, email, linkColor, false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                occupiedRanges.add(new int[]{start, end});
            }
        }

        // 3. Mentions (@username)
        Matcher mentionMatcher = MENTION_PATTERN.matcher(str);
        while (mentionMatcher.find()) {
            int start = mentionMatcher.start();
            int end = mentionMatcher.end();
            String username = mentionMatcher.group(1);
            if (username != null && !isRangeOccupied(occupiedRanges, start, end)) {
                builder.setSpan(new EntitySpan(ENTITY_MENTION, username, linkColor, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                occupiedRanges.add(new int[]{start, end});
            }
        }

        // 4. Cashtags ($BTC)
        Matcher cashMatcher = CASHTAG_PATTERN.matcher(str);
        while (cashMatcher.find()) {
            int start = cashMatcher.start();
            int end = cashMatcher.end();
            String cashtag = cashMatcher.group(1);
            if (cashtag != null && !isRangeOccupied(occupiedRanges, start, end)) {
                builder.setSpan(new EntitySpan(ENTITY_CASHTAG, cashtag, linkColor, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                occupiedRanges.add(new int[]{start, end});
            }
        }

        // 5. Hashtags (#tag)
        Matcher hashMatcher = HASHTAG_PATTERN.matcher(str);
        while (hashMatcher.find()) {
            int start = hashMatcher.start();
            int end = hashMatcher.end();
            String tag = hashMatcher.group(1);
            if (tag != null && !isRangeOccupied(occupiedRanges, start, end)) {
                builder.setSpan(new EntitySpan(ENTITY_HASHTAG, tag, linkColor, false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                occupiedRanges.add(new int[]{start, end});
            }
        }

        // 6. Phone Numbers (7-15 digits)
        Matcher phoneMatcher = PHONE_PATTERN.matcher(str);
        while (phoneMatcher.find()) {
            int start = phoneMatcher.start();
            int end = phoneMatcher.end();
            String rawPhone = phoneMatcher.group();
            if (isValidPhoneNumber(rawPhone) && !isRangeOccupied(occupiedRanges, start, end)) {
                builder.setSpan(new EntitySpan(ENTITY_PHONE, rawPhone, linkColor, false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                occupiedRanges.add(new int[]{start, end});
            }
        }
    }

    private static boolean isRangeOccupied(List<int[]> occupiedRanges, int start, int end) {
        for (int[] r : occupiedRanges) {
            if (Math.max(start, r[0]) < Math.min(end, r[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidPhoneNumber(String text) {
        if (text == null) return false;
        int digitCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                digitCount++;
            }
        }
        return digitCount >= 7 && digitCount <= 15;
    }

    // --- Touch Dispatch Engine (Telegram ChatMessageCell Parity) ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        CharSequence text = getText();
        if (!(text instanceof Spanned)) {
            return super.onTouchEvent(event);
        }
        Spanned spanned = (Spanned) text;
        Layout layout = getLayout();
        if (layout == null) {
            return super.onTouchEvent(event);
        }

        int action = event.getActionMasked();
        float x = event.getX() - getPaddingLeft();
        float y = event.getY() - getPaddingTop();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = event.getX();
                mDownY = event.getY();
                EntitySpan span = findEntitySpanAt(x, y, layout, spanned);
                if (span != null) {
                    mPressedSpan = span;
                    mTouchMoved = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    invalidate();
                    return true;
                }
                mPressedSpan = null;
                // If not touching an entity, let super handle it.
                // If no OnClickListener is set, super returns false and touches bubble to parent bubble/cell.
                return super.onTouchEvent(event);
            }

            case MotionEvent.ACTION_MOVE: {
                if (mPressedSpan != null) {
                    float dist = (float) Math.hypot(event.getX() - mDownX, event.getY() - mDownY);
                    if (dist > mTouchSlop) {
                        mTouchMoved = true;
                        mPressedSpan = null;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        invalidate();
                    }
                    return true;
                }
                return super.onTouchEvent(event);
            }

            case MotionEvent.ACTION_UP: {
                if (mPressedSpan != null && !mTouchMoved) {
                    EntitySpan spanToClick = mPressedSpan;
                    mPressedSpan = null;
                    invalidate();
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    dispatchEntityClick(spanToClick);
                    return true;
                }
                mPressedSpan = null;
                invalidate();
                return super.onTouchEvent(event);
            }

            case MotionEvent.ACTION_CANCEL: {
                if (mPressedSpan != null) {
                    mPressedSpan = null;
                    invalidate();
                }
                return super.onTouchEvent(event);
            }
        }

        return super.onTouchEvent(event);
    }

    private EntitySpan findEntitySpanAt(float x, float y, Layout layout, Spanned spanned) {
        if (y < 0 || y > layout.getHeight()) {
            return null;
        }

        int line = layout.getLineForVertical((int) y);
        if (line < 0 || line >= layout.getLineCount()) {
            return null;
        }

        float lineLeft = layout.getLineLeft(line);
        float lineRight = layout.getLineRight(line);
        if (x < lineLeft || x > lineRight) {
            return null;
        }

        int offset = layout.getOffsetForHorizontal(line, x);
        EntitySpan[] spans = spanned.getSpans(offset, offset, EntitySpan.class);
        if (spans != null && spans.length > 0) {
            for (EntitySpan s : spans) {
                int sStart = spanned.getSpanStart(s);
                int sEnd = spanned.getSpanEnd(s);
                if (offset >= sStart && offset <= sEnd) {
                    return s;
                }
            }
        }

        if (offset > 0) {
            spans = spanned.getSpans(offset - 1, offset - 1, EntitySpan.class);
            if (spans != null && spans.length > 0) {
                for (EntitySpan s : spans) {
                    int sStart = spanned.getSpanStart(s);
                    int sEnd = spanned.getSpanEnd(s);
                    if (offset - 1 >= sStart && offset - 1 <= sEnd) {
                        return s;
                    }
                }
            }
        }

        return null;
    }

    // --- Canvas Drawing (Telegram Pressed Background Highlight) ---

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPressedSpan != null) {
            drawPressedHighlight(canvas);
        }
        super.onDraw(canvas);
    }

    private void drawPressedHighlight(Canvas canvas) {
        CharSequence text = getText();
        if (!(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        Layout layout = getLayout();
        if (layout == null) return;

        int start = spanned.getSpanStart(mPressedSpan);
        int end = spanned.getSpanEnd(mPressedSpan);
        if (start < 0 || end < 0 || start >= end) return;

        int startLine = layout.getLineForOffset(start);
        int endLine = layout.getLineForOffset(end);

        int padL = getPaddingLeft();
        int padT = getPaddingTop();

        int highlightColor = mIsOutgoing ? 0x33FFFFFF : (mIsDarkTheme ? 0x3338BDF8 : 0x250284C7);
        mPressedHighlightPaint.setColor(highlightColor);

        for (int line = startLine; line <= endLine; line++) {
            float left = (line == startLine) ? layout.getPrimaryHorizontal(start) : layout.getLineLeft(line);
            float right = (line == endLine) ? layout.getPrimaryHorizontal(end) : layout.getLineRight(line);

            if (left > right) {
                float tmp = left;
                left = right;
                right = tmp;
            }

            float top = layout.getLineTop(line) + padT;
            float bottom = layout.getLineBottom(line) + padT;
            left += padL - 4f;
            right += padL + 4f;

            mHighlightRect.set(left, top, right, bottom);
            canvas.drawRoundRect(mHighlightRect, dpToPx(4f), dpToPx(4f), mPressedHighlightPaint);
        }
    }

    // --- Entity Action Dispatcher ---

    private void dispatchEntityClick(EntitySpan span) {
        if (mEntityClickListener != null) {
            switch (span.entityType) {
                case ENTITY_URL:
                    mEntityClickListener.onUrlClick(span.value);
                    return;
                case ENTITY_MENTION:
                    mEntityClickListener.onMentionClick(span.value);
                    return;
                case ENTITY_PHONE:
                    mEntityClickListener.onPhoneClick(span.value);
                    return;
                case ENTITY_HASHTAG:
                    mEntityClickListener.onHashtagClick(span.value);
                    return;
                case ENTITY_EMAIL:
                    mEntityClickListener.onEmailClick(span.value);
                    return;
                case ENTITY_CASHTAG:
                    mEntityClickListener.onCashtagClick(span.value);
                    return;
            }
        }
        executeDefaultAction(span);
    }

    public void executeDefaultAction(EntitySpan span) {
        Context ctx = getContext();
        if (ctx == null) return;

        try {
            switch (span.entityType) {
                case ENTITY_URL: {
                    String url = span.value;
                    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ftp://")) {
                        url = "https://" + url;
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    break;
                }
                case ENTITY_PHONE: {
                    String sanitized = span.value.replaceAll("[^0-9+]", "");
                    Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + sanitized));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    break;
                }
                case ENTITY_EMAIL: {
                    Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + span.value));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    break;
                }
                case ENTITY_MENTION: {
                    handleMentionClick(ctx, span.value);
                    break;
                }
                case ENTITY_HASHTAG: {
                    Toast.makeText(ctx, "Hashtag: #" + span.value, Toast.LENGTH_SHORT).show();
                    break;
                }
                case ENTITY_CASHTAG: {
                    String quoteUrl = "https://www.google.com/finance/quote/" + span.value;
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(quoteUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    break;
                }
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Could not open: " + span.value, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleMentionClick(Context ctx, String rawUsername) {
        if (ctx == null || rawUsername == null) return;
        final String clean = rawUsername.startsWith("@") ? rawUsername.substring(1).trim() : rawUsername.trim();
        if (clean.length() < 3 || !clean.matches("[a-zA-Z0-9_]{3,32}")) {
            Toast.makeText(ctx, "Invalid username: @" + clean, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if mention is current user themselves
        String myUname = ctx.getSharedPreferences("VeilPrefs", Context.MODE_PRIVATE).getString("username", null);
        if (myUname == null) {
            myUname = ctx.getSharedPreferences("veil_sovereign_identity_prefs", Context.MODE_PRIVATE).getString("sovereign_username", null);
        }
        if (myUname != null && clean.equalsIgnoreCase(myUname.replace("@", ""))) {
            Toast.makeText(ctx, "This is your username (@" + clean + ")", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(ctx, "Checking @" + clean + "...", Toast.LENGTH_SHORT).show();

        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx)
                .searchUsersByUsername(clean, users -> {
                    com.m3.veil.model.User matchedUser = null;
                    if (users != null) {
                        for (com.m3.veil.model.User u : users) {
                            if (u != null && u.getUsername() != null && clean.equalsIgnoreCase(u.getUsername().replace("@", ""))) {
                                matchedUser = u;
                                break;
                            }
                        }
                    }

                    final com.m3.veil.model.User target = matchedUser;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (target != null && target.getUserId() != null && !target.getUserId().isEmpty()) {
                            Intent intent = new Intent(ctx, com.m3.veil.ui.chat.ChatActivity.class);
                            intent.putExtra("chatId", target.getUserId());
                            intent.putExtra("recipientId", target.getUserId());
                            intent.putExtra("recipientName", target.getDisplayName() != null && !target.getDisplayName().isEmpty() ? target.getDisplayName() : "@" + target.getUsername());
                            if (target.getAvatar() != null && !target.getAvatar().isEmpty()) {
                                intent.putExtra("recipientAvatar", target.getAvatar());
                            } else if (target.getPhotoUrl() != null && !target.getPhotoUrl().isEmpty()) {
                                intent.putExtra("recipientAvatar", target.getPhotoUrl());
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(intent);
                        } else {
                            Toast.makeText(ctx, "User @" + clean + " not found", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
    }

    private float dpToPx(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
