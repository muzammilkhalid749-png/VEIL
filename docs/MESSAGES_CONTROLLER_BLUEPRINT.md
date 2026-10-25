# VEIL MESSAGES CONTROLLER BLUEPRINT
## Pure P2P Decentralized Messaging Engine with Telegram Parity

### Architecture Overview

`VeilMessagesController` is an application-wide singleton (`com.m3.veil.manager.VeilMessagesController`) providing Telegram-grade message pipeline management for VEIL (`com.m3.veil`). It centralizes all 1:1 direct chats, secret chats, P2P group mesh dialogues, ephemeral vanish countdowns, blink view-once media lifecycles, and cryptographic ratchets away from ephemeral Activity lifecycles (`ChatActivity.java`) into a persistent background engine.

```
+---------------------------------------------------------------------------------------+
|                                    UI Layer                                           |
|       ChatActivity.java       HomeFragment.java       ArchivedChatsActivity.java      |
+---------------------------------------------------------------------------------------+
                                        ▲ |
                   MessagesObserver API | | User Actions (Send, Edit, Delete, React)
                                        | ▼
+---------------------------------------------------------------------------------------+
|                             VeilMessagesController                                    |
|                                                                                       |
|  +--------------------+   +---------------------+   +-------------------------------+  |
|  |     stageQueue     |   |     cryptoQueue     |   |        storageQueue           |  |
|  | (Sequencing, State,|   | (OAEP + HKDF Ratchet|   | (SQLCipher Read/Write,        |  |
|  |  Packet Packaging) |   |  Zeroize Ephemeral) |   |  Zero-Wipe Shredder Daemon)   |  |
|  +--------------------+   +---------------------+   +-------------------------------+  |
|                                                                                       |
|  +---------------------------------------------------------------------------------+  |
|  | In-Memory Cache (dialogsDict, dialogMessages, messagesById, unreadCounters)     |  |
|  +---------------------------------------------------------------------------------+  |
|  | SecretChatHelper Engine (READ_ACK Vanish Countdown, Blink View-Once, Shredder) |  |
|  +---------------------------------------------------------------------------------+  |
|  | Lamport Logical Clock & Vector Timestamp Sequencer                              |  |
+---------------------------------------------------------------------------------------+
                                        ▲ |
                                        | ▼
+---------------------------------------------------------------------------------------+
|                                 Transport Layer                                       |
|     WebRtcManager (RTCDataChannel: veil-msg, veil-ctrl, veil-media, veil-mesh-gossip) |
|     VeilMeshDaemonService (Outbox Queue & Silent Wake-Knock Mesh Sync)                |
+---------------------------------------------------------------------------------------+
```

---

### Core Components

#### 1. Concurrency & Queue Model (`DispatchQueue`)
Modeled after Telegram's `DispatchQueue` implementation:
- **`stageQueue`**: Single-threaded `HandlerThread` for message sequencing, Lamport clock ordering, outbox queue state changes, and packet dispatch preparation.
- **`cryptoQueue`**: Dedicated single-threaded executor for asymmetric RSA-OAEP encrypt/decrypt operations, HKDF key derivation, and symmetric ratcheting steps.
- **`storageQueue`**: Single-threaded executor dedicated to SQLCipher `EncryptedChatDatabase` disk reads, writes, transactions, and background media file zero-byte wiping.
- **`mainQueue`**: Main looper dispatcher for firing typed observer callbacks (`MessagesObserver`) to UI components without thread-hopping overhead.

#### 2. Serverless Logical Clock & Message Sequencing
Decentralized networks lack a central timestamp authority. Messages are tagged with:
- `lamportClock`: Monotonically increasing counter per peer and dialog.
- `seqId`: Strictly sequential integer for 1:1 conversation causal ordering.
- `VectorClock`: Mapping of peer IDs to logical timestamps to detect concurrent edits or divergent forks in P2P group mesh channels.

#### 3. Cryptographic Ratcheting & Forward Secrecy
Modeled after Signal/WhatsApp Double Ratchet and Telegram Secret Chat ratcheting:
- Upgrade from static RSA PKCS1Padding to `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`.
- Ephemeral root key exchange with HKDF-SHA256 ratcheting.
- Per-message keys derived from chaining state (`sendChainKey`, `recvChainKey`).
- Immediate overwriting/zeroization (`Arrays.fill(bytes, (byte) 0)`) of ephemeral key material in memory after encryption or decryption.

#### 4. P2P Packet Protocol (Binary ByteBuffer Framing)
Dedicated binary packet framing over WebRTC `RTCDataChannel`:
- `0x564D5347` (`VMSG`): Standard text / media / attachment message frame.
- `0x56454454` (`VEDT`): Message edit frame.
- `0x5644454C` (`VDEL`): Remote delete for everyone frame.
- `0x56524354` (`VRCT`): Emoji reaction frame.
- `0x56504F4C` (`VPOL`): Inline poll creation frame.
- `0x56564F54` (`VVOT`): Inline poll vote casting frame.
- `0x56545950` (`VTYP`): Stealth typing wave indicator frame.
- `0x56524143` (`VRAC`): Read receipt acknowledgment (`READ_ACK`) frame triggering vanish countdown.
- `0x5653534E` (`VSSN`): Screenshot notification frame.

#### 5. Telegram SecretChatHelper Vanish & Security Daemon
- **Vanish Countdown Trigger**: Countdown strictly begins upon receipt of `READ_ACK` (or when local peer opens incoming message). Sent messages do not vanish while unread unless configured for `after_send`.
- **Zero-Wipe Disk Shredder**: When a message with media vanishes or is deleted for everyone, the local file on disk is opened with `RandomAccessFile`, overwritten with 0x00 and random bytes across multiple passes, and then unlinked.
- **Blink View-Once**: Automatically triggers shredding immediately upon dismiss or 10-second viewing timeout.
- **Security Protections**: Auto-detects screenshots via `ContentObserver` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and broadcasts `VSSN` packet to remote peer.

---

### Implementation Phases

1. **Pass 1**: Architecture Core, Threading Queues, Lamport Clock, Dialog & Message Memory Caches.
2. **Pass 2**: Forward-Secrecy RSA-OAEP + HKDF Per-Message Ratchet in `EncryptionManager`.
3. **Pass 3**: P2P Packet Framing, Ingestion, Dispatch, Outbox Retries over `WebRtcManager`.
4. **Pass 4**: SecretChatHelper Vanish Engine, Read-Ack Countdown, Zero-Byte Disk Shredder Daemon, Screenshot Alerts.
5. **Pass 5**: Refactor `ChatActivity` & `HomeFragment` to bind to `VeilMessagesController`, verify build clean.
