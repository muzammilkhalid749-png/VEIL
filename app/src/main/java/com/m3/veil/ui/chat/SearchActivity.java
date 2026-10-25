package com.m3.veil.ui.chat;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.User;
import com.m3.veil.ui.main.adapters.ChatsAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SearchActivity extends AppCompatActivity implements ChatsAdapter.OnChatClickListener {

    private RecyclerView resultsRecyclerView;
    private ChatsAdapter chatsAdapter;
    private List<Chat> allChats;
    private List<Chat> filteredChats;
    private EditText searchEditText;
    private ProgressBar loadingBar;
    private View emptyState;

    private FirebaseFirestore db;
    private String currentUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_search);

        db = FirebaseFirestore.getInstance();
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } else {
            finish();
            return;
        }

        initializeViews();
        handleWindowInsets();
        setupRecyclerView();
        loadAllChats();
        setupSearchListener();
    }

    private void initializeViews() {
        resultsRecyclerView = findViewById(R.id.resultsRecyclerView);
        searchEditText = findViewById(R.id.searchEditText);
        loadingBar = findViewById(R.id.loadingBar);
        emptyState = findViewById(R.id.emptyState);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    private void handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.search_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void setupRecyclerView() {
        allChats = new ArrayList<>();
        filteredChats = new ArrayList<>();
        chatsAdapter = new ChatsAdapter(filteredChats, this);
        resultsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        resultsRecyclerView.setAdapter(chatsAdapter);
    }

    private void loadAllChats() {
        List<Chat> local = EncryptedChatDatabase.getInstance(this).getAllChats();
        allChats.clear();
        if (local != null && !local.isEmpty()) {
            allChats.addAll(local);
        }
        filter("");
    }

    private void setupSearchListener() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void filter(String query) {
        filteredChats.clear();
        if (query.isEmpty()) {
            filteredChats.addAll(allChats);
            chatsAdapter.notifyDataSetChanged();
            updateEmptyState();
        } else {
            String lower = query.toLowerCase();
            for (Chat chat : allChats) {
                boolean matchName = chat.getName() != null && chat.getName().toLowerCase().contains(lower);
                boolean matchMsg = chat.getLastMessage() != null && chat.getLastMessage().toLowerCase().contains(lower);
                if (matchName || matchMsg) {
                    filteredChats.add(chat);
                }
            }

            // Universal search: search full message histories across encrypted vault
            List<com.m3.veil.model.Message> matchingMsgs = EncryptedChatDatabase.getInstance(this).searchMessages(query, 30);
            for (com.m3.veil.model.Message m : matchingMsgs) {
                String cId = null;
                if (m.getReceiverId() != null) {
                    Chat groupChat = EncryptedChatDatabase.getInstance(this).getChat(m.getReceiverId());
                    if (groupChat != null && groupChat.isGroup()) {
                        cId = m.getReceiverId();
                    } else if (m.getSenderId() != null) {
                        cId = com.m3.veil.webrtc.WebRtcManager.getChatId(m.getSenderId(), m.getReceiverId());
                    }
                }
                if (cId != null && !cId.isEmpty()) {
                    boolean alreadyIn = false;
                    for (Chat c : filteredChats) {
                        if (cId.equals(c.getId())) {
                            alreadyIn = true;
                            break;
                        }
                    }
                    if (!alreadyIn) {
                        Chat c = EncryptedChatDatabase.getInstance(this).getChat(cId);
                        if (c != null) {
                            filteredChats.add(c);
                        }
                    }
                }
            }

            chatsAdapter.notifyDataSetChanged();
            updateEmptyState();

            // Ephemeral user directory lookup (username/handle discovery)
            String cleanQuery = lower.startsWith("@") ? lower.substring(1) : lower;
            loadingBar.setVisibility(View.VISIBLE);
            db.collection("users")
                    .orderBy("username")
                    .startAt(cleanQuery)
                    .endAt(cleanQuery + "\uf8ff")
                    .limit(10)
                    .get()
                    .addOnSuccessListener(queryDocumentSnapshots -> {
                        loadingBar.setVisibility(View.GONE);
                        boolean addedNew = false;
                        for (DocumentSnapshot doc : queryDocumentSnapshots) {
                            User user = doc.toObject(User.class);
                            if (user != null) {
                                if (user.getUserId() == null || user.getUserId().isEmpty()) {
                                    user.setUserId(doc.getId());
                                }
                            }
                            if (user != null && user.getUserId() != null && !user.getUserId().equals(currentUserId)) {
                                boolean exists = false;
                                for (Chat c : filteredChats) {
                                    if (c.getParticipants() != null && c.getParticipants().contains(user.getUserId())) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists) {
                                    List<String> ids = Arrays.asList(currentUserId, user.getUserId());
                                    Collections.sort(ids);
                                    String chatId = ids.get(0) + "_" + ids.get(1);

                                    Chat fakeChat = new Chat(
                                            chatId,
                                            user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                                            "Tap to start P2P E2EE chat",
                                            null,
                                            user.getPhotoUrl(),
                                            0,
                                            false,
                                            false,
                                            "direct",
                                            Arrays.asList(currentUserId, user.getUserId())
                                    );
                                    filteredChats.add(fakeChat);
                                    addedNew = true;
                                }
                            }
                        }
                        if (addedNew) {
                            chatsAdapter.notifyDataSetChanged();
                            updateEmptyState();
                        }
                    })
                    .addOnFailureListener(e -> loadingBar.setVisibility(View.GONE));
        }
    }

    private void updateEmptyState() {
        if (filteredChats.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            resultsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            resultsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onChatClick(Chat chat) {
        EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(chat);
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chat.getId());
        intent.putExtra("recipientName", chat.getName());

        String recipientId = "";
        if (chat.getParticipants() != null) {
            for (String id : chat.getParticipants()) {
                if (!id.equals(currentUserId)) {
                    recipientId = id;
                    break;
                }
            }
        }
        intent.putExtra("recipientId", recipientId);
        startActivity(intent);
        finish();
    }

    @Override
    public void onChatLongClick(Chat chat) {}
}
