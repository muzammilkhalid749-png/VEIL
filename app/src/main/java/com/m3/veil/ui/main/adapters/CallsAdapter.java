package com.m3.veil.ui.main.adapters;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.CallRecord;
import com.m3.veil.utils.UiAnimator;

import java.util.List;

public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.CallViewHolder> {

    public interface OnCallItemClickListener {
        void onCallClick(CallRecord call);
        void onCallBackClick(CallRecord call);
        default void onCallLongClick(CallRecord call) {}
    }

    private List<CallRecord> callList;
    private String currentUserId;
    private OnCallItemClickListener listener;

    public CallsAdapter(List<CallRecord> callList, String currentUserId, OnCallItemClickListener listener) {
        this.callList = callList;
        this.currentUserId = currentUserId;
        this.listener = listener;
    }

    @NonNull
    @Override
    public CallViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_call, parent, false);
        return new CallViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CallViewHolder holder, int position) {
        CallRecord record = callList.get(position);
        holder.bind(record, currentUserId, listener);
    }

    @Override
    public int getItemCount() {
        return callList.size();
    }

    public void updateCalls(List<CallRecord> newCalls) {
        this.callList = newCalls;
        notifyDataSetChanged();
    }

    static class CallViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar, statusIcon, btnCallBack;
        TextView contactName, callTime;

        public CallViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.callAvatar);
            statusIcon = itemView.findViewById(R.id.callStatusIcon);
            btnCallBack = itemView.findViewById(R.id.btnCallBack);
            contactName = itemView.findViewById(R.id.callContactName);
            callTime = itemView.findViewById(R.id.callTime);

            UiAnimator.attachSpringPress(btnCallBack);
        }

        private static boolean isSelfName(String name) {
            if (name == null) return true;
            String clean = name.trim();
            return clean.isEmpty() || "Me".equalsIgnoreCase(clean) || "You".equalsIgnoreCase(clean)
                    || "local_user".equalsIgnoreCase(clean) || "null".equalsIgnoreCase(clean);
        }

        public void bind(CallRecord record, String currentUserId, OnCallItemClickListener listener) {
            String myId = (currentUserId != null && !currentUserId.isEmpty() && !"local_user".equals(currentUserId))
                    ? currentUserId : (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null
                    ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : "");
            android.content.Context ctx = itemView.getContext();

            boolean isMissed = "missed".equalsIgnoreCase(record.getStatus())
                    || "declined".equalsIgnoreCase(record.getStatus())
                    || "failed".equalsIgnoreCase(record.getStatus());
            boolean isOutgoing = "outgoing".equalsIgnoreCase(record.getStatus())
                    || (record.getCallerId() != null && !record.getCallerId().isEmpty() && record.getCallerId().equals(myId))
                    || isSelfName(record.getCallerName());

            if (record.getReceiverId() != null && record.getReceiverId().equals(myId)) {
                isOutgoing = false;
            }
            if (isMissed || "incoming".equalsIgnoreCase(record.getStatus())) {
                isOutgoing = false;
            }

            // Determine peer name with multi-tier resolution
            String displayName = null;
            if (record.getPeerName() != null && !record.getPeerName().trim().isEmpty() && !isSelfName(record.getPeerName())) {
                displayName = record.getPeerName().trim();
            }
            if (displayName == null || displayName.isEmpty() || isSelfName(displayName)) {
                String candidate = isOutgoing ? record.getReceiverName() : record.getCallerName();
                if (candidate != null && !candidate.trim().isEmpty() && !isSelfName(candidate)) {
                    displayName = candidate.trim();
                }
            }
            if (displayName == null || displayName.isEmpty() || isSelfName(displayName)) {
                String candidate = isOutgoing ? record.getCallerName() : record.getReceiverName();
                if (candidate != null && !candidate.trim().isEmpty() && !isSelfName(candidate)) {
                    displayName = candidate.trim();
                }
            }
            String resolvedPeerId = record.getPeerId();
            if (resolvedPeerId == null || resolvedPeerId.isEmpty() || resolvedPeerId.equals(myId) || "local_user".equals(resolvedPeerId)) {
                resolvedPeerId = isOutgoing ? record.getReceiverId() : record.getCallerId();
                if (resolvedPeerId == null || resolvedPeerId.isEmpty() || resolvedPeerId.equals(myId) || "local_user".equals(resolvedPeerId)) {
                    resolvedPeerId = isOutgoing ? record.getCallerId() : record.getReceiverId();
                }
            }
            if (displayName == null || displayName.isEmpty() || isSelfName(displayName)) {
                if (resolvedPeerId != null && !resolvedPeerId.isEmpty() && !resolvedPeerId.equals(myId) && !"local_user".equals(resolvedPeerId)) {
                    com.m3.veil.model.Chat c = com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getChat(resolvedPeerId);
                    if (c != null && c.getName() != null && !c.getName().trim().isEmpty() && !isSelfName(c.getName())) {
                        displayName = c.getName().trim();
                    }
                }
            }
            if (displayName == null || displayName.trim().isEmpty() || isSelfName(displayName)) {
                displayName = "Veil Contact";
            }
            contactName.setText(displayName);

            // Avatar rendering
            boolean avatarLoaded = false;
            if (resolvedPeerId != null && !resolvedPeerId.isEmpty() && !resolvedPeerId.equals(myId)) {
                com.m3.veil.model.Chat chat = com.m3.veil.database.EncryptedChatDatabase.getInstance(ctx).getChat(resolvedPeerId);
                if (chat != null && chat.getAvatar() != null && !chat.getAvatar().isEmpty() && !chat.getAvatar().startsWith("http")) {
                    try {
                        byte[] decoded = android.util.Base64.decode(chat.getAvatar(), android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                        if (bmp != null) {
                            avatar.setImageBitmap(bmp);
                            avatarLoaded = true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (!avatarLoaded) {
                avatar.setImageResource(R.drawable.ic_profile_placeholder);
            }

            // Time & direction text
            boolean isVideo = "video".equalsIgnoreCase(record.getCallType());
            long timeMs = record.getTimestamp() != null ? record.getTimestamp().toDate().getTime() : System.currentTimeMillis();
            CharSequence relative = DateUtils.getRelativeTimeSpanString(
                    timeMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);

            String dirPrefix;
            if (isMissed) {
                dirPrefix = "Missed ";
            } else if (isOutgoing) {
                dirPrefix = "Outgoing ";
            } else {
                dirPrefix = "Incoming ";
            }
            String typeStr = isVideo ? "video call • " : "voice call • ";

            String durationSuffix = "";
            if (record.getDurationSeconds() > 0) {
                int sec = record.getDurationSeconds();
                int m = sec / 60;
                int s = sec % 60;
                durationSuffix = " (" + (m > 0 ? (m + "m " + s + "s") : (s + "s")) + ")";
            }
            callTime.setText(dirPrefix + typeStr + relative + durationSuffix);

            // Direction & status icon (WhatsApp / Telegram style)
            if (isMissed) {
                statusIcon.setImageResource(R.drawable.ic_call_incoming);
                statusIcon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.colorError));
            } else if (isOutgoing) {
                statusIcon.setImageResource(R.drawable.ic_call_outgoing);
                statusIcon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.colorSuccess));
            } else {
                statusIcon.setImageResource(R.drawable.ic_call_incoming);
                statusIcon.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.colorPrimary));
            }

            // Call type icon on callback button
            if (isVideo) {
                btnCallBack.setImageResource(R.drawable.ic_video);
            } else {
                btnCallBack.setImageResource(R.drawable.ic_call);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onCallClick(record);
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onCallLongClick(record);
                return true;
            });

            btnCallBack.setOnClickListener(v -> {
                if (listener != null) listener.onCallBackClick(record);
            });
        }
    }
}
