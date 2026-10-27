import React, { useEffect, useRef } from 'react';
import type { Message, PeerProfile } from '../../types/chat';
import { MessageBubble } from './MessageBubble';
import { ShieldCheck, Lock } from 'lucide-react';
import { useAuthStore } from '../../store/useAuthStore';

interface MessageListProps {
  messages: Message[];
  peer?: PeerProfile;
  onReply: (message: Message) => void;
}

export const MessageList: React.FC<MessageListProps> = ({
  messages,
  onReply,
}) => {
  const currentUserId = useAuthStore((s) => s.userId);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  return (
    <div className="flex-1 overflow-y-auto py-4 flex flex-col justify-between">
      {/* Privacy Notice Banner */}
      <div className="flex flex-col items-center justify-center my-4 px-4">
        <div className="flex items-center gap-2 bg-[#171a22]/80 border border-[#232734] px-3.5 py-1.5 rounded-full text-xs text-gray-400 shadow-sm backdrop-blur-sm">
          <ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />
          <span>Messages are routed directly over encrypted WebRTC DataChannels.</span>
        </div>
      </div>

      {/* Messages Feed */}
      <div className="flex-1 space-y-1">
        {messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-center text-gray-500 py-12">
            <Lock className="w-8 h-8 text-indigo-400/50 mb-2" />
            <span className="text-sm font-medium text-gray-400">Zero-Knowledge Channel Ready</span>
            <span className="text-xs text-gray-500 max-w-xs mt-1">
              Say hello! Your payloads bypass all intermediary servers.
            </span>
          </div>
        ) : (
          messages.map((msg) => (
            <MessageBubble
              key={msg.id}
              message={msg}
              isOutgoing={msg.senderId === currentUserId}
              onReply={onReply}
            />
          ))
        )}
        <div ref={bottomRef} />
      </div>
    </div>
  );
};
