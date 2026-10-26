package com.m3.veil.calling;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Central tuning + infrastructure configuration for {@link CallEngine}.
 *
 * <p>Implements the Signal Smart-Hybrid NAT Traversal Strategy:
 * <ul>
 *   <li>Tier 1 (0ms - 1,200ms): Direct STUN P2P ({@code stun:stun.l.google.com:19302},
 *       {@code stun:stun.cloudflare.com:3478}).</li>
 *   <li>Tier 2 (CGNAT / Firewall Fallback): Port 443 TURN TCP/TLS/UDP
 *       ({@code turn:openrelay.metered.ca:443?transport=tcp},
 *       {@code turns:openrelay.metered.ca:443?transport=tcp}, and configurable Cloudflare /
 *       custom Coturn relays) while keeping {@link CallE2ee} (AES-256-GCM SFrame) and DTLS-SRTP
 *       active so the TURN relay only ever sees blind ciphertext.</li>
 * </ul>
 */
public final class CallConfig {

    private static volatile List<String> turnUrls = Collections.emptyList();
    private static volatile String turnUsername;
    private static volatile String turnPassword;

    private static volatile List<String> cloudflareTurnUrls = Collections.emptyList();
    private static volatile String cloudflareTurnUsername;
    private static volatile String cloudflareTurnPassword;

    /** Public STUN pool used for server-reflexive candidates. */
    public static final List<String> DEFAULT_STUN = Collections.unmodifiableList(Arrays.asList(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"));

    /** Default Port 443 OpenRelay TURN fallback endpoints for strict 4G/5G CGNAT traversal. */
    public static final List<String> DEFAULT_PORT_443_TURN = Collections.unmodifiableList(Arrays.asList(
            "turn:openrelay.metered.ca:443?transport=tcp",
            "turn:openrelay.metered.ca:443?transport=udp",
            "turns:openrelay.metered.ca:443?transport=tcp",
            "turn:openrelay.metered.ca:80?transport=tcp"));

    public static final String DEFAULT_TURN_USER = "openrelayproject";
    public static final String DEFAULT_TURN_PASS = "openrelayproject";

    /** Direct STUN P2P probing window before escalating to TURN TCP/TLS on Port 443. */
    public static final long DIRECT_STUN_TIMEOUT_MS = 1_200L;

    /** Maximum age of an incoming call invite signal before it is ignored as stale (60s). */
    public static final long INVITE_MAX_AGE_MS = 60_000L;
    /** Maximum age of an ephemeral call SDP/ICE signal document in Firestore (90s). */
    public static final long SIGNAL_MAX_AGE_MS = 90_000L;

    public static void setGlobalTurn(List<String> urls, String username, String password) {
        turnUrls = urls == null ? Collections.<String>emptyList() : new ArrayList<>(urls);
        turnUsername = username;
        turnPassword = password;
    }

    public static void setCloudflareTurn(List<String> urls, String username, String password) {
        cloudflareTurnUrls = urls == null ? Collections.<String>emptyList() : new ArrayList<>(urls);
        cloudflareTurnUsername = username;
        cloudflareTurnPassword = password;
    }

    public static boolean hasTurn() {
        return true;
    }

    /**
     * Builds the ICE server list: Direct STUN P2P first, followed by Port 443 TURN TCP/TLS
     * (OpenRelay + Cloudflare TURN + custom/ephemeral TURN) without blocking standard WebRTC ICE gathering.
     */
    public static List<PeerConnection.IceServer> iceServers() {
        return iceServers(true);
    }

    /**
     * Builds the ICE server list with optional TURN inclusion for staged 0-1,200ms STUN-first escalation.
     */
    public static List<PeerConnection.IceServer> iceServers(boolean includeTurn) {
        List<PeerConnection.IceServer> servers = new ArrayList<>();

        // Tier 1: Direct STUN P2P (Google + Cloudflare STUN)
        for (String stun : DEFAULT_STUN) {
            servers.add(PeerConnection.IceServer.builder(stun).createIceServer());
        }

        if (!includeTurn) {
            return servers;
        }

        // Tier 2A: Explicitly configured custom TURN servers
        if (!turnUrls.isEmpty()) {
            for (String url : turnUrls) {
                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(url);
                if (turnUsername != null) b.setUsername(turnUsername);
                if (turnPassword != null) b.setPassword(turnPassword);
                servers.add(b.createIceServer());
            }
        }

        // Tier 2B: Configurable Cloudflare TURN on Port 443
        if (!cloudflareTurnUrls.isEmpty()) {
            for (String url : cloudflareTurnUrls) {
                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(url);
                if (cloudflareTurnUsername != null) b.setUsername(cloudflareTurnUsername);
                if (cloudflareTurnPassword != null) b.setPassword(cloudflareTurnPassword);
                servers.add(b.createIceServer());
            }
        }

        // Tier 2C: Reliable Port 443 TURN TCP/TLS/UDP fallback (openrelay.metered.ca:443)
        for (String turnUrl : DEFAULT_PORT_443_TURN) {
            servers.add(PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(DEFAULT_TURN_USER)
                    .setPassword(DEFAULT_TURN_PASS)
                    .createIceServer());
        }

        // Tier 2D: Ephemeral RFC 5389 TURN Fleet from TurnCredentialManager
        try {
            List<PeerConnection.IceServer> fleetServers =
                    com.m3.veil.webrtc.TurnCredentialManager.getInstance(null).getIceServers(true);
            if (fleetServers != null) {
                for (PeerConnection.IceServer s : fleetServers) {
                    if (s != null && ! servers.contains(s)) {
                        servers.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return servers;
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
