# Privacy and content lifecycle

Usual uses Firebase Authentication, Firestore and a private Storage bucket in
`getusualapp`. Guest identities ask for no email/password. Optional Google
linking preserves the UID and provides recovery; it does not copy media to
Google Drive. Reinstalling/clearing an unlinked guest installation may lose
access. Legacy email sign-in is retained. No passwords or Google credentials are
stored by application code. Anonymous-account automatic cleanup must remain
disabled. Transport/provider encryption is not end-to-end encryption.

## Access and lifecycle

- A user can read/write only their own bounded profile. Their preferences and
  pairing state are exact owner reads with trusted writes. Profiles are not a
  public directory. Partner names are bounded snapshots in their couple.
- Invitations require a valid, unexpired, unused code plus explicit creator
  confirmation of the claimant. An active couple has two distinct members;
  transactions serialize claims/confirmation and conflicting membership.
- Only current members of that active couple may read its nondeleted content,
  matches or moves. Secret prompts, devices, delivery events, upload permits and
  authoritative writes are denied to Firestore clients. Trusted operations
  repeat authorization because Admin SDKs bypass Security Rules.
- Saved status is shared on the original item. Either partner can save/unsave.
  Either can delete an item for both: a tombstone denies reads immediately and
  durable cleanup removes its media/document. Exchanged content has no automatic
  expiry. Saving never protects an item from deletion.
- Unpairing closes the couple and clears both memberships before cleanup. Old
  content, games and media become inaccessible; all old-couple objects,
  subcollections and delivery events are scheduled for deletion. There is no
  archive or transfer into a new couple. Each identity and its avatar remain.
- Account deletion first tombstones the identity, closes any couple and revokes
  membership. It revokes Auth sessions/deletes Auth, then retries profile,
  avatar, staging, preferences, operation receipts and device deletion. The
  partner's identity remains. A denied account tombstone lasts 30 days; old ID
  tokens expire sooner. Anonymous spent invitation digests remain to prevent
  code reuse without retaining participant names/UIDs.
- Content/game submissions use actor-bound stable request IDs and transactional
  receipts. Retries cannot retarget a draft to a new identity or couple. Games
  are validated by the server, with version conflicts and participant/turn
  checks. See [game rules](games.md).

Destructive actions explain the shared effect and require confirmation. Access
revocation is immediate at the authoritative commit; physical deletion is
asynchronous. Maintenance runs every five minutes with bounded batches and retry
backoff. A provider outage or backlog can delay physical cleanup.

## Media and Android caches

Processed private objects have no client Storage access or permanent download
URL. Authorized API responses recheck access before and after loading bytes.
Staging permits last 15 minutes, bind owner/checksum/size/kind/couple and cap
outstanding uploads at 20. Actual image bytes, dimensions and checksums are
validated; JPEG/PNG are re-encoded to strip metadata, with a 4 MiB cap. The
bounded 48-hour orphan sweep never expires retained exchange history.

The Android Photo Picker/camera supplies only a selected image. A preview
precedes submission. Private received images and Firestore data use memory
caches. The atomic pending-send outbox uses private files bound to the original
UID/couple. Android backups/device transfer are disabled. Identity changes and
observed membership loss clear private UI, drafts, work, images and
notifications. Deletion removes an observed item from the gallery; old in-memory
bytes are cleared as the gallery changes. No media is exported automatically.

## Notifications and widgets

Preferences default off and belong to each user. Notification permission denial
does not block any core feature. Delivery events are transactional; FCM carries
only recipient/event IDs. The server and receiving worker recheck current
membership, preferences and resource/version. Invalid devices are pruned; events
expire after seven days and idle device registrations after 30 days. Before
sign-out the app attempts unregistering, then deletes/regenerates its messaging
installation for identity changes. Offline removal may need retry, but recipient
checks prevent another identity from displaying that message.

Opt-in memory widgets show only eligible received photos/drawings after a fresh
server check. They do not save private image files; refresh failure is neutral.
Quick actions open the full app. Glance/WorkManager refresh on supported
callbacks, periodic work, received signals and observed changes. Old deep links
recheck current pairing and server authorization. Details and protocol:
[notifications and widgets](notifications-widgets.md).

A server cannot recall downloaded bytes, screenshots, or pixels retained by an
offline launcher. Android/FCM do not guarantee immediate execution. The preview
opt-in explains this exposure. Revocation prevents new authorized reads; do not
promise instant remote erasure of pixels already shown on another device.

## Provider retention and logs

No app-managed database backups, exports, object versions, or restore job are
provisioned by this repository. Provider soft-delete/backup retention must be
verified in the project before external testing; successful object deletion
requests are not proof of deletion from provider recovery copies. Any future
restore must reapply deletion/revocation state before serving traffic. A backup
restore has not been tested and is not an accepted MVP criterion.

Application logs contain only fixed error codes/aggregate diagnostics, never
passwords, tokens/FIDs, invitation codes, emails, media, object URLs, prompts,
guesses or request/response bodies. Restrict access to provider operational
logs; no custom retention override is deployed. Release R8 removes Android Log
calls, including SDK calls; raw debug logcat can contain SDK identifiers and
stays private. No Analytics or Crashlytics SDK is installed.

The [rules audit](security-audit.json) and negative emulator tests cover
ownership, field/type/size tampering, unknown writes, private queries,
cross-couple IDs, revoked identities, stale actions, prompt secrecy and upload
boundaries. Tests and remaining device/provider limitations are recorded in the
roadmap and PRs.
