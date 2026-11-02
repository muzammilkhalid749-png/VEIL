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
 *   <li>In-memory LRU bitmap cache for zero-jank 120 FPS scrolling with file timestamp cache busting.</li>
 * </ul>
 */
public final class VeilAvatarLoader {

    private static final int CACHE_SIZE = 128;
    private static final java.util.Set<String> sPendingAsyncLoads = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
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
        sPendingAsyncLoads.clear();
    }

    private static boolean isPlaceholderSource(@Nullable String src) {
        if (src == null) return true;
        String s = src.trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) || s.startsWith("default_");
    }

    @NonNull
    private static String buildSourceCacheKey(@NonNull String cleanSource, int sizePx) {
        if (cleanSource.startsWith("/") || cleanSource.startsWith("file://")) {
            String path = cleanSource.startsWith("file://") ? Uri.parse(cleanSource).getPath() : cleanSource;
            if (path != null) {
                File f = new File(path);
                if (f.exists()) {
                    return cleanSource + "_" + f.lastModified() + "_" + f.length() + "_" + sizePx;
                }
            }
        }
        return cleanSource + "_" + sizePx;
    }

    /**
     * Loads an avatar into an ImageView asynchronously.
     * Displays a Telegram-style initials gradient placeholder immediately, then loads the image.
     */
    public static void loadAvatar(@NonNull Context context, @Nullable String avatarSource, @NonNull ImageView imageView) {
        loadAvatar(imageView, avatarSource, "User", false);
    }

    public static void loadAvatar(@NonNull ImageView imageView,
                                  @Nullable String avatarSource,
                                  @Nullable String displayName,
                                  boolean isGroup) {
        String safeName = (displayName != null && !displayName.trim().isEmpty())
                ? displayName.trim() : (isGroup ? "Group" : "User");

        if (isPlaceholderSource(avatarSource)) {
            imageView.setTag(safeName + "_iv");
            Bitmap placeholder = createInitialsBitmap(imageView.getContext(), safeName, 160, isGroup);
            imageView.setImageBitmap(placeholder);
            return;
        }

        final String cleanSource = avatarSource.trim();
        final String sourceCacheKey = buildSourceCacheKey(cleanSource, 160);
        final String tagKey = sourceCacheKey + "_iv";
        imageView.setTag(tagKey);

        // 1. Check LRU Cache first so cached avatars appear with 0ms flash
        Bitmap cached = sAvatarCache.get(sourceCacheKey);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 2. Display Telegram harmonic initials/group placeholder while decoding
        Bitmap placeholder = createInitialsBitmap(imageView.getContext(), safeName, 160, isGroup);
        imageView.setImageBitmap(placeholder);

        // 3. Decode asynchronously
        Context appContext = imageView.getContext().getApplicationContext();
        sExecutor.execute(() -> {
            Bitmap decoded = decodeAvatarBitmap(appContext, cleanSource, 160, isGroup, safeName);
            if (decoded != null) {
                sAvatarCache.put(sourceCacheKey, decoded);
                sMainHandler.post(() -> {
                    if (tagKey.equals(imageView.getTag())) {
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
        String safeName = (displayName != null && !displayName.trim().isEmpty())
                ? displayName.trim() : (isGroup ? "Group" : "User");

        if (isPlaceholderSource(avatarSource)) {
            return getInitialsBitmapCached(context, safeName, sizePx, isGroup);
        }

        String cleanSource = avatarSource.trim();
        String cacheKey = buildSourceCacheKey(cleanSource, sizePx);
        Bitmap cached = sAvatarCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        // Return fast fallback initials immediately for zero-jank 120 FPS scrolling,
        // and queue background asynchronous decoding if not already in-flight.
        if (sPendingAsyncLoads.add(cacheKey)) {
            final int targetSize = sizePx;
            final Context appContext = context.getApplicationContext();
            sExecutor.execute(() -> {
                try {
                    Bitmap decoded = decodeAvatarBitmap(appContext, cleanSource, targetSize, isGroup, safeName);
                    if (decoded != null) {
                        sAvatarCache.put(cacheKey, decoded);
                    } else {
                        Bitmap fallback = getInitialsBitmapCached(appContext, safeName, targetSize, isGroup);
                        sAvatarCache.put(cacheKey, fallback);
                    }
                } finally {
                    sPendingAsyncLoads.remove(cacheKey);
                }
            });
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

            // Case A: Local file path or file:// URI (check before Base64 because paths also start with '/')
            if (source.startsWith("/") || source.startsWith("file://")) {
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

            // Case B: Base64 string
            if (raw == null && (source.startsWith("data:image") || isLikelyBase64(source))) {
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
            if (raw == null && source.length() < 64 && source.matches("^[a-zA-Z0-9_]+$")) {
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
        if (str.startsWith("/9j/") || str.startsWith("iVBOR") || str.startsWith("R0lGOD") || str.startsWith("UklGR")) {
            return true;
        }
        return str.length() > 100
                && !str.startsWith("/")
                && !str.startsWith("file://")
                && !str.startsWith("content://")
                && !str.startsWith("http");
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

    private static final java.util.Set<String> sFetchedFirestorePeers =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Map<String, String> sPeerAvatarSourceCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isCustomAvatarSource(@Nullable String av) {
        if (av == null) return false;
        String clean = av.trim();
        return !clean.isEmpty() && !"null".equalsIgnoreCase(clean)
                && !"default".equalsIgnoreCase(clean) && !clean.startsWith("avatar_");
    }

    @Nullable
    public static String resolvePeerAvatarSource(@NonNull Context context, @Nullable String myUserId, @Nullable String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) return null;
        String cleanPeerId = peerId.trim();
        String cachedSource = sPeerAvatarSourceCache.get(cleanPeerId);
        if (isCustomAvatarSource(cachedSource)) {
            return cachedSource;
        }
        try {
            com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(context);
            String myId = myUserId;
            if (myId == null || myId.isEmpty() || "local_user".equals(myId)) {
                myId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(context);
            }
            if (myId != null && !myId.isEmpty() && !cleanPeerId.startsWith("group_") && !cleanPeerId.startsWith("channel_")) {
                String directChatId = com.m3.veil.database.EncryptedChatDatabase.generateDirectChatId(myId, cleanPeerId);
                com.m3.veil.model.Chat directChat = db.getChat(directChatId);
                if (directChat != null && isCustomAvatarSource(directChat.getAvatar())) {
                    sPeerAvatarSourceCache.put(cleanPeerId, directChat.getAvatar());
                    return directChat.getAvatar();
                }
            }
            com.m3.veil.model.Chat byIdChat = db.getChat(cleanPeerId);
            if (byIdChat != null && isCustomAvatarSource(byIdChat.getAvatar())) {
                sPeerAvatarSourceCache.put(cleanPeerId, byIdChat.getAvatar());
                return byIdChat.getAvatar();
            }
            String explicitContactAv = db.getExplicitContactAvatar(cleanPeerId);
            if (isCustomAvatarSource(explicitContactAv)) {
                sPeerAvatarSourceCache.put(cleanPeerId, explicitContactAv);
                return explicitContactAv;
            }
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest = db.getSovereignProfileManifest(cleanPeerId);
            if (manifest != null && isCustomAvatarSource(manifest.avatarBase64)) {
                sPeerAvatarSourceCache.put(cleanPeerId, manifest.avatarBase64);
                return manifest.avatarBase64;
            }
            String basePeerId = cleanPeerId.contains("_persona_")
                    ? cleanPeerId.substring(0, cleanPeerId.indexOf("_persona_")) : cleanPeerId;
            if (!basePeerId.equals(cleanPeerId)) {
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest baseManifest = db.getSovereignProfileManifest(basePeerId);
                if (baseManifest != null && isCustomAvatarSource(baseManifest.avatarBase64)) {
                    sPeerAvatarSourceCache.put(cleanPeerId, baseManifest.avatarBase64);
                    return baseManifest.avatarBase64;
                }
            }
            java.util.List<com.m3.veil.model.Chat> allChats = db.getAllChats();
            if (allChats != null) {
                for (com.m3.veil.model.Chat c : allChats) {
                    if (c == null || c.isGroup()) continue;
                    boolean matches = cleanPeerId.equals(c.getId())
                            || (c.getId() != null && (c.getId().startsWith(cleanPeerId + "_") || c.getId().endsWith("_" + cleanPeerId)))
                            || (c.getParticipants() != null && c.getParticipants().contains(cleanPeerId));
                    if (matches && isCustomAvatarSource(c.getAvatar())) {
                        sPeerAvatarSourceCache.put(cleanPeerId, c.getAvatar());
                        return c.getAvatar();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> sPeerFirestoreCheckTimeMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void evictPeerAvatar(@Nullable Context context, @Nullable String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) return;
        String cleanPeer = peerId.trim();
        String basePeer = cleanPeer.contains("_persona_")
                ? cleanPeer.substring(0, cleanPeer.indexOf("_persona_")) : cleanPeer;
        sPeerAvatarSourceCache.remove(cleanPeer);
        sPeerAvatarSourceCache.remove(basePeer);
        sFetchedFirestorePeers.remove(cleanPeer);
        sFetchedFirestorePeers.remove(basePeer);
        if (context != null) {
            try {
                Context appCtx = context.getApplicationContext();
                com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(appCtx);
                db.clearPeerAvatarEverywhere(cleanPeer);
                if (!basePeer.equals(cleanPeer)) {
                    db.clearPeerAvatarEverywhere(basePeer);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void cachePeerAvatarSource(@Nullable Context context, @Nullable String peerId, @Nullable String avatarSource) {
        if (peerId == null || peerId.trim().isEmpty()) return;
        if (isCustomAvatarSource(avatarSource)) {
            cachePeerAvatarSource(peerId, avatarSource);
        } else if (avatarSource != null) {
            evictPeerAvatar(context, peerId);
        }
    }

    public static void cachePeerAvatarSource(@Nullable String peerId, @Nullable String avatarSource) {
        if (peerId != null && !peerId.trim().isEmpty()) {
            if (isCustomAvatarSource(avatarSource)) {
                sPeerAvatarSourceCache.put(peerId.trim(), avatarSource.trim());
            } else if (avatarSource != null) {
                sPeerAvatarSourceCache.remove(peerId.trim());
            }
        }
    }

    public static void loadPeerAvatar(@Nullable ImageView imageView,
                                      @Nullable String peerId,
                                      @Nullable String fallbackAvatarSource,
                                      @Nullable String displayName) {
        if (isCustomAvatarSource(fallbackAvatarSource)) {
            cachePeerAvatarSource(peerId, fallbackAvatarSource);
        }
        loadPeerAvatar(imageView, null, peerId, displayName, false);
    }

    public static void loadPeerAvatar(@Nullable ImageView imageView,
                                      @Nullable String myUserId,
                                      @Nullable String peerId,
                                      @Nullable String displayName,
                                      boolean isGroup) {
        if (imageView == null) return;
        Context context = imageView.getContext();
        if (context == null) return;

        String safeName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : "Veil Contact";
        final String avatarSource = resolvePeerAvatarSource(context, myUserId, peerId);

        if (isGroup) {
            loadAvatar(imageView, avatarSource, safeName, true);
            return;
        }

        loadAvatar(imageView, avatarSource, safeName, false);

        if (peerId != null && !peerId.trim().isEmpty()
                && !peerId.startsWith("group_") && !peerId.startsWith("channel_")) {
            final String cleanPeer = peerId.trim();
            final String lookupDocId = cleanPeer.contains("_persona_")
                    ? cleanPeer.substring(0, cleanPeer.indexOf("_persona_")) : cleanPeer;
            imageView.setTag(cleanPeer);
            long now = System.currentTimeMillis();
            Long lastCheck = sPeerFirestoreCheckTimeMs.get(cleanPeer);
            if (lastCheck == null || (now - lastCheck) > 20_000L) {
                sPeerFirestoreCheckTimeMs.put(cleanPeer, now);
                final Context appCtx = context.getApplicationContext();
                try {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                            .collection("users").document(lookupDocId).get()
                            .addOnSuccessListener(doc -> {
                                if (doc != null && doc.exists()) {
                                    com.m3.veil.model.User u = doc.toObject(com.m3.veil.model.User.class);
                                    String rawDocAvatar = doc.getString("avatar");
                                    String remoteAvatar = (rawDocAvatar != null) ? rawDocAvatar : (u != null ? u.getAvatar() : null);
                                    String remoteName = u != null && u.getDisplayName() != null ? u.getDisplayName() : doc.getString("displayName");
                                    String remoteUser = u != null && u.getUsername() != null ? u.getUsername() : doc.getString("username");
                                    if (isCustomAvatarSource(remoteAvatar)) {
                                        sPeerAvatarSourceCache.put(cleanPeer, remoteAvatar);
                                        sPeerAvatarSourceCache.put(lookupDocId, remoteAvatar);
                                        try {
                                            com.m3.veil.database.EncryptedChatDatabase db = com.m3.veil.database.EncryptedChatDatabase.getInstance(appCtx);
                                            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest cur = db.getSovereignProfileManifest(cleanPeer);
                                            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest next =
                                                    cur != null ? cur : new com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest();
                                            next.uid = cleanPeer;
                                            next.username = remoteUser != null ? remoteUser : (cur != null && cur.username != null ? cur.username : "");
                                            next.displayName = remoteName != null ? remoteName : (cur != null && cur.displayName != null ? cur.displayName : safeName);
                                            next.avatarBase64 = remoteAvatar;
                                            next.updatedAtTimestamp = System.currentTimeMillis();
                                            db.saveSovereignProfileManifest(next);
                                            String myId = myUserId != null ? myUserId : com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(appCtx);
                                            if (myId != null && !myId.isEmpty()) {
                                                String dChatId = com.m3.veil.database.EncryptedChatDatabase.generateDirectChatId(myId, cleanPeer);
                                                com.m3.veil.model.Chat existingChat = db.getChat(dChatId);
                                                if (existingChat != null && !remoteAvatar.equals(existingChat.getAvatar())) {
                                                    existingChat.setAvatar(remoteAvatar);
                                                    db.saveOrUpdateChat(existingChat);
                                                }
                                            }
                                        } catch (Throwable ignored) {}
                                        if (!remoteAvatar.equals(avatarSource)) {
                                            imageView.post(() -> loadAvatar(imageView, remoteAvatar, safeName, false));
                                        }
                                    } else {
                                        // Remote user has no custom avatar (removed DP or default): evict any stale cached avatar
                                        boolean hadCustomLocal = isCustomAvatarSource(avatarSource)
                                                || isCustomAvatarSource(sPeerAvatarSourceCache.get(cleanPeer));
                                        evictPeerAvatar(appCtx, cleanPeer);
                                        if (hadCustomLocal) {
                                            imageView.post(() -> loadAvatar(imageView, null, safeName, false));
                                        }
                                    }
                                }
                            });
                } catch (Throwable ignored) {}
            }
        }
    }
}
