package com.m3.veil.calling;

/** Why a call ended. Mirrors and extends WhatsApp's call-result reasons. */
public enum CallEndReason {
    LOCAL_HANGUP(0, "ended"),
    REMOTE_HANGUP(1, "ended"),
    DECLINED(2, "declined"),
    BUSY(3, "busy"),
    UNAVAILABLE(4, "unavailable"),
    TIMEOUT(5, "timed out"),
    NO_ANSWER(6, "no answer"),
    CONNECTION_LOST(7, "connection lost"),
    REJECTED_ELSEWHERE(8, "answered elsewhere"),
    KICKED(9, "removed from call"),
    TOS_NOT_ACCEPTED(10, "terms not accepted"),
    ENCRYPTION_ERROR(11, "encryption error"),
    RATE_LIMITED(12, "rate limited"),
    ERROR(13, "error"),
    CALL_LINK_EXPIRED(14, "link expired"),
    WAITING_ROOM_DENIED(15, "denied by host");

    public final int code;
    public final String label;

    CallEndReason(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** Status persisted in Veil's call log. */
    public String logStatus() {
        switch (this) {
            case LOCAL_HANGUP:
            case REMOTE_HANGUP:
                return "completed";
            case DECLINED:
            case WAITING_ROOM_DENIED:
                return "declined";
            case BUSY:
                return "busy";
            case NO_ANSWER:
            case TIMEOUT:
                return "missed";
            case UNAVAILABLE:
            case CONNECTION_LOST:
            case CALL_LINK_EXPIRED:
                return "failed";
            default:
                return "failed";
        }
    }

    public boolean isFailure() {
        return this != LOCAL_HANGUP && this != REMOTE_HANGUP;
    }
}
