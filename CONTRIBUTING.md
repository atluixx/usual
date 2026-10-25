# Contributing to Usual

Usual uses Kotlin/Compose on Android and Firebase Authentication/Cloud Firestore
with trusted TypeScript Cloud Functions. There is no Go/PostgreSQL backend.
Media and shared memories extend pairing; widgets and games follow as separate
milestones. No release or license has been selected.

## Local checks

Use Node.js from [.node-version](.node-version), JDK 21 or newer, Android SDK
platform 36, and Build Tools 36.0.0. Point Android Studio or `ANDROID_HOME` at
your SDK, or set `sdk.dir` in ignored `android/local.properties`.

From the repository root:

```sh
npm ci --ignore-scripts
npm run check
npm run test:firebase
npm run test:storage
npm ci --ignore-scripts --prefix functions
npm run test:backend
```

The first check formats/lints supported Markdown/JSON/YAML/JavaScript files. It
does not compile Kotlin, parse SVG, or check external links. The second starts
local Auth/Firestore emulators and runs integration/Security Rules tests against
`demo-usual`, without production credentials. Java 21+ is required by the
emulator. Use `npm run format` to apply formatting.

From `android/`:

```sh
./gradlew :app:assembleEmulatorDebug :app:lintEmulatorDebug :app:testEmulatorDebugUnitTest
```

These commands use the checked-in dummy emulator configuration; there are no
production keys or Firebase credentials needed for ordinary CI builds. See
[Firebase setup](docs/firebase.md) for running emulators and obtaining the
ignored live app configuration. The Gradle wrapper and distribution checksum are
pinned.

The interactive Firebase setup commands use `npx -y firebase-tools@latest`, as
required by the Firebase skills. The automated emulator test script pins CLI
15.34.1 for reproducibility. Commit `package-lock.json` with intentional
updates. Scoped dependency overrides pin patched KaTeX (GHSA-238p-pmpm-9mq7) and
grpc-js (GHSA-m9gg-hp2v-232j); remove them after upstream adopts patched ranges
and rerun checks. These are test/document tooling, not Android dependencies.

## Issue and pull request workflow

1. Read the [architecture](docs/architecture.md), [domain](docs/domain.md),
   [privacy rules](docs/privacy.md), and related issues/PRs.
2. Search [issues](https://github.com/atluixx/usual/issues?q=is%3Aissue) and
   [pull requests](https://github.com/atluixx/usual/pulls?q=is%3Apr), including
   closed work, before creating tasks. Use `enhancement`, `bug`, or
   `documentation` labels.
3. Track meaningful changes in an issue with context, scope, acceptance
   criteria, and dependencies. Preserve local work; use a dedicated branch based
   on the latest default branch and keep one logical change per PR.
4. Define Firestore document/rule contracts or callable-function contracts
   before their clients. Discuss privacy/destructive/compatibility choices with
   the owner and record approved decisions in `docs/`.
5. Use the appropriate Firebase agent skills for every Firebase task. Inspect
   existing projects/apps/database edition before provisioning; preserve
   unrelated settings and never open collections in test mode as a shortcut.
6. Add meaningful tests. Run applicable formatting, Android lint/unit/build and
   Firebase emulator checks. Update documentation when behavior/setup changes.
7. Open a PR with `Closes #<issue>`, checks actually run, results, limitations,
   and remaining decisions. Address feedback and failing checks.
8. A human merges unless explicitly delegated. Never push implementation to
   `main`.

The initial audit found no branch protection. A maintainer should configure PR
reviews and real CI checks as required once available on `main`; workflows alone
cannot enforce repository protection. CI has read-only permissions and cannot
merge or deploy production configuration.

## Secrets and privacy

Keep live `google-services.json` local/ignored. It is client configuration, not
an Admin SDK key. Never commit admin credentials, signing keys, passwords,
invite codes, backups, private media, or real user fixtures. Review staged
files; ignore rules are a convenience, not a security boundary.

Never enable emulator trust in live/release variants. Use only `demo-` projects
and local endpoints for tests. Admin SDK operations bypass Security Rules and
require explicit authorization in future trusted functions. Do not log full SDK
errors, credentials, request bodies, or private user data. See
[privacy](docs/privacy.md).

## Documentation map

- [Audit](docs/repository-audit.md): inspected baseline and foundation history.
- [Architecture](docs/architecture.md): current stack and planned boundaries.
- [Firebase setup/contract](docs/firebase.md): configuration, profile schema,
  and live/emulator instructions.
- [Domain](docs/domain.md), [API](docs/api.md), and [privacy](docs/privacy.md):
  current behavior and future invariants.
- [Branding](docs/branding.md): build-time branding is still a separate task.
- [Roadmap](docs/roadmap.md): issues, milestones, remaining decisions, and
  evidence.

## Trusted operations

See [the MVP operation contract](docs/mvp-contract.md). Callable functions live
in one codebase under functions/, with a Node 22 deployment runtime and separate
lockfile. Development tooling uses Node 24; backend CI also compiles/tests under
Node 22. The API checks revoked Auth identities and authorizes every Admin SDK
operation, since Admin bypasses Security Rules. No invite codes/request bodies
or raw SDK errors are logged. Run callable integration tests with Auth,
Firestore, and Functions emulators and GCLOUD_PROJECT=demo-usual. Never enable
emulator trust in a live project.
