# API design baseline

Status: **draft conventions, no implemented endpoints**. Before a feature PR
adds handlers or Android calls, commit its executable OpenAPI contract with
complete schemas, validation limits, examples, state transitions, and response
codes. The WebSocket game protocol needs its own versioned message schemas. This
document records the initial decisions to carry into those contracts.

## Shared conventions

- REST prefix `/v1`, JSON requests/responses, UUID identifiers, RFC 3339 UTC
  times. Successful resource responses use `{ "data": ... }`; lists additionally
  use `page: { nextCursor }`. `204` responses have no body. Media is an
  authenticated binary response rather than a JSON wrapper.
- Errors use `application/problem+json` with stable `type`, `title`, `status`,
  machine-readable `code`, and `requestId`. Validation errors identify field
  names and safe codes without echoing sensitive values. No stack traces or
  private data.
- Missing/invalid identity returns `401`; an authenticated account forbidden
  from an operation returns `403`. Inaccessible resource identifiers return
  `404`, just like nonexistent ones. `409` covers version/state/idempotency
  conflicts; `422` invalid inputs; `413` oversize payloads; `429` rate limiting
  with `Retry-After`.
- Every protected request carries a Firebase ID token in
  `Authorization: Bearer`. Verify issuer/project, signature, expiry, revocation,
  and local user status. Couple membership comes from the DB, not custom claims
  or a submitted couple ID.
- Bound requests, uploads, pagination, and timeouts explicitly. Paginate content
  using opaque cursors over `(created_at, id)`, scoped to the authorized query;
  default page size 30, maximum 100 is the initial proposal.

## Initial resource surface

These routes are proposed names, not working commands. Identity/pairing is
scoped in [issue #4](https://github.com/atluixx/usual/issues/4); content follows
separately.

| Proposed endpoint                         | Purpose and important rules                                                         |
| ----------------------------------------- | ----------------------------------------------------------------------------------- |
| `GET/PUT /v1/me`                          | Retrieve/provision or edit the caller's profile; never accept an external user ID   |
| `POST /v1/invitations`                    | Generate an expiring code for the caller; no couple yet                             |
| `POST /v1/invitations/claims`             | Submit a code and explicit consent; return only a limited pairing request           |
| `POST /v1/invitations/{id}/confirm`       | Creator confirms the claimant; atomically create the two-member couple              |
| `DELETE /v1/invitations/{id}`             | Creator revokes a pending/claimed invitation                                        |
| `GET /v1/couple`                          | Return the caller's current active couple or an explicit unpaired state             |
| `DELETE /v1/couple`                       | Idempotent, confirmed unpair request; use a couple-version precondition             |
| `POST /v1/uploads`                        | Receive a bounded image stream into a private pending asset; partner cannot read it |
| `POST /v1/content`                        | Finalize a ready, caller-owned asset into the current couple's history              |
| `GET /v1/content`                         | Paginated history; `saved=true` filters the same rows                               |
| `GET /v1/content/{id}`                    | Return current metadata after membership/deletion checks                            |
| `GET /v1/content/{id}/media`              | Reauthorize and stream private bytes; never redirect to public storage              |
| `PUT /v1/content/{id}/saved`              | Set `{ "saved": true/false }` on the existing row with a version precondition       |
| `DELETE /v1/content/{id}`                 | Tombstone for both partners and enqueue deletion in one transaction                 |
| `PUT/DELETE /v1/devices/{installationId}` | Register/revoke only the caller's device token                                      |
| `PUT /v1/me/preferences`                  | Caller controls notification categories and widget preview visibility               |
| `DELETE /v1/me`                           | Confirm account deletion; deny local access first, then retry external cleanup      |

Pairing code length, expiry, attempt limits, claim replacement, consent, and
identity recovery must be finalized in issue #4. A reasonable starting proposal
is 12 random base32 characters, 15-minute expiry, single claimant, per-identity
and network attempt limits, and creator confirmation after the claimant accepts.
Never log codes or put them in analytics/notification text. Revoke and
regenerate a code if the creator does not recognize the claimant. A code alone
never grants content access.

## Transactions, retries, and delivery

Require `Idempotency-Key` for retryable invitation creation/claims/confirmation,
uploads/finalization, and game actions. Scope records to actor and operation;
store a digest of the validated request and the original resource/result. Same
key and same payload returns the same resource; same key with changed payload
returns `409`. A uniqueness constraint resolves concurrent retries. Reauthorize
before replaying so an old result cannot expose deleted content or a former
couple.

For the MVP, retain successful action keys for the resource/tombstone lifecycle;
do not silently accept a previously used key after pruning. Define any future
bounded retention plus deduplication rule in the endpoint contract. Failed,
uncommitted validation does not create a successful action record. After an
interrupted upload, the same key can resume/retry the same private pending
asset; new bytes with that key conflict. Define checksum and interrupted-stream
behavior in the upload contract before implementation.

Submitting content atomically writes its row, idempotency result, and outbox
event after media validation. Persist before attempting FCM. Worker leases,
exponential backoff/jitter, bounded attempts, invalid-token revocation, and
observable failed events provide at-least-once delivery attempts. FCM delivery
is not guaranteed and must never define gallery state. Recheck membership,
preferences, and deletion before each send. `delivered` at FCM means provider
acceptance, not device receipt; use an authenticated device acknowledgement if
the UI claims receipt/opening.

Android persists pending sends and idempotency keys across restarts. UI
distinguishes local draft, queued, uploading, submitted, and failed/retry
states. A response lost after commit resolves through the same key.
Gallery/foreground sync recovers missed pushes, and an unpaired pending send
must not target a later couple.

## Live and asynchronous games

Define game invitation/acceptance/cancellation/move/snapshot REST contracts
before their clients. Mutations include idempotency keys and expected match
versions. `If-Match`/ETag semantics or explicit `expectedVersion` must be
consistent in the published contract. The backend validates participant,
membership, turn, legal action, and match lifecycle under a DB lock/version
check. Return the authoritative snapshot and new version after commit; stale
requests return `409` and refresh.

Use authenticated WebSocket connections/subscriptions scoped to one authorized
match. Never send tokens in URLs. Revalidate on subscription, action, token
expiry, and couple closure; close revoked sessions. Persist moves first, then
broadcast resource IDs/versions or authorized snapshots. After reconnect or a
version gap, GET the current authorized snapshot; do not infer success from a
socket echo.

Tic-tac-toe may submit moves by REST and receive updates by WebSocket, keeping
one authoritative mutation path. Draw & Guess persists prompts/drawings/guesses
with role-specific projections and turn events. Duplicate, invalid, out-of-turn,
stale, and post-completion actions need explicit contract examples and tests.
