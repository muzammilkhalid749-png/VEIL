package com.m3.veil.ui.main.adapters;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.Comment;

import java.util.List;

public class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.CommentViewHolder> {

    public interface OnCommentReplyListener {
        void onReplyRequested(Comment parentComment);
    }

    private List<Comment> commentList;
    private OnCommentReplyListener replyListener;
    private String postAuthor;

    public CommentsAdapter(List<Comment> commentList) {
        this(commentList, null);
    }

    public CommentsAdapter(List<Comment> commentList, String postAuthor) {
        this.commentList = commentList;
        this.postAuthor = postAuthor;
    }

    public void setPostAuthor(String postAuthor) {
        this.postAuthor = postAuthor;
    }

    public void setOnCommentReplyListener(OnCommentReplyListener listener) {
        this.replyListener = listener;
    }

    @NonNull
    @Override
    public CommentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment, parent, false);
        return new CommentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {
        Comment comment = commentList.get(position);
        holder.bind(comment, postAuthor, replyListener);
    }

    @Override
    public int getItemCount() {
        return commentList != null ? commentList.size() : 0;
    }

    public void updateComments(List<Comment> newComments) {
        this.commentList = newComments;
        notifyDataSetChanged();
    }

    static class CommentViewHolder extends RecyclerView.ViewHolder {
        TextView author, time, text;

        public CommentViewHolder(@NonNull View itemView) {
            super(itemView);
            author = itemView.findViewById(R.id.commentAuthor);
            time = itemView.findViewById(R.id.commentTime);
            text = itemView.findViewById(R.id.commentText);
        }

        public void bind(Comment comment, String postAuthor, OnCommentReplyListener replyListener) {
            String rawUser = comment.getAuthorUsername() != null ? comment.getAuthorUsername() : "anonymous";
            String username = rawUser.startsWith("@") ? rawUser : "@" + rawUser;

            boolean isOp = postAuthor != null && !postAuthor.isEmpty() &&
                    (postAuthor.equalsIgnoreCase(rawUser) ||
                     postAuthor.equalsIgnoreCase(comment.getAuthorId()) ||
                     ("@" + postAuthor).equalsIgnoreCase(rawUser));
            String opBadge = isOp ? " [OP]" : "";

            int depth = Math.min(1, Math.max(0, comment.getDepth())); // 2-level nested comments (#93)
            if (depth > 0) {
                author.setText("↳ " + username + opBadge + " • Reply");
            } else {
                author.setText(username + opBadge + " • Tap to reply");
            }
            text.setText(comment.getText());

            float density = itemView.getResources().getDisplayMetrics().density;
            int leftPad = (int) ((depth > 0 ? 32 : 8) * density);
            itemView.setPadding(leftPad, itemView.getPaddingTop(), itemView.getPaddingRight(), itemView.getPaddingBottom());

            if (comment.getTimestamp() != null) {
                CharSequence relative = DateUtils.getRelativeTimeSpanString(
                        comment.getTimestamp().toDate().getTime(),
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS);
                time.setText(relative);
            } else {
                time.setText("Just now");
            }

            itemView.setOnClickListener(v -> {
                if (replyListener != null) {
                    replyListener.onReplyRequested(comment);
                }
            });
        }
    }
}
