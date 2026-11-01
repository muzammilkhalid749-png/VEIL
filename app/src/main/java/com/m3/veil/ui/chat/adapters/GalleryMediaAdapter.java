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
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;
import com.m3.veil.utils.VoicePlayerManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private boolean alwaysShowSelectionRing = false;
    private int maxSelectionCount = 30;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<>(180);

    public GalleryMediaAdapter(List<GalleryMediaItem> items, OnGalleryItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setSelectionMode(SelectionMode mode) {
        this.selectionMode = mode != null ? mode : SelectionMode.SINGLE_SELECT;
        notifyDataSetChanged();
    }

    public SelectionMode getSelectionMode() {
        return selectionMode;
    }

    public void setAlwaysShowSelectionRing(boolean alwaysShow) {
        this.alwaysShowSelectionRing = alwaysShow;
        if (alwaysShow) {
            this.selectionMode = SelectionMode.MULTI_SELECT;
        }
        notifyDataSetChanged();
    }

    public boolean isAlwaysShowSelectionRing() {
        return alwaysShowSelectionRing;
    }

    public void setMaxSelectionCount(int maxCount) {
        this.maxSelectionCount = Math.max(1, maxCount);
    }

    public void clearSelection() {
        for (GalleryMediaItem item : selectedItems) {
            if (item != null) item.setSelected(false);
        }
        selectedItems.clear();
        if (!alwaysShowSelectionRing) {
            selectionMode = SelectionMode.SINGLE_SELECT;
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onMultiSelectionChanged(new ArrayList<>());
        }
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

            Uri uri = item.getUri();
            String cacheKey = uri != null ? uri.toString() : "";
            Bitmap cached = !cacheKey.isEmpty() ? THUMB_CACHE.get(cacheKey) : null;
            if (cached != null && !cached.isRecycled()) {
                vh.ivThumbnail.setImageBitmap(cached);
                vh.ivThumbnail.setTag(uri);
            } else {
                vh.ivThumbnail.setImageBitmap(null);
                vh.ivThumbnail.setTag(uri);
                loadThumbnailAsync(item, vh.ivThumbnail);
            }

            boolean isGif = item.getPath() != null && item.getPath().toLowerCase(Locale.ROOT).endsWith(".gif");
            if (item.isVideo()) {
                vh.videoDurationPill.setVisibility(View.VISIBLE);
                vh.tvVideoDuration.setText(VoicePlayerManager.formatDuration((int) item.getDurationMs()));
            } else if (isGif) {
                vh.videoDurationPill.setVisibility(View.VISIBLE);
                vh.tvVideoDuration.setText("GIF");
            } else {
                vh.videoDurationPill.setVisibility(View.GONE);
            }

            int selIndex = indexOfSelected(item);
            boolean isSelected = selIndex >= 0;
            vh.selectedOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            vh.ivCheckmark.setVisibility(View.GONE);

            boolean showRing = alwaysShowSelectionRing || selectionMode == SelectionMode.MULTI_SELECT;
            if (showRing) {
                vh.selectionBadge.setVisibility(View.VISIBLE);
                float dp = vh.itemView.getResources().getDisplayMetrics().density;
                if (isSelected) {
                    GradientDrawable accentCircle = new GradientDrawable();
                    accentCircle.setShape(GradientDrawable.OVAL);
                    accentCircle.setColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.veil_accent));
                    accentCircle.setStroke(Math.round(1.5f * dp), Color.WHITE);
                    vh.selectionBadge.setBackground(accentCircle);
                    vh.tvSelectionNumber.setVisibility(View.VISIBLE);
                    vh.tvSelectionNumber.setText(String.valueOf(selIndex + 1));
                } else {
                    GradientDrawable emptyRing = new GradientDrawable();
                    emptyRing.setShape(GradientDrawable.OVAL);
                    emptyRing.setColor(Color.parseColor("#33000000"));
                    emptyRing.setStroke(Math.round(2f * dp), Color.WHITE);
                    vh.selectionBadge.setBackground(emptyRing);
                    vh.tvSelectionNumber.setVisibility(View.GONE);
                }
            } else {
                vh.selectionBadge.setVisibility(View.GONE);
                vh.tvSelectionNumber.setVisibility(View.GONE);
            }

            vh.cardMediaContainer.setOnClickListener(v -> {
                if (!alwaysShowSelectionRing && selectionMode == SelectionMode.SINGLE_SELECT && selectedItems.isEmpty()) {
                    if (listener != null) {
                        listener.onSingleMediaItemClicked(item);
                    }
                    return;
                }
                toggleMultiSelectItem(v, item);
            });

            vh.cardMediaContainer.setOnLongClickListener(v -> {
                selectionMode = SelectionMode.MULTI_SELECT;
                toggleMultiSelectItem(v, item);
                return true;
            });
        }
    }

    private void toggleMultiSelectItem(View anchor, GalleryMediaItem item) {
        int existingIdx = indexOfSelected(item);
        if (existingIdx >= 0) {
            item.setSelected(false);
            selectedItems.remove(existingIdx);
            if (selectedItems.isEmpty() && !alwaysShowSelectionRing) {
                selectionMode = SelectionMode.SINGLE_SELECT;
            }
        } else {
            if (selectedItems.size() >= maxSelectionCount) {
                if (anchor != null && anchor.getContext() != null) {
                    Toast.makeText(anchor.getContext(),
                            "You can select up to " + maxSelectionCount + " items",
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }
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

    public static void loadThumbnailInto(GalleryMediaItem item, ImageView imageView) {
        if (item == null || item.getUri() == null || imageView == null) return;
        Uri uri = item.getUri();
        Bitmap cached = THUMB_CACHE.get(uri.toString());
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }
        ContentResolver cr = imageView.getContext().getContentResolver();
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap thumb = decodeMediaThumb(cr, uri);
            if (thumb != null) {
                THUMB_CACHE.put(uri.toString(), thumb);
                new Handler(Looper.getMainLooper()).post(() -> imageView.setImageBitmap(thumb));
            }
        });
    }

    private void loadThumbnailAsync(GalleryMediaItem item, ImageView imageView) {
        final Uri uri = item.getUri();
        if (uri == null) return;
        final ContentResolver cr = imageView.getContext().getContentResolver();

        executor.execute(() -> {
            Bitmap thumb = decodeMediaThumb(cr, uri);
            if (thumb != null) {
                THUMB_CACHE.put(uri.toString(), thumb);
            }
            final Bitmap result = thumb;
            mainHandler.post(() -> {
                if (uri.equals(imageView.getTag()) && result != null) {
                    imageView.setImageBitmap(result);
                }
            });
        });
    }

    private static Bitmap decodeMediaThumb(ContentResolver cr, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return cr.loadThumbnail(uri, new Size(240, 240), null);
            } else {
                try (InputStream is = cr.openInputStream(uri)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    return BitmapFactory.decodeStream(is, null, opts);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
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
            tvSelectionNumber.setTextSize(11.5f);
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
