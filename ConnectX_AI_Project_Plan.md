# ConnectX — Project Specification for AI Coding Agents

> **Document purpose:** This file is the source of truth for an AI coding agent to plan, implement, test, and document a cross-platform mobile communication app similar in broad functionality to Zalo.
>
> **Project status:** Specification / implementation plan. Do not claim that features are implemented until code and tests demonstrate them.

## 1. Product goal

Build **ConnectX**, a mobile communication app for Android and iOS with:

- Account registration, login, logout, and account recovery.
- User profiles, contacts, search, and presence.
- One-to-one and group messaging.
- Text, image, video, and file messages.
- Voice calls and video calls.
- **Screen sharing during a call**, including OS-authorized full-device screen capture where supported.
- Push notifications for messages and incoming calls.
- Privacy, permission, blocking, reporting, and session controls.

The screen-sharing requirement is a core requirement, not a future optional feature. Design the architecture for it from the beginning.

## 2. Critical platform and security constraints

1. Use only official OS-supported screen-capture APIs:
   - Android: `MediaProjection`, with the required user consent flow and foreground service/notification where required by the Android version.
   - iOS: ReplayKit / Broadcast Upload Extension as appropriate, with the required system UI and entitlements.
2. Do **not** promise that every app or screen will be visible. Banking apps, DRM content, secure windows, authentication screens, OS surfaces, and other protected content may appear black, be omitted, or be restricted by the OS or source app.
3. Never bypass `FLAG_SECURE`, ReplayKit restrictions, OS privacy indicators, consent dialogs, or another app's security protections.
4. Before screen capture starts, clearly explain what will be shared and obtain the user's explicit action. Show a persistent, obvious sharing state and provide a one-tap stop control.
5. Never silently capture a screen, bypass permission prompts, or continue sharing after the user stops or the OS revokes permission.
6. Protect authentication tokens, media access, and call signaling. Do not put service-role keys or private API secrets in the mobile app.
7. Do not claim end-to-end encryption unless it has actually been designed, implemented, independently reviewed, and tested. Document the real encryption model.

## 3. Proposed technology stack

Choose and pin compatible stable versions during project setup. Record version decisions in `docs/TECH_DECISIONS.md`.

### Mobile client
- React Native + TypeScript.
- React Native CLI / bare workflow, or an Expo development build with custom native modules. **Do not rely on Expo Go** for native screen-sharing and call integrations.
- React Navigation or Expo Router.
- TanStack Query for server state where useful.
- Zustand or Redux Toolkit for local application state; choose one and document why.
- React Native WebRTC integration for media calls, after verifying current Android/iOS compatibility.

### Identity and notifications
- Firebase Authentication for registration, login, logout, password reset, and supported identity providers.
- Firebase Cloud Messaging (FCM) for Android push notifications.
- APNs integration for iOS push notifications, using the appropriate Firebase integration if FCM is used.
- Validate Firebase ID tokens on trusted backend endpoints before accepting privileged requests.

### Database and realtime
- Supabase PostgreSQL is the source of truth for application data.
- Supabase Realtime may deliver database changes and presence events where appropriate.
- Verify the Firebase identity on the trusted backend and map Firebase UID to the application profile. Do not assume Supabase Auth owns the same identity.
- Enable Row Level Security (RLS) for exposed tables. Use policies that enforce conversation membership and ownership.
- Never ship the Supabase `service_role` key to clients.

### Media storage
- Cloudinary stores user-uploaded images, videos, avatars, and thumbnails.
- Upload through a trusted backend-issued signed upload authorization, or another secure server-mediated flow. Do not embed Cloudinary API secrets in the app.
- Keep media metadata and authorized asset identifiers in Supabase.
- Use access controls and short-lived signed delivery URLs for private content where supported by the chosen Cloudinary setup.
- Voice/video call streams and live screen-sharing streams are real-time media, not Cloudinary uploads.

### Calling and screen sharing
- WebRTC for one-to-one voice/video and screen media tracks.
- A signaling service over authenticated WebSocket/HTTPS for offers, answers, ICE candidates, ringing, and call state.
- Use a TURN server for connectivity across restrictive networks.
- For group calls, evaluate LiveKit or another SFU-based service. Do not route a group call by creating a full mesh of peer connections unless the expected scale is explicitly small.
- Android screen capture: native module around `MediaProjection`.
- iOS screen capture: native ReplayKit / Broadcast Upload Extension integration as appropriate.
- Design and test call lifecycle handling for backgrounding, interruptions, Bluetooth/headset changes, permission revocation, and network changes.

## 4. Architecture

Use a modular architecture. Keep UI, business logic, persistence, and native media integration separate.

```text
connectx/
├── apps/
│   └── mobile/
│       ├── src/
│       │   ├── app/                 # Navigation and app bootstrap
│       │   ├── components/          # Shared UI components
│       │   ├── features/
│       │   │   ├── auth/
│       │   │   ├── profile/
│       │   │   ├── contacts/
│       │   │   ├── conversations/
│       │   │   ├── messaging/
│       │   │   ├── media/
│       │   │   ├── calls/
│       │   │   ├── screen-sharing/
│       │   │   ├── notifications/
│       │   │   └── settings/
│       │   ├── navigation/
│       │   ├── services/            # Firebase, Supabase, Cloudinary clients
│       │   ├── state/
│       │   ├── hooks/
│       │   ├── types/
│       │   └── utils/
│       ├── android/                 # Native Android integration
│       └── ios/                     # Native iOS integration
├── services/
│   ├── api/                         # Trusted API / token verification
│   ├── signaling/                   # Authenticated WebSocket signaling
│   └── workers/                     # Push delivery and async jobs
├── supabase/
│   ├── migrations/
│   ├── seed.sql
│   └── config.toml
├── packages/
│   └── shared-types/
├── docs/
│   ├── TECH_DECISIONS.md
│   ├── ARCHITECTURE.md
│   ├── DATABASE.md
│   ├── SECURITY.md
│   ├── SCREEN_SHARING.md
│   ├── API.md
│   └── TEST_PLAN.md
├── .env.example
├── README.md
└── AGENTS.md
```

This is a suggested structure. If tooling or package compatibility requires a different structure, document the change before applying it.

## 5. Core user flows

### 5.1 Authentication
- Register with email/password; add phone or social login only when configured and tested.
- Verify email where applicable.
- Log in, log out, reset password, and handle expired/revoked sessions.
- Create or update a profile associated with the Firebase UID.
- Never trust a UID sent by the client without verifying the Firebase ID token.

### 5.2 Contacts and profiles
- Search users by an allowed identifier.
- View profile photo, display name, status, and privacy-controlled presence.
- Add/remove contacts, block/unblock users, and report abuse.
- Avoid exposing email, phone, or other private fields in public user search results.

### 5.3 Messaging
- Direct and group conversations.
- Send text, image, video, and file messages.
- Message states: sending, sent, failed; optionally delivered and read receipts.
- Reply, edit/delete own messages according to documented policy, and react to messages.
- Pagination / cursor-based history loading.
- Realtime updates with reconnect and duplicate-event handling.
- Offline retry and optimistic UI where safe.
- Define maximum upload size and allowed MIME types on both client and server.
- Prevent unauthorized users from reading conversation history or media.

### 5.4 Voice and video calls
- One-to-one voice and video calls first.
- Incoming-call screen, accept/reject, cancel, timeout, busy, and missed-call handling.
- Mute/unmute microphone, enable/disable camera, switch camera, speaker/Bluetooth route where supported.
- Network quality indicators and reconnect behavior.
- Call history and call termination reasons.
- Group calls are a separate milestone after one-to-one reliability is demonstrated.

### 5.5 Screen sharing — mandatory
- Start screen sharing from an active call.
- Explain system capture consent and show OS-controlled picker/confirmation.
- Android: capture using `MediaProjection` and a compliant foreground-service lifecycle.
- iOS: use ReplayKit and required extension/entitlements; verify current platform limitations before implementation.
- Publish the captured frames as a WebRTC video track or the chosen media SDK's screen-share track.
- Remote participant sees a screen-share tile; provide a clear way to switch between participant video and shared screen.
- Keep microphone, camera, and screen sharing as independent toggles.
- Show a persistent indicator while sharing.
- Stop sharing from the app and handle stop events from OS UI, permission revocation, process interruption, and call end.
- Handle rotation, resolution/frame-rate changes, app backgrounding, and thermal/battery constraints.
- Test with ordinary apps and protected-content examples. Expected protected-content behavior is to respect the OS/source app restrictions, not bypass them.
- Document that the user must initiate sharing and can stop it at any time.

### 5.6 Notifications
- Push notifications for new messages and incoming calls.
- Avoid putting sensitive message contents in notifications by default; make previews configurable.
- Handle notification taps and route to the correct conversation/call.
- Expire stale incoming-call notifications and prevent accepting an already-ended call.

## 6. Initial database design

Use UUID primary keys for application records unless there is a documented reason not to. Use UTC timestamps. Add indexes for foreign keys, membership lookups, and chronological pagination. Define and test RLS policies before production.

### `profiles`
- `id uuid primary key`
- `firebase_uid text unique not null`
- `display_name text not null`
- `avatar_asset_id text null`
- `bio text null`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`

### `user_settings`
- `user_id uuid primary key references profiles(id)`
- `allow_messages_from text`
- `show_presence boolean`
- `read_receipts_enabled boolean`
- `notification_preferences jsonb`
- `updated_at timestamptz`

### `contacts`
- `owner_id uuid references profiles(id)`
- `contact_id uuid references profiles(id)`
- `status text`
- `created_at timestamptz`
- Composite uniqueness / checks to prevent invalid duplicates.

### `conversations`
- `id uuid primary key`
- `kind text not null` — `direct` or `group`
- `title text null`
- `created_by uuid references profiles(id)`
- `created_at timestamptz`
- `updated_at timestamptz`

### `conversation_members`
- `conversation_id uuid references conversations(id)`
- `user_id uuid references profiles(id)`
- `role text`
- `joined_at timestamptz`
- `last_read_message_id uuid null`
- Composite primary key `(conversation_id, user_id)`.

### `messages`
- `id uuid primary key`
- `conversation_id uuid references conversations(id)`
- `sender_id uuid references profiles(id)`
- `message_type text not null` — `text`, `image`, `video`, `file`, `system`
- `text_body text null`
- `reply_to_message_id uuid null`
- `created_at timestamptz not null`
- `edited_at timestamptz null`
- `deleted_at timestamptz null`
- Add a stable client-generated idempotency key to prevent duplicate sends on retry.

### `message_attachments`
- `id uuid primary key`
- `message_id uuid references messages(id)`
- `provider text not null` — initially `cloudinary`
- `asset_id text not null`
- `resource_type text not null`
- `mime_type text null`
- `size_bytes bigint null`
- `width int null`
- `height int null`
- `duration_ms bigint null`
- `created_at timestamptz`

### `calls`
- `id uuid primary key`
- `conversation_id uuid null references conversations(id)`
- `initiated_by uuid references profiles(id)`
- `call_type text not null` — `voice`, `video`
- `status text not null`
- `started_at timestamptz null`
- `ended_at timestamptz null`
- `end_reason text null`
- Do not store raw media streams in the database.

### `call_participants`
- `call_id uuid references calls(id)`
- `user_id uuid references profiles(id)`
- `joined_at timestamptz null`
- `left_at timestamptz null`
- `role text null`
- Composite primary key `(call_id, user_id)` if rejoin semantics are handled separately.

### `devices`
- `id uuid primary key`
- `user_id uuid references profiles(id)`
- `push_token text`
- `platform text`
- `updated_at timestamptz`
- Store tokens securely, support rotation, and remove invalid tokens.

### Additional tables as needed
- `blocks`, `reports`, `message_reactions`, `call_events`, `group_invites`, and `audit_events` with restricted access and retention policies.

**Schema rules**
- Generate and version SQL migrations in `supabase/migrations/`; never rely on dashboard-only manual changes.
- Add check constraints or enums for statuses and message types.
- Use database transactions for operations that must be atomic.
- Avoid exposing internal provider secrets, private moderation data, or unrestricted user directories.
- Presence is ephemeral and should not be treated as durable truth in a profile row.

## 7. API and identity boundary

The app uses Firebase Authentication for identity, but Supabase stores application data. Implement a trusted server boundary:

1. Client signs in with Firebase.
2. Client obtains a Firebase ID token.
3. Client calls the trusted API or an explicitly designed Supabase-compatible verification layer with that token.
4. Server verifies the token using Firebase Admin SDK and resolves `firebase_uid` to `profiles.id`.
5. Server authorizes the requested action and enforces conversation membership.
6. For database access, use a documented, tested strategy that does not expose the Supabase service-role key. If direct client access is required, implement a secure custom JWT / authorization bridge compatible with Supabase and test RLS thoroughly. Otherwise route sensitive operations through the trusted API.
7. For WebSocket signaling, verify identity at connection and authorize every call/conversation action. Revalidate expiry and revoke access when appropriate.

Do not assume a Firebase ID token is automatically accepted as a Supabase access token. Document the chosen integration and its security properties in `docs/SECURITY.md`.

## 8. Realtime and signaling events

Define typed, versioned event payloads. Suggested signaling events:

- `call.invite`
- `call.ringing`
- `call.accept`
- `call.reject`
- `call.cancel`
- `call.end`
- `webrtc.offer`
- `webrtc.answer`
- `webrtc.ice_candidate`
- `screen_share.started`
- `screen_share.stopped`
- `participant.mute_changed`
- `participant.camera_changed`
- `call.network_state`

Requirements:
- Authenticate every connection and authorize each event.
- Validate payload schemas and reject unknown or malformed events.
- Rate-limit invitations and signaling messages.
- Handle retries, reconnects, duplicate events, and out-of-order delivery.
- Do not trust client-supplied participant IDs or call membership.
- Never log SDP, ICE credentials, tokens, or private message content unnecessarily.

## 9. Security and privacy requirements

- Store only public client configuration in mobile bundles. Secrets belong on trusted servers.
- Apply RLS to every exposed table and test both allowed and denied cases.
- Use Firebase App Check where appropriate, while not treating it as a substitute for authorization.
- Verify all upload authorization server-side and validate file type, size, and ownership.
- Protect against IDOR, enumeration, spam, replay, and unauthorized group membership.
- Apply request limits, WebSocket rate limits, and abuse-reporting mechanisms.
- Use TLS for network traffic.
- Define account deletion, data retention, export, and media cleanup procedures.
- Ask for permissions only when needed and explain their purpose.
- Never record calls or screen sharing without explicit user consent and a clear indicator.
- Keep sensitive notification previews off by default or make them configurable.
- Document limitations and threat model. Do not claim compliance or E2EE without evidence.

## 10. UI screens

Minimum screens:
1. Splash / initialization.
2. Register / login / password reset.
3. Profile setup.
4. Main tabs: Chats, Contacts, Calls, Settings.
5. Conversation list and search.
6. Direct chat.
7. Group chat and group member management.
8. User profile and contact actions.
9. Incoming call.
10. Active voice call.
11. Active video call.
12. Active call with screen sharing.
13. Call history.
14. Notification and privacy settings.
15. Blocked users and report flow.
16. Upload progress, failure, retry, and permission-denied states.

Accessibility requirements:
- Support screen readers and scalable text.
- Provide visible labels for call controls.
- Do not rely on color alone for status.
- Make the stop-sharing control easy to locate and operate.

## 11. Environment configuration

Create `.env.example` containing variable names and safe placeholders only. Never commit real secrets.

Example variable categories:
- Firebase public app configuration required by the selected SDK.
- Supabase project URL and anon/publishable key.
- Cloudinary cloud name and upload preset only if the preset is intentionally safe for the specific flow.
- API base URL.
- Signaling WebSocket URL.
- TURN server URL and credentials supplied securely / short-lived where possible.
- LiveKit URL and client-issued access-token endpoint if LiveKit is selected.

Never include Firebase Admin credentials, Supabase service-role key, Cloudinary API secret, permanent TURN credentials, or signing keys in the mobile app or `.env.example`.

## 12. Implementation milestones

### Phase 0 — Discovery and technical proof
- Inspect current SDK compatibility and platform requirements.
- Decide direct WebRTC vs LiveKit/SFU, signaling hosting, TURN provider, upload security, and Firebase-to-Supabase identity strategy.
- Create `docs/TECH_DECISIONS.md`.
- Build a minimal native proof of concept for screen capture on a physical Android device and a physical iPhone.
- Record platform limitations and test results in `docs/SCREEN_SHARING.md`.
- **Exit gate:** do not proceed to feature-complete claims until capture and remote delivery feasibility is demonstrated on both target platforms.

### Phase 1 — Project foundation
- Create repository structure, TypeScript rules, linting, formatting, tests, CI, and environment validation.
- Build navigation and design system.
- Add crash/error handling and structured redacted logs.

### Phase 2 — Identity and profiles
- Implement Firebase Auth flows.
- Add trusted token verification and profile creation/update.
- Add RLS / authorization tests and account recovery.

### Phase 3 — Messaging
- Apply database migrations.
- Implement contacts, direct/group conversations, text messaging, pagination, realtime updates, retries, and read states.
- Add Cloudinary upload flow and attachment messages.
- Test offline/reconnect behavior and access control.

### Phase 4 — Voice/video calls
- Implement signaling, TURN, incoming calls, accept/reject/end, one-to-one voice/video, and call history.
- Test on real networks and physical devices.

### Phase 5 — Screen sharing
- Integrate Android MediaProjection and iOS ReplayKit.
- Publish/receive screen track through the call media layer.
- Add permission UX, persistent indicator, stop controls, and failure handling.
- Test protected content and explicitly document expected restrictions.
- **Exit gate:** remote participant can view the authorized screen share, and both app and OS stop paths work reliably.

### Phase 6 — Push and lifecycle hardening
- Integrate FCM/APNs.
- Handle background/foreground transitions, incoming-call timeouts, audio interruptions, network switching, and token rotation.
- Test battery and thermal impact.

### Phase 7 — Production readiness
- Security review, dependency audit, database backup/restore test, rate limits, monitoring, crash reporting, privacy policy, data deletion, release signing, and store review preparation.
- Conduct a limited beta before general release.

## 13. Testing and acceptance criteria

Do not mark a feature complete until its acceptance tests pass.

### Authentication
- Valid registration/login succeeds.
- Invalid credentials and expired sessions are handled safely.
- Backend rejects forged, expired, or revoked identity tokens.
- A user cannot read or mutate another user's profile fields without permission.

### Messaging
- Two authorized users can exchange messages in realtime.
- Messages remain available after app restart.
- Pagination does not skip or duplicate records.
- Retries do not create duplicate messages.
- Non-members cannot read messages, attachments, or group metadata.
- Upload failure shows a useful error and supports retry.

### Calls
- A callee can receive, accept, reject, and end a call.
- Call timeout and missed-call states work.
- Mute/camera toggles reflect actual media state.
- TURN relay works when direct peer connectivity fails.
- Network loss and app lifecycle transitions do not leave ghost calls.

### Screen sharing — release blocker
- User must explicitly initiate capture.
- OS permission/consent flow is shown.
- Remote participant receives the screen track.
- A persistent sharing indicator is visible.
- User can stop sharing from the app.
- OS stop / permission revocation stops the track.
- Ending the call stops sharing and releases capture resources.
- No hidden capture or bypass of protected-content controls occurs.
- Test at minimum on physical Android and iOS devices; simulators alone are insufficient.
- Record supported OS versions, known limitations, and results in `docs/SCREEN_SHARING.md`.

### Reliability and security
- RLS tests cover both allowed and denied access.
- No privileged secret appears in source control, logs, or mobile bundle.
- App handles offline state, retry, and reconnect.
- Accessibility and permission-denied states are tested.
- CI runs lint, type checking, unit tests, and relevant integration tests.

## 14. Instructions for the AI coding agent

1. Read this file completely before changing code.
2. Inspect the repository and existing code before creating or replacing files.
3. If the repository is empty, scaffold the project using the architecture above.
4. Work in small, verifiable milestones. Do not generate the entire app as one untested code dump.
5. Before implementing each milestone, list the files to create/change and the acceptance criteria.
6. Implement real, connected flows. Do not substitute static mock screens for working features unless explicitly labelled as mocks.
7. Keep TypeScript strict. Avoid `any` unless justified and documented.
8. Use migrations for schema changes and add tests for authorization.
9. Keep secrets out of source control. Update `.env.example`, not real credentials.
10. If credentials, provider setup, device access, or signing configuration are missing, implement the integration boundary and clearly document exactly what the developer must configure. Never fabricate successful external integration.
11. Verify package versions and official platform requirements instead of guessing API names.
12. After each milestone, run available lint, type checks, tests, and builds. Report commands, results, and failures honestly.
13. Update README and relevant docs whenever architecture, setup, limitations, or environment variables change.
14. Do not claim the app is production-ready until acceptance criteria, security review, and real-device tests have passed.
15. Start with **Phase 0**, especially the native screen-sharing proof of concept and Firebase/Supabase authorization design.

## 15. Definition of done

The project is ready for a controlled beta only when:
- Android and iOS builds run on real devices.
- Firebase authentication and server-side identity verification work.
- Supabase schema, migrations, and RLS policies are tested.
- Cloudinary uploads use a secure authorization flow.
- Direct messaging works with reconnect and persistence.
- One-to-one voice/video calling works across realistic networks.
- Screen sharing is received remotely on supported devices, has explicit consent and clear stop controls, and respects OS/source-app restrictions.
- Push notifications, privacy controls, logging, and error states are tested.
- Setup instructions and known limitations are documented.

**Do not weaken the screen-sharing safety constraints to satisfy a demo.** The goal is reliable, user-consented screen sharing within the limits of Android and iOS.
