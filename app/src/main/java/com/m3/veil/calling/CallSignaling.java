package com.m3.veil.calling;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.m3.veil.VeilApplication;
import com.m3.veil.utils.SovereignIdentityAndDirectoryEngine;

import org.json.JSONObject;
import org.webrtc.IceCandidate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL VOIP CALL SIGNALING TRANSPORT (SIGNAL SMART-HYBRID DUAL-PATH)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * 1. Primary Channel: Firebase Firestore ("users/{uid}/call_signals") for 100% reliable VoIP
 *    invite, ringing, accept, SDP offer/answer, and ICE candidate delivery.
 * 2. Zero-Footprint Ephemeral Deletion: Every call signal document is deleted from Firestore
 *    immediately upon receipt ("doc.getReference().delete()").
 * 3. Blind FCM Wake Push: Automatically invokes FcmCallPusher.pushCallSignal(...) on INVITE,
 *    END, DECLINE, and VIDEO_UPGRADE_REQUEST so sleeping phones wake in <200ms.
 * 4. Secondary Fast-Path: Parallel Nostr / WebSocket ephemeral relay dispatch.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class CallSignaling {

    private static final String TAG = "CallSignaling";
    private static final long MAX_SIGNAL_AGE_MS = 60_000L;
    private static final long MAX_INVITE_AGE_MS = 45_000L;

    public interface Listener {
        void onSignal(CallSignal signal);
    }

    private Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> processedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ListenerRegistration registration;
    private String myUid;

    public CallSignaling() {
        this.context = VeilApplication.getInstance();
    }

    public CallSignaling(@Nullable Listener initialListener) {
        this.context = VeilApplication.getInstance();
        if (initialListener != null) {
            this.listeners.add(initialListener);
        }
    }

    public void setContext(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void start(String myUid) {
        if (myUid == null || myUid.trim().isEmpty()) {
            return;
        }
        String cleanUid = myUid.trim();
        if (cleanUid.equals(this.myUid) && this.registration != null) {
            return;
        }
        stop();
        this.myUid = cleanUid;

        purgeStaleCallSignals(cleanUid);

        // 1. Primary Channel: Firestore real-time listener on "users/{myUid}/call_signals"
        this.registration = this.db.collection("users")
                .document(this.myUid)
                .collection("call_signals")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        if (dc.getType() != DocumentChange.Type.ADDED
                                && dc.getType() != DocumentChange.Type.MODIFIED) {
                            continue;
                        }
                        DocumentSnapshot doc = dc.getDocument();
                        // Immediately delete ephemeral call signal document from Firestore
                        doc.getReference().delete();

                        if (!this.processedIds.add(doc.getId())) {
                            continue;
                        }

                        try {
                            String raw = doc.getString("raw");
                            CallSignal sig = (raw != null && !raw.isEmpty())
                                    ? CallSignal.decode(raw)
                                    : decodeFromDocumentFields(doc);

                            if (sig == null || sig.type == null || sig.from == null) {
                                continue;
                            }
                            if (sig.from.equals(this.myUid)) {
                                continue;
                            }

                            long age = now - sig.timestamp;
                            long maxAge = CallSignal.INVITE.equals(sig.type)
                                    ? MAX_INVITE_AGE_MS : MAX_SIGNAL_AGE_MS;
                            if (age > maxAge) {
                                continue;
                            }

                            String dedupKey = sig.callId + "_" + sig.type + "_" + sig.timestamp;
                            this.processedIds.add(dedupKey);
                            dispatchSignalToListeners(sig);
                        } catch (Exception ex) {
                            Log.e(TAG, "Failed to parse Firestore call signal", ex);
                        }
                    }
                });

        // 2. Secondary Fast-Path: Decentralized WebSocket / Nostr ephemeral signal subscription
        try {
            Context ctx = resolveContext();
            if (ctx != null) {
                SovereignIdentityAndDirectoryEngine.getInstance(ctx)
                        .subscribeToIncomingSignals(cleanUid, (type, fromUid, payload) -> {
                            if (!"call_signal".equals(type) || payload == null) return;
                            String raw = payload.optString("raw", null);
                            if (raw == null || raw.isEmpty()) return;
                            CallSignal sig = CallSignal.decode(raw);
                            if (sig == null || sig.type == null || sig.from == null) return;
                            if (sig.from.equals(this.myUid)) return;

                            String dedupKey = sig.callId + "_" + sig.type + "_" + sig.timestamp;
                            if (!this.processedIds.add(dedupKey)) return;

                            long age = System.currentTimeMillis() - sig.timestamp;
                            long maxAge = CallSignal.INVITE.equals(sig.type)
                                    ? MAX_INVITE_AGE_MS : MAX_SIGNAL_AGE_MS;
                            if (age > maxAge) return;

                            dispatchSignalToListeners(sig);
                        });
            }
        } catch (Exception e) {
            Log.d(TAG, "WebSocket call signal fallback notice: " + e.getMessage());
        }
    }

    private void dispatchSignalToListeners(@NonNull CallSignal sig) {
        this.main.post(() -> {
            for (Listener l : this.listeners) {
                try {
                    l.onSignal(sig);
                } catch (Throwable t) {
                    Log.w(TAG, "Error in CallSignaling listener", t);
                }
            }
        });
    }

    private Context resolveContext() {
        return this.context != null ? this.context : VeilApplication.getInstance();
    }

    private CallSignal decodeFromDocumentFields(DocumentSnapshot doc) {
        try {
            String typeWire = doc.getString("type");
            String callId = doc.getString("callId");
            String from = doc.getString("from");
            if (typeWire == null || callId == null || from == null) return null;

            CallSignal s = CallSignal.of(typeWire, callId, from).to(this.myUid);
            s.fromName = doc.getString("fromName");
            if (s.fromName == null) s.fromName = doc.getString("callerName");
            String ct = doc.getString("callType");
            if (ct != null) s.callType = ct;
            s.sdpType = doc.getString("sdpType");
            s.sdp = doc.getString("sdp");
            s.candidateMid = doc.getString("candidateMid");
            Long line = doc.getLong("candidateLine");
            if (line != null) s.candidateLine = line.intValue();
            s.candidateSdp = doc.getString("candidateSdp");
            s.e2eeKeyB64 = doc.getString("e2eeKey");
            Long keyIdx = doc.getLong("e2eeKeyIndex");
            if (keyIdx != null) s.e2eeKeyIndex = keyIdx.intValue();
            s.groupId = doc.getString("groupId");
            s.groupName = doc.getString("groupName");
            s.payload = doc.getString("payload");
            Long ts = doc.getLong("ts");
            if (ts == null) ts = doc.getLong("timestamp");
            if (ts != null) s.timestamp = ts;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private void purgeStaleCallSignals(String uid) {
        try {
            this.db.collection("users")
                    .document(uid)
                    .collection("call_signals")
                    .limit(50)
                    .get()
                    .addOnSuccessListener(snapshots -> {
                        if (snapshots == null) return;
                        long cutoff = System.currentTimeMillis() - MAX_SIGNAL_AGE_MS;
                        for (DocumentSnapshot doc : snapshots.getDocuments()) {
                            Long ts = doc.getLong("ts");
                            if (ts == null) ts = doc.getLong("timestamp");
                            if (ts == null || ts < cutoff) {
                                doc.getReference().delete();
                            }
                        }
                    });
        } catch (Exception ignored) {}
    }

    public void stop() {
        if (this.registration != null) {
            this.registration.remove();
            this.registration = null;
        }
        this.myUid = null;
        this.processedIds.clear();
    }

    public void send(CallSignal sig) {
        if (sig == null || sig.to == null || sig.to.isEmpty()) {
            return;
        }
        if (sig.from == null || sig.from.isEmpty()) {
            sig.from = this.myUid;
        }

        final String rawJson = sig.encode();
        final String docId = UUID.randomUUID().toString();

        // 1. Primary Path: Write to Firestore "users/{to}/call_signals/{docId}"
        Map<String, Object> doc = new HashMap<>();
        doc.put("raw", rawJson);
        doc.put("type", sig.type != null ? sig.type : "");
        doc.put("callId", sig.callId != null ? sig.callId : "");
        doc.put("from", sig.from != null ? sig.from : "");
        doc.put("fromName", sig.fromName != null ? sig.fromName : "Veil Peer");
        doc.put("callType", sig.callType != null ? sig.callType : "audio");
        doc.put("ts", sig.timestamp);
        doc.put("timestamp", sig.timestamp);

        this.db.collection("users")
                .document(sig.to)
                .collection("call_signals")
                .document(docId)
                .set(doc)
                .addOnFailureListener(e -> Log.w(TAG, "Firestore call signal write notice: " + e.getMessage()));

        // Auto-expire unconsumed signal document after 45 seconds for zero cloud footprint
        this.main.postDelayed(() -> {
            try {
                this.db.collection("users")
                        .document(sig.to)
                        .collection("call_signals")
                        .document(docId)
                        .delete();
            } catch (Exception ignored) {}
        }, MAX_INVITE_AGE_MS);

        // 2. Blind FCM Wake Push for INVITE / END / DECLINE / VIDEO_UPGRADE_REQUEST
        Context ctx = resolveContext();
        if (ctx != null) {
            if (CallSignal.INVITE.equals(sig.type) || CallSignal.GROUP_INVITE.equals(sig.type)) {
                FcmCallPusher.pushCallSignal(
                        ctx,
                        sig.to,
                        "call_wake",
                        sig.callId != null ? sig.callId : "",
                        sig.from != null ? sig.from : "",
                        sig.fromName != null ? sig.fromName : "Incoming Call",
                        sig.callType != null ? sig.callType : "audio"
                );
            } else if (CallSignal.END.equals(sig.type) || CallSignal.DECLINE.equals(sig.type)) {
                FcmCallPusher.pushCallSignal(
                        ctx,
                        sig.to,
                        "call_end",
                        sig.callId != null ? sig.callId : "",
                        sig.from != null ? sig.from : "",
                        sig.fromName != null ? sig.fromName : "Call Ended",
                        sig.callType != null ? sig.callType : "audio"
                );
            } else if (CallSignal.VIDEO_UPGRADE_REQUEST.equals(sig.type)) {
                FcmCallPusher.pushCallSignal(
                        ctx,
                        sig.to,
                        "call_upgrade",
                        sig.callId != null ? sig.callId : "",
                        sig.from != null ? sig.from : "",
                        sig.fromName != null ? sig.fromName : "Video Upgrade",
                        "video"
                );
            }
        }

        // 3. Secondary Fast-Path: Parallel WebSocket / Nostr ephemeral signal
        try {
            if (ctx != null) {
                JSONObject payload = new JSONObject();
                payload.put("type", "call_signal");
                payload.put("from", sig.from != null ? sig.from : "");
                payload.put("raw", rawJson);
                payload.put("ts", sig.timestamp);
                SovereignIdentityAndDirectoryEngine.getInstance(ctx)
                        .sendEphemeralSignal(sig.to, payload, null);
            }
        } catch (Exception ignored) {}
    }

    public void sendCandidate(String callId, String toUid, IceCandidate c) {
        if (c == null) return;
        CallSignal s = CallSignal.of(CallSignal.CANDIDATE, callId, this.myUid).to(toUid);
        s.candidateMid = c.sdpMid != null ? c.sdpMid : "0";
        s.candidateLine = c.sdpMLineIndex;
        s.candidateSdp = c.sdp;
        send(s);
    }
}
