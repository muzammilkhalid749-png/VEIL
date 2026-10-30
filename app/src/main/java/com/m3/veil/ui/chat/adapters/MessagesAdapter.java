package com.m3.veil.ui.chat.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.chat.MediaViewerActivity;
import com.m3.veil.ui.views.VeilAdvancedTextView;
import com.m3.veil.ui.views.VoiceWaveformView;
import com.m3.veil.utils.BlurUtils;
import com.m3.veil.utils.FileMediaManager;
import com.m3.veil.utils.ImageLoader;
import com.m3.veil.utils.VoicePlayerManager;

import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_NOTICE = 100;
    public static final int VIEW_TYPE_SENT = 101;
    public static final int VIEW_TYPE_RECEIVED = 102;
    public static final int VIEW_TYPE_TYPING = 103;

    private List<Message> messages;
    private String currentUserId;
    private String recipientId;
    private String recipientName;
    private String searchHighlightQuery = "";
    private String activeSearchMessageId = null;
    private SimpleDateFormat timeFormat;

    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public void setSearchHighlightState(String query, String activeMessageId) {
        String clean = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        boolean changed = !clean.equals(this.searchHighlightQuery)
                || (activeMessageId == null ? this.activeSearchMessageId != null : !activeMessageId.equals(this.activeSearchMessageId));
        this.searchHighlightQuery = clean;
        this.activeSearchMessageId = activeMessageId;
        if (changed) {
            notifyDataSetChanged();
        }
    }

    private CharSequence applySearchHighlightSpans(CharSequence base, Message message) {
        if (searchHighlightQuery == null || searchHighlightQuery.isEmpty() || base == null || base.length() == 0) {
            return base;
        }
        String lowerText = base.toString().toLowerCase(Locale.ROOT);
        int idx = lowerText.indexOf(searchHighlightQuery);
        if (idx < 0) {
            return base;
        }
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(base);
        boolean isActiveMatch = message != null && message.getId() != null && message.getId().equals(activeSearchMessageId);
        int bgColor = isActiveMatch ? 0xFFF59E0B : 0xCCFDE047;
        int fgColor = 0xFF0F172A;
        int qLen = searchHighlightQuery.length();
        while (idx >= 0 && idx + qLen <= ssb.length()) {
            ssb.setSpan(new android.text.style.BackgroundColorSpan(bgColor), idx, idx + qLen, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new android.text.style.ForegroundColorSpan(fgColor), idx, idx + qLen, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), idx, idx + qLen, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = lowerText.indexOf(searchHighlightQuery, idx + qLen);
        }
        return ssb;
    }

    public String resolveReplyQuoteSenderName(Message message, Message replyTo) {
        if (replyTo != null) {
            if (isSenderMe(replyTo.getSenderId(), replyTo)) {
                return "You";
            }
            if (replyTo.getSenderName() != null && !com.m3.veil.manager.VeilFeatureManager.isShadowVeilOrGeneric(replyTo.getSenderName())) {
                return replyTo.getSenderName().trim();
            }
        }
        String rawReplySender = message != null ? message.getReplySenderName() : null;
        if ("You".equalsIgnoreCase(rawReplySender)) {
            return "You";
        }
        if (rawReplySender != null && !com.m3.veil.manager.VeilFeatureManager.isShadowVeilOrGeneric(rawReplySender)) {
            return rawReplySender.trim();
        }
        if (recipientName != null && !com.m3.veil.manager.VeilFeatureManager.isShadowVeilOrGeneric(recipientName)) {
            return recipientName.trim();
        }
        if (message != null && message.getChatId() != null) {
            try {
                com.m3.veil.model.Chat dialog = com.m3.veil.manager.VeilMessagesController
                        .getInstance(com.m3.veil.VeilApplication.getInstance())
                        .getDialog(message.getChatId());
                if (dialog != null && !dialog.isGroup() && dialog.getName() != null
                        && !com.m3.veil.manager.VeilFeatureManager.isShadowVeilOrGeneric(dialog.getName())) {
                    return dialog.getName().trim();
                }
            } catch (Throwable ignored) {}
        }
        return "Peer";
    }

    /**
     * Resolves the group sender display name honoring the user's privacy policy:
     * - If preferUsername or sovereign profile has showUsernameInGroup enabled, renders @username.
     * - Otherwise renders the profile display name (or "Peer #XXXX").
     */
    public static String resolveSenderDisplayName(android.content.Context ctx, Message message) {
        if (message == null) return "Peer";
        String senderId = message.getSenderId();
        String username = message.getSenderUsername();
        boolean preferUsername = message.isPreferUsername();
        String profileName = message.getSenderName();

        if (senderId != null && ctx != null) {
            try {
                com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx);
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest = db.getSovereignProfileManifest(senderId);
                if (manifest != null) {
                    preferUsername = manifest.showUsernameInGroup;
                    if (username == null || username.trim().isEmpty()) {
                        username = manifest.username;
                    }
                    if (manifest.displayName != null && !manifest.displayName.trim().isEmpty() && !com.m3.veil.manager.VeilFeatureManager.isShadowVeilOrGeneric(manifest.displayName)) {
                        profileName = manifest.displayName.trim();
                    }
                }
            } catch (Throwable ignored) {}
        }

        // According to user privacy setting: if user configured to show username -> show @username, otherwise show profile name
        if (preferUsername && username != null && !username.trim().isEmpty()) {
            String clean = username.trim();
            return clean.startsWith("@") ? clean : "@" + clean;
        }

        if (profileName != null && !profileName.trim().isEmpty() && !com.m3.veil.manager.VeilFeatureManager.isShadowVeilOrGeneric(profileName)) {
            return profileName.trim();
        }
        if (senderId != null && senderId.length() >= 4) {
            return "Peer #" + senderId.substring(0, 4);
        }
        return "Peer";
    }

    /**
     * Styles the quoted reply container for both Sent and Received bubbles across Light and Dark modes:
     * - Removes fixed minWidth constraints so short or long usernames/texts size dynamically
     * - Renders a guaranteed full-height 4.5dp vertical accent bar on the left edge + high-contrast card well
     *   so the sender/receiver reply bubble never looks faded or loses its vertical line.
     */
    private void styleReplyQuoteCard(
            View replyQuoteContainer,
            TextView replyQuoteName,
            TextView replyQuoteText,
            boolean isOutgoing,
            boolean isDarkBubble) {
        if (replyQuoteContainer == null) return;
        Context ctx = replyQuoteContainer.getContext();
        final float dp = ctx.getResources().getDisplayMetrics().density;
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int maxQuoteTextW = Math.round(screenW * 0.65f);

        replyQuoteContainer.setMinimumWidth(0);
        if (replyQuoteContainer.getLayoutParams() != null
                && replyQuoteContainer.getLayoutParams().width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            replyQuoteContainer.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        View oldStripe = replyQuoteContainer.findViewById(R.id.replyQuoteStripe);
        if (oldStripe != null) {
            oldStripe.setVisibility(View.GONE);
        }
        replyQuoteContainer.setPadding(
                Math.round(13f * dp),
                Math.round(6f * dp),
                Math.round(10f * dp),
                Math.round(6f * dp));

        if (replyQuoteName != null) {
            if (replyQuoteName.getParent() instanceof View) {
                ((View) replyQuoteName.getParent()).setMinimumWidth(0);
            }
            replyQuoteName.setMinimumWidth(0);
            replyQuoteName.setMaxWidth(maxQuoteTextW);
        }
        if (replyQuoteText != null) {
            replyQuoteText.setMinimumWidth(0);
            replyQuoteText.setMaxWidth(maxQuoteTextW);
        }

        final int cardFillColor;
        final int cardStrokeColor;
        final int stripeColor;
        final int quoteTextColor;

        if (isOutgoing) {
            if (isDarkBubble) {
                // Dark Sent Bubble (e.g. #2E2A72 or custom dark color): deep high-contrast well + electric cyan accent
                cardFillColor = 0x52060913;
                cardStrokeColor = 0x33FFFFFF;
                stripeColor = 0xFF38BDF8;
                quoteTextColor = 0xF2F8FAFC;
            } else {
                // Light Sent Bubble (e.g. #E0E7FE): crisp bright white inset card + royal indigo accent
                cardFillColor = 0xE6FFFFFF;
                cardStrokeColor = 0x384338CA;
                stripeColor = 0xFF4338CA;
                quoteTextColor = 0xFF1E293B;
            }
        } else {
            if (isDarkBubble) {
                // Dark Received Bubble (#1E293B): deep obsidian inset well + vibrant sky-indigo accent
                cardFillColor = 0x54090D16;
                cardStrokeColor = 0x33818CF8;
                stripeColor = 0xFF818CF8;
                quoteTextColor = 0xF0F1F5F9;
            } else {
                // Light Received Bubble (#FFFFFF): solid soft indigo-slate well + royal indigo accent
                cardFillColor = 0xFFEEF2FF;
                cardStrokeColor = 0x2E4F46E5;
                stripeColor = 0xFF4F46E5;
                quoteTextColor = 0xFF1E293B;
            }
        }

        if (replyQuoteName != null) {
            replyQuoteName.setTextColor(stripeColor);
        }
        if (replyQuoteText != null) {
            replyQuoteText.setTextColor(quoteTextColor);
        }

        android.graphics.drawable.Drawable quoteCardDrawable = new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Paint stripePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path clipPath = new android.graphics.Path();
            private final android.graphics.RectF rectF = new android.graphics.RectF();
            {
                fillPaint.setStyle(android.graphics.Paint.Style.FILL);
                fillPaint.setColor(cardFillColor);
                stripePaint.setStyle(android.graphics.Paint.Style.FILL);
                stripePaint.setColor(stripeColor);
                strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
                strokePaint.setStrokeWidth(Math.max(1f, 0.85f * dp));
                strokePaint.setColor(cardStrokeColor);
            }

            @Override
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;
                float radius = 8f * dp;
                float stripeW = 4.5f * dp;
                rectF.set(b.left, b.top, b.right, b.bottom);
                clipPath.reset();
                clipPath.addRoundRect(rectF, radius, radius, android.graphics.Path.Direction.CW);

                int save = canvas.save();
                canvas.clipPath(clipPath);
                canvas.drawRect(rectF, fillPaint);
                canvas.drawRect(b.left, b.top, b.left + stripeW, b.bottom, stripePaint);
                canvas.restoreToCount(save);

                float halfStroke = strokePaint.getStrokeWidth() * 0.5f;
                rectF.set(b.left + halfStroke, b.top + halfStroke, b.right - halfStroke, b.bottom - halfStroke);
                canvas.drawRoundRect(rectF, radius, radius, strokePaint);
            }

            @Override
            public void setAlpha(int alpha) {
                fillPaint.setAlpha(alpha);
                stripePaint.setAlpha(alpha);
                strokePaint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                fillPaint.setColorFilter(colorFilter);
                stripePaint.setColorFilter(colorFilter);
                strokePaint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
        replyQuoteContainer.setBackground(quoteCardDrawable);
    }

    public boolean isSenderMe(String senderId, Message msg) {
        boolean isGroupMsg = msg != null && msg.getChatId() != null
                && (msg.getChatId().startsWith("group_") || msg.getChatId().startsWith("channel_") || msg.getChatId().startsWith("lounge_"));
        if (!isGroupMsg && recipientId != null && !recipientId.isEmpty()) {
            if (recipientId.equals(senderId)) return false;
            if (msg != null && recipientId.equals(msg.getReceiverId())) return true;
        }
        if (senderId != null && !senderId.isEmpty()) {
            if (currentUserId != null && currentUserId.equals(senderId)) return true;
            if (com.m3.veil.manager.AuthManager.getInstance().isMyUserId(com.m3.veil.VeilApplication.getInstance(), senderId)) return true;
        }
        return false;
    }
    private boolean isTyping = false;
    private int lastAnimatedPosition = -1;

    public interface OnMessageLongClickListener {
        void onMessageLongClick(Message message, View anchorView);
    }
    
    public interface OnReactionClickListener {
        void onReactionClick(Message message);
    }

    public interface OnReplyClickListener {
        void onReplyClick(String messageId);
    }

    public interface OnPollVoteClickListener {
        void onPollVoteClick(Message message);
    }

    public interface OnPollOptionVoteListener {
        void onPollOptionClicked(Message message, int optionIndex);
        void onViewPollVotesClicked(Message message);
    }

    public interface OnBlinkOpenClickListener {
        void onBlinkOpenClick(Message message);
    }

    public interface OnDoubleTapReactListener {
        void onDoubleTapReact(Message message);
    }

    public interface OnCallMessageClickListener {
        void onCallMessageClick(Message message);
    }

    public interface OnMediaDownloadClickListener {
        void onMediaDownloadClick(Message message);
        void onMediaCancelClick(Message message);
        void onMediaRetryClick(Message message);
    }

    public interface OnQuickForwardClickListener {
        void onQuickForwardClick(Message message);
    }

    public static final int STATE_IDLE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_RETRY = 2;
    public static final int STATE_DOWNLOADED = 3;

    private final java.util.Map<String, Integer> downloadStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> downloadProgresses = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> autoTriggeredVoiceDownloads = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile String pendingAutoPlayMessageId = null;

    private OnMessageLongClickListener longClickListener;
    private OnReactionClickListener reactionClickListener;
    private OnReplyClickListener replyClickListener;
    private OnPollVoteClickListener pollVoteClickListener;
    private OnPollOptionVoteListener pollOptionVoteListener;
    private OnBlinkOpenClickListener blinkOpenClickListener;
    private OnDoubleTapReactListener doubleTapReactListener;
    private OnCallMessageClickListener callMessageClickListener;
    private OnMediaDownloadClickListener mediaDownloadClickListener;
    private OnQuickForwardClickListener quickForwardClickListener;

    public void setOnMediaDownloadClickListener(OnMediaDownloadClickListener listener) {
        this.mediaDownloadClickListener = listener;
    }

    public void setOnQuickForwardClickListener(OnQuickForwardClickListener listener) {
        this.quickForwardClickListener = listener;
    }

    public boolean isAutoTriggeredVoiceDownload(String messageId) {
        return messageId != null && autoTriggeredVoiceDownloads.contains(messageId);
    }

    public void clearAutoTriggeredVoiceDownload(String messageId) {
        if (messageId != null) autoTriggeredVoiceDownloads.remove(messageId);
    }

    public void setMediaDownloadState(String messageId, int state, int progress) {
        if (messageId == null) return;
        downloadStates.put(messageId, state);
        if (progress >= 0) downloadProgresses.put(messageId, progress);
        notifyItemChangedByMessageId(messageId);
        if (state == STATE_DOWNLOADED && messageId.equals(pendingAutoPlayMessageId)) {
            pendingAutoPlayMessageId = null;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (messages == null) return;
                for (int i = 0; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    if (m != null && messageId.equals(m.getId()) && ("voice".equals(m.getType()) || "audio".equals(m.getType()))) {
                        Context ctx = com.m3.veil.VeilApplication.getInstance();
                        if (ctx != null) {
                            VoicePlayerManager.getInstance().playVoiceNote(ctx, m, null, currentUserId);
                        }
                        break;
                    }
                }
            });
        }
    }

    public int getMediaDownloadState(String messageId) {
        if (messageId == null) return STATE_IDLE;
        return downloadStates.getOrDefault(messageId, STATE_IDLE);
    }

    public void notifyItemChangedByMessageId(String messageId) {
        if (messageId == null || messages == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (int i = 0; i < messages.size(); i++) {
                if (messageId.equals(messages.get(i).getId())) {
                    notifyItemChanged(i + 1);
                    break;
                }
            }
        });
    }

    public void setOnReplyClickListener(OnReplyClickListener listener) {
        this.replyClickListener = listener;
    }

    public void setOnPollVoteClickListener(OnPollVoteClickListener listener) {
        this.pollVoteClickListener = listener;
    }

    public void setOnPollOptionVoteListener(OnPollOptionVoteListener listener) {
        this.pollOptionVoteListener = listener;
    }

    public void setOnBlinkOpenClickListener(OnBlinkOpenClickListener listener) {
        this.blinkOpenClickListener = listener;
    }

    public void setOnDoubleTapReactListener(OnDoubleTapReactListener listener) {
        this.doubleTapReactListener = listener;
    }

    public void setOnCallMessageClickListener(OnCallMessageClickListener listener) {
        this.callMessageClickListener = listener;
    }

    /**
     * Built-In On-Device Offline Message Translator (#46) wired directly into MessagesAdapter.
     */
    public void translateMessageInline(android.content.Context context, Message message, String targetLang) {
        if (context == null || message == null || message.getText() == null) return;
        com.m3.veil.utils.VeilLocalIntelligenceEngine.TranslationResult res =
                com.m3.veil.utils.VeilLocalIntelligenceEngine
                        .getInstance()
                        .translateOffline(message.getText(), targetLang != null ? targetLang : "en");
        message.setTranslatedText(res.translatedText);
        message.setTranslatedLang(res.targetLanguage);
        notifyDataSetChanged();
    }

    private static VeilAdvancedTextView.OnEntityClickListener createEntityClickListener(final Context context) {
        return new VeilAdvancedTextView.OnEntityClickListener() {
            @Override
            public void onUrlClick(String url) {
                if (url == null || url.isEmpty() || context == null) return;
                try {
                    String fullUrl = url;
                    if (!fullUrl.startsWith("http://") && !fullUrl.startsWith("https://") && !fullUrl.startsWith("ftp://")) {
                        fullUrl = "https://" + fullUrl;
                    }
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(context, "Could not open link: " + url, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onMentionClick(String rawUsername) {
                if (rawUsername == null || rawUsername.trim().isEmpty() || context == null) return;
                String clean = rawUsername.trim();
                while (clean.startsWith("@")) clean = clean.substring(1).trim();
                if (clean.isEmpty()) return;

                // 1. Validate username format (3-32 chars alphanumeric and underscores)
                if (clean.length() < 3 || clean.length() > 32 || !clean.matches("^[a-zA-Z0-9_]{3,32}$")) {
                    Toast.makeText(context, "Invalid username: @" + clean, Toast.LENGTH_SHORT).show();
                    return;
                }

                String currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(context);
                if (currentUserId == null || currentUserId.isEmpty()) {
                    currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(context).getCurrentUserId();
                }
                if (currentUserId == null || currentUserId.isEmpty()) currentUserId = "veil_anonymous";

                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest myProfile =
                        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getLocalProfile(context);
                if (myProfile != null && myProfile.username != null && myProfile.username.equalsIgnoreCase(clean)) {
                    Toast.makeText(context, "This is your own profile (@" + clean + ")", Toast.LENGTH_SHORT).show();
                    return;
                }

                final String targetHandle = clean;
                final String myUid = currentUserId;

                // 2. Query local Sovereign manifest repository
                com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(context);
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                        db.getSovereignProfileManifestByUsername(targetHandle);

                if (manifest != null && manifest.uid != null && !manifest.uid.isEmpty()) {
                    openChatWithPeer(context, myUid, manifest.uid, manifest.displayName != null ? manifest.displayName : "@" + manifest.username, manifest.avatarBase64);
                    return;
                }

                // 3. Search in local chats
                List<com.m3.veil.model.Chat> allChats = db.getAllChats();
                if (allChats != null) {
                    for (com.m3.veil.model.Chat c : allChats) {
                        if (c != null && !c.isGroup() && c.getName() != null) {
                            String cName = c.getName().trim();
                            if (cName.equalsIgnoreCase("@" + targetHandle) || cName.equalsIgnoreCase(targetHandle)) {
                                String peerId = null;
                                if (c.getParticipants() != null) {
                                    for (String p : c.getParticipants()) {
                                        if (p != null && !p.equals(myUid)) {
                                            peerId = p;
                                            break;
                                        }
                                    }
                                }
                                if (peerId == null && c.getId() != null) {
                                    String[] parts = c.getId().split("_");
                                    if (parts.length == 2) {
                                        peerId = parts[0].equals(myUid) ? parts[1] : parts[0];
                                    }
                                }
                                openChatWithPeer(context, myUid, peerId != null ? peerId : "veil_peer_" + targetHandle.toLowerCase(java.util.Locale.ROOT), c.getName(), c.getAvatar());
                                return;
                            }
                        }
                    }
                }

                // 4. Asynchronously search in Sovereign Directory Engine / Bloom Filter
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(context)
                        .searchUsersByUsername(targetHandle, users -> {
                            if (users != null && !users.isEmpty()) {
                                com.m3.veil.model.User matchedUser = null;
                                for (com.m3.veil.model.User u : users) {
                                    if (u != null && u.getUsername() != null && u.getUsername().equalsIgnoreCase(targetHandle)) {
                                        matchedUser = u;
                                        break;
                                    }
                                }
                                if (matchedUser == null) matchedUser = users.get(0);
                                final com.m3.veil.model.User finalU = matchedUser;
                                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    String targetUid = finalU.getUserId() != null ? finalU.getUserId() : ("veil_peer_" + targetHandle.toLowerCase(java.util.Locale.ROOT));
                                    String title = finalU.getDisplayName() != null ? finalU.getDisplayName() : ("@" + targetHandle);
                                    openChatWithPeer(context, myUid, targetUid, title, finalU.getPhotoUrl());
                                });
                            } else {
                                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    Toast.makeText(context, "User @" + targetHandle + " not found", Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
            }

            @Override
            public void onPhoneClick(String phoneNumber) {
                if (phoneNumber == null || phoneNumber.isEmpty() || context == null) return;
                try {
                    String sanitized = phoneNumber.replaceAll("[^0-9+]", "");
                    Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + sanitized));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(context, "Could not dial: " + phoneNumber, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onHashtagClick(String hashtag) {
                if (hashtag == null || hashtag.isEmpty() || context == null) return;
                if (context instanceof ChatActivity) {
                    ((ChatActivity) context).searchForTextInChat("#" + hashtag);
                } else {
                    Toast.makeText(context, "Hashtag: #" + hashtag, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onEmailClick(String email) {
                if (email == null || email.isEmpty() || context == null) return;
                try {
                    Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(context, "Could not email: " + email, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onCashtagClick(String cashtag) {
                if (cashtag == null || cashtag.isEmpty() || context == null) return;
                try {
                    String quoteUrl = "https://www.google.com/finance/quote/" + cashtag;
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(quoteUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(context, "Stock / Crypto: $" + cashtag, Toast.LENGTH_SHORT).show();
                }
            }
        };
    }

    private static void openChatWithPeer(Context context, String currentUserId, String peerId, String peerTitle, String avatar) {
        if (context == null || peerId == null || peerId.isEmpty()) return;
        List<String> ids = java.util.Arrays.asList(currentUserId, peerId);
        java.util.Collections.sort(ids);
        String chatId = ids.get(0) + "_" + ids.get(1);

        com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(context);
        com.m3.veil.model.Chat existing = db.getChat(chatId);
        if (existing == null) {
            com.m3.veil.model.Chat newChat = new com.m3.veil.model.Chat(
                    chatId,
                    peerTitle != null ? peerTitle : "Peer",
                    "",
                    com.google.firebase.Timestamp.now(),
                    avatar,
                    0,
                    false,
                    false,
                    "direct",
                    ids
            );
            db.saveOrUpdateChat(newChat);
        }

        Intent intent = new Intent(context, com.m3.veil.ui.chat.ChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("recipientId", peerId);
        intent.putExtra("recipientName", peerTitle);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private final java.util.Set<String> revealedSpoilers = new java.util.HashSet<>();

    private static final java.util.regex.Pattern PATTERN_SPOILER = java.util.regex.Pattern.compile("\\|\\|([^|]+)\\|\\|");
    private static final java.util.regex.Pattern PATTERN_BOLD = java.util.regex.Pattern.compile("\\*([^*]+)\\*");
    private static final java.util.regex.Pattern PATTERN_ITALIC = java.util.regex.Pattern.compile("_([^_]+)_");
    private static final java.util.regex.Pattern PATTERN_STRIKE = java.util.regex.Pattern.compile("~([^~]+)~");
    private static final java.util.regex.Pattern PATTERN_CODE = java.util.regex.Pattern.compile("`([^`]+)`");
    private static final android.util.LruCache<String, CharSequence> RICH_TEXT_CACHE = new android.util.LruCache<>(400);

    private final java.util.Calendar cal1 = java.util.Calendar.getInstance();
    private final java.util.Calendar cal2 = java.util.Calendar.getInstance();
    private final java.util.Calendar nowCal = java.util.Calendar.getInstance();
    private final SimpleDateFormat dateHeaderFormat = new SimpleDateFormat("dd MMMM yyyy", Locale.getDefault());

    public void skipInitialAnimations() {
        this.lastAnimatedPosition = messages != null ? messages.size() + 2 : Integer.MAX_VALUE;
    }

    private CharSequence formatRichTextAndPoll(android.content.Context context, Message message) {
        if (message == null) return "";
        if (message.isBlink()) {
            if (message.isBlinkViewed()) {
                return "⚡ Blink message expired (Opened)";
            }
            boolean isMine = message.getSenderId() != null && message.getSenderId().equals(currentUserId);
            return isMine ? "⚡ Blink (View-Once) Sent" : "⚡ Blink Message — Tap to view once";
        }
        if ("poll".equals(message.getType()) && message.getPollJson() != null) {
            try {
                org.json.JSONObject poll = new org.json.JSONObject(message.getPollJson());
                String q = poll.optString("question", message.getText());
                org.json.JSONArray opts = poll.optJSONArray("options");
                StringBuilder sb = new StringBuilder("📊 <b>").append(q).append("</b><br/>");
                if (opts != null) {
                    for (int i = 0; i < opts.length(); i++) {
                        org.json.JSONObject o = opts.getJSONObject(i);
                        sb.append("<br/>• ").append(o.optString("text"))
                          .append("  <b>(").append(o.optInt("votes", 0)).append(")</b>");
                    }
                }
                sb.append("<br/><small><i>Tap to cast anonymous P2P vote</i></small>");
                return androidx.core.text.HtmlCompat.fromHtml(sb.toString(), androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT);
            } catch (Exception ignored) {}
        }
        String raw = message.getText() != null ? message.getText() : "";
        boolean hasTranslation = message.getTranslatedText() != null && !message.getTranslatedText().isEmpty();
        boolean hasSpoiler = raw.contains("||") && raw.indexOf("||") != raw.lastIndexOf("||");
        boolean hasMarkdown = raw.indexOf('*') >= 0 || raw.indexOf('_') >= 0 || raw.indexOf('~') >= 0 || raw.indexOf('`') >= 0;

        // 0ms fast-path for standard plain text messages (no regex or HTML parsing needed)
        if (!hasSpoiler && !hasMarkdown && !hasTranslation) {
            return applySearchHighlightSpans(raw, message);
        }

        boolean isRevealed = message.getId() != null && revealedSpoilers.contains(message.getId());
        String cacheKey = (message.getId() != null ? message.getId() : "") + ":" + raw.hashCode() + ":" + isRevealed
                + ":" + (hasTranslation ? message.getTranslatedText().hashCode() : 0);
        CharSequence cachedFormatted = RICH_TEXT_CACHE.get(cacheKey);
        if (cachedFormatted != null) {
            return applySearchHighlightSpans(cachedFormatted, message);
        }

        String processedRaw = raw;
        if (hasSpoiler && !isRevealed) {
            processedRaw = PATTERN_SPOILER.matcher(raw).replaceAll("▒▒▒▒▒▒ [🕵️ Spoiler • Tap to reveal]");
        } else if (hasSpoiler) {
            processedRaw = PATTERN_SPOILER.matcher(raw).replaceAll("$1");
        }

        String html = processedRaw
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (hasMarkdown) {
            html = PATTERN_BOLD.matcher(html).replaceAll("<b>$1</b>");
            html = PATTERN_ITALIC.matcher(html).replaceAll("<i>$1</i>");
            html = PATTERN_STRIKE.matcher(html).replaceAll("<s>$1</s>");
            html = PATTERN_CODE.matcher(html).replaceAll("<tt>$1</tt>");
        }
        html = html.replace("\n", "<br/>");

        if (hasTranslation) {
            String transEscaped = message.getTranslatedText()
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>");
            String lang = message.getTranslatedLang() != null ? message.getTranslatedLang().toUpperCase(Locale.ROOT) : "OFFLINE";
            boolean isNight = context != null && (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            String headerHex = isNight ? "#818CF8" : "#4F46E5";
            String bodyHex = isNight ? "#CBD5E1" : "#334155";
            html += "<br/><br/><small><font color='" + headerHex + "'>🌐 <b>[Translated — " + lang + "]</b></font></small><br/><font color='" + bodyHex + "'><i>" + transEscaped + "</i></font>";
            CharSequence res = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT);
            RICH_TEXT_CACHE.put(cacheKey, res);
            return applySearchHighlightSpans(res, message);
        }

        if (hasMarkdown) {
            CharSequence res = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT);
            RICH_TEXT_CACHE.put(cacheKey, res);
            return applySearchHighlightSpans(res, message);
        }
        RICH_TEXT_CACHE.put(cacheKey, processedRaw);
        return applySearchHighlightSpans(processedRaw, message);
    }

    private int cachedCustomBubbleColor = 0;
    private float cachedTextSizeSp = 15.5f;
    private float cachedCornerRadiusDp = 18.0f;

    public void refreshAppearancePreferences() {
        PREINFLATED_SENT_VIEWS.clear();
        PREINFLATED_RECEIVED_VIEWS.clear();
        com.m3.veil.VeilApplication app = com.m3.veil.VeilApplication.getInstance();
        if (app != null) {
            try {
                android.content.SharedPreferences appPrefs = app.getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
                String accent = appPrefs.getString("chat_bubble_accent", null);
                if (accent != null && !accent.isEmpty()) {
                    this.cachedCustomBubbleColor = android.graphics.Color.parseColor(accent);
                } else {
                    this.cachedCustomBubbleColor = 0;
                }
                this.cachedTextSizeSp = appPrefs.getFloat("chat_text_size_sp", 15.5f);
                this.cachedCornerRadiusDp = appPrefs.getFloat("chat_bubble_corner_radius_dp", 18.0f);
            } catch (Exception ignored) {}
        }
        notifyDataSetChanged();
    }
    private final java.util.Map<String, Message> messageByIdCache = new java.util.HashMap<>();
    private int lastIndexedSize = -1;

    public MessagesAdapter(List<Message> messages, OnMessageLongClickListener longClickListener, OnReactionClickListener reactionClickListener) {
        this.messages = messages;
        this.lastAnimatedPosition = messages != null ? messages.size() + 1 : 0;
        com.m3.veil.VeilApplication app = com.m3.veil.VeilApplication.getInstance();
        this.currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(app);
        if (this.currentUserId == null || this.currentUserId.isEmpty()) {
            this.currentUserId = FirebaseAuth.getInstance().getCurrentUser() != null 
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        }
        if (app != null) {
            try {
                android.content.SharedPreferences appPrefs = app.getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
                String accent = appPrefs.getString("chat_bubble_accent", null);
                if (accent != null && !accent.isEmpty()) {
                    this.cachedCustomBubbleColor = android.graphics.Color.parseColor(accent);
                }
                this.cachedTextSizeSp = appPrefs.getFloat("chat_text_size_sp", 15.5f);
            } catch (Exception ignored) {}
        }
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        this.longClickListener = longClickListener;
        this.reactionClickListener = reactionClickListener;
        VoicePlayerManager.getInstance().setOnVoiceCompletionQueueListener(this::handleVoiceNoteCompletedAutoPlayNext);
    }

    private void handleVoiceNoteCompletedAutoPlayNext(String completedMessageId) {
        if (completedMessageId == null || messages == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (messages == null) return;
            int completedIdx = -1;
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m != null && completedMessageId.equals(m.getId())) {
                    completedIdx = i;
                    break;
                }
            }
            if (completedIdx < 0 || completedIdx + 1 >= messages.size()) return;
            Message nextMsg = messages.get(completedIdx + 1);
            if (nextMsg == null || (!"voice".equals(nextMsg.getType()) && !"audio".equals(nextMsg.getType())) || nextMsg.isBlink()) {
                return;
            }
            Context ctx = com.m3.veil.VeilApplication.getInstance();
            if (ctx == null) return;

            File localAudio = FileMediaManager.getDecryptedFile(ctx, nextMsg, ".m4a");
            boolean hasLocal = (localAudio != null && localAudio.exists() && localAudio.length() > 0)
                    || (nextMsg.getLocalFilePath() != null && new File(nextMsg.getLocalFilePath()).exists());
            if (!hasLocal) {
                File inlineAudio = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(ctx, nextMsg.getChatId(), nextMsg);
                if (inlineAudio != null && inlineAudio.exists() && inlineAudio.length() > 0) {
                    hasLocal = true;
                }
            }
            if (hasLocal) {
                VoicePlayerManager.getInstance().playVoiceNote(ctx, nextMsg, null, currentUserId);
            } else if (nextMsg.getId() != null) {
                pendingAutoPlayMessageId = nextMsg.getId();
                if (mediaDownloadClickListener != null) {
                    mediaDownloadClickListener.onMediaDownloadClick(nextMsg);
                }
            }
        });
    }

    public void setTyping(boolean typing) {
        if (this.isTyping != typing) {
            this.isTyping = typing;
            if (typing) {
                notifyItemInserted(getItemCount() - 1);
            } else {
                notifyItemRemoved(getItemCount());
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return VIEW_TYPE_NOTICE;
        if (isTyping && position == getItemCount() - 1) return VIEW_TYPE_TYPING;
        
        int msgIndex = position - 1;
        if (msgIndex < 0 || msgIndex >= messages.size()) {
            return VIEW_TYPE_SENT;
        }
        Message msg = messages.get(msgIndex);
        if (msg == null) return VIEW_TYPE_SENT;
        return isSenderMe(msg.getSenderId(), msg) ? VIEW_TYPE_SENT : VIEW_TYPE_RECEIVED;
    }

    private static final int MAX_PREINFLATED_POOL_SIZE = 16;
    private static final java.util.concurrent.ConcurrentLinkedQueue<View> PREINFLATED_SENT_VIEWS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.ConcurrentLinkedQueue<View> PREINFLATED_RECEIVED_VIEWS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicBoolean IS_PREWARMING = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.ExecutorService PREWARM_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VeilViewPrewarmer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public static void prewarmViewPool(final Context context) {
        PREINFLATED_SENT_VIEWS.clear();
        PREINFLATED_RECEIVED_VIEWS.clear();
    }

    private static View obtainPreinflatedView(@NonNull ViewGroup parent, int layoutResId, java.util.concurrent.ConcurrentLinkedQueue<View> pool) {
        Context activityContext = parent.getContext();
        return LayoutInflater.from(activityContext).inflate(layoutResId, parent, false);
    }

    private static void styleDocumentBubbleCard(
            Context ctx,
            View docContainer,
            View iconContainer,
            TextView extBadge,
            TextView fileNameView,
            TextView fileSizeView,
            ImageView actionIcon,
            ProgressBar progressBar,
            String ext,
            boolean isDarkBubble,
            boolean isOutgoing) {
        if (ctx == null || docContainer == null) return;
        float dp = ctx.getResources().getDisplayMetrics().density;

        if (docContainer instanceof ViewGroup && ((ViewGroup) docContainer).getChildCount() > 0) {
            View innerCard = ((ViewGroup) docContainer).getChildAt(0);
            if (innerCard != null) {
                android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                cardBg.setCornerRadius(12f * dp);
                if (isDarkBubble) {
                    cardBg.setColor(isOutgoing ? 0x29000000 : 0xFF162032);
                    cardBg.setStroke(Math.max(1, (int) dp), isOutgoing ? 0x33FFFFFF : 0xFF26354D);
                } else {
                    cardBg.setColor(isOutgoing ? 0xCCFFFFFF : 0xFFF1F5F9);
                    cardBg.setStroke(Math.max(1, (int) dp), isOutgoing ? 0x264F46E5 : 0xFFCBD5E1);
                }
                innerCard.setBackground(cardBg);
            }
        }

        int badgeColor = FileMediaManager.getDocumentBadgeColor(ext);
        if (iconContainer != null) {
            android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
            badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            badgeBg.setCornerRadius(10f * dp);
            badgeBg.setColor(badgeColor);
            iconContainer.setBackgroundTintList(null);
            iconContainer.setBackground(badgeBg);
            if (iconContainer instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) iconContainer;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child instanceof ImageView) {
                        ((ImageView) child).setColorFilter(android.graphics.Color.WHITE);
                    }
                }
            }
        }
        if (extBadge != null) {
            extBadge.setTextColor(android.graphics.Color.WHITE);
        }

        int primaryTextColor = isDarkBubble ? 0xFFF8FAFC : 0xFF0F172A;
        int secondaryTextColor = isDarkBubble ? 0xCCE2E8F0 : 0xFF475569;
        if (fileNameView != null) {
            fileNameView.setTextColor(primaryTextColor);
        }
        if (fileSizeView != null) {
            fileSizeView.setTextColor(secondaryTextColor);
        }
        if (progressBar != null) {
            int progressColor = isDarkBubble ? 0xFF38BDF8 : badgeColor;
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(progressColor));
            progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    isDarkBubble ? 0x33FFFFFF : 0x260F172A));
        }
        if (actionIcon != null) {
            android.graphics.drawable.GradientDrawable actionBg = new android.graphics.drawable.GradientDrawable();
            actionBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            actionBg.setColor(isDarkBubble ? 0x22FFFFFF : 0x140F172A);
            actionIcon.setBackground(actionBg);
        }
    }

    public static void detachContextFromView(View view) {
        if (view == null) return;
        Object tag = view.getTag(R.id.dateHeaderContainer);
        if (tag instanceof android.content.MutableContextWrapper) {
            Context appCtx = view.getContext().getApplicationContext();
            if (appCtx != null) {
                ((android.content.MutableContextWrapper) tag).setBaseContext(appCtx);
            }
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_NOTICE) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_e2ee_notice, parent, false);
            return new NoticeViewHolder(view);
        } else if (viewType == VIEW_TYPE_TYPING) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_typing_indicator, parent, false);
            return new TypingIndicatorViewHolder(view);
        } else if (viewType == VIEW_TYPE_SENT) {
            View view = obtainPreinflatedView(parent, R.layout.item_message_sent, PREINFLATED_SENT_VIEWS);
            return new SentMessageViewHolder(view);
        } else {
            View view = obtainPreinflatedView(parent, R.layout.item_message_received, PREINFLATED_RECEIVED_VIEWS);
            return new ReceivedMessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position == 0 || holder instanceof NoticeViewHolder) {
            return;
        }
        
        if (holder instanceof TypingIndicatorViewHolder) {
            ((TypingIndicatorViewHolder) holder).startAnimation();
            return;
        }

        int msgIndex = position - 1;
        if (msgIndex < 0 || msgIndex >= messages.size()) {
            return;
        }
        Message message = messages.get(msgIndex);
        String time = "";
        if (message.getTimestamp() != null) {
            time = timeFormat.format(message.getTimestamp().toDate());
        }

        boolean showDate = shouldShowDateHeader(msgIndex);
        String dateLabel = showDate ? formatDateHeader(message.getTimestamp()) : "";

        String displayTime = time;
        if (message.isEdited()) {
            displayTime = "edited • " + displayTime;
        }
        if (message.getVoiceFilter() != null && !"normal".equals(message.getVoiceFilter()) && "voice".equals(message.getType())) {
            displayTime = "🎭 " + message.getVoiceFilter() + " • " + displayTime;
        }

        if (holder instanceof SentMessageViewHolder) {
            ((SentMessageViewHolder) holder).bind(message, displayTime, showDate, dateLabel);
        } else if (holder instanceof ReceivedMessageViewHolder) {
            ((ReceivedMessageViewHolder) holder).bind(message, displayTime, showDate, dateLabel);
        }

        if (position > lastAnimatedPosition) {
            setAnimation(holder.itemView);
            lastAnimatedPosition = position;
        } else {
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
        }
    }

    private boolean shouldShowDateHeader(int msgIndex) {
        if (msgIndex < 0 || msgIndex >= messages.size()) return false;
        if (msgIndex == 0) return true;

        Message current = messages.get(msgIndex);
        Message previous = messages.get(msgIndex - 1);

        if (current.getTimestamp() == null || previous.getTimestamp() == null) return false;

        cal1.setTimeInMillis(current.getTimestamp().toDate().getTime());
        cal2.setTimeInMillis(previous.getTimestamp().toDate().getTime());

        return cal1.get(java.util.Calendar.YEAR) != cal2.get(java.util.Calendar.YEAR)
                || cal1.get(java.util.Calendar.DAY_OF_YEAR) != cal2.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private String formatDateHeader(com.google.firebase.Timestamp timestamp) {
        if (timestamp == null) return "";
        Date msgDate = timestamp.toDate();
        cal1.setTimeInMillis(msgDate.getTime());
        nowCal.setTimeInMillis(System.currentTimeMillis());

        if (nowCal.get(java.util.Calendar.YEAR) == cal1.get(java.util.Calendar.YEAR)
                && nowCal.get(java.util.Calendar.DAY_OF_YEAR) == cal1.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "TODAY";
        }

        nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (nowCal.get(java.util.Calendar.YEAR) == cal1.get(java.util.Calendar.YEAR)
                && nowCal.get(java.util.Calendar.DAY_OF_YEAR) == cal1.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "YESTERDAY";
        }

        return dateHeaderFormat.format(msgDate).toUpperCase(Locale.getDefault());
    }

    private void setAnimation(View view) {
        view.setAlpha(0f);
        view.setTranslationY(14f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160)
                .start();
    }

    private int computeClusterPosition(int msgIndex) {
        if (messages == null || msgIndex < 0 || msgIndex >= messages.size()) {
            return com.m3.veil.ui.views.VeilMessageBubbleLayout.CLUSTER_SINGLE;
        }
        Message current = messages.get(msgIndex);
        if (current == null) return com.m3.veil.ui.views.VeilMessageBubbleLayout.CLUSTER_SINGLE;
        String currentSender = current.getSenderId();

        boolean prevSame = false;
        if (msgIndex > 0) {
            Message prev = messages.get(msgIndex - 1);
            if (prev != null && currentSender != null && currentSender.equals(prev.getSenderId())) {
                if (current.getTimestamp() != null && prev.getTimestamp() != null) {
                    long diff = Math.abs(current.getTimestamp().toDate().getTime() - prev.getTimestamp().toDate().getTime());
                    if (diff < 5 * 60 * 1000) {
                        prevSame = true;
                    }
                }
            }
        }

        boolean nextSame = false;
        if (msgIndex < messages.size() - 1) {
            Message next = messages.get(msgIndex + 1);
            if (next != null && currentSender != null && currentSender.equals(next.getSenderId())) {
                if (current.getTimestamp() != null && next.getTimestamp() != null) {
                    long diff = Math.abs(next.getTimestamp().toDate().getTime() - current.getTimestamp().toDate().getTime());
                    if (diff < 5 * 60 * 1000) {
                        nextSame = true;
                    }
                }
            }
        }

        if (prevSame && nextSame) {
            return com.m3.veil.ui.views.VeilMessageBubbleLayout.CLUSTER_MIDDLE;
        } else if (prevSame) {
            return com.m3.veil.ui.views.VeilMessageBubbleLayout.CLUSTER_LAST;
        } else if (nextSame) {
            return com.m3.veil.ui.views.VeilMessageBubbleLayout.CLUSTER_FIRST;
        } else {
            return com.m3.veil.ui.views.VeilMessageBubbleLayout.CLUSTER_SINGLE;
        }
    }

    @Override
    public int getItemCount() {
        return messages.size() + 1 + (isTyping ? 1 : 0);
    }

    public void updateMessages(List<Message> newMessages) {
        this.messages = newMessages;
        this.lastIndexedSize = -1;
        this.messageByIdCache.clear();
        this.lastAnimatedPosition = newMessages != null ? newMessages.size() + 1 : 0;
        notifyDataSetChanged();
    }
    
    private Message getMessageById(String id) {
        if (id == null || messages == null) return null;
        if (lastIndexedSize != messages.size()) {
            messageByIdCache.clear();
            for (int i = 0, n = messages.size(); i < n; i++) {
                Message m = messages.get(i);
                if (m != null && m.getId() != null) {
                    messageByIdCache.put(m.getId(), m);
                }
            }
            lastIndexedSize = messages.size();
        }
        return messageByIdCache.get(id);
    }

    static class NoticeViewHolder extends RecyclerView.ViewHolder {
        public NoticeViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private void updateStatusIcon(ImageView iconView, Message message) {
        updateStatusIcon(iconView, message, false);
    }

    private void updateStatusIcon(ImageView iconView, Message message, boolean isDarkBubble) {
        if (iconView == null || message == null) return;
        int sentColor = isDarkBubble
                ? 0xCCF8FAFC
                : ContextCompat.getColor(iconView.getContext(), R.color.message_status_sent);
        int readColor = isDarkBubble
                ? 0xFF38BDF8
                : ContextCompat.getColor(iconView.getContext(), R.color.message_status_read);
        if (message.isPending()) {
            iconView.setImageResource(R.drawable.ic_timer);
            iconView.setColorFilter(sentColor);
        } else if (message.isRead()) {
            iconView.setImageResource(R.drawable.ic_check_double);
            iconView.setColorFilter(readColor);
        } else if (message.isDelivered()) {
            iconView.setImageResource(R.drawable.ic_check_double);
            iconView.setColorFilter(sentColor);
        } else {
            iconView.setImageResource(R.drawable.ic_check);
            iconView.setColorFilter(sentColor);
        }
    }

    private void updateOverlayStatusIcon(ImageView iconView, Message message) {
        if (iconView == null || message == null) return;
        if (message.isPending()) {
            iconView.setImageResource(R.drawable.ic_timer);
            iconView.setColorFilter(0xFFFFFFFF);
        } else if (message.isRead()) {
            iconView.setImageResource(R.drawable.ic_check_double);
            iconView.setColorFilter(iconView.getContext().getResources().getColor(R.color.message_status_read));
        } else if (message.isDelivered()) {
            iconView.setImageResource(R.drawable.ic_check_double);
            iconView.setColorFilter(0xFFFFFFFF);
        } else {
            iconView.setImageResource(R.drawable.ic_check);
            iconView.setColorFilter(0xFFFFFFFF);
        }
    }

    private void bindBubbleInfoBadges(Message message, ImageView ivPinnedBadge, TextView tvStarredBadge, TextView tvVanishBadge, TextView tvEditedBadge) {
        if (ivPinnedBadge != null) {
            ivPinnedBadge.setVisibility(message != null && message.isPinned() ? View.VISIBLE : View.GONE);
        }
        if (tvStarredBadge != null) {
            tvStarredBadge.setVisibility(message != null && message.isStarred() ? View.VISIBLE : View.GONE);
        }
        if (tvVanishBadge != null) {
            boolean isExpiring = message != null && ((message.getExpiresAt() != null && message.getExpiresAt().toDate().getTime() > 0)
                    || message.getVanishTimer() > 0);
            tvVanishBadge.setVisibility(isExpiring ? View.VISIBLE : View.GONE);
        }
        if (tvEditedBadge != null) {
            tvEditedBadge.setVisibility(message != null && message.isEdited() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Styles the below-bubble info row (timestamp + status tick: clock, single, double, or blue read tick)
     * when displayed below a borderless/chromeless sticker or GIF, or resets it for standard bubbles.
     */
    /**
     * Styles the below-bubble info row (timestamp + status tick: clock, single, double, or blue/green read tick)
     * when displayed below a borderless/chromeless sticker, matching WhatsApp's floating pill (#D9FDD3 / #FFFFFF),
     * or resets it for standard bubbles.
     */
    private void applyChromelessBelowInfoStyle(
            View bubbleInfoContainer,
            TextView timeText,
            ImageView statusIcon,
            ImageView ivPinnedBadge,
            Message message,
            boolean isChromelessSticker,
            boolean isOutgoing,
            boolean isDarkBubble) {
        if (bubbleInfoContainer == null) return;
        float dp = bubbleInfoContainer.getContext().getResources().getDisplayMetrics().density;
        boolean isNight = com.m3.veil.ui.views.ChatMessageCell.resolveIsNightMode(bubbleInfoContainer.getContext());
        if (isChromelessSticker) {
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            pill.setCornerRadius(11f * dp);
            int pillBgColor;
            int pillTextColor;
            if (isOutgoing) {
                pillBgColor = isNight ? 0xFF005C4B : 0xFFD9FDD3;
                pillTextColor = isNight ? 0xB3FFFFFF : 0xFF667781;
            } else {
                pillBgColor = isNight ? 0xFF1F2C34 : 0xFFFFFFFF;
                pillTextColor = isNight ? 0x99FFFFFF : 0xFF667781;
            }
            pill.setColor(pillBgColor);
            bubbleInfoContainer.setBackground(pill);
            bubbleInfoContainer.setElevation(1f * dp);
            int padH = Math.round(7.5f * dp);
            int padV = Math.round(2.5f * dp);
            bubbleInfoContainer.setPadding(padH, padV, padH, padV);
            if (timeText != null) {
                timeText.setTextColor(pillTextColor);
            }
            if (ivPinnedBadge != null) {
                ivPinnedBadge.setColorFilter(pillTextColor);
            }
            if (isOutgoing && statusIcon != null && message != null) {
                statusIcon.setVisibility(View.VISIBLE);
                if (message.isPending()) {
                    statusIcon.setImageResource(R.drawable.ic_timer);
                    statusIcon.setColorFilter(pillTextColor);
                } else if (message.isRead()) {
                    statusIcon.setImageResource(R.drawable.ic_check_double);
                    statusIcon.setColorFilter(isNight ? 0xFF53BDEB : 0xFF25D366);
                } else if (message.isDelivered()) {
                    statusIcon.setImageResource(R.drawable.ic_check_double);
                    statusIcon.setColorFilter(pillTextColor);
                } else {
                    statusIcon.setImageResource(R.drawable.ic_check);
                    statusIcon.setColorFilter(pillTextColor);
                }
            }
        } else {
            bubbleInfoContainer.setBackground(null);
            bubbleInfoContainer.setElevation(0f);
            bubbleInfoContainer.setPadding(0, 0, 0, 0);
            if (isOutgoing && statusIcon != null && message != null && !"call".equals(message.getType())) {
                updateStatusIcon(statusIcon, message, isDarkBubble);
            }
        }
    }

    /**
     * Checks if a message is a standalone square built-in sticker eligible for WhatsApp 2-column side-by-side pairing.
     * Custom photo and video stickers (Portrait / Landscape / file-backed) always render singly at full aspect-ratio size.
     */
    private boolean isEligibleForStickerPairing(Message m) {
        if (m == null || "deleted".equals(m.getType()) || m.isBlink()) return false;
        if (!com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(m)) return false;
        if (m.getLocalFilePath() != null && !m.getLocalFilePath().trim().isEmpty()) {
            return false;
        }
        String text = m.getText() != null ? m.getText().trim() : "";
        if ("[sticker]".equalsIgnoreCase(text)) {
            return false;
        }
        return m.getReplyToMessageId() == null || m.getReplyToMessageId().isEmpty();
    }

    private boolean canPairConsecutiveStickers(Message prev, Message curr) {
        if (!isEligibleForStickerPairing(prev) || !isEligibleForStickerPairing(curr)) return false;
        String s1 = prev.getSenderId();
        String s2 = curr.getSenderId();
        if (s1 == null || !s1.equals(s2)) return false;
        if (prev.getTimestamp() == null || curr.getTimestamp() == null) return false;
        long diff = Math.abs(curr.getTimestamp().toDate().getTime() - prev.getTimestamp().toDate().getTime());
        return diff <= 120_000L;
    }

    /**
     * Computes the position of a sticker within a consecutive run of stickers from the same sender.
     * Even indices (0, 2, 4...) pair with (msgIndex + 1) if available; odd indices (1, 3, 5...) are collapsed
     * into their preceding partner row. Only runs of >= 2 stickers are paired side-by-side; single isolated
     * stickers or the trailing odd sticker in a run render singly with the circular forward button.
     */
    private int getStickerRunOffset(int msgIndex) {
        if (messages == null || msgIndex < 0 || msgIndex >= messages.size()) return -1;
        Message curr = messages.get(msgIndex);
        if (!isEligibleForStickerPairing(curr)) return -1;
        int runStart = msgIndex;
        while (runStart > 0 && canPairConsecutiveStickers(messages.get(runStart - 1), messages.get(runStart))) {
            runStart--;
        }
        return msgIndex - runStart;
    }

    private boolean isCollapsedPairedStickerChild(int msgIndex) {
        int offset = getStickerRunOffset(msgIndex);
        return offset > 0 && (offset % 2 == 1);
    }

    private Message getPairedSecondSticker(int msgIndex) {
        if (messages == null || msgIndex < 0 || msgIndex + 1 >= messages.size()) return null;
        int offset = getStickerRunOffset(msgIndex);
        if (offset < 0 || (offset % 2 != 0)) return null;
        Message curr = messages.get(msgIndex);
        Message next = messages.get(msgIndex + 1);
        if (canPairConsecutiveStickers(curr, next)) {
            return next;
        }
        return null;
    }

    /**
     * Renders a 2-column side-by-side sticker pair (sticker_second_Screenshot_9e6f15cb.jpg) when two
     * stickers are sent consecutively, or removes the pair overlay when rendering a single sticker.
     */
    private void bindPairedStickerOverlay(
            Context ctx,
            View imageCardView,
            Message firstSticker,
            Message secondSticker,
            boolean isOutgoing) {
        if (!(imageCardView instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) imageCardView;
        final String TAG_PAIR = "veil_sticker_pair_overlay";
        View existing = host.findViewWithTag(TAG_PAIR);
        if (secondSticker == null) {
            if (existing != null) host.removeView(existing);
            return;
        }
        if (existing != null) host.removeView(existing);

        float dp = ctx.getResources().getDisplayMetrics().density;
        boolean isNight = com.m3.veil.ui.views.ChatMessageCell.resolveIsNightMode(ctx);
        int gapPx = Math.round(10f * dp);
        int pillHeightPx = Math.round(24f * dp);

        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setTag(TAG_PAIR);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);
        row.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Message[] pair = new Message[]{firstSticker, secondSticker};
        int sumW = 0;
        int maxColH = 0;
        for (int i = 0; i < 2; i++) {
            final Message sMsg = pair[i];
            int[] rawDims = com.m3.veil.utils.VeilStickerGifEngine.computeStickerSizePx(ctx, sMsg);
            int cellW = Math.max(Math.round(96f * dp), Math.min(Math.round(148f * dp), Math.round(rawDims[0] * 0.78f)));
            int cellH = Math.max(Math.round(96f * dp), Math.min(Math.round(186f * dp), Math.round(rawDims[1] * 0.78f)));
            sumW += cellW;
            if (cellH + pillHeightPx > maxColH) {
                maxColH = cellH + pillHeightPx;
            }

            android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.END);
            android.widget.LinearLayout.LayoutParams colLp =
                    new android.widget.LinearLayout.LayoutParams(cellW, cellH + pillHeightPx);
            if (i == 1) colLp.leftMargin = gapPx;
            col.setLayoutParams(colLp);

            ImageView ivSticker = new ImageView(ctx);
            ivSticker.setLayoutParams(new android.widget.LinearLayout.LayoutParams(cellW, cellH));
            ivSticker.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            com.m3.veil.utils.VeilStickerGifEngine.bindStickerToImageView(ctx, sMsg, ivSticker);
            ivSticker.setOnClickListener(v -> {
                com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(ivSticker);
                ivSticker.animate().scaleX(1.16f).scaleY(1.16f).setDuration(140)
                        .withEndAction(() -> ivSticker.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                        .start();
            });
            ivSticker.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onMessageLongClick(sMsg, v);
                    return true;
                }
                return false;
            });
            col.addView(ivSticker);

            // WhatsApp floating timestamp pill below each sticker in the pair ("11:06 PM ✓✓")
            android.widget.LinearLayout pillLayout = new android.widget.LinearLayout(ctx);
            pillLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            pillLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
            pillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            pillBg.setCornerRadius(11f * dp);
            int bgColor = isOutgoing ? (isNight ? 0xFF005C4B : 0xFFD9FDD3) : (isNight ? 0xFF1F2C34 : 0xFFFFFFFF);
            int txtColor = isOutgoing ? (isNight ? 0xB3FFFFFF : 0xFF667781) : (isNight ? 0x99FFFFFF : 0xFF667781);
            pillBg.setColor(bgColor);
            pillLayout.setBackground(pillBg);
            pillLayout.setElevation(1f * dp);
            int padH = Math.round(7f * dp);
            int padV = Math.round(2f * dp);
            pillLayout.setPadding(padH, padV, padH, padV);
            android.widget.LinearLayout.LayoutParams pillLp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.topMargin = Math.round(2f * dp);
            pillLayout.setLayoutParams(pillLp);

            TextView tvTime = new TextView(ctx);
            String sTime = "";
            if (sMsg.getTimestamp() != null) {
                sTime = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(sMsg.getTimestamp().toDate());
            }
            tvTime.setText(sTime);
            tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            tvTime.setTextColor(txtColor);
            pillLayout.addView(tvTime);

            if (isOutgoing) {
                ImageView ivTick = new ImageView(ctx);
                android.widget.LinearLayout.LayoutParams tickLp = new android.widget.LinearLayout.LayoutParams(
                        Math.round(14f * dp), Math.round(14f * dp));
                tickLp.leftMargin = Math.round(3f * dp);
                ivTick.setLayoutParams(tickLp);
                if (sMsg.isPending()) {
                    ivTick.setImageResource(R.drawable.ic_timer);
                    ivTick.setColorFilter(txtColor);
                } else if (sMsg.isRead()) {
                    ivTick.setImageResource(R.drawable.ic_check_double);
                    ivTick.setColorFilter(isNight ? 0xFF53BDEB : 0xFF25D366);
                } else if (sMsg.isDelivered()) {
                    ivTick.setImageResource(R.drawable.ic_check_double);
                    ivTick.setColorFilter(txtColor);
                } else {
                    ivTick.setImageResource(R.drawable.ic_check);
                    ivTick.setColorFilter(txtColor);
                }
                pillLayout.addView(ivTick);
            }
            col.addView(pillLayout);
            row.addView(col);
        }

        host.addView(row);
        int totalW = sumW + gapPx;
        int totalH = maxColH + Math.round(4f * dp);
        com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, totalW, totalH);
    }

    /**
     * Styles the centered circular dark translucent "GIF" badge inside a WhatsApp GIF bubble
     * (matching the "KYA?" GIF in sticker_second_Screenshot_9e6f15cb.jpg).
     */
    private void styleWhatsAppCenteredGifBadge(TextView gifBadge) {
        if (gifBadge == null) return;
        float dp = gifBadge.getContext().getResources().getDisplayMetrics().density;
        int sizePx = Math.round(42f * dp);
        ViewGroup.LayoutParams lp = gifBadge.getLayoutParams();
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) lp;
            flp.width = sizePx;
            flp.height = sizePx;
            flp.gravity = android.view.Gravity.CENTER;
            flp.setMargins(0, 0, 0, 0);
            gifBadge.setLayoutParams(flp);
        }
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0x88000000);
        gifBadge.setBackground(circle);
        gifBadge.setGravity(android.view.Gravity.CENTER);
        gifBadge.setPadding(0, 0, 0, 0);
        gifBadge.setText("GIF");
        gifBadge.setTextColor(0xFFFFFFFF);
        gifBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
    }

    /**
     * Checks if a single message is eligible to be automatically grouped into a multi-photo/video
     * collage bubble when consecutive uncaptioned photos/videos are sent by the same sender.
     */
    private boolean isEligibleForAutoAlbum(Message m) {
        if (m == null || "deleted".equals(m.getType()) || m.isBlink()) return false;
        String t = m.getType();
        if (!"image".equals(t) && !"video".equals(t)) return false;
        if (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(m)
                || com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(m, null)) {
            return false;
        }
        if (m.getPollJson() != null && m.getPollJson().contains("\"album\":true")) {
            return false;
        }
        String txt = m.getText();
        if (txt != null && !txt.trim().isEmpty()
                && !txt.startsWith("[image")
                && !txt.startsWith("[video")
                && !txt.equals("[image message]")
                && !txt.equals("[video message]")) {
            return false;
        }
        return true;
    }

    private boolean canGroupInAutoAlbum(Message prev, Message curr) {
        if (!isEligibleForAutoAlbum(prev) || !isEligibleForAutoAlbum(curr)) return false;
        String s1 = prev.getSenderId();
        String s2 = curr.getSenderId();
        if (s1 == null || !s1.equals(s2)) return false;
        if (prev.getTimestamp() == null || curr.getTimestamp() == null) return false;
        long diff = Math.abs(curr.getTimestamp().toDate().getTime() - prev.getTimestamp().toDate().getTime());
        return diff <= 90_000L;
    }

    private int findMessageIndex(Message target) {
        if (target == null || messages == null) return -1;
        String id = target.getId();
        for (int i = 0, n = messages.size(); i < n; i++) {
            Message m = messages.get(i);
            if (m == target || (id != null && m != null && id.equals(m.getId()))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCollapsedAutoAlbumChild(int msgIndex) {
        if (messages == null || msgIndex <= 0 || msgIndex >= messages.size()) return false;
        Message curr = messages.get(msgIndex);
        Message prev = messages.get(msgIndex - 1);
        return canGroupInAutoAlbum(prev, curr);
    }

    private void applyCollapsedRowState(View itemView, boolean collapsed) {
        if (itemView == null) return;
        float dp = itemView.getContext().getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams rawLp = itemView.getLayoutParams();
        if (collapsed) {
            itemView.setVisibility(View.GONE);
            itemView.setPadding(0, 0, 0, 0);
            if (rawLp != null) {
                rawLp.height = 0;
                rawLp.width = 0;
                if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) rawLp).setMargins(0, 0, 0, 0);
                }
                itemView.setLayoutParams(rawLp);
            }
        } else {
            itemView.setVisibility(View.VISIBLE);
            int padH = Math.round(8f * dp);
            int padV = Math.round(1.5f * dp);
            itemView.setPadding(padH, padV, padH, padV);
            if (rawLp != null && (rawLp.height == 0 || rawLp.width == 0)) {
                rawLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                rawLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                itemView.setLayoutParams(rawLp);
            }
        }
    }

    private void bindQuickForwardButton(ImageView btnQuickForward, Message message, boolean show) {
        if (btnQuickForward == null) return;
        if (!show || message == null || "deleted".equals(message.getType()) || message.isBlink()) {
            btnQuickForward.setVisibility(View.GONE);
            btnQuickForward.setOnClickListener(null);
            return;
        }
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0x5254656F);
        btnQuickForward.setBackground(circle);
        btnQuickForward.setVisibility(View.VISIBLE);
        btnQuickForward.setOnClickListener(v -> {
            if (quickForwardClickListener != null) {
                quickForwardClickListener.onQuickForwardClick(message);
            }
        });
    }

    private String resolveOrMaterializeAlbumItemPath(Context context, Message ownerMessage, org.json.JSONObject itemObj, int itemIndex) {
        if (itemObj == null) return "";
        String path = itemObj.optString("path", "");
        if (!path.isEmpty() && new File(path).exists()) {
            return path;
        }
        String itemType = itemObj.optString("type", "image");
        String ext = "video".equalsIgnoreCase(itemType) ? ".mp4" : ".jpg";
        if (context != null && ownerMessage != null && itemIndex == 0) {
            try {
                File dec = FileMediaManager.getDecryptedFile(context, ownerMessage, ext);
                if (dec != null && dec.exists() && dec.length() > 32) {
                    itemObj.put("path", dec.getAbsolutePath());
                    return dec.getAbsolutePath();
                }
                if (ownerMessage.getLocalFilePath() != null && new File(ownerMessage.getLocalFilePath()).exists()) {
                    itemObj.put("path", ownerMessage.getLocalFilePath());
                    return ownerMessage.getLocalFilePath();
                }
                if (ownerMessage.getMediaUrl() != null && !ownerMessage.getMediaUrl().isEmpty()) {
                    File inlineFile = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(
                            context, ownerMessage.getChatId(), ownerMessage);
                    if (inlineFile != null && inlineFile.exists() && inlineFile.length() > 32) {
                        itemObj.put("path", inlineFile.getAbsolutePath());
                        return inlineFile.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {}
        }
        String thumbB64 = itemObj.optString("thumb", "");
        if (thumbB64.isEmpty() && itemIndex == 0 && ownerMessage != null && ownerMessage.getThumbnail() != null) {
            thumbB64 = ownerMessage.getThumbnail();
        }
        if (thumbB64 != null && !thumbB64.isEmpty() && context != null) {
            try {
                File cacheDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
                String msgId = ownerMessage != null && ownerMessage.getId() != null ? ownerMessage.getId() : "alb";
                File fallbackFile = new File(cacheDir, "dec_album_inline_" + msgId + "_" + itemIndex + ".jpg");
                if (!fallbackFile.exists() || fallbackFile.length() == 0) {
                    byte[] raw = Base64.decode(thumbB64, Base64.DEFAULT);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(fallbackFile)) {
                        fos.write(raw);
                    }
                }
                if (fallbackFile.exists() && fallbackFile.length() > 0) {
                    try {
                        itemObj.put("path", fallbackFile.getAbsolutePath());
                    } catch (Throwable ignored) {}
                    return fallbackFile.getAbsolutePath();
                }
            } catch (Throwable ignored) {}
        }
        return path;
    }

    /**
     * Checks if a message carries an explicit multi-photo/video album payload in pollJson,
     * OR is the head of a consecutive run of 2+ uncaptioned photo/video messages.
     */
    private org.json.JSONArray extractAlbumItems(Message message) {
        if (message == null) return null;
        if (message.getPollJson() != null && !message.getPollJson().isEmpty()) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(message.getPollJson());
                if (obj.optBoolean("album", false)) {
                    org.json.JSONArray arr = obj.optJSONArray("items");
                    if (arr != null && arr.length() > 1) {
                        Context appCtx = com.m3.veil.VeilApplication.getInstance();
                        for (int i = 0; i < arr.length(); i++) {
                            resolveOrMaterializeAlbumItemPath(appCtx, message, arr.optJSONObject(i), i);
                        }
                        return arr;
                    }
                }
            } catch (Throwable ignored) {}
        }

        int idx = findMessageIndex(message);
        if (idx >= 0 && !isCollapsedAutoAlbumChild(idx) && isEligibleForAutoAlbum(message)) {
            int endIdx = idx;
            while (endIdx + 1 < messages.size()
                    && (endIdx - idx) < 29
                    && canGroupInAutoAlbum(messages.get(endIdx), messages.get(endIdx + 1))) {
                endIdx++;
            }
            if (endIdx > idx) {
                Context appCtx = com.m3.veil.VeilApplication.getInstance();
                org.json.JSONArray autoArr = new org.json.JSONArray();
                for (int k = idx; k <= endIdx; k++) {
                    Message m = messages.get(k);
                    if (m == null) continue;
                    String mType = "video".equals(m.getType()) ? "video" : "image";
                    String ext = "video".equals(mType) ? ".mp4" : ".jpg";
                    String path = "";
                    if (appCtx != null) {
                        File dec = FileMediaManager.getDecryptedFile(appCtx, m, ext);
                        if (dec != null && dec.exists() && dec.length() > 0) {
                            path = dec.getAbsolutePath();
                        }
                    }
                    if (path.isEmpty() && m.getLocalFilePath() != null && new File(m.getLocalFilePath()).exists()) {
                        path = m.getLocalFilePath();
                    }
                    org.json.JSONObject itemObj = new org.json.JSONObject();
                    try {
                        itemObj.put("path", path);
                        itemObj.put("type", mType);
                        itemObj.put("caption", "");
                        itemObj.put("messageId", m.getId() != null ? m.getId() : "");
                        if (m.getThumbnail() != null && !m.getThumbnail().isEmpty()) {
                            itemObj.put("thumb", m.getThumbnail());
                        }
                        resolveOrMaterializeAlbumItemPath(appCtx, m, itemObj, k - idx);
                        autoArr.put(itemObj);
                    } catch (Throwable ignored) {}
                }
                if (autoArr.length() > 1) {
                    return autoArr;
                }
            }
        }
        return null;
    }

    private final android.util.LruCache<String, Bitmap> albumTileBitmapCache = new android.util.LruCache<>(64);
    private final android.util.LruCache<String, String> albumTileDurationCache = new android.util.LruCache<>(64);

    /**
     * Renders a WhatsApp-style grouped multi-photo/video collage inside imageCardView:
     * - Transparent 4dp divider lines between tiles so the chat bubble color shows through
     * - Rounded 8.5dp corners on every individual tile
     * - 2 items: 1x2 equal side-by-side rounded tiles
     * - 3 items: 1 tall full-height left tile + 2 stacked square right tiles (Screenshot1_bada0f2a.jpg)
     * - 4+ items: 2x2 rounded tiles with +(totalCount - 3) translucent slate overlay on the 4th tile (Screenshot1_bada0f2a.jpg)
     */
    private void bindGroupedAlbumGridOverlay(
            Context context,
            View imageCardView,
            Message message,
            String senderLabel,
            String time,
            boolean hasCaption) {
        if (!(imageCardView instanceof ViewGroup)) return;
        ViewGroup cardGroup = (ViewGroup) imageCardView;
        View existingGrid = cardGroup.findViewWithTag("veil_album_grid_overlay");
        org.json.JSONArray albumItems = extractAlbumItems(message);
        float dp = context.getResources().getDisplayMetrics().density;

        if (albumItems == null || albumItems.length() <= 1) {
            if (existingGrid != null) {
                existingGrid.setVisibility(View.GONE);
            }
            if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                ((com.google.android.material.card.MaterialCardView) imageCardView).setRadius(12f * dp);
            }
            return;
        }

        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
            com.google.android.material.card.MaterialCardView mcv =
                    (com.google.android.material.card.MaterialCardView) imageCardView;
            mcv.setRadius(0f);
            mcv.setCardElevation(0f);
            mcv.setStrokeWidth(0);
            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        int totalCount = albumItems.length();
        int gridW = Math.round(268f * dp);
        int gridH;
        if (totalCount == 2) {
            gridH = Math.round(164f * dp);
        } else if (totalCount == 3) {
            gridH = Math.round(284f * dp);
        } else {
            gridH = Math.round(268f * dp);
        }
        com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, gridW, gridH);

        LinearLayout gridRoot;
        if (existingGrid instanceof LinearLayout) {
            gridRoot = (LinearLayout) existingGrid;
            gridRoot.removeAllViews();
            gridRoot.setVisibility(View.VISIBLE);
        } else {
            gridRoot = new LinearLayout(context);
            gridRoot.setTag("veil_album_grid_overlay");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            cardGroup.addView(gridRoot, lp);
        }
        // Transparent background so the chat bubble color forms the visible divider lines between tiles
        gridRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        int gap = Math.max(3, Math.round(4f * dp));

        if (totalCount == 3) {
            // WhatsApp 3-item layout: 1 tall full-height left tile + 2 stacked square right tiles
            gridRoot.setOrientation(LinearLayout.HORIZONTAL);

            FrameLayout leftTile = createAlbumGridTile(
                    context, albumItems, 0, totalCount, message, senderLabel, time, hasCaption, dp);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            leftTile.setLayoutParams(leftLp);
            gridRoot.addView(leftTile);

            LinearLayout rightCol = new LinearLayout(context);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            LinearLayout.LayoutParams rightColLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            rightColLp.leftMargin = gap;
            rightCol.setLayoutParams(rightColLp);

            FrameLayout topRightTile = createAlbumGridTile(
                    context, albumItems, 1, totalCount, message, senderLabel, time, hasCaption, dp);
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            topRightTile.setLayoutParams(trLp);
            rightCol.addView(topRightTile);

            FrameLayout bottomRightTile = createAlbumGridTile(
                    context, albumItems, 2, totalCount, message, senderLabel, time, hasCaption, dp);
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            brLp.topMargin = gap;
            bottomRightTile.setLayoutParams(brLp);
            rightCol.addView(bottomRightTile);

            gridRoot.addView(rightCol);
        } else {
            // 2 items (1x2) or 4+ items (2x2 with +N overlay on 4th tile)
            gridRoot.setOrientation(LinearLayout.VERTICAL);
            int shown = Math.min(4, totalCount);
            int rows = shown <= 2 ? 1 : 2;
            int itemIdx = 0;

            for (int r = 0; r < rows; r++) {
                LinearLayout rowLayout = new LinearLayout(context);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                if (r > 0) rowLp.topMargin = gap;
                rowLayout.setLayoutParams(rowLp);

                for (int c = 0; c < 2 && itemIdx < shown; c++) {
                    final int tileIndex = itemIdx++;
                    FrameLayout tile = createAlbumGridTile(
                            context, albumItems, tileIndex, totalCount, message, senderLabel, time, hasCaption, dp);
                    LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    if (c > 0) tileLp.leftMargin = gap;
                    tile.setLayoutParams(tileLp);
                    rowLayout.addView(tile);
                }
                gridRoot.addView(rowLayout);
            }
        }
    }

    private FrameLayout createAlbumGridTile(
            Context context,
            org.json.JSONArray albumItems,
            int tileIndex,
            int totalCount,
            Message message,
            String senderLabel,
            String time,
            boolean hasCaption,
            float dp) {
        org.json.JSONObject itemObj = albumItems.optJSONObject(tileIndex);
        final String itemPath = resolveOrMaterializeAlbumItemPath(context, message, itemObj, tileIndex);
        final String itemType = itemObj != null ? itemObj.optString("type", "image") : "image";
        final String itemThumbB64 = itemObj != null ? itemObj.optString("thumb", "") : "";
        final boolean isVideoTile = "video".equalsIgnoreCase(itemType) || itemPath.toLowerCase(Locale.ROOT).endsWith(".mp4");

        FrameLayout tile = new FrameLayout(context);
        final float tileCornerRadius = 8.5f * dp;
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), tileCornerRadius);
            }
        });
        tile.setClipToOutline(true);

        boolean isNight = com.m3.veil.ui.views.ChatMessageCell.resolveIsNightMode(context);
        android.graphics.drawable.GradientDrawable tileBorder = new android.graphics.drawable.GradientDrawable();
        tileBorder.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        tileBorder.setCornerRadius(tileCornerRadius);
        tileBorder.setColor(android.graphics.Color.TRANSPARENT);
        tileBorder.setStroke(Math.max(1, Math.round(0.75f * dp)), isNight ? 0x26FFFFFF : 0x16000000);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            tile.setForeground(tileBorder);
        }

        ImageView iv = new ImageView(context);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(isNight ? 0xFF1E293B : 0xFFE2E8F0);

        String cacheKey = !itemPath.isEmpty() ? itemPath : (message != null ? message.getId() + "_" + tileIndex : "tile_" + tileIndex);
        Bitmap cachedBmp = albumTileBitmapCache.get(cacheKey);
        if (cachedBmp != null && !cachedBmp.isRecycled()) {
            iv.setImageBitmap(cachedBmp);
        } else {
            Bitmap loaded = null;
            if (!itemPath.isEmpty()) {
                File f = new File(itemPath);
                if (f.exists()) {
                    if (isVideoTile) {
                        try {
                            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                            mmr.setDataSource(f.getAbsolutePath());
                            loaded = mmr.getFrameAtTime();
                            String durMsStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                            if (durMsStr != null) {
                                long dMs = Long.parseLong(durMsStr);
                                if (dMs > 0) {
                                    albumTileDurationCache.put(cacheKey, VoicePlayerManager.formatDuration((int) dMs));
                                }
                            }
                            mmr.release();
                        } catch (Throwable ignored) {}
                    } else {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        loaded = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    }
                }
            }
            if (loaded == null && !itemThumbB64.isEmpty()) {
                try {
                    byte[] decoded = Base64.decode(itemThumbB64, Base64.DEFAULT);
                    loaded = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                } catch (Throwable ignored) {}
            }
            if (loaded != null) {
                albumTileBitmapCache.put(cacheKey, loaded);
                iv.setImageBitmap(loaded);
            }
        }
        tile.addView(iv);

        boolean hasMoreScrim = (tileIndex == 3 && totalCount > 4);
        int dlState = message != null ? downloadStates.getOrDefault(message.getId(), STATE_IDLE) : STATE_IDLE;
        int dlProgress = message != null ? downloadProgresses.getOrDefault(message.getId(), 0) : 0;
        boolean isTransferring = message != null && ((message.isPending() && !message.isDelivered() && !message.isRead())
                || (dlState == STATE_DOWNLOADING && dlProgress < 100));

        if ((isVideoTile || isTransferring) && !hasMoreScrim) {
            FrameLayout centerPill = new FrameLayout(context);
            int pSize = Math.round(40f * dp);
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(pSize, pSize, android.view.Gravity.CENTER);
            centerPill.setLayoutParams(pLp);
            android.graphics.drawable.GradientDrawable pBg = new android.graphics.drawable.GradientDrawable();
            pBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            pBg.setColor(0x8C000000);
            centerPill.setBackground(pBg);

            if (isTransferring) {
                ProgressBar ring = new ProgressBar(context);
                int rSz = Math.round(36f * dp);
                ring.setLayoutParams(new FrameLayout.LayoutParams(rSz, rSz, android.view.Gravity.CENTER));
                ring.setIndeterminate(true);
                ring.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF22C55E));
                centerPill.addView(ring);

                ImageView cancelIcon = new ImageView(context);
                int cSz = Math.round(15f * dp);
                cancelIcon.setLayoutParams(new FrameLayout.LayoutParams(cSz, cSz, android.view.Gravity.CENTER));
                cancelIcon.setImageResource(R.drawable.ic_close);
                cancelIcon.setColorFilter(0xFFFFFFFF);
                centerPill.addView(cancelIcon);
                centerPill.setOnClickListener(v -> {
                    if (mediaDownloadClickListener != null && message != null) {
                        mediaDownloadClickListener.onMediaCancelClick(message);
                    }
                });
            } else {
                ImageView playIcon = new ImageView(context);
                int icSz = Math.round(22f * dp);
                playIcon.setLayoutParams(new FrameLayout.LayoutParams(icSz, icSz, android.view.Gravity.CENTER));
                playIcon.setImageResource(R.drawable.ic_play_arrow);
                playIcon.setColorFilter(0xFFFFFFFF);
                centerPill.addView(playIcon);
            }
            tile.addView(centerPill);
        }

        if (isVideoTile && !hasMoreScrim) {
            String badgeLabel = null;
            if (isTransferring && !itemPath.isEmpty()) {
                File f = new File(itemPath);
                if (f.exists() && f.length() > 0) {
                    badgeLabel = String.format(Locale.US, "%.1f MB", f.length() / (1024f * 1024f));
                }
            }
            if (badgeLabel == null || badgeLabel.isEmpty()) {
                badgeLabel = albumTileDurationCache.get(cacheKey);
            }
            if (badgeLabel != null && !badgeLabel.isEmpty()) {
                TextView tvVidDur = new TextView(context);
                FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.START);
                int m = Math.round(6f * dp);
                dLp.setMargins(m, m, m, m);
                tvVidDur.setLayoutParams(dLp);
                tvVidDur.setText("🎥 " + badgeLabel);
                tvVidDur.setTextColor(0xFFFFFFFF);
                tvVidDur.setTextSize(10.5f);
                tvVidDur.setShadowLayer(3f, 0f, 1f, 0xCC000000);
                tile.addView(tvVidDur);
            }
        }

        // 4th tile +N overlay matching Screenshot1_bada0f2a.jpg (semi-transparent slate scrim + white +N text)
        if (hasMoreScrim) {
            TextView moreOverlay = new TextView(context);
            moreOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            moreOverlay.setBackgroundColor(0x82374151);
            moreOverlay.setGravity(android.view.Gravity.CENTER);
            moreOverlay.setText("+" + (totalCount - 3));
            moreOverlay.setTextColor(0xFFFFFFFF);
            moreOverlay.setTextSize(26f);
            moreOverlay.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            tile.addView(moreOverlay);
        }

        tile.setOnClickListener(v -> {
            Intent viewerIntent = buildMediaViewerAlbumIntent(
                    context, message, itemPath, itemType, senderLabel, time,
                    hasCaption ? message.getText() : "", tileIndex);
            context.startActivity(viewerIntent);
        });
        tile.setOnLongClickListener(v -> {
            if (longClickListener != null && message != null) {
                longClickListener.onMessageLongClick(message, itemViewForLongClick(v));
                return true;
            }
            return false;
        });
        return tile;
    }

    private View itemViewForLongClick(View child) {
        View cur = child;
        while (cur != null && cur.getParent() instanceof View && !(cur.getParent() instanceof RecyclerView)) {
            cur = (View) cur.getParent();
        }
        return cur != null ? cur : child;
    }

    /**
     * Builds an Intent for MediaViewerActivity.
     * - If clickedMessage is a multi-item album, opens Stage 2 (Vertical Scrolling Album Feed Mode)
     *   with all items from that album so clicking any item opens Stage 3 (Full-Screen Viewer).
     * - If clickedMessage is a single photo/video, opens Stage 3 (Full-Screen Viewer) directly.
     */
    private Intent buildMediaViewerAlbumIntent(
            Context context,
            Message clickedMessage,
            String fallbackPath,
            String fallbackType,
            String senderLabel,
            String time,
            String caption,
            int preferredSubIndex) {
        Intent intent = new Intent(context, MediaViewerActivity.class);
        intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, fallbackPath);
        intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, fallbackType);
        intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, senderLabel);
        intent.putExtra(MediaViewerActivity.EXTRA_TIMESTAMP, time);
        if (clickedMessage != null) {
            intent.putExtra(MediaViewerActivity.EXTRA_MESSAGE_ID, clickedMessage.getId());
            intent.putExtra(MediaViewerActivity.EXTRA_CHAT_ID, clickedMessage.getChatId());
            intent.putExtra(MediaViewerActivity.EXTRA_IS_STARRED, clickedMessage.isStarred());
        }
        if (recipientId != null) {
            intent.putExtra(MediaViewerActivity.EXTRA_PEER_ID, recipientId);
        }
        if (caption != null && !caption.isEmpty()) {
            intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, caption);
        }

        java.util.ArrayList<String> albumPaths = new java.util.ArrayList<>();
        java.util.ArrayList<String> albumTypes = new java.util.ArrayList<>();
        java.util.ArrayList<String> albumCaptions = new java.util.ArrayList<>();
        int initialIndex = 0;

        org.json.JSONArray explicitAlbum = extractAlbumItems(clickedMessage);
        boolean isMultiItemAlbum = explicitAlbum != null && explicitAlbum.length() > 1;
        if (isMultiItemAlbum) {
            for (int i = 0; i < explicitAlbum.length(); i++) {
                org.json.JSONObject item = explicitAlbum.optJSONObject(i);
                if (item == null) continue;
                String p = resolveOrMaterializeAlbumItemPath(context, clickedMessage, item, i);
                String t = item.optString("type", "image");
                String c = item.optString("caption", "");
                if (!p.isEmpty()) {
                    if (i == preferredSubIndex) {
                        initialIndex = albumPaths.size();
                    }
                    albumPaths.add(p);
                    albumTypes.add(t);
                    albumCaptions.add(c);
                }
            }
        } else if (messages != null && !messages.isEmpty()) {
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m == null || "deleted".equals(m.getType()) || m.isBlink()) continue;
                org.json.JSONArray subAlbum = extractAlbumItems(m);
                if (subAlbum != null && subAlbum.length() > 0) {
                    for (int j = 0; j < subAlbum.length(); j++) {
                        org.json.JSONObject item = subAlbum.optJSONObject(j);
                        if (item == null) continue;
                        String p = item.optString("path", "");
                        String t = item.optString("type", "image");
                        String c = item.optString("caption", "");
                        if (!p.isEmpty() && new File(p).exists()) {
                            if (clickedMessage != null && m.getId() != null && m.getId().equals(clickedMessage.getId()) && j == preferredSubIndex) {
                                initialIndex = albumPaths.size();
                            }
                            albumPaths.add(p);
                            albumTypes.add(t);
                            albumCaptions.add(c);
                        }
                    }
                    continue;
                }
                String mType = m.getType();
                if ("image".equals(mType) || "video".equals(mType) || "gif".equals(mType)) {
                    String ext = "video".equals(mType) ? ".mp4" : ("gif".equals(mType) ? ".gif" : ".jpg");
                    File f = FileMediaManager.getDecryptedFile(context, m, ext);
                    if (f != null && f.exists() && f.length() > 32) {
                        if (clickedMessage != null && m.getId() != null && m.getId().equals(clickedMessage.getId())) {
                            initialIndex = albumPaths.size();
                        }
                        albumPaths.add(f.getAbsolutePath());
                        albumTypes.add(mType);
                        String mCap = (m.getText() != null && !m.getText().startsWith("[")) ? m.getText() : "";
                        albumCaptions.add(mCap);
                    }
                }
            }
        }

        if (albumPaths.isEmpty() && fallbackPath != null && !fallbackPath.isEmpty()) {
            albumPaths.add(fallbackPath);
            albumTypes.add(fallbackType != null ? fallbackType : "image");
            albumCaptions.add(caption != null ? caption : "");
            initialIndex = 0;
        }

        if (initialIndex >= 0 && initialIndex < albumPaths.size()) {
            intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, albumPaths.get(initialIndex));
            intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, albumTypes.get(initialIndex));
        }
        intent.putStringArrayListExtra(MediaViewerActivity.EXTRA_ALBUM_PATHS, albumPaths);
        intent.putStringArrayListExtra(MediaViewerActivity.EXTRA_ALBUM_TYPES, albumTypes);
        intent.putStringArrayListExtra(MediaViewerActivity.EXTRA_ALBUM_CAPTIONS, albumCaptions);
        intent.putExtra(MediaViewerActivity.EXTRA_INITIAL_INDEX, initialIndex);
        intent.putExtra(MediaViewerActivity.EXTRA_START_IN_ALBUM_FEED, isMultiItemAlbum);
        return intent;
    }

    private void bindCircularOverlayPercent(View downloadingLayout, View cancelBtn, int progress) {
        if (!(downloadingLayout instanceof FrameLayout)) return;
        FrameLayout frame = (FrameLayout) downloadingLayout;
        Object existingTag = frame.getTag(R.id.messageText);
        TextView pctView;
        if (existingTag instanceof TextView) {
            pctView = (TextView) existingTag;
        } else {
            pctView = new TextView(frame.getContext());
            pctView.setTextColor(0xFFFFFFFF);
            pctView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
            pctView.setTypeface(null, android.graphics.Typeface.BOLD);
            pctView.setGravity(android.view.Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER);
            frame.addView(pctView, lp);
            frame.setTag(R.id.messageText, pctView);
        }
        int clamped = Math.max(1, Math.min(99, progress > 0 ? progress : 1));
        pctView.setText("✕\n" + clamped + "%");
        pctView.setLineSpacing(0f, 0.9f);
        pctView.setVisibility(View.VISIBLE);
        if (cancelBtn != null) {
            cancelBtn.setVisibility(View.GONE);
        }
    }

    private void attachDoubleTapAndLongPress(View bubbleView, View rootItemView, Message message) {
        if (bubbleView == null) return;
        bubbleView.setTag(R.id.messageBubble, message);
        Object existingDetector = bubbleView.getTag(R.id.messageText);
        if (existingDetector == null) {
            final android.view.GestureDetector detector = new android.view.GestureDetector(
                    bubbleView.getContext(),
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(android.view.MotionEvent e) {
                            Object tagObj = bubbleView.getTag(R.id.messageBubble);
                            if (!(tagObj instanceof Message)) return false;
                            Message boundMsg = (Message) tagObj;
                            if (doubleTapReactListener != null && !"deleted".equals(boundMsg.getType())) {
                                if (bubbleView instanceof com.m3.veil.ui.views.ChatMessageCell) {
                                    ((com.m3.veil.ui.views.ChatMessageCell) bubbleView).triggerDoubleTapHeartBurst(e.getX(), e.getY());
                                }
                                bubbleView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90)
                                        .withEndAction(() -> bubbleView.animate().scaleX(1f).scaleY(1f).setDuration(90).start())
                                        .start();
                                doubleTapReactListener.onDoubleTapReact(boundMsg);
                                return true;
                            }
                            return false;
                        }
                    });
            detector.setIsLongpressEnabled(false);
            bubbleView.setTag(R.id.messageText, detector);
            bubbleView.setOnTouchListener((v, event) -> {
                detector.onTouchEvent(event);
                return false;
            });

            View.OnLongClickListener longClick = v -> {
                Object tagObj = bubbleView.getTag(R.id.messageBubble);
                if (longClickListener != null && tagObj instanceof Message) {
                    longClickListener.onMessageLongClick((Message) tagObj, rootItemView != null ? rootItemView : bubbleView);
                    return true;
                }
                return false;
            };
            bubbleView.setOnLongClickListener(longClick);
            if (rootItemView != null) {
                rootItemView.setOnLongClickListener(longClick);
            }
        }
    }

    private void bindBlinkCard(Message message, boolean isMine, View blinkContainer,
                               TextView badgeIcon, ProgressBar blinkProgress, TextView titleText, TextView subtitleText) {
        if (blinkContainer == null) return;
        blinkContainer.setVisibility(View.VISIBLE);
        boolean viewed = message.isBlinkViewed();
        int downloadState = downloadStates.getOrDefault(message.getId(), STATE_IDLE);

        if (downloadState == STATE_DOWNLOADING && !viewed) {
            if (badgeIcon != null) badgeIcon.setVisibility(View.GONE);
            if (blinkProgress != null) blinkProgress.setVisibility(View.VISIBLE);
        } else {
            if (badgeIcon != null) {
                badgeIcon.setVisibility(View.VISIBLE);
                badgeIcon.setText(viewed ? "" : "1");
                badgeIcon.setAlpha(viewed ? 0.45f : 1.0f);
            }
            if (blinkProgress != null) blinkProgress.setVisibility(View.GONE);
        }

        String mediaLabel = "Message";
        if ("image".equals(message.getType())) mediaLabel = "Photo";
        else if ("video".equals(message.getType())) mediaLabel = "Video";
        else if ("voice".equals(message.getType())) mediaLabel = "Voice message";

        if (titleText != null) {
            if (viewed) {
                titleText.setText("Opened");
                titleText.setTypeface(null, android.graphics.Typeface.ITALIC);
                titleText.setAlpha(0.65f);
            } else {
                titleText.setText(mediaLabel);
                titleText.setTypeface(null, android.graphics.Typeface.NORMAL);
                titleText.setAlpha(1.0f);
            }
        }
        if (subtitleText != null) {
            subtitleText.setVisibility(View.GONE);
        }
        blinkContainer.setOnClickListener(v -> {
            if (blinkOpenClickListener != null) {
                blinkOpenClickListener.onBlinkOpenClick(message);
            }
        });
    }

    private void bindPollCard(Message message, View pollContainer, TextView questionText,
                              android.widget.LinearLayout optionsLayout, TextView totalVotesText) {
        if (pollContainer == null) return;
        pollContainer.setVisibility(View.VISIBLE);
        optionsLayout.removeAllViews();

        android.content.Context ctx = pollContainer.getContext();
        TextView subtitleView = pollContainer.findViewById(R.id.pollSubtitleText);
        ImageView pollTypeIcon = pollContainer.findViewById(R.id.pollTypeIcon);
        boolean isDarkBubble = com.m3.veil.ui.views.ChatMessageCell.resolveIsNightMode(ctx);
        if (pollContainer.getParent() instanceof com.m3.veil.ui.views.ChatMessageCell) {
            isDarkBubble = ((com.m3.veil.ui.views.ChatMessageCell) pollContainer.getParent()).isDarkBubble() || isDarkBubble;
        }
        int pollPrimary = isDarkBubble ? 0xFFF8FAFC : 0xFF0F172A;
        int pollSecondary = isDarkBubble ? 0xFF94A3B8 : 0xFF64748B;

        try {
            org.json.JSONObject poll = new org.json.JSONObject(
                    message.getPollJson() != null ? message.getPollJson() : "{}");
            String question = poll.optString("question", message.getText());
            boolean allowMultiple = poll.optBoolean("allowMultiple", false);
            if (questionText != null) {
                questionText.setText(question);
                questionText.setTextColor(pollPrimary);
            }
            if (subtitleView != null) {
                subtitleView.setText(allowMultiple ? "Select one or more" : "Select one");
                subtitleView.setTextColor(pollSecondary);
            }
            if (pollTypeIcon != null) {
                pollTypeIcon.setColorFilter(pollSecondary);
            }

            org.json.JSONArray opts = poll.optJSONArray("options");
            int totalVotes = 0;
            if (opts != null) {
                for (int i = 0; i < opts.length(); i++) {
                    org.json.JSONObject o = opts.getJSONObject(i);
                    org.json.JSONArray voters = o.optJSONArray("voters");
                    int vCount = voters != null ? voters.length() : o.optInt("votes", 0);
                    totalVotes += vCount;
                }
                for (int i = 0; i < opts.length(); i++) {
                    org.json.JSONObject o = opts.getJSONObject(i);
                    String optText = o.optString("text", "Option " + (i + 1));
                    org.json.JSONArray voters = o.optJSONArray("voters");

                    boolean votedByMe = false;
                    boolean votedByPeer = false;
                    if (voters != null) {
                        for (int vIdx = 0; vIdx < voters.length(); vIdx++) {
                            String voterId = voters.optString(vIdx, "");
                            if (voterId.equals(currentUserId)) {
                                votedByMe = true;
                            } else if (!voterId.isEmpty()) {
                                votedByPeer = true;
                            }
                        }
                    }
                    int votes = voters != null ? voters.length() : o.optInt("votes", 0);
                    int percent = totalVotes > 0 ? Math.round((votes * 100f) / totalVotes) : 0;

                    View optRow = LayoutInflater.from(ctx).inflate(R.layout.item_poll_option_whatsapp, optionsLayout, false);
                    View checkboxFrame = optRow.findViewById(R.id.poll_option_vote_checkbox);
                    View checkIcon = optRow.findViewById(R.id.poll_option_check_icon);
                    TextView tvOptName = optRow.findViewById(R.id.poll_option_name);
                    TextView tvVoterAvatar1 = optRow.findViewById(R.id.poll_voter_avatar_1);
                    TextView tvVoterAvatar2 = optRow.findViewById(R.id.poll_voter_avatar_2);
                    TextView tvVoteCount = optRow.findViewById(R.id.poll_option_vote_count);
                    ProgressBar ratioBar = optRow.findViewById(R.id.poll_vote_ratio);

                    if (tvOptName != null) {
                        tvOptName.setText(optText);
                        tvOptName.setTextColor(pollPrimary);
                    }
                    if (tvVoteCount != null) {
                        tvVoteCount.setTextColor(pollSecondary);
                    }
                    if (checkboxFrame != null) {
                        checkboxFrame.setBackgroundResource(votedByMe
                                ? R.drawable.bg_poll_checkbox_checked
                                : R.drawable.bg_poll_checkbox_unchecked);
                    }
                    if (checkIcon != null) {
                        checkIcon.setVisibility(votedByMe ? View.VISIBLE : View.GONE);
                    }
                    if (tvVoterAvatar1 != null) {
                        tvVoterAvatar1.setVisibility(votedByMe ? View.VISIBLE : View.GONE);
                    }
                    if (tvVoterAvatar2 != null) {
                        tvVoterAvatar2.setVisibility((votedByPeer || (!votedByMe && votes > 0)) ? View.VISIBLE : View.GONE);
                    }
                    if (tvVoteCount != null) {
                        tvVoteCount.setText(String.valueOf(votes));
                    }
                    if (ratioBar != null) {
                        ratioBar.setMax(100);
                        ratioBar.setProgress(percent);
                    }

                    final int optionIndex = i;
                    optRow.setOnClickListener(v -> {
                        if (pollOptionVoteListener != null) {
                            pollOptionVoteListener.onPollOptionClicked(message, optionIndex);
                        } else if (pollVoteClickListener != null) {
                            pollVoteClickListener.onPollVoteClick(message);
                        }
                    });

                    optionsLayout.addView(optRow);
                }
            }
            if (totalVotesText != null) {
                totalVotesText.setText("View votes");
                totalVotesText.setOnClickListener(v -> {
                    if (pollOptionVoteListener != null) {
                        pollOptionVoteListener.onViewPollVotesClicked(message);
                    } else if (pollVoteClickListener != null) {
                        pollVoteClickListener.onPollVoteClick(message);
                    }
                });
            }
        } catch (Exception e) {
            if (questionText != null) questionText.setText(message.getText());
        }

        pollContainer.setOnClickListener(null);
        pollContainer.setClickable(false);
    }

    private void bindLocationCard(Message message, View locationContainer, TextView titleView, TextView subtitleView) {
        if (locationContainer == null) return;
        locationContainer.setVisibility(View.VISIBLE);
        String loc = message.getText() != null ? message.getText() : "Shared Location";
        double lat = 37.7749;
        double lng = -122.4194;
        String placeTitle = "Current Location";
        String placeSubtitle = "Tap to open in Maps";
        boolean isLive = loc.contains("Live Location");

        if (loc.startsWith("geo:")) {
            String clean = loc.substring(4);
            int qIdx = clean.indexOf("?q=");
            String coords = qIdx != -1 ? clean.substring(0, qIdx) : clean;
            String[] parts = coords.split(",");
            if (parts.length >= 2) {
                try {
                    lat = Double.parseDouble(parts[0].trim());
                    lng = Double.parseDouble(parts[1].trim());
                } catch (Exception ignored) {}
            }
            int openParen = loc.indexOf("(");
            int closeParen = loc.lastIndexOf(")");
            if (openParen != -1 && closeParen > openParen) {
                String labelInside = loc.substring(openParen + 1, closeParen);
                if (labelInside.contains(" - ")) {
                    String[] lp = labelInside.split(" - ", 2);
                    placeTitle = lp[0].trim();
                    placeSubtitle = lp[1].trim();
                } else {
                    placeTitle = labelInside.trim();
                    placeSubtitle = String.format(Locale.US, "%.5f, %.5f", lat, lng);
                }
            } else {
                placeSubtitle = String.format(Locale.US, "%.5f, %.5f", lat, lng);
            }
        } else {
            placeSubtitle = loc;
        }

        if (titleView != null) titleView.setText(placeTitle);
        if (subtitleView != null) subtitleView.setText(placeSubtitle);

        View mapView = locationContainer.findViewById(R.id.locationMapCanvas);
        if (mapView instanceof com.m3.veil.ui.views.WhatsAppMapPreviewView) {
            ((com.m3.veil.ui.views.WhatsAppMapPreviewView) mapView).setLocationData(lat, lng, isLive, 10f);
        }

        locationContainer.setOnClickListener(v -> FileMediaManager.openLocationInMaps(locationContainer.getContext(), message.getText()));
    }

    private void bindCallCard(Message message, View container, FrameLayout iconContainer,
                              ImageView icon, TextView title, TextView subtitle, boolean isSentHolder) {
        if (container == null || message == null) return;
        container.setVisibility(View.VISIBLE);

        boolean isVideo = false;
        String status = "completed";
        int durationSeconds = 0;
        String callerId = message.getSenderId();

        if (message.getPollJson() != null && !message.getPollJson().isEmpty()) {
            try {
                JSONObject obj = new JSONObject(message.getPollJson());
                isVideo = "video".equalsIgnoreCase(obj.optString("callType"));
                status = obj.optString("status", "completed");
                durationSeconds = obj.optInt("duration", 0);
                if (obj.has("callerId")) callerId = obj.optString("callerId");
            } catch (Exception ignored) {}
        } else if (message.getText() != null) {
            String lower = message.getText().toLowerCase();
            isVideo = lower.contains("video");
            if (lower.contains("missed")) status = "missed";
            else if (lower.contains("declined")) status = "declined";
            else if (lower.contains("busy")) status = "busy";
            else if (lower.contains("failed")) status = "failed";
            else if (lower.contains("cancelled")) status = "cancelled";
        }

        boolean isCaller = currentUserId != null && currentUserId.equals(callerId);
        boolean isMissed = "missed".equalsIgnoreCase(status);
        boolean isDeclined = "declined".equalsIgnoreCase(status);
        boolean isBusy = "busy".equalsIgnoreCase(status);

        boolean isDarkCallBubble = com.m3.veil.ui.views.ChatMessageCell.resolveIsNightMode(container.getContext());
        if (container.getParent() instanceof com.m3.veil.ui.views.ChatMessageCell) {
            isDarkCallBubble = ((com.m3.veil.ui.views.ChatMessageCell) container.getParent()).isDarkBubble() || isDarkCallBubble;
        }
        int primaryCallText = isDarkCallBubble ? 0xFFF8FAFC : 0xFF0F172A;
        int secondaryColor = isDarkCallBubble ? 0xFF94A3B8 : 0xFF64748B;
        int greenColor = 0xFF10B981;
        int redColor = 0xFFEF4444;

        if (subtitle != null) {
            subtitle.setTextColor(secondaryColor);
        }

        if (isSentHolder) {
            // Outgoing call (sent side)
            if (isVideo) {
                icon.setImageResource(R.drawable.ic_video_call_outgoing);
            } else {
                icon.setImageResource(R.drawable.ic_call_outgoing);
            }

            title.setText(isVideo ? "Video call" : "Voice call");
            title.setTextColor(primaryCallText);

            if (durationSeconds > 0) {
                icon.setColorFilter(greenColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1A10B981));
                }
                subtitle.setText(formatDuration(durationSeconds));
            } else if (isDeclined) {
                icon.setColorFilter(redColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1AEF4444));
                }
                subtitle.setText("Declined");
            } else if (isBusy) {
                icon.setColorFilter(redColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1AEF4444));
                }
                subtitle.setText("Busy");
            } else {
                // Cancelled or no answer
                icon.setColorFilter(secondaryColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1A64748B));
                }
                subtitle.setText("Cancelled");
            }
        } else {
            // Incoming call (received side)
            if (isVideo) {
                icon.setImageResource(R.drawable.ic_video_call_incoming);
            } else {
                icon.setImageResource(R.drawable.ic_call_incoming);
            }

            if (isMissed || (durationSeconds == 0 && !isDeclined && !isBusy)) {
                icon.setColorFilter(redColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1AEF4444));
                }
                title.setText(isVideo ? "Missed video call" : "Missed voice call");
                title.setTextColor(redColor);
                subtitle.setText("Tap to call back");
            } else if (isDeclined) {
                icon.setColorFilter(redColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1AEF4444));
                }
                title.setText(isVideo ? "Declined video call" : "Declined voice call");
                title.setTextColor(primaryCallText);
                subtitle.setText("Tap to call back");
            } else {
                // Answered incoming call
                icon.setColorFilter(greenColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1A10B981));
                }
                title.setText(isVideo ? "Video call" : "Voice call");
                title.setTextColor(primaryCallText);
                subtitle.setText(formatDuration(durationSeconds));
            }
        }

        final boolean finalIsVideo = isVideo;
        View.OnClickListener clickListener = v -> {
            if (callMessageClickListener != null) {
                callMessageClickListener.onCallMessageClick(message);
            } else {
                android.content.Context ctx = v.getContext();
                boolean isMe = currentUserId != null && currentUserId.equals(message.getSenderId());
                String peerId = isMe ? message.getReceiverId() : message.getSenderId();
                String peerName = null;

                if (peerId != null && !peerId.isEmpty()) {
                    com.m3.veil.model.Chat c = com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getChat(peerId);
                    if (c != null && c.getName() != null && !c.getName().isEmpty() && !"Me".equalsIgnoreCase(c.getName())) {
                        peerName = c.getName();
                    }
                }
                if (peerName == null || peerName.isEmpty() || "Me".equalsIgnoreCase(peerName)) {
                    peerName = isMe ? "Contact" : (message.getSenderName() != null ? message.getSenderName() : "Contact");
                }
                if ("Me".equalsIgnoreCase(peerName)) {
                    peerName = "Veil Peer";
                }

                Intent intent = new Intent(ctx, com.m3.veil.ui.chat.CallActivity.class);
                intent.putExtra(com.m3.veil.ui.chat.CallActivity.EXTRA_CONTACT_ID, peerId);
                intent.putExtra(com.m3.veil.ui.chat.CallActivity.EXTRA_CONTACT_NAME, peerName);
                intent.putExtra(com.m3.veil.ui.chat.CallActivity.EXTRA_CALL_TYPE, finalIsVideo ? "video" : "audio");
                intent.putExtra(com.m3.veil.ui.chat.CallActivity.EXTRA_IS_INCOMING, false);
                ctx.startActivity(intent);
            }
        };
        container.setOnClickListener(clickListener);
    }

    private String formatDuration(int durationSeconds) {
        if (durationSeconds <= 0) return "0 sec";
        if (durationSeconds < 60) return durationSeconds + " sec";
        long minutes = durationSeconds / 60;
        long seconds = durationSeconds % 60;
        if (minutes < 60) {
            return seconds > 0 ? minutes + " min " + seconds + " sec" : minutes + " min";
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + " hr " + minutes + " min";
    }

    public class SentMessageViewHolder extends RecyclerView.ViewHolder {
        View dateHeaderContainer;
        TextView dateHeaderText;
        TextView messageText, timeText, reactionsText;
        ImageView statusIcon;
        View linkPreviewContainer;
        ImageView ivLinkThumb;
        TextView tvLinkDomain, tvLinkTitle, tvLinkDesc;
        View replyQuoteContainer;
        TextView replyQuoteName, replyQuoteText;
        public View messageBubble;
        View bubbleInfoContainer;
        
        // Voice message UI
        View voiceMessageContainer;
        ImageView voiceActionButton;
        ProgressBar voiceProgressBar;
        VoiceWaveformView voiceWaveformView;
        TextView voiceDurationText;
        TextView voiceSpeedButton;
        View voiceMicBadge;
        View voiceLine;
        SeekBar voiceSeekBar;
        
        // Image / GIF / Sticker UI
        View imageMessageContainer;
        View imageCardView;
        ImageView messageImage;
        View imageScrimView;
        TextView imageGifBadge;
        ProgressBar imageProgressBar;
        View imageOverlayPill;
        TextView imageTimeText;
        ImageView imageStatusIcon;
        TextView imageCaptionText;

        // Video UI
        View videoMessageContainer;
        View videoCardView;
        ImageView messageVideoThumbnail;
        View videoPlayButtonContainer;
        ImageView videoPlayIcon;
        ProgressBar videoProgressBar;
        View videoOverlayPill;
        TextView videoTimeText;
        ImageView videoStatusIcon;
        View videoDurationContainer;
        TextView videoDurationText;
        TextView videoCaptionText;

        // Document UI
        View documentMessageContainer;
        View documentIconContainer;
        TextView documentExtensionBadge;
        TextView documentFileName;
        TextView documentFileSize;
        ImageView documentActionIcon;
        ProgressBar documentProgressBar;

        // Location UI
        View locationMessageContainer;
        TextView locationTitle;
        TextView locationSubtitle;

        // Contact UI
        View contactMessageContainer;
        TextView contactName;
        TextView contactPhone;
        TextView contactAction;
        TextView contactActionMessage;
        TextView contactActionSave;

        // Call UI
        View callMessageContainer;
        FrameLayout callIconContainer;
        ImageView callIcon;
        TextView callTitleText;
        TextView callSubtitleText;

        // Poll & Blink & Metadata Badges
        View pollMessageContainer;
        TextView pollQuestionText;
        android.widget.LinearLayout pollOptionsLinearLayout;
        TextView pollTotalVotesText;
        View blinkMessageContainer;
        TextView blinkBadgeIcon;
        ProgressBar blinkProgressBar;
        TextView blinkTitleText;
        TextView blinkSubtitleText;
        ImageView ivPinnedBadge;
        ImageView ivImagePinnedBadge;
        ImageView ivVideoPinnedBadge;
        TextView tvStarredBadge;
        TextView tvVanishBadge;
        TextView tvEditedBadge;

        FrameLayout imageDownloadOverlay;
        LinearLayout imageDownloadIdleLayout;
        TextView imageDownloadSizeText;
        FrameLayout imageDownloadingLayout;
        ProgressBar imageDownloadProgress;
        ImageView imageDownloadCancelBtn;
        LinearLayout imageDownloadRetryLayout;

        FrameLayout videoDownloadOverlay;
        LinearLayout videoDownloadIdleLayout;
        TextView videoDownloadSizeText;
        FrameLayout videoDownloadingLayout;
        ProgressBar videoDownloadProgress;
        ImageView videoDownloadCancelBtn;
        LinearLayout videoDownloadRetryLayout;

        ImageView btnQuickForwardMedia;
        private boolean hasVisibleMediaContainer = false;
        final VeilAdvancedTextView.OnEntityClickListener entityClickListener;

        public SentMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            entityClickListener = createEntityClickListener(itemView.getContext());
            dateHeaderContainer = itemView.findViewById(R.id.dateHeaderContainer);
            dateHeaderText = itemView.findViewById(R.id.dateHeaderText);
            messageText = itemView.findViewById(R.id.messageText);
            timeText = itemView.findViewById(R.id.timeText);
            statusIcon = itemView.findViewById(R.id.statusIcon);
            reactionsText = itemView.findViewById(R.id.reactionsText);
            replyQuoteContainer = itemView.findViewById(R.id.replyQuoteContainer);
            replyQuoteName = itemView.findViewById(R.id.replyQuoteName);
            replyQuoteText = itemView.findViewById(R.id.replyQuoteText);
            messageBubble = itemView.findViewById(R.id.messageBubble);
            btnQuickForwardMedia = itemView.findViewById(R.id.btnQuickForwardMedia);
            bubbleInfoContainer = itemView.findViewById(R.id.bubbleInfoContainer);
            ivPinnedBadge = itemView.findViewById(R.id.ivPinnedBadge);
            tvStarredBadge = itemView.findViewById(R.id.tvStarredBadge);
            tvVanishBadge = itemView.findViewById(R.id.tvVanishBadge);
            tvEditedBadge = itemView.findViewById(R.id.tvEditedBadge);
        }

        private void ensureLinkPreviewViews() {
            if (linkPreviewContainer == null) {
                linkPreviewContainer = itemView.findViewById(R.id.linkPreviewContainer);
                ivLinkThumb = itemView.findViewById(R.id.ivLinkThumb);
                tvLinkDomain = itemView.findViewById(R.id.tvLinkDomain);
                tvLinkTitle = itemView.findViewById(R.id.tvLinkTitle);
                tvLinkDesc = itemView.findViewById(R.id.tvLinkDesc);
            }
        }

        private void ensureVoiceViews() {
            if (voiceMessageContainer == null) {
                voiceMessageContainer = itemView.findViewById(R.id.voiceMessageContainer);
                voiceActionButton = itemView.findViewById(R.id.voiceActionButton);
                voiceProgressBar = itemView.findViewById(R.id.voiceProgressBar);
                voiceWaveformView = itemView.findViewById(R.id.voiceWaveformView);
                voiceDurationText = itemView.findViewById(R.id.voiceDurationText);
                voiceSpeedButton = itemView.findViewById(R.id.voiceSpeedButton);
                voiceMicBadge = itemView.findViewById(R.id.voiceMicBadge);
                voiceLine = itemView.findViewById(R.id.voiceLine);
                voiceSeekBar = itemView.findViewById(R.id.voiceSeekBar);
            }
        }

        private void ensureImageViews() {
            if (imageMessageContainer == null) {
                imageMessageContainer = itemView.findViewById(R.id.imageMessageContainer);
                imageCardView = itemView.findViewById(R.id.imageCardView);
                messageImage = itemView.findViewById(R.id.messageImage);
                imageScrimView = itemView.findViewById(R.id.imageScrimView);
                imageGifBadge = itemView.findViewById(R.id.imageGifBadge);
                imageProgressBar = itemView.findViewById(R.id.imageProgressBar);
                imageOverlayPill = itemView.findViewById(R.id.imageOverlayPill);
                ivImagePinnedBadge = itemView.findViewById(R.id.ivImagePinnedBadge);
                imageTimeText = itemView.findViewById(R.id.imageTimeText);
                imageStatusIcon = itemView.findViewById(R.id.imageStatusIcon);
                imageCaptionText = itemView.findViewById(R.id.imageCaptionText);
                imageDownloadOverlay = itemView.findViewById(R.id.imageDownloadOverlay);
                imageDownloadIdleLayout = itemView.findViewById(R.id.imageDownloadIdleLayout);
                imageDownloadSizeText = itemView.findViewById(R.id.imageDownloadSizeText);
                imageDownloadingLayout = itemView.findViewById(R.id.imageDownloadingLayout);
                imageDownloadProgress = itemView.findViewById(R.id.imageDownloadProgress);
                imageDownloadCancelBtn = itemView.findViewById(R.id.imageDownloadCancelBtn);
                imageDownloadRetryLayout = itemView.findViewById(R.id.imageDownloadRetryLayout);
            }
        }

        private void ensureVideoViews() {
            if (videoMessageContainer == null) {
                videoMessageContainer = itemView.findViewById(R.id.videoMessageContainer);
                videoCardView = itemView.findViewById(R.id.videoCardView);
                messageVideoThumbnail = itemView.findViewById(R.id.messageVideoThumbnail);
                videoPlayButtonContainer = itemView.findViewById(R.id.videoPlayButtonContainer);
                videoPlayIcon = itemView.findViewById(R.id.videoPlayIcon);
                videoProgressBar = itemView.findViewById(R.id.videoProgressBar);
                videoOverlayPill = itemView.findViewById(R.id.videoOverlayPill);
                ivVideoPinnedBadge = itemView.findViewById(R.id.ivVideoPinnedBadge);
                videoTimeText = itemView.findViewById(R.id.videoTimeText);
                videoStatusIcon = itemView.findViewById(R.id.videoStatusIcon);
                videoDurationContainer = itemView.findViewById(R.id.videoDurationContainer);
                videoDurationText = itemView.findViewById(R.id.videoDurationText);
                videoCaptionText = itemView.findViewById(R.id.videoCaptionText);
                videoDownloadOverlay = itemView.findViewById(R.id.videoDownloadOverlay);
                videoDownloadIdleLayout = itemView.findViewById(R.id.videoDownloadIdleLayout);
                videoDownloadSizeText = itemView.findViewById(R.id.videoDownloadSizeText);
                videoDownloadingLayout = itemView.findViewById(R.id.videoDownloadingLayout);
                videoDownloadProgress = itemView.findViewById(R.id.videoDownloadProgress);
                videoDownloadCancelBtn = itemView.findViewById(R.id.videoDownloadCancelBtn);
                videoDownloadRetryLayout = itemView.findViewById(R.id.videoDownloadRetryLayout);
            }
        }

        private void ensureDocumentViews() {
            if (documentMessageContainer == null) {
                documentMessageContainer = itemView.findViewById(R.id.documentMessageContainer);
                documentIconContainer = itemView.findViewById(R.id.documentIconContainer);
                documentExtensionBadge = itemView.findViewById(R.id.documentExtensionBadge);
                documentFileName = itemView.findViewById(R.id.documentFileName);
                documentFileSize = itemView.findViewById(R.id.documentFileSize);
                documentActionIcon = itemView.findViewById(R.id.documentActionIcon);
                documentProgressBar = itemView.findViewById(R.id.documentProgressBar);
            }
        }

        private void ensureLocationViews() {
            if (locationMessageContainer == null) {
                locationMessageContainer = itemView.findViewById(R.id.locationMessageContainer);
                locationTitle = itemView.findViewById(R.id.locationTitle);
                locationSubtitle = itemView.findViewById(R.id.locationSubtitle);
            }
        }

        private void ensureContactViews() {
            if (contactMessageContainer == null) {
                contactMessageContainer = itemView.findViewById(R.id.contactMessageContainer);
                contactName = itemView.findViewById(R.id.contactName);
                contactPhone = itemView.findViewById(R.id.contactPhone);
                contactAction = itemView.findViewById(R.id.contactAction);
                contactActionMessage = itemView.findViewById(R.id.contactActionMessage);
                contactActionSave = itemView.findViewById(R.id.contactActionSave);
            }
        }

        private void ensurePollViews() {
            if (pollMessageContainer == null) {
                pollMessageContainer = itemView.findViewById(R.id.pollMessageContainer);
                pollQuestionText = itemView.findViewById(R.id.pollQuestionText);
                pollOptionsLinearLayout = itemView.findViewById(R.id.pollOptionsLinearLayout);
                pollTotalVotesText = itemView.findViewById(R.id.pollTotalVotesText);
            }
        }

        private void ensureBlinkViews() {
            if (blinkMessageContainer == null) {
                blinkMessageContainer = itemView.findViewById(R.id.blinkMessageContainer);
                blinkBadgeIcon = itemView.findViewById(R.id.blinkBadgeIcon);
                blinkProgressBar = itemView.findViewById(R.id.blinkProgressBar);
                blinkTitleText = itemView.findViewById(R.id.blinkTitleText);
                blinkSubtitleText = itemView.findViewById(R.id.blinkSubtitleText);
            }
        }

        private void ensureCallViews() {
            if (callMessageContainer == null) {
                callMessageContainer = itemView.findViewById(R.id.callMessageContainer);
                callIconContainer = itemView.findViewById(R.id.callIconContainer);
                callIcon = itemView.findViewById(R.id.callIcon);
                callTitleText = itemView.findViewById(R.id.callTitleText);
                callSubtitleText = itemView.findViewById(R.id.callSubtitleText);
            }
        }

        private void hideAllMediaContainers() {
            if (!hasVisibleMediaContainer) return;
            hasVisibleMediaContainer = false;
            if (linkPreviewContainer != null) linkPreviewContainer.setVisibility(View.GONE);
            if (voiceMessageContainer != null) voiceMessageContainer.setVisibility(View.GONE);
            if (imageMessageContainer != null) imageMessageContainer.setVisibility(View.GONE);
            if (videoMessageContainer != null) videoMessageContainer.setVisibility(View.GONE);
            if (documentMessageContainer != null) documentMessageContainer.setVisibility(View.GONE);
            if (locationMessageContainer != null) locationMessageContainer.setVisibility(View.GONE);
            if (contactMessageContainer != null) contactMessageContainer.setVisibility(View.GONE);
            if (pollMessageContainer != null) pollMessageContainer.setVisibility(View.GONE);
            if (blinkMessageContainer != null) blinkMessageContainer.setVisibility(View.GONE);
            if (callMessageContainer != null) callMessageContainer.setVisibility(View.GONE);
        }

        private void applySentBubbleTheme(boolean isDark) {
            Context ctx = itemView.getContext();
            int textPrimary = isDark ? 0xFFF8FAFC : ContextCompat.getColor(ctx, R.color.message_text_primary);
            int textSecondary = isDark ? 0xCCF8FAFC : ContextCompat.getColor(ctx, R.color.message_text_secondary);
            int actionColor = isDark ? 0xFF818CF8 : ContextCompat.getColor(ctx, R.color.message_action_button);

            if (timeText != null) timeText.setTextColor(textSecondary);
            if (ivPinnedBadge != null) ivPinnedBadge.setColorFilter(textSecondary);
            if (tvStarredBadge != null) tvStarredBadge.setTextColor(textSecondary);
            if (tvVanishBadge != null) tvVanishBadge.setTextColor(textSecondary);
            if (tvEditedBadge != null) tvEditedBadge.setTextColor(textSecondary);
            if (voiceDurationText != null) voiceDurationText.setTextColor(textSecondary);
            if (voiceSpeedButton != null) voiceSpeedButton.setTextColor(textPrimary);
            if (documentFileName != null) documentFileName.setTextColor(textPrimary);
            if (documentFileSize != null) documentFileSize.setTextColor(textSecondary);
            if (locationTitle != null) locationTitle.setTextColor(textPrimary);
            if (locationSubtitle != null) locationSubtitle.setTextColor(textSecondary);
            if (contactName != null) contactName.setTextColor(textPrimary);
            if (contactPhone != null) contactPhone.setTextColor(textSecondary);
            if (contactActionMessage != null) contactActionMessage.setTextColor(actionColor);
            if (contactActionSave != null) contactActionSave.setTextColor(actionColor);
            if (pollQuestionText != null) pollQuestionText.setTextColor(textPrimary);
            if (pollTotalVotesText != null) pollTotalVotesText.setTextColor(actionColor);
            if (blinkTitleText != null) blinkTitleText.setTextColor(textPrimary);
            if (callTitleText != null) callTitleText.setTextColor(textPrimary);
            if (callSubtitleText != null) callSubtitleText.setTextColor(textSecondary);
            if (tvLinkTitle != null) tvLinkTitle.setTextColor(textPrimary);
            if (tvLinkDesc != null) tvLinkDesc.setTextColor(textSecondary);
            styleReplyQuoteCard(replyQuoteContainer, replyQuoteName, replyQuoteText, true, isDark);
            if (imageCaptionText != null) imageCaptionText.setTextColor(textPrimary);
            if (videoCaptionText != null) videoCaptionText.setTextColor(textPrimary);
        }

        public void bind(Message message, String time) {
            bind(message, time, false, "");
        }

        public void bind(Message message, String time, boolean showDate, String dateLabel) {
            int msgIdx = findMessageIndex(message);
            if (isCollapsedAutoAlbumChild(msgIdx) || isCollapsedPairedStickerChild(msgIdx)) {
                applyCollapsedRowState(itemView, true);
                return;
            }
            applyCollapsedRowState(itemView, false);
            bindQuickForwardButton(btnQuickForwardMedia, message, false);

            if (dateHeaderContainer != null && dateHeaderText != null) {
                if (showDate && dateLabel != null && !dateLabel.isEmpty()) {
                    dateHeaderContainer.setVisibility(View.VISIBLE);
                    dateHeaderText.setText(dateLabel);
                } else {
                    dateHeaderContainer.setVisibility(View.GONE);
                }
            }
            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
            if (statusIcon != null) statusIcon.setVisibility(View.VISIBLE);
            timeText.setText(time);
            bindBubbleInfoBadges(message, ivPinnedBadge, tvStarredBadge, tvVanishBadge, tvEditedBadge);
            if (messageBubble != null) {
                float stdDp = itemView.getContext().getResources().getDisplayMetrics().density;
                int stdPadH = Math.round(10f * stdDp);
                int stdPadV = Math.round(7f * stdDp);
                messageBubble.setPadding(stdPadH, stdPadV, stdPadH, stdPadV);
                ViewGroup.LayoutParams bLp = messageBubble.getLayoutParams();
                if (bLp != null && bLp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    bLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    messageBubble.setLayoutParams(bLp);
                }
            }
            if (messageText != null) {
                int screenW = itemView.getContext().getResources().getDisplayMetrics().widthPixels;
                messageText.setMaxWidth(Math.round(screenW * 0.72f));
            }
            boolean isDarkBubble = false;
            if (messageBubble instanceof com.m3.veil.ui.views.ChatMessageCell) {
                com.m3.veil.ui.views.ChatMessageCell cell = (com.m3.veil.ui.views.ChatMessageCell) messageBubble;
                int pos = msgIdx >= 0 ? msgIdx : (getAdapterPosition() - 1);
                cell.setMessageObject(message, true, computeClusterPosition(pos), false);
                if (cachedCustomBubbleColor != 0) {
                    cell.setCustomBubbleColor(cachedCustomBubbleColor);
                }
                if (cachedCornerRadiusDp > 0) {
                    cell.setCornerRadiusDp(cachedCornerRadiusDp);
                }
                isDarkBubble = cell.isDarkBubble();
            } else {
                Context ctx = itemView.getContext();
                boolean isNight = (ctx.getResources().getConfiguration().uiMode 
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                int bColor = cachedCustomBubbleColor != 0 ? cachedCustomBubbleColor
                        : ContextCompat.getColor(ctx, R.color.message_bubble_sent);
                isDarkBubble = androidx.core.graphics.ColorUtils.calculateLuminance(bColor) < 0.5;
            }
            applySentBubbleTheme(isDarkBubble);
            attachDoubleTapAndLongPress(messageBubble, itemView, message);
            
            if ("deleted".equals(message.getType())) {
                messageText.setVisibility(View.VISIBLE);
                hideAllMediaContainers();
                messageText.setText("🚫 This message was deleted");
                messageText.setTypeface(null, android.graphics.Typeface.ITALIC);
                messageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp > 0 ? cachedTextSizeSp : 15.0f);
                int deletedTextColor = isDarkBubble ? 0xD9F8FAFC : 0xDD0F172A;
                messageText.setTextColor(deletedTextColor);
                if (timeText != null) {
                    timeText.setText(time);
                    timeText.setTextColor(isDarkBubble ? 0x99F8FAFC : 0x8C0F172A);
                }
                if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                statusIcon.setVisibility(View.GONE);
                reactionsText.setVisibility(View.GONE);
                if (replyQuoteContainer != null) replyQuoteContainer.setVisibility(View.GONE);
            } else if (message.isBlink()) {
                hideAllMediaContainers();
                ensureBlinkViews();
                hasVisibleMediaContainer = true;
                messageText.setVisibility(View.GONE);
                if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                statusIcon.setVisibility(View.VISIBLE);
                if (!"call".equals(message.getType())) {
                    updateStatusIcon(statusIcon, message, isDarkBubble);
                }
                bindBlinkCard(message, true, blinkMessageContainer, blinkBadgeIcon, blinkProgressBar, blinkTitleText, blinkSubtitleText);
                itemView.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onMessageLongClick(message, itemView);
                        return true;
                    }
                    return false;
                });
            } else {
                hideAllMediaContainers();

                if ("voice".equals(message.getType()) || "audio".equals(message.getType())) {
                    ensureVoiceViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    if (voiceMessageContainer != null) {
                        voiceMessageContainer.setVisibility(View.VISIBLE);
                        File localAudio = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".m4a");
                        boolean hasLocal = (localAudio != null && localAudio.exists() && localAudio.length() > 0)
                                || (message.getLocalFilePath() != null && new File(message.getLocalFilePath()).exists());
                        if (!hasLocal && message.getMediaUrl() != null && !message.getMediaUrl().isEmpty()) {
                            File inlineAudio = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(itemView.getContext(), message.getChatId(), message);
                            if (inlineAudio != null && inlineAudio.exists() && inlineAudio.length() > 0) {
                                localAudio = inlineAudio;
                                hasLocal = true;
                            }
                        }

                        if (voiceWaveformView != null) {
                            int playedColor = itemView.getContext().getResources().getColor(R.color.voice_waveform_played);
                            int unplayedColor = itemView.getContext().getResources().getColor(R.color.voice_waveform_unplayed);
                            voiceWaveformView.setColors(playedColor, unplayedColor);
                            voiceWaveformView.setWaveformSeed(message.getId());
                            voiceWaveformView.setOnSeekListener((progress, fromUser) -> {
                                if (fromUser) VoicePlayerManager.getInstance().seekToFraction(progress);
                            });
                        }

                        int totalMs = VoicePlayerManager.getInstance().getCachedAudioDuration(message);
                        if (voiceDurationText != null) {
                            voiceDurationText.setText(totalMs > 0 ? VoicePlayerManager.formatDuration(totalMs) : "0:00");
                        }
                        if (totalMs <= 0) {
                            final String boundMsgId = message.getId();
                            VoicePlayerManager.getInstance().loadAudioDurationAsync(itemView.getContext(), message, durationMs -> {
                                if (durationMs > 0 && voiceDurationText != null && boundMsgId != null && boundMsgId.equals(message.getId())) {
                                    voiceDurationText.setText(VoicePlayerManager.formatDuration(durationMs));
                                }
                            });
                        }

                        if (voiceSpeedButton != null) {
                            float speed = VoicePlayerManager.getInstance().getSpeed();
                            voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", speed).replace(".0", ""));
                            voiceSpeedButton.setOnClickListener(v -> {
                                float nextSpeed = VoicePlayerManager.getInstance().cycleSpeed();
                                voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", nextSpeed).replace(".0", ""));
                            });
                        }

                        int voiceSendState = downloadStates.getOrDefault(message.getId(), STATE_IDLE);
                        final int voiceSendProg = downloadProgresses.getOrDefault(message.getId(), 0);
                        boolean isVoiceCanceled = com.m3.veil.webrtc.P2PFileTransferHelper.isTransferCanceled(message.getId());
                        boolean isVoiceOffline = !com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).isNetworkConnected();
                        if (voiceSendProg >= 100 || message.isRead() || (voiceSendState != STATE_DOWNLOADING && (!message.isPending() || message.isDelivered()))) {
                            if (voiceSendState == STATE_DOWNLOADING) {
                                voiceSendState = STATE_DOWNLOADED;
                                downloadStates.put(message.getId(), STATE_DOWNLOADED);
                            }
                        }
                        boolean isVoiceRetry = (voiceSendState == STATE_RETRY)
                                || isVoiceCanceled
                                || (message.isPending() && !message.isDelivered() && !message.isRead() && isVoiceOffline);
                        boolean isVoiceSending = !isVoiceRetry && ((message.isPending() && !message.isDelivered() && !message.isRead())
                                || (voiceSendState == STATE_DOWNLOADING && voiceSendProg < 100)
                                || (!hasLocal && (message.getMediaUrl() == null || message.getMediaUrl().isEmpty())));

                        if (isVoiceRetry) {
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.GONE);
                            if (voiceActionButton != null) {
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setImageResource(R.drawable.ic_undo);
                                voiceActionButton.setOnClickListener(v -> {
                                    if (mediaDownloadClickListener != null) {
                                        mediaDownloadClickListener.onMediaRetryClick(message);
                                    }
                                });
                            }
                            if (voiceDurationText != null) {
                                voiceDurationText.setText("Tap to retry");
                            }
                        } else if (isVoiceSending) {
                            int shownPct = Math.max(1, Math.min(99, voiceSendProg > 0 ? voiceSendProg : 1));
                            if (voiceProgressBar != null) {
                                voiceProgressBar.setVisibility(View.VISIBLE);
                                voiceProgressBar.setIndeterminate(true);
                            }
                            if (voiceActionButton != null) {
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setImageResource(R.drawable.ic_close);
                                voiceActionButton.setOnClickListener(v -> {
                                    if (mediaDownloadClickListener != null) {
                                        mediaDownloadClickListener.onMediaCancelClick(message);
                                    }
                                });
                            }
                            if (voiceDurationText != null) {
                                voiceDurationText.setText("Sending " + shownPct + "%");
                            }
                        } else {
                            if (voiceActionButton != null) {
                                voiceActionButton.setImageResource(R.drawable.ic_play_arrow);
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setOnClickListener(v -> VoicePlayerManager.getInstance().playVoiceNote(
                                    itemView.getContext(),
                                    message,
                                    null,
                                    currentUserId
                                ));
                            }
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.GONE);

                            VoicePlayerManager.getInstance().registerListener(message.getId(), new VoicePlayerManager.PlaybackListener() {
                                @Override
                                public void onStateChanged(boolean isPlaying) {
                                    if (voiceActionButton != null) {
                                        voiceActionButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
                                    }
                                    if (!isPlaying && voiceWaveformView != null) {
                                        voiceWaveformView.setProgress(0f);
                                        int dur = VoicePlayerManager.getInstance().getCachedAudioDuration(message);
                                        if (voiceDurationText != null) {
                                            voiceDurationText.setText(dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:00");
                                        }
                                    }
                                }

                                @Override
                                public void onProgressUpdate(int currentPosition, int maxDuration) {
                                    if (voiceWaveformView != null && maxDuration > 0) {
                                        voiceWaveformView.setProgress((float) currentPosition / maxDuration);
                                    }
                                    if (voiceDurationText != null) {
                                        voiceDurationText.setText(VoicePlayerManager.formatDuration(currentPosition) + " / " + VoicePlayerManager.formatDuration(maxDuration));
                                    }
                                }

                                @Override
                                public void onSpeedChanged(float speed) {
                                    if (voiceSpeedButton != null) {
                                        voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", speed).replace(".0", ""));
                                    }
                                }
                            });

                            boolean isPlaying = VoicePlayerManager.getInstance().isPlaying(message.getId());
                            if (voiceActionButton != null) {
                                voiceActionButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
                            }
                        }
                    }
                } else if (com.m3.veil.utils.VeilStickerGifEngine.isAnimatedEmojiMessage(message)) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (messageBubble != null) {
                        messageBubble.setPadding(0, 0, 0, 0);
                        messageBubble.setBackground(null);
                    }
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        imageMessageContainer.setPadding(0, 0, 0, 0);
                        imageMessageContainer.setBackground(null);
                        bindGroupedAlbumGridOverlay(itemView.getContext(), imageCardView, null, "You", time, true);
                        bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, null, true);
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setStrokeWidth(0);
                            mcv.setCardElevation(0f);
                            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                            mcv.setBackground(null);
                            mcv.setRadius(0f);
                        }
                        if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        if (imageGifBadge != null) imageGifBadge.setVisibility(View.GONE);
                        if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);
                        if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                        if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                        if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);

                        int[] emojiDims = com.m3.veil.utils.VeilStickerGifEngine.computeAnimatedEmojiSizePx(itemView.getContext(), message);
                        com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, emojiDims[0], emojiDims[1]);

                        if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        if (statusIcon != null) statusIcon.setVisibility(View.VISIBLE);
                        bindQuickForwardButton(btnQuickForwardMedia, message, true);

                        if (messageImage != null) {
                            messageImage.setVisibility(View.VISIBLE);
                            messageImage.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                            com.m3.veil.utils.VeilStickerGifEngine.bindAnimatedEmojiToImageView(itemView.getContext(), message, messageImage);
                        }

                        View.OnClickListener emojiBurstClick = v -> {
                            if (messageImage != null) {
                                int combo = 1;
                                android.graphics.drawable.Drawable d = messageImage.getDrawable();
                                if (d instanceof com.m3.veil.ui.views.VeilAnimatedEmojiDrawable) {
                                    combo = ((com.m3.veil.ui.views.VeilAnimatedEmojiDrawable) d).triggerTapBurst();
                                }
                                v.performHapticFeedback(combo >= 3
                                        ? android.view.HapticFeedbackConstants.LONG_PRESS
                                        : android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                float peakScale = Math.min(1.36f, 1.20f + combo * 0.03f);
                                messageImage.animate().scaleX(peakScale).scaleY(peakScale).setDuration(110)
                                        .withEndAction(() -> messageImage.animate().scaleX(1.0f).scaleY(1.0f).setDuration(170).start())
                                        .start();
                            }
                        };
                        imageMessageContainer.setOnClickListener(emojiBurstClick);
                        if (imageCardView != null) imageCardView.setOnClickListener(emojiBurstClick);
                    }
                } else if ("video_note".equalsIgnoreCase(message.getType())) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (messageBubble != null) {
                        messageBubble.setPadding(0, 0, 0, 0);
                        messageBubble.setBackground(null);
                    }
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        imageMessageContainer.setPadding(0, 0, 0, 0);
                        imageMessageContainer.setBackground(null);
                        float dp = itemView.getContext().getResources().getDisplayMetrics().density;
                        int sizePx = Math.round(180 * dp);
                        com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, sizePx, sizePx);
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setRadius(sizePx / 2f);
                            mcv.setStrokeWidth(Math.round(2.5f * dp));
                            mcv.setStrokeColor(android.graphics.Color.parseColor("#38BDF8"));
                            mcv.setCardBackgroundColor(android.graphics.Color.BLACK);
                            mcv.setCardElevation(2 * dp);
                        }
                        if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        if (imageGifBadge != null) imageGifBadge.setVisibility(View.GONE);
                        if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);
                        if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                        if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                        if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);

                        if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        if (statusIcon != null) statusIcon.setVisibility(View.VISIBLE);
                        bindQuickForwardButton(btnQuickForwardMedia, message, true);

                        if (messageImage != null) {
                            messageImage.setVisibility(View.VISIBLE);
                            messageImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            bindVideoThumbnailBitmap(itemView.getContext(), messageImage, message,
                                    message.getLocalFilePath() != null ? new File(message.getLocalFilePath()) : null);
                        }

                        View.OnClickListener videoNoteClick = v -> {
                            String path = message.getLocalFilePath();
                            if (path != null && !path.isEmpty()) {
                                Intent intent = buildMediaViewerAlbumIntent(
                                        itemView.getContext(), message, path, "video", "You", time, "", 0);
                                itemView.getContext().startActivity(intent);
                            }
                        };
                        imageMessageContainer.setOnClickListener(videoNoteClick);
                        if (imageCardView != null) imageCardView.setOnClickListener(videoNoteClick);
                    }
                } else if (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(message)) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    boolean stickerHasReply = message.getReplyToMessageId() != null && !message.getReplyToMessageId().isEmpty();
                    if (messageBubble != null && !stickerHasReply) {
                        messageBubble.setPadding(0, 0, 0, 0);
                    }
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        imageMessageContainer.setPadding(0, 0, 0, 0);
                        imageMessageContainer.setBackground(null);
                        bindGroupedAlbumGridOverlay(itemView.getContext(), imageCardView, null, "You", time, false);
                        Message secondPairedSticker = getPairedSecondSticker(msgIdx);
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setStrokeWidth(0);
                            mcv.setCardElevation(0f);
                            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                            mcv.setBackground(null);
                            mcv.setRadius(0f);
                        }
                        if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        if (imageGifBadge != null) imageGifBadge.setVisibility(View.GONE);
                        if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);
                        if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                        if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                        if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);

                        if (secondPairedSticker != null) {
                            // 2-Column Side-by-Side Consecutive Sticker Pair (sticker_second_Screenshot_9e6f15cb.jpg)
                            if (messageImage != null) messageImage.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                            bindQuickForwardButton(btnQuickForwardMedia, message, false);
                            bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, secondPairedSticker, true);
                        } else {
                            // Single chromeless sticker with floating pill below & circular forward button (sticker_first_screenshot_87646071.jpg)
                            bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, null, true);
                            int[] stickerDims = com.m3.veil.utils.VeilStickerGifEngine.computeStickerSizePx(itemView.getContext(), message);
                            com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, stickerDims[0], stickerDims[1]);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                            if (statusIcon != null) statusIcon.setVisibility(View.VISIBLE);
                            bindQuickForwardButton(btnQuickForwardMedia, message, true);
                            if (messageImage != null) {
                                messageImage.setVisibility(View.VISIBLE);
                                messageImage.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                                com.m3.veil.utils.VeilStickerGifEngine.bindStickerToImageView(itemView.getContext(), message, messageImage);
                            }
                            View.OnClickListener stickerBounce = v -> {
                                if (messageImage != null) {
                                    com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(messageImage);
                                    messageImage.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140)
                                            .withEndAction(() -> messageImage.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                                            .start();
                                }
                            };
                            imageMessageContainer.setOnClickListener(stickerBounce);
                            if (imageCardView != null) imageCardView.setOnClickListener(stickerBounce);
                        }
                    }
                } else if ("image".equals(message.getType()) || "gif".equals(message.getType())
                        || com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(message, null)
                        || extractAlbumItems(message) != null) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, null, true);
                        String primaryExt = "gif".equals(message.getType()) ? ".gif" : ("video".equals(message.getType()) ? ".mp4" : ".jpg");
                        File localSentMedia = FileMediaManager.getDecryptedFile(
                                itemView.getContext(), message, primaryExt);
                        boolean isGif = com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(message, localSentMedia);
                        if (imageGifBadge != null) {
                            imageGifBadge.setVisibility(isGif ? View.VISIBLE : View.GONE);
                            if (isGif) {
                                styleWhatsAppCenteredGifBadge(imageGifBadge);
                            }
                        }
                        boolean isAlbumCollage = extractAlbumItems(message) != null;
                        float dp = itemView.getContext().getResources().getDisplayMetrics().density;
                        if (messageBubble != null) {
                            if (isAlbumCollage) {
                                int pSide = Math.round(4.5f * dp);
                                int pTop = Math.round(4.5f * dp);
                                int pBot = Math.round(5f * dp);
                                messageBubble.setPadding(pSide, pTop, pSide, pBot);
                            } else if (isGif) {
                                int pGif = Math.round(3.5f * dp);
                                messageBubble.setPadding(pGif, pGif, pGif, pGif);
                            } else {
                                int pSide = Math.round(8f * dp);
                                int pVert = Math.round(6f * dp);
                                messageBubble.setPadding(pSide, pVert, pSide, pVert);
                            }
                        }
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setStrokeWidth(0);
                            mcv.setCardElevation(0f);
                            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                            mcv.setRadius(isAlbumCollage ? 0f : (isGif ? (10f * dp) : (12f * dp)));
                        }
                        if (isAlbumCollage) {
                            if (messageImage != null) messageImage.setVisibility(View.GONE);
                            if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        } else if (isGif) {
                            if (messageImage != null) messageImage.setVisibility(View.VISIBLE);
                            int[] gifDims = com.m3.veil.utils.VeilStickerGifEngine.computeGifSizePx(
                                    itemView.getContext(), message, localSentMedia);
                            com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, gifDims[0], gifDims[1]);
                            if (imageScrimView != null) imageScrimView.setVisibility(View.VISIBLE);
                        } else {
                            if (messageImage != null) messageImage.setVisibility(View.VISIBLE);
                            if (imageScrimView != null) imageScrimView.setVisibility(View.VISIBLE);
                            com.m3.veil.utils.VeilStickerGifEngine.applyDynamicMediaCardSize(
                                     itemView.getContext(), imageCardView, imageCaptionText, message, localSentMedia);
                        }
                        if (messageImage != null && !isAlbumCollage) {
                            messageImage.setBackgroundColor(isGif ? android.graphics.Color.TRANSPARENT : 0x15000000);
                            messageImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        }

                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[image")
                                && !message.getText().startsWith("[video")
                                && !message.getText().startsWith("[gif")
                                && !message.getText().startsWith("[GIF:")
                                && !message.getText().equals("[image message]")
                                && !message.getText().equals("[video message]");

                        bindGroupedAlbumGridOverlay(itemView.getContext(), imageCardView, message, "You", time, hasCaption);
                        bindQuickForwardButton(btnQuickForwardMedia, message, true);

                        if (hasCaption) {
                            if (imageCaptionText != null) {
                                imageCaptionText.setVisibility(View.VISIBLE);
                                if (imageCaptionText instanceof VeilAdvancedTextView) {
                                    ((VeilAdvancedTextView) imageCaptionText).renderAdvancedText(
                                            message.getText(), 14.5f, true, isDarkBubble, entityClickListener);
                                } else {
                                    imageCaptionText.setText(message.getText());
                                }
                            }
                            if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else if (isAlbumCollage) {
                            // WhatsApp grouped collage bubble (Screenshot1_bada0f2a.jpg): single footer row below grid inside bubble
                            if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                            if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                            if (statusIcon != null) statusIcon.setVisibility(View.VISIBLE);
                        } else {
                            // Both standard single photos AND WhatsApp GIF bubbles ("KYA?" in sticker_second_Screenshot_9e6f15cb.jpg)
                            // show bottom-right overlaid timestamp inside the media frame!
                            if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                            if (imageOverlayPill != null) {
                                imageOverlayPill.setVisibility(View.VISIBLE);
                                if (ivImagePinnedBadge != null) ivImagePinnedBadge.setVisibility(message.isPinned() ? View.VISIBLE : View.GONE);
                                if (imageTimeText != null) imageTimeText.setText(time);
                                if (imageStatusIcon != null) updateOverlayStatusIcon(imageStatusIcon, message);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        boolean handledGif = isGif && com.m3.veil.utils.VeilStickerGifEngine.bindGifToImageView(
                                itemView.getContext(), message, localSentMedia, messageImage, imageProgressBar);
                        if (!handledGif && !isAlbumCollage) {
                            if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
                                Bitmap thumb = BlurUtils.getBlurredThumbnail(itemView.getContext(), message.getThumbnail());
                                if (thumb != null) {
                                    messageImage.setImageBitmap(thumb);
                                } else {
                                    byte[] decodedString = Base64.decode(message.getThumbnail(), Base64.DEFAULT);
                                    BitmapFactory.Options opts = new BitmapFactory.Options();
                                    opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                                    Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length, opts);
                                    if (decodedByte != null) {
                                        messageImage.setImageBitmap(decodedByte);
                                    } else {
                                        messageImage.setImageDrawable(null);
                                    }
                                }
                            } else {
                                messageImage.setImageDrawable(null);
                            }

                            ImageLoader.loadImage(
                                itemView.getContext(),
                                message,
                                null,
                                currentUserId,
                                messageImage,
                                imageProgressBar
                            );
                        } else if (isAlbumCollage && imageProgressBar != null) {
                            imageProgressBar.setVisibility(View.GONE);
                        }

                        View.OnClickListener openPhoto = v -> {
                            if (isGif && messageImage != null && messageImage.getDrawable() instanceof android.graphics.drawable.Animatable) {
                                android.graphics.drawable.Animatable anim = (android.graphics.drawable.Animatable) messageImage.getDrawable();
                                if (!anim.isRunning()) {
                                    com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(messageImage);
                                    return;
                                }
                            }
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, primaryExt);
                            String path = (local != null && local.exists()) ? local.getAbsolutePath() : message.getLocalFilePath();
                            org.json.JSONArray alb = extractAlbumItems(message);
                            if ((path == null || path.isEmpty()) && alb != null && alb.length() > 0) {
                                org.json.JSONObject firstObj = alb.optJSONObject(0);
                                if (firstObj != null) path = firstObj.optString("path", "");
                            }
                            if (path != null && !path.isEmpty()) {
                                Intent intent = buildMediaViewerAlbumIntent(
                                        itemView.getContext(), message, path,
                                        isGif ? "gif" : ("video".equals(message.getType()) ? "video" : "image"),
                                        "You", time, hasCaption ? message.getText() : "", 0);
                                itemView.getContext().startActivity(intent);
                            } else if (isGif && messageImage != null) {
                                com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(messageImage);
                            }
                        };
                        imageMessageContainer.setOnClickListener(openPhoto);
                        if (imageCardView != null) imageCardView.setOnClickListener(openPhoto);

                        boolean isInlinePresetGif = message.getText() != null && message.getText().trim().startsWith("[GIF:");
                        int dlState = downloadStates.getOrDefault(message.getId(), STATE_IDLE);
                        final int progress = downloadProgresses.getOrDefault(message.getId(), 0);
                        boolean isImgCanceled = com.m3.veil.webrtc.P2PFileTransferHelper.isTransferCanceled(message.getId());
                        boolean isImgOffline = !com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).isNetworkConnected();
                        if (isInlinePresetGif || progress >= 100 || message.isRead() || (dlState != STATE_DOWNLOADING && (!message.isPending() || message.isDelivered()))) {
                            if (dlState == STATE_DOWNLOADING) {
                                dlState = STATE_DOWNLOADED;
                                downloadStates.put(message.getId(), STATE_DOWNLOADED);
                            }
                        }
                        final boolean isImgRetry = !isInlinePresetGif && ((dlState == STATE_RETRY)
                                || isImgCanceled
                                || (message.isPending() && !message.isDelivered() && !message.isRead() && isImgOffline));
                        final boolean isSending = !isInlinePresetGif && !isImgRetry && ((message.isPending() && !message.isDelivered() && !message.isRead())
                                || (dlState == STATE_DOWNLOADING && progress < 100));

                        if (imageDownloadOverlay != null) {
                            if (isImgRetry) {
                                imageDownloadOverlay.setVisibility(View.VISIBLE);
                                if (imageDownloadIdleLayout != null) imageDownloadIdleLayout.setVisibility(View.GONE);
                                if (imageDownloadingLayout != null) imageDownloadingLayout.setVisibility(View.GONE);
                                if (imageDownloadRetryLayout != null) {
                                    imageDownloadRetryLayout.setVisibility(View.VISIBLE);
                                    imageDownloadRetryLayout.setOnClickListener(v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaRetryClick(message);
                                        }
                                    });
                                }
                                if (imageTimeText != null) {
                                    imageTimeText.setText("Retry • " + time);
                                }
                            } else if (isSending) {
                                int shownPct = Math.max(1, Math.min(99, progress > 0 ? progress : 1));
                                imageDownloadOverlay.setVisibility(View.VISIBLE);
                                if (imageDownloadingLayout != null) {
                                    imageDownloadingLayout.setVisibility(View.VISIBLE);
                                    bindCircularOverlayPercent(imageDownloadingLayout, imageDownloadCancelBtn, shownPct);
                                    View.OnClickListener cancelImgClick = v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaCancelClick(message);
                                        }
                                    };
                                    imageDownloadingLayout.setOnClickListener(cancelImgClick);
                                    if (imageDownloadCancelBtn != null) {
                                        imageDownloadCancelBtn.setOnClickListener(cancelImgClick);
                                    }
                                }
                                if (imageDownloadIdleLayout != null) imageDownloadIdleLayout.setVisibility(View.GONE);
                                if (imageDownloadRetryLayout != null) imageDownloadRetryLayout.setVisibility(View.GONE);
                                if (imageDownloadProgress != null) {
                                    imageDownloadProgress.setIndeterminate(true);
                                }
                                if (imageTimeText != null) {
                                    imageTimeText.setText(shownPct + "% • " + time);
                                }
                            } else {
                                imageDownloadOverlay.setVisibility(View.GONE);
                            }
                        }
                    }
                } else if ("video".equals(message.getType())) {
                    ensureVideoViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    bindQuickForwardButton(btnQuickForwardMedia, message, true);
                    if (videoMessageContainer != null) {
                        videoMessageContainer.setVisibility(View.VISIBLE);
                        File localSentVid = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                        com.m3.veil.utils.VeilStickerGifEngine.applyDynamicMediaCardSize(
                                itemView.getContext(), videoCardView, videoCaptionText, message, localSentVid);
                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[video")
                                && !message.getText().equals("[video message]");

                        if (hasCaption) {
                            if (videoCaptionText != null) {
                                videoCaptionText.setVisibility(View.VISIBLE);
                                if (videoCaptionText instanceof VeilAdvancedTextView) {
                                    ((VeilAdvancedTextView) videoCaptionText).renderAdvancedText(
                                            message.getText(), 14.5f, true, isDarkBubble, entityClickListener);
                                } else {
                                    videoCaptionText.setText(message.getText());
                                }
                            }
                            if (videoOverlayPill != null) videoOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            if (videoCaptionText != null) videoCaptionText.setVisibility(View.GONE);
                            if (videoOverlayPill != null) {
                                videoOverlayPill.setVisibility(View.VISIBLE);
                                if (ivVideoPinnedBadge != null) ivVideoPinnedBadge.setVisibility(message.isPinned() ? View.VISIBLE : View.GONE);
                                if (videoTimeText != null) videoTimeText.setText(time);
                                if (videoStatusIcon != null) updateOverlayStatusIcon(videoStatusIcon, message);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        int dur = VoicePlayerManager.getInstance().getCachedAudioDuration(message);
                        if (videoDurationText != null) {
                            videoDurationText.setText(dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30");
                        }
                        if (dur <= 0) {
                            final String boundVidId = message.getId();
                            VoicePlayerManager.getInstance().loadAudioDurationAsync(itemView.getContext(), message, durationMs -> {
                                if (durationMs > 0 && videoDurationText != null && boundVidId != null && boundVidId.equals(message.getId())) {
                                    videoDurationText.setText(VoicePlayerManager.formatDuration(durationMs));
                                }
                            });
                        }

                        bindVideoThumbnailBitmap(itemView.getContext(), messageVideoThumbnail, message, localSentVid);

                        View.OnClickListener playClick = v -> {
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                            if (local != null && local.exists()) {
                                Intent intent = buildMediaViewerAlbumIntent(
                                        itemView.getContext(), message, local.getAbsolutePath(), "video",
                                        "You", time, hasCaption ? message.getText() : "", 0);
                                itemView.getContext().startActivity(intent);
                            } else {
                                if (videoPlayIcon != null) videoPlayIcon.setVisibility(View.GONE);
                                if (videoProgressBar != null) videoProgressBar.setVisibility(View.VISIBLE);
                                FileMediaManager.downloadAndDecryptMedia(itemView.getContext(), message, ".mp4", new FileMediaManager.DownloadCallback() {
                                    @Override
                                    public void onSuccess(File decryptedFile) {
                                        if (videoPlayIcon != null) videoPlayIcon.setVisibility(View.VISIBLE);
                                        if (videoProgressBar != null) videoProgressBar.setVisibility(View.GONE);
                                        Intent intent = buildMediaViewerAlbumIntent(
                                                itemView.getContext(), message, decryptedFile.getAbsolutePath(), "video",
                                                "You", time, hasCaption ? message.getText() : "", 0);
                                        itemView.getContext().startActivity(intent);
                                    }
                                    @Override
                                    public void onProgress(boolean inProgress) {
                                        if (videoPlayIcon != null) videoPlayIcon.setVisibility(inProgress ? View.GONE : View.VISIBLE);
                                        if (videoProgressBar != null) videoProgressBar.setVisibility(inProgress ? View.VISIBLE : View.GONE);
                                    }
                                    @Override
                                    public void onError(Exception e) {
                                        if (videoPlayIcon != null) videoPlayIcon.setVisibility(View.VISIBLE);
                                        if (videoProgressBar != null) videoProgressBar.setVisibility(View.GONE);
                                        Toast.makeText(itemView.getContext(), "Unable to open video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        };
                        videoMessageContainer.setOnClickListener(playClick);
                        if (videoPlayButtonContainer != null) videoPlayButtonContainer.setOnClickListener(playClick);
                        if (videoCardView != null) videoCardView.setOnClickListener(playClick);

                        int vDlState = downloadStates.getOrDefault(message.getId(), STATE_IDLE);
                        final int vProgress = downloadProgresses.getOrDefault(message.getId(), 0);
                        boolean isVidCanceled = com.m3.veil.webrtc.P2PFileTransferHelper.isTransferCanceled(message.getId());
                        boolean isVidOffline = !com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).isNetworkConnected();
                        if (vProgress >= 100 || message.isRead() || (vDlState != STATE_DOWNLOADING && (!message.isPending() || message.isDelivered()))) {
                            if (vDlState == STATE_DOWNLOADING) {
                                vDlState = STATE_DOWNLOADED;
                                downloadStates.put(message.getId(), STATE_DOWNLOADED);
                            }
                        }
                        final boolean isVideoRetry = (vDlState == STATE_RETRY)
                                || isVidCanceled
                                || (message.isPending() && !message.isDelivered() && !message.isRead() && isVidOffline);
                        final boolean isVideoSending = !isVideoRetry && ((message.isPending() && !message.isDelivered() && !message.isRead())
                                || (vDlState == STATE_DOWNLOADING && vProgress < 100));

                        if (videoDownloadOverlay != null) {
                            if (isVideoRetry) {
                                videoDownloadOverlay.setVisibility(View.VISIBLE);
                                if (videoDownloadIdleLayout != null) videoDownloadIdleLayout.setVisibility(View.GONE);
                                if (videoDownloadingLayout != null) videoDownloadingLayout.setVisibility(View.GONE);
                                if (videoPlayButtonContainer != null) videoPlayButtonContainer.setVisibility(View.GONE);
                                if (videoDownloadRetryLayout != null) {
                                    videoDownloadRetryLayout.setVisibility(View.VISIBLE);
                                    videoDownloadRetryLayout.setOnClickListener(v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaRetryClick(message);
                                        }
                                    });
                                }
                                if (videoDurationText != null) {
                                    String durStr = dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30";
                                    videoDurationText.setText("Retry • " + durStr);
                                }
                            } else if (isVideoSending) {
                                int shownPct = Math.max(1, Math.min(99, vProgress > 0 ? vProgress : 1));
                                videoDownloadOverlay.setVisibility(View.VISIBLE);
                                if (videoDownloadingLayout != null) {
                                    videoDownloadingLayout.setVisibility(View.VISIBLE);
                                    bindCircularOverlayPercent(videoDownloadingLayout, videoDownloadCancelBtn, shownPct);
                                    View.OnClickListener cancelVidClick = v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaCancelClick(message);
                                        }
                                    };
                                    videoDownloadingLayout.setOnClickListener(cancelVidClick);
                                    if (videoDownloadCancelBtn != null) {
                                        videoDownloadCancelBtn.setOnClickListener(cancelVidClick);
                                    }
                                }
                                if (videoDownloadIdleLayout != null) videoDownloadIdleLayout.setVisibility(View.GONE);
                                if (videoDownloadRetryLayout != null) videoDownloadRetryLayout.setVisibility(View.GONE);
                                if (videoPlayButtonContainer != null) videoPlayButtonContainer.setVisibility(View.GONE);
                                if (videoDownloadProgress != null) {
                                    videoDownloadProgress.setIndeterminate(true);
                                }
                                if (videoDurationText != null) {
                                    String durStr = dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30";
                                    videoDurationText.setText(shownPct + "% • " + durStr);
                                }
                            } else {
                                videoDownloadOverlay.setVisibility(View.GONE);
                                if (videoPlayButtonContainer != null) videoPlayButtonContainer.setVisibility(View.VISIBLE);
                            }
                        }
                    }
                } else if ("document".equals(message.getType())) {
                    ensureDocumentViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (documentMessageContainer != null) {
                        documentMessageContainer.setVisibility(View.VISIBLE);
                        String raw = message.getText() != null ? message.getText() : "Document";
                        String name = raw;
                        String size = "";
                        if (raw.contains("|")) {
                            String[] p = raw.split("\\|", 2);
                            name = p[0];
                            size = p[1];
                        }
                        final String cleanName = com.m3.veil.manager.VeilMessagesController.cleanDocumentFileName(name);
                        documentFileName.setText(cleanName);

                        String ext = "DOC";
                        int dot = cleanName.lastIndexOf(".");
                        if (dot != -1 && dot < cleanName.length() - 1) {
                            ext = cleanName.substring(dot + 1).toUpperCase(Locale.ROOT);
                            if (ext.length() > 4) ext = ext.substring(0, 4);
                        }

                        if (documentExtensionBadge != null) {
                            documentExtensionBadge.setText(ext);
                            documentExtensionBadge.setVisibility(View.VISIBLE);
                        }
                        styleDocumentBubbleCard(
                                itemView.getContext(),
                                documentMessageContainer,
                                documentIconContainer,
                                documentExtensionBadge,
                                documentFileName,
                                documentFileSize,
                                documentActionIcon,
                                documentProgressBar,
                                ext,
                                isDarkBubble,
                                true
                        );
                        if (size.isEmpty()) {
                            String fallbackSize = message.getFormattedMediaSize();
                            if (!fallbackSize.isEmpty()) size = fallbackSize;
                        }
                        String formattedInfo = ext + (size.isEmpty() ? "" : " • " + size);
                        documentFileSize.setText(formattedInfo);
                        int docDlState = downloadStates.getOrDefault(message.getId(), STATE_IDLE);
                        final int docProgress = downloadProgresses.getOrDefault(message.getId(), 0);
                        boolean isDocCanceled = com.m3.veil.webrtc.P2PFileTransferHelper.isTransferCanceled(message.getId());
                        boolean isDocOffline = !com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).isNetworkConnected();
                        if (docProgress >= 100 || message.isRead() || (docDlState != STATE_DOWNLOADING && (!message.isPending() || message.isDelivered()))) {
                            if (docDlState == STATE_DOWNLOADING) {
                                docDlState = STATE_DOWNLOADED;
                                downloadStates.put(message.getId(), STATE_DOWNLOADED);
                            }
                        }
                        final boolean isDocRetry = (docDlState == STATE_RETRY)
                                || isDocCanceled
                                || (message.isPending() && !message.isDelivered() && !message.isRead() && isDocOffline);
                        final boolean isDocSending = !isDocRetry && ((message.isPending() && !message.isDelivered() && !message.isRead())
                                || (docDlState == STATE_DOWNLOADING && docProgress < 100));

                        View.OnClickListener openSentDocClick = v -> {
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, "");
                            if (local == null || !local.exists() || local.length() == 0) {
                                local = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(itemView.getContext(), message.getChatId(), message);
                            }
                            if (local != null && local.exists() && local.length() > 0) {
                                FileMediaManager.openDocumentWithOriginalName(itemView.getContext(), local, cleanName);
                            } else if (mediaDownloadClickListener != null) {
                                mediaDownloadClickListener.onMediaDownloadClick(message);
                            }
                        };

                        if (documentProgressBar != null) {
                            if (isDocRetry) {
                                documentProgressBar.setVisibility(View.GONE);
                                documentFileSize.setText(formattedInfo + " • Tap to retry");
                                if (documentActionIcon != null) {
                                    documentActionIcon.setVisibility(View.VISIBLE);
                                    documentActionIcon.setImageResource(R.drawable.ic_undo);
                                    documentActionIcon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.statusError));
                                    documentActionIcon.setOnClickListener(v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaRetryClick(message);
                                        }
                                    });
                                }
                                documentMessageContainer.setOnClickListener(v -> {
                                    if (mediaDownloadClickListener != null) {
                                        mediaDownloadClickListener.onMediaRetryClick(message);
                                    }
                                });
                            } else if (isDocSending) {
                                int shownPct = Math.max(1, Math.min(99, docProgress > 0 ? docProgress : 1));
                                documentProgressBar.setVisibility(View.VISIBLE);
                                documentProgressBar.setIndeterminate(false);
                                documentProgressBar.setMax(100);
                                documentProgressBar.setProgress(shownPct);
                                documentFileSize.setText(formattedInfo + " • Sending " + shownPct + "%");
                                if (documentActionIcon != null) {
                                    documentActionIcon.setVisibility(View.VISIBLE);
                                    documentActionIcon.setImageResource(R.drawable.ic_close);
                                    documentActionIcon.setColorFilter(isDarkBubble ? 0xFFF8FAFC : ContextCompat.getColor(itemView.getContext(), R.color.message_text_primary));
                                    documentActionIcon.setOnClickListener(v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaCancelClick(message);
                                        }
                                    });
                                }
                                documentMessageContainer.setOnClickListener(openSentDocClick);
                            } else {
                                documentProgressBar.setVisibility(View.GONE);
                                if (documentActionIcon != null) {
                                    documentActionIcon.setVisibility(View.VISIBLE);
                                    documentActionIcon.setImageResource(R.drawable.ic_check);
                                    documentActionIcon.setColorFilter(isDarkBubble ? 0xFF34D399 : ContextCompat.getColor(itemView.getContext(), R.color.statusSuccess));
                                    documentActionIcon.setOnClickListener(openSentDocClick);
                                }
                                documentMessageContainer.setOnClickListener(openSentDocClick);
                            }
                        }
                    }
                } else if ("location".equals(message.getType())) {
                    ensureLocationViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    bindLocationCard(message, locationMessageContainer, locationTitle, locationSubtitle);
                } else if ("contact".equals(message.getType())) {
                    ensureContactViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (contactMessageContainer != null) {
                        contactMessageContainer.setVisibility(View.VISIBLE);
                        String raw = message.getText() != null ? message.getText() : "Contact\n";
                        String[] parts = raw.split("\n", 2);
                        final String cName = parts[0];
                        final String phone = parts.length > 1 ? parts[1] : "";
                        contactName.setText(cName);
                        contactPhone.setText(phone.isEmpty() ? "No phone" : phone);

                        View.OnClickListener dialClick = v -> FileMediaManager.openContactDialer(itemView.getContext(), phone);
                        View.OnClickListener saveClick = v -> FileMediaManager.saveContactToPhonebook(itemView.getContext(), cName, phone);

                        if (contactActionMessage != null) contactActionMessage.setOnClickListener(dialClick);
                        if (contactActionSave != null) contactActionSave.setOnClickListener(saveClick);
                        if (contactAction != null) contactAction.setOnClickListener(dialClick);
                        contactMessageContainer.setOnClickListener(dialClick);
                    }
                } else if ("poll".equals(message.getType())) {
                    ensurePollViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindPollCard(message, pollMessageContainer, pollQuestionText, pollOptionsLinearLayout, pollTotalVotesText);
                } else if ("call".equals(message.getType())) {
                    ensureCallViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindCallCard(message, callMessageContainer, callIconContainer, callIcon, callTitleText, callSubtitleText, true);
                    if (statusIcon != null) statusIcon.setVisibility(View.GONE);
                } else {
                    messageText.setVisibility(View.VISIBLE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    CharSequence formatted = formatRichTextAndPoll(itemView.getContext(), message);
                    if (messageText instanceof VeilAdvancedTextView) {
                        ((VeilAdvancedTextView) messageText).renderAdvancedText(
                                formatted,
                                cachedTextSizeSp > 0 ? cachedTextSizeSp : 15.5f,
                                true,
                                isDarkBubble,
                                entityClickListener
                        );
                    } else {
                        messageText.setText(formatted);
                    }
                    String rawMsgTxt = message.getText();
                    if (rawMsgTxt != null && (rawMsgTxt.contains("http://") || rawMsgTxt.contains("https://") || rawMsgTxt.contains("www."))) {
                        ensureLinkPreviewViews();
                        hasVisibleMediaContainer = true;
                        bindLinkPreview(rawMsgTxt, linkPreviewContainer, ivLinkThumb, tvLinkDomain, tvLinkTitle, tvLinkDesc);
                    }
                    final boolean finalIsDarkBubble = isDarkBubble;
                    if (message.getText() != null && message.getText().contains("||") && message.getId() != null) {
                        messageText.setOnClickListener(v -> {
                            if (revealedSpoilers.contains(message.getId())) {
                                revealedSpoilers.remove(message.getId());
                            } else {
                                revealedSpoilers.add(message.getId());
                            }
                            CharSequence refmt = formatRichTextAndPoll(itemView.getContext(), message);
                            if (messageText instanceof VeilAdvancedTextView) {
                                ((VeilAdvancedTextView) messageText).renderAdvancedText(
                                        refmt,
                                        cachedTextSizeSp > 0 ? cachedTextSizeSp : 15.5f,
                                        true,
                                        finalIsDarkBubble,
                                        entityClickListener
                                );
                            } else {
                                messageText.setText(refmt);
                            }
                        });
                    } else {
                        messageText.setOnClickListener(null);
                    }
                }
                
                messageText.setTypeface(null, android.graphics.Typeface.NORMAL);
                messageText.setTextColor(isDarkBubble ? 0xFFF8FAFC : ContextCompat.getColor(itemView.getContext(), R.color.message_text_primary));
                if (!(messageText instanceof VeilAdvancedTextView) && cachedTextSizeSp > 0 && messageText != null) {
                    messageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp);
                }
                if ("call".equals(message.getType())) {
                    if (statusIcon != null) statusIcon.setVisibility(View.GONE);
                } else {
                    if (statusIcon != null) {
                        statusIcon.setVisibility(View.VISIBLE);
                        updateStatusIcon(statusIcon, message, isDarkBubble);
                    }
                }
                
                if (replyQuoteContainer != null) {
                    if (message.getReplyToMessageId() != null) {
                        Message replyTo = getMessageById(message.getReplyToMessageId());
                        if (replyTo != null && !"deleted".equals(replyTo.getType())) {
                            replyQuoteContainer.setVisibility(View.VISIBLE);
                            replyQuoteName.setText(resolveReplyQuoteSenderName(message, replyTo));
                            String quoteText = replyTo.getText();
                            if ("voice".equals(replyTo.getType())) quoteText = "🎤 Voice message";
                            else if ("audio".equals(replyTo.getType())) quoteText = "🎵 Audio";
                            else if (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(replyTo)) quoteText = "✨ Sticker";
                            else if (com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(replyTo, null)) quoteText = "🎬 GIF";
                            else if ("image".equals(replyTo.getType())) quoteText = "📷 Photo";
                            else if ("video".equals(replyTo.getType())) quoteText = "🎥 Video";
                            else if ("document".equals(replyTo.getType())) {
                                String dName = replyTo.getText();
                                if (dName != null && dName.contains("|")) dName = dName.split("\\|", 2)[0];
                                quoteText = "📄 " + (dName != null ? dName : "Document");
                            } else if ("location".equals(replyTo.getType())) quoteText = "📍 Location";
                            else if ("contact".equals(replyTo.getType())) {
                                String cName = replyTo.getText();
                                if (cName != null && cName.contains("\n")) cName = cName.split("\n", 2)[0];
                                quoteText = "👤 " + (cName != null ? cName : "Contact");
                            } else if ("call".equals(replyTo.getType())) {
                                boolean isVideo = replyTo.getText() != null && replyTo.getText().toLowerCase().contains("video");
                                quoteText = (isVideo ? "📹 " : "📞 ") + (replyTo.getText() != null ? replyTo.getText() : "Call");
                            }
                            replyQuoteText.setText(quoteText);
                            replyQuoteText.setTypeface(null, android.graphics.Typeface.NORMAL);
                        } else {
                            replyQuoteContainer.setVisibility(View.VISIBLE);
                            replyQuoteName.setText(resolveReplyQuoteSenderName(message, replyTo));
                            replyQuoteText.setText("This message was deleted");
                            replyQuoteText.setTypeface(null, android.graphics.Typeface.ITALIC);
                        }
                        
                        replyQuoteContainer.setOnClickListener(v -> {
                            if (replyClickListener != null) {
                                replyClickListener.onReplyClick(message.getReplyToMessageId());
                            }
                        });
                    } else {
                        replyQuoteContainer.setVisibility(View.GONE);
                    }
                }
                
                if (!"call".equals(message.getType())) {
                    updateStatusIcon(statusIcon, message, isDarkBubble);
                }
                
                boolean hasValidReactions = false;
                StringBuilder emojis = new StringBuilder();
                if (message.getReactions() != null && !message.getReactions().isEmpty()) {
                    for (String emoji : message.getReactions().values()) {
                        if (emoji != null && !emoji.trim().isEmpty()) {
                            emojis.append(emoji.trim());
                            hasValidReactions = true;
                        }
                    }
                }
                if (hasValidReactions && emojis.length() > 0) {
                    reactionsText.setText(emojis.toString());
                    reactionsText.setVisibility(View.VISIBLE);
                    reactionsText.setOnClickListener(v -> {
                        if (reactionClickListener != null) {
                            reactionClickListener.onReactionClick(message);
                        }
                    });
                } else {
                    reactionsText.setText("");
                    reactionsText.setVisibility(View.GONE);
                    reactionsText.setOnClickListener(null);
                }
                
                applySentBubbleTheme(isDarkBubble);
                boolean isChromelessSent = (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(message)
                        || com.m3.veil.utils.VeilStickerGifEngine.isAnimatedEmojiMessage(message))
                        && (message.getReplyToMessageId() == null || message.getReplyToMessageId().isEmpty());
                applyChromelessBelowInfoStyle(bubbleInfoContainer, timeText, statusIcon, ivPinnedBadge,
                        message, isChromelessSent, true, isDarkBubble);
                if (messageBubble != null) {
                    messageBubble.requestLayout();
                    messageBubble.invalidate();
                }
                itemView.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onMessageLongClick(message, itemView);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    public class ReceivedMessageViewHolder extends RecyclerView.ViewHolder {
        View dateHeaderContainer;
        TextView dateHeaderText;
        TextView messageText, timeText, reactionsText;
        View linkPreviewContainer;
        ImageView ivLinkThumb;
        TextView tvLinkDomain, tvLinkTitle, tvLinkDesc;
        View replyQuoteContainer;
        TextView replyQuoteName, replyQuoteText;
        public View messageBubble;
        View bubbleInfoContainer;
        
        // Voice message UI
        View voiceMessageContainer;
        ImageView voiceActionButton;
        ProgressBar voiceProgressBar;
        VoiceWaveformView voiceWaveformView;
        TextView voiceDurationText;
        TextView voiceSpeedButton;
        View voiceMicBadge;
        View voiceLine;
        SeekBar voiceSeekBar;
        
        // Image / GIF / Sticker UI
        View imageMessageContainer;
        View imageCardView;
        ImageView messageImage;
        View imageScrimView;
        TextView imageGifBadge;
        ProgressBar imageProgressBar;
        View imageOverlayPill;
        TextView imageTimeText;
        TextView imageCaptionText;

        // Video UI
        View videoMessageContainer;
        View videoCardView;
        ImageView messageVideoThumbnail;
        View videoPlayButtonContainer;
        ImageView videoPlayIcon;
        ProgressBar videoProgressBar;
        View videoOverlayPill;
        TextView videoTimeText;
        View videoDurationContainer;
        TextView videoDurationText;
        TextView videoCaptionText;

        // Document UI
        View documentMessageContainer;
        View documentIconContainer;
        TextView documentExtensionBadge;
        TextView documentFileName;
        TextView documentFileSize;
        ImageView documentActionIcon;
        ProgressBar documentProgressBar;

        // Location UI
        View locationMessageContainer;
        TextView locationTitle;
        TextView locationSubtitle;

        // Contact UI
        View contactMessageContainer;
        TextView contactName;
        TextView contactPhone;
        TextView contactAction;
        TextView contactActionMessage;
        TextView contactActionSave;

        // Call UI
        View callMessageContainer;
        FrameLayout callIconContainer;
        ImageView callIcon;
        TextView callTitleText;
        TextView callSubtitleText;

        // Poll & Blink & Metadata Badges
        View pollMessageContainer;
        TextView pollQuestionText;
        android.widget.LinearLayout pollOptionsLinearLayout;
        TextView pollTotalVotesText;
        View blinkMessageContainer;
        TextView blinkBadgeIcon;
        ProgressBar blinkProgressBar;
        TextView blinkTitleText;
        TextView blinkSubtitleText;
        ImageView ivPinnedBadge;
        ImageView ivImagePinnedBadge;
        ImageView ivVideoPinnedBadge;
        TextView tvStarredBadge;
        TextView tvVanishBadge;
        TextView tvEditedBadge;
        TextView senderNameText;
        View groupHeaderDivider;

        FrameLayout imageDownloadOverlay;
        LinearLayout imageDownloadIdleLayout;
        TextView imageDownloadSizeText;
        FrameLayout imageDownloadingLayout;
        ProgressBar imageDownloadProgress;
        ImageView imageDownloadCancelBtn;
        LinearLayout imageDownloadRetryLayout;

        FrameLayout videoDownloadOverlay;
        LinearLayout videoDownloadIdleLayout;
        TextView videoDownloadSizeText;
        FrameLayout videoDownloadingLayout;
        ProgressBar videoDownloadProgress;
        ImageView videoDownloadCancelBtn;
        LinearLayout videoDownloadRetryLayout;

        ImageView btnQuickForwardMedia;
        private boolean hasVisibleMediaContainer = false;
        final VeilAdvancedTextView.OnEntityClickListener entityClickListener;

        public ReceivedMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            entityClickListener = createEntityClickListener(itemView.getContext());
            dateHeaderContainer = itemView.findViewById(R.id.dateHeaderContainer);
            dateHeaderText = itemView.findViewById(R.id.dateHeaderText);
            senderNameText = itemView.findViewById(R.id.senderNameText);
            groupHeaderDivider = itemView.findViewById(R.id.groupHeaderDivider);
            messageText = itemView.findViewById(R.id.messageText);
            timeText = itemView.findViewById(R.id.timeText);
            reactionsText = itemView.findViewById(R.id.reactionsText);
            replyQuoteContainer = itemView.findViewById(R.id.replyQuoteContainer);
            replyQuoteName = itemView.findViewById(R.id.replyQuoteName);
            replyQuoteText = itemView.findViewById(R.id.replyQuoteText);
            messageBubble = itemView.findViewById(R.id.messageBubble);
            btnQuickForwardMedia = itemView.findViewById(R.id.btnQuickForwardMedia);
            bubbleInfoContainer = itemView.findViewById(R.id.bubbleInfoContainer);
            ivPinnedBadge = itemView.findViewById(R.id.ivPinnedBadge);
            tvStarredBadge = itemView.findViewById(R.id.tvStarredBadge);
            tvVanishBadge = itemView.findViewById(R.id.tvVanishBadge);
            tvEditedBadge = itemView.findViewById(R.id.tvEditedBadge);
        }

        private void ensureLinkPreviewViews() {
            if (linkPreviewContainer == null) {
                linkPreviewContainer = itemView.findViewById(R.id.linkPreviewContainer);
                ivLinkThumb = itemView.findViewById(R.id.ivLinkThumb);
                tvLinkDomain = itemView.findViewById(R.id.tvLinkDomain);
                tvLinkTitle = itemView.findViewById(R.id.tvLinkTitle);
                tvLinkDesc = itemView.findViewById(R.id.tvLinkDesc);
            }
        }

        private void ensureVoiceViews() {
            if (voiceMessageContainer == null) {
                voiceMessageContainer = itemView.findViewById(R.id.voiceMessageContainer);
                voiceActionButton = itemView.findViewById(R.id.voiceActionButton);
                voiceProgressBar = itemView.findViewById(R.id.voiceProgressBar);
                voiceWaveformView = itemView.findViewById(R.id.voiceWaveformView);
                voiceDurationText = itemView.findViewById(R.id.voiceDurationText);
                voiceSpeedButton = itemView.findViewById(R.id.voiceSpeedButton);
                voiceMicBadge = itemView.findViewById(R.id.voiceMicBadge);
                voiceLine = itemView.findViewById(R.id.voiceLine);
                voiceSeekBar = itemView.findViewById(R.id.voiceSeekBar);
            }
        }

        private void ensureImageViews() {
            if (imageMessageContainer == null) {
                imageMessageContainer = itemView.findViewById(R.id.imageMessageContainer);
                imageCardView = itemView.findViewById(R.id.imageCardView);
                messageImage = itemView.findViewById(R.id.messageImage);
                imageScrimView = itemView.findViewById(R.id.imageScrimView);
                imageGifBadge = itemView.findViewById(R.id.imageGifBadge);
                imageProgressBar = itemView.findViewById(R.id.imageProgressBar);
                imageOverlayPill = itemView.findViewById(R.id.imageOverlayPill);
                ivImagePinnedBadge = itemView.findViewById(R.id.ivImagePinnedBadge);
                imageTimeText = itemView.findViewById(R.id.imageTimeText);
                imageCaptionText = itemView.findViewById(R.id.imageCaptionText);
                imageDownloadOverlay = itemView.findViewById(R.id.imageDownloadOverlay);
                imageDownloadIdleLayout = itemView.findViewById(R.id.imageDownloadIdleLayout);
                imageDownloadSizeText = itemView.findViewById(R.id.imageDownloadSizeText);
                imageDownloadingLayout = itemView.findViewById(R.id.imageDownloadingLayout);
                imageDownloadProgress = itemView.findViewById(R.id.imageDownloadProgress);
                imageDownloadCancelBtn = itemView.findViewById(R.id.imageDownloadCancelBtn);
                imageDownloadRetryLayout = itemView.findViewById(R.id.imageDownloadRetryLayout);
            }
        }

        private void ensureVideoViews() {
            if (videoMessageContainer == null) {
                videoMessageContainer = itemView.findViewById(R.id.videoMessageContainer);
                videoCardView = itemView.findViewById(R.id.videoCardView);
                messageVideoThumbnail = itemView.findViewById(R.id.messageVideoThumbnail);
                videoPlayButtonContainer = itemView.findViewById(R.id.videoPlayButtonContainer);
                videoPlayIcon = itemView.findViewById(R.id.videoPlayIcon);
                videoProgressBar = itemView.findViewById(R.id.videoProgressBar);
                videoOverlayPill = itemView.findViewById(R.id.videoOverlayPill);
                ivVideoPinnedBadge = itemView.findViewById(R.id.ivVideoPinnedBadge);
                videoTimeText = itemView.findViewById(R.id.videoTimeText);
                videoDurationContainer = itemView.findViewById(R.id.videoDurationContainer);
                videoDurationText = itemView.findViewById(R.id.videoDurationText);
                videoCaptionText = itemView.findViewById(R.id.videoCaptionText);
                videoDownloadOverlay = itemView.findViewById(R.id.videoDownloadOverlay);
                videoDownloadIdleLayout = itemView.findViewById(R.id.videoDownloadIdleLayout);
                videoDownloadSizeText = itemView.findViewById(R.id.videoDownloadSizeText);
                videoDownloadingLayout = itemView.findViewById(R.id.videoDownloadingLayout);
                videoDownloadProgress = itemView.findViewById(R.id.videoDownloadProgress);
                videoDownloadCancelBtn = itemView.findViewById(R.id.videoDownloadCancelBtn);
                videoDownloadRetryLayout = itemView.findViewById(R.id.videoDownloadRetryLayout);
            }
        }

        private void ensureDocumentViews() {
            if (documentMessageContainer == null) {
                documentMessageContainer = itemView.findViewById(R.id.documentMessageContainer);
                documentIconContainer = itemView.findViewById(R.id.documentIconContainer);
                documentExtensionBadge = itemView.findViewById(R.id.documentExtensionBadge);
                documentFileName = itemView.findViewById(R.id.documentFileName);
                documentFileSize = itemView.findViewById(R.id.documentFileSize);
                documentActionIcon = itemView.findViewById(R.id.documentActionIcon);
                documentProgressBar = itemView.findViewById(R.id.documentProgressBar);
            }
        }

        private void ensureLocationViews() {
            if (locationMessageContainer == null) {
                locationMessageContainer = itemView.findViewById(R.id.locationMessageContainer);
                locationTitle = itemView.findViewById(R.id.locationTitle);
                locationSubtitle = itemView.findViewById(R.id.locationSubtitle);
            }
        }

        private void ensureContactViews() {
            if (contactMessageContainer == null) {
                contactMessageContainer = itemView.findViewById(R.id.contactMessageContainer);
                contactName = itemView.findViewById(R.id.contactName);
                contactPhone = itemView.findViewById(R.id.contactPhone);
                contactAction = itemView.findViewById(R.id.contactAction);
                contactActionMessage = itemView.findViewById(R.id.contactActionMessage);
                contactActionSave = itemView.findViewById(R.id.contactActionSave);
            }
        }

        private void ensurePollViews() {
            if (pollMessageContainer == null) {
                pollMessageContainer = itemView.findViewById(R.id.pollMessageContainer);
                pollQuestionText = itemView.findViewById(R.id.pollQuestionText);
                pollOptionsLinearLayout = itemView.findViewById(R.id.pollOptionsLinearLayout);
                pollTotalVotesText = itemView.findViewById(R.id.pollTotalVotesText);
            }
        }

        private void ensureBlinkViews() {
            if (blinkMessageContainer == null) {
                blinkMessageContainer = itemView.findViewById(R.id.blinkMessageContainer);
                blinkBadgeIcon = itemView.findViewById(R.id.blinkBadgeIcon);
                blinkProgressBar = itemView.findViewById(R.id.blinkProgressBar);
                blinkTitleText = itemView.findViewById(R.id.blinkTitleText);
                blinkSubtitleText = itemView.findViewById(R.id.blinkSubtitleText);
            }
        }

        private void ensureCallViews() {
            if (callMessageContainer == null) {
                callMessageContainer = itemView.findViewById(R.id.callMessageContainer);
                callIconContainer = itemView.findViewById(R.id.callIconContainer);
                callIcon = itemView.findViewById(R.id.callIcon);
                callTitleText = itemView.findViewById(R.id.callTitleText);
                callSubtitleText = itemView.findViewById(R.id.callSubtitleText);
            }
        }

        private void hideAllMediaContainers() {
            if (!hasVisibleMediaContainer) return;
            hasVisibleMediaContainer = false;
            if (linkPreviewContainer != null) linkPreviewContainer.setVisibility(View.GONE);
            if (voiceMessageContainer != null) voiceMessageContainer.setVisibility(View.GONE);
            if (imageMessageContainer != null) imageMessageContainer.setVisibility(View.GONE);
            if (videoMessageContainer != null) videoMessageContainer.setVisibility(View.GONE);
            if (documentMessageContainer != null) documentMessageContainer.setVisibility(View.GONE);
            if (locationMessageContainer != null) locationMessageContainer.setVisibility(View.GONE);
            if (contactMessageContainer != null) contactMessageContainer.setVisibility(View.GONE);
            if (pollMessageContainer != null) pollMessageContainer.setVisibility(View.GONE);
            if (blinkMessageContainer != null) blinkMessageContainer.setVisibility(View.GONE);
            if (callMessageContainer != null) callMessageContainer.setVisibility(View.GONE);
        }

        private void applyReceivedBubbleTheme(boolean isDark) {
            Context ctx = itemView.getContext();
            float dp = ctx.getResources().getDisplayMetrics().density;
            int textPrimary = isDark ? 0xFFF8FAFC : 0xFF0F172A;
            int textSecondary = isDark ? 0xFF94A3B8 : 0xFF64748B;
            int actionColor = isDark ? 0xFF818CF8 : 0xFF4F46E5;
            int quoteStripColor = isDark ? 0xFF818CF8 : 0xFF4F46E5;

            if (dateHeaderText != null) {
                dateHeaderText.setTextColor(textSecondary);
                android.graphics.drawable.GradientDrawable datePill = new android.graphics.drawable.GradientDrawable();
                datePill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                datePill.setCornerRadius(14f * dp);
                datePill.setColor(isDark ? 0xE61E293B : 0xE6FFFFFF);
                datePill.setStroke(Math.max(1, (int) dp), isDark ? 0x33FFFFFF : 0x220F172A);
                dateHeaderText.setBackground(datePill);
            }
            if (reactionsText != null) {
                reactionsText.setTextColor(textPrimary);
                android.graphics.drawable.GradientDrawable reactPill = new android.graphics.drawable.GradientDrawable();
                reactPill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                reactPill.setCornerRadius(12f * dp);
                reactPill.setColor(isDark ? 0xFF1E293B : 0xFFFFFFFF);
                reactPill.setStroke(Math.max(1, (int) dp), isDark ? 0x33FFFFFF : 0x220F172A);
                reactionsText.setBackground(reactPill);
            }
            if (linkPreviewContainer != null) {
                android.graphics.drawable.GradientDrawable linkBg = new android.graphics.drawable.GradientDrawable();
                linkBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                linkBg.setCornerRadius(10f * dp);
                linkBg.setColor(isDark ? 0xFF162032 : 0xFFF1F5F9);
                linkPreviewContainer.setBackground(linkBg);
            }
            if (voiceSpeedButton != null) {
                voiceSpeedButton.setTextColor(textPrimary);
                android.graphics.drawable.GradientDrawable speedBg = new android.graphics.drawable.GradientDrawable();
                speedBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                speedBg.setCornerRadius(10f * dp);
                speedBg.setColor(isDark ? 0x26FFFFFF : 0x140F172A);
                voiceSpeedButton.setBackground(speedBg);
            }
            if (voiceWaveformView != null) {
                voiceWaveformView.setColors(isDark ? 0xFF818CF8 : 0xFF4F46E5, isDark ? 0xFF64748B : 0xFF94A3B8);
            }

            if (messageText != null && !"deleted".equals(messageBubble != null && messageBubble.getTag(R.id.messageBubble) instanceof Message ? ((Message) messageBubble.getTag(R.id.messageBubble)).getType() : "")) {
                messageText.setTextColor(textPrimary);
            }
            if (timeText != null) timeText.setTextColor(textSecondary);
            if (ivPinnedBadge != null) ivPinnedBadge.setColorFilter(textSecondary);
            if (tvStarredBadge != null) tvStarredBadge.setTextColor(textSecondary);
            if (tvVanishBadge != null) tvVanishBadge.setTextColor(textSecondary);
            if (tvEditedBadge != null) tvEditedBadge.setTextColor(textSecondary);
            if (voiceDurationText != null) voiceDurationText.setTextColor(textSecondary);
            if (documentFileName != null) documentFileName.setTextColor(textPrimary);
            if (documentFileSize != null) documentFileSize.setTextColor(textSecondary);
            if (locationTitle != null) locationTitle.setTextColor(textPrimary);
            if (locationSubtitle != null) locationSubtitle.setTextColor(textSecondary);
            if (contactName != null) contactName.setTextColor(textPrimary);
            if (contactPhone != null) contactPhone.setTextColor(textSecondary);
            if (contactActionMessage != null) contactActionMessage.setTextColor(actionColor);
            if (contactActionSave != null) contactActionSave.setTextColor(actionColor);
            if (pollQuestionText != null) pollQuestionText.setTextColor(textPrimary);
            if (pollTotalVotesText != null) pollTotalVotesText.setTextColor(actionColor);
            if (blinkTitleText != null) blinkTitleText.setTextColor(textPrimary);
            if (blinkSubtitleText != null) blinkSubtitleText.setTextColor(textSecondary);
            if (callTitleText != null && callTitleText.getCurrentTextColor() != 0xFFEF4444) callTitleText.setTextColor(textPrimary);
            if (callSubtitleText != null) callSubtitleText.setTextColor(textSecondary);
            if (tvLinkDomain != null) tvLinkDomain.setTextColor(textSecondary);
            if (tvLinkTitle != null) tvLinkTitle.setTextColor(textPrimary);
            if (tvLinkDesc != null) tvLinkDesc.setTextColor(textSecondary);
            styleReplyQuoteCard(replyQuoteContainer, replyQuoteName, replyQuoteText, false, isDark);
            if (imageCaptionText != null) imageCaptionText.setTextColor(textPrimary);
            if (videoCaptionText != null) videoCaptionText.setTextColor(textPrimary);
        }

        public void bind(Message message, String time) {
            bind(message, time, false, "");
        }

        public void bind(Message message, String time, boolean showDate, String dateLabel) {
            int msgIdx = findMessageIndex(message);
            if (isCollapsedAutoAlbumChild(msgIdx) || isCollapsedPairedStickerChild(msgIdx)) {
                applyCollapsedRowState(itemView, true);
                return;
            }
            applyCollapsedRowState(itemView, false);
            bindQuickForwardButton(btnQuickForwardMedia, message, false);

            if (dateHeaderContainer != null && dateHeaderText != null) {
                if (showDate && dateLabel != null && !dateLabel.isEmpty()) {
                    dateHeaderContainer.setVisibility(View.VISIBLE);
                    dateHeaderText.setText(dateLabel);
                } else {
                    dateHeaderContainer.setVisibility(View.GONE);
                }
            }
            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
            timeText.setText(time);
            bindBubbleInfoBadges(message, ivPinnedBadge, tvStarredBadge, tvVanishBadge, tvEditedBadge);
            if (messageBubble != null) {
                float stdDp = itemView.getContext().getResources().getDisplayMetrics().density;
                int stdPadH = Math.round(10f * stdDp);
                int stdPadV = Math.round(7f * stdDp);
                messageBubble.setPadding(stdPadH, stdPadV, stdPadH, stdPadV);
                ViewGroup.LayoutParams bLp = messageBubble.getLayoutParams();
                if (bLp != null && bLp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    bLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    messageBubble.setLayoutParams(bLp);
                }
            }
            if (messageText != null) {
                int screenW = itemView.getContext().getResources().getDisplayMetrics().widthPixels;
                messageText.setMaxWidth(Math.round(screenW * 0.72f));
            }
            int pos = msgIdx >= 0 ? msgIdx : (getAdapterPosition() - 1);
            int clusterPos = computeClusterPosition(pos);
            boolean isNight = com.m3.veil.ui.views.ChatMessageCell.resolveIsNightMode(itemView.getContext());
            boolean isGroupMsg = message != null && message.getChatId() != null
                    && (message.getChatId().startsWith("group_") || message.getChatId().startsWith("channel_") || message.getChatId().startsWith("lounge_"));
            if (senderNameText != null) {
                boolean showSenderHeader = isGroupMsg && (clusterPos == com.m3.veil.ui.views.ChatMessageCell.CLUSTER_SINGLE
                        || clusterPos == com.m3.veil.ui.views.ChatMessageCell.CLUSTER_FIRST
                        || showDate);
                if (showSenderHeader) {
                    String sName = resolveSenderDisplayName(itemView.getContext(), message);
                    senderNameText.setText(sName);
                    int[] palette = isNight 
                            ? new int[]{0xFF818CF8, 0xFFF472B6, 0xFF34D399, 0xFFFBBF24, 0xFF60A5FA, 0xFFA78BFA, 0xFF2DD4BF, 0xFFFB7185}
                            : new int[]{0xFF4F46E5, 0xFFDB2777, 0xFF059669, 0xFFD97706, 0xFF2563EB, 0xFF7C3AED, 0xFF0D9488, 0xFFE11D48};
                    int hash = Math.abs((message.getSenderId() != null ? message.getSenderId() : sName).hashCode());
                    senderNameText.setTextColor(palette[hash % palette.length]);
                    senderNameText.setVisibility(View.VISIBLE);
                    if (groupHeaderDivider != null) {
                        groupHeaderDivider.setVisibility(View.VISIBLE);
                    }
                } else {
                    senderNameText.setVisibility(View.GONE);
                    if (groupHeaderDivider != null) {
                        groupHeaderDivider.setVisibility(View.GONE);
                    }
                }
            }
            if (messageBubble instanceof com.m3.veil.ui.views.ChatMessageCell) {
                com.m3.veil.ui.views.ChatMessageCell cell = (com.m3.veil.ui.views.ChatMessageCell) messageBubble;
                cell.setMessageObject(message, false, clusterPos, isGroupMsg);
                if (cachedCornerRadiusDp > 0) {
                    cell.setCornerRadiusDp(cachedCornerRadiusDp);
                }
                isNight = cell.isDarkBubble() || isNight;
            }
            final boolean isDarkBubble = isNight;
            applyReceivedBubbleTheme(isDarkBubble);
            attachDoubleTapAndLongPress(messageBubble, itemView, message);
            
            if ("deleted".equals(message.getType())) {
                messageText.setVisibility(View.VISIBLE);
                hideAllMediaContainers();
                messageText.setText("🚫 This message was deleted");
                messageText.setTypeface(null, android.graphics.Typeface.ITALIC);
                messageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp > 0 ? cachedTextSizeSp : 15.0f);
                int deletedTextColor = isDarkBubble ? 0xD9F8FAFC : 0xDD0F172A;
                messageText.setTextColor(deletedTextColor);
                if (timeText != null) {
                    timeText.setText(time);
                    timeText.setTextColor(isDarkBubble ? 0x99F8FAFC : 0x8C0F172A);
                }
                if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                reactionsText.setVisibility(View.GONE);
                if (replyQuoteContainer != null) replyQuoteContainer.setVisibility(View.GONE);
            } else if (message.isBlink()) {
                hideAllMediaContainers();
                ensureBlinkViews();
                hasVisibleMediaContainer = true;
                messageText.setVisibility(View.GONE);
                if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                bindBlinkCard(message, false, blinkMessageContainer, blinkBadgeIcon, blinkProgressBar, blinkTitleText, blinkSubtitleText);
                applyReceivedBubbleTheme(isDarkBubble);
                itemView.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onMessageLongClick(message, itemView);
                        return true;
                    }
                    return false;
                });
            } else {
                hideAllMediaContainers();

                if ("voice".equals(message.getType()) || "audio".equals(message.getType())) {
                    ensureVoiceViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    if (voiceMessageContainer != null) {
                        voiceMessageContainer.setVisibility(View.VISIBLE);
                        File localAudio = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".m4a");
                        boolean hasLocal = (localAudio != null && localAudio.exists() && localAudio.length() > 0)
                                || (message.getLocalFilePath() != null && new File(message.getLocalFilePath()).exists());
                        if (!hasLocal && message.getMediaUrl() != null && !message.getMediaUrl().isEmpty()) {
                            File inlineAudio = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(itemView.getContext(), message.getChatId(), message);
                            if (inlineAudio != null && inlineAudio.exists() && inlineAudio.length() > 0) {
                                localAudio = inlineAudio;
                                hasLocal = true;
                            }
                        }

                        // Always auto-download incoming voice/audio messages without requiring manual tap
                        if (!hasLocal && message.getId() != null) {
                            int currentState = downloadStates.getOrDefault(message.getId(), STATE_IDLE);
                            if (currentState == STATE_IDLE && autoTriggeredVoiceDownloads.add(message.getId()) && mediaDownloadClickListener != null) {
                                downloadStates.put(message.getId(), STATE_DOWNLOADING);
                                itemView.post(() -> {
                                    if (mediaDownloadClickListener != null) {
                                        mediaDownloadClickListener.onMediaDownloadClick(message);
                                    }
                                });
                            }
                        }

                        final int voiceDownloadState = hasLocal ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);

                        if (voiceWaveformView != null) {
                            int playedColor = isDarkBubble ? 0xFF818CF8 : 0xFF4F46E5;
                            int unplayedColor = isDarkBubble ? 0xFF64748B : 0xFF94A3B8;
                            voiceWaveformView.setColors(playedColor, unplayedColor);
                            voiceWaveformView.setWaveformSeed(message.getId());
                            voiceWaveformView.setOnSeekListener((progress, fromUser) -> {
                                if (fromUser) VoicePlayerManager.getInstance().seekToFraction(progress);
                            });
                        }

                        if (voiceDownloadState == STATE_DOWNLOADED) {
                            int totalMs = VoicePlayerManager.getInstance().getCachedAudioDuration(message);
                            if (voiceDurationText != null) {
                                voiceDurationText.setText(totalMs > 0 ? VoicePlayerManager.formatDuration(totalMs) : "0:00");
                            }
                            if (totalMs <= 0) {
                                final String boundMsgId = message.getId();
                                VoicePlayerManager.getInstance().loadAudioDurationAsync(itemView.getContext(), message, durationMs -> {
                                    if (durationMs > 0 && voiceDurationText != null && boundMsgId != null && boundMsgId.equals(message.getId())) {
                                        voiceDurationText.setText(VoicePlayerManager.formatDuration(durationMs));
                                    }
                                });
                            }

                            if (voiceSpeedButton != null) {
                                float speed = VoicePlayerManager.getInstance().getSpeed();
                                voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", speed).replace(".0", ""));
                                voiceSpeedButton.setOnClickListener(v -> {
                                    float nextSpeed = VoicePlayerManager.getInstance().cycleSpeed();
                                    voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", nextSpeed).replace(".0", ""));
                                });
                            }

                            if (voiceActionButton != null) {
                                boolean isPlaying = VoicePlayerManager.getInstance().isPlaying(message.getId());
                                voiceActionButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setOnClickListener(v -> {
                                    pendingAutoPlayMessageId = null;
                                    VoicePlayerManager.getInstance().playVoiceNote(
                                        itemView.getContext(),
                                        message,
                                        null,
                                        currentUserId
                                    );
                                });
                            }
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.GONE);

                            VoicePlayerManager.getInstance().registerListener(message.getId(), new VoicePlayerManager.PlaybackListener() {
                                @Override
                                public void onStateChanged(boolean isPlaying) {
                                    if (voiceActionButton != null) {
                                        voiceActionButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
                                    }
                                    if (!isPlaying && voiceWaveformView != null) {
                                        voiceWaveformView.setProgress(0f);
                                        int dur = VoicePlayerManager.getInstance().getCachedAudioDuration(message);
                                        if (voiceDurationText != null) {
                                            voiceDurationText.setText(dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:00");
                                        }
                                    }
                                }

                                @Override
                                public void onProgressUpdate(int currentPosition, int maxDuration) {
                                    if (voiceWaveformView != null && maxDuration > 0) {
                                        voiceWaveformView.setProgress((float) currentPosition / maxDuration);
                                    }
                                    if (voiceDurationText != null) {
                                        voiceDurationText.setText(VoicePlayerManager.formatDuration(currentPosition) + " / " + VoicePlayerManager.formatDuration(maxDuration));
                                    }
                                }

                                @Override
                                public void onSpeedChanged(float speed) {
                                    if (voiceSpeedButton != null) {
                                        voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", speed).replace(".0", ""));
                                    }
                                }
                            });
                        } else if (voiceDownloadState == STATE_DOWNLOADING) {
                            int vProg = downloadProgresses.getOrDefault(message.getId(), 0);
                            if (voiceProgressBar != null) {
                                voiceProgressBar.setVisibility(View.VISIBLE);
                                voiceProgressBar.setIndeterminate(vProg <= 0);
                                if (vProg > 0) voiceProgressBar.setProgress(vProg);
                            }
                            if (voiceActionButton != null) {
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setImageResource(R.drawable.ic_close);
                                voiceActionButton.setOnClickListener(v -> {
                                    if (mediaDownloadClickListener != null) {
                                        mediaDownloadClickListener.onMediaCancelClick(message);
                                    }
                                });
                            }
                            if (voiceDurationText != null) {
                                voiceDurationText.setText(vProg > 0 ? ("Downloading " + vProg + "%") : "Downloading...");
                            }
                        } else {
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.GONE);
                            if (voiceActionButton != null) {
                                voiceActionButton.setImageResource(voiceDownloadState == STATE_RETRY ? R.drawable.ic_undo : R.drawable.ic_download);
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setOnClickListener(v -> {
                                    pendingAutoPlayMessageId = message.getId();
                                    clearAutoTriggeredVoiceDownload(message.getId());
                                    if (mediaDownloadClickListener != null) {
                                        if (voiceDownloadState == STATE_RETRY) {
                                            mediaDownloadClickListener.onMediaRetryClick(message);
                                        } else {
                                            mediaDownloadClickListener.onMediaDownloadClick(message);
                                        }
                                    }
                                });
                            }
                            if (voiceDurationText != null) {
                                String sizeStr = message.getFormattedMediaSize();
                                if (voiceDownloadState == STATE_RETRY) {
                                    voiceDurationText.setText(sizeStr.isEmpty() ? "Tap to retry" : (sizeStr + " • Retry"));
                                } else {
                                    voiceDurationText.setText(sizeStr.isEmpty() ? ("audio".equals(message.getType()) ? "Audio" : "Voice note") : sizeStr);
                                }
                            }
                        }
                    }
                } else if (com.m3.veil.utils.VeilStickerGifEngine.isAnimatedEmojiMessage(message)) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (messageBubble != null) {
                        messageBubble.setPadding(0, 0, 0, 0);
                        messageBubble.setBackground(null);
                    }
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        imageMessageContainer.setPadding(0, 0, 0, 0);
                        imageMessageContainer.setBackground(null);
                        bindGroupedAlbumGridOverlay(itemView.getContext(), imageCardView, null, "Contact", time, false);
                        bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, null, false);
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setStrokeWidth(0);
                            mcv.setCardElevation(0f);
                            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                            mcv.setBackground(null);
                            mcv.setRadius(0f);
                        }
                        if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        if (imageGifBadge != null) imageGifBadge.setVisibility(View.GONE);
                        if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);
                        if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                        if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                        if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);

                        int[] emojiDims = com.m3.veil.utils.VeilStickerGifEngine.computeAnimatedEmojiSizePx(itemView.getContext(), message);
                        com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, emojiDims[0], emojiDims[1]);

                        if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        bindQuickForwardButton(btnQuickForwardMedia, message, true);

                        if (messageImage != null) {
                            messageImage.setVisibility(View.VISIBLE);
                            messageImage.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                            com.m3.veil.utils.VeilStickerGifEngine.bindAnimatedEmojiToImageView(itemView.getContext(), message, messageImage);
                        }

                        View.OnClickListener emojiBurstClick = v -> {
                            if (messageImage != null) {
                                int combo = 1;
                                android.graphics.drawable.Drawable d = messageImage.getDrawable();
                                if (d instanceof com.m3.veil.ui.views.VeilAnimatedEmojiDrawable) {
                                    combo = ((com.m3.veil.ui.views.VeilAnimatedEmojiDrawable) d).triggerTapBurst();
                                }
                                v.performHapticFeedback(combo >= 3
                                        ? android.view.HapticFeedbackConstants.LONG_PRESS
                                        : android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                float peakScale = Math.min(1.36f, 1.20f + combo * 0.03f);
                                messageImage.animate().scaleX(peakScale).scaleY(peakScale).setDuration(110)
                                        .withEndAction(() -> messageImage.animate().scaleX(1.0f).scaleY(1.0f).setDuration(170).start())
                                        .start();
                            }
                        };
                        imageMessageContainer.setOnClickListener(emojiBurstClick);
                        if (imageCardView != null) imageCardView.setOnClickListener(emojiBurstClick);
                    }
                } else if ("video_note".equalsIgnoreCase(message.getType())) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (messageBubble != null) {
                        messageBubble.setPadding(0, 0, 0, 0);
                        messageBubble.setBackground(null);
                    }
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        imageMessageContainer.setPadding(0, 0, 0, 0);
                        imageMessageContainer.setBackground(null);
                        float dp = itemView.getContext().getResources().getDisplayMetrics().density;
                        int sizePx = Math.round(180 * dp);
                        com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, sizePx, sizePx);
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setRadius(sizePx / 2f);
                            mcv.setStrokeWidth(Math.round(2.5f * dp));
                            mcv.setStrokeColor(android.graphics.Color.parseColor("#38BDF8"));
                            mcv.setCardBackgroundColor(android.graphics.Color.BLACK);
                            mcv.setCardElevation(2 * dp);
                        }
                        if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        if (imageGifBadge != null) imageGifBadge.setVisibility(View.GONE);
                        if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);
                        if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                        if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                        if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);

                        if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        bindQuickForwardButton(btnQuickForwardMedia, message, true);

                        if (messageImage != null) {
                            messageImage.setVisibility(View.VISIBLE);
                            messageImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            bindVideoThumbnailBitmap(itemView.getContext(), messageImage, message,
                                    message.getLocalFilePath() != null ? new File(message.getLocalFilePath()) : null);
                        }

                        View.OnClickListener videoNoteClick = v -> {
                            String path = message.getLocalFilePath();
                            if (path != null && !path.isEmpty()) {
                                Intent intent = buildMediaViewerAlbumIntent(
                                        itemView.getContext(), message, path, "video",
                                        resolveSenderDisplayName(itemView.getContext(), message), time, "", 0);
                                itemView.getContext().startActivity(intent);
                            }
                        };
                        imageMessageContainer.setOnClickListener(videoNoteClick);
                        if (imageCardView != null) imageCardView.setOnClickListener(videoNoteClick);
                    }
                } else if (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(message)) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    boolean stickerHasReply = message.getReplyToMessageId() != null && !message.getReplyToMessageId().isEmpty();
                    if (messageBubble != null && !stickerHasReply) {
                        messageBubble.setPadding(0, 0, 0, 0);
                    }
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        imageMessageContainer.setPadding(0, 0, 0, 0);
                        imageMessageContainer.setBackground(null);
                        bindGroupedAlbumGridOverlay(itemView.getContext(), imageCardView, null, "Contact", time, false);
                        Message secondPairedSticker = getPairedSecondSticker(msgIdx);
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setStrokeWidth(0);
                            mcv.setCardElevation(0f);
                            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                            mcv.setBackground(null);
                            mcv.setRadius(0f);
                        }
                        if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        if (imageGifBadge != null) imageGifBadge.setVisibility(View.GONE);
                        if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);
                        if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                        if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                        if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);

                        if (secondPairedSticker != null) {
                            // 2-Column Side-by-Side Consecutive Sticker Pair (sticker_second_Screenshot_9e6f15cb.jpg)
                            if (messageImage != null) messageImage.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                            bindQuickForwardButton(btnQuickForwardMedia, message, false);
                            bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, secondPairedSticker, false);
                        } else {
                            // Single chromeless sticker with floating pill below & circular forward button
                            bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, null, false);
                            int[] stickerDims = com.m3.veil.utils.VeilStickerGifEngine.computeStickerSizePx(itemView.getContext(), message);
                            com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, stickerDims[0], stickerDims[1]);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                            bindQuickForwardButton(btnQuickForwardMedia, message, true);
                            if (messageImage != null) {
                                messageImage.setVisibility(View.VISIBLE);
                                messageImage.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                                com.m3.veil.utils.VeilStickerGifEngine.bindStickerToImageView(itemView.getContext(), message, messageImage);
                            }
                            View.OnClickListener stickerBounce = v -> {
                                if (messageImage != null) {
                                    com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(messageImage);
                                    messageImage.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140)
                                            .withEndAction(() -> messageImage.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                                            .start();
                                }
                            };
                            imageMessageContainer.setOnClickListener(stickerBounce);
                            if (imageCardView != null) imageCardView.setOnClickListener(stickerBounce);
                        }
                    }
                } else if ("image".equals(message.getType()) || "gif".equals(message.getType())
                        || com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(message, null)
                        || extractAlbumItems(message) != null) {
                    ensureImageViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        bindPairedStickerOverlay(itemView.getContext(), imageCardView, message, null, false);
                        String recPrimaryExt = "gif".equals(message.getType()) ? ".gif" : ("video".equals(message.getType()) ? ".mp4" : ".jpg");
                        File localImg = FileMediaManager.getDecryptedFile(
                                itemView.getContext(), message, recPrimaryExt);
                        boolean isGif = com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(message, localImg);
                        if (imageGifBadge != null) {
                            imageGifBadge.setVisibility(isGif ? View.VISIBLE : View.GONE);
                            if (isGif) {
                                styleWhatsAppCenteredGifBadge(imageGifBadge);
                            }
                        }
                        org.json.JSONArray recAlbum = extractAlbumItems(message);
                        boolean isAlbumCollage = recAlbum != null && recAlbum.length() > 1;
                        float dp = itemView.getContext().getResources().getDisplayMetrics().density;
                        if (messageBubble != null) {
                            if (isAlbumCollage) {
                                int pSide = Math.round(4.5f * dp);
                                int pTop = Math.round(4.5f * dp);
                                int pBot = Math.round(5f * dp);
                                messageBubble.setPadding(pSide, pTop, pSide, pBot);
                            } else if (isGif) {
                                int pGif = Math.round(3.5f * dp);
                                messageBubble.setPadding(pGif, pGif, pGif, pGif);
                            } else {
                                int pSide = Math.round(8f * dp);
                                int pVert = Math.round(6f * dp);
                                messageBubble.setPadding(pSide, pVert, pSide, pVert);
                            }
                        }
                        if (imageCardView instanceof com.google.android.material.card.MaterialCardView) {
                            com.google.android.material.card.MaterialCardView mcv = (com.google.android.material.card.MaterialCardView) imageCardView;
                            mcv.setStrokeWidth(0);
                            mcv.setCardElevation(0f);
                            mcv.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                            mcv.setRadius(isAlbumCollage ? 0f : (isGif ? (10f * dp) : (12f * dp)));
                        }
                        if (isAlbumCollage) {
                            if (messageImage != null) messageImage.setVisibility(View.GONE);
                            if (imageScrimView != null) imageScrimView.setVisibility(View.GONE);
                        } else if (isGif) {
                            if (messageImage != null) messageImage.setVisibility(View.VISIBLE);
                            int[] gifDims = com.m3.veil.utils.VeilStickerGifEngine.computeGifSizePx(
                                     itemView.getContext(), message, localImg);
                            com.m3.veil.utils.VeilStickerGifEngine.updateViewDimensions(imageCardView, gifDims[0], gifDims[1]);
                            if (imageScrimView != null) imageScrimView.setVisibility(View.VISIBLE);
                        } else {
                            if (messageImage != null) messageImage.setVisibility(View.VISIBLE);
                            if (imageScrimView != null) imageScrimView.setVisibility(View.VISIBLE);
                            com.m3.veil.utils.VeilStickerGifEngine.applyDynamicMediaCardSize(
                                    itemView.getContext(), imageCardView, imageCaptionText, message, localImg);
                        }
                        if (messageImage != null && !isAlbumCollage) {
                            messageImage.setBackgroundColor(isGif ? android.graphics.Color.TRANSPARENT : 0x15000000);
                            messageImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        }

                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[image")
                                && !message.getText().startsWith("[video")
                                && !message.getText().startsWith("[gif")
                                && !message.getText().startsWith("[GIF:")
                                && !message.getText().equals("[image message]")
                                && !message.getText().equals("[video message]");

                        String recSenderName = message.getSenderName() != null ? message.getSenderName() : "Contact";
                        bindGroupedAlbumGridOverlay(itemView.getContext(), imageCardView, message, recSenderName, time, hasCaption);
                        bindQuickForwardButton(btnQuickForwardMedia, message, true);

                        if (hasCaption) {
                            if (imageCaptionText != null) {
                                imageCaptionText.setVisibility(View.VISIBLE);
                                if (imageCaptionText instanceof VeilAdvancedTextView) {
                                    ((VeilAdvancedTextView) imageCaptionText).renderAdvancedText(
                                            message.getText(), 14.5f, false, isDarkBubble, entityClickListener);
                                } else {
                                    imageCaptionText.setText(message.getText());
                                }
                            }
                            if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else if (isAlbumCollage) {
                            if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                            if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            // Both standard single photos AND WhatsApp GIF bubbles ("KYA?" in sticker_second_Screenshot_9e6f15cb.jpg)
                            // show bottom-right overlaid timestamp ("5:26 PM") inside the media frame!
                            if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                            if (imageOverlayPill != null) {
                                imageOverlayPill.setVisibility(View.VISIBLE);
                                if (ivImagePinnedBadge != null) ivImagePinnedBadge.setVisibility(message.isPinned() ? View.VISIBLE : View.GONE);
                                if (imageTimeText != null) imageTimeText.setText(time);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        boolean isInlinePresetGif = message.getText() != null && message.getText().trim().startsWith("[GIF:");
                        boolean hasLocalImg = isInlinePresetGif
                                || isAlbumCollage
                                || (localImg != null && localImg.exists() && localImg.length() >= 64 && !localImg.getName().startsWith("staged_"));

                        final int imgDownloadState = hasLocalImg ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);

                        if (imgDownloadState == STATE_DOWNLOADED) {
                            if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                            boolean boundGif = !isAlbumCollage && isGif && com.m3.veil.utils.VeilStickerGifEngine.bindGifToImageView(
                                    itemView.getContext(), message, localImg, messageImage, imageProgressBar);
                            if (!boundGif && !isAlbumCollage) {
                                ImageLoader.loadImage(
                                    itemView.getContext(),
                                    message,
                                    null,
                                    currentUserId,
                                    messageImage,
                                    imageProgressBar
                                );
                            } else if (isAlbumCollage && imageProgressBar != null) {
                                imageProgressBar.setVisibility(View.GONE);
                            }

                            View.OnClickListener openPhoto = v -> {
                                if (isGif && messageImage != null && messageImage.getDrawable() instanceof android.graphics.drawable.Animatable) {
                                    android.graphics.drawable.Animatable anim = (android.graphics.drawable.Animatable) messageImage.getDrawable();
                                    if (!anim.isRunning()) {
                                        com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(messageImage);
                                        return;
                                    }
                                }
                                File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, recPrimaryExt);
                                String path = (local != null && local.exists()) ? local.getAbsolutePath() : message.getLocalFilePath();
                                if ((path == null || path.isEmpty()) && recAlbum != null && recAlbum.length() > 0) {
                                    org.json.JSONObject firstObj = recAlbum.optJSONObject(0);
                                    if (firstObj != null) path = firstObj.optString("path", "");
                                }
                                if (path != null && !path.isEmpty()) {
                                    Intent intent = buildMediaViewerAlbumIntent(
                                            itemView.getContext(), message, path,
                                            isGif ? "gif" : ("video".equals(message.getType()) ? "video" : "image"),
                                            recSenderName, time, hasCaption ? message.getText() : "", 0);
                                    itemView.getContext().startActivity(intent);
                                    try {
                                        com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).markMessageAsReadExplicitly(message);
                                    } catch (Throwable ignored) {}
                                } else if (isGif && messageImage != null) {
                                    com.m3.veil.utils.VeilStickerGifEngine.replayAnimatedDrawable(messageImage);
                                }
                            };
                            imageMessageContainer.setOnClickListener(openPhoto);
                            if (imageCardView != null) imageCardView.setOnClickListener(openPhoto);
                        } else {
                            // NOT downloaded: Show blurred thumbnail + download overlay
                            Bitmap blurred = BlurUtils.getBlurredThumbnail(itemView.getContext(), message.getThumbnail());
                            if (blurred != null) {
                                messageImage.setImageBitmap(blurred);
                            } else {
                                messageImage.setImageDrawable(null);
                            }
                            if (imageProgressBar != null) imageProgressBar.setVisibility(View.GONE);

                            if (imageDownloadOverlay != null) {
                                imageDownloadOverlay.setVisibility(View.VISIBLE);
                                if (imgDownloadState == STATE_IDLE) {
                                    if (imageDownloadIdleLayout != null) imageDownloadIdleLayout.setVisibility(View.VISIBLE);
                                    if (imageDownloadingLayout != null) imageDownloadingLayout.setVisibility(View.GONE);
                                    if (imageDownloadRetryLayout != null) imageDownloadRetryLayout.setVisibility(View.GONE);
                                    if (imageDownloadSizeText != null) {
                                        String sizeStr = message.getFormattedMediaSize();
                                        imageDownloadSizeText.setText(sizeStr.isEmpty() ? "Download" : sizeStr);
                                    }
                                    if (imageDownloadIdleLayout != null) {
                                        imageDownloadIdleLayout.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaDownloadClick(message);
                                            }
                                        });
                                    }
                                } else if (imgDownloadState == STATE_DOWNLOADING) {
                                    int progress = downloadProgresses.getOrDefault(message.getId(), 0);
                                    int shownPct = Math.max(1, Math.min(99, progress > 0 ? progress : 1));
                                    if (imageDownloadIdleLayout != null) imageDownloadIdleLayout.setVisibility(View.GONE);
                                    if (imageDownloadingLayout != null) {
                                        imageDownloadingLayout.setVisibility(View.VISIBLE);
                                        bindCircularOverlayPercent(imageDownloadingLayout, imageDownloadCancelBtn, shownPct);
                                        imageDownloadingLayout.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaCancelClick(message);
                                            }
                                        });
                                    }
                                    if (imageDownloadRetryLayout != null) imageDownloadRetryLayout.setVisibility(View.GONE);
                                    if (imageDownloadProgress != null) {
                                        imageDownloadProgress.setIndeterminate(true);
                                    }
                                    if (imageTimeText != null) {
                                        imageTimeText.setText(shownPct + "% • " + time);
                                    }
                                } else if (imgDownloadState == STATE_RETRY) {
                                    if (imageDownloadIdleLayout != null) imageDownloadIdleLayout.setVisibility(View.GONE);
                                    if (imageDownloadingLayout != null) imageDownloadingLayout.setVisibility(View.GONE);
                                    if (imageDownloadRetryLayout != null) imageDownloadRetryLayout.setVisibility(View.VISIBLE);
                                    if (imageDownloadRetryLayout != null) {
                                        imageDownloadRetryLayout.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaRetryClick(message);
                                            }
                                        });
                                    }
                                }
                            }
                            imageMessageContainer.setOnClickListener(null);
                            if (imageCardView != null) imageCardView.setOnClickListener(null);
                        }
                    }
                } else if ("video".equals(message.getType())) {
                    ensureVideoViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    bindQuickForwardButton(btnQuickForwardMedia, message, true);
                    if (videoMessageContainer != null) {
                        videoMessageContainer.setVisibility(View.VISIBLE);
                        File localRecVid = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                        com.m3.veil.utils.VeilStickerGifEngine.applyDynamicMediaCardSize(
                                itemView.getContext(), videoCardView, videoCaptionText, message, localRecVid);
                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[video")
                                && !message.getText().equals("[video message]");

                        if (hasCaption) {
                            if (videoCaptionText != null) {
                                videoCaptionText.setVisibility(View.VISIBLE);
                                if (videoCaptionText instanceof VeilAdvancedTextView) {
                                    ((VeilAdvancedTextView) videoCaptionText).renderAdvancedText(
                                            message.getText(), 14.5f, false, isDarkBubble, entityClickListener);
                                } else {
                                    videoCaptionText.setText(message.getText());
                                }
                            }
                            if (videoOverlayPill != null) videoOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            if (videoCaptionText != null) videoCaptionText.setVisibility(View.GONE);
                            if (videoOverlayPill != null) {
                                videoOverlayPill.setVisibility(View.VISIBLE);
                                if (ivVideoPinnedBadge != null) ivVideoPinnedBadge.setVisibility(message.isPinned() ? View.VISIBLE : View.GONE);
                                if (videoTimeText != null) videoTimeText.setText(time);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        int dur = VoicePlayerManager.getInstance().getCachedAudioDuration(message);
                        if (videoDurationText != null) {
                            videoDurationText.setText(dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30");
                        }
                        if (dur <= 0) {
                            final String boundVidId = message.getId();
                            VoicePlayerManager.getInstance().loadAudioDurationAsync(itemView.getContext(), message, durationMs -> {
                                if (durationMs > 0 && videoDurationText != null && boundVidId != null && boundVidId.equals(message.getId())) {
                                    videoDurationText.setText(VoicePlayerManager.formatDuration(durationMs));
                                }
                            });
                        }

                        File localVid = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                        boolean hasLocalVid = (localVid != null && localVid.exists() && localVid.length() >= 64 && !localVid.getName().startsWith("staged_"));

                        final int vidDownloadState = hasLocalVid ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);

                        if (vidDownloadState == STATE_DOWNLOADED) {
                            if (videoDownloadOverlay != null) videoDownloadOverlay.setVisibility(View.GONE);
                            if (videoPlayButtonContainer != null) videoPlayButtonContainer.setVisibility(View.VISIBLE);

                            bindVideoThumbnailBitmap(itemView.getContext(), messageVideoThumbnail, message, localVid);

                            View.OnClickListener playClick = v -> {
                                File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                                if (local != null && local.exists()) {
                                    String recSender = message.getSenderName() != null ? message.getSenderName() : "Contact";
                                    Intent intent = buildMediaViewerAlbumIntent(
                                            itemView.getContext(), message, local.getAbsolutePath(), "video",
                                            recSender, time, hasCaption ? message.getText() : "", 0);
                                    itemView.getContext().startActivity(intent);
                                    try {
                                        com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).markMessageAsReadExplicitly(message);
                                    } catch (Throwable ignored) {}
                                }
                            };
                            if (videoPlayButtonContainer != null) videoPlayButtonContainer.setOnClickListener(playClick);
                            videoMessageContainer.setOnClickListener(playClick);
                            if (videoCardView != null) videoCardView.setOnClickListener(playClick);

                        
                        } else {
                            // NOT downloaded: Show blurred thumbnail + download overlay, hide play button
                            Bitmap blurred = BlurUtils.getBlurredThumbnail(itemView.getContext(), message.getThumbnail());
                            if (blurred != null) {
                                messageVideoThumbnail.setImageBitmap(blurred);
                            } else {
                                messageVideoThumbnail.setImageDrawable(null);
                            }
                            if (videoPlayButtonContainer != null) videoPlayButtonContainer.setVisibility(View.GONE);

                            if (videoDownloadOverlay != null) {
                                videoDownloadOverlay.setVisibility(View.VISIBLE);
                                if (vidDownloadState == STATE_IDLE) {
                                    if (videoDownloadIdleLayout != null) videoDownloadIdleLayout.setVisibility(View.VISIBLE);
                                    if (videoDownloadingLayout != null) videoDownloadingLayout.setVisibility(View.GONE);
                                    if (videoDownloadRetryLayout != null) videoDownloadRetryLayout.setVisibility(View.GONE);
                                    if (videoDownloadSizeText != null) {
                                        String sizeStr = message.getFormattedMediaSize();
                                        videoDownloadSizeText.setText(sizeStr.isEmpty() ? "Download" : sizeStr);
                                    }
                                    if (videoDownloadIdleLayout != null) {
                                        videoDownloadIdleLayout.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaDownloadClick(message);
                                            }
                                        });
                                    }
                                } else if (vidDownloadState == STATE_DOWNLOADING) {
                                    int progress = downloadProgresses.getOrDefault(message.getId(), 0);
                                    int shownPct = Math.max(1, Math.min(99, progress > 0 ? progress : 1));
                                    if (videoDownloadIdleLayout != null) videoDownloadIdleLayout.setVisibility(View.GONE);
                                    if (videoDownloadingLayout != null) {
                                        videoDownloadingLayout.setVisibility(View.VISIBLE);
                                        bindCircularOverlayPercent(videoDownloadingLayout, videoDownloadCancelBtn, shownPct);
                                        videoDownloadingLayout.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaCancelClick(message);
                                            }
                                        });
                                    }
                                    if (videoDownloadRetryLayout != null) videoDownloadRetryLayout.setVisibility(View.GONE);
                                    if (videoDownloadProgress != null) {
                                        videoDownloadProgress.setIndeterminate(true);
                                    }
                                    if (videoDurationText != null) {
                                        String durStr = dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30";
                                        videoDurationText.setText(shownPct + "% • " + durStr);
                                    }
                                } else if (vidDownloadState == STATE_RETRY) {
                                    if (videoDownloadIdleLayout != null) videoDownloadIdleLayout.setVisibility(View.GONE);
                                    if (videoDownloadingLayout != null) videoDownloadingLayout.setVisibility(View.GONE);
                                    if (videoDownloadRetryLayout != null) videoDownloadRetryLayout.setVisibility(View.VISIBLE);
                                    if (videoDownloadRetryLayout != null) {
                                        videoDownloadRetryLayout.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaRetryClick(message);
                                            }
                                        });
                                    }
                                }
                            }
                            videoMessageContainer.setOnClickListener(null);
                            if (videoCardView != null) videoCardView.setOnClickListener(null);
                        }
                    }
                } else if ("document".equals(message.getType())) {
                    ensureDocumentViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (documentMessageContainer != null) {
                        documentMessageContainer.setVisibility(View.VISIBLE);
                        String raw = message.getText() != null ? message.getText() : "Document";
                        String name = raw;
                        String size = "";
                        if (raw.contains("|")) {
                            String[] p = raw.split("\\|", 2);
                            name = p[0];
                            size = p[1];
                        }
                        final String cleanName = com.m3.veil.manager.VeilMessagesController.cleanDocumentFileName(name);
                        documentFileName.setText(cleanName);

                        String ext = "DOC";
                        int dot = cleanName.lastIndexOf(".");
                        if (dot != -1 && dot < cleanName.length() - 1) {
                            ext = cleanName.substring(dot + 1).toUpperCase(Locale.ROOT);
                            if (ext.length() > 4) ext = ext.substring(0, 4);
                        }

                        if (documentExtensionBadge != null) {
                            documentExtensionBadge.setText(ext);
                            documentExtensionBadge.setVisibility(View.VISIBLE);
                        }
                        boolean isRecDarkBubble;
                        if (messageBubble instanceof com.m3.veil.ui.views.ChatMessageCell) {
                            isRecDarkBubble = ((com.m3.veil.ui.views.ChatMessageCell) messageBubble).isDarkBubble();
                        } else {
                            int recBColor = ContextCompat.getColor(itemView.getContext(), R.color.message_bubble_received);
                            isRecDarkBubble = androidx.core.graphics.ColorUtils.calculateLuminance(recBColor) < 0.5;
                        }
                        styleDocumentBubbleCard(
                                itemView.getContext(),
                                documentMessageContainer,
                                documentIconContainer,
                                documentExtensionBadge,
                                documentFileName,
                                documentFileSize,
                                documentActionIcon,
                                documentProgressBar,
                                ext,
                                isRecDarkBubble,
                                false
                        );
                        if (size.isEmpty()) {
                            String fallbackSize = message.getFormattedMediaSize();
                            if (!fallbackSize.isEmpty()) size = fallbackSize;
                        }
                        final String formattedInfo = ext + (size.isEmpty() ? "" : " • " + size);
                        documentFileSize.setText(formattedInfo);

                        File localDoc = FileMediaManager.getDecryptedFile(itemView.getContext(), message, "");
                        boolean hasLocalDoc = (localDoc != null && localDoc.exists() && localDoc.length() >= 64 && !localDoc.getName().startsWith("staged_"));
                        if (!hasLocalDoc
                                && com.m3.veil.manager.VeilMessagesController.shouldAutoDownloadMedia(itemView.getContext(), message.getChatId(), "document")
                                && message.getMediaUrl() != null && !message.getMediaUrl().isEmpty() && !message.getMediaUrl().startsWith("CHUNKED:")) {
                            File inlineDoc = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(itemView.getContext(), message.getChatId(), message);
                            if (inlineDoc != null && inlineDoc.exists() && inlineDoc.length() >= 64) {
                                localDoc = inlineDoc;
                                hasLocalDoc = true;
                            }
                        }

                        final int docDownloadState = hasLocalDoc ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);
                        final int docProgress = downloadProgresses.getOrDefault(message.getId(), 0);

                        View.OnClickListener docClick = v -> {
                            File existingLocal = FileMediaManager.getDecryptedFile(itemView.getContext(), message, "");
                            if (existingLocal != null && existingLocal.exists() && existingLocal.length() >= 64 && !existingLocal.getName().startsWith("staged_")) {
                                com.m3.veil.manager.VeilMessagesController.exportMediaToPublicStorageIfAllowed(
                                        itemView.getContext(), message.getChatId(), message, existingLocal);
                                FileMediaManager.openDocumentWithOriginalName(itemView.getContext(), existingLocal, cleanName);
                                try {
                                    com.m3.veil.manager.VeilMessagesController.getInstance(itemView.getContext()).markMessageAsReadExplicitly(message);
                                } catch (Throwable ignored) {}
                            } else if (mediaDownloadClickListener != null) {
                                if (docDownloadState == STATE_RETRY) {
                                    mediaDownloadClickListener.onMediaRetryClick(message);
                                } else {
                                    mediaDownloadClickListener.onMediaDownloadClick(message);
                                }
                            }
                        };

                        if (documentProgressBar != null) {
                            if (docDownloadState == STATE_DOWNLOADING) {
                                int shownPct = Math.max(1, Math.min(99, docProgress > 0 ? docProgress : 1));
                                documentProgressBar.setVisibility(View.VISIBLE);
                                documentProgressBar.setIndeterminate(false);
                                documentProgressBar.setMax(100);
                                documentProgressBar.setProgress(shownPct);
                                documentFileSize.setText(formattedInfo + " • Downloading " + shownPct + "%");
                                if (documentActionIcon != null) {
                                    documentActionIcon.setVisibility(View.VISIBLE);
                                    documentActionIcon.setImageResource(R.drawable.ic_close);
                                    documentActionIcon.setColorFilter(isRecDarkBubble ? 0xFFF8FAFC : ContextCompat.getColor(itemView.getContext(), R.color.message_text_primary));
                                    documentActionIcon.setOnClickListener(v -> {
                                        if (mediaDownloadClickListener != null) {
                                            mediaDownloadClickListener.onMediaCancelClick(message);
                                        }
                                    });
                                }
                                documentMessageContainer.setOnClickListener(v -> {
                                    if (mediaDownloadClickListener != null) {
                                        mediaDownloadClickListener.onMediaCancelClick(message);
                                    }
                                });
                            } else if (docDownloadState == STATE_DOWNLOADED) {
                                documentProgressBar.setVisibility(View.GONE);
                                documentFileSize.setText(formattedInfo + " • Downloaded");
                                if (documentActionIcon != null) {
                                    documentActionIcon.setVisibility(View.VISIBLE);
                                    documentActionIcon.setImageResource(R.drawable.ic_check);
                                    documentActionIcon.setColorFilter(isRecDarkBubble ? 0xFF34D399 : ContextCompat.getColor(itemView.getContext(), R.color.statusSuccess));
                                    documentActionIcon.setOnClickListener(docClick);
                                }
                                documentMessageContainer.setOnClickListener(docClick);
                            } else {
                                documentProgressBar.setVisibility(View.GONE);
                                documentFileSize.setText(formattedInfo + (docDownloadState == STATE_RETRY ? " • Tap to retry" : " • Tap to download"));
                                if (documentActionIcon != null) {
                                    documentActionIcon.setVisibility(View.VISIBLE);
                                    documentActionIcon.setImageResource(docDownloadState == STATE_RETRY ? R.drawable.ic_undo : R.drawable.ic_download);
                                    int iconColor = (docDownloadState == STATE_RETRY)
                                            ? ContextCompat.getColor(itemView.getContext(), R.color.statusError)
                                            : (isRecDarkBubble ? 0xFF818CF8 : ContextCompat.getColor(itemView.getContext(), R.color.message_action_button));
                                    documentActionIcon.setColorFilter(iconColor);
                                    documentActionIcon.setOnClickListener(docClick);
                                }
                                documentMessageContainer.setOnClickListener(docClick);
                            }
                        }
                    }
                } else if ("location".equals(message.getType())) {
                    ensureLocationViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    bindLocationCard(message, locationMessageContainer, locationTitle, locationSubtitle);
                } else if ("contact".equals(message.getType())) {
                    ensureContactViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (contactMessageContainer != null) {
                        contactMessageContainer.setVisibility(View.VISIBLE);
                        String raw = message.getText() != null ? message.getText() : "Contact\n";
                        String[] parts = raw.split("\n", 2);
                        final String cName = parts[0];
                        final String phone = parts.length > 1 ? parts[1] : "";
                        contactName.setText(cName);
                        contactPhone.setText(phone.isEmpty() ? "No phone" : phone);

                        View.OnClickListener dialClick = v -> FileMediaManager.openContactDialer(itemView.getContext(), phone);
                        View.OnClickListener saveClick = v -> FileMediaManager.saveContactToPhonebook(itemView.getContext(), cName, phone);

                        if (contactActionMessage != null) contactActionMessage.setOnClickListener(dialClick);
                        if (contactActionSave != null) contactActionSave.setOnClickListener(saveClick);
                        if (contactAction != null) contactAction.setOnClickListener(dialClick);
                        contactMessageContainer.setOnClickListener(dialClick);
                    }
                } else if ("poll".equals(message.getType())) {
                    ensurePollViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindPollCard(message, pollMessageContainer, pollQuestionText, pollOptionsLinearLayout, pollTotalVotesText);
                } else if ("call".equals(message.getType())) {
                    ensureCallViews();
                    hasVisibleMediaContainer = true;
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindCallCard(message, callMessageContainer, callIconContainer, callIcon, callTitleText, callSubtitleText, false);
                } else {
                    messageText.setVisibility(View.VISIBLE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    CharSequence formatted = formatRichTextAndPoll(itemView.getContext(), message);
                    if (messageText instanceof VeilAdvancedTextView) {
                        ((VeilAdvancedTextView) messageText).renderAdvancedText(
                                formatted,
                                cachedTextSizeSp > 0 ? cachedTextSizeSp : 15.5f,
                                false,
                                isDarkBubble,
                                entityClickListener
                        );
                    } else {
                        messageText.setText(formatted);
                    }
                    String rawMsgTxt = message.getText();
                    if (rawMsgTxt != null && (rawMsgTxt.contains("http://") || rawMsgTxt.contains("https://") || rawMsgTxt.contains("www."))) {
                        ensureLinkPreviewViews();
                        hasVisibleMediaContainer = true;
                        bindLinkPreview(rawMsgTxt, linkPreviewContainer, ivLinkThumb, tvLinkDomain, tvLinkTitle, tvLinkDesc);
                    }
                    if (message.getText() != null && message.getText().contains("||") && message.getId() != null) {
                        messageText.setOnClickListener(v -> {
                            if (revealedSpoilers.contains(message.getId())) {
                                revealedSpoilers.remove(message.getId());
                            } else {
                                revealedSpoilers.add(message.getId());
                            }
                            CharSequence refmt = formatRichTextAndPoll(itemView.getContext(), message);
                            if (messageText instanceof VeilAdvancedTextView) {
                                ((VeilAdvancedTextView) messageText).renderAdvancedText(
                                        refmt,
                                        cachedTextSizeSp > 0 ? cachedTextSizeSp : 15.5f,
                                        false,
                                        isDarkBubble,
                                        entityClickListener
                                );
                            } else {
                                messageText.setText(refmt);
                            }
                        });
                    } else {
                        messageText.setOnClickListener(null);
                    }
                }
                
                messageText.setTypeface(null, android.graphics.Typeface.NORMAL);
                messageText.setTextColor(isDarkBubble ? 0xFFF8FAFC : 0xFF0F172A);
                if (!(messageText instanceof VeilAdvancedTextView) && cachedTextSizeSp > 0 && messageText != null) {
                    messageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp);
                }
                
                if (replyQuoteContainer != null) {
                    if (message.getReplyToMessageId() != null) {
                        Message replyTo = getMessageById(message.getReplyToMessageId());
                        if (replyTo != null && !"deleted".equals(replyTo.getType())) {
                            replyQuoteContainer.setVisibility(View.VISIBLE);
                            replyQuoteName.setText(resolveReplyQuoteSenderName(message, replyTo));
                            String quoteText = replyTo.getText();
                            if ("voice".equals(replyTo.getType())) quoteText = "🎤 Voice message";
                            else if ("audio".equals(replyTo.getType())) quoteText = "🎵 Audio";
                            else if (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(replyTo)) quoteText = "✨ Sticker";
                            else if (com.m3.veil.utils.VeilStickerGifEngine.isGifMessage(replyTo, null)) quoteText = "🎬 GIF";
                            else if ("image".equals(replyTo.getType())) quoteText = "📷 Photo";
                            else if ("video".equals(replyTo.getType())) quoteText = "🎥 Video";
                            else if ("document".equals(replyTo.getType())) {
                                String dName = replyTo.getText();
                                if (dName != null && dName.contains("|")) dName = dName.split("\\|", 2)[0];
                                quoteText = "📄 " + (dName != null ? dName : "Document");
                            } else if ("location".equals(replyTo.getType())) quoteText = "📍 Location";
                            else if ("contact".equals(replyTo.getType())) {
                                String cName = replyTo.getText();
                                if (cName != null && cName.contains("\n")) cName = cName.split("\n", 2)[0];
                                quoteText = "👤 " + (cName != null ? cName : "Contact");
                            } else if ("call".equals(replyTo.getType())) {
                                boolean isVideo = replyTo.getText() != null && replyTo.getText().toLowerCase().contains("video");
                                quoteText = (isVideo ? "📹 " : "📞 ") + (replyTo.getText() != null ? replyTo.getText() : "Call");
                            }
                            replyQuoteText.setText(quoteText);
                            replyQuoteText.setTypeface(null, android.graphics.Typeface.NORMAL);
                        } else {
                            replyQuoteContainer.setVisibility(View.VISIBLE);
                            replyQuoteName.setText(resolveReplyQuoteSenderName(message, replyTo));
                            replyQuoteText.setText("This message was deleted");
                            replyQuoteText.setTypeface(null, android.graphics.Typeface.ITALIC);
                        }
                        
                        replyQuoteContainer.setOnClickListener(v -> {
                            if (replyClickListener != null) {
                                replyClickListener.onReplyClick(message.getReplyToMessageId());
                            }
                        });
                    } else {
                        replyQuoteContainer.setVisibility(View.GONE);
                    }
                }
                
                boolean hasValidReactions = false;
                StringBuilder emojis = new StringBuilder();
                if (message.getReactions() != null && !message.getReactions().isEmpty()) {
                    for (String emoji : message.getReactions().values()) {
                        if (emoji != null && !emoji.trim().isEmpty()) {
                            emojis.append(emoji.trim());
                            hasValidReactions = true;
                        }
                    }
                }
                if (hasValidReactions && emojis.length() > 0) {
                    reactionsText.setText(emojis.toString());
                    reactionsText.setVisibility(View.VISIBLE);
                    reactionsText.setOnClickListener(v -> {
                        if (reactionClickListener != null) {
                            reactionClickListener.onReactionClick(message);
                        }
                    });
                } else {
                    reactionsText.setText("");
                    reactionsText.setVisibility(View.GONE);
                    reactionsText.setOnClickListener(null);
                }
                
                applyReceivedBubbleTheme(isDarkBubble);
                boolean isChromelessRec = (com.m3.veil.utils.VeilStickerGifEngine.isStickerMessage(message)
                        || com.m3.veil.utils.VeilStickerGifEngine.isAnimatedEmojiMessage(message))
                        && (message.getReplyToMessageId() == null || message.getReplyToMessageId().isEmpty());
                applyChromelessBelowInfoStyle(bubbleInfoContainer, timeText, null, ivPinnedBadge,
                        message, isChromelessRec, false, isDarkBubble);
                if (messageBubble != null) {
                    messageBubble.requestLayout();
                    messageBubble.invalidate();
                }
                itemView.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onMessageLongClick(message, itemView);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    class TypingIndicatorViewHolder extends RecyclerView.ViewHolder {
        View dot1, dot2, dot3;
        
        public TypingIndicatorViewHolder(@NonNull View itemView) {
            super(itemView);
            dot1 = itemView.findViewById(R.id.dot1);
            dot2 = itemView.findViewById(R.id.dot2);
            dot3 = itemView.findViewById(R.id.dot3);
        }
        
        public void startAnimation() {
            animateDot(dot1, 0);
            animateDot(dot2, 150);
            animateDot(dot3, 300);
        }
        
        private void animateDot(View dot, long delay) {
            float dip = itemView.getContext().getResources().getDisplayMetrics().density;
            float jumpHeight = -4f * dip;
            
            android.animation.ObjectAnimator jump = android.animation.ObjectAnimator.ofFloat(dot, "translationY", 0f, jumpHeight, 0f);
            jump.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            jump.setRepeatMode(android.animation.ValueAnimator.RESTART);
            
            android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f, 0.3f);
            fade.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            fade.setRepeatMode(android.animation.ValueAnimator.RESTART);
            
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(jump, fade);
            set.setDuration(900);
            set.setStartDelay(delay);
            set.start();
        }
    }

    private void bindLinkPreview(String text, View container, ImageView thumb, TextView domain, TextView title, TextView desc) {
        if (container == null) return;
        if (text == null || (!text.contains("http://") && !text.contains("https://") && !text.contains("www."))) {
            container.setVisibility(View.GONE);
            return;
        }
        String url = com.m3.veil.utils.LinkPreviewHelper.extractUrl(text);
        if (url == null) {
            container.setVisibility(View.GONE);
            return;
        }

        container.setTag(url);
        container.setOnClickListener(v -> {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                v.getContext().startActivity(intent);
            } catch (Exception ignored) {}
        });

        com.m3.veil.utils.LinkPreviewHelper.LinkPreview cached = com.m3.veil.utils.LinkPreviewHelper.getCachedPreview(url);
        if (cached != null) {
            applyLinkPreview(cached, container, thumb, domain, title, desc);
        } else {
            container.setVisibility(View.GONE);
            com.m3.veil.utils.LinkPreviewHelper.fetchPreview(url, preview -> {
                if (url.equals(container.getTag())) {
                    if (preview != null && preview.hasContent()) {
                        applyLinkPreview(preview, container, thumb, domain, title, desc);
                    } else {
                        container.setVisibility(View.GONE);
                    }
                }
            });
        }
    }

    private void applyLinkPreview(com.m3.veil.utils.LinkPreviewHelper.LinkPreview preview, View container, ImageView thumb, TextView domain, TextView title, TextView desc) {
        container.setVisibility(View.VISIBLE);
        if (domain != null) domain.setText(preview.domain);
        if (title != null) {
            title.setVisibility(preview.title != null && !preview.title.isEmpty() ? View.VISIBLE : View.GONE);
            title.setText(preview.title);
        }
        if (desc != null) {
            desc.setVisibility(preview.description != null && !preview.description.isEmpty() ? View.VISIBLE : View.GONE);
            desc.setText(preview.description);
        }
        if (thumb != null) {
            if (preview.imageBitmap != null) {
                thumb.setVisibility(View.VISIBLE);
                thumb.setImageBitmap(preview.imageBitmap);
            } else {
                thumb.setVisibility(View.GONE);
            }
        }
    }

    private static void bindVideoThumbnailBitmap(Context context, ImageView targetView, Message message, File localVideoFile) {
        if (targetView == null || message == null) return;
        // 1. Try decoding sharp Base64 thumbnail first
        if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
            try {
                byte[] decodedBytes = Base64.decode(message.getThumbnail(), Base64.DEFAULT);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                Bitmap sharpBmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length, opts);
                if (sharpBmp != null) {
                    targetView.setImageBitmap(sharpBmp);
                    return;
                }
            } catch (Throwable ignored) {}
            Bitmap blurred = BlurUtils.getBlurredThumbnail(context, message.getThumbnail());
            if (blurred != null) {
                targetView.setImageBitmap(blurred);
                return;
            }
        }

        // 2. Fallback: extract video frame from local file (e.g. forwarded or edited video) and cache on message
        File vidFile = localVideoFile;
        if ((vidFile == null || !vidFile.exists()) && message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File candidate = new File(message.getLocalFilePath());
            if (candidate.exists()) vidFile = candidate;
        }
        if (vidFile != null && vidFile.exists()) {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(vidFile.getAbsolutePath());
                Bitmap frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) {
                    frame = retriever.getFrameAtTime();
                }
                if (frame != null) {
                    int fw = Math.max(1, frame.getWidth());
                    int fh = Math.max(1, frame.getHeight());
                    float ratio = Math.min(320f / fw, 320f / fh);
                    int tw = Math.max(48, Math.round(fw * ratio));
                    int th = Math.max(48, Math.round(fh * ratio));
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, tw, th, true);
                    targetView.setImageBitmap(scaled);
                    try {
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        scaled.compress(Bitmap.CompressFormat.JPEG, 78, baos);
                        message.setThumbnail(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                    } catch (Throwable ignored) {}
                    if (scaled != frame) frame.recycle();
                    return;
                }
            } catch (Throwable ignored) {
            } finally {
                try { retriever.release(); } catch (Throwable ignored) {}
            }
        }
        targetView.setImageDrawable(null);
    }
}