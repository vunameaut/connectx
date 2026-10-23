# ConnectX — Technical Decisions & Architecture Record (ADR)

> **Document Status:** Official Record for Phase 0  
> **Last Updated:** 2026-10-23

---

## 1. Executive Summary & Goals

ConnectX is a production-grade, secure, cross-platform mobile communication platform providing real-time messaging, voice/video calls, and full-device OS-authorized screen sharing.

This document formalizes the foundational engineering decisions required by **Phase 0** of `ConnectX_AI_Project_Plan.md`.

---

## 2. Core Decisions & Evaluation Matrix

### 2.1 Mobile Framework: Bare React Native + TypeScript vs Expo Dev Client

- **Decision:** Bare React Native (0.76+) with New Architecture / TurboModules or Expo Bare Prebuild (`npx expo run:android` / `npx expo run:ios`).
- **Rationale:** 
  - Standard Expo Go does **not** support custom native code, Foreground Services with `mediaProjection` on Android 14+, or iOS Broadcast Upload Extensions (`RPBroadcastSampleHandler`).
  - Bare React Native with TypeScript gives full deterministic access to `AndroidManifest.xml`, Gradle build logic, native Android Services, iOS Podfiles, and native App Extension targets.
- **State Management:** **Zustand**.
  - Lightweight (< 2KB), boilerplate-free, supports decoupled slice-based state (auth, active call, conversations, network status).
  - Excellent interoperability with non-React listeners (WebSocket signaling, WebRTC track listeners, incoming call receivers).

---

### 2.2 Calling & Screen Sharing: Direct WebRTC vs LiveKit / SFU

- **Decision:** Hybrid Modular Approach with standard **WebRTC Native Foundation (`react-native-webrtc`)** for 1:1 P2P calling and an abstraction layer for **LiveKit SFU** for multi-party group calls.
- **Evaluation:**
  | Requirement | Direct WebRTC (P2P + Mesh) | LiveKit / SFU | Chosen Strategy |
  | :--- | :--- | :--- | :--- |
  | **1:1 Audio/Video Calls** | Zero server media latency, direct P2P, lowest cost | Requires SFU media server relay | **Direct WebRTC** for 1:1 |
  | **Screen Sharing in 1:1** | Native video track injected via `MediaProjection` / ReplayKit | Native video track published to room | **Direct WebRTC track exchange** |
  | **Group Calls (> 3 users)**| CPU/Bandwidth explodes ($N \times (N-1)$ streams) | Optimal downlink bandwidth, server-side simulcast | **SFU / LiveKit** boundary |
- **Signaling Layer:** Node.js + TypeScript WebSocket Server (`services/signaling`) with custom binary/JSON framing, JWT handshake authentication, heartbeat ping-pong, and strict state machine (`invite`, `ringing`, `offer`, `answer`, `ice_candidate`, `screen_share_toggle`, `end`).

---

### 2.3 Identity Strategy: Firebase Auth + Supabase Bridge

- **The Challenge:** Firebase Auth issues identity tokens (`kid`, RS256 signed by Google). Supabase PostgREST and Realtime authenticate via JWTs containing `sub`, `role: "authenticated"`, and claims signed by `SUPABASE_JWT_SECRET` (HS256) or custom asymmetric keys.
- **Decision:** **Trusted Auth Bridge & Token Exchange (Option B)** with Fallback Trusted API Proxy (Option A).
  - **Flow:**
    1. Mobile client authenticates with Firebase (Email/Password, Phone, Google).
    2. Client calls `POST /api/v1/auth/exchange-token` on `services/api` with `{ firebaseIdToken: "..." }`.
    3. Server verifies token via Firebase Admin SDK (`admin.auth().verifyIdToken()`).
    4. Server queries or creates the user in `public.profiles` (`firebase_uid -> id uuid`).
    5. Server mints a Supabase-compatible JWT:
       ```json
       {
         "sub": "<profile.id (UUID)>",
         "firebase_uid": "<firebase_uid>",
         "role": "authenticated",
         "aud": "authenticated",
         "email": "user@example.com",
         "app_metadata": { "provider": "firebase" },
         "user_metadata": { "display_name": "..." },
         "exp": 1740000000
       }
       ```
    6. Client initializes Supabase client with this custom session token:
       `supabase.auth.setSession({ access_token, refresh_token })` or configures Authorization header.
    7. All database queries enforce Row Level Security (RLS) using `auth.uid() = id`.
    8. Neither the client nor public bundles ever touch the Supabase `service_role` key.

---

### 2.4 Cloudinary Secure Media Upload Architecture

- **Decision:** Signed Server-Mediated Upload Authorization.
- **Rule:** Mobile app **never** holds `CLOUDINARY_API_SECRET`.
- **Flow:**
  1. Client sends metadata to `POST /api/v1/media/sign-upload` with MIME type, file size, conversation ID, and checksum.
  2. Server verifies caller is a member of the conversation and file complies with size/type policy.
  3. Server computes SHA-1/SHA-256 Cloudinary signature with timestamp and upload parameters.
  4. Client uploads directly to Cloudinary API using signed parameters.
  5. Client sends Cloudinary asset ID and resource details to `POST /api/v1/messages` (or writes to `messages` via Supabase RLS).
  6. Backend webhook or message verification ensures the asset is genuine before broadcasting.

---

### 2.5 Screen Sharing: Native Protocol & OS Compliance

- **Android:**
  - Target: Android 10 (API 29) to Android 15 (API 35+).
  - Mandatory Service: Foreground Service with `android:foregroundServiceType="mediaProjection"`.
  - Intent flow: `MediaProjectionManager.createScreenCaptureIntent()` -> user accepts system consent prompt -> start foreground service with persistent notification -> obtain `MediaProjection` via `getMediaProjection(resultCode, data)`.
  - Surface capture via `VirtualDisplay` feeding a WebRTC `VideoCapturer` (`ScreenCapturerAndroid`).
- **iOS:**
  - iOS 13+ Broadcast Upload Extension (`RPBroadcastSampleHandler`) linked to App Group.
  - UI trigger: `RPSystemBroadcastPickerView`.
  - Memory budget: Max 50MB resident memory inside extension.
- **Protected Content / DRM / FLAG_SECURE:**
  - OS compositor renders black frames for banking apps, protected DRM (Widevine, FairPlay), and OS-protected surfaces.
  - No bypass is possible or legally permitted. The app must gracefully display a placeholder notification if black frames are detected.

---

## 3. Technology Version Matrix

| Dependency / Component | Pinned Version / Requirement | Justification |
| :--- | :--- | :--- |
| **Node.js** | `>= 20.0.0` (Active LTS) | Compatible with modern ES modules, crypto, and Fastify/Express |
| **TypeScript** | `^5.6.0` | Strict type checking, modern decorators, satisfies operator |
| **React Native** | `0.76.x` / `0.77.x` | Stable modern architecture, Android 15 target SDK 35 support |
| **Firebase Admin (Server)** | `^12.7.0` | Official Google SDK for ID token verification and FCM v1 HTTP API |
| **Supabase JS Client** | `^2.48.0` | Modern PostgREST v12 + Realtime v2 integration |
| **react-native-webrtc** | `^124.0.0` | Modern libwebrtc M124+ build with Android 14+ MediaProjection fixes |
| **Fastify** (services/api) | `^5.0.0` | High throughput, low latency JSON schema validation |
| **ws** (services/signaling) | `^8.18.0` | Standard, robust WebSocket server with ping/pong timeouts |
