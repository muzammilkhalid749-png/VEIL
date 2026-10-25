package com.m3.veil.ui.main.fragments;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.Persona;
import com.m3.veil.ui.chat.ArchivedChatsActivity;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.chat.NewChatActivity;
import com.m3.veil.ui.chat.NewGroupActivity;
import com.m3.veil.ui.main.adapters.ChatsAdapter;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.utils.VeilFirestoreUtils;
import com.m3.veil.webrtc.WebRtcManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment implements ChatsAdapter.OnChatClickListener {

    private RecyclerView chatsRecyclerView;
    private View emptyState;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private com.google.android.material.button.MaterialButton startChatButton;
    private ProgressBar loadingBar;
    private View archivedRow;
    private TextView archivedCount;
    private View archivedDivider;
    private View secretVaultRow;
    private TextView secretVaultCount;
    private View secretVaultDivider;
    private com.google.firebase.firestore.ListenerRegistration groupsListenerRegistration;
    private com.google.firebase.firestore.ListenerRegistration directChatsListenerRegistration;

    private TextView btnActivePersona;
    private TextView tvMeshStatusPill;
    private ImageButton btnQuickNewGroup;
    private ImageButton btnNotificationsBell;
    private EditText etSearchChats;
    private ImageView btnClearSearch;

    private TextView chipFilterAll, chipFilterUnread, chipFilterGroups,
            chipFilterVanish, chipFilterPinned, chipFilterSecret, chipFilterStatus;

    // WhatsApp Status tray & vertical feed
    private View statusTraySection;
    private LinearLayout statusTilesContainer;
    private View statusFeedScrollView;
    private LinearLayout statusFeedContainer;
    private final com.m3.veil.ui.main.status.WhatsAppStatusUi.StatusActionListener statusActionListener =
            new com.m3.veil.ui.main.status.WhatsAppStatusUi.StatusActionListener() {
                @Override
                public void onReplyToStatus(VeilFeatureManager.StatusUpdate status, String replyText) {
                    openStatusReplyChat(status, replyText);
                }

                @Override
                public void onStatusesChanged() {
                    renderStatusTray();
                }
            };

    private ChatsAdapter chatsAdapter;
    private List<Chat> allLocalChats = new ArrayList<>();
    private List<Chat> displayedChats = new ArrayList<>();
    private String currentUserId = "";
    private String activeFilter = "all"; // "all", "unread", "groups", "vanish", "pinned", "secret"
    private String searchQuery = "";
    private boolean isSecretVaultUnlocked = false;

    private final androidx.activity.result.ActivityResultLauncher<Intent> statusMediaLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null && getContext() != null) {
                            Intent data = result.getData();
                            String editedPath = data.getStringExtra(com.m3.veil.ui.chat.MediaEditActivity.RESULT_EDITED_FILE_PATH);
                            String mediaType = data.getStringExtra(com.m3.veil.ui.chat.MediaEditActivity.RESULT_MEDIA_TYPE);
                            String caption = data.getStringExtra(com.m3.veil.ui.chat.MediaEditActivity.RESULT_CAPTION);
                            if (editedPath != null && !editedPath.isEmpty()) {
                                publishMediaStatus(editedPath, mediaType != null ? mediaType : "image", caption);
                            }
                        }
                    });

    public String getActiveFilter() {
        return activeFilter;
    }

    private final EncryptedChatDatabase.DatabaseChangeListener dbListener = new EncryptedChatDatabase.DatabaseChangeListener() {
        @Override
        public void onMessagesUpdated(String chatId) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
        }

        @Override
        public void onChatsUpdated() {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
        }

        @Override
        public void onStatusesUpdated() {
            if (isAdded() && getActivity() != null) requireActivity().runOnUiThread(() -> renderStatusTray());
        }
    };

    private final com.m3.veil.manager.StatusController.StatusObserver statusObserver =
            () -> {
                if (isAdded() && getActivity() != null) requireActivity().runOnUiThread(() -> renderStatusTray());
            };

    private final com.m3.veil.manager.VeilMessagesController.MessagesObserver messagesObserver =
            new com.m3.veil.manager.VeilMessagesController.MessagesObserver() {
                @Override
                public void onDialogsLoaded(java.util.List<Chat> dialogs) {
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
                    }
                }

                @Override
                public void onMessageReceived(String dialogId, Message message) {
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
                    }
                }

                @Override
                public void onMessageStatusChanged(String dialogId, String messageId, boolean delivered, boolean read) {
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
                    }
                }

                @Override
                public void onPeerTyping(String dialogId, String peerId, boolean isTyping) {
                    if (!isAdded() || getActivity() == null) return;
                    requireActivity().runOnUiThread(() -> {
                        for (Chat c : displayedChats) {
                            if (!c.isGroup() && c.getParticipants() != null && c.getParticipants().contains(peerId)) {
                                c.setTyping(isTyping);
                            }
                        }
                        if (chatsAdapter != null) {
                            chatsAdapter.notifyDataSetChanged();
                        }
                    });
                }
            };

    private final WebRtcManager.P2PEventListener p2pListener = new WebRtcManager.P2PEventListener() {
        @Override
        public void onMessageReceived(String peerId, Message message) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
        }

        @Override
        public void onMessageStatusChanged(String messageId, boolean delivered, boolean read) {
            if (isAdded()) requireActivity().runOnUiThread(() -> loadLocalEncryptedChats());
        }

        @Override
        public void onPeerTyping(String peerId, boolean isTyping) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                for (Chat c : displayedChats) {
                    if (!c.isGroup() && c.getParticipants() != null && c.getParticipants().contains(peerId)) {
                        c.setTyping(isTyping);
                    }
                }
                if (chatsAdapter != null) {
                    chatsAdapter.notifyDataSetChanged();
                }
            });
        }

        @Override
        public void onPeerConnected(String peerId) {
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    updatePersonaAndMeshHeader();
                    if (chatsAdapter != null) chatsAdapter.notifyDataSetChanged();
                });
            }
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    updatePersonaAndMeshHeader();
                    if (chatsAdapter != null) chatsAdapter.notifyDataSetChanged();
                });
            }
        }

        @Override
        public void onCallEstablished(String peerId) {}

        @Override
        public void onCallEnded(String peerId) {}
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } else {
            currentUserId = "";
        }

        initializeViews(view);
        setupRecyclerView();
        setupSearchAndFilters();
        updatePersonaAndMeshHeader();
        loadLocalEncryptedChats();

        EncryptedChatDatabase.getInstance(requireContext()).addListener(dbListener);
        WebRtcManager.getInstance(requireContext()).addEventListener(p2pListener);
        com.m3.veil.manager.VeilMessagesController.getInstance(requireContext()).addObserver(messagesObserver);
        com.m3.veil.manager.StatusController.getInstance(requireContext()).addObserver(statusObserver);

        return view;
    }

    private void initializeViews(View view) {
        chatsRecyclerView = view.findViewById(R.id.chatsRecyclerView);
        emptyState = view.findViewById(R.id.emptyState);
        tvEmptyTitle = view.findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = view.findViewById(R.id.tvEmptySubtitle);
        loadingBar = view.findViewById(R.id.loadingBar);
        archivedRow = view.findViewById(R.id.archivedRow);
        archivedCount = view.findViewById(R.id.archivedCount);
        archivedDivider = view.findViewById(R.id.archivedDivider);
        secretVaultRow = view.findViewById(R.id.secretVaultRow);
        secretVaultCount = view.findViewById(R.id.secretVaultCount);
        secretVaultDivider = view.findViewById(R.id.secretVaultDivider);

        if (secretVaultRow != null) {
            secretVaultRow.setVisibility(View.GONE);
        }
        if (secretVaultDivider != null) {
            secretVaultDivider.setVisibility(View.GONE);
        }

        btnActivePersona = view.findViewById(R.id.btnActivePersona);
        tvMeshStatusPill = view.findViewById(R.id.tvMeshStatusPill);
        btnQuickNewGroup = view.findViewById(R.id.btnQuickNewGroup);
        if (btnQuickNewGroup != null) {
            btnQuickNewGroup.setVisibility(View.VISIBLE);
            btnQuickNewGroup.setOnClickListener(v -> startActivity(new Intent(getContext(), NewGroupActivity.class)));
        }
        btnNotificationsBell = view.findViewById(R.id.btnNotificationsBell);
        if (btnNotificationsBell != null) {
            btnNotificationsBell.setOnClickListener(v -> startActivity(new Intent(getContext(), com.m3.veil.ui.notifications.NotificationsActivity.class)));
        }
        etSearchChats = view.findViewById(R.id.etSearchChats);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);

        chipFilterAll = view.findViewById(R.id.chipFilterAll);
        chipFilterUnread = view.findViewById(R.id.chipFilterUnread);
        chipFilterGroups = view.findViewById(R.id.chipFilterGroups);
        chipFilterVanish = view.findViewById(R.id.chipFilterVanish);
        chipFilterPinned = view.findViewById(R.id.chipFilterPinned);
        chipFilterSecret = view.findViewById(R.id.chipFilterSecret);
        chipFilterStatus = view.findViewById(R.id.chipFilterStatus);

        statusTraySection = view.findViewById(R.id.statusTraySection);
        statusTilesContainer = view.findViewById(R.id.statusTilesContainer);
        statusFeedScrollView = view.findViewById(R.id.statusFeedScrollView);
        statusFeedContainer = view.findViewById(R.id.statusFeedContainer);
        View btnAddMyStatusHeader = view.findViewById(R.id.btnAddMyStatusHeader);
        if (btnAddMyStatusHeader != null) {
            btnAddMyStatusHeader.setOnClickListener(v -> openStatusCreationChooser());
        }

        archivedRow.setOnClickListener(v -> startActivity(new Intent(getContext(), ArchivedChatsActivity.class)));
        startChatButton = view.findViewById(R.id.startChatButton);
        if (startChatButton != null) {
            startChatButton.setOnClickListener(v -> startActivity(new Intent(getContext(), NewChatActivity.class)));
        }

        if (btnQuickNewGroup != null) {
            btnQuickNewGroup.setOnClickListener(v -> startActivity(new Intent(getContext(), NewGroupActivity.class)));
        }

        if (btnActivePersona != null) {
            btnActivePersona.setOnClickListener(v -> showPersonaSwitcherDialog());
        }

        if (tvMeshStatusPill != null) {
            tvMeshStatusPill.setOnClickListener(v -> showQuickStealthAndMeshDialog());
        }
    }

    private void setupRecyclerView() {
        displayedChats = new ArrayList<>();
        chatsAdapter = new ChatsAdapter(displayedChats, this);

        chatsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        chatsRecyclerView.setHasFixedSize(true);
        chatsRecyclerView.setItemViewCacheSize(25);
        chatsRecyclerView.setRecycledViewPool(com.m3.veil.VeilApplication.getSharedRecycledViewPool());
        if (chatsRecyclerView.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) chatsRecyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        chatsRecyclerView.setAdapter(chatsAdapter);

        // WhatsApp-style Swipe Right (Pin/Unpin) and Swipe Left (Archive)
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                Chat chat = chatsAdapter.getChatAt(pos);
                if (chat == null || getContext() == null) {
                    chatsAdapter.notifyItemChanged(pos);
                    return;
                }

                if (direction == ItemTouchHelper.RIGHT) {
                    boolean newPinned = !chat.isPinned();
                    chat.setPinned(newPinned);
                    EncryptedChatDatabase.getInstance(requireContext()).updateChatPinStatus(chat.getId(), newPinned);
                    applyFilterAndDisplay();
                    Toast.makeText(getContext(), newPinned ? "📌 Chat pinned to top" : "Chat unpinned", Toast.LENGTH_SHORT).show();
                } else if (direction == ItemTouchHelper.LEFT) {
                    chat.setArchived(true);
                    EncryptedChatDatabase.getInstance(requireContext()).updateChatArchiveStatus(chat.getId(), true);
                    loadLocalEncryptedChats();
                    if (getView() != null) {
                        Snackbar.make(getView(), "📥 Chat archived in SQLCipher vault", Snackbar.LENGTH_LONG)
                                .setAction("Undo", v -> {
                                    chat.setArchived(false);
                                    EncryptedChatDatabase.getInstance(requireContext()).updateChatArchiveStatus(chat.getId(), false);
                                    loadLocalEncryptedChats();
                                }).show();
                    }
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && getContext() != null) {
                    View itemView = viewHolder.itemView;
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    if (dX > 0) {
                        paint.setColor(ContextCompat.getColor(getContext(), R.color.colorPrimaryContainer));
                        RectF bg = new RectF(itemView.getLeft(), itemView.getTop(),
                                itemView.getLeft() + dX, itemView.getBottom());
                        c.drawRect(bg, paint);
                    } else if (dX < 0) {
                        paint.setColor(ContextCompat.getColor(getContext(), R.color.colorSurfaceVariant));
                        RectF bg = new RectF(itemView.getRight() + dX, itemView.getTop(),
                                itemView.getRight(), itemView.getBottom());
                        c.drawRect(bg, paint);
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(chatsRecyclerView);

        chatsRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (Math.abs(dy) > 4 && getActivity() instanceof com.m3.veil.ui.main.MainActivity) {
                    ((com.m3.veil.ui.main.MainActivity) getActivity()).setBottomNavAndFabVisible(dy <= 0);
                }
            }
        });
    }

    private void setupSearchAndFilters() {
        if (etSearchChats != null) {
            etSearchChats.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchQuery = s != null ? s.toString().trim() : "";
                    if (btnClearSearch != null) {
                        btnClearSearch.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                    }

                    // Check Panic Safe Word trigger
                    String safeWord = VeilFeatureManager.getInstance(requireContext()).getSafeWord();
                    if (safeWord != null && !safeWord.isEmpty() && searchQuery.equalsIgnoreCase(safeWord)) {
                        etSearchChats.setText("");
                        EncryptedChatDatabase.getInstance(requireContext()).secureWipeAllData();
                        Toast.makeText(getContext(), "🚨 Panic Safe Word triggered: Local Vault wiped!", Toast.LENGTH_LONG).show();
                        loadLocalEncryptedChats();
                        return;
                    }

                    applyFilterAndDisplay();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (etSearchChats != null) etSearchChats.setText("");
            });
        }

        View.OnClickListener filterClickListener = v -> {
            int id = v.getId();
            if (id == R.id.chipFilterAll) {
                activeFilter = "all";
            } else if (id == R.id.chipFilterUnread) {
                activeFilter = "unread";
            } else if (id == R.id.chipFilterGroups) {
                activeFilter = "groups";
            } else if (id == R.id.chipFilterVanish) {
                activeFilter = "vanish";
            } else if (id == R.id.chipFilterPinned) {
                activeFilter = "pinned";
            } else if (id == R.id.chipFilterStatus) {
                activeFilter = "status";
            } else if (id == R.id.chipFilterSecret) {
                if (isSecretVaultUnlocked && "secret".equals(activeFilter)) {
                    isSecretVaultUnlocked = false;
                    activeFilter = "all";
                    updateFilterChipStyles();
                    applyFilterAndDisplay();
                    Toast.makeText(getContext(), "🔒 Secret Vault locked", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isSecretVaultUnlocked) {
                    promptSecretVaultUnlock();
                    return;
                }
                activeFilter = "secret";
            }
            updateFilterChipStyles();
            applyFilterAndDisplay();
        };

        if (chipFilterAll != null) chipFilterAll.setOnClickListener(filterClickListener);
        if (chipFilterUnread != null) chipFilterUnread.setOnClickListener(filterClickListener);
        if (chipFilterGroups != null) chipFilterGroups.setOnClickListener(filterClickListener);
        if (chipFilterVanish != null) chipFilterVanish.setOnClickListener(filterClickListener);
        if (chipFilterPinned != null) chipFilterPinned.setOnClickListener(filterClickListener);
        if (chipFilterSecret != null) chipFilterSecret.setOnClickListener(filterClickListener);
        if (chipFilterStatus != null) chipFilterStatus.setOnClickListener(filterClickListener);
    }

    private interface OnPinEnteredListener {
        void onPinEntered(String pin, com.google.android.material.bottomsheet.BottomSheetDialog dialog, com.google.android.material.textfield.TextInputLayout layout);
    }

    private void showPinAuthModal(String title, String subtitle, String hint, OnPinEnteredListener listener) {
        if (getContext() == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_pin_auth, null);
        dialog.setContentView(view);

        TextView tvTitle = view.findViewById(R.id.tvPinDialogTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvPinDialogSubtitle);
        com.google.android.material.textfield.TextInputLayout inputLayout = view.findViewById(R.id.pinInputLayout);
        com.google.android.material.textfield.TextInputEditText etPin = view.findViewById(R.id.etPin);
        View btnCancel = view.findViewById(R.id.btnCancelPin);
        View btnConfirm = view.findViewById(R.id.btnConfirmPin);

        tvTitle.setText(title);
        tvSubtitle.setText(subtitle);
        if (hint != null) inputLayout.setHint(hint);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String entered = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            listener.onPinEntered(entered, dialog, inputLayout);
        });

        dialog.show();
    }

    private void promptSecretVaultUnlock() {
        com.m3.veil.manager.VeilFeatureManager fm = com.m3.veil.manager.VeilFeatureManager.getInstance(requireContext());
        boolean hasPassword = fm.hasSecretVaultPassword() || fm.hasMasterPin();
        String title = hasPassword ? "🔒 Unlock Secret Vault" : "🔒 Set Secret Vault Password";
        String subtitle = hasPassword ? "Enter your Secret Vault password to reveal hidden conversations" : "Create a password (min 4 characters) for your Secret Vault";
        String hint = "Vault Password";

        showPinAuthModal(title, subtitle, hint, (pin, dialog, layout) -> {
            if (!hasPassword) {
                if (pin.length() < 4) {
                    layout.setError("Password must be at least 4 characters");
                    return;
                }
                fm.setSecretVaultPassword(pin);
                isSecretVaultUnlocked = true;
                activeFilter = "secret";
                updateFilterChipStyles();
                applyFilterAndDisplay();
                Toast.makeText(getContext(), "🔐 Secret Vault password set & unlocked", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } else {
                if (fm.verifySecretVaultPassword(pin)) {
                    isSecretVaultUnlocked = true;
                    activeFilter = "secret";
                    updateFilterChipStyles();
                    applyFilterAndDisplay();
                    Toast.makeText(getContext(), "🔓 Secret Vault unlocked", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    layout.setError("Invalid Secret Vault password. Please try again.");
                }
            }
        });
    }

    private void updateFilterChipStyles() {
        if (getContext() == null) return;
        styleChip(chipFilterAll, "all".equals(activeFilter));
        styleChip(chipFilterUnread, "unread".equals(activeFilter));
        styleChip(chipFilterGroups, "groups".equals(activeFilter));
        styleChip(chipFilterVanish, "vanish".equals(activeFilter));
        styleChip(chipFilterPinned, "pinned".equals(activeFilter));
        styleChip(chipFilterSecret, "secret".equals(activeFilter));
        styleChip(chipFilterStatus, "status".equals(activeFilter));
    }

    private void styleChip(TextView chip, boolean selected) {
        if (chip == null || getContext() == null) return;
        chip.setBackgroundResource(selected ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
        chip.setTextColor(ContextCompat.getColor(getContext(), selected ? R.color.colorPrimary : R.color.textSecondary));
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void updatePersonaAndMeshHeader() {
        if (getContext() == null) return;
        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());
        Persona active = fm.getActivePersona();
        if (btnActivePersona != null && active != null) {
            btnActivePersona.setText("🎭 " + active.getDisplayName() + " • " + active.getMood());
        }
        if (tvMeshStatusPill != null) {
            int peers = WebRtcManager.getInstance(requireContext()).getConnectedPeersCount();
            int score = fm.calculatePrivacyScore();
            boolean stealth = fm.isStealthTypingEnabled() || fm.isFakeOfflineEnabled();
            tvMeshStatusPill.setText((stealth ? "👻 Stealth" : "⚡ " + peers + " P2P") + " • 🛡️ " + score + "%");
        }
    }

    public void showPersonaSwitcherDialog() {
        if (getContext() == null) return;
        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());
        List<Persona> personas = fm.getPersonas();
        Persona current = fm.getActivePersona();

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_persona_switcher_sheet, null);
        dialog.setContentView(view);

        ViewGroup container = view.findViewById(R.id.personaListContainer);
        View btnAddNew = view.findViewById(R.id.btnAddNewPersona);
        float density = getResources().getDisplayMetrics().density;

        for (Persona p : personas) {
            boolean isSelected = current != null && p.getPersonaId().equals(current.getPersonaId());
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(requireContext());
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1.2f * density));
            card.setStrokeColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.colorPrimary : R.color.colorOutline));
            card.setRadius(14 * density);
            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.colorSurfaceVariant : R.color.colorSurface));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (10 * density);
            card.setLayoutParams(lp);

            LinearLayout itemLayout = new LinearLayout(requireContext());
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
            itemLayout.setPadding((int) (16 * density), (int) (14 * density), (int) (16 * density), (int) (14 * density));

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvName = new TextView(requireContext());
            tvName.setText((isSelected ? "✅ " : "🎭 ") + p.getDisplayName() + (p.isTemporary() ? " [Temp 24h]" : ""));
            tvName.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textPrimary));
            tvName.setTextSize(15);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView tvMood = new TextView(requireContext());
            tvMood.setText(p.getMood() + " • " + p.getBio());
            tvMood.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary));
            tvMood.setTextSize(12);

            textCol.addView(tvName);
            textCol.addView(tvMood);
            itemLayout.addView(textCol);
            card.addView(itemLayout);

            card.setOnClickListener(vCard -> {
                fm.setActivePersona(p.getPersonaId());
                updatePersonaAndMeshHeader();
                Toast.makeText(getContext(), "Switched persona to " + p.getDisplayName(), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            container.addView(card);
        }

        btnAddNew.setOnClickListener(v -> {
            dialog.dismiss();
            showCreatePersonaDialog();
        });

        dialog.show();
    }

    private void showCreatePersonaDialog() {
        if (getContext() == null) return;
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundResource(R.drawable.bottom_sheet_background);

        TextView tvTitle = new TextView(getContext());
        tvTitle.setText("➕ Create New Anonymous Persona");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(getContext(), R.color.textPrimary));
        layout.addView(tvTitle);

        TextView tvSub = new TextView(getContext());
        tvSub.setText("Zero-trace identity for chatting and posting anonymously");
        tvSub.setTextSize(13);
        tvSub.setTextColor(ContextCompat.getColor(getContext(), R.color.textSecondary));
        tvSub.setPadding(0, 4, 0, (int) (16 * getResources().getDisplayMetrics().density));
        layout.addView(tvSub);

        EditText etName = new EditText(getContext());
        etName.setHint("Persona Handle (e.g. @GhostProtocol)");
        etName.setTextColor(ContextCompat.getColor(getContext(), R.color.textPrimary));
        layout.addView(etName);

        EditText etMood = new EditText(getContext());
        etMood.setHint("Mood Badge (e.g. 🌙 Incognito, ⚡ Hyped)");
        etMood.setTextColor(ContextCompat.getColor(getContext(), R.color.textPrimary));
        layout.addView(etMood);

        com.google.android.material.button.MaterialButton btnCreate =
                new com.google.android.material.button.MaterialButton(getContext());
        btnCreate.setText("Create & Activate");
        btnCreate.setCornerRadius((int) (12 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        btnCreate.setLayoutParams(btnLp);
        btnCreate.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String mood = etMood.getText().toString().trim();
            if (!name.isEmpty()) {
                VeilFeatureManager.getInstance(requireContext()).addPersona(
                        name, "Custom zero-trace persona", mood.isEmpty() ? "🎭 Masked" : mood, "#4F46E5", false);
                updatePersonaAndMeshHeader();
                Toast.makeText(getContext(), "Persona created & activated!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } else {
                Toast.makeText(getContext(), "Please enter a handle", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnCreate);

        dialog.setContentView(layout);
        dialog.show();
    }

    private void showQuickStealthAndMeshDialog() {
        if (getContext() == null) return;
        VeilFeatureManager fm = VeilFeatureManager.getInstance(requireContext());

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_privacy_shield_sheet, null);
        dialog.setContentView(view);

        com.google.android.material.materialswitch.MaterialSwitch swStealth = view.findViewById(R.id.switchStealthTyping);
        com.google.android.material.materialswitch.MaterialSwitch swFakeOffline = view.findViewById(R.id.switchFakeOffline);
        com.google.android.material.materialswitch.MaterialSwitch swScreenshots = view.findViewById(R.id.switchBlockScreenshots);
        com.google.android.material.materialswitch.MaterialSwitch swReactions = view.findViewById(R.id.switchAnonymousReactions);
        TextView tvScore = view.findViewById(R.id.tvPrivacyScoreBadge);
        View btnClose = view.findViewById(R.id.btnCloseShield);

        swStealth.setChecked(fm.isStealthTypingEnabled());
        swFakeOffline.setChecked(fm.isFakeOfflineEnabled());
        swScreenshots.setChecked(fm.isFlagSecureEnabled());
        swReactions.setChecked(fm.isAnonymousReactionEnabled());

        Runnable updateScore = () -> {
            int score = 40;
            if (swStealth.isChecked()) score += 15;
            if (swFakeOffline.isChecked()) score += 15;
            if (swScreenshots.isChecked()) score += 15;
            if (swReactions.isChecked()) score += 15;
            tvScore.setText(score + "% Secure");
        };
        updateScore.run();

        swStealth.setOnCheckedChangeListener((b, isChecked) -> { fm.setStealthTypingEnabled(isChecked); updateScore.run(); });
        swFakeOffline.setOnCheckedChangeListener((b, isChecked) -> { fm.setFakeOfflineEnabled(isChecked); updateScore.run(); });
        swScreenshots.setOnCheckedChangeListener((b, isChecked) -> { fm.setFlagSecureEnabled(isChecked); updateScore.run(); });
        swReactions.setOnCheckedChangeListener((b, isChecked) -> { fm.setAnonymousReactionEnabled(isChecked); updateScore.run(); });

        btnClose.setOnClickListener(v -> {
            updatePersonaAndMeshHeader();
            dialog.dismiss();
            Toast.makeText(getContext(), "Privacy Shield applied", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void loadLocalEncryptedChats() {
        if (getContext() == null) return;
        loadingBar.setVisibility(View.GONE);
        List<Chat> localChats = EncryptedChatDatabase.getInstance(requireContext()).getAllChats(true);
        allLocalChats.clear();
        if (localChats != null) {
            allLocalChats.addAll(localChats);
        }
        updateFilterCounts();
        applyFilterAndDisplay();
        renderStatusTray();
    }

    private void updateFilterCounts() {
        int unreadTotal = 0;
        int secretTotal = 0;
        for (Chat c : allLocalChats) {
            if (c.isArchived()) continue;
            if (c.isSecret()) {
                secretTotal++;
                continue;
            }
            if (c.getUnreadCount() > 0) unreadTotal++;
        }
        if (chipFilterUnread != null) {
            chipFilterUnread.setText(unreadTotal > 0 ? "Unread (" + unreadTotal + ")" : "Unread");
        }
        if (chipFilterSecret != null) {
            chipFilterSecret.setText(secretTotal > 0 ? "🔒 Secret Vault (" + secretTotal + ")" : "🔒 Secret Vault");
        }
        if (secretVaultRow != null && secretVaultDivider != null) {
            secretVaultRow.setVisibility(View.GONE);
            secretVaultDivider.setVisibility(View.GONE);
        }
    }

    private void applyFilterAndDisplay() {
        displayedChats.clear();
        String q = searchQuery != null ? searchQuery.toLowerCase(Locale.ROOT) : "";

        for (Chat c : allLocalChats) {
            if (c.isArchived()) continue;
            if ("status".equals(activeFilter)) continue;

            // Secret Vault isolation: only show secret chats when "secret" filter is active
            if ("secret".equals(activeFilter)) {
                if (!c.isSecret()) continue;
            } else {
                if (c.isSecret()) continue;
            }

            if ("unread".equals(activeFilter) && c.getUnreadCount() <= 0) continue;
            if ("groups".equals(activeFilter) && !c.isGroup()) continue;
            if ("pinned".equals(activeFilter) && !c.isPinned()) continue;
            if ("vanish".equals(activeFilter)) {
                boolean hasVanish = c.getVanishTimer() > 0
                        || (c.getVanishMode() != null && !"off".equalsIgnoreCase(c.getVanishMode()) && !c.getVanishMode().isEmpty());
                if (!hasVanish) continue;
            }

            if (!q.isEmpty()) {
                String name = c.getName() != null ? c.getName().toLowerCase(Locale.ROOT) : "";
                String lastMsg = c.getLastMessage() != null ? c.getLastMessage().toLowerCase(Locale.ROOT) : "";
                if (!name.contains(q) && !lastMsg.contains(q)) continue;
            }

            displayedChats.add(c);
        }

        displayedChats.sort((c1, c2) -> {
            if (c1.isPinned() != c2.isPinned()) {
                return c1.isPinned() ? -1 : 1;
            }
            if (c1.getLastMessageTime() == null && c2.getLastMessageTime() == null) return 0;
            if (c1.getLastMessageTime() == null) return 1;
            if (c2.getLastMessageTime() == null) return -1;
            return c2.getLastMessageTime().compareTo(c1.getLastMessageTime());
        });

        chatsAdapter.notifyDataSetChanged();
        updateFilterCounts();
        updateEmptyState();
        updateArchivedHeader();
        updateStatusTrayVisibility();
    }

    private void updateStatusTrayVisibility() {
        if (statusTraySection == null) return;
        boolean isStatusActive = "status".equals(activeFilter);
        if (getActivity() instanceof com.m3.veil.ui.main.MainActivity) {
            ((com.m3.veil.ui.main.MainActivity) getActivity()).setStatusFilterActive(isStatusActive);
        }
        boolean showTray = searchQuery.isEmpty()
                && ("all".equals(activeFilter) || isStatusActive);
        statusTraySection.setVisibility(showTray ? View.VISIBLE : View.GONE);
        if (isStatusActive) {
            chatsRecyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            if (statusFeedScrollView != null) {
                statusFeedScrollView.setVisibility(View.VISIBLE);
                renderStatusFeedList();
            }
        } else {
            if (statusFeedScrollView != null) {
                statusFeedScrollView.setVisibility(View.GONE);
            }
        }
    }

    public void openStatusCreationChooser() {
        if (getContext() == null) return;
        com.m3.veil.ui.main.status.WhatsAppStatusUi.showStatusCreationChooser(
                requireContext(), statusActionListener, this::launchPhotoStatusPicker);
    }

    public void launchPhotoStatusPicker() {
        if (getContext() == null) return;
        com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet gallerySheet =
                new com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet();
        gallerySheet.setMediaGalleryListener(new com.m3.veil.ui.chat.sheets.MediaGalleryBottomSheet.MediaGalleryListener() {
            @Override
            public void onMediaSelectedToSend(Uri mediaUri, String mediaType, String caption) {
                if (getContext() == null || mediaUri == null) return;
                String localPath = resolveUriToLocalFile(mediaUri, mediaType);
                if (localPath != null) {
                    publishMediaStatus(localPath, mediaType != null ? mediaType : "image", caption);
                } else {
                    Toast.makeText(requireContext(), "Could not read selected media file", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onMediaSelectedToEdit(Uri mediaUri, String mediaType) {
                if (getContext() == null || mediaUri == null) return;
                Intent editIntent = new Intent(getContext(), com.m3.veil.ui.chat.MediaEditActivity.class);
                editIntent.putExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_MEDIA_URI, mediaUri);
                editIntent.putExtra(com.m3.veil.ui.chat.MediaEditActivity.EXTRA_MEDIA_TYPE, mediaType != null ? mediaType : "image");
                statusMediaLauncher.launch(editIntent);
            }

            @Override
            public void onCameraRequested() {
                if (getContext() == null) return;
                Intent camIntent = new Intent(getContext(), com.m3.veil.ui.chat.CameraGalleryActivity.class);
                statusMediaLauncher.launch(camIntent);
            }
        });
        gallerySheet.show(getChildFragmentManager(), "StatusPhotoGallery");
    }

    private void publishMediaStatus(String localFilePath, String mediaType, String caption) {
        if (getContext() == null || localFilePath == null) return;
        String safeCaption = caption != null ? caption : "";
        if ("video".equalsIgnoreCase(mediaType)) {
            com.m3.veil.manager.StatusController.getInstance(requireContext())
                    .postVideoStatus(localFilePath, safeCaption);
        } else {
            com.m3.veil.manager.StatusController.getInstance(requireContext())
                    .postPhotoStatus(localFilePath, safeCaption);
        }
        VeilFeatureManager.getInstance(requireContext())
                .addMyMediaStatus(localFilePath, safeCaption, "#1E293B");
        Toast.makeText(requireContext(), "✅ Status posted · encrypted & visible for 24h", Toast.LENGTH_SHORT).show();
        renderStatusTray();
    }

    private String resolveUriToLocalFile(Uri uri, String mediaType) {
        if (uri == null || getContext() == null) return null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                java.io.File f = new java.io.File(uri.getPath());
                if (f.exists() && f.length() > 0) return f.getAbsolutePath();
            }
            String ext = "video".equalsIgnoreCase(mediaType) ? ".mp4" : ".jpg";
            java.io.File statusCacheDir = new java.io.File(requireContext().getFilesDir(), "status_uploads");
            if (!statusCacheDir.exists()) statusCacheDir.mkdirs();
            java.io.File destFile = new java.io.File(statusCacheDir, "status_src_" + System.currentTimeMillis() + ext);
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
            }
            return destFile.exists() && destFile.length() > 0 ? destFile.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void renderStatusTray() {
        if (getContext() == null || statusTilesContainer == null) return;
        List<VeilFeatureManager.StatusUpdate> statuses =
                com.m3.veil.manager.StatusController.getInstance(requireContext()).getStatusUpdatesForUi();
        if (statuses == null || statuses.isEmpty()) {
            statuses = VeilFeatureManager.getInstance(requireContext()).getStatusUpdates(allLocalChats);
        }
        com.m3.veil.ui.main.status.WhatsAppStatusUi.renderTray(
                requireContext(), statusTilesContainer, statuses, statusActionListener, this::launchPhotoStatusPicker);

        if (statusFeedContainer != null) {
            com.m3.veil.ui.main.status.WhatsAppStatusUi.renderVerticalStatusList(
                    requireContext(), statusFeedContainer, statuses, statusActionListener,
                    this::launchPhotoStatusPicker, this::openStatusCreationChooser);
        }

        int unread = 0;
        java.util.Set<String> unreadAuthors = new java.util.HashSet<>();
        for (VeilFeatureManager.StatusUpdate s : statuses) {
            if (!s.isMine && !s.viewed && unreadAuthors.add(s.authorId)) unread++;
        }
        if (chipFilterStatus != null) {
            chipFilterStatus.setText(unread > 0 ? "🟢 Status (" + unread + ")" : "🟢 Status");
        }
    }

    private void renderStatusFeedList() {
        if (getContext() == null || statusFeedContainer == null) return;
        List<VeilFeatureManager.StatusUpdate> statuses =
                com.m3.veil.manager.StatusController.getInstance(requireContext()).getStatusUpdatesForUi();
        if (statuses == null || statuses.isEmpty()) {
            statuses = VeilFeatureManager.getInstance(requireContext()).getStatusUpdates(allLocalChats);
        }
        com.m3.veil.ui.main.status.WhatsAppStatusUi.renderVerticalStatusList(
                requireContext(), statusFeedContainer, statuses, statusActionListener,
                this::launchPhotoStatusPicker, this::openStatusCreationChooser);
    }

    private void openStatusReplyChat(VeilFeatureManager.StatusUpdate status, String replyText) {
        if (getContext() == null || status == null) return;
        Chat target = null;
        for (Chat c : allLocalChats) {
            if (c.getId().equals(status.authorId)
                    || (c.getParticipants() != null && c.getParticipants().contains(status.authorId))) {
                target = c;
                break;
            }
        }
        Intent intent = new Intent(getContext(), ChatActivity.class);
        if (target != null) {
            intent.putExtra("chatId", target.getId());
            intent.putExtra("recipientName", target.getName());
            String recipientId = "";
            if (target.getParticipants() != null) {
                for (String id : target.getParticipants()) {
                    if (id != null && !id.equals(currentUserId)) {
                        recipientId = id;
                        break;
                    }
                }
            }
            intent.putExtra("recipientId", !recipientId.isEmpty() ? recipientId : status.authorId);
        } else {
            intent.putExtra("chatId", status.authorId);
            intent.putExtra("recipientName", status.authorName);
            intent.putExtra("recipientId", status.authorId);
        }
        intent.putExtra("statusReplyText", replyText);
        String quote = (status.contentText != null && !status.contentText.trim().isEmpty())
                ? status.contentText.trim() : "📷 Status Photo";
        intent.putExtra("statusQuoteText", quote);
        startActivity(intent);
    }

    private Handler vanishTickerHandler;
    private Runnable vanishTickerRunnable;

    private void startVanishTicker() {
        stopVanishTicker();
        vanishTickerHandler = new Handler(Looper.getMainLooper());
        vanishTickerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded() && getContext() != null) {
                    EncryptedChatDatabase.getInstance(requireContext()).deleteExpiredVanishMessages();
                    if (vanishTickerHandler != null) {
                        vanishTickerHandler.postDelayed(this, 1000);
                    }
                }
            }
        };
        vanishTickerHandler.postDelayed(vanishTickerRunnable, 1000);
    }

    private void stopVanishTicker() {
        if (vanishTickerHandler != null && vanishTickerRunnable != null) {
            vanishTickerHandler.removeCallbacks(vanishTickerRunnable);
            vanishTickerHandler = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLocalEncryptedChats();
        if (getContext() != null && com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
            WebRtcManager.getInstance(requireContext()).fetchAndProcessOfflineInboxOnce(uid);
            if (groupsListenerRegistration == null) {
                groupsListenerRegistration = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("groups")
                        .whereArrayContains("participants", uid)
                        .addSnapshotListener((snapshots, error) -> {
                            if (error != null || snapshots == null || getContext() == null) return;
                            boolean updated = false;
                            java.util.Set<String> deletedGroups = requireContext()
                                    .getSharedPreferences("VeilPrefs", 0)
                                    .getStringSet("deleted_group_ids", new java.util.HashSet<>());
                            for (com.google.firebase.firestore.DocumentSnapshot doc : snapshots.getDocuments()) {
                                String gId = doc.getString("groupId");
                                if (gId == null) gId = doc.getId();
                                if (deletedGroups != null && deletedGroups.contains(gId)) continue;

                                String gName = doc.getString("groupName");
                                String lastMsg = doc.getString("lastMessage");
                                String chatType = doc.getString("type");
                                boolean isDirectChat = "direct".equalsIgnoreCase(chatType)
                                        || (!gId.startsWith("group_") && !gId.startsWith("channel_") && !gId.startsWith("lounge_") && gId.contains("_"));
                                if (isDirectChat) {
                                    chatType = "direct";
                                } else if (chatType == null || chatType.isEmpty()) {
                                    chatType = gId.startsWith("channel_") ? "channel" : "group";
                                }
                                String adminId = doc.getString("adminId");
                                String description = doc.getString("description");
                                String avatar = doc.getString("avatar");
                                Boolean isPublicObj = doc.getBoolean("isPublic");
                                String handle = doc.getString("handle");
                                @SuppressWarnings("unchecked")
                                java.util.List<String> parts = (java.util.List<String>) doc.get("participants");
                                @SuppressWarnings("unchecked")
                                java.util.List<String> adminIds = (java.util.List<String>) doc.get("adminIds");
                                com.google.firebase.Timestamp ts = doc.getTimestamp("timestamp");
                                if (ts == null) ts = com.google.firebase.Timestamp.now();

                                String peerId = null;
                                if (parts != null) {
                                    for (String p : parts) {
                                        if (p != null && !p.equals(uid)) {
                                            peerId = p;
                                            break;
                                        }
                                    }
                                }
                                if (peerId == null && gId.contains("_")) {
                                    String[] split = gId.split("_");
                                    if (split.length == 2) {
                                        peerId = split[0].equals(uid) ? split[1] : split[0];
                                    }
                                }

                                String chatTitle = gName;
                                if (isDirectChat) {
                                    if (chatTitle == null || chatTitle.isEmpty() || "Encrypted Group".equals(chatTitle) || "Direct Chat".equals(chatTitle)) {
                                        chatTitle = "Veil Contact";
                                    }
                                } else if (chatTitle == null || chatTitle.isEmpty()) {
                                    chatTitle = "channel".equals(chatType) ? "Broadcast Channel" : "Encrypted Group";
                                }

                                Chat existing = EncryptedChatDatabase.getInstance(requireContext()).getChat(gId);
                                if (existing == null) {
                                    Chat newGroup = new Chat(
                                            gId,
                                            chatTitle,
                                            lastMsg != null ? lastMsg : (isDirectChat ? "Direct chat created" : ("channel".equals(chatType) ? "Broadcast channel created" : "Group invite received")),
                                            ts,
                                            avatar,
                                            0,
                                            !isDirectChat,
                                            false,
                                            chatType,
                                            parts != null ? parts : new java.util.ArrayList<>()
                                    );
                                    if (adminId != null) newGroup.setAdminId(adminId);
                                    if (adminIds != null) newGroup.setAdminIds(adminIds);
                                    if (description != null) newGroup.setDescription(description);
                                    if (isPublicObj != null) newGroup.setPublic(isPublicObj);
                                    if (handle != null) newGroup.setHandle(handle);
                                    EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(newGroup);
                                    updated = true;
                                } else {
                                    boolean changed = false;
                                    if (chatTitle != null && !chatTitle.equals(existing.getName()) && !"Encrypted Group".equals(chatTitle)) {
                                        existing.setName(chatTitle);
                                        changed = true;
                                    }
                                    if (lastMsg != null && !lastMsg.equals(existing.getLastMessage())) {
                                        existing.setLastMessage(lastMsg);
                                        existing.setLastMessageTime(ts);
                                        changed = true;
                                    }
                                    if (parts != null) {
                                        existing.setParticipants(parts);
                                        changed = true;
                                    }
                                    if (adminId != null) existing.setAdminId(adminId);
                                    if (adminIds != null) existing.setAdminIds(adminIds);
                                    if (description != null) existing.setDescription(description);
                                    if (avatar != null) existing.setAvatar(avatar);
                                    if (isPublicObj != null) existing.setPublic(isPublicObj);
                                    if (handle != null) existing.setHandle(handle);
                                    existing.setType(chatType);
                                    existing.setGroup(!isDirectChat);
                                    if (changed) {
                                        EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(existing);
                                        updated = true;
                                    }
                                }

                                if (isDirectChat && peerId != null && (chatTitle == null || "Veil User".equals(chatTitle))) {
                                    final String fPeerId = peerId;
                                    final String fGId = gId;
                                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                            .collection("users").document(fPeerId).get()
                                            .addOnSuccessListener(userDoc -> {
                                                if (userDoc != null && userDoc.exists() && getContext() != null) {
                                                    String dName = userDoc.getString("displayName");
                                                    if (dName == null || dName.isEmpty()) dName = userDoc.getString("username");
                                                    if (dName != null && !dName.isEmpty()) {
                                                        Chat c = EncryptedChatDatabase.getInstance(requireContext()).getChat(fGId);
                                                        if (c != null && !dName.equals(c.getName())) {
                                                            c.setName(dName);
                                                            EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(c);
                                                            if (isAdded()) loadLocalEncryptedChats();
                                                        }
                                                    }
                                                }
                                            });
                                }

                                // Establish P2P connections with group participants
                                if (parts != null && getContext() != null) {
                                    for (String pId : parts) {
                                        if (pId != null && !pId.equals(uid)) {
                                            WebRtcManager.getInstance(requireContext()).initiateConnection(pId);
                                        }
                                    }
                                }

                                // Sync recent group messages from Firestore so offline/multi-device members receive everything
                                final String finalGroupId = gId;
                                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                        .collection("groups")
                                        .document(finalGroupId)
                                        .collection("messages")
                                        .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                                        .limit(40)
                                        .get()
                                        .addOnSuccessListener(msgSnaps -> {
                                            if (msgSnaps == null || getContext() == null) return;
                                            boolean anyNewMsg = false;
                                            for (com.google.firebase.firestore.DocumentSnapshot mDoc : msgSnaps.getDocuments()) {
                                                try {
                                                    String mId = mDoc.getString("id");
                                                if (mId == null) mId = mDoc.getId();
                                                com.m3.veil.model.Message existingMsg = EncryptedChatDatabase.getInstance(requireContext()).getMessageById(mId);
                                                String mType = mDoc.getString("type");
                                                String mText = mDoc.getString("text");
                                                if (mText != null && mText.startsWith("ENC:v1:")) {
                                                    mText = EncryptionManager.decryptGroupPayload(finalGroupId, mText);
                                                }
                                                Boolean isEditedObj = mDoc.getBoolean("edited");
                                                String pollJson = mDoc.getString("pollJson");
                                                if (pollJson != null && pollJson.startsWith("ENC:v1:")) {
                                                    pollJson = EncryptionManager.decryptGroupPayload(finalGroupId, pollJson);
                                                }
                                                String sId = mDoc.getString("senderId");
                                                if (existingMsg == null) {
                                                    String sName = mDoc.getString("senderName");
                                                    com.google.firebase.Timestamp mTs = VeilFirestoreUtils.getSafeTimestamp(mDoc, "timestamp", com.google.firebase.Timestamp.now());
                                                    com.m3.veil.model.Message m = new com.m3.veil.model.Message(
                                                            mId,
                                                            sId != null ? sId : "",
                                                            finalGroupId,
                                                            mText != null ? mText : "",
                                                            mTs,
                                                            mType != null ? mType : "text"
                                                    );
                                                    m.setChatId(finalGroupId);
                                                    m.setSenderName(sName);
                                                    m.setReplyToMessageId(mDoc.getString("replyToMessageId"));
                                                    m.setReplySenderName(mDoc.getString("replySenderName"));
                                                    m.setPollJson(pollJson);
                                                    if (Boolean.TRUE.equals(isEditedObj)) m.setEdited(true);
                                                    m.setDelivered(true);
                                                    m.setPending(false);
                                                    boolean isFromMe = uid.equals(sId);
                                                    m.setRead(isFromMe);
                                                    EncryptedChatDatabase.getInstance(requireContext()).saveMessage(finalGroupId, m);
                                                    anyNewMsg = true;

                                                    if (!isFromMe) {
                                                        java.util.Map<String, Object> ack = new java.util.HashMap<>();
                                                        ack.put("delivered", true);
                                                        mDoc.getReference().set(ack, com.google.firebase.firestore.SetOptions.merge());
                                                        if (sId != null && !sId.isEmpty()) {
                                                            WebRtcManager.getInstance(requireContext()).sendReceipt(sId, mId, true, false);
                                                        }
                                                    }
                                                } else {
                                                    boolean isFromMe = uid.equals(sId);
                                                    if (!isFromMe && !existingMsg.isDelivered()) {
                                                        existingMsg.setDelivered(true);
                                                        EncryptedChatDatabase.getInstance(requireContext())
                                                                .updateMessageStatus(mId, true, existingMsg.isRead());
                                                        java.util.Map<String, Object> ack = new java.util.HashMap<>();
                                                        ack.put("delivered", true);
                                                        mDoc.getReference().set(ack, com.google.firebase.firestore.SetOptions.merge());
                                                    }
                                                    boolean msgChanged = false;
                                                    if ("deleted".equals(mType) && !"deleted".equals(existingMsg.getType())) {
                                                        EncryptedChatDatabase.getInstance(requireContext()).markMessageDeleted(finalGroupId, mId);
                                                        msgChanged = true;
                                                    } else if (Boolean.TRUE.equals(isEditedObj) && mText != null && !mText.equals(existingMsg.getText())) {
                                                        EncryptedChatDatabase.getInstance(requireContext()).updateMessageText(finalGroupId, mId, mText);
                                                        msgChanged = true;
                                                    }
                                                    if (pollJson != null && !pollJson.equals(existingMsg.getPollJson())) {
                                                        EncryptedChatDatabase.getInstance(requireContext()).updateMessagePollJson(finalGroupId, mId, pollJson);
                                                        msgChanged = true;
                                                    }
                                                    if (msgChanged) anyNewMsg = true;
                                                }
                                                } catch (Exception docEx) {
                                                    android.util.Log.w("HomeFragment", "Error processing group message doc: " + docEx.getMessage());
                                                }
                                            }
                                            if (anyNewMsg && isAdded()) {
                                                loadLocalEncryptedChats();
                                            }
                                        });
                            }
                            if (updated) {
                                loadLocalEncryptedChats();
                            }
                        });
            }

            if (directChatsListenerRegistration == null) {
                directChatsListenerRegistration = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("chats")
                        .whereArrayContains("participants", uid)
                        .addSnapshotListener((snapshots, error) -> {
                            if (error != null || snapshots == null || getContext() == null) return;
                            boolean updated = false;
                            for (com.google.firebase.firestore.DocumentSnapshot doc : snapshots.getDocuments()) {
                                String cId = doc.getString("chatId");
                                if (cId == null) cId = doc.getId();
                                @SuppressWarnings("unchecked")
                                java.util.List<String> parts = (java.util.List<String>) doc.get("participants");
                                String peerId = null;
                                if (parts != null) {
                                    for (String p : parts) {
                                        if (p != null && !p.equals(uid)) {
                                            peerId = p;
                                            break;
                                        }
                                    }
                                }
                                if (peerId == null && cId.contains("_")) {
                                    String[] split = cId.split("_");
                                    if (split.length == 2) {
                                        peerId = split[0].equals(uid) ? split[1] : split[0];
                                    }
                                }
                                if (peerId == null) continue;

                                String lastMsg = doc.getString("lastMessage");
                                com.google.firebase.Timestamp ts = doc.getTimestamp("timestamp");
                                if (ts == null) ts = com.google.firebase.Timestamp.now();

                                Chat existing = EncryptedChatDatabase.getInstance(requireContext()).getChat(cId);
                                if (existing == null) {
                                    Chat newChat = new Chat(
                                            cId,
                                            "Encrypted Chat",
                                            lastMsg != null ? lastMsg : "Direct chat created",
                                            ts,
                                            null,
                                            0,
                                            false,
                                            false,
                                            "direct",
                                            parts != null ? parts : new java.util.ArrayList<>()
                                    );
                                    EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(newChat);
                                    updated = true;
                                }

                                // Sync recent messages for this 1-on-1 chat
                                final String finalChatId = cId;
                                final String finalPeerId = peerId;
                                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                        .collection("chats")
                                        .document(finalChatId)
                                        .collection("messages")
                                        .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                                        .limit(30)
                                        .get()
                                        .addOnSuccessListener(msgSnaps -> {
                                            if (msgSnaps == null || getContext() == null) return;
                                            boolean anyNew = false;
                                            for (com.google.firebase.firestore.DocumentSnapshot mDoc : msgSnaps.getDocuments()) {
                                                try {
                                                    String mId = mDoc.getString("id");
                                                    if (mId == null) mId = mDoc.getId();
                                                    com.m3.veil.model.Message existingMsg = EncryptedChatDatabase.getInstance(requireContext()).getMessageById(mId);
                                                    String mType = mDoc.getString("type");
                                                    String mText = mDoc.getString("text");
                                                    if (mText != null && mText.startsWith("ENC:v1:")) {
                                                        mText = EncryptionManager.decryptGroupPayload(finalChatId, mText);
                                                    }
                                                    String sId = mDoc.getString("senderId");
                                                    String sName = mDoc.getString("senderName");
                                                    com.google.firebase.Timestamp mTs = VeilFirestoreUtils.getSafeTimestamp(mDoc, "timestamp", com.google.firebase.Timestamp.now());

                                                    if (existingMsg == null) {
                                                        com.m3.veil.model.Message m = new com.m3.veil.model.Message(
                                                                mId,
                                                                sId != null ? sId : "",
                                                                finalPeerId,
                                                                mText != null ? mText : "",
                                                                mTs,
                                                                mType != null ? mType : "text"
                                                        );
                                                        m.setChatId(finalChatId);
                                                        m.setSenderName(sName);
                                                        m.setDelivered(true);
                                                        m.setPending(false);
                                                        m.setRead(false);
                                                        EncryptedChatDatabase.getInstance(requireContext()).saveMessage(finalChatId, m);

                                                        // Acknowledge delivery on Firestore so sender sees double ticks immediately!
                                                        if (sId != null && !sId.equals(uid)) {
                                                            mDoc.getReference().update("delivered", true);
                                                        }
                                                        anyNew = true;
                                                    } else {
                                                        // Sync delivery/read status back to sender's local database
                                                        boolean docDelivered = VeilFirestoreUtils.getSafeBoolean(mDoc, "delivered", false);
                                                        boolean docRead = VeilFirestoreUtils.getSafeBoolean(mDoc, "read", false);
                                                        if (sId != null && sId.equals(uid)) {
                                                            if ((!existingMsg.isDelivered() && docDelivered) || (!existingMsg.isRead() && docRead)) {
                                                                EncryptedChatDatabase.getInstance(requireContext())
                                                                        .updateMessageStatus(mId, docDelivered, docRead);
                                                                anyNew = true;
                                                            }
                                                        }
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                            if (anyNew && isAdded()) {
                                                loadLocalEncryptedChats();
                                            }
                                        });
                            }
                            if (updated && isAdded()) {
                                loadLocalEncryptedChats();
                            }
                        });
            }
        }
        updatePersonaAndMeshHeader();
        loadLocalEncryptedChats();
        updateArchivedHeader();
        startVanishTicker();
        if (getContext() != null) {
            com.m3.veil.manager.StatusController.getInstance(requireContext()).addObserver(statusObserver);
            VeilFeatureManager.getInstance(requireContext()).startStatusSync(() -> {
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(this::renderStatusTray);
                }
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopVanishTicker();
        if (getContext() != null) {
            com.m3.veil.manager.StatusController.getInstance(requireContext()).removeObserver(statusObserver);
            VeilFeatureManager.getInstance(requireContext()).stopStatusSync();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopVanishTicker();
        if (groupsListenerRegistration != null) {
            groupsListenerRegistration.remove();
            groupsListenerRegistration = null;
        }
        if (directChatsListenerRegistration != null) {
            directChatsListenerRegistration.remove();
            directChatsListenerRegistration = null;
        }
        if (getContext() != null) {
            EncryptedChatDatabase.getInstance(requireContext()).removeListener(dbListener);
            WebRtcManager.getInstance(requireContext()).removeEventListener(p2pListener);
            com.m3.veil.manager.VeilMessagesController.getInstance(requireContext()).removeObserver(messagesObserver);
            com.m3.veil.manager.StatusController.getInstance(requireContext()).removeObserver(statusObserver);
        }
    }

    private void updateArchivedHeader() {
        if (getContext() == null || archivedRow == null) return;
        int count = EncryptedChatDatabase.getInstance(requireContext()).getArchivedChatsCount();
        if (count > 0 && "all".equals(activeFilter) && searchQuery.isEmpty()) {
            archivedRow.setVisibility(View.VISIBLE);
            archivedDivider.setVisibility(View.VISIBLE);
            archivedCount.setText(String.valueOf(count));
        } else {
            archivedRow.setVisibility(View.GONE);
            archivedDivider.setVisibility(View.GONE);
        }
    }

    private void updateEmptyState() {
        if ("status".equals(activeFilter)) {
            emptyState.setVisibility(View.GONE);
            chatsRecyclerView.setVisibility(View.GONE);
            return;
        }

        if (displayedChats.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            chatsRecyclerView.setVisibility(View.GONE);
            if (startChatButton != null) {
                startChatButton.setText(R.string.start_chat);
                startChatButton.setOnClickListener(v -> startActivity(new Intent(getContext(), NewChatActivity.class)));
            }
            if (tvEmptyTitle != null && tvEmptySubtitle != null) {
                if (!searchQuery.isEmpty()) {
                    tvEmptyTitle.setText("No matching conversations");
                    tvEmptySubtitle.setText("No chats or messages matched \"" + searchQuery + "\"");
                } else if ("unread".equals(activeFilter)) {
                    tvEmptyTitle.setText("All caught up!");
                    tvEmptySubtitle.setText("You have no unread encrypted messages.");
                } else if ("secret".equals(activeFilter)) {
                    tvEmptyTitle.setText("Secret Vault is empty");
                    tvEmptySubtitle.setText("Long-press any conversation to move it to your hidden Secret Vault.");
                } else {
                    tvEmptyTitle.setText(R.string.no_conversations);
                    tvEmptySubtitle.setText(R.string.start_new_conversation_hint);
                }
            }
        } else {
            emptyState.setVisibility(View.GONE);
            chatsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onChatClick(Chat chat) {
        if (chat == null || getContext() == null) return;

        // If chat has a per-chat PIN lock, verify PIN before opening
        if (chat.getChatLockPin() != null && !chat.getChatLockPin().isEmpty()) {
            showPinAuthModal("🔒 Unlock " + chat.getName(), "This conversation is encrypted & PIN protected", "Enter Chat PIN", (pin, dialog, layout) -> {
                if (chat.getChatLockPin().equals(pin)) {
                    dialog.dismiss();
                    openChatScreen(chat, true);
                } else {
                    layout.setError("Incorrect Chat PIN");
                }
            });
            return;
        }

        openChatScreen(chat, false);
    }

    private void openChatScreen(Chat chat, boolean isPinVerified) {
        chat.setUnreadCount(0);
        EncryptedChatDatabase.getInstance(requireContext()).updateChatUnreadCount(chat.getId(), 0);
        chatsAdapter.notifyDataSetChanged();

        Intent intent = new Intent(getContext(), ChatActivity.class);
        intent.putExtra("chatId", chat.getId());
        intent.putExtra("recipientName", chat.getName());
        intent.putExtra("isPinVerified", isPinVerified);

        String recipientId = null;
        if (chat.getParticipants() != null) {
            for (String id : chat.getParticipants()) {
                if (id != null && !id.equals(currentUserId)) {
                    recipientId = id;
                    break;
                }
            }
        }
        if ((recipientId == null || recipientId.trim().isEmpty()) && chat.getId() != null && !chat.isGroup()) {
            String[] parts = chat.getId().split("_");
            if (parts.length == 2) {
                recipientId = parts[0].equals(currentUserId) ? parts[1] : parts[0];
            }
        }
        if (recipientId != null && !recipientId.trim().isEmpty()) {
            intent.putExtra("recipientId", recipientId);
        }
        startActivity(intent);
    }

    @Override
    public void onChatLongClick(Chat chat) {
        if (chat == null || chat.getId() == null || getContext() == null) return;
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());

        View sheetView = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottom_sheet_chat_actions, null);

        TextView tvTitle = sheetView.findViewById(R.id.tvSheetChatTitle);
        TextView tvSubtitle = sheetView.findViewById(R.id.tvSheetChatSubtitle);
        TextView actionMarkReadUnread = sheetView.findViewById(R.id.actionMarkReadUnread);
        TextView actionPin = sheetView.findViewById(R.id.actionPin);
        TextView actionMute = sheetView.findViewById(R.id.actionMute);
        TextView actionVanishTimer = sheetView.findViewById(R.id.actionVanishTimer);
        TextView actionLockChat = sheetView.findViewById(R.id.actionLockChat);
        TextView actionSecretVault = sheetView.findViewById(R.id.actionSecretVault);
        TextView actionArchive = sheetView.findViewById(R.id.actionArchive);
        TextView actionClearChat = sheetView.findViewById(R.id.actionClearChat);
        TextView actionDelete = sheetView.findViewById(R.id.actionDelete);

        if (tvTitle != null) tvTitle.setText(chat.getName() != null ? chat.getName() : "@Anonymous");
        if (tvSubtitle != null) {
            tvSubtitle.setText("🛡️ Trust Score: " + chat.getTrustScore() + "% • SQLCipher 256-bit");
        }

        if (actionMarkReadUnread != null) {
            actionMarkReadUnread.setText(chat.getUnreadCount() > 0 ? "✓✓  Mark as read" : "🔵  Mark as unread");
            actionMarkReadUnread.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                int nextCount = chat.getUnreadCount() > 0 ? 0 : 1;
                chat.setUnreadCount(nextCount);
                EncryptedChatDatabase.getInstance(requireContext()).updateChatUnreadCount(chat.getId(), nextCount);
                loadLocalEncryptedChats();
            });
        }

        actionPin.setText(chat.isPinned() ? "📌  Unpin chat" : "📌  Pin to top");
        actionMute.setText(chat.isMuted() ? "🔔  Unmute notifications" : "🔕  Mute notifications");
        actionArchive.setText(chat.isArchived() ? "📤  Unarchive chat" : "📥  Archive chat");

        boolean isLocked = chat.getChatLockPin() != null && !chat.getChatLockPin().isEmpty();
        if (actionLockChat != null) {
            actionLockChat.setText(isLocked ? "🔓  Remove / Change Chat PIN lock" : "🔒  Lock chat with PIN");
            actionLockChat.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                if (isLocked) {
                    showPinAuthModal("🔒 Verify Current PIN", "Enter your current 4-digit PIN for " + chat.getName(), "Current PIN", (enteredPin, pinDialog, layout) -> {
                        if (chat.getChatLockPin().equals(enteredPin)) {
                            pinDialog.dismiss();
                            showChangeOrRemoveChatPinDialog(chat);
                        } else {
                            layout.setError("Incorrect current PIN");
                        }
                    });
                } else {
                    showSetChatPinDialog(chat);
                }
            });
        }

        if (actionSecretVault != null) {
            actionSecretVault.setText(chat.isSecret()
                    ? "👁️  Move out of Secret Vault"
                    : "🕵️  Move to Secret Vault folder");
            actionSecretVault.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                if (isLocked) {
                    showPinAuthModal("🔒 Verify Chat PIN", "Enter PIN to move " + chat.getName(), "Chat PIN", (enteredPin, pinDialog, layout) -> {
                        if (chat.getChatLockPin().equals(enteredPin)) {
                            pinDialog.dismiss();
                            toggleChatSecretStatus(chat);
                        } else {
                            layout.setError("Incorrect PIN");
                        }
                    });
                } else {
                    toggleChatSecretStatus(chat);
                }
            });
        }

        if (actionVanishTimer != null) {
            actionVanishTimer.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                showVanishTimerDialog(chat);
            });
        }

        actionPin.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            boolean newPinned = !chat.isPinned();
            chat.setPinned(newPinned);
            EncryptedChatDatabase.getInstance(requireContext()).updateChatPinStatus(chat.getId(), newPinned);
            loadLocalEncryptedChats();
            Toast.makeText(getContext(), newPinned ? "Chat pinned to top" : "Chat unpinned", Toast.LENGTH_SHORT).show();
        });

        actionMute.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            boolean newMuted = !chat.isMuted();
            chat.setMuted(newMuted);
            EncryptedChatDatabase.getInstance(requireContext()).updateChatMuteStatus(chat.getId(), newMuted);
            loadLocalEncryptedChats();
            Toast.makeText(getContext(), newMuted ? "Notifications muted" : "Notifications unmuted", Toast.LENGTH_SHORT).show();
        });

        actionArchive.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            if (isLocked) {
                showPinAuthModal("🔒 Verify Chat PIN", "Enter PIN to archive " + chat.getName(), "Chat PIN", (enteredPin, pinDialog, layout) -> {
                    if (chat.getChatLockPin().equals(enteredPin)) {
                        pinDialog.dismiss();
                        toggleChatArchiveStatus(chat);
                    } else {
                        layout.setError("Incorrect PIN");
                    }
                });
            } else {
                toggleChatArchiveStatus(chat);
            }
        });

        if (actionClearChat != null) {
            actionClearChat.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                if (isLocked) {
                    showPinAuthModal("🔒 Verify Chat PIN", "Enter PIN to clear messages in " + chat.getName(), "Chat PIN", (enteredPin, pinDialog, layout) -> {
                        if (chat.getChatLockPin().equals(enteredPin)) {
                            pinDialog.dismiss();
                            confirmClearChat(chat);
                        } else {
                            layout.setError("Incorrect PIN");
                        }
                    });
                } else {
                    confirmClearChat(chat);
                }
            });
        }

        actionDelete.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            if (isLocked) {
                showPinAuthModal("🔒 Verify Chat PIN", "Enter PIN to delete " + chat.getName(), "Chat PIN", (enteredPin, pinDialog, layout) -> {
                    if (chat.getChatLockPin().equals(enteredPin)) {
                        pinDialog.dismiss();
                        deleteChat(chat);
                    } else {
                        layout.setError("Incorrect PIN");
                    }
                });
            } else {
                deleteChat(chat);
            }
        });

        // Inject Stealth Peek & Multi-Select Batch Operations into the sheet
        if (sheetView instanceof ViewGroup) {
            ViewGroup rootGroup = (ViewGroup) sheetView;
            float dp = getResources().getDisplayMetrics().density;

            TextView actionStealthPeek = new TextView(requireContext());
            actionStealthPeek.setText("👁️  Stealth Peek (Read Without Receipts)");
            actionStealthPeek.setTextSize(15f);
            actionStealthPeek.setTypeface(null, Typeface.BOLD);
            actionStealthPeek.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            actionStealthPeek.setPadding((int) (24 * dp), (int) (14 * dp), (int) (24 * dp), (int) (14 * dp));
            actionStealthPeek.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                showStealthPeekDialog(chat);
            });
            rootGroup.addView(actionStealthPeek, Math.min(2, rootGroup.getChildCount()));

            TextView actionBatchOps = new TextView(requireContext());
            actionBatchOps.setText("☑️  Multi-Select Chats (Batch Pin / Mute / Archive / Delete)");
            actionBatchOps.setTextSize(14.5f);
            actionBatchOps.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
            actionBatchOps.setPadding((int) (24 * dp), (int) (13 * dp), (int) (24 * dp), (int) (13 * dp));
            actionBatchOps.setOnClickListener(v -> {
                bottomSheetDialog.dismiss();
                showBatchChatOperationsSheet(chat);
            });
            rootGroup.addView(actionBatchOps, Math.min(3, rootGroup.getChildCount()));
        }

        bottomSheetDialog.setContentView(sheetView);
        bottomSheetDialog.show();
    }

    private void showStealthPeekDialog(Chat chat) {
        if (getContext() == null || chat == null) return;
        BottomSheetDialog peekSheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(requireContext());
        title.setText("👁️ Stealth Peek • " + (chat.getName() != null ? chat.getName() : "Chat"));
        title.setTextSize(17.5f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
        root.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setText("Reading from local SQLCipher vault without sending read receipts or clearing unread count.");
        sub.setTextSize(12f);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        sub.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        root.addView(sub);

        List<Message> msgs = EncryptedChatDatabase.getInstance(requireContext()).getMessages(chat.getId());
        LinearLayout msgBox = new LinearLayout(requireContext());
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setBackgroundResource(R.drawable.bg_search_input);
        int boxPad = (int) (12 * dp);
        msgBox.setPadding(boxPad, boxPad, boxPad, boxPad);

        if (msgs == null || msgs.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("No messages in local encrypted vault yet.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
            empty.setTextSize(13.5f);
            msgBox.addView(empty);
        } else {
            int start = Math.max(0, msgs.size() - 6);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault());
            for (int i = start; i < msgs.size(); i++) {
                Message m = msgs.get(i);
                if (m == null) continue;
                boolean isMe = currentUserId != null && currentUserId.equals(m.getSenderId());
                String senderLabel = isMe ? "You" : (chat.getName() != null ? chat.getName() : "Peer");
                String timeStr = m.getTimestamp() != null ? sdf.format(m.getTimestamp().toDate()) : "";
                String body = m.getText() != null && !m.getText().isEmpty() ? m.getText() : "[" + m.getType() + "]";

                TextView line = new TextView(requireContext());
                line.setText("[" + timeStr + "] " + senderLabel + ": " + body);
                line.setTextSize(13.5f);
                line.setTextColor(ContextCompat.getColor(requireContext(), isMe ? R.color.colorPrimary : R.color.textPrimary));
                line.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
                msgBox.addView(line);
            }
        }
        root.addView(msgBox);

        MaterialButton btnOpenFull = new MaterialButton(requireContext());
        btnOpenFull.setText("Open Full Conversation");
        btnOpenFull.setCornerRadius((int) (12 * dp));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = (int) (14 * dp);
        btnOpenFull.setLayoutParams(btnLp);
        btnOpenFull.setOnClickListener(v -> {
            peekSheet.dismiss();
            onChatClick(chat);
        });
        root.addView(btnOpenFull);

        peekSheet.setContentView(root);
        peekSheet.show();
    }

    private void showBatchChatOperationsSheet(Chat initialSelected) {
        if (getContext() == null) return;
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        int pad = (int) (20 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(requireContext());
        title.setText("☑️ Multi-Select Chats");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
        root.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setText("Select multiple conversations to pin, mute, mark read, archive, or delete in 1 tap:");
        sub.setTextSize(12.5f);
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary));
        sub.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        root.addView(sub);

        final java.util.Set<String> selectedIds = new java.util.HashSet<>();
        if (initialSelected != null && initialSelected.getId() != null) {
            selectedIds.add(initialSelected.getId());
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        LinearLayout listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        for (Chat c : displayedChats) {
            if (c == null || c.getId() == null) continue;
            android.widget.CheckBox cb = new android.widget.CheckBox(requireContext());
            cb.setText((c.getName() != null ? c.getName() : "Anonymous") + (c.getUnreadCount() > 0 ? " (" + c.getUnreadCount() + " unread)" : ""));
            cb.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
            cb.setChecked(selectedIds.contains(c.getId()));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selectedIds.add(c.getId());
                else selectedIds.remove(c.getId());
            });
            listContainer.addView(cb);
        }
        scroll.addView(listContainer);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (200 * dp));
        scrollLp.bottomMargin = (int) (12 * dp);
        scroll.setLayoutParams(scrollLp);
        root.addView(scroll);

        LinearLayout row1 = new LinearLayout(requireContext());
        row1.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton btnBatchPin = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnBatchPin.setText("📌 Pin");
        btnBatchPin.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnBatchPin.setOnClickListener(v -> {
            for (String id : selectedIds) {
                EncryptedChatDatabase.getInstance(requireContext()).updateChatPinStatus(id, true);
            }
            loadLocalEncryptedChats();
            sheet.dismiss();
            Toast.makeText(getContext(), "Pinned " + selectedIds.size() + " chats", Toast.LENGTH_SHORT).show();
        });
        row1.addView(btnBatchPin);

        MaterialButton btnBatchMute = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnBatchMute.setText("🔕 Mute");
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mLp.leftMargin = (int) (8 * dp);
        btnBatchMute.setLayoutParams(mLp);
        btnBatchMute.setOnClickListener(v -> {
            for (String id : selectedIds) {
                EncryptedChatDatabase.getInstance(requireContext()).updateChatMuteStatus(id, true);
            }
            loadLocalEncryptedChats();
            sheet.dismiss();
            Toast.makeText(getContext(), "Muted " + selectedIds.size() + " chats", Toast.LENGTH_SHORT).show();
        });
        row1.addView(btnBatchMute);

        MaterialButton btnBatchRead = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnBatchRead.setText("✓✓ Read");
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rLp.leftMargin = (int) (8 * dp);
        btnBatchRead.setLayoutParams(rLp);
        btnBatchRead.setOnClickListener(v -> {
            for (String id : selectedIds) {
                EncryptedChatDatabase.getInstance(requireContext()).updateChatUnreadCount(id, 0);
            }
            loadLocalEncryptedChats();
            sheet.dismiss();
            Toast.makeText(getContext(), "Marked " + selectedIds.size() + " chats as read", Toast.LENGTH_SHORT).show();
        });
        row1.addView(btnBatchRead);
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(requireContext());
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = (int) (8 * dp);
        row2.setLayoutParams(row2Lp);

        MaterialButton btnBatchArchive = new MaterialButton(requireContext());
        btnBatchArchive.setText("📥 Archive Selected");
        btnBatchArchive.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnBatchArchive.setOnClickListener(v -> {
            for (String id : selectedIds) {
                EncryptedChatDatabase.getInstance(requireContext()).updateChatArchiveStatus(id, true);
            }
            loadLocalEncryptedChats();
            sheet.dismiss();
            Toast.makeText(getContext(), "Archived " + selectedIds.size() + " chats", Toast.LENGTH_SHORT).show();
        });
        row2.addView(btnBatchArchive);

        MaterialButton btnBatchDelete = new MaterialButton(requireContext());
        btnBatchDelete.setText("🗑️ Delete Selected");
        btnBatchDelete.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));
        btnBatchDelete.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dLp.leftMargin = (int) (8 * dp);
        btnBatchDelete.setLayoutParams(dLp);
        btnBatchDelete.setOnClickListener(v -> {
            for (String id : selectedIds) {
                EncryptedChatDatabase.getInstance(requireContext()).deleteChat(id);
            }
            loadLocalEncryptedChats();
            sheet.dismiss();
            Toast.makeText(getContext(), "Deleted " + selectedIds.size() + " chats from vault", Toast.LENGTH_SHORT).show();
        });
        row2.addView(btnBatchDelete);
        root.addView(row2);

        sheet.setContentView(root);
        sheet.show();
    }

    private void toggleChatSecretStatus(Chat chat) {
        boolean nextSecret = !chat.isSecret();
        chat.setSecret(nextSecret);
        EncryptedChatDatabase.getInstance(requireContext()).updateChatSecretStatus(chat.getId(), nextSecret);
        loadLocalEncryptedChats();
        Toast.makeText(getContext(),
                nextSecret ? "🕵️ Moved to Secret Vault (tap Secret Vault chip to view)" : "Moved back to main chats",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleChatArchiveStatus(Chat chat) {
        boolean newArchived = !chat.isArchived();
        chat.setArchived(newArchived);
        EncryptedChatDatabase.getInstance(requireContext()).updateChatArchiveStatus(chat.getId(), newArchived);
        loadLocalEncryptedChats();
        if (newArchived && getView() != null) {
            Snackbar.make(getView(), "Chat archived in SQLCipher vault", Snackbar.LENGTH_LONG)
                    .setAction("Undo", undoView -> {
                        chat.setArchived(false);
                        EncryptedChatDatabase.getInstance(requireContext()).updateChatArchiveStatus(chat.getId(), false);
                        loadLocalEncryptedChats();
                    }).show();
        }
    }

    private void confirmClearChat(Chat chat) {
        BottomSheetDialog confirmSheet = new BottomSheetDialog(requireContext());
        confirmSheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvConfirmTitle = confirmSheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = confirmSheet.findViewById(R.id.tvDestructiveMessage);
        com.google.android.material.button.MaterialButton btnConfirm = confirmSheet.findViewById(R.id.btnDestructiveConfirm);
        com.google.android.material.button.MaterialButton btnCancel = confirmSheet.findViewById(R.id.btnDestructiveCancel);

        if (tvConfirmTitle != null) tvConfirmTitle.setText("Clear Chat Messages?");
        if (tvMsg != null) {
            tvMsg.setText("Are you sure you want to wipe all messages inside this conversation? The chat will remain open, but all existing messages will be shredded.");
        }
        if (btnConfirm != null) {
            btnConfirm.setText("Clear & Shred");
            btnConfirm.setOnClickListener(cv -> {
                confirmSheet.dismiss();
                EncryptedChatDatabase.getInstance(requireContext()).clearChatMessages(chat.getId());
                loadLocalEncryptedChats();
                Toast.makeText(getContext(), "Messages cleared", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(cv -> confirmSheet.dismiss());
        }
        confirmSheet.show();
    }

    private void showChangeOrRemoveChatPinDialog(Chat chat) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bottom_sheet_background);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(requireContext());
        title.setText("🔒 Manage Chat PIN Lock");
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (14 * dp));
        root.addView(title);

        MaterialButton btnRemove = new MaterialButton(requireContext());
        btnRemove.setText("🔓 Remove PIN Lock");
        btnRemove.setCornerRadius((int) (12 * dp));
        btnRemove.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEF4444));
        btnRemove.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = (int) (10 * dp);
        btnRemove.setLayoutParams(lp1);
        btnRemove.setOnClickListener(v -> {
            sheet.dismiss();
            chat.setChatLockPin(null);
            EncryptedChatDatabase.getInstance(requireContext()).updateChatLockPin(chat.getId(), null);
            loadLocalEncryptedChats();
            Toast.makeText(getContext(), "🔓 Chat PIN lock removed", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnRemove);

        MaterialButton btnChange = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnChange.setText("✏️ Change 4-Digit PIN");
        btnChange.setCornerRadius((int) (12 * dp));
        btnChange.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnChange.setOnClickListener(v -> {
            sheet.dismiss();
            showSetChatPinDialog(chat);
        });
        root.addView(btnChange);

        sheet.setContentView(root);
        sheet.show();
    }

    private void showSetChatPinDialog(Chat chat) {
        if (getContext() == null || chat == null) return;
        showPinAuthModal("🔒 Lock " + chat.getName(), "Set a 4-digit PIN for zero-trace local protection", "New 4-digit PIN", (pin, dialog, layout) -> {
            if (pin.length() >= 4) {
                chat.setChatLockPin(pin);
                EncryptedChatDatabase.getInstance(requireContext()).updateChatLockPin(chat.getId(), pin);
                loadLocalEncryptedChats();
                Toast.makeText(getContext(), "🔒 Chat locked with PIN", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } else {
                layout.setError("PIN must be at least 4 digits");
            }
        });
    }

    private void showVanishTimerDialog(Chat chat) {
        if (getContext() == null || chat == null) return;
        String[] labels = {
                "Off (Permanent encrypted storage)",
                "⚡ 30 Seconds after seen",
                "⏱️ 5 Minutes",
                "⏱️ 1 Hour",
                "⏱️ 24 Hours (WhatsApp Standard)",
                "⏱️ 7 Days",
                "⏱️ 30 Days"
        };
        long[] durations = {
                0L,
                30_000L,
                5 * 60_000L,
                60 * 60_000L,
                24 * 3600_000L,
                7 * 24 * 3600_000L,
                30L * 24 * 3600_000L
        };

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        View view = getLayoutInflater().inflate(R.layout.dialog_vanish_timer_sheet, null);
        dialog.setContentView(view);

        ViewGroup container = view.findViewById(R.id.vanishOptionsContainer);
        float density = getResources().getDisplayMetrics().density;
        long currentTimer = chat.getVanishTimer();

        for (int i = 0; i < labels.length; i++) {
            final long dur = durations[i];
            final String label = labels[i];
            boolean isSelected = (dur == 0 && currentTimer == 0) || (dur > 0 && currentTimer == dur);

            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(requireContext());
            card.setCardElevation(0);
            card.setStrokeWidth((int) (1.2f * density));
            card.setStrokeColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.colorPrimary : R.color.colorOutline));
            card.setRadius(12 * density);
            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.colorSurfaceVariant : R.color.colorSurface));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * density);
            card.setLayoutParams(lp);

            TextView tv = new TextView(requireContext());
            tv.setText((isSelected ? "✅ " : "   ") + label);
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.colorPrimary : R.color.textPrimary));
            tv.setTextSize(14);
            tv.setTypeface(null, isSelected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setPadding((int) (16 * density), (int) (14 * density), (int) (16 * density), (int) (14 * density));

            card.addView(tv);

            card.setOnClickListener(v -> {
                chat.setVanishMode(dur > 0 ? "timer" : "off");
                chat.setVanishTimer(dur);
                EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(chat);
                loadLocalEncryptedChats();
                Toast.makeText(getContext(), "Vanish timer set: " + label, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            container.addView(card);
        }

        dialog.show();
    }

    private void deleteChat(Chat chat) {
        if (chat == null || chat.getId() == null || getContext() == null) return;
        BottomSheetDialog confirmSheet = new BottomSheetDialog(requireContext());
        confirmSheet.setContentView(R.layout.dialog_confirm_destructive_sheet);

        TextView tvTitle = confirmSheet.findViewById(R.id.tvDestructiveTitle);
        TextView tvMsg = confirmSheet.findViewById(R.id.tvDestructiveMessage);
        com.google.android.material.button.MaterialButton btnConfirm = confirmSheet.findViewById(R.id.btnDestructiveConfirm);
        com.google.android.material.button.MaterialButton btnCancel = confirmSheet.findViewById(R.id.btnDestructiveCancel);

        if (tvTitle != null) tvTitle.setText("Delete Chat?");
        if (tvMsg != null) {
            tvMsg.setText("Are you sure you want to wipe this encrypted conversation from your device? All messages and attachments will be shredded.");
        }
        if (btnConfirm != null) {
            btnConfirm.setText("Delete & Shred");
            btnConfirm.setOnClickListener(cv -> {
                confirmSheet.dismiss();
                String deletedId = chat.getId();
                if (chat.isGroup() && getContext() != null) {
                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences("VeilPrefs", 0);
                    java.util.Set<String> delSet = new java.util.HashSet<>(
                            prefs.getStringSet("deleted_group_ids", new java.util.HashSet<>()));
                    delSet.add(deletedId);
                    prefs.edit().putStringSet("deleted_group_ids", delSet).apply();
                    if (currentUserId != null && !currentUserId.isEmpty()) {
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("groups").document(deletedId)
                                .update("participants", com.google.firebase.firestore.FieldValue.arrayRemove(currentUserId));
                    }
                }
                com.m3.veil.manager.VeilMessagesController.getInstance(requireContext()).removeDialogFromCache(deletedId);
                EncryptedChatDatabase.getInstance(requireContext()).deleteChat(deletedId);
                for (int i = allLocalChats.size() - 1; i >= 0; i--) {
                    if (deletedId.equals(allLocalChats.get(i).getId())) {
                        allLocalChats.remove(i);
                    }
                }
                for (int i = displayedChats.size() - 1; i >= 0; i--) {
                    if (deletedId.equals(displayedChats.get(i).getId())) {
                        displayedChats.remove(i);
                    }
                }
                if (chatsAdapter != null) {
                    chatsAdapter.notifyDataSetChanged();
                }
                loadLocalEncryptedChats();
                Toast.makeText(getContext(), "Chat deleted from local SQLCipher vault", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnCancel != null) {
            btnCancel.setOnClickListener(cv -> confirmSheet.dismiss());
        }
        confirmSheet.show();
    }
}
