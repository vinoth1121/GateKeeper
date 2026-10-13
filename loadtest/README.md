# GateKeeper load tests (k6 v2.3.0)

All scripts assume the full stack is running (`docker compose -f
infra/docker-compose.yml up -d` plus `scripts/toxiproxy_setup.ps1 setup`, or
`scripts/run_load.ps1` which does everything). Run them from the repository
root so that `handleSummary` writes into `results/`. Override endpoints with
`BASE_URL` / `DIRECT_URL` (defaults: `http://localhost:8080` /
`http://localhost:8081`); demo keys live in `loadtest/lib.js`.

| Script | Command | What it measures |
|---|---|---|
| steady.js | `k6 run loadtest/steady.js` | Sustained 5000 rps for 60s (constant-arrival-rate, 3000 preallocated VUs) against `/demo/hello` with the PRO key. Steady-state latency (p99 < 1s threshold) and error rate (`http_req_failed` < 0.1). Writes `results/steady.json`. |
| ramp.js | `k6 run loadtest/ramp.js` | Ramping-arrival-rate 500 -> 5000 -> 20000 rps with the PRO key. Finds the point where the limiter starts shedding (429s allowed up to 25%). Writes `results/ramp.json`. |
| connections.js | `CONNECTIONS=2000 k6 run loadtest/connections.js` | Constant-VU run (default `CONNECTIONS=50000`, `DURATION=60s`): each VU makes a short keep-alive request to `/demo/hello` then sleeps 10s, so VU connection pools hold that many resident connections. Measures the gateway under massive connection concurrency. Threshold on `http_req_failed`. Writes `results/connections.json`. |
| overhead.js | `k6 run loadtest/overhead.js` | Two scenarios at 300 rps for 30s each: `direct` (`DIRECT_URL/hello`, mock upstream) and `via` (`BASE_URL/demo/hello`). `handleSummary` reports the p50/p95/p99 delta = the gateway's added overhead, written to `results/overhead.json`. |
| burst.js | `k6 run loadtest/burst.js` | 50 immediate requests with the FREE key; asserts at least one 429 and that 429s carry `RateLimit-Remaining` + `Retry-After` headers; prints a per-burst summary. Writes `results/burst.json`. |

## OS constraints for connections.js

k6 cannot hold raw sockets open between iterations, so connection residency is
modeled with one pooled keep-alive connection per VU (see the comments in
`connections.js`). Before running at high `CONNECTIONS`:

- **Windows**: default ephemeral port range is 49152-65535 (~16k ports) per
  destination tuple — 50k connections to one endpoint require port-range
  tuning or multiple source IPs; TIME_WAIT from earlier runs shrinks the
  range further; 50k VUs is also heavy on its own (start with
  `CONNECTIONS=2000`).
- **Linux**: raise `ulimit -n` (soft default is often 1024) and check
  `net.ipv4.ip_local_port_range`, `net.core.somaxconn`, and conntrack limits.

## overhead.js and the compose network

The compose stack does not publish the mock upstream on the host
(`expose 8081` only), so the `direct` scenario cannot reach
`localhost:8081` from the host. `scripts/run_load.ps1` therefore runs this
script with the `grafana/k6:2.3.0` image inside the compose network
(`BASE_URL=http://gateway:8080`, `DIRECT_URL=http://mock-upstream:8081`).
When running the stack natively (no Docker), point `DIRECT_URL` at your
locally running mock upstream.

After a full run, generate the report: `python scripts/generate_benchmarks.py`.