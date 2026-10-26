package com.m3.veil.ui.chat.sheets;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.chat.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.hdodenhof.circleimageview.CircleImageView;

public class PublicDirectoryBottomSheet extends BottomSheetDialogFragment {

    public static class DirectoryItem {
        public String id;
        public String name;
        public String handle;
        public String description;
        public String avatar;
        public String type; // channel or group
        public List<String> participants;
        public List<String> adminIds;
        public String adminId;

        public DirectoryItem(String id, String name, String handle, String description,
                             String avatar, String type, List<String> participants,
                             List<String> adminIds, String adminId) {
            this.id = id;
            this.name = name;
            this.handle = handle;
            this.description = description;
            this.avatar = avatar;
            this.type = type != null ? type : "group";
            this.participants = participants != null ? participants : new ArrayList<>();
            this.adminIds = adminIds != null ? adminIds : new ArrayList<>();
            this.adminId = adminId;
        }

        public boolean isChannel() {
            return "channel".equalsIgnoreCase(type);
        }
    }

    private EditText etSearch;
    private ChipGroup chipGroupType;
    private RecyclerView rvDirectory;
    private View emptyState;
    private ProgressBar pbLoading;

    private DirectoryAdapter adapter;
    private final List<DirectoryItem> allItems = new ArrayList<>();
    private final List<DirectoryItem> filteredItems = new ArrayList<>();

    private FirebaseFirestore db;
    private String currentUserId;
    private String currentSearch = "";
    private int selectedTypeFilter = 0; // 0=All, 1=Channels, 2=Groups

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_public_directory, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null) currentUserId = "";

        etSearch = view.findViewById(R.id.etSearchDirectory);
        chipGroupType = view.findViewById(R.id.chipGroupDirectoryType);
        rvDirectory = view.findViewById(R.id.rvDirectory);
        emptyState = view.findViewById(R.id.emptyDirectoryState);
        pbLoading = view.findViewById(R.id.pbDirectoryLoading);

        rvDirectory.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DirectoryAdapter(filteredItems, currentUserId, this::onJoinClick);
        rvDirectory.setAdapter(adapter);

        setupListeners();
        loadPublicDirectory();
    }

    private void setupListeners() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearch = s != null ? s.toString().trim() : "";
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        chipGroupType.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipDirChannels) {
                selectedTypeFilter = 1;
            } else if (id == R.id.chipDirGroups) {
                selectedTypeFilter = 2;
            } else {
                selectedTypeFilter = 0;
            }
            applyFilter();
        });
    }

    private void loadPublicDirectory() {
        pbLoading.setVisibility(View.VISIBLE);
        db.collection("groups")
                .whereEqualTo("isPublic", true)
                .limit(60)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!isAdded()) return;
                    pbLoading.setVisibility(View.GONE);
                    allItems.clear();

                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        String id = doc.getString("groupId");
                        if (id == null) id = doc.getId();
                        String name = doc.getString("groupName");
                        String handle = doc.getString("handle");
                        String desc = doc.getString("description");
                        String avatar = doc.getString("avatar");
                        String type = doc.getString("type");
                        String adminId = doc.getString("adminId");

                        @SuppressWarnings("unchecked")
                        List<String> parts = (List<String>) doc.get("participants");
                        @SuppressWarnings("unchecked")
                        List<String> admins = (List<String>) doc.get("adminIds");

                        allItems.add(new DirectoryItem(
                                id,
                                name != null ? name : "Public Entity",
                                handle,
                                desc,
                                avatar,
                                type,
                                parts,
                                admins,
                                adminId
                        ));
                    }

                    applyFilter();
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    pbLoading.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "Failed to load directory", Toast.LENGTH_SHORT).show();
                });
    }

    private void applyFilter() {
        filteredItems.clear();
        String q = currentSearch.toLowerCase(Locale.ROOT);

        for (DirectoryItem item : allItems) {
            if (selectedTypeFilter == 1 && !item.isChannel()) continue;
            if (selectedTypeFilter == 2 && item.isChannel()) continue;

            if (!q.isEmpty()) {
                String n = item.name != null ? item.name.toLowerCase(Locale.ROOT) : "";
                String h = item.handle != null ? item.handle.toLowerCase(Locale.ROOT) : "";
                String d = item.description != null ? item.description.toLowerCase(Locale.ROOT) : "";
                if (!n.contains(q) && !h.contains(q) && !d.contains(q)) continue;
            }
            filteredItems.add(item);
        }

        adapter.notifyDataSetChanged();
        emptyState.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onJoinClick(DirectoryItem item) {
        boolean isAlreadyMember = item.participants.contains(currentUserId);
        if (isAlreadyMember) {
            openChat(item);
            return;
        }

        if (currentUserId.isEmpty()) return;

        // Add user to participants in Firestore
        db.collection("groups").document(item.id)
                .update("participants", FieldValue.arrayUnion(currentUserId))
                .addOnSuccessListener(aVoid -> {
                    if (!isAdded()) return;
                    item.participants.add(currentUserId);

                    // Upsert in SQLCipher
                    Chat c = new Chat(
                            item.id,
                            item.name,
                            "Joined public " + (item.isChannel() ? "channel" : "group"),
                            Timestamp.now(),
                            item.avatar,
                            0,
                            true,
                            false,
                            item.type,
                            item.participants
                    );
                    c.setDescription(item.description);
                    c.setHandle(item.handle);
                    c.setPublic(true);
                    c.setAdminId(item.adminId);
                    c.setAdminIds(item.adminIds);
                    EncryptedChatDatabase.getInstance(requireContext()).saveOrUpdateChat(c);

                    Toast.makeText(requireContext(), "Joined " + item.name, Toast.LENGTH_SHORT).show();
                    openChat(item);
                })
                .addOnFailureListener(e -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Error joining: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void openChat(DirectoryItem item) {
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra("chatId", item.id);
        intent.putExtra("isGroup", true);
        intent.putExtra("isChannel", item.isChannel());
        startActivity(intent);
        dismiss();
    }

    // ==========================================
    // ADAPTER
    // ==========================================

    private static class DirectoryAdapter extends RecyclerView.Adapter<DirectoryAdapter.VH> {
        private final List<DirectoryItem> list;
        private final String currentUserId;
        private final OnJoinListener listener;

        interface OnJoinListener {
            void onJoin(DirectoryItem item);
        }

        DirectoryAdapter(List<DirectoryItem> list, String currentUserId, OnJoinListener listener) {
            this.list = list;
            this.currentUserId = currentUserId;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_public_directory_entry, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DirectoryItem item = list.get(position);
            holder.tvName.setText(item.name);

            String handleText = item.handle != null && !item.handle.isEmpty() ? "@" + item.handle : "";
            int count = item.participants != null ? item.participants.size() : 0;
            String subText = count + (item.isChannel() ? (count == 1 ? " subscriber" : " subscribers") : (count == 1 ? " member" : " members"));
            holder.tvHandle.setText(handleText.isEmpty() ? subText : handleText + " • " + subText);

            if (item.description != null && !item.description.trim().isEmpty()) {
                holder.tvDesc.setVisibility(View.VISIBLE);
                holder.tvDesc.setText(item.description.trim());
            } else {
                holder.tvDesc.setVisibility(View.GONE);
            }

            holder.tvBadge.setText(item.isChannel() ? "Broadcast Channel" : "Discussion Group");
            holder.tvBadge.setTextColor(item.isChannel() ?
                    holder.itemView.getContext().getColor(R.color.colorPrimary) :
                    holder.itemView.getContext().getColor(R.color.colorSecondary));

            boolean isJoined = item.participants != null && item.participants.contains(currentUserId);
            holder.btnJoin.setText(isJoined ? "Open" : "Join");

            if (item.avatar != null && !item.avatar.isEmpty()) {
                if (item.avatar.startsWith("content://") || item.avatar.startsWith("file://")) {
                    holder.ivAvatar.setImageURI(android.net.Uri.parse(item.avatar));
                } else {
                    try {
                        byte[] decoded = android.util.Base64.decode(item.avatar, android.util.Base64.DEFAULT);
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                        if (bmp != null) holder.ivAvatar.setImageBitmap(bmp);
                        else holder.ivAvatar.setImageResource(item.isChannel() ? R.drawable.ic_channel : R.drawable.ic_group);
                    } catch (Exception e) {
                        holder.ivAvatar.setImageResource(item.isChannel() ? R.drawable.ic_channel : R.drawable.ic_group);
                    }
                }
            } else {
                holder.ivAvatar.setImageResource(item.isChannel() ? R.drawable.ic_channel : R.drawable.ic_group);
            }

            holder.btnJoin.setOnClickListener(v -> listener.onJoin(item));
            holder.itemView.setOnClickListener(v -> listener.onJoin(item));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName;
            TextView tvBadge;
            TextView tvHandle;
            TextView tvDesc;
            MaterialButton btnJoin;

            VH(@NonNull View itemView) {
                super(itemView);
                ivAvatar = itemView.findViewById(R.id.ivDirAvatar);
                tvName = itemView.findViewById(R.id.tvDirName);
                tvBadge = itemView.findViewById(R.id.tvDirBadge);
                tvHandle = itemView.findViewById(R.id.tvDirHandle);
                tvDesc = itemView.findViewById(R.id.tvDirDesc);
                btnJoin = itemView.findViewById(R.id.btnDirJoin);
            }
        }
    }
}
