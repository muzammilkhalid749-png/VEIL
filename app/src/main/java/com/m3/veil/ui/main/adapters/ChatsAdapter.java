package com.m3.veil.ui.main.adapters;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.model.Chat;
import com.m3.veil.ui.views.VeilDialogCell;

import java.util.ArrayList;
import java.util.List;

/**
 * High-performance 120 FPS Chats Adapter using Telegram-grade custom Canvas VeilDialogCell.
 * Eliminates nested XML inflation and view hierarchies for locked 120Hz smooth scrolling.
 */
public class ChatsAdapter extends RecyclerView.Adapter<ChatsAdapter.ChatViewHolder> {

    private List<Chat> chatList;
    private final OnChatClickListener listener;
    private final String currentUserId;

    public interface OnChatClickListener {
        void onChatClick(Chat chat);
        void onChatLongClick(Chat chat);
    }

    public ChatsAdapter(List<Chat> chatList, OnChatClickListener listener) {
        this.chatList = chatList != null ? chatList : new ArrayList<>();
        this.listener = listener;
        String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(com.m3.veil.VeilApplication.getInstance());
        this.currentUserId = uid != null ? uid : "";
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        VeilDialogCell cell = new VeilDialogCell(parent.getContext());
        cell.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return new ChatViewHolder(cell);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        Chat chat = chatList.get(position);
        holder.bind(chat, currentUserId);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChatClick(chat);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onChatLongClick(chat);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return chatList != null ? chatList.size() : 0;
    }

    public Chat getChatAt(int position) {
        if (chatList == null || position < 0 || position >= chatList.size()) return null;
        return chatList.get(position);
    }

    public void updateChats(List<Chat> newChats) {
        if (newChats != null) {
            newChats.sort((c1, c2) -> {
                if (c1.isPinned() != c2.isPinned()) {
                    return c1.isPinned() ? -1 : 1;
                }
                if (c1.getLastMessageTime() == null && c2.getLastMessageTime() == null) return 0;
                if (c1.getLastMessageTime() == null) return 1;
                if (c2.getLastMessageTime() == null) return -1;
                return c2.getLastMessageTime().compareTo(c1.getLastMessageTime());
            });
        }
        this.chatList = newChats != null ? newChats : new ArrayList<>();
        notifyDataSetChanged();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        private final VeilDialogCell dialogCell;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            this.dialogCell = (VeilDialogCell) itemView;
        }

        public void bind(Chat chat, String currentUserId) {
            dialogCell.setChat(chat, currentUserId);
        }
    }
}
