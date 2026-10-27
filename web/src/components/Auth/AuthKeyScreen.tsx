import React, { useState } from 'react';
import { Shield, ArrowLeft, Key, User, Cpu, Sparkles } from 'lucide-react';
import { useAuthStore } from '../../store/useAuthStore';

interface AuthKeyScreenProps {
  onBackToQr: () => void;
}

export const AuthKeyScreen: React.FC<AuthKeyScreenProps> = ({ onBackToQr }) => {
  const { loginWithSovereignKey } = useAuthStore();
  const [userId, setUserId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [secretKey, setSecretKey] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!userId.trim()) return;

    const cleanUid = userId.trim();
    const cleanName = displayName.trim() || `Node_${cleanUid.slice(0, 6)}`;
    const cleanUser = cleanName.toLowerCase().replace(/\s+/g, '_');

    loginWithSovereignKey(cleanUid, cleanUser, cleanName, secretKey);
  };

  return (
    <div className="min-h-screen w-screen flex flex-col items-center justify-between bg-[#07080c] text-slate-100 p-6 select-none relative overflow-hidden font-['Plus_Jakarta_Sans',sans-serif]">
      {/* Dynamic Aurora Ambient Glows */}
      <div className="absolute top-1/4 left-1/3 -translate-x-1/2 -translate-y-1/2 w-[650px] h-[650px] bg-gradient-to-tr from-indigo-600/15 to-violet-600/15 rounded-full blur-[150px] pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/3 translate-x-1/2 translate-y-1/2 w-[550px] h-[550px] bg-gradient-to-br from-cyan-600/12 to-indigo-600/12 rounded-full blur-[140px] pointer-events-none" />

      {/* Top Brand Bar */}
      <header className="w-full max-w-5xl flex items-center justify-between z-10">
        <button
          onClick={onBackToQr}
          aria-label="Back to QR Code Login"
          className="flex items-center gap-2 text-xs font-semibold text-slate-300 hover:text-white transition-all duration-200 px-3.5 py-2 rounded-xl bg-white/4 hover:bg-white/8 border border-white/6 cursor-pointer shadow-xs"
        >
          <ArrowLeft className="w-4 h-4" />
          <span>Back to QR Login</span>
        </button>

        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-500 to-violet-600 p-0.5 shadow-md shadow-indigo-600/25">
            <div className="w-full h-full bg-[#0a0c14] rounded-[10px] flex items-center justify-center text-indigo-400">
              <Shield className="w-4 h-4" />
            </div>
          </div>
          <span className="text-base font-bold text-white tracking-wider">VEIL</span>
        </div>
      </header>

      {/* Center Form Card */}
      <main className="w-full max-w-md glass-panel-elevated rounded-3xl p-8 z-10 my-auto shadow-2xl relative overflow-hidden">
        {/* Subtle top specular accent */}
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-48 h-[2px] bg-gradient-to-r from-transparent via-indigo-400 to-transparent opacity-80" />

        <div className="flex items-center gap-3 mb-6">
          <div className="p-3 rounded-2xl bg-gradient-to-tr from-indigo-500/20 to-violet-500/20 text-indigo-300 border border-indigo-500/30">
            <Key className="w-6 h-6" />
          </div>
          <div>
            <div className="flex items-center gap-1.5 text-xs text-indigo-400 font-semibold mb-0.5">
              <Sparkles className="w-3.5 h-3.5" />
              <span>Direct Node Authentication</span>
            </div>
            <h2 className="text-xl font-extrabold text-white tracking-tight">Sovereign Key Login</h2>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 text-left">
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5">
              Sovereign User ID / Phone ID *
            </label>
            <div className="relative">
              <User className="w-4 h-4 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
              <input
                type="text"
                required
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
                placeholder="e.g. user_987654321 or Android ID"
                className="w-full bg-[#131622] text-xs text-white pl-10 pr-3.5 py-3 rounded-xl border border-white/10 focus:border-indigo-500/70 focus:bg-[#161a29] focus:outline-none font-mono transition-all duration-200"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5">
              Display Name (Optional)
            </label>
            <input
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="e.g. Workstation Node"
              className="w-full bg-[#131622] text-xs text-white px-3.5 py-3 rounded-xl border border-white/10 focus:border-indigo-500/70 focus:bg-[#161a29] focus:outline-none transition-all duration-200"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5">
              Public Key / Token (Optional)
            </label>
            <input
              type="password"
              value={secretKey}
              onChange={(e) => setSecretKey(e.target.value)}
              placeholder="0x..."
              className="w-full bg-[#131622] text-xs text-white px-3.5 py-3 rounded-xl border border-white/10 focus:border-indigo-500/70 focus:bg-[#161a29] focus:outline-none font-mono transition-all duration-200"
            />
          </div>

          <div className="pt-3">
            <button
              type="submit"
              className="w-full py-3.5 bg-gradient-to-r from-indigo-600 via-indigo-600 to-violet-600 hover:from-indigo-500 hover:to-violet-500 text-white rounded-xl font-bold text-xs shadow-lg shadow-indigo-600/30 cursor-pointer hover:scale-[1.02] active:scale-[0.98] transition-all duration-200"
            >
              Authorize & Launch Workspace
            </button>
          </div>
        </form>
      </main>

      {/* Footer */}
      <footer className="w-full text-center text-xs text-slate-500 z-10 flex items-center justify-center gap-1.5">
        <Cpu className="w-3.5 h-3.5 text-indigo-400" />
        <span>Hardware Keystore & Zero-Knowledge E2EE Protected</span>
      </footer>
    </div>
  );
};
