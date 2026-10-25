# VEIL — Pure P2P WebRTC Mesh Messenger & Private Community

VEIL is an advanced, privacy-first Android messaging application designed for serverless Peer-to-Peer (P2P) communication over WebRTC DataChannels, featuring multi-hop Store-and-Forward mesh synchronization, SQLCipher local vault encryption, and Telegram-grade custom Canvas UI architecture.

---

## ⚡ Key Architectural Highlights

- **Pure P2P WebRTC Mesh**: Zero intermediary chat servers for 1:1 and multi-participant communication. Encrypted direct DataChannels with automatic STUN/TURN fallback.
- **Store-and-Forward Mesh Engine**: Persistent SQLite Outbox queue delivering encrypted payloads across offline/asynchronous network boundaries.
- **Telegram-Parity Canvas Views**: Custom 120 FPS high-performance views (`VeilDialogCell`, `ChatMessageCell`) with light/dark adaptive palettes, dynamic cluster shapes, and swipe-to-reply physics.
- **Multi-Persona Anonymous Identity**: Instantly switch personas and mood profiles without exposing phone numbers or real-world identifiers.
- **Cryptographic Security Vault**: SQLCipher database encryption, Hardware Keystore integration, Argon2id/PBKDF2 key derivation, Decoy PIN duress workspace, and Safe Word panic wipe.
- **Real-Time Communication**: P2P Audio/Video calls with mutual consent upgrades, in-call video face masking, voice modulation filters, and WebRTC streaming.
- **Discovery & Matchmaking Hub**: Blind Roulette speed-chat matchmaking and public Broadcast Channels directory.

---

## 🛠 Tech Stack

- **Platform**: Native Android (Java 17 / 21)
- **Database**: SQLCipher (AES-256 encrypted SQLite)
- **Signaling & Presence**: WebRTC, Firebase Firestore & Cloud Messaging
- **UI Framework**: Custom Canvas views, Material Components 3, UCrop Studio
- **Security**: Android Keystore, Argon2id, PBKDF2, FLAG_SECURE

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 34+

### Clone & Build
```bash
git clone https://github.com/muzammilkhalid749-png/VEIL.git
cd VEIL
./gradlew assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-universal-debug.apk`
