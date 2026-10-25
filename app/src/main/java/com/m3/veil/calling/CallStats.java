package com.m3.veil.calling;

import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;

import java.util.Map;

/**
 * Parses a WebRTC stats report into the handful of values the UI and call log
 * care about, including whether the selected ICE pair is relayed (so a failed
 * call can explain "no relay reachable" instead of failing silently).
 */
public final class CallStats {

    public long bytesSent;
    public long bytesReceived;
    public long packetsSent;
    public long packetsReceived;
    public long packetsLost;
    public double jitterMs;
    public double framesPerSecondOut;
    public double framesPerSecondIn;
    public double audioLevelIn;
    public double roundTripTimeMs;
    public boolean relay;
    public String localCandidateType = "unknown";
    public String remoteCandidateType = "unknown";
    public String selectedPairState = "unknown";

    public double getPacketLossFraction() {
        long total = packetsReceived + packetsLost;
        return total > 0 ? ((double) packetsLost / total) : 0.0;
    }

    private CallStats() { }

    public static CallStats empty() {
        return new CallStats();
    }

    public static CallStats parse(RTCStatsReport report) {
        CallStats out = new CallStats();
        if (report == null || report.getStatsMap() == null) return out;
        Map<String, RTCStats> stats = report.getStatsMap();

        String selectedPairId = null;
        for (RTCStats s : stats.values()) {
            if ("transport".equals(s.getType())) {
                Object id = s.getMembers().get("selectedCandidatePairId");
                if (id instanceof String) selectedPairId = (String) id;
            }
        }

        RTCStats selectedPair = null;
        for (RTCStats s : stats.values()) {
            String type = s.getType();
            if ("candidate-pair".equals(type)) {
                Object state = s.getMembers().get("state");
                Object nominated = s.getMembers().get("nominated");
                boolean succeeded = "succeeded".equals(String.valueOf(state));
                boolean isNominated = Boolean.TRUE.equals(nominated) || "true".equals(String.valueOf(nominated));
                if (selectedPairId != null && selectedPairId.equals(s.getId())) {
                    selectedPair = s;
                } else if (selectedPair == null && succeeded && isNominated) {
                    selectedPair = s;
                }
            } else if ("outbound-rtp".equals(type)) {
                out.bytesSent += asLong(s.getMembers().get("bytesSent"));
                out.packetsSent += asLong(s.getMembers().get("packetsSent"));
                out.framesPerSecondOut += asDouble(s.getMembers().get("framesPerSecond"));
            } else if ("inbound-rtp".equals(type)) {
                out.bytesReceived += asLong(s.getMembers().get("bytesReceived"));
                out.packetsReceived += asLong(s.getMembers().get("packetsReceived"));
                out.packetsLost += asLong(s.getMembers().get("packetsLost"));
                Object jitter = s.getMembers().get("jitter");
                if (jitter != null) out.jitterMs = asDouble(jitter) * 1000.0;
                out.framesPerSecondIn += asDouble(s.getMembers().get("framesPerSecond"));
                Object level = s.getMembers().get("audioLevel");
                if (level != null) out.audioLevelIn = Math.max(out.audioLevelIn, asDouble(level));
            }
        }

        if (selectedPair != null) {
            out.selectedPairState = String.valueOf(selectedPair.getMembers().get("state"));
            out.roundTripTimeMs = asDouble(selectedPair.getMembers().get("currentRoundTripTime")) * 1000.0;
            String localId = asString(selectedPair.getMembers().get("localCandidateId"));
            String remoteId = asString(selectedPair.getMembers().get("remoteCandidateId"));
            for (RTCStats s : stats.values()) {
                if ("local-candidate".equals(s.getType()) && s.getId().equals(localId)) {
                    out.localCandidateType = asString(s.getMembers().get("candidateType"));
                } else if ("remote-candidate".equals(s.getType()) && s.getId().equals(remoteId)) {
                    out.remoteCandidateType = asString(s.getMembers().get("candidateType"));
                }
            }
            out.relay = "relay".equalsIgnoreCase(out.localCandidateType)
                    || "relay".equalsIgnoreCase(out.remoteCandidateType);
        }
        return out;
    }

    private static long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        return 0L;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return 0d;
    }

    private static String asString(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }
}
