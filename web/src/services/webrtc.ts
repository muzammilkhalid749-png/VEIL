import { signalingService, type SignalPacket } from './signaling';
import type { Message, P2PMetrics } from '../types/chat';
import { fileTransferService, type FileTransferProgress } from './fileTransfer';
import { audioEffects } from './audioEffects';

export interface WebRtcCallbacks {
  onPeerConnected: (peerId: string) => void;
  onPeerDisconnected: (peerId: string) => void;
  onMessageReceived: (peerId: string, message: Message) => void;
  onReceiptReceived: (peerId: string, msgId: string, delivered: boolean, read: boolean) => void;
  onTypingStatus: (peerId: string, isTyping: boolean) => void;
  onRemoteTrack?: (peerId: string, stream: MediaStream) => void;
  onMetricsUpdate?: (peerId: string, metrics: P2PMetrics) => void;
  onIncomingCall?: (fromUserId: string, callId: string, callerName: string, callType: 'audio' | 'video') => void;
  onCallAccepted?: (fromUserId: string, callId: string) => void;
  onCallEnded?: (fromUserId: string) => void;
}

const DEFAULT_ICE_SERVERS: RTCIceServer[] = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
  { urls: 'stun:stun2.l.google.com:19302' },
];

export class WebRtcEngine {
  private currentUserId: string | null = null;
  private peerConnections: Map<string, RTCPeerConnection> = new Map();
  private dataChannels: Map<string, { msg: RTCDataChannel; ctrl?: RTCDataChannel; media?: RTCDataChannel }> = new Map();
  private callbacks: Partial<WebRtcCallbacks> = {};
  private pingIntervals: Map<string, number> = new Map();
  private pendingCandidates: Map<string, RTCIceCandidateInit[]> = new Map();
  private localMediaStream: MediaStream | null = null;

  public init(userId: string, callbacks: WebRtcCallbacks, secondaryPeerId?: string) {
    this.currentUserId = userId;
    this.callbacks = callbacks;
    signalingService.init(userId, secondaryPeerId);
    signalingService.registerHandler(this.handleIncomingSignal.bind(this));

    // Listen for completed P2P file transfers
    fileTransferService.registerOnFileReceived((_transferId, fileName, mediaType, fileUrl, fileSize) => {
      audioEffects.playMessageReceived();
      if (this.currentUserId) {
        const fileMsg: Message = {
          id: crypto.randomUUID(),
          chatId: `chat_files`,
          senderId: 'remote',
          senderName: 'Peer',
          text: `Sent file: ${fileName}`,
          timestamp: Date.now(),
          status: 'delivered',
          type: mediaType.startsWith('image/') ? 'image' : 'file',
          mediaUrl: fileUrl,
          fileName,
          mediaSize: fileSize,
        };
        this.callbacks.onMessageReceived?.('remote', fileMsg);
      }
    });
  }

  public async connectToPeer(peerId: string, isInitiator = true): Promise<RTCPeerConnection> {
    if (!this.currentUserId) throw new Error('WebRTC not initialized with userId');

    let pc = this.peerConnections.get(peerId);
    if (pc && pc.connectionState !== 'closed' && pc.connectionState !== 'failed') {
      return pc;
    }

    pc = new RTCPeerConnection({
      iceServers: DEFAULT_ICE_SERVERS,
      bundlePolicy: 'max-bundle',
    });

    this.peerConnections.set(peerId, pc);

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        signalingService.sendSignal(peerId, {
          type: 'candidate',
          sdp: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid || undefined,
          sdpMLineIndex: event.candidate.sdpMLineIndex ?? undefined,
        });
      }
    };

    pc.onconnectionstatechange = () => {
      console.log(`[WebRTC] Connection with ${peerId}: ${pc.connectionState}`);
      if (pc.connectionState === 'connected') {
        this.callbacks.onPeerConnected?.(peerId);
        this.startHeartbeat(peerId);
      } else if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        this.callbacks.onPeerDisconnected?.(peerId);
        this.stopHeartbeat(peerId);
      }
    };

    pc.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        this.callbacks.onRemoteTrack?.(peerId, event.streams[0]);
      }
    };

    if (isInitiator) {
      const msgChannel = pc.createDataChannel('veil-msg', { ordered: true });
      const ctrlChannel = pc.createDataChannel('veil-ctrl', { ordered: false });
      const mediaChannel = pc.createDataChannel('veil-media', { ordered: true });

      this.setupChannels(peerId, { msg: msgChannel, ctrl: ctrlChannel, media: mediaChannel });

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      await signalingService.sendSignal(peerId, {
        type: 'offer',
        sdp: offer.sdp,
      });
    } else {
      pc.ondatachannel = (event) => {
        const dc = event.channel;
        const current = this.dataChannels.get(peerId) || { msg: dc };

        if (dc.label === 'veil-ctrl') current.ctrl = dc;
        else if (dc.label === 'veil-media') current.media = dc;
        else current.msg = dc;

        this.dataChannels.set(peerId, current);
        this.bindDataChannelEvents(peerId, dc);
      };
    }

    return pc;
  }

  private setupChannels(peerId: string, channels: { msg: RTCDataChannel; ctrl?: RTCDataChannel; media?: RTCDataChannel }) {
    this.dataChannels.set(peerId, channels);
    this.bindDataChannelEvents(peerId, channels.msg);
    if (channels.ctrl) this.bindDataChannelEvents(peerId, channels.ctrl);
    if (channels.media) this.bindDataChannelEvents(peerId, channels.media);
  }

  private bindDataChannelEvents(peerId: string, dc: RTCDataChannel) {
    dc.onopen = () => {
      console.log(`[DataChannel] ${dc.label} OPEN with ${peerId}`);
      if (dc.label === 'veil-msg') {
        this.callbacks.onPeerConnected?.(peerId);
        this.sendCtrlPacket(peerId, { type: 'p2p_ping', senderId: this.currentUserId });
      }
    };

    dc.onmessage = (event) => {
      if (dc.label === 'veil-media') {
        fileTransferService.handleInboundPacket(event.data);
      } else {
        this.handleIncomingData(peerId, event.data);
      }
    };
  }

  private handleIncomingData(peerId: string, data: any) {
    try {
      let text = '';
      if (typeof data === 'string') {
        text = data;
      } else if (data instanceof ArrayBuffer) {
        text = new TextDecoder().decode(data);
      }

      if (!text) return;

      const json = JSON.parse(text);

      if (json.type === 'p2p_ping') {
        this.sendCtrlPacket(peerId, { type: 'p2p_pong', senderId: this.currentUserId });
        return;
      }

      if (json.type === 'p2p_pong') {
        return;
      }

      if (json.type === 'typing') {
        this.callbacks.onTypingStatus?.(peerId, !!json.isTyping);
        return;
      }

      if (json.type === 'receipt') {
        this.callbacks.onReceiptReceived?.(peerId, json.msgId, !!json.delivered, !!json.read);
        return;
      }

      if (json.type === 'chat' || (json.id && json.text !== undefined)) {
        const payload = json.payload || json;
        const message: Message = {
          id: payload.id || crypto.randomUUID(),
          chatId: payload.chatId || `chat_${peerId}`,
          senderId: peerId,
          senderName: payload.senderName || 'Peer',
          text: payload.text || '',
          timestamp: payload.timestamp || Date.now(),
          status: 'delivered',
          type: payload.msgType || 'text',
          replyToId: payload.replyToId,
          replySenderName: payload.replySenderName,
          replyText: payload.replyText,
          mediaUrl: payload.mediaUrl,
        };

        audioEffects.playMessageReceived();
        this.callbacks.onMessageReceived?.(peerId, message);
        this.sendReceipt(peerId, message.id, true, true);
      }
    } catch (err) {
      console.warn('[WebRTC] Inbound packet parsing error:', err);
    }
  }

  public sendMessage(peerId: string, message: Message): boolean {
    const channels = this.dataChannels.get(peerId);
    if (!channels || !channels.msg || channels.msg.readyState !== 'open') {
      return false;
    }

    const payload = {
      type: 'chat',
      id: message.id,
      chatId: message.chatId,
      senderId: this.currentUserId,
      senderName: message.senderName || 'Me',
      text: message.text,
      msgType: message.type,
      timestamp: message.timestamp,
      replyToId: message.replyToId,
      replySenderName: message.replySenderName,
      replyText: message.replyText,
      mediaUrl: message.mediaUrl,
    };

    channels.msg.send(JSON.stringify(payload));
    audioEffects.playMessageSent();
    return true;
  }

  public async sendFile(peerId: string, file: File, onProgress?: (p: FileTransferProgress) => void): Promise<string> {
    const channels = this.dataChannels.get(peerId);
    const mediaChannel = channels?.media || channels?.msg;
    if (!mediaChannel || mediaChannel.readyState !== 'open') {
      throw new Error('DataChannel not connected');
    }
    return fileTransferService.sendFile(mediaChannel, file, onProgress);
  }

  public sendTyping(peerId: string, isTyping: boolean) {
    this.sendCtrlPacket(peerId, { type: 'typing', isTyping });
  }

  public sendReceipt(peerId: string, msgId: string, delivered: boolean, read: boolean) {
    this.sendCtrlPacket(peerId, { type: 'receipt', msgId, delivered, read });
  }

  private sendCtrlPacket(peerId: string, data: any) {
    const channels = this.dataChannels.get(peerId);
    const target = channels?.ctrl?.readyState === 'open' ? channels.ctrl : channels?.msg;
    if (target && target.readyState === 'open') {
      target.send(JSON.stringify(data));
    }
  }

  // Live P2P Calls
  public async initiateCall(peerId: string, type: 'audio' | 'video', callerName: string): Promise<MediaStream> {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: type === 'video',
    });
    this.localMediaStream = stream;

    const pc = await this.connectToPeer(peerId, true);
    stream.getTracks().forEach((track) => pc.addTrack(track, stream));

    const callId = crypto.randomUUID();
    await signalingService.sendSignal(peerId, {
      type: 'call_invite',
      callId,
      callerName,
      callType: type,
    });

    audioEffects.startRingtone();
    return stream;
  }

  public async acceptCall(peerId: string, callId: string, type: 'audio' | 'video'): Promise<MediaStream> {
    audioEffects.stopRingtone();
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: type === 'video',
    });
    this.localMediaStream = stream;

    const pc = await this.connectToPeer(peerId, false);
    stream.getTracks().forEach((track) => pc.addTrack(track, stream));

    await signalingService.sendSignal(peerId, {
      type: 'call_accept',
      callId,
    });

    return stream;
  }

  public endCall(peerId?: string, callId?: string) {
    audioEffects.stopRingtone();
    if (this.localMediaStream) {
      this.localMediaStream.getTracks().forEach((t) => t.stop());
      this.localMediaStream = null;
    }
    if (peerId) {
      signalingService.sendSignal(peerId, {
        type: 'call_end',
        callId: callId || '',
      });
    }
  }

  private async handleIncomingSignal(signal: SignalPacket) {
    const peerId = signal.from;
    if (!peerId || peerId === this.currentUserId) return;

    if (signal.type === 'call_invite') {
      audioEffects.startRingtone();
      this.callbacks.onIncomingCall?.(
        peerId,
        signal.callId || crypto.randomUUID(),
        signal.callerName || 'Peer',
        signal.callType || 'audio'
      );
      return;
    }

    if (signal.type === 'call_accept') {
      audioEffects.stopRingtone();
      this.callbacks.onCallAccepted?.(peerId, signal.callId || '');
      return;
    }

    if (signal.type === 'call_end' || signal.type === 'call_reject') {
      audioEffects.stopRingtone();
      this.callbacks.onCallEnded?.(peerId);
      return;
    }

    if (signal.type === 'offer' && signal.sdp) {
      const pc = await this.connectToPeer(peerId, false);
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: signal.sdp }));

      const pending = this.pendingCandidates.get(peerId) || [];
      for (const cand of pending) {
        await pc.addIceCandidate(cand);
      }
      this.pendingCandidates.delete(peerId);

      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);

      await signalingService.sendSignal(peerId, {
        type: 'answer',
        sdp: answer.sdp,
      });
    } else if (signal.type === 'answer' && signal.sdp) {
      const pc = this.peerConnections.get(peerId);
      if (pc) {
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: signal.sdp }));
        const pending = this.pendingCandidates.get(peerId) || [];
        for (const cand of pending) {
          await pc.addIceCandidate(cand);
        }
        this.pendingCandidates.delete(peerId);
      }
    } else if (signal.type === 'candidate' && signal.sdp) {
      const candidate: RTCIceCandidateInit = {
        candidate: signal.sdp,
        sdpMid: signal.sdpMid || undefined,
        sdpMLineIndex: signal.sdpMLineIndex,
      };

      const pc = this.peerConnections.get(peerId);
      if (pc && pc.remoteDescription) {
        await pc.addIceCandidate(candidate);
      } else {
        const queue = this.pendingCandidates.get(peerId) || [];
        queue.push(candidate);
        this.pendingCandidates.set(peerId, queue);
      }
    }
  }

  private startHeartbeat(peerId: string) {
    this.stopHeartbeat(peerId);
    const interval = window.setInterval(() => {
      this.sendCtrlPacket(peerId, { type: 'p2p_ping', timestamp: Date.now() });
    }, 15_000);
    this.pingIntervals.set(peerId, interval);
  }

  private stopHeartbeat(peerId: string) {
    const interval = this.pingIntervals.get(peerId);
    if (interval) {
      clearInterval(interval);
      this.pingIntervals.delete(peerId);
    }
  }

  public disconnectPeer(peerId: string) {
    this.stopHeartbeat(peerId);
    const pc = this.peerConnections.get(peerId);
    if (pc) {
      pc.close();
      this.peerConnections.delete(peerId);
    }
    this.dataChannels.delete(peerId);
  }
}

export const webrtcEngine = new WebRtcEngine();
