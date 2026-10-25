package com.m3.veil.calling;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Central tuning + infrastructure configuration for {@link CallEngine}.
 *
 * <p>TURN is first-class here (unlike the legacy call path which shipped STUN
 * only and therefore failed on symmetric NAT / CGNAT). Populate TURN once at
 * app start:
 *
 * <pre>
 *   CallConfig.setGlobalTurn(
 *       Arrays.asList("turn:turn.veil.network:3478?transport=udp",
 *                     "turns:turn.veil.network:5349?transport=tcp"),
 *       "veil", "secret");
 * </pre>
 *
 * With no TURN configured the engine still works on open networks and reports
 * {@link CallInfo#isRelayInUse()} so the UI can explain failures.
 */
public final class CallConfig {

    private static volatile List<String> turnUrls = Collections.emptyList();
    private static volatile String turnUsername;
    private static volatile String turnPassword;

    /** Public STUN pool used for server-reflexive candidates. */
    public static final List<String> DEFAULT_STUN = Collections.unmodifiableList(Arrays.asList(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"));

    public static void setGlobalTurn(List<String> urls, String username, String password) {
        turnUrls = urls == null ? Collections.<String>emptyList() : new ArrayList<>(urls);
        turnUsername = username;
        turnPassword = password;
    }

    public static boolean hasTurn() {
        return !turnUrls.isEmpty();
    }

    /** Builds the ICE server list: STUN always, TURN when configured with Anycast fallback. */
    public static List<PeerConnection.IceServer> iceServers() {
        if (hasTurn()) {
            List<PeerConnection.IceServer> servers = new ArrayList<>();
            for (String stun : DEFAULT_STUN) {
                servers.add(PeerConnection.IceServer.builder(stun).createIceServer());
            }
            for (String url : turnUrls) {
                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(url);
                if (turnUsername != null) b.setUsername(turnUsername);
                if (turnPassword != null) b.setPassword(turnPassword);
                servers.add(b.createIceServer());
            }
            return servers;
        }

        // Delegate to TurnCredentialManager for RFC 5389 ephemeral HMAC Zero-Log TURN & low-latency STUN
        try {
            return com.m3.veil.webrtc.TurnCredentialManager.getInstance(null).getIceServers(true);
        } catch (Throwable t) {
            List<PeerConnection.IceServer> fallback = new ArrayList<>();
            for (String stun : DEFAULT_STUN) {
                fallback.add(PeerConnection.IceServer.builder(stun).createIceServer());
            }
            return fallback;
        }
    }

    // ---- behaviour -----------------------------------------------------------

    /** RELAY forces TURN-only (privacy / IP hiding). NOHOST avoids host candidates. */
    public PeerConnection.IceTransportsType iceTransportsType =
            PeerConnection.IceTransportsType.ALL;

    /** Gather ICE continuously so network changes can be recovered without a new call. */
    public PeerConnection.ContinualGatheringPolicy continualGatheringPolicy =
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

    public int iceCandidatePoolSize = 4;
    public boolean enableDscp = true;
    public boolean enableCpuOveruseDetection = false; // Disabled to prevent aggressive throttling on mobile chipsets
    public boolean suspendBelowMinBitrate = true;

    /** Ring timeout before an outgoing call is abandoned (Telegram standard 35s). */
    public long ringingTimeoutMs = 35_000L;
    /** How long we keep a pending incoming offer before rejecting it. */
    public long offerExpiryMs = 35_000L;
    /** Reconnect grace period after ICE disconnect before ending the call (Telegram parity). */
    public long reconnectGraceMs = 5_000L;

    /** Video bitrate ceiling (bps). 650 kbps is optimal for mobile 480p 30fps without frame drops. */
    public int maxVideoBitrate = 650_000;
    public int maxVideoBitrateLowData = 250_000;
    /** Audio bitrate ceiling (bps). 48 kbps Opus voice provides studio fidelity. */
    public int maxAudioBitrate = 48_000;

    /** Dynamically calibrated to device CPU/GPU and thermal capabilities via VeilPerformanceController. */
    public int videoWidth = getDefaultVideoWidth();
    public int videoHeight = getDefaultVideoHeight();
    public int videoFps = getDefaultVideoFps();
    public int lowDataWidth = 480;
    public int lowDataHeight = 360;
    public int lowDataFps = 20;

    private static int getDefaultVideoWidth() {
        try {
            return com.m3.veil.utils.VeilPerformanceController.getInstance().getVideoWidth();
        } catch (Throwable t) {
            return 640;
        }
    }

    private static int getDefaultVideoHeight() {
        try {
            return com.m3.veil.utils.VeilPerformanceController.getInstance().getVideoHeight();
        } catch (Throwable t) {
            return 480;
        }
    }

    private static int getDefaultVideoFps() {
        try {
            return com.m3.veil.utils.VeilPerformanceController.getInstance().getVideoFps();
        } catch (Throwable t) {
            return 30;
        }
    }

    /** Group calls: send multiple spatial/temporal layers so receivers can pick. */
    public boolean simulcast = true;

    /** Preferred video codecs in priority order. VP8 first for universal, stutter-free software/hardware decoding across all mobile GPUs without driver crashes. */
    public List<String> preferredVideoCodecs =
            Collections.unmodifiableList(Arrays.asList("VP8", "H264", "VP9"));

    /** Frame-level E2EE (FrameCryptor / SFrame). Default false for 1:1 calls so DTLS-SRTP encrypts without dropping video keyframes. */
    public boolean e2ee = false;

    /** Hardware AEC/NS when the device advertises support (falls back automatically). */
    public boolean hardwareAec = true;
    public boolean hardwareNs = true;

    public CallConfig copy() {
        CallConfig c = new CallConfig();
        c.iceTransportsType = iceTransportsType;
        c.continualGatheringPolicy = continualGatheringPolicy;
        c.iceCandidatePoolSize = iceCandidatePoolSize;
        c.enableDscp = enableDscp;
        c.enableCpuOveruseDetection = enableCpuOveruseDetection;
        c.suspendBelowMinBitrate = suspendBelowMinBitrate;
        c.ringingTimeoutMs = ringingTimeoutMs;
        c.offerExpiryMs = offerExpiryMs;
        c.reconnectGraceMs = reconnectGraceMs;
        c.maxVideoBitrate = maxVideoBitrate;
        c.maxVideoBitrateLowData = maxVideoBitrateLowData;
        c.maxAudioBitrate = maxAudioBitrate;
        c.videoWidth = videoWidth;
        c.videoHeight = videoHeight;
        c.videoFps = videoFps;
        c.lowDataWidth = lowDataWidth;
        c.lowDataHeight = lowDataHeight;
        c.lowDataFps = lowDataFps;
        c.simulcast = simulcast;
        c.e2ee = e2ee;
        c.hardwareAec = hardwareAec;
        c.hardwareNs = hardwareNs;
        return c;
    }
}
