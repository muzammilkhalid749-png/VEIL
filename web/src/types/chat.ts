export type MessageStatus = 'sending' | 'sent' | 'delivered' | 'read' | 'failed';

export type MessageType = 'text' | 'image' | 'voice' | 'file';

export interface Message {
  id: string;
  chatId: string;
  senderId: string;
  senderName?: string;
  text: string;
  timestamp: number;
  status: MessageStatus;
  type: MessageType;
  replyToId?: string;
  replySenderName?: string;
  replyText?: string;
  mediaUrl?: string;
  mediaSize?: number;
  fileName?: string;
  durationSec?: number;
  isBlink?: boolean;
}

export interface PeerProfile {
  id: string;
  username: string;
  displayName: string;
  avatarUrl?: string;
  about?: string;
  publicKey?: string;
  online: boolean;
  lastSeen?: number;
  isMeshNode?: boolean;
}

export interface ChatSession {
  id: string;
  peerId: string;
  peer: PeerProfile;
  lastMessage?: Message;
  unreadCount: number;
  isPinned?: boolean;
  isMuted?: boolean;
  draftText?: string;
  typingUntil?: number;
}

export type PeerConnectionState = 
  | 'idle' 
  | 'connecting' 
  | 'connected' 
  | 'disconnected' 
  | 'failed';

export interface P2PMetrics {
  rttMs: number;
  bytesReceived: number;
  bytesSent: number;
  iceState: string;
  relayType: 'direct-p2p' | 'stun-turn' | 'mesh-relay';
}

export interface CallSession {
  callId: string;
  peerId: string;
  peerName: string;
  type: 'audio' | 'video';
  direction: 'incoming' | 'outgoing';
  status: 'ringing' | 'connected' | 'ended';
  startedAt?: number;
}
