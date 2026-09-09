# Shush — what is actually implemented

> `plan.md` is the plan. **This file is the record of what was built**, where it diverged, and
> why. Where the two disagree, this one is current.
>
> Every claim here is backed by a command in `README.md` §8 that passes.

**Status: all eight phases complete**, plus a platform split that came after the plan was
written. Outstanding: the benchmark on dedicated hardware and the recorded demo (`deploy.md` §3
and §4), both of which need a machine that is not this laptop.

---

## Phases

| Phase | Exit criterion | State |
| ----- | -------------- | ----- |
| 0 — Spike and viability gate | `./mvnw clean verify`, `/api/health`, WebSocket echo test | passed |
| 1 — Identity, domain, single-node chat | auth, names, history, 1,000 concurrent name allocations | passed |
| 2 — Ordering guarantee + harness v1 | harness exits 0 on 50×200 messages | passed |
| 3 — Horizontal scale + harness v2 | `--assert-multinode` across 3 replicas | passed |
| 4 — Chaos and correctness | replica killed mid-send, invariants hold | passed |
| 5 — Presence, typing, receipts, unread, signup | TTL expiry, unread reset, signup preserves identity | passed |
| 6 — Matching, friends, invites, blocks | 100× concurrent-claim test, retention jobs | passed |
| 7 — Media and the test client | media flow + real-browser journey | passed |
| 8 — Benchmark, README, demo | eight-section README, numbers traced to committed runs | partial — see below |

**Phase 8 is partial by design.** The README is complete and every number in it traces to raw
output in `bench/results/`. What is missing is the run on resized hardware with the load
generator on a separate instance, and the recorded demo. The README says so rather than
presenting laptop figures as a headline number.

## Test surface

| Suite | Count | Notes |
| ----- | ----- | ----- |
| Integration (`api`) | 170 | Real Postgres, Redis, Redpanda, Elasticsearch, MinIO and Chrome via Testcontainers. Nothing mocked |
| Unit (`api`) | 11 | Pure logic only |
| Harness self-tests (`bench`) | 17 | Each invariant fed a violating stream, asserted to report it |
| Isolation (`syamdev-platform`) | 5 checks | Cross-tenant access attempted with real credentials |

---

## Divergences from `plan.md`

Everything below is a deliberate departure, with the reason.

### Infrastructure moved to separate repositories

`plan.md` §4 puts `compose.yaml`, `compose.replicas.yaml`, `compose.observability.yaml` and
`infra/` in this repo. They are gone. Shared services now live in
[`syamdev-platform`](https://github.com/Syam-SriBalaji-T/syamdev-platform) and
[`syamdev-observability`](https://github.com/Syam-SriBalaji-T/syamdev-observability), because
this stopped being the only app that will run on the box. This repo keeps
`compose.platform.yaml`: three stateless replicas and nothing else.

**This knowingly gives up R7** (`aim.md` §2): a reviewer can no longer clone this repo alone and
run it with one command. That was the owner's call, taken explicitly. The replacement is three
repos and a documented order in the platform README.

### Names are tenant-prefixed on the platform

On a shared broker and a shared search cluster, a tenant is only granted its own prefix, so:

| | Standalone default | On the platform |
| --- | --- | --- |
| Kafka topic | `chat.messages` | `shush.chat.messages` |
| Consumer group | `chat-writer` | `shush.chat-writer` |
| Search index | `waiting` | `shush-waiting` |

The consumer group was hardcoded in the `@KafkaListener` annotation and could not be namespaced
at all until this change. The guarantee is unchanged; only the names move.

### `friend_requests.status` gained `declined`

`plan.md` §2.2 allowed `pending` / `accepted` / `expired`. That conflates "nobody replied" with
"someone said no", and the purge rule has to tell them apart. Added in migration `V5`.

### The descending index on `(conversation_id, seq)` was not created

`plan.md` §2.2 lists both a unique constraint and a descending index. A btree scans backwards as
cheaply as forwards, so the second would duplicate the first's index at the cost of an extra
write per message.

### `/api/health` is liveness; readiness is a separate endpoint

`plan.md` §5 asks only for a health endpoint. One endpoint doing both was actively harmful: under
chaos-run load the container probe timed out on dependency checks and declared a healthy node
dead. Liveness now performs no I/O. Readiness checks Postgres and Redis, and deliberately not
Kafka or Elasticsearch — a broker or search blip must not take every replica out of rotation.

### The writer never drops a record

`plan.md` does not specify error handling for the consumer. Spring Kafka's default retries ten
times and then **skips the record** — silent message loss under database pressure, in the one
system whose central claim is that nothing is lost. The writer now retries indefinitely, so a
persistent failure stalls that partition instead.

### `docs/SCHEMA.md` is generated

Anticipated by `plan.md` §2.1 and now real: written by `SchemaDocIT` on every `./mvnw verify`,
by reading a real Postgres after every migration has run. It cannot drift, because it is not
maintained — it is asked.

---

## Bugs the tests and harness found

Each was invisible to code review and would have shipped.

| # | Bug | Found by |
| - | --- | -------- |
| 1 | Name-allocation retry ran inside an aborted transaction, so it could never succeed | ordering test timing |
| 2 | Cross-node delivery reordered under load — the container dispatched each frame on its own thread | chaos harness, 1 conversation in 50 |
| 3 | Two sockets opening in the same millisecond raced the backplane's lazy subscribe; the second user was never subscribed | instrumenting a failing test |
| 4 | Spring Kafka's default error handler silently skipped unwritable records | reading the failure path while diagnosing (2) |
| 5 | No container memory limit, so each replica sized its heap for the whole host and the box swapped | chaos run failing with what looked like message loss |
| 6 | `UUID.compareTo` disagrees with Postgres byte ordering, failing ~half of friend requests at random | friend-request tests, intermittently |
| 7 | `recordSelection` bulk-deleted then re-saved managed entities — broken for every returning visitor | real-browser journey test |
| 8 | `crypto.randomUUID` is undefined outside a secure context, so every send threw over plain HTTP | browser test reaching the app on a non-localhost host |
| 9 | An exception in one frame handler closed the socket, so a bug looked like a network fault | browser test |
| 10 | Any tenant could open the `postgres` maintenance database and read the shared catalogs | `verify-isolation.sh`, first run |
| 11 | SASL was wired into the producer and consumer but not the `KafkaAdmin`, so topic creation failed silently and the broker auto-created `chat.messages` with **one** partition — the partitioning the whole design rests on, quietly not happening | reading `partitions assigned: []` while chasing something else |
| 12 | Friend-request notifications were published *inside* the transaction that created them, so the recipient's client re-read the list and saw nothing | browser test |
| 13 | Images could never render: `<img>` sends no `Authorization` header (401), and the client percent-encoded the key's slashes, which Spring's firewall rejects (400) | browser test asserting the image *loads*, not that a bubble exists |
| 14 | The presigned upload URL was signed for `minio:9000` — a host no browser can resolve | browser test |
| 15 | Single-quoting `.env` values made them shell-safe and broke `./mvnw spring-boot:run`, which reads the same file as `.properties` where a quote is just a character | a numeric port failing to parse |
| 16 | `rpk security user update` rejects `-p`; only `create` takes it. Provisioning passed on a fresh volume and failed on every run after | second `docker compose up` |
| 17 | nginx health-checked itself over `localhost`, which resolves to `::1` first, while nginx listened on IPv4 only — a working edge reported unhealthy | reading `docker ps` |
| 18 | `main` declared `272px 1fr`, and a hidden sidebar leaves the grid rather than collapsing its track, so the landing page rendered squashed against the left edge | a screenshot |
| 19 | The client acknowledged reads from a view that was not on screen — which lies to the sender and zeroes your own unread count | the unread badge never appearing |
| 20 | `now - Long.MIN_VALUE` overflows, so the CORS cache looked permanently fresh and the origin set stayed empty for ever, refusing every cross-origin request with nothing logged | a preflight from an origin that was in the table |
| 21 | nginx forwarded `$host`, which drops the port, so the app compared `localhost:8081` against `localhost`, decided its own frontend was cross-origin, and refused the websocket handshake | the Next.js client failing to connect at all |

Two of these are worth separating out, because the tests that "covered" them passed:

- **12 and 13 were both hidden by weak assertions.** The journey test waited for
  `presenceOfElementLocated` on the request button — presence, not visibility — so it passed
  against an element that no recipient could ever see. Nothing asserted that an image *loads*,
  only that a bubble appeared. Both assertions are now the stronger ones.
- **20 and 21 are the same shape as 11.** Each was a silent refusal with no log line and no
  failing test — a cache that never refreshed, and a port dropped by a proxy. Both were found by
  asking the running system a question, not by reading the code that caused them.
- **11 changed nothing observable.** Ordering still held, dedup still held, the harness still
  passed — on one partition, with eleven of twelve consumers idle. A correctness harness cannot
  catch a scalability property that has silently stopped being exercised.

---

## Where the code lives

```
api/src/main/java/site/syamdev/shush/
  auth/          anonymous identity, device tokens, signup, login, JWT
  user/          names, interests, profile
  conversation/  lifecycle, participants, history, resume
  message/       producer, chat-writer consumer, sequencing, dedup
  realtime/      WebSocket handler, session registry, Redis backplane, ordered delivery
  presence/      presence, typing
  matching/      wait pool, Elasticsearch scoring, atomic Lua claim
  social/        friend requests, friendships, blocks, reports, invites
  media/         presigned upload, HEAD confirmation
  scheduler/     five retention jobs behind a Redis lock
  config/        security, Kafka, Redis, storage, node identity
```

`bench/` is a standalone harness sharing **no code** with `api/`, so a bug in a shared helper
cannot cancel itself out across both sides.

`web/index.html` is the whole client: one file, no build step, no dependencies.
