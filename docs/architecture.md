# Architecture: Firebase backend

Status: Firebase Authentication and Cloud Firestore selected by the owner on
2026-10-25. This explicitly supersedes the earlier Go/PostgreSQL decision for
the backend. Guest-first access with optional Google linking was restored by the
owner after device feedback (#7). No Go backend was implemented; issue #2 is
retired.

## Current stack

- Android: Kotlin, Jetpack Compose, lifecycle ViewModel/StateFlow, and Firebase
  Android SDKs managed by the Firebase BoM.
- Identity: anonymous Firebase Authentication for guest-first access, optional
  Google credential linking/recovery, and SDK-managed sessions. Existing
  email/password accounts retain an optional sign-in/reset path. No email
  verification is required for guest use or future pairing.
- Database: Cloud Firestore Standard edition, `(default)`, São Paulo
  (`southamerica-east1`) in the existing `getusualapp` project.
- Authorization: version-controlled Firestore Security Rules, strict document
  schemas, and emulator tests. App code is never the sole access-control layer.
- Build: pinned Gradle wrapper with distribution SHA-256, Android Gradle plugin,
  version catalog, separate live/emulator flavors, and least-privilege CI.

Authentication, private profiles, trusted pairing, unpairing and identity
deletion are implemented. [docs/firebase.md](firebase.md) defines their exact
collection contract, setup, environments, and verification. There is no custom
API server or PostgreSQL.

## Why the backend changed

The earlier comparison considered Kotlin/Spring Boot, Java/Spring Boot, and Go:
Spring offered integrated transaction/security tooling; Go offered an explicit
HTTP/SQL model and simple deployment. The owner subsequently selected Firebase
to use managed identity and a document database directly from Android. The
Firebase choice now governs implementation; we are not maintaining two backend
stacks.

Firebase reduces service provisioning and supplies Android SDK state listeners
and session management. The trade-off is provider dependence, usage-based
operations, and explicit Firestore rules/data-model design in place of
relational constraints. There are no foreign keys, SQL migrations, or database
triggers to silently rely on. Privileged Firebase Admin SDK operations bypass
Security Rules and must enforce authorization themselves.

## Boundaries as features grow

Use cohesive Android feature/data packages as features arrive, without a generic
repository framework. This small foundation has one repository interface to test
UI state against a fake, and a Firebase implementation used by the application.

| Logical module   | Planned Firebase responsibility                                   |
| ---------------- | ----------------------------------------------------------------- |
| Identity/pairing | Auth identities, private profiles, invitation/couple transactions |
| Content/memories | Firestore metadata and one shared saved status per item           |
| Media            | Private object storage; no public download-token URLs             |
| Games            | Persisted matches/moves and server-validated state transitions    |
| Notifications    | FCM device registration and durable event delivery                |

Pairing and identity deletion use the TypeScript callable Functions codebase in
this Firebase project. Game moves and media cleanup extend those same trusted
modules. See [the implemented operation contract](mvp-contract.md). Each needs a
contract, idempotency, transaction limits, authorization, and cost review before
deployment. Do not give clients write access to membership or authoritative game
state to avoid writing those operations.

Firestore listeners will provide live state synchronization instead of a bespoke
Go WebSocket service. Reconnection still requires an authorized current
snapshot; notifications remain refresh signals, not authoritative state.
Photo/drawing bytes do not belong in Firestore documents. Storage and FCM follow
in the next milestones.

The approved shared-content deletion lifecycle remains unchanged. See
[domain](domain.md), [privacy](privacy.md), and [roadmap](roadmap.md). Rules and
explicit schema-version changes are reviewed and emulator-tested in PRs; future
backfills must be retryable and must not grant interim access.
