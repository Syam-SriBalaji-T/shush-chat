# Shush

Real-time 1:1 chat. Strangers are matched on shared interests, talk, and can keep each
other as friends.

**This is a portfolio and interview artifact, not a product.** Its purpose is to make one
claim and then prove it:

> Strict per-conversation total ordering with effectively-exactly-once delivery, across N
> stateless nodes, verified by an automated harness that asserts zero reordering, zero
> duplicates and zero loss — including while a node is killed mid-run.

Everything else in the system exists to force that problem into the open.

## The guarantee, stated precisely

**For any conversation C, there exists one total order over the messages of C, and every
observer sees exactly that order.** Concretely, for all messages in C:

1. **Dense sequencing.** Each message carries a `seq` that is unique within C, and the set of
   assigned `seq` values is exactly `1..n` with no gaps.
2. **Agreement between observers.** Both participants' sockets receive messages in ascending
   `seq` order, and the durable history endpoint returns the same order after any reload.
3. **Exactly-once effect.** A message with a given `clientMsgId` is persisted at most once and
   delivered to each participant at most once, however many times the client retries it or the
   broker redelivers it.

**Preconditions.** The order is defined by the order in which the broker accepted the messages,
not by wall-clock send time — there is no global clock and two concurrent senders on different
machines have no meaningful "true" order to recover. The guarantee holds while `chat.messages`
is keyed by `conversationId` and the partition count is not changed under a live conversation;
increasing partitions re-keys existing conversations onto different partitions and breaks it.

**Where it is enforced.** One conversation → one partition (the key) → one consumer thread (the
`chat-writer` group) → one writer. `seq` is claimed with `UPDATE conversations SET last_seq =
last_seq + 1 ... RETURNING`, in the same transaction as the insert, so a rolled-back write
releases its number rather than leaving a gap. Deduplication is the
`messages_conversation_sender_client_msg_id_key` unique constraint — a database guarantee, not
application logic.

**What proves it.** `bench/`, run against a live stack. Latest run, one node, WSL laptop:

```
50 conversations · 200 messages each · 100 concurrent sockets · 10,000 messages
wall clock 12.09s · 827 msg/s end to end
no gaps in seq           ok
identical order observed ok
no duplicate deliveries  ok
nothing lost             ok
```

That number is a single-node laptop figure and is *not* the headline benchmark; Phase 8
produces that on dedicated hardware with the load generator on a separate instance.

---

## Status

Phase 2 of 8 — the ordering guarantee, proven on one node. See `docs/plan.md` §5.

The guarantee below holds and is asserted by an automated harness. What is **not** proven
yet is that it survives *horizontal scale* and *node failure* — there is still one node,
so cross-node fanout is untested and nothing has been killed mid-run. Those are Phases 3
and 4, and they are the ones that make the claim worth making.

| Phase | What it lands | State |
| ----- | ------------- | ----- |
| 0 | Spike: Spring Boot 3 on virtual threads, Flyway, Testcontainers | ✅ passing |
| 1 | Identity, domain, single-node chat | ✅ passing |
| 2 | The ordering guarantee + harness v1 | ✅ passing |
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

The invariant harness, against a running stack:

```bash
cd bench && ./mvnw clean package
java -jar bench/target/shush-bench.jar --mode=ordering --conversations=50 --messages=200
# exits 0 only if all four invariants hold
```

The harness has its own tests (`InvariantsTest`) that feed each check a stream violating it and
assert it reports the violation — a harness that cannot fail would make a green run meaningless.

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

**The log.** A send is *produced* to Redpanda topic `chat.messages`, keyed by `conversationId`,
with `acks=all` and an idempotent producer. The socket handler never writes to `messages`. The
`chat-writer` consumer group is the only writer, and because the key is the conversation id,
every message for one conversation lands on one partition and is handled by one thread.

**Two-stage ack.** `sent` when the log accepts the message — it will not be lost. `delivered`
once the writer has committed it and assigned a `seq` — the first moment anything can say where
it sits in the order. Collapsing these into one ack would mean either lying about durability or
withholding the ack until after a database round trip.

**Sequencing and dedup.** `UPDATE conversations SET last_seq = last_seq + 1 ... RETURNING`
takes the row lock that serialises concurrent senders, in the same transaction as the insert,
so a rolled-back write cannot leave a gap. `clientMsgId` dedup is the
`messages_conversation_sender_client_msg_id_key` unique constraint — the insert uses
`ON CONFLICT DO NOTHING` and treats a zero row count as the duplicate signal, because raising
the violation would abort the transaction and leave nothing readable. A duplicate is acked with
the original `seq` and deliberately **not** delivered again.

**History.** `GET /api/conversations/{id}/messages?before=<seq>&limit=<n>` — cursored on
`seq`, not an offset, so a page stays stable while messages keep arriving underneath.

### Not yet true

Single node. Fanout is still a local `ConcurrentHashMap` lookup behind a `MessageDispatcher`
interface, and there is no Redis backplane, so cross-node delivery is entirely untested — the
`--assert-multinode` flag exists in the harness precisely so a Phase 3 run cannot silently pass
on one node. Nothing has been killed mid-run yet either. Phases 3 and 4.

## Design decisions

### Redpanda as the write-ahead log, not a database write followed by a publish

**Chosen.** The client's message is produced to `chat.messages` first. A single consumer group
is the only writer to Postgres, and it also triggers fanout.

**Rejected: write to Postgres, then produce.** This is a dual write to two systems that cannot
be made atomic. If the process dies between the commit and the produce, the message is durable
but never delivered and never appears on anyone's socket — the worst failure mode available,
because the sender was already acked. Fixing it properly needs a transactional outbox plus a
relay, which is strictly more machinery than moving the write behind the log.

**Rejected: write to Postgres only, fan out directly.** Ordering then depends on which node's
transaction commits first, which is a race between concurrent senders on different machines
with no arbiter. Two participants can and will observe different orders. `SERIALIZABLE` plus a
per-conversation advisory lock could recover it, at the cost of serialising every send on a
database lock — and the ordering would still be invisible to any later consumer.

**What the chosen design costs, honestly.** A send now takes a broker round trip before the
`sent` ack, adding latency a direct insert would not. Message delivery is asynchronous relative
to the request, so the client needs the two-stage ack to distinguish durable from sequenced.
And the broker is a new operational dependency that must be up for chat to work at all — the
system fails closed on `produce_failed` rather than accepting a message it cannot order.

**Why this is the right trade here.** Ordering under concurrent producers is the project's
central claim, and this design makes it a property of the topology (one key → one partition →
one consumer) rather than something enforced by locking discipline that a future change could
quietly break.

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
- **The bench harness shares no code with `api/`.** It speaks only the public HTTP and
  WebSocket protocol, so a bug in a shared serialisation or ordering helper cannot cancel itself
  out across both sides.
- **`--messages` must be even**, since it is split between the two participants.
- **The harness reports `nodeId` if a frame carries one**, and `--assert-multinode` fails a run
  where fewer than two nodes participated. The server does not emit `nodeId` yet; Phase 3 adds it.
- **`spring.config.import` reads the gitignored `.env`** so `./mvnw spring-boot:run` works
  without exporting variables by hand. `.env` remains gitignored; `.env.example` stays blank.
