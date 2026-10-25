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
        sheet.setContentView(R.layout.dialog_call_hub_sheet);

        View cardDirect = sheet.findViewById(R.id.cardCallHubDirect);
        View cardRoulette = sheet.findViewById(R.id.cardCallHubRoulette);
        View cardClear = sheet.findViewById(R.id.cardCallHubClear);

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
        sheet.show();
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
