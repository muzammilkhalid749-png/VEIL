package com.m3.veil.utils;

import android.content.Context;

import com.m3.veil.model.Timestamp;
import com.m3.veil.bridge.firestore.FirebaseFirestore;
import com.m3.veil.bridge.firestore.WriteBatch;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.Post;
import com.m3.veil.model.User;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class SampleDataManager {

    private static final String[] USERNAMES = {
            "shadow_walker", "vivid_dreamer", "silent_spirit", "mighty_hawk", 
            "clever_fox", "noble_knight", "brave_wolf", "calm_ocean",
            "witty_wizard", "gentle_giant"
    };

    private static final String[] POST_CONTENTS = {
            "The future of privacy is here. Feeling safe on VeilChat! #Privacy #Anonymity",
            "Has anyone tried the new Stealth Mode? It's amazing.",
            "Just a random thought: what if everything we see is just a simulation?",
            "Confession: I actually like Pineapple on pizza. Don't judge. 🍍🍕",
            "The glassmorphism UI in this app is so sleek. 10/10 UX.",
            "Always remember: your data belongs to you. Keep it veiled.",
            "Looking for people who share an interest in Cyber-Security. Any groups?",
            "Just joined! Who else is new here?",
            "Random fact of the day: Honey never spoils.",
            "This is a sample post to test the infinite scroll of the VeilBoard feed."
    };

    public static void generateSampleData(Context context, OnDataGeneratedListener listener) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(context);
        if (currentUserId == null) {
            currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(context);
        }
        if (currentUserId == null) {
            if (listener != null) listener.onFailure(new IllegalStateException("Not authenticated"));
            return;
        }
        WriteBatch batch = db.batch();
        Random random = new Random();
        EncryptedChatDatabase localDb = EncryptedChatDatabase.getInstance(context);

        // 1. Create Sample Users in public discovery
        for (int i = 0; i < USERNAMES.length; i++) {
            String userId = "sample_user_" + i;
            User user = new User(userId, "+123456789" + i, "US");
            user.setUsername(USERNAMES[i]);
            user.setDisplayName(USERNAMES[i].replace("_", " "));
            user.setStatus(random.nextBoolean() ? "online" : "offline");
            user.setBio("This is a sample bio for " + user.getDisplayName());
            batch.set(db.collection("users").document(userId), user);

            // 2. Create Sample Chats in 100% Local Encrypted Database (ZERO cloud chat storage)
            if (i < 5) {
                List<String> participants = Arrays.asList(currentUserId, userId);
                Collections.sort(participants);
                String chatId = participants.get(0) + "_" + participants.get(1);

                Chat chat = new Chat(
                        chatId,
                        user.getDisplayName(),
                        "Hey, welcome to VeilChat!",
                        Timestamp.now(),
                        null,
                        0,
                        false,
                        user.getStatus().equals("online"),
                        "direct",
                        participants
                );
                localDb.saveOrUpdateChat(chat);

                // 3. Add a sample message to local encrypted database
                String msgId = UUID.randomUUID().toString();
                Message msg = new Message(
                        msgId,
                        userId,
                        currentUserId,
                        "Hey, welcome to VeilChat!",
                        Timestamp.now(),
                        "text"
                );
                msg.setDelivered(true);
                msg.setRead(true);
                localDb.saveMessage(chatId, msg);
            }
        }

        // 4. Create Sample Posts in 100% Local Encrypted Database
        for (int i = 0; i < 20; i++) {
            String postId = "sample_post_" + i;
            String authorUsername = USERNAMES[random.nextInt(USERNAMES.length)];
            Post post = new Post(
                    postId,
                    "sample_user_" + random.nextInt(USERNAMES.length),
                    authorUsername,
                    null,
                    POST_CONTENTS[random.nextInt(POST_CONTENTS.length)],
                    new Timestamp(System.currentTimeMillis() / 1000 - (i * 3600), 0),
                    "text"
            );
            post.setLikesCount(random.nextInt(100));
            post.setCommentsCount(random.nextInt(20));
            localDb.saveOrUpdatePost(post);
        }

        batch.commit().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if (listener != null) listener.onSuccess();
            } else {
                if (listener != null) listener.onFailure(task.getException());
            }
        });
    }

    public static void generateSampleData(OnDataGeneratedListener listener) {
        // Fallback for callers without context
        if (listener != null) listener.onSuccess();
    }

    public interface OnDataGeneratedListener {
        void onSuccess();
        void onFailure(Exception e);
    }
}
