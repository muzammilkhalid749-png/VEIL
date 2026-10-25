package com.m3.veil.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.m3.veil.model.Message;

import java.io.File;

public class ImageLoader {
    private static final String TAG = "ImageLoader";

    public static void loadImage(Context context, Message message, String rsaPrivateKeyString, String currentUserId, ImageView imageView, ProgressBar progressBar) {
        File cacheDir = context.getExternalCacheDir();
        File decryptedFile = new File(cacheDir, "dec_" + message.getId() + ".jpg");

        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File local = new File(message.getLocalFilePath());
            if (local.exists() && local.length() > 0) {
                displayImage(local.getAbsolutePath(), imageView, progressBar);
                return;
            }
        }

        if (decryptedFile.exists() && decryptedFile.length() > 0) {
            displayImage(decryptedFile.getAbsolutePath(), imageView, progressBar);
        } else {
            // Display base64 thumbnail if available while WebRTC P2P streams full image
            if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
                try {
                    byte[] decoded = android.util.Base64.decode(message.getThumbnail(), android.util.Base64.DEFAULT);
                    Bitmap thumb = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    if (thumb != null) {
                        imageView.setImageBitmap(thumb);
                    }
                } catch (Exception ignored) {}
            }
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
        }
    }

    private static void displayImage(String filePath, ImageView imageView, ProgressBar progressBar) {
        if (filePath == null) return;
        new Thread(() -> {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(filePath, options);

                int targetSize = 800;
                int inSampleSize = 1;
                if (options.outHeight > targetSize || options.outWidth > targetSize) {
                    final int halfHeight = options.outHeight / 2;
                    final int halfWidth = options.outWidth / 2;
                    while ((halfHeight / inSampleSize) >= targetSize && (halfWidth / inSampleSize) >= targetSize) {
                        inSampleSize *= 2;
                    }
                }

                BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                decodeOpts.inSampleSize = inSampleSize;
                decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeFile(filePath, decodeOpts);

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                    }
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                });
            } catch (Throwable t) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeFile(filePath);
                        if (bitmap != null) imageView.setImageBitmap(bitmap);
                    } catch (Throwable ignored) {}
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }
}
