import React, { useEffect, useState } from 'react';
import { Sidebar } from './components/Sidebar/Sidebar';
import { ChatHeader } from './components/Chat/ChatHeader';
import { MessageList } from './components/Chat/MessageList';
import { MessageInput } from './components/Chat/MessageInput';
import { PairingModal } from './components/Modals/PairingModal';
import { CallModal } from './components/Modals/CallModal';
import { PeerMetricsModal } from './components/Modals/PeerMetricsModal';
import { NewChatModal } from './components/Modals/NewChatModal';
import { SettingsModal } from './components/Modals/SettingsModal';
import { AuthQrScreen } from './components/Auth/AuthQrScreen';
import { AuthKeyScreen } from './components/Auth/AuthKeyScreen';
import { useAuthStore } from './store/useAuthStore';
import { useChatStore } from './store/useChatStore';
import { webrtcEngine } from './services/webrtc';
import type { Message } from './types/chat';
import { Shield, Sparkles } from 'lucide-react';

export const App: React.FC = () => {
  const { isAuthenticated, userId, webPeerId, isPairedWithPhone, pairedPhoneId, initializeAuth } = useAuthStore();
  const {
    chats,
    activeChatId,
    messages,
    initializeStore,
    sendMessage,
    receiveMessage,
    updateMessageStatus,
    setPeerOnline,
    setPeerTyping,
    startCall,
    endCall,
    setRemoteStream,
    typingPeers,
  } = useChatStore();

  const [authMode, setAuthMode] = useState<'qr' | 'key'>('qr');
  const [isPairingOpen, setIsPairingOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isNewChatOpen, setIsNewChatOpen] = useState(false);
  const [isMetricsOpen, setIsMetricsOpen] = useState(false);
  const [replyTarget, setReplyTarget] = useState<Message | undefined>(undefined);

  // 1. Restore auth state on mount
  useEffect(() => {
    initializeAuth();
  }, [initializeAuth]);

  // 2. Hydrate vault store once authenticated
  useEffect(() => {
    if (isAuthenticated) {
      initializeStore();
    }
  }, [isAuthenticated, initializeStore]);

  // 3. Global keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsPairingOpen(false);
        setIsSettingsOpen(false);
        setIsNewChatOpen(false);
        setIsMetricsOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // 4. Initialize WebRTC signaling when authenticated
  useEffect(() => {
    if (!isAuthenticated || !userId) return;

    webrtcEngine.init(userId, {
      onPeerConnected: (peerId) => {
        setPeerOnline(peerId, true);
      },
      onPeerDisconnected: (peerId) => {
        setPeerOnline(peerId, false);
      },
      onMessageReceived: (peerId, message) => {
        receiveMessage(peerId, message);
      },
      onReceiptReceived: (_peerId, msgId, delivered, read) => {
        if (read) updateMessageStatus(msgId, 'read');
        else if (delivered) updateMessageStatus(msgId, 'delivered');
      },
      onTypingStatus: (peerId, isTyping) => {
        setPeerTyping(peerId, isTyping);
      },
      onRemoteTrack: (_peerId, stream) => {
        setRemoteStream(stream);
      },
      onIncomingCall: (fromUserId, callId, callerName, callType) => {
        useChatStore.setState({
          activeCall: {
            callId,
            peerId: fromUserId,
            peerName: callerName,
            type: callType,
            direction: 'incoming',
            status: 'ringing',
            startedAt: Date.now(),
          },
        });
      },
      onCallAccepted: () => {
        useChatStore.setState((state) => ({
          activeCall: state.activeCall ? { ...state.activeCall, status: 'connected' } : null,
        }));
      },
      onCallEnded: () => {
        endCall();
      },
    }, webPeerId);

    // If paired with phone companion, automatically establish connection to phone
    if (isPairedWithPhone && pairedPhoneId) {
      webrtcEngine.connectToPeer(pairedPhoneId, true).catch((e) => {
        console.log('[App] Phone companion initial connection notice:', e);
      });
    }
  }, [isAuthenticated, userId, webPeerId, isPairedWithPhone, pairedPhoneId, setPeerOnline, receiveMessage, updateMessageStatus, setPeerTyping, setRemoteStream, endCall]);

  // ═══════════════════════════════════════════════════════════════════════════
  // TELEGRAM WEB FIRST-TIME LIFECYCLE GATE
  // ═══════════════════════════════════════════════════════════════════════════
  if (!isAuthenticated) {
    if (authMode === 'key') {
      return <AuthKeyScreen onBackToQr={() => setAuthMode('qr')} />;
    }
    return <AuthQrScreen onSwitchToKeyLogin={() => setAuthMode('key')} />;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // AUTHENTICATED FLOATING BENTO WORKSPACE (VISIONOS + MODERN DARK CINEMA)
  // ═══════════════════════════════════════════════════════════════════════════
  const activeChat = chats.find((c) => c.id === activeChatId);
  const activeMessages = activeChat ? messages[activeChat.id] || [] : [];

  return (
    <div className="flex h-screen w-screen p-2.5 md:p-3.5 bg-[#07080c] text-slate-100 select-none relative overflow-hidden font-['Plus_Jakarta_Sans',sans-serif]">
      {/* Ambient background glows */}
      <div className="absolute top-0 left-1/4 w-[600px] h-[600px] bg-indigo-600/8 rounded-full blur-[160px] pointer-events-none" />
      <div className="absolute bottom-0 right-1/4 w-[600px] h-[600px] bg-violet-600/8 rounded-full blur-[160px] pointer-events-none" />

      {/* Floating Bento Sidebar Column */}
      <div className="h-full rounded-3xl glass-panel shadow-2xl overflow-hidden flex flex-col shrink-0 z-10 border border-white/8">
        <Sidebar
          onOpenPairing={() => setIsPairingOpen(true)}
          onOpenSettings={() => setIsSettingsOpen(true)}
          onOpenNewChat={() => setIsNewChatOpen(true)}
        />
      </div>

      {/* Floating Bento Chat Workspace Column */}
      <main className="flex-1 h-full ml-2.5 md:ml-3 rounded-3xl glass-panel shadow-2xl overflow-hidden flex flex-col relative z-10 border border-white/8">
        {/* Telegram-style subtle wallpaper pattern */}
        <div 
          className="absolute inset-0 opacity-25 pointer-events-none bg-[radial-gradient(#4f46e5_1px,transparent_1px)] [background-size:24px_24px]" 
        />

        {activeChat ? (
          <>
            <ChatHeader
              peer={activeChat.peer}
              isTyping={!!typingPeers[activeChat.peerId]}
              onAudioCall={() => startCall(activeChat.peerId, 'audio')}
              onVideoCall={() => startCall(activeChat.peerId, 'video')}
              onOpenMetrics={() => setIsMetricsOpen(true)}
            />

            <div className="flex-1 overflow-hidden flex flex-col relative z-10">
              <MessageList
                messages={activeMessages}
                peer={activeChat.peer}
                onReply={(msg) => setReplyTarget(msg)}
              />

              <MessageInput
                peerId={activeChat.peerId}
                replyTo={replyTarget}
                onClearReply={() => setReplyTarget(undefined)}
                onSend={(text, replyTo) => sendMessage(text, replyTo)}
              />
            </div>
          </>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center text-center p-8 z-10">
            <div className="w-20 h-20 rounded-3xl bg-gradient-to-tr from-indigo-500/20 to-violet-500/20 text-indigo-400 flex items-center justify-center mb-5 border border-indigo-500/30 shadow-xl shadow-indigo-950/40">
              <Shield className="w-10 h-10" />
            </div>
            <div className="flex items-center gap-1.5 text-xs text-indigo-400 font-semibold mb-1">
              <Sparkles className="w-3.5 h-3.5" />
              <span>VEIL Sovereign P2P Mesh</span>
            </div>
            <h2 className="text-xl font-bold text-white tracking-tight">
              Select a Conversation
            </h2>
            <p className="text-xs text-slate-400 max-w-sm mt-1.5 leading-relaxed">
              Pick an active peer dialog on the left or tap the plus icon to start an encrypted, serverless WebRTC channel.
            </p>
          </div>
        )}
      </main>

      {/* Modals & Overlays */}
      <PairingModal
        isOpen={isPairingOpen}
        onClose={() => setIsPairingOpen(false)}
      />

      <CallModal />

      <PeerMetricsModal
        peer={activeChat?.peer || null}
        isOpen={isMetricsOpen}
        onClose={() => setIsMetricsOpen(false)}
      />

      <NewChatModal
        isOpen={isNewChatOpen}
        onClose={() => setIsNewChatOpen(false)}
      />

      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
      />
    </div>
  );
};

export default App;
