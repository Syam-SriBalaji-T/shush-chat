# Shush — Implementation Plan

> **For any agent or developer picking this up cold.** This document plus `aim.md`,
> `pre-plan.md` and `deploy.md` should be everything needed to build Shush end to end
> without asking the owner product questions.
>
> Read order: `aim.md` (why + locked decisions) → `pre-plan.md` (what the user sees) →
> this file (how to build it) → `deploy.md` (where it runs).

---

## 0. How to use this document

### 0.1 What is already decided — do not reopen

| Area | Decided in | Summary |
|---|---|---|
| Project aim | `aim.md` §1 | Resume/interview artifact. Not a product, not a startup. |
| Language & framework | `aim.md` §4.1 | Java 21 LTS + Spring Boot 3, **Spring MVC on virtual threads**, not WebFlux |
| Event log | `aim.md` §4.2 | Redpanda (Kafka protocol), self-hosted |
| Search | `aim.md` §4.3 | Elasticsearch 8, single node |
| Build tool | `aim.md` §4.4 | Maven |
| Full stack | `aim.md` §5.1 | Postgres, Redis, Redpanda, ES, MinIO, Prometheus, Grafana, nginx |
| Every product behaviour | `pre-plan.md` | Journey, features, retention, what's excluded |
| Hosting | `deploy.md` | Laptop for dev/review; resized EC2 for benchmark; hosting optional |

### 0.2 Rules for whoever implements this

1. **Do not ask the owner product questions.** `pre-plan.md` is settled. If something
   genuinely isn't covered there, make the smallest reasonable choice, implement it, and
   note it in a `## Open Choices` section at the bottom of the README for later review.
2. **Do not reopen technical decisions** in the table above. If one turns out to be
   actively wrong, stop and say so with evidence — don't silently substitute.
3. **Every phase ends with an automated check.** No phase is "done" because it looks done.
   Exit criteria are commands that pass or fail with no human judgement involved.
4. **Write the README as you go.** Each phase names what it adds to the README. Design
   decisions are recorded when made, while rejected alternatives are still fresh.
5. **Commit per phase, on a branch per phase.** `feat/p2-ordering`, etc.
6. **Never weaken a test to make it pass.** If the invariant harness fails, the system is
   wrong, not the harness.

### 0.3 Current state

Nothing is implemented. The repository contains only `docs/`:

```
shush-chat/
└── docs/
    ├── aim.md          Why the project exists; locked technical decisions + rationale
    ├── pre-plan.md     Plain-English feature spec and user journey
    ├── deploy.md       Hosting, cost, benchmark procedure, nginx changes
    └── plan.md         This file
```

Environment notes (see `deploy.md` §1 and the Docker section):
- Dev machine: Windows + WSL2, 8 cores, WSL capped at 6 GB (raise to 10 GB before Phase 4)
- **Docker is currently not reachable from WSL** — `sudo usermod -aG docker $USER`, then
  `wsl --shutdown`. Must be fixed before Phase 0.
- **Repo should be moved off `/mnt/c` into `~/dev/`** before any build runs — 5–20× faster.
- Target server is **ARM64** (Graviton). Local dev is x86_64. Only matters at deploy time.

---

## 1. Target architecture

```
                        ┌──────────────────────────────┐
   Browser ── WSS ──────►          nginx               │
                        │   least_conn, no stickiness  │
                        └───┬──────────┬──────────┬────┘
                            │          │          │
                      ┌─────▼───┐ ┌────▼────┐ ┌───▼─────┐
                      │ api-1   │ │  api-2  │ │  api-3  │   Spring Boot,
                      │         │ │         │ │         │   virtual threads
                      └──┬───┬──┘ └──┬───┬──┘ └──┬───┬──┘
                         │   │       │   │       │   │
            produce ─────┘   └──sub──┤   └──sub──┤   └── sub
                │                    │           │
        ┌───────▼────────┐    ┌──────▼───────────▼──────┐
        │   Redpanda     │    │        Redis            │
        │ chat.messages  │    │  pub/sub  ·  presence   │
        │ key=convId     │    │  typing   ·  matchpool  │
        └───────┬────────┘    └─────────────────────────┘
                │ consume (group: chat-writer)
        ┌───────▼────────┐   ┌──────────────┐   ┌──────────────┐
        │   PostgreSQL   │   │Elasticsearch │   │ MinIO / S3   │
        │  system of     │   │  matching    │   │    media     │
        │    record      │   └──────────────┘   └──────────────┘
        └────────────────┘
```

**The central idea:** Redpanda is the write-ahead log for messages. A client's message is
*produced* first, keyed by conversation ID. A single consumer group writes to Postgres and
fans out. Because the key is the conversation ID, all messages for one conversation land
on one partition, handled by one consumer, in one order — which is where the ordering
guarantee comes from.

**Nodes are stateless with respect to who is connected where.** A node subscribes to a
Redis channel per locally-connected user. Any node can serve any user; no sticky sessions.

---

## 2. Data model

PostgreSQL. Migrations via **Flyway** (`api/src/main/resources/db/migration/V__*.sql`).

```
users
  id                 uuid pk
  display_name       text unique not null       -- "Quiet Otter"
  is_anonymous       boolean not null default true
  email              citext unique null         -- set on signup
  password_hash      text null
  created_at         timestamptz not null
  last_seen_at       timestamptz not null
  deleted_at         timestamptz null

device_tokens
  token_hash         text pk                    -- sha256 of the opaque token
  user_id            uuid fk users
  created_at         timestamptz not null
  last_used_at       timestamptz not null

interests
  id                 smallint pk
  slug               text unique not null
  label              text not null
  popularity         int not null default 0     -- drives the default 5 tiles

user_interests
  user_id            uuid fk users
  interest_id        smallint fk interests
  last_used_at       timestamptz not null
  pk (user_id, interest_id)

conversations
  id                 uuid pk
  kind               text not null              -- 'stranger' | 'friend'
  state              text not null              -- 'active' | 'ended' | 'kept'
  matched_on         smallint[] null            -- interest ids, null if random match
  last_seq           bigint not null default 0  -- monotonic sequence source
  created_at         timestamptz not null
  ended_at           timestamptz null
  purge_after        timestamptz null           -- set when it ends with no friend request

conversation_participants
  conversation_id    uuid fk conversations
  user_id            uuid fk users
  read_cursor_seq    bigint not null default 0
  unread_count       int not null default 0
  left_at            timestamptz null
  pk (conversation_id, user_id)

messages
  id                 uuid pk
  conversation_id    uuid fk conversations
  sender_id          uuid fk users
  seq                bigint not null            -- monotonic within conversation
  kind               text not null              -- 'text' | 'image' | 'system'
  body               text null
  media_key          text null
  client_msg_id      uuid not null              -- idempotency key from client
  created_at         timestamptz not null
  unique (conversation_id, seq)
  unique (conversation_id, sender_id, client_msg_id)   -- dedup
  index (conversation_id, seq desc)                    -- history pagination

friend_requests
  id                 uuid pk
  conversation_id    uuid fk conversations
  from_user_id       uuid fk users
  to_user_id         uuid fk users
  status             text not null              -- 'pending' | 'accepted' | 'expired'
  created_at         timestamptz not null
  expires_at         timestamptz not null       -- created_at + 7 days
  unique (conversation_id, from_user_id)

friendships
  user_a_id          uuid fk users              -- always the lexically smaller uuid
  user_b_id          uuid fk users
  conversation_id    uuid fk conversations
  created_at         timestamptz not null
  pk (user_a_id, user_b_id)

blocks
  blocker_id         uuid fk users
  blocked_id         uuid fk users
  created_at         timestamptz not null
  pk (blocker_id, blocked_id)

reports
  id                 uuid pk
  reporter_id        uuid fk users
  reported_id        uuid fk users
  conversation_id    uuid fk conversations
  reason             text not null
  created_at         timestamptz not null

media_objects
  key                text pk                    -- media/{convId}/{uuid}
  uploader_id        uuid fk users
  conversation_id    uuid fk conversations
  status             text not null              -- 'pending' | 'confirmed'
  mime               text not null
  size_bytes         bigint null
  created_at         timestamptz not null
  expires_at         timestamptz not null       -- 24h anon, 30d if either party has email

invite_links
  code               text pk                    -- short url-safe
  owner_id           uuid fk users
  created_at         timestamptz not null
  expires_at         timestamptz not null
```

**Retention rules** (from `pre-plan.md` §5 and the storage decision):

| Row | Deleted when |
|---|---|
| `conversations` kind=stranger, no friend_request | on end — `purge_after = ended_at + 1h` |
| `friend_requests` pending | `expires_at` reached → status `expired`, conversation purged |
| `users` anonymous, no friendships | 30 days after `last_seen_at` |
| `users` anonymous, has friendships | never on the inactivity rule |
| `media_objects` | `expires_at` reached (24h anon / 30d otherwise) |

---

## 3. Key mechanisms — implement exactly these

### 3.1 Message ordering (the core claim)

**Flow:**

1. Client sends over WebSocket: `{type:"send", conversationId, clientMsgId, kind, body|mediaKey}`
2. Receiving node validates membership, then **produces to Redpanda** topic
   `chat.messages`, `key = conversationId`, `acks=all`. It does *not* write to Postgres.
3. Node immediately acks to the sender: `{type:"ack", clientMsgId, status:"sent"}`
4. Consumer group `chat-writer` consumes. Because the key is the conversation ID, every
   message for a conversation lands on one partition, consumed by exactly one consumer.
5. Consumer, in one transaction:
   - `UPDATE conversations SET last_seq = last_seq + 1 WHERE id = ? RETURNING last_seq`
   - `INSERT INTO messages (...) ON CONFLICT (conversation_id, sender_id, client_msg_id)
     DO NOTHING` — if 0 rows, it's a duplicate: skip fanout, roll back the seq bump
   - `UPDATE conversation_participants SET unread_count = unread_count + 1` for recipients
6. Consumer publishes the persisted message to Redis channel `user:{recipientId}` and
   `user:{senderId}`.
7. Whichever node holds that user's socket receives it and writes to the WebSocket.

**Why produce-then-consume rather than write-to-DB-then-produce:** a dual write to two
systems cannot be made atomic without an outbox. Making Kafka the write-ahead log and the
consumer the *only* writer removes the dual write entirely. Record this in the README under
Design Decisions; the rejected alternative is "write to Postgres first, then produce,"
which loses ordering under concurrent producers and can drop the produce after commit.

**Topic config:** `chat.messages`, 12 partitions, replication 1 (single broker locally),
`retention.ms = 604800000` (7 days).

### 3.2 Deduplication

- `client_msg_id` is a UUID generated by the client, stable across retries of the same
  logical send.
- The unique constraint `(conversation_id, sender_id, client_msg_id)` is the enforcement
  point. `ON CONFLICT DO NOTHING` + row count check is the detection.
- On the receive side, clients also track seen `(conversationId, seq)` and drop repeats —
  needed because Redis pub/sub can redeliver on reconnect.

### 3.3 Cross-node fanout

- On WebSocket connect: node subscribes to Redis channel `user:{userId}`.
- On disconnect: unsubscribe.
- Local registry: `ConcurrentHashMap<UUID, Set<WebSocketSession>>` per node (a user may
  have several tabs).
- Fanout is always via Redis, even when sender and recipient are on the same node. Keeping
  one path avoids a class of bugs where same-node works and cross-node doesn't.

**Backpressure:** before writing to a session, check `session.isOpen()` and the outbound
buffer. If a session's buffer exceeds a threshold (start at 1 MB), close it with a policy
violation — a client that cannot keep up must reconnect and re-sync from history rather
than being buffered indefinitely. Document this in Known Limitations.

### 3.4 Presence and typing

| Key | Value | TTL |
|---|---|---|
| `presence:{userId}` | nodeId | 45 s, refreshed by a 15 s heartbeat |
| `typing:{convId}:{userId}` | `1` | 5 s |

- Online = key exists. Last-seen falls back to `users.last_seen_at`.
- Client throttles typing events to at most one per 3 seconds. Server rejects more.
- On graceful disconnect, delete the presence key immediately; TTL covers crashes.
- Friend online status: a single `MGET` over friend ids.

### 3.5 Unread counts and read receipts

- `unread_count` maintained by the writer consumer (§3.1 step 5). No `COUNT(*)` at read time.
- Read: client sends `{type:"read", conversationId, seq}`. Server sets
  `read_cursor_seq = max(current, seq)`, `unread_count = 0`, publishes a read event to the
  other participant.
- Drift reconciliation: a nightly job recomputes counts from `messages` vs `read_cursor_seq`.
  Documented as a deliberate eventual-consistency tradeoff.

### 3.6 Matching

State in Redis; scoring in Elasticsearch.

1. Client sends `{type:"find", interests:[...], patience: 5|10|0}` (0 = indefinite).
2. Server writes the user into the wait pool: Redis sorted set `matchpool`, score = enqueue
   epoch ms; and indexes `{userId, interests, enqueuedAt}` into ES index `waiting`.
3. Server immediately queries ES for the best-overlapping waiting user, excluding self,
   blocked pairs, and existing friends.
4. If a candidate is found, **claim both atomically** with a Redis Lua script that removes
   both ids from `matchpool` only if both are still present. Loser of a race retries.
5. On success: create the conversation, delete both from `waiting`, push
   `{type:"matched", conversationId, sharedInterests}` to both users.
6. If no candidate: the user waits. A scheduled tick every 500 ms retries.
7. When `patience` elapses (and patience ≠ 0): match with the oldest waiting user
   regardless of interests, `matched_on = null`.

**Be honest in the UI** (already in `pre-plan.md` §3): the conversation header says whether
this was an interest match or a random one.

### 3.7 Media

1. `POST /api/media/upload-url` with `{conversationId, mime, sizeBytes}`.
2. Server validates: mime in `{image/jpeg, image/png, image/webp, image/gif}`,
   `sizeBytes <= 5 MB`, user is a participant. Inserts `media_objects` row as `pending`.
   Returns a presigned PUT valid 5 minutes for key `media/{convId}/{uuid}`.
3. Client PUTs directly to MinIO/S3. Bytes never touch the API.
4. Client sends the chat message with `mediaKey`.
5. Before accepting, the writer consumer issues a HEAD on the object. Missing → reject the
   message. Present → mark `confirmed`, record real size.
6. A scheduled job deletes `pending` rows older than 1 hour and their objects, plus anything
   past `expires_at`.

### 3.8 Identity and auth

- **Anonymous:** `POST /api/auth/anonymous` → server generates a 32-byte opaque token,
  stores `sha256(token)`, creates the user with a generated name, returns
  `{token, jwt, user}`. Client keeps `token` in `localStorage`.
- **Return visit:** `POST /api/auth/device {token}` → new JWT.
- **Signup:** `POST /api/auth/signup {email, password}` with a valid JWT → sets `email`,
  `password_hash`, `is_anonymous = false` on the *existing* row. Nothing is migrated or
  copied; that is the whole point.
- **Sign in:** `POST /api/auth/login {email, password}` → JWT.
- JWT: HS256, 24 h expiry, claims `sub` (user id), `anon` (bool). Secret from env.
- WebSocket auth: JWT in the connect query string, validated before the session opens.

### 3.9 Name allocation

- Two curated lists in `api/src/main/resources/names/{adjectives,nouns}.txt` — 200 each.
- Generate `Adjective Noun`; attempt insert against `users.display_name` unique index; on
  conflict retry up to 5 times; then append a number until free.
- **Shuffle:** `POST /api/me/shuffle-name` — allocates a new name, releases the old one.
  Rate limited to 1/second per user via a Redis token bucket.
- **Custom names:** only when `is_anonymous = false`. Validate 3–24 chars, no impersonation
  of the generated format, uniqueness enforced by the same index.

### 3.10 Scheduled jobs

One scheduler, config-driven, with Redis-based locking so only one replica runs each job —
this deliberately mirrors the distributed cron work on the résumé, and no job may ever run
concurrently with itself.

| Job | Interval | Action |
|---|---|---|
| `purge-conversations` | 5 min | Delete conversations past `purge_after` |
| `expire-friend-requests` | 15 min | `pending` past `expires_at` → `expired`, purge conversation |
| `purge-media` | 1 h | Delete objects past `expires_at`, and `pending` older than 1 h |
| `purge-anonymous-users` | 24 h | Anonymous, no friendships, `last_seen_at` > 30 d |
| `reconcile-unread` | 24 h | Recompute `unread_count` from messages vs read cursor |

Locking: `SET lock:{job} {nodeId} NX PX {ttl}`, released on completion. If the lock is held,
skip this tick — never queue.

---

## 4. Repository layout

```
shush-chat/
├── README.md                       the deliverable (R5, R6, R7)
├── compose.yaml                    dev infrastructure ONLY — no app service
├── compose.replicas.yaml           overlay: 3 api replicas + nginx
├── compose.observability.yaml      overlay: Prometheus + Grafana
├── .env.example
├── api/
│   ├── pom.xml
│   └── src/
│       ├── main/java/site/syamdev/shush/
│       │   ├── ShushApplication.java
│       │   ├── config/             Security, Kafka, Redis, ES, S3, VirtualThread config
│       │   ├── auth/               anonymous, device, signup, login, JWT
│       │   ├── user/               profile, names, interests, shuffle
│       │   ├── conversation/       lifecycle, participants, history
│       │   ├── message/            producer, writer-consumer, dedup, sequencing
│       │   ├── realtime/           WebSocket handler, local registry, Redis backplane
│       │   ├── presence/           presence + typing
│       │   ├── matching/           wait pool, ES scoring, Lua claim
│       │   ├── social/             friend requests, friendships, blocks, reports, invites
│       │   ├── media/              presigned URLs, confirmation
│       │   ├── scheduler/          config-driven jobs + Redis locks
│       │   └── common/             errors, ids, clock, metrics
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── db/migration/       Flyway
│       │   └── names/              adjectives.txt, nouns.txt
│       └── test/java/…             mirrors main; Testcontainers
├── web/
│   └── index.html                  single-file test client, no build step
├── bench/
│   ├── pom.xml                     standalone Java harness
│   └── src/main/java/…             load generator + invariant assertions
├── infra/
│   └── nginx/                      nginx.conf + upstream for local replicas
└── docs/
    ├── aim.md  pre-plan.md  deploy.md  plan.md
```

**`compose.yaml` contains infrastructure only.** During development the app runs on the
host via `mvn spring-boot:run` so the debugger attaches and reload is instant. Containerised
replicas are an overlay used for multi-node testing, not the daily loop.

Compose profiles: `core` (Postgres, Redis, Redpanda), `search` (Elasticsearch), `media`
(MinIO), `obs` (Prometheus, Grafana), `full` (everything).

---

## 5. Phases

Each phase: a goal, the work, and **exit criteria that are commands, not opinions**. A
phase is done when its command passes. No phase requires the owner's involvement unless the
"Decisions needed" line says otherwise — and only one phase has one.

---

### Phase 0 — Spike and viability gate

**Goal:** prove Java + Spring is a workable choice for this developer before committing
three weeks to it. This is the gate from `aim.md` §4.4 / §6.5.

**Work**
- `api/` Maven project: Java 21, Spring Boot 3.x, `spring-boot-starter-web`,
  `-actuator`, `-validation`, `-data-jpa`, Flyway, `spring-boot-docker-compose`,
  `spring-boot-testcontainers`, Testcontainers JUnit 5 + Postgres module.
- `spring.threads.virtual.enabled=true`.
- `compose.yaml` with Postgres only, port bound to `127.0.0.1`.
- Two REST endpoints: `GET /api/health` and `POST /api/echo`.
- One WebSocket endpoint `/ws/echo` that echoes frames.
- One Flyway migration creating a trivial table; one JPA entity; one repository.
- One Testcontainers integration test that starts Postgres, writes and reads a row.

**Exit criteria**
```bash
cd api && ./mvnw clean verify          # compiles, all tests green
curl -s localhost:8080/api/health      # {"status":"UP"}
```
Plus a WebSocket echo verified by an automated test, not by hand.

**Gate:** if this takes more than a week of evenings or feels genuinely miserable, stop and
tell the owner. The fallback is NestJS; every design decision in §3 is language-independent.
**This is the only phase that can escalate a decision.**

---

### Phase 1 — Foundation: identity, domain, single-node chat

**Goal:** two browsers on one server can talk. No guarantees yet.

**Work**
- Full Flyway schema from §2.
- Anonymous auth, device-token return, JWT issue/validate (§3.8) — signup deferred to P5.
- Name allocation with the word lists (§3.9), including collision retry.
- Interests seeded; `GET /api/interests` returns the 5 by popularity, or the caller's
  last-used if known.
- WebSocket handler with JWT auth on connect; local `ConcurrentHashMap` registry.
- Conversation creation (test-only endpoint for now — matching arrives in P6).
- Send/receive text; write straight to Postgres (Redpanda arrives in P2).
- History endpoint, cursor-based on `(conversation_id, seq)`.

**Exit criteria**
```bash
cd api && ./mvnw clean verify
```
Integration tests must cover: anonymous auth issues a working JWT; the same device token
returns the same user; two WebSocket sessions in one conversation exchange messages;
history pagination returns messages in `seq` order; names never collide across 1,000
concurrent allocations.

**README:** start it. Architecture sketch, how to run, what exists so far.

---

### Phase 2 — The ordering guarantee + harness v1

**Goal:** the central claim, on one node.

**Work**
- Redpanda in `compose.yaml`. Topic `chat.messages`, 12 partitions.
- Spring Kafka producer keyed by `conversationId`, `acks=all`, idempotent producer on.
- `chat-writer` consumer group implementing §3.1 step 5 exactly.
- Sequence assignment and dedup via the unique constraints in §2.
- Client-side `clientMsgId` generation.
- Ack protocol: `sent` on produce, `delivered` after persistence and fanout.
- **`bench/` harness v1:** a standalone Java program using virtual threads that opens N
  WebSocket clients, sends M messages per conversation concurrently from both participants,
  records every received frame, and asserts on completion:
  - every `seq` in a conversation is contiguous from 1..N with no gaps
  - both participants observed the identical order
  - no `clientMsgId` appears twice
  - message count sent == count persisted

**Exit criteria**
```bash
cd api   && ./mvnw clean verify
cd bench && ./mvnw clean package
java -jar bench/target/shush-bench.jar --mode=ordering --conversations=50 --messages=200
# exits 0 only if all four invariants hold
```

**README:** the formal guarantee statement, and the Design Decision on Kafka-as-WAL with
the rejected dual-write alternative.

---

### Phase 3 — Horizontal scale + harness v2

**Goal:** the guarantee survives across nodes. This is what R4 exists for.

**Work**
- Redis in `compose.yaml`; Lettuce client.
- Redis pub/sub backplane per §3.3; subscribe on connect, unsubscribe on disconnect.
- Route all fanout through Redis, including same-node.
- Backpressure handling on slow sessions.
- `compose.replicas.yaml`: 3 `api` replicas + nginx with `least_conn`,
  `worker_connections 20480`, `proxy_read_timeout 3600s` (see `deploy.md` §7).
- App-level WebSocket ping/pong every 30 s.
- **Harness v2:** same invariants, but clients connect through nginx so participants land
  on different replicas. Add an assertion that at least two distinct `nodeId`s served the
  run — otherwise the test is silently single-node and proves nothing.

**Exit criteria**
```bash
docker compose -f compose.yaml -f compose.replicas.yaml up -d --build
java -jar bench/target/shush-bench.jar --mode=ordering --via=nginx \
     --conversations=50 --messages=200 --assert-multinode
# exits 0 only if invariants hold AND ≥2 nodes participated
```

**README:** the fanout diagram, and the Design Decision on why no sticky sessions are
needed — the strongest single entry in that section.

---

### Phase 4 — Chaos and correctness (satisfies R3)

**Goal:** the guarantee holds while a node dies. This is the highest-value artifact in the
project; it is deliberately built before any product feature.

**Work**
- **Harness v3** adds: mid-run `docker kill` of one replica; client reconnect with resume
  from last known `seq`; assertion of zero loss, zero duplicates, zero reordering across
  the kill.
- Graceful shutdown: drain WebSocket sessions, commit consumer offsets, deregister presence.
- Consumer rebalance correctness — verify a partition moving mid-run doesn't reorder or
  double-write.
- Actuator + Micrometer + Prometheus endpoint; `compose.observability.yaml`.

**Exit criteria**
```bash
java -jar bench/target/shush-bench.jar --mode=chaos --kill-node=api-2 --at-second=15 \
     --conversations=50 --messages=500
# exits 0 only if zero loss, zero dup, zero reorder across the induced failure
```

**README:** the correctness-harness section, and the first Known Limitations entries.

At the end of this phase the resume claim is proven. Everything after is enrichment.

---

### Phase 5 — Presence, typing, receipts, unread, signup

**Work**
- Presence and typing per §3.4, including the 3 s client throttle.
- "Went offline" vs "left the conversation" as distinct system events (`pre-plan.md` §3).
- Read cursors, read receipts, unread counts per §3.5.
- Signup, login, sign-out per §3.8 — attaching an email to the existing row.
- Custom names once signed up (§3.9).

**Exit criteria** — `./mvnw clean verify`, with integration tests covering: presence key
expires without a heartbeat; typing key expires after 5 s; unread increments for the
recipient only and resets on read; signup preserves user id, name, friends and messages;
a custom name is rejected while anonymous and accepted after signup.

---

### Phase 6 — Matching, friends, invites, blocks

**Work**
- Elasticsearch in `compose.yaml` (`-Xms512m -Xmx512m`, security off); `waiting` index.
- Matching per §3.6 including the Lua claim script and all three patience settings.
- Friend requests: send during or after a conversation; accept; silent decline; 7-day
  expiry. Conversations with no request are purged (§2 retention).
- Friendships, friends list with online status, blocks, reports, invite links.
- The scheduler and its five jobs per §3.10, with Redis locking.

**Exit criteria** — `./mvnw clean verify`, with integration tests covering: two users with
overlapping interests match within the patience window; patience expiry yields a random
match with `matched_on = null`; **two concurrent matchers never claim the same third user**
(run 100 times); a declined request is silent and non-repeatable; a conversation with no
request is purged by the job; the scheduler never runs a job concurrently with itself.

---

### Phase 7 — Media and the test client

**Work**
- MinIO in `compose.yaml`; presigned upload flow per §3.7; HEAD confirmation; expiry job.
- `web/index.html`: a single file, no build step, no framework. Interest picker (5 tiles),
  patience selector, name + shuffle, chat pane, friends list, dark/light toggle with dark
  as default and the choice persisted.

**Exit criteria** — `./mvnw clean verify` for the media flow (URL issued, oversize rejected,
wrong mime rejected, unconfirmed upload purged), plus one Playwright or Selenium test that
drives the real UI through: land → pick interests → get matched → exchange a message →
send a friend request. Automated; no manual clicking.

---

### Phase 8 — Benchmark, README, demo

**Work**
- Resize EC2 per `deploy.md` §3; separate load-generator instance in the same AZ.
- Run the full harness at target scale; capture the results table, Grafana screenshots, and
  the exact hardware and methodology.
- Complete the README to the eight-section spec in `aim.md` §2.1.
- Record the 60–90 s demo per `deploy.md` §4 Path A — two windows, kill a replica mid-chat,
  cut to the harness output.
- Resize back.

**Exit criteria** — README contains all eight sections; every number in it traces to a
committed harness run; `git clone && docker compose --profile full up` works from scratch on
a clean machine.

---

## 6. Testing strategy

| Layer | Tool | Scope |
|---|---|---|
| Unit | JUnit 5 + AssertJ | Pure logic — name generation, scoring, sequence rules |
| Integration | Testcontainers | Real Postgres, Redis, Redpanda, ES, MinIO. **Never mock a broker** |
| Multi-node | Testcontainers `ComposeContainer` | The replicas overlay, driven end to end |
| Invariants | `bench/` harness | Ordering, dedup, loss, under load and under chaos |
| UI | Playwright | One happy-path journey, Phase 7 |

Rules: no `Thread.sleep` in tests — use Awaitility. No mocked Kafka, ever; partitioning is
precisely the behaviour a mock removes. Every bug fixed gets a regression test first.

---

## 7. Conventions

- Java 21, records for DTOs, sealed interfaces for message types, no Lombok (the project
  targets modern Java; records cover most of it).
- Constructor injection only. No field injection.
- Package by feature, not by layer — as laid out in §4.
- All configuration through `application.yml` with env overrides. No secrets in the repo.
- Structured JSON logging; never log message bodies, tokens, or emails.
- Metrics: counter per message produced/consumed/fanned-out; timer for end-to-end latency;
  gauge for open connections.
- Branch per phase (`feat/p3-backplane`), one PR-sized commit per phase.
- Exact dependency versions pinned — no ranges, no `LATEST`.

---

## 8. Risks and fallbacks

| Risk | Signal | Fallback |
|---|---|---|
| Spring ramp too slow | Phase 0 drags past a week | Switch to NestJS; §3 is language-independent |
| WSL memory too tight | OOM or thrashing at Phase 3+ | Raise `.wslconfig` to 10 GB; run `--profile core` only |
| Elasticsearch too heavy | Can't hold the full stack | Drop ES, use a Postgres GIN index on tags; document the tradeoff (`aim.md` §4.3) |
| 10k connections unreachable locally | Harness plateaus | Check `worker_connections` and `ulimit -n` first — that's the usual cause. Then report the real number; an honest 4k beats a fabricated 10k |
| Running out of time | Phase 6 not started by week 3 | Ship Phases 0–5 + 8. Ordering, fanout, chaos and the README are the resume claim; matching and media are not |

---

## 9. Definition of done

1. `git clone && docker compose --profile full up` works on a clean machine.
2. `./mvnw clean verify` is green.
3. The chaos harness exits 0.
4. The README has all eight sections from `aim.md` §2.1, and every number traces to a
   committed harness run.
5. The demo recording is embedded in the README.
6. One resume bullet is supportable, with a guarantee, a number, and a proof.

---

## 10. Handoff prompt for a fresh session

> Paste this into a new Claude Code session started in the `shush-chat` directory.

```
I'm building Shush, a real-time chat service in Java 21 + Spring Boot 3. It is a
portfolio/interview project, not a product.

Read these four files in this order before doing anything:
  docs/aim.md       - why it exists, locked technical decisions and their rationale
  docs/pre-plan.md  - the plain-English feature spec and user journey
  docs/plan.md      - the implementation plan, data model, mechanisms and phases
  docs/deploy.md    - hosting, benchmarking and the nginx changes

Then start Phase 0 from docs/plan.md §5 and work forward.

Rules:
- Every product behaviour is already decided in pre-plan.md. Do not ask me product
  questions. If something genuinely isn't covered, make the smallest reasonable choice,
  implement it, and list it under "## Open Choices" at the end of the README.
- The technical decisions in plan.md §0.1 are locked. If one turns out to be actively
  wrong, stop and tell me with evidence rather than substituting silently.
- A phase is done only when its exit-criteria command passes. Those are automated - never
  ask me to click through anything to verify.
- Never weaken a test to make it pass. If the invariant harness fails, the system is
  wrong.
- Work one phase at a time on a branch named for it. Tell me when a phase's exit criteria
  pass, then continue to the next without waiting for me.
- The only thing you should stop and escalate is the Phase 0 viability gate: if Spring
  Boot proves genuinely painful within a week, say so - the documented fallback is NestJS.

Before Phase 0, two environment fixes from docs/deploy.md:
  1. Docker is not reachable from WSL - `sudo usermod -aG docker $USER`, then
     `wsl --shutdown` from PowerShell.
  2. This repo should be moved off /mnt/c into ~/dev/ before any build runs; builds from
     the Windows filesystem are 5-20x slower.

Start by confirming you've read all four documents and summarising, in about ten lines,
what you're about to build and what Phase 0's exit criteria are. Then begin.
```
