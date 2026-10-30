package com.m3.veil.ui.chat.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.GroupMentionCandidate;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.utils.VeilAvatarLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * MentionSuggestionsAdapter — High-performance modern adapter for group @mention autocomplete.
 */
public class MentionSuggestionsAdapter extends RecyclerView.Adapter<MentionSuggestionsAdapter.MentionViewHolder> {

    public interface OnMentionClickListener {
        void onMentionSelected(@NonNull GroupMentionCandidate candidate);
    }

    private final List<GroupMentionCandidate> mItems = new ArrayList<>();
    private final OnMentionClickListener mListener;

    public MentionSuggestionsAdapter(@NonNull OnMentionClickListener listener) {
        this.mListener = listener;
    }

    public MentionSuggestionsAdapter(@NonNull Context context, @NonNull OnMentionClickListener listener) {
        this.mListener = listener;
    }

    public void submitList(List<GroupMentionCandidate> newCandidates) {
        updateList(newCandidates);
    }

    public void updateList(List<GroupMentionCandidate> newCandidates) {
        mItems.clear();
        if (newCandidates != null) {
            mItems.addAll(newCandidates);
        }
        notifyDataSetChanged();
    }

    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public MentionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mention_suggestion, parent, false);
        return new MentionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MentionViewHolder holder, int position) {
        GroupMentionCandidate candidate = mItems.get(position);
        holder.bind(candidate, mListener);
    }

    static class MentionViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAvatar;
        private final TextView tvDisplayName;
        private final TextView tvUsername;
        private final TextView tvBadge;

        public MentionViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.ivMentionAvatar);
            tvDisplayName = itemView.findViewById(R.id.tvMentionDisplayName);
            tvUsername = itemView.findViewById(R.id.tvMentionUsername);
            tvBadge = itemView.findViewById(R.id.tvMentionBadge);
            UiAnimator.attachSpringPress(itemView);
        }

        public void bind(@NonNull GroupMentionCandidate candidate, @NonNull OnMentionClickListener listener) {
            tvDisplayName.setText(candidate.getDisplayName());
            String cleanUname = candidate.getUsername().startsWith("@") 
                    ? candidate.getUsername() 
                    : "@" + candidate.getUsername();
            tvUsername.setText(cleanUname);

            if (candidate.isOwner()) {
                tvBadge.setVisibility(View.VISIBLE);
                tvBadge.setText("👑 Owner");
            } else if (candidate.isAdmin()) {
                tvBadge.setVisibility(View.VISIBLE);
                tvBadge.setText("🛡️ Admin");
            } else {
                tvBadge.setVisibility(View.GONE);
            }

            // Load avatar if present
            Context ctx = itemView.getContext();
            String avatarStr = candidate.getAvatar();
            if (avatarStr != null && !avatarStr.isEmpty()) {
                if (avatarStr.startsWith("http://") || avatarStr.startsWith("https://")) {
                    VeilAvatarLoader.loadAvatar(ctx, avatarStr, ivAvatar);
                } else if (avatarStr.length() > 50) {
                    try {
                        byte[] decoded = Base64.decode(avatarStr, Base64.DEFAULT);
                        Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                        if (bmp != null) {
                            ivAvatar.setImageBitmap(bmp);
                            ivAvatar.setPadding(0, 0, 0, 0);
                        } else {
                            setDefaultAvatar();
                        }
                    } catch (Throwable t) {
                        setDefaultAvatar();
                    }
                } else {
                    setDefaultAvatar();
                }
            } else {
                setDefaultAvatar();
            }

            itemView.setOnClickListener(v -> listener.onMentionSelected(candidate));
        }

        private void setDefaultAvatar() {
            float dp = itemView.getResources().getDisplayMetrics().density;
            ivAvatar.setImageResource(R.drawable.ic_person);
            ivAvatar.setPadding((int)(6 * dp), (int)(6 * dp), (int)(6 * dp), (int)(6 * dp));
        }
    }
}
