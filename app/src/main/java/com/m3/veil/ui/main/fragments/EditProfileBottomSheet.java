package com.m3.veil.ui.main.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.m3.veil.R;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.model.User;
import com.m3.veil.utils.PreferenceManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.UiAnimator;
import com.m3.veil.utils.UserPreferences;
import com.m3.veil.utils.VeilAvatarLoader;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL EDIT PROFILE BOTTOM SHEET (SIGNAL SMART-HYBRID + BLOOM FILTER O(1) HANDLE VERIFICATION)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Features:
 * 1. Separate Display Name and @Username editing so users can use rich display names with spaces
 *    while maintaining a unique @username handle.
 * 2. Real-time Kirsch-Mitzenmacher k=7 Bloom Filter (<0.02ms O(1)) + Firestore/SQLCipher hybrid
 *    @username availability check.
 * 3. Live Cypherpunk Mood & Status Pill Selector.
 * 4. 256x256 WebP Avatar DP compression, local file caching ("files/avatars/avatar_<uid>.webp"),
 *    and Base64 synchronization across Firestore ("users/{uid}" & "usernames/{username}"),
 *    SQLCipher, UserPreferences, and PreferenceManager.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class EditProfileBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "EditProfileBottomSheet";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");
    private static final long USERNAME_DEBOUNCE_MS = 260L;

    private static final String[] MOODS = {
            "✨ Available", "👻 Ghost Mode", "⚡ Active",
            "🌙 Do Not Disturb", "🔥 Building", "🕵️ Incognito", "💎 Sovereign"
    };

    public interface OnProfileUpdatedListener {
        void onProfileUpdated();
    }

    private OnProfileUpdatedListener listener;

    private CircleImageView editAvatarImage;
    private ImageView btnChangeAvatar;
    private TextView btnRemoveAvatar;
    private EditText editDisplayNameInput;
    private EditText editUsernameInput;
    private TextView tvUsernameAvailability;
    private EditText editBioInput;
    private LinearLayout moodSelectorContainer;
    private Button btnSaveProfile;

    private UserPreferences userPreferences;
    private PreferenceManager preferenceManager;
    private SovereignIdentityAndDirectoryEngine sovereignEngine;
    private FirebaseFirestore db;

    private String currentUid;
    private String originalUsername = "";
    private String selectedMood = MOODS[0];
    private String pendingAvatarBase64 = null;
    private Bitmap selectedAvatarBitmap = null;
    private boolean avatarRemoved = false;
    private boolean isUsernameValidAndAvailable = true;
    private boolean isSaving = false;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable usernameCheckRunnable;

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        processSelectedAvatarUri(imageUri);
                    }
                }
            });

    public void setOnProfileUpdatedListener(OnProfileUpdatedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ModernBottomSheetDialogTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_edit_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = requireContext();
        userPreferences = new UserPreferences(ctx);
        preferenceManager = new PreferenceManager(ctx);
        sovereignEngine = SovereignIdentityAndDirectoryEngine.getInstance(ctx);
        db = FirebaseFirestore.getInstance();

        // Warm up the Kirsch-Mitzenmacher k=7 Bloom Filter
        sovereignEngine.getUsernameBloomFilter();

        currentUid = AuthManager.getInstance().getCurrentUserId(ctx);
        if (currentUid == null || currentUid.isEmpty()) {
            currentUid = sovereignEngine.getCurrentUid();
        }

        bindAndEnhanceViews(view);
        populateCurrentProfileData();
        setupInteractions();
    }

    private void bindAndEnhanceViews(View view) {
        editAvatarImage = view.findViewById(R.id.editAvatarImage);
        btnChangeAvatar = view.findViewById(R.id.btnChangeAvatar);
        btnRemoveAvatar = view.findViewById(R.id.btnRemoveAvatar);
        editDisplayNameInput = view.findViewById(R.id.editDisplayNameInput);
        tvUsernameAvailability = view.findViewById(R.id.tvUsernameAvailability);
        editBioInput = view.findViewById(R.id.editBioInput);
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile);

        // Configure editDisplayNameInput strictly for Display Name, and dynamically insert
        // a dedicated @Username input + Mood Selector into the layout container.
        if (editDisplayNameInput != null) {
            editDisplayNameInput.setHint("Enter your display name");
            if (editDisplayNameInput.getParent() instanceof LinearLayout) {
                LinearLayout parent = (LinearLayout) editDisplayNameInput.getParent();
                int labelIdx = parent.indexOfChild(editDisplayNameInput) - 1;
                if (labelIdx >= 0 && parent.getChildAt(labelIdx) instanceof TextView) {
                    ((TextView) parent.getChildAt(labelIdx)).setText("Display Name");
                }

                float density = getResources().getDisplayMetrics().density;
                int insertPos = parent.indexOfChild(editDisplayNameInput) + 1;

                TextView tvUsernameLabel = new TextView(requireContext());
                tvUsernameLabel.setText("@Username Handle (Bloom Filter O(1) Verified)");
                tvUsernameLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
                tvUsernameLabel.setTextSize(12f);
                tvUsernameLabel.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                labelLp.topMargin = (int) (14 * density);
                tvUsernameLabel.setLayoutParams(labelLp);
                parent.addView(tvUsernameLabel, insertPos++);

                editUsernameInput = new EditText(requireContext());
                editUsernameInput.setHint("e.g. shadow_specter");
                editUsernameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editUsernameInput.setSingleLine(true);
                editUsernameInput.setBackgroundResource(R.drawable.bg_message_received);
                editUsernameInput.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), R.color.colorSurfaceVariant));
                int padH = (int) (16 * density);
                int padV = (int) (12 * density);
                editUsernameInput.setPadding(padH, padV, padH, padV);
                editUsernameInput.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextPrimary));
                editUsernameInput.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
                editUsernameInput.setTextSize(14f);
                LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                inputLp.topMargin = (int) (6 * density);
                editUsernameInput.setLayoutParams(inputLp);
                parent.addView(editUsernameInput, insertPos++);

                // Inject Mood Selector Strip right before Save Button
                int saveIdx = parent.indexOfChild(btnSaveProfile);
                if (saveIdx > 0) {
                    TextView tvMoodLabel = new TextView(requireContext());
                    tvMoodLabel.setText("Active Cypherpunk Mood");
                    tvMoodLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorTextSecondary));
                    tvMoodLabel.setTextSize(12f);
                    tvMoodLabel.setTypeface(null, Typeface.BOLD);
                    LinearLayout.LayoutParams moodLabelLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    moodLabelLp.topMargin = (int) (14 * density);
                    moodLabelLp.bottomMargin = (int) (6 * density);
                    tvMoodLabel.setLayoutParams(moodLabelLp);
                    parent.addView(tvMoodLabel, saveIdx++);

                    HorizontalScrollView moodScroll = new HorizontalScrollView(requireContext());
                    moodScroll.setHorizontalScrollBarEnabled(false);
                    moodScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                    moodSelectorContainer = new LinearLayout(requireContext());
                    moodSelectorContainer.setOrientation(LinearLayout.HORIZONTAL);
                    moodScroll.addView(moodSelectorContainer);
                    parent.addView(moodScroll, saveIdx);
                }
            }
        }

        if (btnSaveProfile != null) {
            UiAnimator.attachSpringPress(btnSaveProfile);
        }
    }

    private void populateCurrentProfileData() {
        selectedMood = preferenceManager.getString("user_mood", MOODS[0]);

        String displayName = userPreferences.getDisplayName();
        String username = userPreferences.getUsername();
        String bio = userPreferences.getBio();
        String avatar = userPreferences.getAvatar();

        if (currentUid != null) {
            SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                    sovereignEngine.getLocalManifest(currentUid);
            if (manifest != null) {
                if ((displayName == null || displayName.isEmpty()) && manifest.displayName != null) {
                    displayName = manifest.displayName;
                }
                if ((username == null || username.isEmpty()) && manifest.username != null) {
                    username = manifest.username;
                }
                if ((bio == null || bio.isEmpty()) && manifest.bio != null) {
                    bio = manifest.bio;
                }
                if ((avatar == null || avatar.isEmpty()) && manifest.avatarBase64 != null) {
                    avatar = manifest.avatarBase64;
                }
                if (manifest.mood != null && !manifest.mood.isEmpty()) {
                    selectedMood = manifest.mood;
                }
            }
        }

        if (username != null) {
            originalUsername = username.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        }

        if (editDisplayNameInput != null) {
            editDisplayNameInput.setText(displayName != null && !displayName.isEmpty() ? displayName : originalUsername);
        }
        if (editUsernameInput != null) {
            editUsernameInput.setText(originalUsername);
        }
        if (editBioInput != null) {
            editBioInput.setText(bio != null ? bio : "");
        }

        renderAvatarPreview(avatar, displayName != null ? displayName : originalUsername);
        renderMoodPills();
    }

    private void renderAvatarPreview(String avatarSource, String fallbackName) {
        if (editAvatarImage == null) return;

        if (currentUid != null) {
            File localWebp = sovereignEngine.getAvatarWebpFile(currentUid);
            if (localWebp.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(localWebp.getAbsolutePath());
                if (bmp != null) {
                    editAvatarImage.setImageBitmap(bmp);
                    if (btnRemoveAvatar != null) btnRemoveAvatar.setVisibility(View.VISIBLE);
                    return;
                }
            }
        }

        if (avatarSource != null && !avatarSource.isEmpty()) {
            VeilAvatarLoader.loadAvatar(editAvatarImage, avatarSource, fallbackName, false);
            if (btnRemoveAvatar != null) btnRemoveAvatar.setVisibility(View.VISIBLE);
        } else {
            VeilAvatarLoader.loadAvatar(editAvatarImage, null, fallbackName, false);
            if (btnRemoveAvatar != null) btnRemoveAvatar.setVisibility(View.GONE);
        }
    }

    private void renderMoodPills() {
        if (moodSelectorContainer == null || getContext() == null) return;
        moodSelectorContainer.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        for (String mood : MOODS) {
            boolean isSelected = mood.equals(selectedMood);
            TextView pill = new TextView(getContext());
            pill.setText(mood);
            pill.setTextSize(12.5f);
            pill.setTypeface(null, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            int padH = (int) (14 * density);
            int padV = (int) (8 * density);
            pill.setPadding(padH, padV, padH, padV);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(18 * density);
            if (isSelected) {
                bg.setColor(ContextCompat.getColor(getContext(), R.color.colorPrimary));
                pill.setTextColor(Color.WHITE);
            } else {
                bg.setColor(ContextCompat.getColor(getContext(), R.color.colorSurfaceVariant));
                bg.setStroke((int) (1 * density), ContextCompat.getColor(getContext(), R.color.colorOutline));
                pill.setTextColor(ContextCompat.getColor(getContext(), R.color.colorTextPrimary));
            }
            pill.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * density);
            pill.setLayoutParams(lp);

            pill.setOnClickListener(v -> {
                selectedMood = mood;
                renderMoodPills();
            });
            moodSelectorContainer.addView(pill);
        }
    }

    private void setupInteractions() {
        View.OnClickListener pickAvatarClick = v -> openGalleryPicker();
        if (editAvatarImage != null) editAvatarImage.setOnClickListener(pickAvatarClick);
        if (btnChangeAvatar != null) btnChangeAvatar.setOnClickListener(pickAvatarClick);

        if (btnRemoveAvatar != null) {
            btnRemoveAvatar.setOnClickListener(v -> {
                selectedAvatarBitmap = null;
                pendingAvatarBase64 = "";
                avatarRemoved = true;
                String dName = editDisplayNameInput != null && editDisplayNameInput.getText() != null
                        ? editDisplayNameInput.getText().toString().trim() : originalUsername;
                VeilAvatarLoader.loadAvatar(editAvatarImage, null, dName, false);
                btnRemoveAvatar.setVisibility(View.GONE);
            });
        }

        if (editUsernameInput != null) {
            editUsernameInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (usernameCheckRunnable != null) {
                        debounceHandler.removeCallbacks(usernameCheckRunnable);
                    }
                    String raw = s != null ? s.toString().trim().replaceFirst("^@", "") : "";
                    validateUsernameWithBloomFilter(raw);
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnSaveProfile != null) {
            btnSaveProfile.setOnClickListener(v -> saveChanges());
        }
    }

    private void openGalleryPicker() {
        try {
            com.m3.veil.manager.PresenceManager.getInstance().notifyExternalPickerLaunched();
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            imagePickerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Unable to open gallery: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void processSelectedAvatarUri(Uri uri) {
        if (getContext() == null) return;
        try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            Bitmap decoded = BitmapFactory.decodeStream(is);
            if (decoded == null) return;

            int width = decoded.getWidth();
            int height = decoded.getHeight();
            int side = Math.min(width, height);
            int x = (width - side) / 2;
            int y = (height - side) / 2;
            Bitmap square = Bitmap.createBitmap(decoded, x, y, side, side);
            Bitmap scaled256 = Bitmap.createScaledBitmap(square, 256, 256, true);

            selectedAvatarBitmap = scaled256;
            pendingAvatarBase64 = sovereignEngine.compressBitmapToWebpBase64(scaled256);
            avatarRemoved = false;

            if (editAvatarImage != null) {
                editAvatarImage.setImageBitmap(scaled256);
            }
            if (btnRemoveAvatar != null) {
                btnRemoveAvatar.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding selected avatar", e);
            Toast.makeText(getContext(), "Failed to load image", Toast.LENGTH_SHORT).show();
        }
    }

    private void validateUsernameWithBloomFilter(String rawUsername) {
        if (tvUsernameAvailability == null || getContext() == null) return;

        String clean = rawUsername.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) {
            isUsernameValidAndAvailable = false;
            tvUsernameAvailability.setVisibility(View.VISIBLE);
            tvUsernameAvailability.setText("Username cannot be empty");
            tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorError));
            return;
        }

        if (clean.equals(originalUsername)) {
            isUsernameValidAndAvailable = true;
            tvUsernameAvailability.setVisibility(View.VISIBLE);
            tvUsernameAvailability.setText("✓ Your current @username (" + originalUsername + ")");
            tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorPrimary));
            return;
        }

        if (!USERNAME_PATTERN.matcher(clean).matches()) {
            isUsernameValidAndAvailable = false;
            tvUsernameAvailability.setVisibility(View.VISIBLE);
            tvUsernameAvailability.setText("Use 3-20 letters, numbers, or underscores (_)");
            tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorError));
            return;
        }

        SovereignIdentityAndDirectoryEngine.ClaimedUsernameBloomFilter bloom =
                sovereignEngine.getUsernameBloomFilter();
        if (bloom.isReserved(clean)) {
            isUsernameValidAndAvailable = false;
            tvUsernameAvailability.setVisibility(View.VISIBLE);
            tvUsernameAvailability.setText("✗ @" + clean + " is a reserved handle");
            tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorError));
            return;
        }

        long t0 = System.nanoTime();
        boolean bloomMightContain = sovereignEngine.mightContainUsernameInBloom(clean);
        long us = Math.max(1L, (System.nanoTime() - t0) / 1000L);

        tvUsernameAvailability.setVisibility(View.VISIBLE);
        if (!bloomMightContain) {
            isUsernameValidAndAvailable = true;
            tvUsernameAvailability.setText("⚡ Bloom O(1) (" + us + "µs): @" + clean + " is available");
            tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorPrimary));
        } else {
            tvUsernameAvailability.setText("🔍 Bloom hit (" + us + "µs) — checking directory...");
            tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorTextSecondary));
        }

        usernameCheckRunnable = () -> {
            sovereignEngine.isUsernameTaken(clean, currentUid, isTaken -> {
                if (!isAdded() || getContext() == null) return;
                String currentText = editUsernameInput != null && editUsernameInput.getText() != null
                        ? editUsernameInput.getText().toString().trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT)
                        : "";
                if (!clean.equals(currentText)) return;

                if (isTaken) {
                    isUsernameValidAndAvailable = false;
                    tvUsernameAvailability.setText("✗ @" + clean + " is already taken");
                    tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorError));
                } else {
                    isUsernameValidAndAvailable = true;
                    tvUsernameAvailability.setText("✓ Bloom + Directory Verified: @" + clean + " is available");
                    tvUsernameAvailability.setTextColor(ContextCompat.getColor(getContext(), R.color.colorPrimary));
                }
            });
        };
        debounceHandler.postDelayed(usernameCheckRunnable, USERNAME_DEBOUNCE_MS);
    }

    private void saveChanges() {
        if (isSaving || getContext() == null) return;

        String newDisplayName = editDisplayNameInput != null && editDisplayNameInput.getText() != null
                ? editDisplayNameInput.getText().toString().trim()
                : "";
        String newUsername = editUsernameInput != null && editUsernameInput.getText() != null
                ? editUsernameInput.getText().toString().trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT)
                : originalUsername;
        String newBio = editBioInput != null && editBioInput.getText() != null
                ? editBioInput.getText().toString().trim()
                : "";

        if (newUsername.isEmpty()) {
            newUsername = originalUsername;
        }
        if (!USERNAME_PATTERN.matcher(newUsername).matches()) {
            Toast.makeText(getContext(), "Username must be 3-20 alphanumeric/underscore characters", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isUsernameValidAndAvailable && !newUsername.equals(originalUsername)) {
            Toast.makeText(getContext(), "Please choose an available @username", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newDisplayName.isEmpty()) {
            newDisplayName = newUsername;
        }

        isSaving = true;
        if (btnSaveProfile != null) {
            btnSaveProfile.setEnabled(false);
            btnSaveProfile.setText("Saving...");
        }

        final String finalUsername = newUsername;
        final String finalDisplayName = newDisplayName;
        final String finalBio = newBio;

        if (currentUid == null || currentUid.isEmpty()) {
            currentUid = sovereignEngine.getCurrentUid();
        }
        final String uid = currentUid;

        // 1. Resolve Avatar Base64 & Local WebP File
        String resolvedAvatarB64 = pendingAvatarBase64;
        String resolvedAvatarPath = null;

        if (avatarRemoved) {
            resolvedAvatarB64 = "";
            if (uid != null) {
                File f = sovereignEngine.getAvatarWebpFile(uid);
                if (f.exists()) f.delete();
            }
        } else if (resolvedAvatarB64 != null && !resolvedAvatarB64.isEmpty() && uid != null) {
            File savedFile = sovereignEngine.saveAvatarBase64ToLocalFile(uid, resolvedAvatarB64);
            if (savedFile != null) {
                resolvedAvatarPath = savedFile.getAbsolutePath();
            }
        } else {
            SovereignIdentityAndDirectoryEngine.SovereignProfileManifest existing =
                    uid != null ? sovereignEngine.getLocalManifest(uid) : null;
            if (existing != null && existing.avatarBase64 != null && !existing.avatarBase64.isEmpty()) {
                resolvedAvatarB64 = existing.avatarBase64;
            } else {
                String existingPrefAv = userPreferences.getAvatar();
                if (existingPrefAv != null && !existingPrefAv.startsWith("/")) {
                    resolvedAvatarB64 = existingPrefAv;
                }
            }
            if (uid != null && sovereignEngine.getAvatarWebpFile(uid).exists()) {
                resolvedAvatarPath = sovereignEngine.getAvatarWebpFile(uid).getAbsolutePath();
            }
        }

        final String finalAvatarB64 = resolvedAvatarB64 != null ? resolvedAvatarB64 : "";
        final String finalAvatarForPrefs = (resolvedAvatarPath != null && !resolvedAvatarPath.isEmpty())
                ? resolvedAvatarPath : finalAvatarB64;

        // 2. Update UserPreferences & PreferenceManager immediately
        preferenceManager.putString("user_mood", selectedMood);
        userPreferences.saveProfileFields(uid, finalUsername, finalDisplayName, finalBio, finalAvatarForPrefs);

        User currentUser = userPreferences.getCurrentUser();
        if (currentUser == null && uid != null) {
            currentUser = new User(uid, "", "SOV");
        }
        if (currentUser != null) {
            currentUser.setUsername(finalUsername);
            currentUser.setDisplayName(finalDisplayName);
            currentUser.setBio(finalBio);
            currentUser.setAvatar(finalAvatarForPrefs);
            currentUser.setPhotoUrl(finalAvatarForPrefs);
            userPreferences.saveUser(currentUser);
        }

        // 3. Register new username in Bloom Filter & sync active persona
        sovereignEngine.recordClaimedUsername(finalUsername);
        com.m3.veil.manager.VeilFeatureManager.getInstance(getContext())
                .syncPrimaryPersonaWithUser(finalUsername, finalDisplayName, finalBio, selectedMood);

        // 4. Update Firestore ("users/{uid}" & "usernames/{finalUsername}")
        if (uid != null && !uid.isEmpty()) {
            long now = System.currentTimeMillis();
            Map<String, Object> updates = new HashMap<>();
            updates.put("userId", uid);
            updates.put("uid", uid);
            updates.put("username", finalUsername);
            updates.put("usernameLower", finalUsername.toLowerCase(Locale.ROOT));
            updates.put("displayName", finalDisplayName);
            updates.put("bio", finalBio);
            updates.put("status", selectedMood);
            updates.put("avatar", finalAvatarB64);
            updates.put("photoUrl", finalAvatarB64);
            updates.put("updatedAtTimestamp", now);
            updates.put("lastSeen", Timestamp.now());

            db.collection("users").document(uid).set(updates, SetOptions.merge());

            Map<String, Object> unameDoc = new HashMap<>();
            unameDoc.put("userId", uid);
            unameDoc.put("uid", uid);
            unameDoc.put("username", finalUsername);
            unameDoc.put("displayName", finalDisplayName);
            unameDoc.put("bio", finalBio);
            unameDoc.put("avatar", finalAvatarB64);
            unameDoc.put("updatedAtTimestamp", now);

            db.collection("usernames").document(finalUsername.toLowerCase(Locale.ROOT)).set(unameDoc, SetOptions.merge());

            if (!originalUsername.isEmpty() && !originalUsername.equalsIgnoreCase(finalUsername)) {
                try {
                    db.collection("usernames").document(originalUsername.toLowerCase(Locale.ROOT)).delete();
                } catch (Exception ignored) {}
            }
        }

        // 5. Publish SovereignProfileManifest across SQLCipher, Surviving File, Firestore, and Nostr
        SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                uid != null ? sovereignEngine.getLocalManifest(uid) : null;
        if (manifest == null) {
            manifest = new SovereignIdentityAndDirectoryEngine.SovereignProfileManifest();
            manifest.uid = uid;
        }
        manifest.username = finalUsername;
        manifest.displayName = finalDisplayName;
        manifest.bio = finalBio;
        manifest.mood = selectedMood;
        manifest.avatarBase64 = finalAvatarB64;
        manifest.updatedAtTimestamp = System.currentTimeMillis();

        sovereignEngine.publishProfileManifest(requireContext(), manifest, new SovereignIdentityAndDirectoryEngine.PublishCallback() {
            @Override
            public void onSuccess() {
                finishSaveOnUiThread();
            }

            @Override
            public void onError(String message) {
                finishSaveOnUiThread();
            }
        });
    }

    private void finishSaveOnUiThread() {
        if (!isAdded()) return;
        isSaving = false;
        if (listener != null) {
            listener.onProfileUpdated();
        }
        Toast.makeText(requireContext(), "Profile updated", Toast.LENGTH_SHORT).show();
        dismissAllowingStateLoss();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (usernameCheckRunnable != null) {
            debounceHandler.removeCallbacks(usernameCheckRunnable);
        }
    }
}
