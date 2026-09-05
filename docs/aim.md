# Shush — Aim & Technical Rationale

> Seed document for `plan.md`. Defines **why** the project exists, **what it must
> prove**, **which technical decisions are locked**, and **why each was taken**.
> It does not contain the implementation plan.

---

## 0. Read this first: the domain is deliberately unoriginal

Chat is not an original idea, and that is a feature, not a bug.

The interviewer already has the domain model in their head. They know what a message
is, what a conversation is, what "delivered" means. That means **100% of their
attention goes to the architecture instead of to understanding what the app does.**

A novel domain is a liability in a resume project: you spend the first five minutes of
every conversation explaining the product, and the reviewer is still building a mental
model when they should already be interrogating your consistency guarantees.

Corollaries that follow from this and should not be re-litigated later:

- **Do not** worry about competition (WhatsApp, Discord, Telegram). Not competing.
- **Do not** worry about the cold-start problem. There are no real users by design.
- **Do not** worry about originality. Originality is scored at zero here.
- **Do not** build a product. Build an engineering artifact that survives interrogation.

---

## 1. Aim

**The sole aim of Shush is to be a resume and interview artifact.**

Monetisation, user growth, and public launch are explicitly **out of scope** and are
not deferred goals to design around. If a decision trades product value for
demonstrable engineering depth, take the engineering depth every time.

### 1.1 The success criterion

The project succeeds if it produces **one resume bullet that survives a 30-minute
technical interrogation**, plus a README that makes a hiring manager want to have that
interrogation.

Target bullet (write the code backwards from this):

> Built a horizontally-scaled real-time messaging service in Java 21 / Spring Boot
> guaranteeing strict per-conversation message ordering and exactly-once delivery
> semantics across N stateless nodes; sustained 10k concurrent WebSocket connections
> and 5k msg/s at p99 < 80ms end-to-end, verified by a load harness that asserts zero
> reordering and zero duplicates under induced node failure.

Note the shape of that sentence. It contains:

1. **A guarantee** — ordering, exactly-once. Not a feature list.
2. **A number** — 10k connections, 5k msg/s, p99 latency.
3. **A proof** — an automated harness that asserts the invariant, not a claim.

Every scoping decision in `plan.md` should be judged against whether it strengthens the
guarantee, the number, or the proof. If it does none of the three, cut it.

### 1.2 Why this project, for this candidate

The strongest line on the current resume is:

> *"Eliminated message interleaving in the production WhatsApp chatbot using an
> XState.js state machine and key-partitioned Redpanda topics, pinning each
> conversation to a single consumer for strict in-order processing."*

That is the most senior-sounding claim available and the one an interviewer is most
likely to dig into. Shush is the **proof artifact for exactly that claim**, at larger
scale and with the reasoning made explicit. It secondarily backs the Elasticsearch and
ClickHouse/analytics bullets.

This alignment is the reason to build chat rather than anything else.

### 1.3 Non-goals (hard boundaries)

| Not doing | Why |
|---|---|
| Public launch to real strangers | Anonymous stranger-chat with open media upload creates IT Rules 2021 intermediary obligations and a CSAM liability surface. Non-negotiable: seeded synthetic users only. |
| "Mental health app" positioning | Buys nothing on a resume; adds duty-of-care exposure. It is a messaging service. |
| Group chats | Fanout complexity without new *kinds* of problems. Possible v2. |
| Video / voice | Entirely different domain (WebRTC, TURN). Zero overlap with the target bullet. |
| Push notifications | Vendor integration work, not distributed-systems work. |
| A polished frontend | A deliberately plain single-page test client is correct and signals confidence. |
| Payments, mobile apps, bots | Product features. Bots are the v2 cold-start answer, irrelevant now. |

---

## 2. Requirements for a resume-grade project

These follow from "the aim is a resume artifact." They differ from product requirements
and should drive `plan.md` more strongly than any feature list.

**R1 — It must state a falsifiable guarantee.**
"A chat app" is not a claim. "Strict per-conversation total ordering under concurrent
producers across N nodes" is a claim. The guarantee must be written down formally in the
README, including the exact conditions under which it holds.

**R2 — It must be measured, not asserted.**
Every number on the resume must come from a reproducible benchmark a reviewer can re-run
with one command. No hand-waved throughput claims.

**R3 — The invariants must be tested adversarially.**
The load harness does not just measure throughput. It **asserts correctness under
stress**: zero reordering, zero duplicates, zero lost messages — including while a node
is killed mid-run. This is the single highest-signal component of the project.

**R4 — It must actually run horizontally scaled.**
Minimum 3 app replicas behind a load balancer, in the default `docker compose up` path.
A single-node chat server proves nothing about fanout; the cross-node case is the point.

**R5 — Design decisions must be documented with rejected alternatives.**
For each significant choice: what was chosen, what was rejected, why. This section is
what separates a project that gets you hired from one that gets a polite nod. §4 of this
document is the source material.

**R6 — Known limitations must be documented.**
Where the design breaks, at what scale, what would be needed to fix it. Naming your own
design's failure modes is a strong seniority signal; pretending they don't exist is the
opposite.

**R7 — It must be trivially runnable.**
`git clone && docker compose up` brings up the entire stack. A reviewer who cannot run it
in one command will not run it. This requirement is why MinIO and self-hosted
infrastructure are chosen over managed cloud services throughout §4 and §5.

**R8 — It must be finished.**
A 60%-complete project is worth zero. Scope is the servant of completion. When in doubt,
cut scope — never cut R1, R2, R3, or R8.

### 2.1 The deliverable is the README

The code is necessary but is not read first. The README must contain:

1. **The guarantee** — stated formally, with preconditions.
2. **Architecture diagram** — nodes, backplane, storage, data flow.
3. **The hard problems** (§3) — each with how it was solved.
4. **Design Decisions** — chosen vs. rejected, with reasoning. *The section that hires you.*
5. **Benchmarks** — methodology, hardware, results, how to reproduce.
6. **Correctness harness** — which invariants are asserted and how.
7. **Known Limitations** — where it breaks and why.
8. **One command to run it.**

### 2.2 Feature set (the minimum that forces the hard problems)

Features exist only to create the engineering problems in §3. Nothing is included because
a chat app "should" have it.

- Auth: JWT issue/verify; users; interest tags
- 1:1 conversations; message send/receive over WebSocket
- Message persistence + cursor-based history pagination
- Offline queue: messages delivered on reconnect, in order, exactly once
- Presence (online/offline/last-seen) and typing indicators, TTL-based
- Read receipts and unread counts
- Media: presigned S3 upload, size and MIME allowlist
- Interest-based matching between users
- Health/readiness endpoints; Prometheus metrics; structured logs

---

## 3. The hard problems this project forces you to solve

The technical core, and the reason chat was chosen. Each must be explicitly addressed in
the README and defensible in an interview.

### 3.1 Per-conversation message ordering under concurrent producers

Two participants send simultaneously from different nodes. Every observer — both clients,
the database, the history endpoint after a reload — must see the same total order for
that conversation. Requires a partition key derived from conversation ID so one
conversation is always handled by one consumer, plus a monotonic per-conversation
sequence. Directly mirrors the key-partitioned Redpanda work on the resume.

### 3.2 At-least-once delivery with idempotent dedup

Networks drop, clients reconnect, consumers redeliver. Delivery is at-least-once at the
transport layer; the *effective* semantics must be exactly-once. Requires client-supplied
idempotency keys on send, a server-side dedup window, and dedup on the receive path so a
reconnect never surfaces a duplicate to the user.

### 3.3 WebSocket fanout across horizontally scaled instances

User A holds a socket on node 1; user B on node 3. Node 1 has no direct route to B's
socket. Requires a backplane — Redis pub/sub or Kafka — plus a connection registry mapping
user → node. This is why R4 (3+ replicas) is mandatory: the problem does not exist on a
single node, and it is the first thing a good interviewer probes.

### 3.4 Presence and typing indicators with TTL, at scale

Presence is high-write, low-value, and must expire without explicit cleanup — a crashed
node must not leave users online forever. Requires TTL-keyed Redis state, heartbeat
refresh, and explicit thought about write amplification: typing indicators fire on every
keystroke and will destroy the datastore if handled naively. Debounce/throttle strategy
must be documented.

### 3.5 Media upload via presigned S3 URLs

Bytes must never flow through the API process. The client requests a presigned PUT, the
API returns a short-TTL signed URL scoped to a single key, the client uploads directly to
object storage, then confirms. Requires size/MIME allowlisting, key namespacing, and a
reconciliation path for uploads that are never confirmed.

### 3.6 Unread counts and read receipts without hammering the database

The naive implementation issues a `COUNT(*)` per conversation per page load and collapses
immediately. Requires maintained counters, an atomic increment/reset path, a read cursor
per user per conversation, and a documented reconciliation strategy for counter drift. A
classic denormalisation-vs-consistency tradeoff — say so explicitly.

### 3.7 Interest-based matching

Match users on overlapping interest tags with a documented, defensible scoring function.
Must state the ranking logic, not just "it returns some users."

---

## 4. Technical decisions and why they were taken

### How to read this section

Each decision has two subsections:

- **The actual reason** — the honest, complete reasoning, *including* career strategy.
  This is for you. It contains things that are true but that you would never volunteer in
  an interview, such as hiring-pool size or which existing resume bullet a choice
  reinforces.
- **The interview answer** — the same decision justified purely from this system's
  technical requirements. This is what you say out loud.

**These are not two different sets of facts.** The interview answer is a *narrower
emphasis* on the same true reasoning, not a fabrication. Every technical claim in the
interview answer must be one you actually acted on and can defend under follow-up
questioning. An invented justification collapses the moment an interviewer probes it, and
that failure costs more than the decision ever gained.

---

### 4.1 Java 21 + Spring Boot 3 — over Node/NestJS and over Go

**Decision: Java 21 (LTS), Spring Boot 3.x, Spring MVC on virtual threads.**

#### The actual reason

1. **Target-company hiring reality dominates everything else.** Amazon is Java-first at
   scale; Oracle owns Java and is overwhelmingly Java; Flipkart is Java/Spring Boot;
   Google runs a very large Java codebase. Across Indian product companies, the Java +
   Spring hiring pool is several times the size of the Go pool. Since the aim is
   maximising the number of doors that open, this is decisive.

2. **The NestJS background makes Spring cheap to learn.** NestJS is explicitly modelled on
   Spring — decorators, a DI container, modules, providers, guards, interceptors.
   `@Injectable` → `@Service`, `@Controller` → `@RestController`, Nest guards → Spring
   filters. The mental model transfers close to 1:1, so the usual "Spring is a heavy ramp"
   objection is much weaker here than it looks. This directly protects R8.

3. **Virtual threads close most of the gap to Go.** Project Loom delivers goroutine-style
   ergonomics on the JVM. What remains is memory footprint per connection, not programming
   model.

4. **Differentiation against the median candidate.** Most 1-YOE Java candidates know Java 8
   and Spring MVC. Java 21 virtual threads, ZGC, and a reasoned Loom-vs-reactive comparison
   read as materially more current.

5. **Acknowledged cost:** Go is arguably the better *pure technical fit* for this workload
   — cheaper goroutines, lower per-connection memory, faster startup, single static binary,
   no GC tuning. Some technical elegance is being traded for market reach. That trade is
   correct given the aim, but it is a real trade and should be named honestly.

#### The interview answer

*Why not Node/NestJS, given that's your production stack?*

**1. Concurrency model.** The workload is ~10k concurrent WebSocket connections, each
mostly idle but each holding state. Node's event loop handles I/O concurrency well, but
any CPU-bound work on the message path — serialisation, fanout, encryption, media metadata
— blocks the single loop and stalls every connection on that process. Scaling past one core
requires `cluster` mode: N processes, N heaps, no shared memory. Java 21 virtual threads
give per-connection blocking-style code cheap enough to allocate one per connection,
**while retaining true multi-core parallelism in a single process.**

**2. Shared in-process state.** The connection registry and presence cache are hot,
node-local, and read constantly. Under Node cluster mode each worker has an isolated heap,
so all of it must be externalised to Redis — a network hop on the critical path for state
that is inherently local. On the JVM one process holds every connection on that node in a
shared `ConcurrentHashMap` across all cores, leaving Redis for genuinely cross-node state.
That is the correct boundary.

**3. Real concurrency primitives.** Ordering guarantees and dedup windows need
`ConcurrentHashMap`, `StampedLock`, `LongAdder`, the atomics, and a specified memory model
(the JMM) to reason about visibility. Node has essentially none of this because
single-threaded code never needed it.

**4. Ecosystem depth for exactly this infrastructure.** Kafka's reference client *is* the
Java client; Spring Kafka, Netty, Lettuce and the official Elasticsearch Java client are
all first-class. The Node equivalents are wrappers over less battle-tested
reimplementations. For a system whose central claim is about Kafka partitioning semantics,
running on the reference implementation is defensible on its own.

**5. Backpressure for slow consumers.** A client on a poor mobile network cannot drain
messages as fast as they arrive; without backpressure the server buffers unboundedly and
OOMs. Netty — which Spring's WebSocket stack sits on — exposes write-buffer high/low
watermarks and `isWritable()`. Node's `ws` offers very little here.

**6. Observability, because the deliverable is a benchmark.** JFR, async-profiler, heap
dumps and JMH are mature and free. R2 demands defensible numbers; the JVM's profiling
story makes those numbers trustworthy.

*Why not Go, then?*

Answer honestly — this is the follow-up that separates a real decision from a rationalised
one: virtual threads gave Loom-style concurrency ergonomics, while the JVM gave a more
mature Kafka/Elasticsearch/observability ecosystem and stronger domain typing. Ecosystem
depth mattered more than per-connection memory at this scale. **Then name Go's real
advantages out loud** — cheaper goroutines, lower memory per connection, faster startup,
single-binary deploys, no GC pauses to reason about. Knowing the downsides of your own
choice is the seniority signal; pretending the choice was free is the opposite.

*Why Spring MVC on virtual threads rather than WebFlux?*

Reactive was the old answer to high-concurrency I/O on the JVM, and it costs a great deal
in debuggability — stack traces stop being meaningful, and every library in the chain must
be non-blocking or the benefit evaporates. Loom gets the same scalability with ordinary
blocking-style code that is readable and profileable. Choosing MVC + virtual threads
deliberately, and being able to explain when WebFlux would still win (true streaming
backpressure end-to-end), is a stronger position than defaulting to reactive.

---

### 4.2 Redpanda — over Apache Kafka

**Decision: Redpanda, self-hosted in Docker, driven by the standard Apache Kafka Java
client via Spring Kafka.**

#### The actual reason

1. **Cost and hosting.** No free managed Kafka exists any more — Upstash discontinued
   their Kafka product in 2024; Confluent Cloud and Aiven are time-limited trials, then
   paid. Redpanda self-hosted is free forever, and self-hosting is *required* anyway by R7.

2. **The deployment target is ARM64.** The EC2 box is a `t4g.small` (Graviton2, aarch64).
   Confluent's `cp-kafka` images have patchy arm64 support; Redpanda officially supports
   Graviton. This is close to a forcing function, not a preference.

3. **Memory budget.** Redpanda runs in ~1 GB and tunes lower (`--smp 1 --memory 1G
   --overprovisioned`). Kafka wants ~1.5 GB of JVM heap before it is comfortable. On a
   memory-constrained benchmark box, and inside a reviewer's `docker compose up`, that
   difference is real.

4. **Setup time is a direct threat to R8.** No ZooKeeper, no KRaft quorum configuration, no
   separate coordination process. A single binary that starts in seconds. Every hour not
   spent fighting broker configuration is an hour spent on §3.

5. **Consistency with existing experience.** The resume already says "Kafka/Redpanda" and
   the production ordering work was done on Redpanda. Using it here means the project and
   the work history tell the same story under questioning.

6. **R7 again.** Fewer containers, faster cold start, fewer moving parts for a reviewer who
   is deciding in the first ninety seconds whether to keep going.

#### The interview answer

**Start by removing the false premise: this is not a choice about Kafka semantics.**
Redpanda implements the Kafka wire protocol. The project uses the actual Apache Kafka Java
client through Spring Kafka — unchanged. Partition assignment, key-based partitioning,
consumer groups, offset management, rebalancing and delivery semantics are identical,
because they are defined by the protocol, not the implementation. The system's central
claim is about *partitioning semantics*, which live at the protocol level.

**Then give the operational reason:** equivalent semantics at a lower operational
footprint. Redpanda is a single C++ binary using a thread-per-core (Seastar) architecture
with no JVM and therefore no GC tuning, no ZooKeeper, and no separate KRaft quorum to
operate. Raft-based replication is built in rather than layered on ISR. For a system that
must come up reliably from `docker compose up` on someone else's machine, that reduction
in moving parts is worth more than anything Kafka's larger ecosystem would have added
here.

**Be ready for "would you choose it in production?"** — the honest answer is: it depends on
what surrounds it. Kafka has a far larger ecosystem (Kafka Connect, an enormous connector
catalogue, Streams, more mature tiered storage) and vastly more engineers who already know
how to operate it, which is often the deciding factor on a real team. Redpanda wins on
operational simplicity and tail latency. For a self-contained service with no connector
requirements, Redpanda; for a company-wide event backbone with heavy Connect usage and an
existing Kafka operations team, Kafka.

**Know the actual differences,** because "it's the same thing" invites a follow-up: no JVM
and no GC pause behaviour; thread-per-core rather than a thread pool; direct I/O instead of
relying on the OS page cache; Raft for replication rather than ISR. Being able to name
these shows the choice was informed rather than convenient.

---

### 4.3 Elasticsearch — over pgvector

**Decision: Elasticsearch 8, single node, for §3.7 interest matching.**

#### The actual reason

1. **It reinforces an existing resume bullet** — *"Elasticsearch-backed RAG retrieval."*
   Same logic that selected chat over other project ideas: strengthen a claim already being
   made rather than adding an orphan technology.

2. **Higher hiring relevance at the target companies.** Elasticsearch is standard backend
   infrastructure at Amazon/Flipkart-scale. As a distinct distributed system it opens real
   interview surface — shard and replica strategy, the refresh-interval-versus-freshness
   tradeoff, mappings and analyzers — where pgvector is a Postgres extension with a much
   thinner discussion.

3. **pgvector needs an embedding model in the path.** That is either an external API
   (latency, cost, rate limits, a network dependency in the request path) or a local model
   (more memory on an already-constrained box). A moving part added to serve the *least
   important* requirement in §3.

4. **The data is lexical, not semantic.** Interest tags come from a controlled vocabulary.
   Reaching for embeddings would be inventing a need, and a knowledgeable interviewer would
   notice.

5. **Named risk:** Elasticsearch is the heaviest component in the stack (~1.5 GB) serving
   the weakest requirement. Run it single-node with `-Xms512m -Xmx512m`, security disabled.
   **If memory or time gets tight, this is the first thing to cut** — a Postgres GIN index
   on a tags array handles overlap matching well, and documenting *that* tradeoff in the
   README recovers most of the value.

#### The interview answer

**Frame it as a data-shape argument, which is what it is.** The matching problem is term
overlap over a controlled tag vocabulary — a lexical retrieval problem. An inverted index
with BM25 relevance scoring is the natural data structure for term overlap with ranking.
Approximate nearest-neighbour search over embeddings solves a *different* problem:
semantic similarity between things that do not share terms. That problem does not exist
here, because both sides of the match draw from the same fixed tag set.

**Add the cost argument.** Vector search would require an embedding model in the request
path — added latency, an external dependency, and an ongoing re-embedding and model-drift
concern — in order to approximate a set-overlap computation that can be done exactly.

**Add what else was needed.** Matching also needs filtering and aggregation — facet by
tag, exclude already-matched users, boost by recent activity. Elasticsearch provides that
natively alongside the ranking, in one query.

**Name the boundary, unprompted.** This is the part that lands: *"pgvector becomes the
right answer the moment the input stops being a controlled vocabulary."* Free-text bios
instead of tags, cross-language matching, or "find users like this user" derived from
behaviour rather than declared interests — all of those are genuine semantic-similarity
problems where an inverted index performs badly and embeddings win. Knowing exactly where
your own choice stops being correct is the strongest form of this answer.

---

### 4.4 Maven — over Gradle

**Decision: Maven.**

#### What these tools are (for reference)

Java has no npm. Maven and Gradle *are* npm + `package.json` + build scripts combined into
one tool: transitive dependency resolution, compilation, test execution, and packaging into
a runnable JAR.

- **Maven** — `pom.xml`, XML, declarative, convention over configuration. Rigid, stable,
  slower builds.
- **Gradle** — Groovy/Kotlin DSL, imperative, flexible, faster via daemon, incremental
  compilation and build cache. More power and more ways to break it.

#### The actual reason

1. **It matches the target companies.** Amazon, Oracle and Flipkart job descriptions
   specify Maven far more often than Gradle. (Gradle's dominance is in Android, which is
   not the target.)

2. **It protects R8.** Spring is being learned simultaneously. A build tool that does
   exactly one predictable thing removes an entire category of distraction. Gradle build
   files are programs, and debugging a build program while learning a framework is a poor
   trade.

3. **Lowest friction path.** Spring Initializr defaults to Maven, and the overwhelming
   majority of Spring documentation and Stack Overflow answers are Maven-first. Every
   minute of searching is cheaper.

4. **Gradle's advantages do not apply.** Its wins are build speed on large multi-module
   codebases and Android tooling. This is one module that compiles in seconds.

#### The interview answer

Frame it as scope-appropriate tooling rather than preference. This is a single-module
service with a fixed dependency set; the build is not a problem that needs solving.
Maven's rigidity is the feature — a declarative POM means no build logic to maintain,
debug, or hand to a reviewer, and reproducibility comes by construction rather than by
discipline. Dependency versions are pinned exactly, with no dynamic version ranges, which
is basic supply-chain hygiene.

Gradle earns its complexity on large multi-module builds where incremental compilation and
the build cache save real wall-clock time, and on Android where it is the only option.
Neither condition holds here, so adopting it would be paying complexity for nothing.

---

## 5. The stack, and where it runs

### 5.1 Locked stack

| Layer | Choice | Hosting | Cost |
|---|---|---|---|
| Language | Java 21 LTS (Temurin, aarch64) | — | free |
| Framework | Spring Boot 3.x — MVC + virtual threads | — | free |
| Build | Maven | — | free |
| Database | PostgreSQL 16 | Docker (Neon only for optional demo) | free |
| Cache / presence / backplane | Redis 7 | Docker | free |
| Event log | Redpanda (Kafka protocol) | Docker | free |
| Search / matching | Elasticsearch 8, single node | Docker | free |
| Object storage | MinIO local · S3 `ap-southeast-1` deployed | Docker / AWS | ~free |
| Metrics | Prometheus + Grafana, via Actuator/Micrometer | Docker | free |
| Ingress | nginx on the existing `edge` network | Docker | free |
| Testing | JUnit 5 + Testcontainers | — | free |
| Load harness | k6 or Gatling, on a separate instance | EC2 spot, ~1 hr | ~$0.05 |

**Total spend: roughly $2–3, one-time, for the benchmark run.** Everything is free at this
scale, and self-hosting is *better* than managed here because R7 requires a reviewer to
reproduce the whole system with one command.

Two supporting notes:

- **MinIO exists to satisfy R7.** A reviewer has no AWS account, no IAM keys, no bucket.
  MinIO gives them working S3-compatible storage with zero setup. Application code talks
  to the S3 API through the AWS SDK with the endpoint as configuration: MinIO locally,
  real S3 when deployed. That the storage layer is S3-API-abstracted is itself worth
  documenting under R5.
- **Testcontainers is close to mandatory for R3.** Asserting message ordering against a
  mocked broker proves nothing, because partitioning is precisely the behaviour a mock
  removes. Integration tests must run against real Postgres, Redis and Redpanda.

### 5.2 Three environments — do not conflate them

| Environment | Where | Purpose |
|---|---|---|
| **Dev + reviewer** | Laptop, `docker compose up` | R7 — the primary deliverable |
| **Benchmark** | EC2, temporarily resized | R2/R3 — the numbers and invariant proofs |
| **Live demo** | Optional; skip, or trimmed single-replica | Low value; a dead link is worse than no link |

### 5.3 Deployment target reality

The box is **`t4g.small` · ARM64 Graviton2 · 2 vCPU · 2 GB RAM · ap-southeast-1a ·
Ubuntu 24.04**, currently running nginx, certbot and the `linkedin_profile_api` stack,
with roughly **1 GB free and already swapping**.

The full stack needs ~5 GB (3 app replicas ~1.2 GB, Elasticsearch ~1.5 GB, Redpanda
~1 GB, Prometheus+Grafana ~450 MB, MinIO ~200 MB, Redis ~100 MB, nginx+OS+dockerd
~600 MB). **The box cannot host it, and does not need to** — R7 targets the reviewer's
machine, not a hosted demo.

**For the benchmark run**, resize rather than provision anything new. AWS allows changing
the instance type of a *stopped* instance for free, preserving the EBS volume, nginx
config and certificates:

1. Stop → resize `t4g.small` → `t4g.2xlarge` (8 vCPU, 32 GB) → start
2. Run benchmarks; capture results and Grafana screenshots
3. Stop → resize back to `t4g.small` → start

`t4g.2xlarge` is ~$0.27/hr in ap-southeast-1; three hours is under a dollar. Reclaim disk
first — `docker builder prune` frees ~3.35 GB of the 19 GB volume — and consider bumping
EBS to 30 GB (~$1/month, gp3).

**Benchmark methodology:** run the load generator on a *separate* instance in the same AZ.
Sharing CPU with the server means measuring the load generator, not the system. State this
in the README — it is exactly what makes a benchmark credible.

### 5.4 ARM64 — the build trap

Development is on Windows/WSL (x86_64); the box is aarch64. **Images built locally will
not run on the box** (`exec format error`). Either build on the EC2 instance, or:

```bash
docker buildx build --platform linux/arm64 -t shush-api:latest .
```

Everything else in the stack ships multi-arch: Postgres, Redis, Redpanda, Elasticsearch 8,
MinIO, Prometheus, Grafana, Temurin 21. The one casualty is Confluent's `cp-kafka` — see
§4.2.

### 5.5 nginx — required changes to the existing `syamdev-infra` config

Three issues in the current config will affect this project specifically:

1. **`worker_connections 1024` is a hard blocker for the headline number.** Each proxied
   WebSocket consumes two file descriptors — one downstream, one upstream — capping
   concurrency near **512 connections**, about 5% of the 10k target. Needs
   `worker_connections 20480` plus a matching `ulimit -n`. This fails silently and looks
   like an unexplained plateau in the load test.

2. **`proxy_read_timeout 60s` will kill idle WebSockets.** The shared `snippets/proxy.conf`
   sets 60s, so any conversation quiet for a minute loses its socket. Needs
   `proxy_read_timeout 3600s` on the shush location *and* an application-level ping/pong
   under 60s. Do both — the heartbeat is correct behaviour regardless; the timeout is the
   safety net.

3. **Load balancing needs `least_conn`, not round-robin.** Round-robin distributes evenly
   at assignment time, but WebSocket connections are long-lived and disconnect unevenly, so
   the distribution drifts badly over a long run.

Also worth noting, in both the config and the README:

- **No `ip_hash` or sticky sessions are required** — and say so explicitly under R5. That is
  precisely what the §3.3 backplane buys: any node can serve any user because cross-node
  routing goes through Redis/Kafka rather than connection affinity. Most implementations
  reach for sticky sessions here; explaining why this one does not is a strong design
  decision.
- `Connection "upgrade"` is hardcoded in the shared snippet, which sends a spurious header
  on plain HTTP requests. The canonical fix is a `map $http_upgrade $connection_upgrade`
  block. It works as-is, but the snippet is shared with other apps.
- `client_max_body_size 20m` is irrelevant to shush, which confirms the §3.5 presigned-URL
  design: media bytes bypass nginx and the API entirely.

---

## 6. Guardrails for `plan.md`

1. Every feature must trace to a hard problem in §3. Features that force no interesting
   problem get cut, regardless of how normal they would be in a real chat app.
2. The correctness harness (R3) is built **early**, not last. It is the highest-value
   artifact and the easiest to skip under time pressure.
3. Multi-replica deployment is the default from day one, not a final step. Retrofitting
   cross-node fanout is far more expensive than designing for it.
4. The README is written incrementally alongside the code. Design decisions are recorded
   when made, while the rejected alternatives are still fresh. §4 is the source material —
   the README gets the *interview answer* form; this file keeps both.
5. Week one is a **Spring spike**, before any feature work: a Spring Boot service with two
   REST endpoints, one WebSocket echo endpoint, and one Testcontainers integration test
   running on arm64. If that is comfortable within a week, proceed. If it is genuinely
   painful, fall back to NestJS and lose nothing structural — every design decision in §3
   is language-independent. Shipping in NestJS beats abandoning in Java, every time.
6. Timebox ruthlessly. Target 3–4 weeks of evenings. Scope is negotiable; R1, R2, R3 and R8
   are not.
