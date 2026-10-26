package com.m3.veil.ui.main.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.m3.veil.model.Timestamp;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilBoardController;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Post;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.chat.sheets.NearbyRadarBottomSheet;
import com.m3.veil.ui.main.CommentsBottomSheet;
import com.m3.veil.ui.main.CreatePostActivity;
import com.m3.veil.ui.main.adapters.PostsAdapter;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DiscoverFragment extends Fragment implements PostsAdapter.OnPostInteractionListener {

    private RecyclerView postsRecyclerView;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshLayout;
    private PostsAdapter postsAdapter;
    private List<Post> postList;
    private FloatingActionButton fabCreatePost;
    private View emptyState;
    private ProgressBar loadingBar;
    private String activeCategoryFilter = "all";
    private String activeSearchQuery = "";

    private final VeilBoardController.VeilBoardObserver boardObserver = new VeilBoardController.VeilBoardObserver() {
        @Override
        public void onFeedRefreshed(List<Post> feed) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }

        @Override
        public void onPostAdded(Post post) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }

        @Override
        public void onPostUpdated(Post post) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }

        @Override
        public void onPostDeleted(String postId) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }

        @Override
        public void onCommentAdded(String targetPostId, int totalComments) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }

        @Override
        public void onPollVoted(String targetPostId, int optionIndex, String pollJson) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }

        @Override
        public void onCoinTipped(String targetPostId, String recipientAuthorId, int amount) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }
    };

    private final EncryptedChatDatabase.DatabaseChangeListener dbListener = new EncryptedChatDatabase.DatabaseChangeListener() {
        @Override
        public void onMessagesUpdated(String chatId) {}

        @Override
        public void onChatsUpdated() {}

        @Override
        public void onPostsUpdated() {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalPosts());
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_discover, container, false);

        initializeViews(view);
        setupRecyclerView();
        loadLocalPosts();

        VeilBoardController.getInstance(requireContext()).addObserver(boardObserver);
        EncryptedChatDatabase.getInstance(requireContext()).addListener(dbListener);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLocalPosts();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getContext() != null) {
            VeilBoardController.getInstance(requireContext()).removeObserver(boardObserver);
            EncryptedChatDatabase.getInstance(requireContext()).removeListener(dbListener);
        }
    }

    private void initializeViews(View view) {
        postsRecyclerView = view.findViewById(R.id.postsRecyclerView);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        fabCreatePost = view.findViewById(R.id.fabCreatePost);
        emptyState = view.findViewById(R.id.emptyState);
        loadingBar = view.findViewById(R.id.loadingBar);

        com.google.android.material.chip.ChipGroup categoryChipGroup = view.findViewById(R.id.categoryChipGroup);
        if (categoryChipGroup != null) {
            categoryChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                if (id == R.id.chipConfessions) {
                    activeCategoryFilter = "confession";
                } else if (id == R.id.chipPolls) {
                    activeCategoryFilter = "poll";
                } else if (id == R.id.chipMoments) {
                    activeCategoryFilter = "moment";
                } else if (id == R.id.chipTrending) {
                    activeCategoryFilter = "trending";
                } else if (id == R.id.chipPublicDirectory) {
                    com.m3.veil.ui.chat.sheets.PublicDirectoryBottomSheet sheet = new com.m3.veil.ui.chat.sheets.PublicDirectoryBottomSheet();
                    sheet.show(getChildFragmentManager(), "PublicDirectoryBottomSheet");
                    group.check(R.id.chipAll);
                    return;
                } else if (id == R.id.chipBlindRoulette) {
                    com.m3.veil.ui.chat.sheets.BlindRouletteBottomSheet sheet = new com.m3.veil.ui.chat.sheets.BlindRouletteBottomSheet();
                    sheet.show(getChildFragmentManager(), "BlindRouletteBottomSheet");
                    group.check(R.id.chipAll);
                    return;
                } else if (id == R.id.chipNearby) {
                    NearbyRadarBottomSheet sheet = NearbyRadarBottomSheet.newInstance();
                    sheet.show(getChildFragmentManager(), "NearbyRadarBottomSheet");
                    group.check(R.id.chipAll);
                    return;
                } else if (id == R.id.chipLeaderboard) {
                    com.m3.veil.ui.chat.sheets.KarmaLeaderboardBottomSheet sheet = new com.m3.veil.ui.chat.sheets.KarmaLeaderboardBottomSheet();
                    sheet.show(getChildFragmentManager(), "KarmaLeaderboardBottomSheet");
                    group.check(R.id.chipAll);
                    return;
                } else if (id == R.id.chipStickers) {
                    com.m3.veil.ui.chat.sheets.StickerMarketplaceBottomSheet sheet = new com.m3.veil.ui.chat.sheets.StickerMarketplaceBottomSheet();
                    sheet.show(getChildFragmentManager(), "StickerMarketplaceBottomSheet");
                    group.check(R.id.chipAll);
                    return;
                } else if (id == R.id.chipJury) {
                    ModerationTransparencyBottomSheet sheet = new ModerationTransparencyBottomSheet();
                    sheet.show(getChildFragmentManager(), "ModerationTransparencyBottomSheet");
                    group.check(R.id.chipAll);
                    return;
                } else {
                    activeCategoryFilter = "all";
                }
                loadLocalPosts();
            });
        }

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setColorSchemeColors(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            swipeRefreshLayout.setOnRefreshListener(this::loadLocalPosts);
        }

        fabCreatePost.setVisibility(View.GONE);
    }

    public void setCategoryFilter(String category) {
        this.activeCategoryFilter = category != null ? category : "all";
        if (getView() != null) {
            com.google.android.material.chip.ChipGroup group = getView().findViewById(R.id.categoryChipGroup);
            if (group != null) {
                if ("confession".equalsIgnoreCase(category)) group.check(R.id.chipConfessions);
                else if ("poll".equalsIgnoreCase(category)) group.check(R.id.chipPolls);
                else if ("moment".equalsIgnoreCase(category)) group.check(R.id.chipMoments);
                else if ("trending".equalsIgnoreCase(category)) group.check(R.id.chipTrending);
                else group.check(R.id.chipAll);
            }
        }
        loadLocalPosts();
    }

    public void refreshFeed() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }
        loadLocalPosts();
    }

    public void showSearchVeilBoardDialog() {
        if (getContext() == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        android.widget.TextView tvTitle = new android.widget.TextView(requireContext());
        tvTitle.setText("🔍 Search VeilBoard & #Hashtags");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        root.addView(tvTitle);

        android.widget.EditText etQuery = new android.widget.EditText(requireContext());
        etQuery.setHint("Search keyword, #hashtag, or @alias...");
        etQuery.setText(activeSearchQuery);
        etQuery.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        etQuery.setHintTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
        etQuery.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        root.addView(etQuery);

        // Quick Trending Hashtags Row
        LinearLayout tagsRow = new LinearLayout(requireContext());
        tagsRow.setOrientation(LinearLayout.HORIZONTAL);
        tagsRow.setPadding(0, 0, 0, (int) (14 * dp));
        String[] quickTags = {"#confession", "#privacy", "#mesh", "#poll", "Clear Filter"};
        for (String tag : quickTags) {
            android.widget.TextView chip = new android.widget.TextView(requireContext());
            chip.setText(tag);
            chip.setTextSize(12f);
            chip.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            chip.setBackgroundResource(R.drawable.bg_filter_chip);
            chip.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if ("Clear Filter".equals(tag)) {
                    activeSearchQuery = "";
                    sheet.dismiss();
                    loadLocalPosts();
                } else {
                    etQuery.setText(tag);
                }
            });
            tagsRow.addView(chip);
        }
        root.addView(tagsRow);

        com.google.android.material.button.MaterialButton btnApply =
                new com.google.android.material.button.MaterialButton(requireContext());
        btnApply.setText("Search VeilBoard");
        btnApply.setCornerRadius((int) (12 * dp));
        btnApply.setOnClickListener(v -> {
            activeSearchQuery = etQuery.getText().toString().trim();
            sheet.dismiss();
            loadLocalPosts();
            if (!activeSearchQuery.isEmpty()) {
                Toast.makeText(getContext(), "Filtering VeilBoard by: " + activeSearchQuery, Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnApply);

        sheet.setContentView(root);
        sheet.show();
    }

    private void loadLocalPosts() {
        if (getContext() == null) return;
        loadingBar.setVisibility(View.GONE);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);

        List<Post> local = VeilBoardController.getInstance(requireContext()).getFeed(activeCategoryFilter);
        postList.clear();
        if (local != null) {
            if (activeSearchQuery == null || activeSearchQuery.trim().isEmpty()) {
                postList.addAll(local);
            } else {
                String q = activeSearchQuery.trim().toLowerCase();
                for (Post p : local) {
                    String body = p.getContent() != null ? p.getContent().toLowerCase() : "";
                    String author = p.getAuthorUsername() != null ? p.getAuthorUsername().toLowerCase() : "";
                    String cat = p.getCategory() != null ? p.getCategory().toLowerCase() : "";
                    if (body.contains(q) || author.contains(q) || cat.contains(q)) {
                        postList.add(p);
                    }
                }
            }
        }
        postsAdapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void setupRecyclerView() {
        postList = new ArrayList<>();
        postsAdapter = new PostsAdapter(postList, this);
        postsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        postsRecyclerView.setAdapter(postsAdapter);

        postsRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (Math.abs(dy) > 4 && getActivity() instanceof com.m3.veil.ui.main.MainActivity) {
                    ((com.m3.veil.ui.main.MainActivity) getActivity()).setBottomNavAndFabVisible(dy <= 0);
                }
            }
        });
    }

    private void updateEmptyState() {
        if (postList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
        } else {
            emptyState.setVisibility(View.GONE);
        }
    }

    @Override
    public void onLikeClick(Post post) {
        if (post == null || post.getId() == null || getContext() == null) return;
        VeilBoardController.getInstance(requireContext()).toggleLike(post.getId());
        loadLocalPosts();
    }

    @Override
    public void onCommentClick(Post post) {
        if (post == null || post.getId() == null) return;
        if (post.getPollJson() != null && !post.getPollJson().isEmpty()) {
            showPostPollVoteOrCommentChoice(post);
        } else {
            CommentsBottomSheet bottomSheet = CommentsBottomSheet.newInstance(post.getId(), post.getAuthorUsername());
            bottomSheet.show(getChildFragmentManager(), "CommentsBottomSheet");
        }
    }

    private void showPostPollVoteOrCommentChoice(Post post) {
        if (getContext() == null || post == null || post.getPollJson() == null) return;
        try {
            JSONObject poll = new JSONObject(post.getPollJson());
            JSONArray opts = poll.optJSONArray("options");
            JSONArray votes = poll.optJSONArray("votes");
            if (opts == null || opts.length() == 0) {
                CommentsBottomSheet bottomSheet = CommentsBottomSheet.newInstance(post.getId(), post.getAuthorUsername());
                bottomSheet.show(getChildFragmentManager(), "CommentsBottomSheet");
                return;
            }

            com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                    new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
            View view = getLayoutInflater().inflate(R.layout.dialog_poll_vote_sheet, null);
            dialog.setContentView(view);

            ViewGroup container = view.findViewById(R.id.pollOptionsContainer);
            View btnOpenComments = view.findViewById(R.id.btnOpenComments);

            int totalVotes = 0;
            if (votes != null) {
                for (int i = 0; i < votes.length(); i++) totalVotes += votes.optInt(i, 0);
            }

            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < opts.length(); i++) {
                final int optIndex = i;
                int v = (votes != null && i < votes.length()) ? votes.optInt(i, 0) : 0;
                int pct = totalVotes > 0 ? (int) Math.round((v * 100.0) / totalVotes) : 0;

                com.google.android.material.card.MaterialCardView card =
                        new com.google.android.material.card.MaterialCardView(requireContext());
                card.setCardElevation(0);
                card.setStrokeWidth((int) (1.2f * density));
                card.setStrokeColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorOutline));
                card.setRadius(12 * density);
                card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorSurface));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int) (10 * density);
                card.setLayoutParams(lp);

                LinearLayout optLayout = new LinearLayout(requireContext());
                optLayout.setOrientation(LinearLayout.VERTICAL);
                optLayout.setPadding((int) (16 * density), (int) (12 * density), (int) (16 * density), (int) (12 * density));

                LinearLayout textRow = new LinearLayout(requireContext());
                textRow.setOrientation(LinearLayout.HORIZONTAL);

                android.widget.TextView tvOption = new android.widget.TextView(requireContext());
                tvOption.setText((i + 1) + ". " + opts.optString(i));
                tvOption.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
                tvOption.setTextSize(14);
                tvOption.setTypeface(null, android.graphics.Typeface.BOLD);
                tvOption.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                android.widget.TextView tvCount = new android.widget.TextView(requireContext());
                tvCount.setText(v + " votes (" + pct + "%)");
                tvCount.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
                tvCount.setTextSize(12);

                textRow.addView(tvOption);
                textRow.addView(tvCount);
                optLayout.addView(textRow);

                ProgressBar pb = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(pct);
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary)));
                LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (6 * density));
                pbLp.topMargin = (int) (8 * density);
                pb.setLayoutParams(pbLp);
                optLayout.addView(pb);

                card.addView(optLayout);

                card.setOnClickListener(vCard -> {
                    try {
                        VeilBoardController.getInstance(requireContext()).voteInPoll(post.getId(), optIndex);
                        Toast.makeText(getContext(), "Anonymous vote cast for option " + (optIndex + 1), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadLocalPosts();
                    } catch (Exception ignored) { }
                });

                container.addView(card);
            }

            btnOpenComments.setOnClickListener(v -> {
                dialog.dismiss();
                CommentsBottomSheet bottomSheet = CommentsBottomSheet.newInstance(post.getId(), post.getAuthorUsername());
                bottomSheet.show(getChildFragmentManager(), "CommentsBottomSheet");
            });

            dialog.show();

        } catch (Exception e) {
            CommentsBottomSheet bottomSheet = CommentsBottomSheet.newInstance(post.getId(), post.getAuthorUsername());
            bottomSheet.show(getChildFragmentManager(), "CommentsBottomSheet");
        }
    }

    @Override
    public void onShareClick(Post post) {
        if (post == null || getContext() == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padH, padV, padH, padV * 2);

        android.widget.TextView tvTitle = new android.widget.TextView(requireContext());
        tvTitle.setText("🔄 Repost / Share Veil (#92)");
        tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvTitle);

        android.widget.TextView tvSub = new android.widget.TextView(requireContext());
        tvSub.setText("Share @" + post.getAuthorUsername() + "'s post to your profile or the mesh");
        tvSub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, 4, 0, (int) (16 * getResources().getDisplayMetrics().density));
        layout.addView(tvSub);

        // Option 1: Quote-Share with thoughts to VeilBoard
        LinearLayout optQuote = new LinearLayout(requireContext());
        optQuote.setOrientation(LinearLayout.HORIZONTAL);
        optQuote.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
        android.widget.TextView tvQuote = new android.widget.TextView(requireContext());
        tvQuote.setText("💬 Quote-Share with thoughts to VeilBoard");
        tvQuote.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        tvQuote.setTextSize(15.5f);
        optQuote.addView(tvQuote);
        optQuote.setOnClickListener(v -> {
            sheet.dismiss();
            promptQuoteShare(post);
        });
        layout.addView(optQuote);

        // Option 2: Instant Repost to Profile Feed
        LinearLayout optRepost = new LinearLayout(requireContext());
        optRepost.setOrientation(LinearLayout.HORIZONTAL);
        optRepost.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
        android.widget.TextView tvRepost = new android.widget.TextView(requireContext());
        tvRepost.setText("🔄 Instant Repost to Profile");
        tvRepost.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        tvRepost.setTextSize(15.5f);
        optRepost.addView(tvRepost);
        optRepost.setOnClickListener(v -> {
            sheet.dismiss();
            VeilBoardController.getInstance(requireContext()).repostPost(post.getId(), null, true, false);
            Toast.makeText(requireContext(), "Post reposted to your profile!", Toast.LENGTH_SHORT).show();
        });
        layout.addView(optRepost);

        // Option 3: External Share
        LinearLayout optExternal = new LinearLayout(requireContext());
        optExternal.setOrientation(LinearLayout.HORIZONTAL);
        optExternal.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
        android.widget.TextView tvExternal = new android.widget.TextView(requireContext());
        tvExternal.setText("🔗 Share Outside VEIL (Android Share)");
        tvExternal.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        tvExternal.setTextSize(15.5f);
        optExternal.addView(tvExternal);
        optExternal.setOnClickListener(v -> {
            sheet.dismiss();
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "@" + post.getAuthorUsername() + " on VeilBoard:\n" + post.getContent());
            startActivity(Intent.createChooser(shareIntent, "Share Veil"));
        });
        layout.addView(optExternal);

        sheet.setContentView(layout);
        sheet.show();
    }

    private void promptQuoteShare(Post post) {
        if (getContext() == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad * 2);

        android.widget.TextView tvTitle = new android.widget.TextView(requireContext());
        tvTitle.setText("💬 Add your thoughts");
        tvTitle.setTextColor(android.graphics.Color.WHITE);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvTitle);

        android.widget.EditText etComment = new android.widget.EditText(requireContext());
        etComment.setHint("What do you think about this post?");
        etComment.setTextColor(android.graphics.Color.WHITE);
        etComment.setHintTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
        layout.addView(etComment);

        com.google.android.material.button.MaterialButton btnSend = new com.google.android.material.button.MaterialButton(requireContext());
        btnSend.setText("Post Quote to VeilBoard");
        btnSend.setCornerRadius((int) (20 * getResources().getDisplayMetrics().density));
        btnSend.setOnClickListener(v -> {
            String comment = etComment.getText().toString().trim();
            dialog.dismiss();
            VeilBoardController.getInstance(requireContext()).repostPost(post.getId(), comment, true, true);
            Toast.makeText(requireContext(), "Quote-share published to VeilBoard!", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnSend);
        dialog.setContentView(layout);
        dialog.show();
    }

    @Override
    public void onAuthorClick(Post post) {
        if (post == null || getContext() == null) return;
        String currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUid == null) currentUid = "";

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_user_mini_profile, null);
        dialog.setContentView(view);

        android.widget.TextView tvName = view.findViewById(R.id.profileDisplayName);
        android.widget.TextView tvUsername = view.findViewById(R.id.profileUsername);
        View btnMessage = view.findViewById(R.id.btnSendMessage);
        View btnCopy = view.findViewById(R.id.btnCopyUsername);

        String username = post.getAuthorUsername() != null ? post.getAuthorUsername() : "Anonymous";
        tvName.setText(username + (post.getMoodBadge() != null ? "  " + post.getMoodBadge() : ""));
        tvUsername.setText("@" + username);

        if (post.getAuthorId() != null && post.getAuthorId().equals(currentUid)) {
            btnMessage.setVisibility(View.GONE);
        } else {
            btnMessage.setVisibility(View.VISIBLE);
            btnMessage.setOnClickListener(v -> {
                dialog.dismiss();
                startDirectChatWithAuthor(post.getAuthorId(), username);
            });
        }

        btnCopy.setOnClickListener(v -> {
            dialog.dismiss();
            // Tip author with VeilCoins anonymously via VeilBoardController (Pure P2P) or copy username
            boolean success = VeilBoardController.getInstance(requireContext()).tipAuthor(post.getId(), 5);
            if (success) {
                Toast.makeText(getContext(), "🪙 Tipped 5 VeilCoins anonymously to @" + username + "!", Toast.LENGTH_SHORT).show();
            } else {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Username", "@" + username));
                    Toast.makeText(getContext(), "Username copied", Toast.LENGTH_SHORT).show();
                }
            }
        });

        dialog.show();
    }

    private void startDirectChatWithAuthor(String authorId, String authorUsername) {
        if (authorId == null || authorId.isEmpty() || getContext() == null) return;
        String currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUid == null || currentUid.isEmpty()) currentUid = "local";

        List<String> ids = Arrays.asList(currentUid, authorId);
        Collections.sort(ids);
        String chatId = ids.get(0) + "_" + ids.get(1);

        Chat existing = EncryptedChatDatabase.getInstance(requireContext()).getChat(chatId);
        if (existing == null) {
            Chat newChat = new Chat(
                    chatId,
                    authorUsername,
                    "Started from VeilBoard post",
                    Timestamp.now(),
                    null,
                    0,
                    false,
                    false,
                    "direct",
                    Arrays.asList(currentUid, authorId)
            );
            EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(newChat);
        }

        Intent intent = new Intent(getContext(), ChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("recipientId", authorId);
        intent.putExtra("recipientName", authorUsername);
        startActivity(intent);
    }
}
