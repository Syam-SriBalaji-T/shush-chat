# Shush

Real-time 1:1 chat. Strangers are matched on shared interests, talk, and can keep each
other as friends.

**This is a portfolio and interview artifact, not a product.** Its purpose is to make one
claim and then prove it:

> Strict per-conversation total ordering with effectively-exactly-once delivery, across N
> stateless nodes, verified by an automated harness that asserts zero reordering, zero
> duplicates and zero loss — including while a node is killed mid-run.

Everything else in the system exists to force that problem into the open.

---

## Status

Phase 0 of 8 — spike and viability gate. See `docs/plan.md` §5 for the phase list.

| Phase | What it lands | State |
| ----- | ------------- | ----- |
| 0 | Spike: Spring Boot 3 on virtual threads, Flyway, Testcontainers | ✅ passing |
| 1 | Identity, domain, single-node chat | — |
| 2 | The ordering guarantee + harness v1 | — |
| 3 | Horizontal scale + harness v2 | — |
| 4 | Chaos and correctness | — |
| 5 | Presence, typing, receipts, unread, signup | — |
| 6 | Matching, friends, invites, blocks | — |
| 7 | Media and the test client | — |
| 8 | Benchmark, README, demo | — |

## Stack

`api/` Java 21 + Spring Boot 3 (Spring MVC on virtual threads, not WebFlux) · Maven ·
Postgres 16 · Redis 7 · Redpanda · Elasticsearch 8 · MinIO · Prometheus + Grafana ·
nginx. Rationale for each, with the rejected alternatives, is in `docs/aim.md` §4.

## Running it

Infrastructure runs in Docker; the application under development runs on the host, so a
debugger attaches and restarts are instant.

```bash
docker compose --profile core up -d     # postgres (redis + redpanda from Phase 2)
cd api && ./mvnw spring-boot:run        # :8080
```

`./mvnw` is the committed Maven wrapper — use it rather than a system `mvn`. Spring Boot's
Docker Compose support will start the `core` profile for you on `spring-boot:run` if it is
not already up.

Tests:

```bash
cd api && ./mvnw clean verify
```

Integration tests run against real Postgres via Testcontainers. Nothing is mocked — from
Phase 2 onward that matters, because partition assignment is exactly the behaviour a mock
would remove.

## Architecture

Full diagram lands in Phase 3, once cross-node fanout exists. The shape it is being built
towards is in `docs/plan.md` §1.

## Documentation

| File | What it settles |
| ---- | --------------- |
| `docs/aim.md` | Why the project exists; locked technical decisions with rationale |
| `docs/pre-plan.md` | Every product behaviour, in plain English |
| `docs/plan.md` | Data model, mechanisms, phases, exit criteria |
| `docs/deploy.md` | Hosting, cost, benchmark procedure, nginx changes |

---

## Open Choices

Decisions taken by the implementer because the specification did not cover them. Listed
for later review; each is the smallest reasonable choice, not a considered preference.

- **Postgres is published on host port `55432`, not `5432`.** The development machine runs
  a native Windows PostgreSQL service bound to `0.0.0.0:5432`, which prevents Docker from
  binding loopback `5432` at all (`/forwards/expose returned unexpected status: 500`). The
  container-side port is unchanged; only the host publication moved, and it is overridable
  with `POSTGRES_PORT`. Picking a non-default host port also avoids the same collision on a
  reviewer's machine, which is common.
- **`GET /api/health` delegates to the Actuator health endpoint** rather than returning a
  constant `{"status":"UP"}`, and answers `503` when the status is not `UP`. The exit
  criterion only asked for the JSON shape, but a load-balancer probe that cannot fail is
  worse than none.
- **The Phase 0 spike lives in its own `spike` package with its own Flyway migration**
  (`V1__spike_records.sql`). Migrations are forward-only, so Phase 1 drops that table in a
  new migration rather than editing `V1`.
- **Maven wrapper is script-only** (`distributionType=only-script`), so no `maven-wrapper.jar`
  binary is committed.
