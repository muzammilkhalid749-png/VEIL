package com.m3.veil.ui.chat;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.widget.LinearLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.m3.veil.R;
import com.m3.veil.calling.CallAudioRoute;
import com.m3.veil.calling.CallConfig;
import com.m3.veil.calling.CallEndReason;
import com.m3.veil.calling.CallEngine;
import com.m3.veil.calling.CallFingerprints;
import com.m3.veil.calling.CallInfo;
import com.m3.veil.calling.CallParticipant;
import com.m3.veil.calling.CallState;
import com.m3.veil.calling.CallType;
import com.m3.veil.calling.CallVideoDevice;
import com.m3.veil.calling.VeilCallService;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.CallRecord;
import com.m3.veil.model.Persona;
import com.m3.veil.utils.UiAnimator;

import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Call screen backed by {@link CallEngine}. Handles 1:1 and group calls, real
 * screen sharing, mid-call video upgrade consent, verified call E2EE, live
 * duration and picture-in-picture.
 */
public class CallActivity extends AppCompatActivity {

    public static volatile boolean isCallActive = false;

    public static final String EXTRA_CONTACT_ID = "extra_contact_id";
    public static final String EXTRA_CONTACT_NAME = "extra_contact_name";
    public static final String EXTRA_CALL_TYPE = "extra_call_type";
    public static final String EXTRA_CALL_ID = "extra_call_id";
    public static final String EXTRA_IS_INCOMING = "extra_is_incoming";
    public static final String EXTRA_FROM_NOTIFICATION = "extra_from_notification";
    public static final String EXTRA_DECLINE_ON_START = "extra_decline_on_start";
    public static final String EXTRA_GROUP_ID = "extra_group_id";
    public static final String EXTRA_GROUP_NAME = "extra_group_name";
    public static final String EXTRA_PARTICIPANT_IDS = "extra_participant_ids";

    private static final int REQ_PERMISSIONS = 101;

    private String contactId;
    private String contactName;
    private String callType = "audio";
    private boolean isIncoming;
    private boolean isGroup;
    private String groupId;
    private String groupName;
    private String[] participantIds;
    private String callId;
    private String currentUserId = "local_user";
    private String currentUsername = null;

    public static boolean isSelfName(String name) {
        if (name == null) return true;
        String clean = name.trim();
        return clean.isEmpty() || "Me".equalsIgnoreCase(clean) || "You".equalsIgnoreCase(clean)
                || "local_user".equalsIgnoreCase(clean) || "null".equalsIgnoreCase(clean);
    }

    private String getResolvedUsername() {
        if (currentUsername != null && !isSelfName(currentUsername)) return currentUsername;
        try {
            Persona persona = VeilFeatureManager.getInstance(this).getActivePersona();
            if (persona != null && persona.getDisplayName() != null && !isSelfName(persona.getDisplayName())) {
                currentUsername = persona.getDisplayName().trim();
                return currentUsername;
            }
        } catch (Throwable ignored) {}
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                String fName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
                if (fName != null && !fName.trim().isEmpty() && !isSelfName(fName)) {
                    currentUsername = fName.trim();
                    return currentUsername;
                }
            }
        } catch (Throwable ignored) {}
        return "Veil User";
    }

    private TextView tvContactName;
    private TextView tvStatus;
    private TextView tvSecurityBadge;
    private ImageView ivAvatar;
    private ImageView btnEndCall;
    private View pulseRing;
    private View topCallInfo;
    private View callControlsContainer;
    private View btnToggleMic;
    private View btnToggleSpeaker;
    private View btnToggleVideo;
    private ImageView ivToggleMic;
    private ImageView ivToggleSpeaker;
    private ImageView ivToggleVideo;
    private TextView tvToggleMic;
    private TextView tvToggleSpeaker;
    private TextView tvToggleVideo;
    private View layoutAcceptCall;
    private ImageView btnAcceptCall;
    private TextView tvEndCallLabel;
    private View btnSwitchCamera;
    private View callEffectsRow;
    private TextView btnVideoMasking;
    private TextView btnVoiceMasking;
    private TextView btnScreenShare;
    private TextView btnCallMore;
    private boolean pendingVideoAcceptOnPermission = false;

    private SurfaceViewRenderer remoteVideoView;
    private SurfaceViewRenderer localVideoView;
    private CardView cardLocalVideo;
    private View videoScrimOverlay;
    private View avatarContainer;

    private boolean isMuted;
    private boolean isSpeakerOn;
    private boolean isVideoActive;
    private boolean isConnected;
    private boolean accepted;
    private boolean finishingCall;

    private CallEngine engine;
    private CallState lastState = CallState.NONE;
    private VideoTrack attachedRemoteTrack;
    private VideoTrack attachedLocalTrack;
    private boolean isVideoFeedsSwapped = false;

    private long callStartedAtMs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private ObjectAnimator pulseAnimator;

    private MediaPlayer ringtonePlayer;
    private ToneGenerator ringbackTone;
    private Runnable ringbackLoop;
    private PowerManager.WakeLock proximityWakeLock;
    private BottomSheetDialog activeSheet;
    private boolean pendingVideoToggleOnPermission;

    private void showSheetSafely(BottomSheetDialog sheet) {
        if (sheet == null || isFinishing() || isDestroyed()) return;
        dismissActiveSheet();
        activeSheet = sheet;
        try {
            sheet.show();
        } catch (Throwable ignored) { }
    }

    private void dismissActiveSheet() {
        if (activeSheet != null) {
            try {
                if (activeSheet.isShowing()) {
                    activeSheet.dismiss();
                }
            } catch (Throwable ignored) { }
            activeSheet = null;
        }
    }

    private final ActivityResultLauncher<Intent> screenShareLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    if (engine != null && engine.startScreenShare(result.getData())) {
                        toast("Screen sharing started");
                    } else {
                        toast("Screen sharing unavailable");
                    }
                } else {
                    toast("Screen sharing cancelled");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isCallActive = true;
        applyLockScreenFlags();

        UiAnimator.enableEdgeToEdge(this, findViewById(android.R.id.content));
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
        setContentView(R.layout.activity_call);

        readExtras(getIntent());
        resolveIdentity();
        initializeViews();

        if (engine == null) {
            engine = CallEngine.getInstance(this);
            engine.init(currentUserId);
            engine.setDisplayName(currentUsername);
            engine.addListener(engineListener);
        }

        VeilCallService.start(this);

        ensurePermissions();

        if (getIntent().getBooleanExtra(EXTRA_DECLINE_ON_START, false)) {
            engine.decline(CallEndReason.DECLINED);
            finish();
            return;
        }

        startPulseAnimation();

        if (isIncoming) {
            showIncomingUi();
        } else {
            showOutgoingUi();
            startOutgoingCall();
        }
        updateUiFromEngine(engine.getCallInfo());
        startTimerLoop();
    }

    private void applyLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, null);
            }
        } catch (Throwable ignored) { }
    }

    private void readExtras(Intent intent) {
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID);
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME);
        if (contactName == null || isSelfName(contactName)) {
            if (contactId != null && !contactId.isEmpty() && !contactId.equals(currentUserId)) {
                try {
                    com.m3.veil.model.Chat c = EncryptedChatDatabase.getInstance(this).getChat(contactId);
                    if (c != null && c.getName() != null && !isSelfName(c.getName())) {
                        contactName = c.getName().trim();
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (contactName == null || isSelfName(contactName)) contactName = "Veil Contact";
        String type = intent.getStringExtra(EXTRA_CALL_TYPE);
        if (type != null) callType = type;
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false);
        groupId = intent.getStringExtra(EXTRA_GROUP_ID);
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME);
        participantIds = intent.getStringArrayExtra(EXTRA_PARTICIPANT_IDS);
        isGroup = groupId != null || participantIds != null;
        String passed = intent.getStringExtra(EXTRA_CALL_ID);
        callId = (passed != null && !passed.isEmpty()) ? passed : UUID.randomUUID().toString();
    }

    private void resolveIdentity() {
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
        currentUsername = getResolvedUsername();
    }

    private void initializeViews() {
        tvContactName = findViewById(R.id.callContactName);
        tvStatus = findViewById(R.id.callStatusText);
        tvSecurityBadge = findViewById(R.id.tvCallSecurityBadge);
        ivAvatar = findViewById(R.id.callAvatar);
        btnEndCall = findViewById(R.id.btnEndCall);
        pulseRing = findViewById(R.id.avatarPulseRing);
        topCallInfo = findViewById(R.id.topCallInfo);
        callControlsContainer = findViewById(R.id.callControlsContainer);
        btnToggleMic = findViewById(R.id.btnToggleMic);
        btnToggleSpeaker = findViewById(R.id.btnToggleSpeaker);
        btnToggleVideo = findViewById(R.id.btnToggleVideo);
        ivToggleMic = findViewById(R.id.ivToggleMic);
        ivToggleSpeaker = findViewById(R.id.ivToggleSpeaker);
        ivToggleVideo = findViewById(R.id.ivToggleVideo);
        tvToggleMic = findViewById(R.id.tvToggleMic);
        tvToggleSpeaker = findViewById(R.id.tvToggleSpeaker);
        tvToggleVideo = findViewById(R.id.tvToggleVideo);
        layoutAcceptCall = findViewById(R.id.layoutAcceptCall);
        btnAcceptCall = findViewById(R.id.btnAcceptCall);
        tvEndCallLabel = findViewById(R.id.tvEndCallLabel);
        remoteVideoView = findViewById(R.id.remoteVideoView);
        localVideoView = findViewById(R.id.localVideoView);
        cardLocalVideo = findViewById(R.id.cardLocalVideo);
        videoScrimOverlay = findViewById(R.id.videoScrimOverlay);
        avatarContainer = findViewById(R.id.avatarContainer);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        callEffectsRow = findViewById(R.id.callEffectsRow);
        btnVideoMasking = findViewById(R.id.btnVideoMasking);
        btnVoiceMasking = findViewById(R.id.btnVoiceMasking);
        btnScreenShare = findViewById(R.id.btnScreenShare);
        btnCallMore = findViewById(R.id.btnCallMore);

        if (remoteVideoView != null) {
            remoteVideoView.setZOrderMediaOverlay(false);
            remoteVideoView.setEnableHardwareScaler(true);
            remoteVideoView.setMirror(false);
        }
        if (localVideoView != null) {
            localVideoView.setZOrderMediaOverlay(true);
            localVideoView.setEnableHardwareScaler(true);
            localVideoView.setMirror(true);
        }
        if ("video".equalsIgnoreCase(callType)) {
            if (remoteVideoView != null) {
                remoteVideoView.setVisibility(View.VISIBLE);
            }
            if (cardLocalVideo != null) {
                cardLocalVideo.setVisibility(View.VISIBLE);
            }
            if (btnVideoMasking != null) {
                btnVideoMasking.setVisibility(View.VISIBLE);
            }
        }

        tvContactName.setText(isGroup && groupName != null ? groupName : contactName);

        btnEndCall.setOnClickListener(v -> onEndClicked());
        if (btnAcceptCall != null) btnAcceptCall.setOnClickListener(v -> acceptCall());
        if (btnToggleMic != null) {
            btnToggleMic.setOnClickListener(v -> toggleMute());
        }
        if (btnToggleSpeaker != null) {
            btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());
            btnToggleSpeaker.setOnLongClickListener(v -> {
                showAudioRoutePickerSheet();
                return true;
            });
        }
        if (cardLocalVideo != null) {
            cardLocalVideo.setOnClickListener(v -> toggleSwapVideoFeeds());
        }
        if (localVideoView != null) {
            localVideoView.setOnClickListener(v -> toggleSwapVideoFeeds());
        }
        if (btnToggleVideo != null) {
            btnToggleVideo.setOnClickListener(v -> toggleVideo());
        }
        if (btnSwitchCamera != null) btnSwitchCamera.setOnClickListener(v -> {
            if (engine != null) engine.switchCamera();
        });
        if (btnVideoMasking != null) {
            btnVideoMasking.setOnClickListener(v -> showTrustRevealDialog());
        }
        if (btnVoiceMasking != null) {
            btnVoiceMasking.setOnClickListener(v -> showVoiceFilterDialog());
        }
        if (btnScreenShare != null) {
            btnScreenShare.setOnClickListener(v -> {
                if (engine != null && engine.isScreenSharing()) {
                    engine.stopScreenShare();
                } else {
                    requestScreenShare();
                }
            });
        }
        if (btnCallMore != null) {
            btnCallMore.setOnClickListener(v -> showVideoOptions());
        }
        if (tvSecurityBadge != null) tvSecurityBadge.setOnClickListener(v -> showE2eeDialog());
    }

    private void ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            missing.add("android.permission.POST_NOTIFICATIONS");
        }
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this,
                "android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            missing.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    // ---- call entry ----------------------------------------------------------

    private void startOutgoingCall() {
        CallType type = "video".equalsIgnoreCase(callType) ? CallType.VIDEO : CallType.AUDIO;
        if (isGroup && participantIds != null && participantIds.length > 0) {
            List<CallEngine.Target> targets = new ArrayList<>();
            for (String id : participantIds) {
                if (id == null || id.equals(contactId)) continue;
                targets.add(new CallEngine.Target(id, id));
            }
            targets.add(0, new CallEngine.Target(contactId, contactName));
            engine.startGroupCall(targets, groupId, groupName, type, callId);
        } else {
            engine.startCall(contactId, contactName, type, callId);
        }
        playRingback();
    }

    private void acceptCall() {
        accepted = true;
        if (layoutAcceptCall != null) layoutAcceptCall.setVisibility(View.GONE);
        if (btnEndCall != null) btnEndCall.setVisibility(View.VISIBLE);
        stopRingtone();
        if (engine != null) engine.accept();
    }

    private void onEndClicked() {
        if (finishingCall) return;
        finishingCall = true;
        stopRingtoneAndRingback();
        if (layoutAcceptCall != null && layoutAcceptCall.getVisibility() == View.VISIBLE) {
            if (engine != null) engine.decline(CallEndReason.DECLINED);
        } else if (engine != null) {
            engine.hangup(CallEndReason.LOCAL_HANGUP);
        }
        finish();
    }

    // ---- engine listener -----------------------------------------------------

    private final CallEngine.Listener engineListener = new CallEngine.Listener() {
        @Override public void onStateChanged(CallState state, CallInfo info) {
            runOnUiThread(() -> {
                lastState = state;
                if (state == CallState.CONNECTED_LONELY || state == CallState.ACTIVE
                        || state == CallState.REJOINING) {
                    if (!isConnected) onConnected();
                }
                updateUiFromEngine(info);
            });
        }

        @Override public void onCallInfoChanged(CallInfo info) {
            runOnUiThread(() -> updateUiFromEngine(info));
        }

        @Override public void onParticipantChanged(CallParticipant participant) {
            runOnUiThread(() -> {
                if (participant != null && participant.reaction != null
                        && System.currentTimeMillis() - participant.reactionAtMs < 2500L) {
                    String who = (participant.self || (participant.peerId != null && participant.peerId.equals(currentUserId)))
                            ? "You" : (participant.name != null ? participant.name : "Peer");
                    toast(who + " reacted " + participant.reaction);
                }
                updateUiFromEngine(engine.getCallInfo());
            });
        }

        @Override public void onRemoteVideoTrack(String peerId, VideoTrack track, boolean screen) {
            runOnUiThread(() -> attachRemoteVideo(track));
        }

        @Override public void onRemoteVideoTrackRemoved(String peerId, boolean screen) {
            runOnUiThread(() -> {
                if (attachedRemoteTrack != null && remoteVideoView != null) {
                    try { attachedRemoteTrack.removeSink(remoteVideoView); } catch (Throwable ignored) { }
                    attachedRemoteTrack = null;
                }
                if (remoteVideoView != null) remoteVideoView.setVisibility(View.GONE);
                if (videoScrimOverlay != null) videoScrimOverlay.setVisibility(View.GONE);
                if (avatarContainer != null) avatarContainer.setVisibility(View.VISIBLE);
            });
        }

        @Override public void onTopologyChanged(CallEngine.CallTopology topology) {
            runOnUiThread(() -> {
                if (topology == CallEngine.CallTopology.SFRAME_RELAY_OVERLAY) {
                    toast("Scaled to E2EE SFrame Group Mesh (5+ peers)");
                }
            });
        }

        @Override public void onActiveSpeakerChanged(String peerId, float audioLevel) {
            runOnUiThread(() -> {
                if (pulseRing != null && isCallActive) {
                    if (peerId != null && audioLevel > 0.04f) {
                        pulseRing.setVisibility(View.VISIBLE);
                        float s = 1.0f + Math.min(0.35f, audioLevel * 1.5f);
                        pulseRing.setScaleX(s);
                        pulseRing.setScaleY(s);
                        pulseRing.setAlpha(0.6f);
                    } else {
                        pulseRing.setScaleX(1.0f);
                        pulseRing.setScaleY(1.0f);
                        pulseRing.setVisibility(View.GONE);
                    }
                }
            });
        }

        @Override public void onVideoUpgradeRequested(String peerId, String peerName) {
            runOnUiThread(() -> showVideoUpgradeConsentSheet(peerName));
        }

        @Override public void onVideoUpgradeResponded(String peerId, String peerName, boolean accepted) {
            runOnUiThread(() -> {
                String who = (peerName != null && !peerName.trim().isEmpty()) ? peerName : "Peer";
                if (accepted) {
                    callType = "video";
                    isVideoActive = true;
                    updateToggle(btnToggleVideo, ivToggleVideo, tvToggleVideo, true, "Video");
                    toast(who + " accepted video call");
                } else {
                    isVideoActive = false;
                    updateToggle(btnToggleVideo, ivToggleVideo, tvToggleVideo, false, "Video");
                    toast(who + " declined video call request");
                }
                if (engine != null) updateUiFromEngine(engine.getCallInfo());
            });
        }

        @Override public void onCallEnded(CallEndReason reason, CallInfo info) {
            runOnUiThread(() -> {
                if (remoteVideoView != null) {
                    CallVideoDevice.detachRenderer(remoteVideoView, attachedRemoteTrack);
                    attachedRemoteTrack = null;
                }
                if (localVideoView != null) {
                    CallVideoDevice.detachRenderer(localVideoView, attachedLocalTrack);
                    attachedLocalTrack = null;
                }
                stopRingtoneAndRingback();
                toast(reason != null ? reason.label : "Call ended");
                saveCallRecord(info, reason);
                finish();
            });
        }

        @Override public void onAudioRouteChanged(CallAudioRoute route) {
            runOnUiThread(() -> {
                isSpeakerOn = route == CallAudioRoute.SPEAKER;
                updateToggle(btnToggleSpeaker, ivToggleSpeaker, tvToggleSpeaker, isSpeakerOn, "Speaker");
                applyProximityPolicy();
            });
        }

        @Override public void onE2eeChanged(boolean enabled, String safetyNumber) {
            runOnUiThread(() -> updateSecurityBadge(enabled, safetyNumber));
        }

        @Override public void onE2eeFingerprintChanged(String peerId, String safetyNumber) {
            runOnUiThread(() -> {
                if (isFinishing()) return;
                BottomSheetDialog sheet = new BottomSheetDialog(CallActivity.this);
                float dp = getResources().getDisplayMetrics().density;
                LinearLayout root = new LinearLayout(CallActivity.this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding((int) (24 * dp), (int) (20 * dp), (int) (24 * dp), (int) (24 * dp));

                TextView tvTitle = new TextView(CallActivity.this);
                tvTitle.setText("⚠️ Security Code Changed");
                tvTitle.setTextSize(18f);
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvTitle.setTextColor(ContextCompat.getColor(CallActivity.this, R.color.textPrimary));
                root.addView(tvTitle);

                TextView tvMsg = new TextView(CallActivity.this);
                tvMsg.setText("The end-to-end call security code for this peer has changed. Verify the safety number to rule out interception.");
                tvMsg.setTextSize(14f);
                tvMsg.setTextColor(ContextCompat.getColor(CallActivity.this, R.color.textSecondary));
                tvMsg.setPadding(0, (int) (8 * dp), 0, (int) (20 * dp));
                root.addView(tvMsg);

                MaterialButton btnReview = new MaterialButton(CallActivity.this);
                btnReview.setText("Review Security Code");
                btnReview.setCornerRadius((int) (12 * dp));
                btnReview.setOnClickListener(v -> {
                    sheet.dismiss();
                    showE2eeDialog();
                });
                root.addView(btnReview);

                MaterialButton btnDismiss = new MaterialButton(CallActivity.this);
                btnDismiss.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                btnDismiss.setElevation(0);
                btnDismiss.setStrokeWidth(0);
                btnDismiss.setText("Dismiss");
                btnDismiss.setTextColor(ContextCompat.getColor(CallActivity.this, R.color.textSecondary));
                btnDismiss.setOnClickListener(v -> sheet.dismiss());
                root.addView(btnDismiss);

                sheet.setContentView(root);
                showSheetSafely(sheet);
            });
        }

        @Override public void onError(String message) {
            runOnUiThread(() -> toast(message));
        }
    };

    private void onConnected() {
        isConnected = true;
        accepted = true;
        callStartedAtMs = System.currentTimeMillis();
        stopRingtoneAndRingback();
        if (pulseRing != null) pulseRing.setVisibility(View.GONE);
        if (layoutAcceptCall != null) layoutAcceptCall.setVisibility(View.GONE);
        if (btnEndCall != null) btnEndCall.setVisibility(View.VISIBLE);
        applyProximityPolicy();
        if (engine != null) {
            updateSecurityBadge(engine.isE2eeEnabled(), engine.getSafetyNumber());
            String peerToLookup = contactId != null ? contactId : engine.getPrimaryPeerId();
            if (peerToLookup != null) {
                VideoTrack remoteTrack = engine.getRemoteVideoTrack(peerToLookup);
                if (remoteTrack != null) {
                    attachRemoteVideo(remoteTrack);
                }
            }
        }
    }

    private void updateUiFromEngine(CallInfo info) {
        if (info == null) return;
        isMuted = info.isMuted();
        isSpeakerOn = info.isSpeakerOn();
        isVideoActive = info.isVideoEnabled() || info.isScreenSharing();

        if (info.getState() != CallState.NONE) {
            tvContactName.setText(info.isGroup() && info.getGroupName() != null
                    ? info.getGroupName() : info.getPeerName());
        }

        updateToggle(btnToggleMic, ivToggleMic, tvToggleMic, isMuted, "Mute");
        updateToggle(btnToggleSpeaker, ivToggleSpeaker, tvToggleSpeaker, isSpeakerOn, "Speaker");
        updateToggle(btnToggleVideo, ivToggleVideo, tvToggleVideo, isVideoActive, "Video");

        if (info.isVideoEnabled() && cardLocalVideo != null) {
            cardLocalVideo.setVisibility(View.VISIBLE);
            attachLocalVideo(engine.getParticipant(currentUserId));
        } else if (cardLocalVideo != null) {
            cardLocalVideo.setVisibility(View.GONE);
        }
        if (attachedRemoteTrack == null && engine != null) {
            String peerToLookup = contactId != null ? contactId : engine.getPrimaryPeerId();
            if (peerToLookup != null) {
                VideoTrack remoteTrack = engine.getRemoteVideoTrack(peerToLookup);
                if (remoteTrack != null) {
                    attachRemoteVideo(remoteTrack);
                }
            }
        }
        if (btnSwitchCamera != null) {
            btnSwitchCamera.setVisibility(isVideoActive && !info.isScreenSharing() ? View.VISIBLE : View.GONE);
        }
        if (btnVideoMasking != null) {
            btnVideoMasking.setVisibility(View.VISIBLE);
            boolean maskActive = engine != null && (engine.isVideoBlurEnabled()
                    || engine.isVideoSilhouetteMode()
                    || engine.getArMaskMode() != com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE);
            btnVideoMasking.setBackgroundResource(maskActive ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            btnVideoMasking.setTextColor(ContextCompat.getColor(this,
                    maskActive ? R.color.colorPrimary : R.color.colorTextPrimary));
        }
        if (btnVoiceMasking != null && engine != null) {
            boolean voiceActive = engine.getVoiceFilter() != com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter.NATURAL;
            btnVoiceMasking.setBackgroundResource(voiceActive ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            btnVoiceMasking.setTextColor(ContextCompat.getColor(this,
                    voiceActive ? R.color.colorPrimary : R.color.colorTextPrimary));
            btnVoiceMasking.setText(voiceActive ? "🎙️ " + engine.getVoiceFilter().title : "🎙️ Voice FX");
        }
        if (btnScreenShare != null) {
            boolean sharing = info.isScreenSharing();
            btnScreenShare.setBackgroundResource(sharing ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            btnScreenShare.setTextColor(ContextCompat.getColor(this,
                    sharing ? R.color.colorPrimary : R.color.colorTextPrimary));
            btnScreenShare.setText(sharing ? "📱 Stop Share" : "📱 Share Screen");
        }

        int participantCount = info.getParticipantCount();
        if (info.getState() == CallState.REJOINING) {
            tvStatus.setText("Reconnecting\u2026");
        } else if (info.isGroup() && isConnected) {
            tvStatus.setText(participantCount + " in call");
        } else if (!isConnected) {
            switch (info.getState()) {
                case CALLING:
                    tvStatus.setText("Ringing\u2026");
                    break;
                case PRE_ACCEPT_RECEIVED:
                    tvStatus.setText("Ringing (delivered)\u2026");
                    break;
                case RECEIVED_CALL:
                    tvStatus.setText("Incoming " + (info.getType() == CallType.VIDEO ? "video call" : "call"));
                    break;
                case ACCEPT_SENT:
                case ACCEPT_RECEIVED:
                    tvStatus.setText("Connecting\u2026");
                    break;
                case REJOINING:
                    tvStatus.setText("Reconnecting\u2026");
                    break;
                default:
                    break;
            }
        } else {
            updateDurationText(info);
        }

        if (info.isVideoUpgradePending() && engine != null) {
            tvStatus.setText(engine.isLocalVideoUpgradeRequested()
                    ? "Waiting for video approval\u2026" : "Video request received");
        }
        updateSecurityBadge(info.isE2eeEnabled(), info.getSafetyNumber());
    }

    private void updateDurationText(CallInfo info) {
        long seconds = info.getDurationMs() / 1000L;
        String suffix = info.isScreenSharing() ? " \u2022 sharing screen"
                : info.isLowDataMode() ? " \u2022 low data" : "";
        tvStatus.setText(String.format(Locale.US, "%02d:%02d%s", seconds / 60, seconds % 60, suffix));
    }

    private void updateToggle(View button, ImageView icon, TextView label, boolean active, String name) {
        if (label != null) label.setTextColor(ContextCompat.getColor(this,
                active ? R.color.colorPrimary : R.color.colorTextPrimary));
        if (icon != null) icon.setColorFilter(ContextCompat.getColor(this,
                active ? R.color.colorPrimary : R.color.colorTextPrimary));
        if (button != null) button.setAlpha(active ? 1f : 0.85f);
    }

    private void updateSecurityBadge(boolean enabled, String safetyNumber) {
        if (tvSecurityBadge == null) return;
        if (enabled) {
            tvSecurityBadge.setVisibility(View.VISIBLE);
            String emojis = engine != null ? engine.getSafetyEmojis() : null;
            boolean verified = engine != null && engine.isFingerprintVerified();
            if (emojis != null && !emojis.isEmpty()) {
                tvSecurityBadge.setText(emojis);
            } else {
                tvSecurityBadge.setText(verified ? "\uD83D\uDD12 Verified" : "\uD83D\uDD12 E2EE");
            }
            tvSecurityBadge.setTextColor(ContextCompat.getColor(this,
                    verified ? R.color.colorPrimary : R.color.colorTextPrimary));
        } else if (safetyNumber != null) {
            tvSecurityBadge.setVisibility(View.VISIBLE);
            tvSecurityBadge.setText("\u26A0 Verify");
            tvSecurityBadge.setTextColor(ContextCompat.getColor(this, R.color.colorError));
        } else {
            tvSecurityBadge.setVisibility(View.GONE);
        }
    }

    private void showE2eeDialog() {
        if (engine == null) return;
        boolean encrypted = engine.isE2eeEnabled();
        String safetyNumber = engine.getSafetyNumber();
        String safetyEmojis = engine.getSafetyEmojis();
        boolean verified = engine.isFingerprintVerified();
        CallFingerprints.Status status = engine.getFingerprintStatus();

        StringBuilder body = new StringBuilder();
        if (encrypted) {
            if (safetyEmojis != null && !safetyEmojis.isEmpty()) {
                body.append("Encryption Key Emoji:\n")
                        .append(safetyEmojis)
                        .append("\n\n")
                        .append("These 4 emoji are derived from the encryption key for this call. If they match the emoji on ")
                        .append(contactName != null ? contactName : "your contact")
                        .append("'s screen, this call is 100% secure.\n\n");
            }
            body.append("Safety number:\n")
                    .append(safetyNumber != null ? safetyNumber : "\u2026")
                    .append("\n\n")
                    .append(verified ? "\u2705 Marked as verified." : "\u26A0 Not verified yet.");
            if (status == CallFingerprints.Status.CHANGED) {
                body.append("\n\u26A0 This code differs from the previously stored one.");
            }
            body.append("\n\nCompare this with your contact out-of-band to rule out interception.");
        } else {
            body.append("This call is protected by transport encryption only (DTLS-SRTP). ")
                    .append("Frame-level E2EE is unavailable on this build.");
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * dp), (int) (18 * dp), (int) (24 * dp), (int) (24 * dp));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🔒 End-to-End Call Security");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(tvTitle);

        if (encrypted && safetyEmojis != null && !safetyEmojis.isEmpty()) {
            TextView tvEmoji = new TextView(this);
            tvEmoji.setText(safetyEmojis);
            tvEmoji.setTextSize(32f);
            tvEmoji.setGravity(android.view.Gravity.CENTER);
            tvEmoji.setPadding(0, (int) (14 * dp), 0, (int) (10 * dp));
            root.addView(tvEmoji);
        }

        if (safetyNumber != null) {
            TextView tvNum = new TextView(this);
            tvNum.setText(safetyNumber);
            tvNum.setTextSize(15f);
            tvNum.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tvNum.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
            tvNum.setGravity(android.view.Gravity.CENTER);
            tvNum.setPadding(0, (int) (10 * dp), 0, (int) (14 * dp));
            root.addView(tvNum);
        }

        TextView tvBody = new TextView(this);
        tvBody.setText(body.toString());
        tvBody.setTextSize(13f);
        tvBody.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvBody.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(tvBody);

        if (encrypted && safetyNumber != null) {
            MaterialButton btnVerify = new MaterialButton(this);
            btnVerify.setText(verified ? "Clear Verification" : "Mark as Verified");
            btnVerify.setCornerRadius((int) (12 * dp));
            btnVerify.setOnClickListener(v -> {
                sheet.dismiss();
                engine.setFingerprintVerified(!verified);
                toast(verified ? "Verification cleared" : "Marked as verified");
            });
            root.addView(btnVerify);
        }

        MaterialButton btnClose = new MaterialButton(this);
        btnClose.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnClose.setElevation(0);
        btnClose.setStrokeWidth(0);
        btnClose.setText("Close");
        btnClose.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        btnClose.setOnClickListener(v -> sheet.dismiss());
        root.addView(btnClose);

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    // ---- controls ------------------------------------------------------------

    private void toggleMute() {
        if (engine == null) return;
        engine.setMuted(!isMuted);
        isMuted = !isMuted;
        updateToggle(btnToggleMic, ivToggleMic, tvToggleMic, isMuted, "Mute");
    }

    private void toggleSpeaker() {
        if (engine == null) return;
        engine.setSpeakerOn(!isSpeakerOn);
        isSpeakerOn = !isSpeakerOn;
        updateToggle(btnToggleSpeaker, ivToggleSpeaker, tvToggleSpeaker, isSpeakerOn, "Speaker");
        applyProximityPolicy();
    }

    private void toggleVideo() {
        if (engine == null) return;
        CallInfo info = engine.getCallInfo();
        boolean isCurrentlyAudioOnly = (info != null && info.getType() == CallType.AUDIO) && !engine.isVideoEnabled();
        if (isCurrentlyAudioOnly) {
            if (engine.isLocalVideoUpgradeRequested()) {
                engine.setVideoEnabled(false);
                updateToggle(btnToggleVideo, ivToggleVideo, tvToggleVideo, false, "Video");
                toast("Cancelled video call request");
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingVideoToggleOnPermission = true;
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
                return;
            }
            engine.setVideoEnabled(true);
            updateToggle(btnToggleVideo, ivToggleVideo, tvToggleVideo, true, "Requesting…");
            toast("Requesting video from " + (contactName != null ? contactName : "peer") + "\u2026");
            return;
        }

        boolean enable = !isVideoActive;
        if (enable && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingVideoToggleOnPermission = true;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
            return;
        }
        engine.setVideoEnabled(enable);
        isVideoActive = enable;
        updateToggle(btnToggleVideo, ivToggleVideo, tvToggleVideo, isVideoActive, "Video");
    }

    private void showVideoUpgradeConsentSheet(String peerName) {
        if (isFinishing() || isDestroyed() || engine == null) return;
        String who = (peerName != null && !peerName.trim().isEmpty()) ? peerName
                : (contactName != null ? contactName : "Your contact");

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setCancelable(false);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * dp), (int) (20 * dp), (int) (24 * dp), (int) (24 * dp));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📹 Video Call Request");
        tvTitle.setTextSize(19f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        root.addView(tvTitle);

        TextView tvMsg = new TextView(this);
        tvMsg.setText(who + " wants to switch this audio call to a video call. Your camera will only turn on if you accept.");
        tvMsg.setTextSize(14f);
        tvMsg.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        tvMsg.setPadding(0, (int) (8 * dp), 0, (int) (20 * dp));
        root.addView(tvMsg);

        MaterialButton btnAccept = new MaterialButton(this);
        btnAccept.setText("Accept & Turn On Camera");
        btnAccept.setCornerRadius((int) (12 * dp));
        btnAccept.setOnClickListener(v -> {
            sheet.dismiss();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingVideoAcceptOnPermission = true;
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
                return;
            }
            callType = "video";
            isVideoActive = true;
            if (engine != null) {
                engine.respondVideoUpgrade(true);
                updateUiFromEngine(engine.getCallInfo());
            }
        });
        root.addView(btnAccept);

        MaterialButton btnDecline = new MaterialButton(this);
        btnDecline.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        btnDecline.setElevation(0);
        btnDecline.setStrokeWidth(0);
        btnDecline.setText("Decline (Stay on Audio)");
        btnDecline.setTextColor(ContextCompat.getColor(this, R.color.colorError));
        btnDecline.setOnClickListener(v -> {
            sheet.dismiss();
            if (engine != null) {
                engine.respondVideoUpgrade(false);
                updateUiFromEngine(engine.getCallInfo());
            }
        });
        root.addView(btnDecline);

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    private void showVideoOptions() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("Reactions & Call Options");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (14 * dp));
        root.addView(title);

        // Quick Emoji Reactions Row
        LinearLayout reactionsRow = new LinearLayout(this);
        reactionsRow.setOrientation(LinearLayout.HORIZONTAL);
        reactionsRow.setGravity(android.view.Gravity.CENTER);
        reactionsRow.setPadding(0, 0, 0, (int) (16 * dp));
        String[] emojis = {"👍", "❤️", "😂", "🔥", "👏", "🎉"};
        for (String emoji : emojis) {
            TextView tvEmoji = new TextView(this);
            tvEmoji.setText(emoji);
            tvEmoji.setTextSize(26f);
            tvEmoji.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
            tvEmoji.setBackgroundResource(R.drawable.bg_filter_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
            tvEmoji.setLayoutParams(lp);
            tvEmoji.setOnClickListener(v -> {
                sheet.dismiss();
                if (engine != null) engine.sendReaction(emoji);
            });
            reactionsRow.addView(tvEmoji);
        }
        root.addView(reactionsRow);

        String[] itemIcons = {"💬", "🔊", "🔄", "📶", "✋"};
        String[] itemTitles = {
                "In-Call Encrypted Quick Chat",
                "Select Audio Output (Speaker / Bluetooth / Earpiece)",
                "Swap Local & Remote Video Feeds",
                engine != null && engine.isLowDataMode() ? "Disable Low-Data Mode" : "Enable Low-Data Mode (Bandwidth Saver)",
                "Raise / Lower Hand"
        };

        for (int i = 0; i < itemTitles.length; i++) {
            final int actionIndex = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));

            TextView icon = new TextView(this);
            icon.setText(itemIcons[i]);
            icon.setTextSize(20f);
            icon.setPadding(0, 0, (int) (14 * dp), 0);
            row.addView(icon);

            TextView text = new TextView(this);
            text.setText(itemTitles[i]);
            text.setTextSize(15.5f);
            text.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            text.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(text);

            row.setOnClickListener(v -> {
                sheet.dismiss();
                if (actionIndex == 0) {
                    showInCallQuickChatSheet();
                } else if (actionIndex == 1) {
                    showAudioRoutePickerSheet();
                } else if (actionIndex == 2) {
                    toggleSwapVideoFeeds();
                } else if (actionIndex == 3) {
                    if (engine != null) {
                        boolean next = !engine.isLowDataMode();
                        engine.setLowDataMode(next);
                        toast(next ? "Low-data mode enabled" : "Low-data mode disabled");
                    }
                } else if (actionIndex == 4) {
                    if (engine != null) {
                        CallParticipant self = engine.getParticipant(currentUserId);
                        boolean next = self == null || !self.handRaised;
                        engine.setRaiseHand(next);
                        toast(next ? "✋ Hand raised" : "Hand lowered");
                    }
                }
            });

            root.addView(row);
        }

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    private void toggleSwapVideoFeeds() {
        if (remoteVideoView == null || localVideoView == null) return;
        isVideoFeedsSwapped = !isVideoFeedsSwapped;
        try {
            if (attachedRemoteTrack != null) {
                attachedRemoteTrack.removeSink(remoteVideoView);
                attachedRemoteTrack.removeSink(localVideoView);
            }
            if (attachedLocalTrack != null) {
                attachedLocalTrack.removeSink(localVideoView);
                attachedLocalTrack.removeSink(remoteVideoView);
            }
            if (isVideoFeedsSwapped) {
                if (attachedLocalTrack != null) {
                    CallVideoDevice.attachRenderer(remoteVideoView, attachedLocalTrack, com.m3.veil.calling.CallMediaFactory.eglBase());
                }
                if (attachedRemoteTrack != null) {
                    CallVideoDevice.attachRenderer(localVideoView, attachedRemoteTrack, com.m3.veil.calling.CallMediaFactory.eglBase());
                }
                toast("🔄 Swapped: Your camera is full-screen");
            } else {
                if (attachedRemoteTrack != null) {
                    CallVideoDevice.attachRenderer(remoteVideoView, attachedRemoteTrack, com.m3.veil.calling.CallMediaFactory.eglBase());
                }
                if (attachedLocalTrack != null) {
                    CallVideoDevice.attachRenderer(localVideoView, attachedLocalTrack, com.m3.veil.calling.CallMediaFactory.eglBase());
                }
                toast("🔄 Swapped: Peer camera is full-screen");
            }
        } catch (Throwable ignored) {}
    }

    private void showAudioRoutePickerSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("🔊 Audio Output Route");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (12 * dp));
        root.addView(title);

        String[] labels = {
                "🔈 Speakerphone",
                "📱 Handset Earpiece",
                "🎧 Bluetooth Audio Device",
                "🔌 Wired Headset"
        };

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        for (int i = 0; i < labels.length; i++) {
            final int routeIdx = i;
            TextView row = new TextView(this);
            row.setText(labels[i]);
            row.setTextSize(15.5f);
            row.setTypeface(null, android.graphics.Typeface.BOLD);
            row.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            row.setPadding((int) (12 * dp), (int) (14 * dp), (int) (12 * dp), (int) (14 * dp));
            row.setOnClickListener(v -> {
                sheet.dismiss();
                if (engine == null) return;
                if (routeIdx == 0) {
                    engine.setSpeakerOn(true);
                    isSpeakerOn = true;
                    if (am != null) am.stopBluetoothSco();
                    if (tvToggleSpeaker != null) tvToggleSpeaker.setText("Speaker");
                } else if (routeIdx == 1) {
                    engine.setSpeakerOn(false);
                    isSpeakerOn = false;
                    if (am != null) am.stopBluetoothSco();
                    if (tvToggleSpeaker != null) tvToggleSpeaker.setText("Earpiece");
                } else if (routeIdx == 2) {
                    engine.setSpeakerOn(false);
                    isSpeakerOn = false;
                    if (am != null) {
                        try {
                            am.startBluetoothSco();
                            am.setBluetoothScoOn(true);
                        } catch (Throwable ignored) {}
                    }
                    if (tvToggleSpeaker != null) tvToggleSpeaker.setText("Bluetooth");
                } else if (routeIdx == 3) {
                    engine.setSpeakerOn(false);
                    isSpeakerOn = false;
                    if (am != null) am.stopBluetoothSco();
                    if (tvToggleSpeaker != null) tvToggleSpeaker.setText("Headset");
                }
                updateToggle(btnToggleSpeaker, ivToggleSpeaker, tvToggleSpeaker, isSpeakerOn || routeIdx == 2,
                        tvToggleSpeaker != null ? tvToggleSpeaker.getText().toString() : "Audio");
                applyProximityPolicy();
                toast("Audio routed to: " + labels[routeIdx]);
            });
            root.addView(row);
        }

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    private void showInCallQuickChatSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("💬 In-Call Encrypted Chat (" + (contactName != null ? contactName : "Peer") + ")");
        title.setTextSize(17.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (10 * dp));
        root.addView(title);

        String peerId = contactId != null ? contactId : (engine != null ? engine.getPrimaryPeerId() : null);
        String chatId = (peerId != null && !peerId.isEmpty())
                ? com.m3.veil.webrtc.WebRtcManager.getChatId(currentUserId, peerId)
                : ("call_chat_" + callId);

        LinearLayout historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setBackgroundResource(R.drawable.bg_search_input);
        int pad = (int) (12 * dp);
        historyBox.setPadding(pad, pad, pad, pad);
        root.addView(historyBox);

        List<com.m3.veil.model.Message> msgs = EncryptedChatDatabase.getInstance(this).getMessagesForChat(chatId);
        if (msgs == null || msgs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Send a quick encrypted note or link without leaving the call.");
            empty.setTextSize(13f);
            empty.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            historyBox.addView(empty);
        } else {
            int start = Math.max(0, msgs.size() - 4);
            for (int i = start; i < msgs.size(); i++) {
                com.m3.veil.model.Message m = msgs.get(i);
                TextView row = new TextView(this);
                boolean mine = currentUserId.equals(m.getSenderId());
                row.setText((mine ? "You: " : (contactName + ": ")) + m.getContent());
                row.setTextSize(13.5f);
                row.setTextColor(ContextCompat.getColor(this, mine ? R.color.colorPrimary : R.color.textPrimary));
                row.setPadding(0, (int) (3 * dp), 0, (int) (3 * dp));
                historyBox.addView(row);
            }
        }

        android.widget.EditText etMsg = new android.widget.EditText(this);
        etMsg.setHint("Type encrypted in-call message...");
        etMsg.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        etMsg.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        etMsg.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        root.addView(etMsg);

        MaterialButton btnSend = new MaterialButton(this);
        btnSend.setText("Send Over P2P DataChannel");
        btnSend.setCornerRadius((int) (12 * dp));
        btnSend.setOnClickListener(v -> {
            String text = etMsg.getText().toString().trim();
            if (text.isEmpty()) return;
            com.m3.veil.model.Message msg = new com.m3.veil.model.Message(
                    UUID.randomUUID().toString(),
                    currentUserId,
                    peerId != null ? peerId : "",
                    text,
                    Timestamp.now(),
                    "text"
            );
            msg.setChatId(chatId);
            msg.setSenderName(currentUsername != null ? currentUsername : "You");
            EncryptedChatDatabase.getInstance(this).saveMessage(chatId, msg);
            if (peerId != null) {
                com.m3.veil.model.Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
                com.m3.veil.webrtc.WebRtcManager.getInstance(this).sendChatMessageToChat(chat, peerId, msg);
            }
            sheet.dismiss();
            toast("💬 Sent encrypted in-call message");
        });
        root.addView(btnSend);

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    private void showVoiceFilterDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("Real-Time Voice Masking DSP");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (6 * dp));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Hardware PCM pitch/formant shifting before Opus wire encoding");
        sub.setTextSize(13f);
        sub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        sub.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(sub);

        com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter currentFilter =
                engine != null ? engine.getVoiceFilter() : com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter.NATURAL;

        com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter[] filters = com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter.values();
        String[] icons = {"🎙️", "🕶️", "🤖", "🐿️", "👻"};
        String[] descs = {
                "Natural voice without DSP processing",
                "Deep 0.7x pitch shift with 160Hz low-pass filter",
                "85Hz ring-modulation & bit-crushing effect",
                "1.55x high pitch transposition with formant boost",
                "1200Hz high-pass filter with spectral whisper noise"
        };

        for (int i = 0; i < filters.length; i++) {
            final com.m3.veil.calling.VoiceMaskingDsp.VoiceFilter filter = filters[i];
            boolean selected = (filter == currentFilter);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));

            TextView icon = new TextView(this);
            icon.setText(icons[i % icons.length]);
            icon.setTextSize(22f);
            icon.setPadding(0, 0, (int) (14 * dp), 0);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView name = new TextView(this);
            name.setText(filter.title + (selected ? "  ✓" : ""));
            name.setTextSize(15.5f);
            name.setTextColor(selected ? ContextCompat.getColor(this, R.color.colorPrimary) : ContextCompat.getColor(this, R.color.textPrimary));
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(name);

            TextView desc = new TextView(this);
            desc.setText(filter.description);
            desc.setTextSize(12.5f);
            desc.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            desc.setPadding(0, (int) (2 * dp), 0, 0);
            textCol.addView(desc);

            row.addView(textCol);

            row.setOnClickListener(v -> {
                if (engine != null) {
                    engine.setVoiceFilter(filter);
                    updateUiFromEngine(engine.getCallInfo());
                }
                toast("Voice Mask: " + filter.title);
                sheet.dismiss();
            });

            root.addView(row);
        }

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    private void showTrustRevealDialog() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (20 * dp), (int) (18 * dp), (int) (20 * dp), (int) (24 * dp));

        TextView title = new TextView(this);
        title.setText("Video Privacy & Trust Reveal");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setPadding(0, 0, 0, (int) (6 * dp));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Real-time separable box blur & cyberpunk silhouette mask (#122, #220)");
        sub.setTextSize(13f);
        sub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        sub.setPadding(0, 0, 0, (int) (16 * dp));
        root.addView(sub);

        // Slider: Trust Reveal
        TextView sliderLabel = new TextView(this);
        com.google.android.material.slider.Slider slider = new com.google.android.material.slider.Slider(this);
        slider.setValueFrom(0.0f);
        slider.setValueTo(1.0f);
        slider.setStepSize(0.05f);

        // Toggle 1: Blur
        com.google.android.material.switchmaterial.SwitchMaterial blurSwitch =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        blurSwitch.setText("Enable Real-Time Face Blur");
        blurSwitch.setTextSize(15.5f);
        blurSwitch.setChecked(engine != null && engine.isVideoBlurEnabled());
        blurSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (engine != null) {
                engine.setVideoBlurEnabled(isChecked);
                float nextVal = engine.getTrustRevealLevel();
                slider.setValue(Math.round(nextVal * 20f) / 20f);
                updateUiFromEngine(engine.getCallInfo());
            }
        });
        root.addView(blurSwitch);

        // Toggle 2: Silhouette
        com.google.android.material.switchmaterial.SwitchMaterial silSwitch =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        silSwitch.setText("Cyberpunk Silhouette Mode (#122)");
        silSwitch.setTextSize(15.5f);
        silSwitch.setChecked(engine != null && engine.isVideoSilhouetteMode());
        silSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (engine != null) {
                engine.setVideoSilhouetteMode(isChecked);
                updateUiFromEngine(engine.getCallInfo());
            }
        });
        root.addView(silSwitch);

        int curPct = Math.round((engine != null ? engine.getTrustRevealLevel() : 1.0f) * 100f);
        sliderLabel.setText("Trust Reveal Slider: " + curPct + "% (" + (curPct >= 99 ? "Clear" : "Masked") + ")");
        sliderLabel.setTextSize(14f);
        sliderLabel.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        sliderLabel.setPadding(0, (int) (14 * dp), 0, (int) (4 * dp));
        root.addView(sliderLabel);

        slider.setValue(Math.round((engine != null ? engine.getTrustRevealLevel() : 1.0f) * 20f) / 20f);
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (engine != null) {
                engine.setTrustRevealLevel(value);
                updateUiFromEngine(engine.getCallInfo());
            }
            int pct = Math.round(value * 100f);
            sliderLabel.setText("Trust Reveal Slider: " + pct + "% (" + (pct >= 99 ? "Clear" : "Masked") + ")");
        });
        root.addView(slider);

        // AR Anonymous Camera Masks (#211)
        TextView arTitle = new TextView(this);
        arTitle.setText("AR Anonymous Face Masks (#211)");
        arTitle.setTextSize(15f);
        arTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        arTitle.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        arTitle.setPadding(0, (int) (14 * dp), 0, (int) (6 * dp));
        root.addView(arTitle);

        HorizontalScrollView arScroll = new HorizontalScrollView(this);
        arScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout arContainer = new LinearLayout(this);
        arContainer.setOrientation(LinearLayout.HORIZONTAL);

        int curArMask = engine != null ? engine.getArMaskMode() : com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE;
        for (int i = 0; i < com.m3.veil.utils.VeilArMaskRenderer.MASK_NAMES.length; i++) {
            final int mode = i;
            TextView chip = new TextView(this);
            chip.setText(com.m3.veil.utils.VeilArMaskRenderer.MASK_ICONS[i] + " " + com.m3.veil.utils.VeilArMaskRenderer.MASK_NAMES[i]);
            chip.setTextSize(12.5f);
            chip.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            chip.setBackgroundResource(curArMask == mode ? R.drawable.bg_filter_chip_selected : R.drawable.bg_filter_chip);
            chip.setTextColor(curArMask == mode ? Color.WHITE : ContextCompat.getColor(this, R.color.textPrimary));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, (int) (6 * dp), 0);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                if (engine != null) {
                    engine.setArMaskMode(mode);
                    updateUiFromEngine(engine.getCallInfo());
                }
                sheet.dismiss();
                toast("AR Mask: " + com.m3.veil.utils.VeilArMaskRenderer.MASK_NAMES[mode]);
            });

            arContainer.addView(chip);
        }
        arScroll.addView(arContainer);
        root.addView(arScroll);

        sheet.setContentView(root);
        showSheetSafely(sheet);
    }

    private void requestScreenShare() {
        try {
            android.media.projection.MediaProjectionManager manager =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                toast("Screen capture unavailable");
                return;
            }
            screenShareLauncher.launch(manager.createScreenCaptureIntent());
        } catch (Throwable t) {
            toast("Screen capture unavailable");
        }
    }

    // ---- video rendering -----------------------------------------------------

    private void attachRemoteVideo(VideoTrack track) {
        if (track == null || remoteVideoView == null || isFinishing() || isDestroyed()) return;
        try {
            if (track.state() == org.webrtc.MediaStreamTrack.State.ENDED) return;
        } catch (Throwable ignored) {
            return;
        }
        if (attachedRemoteTrack != track) {
            if (attachedRemoteTrack != null) {
                try { attachedRemoteTrack.removeSink(remoteVideoView); } catch (Throwable ignored) { }
            }
            boolean attached = CallVideoDevice.attachRenderer(remoteVideoView, track,
                    com.m3.veil.calling.CallMediaFactory.eglBase());
            if (attached) {
                attachedRemoteTrack = track;
                remoteVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL);
                remoteVideoView.setMirror(false);
            }
        }
        try { track.setEnabled(true); } catch (Throwable ignored) { }
        remoteVideoView.setVisibility(View.VISIBLE);
        if (videoScrimOverlay != null) videoScrimOverlay.setVisibility(View.VISIBLE);
        if (avatarContainer != null) avatarContainer.setVisibility(View.GONE);
        View root = findViewById(R.id.call_root);
        if (root != null) root.setBackground(null);
        if (isVideoActive) enterPipIfPossible();
    }

    private void attachLocalVideo(CallParticipant self) {
        if (localVideoView == null || engine == null || isFinishing() || isDestroyed()) return;
        VideoTrack track = engine.getLocalVideoTrack();
        if (track == null) return;
        try {
            if (track.state() == org.webrtc.MediaStreamTrack.State.ENDED) return;
        } catch (Throwable ignored) {
            return;
        }
        if (attachedLocalTrack != track) {
            if (attachedLocalTrack != null) {
                try { attachedLocalTrack.removeSink(localVideoView); } catch (Throwable ignored) { }
            }
            boolean attached = CallVideoDevice.attachRenderer(localVideoView, track,
                    com.m3.veil.calling.CallMediaFactory.eglBase());
            if (attached) {
                attachedLocalTrack = track;
                localVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL);
                localVideoView.setMirror(true);
            }
        }
        localVideoView.setVisibility(View.VISIBLE);
    }

    private void enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(9, 16))
                    .build();
            setPictureInPictureParams(params);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isVideoActive && isConnected) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(9, 16))
                        .build();
                enterPictureInPictureMode(params);
            } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            if (topCallInfo != null) topCallInfo.setVisibility(View.GONE);
            if (callControlsContainer != null) callControlsContainer.setVisibility(View.GONE);
            if (cardLocalVideo != null) cardLocalVideo.setVisibility(View.GONE);
            if (videoScrimOverlay != null) videoScrimOverlay.setVisibility(View.GONE);
        } else {
            if (topCallInfo != null) topCallInfo.setVisibility(View.VISIBLE);
            if (callControlsContainer != null) callControlsContainer.setVisibility(View.VISIBLE);
            if (cardLocalVideo != null && isVideoActive) cardLocalVideo.setVisibility(View.VISIBLE);
            if (videoScrimOverlay != null) videoScrimOverlay.setVisibility(View.VISIBLE);
        }
    }

    // ---- proximity -----------------------------------------------------------

    private void applyProximityPolicy() {
        boolean shouldHold = isConnected && !isSpeakerOn && !isVideoActive;
        try {
            if (shouldHold) {
                if (proximityWakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        proximityWakeLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "veil:call");
                    }
                }
                if (proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                    proximityWakeLock.acquire(60 * 60 * 1000L);
                }
            } else if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
                proximityWakeLock.release();
            }
        } catch (Throwable ignored) { }
    }

    private void releaseProximity() {
        try {
            if (proximityWakeLock != null && proximityWakeLock.isHeld()) proximityWakeLock.release();
        } catch (Throwable ignored) { }
        proximityWakeLock = null;
    }

    // ---- ringtones -----------------------------------------------------------

    private void playRingback() {
        try {
            ringbackTone = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60);
            ringbackLoop = new Runnable() {
                @Override public void run() {
                    try {
                        if (ringbackTone != null) {
                            ringbackTone.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1500);
                        }
                    } catch (Throwable ignored) { }
                    uiHandler.postDelayed(this, 3000);
                }
            };
            uiHandler.post(ringbackLoop);
        } catch (Throwable ignored) { }
    }

    private void playRingtone() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setDataSource(this, uri);
            ringtonePlayer.setAudioStreamType(AudioManager.STREAM_RING);
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepare();
            ringtonePlayer.start();
        } catch (Throwable t) {
            try { android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(500);
            } catch (Throwable ignored) { }
        }
    }

    private void stopRingtone() {
        try {
            if (ringtonePlayer != null) {
                ringtonePlayer.stop();
                ringtonePlayer.release();
            }
        } catch (Throwable ignored) { }
        ringtonePlayer = null;
    }

    private void stopRingtoneAndRingback() {
        stopRingtone();
        if (ringbackLoop != null) uiHandler.removeCallbacks(ringbackLoop);
        ringbackLoop = null;
        try {
            if (ringbackTone != null) ringbackTone.release();
        } catch (Throwable ignored) { }
        ringbackTone = null;
    }

    // ---- ui helpers ----------------------------------------------------------

    private void showIncomingUi() {
        if (layoutAcceptCall != null) layoutAcceptCall.setVisibility(View.VISIBLE);
        if (btnEndCall != null) btnEndCall.setVisibility(View.VISIBLE);
        if (tvEndCallLabel != null) tvEndCallLabel.setText("Decline");
        tvStatus.setText("Incoming " + ("video".equalsIgnoreCase(callType) ? "video call" : "call"));
        if ("video".equalsIgnoreCase(callType) && avatarContainer != null) {
            avatarContainer.setVisibility(View.VISIBLE);
        }
        playRingtone();
    }

    private void showOutgoingUi() {
        if (layoutAcceptCall != null) layoutAcceptCall.setVisibility(View.GONE);
        if (btnEndCall != null) btnEndCall.setVisibility(View.VISIBLE);
        if (tvEndCallLabel != null) tvEndCallLabel.setText("End");
        tvStatus.setText("Calling\u2026");
    }

    private void startPulseAnimation() {
        if (pulseRing == null) return;
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(pulseRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f));
        pulseAnimator.setDuration(1500);
        pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator.start();
    }

    private void startTimerLoop() {
        timerRunnable = new Runnable() {
            @Override public void run() {
                CallInfo info = engine != null ? engine.getCallInfo() : null;
                if (isConnected && info != null) updateDurationText(info);
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.postDelayed(timerRunnable, 1000);
    }

    private void saveCallRecord(CallInfo info, CallEndReason reason) {
        try {
            long durationSeconds = info != null ? info.getDurationMs() / 1000L : 0;
            String status = reason != null ? reason.logStatus() : "completed";
            String selfName = getResolvedUsername();
            String safeContact = (contactName != null && !isSelfName(contactName)) ? contactName.trim() : "Veil Contact";
            String callerId = isIncoming ? contactId : currentUserId;
            String callerName = isIncoming ? safeContact : selfName;
            String receiverId = isIncoming ? currentUserId : contactId;
            String receiverName = isIncoming ? selfName : safeContact;
            String peerId = (contactId != null && !contactId.equals(currentUserId)) ? contactId : (isIncoming ? callerId : receiverId);
            String peerName = safeContact;
            CallRecord record = new CallRecord(callId, peerId, peerName, callerId, callerName, receiverId, receiverName,
                    callType, status, (int) durationSeconds, Timestamp.now());
            EncryptedChatDatabase.getInstance(this).saveCallRecord(record);

            if (!isIncoming && contactId != null && !contactId.isEmpty()) {
                String chatId = com.m3.veil.webrtc.WebRtcManager.getChatId(currentUserId, contactId);
                com.m3.veil.model.Message callMsg = EncryptedChatDatabase.getInstance(this).getMessageById("call_" + callId);
                if (callMsg != null) {
                    try {
                        com.m3.veil.model.Chat chat = EncryptedChatDatabase.getInstance(this).getChat(chatId);
                        com.m3.veil.webrtc.WebRtcManager.getInstance(this).sendChatMessageToChat(chat, contactId, callMsg);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) { }
    }

    private void toast(String message) {
        if (message == null || isFinishing()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED
                        && Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                    toast("Microphone permission is required for calls");
                }
            }
            if (engine != null) {
                engine.onPermissionsGranted();
                if (pendingVideoAcceptOnPermission && cameraGranted) {
                    pendingVideoAcceptOnPermission = false;
                    callType = "video";
                    isVideoActive = true;
                    engine.respondVideoUpgrade(true);
                } else if (pendingVideoToggleOnPermission && cameraGranted) {
                    pendingVideoToggleOnPermission = false;
                    toggleVideo();
                }
                updateUiFromEngine(engine.getCallInfo());
            }
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_DECLINE_ON_START, false)) {
            if (engine != null) engine.decline(CallEndReason.DECLINED);
            finish();
            return;
        }
        readExtras(intent);
        if (isIncoming) showIncomingUi();
    }

    @Override
    protected void onDestroy() {
        dismissActiveSheet();
        super.onDestroy();
        isCallActive = false;
        stopRingtoneAndRingback();
        releaseProximity();
        if (timerRunnable != null) uiHandler.removeCallbacks(timerRunnable);
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (engine != null) {
            if (isFinishing() && engine.isInCall()) {
                if (isIncoming && !accepted) {
                    engine.decline(CallEndReason.DECLINED);
                } else {
                    engine.hangup(CallEndReason.LOCAL_HANGUP);
                }
            }
            engine.removeListener(engineListener);
        }
        if (remoteVideoView != null) {
            CallVideoDevice.detachRenderer(remoteVideoView, attachedRemoteTrack);
            attachedRemoteTrack = null;
        }
        if (localVideoView != null) {
            CallVideoDevice.detachRenderer(localVideoView, attachedLocalTrack);
            attachedLocalTrack = null;
        }
    }
}
