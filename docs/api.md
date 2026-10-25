# Firebase client and operation contracts

Usual now uses Firebase Authentication and Firestore instead of the earlier Go
REST and WebSocket proposal. No `/v1` REST endpoints or custom socket protocol
exist. The Auth SDK flows, trusted
[pairing/lifecycle operations](mvp-contract.md), and exact-document profile
contract in [docs/firebase.md](firebase.md) are implemented.

## Current operations

- Continue without an account: reuse the SDK session or call anonymous sign-in.
  Retry only after failure; duplicate taps share one in-flight operation. The
  invisible Firebase identity still authenticates every protected request.
- Restore an existing SDK session without asking for credentials.
- Optionally obtain a Google ID token through Android Credential Manager on an
  explicit tap. Link it with `currentUser.linkWithCredential`, preserving the
  UID and document paths. A collision/cancellation/failure leaves the current
  identity and profile untouched; never fall back to sign-in or merge accounts.
- Google sign-in from the welcome screen is a separate deliberate action for a
  linked account on another device (or a new Google-backed identity). It never
  runs while another identity is active. Sign-out clears Firebase and Credential
  Manager state; unlinked guests have no destructive sign-out UI.
- Existing email/password users may sign in/reset their password. Their
  identities and documents remain intact. Email registration is no longer in
  onboarding, and verification is no longer a prerequisite for pairing.
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

FCM is a delivery signal. Persist content/game state and durable delivery work
before sending. Deduplicate events, retry failed sends with bounded backoff,
revoke invalid device tokens, and recheck membership/preferences before
delivery. Provider acceptance is not device receipt. Missed pushes must recover
through ordinary sync; notification permission denial must not block core
features.

Future galleries need authorized, bounded, ordered queries/cursors. Future
private media access must revalidate membership/deletion and avoid permanent
public bearer download URLs. No media or game API is exposed in this milestone.

Game operations, versions, roles and invitation/cancellation rules are defined
in [games.md](games.md). Device registration (`fid`), preferences, notification
resolution and the fresh widget snapshot contract are defined in
[notifications-widgets.md](notifications-widgets.md). These all use the same
versioned, actor-bound callable envelope and current-membership authorization.
