import React, { useState } from 'react';
import { X, Shield, Key, Trash2, Smartphone, Check, LogOut } from 'lucide-react';
import { useAuthStore } from '../../store/useAuthStore';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({ isOpen, onClose }) => {
  const {
    userId,
    username,
    displayName,
    publicKey,
    isPairedWithPhone,
    pairedPhoneId,
    updateProfile,
    logout,
    panicWipe,
  } = useAuthStore();

  const [editName, setEditName] = useState(displayName);
  const [editUser, setEditUser] = useState(username);
  const [savedSuccess, setSavedSuccess] = useState(false);

  if (!isOpen) return null;

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    updateProfile(editUser.trim(), editName.trim());
    setSavedSuccess(true);
    setTimeout(() => setSavedSuccess(false), 2000);
  };

  const handleLogout = () => {
    if (confirm('Log out from this web browser? You will need to scan the QR code on your phone again to reconnect.')) {
      onClose();
      logout();
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-[#14161e] border border-[#262b3a] rounded-3xl w-full max-w-lg p-6 shadow-2xl relative text-gray-200">
        <button
          onClick={onClose}
          className="absolute top-5 right-5 text-gray-400 hover:text-white p-1 rounded-lg"
        >
          <X className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-2.5 mb-6">
          <div className="w-10 h-10 rounded-xl bg-indigo-600/20 text-indigo-400 flex items-center justify-center">
            <Shield className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-white tracking-wide">
              Sovereign Identity & Security
            </h2>
            <p className="text-xs text-gray-400">VEIL Cryptographic Node Profile</p>
          </div>
        </div>

        {/* Identity Details */}
        <form onSubmit={handleSave} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-gray-300 mb-1">Display Name</label>
            <input
              type="text"
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              className="w-full bg-[#1b1f2a] text-sm text-white px-3 py-2 rounded-xl border border-[#2a3040] focus:border-indigo-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="block font-semibold text-gray-300 mb-1">Username Handle</label>
            <input
              type="text"
              value={editUser}
              onChange={(e) => setEditUser(e.target.value)}
              className="w-full bg-[#1b1f2a] text-sm text-white px-3 py-2 rounded-xl border border-[#2a3040] focus:border-indigo-500 focus:outline-none"
            />
          </div>

          <div className="flex justify-end">
            <button
              type="submit"
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl font-semibold flex items-center gap-1.5 transition-all"
            >
              {savedSuccess ? (
                <>
                  <Check className="w-4 h-4 text-emerald-300" />
                  <span>Saved</span>
                </>
              ) : (
                'Save Changes'
              )}
            </button>
          </div>
        </form>

        {/* Cryptographic Keys section */}
        <div className="mt-6 pt-4 border-t border-[#232734] space-y-3 text-xs">
          <div className="flex items-center justify-between text-gray-400">
            <span className="flex items-center gap-1.5">
              <Key className="w-3.5 h-3.5 text-indigo-400" />
              Sovereign Peer ID
            </span>
            <span className="font-mono text-gray-200 select-all">{userId}</span>
          </div>

          <div className="flex items-center justify-between text-gray-400">
            <span>Public Identity Key</span>
            <span className="font-mono text-[11px] text-gray-400 select-all">{publicKey.slice(0, 18)}...</span>
          </div>

          {isPairedWithPhone && (
            <div className="bg-[#1b1f2a] p-3 rounded-2xl border border-[#2a3040] flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Smartphone className="w-4 h-4 text-emerald-400" />
                <div>
                  <div className="font-semibold text-white">Linked Android Companion</div>
                  <div className="text-[10px] text-gray-400 font-mono">{pairedPhoneId}</div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Session Management Actions */}
        <div className="mt-6 pt-4 border-t border-[#232734] space-y-2">
          {/* Telegram-style Log Out Button */}
          <button
            onClick={handleLogout}
            className="w-full py-2.5 px-4 bg-white/5 hover:bg-white/10 text-gray-300 hover:text-white border border-[#2a3040] rounded-xl font-semibold text-xs flex items-center justify-center gap-2 transition-colors"
          >
            <LogOut className="w-4 h-4 text-amber-400" />
            <span>Log Out (Unlink Desktop Device)</span>
          </button>

          {/* Panic Wipe Button */}
          <button
            onClick={() => {
              if (confirm('CAUTION: Panic Wipe will instantly destroy all local cryptographic session keys, paired companion bridges, and local cache. Continue?')) {
                panicWipe();
              }
            }}
            className="w-full py-2.5 px-4 bg-red-600/10 hover:bg-red-600/20 text-red-400 border border-red-500/20 rounded-xl font-semibold text-xs flex items-center justify-center gap-2 transition-colors"
          >
            <Trash2 className="w-4 h-4" />
            <span>Panic Wipe (Destroy All Local Identity & Sessions)</span>
          </button>
        </div>
      </div>
    </div>
  );
};
