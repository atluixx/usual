# Privacy and content lifecycle

The owner approved shared saved status, deletion for both partners, and deletion
of old couple data after unpairing/account deletion. The backend is now Firebase
Auth + Firestore. Only the authentication and private-profile behavior below is
implemented; couple/content/game lifecycle operations are future work.

## Current access

Firebase Auth manages anonymous identities, optional Google credentials, legacy
email/password credentials, and session tokens. Passwords never enter Firestore,
logs, saved form state, or analytics. Authentication does not grant access to
other users' resources.

The only allowed Firestore path is the caller's exact `users/{uid}` profile. It
contains a bounded display name, immutable UID/creation time, server update
time, and schema version. No email, role, couple membership, or arbitrary
fields. Clients cannot list profiles, delete them, read another user's profile,
or access any other collection/subcollection. Both creates and updates validate
the full schema.

The Android profile cache is memory-only. Logout/identity changes remove
listeners and displayed data. Cached/pending snapshots are labelled; refresh
uses the server. Android backup is disabled. Password inputs are transient and
cleared on submission. Auth persists sessions through its own SDK storage, not
custom token files. Emulator configuration is isolated from live builds and has
no release variant.

Guest use asks for no email, password, or verification. Google linking is
optional and preserves the Firebase UID; Google tokens exist only during the
credential/link operation and are not stored by app code or copied to Firestore.
A credential collision never merges/switches accounts. Existing email users
retain their sign-in/reset path and can also link Google without changing UID.

An unlinked guest may lose access after clearing app data, reinstalling, or
losing the device. The app explains recovery limits without blocking use. There
is no guest sign-out action, automatic guest deletion, or automatic Firebase
anonymous-account cleanup configuration. Anonymous identities must remain valid
for the same lifetime as their future couple data; do not enable 30-day Auth
cleanup. Linking Google is recovery, not a copy/export of media to Google Drive.

Pairing remains unimplemented in this build. Its contract must accept two
distinct anonymous identities with explicit consent and current membership
checks. Google/email verification must not become a shared-resource access gate.

## Approved future lifecycle

| Action            | Required authoritative effect                                                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Save/unsave       | Update shared saved status on the existing content document; retain history and media                                                          |
| Delete an item    | Deny reads for both, record a tombstone and durable cleanup work, cancel obsolete notifications                                                |
| Unpair            | Close the couple, revoke both memberships/invitations, cancel games, deny old-couple access, schedule deletion of all couple content/game data |
| Delete an account | Deny local/shared resource access first, perform unpairing if needed, revoke devices/tokens, and retry Auth/profile/media deletion             |

UI must explain and confirm destructive actions. Saved status never protects an
item from deletion. Exchanged content has no automatic expiry. Unpairing leaves
the other user's identity intact, with no old-couple archive or transfer to a
new couple. Pending sends must never be retargeted to a new partner.

Firestore does not support an unbounded atomic delete of every document. Future
trusted operations must make one authoritative lifecycle/access change first,
then run bounded idempotent cleanup batches. Rules must consult current active
membership/lifecycle for access; deleting child documents alone is insufficient.
Admin SDK functions bypass rules and must check caller permissions themselves.
No account-deletion or unpair operation is advertised as available in this
build.

## Future media, devices, and delivery

Photos/drawings must use private storage, not Firestore blobs or public
tokenized download URLs. Bound file size/decoded dimensions, validate/re-encode
allowed formats, and strip metadata. Partners cannot read pending uploads.
Tombstones and membership changes deny new reads before eventual object cleanup;
failed deletion retries must not restore access. No bucket/media upload is
provisioned here.

Use generic notification text and opaque IDs/versions, with no private previews,
prompts, guesses, invite codes, or message bodies. Persist authoritative state
before FCM; recheck membership/deletion/preferences at delivery and receipt.
Stable local IDs and reconciliation remove obsolete notifications. Notification
permission is optional. Logouts revoke that installation's device registration;
account deletion revokes every installation. Tokens cannot silently move owners.

Widget previews default to hidden. Opt-in previews must revalidate membership,
visibility, and content state before giving media to a launcher. Use supported
Glance/WorkManager mechanisms and neutral states when revalidation fails. Revoke
old URI grants and clear local caches on observed deletion or membership loss.

A server cannot recall downloaded bytes, screenshots, or pixels held by an
offline launcher. FCM and Android background work do not guarantee immediate
execution. Do not promise immediate remote erasure, exact widget deadlines, or
offline cache revocation. Document and test this exposure before enabling
previews. Quick actions open full authenticated application screens.

## Backups and logs

Before external testing, select/disclose backup, object, tombstone, and log
retention windows plus deletion targets. Restores must reapply
lifecycle/deletion tombstones before serving traffic. Clean replicas/object
versions as appropriate. Do not claim immediate deletion from backups. Current
database creation does not establish a tested backup/restore policy.

Logs may contain safe error codes, request IDs, status/duration, and aggregate
metrics. Exclude passwords, tokens, invite codes, email addresses, object URLs,
private media, prompts, guesses, and full request/response bodies. Restrict
access. Release builds remove Android Log calls through R8, including dependency
calls. Debug Firebase SDK diagnostics can contain account identifiers; keep raw
logcat private and use emulator fixtures. The application does not install
Analytics or Crashlytics in this milestone.

## Required evidence

Current emulator tests cover owner-only profile access, unauthenticated and
cross-user reads/writes, collection listing, ownership/timestamp/schema
tampering, oversized/invalid fields, and unknown/subcollection denial. Android
state tests cover failures/retries, duplicate submits, and clearing old identity
state.

Future tests must use two different couples and substitute content/media/match/
invitation IDs, attempt unauthorized listener subscriptions and actions, and
verify revoked/deleted identities, unpairing during work, stale
notifications/widgets, durable deletion retries, and restore safety before those
features are released.
