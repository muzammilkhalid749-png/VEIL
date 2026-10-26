package com.m3.veil.webrtc;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap.CompressFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ListenerRegistration;
import com.m3.veil.R.drawable;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.StatusController;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.Post;
import com.m3.veil.services.VeilMeshDaemonService;
import com.m3.veil.ui.chat.CallActivity;
import com.m3.veil.utils.EncryptionManager;
import com.m3.veil.utils.VeilPerformanceController;
import com.m3.veil.utils.VeilTorProxyBridge;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoTrack;
import org.webrtc.DataChannel.State;
import org.webrtc.PeerConnection.ContinualGatheringPolicy;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.PeerConnection.IceTransportsType;
import org.webrtc.PeerConnection.SdpSemantics;
import org.webrtc.PeerConnection.SignalingState;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.SessionDescription.Type;

public class WebRtcManager implements SignalingClient.SignalingListener {
   private static final String TAG = "WebRtcManager";
   private static final String DATA_CHANNEL_LABEL = "veil-p2p-channel";
   private static WebRtcManager instance;
   private final Context context;
   private final EglBase rootEglBase = EglBase.create();
   private PeerConnectionFactory peerConnectionFactory;
   private String currentUserId;
   private final Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
   private final Map<String, DataChannel> dataChannels = new ConcurrentHashMap<>();
   private final Map<String, PeerChannelHub> peerHubs = new ConcurrentHashMap<>();
   private final Map<String, Boolean> peerTypingStates = new ConcurrentHashMap<>();
   private final Map<String, Runnable> turnFallbackRunnables = new ConcurrentHashMap<>();
   private final Set<String> turnActivePeers = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private final Map<String, Long> peerLastSeenTime = new ConcurrentHashMap<>();
   private final List<P2PEventListener> listeners = Collections.synchronizedList(new ArrayList<>());
   private final Handler mainHandler = new Handler(Looper.getMainLooper());
   private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
   private ListenerRegistration inboxListener;
   private AudioSource localAudioSource;
   private AudioTrack localAudioTrack;
   private final Set<String> terminatedCallIds = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(100, 0.75F, true) {
      protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
         return this.size() > 300;
      }
   }));
   private final Set<String> processedCallInvites = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(100, 0.75F, true) {
      protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
         return this.size() > 300;
      }
   }));
   private volatile String activeCallId = null;
   private volatile String activeCallPeerId = null;
   private Object audioFocusRequest = null;
   private final Runnable heartbeatRunnable = new Runnable() {
      public void run() {
         long now = System.currentTimeMillis();

         for(Map.Entry<String, DataChannel> entry : dataChannels.entrySet()) {
            String peerId = (String)entry.getKey();
            DataChannel dc = (DataChannel)entry.getValue();
            if (dc != null && dc.state() == State.OPEN) {
               Long lastSeen = (Long)WebRtcManager.this.peerLastSeenTime.get(peerId);
               if (lastSeen != null && now - lastSeen > 10000L) {
                  Log.d("WebRtcManager", "Peer " + peerId + " heartbeat dead-man timed out (" + (now - lastSeen) + "ms) -> disconnecting");
                  WebRtcManager.this.closePeerConnection(peerId);
                  WebRtcManager.this.mainHandler.post(() -> {
                     for(P2PEventListener l : WebRtcManager.this.listeners) {
                        l.onPeerDisconnected(peerId);
                     }

                  });
               } else {
                  try {
                     VeilPacket pingPacket = new VeilPacket((byte)0, (short)1, new byte[0]);
                     if (!WebRtcManager.this.sendPacket(peerId, pingPacket)) {
                        JSONObject ping = new JSONObject();
                        ping.put("type", "p2p_ping");
                        ping.put("senderId", WebRtcManager.this.currentUserId);
                        byte[] bytes = ping.toString().getBytes(StandardCharsets.UTF_8);
                        dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
                     }
                  } catch (Throwable var11) {
                  }
               }
            }
         }

         WebRtcManager.this.heartbeatHandler.postDelayed(this, 3500L);
      }
   };
   public static final int INCOMING_CALL_NOTIFICATION_ID = 9991;
   private static final String CALL_NOTIFICATION_CHANNEL_ID = "veil_call_channel";

   public static synchronized WebRtcManager getInstance(Context context) {
      if (instance == null) {
         Context appCtx = context != null ? context.getApplicationContext() : com.m3.veil.VeilApplication.getInstance();
         instance = new WebRtcManager(appCtx);
      }

      return instance;
   }

   private WebRtcManager(Context context) {
      this.context = context;
      this.initializePeerConnectionFactory();
      SignalingClient.getInstance().setListener(this);
      TurnCredentialManager.getInstance(context).probeAllNodesAsync();
      P2PFileTransferHelper.setOnFileRequestListener(new P2PFileTransferHelper.OnFileRequestListener() {
         public void onFileRequested(String transferId) {
            WebRtcManager.this.handleFileRequestFromPeer(transferId);
         }

         public void onFileTransferCanceled(String transferId) {
            Log.d("WebRtcManager", "File transfer canceled by peer: " + transferId);
         }
      });
      this.startHeartbeatLoop();
      this.registerNetworkCallback();
   }

   public void addEventListener(P2PEventListener listener) {
      if (listener != null && !this.listeners.contains(listener)) {
         this.listeners.add(listener);
      }

   }

   public void removeEventListener(P2PEventListener listener) {
      if (listener != null) {
         this.listeners.remove(listener);
      }

   }

   private volatile String initializedUserId = null;

   public void init(String userId) {
      if (userId != null && !userId.isEmpty()) {
         this.currentUserId = userId;
         if (userId.equals(this.initializedUserId)) {
            return;
         }
         this.initializedUserId = userId;
         SignalingClient.getInstance().init(userId);
         VeilMeshDaemonService.start(this.context);
         this.syncAllPendingMessages();
      }
   }

   public void listenOfflineInbox(String userId) {
   }

   public void fetchAndProcessOfflineInboxOnce(String userId) {
   }

   public void depositOfflineMessageEnvelope(String peerId, String chatId, Message message, String rawJsonPayload, boolean alreadySentP2p) {
      if (!alreadySentP2p && peerId != null && message != null) {
         VeilMeshDaemonService daemon = VeilMeshDaemonService.getInstance(this.context);
         if (daemon != null) {
            daemon.enqueueOutgoingMessage((Chat)null, peerId, message);
         }
      }

   }

   public void depositOfflineActionEnvelope(String peerId, String rawJsonPayload) {
      if (peerId != null && rawJsonPayload != null && !rawJsonPayload.isEmpty()) {
         VeilMeshDaemonService daemon = VeilMeshDaemonService.getInstance(this.context);
         if (daemon != null) {
            daemon.enqueueActionEnvelope(peerId, rawJsonPayload);
         }
      }
   }

   public void syncAllPendingMessages() {
      if (this.currentUserId != null) {
         (new Thread(() -> {
            try {
               VeilMeshDaemonService daemon = VeilMeshDaemonService.getInstance(this.context);
               if (daemon != null) {
                  daemon.triggerOutboxSync();
               }
            } catch (Exception e) {
               Log.e("WebRtcManager", "Error syncing pending messages", e);
            }

         })).start();
      }
   }

   private void startHeartbeatLoop() {
      this.heartbeatHandler.removeCallbacks(this.heartbeatRunnable);
      this.heartbeatHandler.postDelayed(this.heartbeatRunnable, 3500L);
   }

   public boolean isPeerConnected(String peerId) {
      if (peerId == null) {
         return false;
      } else {
         PeerChannelHub hub = (PeerChannelHub)this.peerHubs.get(peerId);
         if (hub != null && hub.isConnected()) {
            Long lastSeen = (Long)this.peerLastSeenTime.get(peerId);
            if (lastSeen != null && System.currentTimeMillis() - lastSeen < 15000L) {
               return true;
            }
         }

         DataChannel dc = (DataChannel)this.dataChannels.get(peerId);
         if (dc != null && dc.state() == State.OPEN) {
            Long lastSeen = (Long)this.peerLastSeenTime.get(peerId);
            return lastSeen != null && System.currentTimeMillis() - lastSeen < 15000L;
         } else {
            return false;
         }
      }
   }

   public List<String> getConnectedPeerIds() {
      List<String> list = new ArrayList();

      for(Map.Entry<String, PeerChannelHub> entry : this.peerHubs.entrySet()) {
         if (entry.getValue() != null && ((PeerChannelHub)entry.getValue()).isConnected()) {
            list.add((String)entry.getKey());
         }
      }

      return list;
   }

   public DataChannel getChannelForLane(String peerId, byte lane) {
      if (peerId == null) {
         return null;
      } else {
         PeerChannelHub hub = (PeerChannelHub)this.peerHubs.get(peerId);
         return hub != null ? hub.getChannelForLane(lane) : (DataChannel)this.dataChannels.get(peerId);
      }
   }

   public DataChannel getDataChannel(String peerId) {
      return this.getChannelForLane(peerId, (byte)1);
   }

   public int getConnectedPeersCount() {
      int count = 0;

      for(PeerChannelHub hub : this.peerHubs.values()) {
         if (hub != null && hub.isConnected()) {
            ++count;
         }
      }

      if (count == 0) {
         for(DataChannel dc : this.dataChannels.values()) {
            if (dc != null && dc.state() == State.OPEN) {
               ++count;
            }
         }
      }

      return count;
   }

   public boolean isPeerTyping(String peerId) {
      return peerId != null && Boolean.TRUE.equals(this.peerTypingStates.get(peerId));
   }

   public boolean isNetworkAvailable() {
      try {
         ConnectivityManager cm = (ConnectivityManager)this.context.getSystemService("connectivity");
         if (cm == null) {
            return true;
         } else {
            Network network = cm.getActiveNetwork();
            if (network == null) {
               return false;
            } else {
               NetworkCapabilities caps = cm.getNetworkCapabilities(network);
               return caps != null && (caps.hasCapability(12) || caps.hasTransport(1) || caps.hasTransport(0));
            }
         }
      } catch (Exception var4) {
         return true;
      }
   }

   private void registerNetworkCallback() {
      try {
         ConnectivityManager cm = (ConnectivityManager)this.context.getSystemService("connectivity");
         if (cm != null && VERSION.SDK_INT >= 24) {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
               private Network activeNet = null;

               public void onAvailable(Network network) {
                  try {
                     if (this.activeNet != null && !this.activeNet.equals(network)) {
                        Log.i("WebRtcManager", "Network switched -> restarting ICE on active WebRTC mesh connections");
                        WebRtcManager.this.restartAllIceConnections();
                     }

                     this.activeNet = network;
                     if (WebRtcManager.this.currentUserId != null) {
                        VeilMeshDaemonService daemon = VeilMeshDaemonService.getInstance(WebRtcManager.this.context);
                        if (daemon != null) {
                           daemon.triggerOutboxSync();
                        }
                     }
                  } catch (Throwable ignored) {
                  }
               }

               public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                  try {
                     if (WebRtcManager.this.currentUserId != null && networkCapabilities != null && networkCapabilities.hasCapability(12)) {
                        VeilMeshDaemonService daemon = VeilMeshDaemonService.getInstance(WebRtcManager.this.context);
                        if (daemon != null) {
                           daemon.triggerOutboxSync();
                        }
                     }
                  } catch (Throwable ignored) {
                  }
               }
            });
         }
      } catch (Throwable t) {
         Log.w("WebRtcManager", "Failed to register network callback: " + t.getMessage());
      }

   }

   public void restartAllIceConnections() {
      for(Map.Entry<String, PeerConnection> entry : this.peerConnections.entrySet()) {
         final String peerId = (String)entry.getKey();
         final PeerConnection pc = (PeerConnection)entry.getValue();
         if (pc != null) {
            try {
               MediaConstraints constraints = new MediaConstraints();
               constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
               pc.createOffer(new SdpObserver() {
                  public void onCreateSuccess(final SessionDescription sessionDescription) {
                     pc.setLocalDescription(new SdpObserver() {
                        public void onCreateSuccess(SessionDescription sdp) {
                        }

                        public void onSetSuccess() {
                           SignalingClient.getInstance().sendOffer(peerId, sessionDescription.description);
                        }

                        public void onCreateFailure(String s) {
                        }

                        public void onSetFailure(String s) {
                        }
                     }, sessionDescription);
                  }

                  public void onSetSuccess() {
                  }

                  public void onCreateFailure(String s) {
                     Log.w("WebRtcManager", "ICE restart offer failure for " + peerId + ": " + s);
                  }

                  public void onSetFailure(String s) {
                  }
               }, constraints);
            } catch (Throwable t) {
               Log.w("WebRtcManager", "Error restarting ICE for " + peerId + ": " + t.getMessage());
            }
         }
      }

   }

   private void initializePeerConnectionFactory() {
      PeerConnectionFactory.InitializationOptions initOptions = InitializationOptions.builder(this.context).setEnableInternalTracer(false).createInitializationOptions();
      PeerConnectionFactory.initialize(initOptions);
      PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
      DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(this.rootEglBase.getEglBaseContext(), true, false);
      VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(this.rootEglBase.getEglBaseContext());
      this.peerConnectionFactory = PeerConnectionFactory.builder().setOptions(options).setVideoEncoderFactory(encoderFactory).setVideoDecoderFactory(decoderFactory).createPeerConnectionFactory();
   }

   private List<PeerConnection.IceServer> getIceServers() {
      return this.getIceServers(false);
   }

   private List<PeerConnection.IceServer> getIceServers(boolean includeTurn) {
      return com.m3.veil.calling.CallConfig.iceServers(includeTurn);
   }

   public void scheduleTurnFallback(String peerId) {
      if (peerId != null) {
         this.cancelTurnFallback(peerId);
         Runnable r = () -> {
            PeerConnection pc = (PeerConnection)this.peerConnections.get(peerId);
            if (pc != null) {
               PeerConnection.IceConnectionState state = pc.iceConnectionState();
               if (state != IceConnectionState.CONNECTED && state != IceConnectionState.COMPLETED) {
                  Log.i("WebRtcManager", "Tier 2 Activated: Direct STUN timed out (1,500ms). Falling back to TURN TCP/443 for peer: " + peerId);
                  this.restartIceWithTurn(peerId);
               }
            }

         };
         this.turnFallbackRunnables.put(peerId, r);
         this.mainHandler.postDelayed(r, com.m3.veil.calling.CallConfig.DIRECT_STUN_TIMEOUT_MS);
      }
   }

   public void cancelTurnFallback(String peerId) {
      if (peerId != null) {
         Runnable r = (Runnable)this.turnFallbackRunnables.remove(peerId);
         if (r != null) {
            this.mainHandler.removeCallbacks(r);
         }

      }
   }

   public void restartIceWithTurn(final String peerId) {
      if (peerId != null) {
         final PeerConnection pc = (PeerConnection)this.peerConnections.get(peerId);
         if (pc != null) {
            this.turnActivePeers.add(peerId);

            try {
               Log.i("WebRtcManager", "Tier 2: Upgrading configuration to TURN Port 443 TCP/UDP for peer " + peerId);
               PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(this.getIceServers(true));
               rtcConfig.sdpSemantics = SdpSemantics.UNIFIED_PLAN;
               rtcConfig.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY;
               if (VeilTorProxyBridge.getInstance(this.context).isStrictRelayEnforced()) {
                  rtcConfig.iceTransportsType = IceTransportsType.RELAY;
               }

               pc.setConfiguration(rtcConfig);
               MediaConstraints constraints = new MediaConstraints();
               constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
               pc.createOffer(new SdpObserver() {
                  public void onCreateSuccess(final SessionDescription sessionDescription) {
                     pc.setLocalDescription(new SdpObserver() {
                        public void onCreateSuccess(SessionDescription sdp) {
                        }

                        public void onSetSuccess() {
                           SignalingClient.getInstance().sendOffer(peerId, sessionDescription.description);
                           Log.d("WebRtcManager", "Dispatched TURN ICE restart offer to " + peerId);
                        }

                        public void onCreateFailure(String s) {
                        }

                        public void onSetFailure(String s) {
                        }
                     }, sessionDescription);
                  }

                  public void onSetSuccess() {
                  }

                  public void onCreateFailure(String s) {
                     Log.w("WebRtcManager", "TURN ICE restart offer creation failed for " + peerId + ": " + s);
                  }

                  public void onSetFailure(String s) {
                  }
               }, constraints);
            } catch (Throwable t) {
               Log.e("WebRtcManager", "Failed to restart ICE with TURN for " + peerId, t);
            }

         }
      }
   }

   public synchronized void closePeerConnection(String peerId) {
      if (peerId != null) {
         this.cancelTurnFallback(peerId);
         this.turnActivePeers.remove(peerId);
         this.peerLastSeenTime.remove(peerId);
         this.peerTypingStates.remove(peerId);
         PeerChannelHub hub = (PeerChannelHub)this.peerHubs.remove(peerId);
         if (hub != null) {
            hub.closeAll();
         }

         DataChannel dc = (DataChannel)this.dataChannels.remove(peerId);
         if (dc != null) {
            try {
               dc.unregisterObserver();
               dc.close();
            } catch (Exception var7) {
            }
         }

         PeerConnection pc = (PeerConnection)this.peerConnections.remove(peerId);
         if (pc != null) {
            try {
               pc.close();
               pc.dispose();
            } catch (Exception var6) {
            }
         }

      }
   }

   public void broadcastLeaving() {
      (new Thread(() -> {
         try {
            JSONObject leaveMsg = new JSONObject();
            leaveMsg.put("type", "peer_leaving");
            leaveMsg.put("senderId", this.currentUserId);
            byte[] data = leaveMsg.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(data);

            for(Map.Entry entry : this.dataChannels.entrySet()) {
               DataChannel dc = (DataChannel)entry.getValue();
               if (dc != null && dc.state() == State.OPEN) {
                  try {
                     dc.send(new DataChannel.Buffer(buffer.duplicate(), false));
                  } catch (Throwable var8) {
                  }
               }
            }
         } catch (Throwable t) {
            Log.w("WebRtcManager", "broadcastLeaving error: " + t.getMessage());
         }

      })).start();
   }

   public synchronized PeerConnection getOrCreatePeerConnection(final String peerId, boolean isInitiator) {
      PeerConnection pc = (PeerConnection)this.peerConnections.get(peerId);
      if (pc != null) {
         PeerConnection.IceConnectionState iceState = pc.iceConnectionState();
         PeerConnection.SignalingState sigState = pc.signalingState();
         if (iceState != IceConnectionState.FAILED && iceState != IceConnectionState.CLOSED && sigState != SignalingState.CLOSED) {
            if (isInitiator) {
               PeerChannelHub hub = (PeerChannelHub)this.peerHubs.get(peerId);
               if (hub == null || !hub.isConnected()) {
                  this.createInitiatorDataChannels(pc, peerId);
               }
            }

            return pc;
         }

         this.closePeerConnection(peerId);
         pc = null;
      }

      int maxConn = VeilPerformanceController.getInstance().getMaxWebRtcConnections();
      if (!this.peerConnections.containsKey(peerId) && this.peerConnections.size() >= maxConn) {
         String pruneCandidate = null;

         for(Map.Entry<String, PeerConnection> entry : this.peerConnections.entrySet()) {
            PeerConnection.IceConnectionState state = ((PeerConnection)entry.getValue()).iceConnectionState();
            if (state == IceConnectionState.DISCONNECTED || state == IceConnectionState.FAILED || state == IceConnectionState.CLOSED || state == IceConnectionState.NEW) {
               pruneCandidate = (String)entry.getKey();
               break;
            }
         }

         if (pruneCandidate != null) {
            Log.i("WebRtcManager", "Pruning stale peer connection " + pruneCandidate + " to respect device budget (" + maxConn + " peers)");
            this.closePeerConnection(pruneCandidate);
         }
      }

      boolean strictRelay = VeilTorProxyBridge.getInstance(this.context).isStrictRelayEnforced();
      PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(this.getIceServers(strictRelay));
      rtcConfig.sdpSemantics = SdpSemantics.UNIFIED_PLAN;
      rtcConfig.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY;
      if (strictRelay) {
         rtcConfig.iceTransportsType = IceTransportsType.RELAY;
         Log.i("WebRtcManager", "Tor/Strict Relay mode active: PeerConnection initialized with IceTransportsType.RELAY");
      }

      pc = this.peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
         public void onSignalingChange(PeerConnection.SignalingState signalingState) {
         }

         public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.d("WebRtcManager", "ICE Connection State with " + peerId + ": " + state);
            if (state != IceConnectionState.CONNECTED && state != IceConnectionState.COMPLETED) {
               if (state == IceConnectionState.FAILED) {
                  if (!WebRtcManager.this.turnActivePeers.contains(peerId)) {
                     Log.w("WebRtcManager", "ICE direct STUN failed for " + peerId + " -> immediately escalating to Tier 2 TURN Port 443");
                     WebRtcManager.this.restartIceWithTurn(peerId);
                  } else {
                     WebRtcManager.this.cancelTurnFallback(peerId);
                     WebRtcManager.this.mainHandler.post(() -> {
                        for(P2PEventListener l : WebRtcManager.this.listeners) {
                           l.onPeerDisconnected(peerId);
                        }

                     });
                     WebRtcManager.this.peerConnections.remove(peerId);
                     WebRtcManager.this.dataChannels.remove(peerId);
                     PeerChannelHub hub = (PeerChannelHub)WebRtcManager.this.peerHubs.remove(peerId);
                     if (hub != null) {
                        hub.closeAll();
                     }
                  }
               } else if (state == IceConnectionState.DISCONNECTED || state == IceConnectionState.CLOSED) {
                  WebRtcManager.this.mainHandler.post(() -> {
                     for(P2PEventListener l : WebRtcManager.this.listeners) {
                        l.onPeerDisconnected(peerId);
                     }

                  });
                  if (state == IceConnectionState.CLOSED) {
                     WebRtcManager.this.cancelTurnFallback(peerId);
                     WebRtcManager.this.peerConnections.remove(peerId);
                     WebRtcManager.this.dataChannels.remove(peerId);
                     PeerChannelHub hub = (PeerChannelHub)WebRtcManager.this.peerHubs.remove(peerId);
                     if (hub != null) {
                        hub.closeAll();
                     }
                  }
               }
            } else {
               WebRtcManager.this.cancelTurnFallback(peerId);
               WebRtcManager.this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onPeerConnected(peerId);
                     l.onCallEstablished(peerId);
                  }

               });
            }

         }

         public void onIceConnectionReceivingChange(boolean b) {
         }

         public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
         }

         public void onIceCandidate(IceCandidate iceCandidate) {
            SignalingClient.getInstance().sendIceCandidate(peerId, iceCandidate);
         }

         public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
         }

         public void onAddStream(MediaStream mediaStream) {
            if (mediaStream != null && !mediaStream.videoTracks.isEmpty()) {
               VideoTrack remoteTrack = (VideoTrack)mediaStream.videoTracks.get(0);
               WebRtcManager.this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onRemoteVideoTrackAdded(peerId, remoteTrack);
                  }

               });
            }

         }

         public void onRemoveStream(MediaStream mediaStream) {
            WebRtcManager.this.mainHandler.post(() -> {
               for(P2PEventListener l : WebRtcManager.this.listeners) {
                  l.onRemoteVideoTrackRemoved(peerId);
               }

               WebRtcManager.this.stopCallAudioHardware(peerId);

               for(P2PEventListener l : WebRtcManager.this.listeners) {
                  l.onCallEnded(peerId);
               }

            });
         }

         public void onDataChannel(DataChannel dataChannel) {
            String label = dataChannel.label();
            PeerChannelHub hub = (PeerChannelHub)WebRtcManager.this.peerHubs.computeIfAbsent(peerId, PeerChannelHub::new);
            byte lane;
            if ("veil-ctrl".equals(label)) {
               hub.ctrlChannel = dataChannel;
               lane = 0;
            } else if ("veil-media".equals(label)) {
               hub.mediaChannel = dataChannel;
               lane = 2;
            } else if (!"veil-swarm".equals(label) && !"veil-mesh-gossip".equals(label)) {
               hub.msgChannel = dataChannel;
               lane = 1;
            } else {
               hub.gossipChannel = dataChannel;
               hub.swarmChannel = dataChannel;
               lane = 3;
            }

            WebRtcManager.this.setupDataChannel(peerId, dataChannel, lane);
         }

         public void onRenegotiationNeeded() {
         }

         public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            try {
               if (rtpReceiver != null && rtpReceiver.track() != null) {
                  rtpReceiver.track().setEnabled(true);
                  if (rtpReceiver.track() instanceof VideoTrack) {
                     VideoTrack remoteTrack = (VideoTrack)rtpReceiver.track();
                     WebRtcManager.this.mainHandler.post(() -> {
                        for(P2PEventListener l : WebRtcManager.this.listeners) {
                           l.onRemoteVideoTrackAdded(peerId, remoteTrack);
                        }

                     });
                  }
               }

               WebRtcManager.this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onCallEstablished(peerId);
                  }

               });
            } catch (Exception var4) {
            }

         }
      });
      this.peerConnections.put(peerId, pc);
      if (isInitiator) {
         this.createInitiatorDataChannels(pc, peerId);
      }

      return pc;
   }

   private void createInitiatorDataChannels(PeerConnection pc, String peerId) {
      PeerChannelHub hub = (PeerChannelHub)this.peerHubs.computeIfAbsent(peerId, PeerChannelHub::new);
      DataChannel.Init reliableInit = new DataChannel.Init();
      reliableInit.ordered = true;
      DataChannel.Init gossipInit = new DataChannel.Init();
      gossipInit.ordered = false;
      gossipInit.maxRetransmits = 3;

      try {
         DataChannel ctrl = pc.createDataChannel("veil-ctrl", reliableInit);
         DataChannel msg = pc.createDataChannel("veil-msg", reliableInit);
         DataChannel media = pc.createDataChannel("veil-media", reliableInit);
         DataChannel swarm = pc.createDataChannel("veil-swarm", gossipInit);
         hub.ctrlChannel = ctrl;
         hub.msgChannel = msg;
         hub.mediaChannel = media;
         hub.gossipChannel = swarm;
         hub.swarmChannel = swarm;
         this.setupDataChannel(peerId, ctrl, (byte)0);
         this.setupDataChannel(peerId, msg, (byte)1);
         this.setupDataChannel(peerId, media, (byte)2);
         this.setupDataChannel(peerId, swarm, (byte)3);
      } catch (Exception e) {
         Log.e("WebRtcManager", "Error creating multi-lane DataChannels for " + peerId, e);
      }

   }

   private void setupDataChannel(String peerId, DataChannel dataChannel) {
      this.setupDataChannel(peerId, dataChannel, (byte)1);
   }

   private void setupDataChannel(final String peerId, final DataChannel dataChannel, final byte lane) {
      if (lane == 1 || !this.dataChannels.containsKey(peerId)) {
         this.dataChannels.put(peerId, dataChannel);
      }
      dataChannel.registerObserver(new DataChannel.Observer() {
         public void onBufferedAmountChange(long l) {
         }

         public void onStateChange() {
            Log.d("WebRtcManager", "DataChannel [" + dataChannel.label() + "] state with " + peerId + ": " + dataChannel.state());
            if (dataChannel.state() == State.OPEN) {
               WebRtcManager.this.peerLastSeenTime.put(peerId, System.currentTimeMillis());
               SignalingClient.getInstance().pruneSignalingForPeer(peerId);
               if (lane == 1) {
                  WebRtcManager.this.mainHandler.post(() -> {
                     for(P2PEventListener l : WebRtcManager.this.listeners) {
                        l.onPeerConnected(peerId);
                     }

                  });
                  VeilMeshDaemonService.getInstance(WebRtcManager.this.context).flushOutboxForPeer(peerId);
                  WebRtcManager.this.syncVeilBoardGossipWithPeer(peerId, dataChannel);
               }
            } else if (dataChannel.state() == State.CLOSED) {
               PeerChannelHub hub = (PeerChannelHub)WebRtcManager.this.peerHubs.get(peerId);
               if (hub == null || !hub.isConnected()) {
                  WebRtcManager.this.peerLastSeenTime.remove(peerId);
                  WebRtcManager.this.dataChannels.remove(peerId);
                  WebRtcManager.this.mainHandler.post(() -> {
                     for(P2PEventListener l : WebRtcManager.this.listeners) {
                        l.onPeerDisconnected(peerId);
                     }

                  });
               }
            }

         }

         public void onMessage(DataChannel.Buffer buffer) {
            ByteBuffer data = buffer.data;
            if (data.remaining() == 9) {
               data.mark();
               byte op = data.get();
               if (op == (byte) 0xFE) {
                  long ts = data.getLong();
                  DataChannel dc = getDataChannel(peerId);
                  if (dc != null && dc.state() == DataChannel.State.OPEN) {
                     ByteBuffer pong = ByteBuffer.allocate(9);
                     pong.put((byte) 0xFF);
                     pong.putLong(ts);
                     pong.flip();
                     dc.send(new DataChannel.Buffer(pong, false));
                  }
                  return;
               } else if (op == (byte) 0xFF) {
                  long ts = data.getLong();
                  SovereignNatTraversalEngine.getInstance(WebRtcManager.this.context).handlePongFrame(peerId, ts);
                  return;
               }
               data.reset();
            }
            if (data.remaining() >= 24) {
               data.mark();
               int magic = data.getInt();
               data.reset();
               if (magic == 1447381324) {
                  VeilPacket packet = VeilPacket.deserialize(data);
                  if (packet != null) {
                     WebRtcManager.this.handleVeilPacket(peerId, packet);
                     return;
                  }
               }
            }

            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String payload = new String(bytes, StandardCharsets.UTF_8);
            WebRtcManager.this.processInboundPayload(peerId, payload);
         }
      });
   }

   public boolean sendPacket(String peerId, VeilPacket packet) {
      if (peerId != null && packet != null) {
         PeerChannelHub hub = (PeerChannelHub)this.peerHubs.get(peerId);
         DataChannel dc = hub != null ? hub.getChannelForLane(packet.getLane()) : (DataChannel)this.dataChannels.get(peerId);
         if (dc != null && dc.state() == State.OPEN) {
            try {
               byte[] bytes = packet.serialize();
               return dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
            } catch (Throwable t) {
               Log.e("WebRtcManager", "sendPacket error to " + peerId + ": " + t.getMessage());
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public void handleVeilPacket(String peerId, VeilPacket packet) {
      if (peerId != null) {
         this.peerLastSeenTime.put(peerId, System.currentTimeMillis());
      }

      if (packet != null) {
         byte lane = packet.getLane();
         short packetType = packet.getPacketType();
         if (lane == 0 || packetType == 1 || packetType == 2 || packetType == 3 || packetType == 4 || packetType == 5 || packetType == 6) {
            if (packetType == 1) {
               VeilPacket pong = new VeilPacket((byte)0, (short)2, new byte[0]);
               this.sendPacket(peerId, pong);
               return;
            }

            if (packetType == 2) {
               return;
            }

            if (packetType == 3) {
               byte[] p = packet.getPayload();
               boolean isTyping = p != null && p.length > 0 && p[0] == 1;
               this.peerTypingStates.put(peerId, isTyping);
               this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onPeerTyping(peerId, isTyping);
                  }

               });
               return;
            }

            if (packetType == 4) {
               String payloadStr = packet.getPayloadAsString();
               String messageId = payloadStr;

               try {
                  if (payloadStr.startsWith("{")) {
                     JSONObject j = new JSONObject(payloadStr);
                     messageId = j.optString("messageId", payloadStr);
                  }
               } catch (Exception var8) {
               }

               final String finalDeliveredId = messageId;
               EncryptedChatDatabase.getInstance(this.context).updateMessageStatus(finalDeliveredId, true, false);
               VeilMeshDaemonService.getInstance(this.context).onDeliveredAckReceived(finalDeliveredId);
               this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onMessageStatusChanged(finalDeliveredId, true, false);
                  }

               });
               return;
            }

            if (packetType == 5) {
               String payloadStr = packet.getPayloadAsString();
               String messageId = payloadStr;

               try {
                  if (payloadStr.startsWith("{")) {
                     JSONObject j = new JSONObject(payloadStr);
                     if ("status_viewed".equals(j.optString("type"))) {
                        StatusController.getInstance(this.context).onInboundViewReceipt(peerId, j);
                        return;
                     }

                     messageId = j.optString("messageId", payloadStr);
                  }
               } catch (Exception var9) {
               }

               final String finalReadId = messageId;
               EncryptedChatDatabase.getInstance(this.context).updateMessageStatus(finalReadId, true, true);
               this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onMessageStatusChanged(finalReadId, true, true);
                  }

               });
               return;
            }

            if (packetType == 6) {
               String messageId = packet.getPayloadAsString();
               String chatId = getChatId(this.currentUserId, peerId);
               EncryptedChatDatabase.getInstance(this.context).markBlinkMessageViewed(chatId, messageId);
               this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onMessageStatusChanged(messageId, true, true);
                  }

               });
               return;
            }
         }

         if (lane != 2 && packetType != 513 && packetType != 514 && packetType != 516 && packetType != 517 && packetType != 518) {
            if (lane != 3 && packetType != 769 && packetType != 770 && packetType != 775) {
               this.processInboundPayload(peerId, packet.getPayloadAsString());
            } else if (packetType == 775) {
               try {
                  StatusController.getInstance(this.context).onInboundStatusGossipPacket(peerId, packet.getPayload());
               } catch (Throwable var10) {
               }

               this.processInboundPayload(peerId, packet.getPayloadAsString());
            } else {
               VeilMeshDaemonService.getInstance(this.context).handleMeshPacket(peerId, packet);
            }
         } else {
            P2PFileTransferHelper.handleIncomingPacket(this.context, peerId, packet, (transferId, fileName, mediaType, caption, file) -> {
               String chatId = getChatId(this.currentUserId, peerId);
               Message existing = EncryptedChatDatabase.getInstance(this.context).getMessageById(transferId);
               String messageContent = caption != null && !caption.trim().isEmpty() ? caption.trim() : fileName;
               Message fileMsg = existing != null ? existing : new Message(transferId, peerId, this.currentUserId, messageContent, Timestamp.now(), mediaType);
               fileMsg.setLocalFilePath(file.getAbsolutePath());
               fileMsg.setDelivered(true);
               fileMsg.setRead(false);
               EncryptedChatDatabase.getInstance(this.context).saveMessage(chatId, fileMsg);
               this.sendReceipt(peerId, transferId, true, false);
               this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onMessageReceived(peerId, fileMsg);
                  }

               });
            });
         }
      }
   }

   public void processInnerDeliveredMessage(String fromPeerId, VeilPacket innerPacket) {
      if (innerPacket != null) {
         try {
            String rawStr = innerPacket.getPayloadAsString();
            if (rawStr != null && rawStr.trim().startsWith("{")) {
               JSONObject rawJson = new JSONObject(rawStr);
               if (rawJson.has("type") && rawJson.has("payload")) {
                  this.processInboundPayload(fromPeerId, rawStr);
                  return;
               }
               if (innerPacket.getPacketType() == 257) {
                  JSONObject wrapped = new JSONObject();
                  wrapped.put("type", "chat");
                  wrapped.put("payload", rawJson);
                  this.processInboundPayload(fromPeerId, wrapped.toString());
                  return;
               }
            }
            this.processInboundPayload(fromPeerId, rawStr);
         } catch (Exception e) {
            Log.e("WebRtcManager", "Error processing inner delivered message", e);
         }

      }
   }

   private void handleInboundDataChannelPayload(String peerId, String payload) {
      this.processInboundPayload(peerId, payload);
   }

   public void processInboundPayload(String peerId, String payload) {
      if (peerId != null) {
         this.peerLastSeenTime.put(peerId, System.currentTimeMillis());
      }

      try {
         JSONObject json = new JSONObject(payload);
         String type = json.optString("type");
         if ("p2p_ping".equals(type)) {
            DataChannel dc = (DataChannel)this.dataChannels.get(peerId);
            if (dc != null && dc.state() == State.OPEN) {
               try {
                  JSONObject pong = new JSONObject();
                  pong.put("type", "p2p_pong");
                  pong.put("senderId", this.currentUserId);
                  byte[] bytes = pong.toString().getBytes(StandardCharsets.UTF_8);
                  dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
               } catch (Throwable var33) {
               }
            }

            return;
         }

         if ("p2p_pong".equals(type)) {
            return;
         }

         if ("chat".equals(type) || (type.isEmpty() && json.has("id") && json.has("chatId"))) {
            JSONObject p = json.has("payload") ? json.getJSONObject("payload") : json;
            String msgId = p.getString("id");
            String chatId = p.optString("chatId", getChatId(this.currentUserId, peerId));
            String text = VeilFeatureManager.getInstance(this.context).applyWordFilter(p.optString("text"));
            String encContent = p.optString("encContent", (String)null);
            String aesKey = p.optString("aesKey", (String)null);
            String iv = p.optString("iv", (String)null);
            int ratchetStep = p.optInt("ratchetStep", 1);
            String dhPublicKey = p.optString("dhPublicKey", (String)null);
            if (encContent != null && iv != null) {
               try {
                  String decrypted = EncryptionManager.decryptHybridRatcheted(chatId, encContent, aesKey, iv, ratchetStep, dhPublicKey);
                  if (decrypted != null && !decrypted.isEmpty() && !decrypted.equals("[Encrypted Message]")) {
                     text = VeilFeatureManager.getInstance(this.context).applyWordFilter(decrypted);
                  }
               } catch (Throwable t) {
                  Log.w("WebRtcManager", "Inbound chat decryption notice: " + t.getMessage());
               }
            }

            String msgType = p.optString("msgType", "text");
            long timestamp = p.optLong("timestamp", System.currentTimeMillis());
            String replyId = p.optString("replyToId", (String)null);
            String replyName = p.optString("replySenderName", (String)null);
            boolean isBlink = p.optBoolean("blink", false);
            String voiceFilter = p.optString("voiceFilter", (String)null);
            String pollJson = p.optString("pollJson", (String)null);
            String senderName = p.optString("senderName", (String)null);
            long expiresAt = p.optLong("expiresAt", 0L);
            Message existingMsg = EncryptedChatDatabase.getInstance(this.context).getMessageById(msgId);
            if (existingMsg != null) {
               this.sendReceipt(peerId, msgId, true, existingMsg.isRead());
               return;
            }

            boolean isGroup = p.optBoolean("isGroup", false)
                    || (chatId != null && (chatId.startsWith("group_") || chatId.startsWith("channel_") || chatId.startsWith("lounge_")));
            List<String> incomingParts = new ArrayList<>();
            JSONArray partsArr = p.optJSONArray("participants");
            if (partsArr != null) {
               for (int i = 0; i < partsArr.length(); i++) {
                  String pid = partsArr.optString(i);
                  if (pid != null && !pid.isEmpty() && !incomingParts.contains(pid)) {
                     incomingParts.add(pid);
                  }
               }
            } else {
               String partStr = p.optString("participants", "");
               if (partStr != null && !partStr.isEmpty()) {
                  for (String pid : partStr.split(",")) {
                     if (!pid.trim().isEmpty() && !incomingParts.contains(pid.trim())) {
                        incomingParts.add(pid.trim());
                     }
                  }
               }
            }
            if (this.currentUserId != null && !incomingParts.contains(this.currentUserId)) {
               incomingParts.add(this.currentUserId);
            }
            if (peerId != null && !incomingParts.contains(peerId)) {
               incomingParts.add(peerId);
            }

            Chat existingChat = EncryptedChatDatabase.getInstance(this.context).getChat(chatId);
            if (existingChat == null) {
               String defaultName = isGroup ? "Encrypted Group" : (senderName != null ? senderName : "Peer " + peerId.substring(0, Math.min(5, peerId.length())));
               String chatName = p.optString("chatName", defaultName);
               String chatType = p.optString("chatType", chatId != null && chatId.startsWith("channel_") ? "channel" : (isGroup ? "group" : "direct"));
               Chat newChat = new Chat(chatId, chatName, text, new Timestamp(new Date(timestamp)), "avatar_1", 1, isGroup, true, chatType, incomingParts);
               String adminId = p.optString("adminId", null);
               if (adminId != null && !adminId.isEmpty()) newChat.setAdminId(adminId);
               String desc = p.optString("description", null);
               if (desc != null && !desc.isEmpty()) newChat.setDescription(desc);
               EncryptedChatDatabase.getInstance(this.context).saveOrUpdateChat(newChat);
            } else {
               if (isGroup && incomingParts.size() > (existingChat.getParticipants() != null ? existingChat.getParticipants().size() : 0)) {
                  existingChat.setParticipants(incomingParts);
               }
               String incomingChatName = p.optString("chatName", null);
               if (isGroup && incomingChatName != null && !incomingChatName.isEmpty()
                       && ("Encrypted Group".equals(existingChat.getName()) || "Anonymous Room".equals(existingChat.getName()))) {
                  existingChat.setName(incomingChatName);
               }
               existingChat.setUnreadCount(existingChat.getUnreadCount() + 1);
               existingChat.setLastMessage(text);
               existingChat.setLastMessageTime(new Timestamp(new Date(timestamp)));
               EncryptedChatDatabase.getInstance(this.context).saveOrUpdateChat(existingChat);
               EncryptedChatDatabase.getInstance(this.context).incrementChatTrustScore(chatId, 2);
            }

            Message message = new Message(msgId, peerId, isGroup ? chatId : this.currentUserId, text, new Timestamp(new Date(timestamp)), msgType);
            message.setChatId(chatId);
            message.setDelivered(true);
            message.setRead(false);
            message.setReplyToMessageId(replyId);
            message.setReplySenderName(replyName);
            message.setBlink(isBlink);
            message.setVoiceFilter(voiceFilter);
            message.setPollJson(pollJson);
            message.setSenderName(senderName);
            String thumb = p.optString("thumbnail", (String)null);
            String mediaUrl = p.optString("mediaUrl", (String)null);
            if (thumb != null && !thumb.isEmpty()) {
               message.setThumbnail(thumb);
            }

            if (mediaUrl != null && !mediaUrl.isEmpty()) {
               message.setMediaUrl(mediaUrl);
            }

            long msgVanishTimer = p.optLong("vanishTimer", existingChat != null ? existingChat.getVanishTimer() : 0L);
            String msgVanishMode = p.optString("vanishMode", existingChat != null ? existingChat.getVanishMode() : "after_send");
            message.setVanishTimer(msgVanishTimer);
            message.setVanishMode(msgVanishMode);
            if ("after_send".equals(msgVanishMode) && msgVanishTimer > 0L) {
               if (expiresAt > 0L) {
                  message.setExpiresAt(new Timestamp(new Date(expiresAt)));
               } else {
                  message.setExpiresAt(new Timestamp(new Date(timestamp + msgVanishTimer)));
               }
            } else if ("after_read".equals(msgVanishMode)) {
               message.setExpiresAt((Timestamp)null);
            } else if (expiresAt > 0L) {
               message.setExpiresAt(new Timestamp(new Date(expiresAt)));
            }

            EncryptedChatDatabase.getInstance(this.context).saveMessage(chatId, message);
            this.sendReceipt(peerId, msgId, true, false);
            this.mainHandler.post(() -> {
               for(P2PEventListener l : WebRtcManager.this.listeners) {
                  l.onMessageReceived(peerId, message);
               }

            });
         } else if ("receipt".equals(type)) {
            String messageId = json.getString("messageId");
            boolean delivered = json.optBoolean("delivered", false);
            boolean read = json.optBoolean("read", false);
            long readAt = json.optLong("readAt", System.currentTimeMillis());
            boolean blinkOpened = json.optBoolean("blinkOpened", false);
            if (blinkOpened) {
               String chatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
               EncryptedChatDatabase.getInstance(this.context).markBlinkMessageViewed(chatId, messageId);
            } else {
               EncryptedChatDatabase.getInstance(this.context).updateMessageStatus(messageId, delivered, read, readAt);
               if (delivered) {
                  VeilMeshDaemonService.getInstance(this.context).onDeliveredAckReceived(messageId);
               }
            }

            this.mainHandler.post(() -> {
               for(P2PEventListener l : WebRtcManager.this.listeners) {
                  l.onMessageStatusChanged(messageId, delivered, read);
               }

            });
         } else if ("chat_read".equals(type)) {
            long readAt = json.optLong("readAt", System.currentTimeMillis());
            String targetChatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
            EncryptedChatDatabase.getInstance(this.context).markOutboundMessagesAsRead(targetChatId, readAt);
            this.mainHandler.post(() -> {
               for(P2PEventListener l : WebRtcManager.this.listeners) {
                  l.onAllMessagesRead(targetChatId);
               }

            });
         } else if ("status_viewed".equals(type)) {
            String statusId = json.optString("statusId");
            String viewerId = json.optString("viewerId", peerId);
            String viewerName = json.optString("viewerName", "Anonymous Peer");
            String avatarUrl = json.optString("avatarUrl", "");
            VeilFeatureManager.getInstance(this.context).recordStatusView(statusId, viewerId, viewerName, avatarUrl);
         } else if ("status_post".equals(type)) {
            String id = json.optString("id");
            String authorId = json.optString("authorId", peerId);
            String authorName = json.optString("authorName", "Anonymous Peer");
            String contentText = json.optString("contentText", "");
            String mediaUri = json.optString("mediaUri", "");
            String bgColorHex = json.optString("bgColorHex", "#008069");
            int fontStyle = json.optInt("fontStyleIndex", 0);
            long timestamp = json.optLong("timestamp", System.currentTimeMillis());
            VeilFeatureManager.getInstance(this.context).addPeerStatus(id, authorId, authorName, contentText, mediaUri, bgColorHex, fontStyle, timestamp);
         } else if (!"peer_leaving".equals(type) && !"disconnect".equals(type)) {
            if ("typing".equals(type)) {
               boolean isTyping = json.optBoolean("isTyping", false);
               if (peerId != null) {
                  this.peerTypingStates.put(peerId, isTyping);
               }

               this.mainHandler.post(() -> {
                  for(P2PEventListener l : WebRtcManager.this.listeners) {
                     l.onPeerTyping(peerId, isTyping);
                  }

               });
            } else if ("reaction".equals(type)) {
               String chatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
               String messageId = json.getString("messageId");
               String reactorId = json.optString("userId", peerId);
               String emoji = json.optString("emoji", "");
               EncryptedChatDatabase.getInstance(this.context).updateMessageReaction(chatId, messageId, reactorId, emoji);
            } else if ("edit_msg".equals(type)) {
               String chatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
               String messageId = json.getString("messageId");
               String newText = json.optString("newText", "");
               EncryptedChatDatabase.getInstance(this.context).updateMessageText(chatId, messageId, newText);
            } else if ("delete_msg".equals(type)) {
               String chatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
               String messageId = json.getString("messageId");
               EncryptedChatDatabase.getInstance(this.context).markMessageDeletedForEveryone(chatId, messageId);
            } else if ("poll_vote".equals(type)) {
               String chatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
               String messageId = json.getString("messageId");
               String pollJson = json.optString("pollJson", "");
               EncryptedChatDatabase.getInstance(this.context).updateMessagePollJson(chatId, messageId, pollJson);
            } else if ("chat_setting".equals(type)) {
               String chatId = json.optString("chatId", getChatId(this.currentUserId, peerId));
               String setting = json.optString("setting");
               Chat chat = EncryptedChatDatabase.getInstance(this.context).getChat(chatId);
               if ("group_invite".equals(setting) || "group_update".equals(setting)) {
                  String gName = json.optString("groupName", chat != null ? chat.getName() : "Encrypted Group");
                  String gType = json.optString("groupType", chat != null ? chat.getType() : "group");
                  long gExp = json.optLong("groupExpiresAt", chat != null ? chat.getGroupExpiresAt() : 0L);
                  String partStr = json.optString("participants", "");
                  List<String> parts = new ArrayList<>();
                  if (!partStr.isEmpty()) {
                     for (String pId : partStr.split(",")) {
                        if (!pId.trim().isEmpty() && !parts.contains(pId.trim())) {
                           parts.add(pId.trim());
                        }
                     }
                  } else if (chat != null && chat.getParticipants() != null) {
                     parts.addAll(chat.getParticipants());
                  }
                  if (this.currentUserId != null && !parts.contains(this.currentUserId) && !"group_update".equals(setting)) {
                     parts.add(this.currentUserId);
                  }
                  Chat gChat = chat != null ? chat : new Chat(chatId, gName, "Joined P2P " + gType, Timestamp.now(), "avatar_2", 1, true, true, gType, parts);
                  gChat.setName(gName);
                  gChat.setType(gType);
                  gChat.setGroup(true);
                  gChat.setParticipants(parts);
                  gChat.setGroupExpiresAt(gExp);
                  String adminId = json.optString("adminId", null);
                  if (adminId != null && !adminId.isEmpty()) gChat.setAdminId(adminId);
                  String adminIdsStr = json.optString("adminIds", null);
                  if (adminIdsStr != null && !adminIdsStr.isEmpty()) {
                     List<String> aList = new ArrayList<>();
                     for (String aId : adminIdsStr.split(",")) {
                        if (!aId.trim().isEmpty()) aList.add(aId.trim());
                     }
                     gChat.setAdminIds(aList);
                  }
                  String desc = json.optString("description", null);
                  if (desc != null) gChat.setDescription(desc);
                  String avatar = json.optString("avatar", null);
                  if (avatar != null && !avatar.isEmpty()) gChat.setAvatar(avatar);
                  if (json.has("isPublic")) gChat.setPublic(json.optBoolean("isPublic", false));
                  String handle = json.optString("handle", null);
                  if (handle != null && !handle.isEmpty()) gChat.setHandle(handle);
                  EncryptedChatDatabase.getInstance(this.context).saveOrUpdateChat(gChat);
               } else if (chat != null) {
                  if ("vanish".equals(setting)) {
                     chat.setVanishMode(json.optString("vanishMode", "off"));
                     chat.setVanishTimer(json.optLong("vanishTimer", 0L));
                     EncryptedChatDatabase.getInstance(this.context).saveOrUpdateChat(chat);
                  } else if ("pin_msg".equals(setting)) {
                     EncryptedChatDatabase.getInstance(this.context).updateChatPinnedMessage(chatId, json.optString("msgId", (String)null), json.optString("msgText", (String)null));
                  }
               }
            } else if ("group_event".equals(type)) {
               EncryptedChatDatabase.GroupEvent evt = new EncryptedChatDatabase.GroupEvent(
                     json.optString("eventId", UUID.randomUUID().toString()),
                     json.optString("groupId", ""),
                     peerId,
                     json.optString("creatorName", "@Anon"),
                     json.optString("title", "Group Event"),
                     json.optString("description", ""),
                     json.optString("location", ""),
                     json.optLong("startTime", System.currentTimeMillis()),
                     json.optLong("endTime", System.currentTimeMillis() + 7200000L)
               );
               EncryptedChatDatabase.getInstance(this.context).saveGroupEvent(evt);
            } else if ("event_rsvp".equals(type)) {
               EncryptedChatDatabase.getInstance(this.context).updateEventRsvp(
                     json.optString("eventId", ""),
                     json.optString("status", "GOING"),
                     json.optInt("rsvpCount", 1)
               );
            } else if ("subgroup_created".equals(json.optString("action"))) {
               EncryptedChatDatabase.GroupSubgroup sg = new EncryptedChatDatabase.GroupSubgroup(
                     json.optString("subgroupId", UUID.randomUUID().toString()),
                     json.optString("groupId", ""),
                     json.optString("title", "Topic"),
                     json.optString("description", ""),
                     json.optString("inviteKey", (String)null),
                     "MEMBER",
                     json.optBoolean("isSecret", false)
               );
               EncryptedChatDatabase.getInstance(this.context).saveSubgroup(sg);
            } else if ("veilboard_repost".equals(json.optString("action"))) {
               EncryptedChatDatabase.VeilBoardRepost rep = new EncryptedChatDatabase.VeilBoardRepost(
                     json.optString("repostId", UUID.randomUUID().toString()),
                     json.optString("originalPostId", ""),
                     peerId,
                     json.optString("authorName", "anonymous"),
                     peerId,
                     json.optString("reposterName", "Anonymous"),
                     json.optString("quoteText", "")
               );
               EncryptedChatDatabase.getInstance(this.context).saveRepost(rep);
            } else if ("veilboard_tip".equals(type)) {
               int tippedCoins = Math.max(1, json.optInt("coins", 5));
               VeilFeatureManager.getInstance(this.context).addVeilCoins(tippedCoins);
               VeilFeatureManager.getInstance(this.context).addKarma(2);
            } else if ("gossip_post_delete".equals(type)) {
               String delPostId = json.optString("postId", "");
               if (!delPostId.isEmpty()) {
                  EncryptedChatDatabase.getInstance(this.context).deletePost(delPostId);
                  com.m3.veil.manager.VeilBoardController.getInstance(this.context).preloadFeedCache();
               }
            } else if ("jury_vote".equals(type)) {
               com.m3.veil.manager.VeilBoardController.getInstance(this.context).onInboundJuryVote(
                     json.optString("postId", ""),
                     json.optBoolean("voteRemove", true),
                     json.optString("jurorId", peerId)
               );
            } else if (!"gossip_post".equals(type) && !"gossip_sync".equals(type)) {
               if ("call_signal".equals(type)) {
                  String action = json.optString("action");
                  String callId = json.optString("callId", UUID.randomUUID().toString());
                  String callerName = json.optString("callerName", "@Anonymous");
                  boolean isVideo = json.optBoolean("isVideo", false);
                  String callTypeStr = isVideo ? "video" : "audio";
                  if ("invite".equals(action)) {
                     this.onCallInviteReceived(peerId, callId, callerName, callTypeStr);
                  } else if ("ringing".equals(action)) {
                     this.onCallRingingReceived(peerId, callId);
                  } else if ("accept".equals(action)) {
                     this.onCallAcceptReceived(peerId, callId);
                  } else if ("reject".equals(action)) {
                     this.onCallRejectReceived(peerId, callId, json.optString("reason", "declined"));
                  } else if ("end".equals(action)) {
                     this.onCallEndReceived(peerId, callId);
                  }
               } else {
                  P2PFileTransferHelper.handleIncomingPayload(this.context, payload, (transferId, fileName, mediaType, caption, file) -> {
                     String chatId = getChatId(this.currentUserId, peerId);
                     Message existing = EncryptedChatDatabase.getInstance(this.context).getMessageById(transferId);
                     String messageContent = caption != null && !caption.trim().isEmpty() ? caption.trim() : fileName;
                     Message fileMsg = existing != null ? existing : new Message(transferId, peerId, this.currentUserId, messageContent, Timestamp.now(), mediaType);
                     fileMsg.setLocalFilePath(file.getAbsolutePath());
                     fileMsg.setDelivered(true);
                     fileMsg.setRead(false);

                     try {
                        String ext = "";
                        if ("image".equals(mediaType)) {
                           ext = ".jpg";
                        } else if ("video".equals(mediaType)) {
                           ext = ".mp4";
                        } else if ("voice".equals(mediaType)) {
                           ext = ".m4a";
                        } else if ("document".equals(mediaType)) {
                           int dot = fileName.lastIndexOf(".");
                           if (dot != -1) {
                              ext = fileName.substring(dot);
                           }
                        }

                        File standardDec = new File(this.context.getExternalCacheDir(), "dec_" + transferId + ext);
                        if (!standardDec.exists()) {
                           copyFile(file, standardDec);
                        }

                        fileMsg.setLocalFilePath(standardDec.getAbsolutePath());
                     } catch (Exception var17) {
                     }

                     if ("image".equals(mediaType)) {
                        try {
                           Bitmap bitmap = BitmapFactory.decodeFile(fileMsg.getLocalFilePath());
                           if (bitmap != null) {
                              Bitmap thumbBitmap = Bitmap.createScaledBitmap(bitmap, 40, 40, true);
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();
                              thumbBitmap.compress(CompressFormat.JPEG, 40, baos);
                              fileMsg.setThumbnail(Base64.encodeToString(baos.toByteArray(), 0));
                           }
                        } catch (Exception var16) {
                        }
                     } else if ("video".equals(mediaType)) {
                        try {
                           MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                           retriever.setDataSource(fileMsg.getLocalFilePath());
                           Bitmap frame = retriever.getFrameAtTime();
                           if (frame != null) {
                              Bitmap thumb = Bitmap.createScaledBitmap(frame, 60, 60, true);
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();
                              thumb.compress(CompressFormat.JPEG, 40, baos);
                              fileMsg.setThumbnail(Base64.encodeToString(baos.toByteArray(), 0));
                           }

                           retriever.release();
                        } catch (Exception var15) {
                        }
                     }

                     EncryptedChatDatabase.getInstance(this.context).saveMessage(chatId, fileMsg);
                     this.sendReceipt(peerId, transferId, true, false);
                     this.mainHandler.post(() -> {
                        for(P2PEventListener l : WebRtcManager.this.listeners) {
                           l.onMessageReceived(peerId, fileMsg);
                        }

                     });
                  });
               }
            } else {
               JSONArray postsArr = json.optJSONArray("posts");
               if (postsArr != null) {
                  for(int i = 0; i < postsArr.length(); ++i) {
                     JSONObject po = postsArr.getJSONObject(i);
                     Post post = this.jsonToPost(po);
                     if (post != null) {
                        EncryptedChatDatabase.getInstance(this.context).saveOrUpdatePost(post);
                     }
                  }
               }
            }
         } else {
            Log.d("WebRtcManager", "Peer " + peerId + " reported peer_leaving -> closing P2P DataChannel");
            this.closePeerConnection(peerId);
            this.mainHandler.post(() -> {
               for(P2PEventListener l : WebRtcManager.this.listeners) {
                  l.onPeerDisconnected(peerId);
               }

            });
         }
      } catch (Exception e) {
         Log.e("WebRtcManager", "Error parsing inbound DataChannel payload", e);
      }

   }

   public void sendDirectMessage(String peerId, Message message) {
      this.sendTextMessage(peerId, message);
   }

   public void sendTextMessage(String peerId, Message message) {
      this.sendChatMessageInternal(getChatId(this.currentUserId, peerId), peerId, message, false, (String)null, "direct");
   }

   public void sendChatMessageToChat(Chat chat, String peerId, Message message) {
      if (chat != null && chat.getVanishTimer() > 0L) {
         long timer = chat.getVanishTimer();
         String mode = chat.getVanishMode() != null ? chat.getVanishMode() : "after_send";
         message.setVanishTimer(timer);
         message.setVanishMode(mode);
         if ("after_send".equals(mode)) {
            if (message.getExpiresAt() == null || message.getExpiresAt().toDate().getTime() == 0L) {
               message.setExpiresAt(new Timestamp(new Date(System.currentTimeMillis() + timer)));
            }
         } else if ("after_read".equals(mode)) {
            message.setExpiresAt((Timestamp)null);
         }
      }

      if (chat != null && chat.isGroup() && chat.getParticipants() != null) {
         message.setPending(!this.isNetworkAvailable());
         EncryptedChatDatabase.getInstance(this.context).saveMessage(chat.getId(), message);

         for(String memberId : chat.getParticipants()) {
            if (memberId != null && !memberId.equals(this.currentUserId)) {
               this.dispatchChatPacketToPeer(chat.getId(), memberId, message, true, chat.getName(), chat.getType());
            }
         }
      } else {
         String targetChatId = chat != null ? chat.getId() : getChatId(this.currentUserId, peerId);
         this.sendChatMessageInternal(targetChatId, peerId, message, false, chat != null ? chat.getName() : null, "direct");
      }

   }

   private void sendChatMessageInternal(String chatId, String peerId, Message message, boolean isGroup, String chatName, String chatType) {
      message.setPending(!this.isNetworkAvailable());
      EncryptedChatDatabase.getInstance(this.context).saveMessage(chatId, message);
      EncryptedChatDatabase.getInstance(this.context).incrementChatTrustScore(chatId, 2);
      VeilFeatureManager.getInstance(this.context).addXp(5);
      this.dispatchChatPacketToPeer(chatId, peerId, message, isGroup, chatName, chatType);
   }

   private void dispatchChatPacketToPeer(String chatId, String peerId, Message message, boolean isGroup, String chatName, String chatType) {
      if (peerId != null && !peerId.isEmpty()) {
         boolean sentOverP2P = false;
         JSONObject json = new JSONObject();

         try {
            json.put("type", "chat");
            JSONObject p = new JSONObject();
            p.put("id", message.getId());
            p.put("chatId", chatId);
            p.put("senderId", this.currentUserId);
            p.put("senderName", message.getSenderName() != null ? message.getSenderName() : VeilFeatureManager.getInstance(this.context).getActivePersona().getDisplayName());
            p.put("text", message.getText());
            p.put("msgType", message.getType());
            p.put("timestamp", message.getTimestamp() != null ? message.getTimestamp().toDate().getTime() : System.currentTimeMillis());
            p.put("replyToId", message.getReplyToMessageId());
            p.put("replySenderName", message.getReplySenderName());
            p.put("blink", message.isBlink());
            p.put("voiceFilter", message.getVoiceFilter());
            p.put("pollJson", message.getPollJson());
            if (message.getThumbnail() != null) {
               p.put("thumbnail", message.getThumbnail());
            }

            if (message.getMediaUrl() != null) {
               p.put("mediaUrl", message.getMediaUrl());
            }

            if (message.getLocalFilePath() != null) {
               p.put("localFilePath", message.getLocalFilePath());
            }

            p.put("expiresAt", message.getExpiresAt() != null ? message.getExpiresAt().toDate().getTime() : 0L);
            p.put("vanishTimer", message.getVanishTimer());
            p.put("vanishMode", message.getVanishMode());
            p.put("isGroup", isGroup);
            if (chatName != null) {
               p.put("chatName", chatName);
            }

            if (chatType != null) {
               p.put("chatType", chatType);
            }

            json.put("payload", p);
            VeilPacket packet = new VeilPacket((byte)1, (short)257, json.toString());
            sentOverP2P = this.sendPacket(peerId, packet);
            if (sentOverP2P) {
               EncryptedChatDatabase.getInstance(this.context).updateMessageStatus(message.getId(), false, false);
            }
         } catch (Exception e) {
            Log.e("WebRtcManager", "Error sending P2P chat packet", e);
         }

         if (!sentOverP2P) {
            VeilMeshDaemonService.getInstance(this.context).enqueueOutgoingMessage((Chat)null, peerId, message);
         }

      }
   }

   public void sendFileMessage(String peerId, Message message, File file, P2PFileTransferHelper.FileTransferCallback callback) {
      String chatId = getChatId(this.currentUserId, peerId);
      Chat chat = EncryptedChatDatabase.getInstance(this.context).getChat(chatId);
      if (chat != null && chat.getVanishTimer() > 0L) {
         long timer = chat.getVanishTimer();
         String mode = chat.getVanishMode() != null ? chat.getVanishMode() : "after_send";
         message.setVanishTimer(timer);
         message.setVanishMode(mode);
         if ("after_send".equals(mode)) {
            if (message.getExpiresAt() == null || message.getExpiresAt().toDate().getTime() == 0L) {
               message.setExpiresAt(new Timestamp(new Date(System.currentTimeMillis() + timer)));
            }
         } else if ("after_read".equals(mode)) {
            message.setExpiresAt((Timestamp)null);
         }
      }

      if (file != null && file.exists()) {
         message.setMediaSize(file.length());
         message.setLocalFilePath(file.getAbsolutePath());
      }

      message.setPending(!this.isNetworkAvailable());
      EncryptedChatDatabase.getInstance(this.context).saveMessage(chatId, message);
      VeilFeatureManager.getInstance(this.context).addXp(10);
      this.dispatchChatPacketToPeer(chatId, peerId, message, false, chat != null ? chat.getName() : null, "direct");
      if (callback != null) {
         callback.onComplete(message.getId());
      }

   }

   public void requestMediaDownload(String peerId, String messageId, P2PFileTransferHelper.DownloadProgressListener callback) {
      if (messageId != null) {
         DataChannel dc = peerId != null ? (DataChannel)this.dataChannels.get(peerId) : null;
         if (callback != null) {
            P2PFileTransferHelper.registerDownloadListener(messageId, callback);
         }

         if (dc != null && dc.state() == State.OPEN) {
            P2PFileTransferHelper.requestFile(dc, messageId);
         } else {
            if (peerId != null) {
               this.initiateConnection(peerId);
            }

            if (callback != null) {
               this.mainHandler.post(() -> callback.onError(messageId, "Connecting to peer..."));
            }
         }

      }
   }

   public void cancelMediaDownload(String peerId, String messageId) {
      if (messageId != null) {
         DataChannel dc = peerId != null ? (DataChannel)this.dataChannels.get(peerId) : null;
         P2PFileTransferHelper.unregisterDownloadListener(messageId);
         if (dc != null && dc.state() == State.OPEN) {
            P2PFileTransferHelper.cancelFileTransfer(dc, messageId);
         }

      }
   }

   private void handleFileRequestFromPeer(String transferId) {
      if (transferId != null) {
         Message msg = EncryptedChatDatabase.getInstance(this.context).getMessageById(transferId);
         if (msg != null) {
            File file = null;
            if (msg.getLocalFilePath() != null) {
               file = new File(msg.getLocalFilePath());
            }

            if (file == null || !file.exists()) {
               String ext = "";
               if ("image".equals(msg.getType())) {
                  ext = ".jpg";
               } else if ("video".equals(msg.getType())) {
                  ext = ".mp4";
               } else if ("voice".equals(msg.getType())) {
                  ext = ".m4a";
               } else if ("document".equals(msg.getType())) {
                  String text = msg.getText();
                  if (text != null && text.contains(".")) {
                     int pipe = text.indexOf("|");
                     String fn = pipe != -1 ? text.substring(0, pipe) : text;
                     int dot = fn.lastIndexOf(".");
                     if (dot != -1) {
                        ext = fn.substring(dot);
                     }
                  }
               }

               File cached = new File(this.context.getExternalCacheDir(), "dec_" + transferId + ext);
               if (cached.exists()) {
                  file = cached;
               }
            }

            if (file != null && file.exists()) {
               String peerId = msg.getReceiverId();
               DataChannel dc = peerId != null ? (DataChannel)this.dataChannels.get(peerId) : null;
               if (dc == null || dc.state() != State.OPEN) {
                  for(DataChannel chan : this.dataChannels.values()) {
                     if (chan != null && chan.state() == State.OPEN) {
                        dc = chan;
                        break;
                     }
                  }
               }

               if (dc != null && dc.state() == State.OPEN) {
                  P2PFileTransferHelper.sendFileOverDataChannel(dc, transferId, file, msg.getType(), msg.getText(), msg.isBlink(), msg.getThumbnail(), msg.getVoiceFilter(), (P2PFileTransferHelper.FileTransferCallback)null);
               }
            }

         }
      }
   }

   public void sendReaction(String peerId, String chatId, String messageId, String userId, String emoji) {
      EncryptedChatDatabase.getInstance(this.context).updateMessageReaction(chatId, messageId, userId, emoji);
      VeilFeatureManager.getInstance(this.context).addKarma(1);
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "reaction");
         json.put("chatId", chatId);
         json.put("messageId", messageId);
         json.put("userId", userId);
         json.put("emoji", emoji != null ? emoji : "");
         return json;
      });
   }

   public void sendEditMessage(String peerId, String chatId, String messageId, String newText) {
      EncryptedChatDatabase.getInstance(this.context).updateMessageText(chatId, messageId, newText);
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "edit_msg");
         json.put("chatId", chatId);
         json.put("messageId", messageId);
         json.put("newText", newText);
         return json;
      });
   }

   public void sendDeleteMessageForEveryone(String peerId, String chatId, String messageId) {
      EncryptedChatDatabase.getInstance(this.context).markMessageDeletedForEveryone(chatId, messageId);
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "delete_msg");
         json.put("chatId", chatId);
         json.put("messageId", messageId);
         return json;
      });
   }

   public void sendPollVote(String peerId, String chatId, String messageId, String updatedPollJson) {
      EncryptedChatDatabase.getInstance(this.context).updateMessagePollJson(chatId, messageId, updatedPollJson);
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "poll_vote");
         json.put("chatId", chatId);
         json.put("messageId", messageId);
         json.put("pollJson", updatedPollJson);
         return json;
      });
   }

   public void sendBlinkOpenedReceipt(String peerId, String chatId, String messageId) {
      EncryptedChatDatabase.getInstance(this.context).markBlinkMessageViewed(chatId, messageId);
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "receipt");
         json.put("chatId", chatId);
         json.put("messageId", messageId);
         json.put("delivered", true);
         json.put("read", true);
         json.put("blinkOpened", true);
         return json;
      });
   }

   public void sendChatSettingPacket(String peerId, String chatId, long vanishTimer, String vanishMode, String pinnedMsgId, String pinnedMsgText) {
      if (pinnedMsgId == null && pinnedMsgText == null) {
         this.sendVanishSetting(peerId, chatId, vanishMode, vanishTimer);
      } else {
         this.sendPinMessageSetting(peerId, chatId, pinnedMsgId, pinnedMsgText);
      }

   }

   public void sendCallSignalPacket(String peerId, String action, String callType, String callerName) {
      this.sendCallSignalPacket(peerId, action, callType, callerName, UUID.randomUUID().toString());
   }

   public void sendCallSignalPacket(String peerId, String action, String callType, String callerName, String callId) {
      if ("invite".equals(action)) {
         SignalingClient.getInstance().sendCallInvite(peerId, callId, callerName, callType);
      } else if ("ringing".equals(action)) {
         SignalingClient.getInstance().sendCallRinging(peerId, callId);
      } else if ("accept".equals(action)) {
         SignalingClient.getInstance().sendCallAccept(peerId, callId);
      } else if ("reject".equals(action)) {
         SignalingClient.getInstance().sendCallReject(peerId, callId, "declined");
      } else if ("end".equals(action)) {
         SignalingClient.getInstance().sendCallEnd(peerId, callId);
      }

      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "call_signal");
         json.put("action", action);
         json.put("callId", callId);
         json.put("callerName", callerName != null ? callerName : "@Anonymous");
         json.put("isVideo", "video".equalsIgnoreCase(callType));
         return json;
      });
   }

   public void sendVanishSetting(String peerId, String chatId, String vanishMode, long vanishTimer) {
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "chat_setting");
         json.put("setting", "vanish");
         json.put("chatId", chatId);
         json.put("vanishMode", vanishMode);
         json.put("vanishTimer", vanishTimer);
         return json;
      });
   }

   public void sendPinMessageSetting(String peerId, String chatId, String msgId, String msgText) {
      EncryptedChatDatabase.getInstance(this.context).updateChatPinnedMessage(chatId, msgId, msgText);
      this.sendJsonToPeer(peerId, () -> {
         JSONObject json = new JSONObject();
         json.put("type", "chat_setting");
         json.put("setting", "pin_msg");
         json.put("chatId", chatId);
         json.put("msgId", msgId);
         json.put("msgText", msgText);
         return json;
      });
   }

   public void sendGroupInviteToPeers(Chat groupChat) {
      if (groupChat != null && groupChat.getParticipants() != null) {
         StringBuilder partSb = new StringBuilder();
         for(int i = 0; i < groupChat.getParticipants().size(); ++i) {
            partSb.append((String)groupChat.getParticipants().get(i));
            if (i < groupChat.getParticipants().size() - 1) {
               partSb.append(",");
            }
         }
         String partStr = partSb.toString();

         StringBuilder adminSb = new StringBuilder();
         List<String> admins = groupChat.getAdminIds();
         if (admins != null) {
            for (int i = 0; i < admins.size(); i++) {
               if (i > 0) adminSb.append(",");
               adminSb.append(admins.get(i));
            }
         }
         String adminStr = adminSb.toString();

         for(String peerId : groupChat.getParticipants()) {
            if (peerId != null && !peerId.equals(this.currentUserId)) {
               this.sendJsonToPeer(peerId, () -> {
                  JSONObject json = new JSONObject();
                  json.put("type", "chat_setting");
                  json.put("setting", "group_invite");
                  json.put("chatId", groupChat.getId());
                  json.put("groupName", groupChat.getName());
                  json.put("groupType", groupChat.getType() != null ? groupChat.getType() : "group");
                  json.put("groupExpiresAt", groupChat.getGroupExpiresAt());
                  json.put("participants", partStr);
                  if (groupChat.getAdminId() != null) json.put("adminId", groupChat.getAdminId());
                  if (!adminStr.isEmpty()) json.put("adminIds", adminStr);
                  if (groupChat.getDescription() != null) json.put("description", groupChat.getDescription());
                  if (groupChat.getAvatar() != null) json.put("avatar", groupChat.getAvatar());
                  json.put("isPublic", groupChat.isPublic());
                  if (groupChat.getHandle() != null) json.put("handle", groupChat.getHandle());
                  return json;
               });
            }
         }

      }
   }

   public void broadcastPostToMesh(Post post) {
      if (post != null) {
         EncryptedChatDatabase.getInstance(this.context).saveOrUpdatePost(post);

         try {
            JSONObject packet = new JSONObject();
            packet.put("type", "gossip_post");
            JSONArray arr = new JSONArray();
            arr.put(this.postToJson(post));
            packet.put("posts", arr);
            byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);

            for(DataChannel dc : this.dataChannels.values()) {
               if (dc != null && dc.state() == State.OPEN) {
                  dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
               }
            }
         } catch (Exception e) {
            Log.e("WebRtcManager", "Error broadcasting post to P2P gossip mesh", e);
         }

      }
   }

   public void broadcastPacket(VeilPacket packet) {
      if (packet != null) {
         try {
            byte[] bytes = packet.serialize();

            for(DataChannel dc : this.dataChannels.values()) {
               if (dc != null && dc.state() == State.OPEN) {
                  dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
               }
            }
         } catch (Exception e) {
            Log.w("WebRtcManager", "broadcastPacket failed: " + e.getMessage());
         }

      }
   }

   private void syncVeilBoardGossipWithPeer(String peerId, DataChannel dc) {
      if (dc != null && dc.state() == State.OPEN) {
         (new Thread(() -> {
            try {
               List<Post> localPosts = EncryptedChatDatabase.getInstance(this.context).getAllPosts();
               if (localPosts.isEmpty()) {
                  return;
               }

               JSONObject packet = new JSONObject();
               packet.put("type", "gossip_sync");
               JSONArray arr = new JSONArray();
               int count = Math.min(15, localPosts.size());

               for(int i = 0; i < count; ++i) {
                  arr.put(this.postToJson((Post)localPosts.get(i)));
               }

               packet.put("posts", arr);
               byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
               dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
            } catch (Exception var7) {
            }

         })).start();
      }
   }

   private JSONObject postToJson(Post p) throws Exception {
      JSONObject o = new JSONObject();
      o.put("id", p.getId());
      o.put("authorId", p.getAuthorId());
      o.put("authorUsername", p.getAuthorUsername());
      o.put("authorAvatar", p.getAuthorAvatar());
      o.put("content", p.getContent());
      o.put("timestamp", p.getTimestamp() != null ? p.getTimestamp().toDate().getTime() : System.currentTimeMillis());
      o.put("likesCount", p.getLikesCount());
      o.put("commentsCount", p.getCommentsCount());
      o.put("type", p.getType());
      o.put("imageUrl", p.getImageUrl());
      o.put("moodBadge", p.getMoodBadge());
      o.put("category", p.getCategory());
      o.put("pollJson", p.getPollJson());
      o.put("expiresAtMillis", p.getExpiresAtMillis());
      o.put("commentsJson", p.getCommentsJson());
      if (p.getHashtags() != null) {
         o.put("hashtags", new JSONArray(p.getHashtags()));
      }

      if (p.getLikedBy() != null) {
         o.put("likedBy", new JSONArray(p.getLikedBy()));
      }

      return o;
   }

   private Post jsonToPost(JSONObject o) {
      try {
         String id = o.getString("id");
         String authorId = o.optString("authorId", "anon");
         String authorUsername = o.optString("authorUsername", "@Anonymous");
         String authorAvatar = o.optString("authorAvatar", "avatar_1");
         String content = o.optString("content", "");
         long ts = o.optLong("timestamp", System.currentTimeMillis());
         String type = o.optString("type", "text");
         Post p = new Post(id, authorId, authorUsername, authorAvatar, content, new Timestamp(new Date(ts)), type);
         p.setLikesCount(o.optInt("likesCount", 0));
         p.setCommentsCount(o.optInt("commentsCount", 0));
         p.setImageUrl(o.optString("imageUrl", (String)null));
         p.setMoodBadge(o.optString("moodBadge", (String)null));
         p.setCategory(o.optString("category", "feed"));
         p.setPollJson(o.optString("pollJson", (String)null));
         p.setExpiresAtMillis(o.optLong("expiresAtMillis", 0L));
         p.setCommentsJson(o.optString("commentsJson", (String)null));
         JSONArray tagsArr = o.optJSONArray("hashtags");
         if (tagsArr != null) {
            List<String> tags = new ArrayList();

            for(int i = 0; i < tagsArr.length(); ++i) {
               tags.add(tagsArr.getString(i));
            }

            p.setHashtags(tags);
         }

         JSONArray likedArr = o.optJSONArray("likedBy");
         if (likedArr != null) {
            List<String> liked = new ArrayList();

            for(int i = 0; i < likedArr.length(); ++i) {
               liked.add(likedArr.getString(i));
            }

            p.setLikedBy(liked);
         }

         return p;
      } catch (Exception var15) {
         return null;
      }
   }

   private void sendJsonToPeer(String peerId, JsonBuilder builder) {
      if (peerId != null) {
         boolean sentOverP2p = false;
         JSONObject json = null;

         try {
            json = builder.build();
            if (json != null) {
               String type = json.optString("type", "");
               byte lane = (byte)(!"typing".equals(type) && !"receipt".equals(type) && !"call_signal".equals(type) ? 1 : 0);
               DataChannel dc = this.getChannelForLane(peerId, lane);
               if (dc != null && dc.state() == State.OPEN) {
                  byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                  dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
                  sentOverP2p = true;
               }
            }
         } catch (Exception var9) {
         }

         if (!sentOverP2p && json != null) {
            String type = json.optString("type", "");
            if (!"typing".equals(type) && !"call_signal".equals(type)) {
               this.depositOfflineActionEnvelope(peerId, json.toString());
            } else {
               this.initiateConnection(peerId);
            }
         }

      }
   }

   private void flushPendingMessages(String peerId, DataChannel dc) {
      if (dc != null && dc.state() == State.OPEN && peerId != null) {
         (new Thread(() -> {
            try {
               List<Message> pending = EncryptedChatDatabase.getInstance(this.context).getPendingMessages(peerId);
               if (pending == null || pending.isEmpty()) {
                  return;
               }

               for(Message msg : pending) {
                  if (dc.state() != State.OPEN) {
                     break;
                  }

                  String type = msg.getType();
                  if (!"text".equals(type) && !"location".equals(type) && !"contact".equals(type) && !"poll".equals(type)) {
                     if (msg.getLocalFilePath() != null) {
                        File file = new File(msg.getLocalFilePath());
                        if (file.exists()) {
                           P2PFileTransferHelper.sendFileOverDataChannel(dc, msg.getId(), file, msg.getType(), msg.getText(), (P2PFileTransferHelper.FileTransferCallback)null);
                        }
                     }
                  } else {
                     JSONObject json = new JSONObject();
                     json.put("type", "chat");
                     JSONObject p = new JSONObject();
                     p.put("id", msg.getId());
                     p.put("chatId", getChatId(this.currentUserId, peerId));
                     p.put("senderId", this.currentUserId);
                     p.put("senderName", msg.getSenderName() != null ? msg.getSenderName() : VeilFeatureManager.getInstance(this.context).getActivePersona().getDisplayName());
                     p.put("text", msg.getText());
                     p.put("msgType", msg.getType());
                     p.put("timestamp", msg.getTimestamp() != null ? msg.getTimestamp().toDate().getTime() : System.currentTimeMillis());
                     p.put("replyToId", msg.getReplyToMessageId());
                     p.put("replySenderName", msg.getReplySenderName());
                     p.put("blink", msg.isBlink());
                     p.put("voiceFilter", msg.getVoiceFilter());
                     p.put("pollJson", msg.getPollJson());
                     p.put("expiresAt", msg.getExpiresAt() != null ? msg.getExpiresAt().toDate().getTime() : 0L);
                     json.put("payload", p);
                     byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                     dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
                  }

                  try {
                     Thread.sleep(80L);
                  } catch (InterruptedException var10) {
                  }
               }
            } catch (Exception e) {
               Log.e("WebRtcManager", "Error flushing pending messages for " + peerId, e);
            }

         })).start();
      }
   }

   private static void copyFile(File src, File dst) throws IOException {
      InputStream in = new FileInputStream(src);

      try {
         OutputStream out = new FileOutputStream(dst);

         try {
            byte[] buf = new byte[8192];

            int len;
            while((len = in.read(buf)) > 0) {
               out.write(buf, 0, len);
            }
         } catch (Throwable var8) {
            try {
               out.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }

            throw var8;
         }

         out.close();
      } catch (Throwable var9) {
         try {
            in.close();
         } catch (Throwable var6) {
            var9.addSuppressed(var6);
         }

         throw var9;
      }

      in.close();
   }

   public void sendReceipt(String peerId, String messageId, boolean delivered, boolean read) {
      if (peerId != null && messageId != null) {
         JSONObject json = new JSONObject();

         try {
            json.put("type", "receipt");
            json.put("messageId", messageId);
            json.put("delivered", delivered || read);
            json.put("read", read);
            json.put("readAt", System.currentTimeMillis());
         } catch (Exception var11) {
         }

         short packetType = (short)(read ? 5 : 4);
         VeilPacket packet = new VeilPacket((byte)0, packetType, json.toString());
         boolean sentViaWebRtc = this.sendPacket(peerId, packet);
         if (!sentViaWebRtc) {
            DataChannel dc = this.getChannelForLane(peerId, (byte)0);
            if (dc != null && dc.state() == State.OPEN) {
               try {
                  byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                  dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
                  sentViaWebRtc = true;
               } catch (Exception var10) {
               }
            }
         }

         if (!sentViaWebRtc) {
            try {
               SignalingClient.getInstance().sendP2PSignal(peerId, "p2p_action", json);
            } catch (Throwable ignored) {}
         }

         // Dual-channel receipt delivery: update Firestore message document in groups & chats
         try {
            String cId = getChatId(this.currentUserId, peerId);
            java.util.Map<String, Object> upd = new java.util.HashMap<>();
            if (delivered || read) upd.put("delivered", true);
            if (read) {
               upd.put("read", true);
               upd.put("readAt", System.currentTimeMillis());
            }
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("groups").document(cId)
                    .collection("messages").document(messageId)
                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("chats").document(cId)
                    .collection("messages").document(messageId)
                    .set(upd, com.google.firebase.firestore.SetOptions.merge());
         } catch (Throwable ignored) {}

      }
   }

   public void sendChatReadReceipt(String peerId, String chatId) {
      if (peerId != null) {
         String resolvedChatId = (chatId != null && !chatId.isEmpty()) ? chatId : getChatId(this.currentUserId, peerId);
         long now = System.currentTimeMillis();
         this.sendJsonToPeer(peerId, () -> {
            JSONObject json = new JSONObject();
            json.put("type", "chat_read");
            json.put("chatId", resolvedChatId);
            json.put("readAt", now);
            return json;
         });
         try {
            JSONObject sig = new JSONObject();
            sig.put("type", "chat_read");
            sig.put("chatId", resolvedChatId);
            sig.put("readAt", now);
            SignalingClient.getInstance().sendP2PSignal(peerId, "p2p_action", sig);
         } catch (Throwable ignored) {}
         try {
            com.google.firebase.firestore.FirebaseFirestore fs = com.google.firebase.firestore.FirebaseFirestore.getInstance();
            java.util.Map<String, Object> ack = new java.util.HashMap<>();
            ack.put("delivered", true);
            ack.put("read", true);
            ack.put("readAt", now);
            fs.collection("groups").document(resolvedChatId).collection("messages")
                    .whereEqualTo("senderId", peerId)
                    .get()
                    .addOnSuccessListener(snaps -> {
                       if (snaps != null) {
                          for (com.google.firebase.firestore.DocumentSnapshot d : snaps.getDocuments()) {
                             Boolean r = d.getBoolean("read");
                             if (!Boolean.TRUE.equals(r)) {
                                d.getReference().set(ack, com.google.firebase.firestore.SetOptions.merge());
                             }
                          }
                       }
                    });
            fs.collection("chats").document(resolvedChatId).collection("messages")
                    .whereEqualTo("senderId", peerId)
                    .get()
                    .addOnSuccessListener(snaps -> {
                       if (snaps != null) {
                          for (com.google.firebase.firestore.DocumentSnapshot d : snaps.getDocuments()) {
                             Boolean r = d.getBoolean("read");
                             if (!Boolean.TRUE.equals(r)) {
                                d.getReference().set(ack, com.google.firebase.firestore.SetOptions.merge());
                             }
                          }
                       }
                    });
         } catch (Throwable ignored) {}
      }
   }

   public void sendStatusViewReceipt(String peerId, String statusId, String myName) {
      if (peerId != null && statusId != null) {
         this.sendJsonToPeer(peerId, () -> {
            JSONObject json = new JSONObject();
            json.put("type", "status_viewed");
            json.put("statusId", statusId);
            json.put("viewerId", this.currentUserId);
            json.put("viewerName", myName);
            json.put("viewedAt", System.currentTimeMillis());
            return json;
         });
      }
   }

   public void broadcastStatus(String statusId, String contentText, String mediaUri, String bgColor, int fontStyle) {
      if (statusId != null) {
         try {
            JSONObject json = new JSONObject();
            json.put("type", "status_post");
            json.put("id", statusId);
            json.put("authorId", this.currentUserId);
            json.put("authorName", VeilFeatureManager.getInstance(this.context).getActivePersona().getDisplayName());
            json.put("contentText", contentText != null ? contentText : "");
            json.put("mediaUri", mediaUri != null ? mediaUri : "");
            json.put("bgColorHex", bgColor != null ? bgColor : "#008069");
            json.put("fontStyleIndex", fontStyle);
            json.put("timestamp", System.currentTimeMillis());
            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            VeilPacket packet = new VeilPacket((byte)3, (short)775, payload);

            for(String peerId : this.peerHubs.keySet()) {
               this.sendPacket(peerId, packet);
            }
         } catch (Exception e) {
            Log.w("WebRtcManager", "broadcastStatus failed: " + e.getMessage());
         }

      }
   }

   public void broadcastStatus(byte[] manifestBytes) {
      if (manifestBytes != null && manifestBytes.length != 0) {
         try {
            VeilPacket packet = new VeilPacket((byte)3, (short)775, manifestBytes);

            for(String peerId : this.peerHubs.keySet()) {
               this.sendPacket(peerId, packet);
            }
         } catch (Exception e) {
            Log.w("WebRtcManager", "broadcastStatus bytes failed: " + e.getMessage());
         }

      }
   }

   public boolean sendGossipPacket(String peerId, byte[] payload) {
      if (peerId != null && payload != null) {
         VeilPacket packet = new VeilPacket((byte)3, (short)775, payload);
         return this.sendPacket(peerId, packet);
      } else {
         return false;
      }
   }

   public void sendTyping(String peerId, boolean isTyping) {
      if (!VeilFeatureManager.getInstance(this.context).isStealthTypingEnabled()) {
         VeilPacket packet = new VeilPacket((byte)0, (short)3, new byte[]{(byte)(isTyping ? 1 : 0)});
         if (!this.sendPacket(peerId, packet)) {
            DataChannel dc = this.getChannelForLane(peerId, (byte)0);
            if (dc != null && dc.state() == State.OPEN) {
               try {
                  JSONObject json = new JSONObject();
                  json.put("type", "typing");
                  json.put("isTyping", isTyping);
                  byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                  dc.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
               } catch (Exception var7) {
               }
            }
         }

      }
   }

   public void requestVoipAudioFocus() {
      try {
         AudioManager am = (AudioManager)this.context.getSystemService("audio");
         if (am == null) {
            return;
         }

         if (VERSION.SDK_INT >= 26) {
            AudioAttributes playbackAttributes = (new AudioAttributes.Builder()).setUsage(2).setContentType(1).build();
            AudioFocusRequest request = (new AudioFocusRequest.Builder(2)).setAudioAttributes(playbackAttributes).setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener((focusChange) -> {
            }).build();
            this.audioFocusRequest = request;
            am.requestAudioFocus(request);
         } else {
            am.requestAudioFocus((AudioManager.OnAudioFocusChangeListener)null, 0, 2);
         }

         am.setMode(3);
      } catch (Exception e) {
         Log.w("WebRtcManager", "Failed to request VoIP audio focus: " + e.getMessage());
      }

   }

   public void abandonVoipAudioFocus() {
      try {
         AudioManager am = (AudioManager)this.context.getSystemService("audio");
         if (am == null) {
            return;
         }

         if (VERSION.SDK_INT >= 26 && this.audioFocusRequest instanceof AudioFocusRequest) {
            am.abandonAudioFocusRequest((AudioFocusRequest)this.audioFocusRequest);
            this.audioFocusRequest = null;
         } else {
            am.abandonAudioFocus((AudioManager.OnAudioFocusChangeListener)null);
         }

         am.setMicrophoneMute(false);
         am.setSpeakerphoneOn(false);
         am.setMode(0);
      } catch (Exception e) {
         Log.w("WebRtcManager", "Failed to abandon VoIP audio focus: " + e.getMessage());
      }

   }

   public synchronized void stopCallAudioHardware(String peerId) {
      Log.d("WebRtcManager", "stopCallAudioHardware executing for peer: " + peerId);
      if (this.localAudioTrack != null) {
         try {
            this.localAudioTrack.setEnabled(false);
            this.localAudioTrack.dispose();
         } catch (Exception var10) {
         }

         this.localAudioTrack = null;
      }

      if (this.localAudioSource != null) {
         try {
            this.localAudioSource.dispose();
         } catch (Exception var9) {
         }

         this.localAudioSource = null;
      }

      if (peerId != null) {
         PeerConnection pc = (PeerConnection)this.peerConnections.remove(peerId);
         if (pc != null) {
            try {
               for(RtpSender sender : pc.getSenders()) {
                  if (sender != null && sender.track() != null) {
                     try {
                        sender.track().setEnabled(false);
                     } catch (Exception var8) {
                     }
                  }
               }

               for(RtpReceiver receiver : pc.getReceivers()) {
                  if (receiver != null && receiver.track() != null) {
                     try {
                        receiver.track().setEnabled(false);
                     } catch (Exception var7) {
                     }
                  }
               }

               pc.close();
               pc.dispose();
            } catch (Exception var11) {
            }
         }

         DataChannel dc = (DataChannel)this.dataChannels.remove(peerId);
         if (dc != null) {
            try {
               dc.unregisterObserver();
               dc.close();
            } catch (Exception var6) {
            }
         }
      }

      this.abandonVoipAudioFocus();
   }

   public synchronized void ensureLocalAudioTrack() {
      if (this.localAudioTrack == null && this.peerConnectionFactory != null) {
         try {
            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            this.localAudioSource = this.peerConnectionFactory.createAudioSource(audioConstraints);
            this.localAudioTrack = this.peerConnectionFactory.createAudioTrack("ARDAMSa0", this.localAudioSource);
            this.localAudioTrack.setEnabled(true);
         } catch (Exception var2) {
         }
      }

   }

   public void startAudioCall(String peerId) {
      this.startCall(peerId, "audio", UUID.randomUUID().toString());
   }

   public void startCall(String peerId, boolean isVideo) {
      this.startCall(peerId, isVideo ? "video" : "audio", UUID.randomUUID().toString());
   }

   public void startCall(String peerId, String callType, String callId) {
      if (peerId != null) {
         this.activeCallId = callId;
         this.activeCallPeerId = peerId;
         boolean isVideo = "video".equalsIgnoreCase(callType);
         this.requestVoipAudioFocus();
         PeerConnection pc = this.getOrCreatePeerConnection(peerId, true);
         this.ensureLocalAudioTrack();
         if (this.localAudioTrack != null) {
            try {
               pc.addTrack(this.localAudioTrack, Collections.singletonList("ARDAMS"));
            } catch (Exception var7) {
            }
         }

         String callerName = VeilFeatureManager.getInstance(this.context).getActivePersona().getDisplayName();
         SignalingClient.getInstance().sendCallInvite(peerId, callId, callerName, callType);
         this.sendJsonToPeer(peerId, () -> {
            JSONObject json = new JSONObject();
            json.put("type", "call_signal");
            json.put("action", "invite");
            json.put("callId", callId);
            json.put("callerName", callerName);
            json.put("isVideo", isVideo);
            return json;
         });
         this.initiateConnection(peerId);
      }
   }

   public void acceptCall(String peerId) {
      this.acceptCall(peerId, UUID.randomUUID().toString(), false);
   }

   public void acceptCall(String peerId, String callId) {
      this.acceptCall(peerId, callId, false);
   }

   public void acceptCall(String peerId, String callId, boolean isVideo) {
      if (peerId != null) {
         this.activeCallId = callId;
         this.activeCallPeerId = peerId;
         this.dismissIncomingCallNotification();
         this.requestVoipAudioFocus();
         PeerConnection pc = this.getOrCreatePeerConnection(peerId, false);
         this.ensureLocalAudioTrack();
         if (this.localAudioTrack != null) {
            try {
               pc.addTrack(this.localAudioTrack, Collections.singletonList("ARDAMS"));
            } catch (Exception var6) {
            }
         }

         SignalingClient.getInstance().sendCallAccept(peerId, callId);
         this.sendJsonToPeer(peerId, () -> {
            JSONObject json = new JSONObject();
            json.put("type", "call_signal");
            json.put("action", "accept");
            json.put("callId", callId);
            return json;
         });
         this.initiateConnection(peerId);
      }
   }

   public void rejectCall(String peerId, String callId, String reason) {
      if (callId != null) {
         this.terminatedCallIds.add(callId);
         if (callId.equals(this.activeCallId)) {
            this.activeCallId = null;
            this.activeCallPeerId = null;
         }
      }

      this.dismissIncomingCallNotification();
      this.stopCallAudioHardware(peerId);
      if (peerId != null && callId != null) {
         SignalingClient.getInstance().sendCallReject(peerId, callId, reason);
         this.sendJsonToPeer(peerId, () -> {
            JSONObject json = new JSONObject();
            json.put("type", "call_signal");
            json.put("action", "reject");
            json.put("callId", callId);
            json.put("reason", reason != null ? reason : "declined");
            return json;
         });
      }

   }

   public void setMicrophoneMute(boolean muted) {
      if (this.localAudioTrack != null) {
         this.localAudioTrack.setEnabled(!muted);
      }

   }

   public EglBase getRootEglBase() {
      return this.rootEglBase;
   }

   public void endCall(String peerId) {
      this.endCall(peerId, UUID.randomUUID().toString());
   }

   public void endCall(String peerId, String callId) {
      if (callId != null) {
         this.terminatedCallIds.add(callId);
         if (callId.equals(this.activeCallId)) {
            this.activeCallId = null;
            this.activeCallPeerId = null;
         }
      }

      this.dismissIncomingCallNotification();
      this.stopCallAudioHardware(peerId);
      if (peerId != null) {
         SignalingClient.getInstance().sendCallEnd(peerId, callId);
         this.sendJsonToPeer(peerId, () -> {
            JSONObject json = new JSONObject();
            json.put("type", "call_signal");
            json.put("action", "end");
            json.put("callId", callId);
            return json;
         });
      }

      this.mainHandler.post(() -> {
         for(P2PEventListener l : WebRtcManager.this.listeners) {
            l.onCallEnded(peerId, callId);
         }

      });
   }

   public void showIncomingCallNotification(String peerId, String peerName, String callType, String callId) {
      try {
         NotificationManager nm = (NotificationManager)this.context.getSystemService("notification");
         if (nm == null) {
            return;
         }

         if (VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("veil_call_channel", "Incoming Calls", 4);
            channel.setDescription("Incoming E2EE voice and video calls");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0L, 1000L, 1000L});
            channel.setLockscreenVisibility(1);
            nm.createNotificationChannel(channel);
         }

         Intent fullScreenIntent = new Intent(this.context, CallActivity.class);
         fullScreenIntent.addFlags(805306368);
         fullScreenIntent.putExtra("extra_contact_id", peerId);
         fullScreenIntent.putExtra("extra_contact_name", peerName);
         fullScreenIntent.putExtra("extra_call_type", callType);
         fullScreenIntent.putExtra("extra_call_id", callId);
         fullScreenIntent.putExtra("extra_is_incoming", true);
         PendingIntent pendingIntent = PendingIntent.getActivity(this.context, callId != null ? callId.hashCode() : 1, fullScreenIntent, 134217728 | (VERSION.SDK_INT >= 23 ? 67108864 : 0));
         NotificationCompat.Builder builder = (new NotificationCompat.Builder(this.context, "veil_call_channel")).setSmallIcon(drawable.ic_call).setContentTitle(peerName != null ? peerName : "Unknown Caller").setContentText("Incoming " + ("video".equalsIgnoreCase(callType) ? "video" : "voice") + " call...").setPriority(2).setCategory("call").setFullScreenIntent(pendingIntent, true).setContentIntent(pendingIntent).setAutoCancel(true).setOngoing(true);
         nm.notify(9991, builder.build());
      } catch (Exception e) {
         Log.e("WebRtcManager", "Failed to show incoming call notification", e);
      }

   }

   public void dismissIncomingCallNotification() {
      try {
         NotificationManager nm = (NotificationManager)this.context.getSystemService("notification");
         if (nm != null) {
            nm.cancel(9991);
         }
      } catch (Exception var2) {
      }

   }

   public void onCallInviteReceived(String fromUserId, String callId, String callerName, String callType) {
      if (fromUserId != null && !fromUserId.equals(this.currentUserId)) {
         if (callId != null && !callId.isEmpty()) {
            if (this.terminatedCallIds.contains(callId)) {
               Log.d("WebRtcManager", "Ignoring call invite for already terminated call: " + callId);
            } else if (this.processedCallInvites.contains(callId)) {
               Log.d("WebRtcManager", "Duplicate invite ignored for callId: " + callId);
            } else {
               this.mainHandler.post(() -> {
                  if (!this.terminatedCallIds.contains(callId)) {
                     if (!CallActivity.isCallActive && (this.activeCallId == null || this.activeCallId.equals(callId))) {
                        this.processedCallInvites.add(callId);
                        this.activeCallId = callId;
                        this.activeCallPeerId = fromUserId;
                        SignalingClient.getInstance().sendCallRinging(fromUserId, callId);
                        this.getOrCreatePeerConnection(fromUserId, false);

                        for(P2PEventListener l : WebRtcManager.this.listeners) {
                           l.onIncomingCall(fromUserId, callerName, callType, callId);
                        }

                        if (!CallActivity.isCallActive) {
                           Intent callIntent = new Intent(this.context, CallActivity.class);
                           callIntent.addFlags(805306368);
                           callIntent.putExtra("extra_contact_id", fromUserId);
                           callIntent.putExtra("extra_contact_name", callerName);
                           callIntent.putExtra("extra_call_type", callType);
                           callIntent.putExtra("extra_call_id", callId);
                           callIntent.putExtra("extra_is_incoming", true);

                           try {
                              this.context.startActivity(callIntent);
                           } catch (Exception e) {
                              Log.e("WebRtcManager", "Failed to launch incoming CallActivity", e);
                           }
                        }

                        this.showIncomingCallNotification(fromUserId, callerName, callType, callId);
                     } else {
                        SignalingClient.getInstance().sendCallReject(fromUserId, callId, "busy");
                     }
                  }
               });
            }
         }
      }
   }

   public void onCallRingingReceived(String fromUserId, String callId) {
      this.mainHandler.post(() -> {
         for(P2PEventListener l : WebRtcManager.this.listeners) {
            l.onCallRinging(fromUserId, callId);
         }

      });
   }

   public void onCallAcceptReceived(String fromUserId, String callId) {
      this.activeCallId = callId;
      this.activeCallPeerId = fromUserId;
      this.mainHandler.post(() -> {
         this.dismissIncomingCallNotification();

         for(P2PEventListener l : WebRtcManager.this.listeners) {
            l.onCallAccepted(fromUserId, callId);
            l.onCallEstablished(fromUserId);
         }

      });
   }

   public void onCallRejectReceived(String fromUserId, String callId, String reason) {
      if (callId != null) {
         this.terminatedCallIds.add(callId);
         if (callId.equals(this.activeCallId)) {
            this.activeCallId = null;
            this.activeCallPeerId = null;
         }
      }

      this.stopCallAudioHardware(fromUserId);
      this.mainHandler.post(() -> {
         this.dismissIncomingCallNotification();

         for(P2PEventListener l : WebRtcManager.this.listeners) {
            l.onCallRejected(fromUserId, callId, reason);
         }

      });
   }

   public void onCallEndReceived(String fromUserId, String callId) {
      if (callId != null) {
         this.terminatedCallIds.add(callId);
         if (callId.equals(this.activeCallId)) {
            this.activeCallId = null;
            this.activeCallPeerId = null;
         }
      }

      this.stopCallAudioHardware(fromUserId);
      this.mainHandler.post(() -> {
         this.dismissIncomingCallNotification();

         for(P2PEventListener l : WebRtcManager.this.listeners) {
            l.onCallEnded(fromUserId, callId);
         }

      });
   }

   public void initiateConnection(final String peerId) {
      if (peerId != null && !peerId.isEmpty() && !peerId.equals(this.currentUserId)) {
         this.scheduleTurnFallback(peerId);
         final PeerConnection pc = this.getOrCreatePeerConnection(peerId, true);
         MediaConstraints sdpConstraints = new MediaConstraints();
         sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
         sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
         pc.createOffer(new SimpleSdpObserver() {
            public void onCreateSuccess(final SessionDescription sessionDescription) {
               pc.setLocalDescription(new SimpleSdpObserver() {
                  public void onSetSuccess() {
                     SignalingClient.getInstance().sendOffer(peerId, sessionDescription.description);
                  }
               }, sessionDescription);
            }
         }, sdpConstraints);
      }
   }

   public void onOfferReceived(final String fromUserId, String sdp) {
      if (fromUserId == null || sdp == null) return;
      PeerConnection existingPc = (PeerConnection)this.peerConnections.get(fromUserId);
      if (existingPc != null && existingPc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
         boolean isPolite = this.currentUserId != null && this.currentUserId.compareTo(fromUserId) < 0;
         if (!isPolite) {
            Log.i("WebRtcManager", "Impolite peer ignoring collision offer from " + fromUserId);
            return;
         }
         Log.i("WebRtcManager", "Polite peer resetting local offer to accept remote offer from " + fromUserId);
         this.closePeerConnection(fromUserId);
         existingPc = null;
      }
      final PeerConnection pc = (existingPc != null) ? existingPc : this.getOrCreatePeerConnection(fromUserId, false);
      SessionDescription remoteDesc = new SessionDescription(Type.OFFER, sdp);
      pc.setRemoteDescription(new SimpleSdpObserver() {
         public void onSetSuccess() {
            MediaConstraints sdpConstraints = new MediaConstraints();
            sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            pc.createAnswer(new SimpleSdpObserver() {
               public void onCreateSuccess(final SessionDescription sessionDescription) {
                  pc.setLocalDescription(new SimpleSdpObserver() {
                     public void onSetSuccess() {
                        SignalingClient.getInstance().sendAnswer(fromUserId, sessionDescription.description);
                     }
                  }, sessionDescription);
               }
            }, sdpConstraints);
         }
      }, remoteDesc);
   }

   public void onAnswerReceived(String fromUserId, String sdp) {
      if (fromUserId == null || sdp == null) return;
      PeerConnection pc = (PeerConnection)this.peerConnections.get(fromUserId);
      if (pc != null && pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
         SessionDescription remoteDesc = new SessionDescription(Type.ANSWER, sdp);
         pc.setRemoteDescription(new SimpleSdpObserver(), remoteDesc);
      }
   }

   public void onIceCandidateReceived(String fromUserId, IceCandidate candidate) {
      if (fromUserId == null || candidate == null) return;
      PeerConnection pc = (PeerConnection)this.peerConnections.get(fromUserId);
      if (pc != null && pc.getRemoteDescription() != null) {
         try {
            pc.addIceCandidate(candidate);
         } catch (Exception ignored) {}
      }
   }

   public void onWakeUpReceived(String fromUserId, String chatId) {
      if (fromUserId != null && this.currentUserId != null && this.currentUserId.compareTo(fromUserId) > 0) {
         this.initiateConnection(fromUserId);
      }
   }

   public void onPeerOnline(String peerId) {
      if (peerId != null && this.currentUserId != null && this.currentUserId.compareTo(peerId) > 0) {
         this.initiateConnection(peerId);
      }
   }

   public static String getChatId(String user1, String user2) {
      if (user1 != null && user2 != null) {
         return user1.compareTo(user2) < 0 ? user1 + "_" + user2 : user2 + "_" + user1;
      } else {
         return "default_chat";
      }
   }

   public interface P2PEventListener {
      void onMessageReceived(String var1, Message var2);

      void onMessageStatusChanged(String var1, boolean var2, boolean var3);

      default void onAllMessagesRead(String chatId) {
      }

      void onPeerTyping(String var1, boolean var2);

      void onPeerConnected(String var1);

      void onPeerDisconnected(String var1);

      void onCallEstablished(String var1);

      void onCallEnded(String var1);

      default void onIncomingCall(String peerId, String peerName, boolean isVideo) {
      }

      default void onIncomingCall(String peerId, String peerName, String callType) {
      }

      default void onIncomingCall(String peerId, String peerName, String callType, String callId) {
         this.onIncomingCall(peerId, peerName, callType);
      }

      default void onCallRinging(String peerId, String callId) {
      }

      default void onCallAccepted(String peerId, String callId) {
         this.onCallEstablished(peerId);
      }

      default void onCallRejected(String peerId, String callId, String reason) {
         this.onCallEnded(peerId);
      }

      default void onCallEnded(String peerId, String callId) {
         this.onCallEnded(peerId);
      }

      default void onRemoteVideoTrackAdded(String peerId, VideoTrack videoTrack) {
      }

      default void onRemoteVideoTrackRemoved(String peerId) {
      }
   }

   public static class PeerChannelHub {
      public final String peerId;
      public volatile DataChannel ctrlChannel;
      public volatile DataChannel msgChannel;
      public volatile DataChannel mediaChannel;
      public volatile DataChannel gossipChannel;
      public volatile DataChannel swarmChannel;

      public PeerChannelHub(String peerId) {
         this.peerId = peerId;
      }

      public boolean isConnected() {
         return this.msgChannel != null && this.msgChannel.state() == State.OPEN || this.ctrlChannel != null && this.ctrlChannel.state() == State.OPEN;
      }

      public DataChannel getChannelForLane(byte lane) {
         switch (lane) {
            case 0:
               if (this.ctrlChannel != null && this.ctrlChannel.state() == State.OPEN) {
                  return this.ctrlChannel;
               }

               if (this.msgChannel != null && this.msgChannel.state() == State.OPEN) {
                  return this.msgChannel;
               }
               break;
            case 1:
               if (this.msgChannel != null && this.msgChannel.state() == State.OPEN) {
                  return this.msgChannel;
               }

               if (this.ctrlChannel != null && this.ctrlChannel.state() == State.OPEN) {
                  return this.ctrlChannel;
               }
               break;
            case 2:
               if (this.mediaChannel != null && this.mediaChannel.state() == State.OPEN) {
                  return this.mediaChannel;
               }

               if (this.msgChannel != null && this.msgChannel.state() == State.OPEN) {
                  return this.msgChannel;
               }
               break;
            case 3:
               if (this.swarmChannel != null && this.swarmChannel.state() == State.OPEN) {
                  return this.swarmChannel;
               }

               if (this.gossipChannel != null && this.gossipChannel.state() == State.OPEN) {
                  return this.gossipChannel;
               }

               if (this.ctrlChannel != null && this.ctrlChannel.state() == State.OPEN) {
                  return this.ctrlChannel;
               }
         }

         if (this.msgChannel != null && this.msgChannel.state() == State.OPEN) {
            return this.msgChannel;
         } else if (this.ctrlChannel != null && this.ctrlChannel.state() == State.OPEN) {
            return this.ctrlChannel;
         } else if (this.mediaChannel != null && this.mediaChannel.state() == State.OPEN) {
            return this.mediaChannel;
         } else if (this.swarmChannel != null && this.swarmChannel.state() == State.OPEN) {
            return this.swarmChannel;
         } else {
            return this.gossipChannel != null && this.gossipChannel.state() == State.OPEN ? this.gossipChannel : null;
         }
      }

      public void closeAll() {
         this.closeChannel(this.ctrlChannel);
         this.closeChannel(this.msgChannel);
         this.closeChannel(this.mediaChannel);
         this.closeChannel(this.gossipChannel);
         this.closeChannel(this.swarmChannel);
      }

      private void closeChannel(DataChannel dc) {
         if (dc != null) {
            try {
               dc.unregisterObserver();
               dc.close();
            } catch (Throwable var3) {
            }
         }

      }
   }

   private static class SimpleSdpObserver implements SdpObserver {
      public void onCreateSuccess(SessionDescription sessionDescription) {
      }

      public void onSetSuccess() {
      }

      public void onCreateFailure(String s) {
         Log.e("WebRtcManager", "SDP Create Failure: " + s);
      }

      public void onSetFailure(String s) {
         Log.e("WebRtcManager", "SDP Set Failure: " + s);
      }
   }

   private interface JsonBuilder {
      JSONObject build() throws Exception;
   }

   public void onIncomingMeshPacket(String peerId, byte[] payload) {
      if (payload == null || payload.length == 0) return;
      ByteBuffer buf = ByteBuffer.wrap(payload);
      if (buf.remaining() >= 24) {
         buf.mark();
         int magic = buf.getInt();
         buf.reset();
         if (magic == 1447381324) {
            VeilPacket packet = VeilPacket.deserialize(buf);
            if (packet != null) {
               handleVeilPacket(peerId, packet);
               return;
            }
         }
      }
      String text = new String(payload, StandardCharsets.UTF_8);
      processInboundPayload(peerId, text);
   }

   public Context getContext() {
      return this.context;
   }

   public synchronized void disconnectAll() {
      for (String peerId : new ArrayList<>(this.peerConnections.keySet())) {
         closePeerConnection(peerId);
      }
      this.peerConnections.clear();
      this.dataChannels.clear();
      this.peerHubs.clear();
      this.turnActivePeers.clear();
      this.peerLastSeenTime.clear();
      this.peerTypingStates.clear();
      this.initializedUserId = null;
   }

   public boolean sendMeshFrame(String peerId, byte[] frameBytes) {
      if (peerId == null || frameBytes == null || frameBytes.length == 0) {
         return false;
      }
      VeilPacket packet = new VeilPacket((byte) 3, (short) 769, frameBytes);
      if (sendPacket(peerId, packet)) {
         return true;
      }
      DataChannel dc = getChannelForLane(peerId, (byte) 3);
      if (dc == null) {
         dc = getDataChannel(peerId);
      }
      if (dc != null && dc.state() == State.OPEN) {
         try {
            return dc.send(new DataChannel.Buffer(ByteBuffer.wrap(frameBytes), true));
         } catch (Throwable ignored) {
         }
      }
      return false;
   }

   public boolean sendMeshRelayFrame(String relayPeerId, String recipientId, String packetId, byte[] encryptedPayload, byte ttlHops) {
      if (relayPeerId == null || encryptedPayload == null) {
         return false;
      }
      VeilPacket packet = new VeilPacket((byte) 3, (short) 770, encryptedPayload);
      return sendPacket(relayPeerId, packet);
   }

   public void notifyReceiptStatusChanged(final String messageId, final boolean delivered, final boolean read) {
      if (messageId == null) return;
      this.mainHandler.post(() -> {
         for (P2PEventListener l : WebRtcManager.this.listeners) {
            l.onMessageStatusChanged(messageId, delivered, read);
         }
      });
   }

}
