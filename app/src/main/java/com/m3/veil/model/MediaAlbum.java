package com.m3.veil.model;

import android.net.Uri;

public class MediaAlbum {
    private final String id;
    private final String name;
    private Uri coverUri;
    private int count;

    public MediaAlbum(String id, String name, Uri coverUri, int count) {
        this.id = id;
        this.name = name;
        this.coverUri = coverUri;
        this.count = count;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Uri getCoverUri() { return coverUri; }
    public void setCoverUri(Uri coverUri) { this.coverUri = coverUri; }
    public int getCount() { return count; }
    public void incrementCount() { this.count++; }

    @Override
    public String toString() {
        return name + " (" + count + ")";
    }
}
