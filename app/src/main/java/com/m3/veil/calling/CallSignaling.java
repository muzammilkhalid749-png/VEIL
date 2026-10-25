package com.m3.veil.calling;

import android.util.Log;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Durable call signaling transport.
 *
 * <p>Each user owns the Firestore collection
 * {@code users/{uid}/call_signals}. Signals are addressed by writing into the
 * recipient's collection, so no server code is required and delivery survives
 * process death / offline periods. Once a call is ACTIVE the engine additionally
 * mirrors control signals over the call DataChannel so reactions, raise-hand,
 * DTMF and video-upgrade requests do not pay a server round-trip.
 *
 * <p>This replaces the legacy single-listener, call-invite-only path and adds
 * proper dedup, TTL cleanup and per-call correlation.
 */
public class CallSignaling {

    private static final String TAG = "CallSignaling";
    private static final String COLLECTION = "call_signals";
    private static final long SEEN_CAPACITY = 1024L;

    public interface Listener {
        void onSignal(CallSignal signal);
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> seenDocIds = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CAPACITY;
                }
            }));
    private final Executor io = Executors.newSingleThreadExecutor();

    private volatile String uid;
    private android.content.Context context;
    private ListenerRegistration registration;

    public void setContext(android.content.Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized void start(String userId) {
        if (userId == null) return;
        if (userId.equals(uid) && registration != null) return;
        stop();
        this.uid = userId;
        registration = db.collection("users").document(userId).collection(COLLECTION)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.w(TAG, "signal listener error: " + error.getMessage());
                        return;
                    }
                    if (snapshots == null) return;
                    java.util.List<CallSignal> batch = new java.util.ArrayList<>();
                    for (DocumentChange change : snapshots.getDocumentChanges()) {
                        if (change.getType() == DocumentChange.Type.REMOVED) continue;
                        String docId = change.getDocument().getId();
                        if (!seenDocIds.add(docId)) continue;
                        Map<String, Object> raw = change.getDocument().getData();
                        Object encoded = raw.get("signal");
                        String json = encoded instanceof String ? (String) encoded : null;
                        if (json == null) {
                            // expanded form: rebuild the envelope from raw fields
                            org.json.JSONObject rebuilt = new org.json.JSONObject();
                            try {
                                for (Map.Entry<String, Object> e : raw.entrySet()) {
                                    rebuilt.put(e.getKey(), e.getValue());
                                }
                            } catch (Exception ignored) { }
                            json = rebuilt.toString();
                        }
                        CallSignal signal = CallSignal.decode(json);
                        if (signal == null) continue;
                        batch.add(signal);
                        // best-effort cleanup so the mailbox never grows unbounded
                        final String id = docId;
                        final String owner = userId;
                        io.execute(() -> {
                            try {
                                db.collection("users").document(owner).collection(COLLECTION)
                                        .document(id).delete();
                            } catch (Exception ignored) { }
                        });
                    }
                    // Deliver in the order the sender produced them: a queued
                    // INVITE must never overtake its matching END.
                    batch.sort((x, y) -> Long.compare(x.timestamp, y.timestamp));
                    for (CallSignal signal : batch) dispatch(signal);
                });
        Log.i(TAG, "call signaling started for " + userId);
    }

    private void dispatch(CallSignal signal) {
        for (Listener l : listeners) {
            try {
                l.onSignal(signal);
            } catch (Exception e) {
                Log.e(TAG, "signal listener crashed", e);
            }
        }
    }

    public synchronized void stop() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        seenDocIds.clear();
    }

    public String getUid() {
        return uid;
    }

    /** Sends to {@link CallSignal#to}. */
    public void send(CallSignal signal) {
        if (signal == null || signal.to == null) return;
        sendTo(signal.to, signal);
    }

    public void sendTo(String targetUid, CallSignal signal) {
        if (targetUid == null) return;
        signal.to = targetUid;
        Map<String, Object> doc = new HashMap<>();
        doc.put("signal", signal.encode());
        doc.put("type", signal.type);
        doc.put("callId", signal.callId);
        doc.put("from", signal.from);
        doc.put("createdAt", FieldValue.serverTimestamp());
        doc.put("timestamp", System.currentTimeMillis());
        db.collection("users").document(targetUid).collection(COLLECTION)
                .add(doc)
                .addOnFailureListener(e -> Log.w(TAG, "failed to send " + signal.type + ": " + e.getMessage()));

        // Also dispatch direct high-priority FCM push to instantly wake callee if backgrounded/killed
        if (context != null) {
            String signalType = null;
            if (CallSignal.INVITE.equals(signal.type)) {
                signalType = "call_wake";
            } else if (CallSignal.END.equals(signal.type) || CallSignal.DECLINE.equals(signal.type)) {
                signalType = "call_end";
            } else if (CallSignal.VIDEO_UPGRADE_REQUEST.equals(signal.type)) {
                signalType = "call_upgrade";
            }
            if (signalType != null) {
                FcmCallPusher.pushCallSignal(
                        context,
                        targetUid,
                        signalType,
                        signal.callId,
                        signal.from,
                        signal.fromName,
                        signal.callType
                );
            }
        }
    }

    public void sendToAll(List<String> targetUids, CallSignal signal) {
        if (targetUids == null) return;
        for (String target : targetUids) {
            if (target == null || target.equals(signal.from)) continue;
            sendTo(target, signal);
        }
    }
}
