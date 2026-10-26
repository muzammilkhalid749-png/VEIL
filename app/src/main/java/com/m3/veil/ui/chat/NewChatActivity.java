package com.m3.veil.ui.chat;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.Persona;
import com.m3.veil.model.User;
import com.m3.veil.ui.chat.adapters.UsersAdapter;
import com.m3.veil.ui.chat.sheets.NearbyRadarBottomSheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class NewChatActivity extends AppCompatActivity implements UsersAdapter.OnUserClickListener {

    private RecyclerView usersRecyclerView;
    private UsersAdapter usersAdapter;
    private List<User> userList;
    private EditText searchEditText;
    private ProgressBar loadingBar;
    private View emptyState;

    private FirebaseFirestore db;
    private String currentUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_new_chat);

        try {
            db = FirebaseFirestore.getInstance();
        } catch (Throwable ignored) {}
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this);
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this).getCurrentUserId();
        }
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "veil_anonymous";
        }
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            com.m3.veil.manager.AuthManager.getInstance().onSessionStarted(this, FirebaseAuth.getInstance().getCurrentUser());
        }

        initializeViews();
        handleWindowInsets();
        setupRecyclerView();
        setupSearchListener();
        searchUsers("");
    }

    private void initializeViews() {
        usersRecyclerView = findViewById(R.id.usersRecyclerView);
        searchEditText = findViewById(R.id.searchEditText);
        loadingBar = findViewById(R.id.loadingBar);
        emptyState = findViewById(R.id.emptyState);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        View btnNewGroup = findViewById(R.id.btnNewGroup);
        if (btnNewGroup != null) {
            btnNewGroup.setOnClickListener(v -> {
                startActivity(new Intent(this, NewGroupActivity.class));
                finish();
            });
        }
        View btnNewChannel = findViewById(R.id.btnNewChannel);
        if (btnNewChannel != null) {
            btnNewChannel.setOnClickListener(v -> {
                startActivity(new Intent(this, CreateChannelActivity.class));
                finish();
            });
        }
        View btnDiscoveryHub = findViewById(R.id.btnDiscoveryHub);
        if (btnDiscoveryHub != null) {
            btnDiscoveryHub.setOnClickListener(v -> showDiscoveryPowerHub());
        }
    }

    /**
     * Discovery & Matchmaking Power Hub:
     * - New Group or Broadcast Channel
     * - Random Chat Roulette (Match with an anonymous online peer)
     * - One-Time Whisper Link / Temporary Chat Code
     * - Mood-Based Matchmaking ("Curious", "Night Owl", "Chill", "Cypherpunk")
     * - Ping Nearby Proximity Ephemeral Room
     */
    private void showDiscoveryPowerHub() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_social_hub_sheet);

        View cardRoulette = sheet.findViewById(R.id.cardHubRoulette);
        View cardMood = sheet.findViewById(R.id.cardHubMood);
        View cardNearby = sheet.findViewById(R.id.cardHubNearby);
        View cardWhisper = sheet.findViewById(R.id.cardHubWhisper);
        View cardChannel = sheet.findViewById(R.id.cardHubChannel);

        if (cardRoulette != null) {
            cardRoulette.setOnClickListener(v -> {
                sheet.dismiss();
                startRandomRouletteChat();
            });
        }
        if (cardMood != null) {
            cardMood.setOnClickListener(v -> {
                sheet.dismiss();
                showMoodMatchDialog();
            });
        }
        if (cardNearby != null) {
            cardNearby.setOnClickListener(v -> {
                sheet.dismiss();
                joinNearbyProximityRoom();
            });
        }
        if (cardWhisper != null) {
            cardWhisper.setOnClickListener(v -> {
                sheet.dismiss();
                showWhisperCodeDialog();
            });
        }
        if (cardChannel != null) {
            cardChannel.setOnClickListener(v -> {
                sheet.dismiss();
                showCreateChannelDialog();
            });
        }
        sheet.show();
    }

    private void showCreateChannelDialog() {
        startActivity(new Intent(this, CreateChannelActivity.class));
        finish();
    }

    private ListenerRegistration rouletteListener;
    private BottomSheetDialog rouletteDialog;
    private final android.os.Handler rouletteTimeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable rouletteTimeoutRunnable;
    private ListenerRegistration whisperListener;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rouletteListener != null) {
            rouletteListener.remove();
            rouletteListener = null;
        }
        if (rouletteTimeoutRunnable != null) {
            rouletteTimeoutHandler.removeCallbacks(rouletteTimeoutRunnable);
        }
        if (whisperListener != null) {
            whisperListener.remove();
            whisperListener = null;
        }
    }

    private void startRandomRouletteChat() {
        if (isFinishing() || isDestroyed()) return;

        Persona persona = VeilFeatureManager.getInstance(this).getActivePersona();
        String myUsername = persona != null && persona.getDisplayName() != null ? persona.getDisplayName() : "anonymous";

        // 1. Check if another peer is already waiting in roulette_pool
        db.collection("roulette_pool")
                .whereEqualTo("status", "waiting")
                .limit(10)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (isFinishing() || isDestroyed()) return;
                    DocumentSnapshot candidate = null;
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        String waitingUid = doc.getString("userId");
                        if (waitingUid != null && !waitingUid.equals(currentUserId)) {
                            candidate = doc;
                            break;
                        }
                    }

                    if (candidate != null) {
                        // Found waiting match!
                        String peerId = candidate.getString("userId");
                        String peerUsername = candidate.getString("username");
                        if (peerUsername == null || peerUsername.isEmpty()) peerUsername = "Stranger";
                        List<String> ids = Arrays.asList(currentUserId, peerId);
                        Collections.sort(ids);
                        String chatId = ids.get(0) + "_" + ids.get(1);

                        // Atomically mark matched
                        candidate.getReference().update(
                                "status", "matched",
                                "matchedUserId", currentUserId,
                                "matchedUsername", myUsername,
                                "chatId", chatId
                        );

                        connectRoulettePeer(chatId, peerId, peerUsername);
                    } else {
                        // No peer waiting: enter queue and wait
                        enterRouletteQueue(myUsername);
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Roulette network notice: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void enterRouletteQueue(String myUsername) {
        Map<String, Object> poolData = new HashMap<>();
        poolData.put("userId", currentUserId);
        poolData.put("username", myUsername);
        poolData.put("status", "waiting");
        poolData.put("timestamp", FieldValue.serverTimestamp());

        db.collection("roulette_pool").document(currentUserId).set(poolData);

        // Build clean Material waiting dialog
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_roulette_search_sheet);
        sheet.setCancelable(false);

        View btnCancel = sheet.findViewById(R.id.btnCancelRoulette);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> cancelRouletteSearch());
        }

        rouletteDialog = sheet;
        rouletteDialog.show();

        // Listen for match
        if (rouletteListener != null) rouletteListener.remove();
        rouletteListener = db.collection("roulette_pool").document(currentUserId)
                .addSnapshotListener((snapshot, e) -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    String status = snapshot.getString("status");
                    if ("matched".equals(status)) {
                        String matchedUserId = snapshot.getString("matchedUserId");
                        String matchedUsername = snapshot.getString("matchedUsername");
                        String matchedChatId = snapshot.getString("chatId");
                        if (matchedUsername == null || matchedUsername.isEmpty()) matchedUsername = "Stranger";

                        // Cleanup my queue document
                        snapshot.getReference().delete();
                        if (rouletteListener != null) {
                            rouletteListener.remove();
                            rouletteListener = null;
                        }
                        if (rouletteTimeoutRunnable != null) {
                            rouletteTimeoutHandler.removeCallbacks(rouletteTimeoutRunnable);
                        }
                        if (rouletteDialog != null && rouletteDialog.isShowing()) {
                            rouletteDialog.dismiss();
                        }

                        connectRoulettePeer(matchedChatId, matchedUserId, matchedUsername);
                    }
                });

        // 20 second timeout
        rouletteTimeoutRunnable = () -> {
            if (rouletteDialog != null && rouletteDialog.isShowing()) {
                cancelRouletteSearch();
                if (!userList.isEmpty()) {
                    User randomPeer = userList.get(new Random().nextInt(userList.size()));
                    Toast.makeText(this, "🎲 Connected with community peer @" + randomPeer.getUsername(), Toast.LENGTH_SHORT).show();
                    startChatWithUser(randomPeer);
                } else {
                    Toast.makeText(this, "No anonymous peers currently waiting. Try again shortly!", Toast.LENGTH_LONG).show();
                }
            }
        };
        rouletteTimeoutHandler.postDelayed(rouletteTimeoutRunnable, 20000);
    }

    private void cancelRouletteSearch() {
        if (rouletteListener != null) {
            rouletteListener.remove();
            rouletteListener = null;
        }
        if (rouletteTimeoutRunnable != null) {
            rouletteTimeoutHandler.removeCallbacks(rouletteTimeoutRunnable);
        }
        db.collection("roulette_pool").document(currentUserId).delete();
        if (rouletteDialog != null && rouletteDialog.isShowing()) {
            rouletteDialog.dismiss();
        }
    }

    private void connectRoulettePeer(String chatId, String peerId, String peerUsername) {
        Chat rouletteChat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (rouletteChat == null) {
            rouletteChat = new Chat(
                    chatId,
                    "🎲 @" + peerUsername,
                    "Matched via Random Roulette (Vanish active)",
                    Timestamp.now(),
                    null,
                    0,
                    false,
                    true,
                    "direct",
                    Arrays.asList(currentUserId, peerId)
            );
            rouletteChat.setVanishTimer(60000);
            rouletteChat.setVanishMode("after_read");
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(rouletteChat);
        }

        Toast.makeText(this, "🎲 Matched with @" + peerUsername + "!", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("recipientId", peerId);
        intent.putExtra("recipientName", rouletteChat.getName());
        startActivity(intent);
        finish();
    }

    private void showWhisperCodeDialog() {
        Persona persona = VeilFeatureManager.getInstance(this).getActivePersona();
        String myUsername = persona != null && persona.getDisplayName() != null ? persona.getDisplayName() : "anonymous";
        String myCode = "VEIL-" + (100000 + new Random().nextInt(899999));

        // Register my whisper code in Firestore
        Map<String, Object> data = new HashMap<>();
        data.put("code", myCode);
        data.put("creatorId", currentUserId);
        data.put("creatorUsername", myUsername);
        data.put("status", "active");
        data.put("createdAt", FieldValue.serverTimestamp());
        db.collection("whisper_codes").document(myCode).set(data);

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_whisper_key_sheet);

        TextView tvMyCode = sheet.findViewById(R.id.tvWhisperMyCode);
        MaterialButton btnCopy = sheet.findViewById(R.id.btnCopyWhisperCode);
        TextInputEditText etPeerCode = sheet.findViewById(R.id.etPeerWhisperCode);
        MaterialButton btnConnect = sheet.findViewById(R.id.btnConnectWhisper);

        if (tvMyCode != null) tvMyCode.setText(myCode);

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Whisper Link", "veil://whisper/" + myCode));
                    Toast.makeText(this, "Self-destructing Whisper Link copied!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnConnect != null && etPeerCode != null) {
            btnConnect.setOnClickListener(v -> {
                String entered = etPeerCode.getText() != null ? etPeerCode.getText().toString().trim().toUpperCase() : "";
                if (entered.isEmpty()) {
                    Toast.makeText(this, "Please enter peer's whisper key", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (entered.equals(myCode)) {
                    Toast.makeText(this, "Cannot connect with your own key", Toast.LENGTH_SHORT).show();
                    return;
                }

                db.collection("whisper_codes").document(entered).get().addOnSuccessListener(doc -> {
                    if (doc.exists() && "active".equals(doc.getString("status"))) {
                        String creatorId = doc.getString("creatorId");
                        String creatorUsername = doc.getString("creatorUsername");
                        if (creatorUsername == null || creatorUsername.isEmpty()) creatorUsername = "Peer";

                        // Burn key
                        doc.getReference().update(
                                "status", "claimed",
                                "claimedBy", currentUserId,
                                "claimedUsername", myUsername
                        );

                        if (whisperListener != null) {
                            whisperListener.remove();
                            whisperListener = null;
                        }
                        db.collection("whisper_codes").document(myCode).delete();
                        sheet.dismiss();

                        connectWhisperPeer(creatorId, creatorUsername);
                    } else {
                        Toast.makeText(this, "Invalid or expired Whisper Key", Toast.LENGTH_SHORT).show();
                    }
                }).addOnFailureListener(e -> {
                    Toast.makeText(this, "Connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            });
        }

        sheet.setOnDismissListener(d -> {
            if (whisperListener != null) {
                whisperListener.remove();
                whisperListener = null;
            }
            db.collection("whisper_codes").document(myCode).delete();
        });

        // Listen for when someone claims my whisper code
        whisperListener = db.collection("whisper_codes").document(myCode)
                .addSnapshotListener((snapshot, e) -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (e != null || snapshot == null || !snapshot.exists()) return;

                    if ("claimed".equals(snapshot.getString("status"))) {
                        String peerId = snapshot.getString("claimedBy");
                        String peerUsername = snapshot.getString("claimedUsername");
                        if (peerUsername == null || peerUsername.isEmpty()) peerUsername = "Peer";

                        snapshot.getReference().delete();
                        if (whisperListener != null) {
                            whisperListener.remove();
                            whisperListener = null;
                        }
                        if (sheet.isShowing()) {
                            sheet.dismiss();
                        }

                        connectWhisperPeer(peerId, peerUsername);
                    }
                });

        sheet.show();
    }

    private void connectWhisperPeer(String peerId, String peerUsername) {
        List<String> ids = Arrays.asList(currentUserId, peerId);
        Collections.sort(ids);
        String chatId = ids.get(0) + "_" + ids.get(1);

        Chat whisperChat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (whisperChat == null) {
            whisperChat = new Chat(
                    chatId,
                    "🔑 @" + peerUsername,
                    "Connected via 1-time Whisper Key",
                    Timestamp.now(),
                    null,
                    0,
                    false,
                    true,
                    "direct",
                    Arrays.asList(currentUserId, peerId)
            );
            whisperChat.setVanishMode("after_read");
            whisperChat.setVanishTimer(30000);
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(whisperChat);
        }

        Toast.makeText(this, "🔑 Connected with @" + peerUsername + " via Whisper!", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("recipientId", peerId);
        intent.putExtra("recipientName", whisperChat.getName());
        startActivity(intent);
        finish();
    }

    private void showMoodMatchDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.dialog_mood_picker_sheet);

        View cardNightOwl = sheet.findViewById(R.id.cardMoodNightOwl);
        View cardCurious = sheet.findViewById(R.id.cardMoodCurious);
        View cardCypherpunk = sheet.findViewById(R.id.cardMoodCypherpunk);
        View cardChill = sheet.findViewById(R.id.cardMoodChill);
        View cardVent = sheet.findViewById(R.id.cardMoodVent);

        if (cardNightOwl != null) cardNightOwl.setOnClickListener(v -> { sheet.dismiss(); launchMoodRoom("Night Owl", "🌙 Night Owl Lounge"); });
        if (cardCurious != null) cardCurious.setOnClickListener(v -> { sheet.dismiss(); launchMoodRoom("Curious", "🔮 Curious Mind Lounge"); });
        if (cardCypherpunk != null) cardCypherpunk.setOnClickListener(v -> { sheet.dismiss(); launchMoodRoom("Cypherpunk", "🛡️ Cypherpunk Lounge"); });
        if (cardChill != null) cardChill.setOnClickListener(v -> { sheet.dismiss(); launchMoodRoom("Chill", "☕ Chill & Casual Lounge"); });
        if (cardVent != null) cardVent.setOnClickListener(v -> { sheet.dismiss(); launchMoodRoom("Vent", "💬 Safe Space Lounge"); });

        sheet.show();
    }

    private void launchMoodRoom(String moodKey, String roomName) {
        String roomId = "mood_lounge_" + moodKey.toLowerCase().replace(" ", "_");
        Chat moodRoom = EncryptedChatDatabase.getInstance(this).getChat(roomId);
        if (moodRoom == null) {
            moodRoom = new Chat(
                    roomId,
                    roomName,
                    "Public ephemeral lounge for " + moodKey + " mood",
                    Timestamp.now(),
                    null,
                    0,
                    true,
                    false,
                    "group",
                    Collections.singletonList(currentUserId)
            );
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(moodRoom);
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", roomId);
        intent.putExtra("recipientName", moodRoom.getName());
        startActivity(intent);
        finish();
    }

    private void joinNearbyProximityRoom() {
        NearbyRadarBottomSheet sheet = NearbyRadarBottomSheet.newInstance();
        sheet.show(getSupportFragmentManager(), "NearbyRadarBottomSheet");
    }

    private void handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.new_chat_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private TextView tvNewChatDirectoryStatus;
    private View btnQuickConnectHandle;
    private TextView tvQuickConnectLabel;
    private final android.os.Handler searchDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearchRunnable;
    private final java.util.concurrent.atomic.AtomicInteger searchSequence = new java.util.concurrent.atomic.AtomicInteger(0);

    private void setupRecyclerView() {
        userList = new ArrayList<>();
        usersAdapter = new UsersAdapter(userList, this);
        usersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        usersRecyclerView.setAdapter(usersAdapter);

        tvNewChatDirectoryStatus = findViewById(R.id.tvNewChatDirectoryStatus);
        btnQuickConnectHandle = findViewById(R.id.btnQuickConnectHandle);
        tvQuickConnectLabel = findViewById(R.id.tvQuickConnectLabel);
    }

    private void setupSearchListener() {
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String raw = s != null ? s.toString().trim() : "";
                scheduleDebouncedSearch(raw);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void scheduleDebouncedSearch(String rawQuery) {
        if (pendingSearchRunnable != null) {
            searchDebounceHandler.removeCallbacks(pendingSearchRunnable);
        }

        String clean = rawQuery != null ? rawQuery.trim().toLowerCase() : "";
        if (clean.startsWith("@")) {
            clean = clean.substring(1);
        }
        final String cleanHandle = clean;

        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine engine =
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this);

        if (cleanHandle.isEmpty()) {
            if (btnQuickConnectHandle != null) {
                btnQuickConnectHandle.setVisibility(View.GONE);
            }
            if (tvNewChatDirectoryStatus != null) {
                int bloomCount = engine.getBloomFilter().getApproximateCount();
                tvNewChatDirectoryStatus.setText("🔐 O(1) k=7 Bloom Directory (" + bloomCount + " handles) • Mesh Ready");
            }
            searchUsers("");
            return;
        }

        boolean bloomHit = cleanHandle.length() >= 3 && engine.mightContainUsername(cleanHandle);
        if (tvNewChatDirectoryStatus != null) {
            if (bloomHit) {
                tvNewChatDirectoryStatus.setText("⚡ Bloom Filter HIT (<0.02ms): @" + cleanHandle + " indexed in directory");
            } else {
                tvNewChatDirectoryStatus.setText("🔍 Searching SQLCipher + Blinded Directory for @" + cleanHandle + "…");
            }
        }

        if (btnQuickConnectHandle != null && tvQuickConnectLabel != null) {
            if (cleanHandle.length() >= 3 && cleanHandle.matches("^[a-z0-9_]{3,32}$")) {
                btnQuickConnectHandle.setVisibility(View.VISIBLE);
                tvQuickConnectLabel.setText(bloomHit
                        ? "⚡ Verified in Bloom Filter: Chat with @" + cleanHandle
                        : "🔗 Direct P2P Lookup & Connect with @" + cleanHandle);
                btnQuickConnectHandle.setOnClickListener(v -> connectDirectlyByHandle(cleanHandle));
            } else {
                btnQuickConnectHandle.setVisibility(View.GONE);
            }
        }

        pendingSearchRunnable = () -> searchUsers(cleanHandle);
        searchDebounceHandler.postDelayed(pendingSearchRunnable, 160L);
    }

    private void connectDirectlyByHandle(String cleanHandle) {
        loadingBar.setVisibility(View.VISIBLE);
        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this)
                .searchUsersByUsername(cleanHandle, users -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        loadingBar.setVisibility(View.GONE);
                        if (users != null) {
                            for (User u : users) {
                                if (u != null && u.getUsername() != null
                                        && u.getUsername().equalsIgnoreCase(cleanHandle)
                                        && !currentUserId.equals(u.getUserId())) {
                                    startChatWithUser(u);
                                    return;
                                }
                            }
                            if (!users.isEmpty() && !currentUserId.equals(users.get(0).getUserId())) {
                                startChatWithUser(users.get(0));
                                return;
                            }
                        }
                        Toast.makeText(this, "No registered peer found for @" + cleanHandle + " yet", Toast.LENGTH_SHORT).show();
                    });
                });
    }

    private void searchUsers(String query) {
        loadingBar.setVisibility(View.VISIBLE);

        String cleanQuery = query != null ? query.trim().toLowerCase() : "";
        if (cleanQuery.startsWith("@")) {
            cleanQuery = cleanQuery.substring(1);
        }
        final String finalQuery = cleanQuery;
        final int seq = searchSequence.incrementAndGet();

        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this)
                .searchUsersByUsername(finalQuery, users -> {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || seq != searchSequence.get()) return;
                        loadingBar.setVisibility(View.GONE);
                        userList.clear();
                        if (users != null) {
                            for (User user : users) {
                                if (user != null && user.getUserId() != null && !user.getUserId().equals(currentUserId)) {
                                    userList.add(user);
                                }
                            }
                        }
                        appendNearbyMeshPeers(finalQuery);
                        usersAdapter.notifyDataSetChanged();
                        updateEmptyState();
                    });
                });
    }

    private User parseUserFromDoc(DocumentSnapshot doc) {
        if (doc == null || !doc.exists()) return null;
        try {
            User user = doc.toObject(User.class);
            if (user == null) user = new User();
            if (user.getUserId() == null || user.getUserId().trim().isEmpty()) {
                user.setUserId(doc.getId());
            }
            if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
                String fallback = "anon_" + doc.getId().substring(0, Math.min(6, doc.getId().length())).toLowerCase();
                user.setUsername(fallback);
            }
            if (user.getDisplayName() == null || user.getDisplayName().trim().isEmpty()) {
                user.setDisplayName("@" + user.getUsername());
            }
            return user;
        } catch (Exception e) {
            User fallback = new User();
            fallback.setUserId(doc.getId());
            String uname = doc.getString("username");
            if (uname == null || uname.isEmpty()) {
                uname = "anon_" + doc.getId().substring(0, Math.min(6, doc.getId().length())).toLowerCase();
            }
            fallback.setUsername(uname);
            String dname = doc.getString("displayName");
            fallback.setDisplayName(dname != null && !dname.isEmpty() ? dname : ("@" + uname));
            return fallback;
        }
    }

    private void appendNearbyMeshPeers(String queryLower) {
        try {
            List<com.m3.veil.webrtc.NearbyMeshController.NearbyPeer> nearby =
                    com.m3.veil.webrtc.NearbyMeshController.getInstance(this).getDiscoveredPeers();
            if (nearby == null) return;
            for (com.m3.veil.webrtc.NearbyMeshController.NearbyPeer np : nearby) {
                if (np == null || np.id == null || np.id.equals(currentUserId)) continue;
                boolean exists = false;
                for (User u : userList) {
                    if (np.id.equals(u.getUserId())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    String handle = np.alias != null ? np.alias.replace("@", "") : ("nearby_" + np.id.substring(0, Math.min(4, np.id.length())));
                    if (queryLower.isEmpty() || handle.toLowerCase().contains(queryLower)) {
                        User nu = new User();
                        nu.setUserId(np.id);
                        nu.setUsername(handle);
                        nu.setDisplayName("📡 " + (np.alias != null ? np.alias : handle));
                        nu.setStatus("online");
                        userList.add(nu);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateEmptyState() {
        if (userList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            usersRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            usersRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onUserClick(User user) {
        startChatWithUser(user);
    }

    private void startChatWithUser(User otherUser) {
        List<String> ids = Arrays.asList(currentUserId, otherUser.getUserId());
        Collections.sort(ids);
        String chatId = ids.get(0) + "_" + ids.get(1);

        Chat existing = EncryptedChatDatabase.getInstance(this).getChat(chatId);
        if (existing == null) {
            String recipientTitle = otherUser.getDisplayName() != null ? otherUser.getDisplayName() : otherUser.getUsername();
            Chat newChat = new Chat(
                    chatId,
                    recipientTitle,
                    "",
                    Timestamp.now(),
                    otherUser.getPhotoUrl(),
                    0,
                    false,
                    false,
                    "direct",
                    Arrays.asList(currentUserId, otherUser.getUserId())
            );
            EncryptedChatDatabase.getInstance(this).saveOrUpdateChat(newChat);
        }
        navigateToChat(chatId, otherUser);
    }

    private void navigateToChat(String chatId, User otherUser) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId", chatId);
        intent.putExtra("recipientId", otherUser.getUserId());
        intent.putExtra("recipientName", otherUser.getDisplayName() != null
                ? otherUser.getDisplayName() : otherUser.getUsername());
        startActivity(intent);
        finish();
    }
}
