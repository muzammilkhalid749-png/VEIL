package com.m3.veil.ui.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.m3.veil.R;
import com.m3.veil.model.Chat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Telegram-Parity Custom View for Dialog/Chat List Rows.
 * Modeled directly on Telegram's {@code org.telegram.ui.Cells.DialogCell}.
 *
 * <p>Architecture & Features:
 * <ul>
 *   <li><b>Zero Child Views</b>: Renders 100% directly onto {@link Canvas} using pre-measured
 *       {@link StaticLayout}s and pre-allocated {@link Paint}s for 120fps scrolling.</li>
 *   <li><b>Dynamic Light & Dark Mode Engine</b>: Automatically resolves all semantic colors from
 *       {@code values/colors.xml} and {@code values-night/colors.xml} on attach, bind, and
 *       {@link #onConfigurationChanged(Configuration)}. Zero hardcoded dark-only or light-only colors.</li>
 *   <li><b>Stories / Status Ring</b>: Renders Telegram's multi-stop gradient ring for unread statuses
 *       and muted ring for viewed statuses around the avatar.</li>
 *   <li><b>Animated Status Checkmarks & Unread Counter</b>: Smooth {@link ValueAnimator} transitions
 *       for delivery/read checkmarks, online presence dot, muted icon, and unread count badge.</li>
 *   <li><b>Inline Media Micro-Thumbnails & Drafts</b>: Renders 18dp rounded media preview badges,
 *       group sender prefixes, red {@code Draft:} labels, {@code @} mention pills, and {@code ❤️} reaction badges.</li>
 *   <li><b>ButtonBounce Physics</b>: Telegram-style 0.98f spring press scale + Material ripple.</li>
 * </ul>
 */
public class VeilDialogCell extends View {

    // Layout Constants (dp) matching Telegram DialogCell
    private static final int CELL_HEIGHT_TWO_LINE_DP = 76;
    private static final int CELL_HEIGHT_THREE_LINE_DP = 84;
    private static final int AVATAR_SIZE_DP = 54;
    private static final int AVATAR_LEFT_DP = 14;
    private static final int CONTENT_LEFT_DP = 80;
    private static final int RIGHT_PADDING_DP = 16;
    private static final int BADGE_SIZE_DP = 22;

    // Status Checkmark Flags (Telegram DialogCell bitmask parity)
    private static final int STATUS_NONE = 0;
    private static final int STATUS_CLOCK = 1;
    private static final int STATUS_CHECK_SINGLE = 2;
    private static final int STATUS_CHECK_DOUBLE = 6; // 2 | 4
    private static final int STATUS_CHECK_READ = 14;  // 2 | 4 | 8

    // Media Preview Icon Types
    private static final int MEDIA_NONE = 0;
    private static final int MEDIA_PHOTO = 1;
    private static final int MEDIA_VIDEO = 2;
    private static final int MEDIA_VOICE = 3;
    private static final int MEDIA_DOCUMENT = 4;
    private static final int MEDIA_LOCATION = 5;
    private static final int MEDIA_POLL = 6;
    private static final int MEDIA_CALL = 7;
    private static final int MEDIA_BLINK = 8;
    private static final int MEDIA_STICKER = 9;

    // Avatar Palette (Harmonic across Light & Dark Mode)
    private static final int[][] AVATAR_GRADIENTS = {
            {0xFF6366F1, 0xFF4F46E5}, // Indigo
            {0xFFEC4899, 0xFFDB2777}, // Pink
            {0xFF10B981, 0xFF059669}, // Emerald
            {0xFFF59E0B, 0xFFD97706}, // Amber
            {0xFF3B82F6, 0xFF2563EB}, // Blue
            {0xFF8B5CF6, 0xFF7C3AED}, // Violet
            {0xFF14B8A6, 0xFF0D9488}, // Teal
            {0xFFF43F5E, 0xFFE11D48}  // Rose
    };

    private final float mDensity;
    private final float mScaledDensity;

    // Shared In-Memory Avatar Bitmap Cache
    private static final android.util.LruCache<String, Bitmap> sAvatarCache =
            new android.util.LruCache<>(24);

    public static void clearAvatarCache() {
        sAvatarCache.evictAll();
    }

    // Dynamic Theme State
    private boolean mIsNightMode = false;
    private int mColorSurface;
    private int mColorSurfaceVariant;
    private int mColorPinnedOverlay;
    private int mColorTextPrimary;
    private int mColorTextSecondary;
    private int mColorPrimary;
    private int mColorAccent;
    private int mColorOutline;
    private int mColorOnline;
    private int mColorError;
    private int mColorStatusSent;
    private int mColorStatusRead;
    private int mColorBadgeMuted;
    private int mColorMediaThumbBg;

    // Pre-allocated Paints (Zero Allocation in onDraw)
    private final TextPaint mNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mNameEncryptedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mMessagePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mMessageTypingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mSenderPrefixPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTimeAccentPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mCountTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mAvatarTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTagTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Paint mPinnedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAvatarCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStoryRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOnlineStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOnlineFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mUnreadBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMentionBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mReactionBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCheckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMediaThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTopicChipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Pre-allocated Geometry
    private final RectF mAvatarRect = new RectF();
    private final RectF mStoryRingRect = new RectF();
    private final RectF mBadgeRect = new RectF();
    private final RectF mMentionRect = new RectF();
    private final RectF mReactionRect = new RectF();
    private final RectF mMediaThumbRect = new RectF();
    private final RectF mTempRect = new RectF();
    private final Path mVectorPath = new Path();

    // Cached StaticLayouts
    private StaticLayout mNameLayout;
    private StaticLayout mSenderLineLayout;
    private StaticLayout mMessageLayout;
    private StaticLayout mTimeLayout;
    private StaticLayout mCountLayout;

    // Bound Chat State
    private Chat mChat;
    private String mCurrentUserId = "";
    private boolean mUseSeparator = true;
    private boolean mUseThreeLines = false;
    private String mAvatarInitials = "?";
    private int mAvatarColorIndex = 0;
    private LinearGradient mAvatarShader;
    private LinearGradient mStoryShader;
    private int mLastAvatarTop = -1;

    // Status & Feature Flags
    private boolean mHasActiveStory = false;
    private boolean mHasUnreadStory = false;
    private boolean mDrawNameLock = false;
    private boolean mDrawVerified = false;
    private boolean mDrawMuted = false;
    private boolean mDrawPinned = false;
    private boolean mHasDraft = false;
    private boolean mHasMention = false;
    private boolean mHasReactionMention = false;
    private int mMediaType = MEDIA_NONE;
    private int mCheckState = STATUS_NONE;
    private int mLastCheckState = STATUS_NONE;
    private String mVanishLabel = null;

    // Animated Progress Values (Telegram DialogCell animation parity)
    private float mOnlineProgress = 0f;
    private float mMutedProgress = 0f;
    private float mStatusTransitionProgress = 1f;
    private float mCountScaleProgress = 1f;
    private float mBounceScale = 1f;
    private int mLastUnreadCount = -1;
    private String mLastChatId = null;

    private ValueAnimator mStatusAnimator;
    private ValueAnimator mCountAnimator;
    private ValueAnimator mBounceAnimator;

    // Computed Coordinates
    private int mNameLeft;
    private int mNameTop;
    private int mNameLockLeft;
    private int mVerifiedLeft;
    private int mMuteIconLeft;
    private int mTimeLeft;
    private int mTimeTop;
    private int mCheckLeft;
    private int mCheckTop;
    private int mSenderLineTop;
    private int mMessageLeft;
    private int mMessageTop;
    private int mMediaThumbLeft;
    private int mMediaThumbTop;
    private int mBadgeLeft;
    private int mBadgeTop;
    private int mBadgeWidth;
    private int mMentionLeft;
    private int mReactionLeft;
    private int mPinLeft;
    private int mPinTop;

    public VeilDialogCell(@NonNull Context context) {
        this(context, null);
    }

    public VeilDialogCell(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VeilDialogCell(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mScaledDensity = context.getResources().getDisplayMetrics().scaledDensity;

        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            setBackgroundResource(outValue.resourceId);
        }
        setClickable(true);
        setFocusable(true);

        initPaints();
        refreshThemeColors(true);
    }

    private int dp(float value) {
        return Math.round(value * mDensity);
    }

    private float dpf2(float value) {
        return value * mDensity;
    }

    private float sp(float value) {
        return value * mScaledDensity;
    }

    private void initPaints() {
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);

        mNamePaint.setTextSize(sp(16f));
        mNamePaint.setTypeface(medium);

        mNameEncryptedPaint.setTextSize(sp(16f));
        mNameEncryptedPaint.setTypeface(medium);

        mSenderPrefixPaint.setTextSize(sp(14f));
        mSenderPrefixPaint.setTypeface(medium);

        mMessagePaint.setTextSize(sp(14.5f));
        mMessagePaint.setTypeface(Typeface.DEFAULT);

        mMessageTypingPaint.setTextSize(sp(14.5f));
        mMessageTypingPaint.setTypeface(medium);

        mTimePaint.setTextSize(sp(12f));
        mTimePaint.setTypeface(Typeface.DEFAULT);

        mTimeAccentPaint.setTextSize(sp(12f));
        mTimeAccentPaint.setTypeface(medium);

        mCountTextPaint.setTextSize(sp(12f));
        mCountTextPaint.setTypeface(bold);

        mAvatarTextPaint.setTextSize(sp(20f));
        mAvatarTextPaint.setTypeface(bold);
        mAvatarTextPaint.setColor(Color.WHITE);
        mAvatarTextPaint.setTextAlign(Paint.Align.CENTER);

        mTagTextPaint.setTextSize(sp(10f));
        mTagTextPaint.setTypeface(bold);

        mStoryRingPaint.setStyle(Paint.Style.STROKE);
        mStoryRingPaint.setStrokeWidth(dpf2(2.2f));
        mStoryRingPaint.setStrokeCap(Paint.Cap.ROUND);

        mCheckPaint.setStyle(Paint.Style.STROKE);
        mCheckPaint.setStrokeWidth(dpf2(1.75f));
        mCheckPaint.setStrokeCap(Paint.Cap.ROUND);
        mCheckPaint.setStrokeJoin(Paint.Join.ROUND);

        mIconStrokePaint.setStyle(Paint.Style.STROKE);
        mIconStrokePaint.setStrokeWidth(dpf2(1.6f));
        mIconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mIconStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mIconFillPaint.setStyle(Paint.Style.FILL);
        mMediaThumbPaint.setStyle(Paint.Style.FILL);
        mDividerPaint.setStrokeWidth(Math.max(1f, dpf2(0.75f)));
    }

    /**
     * Dynamically resolves Light Mode and Dark Mode colors from resource qualifiers.
     */
    public void refreshThemeColors(boolean force) {
        Context context = getContext();
        if (context == null) return;

        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!force && night == mIsNightMode && mColorTextPrimary != 0) {
            return;
        }
        mIsNightMode = night;

        mColorSurface = ContextCompat.getColor(context, R.color.colorSurface);
        mColorSurfaceVariant = ContextCompat.getColor(context, R.color.colorSurfaceVariant);
        mColorTextPrimary = ContextCompat.getColor(context, R.color.textPrimary);
        mColorTextSecondary = ContextCompat.getColor(context, R.color.textSecondary);
        mColorPrimary = ContextCompat.getColor(context, R.color.colorPrimary);
        mColorAccent = ContextCompat.getColor(context, R.color.veil_accent);
        mColorOutline = ContextCompat.getColor(context, R.color.colorOutline);
        mColorOnline = ContextCompat.getColor(context, R.color.statusSuccess);
        mColorError = ContextCompat.getColor(context, R.color.statusError);
        mColorStatusSent = ContextCompat.getColor(context, R.color.message_status_sent);
        mColorStatusRead = ContextCompat.getColor(context, R.color.message_status_read);

        mColorPinnedOverlay = mIsNightMode
                ? ColorUtils.setAlphaComponent(mColorSurfaceVariant, 115)
                : ColorUtils.setAlphaComponent(mColorSurfaceVariant, 165);
        mColorBadgeMuted = mIsNightMode ? 0xFF475569 : 0xFF94A3B8;
        mColorMediaThumbBg = ColorUtils.setAlphaComponent(mColorPrimary, mIsNightMode ? 48 : 30);

        mNamePaint.setColor(mColorTextPrimary);
        mNameEncryptedPaint.setColor(mIsNightMode ? 0xFF34D399 : 0xFF059669);
        mSenderPrefixPaint.setColor(mColorTextPrimary);
        mMessagePaint.setColor(mColorTextSecondary);
        mMessageTypingPaint.setColor(mColorPrimary);
        mTimePaint.setColor(mColorTextSecondary);
        mTimeAccentPaint.setColor(mColorPrimary);
        mCountTextPaint.setColor(Color.WHITE);

        mPinnedBgPaint.setColor(mColorPinnedOverlay);
        mOnlineStrokePaint.setColor(mColorSurface);
        mOnlineFillPaint.setColor(mColorOnline);
        mUnreadBadgePaint.setColor(mColorPrimary);
        mMentionBadgePaint.setColor(mColorPrimary);
        mReactionBadgePaint.setColor(mColorError);
        mDividerPaint.setColor(mColorOutline);
        mTopicChipPaint.setColor(ColorUtils.setAlphaComponent(mColorPrimary, mIsNightMode ? 50 : 28));
        mTagTextPaint.setColor(mColorPrimary);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshThemeColors(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshThemeColors(true);
        if (getMeasuredWidth() > 0 && mChat != null) {
            buildLayout(getMeasuredWidth());
            invalidate();
        }
    }

    /**
     * Binds a {@link Chat} conversation to this Telegram-grade DialogCell.
     */
    public void setChat(@NonNull Chat chat, @Nullable String currentUserId) {
        setChat(chat, currentUserId, true);
    }

    public void setChat(@NonNull Chat chat, @Nullable String currentUserId, boolean useSeparator) {
        refreshThemeColors(false);

        boolean sameChat = mLastChatId != null && mLastChatId.equals(chat.getId());
        this.mChat = chat;
        this.mCurrentUserId = currentUserId != null ? currentUserId : "";
        this.mUseSeparator = useSeparator;

        // Avatar initials & deterministic gradient
        String name = chat.getName() != null && !chat.getName().trim().isEmpty()
                ? chat.getName().trim() : "Anonymous Peer";
        mAvatarInitials = extractInitials(name);
        String seed = chat.getId() != null ? chat.getId() : name;
        mAvatarColorIndex = Math.abs(seed.hashCode()) % AVATAR_GRADIENTS.length;
        mLastAvatarTop = -1; // Rebuild gradient shader on next draw

        // Check active/unread story status
        resolveStoryState(chat);

        // Online & Muted progress state
        boolean isOnline = !chat.isGroup() && chat.isOnline();
        if (!sameChat) {
            mOnlineProgress = isOnline ? 1f : 0f;
            mMutedProgress = chat.isMuted() ? 1f : 0f;
        }

        // Unread count pop animation when count increments on the same row
        int newUnread = Math.max(0, chat.getUnreadCount());
        if (sameChat && newUnread != mLastUnreadCount && newUnread > 0) {
            animateUnreadCounter();
        }
        mLastUnreadCount = newUnread;
        mLastChatId = chat.getId();

        if (getMeasuredWidth() > 0) {
            buildLayout(getMeasuredWidth());
        } else {
            requestLayout();
        }
        invalidate();
    }

    private void resolveStoryState(@NonNull Chat chat) {
        mHasActiveStory = false;
        mHasUnreadStory = false;
        if (chat.isGroup()) return;
        try {
            String peerId = extractPeerId(chat);
            if (peerId != null && !peerId.isEmpty()) {
                SharedPreferences sp = getContext().getSharedPreferences("veil_stories_cache", Context.MODE_PRIVATE);
                long storyTs = sp.getLong("story_ts_" + peerId, 0L);
                if (System.currentTimeMillis() - storyTs < 24 * 3600 * 1000L) {
                    mHasActiveStory = true;
                    mHasUnreadStory = !sp.getBoolean("story_seen_" + peerId, false);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String extractPeerId(@NonNull Chat chat) {
        if (chat.getParticipants() != null) {
            for (String p : chat.getParticipants()) {
                if (p != null && !p.equals(mCurrentUserId)) return p;
            }
        }
        String id = chat.getId();
        if (id != null && id.contains("_")) {
            String[] parts = id.split("_");
            if (parts.length == 2) {
                return parts[0].equals(mCurrentUserId) ? parts[1] : parts[0];
            }
        }
        return id;
    }

    private void animateUnreadCounter() {
        if (mCountAnimator != null && mCountAnimator.isRunning()) {
            mCountAnimator.cancel();
        }
        mCountAnimator = ValueAnimator.ofFloat(0.65f, 1.12f, 1.0f);
        mCountAnimator.setDuration(240);
        mCountAnimator.addUpdateListener(anim -> {
            mCountScaleProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        mCountAnimator.start();
    }

    private void animateCheckStateTransition() {
        if (mStatusAnimator != null && mStatusAnimator.isRunning()) {
            mStatusAnimator.cancel();
        }
        mStatusTransitionProgress = 0f;
        mStatusAnimator = ValueAnimator.ofFloat(0f, 1f);
        mStatusAnimator.setDuration(210);
        mStatusAnimator.addUpdateListener(anim -> {
            mStatusTransitionProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        mStatusAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mStatusTransitionProgress = 1f;
                mLastCheckState = mCheckState;
                invalidate();
            }
        });
        mStatusAnimator.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            animatePressBounce(0.98f);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            animatePressBounce(1.0f);
        }
        return super.onTouchEvent(event);
    }

    private void animatePressBounce(float targetScale) {
        if (mBounceAnimator != null && mBounceAnimator.isRunning()) {
            mBounceAnimator.cancel();
        }
        mBounceAnimator = ValueAnimator.ofFloat(mBounceScale, targetScale);
        mBounceAnimator.setDuration(targetScale < 1f ? 90 : 180);
        if (targetScale == 1f) {
            mBounceAnimator.setInterpolator(new OvershootInterpolator(1.5f));
        }
        mBounceAnimator.addUpdateListener(anim -> {
            mBounceScale = (float) anim.getAnimatedValue();
            invalidate();
        });
        mBounceAnimator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int heightDp = mUseThreeLines ? CELL_HEIGHT_THREE_LINE_DP : CELL_HEIGHT_TWO_LINE_DP;
        int height = dp(heightDp) + (mUseSeparator ? 1 : 0);
        setMeasuredDimension(width, height);
        if (width > 0 && mChat != null) {
            buildLayout(width);
        }
    }

    /**
     * Telegram DialogCell.buildLayout(int width) parity:
     * Pre-computes all StaticLayouts, icon positions, checkmarks, media thumbnails, and badges.
     */
    private void buildLayout(int width) {
        if (mChat == null || width <= 0) return;

        int contentLeft = dp(CONTENT_LEFT_DP);
        int rightEdge = width - dp(RIGHT_PADDING_DP);

        // 1. Outgoing Message Checkmark State Machine
        boolean isOutgoingLast = mChat.getLastMessageSenderId() != null
                && !mChat.getLastMessageSenderId().isEmpty()
                && mChat.getLastMessageSenderId().equals(mCurrentUserId);

        int newCheckState = STATUS_NONE;
        if (isOutgoingLast && !mChat.isTyping()) {
            if (mChat.isLastMessagePending()) {
                newCheckState = STATUS_CLOCK;
            } else if (mChat.isLastMessageRead()) {
                newCheckState = STATUS_CHECK_READ;
            } else if (mChat.isLastMessageDelivered()) {
                newCheckState = STATUS_CHECK_DOUBLE;
            } else {
                newCheckState = STATUS_CHECK_SINGLE;
            }
        }
        if (mLastCheckState != STATUS_NONE && mLastCheckState != newCheckState) {
            mCheckState = newCheckState;
            animateCheckStateTransition();
        } else {
            mCheckState = newCheckState;
            mLastCheckState = newCheckState;
        }

        // 2. Timestamp Layout (Top-Right)
        String timeStr = mChat.getLastMessageTime() != null
                ? formatTelegramTimestamp(mChat.getLastMessageTime().toDate()) : "";
        TextPaint activeTimePaint = mChat.getUnreadCount() > 0 && !mChat.isMuted()
                ? mTimeAccentPaint : mTimePaint;
        int timeWidth = (int) Math.ceil(Layout.getDesiredWidth(timeStr, activeTimePaint));
        mTimeLayout = createSingleLineLayout(timeStr, activeTimePaint, timeWidth);
        mTimeTop = dp(16);
        mTimeLeft = rightEdge - timeWidth;

        // Checkmark coordinates to the left of timestamp (Telegram style)
        if (mCheckState != STATUS_NONE) {
            int checkW = (mCheckState == STATUS_CHECK_DOUBLE || mCheckState == STATUS_CHECK_READ) ? dp(18) : dp(14);
            mCheckLeft = mTimeLeft - checkW - dp(4);
            mCheckTop = mTimeTop + dp(2);
        } else {
            mCheckLeft = mTimeLeft;
        }

        // 3. Title & Security/Trust/Mute Badges (Top-Left)
        mDrawNameLock = mChat.isSecret() || (mChat.getChatLockPin() != null && !mChat.getChatLockPin().isEmpty());
        mDrawVerified = mChat.getTrustScore() >= 80;
        mDrawMuted = mChat.isMuted();
        mDrawPinned = mChat.isPinned();

        mNameLeft = contentLeft;
        mNameTop = dp(14);

        if (mDrawNameLock) {
            mNameLockLeft = mNameLeft;
            mNameLeft += dp(16);
        }

        String rawTitle = mChat.getName() != null && !mChat.getName().trim().isEmpty()
                ? mChat.getName().trim() : "Anonymous Peer";
        if ("channel".equalsIgnoreCase(mChat.getType())) {
            rawTitle = "📢 " + rawTitle;
        } else if (mChat.isGroup()) {
            rawTitle = "👥 " + rawTitle;
        }

        int reservedRightForTitle = width - mCheckLeft + dp(10);
        if (mDrawVerified) reservedRightForTitle += dp(18);
        if (mDrawMuted) reservedRightForTitle += dp(18);

        int maxNameWidth = Math.max(dp(64), width - mNameLeft - reservedRightForTitle);
        TextPaint activeNamePaint = mDrawNameLock ? mNameEncryptedPaint : mNamePaint;
        CharSequence ellipsizedName = TextUtils.ellipsize(rawTitle, activeNamePaint, maxNameWidth, TextUtils.TruncateAt.END);
        mNameLayout = createSingleLineLayout(ellipsizedName, activeNamePaint, maxNameWidth);

        float actualNameLineW = mNameLayout.getLineCount() > 0 ? mNameLayout.getLineWidth(0) : 0;
        int currentBadgeX = (int) (mNameLeft + actualNameLineW + dp(5));

        if (mDrawVerified) {
            mVerifiedLeft = currentBadgeX;
            currentBadgeX += dp(18);
        }
        if (mDrawMuted) {
            mMuteIconLeft = currentBadgeX;
        }

        // 4. Bottom-Right Badges: Unread Counter, Mention '@', Reaction '❤️', Vanish TTL, Pin
        int unreadCount = Math.max(0, mChat.getUnreadCount());
        int rightBottomX = rightEdge;
        mBadgeTop = dp(42);

        if (unreadCount > 0) {
            String countStr = unreadCount > 999 ? "999+" : String.valueOf(unreadCount);
            int countTextW = (int) Math.ceil(Layout.getDesiredWidth(countStr, mCountTextPaint));
            mBadgeWidth = Math.max(dp(BADGE_SIZE_DP), countTextW + dp(12));
            mBadgeLeft = rightBottomX - mBadgeWidth;
            mCountLayout = createSingleLineLayout(countStr, mCountTextPaint, countTextW);
            rightBottomX = mBadgeLeft - dp(6);
        } else {
            mCountLayout = null;
            mBadgeWidth = 0;
            if (mDrawPinned) {
                mPinLeft = rightBottomX - dp(16);
                mPinTop = dp(45);
                rightBottomX = mPinLeft - dp(6);
            }
        }

        // Detect mention / reaction badges on unread chats
        String rawLastMsg = mChat.getLastMessage() != null ? mChat.getLastMessage() : "";
        mHasMention = unreadCount > 0 && (rawLastMsg.contains("@") || rawLastMsg.startsWith("↩️"));
        mHasReactionMention = unreadCount > 0 && (rawLastMsg.contains("reacted ") || rawLastMsg.contains("❤️"));

        if (mHasMention) {
            mMentionLeft = rightBottomX - dp(BADGE_SIZE_DP);
            rightBottomX = mMentionLeft - dp(5);
        }
        if (mHasReactionMention) {
            mReactionLeft = rightBottomX - dp(BADGE_SIZE_DP);
            rightBottomX = mReactionLeft - dp(5);
        }

        // Vanish Timer Indicator
        if (mChat.getVanishTimer() > 0) {
            mVanishLabel = formatVanishShort(mChat.getVanishTimer());
        } else {
            mVanishLabel = null;
        }

        // 5. Subtitle / Draft / Media Preview / Sender Name (Bottom-Left)
        mMessageTop = dp(43);
        mMessageLeft = contentLeft;
        mMediaType = MEDIA_NONE;
        mHasDraft = false;

        // Check if a local unsent draft exists for this chat
        String draftText = null;
        try {
            if (mChat.getId() != null) {
                SharedPreferences draftPrefs = getContext().getSharedPreferences("veil_drafts", Context.MODE_PRIVATE);
                draftText = draftPrefs.getString("draft_" + mChat.getId(), null);
            }
        } catch (Exception ignored) {
        }

        if (mChat.isTyping()) {
            int maxMsgWidth = Math.max(dp(60), rightBottomX - mMessageLeft - dp(8));
            String typingStr = mChat.isGroup() ? " Someone is typing..." : " typing...";
            CharSequence ellipsized = TextUtils.ellipsize(typingStr, mMessageTypingPaint, maxMsgWidth, TextUtils.TruncateAt.END);
            mMessageLayout = createSingleLineLayout(ellipsized, mMessageTypingPaint, maxMsgWidth);
        } else if (draftText != null && !draftText.trim().isEmpty()) {
            mHasDraft = true;
            SpannableStringBuilder draftBuilder = new SpannableStringBuilder("Draft: ");
            draftBuilder.setSpan(new ForegroundColorSpan(mColorError), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            draftBuilder.setSpan(new StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            draftBuilder.append(draftText.trim().replace("\n", " "));
            int maxMsgWidth = Math.max(dp(60), rightBottomX - mMessageLeft - dp(8));
            CharSequence ellipsized = TextUtils.ellipsize(draftBuilder, mMessagePaint, maxMsgWidth, TextUtils.TruncateAt.END);
            mMessageLayout = createSingleLineLayout(ellipsized, mMessagePaint, maxMsgWidth);
        } else {
            mMediaType = resolveMediaType(mChat.getLastMessageType(), rawLastMsg);
            if (mMediaType != MEDIA_NONE) {
                mMediaThumbLeft = mMessageLeft;
                mMediaThumbTop = mMessageTop + dp(1);
                mMessageLeft += dp(23);
            }

            SpannableStringBuilder previewBuilder = buildRichMessagePreview(rawLastMsg, isOutgoingLast);
            int maxMsgWidth = Math.max(dp(60), rightBottomX - mMessageLeft - dp(8));
            CharSequence ellipsized = TextUtils.ellipsize(previewBuilder, mMessagePaint, maxMsgWidth, TextUtils.TruncateAt.END);
            mMessageLayout = createSingleLineLayout(ellipsized, mMessagePaint, maxMsgWidth);
        }
    }

    private int resolveMediaType(@Nullable String type, @NonNull String rawMsg) {
        String t = type != null ? type.toLowerCase(Locale.ROOT) : "";
        String lower = rawMsg.toLowerCase(Locale.ROOT);
        if ("image".equals(t) || lower.startsWith("[image") || lower.contains("📷 photo")) {
            return MEDIA_PHOTO;
        } else if ("video".equals(t) || lower.startsWith("[video") || lower.contains("🎥 video")) {
            return MEDIA_VIDEO;
        } else if ("voice".equals(t) || lower.startsWith("[voice") || lower.contains("🎤 voice")) {
            return MEDIA_VOICE;
        } else if ("document".equals(t) || lower.contains("📄")) {
            return MEDIA_DOCUMENT;
        } else if ("location".equals(t) || lower.startsWith("geo:") || lower.contains("📍")) {
            return MEDIA_LOCATION;
        } else if ("poll".equals(t) || lower.startsWith("📊")) {
            return MEDIA_POLL;
        } else if ("call".equals(t) || lower.contains("voice call") || lower.contains("video call")) {
            return MEDIA_CALL;
        } else if (lower.contains("blink") || lower.contains("view-once")) {
            return MEDIA_BLINK;
        } else if ("sticker".equals(t)) {
            return MEDIA_STICKER;
        }
        return MEDIA_NONE;
    }

    private SpannableStringBuilder buildRichMessagePreview(@NonNull String rawMsg, boolean isOutgoingLast) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String clean = rawMsg.replace("\n", " ").trim();
        if (clean.isEmpty()) {
            clean = "No messages yet";
        } else if ("[image]".equalsIgnoreCase(clean) || "[image message]".equalsIgnoreCase(clean)) {
            clean = "Photo";
        } else if ("[video]".equalsIgnoreCase(clean) || "[video message]".equalsIgnoreCase(clean)) {
            clean = "Video";
        } else if ("[voice]".equalsIgnoreCase(clean) || "[voice message]".equalsIgnoreCase(clean)) {
            clean = "Voice message";
        } else if (clean.startsWith("geo:")) {
            clean = "Shared Location";
        }

        // Group or outgoing sender prefix in primary text color (Telegram style)
        if (isOutgoingLast && !clean.startsWith("You:")) {
            String prefix = "You: ";
            sb.append(prefix);
            sb.setSpan(new ForegroundColorSpan(mColorTextPrimary), 0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, prefix.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (mChat != null && mChat.isGroup() && clean.contains(": ")) {
            int colonIdx = clean.indexOf(": ");
            if (colonIdx > 0 && colonIdx < 24) {
                String senderPrefix = clean.substring(0, colonIdx + 2);
                clean = clean.substring(colonIdx + 2);
                sb.append(senderPrefix);
                sb.setSpan(new ForegroundColorSpan(mColorPrimary), 0, senderPrefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, senderPrefix.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        int contentStart = sb.length();
        sb.append(clean);

        // Highlight media label in primary accent when no custom caption
        if (mMediaType != MEDIA_NONE && (clean.equals("Photo") || clean.equals("Video")
                || clean.equals("Voice message") || clean.equals("Shared Location"))) {
            sb.setSpan(new ForegroundColorSpan(mColorPrimary), contentStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private StaticLayout createSingleLineLayout(CharSequence text, TextPaint paint, int width) {
        int safeWidth = Math.max(1, width);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, safeWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
        } else {
            return new StaticLayout(text, paint, safeWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        }
    }

    // =========================================================================
    // SINGLE-PASS CANVAS RENDERING (Telegram DialogCell.onDraw Parity)
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (mChat == null) return;

        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        boolean needsNextFrame = false;

        // 0. Pinned Dialog Background Highlight
        if (mDrawPinned) {
            canvas.drawRect(0, 0, width, height - (mUseSeparator ? 1 : 0), mPinnedBgPaint);
        }

        canvas.save();
        if (mBounceScale != 1f) {
            canvas.scale(mBounceScale, mBounceScale, width / 2f, height / 2f);
        }

        // 1. Draw Avatar + Story Ring + Online/TTL Overlays
        if (drawAvatarAndOverlays(canvas, height)) {
            needsNextFrame = true;
        }

        // 2. Draw Lock Icon + Chat Title + Verified Shield + Animated Mute Icon
        if (drawTitleRow(canvas)) {
            needsNextFrame = true;
        }

        // 3. Draw Checkmarks + Timestamp (Top-Right)
        drawCheckStatusAndTime(canvas);

        // 4. Draw Message Preview / Typing Wave / Inline Media Thumbnail (Bottom-Left)
        if (drawSubtitleRow(canvas)) {
            needsNextFrame = true;
        }

        // 5. Draw Unread Counter Pill / Mention '@' / Reaction '❤️' / Pin Icon (Bottom-Right)
        drawBadgesRow(canvas);

        canvas.restore();

        // 6. Hairline Bottom Divider aligned to content column
        if (mUseSeparator) {
            canvas.drawLine(dp(CONTENT_LEFT_DP), height - 1, width, height - 1, mDividerPaint);
        }

        if (needsNextFrame) {
            postInvalidateOnAnimation();
        }
    }

    private boolean drawAvatarAndOverlays(Canvas canvas, int cellHeight) {
        boolean needInvalidate = false;
        int avatarSize = dp(AVATAR_SIZE_DP);
        int avatarLeft = dp(AVATAR_LEFT_DP);
        int avatarTop = (cellHeight - avatarSize) / 2;
        float cx = avatarLeft + avatarSize / 2f;
        float cy = avatarTop + avatarSize / 2f;
        float radius = avatarSize / 2f;

        // Rebuild gradient shaders if row height changed
        if (mLastAvatarTop != avatarTop) {
            int[] colors = AVATAR_GRADIENTS[mAvatarColorIndex];
            mAvatarShader = new LinearGradient(
                    avatarLeft, avatarTop, avatarLeft + avatarSize, avatarTop + avatarSize,
                    colors[0], colors[1], Shader.TileMode.CLAMP);
            mStoryShader = new LinearGradient(
                    avatarLeft, avatarTop, avatarLeft + avatarSize, avatarTop + avatarSize,
                    new int[]{0xFF6366F1, 0xFFEC4899, 0xFF10B981},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP);
            mLastAvatarTop = avatarTop;
        }

        // Story Ring (Telegram StoriesUtilities.drawAvatarWithStory parity)
        float avatarDrawRadius = radius;
        if (mHasActiveStory) {
            float ringRadius = radius + dpf2(2.5f);
            mStoryRingRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
            if (mHasUnreadStory) {
                mStoryRingPaint.setShader(mStoryShader);
                mStoryRingPaint.setStrokeWidth(dpf2(2.3f));
            } else {
                mStoryRingPaint.setShader(null);
                mStoryRingPaint.setColor(mColorOutline);
                mStoryRingPaint.setStrokeWidth(dpf2(1.5f));
            }
            canvas.drawOval(mStoryRingRect, mStoryRingPaint);
            avatarDrawRadius = radius - dpf2(1.8f);
        }

        // Avatar Circle Fill
        mAvatarCirclePaint.setShader(mAvatarShader);
        canvas.drawCircle(cx, cy, avatarDrawRadius, mAvatarCirclePaint);

        // Universal Decoded Avatar Bitmap or Initials (Telegram Parity)
        Bitmap avatarBitmap = null;
        if (mChat.getAvatar() != null && !mChat.getAvatar().trim().isEmpty()) {
            avatarBitmap = com.m3.veil.utils.VeilAvatarLoader.getAvatarBitmap(
                    getContext(),
                    mChat.getAvatar(),
                    mChat.getName(),
                    (int) (avatarDrawRadius * 2),
                    mChat.isGroup()
            );
        }
        if (avatarBitmap != null && !avatarBitmap.isRecycled()) {
            mAvatarRect.set(cx - avatarDrawRadius, cy - avatarDrawRadius, cx + avatarDrawRadius, cy + avatarDrawRadius);
            canvas.drawBitmap(avatarBitmap, null, mAvatarRect, null);
        } else {
            Paint.FontMetrics fm = mAvatarTextPaint.getFontMetrics();
            float textBaseline = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(mAvatarInitials, cx, textBaseline, mAvatarTextPaint);
        }

        // Animated Online Presence Dot (150ms smooth transition like Telegram)
        boolean isOnline = !mChat.isGroup() && mChat.isOnline();
        if (isOnline && mOnlineProgress < 1f) {
            mOnlineProgress = Math.min(1f, mOnlineProgress + 16f / 150f);
            needInvalidate = true;
        } else if (!isOnline && mOnlineProgress > 0f) {
            mOnlineProgress = Math.max(0f, mOnlineProgress - 16f / 150f);
            needInvalidate = true;
        }

        if (mOnlineProgress > 0f) {
            float dotX = avatarLeft + avatarSize - dp(7);
            float dotY = avatarTop + avatarSize - dp(7);
            canvas.drawCircle(dotX, dotY, dpf2(7.5f) * mOnlineProgress, mOnlineStrokePaint);
            canvas.drawCircle(dotX, dotY, dpf2(5.2f) * mOnlineProgress, mOnlineFillPaint);
        } else if (mVanishLabel != null) {
            // Telegram TTL Timer Mini-Badge on Avatar Bottom-Right when Vanish Mode is active
            float ttlX = avatarLeft + avatarSize - dp(8);
            float ttlY = avatarTop + avatarSize - dp(8);
            canvas.drawCircle(ttlX, ttlY, dpf2(9.5f), mOnlineStrokePaint);
            mIconFillPaint.setColor(mColorPrimary);
            canvas.drawCircle(ttlX, ttlY, dpf2(8f), mIconFillPaint);

            mIconStrokePaint.setColor(Color.WHITE);
            mIconStrokePaint.setStrokeWidth(dpf2(1.2f));
            canvas.drawLine(ttlX, ttlY - dpf2(3.5f), ttlX, ttlY, mIconStrokePaint);
            canvas.drawLine(ttlX, ttlY, ttlX + dpf2(2.5f), ttlY + dpf2(1.5f), mIconStrokePaint);
        }

        return needInvalidate;
    }

    private boolean drawTitleRow(Canvas canvas) {
        boolean needInvalidate = false;

        // 1. Secret / Locked Chat Padlock Icon
        if (mDrawNameLock) {
            int lockColor = mIsNightMode ? 0xFF34D399 : 0xFF059669;
            mIconStrokePaint.setColor(lockColor);
            mIconStrokePaint.setStrokeWidth(dpf2(1.5f));
            mIconFillPaint.setColor(lockColor);

            float lx = mNameLockLeft + dpf2(2f);
            float ly = mNameTop + dpf2(3f);
            // Shackle arc
            mTempRect.set(lx + dpf2(2f), ly, lx + dpf2(8.5f), ly + dpf2(6.5f));
            canvas.drawArc(mTempRect, 180, 180, false, mIconStrokePaint);
            // Lock body
            mTempRect.set(lx + dpf2(0.8f), ly + dpf2(5f), lx + dpf2(9.7f), ly + dpf2(12.2f));
            canvas.drawRoundRect(mTempRect, dpf2(1.8f), dpf2(1.8f), mIconFillPaint);
        }

        // 2. Chat Title StaticLayout
        if (mNameLayout != null) {
            canvas.save();
            canvas.translate(mNameLeft, mNameTop);
            mNameLayout.draw(canvas);
            canvas.restore();
        }

        // 3. Verified Peer Shield Badge (Telegram Verified Check Parity)
        if (mDrawVerified) {
            float vx = mVerifiedLeft + dpf2(7f);
            float vy = mNameTop + dpf2(9.5f);
            mIconFillPaint.setColor(mColorPrimary);
            canvas.drawCircle(vx, vy, dpf2(6.5f), mIconFillPaint);

            mIconStrokePaint.setColor(Color.WHITE);
            mIconStrokePaint.setStrokeWidth(dpf2(1.5f));
            mVectorPath.reset();
            mVectorPath.moveTo(vx - dpf2(2.8f), vy + dpf2(0.2f));
            mVectorPath.lineTo(vx - dpf2(0.7f), vy + dpf2(2.3f));
            mVectorPath.lineTo(vx + dpf2(3.1f), vy - dpf2(1.9f));
            canvas.drawPath(mVectorPath, mIconStrokePaint);
        }

        // 4. Animated Muted Speaker Icon
        if (mDrawMuted && mMutedProgress < 1f) {
            mMutedProgress = Math.min(1f, mMutedProgress + 16f / 150f);
            needInvalidate = true;
        } else if (!mDrawMuted && mMutedProgress > 0f) {
            mMutedProgress = Math.max(0f, mMutedProgress - 16f / 150f);
            needInvalidate = true;
        }

        if (mMutedProgress > 0f) {
            float mx = mMuteIconLeft + dpf2(2f);
            float my = mNameTop + dpf2(4.5f);
            canvas.save();
            canvas.scale(mMutedProgress, mMutedProgress, mx + dpf2(6f), my + dpf2(5f));
            mIconStrokePaint.setColor(mColorTextSecondary);
            mIconStrokePaint.setStrokeWidth(dpf2(1.4f));
            // Speaker cone
            mVectorPath.reset();
            mVectorPath.moveTo(mx + dpf2(1f), my + dpf2(3.5f));
            mVectorPath.lineTo(mx + dpf2(3.5f), my + dpf2(3.5f));
            mVectorPath.lineTo(mx + dpf2(6.8f), my + dpf2(1f));
            mVectorPath.lineTo(mx + dpf2(6.8f), my + dpf2(9.5f));
            mVectorPath.lineTo(mx + dpf2(3.5f), my + dpf2(7f));
            mVectorPath.lineTo(mx + dpf2(1f), my + dpf2(7f));
            mVectorPath.close();
            canvas.drawPath(mVectorPath, mIconStrokePaint);
            // Slash line
            canvas.drawLine(mx + dpf2(9f), my + dpf2(2f), mx + dpf2(1.5f), my + dpf2(9f), mIconStrokePaint);
            canvas.restore();
        }

        return needInvalidate;
    }

    private void drawCheckStatusAndTime(Canvas canvas) {
        // 1. Outgoing Delivery / Read Checkmarks with smooth scale/alpha transition
        if (mCheckState != STATUS_NONE) {
            canvas.save();
            float s = 0.75f + 0.25f * mStatusTransitionProgress;
            canvas.scale(s, s, mCheckLeft + dp(7), mCheckTop + dp(6));

            if (mCheckState == STATUS_CLOCK) {
                mCheckPaint.setColor(mColorStatusSent);
                float cx = mCheckLeft + dpf2(6f);
                float cy = mCheckTop + dpf2(6.5f);
                canvas.drawCircle(cx, cy, dpf2(5.2f), mCheckPaint);
                canvas.drawLine(cx, cy - dpf2(2.8f), cx, cy, mCheckPaint);
                canvas.drawLine(cx, cy, cx + dpf2(2.2f), cy + dpf2(1.4f), mCheckPaint);
            } else {
                boolean isRead = mCheckState == STATUS_CHECK_READ;
                boolean isDouble = mCheckState == STATUS_CHECK_DOUBLE || isRead;
                mCheckPaint.setColor(isRead ? mColorStatusRead : mColorStatusSent);

                // First Checkmark
                mVectorPath.reset();
                mVectorPath.moveTo(mCheckLeft + dpf2(1f), mCheckTop + dpf2(6.8f));
                mVectorPath.lineTo(mCheckLeft + dpf2(4.2f), mCheckTop + dpf2(10f));
                mVectorPath.lineTo(mCheckLeft + dpf2(10.5f), mCheckTop + dpf2(3.2f));
                canvas.drawPath(mVectorPath, mCheckPaint);

                // Second Checkmark (Double Tick)
                if (isDouble) {
                    mVectorPath.reset();
                    mVectorPath.moveTo(mCheckLeft + dpf2(6f), mCheckTop + dpf2(6.8f));
                    mVectorPath.lineTo(mCheckLeft + dpf2(9.2f), mCheckTop + dpf2(10f));
                    mVectorPath.lineTo(mCheckLeft + dpf2(15.5f), mCheckTop + dpf2(3.2f));
                    canvas.drawPath(mVectorPath, mCheckPaint);
                }
            }
            canvas.restore();
        }

        // 2. Timestamp StaticLayout
        if (mTimeLayout != null) {
            canvas.save();
            canvas.translate(mTimeLeft, mTimeTop);
            mTimeLayout.draw(canvas);
            canvas.restore();
        }
    }

    private boolean drawSubtitleRow(Canvas canvas) {
        boolean needInvalidate = false;

        // 1. Animated 3-Dot Typing Indicator (Telegram TypingDotsDrawable parity)
        if (mChat.isTyping()) {
            long now = System.currentTimeMillis();
            float dotBaseX = dp(CONTENT_LEFT_DP) + dpf2(3f);
            float dotBaseY = mMessageTop + dpf2(10f);
            mIconFillPaint.setColor(mColorPrimary);

            for (int i = 0; i < 3; i++) {
                float phase = ((now + i * 160L) % 900L) / 900f;
                float bounce = (float) Math.sin(phase * Math.PI * 2f);
                float radius = dpf2(2.3f) + dpf2(0.7f) * Math.max(0f, bounce);
                float dy = -dpf2(2.2f) * Math.max(0f, bounce);
                canvas.drawCircle(dotBaseX + i * dpf2(6f), dotBaseY + dy, radius, mIconFillPaint);
            }

            if (mMessageLayout != null) {
                canvas.save();
                canvas.translate(dp(CONTENT_LEFT_DP) + dp(18), mMessageTop);
                mMessageLayout.draw(canvas);
                canvas.restore();
            }
            return true; // Continue animating typing dots
        }

        // 2. Inline Media Micro-Thumbnail Badge (18dp x 18dp rounded square)
        if (!mHasDraft && mMediaType != MEDIA_NONE) {
            float thumbSize = dpf2(18f);
            mMediaThumbRect.set(mMediaThumbLeft, mMediaThumbTop, mMediaThumbLeft + thumbSize, mMediaThumbTop + thumbSize);
            mMediaThumbPaint.setColor(mColorMediaThumbBg);
            canvas.drawRoundRect(mMediaThumbRect, dpf2(5f), dpf2(5f), mMediaThumbPaint);

            float icx = mMediaThumbRect.centerX();
            float icy = mMediaThumbRect.centerY();
            mIconFillPaint.setColor(mColorPrimary);
            mIconStrokePaint.setColor(mColorPrimary);
            mIconStrokePaint.setStrokeWidth(dpf2(1.4f));

            switch (mMediaType) {
                case MEDIA_VIDEO:
                    // Play triangle
                    mVectorPath.reset();
                    mVectorPath.moveTo(icx - dpf2(2.5f), icy - dpf2(3.5f));
                    mVectorPath.lineTo(icx + dpf2(3.8f), icy);
                    mVectorPath.lineTo(icx - dpf2(2.5f), icy + dpf2(3.5f));
                    mVectorPath.close();
                    canvas.drawPath(mVectorPath, mIconFillPaint);
                    break;
                case MEDIA_VOICE:
                    // 3 vertical waveform bars
                    canvas.drawLine(icx - dpf2(3.5f), icy - dpf2(2.5f), icx - dpf2(3.5f), icy + dpf2(2.5f), mIconStrokePaint);
                    canvas.drawLine(icx, icy - dpf2(4.5f), icx, icy + dpf2(4.5f), mIconStrokePaint);
                    canvas.drawLine(icx + dpf2(3.5f), icy - dpf2(2f), icx + dpf2(3.5f), icy + dpf2(2f), mIconStrokePaint);
                    break;
                case MEDIA_POLL:
                    // Horizontal poll bars
                    canvas.drawLine(icx - dpf2(4f), icy - dpf2(3f), icx + dpf2(4f), icy - dpf2(3f), mIconStrokePaint);
                    canvas.drawLine(icx - dpf2(4f), icy, icx + dpf2(2f), icy, mIconStrokePaint);
                    canvas.drawLine(icx - dpf2(4f), icy + dpf2(3f), icx + dpf2(3f), icy + dpf2(3f), mIconStrokePaint);
                    break;
                case MEDIA_LOCATION:
                    // Map pin circle
                    canvas.drawCircle(icx, icy - dpf2(1f), dpf2(3f), mIconStrokePaint);
                    canvas.drawCircle(icx, icy - dpf2(1f), dpf2(1f), mIconFillPaint);
                    break;
                case MEDIA_BLINK:
                    // View-Once dashed ring
                    canvas.drawCircle(icx, icy, dpf2(4.5f), mIconStrokePaint);
                    break;
                case MEDIA_PHOTO:
                default:
                    // Mini landscape mountain + sun inside rounded frame
                    canvas.drawCircle(icx - dpf2(2.2f), icy - dpf2(2.2f), dpf2(1.4f), mIconFillPaint);
                    mVectorPath.reset();
                    mVectorPath.moveTo(icx - dpf2(4.5f), icy + dpf2(4f));
                    mVectorPath.lineTo(icx - dpf2(1f), icy);
                    mVectorPath.lineTo(icx + dpf2(1.5f), icy + dpf2(2.5f));
                    mVectorPath.lineTo(icx + dpf2(3.2f), icy + dpf2(0.8f));
                    mVectorPath.lineTo(icx + dpf2(5f), icy + dpf2(4f));
                    mVectorPath.close();
                    canvas.drawPath(mVectorPath, mIconFillPaint);
                    break;
            }
        }

        // 3. Message Preview StaticLayout
        if (mMessageLayout != null) {
            canvas.save();
            canvas.translate(mMessageLeft, mMessageTop);
            mMessageLayout.draw(canvas);
            canvas.restore();
        }

        return needInvalidate;
    }

    private void drawBadgesRow(Canvas canvas) {
        int badgeH = dp(BADGE_SIZE_DP);
        float badgeRadius = badgeH / 2f;

        // 1. Reaction '❤️' Mention Pill
        if (mHasReactionMention) {
            mReactionRect.set(mReactionLeft, mBadgeTop, mReactionLeft + badgeH, mBadgeTop + badgeH);
            canvas.drawRoundRect(mReactionRect, badgeRadius, badgeRadius, mReactionBadgePaint);
            float hcx = mReactionRect.centerX();
            float hcy = mReactionRect.centerY() + sp(4f);
            canvas.drawText("♥", hcx, hcy, mAvatarTextPaint.getTextSize() > 0 ? createMiniBadgePaint() : mCountTextPaint);
        }

        // 2. Direct '@' Mention Pill
        if (mHasMention) {
            mMentionRect.set(mMentionLeft, mBadgeTop, mMentionLeft + badgeH, mBadgeTop + badgeH);
            canvas.drawRoundRect(mMentionRect, badgeRadius, badgeRadius, mMentionBadgePaint);
            Paint miniPaint = createMiniBadgePaint();
            canvas.drawText("@", mMentionRect.centerX(), mMentionRect.centerY() + sp(4f), miniPaint);
        }

        // 3. Unread Counter Pill (with scale animation & muted gray state)
        int unreadCount = mChat.getUnreadCount();
        if (unreadCount > 0 && mCountLayout != null) {
            mBadgeRect.set(mBadgeLeft, mBadgeTop, mBadgeLeft + mBadgeWidth, mBadgeTop + badgeH);
            mUnreadBadgePaint.setColor(mChat.isMuted() ? mColorBadgeMuted : mColorPrimary);

            canvas.save();
            if (mCountScaleProgress != 1f) {
                canvas.scale(mCountScaleProgress, mCountScaleProgress, mBadgeRect.centerX(), mBadgeRect.centerY());
            }
            canvas.drawRoundRect(mBadgeRect, badgeRadius, badgeRadius, mUnreadBadgePaint);

            float textX = mBadgeLeft + (mBadgeWidth - mCountLayout.getWidth()) / 2f;
            float textY = mBadgeTop + (badgeH - mCountLayout.getHeight()) / 2f;
            canvas.translate(textX, textY);
            mCountLayout.draw(canvas);
            canvas.restore();
        } else if (mDrawPinned) {
            // 4. Vector Pushpin Icon (when unreadCount == 0 and pinned)
            mIconStrokePaint.setColor(mColorTextSecondary);
            mIconStrokePaint.setStrokeWidth(dpf2(1.5f));
            float px = mPinLeft + dpf2(8f);
            float py = mPinTop + dpf2(7f);
            canvas.save();
            canvas.rotate(42f, px, py);
            // Pin head & body
            canvas.drawLine(px - dpf2(3.5f), py - dpf2(4.5f), px + dpf2(3.5f), py - dpf2(4.5f), mIconStrokePaint);
            canvas.drawLine(px - dpf2(2f), py - dpf2(4.5f), px - dpf2(2.8f), py + dpf2(0.5f), mIconStrokePaint);
            canvas.drawLine(px + dpf2(2f), py - dpf2(4.5f), px + dpf2(2.8f), py + dpf2(0.5f), mIconStrokePaint);
            canvas.drawLine(px - dpf2(4.2f), py + dpf2(0.5f), px + dpf2(4.2f), py + dpf2(0.5f), mIconStrokePaint);
            // Pin needle
            canvas.drawLine(px, py + dpf2(0.5f), px, py + dpf2(6f), mIconStrokePaint);
            canvas.restore();
        }
    }

    private Paint mMiniBadgePaint;

    private Paint createMiniBadgePaint() {
        if (mMiniBadgePaint == null) {
            mMiniBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mMiniBadgePaint.setColor(Color.WHITE);
            mMiniBadgePaint.setTextSize(sp(11.5f));
            mMiniBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);
            mMiniBadgePaint.setTextAlign(Paint.Align.CENTER);
        }
        return mMiniBadgePaint;
    }

    // =========================================================================
    // FORMATTING HELPERS
    // =========================================================================

    private Bitmap getOrLoadAvatarBitmap(@NonNull String avatarKey, int sizePx) {
        if (sizePx <= 0) return null;
        String cacheKey = avatarKey + "_" + sizePx;
        Bitmap cached = sAvatarCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        try {
            Context ctx = getContext();
            int resId = ctx.getResources().getIdentifier(avatarKey, "drawable", ctx.getPackageName());
            if (resId != 0) {
                android.graphics.drawable.Drawable d = ContextCompat.getDrawable(ctx, resId);
                if (d != null) {
                    Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    Path clipPath = new Path();
                    clipPath.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
                    c.clipPath(clipPath);
                    d.setBounds(0, 0, sizePx, sizePx);
                    d.draw(c);
                    sAvatarCache.put(cacheKey, bmp);
                    return bmp;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String extractInitials(@NonNull String name) {
        String clean = name.replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
        if (clean.isEmpty()) return "V";
        String[] parts = clean.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (String.valueOf(parts[0].charAt(0)) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
        }
        return String.valueOf(clean.charAt(0)).toUpperCase(Locale.ROOT);
    }

    private static String formatTelegramTimestamp(@NonNull Date date) {
        Calendar now = Calendar.getInstance();
        Calendar msg = Calendar.getInstance();
        msg.setTime(date);

        if (now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        }

        long diffDays = (now.getTimeInMillis() - msg.getTimeInMillis()) / (24L * 3600L * 1000L);
        if (diffDays < 7 && now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)) {
            return new SimpleDateFormat("EEE", Locale.getDefault()).format(date);
        }
        if (now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)) {
            return new SimpleDateFormat("MMM d", Locale.getDefault()).format(date);
        }
        return new SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(date);
    }

    private static String formatVanishShort(long millis) {
        long sec = millis / 1000L;
        if (sec < 60) return sec + "s";
        long min = sec / 60L;
        if (min < 60) return min + "m";
        long hr = min / 60L;
        if (hr < 24) return hr + "h";
        return (hr / 24L) + "d";
    }
}
