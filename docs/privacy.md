# Privacy and content lifecycle

This document specifies required behavior; it is not implemented yet. The owner
approved shared saved status, deletion for both partners, and deletion of couple
content on unpairing/account deletion on 2026-10-25. The UI must explain and
confirm destructive actions before submission.

## Resource access

- User: self manages profile/settings. The active partner sees only display name
  and authorized private avatar. No public profiles or user directory.
- Invitation: creator manages it; possession of a valid code permits a limited
  pairing request, not content access. The approved guest flow requires both
  people's confirmation. Other users receive no claimant/profile details.
- Couple, content, saved gallery, game state: only current members of that
  active couple, checked on every REST request and WebSocket
  subscription/action.
- Media: the uploader may access a pending upload; ready content media also
  requires active couple membership and a non-deleted content item. Other
  resource types such as avatars need their own explicit policy.
- Device tokens, notification preferences, and widget settings: self only.
- Outbox/cleanup jobs: internal workers only; never client-readable.

Verify Firebase ID tokens, issuer/project, expiry, revocation, and the active
local user. Anonymous users still require verified tokens; user-entered pairing
codes cannot substitute for identity. Never accept sender, couple membership,
token ownership, or game state merely because the client supplied it.

## Approved lifecycle

| Action            | Server effect                                                                                                                                              | Effect for both devices                                                                                      |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Save/unsave       | Change the existing item's shared saved fields and version                                                                                                 | Both galleries reflect the same status; history/media are retained                                           |
| Delete one item   | Tombstone and enqueue media cleanup atomically; cancel unsent related notifications                                                                        | Both lose access immediately on the server; reconcile galleries, notifications, and widgets                  |
| Unpair            | Close the couple, end both memberships, revoke pending invitations, cancel active games, tombstone all couple content, enqueue cleanup                     | Both lose access to the old couple; no archive and no transfer to a later couple                             |
| Delete an account | Mark local user deletion-pending first; perform the unpair transaction if paired; revoke devices and cancel events; enqueue identity/profile/media cleanup | The partner becomes unpaired and loses old couple data; deleting user cannot authenticate to local resources |

Neither save nor elapsed time prevents explicit deletion. Exchanged content
remains until deleted or the couple/account lifecycle deletes it. Unpairing does
not delete the other person's user identity. Device tokens may stay registered
for the remaining account, but old-couple deliveries become ineligible.

Unpairing also schedules deletion of game state, moves, prompts, and game media;
there is no retained game archive. Account deletion additionally removes the
deleting user's profile/avatar and unreferenced pending media. Keep only minimal
access-denial/idempotency tombstones for the disclosed retention window, not
private content. A media response already in progress or bytes already
downloaded cannot be recalled; subsequent reads must fail.

Identity deletion is an external operation: deny access in Usual first, then
retry Firebase refresh-token revocation and account deletion durably. Token
verification alone must not recreate a deletion-pending local account. Logouts
revoke the current installation token and clear local data; account deletion
revokes every installation. Registration must not silently transfer a token
between accounts.

## Private media and access revocation

Keep buckets private with no public reads, public ACLs, listing, or public CDN.
For the MVP, stream reads through an authenticated backend endpoint that
rechecks content existence and membership on every request. Return
`Cache-Control: no-store` and avoid logging request bodies or credentials. Do
not issue presigned download URLs:
[presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
are bearer credentials usable until expiry, which complicates immediate
revocation. This costs backend bandwidth but simplifies the required deletion
semantics.

Bound uploads by bytes, decoded pixel count, type, and time; decode and
re-encode allowed raster formats and strip metadata. Never trust extensions or
client MIME types. Drawings start as PNG. Uploads remain inaccessible to the
partner until validation and atomic content submission. Clean unfinished uploads
after a defined TTL; physical deletion failures retry without restoring read
access. Keep physical deletion timing and operational alerts explicit before
external testing.

## Android caches, widgets, and notifications

Scope Room rows and private media files by user and couple/access version. Keep
sensitive files in app-private storage, exclude credentials/media/databases from
Android backups and device transfer, and clear them on logout, observed
deletion, or membership loss. A queued send for a closed couple must fail
instead of being retargeted to a new partner. Deep links contain opaque IDs and
always reauthorize; they are not access grants.

Default notification text is generic, with no media, drawing prompt, guess,
invite code, or message body. Events contain IDs/versions only. Recheck current
state and preferences before delivery and after receipt; duplicates refresh
once, stale signals cannot resurrect content. Use stable notification IDs so
reconciliation can cancel obsolete local notifications. Notification permission
is optional.

Default widget previews to hidden. An opted-in memory widget displays only an
eligible received item for the current couple, not a permanently cached bitmap.
Use Glance updates and WorkManager refreshes/reconciliation, plus app foreground
and push triggers. Recheck membership and visibility before providing media to
the launcher; clear old URI grants and replace with a neutral state when
invalidated. Quick actions open authenticated application screens.

There is a physical limit: a server cannot instantly erase already downloaded
files, screenshots, or pixels retained by a launcher on an offline device.
Android background work and FCM are not guaranteed to run immediately. Bound
local preview eligibility, hide on failed revalidation, and test
offline/locked-device behavior; do not promise remote erasure or exact widget
refresh deadlines. Previews require an explicit opt-in that explains this
exposure. Strict immediate offline hiding would require a different widget
experience, such as a neutral open-app shortcut. See
[Glance state/updates](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)
and
[WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent).

## Backups and logs

Production launch requires a chosen region, encrypted backups, bounded
retention, and a deletion/restore runbook. Deletion tombstones must be reapplied
before a restored system serves traffic; object versions and replicas must also
be cleaned. Do not promise immediate deletion from backups. Set and disclose
exact retention and cleanup targets before external testing; these are currently
open decisions.

Logs may contain request IDs, redacted route templates, status, duration, error
codes, and aggregate worker metrics. Exclude credentials, FCM tokens, invite
codes, emails, private object keys/URLs, media bytes, drawing prompts, guesses,
and full request/response bodies. Restrict log access and set a retention
period.

## Required authorization evidence

Before release, tests must authenticate two separate couples and substitute each
other's content IDs, media IDs, match IDs, invitation IDs, and WebSocket
targets. Test anonymous/expired/revoked tokens, deleted users, old deep links,
unpairing during upload/read/move, duplicate delivery after deletion,
installation token ownership, widget reconciliation, and account deletion with
retrying workers. Unauthorized lookups return the same not-found response as
missing resources.
