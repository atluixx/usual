# Architecture decision: Go modular monolith

Status: **Go selected by the product owner on 2026-10-25**, before backend
implementation. Kotlin/Spring Boot was the initial engineering recommendation;
the owner chose Go after reviewing the trade-offs. Use Go consistently from
here.

## Candidate comparison

These are engineering judgments for Usual, not measured performance or hosting
cost claims. Kotlin and Java share Spring's ecosystem; all three can meet the
MVP.

| Concern                      | Kotlin + Spring Boot                                               | Java + Spring Boot                                                           | Go                                                                           |
| ---------------------------- | ------------------------------------------------------------------ | ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Reliability and maintenance  | Mature framework; concise types; proxy/plugin conventions to learn | Same mature framework; fewer language integration concerns; more boilerplate | Small standard library surface; explicit errors and lifecycle management     |
| PostgreSQL and transactions  | Spring JDBC, declarative transactions, Flyway                      | Same database/migration options                                              | pgx transactions and SQL migrations; boundaries wired explicitly             |
| Authentication/authorization | Spring Security and Firebase Admin Java                            | Same security integrations                                                   | Firebase Admin Go; explicit middleware and resource checks                   |
| REST and WebSockets          | Integrated MVC and WebSocket support                               | Same framework support                                                       | Standard HTTP server; a maintained WebSocket dependency added with live play |
| FCM                          | Firebase Admin Java interoperability                               | Firebase Admin Java                                                          | Firebase Admin Go                                                            |
| Testing                      | JUnit, Spring tests, Testcontainers                                | Same mature ecosystem                                                        | Built-in testing/httptest, race detector; real PostgreSQL integration tests  |
| Deployment and cost          | JVM artifact/container; memory needs must be measured              | Same JVM operational profile                                                 | Single application binary/container; generally smaller runtime footprint     |
| Speed and extensibility      | Strong integrated defaults; some framework complexity              | Strong defaults; more repetitive code                                        | Quick small services; more manual integration as features accumulate         |
| Small app that grows         | Good fit for a monolith                                            | Good fit for a monolith                                                      | Good fit with disciplined SQL, module boundaries, and shared middleware      |
| Learning value               | Transactions/security plus Kotlin overlap with Android             | Widely transferable Java/Spring concepts                                     | HTTP, SQL, transactions, concurrency, and explicit failure handling          |

The original Kotlin recommendation favored integrated transaction/security
tooling, not language familiarity. Go trades those defaults for straightforward
deployment and explicit control. Its extra integration work is acceptable if
authorization, transactions, retries, and timeouts are tested as shared
behavior. Additional stacks offer no clear requirement-driven advantage at this
stage.

Primary references:
[Spring Kotlin support](https://docs.spring.io/spring-boot/reference/features/kotlin.html),
[Spring transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction.html),
[Spring WebSockets](https://docs.spring.io/spring-framework/reference/web/websocket.html),
[Go HTTP](https://pkg.go.dev/net/http),
[pgx](https://pkg.go.dev/github.com/jackc/pgx/v5), and
[Firebase Admin SDKs](https://firebase.google.com/docs/admin/setup).

## Selected components

- Backend: Go, `net/http`, `pgx`/`pgxpool`, PostgreSQL, and
  [goose SQL migrations](https://github.com/pressly/goose). Pin compatible
  supported versions in the backend scaffold rather than floating on `latest`.
- Identity/push: Firebase Authentication and the Firebase Admin Go SDK; FCM for
  Android notifications. No passwords stored in Usual. The approved onboarding
  is guest-first with optional Google linking; see [roadmap](roadmap.md).
- Media: one private object bucket accessed by the backend. Authenticated media
  reads avoid irrevocable bearer download links; see [privacy](privacy.md).
- Android: Kotlin, Compose, Glance, Photo Picker, a custom drawing canvas,
  WorkManager, Room for durable state/pending actions, and DataStore
  preferences.
- Live play: WebSockets for invalidation/state updates; PostgreSQL remains
  authoritative. Choose and pin the WebSocket library in the live-game issue.
- Tests: Go tests/httptest plus real PostgreSQL tests; Android unit/UI tests as
  behavior is implemented. Fakes and the Auth Emulator avoid production secrets.

One deployable backend process serves REST and WebSockets and runs bounded
outbox and cleanup workers. Use managed PostgreSQL and private object storage
when deploying; select the provider/region before collecting real user data. No
Redis, broker, Kubernetes, or additional backend service is justified. Start
with one replica; adding replicas requires a separate live fan-out decision.

## Module boundaries

Use cohesive Go packages under a future `backend/internal/`; handlers call use
cases, which own authorization and transaction boundaries. Keep SQL near its
module. Avoid generic repositories and speculative plugin frameworks.

| Module           | Responsibility                                                    |
| ---------------- | ----------------------------------------------------------------- |
| Identity/pairing | Token verification, user profiles, invitations, couple membership |
| Content          | Submission state, immutable media references, exchange history    |
| Media            | Bounded uploads, image validation, private reads, deletion jobs   |
| Memories         | Shared saved status and gallery queries over content metadata     |
| Games            | Invitations, participants, valid moves, persisted match versions  |
| Notifications    | Device tokens/preferences, transactional outbox, FCM retries      |

Content and memories share the same content row and stored object. A module that
changes state and emits an event writes both in one PostgreSQL transaction. An
in-process outbox worker sends only after commit; network calls stay outside DB
transactions. PostgreSQL jobs are enough for retries and orphan cleanup.

Use request contexts, bounded timeouts/body sizes, graceful shutdown, structured
redacted logs, and explicit transaction rollback. Check protected resource
access inside the same transaction as mutations. The [domain](domain.md) defines
database constraints; [API design](api.md) defines retries and concurrency
requirements.
