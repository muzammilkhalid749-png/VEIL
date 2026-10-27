# VEIL Web — Pure P2P WebRTC Mesh Companion

VEIL Web is the high-performance desktop web client for VEIL, designed for serverless Peer-to-Peer (P2P) messaging directly over WebRTC DataChannels, matching Telegram's desktop UX.

---

## ⚡ Architectural Highlights

- **Pure WebRTC DataChannels**: Direct browser-to-mobile and browser-to-browser communication with zero intermediary chat server persistence.
- **Dual-Path Zero-Knowledge Signaling**: Connects directly to Firebase Firestore signaling (`users/{peerId}/signals`) matching the native Android `SignalingClient.java` contract.
- **Ephemeral Auto-Purge**: All signal documents (SDP offers, answers, ICE candidates) self-destruct immediately upon receipt for zero digital footprint.
- **Multi-Lane DataChannels**:
  - `veil-ctrl` (lane 0): Heartbeat pings, typing indicators, read receipts.
  - `veil-msg` (lane 1): Chat messages, outbox sync payloads.
  - `veil-media` (lane 2): Large binary chunks & file streams.
- **Device Pairing via QR**: Instant desktop-to-phone pairing by scanning a cryptographic QR code with the VEIL Android app.
- **Sovereign Node Security**: Local key derivation, zero tracking, and instant one-click Panic Wipe.

---

## 🛠 Tech Stack

- **Framework**: React 19 + TypeScript + Vite
- **Styling**: Tailwind CSS (Telegram-grade obsidian dark theme)
- **State Store**: Zustand
- **Signaling**: Firebase Firestore Web SDK
- **Media & Transports**: WebRTC (`RTCPeerConnection`, `RTCDataChannel`)
- **Icons**: Lucide React

---

## 🚀 Development & Running

### 1. Run Local Dev Server
```bash
cd web
npm run dev
```

### 2. Build for Production
```bash
cd web
npm run build
```

### 3. Deploy to Firebase Hosting
```bash
# From project root:
firebase deploy --only hosting
```
