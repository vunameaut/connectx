# Instructions for AI Coding Agents

> This document defines the engineering standards and execution guidelines for AI agents working on the ConnectX codebase.

---

## 1. Ground Rules

1. **Source of Truth:** `ConnectX_AI_Project_Plan.md` and documents in `docs/` are the official project specifications.
2. **Phase Completion Discipline:** Complete each milestone in sequence. Never mark a milestone done without real code, verified error handling, tests, and honest status reports.
3. **No Fake Integration:** Never pretend external services (Firebase, Supabase, Cloudinary, TURN) are connected when credentials are missing. Use clear integration boundaries, fallback mocks in test suites, and informative configuration warnings.
4. **Zero Secret Leakage:** Never write private keys (`service_role`, `CLOUDINARY_API_SECRET`, service account private keys) into mobile apps or public configs.
5. **Strict TypeScript:** No implicit `any`. All schemas, API payloads, and signaling events must be strongly typed using `packages/shared-types`.
6. **Screen Sharing Constraints:** Always adhere to OS permissions, user consent dialogs, persistent foreground service notifications, and `FLAG_SECURE` boundaries.

---

## 2. Testing & Verification Requirements

- Every backend endpoint in `services/api` must have corresponding automated tests (Jest/Vitest).
- Every signaling event in `services/signaling` must have unit tests verifying state transitions and unauthorized message rejection.
- Database changes must be packaged as SQL migrations in `supabase/migrations/` with explicit Row Level Security policies.
