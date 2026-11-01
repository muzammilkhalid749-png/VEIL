package com.m3.veil.ui.chat.sheets;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.m3.veil.R;
import com.m3.veil.model.GalleryMediaItem;
import com.m3.veil.ui.chat.adapters.GalleryMediaAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in Media Gallery & Album Picker BottomSheet matching WhatsApp, Telegram, and Signal.
 * Supports:
 * - Album Selector Dropdown ("Recents ▼" -> Recents, Videos, WhatsApp, Camera, Screenshots, More apps -> Files/Drive/Photos/File Manager, See more)
 * - Direct system intent bridge (SystemPickerBridgeFragment) for Files, Drive, Photos, File Manager, and Music/Audio selection
 * - Single & Multi-selection with numbered badges (1, 2, 3...)
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

    public interface OnSystemMediaPickedCallback {
        void onMediaPicked(ArrayList<Uri> uris, ArrayList<String> types);
    }

    public static class AlbumBucket {
        public final String id;
        public final String name;
        public final List<GalleryMediaItem> items = new ArrayList<>();

        public AlbumBucket(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * Headless Fragment that bridges system intents (Files, Drive, Photos, File Manager, Audio Picker)
     * and runtime permission requests from any Context or Dialog (such as WhatsAppStatusUi).
     */
    public static class SystemPickerBridgeFragment extends Fragment {
        private static final String TAG = "VeilSystemPickerBridge";
        private OnSystemMediaPickedCallback pendingMediaCallback;
        private Runnable pendingPermissionCallback;
        private Intent pendingLaunchIntent;
        private String[] pendingPermArray;

        private final ActivityResultLauncher<Intent> fileLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    OnSystemMediaPickedCallback cb = pendingMediaCallback;
                    pendingMediaCallback = null;
                    if (cb == null || getContext() == null) return;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        ArrayList<Uri> uris = new ArrayList<>();
                        ArrayList<String> types = new ArrayList<>();
                        ContentResolver cr = requireContext().getContentResolver();

                        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                                Uri u = data.getClipData().getItemAt(i).getUri();
                                if (u != null) {
                                    takeReadPermissionSafely(cr, data, u);
                                    String detectedType = detectMediaType(cr, u);
                                    Uri safeUri = copyContentUriToLocalFileIfNeeded(requireContext(), cr, u, detectedType);
                                    uris.add(safeUri != null ? safeUri : u);
                                    types.add(detectedType);
                                }
                            }
                        } else if (data.getData() != null) {
                            Uri u = data.getData();
                            takeReadPermissionSafely(cr, data, u);
                            String detectedType = detectMediaType(cr, u);
                            Uri safeUri = copyContentUriToLocalFileIfNeeded(requireContext(), cr, u, detectedType);
                            uris.add(safeUri != null ? safeUri : u);
                            types.add(detectedType);
                        }
                        if (!uris.isEmpty()) {
                            cb.onMediaPicked(uris, types);
                        }
                    }
                });

        private final ActivityResultLauncher<String[]> permLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    Runnable cb = pendingPermissionCallback;
                    pendingPermissionCallback = null;
                    if (cb != null) cb.run();
                });

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (pendingLaunchIntent != null) {
                Intent toLaunch = pendingLaunchIntent;
                pendingLaunchIntent = null;
                safeLaunchIntent(toLaunch);
            }
            if (pendingPermArray != null) {
                String[] perms = pendingPermArray;
                pendingPermArray = null;
                try {
                    permLauncher.launch(perms);
                } catch (Exception ignored) {}
            }
        }

        public void launchPickerIntent(Intent intent, OnSystemMediaPickedCallback callback) {
            this.pendingMediaCallback = callback;
            if (isAdded()) {
                safeLaunchIntent(intent);
            } else {
                this.pendingLaunchIntent = intent;
            }
        }

        public void launchPermissionRequest(String[] perms, Runnable callback) {
            this.pendingPermissionCallback = callback;
            if (isAdded()) {
                try {
                    permLauncher.launch(perms);
                } catch (Exception ignored) {
                    if (callback != null) callback.run();
                }
            } else {
                this.pendingPermArray = perms;
            }
        }

        private void safeLaunchIntent(Intent primary) {
            try {
                fileLauncher.launch(primary);
            } catch (Exception e) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.setType("*/*");
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fileLauncher.launch(Intent.createChooser(fallback, "Select File"));
                } catch (Exception ignored) {}
            }
        }

        private static void takeReadPermissionSafely(ContentResolver cr, Intent data, Uri uri) {
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (flags != 0) {
                    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Throwable ignored) {}
        }

        public static String detectMediaType(ContentResolver cr, Uri uri) {
            try {
                String mime = cr.getType(uri);
                if (mime != null) {
                    if (mime.startsWith("video")) return "video";
                    if (mime.startsWith("audio")) return "audio";
                    if (mime.startsWith("image")) return "image";
                }
                String s = uri.toString().toLowerCase();
                if (s.endsWith(".mp4") || s.endsWith(".mkv") || s.endsWith(".webm") || s.endsWith(".mov") || s.endsWith(".3gp") || s.endsWith(".avi")) return "video";
                if (s.endsWith(".mp3") || s.endsWith(".m4a") || s.endsWith(".wav") || s.endsWith(".ogg") || s.endsWith(".aac") || s.endsWith(".flac")) return "audio";
            } catch (Throwable ignored) {}
            return "image";
        }

        public static Uri copyContentUriToLocalFileIfNeeded(Context ctx, ContentResolver cr, Uri uri, String mediaType) {
            if (ctx == null || cr == null || uri == null) return uri;
            if ("file".equalsIgnoreCase(uri.getScheme())) return uri;
            try {
                String ext = ".jpg";
                if ("video".equalsIgnoreCase(mediaType)) ext = ".mp4";
                else if ("audio".equalsIgnoreCase(mediaType)) ext = ".mp3";
                java.io.File baseDir = ctx.getExternalCacheDir() != null ? ctx.getExternalCacheDir() : ctx.getCacheDir();
                java.io.File dir = new java.io.File(baseDir, "picked_media");
                if (!dir.exists()) dir.mkdirs();
                java.io.File out = new java.io.File(dir, "picked_" + System.currentTimeMillis() + "_" + Math.abs(uri.hashCode()) + ext);
                try (java.io.InputStream in = cr.openInputStream(uri);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    if (in == null) return uri;
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                }
                if (out.exists() && out.length() > 0) {
                    return Uri.fromFile(out);
                }
            } catch (Throwable ignored) {}
            return uri;
        }
    }

    @Nullable
    public static FragmentActivity unwrapFragmentActivity(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof FragmentActivity) {
                return (FragmentActivity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static SystemPickerBridgeFragment getOrAttachBridge(FragmentActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        Fragment existing = fm.findFragmentByTag(SystemPickerBridgeFragment.TAG);
        if (existing instanceof SystemPickerBridgeFragment) {
            return (SystemPickerBridgeFragment) existing;
        }
        SystemPickerBridgeFragment bridge = new SystemPickerBridgeFragment();
        fm.beginTransaction().add(bridge, SystemPickerBridgeFragment.TAG).commitNowAllowingStateLoss();
        return bridge;
    }

    /**
     * Launches the requested external source ("Files", "Drive", "Photos", "File Manager", or "Music")
     * from any Context and returns the selected URIs via callback.
     */
    public static void launchExternalSourcePicker(Context ctx, String appType, boolean allowMultiple,
                                                  OnSystemMediaPickedCallback callback) {
        FragmentActivity activity = unwrapFragmentActivity(ctx);
        if (activity == null) return;
        Intent intent = buildExternalAppIntent(activity, appType, allowMultiple);
        SystemPickerBridgeFragment bridge = getOrAttachBridge(activity);
        bridge.launchPickerIntent(intent, callback);
    }

    /**
     * Requests runtime media/storage permissions from any Context and runs onResult when finished.
     */
    public static void requestPermissionsFromContext(Context ctx, boolean includeAudio, Runnable onResult) {
        FragmentActivity activity = unwrapFragmentActivity(ctx);
        if (activity == null) {
            if (onResult != null) onResult.run();
            return;
        }
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            perms = includeAudio
                    ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
                    : new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = includeAudio
                    ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO}
                    : new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        } else {
            perms = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        SystemPickerBridgeFragment bridge = getOrAttachBridge(activity);
        bridge.launchPermissionRequest(perms, onResult);
    }

    /**
     * Requests runtime RECORD_AUDIO permission from any Context and runs onResult when finished.
     */
    public static void requestRecordAudioPermission(Context ctx, Runnable onResult) {
        FragmentActivity activity = unwrapFragmentActivity(ctx);
        if (activity == null) {
            if (onResult != null) onResult.run();
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (onResult != null) onResult.run();
            return;
        }
        SystemPickerBridgeFragment bridge = getOrAttachBridge(activity);
        bridge.launchPermissionRequest(new String[]{Manifest.permission.RECORD_AUDIO}, onResult);
    }

    public static Intent buildExternalAppIntent(Context ctx, String appType, boolean allowMultiple) {
        if ("Music".equalsIgnoreCase(appType) || "Audio".equalsIgnoreCase(appType)) {
            Intent getAudio = new Intent(Intent.ACTION_GET_CONTENT);
            getAudio.addCategory(Intent.CATEGORY_OPENABLE);
            getAudio.setType("audio/*");
            getAudio.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            getAudio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return Intent.createChooser(getAudio, "Select Music or Audio File");
        }

        if ("Photos".equalsIgnoreCase(appType)) {
            Intent pickIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pickIntent.setType("*/*");
            pickIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            pickIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
            pickIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return pickIntent;
        } else if ("File Manager".equalsIgnoreCase(appType)) {
            Intent fmIntent = new Intent(Intent.ACTION_GET_CONTENT);
            fmIntent.addCategory(Intent.CATEGORY_OPENABLE);
            fmIntent.setType("*/*");
            fmIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            fmIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
            fmIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return Intent.createChooser(fmIntent, "Open File Manager");
        }

        // Default ("Files" or fallback): Android Storage Access Framework Document Picker
        Intent docIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        docIntent.addCategory(Intent.CATEGORY_OPENABLE);
        docIntent.setType("*/*");
        docIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        docIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        docIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return docIntent;
    }

    private RecyclerView rvMediaGrid;
    private ProgressBar galleryProgressBar;
    private View layoutGalleryEmptyOrPermission;
    private ImageView ivGalleryEmptyIcon;
    private TextView tvGalleryEmptyTitle;
    private TextView tvGalleryEmptySubtitle;
    private MaterialButton btnGrantGalleryPermission;
    private View bottomMediaActionContainer;
    private ImageView ivSelectedPreview;
    private ImageView btnEditSelectedMedia;
    private EditText etMediaCaption;
    private FrameLayout btnSendMedia;
    private ImageView btnCloseGallery;

    private View layoutGalleryAlbumSelector;
    private TextView tvGalleryTitle;
    private ImageView ivGalleryAlbumChevron;
    private View viewGalleryAlbumScrim;
    private View cardGalleryAlbumDropdown;
    private LinearLayout llGalleryAlbumDropdownContainer;
    private View fabGalleryMultiOrFolder;

    private GalleryMediaAdapter adapter;
    private final List<GalleryMediaItem> allMediaItems = new ArrayList<>();
    private final List<GalleryMediaItem> displayedMediaItems = new ArrayList<>();
    private final List<GalleryMediaItem> currentSelectedBatch = new ArrayList<>();
    private final Map<String, AlbumBucket> albumBuckets = new LinkedHashMap<>();
    private String activeBucketId = "ALL";
    private boolean showingAllAlbumsInDropdown = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaGalleryListener listener;
    private GalleryMediaItem currentSelectedMedia = null;

    private final ActivityResultLauncher<String[]> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean anyGranted = false;
                if (result != null) {
                    for (Boolean granted : result.values()) {
                        if (Boolean.TRUE.equals(granted)) {
                            anyGranted = true;
                            break;
                        }
                    }
                }
                if (anyGranted || hasStoragePermission()) {
                    loadDeviceMedia();
                } else {
                    showPermissionRequiredState();
                }
            });

    private final ActivityResultLauncher<Intent> systemFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && listener != null) {
                    Intent data = result.getData();
                    ContentResolver cr = requireContext().getContentResolver();
                    if (data.getClipData() != null && data.getClipData().getItemCount() > 1) {
                        ArrayList<Uri> uris = new ArrayList<>();
                        ArrayList<String> types = new ArrayList<>();
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            Uri u = data.getClipData().getItemAt(i).getUri();
                            if (u != null) {
                                String t = SystemPickerBridgeFragment.detectMediaType(cr, u);
                                Uri safeUri = SystemPickerBridgeFragment.copyContentUriToLocalFileIfNeeded(requireContext(), cr, u, t);
                                uris.add(safeUri != null ? safeUri : u);
                                types.add(t);
                            }
                        }
                        if (!uris.isEmpty()) {
                            dismiss();
                            listener.onMultiMediaSelectedToEdit(uris, types);
                        }
                    } else if (data.getData() != null) {
                        Uri u = data.getData();
                        String type = SystemPickerBridgeFragment.detectMediaType(cr, u);
                        Uri safeUri = SystemPickerBridgeFragment.copyContentUriToLocalFileIfNeeded(requireContext(), cr, u, type);
                        dismiss();
                        listener.onMediaSelectedToEdit(safeUri != null ? safeUri : u, type);
                    }
                }
            });

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
                ViewGroup.LayoutParams lp = bottomSheet.getLayoutParams();
                lp.height = (int) (requireContext().getResources().getDisplayMetrics().heightPixels * 0.92f);
                bottomSheet.setLayoutParams(lp);
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
        layoutGalleryEmptyOrPermission = view.findViewById(R.id.layoutGalleryEmptyOrPermission);
        ivGalleryEmptyIcon = view.findViewById(R.id.ivGalleryEmptyIcon);
        tvGalleryEmptyTitle = view.findViewById(R.id.tvGalleryEmptyTitle);
        tvGalleryEmptySubtitle = view.findViewById(R.id.tvGalleryEmptySubtitle);
        btnGrantGalleryPermission = view.findViewById(R.id.btnGrantGalleryPermission);
        bottomMediaActionContainer = view.findViewById(R.id.bottomMediaActionContainer);
        ivSelectedPreview = view.findViewById(R.id.ivSelectedPreview);
        btnEditSelectedMedia = view.findViewById(R.id.btnEditSelectedMedia);
        etMediaCaption = view.findViewById(R.id.etMediaCaption);
        btnSendMedia = view.findViewById(R.id.btnSendMedia);
        btnCloseGallery = view.findViewById(R.id.btnCloseGallery);

        layoutGalleryAlbumSelector = view.findViewById(R.id.layoutGalleryAlbumSelector);
        tvGalleryTitle = view.findViewById(R.id.tvGalleryTitle);
        ivGalleryAlbumChevron = view.findViewById(R.id.ivGalleryAlbumChevron);
        viewGalleryAlbumScrim = view.findViewById(R.id.viewGalleryAlbumScrim);
        cardGalleryAlbumDropdown = view.findViewById(R.id.cardGalleryAlbumDropdown);
        llGalleryAlbumDropdownContainer = view.findViewById(R.id.llGalleryAlbumDropdownContainer);
        fabGalleryMultiOrFolder = view.findViewById(R.id.fabGalleryMultiOrFolder);

        btnCloseGallery.setOnClickListener(v -> dismiss());

        if (layoutGalleryAlbumSelector != null) {
            layoutGalleryAlbumSelector.setOnClickListener(v -> toggleAlbumDropdown(false));
        }
        if (viewGalleryAlbumScrim != null) {
            viewGalleryAlbumScrim.setOnClickListener(v -> hideAlbumDropdown());
        }
        if (fabGalleryMultiOrFolder != null) {
            fabGalleryMultiOrFolder.setOnClickListener(v -> {
                hideAlbumDropdown();
                launchExternalAppPicker("File Manager");
            });
            fabGalleryMultiOrFolder.setOnLongClickListener(v -> {
                boolean ringShown = !adapter.isAlwaysShowSelectionRing();
                adapter.setAlwaysShowSelectionRing(ringShown);
                Toast.makeText(requireContext(),
                        ringShown ? "Multi-select enabled" : "Single-tap mode",
                        Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        rvMediaGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        adapter = new GalleryMediaAdapter(displayedMediaItems, new GalleryMediaAdapter.OnGalleryItemClickListener() {
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
                    GalleryMediaAdapter.loadThumbnailInto(last, ivSelectedPreview);
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

        if (hasStoragePermission()) {
            loadDeviceMedia();
        } else {
            showPermissionRequiredState();
            requestStoragePermissions();
        }
    }

    private void toggleAlbumDropdown(boolean showMoreAppsPage) {
        if (cardGalleryAlbumDropdown == null || llGalleryAlbumDropdownContainer == null) return;
        if (cardGalleryAlbumDropdown.getVisibility() == View.VISIBLE && !showMoreAppsPage) {
            hideAlbumDropdown();
            return;
        }
        populateAlbumDropdown(showMoreAppsPage);
        cardGalleryAlbumDropdown.setVisibility(View.VISIBLE);
        if (viewGalleryAlbumScrim != null) viewGalleryAlbumScrim.setVisibility(View.VISIBLE);
        if (ivGalleryAlbumChevron != null) ivGalleryAlbumChevron.animate().rotation(180f).setDuration(180).start();
    }

    private void hideAlbumDropdown() {
        if (cardGalleryAlbumDropdown != null) cardGalleryAlbumDropdown.setVisibility(View.GONE);
        if (viewGalleryAlbumScrim != null) viewGalleryAlbumScrim.setVisibility(View.GONE);
        if (ivGalleryAlbumChevron != null) ivGalleryAlbumChevron.animate().rotation(0f).setDuration(180).start();
    }

    private void populateAlbumDropdown(boolean showMoreAppsPage) {
        if (getContext() == null || llGalleryAlbumDropdownContainer == null) return;
        llGalleryAlbumDropdownContainer.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        if (showMoreAppsPage) {
            // External apps: Files, Photos, File Manager (Google Drive removed per user request)
            String[][] externalApps = {
                    {"Files", "#3B82F6"},
                    {"Photos", "#F59E0B"},
                    {"File Manager", "#0EA5E9"}
            };
            for (String[] app : externalApps) {
                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(16 * dp), Math.round(12 * dp));
                android.util.TypedValue tv = new android.util.TypedValue();
                if (requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    row.setBackgroundResource(tv.resourceId);
                }

                FrameLayout iconCircle = new FrameLayout(requireContext());
                iconCircle.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * dp), Math.round(42 * dp)));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(12 * dp);
                bg.setColor(Color.parseColor(app[1]));
                iconCircle.setBackground(bg);

                ImageView iv = new ImageView(requireContext());
                iv.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
                iv.setImageResource("Photos".equals(app[0]) ? R.drawable.ic_image : R.drawable.ic_folder_multi);
                iv.setColorFilter(Color.WHITE);
                iconCircle.addView(iv);
                row.addView(iconCircle);

                TextView tvName = new TextView(requireContext());
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                nlp.leftMargin = Math.round(14 * dp);
                tvName.setLayoutParams(nlp);
                tvName.setText(app[0]);
                tvName.setTextSize(15.5f);
                tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
                row.addView(tvName);

                final String selectedApp = app[0];
                row.setOnClickListener(v -> {
                    hideAlbumDropdown();
                    launchExternalAppPicker(selectedApp);
                });
                llGalleryAlbumDropdownContainer.addView(row);
            }
            return;
        }

        // Screenshot 4.jpg: Recents, Videos, WhatsApp/Device Albums, More apps, See more
        List<AlbumBucket> bucketsList = new ArrayList<>(albumBuckets.values());
        int limit = showingAllAlbumsInDropdown ? bucketsList.size() : Math.min(bucketsList.size(), 5);
        for (int i = 0; i < limit; i++) {
            AlbumBucket bucket = bucketsList.get(i);
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
            android.util.TypedValue tv = new android.util.TypedValue();
            if (requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                row.setBackgroundResource(tv.resourceId);
            }

            MaterialCardView thumbCard = new MaterialCardView(requireContext());
            thumbCard.setLayoutParams(new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp)));
            thumbCard.setRadius(10 * dp);
            thumbCard.setCardElevation(0f);
            thumbCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorSurfaceVariant));

            ImageView ivThumb = new ImageView(requireContext());
            ivThumb.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (!bucket.items.isEmpty()) {
                GalleryMediaAdapter.loadThumbnailInto(bucket.items.get(0), ivThumb);
            }
            thumbCard.addView(ivThumb);
            row.addView(thumbCard);

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Math.round(14 * dp);
            textCol.setLayoutParams(tlp);

            TextView tvAlbumName = new TextView(requireContext());
            tvAlbumName.setText(bucket.name);
            tvAlbumName.setTextSize(15.5f);
            tvAlbumName.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvAlbumName.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
            textCol.addView(tvAlbumName);

            TextView tvCount = new TextView(requireContext());
            tvCount.setText(bucket.items.size() + (bucket.items.size() == 1 ? " item" : " items"));
            tvCount.setTextSize(12.5f);
            tvCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
            textCol.addView(tvCount);

            row.addView(textCol);

            if (bucket.id.equals(activeBucketId)) {
                ImageView ivCheck = new ImageView(requireContext());
                ivCheck.setLayoutParams(new LinearLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp)));
                ivCheck.setImageResource(R.drawable.ic_check);
                ivCheck.setColorFilter(ContextCompat.getColor(requireContext(), R.color.veil_accent));
                row.addView(ivCheck);
            }

            row.setOnClickListener(v -> {
                activeBucketId = bucket.id;
                if (tvGalleryTitle != null) tvGalleryTitle.setText(bucket.name);
                hideAlbumDropdown();
                applyBucketFilter();
            });
            llGalleryAlbumDropdownContainer.addView(row);
        }

        // "More apps" row (Files, +3 -> opens Screenshot 5.jpg submenu)
        LinearLayout moreAppsRow = new LinearLayout(requireContext());
        moreAppsRow.setOrientation(LinearLayout.HORIZONTAL);
        moreAppsRow.setGravity(Gravity.CENTER_VERTICAL);
        moreAppsRow.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            moreAppsRow.setBackgroundResource(tv.resourceId);
        }

        FrameLayout moreIconBox = new FrameLayout(requireContext());
        moreIconBox.setLayoutParams(new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp)));
        GradientDrawable mBg = new GradientDrawable();
        mBg.setCornerRadius(10 * dp);
        mBg.setColor(ContextCompat.getColor(requireContext(), R.color.colorSurfaceVariant));
        moreIconBox.setBackground(mBg);
        ImageView ivMoreFolder = new ImageView(requireContext());
        ivMoreFolder.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
        ivMoreFolder.setImageResource(R.drawable.ic_folder_multi);
        ivMoreFolder.setColorFilter(ContextCompat.getColor(requireContext(), R.color.veil_accent));
        moreIconBox.addView(ivMoreFolder);
        moreAppsRow.addView(moreIconBox);

        LinearLayout moreCol = new LinearLayout(requireContext());
        moreCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mclp.leftMargin = Math.round(14 * dp);
        moreCol.setLayoutParams(mclp);
        TextView tvMoreTitle = new TextView(requireContext());
        tvMoreTitle.setText("More apps");
        tvMoreTitle.setTextSize(15.5f);
        tvMoreTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvMoreTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
        moreCol.addView(tvMoreTitle);
        TextView tvMoreSub = new TextView(requireContext());
        tvMoreSub.setText("Files, +2");
        tvMoreSub.setTextSize(12.5f);
        tvMoreSub.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
        moreCol.addView(tvMoreSub);
        moreAppsRow.addView(moreCol);

        ImageView ivArrow = new ImageView(requireContext());
        ivArrow.setLayoutParams(new LinearLayout.LayoutParams(Math.round(18 * dp), Math.round(18 * dp)));
        ivArrow.setImageResource(R.drawable.ic_chevron_right);
        ivArrow.setColorFilter(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
        moreAppsRow.addView(ivArrow);

        moreAppsRow.setOnClickListener(v -> populateAlbumDropdown(true));
        llGalleryAlbumDropdownContainer.addView(moreAppsRow);

        // "See more" row
        if (!showingAllAlbumsInDropdown && bucketsList.size() > 5) {
            LinearLayout seeMoreRow = new LinearLayout(requireContext());
            seeMoreRow.setOrientation(LinearLayout.HORIZONTAL);
            seeMoreRow.setGravity(Gravity.CENTER_VERTICAL);
            seeMoreRow.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
            if (tv.resourceId != 0) seeMoreRow.setBackgroundResource(tv.resourceId);

            FrameLayout seeBox = new FrameLayout(requireContext());
            seeBox.setLayoutParams(new LinearLayout.LayoutParams(Math.round(46 * dp), Math.round(46 * dp)));
            seeBox.setBackground(mBg);
            ImageView ivSee = new ImageView(requireContext());
            ivSee.setLayoutParams(new FrameLayout.LayoutParams(Math.round(22 * dp), Math.round(22 * dp), Gravity.CENTER));
            ivSee.setImageResource(R.drawable.ic_folder_multi);
            ivSee.setColorFilter(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
            seeBox.addView(ivSee);
            seeMoreRow.addView(seeBox);

            TextView tvSee = new TextView(requireContext());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            slp.leftMargin = Math.round(14 * dp);
            tvSee.setLayoutParams(slp);
            tvSee.setText("See more");
            tvSee.setTextSize(15.5f);
            tvSee.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvSee.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
            seeMoreRow.addView(tvSee);

            seeMoreRow.setOnClickListener(v -> {
                showingAllAlbumsInDropdown = true;
                populateAlbumDropdown(false);
            });
            llGalleryAlbumDropdownContainer.addView(seeMoreRow);
        }
    }

    private void launchExternalAppPicker(String appType) {
        if (getContext() == null) return;
        try {
            Intent intent = buildExternalAppIntent(requireContext(), appType, true);
            systemFilePickerLauncher.launch(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("*/*");
                fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                systemFilePickerLauncher.launch(Intent.createChooser(fallback, "Select Photos or Videos"));
            } catch (Exception ignored) {}
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            storagePermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            });
        } else {
            storagePermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            });
        }
    }

    private void showPermissionRequiredState() {
        if (layoutGalleryEmptyOrPermission != null) layoutGalleryEmptyOrPermission.setVisibility(View.VISIBLE);
        if (rvMediaGrid != null) rvMediaGrid.setVisibility(View.GONE);
        if (ivGalleryEmptyIcon != null) ivGalleryEmptyIcon.setImageResource(R.drawable.ic_camera);
        if (tvGalleryEmptyTitle != null) tvGalleryEmptyTitle.setText("Allow Access to Media");
        if (tvGalleryEmptySubtitle != null) {
            tvGalleryEmptySubtitle.setText("To share photos and videos in your status or chat, grant VEIL access to your media library.");
        }
        if (btnGrantGalleryPermission != null) {
            btnGrantGalleryPermission.setText("Grant Access");
            btnGrantGalleryPermission.setVisibility(View.VISIBLE);
            btnGrantGalleryPermission.setOnClickListener(v -> requestStoragePermissions());
        }
    }

    private void showEmptyMediaState() {
        if (layoutGalleryEmptyOrPermission != null) layoutGalleryEmptyOrPermission.setVisibility(View.VISIBLE);
        if (rvMediaGrid != null) rvMediaGrid.setVisibility(View.GONE);
        if (ivGalleryEmptyIcon != null) ivGalleryEmptyIcon.setImageResource(R.drawable.ic_camera);
        if (tvGalleryEmptyTitle != null) tvGalleryEmptyTitle.setText("No Photos or Videos");
        if (tvGalleryEmptySubtitle != null) {
            tvGalleryEmptySubtitle.setText("Take a photo, record a video, or browse files to get started.");
        }
        if (btnGrantGalleryPermission != null) {
            btnGrantGalleryPermission.setText("Browse Files");
            btnGrantGalleryPermission.setVisibility(View.VISIBLE);
            btnGrantGalleryPermission.setOnClickListener(v -> launchExternalAppPicker("Files"));
        }
    }

    private void loadDeviceMedia() {
        if (getContext() == null) return;
        if (!hasStoragePermission()) {
            showPermissionRequiredState();
            return;
        }

        if (layoutGalleryEmptyOrPermission != null) layoutGalleryEmptyOrPermission.setVisibility(View.GONE);
        if (rvMediaGrid != null) rvMediaGrid.setVisibility(View.VISIBLE);
        if (galleryProgressBar != null) galleryProgressBar.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            if (getContext() == null) return;
            List<GalleryMediaItem> queried = queryAllDeviceMedia(requireContext().getContentResolver(), false);
            Map<String, AlbumBucket> builtBuckets = buildAlbumBuckets(queried);

            mainHandler.post(() -> {
                if (getContext() == null || isDetached()) return;
                if (galleryProgressBar != null) galleryProgressBar.setVisibility(View.GONE);
                allMediaItems.clear();
                allMediaItems.addAll(queried);
                albumBuckets.clear();
                albumBuckets.putAll(builtBuckets);
                applyBucketFilter();
            });
        });
    }

    private void applyBucketFilter() {
        displayedMediaItems.clear();
        displayedMediaItems.add(GalleryMediaItem.createCameraTile());
        AlbumBucket bucket = albumBuckets.get(activeBucketId);
        if (bucket != null) {
            displayedMediaItems.addAll(bucket.items);
        } else {
            displayedMediaItems.addAll(allMediaItems);
        }
        adapter.notifyDataSetChanged();

        if (allMediaItems.isEmpty()) {
            showEmptyMediaState();
        } else {
            if (layoutGalleryEmptyOrPermission != null) layoutGalleryEmptyOrPermission.setVisibility(View.GONE);
            if (rvMediaGrid != null) rvMediaGrid.setVisibility(View.VISIBLE);
        }
    }

    public static List<GalleryMediaItem> queryAllDeviceMedia(ContentResolver cr, boolean imagesOnly) {
        List<GalleryMediaItem> queried = new ArrayList<>();
        if (cr == null) return queried;

        String[] imageProj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
        };
        try (Cursor c = cr.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProj,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
                int bIdCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                int bNameCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
                int count = 0;
                while (c.moveToNext() && count < 250) {
                    long id = c.getLong(idCol);
                    String path = dataCol != -1 ? c.getString(dataCol) : null;
                    String bId = bIdCol != -1 ? c.getString(bIdCol) : "default";
                    String bName = bNameCol != -1 ? c.getString(bNameCol) : "Photos";
                    long dateAdded = dateCol != -1 ? c.getLong(dateCol) : 0L;
                    Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    queried.add(new GalleryMediaItem(id, uri, path, false, 0, bId, bName, dateAdded));
                    count++;
                }
            }
        } catch (Exception ignored) {}

        if (!imagesOnly) {
            String[] videoProj = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED
            };
            try (Cursor c = cr.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProj,
                    null,
                    null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA);
                    int durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int bIdCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                    int bNameCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    int dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED);
                    int count = 0;
                    while (c.moveToNext() && count < 100) {
                        long id = c.getLong(idCol);
                        String path = dataCol != -1 ? c.getString(dataCol) : null;
                        long dur = durCol != -1 ? c.getLong(durCol) : 0L;
                        String bId = bIdCol != -1 ? c.getString(bIdCol) : "default_vid";
                        String bName = bNameCol != -1 ? c.getString(bNameCol) : "Videos";
                        long dateAdded = dateCol != -1 ? c.getLong(dateCol) : 0L;
                        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                        queried.add(new GalleryMediaItem(id, uri, path, true, dur, bId, bName, dateAdded));
                        count++;
                    }
                }
            } catch (Exception ignored) {}
        }

        Collections.sort(queried, (a, b) -> Long.compare(b.getDateAdded(), a.getDateAdded()));
        return queried;
    }

    public static Map<String, AlbumBucket> buildAlbumBuckets(List<GalleryMediaItem> items) {
        Map<String, AlbumBucket> map = new LinkedHashMap<>();
        AlbumBucket recents = new AlbumBucket("ALL", "Recents");
        AlbumBucket videos = new AlbumBucket("VIDEOS", "Videos");
        map.put("ALL", recents);

        Map<String, AlbumBucket> folderBuckets = new LinkedHashMap<>();
        if (items != null) {
            for (GalleryMediaItem item : items) {
                recents.items.add(item);
                if (item.isVideo()) {
                    videos.items.add(item);
                }
                String bName = item.getBucketName();
                if (bName != null && !bName.trim().isEmpty()) {
                    String key = "BUCKET_" + bName.trim();
                    AlbumBucket fb = folderBuckets.get(key);
                    if (fb == null) {
                        fb = new AlbumBucket(key, bName.trim());
                        folderBuckets.put(key, fb);
                    }
                    fb.items.add(item);
                }
            }
        }
        if (!videos.items.isEmpty()) {
            map.put("VIDEOS", videos);
        }
        map.putAll(folderBuckets);
        return map;
    }

    public static boolean checkStoragePermission(android.content.Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private boolean hasStoragePermission() {
        return checkStoragePermission(getContext());
    }

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }
}
