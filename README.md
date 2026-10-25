<p align="center">
  <img src="assets/logo.svg" width="96" height="96" alt="Usual provisional logo">
</p>

<h1 align="center">Usual</h1>

<p align="center">A private space for two people to connect, play, and keep what matters.</p>

Early development. This repository currently contains the engineering plan and
repository checks; there is no runnable Android application or backend yet.

## Features

Planned for Android:

- **Connect:** Private photo and drawing exchange, notifications, and widgets.
- **Play:** Asynchronous Draw & Guess and live tic-tac-toe.
- **Remember:** Exchange history and shared saved memories.

See the [roadmap](docs/roadmap.md) for milestones and verification status.

## Development

Use the Node.js version in [.node-version](.node-version) for repository
tooling:

```sh
npm ci --ignore-scripts
npm run check
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow and
[docs/architecture.md](docs/architecture.md) for the selected Go backend stack.

## Contributing

Start with [GitHub Issues](https://github.com/atluixx/usual/issues) and follow
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
