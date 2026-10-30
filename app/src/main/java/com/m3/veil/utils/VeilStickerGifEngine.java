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
    private static final LruCache<String, Boolean> STICKER_DETECTION_CACHE = new LruCache<>(512);
    private static final LruCache<String, Bitmap> DIE_CUT_STICKER_CACHE = new LruCache<>(64);
    public static final int MAX_AUTO_PLAY_LOOPS = 2;
    private static final long MAX_FINITE_ANIM_DURATION_MS = 3200L;
    private static final long AUTO_PLAY_WINDOW_MS = 3800L;
    private static final android.os.Handler MAIN_ANIM_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FIRST_AUTO_PLAY_START_MS =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        String trimmed = text != null ? text.trim() : "";
        return (trimmed.startsWith("[STICKER:") && trimmed.endsWith("]"))
                || "[sticker]".equalsIgnoreCase(trimmed);
    }

    public static boolean isAnimatedEmojiMessage(@Nullable Message message) {
        if (message == null) return false;
        if ("animated_emoji".equalsIgnoreCase(message.getType())) return true;
        if (message.getType() == null || "text".equalsIgnoreCase(message.getType())) {
            String text = message.getText();
            if (text == null || text.trim().isEmpty()) return false;
            String trimmed = text.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) return false;
            List<String> clusters = extractEmojiClusters(trimmed, 4);
            if (!clusters.isEmpty() && clusters.size() <= 3) return true;
            try {
                com.m3.veil.ui.views.VeilAdvancedTextView.EmojiAnalysisResult result =
                        com.m3.veil.ui.views.VeilAdvancedTextView.analyzeEmojis(trimmed);
                return result != null && result.isEmojiOnly && result.emojiCount >= 1 && result.emojiCount <= 3;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Parses a pure-emoji string into individual grapheme clusters (handling ZWJ, skin tones,
     * variation selectors, and keycaps). Returns an empty list if any non-emoji text is present.
     */
    @NonNull
    public static List<String> extractEmojiClusters(@Nullable String text, int maxAllowed) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        String s = text.trim();
        if (s.isEmpty() || s.length() > 42) return result;

        int len = s.length();
        int i = 0;
        StringBuilder currentCluster = null;
        boolean expectJoinerTarget = false;

        while (i < len) {
            int cp = Character.codePointAt(s, i);
            int charCount = Character.charCount(cp);

            if (Character.isWhitespace(cp)) {
                i += charCount;
                continue;
            }

            if (cp == 0x200D) {
                // Zero-Width Joiner continues current emoji sequence
                if (currentCluster == null) return new ArrayList<>();
                currentCluster.appendCodePoint(cp);
                expectJoinerTarget = true;
                i += charCount;
                continue;
            }

            if (cp == 0xFE0E || cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF) || cp == 0x20E3) {
                if (currentCluster == null) return new ArrayList<>();
                currentCluster.appendCodePoint(cp);
                i += charCount;
                continue;
            }

            if (isEmojiCodePoint(cp) || (cp >= '0' && cp <= '9' && i + charCount < len && Character.codePointAt(s, i + charCount) == 0x20E3)) {
                if (currentCluster == null) {
                    currentCluster = new StringBuilder();
                } else if (!expectJoinerTarget) {
                    result.add(currentCluster.toString());
                    if (result.size() >= maxAllowed) {
                        return new ArrayList<>();
                    }
                    currentCluster = new StringBuilder();
                }
                currentCluster.appendCodePoint(cp);
                expectJoinerTarget = false;
                i += charCount;
            } else {
                // Non-emoji character encountered -> not a pure emoji message
                return new ArrayList<>();
            }
        }

        if (currentCluster != null && currentCluster.length() > 0) {
            result.add(currentCluster.toString());
        }
        if (result.size() > maxAllowed) {
            return new ArrayList<>();
        }
        return result;
    }

    public static boolean isSingleEmojiOnly(@Nullable String text) {
        List<String> clusters = extractEmojiClusters(text, 2);
        return clusters.size() == 1;
    }

    public static boolean isSingleOrDoubleEmoji(@Nullable String text) {
        List<String> clusters = extractEmojiClusters(text, 3);
        return !clusters.isEmpty() && clusters.size() <= 2;
    }

    public static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F600 && cp <= 0x1F64F) // Emoticons
                || (cp >= 0x1F300 && cp <= 0x1F5FF) // Misc Symbols and Pictographs
                || (cp >= 0x1F680 && cp <= 0x1F6FF) // Transport and Map
                || (cp >= 0x1F700 && cp <= 0x1F77F) // Alchemical
                || (cp >= 0x1F780 && cp <= 0x1F7FF) // Geometric Extended
                || (cp >= 0x1F800 && cp <= 0x1F8FF) // Supplemental Arrows
                || (cp >= 0x1F900 && cp <= 0x1F9FF) // Supplemental Symbols (🥹, 🫡, etc.)
                || (cp >= 0x1FA00 && cp <= 0x1FAFF) // Symbols Extended-A
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // Regional indicators (flags)
                || (cp >= 0x2600 && cp <= 0x27BF)   // Misc symbols & Dingbats (❤️, 🔥, etc.)
                || (cp >= 0x2300 && cp <= 0x23FF)   // Misc Technical
                || (cp >= 0x2B50 && cp <= 0x2B55)
                || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
                || cp == 0x2934 || cp == 0x2935 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
    }

    public static String extractAnimatedEmoji(@Nullable Message message) {
        if (message == null || message.getText() == null) return "❤️";
        return message.getText().trim();
    }

    public static int[] computeAnimatedEmojiSizePx(@NonNull Context context) {
        return computeAnimatedEmojiSizePx(context, null);
    }

    public static int[] computeAnimatedEmojiSizePx(@NonNull Context context, @Nullable Message message) {
        float dp = context.getResources().getDisplayMetrics().density;
        String emoji = extractAnimatedEmoji(message);
        List<String> clusters = extractEmojiClusters(emoji, 3);
        int count = clusters.size();
        if (count == 2) {
            return new int[]{Math.round(212 * dp), Math.round(138 * dp)};
        } else if (count >= 3) {
            return new int[]{Math.round(248 * dp), Math.round(120 * dp)};
        }
        int size = Math.round(168 * dp);
        return new int[]{size, size};
    }

    public static void bindAnimatedEmojiToImageView(@NonNull Context context, @Nullable Message message, @NonNull ImageView imageView) {
        String emoji = extractAnimatedEmoji(message);
        String bindKey = "veil_anim_emoji_" + (message != null && message.getId() != null ? message.getId() : "") + "_" + emoji;
        Object existingTag = imageView.getTag();
        if (bindKey.equals(existingTag) && imageView.getDrawable() instanceof com.m3.veil.ui.views.VeilAnimatedEmojiDrawable) {
            return;
        }
        boolean autoStart = shouldAutoPlayOnce(message);
        com.m3.veil.ui.views.VeilAnimatedEmojiDrawable drawable =
                new com.m3.veil.ui.views.VeilAnimatedEmojiDrawable(context, emoji);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageDrawable(drawable);
        imageView.setTag(bindKey);
        if (autoStart) {
            drawable.start();
        } else {
            drawable.stop();
        }
    }

    /**
     * Returns true ONLY if the bitmap is a genuine die-cut transparent sticker whose outer perimeter
     * AND inset ring (at 8% and 14% inward from edges) are predominantly transparent—NOT merely a
     * rounded-rectangle keyboard sticker card that has transparent 2px corners around a solid/bordered box.
     */
    public static boolean hasTransparentCorners(@Nullable Bitmap bmp) {
        if (bmp == null || bmp.getWidth() < 8 || bmp.getHeight() < 8) return false;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int transparentExtreme = 0;
        if (Color.alpha(bmp.getPixel(0, 0)) < 40) transparentExtreme++;
        if (Color.alpha(bmp.getPixel(w - 1, 0)) < 40) transparentExtreme++;
        if (Color.alpha(bmp.getPixel(0, h - 1)) < 40) transparentExtreme++;
        if (Color.alpha(bmp.getPixel(w - 1, h - 1)) < 40) transparentExtreme++;
        if (transparentExtreme < 2) return false;

        // Verify inset ring at 9% and 15% from edges so rounded-corner white/solid keyboard sticker cards
        // are NOT mistaken for already-transparent die-cut stickers!
        int insetX = Math.max(3, Math.round(w * 0.10f));
        int insetY = Math.max(3, Math.round(h * 0.10f));
        int insetSamples = 0;
        int insetTransparent = 0;
        int stepX = Math.max(2, (w - 2 * insetX) / 12);
        int stepY = Math.max(2, (h - 2 * insetY) / 12);
        for (int x = insetX; x < w - insetX; x += stepX) {
            insetSamples += 2;
            if (Color.alpha(bmp.getPixel(x, insetY)) < 45) insetTransparent++;
            if (Color.alpha(bmp.getPixel(x, h - 1 - insetY)) < 45) insetTransparent++;
        }
        for (int y = insetY; y < h - insetY; y += stepY) {
            insetSamples += 2;
            if (Color.alpha(bmp.getPixel(insetX, y)) < 45) insetTransparent++;
            if (Color.alpha(bmp.getPixel(w - 1 - insetX, y)) < 45) insetTransparent++;
        }
        return insetSamples > 0 && ((float) insetTransparent / (float) insetSamples) >= 0.42f;
    }

    public static boolean hasUniformSolidCorners(@Nullable Bitmap bmp) {
        if (bmp == null || bmp.getWidth() < 8 || bmp.getHeight() < 8) return false;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] offsets = new int[]{2, Math.max(3, Math.round(Math.min(w, h) * 0.06f)), Math.max(5, Math.round(Math.min(w, h) * 0.12f))};
        for (int off : offsets) {
            if (off >= w / 2 || off >= h / 2) continue;
            int[] corners = new int[]{
                    bmp.getPixel(off, off),
                    bmp.getPixel(w - 1 - off, off),
                    bmp.getPixel(off, h - 1 - off),
                    bmp.getPixel(w - 1 - off, h - 1 - off)
            };
            int ref = corners[0];
            if (Color.alpha(ref) < 180) continue;
            int r = Color.red(ref), g = Color.green(ref), b = Color.blue(ref);
            int matchCount = 0;
            for (int c : corners) {
                if (Color.alpha(c) >= 180 && colorDist(c, r, g, b) <= 32) matchCount++;
            }
            if (matchCount >= 3) return true;
        }
        return false;
    }

    /**
     * Checks whether the bitmap has a studio background box or bordered sticker card around its perimeter
     * (including Gboard / mobile keyboard stickers with rounded corners, outer border frames, or solid fills).
     */
    public static boolean hasUniformSolidBorderBox(@Nullable Bitmap bmp) {
        if (bmp == null || bmp.getWidth() < 16 || bmp.getHeight() < 16) return false;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] ringInsets = new int[]{
                2,
                Math.max(3, Math.round(Math.min(w, h) * 0.05f)),
                Math.max(5, Math.round(Math.min(w, h) * 0.10f)),
                Math.max(7, Math.round(Math.min(w, h) * 0.14f))
        };
        for (int inset : ringInsets) {
            if (inset >= w / 3 || inset >= h / 3) continue;
            int[] seedCandidates = new int[]{
                    bmp.getPixel(inset, inset),
                    bmp.getPixel(w / 2, inset),
                    bmp.getPixel(inset, h / 2),
                    bmp.getPixel(w - 1 - inset, inset)
            };
            for (int ref : seedCandidates) {
                if (Color.alpha(ref) < 180) continue;
                int r = Color.red(ref), g = Color.green(ref), b = Color.blue(ref);
                int samples = 0;
                int matches = 0;
                int stepX = Math.max(1, (w - 2 * inset) / 20);
                int stepY = Math.max(1, (h - 2 * inset) / 20);
                for (int x = inset; x < w - inset; x += stepX) {
                    samples += 2;
                    int pTop = bmp.getPixel(x, inset);
                    int pBot = bmp.getPixel(x, h - 1 - inset);
                    if (Color.alpha(pTop) < 50 || colorDist(pTop, r, g, b) <= 32) matches++;
                    if (Color.alpha(pBot) < 50 || colorDist(pBot, r, g, b) <= 32) matches++;
                }
                for (int y = inset; y < h - inset; y += stepY) {
                    samples += 2;
                    int pLeft = bmp.getPixel(inset, y);
                    int pRight = bmp.getPixel(w - 1 - inset, y);
                    if (Color.alpha(pLeft) < 50 || colorDist(pLeft, r, g, b) <= 32) matches++;
                    if (Color.alpha(pRight) < 50 || colorDist(pRight, r, g, b) <= 32) matches++;
                }
                if (samples > 0 && ((float) matches / samples) >= 0.62f) {
                    int centerDiffs = 0;
                    int[] cxOffsets = new int[]{w / 2, w / 3, (w * 2) / 3};
                    int[] cyOffsets = new int[]{h / 2, h / 3, (h * 2) / 3};
                    for (int cx : cxOffsets) {
                        for (int cy : cyOffsets) {
                            if (colorDist(bmp.getPixel(cx, cy), r, g, b) > 20) {
                                centerDiffs++;
                            }
                        }
                    }
                    if (centerDiffs >= 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Determines whether an incoming media file from keyboard / content commit is a sticker
     * (WebP, PNG, sticker URI, or square-ish asset with transparent/bordered background).
     */
    public static boolean isLikelyKeyboardStickerFile(@Nullable File file, @Nullable String mime, @Nullable String uriString) {
        String m = mime != null ? mime.toLowerCase(Locale.ROOT) : "";
        String u = uriString != null ? uriString.toLowerCase(Locale.ROOT) : "";
        if (m.contains("webp") || m.contains("png")
                || u.endsWith(".webp") || u.endsWith(".png")
                || u.contains("sticker") || u.contains("emoji") || u.contains("bitmoji") || u.contains("gboard")) {
            return true;
        }
        if (file == null || !file.exists()) return false;
        String ext = detectStickerFileExtension(file);
        if (".webp".equals(ext) || ".png".equals(ext)) return true;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp != null) {
                int bw = bmp.getWidth();
                int bh = bmp.getHeight();
                float ratio = bh > 0 ? (float) bw / (float) bh : 1f;
                boolean squareish = ratio >= 0.75f && ratio <= 1.33f;
                boolean hasStickerBg = hasTransparentCorners(bmp) || hasUniformSolidBorderBox(bmp);
                bmp.recycle();
                return squareish || hasStickerBg;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @NonNull
    public static String detectStickerFileExtension(@Nullable File file) {
        if (file != null && file.exists() && file.length() >= 12) {
            try (InputStream is = new FileInputStream(file)) {
                byte[] h = new byte[12];
                if (is.read(h) >= 12) {
                    if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
                        return ".gif";
                    }
                    if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                            && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
                        return ".webp";
                    }
                    if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
                        return ".png";
                    }
                }
            } catch (Throwable ignored) {}
        }
        return ".png";
    }

    public static boolean isAnimatedWebpFile(@Nullable File file) {
        if (file == null || !file.exists() || file.length() < 30) return false;
        try (InputStream is = new FileInputStream(file)) {
            byte[] h = new byte[32];
            int read = is.read(h);
            if (read >= 21
                    && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
                // VP8X extended WebP header stores animation bit (0x02) at byte offset 20
                if (h[12] == 'V' && h[13] == 'P' && h[14] == '8' && h[15] == 'X') {
                    return (h[20] & 0x02) != 0;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isAnimatedStickerFile(@Nullable File file) {
        return isGifFile(file) || isAnimatedWebpFile(file);
    }

    public static boolean isGifMessage(@Nullable Message message, @Nullable File localFile) {
        if (message == null) return false;
        if (isStickerMessage(message)) return false;
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

    public static boolean shouldAutoPlayOnce(@Nullable Message message) {
        if (message == null || message.getId() == null || message.getId().isEmpty()) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        Long firstStart = FIRST_AUTO_PLAY_START_MS.get(message.getId());
        if (firstStart == null) {
            FIRST_AUTO_PLAY_START_MS.put(message.getId(), now);
            return true;
        }
        return (now - firstStart) <= AUTO_PLAY_WINDOW_MS;
    }

    public static void replayAnimatedDrawable(@Nullable ImageView imageView) {
        if (imageView == null) return;
        Drawable d = imageView.getDrawable();
        if (d instanceof Animatable) {
            final Animatable anim = (Animatable) d;
            try {
                if (anim.isRunning()) {
                    anim.stop();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) d).setRepeatCount(MAX_AUTO_PLAY_LOOPS - 1);
                }
                anim.start();
                imageView.invalidate();
                MAIN_ANIM_HANDLER.postDelayed(() -> {
                    try {
                        if (anim.isRunning()) {
                            anim.stop();
                            imageView.invalidate();
                        }
                    } catch (Throwable ignored) {}
                }, MAX_FINITE_ANIM_DURATION_MS);
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // 3. NATIVE GIF / STICKER FILE DECODER + FINITE-LOOP ANIMATED DRAWABLE LOADER (WHATSAPP/TELEGRAM APPROACH)
    // =========================================================================

    /**
     * Loads a finite-loop (WhatsApp/Telegram style) animated Drawable from a local .gif or animated .webp file.
     * Never loops infinitely; stops cleanly after {@link #MAX_AUTO_PLAY_LOOPS} cycles (or {@link #MAX_FINITE_ANIM_DURATION_MS}).
     */
    @Nullable
    public static Drawable loadAnimatedGifFromFile(@Nullable File file) {
        return loadSingleCycleAnimatedDrawable(file, true, false, false);
    }

    @Nullable
    public static Drawable loadSingleCycleAnimatedDrawable(
            @Nullable File file,
            boolean autoStart,
            boolean stripSolidBg,
            boolean fitCenter) {
        if (file == null || !file.exists() || file.length() < 16) return null;

        if (stripSolidBg) {
            try {
                Movie movie = Movie.decodeFile(file.getAbsolutePath());
                if (movie != null && movie.width() > 0 && movie.height() > 0) {
                    MovieGifDrawable drawable = new MovieGifDrawable(movie, true, fitCenter);
                    if (autoStart) {
                        drawable.start();
                    }
                    return drawable;
                }
            } catch (Throwable ignored) {}

            // If Movie couldn't decode (e.g. animated WebP from keyboard with solid background/border),
            // wrap AnimatedImageDrawable in a real-time chroma-key border/background stripper!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(file);
                    Drawable decoded = android.graphics.ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                    if (decoded instanceof AnimatedImageDrawable) {
                        AnimatedImageDrawable anim = (AnimatedImageDrawable) decoded;
                        anim.setRepeatCount(MAX_AUTO_PLAY_LOOPS - 1);
                        ChromaKeyAnimatedDrawable wrapped = new ChromaKeyAnimatedDrawable(anim, fitCenter);
                        if (autoStart) {
                            wrapped.start();
                        } else {
                            wrapped.stop();
                        }
                        return wrapped;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(file);
                Drawable decoded = android.graphics.ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                if (decoded instanceof AnimatedImageDrawable) {
                    final AnimatedImageDrawable anim = (AnimatedImageDrawable) decoded;
                    anim.setRepeatCount(MAX_AUTO_PLAY_LOOPS - 1);
                    if (autoStart) {
                        anim.start();
                        MAIN_ANIM_HANDLER.postDelayed(() -> {
                            try {
                                if (anim.isRunning()) anim.stop();
                            } catch (Throwable ignored) {}
                        }, MAX_FINITE_ANIM_DURATION_MS);
                    } else {
                        anim.stop();
                    }
                    return anim;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Movie movie = Movie.decodeFile(file.getAbsolutePath());
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                MovieGifDrawable drawable = new MovieGifDrawable(movie, stripSolidBg, fitCenter);
                if (autoStart) {
                    drawable.start();
                }
                return drawable;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Real-time transparent wrapper for API 28+ {@link AnimatedImageDrawable} (such as Gboard animated WebP/GIF
     * stickers that have a solid background card or border frame), stripping the border/background per frame
     * and stopping cleanly after {@link #MAX_FINITE_ANIM_DURATION_MS}.
     */
    public static class ChromaKeyAnimatedDrawable extends Drawable implements Animatable {
        private final Drawable delegate;
        private final boolean fitCenter;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap frameBuffer;
        private Canvas frameCanvas;
        private Bitmap cachedRestingFrame;
        private boolean running = false;
        private long startMs = 0L;

        public ChromaKeyAnimatedDrawable(@NonNull Drawable delegate, boolean fitCenter) {
            this.delegate = delegate;
            this.fitCenter = fitCenter;
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(1, delegate.getIntrinsicWidth());
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(1, delegate.getIntrinsicHeight());
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            int dw = getIntrinsicWidth();
            int dh = getIntrinsicHeight();
            if (b.isEmpty() || dw <= 0 || dh <= 0) return;

            if (running) {
                long elapsed = SystemClock.uptimeMillis() - startMs;
                if (elapsed >= MAX_FINITE_ANIM_DURATION_MS) {
                    stop();
                }
            }

            float scaleX = (float) b.width() / (float) dw;
            float scaleY = (float) b.height() / (float) dh;
            float scale = fitCenter ? Math.min(scaleX, scaleY) : Math.max(scaleX, scaleY);
            float dx = b.left + (b.width() - dw * scale) * 0.5f;
            float dy = b.top + (b.height() - dh * scale) * 0.5f;

            if (!running && cachedRestingFrame != null && !cachedRestingFrame.isRecycled()) {
                canvas.save();
                canvas.translate(dx, dy);
                canvas.scale(scale, scale);
                canvas.drawBitmap(cachedRestingFrame, 0f, 0f, paint);
                canvas.restore();
                return;
            }

            if (frameBuffer == null || frameBuffer.getWidth() != dw || frameBuffer.getHeight() != dh) {
                frameBuffer = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
                frameCanvas = new Canvas(frameBuffer);
            }
            frameBuffer.eraseColor(Color.TRANSPARENT);
            delegate.setBounds(0, 0, dw, dh);
            delegate.draw(frameCanvas);
            Bitmap stripped = stripSolidBorderBackgroundIfPresent(frameBuffer);
            if (!running || cachedRestingFrame == null) {
                cachedRestingFrame = stripped;
            }

            canvas.save();
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            canvas.drawBitmap(stripped, 0f, 0f, paint);
            canvas.restore();

            if (running) {
                invalidateSelf();
            }
        }

        @Override
        public void start() {
            startMs = SystemClock.uptimeMillis();
            running = true;
            if (delegate instanceof Animatable) {
                try {
                    ((Animatable) delegate).start();
                } catch (Throwable ignored) {}
            }
            invalidateSelf();
        }

        @Override
        public void stop() {
            running = false;
            if (delegate instanceof Animatable) {
                try {
                    ((Animatable) delegate).stop();
                } catch (Throwable ignored) {}
            }
            invalidateSelf();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Binds either a built-in animated GIF preset (`[GIF:...]`) or a local `.gif` file into an ImageView.
     * Plays 2 loops on display (WhatsApp/Telegram approach) and replays 2 loops on tap.
     */
    public static boolean bindGifToImageView(
            @NonNull Context context,
            @NonNull Message message,
            @Nullable File localFile,
            @NonNull ImageView targetView,
            @Nullable View progressBar) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        String text = message.getText() != null ? message.getText().trim() : "";
        File gifFile = localFile;
        if (!text.startsWith("[GIF:") && (gifFile == null || !gifFile.exists())) {
            gifFile = FileMediaManager.getDecryptedFile(context, message, ".gif");
        }
        String bindKey = "veil_gif_" + (message.getId() != null ? message.getId() : "")
                + "_" + (gifFile != null ? gifFile.getAbsolutePath() : text);
        Object existingTag = targetView.getTag();
        if (bindKey.equals(existingTag) && targetView.getDrawable() instanceof Animatable) {
            // Preserve existing Drawable across RecyclerView re-binds so finite playback is never restarted
            return true;
        }

        boolean autoStart = shouldAutoPlayOnce(message);
        if (text.startsWith("[GIF:")) {
            GifPreset preset = findGifPresetByCode(text);
            if (preset != null) {
                AnimatedGifSceneDrawable scene = new AnimatedGifSceneDrawable(context, preset);
                targetView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                targetView.setImageDrawable(scene);
                targetView.setTag(bindKey);
                if (autoStart) {
                    scene.start();
                } else {
                    scene.stop();
                }
                return true;
            }
        }

        if (gifFile != null && gifFile.exists()) {
            Drawable gifDrawable = loadSingleCycleAnimatedDrawable(gifFile, autoStart, false, false);
            if (gifDrawable != null) {
                targetView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                targetView.setImageDrawable(gifDrawable);
                targetView.setTag(bindKey);
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static File resolveStickerFile(@NonNull Context context, @Nullable Message message) {
        if (message == null) return null;
        String[] exts = new String[]{".webp", ".png", ".gif", ".jpg"};
        for (String ext : exts) {
            File dec = FileMediaManager.getDecryptedFile(context, message, ext);
            if (dec != null && dec.exists() && dec.length() > 16) {
                return dec;
            }
        }
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File f = new File(message.getLocalFilePath());
            if (f.exists() && f.length() > 16) {
                return f;
            }
        }
        try {
            File inlineFile = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(
                    context, message.getChatId(), message);
            if (inlineFile != null && inlineFile.exists() && inlineFile.length() > 16) {
                return inlineFile;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Binds a WhatsApp/Telegram-style chromeless, borderless, transparent sticker into an ImageView.
     * Plays 2 cycles on display (WhatsApp/Telegram approach) and replays on tap.
     */
    public static void bindStickerToImageView(
            @NonNull Context context,
            @NonNull Message message,
            @NonNull ImageView targetView) {
        targetView.setBackgroundColor(Color.TRANSPARENT);
        targetView.setBackground(null);
        targetView.setPadding(0, 0, 0, 0);
        String raw = message.getText() != null ? message.getText().trim() : "✨";
        File candidateFile = resolveStickerFile(context, message);

        String bindKey = "veil_sticker_" + (message.getId() != null ? message.getId() : "")
                + "_" + (candidateFile != null ? candidateFile.getAbsolutePath() : raw);
        Object existingTag = targetView.getTag();
        if (bindKey.equals(existingTag) && targetView.getDrawable() instanceof Animatable) {
            Drawable active = targetView.getDrawable();
            if (active.getIntrinsicWidth() > 0 && active.getIntrinsicHeight() > 0) {
                syncStickerCardDimensions(context, targetView, message, active.getIntrinsicWidth(), active.getIntrinsicHeight());
            }
            // Preserve active animated sticker drawable across RecyclerView re-binds
            return;
        }

        boolean autoPlayOnce = shouldAutoPlayOnce(message);

        // 1. Check if backed by a local .webp / .png / .gif / .jpg sticker file
        if (candidateFile != null && candidateFile.exists()) {
            String pathKey = candidateFile.getAbsolutePath();
            if (isAnimatedStickerFile(candidateFile)) {
                Bitmap firstFrame = null;
                boolean needsSolidBorderStrip = false;
                int frameW = 0;
                int frameH = 0;
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    firstFrame = BitmapFactory.decodeFile(pathKey, opts);
                    if (firstFrame != null) {
                        frameW = firstFrame.getWidth();
                        frameH = firstFrame.getHeight();
                        if (!hasTransparentCorners(firstFrame)) {
                            needsSolidBorderStrip = true;
                        }
                    }
                } catch (Throwable ignored) {}

                Drawable d = loadSingleCycleAnimatedDrawable(candidateFile, autoPlayOnce, needsSolidBorderStrip, true);
                if (d != null) {
                    int dw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : frameW;
                    int dh = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : frameH;
                    if (dw > 0 && dh > 0) {
                        DIMENSION_CACHE.put(pathKey, new int[]{dw, dh});
                        syncStickerCardDimensions(context, targetView, message, dw, dh);
                    }
                    if (firstFrame != null) firstFrame.recycle();
                    targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    targetView.setImageDrawable(d);
                    targetView.setTag(bindKey);
                    return;
                }
                if (firstFrame != null) {
                    Bitmap dieCut = stripSolidBorderBackgroundIfPresent(firstFrame);
                    DIE_CUT_STICKER_CACHE.put(pathKey, dieCut);
                    DIMENSION_CACHE.put(pathKey, new int[]{dieCut.getWidth(), dieCut.getHeight()});
                    syncStickerCardDimensions(context, targetView, message, dieCut.getWidth(), dieCut.getHeight());
                    targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    targetView.setImageBitmap(dieCut);
                    targetView.setTag(bindKey);
                    return;
                }
            } else {
                Bitmap cached = DIE_CUT_STICKER_CACHE.get(pathKey);
                if (cached != null && !cached.isRecycled()) {
                    DIMENSION_CACHE.put(pathKey, new int[]{cached.getWidth(), cached.getHeight()});
                    syncStickerCardDimensions(context, targetView, message, cached.getWidth(), cached.getHeight());
                    targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    targetView.setImageBitmap(cached);
                    targetView.setTag(bindKey);
                    return;
                }
                Bitmap cachedDecoded = decodePhotoPreservingAspectRatio(candidateFile, 512);
                if (cachedDecoded != null) {
                    Bitmap dieCut = stripSolidBorderBackgroundIfPresent(cachedDecoded);
                    DIE_CUT_STICKER_CACHE.put(pathKey, dieCut);
                    DIMENSION_CACHE.put(pathKey, new int[]{dieCut.getWidth(), dieCut.getHeight()});
                    syncStickerCardDimensions(context, targetView, message, dieCut.getWidth(), dieCut.getHeight());
                    targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    targetView.setImageBitmap(dieCut);
                    targetView.setTag(bindKey);
                    return;
                }
            }
        }

        // 2. Check if message has a base64 thumbnail for custom/keyboard sticker
        if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()
                && ("[sticker]".equalsIgnoreCase(raw) || raw.isEmpty())) {
            String thumbKey = "thumb_" + (message.getId() != null ? message.getId() : raw.hashCode());
            Bitmap cachedThumb = DIE_CUT_STICKER_CACHE.get(thumbKey);
            if (cachedThumb != null && !cachedThumb.isRecycled()) {
                syncStickerCardDimensions(context, targetView, message, cachedThumb.getWidth(), cachedThumb.getHeight());
                targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                targetView.setImageBitmap(cachedThumb);
                targetView.setTag(bindKey);
                return;
            }
            try {
                byte[] bytes = android.util.Base64.decode(message.getThumbnail(), android.util.Base64.DEFAULT);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (bmp != null) {
                    Bitmap dieCut = stripSolidBorderBackgroundIfPresent(bmp);
                    DIE_CUT_STICKER_CACHE.put(thumbKey, dieCut);
                    syncStickerCardDimensions(context, targetView, message, dieCut.getWidth(), dieCut.getHeight());
                    targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    targetView.setImageBitmap(dieCut);
                    targetView.setTag(bindKey);
                    return;
                }
            } catch (Exception ignored) {}
        }

        if (raw.startsWith("[GIF:")) {
            GifPreset gifPreset = findGifPresetByCode(raw);
            if (gifPreset != null) {
                AnimatedGifSceneDrawable scene = new AnimatedGifSceneDrawable(context, gifPreset);
                targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                targetView.setImageDrawable(scene);
                targetView.setTag(bindKey);
                if (autoPlayOnce) {
                    scene.start();
                } else {
                    scene.stop();
                }
                return;
            }
        }

        StickerPreset preset = resolveStickerPreset(raw);
        AnimatedStickerDrawable stickerDrawable = new AnimatedStickerDrawable(context, preset);
        targetView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        targetView.setImageDrawable(stickerDrawable);
        targetView.setTag(bindKey);
        if (autoPlayOnce) {
            stickerDrawable.start();
        } else {
            stickerDrawable.stop();
        }
    }

    /**
     * Multi-stage die-cut sticker background & border remover for Gboard, Samsung Keyboard, and custom stickers:
     * - Preserves already-transparent die-cut stickers untouched.
     * - Detects and strips outer border frames (1px-10px stroke around sticker cards), rounded-corner
     *   white/dark/colored card backgrounds, and studio solid backgrounds via multi-seed flood-fill
     *   with smooth 2px contour alpha feathering.
     */
    @NonNull
    public static Bitmap stripSolidBorderBackgroundIfPresent(@NonNull Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 8 || h < 8) return src;
            // 1. Genuine transparent die-cut sticker -> return untouched (zero pixel alteration)
            if (hasTransparentCorners(src)) {
                return src;
            }

            Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
            int[] pixels = new int[w * h];
            out.getPixels(pixels, 0, w, 0, 0, w, h);

            // Sample multiple inset rings (2px, 5%, 10%, 14%) to capture both outer border stroke color (seed1)
            // and inner card background fill color (seed2)
            int insetEdge = 2;
            int insetMid = Math.max(4, Math.round(Math.min(w, h) * 0.06f));
            int insetInner = Math.max(7, Math.round(Math.min(w, h) * 0.11f));

            int cEdge = findDominantRingColor(pixels, w, h, insetEdge);
            int cMid = findDominantRingColor(pixels, w, h, insetMid);
            int cInner = findDominantRingColor(pixels, w, h, insetInner);

            int sr1 = Color.red(cEdge), sg1 = Color.green(cEdge), sb1 = Color.blue(cEdge);
            int sr2 = Color.red(cMid), sg2 = Color.green(cMid), sb2 = Color.blue(cMid);
            int sr3 = Color.red(cInner), sg3 = Color.green(cInner), sb3 = Color.blue(cInner);

            boolean[] visited = new boolean[w * h];
            int[] queue = new int[w * h];
            int head = 0, tail = 0;

            int tolerance = 30;
            int borderMarginX = Math.max(6, Math.round(w * 0.13f));
            int borderMarginY = Math.max(6, Math.round(h * 0.13f));

            // Seed from all 4 outer edges AND any transparent rounded-corner margin pixels
            for (int x = 0; x < w; x++) {
                int topIdx = x;
                int botIdx = (h - 1) * w + x;
                if (matchesMultiBgSeed(pixels[topIdx], sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, tolerance)) {
                    visited[topIdx] = true;
                    queue[tail++] = topIdx;
                }
                if (!visited[botIdx] && matchesMultiBgSeed(pixels[botIdx], sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, tolerance)) {
                    visited[botIdx] = true;
                    queue[tail++] = botIdx;
                }
            }
            for (int y = 0; y < h; y++) {
                int leftIdx = y * w;
                int rightIdx = y * w + (w - 1);
                if (!visited[leftIdx] && matchesMultiBgSeed(pixels[leftIdx], sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, tolerance)) {
                    visited[leftIdx] = true;
                    queue[tail++] = leftIdx;
                }
                if (!visited[rightIdx] && matchesMultiBgSeed(pixels[rightIdx], sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, tolerance)) {
                    visited[rightIdx] = true;
                    queue[tail++] = rightIdx;
                }
            }

            while (head < tail) {
                int idx = queue[head++];
                pixels[idx] = Color.TRANSPARENT;
                int x = idx % w;
                int y = idx / w;
                boolean inOuterBorderBand = (x < borderMarginX || x >= w - borderMarginX || y < borderMarginY || y >= h - borderMarginY);
                int activeTol = inOuterBorderBand ? (tolerance + 12) : tolerance;

                if (x > 0) {
                    int n = idx - 1;
                    if (!visited[n] && matchesBgOrBorder(pixels[n], inOuterBorderBand, sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, activeTol)) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
                if (x + 1 < w) {
                    int n = idx + 1;
                    if (!visited[n] && matchesBgOrBorder(pixels[n], inOuterBorderBand, sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, activeTol)) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
                if (y > 0) {
                    int n = idx - w;
                    if (!visited[n] && matchesBgOrBorder(pixels[n], inOuterBorderBand, sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, activeTol)) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
                if (y + 1 < h) {
                    int n = idx + w;
                    if (!visited[n] && matchesBgOrBorder(pixels[n], inOuterBorderBand, sr1, sg1, sb1, sr2, sg2, sb2, sr3, sg3, sb3, activeTol)) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
            }

            // Safety guard: if flood-fill removed > 82% of total pixels (or < 3% on a full-bleed photo),
            // apply clean die-cut sticker silhouette or return the stripped cutout
            int totalPx = w * h;
            if (tail > (totalPx * 82) / 100 || tail < (totalPx * 3) / 100) {
                out.recycle();
                return applyCrispRoundedStickerCorners(src);
            }

            // Crisp 2px contour alpha feathering so no thin border halo remains around the sticker subject
            for (int y = 1; y < h - 1; y++) {
                int rowOffset = y * w;
                for (int x = 1; x < w - 1; x++) {
                    int idx = rowOffset + x;
                    if (!visited[idx]) {
                        int transNeighbors = 0;
                        if (visited[idx - 1]) transNeighbors++;
                        if (visited[idx + 1]) transNeighbors++;
                        if (visited[idx - w]) transNeighbors++;
                        if (visited[idx + w]) transNeighbors++;
                        if (transNeighbors > 0) {
                            int px = pixels[idx];
                            int d1 = colorDist(px, sr1, sg1, sb1);
                            int d2 = colorDist(px, sr2, sg2, sb2);
                            int d3 = colorDist(px, sr3, sg3, sb3);
                            int minDist = Math.min(d1, Math.min(d2, d3));
                            if (minDist < tolerance + 16) {
                                int origA = Color.alpha(px);
                                int softA = Math.min(origA, Math.max(0, (minDist - tolerance / 2) * 255 / 22));
                                pixels[idx] = Color.argb(softA, Color.red(px), Color.green(px), Color.blue(px));
                            } else if (transNeighbors >= 3) {
                                int origA = Color.alpha(px);
                                pixels[idx] = Color.argb(origA / 2, Color.red(px), Color.green(px), Color.blue(px));
                            }
                        }
                    }
                }
            }

            out.setPixels(pixels, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception e) {
            return src;
        }
    }

    private static int findDominantRingColor(int[] pixels, int w, int h, int inset) {
        int safeInsetX = Math.min(Math.max(1, inset), w / 3);
        int safeInsetY = Math.min(Math.max(1, inset), h / 3);
        int[] candidates = new int[]{
                pixels[safeInsetY * w + safeInsetX],
                pixels[safeInsetY * w + (w / 2)],
                pixels[safeInsetY * w + (w - 1 - safeInsetX)],
                pixels[(h / 2) * w + safeInsetX],
                pixels[(h / 2) * w + (w - 1 - safeInsetX)],
                pixels[(h - 1 - safeInsetY) * w + safeInsetX],
                pixels[(h - 1 - safeInsetY) * w + (w / 2)],
                pixels[(h - 1 - safeInsetY) * w + (w - 1 - safeInsetX)]
        };
        int bestColor = Color.WHITE;
        int bestScore = -1;
        for (int c : candidates) {
            if (Color.alpha(c) < 120) continue;
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int score = 0;
            for (int other : candidates) {
                if (Color.alpha(other) >= 120 && colorDist(other, r, g, b) <= 28) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestColor = c;
            }
        }
        return bestColor;
    }

    private static boolean matchesMultiBgSeed(int pixel,
                                              int r1, int g1, int b1,
                                              int r2, int g2, int b2,
                                              int r3, int g3, int b3,
                                              int tol) {
        if (Color.alpha(pixel) < 65) return true;
        return colorDist(pixel, r1, g1, b1) <= tol
                || colorDist(pixel, r2, g2, b2) <= tol
                || colorDist(pixel, r3, g3, b3) <= tol;
    }

    private static boolean matchesBgOrBorder(int pixel,
                                             boolean inOuterBorderBand,
                                             int r1, int g1, int b1,
                                             int r2, int g2, int b2,
                                             int r3, int g3, int b3,
                                             int tol) {
        int a = Color.alpha(pixel);
        if (a < 65) return true;
        if (matchesMultiBgSeed(pixel, r1, g1, b1, r2, g2, b2, r3, g3, b3, tol)) {
            return true;
        }
        // If inside the outer 13% border band of a keyboard sticker card, also strip neutral white/gray/black
        // card borders & drop-shadow rings so the sticker has zero rectangular frame!
        if (inOuterBorderBand) {
            int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
            int maxDiff = Math.max(Math.abs(r - g), Math.max(Math.abs(g - b), Math.abs(r - b)));
            if (maxDiff <= 18 && (r >= 225 || r <= 42)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public static Bitmap applyCrispRoundedStickerCorners(@NonNull Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 8 || h < 8) return src;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float radius = Math.min(w, h) * 0.085f;
            Path clipPath = new Path();
            clipPath.addRoundRect(new RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            canvas.drawBitmap(src, 0f, 0f, paint);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    private static boolean matchesSingleBgSeed(int pixel, int r, int g, int b, int tol) {
        if (Color.alpha(pixel) < 35) return true;
        return colorDist(pixel, r, g, b) <= tol;
    }

    private static int colorDist(int pixel, int r, int g, int b) {
        int dr = Math.abs(Color.red(pixel) - r);
        int dg = Math.abs(Color.green(pixel) - g);
        int db = Math.abs(Color.blue(pixel) - b);
        return Math.max(dr, Math.max(dg, db));
    }

    /**
     * Computes WhatsApp/Telegram-standard 1:1 Square sticker display [widthPx, heightPx] (174dp x 174dp).
     * Stickers always render in a 1:1 square box in chat regardless of source media orientation.
     */
    @NonNull
    public static int[] computeStickerSizePx(@NonNull Context context, @Nullable Message message) {
        float dp = context.getResources().getDisplayMetrics().density;
        int sq = Math.round(174f * dp);
        return new int[]{sq, sq};
    }

    @NonNull
    public static int[] computeStickerSizeFromIntrinsic(float dp, int intrinsicW, int intrinsicH) {
        int sq = Math.round(174f * dp);
        return new int[]{sq, sq};
    }

    /**
     * Dynamically synchronizes the sticker card container's LayoutParams to match the loaded
     * sticker's true Portrait, Landscape, or Square dimensions immediately on bind.
     */
    private static void syncStickerCardDimensions(
            @NonNull Context context,
            @NonNull ImageView targetView,
            @Nullable Message message,
            int intrinsicW,
            int intrinsicH) {
        if (intrinsicW <= 0 || intrinsicH <= 0) return;
        if (message != null && message.getId() != null) {
            DIMENSION_CACHE.put("msg_" + message.getId(), new int[]{intrinsicW, intrinsicH});
        }
        float dp = context.getResources().getDisplayMetrics().density;
        int[] dims = computeStickerSizeFromIntrinsic(dp, intrinsicW, intrinsicH);
        android.view.ViewParent p1 = targetView.getParent();
        if (p1 instanceof View) {
            android.view.ViewParent p2 = p1.getParent();
            if (p2 instanceof View && ((View) p2).getId() == com.m3.veil.R.id.imageCardView) {
                updateViewDimensions((View) p2, dims[0], dims[1]);
                return;
            }
            if (((View) p1).getId() == com.m3.veil.R.id.imageCardView) {
                updateViewDimensions((View) p1, dims[0], dims[1]);
            }
        }
    }

    /**
     * Computes Telegram/WhatsApp-style per-GIF display [widthPx, heightPx] so every GIF has its own
     * natural width and height according to its aspect ratio.
     */
    @NonNull
    public static int[] computeGifSizePx(@NonNull Context context, @Nullable Message message, @Nullable File localFile) {
        float dp = context.getResources().getDisplayMetrics().density;
        int[] intrinsic = resolveMediaIntrinsicDimensions(context, message, localFile);
        if (intrinsic != null && intrinsic.length >= 2 && intrinsic[0] > 0 && intrinsic[1] > 0) {
            float ratio = (float) intrinsic[0] / (float) intrinsic[1];
            float wDp;
            float hDp;
            if (ratio >= 1.55f) {
                // 16:9 or ultrawide GIF
                wDp = 258f;
                hDp = Math.max(134f, Math.min(168f, wDp / ratio));
            } else if (ratio >= 1.15f) {
                // 4:3 landscape GIF
                wDp = 236f;
                hDp = Math.max(156f, Math.min(192f, wDp / ratio));
            } else if (ratio <= 0.82f) {
                // 3:4 or 9:16 portrait GIF
                wDp = ratio <= 0.65f ? 176f : 192f;
                hDp = Math.max(220f, Math.min(268f, wDp / Math.max(0.55f, ratio)));
            } else {
                // Square-ish GIF
                wDp = 204f;
                hDp = Math.max(186f, Math.min(216f, wDp / ratio));
            }
            return new int[]{Math.round(wDp * dp), Math.round(hDp * dp)};
        }
        // Fallback varied dimensions based on GIF payload/id so different GIFs have distinct sizes
        String seed = (message != null && message.getText() != null && !message.getText().isEmpty())
                ? message.getText()
                : (message != null && message.getId() != null ? message.getId() : "gif");
        int variant = Math.abs(seed.hashCode()) % 5;
        switch (variant) {
            case 0:
                return new int[]{Math.round(256f * dp), Math.round(144f * dp)}; // 16:9 wide
            case 1:
                return new int[]{Math.round(188f * dp), Math.round(244f * dp)}; // 3:4 portrait
            case 2:
                return new int[]{Math.round(234f * dp), Math.round(176f * dp)}; // 4:3 classic
            case 3:
                return new int[]{Math.round(202f * dp), Math.round(202f * dp)}; // 1:1 square
            default:
                return new int[]{Math.round(264f * dp), Math.round(152f * dp)}; // cinematic
        }
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
        public final int intrinsicWidth; // in dp
        public final int intrinsicHeight; // in dp

        public StickerPreset(String code, String emoji, String label, int primaryColor, int secondaryColor, int styleType) {
            this(code, emoji, label, primaryColor, secondaryColor, styleType, 172, 172);
        }

        public StickerPreset(String code, String emoji, String label, int primaryColor, int secondaryColor, int styleType,
                             int intrinsicWidth, int intrinsicHeight) {
            this.code = code;
            this.emoji = emoji;
            this.label = label;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.styleType = styleType;
            this.intrinsicWidth = intrinsicWidth;
            this.intrinsicHeight = intrinsicHeight;
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
        // Animated Stickers with varied Telegram/WhatsApp-style natural dimensions (dp) per sticker!
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:fire_lit]", "🔥", "LIT!", 0xFFF97316, 0xFFEF4444, 1, 162, 198));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:rocket_moon]", "🚀", "TO THE MOON", 0xFF6366F1, 0xFF38BDF8, 0, 154, 210));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:heart_love]", "❤️", "LOVE IT", 0xFFEC4899, 0xFFF43F5E, 1, 178, 166));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:laugh_lol]", "😂", "HAHAHA!", 0xFFFACC15, 0xFFF59E0B, 4, 196, 158));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:party_boom]", "🎉", "CELEBRATE", 0xFFA855F7, 0xFFEC4899, 4, 208, 164));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:cypher_bot]", "🤖", "BEEP BOOP", 0xFF10B981, 0xFF06B6D4, 3, 168, 188));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:cool_shades]", "😎", "DEAL WITH IT", 0xFF3B82F6, 0xFF1D4ED8, 2, 214, 148));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:mind_blown]", "🤯", "MIND BLOWN", 0xFFD946EF, 0xFF6366F1, 0, 184, 196));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:ghost_poof]", "👻", "POOF!", 0xFF818CF8, 0xFFC084FC, 2, 150, 192));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:hundred_gold]", "💯", "100% REAL", 0xFFF59E0B, 0xFFEF4444, 1, 192, 146));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:whale_splash]", "🐋", "WHALE MOVE", 0xFF0EA5E9, 0xFF2563EB, 2, 218, 152));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:thumbs_gg]", "👍", "AWESOME", 0xFF22C55E, 0xFF16A34A, 0, 164, 174));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:coffee_vibe]", "☕", "GOOD VIBES", 0xFFD97706, 0xFF92400E, 2, 172, 184));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:sparkle_magic]", "✨", "MAGIC", 0xFFFBBF24, 0xFFA855F7, 4, 158, 158));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:alien_ufo]", "🛸", "BEAM ME UP", 0xFF14B8A6, 0xFF6366F1, 3, 206, 156));
        BUILTIN_ANIMATED_STICKERS.add(new StickerPreset("[STICKER:crown_king]", "👑", "LEGEND", 0xFFEAB308, 0xFFCA8A04, 0, 186, 162));

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
        // Dynamic animated sticker for any custom pack emoji or custom text sticker with varied natural sizes!
        String inner = clean;
        if (inner.startsWith("[STICKER:") && inner.endsWith("]")) {
            inner = inner.substring(9, inner.length() - 1).trim();
        }
        int hash = Math.abs(inner.hashCode());
        int[] colors1 = {0xFF6366F1, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF3B82F6, 0xFFA855F7};
        int[] colors2 = {0xFF38BDF8, 0xFFF43F5E, 0xFF06B6D4, 0xFFEF4444, 0xFF818CF8, 0xFFEC4899};
        int[][] sizeVariants = {
                {198, 152}, // Wide sticker
                {154, 198}, // Tall sticker
                {174, 174}, // Balanced square sticker
                {212, 146}, // Banner sticker
                {164, 190}, // Portrait character sticker
                {188, 162}  // Compact landscape sticker
        };
        int cIdx = hash % colors1.length;
        int[] sz = sizeVariants[hash % sizeVariants.length];
        String label = inner.length() > 4 ? inner.toUpperCase(Locale.ROOT) : "STICKER";
        String dispEmoji = inner.length() <= 4 ? inner : "✨";
        return new StickerPreset("[STICKER:" + inner + "]", dispEmoji, label, colors1[cIdx], colors2[cIdx], hash % 5, sz[0], sz[1]);
    }

    @Nullable
    public static File materializePresetGifToFile(@NonNull Context context, @NonNull String gifCode) {
        try {
            GifPreset preset = findGifPresetByCode(gifCode);
            int w = preset != null ? Math.max(420, preset.intrinsicWidth) : 512;
            int h = preset != null ? Math.max(420, preset.intrinsicHeight) : 512;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            if (preset != null) {
                AnimatedGifSceneDrawable d = new AnimatedGifSceneDrawable(context, preset);
                d.setBounds(0, 0, w, h);
                d.draw(canvas);
            } else {
                StickerPreset sp = resolveStickerPreset(gifCode);
                AnimatedStickerDrawable sd = new AnimatedStickerDrawable(context, sp);
                sd.setBounds(0, 0, w, h);
                sd.draw(canvas);
            }
            File cacheDir = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
            File out = new File(cacheDir, "preset_gif_" + System.currentTimeMillis() + ".png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            bmp.recycle();
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // =========================================================================
    // 5. TELEGRAM/WHATSAPP-STYLE ANIMATED STICKER DRAWABLE (PLAYS 3 CYCLES THEN STOPS)
    // =========================================================================

    public static class AnimatedStickerDrawable extends Drawable implements Animatable {
        private static final long SINGLE_CYCLE_MS = 1800L;
        private static final long TOTAL_PLAY_MS = SINGLE_CYCLE_MS * MAX_AUTO_PLAY_LOOPS;
        private final StickerPreset preset;
        private final float dp;
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint emojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF badgeRect = new RectF();
        private boolean running = false;
        private long cycleStartMs = 0L;

        public AnimatedStickerDrawable(@NonNull Context context, @NonNull StickerPreset preset) {
            this.preset = preset;
            this.dp = context.getResources().getDisplayMetrics().density;

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
        public int getIntrinsicWidth() {
            return Math.round(preset.intrinsicWidth * dp);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(preset.intrinsicHeight * dp);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;

            float w = b.width();
            float h = b.height();
            float cx = b.left + w * 0.5f;
            float cy = b.top + h * 0.44f;
            float minDim = Math.min(w, h);

            float phase = 0f;
            if (running) {
                long elapsed = SystemClock.uptimeMillis() - cycleStartMs;
                if (elapsed >= TOTAL_PLAY_MS) {
                    running = false;
                    phase = 0f;
                } else {
                    phase = (float) (elapsed % SINGLE_CYCLE_MS) / (float) SINGLE_CYCLE_MS;
                }
            }
            float angleRad = (float) (phase * Math.PI * 2.0);

            // 1. WhatsApp Die-Cut Sticker Style: ZERO background circle, ZERO border box!
            emojiPaint.setTextSize(minDim * 0.14f);
            float noteBounce = running ? (float) Math.sin(angleRad * 2f) * (3.5f * dp) : 0f;
            labelPaint.setStyle(Paint.Style.FILL);
            labelPaint.setColor(0xFF1F1F1F);
            labelPaint.setTextSize(minDim * 0.15f);
            canvas.drawText("♪", cx - minDim * 0.30f, cy - minDim * 0.08f + noteBounce, labelPaint);
            canvas.drawText("♫", cx + minDim * 0.30f, cy - minDim * 0.12f - noteBounce, labelPaint);

            // 2. Central sticker character (animates 3 cycles then settles cleanly at upright resting pose)
            canvas.save();
            float bounceY = running ? (float) Math.sin(angleRad) * (minDim * 0.035f) : 0f;
            float scale = running ? (0.96f + 0.07f * (float) Math.sin(angleRad * 2f)) : 1.0f;
            float tiltDeg = running ? ((float) Math.sin(angleRad) * 4.5f) : 0f;
            canvas.translate(cx, cy + bounceY);
            canvas.rotate(tiltDeg);
            canvas.scale(scale, scale);

            emojiPaint.setTextSize(minDim * 0.52f);
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            float textCenterOffset = -(fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(preset.emoji, 0, textCenterOffset, emojiPaint);
            canvas.restore();

            // 3. Borderless hand-lettered WhatsApp sticker typography (white die-cut halo + dark ink text)
            if (preset.label != null && !preset.label.isEmpty()) {
                float textSize = Math.max(12f * dp, minDim * 0.115f);
                labelPaint.setTextSize(textSize);
                Paint.FontMetrics lfm = labelPaint.getFontMetrics();
                float lBaseline = b.top + h * 0.85f - (lfm.ascent + lfm.descent) * 0.5f;

                labelPaint.setStyle(Paint.Style.STROKE);
                labelPaint.setStrokeJoin(Paint.Join.ROUND);
                labelPaint.setStrokeCap(Paint.Cap.ROUND);
                labelPaint.setStrokeWidth(3.5f * dp);
                labelPaint.setColor(0xF2FFFFFF);
                canvas.drawText(preset.label, cx, lBaseline, labelPaint);

                labelPaint.setStyle(Paint.Style.FILL);
                labelPaint.setColor(0xFF1F1D1B);
                canvas.drawText(preset.label, cx, lBaseline, labelPaint);
            }

            if (running) {
                invalidateSelf();
            }
        }

        @Override
        public void start() {
            cycleStartMs = SystemClock.uptimeMillis();
            running = true;
            invalidateSelf();
        }

        @Override
        public void stop() {
            running = false;
            invalidateSelf();
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
    // 6. 3-CYCLE ANIMATED GIF SCENE DRAWABLE (PORTRAIT / LANDSCAPE / SQUARE)
    // =========================================================================

    public static class AnimatedGifSceneDrawable extends Drawable implements Animatable {
        private static final long SINGLE_CYCLE_MS = 2000L;
        private static final long TOTAL_PLAY_MS = SINGLE_CYCLE_MS * MAX_AUTO_PLAY_LOOPS;
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
        private boolean running = false;
        private long cycleStartMs = 0L;

        public AnimatedGifSceneDrawable(@NonNull Context context, @NonNull GifPreset preset) {
            this.preset = preset;
            this.dp = context.getResources().getDisplayMetrics().density;

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

            canvas.drawRect(b, bgPaint);

            float t = 0.25f;
            if (running) {
                long elapsed = SystemClock.uptimeMillis() - cycleStartMs;
                if (elapsed >= TOTAL_PLAY_MS) {
                    running = false;
                    t = 0.25f;
                } else {
                    t = (float) (elapsed % SINGLE_CYCLE_MS) / (float) SINGLE_CYCLE_MS;
                }
            }
            float rad = (float) (t * Math.PI * 2.0);
            float cx = b.left + w * 0.5f;
            float cy = b.top + h * 0.44f;
            float minDim = Math.min(w, h);

            particlePaint.setColor(preset.accentColor);
            for (int i = 0; i < 8; i++) {
                float pPhase = (t + i * 0.125f) % 1.0f;
                if (preset.animMode == 2) {
                    float x = b.left + w * (0.15f + 0.7f * ((i * 37 % 100) / 100f));
                    float y = b.bottom - pPhase * h;
                    particlePaint.setAlpha((int) (180 * (1f - Math.abs(pPhase - 0.5f) * 1.6f)));
                    canvas.drawCircle(x, y, (3f + (i % 3) * 1.5f) * dp, particlePaint);
                } else {
                    float angle = (float) (i * Math.PI * 0.25 + rad * 0.5);
                    float dist = minDim * (0.12f + pPhase * 0.38f);
                    float px = cx + (float) Math.cos(angle) * dist;
                    float py = cy + (float) Math.sin(angle) * dist;
                    particlePaint.setAlpha((int) (200 * (1f - pPhase)));
                    canvas.drawCircle(px, py, (3.2f + (1f - pPhase) * 3f) * dp, particlePaint);
                }
            }

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

            canvas.save();
            float scale = running ? (0.92f + 0.14f * (float) Math.sin(rad * 2f)) : 1.0f;
            float dy = running ? ((float) Math.cos(rad) * (5f * dp)) : 0f;
            canvas.translate(cx, cy + dy);
            canvas.scale(scale, scale);
            emojiPaint.setTextSize(minDim * 0.36f);
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            canvas.drawText(preset.centerEmoji, 0, -(fm.ascent + fm.descent) * 0.5f, emojiPaint);
            canvas.restore();

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
            cycleStartMs = SystemClock.uptimeMillis();
            running = true;
            invalidateSelf();
        }

        @Override
        public void stop() {
            running = false;
            invalidateSelf();
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
    // 7. 3-CYCLE TRANSPARENT / CHROMA-KEY MOVIE GIF DRAWABLE (WHATSAPP/TELEGRAM APPROACH)
    // =========================================================================

    public static class MovieGifDrawable extends Drawable implements Animatable {
        private final Movie movie;
        private final boolean stripSolidBg;
        private final boolean fitCenter;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap offscreenBuffer;
        private Canvas offscreenCanvas;
        private Bitmap cachedRestingTransparentFrame;
        private long movieStart = 0;
        private boolean running = false;

        public MovieGifDrawable(@NonNull Movie movie) {
            this(movie, false, false);
        }

        public MovieGifDrawable(@NonNull Movie movie, boolean stripSolidBg, boolean fitCenter) {
            this.movie = movie;
            this.stripSolidBg = stripSolidBg;
            this.fitCenter = fitCenter;
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
            int mw = movie.width();
            int mh = movie.height();
            if (b.isEmpty() || mw <= 0 || mh <= 0) return;

            int rawDur = movie.duration();
            int singleLoopDur = rawDur > 200 ? rawDur : 1500;
            long totalPlayMs = (long) singleLoopDur * MAX_AUTO_PLAY_LOOPS;
            int relTime = 0;
            if (running) {
                long now = SystemClock.uptimeMillis();
                if (movieStart == 0) {
                    movieStart = now;
                }
                long elapsed = now - movieStart;
                if (elapsed >= totalPlayMs) {
                    // Play 3 cycles (WhatsApp/Telegram approach) then stop cleanly
                    running = false;
                    relTime = 0;
                } else {
                    relTime = (int) (elapsed % singleLoopDur);
                }
            }

            float scaleX = (float) b.width() / (float) mw;
            float scaleY = (float) b.height() / (float) mh;
            float scale = fitCenter ? Math.min(scaleX, scaleY) : Math.max(scaleX, scaleY);
            float dx = b.left + (b.width() - mw * scale) * 0.5f;
            float dy = b.top + (b.height() - mh * scale) * 0.5f;

            if (stripSolidBg) {
                if (!running && cachedRestingTransparentFrame != null && !cachedRestingTransparentFrame.isRecycled()) {
                    canvas.save();
                    canvas.translate(dx, dy);
                    canvas.scale(scale, scale);
                    canvas.drawBitmap(cachedRestingTransparentFrame, 0f, 0f, bitmapPaint);
                    canvas.restore();
                    return;
                }
                if (offscreenBuffer == null || offscreenBuffer.getWidth() != mw || offscreenBuffer.getHeight() != mh) {
                    offscreenBuffer = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
                    offscreenCanvas = new Canvas(offscreenBuffer);
                }
                offscreenBuffer.eraseColor(Color.TRANSPARENT);
                movie.setTime(relTime);
                movie.draw(offscreenCanvas, 0f, 0f);
                Bitmap transparentFrame = stripSolidBorderBackgroundIfPresent(offscreenBuffer);
                if (!running && relTime == 0) {
                    cachedRestingTransparentFrame = transparentFrame;
                }
                canvas.save();
                canvas.translate(dx, dy);
                canvas.scale(scale, scale);
                canvas.drawBitmap(transparentFrame, 0f, 0f, bitmapPaint);
                canvas.restore();
            } else {
                movie.setTime(relTime);
                canvas.save();
                canvas.translate(dx, dy);
                canvas.scale(scale, scale);
                movie.draw(canvas, 0f, 0f);
                canvas.restore();
            }

            if (running) {
                invalidateSelf();
            }
        }

        @Override
        public void start() {
            movieStart = SystemClock.uptimeMillis();
            running = true;
            invalidateSelf();
        }

        @Override
        public void stop() {
            running = false;
            invalidateSelf();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void setAlpha(int alpha) {
            bitmapPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {
            bitmapPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // =========================================================================
    // 8. COMPLETE PHOTO STICKER CREATOR STUDIO & ANIMATED VIDEO-TO-GIF89A STICKER ENCODER
    // =========================================================================

    public static final int STICKER_MODE_AUTO_CUTOUT = 0;
    public static final int STICKER_MODE_FULL_PORTRAIT = 1;
    public static final int STICKER_MODE_FREEHAND_CUT = 2;
    public static final int STICKER_MODE_SHAPE_CIRCLE = 3;
    public static final long MAX_VIDEO_STICKER_DURATION_MS = 5000L;

    /**
     * Decodes a photo file with EXIF rotation applied and scales its longest side to {@code maxSidePx},
     * strictly preserving the photo's natural Portrait (e.g. 9:16 / 3:4), Landscape, or Square aspect ratio.
     */
    @Nullable
    public static Bitmap decodePhotoPreservingAspectRatio(@NonNull File photoFile, int maxSidePx) {
        if (!photoFile.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bounds);
            int rawW = bounds.outWidth;
            int rawH = bounds.outHeight;
            if (rawW <= 0 || rawH <= 0) return null;

            int inSample = 1;
            while ((rawW / inSample) > maxSidePx * 2 || (rawH / inSample) > maxSidePx * 2) {
                inSample *= 2;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = inSample;
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), decodeOpts);
            if (decoded == null) return null;

            int rotationDeg = 0;
            try {
                android.media.ExifInterface exif = new android.media.ExifInterface(photoFile.getAbsolutePath());
                int ori = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
                if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotationDeg = 90;
                else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotationDeg = 180;
                else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotationDeg = 270;
            } catch (Throwable ignored) {}

            if (rotationDeg != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotationDeg);
                Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                if (rotated != decoded) decoded.recycle();
                decoded = rotated;
            }

            int w = decoded.getWidth();
            int h = decoded.getHeight();
            float scale = Math.min(1f, (float) maxSidePx / (float) Math.max(w, h));
            if (scale < 0.99f) {
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true);
                if (scaled != decoded) decoded.recycle();
                decoded = scaled;
            }
            return decoded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Normalizes any source bitmap into a 1:1 Square (sidePx x sidePx) ARGB_8888 bitmap:
     * - When {@code centerCrop} is true: center-crops the bitmap so the 1:1 square is completely filled.
     * - When {@code centerCrop} is false: scales and centers the bitmap inside the 1:1 square canvas with transparent padding.
     */
    @NonNull
    public static Bitmap fitOrCenterCropToSquare(@NonNull Bitmap src, int sidePx, boolean centerCrop) {
        int sw = Math.max(1, src.getWidth());
        int sh = Math.max(1, src.getHeight());
        int side = Math.max(64, sidePx);
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        float scale = centerCrop
                ? Math.max((float) side / sw, (float) side / sh)
                : (Math.min((float) side / sw, (float) side / sh) * 0.92f);
        float dw = sw * scale;
        float dh = sh * scale;
        float dx = (side - dw) * 0.5f;
        float dy = (side - dh) * 0.5f;
        RectF dst = new RectF(dx, dy, dx + dw, dy + dh);
        canvas.drawBitmap(src, null, dst, paint);
        return out;
    }

    /**
     * Generates a Telegram/WhatsApp-standard 512x512 (1:1 Square) custom Photo Sticker bitmap, supporting:
     * - {@link #STICKER_MODE_AUTO_CUTOUT}: Smart foreground subject extraction centered in a 512x512 square
     * - {@link #STICKER_MODE_FULL_PORTRAIT}: 1:1 Square center-cropped photo with die-cut rounded sticker corners
     * - {@link #STICKER_MODE_FREEHAND_CUT}: User finger-traced lasso cutout centered in a 512x512 square
     * - {@link #STICKER_MODE_SHAPE_CIRCLE}: Clean 1:1 Circle badge cutout in a 512x512 square
     * Plus optional crisp white die-cut contour outline, bold outlined sticker caption text, and corner emoji stamp.
     */
    @NonNull
    public static Bitmap createCustomPhotoStickerBitmap(
            @NonNull Bitmap src,
            int mode,
            boolean addWhiteOutline,
            @Nullable Path freehandViewPath,
            int viewW,
            int viewH,
            @Nullable String captionText,
            int captionColor,
            @Nullable String stampEmoji) {
        final int squareSide = 512;
        int sw = Math.max(1, src.getWidth());
        int sh = Math.max(1, src.getHeight());
        Bitmap working;

        if (mode == STICKER_MODE_FREEHAND_CUT && freehandViewPath != null && !freehandViewPath.isEmpty() && viewW > 0 && viewH > 0) {
            // Map user's finger-traced path from preview ImageView coordinates (FIT_CENTER) into bitmap coordinates
            float scale = Math.min((float) viewW / sw, (float) viewH / sh);
            float dx = (viewW - sw * scale) * 0.5f;
            float dy = (viewH - sh * scale) * 0.5f;
            android.graphics.Matrix inv = new android.graphics.Matrix();
            inv.postTranslate(-dx, -dy);
            inv.postScale(1f / Math.max(0.001f, scale), 1f / Math.max(0.001f, scale));

            Path bmpPath = new Path();
            freehandViewPath.transform(inv, bmpPath);
            bmpPath.close();

            Bitmap masked = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(masked);
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawPath(bmpPath, maskPaint);
            maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(src, 0f, 0f, maskPaint);

            RectF bounds = new RectF();
            bmpPath.computeBounds(bounds, true);
            int pad = Math.max(12, Math.round(Math.max(sw, sh) * 0.04f));
            int cropL = Math.max(0, Math.round(bounds.left) - pad);
            int cropT = Math.max(0, Math.round(bounds.top) - pad);
            int cropR = Math.min(sw, Math.round(bounds.right) + pad);
            int cropB = Math.min(sh, Math.round(bounds.bottom) + pad);
            Bitmap croppedCutout;
            if (cropR - cropL >= 32 && cropB - cropT >= 32) {
                croppedCutout = Bitmap.createBitmap(masked, cropL, cropT, cropR - cropL, cropB - cropT);
            } else {
                croppedCutout = masked;
            }
            working = fitOrCenterCropToSquare(croppedCutout, squareSide, false);
        } else if (mode == STICKER_MODE_SHAPE_CIRCLE) {
            Bitmap sqSrc = fitOrCenterCropToSquare(src, squareSide, true);
            working = Bitmap.createBitmap(squareSide, squareSide, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(working);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float inset = Math.max(12f, squareSide * 0.035f);
            RectF oval = new RectF(inset, inset, squareSide - inset, squareSide - inset);
            c.drawOval(oval, p);
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(sqSrc, 0f, 0f, p);
        } else if (mode == STICKER_MODE_AUTO_CUTOUT) {
            Bitmap cutout = extractSmartSubjectCutout(src);
            working = fitOrCenterCropToSquare(cutout, squareSide, false);
        } else {
            // STICKER_MODE_FULL_PORTRAIT (1:1 Square Sticker): Center-crop into 512x512 square with crisp rounded corners
            Bitmap sqSrc = fitOrCenterCropToSquare(src, squareSide, true);
            working = Bitmap.createBitmap(squareSide, squareSide, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(working);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float inset = addWhiteOutline ? Math.max(10f, squareSide * 0.028f) : 0f;
            float radius = Math.max(24f, squareSide * 0.08f);
            RectF rect = new RectF(inset, inset, squareSide - inset, squareSide - inset);
            c.drawRoundRect(rect, radius, radius, p);
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(sqSrc, 0f, 0f, p);
        }

        if (addWhiteOutline) {
            working = applyDieCutWhiteContourOutline(working, Math.max(5, Math.round(squareSide * 0.022f)));
        }

        // Draw optional corner emoji stamp and/or die-cut sticker caption text
        boolean hasCaption = captionText != null && !captionText.trim().isEmpty();
        boolean hasStamp = stampEmoji != null && !stampEmoji.trim().isEmpty();
        if (hasCaption || hasStamp) {
            Bitmap mutable = working.isMutable() ? working : working.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(mutable);
            int mw = mutable.getWidth();
            int mh = mutable.getHeight();
            float minDim = Math.min(mw, mh);

            if (hasStamp) {
                TextPaint emojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                emojiPaint.setTextSize(minDim * 0.20f);
                emojiPaint.setTextAlign(Paint.Align.CENTER);
                float ex = mw - minDim * 0.16f;
                float ey = minDim * 0.22f;
                canvas.save();
                canvas.rotate(12f, ex, ey);
                canvas.drawText(stampEmoji.trim(), ex, ey, emojiPaint);
                canvas.restore();
            }

            if (hasCaption) {
                drawStickerCaptionOnCanvas(canvas, captionText.trim(), captionColor != 0 ? captionColor : Color.WHITE, mw, mh);
            }
            working = mutable;
        }

        return working;
    }

    /**
     * Smart foreground subject segmentation for {@link #STICKER_MODE_AUTO_CUTOUT}:
     * Samples the outer perimeter color distribution and flood-fills connected background regions from all
     * 4 edges toward the center while protecting the central portrait subject, with smooth 2px feathering.
     */
    @NonNull
    private static Bitmap extractSmartSubjectCutout(@NonNull Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 16 || h < 16) return src.copy(Bitmap.Config.ARGB_8888, true);

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // If already transparent, return copy
        if (((pixels[0] >>> 24) < 32) && ((pixels[w - 1] >>> 24) < 32)) {
            return src.copy(Bitmap.Config.ARGB_8888, true);
        }

        // Compute average border palette across top, left, right edges
        long sumR = 0, sumG = 0, sumB = 0, count = 0;
        int step = Math.max(1, Math.min(w, h) / 32);
        for (int x = 0; x < w; x += step) {
            int pTop = pixels[x];
            sumR += (pTop >> 16) & 0xFF;
            sumG += (pTop >> 8) & 0xFF;
            sumB += pTop & 0xFF;
            count++;
        }
        for (int y = 0; y < h; y += step) {
            int pLeft = pixels[y * w];
            int pRight = pixels[y * w + (w - 1)];
            sumR += ((pLeft >> 16) & 0xFF) + ((pRight >> 16) & 0xFF);
            sumG += ((pLeft >> 8) & 0xFF) + ((pRight >> 8) & 0xFF);
            sumB += (pLeft & 0xFF) + (pRight & 0xFF);
            count += 2;
        }
        int bgR = (int) (sumR / Math.max(1, count));
        int bgG = (int) (sumG / Math.max(1, count));
        int bgB = (int) (sumB / Math.max(1, count));

        boolean[] visited = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;

        for (int x = 0; x < w; x++) {
            int topIdx = x;
            visited[topIdx] = true;
            queue[tail++] = topIdx;
            int botIdx = (h - 1) * w + x;
            if (!visited[botIdx]) {
                visited[botIdx] = true;
                queue[tail++] = botIdx;
            }
        }
        for (int y = 1; y < h - 1; y++) {
            int leftIdx = y * w;
            if (!visited[leftIdx]) {
                visited[leftIdx] = true;
                queue[tail++] = leftIdx;
            }
            int rightIdx = y * w + (w - 1);
            if (!visited[rightIdx]) {
                visited[rightIdx] = true;
                queue[tail++] = rightIdx;
            }
        }

        float cx = w * 0.5f;
        float cy = h * 0.52f;
        float rx = w * 0.28f;
        float ry = h * 0.34f;
        int removedCount = 0;

        while (head < tail) {
            int idx = queue[head++];
            int px = idx % w;
            int py = idx / w;

            // Protect core subject center oval unless color is nearly identical to border
            float normDx = (px - cx) / rx;
            float normDy = (py - cy) / ry;
            boolean insideSubjectCore = (normDx * normDx + normDy * normDy) < 0.85f;

            int c = pixels[idx];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int distGlobal = Math.max(Math.abs(r - bgR), Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
            int maxGlobalTol = insideSubjectCore ? 18 : 42;
            if (distGlobal > maxGlobalTol) {
                continue;
            }

            pixels[idx] = 0x00000000;
            removedCount++;

            // 4-connected neighbors with local gradient check
            int[] neighbors = new int[]{
                    px > 0 ? idx - 1 : -1,
                    px + 1 < w ? idx + 1 : -1,
                    py > 0 ? idx - w : -1,
                    py + 1 < h ? idx + w : -1
            };
            for (int nIdx : neighbors) {
                if (nIdx >= 0 && !visited[nIdx]) {
                    int nc = pixels[nIdx];
                    int nr = (nc >> 16) & 0xFF;
                    int ng = (nc >> 8) & 0xFF;
                    int nb = nc & 0xFF;
                    int localStep = Math.max(Math.abs(nr - r), Math.max(Math.abs(ng - g), Math.abs(nb - b)));
                    if (localStep <= 22) {
                        visited[nIdx] = true;
                        queue[tail++] = nIdx;
                    }
                }
            }
        }

        // If background is a complex multi-color real-world scene where < 8% of border pixels matched,
        // apply a clean portrait/subject rounded silhouette mask so the subject is never jagged
        if (removedCount < (w * h) / 14) {
            Bitmap fallback = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(fallback);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float insetX = w * 0.04f;
            float insetY = h * 0.03f;
            RectF rect = new RectF(insetX, insetY, w - insetX, h - insetY);
            float rad = Math.min(w, h) * 0.14f;
            c.drawRoundRect(rect, rad, rad, p);
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(src, 0f, 0f, p);
            return fallback;
        }

        // Feather 1px boundary for smooth anti-aliased cutout edges
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                if ((pixels[i] >>> 24) != 0) {
                    int transNeighbors = 0;
                    if ((pixels[i - 1] >>> 24) == 0) transNeighbors++;
                    if ((pixels[i + 1] >>> 24) == 0) transNeighbors++;
                    if ((pixels[i - w] >>> 24) == 0) transNeighbors++;
                    if ((pixels[i + w] >>> 24) == 0) transNeighbors++;
                    if (transNeighbors >= 2) {
                        pixels[i] = (0x99 << 24) | (pixels[i] & 0x00FFFFFF);
                    }
                }
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * Draws a crisp Telegram/WhatsApp-style white die-cut sticker outline around any non-transparent silhouette.
     */
    @NonNull
    public static Bitmap applyDieCutWhiteContourOutline(@NonNull Bitmap src, int strokeRadiusPx) {
        int w = src.getWidth();
        int h = src.getHeight();
        int r = Math.max(2, Math.min(14, strokeRadiusPx));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        Bitmap alphaMask = src.extractAlpha();
        Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setColor(Color.WHITE);

        // Draw radial 12-direction dilated silhouette to create a smooth continuous die-cut sticker border
        for (int i = 0; i < 12; i++) {
            double angle = (Math.PI * 2.0 * i) / 12.0;
            float dx = (float) (Math.cos(angle) * r);
            float dy = (float) (Math.sin(angle) * r);
            canvas.drawBitmap(alphaMask, dx, dy, outlinePaint);
        }
        int innerR = Math.max(1, r / 2);
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 * i) / 8.0;
            float dx = (float) (Math.cos(angle) * innerR);
            float dy = (float) (Math.sin(angle) * innerR);
            canvas.drawBitmap(alphaMask, dx, dy, outlinePaint);
        }
        alphaMask.recycle();

        Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(src, 0f, 0f, fgPaint);
        return out;
    }

    private static void drawStickerCaptionOnCanvas(@NonNull Canvas canvas, @NonNull String text, int fillColor, int w, int h) {
        float minDim = Math.min(w, h);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        float textSize = Math.max(18f, minDim * 0.105f);
        tp.setTextSize(textSize);
        float maxAllowedW = w * 0.88f;
        float measured = tp.measureText(text);
        if (measured > maxAllowedW && measured > 0f) {
            textSize = Math.max(14f, textSize * (maxAllowedW / measured));
            tp.setTextSize(textSize);
        }

        float cx = w * 0.5f;
        float cy = h - Math.max(20f, minDim * 0.085f);

        // Outer black contrast halo + white die-cut stroke + vibrant fill color
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeJoin(Paint.Join.ROUND);
        tp.setStrokeCap(Paint.Cap.ROUND);
        tp.setStrokeWidth(Math.max(6f, textSize * 0.24f));
        tp.setColor(0xDD000000);
        canvas.drawText(text, cx, cy, tp);

        if (fillColor != Color.WHITE) {
            tp.setStrokeWidth(Math.max(3f, textSize * 0.12f));
            tp.setColor(Color.WHITE);
            canvas.drawText(text, cx, cy, tp);
        }

        tp.setStyle(Paint.Style.FILL);
        tp.setColor(fillColor);
        canvas.drawText(text, cx, cy, tp);
    }

    /**
     * Creates a real multi-frame looping Animated GIF89a Sticker file (`.gif`) from a trimmed video range
     * `[startMs, endMs]` (strictly capped at {@link #MAX_VIDEO_STICKER_DURATION_MS} = 5.0 seconds),
     * preserving the video's true Portrait (e.g. 9:16), Landscape (16:9), or Square (1:1) aspect ratio!
     */
    @Nullable
    public static File createAnimatedVideoStickerFile(
            @NonNull Context context,
            @NonNull File videoFile,
            long startMs,
            long endMs,
            boolean addWhiteOutline,
            @Nullable String captionText,
            int captionColor) {
        if (!videoFile.exists()) return null;
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(videoFile.getAbsolutePath());
            String durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long totalMs = 5000L;
            if (durStr != null) {
                try {
                    totalMs = Math.max(500L, Long.parseLong(durStr));
                } catch (NumberFormatException ignored) {}
            }

            int metaW = 0;
            int metaH = 0;
            int metaRot = 0;
            try {
                String wStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String rStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (wStr != null) metaW = Integer.parseInt(wStr);
                if (hStr != null) metaH = Integer.parseInt(hStr);
                if (rStr != null) metaRot = Integer.parseInt(rStr);
            } catch (Throwable ignored) {}
            int expectedDisplayW = (metaRot == 90 || metaRot == 270) ? metaH : metaW;
            int expectedDisplayH = (metaRot == 90 || metaRot == 270) ? metaW : metaH;

            long safeStart = Math.max(0L, Math.min(startMs, totalMs - 200L));
            long safeEnd = Math.max(safeStart + 300L, Math.min(endMs, totalMs));
            if (safeEnd - safeStart > MAX_VIDEO_STICKER_DURATION_MS) {
                safeEnd = safeStart + MAX_VIDEO_STICKER_DURATION_MS;
            }
            long clipDurationMs = Math.max(400L, safeEnd - safeStart);

            // Sample at ~8 FPS across [safeStart, safeEnd] (between 6 and 36 frames for smooth animated playback)
            int frameDelayMs = 120;
            int frameCount = (int) Math.max(6, Math.min(36, clipDurationMs / frameDelayMs));
            long stepUs = (clipDurationMs * 1000L) / Math.max(1, frameCount);

            List<Bitmap> frames = new ArrayList<>(frameCount);
            int targetW = 0;
            int targetH = 0;

            for (int i = 0; i < frameCount; i++) {
                long timeUs = (safeStart * 1000L) + (i * stepUs);
                Bitmap raw = mmr.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                if (raw == null) {
                    raw = mmr.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (raw == null && !frames.isEmpty()) {
                    frames.add(frames.get(frames.size() - 1));
                    continue;
                }
                if (raw == null) continue;

                // Ensure frame orientation matches true Portrait or Landscape display orientation
                if ((metaRot == 90 || metaRot == 270) && expectedDisplayW > 0 && expectedDisplayH > 0) {
                    boolean shouldBePortrait = expectedDisplayH > expectedDisplayW;
                    boolean rawIsLandscape = raw.getWidth() > raw.getHeight();
                    if (shouldBePortrait && rawIsLandscape) {
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.postRotate(metaRot);
                        Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                        if (rotated != raw) raw.recycle();
                        raw = rotated;
                    }
                }

                if (targetW <= 0 || targetH <= 0) {
                    // Always render animated video stickers in a 320x320 (1:1 Square) canvas
                    targetW = 320;
                    targetH = 320;
                }

                Bitmap scaled = fitOrCenterCropToSquare(raw, targetW, true);
                if (scaled != raw) raw.recycle();

                // Apply rounded sticker corners + optional white die-cut border + optional caption
                Bitmap styled = styleVideoStickerFrame(scaled, addWhiteOutline, captionText, captionColor);
                if (styled != scaled) scaled.recycle();
                frames.add(styled);
            }

            if (frames.isEmpty()) return null;

            File stickerDir = new File(context.getFilesDir(), "custom_stickers");
            if (!stickerDir.exists()) stickerDir.mkdirs();
            File outGif = new File(stickerDir, "sticker_" + System.currentTimeMillis() + ".gif");
            try (java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(new java.io.FileOutputStream(outGif), 32768)) {
                writeAnimatedGif89a(bos, frames, targetW, targetH, frameDelayMs / 10);
                bos.flush();
            }

            DIMENSION_CACHE.put(outGif.getAbsolutePath(), new int[]{targetW, targetH});
            for (Bitmap b : frames) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            return (outGif.exists() && outGif.length() > 64) ? outGif : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    @NonNull
    private static Bitmap styleVideoStickerFrame(
            @NonNull Bitmap frame,
            boolean addWhiteOutline,
            @Nullable String captionText,
            int captionColor) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        float inset = addWhiteOutline ? Math.max(4f, Math.min(w, h) * 0.022f) : 0f;
        float radius = Math.max(14f, Math.min(w, h) * 0.08f);
        RectF rect = new RectF(inset, inset, w - inset, h - inset);

        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawRoundRect(rect, radius, radius, maskPaint);
        maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(frame, 0f, 0f, maskPaint);

        if (addWhiteOutline) {
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(3f, inset * 0.9f));
            borderPaint.setColor(Color.WHITE);
            c.drawRoundRect(rect, radius, radius, borderPaint);
        }

        if (captionText != null && !captionText.trim().isEmpty()) {
            drawStickerCaptionOnCanvas(c, captionText.trim(), captionColor != 0 ? captionColor : Color.WHITE, w, h);
        }
        return out;
    }

    /**
     * Compact, dependency-free GIF89a multi-frame encoder with 3-3-2 RGB quantization + transparent index 255
     * and Netscape 2.0 looping extension so custom video stickers animate natively via {@link AnimatedImageDrawable}
     * and {@link MovieGifDrawable}.
     */
    private static void writeAnimatedGif89a(
            @NonNull java.io.OutputStream os,
            @NonNull List<Bitmap> frames,
            int width,
            int height,
            int delayCentiseconds) throws java.io.IOException {
        // 1. Header: GIF89a
        os.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});

        // 2. Logical Screen Descriptor (Global Color Table Flag = 1, 256 colors -> 0xF7)
        writeShortLE(os, width);
        writeShortLE(os, height);
        os.write(0xF7); // GCT present, 8-bit color resolution, 256 entries
        os.write(255);  // Background color index (transparent index 255)
        os.write(0);    // Pixel aspect ratio

        // 3. Global Color Table (256 entries: 0..254 = 3-3-2 RGB cube, 255 = transparent key)
        for (int i = 0; i < 256; i++) {
            if (i == 255) {
                os.write(0);
                os.write(0);
                os.write(0);
            } else {
                int r3 = (i >> 5) & 0x07;
                int g3 = (i >> 2) & 0x07;
                int b2 = i & 0x03;
                os.write((r3 * 255) / 7);
                os.write((g3 * 255) / 7);
                os.write((b2 * 255) / 3);
            }
        }

        // 4. Netscape 2.0 Application Extension for looping
        os.write(new byte[]{
                0x21, (byte) 0xFF, 0x0B,
                'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0',
                0x03, 0x01, 0x00, 0x00, 0x00
        });

        int[] argb = new int[width * height];
        byte[] indexed = new byte[width * height];

        for (Bitmap bmp : frames) {
            if (bmp == null || bmp.isRecycled()) continue;
            bmp.getPixels(argb, 0, width, 0, 0, width, height);
            for (int i = 0; i < argb.length; i++) {
                int c = argb[i];
                int a = (c >>> 24) & 0xFF;
                if (a < 96) {
                    indexed[i] = (byte) 255;
                } else {
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    int idx = ((r >> 5) << 5) | ((g >> 5) << 2) | (b >> 6);
                    if (idx == 255) idx = 254;
                    indexed[i] = (byte) idx;
                }
            }

            // Graphic Control Extension (Disposal = 2: restore to background/transparent, transparent flag = 1)
            os.write(0x21);
            os.write(0xF9);
            os.write(0x04);
            os.write(0x09); // Disposal method 2 (bits 2-4 = 010) + transparent color flag (bit 0 = 1)
            writeShortLE(os, Math.max(4, delayCentiseconds));
            os.write(255);  // Transparent color index
            os.write(0x00);

            // Image Descriptor
            os.write(0x2C);
            writeShortLE(os, 0);
            writeShortLE(os, 0);
            writeShortLE(os, width);
            writeShortLE(os, height);
            os.write(0x00); // No local color table, non-interlaced

            // LZW Image Data
            writeLzwImageData(os, indexed);
        }

        // 5. GIF Trailer
        os.write(0x3B);
    }

    private static void writeShortLE(@NonNull java.io.OutputStream os, int val) throws java.io.IOException {
        os.write(val & 0xFF);
        os.write((val >> 8) & 0xFF);
    }

    /**
     * Fast LZW encoder for 8-bit indexed GIF frames using 12-bit hash table and periodic clear codes.
     */
    private static void writeLzwImageData(@NonNull java.io.OutputStream os, @NonNull byte[] pixels) throws java.io.IOException {
        final int minCodeSize = 8;
        os.write(minCodeSize);

        final int clearCode = 1 << minCodeSize; // 256
        final int endCode = clearCode + 1;      // 257
        int nextCode = endCode + 1;             // 258
        int codeSize = minCodeSize + 1;         // 9

        final int tableSize = 5003;
        int[] hashKeys = new int[tableSize];
        int[] hashValues = new int[tableSize];
        java.util.Arrays.fill(hashKeys, -1);

        byte[] subBlock = new byte[255];
        int subBlockLen = 0;
        int bitBuffer = 0;
        int bitCount = 0;

        // Emit initial Clear Code
        bitBuffer |= (clearCode << bitCount);
        bitCount += codeSize;
        while (bitCount >= 8) {
            subBlock[subBlockLen++] = (byte) (bitBuffer & 0xFF);
            if (subBlockLen == 255) {
                os.write(255);
                os.write(subBlock, 0, 255);
                subBlockLen = 0;
            }
            bitBuffer >>>= 8;
            bitCount -= 8;
        }

        if (pixels.length > 0) {
            int prefix = pixels[0] & 0xFF;
            for (int i = 1; i < pixels.length; i++) {
                int k = pixels[i] & 0xFF;
                int key = (prefix << 8) | k;
                int h = ((prefix << 4) ^ k) % tableSize;
                if (h < 0) h += tableSize;

                boolean found = false;
                while (hashKeys[h] != -1) {
                    if (hashKeys[h] == key) {
                        prefix = hashValues[h];
                        found = true;
                        break;
                    }
                    h = (h + 1) % tableSize;
                }

                if (!found) {
                    // Output prefix code
                    bitBuffer |= (prefix << bitCount);
                    bitCount += codeSize;
                    while (bitCount >= 8) {
                        subBlock[subBlockLen++] = (byte) (bitBuffer & 0xFF);
                        if (subBlockLen == 255) {
                            os.write(255);
                            os.write(subBlock, 0, 255);
                            subBlockLen = 0;
                        }
                        bitBuffer >>>= 8;
                        bitCount -= 8;
                    }

                    if (nextCode < 4096) {
                        hashKeys[h] = key;
                        hashValues[h] = nextCode;
                        if (nextCode == (1 << codeSize) && codeSize < 12) {
                            codeSize++;
                        }
                        nextCode++;
                    } else {
                        // Table full: emit Clear Code and reset dictionary
                        bitBuffer |= (clearCode << bitCount);
                        bitCount += codeSize;
                        while (bitCount >= 8) {
                            subBlock[subBlockLen++] = (byte) (bitBuffer & 0xFF);
                            if (subBlockLen == 255) {
                                os.write(255);
                                os.write(subBlock, 0, 255);
                                subBlockLen = 0;
                            }
                            bitBuffer >>>= 8;
                            bitCount -= 8;
                        }
                        java.util.Arrays.fill(hashKeys, -1);
                        nextCode = endCode + 1;
                        codeSize = minCodeSize + 1;
                    }
                    prefix = k;
                }
            }

            // Emit final prefix
            bitBuffer |= (prefix << bitCount);
            bitCount += codeSize;
            while (bitCount >= 8) {
                subBlock[subBlockLen++] = (byte) (bitBuffer & 0xFF);
                if (subBlockLen == 255) {
                    os.write(255);
                    os.write(subBlock, 0, 255);
                    subBlockLen = 0;
                }
                bitBuffer >>>= 8;
                bitCount -= 8;
            }
        }

        // Emit End of Information Code
        bitBuffer |= (endCode << bitCount);
        bitCount += codeSize;
        while (bitCount > 0) {
            subBlock[subBlockLen++] = (byte) (bitBuffer & 0xFF);
            if (subBlockLen == 255) {
                os.write(255);
                os.write(subBlock, 0, 255);
                subBlockLen = 0;
            }
            bitBuffer >>>= 8;
            bitCount -= 8;
        }

        if (subBlockLen > 0) {
            os.write(subBlockLen);
            os.write(subBlock, 0, subBlockLen);
        }
        os.write(0x00); // Block terminator
    }
}
