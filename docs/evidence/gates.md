# GateKeeper — Evidence Gates (0–12)

## Recording rules

1. **Every gate run is appended, never overwritten.** Each run adds a dated
   entry to the gate's evidence file containing: **date, exact command
   executed, and outcome** (PASS/FAIL plus the key numbers the gate requires).
2. **Failed gates record BOTH attempts.** If a gate fails and is re-run after a
   fix, the failed attempt AND the subsequent passing attempt are both kept in
   the file — evidence of the fix, not just the final green.
3. Evidence files live in `docs/evidence/` as `*.txt` (written by the
   evidence agent; this file links to them but does not contain them).
   One file per gate, named `gate-NN-*.txt`.
4. A gate is only marked PASSED in the table below when its evidence file shows
   a dated passing run with the recorded command. Until then it is PENDING.
5. Commands must be recorded verbatim (including env vars such as
   `SMOKE_URL=...` and JVM flags such as `-Djdk.tracePinnedThreads=full`), so
   any run is reproducible from the file alone.

## Gates

| Gate | Requirement | Status | Evidence files | Notes |
|---|---|---|---|---|
| 0 | Build green: `mvn -B verify` passes locally and in CI on Java 21 / Boot 3.5.16 | PASSED | `docs/evidence/gate-00-build.txt` | Includes Spotless check; record exact commit SHA |
| 1 | Unit tests pass: Lua bucket/window logic, milli-token math, TTL math, key hashing | PASSED | `docs/evidence/gate-01-unit.txt` | Pure-Java ports of the Lua math are compared against script results |
| 2 | Redis integration tests pass via Testcontainers (real Redis, token bucket + sliding window, EVALSHA+NOSCRIPT fallback path exercised) | PASSED | `docs/evidence/gate-02-redis.txt` | Testcontainers 1.21.4; NOSCRIPT forced via SCRIPT FLUSH mid-test |
| 3 | Fault injection via toxiproxy: injected latency and connection reset are survived; Resilience4j breaker opens and half-opens correctly | PENDING | `docs/evidence/gate-03-toxiproxy.txt` | `scripts/toxiproxy_setup.ps1` / `.sh`; record breaker state transitions |
| 4 | Smoke test: `scripts/smoke.sh` all-PASS against the compose stack | PENDING | `docs/evidence/gate-04-smoke.txt` | Record `SMOKE_URL`, full PASS/FAIL list |
| 5 | Rate-limit correctness: burst admitted up to capacity, refill verified over time, 429 + `Retry-After` correct, tenants isolated | PENDING | `docs/evidence/gate-05-correctness.txt` | Per-tenant isolation across the demo tiers |
| 6 | Leasing conservation: total admitted across nodes <= limit + nodes x leaseSize; strict policies never overshoot | PASSED | `docs/evidence/gate-06-leasing.txt` | Multi-node simulation; asserts the documented bound |
| 7 | Auth: valid key passes; unknown/malformed/revoked keys all return uniform 401 (same body/timing profile) | PASSED | `docs/evidence/gate-07-auth.txt` | Constant-time compare; SHA-256 at rest verified |
| 8 | Trusted-proxy XFF: rightmost-untrusted algorithm correct; untrusted peers' XFF ignored | PASSED | `docs/evidence/gate-08-xff.txt` | Matrix of direct-peer x XFF-chain cases |
| 9 | CEL policy safety: publish-time compile errors carry positions; sandbox forces `tenant.id=='demo-sandbox'` and bounds numeric limits; size/cost limits enforced | PASSED | `docs/evidence/gate-09-cel.txt` | Rejected-policy transcripts required (both attempts if any fail first) |
| 10 | Load test baseline: k6 steady/ramp/burst/connections/overhead scripts run locally with results recorded (with `-Djdk.tracePinnedThreads=full`) | PENDING | `docs/evidence/gate-10-load.txt` | k6 v2.3.0; record any pinned-thread reports verbatim |
| 11 | Observability: Prometheus scrapes gateway metrics; Grafana dashboards render; label set limited to tier/rule/decision/reason | PENDING | `docs/evidence/gate-11-metrics.txt` | `infra/prometheus`, `infra/grafana`; cardinality check included |
| 12 | Docs complete: decisions/threat-model/deployment/READMEs present; every version claim carries its source+date; live deployment steps verified end-to-end by the user | PENDING | `docs/evidence/gate-12-docs.txt` | `docs/DEPLOYMENT.md` walk-through by the user; screenshots in `results/` |