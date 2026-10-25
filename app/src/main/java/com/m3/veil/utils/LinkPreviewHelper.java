package com.m3.veil.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkPreviewHelper
 * WhatsApp & Telegram-style OpenGraph rich card parser and link metadata cache.
 * Asynchronously extracts og:title, og:description, og:image, and domain from URLs.
 */
public class LinkPreviewHelper {

    public static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[a-zA-Z0-9+&@#/%?=~_|!:,.;]*[a-zA-Z0-9+&@#/%=~_|])",
            Pattern.CASE_INSENSITIVE
    );

    public static class LinkPreview {
        public final String url;
        public final String domain;
        public final String title;
        public final String description;
        public final String imageUrl;
        public Bitmap imageBitmap;

        public LinkPreview(String url, String domain, String title, String description, String imageUrl, Bitmap imageBitmap) {
            this.url = url;
            this.domain = domain;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
            this.imageBitmap = imageBitmap;
        }

        public boolean hasContent() {
            return (title != null && !title.isEmpty()) || (description != null && !description.isEmpty());
        }
    }

    public interface PreviewCallback {
        void onPreviewLoaded(@Nullable LinkPreview preview);
    }

    private static final LruCache<String, LinkPreview> sPreviewCache = new LruCache<>(100);
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    public static String extractUrl(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = URL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Nullable
    public static LinkPreview getCachedPreview(@NonNull String url) {
        return sPreviewCache.get(url);
    }

    public static void fetchPreview(@NonNull final String targetUrl, @NonNull final PreviewCallback callback) {
        LinkPreview cached = sPreviewCache.get(targetUrl);
        if (cached != null) {
            sMainHandler.post(() -> callback.onPreviewLoaded(cached));
            return;
        }

        sExecutor.execute(() -> {
            LinkPreview parsed = null;
            try {
                parsed = parseMetadata(targetUrl);
                if (parsed != null && parsed.hasContent()) {
                    sPreviewCache.put(targetUrl, parsed);
                }
            } catch (Exception ignored) {
            }

            final LinkPreview result = parsed;
            sMainHandler.post(() -> callback.onPreviewLoaded(result));
        });
    }

    private static LinkPreview parseMetadata(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 400) {
                return null;
            }

            String contentType = conn.getContentType();
            if (contentType != null && !contentType.toLowerCase().contains("text/html") && !contentType.toLowerCase().contains("xhtml")) {
                // If direct image URL
                if (contentType.toLowerCase().startsWith("image/")) {
                    Bitmap bmp = decodeImage(urlString);
                    String domain = extractDomain(urlString);
                    return new LinkPreview(urlString, domain, domain, "Image", urlString, bmp);
                }
                return null;
            }

            StringBuilder headHtml = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                int totalChars = 0;
                while ((line = reader.readLine()) != null) {
                    headHtml.append(line).append(" ");
                    totalChars += line.length();
                    if (line.toLowerCase().contains("</head>") || totalChars > 65536) {
                        break;
                    }
                }
            }

            String html = headHtml.toString();
            String title = extractTagContent(html, "property=\"og:title\"");
            if (title == null) title = extractTagContent(html, "name=\"twitter:title\"");
            if (title == null) title = extractHtmlTitle(html);

            String description = extractTagContent(html, "property=\"og:description\"");
            if (description == null) description = extractTagContent(html, "name=\"twitter:description\"");
            if (description == null) description = extractTagContent(html, "name=\"description\"");

            String imageUrl = extractTagContent(html, "property=\"og:image\"");
            if (imageUrl == null) imageUrl = extractTagContent(html, "name=\"twitter:image\"");

            // Youtube fallback
            if (imageUrl == null && (urlString.contains("youtube.com") || urlString.contains("youtu.be"))) {
                String ytId = extractYouTubeId(urlString);
                if (ytId != null) {
                    imageUrl = "https://img.youtube.com/vi/" + ytId + "/hqdefault.jpg";
                }
            }

            // Resolve relative image URLs
            if (imageUrl != null && imageUrl.startsWith("/")) {
                Uri baseUri = Uri.parse(urlString);
                imageUrl = baseUri.getScheme() + "://" + baseUri.getAuthority() + imageUrl;
            }

            Bitmap imageBitmap = null;
            if (imageUrl != null && !imageUrl.isEmpty()) {
                imageBitmap = decodeImage(imageUrl);
            }

            String domain = extractDomain(urlString);
            if (title != null) title = unescapeHtml(title).trim();
            if (description != null) description = unescapeHtml(description).trim();

            if (title == null && description == null) {
                return null;
            }

            return new LinkPreview(urlString, domain, title, description, imageUrl, imageBitmap);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String extractTagContent(String html, String attributeMarker) {
        // match: <meta ... attributeMarker ... content="([^"]+)" or reverse
        Pattern pattern1 = Pattern.compile("<meta[^>]+" + Pattern.quote(attributeMarker) + "[^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m1 = pattern1.matcher(html);
        if (m1.find()) return m1.group(1);

        Pattern pattern2 = Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+" + Pattern.quote(attributeMarker), Pattern.CASE_INSENSITIVE);
        Matcher m2 = pattern2.matcher(html);
        if (m2.find()) return m2.group(1);

        return null;
    }

    private static String extractHtmlTitle(String html) {
        Pattern pattern = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    public static String extractDomain(String urlString) {
        try {
            Uri uri = Uri.parse(urlString);
            String host = uri.getHost();
            if (host != null) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {
        }
        return "WEB LINK";
    }

    private static String extractYouTubeId(String url) {
        Pattern pattern = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|watch\\?v=|watch\\?.+&v=))([\\w-]{11})");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static Bitmap decodeImage(String imgUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(imgUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoInput(true);
            conn.connect();

            try (InputStream input = conn.getInputStream()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String unescapeHtml(String text) {
        return text.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }
}
