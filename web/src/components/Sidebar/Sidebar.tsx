import React, { useState } from 'react';
import { SidebarHeader } from './SidebarHeader';
import { ChatListItem } from './ChatListItem';
import { useChatStore } from '../../store/useChatStore';
import { UserPlus } from 'lucide-react';

interface SidebarProps {
  onOpenPairing: () => void;
  onOpenSettings: () => void;
  onOpenNewChat: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({
  onOpenPairing,
  onOpenSettings,
  onOpenNewChat,
}) => {
  const { chats, activeChatId, setActiveChat, typingPeers } = useChatStore();
  const [searchQuery, setSearchQuery] = useState('');

  const filteredChats = chats.filter((c) => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return true;
    return (
      c.peer.displayName.toLowerCase().includes(q) ||
      c.peer.username.toLowerCase().includes(q) ||
      c.peer.id.toLowerCase().includes(q) ||
      c.lastMessage?.text.toLowerCase().includes(q)
    );
  });

  return (
    <aside className="w-80 md:w-96 h-full flex flex-col select-none relative bg-[#0c0e15]/80 backdrop-blur-2xl">
      <SidebarHeader
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        onOpenPairing={onOpenPairing}
        onOpenSettings={onOpenSettings}
      />

      {/* Chat Sessions List */}
      <div className="flex-1 overflow-y-auto py-2 space-y-1">
        {filteredChats.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-48 text-center px-4">
            <span className="text-sm text-slate-400 font-medium">No conversations found</span>
            <span className="text-xs text-slate-500 mt-1">Start a direct P2P channel or scan a peer ID</span>
          </div>
        ) : (
          filteredChats.map((chat) => (
            <ChatListItem
              key={chat.id}
              chat={chat}
              isActive={chat.id === activeChatId}
              isTyping={!!typingPeers[chat.peerId]}
              onSelect={() => setActiveChat(chat.id)}
            />
          ))
        )}
      </div>

      {/* Floating Add Peer Button */}
      <button
        onClick={onOpenNewChat}
        aria-label="Start direct P2P chat"
        className="absolute bottom-5 right-5 w-12 h-12 rounded-2xl bg-gradient-to-tr from-indigo-600 to-violet-600 hover:from-indigo-500 hover:to-violet-500 text-white flex items-center justify-center shadow-lg shadow-indigo-600/30 cursor-pointer hover:scale-105 active:scale-95 transition-all duration-200"
        title="Start Direct P2P Chat"
      >
        <UserPlus className="w-5 h-5" />
      </button>
    </aside>
  );
};
