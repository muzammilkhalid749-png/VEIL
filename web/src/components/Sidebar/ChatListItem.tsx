import React from 'react';
import { Check, CheckCheck, Clock, Radio } from 'lucide-react';
import type { ChatSession } from '../../types/chat';

interface ChatListItemProps {
  chat: ChatSession;
  isActive: boolean;
  isTyping: boolean;
  onSelect: () => void;
}

export const ChatListItem: React.FC<ChatListItemProps> = ({
  chat,
  isActive,
  isTyping,
  onSelect,
}) => {
  const { peer, lastMessage, unreadCount } = chat;

  const formatTime = (ts?: number) => {
    if (!ts) return '';
    const date = new Date(ts);
    const now = new Date();
    if (date.toDateString() === now.toDateString()) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  };

  const getStatusIcon = (status?: string) => {
    switch (status) {
      case 'sending':
        return <Clock className="w-3.5 h-3.5 text-slate-400" aria-label="Sending" />;
      case 'sent':
        return <Check className="w-3.5 h-3.5 text-slate-400" aria-label="Sent" />;
      case 'delivered':
        return <CheckCheck className="w-3.5 h-3.5 text-slate-400" aria-label="Delivered" />;
      case 'read':
        return <CheckCheck className="w-3.5 h-3.5 text-indigo-400" aria-label="Read" />;
      default:
        return null;
    }
  };

  return (
    <div
      onClick={onSelect}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect();
        }
      }}
      aria-label={`Chat with ${peer.displayName}`}
      className={`flex items-center gap-3 px-3 py-3 cursor-pointer rounded-2xl mx-2 transition-all duration-200 select-none ${
        isActive
          ? 'bg-indigo-600/20 border-l-2 border-indigo-500 text-white shadow-xs'
          : 'hover:bg-[#161a24] text-slate-300'
      }`}
    >
      {/* Avatar Container with presence badge */}
      <div className="relative shrink-0">
        <div className="w-12 h-12 rounded-full bg-[#1e2330] border border-[#2d3446] flex items-center justify-center font-bold text-sm text-indigo-200 shadow-inner">
          {peer.displayName.slice(0, 2).toUpperCase()}
        </div>
        
        {peer.online ? (
          <span 
            className="absolute bottom-0 right-0 w-3.5 h-3.5 rounded-full bg-emerald-500 border-2 border-[#11131a]" 
            title="Online"
            aria-label="Online"
          />
        ) : (
          <span 
            className="absolute bottom-0 right-0 w-3.5 h-3.5 rounded-full bg-slate-500 border-2 border-[#11131a]" 
            title="Offline"
            aria-label="Offline"
          />
        )}
      </div>

      {/* Info Column */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between mb-0.5">
          <span className="text-sm font-semibold truncate flex items-center gap-1.5 text-slate-100">
            {peer.displayName}
            {peer.isMeshNode && (
              <span title="Active Mesh Node" className="inline-flex">
                <Radio className="w-3 h-3 text-indigo-400 shrink-0" />
              </span>
            )}
          </span>
          <span className="text-[11px] text-slate-400 shrink-0 font-medium">
            {formatTime(lastMessage?.timestamp)}
          </span>
        </div>

        <div className="flex items-center justify-between">
          <div className="text-xs text-slate-400 truncate flex items-center gap-1">
            {isTyping ? (
              <span className="text-indigo-400 font-medium italic animate-pulse">
                typing...
              </span>
            ) : (
              <>
                {lastMessage?.senderId !== peer.id && lastMessage?.status && (
                  <span className="shrink-0">{getStatusIcon(lastMessage.status)}</span>
                )}
                <span className="truncate">{lastMessage?.text || 'No messages yet'}</span>
              </>
            )}
          </div>

          {unreadCount > 0 && (
            <span className="ml-2 shrink-0 bg-indigo-600 text-white font-bold text-[10px] px-1.5 py-0.5 rounded-full min-w-[18px] text-center shadow-md">
              {unreadCount}
            </span>
          )}
        </div>
      </div>
    </div>
  );
};
