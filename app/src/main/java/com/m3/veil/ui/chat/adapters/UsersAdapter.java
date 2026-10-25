package com.m3.veil.ui.chat.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.User;
import java.util.List;

public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.UserViewHolder> {

    private List<User> userList;
    private OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(User user);
    }

    public UsersAdapter(List<User> userList, OnUserClickListener listener) {
        this.userList = userList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        User user = userList.get(position);
        holder.bind(user, listener);
    }

    @Override
    public int getItemCount() {
        return userList.size();
    }

    public void updateUsers(List<User> newUsers) {
        this.userList = newUsers;
        notifyDataSetChanged();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView name, username;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            name = itemView.findViewById(R.id.name);
            username = itemView.findViewById(R.id.username);
        }

        public void bind(User user, OnUserClickListener listener) {
            name.setText(user.getDisplayName() != null ? user.getDisplayName() : "Anonymous");
            username.setText("@" + user.getUsername());
            
            // Set avatar with Base64 decoding support
            boolean avatarLoaded = false;
            if (user.getAvatar() != null && !user.getAvatar().isEmpty()) {
                try {
                    byte[] decoded = android.util.Base64.decode(user.getAvatar(), android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    if (bmp != null) {
                        avatar.setImageBitmap(bmp);
                        avatarLoaded = true;
                    }
                } catch (Exception ignored) {}
            }
            if (!avatarLoaded) {
                avatar.setImageResource(R.drawable.ic_person);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onUserClick(user);
            });
        }
    }
}
