import React from 'react';
import { Check, CheckCheck, Clock, Reply, FileText, Download } from 'lucide-react';
import type { Message } from '../../types/chat';

interface MessageBubbleProps {
  message: Message;
  isOutgoing: boolean;
  onReply: (message: Message) => void;
}

export const MessageBubble: React.FC<MessageBubbleProps> = ({
  message,
  isOutgoing,
  onReply,
}) => {
  const formatTime = (ts: number) => {
    return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const formatFileSize = (bytes?: number) => {
    if (!bytes) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const renderStatus = () => {
    if (!isOutgoing) return null;
    switch (message.status) {
      case 'sending':
        return <Clock className="w-3 h-3 text-indigo-200/70 inline ml-1" aria-label="Sending" />;
      case 'sent':
        return <Check className="w-3 h-3 text-indigo-200/90 inline ml-1" aria-label="Sent" />;
      case 'delivered':
        return <CheckCheck className="w-3 h-3 text-indigo-200/90 inline ml-1" aria-label="Delivered" />;
      case 'read':
        return <CheckCheck className="w-3 h-3 text-cyan-300 inline ml-1" aria-label="Read" />;
      default:
        return null;
    }
  };

  return (
    <div
      className={`group flex items-end gap-1.5 my-1 px-4 ${
        isOutgoing ? 'justify-end' : 'justify-start'
      }`}
    >
      {isOutgoing && (
        <button
          onClick={() => onReply(message)}
          aria-label="Reply to message"
          className="opacity-0 group-hover:opacity-100 p-2 text-slate-400 hover:text-white cursor-pointer transition-opacity duration-200 rounded-lg hover:bg-white/5"
          title="Reply"
        >
          <Reply className="w-3.5 h-3.5" />
        </button>
      )}

      {/* Bubble Container */}
      <div
        className={`max-w-[75%] md:max-w-[65%] rounded-2xl px-4 py-2.5 text-sm shadow-md transition-all duration-150 relative ${
          isOutgoing
            ? 'bg-gradient-to-br from-indigo-600 to-indigo-700 text-white rounded-br-xs'
            : 'bg-[#181c26] text-slate-100 border border-[#262c3c] rounded-bl-xs'
        }`}
      >
        {/* Reply Quote Banner */}
        {message.replyToId && message.replyText && (
          <div
            className={`border-l-2 pl-2.5 py-1 mb-2 text-xs rounded-lg bg-black/20 truncate ${
              isOutgoing ? 'border-white/70 text-white/95' : 'border-indigo-400 text-slate-200'
            }`}
          >
            <div className="font-semibold text-[10px] text-indigo-200 truncate">
              {message.replySenderName || 'Original Message'}
            </div>
            <div className="truncate text-[11px] mt-0.5">{message.replyText}</div>
          </div>
        )}

        {/* Media Preview (Image) */}
        {message.type === 'image' && message.mediaUrl && (
          <div className="mb-2 overflow-hidden rounded-xl">
            <img
              src={message.mediaUrl}
              alt="attachment"
              className="max-h-72 w-auto rounded-xl object-cover hover:opacity-95 transition-opacity cursor-pointer"
              onClick={() => window.open(message.mediaUrl, '_blank')}
            />
          </div>
        )}

        {/* Media File Card (File/Doc) */}
        {message.type === 'file' && message.mediaUrl && (
          <div className="flex items-center gap-3 bg-black/25 p-2.5 rounded-xl mb-2 border border-white/10">
            <div className="p-2.5 bg-indigo-500/20 text-indigo-300 rounded-xl">
              <FileText className="w-5 h-5" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-semibold truncate text-white">
                {message.fileName || 'Attachment'}
              </div>
              <div className="text-[10px] text-slate-400 mt-0.5">
                {formatFileSize(message.mediaSize)}
              </div>
            </div>
            <a
              href={message.mediaUrl}
              download={message.fileName || 'file'}
              aria-label={`Download ${message.fileName || 'file'}`}
              className="p-2 rounded-xl bg-white/10 hover:bg-white/20 text-white cursor-pointer transition-colors"
              title="Download file"
            >
              <Download className="w-4 h-4" />
            </a>
          </div>
        )}

        {/* Text Message */}
        <div className="leading-relaxed select-text whitespace-pre-wrap break-words">
          {message.text}
        </div>

        {/* Metadata Footer */}
        <div
          className={`flex items-center justify-end gap-1 mt-1 text-[10px] select-none ${
            isOutgoing ? 'text-indigo-200/80' : 'text-slate-400'
          }`}
        >
          <span>{formatTime(message.timestamp)}</span>
          {renderStatus()}
        </div>
      </div>

      {!isOutgoing && (
        <button
          onClick={() => onReply(message)}
          aria-label="Reply to message"
          className="opacity-0 group-hover:opacity-100 p-2 text-slate-400 hover:text-white cursor-pointer transition-opacity duration-200 rounded-lg hover:bg-white/5"
          title="Reply"
        >
          <Reply className="w-3.5 h-3.5" />
        </button>
      )}
    </div>
  );
};
