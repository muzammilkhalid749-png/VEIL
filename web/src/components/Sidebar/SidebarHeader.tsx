import React, { useState } from 'react';
import { Search, QrCode, Menu, Lock } from 'lucide-react';
import { useAuthStore } from '../../store/useAuthStore';

interface SidebarHeaderProps {
  searchQuery: string;
  onSearchChange: (q: string) => void;
  onOpenPairing: () => void;
  onOpenSettings: () => void;
}

export const SidebarHeader: React.FC<SidebarHeaderProps> = ({
  searchQuery,
  onSearchChange,
  onOpenPairing,
  onOpenSettings,
}) => {
  const { displayName, userId, isPairedWithPhone } = useAuthStore();
  const [activeTab, setActiveTab] = useState<'all' | 'direct' | 'mesh'>('all');

  return (
    <div className="flex flex-col border-b border-[#1e2230] bg-[#11131a] p-3 gap-3">
      {/* Top Bar */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <button 
            onClick={onOpenSettings}
            aria-label="Open Settings and Identity Vault"
            className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-[#1a1d28] cursor-pointer transition-all duration-200"
            title="Settings & Sovereign Identity"
          >
            <Menu className="w-5 h-5" />
          </button>
          
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center font-bold text-xs text-white shadow-md select-none">
              {displayName.slice(0, 2).toUpperCase()}
            </div>
            <div className="flex flex-col">
              <span className="text-sm font-semibold text-white tracking-wide flex items-center gap-1.5">
                VEIL <span className="text-[10px] bg-indigo-500/20 text-indigo-300 px-1.5 py-0.5 rounded font-mono font-medium border border-indigo-500/30">WEB</span>
              </span>
              <span className="text-[10px] text-slate-400 font-mono flex items-center gap-1">
                <Lock className="w-2.5 h-2.5 text-emerald-400" />
                {userId.slice(0, 14)}...
              </span>
            </div>
          </div>
        </div>

        {/* Action button */}
        <button
          onClick={onOpenPairing}
          aria-label="Link Phone via QR code"
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold cursor-pointer transition-all duration-200 ${
            isPairedWithPhone
              ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 hover:bg-emerald-500/20'
              : 'bg-indigo-600/20 text-indigo-300 border border-indigo-500/30 hover:bg-indigo-600/30'
          }`}
          title="Link Android Phone via QR"
        >
          <QrCode className="w-3.5 h-3.5" />
          <span>{isPairedWithPhone ? 'Linked' : 'Link Phone'}</span>
        </button>
      </div>

      {/* Accessible Search Input */}
      <div className="relative">
        <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          aria-label="Search chats, contacts, and mesh nodes"
          placeholder="Search chats or peer IDs..."
          className="w-full bg-[#181c26] text-sm text-slate-100 placeholder-slate-400 rounded-xl pl-9 pr-3 py-2 border border-transparent focus:border-indigo-500/60 focus:bg-[#1c202d] focus:outline-none transition-all duration-200"
        />
      </div>

      {/* Navigation Filter Tabs */}
      <div className="flex items-center gap-1.5 text-xs">
        {(['all', 'direct', 'mesh'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            aria-label={`Filter by ${tab}`}
            className={`px-3 py-1.5 rounded-full capitalize font-medium cursor-pointer transition-all duration-200 ${
              activeTab === tab
                ? 'bg-indigo-600/25 text-indigo-200 font-semibold border border-indigo-500/30'
                : 'text-slate-400 hover:text-slate-200 hover:bg-[#181c26]'
            }`}
          >
            {tab === 'all' ? 'All Chats' : tab === 'direct' ? 'Direct P2P' : 'Mesh Nodes'}
          </button>
        ))}
      </div>
    </div>
  );
};
