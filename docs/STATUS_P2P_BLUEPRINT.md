# VEIL Pure P2P Status Engine — Architecture Blueprint
**Parity Target**: Telegram `StoriesController.java` & `StoriesStorage.java` + WhatsApp Status UX  
**Transport**: 100% Serverless WebRTC Mesh (`RTCDataChannel` lanes: `veil-mesh-gossip`, `veil-media`, `veil-ctrl`)  
**Storage Law**: ZERO Firestore / Cloud Storage — 100% Local SQLCipher + Encrypted P2P Swarm  

---

## 1. Architectural Foundation & Zero-Cloud Law

Unlike legacy systems that upload media and metadata to centralized cloud buckets (AWS S3, Firebase Cloud Storage, or Firestore collections), the **VEIL Status Engine** operates as an end-to-end encrypted, serverless, peer-to-peer gossip mesh:

1. **Origin Encryption**: Media is compressed and encrypted once locally using **AES-256-GCM** with a cryptographically secure random ephemeral key ($K_{status}$) and 12-byte IV.
2. **Multi-Recipient Envelope Wrapping**: For each permitted recipient (determined by the Privacy ACL), $K_{status}$ is individually wrapped using the recipient's public key (RSA-OAEP or ECIES/ECDH shared secret). Unapproved peers receive only opaque ciphertext that they can neither read nor decrypt.
3. **P2P Announcement Broadcast**: A compact binary `StatusAnnouncementPacket` (containing status ID, author ID, timestamp, expiration timestamp, privacy ACL mode, 32x32 byte micro-preview/BlurHash, total bytes, total 16KB chunks, and the recipient's wrapped key envelope) is gossiped over the `veil-mesh-gossip` WebRTC DataChannel lane.
4. **Resumable Swarm Chunk Streaming**: Encrypted 16KB media chunks are pulled directly from the author or seeded by mutual peers who share the authorization list via `P2PFileTransferHelper` (`veil-media` lane).
5. **Signed P2P View Receipts**: When a permitted contact opens and watches a status segment, an encrypted `StatusViewReceipt` is dispatched back to the author over the `veil-ctrl` lane. View counts and viewer profiles update in real time with zero server mediation.
6. **24-Hour Cryptographic Shredder**: Status segments expire 24 hours after publication. A background timer and database trigger securely wipes expired records, chunk bitmaps, and zeroes/shreds temporary decrypted cache files on disk.

```
+------------------------------------------------------------------------------------------------+
|                                      VEIL STATUS ENGINE ARCHITECTURE                           |
+------------------------------------------------------------------------------------------------+
|                                                                                                |
|   [Author: Alice]                                                     [Peer: Bob]              |
|   1. Capture Media / Text                                                                      |
|   2. Generate 32x32 Thumbnail                                                                  |
|   3. AES-256-GCM Encrypt Media                                                                 |
|   4. Wrap K_status for Bob (RSA-OAEP)                                                          |
|   5. Store in local SQLCipher                                                                  |
|   6. Broadcast Announcement ---------- veil-mesh-gossip ----------> 1. Ingest Announcement     |
|                                                                       2. Unwrap K_status       |
|                                                                       3. Render 0ms Preview    |
|   7. Stream 16KB Chunks <------------- veil-media (P2P Pull) --------- 4. Preload 64KB Head    |
|                                                                       5. Decrypt on playback   |
|   8. Receive Signed Receipt <--------- veil-ctrl (View Receipt) ------ 6. Send Signed Receipt  |
|                                                                                                |
+------------------------------------------------------------------------------------------------+
```

---

## 2. Telegram Reference Mapping

Modeled directly on Telegram's Android implementation:

| Telegram Component (`TMessagesProj`) | VEIL Component (`com.m3.veil`) | Architectural Adaptation |
| :--- | :--- | :--- |
| `StoriesController.java` (5,267 LOC) | `StatusController.java` (1,500+ LOC) | Coordinates status posting, active uploads, in-memory caching, look-ahead preloading, unread ring sorting, and lifecycle observation. |
| `StoriesStorage.java` (926 LOC) | `EncryptedChatDatabase.java` (`veil_statuses` tables) | Normalized SQLCipher encrypted database persistence with WAL mode, chunk bitmap persistence, and unread watermark tracking. |
| `UploadingStory` | `StatusController.UploadingStatus` | Multi-stage upload state machine: compression, micro-thumbnail generation, AES-256-GCM encryption, key wrapping, and chunk broadcast. |
| `dialogIdToMaxReadId` | `peerIdToMaxReadStatusId` | Monotonic watermarking mapping each contact's UID to the highest seen status segment ID for unread ring color calculation. |
| `StoriesList` & `PeerStories` | `PeerStatusChain` | Chronologically ordered collection of active 24-hour status segments per author. |
| `StoryCustomParamsHelper` | `StatusGossipManifest` | Deterministic binary serialization packing segment parameters, privacy bitmasks, and recipient key envelopes into binary bytebuffers. |
| `FileLoader` chunk queue | `P2PFileTransferHelper` | 16KB chunk streaming over WebRTC DataChannel with BitSet resumption and sliding-window backpressure. |

---

## 3. Core State Machines

### 3.1 `UploadingStatus`
Tracks the lifecycle of an outbound status publication:
```
+---------------+      +-------------------+      +--------------------+      +--------------------+
|  PREPARING    | ---> |  ENCRYPTING_MEDIA | ---> |  WRAPPING_KEYS     | ---> |  SAVING_LOCAL_DB   |
+---------------+      +-------------------+      +--------------------+      +--------------------+
                                                                                        |
                                                                                        v
+---------------+      +-------------------+      +--------------------+      +--------------------+
|   COMPLETED   | <--- |  SEEDING_SWARM    | <--- |  BROADCAST_GOSSIP  | <--- |  CHUNK_STREAMING   |
+---------------+      +-------------------+      +--------------------+      +--------------------+
```
- **State Properties**: `statusId`, `localFilePath`, `thumbBytes`, `progress`, `bytesUploaded`, `totalBytes`, `privacyAcl`, `error`.
- **Cancellation**: Supports cancelling an upload before completion, cleaning up intermediate encrypted slabs.

### 3.2 `PeerStatusChain`
Represents an author's active status reel:
- `authorId`: Remote or local user ID.
- `authorName`: Author display name.
- `authorAvatarUrl`: Author profile picture link or cached thumbnail.
- `segments`: Sorted list of `StatusSegment` items ordered by `timestampMs ASC`.
- `maxReadStatusId`: ID of the latest status viewed by the local user.
- `hasUnread()`: Returns `true` if any segment in the chain has `timestampMs > maxReadStatusId` and `viewed == false`.
- `isCloseFriends()`: Returns `true` if any unread segment is targeted to Close Friends (triggers green ring styling).

### 3.3 `StatusGossipManifest`
Binary wire format transported across `veil-mesh-gossip` (`TYPE_STATUS_POST`):
- `magic`: `0x56535453` ("VSTS" - Veil Status)
- `version`: `0x01`
- `statusId`: UTF-8 string (UUID)
- `authorId`: UTF-8 string
- `authorName`: UTF-8 string
- `contentText`: UTF-8 string (caption or text status)
- `mediaType`: `IMAGE` / `VIDEO` / `TEXT`
- `bgColorHex`: Hex color string
- `fontStyleIndex`: Integer 0-4
- `timestampMs`: Long
- `expiresAtMs`: Long (typically `timestampMs + 86400000L`)
- `totalBytes`: Long
- `chunkCount`: Integer
- `chunkHash`: SHA-256 of the encrypted file
- `wrappedKeysMap`: Map of `recipientUid -> base64WrappedKey` (allowing only authorized peers to decrypt)
- `authorSignature`: Ed25519 or ECDSA signature over the manifest header

---

## 4. Privacy ACL Engine

Privacy is cryptographically enforced at the transport layer:
1. `ALL_CONTACTS`: Status key $K_{status}$ is wrapped for all mutual contacts found in local SQLCipher.
2. `CLOSE_FRIENDS`: $K_{status}$ is wrapped only for contacts tagged as "Close Friends" in privacy settings. Unread rings render with Telegram/Instagram style neon green outlines.
3. `SELECTED_PEERS`: $K_{status}$ is wrapped strictly for an explicit whitelist of peer UIDs.
4. `PERSONA_ISOLATED`: Encrypted under the active Veil Persona cryptographic identity, completely decoupled from the base user identity.

Peers not in the recipient list receive the gossip announcement (for DHT/swarm routing) but cannot derive $K_{status}$, ensuring mathematical zero-knowledge privacy.

---

## 5. SQLCipher Database Schema (`EncryptedChatDatabase.java`)

### 5.1 `veil_statuses`
| Column | Type | Description |
| :--- | :--- | :--- |
| `status_id` | `TEXT PRIMARY KEY` | Unique UUID of the status segment |
| `author_id` | `TEXT` | UID of the author |
| `author_name` | `TEXT` | Author display name |
| `content_text` | `TEXT` | Caption or text content |
| `media_type` | `TEXT` | `image`, `video`, or `text` |
| `media_file_path` | `TEXT` | Path to local or decrypted media file |
| `encrypted_file_path` | `TEXT` | Path to downloaded encrypted ciphertext slab |
| `thumb_blob` | `BLOB` | 32x32 byte micro-preview for 0ms rendering |
| `bg_color_hex` | `TEXT` | Background color for text status |
| `font_style_index` | `INTEGER` | Font index |
| `timestamp_ms` | `INTEGER` | Publication timestamp |
| `expires_at_ms` | `INTEGER` | Expiration timestamp (timestamp + 24h) |
| `total_bytes` | `INTEGER` | Total file size in bytes |
| `total_chunks` | `INTEGER` | Total 16KB chunks |
| `privacy_acl_mode` | `INTEGER` | ACL mode (0: All, 1: Close Friends, 2: Selected, 3: Persona) |
| `is_mine` | `INTEGER` | `1` if authored by current user, `0` otherwise |
| `is_viewed` | `INTEGER` | `1` if local user viewed this segment |
| `view_count` | `INTEGER` | Count of unique viewers |
| `aes_key_wrapped` | `TEXT` | Base64 wrapped AES key |
| `iv_base64` | `TEXT` | Base64 12-byte GCM IV |

### 5.2 `veil_status_views`
| Column | Type | Description |
| :--- | :--- | :--- |
| `view_id` | `TEXT PRIMARY KEY` | UUID of view receipt |
| `status_id` | `TEXT` | Reference to `veil_statuses.status_id` |
| `viewer_id` | `TEXT` | UID of the contact who viewed |
| `viewer_name` | `TEXT` | Viewer display name |
| `viewer_avatar_url` | `TEXT` | Viewer avatar |
| `viewed_at_ms` | `INTEGER` | Timestamp of view |
| `signed_receipt_blob` | `BLOB` | Cryptographic signature of viewer |

### 5.3 `veil_status_chunk_bitmaps`
| Column | Type | Description |
| :--- | :--- | :--- |
| `status_id` | `TEXT PRIMARY KEY` | Status segment ID |
| `chunk_bitmap_blob` | `BLOB` | Serialized `BitSet` tracking received 16KB chunks |
| `completed_chunks` | `INTEGER` | Count of downloaded chunks |
| `updated_at_ms` | `INTEGER` | Last download activity |

### 5.4 `veil_status_gossip_queue`
| Column | Type | Description |
| :--- | :--- | :--- |
| `queue_id` | `TEXT PRIMARY KEY` | Unique queue item ID |
| `status_id` | `TEXT` | Reference to status |
| `target_peer_id` | `TEXT` | Peer to gossip announcement to |
| `packet_payload_blob`| `BLOB` | Serialized `VeilPacket` binary payload |
| `retry_count` | `INTEGER` | Retry counter |
| `last_attempt_ms` | `INTEGER` | Timestamp of last transmission |

---

## 6. Look-Ahead Media Preloader & Swarm Pulling

To ensure zero-lag instant playback when tapping through contacts:
1. **P2P Swarm Discovery**: When a `StatusAnnouncementPacket` is received, the preloader registers the status in an active download queue.
2. **Head Chunk Fetching (64KB Look-Ahead)**: The preloader immediately issues chunk requests for chunks `0, 1, 2, 3` (first 64KB) of the next 3 unviewed contact reels over `veil-media`.
3. **Decryption-on-the-Fly**: Once chunks `0..3` arrive, the playback player can initiate buffering immediately while the remaining chunks stream in the background.
4. **Swarm Peer Seeding**: Any connected peer that has downloaded the encrypted chunks for a status (and is authorized by the author) will fulfill chunk requests for mutual peers, distributing network load across the mesh.

---

## 7. Cryptographic Shredder & 24h Purge

A scheduled background job running every 15 minutes enforces the 24-hour TTL:
1. Query `veil_statuses` for records where `expires_at_ms <= System.currentTimeMillis()`.
2. For each expired status:
   - Securely overwrite and delete `encrypted_file_path` and `media_file_path` on disk (`RandomAccessFile` zeroing before `file.delete()`).
   - Delete associated rows from `veil_status_views`, `veil_status_chunk_bitmaps`, and `veil_status_gossip_queue`.
   - Evict from `StatusController` memory caches (`allPeerChains`, `statusItemCache`).
3. Notify UI observers (`onStatusesChanged()`) so the expired status is removed seamlessly from user viewports.

---

## 8. Implementation Verification Checklist
- [x] Pass 1: Architecture Blueprint & Core State Machines (`StatusController.java` Pass 1)
- [x] Pass 2: Normalized SQLCipher Tables & Binary Serialization (`EncryptedChatDatabase.java` status tables)
- [x] Pass 3: Pure P2P Distribution, AES-256-GCM Encryption & Swarm Chunk Streaming (`P2PFileTransferHelper` & `StatusController`)
- [x] Pass 4: Look-Ahead 64KB Preloader, Ring Sorting & 24h Cryptographic Shredder (`StatusController.java` & `VeilBoardController.java`)
- [x] Pass 5: UI Observer Wiring (`WhatsAppStatusUi`, `HomeFragment`, `DiscoverFragment`, `CreatePostActivity`, `CommentsBottomSheet`), Zero TODOs, Build Verification
