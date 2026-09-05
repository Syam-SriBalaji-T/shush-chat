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

Phase 1 of 8 — identity, domain and single-node chat. See `docs/plan.md` §5 for the phase list.

The ordering guarantee above is **not proven yet**. Phase 1 writes messages straight to
Postgres from the request thread on a single node; Redpanda, cross-node fanout and the
invariant harness arrive in Phases 2–4. Nothing here should be read as the finished claim.

| Phase | What it lands | State |
| ----- | ------------- | ----- |
| 0 | Spike: Spring Boot 3 on virtual threads, Flyway, Testcontainers | ✅ passing |
| 1 | Identity, domain, single-node chat | ✅ passing |
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
cp .env.example .env                    # then set SHUSH_JWT_SECRET
docker compose --profile core up -d     # postgres (redis + redpanda from Phase 2)
cd api && ./mvnw spring-boot:run        # :8080
```

`SHUSH_JWT_SECRET` has no default and must be at least 32 bytes — the app refuses to start
without it rather than signing tokens with a value anyone reading this repository knows.
Generate one with `openssl rand -base64 48`. The app reads `.env` directly, so exporting it
by hand is not necessary.

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

## What exists so far

**Identity.** `POST /api/auth/anonymous` mints a user with a generated *Adjective Noun*
display name, returns an opaque 32-byte device token (only its SHA-256 is stored) and a
24 h HS256 JWT. `POST /api/auth/device` exchanges the device token for a fresh JWT on the
same user row — so a returning browser keeps its name, and later its friends and history.
Signing up in Phase 5 will set `email` on that same row; nothing is copied or migrated,
which is why nothing resets.

**Names.** Two committed 200-word lists give 40,000 combinations. Allocation is optimistic:
propose, insert, let the unique index on `users.display_name` decide, retry on conflict.
Checking availability first would be a check-then-act race across nodes, and the index has
to be the authority anyway. `NameAllocationIT` runs 1,000 concurrent allocations and asserts
1,000 distinct names.

**Interests.** 28 seeded tags. `GET /api/interests` returns the five most popular to a new
visitor and the caller's own last-used five to a returning one.

**Chat.** `GET /ws/chat?token=<jwt>` — the JWT travels in the query string because a browser
WebSocket cannot set an `Authorization` header, so a handshake interceptor authenticates the
upgrade before the socket opens. A `send` frame is validated for membership, assigned the
next `seq`, persisted and fanned out. Frames are camelCase JSON over a sealed interface, so
the handler's switch is exhaustive at compile time.

**Sequencing and dedup.** `UPDATE conversations SET last_seq = last_seq + 1 ... RETURNING`
takes the row lock that serialises concurrent senders, in the same transaction as the insert,
so a rolled-back write cannot leave a gap. `clientMsgId` dedup is the
`messages_conversation_sender_client_msg_id_key` unique constraint — the insert uses
`ON CONFLICT DO NOTHING` and treats a zero row count as the duplicate signal, because raising
the violation would abort the transaction and leave nothing readable. A duplicate is acked
with the original `seq` and deliberately **not** fanned out again.

**History.** `GET /api/conversations/{id}/messages?before=<seq>&limit=<n>` — cursored on
`seq`, not an offset, so a page stays stable while messages keep arriving underneath.

### Not yet true

Single node. Fanout is a local `ConcurrentHashMap` lookup, there is no backplane, and
messages are written from the request thread rather than through Redpanda. Every one of
those is a Phase 2–4 problem, and the design notes above are written so those phases move
*who* runs the rules rather than *what* the rules are.

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
- **`plan.md` §2.2 lists both `unique (conversation_id, seq)` and `index (conversation_id,
  seq desc)` on `messages`; only the unique constraint is created.** A btree scans backwards
  as cheaply as forwards, so the descending index would duplicate the constraint's index and
  cost a second write per message for nothing. Trivially added later if a plan shows otherwise.
- **Conversations are created by a flag-guarded dev endpoint** (`POST /api/dev/conversations`,
  off unless `shush.dev-endpoints.enabled`) until matching lands in Phase 6. The caller must be
  one of the two participants.
- **`PUT /api/interests/mine` records a selection.** `pre-plan.md` settles the behaviour but
  names no endpoint; the returning-visitor tiles need the selection stored somewhere.
- **Name collisions past five random attempts fall back to `Adjective Noun N`** (e.g.
  `Quiet Otter 2`), matching the "adding a number when one is taken" line in `pre-plan.md` §7.
- **The WebSocket lives at `/ws/chat` and accepts only `send` frames so far.** `read`,
  `typing` and `find` join the sealed `ClientFrame` interface in Phases 5 and 6.
- **`spring.config.import` reads the gitignored `.env`** so `./mvnw spring-boot:run` works
  without exporting variables by hand. `.env` remains gitignored; `.env.example` stays blank.
