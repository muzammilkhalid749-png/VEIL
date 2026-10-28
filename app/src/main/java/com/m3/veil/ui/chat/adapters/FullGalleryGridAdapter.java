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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;
import com.m3.veil.utils.VoicePlayerManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FullGalleryGridAdapter extends RecyclerView.Adapter<FullGalleryGridAdapter.MediaGridViewHolder> {

    public interface OnGalleryInteractionListener {
        void onMediaItemClicked(GalleryMediaItem item);
        void onSelectionChanged(List<GalleryMediaItem> selectedItems);
    }

    private final List<GalleryMediaItem> items;
    private final List<GalleryMediaItem> selectedItems = new ArrayList<>();
    private final OnGalleryInteractionListener listener;
    private static final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "VeilFullGalleryThumb");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final LruCache<Uri, Bitmap> sThumbCache = new LruCache<>(300);

    public FullGalleryGridAdapter(List<GalleryMediaItem> items, OnGalleryInteractionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public MediaGridViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_full_gallery_grid, parent, false);
        return new MediaGridViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaGridViewHolder holder, int position) {
        GalleryMediaItem item = items.get(position);

        holder.ivThumb.setImageDrawable(null);
        holder.ivThumb.setTag(item.getUri());

        loadThumbnailAsync(item.getUri(), holder.ivThumb);

        // Video duration badge
        if (item.isVideo()) {
            holder.layoutVideoDuration.setVisibility(View.VISIBLE);
            holder.tvDuration.setText(VoicePlayerManager.formatDuration((int) item.getDurationMs()));
        } else {
            holder.layoutVideoDuration.setVisibility(View.GONE);
        }

        // Multi-select state
        boolean isSelected = item.isSelected();
        holder.viewSelectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.layoutSelectionBadge.setBackgroundResource(
                isSelected ? R.drawable.bg_voice_circle_received : R.drawable.bg_circle_translucent
        );

        if (isSelected && item.getSelectionIndex() > 0) {
            holder.tvSelectionNumber.setText(String.valueOf(item.getSelectionIndex()));
            holder.tvSelectionNumber.setVisibility(View.VISIBLE);
        } else {
            holder.tvSelectionNumber.setVisibility(View.GONE);
        }

        // Tap cell
        holder.itemView.setOnClickListener(v -> {
            if (!selectedItems.isEmpty()) {
                // If in multi-select mode, cell tap toggles selection
                toggleSelection(item, holder.getAdapterPosition());
            } else {
                if (listener != null) listener.onMediaItemClicked(item);
            }
        });

        // Tap badge directly toggles multi-select
        holder.layoutSelectionBadge.setOnClickListener(v -> {
            toggleSelection(item, holder.getAdapterPosition());
        });

        // Long press starts multi-select
        holder.itemView.setOnLongClickListener(v -> {
            toggleSelection(item, holder.getAdapterPosition());
            return true;
        });
    }

    private void toggleSelection(GalleryMediaItem item, int position) {
        if (item.isSelected()) {
            item.setSelected(false);
            item.setSelectionIndex(-1);
            selectedItems.remove(item);
            // Re-index remaining selected items (1, 2, 3...) like WhatsApp
            for (int i = 0; i < selectedItems.size(); i++) {
                selectedItems.get(i).setSelectionIndex(i + 1);
            }
            notifyDataSetChanged();
        } else {
            item.setSelected(true);
            selectedItems.add(item);
            item.setSelectionIndex(selectedItems.size());
            notifyItemChanged(position);
        }

        if (listener != null) {
            listener.onSelectionChanged(selectedItems);
        }
    }

    public void clearSelection() {
        for (GalleryMediaItem it : selectedItems) {
            it.setSelected(false);
            it.setSelectionIndex(-1);
        }
        selectedItems.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedItems);
        }
    }

    public List<GalleryMediaItem> getSelectedItems() {
        return new ArrayList<>(selectedItems);
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
                    thumb = cr.loadThumbnail(uri, new Size(240, 240), null);
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

    static class MediaGridViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb;
        View viewSelectionOverlay;
        LinearLayout layoutVideoDuration;
        TextView tvDuration;
        FrameLayout layoutSelectionBadge;
        TextView tvSelectionNumber;

        public MediaGridViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumb = itemView.findViewById(R.id.ivGalleryThumb);
            viewSelectionOverlay = itemView.findViewById(R.id.viewSelectionOverlay);
            layoutVideoDuration = itemView.findViewById(R.id.layoutVideoDuration);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            layoutSelectionBadge = itemView.findViewById(R.id.layoutSelectionBadge);
            tvSelectionNumber = itemView.findViewById(R.id.tvSelectionNumber);
        }
    }
}
