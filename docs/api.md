# Firebase client and operation contracts

Usual now uses Firebase Authentication and Firestore instead of the earlier Go
REST and WebSocket proposal. No `/v1` REST endpoints or custom socket protocol
exist. Only the Auth SDK flows and exact-document profile contract in
[docs/firebase.md](firebase.md) are implemented in this foundation.

## Current operations

- Create an email/password account through Firebase Auth. Do not retry account
  creation blindly after an ambiguous failure; offer sign-in/reset instead.
- Sign in, restore an existing SDK session, sign out, send verification mail,
  refresh verification state/token, and request a password-reset email.
- Read/observe `users/{currentUid}`. Create/update a bounded display name
  through a Firestore transaction, preserving the creation timestamp. No
  collection list.
- Show loading, missing-profile, offline/cache, permission/error, retry, and
  submitted states separately. Profile failure does not undo or duplicate the
  Auth identity; a user can retry profile setup independently.

The UI does not display raw SDK exception messages or log credentials. Passwords
stay in transient form/action memory, never saved instance state. Email is shown
only to its own signed-in account and is not copied to Firestore. Writes report
success after server acknowledgement; cached snapshots are labelled explicitly.

## Future trusted operations

Before pairing, media, or games, define callable-function request/result
schemas, validation bounds, state transitions, and stable safe error codes in
their issues. Verify authentication and current membership in trusted code;
Admin SDK calls bypass rules. Decline malformed or inaccessible identifiers
without leaking data.

Pairing claims and creator confirmation, content submission, and game actions
need actor/operation-scoped idempotency keys and request digests. Same
key/payload must return the same authorized resource/result; changed payload
conflicts. Persist deduplication with the state transition, and reauthorize
replay so old keys cannot expose a closed couple or deleted content. Define
retention explicitly.

Transactions must read authoritative invitation/membership/match versions before
writing. Client-provided sender IDs, couple IDs, board state, or result are not
trusted. Retry Firestore transaction conflicts safely without repeating network
side effects inside the transaction callback.

## Delivery and real-time state

Use Firestore snapshot listeners for authorized live state. On reconnect,
recover the current versioned snapshot and discard stale local assumptions.
Listener updates do not prove another device displayed the content.

FCM is a future delivery signal. Persist content/game state and durable delivery
work before sending. Deduplicate events, retry failed sends with bounded
backoff, revoke invalid device tokens, and recheck membership/preferences before
delivery. Provider acceptance is not device receipt. Missed pushes must recover
through ordinary sync; notification permission denial must not block core
features.

Future galleries need authorized, bounded, ordered queries/cursors. Future
private media access must revalidate membership/deletion and avoid permanent
public bearer download URLs. No media or game API is exposed in this milestone.
