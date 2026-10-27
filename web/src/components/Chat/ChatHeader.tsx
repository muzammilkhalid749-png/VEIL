import React from 'react';
import { Phone, Video, Info, Lock, Radio } from 'lucide-react';
import type { PeerProfile } from '../../types/chat';

interface ChatHeaderProps {
  peer: PeerProfile;
  isTyping: boolean;
  onAudioCall: () => void;
  onVideoCall: () => void;
  onOpenMetrics: () => void;
}

export const ChatHeader: React.FC<ChatHeaderProps> = ({
  peer,
  isTyping,
  onAudioCall,
  onVideoCall,
  onOpenMetrics,
}) => {
  return (
    <header className="h-16 px-5 border-b border-[#1e2230] bg-[#11131a]/95 backdrop-blur-md flex items-center justify-between shrink-0 select-none">
      {/* Peer Profile Summary */}
      <div className="flex items-center gap-3">
        <div className="relative">
          <div className="w-10 h-10 rounded-full bg-gradient-to-tr from-indigo-700 to-violet-600 flex items-center justify-center font-bold text-sm text-white shadow-md">
            {peer.displayName.slice(0, 2).toUpperCase()}
          </div>
          {peer.online && (
            <span className="absolute bottom-0 right-0 w-3 h-3 rounded-full bg-emerald-500 border-2 border-[#11131a]" />
          )}
        </div>

        <div className="flex flex-col">
          <div className="flex items-center gap-2">
            <span className="text-sm font-bold text-slate-100 tracking-wide">
              {peer.displayName}
            </span>
            {peer.isMeshNode && (
              <span className="flex items-center gap-1 text-[10px] bg-indigo-500/20 text-indigo-300 px-2 py-0.5 rounded-full font-mono border border-indigo-500/30">
                <Radio className="w-2.5 h-2.5" /> MESH
              </span>
            )}
          </div>
          
          <div className="text-xs text-slate-400 flex items-center gap-1 font-mono">
            {isTyping ? (
              <span className="text-indigo-400 font-sans italic animate-pulse">typing...</span>
            ) : peer.online ? (
              <span className="text-emerald-400 flex items-center gap-1">
                <Lock className="w-3 h-3 text-emerald-400" />
                Direct P2P DataChannel Active
              </span>
            ) : (
              <span className="text-slate-400">Offline (Mesh Store-and-Forward)</span>
            )}
          </div>
        </div>
      </div>

      {/* Action Buttons: 44x44px touch targets according to UI/UX Pro Max Priority 2 */}
      <div className="flex items-center gap-1 text-slate-300">
        <button
          onClick={onAudioCall}
          aria-label="Start Encrypted Audio Call"
          className="w-11 h-11 flex items-center justify-center rounded-xl hover:bg-[#1a1e28] hover:text-white cursor-pointer transition-all duration-200"
          title="Encrypted Audio Call"
        >
          <Phone className="w-4 h-4" />
        </button>

        <button
          onClick={onVideoCall}
          aria-label="Start Encrypted Video Call"
          className="w-11 h-11 flex items-center justify-center rounded-xl hover:bg-[#1a1e28] hover:text-white cursor-pointer transition-all duration-200"
          title="Encrypted Video Call"
        >
          <Video className="w-4 h-4" />
        </button>

        <button
          onClick={onOpenMetrics}
          aria-label="Open P2P Diagnostics and Connection Metrics"
          className="w-11 h-11 flex items-center justify-center rounded-xl hover:bg-[#1a1e28] hover:text-indigo-400 cursor-pointer transition-all duration-200"
          title="P2P Diagnostics & WebRTC Metrics"
        >
          <Info className="w-4 h-4" />
        </button>
      </div>
    </header>
  );
};
