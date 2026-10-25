# Incremental delivery roadmap

The owner selected Firebase Auth + Firestore, then restored guest-first access
with optional Google linking (#7). Go/PostgreSQL and mandatory email/password
onboarding are superseded. Permanent Android ID: `com.okoda.usual`; project:
`getusualapp`; database: Standard `(default)` in São Paulo. Shared saved status,
deletion for both partners, and old-couple deletion on unpair/account deletion
remain approved.

## Current work

- [#1 — Repository foundation](https://github.com/atluixx/usual/issues/1):
  README, contributor workflow, templates, architecture, and least-privilege CI
  in PR #5.
- [#6 — Firebase Android foundation](https://github.com/atluixx/usual/issues/6):
  registered Android app, guest/Google and legacy sign-in flows, private
  Firestore profile, prototype Security Rules, emulators, Android build/tests,
  and updated documents. PR #5 is expanded around this final implementation; no
  merge is automatic.
- [#7 — Guest-first correction](https://github.com/atluixx/usual/issues/7): no
  email/password gate, optional Google linking with preserved UID, recovery
  limits, and updated pairing prerequisites. Included in foundation PR #5.
- [#2 — Go foundation](https://github.com/atluixx/usual/issues/2): retired, not
  implemented, because the owner chose Firebase.

The implemented profile schema and setup are in [firebase.md](firebase.md).
Auth/Firestore emulator tests prove identity/profile operations and access
denial; Android unit tests target UI state and retry/session behavior. Record
actual build, CI, and device results in the PR rather than equating code
presence with proof.

## First end-to-end product milestone

The goal remains: two people pair, send/receive a drawing, open it from a
notification and memory widget, and find it in a private chronological gallery.
Deliver in small issue-linked PRs:

1. Finish the Firebase foundation and guest-first correction (#6, #7).
2. [#4 — Trusted Firebase pairing](https://github.com/atluixx/usual/issues/4):
   contracts, anonymous guests, mutual consent, transactional membership,
   idempotency, Security Rules, and concurrency/abuse tests.
3. Scope Android pairing screens after that contract, with loading, error,
   retry, expiry/revocation, and two-installation/restart evidence.
4. Scope private drawing upload/finalization/history, storage authorization,
   idempotency, tombstones, durable cleanup/delivery events, and cross-couple
   tests.
5. Scope drawing canvas/preview, durable sends, received detail, and private
   gallery against those contracts. Prove exchange/retry/restart before push
   integration.
6. Scope device registration/preferences, FCM delivery/retries, permission
   denial, deep links, and duplicate/stale-event behavior.
7. Scope Glance memory/quick-action widgets with supported WorkManager updates,
   received-only eligibility, privacy controls, deep links, and stale/offline
   tests.

Unnumbered tasks above are sequencing proposals. Search/create focused issues
before implementing them; split further when necessary. No large speculative
catalogue of tasks or games. [#3](https://github.com/atluixx/usual/issues/3)
retains build-time branding as separate work; a default icon is not that
feature.

## Later MVP work

- Photos through Android Photo Picker/camera, shared save/unsave/delete UI, and
  complete account deletion/unpair cleanup and cache/widget invalidation.
- Draw & Guess with persisted private prompts/turns, asynchronous resume, and
  notifications. Define rounds, guess limits, prompts, and cancellation first.
- Tic-tac-toe with trusted legal moves, Firestore listeners, consistent results,
  version conflicts, and reconnection snapshots. No broader catalogue
  beforehand.

Backend access revocation must exist before shared content is exposed. Bulk
cleanup cannot depend on an unbounded Firestore transaction. Account deletion,
media reads, and game moves require explicit trusted authorization because Admin
SDK operations bypass Security Rules.

## Open decisions

The application ID, backend, identity provider/method, database edition, and
region are settled. Minimum Android version is API 26 in the foundation;
compile/ target SDK is 36 to match available platform tooling and compatible
dependencies.

Still needed at the relevant milestone: storage/function deployment and budget;
precise deletion/backup/log retention and restore policy; offline widget preview
exposure; end-to-end encryption expectations; final branding and license; game
rules. No real private media should be collected before its access/deletion
design is implemented and verified. Current encryption/HTTPS is not end-to-end
encryption.

## CI

Repository checks run a locked npm install plus formatting/Markdown lint.
Firebase checks use a pinned CLI and local Auth/Firestore emulators with
`demo-usual` and no production credentials. Android checks
compile/lint/unit-test the emulator flavor with a dummy client config, pinned
wrapper/checksum, and JDK 21. CI permissions are read-only; no production deploy
or signing keys are needed. External links and real-device behavior are not
automatically proved by these jobs.

Add emulator UI/device tests where they verify behavior unit tests cannot. Test
future shared resources using two distinct couples, including REST/callable and
listener ID substitution, stale operations, and retry/concurrency cases.

## MVP acceptance evidence ledger

All complete MVP criteria remain **unverified** until their full behavior has
linked evidence. Authentication/profile tests alone do not complete pairing or
any end-to-end content criterion.

| Spec criterion                          | Evidence required                                                            | Status     |
| --------------------------------------- | ---------------------------------------------------------------------------- | ---------- |
| 1. Accounts and secure pairing          | Identity foundation plus two-device verified consent and recovery            | Unverified |
| 2. Invitation validity/non-reuse        | Trusted operation + rules/concurrency abuse tests                            | Unverified |
| 3. Photos and drawings                  | Two-device exchange and format validation                                    | Unverified |
| 4. Retry without duplicates             | Lost response, restart, same-key/concurrent tests                            | Unverified |
| 5. Private chronological gallery        | Isolation, query pagination, Android UI                                      | Unverified |
| 6. Save/unsave/delete                   | Shared state, tombstone, media denial                                        | Unverified |
| 7. Widget display/open                  | Received-only visibility, launcher/deep-link/stale-state                     | Unverified |
| 8. Push when allowed                    | FCM device evidence and retry tests                                          | Unverified |
| 9. Permission denial                    | Core exchange works without notifications                                    | Unverified |
| 10. Async game persistence              | Role secrecy and restart/resume                                              | Unverified |
| 11. Live game recovery                  | Moves, interruption, listener reconnect/snapshot                             | Unverified |
| 12. Invalid/duplicate/out-of-turn moves | Trusted rules, idempotency, version conflicts                                | Unverified |
| 13. Unauthorized access rejection       | Cross-couple IDs over calls/media/listeners; profile tests are only a subset | Unverified |
| 14. Account deletion/unpairing          | Revocation, durable cleanup, offline reconciliation, restores                | Unverified |
| 15. Relevant tests/lint/builds          | Foundation checks plus complete feature/device checks                        | Unverified |
| 16. Build-time branding                 | Default/custom/invalid assets and APK resource checks                        | Unverified |
| 17. Accurate MVP README                 | Working setup and final feature audit                                        | Unverified |
