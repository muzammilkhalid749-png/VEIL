package com.m3.veil.ui.main;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.m3.veil.model.Timestamp;
import com.m3.veil.bridge.firestore.DocumentSnapshot;
import com.m3.veil.bridge.firestore.FieldValue;
import com.m3.veil.bridge.firestore.FirebaseFirestore;
import com.m3.veil.bridge.firestore.ListenerRegistration;
import com.m3.veil.bridge.firestore.Query;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilBoardController;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Comment;
import com.m3.veil.model.Persona;
import com.m3.veil.model.Post;
import com.m3.veil.ui.main.adapters.CommentsAdapter;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class CommentsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_POST_ID = "arg_post_id";
    private static final String ARG_POST_AUTHOR = "arg_post_author";

    private String postId;
    private String postAuthor;

    private RecyclerView recyclerView;
    private CommentsAdapter adapter;
    private List<Comment> commentList;
    private View emptyState;
    private ProgressBar loadingBar;
    private EditText commentInput;
    private ImageView btnSend;
    private TextView commentsTitle;

    private String currentUserId;
    private String currentUsername = "anonymous";
    private String replyingToCommentId = null;
    private String replyingToAuthor = null;

    public static CommentsBottomSheet newInstance(String postId, String postAuthor) {
        CommentsBottomSheet sheet = new CommentsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_POST_ID, postId);
        args.putString(ARG_POST_AUTHOR, postAuthor);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            postId = getArguments().getString(ARG_POST_ID);
            postAuthor = getArguments().getString(ARG_POST_AUTHOR);
        }
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext()).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_comments_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        commentsTitle = view.findViewById(R.id.commentsTitle);
        recyclerView = view.findViewById(R.id.commentsRecyclerView);
        emptyState = view.findViewById(R.id.emptyCommentsState);
        loadingBar = view.findViewById(R.id.commentsLoadingBar);
        commentInput = view.findViewById(R.id.commentInput);
        btnSend = view.findViewById(R.id.btnSendComment);
        ImageView btnClose = view.findViewById(R.id.btnCloseComments);

        if (postAuthor != null) {
            commentsTitle.setText("Anonymous Replies on @" + postAuthor + "'s Veil");
        }

        Persona activePersona = VeilFeatureManager.getInstance(requireContext()).getActivePersona();
        if (activePersona != null && activePersona.getDisplayName() != null) {
            currentUsername = activePersona.getDisplayName().toLowerCase().replace(" ", "_");
        }

        UiAnimator.attachSpringPress(btnSend);
        UiAnimator.attachSpringPress(btnClose);
        btnClose.setOnClickListener(v -> dismiss());

        commentList = new ArrayList<>();
        adapter = new CommentsAdapter(commentList, postAuthor);
        adapter.setOnCommentReplyListener(parentComment -> {
            if (parentComment == null) return;
            // Enforce max 2-level nesting (#93): if replying to a depth-1 comment, attach to its root parent
            replyingToCommentId = parentComment.getParentCommentId() != null && !parentComment.getParentCommentId().isEmpty()
                    ? parentComment.getParentCommentId()
                    : parentComment.getId();
            replyingToAuthor = parentComment.getAuthorUsername();
            commentInput.setHint("Replying to @" + replyingToAuthor + " (tap title to cancel)...");
            commentInput.requestFocus();
        });
        commentsTitle.setOnClickListener(v -> {
            replyingToCommentId = null;
            replyingToAuthor = null;
            commentInput.setHint("Add an anonymous comment...");
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        btnSend.setOnClickListener(v -> postComment());
        btnSend.setOnLongClickListener(v -> {
            // Quick Voice Confession Comment (#94)
            String[] dspFilters = {"Ghost Pitch (+4st)", "Cyber Synth", "Deep Shadow (-5st)"};
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("🎙️ Post Anonymous Voice Reply (#94)")
                    .setItems(dspFilters, (d, which) -> {
                        String tag = "🎙️ [Voice Reply • " + dspFilters[which] + " • 0:06]";
                        VeilBoardController.getInstance(requireContext()).addComment(postId, tag, replyingToCommentId);
                        replyingToCommentId = null;
                        commentInput.setHint("Add an anonymous comment...");
                        loadLocalComments();
                    })
                    .show();
            return true;
        });

        loadLocalComments();
        listenToRemoteComments();
    }

    private final VeilBoardController.VeilBoardObserver boardObserver = new VeilBoardController.VeilBoardObserver() {
        @Override
        public void onFeedRefreshed(List<Post> feed) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalComments());
        }

        @Override
        public void onPostAdded(Post post) {}

        @Override
        public void onPostUpdated(Post post) {
            if (isAdded() && post != null && postId != null && postId.equals(post.getId())) {
                requireActivity().runOnUiThread(() -> loadLocalComments());
            }
        }

        @Override
        public void onPostDeleted(String deletedPostId) {
            if (isAdded() && postId != null && postId.equals(deletedPostId)) {
                requireActivity().runOnUiThread(() -> dismiss());
            }
        }

        @Override
        public void onCommentAdded(String targetPostId, int totalComments) {
            if (isAdded() && postId != null && postId.equals(targetPostId)) {
                requireActivity().runOnUiThread(() -> loadLocalComments());
            }
        }

        @Override
        public void onPollVoted(String targetPostId, int optionIndex, String pollJson) {}

        @Override
        public void onCoinTipped(String targetPostId, String recipientAuthorId, int amount) {}
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getContext() != null) {
            VeilBoardController.getInstance(requireContext()).removeObserver(boardObserver);
        }
    }

    private void listenToRemoteComments() {
        if (postId == null || getContext() == null) return;
        loadingBar.setVisibility(View.GONE);
        VeilBoardController.getInstance(requireContext()).addObserver(boardObserver);
    }

    private Post findTargetPost() {
        if (postId == null || getContext() == null) return null;
        return VeilBoardController.getInstance(requireContext()).getPost(postId);
    }

    private void loadLocalComments() {
        if (getContext() == null) return;
        loadingBar.setVisibility(View.GONE);
        commentList.clear();

        List<JSONObject> rawComments = VeilBoardController.getInstance(requireContext()).getCommentsForPost(postId);
        List<Comment> rootComments = new ArrayList<>();
        List<Comment> childReplies = new ArrayList<>();
        for (JSONObject obj : rawComments) {
            if (obj == null) continue;
            String cId = obj.optString("id", UUID.randomUUID().toString());
            String aId = obj.optString("authorId", "anon");
            String aName = obj.optString("authorAlias", obj.optString("authorUsername", "Anon"));
            String text = obj.optString("text", obj.optString("content", ""));
            long ts = obj.optLong("timestamp", System.currentTimeMillis());
            String parentId = obj.optString("parentId", "");

            boolean isChild = parentId != null && !parentId.isEmpty();
            Comment c = new Comment(cId, postId, aId, aName, text, new Timestamp(new Date(ts)),
                    isChild ? parentId : null, isChild ? 1 : 0);
            if (isChild) {
                childReplies.add(c);
            } else {
                rootComments.add(c);
            }
        }

        // Flatten into 2-level nested hierarchy (#93): each root comment followed immediately by its replies
        for (Comment root : rootComments) {
            commentList.add(root);
            for (Comment reply : childReplies) {
                if (root.getId() != null && root.getId().equals(reply.getParentCommentId())) {
                    commentList.add(reply);
                }
            }
        }
        // Add any orphan replies at the end
        for (Comment reply : childReplies) {
            if (!commentList.contains(reply)) {
                commentList.add(reply);
            }
        }

        adapter.notifyDataSetChanged();
        if (commentList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.smoothScrollToPosition(commentList.size() - 1);
        }
    }

    private void postComment() {
        String text = commentInput.getText().toString().trim();
        if (TextUtils.isEmpty(text) || getContext() == null || postId == null) return;

        commentInput.setText("");
        String targetParent = replyingToCommentId;
        replyingToCommentId = null;
        replyingToAuthor = null;
        commentInput.setHint("Add an anonymous comment...");
        VeilBoardController.getInstance(requireContext()).addComment(postId, text, targetParent);
        loadLocalComments();
    }
}
