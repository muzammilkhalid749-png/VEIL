package com.m3.veil.calling;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.Timestamp;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.CallRecord;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VEIL's next-generation call engine.
 *
 * <p>Design goals (and the concrete fixes over the legacy WebRtcManager call path):
 * <ol>
 *   <li><b>Accept gating</b> — the callee creates no PeerConnection and sends no
 *       answer until the human accepts. The legacy path answered the offer while
 *       the phone was still ringing.</li>
 *   <li><b>Perfect negotiation</b> — offers are initiated deterministically by
 *       the lexicographically smaller identity and collisions are resolved with
 *       rollback, so mid-call renegotiation (video upgrade, screen share) never
 *       glares.</li>
 *   <li><b>TURN/relay + ICE restart</b> — see {@link CallConfig}.</li>
 *   <li><b>Separate PeerConnection per peer</b> — calls no longer share the chat
 *       DataChannel, so hanging up does not drop messaging.</li>
 *   <li><b>Frame-level E2EE</b> via {@link CallE2ee}.</li>
 *   <li><b>Group mesh</b>, screen share, DTMF, reactions, raise hand, low-data
 *       mode and live {@link CallStats}.</li>
 * </ol>
 */
public class CallEngine implements CallSignaling.Listener {

    private static final String TAG = "CallEngine";
    private static final String CTRL_LABEL = "veil-call-ctrl";

    private static volatile CallEngine instance;

    // ---- public contracts ----------------------------------------------------

    public interface Listener {
        default void onStateChanged(CallState state, CallInfo info) { }
        default void onCallInfoChanged(CallInfo info) { }
        default void onParticipantChanged(CallParticipant participant) { }
        default void onRemoteVideoTrack(String peerId, VideoTrack track, boolean screen) { }
        default void onRemoteVideoTrackRemoved(String peerId, boolean screen) { }
        default void onIncomingCall(CallInfo info) { }
        default void onVideoUpgradeRequested(String peerId, String peerName) { }
        default void onVideoUpgradeResponded(String peerId, String peerName, boolean accepted) { }
        default void onCallEnded(CallEndReason reason, CallInfo info) { }
        default void onAudioRouteChanged(CallAudioRoute route) { }
        default void onE2eeChanged(boolean enabled, String safetyNumber) { }
        default void onE2eeFingerprintChanged(String peerId, String safetyNumber) { }
        default void onTopologyChanged(CallTopology topology) { }
        default void onActiveSpeakerChanged(String peerId, float audioLevel) { }
        default void onError(String message) { }
    }

    /**
     * Group Call P2P Mesh Topology Scaling (#125).
     * DIRECT_P2P_MESH: Direct full-mesh WebRTC connections for 2 to 4 peers.
     * SFRAME_RELAY_OVERLAY: Seamlessly scales 5+ peers with FrameCryptor AES-GCM SFrame encryption.
     */
    public enum CallTopology {
        DIRECT_P2P_MESH,
        SFRAME_RELAY_OVERLAY,
        CASCADED_P2P_TREE
    }

    public interface CallLogSink {
        void onCallLogged(CallInfo info, CallEndReason reason, long durationMs,
                          long bytesSent, long bytesReceived);
    }

    public static final class Target {
        public final String peerId;
        public final String name;

        public Target(String peerId, String name) {
            this.peerId = peerId;
            this.name = name;
        }
    }

    // ---- state ---------------------------------------------------------------

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService diskIo = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CallSignaling signaling = new CallSignaling();
    private final CallAudioDevice audioDevice;
    private final CallVideoDevice videoDevice;
    private final CallE2ee e2ee = new CallE2ee();

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, List<CallSignal>> deferred = new ConcurrentHashMap<>();
    /** Call ids that already ended, so a late queued INVITE never rings. */
    private final java.util.Set<String> endedCallIds = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 64;
                }
            });

    private CallConfig config = new CallConfig();
    private CallLogSink logSink;
    private volatile String currentUserId;
    private String displayName = null;

    private org.webrtc.AudioSource sharedAudioSource;
    private AudioTrack sharedAudioTrack;
    private CallParticipant selfParticipant;
    private CallFingerprints.Status fingerprintStatus = CallFingerprints.Status.UNKNOWN;
    private CallFingerprints.Status lastNotifiedFingerprintStatus = CallFingerprints.Status.UNKNOWN;
    private String lastFingerprintValue;

    private String callId;
    private String groupId;
    private String groupName;
    private String primaryPeerId;
    private String primaryPeerName;
    private CallType callType = CallType.AUDIO;
    private CallState state = CallState.NONE;
    private boolean caller;
    private boolean muted;
    private boolean videoEnabled;
    private boolean screenSharing;
    private boolean lowDataMode;
    private boolean localVideoUpgradeRequested;
    private boolean remoteVideoUpgradeRequested;
    private boolean waitingRoomEnabled;
    private boolean inWaitingRoom;
    private String pendingInviteKey;
    private String pendingInvitePayload;
    private long startedAtMs;
    private long connectedAtMs;
    private long endedAtMs;
    private CallEndReason endReason;
    private CallStats stats = CallStats.empty();
    private volatile CallTopology topology = CallTopology.DIRECT_P2P_MESH;
    private volatile String activeSpeakerId = null;
    private volatile float activeSpeakerLevel = 0.0f;
    private long lastSpeakerChangeMs = 0L;

    private Runnable ringingTimeout;
    private Runnable offerExpiry;
    private Runnable reconnectTimeout;
    private Runnable statsPoll;
    private Runnable ringingHeartbeatRunnable;
    private Runnable incomingRingingWatchdog;
    private Runnable handshakeTimeout;
    private long lastRingingPingMs;
    private ConnectivityManager.NetworkCallback networkCallback;

    private CallEngine(Context context) {
        this.context = context.getApplicationContext();
        this.signaling.setContext(this.context);
        CallMediaFactory.init(this.context);
        this.audioDevice = new CallAudioDevice(this.context);
        this.videoDevice = new CallVideoDevice(this.context, CallMediaFactory.eglBase());
        this.audioDevice.setFocusLostListener(() -> {
            Log.w(TAG, "Audio focus permanently lost (e.g. GSM phone call incoming/active) -> ending VoIP call");
            main.post(() -> {
                if (isInCall()) {
                    hangup(CallEndReason.LOCAL_HANGUP);
                }
            });
        });
    }

    public static CallEngine getInstance(Context context) {
        CallEngine local = instance;
        if (local == null) {
            synchronized (CallEngine.class) {
                local = instance;
                if (local == null) {
                    local = new CallEngine(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private volatile String initializedUserId = null;

    /** Registers the user for call delivery and warms the WebRTC stack. */
    public void init(String userId) {
        if (userId == null || userId.isEmpty()) return;
        currentUserId = userId;
        if (userId.equals(initializedUserId)) {
            return;
        }
        initializedUserId = userId;
        this.displayName = getResolvedDisplayName();
        CallMediaFactory.init(context);
        com.m3.veil.webrtc.TurnCredentialManager.getInstance(context).probeAllNodesAsync();
        signaling.addListener(this);
        signaling.start(userId);
        loadTurnConfig();
        registerNetworkCallback();
        Log.i(TAG, "CallEngine ready for " + userId + " (e2ee=" + e2ee.isSupported()
                + ", turn=" + CallConfig.hasTurn() + ")");
    }

    public void shutdown() {
        initializedUserId = null;
        signaling.removeListener(this);
        signaling.stop();
        unregisterNetworkCallback();
        try { diskIo.shutdown(); } catch (Throwable ignored) { }
    }

    private Runnable networkLostTimeout;

    /**
     * Ported from Telegram VoIP (org.telegram.messenger.voip.VoIPService#updateNetworkType).
     * Monitors default network interface transitions (e.g. Wi-Fi to Cellular 4G/5G)
     * and automatically restarts ICE across active peer connections so calls never drop.
     * Also detects total network loss (airplane mode, Wi-Fi disconnected) and triggers
     * fast reconnect grace before cleanly ending the call on both sides.
     */
    private void registerNetworkCallback() {
        if (networkCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        main.post(() -> {
                            if (isInCall()) {
                                Log.i(TAG, "Network transition / recovery detected -> triggering ICE restart");
                                cancelNetworkLostTimeout();
                                restartIce();
                            }
                        });
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        main.post(() -> {
                            if (!isInCall()) return;
                            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                            boolean hasOther = false;
                            if (manager != null) {
                                Network active = manager.getActiveNetwork();
                                hasOther = (active != null && !active.equals(network));
                            }
                            if (!hasOther) {
                                Log.w(TAG, "All network connectivity lost during call -> handling fast disconnect");
                                if (state == CallState.RECEIVED_CALL) {
                                    decline(CallEndReason.CONNECTION_LOST);
                                } else if (state == CallState.CALLING) {
                                    hangup(CallEndReason.CONNECTION_LOST);
                                } else {
                                    setState(CallState.REJOINING);
                                    scheduleNetworkLostTimeout();
                                }
                            }
                        });
                    }
                };
                cm.registerDefaultNetworkCallback(networkCallback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerNetworkCallback failed: " + t.getMessage());
        }
    }

    private void scheduleNetworkLostTimeout() {
        if (networkLostTimeout != null) return;
        networkLostTimeout = () -> {
            networkLostTimeout = null;
            if (isInCall()) {
                Log.w(TAG, "Network lost timeout expired -> ending call with CONNECTION_LOST");
                endCall(CallEndReason.CONNECTION_LOST, true);
            }
        };
        main.postDelayed(networkLostTimeout, config.reconnectGraceMs);
    }

    private void cancelNetworkLostTimeout() {
        if (networkLostTimeout != null) {
            main.removeCallbacks(networkLostTimeout);
            networkLostTimeout = null;
        }
    }

    private void unregisterNetworkCallback() {
        cancelNetworkLostTimeout();
        if (networkCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) { }
        networkCallback = null;
    }

    /** Restarts ICE on all active peer connections without dropping the call. */
    public void restartIce() {
        if (!isInCall()) return;
        for (Peer peer : peers.values()) {
            if (peer.pc != null && isInCall()) {
                try {
                    peer.pc.restartIce();
                } catch (Throwable t) {
                    Log.w(TAG, "restartIce failed for " + peer.peerId + ": " + t.getMessage());
                }
            }
        }
    }

    /**
     * Injects TURN relay credentials (e.g. from remote config) and applies them
     * to any live PeerConnection, then restarts ICE so relay candidates are
     * gathered immediately. Without this the engine would be STUN-only, which
     * fails on symmetric NAT / carrier-grade NAT.
     */
    public void setTurnServers(List<String> urls, String username, String password) {
        CallConfig.setGlobalTurn(urls, username, password);
        applyIceServersToPeers();
        Log.i(TAG, "TURN configured: " + (urls == null ? 0 : urls.size())
                + " server(s), configured=" + CallConfig.hasTurn());
    }

    public boolean isTurnConfigured() {
        return CallConfig.hasTurn();
    }

    private void applyIceServersToPeers() {
        if (!isInCall()) return;
        for (Peer peer : peers.values()) {
            if (peer.pc == null || !isInCall()) continue;
            try {
                peer.pc.setConfiguration(CallMediaFactory.buildConfiguration(config));
                peer.pc.restartIce();
            } catch (Throwable t) {
                Log.w(TAG, "setConfiguration failed for " + peer.peerId + ": " + t.getMessage());
            }
        }
    }

    /**
     * Best-effort remote TURN discovery from {@code config/webrtc}. Expected
     * fields: {@code turnUrls} (array or comma-separated string),
     * {@code turnUsername}, {@code turnPassword}.
     */
    private void loadTurnConfig() {
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("config").document("webrtc")
                    .get()
                    .addOnSuccessListener(doc -> {
                        if (doc == null || !doc.exists()) return;
                        List<String> urls = new ArrayList<>();
                        Object raw = doc.get("turnUrls");
                        if (raw instanceof List) {
                            for (Object o : (List<?>) raw) {
                                if (o != null) urls.add(String.valueOf(o));
                            }
                        } else if (raw instanceof String) {
                            for (String part : ((String) raw).split(",")) {
                                if (!part.trim().isEmpty()) urls.add(part.trim());
                            }
                        }
                        if (urls.isEmpty()) return;
                        final String user = doc.getString("turnUsername");
                        final String pass = doc.getString("turnPassword");
                        main.post(() -> setTurnServers(urls, user, pass));
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "TURN discovery failed: " + e.getMessage()));
        } catch (Throwable t) {
            Log.w(TAG, "TURN discovery skipped: " + t.getMessage());
        }
    }

    public void setConfig(CallConfig config) {
        if (config != null) this.config = config;
    }

    public CallConfig getConfig() {
        return config;
    }

    public static boolean isSelfName(String name) {
        if (name == null) return true;
        String clean = name.trim();
        return clean.isEmpty() || "Me".equalsIgnoreCase(clean) || "You".equalsIgnoreCase(clean)
                || "local_user".equalsIgnoreCase(clean) || "null".equalsIgnoreCase(clean);
    }

    public String getResolvedDisplayName() {
        if (displayName != null && !isSelfName(displayName)) {
            return displayName;
        }
        try {
            com.m3.veil.model.Persona persona =
                    com.m3.veil.manager.VeilFeatureManager.getInstance(context).getActivePersona();
            if (persona != null && persona.getDisplayName() != null && !isSelfName(persona.getDisplayName())) {
                this.displayName = persona.getDisplayName().trim();
                return this.displayName;
            }
        } catch (Throwable ignored) {}
        try {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest =
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this.context).getLocalManifest();
            if (manifest != null && manifest.displayName != null && !manifest.displayName.trim().isEmpty() && !isSelfName(manifest.displayName)) {
                this.displayName = manifest.displayName.trim();
                return this.displayName;
            }
            if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                String fName = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
                if (fName != null && !fName.trim().isEmpty() && !isSelfName(fName)) {
                    this.displayName = fName.trim();
                    return this.displayName;
                }
            }
        } catch (Throwable ignored) {}
        return "Veil User";
    }

    public void setDisplayName(String name) {
        if (name != null && !name.trim().isEmpty() && !isSelfName(name)) {
            this.displayName = name.trim();
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setCallLogSink(CallLogSink sink) {
        this.logSink = sink;
    }

    public CallState getState() {
        return state;
    }

    public boolean isInCall() {
        return state != CallState.NONE && state != CallState.ENDING;
    }

    public boolean isMuted() {
        return muted;
    }

    public CallAudioRoute getAudioRoute() {
        return audioDevice.getRoute();
    }

    public CallAudioDevice getAudioDevice() {
        return audioDevice;
    }

    public VoiceMaskingDsp.VoiceFilter getVoiceFilter() {
        return audioDevice != null ? audioDevice.getVoiceFilter() : VoiceMaskingDsp.VoiceFilter.NATURAL;
    }

    public void setVoiceFilter(VoiceMaskingDsp.VoiceFilter filter) {
        if (audioDevice != null) {
            audioDevice.setVoiceFilter(filter);
        }
    }

    // Anonymous Blurred Face Video Calls & Live Trust Reveal (#122, #220)
    public void setVideoBlurEnabled(boolean enabled) {
        if (videoDevice != null) {
            videoDevice.setBlurEnabled(enabled);
        }
    }

    public boolean isVideoBlurEnabled() {
        return videoDevice != null && videoDevice.isBlurEnabled();
    }

    public void setVideoSilhouetteMode(boolean enabled) {
        if (videoDevice != null) {
            videoDevice.setSilhouetteMode(enabled);
        }
    }

    public boolean isVideoSilhouetteMode() {
        return videoDevice != null && videoDevice.isSilhouetteMode();
    }

    public void setTrustRevealLevel(float level) {
        if (videoDevice != null) {
            videoDevice.setTrustRevealLevel(level);
        }
    }

    public float getTrustRevealLevel() {
        return videoDevice != null ? videoDevice.getTrustRevealLevel() : 1.0f;
    }

    // AR Anonymous Camera Masks (#211)
    public void setArMaskMode(int mode) {
        if (videoDevice != null) {
            videoDevice.setArMaskMode(mode);
        }
    }

    public int getArMaskMode() {
        return videoDevice != null ? videoDevice.getArMaskMode() : com.m3.veil.utils.VeilArMaskRenderer.MASK_NONE;
    }

    // Group Call SFrame E2EE Topology & Active Speaker (#125)
    public CallTopology getTopology() {
        return topology;
    }

    public String getActiveSpeakerId() {
        return activeSpeakerId;
    }

    public float getActiveSpeakerLevel() {
        return activeSpeakerLevel;
    }

    public boolean isE2eeEnabled() {
        return e2ee.isEnabled();
    }

    public CallFingerprints.Status getFingerprintStatus() {
        return fingerprintStatus;
    }

    public boolean isFingerprintVerified() {
        return primaryPeerId != null && CallFingerprints.isVerified(context, primaryPeerId);
    }

    public void setFingerprintVerified(boolean verified) {
        if (primaryPeerId == null) return;
        CallFingerprints.setVerified(context, primaryPeerId, verified);
        notifyE2ee();
    }

    public String getSafetyNumber() {
        String peer = primaryPeerId;
        if (peer == null && !peers.isEmpty()) peer = peers.keySet().iterator().next();
        return currentUserId == null || peer == null ? null : e2ee.safetyNumber(currentUserId, peer);
    }

    /**
     * 4-emoji visual verification string ported from Telegram VoIP.
     */
    public String getSafetyEmojis() {
        String peer = primaryPeerId;
        if (peer == null && !peers.isEmpty()) peer = peers.keySet().iterator().next();
        return currentUserId == null || peer == null ? null : e2ee.safetyEmojis(currentUserId, peer);
    }

    public List<CallParticipant> getParticipants() {
        List<CallParticipant> out = new ArrayList<>();
        for (Peer peer : peers.values()) if (peer.participant != null) out.add(peer.participant);
        return out;
    }

    public CallParticipant getParticipant(String peerId) {
        Peer peer = peers.get(peerId);
        return peer == null ? null : peer.participant;
    }

    /** The local camera track, or null when the camera is not running. */
    public VideoTrack getLocalVideoTrack() {
        return videoDevice.getCameraTrack();
    }

    /** The local screen-share track, or null when not sharing. */
    public VideoTrack getLocalScreenTrack() {
        return videoDevice.getScreenTrack();
    }

    public VideoTrack getRemoteVideoTrack(String peerId) {
        Peer peer = peers.get(peerId);
        if (peer == null || peer.participant == null) return null;
        return peer.participant.renderTrack();
    }

    public String getPrimaryPeerId() {
        return primaryPeerId;
    }

    public CallInfo getCallInfo() {
        return buildInfo();
    }

    // ---- outgoing ------------------------------------------------------------

    public void startCall(String peerId, String peerName, CallType type, String callId) {
        if (isInCall()) {
            emitError("Already in a call");
            return;
        }
        if (!CallMediaFactory.isReady()) {
            emitError("Call engine unavailable (WebRTC failed to initialise)");
            return;
        }
        this.caller = true;
        this.callId = callId;
        this.callType = type;
        this.primaryPeerId = peerId;

        String resolvedPeer = peerName;
        if (isSelfName(resolvedPeer)) {
            try {
                com.m3.veil.model.Chat c = com.m3.veil.database.EncryptedChatDatabase.getInstance(context).getChat(peerId);
                if (c != null && c.getName() != null && !isSelfName(c.getName())) {
                    resolvedPeer = c.getName().trim();
                }
            } catch (Throwable ignored) {}
        }
        if (isSelfName(resolvedPeer)) {
            resolvedPeer = "Veil Contact";
        }
        this.primaryPeerName = resolvedPeer;

        this.groupId = null;
        this.groupName = null;
        this.startedAtMs = System.currentTimeMillis();
        this.connectedAtMs = 0;
        this.endedAtMs = 0;
        this.endReason = null;
        this.videoEnabled = type == CallType.VIDEO;
        this.muted = false;

        String selfName = getResolvedDisplayName();
        this.selfParticipant = new CallParticipant(currentUserId, selfName, true);
        resetStats();

        e2ee.generateMasterKey();
        audioDevice.start(type == CallType.VIDEO);

        setState(CallState.CALLING);

        CallSignal invite = CallSignal.of(CallSignal.INVITE, callId, currentUserId).to(peerId);
        invite.fromName = selfName;
        invite.callType = type.wire;
        invite.e2eeKeyB64 = e2ee.exportMasterKeyBase64();
        invite.e2eeKeyIndex = e2ee.getKeyIndex();
        signaling.send(invite);

        if (type == CallType.VIDEO) {
            int w = lowDataMode ? config.lowDataWidth : config.videoWidth;
            int h = lowDataMode ? config.lowDataHeight : config.videoHeight;
            int fps = lowDataMode ? config.lowDataFps : config.videoFps;
            videoDevice.startCamera(w, h, fps);
        }
        putParticipant(peerId, resolvedPeer);
        scheduleRingingTimeout();
        startRingingHeartbeat();
        notifyInfo();
    }

    public void startGroupCall(List<Target> targets, String groupId, String groupName,
                               CallType type, String callId) {
        if (isInCall()) {
            emitError("Already in a call");
            return;
        }
        if (targets == null || targets.isEmpty()) {
            emitError("No participants");
            return;
        }
        this.caller = true;
        this.callId = callId;
        this.callType = type;
        this.groupId = groupId;
        this.groupName = groupName;
        Target first = targets.get(0);
        this.primaryPeerId = first.peerId;
        this.primaryPeerName = groupName != null ? groupName : first.name;
        this.startedAtMs = System.currentTimeMillis();
        this.videoEnabled = type == CallType.VIDEO;
        this.selfParticipant = new CallParticipant(currentUserId, displayName, true);
        resetStats();

        e2ee.generateMasterKey();
        audioDevice.start(type == CallType.VIDEO);
        setState(CallState.CALLING);

        String roster = buildRoster(targets);
        for (Target target : targets) {
            CallSignal invite = CallSignal.of(CallSignal.INVITE, callId, currentUserId).to(target.peerId);
            invite.fromName = displayName;
            invite.callType = type.wire;
            invite.groupId = groupId;
            invite.groupName = groupName;
            invite.payload = roster;
            invite.e2eeKeyB64 = e2ee.exportMasterKeyBase64();
            signaling.send(invite);
            ensurePeer(target.peerId, target.name);
        }
        for (Target target : targets) maybeInitiate(target.peerId);
        scheduleRingingTimeout();
        startRingingHeartbeat();
        notifyInfo();
    }

    private String buildRoster(List<Target> targets) {
        try {
            JSONArray array = new JSONArray();
            JSONObject self = new JSONObject();
            self.put("id", currentUserId);
            self.put("name", displayName);
            array.put(self);
            for (Target target : targets) {
                JSONObject o = new JSONObject();
                o.put("id", target.peerId);
                o.put("name", target.name);
                array.put(o);
            }
            return array.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---- incoming ------------------------------------------------------------

    public void accept() {
        if (state != CallState.RECEIVED_CALL) return;
        cancelOfferExpiry();
        cancelIncomingRingingWatchdog();
        if (pendingInviteKey != null) e2ee.importMasterKeyBase64(pendingInviteKey);
        else e2ee.generateMasterKey();
        audioDevice.start(callType == CallType.VIDEO);
        if (callType == CallType.VIDEO) {
            this.videoEnabled = true;
        }
        setState(CallState.ACCEPT_SENT);
        scheduleHandshakeTimeout();
        CallSignal accept = CallSignal.of(CallSignal.ACCEPT, callId, currentUserId).to(primaryPeerId);
        accept.e2eeKeyB64 = e2ee.exportMasterKeyBase64();
        accept.e2eeKeyIndex = e2ee.getKeyIndex();
        signaling.send(accept);

        ensurePeer(primaryPeerId, primaryPeerName);
        if (pendingInvitePayload != null) joinGroupMesh(pendingInvitePayload);
        maybeInitiate(primaryPeerId);
        notifyInfo();
    }

    public void decline(CallEndReason reason) {
        if (state != CallState.RECEIVED_CALL && state != CallState.PRE_ACCEPT_RECEIVED) return;
        cancelOfferExpiry();
        cancelIncomingRingingWatchdog();
        CallEndReason why = reason == null ? CallEndReason.DECLINED : reason;
        if (primaryPeerId != null) {
            CallSignal decline = CallSignal.of(CallSignal.DECLINE, callId, currentUserId).to(primaryPeerId);
            decline.payload = reasonPayload(why);
            signaling.send(decline);
        }
        endCall(why, false);
    }

    public void hangup() {
        hangup(CallEndReason.LOCAL_HANGUP);
    }

    public void hangup(CallEndReason reason) {
        cancelRingingHeartbeat();
        endCall(reason == null ? CallEndReason.LOCAL_HANGUP : reason, true);
    }

    // ---- controls ------------------------------------------------------------

    public void setMuted(boolean value) {
        muted = value;
        audioDevice.setMuted(value);
        for (Peer peer : peers.values()) {
            if (peer.localAudioTrack != null) peer.localAudioTrack.setEnabled(!value);
        }
        CallParticipant self = selfParticipant();
        if (self != null) self.muted = value;
        broadcastControl(mutePayload(value), CallSignal.MUTE);
        notifyInfo();
    }

    public void setSpeakerOn(boolean speaker) {
        audioDevice.setSpeakerOn(speaker);
        notifyRoute();
        notifyInfo();
    }

    public boolean isSpeakerOn() {
        return audioDevice != null && audioDevice.isSpeakerOn();
    }

    public CallInfo getInfo() {
        return getCallInfo();
    }

    public void setAudioRoute(CallAudioRoute route) {
        audioDevice.setRoute(route);
        notifyRoute();
    }

    public CallAudioRoute cycleAudioRoute() {
        CallAudioRoute route = audioDevice.cycleRoute();
        notifyRoute();
        return route;
    }

    /**
     * Enables/disables local video. On an audio call this raises a consent
     * request with the peer before any camera is opened; on a video call it
     * simply starts/stops sending and lets perfect negotiation renegotiate.
     */
    public boolean setVideoEnabled(boolean enable) {
        if (!isInCall()) return false;
        if (enable) {
            if (callType == CallType.AUDIO) {
                if (!localVideoUpgradeRequested) {
                    localVideoUpgradeRequested = true;
                    broadcastControl(null, CallSignal.VIDEO_UPGRADE_REQUEST);
                    notifyInfo();
                }
                return true;
            }
            if (videoEnabled) return true;
            videoEnabled = true;
            applyLocalVideo(true);
            broadcastControl("true", CallSignal.VIDEO_STATE);
            CallParticipant self = selfParticipant();
            if (self != null) self.videoEnabled = true;
            audioDevice.setRoute(CallAudioRoute.SPEAKER);
            notifyRoute();
        } else {
            if (localVideoUpgradeRequested) {
                localVideoUpgradeRequested = false;
                broadcastControl(null, CallSignal.VIDEO_UPGRADE_REJECT);
                notifyInfo();
                return true;
            }
            if (!videoEnabled) return true;
            videoEnabled = false;
            applyLocalVideo(false);
            broadcastControl("false", CallSignal.VIDEO_STATE);
            CallParticipant self = selfParticipant();
            if (self != null) self.videoEnabled = false;
            audioDevice.setRoute(CallAudioRoute.EARPIECE);
            notifyRoute();
        }
        notifyInfo();
        return true;
    }

    public boolean isVideoEnabled() {
        return videoEnabled;
    }

    /** Answer an incoming video-upgrade request. */
    public void respondVideoUpgrade(boolean accept) {
        if (!remoteVideoUpgradeRequested) return;
        remoteVideoUpgradeRequested = false;
        broadcastControl(null, accept ? CallSignal.VIDEO_UPGRADE_ACCEPT : CallSignal.VIDEO_UPGRADE_REJECT);
        if (accept) {
            callType = CallType.VIDEO;
            videoEnabled = true;
            applyLocalVideo(true);
            broadcastControl("true", CallSignal.VIDEO_STATE);
            CallParticipant self = selfParticipant();
            if (self != null) self.videoEnabled = true;
            audioDevice.setRoute(CallAudioRoute.SPEAKER);
            notifyRoute();
        }
        notifyInfo();
    }

    public boolean isRemoteVideoUpgradeRequested() {
        return remoteVideoUpgradeRequested;
    }

    public boolean isLocalVideoUpgradeRequested() {
        return localVideoUpgradeRequested;
    }

    private void applyLocalVideo(boolean enable) {
        if (enable) {
            int w = lowDataMode ? config.lowDataWidth : config.videoWidth;
            int h = lowDataMode ? config.lowDataHeight : config.videoHeight;
            int fps = lowDataMode ? config.lowDataFps : config.videoFps;
            if (!videoDevice.startCamera(w, h, fps)) {
                emitError("Camera unavailable");
                videoEnabled = false;
                return;
            }
            VideoTrack newTrack = videoDevice.getCameraTrack();
            for (Peer peer : peers.values()) {
                if (peer.videoTx == null || peer.pc == null) continue;
                peer.localVideoTrack = newTrack;
                if (peer.videoSender == null) peer.videoSender = peer.videoTx.getSender();
                try {
                    peer.videoTx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                } catch (Throwable ignored) { }
                if (peer.videoSender != null && peer.localVideoTrack != null) {
                    try {
                        peer.videoSender.setTrack(peer.localVideoTrack, false);
                    } catch (Throwable ignored) { }
                    applyBitrate(peer);
                    if (config.e2ee && e2ee.isSupported()) {
                        e2ee.attachSender(CallMediaFactory.factory(), peer.videoSender, currentUserId + ":video");
                    }
                }
            }
        } else {
            for (Peer peer : peers.values()) {
                if (peer.videoSender != null) {
                    try {
                        peer.videoSender.setTrack(null, false);
                    } catch (Throwable ignored) { }
                }
                if (peer.videoTx != null) {
                    try {
                        peer.videoTx.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY);
                    } catch (Throwable ignored) { }
                }
                peer.localVideoTrack = null;
                if (config.e2ee && e2ee.isSupported()) {
                    e2ee.detachParticipant(currentUserId + ":video");
                }
            }
            videoDevice.stopCamera();
        }
    }

    public boolean startScreenShare(Intent permissionData) {
        if (!isInCall()) return false;
        int w = lowDataMode ? config.lowDataWidth : config.videoWidth;
        int h = lowDataMode ? config.lowDataHeight : config.videoHeight;
        if (!videoDevice.startScreenCapture(permissionData, w, h, Math.min(config.videoFps, 15))) {
            emitError("Screen capture permission denied");
            return false;
        }
        screenSharing = true;
        VideoTrack track = videoDevice.getScreenTrack();
        for (Peer peer : peers.values()) {
            if (peer.screenTx == null) {
                RtpTransceiver.RtpTransceiverInit init = new RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        Collections.singletonList("veil-screen"));
                peer.screenTx = peer.pc.addTransceiver(track, init);
                peer.screenSender = peer.screenTx.getSender();
            } else {
                peer.screenTx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                if (peer.screenSender != null) peer.screenSender.setTrack(track, false);
            }
            if (peer.screenSender != null) {
                e2ee.attachSender(CallMediaFactory.factory(), peer.screenSender, currentUserId + ":screen");
            }
        }
        broadcastControl(null, CallSignal.SCREEN_START);
        notifyInfo();
        return true;
    }

    public void stopScreenShare() {
        if (!screenSharing) return;
        screenSharing = false;
        for (Peer peer : peers.values()) {
            if (peer.screenTx != null) {
                peer.screenTx.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE);
            }
            e2ee.detachParticipant(currentUserId + ":screen");
        }
        videoDevice.stopScreenCapture();
        broadcastControl(null, CallSignal.SCREEN_STOP);
        notifyInfo();
    }

    public boolean isScreenSharing() {
        return screenSharing;
    }

    public boolean switchCamera() {
        return videoDevice.switchCamera();
    }

    public void sendDtmf(String tones) {
        if (tones == null || tones.isEmpty()) return;
        for (Peer peer : peers.values()) {
            try {
                if (peer.audioSender != null && peer.audioSender.dtmf() != null) {
                    peer.audioSender.dtmf().insertDtmf(tones, 200, 70);
                }
            } catch (Throwable t) {
                Log.w(TAG, "dtmf failed: " + t.getMessage());
            }
        }
        broadcastControl(tones, CallSignal.DTMF);
    }

    public void sendReaction(String emoji) {
        CallParticipant self = selfParticipant();
        if (self != null) {
            self.reaction = emoji;
            self.reactionAtMs = System.currentTimeMillis();
            notifyParticipant(self);
        }
        broadcastControl(emoji, CallSignal.REACTION);
    }

    public void setRaiseHand(boolean raised) {
        CallParticipant self = selfParticipant();
        if (self != null) {
            self.handRaised = raised;
            notifyParticipant(self);
        }
        broadcastControl(Boolean.toString(raised), CallSignal.RAISE_HAND);
    }

    public void setLowDataMode(boolean enabled) {
        lowDataMode = enabled;
        if (videoEnabled) {
            int w = enabled ? config.lowDataWidth : config.videoWidth;
            int h = enabled ? config.lowDataHeight : config.videoHeight;
            int fps = enabled ? config.lowDataFps : config.videoFps;
            videoDevice.setFormat(w, h, fps);
        }
        for (Peer peer : peers.values()) applyBitrate(peer);
    }

    public boolean isLowDataMode() {
        return lowDataMode;
    }

    // ---- signaling -----------------------------------------------------------

    @Override
    public void onSignal(CallSignal signal) {
        if (signal == null) return;
        main.post(() -> handleSignal(signal, null));
    }

    private void handleSignal(CallSignal signal, String viaPeerId) {
        String from = signal.from != null ? signal.from : viaPeerId;
        if (from == null || from.equals(currentUserId)) return;

        switch (signal.type) {
            case CallSignal.INVITE:
                onInvite(signal, from);
                break;
            case CallSignal.RINGING:
                if (caller && callId != null && callId.equals(signal.callId)) {
                    setState(CallState.PRE_ACCEPT_RECEIVED);
                }
                break;
            case CallSignal.RINGING_PING:
                if (isSignalForCurrentCall(signal) || (state == CallState.RECEIVED_CALL && from != null && from.equals(primaryPeerId))) {
                    lastRingingPingMs = System.currentTimeMillis();
                }
                break;
            case CallSignal.ACCEPT:
                onAccepted(signal, from);
                break;
            case CallSignal.DECLINE:
                if (!isSignalForCurrentCall(signal) && !(isInCall() && from != null && from.equals(primaryPeerId))) {
                    rememberEnded(signal.callId, from);
                    break;
                }
                endCall(parseReason(signal.payload, CallEndReason.DECLINED), false);
                break;
            case CallSignal.END:
                if (!isSignalForCurrentCall(signal) && !(isInCall() && from != null && from.equals(primaryPeerId))) {
                    rememberEnded(signal.callId, from);
                    break;
                }
                if (groupId != null && peers.size() > 1 && from != null) {
                    removeGroupParticipant(from);
                    evaluateTopologyScaling();
                } else {
                    endCall(parseReason(signal.payload, CallEndReason.REMOTE_HANGUP), false);
                }
                break;
            case CallSignal.GROUP_LEAVE:
                if (from != null) {
                    removeGroupParticipant(from);
                    evaluateTopologyScaling();
                }
                break;
            case CallSignal.OFFER:
            case CallSignal.ANSWER:
            case CallSignal.CANDIDATE:
                if (!isSignalForCurrentCall(signal)) break;
                routeSdp(signal, from);
                break;
            case CallSignal.VIDEO_UPGRADE_REQUEST:
                remoteVideoUpgradeRequested = true;
                for (Listener l : listeners) l.onVideoUpgradeRequested(from, peerName(from));
                notifyInfo();
                break;
            case CallSignal.VIDEO_UPGRADE_ACCEPT:
                localVideoUpgradeRequested = false;
                remoteVideoUpgradeRequested = false;
                callType = CallType.VIDEO;
                videoEnabled = true;
                applyLocalVideo(true);
                broadcastControl("true", CallSignal.VIDEO_STATE);
                CallParticipant selfAcc = selfParticipant();
                if (selfAcc != null) selfAcc.videoEnabled = true;
                audioDevice.setRoute(CallAudioRoute.SPEAKER);
                notifyRoute();
                for (Listener l : listeners) l.onVideoUpgradeResponded(from, peerName(from), true);
                notifyInfo();
                break;
            case CallSignal.VIDEO_STATE:
                boolean remoteVideoOn = "true".equalsIgnoreCase(signal.payload);
                updateParticipantFlag(from, false, remoteVideoOn);
                broadcastControl(signal.payload, CallSignal.VIDEO_STATE);
                Peer pFrom = peers.get(from);
                if (!remoteVideoOn) {
                    if (pFrom != null && pFrom.participant != null) {
                        pFrom.participant.videoTrack = null;
                    }
                    for (Listener l : listeners) {
                        l.onRemoteVideoTrackRemoved(from, false);
                    }
                } else if (pFrom != null) {
                    scanReceivers(pFrom);
                }
                notifyInfo();
                break;
            case CallSignal.VIDEO_UPGRADE_REJECT:
                localVideoUpgradeRequested = false;
                remoteVideoUpgradeRequested = false;
                for (Listener l : listeners) l.onVideoUpgradeResponded(from, peerName(from), false);
                notifyInfo();
                break;
            case CallSignal.SCREEN_START:
                updateParticipantFlag(from, true, null);
                broadcastControl(null, CallSignal.SCREEN_START);
                break;
            case CallSignal.SCREEN_STOP:
                updateParticipantFlag(from, false, null);
                broadcastControl(null, CallSignal.SCREEN_STOP);
                break;
            case CallSignal.MUTE:
                updateParticipantMute(from, "true".equals(signal.payload));
                broadcastControl(signal.payload, CallSignal.MUTE);
                break;
            case CallSignal.REACTION:
                updateParticipantReaction(from, signal.payload);
                broadcastControl(signal.payload, CallSignal.REACTION);
                break;
            case CallSignal.RAISE_HAND:
                updateParticipantHand(from, "true".equals(signal.payload));
                broadcastControl(signal.payload, CallSignal.RAISE_HAND);
                break;
            case CallSignal.DTMF:
            case CallSignal.PARTICIPANT_UPDATE:
            case CallSignal.PING:
                break;
            case CallSignal.KEY_ANNOUNCE:
                if (signal.e2eeKeyB64 != null) {
                    e2ee.importMasterKeyBase64(signal.e2eeKeyB64);
                    notifyE2ee();
                }
                break;
            case CallSignal.KEY_RATCHET:
                e2ee.adoptRemoteKeyIndex(signal.e2eeKeyIndex);
                break;
            case CallSignal.GROUP_INVITE:
                onGroupInvite(signal, from);
                break;
            default:
                break;
        }
    }

    public void onExternalInvite(String fromUserId, String incomingCallId, String callerName, String incomingCallType) {
        if (fromUserId == null || incomingCallId == null || incomingCallId.isEmpty()) return;
        main.post(() -> {
            if (endedCallIds.contains(incomingCallId)) return;
            if (isInCall() && callId != null && callId.equals(incomingCallId)) return;
            CallSignal sig = CallSignal.of(CallSignal.INVITE, incomingCallId, fromUserId)
                    .to(currentUserId != null ? currentUserId : "");
            sig.fromName = callerName;
            sig.callType = incomingCallType != null ? incomingCallType : "audio";
            sig.timestamp = System.currentTimeMillis();
            onInvite(sig, fromUserId);
        });
    }

    private void onInvite(CallSignal signal, String from) {
        if (signal.callId != null && endedCallIds.contains(signal.callId)) {
            Log.i(TAG, "ignoring invite for already-ended call " + signal.callId);
            return;
        }
        long ageMs = System.currentTimeMillis() - signal.timestamp;
        if (signal.timestamp > 0 && ageMs > Math.max(config.ringingTimeoutMs, 120_000L)) {
            // The caller rang while this device was offline and has long since
            // given up: record a missed call instead of ringing a dead session.
            missedStaleCall(signal, from, ageMs);
            return;
        }
        if (isInCall()) {
            if (callId != null && callId.equals(signal.callId)) {
                return;
            }
            CallSignal busy = CallSignal.of(CallSignal.DECLINE, signal.callId, currentUserId).to(from);
            busy.payload = reasonPayload(CallEndReason.BUSY);
            signaling.send(busy);
            return;
        }
        caller = false;
        callId = signal.callId;
        callType = CallType.fromWire(signal.callType);
        groupId = signal.groupId;
        groupName = signal.groupName;
        primaryPeerId = from;
        String remoteName = signal.fromName;
        if (isSelfName(remoteName)) {
            try {
                com.m3.veil.model.Chat c = com.m3.veil.database.EncryptedChatDatabase.getInstance(context).getChat(from);
                if (c != null && c.getName() != null && !isSelfName(c.getName())) {
                    remoteName = c.getName().trim();
                }
            } catch (Throwable ignored) {}
        }
        if (isSelfName(remoteName)) {
            remoteName = "Veil Contact";
        }
        primaryPeerName = remoteName;
        pendingInviteKey = signal.e2eeKeyB64;
        pendingInvitePayload = signal.payload;
        selfParticipant = new CallParticipant(currentUserId, getResolvedDisplayName(), true);
        startedAtMs = System.currentTimeMillis();
        connectedAtMs = 0;
        endedAtMs = 0;
        endReason = null;
        videoEnabled = false;
        muted = false;
        resetStats();
        putParticipant(from, primaryPeerName);

        setState(CallState.RECEIVED_CALL);
        CallSignal ringing = CallSignal.of(CallSignal.RINGING, callId, currentUserId).to(from);
        signaling.send(ringing);
        scheduleOfferExpiry();
        startIncomingRingingWatchdog();
        CallInfo info = buildInfo();
        for (Listener l : listeners) l.onIncomingCall(info);
        CallNotificationHelper.showIncomingCall(context, info);
        notifyInfo();
    }

    private boolean isSignalForCurrentCall(CallSignal signal) {
        return isInCall() && signal.callId != null && callId != null && signal.callId.equals(callId);
    }

    private void rememberEnded(String id, String from) {
        if (id != null) endedCallIds.add(id);
        Log.d(TAG, "recorded ended call " + id + " (from " + from + ")");
    }

    private void missedStaleCall(CallSignal signal, String from, long ageMs) {
        Log.i(TAG, "dropping stale invite from " + from + " age=" + ageMs + "ms");
        endedCallIds.add(signal.callId != null ? signal.callId : "");
        String name = signal.fromName != null ? signal.fromName : from;
        boolean video = "video".equalsIgnoreCase(signal.callType);
        try {
            CallNotificationHelper.showMissedCall(context, name, video);
        } catch (Throwable ignored) { }
        try {
            String safeName = (name != null && !name.trim().isEmpty() && !isSelfName(name)) ? name.trim() : "Veil Contact";
            if (isSelfName(safeName)) {
                try {
                    com.m3.veil.model.Chat c = com.m3.veil.database.EncryptedChatDatabase.getInstance(context).getChat(from);
                    if (c != null && c.getName() != null && !isSelfName(c.getName())) {
                        safeName = c.getName().trim();
                    }
                } catch (Throwable ignored) {}
            }
            if (isSelfName(safeName)) safeName = "Veil Contact";

            String selfName = getResolvedDisplayName();
            CallRecord record = new CallRecord(signal.callId, from, safeName, from, safeName, currentUserId, selfName,
                    signal.callType, "missed", 0, Timestamp.now());
            final Context appContext = context;
            diskIo.execute(() -> {
                try {
                    EncryptedChatDatabase.getInstance(appContext).saveCallRecord(record);
                } catch (Throwable t) {
                    Log.w(TAG, "missed call log failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "missed call record failed: " + t.getMessage());
        }
    }

    private void onAccepted(CallSignal signal, String from) {
        if (!isInCall()) return;
        if (signal.e2eeKeyB64 != null) e2ee.importMasterKeyBase64(signal.e2eeKeyB64);
        cancelRingingTimeout();
        cancelRingingHeartbeat();
        ensurePeer(from, peerName(from));
        maybeInitiate(from);
        setState(CallState.ACCEPT_RECEIVED);
        scheduleHandshakeTimeout();
        notifyE2ee();
    }

    private void onGroupInvite(CallSignal signal, String from) {
        if (!isInCall()) {
            onInvite(signal, from);
            return;
        }
        if (groupFromPayload(signal.payload) == null) return;
        ensurePeer(from, peerName(from));
        maybeInitiate(from);
    }

    private void joinGroupMesh(String rosterJson) {
        List<Target> roster = groupFromPayload(rosterJson);
        if (roster == null) return;
        for (Target target : roster) {
            if (target.peerId == null || target.peerId.equals(currentUserId)
                    || target.peerId.equals(primaryPeerId)) continue;
            CallSignal invite = CallSignal.of(CallSignal.GROUP_INVITE, callId, currentUserId).to(target.peerId);
            invite.fromName = displayName;
            invite.callType = callType.wire;
            invite.groupId = groupId;
            invite.groupName = groupName;
            invite.payload = rosterJson;
            signaling.send(invite);
            ensurePeer(target.peerId, target.name);
            maybeInitiate(target.peerId);
        }
    }

    private List<Target> groupFromPayload(String payload) {
        if (payload == null) return null;
        try {
            JSONArray array = new JSONArray(payload);
            List<Target> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new Target(o.optString("id"), o.optString("name")));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void routeSdp(CallSignal signal, String from) {
        Peer peer = peers.get(from);
        if (peer == null || peer.pc == null) {
            deferred.computeIfAbsent(from, k -> new ArrayList<>()).add(signal);
            return;
        }
        if (CallSignal.OFFER.equals(signal.type)) {
            handleRemoteOffer(peer, signal.sdp);
        } else if (CallSignal.ANSWER.equals(signal.type)) {
            handleRemoteAnswer(peer, signal.sdp);
        } else {
            handleRemoteCandidate(peer, signal);
        }
    }

    // ---- peer + negotiation --------------------------------------------------

    public void onPermissionsGranted() {
        if (!isInCall()) return;
        if (callType == CallType.VIDEO || videoEnabled) {
            int w = lowDataMode ? config.lowDataWidth : config.videoWidth;
            int h = lowDataMode ? config.lowDataHeight : config.videoHeight;
            int fps = lowDataMode ? config.lowDataFps : config.videoFps;
            if (videoDevice.startCamera(w, h, fps)) {
                VideoTrack track = videoDevice.getCameraTrack();
                for (Peer peer : peers.values()) {
                    if (peer.pc == null) continue;
                    peer.localVideoTrack = track;
                    if (peer.videoTx != null) {
                        peer.videoSender = peer.videoTx.getSender();
                        peer.videoTx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                        if (peer.videoSender != null && track != null) {
                            peer.videoSender.setTrack(track, false);
                            applyBitrate(peer);
                            if (config.e2ee && e2ee.isSupported()) {
                                e2ee.attachSender(CallMediaFactory.factory(), peer.videoSender, currentUserId + ":video");
                            }
                        }
                    }
                }
                notifyInfo();
            }
        }
    }

    private boolean isInitiator(String peerId) {
        if (groupId == null) {
            return caller;
        }
        return currentUserId != null && peerId != null && currentUserId.compareTo(peerId) < 0;
    }

    private Peer ensurePeer(String peerId, String name) {
        Peer existing = peers.get(peerId);
        if (existing != null && existing.pc != null) return existing;
        if (!CallMediaFactory.isReady()) return null;

        Peer peer = existing != null ? existing : new Peer(peerId, name != null ? name : peerId);
        peer.lastPacketReceivedMs = System.currentTimeMillis();
        if (peer.participant == null) peer.participant = new CallParticipant(peerId, name != null ? name : peerId, false);
        peer.polite = !isInitiator(peerId);
        peer.pc = CallMediaFactory.createPeerConnection(config, new PeerObserver(peer));
        if (peer.pc == null) {
            peers.remove(peerId);
            return null;
        }
        peers.put(peerId, peer);
        putParticipant(peerId, peer.name);
        evaluateTopologyScaling();

        if (isInitiator(peerId)) {
            attachLocalAudio(peer);
            if (callType == CallType.VIDEO || videoEnabled) {
                attachLocalVideo(peer);
            } else {
                RtpTransceiver.RtpTransceiverInit init = new RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                        Collections.singletonList("veil-video"));
                peer.videoTx = peer.pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init);
                peer.videoSender = peer.videoTx.getSender();
            }
            ensureControlChannel(peer);
            applyBitrate(peer);
        }

        List<CallSignal> pending = deferred.remove(peerId);
        if (pending != null) {
            for (CallSignal signal : pending) routeSdp(signal, peerId);
        }
        return peer;
    }

    private void bindAnswererLocalMedia(Peer peer) {
        if (peer == null || peer.pc == null) return;
        try {
            if (sharedAudioTrack == null) {
                sharedAudioSource = CallMediaFactory.createAudioSource();
                sharedAudioTrack = CallMediaFactory.createAudioTrack(sharedAudioSource);
            }
            sharedAudioTrack.setEnabled(!muted);
            peer.localAudioTrack = sharedAudioTrack;

            VideoTrack videoTrack = null;
            if (callType == CallType.VIDEO || videoEnabled) {
                int w = lowDataMode ? config.lowDataWidth : config.videoWidth;
                int h = lowDataMode ? config.lowDataHeight : config.videoHeight;
                int fps = lowDataMode ? config.lowDataFps : config.videoFps;
                boolean haveCamera = videoDevice.isCameraRunning() || videoDevice.startCamera(w, h, fps);
                videoTrack = haveCamera ? videoDevice.getCameraTrack() : null;
                if (videoTrack != null) peer.localVideoTrack = videoTrack;
            }

            for (RtpTransceiver tx : peer.pc.getTransceivers()) {
                if (tx == null || tx.getMediaType() == null) continue;
                if (tx.getMediaType() == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && peer.audioTx == null) {
                    peer.audioTx = tx;
                    peer.audioSender = tx.getSender();
                    if (peer.audioSender != null && peer.localAudioTrack != null) {
                        peer.audioSender.setTrack(peer.localAudioTrack, false);
                        if (config.e2ee && e2ee.isSupported()) {
                            e2ee.attachSender(CallMediaFactory.factory(), peer.audioSender, currentUserId + ":audio");
                        }
                    }
                    tx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                } else if (tx.getMediaType() == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && peer.videoTx == null) {
                    peer.videoTx = tx;
                    peer.videoSender = tx.getSender();
                    CallMediaFactory.applyCodecPreferences(peer.videoTx, config);
                    if (videoTrack != null && peer.videoSender != null) {
                        peer.videoSender.setTrack(videoTrack, false);
                        tx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                        if (config.e2ee && e2ee.isSupported()) {
                            e2ee.attachSender(CallMediaFactory.factory(), peer.videoSender, currentUserId + ":video");
                        }
                    } else {
                        tx.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY);
                    }
                }
            }
            applyBitrate(peer);
        } catch (Throwable t) {
            Log.e(TAG, "bindAnswererLocalMedia failed", t);
        }
    }

    private void attachLocalAudio(Peer peer) {
        try {
            if (sharedAudioTrack == null) {
                sharedAudioSource = CallMediaFactory.createAudioSource();
                sharedAudioTrack = CallMediaFactory.createAudioTrack(sharedAudioSource);
            }
            sharedAudioTrack.setEnabled(!muted);
            peer.localAudioTrack = sharedAudioTrack;
            List<org.webrtc.RtpParameters.Encoding> encodings = new ArrayList<>();
            encodings.add(new org.webrtc.RtpParameters.Encoding("a", true, 1.0));
            RtpTransceiver.RtpTransceiverInit init = new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                    Collections.singletonList("veil-audio"), encodings);
            peer.audioTx = peer.pc.addTransceiver(peer.localAudioTrack, init);
            peer.audioSender = peer.audioTx.getSender();
            if (config.e2ee && e2ee.isSupported()) {
                e2ee.attachSender(CallMediaFactory.factory(), peer.audioSender, currentUserId + ":audio");
            }
        } catch (Throwable t) {
            Log.e(TAG, "attachLocalAudio failed", t);
        }
    }

    private void attachLocalVideo(Peer peer) {
        int w = lowDataMode ? config.lowDataWidth : config.videoWidth;
        int h = lowDataMode ? config.lowDataHeight : config.videoHeight;
        int fps = lowDataMode ? config.lowDataFps : config.videoFps;
        boolean haveCamera = videoDevice.isCameraRunning() || videoDevice.startCamera(w, h, fps);
        VideoTrack track = haveCamera ? videoDevice.getCameraTrack() : null;
        if (track != null) peer.localVideoTrack = track;

        List<org.webrtc.RtpParameters.Encoding> encodings = new ArrayList<>();
        if (config.simulcast && groupId != null) {
            encodings.add(new org.webrtc.RtpParameters.Encoding("h", true, 1.0));
            encodings.add(new org.webrtc.RtpParameters.Encoding("m", true, 2.0));
            encodings.add(new org.webrtc.RtpParameters.Encoding("l", true, 4.0));
        }
        RtpTransceiver.RtpTransceiverDirection direction = track != null
                ? RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                : RtpTransceiver.RtpTransceiverDirection.RECV_ONLY;
        RtpTransceiver.RtpTransceiverInit init = new RtpTransceiver.RtpTransceiverInit(
                direction, Collections.singletonList("veil-video"), encodings);
        // Even without camera access we must create a receive-capable video
        // transceiver so the peer's video has somewhere to land.
        peer.videoTx = track != null
                ? peer.pc.addTransceiver(track, init)
                : peer.pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init);
        peer.videoSender = peer.videoTx.getSender();
        CallMediaFactory.applyCodecPreferences(peer.videoTx, config);
        if (track != null && config.e2ee && e2ee.isSupported()) {
            e2ee.attachSender(CallMediaFactory.factory(), peer.videoSender, currentUserId + ":video");
        }
    }

    /**
     * Attaches remote video tracks already present on the connection.
     *
     * <p>Under Unified Plan the remote track arrives on an existing
     * transceiver's receiver and {@code onAddTrack} is not reliably invoked, so
     * we scan receivers after every remote description and again on connect.
     */
    private void scanReceivers(Peer peer) {
        if (peer == null || peer.pc == null || peer.participant == null) return;
        try {
            for (RtpReceiver receiver : peer.pc.getReceivers()) {
                if (receiver == null || receiver.track() == null) continue;
                if (receiver.track() instanceof AudioTrack) {
                    AudioTrack audioTrack = (AudioTrack) receiver.track();
                    peer.remoteAudioTrack = audioTrack;
                    if (config.e2ee && e2ee.isSupported()) {
                        e2ee.attachReceiver(CallMediaFactory.factory(), receiver, peer.peerId + ":audio");
                    }
                    continue;
                }
                if (!(receiver.track() instanceof VideoTrack)) continue;
                VideoTrack track = (VideoTrack) receiver.track();
                boolean screen = peer.screenTx != null && peer.screenTx.getReceiver() == receiver;
                VideoTrack current = screen ? peer.participant.screenTrack : peer.participant.videoTrack;
                boolean isNewTrack = (current != track);
                if (isNewTrack) {
                    if (screen) {
                        peer.participant.screenTrack = track;
                        peer.participant.screenSharing = true;
                    } else {
                        peer.participant.videoTrack = track;
                        peer.participant.videoEnabled = true;
                    }
                    if (config.e2ee && e2ee.isSupported()) {
                        e2ee.attachReceiver(CallMediaFactory.factory(), receiver,
                                peer.peerId + (screen ? ":screen" : ":video"));
                    }
                    final VideoTrack notifyTrack = track;
                    final boolean notifyScreen = screen;
                    main.post(() -> {
                        for (Listener l : listeners) l.onRemoteVideoTrack(peer.peerId, notifyTrack, notifyScreen);
                        notifyInfo();
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scanReceivers failed: " + t.getMessage());
        }
    }

    private void applyBitrate(Peer peer) {
        try {
            int videoBitrate = lowDataMode ? config.maxVideoBitrateLowData : config.maxVideoBitrate;
            if (peer.videoSender != null) {
                org.webrtc.RtpParameters params = peer.videoSender.getParameters();
                if (params != null) {
                    params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.BALANCED;
                    if (params.encodings != null) {
                        for (org.webrtc.RtpParameters.Encoding encoding : params.encodings) {
                            encoding.maxBitrateBps = Integer.valueOf(videoBitrate);
                            encoding.minBitrateBps = Integer.valueOf(150_000);
                            encoding.maxFramerate = Integer.valueOf(config.videoFps);
                        }
                    }
                    peer.videoSender.setParameters(params);
                }
            }
            if (peer.audioSender != null) {
                org.webrtc.RtpParameters params = peer.audioSender.getParameters();
                if (params != null && params.encodings != null) {
                    for (org.webrtc.RtpParameters.Encoding encoding : params.encodings) {
                        encoding.maxBitrateBps = Integer.valueOf(config.maxAudioBitrate);
                    }
                    peer.audioSender.setParameters(params);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyBitrate failed: " + t.getMessage());
        }
    }

    private void maybeInitiate(String peerId) {
        if (currentUserId == null || peerId == null) return;
        if (isInitiator(peerId)) {
            Peer peer = peers.get(peerId);
            if (peer != null && peer.pc != null) {
                ensureControlChannel(peer);
                negotiate(peer);
            }
        }
    }

    private void ensureControlChannel(Peer peer) {
        if (peer.ctrl != null) return;
        try {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            peer.ctrl = peer.pc.createDataChannel(CTRL_LABEL, init);
            registerControlChannel(peer);
        } catch (Throwable t) {
            Log.w(TAG, "createDataChannel failed: " + t.getMessage());
        }
    }

    /**
     * Elite Opus voice optimization:
     * - useinbandfec=1: Enables Forward Error Correction to repair dropped packets
     * - usedtx=1: Discontinuous Transmission (transmits 0 bytes during silence)
     * - maxaveragebitrate=32000: Optimal speech clarity bitrate (matches Telegram VoIP)
     * - stereo=0: Forces single-channel mono speech (halves CPU and transport cost)
     * - cbr=0: Dynamic Variable Bitrate (adapts to real-time wireless channel conditions)
     */
    private SessionDescription optimizeSdp(SessionDescription sdp) {
        if (sdp == null || sdp.description == null) return sdp;
        String desc = sdp.description;
        try {
            if (desc.contains("opus/48000")) {
                String[] lines = desc.split("\\r?\\n");
                StringBuilder out = new StringBuilder();
                String opusPayload = "111";
                boolean fmtpFound = false;
                for (String line : lines) {
                    if (line.startsWith("a=rtpmap:") && line.contains("opus/48000")) {
                        String[] parts = line.split(" ")[0].split(":");
                        if (parts.length > 1) {
                            opusPayload = parts[1];
                        }
                    }
                    if (line.startsWith("a=fmtp:" + opusPayload)) {
                        fmtpFound = true;
                        String params = "useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0;cbr=0";
                        if (!line.contains("useinbandfec=1")) {
                            line = line + ";" + params;
                        } else {
                            line = "a=fmtp:" + opusPayload + " " + params;
                        }
                    }
                    out.append(line).append("\r\n");
                    if (line.startsWith("a=rtpmap:") && line.contains("opus/48000") && !fmtpFound) {
                        out.append("a=fmtp:").append(opusPayload)
                                .append(" useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0;cbr=0\r\n");
                        fmtpFound = true;
                    }
                }
                desc = out.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "optimizeSdp failed: " + t.getMessage());
        }
        return new SessionDescription(sdp.type, desc);
    }

    private void negotiate(Peer peer) {
        if (peer == null || peer.pc == null) return;
        if (peer.makingOffer) return; // debounce: one outstanding offer per peer
        peer.makingOffer = true;
        peer.pc.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                if (peer.pc == null) return;
                final SessionDescription optimized = optimizeSdp(sdp);
                peer.pc.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) { }
                    @Override public void onSetSuccess() {
                        peer.makingOffer = false;
                        sendSdp(peer, CallSignal.OFFER, optimized);
                    }
                    @Override public void onCreateFailure(String s) { }
                    @Override public void onSetFailure(String s) {
                        peer.makingOffer = false;
                        Log.w(TAG, "setLocalDescription(offer) failed: " + s);
                    }
                }, optimized);
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String s) {
                peer.makingOffer = false;
                Log.w(TAG, "createOffer failed: " + s);
            }
            @Override public void onSetFailure(String s) { }
        }, new MediaConstraints());
    }

    private void handleRemoteOffer(Peer peer, String sdp) {
        boolean readyForOffer = !peer.makingOffer
                && (peer.pc.signalingState() == PeerConnection.SignalingState.STABLE || peer.isSettingRemoteAnswerPending);
        boolean offerCollision = !readyForOffer && !peer.polite;
        peer.ignoreOffer = !peer.polite && offerCollision;
        if (peer.ignoreOffer) return;
        peer.pc.setRemoteDescription(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) { }
            @Override public void onSetSuccess() {
                peer.hasRemoteDescription = true;
                peer.lastPacketReceivedMs = System.currentTimeMillis();
                bindAnswererLocalMedia(peer);
                drainCandidates(peer);
                scanReceivers(peer);
                createAnswer(peer);
            }
            @Override public void onCreateFailure(String s) { }
            @Override public void onSetFailure(String s) {
                Log.w(TAG, "setRemoteDescription(offer) failed: " + s);
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    private void createAnswer(Peer peer) {
        if (peer.pc == null) return;
        peer.pc.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                if (peer.pc == null) return;
                final SessionDescription optimized = optimizeSdp(sdp);
                peer.pc.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) { }
                    @Override public void onSetSuccess() { sendSdp(peer, CallSignal.ANSWER, optimized); }
                    @Override public void onCreateFailure(String s) { }
                    @Override public void onSetFailure(String s) {
                        Log.w(TAG, "setLocalDescription(answer) failed: " + s);
                    }
                }, optimized);
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String s) { Log.w(TAG, "createAnswer failed: " + s); }
            @Override public void onSetFailure(String s) { }
        }, new MediaConstraints());
    }

    private void handleRemoteAnswer(Peer peer, String sdp) {
        peer.isSettingRemoteAnswerPending = true;
        peer.pc.setRemoteDescription(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) { }
            @Override public void onSetSuccess() {
                peer.isSettingRemoteAnswerPending = false;
                peer.hasRemoteDescription = true;
                drainCandidates(peer);
                scanReceivers(peer);
            }
            @Override public void onCreateFailure(String s) { }
            @Override public void onSetFailure(String s) {
                peer.isSettingRemoteAnswerPending = false;
                Log.w(TAG, "setRemoteDescription(answer) failed: " + s);
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    private void handleRemoteCandidate(Peer peer, CallSignal signal) {
        if (signal.candidateSdp == null) return;
        if (signal.candidateSdp.contains("typ srflx") || signal.candidateSdp.contains("typ prflx")) {
            try {
                String[] parts = signal.candidateSdp.split("\\s+");
                if (parts.length >= 6) {
                    com.m3.veil.webrtc.SovereignNatTraversalEngine.getInstance(context)
                            .executeSymmetricPortSpray(peer.peerId, parts[4], Integer.parseInt(parts[5]));
                }
            } catch (Exception ignored) {}
        }
        IceCandidate candidate = new IceCandidate(signal.candidateMid, signal.candidateLine, signal.candidateSdp);
        if (!peer.hasRemoteDescription) {
            peer.pendingRemoteCandidates.add(candidate);
            return;
        }
        try {
            peer.pc.addIceCandidate(candidate);
        } catch (Throwable t) {
            Log.w(TAG, "addIceCandidate failed: " + t.getMessage());
        }
    }

    private void drainCandidates(Peer peer) {
        for (IceCandidate candidate : new ArrayList<>(peer.pendingRemoteCandidates)) {
            try {
                peer.pc.addIceCandidate(candidate);
            } catch (Throwable ignored) { }
        }
        peer.pendingRemoteCandidates.clear();
    }

    private void sendSdp(Peer peer, String type, SessionDescription sdp) {
        CallSignal signal = CallSignal.of(type, callId, currentUserId).to(peer.peerId);
        signal.sdpType = sdp.type.canonicalForm();
        signal.sdp = sdp.description;
        signaling.send(signal);
    }

    // ---- control channel -----------------------------------------------------

    private void registerControlChannel(Peer peer) {
        if (peer.ctrl == null) return;
        peer.ctrl.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) { }
            @Override public void onStateChange() {
                Log.d(TAG, "ctrl channel " + peer.peerId + " -> " + peer.ctrl.state());
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer data = buffer.data;
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                peer.lastPacketReceivedMs = System.currentTimeMillis();
                CallSignal signal = CallSignal.decode(new String(bytes, StandardCharsets.UTF_8));
                if (signal != null) {
                    if (CallSignal.PING.equals(signal.type)) {
                        if (peer.ctrl != null && peer.ctrl.state() == DataChannel.State.OPEN) {
                            CallSignal pong = CallSignal.of("pong", callId, currentUserId).to(peer.peerId);
                            try {
                                peer.ctrl.send(new DataChannel.Buffer(
                                        ByteBuffer.wrap(pong.encode().getBytes(StandardCharsets.UTF_8)), false));
                            } catch (Throwable ignored) { }
                        }
                        return;
                    } else if ("pong".equals(signal.type)) {
                        return;
                    }
                    main.post(() -> handleSignal(signal, peer.peerId));
                }
            }
        });
    }

    private void broadcastControl(String payload, String type) {
        for (Peer peer : peers.values()) {
            CallSignal signal = CallSignal.of(type, callId, currentUserId).to(peer.peerId);
            signal.payload = payload;
            boolean sentOverChannel = false;
            if (peer.ctrl != null && peer.ctrl.state() == DataChannel.State.OPEN) {
                try {
                    sentOverChannel = peer.ctrl.send(new DataChannel.Buffer(
                            ByteBuffer.wrap(signal.encode().getBytes(StandardCharsets.UTF_8)), false));
                } catch (Throwable ignored) { }
            }
            if (!sentOverChannel) signaling.send(signal);
        }
    }

    private void sendToPeer(String peerId, String payload, String type) {
        Peer peer = peers.get(peerId);
        if (peer == null) return;
        CallSignal signal = CallSignal.of(type, callId, currentUserId).to(peerId);
        signal.payload = payload;
        boolean sentOverChannel = false;
        if (peer.ctrl != null && peer.ctrl.state() == DataChannel.State.OPEN) {
            try {
                sentOverChannel = peer.ctrl.send(new DataChannel.Buffer(
                        ByteBuffer.wrap(signal.encode().getBytes(StandardCharsets.UTF_8)), false));
            } catch (Throwable ignored) { }
        }
        if (!sentOverChannel) signaling.send(signal);
    }

    // ---- participants --------------------------------------------------------

    private CallParticipant selfParticipant() {
        if (selfParticipant == null && currentUserId != null) {
            selfParticipant = new CallParticipant(currentUserId, displayName, true);
        }
        return selfParticipant;
    }

    private void putParticipant(String peerId, String name) {
        Peer peer = peers.get(peerId);
        if (peer == null) {
            peer = new Peer(peerId, name);
            peers.put(peerId, peer);
            peer.participant = new CallParticipant(peerId, name, false);
        } else if (peer.participant == null) {
            peer.participant = new CallParticipant(peerId, name, false);
        }
        peer.participant.e2eeEnabled = e2ee.isEnabled();
        notifyParticipant(peer.participant);
    }

    private void updateParticipantFlag(String peerId, boolean screenValue, Boolean video) {
        Peer peer = peers.get(peerId);
        if (peer == null || peer.participant == null) return;
        peer.participant.screenSharing = screenValue;
        if (video != null) peer.participant.videoEnabled = video;
        notifyParticipant(peer.participant);
    }

    private void updateParticipantMute(String peerId, boolean value) {
        Peer peer = peers.get(peerId);
        if (peer == null || peer.participant == null) return;
        peer.participant.muted = value;
        notifyParticipant(peer.participant);
    }

    private void updateParticipantReaction(String peerId, String emoji) {
        Peer peer = peers.get(peerId);
        if (peer == null || peer.participant == null) return;
        peer.participant.reaction = emoji;
        peer.participant.reactionAtMs = System.currentTimeMillis();
        notifyParticipant(peer.participant);
    }

    private void updateParticipantHand(String peerId, boolean raised) {
        Peer peer = peers.get(peerId);
        if (peer == null || peer.participant == null) return;
        peer.participant.handRaised = raised;
        notifyParticipant(peer.participant);
    }

    private String peerName(String peerId) {
        Peer peer = peers.get(peerId);
        return peer != null && peer.participant != null ? peer.participant.name : peerId;
    }

    // ---- observer ------------------------------------------------------------

    private final class PeerObserver implements PeerConnection.Observer {
        private final Peer peer;

        PeerObserver(Peer peer) {
            this.peer = peer;
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState newState) { }

        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            if (peer.participant != null) peer.participant.connectionState = newState.name().toLowerCase();
            main.post(() -> {
                if (!isInCall() || peer.pc == null) return;
                switch (newState) {
                    case CONNECTED:
                    case COMPLETED:
                        onPeerConnected(peer);
                        break;
                    case DISCONNECTED:
                        setState(CallState.REJOINING);
                        scheduleReconnect(peer);
                        break;
                    case FAILED:
                        setState(CallState.REJOINING);
                        if (isInCall() && peer.pc != null) {
                            try { peer.pc.restartIce(); } catch (Throwable ignored) { }
                        }
                        main.postDelayed(() -> {
                            if (isInCall() && (peer.pc == null || peer.pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED)) {
                                endCall(CallEndReason.CONNECTION_LOST, true);
                            }
                        }, 4_000L);
                        break;
                    case CLOSED:
                        if (isInCall()) {
                            endCall(CallEndReason.CONNECTION_LOST, false);
                        }
                        break;
                    default:
                        break;
                }
            });
        }

        @Override public void onIceConnectionReceivingChange(boolean receiving) { }

        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState newState) { }

        @Override public void onIceCandidate(IceCandidate candidate) {
            if (candidate == null) return;
            CallSignal signal = CallSignal.of(CallSignal.CANDIDATE, callId, currentUserId).to(peer.peerId);
            signal.candidateMid = candidate.sdpMid;
            signal.candidateLine = candidate.sdpMLineIndex;
            signal.candidateSdp = candidate.sdp;
            signaling.send(signal);
        }

        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }

        @Override public void onAddStream(org.webrtc.MediaStream stream) {
            main.post(() -> scanReceivers(peer));
        }

        @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }

        @Override public void onDataChannel(DataChannel dataChannel) {
            if (dataChannel == null) return;
            peer.ctrl = dataChannel;
            registerControlChannel(peer);
        }

        @Override public void onRenegotiationNeeded() {
            // The first negotiation is driven explicitly by maybeInitiate(); only
            // the post-handshake renegotiations (video upgrade, screen share,
            // ICE restart) are driven from here.
            main.post(() -> {
                if (peer.pc != null && peer.hasRemoteDescription) negotiate(peer);
            });
        }

        @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] mediaStreams) {
            if (receiver == null || receiver.track() == null) return;
            if (config.e2ee && e2ee.isSupported()) {
                e2ee.attachReceiver(CallMediaFactory.factory(), receiver,
                        peer.peerId + (receiver.track().kind().equals("video") ? ":video" : ":audio"));
            }
            scanReceivers(peer);
        }

        @Override public void onTrack(RtpTransceiver transceiver) {
            if (transceiver == null) return;
            RtpReceiver receiver = transceiver.getReceiver();
            if (receiver == null || receiver.track() == null) return;
            if (config.e2ee && e2ee.isSupported()) {
                e2ee.attachReceiver(CallMediaFactory.factory(), receiver,
                        peer.peerId + (receiver.track().kind().equals("video") ? ":video" : ":audio"));
            }
            main.post(() -> scanReceivers(peer));
        }

        @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
            main.post(() -> {
                if (!isInCall() || peer.pc == null) return;
                switch (newState) {
                    case CONNECTED:
                        onPeerConnected(peer);
                        break;
                    case DISCONNECTED:
                        setState(CallState.REJOINING);
                        scheduleReconnect(peer);
                        break;
                    case FAILED:
                        setState(CallState.REJOINING);
                        if (isInCall() && peer.pc != null) {
                            try { peer.pc.restartIce(); } catch (Throwable ignored) { }
                        }
                        main.postDelayed(() -> {
                            if (isInCall()) {
                                endCall(CallEndReason.CONNECTION_LOST, true);
                            }
                        }, 4_000L);
                        break;
                    case CLOSED:
                        if (isInCall()) {
                            endCall(CallEndReason.CONNECTION_LOST, false);
                        }
                        break;
                    default:
                        break;
                }
            });
        }
    }

    private void onPeerConnected(Peer peer) {
        cancelReconnect();
        cancelHandshakeTimeout();
        peer.lastPacketReceivedMs = System.currentTimeMillis();
        if (peer.participant != null) peer.participant.connectionState = "connected";
        if (connectedAtMs == 0) connectedAtMs = System.currentTimeMillis();
        if (peers.size() > 1) {
            setState(CallState.ACTIVE);
        } else {
            setState(CallState.ACTIVE);
        }
        scanReceivers(peer);
        notifyE2ee();
        startStatsPolling();
    }

    // ---- timers --------------------------------------------------------------

    private void scheduleRingingTimeout() {
        cancelRingingTimeout();
        ringingTimeout = () -> {
            if (state == CallState.CALLING || state == CallState.PRE_ACCEPT_RECEIVED) {
                hangup(CallEndReason.NO_ANSWER);
            }
        };
        main.postDelayed(ringingTimeout, config.ringingTimeoutMs);
    }

    private void cancelRingingTimeout() {
        if (ringingTimeout != null) {
            main.removeCallbacks(ringingTimeout);
            ringingTimeout = null;
        }
    }

    private void scheduleOfferExpiry() {
        cancelOfferExpiry();
        offerExpiry = () -> {
            if (state == CallState.RECEIVED_CALL) decline(CallEndReason.TIMEOUT);
        };
        main.postDelayed(offerExpiry, config.offerExpiryMs);
    }

    private void cancelOfferExpiry() {
        if (offerExpiry != null) {
            main.removeCallbacks(offerExpiry);
            offerExpiry = null;
        }
    }

    private void startRingingHeartbeat() {
        cancelRingingHeartbeat();
        ringingHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (state == CallState.CALLING || state == CallState.PRE_ACCEPT_RECEIVED) {
                    if (primaryPeerId != null && callId != null) {
                        CallSignal ping = CallSignal.of(CallSignal.RINGING_PING, callId, currentUserId).to(primaryPeerId);
                        signaling.send(ping);
                    }
                    main.postDelayed(this, 2500L);
                }
            }
        };
        main.postDelayed(ringingHeartbeatRunnable, 2500L);
    }

    private void cancelRingingHeartbeat() {
        if (ringingHeartbeatRunnable != null) {
            main.removeCallbacks(ringingHeartbeatRunnable);
            ringingHeartbeatRunnable = null;
        }
    }

    private void startIncomingRingingWatchdog() {
        cancelIncomingRingingWatchdog();
        lastRingingPingMs = System.currentTimeMillis();
        incomingRingingWatchdog = new Runnable() {
            @Override
            public void run() {
                if (state == CallState.RECEIVED_CALL) {
                    long elapsed = System.currentTimeMillis() - lastRingingPingMs;
                    if (elapsed > 25_000L) {
                        Log.w(TAG, "Caller ringing ping stopped for " + elapsed + "ms -> auto declining with CONNECTION_LOST");
                        decline(CallEndReason.CONNECTION_LOST);
                        return;
                    }
                    main.postDelayed(this, 2000L);
                }
            }
        };
        main.postDelayed(incomingRingingWatchdog, 2000L);
    }

    private void cancelIncomingRingingWatchdog() {
        if (incomingRingingWatchdog != null) {
            main.removeCallbacks(incomingRingingWatchdog);
            incomingRingingWatchdog = null;
        }
    }

    private void scheduleReconnect(Peer peer) {
        if (reconnectTimeout != null) return;
        reconnectTimeout = () -> {
            reconnectTimeout = null;
            if (!isInCall()) return;
            boolean anyConnected = false;
            for (Peer p : peers.values()) {
                if (p.pc != null
                        && (p.pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED
                        || p.pc.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED)) {
                    anyConnected = true;
                    break;
                }
            }
            if (!anyConnected && isInCall()) {
                for (Peer p : peers.values()) {
                    if (p.pc != null && isInCall()) {
                        try { p.pc.restartIce(); } catch (Throwable ignored) { }
                    }
                }
                main.postDelayed(() -> {
                    if (isInCall()) {
                        boolean stillConnected = false;
                        for (Peer p : peers.values()) {
                            if (p.pc != null && (p.pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED
                                    || p.pc.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED)) {
                                stillConnected = true;
                                break;
                            }
                        }
                        if (!stillConnected) {
                            Log.w(TAG, "ICE reconnect failed -> ending call with CONNECTION_LOST");
                            endCall(CallEndReason.CONNECTION_LOST, true);
                        }
                    }
                }, 3_000L);
            }
        };
        main.postDelayed(reconnectTimeout, config.reconnectGraceMs);
    }

    private void cancelReconnect() {
        if (reconnectTimeout != null) {
            main.removeCallbacks(reconnectTimeout);
            reconnectTimeout = null;
        }
    }

    private void scheduleHandshakeTimeout() {
        cancelHandshakeTimeout();
        handshakeTimeout = () -> {
            handshakeTimeout = null;
            if (isInCall() && (state == CallState.ACCEPT_SENT || state == CallState.ACCEPT_RECEIVED)) {
                Log.w(TAG, "Call handshake timeout (peer disconnected during connection setup) -> ending call with CONNECTION_LOST");
                endCall(CallEndReason.CONNECTION_LOST, true);
            }
        };
        main.postDelayed(handshakeTimeout, 25_000L);
    }

    private void cancelHandshakeTimeout() {
        if (handshakeTimeout != null) {
            main.removeCallbacks(handshakeTimeout);
            handshakeTimeout = null;
        }
    }

    private void startStatsPolling() {
        if (statsPoll != null) return;
        statsPoll = new Runnable() {
            @Override public void run() {
                if (!isInCall()) return;
                pollStats();
                main.postDelayed(this, 1500L);
            }
        };
        main.postDelayed(statsPoll, 1500L);
    }

    private void stopStatsPolling() {
        if (statsPoll != null) {
            main.removeCallbacks(statsPoll);
            statsPoll = null;
        }
    }

    private void pollStats() {
        long now = System.currentTimeMillis();
        for (final Peer peer : peers.values()) {
            if (peer.pc == null) continue;

            // Direct DataChannel heartbeat & dead-man detection (Telegram parity)
            if (peer.ctrl != null && peer.ctrl.state() == DataChannel.State.OPEN) {
                CallSignal ping = CallSignal.of(CallSignal.PING, callId, currentUserId).to(peer.peerId);
                try {
                    peer.ctrl.send(new DataChannel.Buffer(
                            ByteBuffer.wrap(ping.encode().getBytes(StandardCharsets.UTF_8)), false));
                } catch (Throwable ignored) { }
            }

            try {
                peer.pc.getStats(new RTCStatsCollectorCallback() {
                    @Override public void onStatsDelivered(RTCStatsReport report) {
                        CallStats parsed = CallStats.parse(report);
                        main.post(() -> applyStats(peer, parsed));
                    }
                });
            } catch (Throwable ignored) { }

            PeerConnection.IceConnectionState iceState = peer.pc.iceConnectionState();
            boolean iceHealthy = (iceState == PeerConnection.IceConnectionState.CONNECTED
                    || iceState == PeerConnection.IceConnectionState.COMPLETED);
            if (iceHealthy && connectedAtMs > 0 && (now - connectedAtMs) < 15_000L) {
                peer.lastPacketReceivedMs = now;
            }

            long quietMs = now - peer.lastPacketReceivedMs;
            if (!iceHealthy && quietMs > 15_000L && state == CallState.ACTIVE) {
                Log.w(TAG, "Peer " + peer.peerId + " quiet for " + quietMs + "ms -> REJOINING");
                setState(CallState.REJOINING);
            } else if (!iceHealthy && quietMs > 35_000L && isInCall()) {
                Log.w(TAG, "Peer " + peer.peerId + " silent for " + quietMs + "ms -> ending call with CONNECTION_LOST");
                endCall(CallEndReason.CONNECTION_LOST, true);
                return;
            }
        }
    }

    private void applyStats(Peer peer, CallStats parsed) {
        if (!isInCall() || peer == null) return;
        if (parsed.bytesReceived > peer.lastCumulativeBytesReceived
                || parsed.packetsReceived > 0
                || parsed.bytesSent > 0
                || "succeeded".equalsIgnoreCase(parsed.selectedPairState)) {
            peer.lastCumulativeBytesReceived = Math.max(peer.lastCumulativeBytesReceived, parsed.bytesReceived);
            peer.lastPacketReceivedMs = System.currentTimeMillis();
            if (state == CallState.REJOINING && peer.pc != null
                    && (peer.pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED
                    || peer.pc.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED)) {
                setState(CallState.ACTIVE);
            }
        }
        stats.bytesSent = parsed.bytesSent;
        stats.bytesReceived = parsed.bytesReceived;
        stats.relay = stats.relay || parsed.relay;
        stats.roundTripTimeMs = parsed.roundTripTimeMs;
        if (peer.participant != null) {
            peer.participant.bytesSent = parsed.bytesSent;
            peer.participant.bytesReceived = parsed.bytesReceived;
            peer.participant.audioLevel = (float) parsed.audioLevelIn;
            peer.participant.speaking = parsed.audioLevelIn > 0.05;
            if (peer.participant.screenTrack != null) peer.participant.screenSharing = true;
            notifyParticipant(peer.participant);
            detectActiveSpeaker(peer.peerId, (float) parsed.audioLevelIn);
            adaptAudioBitrateToNetwork(peer, parsed);
        }
    }

    private void adaptAudioBitrateToNetwork(Peer peer, CallStats stats) {
        if (peer == null || peer.audioSender == null || stats == null) return;
        double lossRate = stats.getPacketLossFraction();
        double rtt = stats.roundTripTimeMs;

        int targetAudioBps;
        if (lossRate > 0.12 || rtt > 450.0 || stats.jitterMs > 120.0) {
            // Severe packet loss or 2G/3G high congestion: scale down to 6 kbps ultra-low-bitrate Opus speech
            targetAudioBps = 6_000;
            if (audioDevice != null) audioDevice.setLossyNetworkMode(true);
        } else if (lossRate > 0.05 || rtt > 250.0 || stats.jitterMs > 60.0) {
            // Moderate loss or high latency: scale down to 12 kbps
            targetAudioBps = 12_000;
            if (audioDevice != null) audioDevice.setLossyNetworkMode(true);
        } else if (lowDataMode) {
            targetAudioBps = 16_000;
            if (audioDevice != null) audioDevice.setLossyNetworkMode(false);
        } else {
            // Clean wireless conditions: full 32 kbps speech clarity
            targetAudioBps = config.maxAudioBitrate;
            if (audioDevice != null) audioDevice.setLossyNetworkMode(false);
        }

        if (peer.currentAudioBitrateBps != targetAudioBps) {
            peer.currentAudioBitrateBps = targetAudioBps;
            try {
                org.webrtc.RtpParameters params = peer.audioSender.getParameters();
                if (params != null && params.encodings != null) {
                    for (org.webrtc.RtpParameters.Encoding enc : params.encodings) {
                        enc.maxBitrateBps = targetAudioBps;
                        enc.minBitrateBps = 6_000;
                    }
                    peer.audioSender.setParameters(params);
                    Log.i(TAG, "Adapted Opus audio bitrate for " + peer.peerId + " -> " + (targetAudioBps / 1000)
                            + " kbps (loss=" + String.format(Locale.US, "%.1f", lossRate * 100) + "%, RTT=" + (int) rtt + "ms)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to adapt audio bitrate: " + t.getMessage());
            }
        }
    }

    // ---- Group SFrame E2EE Scaling & Active Speaker Detection (#125) ---------

    private void detectActiveSpeaker(String peerId, float audioLevel) {
        long now = System.currentTimeMillis();
        if (audioLevel > 0.04f) {
            if (!peerId.equals(activeSpeakerId) && (now - lastSpeakerChangeMs > 400L || audioLevel > activeSpeakerLevel * 1.25f)) {
                activeSpeakerId = peerId;
                activeSpeakerLevel = audioLevel;
                lastSpeakerChangeMs = now;
                main.post(() -> {
                    for (Listener l : listeners) {
                        l.onActiveSpeakerChanged(peerId, audioLevel);
                    }
                });
                if (topology == CallTopology.SFRAME_RELAY_OVERLAY) {
                    prioritizeSpeakerBitrate(peerId);
                }
            } else if (peerId.equals(activeSpeakerId)) {
                activeSpeakerLevel = audioLevel;
            }
        } else if (peerId.equals(activeSpeakerId) && now - lastSpeakerChangeMs > 2500L) {
            activeSpeakerId = null;
            activeSpeakerLevel = 0.0f;
            lastSpeakerChangeMs = now;
            main.post(() -> {
                for (Listener l : listeners) {
                    l.onActiveSpeakerChanged(null, 0.0f);
                }
            });
        }
    }

    private void prioritizeSpeakerBitrate(String speakerPeerId) {
        for (Peer p : peers.values()) {
            if (p.videoSender == null) continue;
            try {
                org.webrtc.RtpParameters params = p.videoSender.getParameters();
                if (params != null && params.encodings != null) {
                    boolean isSpeaker = p.peerId.equals(speakerPeerId);
                    int targetBps = isSpeaker ? config.maxVideoBitrate : 200_000;
                    for (org.webrtc.RtpParameters.Encoding enc : params.encodings) {
                        enc.maxBitrateBps = targetBps;
                    }
                    p.videoSender.setParameters(params);
                }
            } catch (Throwable ignored) { }
        }
    }

    public void evaluateTopologyScaling() {
        int totalParticipants = peers.size() + 1; // including self
        CallTopology newTopology = (totalParticipants >= 5)
                ? CallTopology.CASCADED_P2P_TREE
                : CallTopology.DIRECT_P2P_MESH;

        if (topology != newTopology) {
            CallTopology oldTopology = topology;
            topology = newTopology;
            Log.i(TAG, "Group Call Topology transitioned: " + oldTopology + " -> " + newTopology
                    + " (" + totalParticipants + " participants in call)");

            if (newTopology == CallTopology.CASCADED_P2P_TREE || newTopology == CallTopology.SFRAME_RELAY_OVERLAY) {
                applySFrameRelayOverlaySettings();
                try {
                    boolean isHighSpec = com.m3.veil.utils.VeilPerformanceController.getInstance().isHighEndDevice();
                    long selfBwKbps = isHighSpec ? 3500L : 800L;
                    long selfRttMs = stats != null && stats.roundTripTimeMs > 0 ? (long) stats.roundTripTimeMs : 35L;
                    List<com.m3.veil.webrtc.SovereignNatTraversalEngine.CascadedGroupNode> nodes = new ArrayList<>();
                    nodes.add(new com.m3.veil.webrtc.SovereignNatTraversalEngine.CascadedGroupNode(
                            currentUserId != null ? currentUserId : "local", selfBwKbps, selfRttMs));
                    for (Peer p : peers.values()) {
                        nodes.add(new com.m3.veil.webrtc.SovereignNatTraversalEngine.CascadedGroupNode(
                                p.peerId, 2000L, 45L));
                    }
                    com.m3.veil.webrtc.SovereignNatTraversalEngine.getInstance(context)
                            .electGroupCallStemRelayer(callId != null ? callId : "group_call", nodes);
                } catch (Throwable ignored) { }
            } else {
                restoreDirectMeshSettings();
            }

            main.post(() -> {
                for (Listener l : listeners) {
                    l.onTopologyChanged(newTopology);
                }
            });
        }
        updateSpatialAudioLayout();
    }

    private void applySFrameRelayOverlaySettings() {
        Log.i(TAG, "Applying SFrame Relay Overlay: enforcing frame cryptors and dynamic simulcast");
        for (Peer peer : peers.values()) {
            if (config.e2ee && e2ee.isSupported()) {
                if (peer.audioSender != null) {
                    e2ee.attachSender(CallMediaFactory.factory(), peer.audioSender, currentUserId + ":audio");
                }
                if (peer.videoSender != null) {
                    e2ee.attachSender(CallMediaFactory.factory(), peer.videoSender, currentUserId + ":video");
                }
            }
            applyBitrate(peer);
        }
    }

    private void restoreDirectMeshSettings() {
        Log.i(TAG, "Restoring Direct P2P Mesh parameters");
        for (Peer peer : peers.values()) {
            applyBitrate(peer);
        }
    }

    private void updateSpatialAudioLayout() {
        List<String> peerIds = new ArrayList<>(peers.keySet());
        Collections.sort(peerIds);
        int n = peerIds.size();
        if (n <= 1) {
            for (String id : peerIds) {
                Peer p = peers.get(id);
                if (p != null && p.participant != null) {
                    p.participant.spatialPan = 0.0f;
                    p.participant.leftGain = 1.0f;
                    p.participant.rightGain = 1.0f;
                    notifyParticipant(p.participant);
                }
            }
            return;
        }

        for (int i = 0; i < n; i++) {
            String id = peerIds.get(i);
            Peer p = peers.get(id);
            if (p == null || p.participant == null) continue;

            float pan = -0.70f + (1.40f * i / (n - 1));
            p.participant.spatialPan = pan;
            float normalized = (pan + 1.0f) * 0.5f; // [0.0, 1.0]
            p.participant.leftGain = (float) Math.cos(normalized * (Math.PI / 2.0));
            p.participant.rightGain = (float) Math.sin(normalized * (Math.PI / 2.0));

            if (p.remoteAudioTrack != null) {
                try {
                    p.remoteAudioTrack.setVolume(1.0);
                } catch (Throwable ignored) { }
            }
            notifyParticipant(p.participant);
        }
    }

    public void removeGroupParticipant(String peerId) {
        if (peerId == null) return;
        Peer p = peers.remove(peerId);
        if (p != null) {
            try {
                if (p.ctrl != null) {
                    p.ctrl.unregisterObserver();
                    p.ctrl.dispose();
                    p.ctrl = null;
                }
            } catch (Throwable ignored) { }
            try {
                if (p.pc != null) {
                    p.pc.close();
                    p.pc.dispose();
                    p.pc = null;
                }
            } catch (Throwable ignored) { }
            if (config.e2ee && e2ee.isSupported()) {
                e2ee.detachParticipant(peerId + ":audio");
                e2ee.detachParticipant(peerId + ":video");
                e2ee.detachParticipant(peerId + ":screen");
            }
            if (peerId.equals(activeSpeakerId)) {
                activeSpeakerId = null;
                activeSpeakerLevel = 0.0f;
            }
            main.post(() -> {
                for (Listener l : listeners) {
                    if (p.participant != null) {
                        l.onParticipantChanged(p.participant);
                    }
                }
                notifyInfo();
            });
        }
    }

    private void resetStats() {
        stats = CallStats.empty();
    }

    // ---- state + end ---------------------------------------------------------

    private void setState(CallState newState) {
        if (state == newState) return;
        state = newState;
        CallInfo info = buildInfo();
        if (newState.isEstablished()) {
            VeilCallService.start(context);
            CallNotificationHelper.cancelIncoming(context);
        }
        for (Listener l : listeners) l.onStateChanged(newState, info);
        notifyInfo();
    }

    private void notifyInfo() {
        if (!isInCall()) return;
        CallInfo info = buildInfo();
        if (state.isEstablished()) CallNotificationHelper.showOngoingCall(context, info);
        for (Listener l : listeners) l.onCallInfoChanged(info);
    }

    private void notifyParticipant(CallParticipant participant) {
        for (Listener l : listeners) l.onParticipantChanged(participant);
    }

    private void notifyRoute() {
        for (Listener l : listeners) l.onAudioRouteChanged(audioDevice.getRoute());
    }

    private void notifyE2ee() {
        boolean enabled = e2ee.isEnabled();
        for (Peer peer : peers.values()) {
            if (peer.participant != null) peer.participant.e2eeEnabled = enabled;
        }
        String safety = getSafetyNumber();
        if (enabled && safety != null && primaryPeerId != null) {
            fingerprintStatus = CallFingerprints.record(context, primaryPeerId, safety);
            if (fingerprintStatus == CallFingerprints.Status.CHANGED
                    || (fingerprintStatus != lastNotifiedFingerprintStatus
                        && !safety.equals(lastFingerprintValue))) {
                lastNotifiedFingerprintStatus = fingerprintStatus;
                lastFingerprintValue = safety;
                if (fingerprintStatus == CallFingerprints.Status.CHANGED) {
                    for (Listener l : listeners) l.onE2eeFingerprintChanged(primaryPeerId, safety);
                }
            }
        } else {
            fingerprintStatus = CallFingerprints.Status.UNKNOWN;
        }
        for (Listener l : listeners) l.onE2eeChanged(enabled, safety);
    }

    private void emitError(String message) {
        Log.w(TAG, message);
        for (Listener l : listeners) l.onError(message);
    }

    private CallInfo buildInfo() {
        CallInfo.Builder builder = CallInfo.builder()
                .callId(callId)
                .peerId(primaryPeerId)
                .peerName(primaryPeerName)
                .caller(caller)
                .group(groupId != null)
                .groupId(groupId)
                .groupName(groupName)
                .type(callType)
                .state(state)
                .muted(muted)
                .speakerOn(audioDevice.isSpeakerOn())
                .videoEnabled(videoEnabled)
                .screenSharing(screenSharing)
                .lowDataMode(lowDataMode)
                .e2eeEnabled(e2ee.isEnabled())
                .safetyNumber(getSafetyNumber())
                .relayInUse(stats != null && stats.relay)
                .startedAtMs(startedAtMs)
                .connectedAtMs(connectedAtMs)
                .endedAtMs(endedAtMs)
                .endReason(endReason)
                .waitingRoomEnabled(waitingRoomEnabled)
                .inWaitingRoom(inWaitingRoom)
                .videoUpgradePending(localVideoUpgradeRequested || remoteVideoUpgradeRequested);
        Map<String, CallParticipant> copy = new LinkedHashMap<>();
        if (selfParticipant != null) copy.put(selfParticipant.peerId, selfParticipant);
        for (Peer peer : peers.values()) {
            if (peer.participant != null && !peer.participant.self) copy.put(peer.peerId, peer.participant);
        }
        builder.participants(copy);
        return builder.build();
    }

    private void endCall(CallEndReason reason, boolean notifyRemote) {
        if (state == CallState.NONE || state == CallState.ENDING) return;
        endReason = reason == null ? CallEndReason.LOCAL_HANGUP : reason;
        rememberEnded(callId, "local");
        endedAtMs = System.currentTimeMillis();
        cancelRingingTimeout();
        cancelOfferExpiry();
        cancelReconnect();
        cancelHandshakeTimeout();
        stopStatsPolling();

        if (notifyRemote) {
            java.util.Set<String> targets = new java.util.HashSet<>(peers.keySet());
            if (primaryPeerId != null && !primaryPeerId.isEmpty()) {
                targets.add(primaryPeerId);
            }
            for (String targetPeerId : targets) {
                CallSignal end = CallSignal.of(CallSignal.END, callId, currentUserId).to(targetPeerId);
                end.payload = reasonPayload(endReason);
                signaling.send(end);
            }
        }

        setState(CallState.ENDING);
        CallInfo info = buildInfo();
        CallNotificationHelper.cancelAll(context);
        VeilCallService.stop(context);
        teardown();
        persistCallRecord(info, endReason);
        if (logSink != null) {
            logSink.onCallLogged(info, endReason, info.getDurationMs(), stats.bytesSent, stats.bytesReceived);
        }
        for (Listener l : listeners) l.onCallEnded(endReason, info);
        state = CallState.NONE;
    }

    /**
     * Writes the encrypted call-log row. Done by the engine (not the UI) so
     * missed and declined calls are recorded even when CallActivity never opens.
     */
    private void persistCallRecord(CallInfo info, CallEndReason reason) {
        try {
            if (currentUserId == null || primaryPeerId == null || callId == null) return;
            String status = reason != null ? reason.logStatus() : "completed";
            long durationSeconds = info != null ? info.getDurationMs() / 1000L : 0L;
            String selfName = getResolvedDisplayName();
            String safePeerName = (!isSelfName(primaryPeerName)) ? primaryPeerName : "Veil Contact";
            String callerId = caller ? currentUserId : primaryPeerId;
            String callerName = caller ? selfName : safePeerName;
            String receiverId = caller ? primaryPeerId : currentUserId;
            String receiverName = caller ? safePeerName : selfName;
            String type = callType != null ? callType.wire : "audio";
            String peerId = primaryPeerId;
            String peerName = safePeerName;
            CallRecord record = new CallRecord(callId, peerId, peerName, callerId, callerName, receiverId, receiverName,
                    type, status, (int) durationSeconds, Timestamp.now());
            final Context appContext = context;
            diskIo.execute(() -> {
                try {
                    EncryptedChatDatabase.getInstance(appContext).saveCallRecord(record);
                } catch (Throwable t) {
                    Log.w(TAG, "call log write failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "persistCallRecord failed: " + t.getMessage());
        }
    }

    private void teardown() {
        cancelNetworkLostTimeout();
        cancelRingingTimeout();
        cancelOfferExpiry();
        cancelReconnect();
        cancelRingingHeartbeat();
        cancelIncomingRingingWatchdog();
        stopStatsPolling();
        topology = CallTopology.DIRECT_P2P_MESH;
        activeSpeakerId = null;
        activeSpeakerLevel = 0.0f;

        for (Peer peer : peers.values()) {
            try {
                if (peer.ctrl != null) {
                    DataChannel dc = peer.ctrl;
                    peer.ctrl = null;
                    dc.unregisterObserver();
                    dc.dispose();
                }
            } catch (Throwable ignored) { }
            try {
                if (peer.pc != null) {
                    PeerConnection pc = peer.pc;
                    peer.pc = null;
                    pc.close();
                    pc.dispose();
                }
            } catch (Throwable ignored) { }
        }
        try {
            if (sharedAudioTrack != null) sharedAudioTrack.dispose();
        } catch (Throwable ignored) { }
        try {
            if (sharedAudioSource != null) sharedAudioSource.dispose();
        } catch (Throwable ignored) { }
        sharedAudioTrack = null;
        sharedAudioSource = null;
        selfParticipant = null;
        fingerprintStatus = CallFingerprints.Status.UNKNOWN;
        lastNotifiedFingerprintStatus = CallFingerprints.Status.UNKNOWN;
        lastFingerprintValue = null;
        peers.clear();
        deferred.clear();
        videoDevice.stopScreenCapture();
        videoDevice.stopCamera();
        audioDevice.stop();
        e2ee.dispose();
        screenSharing = false;
        videoEnabled = false;
        remoteVideoUpgradeRequested = false;
        localVideoUpgradeRequested = false;
        pendingInviteKey = null;
        pendingInvitePayload = null;
    }

    // ---- helpers -------------------------------------------------------------

    private String reasonPayload(CallEndReason reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("reason", reason.code);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private CallEndReason parseReason(String payload, CallEndReason fallback) {
        if (payload == null) return fallback;
        try {
            JSONObject o = new JSONObject(payload);
            int code = o.optInt("reason", -1);
            for (CallEndReason reason : CallEndReason.values()) {
                if (reason.code == code) return reason;
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private String mutePayload(boolean value) {
        try {
            JSONObject o = new JSONObject();
            o.put("muted", value);
            return o.toString();
        } catch (Exception e) {
            return Boolean.toString(value);
        }
    }

    // ---- peer holder ---------------------------------------------------------

    private static final class Peer {
        final String peerId;
        String name;
        PeerConnection pc;
        RtpTransceiver audioTx;
        RtpTransceiver videoTx;
        RtpTransceiver screenTx;
        RtpSender audioSender;
        RtpSender videoSender;
        RtpSender screenSender;
        AudioTrack localAudioTrack;
        AudioTrack remoteAudioTrack;
        VideoTrack localVideoTrack;
        int currentAudioBitrateBps = 32_000;
        DataChannel ctrl;
        CallParticipant participant;
        long lastPacketReceivedMs = System.currentTimeMillis();
        long lastCumulativeBytesReceived = 0;

        boolean makingOffer;
        boolean ignoreOffer;
        boolean polite;
        boolean hasRemoteDescription;
        boolean isSettingRemoteAnswerPending;
        final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();

        Peer(String peerId, String name) {
            this.peerId = peerId;
            this.name = name;
            this.participant = new CallParticipant(peerId, name, false);
        }
    }
}
