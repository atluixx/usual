# Firebase connection and profile contract

The owner selected Firebase Authentication and Cloud Firestore, replacing the
earlier Go/PostgreSQL backend decision. Guest-first access with optional Google
linking is the current onboarding decision (#7). The target project is
`getusualapp`, Android application ID `com.okoda.usual`, and the database is
Standard edition `(default)` in `southamerica-east1` (São Paulo).

## Scope and contract

The welcome screen offers **Continue without an account**, creating/reusing an
anonymous Firebase identity. Email, password, verification, and Google linking
are not required. The Firebase SDK restores the same session on restart. A
profile write is separately retryable and never creates another identity.

**Link Google (optional)** requests a Google credential through Android
Credential Manager only after a tap. `linkWithCredential` attaches it to the
current UID; it must not call `signInWithCredential` as a fallback. Cancellation
or an already-linked credential leaves the guest and profile unchanged. Google
sign-in from the welcome screen is a separate explicit recovery action; do not
silently replace an active guest with another account. Existing email/password
users retain sign-in/reset support and their existing UID/profile.

Never write credentials/tokens or duplicate email addresses into Firestore,
logs, saved instance state, or analytics. There is no unlinked-guest sign-out
UI. Reinstalling/clearing data or losing the device may lose guest access;
Google linking is an optional recovery method. Do not enable automatic anonymous
user cleanup: its 30-day deletion would strand valid guest/couple data.

The owner-private profile path is `users/{auth.uid}`:

| Field           | Contract                                                                  |
| --------------- | ------------------------------------------------------------------------- |
| `uid`           | Required string equal to the authenticated UID and document ID; immutable |
| `displayName`   | Required nonblank string, 1–80 characters; owner may change it            |
| `createdAt`     | Required server timestamp on creation; immutable                          |
| `updatedAt`     | Required server timestamp on each create/update                           |
| `schemaVersion` | Required integer `1`; immutable                                           |

Only an authenticated owner may get this exact document. Collection listing,
deletion, extra fields, subcollections, other users' profiles, and undefined
collections are denied. Both creates and updates validate the full schema. A
profile confers no couple membership or privileged role.
[Trusted pairing operations](mvp-contract.md) own invitations, exact-two-person
couples, unpairing and account deletion. Anonymous participants require no email
verification or Google linking. Own pairing state, participant invitations and
current active couples have narrowly scoped exact-document reads.

The Android app uses a memory-only Firestore cache for this initial private
profile and server reads for explicit refreshes. It clears displayed profile
state and listeners when the authenticated identity changes. No persistent
private-media or offline gallery cache is introduced here. Identity deletion and
couple cleanup use trusted operations and durable jobs; direct client profile
deletion remains denied.

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
- Run local Auth/Firestore emulator tests for guest creation,
  identity-preserving Google linking/recovery/collision, legacy email sign-in,
  private profile persistence, and denied unauthorized/schema mutations.
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

## Google provider setup and verification

Anonymous Auth is enabled by `firebase.json` and a real Auth configuration
deployment. The Google provider additionally needs a public support email, brand
display name, Android signing SHA-1, and web OAuth client ID. Keep real OAuth
client configuration in the ignored live `google-services.json`.

To register the certificate used by a build, obtain its SHA-1 with Gradle's
`./gradlew :app:signingReport` from `android/`, then run:

```sh
npx -y firebase-tools@latest apps:android:sha:create 1:681468816871:android:611a9aa1625264c96e2142 YOUR_CERTIFICATE_SHA1 --project getusualapp
```

Enable Google using the Firebase Auth skill's `googleSignIn` provider settings
and deploy `--only auth --project getusualapp`; then download a fresh SDK
configuration to a new temporary output path, verify the package/project, and
replace the ignored live file (the CLI refuses to overwrite an existing `--out`
file). The generated configuration must include a web OAuth client
(`client_type: 3`), from which the Google services plugin creates
`default_web_client_id`. A missing client shows a clear setup error when Google
is tapped and does not block guest access. Register the actual Play App Signing
certificate before testing a Play-distributed build; the local debug certificate
only covers builds signed with this machine's debug keystore.

Real Google account selection requires a live build and Google Play services.
The emulator flavor disables real Google selection. Auth emulator integration
tests use emulator-only synthetic Google ID tokens to prove UID preservation,
profile continuity, collision safety, and recovery. They do not verify the real
Google OAuth/consent setup. Android state tests cover picker cancellation and
busy/retry state. Record live Google device testing separately.

The device tester reported “selected page mode is invalid” on the email action
page. The Android code calls Firebase’s standard `sendEmailVerification` without
custom action settings; it does not build or rewrite the link. This points to
the hosted action handler/link rather than a reason to require registration. The
exact cause is not yet confirmed. Do not request/store the user’s action link or
one-time code. Removing verification as a product prerequisite does not prove
that a legacy email link problem was fixed.

On 2026-10-25, Auth deployments enabling anonymous and then Google sign-in both
succeeded, preserving email/password. The owner selected
`support@getusual.space` for Google support (the mailbox/domain still needs to
be made operational). This machine’s debug SHA-1 was registered for the existing
Android app. No Google OAuth/physical-device test has yet been claimed, and no
production test users/documents were created by automated tests.

## Trusted pairing

Build Functions with npm ci --ignore-scripts --prefix functions followed by npm
--prefix functions run build. Start local Auth/Firestore/Functions emulators
with npx -y firebase-tools@latest emulators:start --project demo-usual --only
auth,firestore,functions. The Android emulator flavor uses Functions port 5001.
The API region is southamerica-east1. Node 22 is the deployment runtime; backend
CI validates it. Auth user-delete triggers use the same idempotent lifecycle
path as explicit deletion. Maintenance expires invitations and retries bounded
cleanup every five minutes. Production deployment is separate from local test
results; record the actual result rather than treating compilation as
deployment.

## Two local Android installations

The emulator-only Gradle property partnerEmulator=true changes only that test
client's package to com.okoda.usual.emulator.partner. Build/install one normal
emulator APK, then run ./gradlew :app:installEmulatorDebug
-PpartnerEmulator=true from android/ to install the second client on the same
Android emulator. Both use demo-usual and the same local services, with separate
Auth/app data. This avoids needing two large Android emulators for couple tests.
It never changes the live/release application ID and cannot enable an emulator
release variant.

Private media uses Storage port 9199 as well as Auth/Firestore/Functions. Run
all four emulators for exchange testing. Live bucket setup is a separate
one-time Console operation; publishing rules alone does not create a bucket. The
owner confirmed São Paulo production-mode bucket setup, followed by a successful
Storage/Firestore rules/index deployment.

### Storage rules with Firestore permits

The Storage service agent needs `roles/firebaserules.firestoreServiceAgent` to
read the server-issued upload permits. On the first Storage deployment, use an
interactive CLI deployment and accept that specific role when prompted:

```sh
npx -y firebase-tools@15.34.1 deploy --only storage --project getusualapp --interactive
```

CLI 15.34.1 skips this IAM check in noninteractive/agent mode. A rules
deployment can therefore succeed while real uploads return
`storage/unauthorized`. Agent-provisioned setups must deliberately run the
interactive check; force mode may accept the standard role within an already
authorized setup. Subsequent rules deployments can be noninteractive. Verify
with a real SDK upload; an emulator does not prove the live service-agent
binding. Private processed media remain denied to Storage clients. Cloud
Functions Firestore triggers can also need a few minutes for a newly provisioned
Eventarc service agent's standard permissions to propagate; retry the failed
deployment after propagation.

The checked-in Android emulator client uses a deliberately non-secret,
valid-shaped dummy API key. The callable SDK's legacy Instance ID dependency
validates key format before making a request; `fake-emulator-api-key` fails that
check on Android. Keep the dummy project `demo-usual` and local emulator routes;
never substitute production credentials or enable emulator trust in live builds.

## Observed provider retention

A read-only API check on 2026-10-25 confirmed `getusualapp.firebasestorage.app`
is in `SOUTHAMERICA-EAST1`, with seven-day soft-delete retention and no object
versioning, retention lock or lifecycle policy. The default Standard Firestore
database is in `southamerica-east1`, with PITR disabled and no backup schedules.
These settings were observed, not changed by this implementation. Deleting
content revokes application access immediately; provider recovery copies can
remain until their retention expires. See [privacy](privacy.md).
