# Firebase connection and profile contract

The owner selected Firebase Authentication and Cloud Firestore, replacing the
earlier Go/PostgreSQL and guest/Google onboarding decisions. The target project
is `getusualapp`, Android application ID `com.okoda.usual`, and the requested
database is Standard edition `(default)` in `southamerica-east1` (São Paulo).

## Scope and contract

This milestone uses email/password authentication. Firebase Auth owns passwords,
email addresses, verification, password reset, and session persistence. Never
write passwords or duplicate email addresses into Firestore, logs, saved
instance state, or analytics. Sign-up creates an Auth identity first; profile
setup is separately retryable if its write fails. Existing sessions restore
through the Firebase SDK.

The only client-accessible Firestore path is `users/{auth.uid}`:

| Field           | Contract                                                                  |
| --------------- | ------------------------------------------------------------------------- |
| `uid`           | Required string equal to the authenticated UID and document ID; immutable |
| `displayName`   | Required nonblank string, 1–80 characters; owner may change it            |
| `createdAt`     | Required server timestamp on creation; immutable                          |
| `updatedAt`     | Required server timestamp on each create/update                           |
| `schemaVersion` | Required integer `1`; immutable                                           |

Only an authenticated owner may get this exact document. Collection listing,
deletion, extra fields, subcollections, other users' profiles, and every other
collection are denied. Both creates and updates validate the full schema. A
profile confers no couple membership or privileged role. Future pairing requires
its own trusted transaction and authorization contract; verified email is a
prerequisite for that future operation.

The Android app uses a memory-only Firestore cache for this initial private
profile and server reads for explicit refreshes. It clears displayed profile
state and listeners when the authenticated identity changes. No persistent
private-media or offline gallery cache is introduced here. Account deletion and
couple data cleanup remain future work; direct client profile deletion is
intentionally denied.

## Environments

The live flavor connects to the registered Android app using the ignored
`android/app/src/live/google-services.json`. The emulator flavor uses a
checked-in dummy configuration for `demo-usual`, application ID
`com.okoda.usual.emulator`, and localhost emulator services through Android's
`10.0.2.2` host bridge. It does not require production credentials. Emulator
release variants are disabled, and live variants never enable emulator trust.

Auth/Firestore emulator tests must use a `demo-` project and verify the
configured emulator endpoints before making requests. They must not create test
identities or documents in `getusualapp`. The real Android client configuration
is not an Admin SDK key; server credentials are never part of the APK or
repository.

## Verification required

- Verify the remote Android registration, downloaded package/project
  identifiers, email/password provider deployment, database edition/location,
  and rules release.
- Run local Auth/Firestore emulator tests for successful email/password sign-up,
  sign-in/sign-out, profile persistence, and denied unauthorized/schema
  mutations.
- Compile/lint/test the Android app using the pinned Gradle wrapper and SDK.
- Record device testing separately. A build or web-SDK emulator test does not
  prove that Android UI flows were exercised on a device.

## Development commands

From the repository root, fetch the live client configuration using your
authorized Firebase login (the JSON stays ignored):

```sh
npx -y firebase-tools@latest login
mkdir -p android/app/src/live
npx -y firebase-tools@latest apps:sdkconfig ANDROID 1:681468816871:android:611a9aa1625264c96e2142 --project getusualapp --out android/app/src/live/google-services.json
```

Build the real Firebase client from `android/`:

```sh
./gradlew :app:assembleLiveDebug
```

The APK is `android/app/build/outputs/apk/live/debug/app-live-debug.apk`
relative to the repository root. Install it on an Android 8.0+ device through
Android Studio or ADB. The live app uses HTTPS and the real project, so use
intentional accounts; production fixtures are not part of automated tests.

For local development, start the emulators from the repository root:

```sh
npx -y firebase-tools@latest emulators:start --project demo-usual --only auth,firestore
```

Then use Android Studio's Android emulator and run this from `android/`:

```sh
./gradlew :app:installEmulatorDebug
```

This flavor uses `10.0.2.2` to reach the host's Auth port 9099 and Firestore
port 8080. It is not configured for a physical phone's network. The Emulator UI
is `http://127.0.0.1:4000`; verification/reset messages are shown locally rather
than sent to real inboxes. Data is ephemeral unless explicitly
exported/imported. `npm run test:firebase` starts/stops emulators automatically
for the test suite.

A maintainer can apply reviewed configuration with:

```sh
npx -y firebase-tools@latest deploy --only auth,firestore --project getusualapp
```

This changes the live project; ordinary CI only tests local emulators. Preserve
unrelated provider settings and inspect the selected project before deployment.
The default database must already exist in the agreed location.

## Observed remote setup

On 2026-10-25, registration returned active Android app
`1:681468816871:android:611a9aa1625264c96e2142`, package `com.okoda.usual` in
`getusualapp`. Downloaded configuration identifiers were checked against those
values. Email/password Auth deployment succeeded. Database creation confirmed
`FIRESTORE_NATIVE`, `STANDARD`, `(default)`, `southamerica-east1`; Firestore
rules/index deployment and a preceding dry-run both succeeded. No live test
identities or documents were created. Existing providers were not disabled.

The private-profile rules are a prototype, with emulator authorization/schema
coverage; review them before broad distribution. Device/build results belong in
the PR evidence. Infrastructure configuration alone does not verify UI behavior.

## Build and logging notes

Firebase Auth 25 requires Kotlin 2.4 metadata. The version catalog supplies
Kotlin 2.4.21 to AGP's built-in Kotlin through its supported classpath override.
Compose and Lifecycle are pinned to versions compatible with the available SDK
36 platform; we do not bypass metadata or lint checks. Gradle worker/heap limits
keep local emulator development within a modest memory budget.

Live release builds use R8 and remove Android Log calls, including dependency
logs. Debug builds retain SDK diagnostics, which can include account
identifiers; do not share raw logcat or use real account data as test fixtures.
The unsigned release build is for verification, not a signed/store-distributed
release:

```sh
# From android/, after downloading the live client configuration
./gradlew :app:assembleLiveRelease
```

Its output is
`android/app/build/outputs/apk/live/release/app-live-release-unsigned.apk`. The
live debug APK is convenient for local development; use intentional accounts and
keep diagnostic output private. No production log collection is installed.
