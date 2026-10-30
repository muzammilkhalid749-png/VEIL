package com.m3.veil.ui.chat.adapters;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

public class GalleryMediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public enum SelectionMode {
        SINGLE_SELECT,
        MULTI_SELECT
    }

    public interface OnGalleryItemClickListener {
        void onCameraTileClicked();
        void onMediaItemSelected(GalleryMediaItem item);
        default void onSingleMediaItemClicked(GalleryMediaItem item) {
            onMediaItemSelected(item);
        }
        default void onMultiSelectionChanged(List<GalleryMediaItem> selectedItems) {}
    }

    private final List<GalleryMediaItem> items;
    private final List<GalleryMediaItem> selectedItems = new ArrayList<>();
    private final OnGalleryItemClickListener listener;
    private SelectionMode selectionMode = SelectionMode.SINGLE_SELECT;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

            vh.ivThumbnail.setImageBitmap(null);
            vh.ivThumbnail.setTag(item.getUri());
            loadThumbnailAsync(item, vh.ivThumbnail);

            if (item.isVideo()) {
                vh.videoDurationPill.setVisibility(View.VISIBLE);
                vh.tvVideoDuration.setText(VoicePlayerManager.formatDuration((int) item.getDurationMs()));
            } else {
                vh.videoDurationPill.setVisibility(View.GONE);
            }

            int selIndex = indexOfSelected(item);
            boolean isSelected = selIndex >= 0;
            vh.selectedOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            vh.ivCheckmark.setVisibility(View.GONE);

            if (selectionMode == SelectionMode.MULTI_SELECT) {
                vh.selectionBadge.setVisibility(View.VISIBLE);
                if (isSelected) {
                    GradientDrawable greenCircle = new GradientDrawable();
                    greenCircle.setShape(GradientDrawable.OVAL);
                    greenCircle.setColor(Color.parseColor("#00A884"));
                    vh.selectionBadge.setBackground(greenCircle);
                    vh.tvSelectionNumber.setVisibility(View.VISIBLE);
                    vh.tvSelectionNumber.setText(String.valueOf(selIndex + 1));
                } else {
                    vh.selectionBadge.setBackgroundResource(R.drawable.bg_circle_translucent);
                    vh.tvSelectionNumber.setVisibility(View.GONE);
                }
            } else {
                vh.selectionBadge.setVisibility(View.GONE);
                vh.tvSelectionNumber.setVisibility(View.GONE);
            }

            // Single Tap: In SINGLE_SELECT, immediately pass URI to preview/editor; in MULTI_SELECT, toggle selection
            vh.cardMediaContainer.setOnClickListener(v -> {
                if (selectionMode == SelectionMode.SINGLE_SELECT && selectedItems.isEmpty()) {
                    if (listener != null) {
                        listener.onSingleMediaItemClicked(item);
                    }
                    return;
                }
                toggleMultiSelectItem(item);
            });

            // Long Press: Transition to MULTI_SELECT and select item
            vh.cardMediaContainer.setOnLongClickListener(v -> {
                selectionMode = SelectionMode.MULTI_SELECT;
                toggleMultiSelectItem(item);
                return true;
            });
        }
    }

    private void toggleMultiSelectItem(GalleryMediaItem item) {
        int existingIdx = indexOfSelected(item);
        if (existingIdx >= 0) {
            item.setSelected(false);
            selectedItems.remove(existingIdx);
            if (selectedItems.isEmpty()) {
                selectionMode = SelectionMode.SINGLE_SELECT;
            }
        } else {
            selectionMode = SelectionMode.MULTI_SELECT;
            item.setSelected(true);
            selectedItems.add(item);
        }
        notifyDataSetChanged();
        if (listener != null) {
            GalleryMediaItem latest = selectedItems.isEmpty() ? null : selectedItems.get(selectedItems.size() - 1);
            listener.onMediaItemSelected(latest);
            listener.onMultiSelectionChanged(new ArrayList<>(selectedItems));
        }
    }

    private int indexOfSelected(GalleryMediaItem target) {
        if (target == null || target.getUri() == null) return -1;
        for (int i = 0; i < selectedItems.size(); i++) {
            GalleryMediaItem s = selectedItems.get(i);
            if (s != null && target.getUri().equals(s.getUri())) {
                return i;
            }
        }
        return -1;
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
        return selectedItems.isEmpty() ? null : selectedItems.get(selectedItems.size() - 1);
    }

    public List<GalleryMediaItem> getSelectedItems() {
        return new ArrayList<>(selectedItems);
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
        TextView tvSelectionNumber;

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

            tvSelectionNumber = new TextView(itemView.getContext());
            tvSelectionNumber.setGravity(Gravity.CENTER);
            tvSelectionNumber.setTextColor(Color.WHITE);
            tvSelectionNumber.setTextSize(11f);
            tvSelectionNumber.setTypeface(null, Typeface.BOLD);
            tvSelectionNumber.setVisibility(View.GONE);
            if (selectionBadge instanceof FrameLayout) {
                ((FrameLayout) selectionBadge).addView(tvSelectionNumber, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER));
            }
        }
    }
}
