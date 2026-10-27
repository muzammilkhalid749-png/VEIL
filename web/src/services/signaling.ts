import { 
  collection, 
  doc, 
  setDoc, 
  deleteDoc, 
  onSnapshot, 
  getDocs, 
  limit, 
  query 
} from 'firebase/firestore';
import { db } from './firebase';
import { nostrSync } from './nostrSync';

export interface SignalPacket {
  signalId: string;
  type: 
    | 'offer' 
    | 'answer' 
    | 'candidate' 
    | 'wake_up' 
    | 'peer_online' 
    | 'call_invite' 
    | 'call_ringing' 
    | 'call_accept' 
    | 'call_reject' 
    | 'call_end' 
    | 'p2p_action';
  from: string;
  timestamp: number;
  encrypted: boolean;
  sdp?: string;
  sdpMid?: string;
  sdpMLineIndex?: number;
  callId?: string;
  callerName?: string;
  callType?: 'audio' | 'video';
  reason?: string;
  chatId?: string;
  payload?: any;
}

export type SignalHandler = (signal: SignalPacket) => void;

class SignalingService {
  private currentUserId: string | null = null;
  private secondaryPeerId: string | null = null;
  private unsubscribeListeners: (() => void)[] = [];
  private signalHandlers: Set<SignalHandler> = new Set();
  private processedSignalIds: Set<string> = new Set();

  public init(userId: string, secondaryPeerId?: string) {
    if (!userId) return;
    this.currentUserId = userId.trim();
    if (secondaryPeerId) {
      this.secondaryPeerId = secondaryPeerId.trim();
    }
    this.startListening();
  }

  public setSecondaryPeerId(peerId: string) {
    this.secondaryPeerId = peerId.trim();
    this.startListening();
  }

  public registerHandler(handler: SignalHandler): () => void {
    this.signalHandlers.add(handler);
    return () => this.signalHandlers.delete(handler);
  }

  private startListening() {
    // Teardown any existing active listeners
    this.unsubscribeListeners.forEach((unsub) => unsub());
    this.unsubscribeListeners = [];

    if (!this.currentUserId) return;

    const targetIdsToListen = new Set<string>();
    targetIdsToListen.add(this.currentUserId);
    if (this.secondaryPeerId) {
      targetIdsToListen.add(this.secondaryPeerId);
    }

    // 1. Primary Decentralized Fast-Path: Nostr Relay Pool
    targetIdsToListen.forEach((targetId) => {
      nostrSync.subscribeToCollection(`users/${targetId}/signals`, (_docId, rawData) => {
        const sigId = rawData.signalId || _docId;
        if (this.processedSignalIds.has(sigId)) return;
        this.processedSignalIds.add(sigId);

        // Max age 90s filter
        const now = Date.now();
        if (rawData.timestamp && now - rawData.timestamp > 90_000) {
          return;
        }

        const packet: SignalPacket = {
          signalId: sigId,
          type: rawData.type || 'p2p_action',
          from: rawData.from || 'peer',
          timestamp: rawData.timestamp || Date.now(),
          encrypted: !!rawData.encrypted,
          sdp: rawData.sdp,
          sdpMid: rawData.sdpMid,
          sdpMLineIndex: rawData.sdpMLineIndex,
          callId: rawData.callId,
          callerName: rawData.callerName,
          callType: rawData.callType,
          reason: rawData.reason,
          chatId: rawData.chatId,
          payload: rawData.payload,
        };

        this.signalHandlers.forEach((handler) => {
          try {
            handler(packet);
          } catch (err) {
            console.error('[SignalingService] Handler error:', err);
          }
        });
      }).then((unsub) => {
        this.unsubscribeListeners.push(unsub);
      });
    });

    // 2. Secondary Fallback: Firestore onSnapshot (if available)
    const firestore = db;
    if (firestore) {
      targetIdsToListen.forEach((targetId) => {
        try {
          const signalsCol = collection(firestore, 'users', targetId, 'signals');
          this.purgeStaleSignals(targetId);

          const unsub = onSnapshot(signalsCol, (snapshot) => {
            snapshot.docChanges().forEach(async (change) => {
              if (change.type === 'added' || change.type === 'modified') {
                const docRef = change.doc.ref;
                const data = change.doc.data() as SignalPacket;

                try {
                  await deleteDoc(docRef);
                } catch (ignored) {}

                const sigId = data.signalId || change.doc.id;
                if (this.processedSignalIds.has(sigId)) {
                  return;
                }
                this.processedSignalIds.add(sigId);

                const now = Date.now();
                if (data.timestamp && now - data.timestamp > 90_000) {
                  return;
                }

                this.signalHandlers.forEach((handler) => {
                  try {
                    handler(data);
                  } catch (err) {
                    console.error('[SignalingService] Handler error:', err);
                  }
                });
              }
            });
          });

          this.unsubscribeListeners.push(unsub);
        } catch (ignored) {}
      });
    }
  }

  public async sendSignal(toUserId: string, signalData: Partial<SignalPacket>): Promise<void> {
    if (!this.currentUserId || !toUserId) {
      console.warn('[SignalingService] Cannot send signal: uninitialized or missing peer');
      return;
    }

    const signalId = crypto.randomUUID();
    const packet: SignalPacket = {
      signalId,
      type: signalData.type || 'p2p_action',
      from: this.secondaryPeerId || this.currentUserId,
      timestamp: Date.now(),
      encrypted: false,
      ...signalData,
    };

    // 1. Primary Decentralized Dispatch via Nostr Relays (Instant & Quota-Proof)
    nostrSync.publishDocument(`users/${toUserId}/signals/${signalId}`, packet).catch((err) => {
      console.debug('[SignalingService] Nostr signal notice:', err);
    });

    // 2. Secondary Firestore Dispatch (Parallel Fallback)
    if (db) {
      try {
        const targetDocRef = doc(db, 'users', toUserId, 'signals', signalId);
        setDoc(targetDocRef, packet).catch(() => {});

        setTimeout(async () => {
          try {
            await deleteDoc(targetDocRef);
          } catch (ignored) {}
        }, 45_000);
      } catch (ignored) {}
    }
  }

  public async purgeStaleSignals(targetId?: string): Promise<void> {
    const uid = targetId || this.currentUserId;
    if (!db || !uid) return;
    try {
      const q = query(collection(db, 'users', uid, 'signals'), limit(30));
      const snap = await getDocs(q);
      const cutoff = Date.now() - 90_000;
      snap.forEach(async (d) => {
        const data = d.data();
        if (!data.timestamp || data.timestamp < cutoff) {
          try {
            await deleteDoc(d.ref);
          } catch (ignored) {}
        }
      });
    } catch (ignored) {}
  }

  public disconnect() {
    this.unsubscribeListeners.forEach((unsub) => unsub());
    this.unsubscribeListeners = [];
    this.currentUserId = null;
    this.secondaryPeerId = null;
    this.processedSignalIds.clear();
  }
}

export const signalingService = new SignalingService();
