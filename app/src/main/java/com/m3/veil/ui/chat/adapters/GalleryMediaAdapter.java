package com.m3.veil.ui.chat.adapters;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;
import com.m3.veil.utils.VoicePlayerManager;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryMediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnGalleryItemClickListener {
        void onCameraTileClicked();
        void onMediaItemSelected(GalleryMediaItem item);
    }

    private final List<GalleryMediaItem> items;
    private final OnGalleryItemClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int selectedPosition = -1;

    public GalleryMediaAdapter(List<GalleryMediaItem> items, OnGalleryItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery_media, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MediaViewHolder vh = (MediaViewHolder) holder;
        GalleryMediaItem item = items.get(position);

        if (item.isCameraTile()) {
            vh.cardCameraTile.setVisibility(View.VISIBLE);
            vh.cardMediaContainer.setVisibility(View.GONE);
            vh.cardCameraTile.setOnClickListener(v -> {
                if (listener != null) listener.onCameraTileClicked();
            });
        } else {
            vh.cardCameraTile.setVisibility(View.GONE);
            vh.cardMediaContainer.setVisibility(View.VISIBLE);

            // Thumbnail loading
            vh.ivThumbnail.setImageBitmap(null);
            vh.ivThumbnail.setTag(item.getUri());

            loadThumbnailAsync(item, vh.ivThumbnail);

            // Video indicator
            if (item.isVideo()) {
                vh.videoDurationPill.setVisibility(View.VISIBLE);
                vh.tvVideoDuration.setText(VoicePlayerManager.formatDuration((int) item.getDurationMs()));
            } else {
                vh.videoDurationPill.setVisibility(View.GONE);
            }

            // Selection state
            boolean isSelected = item.isSelected();
            vh.selectedOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            vh.ivCheckmark.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            vh.selectionBadge.setBackgroundResource(isSelected ? R.drawable.bg_voice_circle_received : R.drawable.bg_circle_translucent);

            vh.cardMediaContainer.setOnClickListener(v -> {
                int previousPos = selectedPosition;
                if (selectedPosition != -1 && selectedPosition < items.size()) {
                    items.get(selectedPosition).setSelected(false);
                    notifyItemChanged(selectedPosition);
                }

                if (previousPos == vh.getAdapterPosition()) {
                    // Deselect
                    selectedPosition = -1;
                    if (listener != null) listener.onMediaItemSelected(null);
                } else {
                    selectedPosition = vh.getAdapterPosition();
                    item.setSelected(true);
                    notifyItemChanged(selectedPosition);
                    if (listener != null) listener.onMediaItemSelected(item);
                }
            });
        }
    }

    private void loadThumbnailAsync(GalleryMediaItem item, ImageView imageView) {
        final Uri uri = item.getUri();
        final ContentResolver cr = imageView.getContext().getContentResolver();

        executor.execute(() -> {
            Bitmap thumb = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thumb = cr.loadThumbnail(uri, new Size(200, 200), null);
                } else {
                    try (InputStream is = cr.openInputStream(uri)) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 4;
                        thumb = BitmapFactory.decodeStream(is, null, opts);
                    }
                }
            } catch (Exception ignored) {}

            final Bitmap result = thumb;
            mainHandler.post(() -> {
                if (uri.equals(imageView.getTag()) && result != null) {
                    imageView.setImageBitmap(result);
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public GalleryMediaItem getSelectedItem() {
        if (selectedPosition >= 0 && selectedPosition < items.size()) {
            return items.get(selectedPosition);
        }
        return null;
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        View cardCameraTile;
        View cardMediaContainer;
        ImageView ivThumbnail;
        View selectedOverlay;
        View videoDurationPill;
        TextView tvVideoDuration;
        View selectionBadge;
        ImageView ivCheckmark;

        public MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            cardCameraTile = itemView.findViewById(R.id.cardCameraTile);
            cardMediaContainer = itemView.findViewById(R.id.cardMediaContainer);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            selectedOverlay = itemView.findViewById(R.id.selectedOverlay);
            videoDurationPill = itemView.findViewById(R.id.videoDurationPill);
            tvVideoDuration = itemView.findViewById(R.id.tvVideoDuration);
            selectionBadge = itemView.findViewById(R.id.selectionBadge);
            ivCheckmark = itemView.findViewById(R.id.ivCheckmark);
        }
    }
}
