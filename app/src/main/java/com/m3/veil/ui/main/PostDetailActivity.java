package com.m3.veil.ui.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.manager.VeilBoardController;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Persona;
import com.m3.veil.model.Post;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.chat.MediaViewerActivity;
import com.m3.veil.ui.views.VeilCanvasSpoilerAndMosaicView;
import com.m3.veil.ui.views.WaveformView;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Reddit-Grade Post Detail & Multi-Level Threaded Discussion Activity for VeilBoard.
 * Supports:
 * - Full multi-modal post rendering (Spoilers, Mosaic, Masked Voice DSP, Polls/Quizzes, Collab Chains)
 * - Upvote / Downvote karma system + Anonymous Awards (Gold, Cyber Shield, Mindblown, Wholesome, Fire)
 * - Comment Sorting: 🔥 Best, ⚡ New, 🏆 Top, ⚔️ Controversial
 * - Multi-level collapsible comment trees with vertical thread depth guide lines & [OP] badges
 * - Real-time Firestore + P2P Mesh comment synchronization
 */
public class PostDetailActivity extends AppCompatActivity {

    public static final String EXTRA_POST_ID = "extra_post_id";

    private String postId;
    private Post currentPost;
    private String currentUserId;
    private String currentAlias;

    private TextView tvSubveilHeader;
    private TextView tvThreadSubHeader;
    private CircleImageView ivPostDetailAvatar;
    private TextView tvPostDetailAuthor;
    private TextView tvPostDetailMeta;
    private TextView tvPostDetailContent;
    private LinearLayout layoutPostDetailAttachments;
    private LinearLayout layoutAwardsStrip;
    private TextView btnUpvotePost;
    private TextView btnDownvotePost;
    private TextView tvVoteScore;
    private TextView tvCommentsPillText;
    private LinearLayout layoutThreadedCommentsContainer;
    private TextView tvEmptyThreadState;
    private LinearLayout layoutReplyingBanner;
    private TextView tvReplyingToLabel;
    private EditText etThreadCommentInput;

    private TextView chipSortBest;
    private TextView chipSortNew;
    private TextView chipSortTop;
    private TextView chipSortControversial;

    private String activeSortMode = "best"; // best, new, top, controversial
    private String replyingToCommentId = null;
    private String replyingToAuthor = null;

    private final Set<String> collapsedCommentIds = new HashSet<>();
    private final Set<String> revealedSpoilers = new HashSet<>();
    private final Map<String, Integer> localCommentVotes = new HashMap<>(); // commentId -> +1 / -1
    private final Map<String, String> commentAwards = new HashMap<>();
    private final List<String> postAwards = new ArrayList<>();
    private int localPostVoteState = 0; // +1 upvoted, -1 downvoted, 0 neutral

    private ListenerRegistration firestorePostCommentsListener;

    private static final int[] THREAD_GUIDE_COLORS = {
            0xFF38BDF8, // Cyan
            0xFFA855F7, // Purple
            0xFF10B981, // Emerald
            0xFFF59E0B, // Amber
            0xFFEC4899  // Pink
    };

    private static class ThreadCommentNode {
        String id;
        String postId;
        String authorId;
        String authorAlias;
        String text;
        long timestamp;
        String parentId;
        int score;
        int depth;
        String awardBadge;
        final List<ThreadCommentNode> children = new ArrayList<>();
    }

    private final VeilBoardController.VeilBoardObserver boardObserver = new VeilBoardController.VeilBoardObserver() {
        @Override
        public void onFeedRefreshed(List<Post> feed) {
            runOnUiThread(() -> reloadPostAndComments());
        }

        @Override
        public void onPostAdded(Post post) {}

        @Override
        public void onPostUpdated(Post post) {
            if (post != null && postId != null && postId.equals(post.getId())) {
                runOnUiThread(() -> reloadPostAndComments());
            }
        }

        @Override
        public void onPostDeleted(String deletedPostId) {
            if (postId != null && postId.equals(deletedPostId)) {
                runOnUiThread(() -> {
                    Toast.makeText(PostDetailActivity.this, "Post was removed", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }

        @Override
        public void onCommentAdded(String targetPostId, int totalComments) {
            if (postId != null && postId.equals(targetPostId)) {
                runOnUiThread(() -> reloadPostAndComments());
            }
        }

        @Override
        public void onPollVoted(String targetPostId, int optionIndex, String pollJson) {
            if (postId != null && postId.equals(targetPostId)) {
                runOnUiThread(() -> reloadPostAndComments());
            }
        }

        @Override
        public void onCoinTipped(String targetPostId, String recipientAuthorId, int amount) {
            if (postId != null && postId.equals(targetPostId)) {
                runOnUiThread(() -> reloadPostAndComments());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_post_detail);

        View root = findViewById(R.id.rootPostDetail);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        postId = getIntent().getStringExtra(EXTRA_POST_ID);
        if (postId == null || postId.isEmpty()) {
            finish();
            return;
        }

        currentUserId = AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        Persona activePersona = VeilFeatureManager.getInstance(this).getActivePersona();
        currentAlias = (activePersona != null && activePersona.getDisplayName() != null)
                ? activePersona.getDisplayName().toLowerCase(Locale.ROOT).replace(" ", "_")
                : "anonymous";

        bindViews();
        loadSavedVoteAndAwardState();
        reloadPostAndComments();
        subscribeRealtimeFirestoreComments();

        VeilBoardController.getInstance(this).addObserver(boardObserver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        VeilBoardController.getInstance(this).removeObserver(boardObserver);
        if (firestorePostCommentsListener != null) {
            firestorePostCommentsListener.remove();
            firestorePostCommentsListener = null;
        }
    }

    private void bindViews() {
        tvSubveilHeader = findViewById(R.id.tvSubveilHeader);
        tvThreadSubHeader = findViewById(R.id.tvThreadSubHeader);
        ivPostDetailAvatar = findViewById(R.id.ivPostDetailAvatar);
        tvPostDetailAuthor = findViewById(R.id.tvPostDetailAuthor);
        tvPostDetailMeta = findViewById(R.id.tvPostDetailMeta);
        tvPostDetailContent = findViewById(R.id.tvPostDetailContent);
        layoutPostDetailAttachments = findViewById(R.id.layoutPostDetailAttachments);
        layoutAwardsStrip = findViewById(R.id.layoutAwardsStrip);
        btnUpvotePost = findViewById(R.id.btnUpvotePost);
        btnDownvotePost = findViewById(R.id.btnDownvotePost);
        tvVoteScore = findViewById(R.id.tvVoteScore);
        tvCommentsPillText = findViewById(R.id.tvCommentsPillText);
        layoutThreadedCommentsContainer = findViewById(R.id.layoutThreadedCommentsContainer);
        tvEmptyThreadState = findViewById(R.id.tvEmptyThreadState);
        layoutReplyingBanner = findViewById(R.id.layoutReplyingBanner);
        tvReplyingToLabel = findViewById(R.id.tvReplyingToLabel);
        etThreadCommentInput = findViewById(R.id.etThreadCommentInput);

        chipSortBest = findViewById(R.id.chipSortBest);
        chipSortNew = findViewById(R.id.chipSortNew);
        chipSortTop = findViewById(R.id.chipSortTop);
        chipSortControversial = findViewById(R.id.chipSortControversial);

        findViewById(R.id.btnBackPostDetail).setOnClickListener(v -> finish());
        findViewById(R.id.btnBookmarkPost).setOnClickListener(v -> bookmarkCurrentPost());
        findViewById(R.id.btnMorePostDetail).setOnClickListener(v -> showPostMoreActionsSheet());
        findViewById(R.id.btnDmAuthor).setOnClickListener(v -> openDirectChatWithOp());
        findViewById(R.id.btnAwardPost).setOnClickListener(v -> showGiveAwardSheet(null));
        findViewById(R.id.btnSharePostDetail).setOnClickListener(v -> shareCurrentPost());

        btnUpvotePost.setOnClickListener(v -> handlePostVote(1));
        btnDownvotePost.setOnClickListener(v -> handlePostVote(-1));

        findViewById(R.id.btnCancelReplyTarget).setOnClickListener(v -> clearReplyTarget());
        findViewById(R.id.btnSendThreadComment).setOnClickListener(v -> submitComment());
        findViewById(R.id.btnVoiceComment).setOnClickListener(v -> promptVoiceComment());

        View.OnClickListener sortClick = v -> {
            if (v == chipSortBest) activeSortMode = "best";
            else if (v == chipSortNew) activeSortMode = "new";
            else if (v == chipSortTop) activeSortMode = "top";
            else if (v == chipSortControversial) activeSortMode = "controversial";
            updateSortChipsUI();
            renderThreadedComments();
        };
        chipSortBest.setOnClickListener(sortClick);
        chipSortNew.setOnClickListener(sortClick);
        chipSortTop.setOnClickListener(sortClick);
        chipSortControversial.setOnClickListener(sortClick);
    }

    private void updateSortChipsUI() {
        TextView[] chips = {chipSortBest, chipSortNew, chipSortTop, chipSortControversial};
        String[] modes = {"best", "new", "top", "controversial"};
        for (int i = 0; i < chips.length; i++) {
            boolean selected = modes[i].equals(activeSortMode);
            chips[i].setBackgroundResource(selected ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chips[i].setTextColor(selected ? Color.WHITE : ContextCompat.getColor(this, R.color.colorTextPrimary));
        }
    }

    private void loadSavedVoteAndAwardState() {
        SharedPreferences prefs = getSharedPreferences("VeilRedditThreadPrefs", MODE_PRIVATE);
        localPostVoteState = prefs.getInt("post_vote_" + postId, 0);
        String savedAwards = prefs.getString("post_awards_" + postId, "");
        postAwards.clear();
        if (savedAwards != null && !savedAwards.isEmpty()) {
            postAwards.addAll(Arrays.asList(savedAwards.split(",")));
        }
    }

    private void saveVoteAndAwardState() {
        SharedPreferences prefs = getSharedPreferences("VeilRedditThreadPrefs", MODE_PRIVATE);
        prefs.edit()
                .putInt("post_vote_" + postId, localPostVoteState)
                .putString("post_awards_" + postId, TextUtils.join(",", postAwards))
                .apply();
    }

    private void reloadPostAndComments() {
        currentPost = VeilBoardController.getInstance(this).getPost(postId);
        if (currentPost == null) {
            List<Post> all = EncryptedChatDatabase.getInstance(this).getAllPosts();
            for (Post p : all) {
                if (postId.equals(p.getId())) {
                    currentPost = p;
                    break;
                }
            }
        }
        if (currentPost == null) {
            Toast.makeText(this, "Post not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        renderPostHero(currentPost);
        renderThreadedComments();
    }

    private void renderPostHero(Post post) {
        float dp = getResources().getDisplayMetrics().density;
        String category = post.getCategory() != null && !post.getCategory().isEmpty()
                ? post.getCategory().toLowerCase(Locale.ROOT) : "veilboard";
        tvSubveilHeader.setText("v/" + category);

        String authorName = post.getAuthorUsername() != null ? post.getAuthorUsername() : "anonymous";
        String badge = post.getMoodBadge() != null && !post.getMoodBadge().isEmpty() ? "  " + post.getMoodBadge() : "";
        tvPostDetailAuthor.setText("@" + authorName + badge);

        // Load author avatar
        String authorId = post.getAuthorId();
        File localAvatar = authorId != null ? new File(getFilesDir(), "avatars/avatar_" + authorId + ".webp") : null;
        if (localAvatar != null && localAvatar.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(localAvatar.getAbsolutePath());
            if (bmp != null) ivPostDetailAvatar.setImageBitmap(bmp);
        } else if (post.getAuthorAvatar() != null && new File(post.getAuthorAvatar()).exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(post.getAuthorAvatar());
            if (bmp != null) ivPostDetailAvatar.setImageBitmap(bmp);
        } else {
            ivPostDetailAvatar.setImageResource(R.drawable.ic_profile_placeholder);
        }

        if (post.getTimestamp() != null) {
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    post.getTimestamp().toDate().getTime(),
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            tvPostDetailMeta.setText(rel + " • " + category.toUpperCase(Locale.ROOT));
        }

        JSONObject meta = null;
        int gradientIndex = 0;
        int fontStyle = 0;
        boolean isCollabChain = false;
        JSONArray chainNodes = null;
        String voiceFilter = "Ghost";

        if (post.getPollJson() != null && !post.getPollJson().isEmpty()) {
            try {
                meta = new JSONObject(post.getPollJson());
                gradientIndex = meta.optInt("gradientIndex", 0);
                fontStyle = meta.optInt("fontStyle", 0);
                isCollabChain = meta.optBoolean("collabChain", false);
                chainNodes = meta.optJSONArray("chainNodes");
                voiceFilter = meta.optString("voiceFilter", "Ghost");
            } catch (Exception ignored) {}
        }

        if (gradientIndex > 0 && gradientIndex < CreatePostActivity.GRADIENT_PALETTES.length) {
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    CreatePostActivity.GRADIENT_PALETTES[gradientIndex]
            );
            gd.setCornerRadius(14 * dp);
            tvPostDetailContent.setBackground(gd);
            int pad = (int) (14 * dp);
            tvPostDetailContent.setPadding(pad, pad, pad, pad);
        } else {
            tvPostDetailContent.setBackground(null);
            tvPostDetailContent.setPadding(0, 0, 0, 0);
        }

        switch (fontStyle) {
            case 1:
                tvPostDetailContent.setTypeface(Typeface.SERIF, Typeface.NORMAL);
                break;
            case 2:
                tvPostDetailContent.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                break;
            case 3:
                tvPostDetailContent.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
                break;
            case 4:
                tvPostDetailContent.setTypeface(Typeface.SERIF, Typeface.ITALIC);
                break;
            default:
                tvPostDetailContent.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
                break;
        }

        String rawContent = post.getContent() != null ? post.getContent() : "";
        tvPostDetailContent.setText(buildFormattedSpannable(rawContent));
        tvPostDetailContent.setMovementMethod(LinkMovementMethod.getInstance());

        layoutPostDetailAttachments.removeAllViews();
        renderPostAttachments(post, meta, voiceFilter, isCollabChain, chainNodes);

        // Render Awards Strip
        if (postAwards.isEmpty()) {
            layoutAwardsStrip.setVisibility(View.GONE);
        } else {
            layoutAwardsStrip.setVisibility(View.VISIBLE);
            layoutAwardsStrip.removeAllViews();
            for (String award : postAwards) {
                TextView badgeTv = new TextView(this);
                badgeTv.setText(award);
                badgeTv.setTextSize(11.5f);
                badgeTv.setTextColor(0xFFFFD700);
                badgeTv.setBackgroundResource(R.drawable.bg_filter_chip);
                badgeTv.setPadding((int) (8 * dp), (int) (3 * dp), (int) (8 * dp), (int) (3 * dp));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = (int) (6 * dp);
                badgeTv.setLayoutParams(lp);
                layoutAwardsStrip.addView(badgeTv);
            }
        }

        boolean liked = post.getLikedBy() != null && post.getLikedBy().contains(currentUserId);
        int baseScore = post.getLikesCount();
        if (localPostVoteState == -1 && !liked) {
            baseScore = Math.max(0, baseScore - 1);
        }
        tvVoteScore.setText(String.valueOf(baseScore));
        btnUpvotePost.setTextColor(liked || localPostVoteState == 1
                ? Color.parseColor("#FF4500")
                : ContextCompat.getColor(this, R.color.colorTextPrimary));
        btnDownvotePost.setTextColor(localPostVoteState == -1
                ? Color.parseColor("#7193FF")
                : ContextCompat.getColor(this, R.color.colorTextSecondary));
    }

    private void renderPostAttachments(Post post, JSONObject meta, String voiceFilter,
                                       boolean isCollabChain, JSONArray chainNodes) {
        float dp = getResources().getDisplayMetrics().density;

        // 1. Voice Confession Player
        if ("voice".equalsIgnoreCase(post.getType())
                || (post.getImageUrl() != null && (post.getImageUrl().endsWith(".m4a") || post.getImageUrl().endsWith(".pcm")))) {
            LinearLayout voiceRow = new LinearLayout(this);
            voiceRow.setOrientation(LinearLayout.HORIZONTAL);
            voiceRow.setGravity(Gravity.CENTER_VERTICAL);
            voiceRow.setBackgroundResource(R.drawable.bg_search_input);
            int pad = (int) (12 * dp);
            voiceRow.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vLp.bottomMargin = (int) (10 * dp);
            voiceRow.setLayoutParams(vLp);

            TextView tvPlay = new TextView(this);
            tvPlay.setText("▶️ Play Voice Confession [" + voiceFilter + " DSP]");
            tvPlay.setTextColor(Color.WHITE);
            tvPlay.setTextSize(13.5f);
            tvPlay.setTypeface(null, Typeface.BOLD);
            voiceRow.addView(tvPlay);

            WaveformView waveformView = new WaveformView(this);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, (int) (28 * dp), 1f);
            wLp.leftMargin = (int) (10 * dp);
            waveformView.setLayoutParams(wLp);
            for (int i = 0; i < 18; i++) {
                waveformView.addAmplitude(8000f + (float) (Math.abs(Math.sin(i * 0.7)) * 22000f));
            }
            voiceRow.addView(waveformView);

            voiceRow.setOnClickListener(v -> {
                Toast.makeText(this, "🔊 Playing Voice Confession (" + voiceFilter + " DSP)", Toast.LENGTH_SHORT).show();
                if (post.getImageUrl() != null && (post.getImageUrl().endsWith(".m4a") || post.getImageUrl().endsWith(".wav"))) {
                    try {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setDataSource(post.getImageUrl());
                        mp.prepare();
                        mp.start();
                        mp.setOnCompletionListener(MediaPlayer::release);
                    } catch (Exception ignored) {}
                }
            });
            layoutPostDetailAttachments.addView(voiceRow);
        }

        // 2. Multi-Photo Mosaic & Particle Spoiler
        List<String> mediaList = post.getMediaUrls();
        List<String> validImages = new ArrayList<>();
        if (mediaList != null && !mediaList.isEmpty()) {
            for (String url : mediaList) {
                if (url != null && !url.endsWith(".m4a") && !url.endsWith(".pcm") && !url.endsWith(".wav")) {
                    validImages.add(url);
                }
            }
        } else if (post.getImageUrl() != null && !post.getImageUrl().isEmpty()
                && !post.getImageUrl().endsWith(".m4a") && !post.getImageUrl().endsWith(".pcm") && !post.getImageUrl().endsWith(".wav")) {
            validImages.add(post.getImageUrl());
        }

        if (!validImages.isEmpty()) {
            VeilCanvasSpoilerAndMosaicView mosaicView = new VeilCanvasSpoilerAndMosaicView(this);
            int heightDp = validImages.size() == 1 ? 260 : 300;
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (heightDp * dp));
            mLp.bottomMargin = (int) (10 * dp);
            mosaicView.setLayoutParams(mLp);
            mosaicView.setImages(validImages);
            boolean isSpoiler = post.isSpoiler() && !revealedSpoilers.contains(post.getId());
            mosaicView.setSpoiler(isSpoiler);
            mosaicView.setOnSpoilerStateChangeListener(revealed -> {
                if (revealed && post.getId() != null) revealedSpoilers.add(post.getId());
            });
            mosaicView.setOnTileClickListener((index, url) -> {
                Intent intent = new Intent(this, MediaViewerActivity.class);
                intent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, url);
                intent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "image");
                intent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, "@" + post.getAuthorUsername());
                startActivity(intent);
            });
            layoutPostDetailAttachments.addView(mosaicView);
        }

        // 3. Interactive Poll / Quiz
        if (meta != null && meta.has("options")) {
            JSONArray opts = meta.optJSONArray("options");
            JSONArray votes = meta.optJSONArray("votes");
            boolean isQuiz = meta.optBoolean("isQuiz", false);
            int correctIdx = meta.optInt("correctOptionIndex", 0);

            if (opts != null && opts.length() > 0) {
                int totalVotes = 0;
                if (votes != null) {
                    for (int i = 0; i < votes.length(); i++) totalVotes += votes.optInt(i, 0);
                }

                TextView pollHeader = new TextView(this);
                pollHeader.setText(isQuiz ? "🧠 Anonymous Trivia Quiz:" : "📊 Community Poll (" + totalVotes + " votes):");
                pollHeader.setTextSize(13f);
                pollHeader.setTypeface(null, Typeface.BOLD);
                pollHeader.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
                pollHeader.setPadding(0, 0, 0, (int) (6 * dp));
                layoutPostDetailAttachments.addView(pollHeader);

                for (int i = 0; i < opts.length(); i++) {
                    final int optIndex = i;
                    int v = (votes != null && i < votes.length()) ? votes.optInt(i, 0) : 0;
                    int pct = totalVotes > 0 ? (int) Math.round((v * 100.0) / totalVotes) : 0;

                    TextView optBtn = new TextView(this);
                    optBtn.setText((i + 1) + ". " + opts.optString(i) + "  •  " + v + " votes (" + pct + "%)");
                    optBtn.setTextSize(13.5f);
                    optBtn.setTextColor(Color.WHITE);
                    optBtn.setBackgroundResource(R.drawable.bg_search_input);
                    int pad = (int) (11 * dp);
                    optBtn.setPadding(pad, pad, pad, pad);
                    LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    oLp.bottomMargin = (int) (6 * dp);
                    optBtn.setLayoutParams(oLp);

                    optBtn.setOnClickListener(view -> {
                        VeilBoardController.getInstance(this).voteInPoll(post.getId(), optIndex);
                        Toast.makeText(this, isQuiz
                                ? (optIndex == correctIdx ? "✅ Correct Quiz Answer!" : "❌ Answer was Option #" + (correctIdx + 1))
                                : "📊 Poll vote recorded!", Toast.LENGTH_SHORT).show();
                        reloadPostAndComments();
                    });
                    layoutPostDetailAttachments.addView(optBtn);
                }
            }
        }

        // 4. Collaborative Chain Nodes
        if (isCollabChain && chainNodes != null && chainNodes.length() > 0) {
            for (int i = 0; i < chainNodes.length(); i++) {
                TextView nodeTv = new TextView(this);
                nodeTv.setText("🔗 " + chainNodes.optString(i));
                nodeTv.setTextSize(13.5f);
                nodeTv.setTextColor(0xFF38BDF8);
                nodeTv.setPadding((int) (8 * dp), (int) (4 * dp), (int) (8 * dp), (int) (4 * dp));
                layoutPostDetailAttachments.addView(nodeTv);
            }
        }
    }

    private SpannableStringBuilder buildFormattedSpannable(String rawText) {
        SpannableStringBuilder spannable = new SpannableStringBuilder(rawText);
        int primaryColor = ContextCompat.getColor(this, R.color.colorPrimary);

        int idx = 0;
        while (idx < rawText.length()) {
            int hashStart = rawText.indexOf('#', idx);
            if (hashStart == -1) break;
            int hashEnd = hashStart + 1;
            while (hashEnd < rawText.length() && (Character.isLetterOrDigit(rawText.charAt(hashEnd)) || rawText.charAt(hashEnd) == '_')) {
                hashEnd++;
            }
            if (hashEnd > hashStart + 1) {
                spannable.setSpan(new ForegroundColorSpan(primaryColor), hashStart, hashEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            idx = hashEnd;
        }

        int bIdx = 0;
        while (bIdx < rawText.length()) {
            int start = rawText.indexOf("**", bIdx);
            if (start == -1) break;
            int end = rawText.indexOf("**", start + 2);
            if (end == -1) break;
            spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bIdx = end + 2;
        }

        boolean revealed = revealedSpoilers.contains(postId);
        int sIdx = 0;
        while (sIdx < rawText.length()) {
            int start = rawText.indexOf("||", sIdx);
            if (start == -1) break;
            int end = rawText.indexOf("||", start + 2);
            if (end == -1) break;
            if (!revealed) {
                spannable.setSpan(new BackgroundColorSpan(0xFF334155), start, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(0xFF334155), start + 2, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        revealedSpoilers.add(postId);
                        if (currentPost != null) renderPostHero(currentPost);
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setUnderlineText(false);
                    }
                }, start, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                spannable.setSpan(new BackgroundColorSpan(0x3338BDF8), start, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sIdx = end + 2;
        }
        return spannable;
    }

    private void handlePostVote(int direction) {
        if (currentPost == null) return;
        if (direction == 1) {
            localPostVoteState = (localPostVoteState == 1) ? 0 : 1;
            VeilBoardController.getInstance(this).toggleLike(postId);
        } else {
            localPostVoteState = (localPostVoteState == -1) ? 0 : -1;
        }
        saveVoteAndAwardState();
        reloadPostAndComments();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REDDIT-STYLE MULTI-LEVEL COLLAPSIBLE COMMENT TREE ENGINE
    // ─────────────────────────────────────────────────────────────────────────

    private void renderThreadedComments() {
        if (layoutThreadedCommentsContainer == null) return;
        layoutThreadedCommentsContainer.removeAllViews();

        List<JSONObject> rawComments = VeilBoardController.getInstance(this).getCommentsForPost(postId);
        Map<String, ThreadCommentNode> nodeMap = new HashMap<>();
        List<ThreadCommentNode> rootNodes = new ArrayList<>();

        for (JSONObject obj : rawComments) {
            if (obj == null) continue;
            ThreadCommentNode node = new ThreadCommentNode();
            node.id = obj.optString("id", UUID.randomUUID().toString());
            node.postId = postId;
            node.authorId = obj.optString("authorId", "anon");
            node.authorAlias = obj.optString("authorAlias", obj.optString("authorUsername", "anonymous"));
            node.text = obj.optString("text", obj.optString("content", ""));
            node.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
            node.parentId = obj.optString("parentId", "");
            int baseLikes = obj.optInt("likes", 1);
            int localDelta = localCommentVotes.getOrDefault(node.id, 0);
            node.score = Math.max(0, baseLikes + localDelta);
            node.awardBadge = commentAwards.get(node.id);
            nodeMap.put(node.id, node);
        }

        for (ThreadCommentNode node : nodeMap.values()) {
            if (node.parentId != null && !node.parentId.isEmpty() && nodeMap.containsKey(node.parentId)) {
                ThreadCommentNode parent = nodeMap.get(node.parentId);
                if (parent != null && parent != node) {
                    parent.children.add(node);
                    continue;
                }
            }
            rootNodes.add(node);
        }

        sortCommentNodes(rootNodes);
        for (ThreadCommentNode r : rootNodes) {
            sortChildrenRecursively(r, 0);
        }

        int totalCount = rawComments.size();
        tvCommentsPillText.setText("💬 " + totalCount);
        tvThreadSubHeader.setText(totalCount + (totalCount == 1 ? " comment" : " comments") + " • Sorted by " + activeSortMode.toUpperCase(Locale.ROOT));

        if (rootNodes.isEmpty()) {
            tvEmptyThreadState.setVisibility(View.VISIBLE);
            return;
        }
        tvEmptyThreadState.setVisibility(View.GONE);

        for (ThreadCommentNode root : rootNodes) {
            renderCommentNodeRecursive(root, 0);
        }
    }

    private void sortChildrenRecursively(ThreadCommentNode node, int depth) {
        node.depth = depth;
        sortCommentNodes(node.children);
        for (ThreadCommentNode child : node.children) {
            sortChildrenRecursively(child, Math.min(depth + 1, 4));
        }
    }

    private void sortCommentNodes(List<ThreadCommentNode> nodes) {
        nodes.sort((a, b) -> {
            switch (activeSortMode) {
                case "new":
                    return Long.compare(b.timestamp, a.timestamp);
                case "top":
                    int cmpTop = Integer.compare(b.score, a.score);
                    return cmpTop != 0 ? cmpTop : Long.compare(b.timestamp, a.timestamp);
                case "controversial":
                    int cReplies = Integer.compare(b.children.size(), a.children.size());
                    return cReplies != 0 ? cReplies : Integer.compare(a.score, b.score);
                case "best":
                default:
                    double rankA = a.score * 2.0 + a.children.size() * 1.5;
                    double rankB = b.score * 2.0 + b.children.size() * 1.5;
                    int cmpBest = Double.compare(rankB, rankA);
                    return cmpBest != 0 ? cmpBest : Long.compare(b.timestamp, a.timestamp);
            }
        });
    }

    private int countDescendants(ThreadCommentNode node) {
        int count = node.children.size();
        for (ThreadCommentNode c : node.children) {
            count += countDescendants(c);
        }
        return count;
    }

    private void renderCommentNodeRecursive(ThreadCommentNode node, int depth) {
        float dp = getResources().getDisplayMetrics().density;
        boolean isCollapsed = collapsedCommentIds.contains(node.id);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.leftMargin = (int) (depth * 14 * dp);
        wLp.topMargin = (int) ((depth == 0 ? 8 : 4) * dp);
        wrapper.setLayoutParams(wLp);

        // Reddit Vertical Thread Depth Guide Bar
        if (depth > 0) {
            View guideBar = new View(this);
            LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams((int) (2.5f * dp), ViewGroup.LayoutParams.MATCH_PARENT);
            gLp.rightMargin = (int) (10 * dp);
            guideBar.setLayoutParams(gLp);
            guideBar.setBackgroundColor(THREAD_GUIDE_COLORS[(depth - 1) % THREAD_GUIDE_COLORS.length]);
            wrapper.addView(guideBar);
        }

        LinearLayout cardCol = new LinearLayout(this);
        cardCol.setOrientation(LinearLayout.VERTICAL);
        cardCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cardCol.setBackgroundResource(R.drawable.bg_search_input);
        int pad = (int) (11 * dp);
        cardCol.setPadding(pad, (int) (9 * dp), pad, (int) (9 * dp));

        // Comment Header: @author + [OP] badge + relative time + collapse indicator
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvAuthor = new TextView(this);
        tvAuthor.setText("@" + node.authorAlias);
        tvAuthor.setTextSize(12.5f);
        tvAuthor.setTypeface(null, Typeface.BOLD);
        tvAuthor.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
        headerRow.addView(tvAuthor);

        boolean isOp = currentPost != null && (
                (currentPost.getAuthorId() != null && currentPost.getAuthorId().equals(node.authorId))
                        || (currentPost.getAuthorUsername() != null && currentPost.getAuthorUsername().equalsIgnoreCase(node.authorAlias))
        );
        if (isOp) {
            TextView opBadge = new TextView(this);
            opBadge.setText("OP");
            opBadge.setTextSize(9.5f);
            opBadge.setTypeface(null, Typeface.BOLD);
            opBadge.setTextColor(Color.WHITE);
            opBadge.setBackgroundResource(R.drawable.bg_filter_chip_selected);
            opBadge.setPadding((int) (5 * dp), (int) (1 * dp), (int) (5 * dp), (int) (1 * dp));
            LinearLayout.LayoutParams opLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            opLp.leftMargin = (int) (6 * dp);
            opBadge.setLayoutParams(opLp);
            headerRow.addView(opBadge);
        }

        if (node.awardBadge != null && !node.awardBadge.isEmpty()) {
            TextView aw = new TextView(this);
            aw.setText(" " + node.awardBadge);
            aw.setTextSize(11.5f);
            aw.setTextColor(0xFFFFD700);
            headerRow.addView(aw);
        }

        CharSequence relTime = DateUtils.getRelativeTimeSpanString(
                node.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        TextView tvTime = new TextView(this);
        tvTime.setText("  •  " + relTime);
        tvTime.setTextSize(11f);
        tvTime.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
        tvTime.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(tvTime);

        if (isCollapsed) {
            int hidden = countDescendants(node) + 1;
            TextView tvCollapsed = new TextView(this);
            tvCollapsed.setText("[+" + hidden + " collapsed]");
            tvCollapsed.setTextSize(11f);
            tvCollapsed.setTypeface(null, Typeface.BOLD);
            tvCollapsed.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            headerRow.addView(tvCollapsed);
        }

        cardCol.addView(headerRow);

        if (!isCollapsed) {
            TextView tvBody = new TextView(this);
            tvBody.setText(node.text);
            tvBody.setTextSize(14f);
            tvBody.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
            tvBody.setPadding(0, (int) (5 * dp), 0, (int) (6 * dp));
            cardCol.addView(tvBody);

            // Comment Action Row: Upvote / Score / Downvote / Reply / Award
            LinearLayout actionRow = new LinearLayout(this);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);

            int myVote = localCommentVotes.getOrDefault(node.id, 0);

            TextView btnUp = new TextView(this);
            btnUp.setText("▲ " + node.score);
            btnUp.setTextSize(12f);
            btnUp.setTypeface(null, Typeface.BOLD);
            btnUp.setTextColor(myVote == 1 ? Color.parseColor("#FF4500")
                    : ContextCompat.getColor(this, R.color.colorTextSecondary));
            btnUp.setPadding(0, (int) (2 * dp), (int) (10 * dp), (int) (2 * dp));
            btnUp.setOnClickListener(v -> {
                localCommentVotes.put(node.id, myVote == 1 ? 0 : 1);
                renderThreadedComments();
            });
            actionRow.addView(btnUp);

            TextView btnDown = new TextView(this);
            btnDown.setText("▼");
            btnDown.setTextSize(12f);
            btnDown.setTypeface(null, Typeface.BOLD);
            btnDown.setTextColor(myVote == -1 ? Color.parseColor("#7193FF")
                    : ContextCompat.getColor(this, R.color.colorTextSecondary));
            btnDown.setPadding(0, (int) (2 * dp), (int) (16 * dp), (int) (2 * dp));
            btnDown.setOnClickListener(v -> {
                localCommentVotes.put(node.id, myVote == -1 ? 0 : -1);
                renderThreadedComments();
            });
            actionRow.addView(btnDown);

            TextView btnReply = new TextView(this);
            btnReply.setText("↩ Reply");
            btnReply.setTextSize(12f);
            btnReply.setTypeface(null, Typeface.BOLD);
            btnReply.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            btnReply.setPadding(0, (int) (2 * dp), (int) (16 * dp), (int) (2 * dp));
            btnReply.setOnClickListener(v -> {
                replyingToCommentId = node.id;
                replyingToAuthor = node.authorAlias;
                layoutReplyingBanner.setVisibility(View.VISIBLE);
                tvReplyingToLabel.setText("Replying to @" + replyingToAuthor);
                etThreadCommentInput.setHint("Reply to @" + replyingToAuthor + "...");
                etThreadCommentInput.requestFocus();
            });
            actionRow.addView(btnReply);

            TextView btnAward = new TextView(this);
            btnAward.setText("🏆 Award");
            btnAward.setTextSize(12f);
            btnAward.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
            btnAward.setPadding(0, (int) (2 * dp), (int) (14 * dp), (int) (2 * dp));
            btnAward.setOnClickListener(v -> showGiveAwardSheet(node.id));
            actionRow.addView(btnAward);

            TextView btnCollapse = new TextView(this);
            btnCollapse.setText("Collapse");
            btnCollapse.setTextSize(11.5f);
            btnCollapse.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
            btnCollapse.setOnClickListener(v -> {
                collapsedCommentIds.add(node.id);
                renderThreadedComments();
            });
            actionRow.addView(btnCollapse);

            cardCol.addView(actionRow);
        }

        // Tap header or long-press card to toggle collapse/expand like Reddit
        View.OnClickListener toggleCollapse = v -> {
            if (collapsedCommentIds.contains(node.id)) {
                collapsedCommentIds.remove(node.id);
            } else {
                collapsedCommentIds.add(node.id);
            }
            renderThreadedComments();
        };
        headerRow.setOnClickListener(toggleCollapse);
        cardCol.setOnLongClickListener(v -> {
            toggleCollapse.onClick(v);
            return true;
        });

        wrapper.addView(cardCol);
        layoutThreadedCommentsContainer.addView(wrapper);

        if (!isCollapsed) {
            for (ThreadCommentNode child : node.children) {
                renderCommentNodeRecursive(child, depth + 1);
            }
        }
    }

    private void clearReplyTarget() {
        replyingToCommentId = null;
        replyingToAuthor = null;
        layoutReplyingBanner.setVisibility(View.GONE);
        etThreadCommentInput.setHint("Add an anonymous comment...");
    }

    private void submitComment() {
        String text = etThreadCommentInput.getText().toString().trim();
        if (text.isEmpty()) return;

        String parentId = replyingToCommentId;
        etThreadCommentInput.setText("");
        clearReplyTarget();

        VeilBoardController.getInstance(this).addComment(postId, text, parentId);

        // Also sync comment to Firestore posts/{postId}/comments for real-time cross-device Reddit thread sync
        try {
            Map<String, Object> doc = new HashMap<>();
            String cId = "c_" + UUID.randomUUID().toString();
            doc.put("id", cId);
            doc.put("postId", postId);
            doc.put("authorId", currentUserId);
            doc.put("authorAlias", currentAlias);
            doc.put("text", text);
            doc.put("parentId", parentId != null ? parentId : "");
            doc.put("timestamp", System.currentTimeMillis());
            FirebaseFirestore.getInstance()
                    .collection("posts").document(postId)
                    .collection("comments").document(cId)
                    .set(doc, SetOptions.merge());
        } catch (Exception ignored) {}

        reloadPostAndComments();
    }

    private void promptVoiceComment() {
        String[] dspFilters = {"Ghost Pitch (+4st)", "Cyber Synth", "Deep Shadow (-5st)", "Helium Mask"};
        new AlertDialog.Builder(this)
                .setTitle("🎙️ Post Masked Voice Comment")
                .setItems(dspFilters, (d, which) -> {
                    String tag = "🎙️ [Masked Voice Reply • " + dspFilters[which] + " • 0:07]";
                    VeilBoardController.getInstance(this).addComment(postId, tag, replyingToCommentId);
                    clearReplyTarget();
                    reloadPostAndComments();
                })
                .show();
    }

    private void subscribeRealtimeFirestoreComments() {
        try {
            firestorePostCommentsListener = FirebaseFirestore.getInstance()
                    .collection("posts").document(postId)
                    .collection("comments")
                    .addSnapshotListener((snap, e) -> {
                        if (e != null || snap == null || isFinishing()) return;
                        List<JSONObject> existing = VeilBoardController.getInstance(this).getCommentsForPost(postId);
                        Set<String> knownIds = new HashSet<>();
                        for (JSONObject o : existing) {
                            if (o != null) knownIds.add(o.optString("id"));
                        }
                        boolean addedAny = false;
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            String cid = d.getString("id");
                            if (cid == null) cid = d.getId();
                            String text = d.getString("text");
                            if (text == null || knownIds.contains(cid)) continue;
                            boolean dupText = false;
                            for (JSONObject o : existing) {
                                if (o != null && text.equals(o.optString("text"))) {
                                    dupText = true;
                                    break;
                                }
                            }
                            if (!dupText && currentPost != null) {
                                try {
                                    JSONObject cObj = new JSONObject();
                                    cObj.put("id", cid);
                                    cObj.put("postId", postId);
                                    cObj.put("authorId", d.getString("authorId"));
                                    cObj.put("authorAlias", d.getString("authorAlias"));
                                    cObj.put("text", text);
                                    cObj.put("parentId", d.getString("parentId") != null ? d.getString("parentId") : "");
                                    Long ts = d.getLong("timestamp");
                                    cObj.put("timestamp", ts != null ? ts : System.currentTimeMillis());

                                    JSONArray arr = (currentPost.getCommentsJson() != null && !currentPost.getCommentsJson().isEmpty())
                                            ? new JSONArray(currentPost.getCommentsJson())
                                            : new JSONArray();
                                    arr.put(cObj);
                                    currentPost.setCommentsJson(arr.toString());
                                    currentPost.setCommentsCount(arr.length());
                                    EncryptedChatDatabase.getInstance(this).saveOrUpdatePost(currentPost);
                                    addedAny = true;
                                } catch (Exception ignored) {}
                            }
                        }
                        if (addedAny) {
                            runOnUiThread(this::reloadPostAndComments);
                        }
                    });
        } catch (Exception ignored) {}
    }

    private void showGiveAwardSheet(String targetCommentId) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(targetCommentId == null ? "🏆 Give Anonymous Award to Post" : "🏆 Give Award to Comment");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, (int) (12 * dp));
        root.addView(title);

        String[][] awards = {
                {"🥇 Sovereign Gold", "5 VeilCoins • Highlights exceptional insight", "5"},
                {"🛡️ Cyber Guardian", "3 VeilCoins • Zero-knowledge privacy excellence", "3"},
                {"🤯 Mindblown", "3 VeilCoins • Perspective-shifting post", "3"},
                {"🔥 Pure Fire", "2 VeilCoins • Trending community heat", "2"},
                {"💎 Diamond Hands", "10 VeilCoins • Elite thread contribution", "10"}
        };

        for (String[] aw : awards) {
            final String badge = aw[0];
            final int cost = Integer.parseInt(aw[2]);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_search_input);
            row.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            row.setLayoutParams(lp);

            TextView tvName = new TextView(this);
            tvName.setText(badge);
            tvName.setTextSize(15f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(0xFFFFD700);
            row.addView(tvName);

            TextView tvSub = new TextView(this);
            tvSub.setText(aw[1]);
            tvSub.setTextSize(12f);
            tvSub.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
            row.addView(tvSub);

            row.setOnClickListener(v -> {
                sheet.dismiss();
                VeilBoardController.getInstance(this).tipAuthor(postId, cost);
                if (targetCommentId == null) {
                    postAwards.add(badge);
                    saveVoteAndAwardState();
                    if (currentPost != null) renderPostHero(currentPost);
                } else {
                    commentAwards.put(targetCommentId, badge);
                    renderThreadedComments();
                }
                Toast.makeText(this, "🏆 Awarded " + badge + " anonymously!", Toast.LENGTH_SHORT).show();
            });
            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void bookmarkCurrentPost() {
        if (currentPost == null) return;
        EncryptedChatDatabase.getInstance(this).saveVaultNote(
                "bm_" + postId,
                "🔖 Saved Thread by @" + currentPost.getAuthorUsername(),
                currentPost.getContent(),
                "🔖 VeilBoard Thread",
                0L
        );
        Toast.makeText(this, "🔖 Thread bookmarked!", Toast.LENGTH_SHORT).show();
    }

    private void shareCurrentPost() {
        if (currentPost == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                "@" + currentPost.getAuthorUsername() + " on v/" + (currentPost.getCategory() != null ? currentPost.getCategory() : "veilboard")
                        + ":\n" + currentPost.getContent());
        startActivity(Intent.createChooser(shareIntent, "Share Veil Thread"));
    }

    private void openDirectChatWithOp() {
        if (currentPost == null || currentPost.getAuthorId() == null) return;
        String authorId = currentPost.getAuthorId();
        String authorUsername = currentPost.getAuthorUsername() != null ? currentPost.getAuthorUsername() : "Anonymous";
        if (authorId.equals(currentUserId)) {
            Toast.makeText(this, "You are the author (OP) of this thread", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> ids = Arrays.asList(currentUserId, authorId);
        Collections.sort(ids);
        String chatId = ids.get(0) + "_" + ids.get(1);

        Chat existing = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (existing == null) {
            Chat newChat = new Chat(
                    chatId,
                    authorUsername,
                    "Started from v/" + (currentPost.getCategory() != null ? currentPost.getCategory() : "veilboard") + " thread",
                    Timestamp.now(),
                    null,
                    0,
                    false,
                    false,
                    "direct",
                    Arrays.asList(currentUserId, authorId)
            );
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(newChat);
        }

        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("recipientId", authorId);
        intent.putExtra("recipientName", authorUsername);
        startActivity(intent);
    }

    private void showPostMoreActionsSheet() {
        if (currentPost == null) return;
        String[] items = {
                "🔖 Bookmark Thread",
                "📋 Copy Thread Text",
                "🔄 Instant Repost to Profile",
                "⚖️ Report Thread to Community Jury"
        };
        new AlertDialog.Builder(this)
                .setTitle("Thread Options")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            bookmarkCurrentPost();
                            break;
                        case 1:
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("Veil Thread", currentPost.getContent()));
                                Toast.makeText(this, "Thread text copied", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 2:
                            VeilBoardController.getInstance(this).repostPost(postId, null, true, false);
                            Toast.makeText(this, "Reposted to your profile!", Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            VeilBoardController.getInstance(this).reportPost(postId, "Reported from Thread Detail");
                            Toast.makeText(this, "⚖️ Submitted to Decentralized Community Jury", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }
}
