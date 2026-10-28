package com.m3.veil.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;

/**
 * Ultra-fast pure-Java StackBlur implementation for creating WhatsApp-style
 * blurred media previews before downloading.
 * Includes in-memory LruCache for instant 60fps rendering in RecyclerView.
 */
public class BlurUtils {

    private static final LruCache<String, Bitmap> blurMemoryCache = new LruCache<String, Bitmap>(4096) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024); // KB
        }
    };

    /**
     * Clear all cached blurred thumbnails on memory trim or low RAM signals.
     */
    public static void clearCache() {
        blurMemoryCache.evictAll();
    }

    /**
     * Retrieve or generate a smoothly blurred Bitmap from a base64 thumbnail string.
     */
    public static Bitmap getBlurredThumbnail(Context context, String base64Thumbnail) {
        if (base64Thumbnail == null || base64Thumbnail.trim().isEmpty()) {
            return null;
        }

        String cacheKey = base64Thumbnail.hashCode() + "_" + base64Thumbnail.length();
        Bitmap cached = blurMemoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        try {
            byte[] decoded = Base64.decode(base64Thumbnail, Base64.DEFAULT);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = VeilPerformanceController.getInstance().getPreferredBitmapConfig();
            Bitmap rawThumb = BitmapFactory.decodeByteArray(decoded, 0, decoded.length, opts);
            if (rawThumb == null) return null;

            boolean allowHeavyBlur = VeilPerformanceController.getInstance().isBlurShaderEnabled();

            // Scale down if large to ensure blur takes under 3ms
            int maxDim = allowHeavyBlur ? 80 : 48;
            Bitmap scaled;
            if (rawThumb.getWidth() > maxDim || rawThumb.getHeight() > maxDim) {
                float aspect = (float) rawThumb.getWidth() / rawThumb.getHeight();
                int w = maxDim;
                int h = (int) (maxDim / aspect);
                scaled = Bitmap.createScaledBitmap(rawThumb, w, Math.max(1, h), true);
            } else {
                scaled = rawThumb;
            }

            int radius = allowHeavyBlur ? 18 : 6;
            Bitmap blurred = fastStackBlur(scaled, radius);
            if (blurred != null) {
                blurMemoryCache.put(cacheKey, blurred);
                return blurred;
            }
            blurMemoryCache.put(cacheKey, scaled);
            return scaled;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * StackBlur algorithm by Mario Klingemann <mario@quasimondo.com>.
     * Pure Java implementation: fast, high quality, and works across all Android versions.
     */
    public static Bitmap fastStackBlur(Bitmap sentBitmap, int radius) {
        if (radius < 1 || sentBitmap == null) return null;

        Bitmap bitmap;
        try {
            bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true);
        } catch (Throwable t) {
            return sentBitmap;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) {
            dv[i] = (i / divsum);
        }

        yw = yi = 0;

        int[][] stack = new int[div][3];
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }

        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;

                sir = stack[i + radius];

                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];

                rbs = r1 - Math.abs(i);

                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;

                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }

                if (i < hm) {
                    yp += w;
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * w;
                }
                p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
        return bitmap;
    }
}
