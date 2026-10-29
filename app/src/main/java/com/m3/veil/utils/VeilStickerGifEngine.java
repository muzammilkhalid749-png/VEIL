package com.m3.veil.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.Base64;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.m3.veil.model.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Telegram & WhatsApp Parity Engine for:
 * 1. Dynamic Aspect-Ratio Media Bubbles (Portrait, Landscape, Square) for Photos, Videos & GIFs.
 * 2. Native Animated GIF Playback (ImageDecoder / AnimatedImageDrawable on API 28+, Movie canvas fallback,
 *    plus 60fps procedural looping GIF scenes).
 * 3. Chromeless Animated Stickers (Telegram-style borderless looping animated stickers).
 */
public class VeilStickerGifEngine {

    private static final LruCache<String, int[]> DIMENSION_CACHE = new LruCache<>(256);

    // =========================================================================
    // 1. DYNAMIC MEDIA BUBBLE ASPECT-RATIO ENGINE (WHATSAPP / TELEGRAM PARITY)
    // =========================================================================

    /**
     * Caches known source dimensions [width, height] for a message or file path.
     */
    public static void cacheDimensions(@Nullable String key, int width, int height) {
        if (key == null || key.isEmpty() || width <= 0 || height <= 0) return;
        DIMENSION_CACHE.put(key, new int[]{width, height});
    }

    /**
     * Resolves the intrinsic [width, height] of a photo, video, or GIF message without full bitmap allocation.
     */
    @Nullable
    public static int[] resolveMediaIntrinsicDimensions(@NonNull Context context, @Nullable Message message, @Nullable File localFile) {
        if (message == null) return null;

        String msgKey = message.getId() != null ? "msg_" + message.getId() : null;
        if (msgKey != null) {
            int[] cached = DIMENSION_CACHE.get(msgKey);
            if (cached != null && cached[0] > 0 && cached[1] > 0) {
                return cached;
            }
        }

        // 1. Built-in GIF preset aspect ratios (if message is a preset GIF)
        String rawText = message.getText() != null ? message.getText().trim() : "";
        if (rawText.startsWith("[GIF:")) {
            GifPreset preset = findGifPresetByCode(rawText);
            if (preset != null) {
                int[] dims = new int[]{preset.intrinsicWidth, preset.intrinsicHeight};
                if (msgKey != null) cacheDimensions(msgKey, dims[0], dims[1]);
                return dims;
            }
        }

        // 2. Check local file path or provided decrypted file
        File fileToInspect = localFile;
        if ((fileToInspect == null || !fileToInspect.exists() || fileToInspect.length() < 32)
                && message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File candidate = new File(message.getLocalFilePath());
            if (candidate.exists() && candidate.length() >= 32) {
                fileToInspect = candidate;
            }
        }
        if (fileToInspect == null || !fileToInspect.exists() || fileToInspect.length() < 32) {
            String type = message.getType();
            String ext = "video".equals(type) ? ".mp4" : ("gif".equals(type) ? ".gif" : ".jpg");
            File dec = FileMediaManager.getDecryptedFile(context, message, ext);
            if (dec != null && dec.exists() && dec.length() >= 32) {
                fileToInspect = dec;
            }
        }

        if (fileToInspect != null && fileToInspect.exists() && fileToInspect.length() >= 32) {
            String pathKey = fileToInspect.getAbsolutePath();
            int[] pathCached = DIMENSION_CACHE.get(pathKey);
            if (pathCached != null && pathCached[0] > 0 && pathCached[1] > 0) {
                if (msgKey != null) cacheDimensions(msgKey, pathCached[0], pathCached[1]);
                return pathCached;
            }

            if ("video".equals(message.getType()) || pathKey.endsWith(".mp4")) {
                // First try fast base64 thumbnail header if it has non-square aspect ratio
                int[] thumbDims = decodeBase64ThumbnailBounds(message.getThumbnail());
                if (thumbDims != null && thumbDims[0] > 0 && thumbDims[1] > 0 && thumbDims[0] != thumbDims[1]) {
                    cacheDimensions(pathKey, thumbDims[0], thumbDims[1]);
                    if (msgKey != null) cacheDimensions(msgKey, thumbDims[0], thumbDims[1]);
                    return thumbDims;
                }
                // Otherwise inspect video metadata header
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(pathKey);
                    String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    String rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (wStr != null && hStr != null) {
                        int vw = Integer.parseInt(wStr);
                        int vh = Integer.parseInt(hStr);
                        int rot = rotStr != null ? Integer.parseInt(rotStr) : 0;
                        if (rot == 90 || rot == 270) {
                            int tmp = vw;
                            vw = vh;
                            vh = tmp;
                        }
                        if (vw > 0 && vh > 0) {
                            int[] dims = new int[]{vw, vh};
                            cacheDimensions(pathKey, vw, vh);
                            if (msgKey != null) cacheDimensions(msgKey, vw, vh);
                            return dims;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        retriever.release();
                    } catch (Throwable ignored) {}
                }
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(pathKey, opts);
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    int[] dims = new int[]{opts.outWidth, opts.outHeight};
                    cacheDimensions(pathKey, dims[0], dims[1]);
                    if (msgKey != null) cacheDimensions(msgKey, dims[0], dims[1]);
                    return dims;
                }
            }
        }

        // 3. Fallback to Base64 thumbnail bounds
        int[] thumbDims = decodeBase64ThumbnailBounds(message.getThumbnail());
        if (thumbDims != null && thumbDims[0] > 0 && thumbDims[1] > 0) {
            if (msgKey != null) cacheDimensions(msgKey, thumbDims[0], thumbDims[1]);
            return thumbDims;
        }

        return null;
    }

    @Nullable
    private static int[] decodeBase64ThumbnailBounds(@Nullable String base64Thumb) {
        if (base64Thumb == null || base64Thumb.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64Thumb, Base64.DEFAULT);
            if (bytes == null || bytes.length < 16) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                return new int[]{opts.outWidth, opts.outHeight};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Computes WhatsApp/Telegram-grade bubble card [widthPx, heightPx] from intrinsic media dimensions:
     * - Portrait (e.g. 9:16, 3:4): narrower width (214dp-226dp), taller height (255dp-308dp)
     * - Landscape (e.g. 16:9, 4:3): wider width (264dp-276dp), shorter height (150dp-198dp)
     * - Square (~1:1): balanced square (238dp x 238dp)
     */
    @NonNull
    public static int[] computeBubbleCardSizePx(@NonNull Context context, @Nullable int[] intrinsicDims) {
        float dp = context.getResources().getDisplayMetrics().density;
        if (intrinsicDims == null || intrinsicDims.length < 2 || intrinsicDims[0] <= 0 || intrinsicDims[1] <= 0) {
            // Default balanced 4:3 card when dimensions are unknown
            return new int[]{Math.round(250f * dp), Math.round(210f * dp)};
        }

        int srcW = intrinsicDims[0];
        int srcH = intrinsicDims[1];
        float ratio = (float) srcW / (float) srcH; // width / height

        float targetWdp;
        float targetHdp;

        if (ratio < 0.85f) {
            // PORTRAIT (tall photo / vertical reel video)
            if (ratio <= 0.62f) {
                // 9:16 or taller portrait
                targetWdp = 212f;
            } else {
                // 3:4 portrait
                targetWdp = 224f;
            }
            targetHdp = targetWdp / Math.max(0.45f, ratio);
            targetHdp = Math.max(248f, Math.min(308f, targetHdp));
        } else if (ratio > 1.18f) {
            // LANDSCAPE (widescreen 16:9 / 4:3 photo or video)
            if (ratio >= 1.55f) {
                // 16:9 or wider cinematic landscape
                targetWdp = 274f;
            } else {
                // 4:3 landscape
                targetWdp = 262f;
            }
            targetHdp = targetWdp / Math.min(2.25f, ratio);
            targetHdp = Math.max(146f, Math.min(198f, targetHdp));
        } else {
            // SQUARE (~1:1)
            targetWdp = 236f;
            targetHdp = Math.max(216f, Math.min(246f, targetWdp / ratio));
        }

        return new int[]{Math.round(targetWdp * dp), Math.round(targetHdp * dp)};
    }

    /**
     * Applies dynamic Portrait / Landscape / Square dimensions to a photo/video/GIF card and its caption.
     */
    public static void applyDynamicMediaCardSize(
            @NonNull Context context,
            @Nullable View cardView,
            @Nullable TextView captionView,
            @Nullable Message message,
            @Nullable File localFile) {
        if (cardView == null) return;
        int[] intrinsic = resolveMediaIntrinsicDimensions(context, message, localFile);
        int[] sizePx = computeBubbleCardSizePx(context, intrinsic);
        updateViewDimensions(cardView, sizePx[0], sizePx[1]);
        if (captionView != null) {
            ViewGroup.LayoutParams capLp = captionView.getLayoutParams();
            if (capLp != null && capLp.width != sizePx[0]) {
                capLp.width = sizePx[0];
                captionView.setLayoutParams(capLp);
            }
        }
    }

    public static void updateViewDimensions(@Nullable View view, int widthPx, int heightPx) {
        if (view == null) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && (lp.width != widthPx || lp.height != heightPx)) {
            lp.width = widthPx;
            lp.height = heightPx;
            view.setLayoutParams(lp);
        }
    }

    // =========================================================================
    // 2. STICKER & GIF MESSAGE CLASSIFICATION
    // =========================================================================

    public static boolean isStickerMessage(@Nullable Message message) {
        if (message == null) return false;
        if ("sticker".equalsIgnoreCase(message.getType())) return true;
        String text = message.getText();
        if (text != null) {
            String trimmed = text.trim();
            if (trimmed.startsWith("[STICKER:") && trimmed.endsWith("]")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isGifMessage(@Nullable Message message, @Nullable File localFile) {
        if (message == null) return false;
        if ("gif".equalsIgnoreCase(message.getType())) return true;
        String text = message.getText();
        if (text != null) {
            String trimmed = text.trim();
            if (trimmed.startsWith("[GIF:") && trimmed.endsWith("]")) {
                return true;
            }
        }
        if (message.getLocalFilePath() != null && message.getLocalFilePath().toLowerCase(Locale.ROOT).endsWith(".gif")) {
            return true;
        }
        return isGifFile(localFile);
    }

    public static boolean isGifFile(@Nullable File file) {
        if (file == null || !file.exists() || file.length() < 6) return false;
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".gif")) return true;
        try (InputStream is = new FileInputStream(file)) {
            byte[] header = new byte[6];
            if (is.read(header) == 6) {
                String sig = new String(header, "US-ASCII");
                return sig.startsWith("GIF87a") || sig.startsWith("GIF89a");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // =========================================================================
    // 3. NATIVE GIF FILE DECODER + ANIMATED DRAWABLE LOADER
    // =========================================================================

    /**
     * Loads a looping animated Drawable from a local .gif file (or animated .webp on API 28+).
     */
    @Nullable
    public static Drawable loadAnimatedGifFromFile(@Nullable File file) {
        if (file == null || !file.exists() || file.length() < 16) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(file);
                Drawable decoded = android.graphics.ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                if (decoded instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable anim = (AnimatedImageDrawable) decoded;
                    anim.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    anim.start();
                    return anim;
                }
            } catch (Throwable ignored) {}
        }
        try {
            Movie movie = Movie.decodeFile(file.getAbsolutePath());
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                MovieGifDrawable drawable = new MovieGifDrawable(movie);
                drawable.start();
                return drawable;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Binds either a built-in animated GIF preset (`[GIF:...]`) or a local `.gif` file into an ImageView.
     */
    public static boolean bindGifToImageView(
            @NonNull Context context,
            @NonNull Message message,
            @Nullable File localFile,
            @NonNull ImageView targetView,
            @Nullable View progressBar) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        String text = message.getText() != null ? message.getText().trim() : "";
        if (text.startsWith("[GIF:")) {
            GifPreset preset = findGifPresetByCode(text);
            if (preset != null) {
                AnimatedGifSceneDrawable scene = new AnimatedGifSceneDrawable(context, preset);
                targetView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                targetView.setImageDrawable(scene);
                scene.start();
                return true;
            }
        }

        File gifFile = localFile;
        if (gifFile == null || !gifFile.exists()) {
            gifFile = FileMediaManager.getDecryptedFile(context, message, ".gif");
        }
        if (gifFile != null && gifFile.exists()) {
            Drawable gifDrawable = loadAnimatedGifFromFile(gifFile);
            if (gifDrawable != null) {
                targetView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                targetView.setImageDrawable(gifDrawable);
                if (gifDrawable instanceof Animatable) {
                    ((Animatable) gifDrawable).start();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Binds a Telegram-style chromeless animated sticker into an ImageView.
     */
    public static void bindStickerToImageView(
            @NonNull Context context,
            @NonNull Message message,
            @NonNull ImageView targetView) {
        String raw = message.getText() != null ? message.getText().trim() : "✨";
        // Check if backed by a local .gif / .webp sticker file
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File f = new File(message.getLocalFilePath());
            if (f.exists() && isGifFile(f)) {
                Drawable d = loadAnimatedGifFromFile(f);
                if (d != null) {
                    targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    targetView.setImageDrawable(d);
                    return;
                }
            }
        }
        StickerPreset preset = resolveStickerPreset(raw);
        AnimatedStickerDrawable stickerDrawable = new AnimatedStickerDrawable(context, preset);
        targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        targetView.setImageDrawable(stickerDrawable);
        stickerDrawable.start();
    }

    // =========================================================================
    // 4. BUILT-IN ANIMATED STICKERS & GIF PRESETS CATALOG
    // =========================================================================

    public static class StickerPreset {
        public final String code;
        public final String emoji;
        public final String label;
        public final int primaryColor;
        public final int secondaryColor;
        public final int styleType; // 0=pulse+orbit, 1=bounce+flame, 2=wave+float, 3=glitch+neon, 4=confetti+party

        public StickerPreset(String code, String emoji, String label, int primaryColor, int secondaryColor, int styleType) {
            this.code = code;
            this.emoji = emoji;
            this.label = label;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.styleType = styleType;
        }
    }

    public static class GifPreset {
        public final String code;
        public final String title;
        public final String category;
        public final String centerEmoji;
        public final String captionOverlay;
        public final int bgStartColor;
        public final int bgEndColor;
        public final int accentColor;
        public final int intrinsicWidth;
        public final int intrinsicHeight;
        public final int animMode;

        public GifPreset(String code, String title, String category, String centerEmoji, String captionOverlay,
                         int bgStartColor, int bgEndColor, int accentColor, int intrinsicWidth, int intrinsicHeight, int animMode) {
            this.code = code;
            this.title = title;
            this.category = category;
            this.centerEmoji = centerEmoji;
            this.captionOverlay = captionOverlay;
            this.bgStartColor = bgStartColor;
            this.bgEndColor = bgEndColor;
            this.accentColor = accentColor;
            this.intrinsicWidth = intrinsicWidth;
            this.intrinsicHeight = intrinsicHeight;
            this.animMode = animMode;
        }
    }

    private static final List<StickerPreset> BUILTIN_ANIMATED_STICKERS = new ArrayList<>();
    private static final List<GifPreset> BUILTIN_GIFS = new ArrayList<>();

    static {
        // Animated Stickers (Telegram-style expressive characters & badges)
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:fire_lit]", "🔥", "LIT!", 0xFFF97316, 0xFFEF4444, 1));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:rocket_moon]", "🚀", "TO THE MOON", 0xFF6366F1, 0xFF38BDF8, 0));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:heart_love]", "❤️", "LOVE IT", 0xFFEC4899, 0xFFF43F5E, 1));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:laugh_lol]", "😂", "HAHAHA!", 0xFFFACC15, 0xFFF59E0B, 4));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:party_boom]", "🎉", "CELEBRATE", 0xFFA855F7, 0xFFEC4899, 4));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:cypher_bot]", "🤖", "BEEP BOOP", 0xFF10B981, 0xFF06B6D4, 3));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:cool_shades]", "😎", "DEAL WITH IT", 0xFF3B82F6, 0xFF1D4ED8, 2));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:mind_blown]", "🤯", "MIND BLOWN", 0xFFD946EF, 0xFF6366F1, 0));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:ghost_poof]", "👻", "POOF!", 0xFF818CF8, 0xFFC084FC, 2));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:hundred_gold]", "💯", "100% REAL", 0xFFF59E0B, 0xFFEF4444, 1));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:whale_splash]", "🐋", "WHALE MOVE", 0xFF0EA5E9, 0xFF2563EB, 2));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:thumbs_gg]", "👍", "AWESOME", 0xFF22C55E, 0xFF16A34A, 0));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:coffee_vibe]", "☕", "GOOD VIBES", 0xFFD97706, 0xFF92400E, 2));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:sparkle_magic]", "✨", "MAGIC", 0xFFFBBF24, 0xFFA855F7, 4));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:alien_ufo]", "🛸", "BEAM ME UP", 0xFF14B8A6, 0xFF6366F1, 3));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:crown_king]", "👑", "LEGEND", 0xFFEAB308, 0xFFCA8A04, 0));

        // Built-in Animated GIFs with Portrait, Landscape, and Square aspect ratios!
        BUILTIN_GIFS.add(new GifPreset("[GIF:mind_blown_16_9]", "Mind Blown Galaxy", "Reactions", "🤯", "MIND BLOWN",
                0xFF0F172A, 0xFF4C1D95, 0xFFE879F9, 480, 270, 0)); // 16:9 Landscape
        BUILTIN_GIFS.add(new GifPreset("[GIF:party_vibes_16_9]", "Party Confetti Vibe", "Celebrate", "🥳", "LET'S GOOO!",
                0xFF1E1B4B, 0xFF831843, 0xFFF472B6, 480, 270, 1)); // 16:9 Landscape
        BUILTIN_GIFS.add(new GifPreset("[GIF:rocket_launch_3_4]", "Rocket Launch", "Trending", "🚀", "TO THE MOON",
                0xFF090D16, 0xFF1E3A8A, 0xFF38BDF8, 360, 480, 2)); // 3:4 Portrait
        BUILTIN_GIFS.add(new GifPreset("[GIF:neon_matrix_16_9]", "Cyber Matrix Pulse", "Tech", "⚡", "SYSTEM ONLINE",
                0xFF022C22, 0xFF0F172A, 0xFF34D399, 480, 270, 3)); // 16:9 Landscape
        BUILTIN_GIFS.add(new GifPreset("[GIF:popcorn_drama_4_3]", "Grabbing Popcorn", "Reactions", "🍿", "THIS IS GOOD",
                0xFF450A0A, 0xFF1E293B, 0xFFFBBF24, 420, 315, 0)); // 4:3 Landscape
        BUILTIN_GIFS.add(new GifPreset("[GIF:heart_burst_1_1]", "Heart Beat Burst", "Love", "💖", "SENDING LOVE",
                0xFF500724, 0xFF881337, 0xFFFB7185, 380, 380, 1)); // 1:1 Square
        BUILTIN_GIFS.add(new GifPreset("[GIF:fire_hype_3_4]", "Maximum Hype Fire", "Trending", "🔥", "STRAIGHT FIRE",
                0xFF431407, 0xFF7C2D12, 0xFFFB923C, 360, 480, 2)); // 3:4 Portrait
        BUILTIN_GIFS.add(new GifPreset("[GIF:applause_clap_16_9]", "Standing Ovation", "Reactions", "👏", "BRAVO! 10/10",
                0xFF172554, 0xFF0F172A, 0xFFFACC15, 480, 270, 1)); // 16:9 Landscape
        BUILTIN_GIFS.add(new GifPreset("[GIF:deal_with_it_16_9]", "Deal With It", "Meme", "🕶️", "DEAL WITH IT",
                0xFF0F172A, 0xFF1E293B, 0xFF60A5FA, 480, 270, 3)); // 16:9 Landscape
        BUILTIN_GIFS.add(new GifPreset("[GIF:chill_wave_3_4]", "Sunset Synthwave", "Trending", "🌊", "CHILL VIBES",
                0xFF2E1065, 0xFF701A75, 0xFF2DD4BF, 360, 480, 2)); // 3:4 Portrait
        BUILTIN_GIFS.add(new GifPreset("[GIF:lol_tears_1_1]", "Rolling Laughing", "Meme", "🤣", "I CAN'T EVEN",
                0xFF422006, 0xFF713F12, 0xFFFDE047, 380, 380, 0)); // 1:1 Square
        BUILTIN_GIFS.add(new GifPreset("[GIF:victory_trophy_4_3]", "Champion Win", "Celebrate", "🏆", "FLAWLESS WIN",
                0xFF1E1B4B, 0xFF312E81, 0xFFFBBF24, 420, 315, 1)); // 4:3 Landscape
    }

    public static List<StickerPreset> getBuiltinAnimatedStickers() {
        return BUILTIN_ANIMATED_STICKERS;
    }

    public static List<GifPreset> getBuiltinGifs(@Nullable String queryOrCategory) {
        if (queryOrCategory == null || queryOrCategory.trim().isEmpty() || "All".equalsIgnoreCase(queryOrCategory.trim())) {
            return BUILTIN_GIFS;
        }
        String q = queryOrCategory.trim().toLowerCase(Locale.ROOT);
        List<GifPreset> filtered = new ArrayList<>();
        for (GifPreset g : BUILTIN_GIFS) {
            if (g.title.toLowerCase(Locale.ROOT).contains(q)
                    || g.category.toLowerCase(Locale.ROOT).contains(q)
                    || g.captionOverlay.toLowerCase(Locale.ROOT).contains(q)
                    || g.centerEmoji.contains(q)) {
                filtered.add(g);
            }
        }
        return filtered.isEmpty() ? BUILTIN_GIFS : filtered;
    }

    @Nullable
    public static GifPreset findGifPresetByCode(@Nullable String code) {
        if (code == null) return null;
        String clean = code.trim();
        for (GifPreset g : BUILTIN_GIFS) {
            if (g.code.equalsIgnoreCase(clean)) return g;
        }
        return null;
    }

    @NonNull
    public static StickerPreset resolveStickerPreset(@Nullable String rawCodeOrEmoji) {
        if (rawCodeOrEmoji == null || rawCodeOrEmoji.trim().isEmpty()) {
            return BUILTIN_ANIMATED_STICKERS.get(0);
        }
        String clean = rawCodeOrEmoji.trim();
        for (StickerPreset p : BUILTIN_ANIMATED_STICKERS) {
            if (p.code.equalsIgnoreCase(clean) || p.emoji.equals(clean)) {
                return p;
            }
        }
        // Dynamic animated sticker for any custom pack emoji or custom text sticker!
        String inner = clean;
        if (inner.startsWith("[STICKER:") && inner.endsWith("]")) {
            inner = inner.substring(9, inner.length() - 1).trim();
        }
        int hash = Math.abs(inner.hashCode());
        int[] colors1 = {0xFF6366F1, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF3B82F6, 0xFFA855F7};
        int[] colors2 = {0xFF38BDF8, 0xFFF43F5E, 0xFF06B6D4, 0xFFEF4444, 0xFF818CF8, 0xFFEC4899};
        int cIdx = hash % colors1.length;
        String label = inner.length() > 4 ? inner.toUpperCase(Locale.ROOT) : "STICKER";
        String dispEmoji = inner.length() <= 4 ? inner : "✨";
        return new StickerPreset("[STICKER:" + inner + "]", dispEmoji, label, colors1[cIdx], colors2[cIdx], hash % 5);
    }

    // =========================================================================
    // 5. TELEGRAM-STYLE ANIMATED STICKER DRAWABLE (60FPS LOOPING CANVAS)
    // =========================================================================

    public static class AnimatedStickerDrawable extends Drawable implements Animatable {
        private final StickerPreset preset;
        private final float dp;
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint emojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF badgeRect = new RectF();
        private boolean running = true;
        private final long startTimeOffset;

        public AnimatedStickerDrawable(@NonNull Context context, @NonNull StickerPreset preset) {
            this.preset = preset;
            this.dp = context.getResources().getDisplayMetrics().density;
            this.startTimeOffset = Math.abs(preset.code.hashCode() % 4000);

            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * dp);

            badgePaint.setStyle(Paint.Style.FILL);
            badgeStrokePaint.setStyle(Paint.Style.STROKE);
            badgeStrokePaint.setStrokeWidth(1.8f * dp);
            badgeStrokePaint.setColor(0xCCFFFFFF);

            emojiPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setColor(Color.WHITE);
            labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;

            float w = b.width();
            float h = b.height();
            float cx = b.left + w * 0.5f;
            float cy = b.top + h * 0.45f;
            float minDim = Math.min(w, h);

            long elapsed = (SystemClock.uptimeMillis() + startTimeOffset) % 2400L;
            float phase = (float) elapsed / 2400f; // 0..1 loop
            float angleRad = (float) (phase * Math.PI * 2.0);

            // 1. Soft radial aura glow behind the sticker (transparent surroundings, no bubble box)
            float pulse = 0.88f + 0.12f * (float) Math.sin(angleRad);
            float auraRadius = minDim * 0.36f * pulse;
            glowPaint.setColor(preset.primaryColor);
            glowPaint.setAlpha(46);
            canvas.drawCircle(cx, cy, auraRadius, glowPaint);

            glowPaint.setColor(preset.secondaryColor);
            glowPaint.setAlpha(34);
            canvas.drawCircle(cx, cy, auraRadius * 0.76f, glowPaint);

            // 2. Orbiting sparkle particles
            int particleCount = 6;
            for (int i = 0; i < particleCount; i++) {
                float pAngle = angleRad + (float) (i * Math.PI * 2.0 / particleCount);
                float orbitR = minDim * (0.33f + 0.05f * (float) Math.sin(angleRad * 2f + i));
                float px = cx + (float) Math.cos(pAngle) * orbitR;
                float py = cy + (float) Math.sin(pAngle) * orbitR * 0.85f;
                float pSize = (2.5f + 1.8f * (float) Math.cos(angleRad + i)) * dp;
                glowPaint.setColor(i % 2 == 0 ? preset.primaryColor : preset.secondaryColor);
                glowPaint.setAlpha(185);
                canvas.drawCircle(px, py, Math.max(1.5f, pSize), glowPaint);
            }

            // 3. Animated central character / emoji with spring bounce & tilt
            canvas.save();
            float bounceY = (float) Math.sin(angleRad) * (minDim * 0.045f);
            float scale = 0.94f + 0.11f * (float) Math.sin(angleRad * 2f);
            float tiltDeg = (float) Math.cos(angleRad) * 6.5f;
            canvas.translate(cx, cy + bounceY);
            canvas.rotate(tiltDeg);
            canvas.scale(scale, scale);

            emojiPaint.setTextSize(minDim * 0.44f);
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            float textCenterOffset = -(fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(preset.emoji, 0, textCenterOffset, emojiPaint);
            canvas.restore();

            // 4. Expressive sticker ribbon label at the bottom (Telegram sticker caption style)
            if (preset.label != null && !preset.label.isEmpty()) {
                labelPaint.setTextSize(Math.max(10f * dp, minDim * 0.088f));
                float textW = labelPaint.measureText(preset.label);
                float padH = 10f * dp;
                float badgeW = Math.min(w * 0.92f, textW + padH * 2f);
                float badgeH = Math.max(20f * dp, minDim * 0.155f);
                float badgeTop = b.top + h * 0.74f;
                badgeRect.set(cx - badgeW * 0.5f, badgeTop, cx + badgeW * 0.5f, badgeTop + badgeH);

                badgePaint.setColor(preset.primaryColor);
                badgePaint.setAlpha(235);
                canvas.drawRoundRect(badgeRect, badgeH * 0.5f, badgeH * 0.5f, badgePaint);
                canvas.drawRoundRect(badgeRect, badgeH * 0.5f, badgeH * 0.5f, badgeStrokePaint);

                Paint.FontMetrics lfm = labelPaint.getFontMetrics();
                float lBaseline = badgeRect.centerY() - (lfm.ascent + lfm.descent) * 0.5f;
                canvas.drawText(preset.label, cx, lBaseline, labelPaint);
            }

            if (running) {
                invalidateSelf();
            }
        }

        @Override
        public void start() {
            if (!running) {
                running = true;
                invalidateSelf();
            }
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // =========================================================================
    // 6. LOOPING ANIMATED GIF SCENE DRAWABLE (PORTRAIT / LANDSCAPE / SQUARE)
    // =========================================================================

    public static class AnimatedGifSceneDrawable extends Drawable implements Animatable {
        private final GifPreset preset;
        private final float dp;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint emojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path wavePath = new Path();
        private final RectF bannerRect = new RectF();
        private LinearGradient cachedGradient;
        private int lastW = -1, lastH = -1;
        private boolean running = true;
        private final long seedOffset;

        public AnimatedGifSceneDrawable(@NonNull Context context, @NonNull GifPreset preset) {
            this.preset = preset;
            this.dp = context.getResources().getDisplayMetrics().density;
            this.seedOffset = Math.abs(preset.code.hashCode() % 5000);

            emojiPaint.setTextAlign(Paint.Align.CENTER);
            captionPaint.setTextAlign(Paint.Align.CENTER);
            captionPaint.setColor(Color.WHITE);
            captionPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            captionPaint.setShadowLayer(4f * dp, 0f, 1.5f * dp, 0xCC000000);

            bannerPaint.setColor(0x88000000);
            bannerPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public int getIntrinsicWidth() {
            return preset.intrinsicWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return preset.intrinsicHeight;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;

            int w = b.width();
            int h = b.height();
            if (w != lastW || h != lastH || cachedGradient == null) {
                lastW = w;
                lastH = h;
                cachedGradient = new LinearGradient(
                        b.left, b.top, b.right, b.bottom,
                        preset.bgStartColor, preset.bgEndColor, Shader.TileMode.CLAMP);
                bgPaint.setShader(cachedGradient);
            }

            // 1. Rich gradient backdrop
            canvas.drawRect(b, bgPaint);

            long now = (SystemClock.uptimeMillis() + seedOffset) % 2600L;
            float t = (float) now / 2600f;
            float rad = (float) (t * Math.PI * 2.0);
            float cx = b.left + w * 0.5f;
            float cy = b.top + h * 0.44f;
            float minDim = Math.min(w, h);

            // 2. Animated synthwave / pulse rings / starfield
            particlePaint.setColor(preset.accentColor);
            for (int i = 0; i < 8; i++) {
                float pPhase = (t + i * 0.125f) % 1.0f;
                if (preset.animMode == 2) {
                    // Rising vertical streaks (Rocket / Fire portrait)
                    float x = b.left + w * (0.15f + 0.7f * ((i * 37 % 100) / 100f));
                    float y = b.bottom - pPhase * h;
                    particlePaint.setAlpha((int) (180 * (1f - Math.abs(pPhase - 0.5f) * 1.6f)));
                    canvas.drawCircle(x, y, (3f + (i % 3) * 1.5f) * dp, particlePaint);
                } else {
                    // Expanding radial rings & burst stars
                    float angle = (float) (i * Math.PI * 0.25 + rad * 0.5);
                    float dist = minDim * (0.12f + pPhase * 0.38f);
                    float px = cx + (float) Math.cos(angle) * dist;
                    float py = cy + (float) Math.sin(angle) * dist;
                    particlePaint.setAlpha((int) (200 * (1f - pPhase)));
                    canvas.drawCircle(px, py, (3.2f + (1f - pPhase) * 3f) * dp, particlePaint);
                }
            }

            // Subtle animated wave at bottom
            wavePath.reset();
            float waveBaseY = b.top + h * 0.76f;
            wavePath.moveTo(b.left, b.bottom);
            wavePath.lineTo(b.left, waveBaseY);
            for (int step = 0; step <= 8; step++) {
                float sx = b.left + (w * step / 8f);
                float sy = waveBaseY + (float) Math.sin(rad + step * 0.8f) * (6f * dp);
                wavePath.lineTo(sx, sy);
            }
            wavePath.lineTo(b.right, b.bottom);
            wavePath.close();
            particlePaint.setColor(preset.accentColor);
            particlePaint.setAlpha(42);
            canvas.drawPath(wavePath, particlePaint);

            // 3. Animated hero emoji
            canvas.save();
            float scale = 0.92f + 0.14f * (float) Math.sin(rad * 2f);
            float dy = (float) Math.cos(rad) * (5f * dp);
            canvas.translate(cx, cy + dy);
            canvas.scale(scale, scale);
            emojiPaint.setTextSize(minDim * 0.36f);
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            canvas.drawText(preset.centerEmoji, 0, -(fm.ascent + fm.descent) * 0.5f, emojiPaint);
            canvas.restore();

            // 4. Bold Impact / Meme GIF caption overlay
            if (preset.captionOverlay != null && !preset.captionOverlay.isEmpty()) {
                captionPaint.setTextSize(Math.max(12f * dp, minDim * 0.095f));
                float tw = captionPaint.measureText(preset.captionOverlay);
                float bh = Math.max(22f * dp, minDim * 0.15f);
                float bw = Math.min(w * 0.9f, tw + 20f * dp);
                float by = b.top + h * 0.72f;
                bannerRect.set(cx - bw * 0.5f, by, cx + bw * 0.5f, by + bh);
                canvas.drawRoundRect(bannerRect, 6f * dp, 6f * dp, bannerPaint);

                Paint.FontMetrics cfm = captionPaint.getFontMetrics();
                canvas.drawText(preset.captionOverlay, cx, bannerRect.centerY() - (cfm.ascent + cfm.descent) * 0.5f, captionPaint);
            }

            if (running) {
                invalidateSelf();
            }
        }

        @Override
        public void start() {
            if (!running) {
                running = true;
                invalidateSelf();
            }
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    // =========================================================================
    // 7. LEGACY / STREAM MOVIE GIF DRAWABLE FALLBACK
    // =========================================================================

    public static class MovieGifDrawable extends Drawable implements Animatable {
        private final Movie movie;
        private long movieStart = 0;
        private boolean running = true;

        public MovieGifDrawable(@NonNull Movie movie) {
            this.movie = movie;
        }

        @Override
        public int getIntrinsicWidth() {
            return movie.width();
        }

        @Override
        public int getIntrinsicHeight() {
            return movie.height();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || movie.width() <= 0 || movie.height() <= 0) return;

            long now = SystemClock.uptimeMillis();
            if (movieStart == 0) {
                movieStart = now;
            }
            int dur = movie.duration();
            if (dur <= 0) dur = 1000;
            int relTime = (int) ((now - movieStart) % dur);
            movie.setTime(relTime);

            canvas.save();
            float scaleX = (float) b.width() / (float) movie.width();
            float scaleY = (float) b.height() / (float) movie.height();
            float scale = Math.max(scaleX, scaleY);
            float dx = b.left + (b.width() - movie.width() * scale) * 0.5f;
            float dy = b.top + (b.height() - movie.height() * scale) * 0.5f;
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            movie.draw(canvas, 0, 0);
            canvas.restore();

            if (running) {
                invalidateSelf();
            }
        }

        @Override
        public void start() {
            running = true;
            invalidateSelf();
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
