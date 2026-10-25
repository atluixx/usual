# Firebase domain model

The owner replaced PostgreSQL with Firestore before product implementation. This
model preserves the product invariants while making enforcement explicit.
Profiles, pairing state, invitations, couples, account tombstones, operation
receipts and cleanup jobs are implemented. Content, private media and avatars
are implemented, as are games, notification delivery and widget preferences.

## Implemented private profile

Firebase Auth owns anonymous identity and optional linked Google credentials.
Existing email/password credentials also remain managed by Auth. The Firestore
profile key is the Firebase UID, not a client-generated user ID. Fields are
`uid`, `displayName`, `createdAt`, `updatedAt`, and `schemaVersion: 1`; see the
exact [contract](firebase.md). Server timestamps prevent forged ordering. Rules
enforce owner-only exact-document reads and validated creates/updates, immutable
UID and creation time, an 80-character name bound, and no extra fields. Clients
cannot list or delete profiles or add membership/admin fields.

## Collections

| Entity/path                             | Purpose and enforcement                                                                                    |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `couples/{coupleId}`                    | Exactly two distinct member UIDs only when active; closed couples authorize no reads                       |
| `pairing/{uid}`                         | One authoritative active couple per user, written only with the couple transaction                         |
| `invitations/{invitationId}`            | Creator, code digest, claimant, validity/consent/status; never a public code directory                     |
| `couples/{coupleId}/content/{id}`       | Sender, type, private media reference, shared saved status, version, server timestamps, deletion tombstone |
| `uploads/{uid}/items/{uploadId}`        | Private pending-media authorization/validation state and unfinished-upload cleanup                         |
| `devices/{sha256(fid)}`                 | Owner-bound messaging installations, expiry; all client reads/writes denied                                |
| `couples/{coupleId}/matches/{matchId}`  | Game participants, lifecycle, authoritative state/version; server-only mutation                            |
| `.../matches/{matchId}/moves/{moveId}`  | Actor, action, sequence/version, idempotency key; immutable accepted moves                                 |
| `operations/{uid}/requests/{requestId}` | Server-owned deduplication records scoped to user, operation, and request digest                           |
| `deliveryEvents/{eventId}`              | Durable FCM work, recipient/resource/version, lease and retry state                                        |
| `cleanupJobs/{jobId}`                   | Retryable storage/identity cleanup without restoring client access                                         |

Use unguessable document IDs where appropriate and Firestore timestamps. IDs
alone are not authorization. Do not put private media, passwords, email
addresses, or game prompts in a broadly readable document. Firestore reads
return whole documents; role-secret Draw & Guess state needs separate
documents/projections.

## Pairing invariants

An invitation is not a one-member couple. Claimant and creator both consent
before an active couple is created. The trusted pairing operation must
transactionally read invitation validity and both deterministic membership
documents, reject self-pairing/reuse/already-paired users, then write the
accepted invitation, exactly two members, and both memberships. Concurrent
acceptances must conflict/retry, not produce a third member or two active
couples.

Firestore has no SQL foreign keys, uniqueness indexes, or deferred constraint
triggers. Deny direct client writes to these authoritative documents, validate
the invariants in trusted code, and test retries/concurrency against the
emulator. Admin SDK calls bypass rules; functions must check the authenticated
caller (including anonymous guests), current membership, and lifecycle for every
operation. Pairing must not require email/password, email verification, or
Google linking. Linking preserves UID, so memberships remain attached.

## Content, memories, and games

Saved state belongs on the content item (`savedAt`, `savedBy`) and is shared.
Saving/unsaving changes metadata without copying media. History contains every
non-deleted exchanged item; the saved gallery filters it. Either partner may
delete an item for both. There is no automatic expiry of exchanged content.

A content submission must atomically persist authoritative metadata, its
idempotency result, and delivery work after private media validation. Membership
checks occur within the operation, not from a submitted sender/couple ID.
Cleanup uses tombstones and durable jobs; security predicates deny reads before
physical object deletion completes. Define bounded transaction/write behavior
before implementing bulk unpair deletion.

Games use server-validated actions and transactionally incremented versions. A
client cannot submit an authoritative board/result. Draw & Guess persists the
private prompt, drawing, and guess lifecycle; tic-tac-toe persists legal turns
and consistent results. Invitation, completion, cancellation, restart,
duplicate, out-of-turn, and concurrent-action contracts are required before
their clients.

Indexes are added only for implemented queries. The current profile uses an
exact UID lookup and needs no composite index. Future chronological galleries
need scoped cursors and explicit composite indexes. Schema changes/backfills
must remain compatible with old clients and must never temporarily broaden
rules.

Game rules/lifecycles and private `couples/{coupleId}/secrets/{matchId}` prompts
are specified in [games](games.md). `preferences/{uid}` contains three
owner-readable/server-written booleans, default false. Devices and delivery
events are server-only. See [notifications/widgets](notifications-widgets.md)
for leases, registration expiry, event retention and snapshot queries.
