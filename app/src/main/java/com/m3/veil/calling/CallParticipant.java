package com.m3.veil.calling;

import org.webrtc.VideoTrack;

/**
 * A participant in a 1:1 or group call. Tracks the same per-peer signals
 * WhatsApp exposes in its ParticipantInfo (mute, video, screen share, raised
 * hand, speaking, reactions, network health) plus the actual WebRTC track.
 */
public class CallParticipant {

    public final String peerId;
    public final String name;
    public final boolean self;
    public final long joinedAtMs;

    public volatile boolean muted;
    public volatile boolean videoEnabled;
    public volatile boolean screenSharing;
    public volatile boolean handRaised;
    public volatile boolean speaking;
    public volatile float audioLevel;
    public volatile float spatialPan = 0.0f; // -0.75 (left) to +0.75 (right), 0.0 = center
    public volatile float leftGain = 1.0f;
    public volatile float rightGain = 1.0f;
    public volatile boolean e2eeEnabled = true;
    public volatile String reaction;
    public volatile long reactionAtMs;
    public volatile String connectionState = "new";
    public volatile int videoWidth;
    public volatile int videoHeight;
    public volatile long videoFramesRendered;
    public volatile long bytesSent;
    public volatile long bytesReceived;
    public volatile VideoTrack videoTrack;
    public volatile VideoTrack screenTrack;

    public CallParticipant(String peerId, String name, boolean self) {
        this.peerId = peerId;
        this.name = name;
        this.self = self;
        this.joinedAtMs = System.currentTimeMillis();
    }

    public boolean hasVideo() {
        return (videoEnabled && videoTrack != null) || screenTrack != null;
    }

    /** The track that should be rendered for this participant, screen share wins. */
    public VideoTrack renderTrack() {
        return screenTrack != null ? screenTrack : videoTrack;
    }

    @Override
    public String toString() {
        return "CallParticipant{" + name + ", self=" + self + ", mute=" + muted
                + ", video=" + videoEnabled + ", screen=" + screenSharing
                + ", hand=" + handRaised + ", state=" + connectionState + '}';
    }
}
