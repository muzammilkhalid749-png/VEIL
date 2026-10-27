import React, { useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { X, Smartphone, ArrowRight, ShieldCheck, CheckCircle2 } from 'lucide-react';
import { useAuthStore } from '../../store/useAuthStore';
import { useChatStore } from '../../store/useChatStore';

interface PairingModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PairingModal: React.FC<PairingModalProps> = ({ isOpen, onClose }) => {
  const { userId, publicKey, isPairedWithPhone, pairWithPhone, pairedPhoneId } = useAuthStore();
  const { connectToPeer } = useChatStore();
  const [manualPhoneId, setManualPhoneId] = useState('');
  const [isConnecting, setIsConnecting] = useState(false);

  if (!isOpen) return null;

  const pairingPayload = JSON.stringify({
    app: 'VEIL',
    type: 'companion_pairing',
    webPeerId: userId,
    publicKey: publicKey,
    timestamp: Date.now(),
  });

  const handleManualPair = async () => {
    if (!manualPhoneId.trim()) return;
    setIsConnecting(true);
    try {
      const cleanId = manualPhoneId.trim();
      pairWithPhone(cleanId);
      await connectToPeer(cleanId);
      onClose();
    } finally {
      setIsConnecting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-[#15171e] border border-[#252a37] rounded-3xl w-full max-w-lg p-6 shadow-2xl relative text-gray-200">
        <button
          onClick={onClose}
          className="absolute top-5 right-5 text-gray-400 hover:text-white p-1 rounded-lg"
        >
          <X className="w-5 h-5" />
        </button>

        <div className="text-center mb-6">
          <div className="w-12 h-12 rounded-2xl bg-indigo-600/20 text-indigo-400 mx-auto flex items-center justify-center mb-3 border border-indigo-500/30">
            <Smartphone className="w-6 h-6" />
          </div>
          <h2 className="text-xl font-bold text-white tracking-wide">
            {isPairedWithPhone ? 'Phone Linked Successfully' : 'Link Your VEIL Android App'}
          </h2>
          <p className="text-xs text-gray-400 mt-1">
            Establish a zero-knowledge, encrypted WebRTC bridge directly with your phone.
          </p>
        </div>

        {isPairedWithPhone ? (
          <div className="flex flex-col items-center py-6 text-center space-y-3">
            <CheckCircle2 className="w-14 h-14 text-emerald-400" />
            <div className="text-sm font-semibold text-white">Active P2P Companion Channel</div>
            <div className="text-xs text-gray-400 font-mono bg-[#1c202a] px-3 py-1.5 rounded-lg border border-[#2b3040]">
              Paired Peer ID: {pairedPhoneId}
            </div>
            <p className="text-xs text-gray-400 max-w-xs">
              Your messages and outbox sync directly with your phone without passing through any central server.
            </p>
          </div>
        ) : (
          <div className="space-y-6">
            {/* QR Code Container */}
            <div className="flex flex-col items-center justify-center">
              <div className="p-4 bg-white rounded-2xl shadow-xl">
                <QRCodeSVG
                  value={pairingPayload}
                  size={200}
                  level="M"
                  includeMargin={false}
                />
              </div>
              <span className="text-xs text-gray-400 mt-3 flex items-center gap-1.5">
                <ShieldCheck className="w-4 h-4 text-emerald-400" />
                Point your VEIL Android camera to scan
              </span>
            </div>

            {/* Manual Pair Fallback */}
            <div className="border-t border-[#232734] pt-4">
              <label className="block text-xs font-medium text-gray-400 mb-1.5">
                Or enter Phone Peer ID manually:
              </label>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={manualPhoneId}
                  onChange={(e) => setManualPhoneId(e.target.value)}
                  placeholder="e.g. user_987654321..."
                  className="flex-1 bg-[#1a1d26] text-xs text-white px-3 py-2 rounded-xl border border-[#2b3040] focus:border-indigo-500 focus:outline-none"
                />
                <button
                  onClick={handleManualPair}
                  disabled={isConnecting}
                  className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold rounded-xl flex items-center gap-1 transition-all"
                >
                  <span>Connect</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
