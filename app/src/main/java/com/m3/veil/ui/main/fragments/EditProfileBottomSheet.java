package com.m3.veil.ui.main.fragments;



import android.app.Activity;

import android.content.Context;

import android.content.Intent;

import android.graphics.Bitmap;

import android.graphics.BitmapFactory;

import android.net.Uri;

import android.os.Bundle;

import android.os.Handler;

import android.os.Looper;

import android.provider.MediaStore;

import android.text.Editable;

import android.text.TextUtils;

import android.text.TextWatcher;

import android.util.Base64;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.Button;

import android.widget.EditText;

import android.widget.ImageView;

import android.widget.TextView;

import android.widget.Toast;



import androidx.activity.result.ActivityResultLauncher;

import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.core.content.FileProvider;



import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import com.m3.veil.bridge.firestore.DocumentSnapshot;

import com.m3.veil.bridge.firestore.FirebaseFirestore;

import com.m3.veil.R;

import com.m3.veil.manager.VeilFeatureManager;

import com.m3.veil.model.User;

import com.m3.veil.utils.UiAnimator;

import com.m3.veil.webrtc.VeilPacket;

import com.m3.veil.webrtc.WebRtcManager;

import com.yalantis.ucrop.UCrop;



import org.json.JSONObject;



import java.io.ByteArrayOutputStream;

import java.io.File;

import java.io.InputStream;

import java.util.HashMap;

import java.util.Map;



import de.hdodenhof.circleimageview.CircleImageView;



public class EditProfileBottomSheet extends BottomSheetDialogFragment {



    public interface OnProfileUpdatedListener {

        void onProfileUpdated();

    }



    private EditText editDisplayNameInput, editBioInput;

    private TextView tvUsernameAvailability;

    private CircleImageView editAvatarImage;

    private TextView btnRemoveAvatar;

    private Button btnSaveProfile;

    private ImageView btnChangeAvatar;



    private FirebaseFirestore db;

    private String currentUserId;

    private String originalUsername = "";

    private OnProfileUpdatedListener listener;

    private String selectedAvatarBase64 = null;

    private boolean avatarRemoved = false;

    private boolean isUsernameValid = true;



    private Uri cameraTempUri;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());

    private Runnable usernameCheckRunnable;



    // Gallery Picker Launcher

    private final ActivityResultLauncher<Intent> galleryPickerLauncher = registerForActivityResult(

            new ActivityResultContracts.StartActivityForResult(),

            result -> {

                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {

                    Uri selectedImageUri = result.getData().getData();

                    if (selectedImageUri != null) {

                        launchUCrop(selectedImageUri);

                    }

                }

            }

    );



    // Camera Launcher

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(

            new ActivityResultContracts.StartActivityForResult(),

            result -> {

                if (result.getResultCode() == Activity.RESULT_OK && cameraTempUri != null) {

                    launchUCrop(cameraTempUri);

                }

            }

    );



    // UCrop Launcher

    private final ActivityResultLauncher<Intent> uCropLauncher = registerForActivityResult(

            new ActivityResultContracts.StartActivityForResult(),

            result -> {

                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {

                    Uri resultUri = UCrop.getOutput(result.getData());

                    if (resultUri != null) {

                        processCroppedAvatar(resultUri);

                    }

                } else if (result.getResultCode() == UCrop.RESULT_ERROR && result.getData() != null) {

                    Throwable cropError = UCrop.getError(result.getData());

                    Toast.makeText(requireContext(), "Crop failed: " + (cropError != null ? cropError.getMessage() : "unknown"), Toast.LENGTH_SHORT).show();

                }

            }

    );



    public void setOnProfileUpdatedListener(OnProfileUpdatedListener listener) {

        this.listener = listener;

    }



    @Override

    public int getTheme() {

        return R.style.ModernBottomSheetDialogTheme;

    }



    @Nullable

    @Override

    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.dialog_edit_profile, container, false);

    }



    @Override

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);



        try {
            db = FirebaseFirestore.getInstance();
        } catch (Exception ignored) {
            db = null;
        }
        currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(requireContext());
        if (currentUserId == null) {
            currentUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(getContext());
        }



        editDisplayNameInput = view.findViewById(R.id.editDisplayNameInput);

        tvUsernameAvailability = view.findViewById(R.id.tvUsernameAvailability);

        editBioInput = view.findViewById(R.id.editBioInput);

        editAvatarImage = view.findViewById(R.id.editAvatarImage);

        btnRemoveAvatar = view.findViewById(R.id.btnRemoveAvatar);

        btnSaveProfile = view.findViewById(R.id.btnSaveProfile);

        btnChangeAvatar = view.findViewById(R.id.btnChangeAvatar);



        UiAnimator.attachSpringPress(btnSaveProfile);

        UiAnimator.attachSpringPress(btnChangeAvatar);



        btnChangeAvatar.setOnClickListener(v -> showAvatarSourceChooser());

        editAvatarImage.setOnClickListener(v -> showAvatarSourceChooser());

        btnRemoveAvatar.setOnClickListener(v -> removeAvatar());



        setupUsernameValidation();

        loadCurrentProfile();



        btnSaveProfile.setOnClickListener(v -> saveProfile());

    }



    private void showAvatarSourceChooser() {

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());

        View v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_pin_auth, null);

        dialog.setContentView(v);



        TextView tvTitle = v.findViewById(R.id.tvPinDialogTitle);

        TextView tvSubtitle = v.findViewById(R.id.tvPinDialogSubtitle);

        v.findViewById(R.id.pinInputLayout).setVisibility(View.GONE);

        v.findViewById(R.id.btnConfirmPin).setVisibility(View.GONE);



        tvTitle.setText("Profile Avatar");

        tvSubtitle.setText("Choose an option to customize your avatar:");



        ViewGroup parent = (ViewGroup) v.findViewById(R.id.pinInputLayout).getParent();

        int index = parent.indexOfChild(v.findViewById(R.id.pinInputLayout));



        android.widget.LinearLayout optionsLayout = new android.widget.LinearLayout(requireContext());

        optionsLayout.setOrientation(android.widget.LinearLayout.VERTICAL);

        optionsLayout.setPadding(0, 16, 0, 16);



        TextView btnCamera = createOptionRow("📷  Take Photo with Camera", () -> {

            dialog.dismiss();

            launchCamera();

        });

        TextView btnGallery = createOptionRow("🖼️  Choose from Gallery & Crop", () -> {

            dialog.dismiss();

            launchGallery();

        });

        TextView btnRemove = createOptionRow("🗑️  Remove Profile Photo", () -> {

            dialog.dismiss();

            removeAvatar();

        });

        btnRemove.setTextColor(requireContext().getColor(R.color.colorError));



        optionsLayout.addView(btnCamera);

        optionsLayout.addView(btnGallery);

        if (selectedAvatarBase64 != null || btnRemoveAvatar.getVisibility() == View.VISIBLE) {

            optionsLayout.addView(btnRemove);

        }



        parent.addView(optionsLayout, index);



        v.findViewById(R.id.btnCancelPin).setOnClickListener(view -> dialog.dismiss());

        dialog.show();

    }



    private TextView createOptionRow(String label, Runnable action) {

        TextView tv = new TextView(requireContext());

        tv.setText(label);

        tv.setTextSize(15.5f);

        tv.setTextColor(requireContext().getColor(R.color.textPrimary));

        tv.setPadding(24, 28, 24, 28);

        tv.setBackgroundResource(R.drawable.bg_filter_chip);

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(

                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        lp.setMargins(0, 0, 0, 16);

        tv.setLayoutParams(lp);

        tv.setOnClickListener(v -> action.run());

        return tv;

    }



    private void launchGallery() {

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

        intent.setType("image/*");

        galleryPickerLauncher.launch(Intent.createChooser(intent, "Choose Profile Avatar"));

    }



    private void launchCamera() {

        try {

            File photoFile = new File(requireContext().getCacheDir(), "avatar_cam_" + System.currentTimeMillis() + ".jpg");

            cameraTempUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", photoFile);



            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraTempUri);

            cameraLauncher.launch(intent);

        } catch (Exception e) {

            Toast.makeText(requireContext(), "Camera launch error: " + e.getMessage(), Toast.LENGTH_SHORT).show();

        }

    }



    private void launchUCrop(Uri sourceUri) {

        File destFile = new File(requireContext().getCacheDir(), "crop_" + System.currentTimeMillis() + ".jpg");

        Uri destUri = Uri.fromFile(destFile);



        UCrop.Options options = new UCrop.Options();

        options.setCircleDimmedLayer(true);

        options.setShowCropGrid(false);

        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);

        options.setCompressionQuality(85);

        options.setToolbarColor(requireContext().getColor(R.color.colorSurface));

        options.setStatusBarColor(requireContext().getColor(R.color.colorSurface));

        options.setToolbarWidgetColor(requireContext().getColor(R.color.textPrimary));

        options.setToolbarTitle("Crop Profile Photo");



        UCrop uCrop = UCrop.of(sourceUri, destUri)

                .withAspectRatio(1, 1)

                .withMaxResultSize(1080, 1080)

                .withOptions(options);



        uCropLauncher.launch(uCrop.getIntent(requireContext()));

    }



    private void processCroppedAvatar(Uri uri) {

        new Thread(() -> {

            try {

                InputStream is = requireContext().getContentResolver().openInputStream(uri);

                Bitmap bitmap = BitmapFactory.decodeStream(is);

                if (is != null) is.close();



                if (bitmap != null) {

                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    scaled.compress(Bitmap.CompressFormat.WEBP, 85, baos);

                    byte[] bytes = baos.toByteArray();

                    selectedAvatarBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);

                    avatarRemoved = false;



                    if (currentUserId != null && !currentUserId.isEmpty()) {

                        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext())

                                .saveAvatarWebpFile(currentUserId, selectedAvatarBase64);

                    }



                    requireActivity().runOnUiThread(() -> {

                        if (isAdded() && editAvatarImage != null) {

                            editAvatarImage.setImageBitmap(scaled);

                            btnRemoveAvatar.setVisibility(View.VISIBLE);

                        }

                    });

                }

            } catch (Exception e) {

                requireActivity().runOnUiThread(() -> {

                    if (isAdded()) {

                        Toast.makeText(requireContext(), "Failed to process cropped avatar", Toast.LENGTH_SHORT).show();

                    }

                });

            }

        }).start();

    }



    private void removeAvatar() {

        selectedAvatarBase64 = null;

        avatarRemoved = true;

        editAvatarImage.setImageResource(R.drawable.ic_profile_placeholder);

        btnRemoveAvatar.setVisibility(View.GONE);

        Toast.makeText(requireContext(), "Avatar removed. Click 'Save Changes' to apply.", Toast.LENGTH_SHORT).show();

    }



    private void setupUsernameValidation() {

        editDisplayNameInput.addTextChangedListener(new TextWatcher() {

            @Override

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}



            @Override

            public void onTextChanged(CharSequence s, int start, int before, int count) {}



            @Override

            public void afterTextChanged(Editable s) {

                String input = s != null ? s.toString().trim() : "";

                if (input.equals(originalUsername)) {

                    tvUsernameAvailability.setVisibility(View.GONE);

                    isUsernameValid = true;

                    btnSaveProfile.setEnabled(true);

                    return;

                }



                if (input.length() < 3) {

                    tvUsernameAvailability.setText("Display name must be at least 3 characters");

                    tvUsernameAvailability.setTextColor(requireContext().getColor(R.color.colorError));

                    tvUsernameAvailability.setVisibility(View.VISIBLE);

                    isUsernameValid = false;

                    btnSaveProfile.setEnabled(false);

                    return;

                }



                tvUsernameAvailability.setText("Checking availability...");

                tvUsernameAvailability.setTextColor(requireContext().getColor(R.color.textSecondary));

                tvUsernameAvailability.setVisibility(View.VISIBLE);



                if (usernameCheckRunnable != null) {

                    debounceHandler.removeCallbacks(usernameCheckRunnable);

                }



                usernameCheckRunnable = () -> checkUsernameAvailability(input);

                debounceHandler.postDelayed(usernameCheckRunnable, 450);

            }

        });

    }



    private void checkUsernameAvailability(String username) {
        if (username == null || username.trim().isEmpty()) {
            if (isAdded()) tvUsernameAvailability.setText("");
            return;
        }
        final String clean = username.startsWith("@") ? username.substring(1).trim() : username.trim();
        if (originalUsername != null && originalUsername.equalsIgnoreCase(clean)) {
            if (isAdded()) {
                tvUsernameAvailability.setText("✓ @" + clean + " is your current username");
                tvUsernameAvailability.setTextColor(requireContext().getColor(R.color.colorPrimary));
                isUsernameValid = true;
                btnSaveProfile.setEnabled(true);
            }
            return;
        }
        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext())
                .isUsernameTaken(clean, isTaken -> {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (!isTaken) {
                            tvUsernameAvailability.setText("✓ @" + clean + " is available");
                            tvUsernameAvailability.setTextColor(requireContext().getColor(R.color.colorPrimary));
                            isUsernameValid = true;
                            btnSaveProfile.setEnabled(true);
                        } else {
                            tvUsernameAvailability.setText("✗ @" + clean + " is already taken");
                            tvUsernameAvailability.setTextColor(requireContext().getColor(R.color.colorError));
                            isUsernameValid = false;
                            btnSaveProfile.setEnabled(false);
                        }
                    });
                });
    }

    private void loadCurrentProfile() {

        if (currentUserId == null || currentUserId.isEmpty()) {

            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext()).getCurrentUserId();

            if (currentUserId == null) {

                com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(requireContext());

                currentUserId = up.getUserId();

            }

        }

        if (currentUserId == null) return;



        // 1. Load from local UserPreferences & Sovereign storage first

        com.m3.veil.utils.UserPreferences userPrefs = new com.m3.veil.utils.UserPreferences(requireContext());

        User localUser = userPrefs.getCurrentUser();

        if (localUser != null) {

            if (localUser.getDisplayName() != null && !localUser.getDisplayName().isEmpty()) {

                originalUsername = localUser.getDisplayName();

                editDisplayNameInput.setText(originalUsername);

            }

            if (localUser.getBio() != null) {

                editBioInput.setText(localUser.getBio());

            }

            if (localUser.getAvatar() != null && !localUser.getAvatar().isEmpty()) {

                try {

                    byte[] decoded = Base64.decode(localUser.getAvatar(), Base64.DEFAULT);

                    Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);

                    if (bmp != null) {

                        editAvatarImage.setImageBitmap(bmp);

                        btnRemoveAvatar.setVisibility(View.VISIBLE);

                    }

                } catch (Exception ignored) {}

            }

        }



        // Check local avatar file

        java.io.File avatarFile = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext()).getAvatarWebpFile(currentUserId);

        if (avatarFile.exists()) {

            Bitmap bmp = BitmapFactory.decodeFile(avatarFile.getAbsolutePath());

            if (bmp != null) {

                editAvatarImage.setImageBitmap(bmp);

                btnRemoveAvatar.setVisibility(View.VISIBLE);

            }

        }



        // 2. Query Firestore as secondary sync if present

        try {

            db.collection("users").document(currentUserId).get()

                    .addOnSuccessListener(documentSnapshot -> {

                        if (documentSnapshot.exists() && isAdded()) {

                            User user = documentSnapshot.toObject(User.class);

                            if (user != null) {

                                if (user.getDisplayName() != null && TextUtils.isEmpty(editDisplayNameInput.getText())) {

                                    originalUsername = user.getDisplayName();

                                    editDisplayNameInput.setText(originalUsername);

                                }

                                if (user.getBio() != null && TextUtils.isEmpty(editBioInput.getText())) {

                                    editBioInput.setText(user.getBio());

                                }

                            }

                        }

                    });

        } catch (Exception ignored) {}

    }



    private void saveProfile() {

        String newName = editDisplayNameInput.getText().toString().trim();

        String newBio = editBioInput.getText().toString().trim();



        if (TextUtils.isEmpty(newName)) {

            Toast.makeText(requireContext(), "Display name cannot be empty", Toast.LENGTH_SHORT).show();

            return;

        }



        if (!isUsernameValid) {

            Toast.makeText(requireContext(), "Please select an available username", Toast.LENGTH_SHORT).show();

            return;

        }



        btnSaveProfile.setEnabled(false);



        if (currentUserId == null || currentUserId.isEmpty()) {

            currentUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext()).getCurrentUserId();

            if (currentUserId == null) {

                com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(requireContext());

                currentUserId = up.getUserId();

            }

        }



        // 1. Build and publish cryptographically signed SovereignProfileManifest

        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine engine =

                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(requireContext());

        com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(requireContext());

        com.m3.veil.manager.VeilFeatureManager fm = com.m3.veil.manager.VeilFeatureManager.getInstance(requireContext());



        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =

                new com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest();

        manifest.uid = currentUserId != null ? currentUserId : "veil_anon";

        manifest.username = up.getUsername() != null ? up.getUsername() : "";

        manifest.displayName = newName;

        manifest.bio = newBio;

        manifest.xp = fm.getXp();

        manifest.karma = fm.getKarma();

        manifest.veilCoins = fm.getVeilCoins();

        manifest.personasJsonArray = fm.getPersonas() != null ? fm.getPersonas().toString() : "[]";

        manifest.publicKeyBase64 = com.m3.veil.utils.EncryptionManager.getPublicKeyString();

        manifest.phoneNumber = up.getCurrentUser() != null ? up.getCurrentUser().getPhone() : null;



        if (selectedAvatarBase64 != null) {

            manifest.avatarBase64 = selectedAvatarBase64;

            engine.saveAvatarWebpFile(manifest.uid, selectedAvatarBase64);

        } else if (avatarRemoved) {

            manifest.avatarBase64 = "";

        } else {

            User existing = up.getCurrentUser();

            if (existing != null && existing.getAvatar() != null) {

                manifest.avatarBase64 = existing.getAvatar();

            }

        }



        engine.publishProfileManifest(requireContext(), manifest, null);



        // 2. Update local UserPreferences

        up.updateUserField("displayName", newName);

        up.updateUserField("bio", newBio);

        if (selectedAvatarBase64 != null) {

            up.updateUserField("avatar", selectedAvatarBase64);

        } else if (avatarRemoved) {

            up.updateUserField("avatar", "");

        }



        // 3. Update active persona details

        fm.updateActivePersonaDetails(newName, newBio);



        // 4. Broadcast profile update over P2P mesh data channels

        broadcastProfileUpdateToMesh(newName, newBio, selectedAvatarBase64 != null ? selectedAvatarBase64 : (avatarRemoved ? "" : null));



        // 5. Best-effort Firestore update

        try {

            if (currentUserId != null && db != null) {

                Map<String, Object> updates = new HashMap<>();

                updates.put("displayName", newName);

                updates.put("bio", newBio);

                if (selectedAvatarBase64 != null) {

                    updates.put("avatar", selectedAvatarBase64);

                } else if (avatarRemoved) {

                    updates.put("avatar", null);

                }

                db.collection("users").document(currentUserId).update(updates);

            }

        } catch (Exception ignored) {}



        if (isAdded()) {

            Toast.makeText(requireContext(), "Profile updated and published to mesh", Toast.LENGTH_SHORT).show();

            if (listener != null) listener.onProfileUpdated();

            dismiss();

        }

    }



    private void broadcastProfileUpdateToMesh(String name, String bio, String avatarBase64) {

        try {

            JSONObject obj = new JSONObject();

            obj.put("type", "PROFILE_UPDATE");

            obj.put("userId", currentUserId);

            obj.put("displayName", name);

            obj.put("bio", bio);

            if (avatarBase64 != null) {

                obj.put("avatar", avatarBase64);

            }

            VeilPacket packet = new VeilPacket((byte) 0x01, VeilPacket.TYPE_GROUP_SETTINGS, obj.toString());

            WebRtcManager.getInstance(requireContext()).broadcastPacket(packet);

        } catch (Throwable t) {

            // Non-fatal

        }

    }

}

