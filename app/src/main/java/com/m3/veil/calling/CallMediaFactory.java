package com.m3.veil.calling;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.HardwareVideoDecoderFactory;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpCapabilities;
import org.webrtc.RtpTransceiver;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared WebRTC plumbing for the call engine: a single PeerConnectionFactory,
 * hardware-aware audio device module and codec-preference plumbing.
 *
 * <p>Improvements over the legacy path:
 * <ul>
 *   <li>JavaAudioDeviceModule with hardware AEC/NS, low-latency mode and
 *       VOICE_COMMUNICATION attributes instead of bare default constraints.</li>
 *   <li>Continuous ICE gathering + configurable TURN ({@link CallConfig}).</li>
 *   <li>SDP semantics UNIFIED_PLAN so transceivers can be added/removed
 *       mid-call (video upgrade, screen share) without renegotiation glare.</li>
 *   <li>Codec ordering (H.264 first) applied through
 *       {@link RtpTransceiver#setCodecPreferences}.</li>
 * </ul>
 */
public final class CallMediaFactory {

    private static final String TAG = "CallMediaFactory";

    private static volatile boolean initialized;
    private static EglBase eglBase;
    private static PeerConnectionFactory factory;
    private static JavaAudioDeviceModule audioDeviceModule;

    private CallMediaFactory() { }

    public static synchronized void init(Context context) {
        if (initialized) return;
        Context app = context.getApplicationContext();
        try {
            PeerConnectionFactory.InitializationOptions options =
                    PeerConnectionFactory.InitializationOptions.builder(app)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(options);

            eglBase = EglBase.create();

            JavaAudioDeviceModule.Builder adm = JavaAudioDeviceModule.builder(app)
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setUseLowLatency(false)
                    .setUseStereoInput(false)
                    .setUseStereoOutput(false)
                    .setSamplesReadyCallback(audioSamples -> {
                        if (audioSamples != null && audioSamples.getData() != null) {
                            byte[] data = audioSamples.getData();
                            VoiceMaskingDsp.VoiceFilter filter = VoiceMaskingDsp.VoiceFilter.NATURAL;
                            try {
                                CallEngine ce = CallEngine.getInstance(app);
                                if (ce != null) filter = ce.getVoiceFilter();
                            } catch (Throwable ignored) { }
                            CallAudioDevice.getSharedDsp().processBytes(
                                    data, 0, data.length,
                                    audioSamples.getSampleRate(), audioSamples.getChannelCount(),
                                    filter);
                        }
                    });
            try {
                adm.setUseHardwareAcousticEchoCanceler(true);
                adm.setUseHardwareNoiseSuppressor(true);
            } catch (Throwable ignored) { }
            audioDeviceModule = adm.createAudioDeviceModule();

            // enableH264HighProfile is set to false for low-latency Baseline profile (Telegram/WhatsApp parity)
            DefaultVideoEncoderFactory encoder =
                    new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, false);
            VideoDecoderFactory decoder =
                    new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

            PeerConnectionFactory.Options pcOptions = new PeerConnectionFactory.Options();
            factory = PeerConnectionFactory.builder()
                    .setOptions(pcOptions)
                    .setAudioDeviceModule(audioDeviceModule)
                    .setVideoEncoderFactory(encoder)
                    .setVideoDecoderFactory(decoder)
                    .createPeerConnectionFactory();

            initialized = true;
            Log.i(TAG, "WebRTC call stack initialised");
        } catch (Throwable t) {
            Log.e(TAG, "failed to initialise WebRTC", t);
        }
    }

    public static boolean isReady() {
        return initialized && factory != null;
    }

    public static EglBase eglBase() {
        return eglBase;
    }

    public static PeerConnectionFactory factory() {
        return factory;
    }

    /**
     * Builds the RTC configuration (ICE servers + transport policy) shared by
     * PeerConnection creation and live reconfiguration.
     */
    public static PeerConnection.RTCConfiguration buildConfiguration(CallConfig config) {
        CallConfig effective = config != null ? config : new CallConfig();
        PeerConnection.RTCConfiguration rtc =
                new PeerConnection.RTCConfiguration(CallConfig.iceServers());
        rtc.iceTransportsType = effective.iceTransportsType;
        rtc.continualGatheringPolicy = effective.continualGatheringPolicy;
        rtc.iceCandidatePoolSize = effective.iceCandidatePoolSize;
        rtc.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtc.keyType = PeerConnection.KeyType.ECDSA;
        rtc.enableImplicitRollback = true;
        rtc.offerExtmapAllowMixed = true;
        rtc.iceConnectionReceivingTimeout = 3000;
        rtc.iceBackupCandidatePairPingInterval = 2000;
        return rtc;
    }

    public static PeerConnection createPeerConnection(CallConfig config, PeerConnection.Observer observer) {
        PeerConnection.RTCConfiguration rtc = new PeerConnection.RTCConfiguration(CallConfig.iceServers());
        if (com.m3.veil.VeilApplication.getInstance() != null &&
                com.m3.veil.utils.VeilTorProxyBridge.getInstance(com.m3.veil.VeilApplication.getInstance()).isStrictRelayEnforced()) {
            rtc.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
        } else {
            rtc.iceTransportsType = config.iceTransportsType;
        }
        rtc.continualGatheringPolicy = config.continualGatheringPolicy;
        rtc.iceCandidatePoolSize = config.iceCandidatePoolSize;
        rtc.enableDscp = config.enableDscp;
        rtc.enableCpuOveruseDetection = config.enableCpuOveruseDetection;
        rtc.suspendBelowMinBitrate = config.suspendBelowMinBitrate;
        rtc.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtc.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtc.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtc.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtc.keyType = PeerConnection.KeyType.ECDSA;
        // Perfect-negotiation collision handling relies on implicit rollback.
        rtc.enableImplicitRollback = true;
        rtc.offerExtmapAllowMixed = true;
        rtc.iceConnectionReceivingTimeout = 3000;
        rtc.iceBackupCandidatePairPingInterval = 2000;
        return factory.createPeerConnection(rtc, observer);
    }

    public static AudioSource createAudioSource() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        return factory.createAudioSource(constraints);
    }

    public static AudioTrack createAudioTrack(AudioSource source) {
        return factory.createAudioTrack("veil_call_audio", source);
    }

    public static VideoSource createVideoSource(boolean screencast) {
        return factory.createVideoSource(screencast);
    }

    public static VideoTrack createVideoTrack(VideoSource source, String id) {
        return factory.createVideoTrack(id, source);
    }

    /**
     * Orders the codecs the peer offered according to {@link CallConfig#preferredVideoCodecs}
     * so H.264 hardware encoding is attempted before VP8/VP9.
     */
    public static void applyCodecPreferences(RtpTransceiver transceiver, CallConfig config) {
        if (transceiver == null || config == null) return;
        try {
            RtpCapabilities capabilities =
                    factory.getRtpSenderCapabilities(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
            if (capabilities == null || capabilities.codecs == null) return;
            List<RtpCapabilities.CodecCapability> ordered = new ArrayList<>();
            for (String preferred : config.preferredVideoCodecs) {
                for (RtpCapabilities.CodecCapability codec : capabilities.codecs) {
                    String name = codec.name == null ? "" : codec.name.toUpperCase(Locale.US);
                    if (name.equals(preferred.toUpperCase(Locale.US)) && !ordered.contains(codec)) {
                        ordered.add(codec);
                    }
                }
            }
            for (RtpCapabilities.CodecCapability codec : capabilities.codecs) {
                if (!ordered.contains(codec)) ordered.add(codec);
            }
            if (!ordered.isEmpty()) transceiver.setCodecPreferences(ordered);
        } catch (Throwable t) {
            Log.w(TAG, "codec preference failed: " + t.getMessage());
        }
    }

    public static synchronized void dispose() {
        try {
            if (factory != null) factory.dispose();
        } catch (Throwable ignored) { }
        try {
            if (audioDeviceModule != null) audioDeviceModule.release();
        } catch (Throwable ignored) { }
        try {
            if (eglBase != null) eglBase.release();
        } catch (Throwable ignored) { }
        factory = null;
        audioDeviceModule = null;
        eglBase = null;
        initialized = false;
    }
}
