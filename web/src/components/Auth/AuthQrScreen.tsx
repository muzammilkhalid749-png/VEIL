import React, { useState, useEffect } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { Shield, RefreshCw, Smartphone, KeyRound, Cpu, CheckCircle2, Sparkles, Copy, Check, QrCode, Hash } from 'lucide-react';
import { useAuthStore } from '../../store/useAuthStore';

interface AuthQrScreenProps {
  onSwitchToKeyLogin: () => void;
}

export const AuthQrScreen: React.FC<AuthQrScreenProps> = ({ onSwitchToKeyLogin }) => {
  const {
    pairingQrPayload,
    pairingPin,
    webPeerId,
    isQrExpired,
    pairingStatus,
    refreshPairingQr,
    loginAsStandalone,
  } = useAuthStore();

  const [activeTab, setActiveTab] = useState<'qr' | 'code'>('qr');
  const [copiedPin, setCopiedPin] = useState(false);
  const [copiedPeerId, setCopiedPeerId] = useState(false);
  const [secondsRemaining, setSecondsRemaining] = useState(90);

  // 90s countdown timer
  useEffect(() => {
    if (isQrExpired || pairingStatus === 'confirmed') return;
    setSecondsRemaining(90);

    const interval = setInterval(() => {
      setSecondsRemaining((prev) => {
        if (prev <= 1) {
          clearInterval(interval);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [pairingQrPayload, isQrExpired, pairingStatus]);

  const handleCopyPin = () => {
    if (!pairingPin) return;
    navigator.clipboard.writeText(pairingPin);
    setCopiedPin(true);
    setTimeout(() => setCopiedPin(false), 2000);
  };

  const handleCopyPeerId = () => {
    if (!webPeerId) return;
    navigator.clipboard.writeText(webPeerId);
    setCopiedPeerId(true);
    setTimeout(() => setCopiedPeerId(false), 2000);
  };

  return (
    <div className="min-h-screen w-screen flex flex-col items-center justify-between bg-[#07080c] text-slate-100 p-6 select-none relative overflow-hidden font-['Plus_Jakarta_Sans',sans-serif]">
      {/* Dynamic Aurora Ambient Glows */}
      <div className="absolute top-1/4 left-1/3 -translate-x-1/2 -translate-y-1/2 w-[650px] h-[650px] bg-gradient-to-tr from-indigo-600/15 to-violet-600/15 rounded-full blur-[150px] pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/3 translate-x-1/2 translate-y-1/2 w-[550px] h-[550px] bg-gradient-to-br from-cyan-600/12 to-indigo-600/12 rounded-full blur-[140px] pointer-events-none" />

      {/* Subtle geometric dot grid background */}
      <div className="absolute inset-0 bg-[radial-gradient(rgba(255,255,255,0.06)_1px,transparent_1px)] [background-size:28px_28px] opacity-40 pointer-events-none" />

      {/* Top Header */}
      <header className="w-full max-w-5xl flex items-center justify-between z-10">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-indigo-500 via-indigo-600 to-violet-600 p-0.5 shadow-lg shadow-indigo-600/30">
            <div className="w-full h-full bg-[#0a0c14] rounded-[14px] flex items-center justify-center text-indigo-400">
              <Shield className="w-5 h-5" />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xl font-extrabold tracking-tight text-white">
              VEIL
            </span>
            <span className="text-[10px] font-mono font-bold tracking-wider uppercase px-2 py-0.5 rounded-full bg-gradient-to-r from-indigo-500/20 to-violet-500/20 text-indigo-300 border border-indigo-500/30 shadow-xs">
              Sovereign P2P
            </span>
          </div>
        </div>

        <button
          onClick={() => loginAsStandalone()}
          aria-label="Launch independent web node without phone"
          className="text-xs text-slate-400 hover:text-white transition-all duration-200 flex items-center gap-2 px-3.5 py-2 rounded-xl bg-white/4 hover:bg-white/8 border border-white/6 hover:border-white/12 cursor-pointer shadow-xs"
        >
          <Cpu className="w-3.5 h-3.5 text-indigo-400" />
          <span>Standalone Mode</span>
        </button>
      </header>

      {/* Main Glassmorphic Auth Center Card */}
      <main className="w-full max-w-lg z-10 my-auto py-3 flex flex-col items-center">
        <div className="w-full glass-panel-elevated rounded-3xl p-7 relative overflow-hidden text-center shadow-2xl">
          {/* Subtle top light bar */}
          <div className="absolute top-0 left-1/2 -translate-x-1/2 w-48 h-[2px] bg-gradient-to-r from-transparent via-indigo-400 to-transparent opacity-80" />

          {/* Heading */}
          <div className="flex items-center justify-center gap-1.5 text-xs font-semibold text-indigo-400 mb-2">
            <Sparkles className="w-3.5 h-3.5" />
            <span className="tracking-wide uppercase text-[11px]">Instant Companion Pairing</span>
          </div>
          <h1 className="text-2xl md:text-3xl font-extrabold text-white tracking-tight mb-2">
            Log in to VEIL Web
          </h1>
          <p className="text-xs text-slate-400 max-w-sm mx-auto mb-5 leading-relaxed">
            Link your browser with your VEIL Android app via zero-knowledge WebRTC DataChannels.
          </p>

          {/* Tab Selector: QR Code vs Numeric PIN */}
          <div className="inline-flex items-center p-1 rounded-xl bg-[#0a0c14]/80 border border-white/8 mb-6">
            <button
              onClick={() => setActiveTab('qr')}
              className={`flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-medium transition-all duration-200 cursor-pointer ${
                activeTab === 'qr'
                  ? 'bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-md shadow-indigo-600/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <QrCode className="w-3.5 h-3.5" />
              <span>QR Code</span>
            </button>
            <button
              onClick={() => setActiveTab('code')}
              className={`flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-medium transition-all duration-200 cursor-pointer ${
                activeTab === 'code'
                  ? 'bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-md shadow-indigo-600/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Hash className="w-3.5 h-3.5" />
              <span>PIN &amp; Peer ID</span>
            </button>
          </div>

          {activeTab === 'qr' ? (
            /* QR Code Container with Sci-Fi Targeting Brackets & Laser Scanline */
            <div className="relative inline-block p-4.5 bg-[#0e1017] rounded-3xl border border-white/10 shadow-2xl shadow-indigo-950/60 mb-5 group">
              {/* Corner Targeting Accents */}
              <div className="absolute top-2 left-2 w-4 h-4 border-t-2 border-l-2 border-indigo-500 rounded-tl-lg" />
              <div className="absolute top-2 right-2 w-4 h-4 border-t-2 border-r-2 border-indigo-500 rounded-tr-lg" />
              <div className="absolute bottom-2 left-2 w-4 h-4 border-b-2 border-l-2 border-indigo-500 rounded-bl-lg" />
              <div className="absolute bottom-2 right-2 w-4 h-4 border-b-2 border-r-2 border-indigo-500 rounded-br-lg" />

              {/* Glowing Laser Scanline */}
              {!isQrExpired && pairingStatus === 'waiting' && (
                <div className="animate-scanline rounded-full pointer-events-none" />
              )}

              {/* White QR Inner Plate */}
              <div className="p-3 bg-white rounded-2xl shadow-md relative overflow-hidden">
                <QRCodeSVG
                  value={pairingQrPayload || 'veil://pair'}
                  size={200}
                  level="M"
                  includeMargin={false}
                />

                {/* Center Emblem */}
                <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-10 h-10 bg-white rounded-xl shadow-lg p-1 flex items-center justify-center">
                  <div className="w-full h-full bg-gradient-to-tr from-indigo-600 to-violet-600 rounded-lg flex items-center justify-center text-white shadow-inner">
                    <Shield className="w-4 h-4" />
                  </div>
                </div>
              </div>

              {/* Expired State Overlay */}
              {isQrExpired && (
                <div className="absolute inset-0 bg-[#07080c]/94 backdrop-blur-xs rounded-3xl flex flex-col items-center justify-center p-6 text-white animate-fade-in z-20">
                  <span className="text-sm font-semibold text-slate-200 mb-3">
                    QR Code Expired
                  </span>
                  <button
                    onClick={refreshPairingQr}
                    className="px-5 py-2.5 bg-gradient-to-r from-indigo-600 to-violet-600 hover:from-indigo-500 hover:to-violet-500 text-white rounded-xl text-xs font-bold flex items-center gap-2 shadow-lg shadow-indigo-600/30 cursor-pointer hover:scale-105 active:scale-95 transition-all duration-200"
                  >
                    <RefreshCw className="w-4 h-4" />
                    <span>Generate New QR</span>
                  </button>
                </div>
              )}

              {/* Scanned Progress Overlay */}
              {pairingStatus === 'scanned' && (
                <div className="absolute inset-0 bg-[#07080c]/90 backdrop-blur-xs rounded-3xl flex flex-col items-center justify-center p-6 text-white z-20">
                  <div className="w-10 h-10 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin mb-3" />
                  <span className="text-sm font-bold text-white">Phone Detected</span>
                  <span className="text-xs text-indigo-300 mt-1">Authorizing handshake...</span>
                </div>
              )}

              {/* Confirmed State Overlay */}
              {pairingStatus === 'confirmed' && (
                <div className="absolute inset-0 bg-emerald-950/90 backdrop-blur-xs rounded-3xl flex flex-col items-center justify-center p-6 text-white z-20">
                  <CheckCircle2 className="w-12 h-12 text-emerald-400 mb-2 animate-bounce" />
                  <span className="text-sm font-bold text-white">Pairing Approved!</span>
                  <span className="text-xs text-emerald-300 mt-1">Opening sovereign workspace...</span>
                </div>
              )}
            </div>
          ) : (
            /* Numeric 6-Digit PIN & Peer ID Direct Connection View */
            <div className="w-full bg-[#0a0c13]/80 border border-white/8 rounded-2xl p-5 text-center mb-5 animate-fade-in">
              <span className="text-[11px] font-mono tracking-wider text-slate-400 uppercase">
                6-Digit Pairing PIN
              </span>
              
              <div className="flex items-center justify-center gap-2 my-3">
                {pairingPin ? (
                  pairingPin.split('').map((digit, idx) => (
                    <React.Fragment key={idx}>
                      {idx === 3 && <span className="text-slate-500 mx-1 font-mono text-lg">•</span>}
                      <span className="w-10 h-12 rounded-xl bg-white/6 border border-white/12 flex items-center justify-center text-xl font-mono font-bold text-white shadow-inner">
                        {digit}
                      </span>
                    </React.Fragment>
                  ))
                ) : (
                  <span className="text-sm text-slate-500">Generating PIN...</span>
                )}
              </div>

              <div className="flex items-center justify-center gap-2 mt-4">
                <button
                  onClick={handleCopyPin}
                  className="px-3.5 py-1.5 rounded-lg bg-white/6 hover:bg-white/10 text-xs text-indigo-300 border border-indigo-500/20 hover:border-indigo-500/40 flex items-center gap-1.5 transition-all cursor-pointer"
                >
                  {copiedPin ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copiedPin ? 'PIN Copied!' : 'Copy 6-Digit PIN'}</span>
                </button>
              </div>

              {/* Web Node Peer ID */}
              <div className="mt-5 pt-4 border-t border-white/6 text-left">
                <span className="text-[10px] font-mono uppercase tracking-wider text-slate-500 block mb-1">
                  Browser Node Peer ID
                </span>
                <div className="flex items-center justify-between p-2.5 rounded-xl bg-black/40 border border-white/6">
                  <span className="text-xs font-mono text-indigo-300 truncate max-w-[260px]">
                    {webPeerId || 'veil_web_initializing'}
                  </span>
                  <button
                    onClick={handleCopyPeerId}
                    aria-label="Copy Web Peer ID"
                    className="p-1 rounded-md hover:bg-white/10 text-slate-400 hover:text-white transition-colors cursor-pointer"
                  >
                    {copiedPeerId ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Expiry Progress Bar & Timer */}
          <div className="flex items-center justify-between text-[11px] text-slate-400 max-w-xs mx-auto mb-4 px-1">
            <span className="font-mono">
              Valid for: <strong className="text-indigo-400">{secondsRemaining}s</strong>
            </span>
            <button
              onClick={refreshPairingQr}
              className="flex items-center gap-1 text-slate-400 hover:text-white transition-colors cursor-pointer text-[11px]"
            >
              <RefreshCw className="w-3 h-3" />
              <span>Refresh</span>
            </button>
          </div>

          {/* Stepped Guidance Container */}
          <div className="w-full bg-[#0a0c13]/70 border border-white/6 rounded-2xl p-4 text-left space-y-2.5 text-xs text-slate-300">
            <div className="flex items-center gap-3">
              <span className="w-5 h-5 rounded-full bg-indigo-500/20 text-indigo-300 font-bold flex items-center justify-center text-[10px] shrink-0 border border-indigo-500/40 font-mono">
                1
              </span>
              <span>Open <strong className="text-white font-semibold">VEIL</strong> on your Android phone</span>
            </div>
            <div className="flex items-center gap-3">
              <span className="w-5 h-5 rounded-full bg-indigo-500/20 text-indigo-300 font-bold flex items-center justify-center text-[10px] shrink-0 border border-indigo-500/40 font-mono">
                2
              </span>
              <span>Go to <strong className="text-white font-semibold">Settings</strong> → <strong className="text-white font-semibold">Linked Devices</strong></span>
            </div>
            <div className="flex items-center gap-3">
              <span className="w-5 h-5 rounded-full bg-indigo-500/20 text-indigo-300 font-bold flex items-center justify-center text-[10px] shrink-0 border border-indigo-500/40 font-mono">
                3
              </span>
              <span>{activeTab === 'qr' ? 'Scan the QR code with camera' : 'Enter the 6-digit PIN in the input field'}</span>
            </div>
          </div>

          {/* Alternative Sovereign Key Action */}
          <div className="mt-5 pt-4 border-t border-white/6">
            <button
              onClick={onSwitchToKeyLogin}
              className="text-xs font-bold tracking-wider uppercase text-indigo-400 hover:text-indigo-300 transition-colors flex items-center justify-center gap-2 w-full py-2 cursor-pointer hover:bg-white/4 rounded-xl"
            >
              <KeyRound className="w-4 h-4" />
              <span>Log in with Sovereign Key / User ID</span>
            </button>
          </div>
        </div>
      </main>

      {/* Footer */}
      <footer className="w-full text-center text-xs text-slate-500 z-10 flex items-center justify-center gap-2">
        <Smartphone className="w-4 h-4 text-indigo-400" />
        <span>End-to-End Encrypted WebRTC DataChannels • Zero Central Server</span>
      </footer>
    </div>
  );
};
