# ConnectX — Security, Identity & Threat Model

> **Document Status:** Official Security Specification for Phase 0  
> **Last Updated:** 2026-10-23

---

## 1. Threat Model & Security Boundaries

```text
+-------------------+        Firebase ID Token (RS256)        +----------------------+
|                   | --------------------------------------> |                      |
|                   |                                         |  Trusted API         |
|  Mobile Client    | <-------------------------------------- |  (services/api)      |
|  (Untrusted)      |       Supabase Custom JWT (HS256)       |  Holds service_role  |
|                   |                                         +----------------------+
+-------------------+                                                    |
          |                                                              | PostgREST Admin /
          | Authenticated Supabase Custom JWT                            | Direct DB Query
          v                                                              v
+------------------------------------------------------------------------------------+
|                               Supabase PostgreSQL DB                               |
|                     Row Level Security (RLS) Enforced on auth.uid()                |
+------------------------------------------------------------------------------------+
```

### 1.1 Critical Rules
1. **Never Ship Secrets to Mobile:**
   - Supabase `service_role` key: **NEVER** in `.env.mobile` or app bundle.
   - Cloudinary `API_SECRET`: **NEVER** in mobile bundle.
   - Firebase Service Account private key: **NEVER** in mobile bundle.
2. **Never Trust Client-Asserted Identity:**
   - Any API request or WebSocket message carrying a `user_id` or `firebase_uid` in the body is **rejected** unless backed by a cryptographically verified token.
3. **Database Security (RLS Mandatory):**
   - Every single table in `supabase/migrations/` has `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;`.
   - Table permissions default to deny all (`RESTRICTIVE`). Explicit policies allow only participants/owners.

---

## 2. Firebase to Supabase Token Bridge Specification

### 2.1 The Authentication Flow
1. **Sign-In:** Client completes authentication with Firebase Auth SDK on device (Email/Password, Google, etc.).
2. **ID Token Retrieval:** Client requests `user.getIdToken(forceRefresh = false)`.
3. **Exchange Request:** Client invokes `POST /api/v1/auth/exchange-token` with `{ "idToken": "..." }`.
4. **Server-Side Verification:**
   ```typescript
   import admin from 'firebase-admin';
   const decodedToken = await admin.auth().verifyIdToken(idToken);
   const firebaseUid = decodedToken.uid;
   const email = decodedToken.email;
   ```
5. **Profile Resolution / Provisioning:**
   - Server checks `profiles` table for `firebase_uid == decodedToken.uid`.
   - If not found, server provisions a new profile:
     - Generates UUID `id`.
     - Sets `firebase_uid = decodedToken.uid`.
     - Sets `display_name = decodedToken.name || email.split('@')[0]`.
     - Initializes `user_settings` default record.
6. **Supabase Custom JWT Generation:**
   - The server signs a Supabase JWT with `SUPABASE_JWT_SECRET`:
   ```json
   {
     "sub": "b2c9a1d4-1111-4444-9999-abcdef123456",
     "role": "authenticated",
     "aud": "authenticated",
     "firebase_uid": "FirebaseUidString123",
     "email": "user@example.com",
     "iss": "connectx-auth-bridge",
     "iat": 1740000000,
     "exp": 1740003600
   }
   ```
7. **Client Session Initialization:**
   - Client receives `{ accessToken, profile, expiresIn }`.
   - Client configures Supabase client with this `accessToken`.
   - When communicating with Supabase PostgreSQL or Supabase Realtime, `auth.uid()` evaluates to the profile's UUID (`b2c9a1d4-...`).

---

## 3. Row Level Security (RLS) Strategy

All tables use `auth.uid()` which resolves to the UUID in the custom JWT's `sub` claim.

### 3.1 Policy Examples
- **`profiles`:**
  - `SELECT`: Allowed for any authenticated user (`auth.role() = 'authenticated'`).
  - `UPDATE`: Only allowed if `auth.uid() = id`.
- **`conversations` & `conversation_members`:**
  - `SELECT`: Only users who have an active row in `conversation_members` where `conversation_members.user_id = auth.uid()`.
- **`messages`:**
  - `SELECT`: Only users who are members of the message's `conversation_id`.
  - `INSERT`: Caller must be a member of `conversation_id` AND `sender_id = auth.uid()`.
  - `UPDATE`: Only allowed if `sender_id = auth.uid()` within 15 minutes of creation.
  - `DELETE`: Soft delete (`deleted_at = now()`) only if `sender_id = auth.uid()`.

---

## 4. Cloudinary Secure Media Flow

1. Client requests upload signature:
   `POST /api/v1/media/sign-upload` with:
   - `conversationId`: UUID
   - `resourceType`: `image` | `video` | `raw`
   - `sizeBytes`: Number (validated against max limits: image 10MB, video 50MB, file 25MB)
2. Backend verifies caller is member of `conversationId`.
3. Backend creates signed parameters using Cloudinary SDK:
   - `timestamp`
   - `folder`: `connectx/conversations/<conversationId>`
   - `signature`: SHA-1/SHA-256 HMAC of sorted parameters with `CLOUDINARY_API_SECRET`.
4. Client uploads directly to `https://api.cloudinary.com/v1_1/<cloud_name>/<resource_type>/upload`.
5. Mobile client does not know and cannot access the Cloudinary API secret.

---

## 5. WebRTC Signaling Authentication

1. The WebSocket connection URL is `wss://<signaling-host>/ws?token=<custom_jwt>`.
2. The signaling server decodes and verifies the JWT at connection handshake.
3. Every signaling message (`call.invite`, `webrtc.offer`, `screen_share.started`) enforces:
   - `payload.senderId === authenticatedUserId`.
   - The caller and recipient are either mutual contacts or members of the targeted conversation.
