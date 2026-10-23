# ConnectX — Master Test Plan & Verification Criteria

> **Document Status:** Official Test Plan for AI Agents & Developers  
> **Testing Pillars:** Unit, Integration, RLS Authorization, Native OS Lifecycle, E2E Real-Device Verification

---

## 1. Testing Strategy Overview

ConnectX follows a rigorous verification pyramid:
1. **Unit Tests:** Pure logic (auth token decoding, signaling state machine, message idempotency, payload validation).
2. **Database & RLS Policy Tests:** Automated tests asserting both **PERMITTED** operations and **REJECTED** unauthorized breaches.
3. **Integration & API Tests:** Mocked external dependencies (Firebase Admin, Cloudinary, Coturn) verifying full HTTP/WS contracts.
4. **Native Module & OS Lifecycle Tests:** Verifying Android `MediaProjection` foreground service and iOS ReplayKit extension constraints.
5. **Physical Device E2E Verification:** Mandatory for screen capture, audio focus, network handoff, and backgrounding.

---

## 2. Phase 0 Acceptance Criteria & Exit Gates

| ID | Test Case | Target Component | Expected Result | Status |
| :--- | :--- | :--- | :--- | :--- |
| **TC-0.1** | Environment & Node/SDK Verification | Host Environment | Node >= 20, Java JDK 21, Android SDK path verified | **PASSED** |
| **TC-0.2** | Monorepo Structure Scaffolding | Repository | All apps, services, packages, docs, migrations present | In Progress |
| **TC-0.3** | Firebase to Supabase Token Bridge | `services/api` | Valid Firebase token produces profile & signed Supabase JWT; invalid token rejected 401 | Ready for impl |
| **TC-0.4** | Android MediaProjection Service Lifecycle | `apps/mobile/android` | Foreground service starts with type `mediaProjection` on Android 14+ without SecurityException | Ready for impl |
| **TC-0.5** | iOS ReplayKit Sample Handler | `apps/mobile/ios` | Broadcast upload handler receives sample buffers within 50MB memory ceiling | Ready for impl |
| **TC-0.6** | Security Surface / FLAG_SECURE Verification | Native Screen Capture | OS compositor blacks out protected windows; no app crashes | Verified by OS design |

---

## 3. Screen Sharing Verification Protocol (Release Blocker)

### Mandatory Test Matrix on Physical Hardware
1. **Explicit Prompt Verification:**
   - Tap "Share Screen" -> System modal must appear ("Start recording or casting with ConnectX?").
   - If user taps "Cancel" -> WebRTC track is not initiated; UI reverts smoothly without error.
   - If user taps "Start now" -> Foreground service icon appears in notification shade.
2. **Remote Participant Rendering:**
   - The remote peer receives `screen_share.started` signaling event.
   - Remote UI automatically creates an `RTCView` showing the shared screen stream.
3. **In-App & OS Stop Controls:**
   - Case A: User taps "Stop Sharing" button in ConnectX call overlay -> Capture stops, remote screen view closes.
   - Case B: User opens Android notification bar and taps "Stop" on the persistent service notification -> Android triggers `MediaProjection.Callback.onStop()`, ConnectX cleanly notifies remote peer via signaling and stops the track.
   - Case C: Call is terminated (either party hangs up) -> Foreground service stops immediately; notification dismissed.
4. **Protected Content Handling:**
   - Launch bank app or secure screen while sharing.
   - Remote screen displays black surface for that region/window.
   - Native logs verify no security fault or uncaught exception.

---

## 4. Database RLS Automated Test Suite

Every RLS policy in `supabase/migrations/` must be verified with automated test scripts:
- **Test RLS-1 (Profile Isolation):** User A can view User B's public profile, but cannot update User B's `display_name` or `bio`.
- **Test RLS-2 (Conversation Privacy):** User C (not in group conversation G) receives 0 rows when executing `SELECT * FROM messages WHERE conversation_id = G`.
- **Test RLS-3 (Message Integrity):** User A cannot insert a message claiming `sender_id = User B`.
- **Test RLS-4 (Soft-delete only):** User A cannot permanently delete records from `messages`, only update `deleted_at = now()`.

---

## 5. Execution Commands

- **Run all unit & integration tests:**
  ```bash
  npm test
  ```
- **Run API service tests:**
  ```bash
  npm --prefix services/api test
  ```
- **Run Signaling tests:**
  ```bash
  npm --prefix services/signaling test
  ```
- **Run Supabase migration verification:**
  ```bash
  npx supabase test db
  ```
