# ConnectX — Modern Cross-Platform Communication Platform

ConnectX is a production-grade, secure, cross-platform mobile communication app for Android and iOS providing real-time messaging, voice/video calls, and full-device OS-authorized screen sharing.

---

## 🚀 Key Architectural Capabilities

- **Zero-Trust Identity Bridge:** Firebase Authentication for identity federation + trusted backend token exchange for Supabase Row-Level Security (RLS).
- **Real-Time Database & Presence:** Supabase PostgreSQL with rigorous RLS policies guaranteeing strict conversation isolation.
- **WebRTC Calling & Screen Sharing:**
  - 1:1 Voice & Video over WebRTC with STUN/TURN traversal.
  - Native Android `MediaProjection` foreground service compliant with Android 14+ (API 34/35).
  - Native iOS ReplayKit Broadcast Upload Extension.
  - Respects OS security perimeters (`FLAG_SECURE`, DRM, banking apps).
- **Secure Media Storage:** Cloudinary signed uploads mediated by server authorization—zero API secrets shipped to client devices.
- **WebSocket Signaling:** Typed, authenticated signaling server with heartbeat liveness and connection recovery.

---

## 📁 Repository Structure

```text
connectx/
├── apps/
│   └── mobile/           # React Native cross-platform mobile app
│       ├── android/      # Native Android code & ScreenCaptureService
│       ├── ios/          # Native iOS code & ReplayKit Broadcast Extension
│       └── src/          # TypeScript application logic & UI
├── services/
│   ├── api/              # Trusted backend (Firebase ID token verification & Supabase JWT minting)
│   ├── signaling/        # WebRTC WebSocket signaling server
│   └── workers/          # Push notification dispatchers
├── packages/
│   └── shared-types/     # Shared TypeScript models, DTOs & event protocols
├── supabase/
│   ├── migrations/       # SQL migrations with RLS policies
│   └── seed.sql          # Development test data
└── docs/                 # Specifications & Architectural Decision Records
    ├── TECH_DECISIONS.md
    ├── ARCHITECTURE.md
    ├── DATABASE.md
    ├── SECURITY.md
    ├── SCREEN_SHARING.md
    ├── API.md
    └── TEST_PLAN.md
```

---

## 🛠️ Quick Start & Local Setup

### 1. Prerequisites
- Node.js >= 20.0.0
- npm >= 10.0.0
- Java OpenJDK 17 or 21
- Android Studio / Android SDK (API 34/35)
- (macOS only) Xcode 15+ for iOS

### 2. Environment Configuration
Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```
Fill in your development credentials for Firebase, Supabase, and Cloudinary.

### 3. Installation & Building
```bash
# Install root and workspace dependencies
npm install

# Build shared types package
npm --prefix packages/shared-types run build

# Start the API server
npm --prefix services/api run dev

# Start the Signaling server
npm --prefix services/signaling run dev

# Run mobile app
npm --prefix apps/mobile run start
```

### 4. Running Automated Tests
```bash
npm test
```

---

## 🔒 Security & Screen Sharing Disclosure

ConnectX strictly adheres to operating system user privacy and security guidelines:
- **No Silent Capture:** Screen capture requires explicit, manual user gesture and system confirmation.
- **Protected Content:** In compliance with Android and iOS security models, DRM-protected media and surfaces flagged with `FLAG_SECURE` (e.g., banking apps) cannot be captured and will appear blank/black.
