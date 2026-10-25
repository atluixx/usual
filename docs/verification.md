# MVP verification record

Verification on 2026-10-25 used synthetic media and deliberately created test
identities. No production credentials, invitation codes, tokens or private
fixtures are committed. CI uses `demo-usual`, not the live project.

## Automated checks

- `npm run check`: Prettier and Markdown lint passed. All five PR #16 checks
  passed on implementation commit `0d20a19`, including the Android APK branding
  tests; parent PR #15 checks passed on `479f365`.
- `npm run test:backend`: 24 trusted-operation emulator tests passed, covering
  invitation races/expiry/reuse, membership, lifecycle cleanup, media
  validation, idempotency, game rules/concurrency, notification leases/retries
  and preferences.
- `npm run test:firebase`: 16 Auth/Firestore tests passed, including guest
  identity preservation during optional Google linking/recovery, collisions,
  cross-couple identifier substitution, prompt secrecy and denied writes.
- `npm run test:storage`: 3 private Storage rules tests passed.
- `npm run test:callable`: 3 actual SDK integration tests passed, including
  callable authentication, uploads, content exchange, game snapshots and
  reconnect recovery.
- Android state/deep-link suite: 14 unit tests passed. Prior PR CI also compiled
  and linted the emulator flavor. The final live Debug compilation/lint and all
  14 live unit tests passed. Lint reported 31 warnings and 4 hints, with no
  errors; these include the SDK lint check for the superseded token callback.
- `npm run test:branding`: default/custom APK labels, packaged logos, unchanged
  application ID, missing/damaged assets and blank names passed. The test
  restores the default build after checking changed inputs.

## Android integration

A Google APIs Android 36 emulator ran two separately installed demo flavors
(`com.okoda.usual.emulator` and `.emulator.partner`) against the Firebase
emulators. These were manual UI/device checks, not a committed instrumentation
suite:

- Both guest installations paired using a private code and mutual confirmation.
- The drawing canvas produced a preview and a private received gallery item.
- A queued drawing survived offline submission and process restart. Restoring
  connectivity delivered exactly one item. Temporary offline pairing preserved
  the last server-confirmed membership instead of clearing the composer.
- Android Photo Picker selected a synthetic PNG; preview/send/gallery worked.
- The other installation opened received image details and toggled shared saved
  status. Core flows ran with notification permission ungranted.
- Tic-tac-toe invitation, acceptance, alternating legal moves, process restart,
  deep-link recovery and consistent final winner passed on both installations.
- Draw & Guess invitation, private prompt, canvas upload, process restart,
  partner guess and cooperative completion passed.
- The partner's memory widget rendered received media and opened its authorized
  detail screen. Preview opt-out rendered a neutral widget. The quick-action
  widget opened the canvas, Android Photo Picker and game chooser.

The test AVD used three-button navigation and disabled its failing captive
portal probe so WorkManager's connected-network constraint could run. No
production network constraints were bypassed. Local machine resource pressure
and UI automation timing failures were not counted as successful app checks.

## Live Firebase

A controlled SDK test in `getusualapp` passed two-guest mutual pairing, PNG
upload/read, same-key retry, shared saved status, fresh widget snapshot,
tic-tac-toe completion and media denial after deletion. Deletion was requested
for both test identities afterward. The run discovered and corrected the Storage
service agent's missing Firestore cross-service rules role.

Firestore/Storage rules, indexes, trusted API, scheduled cleanup, identity
cleanup and the FCM delivery trigger were deployed successfully. The provider
retention settings were read back; see [privacy](privacy.md). A separate Google
APIs Android 36 run installed the optimized live APK copy signed with the local
debug key. Anonymous pairing and notification consent passed. A synthetic
drawing committed through the normal API produced a real FCM notification with
the generic preview, and tapping it opened the authorized received drawing and
its synthetic caption. No privileged test send or fake notification substituted
for the deployed trigger. The temporary identities were subsequently submitted
for deletion. Provider delivery acceptance alone was not counted as
device-display evidence.

## Remaining verification

Final live FCM display/deep-link and release packaging results are being
recorded with this PR. Native Google account selection/recovery needs a
controlled Google account and has not been exercised; Auth emulator
identity-linking tests do not prove the OAuth consent configuration. No Play
upload, production release signing, physical-device matrix, provider
physical-erasure audit or backup restore has been performed. No license has been
selected.

The final CI rerun exposed a timing flaw in the callable revocation test: a new
sign-in and revocation could land in the same second. Firebase compares
`auth_time` with `tokensValidAfterTime`; the test now waits for the next second
and asserts the revocation timestamp is strictly later before requiring denial.
Production verification still uses `verifyIdToken(token, true)`; no
authorization check was relaxed. See
[Firebase session management](https://firebase.google.com/docs/auth/admin/manage-sessions).
