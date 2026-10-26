package com.m3.veil.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.m3.veil.R;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telegram-Parity Universal Avatar Engine for VEIL.
 * Modeled on Telegram's {@code AvatarDrawable} and {@code ImageReceiver}.
 *
 * <p>Key Capabilities:
 * <ul>
 *   <li>Instant synchronous rendering for custom Canvas views (e.g. {@code VeilDialogCell}).</li>
 *   <li>Asynchronous decoded loading for {@link ImageView} and {@link de.hdodenhof.circleimageview.CircleImageView}.</li>
 *   <li>Universal source support: Base64 strings, file:// paths, content:// URIs, and drawable resource keys.</li>
 *   <li>Telegram harmonic two-stop gradient circle with centered bold initials when no image exists.</li>
 *   <li>In-memory LRU bitmap cache for zero-jank 120 FPS scrolling.</li>
 * </ul>
 */
public final class VeilAvatarLoader {

    private static final int CACHE_SIZE = 64;
    private static final LruCache<String, Bitmap> sAvatarCache = new LruCache<>(CACHE_SIZE);
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    // Telegram Harmonic Gradient Palette
    private static final int[][] GRADIENTS = {
            {0xFF6366F1, 0xFF4F46E5}, // Indigo
            {0xFFEC4899, 0xFFDB2777}, // Pink
            {0xFF10B981, 0xFF059669}, // Emerald
            {0xFFF59E0B, 0xFFD97706}, // Amber
            {0xFF3B82F6, 0xFF2563EB}, // Blue
            {0xFF8B5CF6, 0xFF7C3AED}, // Violet
            {0xFF14B8A6, 0xFF0D9488}, // Teal
            {0xFFF43F5E, 0xFFE11D48}  // Rose
    };

    private VeilAvatarLoader() {}

    /**
     * Clears in-memory avatar cache on memory pressure or user profile update.
     */
    public static void clearCache() {
        sAvatarCache.evictAll();
    }

    /**
     * Loads an avatar into an ImageView asynchronously.
     * Displays a Telegram-style initials gradient placeholder immediately, then loads the image.
     */
    public static void loadAvatar(@NonNull ImageView imageView,
                                  @Nullable String avatarSource,
                                  @Nullable String displayName,
                                  boolean isGroup) {
        String safeName = displayName != null ? displayName : (isGroup ? "Group" : "User");
        String cacheKey = (avatarSource != null ? avatarSource : safeName) + "_iv";
        imageView.setTag(cacheKey);

        // 1. Immediately display Telegram harmonic initials/group placeholder
        Bitmap placeholder = createInitialsBitmap(imageView.getContext(), safeName, 160, isGroup);
        imageView.setImageBitmap(placeholder);

        if (avatarSource == null || avatarSource.trim().isEmpty()) {
            return;
        }

        final String cleanSource = avatarSource.trim();

        // 2. Check LRU Cache
        Bitmap cached = sAvatarCache.get(cleanSource + "_160");
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 3. Decode asynchronously
        Context appContext = imageView.getContext().getApplicationContext();
        sExecutor.execute(() -> {
            Bitmap decoded = decodeAvatarBitmap(appContext, cleanSource, 160, isGroup, safeName);
            if (decoded != null) {
                sAvatarCache.put(cleanSource + "_160", decoded);
                sMainHandler.post(() -> {
                    if (cacheKey.equals(imageView.getTag())) {
                        imageView.setImageBitmap(decoded);
                    }
                });
            }
        });
    }

    /**
     * Synchronously resolves and returns a circular Bitmap for custom Canvas drawing (e.g. {@code VeilDialogCell}).
     */
    @Nullable
    public static Bitmap getAvatarBitmap(@NonNull Context context,
                                         @Nullable String avatarSource,
                                         @Nullable String displayName,
                                         int sizePx,
                                         boolean isGroup) {
        if (sizePx <= 0) sizePx = 120;
        String safeName = displayName != null ? displayName : (isGroup ? "Group" : "User");

        if (avatarSource == null || avatarSource.trim().isEmpty()) {
            return getInitialsBitmapCached(context, safeName, sizePx, isGroup);
        }

        String cleanSource = avatarSource.trim();
        String cacheKey = cleanSource + "_" + sizePx;
        Bitmap cached = sAvatarCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        Bitmap decoded = decodeAvatarBitmap(context, cleanSource, sizePx, isGroup, safeName);
        if (decoded != null) {
            sAvatarCache.put(cacheKey, decoded);
            return decoded;
        }

        return getInitialsBitmapCached(context, safeName, sizePx, isGroup);
    }

    private static Bitmap getInitialsBitmapCached(Context context, String displayName, int sizePx, boolean isGroup) {
        String key = "initials_" + displayName + "_" + sizePx + "_" + isGroup;
        Bitmap cached = sAvatarCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap bmp = createInitialsBitmap(context, displayName, sizePx, isGroup);
        sAvatarCache.put(key, bmp);
        return bmp;
    }

    /**
     * Decodes any avatar source into a circular scaled Bitmap.
     */
    @Nullable
    private static Bitmap decodeAvatarBitmap(Context context,
                                             String source,
                                             int sizePx,
                                             boolean isGroup,
                                             String displayName) {
        try {
            Bitmap raw = null;

            // Case A: Base64 string
            if (source.startsWith("data:image") || isLikelyBase64(source)) {
                String cleanBase64 = source;
                int commaIdx = cleanBase64.indexOf(",");
                if (commaIdx != -1) {
                    cleanBase64 = cleanBase64.substring(commaIdx + 1);
                }
                cleanBase64 = cleanBase64.replaceAll("\\s", "");
                byte[] bytes = Base64.decode(cleanBase64, Base64.DEFAULT);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            }

            // Case B: Local file path or file:// URI
            if (raw == null && (source.startsWith("/") || source.startsWith("file://"))) {
                String filePath = source.startsWith("file://") ? Uri.parse(source).getPath() : source;
                if (filePath != null && new File(filePath).exists()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(filePath, opts);
                    opts.inSampleSize = calculateInSampleSize(opts, sizePx, sizePx);
                    opts.inJustDecodeBounds = false;
                    raw = BitmapFactory.decodeFile(filePath, opts);
                }
            }

            // Case C: Android Content URI (content://)
            if (raw == null && source.startsWith("content://")) {
                Uri contentUri = Uri.parse(source);
                ContentResolver cr = context.getContentResolver();
                try (InputStream in = cr.openInputStream(contentUri)) {
                    if (in != null) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        raw = BitmapFactory.decodeStream(in, null, opts);
                    }
                }
            }

            // Case D: Resource Identifier (e.g. "avatar_1" or "ic_person")
            if (raw == null) {
                int resId = context.getResources().getIdentifier(source, "drawable", context.getPackageName());
                if (resId != 0) {
                    Drawable d = ContextCompat.getDrawable(context, resId);
                    if (d instanceof BitmapDrawable) {
                        raw = ((BitmapDrawable) d).getBitmap();
                    } else if (d != null) {
                        raw = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(raw);
                        d.setBounds(0, 0, sizePx, sizePx);
                        d.draw(c);
                    }
                }
            }

            if (raw == null) {
                return null;
            }

            // Crop to circular bitmap of exact sizePx
            return createCircularBitmap(raw, sizePx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isLikelyBase64(String str) {
        if (str.length() < 30) return false;
        return str.startsWith("/9j/") || str.startsWith("iVBOR") || str.startsWith("R0lGOD") || str.startsWith("UklGR");
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    /**
     * Scales and clips any bitmap to a circle with smooth anti-aliased edge.
     */
    @NonNull
    public static Bitmap createCircularBitmap(@NonNull Bitmap source, int sizePx) {
        Bitmap output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path path = new Path();
        float radius = sizePx / 2f;
        path.addCircle(radius, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);

        // Center crop source bitmap
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        Rect srcRect;
        if (srcW > srcH) {
            int left = (srcW - srcH) / 2;
            srcRect = new Rect(left, 0, left + srcH, srcH);
        } else {
            int top = (srcH - srcW) / 2;
            srcRect = new Rect(0, top, srcW, top + srcW);
        }
        Rect dstRect = new Rect(0, 0, sizePx, sizePx);
        canvas.drawBitmap(source, srcRect, dstRect, paint);

        return output;
    }

    /**
     * Generates a Telegram-parity circular initials gradient bitmap.
     */
    @NonNull
    public static Bitmap createInitialsBitmap(@NonNull Context context,
                                              @Nullable String displayName,
                                              int sizePx,
                                              boolean isGroup) {
        if (sizePx <= 0) sizePx = 120;
        Bitmap output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        String name = displayName != null ? displayName.trim() : (isGroup ? "Group" : "User");
        int colorIdx = Math.abs(name.hashCode()) % GRADIENTS.length;
        int[] grad = GRADIENTS[colorIdx];

        // 1. Draw gradient circle
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setShader(new LinearGradient(
                0, 0, 0, sizePx,
                grad[0], grad[1],
                Shader.TileMode.CLAMP
        ));
        float r = sizePx / 2f;
        canvas.drawCircle(r, r, r, circlePaint);

        // 2. Draw Group Icon or Initials
        if (isGroup) {
            Drawable groupDrawable = ContextCompat.getDrawable(context, R.drawable.ic_group);
            if (groupDrawable != null) {
                int pad = (int) (sizePx * 0.26f);
                groupDrawable.setBounds(pad, pad, sizePx - pad, sizePx - pad);
                groupDrawable.setTint(Color.WHITE);
                groupDrawable.draw(canvas);
                return output;
            }
        }

        // Draw Initials
        String initials = extractInitials(name);
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(sizePx * 0.42f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textBaseline = r - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(initials, r, textBaseline, textPaint);

        return output;
    }

    @NonNull
    public static String extractInitials(@NonNull String name) {
        String clean = name.replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
        if (clean.isEmpty()) return "V";
        String[] parts = clean.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (String.valueOf(parts[0].charAt(0)) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
        }
        return String.valueOf(clean.charAt(0)).toUpperCase(Locale.ROOT);
    }
}
