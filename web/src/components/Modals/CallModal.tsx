import React, { useState, useEffect, useRef } from 'react';
import { PhoneOff, Phone, Mic, MicOff, Video, VideoOff, Shield } from 'lucide-react';
import { useChatStore } from '../../store/useChatStore';

export const CallModal: React.FC = () => {
  const { activeCall, localStream, remoteStream, answerCall, endCall } = useChatStore();
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoEnabled, setIsVideoEnabled] = useState(activeCall?.type === 'video');
  const [duration, setDuration] = useState(0);

  const localVideoRef = useRef<HTMLVideoElement>(null);
  const remoteVideoRef = useRef<HTMLVideoElement>(null);
  const remoteAudioRef = useRef<HTMLAudioElement>(null);

  // Bind local media stream to local video element
  useEffect(() => {
    if (localVideoRef.current && localStream) {
      localVideoRef.current.srcObject = localStream;
    }
  }, [localStream]);

  // Bind remote media stream to remote video / audio elements
  useEffect(() => {
    if (remoteStream) {
      if (remoteVideoRef.current) {
        remoteVideoRef.current.srcObject = remoteStream;
      }
      if (remoteAudioRef.current) {
        remoteAudioRef.current.srcObject = remoteStream;
      }
    }
  }, [remoteStream]);

  // Call duration timer
  useEffect(() => {
    if (!activeCall || activeCall.status !== 'connected') return;
    const interval = setInterval(() => {
      setDuration((d) => d + 1);
    }, 1000);
    return () => clearInterval(interval);
  }, [activeCall]);

  if (!activeCall) return null;

  const toggleMute = () => {
    if (localStream) {
      localStream.getAudioTracks().forEach((track) => {
        track.enabled = !track.enabled;
      });
      setIsMuted(!isMuted);
    }
  };

  const toggleVideo = () => {
    if (localStream) {
      localStream.getVideoTracks().forEach((track) => {
        track.enabled = !track.enabled;
      });
      setIsVideoEnabled(!isVideoEnabled);
    }
  };

  const formatDuration = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/90 backdrop-blur-md flex items-center justify-center p-4">
      {/* Hidden audio element for remote audio stream */}
      <audio ref={remoteAudioRef} autoPlay />

      <div className="bg-[#12141a] border border-[#262b39] rounded-3xl w-full max-w-lg p-6 shadow-2xl flex flex-col items-center text-center text-gray-200 relative overflow-hidden">
        {/* Security Badge */}
        <div className="flex items-center gap-1.5 text-xs bg-indigo-500/10 text-indigo-400 px-3 py-1 rounded-full border border-indigo-500/20 mb-4 font-mono">
          <Shield className="w-3.5 h-3.5" />
          <span>E2E Encrypted WebRTC Stream</span>
        </div>

        {/* Video Streams Container (if Video Call) */}
        {activeCall.type === 'video' && (
          <div className="w-full h-64 bg-black/60 rounded-2xl mb-4 relative overflow-hidden border border-[#282d3d] flex items-center justify-center">
            {remoteStream ? (
              <video
                ref={remoteVideoRef}
                autoPlay
                playsInline
                className="w-full h-full object-cover"
              />
            ) : (
              <div className="text-xs text-gray-500 flex flex-col items-center gap-2">
                <Video className="w-8 h-8 text-indigo-400/40" />
                <span>Waiting for remote camera stream...</span>
              </div>
            )}

            {/* Picture-in-Picture Local Video */}
            {localStream && (
              <div className="absolute bottom-2 right-2 w-28 h-20 rounded-xl overflow-hidden border-2 border-indigo-500/50 shadow-lg bg-black">
                <video
                  ref={localVideoRef}
                  autoPlay
                  playsInline
                  muted
                  className="w-full h-full object-cover"
                />
              </div>
            )}
          </div>
        )}

        {/* Peer Avatar when video is not active */}
        {activeCall.type === 'audio' && (
          <div className="relative mb-4">
            <div className="w-24 h-24 rounded-full bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center font-bold text-3xl text-white shadow-2xl animate-pulse">
              {activeCall.peerName.slice(0, 2).toUpperCase()}
            </div>
          </div>
        )}

        <h3 className="text-xl font-bold text-white tracking-wide">{activeCall.peerName}</h3>
        <p className="text-sm text-gray-400 mt-1 font-mono">
          {activeCall.status === 'ringing'
            ? activeCall.direction === 'incoming'
              ? 'Incoming Encrypted Call...'
              : 'Calling P2P Node...'
            : formatDuration(duration)}
        </p>

        {/* Action Controls */}
        <div className="flex items-center gap-4 mt-8">
          {activeCall.status === 'ringing' && activeCall.direction === 'incoming' ? (
            <>
              <button
                onClick={answerCall}
                className="p-4 rounded-full bg-emerald-600 hover:bg-emerald-500 text-white shadow-lg hover:scale-105 active:scale-95 transition-all flex items-center gap-2 px-6"
                title="Answer Call"
              >
                <Phone className="w-5 h-5" />
                <span className="text-xs font-semibold">Answer</span>
              </button>

              <button
                onClick={endCall}
                className="p-4 rounded-full bg-red-600 hover:bg-red-500 text-white shadow-lg hover:scale-105 active:scale-95 transition-all flex items-center gap-2 px-6"
                title="Decline"
              >
                <PhoneOff className="w-5 h-5" />
                <span className="text-xs font-semibold">Decline</span>
              </button>
            </>
          ) : (
            <>
              <button
                onClick={toggleMute}
                className={`p-3.5 rounded-full transition-all ${
                  isMuted ? 'bg-red-500/20 text-red-400 border border-red-500/30' : 'bg-[#1e2330] hover:bg-[#282e40] text-gray-200'
                }`}
                title={isMuted ? 'Unmute' : 'Mute'}
              >
                {isMuted ? <MicOff className="w-5 h-5" /> : <Mic className="w-5 h-5" />}
              </button>

              {activeCall.type === 'video' && (
                <button
                  onClick={toggleVideo}
                  className={`p-3.5 rounded-full transition-all ${
                    !isVideoEnabled ? 'bg-red-500/20 text-red-400 border border-red-500/30' : 'bg-[#1e2330] hover:bg-[#282e40] text-gray-200'
                  }`}
                  title={isVideoEnabled ? 'Turn camera off' : 'Turn camera on'}
                >
                  {isVideoEnabled ? <Video className="w-5 h-5" /> : <VideoOff className="w-5 h-5" />}
                </button>
              )}

              <button
                onClick={endCall}
                className="p-3.5 rounded-full bg-red-600 hover:bg-red-500 text-white shadow-lg hover:scale-105 active:scale-95 transition-all"
                title="End Call"
              >
                <PhoneOff className="w-5 h-5" />
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
