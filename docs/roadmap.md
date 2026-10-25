# Incremental delivery roadmap

No product feature is implemented or verified yet. Foundation checks verify
repository files only. Follow-up feature issues must be scoped before code, with
contracts, tests, and a PR for each logical change.

## Recorded decisions and remaining questions

The owner selected **Go** on 2026-10-25. Use PostgreSQL, private object storage,
REST/WebSockets, FCM, explicit migrations, and one modular backend. Shared saved
status and deletion for both partners, including cleanup on unpair/account
deletion, were explicitly approved. See [architecture](architecture.md) and
[privacy](privacy.md).

The owner then asked for immediate code pairing and optional Google persistence,
revising the earlier email/password onboarding choice. The current proposal is:

- Automatically create an anonymous Firebase identity; show pairing without a
  sign-in form. It still supplies verified tokens for secure backend ownership.
- Offer Google linking for recovery/another device, preserving the same Firebase
  UID and couple membership. Linking adds a recovery identity; it does not
  export media to Google Drive.
- Explain that guest-only access may be lost after clearing app data or
  reinstalling. Do not turn on automatic anonymous-account cleanup, which could
  delete long-lived guests. Existing exchanges do not expire automatically.
- If a Google credential already belongs to another account, preserve the
  current guest session and show a recovery/conflict path. Do not silently
  switch, merge couples, or transfer private data. Recovery semantics need tests
  before release.
- Both people consent to code pairing: claimant accepts, then creator confirms;
  only then does the backend create an active couple.

Status: **guest-first access, optional Google linking, and mutual pairing
confirmation approved by the owner on 2026-10-25**. No identity code has been
written. This design follows Firebase's
[anonymous authentication](https://firebase.google.com/docs/auth/android/anonymous-auth)
and
[credential linking](https://firebase.google.com/docs/auth/android/account-linking)
capabilities. Use
[Google sign-in](https://firebase.google.com/docs/auth/android/google-signin)
with the supported Android integration and test recovery with the
[Auth Emulator](https://firebase.google.com/docs/emulator-suite/connect_auth).

Other decisions, with the step they block:

| Decision                                                  | Initial recommendation                                                     | Needed before                              |
| --------------------------------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------ |
| Android application ID and supported versions             | Confirm an owned stable namespace; API 26 minimum is a proposal            | Android scaffold                           |
| Guest recovery and invitation UX/limits                   | Anonymous → optional Google; mutual confirmation; short-lived random codes | Identity/pairing implementation            |
| Hosting, storage provider, region, and budget             | One small service, managed PostgreSQL, one private bucket near users       | Media/deployment and real user data        |
| Object/backup/log deletion windows                        | Bounded, documented retention with retry metrics and restore tombstones    | External testing                           |
| Widget offline preview exposure                           | Hidden by default; explicit opt-in and bounded revalidation                | Memory widget                              |
| End-to-end encryption expectation                         | TLS + private storage encryption initially; this is not E2EE               | Media design, before collecting real media |
| Logo/design and license                                   | Provisional documentation logo; choose license explicitly                  | Brand finalization/public distribution     |
| Game rounds, prompt source, guess limits, cancellation UX | Small documented rule sets, no ranking                                     | Each game contract                         |

Do not let deployment or game choices block the repository foundation. Ask the
owner about irreversible/security-sensitive decisions at the relevant milestone.

## M0: repository foundation

[Issue #1](https://github.com/atluixx/usual/issues/1): concise README,
contributor workflow, issue/PR templates, selected stack, domain/privacy/API
proposals, branding plan, and reproducible documentation/configuration CI.
Acceptance is the issue's checklist plus actual local/remote check results in
the linked PR. No APK, backend, feature completion, or production deployment is
part of M0.

## M1: first drawing between two people

Build this vertical slice in ordered PRs, not one generated application:

1. [Go foundation — #2](https://github.com/atluixx/usual/issues/2): runnable
   service, configuration, PostgreSQL/migration tooling, health checks, backend
   CI.
2. [Android shell and branding — #3](https://github.com/atluixx/usual/issues/3):
   Compose app, stable ID, generated name/logo resources, build/lint checks.
   This can proceed independently of backend feature work after the ID is
   agreed.
3. [Identity/pairing APIs — #4](https://github.com/atluixx/usual/issues/4):
   finalize guest/Google ownership, publish contract, constrain/authorize
   pairing in real PostgreSQL, test expiry/revocation/reuse/concurrency and
   unpair access revocation.
4. Next scoped issue: Android guest/Google and pairing screens against that
   contract, with loading, empty, failure, and retry states. Verify restart and
   recovery on two installations.
5. Next scoped issue: private drawing upload/finalization/history API,
   executable contract, idempotency, tombstones, cleanup, and outbox. Prove
   cross-couple denial and interrupted upload/retry behavior before client
   integration.
6. Next scoped issue: drawing canvas, preview, durable send queue, received
   detail, and chronological gallery. Prove two-device drawing exchange and
   restart/offline recovery before adding push.
7. Next scoped issue: device registration/preferences, FCM worker and Android
   permission/deep links. Prove permission denial is harmless and
   duplicate/stale events cannot resurrect content.
8. Next scoped issue: privacy-aware Glance memory/quick-action widgets with
   supported WorkManager updates and shared deep links. Test stale/unauthorized
   previews and launcher behavior on real Android.

Items without issue numbers are sequencing proposals tracked by M0, not active
implementation tasks. Search and create their focused issues before coding;
split further if a change cannot be independently tested/reviewed. Reuse
existing labels and avoid a speculative catalogue of issues.

M1 is complete only when two separate users can pair, send a drawing with a
retry-safe submission, receive it, open it from a notification and memory
widget, and see it in their private chronological gallery. Record device/API
versions, test commands and results, and cross-couple/deleted-content denial
evidence. Notifications are optional; the same exchange must work with
permission declined.

## M2–M4: complete the MVP

- **M2 — Content controls:** photos via Photo Picker/camera, shared save/unsave,
  deletion UI, unpair/account-deletion completion, durable cleanup, cache/widget
  invalidation, recovery/conflict paths. Backend read revocation must already
  exist when content first becomes accessible in M1.
- **M3 — Asynchronous game:** Draw & Guess contract, rules, persisted lifecycle,
  role-specific views, retries, and turn notifications; restart/resume evidence.
- **M4 — Live game and validation:** tic-tac-toe contract, authoritative moves,
  WebSocket reconnection/snapshots, version conflicts, complete privacy and
  release checks. No larger catalogue before both games are reliable.

## CI as implementation grows

M0 runs `npm ci --ignore-scripts` and `npm run check` on PRs and `main`;
formatter checks supported Markdown/JSON/YAML and Markdown lint checks document
structure. No runtime/backend/Android checks are claimed yet, and external links
are not network-validated. Keep workflow permissions read-only and production
secrets out of ordinary checks.

Add with the Go scaffold: formatting, `go vet`, race-enabled tests, build,
module integrity, and PostgreSQL migration smoke checks. Add real constraint,
concurrency, authorization, and outbox tests with their modules. Pin
Go/dependencies and the database version; keep lock/module checksums
reproducible.

Add with the Android scaffold: wrapper/dependency validation, compilation,
Android lint, relevant unit tests, and default/custom/invalid branding builds.
Add emulator UI tests where they verify behavior that unit tests cannot. Do not
require release signing or a production Firebase project for pull requests.

## MVP acceptance evidence ledger

Every row is currently **unverified**. Replace status only with links to tests,
builds, and relevant device evidence; a document or generated file is not
feature evidence.

| Spec criterion                          | Planned evidence / milestone                                                | Status     |
| --------------------------------------- | --------------------------------------------------------------------------- | ---------- |
| 1. Accounts and secure pairing          | Guest identity/optional recovery and two-device consent tests; M1           | Unverified |
| 2. Invitation validity and non-reuse    | Raw SQL + API abuse/concurrency tests; M1                                   | Unverified |
| 3. Photos and drawings                  | Two-device exchange/format validation; M1 drawings, M2 photos               | Unverified |
| 4. Retry without duplicates             | Lost-response, restart, concurrent same-key tests; M1                       | Unverified |
| 5. Private chronological gallery        | API isolation/pagination and Android UI tests; M1                           | Unverified |
| 6. Save/unsave/delete                   | Shared state and tombstone/media denial tests; M2                           | Unverified |
| 7. Widget display/open                  | Received-only, visibility, launcher/deep-link and stale-state tests; M1     | Unverified |
| 8. Push delivery when allowed           | FCM device evidence and outbox retry tests; M1                              | Unverified |
| 9. Notification denial                  | Exchange/gallery work with permission denied; M1                            | Unverified |
| 10. Async game persistence              | Restart/resume and role secrecy tests; M3                                   | Unverified |
| 11. Live game recovery                  | Two-device moves, interruption/reconnect, snapshot tests; M4                | Unverified |
| 12. Invalid/duplicate/out-of-turn moves | Server rules, idempotency, concurrent version tests; M3/M4                  | Unverified |
| 13. Unauthorized access rejection       | Cross-couple ID substitution over REST/media/WebSocket; M1–M4               | Unverified |
| 14. Account deletion/unpairing          | Revocation, durable cleanup, offline reconciliation, restore tests; M2      | Unverified |
| 15. Tests/lint/builds                   | Real backend and Android CI plus relevant device checks; M1–M4              | Unverified |
| 16. Build-time branding                 | Default/custom/invalid asset APK/resource checks; M1                        | Unverified |
| 17. Accurate README                     | Recheck against working setup and feature behavior each PR; final MVP audit | Unverified |
