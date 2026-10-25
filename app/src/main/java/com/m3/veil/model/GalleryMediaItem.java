package com.m3.veil.model;

import android.net.Uri;

public class GalleryMediaItem {
    private final long id;
    private final Uri uri;
    private final String path;
    private final boolean isVideo;
    private final long durationMs;
    private final boolean isCameraTile;
    private final String bucketId;
    private final String bucketName;
    private final long dateAdded;
    private boolean isSelected;
    private int selectionIndex = -1; // 1, 2, 3... for multi-select badges

    public GalleryMediaItem(long id, Uri uri, String path, boolean isVideo, long durationMs) {
        this(id, uri, path, isVideo, durationMs, "", "All", 0);
    }

    public GalleryMediaItem(long id, Uri uri, String path, boolean isVideo, long durationMs,
                            String bucketId, String bucketName, long dateAdded) {
        this.id = id;
        this.uri = uri;
        this.path = path;
        this.isVideo = isVideo;
        this.durationMs = durationMs;
        this.bucketId = bucketId != null ? bucketId : "";
        this.bucketName = bucketName != null ? bucketName : "All";
        this.dateAdded = dateAdded;
        this.isCameraTile = false;
        this.isSelected = false;
        this.selectionIndex = -1;
    }

    public static GalleryMediaItem createCameraTile() {
        return new GalleryMediaItem();
    }

    private GalleryMediaItem() {
        this.id = -1;
        this.uri = null;
        this.path = null;
        this.isVideo = false;
        this.durationMs = 0;
        this.bucketId = "";
        this.bucketName = "";
        this.dateAdded = Long.MAX_VALUE;
        this.isCameraTile = true;
        this.isSelected = false;
        this.selectionIndex = -1;
    }

    public long getId() { return id; }
    public Uri getUri() { return uri; }
    public String getPath() { return path; }
    public boolean isVideo() { return isVideo; }
    public long getDurationMs() { return durationMs; }
    public boolean isCameraTile() { return isCameraTile; }
    public String getBucketId() { return bucketId; }
    public String getBucketName() { return bucketName; }
    public long getDateAdded() { return dateAdded; }
    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }
    public int getSelectionIndex() { return selectionIndex; }
    public void setSelectionIndex(int selectionIndex) { this.selectionIndex = selectionIndex; }
}
