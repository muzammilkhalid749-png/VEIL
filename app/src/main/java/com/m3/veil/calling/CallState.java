package com.m3.veil.calling;

/**
 * Full call lifecycle state machine (superset of WhatsApp's CallState).
 * Every state transition is emitted by {@link CallEngine} and drives UI, audio
 * routing, notification and logging. This replaces the decorative enum that the
 * legacy WebRtcManager imported but never used.
 */
public enum CallState {
    /** No call exists. */
    NONE,
    /** We are warming up capture/engine before an invite is emitted. */
    PRECALLING,
    /** Outgoing call: offer sent, waiting for the remote to pre-accept. */
    CALLING,
    /** Remote device acknowledged the offer but the human has not accepted yet. */
    PRE_ACCEPT_RECEIVED,
    /** Incoming call ringing on this device, not yet answered. */
    RECEIVED_CALL,
    /** We answered; local accept is on the wire. */
    ACCEPT_SENT,
    /** The remote accepted; media negotiation is completing. */
    ACCEPT_RECEIVED,
    /** Two-way media flowing. */
    ACTIVE,
    /** Same account is in the call on another device (multi-device awareness). */
    ACTIVE_ELSEWHERE,
    /** Reconnecting after an ICE failure / network change. */
    REJOINING,
    /** Joined through a call link. */
    LINK,
    /** In the call but nobody else has connected yet. */
    CONNECTED_LONELY,
    /** Call screening / answering-machine phase before connecting. */
    SCREENING,
    /** Teardown in progress. */
    ENDING,
    /** Broadcaster call bootstrap. */
    BCALL_STARTING;

    public boolean isEnded() {
        return this == NONE || this == ENDING;
    }

    public boolean isRinging() {
        return this == CALLING || this == PRE_ACCEPT_RECEIVED || this == RECEIVED_CALL;
    }

    public boolean isIncoming() {
        return this == RECEIVED_CALL || this == PRE_ACCEPT_RECEIVED;
    }

    public boolean isEstablished() {
        return this == ACTIVE || this == REJOINING || this == LINK
                || this == CONNECTED_LONELY || this == SCREENING;
    }

    /** True once local media may be negotiated. Accept-gating is enforced with this. */
    public boolean allowsMediaNegotiation() {
        return this == ACCEPT_SENT || this == ACCEPT_RECEIVED || isEstablished();
    }
}
