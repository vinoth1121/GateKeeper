# GateKeeper — Documentation Index

| Doc | What it covers |
|---|---|
| [decisions.md](decisions.md) | Binding stack decisions: verified version table (with sources), SCG Server MVC on Boot 3.5.16 + Java 21 virtual threads, why not WebFlux, virtual-thread hygiene, Redis design (hash-tag keys, EVALSHA+NOSCRIPT, server TIME, milli-token math), leasing accuracy bound, Testcontainers 1.21.4, hosting decision (Render + Upstash free tiers) with alternatives, demo-vs-local reality |
| [threat-model.md](threat-model.md) | Threat-by-threat model with concrete mitigations: XFF spoofing, key brute force, CEL injection, Pub/Sub loss, Redis outage/failModes, lease overshoot, metrics cardinality, oversized requests, SSRF, hop-by-hop headers, secrets |
| [evidence/gates.md](evidence/gates.md) | Gates 0–12: requirements, status, evidence-file mapping, and the recording rules (append date+command+outcome per run; failed gates record BOTH attempts) |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Live demo on $0 tiers: Upstash free Redis (`rediss://`, EVAL + Pub/Sub), two Render free web services from this repo (gateway + mock-upstream), env vars, health check, smoke test, sleep-after-idle warning, admin-key rotation. Requires **your** cloud accounts — agents cannot sign up |
| [BENCHMARKS.md](BENCHMARKS.md) | Local benchmark methodology and results (k6 v2.3.0) — all performance claims come from local runs, never from the hosted demo |

## Reading order

1. `decisions.md` — what GateKeeper is built from and why.
2. `threat-model.md` — what it defends against and how.
3. `evidence/gates.md` — what must be proven, and the recorded proof of each run.
4. `DEPLOYMENT.md` — how to put the demo on the public internet for free.