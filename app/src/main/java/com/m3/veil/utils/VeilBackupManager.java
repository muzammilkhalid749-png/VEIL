package com.m3.veil.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * VeilBackupManager
 * Production AES-256-GCM encrypted local vault backup (.veilbackup) system.
 * Allows complete export and restore of chats and messages with password protection.
 */
public class VeilBackupManager {

    private static final byte[] MAGIC_HEADER = new byte[]{'V', 'E', 'I', 'L'};
    private static final byte FORMAT_VERSION = 0x01;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    public interface ExportCallback {
        void onSuccess(File backupFile, Uri shareableUri, int chatCount, int messageCount);
        void onError(String error);
    }

    public interface RestoreCallback {
        void onSuccess(int chatsRestored, int messagesRestored);
        void onError(String error);
    }

    public static class BackupContainer {
        public String app = "com.m3.veil";
        public int version = 1;
        public long timestamp;
        public List<Chat> chats;
        public Map<String, List<Message>> messages;
    }

    public static void exportBackup(@NonNull Context context, @NonNull String passphrase, @NonNull ExportCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(context);
                List<Chat> chats = db.getAllChats(true);
                Map<String, List<Message>> messagesMap = new HashMap<>();
                int totalMsgs = 0;

                if (chats != null) {
                    for (Chat c : chats) {
                        if (c.getId() != null) {
                            List<Message> msgs = db.getMessages(c.getId(), 5000, 0);
                            if (msgs != null && !msgs.isEmpty()) {
                                messagesMap.put(c.getId(), msgs);
                                totalMsgs += msgs.size();
                            }
                        }
                    }
                }

                BackupContainer container = new BackupContainer();
                container.timestamp = System.currentTimeMillis();
                container.chats = chats;
                container.messages = messagesMap;

                Gson gson = new Gson();
                String json = gson.toJson(container);
                byte[] plaintextBytes = json.getBytes(StandardCharsets.UTF_8);

                // Generate random Salt and IV
                SecureRandom random = new SecureRandom();
                byte[] salt = new byte[SALT_LENGTH];
                random.nextBytes(salt);

                byte[] iv = new byte[IV_LENGTH];
                random.nextBytes(iv);

                // PBKDF2 Key Derivation
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                PBEKeySpec keySpec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
                SecretKey tmpKey = factory.generateSecret(keySpec);
                SecretKey secretKey = new SecretKeySpec(tmpKey.getEncoded(), "AES");

                // AES-GCM Encrypt
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
                byte[] ciphertext = cipher.doFinal(plaintextBytes);

                // Write file: [4 bytes MAGIC][1 byte VERSION][16 bytes SALT][12 bytes IV][CIPHERTEXT]
                String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File cacheDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
                File backupFile = new File(cacheDir, "VEIL_Vault_" + dateStr + ".veilbackup");

                try (FileOutputStream fos = new FileOutputStream(backupFile)) {
                    fos.write(MAGIC_HEADER);
                    fos.write(FORMAT_VERSION);
                    fos.write(salt);
                    fos.write(iv);
                    fos.write(ciphertext);
                    fos.flush();
                }

                Uri shareableUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", backupFile);
                final int finalChatCount = chats != null ? chats.size() : 0;
                final int finalMsgCount = totalMsgs;

                mainHandler.post(() -> callback.onSuccess(backupFile, shareableUri, finalChatCount, finalMsgCount));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Backup failed: " + e.getMessage()));
            }
        });
    }

    public static void restoreBackup(@NonNull Context context, @NonNull Uri backupUri, @NonNull String passphrase, @NonNull RestoreCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = context.getContentResolver().openInputStream(backupUri)) {
                    if (is == null) {
                        mainHandler.post(() -> callback.onError("Unable to open backup file."));
                        return;
                    }
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                }

                byte[] fileBytes = baos.toByteArray();
                if (fileBytes.length < MAGIC_HEADER.length + 1 + SALT_LENGTH + IV_LENGTH + 16) {
                    mainHandler.post(() -> callback.onError("Invalid backup file: file too small or corrupted."));
                    return;
                }

                ByteBuffer bb = ByteBuffer.wrap(fileBytes);
                byte[] magic = new byte[4];
                bb.get(magic);
                if (magic[0] != MAGIC_HEADER[0] || magic[1] != MAGIC_HEADER[1] ||
                        magic[2] != MAGIC_HEADER[2] || magic[3] != MAGIC_HEADER[3]) {
                    mainHandler.post(() -> callback.onError("Invalid backup file: header signature mismatch."));
                    return;
                }

                byte version = bb.get();
                if (version != FORMAT_VERSION) {
                    mainHandler.post(() -> callback.onError("Unsupported backup file version: " + version));
                    return;
                }

                byte[] salt = new byte[SALT_LENGTH];
                bb.get(salt);

                byte[] iv = new byte[IV_LENGTH];
                bb.get(iv);

                byte[] ciphertext = new byte[bb.remaining()];
                bb.get(ciphertext);

                // Key Derivation
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                PBEKeySpec keySpec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
                SecretKey tmpKey = factory.generateSecret(keySpec);
                SecretKey secretKey = new SecretKeySpec(tmpKey.getEncoded(), "AES");

                // AES-GCM Decrypt
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
                byte[] plaintext = cipher.doFinal(ciphertext);

                String json = new String(plaintext, StandardCharsets.UTF_8);
                Gson gson = new Gson();
                BackupContainer container = gson.fromJson(json, BackupContainer.class);

                if (container == null) {
                    mainHandler.post(() -> callback.onError("Failed to parse backup payload."));
                    return;
                }

                EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(context);
                int restoredChats = 0;
                int restoredMessages = 0;

                if (container.chats != null) {
                    for (Chat c : container.chats) {
                        db.saveOrUpdateChat(c);
                        restoredChats++;
                    }
                }

                if (container.messages != null) {
                    for (Map.Entry<String, List<Message>> entry : container.messages.entrySet()) {
                        String chatId = entry.getKey();
                        List<Message> msgs = entry.getValue();
                        if (msgs != null) {
                            for (Message m : msgs) {
                                db.saveMessage(chatId, m);
                                restoredMessages++;
                            }
                        }
                    }
                }

                final int finalChats = restoredChats;
                final int finalMsgs = restoredMessages;
                mainHandler.post(() -> callback.onSuccess(finalChats, finalMsgs));
            } catch (javax.crypto.AEADBadTagException badTagEx) {
                mainHandler.post(() -> callback.onError("Incorrect backup password or corrupted backup file!"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Restore failed: " + e.getMessage()));
            }
        });
    }

    public static void shareBackupFile(@NonNull Context context, @NonNull Uri uri, @NonNull String title) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/octet-stream");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(shareIntent, "Save or Export VEIL Encrypted Backup"));
    }
}
