package com.m3.veil.utils;

import android.content.Context;
import android.text.format.Formatter;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Intelligent Media Cache Manager for VEIL.
 * Implements automatic background cache pruning, orphaned temp file cleanup,
 * and LRU eviction to prevent storage bloat.
 */
public class VeilMediaCacheManager {

    private static final String TAG = "VeilMediaCacheManager";

    // 24 hours in milliseconds for ephemeral temp files (crops, recordings, partials)
    private static final long TEMP_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    // 48 hours in milliseconds for expired status media
    private static final long STATUS_CACHE_MAX_AGE_MS = 48 * 60 * 60 * 1000L;

    // Maximum cache size before LRU eviction triggers (250 MB)
    private static final long MAX_CACHE_SIZE_BYTES = 250 * 1024 * 1024L;

    // Target size after LRU eviction (100 MB)
    private static final long TARGET_CACHE_SIZE_BYTES = 100 * 1024 * 1024L;

    /**
     * Runs in background on app startup or idle. Prunes stale temp artifacts,
     * expired statuses, and keeps the cache within safe thresholds.
     */
    public static void autoPruneCache(Context context) {
        if (context == null) return;
        new Thread(() -> {
            try {
                Context appCtx = context.getApplicationContext();
                long now = System.currentTimeMillis();

                List<File> cacheDirs = new ArrayList<>();
                File internalCache = appCtx.getCacheDir();
                if (internalCache != null && internalCache.exists()) cacheDirs.add(internalCache);

                File externalCache = appCtx.getExternalCacheDir();
                if (externalCache != null && externalCache.exists()) cacheDirs.add(externalCache);

                int filesDeleted = 0;
                long bytesReclaimed = 0;

                // 1. Prune ephemeral temp files & old status media
                for (File dir : cacheDirs) {
                    List<File> allFiles = new ArrayList<>();
                    collectFiles(dir, allFiles);

                    for (File f : allFiles) {
                        if (f == null || !f.isFile()) continue;
                        String name = f.getName().toLowerCase();
                        long age = now - f.lastModified();

                        boolean isTemp = name.startsWith("cropped_avatar_")
                                || name.startsWith("temp_crop_")
                                || name.startsWith("ucrop_")
                                || name.startsWith("voice_record_")
                                || name.startsWith("temp_voice_")
                                || name.startsWith("temp_capture_")
                                || name.startsWith("trimmed_")
                                || name.startsWith("video_record_")
                                || name.endsWith(".tmp")
                                || name.endsWith(".partial");

                        boolean isStatusMedia = name.startsWith("status_")
                                || name.contains("status_voice")
                                || name.contains("status_vid");

                        if (isTemp && age > TEMP_FILE_MAX_AGE_MS) {
                            long len = f.length();
                            if (f.delete()) {
                                filesDeleted++;
                                bytesReclaimed += len;
                            }
                        } else if (isStatusMedia && age > STATUS_CACHE_MAX_AGE_MS) {
                            long len = f.length();
                            if (f.delete()) {
                                filesDeleted++;
                                bytesReclaimed += len;
                            }
                        }
                    }
                }

                // 2. LRU eviction if cache exceeds MAX_CACHE_SIZE_BYTES
                long totalCache = calculateTotalCache(cacheDirs);
                if (totalCache > MAX_CACHE_SIZE_BYTES) {
                    List<File> allCacheFiles = new ArrayList<>();
                    for (File dir : cacheDirs) {
                        collectFiles(dir, allCacheFiles);
                    }
                    // Sort oldest first
                    Collections.sort(allCacheFiles, Comparator.comparingLong(File::lastModified));

                    for (File f : allCacheFiles) {
                        if (totalCache <= TARGET_CACHE_SIZE_BYTES) break;
                        if (f.isFile()) {
                            long len = f.length();
                            if (f.delete()) {
                                totalCache -= len;
                                bytesReclaimed += len;
                                filesDeleted++;
                            }
                        }
                    }
                }

                if (filesDeleted > 0) {
                    Log.i(TAG, "autoPruneCache completed: reclaimed " + bytesReclaimed + " bytes (" + filesDeleted + " files)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "autoPruneCache error: " + t.getMessage());
            }
        }, "VeilMediaCachePruner").start();
    }

    public static long calculateTotalCache(List<File> dirs) {
        long total = 0;
        if (dirs == null) return 0;
        for (File d : dirs) {
            total += getFolderSize(d);
        }
        return total;
    }

    public static long getFolderSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long size = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                size += getFolderSize(child);
            }
        }
        return size;
    }

    private static void collectFiles(File dir, List<File> accumulator) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile()) {
            accumulator.add(dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    collectFiles(f, accumulator);
                } else if (f.isFile()) {
                    accumulator.add(f);
                }
            }
        }
    }

    public static String getReadableCacheSize(Context context) {
        if (context == null) return "0 B";
        List<File> dirs = new ArrayList<>();
        File ic = context.getCacheDir();
        if (ic != null) dirs.add(ic);
        File ec = context.getExternalCacheDir();
        if (ec != null) dirs.add(ec);
        long total = calculateTotalCache(dirs);
        return Formatter.formatFileSize(context, total);
    }
}
