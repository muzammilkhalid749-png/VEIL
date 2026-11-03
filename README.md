# VEIL

VEIL is a serverless, peer-to-peer encrypted communication protocol and native Android client written in Java. It is designed to operate without centralized metadata servers, phone-number registration, or persistent third-party data retention.

## Overview

Most mobile messengers rely on central servers for account identity, message routing, and group state management. VEIL replaces centralized infrastructure with a local-first architecture that combines decentralized relay signaling, direct WebRTC peer-to-peer transport, frame-level media encryption, and encrypted local storage.

## Core Architecture

* **Decentralized Signaling:** Uses Nostr relays (NIP-44 / NIP-59 gift-wrapped events) for asynchronous peer discovery, session negotiation, and push wakeups without exposing social graphs.
* **P2P Transport & NAT Traversal:** Establishes direct peer-to-peer WebRTC data and media channels (`SovereignNatTraversalEngine`) with STUN/TURN fallback for carrier-grade NAT (CGNAT) environments.
* **Dual-Topology Group Calling:** Supports full-mesh WebRTC routing for small groups and dynamic cascaded-tree forwarding (`CallEngine`) to minimize bandwidth overhead on mobile connections.
* **End-to-End & Frame-Level Encryption:** Implements Group Sender-Key Ratchets for messaging and SFrame per-frame encryption for real-time audio and video streams, ensuring intermediate relays or forwarding nodes cannot inspect media payloads.
* **Encrypted Local Persistence:** Stores message envelopes, ratchet states, and identity keys locally using SQLCipher (`AES-256`).
* **Non-Custodial Vault & eCash:** Integrates deterministic BIP-39 key derivation and Chaumian blind-signature ecash (Cashu NUT-13 / NUT-28) via `VeilVaultEngine` for private peer-to-peer transfers.
* **Programmatic UI Rendering:** Uses custom Java view layouts and canvas drawing for high-frame-rate chat cells and real-time spatial audio call indicators.

## Repository Structure

* `app/src/main/java/com/m3/veil/` — Core Android client implementation
  * `call/` — WebRTC session controllers, SFrame media encryptors, and mesh/cascaded-tree routing engines
  * `crypto/` — Sender-Key ratchet state machines, key derivation, and envelope encryption
  * `network/` — Nostr WebSocket relay pools, ICE candidate negotiation, and NAT traversal utilities
  * `vault/` — Non-custodial BIP-39 wallet and Cashu blind-signature implementation
  * `ui/` — Programmatic Java layouts, chat cells, and call micro-pill components

## Building from Source

### Prerequisites

* Android Studio (Ladybug or newer)
* JDK 17+
* Android SDK Platform 34+

### Build Steps

1. Clone the repository:
   ```bash
   git clone [https://github.com/muzammilkhalid749-png/VEIL.git](https://github.com/muzammilkhalid749-png/VEIL.git)
   cd VEIL
   
