package com.m3.veil.utils;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.model.Message;

import java.io.File;

public class FileMediaManager {
    private static final String TAG = "FileMediaManager";

    public interface DownloadCallback {
        void onSuccess(File decryptedFile);
        void onProgress(boolean inProgress);
        void onError(Exception e);
    }

    public static File getDecryptedFile(Context context, Message message, String defaultExt) {
        if (message == null || context == null) return null;
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File local = new File(message.getLocalFilePath());
            if (local.exists() && local.length() > 0 && !local.getName().startsWith("staged_")) {
                return local;
            }
        }
        String ext = com.m3.veil.manager.VeilMessagesController.resolveMediaExtension(message);
        if ((ext == null || ext.isEmpty() || ".bin".equals(ext)) && defaultExt != null && !defaultExt.isEmpty()) {
            ext = defaultExt;
        }
        if (ext == null) ext = ".bin";
        File target = new File(context.getExternalCacheDir(), "dec_" + message.getId() + ext);
        if (target.exists() && target.length() > 0) {
            return target;
        }
        if (defaultExt != null && !defaultExt.equals(ext)) {
            File alt = new File(context.getExternalCacheDir(), "dec_" + message.getId() + defaultExt);
            if (alt.exists() && alt.length() > 0) {
                return alt;
            }
        }
        return target;
    }

    public static void downloadAndDecryptMedia(Context context, Message message, String defaultExt, DownloadCallback callback) {
        if (context == null || message == null) {
            if (callback != null) callback.onError(new Exception("Invalid media message"));
            return;
        }
        File decryptedFile = getDecryptedFile(context, message, defaultExt);
        if (decryptedFile != null && decryptedFile.exists() && decryptedFile.length() > 0) {
            if (callback != null) callback.onSuccess(decryptedFile);
            return;
        }

        String dialogId = message.getChatId();
        File inlineDecoded = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(context, dialogId, message);
        if (inlineDecoded != null && inlineDecoded.exists() && inlineDecoded.length() > 0) {
            if (callback != null) callback.onSuccess(inlineDecoded);
            return;
        }

        if (message.getMediaUrl() != null && message.getMediaUrl().startsWith("CHUNKED:v1:") && dialogId != null && !dialogId.isEmpty()) {
            if (callback != null) callback.onProgress(true);
            com.m3.veil.manager.VeilMessagesController.downloadChunkedMediaFromFirestore(
                    context.getApplicationContext(),
                    dialogId,
                    message,
                    message.getMediaUrl(),
                    new com.m3.veil.webrtc.P2PFileTransferHelper.DownloadProgressListener() {
                        @Override
                        public void onProgress(String messageId, int progress) {
                            if (callback != null) callback.onProgress(true);
                        }

                        @Override
                        public void onComplete(String messageId, File downloadedFile) {
                            if (callback != null) {
                                callback.onProgress(false);
                                callback.onSuccess(downloadedFile);
                            }
                        }

                        @Override
                        public void onError(String messageId, String error) {
                            if (callback != null) {
                                callback.onProgress(false);
                                callback.onError(new Exception(error != null ? error : "Media transfer failed"));
                            }
                        }
                    });
            return;
        }

        if (callback != null) {
            callback.onProgress(false);
            callback.onError(new Exception("Media awaiting peer-to-peer WebRTC transfer"));
        }
    }

    public static void openDocumentWithOriginalName(Context context, File file, String rawDisplayFileName) {
        if (file == null || !file.exists()) {
            Toast.makeText(context, "File is not downloaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        File fileToOpen = file;
        String cleanName = com.m3.veil.manager.VeilMessagesController.cleanDocumentFileName(rawDisplayFileName);
        if (cleanName != null && !cleanName.isEmpty() && ! "Document".equals(cleanName)) {
            try {
                File openDir = new File(context.getExternalCacheDir(), "open_docs");
                if (!openDir.exists()) openDir.mkdirs();
                File namedCopy = new File(openDir, cleanName);
                if (!namedCopy.exists() || namedCopy.length() != file.length()) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(file);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(namedCopy)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                        out.flush();
                    }
                }
                if (namedCopy.exists() && namedCopy.length() > 0) {
                    fileToOpen = namedCopy;
                }
            } catch (Exception ignored) {}
        }
        openFileWithSystemViewer(context, fileToOpen);
    }

    public static void openFileWithSystemViewer(Context context, File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, "Open " + file.getName()));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "Could not open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void openLocationInMaps(Context context, String locationText) {
        if (locationText == null || locationText.trim().isEmpty()) return;
        try {
            Uri mapUri;
            if (locationText.startsWith("geo:")) {
                mapUri = Uri.parse(locationText);
            } else {
                mapUri = Uri.parse("geo:0,0?q=" + Uri.encode(locationText));
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, mapUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Fallback to browser Google Maps
            try {
                String query = locationText.replace("geo:", "");
                int q = query.indexOf("?q=");
                if (q != -1) query = query.substring(q + 3);
                Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query));
                Intent webIntent = new Intent(Intent.ACTION_VIEW, webUri);
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(webIntent);
            } catch (Exception ex) {
                Toast.makeText(context, "Unable to open maps: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Unable to open maps: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void openContactDialer(Context context, String phone) {
        if (phone == null || phone.trim().isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Unable to dial: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void saveContactToPhonebook(Context context, String name, String phone) {
        try {
            Intent intent = new Intent(Intent.ACTION_INSERT);
            intent.setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE);
            if (name != null && !name.isEmpty()) {
                intent.putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name);
            }
            if (phone != null && !phone.isEmpty()) {
                intent.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Unable to save contact: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static int getDocumentBadgeColor(String extension) {
        if (extension == null) return 0xFF64748B;
        String ext = extension.toLowerCase(java.util.Locale.ROOT).replace(".", "");
        switch (ext) {
            case "pdf":
                return 0xFFEF4444; // Red
            case "doc":
            case "docx":
            case "txt":
            case "rtf":
                return 0xFF3B82F6; // Blue
            case "xls":
            case "xlsx":
            case "csv":
                return 0xFF10B981; // Green
            case "ppt":
            case "pptx":
                return 0xFFF59E0B; // Orange
            case "zip":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
                return 0xFF8B5CF6; // Purple
            case "apk":
                return 0xFF059669; // Emerald Teal
            case "mp4":
            case "mkv":
            case "mov":
            case "avi":
            case "webm":
                return 0xFF6366F1; // Indigo
            case "jpg":
            case "jpeg":
            case "png":
            case "webp":
            case "gif":
                return 0xFF0EA5E9; // Sky Blue
            case "mp3":
            case "wav":
            case "aac":
            case "flac":
            case "m4a":
                return 0xFFEC4899; // Pink
            default:
                return 0xFF64748B; // Slate
        }
    }

    public static String getMimeType(String filename) {
        String extension = "";
        int i = filename.lastIndexOf('.');
        if (i > 0) {
            extension = filename.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
        }
        android.webkit.MimeTypeMap mime = android.webkit.MimeTypeMap.getSingleton();
        String type = mime.getMimeTypeFromExtension(extension);
        return type != null ? type : "*/*";
    }
}
