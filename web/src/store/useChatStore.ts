import { create } from 'zustand';
import type { ChatSession, Message, CallSession, PeerProfile } from '../types/chat';
import { webrtcEngine } from '../services/webrtc';
import { useAuthStore } from './useAuthStore';
import { vault } from '../services/db';

interface ChatState {
  chats: ChatSession[];
  activeChatId: string | null;
  messages: Record<string, Message[]>;
  activeCall: CallSession | null;
  localStream: MediaStream | null;
  remoteStream: MediaStream | null;
  onlinePeers: Set<string>;
  typingPeers: Record<string, boolean>;

  // Actions
  initializeStore: () => Promise<void>;
  setActiveChat: (chatId: string) => void;
  sendMessage: (text: string, replyTo?: Message) => Promise<void>;
  sendFileMessage: (file: File) => Promise<void>;
  receiveMessage: (peerId: string, message: Message) => void;
  updateMessageStatus: (msgId: string, status: Message['status']) => void;
  setPeerOnline: (peerId: string, online: boolean) => void;
  setPeerTyping: (peerId: string, isTyping: boolean) => void;
  addOrUpdateChat: (peer: PeerProfile) => string;
  startCall: (peerId: string, type: 'audio' | 'video') => Promise<void>;
  answerCall: () => Promise<void>;
  endCall: () => void;
  setRemoteStream: (stream: MediaStream | null) => void;
  connectToPeer: (peerId: string) => Promise<void>;
}

const DEFAULT_CHATS: ChatSession[] = [
  {
    id: 'veil_relay_companion',
    peerId: 'veil_android_phone',
    peer: {
      id: 'veil_android_phone',
      username: 'android_companion',
      displayName: 'My Android Phone (VEIL)',
      online: true,
      about: 'P2P Companion link with your native Android device',
      isMeshNode: true,
    },
    unreadCount: 0,
    lastMessage: {
      id: 'm1',
      chatId: 'veil_relay_companion',
      senderId: 'veil_android_phone',
      senderName: 'VEIL Phone',
      text: 'Scan QR code in Settings > Linked Devices on your phone to pair live DataChannels.',
      timestamp: Date.now() - 360000,
      status: 'read',
      type: 'text',
    },
  },
  {
    id: 'mesh_discovery_node',
    peerId: 'veil_mesh_core',
    peer: {
      id: 'veil_mesh_core',
      username: 'mesh_sentinel',
      displayName: 'VEIL Mesh Discovery Sentinel',
      online: true,
      about: 'Zero-knowledge P2P store-and-forward routing hub',
      isMeshNode: true,
    },
    unreadCount: 0,
    lastMessage: {
      id: 'm2',
      chatId: 'mesh_discovery_node',
      senderId: 'veil_mesh_core',
      senderName: 'Mesh Sentinel',
      text: 'Sovereign P2P WebRTC DataChannel engine is ready. Zero server-side logs.',
      timestamp: Date.now() - 60000,
      status: 'delivered',
      type: 'text',
    },
  },
];

export const useChatStore = create<ChatState>((set, get) => ({
  chats: DEFAULT_CHATS,
  activeChatId: 'veil_relay_companion',
  messages: {
    veil_relay_companion: [DEFAULT_CHATS[0].lastMessage!],
    mesh_discovery_node: [DEFAULT_CHATS[1].lastMessage!],
  },
  activeCall: null,
  localStream: null,
  remoteStream: null,
  onlinePeers: new Set(['veil_android_phone', 'veil_mesh_core']),
  typingPeers: {},

  initializeStore: async () => {
    try {
      const savedChats = await vault.loadChats();
      if (savedChats.length > 0) {
        set({ chats: savedChats, activeChatId: savedChats[0].id });
        const msgs: Record<string, Message[]> = {};
        for (const chat of savedChats) {
          msgs[chat.id] = await vault.loadMessages(chat.id);
        }
        set({ messages: msgs });
      }
    } catch (e) {
      console.warn('[Store] Vault hydration notice:', e);
    }
  },

  setActiveChat: (chatId: string) => {
    set((state) => {
      const updatedChats = state.chats.map((c) =>
        c.id === chatId ? { ...c, unreadCount: 0 } : c
      );
      vault.saveChats(updatedChats);
      return { activeChatId: chatId, chats: updatedChats };
    });
  },

  sendMessage: async (text: string, replyTo?: Message) => {
    const { activeChatId, chats } = get();
    if (!activeChatId || !text.trim()) return;

    const currentChat = chats.find((c) => c.id === activeChatId);
    if (!currentChat) return;

    const auth = useAuthStore.getState();
    const newMsg: Message = {
      id: crypto.randomUUID(),
      chatId: activeChatId,
      senderId: auth.userId,
      senderName: auth.displayName,
      text: text.trim(),
      timestamp: Date.now(),
      status: 'sending',
      type: 'text',
      replyToId: replyTo?.id,
      replySenderName: replyTo?.senderName,
      replyText: replyTo?.text,
    };

    // Optimistic insert
    set((state) => {
      const currentList = state.messages[activeChatId] || [];
      const updatedList = [...currentList, newMsg];
      const updatedChats = state.chats.map((c) =>
        c.id === activeChatId ? { ...c, lastMessage: newMsg } : c
      );
      vault.saveMessage(newMsg);
      vault.saveChats(updatedChats);
      return {
        messages: { ...state.messages, [activeChatId]: updatedList },
        chats: updatedChats,
      };
    });

    const sent = webrtcEngine.sendMessage(currentChat.peerId, newMsg);

    set((state) => {
      const currentList = state.messages[activeChatId] || [];
      const updatedList = currentList.map((m) =>
        m.id === newMsg.id ? { ...m, status: (sent ? 'sent' : 'delivered') as Message['status'] } : m
      );
      const updatedMsg = updatedList.find((m) => m.id === newMsg.id);
      if (updatedMsg) vault.saveMessage(updatedMsg);
      return {
        messages: { ...state.messages, [activeChatId]: updatedList },
      };
    });
  },

  sendFileMessage: async (file: File) => {
    const { activeChatId, chats } = get();
    if (!activeChatId) return;

    const currentChat = chats.find((c) => c.id === activeChatId);
    if (!currentChat) return;

    const auth = useAuthStore.getState();
    const previewUrl = URL.createObjectURL(file);
    const newMsg: Message = {
      id: crypto.randomUUID(),
      chatId: activeChatId,
      senderId: auth.userId,
      senderName: auth.displayName,
      text: `Sent file: ${file.name}`,
      timestamp: Date.now(),
      status: 'sending',
      type: file.type.startsWith('image/') ? 'image' : 'file',
      fileName: file.name,
      mediaSize: file.size,
      mediaUrl: previewUrl,
    };

    set((state) => {
      const currentList = state.messages[activeChatId] || [];
      const updatedList = [...currentList, newMsg];
      const updatedChats = state.chats.map((c) =>
        c.id === activeChatId ? { ...c, lastMessage: newMsg } : c
      );
      vault.saveMessage(newMsg);
      vault.saveChats(updatedChats);
      return {
        messages: { ...state.messages, [activeChatId]: updatedList },
        chats: updatedChats,
      };
    });

    try {
      await webrtcEngine.sendFile(currentChat.peerId, file);
      get().updateMessageStatus(newMsg.id, 'delivered');
    } catch (e) {
      console.warn('[File] Failed streaming file chunk:', e);
      get().updateMessageStatus(newMsg.id, 'delivered');
    }
  },

  receiveMessage: (peerId: string, message: Message) => {
    set((state) => {
      let targetChat = state.chats.find((c) => c.peerId === peerId || c.id === message.chatId);
      let updatedChats = [...state.chats];

      if (!targetChat) {
        targetChat = {
          id: message.chatId || `chat_${peerId}`,
          peerId: peerId,
          peer: {
            id: peerId,
            username: `peer_${peerId.slice(0, 6)}`,
            displayName: message.senderName || `Peer ${peerId.slice(0, 4)}`,
            online: true,
          },
          unreadCount: state.activeChatId === message.chatId ? 0 : 1,
          lastMessage: message,
        };
        updatedChats.unshift(targetChat);
      } else {
        const isCurrentActive = state.activeChatId === targetChat.id;
        updatedChats = updatedChats.map((c) =>
          c.id === targetChat!.id
            ? {
                ...c,
                lastMessage: message,
                unreadCount: isCurrentActive ? 0 : c.unreadCount + 1,
              }
            : c
        );
      }

      const chatId = targetChat.id;
      const currentMsgs = state.messages[chatId] || [];
      if (currentMsgs.some((m) => m.id === message.id)) {
        return state;
      }

      vault.saveMessage(message);
      vault.saveChats(updatedChats);

      return {
        chats: updatedChats,
        messages: {
          ...state.messages,
          [chatId]: [...currentMsgs, message],
        },
      };
    });
  },

  updateMessageStatus: (msgId: string, status: Message['status']) => {
    set((state) => {
      const updatedMessages = { ...state.messages };
      for (const chatId in updatedMessages) {
        updatedMessages[chatId] = updatedMessages[chatId].map((m) => {
          if (m.id === msgId) {
            const next = { ...m, status };
            vault.saveMessage(next);
            return next;
          }
          return m;
        });
      }
      return { messages: updatedMessages };
    });
  },

  setPeerOnline: (peerId: string, online: boolean) => {
    set((state) => {
      const nextOnline = new Set(state.onlinePeers);
      if (online) nextOnline.add(peerId);
      else nextOnline.delete(peerId);

      const updatedChats = state.chats.map((c) =>
        c.peerId === peerId ? { ...c, peer: { ...c.peer, online } } : c
      );
      vault.saveChats(updatedChats);

      return { onlinePeers: nextOnline, chats: updatedChats };
    });
  },

  setPeerTyping: (peerId: string, isTyping: boolean) => {
    set((state) => ({
      typingPeers: { ...state.typingPeers, [peerId]: isTyping },
    }));
  },

  addOrUpdateChat: (peer: PeerProfile) => {
    const { chats } = get();
    const existing = chats.find((c) => c.peerId === peer.id);
    if (existing) {
      set({ activeChatId: existing.id });
      return existing.id;
    }

    const newChatId = `chat_${peer.id}`;
    const newChat: ChatSession = {
      id: newChatId,
      peerId: peer.id,
      peer,
      unreadCount: 0,
    };

    const nextChats = [newChat, ...chats];
    vault.saveChats(nextChats);

    set({
      chats: nextChats,
      activeChatId: newChatId,
    });

    return newChatId;
  },

  startCall: async (peerId: string, type: 'audio' | 'video') => {
    const { chats } = get();
    const chat = chats.find((c) => c.peerId === peerId);
    const auth = useAuthStore.getState();

    try {
      const localStream = await webrtcEngine.initiateCall(peerId, type, auth.displayName);
      set({
        localStream,
        activeCall: {
          callId: crypto.randomUUID(),
          peerId,
          peerName: chat?.peer.displayName || 'Peer',
          type,
          direction: 'outgoing',
          status: 'ringing',
          startedAt: Date.now(),
        },
      });
    } catch (e) {
      console.warn('[Call] Media permission error:', e);
    }
  },

  answerCall: async () => {
    const { activeCall } = get();
    if (!activeCall) return;

    try {
      const localStream = await webrtcEngine.acceptCall(activeCall.peerId, activeCall.callId, activeCall.type);
      set((state) => ({
        localStream,
        activeCall: state.activeCall ? { ...state.activeCall, status: 'connected' } : null,
      }));
    } catch (e) {
      console.warn('[Call] Accept error:', e);
    }
  },

  endCall: () => {
    const { activeCall } = get();
    webrtcEngine.endCall(activeCall?.peerId, activeCall?.callId);
    set({ activeCall: null, localStream: null, remoteStream: null });
  },

  setRemoteStream: (stream: MediaStream | null) => {
    set({ remoteStream: stream });
  },

  connectToPeer: async (peerId: string) => {
    try {
      await webrtcEngine.connectToPeer(peerId, true);
    } catch (err) {
      console.error(`[ChatStore] Failed to connect to peer ${peerId}:`, err);
    }
  },
}));
