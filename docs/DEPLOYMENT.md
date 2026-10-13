# GateKeeper — Deploying the Live Demo (Render + Upstash, $0)

**Who can do this:** you. Automated agents cannot create cloud accounts —
signups require a human's payment/identity verification. These steps deploy the
same gateway image that runs locally, to free tiers.

Free-tier reality check (see `docs/decisions.md` §7): Render free web services
sleep after 15 minutes without inbound traffic and take about a minute to wake;
Upstash free Redis caps commands (500K/month). The demo shows **functional**
behavior — all benchmark numbers come from local runs (`docs/BENCHMARKS.md`).

## 1. Upstash Redis (free tier)

1. Create a free Upstash account and database (any region close to your Render
   region; free tier: 250 MB data, 500K commands/month —
   https://upstash.com/pricing).
2. Copy the **TLS** connection string — it looks like
   `rediss://default:<password>@<host>:6379`. GateKeeper uses EVAL/EVALSHA and
   Pub/Sub, which run over the TCP/TLS connection, so use the `rediss://` URL,
   not the REST URL.
3. Keep it handy; it becomes `GATEKEEPER_REDIS_URL`.

## 2. Render — two free web services

Deploy from your GitHub repo. Render builds Docker images from the repo
(Hobby workspace supports Docker deploys).

**Service A — `gatekeeper-mock-upstream`** (deploy first):

- New → Web Service → connect the GitHub repo
- Dockerfile path: `mock-upstream/Dockerfile`
- Plan: **Free**
- No env vars needed. Note its URL: `https://<mock>.onrender.com`

**Service B — `gatekeeper-gateway`**:

- New → Web Service → same repo
- Dockerfile path: `gateway/Dockerfile`
- Plan: **Free** (512 MB RAM)
- Environment variables:

  | Variable | Value |
  |---|---|
  | `GATEKEEPER_ADMIN_KEY` | a long random string (generate one, e.g. with a password manager; never commit it) |
  | `GATEKEEPER_REDIS_URL` | the `rediss://...` URL from step 1 |
  | `GATEKEEPER_UPSTREAM_URL` | `http://<mock>.onrender.com` (service A's URL) |
  | `GATEKEEPER_TRUSTED_PROXIES` | Render fronts services with its own proxy; set this so XFF from Render's edge is honored — use the proxy IPs/CIDRs Render documents for your service (see Render's docs; the exact current ranges are not verified from this build environment — check Render's `X-Forwarded-For` documentation at deploy time) |
  | `JAVA_TOOL_OPTIONS` | `-Xmx384m -XX:MaxRAMPercentage=75` (sized for the 512 MB free plan) |
  | `GATEKEEPER_PROM_PUBLIC` | `false` (don't expose the metrics scrape publicly) |

## 3. Health verification

1. Visit `https://<gateway>.onrender.com/` — the console UI should load.
2. `GET https://<gateway>.onrender.com/actuator/health` → expect `{"status":"UP"}`
   (first request after idle will be slow — see below).
3. If the app fails to boot, check Render logs for Redis connection errors
   (wrong URL scheme, missing `rediss://`).

## 4. Smoke test from your machine

```bash
SMOKE_URL=https://<gateway>.onrender.com bash scripts/smoke.sh
```

All checks must print PASS (see `docs/evidence/gates.md` Gate 4). Append the
run to the Gate 4 evidence file per the recording rules.

## 5. Free-tier sleep warning

Render's docs (https://render.com/docs/free): free web services spin down
after 15 minutes without inbound traffic and take about a minute to spin back
up on the next request. So:

- The **first request after idle is slow** — that's the platform waking the
  container, not the gateway misbehaving.
- The console shows a **"waking up"** state rather than pretending the service
  is live.
- All **benchmarks ran locally** per `docs/BENCHMARKS.md`; hosted timings are
  not measurements of GateKeeper.

## 6. Admin-key rotation

If the admin key leaks or a collaborator leaves:

1. Generate a new long random key.
2. In Render, update `GATEKEEPER_ADMIN_KEY` on the gateway service.
3. Render redeploys automatically on env change; re-run the smoke test.
4. Do not put the old or new key in chat, tickets, or the repo. If it was ever
  committed, rotate immediately and treat it as public.