# Repository audit

Inspected on 2026-10-25 before changing the working tree.

- Repository: [atluixx/usual](https://github.com/atluixx/usual), public, GitHub
  Issues enabled, authenticated GitHub access available.
- Default branch: `main`; local branch and fetched `origin/main` both at
  [`bbb6221`](https://github.com/atluixx/usual/commit/bbb622132c6a6eb167433a537a0287e49235d191),
  the initial commit. The working tree was clean.
- Tracked files: only `.gitignore`, containing generic Java artifact exclusions.
  This is not evidence of an existing Java application.
- No Android, Gradle, backend, database, media, Firebase, tests, or application
  configuration existed. No product acceptance criterion was implemented.
- No open or closed issues or pull requests, workflows, contribution
  instructions, applicable `AGENTS.md`, README, selected license, releases, or
  existing logo were present in the inspected baseline.
- Existing labels include `bug`, `enhancement`, and `documentation`; reuse them.
- `main` was unprotected and no repository rulesets existed. No repository
  settings were changed during this milestone.

The [Pier README](https://github.com/pier-dock/pier-dock/blob/main/README.md)
was read as requested. Its centered logo/title/description and short feature,
development, and contribution sections inform Usual's layout. Release links,
platform claims, and licensing text are not applicable to the current
repository.

## Foundation scope

[Issue #1](https://github.com/atluixx/usual/issues/1) tracks the first change:
honest project documentation, a provisional SVG mark, issue/PR templates,
contributor workflow, reproducible formatting/linting, and least-privilege CI.
This milestone does not create an Android app, backend, database, or deployed
infrastructure.

## Layout after this milestone

```text
.github/        Issue and PR templates; repository checks workflow
assets/         Provisional README logo
docs/           Architecture, domain, API, privacy, branding, and roadmap
CONTRIBUTING.md Contributor workflow and working check commands
README.md       Concise project overview and honest status
package*.json   Locked documentation tools only
```

`android/` and `backend/` are planned locations. They do not exist yet. The
local environment has GitHub CLI access, Node.js, Go, Java, and Docker
executables; application compilation, Docker daemon availability, and Android
SDK/emulator readiness have not been verified.
