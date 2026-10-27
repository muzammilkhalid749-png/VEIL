import React from 'react';
import { X, ShieldCheck, Activity, Key, Radio, Layers } from 'lucide-react';
import type { PeerProfile } from '../../types/chat';

interface PeerMetricsModalProps {
  peer: PeerProfile | null;
  isOpen: boolean;
  onClose: () => void;
}

export const PeerMetricsModal: React.FC<PeerMetricsModalProps> = ({
  peer,
  isOpen,
  onClose,
}) => {
  if (!isOpen || !peer) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-[#14161e] border border-[#262b3a] rounded-3xl w-full max-w-md p-6 shadow-2xl relative text-gray-200">
        <button
          onClick={onClose}
          className="absolute top-5 right-5 text-gray-400 hover:text-white p-1 rounded-lg"
        >
          <X className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-2.5 mb-6">
          <Activity className="w-5 h-5 text-indigo-400" />
          <h2 className="text-lg font-bold text-white tracking-wide">
            P2P Connection Diagnostics
          </h2>
        </div>

        {/* Diagnostic Items */}
        <div className="space-y-3 text-xs">
          {/* Peer Details */}
          <div className="bg-[#1a1e28] p-3 rounded-2xl border border-[#2b3142] space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-gray-400">Target Node</span>
              <span className="font-semibold text-white">{peer.displayName}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-gray-400">Peer ID</span>
              <span className="font-mono text-[11px] text-indigo-300 truncate max-w-[200px]">
                {peer.id}
              </span>
            </div>
          </div>

          {/* WebRTC State */}
          <div className="bg-[#1a1e28] p-3 rounded-2xl border border-[#2b3142] space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-gray-400 flex items-center gap-1.5">
                <Radio className="w-3.5 h-3.5 text-emerald-400" />
                Transport Mode
              </span>
              <span className="text-emerald-400 font-semibold font-mono">
                Direct WebRTC DataChannel
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-gray-400 flex items-center gap-1.5">
                <Layers className="w-3.5 h-3.5 text-indigo-400" />
                Active Lanes
              </span>
              <span className="font-mono text-gray-300">
                veil-ctrl (0), veil-msg (1)
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-gray-400">Signaling Hygiene</span>
              <span className="text-indigo-300 font-semibold">
                Ephemeral Zero-Knowledge
              </span>
            </div>
          </div>

          {/* Security & Cryptography */}
          <div className="bg-[#1a1e28] p-3 rounded-2xl border border-[#2b3142] space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-gray-400 flex items-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
                Encryption Standard
              </span>
              <span className="font-mono text-gray-200">
                AES-256-GCM / Argon2id
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-gray-400 flex items-center gap-1.5">
                <Key className="w-3.5 h-3.5 text-amber-400" />
                Server Intermediaries
              </span>
              <span className="text-emerald-400 font-bold">
                0 (Pure Serverless P2P)
              </span>
            </div>
          </div>
        </div>

        <div className="mt-6 flex justify-end">
          <button
            onClick={onClose}
            className="w-full py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl font-semibold text-xs transition-colors"
          >
            Close Diagnostics
          </button>
        </div>
      </div>
    </div>
  );
};
