import React, { useState, useRef, useEffect } from 'react';
import { Send, Paperclip, Smile, Mic, X } from 'lucide-react';
import type { Message } from '../../types/chat';
import { webrtcEngine } from '../../services/webrtc';
import { useChatStore } from '../../store/useChatStore';

interface MessageInputProps {
  peerId: string;
  replyTo?: Message;
  onClearReply: () => void;
  onSend: (text: string, replyTo?: Message) => void;
}

export const MessageInput: React.FC<MessageInputProps> = ({
  peerId,
  replyTo,
  onClearReply,
  onSend,
}) => {
  const [text, setText] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const typingTimeoutRef = useRef<number | null>(null);
  const sendFileMessage = useChatStore((s) => s.sendFileMessage);

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 120)}px`;
    }
  }, [text]);

  const handleTextChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const val = e.target.value;
    setText(val);

    webrtcEngine.sendTyping(peerId, true);
    if (typingTimeoutRef.current) clearTimeout(typingTimeoutRef.current);
    typingTimeoutRef.current = window.setTimeout(() => {
      webrtcEngine.sendTyping(peerId, false);
    }, 2000);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const handleSubmit = () => {
    if (!text.trim()) return;
    onSend(text.trim(), replyTo);
    setText('');
    onClearReply();
    webrtcEngine.sendTyping(peerId, false);
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      sendFileMessage(file);
      e.target.value = '';
    }
  };

  return (
    <div className="p-3 bg-[#11131a]/95 backdrop-blur-md border-t border-[#1e2230] shrink-0">
      {/* Hidden File Input */}
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={handleFileChange}
      />

      {/* Reply Banner */}
      {replyTo && (
        <div className="flex items-center justify-between bg-[#191d28] border-l-2 border-indigo-500 px-3 py-1.5 rounded-xl mb-2 text-xs">
          <div className="flex flex-col truncate">
            <span className="font-semibold text-indigo-400">
              Replying to {replyTo.senderName || 'Peer'}
            </span>
            <span className="text-slate-300 truncate">{replyTo.text}</span>
          </div>
          <button
            onClick={onClearReply}
            aria-label="Cancel reply"
            className="text-slate-400 hover:text-white p-1.5 cursor-pointer rounded-lg hover:bg-white/5 transition-colors"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      {/* Input Composer Bar */}
      <div className="flex items-end gap-1.5 bg-[#171a24] rounded-2xl p-1.5 border border-[#232836] focus-within:border-indigo-500/60 focus-within:ring-2 focus-within:ring-indigo-500/20 transition-all duration-200 shadow-inner">
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          aria-label="Attach File or Image"
          className="w-10 h-10 flex items-center justify-center text-slate-400 hover:text-indigo-400 hover:bg-[#1f2432] cursor-pointer transition-all duration-200 rounded-xl"
          title="Send File or Image (P2P Stream)"
        >
          <Paperclip className="w-5 h-5" />
        </button>

        <textarea
          ref={textareaRef}
          value={text}
          onChange={handleTextChange}
          onKeyDown={handleKeyDown}
          aria-label="Write an encrypted message"
          placeholder="Write an encrypted message..."
          rows={1}
          className="flex-1 bg-transparent text-sm text-slate-100 placeholder-slate-400 resize-none max-h-32 focus:outline-none py-2 px-1 leading-relaxed"
        />

        <button
          type="button"
          aria-label="Insert Emoji"
          className="w-10 h-10 flex items-center justify-center text-slate-400 hover:text-slate-200 hover:bg-[#1f2432] cursor-pointer transition-all duration-200 rounded-xl"
          title="Emoji"
        >
          <Smile className="w-5 h-5" />
        </button>

        {text.trim() ? (
          <button
            onClick={handleSubmit}
            aria-label="Send message"
            className="w-10 h-10 flex items-center justify-center bg-gradient-to-tr from-indigo-600 to-violet-500 hover:from-indigo-500 hover:to-violet-400 text-white rounded-xl shadow-md cursor-pointer hover:scale-105 active:scale-95 transition-all duration-200"
            title="Send"
          >
            <Send className="w-4 h-4" />
          </button>
        ) : (
          <button
            type="button"
            aria-label="Record Voice Note"
            className="w-10 h-10 flex items-center justify-center text-slate-400 hover:text-white hover:bg-[#1f2432] cursor-pointer transition-all duration-200 rounded-xl"
            title="Voice Note"
          >
            <Mic className="w-5 h-5" />
          </button>
        )}
      </div>
    </div>
  );
};
