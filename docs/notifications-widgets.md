# Notifications, deep links and widgets

Delivery events are committed atomically with content/game state. A Firestore
trigger attempts FCM delivery; the five-minute maintenance job retries pending
or expired-lease events with bounded exponential backoff. FCM is a refresh
signal, never authority. Duplicate signals are expected. Expired or invalid
resource/couple/recipient state suppresses old events. Delivery success means
FCM accepted a message, not that Android displayed/read it. No email, name,
caption, prompt, image, token or game board is logged or sent in previews.

All server calls require the current authenticated UID. device.register binds an
opaque registration token to its UID, capped at five devices; it cannot move a
token from another identity. device.unregister removes only the owner's token.
The app deletes/regenerates its SDK token on identity changes and tries to
unregister before sign-out. Offline clients cannot guarantee immediate remote
token deletion; payload recipient checks, current authorization and
invalid-token pruning prevent another identity from displaying those events.

preferences.set updates contentNotifications, gameNotifications and
widgetPreview booleans (all default false). Each user controls only their own
preferences. Android requests POST_NOTIFICATIONS only after an explicit action;
declining permission does not affect pairing, exchange, gallery or games.
notification.resolve(eventId) rechecks the recipient, preference, active couple
and current resource/version before returning an opaque authorized destination.

Deep links use usual://content/{coupleId}/{contentId},
usual://game/{coupleId}/{matchId}, and usual://action/{draw|photo|game}. They
are navigation requests, validated for shape and current couple before opening a
server-authorized screen. Old IDs cannot be retargeted to a new couple. No media
URL or credential belongs in a link.

Memory widgets opt in separately, show received photos/drawings only, and fetch
widget.snapshot to recheck current identity, preferences and item eligibility.
They show a neutral state on refresh failure/offline and never save private
image bytes to widget files. Quick-action widgets open the full application for
drawing, photo picking or games. Glance updates run through supported widget
callbacks and WorkManager (periodic work at least 15 minutes), and refresh after
push, foreground synchronization and observed state revocation. A launcher may
retain pixels while offline or paused: no system can recall an already visible
preview/screenshot remotely. The opt-in explains this limitation. Unpairing,
identity changes and preview opt-out clear previews when observed locally.

FCM SDK 26 uses register/unregister and onRegistered with FIDs; the manifest
enables installation-ID messaging. The backend sends with the Admin SDK fids
field. On identity changes the installation is deleted before registration for
the new identity. See the
[Android API](https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessaging)
and
[Admin send API](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk).
