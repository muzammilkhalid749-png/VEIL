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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.m3.veil.R;
import com.m3.veil.model.Message;
import com.m3.veil.ui.chat.MediaViewerActivity;
import com.m3.veil.ui.views.VoiceWaveformView;
import com.m3.veil.utils.BlurUtils;
import com.m3.veil.utils.FileMediaManager;
import com.m3.veil.utils.ImageLoader;
import com.m3.veil.utils.VoicePlayerManager;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_NOTICE = 0;
    private static final int VIEW_TYPE_SENT = 1;
    private static final int VIEW_TYPE_RECEIVED = 2;
    private static final int VIEW_TYPE_TYPING = 3;

    private List<Message> messages;
    private String currentUserId;
    private SimpleDateFormat timeFormat;
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

    public static final int STATE_IDLE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_RETRY = 2;
    public static final int STATE_DOWNLOADED = 3;

    private final java.util.Map<String, Integer> downloadStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> downloadProgresses = new java.util.concurrent.ConcurrentHashMap<>();

    private OnMessageLongClickListener longClickListener;
    private OnReactionClickListener reactionClickListener;
    private OnReplyClickListener replyClickListener;
    private OnPollVoteClickListener pollVoteClickListener;
    private OnPollOptionVoteListener pollOptionVoteListener;
    private OnBlinkOpenClickListener blinkOpenClickListener;
    private OnDoubleTapReactListener doubleTapReactListener;
    private OnCallMessageClickListener callMessageClickListener;
    private OnMediaDownloadClickListener mediaDownloadClickListener;

    public void setOnMediaDownloadClickListener(OnMediaDownloadClickListener listener) {
        this.mediaDownloadClickListener = listener;
    }

    public void setMediaDownloadState(String messageId, int state, int progress) {
        if (messageId == null) return;
        downloadStates.put(messageId, state);
        if (progress >= 0) downloadProgresses.put(messageId, progress);
        notifyItemChangedByMessageId(messageId);
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
                    notifyItemChanged(i);
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

    private final java.util.Set<String> revealedSpoilers = new java.util.HashSet<>();

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
        boolean hasSpoiler = raw.contains("||") && raw.indexOf("||") != raw.lastIndexOf("||");
        boolean isRevealed = message.getId() != null && revealedSpoilers.contains(message.getId());

        String processedRaw = raw;
        if (hasSpoiler && !isRevealed) {
            processedRaw = raw.replaceAll("\\|\\|([^|]+)\\|\\|", "▒▒▒▒▒▒ [🕵️ Spoiler • Tap to reveal]");
        } else if (hasSpoiler) {
            processedRaw = raw.replaceAll("\\|\\|([^|]+)\\|\\|", "$1");
        }

        String html = processedRaw
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("\\*([^*]+)\\*", "<b>$1</b>")
                .replaceAll("_([^_]+)_", "<i>$1</i>")
                .replaceAll("~([^~]+)~", "<s>$1</s>")
                .replaceAll("`([^`]+)`", "<tt>$1</tt>")
                .replace("\n", "<br/>");

        if (message.getTranslatedText() != null && !message.getTranslatedText().isEmpty()) {
            String transEscaped = message.getTranslatedText()
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>");
            String lang = message.getTranslatedLang() != null ? message.getTranslatedLang().toUpperCase(Locale.ROOT) : "OFFLINE";
            boolean isNight = context != null && (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            String headerHex = isNight ? "#818CF8" : "#4F46E5";
            String bodyHex = isNight ? "#CBD5E1" : "#334155";
            html += "<br/><br/><small><font color='" + headerHex + "'>🌐 <b>[Translated — " + lang + "]</b></font></small><br/><font color='" + bodyHex + "'><i>" + transEscaped + "</i></font>";
            return androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT);
        }

        if (processedRaw.contains("*") || processedRaw.contains("_") || processedRaw.contains("`") || processedRaw.contains("~")) {
            return androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT);
        }
        return processedRaw;
    }

    public MessagesAdapter(List<Message> messages, OnMessageLongClickListener longClickListener, OnReactionClickListener reactionClickListener) {
        this.messages = messages;
        this.currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(com.m3.veil.VeilApplication.getInstance());
        if (this.currentUserId == null) {
            this.currentUserId = "";
        }
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        this.longClickListener = longClickListener;
        this.reactionClickListener = reactionClickListener;
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
        if (messages.get(msgIndex).getSenderId().equals(currentUserId)) {
            return VIEW_TYPE_SENT;
        } else {
            return VIEW_TYPE_RECEIVED;
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
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_sent, parent, false);
            return new SentMessageViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_received, parent, false);
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

        java.util.Calendar cal1 = java.util.Calendar.getInstance();
        cal1.setTime(current.getTimestamp().toDate());

        java.util.Calendar cal2 = java.util.Calendar.getInstance();
        cal2.setTime(previous.getTimestamp().toDate());

        return cal1.get(java.util.Calendar.YEAR) != cal2.get(java.util.Calendar.YEAR)
                || cal1.get(java.util.Calendar.DAY_OF_YEAR) != cal2.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private String formatDateHeader(com.m3.veil.model.Timestamp timestamp) {
        if (timestamp == null) return "";
        java.util.Calendar msgCal = java.util.Calendar.getInstance();
        msgCal.setTime(timestamp.toDate());

        java.util.Calendar nowCal = java.util.Calendar.getInstance();

        if (nowCal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR)
                && nowCal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "TODAY";
        }

        nowCal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (nowCal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR)
                && nowCal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "YESTERDAY";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy", Locale.getDefault());
        return sdf.format(timestamp.toDate()).toUpperCase(Locale.getDefault());
    }

    private void setAnimation(View view) {
        view.setAlpha(0f);
        view.setTranslationY(14f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
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
        notifyDataSetChanged();
    }
    
    private Message getMessageById(String id) {
        if (id == null) return null;
        for (Message m : messages) {
            if (id.equals(m.getId())) return m;
        }
        return null;
    }

    static class NoticeViewHolder extends RecyclerView.ViewHolder {
        public NoticeViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private void updateStatusIcon(ImageView iconView, Message message) {
        if (iconView == null || message == null) return;
        if (message.isPending()) {
            iconView.setImageResource(R.drawable.ic_timer);
            iconView.setColorFilter(iconView.getContext().getResources().getColor(R.color.message_status_sent));
        } else if (message.isRead()) {
            iconView.setImageResource(R.drawable.ic_check_double);
            iconView.setColorFilter(iconView.getContext().getResources().getColor(R.color.message_status_read));
        } else if (message.isDelivered()) {
            iconView.setImageResource(R.drawable.ic_check_double);
            iconView.setColorFilter(iconView.getContext().getResources().getColor(R.color.message_status_sent));
        } else {
            iconView.setImageResource(R.drawable.ic_check);
            iconView.setColorFilter(iconView.getContext().getResources().getColor(R.color.message_status_sent));
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

    private void bindBubbleInfoBadges(Message message, TextView tvStarredBadge, TextView tvVanishBadge, TextView tvEditedBadge) {
        if (tvStarredBadge != null) {
            tvStarredBadge.setVisibility(message.isStarred() ? View.VISIBLE : View.GONE);
        }
        if (tvVanishBadge != null) {
            boolean isExpiring = (message.getExpiresAt() != null && message.getExpiresAt().toDate().getTime() > 0)
                    || message.getVanishTimer() > 0;
            tvVanishBadge.setVisibility(isExpiring ? View.VISIBLE : View.GONE);
        }
        if (tvEditedBadge != null) {
            tvEditedBadge.setVisibility(message.isEdited() ? View.VISIBLE : View.GONE);
        }
    }

    private void attachDoubleTapAndLongPress(View bubbleView, View rootItemView, Message message) {
        if (bubbleView == null) return;
        android.view.GestureDetector detector = new android.view.GestureDetector(
                bubbleView.getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (doubleTapReactListener != null && !"deleted".equals(message.getType())) {
                            if (bubbleView instanceof com.m3.veil.ui.views.ChatMessageCell) {
                                ((com.m3.veil.ui.views.ChatMessageCell) bubbleView).triggerDoubleTapHeartBurst(e.getX(), e.getY());
                            }
                            bubbleView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90)
                                    .withEndAction(() -> bubbleView.animate().scaleX(1f).scaleY(1f).setDuration(90).start())
                                    .start();
                            doubleTapReactListener.onDoubleTapReact(message);
                            return true;
                        }
                        return false;
                    }
                });
        detector.setIsLongpressEnabled(false);
        bubbleView.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false;
        });

        View.OnLongClickListener longClick = v -> {
            if (longClickListener != null) {
                longClickListener.onMessageLongClick(message, rootItemView != null ? rootItemView : bubbleView);
                return true;
            }
            return false;
        };
        bubbleView.setOnLongClickListener(longClick);
        if (rootItemView != null) {
            rootItemView.setOnLongClickListener(longClick);
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

        try {
            org.json.JSONObject poll = new org.json.JSONObject(
                    message.getPollJson() != null ? message.getPollJson() : "{}");
            String question = poll.optString("question", message.getText());
            boolean allowMultiple = poll.optBoolean("allowMultiple", false);
            if (questionText != null) questionText.setText(question);
            if (subtitleView != null) {
                subtitleView.setText(allowMultiple ? "Select one or more" : "Select one");
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

                    if (tvOptName != null) tvOptName.setText(optText);
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

        int greenColor = ContextCompat.getColor(container.getContext(), R.color.statusSuccess);
        int redColor = ContextCompat.getColor(container.getContext(), R.color.statusError);
        int secondaryColor = ContextCompat.getColor(container.getContext(), R.color.textSecondary);

        if (isSentHolder) {
            // Outgoing call (sent side)
            if (isVideo) {
                icon.setImageResource(R.drawable.ic_video_call_outgoing);
            } else {
                icon.setImageResource(R.drawable.ic_call_outgoing);
            }

            title.setText(isVideo ? "Video call" : "Voice call");
            title.setTextColor(ContextCompat.getColor(container.getContext(), R.color.message_text_primary));

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
                title.setTextColor(ContextCompat.getColor(container.getContext(), R.color.message_text_primary));
                subtitle.setText("Tap to call back");
            } else {
                // Answered incoming call
                icon.setColorFilter(greenColor);
                if (iconContainer != null) {
                    iconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1A10B981));
                }
                title.setText(isVideo ? "Video call" : "Voice call");
                title.setTextColor(ContextCompat.getColor(container.getContext(), R.color.message_text_primary));
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
        
        // Image UI
        View imageMessageContainer;
        View imageCardView;
        ImageView messageImage;
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

        public SentMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            dateHeaderContainer = itemView.findViewById(R.id.dateHeaderContainer);
            dateHeaderText = itemView.findViewById(R.id.dateHeaderText);
            messageText = itemView.findViewById(R.id.messageText);
            linkPreviewContainer = itemView.findViewById(R.id.linkPreviewContainer);
            ivLinkThumb = itemView.findViewById(R.id.ivLinkThumb);
            tvLinkDomain = itemView.findViewById(R.id.tvLinkDomain);
            tvLinkTitle = itemView.findViewById(R.id.tvLinkTitle);
            tvLinkDesc = itemView.findViewById(R.id.tvLinkDesc);
            timeText = itemView.findViewById(R.id.timeText);
            statusIcon = itemView.findViewById(R.id.statusIcon);
            reactionsText = itemView.findViewById(R.id.reactionsText);
            replyQuoteContainer = itemView.findViewById(R.id.replyQuoteContainer);
            replyQuoteName = itemView.findViewById(R.id.replyQuoteName);
            replyQuoteText = itemView.findViewById(R.id.replyQuoteText);
            messageBubble = itemView.findViewById(R.id.messageBubble);
            bubbleInfoContainer = itemView.findViewById(R.id.bubbleInfoContainer);
            
            voiceMessageContainer = itemView.findViewById(R.id.voiceMessageContainer);
            voiceActionButton = itemView.findViewById(R.id.voiceActionButton);
            voiceProgressBar = itemView.findViewById(R.id.voiceProgressBar);
            voiceWaveformView = itemView.findViewById(R.id.voiceWaveformView);
            voiceDurationText = itemView.findViewById(R.id.voiceDurationText);
            voiceSpeedButton = itemView.findViewById(R.id.voiceSpeedButton);
            voiceMicBadge = itemView.findViewById(R.id.voiceMicBadge);
            voiceLine = itemView.findViewById(R.id.voiceLine);
            voiceSeekBar = itemView.findViewById(R.id.voiceSeekBar);
            
            imageMessageContainer = itemView.findViewById(R.id.imageMessageContainer);
            imageCardView = itemView.findViewById(R.id.imageCardView);
            messageImage = itemView.findViewById(R.id.messageImage);
            imageProgressBar = itemView.findViewById(R.id.imageProgressBar);
            imageOverlayPill = itemView.findViewById(R.id.imageOverlayPill);
            imageTimeText = itemView.findViewById(R.id.imageTimeText);
            imageStatusIcon = itemView.findViewById(R.id.imageStatusIcon);
            imageCaptionText = itemView.findViewById(R.id.imageCaptionText);

            videoMessageContainer = itemView.findViewById(R.id.videoMessageContainer);
            videoCardView = itemView.findViewById(R.id.videoCardView);
            messageVideoThumbnail = itemView.findViewById(R.id.messageVideoThumbnail);
            videoPlayButtonContainer = itemView.findViewById(R.id.videoPlayButtonContainer);
            videoPlayIcon = itemView.findViewById(R.id.videoPlayIcon);
            videoProgressBar = itemView.findViewById(R.id.videoProgressBar);
            videoOverlayPill = itemView.findViewById(R.id.videoOverlayPill);
            videoTimeText = itemView.findViewById(R.id.videoTimeText);
            videoStatusIcon = itemView.findViewById(R.id.videoStatusIcon);
            videoDurationContainer = itemView.findViewById(R.id.videoDurationContainer);
            videoDurationText = itemView.findViewById(R.id.videoDurationText);
            videoCaptionText = itemView.findViewById(R.id.videoCaptionText);

            documentMessageContainer = itemView.findViewById(R.id.documentMessageContainer);
            documentIconContainer = itemView.findViewById(R.id.documentIconContainer);
            documentExtensionBadge = itemView.findViewById(R.id.documentExtensionBadge);
            documentFileName = itemView.findViewById(R.id.documentFileName);
            documentFileSize = itemView.findViewById(R.id.documentFileSize);
            documentActionIcon = itemView.findViewById(R.id.documentActionIcon);
            documentProgressBar = itemView.findViewById(R.id.documentProgressBar);

            locationMessageContainer = itemView.findViewById(R.id.locationMessageContainer);
            locationTitle = itemView.findViewById(R.id.locationTitle);
            locationSubtitle = itemView.findViewById(R.id.locationSubtitle);

            contactMessageContainer = itemView.findViewById(R.id.contactMessageContainer);
            contactName = itemView.findViewById(R.id.contactName);
            contactPhone = itemView.findViewById(R.id.contactPhone);
            contactAction = itemView.findViewById(R.id.contactAction);
            contactActionMessage = itemView.findViewById(R.id.contactActionMessage);
            contactActionSave = itemView.findViewById(R.id.contactActionSave);

            pollMessageContainer = itemView.findViewById(R.id.pollMessageContainer);
            pollQuestionText = itemView.findViewById(R.id.pollQuestionText);
            pollOptionsLinearLayout = itemView.findViewById(R.id.pollOptionsLinearLayout);
            pollTotalVotesText = itemView.findViewById(R.id.pollTotalVotesText);

            blinkMessageContainer = itemView.findViewById(R.id.blinkMessageContainer);
            blinkBadgeIcon = itemView.findViewById(R.id.blinkBadgeIcon);
            blinkProgressBar = itemView.findViewById(R.id.blinkProgressBar);
            blinkTitleText = itemView.findViewById(R.id.blinkTitleText);
            blinkSubtitleText = itemView.findViewById(R.id.blinkSubtitleText);

            imageDownloadOverlay = itemView.findViewById(R.id.imageDownloadOverlay);
            imageDownloadIdleLayout = itemView.findViewById(R.id.imageDownloadIdleLayout);
            imageDownloadSizeText = itemView.findViewById(R.id.imageDownloadSizeText);
            imageDownloadingLayout = itemView.findViewById(R.id.imageDownloadingLayout);
            imageDownloadProgress = itemView.findViewById(R.id.imageDownloadProgress);
            imageDownloadCancelBtn = itemView.findViewById(R.id.imageDownloadCancelBtn);
            imageDownloadRetryLayout = itemView.findViewById(R.id.imageDownloadRetryLayout);

            videoDownloadOverlay = itemView.findViewById(R.id.videoDownloadOverlay);
            videoDownloadIdleLayout = itemView.findViewById(R.id.videoDownloadIdleLayout);
            videoDownloadSizeText = itemView.findViewById(R.id.videoDownloadSizeText);
            videoDownloadingLayout = itemView.findViewById(R.id.videoDownloadingLayout);
            videoDownloadProgress = itemView.findViewById(R.id.videoDownloadProgress);
            videoDownloadCancelBtn = itemView.findViewById(R.id.videoDownloadCancelBtn);
            videoDownloadRetryLayout = itemView.findViewById(R.id.videoDownloadRetryLayout);

            callMessageContainer = itemView.findViewById(R.id.callMessageContainer);
            callIconContainer = itemView.findViewById(R.id.callIconContainer);
            callIcon = itemView.findViewById(R.id.callIcon);
            callTitleText = itemView.findViewById(R.id.callTitleText);
            callSubtitleText = itemView.findViewById(R.id.callSubtitleText);

            tvStarredBadge = itemView.findViewById(R.id.tvStarredBadge);
            tvVanishBadge = itemView.findViewById(R.id.tvVanishBadge);
            tvEditedBadge = itemView.findViewById(R.id.tvEditedBadge);
        }

        private void hideAllMediaContainers() {
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

        public void bind(Message message, String time) {
            bind(message, time, false, "");
        }

        public void bind(Message message, String time, boolean showDate, String dateLabel) {
            if (dateHeaderContainer != null && dateHeaderText != null) {
                if (showDate && dateLabel != null && !dateLabel.isEmpty()) {
                    dateHeaderContainer.setVisibility(View.VISIBLE);
                    dateHeaderText.setText(dateLabel);
                } else {
                    dateHeaderContainer.setVisibility(View.GONE);
                }
            }
            timeText.setText(time);
            bindBubbleInfoBadges(message, tvStarredBadge, tvVanishBadge, tvEditedBadge);
            if (messageBubble instanceof com.m3.veil.ui.views.ChatMessageCell) {
                com.m3.veil.ui.views.ChatMessageCell cell = (com.m3.veil.ui.views.ChatMessageCell) messageBubble;
                int pos = getAdapterPosition() - 1;
                cell.setMessageObject(message, true, computeClusterPosition(pos), false);
                try {
                    android.content.SharedPreferences appPrefs = itemView.getContext().getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
                    String accent = appPrefs.getString("chat_bubble_accent", null);
                    if (accent != null && !accent.isEmpty()) {
                        cell.setCustomBubbleColor(android.graphics.Color.parseColor(accent));
                    }
                } catch (Exception ignored) {}
            }
            attachDoubleTapAndLongPress(messageBubble, itemView, message);
            
            if ("deleted".equals(message.getType())) {
                messageText.setVisibility(View.VISIBLE);
                hideAllMediaContainers();
                messageText.setText("🚫 This message was deleted");
                messageText.setTypeface(null, android.graphics.Typeface.ITALIC);
                messageText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.message_text_secondary));
                statusIcon.setVisibility(View.GONE);
                reactionsText.setVisibility(View.GONE);
                if (replyQuoteContainer != null) replyQuoteContainer.setVisibility(View.GONE);
            } else if (message.isBlink()) {
                hideAllMediaContainers();
                messageText.setVisibility(View.GONE);
                if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                statusIcon.setVisibility(View.VISIBLE);
                if (!"call".equals(message.getType())) {
                    updateStatusIcon(statusIcon, message);
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

                if ("voice".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    if (voiceMessageContainer != null) {
                        voiceMessageContainer.setVisibility(View.VISIBLE);
                        File localAudio = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".m4a");
                        boolean hasLocal = (localAudio != null && localAudio.exists() && localAudio.length() > 0)
                                || (message.getLocalFilePath() != null && new File(message.getLocalFilePath()).exists());

                        if (voiceWaveformView != null) {
                            int playedColor = itemView.getContext().getResources().getColor(R.color.voice_waveform_played);
                            int unplayedColor = itemView.getContext().getResources().getColor(R.color.voice_waveform_unplayed);
                            voiceWaveformView.setColors(playedColor, unplayedColor);
                            voiceWaveformView.setWaveformSeed(message.getId());
                            voiceWaveformView.setOnSeekListener((progress, fromUser) -> {
                                if (fromUser) VoicePlayerManager.getInstance().seekToFraction(progress);
                            });
                        }

                        int totalMs = VoicePlayerManager.getInstance().getAudioDuration(itemView.getContext(), message);
                        if (voiceDurationText != null) {
                            voiceDurationText.setText(totalMs > 0 ? VoicePlayerManager.formatDuration(totalMs) : "0:00");
                        }

                        if (voiceSpeedButton != null) {
                            float speed = VoicePlayerManager.getInstance().getSpeed();
                            voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", speed).replace(".0", ""));
                            voiceSpeedButton.setOnClickListener(v -> {
                                float nextSpeed = VoicePlayerManager.getInstance().cycleSpeed();
                                voiceSpeedButton.setText(String.format(Locale.getDefault(), "%.1fX", nextSpeed).replace(".0", ""));
                            });
                        }

                        if (!hasLocal && (message.getMediaUrl() == null || message.getMediaUrl().isEmpty())) {
                            if (voiceActionButton != null) voiceActionButton.setVisibility(View.GONE);
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.VISIBLE);
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
                                        int dur = VoicePlayerManager.getInstance().getAudioDuration(itemView.getContext(), message);
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
                } else if ("image".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[image")
                                && !message.getText().equals("[image message]");

                        if (hasCaption) {
                            if (imageCaptionText != null) {
                                imageCaptionText.setVisibility(View.VISIBLE);
                                imageCaptionText.setText(message.getText());
                            }
                            if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                            if (imageOverlayPill != null) {
                                imageOverlayPill.setVisibility(View.VISIBLE);
                                if (imageTimeText != null) imageTimeText.setText(time);
                                if (imageStatusIcon != null) updateOverlayStatusIcon(imageStatusIcon, message);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
                            Bitmap thumb = BlurUtils.getBlurredThumbnail(itemView.getContext(), message.getThumbnail());
                            if (thumb != null) {
                                messageImage.setImageBitmap(thumb);
                            } else {
                                byte[] decodedString = Base64.decode(message.getThumbnail(), Base64.DEFAULT);
                                BitmapFactory.Options opts = new BitmapFactory.Options();
                                opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                                Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length, opts);
                                messageImage.setImageBitmap(decodedByte);
                            }
                        } else {
                            messageImage.setImageBitmap(null);
                        }

                        ImageLoader.loadImage(
                            itemView.getContext(),
                            message,
                            null,
                            currentUserId,
                            messageImage,
                            imageProgressBar
                        );

                        View.OnClickListener openPhoto = v -> {
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".jpg");
                            if (local != null && local.exists()) {
                                Intent intent = new Intent(itemView.getContext(), MediaViewerActivity.class);
                                intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, local.getAbsolutePath());
                                intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "image");
                                intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, "You");
                                intent.putExtra(MediaViewerActivity.EXTRA_TIMESTAMP, time);
                                if (hasCaption) intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, message.getText());
                                itemView.getContext().startActivity(intent);
                            }
                        };
                        imageMessageContainer.setOnClickListener(openPhoto);
                        if (imageCardView != null) imageCardView.setOnClickListener(openPhoto);
                    }
                } else if ("video".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (videoMessageContainer != null) {
                        videoMessageContainer.setVisibility(View.VISIBLE);
                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[video")
                                && !message.getText().equals("[video message]");

                        if (hasCaption) {
                            if (videoCaptionText != null) {
                                videoCaptionText.setVisibility(View.VISIBLE);
                                videoCaptionText.setText(message.getText());
                            }
                            if (videoOverlayPill != null) videoOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            if (videoCaptionText != null) videoCaptionText.setVisibility(View.GONE);
                            if (videoOverlayPill != null) {
                                videoOverlayPill.setVisibility(View.VISIBLE);
                                if (videoTimeText != null) videoTimeText.setText(time);
                                if (videoStatusIcon != null) updateOverlayStatusIcon(videoStatusIcon, message);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        int dur = VoicePlayerManager.getInstance().getAudioDuration(itemView.getContext(), message);
                        if (videoDurationText != null) {
                            videoDurationText.setText(dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30");
                        }

                        if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
                            Bitmap thumb = BlurUtils.getBlurredThumbnail(itemView.getContext(), message.getThumbnail());
                            if (thumb != null) {
                                messageVideoThumbnail.setImageBitmap(thumb);
                            } else {
                                try {
                                    byte[] decodedString = Base64.decode(message.getThumbnail(), Base64.DEFAULT);
                                    BitmapFactory.Options opts = new BitmapFactory.Options();
                                    opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                                    Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length, opts);
                                    messageVideoThumbnail.setImageBitmap(decodedByte);
                                } catch (Exception ignored) {}
                            }
                        }

                        View.OnClickListener playClick = v -> {
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                            if (local != null && local.exists()) {
                                Intent intent = new Intent(itemView.getContext(), MediaViewerActivity.class);
                                intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, local.getAbsolutePath());
                                intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "video");
                                intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, "You");
                                intent.putExtra(MediaViewerActivity.EXTRA_TIMESTAMP, time);
                                if (hasCaption) intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, message.getText());
                                itemView.getContext().startActivity(intent);
                            } else {
                                if (videoPlayIcon != null) videoPlayIcon.setVisibility(View.GONE);
                                if (videoProgressBar != null) videoProgressBar.setVisibility(View.VISIBLE);
                                FileMediaManager.downloadAndDecryptMedia(itemView.getContext(), message, ".mp4", new FileMediaManager.DownloadCallback() {
                                    @Override
                                    public void onSuccess(File decryptedFile) {
                                        if (videoPlayIcon != null) videoPlayIcon.setVisibility(View.VISIBLE);
                                        if (videoProgressBar != null) videoProgressBar.setVisibility(View.GONE);
                                        Intent intent = new Intent(itemView.getContext(), MediaViewerActivity.class);
                                        intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, decryptedFile.getAbsolutePath());
                                        intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "video");
                                        intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, "You");
                                        intent.putExtra(MediaViewerActivity.EXTRA_TIMESTAMP, time);
                                        if (hasCaption) intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, message.getText());
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
                    }
                } else if ("document".equals(message.getType())) {
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
                        documentFileName.setText(name);

                        String ext = "DOC";
                        int dot = name.lastIndexOf(".");
                        if (dot != -1) {
                            ext = name.substring(dot + 1).toUpperCase(Locale.ROOT);
                            if (ext.length() > 4) ext = ext.substring(0, 4);
                        }

                        if (documentExtensionBadge != null) {
                            documentExtensionBadge.setText(ext);
                            documentExtensionBadge.setVisibility(View.VISIBLE);
                        }
                        if (documentIconContainer != null) {
                            int badgeColor = FileMediaManager.getDocumentBadgeColor(ext);
                            documentIconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(badgeColor));
                        }
                        String formattedInfo = ext + (size.isEmpty() ? "" : " • " + size);
                        documentFileSize.setText(formattedInfo);

                        documentMessageContainer.setOnClickListener(v -> {
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, "");
                            if (local.exists()) {
                                FileMediaManager.openFileWithSystemViewer(itemView.getContext(), local);
                            } else {
                                Toast.makeText(itemView.getContext(), "Document awaiting peer-to-peer WebRTC transfer", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } else if ("location".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    bindLocationCard(message, locationMessageContainer, locationTitle, locationSubtitle);
                } else if ("contact".equals(message.getType())) {
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
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindPollCard(message, pollMessageContainer, pollQuestionText, pollOptionsLinearLayout, pollTotalVotesText);
                } else if ("call".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindCallCard(message, callMessageContainer, callIconContainer, callIcon, callTitleText, callSubtitleText, true);
                    if (statusIcon != null) statusIcon.setVisibility(View.GONE);
                } else {
                    messageText.setVisibility(View.VISIBLE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    messageText.setText(formatRichTextAndPoll(itemView.getContext(), message));
                    bindLinkPreview(message.getText(), linkPreviewContainer, ivLinkThumb, tvLinkDomain, tvLinkTitle, tvLinkDesc);
                    if (message.getText() != null && message.getText().contains("||") && message.getId() != null) {
                        messageText.setOnClickListener(v -> {
                            if (revealedSpoilers.contains(message.getId())) {
                                revealedSpoilers.remove(message.getId());
                            } else {
                                revealedSpoilers.add(message.getId());
                            }
                            messageText.setText(formatRichTextAndPoll(itemView.getContext(), message));
                        });
                    } else {
                        messageText.setOnClickListener(null);
                    }
                }
                
                messageText.setTypeface(null, android.graphics.Typeface.NORMAL);
                messageText.setTextColor(itemView.getContext().getResources().getColor(R.color.message_text_primary));
                try {
                    android.content.SharedPreferences appPrefs = itemView.getContext().getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
                    float textSize = appPrefs.getFloat("chat_text_size_sp", 15.5f);
                    if (textSize > 0 && messageText != null) {
                        messageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize);
                    }
                } catch (Exception ignored) {}
                if ("call".equals(message.getType())) {
                    if (statusIcon != null) statusIcon.setVisibility(View.GONE);
                } else {
                    if (statusIcon != null) {
                        statusIcon.setVisibility(View.VISIBLE);
                        if (!"call".equals(message.getType())) {
                    updateStatusIcon(statusIcon, message);
                }
                    }
                }
                
                if (replyQuoteContainer != null) {
                    if (message.getReplyToMessageId() != null) {
                        Message replyTo = getMessageById(message.getReplyToMessageId());
                        if (replyTo != null && !"deleted".equals(replyTo.getType())) {
                            replyQuoteContainer.setVisibility(View.VISIBLE);
                            replyQuoteName.setText(message.getReplySenderName() != null ? message.getReplySenderName() : "User");
                            String quoteText = replyTo.getText();
                            if ("voice".equals(replyTo.getType())) quoteText = "🎤 Voice message";
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
                            replyQuoteName.setText(message.getReplySenderName() != null ? message.getReplySenderName() : "User");
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
                    updateStatusIcon(statusIcon, message);
                }
                
                if (message.getReactions() != null && !message.getReactions().isEmpty()) {
                    StringBuilder emojis = new StringBuilder();
                    for (String emoji : message.getReactions().values()) {
                        emojis.append(emoji);
                    }
                    reactionsText.setText(emojis.toString());
                    reactionsText.setVisibility(View.VISIBLE);
                    
                    reactionsText.setOnClickListener(v -> {
                        if (reactionClickListener != null) {
                            reactionClickListener.onReactionClick(message);
                        }
                    });
                } else {
                    reactionsText.setVisibility(View.GONE);
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
        
        // Image UI
        View imageMessageContainer;
        View imageCardView;
        ImageView messageImage;
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

        public ReceivedMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            dateHeaderContainer = itemView.findViewById(R.id.dateHeaderContainer);
            dateHeaderText = itemView.findViewById(R.id.dateHeaderText);
            messageText = itemView.findViewById(R.id.messageText);
            linkPreviewContainer = itemView.findViewById(R.id.linkPreviewContainer);
            ivLinkThumb = itemView.findViewById(R.id.ivLinkThumb);
            tvLinkDomain = itemView.findViewById(R.id.tvLinkDomain);
            tvLinkTitle = itemView.findViewById(R.id.tvLinkTitle);
            tvLinkDesc = itemView.findViewById(R.id.tvLinkDesc);
            linkPreviewContainer = itemView.findViewById(R.id.linkPreviewContainer);
            ivLinkThumb = itemView.findViewById(R.id.ivLinkThumb);
            tvLinkDomain = itemView.findViewById(R.id.tvLinkDomain);
            tvLinkTitle = itemView.findViewById(R.id.tvLinkTitle);
            tvLinkDesc = itemView.findViewById(R.id.tvLinkDesc);
            timeText = itemView.findViewById(R.id.timeText);
            reactionsText = itemView.findViewById(R.id.reactionsText);
            replyQuoteContainer = itemView.findViewById(R.id.replyQuoteContainer);
            replyQuoteName = itemView.findViewById(R.id.replyQuoteName);
            replyQuoteText = itemView.findViewById(R.id.replyQuoteText);
            messageBubble = itemView.findViewById(R.id.messageBubble);
            bubbleInfoContainer = itemView.findViewById(R.id.bubbleInfoContainer);
            
            voiceMessageContainer = itemView.findViewById(R.id.voiceMessageContainer);
            voiceActionButton = itemView.findViewById(R.id.voiceActionButton);
            voiceProgressBar = itemView.findViewById(R.id.voiceProgressBar);
            voiceWaveformView = itemView.findViewById(R.id.voiceWaveformView);
            voiceDurationText = itemView.findViewById(R.id.voiceDurationText);
            voiceSpeedButton = itemView.findViewById(R.id.voiceSpeedButton);
            voiceMicBadge = itemView.findViewById(R.id.voiceMicBadge);
            voiceLine = itemView.findViewById(R.id.voiceLine);
            voiceSeekBar = itemView.findViewById(R.id.voiceSeekBar);
            
            imageMessageContainer = itemView.findViewById(R.id.imageMessageContainer);
            imageCardView = itemView.findViewById(R.id.imageCardView);
            messageImage = itemView.findViewById(R.id.messageImage);
            imageProgressBar = itemView.findViewById(R.id.imageProgressBar);
            imageOverlayPill = itemView.findViewById(R.id.imageOverlayPill);
            imageTimeText = itemView.findViewById(R.id.imageTimeText);
            imageCaptionText = itemView.findViewById(R.id.imageCaptionText);

            videoMessageContainer = itemView.findViewById(R.id.videoMessageContainer);
            videoCardView = itemView.findViewById(R.id.videoCardView);
            messageVideoThumbnail = itemView.findViewById(R.id.messageVideoThumbnail);
            videoPlayButtonContainer = itemView.findViewById(R.id.videoPlayButtonContainer);
            videoPlayIcon = itemView.findViewById(R.id.videoPlayIcon);
            videoProgressBar = itemView.findViewById(R.id.videoProgressBar);
            videoOverlayPill = itemView.findViewById(R.id.videoOverlayPill);
            videoTimeText = itemView.findViewById(R.id.videoTimeText);
            videoDurationContainer = itemView.findViewById(R.id.videoDurationContainer);
            videoDurationText = itemView.findViewById(R.id.videoDurationText);
            videoCaptionText = itemView.findViewById(R.id.videoCaptionText);

            documentMessageContainer = itemView.findViewById(R.id.documentMessageContainer);
            documentIconContainer = itemView.findViewById(R.id.documentIconContainer);
            documentExtensionBadge = itemView.findViewById(R.id.documentExtensionBadge);
            documentFileName = itemView.findViewById(R.id.documentFileName);
            documentFileSize = itemView.findViewById(R.id.documentFileSize);
            documentActionIcon = itemView.findViewById(R.id.documentActionIcon);
            documentProgressBar = itemView.findViewById(R.id.documentProgressBar);

            locationMessageContainer = itemView.findViewById(R.id.locationMessageContainer);
            locationTitle = itemView.findViewById(R.id.locationTitle);
            locationSubtitle = itemView.findViewById(R.id.locationSubtitle);

            contactMessageContainer = itemView.findViewById(R.id.contactMessageContainer);
            contactName = itemView.findViewById(R.id.contactName);
            contactPhone = itemView.findViewById(R.id.contactPhone);
            contactAction = itemView.findViewById(R.id.contactAction);
            contactActionMessage = itemView.findViewById(R.id.contactActionMessage);
            contactActionSave = itemView.findViewById(R.id.contactActionSave);

            pollMessageContainer = itemView.findViewById(R.id.pollMessageContainer);
            pollQuestionText = itemView.findViewById(R.id.pollQuestionText);
            pollOptionsLinearLayout = itemView.findViewById(R.id.pollOptionsLinearLayout);
            pollTotalVotesText = itemView.findViewById(R.id.pollTotalVotesText);

            blinkMessageContainer = itemView.findViewById(R.id.blinkMessageContainer);
            blinkBadgeIcon = itemView.findViewById(R.id.blinkBadgeIcon);
            blinkProgressBar = itemView.findViewById(R.id.blinkProgressBar);
            blinkTitleText = itemView.findViewById(R.id.blinkTitleText);
            blinkSubtitleText = itemView.findViewById(R.id.blinkSubtitleText);

            imageDownloadOverlay = itemView.findViewById(R.id.imageDownloadOverlay);
            imageDownloadIdleLayout = itemView.findViewById(R.id.imageDownloadIdleLayout);
            imageDownloadSizeText = itemView.findViewById(R.id.imageDownloadSizeText);
            imageDownloadingLayout = itemView.findViewById(R.id.imageDownloadingLayout);
            imageDownloadProgress = itemView.findViewById(R.id.imageDownloadProgress);
            imageDownloadCancelBtn = itemView.findViewById(R.id.imageDownloadCancelBtn);
            imageDownloadRetryLayout = itemView.findViewById(R.id.imageDownloadRetryLayout);

            videoDownloadOverlay = itemView.findViewById(R.id.videoDownloadOverlay);
            videoDownloadIdleLayout = itemView.findViewById(R.id.videoDownloadIdleLayout);
            videoDownloadSizeText = itemView.findViewById(R.id.videoDownloadSizeText);
            videoDownloadingLayout = itemView.findViewById(R.id.videoDownloadingLayout);
            videoDownloadProgress = itemView.findViewById(R.id.videoDownloadProgress);
            videoDownloadCancelBtn = itemView.findViewById(R.id.videoDownloadCancelBtn);
            videoDownloadRetryLayout = itemView.findViewById(R.id.videoDownloadRetryLayout);

            callMessageContainer = itemView.findViewById(R.id.callMessageContainer);
            callIconContainer = itemView.findViewById(R.id.callIconContainer);
            callIcon = itemView.findViewById(R.id.callIcon);
            callTitleText = itemView.findViewById(R.id.callTitleText);
            callSubtitleText = itemView.findViewById(R.id.callSubtitleText);

            tvStarredBadge = itemView.findViewById(R.id.tvStarredBadge);
            tvVanishBadge = itemView.findViewById(R.id.tvVanishBadge);
            tvEditedBadge = itemView.findViewById(R.id.tvEditedBadge);
        }

        private void hideAllMediaContainers() {
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

        public void bind(Message message, String time) {
            bind(message, time, false, "");
        }

        public void bind(Message message, String time, boolean showDate, String dateLabel) {
            if (dateHeaderContainer != null && dateHeaderText != null) {
                if (showDate && dateLabel != null && !dateLabel.isEmpty()) {
                    dateHeaderContainer.setVisibility(View.VISIBLE);
                    dateHeaderText.setText(dateLabel);
                } else {
                    dateHeaderContainer.setVisibility(View.GONE);
                }
            }
            timeText.setText(time);
            bindBubbleInfoBadges(message, tvStarredBadge, tvVanishBadge, tvEditedBadge);
            if (messageBubble instanceof com.m3.veil.ui.views.ChatMessageCell) {
                com.m3.veil.ui.views.ChatMessageCell cell = (com.m3.veil.ui.views.ChatMessageCell) messageBubble;
                int pos = getAdapterPosition() - 1;
                cell.setMessageObject(message, false, computeClusterPosition(pos), true);
            }
            attachDoubleTapAndLongPress(messageBubble, itemView, message);
            
            if ("deleted".equals(message.getType())) {
                messageText.setVisibility(View.VISIBLE);
                hideAllMediaContainers();
                messageText.setText("🚫 This message was deleted");
                messageText.setTypeface(null, android.graphics.Typeface.ITALIC);
                messageText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.message_text_secondary));
                reactionsText.setVisibility(View.GONE);
                if (replyQuoteContainer != null) replyQuoteContainer.setVisibility(View.GONE);
            } else if (message.isBlink()) {
                hideAllMediaContainers();
                messageText.setVisibility(View.GONE);
                if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                bindBlinkCard(message, false, blinkMessageContainer, blinkBadgeIcon, blinkProgressBar, blinkTitleText, blinkSubtitleText);
                itemView.setOnLongClickListener(v -> {
                    if (longClickListener != null) {
                        longClickListener.onMessageLongClick(message, itemView);
                        return true;
                    }
                    return false;
                });
            } else {
                hideAllMediaContainers();

                if ("voice".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    if (voiceMessageContainer != null) {
                        voiceMessageContainer.setVisibility(View.VISIBLE);
                        File localAudio = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".m4a");
                        boolean hasLocal = (localAudio != null && localAudio.exists() && localAudio.length() > 0)
                                || (message.getLocalFilePath() != null && new File(message.getLocalFilePath()).exists());

                        final int voiceDownloadState = hasLocal ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);

                        if (voiceWaveformView != null) {
                            int playedColor = itemView.getContext().getResources().getColor(R.color.voice_waveform_played);
                            int unplayedColor = itemView.getContext().getResources().getColor(R.color.voice_waveform_unplayed);
                            voiceWaveformView.setColors(playedColor, unplayedColor);
                            voiceWaveformView.setWaveformSeed(message.getId());
                            voiceWaveformView.setOnSeekListener((progress, fromUser) -> {
                                if (fromUser) VoicePlayerManager.getInstance().seekToFraction(progress);
                            });
                        }

                        if (voiceDownloadState == STATE_DOWNLOADED) {
                            int totalMs = VoicePlayerManager.getInstance().getAudioDuration(itemView.getContext(), message);
                            if (voiceDurationText != null) {
                                voiceDurationText.setText(totalMs > 0 ? VoicePlayerManager.formatDuration(totalMs) : "0:00");
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
                                        int dur = VoicePlayerManager.getInstance().getAudioDuration(itemView.getContext(), message);
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
                            if (voiceActionButton != null) voiceActionButton.setVisibility(View.GONE);
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.VISIBLE);
                            if (voiceDurationText != null) {
                                voiceDurationText.setText("Downloading...");
                            }
                        } else {
                            if (voiceProgressBar != null) voiceProgressBar.setVisibility(View.GONE);
                            if (voiceActionButton != null) {
                                voiceActionButton.setImageResource(voiceDownloadState == STATE_RETRY ? R.drawable.ic_undo : R.drawable.ic_download);
                                voiceActionButton.setVisibility(View.VISIBLE);
                                voiceActionButton.setOnClickListener(v -> {
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
                                voiceDurationText.setText(sizeStr.isEmpty() ? "Voice note" : sizeStr);
                            }
                        }
                    }
                } else if ("image".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (imageMessageContainer != null) {
                        imageMessageContainer.setVisibility(View.VISIBLE);
                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[image")
                                && !message.getText().equals("[image message]");

                        if (hasCaption) {
                            if (imageCaptionText != null) {
                                imageCaptionText.setVisibility(View.VISIBLE);
                                imageCaptionText.setText(message.getText());
                            }
                            if (imageOverlayPill != null) imageOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            if (imageCaptionText != null) imageCaptionText.setVisibility(View.GONE);
                            if (imageOverlayPill != null) {
                                imageOverlayPill.setVisibility(View.VISIBLE);
                                if (imageTimeText != null) imageTimeText.setText(time);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        File localImg = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".jpg");
                        boolean hasLocalImg = (localImg != null && localImg.exists() && localImg.length() > 0)
                                || (message.getLocalFilePath() != null && new File(message.getLocalFilePath()).exists());

                        final int imgDownloadState = hasLocalImg ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);

                        if (imgDownloadState == STATE_DOWNLOADED) {
                            if (imageDownloadOverlay != null) imageDownloadOverlay.setVisibility(View.GONE);
                            ImageLoader.loadImage(
                                itemView.getContext(),
                                message,
                                null,
                                currentUserId,
                                messageImage,
                                imageProgressBar
                            );

                            View.OnClickListener openPhoto = v -> {
                                File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".jpg");
                                if (local != null && local.exists()) {
                                    Intent intent = new Intent(itemView.getContext(), MediaViewerActivity.class);
                                    intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, local.getAbsolutePath());
                                    intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "image");
                                    intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, message.getSenderName() != null ? message.getSenderName() : "Contact");
                                    intent.putExtra(MediaViewerActivity.EXTRA_TIMESTAMP, time);
                                    if (hasCaption) intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, message.getText());
                                    itemView.getContext().startActivity(intent);
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
                                messageImage.setImageBitmap(null);
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
                                    if (imageDownloadIdleLayout != null) imageDownloadIdleLayout.setVisibility(View.GONE);
                                    if (imageDownloadingLayout != null) imageDownloadingLayout.setVisibility(View.VISIBLE);
                                    if (imageDownloadRetryLayout != null) imageDownloadRetryLayout.setVisibility(View.GONE);
                                    if (imageDownloadProgress != null) {
                                        int progress = downloadProgresses.getOrDefault(message.getId(), 0);
                                        imageDownloadProgress.setIndeterminate(progress <= 0);
                                        if (progress > 0) imageDownloadProgress.setProgress(progress);
                                    }
                                    if (imageDownloadCancelBtn != null) {
                                        imageDownloadCancelBtn.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaCancelClick(message);
                                            }
                                        });
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
                    messageText.setVisibility(View.GONE);
                    if (videoMessageContainer != null) {
                        videoMessageContainer.setVisibility(View.VISIBLE);
                        boolean hasCaption = message.getText() != null && !message.getText().trim().isEmpty()
                                && !message.getText().startsWith("[video")
                                && !message.getText().equals("[video message]");

                        if (hasCaption) {
                            if (videoCaptionText != null) {
                                videoCaptionText.setVisibility(View.VISIBLE);
                                videoCaptionText.setText(message.getText());
                            }
                            if (videoOverlayPill != null) videoOverlayPill.setVisibility(View.GONE);
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                        } else {
                            if (videoCaptionText != null) videoCaptionText.setVisibility(View.GONE);
                            if (videoOverlayPill != null) {
                                videoOverlayPill.setVisibility(View.VISIBLE);
                                if (videoTimeText != null) videoTimeText.setText(time);
                            }
                            if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.GONE);
                        }

                        int dur = VoicePlayerManager.getInstance().getAudioDuration(itemView.getContext(), message);
                        if (videoDurationText != null) {
                            videoDurationText.setText(dur > 0 ? VoicePlayerManager.formatDuration(dur) : "0:30");
                        }

                        File localVid = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                        boolean hasLocalVid = (localVid != null && localVid.exists() && localVid.length() > 0)
                                || (message.getLocalFilePath() != null && new File(message.getLocalFilePath()).exists());

                        final int vidDownloadState = hasLocalVid ? STATE_DOWNLOADED : downloadStates.getOrDefault(message.getId(), STATE_IDLE);

                        if (vidDownloadState == STATE_DOWNLOADED) {
                            if (videoDownloadOverlay != null) videoDownloadOverlay.setVisibility(View.GONE);
                            if (videoPlayButtonContainer != null) videoPlayButtonContainer.setVisibility(View.VISIBLE);

                            if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
                                Bitmap thumb = BlurUtils.getBlurredThumbnail(itemView.getContext(), message.getThumbnail());
                                if (thumb != null) {
                                    messageVideoThumbnail.setImageBitmap(thumb);
                                } else {
                                    try {
                                        byte[] decodedString = Base64.decode(message.getThumbnail(), Base64.DEFAULT);
                                        BitmapFactory.Options opts = new BitmapFactory.Options();
                                        opts.inPreferredConfig = com.m3.veil.utils.VeilPerformanceController.getInstance().getPreferredBitmapConfig();
                                        Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length, opts);
                                        messageVideoThumbnail.setImageBitmap(decodedByte);
                                    } catch (Exception ignored) {}
                                }
                            }

                            View.OnClickListener playClick = v -> {
                                File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, ".mp4");
                                if (local != null && local.exists()) {
                                    Intent intent = new Intent(itemView.getContext(), MediaViewerActivity.class);
                                    intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, local.getAbsolutePath());
                                    intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "video");
                                    intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, message.getSenderName() != null ? message.getSenderName() : "Contact");
                                    intent.putExtra(MediaViewerActivity.EXTRA_TIMESTAMP, time);
                                    if (hasCaption) intent.putExtra(MediaViewerActivity.EXTRA_CAPTION, message.getText());
                                    itemView.getContext().startActivity(intent);
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
                                messageVideoThumbnail.setImageBitmap(null);
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
                                    if (videoDownloadIdleLayout != null) videoDownloadIdleLayout.setVisibility(View.GONE);
                                    if (videoDownloadingLayout != null) videoDownloadingLayout.setVisibility(View.VISIBLE);
                                    if (videoDownloadRetryLayout != null) videoDownloadRetryLayout.setVisibility(View.GONE);
                                    if (videoDownloadProgress != null) {
                                        int progress = downloadProgresses.getOrDefault(message.getId(), 0);
                                        videoDownloadProgress.setIndeterminate(progress <= 0);
                                        if (progress > 0) videoDownloadProgress.setProgress(progress);
                                    }
                                    if (videoDownloadCancelBtn != null) {
                                        videoDownloadCancelBtn.setOnClickListener(v -> {
                                            if (mediaDownloadClickListener != null) {
                                                mediaDownloadClickListener.onMediaCancelClick(message);
                                            }
                                        });
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
                        documentFileName.setText(name);

                        String ext = "DOC";
                        int dot = name.lastIndexOf(".");
                        if (dot != -1) {
                            ext = name.substring(dot + 1).toUpperCase(Locale.ROOT);
                            if (ext.length() > 4) ext = ext.substring(0, 4);
                        }

                        if (documentExtensionBadge != null) {
                            documentExtensionBadge.setText(ext);
                            documentExtensionBadge.setVisibility(View.VISIBLE);
                        }
                        if (documentIconContainer != null) {
                            int badgeColor = FileMediaManager.getDocumentBadgeColor(ext);
                            documentIconContainer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(badgeColor));
                        }
                        String formattedInfo = ext + (size.isEmpty() ? "" : " • " + size);
                        documentFileSize.setText(formattedInfo);

                        documentMessageContainer.setOnClickListener(v -> {
                            File local = FileMediaManager.getDecryptedFile(itemView.getContext(), message, "");
                            if (local.exists()) {
                                FileMediaManager.openFileWithSystemViewer(itemView.getContext(), local);
                            } else {
                                Toast.makeText(itemView.getContext(), "Document awaiting peer-to-peer WebRTC transfer", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } else if ("location".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    bindLocationCard(message, locationMessageContainer, locationTitle, locationSubtitle);
                } else if ("contact".equals(message.getType())) {
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
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindPollCard(message, pollMessageContainer, pollQuestionText, pollOptionsLinearLayout, pollTotalVotesText);
                } else if ("call".equals(message.getType())) {
                    messageText.setVisibility(View.GONE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    bindCallCard(message, callMessageContainer, callIconContainer, callIcon, callTitleText, callSubtitleText, false);
                } else {
                    messageText.setVisibility(View.VISIBLE);
                    if (bubbleInfoContainer != null) bubbleInfoContainer.setVisibility(View.VISIBLE);
                    messageText.setText(formatRichTextAndPoll(itemView.getContext(), message));
                    bindLinkPreview(message.getText(), linkPreviewContainer, ivLinkThumb, tvLinkDomain, tvLinkTitle, tvLinkDesc);
                    if (message.getText() != null && message.getText().contains("||") && message.getId() != null) {
                        messageText.setOnClickListener(v -> {
                            if (revealedSpoilers.contains(message.getId())) {
                                revealedSpoilers.remove(message.getId());
                            } else {
                                revealedSpoilers.add(message.getId());
                            }
                            messageText.setText(formatRichTextAndPoll(itemView.getContext(), message));
                        });
                    } else {
                        messageText.setOnClickListener(null);
                    }
                }
                
                messageText.setTypeface(null, android.graphics.Typeface.NORMAL);
                messageText.setTextColor(itemView.getContext().getResources().getColor(R.color.message_text_primary));
                try {
                    android.content.SharedPreferences appPrefs = itemView.getContext().getSharedPreferences("veil_appearance_prefs", Context.MODE_PRIVATE);
                    float textSize = appPrefs.getFloat("chat_text_size_sp", 15.5f);
                    if (textSize > 0 && messageText != null) {
                        messageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize);
                    }
                } catch (Exception ignored) {}
                
                if (replyQuoteContainer != null) {
                    if (message.getReplyToMessageId() != null) {
                        Message replyTo = getMessageById(message.getReplyToMessageId());
                        if (replyTo != null && !"deleted".equals(replyTo.getType())) {
                            replyQuoteContainer.setVisibility(View.VISIBLE);
                            replyQuoteName.setText(message.getReplySenderName() != null ? message.getReplySenderName() : "User");
                            String quoteText = replyTo.getText();
                            if ("voice".equals(replyTo.getType())) quoteText = "🎤 Voice message";
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
                            replyQuoteName.setText(message.getReplySenderName() != null ? message.getReplySenderName() : "User");
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
                
                if (message.getReactions() != null && !message.getReactions().isEmpty()) {
                    StringBuilder emojis = new StringBuilder();
                    for (String emoji : message.getReactions().values()) {
                        emojis.append(emoji);
                    }
                    reactionsText.setText(emojis.toString());
                    reactionsText.setVisibility(View.VISIBLE);
                    
                    reactionsText.setOnClickListener(v -> {
                        if (reactionClickListener != null) {
                            reactionClickListener.onReactionClick(message);
                        }
                    });
                } else {
                    reactionsText.setVisibility(View.GONE);
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
}