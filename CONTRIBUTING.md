# Contributing to Usual

Usual is starting with a repository foundation. There is no app build, backend
module, release, or selected license yet. Do not add documentation implying
those exist before their implementation lands.

## Local checks

Install the exact Node.js version in [.node-version](.node-version), then run
from the repository root:

```sh
npm ci --ignore-scripts
npm run check
```

These commands check Markdown structure and formatting of supported
documentation, JSON, and YAML files. They do not compile or test Android, Go,
SQL, or SVG files or validate external links. `npm run format` applies
formatting; `npm run lint:docs` runs only Markdown linting. Node.js is
development tooling, not the backend runtime.

Commit `package-lock.json` with intentional tool updates. Do not use unpinned
`npx ...@latest` commands in CI. The workflow uses the same lockfile and
commands as local development, pinned action commits, read-only permissions, and
no production secrets. Add app checks with the corresponding app scaffolds, as
described in the [roadmap](docs/roadmap.md).

The scoped KaTeX override pins the patched version for
[GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7),
because the linter's transitive math extension still requests an affected
version range. Remove the override when upstream adopts a patched range; recheck
math rendering and the repository checks when updating it.

## Issue and pull request workflow

1. Read the [architecture](docs/architecture.md), [domain](docs/domain.md),
   [privacy rules](docs/privacy.md), and related issues/PRs.
2. Search [issues](https://github.com/atluixx/usual/issues?q=is%3Aissue) and
   [pull requests](https://github.com/atluixx/usual/pulls?q=is%3Apr) before
   creating work. Include closed work in the search.
3. Find or create an issue with context, scope, acceptance criteria, and
   relevant dependencies. Use existing `enhancement`, `bug`, or `documentation`
   labels.
4. Preserve local work. Start a dedicated branch from the latest `origin/main`.
   Make one logical change; avoid speculative refactors.
5. Define or update the API contract before its implementation and client.
   Discuss decisions affecting privacy, destructive behavior, or compatibility
   before implementing them. Record approved decisions in `docs/`.
6. Add meaningful tests alongside application code. Run the checks appropriate
   to the change and update documentation when setup or behavior changes.
7. Open a PR targeting `main` with `Closes #<issue>`. Report checks actually
   run, their results, limitations, and remaining decisions.
8. Address review feedback and failing checks. An authorized human merges;
   agents must not merge unless explicitly asked. Never push implementation to
   `main`.

The repository audit found no branch protection. A maintainer should configure
PR review and the real CI check as required once the workflow is available on
`main`. The workflow itself cannot enforce repository protection.

## Secrets and privacy

Use local ignored configuration and GitHub Actions secrets only when genuinely
needed. Never commit credentials, service-account keys, signing keys, database
backups, real invite codes, or private user media. Ignore rules are a
convenience; review staged changes before committing.

Ordinary tests must use test identities, private-storage fakes, or local
emulators without production credentials. Never disable authorization for
release builds to make tests pass. Keep private resource bodies and credentials
out of logs and bug reports. Follow [docs/privacy.md](docs/privacy.md) for the
intended access model.

## Documentation map

- [Repository audit](docs/repository-audit.md): inspected baseline and
  limitations.
- [Architecture](docs/architecture.md): selected stack and module boundaries.
- [Domain](docs/domain.md): proposed schema and integrity constraints.
- [API design](docs/api.md): conventions and contract work before features.
- [Privacy](docs/privacy.md): approved lifecycle and implementation
  requirements.
- [Branding](docs/branding.md): intended build configuration, not yet
  implemented.
- [Roadmap](docs/roadmap.md): ordered milestones, open decisions, and evidence.
