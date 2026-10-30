package com.m3.veil.ui.chat.sheets;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;
import com.m3.veil.ui.chat.adapters.GalleryMediaAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in Media Gallery & Camera Picker BottomSheet matching WhatsApp, Telegram, and Signal.
 * Supports selecting single or multiple photos & videos with numbered badges (1, 2, 3, 4),
 * inline caption bar, and batch editing in MediaEditActivity.
 */
public class MediaGalleryBottomSheet extends BottomSheetDialogFragment {

    public interface MediaGalleryListener {
        void onMediaSelectedToSend(Uri mediaUri, String mediaType, String caption);
        void onMediaSelectedToEdit(Uri mediaUri, String mediaType);
        default void onMultiMediaSelectedToSend(ArrayList<Uri> uris, ArrayList<String> types, String caption) {
            if (uris != null && !uris.isEmpty()) {
                onMediaSelectedToSend(uris.get(0), types != null && !types.isEmpty() ? types.get(0) : "image", caption);
            }
        }
        default void onMultiMediaSelectedToEdit(ArrayList<Uri> uris, ArrayList<String> types) {
            if (uris != null && !uris.isEmpty()) {
                onMediaSelectedToEdit(uris.get(0), types != null && !types.isEmpty() ? types.get(0) : "image");
            }
        }
        void onCameraRequested();
    }

    private RecyclerView rvMediaGrid;
    private ProgressBar galleryProgressBar;
    private TextView tvEmptyGallery;
    private View bottomMediaActionContainer;
    private ImageView ivSelectedPreview;
    private ImageView btnEditSelectedMedia;
    private EditText etMediaCaption;
    private FrameLayout btnSendMedia;
    private ImageView btnCloseGallery;

    private GalleryMediaAdapter adapter;
    private final List<GalleryMediaItem> mediaItems = new ArrayList<>();
    private final List<GalleryMediaItem> currentSelectedBatch = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaGalleryListener listener;
    private GalleryMediaItem currentSelectedMedia = null;

    public void setMediaGalleryListener(MediaGalleryListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_media_gallery_picker, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvMediaGrid = view.findViewById(R.id.rvMediaGrid);
        galleryProgressBar = view.findViewById(R.id.galleryProgressBar);
        tvEmptyGallery = view.findViewById(R.id.tvEmptyGallery);
        bottomMediaActionContainer = view.findViewById(R.id.bottomMediaActionContainer);
        ivSelectedPreview = view.findViewById(R.id.ivSelectedPreview);
        btnEditSelectedMedia = view.findViewById(R.id.btnEditSelectedMedia);
        etMediaCaption = view.findViewById(R.id.etMediaCaption);
        btnSendMedia = view.findViewById(R.id.btnSendMedia);
        btnCloseGallery = view.findViewById(R.id.btnCloseGallery);

        btnCloseGallery.setOnClickListener(v -> dismiss());

        rvMediaGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        adapter = new GalleryMediaAdapter(mediaItems, new GalleryMediaAdapter.OnGalleryItemClickListener() {
            @Override
            public void onCameraTileClicked() {
                dismiss();
                if (listener != null) listener.onCameraRequested();
            }

            @Override
            public void onMediaItemSelected(GalleryMediaItem item) {
                currentSelectedMedia = item;
            }

            @Override
            public void onSingleMediaItemClicked(GalleryMediaItem item) {
                if (item != null && item.getUri() != null && listener != null) {
                    dismiss();
                    listener.onMediaSelectedToEdit(item.getUri(), item.isVideo() ? "video" : "image");
                }
            }

            @Override
            public void onMultiSelectionChanged(List<GalleryMediaItem> selectedItems) {
                currentSelectedBatch.clear();
                if (selectedItems != null) {
                    currentSelectedBatch.addAll(selectedItems);
                }
                if (!currentSelectedBatch.isEmpty()) {
                    GalleryMediaItem last = currentSelectedBatch.get(currentSelectedBatch.size() - 1);
                    currentSelectedMedia = last;
                    bottomMediaActionContainer.setVisibility(View.VISIBLE);
                    try {
                        ivSelectedPreview.setImageURI(last.getUri());
                    } catch (Exception ignored) {}
                    if (etMediaCaption != null) {
                        etMediaCaption.setHint(currentSelectedBatch.size() > 1
                                ? "Add a caption (" + currentSelectedBatch.size() + " selected)..."
                                : "Add a caption...");
                    }
                } else {
                    currentSelectedMedia = null;
                    bottomMediaActionContainer.setVisibility(View.GONE);
                }
            }
        });
        rvMediaGrid.setAdapter(adapter);

        // Edit / Crop button clicked (supports both single and multi-item batch edit)
        btnEditSelectedMedia.setOnClickListener(v -> {
            if (!currentSelectedBatch.isEmpty() && listener != null) {
                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<String> types = new ArrayList<>();
                for (GalleryMediaItem gi : currentSelectedBatch) {
                    uris.add(gi.getUri());
                    types.add(gi.isVideo() ? "video" : "image");
                }
                dismiss();
                if (uris.size() > 1) {
                    listener.onMultiMediaSelectedToEdit(uris, types);
                } else {
                    listener.onMediaSelectedToEdit(uris.get(0), types.get(0));
                }
            } else if (currentSelectedMedia != null && listener != null) {
                Uri uri = currentSelectedMedia.getUri();
                String type = currentSelectedMedia.isVideo() ? "video" : "image";
                dismiss();
                listener.onMediaSelectedToEdit(uri, type);
            }
        });

        // Direct Send button clicked (supports both single and multi-item album send)
        btnSendMedia.setOnClickListener(v -> {
            String caption = etMediaCaption.getText() != null ? etMediaCaption.getText().toString().trim() : "";
            if (!currentSelectedBatch.isEmpty() && listener != null) {
                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<String> types = new ArrayList<>();
                for (GalleryMediaItem gi : currentSelectedBatch) {
                    uris.add(gi.getUri());
                    types.add(gi.isVideo() ? "video" : "image");
                }
                dismiss();
                if (uris.size() > 1) {
                    listener.onMultiMediaSelectedToSend(uris, types, caption);
                } else {
                    listener.onMediaSelectedToSend(uris.get(0), types.get(0), caption);
                }
            } else if (currentSelectedMedia != null && listener != null) {
                Uri uri = currentSelectedMedia.getUri();
                String type = currentSelectedMedia.isVideo() ? "video" : "image";
                dismiss();
                listener.onMediaSelectedToSend(uri, type, caption);
            }
        });

        loadDeviceMedia();
    }

    private void loadDeviceMedia() {
        if (!hasStoragePermission()) {
            mediaItems.clear();
            mediaItems.add(GalleryMediaItem.createCameraTile());
            adapter.notifyDataSetChanged();
            tvEmptyGallery.setText("Storage permission required to browse gallery");
            tvEmptyGallery.setVisibility(View.VISIBLE);
            return;
        }

        galleryProgressBar.setVisibility(View.VISIBLE);
        tvEmptyGallery.setVisibility(View.GONE);

        executor.execute(() -> {
            List<GalleryMediaItem> queried = new ArrayList<>();
            queried.add(GalleryMediaItem.createCameraTile());

            ContentResolver cr = requireContext().getContentResolver();

            String[] imageProj = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_ADDED
            };
            try (Cursor c = cr.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProj,
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 60")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String path = dataCol != -1 ? c.getString(dataCol) : null;
                        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        queried.add(new GalleryMediaItem(id, uri, path, false, 0));
                    }
                }
            } catch (Exception ignored) {}

            String[] videoProj = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED
            };
            try (Cursor c = cr.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProj,
                    null,
                    null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC LIMIT 30")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA);
                    int durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String path = dataCol != -1 ? c.getString(dataCol) : null;
                        long dur = durCol != -1 ? c.getLong(durCol) : 0;
                        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                        queried.add(new GalleryMediaItem(id, uri, path, true, dur));
                    }
                }
            } catch (Exception ignored) {}

            mainHandler.post(() -> {
                galleryProgressBar.setVisibility(View.GONE);
                mediaItems.clear();
                mediaItems.addAll(queried);
                adapter.notifyDataSetChanged();
                if (mediaItems.size() <= 1) {
                    tvEmptyGallery.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }
}
