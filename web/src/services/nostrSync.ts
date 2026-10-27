import { finalizeEvent } from 'nostr-tools';

export interface NostrDocumentWrapper {
  path: string;
  col: string;
  id: string;
  ts: number;
  deleted: boolean;
  fields?: Record<string, any>;
  data?: Record<string, any>;
}

export type DocumentListener = (data: Record<string, any>) => void;
export type CollectionListener = (docId: string, data: Record<string, any>) => void;

const NOSTR_RELAYS = [
  'wss://relay.primal.net',
  'wss://relay.damus.io',
  'wss://nos.lol',
];

class NostrSyncService {
  private sockets: Map<string, WebSocket> = new Map();
  private activeDocSubs: Map<string, Set<DocumentListener>> = new Map();
  private activeColSubs: Map<string, Set<CollectionListener>> = new Map();
  private registeredTags: Set<string> = new Set();
  private reconnectTimers: Map<string, number> = new Map();
  private isDestroyed = false;

  constructor() {
    this.connectRelays();
  }

  public connectRelays() {
    this.isDestroyed = false;
    NOSTR_RELAYS.forEach((url) => this.connectSocket(url));
  }

  private connectSocket(url: string) {
    if (this.isDestroyed) return;
    if (this.sockets.has(url)) {
      const existing = this.sockets.get(url);
      if (existing && (existing.readyState === WebSocket.OPEN || existing.readyState === WebSocket.CONNECTING)) {
        return;
      }
    }

    try {
      const ws = new WebSocket(url);

      ws.onopen = () => {
        console.log(`[NostrSync] Connected to relay: ${url}`);
        this.sockets.set(url, ws);
        // Resubscribe to all active tags
        this.registeredTags.forEach((tag) => {
          this.sendSubRequest(ws, tag);
        });
      };

      ws.onmessage = (event) => {
        this.handleRelayMessage(event.data);
      };

      ws.onerror = (err) => {
        console.debug(`[NostrSync] Relay notice (${url}):`, err);
      };

      ws.onclose = () => {
        this.sockets.delete(url);
        if (!this.isDestroyed) {
          const timer = window.setTimeout(() => this.connectSocket(url), 3000);
          this.reconnectTimers.set(url, timer);
        }
      };
    } catch (e) {
      console.debug(`[NostrSync] Could not open WebSocket to ${url}:`, e);
    }
  }

  private sendSubRequest(ws: WebSocket, tag: string) {
    if (ws.readyState !== WebSocket.OPEN) return;
    const subId = `sub_${tag.slice(0, 16)}`;
    const req = JSON.stringify(['REQ', subId, { kinds: [30078], '#t': [tag], limit: 50 }]);
    try {
      ws.send(req);
    } catch (ignored) {}
  }

  private handleRelayMessage(rawText: string) {
    if (!rawText || !rawText.startsWith('["EVENT"')) return;
    try {
      const arr = JSON.parse(rawText);
      if (!Array.isArray(arr) || arr.length < 3) return;
      const event = arr[2];
      if (!event || !event.content || typeof event.content !== 'string') return;

      const wrapper = JSON.parse(event.content) as NostrDocumentWrapper;
      const normPath = (wrapper.path || '').replace(/^\/+|\/+$/g, '').trim();
      const docId = wrapper.id || normPath.split('/').pop() || '';
      const normCol = wrapper.col || normPath.split('/').slice(0, -1).join('/');

      // Parse fields or raw data
      const data: Record<string, any> = {};
      if (wrapper.fields) {
        for (const [k, v] of Object.entries(wrapper.fields)) {
          if (v && typeof v === 'object') {
            if (v.stringValue !== undefined) data[k] = v.stringValue;
            else if (v.booleanValue !== undefined) data[k] = v.booleanValue;
            else if (v.integerValue !== undefined) data[k] = Number(v.integerValue);
            else if (v.doubleValue !== undefined) data[k] = Number(v.doubleValue);
            else if (v.mapValue && v.mapValue.fields) data[k] = v.mapValue.fields;
            else data[k] = v;
          } else {
            data[k] = v;
          }
        }
      } else if (wrapper.data) {
        Object.assign(data, wrapper.data);
      }

      // Notify document subscribers
      const docListeners = this.activeDocSubs.get(normPath);
      if (docListeners) {
        docListeners.forEach((listener) => {
          try {
            listener(data);
          } catch (e) {
            console.error('[NostrSync] Doc listener error:', e);
          }
        });
      }

      // Notify collection subscribers
      const colListeners = this.activeColSubs.get(normCol);
      if (colListeners) {
        colListeners.forEach((listener) => {
          try {
            listener(docId, data);
          } catch (e) {
            console.error('[NostrSync] Col listener error:', e);
          }
        });
      }
    } catch (ignored) {}
  }

  public async subscribeToDocument(docPath: string, listener: DocumentListener): Promise<() => void> {
    const normPath = docPath.replace(/^\/+|\/+$/g, '').trim();
    if (!this.activeDocSubs.has(normPath)) {
      this.activeDocSubs.set(normPath, new Set());
    }
    this.activeDocSubs.get(normPath)!.add(listener);

    const docTag = await this.documentTagFor(normPath);
    this.registeredTags.add(docTag);

    // Send REQ to all active sockets
    this.sockets.forEach((ws) => {
      this.sendSubRequest(ws, docTag);
    });

    return () => {
      const set = this.activeDocSubs.get(normPath);
      if (set) {
        set.delete(listener);
        if (set.size === 0) {
          this.activeDocSubs.delete(normPath);
        }
      }
    };
  }

  public async subscribeToCollection(colPath: string, listener: CollectionListener): Promise<() => void> {
    const normCol = colPath.replace(/^\/+|\/+$/g, '').trim();
    if (!this.activeColSubs.has(normCol)) {
      this.activeColSubs.set(normCol, new Set());
    }
    this.activeColSubs.get(normCol)!.add(listener);

    const colTag = await this.collectionTagFor(normCol);
    this.registeredTags.add(colTag);

    // Send REQ to all active sockets
    this.sockets.forEach((ws) => {
      this.sendSubRequest(ws, colTag);
    });

    return () => {
      const set = this.activeColSubs.get(normCol);
      if (set) {
        set.delete(listener);
        if (set.size === 0) {
          this.activeColSubs.delete(normCol);
        }
      }
    };
  }

  public async publishDocument(docPath: string, data: Record<string, any>, isDeleted = false): Promise<void> {
    const normPath = docPath.replace(/^\/+|\/+$/g, '').trim();
    const normCol = normPath.split('/').slice(0, -1).join('/');
    const docId = normPath.split('/').pop() || '';

    const colTag = await this.collectionTagFor(normCol);
    const docTag = await this.documentTagFor(normPath);

    // Derive deterministic 32-byte secret key matching Android
    const seckey = await this.deriveSecretKey(`veil_fs_doc_v3:${normPath}`);

    // Convert data to Firestore REST fields schema
    const fields: Record<string, any> = {};
    for (const [k, v] of Object.entries(data)) {
      fields[k] = this.toFirestoreValue(v);
    }

    const wrapper: NostrDocumentWrapper = {
      path: normPath,
      col: normCol,
      id: docId,
      ts: Date.now(),
      deleted: isDeleted,
      fields,
    };

    const event = finalizeEvent({
      kind: 30078,
      created_at: Math.floor(Date.now() / 1000),
      tags: [
        ['d', normPath],
        ['t', colTag],
        ['t', docTag],
      ],
      content: JSON.stringify(wrapper),
    }, seckey);

    const wireMsg = JSON.stringify(['EVENT', event]);
    this.sockets.forEach((ws) => {
      if (ws.readyState === WebSocket.OPEN) {
        try {
          ws.send(wireMsg);
        } catch (ignored) {}
      }
    });
  }

  public async sha256Hex(str: string): Promise<string> {
    const buf = new TextEncoder().encode(str);
    const digest = await crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }

  public async documentTagFor(path: string): Promise<string> {
    const norm = path.replace(/^\/+|\/+$/g, '').trim();
    const hex = await this.sha256Hex(`doc:${norm}`);
    return `vfs3d_${hex.slice(0, 22)}`;
  }

  public async collectionTagFor(col: string): Promise<string> {
    const norm = col.replace(/^\/+|\/+$/g, '').trim();
    const hex = await this.sha256Hex(`col:${norm}`);
    return `vfs3c_${hex.slice(0, 22)}`;
  }

  private async deriveSecretKey(seed: string): Promise<Uint8Array> {
    const buf = new TextEncoder().encode(seed);
    const digest = await crypto.subtle.digest('SHA-256', buf);
    return new Uint8Array(digest);
  }

  private toFirestoreValue(val: any): Record<string, any> {
    if (val === null || val === undefined) {
      return { nullValue: null };
    }
    if (typeof val === 'boolean') {
      return { booleanValue: val };
    }
    if (typeof val === 'number') {
      if (Number.isInteger(val)) {
        return { integerValue: String(val) };
      }
      return { doubleValue: val };
    }
    if (typeof val === 'string') {
      return { stringValue: val };
    }
    if (Array.isArray(val)) {
      return {
        arrayValue: {
          values: val.map((item) => this.toFirestoreValue(item)),
        },
      };
    }
    if (typeof val === 'object') {
      const subFields: Record<string, any> = {};
      for (const [k, v] of Object.entries(val)) {
        subFields[k] = this.toFirestoreValue(v);
      }
      return {
        mapValue: {
          fields: subFields,
        },
      };
    }
    return { stringValue: String(val) };
  }

  public destroy() {
    this.isDestroyed = true;
    this.reconnectTimers.forEach((timer) => clearTimeout(timer));
    this.reconnectTimers.clear();
    this.sockets.forEach((ws) => ws.close());
    this.sockets.clear();
    this.activeDocSubs.clear();
    this.activeColSubs.clear();
    this.registeredTags.clear();
  }
}

export const nostrSync = new NostrSyncService();
