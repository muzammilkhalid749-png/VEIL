import type { ChatSession, Message } from '../types/chat';

const DB_NAME = 'veil_vault_db_v1';
const DB_VERSION = 1;
const STORE_CHATS = 'chats';
const STORE_MESSAGES = 'messages';

class IndexedDbVault {
  private dbPromise: Promise<IDBDatabase> | null = null;

  private getDb(): Promise<IDBDatabase> {
    if (!this.dbPromise) {
      this.dbPromise = new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);

        request.onupgradeneeded = (event) => {
          const db = (event.target as IDBOpenDBRequest).result;
          if (!db.objectStoreNames.contains(STORE_CHATS)) {
            db.createObjectStore(STORE_CHATS, { keyPath: 'id' });
          }
          if (!db.objectStoreNames.contains(STORE_MESSAGES)) {
            const msgStore = db.createObjectStore(STORE_MESSAGES, { keyPath: 'id' });
            msgStore.createIndex('chatId', 'chatId', { unique: false });
          }
        };

        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
    }
    return this.dbPromise;
  }

  public async saveChats(chats: ChatSession[]): Promise<void> {
    try {
      const db = await this.getDb();
      const tx = db.transaction(STORE_CHATS, 'readwrite');
      const store = tx.objectStore(STORE_CHATS);
      chats.forEach((chat) => store.put(chat));
    } catch (e) {
      console.warn('[Vault] Failed saving chats:', e);
    }
  }

  public async loadChats(): Promise<ChatSession[]> {
    try {
      const db = await this.getDb();
      return new Promise((resolve) => {
        const tx = db.transaction(STORE_CHATS, 'readonly');
        const store = tx.objectStore(STORE_CHATS);
        const req = store.getAll();
        req.onsuccess = () => resolve((req.result as ChatSession[]) || []);
        req.onerror = () => resolve([]);
      });
    } catch {
      return [];
    }
  }

  public async saveMessage(msg: Message): Promise<void> {
    try {
      const db = await this.getDb();
      const tx = db.transaction(STORE_MESSAGES, 'readwrite');
      const store = tx.objectStore(STORE_MESSAGES);
      store.put(msg);
    } catch (e) {
      console.warn('[Vault] Failed saving message:', e);
    }
  }

  public async loadMessages(chatId: string): Promise<Message[]> {
    try {
      const db = await this.getDb();
      return new Promise((resolve) => {
        const tx = db.transaction(STORE_MESSAGES, 'readonly');
        const store = tx.objectStore(STORE_MESSAGES);
        const index = store.index('chatId');
        const req = index.getAll(IDBKeyRange.only(chatId));
        req.onsuccess = () => {
          const res = (req.result as Message[]) || [];
          res.sort((a, b) => a.timestamp - b.timestamp);
          resolve(res);
        };
        req.onerror = () => resolve([]);
      });
    } catch {
      return [];
    }
  }

  public async wipeAll(): Promise<void> {
    try {
      const db = await this.getDb();
      const tx = db.transaction([STORE_CHATS, STORE_MESSAGES], 'readwrite');
      tx.objectStore(STORE_CHATS).clear();
      tx.objectStore(STORE_MESSAGES).clear();
    } catch (e) {
      console.warn('[Vault] Wipe error:', e);
    }
  }
}

export const vault = new IndexedDbVault();
