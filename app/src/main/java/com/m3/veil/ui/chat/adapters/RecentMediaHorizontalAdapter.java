package com.m3.veil.ui.chat.adapters;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecentMediaHorizontalAdapter extends RecyclerView.Adapter<RecentMediaHorizontalAdapter.RecentViewHolder> {

    public interface OnRecentMediaClickListener {
        void onRecentMediaClicked(GalleryMediaItem item);
    }

    private final List<GalleryMediaItem> items;
    private final OnRecentMediaClickListener listener;
    private static final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "VeilRecentMediaThumb");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final LruCache<Uri, Bitmap> sThumbCache = new LruCache<>(100);

    public RecentMediaHorizontalAdapter(List<GalleryMediaItem> items, OnRecentMediaClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RecentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_media_horizontal, parent, false);
        return new RecentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecentViewHolder holder, int position) {
        GalleryMediaItem item = items.get(position);

        holder.ivThumb.setImageDrawable(null);
        holder.ivThumb.setTag(item.getUri());
        holder.ivVideoIcon.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

        loadThumbnailAsync(item.getUri(), holder.ivThumb);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onRecentMediaClicked(item);
        });
    }

    private void loadThumbnailAsync(Uri uri, ImageView imageView) {
        if (uri == null) return;
        Bitmap cached = sThumbCache.get(uri);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }
        final ContentResolver cr = imageView.getContext().getContentResolver();
        executor.execute(() -> {
            Bitmap thumb = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thumb = cr.loadThumbnail(uri, new Size(160, 160), null);
                } else {
                    try (InputStream is = cr.openInputStream(uri)) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 4;
                        thumb = BitmapFactory.decodeStream(is, null, opts);
                    }
                }
            } catch (Exception ignored) {}

            final Bitmap res = thumb;
            if (res != null) {
                sThumbCache.put(uri, res);
            }
            mainHandler.post(() -> {
                if (uri.equals(imageView.getTag()) && res != null) {
                    imageView.setImageBitmap(res);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class RecentViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        ImageView ivVideoIcon;

        public RecentViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivRecentThumb);
            ivVideoIcon = itemView.findViewById(R.id.ivRecentVideoIcon);
        }
    }
}
