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

**What proves it.** `bench/`, run against a live stack through nginx, with clients landing on
whichever of three replicas `least_conn` gives them.

*Ordering mode* — both participants sending as fast as the socket allows:

```
50 conversations · 200 messages each · 100 concurrent sockets · 10,000 messages
nodes serving [api-1, api-2, api-3]
wall clock 16.7–53.2s over four runs · 188–598 msg/s end to end
no gaps in seq           ok
identical order observed ok
no duplicate deliveries  ok
nothing lost             ok
```

`--assert-multinode` fails the run unless at least two nodes actually served it, so a
misconfigured stack cannot pass by quietly being single-node. That flag is itself checked
against a one-node run, where it correctly fails.

*Chaos mode* — the same invariants, with `docker kill` on a replica 15 seconds in, while
traffic is still flowing:

```
50 conversations · 500 messages each · 100 sockets · 25,000 messages
api-2 killed at second 15, mid-send
22-37 socket reconnections, each retransmitting its unacked message, over five runs
no reordering across the kill   ok
no duplicate deliveries         ok
nothing lost                    ok
every send in the log once      ok
resume returns what was missed  ok
the kill actually disturbed it  ok
```

`docker kill`, not `docker stop`: a SIGKILL gives the process no chance to drain sockets or
commit consumer offsets. Correctness must not depend on a dying node behaving politely, and a
graceful stop would quietly be testing the easy case.

These are laptop figures — three JVMs plus Postgres, Redis and Redpanda in Docker on WSL, with
the load generator on the same machine — and the spread between runs shows it. The chaos-mode
throughput is lower still because that run deliberately *paces* its sends over 40 s so the kill
lands mid-flight; it is an offered rate, not a ceiling. None of these are the headline
benchmark; Phase 8 produces that on dedicated hardware with the load generator on a separate
instance, which is what makes a number credible.

---

## Status

Phase 6 of 8 — the guarantee holds across three replicas behind a load balancer, with no
sticky sessions, while a replica is killed mid-conversation. Interest matching, friends,
invites, blocks and the retention jobs are in. See `docs/plan.md` §5.

What remains is media upload, the single-file test client (Phase 7), and the benchmark on real
hardware (Phase 8).

| Phase | What it lands | State |
| ----- | ------------- | ----- |
| 0 | Spike: Spring Boot 3 on virtual threads, Flyway, Testcontainers | ✅ passing |
| 1 | Identity, domain, single-node chat | ✅ passing |
| 2 | The ordering guarantee + harness v1 | ✅ passing |
| 3 | Horizontal scale + harness v2 | ✅ passing |
| 4 | Chaos and correctness | ✅ passing |
| 5 | Presence, typing, receipts, unread, signup | ✅ passing |
| 6 | Matching, friends, invites, blocks | ✅ passing |
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

Three replicas behind nginx — the configuration the guarantee is actually claimed for:

```bash
docker compose -f compose.yaml -f compose.replicas.yaml --profile core up -d --build
curl -s localhost:8081/api/health
```

The invariant harness, against a running stack:

```bash
cd bench && ./mvnw clean package

# single node on the host
java -jar bench/target/shush-bench.jar --mode=ordering --conversations=50 --messages=200

# through nginx, across three replicas
java -jar bench/target/shush-bench.jar --mode=ordering --via=nginx \
     --conversations=50 --messages=200 --assert-multinode
# exits 0 only if all four invariants hold AND at least two nodes served the run

# and with a replica killed 15 seconds in, mid-send
java -jar bench/target/shush-bench.jar --mode=chaos --via=nginx \
     --kill-node=api-2 --at-second=15 --conversations=50 --messages=500
# exits 0 only if nothing was lost, duplicated or reordered across the failure
```

Chaos mode leaves the replica dead. Bring it back with
`docker compose -f compose.yaml -f compose.replicas.yaml --profile core up -d` before the next
run — the harness fails a run in which nothing reconnected, so a second run against an
already-dead node reports that rather than passing vacuously.

Metrics, if you want to watch it happen:

```bash
docker compose -f compose.yaml -f compose.replicas.yaml -f compose.observability.yaml \
  --profile core up -d
# Grafana on :3001, Prometheus on :9090, scraping each replica separately
```

The harness has its own tests (`InvariantsTest`, 17 of them) that feed each check a stream
violating it and assert it reports the violation — a harness that cannot fail would make a green
run meaningless. The two "did this run prove anything" guards are checked the same way:
`--assert-multinode` is run against a single node, and the chaos mode is run against an
already-dead replica. Both correctly fail rather than passing vacuously.

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

**Cross-node fanout.** A node subscribes to `user:{userId}` on Redis while it holds one of that
user's sockets, and drops the subscription when the last one closes — so a node listens only for
the users actually connected to it, and adding replicas does not multiply backplane traffic. The
writer publishes every message and every `delivered` ack to the recipients' channels, whichever
node is holding them.

**Ordered delivery.** Frames for one user are written to their sockets in the order they arrived
from the backplane, via a per-user chain of tasks on virtual threads. See the design decision
below — this is where the one genuinely subtle bug in the project lived.

**Backpressure.** Sessions are wrapped in a `ConcurrentWebSocketSessionDecorator` with a 1 MB
buffer and a 10 s send time limit. A client that cannot drain frames fast enough has its socket
terminated rather than buffered indefinitely; it reconnects and re-syncs from history, which is
cheap, whereas an unbounded buffer takes every other user on that node down with it.

**Heartbeat.** Every socket is pinged every 30 s, comfortably inside nginx's `proxy_read_timeout`,
because a quiet conversation is completely normal and must not lose its connection.

**Resume.** `GET /api/conversations/{id}/messages?after=<seq>` returns everything a returning
client missed, oldest first. It is the same index as scrollback, walked in the other direction,
and it is what makes a terminated or killed socket a non-event rather than a hole in the
conversation.

**Failure handling.** A killed node's sockets reconnect through the load balancer and land on a
different replica. Its Kafka partitions are reassigned to the survivors; a message that was
mid-write is redelivered and absorbed by the dedup constraint. A message the node had read from
the socket but not yet produced is genuinely lost — and the client, which never saw its `sent`
ack, retransmits it with the same `clientMsgId`.

**Graceful shutdown.** A *planned* stop drains sockets with `GOING_AWAY` first, so clients
reconnect immediately instead of waiting for a TCP timeout. That is a deploy nicety, not a
correctness mechanism, which is exactly why the harness kills rather than stops.

**Presence.** `presence:{userId}` in Redis, renewed every 15 s and expiring after 45 s. The TTL
*is* the design: presence asserted by a key that has to be renewed cannot outlive the process
renewing it, so a node dying is self-correcting and no cleanup job exists. Two missed renewals
before anyone is marked away, so a GC pause does not flicker someone offline. A friends list
resolves in one `MGET` rather than one call per friend.

**Offline is not leaving.** Losing connection publishes a `presence` frame and the conversation
stays open, because they might come back and anything sent meanwhile is waiting when they do.
Deliberately leaving publishes a `left` frame, ends the conversation, and schedules it for purge.
pre-plan.md §3 makes the two visibly different to the other person, so they are different frames.

**Typing indicators, throttled server-side.** `typing:{convId}:{userId}` with a 5 s TTL, gated by
a separate 3 s `SET NX` throttle key. Typing fires on keystrokes and is by far the
highest-frequency thing in the protocol — the client is asked to send at most one every three
seconds and the server enforces the same bound rather than trusting it, because a client with a
bug should not be able to melt the datastore. Sending a message clears the indicator.

**Unread counts and read receipts.** The counter is maintained by the writer, never a
`COUNT(*)` at read time — that is the query that collapses first as a conversation grows. The read
cursor only ever moves forward, so a second device reading more slowly cannot drag it backwards
and resurrect read messages; a read that does not move the cursor fires no receipt, so receipts
do not go off on every scroll.

**Saving an account.** `POST /api/auth/signup` attaches an email and password to the row the
caller already has. Nothing is created, copied or migrated — same id, same name, same
conversations — which is the whole mechanism behind "everything transfers, nothing resets". Login
hashes even when the email is unknown, so a missing account and a wrong password take the same
time. A chosen name is refused while anonymous, and refused if it is shaped like a generated one,
so nobody can mint a name indistinguishable from an assigned one.

**Matching.** A waiting user goes into a Redis sorted set and an Elasticsearch `waiting` index,
and the best-overlapping candidate is claimed by a Lua script that removes *both* ids only if
*both* are still present. That atomicity is the whole mechanism: two matchers can and do find the
same third person at the same instant, losing is normal and cheap — the loser stays in the pool
and retries on the next tick — but both winning would put one person in two conversations at once.
`ConcurrentClaimIT` asserts exactly one winner, a hundred times.

**The patience dial is honest.** Five or ten seconds means "try for a shared interest, then give
me anyone"; zero means "only somebody who actually shares one, however long that takes". A random
match is stored as `matched_on = null` and the `matched` frame says `randomMatch: true`, because
presenting a random match as an interest match is a small lie the user notices the moment they
start talking.

**Why Elasticsearch and not embeddings.** Matching is term overlap over a controlled vocabulary
— a lexical retrieval problem, which is what an inverted index is for, and it does the ranking,
the exclusions (blocked either way, already friends, yourself) and the tie-break on waiting time
in one query. Both sides draw from the same fixed tag list, so there is no semantic gap for
vectors to close, and approximate nearest-neighbour search would put an embedding model in the
request path to approximate a set intersection that can be computed exactly. That flips the
moment the input stops being a controlled vocabulary — free-text bios, cross-language matching,
"find users like this user" derived from behaviour — and that is where pgvector becomes the right
answer instead.

**Keeping someone.** A friend request works during the conversation *and* after it has ended,
which is the point: it is the one thing that still works once someone has left, so a good chat is
not lost because the other person closed their laptop first. Accepting keeps the conversation
forever; declining says nothing at all and cannot be re-asked, because the row stays and the
unique constraint refuses a second one. A conversation nobody asked to keep is deleted, along
with everything said in it.

**Five scheduled sweeps, each on one replica.** Every node runs the same timers, so a Redis lock
with a TTL decides which one actually runs. A contending tick is *skipped*, never queued: these
are periodic sweeps, so a missed run is corrected by the next one, whereas a queue of them piles
up faster than it drains the moment one run is slow. The lock is released only by its holder, so
a node that overran its lease cannot delete a lock another node is relying on.

### Not yet true

Media upload is still to come, and there is no UI beyond the harness (Phase 7). The benchmark
numbers above are laptop numbers, not the Phase 8 measurement.

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

### No sticky sessions, and why that is the interesting part

**Chosen.** nginx balances with `least_conn` and no affinity of any kind. Any replica serves any
user. Authentication is a stateless JWT, there is no server-side session, and a client that
reconnects will usually land on a different node — which is fine, because nothing about a user
lives on the node holding their socket except the socket itself.

**Rejected: `ip_hash` or a sticky cookie.** This is the reflex answer to "user A is on node 1 and
user B is on node 3", and it does make the immediate problem disappear. It also means a node
dying takes its users' sessions with it rather than letting them reconnect anywhere; that users
behind one corporate NAT all pin to one replica; that scaling out does not rebalance existing
connections; and that the cross-node path is never exercised in normal operation, so it rots
undetected. Stickiness converts a routing problem into an availability and load-distribution
problem.

**Rejected: a shared connection registry in Redis** (user → node), with nodes forwarding directly
to each other. It works, but it adds a lookup on the delivery path, a consistency problem when
the registry disagrees with reality after a crash, and node-to-node addressing — which is the
thing that makes replicas non-interchangeable. Pub/sub already solves routing without anyone
needing to know where anyone is.

**Why round-robin was rejected too.** WebSocket connections are long-lived and disconnect
unevenly. Round-robin balances at assignment time, so over a long run the distribution drifts;
`least_conn` tracks what is actually open.

**The cost, honestly.** Every message crosses Redis even when both participants are on the same
node — an avoidable hop in the common case. That is deliberate: a same-node shortcut is an easy
optimisation and a bad one, because the local path is the one that always works in development,
so cross-node delivery would break silently and only surface under a load balancer. One path
means a bug is a bug everywhere. It also makes Redis a hard dependency for delivery, though not
for durability — messages already committed are never lost by a Redis failure, only undelivered
until the client refetches history.

### One row per friendship, and the ordering bug that hid in it

`friendships` stores each pair once, with the ids in a fixed order so the primary key can enforce
that — storing both directions would make "are these two friends?" a question with two answers
that eventually disagree. A check constraint asserts the order.

The first implementation ordered the pair with `UUID.compareTo`, and roughly half of all friend
requests failed with a constraint violation, at random. **Java compares a UUID's two halves as
signed longs; Postgres compares the sixteen bytes unsigned.** For any pair differing in the top
bit the two disagree, so a pair ordered in Java and then checked by the database is rejected —
non-deterministically, because the ids are random. The database owns the constraint, so the
ordering has to match the database. `FriendshipTest` pins it with a pair chosen to differ in
exactly that bit.

It is worth naming because it is the shape of bug that survives review: the code reads correctly,
the test that would catch it passes half the time, and the failure looks like flakiness.

### Per-user ordered delivery — a bug the harness caught

The first three-replica run failed on exactly one invariant: two participants in one conversation
out of fifty disagreed about the order of messages 165 and 166. Sequence numbers were dense and
correct, and the database was correct — only the order in which frames reached the two sockets
differed.

The cause was that Spring's `RedisMessageListenerContainer` dispatches each received message to a
task executor, so two frames arriving on one channel raced each other to the socket. Nothing
about it was visible on a single node, because fanout there had been a synchronous write from the
writer thread. It needed three replicas, a load balancer and 10,000 messages to appear once.

The fix is two-part: the container now delivers on its receiving thread, preserving per-channel
receive order, and the listener immediately hands off to a **chain of tasks per user** rather than
a shared pool — so ordering is guaranteed within a user and nothing is promised between users,
which is exactly what the guarantee actually claims. A striped thread pool would have been
simpler, but one slow socket would then stall every user sharing its stripe; virtual threads make
a chain per connected user cost almost nothing, so there is no reason to accept that.

`CrossNodeFanoutIT.aBurstAcrossNodesArrivesInOneOrderOnBothSockets` is the regression test, and
it runs in `./mvnw verify` against two real application contexts sharing one Postgres, Redis and
broker.

## Architecture

Full diagram lands in Phase 3, once cross-node fanout exists. The shape it is being built
towards is in `docs/plan.md` §1.

## Known Limitations

Written down because naming where your own design breaks is the point of the exercise.

**A message read from a socket but not yet produced is lost if that node dies.** The window is
sub-millisecond and the client recovers by retransmitting an unacked `clientMsgId`, but the
recovery is the *client's* responsibility — a client that does not track unacked sends will
silently drop that message. The `sent` ack is the durability boundary and nothing before it is
a promise.

**Ordering is per conversation and nothing more.** There is no ordering between conversations,
and none between a message and, say, a friend request. That is deliberate — a global order would
mean a single partition and no horizontal scale at all — but it means "Alice's message arrived
before Bob's" is only meaningful within one conversation.

**Increasing the partition count breaks the guarantee for existing conversations.** Adding
partitions re-hashes keys, so a conversation can move to a different partition while messages
for it are still in flight on the old one, and two consumers can then be writing it at once. It
is set to 12 up front for that reason. Changing it safely needs a drain-and-migrate, which is
not implemented.

**Redis is a hard dependency for delivery, though not for durability.** If Redis is unavailable,
messages are still committed and sequenced correctly and history serves them, but nothing is
pushed to a live socket. There is no fallback path, deliberately — a same-node shortcut would
reintroduce the two-path problem described above.

**Consumer failure detection is tuned to 10 s, not to zero.** Between a node dying and the group
noticing, its partitions are stranded: those conversations accept sends (the log takes them) but
nothing is written or delivered until the rebalance completes. Lowering it further trades
against evicting healthy consumers during a GC pause.

**The unread counter is maintained, not derived.** It is incremented by the writer rather than
counted at read time, which is the entire point, but it can drift: marking a conversation read
sets the counter to zero even when the cursor was moved to a point in the middle, so messages
after that point stop being counted. That is the tradeoff `plan.md` §3.5 chose, and it is why a
nightly reconciliation job is specified — that job is Phase 6 and is not implemented yet.

**Presence is per user, not per device.** Closing one of three tabs does not mark you offline,
which is correct, but presence also cannot tell anyone *which* device you are on, and a user
whose last node dies stays "online" for up to the remaining TTL.

**A partition stalls rather than dropping a record it cannot write.** Spring Kafka's default
error handler retries ten times and then skips the record — silent message loss under database
pressure. The writer instead retries indefinitely, so a persistent failure pauses the
conversations on that partition until it clears. That is the deliberate trade for a system whose
claim is that nothing is lost, but it does mean one bad dependency can stop one twelfth of
conversations rather than degrading all of them evenly.

**Backpressure terminates rather than degrades.** A client 1 MB behind has its socket closed.
That is correct for a chat service — reconnect and re-sync is cheap — but it means a very slow
network can produce a reconnect loop, and there is no exponential backoff on the server side to
discourage it.

**Matching ranking is BM25 over tag terms, not a tuned relevance model.** More shared tags
scores higher and ties break on who has waited longest, which is defensible and simple. It does
not weight rare interests above common ones, so matching two people on "music" counts the same
as matching them on "volunteering" — a real scoring function would not treat those equally.

**A blocked user is excluded from matching but not from an existing conversation.** Blocking
removes the friendship and stops future matches, but the two are not forcibly removed from a
conversation they are already in; the blocker has to leave it.

**Reports are recorded, not acted on.** There is no moderation queue and nothing reads the table.
That is honest for a project running with test users, and it is exactly the piece of work that
would have to exist *before* opening anonymous image-sharing to real strangers — a separate
undertaking, not a feature toggle.

**No authentication on the Redis or Kafka connections.** Both are reachable only on the compose
network and bound to loopback on the host. That is appropriate for a local stack and would not
be for a deployment.

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
- **The server sends a `hello` frame on connect** carrying `userId` and `nodeId`. `nodeId` is
  diagnostic only — nothing addresses a node — but without it neither an operator nor the harness
  could tell a genuinely multi-node run from a single-node one.
- **Backplane subscription registration is serialised behind a lock**, and the subscription
  connection is warmed up at startup with a listener on an unused channel. Two sockets opening in
  the same millisecond otherwise raced Spring's lazy subscription setup, and the second user was
  silently never subscribed.
- **nginx listens on 8081, not 8080**, so the replica stack and a host-run `spring-boot:run`
  can be up at the same time without clashing.
- **`/api/health` is liveness and `/api/health/ready` is readiness.** Phase 0 had a single
  endpoint delegating to the full Actuator aggregate; under chaos-run load the container probe
  timed out on dependency checks and declared a healthy node dead. Liveness now performs no I/O.
  Readiness checks Postgres and Redis but deliberately **not** Kafka: a broker blip must not mark
  every replica unready and take the service down, since history, auth and existing sockets keep
  working and a failed produce is already reported as `produce_failed`.
- **Listener concurrency is 4 per replica in the three-replica stack** (12 partitions ÷ 3), not
  the single-node default of 12. Leaving it at 12 gives the group 36 members for 12 partitions —
  24 idle, and every rebalance after a kill has to shuffle all 36.
- **Chaos mode paces its sends over 40 s by default** (`--send-seconds`). Firing everything as
  fast as possible finishes in about two seconds, so a kill scheduled for later lands after the
  last send and tests nothing.
- **The harness treats a message as sent only when the server acks it**, and retransmits unacked
  ones with the same `clientMsgId`. Counting a successful socket write as a send would report
  loss that is really the client's failure to retry.
- **`friend_requests.status` gained a `declined` value** (migration `V5`). `plan.md` §2.2 allowed
  only `pending`/`accepted`/`expired`, which conflates two different answers — nobody replied, and
  someone said no — and the purge rule needs to tell them apart.
- **A declined request means the conversation is not kept** and goes back on the purge clock.
  `pre-plan.md` says an accepted request keeps it and no request deletes it, but does not say
  which a decline is; not keeping it is the reading consistent with "strangers stay strangers".
- **Elasticsearch is in the `search` and `full` compose profiles, not `core`.** It is the
  heaviest thing in the stack serving the least critical requirement, and the correctness harness
  does not need it — an ordering or chaos run should not have to pay a gigabyte for it. The
  readiness probe excludes it for the same reason it excludes Kafka.
- **The `waiting` index is written with `refresh=true`.** Two people arriving together must be
  able to find each other, and Elasticsearch's default one-second refresh is an eternity inside a
  five-second patience window. It costs write throughput on an index that holds only the people
  currently waiting.
- **The matching tick runs on every replica without a lock**, unlike the five retention jobs. It
  only ticks for the users that replica is holding, and the claim is already atomic — a lock here
  would serialise all matching through one replica to prevent a race that is already prevented.
- **Sign-out deletes the device token rather than revoking the JWT.** The JWT stays valid until
  it expires (24 h); revoking it would need a denylist and a lookup on every request, which is a
  real cost for a threat this project does not have. Worth naming rather than pretending.
- **`POST /api/auth/signup` is under the permit-all `/api/auth/**` prefix but requires a valid
  JWT**, because it attaches to an existing account rather than creating one. The bearer filter
  still runs on permitted paths, so an unauthenticated call gets a 401 from the controller.
- **Replica containers have an explicit 768 MB memory limit and the JVM takes 60% of it.**
  `MaxRAMPercentage` is a percentage of the *container's* limit, and with no limit set that is
  the whole host — so three replicas each sized themselves for the entire machine, the box went
  into swap, and the broker began stalling its reactor for hundreds of milliseconds. The
  symptom looked exactly like message loss. The remaining 40% is metaspace, code cache, thread
  stacks and direct buffers, not slack.
- **`spring.config.import` reads the gitignored `.env`** so `./mvnw spring-boot:run` works
  without exporting variables by hand. `.env` remains gitignored; `.env.example` stays blank.
