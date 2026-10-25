# Delivery roadmap and acceptance evidence

The selected backend is Firebase Authentication, Firestore Standard and private
Storage in `getusualapp`, São Paulo. Guest-first access with optional Google
linking supersedes mandatory email onboarding and the earlier Go proposal. The
live Android ID is permanently `com.okoda.usual`. Shared saves, deletion for
both, and old-couple deletion after unpair/account deletion are approved.

## Review stack

All implementation is on issue branches, never pushed to `main`. A human merges
these open dependent PRs in order, retargeting/rebasing each layer as needed:

| Issues     | Pull request                                    | Scope                                                          |
| ---------- | ----------------------------------------------- | -------------------------------------------------------------- |
| #1, #6, #7 | [#5](https://github.com/atluixx/usual/pull/5)   | Repository, Android/Firebase, guest access and optional Google |
| #4         | [#12](https://github.com/atluixx/usual/pull/12) | Mutual pairing and identity lifecycle                          |
| #9         | [#13](https://github.com/atluixx/usual/pull/13) | Photos/drawings/messages, durable sends and private gallery    |
| #10        | [#14](https://github.com/atluixx/usual/pull/14) | Persistent Draw & Guess and live tic-tac-toe                   |
| #11        | [#15](https://github.com/atluixx/usual/pull/15) | FCM, preferences, deep links and both Glance widgets           |
| #3         | Branding branch                                 | Build-time branding and integrated verification                |

Issue #2 (Go) is retired. Issue #8 tracks the legacy email action-page error;
guest pairing never requires that page or email verification. No licence or
public release is claimed. README features describe the implemented branch.

## Evidence ledger

Code presence is not acceptance evidence. The backend suite currently has 24
passing emulator tests; Auth/Firestore rules 16; Storage rules 3; actual
Firebase callable/Storage/Firestore SDK integration 3; Android state/deep-link
units 14. The SDK integration includes live match snapshots and reconnect
recovery. CI on PRs #5/#12/#13/#14/#15 passed their relevant checks. Later
changes need their own checks; see the final verification record when available.

A live-project smoke test used two deliberately created, agent-owned anonymous
identities and synthetic media. It proved mutual pairing, private drawing
upload/read, same-key retry, shared save, fresh widget snapshot, legal
tic-tac-toe completion and media denial after deletion. Deletion was requested
for both test identities afterward. This caught and corrected the Storage
service agent's missing Firestore lookup role. The FCM Eventarc trigger is
deployed. None of this alone proves notification display or launcher rendering.

| MVP criterion                           | Current evidence / remaining verification                                                                             |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 1. Accounts and secure pairing          | Auth/operation/SDK tests and live pairing; Android two-installation mutual pairing passed                             |
| 2. Invitation validity/non-reuse        | Operation concurrency/idempotency/expiry tests and denied direct client writes                                        |
| 3. Photos/drawings                      | Validation, Storage and live SDK drawing exchange; Android picker/canvas and received detail passed                   |
| 4. Retry without duplicates             | Immutable upload, lost response and same-key receipts; Android offline/restart delivered exactly once                 |
| 5. Private gallery                      | Bounded authorized queries and cross-couple rules; Android received gallery/detail/save passed                        |
| 6. Save/unsave/delete                   | Shared state, tombstone and revoked media tests; live save/delete passed                                              |
| 7. Widget display/open                  | Fresh received-only snapshot tested; Android received widget render/tap and opt-out passed                            |
| 8. Push when allowed                    | Outbox/retry/authorization tests; live FCM device display pending                                                     |
| 9. Permission denial                    | Core server operations do not depend on notification preferences; Android core flows passed with permission ungranted |
| 10. Async game persistence              | Role-secret prompt/drawing/guess transactions tested; Android round/restart/guess passed                              |
| 11. Live game recovery                  | Versioned moves and SDK listener reconnect tested; Android restart and consistent winner passed                       |
| 12. Invalid/duplicate/out-of-turn moves | Trusted tests reject illegal/stale actions, serialize conflicts and preserve one result                               |
| 13. Unauthorized access                 | Negative Auth, callable, Firestore and Storage tests, substituted couple IDs and revoked identities                   |
| 14. Unpair/account deletion             | Immediate denial and durable cleanup tests; seven-day provider soft-delete documented; no restore test                |
| 15. Tests/lint/builds                   | Prior PR CI passed; final Android/live/release checks in progress                                                     |
| 16. Branding                            | Default/custom APK labels and packaged logos, stable ID and invalid input checks passed                               |
| 17. Accurate README                     | Minimal current features/setup; links verified; detailed evidence outside README                                      |

## External-testing prerequisites

The integrated Android pass is recorded in [verification](verification.md); live
FCM and final release results are being recorded. A read-only provider check
confirmed seven-day Storage soft-delete retention, no versioning, disabled
Firestore PITR and no backup schedules. No backup restore has been tested. A
server cannot recall already downloaded screenshots or an offline launcher's
retained pixels; preview opt-in explains that limit. Google chooser/real-account
recovery needs a controlled Google account; emulator credentials do not prove
it.

CI uses pinned dependencies, read-only GitHub permissions, dummy `demo-usual`
configuration and no production secrets. Android checks compile/lint/unit-test
API 36, plus default/custom/invalid APK branding. No workflow merges, deploys
production, signs releases, or bypasses branch protection. A maintainer should
configure required PR reviews/checks on GitHub before merging.
