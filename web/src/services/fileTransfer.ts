/**
 * Telegram-Grade P2P File Transfer Engine over WebRTC RTCDataChannel
 * Matches P2PFileTransferHelper.java with 16KB chunks and buffered flow control.
 */

export const CHUNK_SIZE = 16384; // 16 KB per chunk

export interface FileTransferProgress {
  transferId: string;
  fileName: string;
  totalBytes: number;
  transferredBytes: number;
  percentage: number;
  status: 'transferring' | 'completed' | 'failed';
}

export type FileReceivedCallback = (
  transferId: string,
  fileName: string,
  mediaType: string,
  fileUrl: string,
  fileSize: number
) => void;

interface InboundTransfer {
  transferId: string;
  fileName: string;
  mediaType: string;
  totalBytes: number;
  totalChunks: number;
  chunks: Map<number, Uint8Array>;
  receivedChunksCount: number;
}

class P2PFileTransferService {
  private inboundTransfers: Map<string, InboundTransfer> = new Map();
  private onFileReceivedListeners: Set<FileReceivedCallback> = new Set();

  public registerOnFileReceived(cb: FileReceivedCallback): () => void {
    this.onFileReceivedListeners.add(cb);
    return () => this.onFileReceivedListeners.delete(cb);
  }

  public async sendFile(
    dataChannel: RTCDataChannel,
    file: File,
    onProgress?: (progress: FileTransferProgress) => void
  ): Promise<string> {
    if (dataChannel.readyState !== 'open') {
      throw new Error('DataChannel is not open for file transfer');
    }

    const transferId = crypto.randomUUID();
    const totalBytes = file.size;
    const totalChunks = Math.ceil(totalBytes / CHUNK_SIZE);

    // 1. Send FILE_START Header
    const startPayload = {
      action: 'FILE_START',
      transferId,
      fileName: file.name,
      fileSize: totalBytes,
      totalChunks,
      mediaType: file.type || 'application/octet-stream',
    };
    dataChannel.send(JSON.stringify(startPayload));

    // 2. Stream chunks with flow-control
    const arrayBuffer = await file.arrayBuffer();
    const uint8View = new Uint8Array(arrayBuffer);

    for (let i = 0; i < totalChunks; i++) {
      // Flow control: wait if buffer exceeds 1MB
      if (dataChannel.bufferedAmount > 1024 * 1024) {
        await new Promise((resolve) => setTimeout(resolve, 30));
      }

      const start = i * CHUNK_SIZE;
      const end = Math.min(start + CHUNK_SIZE, totalBytes);
      const slice = uint8View.subarray(start, end);

      // Base64 encode chunk
      let binary = '';
      for (let b = 0; b < slice.byteLength; b++) {
        binary += String.fromCharCode(slice[b]);
      }
      const base64Chunk = btoa(binary);

      const chunkPayload = {
        action: 'FILE_CHUNK',
        transferId,
        chunkIndex: i,
        chunkData: base64Chunk,
      };

      dataChannel.send(JSON.stringify(chunkPayload));

      const transferredBytes = end;
      onProgress?.({
        transferId,
        fileName: file.name,
        totalBytes,
        transferredBytes,
        percentage: Math.round((transferredBytes / totalBytes) * 100),
        status: i === totalChunks - 1 ? 'completed' : 'transferring',
      });
    }

    // 3. Send FILE_END
    const endPayload = {
      action: 'FILE_END',
      transferId,
      totalChunks,
    };
    dataChannel.send(JSON.stringify(endPayload));

    return transferId;
  }

  public handleInboundPacket(data: any) {
    try {
      const json = typeof data === 'string' ? JSON.parse(data) : data;
      const action = json.action;

      if (action === 'FILE_START') {
        this.inboundTransfers.set(json.transferId, {
          transferId: json.transferId,
          fileName: json.fileName,
          mediaType: json.mediaType || 'application/octet-stream',
          totalBytes: json.fileSize || 0,
          totalChunks: json.totalChunks,
          chunks: new Map(),
          receivedChunksCount: 0,
        });
      } else if (action === 'FILE_CHUNK') {
        const transfer = this.inboundTransfers.get(json.transferId);
        if (!transfer) return;

        // Decode base64
        const binaryString = atob(json.chunkData);
        const bytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
          bytes[i] = binaryString.charCodeAt(i);
        }

        if (!transfer.chunks.has(json.chunkIndex)) {
          transfer.chunks.set(json.chunkIndex, bytes);
          transfer.receivedChunksCount++;
        }
      } else if (action === 'FILE_END') {
        const transfer = this.inboundTransfers.get(json.transferId);
        if (!transfer) return;

        // Reassemble slices into Blob
        const parts: BlobPart[] = [];
        for (let i = 0; i < transfer.totalChunks; i++) {
          const chunk = transfer.chunks.get(i);
          if (chunk) parts.push(chunk as any);
        }

        const blob = new Blob(parts, { type: transfer.mediaType });
        const objectUrl = URL.createObjectURL(blob);

        this.onFileReceivedListeners.forEach((cb) => {
          cb(
            transfer.transferId,
            transfer.fileName,
            transfer.mediaType,
            objectUrl,
            transfer.totalBytes
          );
        });

        this.inboundTransfers.delete(json.transferId);
      }
    } catch (err) {
      console.warn('[P2PFileTransfer] Packet error:', err);
    }
  }
}

export const fileTransferService = new P2PFileTransferService();
