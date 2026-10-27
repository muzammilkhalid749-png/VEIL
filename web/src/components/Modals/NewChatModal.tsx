import React, { useState } from 'react';
import { X, UserPlus } from 'lucide-react';
import { useChatStore } from '../../store/useChatStore';

interface NewChatModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const NewChatModal: React.FC<NewChatModalProps> = ({ isOpen, onClose }) => {
  const { addOrUpdateChat, connectToPeer } = useChatStore();
  const [peerId, setPeerId] = useState('');
  const [displayName, setDisplayName] = useState('');

  if (!isOpen) return null;

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!peerId.trim()) return;

    const cleanPeerId = peerId.trim();
    const cleanName = displayName.trim() || `Peer ${cleanPeerId.slice(0, 6)}`;

    addOrUpdateChat({
      id: cleanPeerId,
      username: cleanName.toLowerCase().replace(/\s+/g, '_'),
      displayName: cleanName,
      online: true,
      isMeshNode: false,
    });

    // Attempt direct WebRTC handshake
    connectToPeer(cleanPeerId);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-[#14161e] border border-[#262b3a] rounded-3xl w-full max-w-md p-6 shadow-2xl relative text-gray-200">
        <button
          onClick={onClose}
          className="absolute top-5 right-5 text-gray-400 hover:text-white p-1 rounded-lg"
        >
          <X className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-2.5 mb-5">
          <div className="p-2 rounded-xl bg-indigo-600/20 text-indigo-400">
            <UserPlus className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-white tracking-wide">
              Start Direct P2P Chat
            </h2>
            <p className="text-xs text-gray-400">
              Connect to any peer node by their sovereign Peer ID
            </p>
          </div>
        </div>

        <form onSubmit={handleCreate} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-gray-300 mb-1.5">
              Peer ID *
            </label>
            <input
              type="text"
              required
              value={peerId}
              onChange={(e) => setPeerId(e.target.value)}
              placeholder="e.g. veil_web_9a12c4 or Android User ID"
              className="w-full bg-[#1b1f2a] text-sm text-white px-3.5 py-2.5 rounded-xl border border-[#2a3040] focus:border-indigo-500 focus:outline-none font-mono"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-gray-300 mb-1.5">
              Display Name (Optional)
            </label>
            <input
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="e.g. Alice / Work Phone"
              className="w-full bg-[#1b1f2a] text-sm text-white px-3.5 py-2.5 rounded-xl border border-[#2a3040] focus:border-indigo-500 focus:outline-none"
            />
          </div>

          <div className="pt-2 flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-xs font-semibold text-gray-400 hover:text-white transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl font-semibold text-xs transition-all shadow-md"
            >
              Connect & Open Chat
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
