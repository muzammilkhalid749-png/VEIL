package com.m3.veil.calling;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wire format for every call control message. Carried over Firestore
 * ({@link CallSignaling}) and, once media is up, over the call DataChannel so
 * post-connect control has zero server round-trips.
 *
 * <p>This is the equivalent of WhatsApp's XMPP call stanzas
 * (VoipStanzaChildNode) but transport-agnostic and versionable.
 */
public class CallSignal {

    // life cycle
    public static final String INVITE = "invite";
    public static final String RINGING = "ringing";
    public static final String RINGING_PING = "ringing_ping";
    public static final String PRE_ACCEPT = "pre_accept";
    public static final String ACCEPT = "accept";
    public static final String DECLINE = "decline";
    public static final String END = "end";
    public static final String OFFER = "offer";
    public static final String ANSWER = "answer";
    public static final String CANDIDATE = "candidate";
    // in-call control
    public static final String VIDEO_UPGRADE_REQUEST = "video_upgrade_request";
    public static final String VIDEO_UPGRADE_ACCEPT = "video_upgrade_accept";
    public static final String VIDEO_UPGRADE_REJECT = "video_upgrade_reject";
    public static final String SCREEN_START = "screen_start";
    public static final String SCREEN_STOP = "screen_stop";
    public static final String MUTE = "mute";
    public static final String PARTICIPANT_UPDATE = "participant_update";
    public static final String REACTION = "reaction";
    public static final String RAISE_HAND = "raise_hand";
    public static final String DTMF = "dtmf";
    public static final String PING = "ping";
    // e2ee
    public static final String KEY_ANNOUNCE = "key_announce";
    public static final String KEY_RATCHET = "key_ratchet";
    // group / links / waiting room
    public static final String GROUP_INVITE = "group_invite";
    public static final String GROUP_LEAVE = "group_leave";
    public static final String LINK_JOIN = "link_join";
    public static final String WAITING_ROOM_JOIN = "waiting_room_join";
    public static final String WAITING_ROOM_ADMIT = "waiting_room_admit";
    public static final String WAITING_ROOM_DENY = "waiting_room_deny";

    public String type;
    public String callId;
    public String from;
    public String fromName;
    public String to;
    public String groupId;
    public String groupName;
    public String callType = "audio";
    /** "offer" / "answer" / "pranswer" / "rollback". */
    public String sdpType;
    public String sdp;
    public String candidateMid;
    public int candidateLine;
    public String candidateSdp;
    public String e2eeKeyB64;
    public int e2eeKeyIndex = -1;
    public String payload;
    public long timestamp = System.currentTimeMillis();

    public static CallSignal of(String type, String callId, String from) {
        CallSignal s = new CallSignal();
        s.type = type;
        s.callId = callId;
        s.from = from;
        return s;
    }

    public CallSignal to(String target) {
        this.to = target;
        return this;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.putOpt("callId", callId);
        o.putOpt("from", from);
        o.putOpt("fromName", fromName);
        o.putOpt("to", to);
        o.putOpt("groupId", groupId);
        o.putOpt("groupName", groupName);
        o.putOpt("callType", callType);
        o.putOpt("sdpType", sdpType);
        o.putOpt("sdp", sdp);
        o.putOpt("candidateMid", candidateMid);
        if (candidateLine >= 0) o.put("candidateLine", candidateLine);
        o.putOpt("candidateSdp", candidateSdp);
        o.putOpt("e2eeKey", e2eeKeyB64);
        o.put("e2eeKeyIndex", e2eeKeyIndex);
        o.putOpt("payload", payload);
        o.put("timestamp", timestamp);
        return o;
    }

    public String encode() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static CallSignal decode(String raw) {
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            CallSignal s = new CallSignal();
            s.type = o.optString("type", null);
            if (s.type == null) return null;
            s.callId = o.optString("callId", null);
            s.from = o.optString("from", null);
            s.fromName = o.optString("fromName", null);
            s.to = o.optString("to", null);
            s.groupId = o.optString("groupId", null);
            s.groupName = o.optString("groupName", null);
            s.callType = o.optString("callType", "audio");
            s.sdpType = o.optString("sdpType", null);
            s.sdp = o.optString("sdp", null);
            s.candidateMid = o.optString("candidateMid", null);
            s.candidateLine = o.optInt("candidateLine", -1);
            s.candidateSdp = o.optString("candidateSdp", null);
            s.e2eeKeyB64 = o.optString("e2eeKey", null);
            s.e2eeKeyIndex = o.optInt("e2eeKeyIndex", -1);
            s.payload = o.optString("payload", null);
            s.timestamp = o.optLong("timestamp", System.currentTimeMillis());
            return s;
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "CallSignal{" + type + " call=" + callId + " " + from + "->" + to + '}';
    }
}
