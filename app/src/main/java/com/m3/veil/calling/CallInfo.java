package com.m3.veil.calling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable-ish snapshot of the active call, analogous to WhatsApp's CallInfo
 * but surfaced as a friendly object for the UI. Obtain the current snapshot from
 * {@link CallEngine#getCallInfo()}.
 */
public class CallInfo {

    private final String callId;
    private final String peerId;
    private final String peerName;
    private final boolean caller;
    private final boolean group;
    private final String groupId;
    private final String groupName;
    private final CallType type;
    private final CallState state;
    private final boolean muted;
    private final boolean speakerOn;
    private final boolean videoEnabled;
    private final boolean screenSharing;
    private final boolean lowDataMode;
    private final boolean e2eeEnabled;
    private final String safetyNumber;
    private final boolean relayInUse;
    private final long startedAtMs;
    private final long connectedAtMs;
    private final long endedAtMs;
    private final CallEndReason endReason;
    private final String waitingRoomLink;
    private final boolean waitingRoomEnabled;
    private final boolean inWaitingRoom;
    private final boolean videoUpgradePending;
    private final Map<String, CallParticipant> participants;

    private CallInfo(Builder b) {
        this.callId = b.callId;
        this.peerId = b.peerId;
        this.peerName = b.peerName;
        this.caller = b.caller;
        this.group = b.group;
        this.groupId = b.groupId;
        this.groupName = b.groupName;
        this.type = b.type;
        this.state = b.state;
        this.muted = b.muted;
        this.speakerOn = b.speakerOn;
        this.videoEnabled = b.videoEnabled;
        this.screenSharing = b.screenSharing;
        this.lowDataMode = b.lowDataMode;
        this.e2eeEnabled = b.e2eeEnabled;
        this.safetyNumber = b.safetyNumber;
        this.relayInUse = b.relayInUse;
        this.startedAtMs = b.startedAtMs;
        this.connectedAtMs = b.connectedAtMs;
        this.endedAtMs = b.endedAtMs;
        this.endReason = b.endReason;
        this.waitingRoomLink = b.waitingRoomLink;
        this.waitingRoomEnabled = b.waitingRoomEnabled;
        this.inWaitingRoom = b.inWaitingRoom;
        this.videoUpgradePending = b.videoUpgradePending;
        this.participants = Collections.unmodifiableMap(new LinkedHashMap<>(b.participants));
    }

    public String getCallId() { return callId; }
    public String getPeerId() { return peerId; }
    public String getPeerName() { return peerName; }
    public boolean isCaller() { return caller; }
    public boolean isGroup() { return group; }
    public String getGroupId() { return groupId; }
    public String getGroupName() { return groupName; }
    public CallType getType() { return type; }
    public CallState getState() { return state; }
    public boolean isMuted() { return muted; }
    public boolean isSpeakerOn() { return speakerOn; }
    public boolean isVideoEnabled() { return videoEnabled; }
    public boolean isScreenSharing() { return screenSharing; }
    public boolean isLowDataMode() { return lowDataMode; }
    public boolean isE2eeEnabled() { return e2eeEnabled; }
    public String getSafetyNumber() { return safetyNumber; }
    public boolean isRelayInUse() { return relayInUse; }
    public long getStartedAtMs() { return startedAtMs; }
    public long getConnectedAtMs() { return connectedAtMs; }
    public long getEndedAtMs() { return endedAtMs; }
    public CallEndReason getEndReason() { return endReason; }
    public String getWaitingRoomLink() { return waitingRoomLink; }
    public boolean isWaitingRoomEnabled() { return waitingRoomEnabled; }
    public boolean isInWaitingRoom() { return inWaitingRoom; }
    public boolean isVideoUpgradePending() { return videoUpgradePending; }
    public boolean isVideo() { return type == CallType.VIDEO || videoEnabled || screenSharing; }
    public boolean isConnected() { return state.isEstablished(); }

    public Map<String, CallParticipant> getParticipants() { return participants; }

    public List<CallParticipant> getParticipantList() {
        return new ArrayList<>(participants.values());
    }

    public int getParticipantCount() {
        return participants.size();
    }

    public CallParticipant getSelf() {
        for (CallParticipant p : participants.values()) if (p.self) return p;
        return null;
    }

    public long getDurationMs() {
        long from = connectedAtMs > 0 ? connectedAtMs : startedAtMs;
        long to = endedAtMs > 0 ? endedAtMs : System.currentTimeMillis();
        return Math.max(0, to - from);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String callId = "";
        private String peerId = "";
        private String peerName = "";
        private boolean caller;
        private boolean group;
        private String groupId;
        private String groupName;
        private CallType type = CallType.AUDIO;
        private CallState state = CallState.NONE;
        private boolean muted;
        private boolean speakerOn;
        private boolean videoEnabled;
        private boolean screenSharing;
        private boolean lowDataMode;
        private boolean e2eeEnabled = true;
        private String safetyNumber;
        private boolean relayInUse;
        private long startedAtMs = System.currentTimeMillis();
        private long connectedAtMs;
        private long endedAtMs;
        private CallEndReason endReason;
        private String waitingRoomLink;
        private boolean waitingRoomEnabled;
        private boolean inWaitingRoom;
        private boolean videoUpgradePending;
        private final Map<String, CallParticipant> participants = new LinkedHashMap<>();

        public Builder callId(String v) { this.callId = v; return this; }
        public Builder peerId(String v) { this.peerId = v; return this; }
        public Builder peerName(String v) { this.peerName = v; return this; }
        public Builder caller(boolean v) { this.caller = v; return this; }
        public Builder group(boolean v) { this.group = v; return this; }
        public Builder groupId(String v) { this.groupId = v; return this; }
        public Builder groupName(String v) { this.groupName = v; return this; }
        public Builder type(CallType v) { this.type = v; return this; }
        public Builder state(CallState v) { this.state = v; return this; }
        public Builder muted(boolean v) { this.muted = v; return this; }
        public Builder speakerOn(boolean v) { this.speakerOn = v; return this; }
        public Builder videoEnabled(boolean v) { this.videoEnabled = v; return this; }
        public Builder screenSharing(boolean v) { this.screenSharing = v; return this; }
        public Builder lowDataMode(boolean v) { this.lowDataMode = v; return this; }
        public Builder e2eeEnabled(boolean v) { this.e2eeEnabled = v; return this; }
        public Builder safetyNumber(String v) { this.safetyNumber = v; return this; }
        public Builder relayInUse(boolean v) { this.relayInUse = v; return this; }
        public Builder startedAtMs(long v) { this.startedAtMs = v; return this; }
        public Builder connectedAtMs(long v) { this.connectedAtMs = v; return this; }
        public Builder endedAtMs(long v) { this.endedAtMs = v; return this; }
        public Builder endReason(CallEndReason v) { this.endReason = v; return this; }
        public Builder waitingRoomLink(String v) { this.waitingRoomLink = v; return this; }
        public Builder waitingRoomEnabled(boolean v) { this.waitingRoomEnabled = v; return this; }
        public Builder inWaitingRoom(boolean v) { this.inWaitingRoom = v; return this; }
        public Builder videoUpgradePending(boolean v) { this.videoUpgradePending = v; return this; }
        public Builder participants(Map<String, CallParticipant> v) { this.participants.clear(); if (v != null) this.participants.putAll(v); return this; }

        public CallInfo build() { return new CallInfo(this); }
    }
}
