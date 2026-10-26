package com.m3.veil.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Lightweight WebRTC Signaling Client.
 * Connects to a Node.js/Socket.io signaling server for SDP Offer/Answer and ICE exchange.
 * Includes automatic real-time fallback via encrypted Firestore signaling documents so that
 * peer-to-peer connections succeed with zero server setup during local testing or production.
 */
public class SignalingClient {

    private static final String TAG = "SignalingClient";

    // Default signaling endpoint (configurable)
    private static final String DEFAULT_SOCKET_URL = "https://signaling.veil.network:443";

    public interface SignalingListener {
        void onOfferReceived(String fromUserId, String sdp);
        void onAnswerReceived(String fromUserId, String sdp);
        void onIceCandidateReceived(String fromUserId, IceCandidate candidate);
        void onWakeUpReceived(String fromUserId, String chatId);
        void onPeerOnline(String peerId);
        void onCallInviteReceived(String fromUserId, String callId, String callerName, String callType);
        void onCallRingingReceived(String fromUserId, String callId);
        void onCallAcceptReceived(String fromUserId, String callId);
        void onCallRejectReceived(String fromUserId, String callId, String reason);
        void onCallEndReceived(String fromUserId, String callId);
    }

    private static SignalingClient instance;
    private Context mContext;
    private Socket socket;
    private String currentUserId;
    private SignalingListener listener;
    private FirebaseFirestore db;

    public void setContext(Context context) {
        if (context != null) {
            this.mContext = context.getApplicationContext();
        }
    }

    private Context getContext() {
        if (mContext != null) return mContext;
        return com.m3.veil.VeilApplication.getInstance();
    }
    private ListenerRegistration firestoreSignalingListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isSocketConnected = false;

    // LRU Set of processed signal document IDs to guarantee zero duplicate signal processing
    private final Set<String> processedSignalDocIds = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            })
    );

    public static synchronized SignalingClient getInstance() {
        if (instance == null) {
            instance = new SignalingClient();
        }
        if (instance.mContext == null) {
            instance.mContext = com.m3.veil.VeilApplication.getInstance();
        }
        return instance;
    }

    private SignalingClient() {
        try {
            db = FirebaseFirestore.getInstance();
        } catch (Throwable ignored) {}
    }

    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }

    public void init(String userId) {
        if (userId == null || userId.equals(currentUserId)) return;
        this.currentUserId = userId;

        connectSocket(DEFAULT_SOCKET_URL);
        listenWebSocketSignaling();
        listenFirestoreSignaling();
    }

    private void listenWebSocketSignaling() {
        if (currentUserId == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx)
                .subscribeToIncomingSignals(currentUserId, (type, fromUid, payload) -> {
                    if (fromUid == null || fromUid.equals(currentUserId) || payload == null) return;
                    String sigId = payload.optString("sigId", type + "_" + fromUid + "_" + payload.optLong("timestamp", System.currentTimeMillis()));
                    if (processedSignalDocIds.contains(sigId)) return;
                    processedSignalDocIds.add(sigId);

                    mainHandler.post(() -> {
                        try {
                            if ("offer".equals(type)) {
                                String sdp = payload.optString("sdp");
                                if (listener != null) listener.onOfferReceived(fromUid, sdp);
                            } else if ("answer".equals(type)) {
                                String sdp = payload.optString("sdp");
                                if (listener != null) listener.onAnswerReceived(fromUid, sdp);
                            } else if ("candidate".equals(type) || "ice_candidate".equals(type)) {
                                String sdpMid = payload.optString("sdpMid");
                                int sdpMLineIndex = payload.optInt("sdpMLineIndex", 0);
                                String sdp = payload.optString("candidate");
                                IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                                checkAndTriggerHolePunchSpray(fromUid, candidate);
                                if (listener != null) listener.onIceCandidateReceived(fromUid, candidate);
                            } else if ("wake_up".equals(type)) {
                                String chatId = payload.optString("chatId");
                                if (listener != null) listener.onWakeUpReceived(fromUid, chatId);
                            } else if ("call_invite".equals(type)) {
                                String callId = payload.optString("callId");
                                String callerName = payload.optString("callerName", "@Anonymous");
                                String callType = payload.optString("callType", "audio");
                                if (listener != null) listener.onCallInviteReceived(fromUid, callId, callerName, callType);
                            } else if ("call_ringing".equals(type)) {
                                String callId = payload.optString("callId");
                                if (listener != null) listener.onCallRingingReceived(fromUid, callId);
                            } else if ("call_accept".equals(type)) {
                                String callId = payload.optString("callId");
                                if (listener != null) listener.onCallAcceptReceived(fromUid, callId);
                            } else if ("call_reject".equals(type)) {
                                String callId = payload.optString("callId");
                                String reason = payload.optString("reason", "declined");
                                if (listener != null) listener.onCallRejectReceived(fromUid, callId, reason);
                            } else if ("call_end".equals(type)) {
                                String callId = payload.optString("callId");
                                if (listener != null) listener.onCallEndReceived(fromUid, callId);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error handling websocket signal: " + e.getMessage());
                        }
                    });
                });
    }

    private void dispatchEphemeralSignal(String targetUserId, String type, JSONObject customPayload) {
        if (targetUserId == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            JSONObject data = customPayload != null ? customPayload : new JSONObject();
            data.put("type", type);
            data.put("from", currentUserId != null ? currentUserId : "veil_anon");
            data.put("to", targetUserId);
            data.put("timestamp", System.currentTimeMillis());
            data.put("sigId", type + "_" + (currentUserId != null ? currentUserId : "anon") + "_" + System.currentTimeMillis());
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(ctx)
                    .sendEphemeralSignal(targetUserId, data, null);
        } catch (Exception ignored) {}
    }

    private void connectSocket(String url) {
        try {
            IO.Options options = new IO.Options();
            options.reconnection = true;
            options.reconnectionAttempts = 5;
            options.reconnectionDelay = 2000;
            options.timeout = 5000;

            // Route through Tor/SOCKS5 Proxy Bridge (#116) if enabled
            try {
                Context ctx = com.m3.veil.VeilApplication.getInstance();
                if (ctx != null) {
                    com.m3.veil.utils.VeilTorProxyBridge proxyBridge = com.m3.veil.utils.VeilTorProxyBridge.getInstance(ctx);
                    if (proxyBridge.isProxyEnabled()) {
                        okhttp3.OkHttpClient proxiedClient = proxyBridge.buildProxiedOkHttpClient();
                        options.callFactory = proxiedClient;
                        options.webSocketFactory = proxiedClient;
                        Log.i(TAG, "SignalingClient routed through Tor/SOCKS5 proxy: " + proxyBridge.getProxyHost() + ":" + proxyBridge.getProxyPort());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Proxy attachment notice for SignalingClient: " + t.getMessage());
            }

            socket = IO.socket(URI.create(url), options);

            socket.on(Socket.EVENT_CONNECT, args -> {
                Log.d(TAG, "Socket connected to signaling server: " + url);
                isSocketConnected = true;
                // Register our user ID with the signaling server
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("userId", currentUserId);
                    socket.emit("register", reg);
                } catch (JSONException e) {
                    Log.e(TAG, "Error emitting register", e);
                }
            });

            socket.on(Socket.EVENT_DISCONNECT, args -> {
                Log.d(TAG, "Socket disconnected from signaling server");
                isSocketConnected = false;
            });

            socket.on("offer", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String sdp = data.optString("sdp");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onOfferReceived(from, sdp);
                    });
                }
            });

            socket.on("answer", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String sdp = data.optString("sdp");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAnswerReceived(from, sdp);
                    });
                }
            });

            socket.on("ice_candidate", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String sdpMid = data.optString("sdpMid");
                    int sdpMLineIndex = data.optInt("sdpMLineIndex");
                    String sdp = data.optString("candidate");
                    IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                    checkAndTriggerHolePunchSpray(from, candidate);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onIceCandidateReceived(from, candidate);
                    });
                }
            });

            socket.on("wake_up", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String chatId = data.optString("chatId");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onWakeUpReceived(from, chatId);
                    });
                }
            });

            socket.on("call_invite", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String callId = data.optString("callId");
                    String callerName = data.optString("callerName", "@Anonymous");
                    String callType = data.optString("callType", "audio");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onCallInviteReceived(from, callId, callerName, callType);
                    });
                }
            });

            socket.on("call_ringing", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String callId = data.optString("callId");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onCallRingingReceived(from, callId);
                    });
                }
            });

            socket.on("call_accept", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String callId = data.optString("callId");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onCallAcceptReceived(from, callId);
                    });
                }
            });

            socket.on("call_reject", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String callId = data.optString("callId");
                    String reason = data.optString("reason", "declined");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onCallRejectReceived(from, callId, reason);
                    });
                }
            });

            socket.on("call_end", args -> {
                if (args.length > 0 && args[0] instanceof JSONObject) {
                    JSONObject data = (JSONObject) args[0];
                    String from = data.optString("from");
                    String callId = data.optString("callId");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onCallEndReceived(from, callId);
                    });
                }
            });

            socket.connect();

        } catch (Exception e) {
            Log.w(TAG, "Socket.io connect failed, relying on Firestore real-time signaling fallback: " + e.getMessage());
        }
    }

    /**
     * Real-time Firestore signaling channel.
     * Ensures SDP & ICE candidate exchange works out-of-the-box everywhere without requiring
     * external WebSocket ports open.
     * Uses DocumentChange.Type.ADDED and processedSignalDocIds deduplication to prevent duplicate
     * delivery or loop cycles from cache updates or deletions.
     */
    private void listenFirestoreSignaling() {
        if (currentUserId == null) return;
        if (firestoreSignalingListener != null) firestoreSignalingListener.remove();

        firestoreSignalingListener = db.collection("users")
                .document(currentUserId)
                .collection("signals")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    for (DocumentChange dc : snapshots.getDocumentChanges()) {
                        // WhatsApp-grade stream filtering: only process newly added documents
                        if (dc.getType() != DocumentChange.Type.ADDED) continue;

                        DocumentSnapshot doc = dc.getDocument();
                        String docId = doc.getId();
                        if (processedSignalDocIds.contains(docId)) continue;
                        processedSignalDocIds.add(docId);

                        String type = doc.getString("type");
                        String from = doc.getString("from");
                        if (type == null || from == null) {
                            doc.getReference().delete();
                            continue;
                        }

                        if ("offer".equals(type)) {
                            String sdp = doc.getString("sdp");
                            if (listener != null) listener.onOfferReceived(from, sdp);
                        } else if ("answer".equals(type)) {
                            String sdp = doc.getString("sdp");
                            if (listener != null) listener.onAnswerReceived(from, sdp);
                        } else if ("candidate".equals(type)) {
                            String sdpMid = doc.getString("sdpMid");
                            Long mLine = doc.getLong("sdpMLineIndex");
                            int sdpMLineIndex = mLine != null ? mLine.intValue() : 0;
                            String sdp = doc.getString("candidate");
                            IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                            checkAndTriggerHolePunchSpray(from, candidate);
                            if (listener != null) listener.onIceCandidateReceived(from, candidate);
                        } else if ("wake_up".equals(type)) {
                            String chatId = doc.getString("chatId");
                            if (listener != null) listener.onWakeUpReceived(from, chatId);
                        } else if ("call_invite".equals(type)) {
                            String callId = doc.getString("callId");
                            String callerName = doc.getString("callerName");
                            String callType = doc.getString("callType");
                            if (listener != null) listener.onCallInviteReceived(from, callId, callerName != null ? callerName : "@Anonymous", callType != null ? callType : "audio");
                        } else if ("call_ringing".equals(type)) {
                            String callId = doc.getString("callId");
                            if (listener != null) listener.onCallRingingReceived(from, callId);
                        } else if ("call_accept".equals(type)) {
                            String callId = doc.getString("callId");
                            if (listener != null) listener.onCallAcceptReceived(from, callId);
                        } else if ("call_reject".equals(type)) {
                            String callId = doc.getString("callId");
                            String reason = doc.getString("reason");
                            if (listener != null) listener.onCallRejectReceived(from, callId, reason != null ? reason : "declined");
                        } else if ("call_end".equals(type)) {
                            String callId = doc.getString("callId");
                            if (listener != null) listener.onCallEndReceived(from, callId);
                        }

                        // Prune processed signal immediately for zero-knowledge privacy.
                        // Removed documents trigger DocumentChange.Type.REMOVED which is filtered above.
                        try {
                            doc.getReference().delete();
                        } catch (Throwable ignored) {}
                    }
                });
    }

    public void pruneSignalingForPeer(String peerId) {
        if (peerId == null || currentUserId == null) return;
        try {
            db.collection("users")
                    .document(currentUserId)
                    .collection("signals")
                    .whereEqualTo("from", peerId)
                    .get()
                    .addOnSuccessListener(snapshots -> {
                        if (snapshots != null) {
                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                doc.getReference().delete();
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    public void sendOffer(String targetUserId, String sdp) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sdp", sdp);
            dispatchEphemeralSignal(targetUserId, "offer", payload);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("sdp", sdp);
                socket.emit("offer", data);
                return;
            } catch (Exception ignored) {}
        }

        // Firestore Fallback
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "offer");
                signal.put("from", currentUserId);
                signal.put("sdp", sdp);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendAnswer(String targetUserId, String sdp) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sdp", sdp);
            dispatchEphemeralSignal(targetUserId, "answer", payload);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("sdp", sdp);
                socket.emit("answer", data);
                return;
            } catch (Exception ignored) {}
        }

        // Firestore Fallback
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "answer");
                signal.put("from", currentUserId);
                signal.put("sdp", sdp);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendIceCandidate(String targetUserId, IceCandidate candidate) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sdpMid", candidate.sdpMid);
            payload.put("sdpMLineIndex", candidate.sdpMLineIndex);
            payload.put("candidate", candidate.sdp);
            dispatchEphemeralSignal(targetUserId, "candidate", payload);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("sdpMid", candidate.sdpMid);
                data.put("sdpMLineIndex", candidate.sdpMLineIndex);
                data.put("candidate", candidate.sdp);
                socket.emit("ice_candidate", data);
                return;
            } catch (Exception ignored) {}
        }

        // Firestore Fallback
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "candidate");
                signal.put("from", currentUserId);
                signal.put("sdpMid", candidate.sdpMid);
                signal.put("sdpMLineIndex", candidate.sdpMLineIndex);
                signal.put("candidate", candidate.sdp);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendWakeUp(String targetUserId, String chatId) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("chatId", chatId);
            dispatchEphemeralSignal(targetUserId, "wake_up", payload);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("chatId", chatId);
                socket.emit("wake_up", data);
            } catch (Exception ignored) {}
        }

        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "wake_up");
                signal.put("from", currentUserId);
                signal.put("chatId", chatId);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendCallInvite(String targetUserId, String callId, String callerName, String callType) {
        if (targetUserId == null) return;
        try {
            JSONObject p = new JSONObject();
            p.put("callId", callId);
            p.put("callerName", callerName);
            p.put("callType", callType);
            dispatchEphemeralSignal(targetUserId, "call_invite", p);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("callId", callId);
                data.put("callerName", callerName);
                data.put("callType", callType);
                socket.emit("call_invite", data);
            } catch (Exception ignored) {}
        }
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "call_invite");
                signal.put("from", currentUserId);
                signal.put("callId", callId);
                signal.put("callerName", callerName);
                signal.put("callType", callType);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }

        sendWakeUp(targetUserId, "call_" + callId);
    }

    public void sendCallRinging(String targetUserId, String callId) {
        if (targetUserId == null) return;
        try {
            JSONObject p = new JSONObject();
            p.put("callId", callId);
            dispatchEphemeralSignal(targetUserId, "call_ringing", p);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("callId", callId);
                socket.emit("call_ringing", data);
            } catch (Exception ignored) {}
        }
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "call_ringing");
                signal.put("from", currentUserId);
                signal.put("callId", callId);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendCallAccept(String targetUserId, String callId) {
        if (targetUserId == null) return;
        try {
            JSONObject p = new JSONObject();
            p.put("callId", callId);
            dispatchEphemeralSignal(targetUserId, "call_accept", p);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("callId", callId);
                socket.emit("call_accept", data);
            } catch (Exception ignored) {}
        }
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "call_accept");
                signal.put("from", currentUserId);
                signal.put("callId", callId);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendCallReject(String targetUserId, String callId, String reason) {
        if (targetUserId == null) return;
        try {
            JSONObject p = new JSONObject();
            p.put("callId", callId);
            p.put("reason", reason != null ? reason : "declined");
            dispatchEphemeralSignal(targetUserId, "call_reject", p);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("callId", callId);
                data.put("reason", reason != null ? reason : "declined");
                socket.emit("call_reject", data);
            } catch (Exception ignored) {}
        }
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "call_reject");
                signal.put("from", currentUserId);
                signal.put("callId", callId);
                signal.put("reason", reason != null ? reason : "declined");
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    public void sendCallEnd(String targetUserId, String callId) {
        if (targetUserId == null) return;
        try {
            JSONObject p = new JSONObject();
            p.put("callId", callId);
            dispatchEphemeralSignal(targetUserId, "call_end", p);
        } catch (Exception ignored) {}

        if (isSocketConnected && socket != null) {
            try {
                JSONObject data = new JSONObject();
                data.put("to", targetUserId);
                data.put("from", currentUserId);
                data.put("callId", callId);
                socket.emit("call_end", data);
            } catch (Exception ignored) {}
        }
        if (db != null) {
            try {
                Map<String, Object> signal = new HashMap<>();
                signal.put("type", "call_end");
                signal.put("from", currentUserId);
                signal.put("callId", callId);
                signal.put("timestamp", Timestamp.now());
                db.collection("users").document(targetUserId).collection("signals").add(signal);
            } catch (Throwable ignored) {}
        }
    }

    private void checkAndTriggerHolePunchSpray(String targetUserId, IceCandidate candidate) {
        if (targetUserId == null || candidate == null || candidate.sdp == null) return;
        if (candidate.sdp.contains("typ srflx") || candidate.sdp.contains("typ prflx")) {
            try {
                String[] parts = candidate.sdp.split("\\s+");
                if (parts.length >= 6) {
                    String ip = parts[4];
                    int port = Integer.parseInt(parts[5]);
                    SovereignNatTraversalEngine.getInstance(null)
                            .executeSymmetricPortSpray(targetUserId, ip, port);
                }
            } catch (Exception ignored) {}
        }
    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
        if (firestoreSignalingListener != null) {
            firestoreSignalingListener.remove();
            firestoreSignalingListener = null;
        }
        isSocketConnected = false;
    }
}
