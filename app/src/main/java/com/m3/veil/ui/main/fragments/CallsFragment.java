package com.m3.veil.ui.main.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.CallRecord;
import com.m3.veil.model.User;
import com.m3.veil.ui.chat.CallActivity;
import com.m3.veil.ui.chat.NewChatActivity;
import com.m3.veil.ui.main.adapters.CallsAdapter;
import com.m3.veil.utils.UiAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class CallsFragment extends Fragment implements CallsAdapter.OnCallItemClickListener {

    private RecyclerView callsRecyclerView;
    private View emptyState;
    private ProgressBar loadingBar;
    private ExtendedFloatingActionButton fabNewCall;

    private CallsAdapter callsAdapter;
    private List<CallRecord> callList;
    private String currentUserId;

    private final EncryptedChatDatabase.DatabaseChangeListener dbListener = new EncryptedChatDatabase.DatabaseChangeListener() {
        @Override
        public void onMessagesUpdated(String chatId) {}

        @Override
        public void onChatsUpdated() {}

        @Override
        public void onCallsUpdated() {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalCalls());
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calls, container, false);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } else {
            currentUserId = "local_user";
        }

        callsRecyclerView = view.findViewById(R.id.callsRecyclerView);
        emptyState = view.findViewById(R.id.emptyState);
        loadingBar = view.findViewById(R.id.loadingBar);
        fabNewCall = view.findViewById(R.id.fabNewCall);

        UiAnimator.attachSpringPress(fabNewCall);
        fabNewCall.setOnClickListener(v -> showCallHubMenu());

        setupRecyclerView();
        loadLocalCalls();

        EncryptedChatDatabase.getInstance(requireContext()).addListener(dbListener);

        return view;
    }

    public void showCallHubMenu() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View sheetView = getLayoutInflater().inflate(R.layout.dialog_call_hub_sheet, null);
        sheet.setContentView(sheetView);

        View cardDirect = sheetView.findViewById(R.id.cardCallHubDirect);
        View cardRoulette = sheetView.findViewById(R.id.cardCallHubRoulette);
        View cardClear = sheetView.findViewById(R.id.cardCallHubClear);

        if (cardDirect != null) {
            cardDirect.setOnClickListener(v -> {
                sheet.dismiss();
                startActivity(new Intent(getContext(), NewChatActivity.class));
            });
        }
        if (cardRoulette != null) {
            cardRoulette.setOnClickListener(v -> {
                sheet.dismiss();
                startRandomPeerVoiceCall();
            });
        }
        if (cardClear != null) {
            cardClear.setOnClickListener(v -> {
                sheet.dismiss();
                confirmClearAllCalls();
            });
        }

        // Inject Create P2P Call Link & Group Call cards into the sheet container
        if (cardDirect != null && cardDirect.getParent() instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout container = (android.widget.LinearLayout) cardDirect.getParent();
            float dp = getResources().getDisplayMetrics().density;
            int insertIdx = container.indexOfChild(cardDirect) + 1;

            android.widget.LinearLayout btnCallLink = new android.widget.LinearLayout(requireContext());
            btnCallLink.setOrientation(android.widget.LinearLayout.VERTICAL);
            btnCallLink.setBackgroundResource(R.drawable.bg_search_input);
            int pad = (int) (14 * dp);
            btnCallLink.setPadding(pad, pad, pad, pad);
            android.widget.LinearLayout.LayoutParams linkLp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            linkLp.bottomMargin = (int) (10 * dp);
            btnCallLink.setLayoutParams(linkLp);

            TextView tvLinkTitle = new TextView(requireContext());
            tvLinkTitle.setText("🔗 Create Encrypted P2P Call Link");
            tvLinkTitle.setTextSize(15f);
            tvLinkTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLinkTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
            btnCallLink.addView(tvLinkTitle);

            TextView tvLinkSub = new TextView(requireContext());
            tvLinkSub.setText("Generate a shareable veil://call/ link for instant E2EE voice or video calls");
            tvLinkSub.setTextSize(12.5f);
            tvLinkSub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
            btnCallLink.addView(tvLinkSub);

            btnCallLink.setOnClickListener(v -> {
                sheet.dismiss();
                showCreateCallLinkSheet();
            });
            container.addView(btnCallLink, insertIdx);

            android.widget.LinearLayout btnGroupCall = new android.widget.LinearLayout(requireContext());
            btnGroupCall.setOrientation(android.widget.LinearLayout.VERTICAL);
            btnGroupCall.setBackgroundResource(R.drawable.bg_search_input);
            btnGroupCall.setPadding(pad, pad, pad, pad);
            android.widget.LinearLayout.LayoutParams grpLp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            grpLp.bottomMargin = (int) (10 * dp);
            btnGroupCall.setLayoutParams(grpLp);

            TextView tvGrpTitle = new TextView(requireContext());
            tvGrpTitle.setText("👥 Start E2EE Group Mesh Call");
            tvGrpTitle.setTextSize(15f);
            tvGrpTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvGrpTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
            btnGroupCall.addView(tvGrpTitle);

            TextView tvGrpSub = new TextView(requireContext());
            tvGrpSub.setText("Select multiple contacts for a multi-peer SFrame encrypted voice or video call");
            tvGrpSub.setTextSize(12.5f);
            tvGrpSub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
            btnGroupCall.addView(tvGrpSub);

            btnGroupCall.setOnClickListener(v -> {
                sheet.dismiss();
                showGroupCallPickerDialog();
            });
            container.addView(btnGroupCall, insertIdx + 1);
        }

        sheet.show();
    }

    private void showCreateCallLinkSheet() {
        if (getContext() == null) return;
        String token = UUID.randomUUID().toString().substring(0, 8);
        String callLink = "veil://call/" + token;

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (24 * dp), (int) (20 * dp), (int) (24 * dp), (int) (28 * dp));

        TextView title = new TextView(requireContext());
        title.setText("🔗 Encrypted P2P Call Link");
        title.setTextSize(18.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
        root.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setText("Anyone with this VEIL link can join your direct WebRTC call room with end-to-end key verification.");
        sub.setTextSize(13f);
        sub.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
        sub.setPadding(0, (int) (6 * dp), 0, (int) (14 * dp));
        root.addView(sub);

        TextView linkBox = new TextView(requireContext());
        linkBox.setText(callLink);
        linkBox.setTextSize(15f);
        linkBox.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        linkBox.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        linkBox.setBackgroundResource(R.drawable.bg_search_input);
        int pad = (int) (14 * dp);
        linkBox.setPadding(pad, pad, pad, pad);
        root.addView(linkBox);

        com.google.android.material.button.MaterialButton btnCopy = new com.google.android.material.button.MaterialButton(requireContext());
        btnCopy.setText("📋 Copy Call Link");
        btnCopy.setCornerRadius((int) (12 * dp));
        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Veil Call Link", callLink));
                Toast.makeText(getContext(), "Call link copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnCopy);

        com.google.android.material.button.MaterialButton btnShare = new com.google.android.material.button.MaterialButton(requireContext());
        btnShare.setText("📤 Share Call Link");
        btnShare.setCornerRadius((int) (12 * dp));
        btnShare.setOnClickListener(v -> {
            sheet.dismiss();
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, "Join my encrypted VEIL P2P call: " + callLink);
            startActivity(Intent.createChooser(share, "Share Call Link"));
        });
        root.addView(btnShare);

        com.google.android.material.button.MaterialButton btnJoinNow = new com.google.android.material.button.MaterialButton(requireContext());
        btnJoinNow.setText("📞 Start Call Room Now");
        btnJoinNow.setCornerRadius((int) (12 * dp));
        btnJoinNow.setOnClickListener(v -> {
            sheet.dismiss();
            Intent intent = new Intent(getContext(), CallActivity.class);
            intent.putExtra(CallActivity.EXTRA_CALL_ID, "room_" + token);
            intent.putExtra(CallActivity.EXTRA_GROUP_ID, "room_" + token);
            intent.putExtra(CallActivity.EXTRA_GROUP_NAME, "Call Room #" + token);
            intent.putExtra(CallActivity.EXTRA_CALL_TYPE, "video");
            intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
            startActivity(intent);
        });
        root.addView(btnJoinNow);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showGroupCallPickerDialog() {
        if (getContext() == null) return;
        List<com.m3.veil.model.Chat> allChats = EncryptedChatDatabase.getInstance(requireContext()).getAllChats();
        List<com.m3.veil.model.Chat> candidates = new ArrayList<>();
        if (allChats != null) {
            for (com.m3.veil.model.Chat c : allChats) {
                if (c != null && !c.isBlocked() && c.getName() != null && !c.getName().trim().isEmpty()) {
                    candidates.add(c);
                }
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(getContext(), "Start a chat with contacts first to launch a group call", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(getContext(), NewChatActivity.class));
            return;
        }

        String[] names = new String[candidates.size()];
        boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getName();
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("👥 Select Peers for Group Call")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("📹 Video Call", (dialog, which) -> launchSelectedGroupCall(candidates, checked, "video"))
                .setNeutralButton("📞 Voice Call", (dialog, which) -> launchSelectedGroupCall(candidates, checked, "audio"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchSelectedGroupCall(List<com.m3.veil.model.Chat> candidates, boolean[] checked, String type) {
        List<String> selectedIds = new ArrayList<>();
        List<String> selectedNames = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (checked[i]) {
                com.m3.veil.model.Chat c = candidates.get(i);
                String peerId = c.getId();
                if (c.getParticipants() != null) {
                    for (String p : c.getParticipants()) {
                        if (p != null && !p.equals(currentUserId)) {
                            peerId = p;
                            break;
                        }
                    }
                }
                selectedIds.add(peerId);
                selectedNames.add(c.getName());
            }
        }
        if (selectedIds.isEmpty()) {
            Toast.makeText(getContext(), "Select at least 1 contact", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(getContext(), CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CONTACT_ID, selectedIds.get(0));
        intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, selectedNames.get(0));
        intent.putExtra(CallActivity.EXTRA_GROUP_ID, "grp_call_" + UUID.randomUUID().toString().substring(0, 6));
        intent.putExtra(CallActivity.EXTRA_GROUP_NAME, "Group Call (" + (selectedIds.size() + 1) + " peers)");
        intent.putExtra(CallActivity.EXTRA_PARTICIPANT_IDS, selectedIds.toArray(new String[0]));
        intent.putExtra(CallActivity.EXTRA_CALL_TYPE, type);
        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
        startActivity(intent);
    }

    private void startRandomPeerVoiceCall() {
        loadingBar.setVisibility(View.VISIBLE);
        FirebaseFirestore.getInstance().collection("users")
                .limit(20)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (getContext() == null || !isAdded()) return;
                    loadingBar.setVisibility(View.GONE);

                    List<User> peers = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        User u = doc.toObject(User.class);
                        if (u != null && u.getUserId() != null && !u.getUserId().equals(currentUserId)) {
                            peers.add(u);
                        }
                    }

                    if (!peers.isEmpty()) {
                        User chosen = peers.get(new Random().nextInt(peers.size()));
                        String name = chosen.getDisplayName() != null ? chosen.getDisplayName() : chosen.getUsername();
                        Toast.makeText(getContext(), "🎲 Connecting encrypted call with @" + chosen.getUsername() + "...", Toast.LENGTH_SHORT).show();

                        Intent intent = new Intent(getContext(), CallActivity.class);
                        intent.putExtra(CallActivity.EXTRA_CONTACT_ID, chosen.getUserId());
                        intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, name);
                        intent.putExtra(CallActivity.EXTRA_CALL_TYPE, "audio");
                        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
                        startActivity(intent);
                    } else {
                        Toast.makeText(getContext(), "No other peers currently available for random call", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    if (getContext() == null || !isAdded()) return;
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Network notice: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    public void confirmClearAllCalls() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        sheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvTitle = sheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = sheet.findViewById(R.id.tvDestructiveMessage);
        View btnConfirm = sheet.findViewById(R.id.btnDestructiveConfirm);
        View btnCancel = sheet.findViewById(R.id.btnDestructiveCancel);

        if (tvTitle != null) tvTitle.setText("Clear Call Vault?");
        if (tvMsg != null) tvMsg.setText("Permanently delete all call history from your local encrypted vault? This action cannot be reversed.");
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                sheet.dismiss();
                EncryptedChatDatabase.getInstance(requireContext()).clearAllCalls();
                loadLocalCalls();
                Toast.makeText(getContext(), "Call history cleared", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }
        sheet.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLocalCalls();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getContext() != null) {
            EncryptedChatDatabase.getInstance(requireContext()).removeListener(dbListener);
        }
    }

    private void setupRecyclerView() {
        callList = new ArrayList<>();
        callsAdapter = new CallsAdapter(callList, currentUserId, this);
        callsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        callsRecyclerView.setAdapter(callsAdapter);

        // Swipe-to-delete call log
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                if (pos >= 0 && pos < callList.size()) {
                    CallRecord removed = callList.get(pos);
                    EncryptedChatDatabase.getInstance(requireContext()).deleteCall(removed.getId());
                    loadLocalCalls();

                    if (getView() != null) {
                        Snackbar.make(getView(), "Call log deleted", Snackbar.LENGTH_LONG)
                                .setAction("Undo", v -> {
                                    EncryptedChatDatabase.getInstance(requireContext()).saveCallRecord(removed);
                                    loadLocalCalls();
                                })
                                .show();
                    }
                }
            }
        };
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(callsRecyclerView);
    }

    private void loadLocalCalls() {
        if (getContext() == null) return;
        loadingBar.setVisibility(View.GONE);
        List<CallRecord> local = EncryptedChatDatabase.getInstance(requireContext()).getAllCalls();
        callList.clear();
        if (local != null) {
            callList.addAll(local);
        }
        callsAdapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (callList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            callsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            callsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onCallClick(CallRecord call) {
        startCall(call);
    }

    @Override
    public void onCallBackClick(CallRecord call) {
        startCall(call);
    }

    @Override
    public void onCallLongClick(CallRecord call) {
        if (call == null || getContext() == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        sheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvTitle = sheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = sheet.findViewById(R.id.tvDestructiveMessage);
        View btnConfirm = sheet.findViewById(R.id.btnDestructiveConfirm);
        View btnCancel = sheet.findViewById(R.id.btnDestructiveCancel);

        if (tvTitle != null) tvTitle.setText("Delete Call Record?");
        if (tvMsg != null) tvMsg.setText("Remove this encrypted call entry from your local history?");
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                sheet.dismiss();
                EncryptedChatDatabase.getInstance(requireContext()).deleteCall(call.getId());
                loadLocalCalls();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> sheet.dismiss());
        }
        sheet.show();
    }

    private static boolean isSelfName(String name) {
        if (name == null) return true;
        String clean = name.trim();
        return clean.isEmpty() || "Me".equalsIgnoreCase(clean) || "You".equalsIgnoreCase(clean)
                || "local_user".equalsIgnoreCase(clean) || "null".equalsIgnoreCase(clean);
    }

    private void startCall(CallRecord call) {
        if (call == null || getContext() == null) return;
        String myUid = (currentUserId != null && !currentUserId.isEmpty() && !"local_user".equals(currentUserId))
                ? currentUserId : (FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "local_user");

        boolean isOutgoing = "outgoing".equalsIgnoreCase(call.getStatus())
                || (call.getCallerId() != null && call.getCallerId().equals(myUid))
                || isSelfName(call.getCallerName());

        String targetId = call.getPeerId();
        if (targetId == null || targetId.isEmpty() || targetId.equals(myUid) || "local_user".equals(targetId)) {
            targetId = isOutgoing ? call.getReceiverId() : call.getCallerId();
            if (targetId == null || targetId.isEmpty() || targetId.equals(myUid) || "local_user".equals(targetId)) {
                targetId = isOutgoing ? call.getCallerId() : call.getReceiverId();
            }
        }

        String targetName = call.getPeerName();
        if (isSelfName(targetName)) {
            targetName = isOutgoing ? call.getReceiverName() : call.getCallerName();
        }
        if (isSelfName(targetName)) {
            targetName = isOutgoing ? call.getCallerName() : call.getReceiverName();
        }
        if (isSelfName(targetName) && targetId != null && !targetId.isEmpty() && !targetId.equals(myUid) && !"local_user".equals(targetId)) {
            com.m3.veil.model.Chat c = EncryptedChatDatabase.getInstance(requireContext()).getChat(targetId);
            if (c != null && c.getName() != null && !isSelfName(c.getName())) {
                targetName = c.getName().trim();
            }
        }
        if (isSelfName(targetName)) {
            targetName = "Veil Contact";
        }

        Intent intent = new Intent(getContext(), CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CONTACT_ID, targetId);
        intent.putExtra(CallActivity.EXTRA_CONTACT_NAME, targetName);
        intent.putExtra(CallActivity.EXTRA_CALL_TYPE, call.getCallType() != null ? call.getCallType() : "audio");
        intent.putExtra(CallActivity.EXTRA_IS_INCOMING, false);
        startActivity(intent);
    }
}
