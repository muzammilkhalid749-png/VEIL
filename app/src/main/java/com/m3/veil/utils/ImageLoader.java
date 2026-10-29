package com.m3.veil.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.m3.veil.model.Message;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService DECODE_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VeilImageDecodePool");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<String, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024); // KB
        }
    };

    public static void clearCache() {
        BITMAP_CACHE.evictAll();
    }

    public static void loadImage(Context context, Message message, String rsaPrivateKeyString, String currentUserId, ImageView imageView, ProgressBar progressBar) {
        if (context == null || message == null || imageView == null) return;
        File cacheDir = context.getExternalCacheDir();
        File decryptedGif = new File(cacheDir, "dec_" + message.getId() + ".gif");
        File decryptedFile = decryptedGif.exists() && decryptedGif.length() > 0
                ? decryptedGif
                : new File(cacheDir, "dec_" + message.getId() + ".jpg");

        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File local = new File(message.getLocalFilePath());
            if (local.exists() && local.length() > 0) {
                if (VeilStickerGifEngine.isGifFile(local)) {
                    android.graphics.drawable.Drawable gif = VeilStickerGifEngine.loadAnimatedGifFromFile(local);
                    if (gif != null) {
                        imageView.setImageDrawable(gif);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        return;
                    }
                }
                displayImage(local.getAbsolutePath(), imageView, progressBar);
                return;
            }
        }

        if (decryptedFile.exists() && decryptedFile.length() > 0) {
            if (VeilStickerGifEngine.isGifFile(decryptedFile)) {
                android.graphics.drawable.Drawable gif = VeilStickerGifEngine.loadAnimatedGifFromFile(decryptedFile);
                if (gif != null) {
                    imageView.setImageDrawable(gif);
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    return;
                }
            }
            displayImage(decryptedFile.getAbsolutePath(), imageView, progressBar);
        } else {
            // Display base64 thumbnail if available while WebRTC P2P streams full image
            if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
                Bitmap thumb = BlurUtils.getBlurredThumbnail(context, message.getThumbnail());
                if (thumb != null && !thumb.isRecycled()) {
                    imageView.setImageBitmap(thumb);
                }
            }
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
        }
    }

    private static void displayImage(final String filePath, final ImageView imageView, final ProgressBar progressBar) {
        if (filePath == null || imageView == null) return;

        Bitmap cached = BITMAP_CACHE.get(filePath);
        if (cached != null && !cached.isRecycled()) {
            imageView.setTag(filePath);
            imageView.setImageBitmap(cached);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            return;
        }

        imageView.setTag(filePath);
        DECODE_EXECUTOR.execute(() -> {
            try {
                Bitmap cachedNow = BITMAP_CACHE.get(filePath);
                if (cachedNow != null && !cachedNow.isRecycled()) {
                    MAIN_HANDLER.post(() -> {
                        if (filePath.equals(imageView.getTag())) {
                            imageView.setImageBitmap(cachedNow);
                        }
                        if (progressBar != null) {
                            progressBar.setVisibility(View.GONE);
                        }
                    });
                    return;
                }

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(filePath, options);
                if (options.outWidth > 0 && options.outHeight > 0) {
                    VeilStickerGifEngine.cacheDimensions(filePath, options.outWidth, options.outHeight);
                }

                int targetSize = 720;
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
                final Bitmap bitmap = BitmapFactory.decodeFile(filePath, decodeOpts);
                if (bitmap != null) {
                    BITMAP_CACHE.put(filePath, bitmap);
                }

                MAIN_HANDLER.post(() -> {
                    if (bitmap != null && !bitmap.isRecycled() && filePath.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                });
            } catch (Throwable t) {
                MAIN_HANDLER.post(() -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                });
            }
        });
    }
}
