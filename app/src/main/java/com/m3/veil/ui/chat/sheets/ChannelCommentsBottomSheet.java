package com.m3.veil.ui.chat.sheets;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.m3.veil.R;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Persona;
import com.m3.veil.utils.VeilFirestoreUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Telegram-Grade Broadcast Channel Post Discussion Sheet.
 * Allows subscribers and admins to leave comments on any channel broadcast post,
 * with real-time Firestore synchronization and active persona attribution.
 */
public class ChannelCommentsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_CHANNEL_ID = "channel_id";
    private static final String ARG_POST_ID = "post_id";
    private static final String ARG_POST_TEXT = "post_text";
    private static final String ARG_POST_AUTHOR = "post_author";

    public static ChannelCommentsBottomSheet newInstance(String channelId, String postId, String postText, String postAuthor) {
        ChannelCommentsBottomSheet sheet = new ChannelCommentsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CHANNEL_ID, channelId);
        args.putString(ARG_POST_ID, postId);
        args.putString(ARG_POST_TEXT, postText);
        args.putString(ARG_POST_AUTHOR, postAuthor);
        sheet.setArguments(args);
        return sheet;
    }

    private String channelId;
    private String postId;
    private String postText;
    private String postAuthor;

    private RecyclerView rvComments;
    private View emptyCommentsState;
    private ProgressBar pbLoadingComments;
    private EditText etCommentInput;
    private ImageView btnSendComment;
    private CommentsAdapter adapter;
    private final List<ChannelComment> commentList = new ArrayList<>();

    private ListenerRegistration commentsListener;
    private String currentUserId;
    private String currentUserName = "Subscriber";
    private String currentUserAvatar = null;

    public static class ChannelComment {
        public String id;
        public String authorId;
        public String authorName;
        public String authorAvatar;
        public String text;
        public long timestamp;

        public ChannelComment() {}

        public ChannelComment(String id, String authorId, String authorName, String authorAvatar, String text, long timestamp) {
            this.id = id;
            this.authorId = authorId;
            this.authorName = authorName;
            this.authorAvatar = authorAvatar;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            channelId = getArguments().getString(ARG_CHANNEL_ID);
            postId = getArguments().getString(ARG_POST_ID);
            postText = getArguments().getString(ARG_POST_TEXT);
            postAuthor = getArguments().getString(ARG_POST_AUTHOR);
        }

        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        Persona persona = VeilFeatureManager.getInstance(requireContext()).getActivePersona();
        if (persona != null) {
            currentUserName = persona.getDisplayName();
            currentUserAvatar = persona.getAvatar();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_channel_comments, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvComments = view.findViewById(R.id.rvComments);
        emptyCommentsState = view.findViewById(R.id.emptyCommentsState);
        pbLoadingComments = view.findViewById(R.id.pbLoadingComments);
        etCommentInput = view.findViewById(R.id.etCommentInput);
        btnSendComment = view.findViewById(R.id.btnSendComment);

        view.findViewById(R.id.btnCloseComments).setOnClickListener(v -> dismiss());

        TextView tvAuthor = view.findViewById(R.id.tvPostAuthor);
        TextView tvSnippet = view.findViewById(R.id.tvPostSnippet);
        if (tvAuthor != null) tvAuthor.setText(postAuthor != null ? postAuthor : "Channel Post");
        if (tvSnippet != null) tvSnippet.setText(postText != null ? postText : "Broadcast Post");

        rvComments.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new CommentsAdapter(commentList);
        rvComments.setAdapter(adapter);

        btnSendComment.setOnClickListener(v -> sendComment());

        loadComments();
    }

    private void loadComments() {
        if (channelId == null || postId == null) return;

        pbLoadingComments.setVisibility(View.VISIBLE);
        commentsListener = FirebaseFirestore.getInstance()
                .collection("groups").document(channelId)
                .collection("messages").document(postId)
                .collection("comments")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .limit(100)
                .addSnapshotListener((queryDocumentSnapshots, e) -> {
                    if (!isAdded()) return;
                    pbLoadingComments.setVisibility(View.GONE);
                    if (e != null || queryDocumentSnapshots == null) {
                        emptyCommentsState.setVisibility(commentList.isEmpty() ? View.VISIBLE : View.GONE);
                        return;
                    }

                    commentList.clear();
                    for (DocumentSnapshot doc : queryDocumentSnapshots.getDocuments()) {
                        String id = doc.getId();
                        String authorId = doc.getString("authorId");
                        String authorName = doc.getString("authorName");
                        String authorAvatar = doc.getString("authorAvatar");
                        String text = doc.getString("text");
                        long timestamp = VeilFirestoreUtils.getSafeTimestampMillis(doc, "timestamp", System.currentTimeMillis());

                        commentList.add(new ChannelComment(id, authorId, authorName, authorAvatar, text, timestamp));
                    }

                    adapter.notifyDataSetChanged();
                    emptyCommentsState.setVisibility(commentList.isEmpty() ? View.VISIBLE : View.GONE);
                    if (!commentList.isEmpty()) {
                        rvComments.scrollToPosition(commentList.size() - 1);
                    }
                });
    }

    private void sendComment() {
        String text = etCommentInput.getText() != null ? etCommentInput.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;

        etCommentInput.setText("");

        String commentId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Map<String, Object> map = new HashMap<>();
        map.put("id", commentId);
        map.put("authorId", currentUserId);
        map.put("authorName", currentUserName);
        map.put("authorAvatar", currentUserAvatar != null ? currentUserAvatar : "");
        map.put("text", text);
        map.put("timestamp", now);

        // Optimistic UI insert
        commentList.add(new ChannelComment(commentId, currentUserId, currentUserName, currentUserAvatar, text, now));
        adapter.notifyItemInserted(commentList.size() - 1);
        emptyCommentsState.setVisibility(View.GONE);
        rvComments.scrollToPosition(commentList.size() - 1);

        FirebaseFirestore.getInstance()
                .collection("groups").document(channelId)
                .collection("messages").document(postId)
                .collection("comments").document(commentId)
                .set(map)
                .addOnFailureListener(e -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Comment failed to sync", Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (commentsListener != null) {
            commentsListener.remove();
        }
    }

    private static class CommentsAdapter extends RecyclerView.Adapter<CommentsAdapter.VH> {
        private final List<ChannelComment> list;

        CommentsAdapter(List<ChannelComment> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel_comment, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChannelComment c = list.get(position);
            holder.tvAuthor.setText(c.authorName != null ? c.authorName : "Anonymous");
            holder.tvText.setText(c.text != null ? c.text : "");
            CharSequence timeAgo = DateUtils.getRelativeTimeSpanString(c.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            holder.tvTime.setText(timeAgo);

            if (c.authorAvatar != null && !c.authorAvatar.isEmpty()) {
                if (c.authorAvatar.startsWith("content://") || c.authorAvatar.startsWith("file://")) {
                    holder.ivAvatar.setImageURI(android.net.Uri.parse(c.authorAvatar));
                } else {
                    holder.ivAvatar.setImageResource(R.drawable.ic_account);
                }
            } else {
                holder.ivAvatar.setImageResource(R.drawable.ic_account);
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvAuthor, tvTime, tvText;

            VH(@NonNull View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.ivCommentAvatar);
                tvAuthor = v.findViewById(R.id.tvCommentAuthor);
                tvTime = v.findViewById(R.id.tvCommentTime);
                tvText = v.findViewById(R.id.tvCommentText);
            }
        }
    }
}
