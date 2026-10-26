package com.m3.veil.model;

import com.google.firebase.Timestamp;
import java.util.Arrays;
import java.util.List;

public class CallRecord {
    private String id;
    private String callerId;
    private String callerName;
    private String receiverId;
    private String receiverName;
    private String peerId;
    private String peerName;
    private String callType; // "audio", "video"
    private String status;   // "incoming", "outgoing", "missed", "completed"
    private int durationSeconds;
    private Timestamp timestamp;
    private List<String> participants;

    public CallRecord() {
        // Required for Firestore / serialization
    }

    public CallRecord(String id, String callerId, String callerName, String receiverId, String receiverName,
                      String callType, String status, int durationSeconds, Timestamp timestamp) {
        this.id = id;
        this.callerId = callerId;
        this.callerName = callerName;
        this.receiverId = receiverId;
        this.receiverName = receiverName;
        this.callType = callType;
        this.status = status;
        this.durationSeconds = durationSeconds;
        this.timestamp = timestamp;
        this.participants = Arrays.asList(callerId != null ? callerId : "", receiverId != null ? receiverId : "");

        // Auto-resolve peer name and ID so "Me" is never the contact
        resolvePeerInfo();
    }

    public CallRecord(String id, String peerId, String peerName, String callerId, String callerName,
                      String receiverId, String receiverName, String callType, String status,
                      int durationSeconds, Timestamp timestamp) {
        this.id = id;
        this.peerId = peerId;
        this.peerName = peerName;
        this.callerId = callerId;
        this.callerName = callerName;
        this.receiverId = receiverId;
        this.receiverName = receiverName;
        this.callType = callType;
        this.status = status;
        this.durationSeconds = durationSeconds;
        this.timestamp = timestamp;
        this.participants = Arrays.asList(callerId != null ? callerId : "", receiverId != null ? receiverId : "");

        if (this.peerName == null || this.peerName.isEmpty() || isMe(this.peerName)) {
            resolvePeerInfo();
        }
    }

    private void resolvePeerInfo() {
        String currentUid = null;
        try {
            currentUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId();
        } catch (Throwable ignored) {}

        boolean isOut = "outgoing".equalsIgnoreCase(status);
        if (currentUid != null) {
            if (currentUid.equals(callerId)) {
                isOut = true;
            } else if (currentUid.equals(receiverId)) {
                isOut = false;
            }
        }
        if (!isOut && isMe(callerName)) {
            isOut = true;
        }

        if (isOut) {
            // Outgoing call: Remote party is strictly the RECEIVER (never self/caller)
            this.peerId = (receiverId != null && !receiverId.isEmpty() && !receiverId.equals(currentUid) && !"local_user".equals(receiverId))
                    ? receiverId : ((callerId != null && !callerId.equals(currentUid) && !"local_user".equals(callerId)) ? callerId : "");
            this.peerName = (!isMe(receiverName) && receiverName != null && !receiverName.trim().isEmpty())
                    ? receiverName.trim() : "Veil Contact";
        } else {
            // Incoming call: Remote party is strictly the CALLER (never self/receiver)
            this.peerId = (callerId != null && !callerId.isEmpty() && !callerId.equals(currentUid) && !"local_user".equals(callerId))
                    ? callerId : ((receiverId != null && !receiverId.equals(currentUid) && !"local_user".equals(receiverId)) ? receiverId : "");
            this.peerName = (!isMe(callerName) && callerName != null && !callerName.trim().isEmpty())
                    ? callerName.trim() : "Veil Contact";
        }

        if (isMe(this.peerName)) {
            this.peerName = "Veil Contact";
        }
    }

    private boolean isMe(String name) {
        if (name == null) return true;
        String clean = name.trim();
        return clean.isEmpty() || "Me".equalsIgnoreCase(clean) || "You".equalsIgnoreCase(clean)
                || "local_user".equalsIgnoreCase(clean) || "null".equalsIgnoreCase(clean);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCallerId() { return callerId; }
    public void setCallerId(String callerId) { this.callerId = callerId; }

    public String getCallerName() { return callerName; }
    public void setCallerName(String callerName) { this.callerName = callerName; }

    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }

    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }

    public String getCallType() { return callType; }
    public void setCallType(String callType) { this.callType = callType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }

    public String getPeerName() { return peerName; }
    public void setPeerName(String peerName) { this.peerName = peerName; }

    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }
}
