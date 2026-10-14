# 🛡️ GateKeeper

> **High-Performance Distributed API Gateway & Token-Leasing Engine**  
> *Built on Java 21 Virtual Threads (Loom), Spring Cloud Gateway Server WebMvc, Redis, Google CEL, and Resilience4j.*

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/SCG%20Server%20WebMvc-4.3.5-green.svg)](https://spring.io/projects/spring-cloud-gateway)
[![Redis](https://img.shields.io/badge/Redis-7.4%20Alpine-red.svg)](https://redis.io/)
[![Audit Status](https://img.shields.io/badge/QA%20Audit-VERIFIED%20(14%2F14%20Gates)-blue.svg)](AUDIT.md)

---

## 🌟 Executive Summary

**GateKeeper** is an enterprise-grade, high-throughput edge gateway engineered for distributed microservice architectures and LLM application platforms. It combines zero-overhead **virtual threads (Project Loom)** with mathematically proven **distributed token leasing**, fail-open resilience, runtime-reconfigurable **Google CEL (Common Expression Language)** policies, and an **LLM Token-Per-Minute (TPM) streaming reverse proxy**.

### Why GateKeeper?

- ⚡ **Zero-Overhead Virtual Threads:** One virtual thread per request on the Servlet stack. Eliminates reactive complexity, callback hell, and carrier-thread pinning while handling massive concurrency.
- 🎯 **Distributed Token Leasing:** Nodes lease token batches from Redis locally, reducing central Redis round-trips by up to 80% while mathematically guaranteeing global rate limits.
- 🛡️ **Resilience-First Fail-Open:** Integrated Resilience4j circuit breakers and local lease backstops ensure zero 5xx errors and continuous availability during Redis partitions or total outages.
- 📜 **Dynamic Google CEL Hot-Reloading:** Expressive, compile-checked rate limit and routing rules defined in Common Expression Language and hot-reloaded safely at runtime with zero downtime.
- 🤖 **LLM Token-Per-Minute (TPM) Metering:** Server-Sent Events (SSE) streaming reverse proxy with real-time prompt/completion token reconciliation, mid-stream abort handling, and budget adjustments.
- 📊 **Real-Time Operator Console:** Built-in glassmorphism web console with live SVG token-bucket physical simulations, canvas sparklines, and interactive request routing diagrams.

---

## 🏛️ System Architecture

```mermaid
flowchart TD
    Client["Client / API Consumer"] -->|HTTP / SSE| GW["GateKeeper Gateway (Port 8080)<br/><i>Java 21 Virtual Threads</i>"]
    
    subgraph Core ["Gateway Core Pipeline"]
        Auth["Auth & Timing-Safe Hash Guard"]
        CEL["Google CEL Policy Evaluator"]
        Lease["Distributed Token Lease Engine"]
        CB["Resilience4j Circuit Breaker"]
        LLM["LLM Token Metering (SSE Proxy)"]
    end
    
    GW --> Auth --> CEL --> Lease
    Lease <-->|Lettuce Sync Multiplexed| Redis[("Central Redis 7.4<br/><i>Token Buckets & Sliding Windows</i>")]
    Lease -.->|Redis Unavailable (Fail-Open)| Backstop["Local Lease Cache Backstop"]
    
    Lease --> CB --> Upstream["Target Microservices / Mock Upstream (Port 8081)"]
    CEL --> LLM --> OpenAI["Upstream AI Provider"]
    
    subgraph Observability ["Telemetry & Control"]
        Prom["Prometheus (Port 9090)"]
        Graf["Grafana Dashboards (Port 3000)"]
        UI["Operator Console (/console/)"]
    end
    
    GW -.->|Scrapes /actuator/prometheus| Prom --> Graf
    GW -.->|Serves| UI
```

---

## 🚀 Quickstart Guide

### 1. Prerequisites
- [Docker Engine & Docker Compose](https://www.docker.com/) (v24+)
- Or for bare-metal build: JDK 21+ and Apache Maven 3.9+

### 2. Start the Stack (One Command)
Clone the repository and run:
```bash
docker compose -f infra/docker-compose.yml up -d --build
```

The stack spins up the following services:
| Service | URL / Port | Description |
|---|---|---|
| **GateKeeper Gateway** | `http://localhost:8080` | Primary reverse proxy, rate limiter, and CEL engine |
| **Operator Console** | `http://localhost:8080/console/` | Real-time monitoring and policy management UI |
| **Health Check** | `http://localhost:8080/actuator/health` | Gateway readiness and circuit breaker status |
| **Mock Upstream** | `http://localhost:8081` | Synthetic upstream service for testing |
| **Central Redis** | `localhost:6379` | Distributed rate limiting state storage |
| **Toxiproxy** | `localhost:8474` / `26379` | Chaos network simulation proxy |
| **Prometheus** | `http://localhost:9090` | Low-cardinality metrics collector |
| **Grafana** | `http://localhost:3000` | Pre-configured operational dashboards (`admin`/`admin`) |

### 3. Verify Stack Health
```bash
curl -s http://localhost:8080/actuator/health
# {"status":"UP","components":{"circuitBreakers":{"status":"UP"},"redis":{"status":"UP"}}}
```

---

## 🔑 Demo API Keys & Tiers

GateKeeper ships with pre-configured tier definitions for out-of-the-box testing:

| Tier | API Key Header (`X-API-Key`) | Capacity | Refill Rate | Burst Allowance |
|---|---|---|---|---|
| **Free** | `gk_demo_free_tenant` | 10 tokens | 5 tokens/sec | Drains quickly (ideal for testing 429) |
| **Pro** | `gk_demo_pro_tenant` | 100 tokens | 50 tokens/sec | Steady production workload |
| **Enterprise** | `gk_demo_ent_tenant` | 1,000 tokens | 500 tokens/sec | High-throughput dedicated tenant |
| **Sandbox** | `gk_demo_sandbox` | 20 tokens | 10 tokens/sec | Restricted to safe CEL playground |
| **Admin** | `dev-admin-key-change-me` | N/A | N/A | Authenticates management & policy endpoints |

---

## 💻 Live Verification & Examples

### 1. Normal Upstream Request (200 OK)
```bash
curl -i -H "X-API-Key: gk_demo_pro_tenant" http://localhost:8080/demo/hello
```
Returns `200 OK` with standard RFC rate limit headers:
```http
HTTP/1.1 200 OK
RateLimit-Limit: 100
RateLimit-Remaining: 99
RateLimit-Reset: 1
```

### 2. Burst Exhaustion & Throttling (429 Too Many Requests)
Drain the Free tier bucket by issuing rapid concurrent requests:
```bash
for i in {1..20}; do curl -s -o /dev/null -H "X-API-Key: gk_demo_free_tenant" http://localhost:8080/demo/hello; done
curl -i -H "X-API-Key: gk_demo_free_tenant" http://localhost:8080/demo/hello
```
Response:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 1
RateLimit-Remaining: 0
```

### 3. Google CEL Hot-Reload & Dry-Run
Validate a CEL rule dynamically without deploying code:
```bash
curl -X POST http://localhost:8080/api/policies/dry-run \
  -H "Content-Type: application/json" \
  -H "X-API-Key: gk_demo_pro_tenant" \
  -d '{
    "expression": "request.path.startsWith(\"/demo\") && request.method == \"GET\"",
    "limit": { "capacity": 50, "refillPerSec": 25 }
  }'
```

### 4. LLM Server-Sent Events (SSE) Streaming
Stream tokens from the integrated LLM proxy:
```bash
curl -N -X POST http://localhost:8080/llm/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer gk_demo_pro_tenant" \
  -d '{
    "model": "mock-gpt",
    "messages": [{"role": "user", "content": "Explain token bucket algorithm"}],
    "stream": true
  }'
```

---

## 🎨 Interactive Operator Console

Navigate to **`http://localhost:8080/console/`** to explore the built-in management interface:

1. **Live Token Bucket Visualizer:** SVG physical simulation displaying fluid token level, refill animation, and droplet drain effects in real-time.
2. **High-Performance Sparklines:** Zero-dependency canvas sparklines plotting latency trends and throughput without layout thrashing.
3. **Interactive Traffic Track Diagram:** End-to-end visual trace animating incoming requests through Auth, CEL Policy, Token Limiter, Circuit Breaker, and Upstream dispatch.
4. **Policy Playground:** Test and deploy Google CEL rules directly with syntax validation and instant feedback.

---

## 🧪 Testing & Verification Suites

### Run Full Java Test Suite
```bash
mvn clean verify
```
All unit, integration, and property tests pass on Java 21 with ByteBuddy dynamic agent loading enabled (`-XX:+EnableDynamicAgentLoading`).

### Run End-to-End Smoke Test Suite
```bash
bash scripts/smoke.sh
```
Executes all 14 integration assertions including health checks, console availability, tier rate-limiting, timing-safe authentication, CEL policy scoping, and SSE streaming.

### Run k6 Load & Concurrency Benchmarks
```powershell
powershell -File scripts\run_load.ps1
```
*Note: On Windows hosts, connection concurrency automatically defaults to `CONNECTIONS=2000` to prevent OS ephemeral socket exhaustion.*

---

## 📚 Documentation Directory

| Document | Purpose |
|---|---|
| [**`AUDIT.md`**](AUDIT.md) | Full independent QA/SRE verification report with command evidence and benchmark cross-checks |
| [**`docs/decisions.md`**](docs/decisions.md) | Architectural Decision Records (ADRs) and verified dependency versions |
| [**`docs/threat-model.md`**](docs/threat-model.md) | Threat vectors, timing-safe auth, X-Forwarded-For parsing, and CEL sandboxing |
| [**`docs/DEPLOYMENT.md`**](docs/DEPLOYMENT.md) | Guide for 100% free cloud deployment on Render and Upstash Redis |
| [**`docs/evidence/gates.md`**](docs/evidence/gates.md) | Verification logs and criteria for Gates 0 through 12 |
| [**`loadtest/README.md`**](loadtest/README.md) | k6 performance scripts, scenario designs, and tuning notes |

---

## ⚖️ License
Licensed under the Apache License, Version 2.0.
