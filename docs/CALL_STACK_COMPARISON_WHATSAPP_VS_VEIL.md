# WhatsApp VoIP vs VEIL Calls — Architecture & Flow Comparison

Audit date: this session. Sources:
- VEIL: `F:\AndroidStudioProjects\VEIL\app\src\main`
- WhatsApp (decompiled): `E:\Apk\Decompile\sources` + `E:\Apk\Decompile\resources\AndroidManifest.xml`

---

## 1. Bottom line

**No — VEIL's call code is not the same as WhatsApp's.** It is not "the same but less polished"; it is a *fundamentally different stack* with a much smaller surface.

| | WhatsApp | VEIL |
|---|---|---|
| Media engine | Proprietary **native** VoIP engine (`libs.so`), Java bridge `Voip` / `VoipNativeImpl` / `JNIUtils` | Google/Stream **`org.webrtc`** Java SDK (`io.getstream:stream-webrtc-android:1.3.1`) |
| Transport | Own relay + P2P with server-side call control | Pure P2P WebRTC; **STUN only, no TURN/relay** |
| Signaling | XMPP stanzas + proprietary HTTP ("voice session") | Firestore documents + optional socket.io; call signals also over the chat DataChannel |
| Call E2EE | Dedicated call key exchange (`CryptoCallback.generateE2EKeysV2`, `getSecureSsrc`, HKDF/HMAC) | Only default DTLS-SRTP; "E2EE" dialog reuses the *chat* safety number |
| Topology | 1:1, **group**, call links, waiting rooms, audio chats | **1:1 only** |
| States | 14-state native state machine | 8-value enum, not actually driving logic |
| UI/service | Foreground service + Telecom + PiP + call log DB | Plain single Activity; no foreground service, no Telecom |

WhatsApp ships roughly **150 engine operations** in a single interface (`X/InterfaceC11390fM.java` = `X.0fM`, lines 24–280). VEIL implements a handful of them.

---

## 2. WhatsApp architecture (what the decompile shows)

### 2.1 Engine core
- `com.whatsapp.calling.infra.voipcalling` models and `com.whatsapp.infra.protocol.VoipStanzaChildNode` are present.
- The Java engine entry points `com.whatsapp.calling.voipcalling.Voip`, `VoipNativeImpl` and `JNIUtils` are **not in the dump** but are referenced by 621 obfuscated classes (e.g. `X/C1OJ.java:65-67`, `X/C3NN.java:25-33`, `X/F78.java:7`). This is the proguard-kept bridge over the native engine in `libs.so`.
- The app-facing contract is `X/InterfaceC11390fM.java`. Selected operations (exact names, lines 24–280):

  - Call control: `acceptCall`, `acceptCallWithVideoStopped`, `acceptMediaOnly`, `commitAccept`, `rejectCall`, `rejectPendingCall`, `endCall`, `endCallAndAcceptPendingCall`, `timeoutPendingCall`, `peekIncomingOffer`, `joinOngoingCall`, `checkOngoingCalls`
  - Video: `requestVideoUpgrade`, `acceptVideoUpgrade`, `cancelVideoUpgrade`, `rejectVideoUpgrade`, `startVideoCaptureStream`, `startVideoRenderStream`, `stopVideoRenderStream`, `switchCamera`, `turnCameraOn/Off`, `refreshCaptureDevice`, `refreshVideoDevice`, `setVideoCropAspectRatio`, `dumpLastVideoFrame`
  - Group/link/rooms: `invite`, `inviteToGroupCall`, `cancelInviteToGroupCall`, `createCallLink`, `editCallLink`, `previewCallLink`, `joinCallLink`, `queryCallLinkForLinkEdit`, `waitingRoomAdmit/Deny/Toggle/AdmitAll`, `sendMutePeerRequestInGroupCall`, `sendRemoveUserRequest`, `allowUnknownPeerVideo`
  - In-call extras: `muteCall`, `enableNoiseCancellation`, `sendDTMFTone`, `sendCallReaction`, `sendRaiseHand`, `sendWaveToVoiceChat`, `screenCall`, `stopCallRecording`, `setCallLowDataUsage`, `setCallDataSavingMode`, `adjustAudioLevel`, `notifyAudioRouteChange`
  - Network: `switchNetworkWithAlternativeSocket`, `startTestNetworkConditionWithAlternativeSocket`, `updateProxy`, `notifyLostOfAlternativeNetwork`, `updateNetworkRestrictions`, `processWaWifiInfo`, `processWaCellSignalStrength`
  - Multi-device: `notifyDeviceIdentityChanged/Deleted`, `resendOfferOnDecryptionFailure`, `sendRekeyRequest`, `resendOfferForEndedCall`, `transferCallStart`, `toggleToHammerheadDev`
  - AI/avatar/wearable: `prewarmForGenai`, `turnCodecAvatarOn/Off`, `setCodecAvatarFullDuplexConsent`, `sendCodecAvatarImuData`, `sendWearableAttribution`, `verifyIncomingBotIdentityKey`

### 2.2 State machine
`calling/infra/voipcalling/CallState.java` — 14 states:
`NONE, CALLING, PRE_ACCEPT_RECEIVED, RECEIVED_CALL, ACCEPT_SENT, ACCEPT_RECEIVED, ACTIVE, ACTIVE_ELSEWHERE, REJOINING, LINK, CONNECTED_LONELY, PRECALLING, ENDING, BCALL_STARTING, SCREENING`.
VEIL copies the *names* of 8 of these but never uses them to drive the engine.

### 2.3 Call model
`calling/infra/voipcalling/CallInfo.java` (1,466 lines) exposes, among others:
group calls, call links (`callLinkToken`, `callLinkState`, `callLinkCreatorJid`), waiting room (`isWaitingRoomAdmin/Enabled`, `waitingRoomParticipantJids`), call waiting (`CallWaitingInfo`, `hasPendingCall`, `isCallOnHold`), voice chats, bot calls / BCall, video upgrade (`isSelfRequestingUpgrade`, `isPeerRequestingUpgradeWithAutoAccept`), screen share (`ScreenSharerInfo`, `isDualStreamSsEnabled`), relay (`relayCallUuid`), E2EE flag (`isNotE2ee`), durations (`audioDuration`, `videoDuration`, `callActiveTime`), extensions (GenAI video), `phashBasedCall`, `connectedLimit`.

`calling/infra/ParticipantInfo.java` exposes per-participant: speaking/dominant speaker, muted/mute-requested, hand raised, screen sharing, codec avatar, network health, reconnecting options, video decode/render paused states, reactions, audio/video-switch capability, wearable attribution, call-grid rank.

### 2.4 Planes
- **Signaling XMPP**: `SignalingXmppCallback.sendCallStanza(Jid, String, VoipStanzaChildNode)`; stanza nodes built with attributes/children/data (`infra/protocol/VoipStanzaChildNode.java`).
- **Signaling HTTP**: `SignalingHttpCallback` with request types `VOICE_SESSION=0`, `CODEC_AVATAR=1`.
- **Crypto (call E2EE)**: `CryptoCallback` — `generateE2EKeysV2`, `getSecureSsrc`, `hkdfSha256`, `hmacSha256`, `generateRandomBytes`.
- **Data channel**: `DataChannelCallback.onMessageReceived(byte[], boolean)`.
- **Video pipeline**: `VideoPort` (native render + render engine, brightness/enhancement, corner radius, Lanczos, scale types), `CaptureStream` (native push of NV21/ABGR frames, dual-stream, async capture), `CameraInfo`, `GlVideoRenderer` (native), `EncodedVideoPassthrough` (H.264/H.265, symmetric/asymmetric encoder/decoder modes, BWE callback, keyframe request).
- **Audio pipeline**: `VoipSystemAudioDeviceFactory`, `ScreenShareAudioCapturer` (MediaProjection + `AudioPlaybackCaptureConfiguration`, `X/C57009Ppj.java:11-20`).
- **Screening/ASR**: `ScreeningPcmNative`, `MlKitSpeechRecognizer`.

### 2.5 Android components (manifest)
- `com.whatsapp.calling.service.VoiceFGService` with `foregroundServiceType="microphone|camera|mediaProjection|phoneCall"` (line 5587–5589).
- `SelfManagedConnectionService` (Telecom)`android.telecom.ConnectionService` (5591–5595).
- `com.whatsapp.calling.ui.VoipActivityV2` with `taskAffinity=":voip"`, `supportsPictureInPicture="true"` (4224–4231).
- `SystemDialerCallLandingActivity`, `CallLinkActivity`, `GroupCallLogActivity`, `GroupCallParticipantPicker`, `CallLogActivityV2`, `CallSpamActivity`, `CallRatingActivity`, `VoipPermissionsActivity`, `GroupCallPsaActivity`, `FavoriteCallListActivity`, `UpcomingCallListActivity`, `DialerActivity` (4242–4324).
- `DeclineIntentReceiver` (hangup/reject/group reminder/end bot call) (5577–5583).
- Permissions: `MANAGE_OWN_CALLS`, `ANSWER_PHONE_CALLS`, `READ_CALL_LOG`, `FOREGROUND_SERVICE_MICROPHONE/CAMERA/MEDIA_PROJECTION/PHONE_CALL`, `DETECT_SCREEN_CAPTURE`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS` (lines 168–256).

---

## 3. VEIL architecture

### 3.1 Files
- `app/src/main/java/com/m3/veil/webrtc/WebRtcManager.java` — everything: chat DataChannels + 1:1 calls (`startCall`, `acceptCall`, `rejectCall`, `endCall`, SDP/ICE, notifications).
- `webrtc/SignalingClient.java` — Firestore `users/{uid}/signals` + optional socket.io; `call_invite/ringing/accept/reject/end`.
- `webrtc/audio/VoipAudioDevice.java`, `webrtc/video/VoipVideoDevice.java` — thin wrappers over `org.webrtc`.
- `webrtc/model/CallState.java`, `CallInfo.java`, `model/CallRecord.java`.
- `ui/chat/CallActivity.java` — the call screen.

### 3.2 Actual flow (outgoing)
`CallActivity.initiateWebRtcCall()` → `WebRtcManager.startCall(peerId, callType, callId)` (WebRtcManager.java:1558):
1. request audio focus → `getOrCreatePeerConnection(peerId, true)`
2. create local audio track (VideoTrack only if `isVideo`), `pc.addTrack(...)` (1571–1590)
3. `SignalingClient.sendCallInvite(...)` + DataChannel `call_signal:invite` + `sendWakeUp`
4. `initiateConnection(peerId)` → `createOffer` with `OfferToReceiveAudio=true`, `OfferToReceiveVideo=true` → `sendOffer` (1931–1949)
5. Callee: `onCallInviteReceived` (1809) → busy check → `sendCallRinging` → open `CallActivity` + full-screen notification
6. Callee accept → `acceptCall` (1619) adds tracks, `sendCallAccept`, then `initiateConnection` again
7. ICE connected → `onIceConnectionChange(CONNECTED)` fires `onCallEstablished` (onIceConnectionChange 490–498)
8. `endCall` (1714) → `stopCallAudioHardware` disposes tracks/PC/DC and resets AudioManager to MODE_NORMAL.

### 3.3 Signaling
- Firestore-first with socket.io opportunistic: STUN servers are hard-coded Google STUN (WebRtcManager.java:429–435); there is **no TURN server**.
- Same `PeerConnection` is shared between the chat DataChannel and the call media; ending a call removes the DataChannel and closes the PC (stopCallAudioHardware, 1471–1533), so **ending a call tears down the P2P chat session** with that peer.

---

## 4. Flow-by-flow comparison

### 4.1 Outgoing 1:1 call
| Step | WhatsApp | VEIL |
|---|---|---|
| Trigger | UI → `Voip` engine → server offer | `WebRtcManager.startCall` |
| Offer path | XMPP stanza fan-out to all devices, then pre-accept/ack | Direct SDP offer over Firestore/socket + DataChannel |
| Server role | Relay/call control, phash device fan-out, offer expiry | Firestore only as a mailbox |
| Media path | Native engine; relay fallback | P2P only; fails behind symmetric NAT without TURN |
| Ringing | `PRE_ACCEPT_RECEIVED` → `RECEIVED_CALL` | Text "Ringing (P2P E2EE)..."; state set locally |
| Accept | `acceptCall`/`commitAccept`; media only after commit | Adds tracks then re-initiates offer/answer |
| Active | `ACTIVE` + E2E keys + logging | ICE CONNECTED event |

### 4.2 Incoming call
| Step | WhatsApp | VEIL |
|---|---|---|
| Wake | FCM/push + `VoiceFGService` | FCM + Firestore listener + full-screen notification |
| Pre-accept | `peekIncomingOffer` → `CallOfferInfo` | none |
| Busy | `REJECT_REASON_BUSY` with call waiting | `sendCallReject("busy")`, no call waiting |
| Accept variants | accept with video stopped / media only / commit | single `acceptCall` |
| Decline reasons | declined, busy, unavailable, TOS, ENC | declined / busy only |

### 4.3 Video upgrade mid-call
- WhatsApp: `requestVideoUpgrade` / `acceptVideoUpgrade` / `cancelVideoUpgrade` / `rejectVideoUpgrade`, plus per-participant `isAudioVideoSwitchSupported`, auto-accept upgrade, rotation requests, asymmetric H.264/H.265 passthrough.
- VEIL: **does not exist.** `CallActivity.toggleVideo()` (704–709) only calls `setVideoEnabled` on an already-created local track. No track is added, no SDP renegotiation, and `onRenegotiationNeeded()` is empty (WebRtcManager.java:555). Video therefore only works if the call was created as video.

### 4.4 Group / multi-party calls
- WhatsApp: full group calls, call links, waiting rooms, participant grid ranking, mute-peer/remove-user, raise hand, reactions, connected limit.
- VEIL: **none.** One `activeCallPeerId`, one call at a time; group only exists for chat.

### 4.5 Screen sharing
- WhatsApp: `MediaProjection` + `AudioPlaybackCaptureConfiguration` (`X/C57009Ppj.java`), `ScreenSharerInfo` (dual-stream, decode/render/codec pause flags), `ScreenShareResourceManager`.
- VEIL: `showVideoPrivacyAndScreenShareDialog()` (CallActivity.java:457–476) only shows a Toast "P2P Screen Share mode ready". No implementation.

### 4.6 Other features
| Feature | WhatsApp | VEIL |
|---|---|---|
| Call recording | `stopCallRecording`, `ScreeningRecordingInfo` | none |
| Call screening/ASR | `ScreeningPcmNative`, `screenCall`, `MlKitSpeechRecognizer` | none |
| DTMF | `sendDTMFTone` | none |
| Reactions / raise hand | `sendCallReaction`, `sendRaiseHand` | none |
| Noise cancellation | `enableNoiseCancellation` | only WebRTC AEC/NS constraints |
| Low-data mode | `setCallLowDataUsage`, `setCallDataSavingMode`, `MAX_DATA_USAGE_IN_A_CALL` | none |
| Network switching / proxy | alternative socket, proxy update, Wi-Fi/cell telemetry | none |
| Video quality control | `PeerRxSubscriptionInfo.videoQuality`, H.264/H.265 hw/sw | fixed 640×480 @30fps |
| Call E2EE key exchange | `generateE2EKeysV2`, `getSecureSsrc` | none (dialog reuses chat safety number) |
| Telecom integration | `SelfManagedConnectionService`, `MANAGE_OWN_CALLS` | none |
| Foreground service | `VoiceFGService` (mic/camera/mediaProjection/phoneCall) | none (plain Activity + notification) |
| Picture-in-Picture | `supportsPictureInPicture="true"` | not declared; portrait-locked |
| Call log DB | `CallLogInfo`, `CallLogActivityV2`, group logs, byte counters | `CallRecord` in local DB |
| Spam/rating/upcoming/favorites | dedicated activities | none |
| Multi-device call state | `ACTIVE_ELSEWHERE`, device rekey/resend, transfer | none |
| AI/bot/avatar/wearable/glasses | GenAI voice, codec avatars, wearables | none |

---

## 5. Concrete correctness problems in VEIL's current call code

1. **Answer is produced before the callee accepts.** `onOfferReceived` (WebRtcManager.java:1951–1977) sets the remote offer, creates and sends an **answer** immediately — there is no user gate. The comment at 1954–1956 claims mic isolation, and that part is true (no track added), but SDP/ICE still complete while ringing. Caller can see `onCallEstablished` from ICE CONNECTED (490–498) before accept.
2. **Double offer/glare on accept.** After the callee answers, `acceptCall` calls `initiateConnection` again (1666), generating a second offer while the PC may already be stable; the caller's `onAnswerReceived` (1980) blindly `setRemoteDescription(ANSWER)`, which can throw on a stable PC. `onRenegotiationNeeded` is empty.
3. **No TURN/relay** → calls fail on carrier-grade NAT / restrictive networks, exactly where WhatsApp's relay infrastructure exists.
4. **Call end kills the chat channel.** `stopCallAudioHardware` removes the DataChannel and closes/disposes the shared PeerConnection (1497–1526).
5. **CallState enum is decorative** in the engine: imported at line 41 but never used in WebRtcManager; the UI sets it manually.
6. **No renegotiation for video**, so "toggle video" on an audio call cannot work.
7. **Audio focus is `AUDIOFOCUS_GAIN_TRANSIENT`** (1432) instead of a durable gain for calls; there is no `AUDIOFOCUS_GAIN` and no focus-loss handling (listener is a no-op at 1435).
8. **No foreground service / no call permissions** (`MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_*`, `MODIFY_AUDIO_SETTINGS` is present but not the FGS ones) → the call can be killed when backgrounded on Android 14+.
9. **No call timeout** for unanswered outgoing calls (WhatsApp has offer expiry / `timeoutPendingCall`).
10. **Full-screen intent only**; no Telecom self-managed connection, so integration with car/Bluetooth/system dialer is missing.
11. **Privacy claims in UI are not backed by code**: "Voice Mask", "Face Blur", "Spatial 3D Audio", "E2EE Call Verification" (CallActivity.java:439–485) are dialogs/Toasts or chat-derived values.

---

## 6. Suggested parity roadmap (highest value first)

1. **TURN/relay**: add TURN credentials + `iceTransportPolicy` and ICE restart; this fixes the majority of "call doesn't connect" reports.
2. **Fix the handshake**: do not send an answer/ICE before accept; gate media and answer on the accept action; implement a real state machine (CALLING → RINGING → ACCEPTING → CONNECTED → ENDING) using `CallState`.
3. **Add `FOREGROUND_SERVICE_MICROPHONE/CAMERA/PHONE_CALL`** + a call foreground service and `MANAGE_OWN_CALLS`, plus `android:supportsPictureInPicture` and landscape video.
4. **Mid-call video upgrade** with SDP renegotiation (`addTrack` + `createOffer` and proper `onRenegotiationNeeded`) instead of just enabling a track.
5. **Decouple call PC from chat PC** so ending a call does not drop messaging.
6. **Call E2EE**: derive per-call keys and insert a frame encryptor/decryptor (or SRTP keying via Insertable Streams) so the safety-number dialog is real.
7. **Screen share** via `MediaProjection` (activity `createScreenCaptureIntent`) and capturing playback audio.
8. **Group calls** (mesh or SFU) with a participant list, and **call links**.
9. **Call log/reason codes**: extend `CallRecord` and reject reasons; add call timeout, call waiting.
10. Optional, for WhatsApp-style polish: DTMF, reactions, raise hand, low-data mode, call rating/spam reporting.

---

## 7. Method / evidence note

- WhatsApp's Java engine class `com.whatsapp.calling.voipcalling.Voip` (and `VoipNativeImpl`/`JNIUtils`) is **absent from the provided dump**; only its interface (`X/InterfaceC11390fM`) and the model/native classes around it are present. Those 621 referring classes were enumerated by scanning all 43,762 files in `sources\X`. Conclusions about WhatsApp behavior are drawn from the interface contract, the model classes, the native JNI classes, and the manifest — not from the native binary itself.
- VEIL evidence is from the exact files cited above.

---

## 8. Update — the rebuilt VEIL call engine (shipped)

The parity roadmap above is now implemented. The legacy WebRTC call path in
`WebRtcManager` is no longer used by the UI; a new self-contained engine under
`app/src/main/java/com/m3/veil/calling/` owns the whole call lifecycle.

### 8.1 Module inventory

| File | Lines |
|---|---|
| `calling/CallAudioDevice.java` | 316 |
| `calling/CallAudioRoute.java` | 12 |
| `calling/CallConfig.java` | 143 |
| `calling/CallE2ee.java` | 258 |
| `calling/CallEndReason.java` | 57 |
| `calling/CallEngine.java` | 1655 |
| `calling/CallInfo.java` | 181 |
| `calling/CallMediaFactory.java` | 195 |
| `calling/CallNotificationHelper.java` | 163 |
| `calling/CallParticipant.java` | 58 |
| `calling/CallSignal.java` | 145 |
| `calling/CallSignaling.java` | 161 |
| `calling/CallState.java` | 63 |
| `calling/CallStats.java` | 101 |
| `calling/CallType.java` | 18 |
| `calling/CallVideoDevice.java` | 327 |
| `calling/VeilCallService.java` | 92 |

Plus integration changes:

| File | Change |
|---|---|
| `ui/chat/CallActivity.java` | Rewritten on `CallEngine` (group calls, screen share, video-upgrade consent, real E2EE dialog, duration, PiP). |
| `manager/AuthManager.java` | Starts `CallEngine` on sign-in, shuts it down on sign-out. |
| `AndroidManifest.xml` | Adds FGS + microphone/camera/mediaProjection/phoneCall permissions, `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT`, declares `VeilCallService`, enables PiP on `CallActivity`. |

### 8.2 Each roadmap item, answered

1. **Real state machine** — 15-state `CallState` (NONE → PRECALLING → CALLING →
   PRE_ACCEPT_RECEIVED/RECEIVED_CALL → ACCEPT_SENT/ACCEPT_RECEIVED → ACTIVE /
   REJOINING / LINK / CONNECTED_LONELY / SCREENING / ENDING), with
   `isEnded/isRinging/isIncoming/isEstablished`.
2. **Accept gating** — the callee creates **no** `PeerConnection` and sends **no**
   answer until the human accepts. INVITE only arms ringing state.
3. **TURN / relay** — `CallConfig` holds STUN **and** TURN (global `setGlobalTurn`),
   `ALL` ICE transport, continual gathering, and a candidate pool.
4. **ICE restart** — `reconnectGraceMs` watchdog → `PeerConnection.restartIce()`
   → `CallEndReason.CONNECTION_LOST` if still dead.
5. **Separate PC per peer** — ending a call cannot drop the chat DataChannel.
6. **Call E2EE** — `CallE2ee` builds `FrameCryptorKeyProvider` + `FrameCryptor`
   (AES-GCM) per sender/receiver; the caller mints a 32-byte key that travels in
   `KEY_ANNOUNCE`/`INVITE`; a real safety number is derived with
   `EncryptionManager.hkdfSha256`.
7. **Screen share** — `CallVideoDevice` uses `CaptureController`/
   `ScreenCapturerAndroid` from a `MediaProjection` grant; the activity requests
   consent through `ActivityResultContracts.StartActivityForResult`.
8. **Group calls** — `startGroupCall` fans out invites with a roster; accepting
   peers join a full mesh using the same deterministic-offerer rule as 1:1.
   Simulcast is enabled for group video.
9. **Call logs / reason codes** — 16 `CallEndReason`s; the engine itself writes
   the encrypted `CallRecord` so missed/declined calls are logged even when the
   UI never opens.
10. **Polish** — DTMF (`RtpSender.dtmf()`), reactions, raise hand, low-data mode,
    live `CallStats` (via `RTCStatsReport`), audio-route cycling
    (earpiece/speaker/BT/wired/hearing-aid) and a proximity wake lock.
11. **Perfect negotiation** — deterministic initiator
    (`currentUserId.compareTo(peerId) < 0`), rollback on collision
    (`enableImplicitRollback`), and a one-outstanding-offer debounce, so video
    upgrade and screen share renegotiate without glare.

### 8.3 Where VEIL now exceeds WhatsApp on paper

| Capability | WhatsApp (observed) | VEIL (new engine) |
|---|---|---|
| Negotiation | proprietary native | open libwebrtc perfect negotiation |
| Frame E2EE | native, closed | `FrameCryptor` AES-GCM + HKDF safety number |
| Group transport | server-routed (likely SFU) | full mesh (works at small group sizes) |
| Reconnect | native | `restartIce()` + explicit `REJOINING` state |
| Call logs | local + server | SQLCipher-encrypted local log with reason codes |
| Screen share | yes (`ScreenSharerInfo`) | yes |
| Low-data mode | yes | yes |

### 8.4 Honest remaining gaps

- **Self-managed `ConnectionService`** — `MANAGE_OWN_CALLS` is declared but no
  `VeilTelecomConnectionService` yet, so calls do not appear in the system
  dialer / Android Auto.
- **SFU for large groups** — mesh is fine for small calls; >6–8 participants
  needs a server (WhatsApp uses one).
- **Call links & waiting rooms** — modelled in `CallSignal`/`CallInfo`, not yet
  wired to UI.
- **Screening / on-device ASR**, encoded video passthrough and call rating/spam
  reporting are not ported.
- The legacy `WebRtcManager` call methods remain in the tree (unused) and can be
  deleted once no release branch depends on them.

### 8.5 Second hardening pass

Three objective items were only *configurable* after the first pass; they are now
wired end-to-end.

| Item | Before | Now |
|---|---|---|
| **TURN / relay** | `CallConfig.setGlobalTurn` existed but nothing ever called it → effectively STUN-only | `CallEngine.loadTurnConfig()` reads `config/webrtc` (`turnUrls`, `turnUsername`, `turnPassword`); `setTurnServers(...)` applies it and live-reconfigures every PeerConnection via `pc.setConfiguration(...)` + `restartIce()`. `CallEngine.isTurnConfigured()` exposes the state. |
| **E2EE fingerprint verification** | safety number shown once in a dialog | `CallFingerprints` persists the per-peer fingerprint in app-private prefs, classifies it as NEW/MATCH/CHANGED, clears the verified flag on change, and `CallEngine` raises `onE2eeFingerprintChanged` → a "security code changed" warning. The dialog offers **Mark as verified / Clear verification**, and the in-call badge reads "🔒 Verified". |
| **Background call wake** | FCM `call_wake` started the legacy chat stack only | `MyFirebaseMessagingService` routes `call_wake` to `CallEngine.init(uid)`, which owns the `call_signals` listener and raises the full-screen incoming-call notification. |

Verification for this pass: `gradlew :app:assembleDebug --offline` → **BUILD
SUCCESSFUL**; the merged manifest was parsed and confirms the service and all
nine call-related permissions/attributes.

### 8.6 Third pass — reported call defects fixed

Three defects were reported from real device testing and traced to concrete
causes.

**1. "Switching to video shows only my own preview."** Two independent bugs:

- The audio-call path created the video transceiver with
  `addTransceiver(MEDIA_TYPE_VIDEO, RECV_ONLY)` but never stored its sender, and
  `applyLocalVideo` then skipped `setTrack` because the sender was null — so the
  upgraded call never transmitted video. The sender is now always captured.
- Under Unified Plan the remote track arrives on an existing transceiver's
  receiver and `onAddTrack` is not reliably invoked, so the remote video sink was
  never attached. `CallEngine.scanReceivers(peer)` now walks
  `pc.getReceivers()` after every remote description, on connect, and from
  `onAddTrack`, and attaches the first remote video track — this is what makes
  the remote picture appear.
- Switching to video is now immediate and mutual (WhatsApp behaviour): the
  camera starts locally, a `VIDEO_UPGRADE_REQUEST` is broadcast, and the peer
  mirrors it and replies `VIDEO_UPGRADE_ACCEPT` instead of blocking on a consent
  dialog.

**2. "The callee was offline; when it came online the call arrived late and
showed as missed."** Delivery and freshness were both wrong:

- **Delivery:** `firebase.json` already pointed at the `backend` Cloud Functions
  codebase, but it only contained `sendChatNotification` — there was **no call
  push**, so an offline/killed device learned about an INVITE only when its
  Firestore listener next attached. `backend/index.js` now has
  `sendCallNotification`, a Firestore trigger on
  `users/{callee}/call_signals/{id}` that sends a high-priority, 45 s-TTL,
  data-only FCM push (`call_wake` / `call_upgrade`) and a `call_end` push so a
  dead ring is torn down. Data-only keeps the notification and its full-screen
  intent under client control.
- **Freshness:** the engine ignored signal age, so a queued INVITE would ring
  long after the caller gave up. INVITEs older than `ringingTimeoutMs` are now
  dropped and recorded as a missed call (encrypted call-log row + a normal
  missed-call notification), and an LRU of ended call ids suppresses any late
  INVITE whose END/DECLINE already arrived.
- **Ordering:** `CallSignaling` now sorts a delivery batch by
  `CallSignal.timestamp` before dispatch, so a queued INVITE can never overtake
  its matching END. `DECLINE`/`END`/`OFFER`/`ANSWER`/`CANDIDATE` are also
  matched against the active `callId`, so a stale signal cannot end the wrong
  call.

**Deployment note:** the call push only works once the function is deployed:

```
firebase deploy --only functions
```

Until then the client-side freshness fixes still prevent late/missed ringing,
but an offline device will only be woken when its process next runs.



