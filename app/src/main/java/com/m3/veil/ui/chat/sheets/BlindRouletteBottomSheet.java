package com.m3.veil.ui.chat.sheets;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.chat.ChatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlindRouletteBottomSheet extends BottomSheetDialogFragment {

    private ProgressBar pbRadar;
    private TextView tvStatus;
    private ChipGroup chipGroupMood;
    private MaterialButton btnStartMatch;
    private MaterialButton btnCancel;

    private FirebaseFirestore db;
    private String currentUserId;
    private String activePersonaName;
    private String selectedMood = "Any";

    private DocumentReference myLobbyDoc;
    private ListenerRegistration lobbyListener;
    private boolean isMatching = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_roulette_search_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null || currentUserId.isEmpty()) {
            currentUserId = "anon_" + UUID.randomUUID().toString().substring(0, 6);
        }
        activePersonaName = VeilFeatureManager.getInstance(requireContext()).getActivePersona().getDisplayName();

        pbRadar = view.findViewById(R.id.pbRouletteRadar);
        tvStatus = view.findViewById(R.id.tvRouletteStatus);
        chipGroupMood = view.findViewById(R.id.chipGroupMood);
        btnStartMatch = view.findViewById(R.id.btnStartMatch);
        btnCancel = view.findViewById(R.id.btnCancelRoulette);

        pbRadar.setVisibility(View.GONE);

        setupListeners();
    }

    private void setupListeners() {
        chipGroupMood.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipMoodConfess) selectedMood = "Confessions";
            else if (id == R.id.chipMoodCyber) selectedMood = "Tech & Crypto";
            else if (id == R.id.chipMoodChill) selectedMood = "Casual Chill";
            else if (id == R.id.chipMoodDare) selectedMood = "Truth or Dare";
            else selectedMood = "Any";
        });

        btnStartMatch.setOnClickListener(v -> startMatchmaking());
        btnCancel.setOnClickListener(v -> cancelMatchmakingAndDismiss());
    }

    private void startMatchmaking() {
        isMatching = true;
        pbRadar.setVisibility(View.VISIBLE);
        btnStartMatch.setEnabled(false);
        chipGroupMood.setEnabled(false);
        tvStatus.setText("Scanning mesh for an anonymous partner in [" + selectedMood + "]...");

        long thirtySecsAgo = System.currentTimeMillis() - 45000L;

        // 1. Try to find an existing waiting peer in roulette_lobby
        db.collection("roulette_lobby")
                .whereEqualTo("status", "waiting")
                .limit(10)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!isAdded() || !isMatching) return;

                    DocumentSnapshot matchDoc = null;
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        String peerUid = doc.getString("userId");
                        if (peerUid != null && !peerUid.equals(currentUserId)) {
                            String mood = doc.getString("mood");
                            if ("Any".equals(selectedMood) || "Any".equals(mood) || selectedMood.equalsIgnoreCase(mood)) {
                                matchDoc = doc;
                                break;
                            }
                        }
                    }

                    if (matchDoc != null) {
                        // Found a partner! Claim the match
                        claimMatch(matchDoc);
                    } else {
                        // No partner waiting, register self in lobby and wait
                        registerInLobby();
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    registerInLobby();
                });

        // 35-second fallback timeout
        timeoutHandler.postDelayed(() -> {
            if (isMatching && isAdded()) {
                tvStatus.setText("No mesh partners active right now. Matching you with @VeilBot for a demo speed chat!");
                timeoutHandler.postDelayed(this::matchWithDemoBot, 1500);
            }
        }, 35000);
    }

    private void claimMatch(DocumentSnapshot peerDoc) {
        String peerUid = peerDoc.getString("userId");
        String peerPersona = peerDoc.getString("personaName");
        String sessionId = "roulette_" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> update = new HashMap<>();
        update.put("status", "matched");
        update.put("matchedWith", currentUserId);
        update.put("sessionId", sessionId);

        peerDoc.getReference().update(update)
                .addOnSuccessListener(aVoid -> {
                    if (!isAdded()) return;
                    openRouletteSession(sessionId, peerUid, peerPersona != null ? peerPersona : "Roulette Partner");
                })
                .addOnFailureListener(e -> {
                    // Conflict claiming match, register in lobby instead
                    if (isAdded() && isMatching) registerInLobby();
                });
    }

    private void registerInLobby() {
        myLobbyDoc = db.collection("roulette_lobby").document(currentUserId);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", currentUserId);
        data.put("personaName", activePersonaName);
        data.put("mood", selectedMood);
        data.put("status", "waiting");
        data.put("timestamp", FieldValue.serverTimestamp());

        myLobbyDoc.set(data).addOnSuccessListener(aVoid -> {
            if (!isAdded() || !isMatching) return;
            listenForPartnerMatch();
        });
    }

    private void listenForPartnerMatch() {
        if (myLobbyDoc == null) return;
        lobbyListener = myLobbyDoc.addSnapshotListener((doc, e) -> {
            if (e != null || doc == null || !doc.exists() || !isMatching || !isAdded()) return;

            String status = doc.getString("status");
            if ("matched".equals(status)) {
                String sessionId = doc.getString("sessionId");
                String partnerId = doc.getString("matchedWith");
                // Clean up document
                myLobbyDoc.delete();
                openRouletteSession(sessionId, partnerId, "Anonymous Peer");
            }
        });
    }

    private void matchWithDemoBot() {
        String sessionId = "roulette_" + UUID.randomUUID().toString().substring(0, 8);
        cancelLobbyEntry();
        openRouletteSession(sessionId, "bot_roulette", "🎭 Anonymous Whisperer");
    }

    private void openRouletteSession(String sessionId, String partnerId, String partnerName) {
        isMatching = false;
        timeoutHandler.removeCallbacksAndMessages(null);

        // Create ephemeral 1:1 secret speed chat with 3-minute vanish timer (180 seconds)
        Chat rouletteChat = new Chat(
                sessionId,
                "🎲 " + partnerName,
                "Speed match started • 180s vanish timer active",
                Timestamp.now(),
                null,
                0,
                false,
                false,
                "direct",
                Arrays.asList(currentUserId, partnerId)
        );
        rouletteChat.setSecret(true);
        rouletteChat.setVanishTimer(180000L); // 180 seconds
        rouletteChat.setVanishMode("after_read");
        EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(rouletteChat);

        Toast.makeText(requireContext(), "Matched! 3-minute speed chat started ⚡", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra("chatId", sessionId);
        intent.putExtra("isGroup", false);
        intent.putExtra("isChannel", false);
        intent.putExtra("isSecret", true);
        startActivity(intent);
        dismiss();
    }

    private void cancelMatchmakingAndDismiss() {
        isMatching = false;
        timeoutHandler.removeCallbacksAndMessages(null);
        cancelLobbyEntry();
        dismiss();
    }

    private void cancelLobbyEntry() {
        if (lobbyListener != null) {
            lobbyListener.remove();
            lobbyListener = null;
        }
        if (myLobbyDoc != null) {
            myLobbyDoc.delete();
            myLobbyDoc = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelLobbyEntry();
    }
}
