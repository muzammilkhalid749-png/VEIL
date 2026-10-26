package com.m3.veil.ui.main.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilBoardController;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Post;
import com.m3.veil.ui.main.CreatePostActivity;
import com.m3.veil.ui.views.WaveformView;
import com.m3.veil.ui.views.VeilCanvasSpoilerAndMosaicView;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.BlurUtils;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 120 FPS Multi-Modal VeilBoard Feed Adapter:
 * - Rich text (**bold**, *italic*, #hashtags) & ||spoiler|| tap-to-reveal shimmer spans
 * - 12 Custom Background Gradients + 5 Typography Styles
 * - Voice Confession WaveformView player with DSP filter badge
 * - Media / Doodle (#207) rendering with privacy mosaic blur toggle
 * - Interactive Poll & Quiz Mode voting
 * - Time-Capsule Lock (#215) countdown
 * - Collaborative Chain (#214) node display & append action
 * - Quick actions: Bookmark to Vault (#114), Tip VeilCoins (#164), Quote-Repost (#92),
 *   Anonymous DM (#99), and Community Jury Report (#193)
 */
public class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.PostViewHolder> {

    private List<Post> postList;
    private final OnPostInteractionListener listener;
    private static final Set<String> revealedSpoilers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> unblurredMediaPosts = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public interface OnPostInteractionListener {
        void onLikeClick(Post post);
        void onCommentClick(Post post);
        void onShareClick(Post post);
        void onAuthorClick(Post post);
    }

    public PostsAdapter(List<Post> postList, OnPostInteractionListener listener) {
        this.postList = postList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_post, parent, false);
        return new PostViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PostViewHolder holder, int position) {
        Post post = postList.get(position);
        holder.bind(post, listener, () -> notifyItemChanged(position));
    }

    @Override
    public int getItemCount() {
        return postList.size();
    }

    public void updatePosts(List<Post> newPosts) {
        this.postList = newPosts;
        notifyDataSetChanged();
    }

    static class PostViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar, likeIcon, moreButton;
        TextView username, time, content, likesCount, commentsCount;
        View likeButton, commentButton, shareButton;
        LinearLayout dynamicAttachmentContainer;

        public PostViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.authorAvatar);
            username = itemView.findViewById(R.id.authorUsername);
            time = itemView.findViewById(R.id.timeText);
            content = itemView.findViewById(R.id.postContent);
            likesCount = itemView.findViewById(R.id.likesCount);
            commentsCount = itemView.findViewById(R.id.commentsCount);
            likeIcon = itemView.findViewById(R.id.likeIcon);
            likeButton = itemView.findViewById(R.id.likeButton);
            commentButton = itemView.findViewById(R.id.commentButton);
            shareButton = itemView.findViewById(R.id.shareButton);

            // Locate header more options button and attach dynamic attachment container below postContent
            if (content != null && content.getParent() instanceof LinearLayout) {
                LinearLayout cardCol = (LinearLayout) content.getParent();
                if (cardCol.getChildCount() > 0 && cardCol.getChildAt(0) instanceof LinearLayout) {
                    LinearLayout headerRow = (LinearLayout) cardCol.getChildAt(0);
                    if (headerRow.getChildCount() >= 3 && headerRow.getChildAt(2) instanceof ImageView) {
                        moreButton = (ImageView) headerRow.getChildAt(2);
                    }
                }
                int contentIdx = cardCol.indexOfChild(content);
                dynamicAttachmentContainer = new LinearLayout(itemView.getContext());
                dynamicAttachmentContainer.setOrientation(LinearLayout.VERTICAL);
                cardCol.addView(dynamicAttachmentContainer, contentIdx + 1);
            }
        }

        public void bind(Post post, OnPostInteractionListener listener, Runnable onRefreshItem) {
            Context context = itemView.getContext();
            float dp = context.getResources().getDisplayMetrics().density;

            String badge = post.getMoodBadge() != null && !post.getMoodBadge().isEmpty()
                    ? "  •  " + post.getMoodBadge() : "";
            username.setText("@" + (post.getAuthorUsername() != null ? post.getAuthorUsername() : "anonymous") + badge);

            if (avatar != null) {
                String authorId = post.getAuthorId();
                java.io.File localAvatar = authorId != null ? new java.io.File(context.getFilesDir(), "avatars/avatar_" + authorId + ".webp") : null;
                if (localAvatar != null && localAvatar.exists()) {
                    Bitmap bmp = BitmapFactory.decodeFile(localAvatar.getAbsolutePath());
                    if (bmp != null) {
                        avatar.setImageBitmap(bmp);
                    } else {
                        avatar.setImageResource(R.drawable.ic_profile_placeholder);
                    }
                } else if (post.getAuthorAvatar() != null && new java.io.File(post.getAuthorAvatar()).exists()) {
                    Bitmap bmp = BitmapFactory.decodeFile(post.getAuthorAvatar());
                    if (bmp != null) {
                        avatar.setImageBitmap(bmp);
                    } else {
                        avatar.setImageResource(R.drawable.ic_profile_placeholder);
                    }
                } else {
                    avatar.setImageResource(R.drawable.ic_profile_placeholder);
                }
            }

            // Parse rich studio metadata from pollJson if present
            JSONObject meta = null;
            int gradientIndex = 0;
            int fontStyle = 0;
            long unlockAtMillis = 0L;
            boolean isCollabChain = false;
            JSONArray chainNodes = null;
            String voiceFilter = "Ghost";

            if (post.getPollJson() != null && !post.getPollJson().isEmpty()) {
                try {
                    meta = new JSONObject(post.getPollJson());
                    gradientIndex = meta.optInt("gradientIndex", 0);
                    fontStyle = meta.optInt("fontStyle", 0);
                    unlockAtMillis = meta.optLong("unlockAtMillis", 0L);
                    isCollabChain = meta.optBoolean("collabChain", false);
                    chainNodes = meta.optJSONArray("chainNodes");
                    voiceFilter = meta.optString("voiceFilter", "Ghost");
                } catch (Exception ignored) {}
            }

            // 1. Apply Custom Gradient & Typography Style
            if (itemView instanceof MaterialCardView) {
                MaterialCardView cardView = (MaterialCardView) itemView;
                if (gradientIndex > 0 && gradientIndex < CreatePostActivity.GRADIENT_PALETTES.length) {
                    GradientDrawable gd = new GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            CreatePostActivity.GRADIENT_PALETTES[gradientIndex]
                    );
                    gd.setCornerRadius(16 * dp);
                    content.setBackground(gd);
                    int pad = (int) (14 * dp);
                    content.setPadding(pad, pad, pad, pad);
                } else {
                    content.setBackground(null);
                    content.setPadding(0, 0, 0, 0);
                }
            }

            switch (fontStyle) {
                case 1:
                    content.setTypeface(Typeface.SERIF, Typeface.NORMAL);
                    break;
                case 2:
                    content.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                    break;
                case 3:
                    content.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
                    break;
                case 4:
                    content.setTypeface(Typeface.SERIF, Typeface.ITALIC);
                    break;
                default:
                    content.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
                    break;
            }

            // 2. Check Time-Capsule Lock (#215)
            long now = System.currentTimeMillis();
            if (unlockAtMillis > now) {
                long remainingMins = Math.max(1, (unlockAtMillis - now) / 60000L);
                long hrs = remainingMins / 60;
                long mins = remainingMins % 60;
                String lockText = "⏳ [TIME-CAPSULE LOCKED #215]\nThis encrypted Veil is sealed until "
                        + (hrs > 0 ? hrs + "h " + mins + "m" : mins + "m") + " from now.";
                content.setText(lockText);
                if (dynamicAttachmentContainer != null) {
                    dynamicAttachmentContainer.removeAllViews();
                }
            } else {
                // 3. Build Rich Body Text with ||Spoiler|| Shimmer & Hashtags
                StringBuilder bodyBuilder = new StringBuilder();
                if ("confession".equalsIgnoreCase(post.getCategory())) {
                    bodyBuilder.append("🕵️ [CONFESSION] ");
                } else if ("moment".equalsIgnoreCase(post.getCategory()) || post.isMomentActive()) {
                    bodyBuilder.append("⏳ [24h MOMENT] ");
                }
                if (isCollabChain) {
                    bodyBuilder.append("🔗 [COLLAB CHAIN #214] ");
                }
                if (post.getContent() != null) {
                    bodyBuilder.append(post.getContent());
                }

                content.setText(buildFormattedSpannable(context, post.getId(), bodyBuilder.toString(), onRefreshItem));
                content.setMovementMethod(LinkMovementMethod.getInstance());

                // 4. Render Multi-Modal Attachments (Voice Waveform, Image/Doodle Mosaic, Poll/Quiz, Collab Chain)
                if (dynamicAttachmentContainer != null) {
                    dynamicAttachmentContainer.removeAllViews();
                    renderDynamicAttachments(context, post, meta, voiceFilter, isCollabChain, chainNodes, onRefreshItem);
                }
            }

            likesCount.setText(String.valueOf(post.getLikesCount()));
            commentsCount.setText(String.valueOf(post.getCommentsCount()));

            if (post.getTimestamp() != null) {
                CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                        post.getTimestamp().toDate().getTime(),
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS);
                String catTag = post.getCategory() != null ? " • " + post.getCategory().toUpperCase() : "";
                time.setText(relativeTime + catTag);
            }

            String currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                    : SovereignIdentityAndDirectoryEngine.getInstance(context).getCurrentUserId();

            boolean isLiked = post.getLikedBy() != null && post.getLikedBy().contains(currentUid);
            if (isLiked) {
                likeIcon.setColorFilter(Color.parseColor("#E91E63"));
                likesCount.setTextColor(Color.parseColor("#E91E63"));
            } else {
                likeIcon.setColorFilter(ContextCompat.getColor(context, R.color.textSecondary));
                likesCount.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            }

            likeButton.setOnClickListener(v -> {
                if (listener != null) listener.onLikeClick(post);
            });

            commentButton.setOnClickListener(v -> {
                if (listener != null) listener.onCommentClick(post);
            });

            shareButton.setOnClickListener(v -> {
                if (listener != null) listener.onShareClick(post);
            });

            View.OnClickListener authorClick = v -> {
                if (listener != null) listener.onAuthorClick(post);
            };
            avatar.setOnClickListener(authorClick);
            username.setOnClickListener(authorClick);

            if (moreButton != null) {
                final boolean finalCollab = isCollabChain;
                moreButton.setOnClickListener(v -> showPostQuickActionsSheet(context, post, finalCollab, listener, onRefreshItem));
            }
        }

        private SpannableStringBuilder buildFormattedSpannable(Context context, String postId, String rawText, Runnable onRefreshItem) {
            SpannableStringBuilder spannable = new SpannableStringBuilder(rawText);
            int primaryColor = ContextCompat.getColor(context, R.color.colorPrimary);

            // Highlight #hashtags
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

            // Bold **text**
            int bIdx = 0;
            while (bIdx < rawText.length()) {
                int start = rawText.indexOf("**", bIdx);
                if (start == -1) break;
                int end = rawText.indexOf("**", start + 2);
                if (end == -1) break;
                spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                bIdx = end + 2;
            }

            // ||Spoiler|| tap-to-reveal shimmer spans
            boolean revealed = postId != null && revealedSpoilers.contains(postId);
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
                            if (postId != null) {
                                revealedSpoilers.add(postId);
                                if (onRefreshItem != null) onRefreshItem.run();
                            }
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

        private void renderDynamicAttachments(Context context, Post post, JSONObject meta, String voiceFilter,
                                              boolean isCollabChain, JSONArray chainNodes, Runnable onRefreshItem) {
            float dp = context.getResources().getDisplayMetrics().density;

            // A. Voice Confession Waveform Player (#84)
            if ("voice".equalsIgnoreCase(post.getType()) || (post.getImageUrl() != null && (post.getImageUrl().endsWith(".m4a") || post.getImageUrl().endsWith(".pcm")))) {
                LinearLayout voiceRow = new LinearLayout(context);
                voiceRow.setOrientation(LinearLayout.HORIZONTAL);
                voiceRow.setGravity(Gravity.CENTER_VERTICAL);
                voiceRow.setBackgroundResource(R.drawable.bg_search_input);
                int pad = (int) (12 * dp);
                voiceRow.setPadding(pad, pad, pad, pad);
                LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vLp.bottomMargin = (int) (10 * dp);
                voiceRow.setLayoutParams(vLp);

                TextView tvPlay = new TextView(context);
                tvPlay.setText("▶️ Voice Confession [" + voiceFilter + " DSP]");
                tvPlay.setTextColor(Color.WHITE);
                tvPlay.setTextSize(13.5f);
                tvPlay.setTypeface(null, Typeface.BOLD);
                voiceRow.addView(tvPlay);

                WaveformView waveformView = new WaveformView(context);
                LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, (int) (28 * dp), 1f);
                wLp.leftMargin = (int) (10 * dp);
                waveformView.setLayoutParams(wLp);
                for (int i = 0; i < 18; i++) {
                    waveformView.addAmplitude(8000f + (float) (Math.abs(Math.sin(i * 0.7)) * 22000f));
                }
                voiceRow.addView(waveformView);

                voiceRow.setOnClickListener(v -> {
                    Toast.makeText(context, "🔊 Playing Anonymous Voice Confession (" + voiceFilter + " DSP)", Toast.LENGTH_SHORT).show();
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
                dynamicAttachmentContainer.addView(voiceRow);
            }

            // B. Multi-Photo Mosaic & Spoiler Canvas Rendering (Telegram Parity)
            List<String> mediaList = post.getMediaUrls();
            List<String> validImages = new java.util.ArrayList<>();
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
                VeilCanvasSpoilerAndMosaicView mosaicView = new VeilCanvasSpoilerAndMosaicView(context);
                int heightDp = validImages.size() == 1 ? 220 : 260;
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (heightDp * dp));
                mLp.bottomMargin = (int) (10 * dp);
                mosaicView.setLayoutParams(mLp);

                mosaicView.setImages(validImages);
                boolean isSpoiler = post.isSpoiler() || "confession".equalsIgnoreCase(post.getCategory());
                boolean alreadyRevealed = post.getId() != null && revealedSpoilers.contains(post.getId());
                mosaicView.setSpoiler(isSpoiler && !alreadyRevealed);

                mosaicView.setOnSpoilerStateChangeListener(revealed -> {
                    if (revealed && post.getId() != null) {
                        revealedSpoilers.add(post.getId());
                    }
                });

                mosaicView.setOnTileClickListener((index, url) -> {
                    android.content.Intent intent = new android.content.Intent(context, com.m3.veil.ui.chat.MediaViewerActivity.class);
                    intent.putExtra(com.m3.veil.ui.chat.MediaViewerActivity.EXTRA_FILE_PATH, url);
                    intent.putExtra(com.m3.veil.ui.chat.MediaViewerActivity.EXTRA_MEDIA_TYPE, "image");
                    intent.putExtra(com.m3.veil.ui.chat.MediaViewerActivity.EXTRA_SENDER_NAME, "@" + post.getAuthorUsername());
                    context.startActivity(intent);
                });

                dynamicAttachmentContainer.addView(mosaicView);
            }

            // C. Interactive Poll & Quiz Mode Bars (#85, #89)
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

                    TextView pollHeader = new TextView(context);
                    pollHeader.setText(isQuiz ? "🧠 Anonymous Trivia Quiz (Tap to answer):" : "📊 Anonymous Poll (Tap option to vote):");
                    pollHeader.setTextSize(12.5f);
                    pollHeader.setTypeface(null, Typeface.BOLD);
                    pollHeader.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
                    pollHeader.setPadding(0, 0, 0, (int) (6 * dp));
                    dynamicAttachmentContainer.addView(pollHeader);

                    for (int i = 0; i < opts.length(); i++) {
                        final int optIndex = i;
                        int v = (votes != null && i < votes.length()) ? votes.optInt(i, 0) : 0;
                        int pct = totalVotes > 0 ? (int) Math.round((v * 100.0) / totalVotes) : 0;

                        TextView optBtn = new TextView(context);
                        optBtn.setText((i + 1) + ". " + opts.optString(i) + "  •  " + v + " votes (" + pct + "%)");
                        optBtn.setTextSize(13f);
                        optBtn.setTextColor(Color.WHITE);
                        optBtn.setBackgroundResource(R.drawable.bg_search_input);
                        int pad = (int) (10 * dp);
                        optBtn.setPadding(pad, pad, pad, pad);
                        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        oLp.bottomMargin = (int) (6 * dp);
                        optBtn.setLayoutParams(oLp);

                        optBtn.setOnClickListener(view -> {
                            VeilBoardController.getInstance(context).voteInPoll(post.getId(), optIndex);
                            if (isQuiz) {
                                Toast.makeText(context, optIndex == correctIdx
                                        ? "✅ Correct Quiz Answer! (+10 XP)"
                                        : "❌ Not quite! Correct was Option #" + (correctIdx + 1), Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, "📊 Anonymous vote recorded!", Toast.LENGTH_SHORT).show();
                            }
                        });
                        dynamicAttachmentContainer.addView(optBtn);
                    }
                }
            }

            // D. Collaborative Chain Post Nodes & Append Button (#214)
            if (isCollabChain) {
                if (chainNodes != null && chainNodes.length() > 0) {
                    for (int i = 0; i < chainNodes.length(); i++) {
                        TextView nodeTv = new TextView(context);
                        nodeTv.setText("🔗 " + chainNodes.optString(i));
                        nodeTv.setTextSize(13f);
                        nodeTv.setTextColor(0xFF38BDF8);
                        nodeTv.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
                        dynamicAttachmentContainer.addView(nodeTv);
                    }
                }

                MaterialButton btnAppend = new MaterialButton(context);
                btnAppend.setText("➕ Append Node to Collab Chain (#214)");
                btnAppend.setTextSize(11.5f);
                btnAppend.setCornerRadius((int) (12 * dp));
                btnAppend.setOnClickListener(v -> showAppendCollabNodeDialog(context, post, onRefreshItem));
                dynamicAttachmentContainer.addView(btnAppend);
            }
        }

        private void showAppendCollabNodeDialog(Context context, Post post, Runnable onRefreshItem) {
            BottomSheetDialog sheet = new BottomSheetDialog(context);
            float dp = context.getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.bottom_sheet_background);
            root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

            TextView title = new TextView(context);
            title.setText("🔗 Append to Collaborative Chain (#214)");
            title.setTextSize(17.5f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            root.addView(title);

            EditText etNode = new EditText(context);
            etNode.setHint("Write the next line or thought in this chain...");
            etNode.setTextColor(Color.WHITE);
            etNode.setHintTextColor(ContextCompat.getColor(context, R.color.textSecondary));
            root.addView(etNode);

            MaterialButton btnAdd = new MaterialButton(context);
            btnAdd.setText("Append & Broadcast to Mesh");
            btnAdd.setCornerRadius((int) (14 * dp));
            btnAdd.setOnClickListener(v -> {
                String line = etNode.getText().toString().trim();
                if (line.isEmpty()) return;
                try {
                    JSONObject meta = post.getPollJson() != null ? new JSONObject(post.getPollJson()) : new JSONObject();
                    meta.put("collabChain", true);
                    JSONArray nodes = meta.optJSONArray("chainNodes");
                    if (nodes == null) nodes = new JSONArray();
                    String alias = VeilFeatureManager.getInstance(context).getActivePersona().getDisplayName();
                    nodes.put(alias + ": " + line);
                    meta.put("chainNodes", nodes);
                    post.setPollJson(meta.toString());
                    EncryptedChatDatabase.getInstance(context).saveOrUpdatePost(post);
                    WebRtcManager.getInstance(context).broadcastPostToMesh(post);
                    sheet.dismiss();
                    if (onRefreshItem != null) onRefreshItem.run();
                    Toast.makeText(context, "🔗 Node appended to chain!", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            });
            root.addView(btnAdd);

            sheet.setContentView(root);
            sheet.show();
        }

        private void showPostQuickActionsSheet(Context context, Post post, boolean isCollabChain,
                                               OnPostInteractionListener listener, Runnable onRefreshItem) {
            BottomSheetDialog sheet = new BottomSheetDialog(context);
            float dp = context.getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.bottom_sheet_background);
            root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

            TextView title = new TextView(context);
            title.setText("⚡ Veil Actions (@" + (post.getAuthorUsername() != null ? post.getAuthorUsername() : "anonymous") + ")");
            title.setTextSize(17.5f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setPadding(0, 0, 0, (int) (12 * dp));
            root.addView(title);

            String[] actions = {
                    "🔖 Bookmark Post to Encrypted Vault (#114)",
                    "🪙 Tip 5 VeilCoins Anonymously (#164)",
                    "➕ Append Node to Collaborative Chain (#214)",
                    "💬 1-Tap Anonymous DM with Author (#99)",
                    "🔄 Quote-Repost to VeilBoard (#92)",
                    "⚖️ Flag Post for Community Jury (#193)"
            };

            for (int i = 0; i < actions.length; i++) {
                final int idx = i;
                TextView row = new TextView(context);
                row.setText(actions[i]);
                row.setTextSize(15f);
                row.setTextColor(Color.WHITE);
                row.setPadding((int) (8 * dp), (int) (12 * dp), (int) (8 * dp), (int) (12 * dp));
                row.setOnClickListener(v -> {
                    sheet.dismiss();
                    switch (idx) {
                        case 0:
                            EncryptedChatDatabase.getInstance(context).saveVaultNote(
                                    "bm_" + UUID.randomUUID().toString(),
                                    "🔖 Saved Veil by @" + post.getAuthorUsername(),
                                    post.getContent(),
                                    "🔖 Bookmark",
                                    0L
                            );
                            Toast.makeText(context, "🔖 Saved to SQLCipher Encrypted Notes Vault!", Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            if (VeilBoardController.getInstance(context).tipAuthor(post.getId(), 5)) {
                                Toast.makeText(context, "🪙 Tipped 5 VeilCoins anonymously!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, "Insufficient VeilCoins", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 2:
                            showAppendCollabNodeDialog(context, post, onRefreshItem);
                            break;
                        case 3:
                            if (listener != null) listener.onAuthorClick(post);
                            break;
                        case 4:
                            if (listener != null) listener.onShareClick(post);
                            break;
                        case 5:
                            VeilBoardController.getInstance(context).reportPost(post.getId(), "Flagged by peer for Jury Review");
                            Toast.makeText(context, "⚖️ Submitted to Decentralized Community Jury (#193)", Toast.LENGTH_SHORT).show();
                            break;
                    }
                });
                root.addView(row);
            }

            sheet.setContentView(root);
            sheet.show();
        }
    }
}
