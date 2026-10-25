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
