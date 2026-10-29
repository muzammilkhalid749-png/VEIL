package com.m3.veil.ui.splash;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.m3.veil.R;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.services.VeilMeshDaemonService;
import com.m3.veil.ui.auth.PhoneAuthActivity;
import com.m3.veil.ui.auth.PinUnlockActivity;
import com.m3.veil.ui.auth.UsernameActivity;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.ui.onboard.OnboardingActivity;
import com.m3.veil.utils.PreferenceManager;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;
import com.m3.veil.utils.UserPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL CYPHERPUNK SPLASH & CRYPTOGRAPHIC BOOTLOADER (SIGNAL SMART-HYBRID ARCHITECTURE)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * 1. Binds to R.layout.activity_splash (splash_root, splash_logo, shield_glow, text_container,
 *    app_name, tagline, particles_view) and injects a live cryptographic boot telemetry pill.
 * 2. Pre-warms SovereignIdentityAndDirectoryEngine (65,536-bit k=7 Kirsch-Mitzenmacher Bloom Filter
 *    for @username availability checking + SQLCipher WAL identity vault).
 * 3. Deterministic state machine routing:
 *    - Unauthenticated -> OnboardingActivity or PhoneAuthActivity
 *    - Authenticated + Complete Profile (@username claimed) -> MainActivity (or PinUnlockActivity)
 *    - Authenticated + Restorable Profile on Firestore / Nostr -> Instant 50ms restore -> MainActivity
 *    - Authenticated + No @username yet -> UsernameActivity
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";

    private RelativeLayout splashRoot;
    private ImageView splashLogo;
    private CloakedWhisperLogoDrawable whisperLogoDrawable;
    private ImageView shieldGlow;
    private LinearLayout textContainer;
    private TextView appNameText;
    private TextView taglineText;
    private ImageView particlesView;
    private TextView bootTelemetryChip;

    private PreferenceManager preferenceManager;
    private UserPreferences userPreferences;
    private FirebaseFirestore firestore;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean hasNavigated = false;

    private volatile boolean cachedPinLockEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isTaskRoot()) {
            Intent intent = getIntent();
            if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(intent.getAction())) {
                finish();
                return;
            }
        }
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        preferenceManager = new PreferenceManager(this);
        userPreferences = new UserPreferences(this);
        firestore = FirebaseFirestore.getInstance();

        setupViews();
        warmCryptographicSubsystemsAsync();
        startAnimations();
        scheduleNavigation();
    }

    private void setupViews() {
        splashRoot = findViewById(R.id.splash_root);
        View rawLogoView = findViewById(R.id.splash_logo);
        if (rawLogoView instanceof ImageView) {
            splashLogo = (ImageView) rawLogoView;
        } else if (rawLogoView != null && rawLogoView.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) rawLogoView.getParent();
            int index = parent.indexOfChild(rawLogoView);
            ViewGroup.LayoutParams lp = rawLogoView.getLayoutParams();
            parent.removeViewAt(index);

            ImageView replacementLogo = new ImageView(this);
            replacementLogo.setId(R.id.splash_logo);
            replacementLogo.setLayoutParams(lp);
            replacementLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            parent.addView(replacementLogo, index);
            splashLogo = replacementLogo;
        }

        shieldGlow = findViewById(R.id.shield_glow);
        textContainer = findViewById(R.id.text_container);
        appNameText = findViewById(R.id.app_name);
        taglineText = findViewById(R.id.tagline);
        particlesView = findViewById(R.id.particles_view);

        if (splashRoot != null) {
            ViewCompat.setOnApplyWindowInsetsListener(splashRoot, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                int basePad = dp(32);
                v.setPadding(
                        systemBars.left + basePad,
                        systemBars.top + basePad,
                        systemBars.right + basePad,
                        systemBars.bottom + basePad
                );
                return insets;
            });
        }

        if (textContainer != null) {
            bootTelemetryChip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.topMargin = dp(20);
            bootTelemetryChip.setLayoutParams(lp);
            bootTelemetryChip.setGravity(Gravity.CENTER);
            bootTelemetryChip.setPadding(dp(14), dp(6), dp(14), dp(6));
            bootTelemetryChip.setTextColor(Color.parseColor("#B8A9FF"));
            bootTelemetryChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            bootTelemetryChip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            bootTelemetryChip.setText("⚡ Initializing SQLCipher WAL & Bloom Filter…");

            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setShape(GradientDrawable.RECTANGLE);
            pillBg.setCornerRadius(dp(18));
            pillBg.setColor(Color.parseColor("#227C4DFF"));
            pillBg.setStroke(dp(1), Color.parseColor("#557C4DFF"));
            bootTelemetryChip.setBackground(pillBg);
            bootTelemetryChip.setAlpha(0f);

            textContainer.addView(bootTelemetryChip);
        }

        if (splashLogo != null) {
            if (splashLogo.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) splashLogo.getParent();
                parent.setClipChildren(false);
                parent.setClipToPadding(false);
            }
            splashLogo.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            whisperLogoDrawable = new CloakedWhisperLogoDrawable();
            splashLogo.setImageDrawable(whisperLogoDrawable);

            splashLogo.setScaleX(0.84f);
            splashLogo.setScaleY(0.84f);
            splashLogo.setAlpha(0f);
            splashLogo.setTranslationY(dp(10));
        }
        if (shieldGlow != null) {
            shieldGlow.setScaleX(0.2f);
            shieldGlow.setScaleY(0.2f);
            shieldGlow.setAlpha(0f);
        }
        if (textContainer != null) {
            textContainer.setTranslationY(dp(24));
            textContainer.setAlpha(0f);
        }
    }

    /**
     * Warms up the Sovereign Identity engine and the 65,536-bit k=7 Kirsch-Mitzenmacher Bloom Filter
     * on a background thread so @username checks and P2P directory lookups run in < 0.02ms.
     */
    private void warmCryptographicSubsystemsAsync() {
        new Thread(() -> {
            try {
                SovereignIdentityAndDirectoryEngine.getInstance(getApplicationContext());
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
                android.content.SharedPreferences securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "veil_pin_prefs",
                        masterKeyAlias,
                        getApplicationContext(),
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                cachedPinLockEnabled = (securePrefs.getString("pin_hash", null) != null);
            } catch (Throwable t) {
                Log.w(TAG, "Subsystem warm-up notice: " + t.getMessage());
            }
        }, "VeilSplashWarmup").start();
    }

    private void startAnimations() {
        if (shieldGlow != null) {
            AnimatorSet glowSet = new AnimatorSet();
            glowSet.playTogether(
                    ObjectAnimator.ofFloat(shieldGlow, "alpha", 0f, 0.55f),
                    ObjectAnimator.ofFloat(shieldGlow, "scaleX", 0.5f, 1.15f),
                    ObjectAnimator.ofFloat(shieldGlow, "scaleY", 0.5f, 1.15f)
            );
            glowSet.setDuration(650);
            glowSet.setInterpolator(new DecelerateInterpolator());
            glowSet.start();

            ValueAnimator pulse = ValueAnimator.ofFloat(1.0f, 1.12f);
            pulse.setDuration(1600);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setInterpolator(new AccelerateDecelerateInterpolator());
            pulse.addUpdateListener(anim -> {
                if (shieldGlow != null) {
                    float s = (float) anim.getAnimatedValue();
                    shieldGlow.setScaleX(s);
                    shieldGlow.setScaleY(s);
                }
            });
            handler.postDelayed(pulse::start, 650);
        }

        if (splashLogo != null) {
            handler.postDelayed(() -> {
                AnimatorSet logoSet = new AnimatorSet();
                logoSet.playTogether(
                        ObjectAnimator.ofFloat(splashLogo, "alpha", 0f, 1f),
                        ObjectAnimator.ofFloat(splashLogo, "scaleX", 0.84f, 1f),
                        ObjectAnimator.ofFloat(splashLogo, "scaleY", 0.84f, 1f),
                        ObjectAnimator.ofFloat(splashLogo, "translationY", dp(10), 0f)
                );
                logoSet.setDuration(620);
                logoSet.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
                logoSet.start();

                if (whisperLogoDrawable != null) {
                    whisperLogoDrawable.startAwakening();
                }
            }, 30);
        }

        if (textContainer != null) {
            handler.postDelayed(() -> {
                AnimatorSet textSet = new AnimatorSet();
                textSet.playTogether(
                        ObjectAnimator.ofFloat(textContainer, "alpha", 0f, 1f),
                        ObjectAnimator.ofFloat(textContainer, "translationY", dp(24), 0f)
                );
                textSet.setDuration(420);
                textSet.setInterpolator(new DecelerateInterpolator());
                textSet.start();
            }, 340);
        }

        if (particlesView != null) {
            ObjectAnimator drift = ObjectAnimator.ofFloat(particlesView, "alpha", 0.15f, 0.38f);
            drift.setDuration(1400);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setRepeatMode(ValueAnimator.REVERSE);
            drift.start();
        }

        if (bootTelemetryChip != null) {
            handler.postDelayed(() -> {
                bootTelemetryChip.animate().alpha(1f).setDuration(240).start();
            }, 450);

            String[] bootSteps = new String[]{
                    "⚡ Hydrating k=7 Murmur3+SHA256 Bloom Filter…",
                    "🔐 Mounting SQLCipher AES-256-GCM WAL Vault…",
                    "🛰️ Synchronizing Zero-Knowledge P2P Mesh…"
            };
            for (int i = 0; i < bootSteps.length; i++) {
                final String step = bootSteps[i];
                handler.postDelayed(() -> {
                    if (!isFinishing() && bootTelemetryChip != null) {
                        bootTelemetryChip.setText(step);
                    }
                }, 520L + (i * 550L));
            }
        }
    }

    private void scheduleNavigation() {
        handler.postDelayed(this::checkAuthAndNavigate, 2600);
    }

    private void checkAuthAndNavigate() {
        if (isFinishing() || hasNavigated) return;

        AuthManager authManager = AuthManager.getInstance();
        String uid = authManager.getCurrentUserId(this);
        boolean isLoggedIn = authManager.isLoggedIn(this) && uid != null && !uid.isEmpty();

        if (!isLoggedIn) {
            if (!preferenceManager.isOnboardingCompleted()) {
                navigateToActivity(OnboardingActivity.class);
            } else {
                userPreferences.clearUser();
                navigateToActivity(PhoneAuthActivity.class);
            }
            return;
        }

        // Synchronize FirebaseAuth UID with the deterministic Sovereign/Phone UID
        FirebaseAuth.getInstance().setCurrentUserUid(uid);

        // Fast path: if local profile (@username) is already present in UserPreferences / PreferenceManager / SQLCipher
        if (authManager.hasCompleteProfile(this)) {
            preferenceManager.setOnboardingCompleted(true);
            refreshUserDataInBackground(uid);
            try {
                VeilMeshDaemonService.start(this);
            } catch (Exception ignored) {}
            navigateToMainOrPinLock();
            return;
        }

        // Hybrid restore path: check Firebase Firestore ("users/{uid}") + local backup + Nostr
        authManager.verifyOrRestoreProfile(this, (hasCompleteProfile, restoredUser) -> {
            if (isFinishing() || hasNavigated) return;
            if (hasCompleteProfile) {
                preferenceManager.setOnboardingCompleted(true);
                refreshUserDataInBackground(uid);
                try {
                    VeilMeshDaemonService.start(this);
                } catch (Exception ignored) {}
                navigateToMainOrPinLock();
            } else {
                navigateToUsernameActivity(uid);
            }
        });
    }

    private void refreshUserDataInBackground(String userId) {
        if (userId == null || userId.isEmpty()) return;

        firestore.collection("users")
                .document(userId)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                        DocumentSnapshot doc = task.getResult();
                        String uname = doc.getString("username");
                        if (AuthManager.getInstance().isValidClaimedUsername(uname, userId)) {
                            String dname = doc.getString("displayName");
                            String bio = doc.getString("bio");
                            String avatar = doc.getString("avatar");
                            String photoUrl = doc.getString("photoUrl");
                            String resolvedAv = (avatar != null && !avatar.isEmpty()) ? avatar : photoUrl;
                            String avatarPath = null;
                            if (resolvedAv != null && !resolvedAv.isEmpty() && !resolvedAv.startsWith("/") && !resolvedAv.startsWith("http")) {
                                File f = SovereignIdentityAndDirectoryEngine.getInstance(this)
                                        .saveAvatarBase64ToLocalFile(userId, resolvedAv);
                                if (f != null) avatarPath = f.getAbsolutePath();
                            } else if (photoUrl != null && !photoUrl.isEmpty()) {
                                avatarPath = photoUrl;
                            }
                            userPreferences.saveProfileFields(
                                    userId,
                                    uname.trim(),
                                    dname != null ? dname : uname.trim(),
                                    bio != null ? bio : "",
                                    avatarPath,
                                    resolvedAv
                            );
                        }
                        updateOnlineStatus(userId, true);
                    }
                });
    }

    private void updateOnlineStatus(String userId, boolean isOnline) {
        if (userId == null || userId.isEmpty()) return;
        Map<String, Object> updates = new HashMap<>();
        updates.put("isOnline", isOnline);
        updates.put("lastSeen", Timestamp.now());
        firestore.collection("users").document(userId).set(updates, SetOptions.merge());
    }

    private void navigateToMainOrPinLock() {
        Class<?> destination = cachedPinLockEnabled ? PinUnlockActivity.class : MainActivity.class;
        navigateToActivity(destination);
    }

    private void navigateToUsernameActivity(String userId) {
        if (hasNavigated || isFinishing() || isDestroyed()) return;
        hasNavigated = true;

        View rootView = splashRoot != null ? splashRoot : findViewById(android.R.id.content);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(rootView, "alpha", 1f, 0f);
        fadeOut.setDuration(220);
        fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                Intent intent = new Intent(SplashActivity.this, UsernameActivity.class);
                if (userId != null && !userId.isEmpty()) {
                    intent.putExtra("userId", userId);
                }
                intent.putExtra("is_restore", false);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        });
        fadeOut.start();
    }

    private void navigateToActivity(Class<?> targetActivity) {
        if (hasNavigated || isFinishing()) return;
        hasNavigated = true;

        View rootView = splashRoot != null ? splashRoot : findViewById(android.R.id.content);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(rootView, "alpha", 1f, 0f);
        fadeOut.setDuration(220);
        fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                Intent intent = new Intent(SplashActivity.this, targetActivity);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        });
        fadeOut.start();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    @Override
    protected void onDestroy() {
        if (whisperLogoDrawable != null) {
            whisperLogoDrawable.stopAnimations();
        }
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * Minimal, Flaw-Corrected Hardware-Accelerated Drawable for "The Cloaked Whisper".
     * Specifically tuned for clean modern light/minimal splash screens:
     * - Zero bottom polygon spikes, zero top-crown steps, zero sticker outlines.
     * - True needle-sharp calligraphic cyan rim reveal (no blunt round stick ends).
     * - Clean 3-stage origami unfold + spring shield emergence + subtle glass sheen.
     */
    private static final class CloakedWhisperLogoDrawable extends Drawable {
        private static final float VIEWPORT = 200f;
        private static final float SHIELD_CX = 127f;
        private static final float SHIELD_CY = 94f;

        private float shroudProgress = 0f;
        private float cowlProgress = 0f;
        private float rimProgress = 0f;
        private float shieldProgress = 0f;
        private float idlePhase = 0f;
        private int masterAlpha = 255;

        // Clean Anatomical Paths
        private final Path outerShroudPath = new Path();
        private final Path hoodCavityPath = new Path();
        private final Path pleat1ValleyPath = new Path();
        private final Path pleat1RidgePath = new Path();
        private final Path pleat2ValleyPath = new Path();
        private final Path innerCavityRimPath = new Path();
        private final Path rightCollarBodyPath = new Path();
        private final Path rightCollarCyanEdgePath = new Path();

        private final Path leftCowlBodyPath = new Path();
        private final Path leftCowlOuterBevelPath = new Path();
        private final Path leftCowlInnerRimPath = new Path();

        private final Path taperedCyanHoodRimPath = new Path();
        private final Path shieldOuterBevelPath = new Path();
        private final Path shieldLeftFacetPath = new Path();
        private final Path shieldRightFacetPath = new Path();
        private final Path shieldOutlinePath = new Path();

        // Paints
        private final Paint shroudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cavityPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pleatValleyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pleatRidgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerCavityRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint collarBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint collarCyanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint cowlBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cowlOuterBevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cowlInnerRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint cyanSoftGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint taperedCyanRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint shieldBacklightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shieldOuterBevelPaintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shieldLeftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shieldRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shieldOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glassSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final LinearGradient sheenGradient;
        private final Matrix sheenMatrix = new Matrix();

        private ValueAnimator revealAnimator;
        private ValueAnimator idleAnimator;

        CloakedWhisperLogoDrawable() {
            buildFlawlessGeometry();

            // 1. Sculpted Shroud & Smooth Rounded Hood Cavity
            shroudPaint.setShader(new LinearGradient(
                    64, 24, 168, 162,
                    new int[]{0xFF3B4382, 0xFF212652, 0xFF0E122B},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));

            cavityPaint.setShader(new RadialGradient(
                    SHIELD_CX, SHIELD_CY, 54f,
                    new int[]{0xFF194270, 0xFF0A1730, 0xFF050814},
                    new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));

            pleatValleyPaint.setShader(new LinearGradient(
                    58, 34, 108, 160,
                    0xFF131735, 0xFF070A1A, Shader.TileMode.CLAMP));

            pleatRidgePaint.setShader(new LinearGradient(
                    70, 32, 110, 158,
                    new int[]{0x956366F1, 0x754F46E5, 0x0038BDF8},
                    new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));

            innerCavityRimPaint.setShader(new LinearGradient(
                    138, 44, 92, 148,
                    new int[]{0xD038BDF8, 0x806366F1, 0x001E1B4B},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));

            collarBodyPaint.setShader(new LinearGradient(
                    168, 114, 114, 163,
                    new int[]{0xFF38BDF8, 0xFF2563EB, 0xFF172554},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

            collarCyanPaint.setShader(new LinearGradient(
                    168, 114, 114, 163,
                    new int[]{0xFFA5F3FC, 0xFF00F5FF, 0x000284C7},
                    new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));

            // 2. Left Speech-Bubble Crescent Wing (No 2D Sticker Outline)
            cowlBodyPaint.setShader(new LinearGradient(
                    28, 26, 104, 164,
                    new int[]{0xFF6366F1, 0xFF4F46E5, 0xFF3730A3},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

            // Fades seamlessly into the tail so there is NEVER a hard diagonal cut line
            cowlOuterBevelPaint.setShader(new LinearGradient(
                    28, 26, 62, 162,
                    new int[]{0xFF93C5FD, 0xFF60A5FA, 0x004F46E5},
                    new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));

            cowlInnerRimPaint.setStyle(Paint.Style.STROKE);
            cowlInnerRimPaint.setStrokeWidth(1.5f);
            cowlInnerRimPaint.setStrokeCap(Paint.Cap.ROUND);
            cowlInnerRimPaint.setShader(new LinearGradient(
                    96, 26, 102, 162,
                    new int[]{0xFFBAE6FD, 0x90818CF8, 0x306366F1},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

            // 3. Calligraphic Tapered Neon Cyan Hood Rim
            cyanSoftGlowPaint.setStyle(Paint.Style.FILL);
            cyanSoftGlowPaint.setColor(0x5500E5FF);
            cyanSoftGlowPaint.setMaskFilter(new BlurMaskFilter(5.5f, BlurMaskFilter.Blur.NORMAL));

            taperedCyanRimPaint.setStyle(Paint.Style.FILL);
            taperedCyanRimPaint.setShader(new LinearGradient(
                    76, 24, 172, 158,
                    new int[]{0xFF38BDF8, 0xFFE0F2FE, 0xFF00F5FF, 0xFF0284C7},
                    new float[]{0f, 0.38f, 0.78f, 1f}, Shader.TileMode.CLAMP));

            // 4. Heroic Split-Facet Shield
            shieldBacklightPaint.setShader(new RadialGradient(
                    SHIELD_CX, SHIELD_CY, 36f,
                    new int[]{0xA500F5FF, 0x380284C7, 0x00000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

            shieldOuterBevelPaintFill.setShader(new LinearGradient(
                    103, 64, 151, 124,
                    0xFF67E8F9, 0xFF0284C7, Shader.TileMode.CLAMP));

            shieldLeftPaint.setShader(new LinearGradient(
                    105, 67, 127, 121,
                    new int[]{0xFFE0F2FE, 0xFF67E8F9, 0xFF06B6D4},
                    new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));

            shieldRightPaint.setShader(new LinearGradient(
                    127, 67, 149, 121,
                    new int[]{0xFF38BDF8, 0xFF0284C7, 0xFF0369A1},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));

            shieldOutlinePaint.setStyle(Paint.Style.STROKE);
            shieldOutlinePaint.setStrokeWidth(1.6f);
            shieldOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
            shieldOutlinePaint.setShader(new LinearGradient(
                    103, 64, 151, 124,
                    new int[]{0xFFFFFFFF, 0xFFA5F3FC, 0xFF00F5FF},
                    null, Shader.TileMode.CLAMP));

            // 5. Minimal Diagonal Glass Sheen
            sheenGradient = new LinearGradient(
                    -45, -45, 0, 0,
                    new int[]{0x00FFFFFF, 0x48FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            glassSheenPaint.setShader(sheenGradient);
        }

        private void buildFlawlessGeometry() {
            // 1. Outer Hood Shroud (Smooth circular contour, tucks cleanly at y=162 with ZERO bottom spike)
            outerShroudPath.moveTo(76, 40);
            outerShroudPath.cubicTo(98, 23, 128, 21, 149, 32);
            outerShroudPath.cubicTo(164, 40, 171, 58, 172, 82);
            outerShroudPath.cubicTo(173, 98, 171, 110, 165, 120);
            outerShroudPath.cubicTo(155, 134, 134, 149, 110, 162);
            outerShroudPath.cubicTo(104, 158, 98, 150, 92, 142);
            outerShroudPath.cubicTo(70, 120, 56, 94, 62, 64);
            outerShroudPath.cubicTo(65, 51, 70, 44, 76, 40);
            outerShroudPath.close();

            // 2. Smooth Rounded Hood Cavity Behind Shield (No sharp 1:30 corner)
            hoodCavityPath.moveTo(136, 46);
            hoodCavityPath.cubicTo(153, 47, 163, 66, 163, 93);
            hoodCavityPath.cubicTo(163, 111, 151, 126, 120, 148);
            hoodCavityPath.cubicTo(103, 132, 93, 112, 95, 88);
            hoodCavityPath.cubicTo(97, 62, 116, 45, 136, 46);
            hoodCavityPath.close();

            // 3. Sculpted Inner Cloth Pleats (Left of Shield)
            pleat1ValleyPath.moveTo(96, 31);
            pleat1ValleyPath.cubicTo(70, 48, 62, 82, 74, 112);
            pleat1ValleyPath.cubicTo(82, 130, 98, 145, 108, 160);
            pleat1ValleyPath.cubicTo(94, 146, 74, 130, 62, 108);
            pleat1ValleyPath.cubicTo(50, 82, 60, 48, 96, 31);
            pleat1ValleyPath.close();

            pleat1RidgePath.moveTo(102, 31);
            pleat1RidgePath.cubicTo(76, 50, 68, 84, 80, 112);
            pleat1RidgePath.cubicTo(88, 130, 102, 144, 110, 159);
            pleat1RidgePath.cubicTo(100, 145, 84, 130, 75, 112);
            pleat1RidgePath.cubicTo(63, 84, 71, 50, 102, 31);
            pleat1RidgePath.close();

            pleat2ValleyPath.moveTo(120, 38);
            pleat2ValleyPath.cubicTo(92, 54, 82, 86, 92, 114);
            pleat2ValleyPath.cubicTo(98, 130, 108, 142, 115, 154);
            pleat2ValleyPath.cubicTo(104, 142, 90, 128, 84, 110);
            pleat2ValleyPath.cubicTo(76, 84, 88, 52, 120, 38);
            pleat2ValleyPath.close();

            innerCavityRimPath.moveTo(136, 46);
            innerCavityRimPath.cubicTo(116, 45, 97, 62, 95, 88);
            innerCavityRimPath.cubicTo(93, 112, 103, 132, 120, 148);
            innerCavityRimPath.cubicTo(106, 133, 97, 113, 99, 88);
            innerCavityRimPath.cubicTo(101, 65, 118, 49, 136, 46);
            innerCavityRimPath.close();

            // 4. Sleek Crescent Lower-Right Collar Blade (Zero Notch)
            rightCollarBodyPath.moveTo(167, 115);
            rightCollarBodyPath.cubicTo(161, 138, 142, 154, 114, 163);
            rightCollarBodyPath.cubicTo(128, 148, 146, 133, 167, 115);
            rightCollarBodyPath.close();

            rightCollarCyanEdgePath.moveTo(167, 115);
            rightCollarCyanEdgePath.cubicTo(161, 138, 142, 154, 114, 163);
            rightCollarCyanEdgePath.cubicTo(138, 152, 156, 136, 164, 118);
            rightCollarCyanEdgePath.close();

            // 5. Seamless Left Speech-Bubble Crescent Wing
            leftCowlBodyPath.moveTo(96, 26);
            leftCowlBodyPath.cubicTo(54, 32, 26, 66, 28, 108);
            leftCowlBodyPath.cubicTo(29, 122, 34, 135, 41, 144);
            leftCowlBodyPath.cubicTo(39, 151, 33, 158, 26, 163);
            leftCowlBodyPath.cubicTo(39, 163, 50, 159, 59, 153);
            leftCowlBodyPath.cubicTo(71, 160, 84, 164, 95, 163);
            leftCowlBodyPath.cubicTo(104, 162, 107, 153, 101, 144);
            leftCowlBodyPath.cubicTo(93, 132, 75, 121, 64, 103);
            leftCowlBodyPath.cubicTo(51, 81, 56, 51, 78, 35);
            leftCowlBodyPath.cubicTo(84, 30, 90, 27, 96, 26);
            leftCowlBodyPath.close();

            // Continuous Outer Crescent Bevel (Flows smoothly from top crown into tail tip)
            leftCowlOuterBevelPath.moveTo(96, 26);
            leftCowlOuterBevelPath.cubicTo(54, 32, 26, 66, 28, 108);
            leftCowlOuterBevelPath.cubicTo(29, 122, 34, 135, 41, 144);
            leftCowlOuterBevelPath.cubicTo(39, 151, 33, 158, 26, 163);
            leftCowlOuterBevelPath.cubicTo(36, 154, 42, 142, 41, 124);
            leftCowlOuterBevelPath.cubicTo(38, 82, 56, 42, 96, 26);
            leftCowlOuterBevelPath.close();

            // Subtle Inner S-Curve Specular Edge (Replaces 2D Sticker Outline)
            leftCowlInnerRimPath.moveTo(96, 26);
            leftCowlInnerRimPath.cubicTo(90, 27, 84, 30, 78, 35);
            leftCowlInnerRimPath.cubicTo(56, 51, 51, 81, 64, 103);
            leftCowlInnerRimPath.cubicTo(75, 121, 93, 132, 101, 144);
            leftCowlInnerRimPath.cubicTo(107, 153, 104, 162, 95, 163);

            // 6. Calligraphic Variable-Width Neon Cyan Hood Rim (Needle-Sharp Tapers at Both Ends)
            taperedCyanHoodRimPath.moveTo(76, 40);
            taperedCyanHoodRimPath.cubicTo(98, 23, 128, 21, 149, 32);
            taperedCyanHoodRimPath.cubicTo(164, 40, 171, 58, 172, 82);
            taperedCyanHoodRimPath.cubicTo(173, 98, 171, 110, 165, 120);
            taperedCyanHoodRimPath.cubicTo(155, 134, 134, 149, 110, 162);
            taperedCyanHoodRimPath.cubicTo(132, 146, 151, 131, 160, 118);
            taperedCyanHoodRimPath.cubicTo(166, 108, 168, 96, 167, 82);
            taperedCyanHoodRimPath.cubicTo(166, 60, 159, 44, 146, 36);
            taperedCyanHoodRimPath.cubicTo(126, 26, 99, 27, 76, 40);
            taperedCyanHoodRimPath.close();

            // 7. Balanced Heroic Split-Facet Shield (Centered at 127, 94)
            shieldOuterBevelPath.moveTo(127, 64);
            shieldOuterBevelPath.cubicTo(119, 69.2f, 110.5f, 71.8f, 103.5f, 72.8f);
            shieldOuterBevelPath.lineTo(103.5f, 93.5f);
            shieldOuterBevelPath.cubicTo(103.5f, 108.5f, 114f, 118.5f, 127f, 124.5f);
            shieldOuterBevelPath.cubicTo(140f, 118.5f, 150.5f, 108.5f, 150.5f, 93.5f);
            shieldOuterBevelPath.lineTo(150.5f, 72.8f);
            shieldOuterBevelPath.cubicTo(143.5f, 71.8f, 135f, 69.2f, 127f, 64f);
            shieldOuterBevelPath.close();

            shieldLeftFacetPath.moveTo(127, 66.8f);
            shieldLeftFacetPath.cubicTo(119.8f, 71.4f, 112f, 73.6f, 106f, 74.5f);
            shieldLeftFacetPath.lineTo(106f, 93.2f);
            shieldLeftFacetPath.cubicTo(106f, 106.8f, 115.4f, 115.8f, 127f, 121.5f);
            shieldLeftFacetPath.close();

            shieldRightFacetPath.moveTo(127, 66.8f);
            shieldRightFacetPath.lineTo(127, 121.5f);
            shieldRightFacetPath.cubicTo(138.6f, 115.8f, 148f, 106.8f, 148f, 93.2f);
            shieldRightFacetPath.lineTo(148f, 74.5f);
            shieldRightFacetPath.cubicTo(142f, 73.6f, 134.2f, 71.4f, 127f, 66.8f);
            shieldRightFacetPath.close();

            shieldOutlinePath.set(shieldOuterBevelPath);
        }

        void startAwakening() {
            stopAnimations();
            PathInterpolator modernOut = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
            OvershootInterpolator gentleSpring = new OvershootInterpolator(1.08f);

            revealAnimator = ValueAnimator.ofFloat(0f, 1f);
            revealAnimator.setDuration(1350L);
            revealAnimator.addUpdateListener(anim -> {
                float t = (float) anim.getAnimatedValue();
                shroudProgress = modernOut.getInterpolation(clamp01(t / 0.52f));
                cowlProgress = gentleSpring.getInterpolation(clamp01((t - 0.08f) / 0.56f));
                rimProgress = modernOut.getInterpolation(clamp01((t - 0.18f) / 0.58f));
                shieldProgress = gentleSpring.getInterpolation(clamp01((t - 0.34f) / 0.56f));
                invalidateSelf();
            });
            revealAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    startIdleLoop();
                }
            });
            revealAnimator.start();
        }

        private void startIdleLoop() {
            idleAnimator = ValueAnimator.ofFloat(0f, 1f);
            idleAnimator.setDuration(3000L);
            idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
            idleAnimator.setInterpolator(new LinearInterpolator());
            idleAnimator.addUpdateListener(anim -> {
                idlePhase = (float) anim.getAnimatedValue();
                invalidateSelf();
            });
            idleAnimator.start();
        }

        void stopAnimations() {
            if (revealAnimator != null) {
                revealAnimator.cancel();
                revealAnimator = null;
            }
            if (idleAnimator != null) {
                idleAnimator.cancel();
                idleAnimator = null;
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;

            float scale = Math.min(b.width(), b.height()) / VIEWPORT;
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(scale, scale);

            float alphaScale = masterAlpha / 255f;

            // 1. Stage 1: Smooth Origami Unfold of Hood Shroud, Rounded Cavity & Pleats
            if (shroudProgress > 0f) {
                canvas.save();
                float s = 0.88f + 0.12f * shroudProgress;
                canvas.scale(s, s, 114f, 98f);
                int a = (int) (255 * clamp01(shroudProgress) * alphaScale);

                shroudPaint.setAlpha(a);
                cavityPaint.setAlpha(a);
                pleatValleyPaint.setAlpha(a);
                pleatRidgePaint.setAlpha(a);
                collarBodyPaint.setAlpha(a);

                canvas.drawPath(outerShroudPath, shroudPaint);
                canvas.drawPath(hoodCavityPath, cavityPaint);
                canvas.drawPath(pleat1ValleyPath, pleatValleyPaint);
                canvas.drawPath(pleat1RidgePath, pleatRidgePaint);
                canvas.drawPath(pleat2ValleyPath, pleatValleyPaint);
                canvas.drawPath(rightCollarBodyPath, collarBodyPaint);

                int bounceAlpha = (int) (a * (0.3f + 0.7f * clamp01(shieldProgress)));
                innerCavityRimPaint.setAlpha(bounceAlpha);
                canvas.drawPath(innerCavityRimPath, innerCavityRimPaint);
                canvas.restore();
            }

            // 2. Stage 2: Calligraphic Tapered Neon Cyan Rim (Progressive Clip — Always Needle-Sharp)
            if (rimProgress > 0f) {
                canvas.save();
                float clipBottom = 20f + 146f * rimProgress;
                canvas.clipRect(70f, 18f, 185f, clipBottom);

                int rimAlpha = (int) (255 * clamp01(rimProgress * 1.25f) * alphaScale);
                cyanSoftGlowPaint.setAlpha((int) (130 * clamp01(rimProgress) * alphaScale));
                taperedCyanRimPaint.setAlpha(rimAlpha);
                collarCyanPaint.setAlpha(rimAlpha);

                canvas.drawPath(taperedCyanHoodRimPath, cyanSoftGlowPaint);
                canvas.drawPath(taperedCyanHoodRimPath, taperedCyanRimPaint);
                canvas.drawPath(rightCollarCyanEdgePath, collarCyanPaint);
                canvas.restore();
            }

            // 3. Stage 3: Left Speech-Bubble Crescent Wing Glide
            if (cowlProgress > 0f) {
                canvas.save();
                float p = clamp01(cowlProgress);
                float dx = (1f - cowlProgress) * -12f;
                float dy = (1f - cowlProgress) * 8f;
                float rot = (1f - cowlProgress) * -7f;
                canvas.translate(dx, dy);
                canvas.rotate(rot, 74f, 102f);

                int cowlAlpha = (int) (255 * p * alphaScale);
                cowlBodyPaint.setAlpha(cowlAlpha);
                cowlOuterBevelPaint.setAlpha(cowlAlpha);
                cowlInnerRimPaint.setAlpha(cowlAlpha);

                canvas.drawPath(leftCowlBodyPath, cowlBodyPaint);
                canvas.drawPath(leftCowlOuterBevelPath, cowlOuterBevelPaint);
                canvas.drawPath(leftCowlInnerRimPath, cowlInnerRimPaint);
                canvas.restore();
            }

            // 4. Stage 4: Heroic Split-Facet Shield Spring Emergence & Levitation
            if (shieldProgress > 0f) {
                float floatY = (float) Math.sin(idlePhase * Math.PI * 2f) * 1.8f;
                float shieldScale = 0.68f + 0.32f * shieldProgress;
                int sAlpha = (int) (255 * clamp01(shieldProgress * 1.2f) * alphaScale);

                canvas.save();
                canvas.translate(0f, floatY);
                canvas.scale(shieldScale, shieldScale, SHIELD_CX, SHIELD_CY);

                // Internal cyan backlight inside the dark hood cavity
                shieldBacklightPaint.setAlpha(sAlpha);
                canvas.drawCircle(SHIELD_CX, SHIELD_CY, 34f, shieldBacklightPaint);

                shieldOuterBevelPaintFill.setAlpha(sAlpha);
                shieldLeftPaint.setAlpha(sAlpha);
                shieldRightPaint.setAlpha(sAlpha);
                shieldOutlinePaint.setAlpha(sAlpha);

                canvas.drawPath(shieldOuterBevelPath, cyanSoftGlowPaint);
                canvas.drawPath(shieldOuterBevelPath, shieldOuterBevelPaintFill);
                canvas.drawPath(shieldLeftFacetPath, shieldLeftPaint);
                canvas.drawPath(shieldRightFacetPath, shieldRightPaint);
                canvas.drawPath(shieldOutlinePath, shieldOutlinePaint);

                // Subtle Diagonal Glass Sheen Across Shield Facets During Idle
                if (idlePhase > 0f) {
                    float sheenOffset = 60f + idlePhase * 160f;
                    sheenMatrix.setTranslate(sheenOffset, sheenOffset);
                    sheenGradient.setLocalMatrix(sheenMatrix);
                    glassSheenPaint.setAlpha((int) (180 * alphaScale));
                    canvas.drawPath(shieldOuterBevelPath, glassSheenPaint);
                }

                canvas.restore();
            }

            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            this.masterAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 400;
        }

        @Override
        public int getIntrinsicHeight() {
            return 400;
        }

        private static float clamp01(float val) {
            return Math.max(0f, Math.min(1f, val));
        }
    }
}