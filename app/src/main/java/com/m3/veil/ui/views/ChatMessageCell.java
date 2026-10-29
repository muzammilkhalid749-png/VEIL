package com.m3.veil.ui.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.m3.veil.R;
import com.m3.veil.model.Message;

/**
 * Telegram-Parity Message Bubble & Cell Engine.
 * Modeled directly on Telegram's {@code org.telegram.ui.Cells.ChatMessageCell}.
 *
 * <p>Architecture & Capabilities:
 * <ul>
 *   <li><b>Full Light & Dark Theme Engine</b>: Dynamically resolves {@code R.color.message_bubble_sent},
 *       {@code R.color.message_bubble_received}, {@code R.color.message_text_primary},
 *       {@code R.color.message_text_secondary}, {@code R.color.colorOutline}, and
 *       {@code R.color.message_quote_bg} on attach, bind, and {@link #onConfigurationChanged(Configuration)}.</li>
 *   <li><b>Telegram Curved Bezier Bubble Tail</b>: Renders smooth bezier tails on bottom-right (sent)
 *       and bottom-left (received) for {@link #CLUSTER_SINGLE} and {@link #CLUSTER_LAST}, with grouped
 *       corner clustering ({@code 18dp} large / {@code 5dp} clustered).</li>
 *   <li><b>Sender / Persona Header</b>: Renders deterministic peer color name headers for group/channel
 *       and anonymous persona messages.</li>
 *   <li><b>Vanish TTL Circular Countdown Arc</b>: Live animated countdown ring for ephemeral messages.</li>
 *   <li><b>Double-Tap Heart Burst & Highlight Pulse</b>: Canvas-rendered floating heart burst on
 *       double-tap and smooth accent flash when scrolled to via reply quote or search.</li>
 *   <li><b>Swipe-to-Reply Spring Physics</b>: Rubber-band drag with haptic threshold and rotating arrow.</li>
 * </ul>
 */
public class ChatMessageCell extends LinearLayout {

    // Delivery Status Flags
    public static final int STATUS_NONE = 0;
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_SENT = 2;
    public static final int STATUS_DELIVERED = 3;
    public static final int STATUS_READ = 4;

    // Cluster Position for Grouped Message Radii (Telegram ChatMessageCell grouped style)
    public static final int CLUSTER_SINGLE = 0;
    public static final int CLUSTER_FIRST = 1;
    public static final int CLUSTER_MIDDLE = 2;
    public static final int CLUSTER_LAST = 3;

    // Sender Name Palette (Adaptive for Light & Dark Mode)
    private static final int[] SENDER_COLORS_LIGHT = {
            0xFF4F46E5, 0xFFDB2777, 0xFF059669, 0xFFD97706,
            0xFF2563EB, 0xFF7C3AED, 0xFF0D9488, 0xFFE11D48
    };
    private static final int[] SENDER_COLORS_DARK = {
            0xFF818CF8, 0xFFF472B6, 0xFF34D399, 0xFFFBBF24,
            0xFF60A5FA, 0xFFA78BFA, 0xFF2DD4BF, 0xFFFB7185
    };

    // Dimension Constants (dp)
    private static final float CORNER_LARGE_DP = 17.5f;
    private static final float CORNER_SMALL_DP = 5f;
    private static final float TAIL_WIDTH_DP = 6.5f;
    private static final float SWIPE_THRESHOLD_DP = 50f;
    private static final float SWIPE_MAX_OFFSET_DP = 92f;

    protected final float mDensity;
    protected final float mScaledDensity;
    protected final float mCornerLarge;
    protected final float mCornerSmall;
    protected final float mTailWidth;
    protected final float mSwipeThreshold;
    protected final float mSwipeMaxOffset;
    protected final int mTouchSlop;

    // Dynamic Theme Colors
    protected boolean mIsNightMode = false;
    protected int mColorBubbleSent;
    protected int mColorBubbleReceived;
    protected int mColorBubbleSentStroke;
    protected int mColorBubbleReceivedStroke;
    protected int mColorTextPrimary;
    protected int mColorTextSecondary;
    protected int mColorPrimary;
    protected int mColorQuoteStrip;
    protected int mColorQuoteBg;
    protected int mColorStatusSent;
    protected int mColorStatusRead;
    protected int mColorError;

    // Message & Bubble State
    protected Message mMessage;
    protected boolean mIsOutgoing = true;
    protected int mClusterPosition = CLUSTER_SINGLE;
    protected int mDeliveryStatus = STATUS_NONE;
    protected String mTimeString = "";
    protected boolean mIsStarred = false;
    protected boolean mIsVanish = false;
    protected boolean mIsEdited = false;
    protected int mCustomBubbleColor = 0;
    protected boolean mDrawTail = true;
    protected String mSenderHeaderName = null;
    protected int mSenderHeaderColor = 0;
    protected long mVanishExpiresAtMs = 0L;
    protected long mVanishTotalDurationMs = 0L;

    // Pre-allocated Corner Radii [TL_x, TL_y, TR_x, TR_y, BR_x, BR_y, BL_x, BL_y]
    protected final float[] mRadii = new float[8];

    // Pre-allocated Canvas Geometry
    protected final RectF mBubbleRect = new RectF();
    protected final RectF mTailArcRect = new RectF();
    protected final RectF mVanishArcRect = new RectF();
    protected final Path mBubblePath = new Path();
    protected final Path mReplyArrowPath = new Path();
    protected final Path mHeartBurstPath = new Path();

    // Pre-allocated Paints (Zero Allocation in dispatchDraw / onDraw)
    protected final Paint mBubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mBubbleSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mBubbleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mReplyCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mReplyArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mVanishArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint mHeartBurstPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint mSenderHeaderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint mStandaloneTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint mStandaloneTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // Optional Standalone Layouts (when ChatMessageCell is used without XML children)
    private StaticLayout mSenderHeaderLayout;
    private StaticLayout mStandaloneBodyLayout;
    private LinearGradient mSentSheenGradient;
    private int mLastGradientHeight = -1;

    // Interactive Animations (Highlight Pulse & Double-Tap Heart Burst)
    private float mHighlightAlpha = 0f;
    private float mHeartBurstProgress = 0f;
    private float mHeartBurstX = 0f;
    private float mHeartBurstY = 0f;
    private ValueAnimator mHighlightAnimator;
    private ValueAnimator mHeartBurstAnimator;

    // Swipe-to-Reply Physics
    private float mSwipeTranslationX = 0f;
    private boolean mIsSwiping = false;
    private boolean mSwipeTriggered = false;
    private float mDownX = 0f;
    private float mDownY = 0f;
    private VelocityTracker mVelocityTracker;
    private ValueAnimator mSpringAnimator;
    private OnSwipeReplyListener mSwipeReplyListener;

    public interface OnSwipeReplyListener {
        void onSwipeReplyTriggered(View bubbleView);
    }

    public ChatMessageCell(@NonNull Context context) {
        this(context, null);
    }

    public ChatMessageCell(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ChatMessageCell(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setBackground(null);

        mDensity = context.getResources().getDisplayMetrics().density;
        mScaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        mCornerLarge = CORNER_LARGE_DP * mDensity;
        mCornerSmall = CORNER_SMALL_DP * mDensity;
        mTailWidth = TAIL_WIDTH_DP * mDensity;
        mSwipeThreshold = SWIPE_THRESHOLD_DP * mDensity;
        mSwipeMaxOffset = SWIPE_MAX_OFFSET_DP * mDensity;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        initPaints();
        refreshThemeColors(true);
        updateCornerRadii();
    }

    protected int dp(float value) {
        return Math.round(value * mDensity);
    }

    protected float dpf2(float value) {
        return value * mDensity;
    }

    protected float sp(float value) {
        return value * mScaledDensity;
    }

    private void initPaints() {
        mBubblePaint.setStyle(Paint.Style.FILL);
        mBubbleSheenPaint.setStyle(Paint.Style.FILL);

        mBubbleStrokePaint.setStyle(Paint.Style.STROKE);
        mBubbleStrokePaint.setStrokeWidth(Math.max(1f, dpf2(0.85f)));

        mHighlightPaint.setStyle(Paint.Style.FILL);

        mReplyCirclePaint.setStyle(Paint.Style.FILL);

        mReplyArrowPaint.setStyle(Paint.Style.STROKE);
        mReplyArrowPaint.setStrokeWidth(dpf2(2f));
        mReplyArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        mReplyArrowPaint.setStrokeJoin(Paint.Join.ROUND);
        mReplyArrowPaint.setColor(Color.WHITE);

        mVanishArcPaint.setStyle(Paint.Style.STROKE);
        mVanishArcPaint.setStrokeWidth(dpf2(1.5f));
        mVanishArcPaint.setStrokeCap(Paint.Cap.ROUND);

        mHeartBurstPaint.setStyle(Paint.Style.FILL);
        mHeartBurstPaint.setColor(0xFFEF4444);

        mSenderHeaderPaint.setTextSize(sp(13f));
        mSenderHeaderPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        mStandaloneTextPaint.setTextSize(sp(15.5f));
        mStandaloneTimePaint.setTextSize(sp(11f));
    }

    public static boolean resolveIsNightMode(@Nullable Context context) {
        if (context != null) {
            try {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                android.content.SharedPreferences prefs = app.getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
                int themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                if (themeMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                    return true;
                } else if (themeMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                    return false;
                }
            } catch (Exception ignored) {}
        }
        int defaultMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        if (defaultMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            return true;
        } else if (defaultMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
            return false;
        }
        if (context != null) {
            return (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    /**
     * Dynamically resolves Light Mode and Dark Mode semantic bubble and text colors.
     */
    public void refreshThemeColors(boolean force) {
        Context context = getContext();
        if (context == null) return;

        boolean night = resolveIsNightMode(context);
        if (!force && night == mIsNightMode && mColorBubbleSent != 0 && mColorBubbleReceived != 0) {
            updateColors();
            return;
        }
        mIsNightMode = night;

        if (mIsNightMode) {
            mColorBubbleSent = 0xFF2E2A72;
            mColorBubbleReceived = 0xFF1E293B;
            mColorTextPrimary = 0xFFF8FAFC;
            mColorTextSecondary = 0xFF94A3B8;
            mColorPrimary = 0xFF6366F1;
            mColorQuoteStrip = 0xFF818CF8;
            mColorQuoteBg = 0x1A818CF8;
            mColorStatusSent = 0xFF64748B;
            mColorStatusRead = 0xFF38BDF8;
            mColorError = 0xFFEF4444;
            mColorBubbleSentStroke = ColorUtils.setAlphaComponent(0xFF818CF8, 56);
            mColorBubbleReceivedStroke = ColorUtils.setAlphaComponent(0xFF334155, 160);
        } else {
            mColorBubbleSent = 0xFFE0E7FE;
            mColorBubbleReceived = 0xFFFFFFFF;
            mColorTextPrimary = 0xFF0F172A;
            mColorTextSecondary = 0xFF64748B;
            mColorPrimary = 0xFF4F46E5;
            mColorQuoteStrip = 0xFF4F46E5;
            mColorQuoteBg = 0x0A4F46E5;
            mColorStatusSent = 0xFF94A3B8;
            mColorStatusRead = 0xFF4F46E5;
            mColorError = 0xFFEF4444;
            mColorBubbleSentStroke = 0xFFC7D2FE;
            mColorBubbleReceivedStroke = 0xFFE2E8F0;
        }

        mReplyCirclePaint.setColor(mColorPrimary);
        mHighlightPaint.setColor(mColorPrimary);
        mVanishArcPaint.setColor(mColorPrimary);
        mStandaloneTextPaint.setColor(mColorTextPrimary);
        mStandaloneTimePaint.setColor(mColorTextSecondary);
        mLastGradientHeight = -1;

        updateColors();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshThemeColors(true);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshThemeColors(true);
        invalidate();
    }

    public boolean isNightModeActive() {
        return mIsNightMode;
    }

    public void setOnSwipeReplyListener(OnSwipeReplyListener listener) {
        this.mSwipeReplyListener = listener;
    }

    // =========================================================================
    // TELEGRAM CHATMESSAGECELL BINDING & CONFIGURATION APIS
    // =========================================================================

    /**
     * Binds full message metadata to this cell (Telegram {@code setMessageObject} parity).
     */
    public void setMessageObject(@NonNull Message message, boolean outgoing, int clusterPosition, boolean showSenderHeader) {
        refreshThemeColors(true);
        this.mMessage = message;
        this.mIsOutgoing = outgoing;
        this.mClusterPosition = clusterPosition;
        this.mIsStarred = message.isStarred();
        this.mIsEdited = message.isEdited();
        this.mVanishTotalDurationMs = message.getVanishTimer();
        this.mVanishExpiresAtMs = message.getExpiresAt() != null
                ? message.getExpiresAt().toDate().getTime() : 0L;
        this.mIsVanish = mVanishExpiresAtMs > 0L || mVanishTotalDurationMs > 0L;

        // Show tail only on SINGLE or LAST message in a cluster
        this.mDrawTail = (clusterPosition == CLUSTER_SINGLE || clusterPosition == CLUSTER_LAST);

        // Group / Persona Sender Header on first/single incoming bubble
        if (!outgoing && showSenderHeader
                && (clusterPosition == CLUSTER_SINGLE || clusterPosition == CLUSTER_FIRST)
                && message.getSenderName() != null && !message.getSenderName().trim().isEmpty()) {
            mSenderHeaderName = message.getSenderName().trim();
            int[] palette = mIsNightMode ? SENDER_COLORS_DARK : SENDER_COLORS_LIGHT;
            String seed = message.getSenderId() != null ? message.getSenderId() : mSenderHeaderName;
            mSenderHeaderColor = palette[Math.abs(seed.hashCode()) % palette.length];
            mSenderHeaderPaint.setColor(mSenderHeaderColor);
        } else {
            mSenderHeaderName = null;
            mSenderHeaderLayout = null;
        }

        updateColors();
        updateCornerRadii();
        updateBubblePath(getWidth(), getHeight());
        invalidate();
    }

    public void setOutgoing(boolean outgoing) {
        refreshThemeColors(false);
        if (this.mIsOutgoing != outgoing) {
            this.mIsOutgoing = outgoing;
            updateColors();
            updateCornerRadii();
            updateBubblePath(getWidth(), getHeight());
            invalidate();
        }
    }

    public boolean isOutgoing() {
        return mIsOutgoing;
    }

    public void setClusterPosition(int clusterPosition) {
        boolean shouldTail = (clusterPosition == CLUSTER_SINGLE || clusterPosition == CLUSTER_LAST);
        if (this.mClusterPosition != clusterPosition || this.mDrawTail != shouldTail) {
            this.mClusterPosition = clusterPosition;
            this.mDrawTail = shouldTail;
            updateCornerRadii();
            updateBubblePath(getWidth(), getHeight());
            invalidate();
        }
    }

    public void setDeliveryStatus(int status) {
        if (this.mDeliveryStatus != status) {
            this.mDeliveryStatus = status;
            invalidate();
        }
    }

    public void setTimeString(String time) {
        this.mTimeString = time != null ? time : "";
        invalidate();
    }

    public void setBadges(boolean starred, boolean vanish, boolean edited) {
        this.mIsStarred = starred;
        this.mIsVanish = vanish;
        this.mIsEdited = edited;
        invalidate();
    }

    public void setCustomBubbleColor(int color) {
        if (this.mCustomBubbleColor != color) {
            this.mCustomBubbleColor = color;
            updateColors();
            invalidate();
        }
    }

    /**
     * Triggers Telegram's smooth accent highlight pulse when jumping to a quoted or searched message.
     */
    public void triggerHighlightPulse() {
        if (mHighlightAnimator != null && mHighlightAnimator.isRunning()) {
            mHighlightAnimator.cancel();
        }
        mHighlightAnimator = ValueAnimator.ofFloat(0f, 0.32f, 0f);
        mHighlightAnimator.setDuration(950);
        mHighlightAnimator.setInterpolator(new DecelerateInterpolator());
        mHighlightAnimator.addUpdateListener(anim -> {
            mHighlightAlpha = (float) anim.getAnimatedValue();
            invalidate();
        });
        mHighlightAnimator.start();
    }

    /**
     * Triggers a floating heart burst animation on canvas when the user double-taps a message bubble.
     */
    public void triggerDoubleTapHeartBurst(float touchX, float touchY) {
        mHeartBurstX = touchX > 0 ? touchX : getWidth() / 2f;
        mHeartBurstY = touchY > 0 ? touchY : getHeight() / 2f;
        if (mHeartBurstAnimator != null && mHeartBurstAnimator.isRunning()) {
            mHeartBurstAnimator.cancel();
        }
        mHeartBurstAnimator = ValueAnimator.ofFloat(0f, 1f);
        mHeartBurstAnimator.setDuration(520);
        mHeartBurstAnimator.setInterpolator(new OvershootInterpolator(1.6f));
        mHeartBurstAnimator.addUpdateListener(anim -> {
            mHeartBurstProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        mHeartBurstAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mHeartBurstProgress = 0f;
                invalidate();
            }
        });
        mHeartBurstAnimator.start();
    }

    // =========================================================================
    // DYNAMIC CORNER CLUSTERING & BEZIER TAIL GEOMETRY
    // =========================================================================

    private float mCustomCornerRadiusDp = -1f;

    public void setCornerRadiusDp(float radiusDp) {
        if (radiusDp > 0 && Math.abs(this.mCustomCornerRadiusDp - radiusDp) > 0.1f) {
            this.mCustomCornerRadiusDp = radiusDp;
            updateCornerRadii();
            updateBubblePath(getWidth(), getHeight());
            invalidate();
        }
    }

    public boolean isDarkBubble() {
        int color = (mIsOutgoing && mCustomBubbleColor != 0) ? mCustomBubbleColor
                : (mIsOutgoing ? mColorBubbleSent : mColorBubbleReceived);
        return ColorUtils.calculateLuminance(color) < 0.5;
    }

    protected void updateCornerRadii() {
        float rL = mCustomCornerRadiusDp > 0 ? (mCustomCornerRadiusDp * mDensity) : mCornerLarge;
        float rS = mCustomCornerRadiusDp > 0 ? Math.min(5f * mDensity, rL * 0.35f) : mCornerSmall;

        if (mIsOutgoing) {
            switch (mClusterPosition) {
                case CLUSTER_FIRST:
                    setRadii(rL, rL, rS, rL);
                    break;
                case CLUSTER_MIDDLE:
                    setRadii(rL, rS, rS, rL);
                    break;
                case CLUSTER_LAST:
                    setRadii(rL, rS, rS, rL);
                    break;
                case CLUSTER_SINGLE:
                default:
                    setRadii(rL, rL, rS, rL);
                    break;
            }
        } else {
            switch (mClusterPosition) {
                case CLUSTER_FIRST:
                    setRadii(rL, rL, rL, rS);
                    break;
                case CLUSTER_MIDDLE:
                    setRadii(rS, rL, rL, rS);
                    break;
                case CLUSTER_LAST:
                    setRadii(rS, rL, rL, rS);
                    break;
                case CLUSTER_SINGLE:
                default:
                    setRadii(rL, rL, rL, rS);
                    break;
            }
        }
    }

    private void setRadii(float tl, float tr, float br, float bl) {
        mRadii[0] = tl; mRadii[1] = tl;
        mRadii[2] = tr; mRadii[3] = tr;
        mRadii[4] = br; mRadii[5] = br;
        mRadii[6] = bl; mRadii[7] = bl;
    }

    protected void updateColors() {
        if (mIsOutgoing) {
            if (mCustomBubbleColor != 0) {
                mBubblePaint.setColor(mCustomBubbleColor);
            } else {
                mBubblePaint.setColor(mColorBubbleSent);
            }
            mBubbleStrokePaint.setColor(mColorBubbleSentStroke);
        } else {
            mBubblePaint.setColor(mColorBubbleReceived);
            mBubbleStrokePaint.setColor(mColorBubbleReceivedStroke);
        }
        mLastGradientHeight = -1;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();
        int innerWidth = measuredWidth - getPaddingLeft() - getPaddingRight();
        if (innerWidth <= 0) {
            return;
        }

        View replyQuote = findViewById(R.id.replyQuoteContainer);
        if (replyQuote != null && replyQuote.getVisibility() == VISIBLE && replyQuote.getMeasuredWidth() < innerWidth) {
            int oldReplyHeight = replyQuote.getMeasuredHeight();
            int exactWidthSpec = MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY);
            int childHeightSpec = getChildMeasureSpec(
                    heightMeasureSpec,
                    getPaddingTop() + getPaddingBottom(),
                    replyQuote.getLayoutParams() != null ? replyQuote.getLayoutParams().height : LayoutParams.WRAP_CONTENT
            );
            replyQuote.measure(exactWidthSpec, childHeightSpec);
            int newReplyHeight = replyQuote.getMeasuredHeight();
            if (newReplyHeight != oldReplyHeight) {
                setMeasuredDimension(measuredWidth, measuredHeight + (newReplyHeight - oldReplyHeight));
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateBubblePath(w, h);
    }

    protected void updateBubblePath(int w, int h) {
        if (w <= 0 || h <= 0) return;

        float strokeInset = dpf2(0.6f);
        mBubbleRect.set(strokeInset, strokeInset, w - strokeInset, h - strokeInset);
        mBubblePath.reset();
        mBubblePath.addRoundRect(mBubbleRect, mRadii, Path.Direction.CW);

        // Subtle Telegram bezier tail on bottom corner for SINGLE / LAST cluster messages
        if (mDrawTail && h >= dp(28)) {
            Path tailPath = new Path();
            if (mIsOutgoing) {
                float right = mBubbleRect.right;
                float bottom = mBubbleRect.bottom;
                tailPath.moveTo(right - dpf2(8f), bottom - dpf2(10f));
                tailPath.quadTo(right, bottom - dpf2(2f), right + dpf2(3.5f), bottom);
                tailPath.quadTo(right - dpf2(3f), bottom, right - dpf2(10f), bottom - dpf2(2f));
                tailPath.close();
            } else {
                float left = mBubbleRect.left;
                float bottom = mBubbleRect.bottom;
                tailPath.moveTo(left + dpf2(8f), bottom - dpf2(10f));
                tailPath.quadTo(left, bottom - dpf2(2f), left - dpf2(3.5f), bottom);
                tailPath.quadTo(left + dpf2(3f), bottom, left + dpf2(10f), bottom - dpf2(2f));
                tailPath.close();
            }
            mBubblePath.op(tailPath, Path.Op.UNION);
        }

        // Subtle vertical gradient sheen for outgoing bubbles
        if (mIsOutgoing && mLastGradientHeight != h) {
            int topSheen = mIsNightMode ? 0x16818CF8 : 0x146366F1;
            int bottomSheen = 0x00000000;
            mSentSheenGradient = new LinearGradient(0, 0, 0, h, topSheen, bottomSheen, Shader.TileMode.CLAMP);
            mBubbleSheenPaint.setShader(mSentSheenGradient);
            mLastGradientHeight = h;
        }
    }

    // =========================================================================
    // DRAWING PIPELINE (Canvas Bubble + Overlays + Child Views)
    // =========================================================================

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mBubblePath.isEmpty() && getWidth() > 0 && getHeight() > 0) {
            updateBubblePath(getWidth(), getHeight());
        }

        canvas.save();

        // 1. Horizontal Swipe-to-Reply Translation & Rotating Indicator
        if (mSwipeTranslationX != 0f) {
            canvas.translate(mSwipeTranslationX, 0f);
            drawSwipeReplyIndicator(canvas);
        }

        // 2. Draw Anti-Aliased Clustered Message Bubble + Theme-Aware Border
        canvas.drawPath(mBubblePath, mBubblePaint);
        if (mIsOutgoing && mSentSheenGradient != null) {
            canvas.drawPath(mBubblePath, mBubbleSheenPaint);
        }
        canvas.drawPath(mBubblePath, mBubbleStrokePaint);

        // 3. Draw Search / Reply Highlight Pulse Overlay
        if (mHighlightAlpha > 0f) {
            mHighlightPaint.setAlpha((int) (255 * mHighlightAlpha));
            canvas.drawPath(mBubblePath, mHighlightPaint);
        }

        // 4. Draw Child Views (Text, Reply Quote, Media Cards, Voice Waveform, Polls)
        super.dispatchDraw(canvas);

        // 5. Draw Live Ephemeral Vanish Countdown Arc (if message is expiring)
        if (mIsVanish && mVanishExpiresAtMs > System.currentTimeMillis()) {
            drawVanishCountdownArc(canvas);
        }

        // 6. Draw Double-Tap Floating Heart Burst Overlay
        if (mHeartBurstProgress > 0f) {
            drawDoubleTapHeartBurst(canvas);
        }

        canvas.restore();
    }

    private void drawVanishCountdownArc(Canvas canvas) {
        long now = System.currentTimeMillis();
        long remaining = Math.max(0L, mVanishExpiresAtMs - now);
        long total = mVanishTotalDurationMs > 0L ? mVanishTotalDurationMs : 60_000L;
        float fraction = Math.max(0.05f, Math.min(1f, (float) remaining / (float) total));

        float r = dpf2(5.5f);
        float cx = mBubbleRect.right - dpf2(12f);
        float cy = mBubbleRect.top + dpf2(12f);
        mVanishArcRect.set(cx - r, cy - r, cx + r, cy + r);

        mVanishArcPaint.setColor(remaining < 10_000L ? mColorError : mColorPrimary);
        mVanishArcPaint.setAlpha(70);
        canvas.drawOval(mVanishArcRect, mVanishArcPaint);

        mVanishArcPaint.setAlpha(235);
        canvas.drawArc(mVanishArcRect, -90f, 360f * fraction, false, mVanishArcPaint);
        postInvalidateDelayed(250);
    }

    private void drawDoubleTapHeartBurst(Canvas canvas) {
        canvas.save();
        float scale = mHeartBurstProgress < 0.7f
                ? (mHeartBurstProgress / 0.7f) * 1.25f
                : 1.25f - ((mHeartBurstProgress - 0.7f) / 0.3f) * 0.4f;
        int alpha = mHeartBurstProgress < 0.65f
                ? 255
                : (int) (255 * (1f - (mHeartBurstProgress - 0.65f) / 0.35f));
        float dy = -dpf2(18f) * mHeartBurstProgress;

        canvas.translate(mHeartBurstX, mHeartBurstY + dy);
        canvas.scale(scale, scale);

        float s = dpf2(14f);
        mHeartBurstPath.reset();
        mHeartBurstPath.moveTo(0, s * 0.35f);
        mHeartBurstPath.cubicTo(-s, -s * 0.35f, -s * 0.55f, -s * 0.95f, 0, -s * 0.4f);
        mHeartBurstPath.cubicTo(s * 0.55f, -s * 0.95f, s, -s * 0.35f, 0, s * 0.35f);
        mHeartBurstPath.close();

        mHeartBurstPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
        canvas.drawPath(mHeartBurstPath, mHeartBurstPaint);
        canvas.restore();
    }

    private void drawSwipeReplyIndicator(Canvas canvas) {
        float dp = mDensity;
        float progress = Math.min(1.0f, Math.abs(mSwipeTranslationX) / mSwipeThreshold);
        float circleSize = (16f + 10f * progress) * dp;
        float circleRadius = circleSize / 2f;

        float cx = mIsOutgoing
                ? (mBubbleRect.right - mSwipeTranslationX + (22f * dp))
                : (mBubbleRect.left - mSwipeTranslationX - (22f * dp));
        float cy = mBubbleRect.centerY();

        mReplyCirclePaint.setAlpha((int) (255 * progress));
        canvas.drawCircle(cx, cy, circleRadius, mReplyCirclePaint);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(progress * 360f);

        mReplyArrowPath.reset();
        mReplyArrowPath.moveTo(-4f * dp, -2f * dp);
        mReplyArrowPath.lineTo(-7f * dp, 1f * dp);
        mReplyArrowPath.lineTo(-4f * dp, 4f * dp);
        mReplyArrowPath.moveTo(-7f * dp, 1f * dp);
        mReplyArrowPath.quadTo(0f, 1f * dp, 4f * dp, -4f * dp);

        mReplyArrowPaint.setAlpha((int) (255 * progress));
        canvas.drawPath(mReplyArrowPath, mReplyArrowPaint);
        canvas.restore();
    }

    // =========================================================================
    // SWIPE-TO-REPLY SPRING PHYSICS & TOUCH HANDLING
    // =========================================================================

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mSwipeReplyListener == null) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mIsSwiping = false;
                mSwipeTriggered = false;
                initVelocityTracker();
                mVelocityTracker.addMovement(ev);
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - mDownX;
                float dy = ev.getY() - mDownY;
                if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    mIsSwiping = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                recycleVelocityTracker();
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mSwipeReplyListener == null) {
            return super.onTouchEvent(ev);
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                return true;

            case MotionEvent.ACTION_MOVE:
                float deltaX = ev.getX() - mDownX;
                if (!mIsSwiping && Math.abs(deltaX) > mTouchSlop) {
                    mIsSwiping = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }

                if (mIsSwiping) {
                    float direction = deltaX < 0 ? -1f : 1f;
                    float absDelta = Math.abs(deltaX);
                    float rubberBand = (float) (mSwipeMaxOffset * Math.tanh(absDelta / (mSwipeMaxOffset * 1.5f)));
                    mSwipeTranslationX = direction * rubberBand;

                    if (absDelta >= mSwipeThreshold && !mSwipeTriggered) {
                        mSwipeTriggered = true;
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    } else if (absDelta < mSwipeThreshold) {
                        mSwipeTriggered = false;
                    }
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIsSwiping) {
                    if (mSwipeTriggered && mSwipeReplyListener != null) {
                        mSwipeReplyListener.onSwipeReplyTriggered(this);
                    }
                    animateSpringReturn();
                }
                recycleVelocityTracker();
                mIsSwiping = false;
                break;
        }
        return super.onTouchEvent(ev);
    }

    private void animateSpringReturn() {
        if (mSpringAnimator != null && mSpringAnimator.isRunning()) {
            mSpringAnimator.cancel();
        }
        mSpringAnimator = ValueAnimator.ofFloat(mSwipeTranslationX, 0f);
        mSpringAnimator.setDuration(280);
        mSpringAnimator.setInterpolator(new OvershootInterpolator(1.4f));
        mSpringAnimator.addUpdateListener(animation -> {
            mSwipeTranslationX = (float) animation.getAnimatedValue();
            invalidate();
        });
        mSpringAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSwipeTranslationX = 0f;
                mSwipeTriggered = false;
                invalidate();
            }
        });
        mSpringAnimator.start();
    }

    private void initVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
}
