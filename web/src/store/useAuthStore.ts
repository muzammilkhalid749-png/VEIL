import { create } from 'zustand';
import { doc, onSnapshot, setDoc, deleteDoc, type Unsubscribe } from 'firebase/firestore';
import { db } from '../services/firebase';
import { nostrSync } from '../services/nostrSync';

export interface AuthState {
  isAuthenticated: boolean;
  userId: string;
  username: string;
  displayName: string;
  publicKey: string;
  webPeerId: string;
  isPairedWithPhone: boolean;
  pairedPhoneId?: string;
  pairedAt?: number;
  
  // Pairing Lifecycle State
  pairingSessionId: string;
  pairingPin: string;
  pairingQrPayload: string;
  isQrExpired: boolean;
  pairingStatus: 'idle' | 'waiting' | 'scanned' | 'confirmed' | 'failed';
  
  // Actions
  initializeAuth: () => void;
  generatePairingSession: () => void;
  refreshPairingQr: () => void;
  confirmPairing: (phoneData: { userId: string; username: string; displayName: string; publicKey?: string; webPeerId?: string }) => void;
  pairWithPhone: (phoneUserId: string) => void;
  updateProfile: (username: string, displayName: string) => void;
  loginAsStandalone: (customName?: string) => void;
  loginWithSovereignKey: (userId: string, username: string, displayName: string, privateOrPublicKey?: string) => void;
  logout: () => void;
  panicWipe: () => void;
}

const STORAGE_KEY = 'veil_auth_session_v2';
let sessionListener: Unsubscribe | null = null;
let nostrSessionUnsub: (() => void) | null = null;
let qrExpireTimer: number | null = null;

export const useAuthStore = create<AuthState>((set, get) => ({
  isAuthenticated: false,
  userId: '',
  username: '',
  displayName: '',
  publicKey: '',
  webPeerId: '',
  isPairedWithPhone: false,
  pairedPhoneId: undefined,
  pairedAt: undefined,

  pairingSessionId: '',
  pairingPin: '',
  pairingQrPayload: '',
  isQrExpired: false,
  pairingStatus: 'idle',

  initializeAuth: () => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        if (parsed.isAuthenticated && parsed.userId) {
          set({
            isAuthenticated: true,
            userId: parsed.userId,
            username: parsed.username || 'VeilUser',
            displayName: parsed.displayName || 'Sovereign Node',
            publicKey: parsed.publicKey || '',
            webPeerId: parsed.webPeerId || `veil_web_${crypto.randomUUID().slice(0, 8)}`,
            isPairedWithPhone: !!parsed.isPairedWithPhone,
            pairedPhoneId: parsed.pairedPhoneId,
            pairedAt: parsed.pairedAt,
          });
          return;
        }
      }
    } catch (ignored) {}

    // Not authenticated -> kick off pairing lifecycle
    set({ isAuthenticated: false });
    get().generatePairingSession();
  },

  generatePairingSession: () => {
    // Teardown existing listeners and timer
    if (nostrSessionUnsub) {
      nostrSessionUnsub();
      nostrSessionUnsub = null;
    }
    if (sessionListener) {
      sessionListener();
      sessionListener = null;
    }
    if (qrExpireTimer) {
      clearTimeout(qrExpireTimer);
      qrExpireTimer = null;
    }

    const sessionId = `veil_sess_${crypto.randomUUID()}`;
    const ephemeralKey = `0x${Array.from(crypto.getRandomValues(new Uint8Array(16))).map(b => b.toString(16).padStart(2, '0')).join('')}`;
    const webPeerId = `veil_web_${crypto.randomUUID().slice(0, 8)}`;
    const randomPin = Math.floor(100000 + Math.random() * 900000).toString(); // 6-digit numeric PIN

    // Telegram / WhatsApp pairing URI standard
    const qrPayload = JSON.stringify({
      app: 'VEIL',
      version: '1.0',
      action: 'link_device',
      sessionId: sessionId,
      webPeerId: webPeerId,
      pin: randomPin,
      key: ephemeralKey,
      timestamp: Date.now(),
    });

    set({
      pairingSessionId: sessionId,
      pairingPin: randomPin,
      webPeerId: webPeerId,
      pairingQrPayload: qrPayload,
      isQrExpired: false,
      pairingStatus: 'waiting',
    });

    // 1. Primary real-time listener: Nostr Decentralized Relays (Instant & resilient across mobile cellular)
    nostrSync.subscribeToDocument(`pairing_sessions/${sessionId}`, (data) => {
      console.log('[NostrAuth] Received pairing handshake from mobile:', data);
      if (data.status === 'scanned') {
        set({ pairingStatus: 'scanned' });
      } else if (data.status === 'confirmed' && data.userId) {
        set({ pairingStatus: 'confirmed' });
        
        if (nostrSessionUnsub) {
          nostrSessionUnsub();
          nostrSessionUnsub = null;
        }
        if (sessionListener) {
          sessionListener();
          sessionListener = null;
        }

        get().confirmPairing({
          userId: data.userId,
          username: data.username || 'phone_user',
          displayName: data.displayName || 'VEIL Phone',
          publicKey: data.publicKey || '',
          webPeerId: webPeerId,
        });
      }
    }).then((unsub) => {
      nostrSessionUnsub = unsub;
    });

    // Publish initial pending session to Nostr relay pool
    nostrSync.publishDocument(`pairing_sessions/${sessionId}`, {
      sessionId,
      webPeerId,
      pin: randomPin,
      key: ephemeralKey,
      status: 'waiting',
      createdAt: Date.now(),
      expiresAt: Date.now() + 90_000,
    }).catch(() => {});

    // 2. Secondary listener: Cloud Firestore (parallel fallback)
    if (db) {
      try {
        const sessionDocRef = doc(db, 'pairing_sessions', sessionId);
        
        setDoc(sessionDocRef, {
          sessionId,
          webPeerId,
          pin: randomPin,
          key: ephemeralKey,
          status: 'waiting',
          createdAt: Date.now(),
          expiresAt: Date.now() + 90_000,
        }).catch(() => {});

        sessionListener = onSnapshot(sessionDocRef, async (snap) => {
          if (!snap.exists()) return;
          const data = snap.data();

          if (data.status === 'scanned') {
            set({ pairingStatus: 'scanned' });
          } else if (data.status === 'confirmed' && data.userId) {
            set({ pairingStatus: 'confirmed' });
            
            try {
              await deleteDoc(sessionDocRef);
            } catch (ignored) {}

            get().confirmPairing({
              userId: data.userId,
              username: data.username || `phone_user`,
              displayName: data.displayName || `VEIL Phone`,
              publicKey: data.publicKey || '',
              webPeerId: webPeerId,
            });
          }
        });
      } catch (e) {
        console.warn('[Pairing] Firestore session notice:', e);
      }
    }

    // 3. Set 90-second QR expiry timer (matching Telegram Web)
    qrExpireTimer = window.setTimeout(() => {
      set({ isQrExpired: true, pairingStatus: 'idle' });
      if (nostrSessionUnsub) {
        nostrSessionUnsub();
        nostrSessionUnsub = null;
      }
      if (sessionListener) {
        sessionListener();
        sessionListener = null;
      }
    }, 90_000);
  },

  refreshPairingQr: () => {
    get().generatePairingSession();
  },

  confirmPairing: (phoneData) => {
    if (nostrSessionUnsub) {
      nostrSessionUnsub();
      nostrSessionUnsub = null;
    }
    if (sessionListener) {
      sessionListener();
      sessionListener = null;
    }
    if (qrExpireTimer) {
      clearTimeout(qrExpireTimer);
      qrExpireTimer = null;
    }

    const currentWebPeerId = phoneData.webPeerId || get().webPeerId || `veil_web_${crypto.randomUUID().slice(0, 8)}`;

    const sessionState = {
      isAuthenticated: true,
      userId: phoneData.userId,
      username: phoneData.username,
      displayName: phoneData.displayName,
      publicKey: phoneData.publicKey || '',
      webPeerId: currentWebPeerId,
      isPairedWithPhone: true,
      pairedPhoneId: phoneData.userId,
      pairedAt: Date.now(),
    };

    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessionState));
    set({
      ...sessionState,
      pairingStatus: 'idle',
      pairingSessionId: '',
      pairingPin: '',
      pairingQrPayload: '',
      isQrExpired: false,
    });
  },

  pairWithPhone: (phoneUserId: string) => {
    get().confirmPairing({
      userId: phoneUserId,
      username: `phone_${phoneUserId.slice(0, 6)}`,
      displayName: `Linked Phone (${phoneUserId.slice(0, 6)})`,
    });
  },

  updateProfile: (username: string, displayName: string) => {
    set((state) => {
      const updated = { ...state, username, displayName };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
      return { username, displayName };
    });
  },

  loginAsStandalone: (customName?: string) => {
    if (nostrSessionUnsub) {
      nostrSessionUnsub();
      nostrSessionUnsub = null;
    }
    if (sessionListener) {
      sessionListener();
      sessionListener = null;
    }
    if (qrExpireTimer) {
      clearTimeout(qrExpireTimer);
      qrExpireTimer = null;
    }

    const newId = `veil_web_${crypto.randomUUID().slice(0, 8)}`;
    const randomSuffix = Math.floor(1000 + Math.random() * 9000);
    const sessionState = {
      isAuthenticated: true,
      userId: newId,
      username: `sovereign_${randomSuffix}`,
      displayName: customName || `Veil Web #${randomSuffix}`,
      publicKey: `0x${Array.from(crypto.getRandomValues(new Uint8Array(16))).map(b => b.toString(16).padStart(2, '0')).join('')}`,
      webPeerId: newId,
      isPairedWithPhone: false,
      pairedPhoneId: undefined,
      pairedAt: undefined,
    };

    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessionState));
    set({
      ...sessionState,
      pairingStatus: 'idle',
      pairingSessionId: '',
      pairingPin: '',
      pairingQrPayload: '',
      isQrExpired: false,
    });
  },

  loginWithSovereignKey: (userId, username, displayName, privateOrPublicKey) => {
    if (nostrSessionUnsub) {
      nostrSessionUnsub();
      nostrSessionUnsub = null;
    }
    if (sessionListener) {
      sessionListener();
      sessionListener = null;
    }
    if (qrExpireTimer) {
      clearTimeout(qrExpireTimer);
      qrExpireTimer = null;
    }

    const sessionState = {
      isAuthenticated: true,
      userId,
      username: username || `key_node`,
      displayName: displayName || `Sovereign Key Node`,
      publicKey: privateOrPublicKey || '',
      webPeerId: `veil_web_${userId.replace(/[^a-zA-Z0-9]/g, '').slice(0, 8)}`,
      isPairedWithPhone: false,
      pairedPhoneId: undefined,
      pairedAt: undefined,
    };

    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessionState));
    set({
      ...sessionState,
      pairingStatus: 'idle',
      pairingSessionId: '',
      pairingPin: '',
      pairingQrPayload: '',
      isQrExpired: false,
    });
  },

  logout: () => {
    if (nostrSessionUnsub) {
      nostrSessionUnsub();
      nostrSessionUnsub = null;
    }
    if (sessionListener) {
      sessionListener();
      sessionListener = null;
    }
    if (qrExpireTimer) {
      clearTimeout(qrExpireTimer);
      qrExpireTimer = null;
    }
    localStorage.removeItem(STORAGE_KEY);
    set({
      isAuthenticated: false,
      userId: '',
      username: '',
      displayName: '',
      publicKey: '',
      webPeerId: '',
      isPairedWithPhone: false,
      pairedPhoneId: undefined,
      pairedAt: undefined,
      pairingStatus: 'idle',
    });
    get().generatePairingSession();
  },

  panicWipe: () => {
    if (nostrSessionUnsub) {
      nostrSessionUnsub();
      nostrSessionUnsub = null;
    }
    if (sessionListener) {
      sessionListener();
      sessionListener = null;
    }
    if (qrExpireTimer) {
      clearTimeout(qrExpireTimer);
      qrExpireTimer = null;
    }
    localStorage.clear();
    indexedDB.deleteDatabase('veil_vault_db_v1');
    sessionStorage.clear();
    set({
      isAuthenticated: false,
      userId: '',
      username: '',
      displayName: '',
      publicKey: '',
      webPeerId: '',
      isPairedWithPhone: false,
      pairedPhoneId: undefined,
      pairedAt: undefined,
      pairingStatus: 'idle',
    });
    get().generatePairingSession();
  },
}));
