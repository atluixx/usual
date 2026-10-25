<p align="center">
  <img src="assets/logo.svg" width="96" height="96" alt="Usual provisional logo">
</p>

<h1 align="center">Usual</h1>

<p align="center">A private space for two people to connect, play, and keep what matters.</p>

Early development: an Android authentication and private-profile foundation.

## Features

- **Access:** Continue without an account; optionally link Google for recovery.
  Existing email accounts retain sign-in/reset support.
- **Profile:** A private display name stored in Cloud Firestore.
- **Together:** Private pairing codes, confirmation by both people, unpairing,
  and identity deletion.
- **Planned:** Photo/drawing exchange, memories, widgets, and games.

See the [roadmap](docs/roadmap.md) for feature and verification status.

## Development

Use Node.js from [.node-version](.node-version), JDK 21+, and Android SDK 36:

```sh
npm ci --ignore-scripts
npm run check
npm run test:firebase
cd android
./gradlew :app:assembleEmulatorDebug
```

See [CONTRIBUTING.md](CONTRIBUTING.md) and [Firebase setup](docs/firebase.md) to
run the app with emulators or connect the live Firebase project.

## Contributing

Start with [GitHub Issues](https://github.com/atluixx/usual/issues) and follow
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
