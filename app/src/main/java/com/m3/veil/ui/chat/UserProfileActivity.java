package com.m3.veil.ui.chat;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.User;
import com.m3.veil.ui.chat.sheets.EventsCalendarBottomSheet;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.webrtc.WebRtcManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UserProfileActivity extends AppCompatActivity {

    private long currentVanishTimer = 0;
    private String currentVanishMode = "after_send";
    private boolean isUserBlocked = false;
    private String currentUserId;
    private String peerUserId;
    private String peerUserName;
    private String chatId;
    private boolean isGroup = false;

    private TextView tvBlockUser;
    private ImageView ivBlockUser;
    private LinearLayout llGroupParticipants;
    private TextView tvParticipantsHeader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_user_profile);

        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        android.view.View mainView = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            androidx.core.graphics.Insets systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        peerUserId = getIntent().getStringExtra("userId");
        if (peerUserId != null && peerUserId.trim().isEmpty()) {
            peerUserId = null;
        }
        peerUserName = getIntent().getStringExtra("userName");
        chatId = getIntent().getStringExtra("chatId");
        isGroup = getIntent().getBooleanExtra("isGroup", false);
        if (chatId != null && (chatId.startsWith("group_") || chatId.contains("group"))) {
            isGroup = true;
        }
        Chat chat = chatId != null ? EncryptedChatDatabase.getInstance(this).getChat(chatId) : null;
        if (chat != null && chat.isGroup()) {
            isGroup = true;
        }

        TextView nameText = findViewById(R.id.profileNameText);
        TextView profileSubtitle = findViewById(R.id.profileSubtitleText);
        if (peerUserName != null && nameText != null) {
            nameText.setText(peerUserName);
        }

        de.hdodenhof.circleimageview.CircleImageView profileAvatarImage = findViewById(R.id.profileAvatarImage);
        final String[] activeAvatarSource = {
                (chat != null && chat.getAvatar() != null && !chat.getAvatar().isEmpty())
                        ? chat.getAvatar() : null
        };
        if (profileAvatarImage != null) {
            com.m3.veil.utils.VeilAvatarLoader.loadAvatar(profileAvatarImage, activeAvatarSource[0], peerUserName, isGroup);
            profileAvatarImage.setOnClickListener(v -> {
                if (activeAvatarSource[0] != null && !activeAvatarSource[0].isEmpty()) {
                    Intent viewerIntent = new Intent(this, MediaViewerActivity.class);
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, activeAvatarSource[0]);
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE, "image");
                    viewerIntent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME, peerUserName != null ? peerUserName : "Profile");
                    startActivity(viewerIntent);
                }
            });
        }
        if (!isGroup && peerUserId != null && !peerUserId.trim().isEmpty()
                && !peerUserId.startsWith("group_") && !peerUserId.startsWith("channel_")) {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine engine =
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this);
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest cachedM =
                    EncryptedChatDatabase.getInstance(this).getSovereignProfileManifest(peerUserId);
            if (cachedM != null) {
                applyResolvedPeerManifest(cachedM, nameText, profileSubtitle, profileAvatarImage, activeAvatarSource, chat);
            }
            // Also fetch latest manifest from Firestore (50ms) with Nostr relay fallback
            engine.fetchManifestFromFirestore(peerUserId, firestoreManifest -> {
                if (firestoreManifest != null) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            applyResolvedPeerManifest(firestoreManifest, nameText, profileSubtitle, profileAvatarImage, activeAvatarSource, chat);
                        }
                    });
                } else {
                    engine.queryNostrRelays(peerUserId, 3000, nostrManifest -> {
                        if (nostrManifest != null) {
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    applyResolvedPeerManifest(nostrManifest, nameText, profileSubtitle, profileAvatarImage, activeAvatarSource, chat);
                                }
                            });
                        }
                    });
                }
            });
        }

        tvBlockUser = findViewById(R.id.tvBlockUser);
        ivBlockUser = findViewById(R.id.ivBlockUser);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        android.view.View btnAudio = findViewById(R.id.btnProfileAudioCall);
        android.view.View btnVideo = findViewById(R.id.btnProfileVideoCall);
        if (btnAudio != null) {
            UiAnimator.attachSpringPress(btnAudio);
            btnAudio.setOnClickListener(v -> startCall("audio"));
        }
        if (btnVideo != null) {
            UiAnimator.attachSpringPress(btnVideo);
            btnVideo.setOnClickListener(v -> startCall("video"));
        }

        if (isGroup) {
            boolean isChan = (chat != null && chat.isChannel()) || (chatId != null && chatId.startsWith("channel_"));
            String descText = (chat != null && chat.getDescription() != null && !chat.getDescription().isEmpty())
                    ? chat.getDescription()
                    : (isChan ? "End-to-End Encrypted Broadcast Channel" : "End-to-End Encrypted Group");
            if (profileSubtitle != null) profileSubtitle.setText(descText);
            android.view.View groupMembersLayout = findViewById(R.id.layoutGroupMembers);
            if (groupMembersLayout != null) groupMembersLayout.setVisibility(android.view.View.VISIBLE);
            llGroupParticipants = findViewById(R.id.llGroupParticipants);
            tvParticipantsHeader = findViewById(R.id.tvParticipantsHeader);
            android.view.View btnAddPart = findViewById(R.id.btnAddParticipant);
            if (btnAddPart != null) {
                UiAnimator.attachSpringPress(btnAddPart);
                btnAddPart.setOnClickListener(v -> showAddParticipantDialog());
            }
            loadAndRenderGroupMembers();
            setupGroupActionOptions();
        } else {
            setupDirectChatAdvancedOptions();
        }

        loadLocalChatMetadata();

        findViewById(R.id.btnEncryption).setOnClickListener(v -> showSafetyNumberDialog());
        findViewById(R.id.btnMedia).setOnClickListener(v -> showSharedMediaGalleryDialog());
        findViewById(R.id.btnMute).setOnClickListener(v -> toggleMuteChat());
        findViewById(R.id.btnVanishTimer).setOnClickListener(v -> showVanishTimerDialog());
        View vanishModeRow = findViewById(R.id.btnVanishMode);
        if (vanishModeRow != null) {
            vanishModeRow.setVisibility(View.VISIBLE);
            vanishModeRow.setOnClickListener(v -> showVanishModeDialog());
        }
        findViewById(R.id.btnClearChat).setOnClickListener(v -> confirmClearChat());
        findViewById(R.id.btnBlockUser).setOnClickListener(v -> handleBlockOrLeave());
    }

    private void applyResolvedPeerManifest(
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest m,
            TextView nameText,
            TextView profileSubtitle,
            de.hdodenhof.circleimageview.CircleImageView profileAvatarImage,
            String[] activeAvatarSource,
            Chat chat) {
        if (m == null) return;
        if (m.displayName != null && !m.displayName.trim().isEmpty()) {
            peerUserName = m.displayName.trim();
            if (nameText != null) {
                nameText.setText(peerUserName);
            }
        }
        if (profileSubtitle != null) {
            StringBuilder sub = new StringBuilder();
            if (m.username != null && !m.username.trim().isEmpty()) {
                sub.append("@").append(m.username.trim());
            }
            if (m.bio != null && !m.bio.trim().isEmpty()) {
                if (sub.length() > 0) sub.append("  •  ");
                sub.append(m.bio.trim());
            }
            if (sub.length() > 0) {
                profileSubtitle.setText(sub.toString());
            }
        }
        if (m.avatarBase64 != null && !m.avatarBase64.trim().isEmpty()) {
            activeAvatarSource[0] = m.avatarBase64.trim();
            if (profileAvatarImage != null) {
                com.m3.veil.utils.VeilAvatarLoader.loadAvatar(profileAvatarImage, activeAvatarSource[0], peerUserName, false);
            }
            if (chat != null && (chat.getAvatar() == null || !chat.getAvatar().equals(activeAvatarSource[0]))) {
                chat.setAvatar(activeAvatarSource[0]);
                EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
            }
        }
    }

    private void startCall(String type) {
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CALL_TYPE, type);
        intent.putExtra(CallActivity.EXTRA_CALL_ID, UUID.randomUUID().toString());
        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
        if (isGroup && chatId != null) {
            Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
            List<String> parts = chat != null && chat.getParticipants() != null
                    ? new ArrayList<>(chat.getParticipants()) : new ArrayList<>();
            parts.remove(currentUserId);
            if (parts.isEmpty()) {
                Toast.makeText(this, "No other group members available to call", Toast.LENGTH_SHORT).show();
                return;
            }
            intent.putExtra(CallActivity.EXTRA_CONTACT_ID, parts.get(0));
            intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, peerUserName != null ? peerUserName : "Group Call");
            intent.putExtra(CallActivity.EXTRA_GROUP_ID, chatId);
            intent.putExtra(CallActivity.EXTRA_GROUP_NAME, peerUserName != null ? peerUserName : "Group Call");
            intent.putExtra(CallActivity.EXTRA_PARTICIPANT_IDS, parts.toArray(new String[0]));
        } else {
            if (peerUserId == null || peerUserId.isEmpty()) {
                Toast.makeText(this, "Cannot initiate call: Peer unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            intent.putExtra(CallActivity.EXTRA_CONTACT_ID, peerUserId);
            intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, peerUserName != null ? peerUserName : "Peer");
        }
        startActivity(intent);
    }

    private void handleBlockOrLeave() {
        if (isGroup) {
            confirmLeaveGroup();
            return;
        }
        toggleBlockUser();
    }

    private void confirmLeaveGroup() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvTitle = sheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = sheet.findViewById(R.id.tvDestructiveMessage);
        MaterialButton btnConfirm = sheet.findViewById(R.id.btnDestructiveConfirm);
        MaterialButton btnCancel = sheet.findViewById(R.id.btnDestructiveCancel);

        if (tvTitle != null) tvTitle.setText("Leave Group?");
        if (tvMsg != null) {
            tvMsg.setText("Are you sure you want to leave \"" + (peerUserName != null ? peerUserName : "this group") + "\"? You will no longer receive group messages.");
        }
        if (btnConfirm != null) {
            btnConfirm.setText("Leave Group");
            btnConfirm.setOnClickListener(v -> {
                sheet.dismiss();
                Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
                String myName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
                if (chat != null) {
                    Message leaveNotice = new Message(
                            UUID.randomUUID().toString(),
                            currentUserId,
                            chatId,
                            "👋 " + (myName != null ? myName : "A member") + " left the group",
                            Timestamp.now(),
                            "text"
                    );
                    leaveNotice.setChatId(chatId);
                    leaveNotice.setSenderName(myName);
                    com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(chat, null, leaveNotice);
                }
                android.content.SharedPreferences prefs = getSharedPreferences("VeilPrefs", 0);
                java.util.Set<String> delSet = new java.util.HashSet<>(
                        prefs.getStringSet("deleted_group_ids", new java.util.HashSet<>()));
                delSet.add(chatId);
                prefs.edit().putStringSet("deleted_group_ids", delSet).apply();
                com.m3.veil.manager.VeilMessagesController.getInstance(this).removeDialogFromCache(chatId);
                EncryptedChatDatabase.getInstance(this).deleteChat(chatId);
                FirebaseFirestore.getInstance().collection("groups").document(chatId)
                        .update("participants", FieldValue.arrayRemove(currentUserId));
                Toast.makeText(this, "You left the group", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }
        sheet.show();
    }

    private void loadAndRenderGroupMembers() {
        if (chatId == null || llGroupParticipants == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        List<String> participants = chat != null && chat.getParticipants() != null
                ? new ArrayList<>(chat.getParticipants()) : new ArrayList<>();

        FirebaseFirestore.getInstance().collection("groups").document(chatId).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists() && doc.get("participants") != null) {
                        @SuppressWarnings("unchecked")
                        List<String> remoteParts = (List<String>) doc.get("participants");
                        if (remoteParts != null && !remoteParts.isEmpty()) {
                            participants.clear();
                            participants.addAll(remoteParts);
                            if (chat != null) {
                                chat.setParticipants(participants);
                                EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
                            }
                        }
                    }
                    renderParticipantsView(participants);
                })
                .addOnFailureListener(e -> renderParticipantsView(participants));
    }

    private void renderParticipantsView(List<String> participants) {
        if (llGroupParticipants == null) return;
        llGroupParticipants.removeAllViews();
        Chat chat = chatId != null ? EncryptedChatDatabase.getInstance(this).getChat(chatId) : null;
        boolean isChan = (chat != null && chat.isChannel()) || (chatId != null && chatId.startsWith("channel_"));
        if (tvParticipantsHeader != null) {
            tvParticipantsHeader.setText((isChan ? "Subscribers (" : "Participants (") + participants.size() + ")");
        }
        boolean amIAdmin = chat == null || currentUserId == null || chat.isAdmin(currentUserId);
        float dp = getResources().getDisplayMetrics().density;
        for (String pId : participants) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (8 * dp), (int) (10 * dp), (int) (8 * dp), (int) (10 * dp));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(R.drawable.button_primary_selector);

            ImageView avatar = new ImageView(this);
            avatar.setLayoutParams(new LinearLayout.LayoutParams((int) (38 * dp), (int) (38 * dp)));
            avatar.setImageResource(R.drawable.ic_person);
            avatar.setBackgroundResource(R.drawable.bg_circle_icon);
            avatar.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorSurfaceVariant));
            avatar.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
            avatar.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary));
            row.addView(avatar);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            infoLp.setMarginStart((int) (14 * dp));
            info.setLayoutParams(infoLp);

            TextView tvName = new TextView(this);
            tvName.setTextSize(15f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(ContextCompat.getColor(this, R.color.colorTextPrimary));
            String label = pId.equals(currentUserId) ? "You (" + pId.substring(0, Math.min(8, pId.length())) + ")" : pId;
            tvName.setText(label);
            info.addView(tvName);

            TextView tvSub = new TextView(this);
            tvSub.setTextSize(12f);
            tvSub.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
            tvSub.setText("P2P Mesh Peer");
            info.addView(tvSub);

            row.addView(info);

            boolean isMemberAdmin = chat != null && chat.isAdmin(pId);
            boolean isCreator = chat != null && pId.equals(chat.getAdminId());
            if (isMemberAdmin) {
                TextView badge = new TextView(this);
                badge.setText(isCreator ? "👑 Owner" : "🛡️ Admin");
                badge.setTextSize(11f);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
                badge.setBackgroundResource(R.drawable.bg_filter_chip);
                badge.setPadding((int) (8 * dp), (int) (3 * dp), (int) (8 * dp), (int) (3 * dp));
                row.addView(badge);
            }

            final String[] resolvedDisplayName = {label};
            FirebaseFirestore.getInstance().collection("users").document(pId).get()
                    .addOnSuccessListener(uDoc -> {
                        if (uDoc.exists()) {
                            String name = uDoc.getString("displayName");
                            if (name == null || name.isEmpty()) name = uDoc.getString("username");
                            if (name != null) {
                                resolvedDisplayName[0] = name;
                                String finalName = pId.equals(currentUserId) ? name + " (You)" : name;
                                tvName.setText(finalName);
                                String handle = uDoc.getString("username");
                                if (handle != null) tvSub.setText("@" + handle);
                            }
                            String av = uDoc.getString("avatarBase64");
                            if (av == null || av.isEmpty()) av = uDoc.getString("avatarUrl");
                            if (av != null && !av.isEmpty()) {
                                avatar.setPadding(0, 0, 0, 0);
                                avatar.clearColorFilter();
                                com.m3.veil.utils.VeilAvatarLoader.loadAvatar(avatar, av, resolvedDisplayName[0], false);
                            }
                        }
                    });

            if (amIAdmin && !pId.equals(currentUserId)) {
                row.setOnClickListener(v -> showMemberAdminActionsSheet(pId, resolvedDisplayName[0], isMemberAdmin));
                row.setOnLongClickListener(v -> {
                    showMemberAdminActionsSheet(pId, resolvedDisplayName[0], isMemberAdmin);
                    return true;
                });
            }

            llGroupParticipants.addView(row);
        }
    }

    private void showMemberAdminActionsSheet(String targetUserId, String displayName, boolean isCurrentlyAdmin) {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (chat == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("Manage Member: " + displayName);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(title);

        MaterialButton btnToggleAdmin = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnToggleAdmin.setText(isCurrentlyAdmin ? "Dismiss as Admin" : "👑 Promote to Group Admin");
        btnToggleAdmin.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (10 * dp);
        btnToggleAdmin.setLayoutParams(lp1);
        btnToggleAdmin.setOnClickListener(v -> {
            sheet.dismiss();
            if (isCurrentlyAdmin) {
                chat.removeAdmin(targetUserId);
                FirebaseFirestore.getInstance().collection("groups").document(chatId)
                        .update("adminIds", FieldValue.arrayRemove(targetUserId));
            } else {
                chat.addAdmin(targetUserId);
                FirebaseFirestore.getInstance().collection("groups").document(chatId)
                        .update("adminIds", FieldValue.arrayUnion(targetUserId));
            }
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
            WebRtcManager.getInstance(this).sendGroupInviteToPeers(chat);

            String myName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
            String roleText = isCurrentlyAdmin
                    ? ("🛡️ " + displayName + " was dismissed as admin")
                    : ("👑 " + displayName + " was promoted to Group Admin");
            Message roleNotice = new Message(UUID.randomUUID().toString(), currentUserId, chatId, roleText, Timestamp.now(), "text");
            roleNotice.setChatId(chatId);
            roleNotice.setSenderName(myName);
            com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(chat, null, roleNotice);

            Toast.makeText(this, isCurrentlyAdmin ? displayName + " dismissed as admin" : displayName + " is now an Admin", Toast.LENGTH_SHORT).show();
            loadAndRenderGroupMembers();
        });
        root.addView(btnToggleAdmin);

        MaterialButton btnRemove = new MaterialButton(this);
        btnRemove.setText("🚫 Remove from Group");
        btnRemove.setCornerRadius((int) (12 * dp));
        btnRemove.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));
        btnRemove.setTextColor(Color.WHITE);
        btnRemove.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnRemove.setOnClickListener(v -> {
            sheet.dismiss();
            if (chat.getParticipants() != null) {
                chat.getParticipants().remove(targetUserId);
            }
            chat.removeAdmin(targetUserId);
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
            int rotatedEpoch = com.m3.veil.utils.EncryptionManager.rotateGroupSenderKeyEpoch(chatId);
            FirebaseFirestore.getInstance().collection("groups").document(chatId)
                    .update(
                            "participants", FieldValue.arrayRemove(targetUserId),
                            "adminIds", FieldValue.arrayRemove(targetUserId),
                            "groupKeyEpoch", rotatedEpoch
                    );
            WebRtcManager.getInstance(this).sendGroupInviteToPeers(chat);

            String myName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
            Message removeNotice = new Message(
                    UUID.randomUUID().toString(),
                    currentUserId,
                    chatId,
                    "🚫 " + displayName + " was removed from the group (Sender-Key rotated to Epoch #" + rotatedEpoch + ")",
                    Timestamp.now(),
                    "text"
            );
            removeNotice.setChatId(chatId);
            removeNotice.setSenderName(myName);
            com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(chat, null, removeNotice);

            Toast.makeText(this, displayName + " removed & group keys rotated", Toast.LENGTH_SHORT).show();
            loadAndRenderGroupMembers();
        });
        root.addView(btnRemove);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showAddParticipantDialog() {
        FirebaseFirestore.getInstance().collection("users").limit(30).get()
                .addOnSuccessListener(snapshots -> {
                    List<User> candidates = new ArrayList<>();
                    Chat chat = chatId != null ? EncryptedChatDatabase.getInstance(this).getChat(chatId) : null;
                    List<String> existing = chat != null && chat.getParticipants() != null ? chat.getParticipants() : new ArrayList<>();

                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        User u = doc.toObject(User.class);
                        if (u != null && u.getUserId() != null && !existing.contains(u.getUserId())) {
                            candidates.add(u);
                        }
                    }

                    if (candidates.isEmpty()) {
                        Toast.makeText(this, "No more users available to add", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    BottomSheetDialog sheet = new BottomSheetDialog(this);
                    float dp = getResources().getDisplayMetrics().density;
                    LinearLayout root = new LinearLayout(this);
                    root.setOrientation(LinearLayout.VERTICAL);
                    root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

                    TextView title = new TextView(this);
                    title.setText("Add Participant to Group");
                    title.setTextSize(18f);
                    title.setTypeface(null, Typeface.BOLD);
                    title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
                    title.setPadding(0, 0, 0, (int) (16 * dp));
                    root.addView(title);

                    for (User u : candidates) {
                        String dn = u.getDisplayName() != null ? u.getDisplayName() : u.getUsername();
                        String handle = u.getUsername() != null ? "@" + u.getUsername() : "";

                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));

                        TextView avatar = new TextView(this);
                        avatar.setText(dn != null && !dn.isEmpty() ? dn.substring(0, 1).toUpperCase() : "?");
                        avatar.setTextColor(ContextCompat.getColor(this, android.R.color.white));
                        avatar.setTextSize(15f);
                        avatar.setTypeface(null, Typeface.BOLD);
                        avatar.setGravity(Gravity.CENTER);
                        avatar.setBackgroundResource(R.drawable.bg_avatar_circle);
                        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp));
                        avLp.rightMargin = (int) (14 * dp);
                        avatar.setLayoutParams(avLp);
                        row.addView(avatar);

                        LinearLayout col = new LinearLayout(this);
                        col.setOrientation(LinearLayout.VERTICAL);

                        TextView tvName = new TextView(this);
                        tvName.setText(dn);
                        tvName.setTextSize(15.5f);
                        tvName.setTypeface(null, Typeface.BOLD);
                        tvName.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
                        col.addView(tvName);

                        if (!handle.isEmpty()) {
                            TextView tvHandle = new TextView(this);
                            tvHandle.setText(handle);
                            tvHandle.setTextSize(13f);
                            tvHandle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                            col.addView(tvHandle);
                        }
                        row.addView(col);

                        row.setOnClickListener(v -> {
                            sheet.dismiss();
                            addParticipantToGroup(u.getUserId(), u.getDisplayName());
                        });

                        root.addView(row);
                    }
                    sheet.setContentView(root);
                    sheet.show();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Failed to load users", Toast.LENGTH_SHORT).show());
    }

    private void addParticipantToGroup(String newUserId, String name) {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (chat == null) return;
        if (chat.getParticipants() == null) {
            chat.setParticipants(new ArrayList<>());
        }
        if (!chat.getParticipants().contains(newUserId)) {
            chat.getParticipants().add(newUserId);
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
            FirebaseFirestore.getInstance().collection("groups").document(chatId)
                    .update("participants", FieldValue.arrayUnion(newUserId));

            // Send full group invite & initiate WebRTC connection so new member receives the group immediately
            WebRtcManager.getInstance(this).sendGroupInviteToPeers(chat);
            WebRtcManager.getInstance(this).initiateConnection(newUserId);

            String myName = com.m3.veil.manager.VeilFeatureManager.getInstance(this).getActivePersona().getDisplayName();
            Message addNotice = new Message(
                    UUID.randomUUID().toString(),
                    currentUserId,
                    chatId,
                    "➕ " + (myName != null ? myName : "Admin") + " added " + (name != null ? name : "a new member") + " to the group",
                    Timestamp.now(),
                    "text"
            );
            addNotice.setChatId(chatId);
            addNotice.setSenderName(myName);
            com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(chat, null, addNotice);

            Toast.makeText(this, (name != null ? name : "User") + " added to group", Toast.LENGTH_SHORT).show();
            loadAndRenderGroupMembers();
        }
    }

    private void loadLocalChatMetadata() {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (chat != null) {
            currentVanishTimer = chat.getVanishTimer();
            currentVanishMode = chat.getVanishMode() != null ? chat.getVanishMode() : "after_send";
            isUserBlocked = chat.isBlocked();
        }
        updateVanishTimerUI();
        updateVanishModeUI();
        updateBlockUI();

        // Count shared media/files/polls in local SQLCipher vault
        List<Message> msgs = EncryptedChatDatabase.getInstance(this).getMessagesForChat(chatId);
        int mediaCount = 0;
        for (Message m : msgs) {
            if (!"text".equals(m.getType()) || m.isStarred()) {
                mediaCount++;
            }
        }
        android.view.View btnMedia = findViewById(R.id.btnMedia);
        if (btnMedia instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) btnMedia;
            if (vg.getChildCount() > 1 && vg.getChildAt(1) instanceof android.view.ViewGroup) {
                android.view.ViewGroup inner = (android.view.ViewGroup) vg.getChildAt(1);
                if (inner.getChildCount() > 1 && inner.getChildAt(1) instanceof TextView) {
                    ((TextView) inner.getChildAt(1)).setText(mediaCount + " encrypted items & starred notes");
                }
            }
        }
    }

    private void updateVanishTimerUI() {
        TextView vanishStatus = findViewById(R.id.vanishTimerStatus);
        if (vanishStatus == null) return;
        if (currentVanishTimer == 10000) vanishStatus.setText("10 Seconds");
        else if (currentVanishTimer == 60000) vanishStatus.setText("1 Minute");
        else if (currentVanishTimer == 3600000) vanishStatus.setText("1 Hour");
        else if (currentVanishTimer == 86400000) vanishStatus.setText("24 Hours (1 Day)");
        else if (currentVanishTimer == 604800000L) vanishStatus.setText("7 Days");
        else if (currentVanishTimer == 2592000000L) vanishStatus.setText("30 Days");
        else vanishStatus.setText("Off");
    }

    private void updateVanishModeUI() {
        TextView modeStatus = findViewById(R.id.vanishModeStatus);
        if (modeStatus != null) {
            modeStatus.setText("after_read".equals(currentVanishMode) ? "After Read (Ephemeral)" : "After Send");
        }
    }

    /**
     * WhatsApp/Signal-style 60-digit Safety Number Verification Dialog + Blinded VOPRF Token Proof.
     */
    private void showSafetyNumberDialog() {
        String safetyNumber = EncryptionManager.generateSafetyNumber(currentUserId, peerUserId != null ? peerUserId : chatId);
        String voprfToken = EncryptionManager.generateBlindedAnonymousCredential(currentUserId, chatId != null ? chatId : "p2p");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(56, 36, 56, 20);

        TextView badge = new TextView(this);
        badge.setText("🔒 E2EE WebRTC DataChannel + SQLCipher-256");
        badge.setTextSize(13f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        badge.setGravity(Gravity.CENTER);
        container.addView(badge);

        TextView codeView = new TextView(this);
        codeView.setText(safetyNumber);
        codeView.setTextSize(17f);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(24, 36, 24, 36);
        container.addView(codeView);

        TextView desc = new TextView(this);
        desc.setText("Compare these 60 digits with " + (peerUserName != null ? peerUserName : "your peer")
                + ". Keys are derived locally via HKDF-SHA256 & Labyrinth thread ratchets.\n\nVOPRF Blinded Credential:\n"
                + voprfToken.substring(0, Math.min(28, voprfToken.length())) + "...");
        desc.setTextSize(12.5f);
        desc.setGravity(Gravity.CENTER);
        container.addView(desc);

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;

        MaterialButton btnVerify = new MaterialButton(this);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = (int) (18 * dp);
        btnVerify.setLayoutParams(vLp);
        btnVerify.setText("Mark Verified (Trust 100%)");
        btnVerify.setCornerRadius((int) (12 * dp));
        btnVerify.setOnClickListener(v -> {
            if (chatId != null) {
                Chat c = EncryptedChatDatabase.getInstance(this).getChat(chatId);
                if (c != null) {
                    c.setTrustScore(100);
                    EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(c);
                }
            }
            Toast.makeText(this, "Peer identity verified (Trust Score 100%)", Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        container.addView(btnVerify);

        MaterialButton btnCopy = new MaterialButton(this);
        btnCopy.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnCopy.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        btnCopy.setElevation(0);
        btnCopy.setStrokeWidth(0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = (int) (6 * dp);
        btnCopy.setLayoutParams(cLp);
        btnCopy.setText("Copy 60 Digits");
        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("VEIL Safety Number", safetyNumber));
                Toast.makeText(this, "Safety number copied", Toast.LENGTH_SHORT).show();
            }
            sheet.dismiss();
        });
        container.addView(btnCopy);

        sheet.setContentView(container);
        sheet.show();
    }

    private void showSharedMediaGalleryDialog() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_shared_media_sheet);

        TextView chipAll = sheet.findViewById(R.id.chipMediaAll);
        TextView chipImages = sheet.findViewById(R.id.chipMediaImages);
        TextView chipVoice = sheet.findViewById(R.id.chipMediaVoice);
        TextView chipPolls = sheet.findViewById(R.id.chipMediaPolls);
        TextView chipStarred = sheet.findViewById(R.id.chipMediaStarred);
        LinearLayout itemsContainer = sheet.findViewById(R.id.sharedMediaItemsContainer);
        View emptyState = sheet.findViewById(R.id.sharedMediaEmptyState);

        List<Message> allMessages = EncryptedChatDatabase.getInstance(this).getMessagesForChat(chatId);

        final String[] currentFilter = {"all"};
        final Runnable renderList = () -> {
            if (itemsContainer == null || emptyState == null) return;
            itemsContainer.removeAllViews();
            float dp = getResources().getDisplayMetrics().density;
            int count = 0;

            for (Message m : allMessages) {
                boolean match = false;
                String tag = "📄 File";
                String snippet = m.getContent() != null ? m.getContent() : "Media item";
                int iconBg = R.drawable.bg_swatch_indigo;

                if ("image".equals(m.getType()) || "doodle".equals(m.getType()) || "video".equals(m.getType())) {
                    tag = "video".equals(m.getType()) ? "🎬 Video" : "🖼️ Image";
                    iconBg = R.drawable.bg_swatch_emerald;
                    if ("all".equals(currentFilter[0]) || "images".equals(currentFilter[0])) match = true;
                } else if ("voice".equals(m.getType())) {
                    tag = "🎤 Voice Note";
                    iconBg = R.drawable.bg_swatch_amber;
                    if ("all".equals(currentFilter[0]) || "voice".equals(currentFilter[0])) match = true;
                } else if ("poll".equals(m.getType())) {
                    tag = "📊 Community Poll";
                    iconBg = R.drawable.bg_swatch_purple;
                    if ("all".equals(currentFilter[0]) || "polls".equals(currentFilter[0])) match = true;
                } else if (m.isStarred()) {
                    tag = "⭐ Starred Message";
                    iconBg = R.drawable.bg_swatch_rose;
                    if ("all".equals(currentFilter[0]) || "starred".equals(currentFilter[0])) match = true;
                }

                if (!match) continue;
                count++;

                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
                itemRow.setClickable(true);
                itemRow.setFocusable(true);

                TextView iconView = new TextView(this);
                iconView.setLayoutParams(new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
                iconView.setGravity(Gravity.CENTER);
                iconView.setBackgroundResource(iconBg);
                iconView.setText(tag.substring(0, Math.min(2, tag.length())));
                iconView.setTextSize(18f);
                itemRow.addView(iconView);

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                colLp.leftMargin = (int) (12 * dp);
                col.setLayoutParams(colLp);

                TextView tvTag = new TextView(this);
                tvTag.setText(tag);
                tvTag.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
                tvTag.setTextSize(14.5f);
                tvTag.setTypeface(null, Typeface.BOLD);
                col.addView(tvTag);

                TextView tvSnippet = new TextView(this);
                tvSnippet.setText(snippet);
                tvSnippet.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                tvSnippet.setTextSize(12f);
                tvSnippet.setMaxLines(2);
                col.addView(tvSnippet);

                itemRow.addView(col);

                final String finalSnippet = snippet;
                itemRow.setOnClickListener(v -> {
                    String localPath = m.getLocalFilePath();
                    if (("image".equals(m.getType()) || "doodle".equals(m.getType()) || "video".equals(m.getType()))
                            && localPath != null && new java.io.File(localPath).exists()) {
                        Intent viewerIntent = new Intent(this, MediaViewerActivity.class);
                        viewerIntent.putExtra(MediaViewerActivity.EXTRA_FILE_PATH, localPath);
                        viewerIntent.putExtra(MediaViewerActivity.EXTRA_MEDIA_TYPE,
                                "video".equals(m.getType()) ? "video" : "image");
                        viewerIntent.putExtra(MediaViewerActivity.EXTRA_SENDER_NAME,
                                m.getSenderName() != null ? m.getSenderName() : (peerUserName != null ? peerUserName : "Veil Contact"));
                        viewerIntent.putExtra(MediaViewerActivity.EXTRA_CAPTION, finalSnippet);
                        viewerIntent.putExtra(MediaViewerActivity.EXTRA_MESSAGE_ID, m.getId());
                        viewerIntent.putExtra(MediaViewerActivity.EXTRA_CHAT_ID, chatId);
                        startActivity(viewerIntent);
                        sheet.dismiss();
                    } else {
                        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cb != null) {
                            cb.setPrimaryClip(android.content.ClipData.newPlainText("Veil Vault Item", finalSnippet));
                        }
                        Toast.makeText(this, "Copied item details • Returning to chat", Toast.LENGTH_SHORT).show();
                        sheet.dismiss();
                        finish();
                    }
                });

                itemsContainer.addView(itemRow);
            }

            emptyState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
            itemsContainer.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        };

        View.OnClickListener chipListener = v -> {
            if (chipAll != null) chipAll.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            if (chipImages != null) chipImages.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            if (chipVoice != null) chipVoice.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            if (chipPolls != null) chipPolls.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            if (chipStarred != null) chipStarred.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));

            ((TextView) v).setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            if (v == chipAll) currentFilter[0] = "all";
            else if (v == chipImages) currentFilter[0] = "images";
            else if (v == chipVoice) currentFilter[0] = "voice";
            else if (v == chipPolls) currentFilter[0] = "polls";
            else if (v == chipStarred) currentFilter[0] = "starred";
            renderList.run();
        };

        if (chipAll != null) chipAll.setOnClickListener(chipListener);
        if (chipImages != null) chipImages.setOnClickListener(chipListener);
        if (chipVoice != null) chipVoice.setOnClickListener(chipListener);
        if (chipPolls != null) chipPolls.setOnClickListener(chipListener);
        if (chipStarred != null) chipStarred.setOnClickListener(chipListener);

        renderList.run();
        sheet.show();
    }

    private void toggleMuteChat() {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        boolean newMuted = chat == null || !chat.isMuted();
        EncryptedChatDatabase.getInstance(this).updateChatMuteStatus(chatId, newMuted);
        Toast.makeText(this, newMuted ? "Notifications muted" : "Notifications unmuted", Toast.LENGTH_SHORT).show();
    }

    private void updateBlockUI() {
        if (tvBlockUser == null || ivBlockUser == null) return;
        if (isGroup) {
            tvBlockUser.setText("Leave Group");
            tvBlockUser.setTextColor(ContextCompat.getColor(this, R.color.colorError));
            ivBlockUser.setImageResource(R.drawable.ic_close);
            ivBlockUser.setColorFilter(ContextCompat.getColor(this, R.color.colorError));
            return;
        }
        if (isUserBlocked) {
            tvBlockUser.setText("Unblock User");
            tvBlockUser.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            ivBlockUser.setImageResource(R.drawable.ic_check);
            ivBlockUser.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary));
        } else {
            tvBlockUser.setText("Block User");
            tvBlockUser.setTextColor(ContextCompat.getColor(this, R.color.colorError));
            ivBlockUser.setImageResource(R.drawable.ic_close);
            ivBlockUser.setColorFilter(ContextCompat.getColor(this, R.color.colorError));
        }
    }

    private void toggleBlockUser() {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        isUserBlocked = !isUserBlocked;
        if (chat != null) {
            chat.setBlocked(isUserBlocked);
            chat.setBlockedBy(isUserBlocked ? currentUserId : null);
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
        }
        updateBlockUI();
        Toast.makeText(this, isUserBlocked ? "User blocked locally on P2P layer" : "User unblocked", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearChat() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvTitle = sheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = sheet.findViewById(R.id.tvDestructiveMessage);
        MaterialButton btnConfirm = sheet.findViewById(R.id.btnDestructiveConfirm);
        MaterialButton btnCancel = sheet.findViewById(R.id.btnDestructiveCancel);

        if (tvTitle != null) tvTitle.setText("Clear Encrypted Chat?");
        if (tvMsg != null) {
            tvMsg.setText("Are you sure you want to wipe all messages in this local conversation? This operation permanently shreds all SQLCipher encrypted data blocks.");
        }
        if (btnConfirm != null) {
            btnConfirm.setText("Clear & Shred");
            btnConfirm.setOnClickListener(v -> {
                sheet.dismiss();
                EncryptedChatDatabase.getInstance(this).clearChatMessages(chatId);
                Toast.makeText(this, "Chat wiped from local SQLCipher vault", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }
        sheet.show();
    }

    private void showVanishModeDialog() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_vanish_mode_sheet);

        View cardAfterRead = sheet.findViewById(R.id.cardVanishAfterRead);
        View cardAfterSend = sheet.findViewById(R.id.cardVanishAfterSend);
        RadioButton rbAfterRead = sheet.findViewById(R.id.rbVanishAfterRead);
        RadioButton rbAfterSend = sheet.findViewById(R.id.rbVanishAfterSend);

        boolean isAfterRead = "after_read".equals(currentVanishMode);
        if (rbAfterRead != null) rbAfterRead.setChecked(isAfterRead);
        if (rbAfterSend != null) rbAfterSend.setChecked(!isAfterRead);

        if (cardAfterRead != null) {
            cardAfterRead.setOnClickListener(v -> {
                currentVanishMode = "after_read";
                applyVanishModeChange("After Read (Ephemeral)");
                sheet.dismiss();
            });
        }
        if (cardAfterSend != null) {
            cardAfterSend.setOnClickListener(v -> {
                currentVanishMode = "after_send";
                applyVanishModeChange("After Send");
                sheet.dismiss();
            });
        }
        sheet.show();
    }

    private void applyVanishModeChange(String label) {
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (chat != null) {
            chat.setVanishMode(currentVanishMode);
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
        }
        if (peerUserId != null && !peerUserId.isEmpty()) {
            WebRtcManager.getInstance(this).sendChatSettingPacket(
                    peerUserId, chatId, currentVanishTimer, currentVanishMode, null, null);
        }
        updateVanishModeUI();
        Toast.makeText(this, "Vanish mode set to " + label, Toast.LENGTH_SHORT).show();
    }

    private void showVanishTimerDialog() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_vanish_timer_sheet);

        LinearLayout optionsContainer = sheet.findViewById(R.id.vanishOptionsContainer);
        if (optionsContainer == null) return;

        String[] options = {"Off", "10 Seconds", "1 Minute", "1 Hour", "24 Hours", "7 Days", "30 Days"};
        String[] descriptions = {
                "Messages persist normally in encrypted database",
                "Ultra-fast zero-trace vanishing for ephemeral secrets",
                "Quick shredding for brief confidential chats",
                "Medium-length conversation persistence",
                "Standard 24-hour daily wipe cycle",
                "Weekly rolling confidentiality",
                "Extended monthly confidentiality"
        };
        long[] times = {0, 10000, 60000, 3600000, 86400000L, 604800000L, 2592000000L};

        optionsContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        for (int i = 0; i < times.length; i++) {
            final long selectedTime = times[i];
            final String label = options[i];
            final boolean isSelected = (currentVanishTimer == selectedTime);

            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dp);
            card.setLayoutParams(lp);
            card.setRadius(12 * dp);
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1 * dp));
            card.setStrokeColor(ContextCompat.getColor(this, isSelected ? R.color.colorPrimary : R.color.colorOutline));
            card.setCardBackgroundColor(ContextCompat.getColor(this, isSelected ? R.color.surfaceVariant : android.R.color.transparent));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textCol.setLayoutParams(tcLp);

            TextView tvOpt = new TextView(this);
            tvOpt.setText(label);
            tvOpt.setTextColor(ContextCompat.getColor(this, isSelected ? R.color.colorPrimary : R.color.textPrimary));
            tvOpt.setTextSize(15.5f);
            tvOpt.setTypeface(null, Typeface.BOLD);
            textCol.addView(tvOpt);

            TextView tvDesc = new TextView(this);
            tvDesc.setText(descriptions[i]);
            tvDesc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            tvDesc.setTextSize(12f);
            textCol.addView(tvDesc);
            row.addView(textCol);

            RadioButton rb = new RadioButton(this);
            rb.setChecked(isSelected);
            rb.setClickable(false);
            rb.setFocusable(false);
            row.addView(rb);

            card.addView(row);
            card.setOnClickListener(v -> {
                sheet.dismiss();
                currentVanishTimer = selectedTime;
                Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
                if (chat != null) {
                    chat.setVanishTimer(currentVanishTimer);
                    EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
                }
                if (peerUserId != null && !peerUserId.isEmpty()) {
                    WebRtcManager.getInstance(this).sendChatSettingPacket(
                            peerUserId, chatId, currentVanishTimer, currentVanishMode, null, null);
                }
                updateVanishTimerUI();
                Toast.makeText(this, "Vanish schedule set to " + label, Toast.LENGTH_SHORT).show();
            });

            optionsContainer.addView(card);
        }

        sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // GROUP ENHANCED FEATURES (#68, #71, #73, #74, #225)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void setupGroupActionOptions() {
        LinearLayout membersLayout = findViewById(R.id.layoutGroupMembers);
        if (membersLayout == null) return;
        ViewGroup parent = (ViewGroup) membersLayout.getParent();
        if (parent == null) return;
        int insertIdx = parent.indexOfChild(membersLayout);

        parent.addView(createProfileOptionRow("✏️ Edit Group / Channel Info", "Change title, description, or public visibility", R.drawable.ic_edit, v -> {
            showEditGroupInfoDialog();
        }), insertIdx);

        parent.addView(createProfileOptionRow("🛡️ Group Permissions & Slow Mode", "Configure who can post, add members, and message cooldown", R.drawable.ic_shield, v -> {
            showGroupPermissionsAndSlowModeDialog();
        }), insertIdx + 1);

        parent.addView(createProfileOptionRow("🔗 Invite Link & QR Code", "Share veil://join/" + (chatId != null ? chatId : "group") + " or revoke link", R.drawable.ic_share, v -> {
            showGroupInviteLinkSheet();
        }), insertIdx + 2);

        parent.addView(createProfileOptionRow("📅 Group Events & Calendar (#68)", "RSVP, countdowns, and synchronized mesh events", R.drawable.ic_timer, v -> {
            EventsCalendarBottomSheet sheet = EventsCalendarBottomSheet.newInstance(chatId, peerUserName != null ? peerUserName : "Group");
            sheet.show(getSupportFragmentManager(), "EventsCalendarBottomSheet");
        }), insertIdx + 3);

        parent.addView(createProfileOptionRow("📁 Secret Sub-Groups & Topics (#71)", "Manage and join encrypted topic channels", R.drawable.ic_chat, v -> {
            showSubgroupsDialog();
        }), insertIdx + 4);

        parent.addView(createProfileOptionRow("🎨 Group Themes & Wallpaper (#73)", "Customize theme and broadcast to group mesh", R.drawable.ic_appearance, v -> {
            showGroupThemesDialog();
        }), insertIdx + 5);

        parent.addView(createProfileOptionRow("🏆 Group Achievements (#74)", "View cryptographic member reputation badges", R.drawable.ic_shield, v -> {
            showGroupAchievementsDialog();
        }), insertIdx + 6);

        parent.addView(createProfileOptionRow("⚙️ Advanced Group & Channel Console", "Full administrative dashboard, slow mode, and member audit", R.drawable.ic_settings, v -> {
            Intent intent = new Intent(this, GroupSettingsActivity.class);
            intent.putExtra("chatId", chatId);
            intent.putExtra("groupName", peerUserName);
            startActivity(intent);
        }), insertIdx + 7);
    }

    private void setupDirectChatAdvancedOptions() {
        LinearLayout membersLayout = findViewById(R.id.layoutGroupMembers);
        if (membersLayout == null) return;
        ViewGroup parent = (ViewGroup) membersLayout.getParent();
        if (parent == null) return;
        int insertIdx = parent.indexOfChild(membersLayout);

        parent.addView(createProfileOptionRow("🔐 Per-Chat Vault PIN Lock", "Protect this conversation with an isolated 4-digit PIN", R.drawable.ic_lock, v -> {
            showPerChatPinLockDialog();
        }), insertIdx);

        parent.addView(createProfileOptionRow("🎨 Custom Chat Theme & Wallpaper", "Personalize background colors & sync chat theme", R.drawable.ic_appearance, v -> {
            showGroupThemesDialog();
        }), insertIdx + 1);

        parent.addView(createProfileOptionRow("⚙️ Advanced Chat & Privacy Settings", "Read receipts, screenshot shield, auto-translate & export", R.drawable.ic_settings, v -> {
            Intent intent = new Intent(this, ChatSettingsActivity.class);
            intent.putExtra("chatId", chatId);
            intent.putExtra("userId", peerUserId);
            intent.putExtra("userName", peerUserName);
            startActivity(intent);
        }), insertIdx + 2);
    }

    private void showPerChatPinLockDialog() {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        boolean currentlyLocked = chat != null && chat.getChatLockPin() != null && !chat.getChatLockPin().isEmpty();

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(currentlyLocked ? "🔓 Manage Per-Chat PIN Lock" : "🔐 Lock Chat with 4-Digit PIN");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(title);

        EditText etPin = new EditText(this);
        etPin.setHint("Enter 4-digit PIN...");
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        etPin.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etPin.setBackgroundResource(R.drawable.bg_search_input);
        int boxPad = (int) (12 * dp);
        etPin.setPadding(boxPad, boxPad, boxPad, boxPad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * dp);
        lp.bottomMargin = (int) (12 * dp);
        etPin.setLayoutParams(lp);
        root.addView(etPin);

        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText(currentlyLocked ? "Update PIN Lock" : "Enable PIN Lock");
        btnSave.setCornerRadius((int) (12 * dp));
        btnSave.setOnClickListener(v -> {
            String pin = etPin.getText().toString().trim();
            if (pin.length() < 4) {
                etPin.setError("Enter at least 4 digits");
                return;
            }
            if (chat != null) {
                chat.setChatLockPin(pin);
                EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
            }
            sheet.dismiss();
            Toast.makeText(this, "🔐 Chat locked with custom PIN", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);

        if (currentlyLocked) {
            MaterialButton btnUnlock = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnUnlock.setText("Remove PIN Lock");
            btnUnlock.setCornerRadius((int) (12 * dp));
            btnUnlock.setOnClickListener(v -> {
                if (chat != null) {
                    chat.setChatLockPin(null);
                    EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
                }
                sheet.dismiss();
                Toast.makeText(this, "🔓 Per-chat PIN lock removed", Toast.LENGTH_SHORT).show();
            });
            root.addView(btnUnlock);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void showGroupPermissionsAndSlowModeDialog() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🛡️ Group Permissions & Slow Mode");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Control member capabilities and rate-limiting across the group mesh");
        sub.setTextSize(12.5f);
        sub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        sub.setPadding(0, (int) (4 * dp), 0, (int) (14 * dp));
        root.addView(sub);

        android.content.SharedPreferences prefs = getSharedPreferences("VeilGroupPrefs", MODE_PRIVATE);
        boolean adminsOnlySend = prefs.getBoolean("admins_only_send_" + chatId, false);
        boolean membersCanInvite = prefs.getBoolean("members_can_invite_" + chatId, true);
        boolean membersCanPin = prefs.getBoolean("members_can_pin_" + chatId, true);
        int slowModeSec = prefs.getInt("slow_mode_sec_" + chatId, 0);

        android.widget.CheckBox cbAdminsOnly = new android.widget.CheckBox(this);
        cbAdminsOnly.setText("🔒 Only Admins Can Send Messages");
        cbAdminsOnly.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        cbAdminsOnly.setChecked(adminsOnlySend);
        root.addView(cbAdminsOnly);

        android.widget.CheckBox cbInvite = new android.widget.CheckBox(this);
        cbInvite.setText("👥 Allow Members to Add New Participants");
        cbInvite.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        cbInvite.setChecked(membersCanInvite);
        root.addView(cbInvite);

        android.widget.CheckBox cbPin = new android.widget.CheckBox(this);
        cbPin.setText("📌 Allow Members to Pin Messages & Create Polls");
        cbPin.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        cbPin.setChecked(membersCanPin);
        root.addView(cbPin);

        TextView tvSlowHeader = new TextView(this);
        tvSlowHeader.setText("⏱️ Slow Mode Cooldown");
        tvSlowHeader.setTextSize(14.5f);
        tvSlowHeader.setTypeface(null, Typeface.BOLD);
        tvSlowHeader.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        tvSlowHeader.setPadding(0, (int) (12 * dp), 0, (int) (6 * dp));
        root.addView(tvSlowHeader);

        final int[] selectedSlow = {slowModeSec};
        String[] slowLabels = {"Off", "10s", "30s", "1m", "5m", "15m"};
        int[] slowValues = {0, 10, 30, 60, 300, 900};

        LinearLayout slowStrip = new LinearLayout(this);
        slowStrip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < slowLabels.length; i++) {
            final int val = slowValues[i];
            TextView chip = new TextView(this);
            chip.setText(slowLabels[i]);
            chip.setTextSize(12.5f);
            chip.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
            boolean sel = (val == selectedSlow[0]);
            chip.setBackgroundResource(sel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(sel ? Color.WHITE : ContextCompat.getColor(this, R.color.textPrimary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                selectedSlow[0] = val;
                for (int j = 0; j < slowStrip.getChildCount(); j++) {
                    TextView c = (TextView) slowStrip.getChildAt(j);
                    boolean isSel = (slowValues[j] == val);
                    c.setBackgroundResource(isSel ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
                    c.setTextColor(isSel ? Color.WHITE : ContextCompat.getColor(this, R.color.textPrimary));
                }
            });
            slowStrip.addView(chip);
        }
        root.addView(slowStrip);

        MaterialButton btnSavePerms = new MaterialButton(this);
        btnSavePerms.setText("Save Group Permissions");
        btnSavePerms.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (16 * dp);
        btnSavePerms.setLayoutParams(btnLp);
        btnSavePerms.setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean("admins_only_send_" + chatId, cbAdminsOnly.isChecked())
                    .putBoolean("members_can_invite_" + chatId, cbInvite.isChecked())
                    .putBoolean("members_can_pin_" + chatId, cbPin.isChecked())
                    .putInt("slow_mode_sec_" + chatId, selectedSlow[0])
                    .apply();
            java.util.Map<String, Object> updates = new java.util.HashMap<>();
            updates.put("adminsOnlySend", cbAdminsOnly.isChecked());
            updates.put("slowModeSec", selectedSlow[0]);
            FirebaseFirestore.getInstance().collection("groups").document(chatId).update(updates);
            sheet.dismiss();
            Toast.makeText(this, "🛡️ Group permissions & Slow Mode updated", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSavePerms);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showGroupInviteLinkSheet() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🔗 Group Invite Link");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(title);

        android.content.SharedPreferences prefs = getSharedPreferences("VeilGroupPrefs", MODE_PRIVATE);
        String token = prefs.getString("invite_token_" + chatId, chatId);
        final String[] currentLink = {"veil://join/" + token};

        TextView tvLinkBox = new TextView(this);
        tvLinkBox.setText(currentLink[0]);
        tvLinkBox.setTextSize(14f);
        tvLinkBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvLinkBox.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
        tvLinkBox.setBackgroundResource(R.drawable.bg_search_input);
        int boxPad = (int) (14 * dp);
        tvLinkBox.setPadding(boxPad, boxPad, boxPad, boxPad);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = (int) (12 * dp);
        boxLp.bottomMargin = (int) (14 * dp);
        tvLinkBox.setLayoutParams(boxLp);
        root.addView(tvLinkBox);

        MaterialButton btnCopy = new MaterialButton(this);
        btnCopy.setText("📋 Copy Invite Link");
        btnCopy.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (8 * dp);
        btnCopy.setLayoutParams(lp1);
        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Veil Group Invite", currentLink[0]));
            }
            Toast.makeText(this, "Invite link copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnCopy);

        MaterialButton btnShare = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnShare.setText("📤 Share Invite Link");
        btnShare.setCornerRadius((int) (12 * dp));
        btnShare.setLayoutParams(lp1);
        btnShare.setOnClickListener(v -> {
            sheet.dismiss();
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_TEXT, "Join \"" + (peerUserName != null ? peerUserName : "Encrypted Group") + "\" on VEIL P2P Mesh: " + currentLink[0]);
            startActivity(Intent.createChooser(sendIntent, "Share Group Invite Link"));
        });
        root.addView(btnShare);

        MaterialButton btnRevoke = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnRevoke.setText("🔄 Revoke & Generate New Link");
        btnRevoke.setCornerRadius((int) (12 * dp));
        btnRevoke.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnRevoke.setOnClickListener(v -> {
            String newTok = chatId + "_" + UUID.randomUUID().toString().substring(0, 6);
            prefs.edit().putString("invite_token_" + chatId, newTok).apply();
            currentLink[0] = "veil://join/" + newTok;
            tvLinkBox.setText(currentLink[0]);
            Toast.makeText(this, "Previous invite link revoked; new link generated", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnRevoke);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showEditGroupInfoDialog() {
        if (chatId == null) return;
        Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (chat == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText(chat.isChannel() ? "✏️ Edit Broadcast Channel" : "✏️ Edit Group Info");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (12 * dp));
        root.addView(title);

        EditText etName = new EditText(this);
        etName.setText(chat.getName() != null ? chat.getName() : "");
        etName.setHint("Group / Channel Name");
        etName.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        etName.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etName.setBackgroundResource(R.drawable.bg_search_input);
        int pad = (int) (12 * dp);
        etName.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (12 * dp);
        etName.setLayoutParams(lp1);
        root.addView(etName);

        EditText etDesc = new EditText(this);
        etDesc.setText(chat.getDescription() != null ? chat.getDescription() : "");
        etDesc.setHint("Description / Bio (optional)");
        etDesc.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        etDesc.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etDesc.setBackgroundResource(R.drawable.bg_search_input);
        etDesc.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.bottomMargin = (int) (12 * dp);
        etDesc.setLayoutParams(lp2);
        root.addView(etDesc);

        android.widget.CheckBox cbPublic = new android.widget.CheckBox(this);
        cbPublic.setText("🌐 List in Public Groups & Channels Directory");
        cbPublic.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        cbPublic.setChecked(chat.isPublic());
        root.addView(cbPublic);

        MaterialButton btnSave = new MaterialButton(this);
        btnSave.setText("Save & Sync to All Members");
        btnSave.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (14 * dp);
        btnSave.setLayoutParams(btnLp);
        btnSave.setOnClickListener(v -> {
            String newName = etName.getText().toString().trim();
            String newDesc = etDesc.getText().toString().trim();
            if (newName.isEmpty()) {
                etName.setError("Name cannot be empty");
                return;
            }
            sheet.dismiss();
            chat.setName(newName);
            chat.setDescription(newDesc);
            chat.setPublic(cbPublic.isChecked());
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);

            peerUserName = newName;
            TextView nameText = findViewById(R.id.profileNameText);
            if (nameText != null) nameText.setText(newName);
            TextView subText = findViewById(R.id.profileSubtitleText);
            if (subText != null && !newDesc.isEmpty()) subText.setText(newDesc);

            java.util.Map<String, Object> updates = new java.util.HashMap<>();
            updates.put("groupName", newName);
            updates.put("description", newDesc);
            updates.put("isPublic", cbPublic.isChecked());
            FirebaseFirestore.getInstance().collection("groups").document(chatId).update(updates);

            WebRtcManager.getInstance(this).sendGroupInviteToPeers(chat);
            Toast.makeText(this, "Updated & synced across all members", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);

        sheet.setContentView(root);
        sheet.show();
    }

    private View createProfileOptionRow(String title, String subtitle, int iconRes, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (16 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (16 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.button_primary_selector);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView iv = new ImageView(this);
        int iconDim = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(iconDim, iconDim);
        ivLp.rightMargin = (int) (16 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(ivLp);
        iv.setImageResource(iconRes);
        iv.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary));
        row.addView(iv);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(15.5f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(12.5f);
        textCol.addView(tvSub);

        row.addView(textCol);
        row.setOnClickListener(onClick);
        return row;
    }

    private void showSubgroupsDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📁 Secret Sub-Groups & Topics (#71)");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("End-to-end encrypted topic threads inside this group");
        tvSubtitle.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSubtitle.setTextSize(13);
        tvSubtitle.setPadding(0, 4, 0, (int) (14 * getResources().getDisplayMetrics().density));
        root.addView(tvSubtitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        List<EncryptedChatDatabase.GroupSubgroup> subgroups =
                EncryptedChatDatabase.getInstance(this).getSubgroupsForGroup(chatId);

        if (subgroups.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("No sub-groups created yet.");
            emptyTv.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            emptyTv.setTextSize(13.5f);
            listContainer.addView(emptyTv);
        } else {
            for (EncryptedChatDatabase.GroupSubgroup sg : subgroups) {
                com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
                card.setRadius(24f);
                card.setCardElevation(0f);
                card.setStrokeWidth(2);
                card.setStrokeColor(Color.parseColor("#263238"));
                card.setCardBackgroundColor(Color.parseColor("#1A202C"));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
                card.setLayoutParams(cardLp);

                LinearLayout itemLayout = new LinearLayout(this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setPadding((int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (12 * getResources().getDisplayMetrics().density));

                TextView tvSgTitle = new TextView(this);
                String icon = sg.isSecret ? "🔒 #" : "💬 #";
                tvSgTitle.setText(icon + sg.title);
                tvSgTitle.setTextColor(Color.WHITE);
                tvSgTitle.setTextSize(15.5f);
                tvSgTitle.setTypeface(null, Typeface.BOLD);
                itemLayout.addView(tvSgTitle);

                if (sg.description != null && !sg.description.isEmpty()) {
                    TextView tvSgDesc = new TextView(this);
                    tvSgDesc.setText(sg.description);
                    tvSgDesc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                    tvSgDesc.setTextSize(13);
                    tvSgDesc.setPadding(0, 4, 0, 0);
                    itemLayout.addView(tvSgDesc);
                }

                card.addView(itemLayout);
                card.setOnClickListener(v -> {
                    sheet.dismiss();
                    String subChatId = chatId + "_topic_" + sg.subgroupId;
                    Chat existingTopic = EncryptedChatDatabase.getInstance(this).getChat(subChatId);
                    if (existingTopic == null) {
                        Chat parentChat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
                        List<String> parts = parentChat != null && parentChat.getParticipants() != null
                                ? new ArrayList<>(parentChat.getParticipants())
                                : new ArrayList<>( java.util.Collections.singletonList(currentUserId));
                        Chat topicChat = new Chat(
                                subChatId,
                                "#" + sg.title + " (" + (peerUserName != null ? peerUserName : "Group") + ")",
                                sg.description != null ? sg.description : "Encrypted topic thread",
                                com.google.firebase.Timestamp.now(),
                                null,
                                0,
                                true,
                                false,
                                "group",
                                parts
                        );
                        EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(topicChat);
                    }
                    Intent topicIntent = new Intent(this, ChatActivity.class);
                    topicIntent.putExtra("chatId", subChatId);
                    topicIntent.putExtra("isGroup", true);
                    topicIntent.putExtra("recipientName", "#" + sg.title);
                    startActivity(topicIntent);
                });
                listContainer.addView(card);
            }
        }

        scroll.addView(listContainer);
        root.addView(scroll);

        MaterialButton btnCreateTopic = new MaterialButton(this);
        btnCreateTopic.setText("➕ Create New Topic / Sub-Group");
        btnCreateTopic.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        btnCreateTopic.setLayoutParams(btnLp);
        btnCreateTopic.setOnClickListener(v -> {
            sheet.dismiss();
            showCreateSubgroupDialog();
        });
        root.addView(btnCreateTopic);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showCreateSubgroupDialog() {
        if (chatId == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("➕ Create Group Topic / Sub-Group");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        EditText etTitle = new EditText(this);
        etTitle.setHint("Topic title (e.g. general, announcements, dev)...");
        etTitle.setTextColor(Color.WHITE);
        etTitle.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        root.addView(etTitle);

        EditText etDesc = new EditText(this);
        etDesc.setHint("Topic description...");
        etDesc.setTextColor(Color.WHITE);
        etDesc.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        root.addView(etDesc);

        android.widget.CheckBox cbSecret = new android.widget.CheckBox(this);
        cbSecret.setText("🔒 Secret E2EE Sub-Group");
        cbSecret.setTextColor(Color.WHITE);
        cbSecret.setChecked(true);
        root.addView(cbSecret);

        MaterialButton btnCreate = new MaterialButton(this);
        btnCreate.setText("Create Topic");
        btnCreate.setCornerRadius((int) (12 * dp));
        btnCreate.setOnClickListener(v -> {
            String t = etTitle.getText().toString().trim();
            if (t.isEmpty()) {
                etTitle.setError("Required");
                return;
            }
            String d = etDesc.getText().toString().trim();
            String sgId = "sg_" + UUID.randomUUID().toString().substring(0, 8);
            EncryptedChatDatabase.GroupSubgroup newSg = new EncryptedChatDatabase.GroupSubgroup(
                    sgId, chatId, t, d, null, "MEMBER", cbSecret.isChecked()
            );
            EncryptedChatDatabase.getInstance(this).saveSubgroup(newSg);
            sheet.dismiss();
            Toast.makeText(this, "📁 Topic #" + t + " created!", Toast.LENGTH_SHORT).show();
            showSubgroupsDialog();
        });
        root.addView(btnCreate);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showGroupThemesDialog() {
        String[] names = {
                "Default Night (#121212)",
                "Midnight Abyss (#0D1117)",
                "Cyber Indigo (#121026)",
                "Emerald Matrix (#091E1A)",
                "Crimson Velvet (#240B15)",
                "Royal Gold (#1F1905)",
                "Solar Amber (#2A1B0A)",
                "Deep Ocean (#081D2E)"
        };
        String[] hexes = {
                "#121212",
                "#0D1117",
                "#121026",
                "#091E1A",
                "#240B15",
                "#1F1905",
                "#2A1B0A",
                "#081D2E"
        };

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🎨 Group Themes & Wallpaper (#73)");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Select a theme to synchronize across all peers in this group");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, 4, 0, (int) (16 * getResources().getDisplayMetrics().density));
        root.addView(tvSub);

        for (int i = 0; i < names.length; i++) {
            final String hex = hexes[i];
            final String name = names[i];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, (int) (10 * getResources().getDisplayMetrics().density));

            View swatch = new View(this);
            int sDim = (int) (24 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(sDim, sDim);
            swatchLp.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
            swatch.setLayoutParams(swatchLp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(Color.parseColor(hex));
            gd.setStroke(2, Color.WHITE);
            swatch.setBackground(gd);
            row.addView(swatch);

            TextView tvName = new TextView(this);
            tvName.setText(name);
            tvName.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            tvName.setTextSize(15.5f);
            row.addView(tvName);

            row.setOnClickListener(v -> {
                sheet.dismiss();
                EncryptedChatDatabase.getInstance(this).updateChatWallpaper(chatId, hex);
                try {
                    org.json.JSONObject payload = new org.json.JSONObject();
                    payload.put("action", "group_theme_sync");
                    payload.put("groupId", chatId);
                    payload.put("themeHex", hex);
                    payload.put("themeName", name);
                    com.m3.veil.webrtc.VeilPacket packet = new com.m3.veil.webrtc.VeilPacket(
                            com.m3.veil.webrtc.VeilPacket.LANE_CTRL,
                            com.m3.veil.webrtc.VeilPacket.TYPE_GROUP_SETTINGS,
                            payload.toString()
                    );
                    WebRtcManager.getInstance(this).broadcastPacket(packet);
                } catch (Throwable ignored) {}
                Toast.makeText(this, "Group theme updated & synced across mesh!", Toast.LENGTH_SHORT).show();
            });
            root.addView(row);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private void showGroupAchievementsDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int padH = (int) (20 * getResources().getDisplayMetrics().density);
        int padV = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padH, padV, padH, padV * 2);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🏆 Group Member Achievements (#74)");
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Cryptographic badges unlocked via active participation in this group");
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvSub.setTextSize(13);
        tvSub.setPadding(0, 4, 0, (int) (14 * getResources().getDisplayMetrics().density));
        root.addView(tvSub);

        int eventCount = EncryptedChatDatabase.getInstance(this).getEventsForGroup(chatId).size();
        int subgroupCount = EncryptedChatDatabase.getInstance(this).getSubgroupsForGroup(chatId).size();

        String[][] badges = {
                {"👑 Founding Architect", "Created or joined as one of the inaugural peers", "true"},
                {"⚡ Mesh Relay Master", "Exchanged encrypted P2P frames across group", "true"},
                {"🛡️ Cryptographic Guardian", "E2EE session verified with forward secrecy", "true"},
                {"🎙️ Voice Maverick", "Participated in live voice room or call", "true"},
                {"📅 Master Event Host", "Created or joined group events", String.valueOf(eventCount > 0)},
                {"📁 Topic Pioneer", "Created or unlocked group sub-topics", String.valueOf(subgroupCount > 0)}
        };

        ScrollView scroll = new ScrollView(this);
        LinearLayout badgeList = new LinearLayout(this);
        badgeList.setOrientation(LinearLayout.VERTICAL);

        for (String[] b : badges) {
            String bTitle = b[0];
            String bDesc = b[1];
            boolean unlocked = Boolean.parseBoolean(b[2]);

            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
            card.setRadius(24f);
            card.setCardElevation(0f);
            card.setStrokeWidth(2);
            card.setStrokeColor(unlocked ? Color.parseColor("#FFD700") : Color.parseColor("#333333"));
            card.setCardBackgroundColor(unlocked ? Color.parseColor("#1C1E24") : Color.parseColor("#14161B"));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            card.setLayoutParams(cardLp);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding((int) (14 * getResources().getDisplayMetrics().density), (int) (10 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density), (int) (10 * getResources().getDisplayMetrics().density));

            TextView tvBTitle = new TextView(this);
            tvBTitle.setText(bTitle + (unlocked ? "  [UNLOCKED ✅]" : "  [LOCKED 🔒]"));
            tvBTitle.setTextColor(unlocked ? Color.parseColor("#FFD700") : Color.GRAY);
            tvBTitle.setTextSize(15);
            tvBTitle.setTypeface(null, Typeface.BOLD);
            item.addView(tvBTitle);

            TextView tvBDesc = new TextView(this);
            tvBDesc.setText(bDesc);
            tvBDesc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            tvBDesc.setTextSize(12.5f);
            item.addView(tvBDesc);

            card.addView(item);
            badgeList.addView(card);
        }

        scroll.addView(badgeList);
        root.addView(scroll);
        sheet.setContentView(root);
        sheet.show();
    }
}
