package com.m3.veil.ui.chat;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.m3.veil.model.Timestamp;
import com.m3.veil.bridge.firestore.DocumentSnapshot;
import com.m3.veil.bridge.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.User;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.webrtc.WebRtcManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class NewGroupActivity extends AppCompatActivity {

    private EditText etGroupName;
    private EditText searchEditText;
    private TextView tvSubtitle;
    private RecyclerView usersRecyclerView;
    private ProgressBar loadingBar;
    private View emptyState;
    private FloatingActionButton fabCreateGroup;

    private FirebaseFirestore db;
    private String currentUserId;
    private String currentUsername = "You";

    private final List<User> allUsers = new ArrayList<>();
    private final List<User> displayedUsers = new ArrayList<>();
    private final Set<String> selectedUserIds = new HashSet<>();
    private GroupUserSelectionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_new_group);

        handleWindowInsets();

        db = FirebaseFirestore.getInstance();
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "local_user";
        }

        initializeViews();
        loadCurrentUserDetails();
        loadUsers();
    }

    private void handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.new_group_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void initializeViews() {
        etGroupName = findViewById(R.id.etGroupName);
        searchEditText = findViewById(R.id.searchEditText);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        usersRecyclerView = findViewById(R.id.usersRecyclerView);
        loadingBar = findViewById(R.id.loadingBar);
        emptyState = findViewById(R.id.emptyState);
        fabCreateGroup = findViewById(R.id.fabCreateGroup);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        boolean isChannelMode = getIntent().getBooleanExtra("isChannel", false);
        com.google.android.material.textfield.TextInputLayout groupNameInputLayout = findViewById(R.id.groupNameInputLayout);
        if (isChannelMode) {
            if (groupNameInputLayout != null) {
                groupNameInputLayout.setHint("Channel name...");
            }
            if (tvSubtitle != null) {
                tvSubtitle.setText("Add initial subscribers (optional)");
            }
        }

        adapter = new GroupUserSelectionAdapter(displayedUsers, selectedUserIds, user -> {
            if (selectedUserIds.contains(user.getUserId())) {
                selectedUserIds.remove(user.getUserId());
            } else {
                selectedUserIds.add(user.getUserId());
            }
            adapter.notifyDataSetChanged();
            updateSubtitle();
        });

        usersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        usersRecyclerView.setAdapter(adapter);

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterUsers(s.toString().trim());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        fabCreateGroup.setOnClickListener(v -> createGroup());
        UiAnimator.attachSpringPress(fabCreateGroup);
    }

    private void updateSubtitle() {
        int count = selectedUserIds.size();
        if (count == 0) {
            tvSubtitle.setText("Add participants");
        } else {
            tvSubtitle.setText(count + (count == 1 ? " participant selected" : " participants selected"));
        }
    }

    private void loadCurrentUserDetails() {
        db.collection("users").document(currentUserId).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String name = doc.getString("displayName");
                        if (name == null || name.isEmpty()) name = doc.getString("username");
                        if (name != null && !name.isEmpty()) currentUsername = name;
                    }
                });
    }

    private void loadUsers() {
        loadingBar.setVisibility(View.VISIBLE);
        db.collection("users").limit(50).get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    loadingBar.setVisibility(View.GONE);
                    allUsers.clear();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        User user = doc.toObject(User.class);
                        if (user != null) {
                            if (user.getUserId() == null || user.getUserId().isEmpty()) {
                                user.setUserId(doc.getId());
                            }
                            if (user.getUsername() == null || user.getUsername().isEmpty()) {
                                user.setUsername("anon_" + doc.getId().substring(0, Math.min(6, doc.getId().length())).toLowerCase());
                            }
                            if (!user.getUserId().equals(currentUserId)) {
                                allUsers.add(user);
                            }
                        }
                    }
                    filterUsers(searchEditText.getText().toString().trim());
                })
                .addOnFailureListener(e -> {
                    loadingBar.setVisibility(View.GONE);
                    updateEmptyState();
                });
    }

    private void filterUsers(String query) {
        displayedUsers.clear();
        String q = query.toLowerCase();
        for (User u : allUsers) {
            String name = u.getDisplayName() != null ? u.getDisplayName().toLowerCase() : "";
            String username = u.getUsername() != null ? u.getUsername().toLowerCase() : "";
            if (name.contains(q) || username.contains(q)) {
                displayedUsers.add(u);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (displayedUsers.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            usersRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            usersRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void createGroup() {
        String groupName = etGroupName.getText().toString().trim();
        if (groupName.isEmpty()) {
            etGroupName.setError("Group name is required");
            etGroupName.requestFocus();
            return;
        }

        boolean isChannelMode = getIntent().getBooleanExtra("isChannel", false);
        if (!isChannelMode && selectedUserIds.isEmpty()) {
            Toast.makeText(this, "Select at least 1 participant", Toast.LENGTH_SHORT).show();
            return;
        }

        loadingBar.setVisibility(View.VISIBLE);
        fabCreateGroup.setEnabled(false);

        String prefix = isChannelMode ? "channel_" : "group_";
        String groupId = prefix + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);
        List<String> participants = new ArrayList<>(selectedUserIds);
        if (!participants.contains(currentUserId)) {
            participants.add(currentUserId);
        }

        String chatType = isChannelMode ? "channel" : "group";
        String initialSummary = isChannelMode
                ? "📢 Broadcast Channel created by " + currentUsername
                : "🛡️ " + currentUsername + " created encrypted group \"" + groupName + "\"";

        Chat groupChat = new Chat(
                groupId,
                groupName,
                initialSummary,
                Timestamp.now(),
                null,
                0,
                true,
                true,
                chatType,
                participants
        );
        groupChat.setAdminId(currentUserId);
        groupChat.addAdmin(currentUserId);

        // 1. Save to local SQLCipher Encrypted Vault & Controller cache
        EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(groupChat);

        // 1b. Create default general discussion sub-group topic (#71)
        EncryptedChatDatabase.GroupSubgroup defaultTopic = new EncryptedChatDatabase.GroupSubgroup(
                groupId + "_general",
                groupId,
                "General Discussion",
                "Default topic channel for " + groupName,
                null,
                "MEMBER",
                false
        );
        EncryptedChatDatabase.getInstance(this).saveSubgroup(defaultTopic);

        // 2. Initial system notice message
        String welcomeMsgId = UUID.randomUUID().toString();
        Message welcomeMsg = new Message(welcomeMsgId, currentUserId, groupId,
                initialSummary,
                Timestamp.now(), "text");
        welcomeMsg.setChatId(groupId);
        welcomeMsg.setSenderName(currentUsername);

        // 3. Register in Firestore for cross-device sync & offline peer discovery
        java.util.Map<String, Object> groupDoc = new java.util.HashMap<>();
        groupDoc.put("groupId", groupId);
        groupDoc.put("groupName", groupName);
        groupDoc.put("type", chatType);
        groupDoc.put("adminId", currentUserId);
        groupDoc.put("adminIds", groupChat.getAdminIds());
        groupDoc.put("creatorId", currentUserId);
        groupDoc.put("creatorName", currentUsername);
        groupDoc.put("participants", participants);
        groupDoc.put("lastMessage", "🔒 " + (isChannelMode ? "Broadcast Channel created" : "P2P Encrypted Group created"));
        groupDoc.put("lastMessageSenderId", currentUserId);
        groupDoc.put("lastMessageSenderName", currentUsername);
        groupDoc.put("timestamp", Timestamp.now());
        groupDoc.put("isGroup", true);
        db.collection("groups").document(groupId).set(groupDoc);

        // 4. Fan-out P2P group invite AND welcome message to all participants under groupId
        WebRtcManager.getInstance(this).sendGroupInviteToPeers(groupChat);
        for (String peerId : selectedUserIds) {
            WebRtcManager.getInstance(this).initiateConnection(peerId);
        }
        com.m3.veil.manager.VeilMessagesController.getInstance(this).sendMessage(groupChat, null, welcomeMsg);

        Toast.makeText(this, isChannelMode ? "Broadcast Channel created!" : "P2P Encrypted Group created!", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", groupId);
        intent.putExtra("recipientName", groupName);
        intent.putExtra("isGroup", true);
        if (!selectedUserIds.isEmpty()) {
            intent.putExtra("recipientId", selectedUserIds.iterator().next());
        }
        startActivity(intent);
        finish();
    }

    private static class GroupUserSelectionAdapter extends RecyclerView.Adapter<GroupUserSelectionAdapter.ViewHolder> {

        interface OnItemClickListener {
            void onItemClick(User user);
        }

        private final List<User> users;
        private final Set<String> selectedIds;
        private final OnItemClickListener listener;

        GroupUserSelectionAdapter(List<User> users, Set<String> selectedIds, OnItemClickListener listener) {
            this.users = users;
            this.selectedIds = selectedIds;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group_select_user, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            User user = users.get(position);
            holder.bind(user, selectedIds.contains(user.getUserId()), listener);
        }

        @Override
        public int getItemCount() {
            return users.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView avatar;
            private final TextView name;
            private final TextView username;
            private final ImageView ivCheckbox;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.avatar);
                name = itemView.findViewById(R.id.name);
                username = itemView.findViewById(R.id.username);
                ivCheckbox = itemView.findViewById(R.id.ivCheckbox);
            }

            void bind(User user, boolean isSelected, OnItemClickListener listener) {
                name.setText(user.getDisplayName() != null ? user.getDisplayName() : "Anonymous");
                username.setText("@" + user.getUsername());
                com.m3.veil.utils.VeilAvatarLoader.loadAvatar(avatar, user.getAvatar(), user.getDisplayName(), false);

                if (isSelected) {
                    ivCheckbox.setImageResource(R.drawable.ic_check_circle);
                    ivCheckbox.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.colorPrimary));
                } else {
                    ivCheckbox.setImageResource(R.drawable.ic_circle_outline);
                    ivCheckbox.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.textSecondary));
                }

                itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onItemClick(user);
                });
            }
        }
    }
}
