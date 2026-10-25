# MVP operations and delivery contract

The backend is a single TypeScript Firebase Functions codebase (Node 22), with
one authenticated callable API, Firestore transactions/listeners, private
Storage, and FCM. It uses the existing Standard database in São Paulo. No custom
Go server or public media URLs are introduced. Functions and Storage need a
Blaze project; deployment must report a billing prerequisite rather than
silently enable billing.

## Pairing and identity

Callable API: region southamerica-east1, name api. Request envelope fields are v
(1), op, requestId (16–80 URL-safe characters), and data (an object). Unknown
fields and inputs over 32 KiB are rejected. Mutations persist an actor-scoped
request digest/result with the state transition; retries reuse requestId.
Changed payloads conflict. Authentication checks revoked tokens and
disabled/deleted users; anonymous identities are valid. All calls have a
per-user 60/minute throttle. Errors use Firebase callable codes and safe
messages.

- pair.create: data.code is a client-generated 96-bit random code (24 hex
  characters, displayed in groups). Only its SHA-256 digest is stored remotely.
  The device retains the code locally while showing the invitation. The result
  returns invitationId and expiresAt (epoch milliseconds). Invites expire in 20
  minutes. The owner cancels an earlier invite before making another.
- pair.claim: data.code; the other person requests pairing. No couple access is
  granted. Each person can have only one outstanding invitation or active
  couple.
- pair.confirm: invitationId and confirm=true; only its creator may confirm.
  Both deterministic pairing documents and the invitation are read inside the
  transaction. It creates exactly two distinct members and both memberships.
- pair.cancel: invitationId; either participant revokes an unfinished invite.
- pair.end: coupleId and confirm=true; either member closes the couple, clears
  both memberships and queues physical cleanup. Old data is immediately denied.
- identity.delete: confirm=true; revokes shared access first, leaves a durable
  cleanup job, revokes Auth refresh tokens, and deletes the Auth user. Partial
  failures retry. External Auth deletion invokes the same cleanup path.

The private owner-readable pairing/uid document carries status, coupleId and
invitationId. Invitation documents can be read only by creator/claimant, never
listed. Active couple documents expose the two bounded display names, not email
addresses. All membership/invitation/couple writes require trusted operations.
The client observes exact documents and clears old listeners/private state when
identity or membership changes. Code possession alone cannot form a couple: the
claimant accepts and the creator explicitly confirms the requested name.

Unpairing and account deletion follow the already-approved destructive
lifecycle. Closed couples authorize no content or games. Cleanup is
bounded/retryable, not an unbounded database transaction. Account tombstones
block stale tokens for 30 days, then are removed. Already downloaded pixels
cannot be recalled from offline devices; caches/widgets must clear upon
observation/revalidation.

Subsequent content, games, and delivery milestones extend this contract before
their clients. Their issue acceptance criteria remain unverified until backed by
executed tests and recorded device/deployment evidence.

Expired invitation cleanup releases both pending participants. Spent invitation
code digests remain as anonymous anti-reuse tombstones; cleanup removes names,
member IDs and couple IDs after unpair/account deletion. There is no code
lookup/list API. Idempotency records live with the owning identity so very old
retries cannot duplicate retained content; account cleanup removes them.

## Content and private media

A device uploads at most 4 MiB of JPEG/PNG to pending/uid/uploadId using its
Firebase identity. The upload includes a SHA-256 metadata field. Only that
identity can access this temporary object; all private/ objects are denied to
client SDKs. The trusted finalizer independently checks size, signature, decoded
format/pixel limits and actual SHA-256, re-encodes to remove metadata, then
persists one authoritative item plus an outbox event in a transaction. Storage
writes happen outside retried transaction callbacks; orphan objects are private
and cleaned later. SDK getDownloadURL and public/signed bearer media URLs are
not used. Authenticated reads revalidate membership and tombstones after loading
bytes, before returning bounded base64 media to the client.

- content.send: coupleId, kind (PHOTO, DRAWING or MESSAGE), caption (0–280;
  nonblank for MESSAGE), and uploadId/sha256 for media. Stable requestId
  survives upload/network/process retries. The result gives contentId and
  coupleId.
- content.media: coupleId, contentId and optional thumbnail boolean; returns
  mimeType, sha256 and base64 bytes. Deleted/revoked content is denied.
- content.save: coupleId, contentId, saved boolean. Both may change one shared
  saved status; no media copy.
- content.react: coupleId, contentId, reaction (an allowed emoji or empty to
  remove the caller's reaction). Each member controls their own reaction only.
- content.delete: coupleId, contentId. Either member tombstones the item for
  both and queues physical object/document cleanup. No undo or expiration.
- content.ack: coupleId, ids (at most 20), opened boolean. Only the recipient's
  eligible items receive received/opened timestamps. Push acceptance is not
  delivery. The gallery lists at most 20 nondeleted items per page using
  deleted=false, createdAt descending, and an optional immutable cursor. Saved
  memory queries additionally constrain saved=true.
- profile.avatar: uploadId/sha256. Updates the caller's private avatar; partner
  access requires current active membership. Previous media is queued for
  cleanup. profile.avatarMedia: ownerUid and optional thumbnail boolean.

Android copies picker/camera/drawing output into private app storage for
preview. Sending uses an atomic persisted outbox and unique WorkManager work;
the record freezes owner UID and couple ID. Retries never retarget a new
partner. Sign-out, unpair and deletion clear local private state and pending
work. Only upload state persists on disk; received media caches stay in memory.
Notification and widget visibility defaults are defined in their later delivery
milestone.

The client includes actorUid as an identity-change guard; the server compares it
with the verified caller, never uses it to choose the caller. Legacy v1 requests
may omit it. Outbox RPCs always include it and freeze the original couple ID.
Before upload, media.authorize creates/renews a 15-minute,
checksum/size/kind-bound permit. Each identity has at most 20 outstanding
permits. Storage Rules consult that permit and account tombstone; clients cannot
mint permits. The worker first checks operation.status, so a completed send can
recover without re-uploading a consumed ticket. media.discard revokes owner
staging and queues physical cleanup.

Unpair immediately denies shared reads/finalization. Owner-only in-flight
staging can remain until its short permit expires or cleanup runs; it cannot be
published into a closed or different couple. Cleanup revokes those permits and
queues raw object deletion. A bounded paginated orphan sweep removes abandoned
objects after 48 hours. This is staging cleanup, not expiration of exchange
history.
