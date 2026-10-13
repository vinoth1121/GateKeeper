# GateKeeper — Architecture & Tooling Decisions

This file records the binding decisions behind GateKeeper's stack, runtime, Redis
design, and hosting. Every version claim below was verified from the cited
source on 2026-10-10 (Maven Central `maven-metadata.xml`, the Spring/Spring
Cloud release metadata, GitHub API, and `docker` on the build machine) unless
explicitly marked **not verified from build environment**.

## 1. Verified versions

| Component | Coordinate / Tool | Exact version | Source (verified 2026-10-10) |
|---|---|---|---|
| JDK | Eclipse Temurin 21 (LTS) | 21.0.12 locally; newest Temurin 21 release is `jdk-21.0.12.1+1` (2026-08-19) | Adoptium/GitHub API + local `java -version` |
| Spring Boot | `org.springframework.boot:spring-boot-starter-parent` | **3.5.16** (newest 3.5.x GA; latest overall stable is 4.1.1, 4.2.0-M2 milestone on Central) | Maven Central metadata. Boot 4 migration deliberately deferred — see §2 |
| Spring Cloud train | `org.springframework.cloud:spring-cloud-dependencies` | **2025.0.3** (latest stable train is 2025.1.3, which requires Boot 4.0.8) | Train POM `spring-boot.version` property + per-tag docs; train 2025.0.x ↔ Boot 3.5.x |
| Gateway | `spring-cloud-starter-gateway-mvc` | **4.3.5** (2025.0.x line). The 5.x/Boot-4 line is renamed `spring-cloud-starter-gateway-server-webmvc` (5.0.3 latest stable) | 4.3.5 jar metadata; servlet-stack gateway; route property prefix defined in `gateway/src/main/resources/application.yml` — see that file, don't guess |
| Redis | `redis:7.4-alpine` | **7.4.11** running in Docker (current stable line is 8.x, latest 8.10.2; newest 7.2.x is 7.2.16) | `docker` on build machine |
| Redis client | `io.lettuce:lettuce-core` | **7.8.0.RELEASE** — raw client; deliberately NO `spring-data-redis`; one shared multiplexed connection + sync API on virtual threads | Maven Central |
| Resilience | `resilience4j-bom` | **2.4.0** | Maven Central |
| Test containers | `testcontainers-bom` | **1.21.4** (includes `toxiproxy` module; Testcontainers 2.0.5 stable exists but has NO toxiproxy module) — rationale in §6 | Maven Central |
| Local cache | `com.github.ben-manes.caffeine:caffeine` | **3.3.0** | Maven Central |
| Token counting | `com.knuddels:jtokkit` | **1.1.0** | Maven Central |
| CEL | `dev.cel:cel` | **0.14.0** (repo is now `cel-expr/cel-java`) | Maven Central |
| Micrometer | Boot-managed | Version managed by Boot 3.5.16 (latest standalone stable 1.17.1 exists) — kept on the Boot-managed version for actuator compatibility | Spring Boot 3.5.16 BOM |
| Code style | `com.diffplug.spotless` | **3.10.4** | Maven Central |
| Load testing | k6 | **v2.3.0** (2026-09-21) | k6 releases |
| Build | Apache Maven | **3.9.16** | `mvn -version` |
| Containers | Docker Engine / Desktop / compose | **29.8.0 / 4.92.0 / v5.5.1** | `docker version` |
| Build machine | Windows 11 Home 10.0.26200, Intel Core 5 210H (8C/12T), 16 GB RAM; Node 24.16.0, Python 3.12.10, git 2.54 | — | Local machine |

## 2. Gateway stack: SCG Server MVC on Boot 3.5.16 + Java 21 virtual threads

The gateway runs **Spring Cloud Gateway Server MVC** (`spring-cloud-starter-gateway-mvc`
4.3.5) on **Spring Boot 3.5.16 / Java 21** with
`spring.threads.virtual.enabled=true` — the servlet stack, one virtual thread per
request. Boot 4 / SCG 5.x is explicitly out of scope for v1; migration is
future work. Route definitions live in
`gateway/src/main/resources/application.yml`.

### Why not WebFlux

1. A Netty event-loop is a small fixed thread pool — virtual threads bring it no benefit; there is nothing to unpin.
2. On JDK 21, blocking Redis I/O inside `synchronized` blocks is a pinned-thread hazard: carrier threads freeze and the whole event loop stalls.
3. The blocking Lettuce sync API is trivially correct on virtual threads — no reactive chains, no `flatMap` psychology.
4. Servlet + thread-per-request is simpler to program and debug: readable stack traces, normal breakpoints, plain exceptions.
5. Spring maintains SCG Server MVC for exactly this profile — servlet + virtual threads — so we are on a supported, mature line rather than fighting the framework.

## 3. Virtual-thread hygiene rules

- **No `synchronized` around blocking I/O.** Blocking sections use
  `ReentrantLock` (or atomics/volatile) only, so virtual threads never pin
  carrier threads while waiting on Redis or the upstream.
- **One shared multiplexed Lettuce connection.** All requests share a single
  connection; the sync API is only safe here because callers run on virtual
  threads and Lettuce multiplexes commands with completion futures underneath.
  No per-request connections, no connection pools.
- **Load runs record pinning.** Every load-test campaign runs with
  `-Djdk.tracePinnedThreads=full` and the results (including any reported
  pinned-thread events) are appended to the evidence files per the rules in
  `docs/evidence/gates.md`.

## 4. Redis design

- **Hash-tag keys.** All keys use `gk:{tenantId}:...` so a future migration to
  Redis Cluster hashes every tenant's keys to the same slot — Lua scripts keep
  working unchanged.
- **EVALSHA + NOSCRIPT fallback.** Scripts are loaded once at startup
  (`SCRIPT LOAD`), executed via `EVALSHA`; on a `NOSCRIPT` error the gateway
  re-`LOAD`s and retries — resilient to server-side script flushes/restarts.
- **`redis TIME` only.** All window/refill math uses Redis server time, never
  gateway clock time — multi-instance correctness does not depend on clock sync.
- **Integer milli-token math.** Rates and refill are computed in integer
  milli-tokens (token count x 1000) to avoid floating-point drift between nodes.
- **TTL ~2x refill period.** Idle buckets expire after roughly twice the refill
  window so refill arithmetic is never applied to a bucket whose elapsed time
  predates a restart of the script's assumptions, and memory stays bounded.

## 5. Leasing accuracy bound

Each gateway node leases tokens from the global pool. The bound on accuracy:

- **The global limit may overshoot by at most `nodes x leaseSize` tokens** —
  that is the documented worst case and an accepted property of the design.
- **Strict policies bypass leasing** — decisions that must be exact
  (e.g. hard "deny over N" rules) go straight to the global bucket.
- **Tokens lost to a crashed node = acceptable under-use** — a node that dies
  with unspent lease tokens simply over-reserves until the lease expires; the
  system errs on the side of throttling slightly early, never throttling late.

## 6. Testcontainers 1.21.4 rationale

Testcontainers 2.0.5 is stable but ships **no toxiproxy module**; fault-injection
gates (latency, partition, connection reset against Redis) rely on
`org.testcontainers:toxiproxy`, which exists in the 1.21.x line. GateKeeper
therefore pins **testcontainers-bom 1.21.4** and defers the 2.x jump until
toxiproxy support (or an equivalent replacement) is available there.

## 7. Hosting decision: Render free web service + Upstash free Redis

**Decision:** the public demo runs the real gateway as a **Render free web
service**, backed by **Upstash free-tier Redis**. Both tiers are $0.

Constraints and design responses:

- **Render free web service: 512 MB RAM, sleeps when idle.** Per
  https://render.com/docs/free (fetched 2026-10-10 via `curl.exe`):
  > "Render spins down a Free web service that goes 15 minutes without
  > receiving any inbound traffic. This includes both HTTP requests and
  > WebSocket messages from existing connections."
  >
  > It "spins back up whenever it next receives an HTTP request or new
  > WebSocket connection. This process takes about one minute" and Render
  > shows a loading page to browsers in the meantime.

  Design responses: JVM sized to the plan (`-Xmx384m`, see
  `docs/DEPLOYMENT.md`), lazy init of non-critical beans, the console UI shows
  a "waking up" state, and `docs/README.md` notes that all heavy benchmarks ran
  locally (see `docs/BENCHMARKS.md`). First request after idle is slow by
  platform design, not a gateway defect.

- **Upstash free Redis.** Per https://upstash.com/pricing (fetched 2026-10-10
  via `curl.exe`): "Free tier includes 250MB data size and 500K commands per
  month" (bandwidth 10 GB/month on the free tier; Pay-As-You-Go beyond is
  $0.20 per 100K commands). Upstash's Redis command reference
  (https://docs.upstash.com/redis — sitemap and command pages fetched 2026-10-10)
  documents **EVAL/EVALSHA scripting** and **Pub/Sub over the TCP/TLS
  connection** ("TCP examples use the TLS REDIS_URL from the Upstash console").
  Both are hard requirements (Lua rate limiting; config-change invalidation
  broadcasts). **Local leasing keeps Redis calls low** — one EVALSHA per
  request amortized by leased tokens, one subscribe connection, rare PINGs —
  which is exactly the traffic profile that fits command-capped free tiers.

- **Render Key Value was rejected** as the primary store: free tier is 25 MB /
  50 connections and — critically — the required EVAL/Pub/Sub surface is
  Upstash's, not a managed-Key-Value feature set. (Facts from Render's pricing
  page, fetched 2026-10-10 by the research pass.)

### Alternatives considered

| Option | Verdict | Why |
|---|---|---|
| **Render free web service + Upstash free Redis** (chosen) | **Chosen** | $0; supports Docker deploys (Hobby workspace: 25 services, 5 GB bandwidth/month, global regions + CDN); Upstash free tier has EVAL + Pub/Sub over TCP and a command cap our lease-amortized design fits |
| Oracle Cloud Always Free VM | Viable alternative | Always-on real Redis possible (Always Free includes AMD and Arm Ampere A1 compute; $300/30-day trial; unlimited-time Always Free services; one account per person; accounts idle 30+ days may be suspended) — but signup **requires credit/debit card identity verification**, an always-on VM needs patching/ops, and the sleep-free property is not worth that overhead for a demo |
| Fly.io / Railway free tiers | Rejected | Not evaluated from the build environment on 2026-10-10; equivalent constraints (sleep, RAM caps) apply and Upstash+Render covers the demo's needs |
| Self-host on the build machine | Rejected for the demo | Windows 11 Home behind residential NAT is not a hosting target; local runs remain the benchmark environment |

**EXPLICIT constraint:** automated agents cannot create cloud accounts
(signups require a human's payment card and identity verification — true for
both Render and Upstash, and card-gated for Oracle). **A live deployment
therefore requires the user's own accounts**; the copy-paste steps are in
`docs/DEPLOYMENT.md`.

## 8. Public demo vs local reality

The hosted demo is the **real gateway code** (same Docker image as local runs),
but it demonstrates **functional** behavior, not throughput:

- What the demo shows: tenant tiers, 401s for bad keys, 429s with `Retry-After`,
  rate-limit response headers, the console UI, policy decisions, CEL sandbox
  behavior.
- What the demo does **not** show: benchmark numbers. Free-tier instances share
  CPU, have 512 MB RAM, and sleep after 15 idle minutes; any timing readout
  there reflects the platform, not GateKeeper. All performance claims come
  from local runs recorded in `docs/BENCHMARKS.md` (k6 v2.3.0, Docker-local
  Redis) — treat hosted latencies as "waking-up theater," nothing more.