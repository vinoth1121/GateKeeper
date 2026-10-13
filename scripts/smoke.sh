#!/usr/bin/env bash
# GateKeeper smoke test (bash). Every check prints PASS/FAIL; exit code is 0
# only when all checks pass.
#
# Usage:
#   SMOKE_URL=http://localhost:8080 ./scripts/smoke.sh
#
# Prereq: stack up via infra/docker-compose.yml + scripts/toxiproxy_setup.ps1 setup
set -u

SMOKE_URL="${SMOKE_URL:-http://localhost:8080}"
PRO_KEY="gk_demo_pro_tenant"
FREE_KEY="gk_demo_free_tenant"
SANDBOX_KEY="gk_demo_sandbox"
ADMIN_KEY="${GATEKEEPER_ADMIN_KEY:-dev-admin-key-change-me}"

PASS=0
FAIL=0

report() {
  if [ "$2" -eq 0 ]; then
    echo "PASS: $1"
    PASS=$((PASS + 1))
  else
    echo "FAIL: $1"
    FAIL=$((FAIL + 1))
  fi
}

http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# --- 1. health -------------------------------------------------------------
code="$(http_code "$SMOKE_URL/actuator/health")"
body="$(curl -s "$SMOKE_URL/actuator/health")"
ok=1
if [ "$code" = "200" ] && printf '%s' "$body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then ok=0; fi
report "health endpoint returns 200 with status UP" "$ok"

# --- 2. console ------------------------------------------------------------
code="$(http_code "$SMOKE_URL/console/")"
report "GET /console/ returns 200" $([ "$code" = "200" ]; echo $?)

# --- 3. free tier: 200 first, then 429 burst with rate-limit headers -------
code="$(http_code -H "X-API-Key: $FREE_KEY" "$SMOKE_URL/demo/hello")"
report "free demo key gets 200 before burst" $([ "$code" = "200" ]; echo $?)

saw429=1; ratelimit_hdr=1; retryafter_hdr=1
urls=$(for _ in $(seq 1 50); do echo "$SMOKE_URL/demo/hello"; done)
if curl -s -Z -V >/dev/null 2>&1; then
  hdrs="$(curl -s -Z -D - -o /dev/null -H "X-API-Key: $FREE_KEY" $urls)"
  case "$hdrs" in
    *" 429"*)
      saw429=0
      printf '%s' "$hdrs" | tr -d '\r' | grep -qi 'ratelimit-' && ratelimit_hdr=0
      printf '%s' "$hdrs" | tr -d '\r' | grep -qi 'retry-after:' && retryafter_hdr=0
      ;;
  esac
else
  for _ in $(seq 1 50); do
    hdrs="$(curl -s -D - -o /dev/null -H "X-API-Key: $FREE_KEY" "$SMOKE_URL/demo/hello")"
    status="$(printf '%s' "$hdrs" | head -n 1 | tr -d '\r')"
    case "$status" in
      *" 429"*)
        saw429=0
        printf '%s' "$hdrs" | tr -d '\r' | grep -qi 'ratelimit-' && ratelimit_hdr=0
        printf '%s' "$hdrs" | tr -d '\r' | grep -qi 'retry-after:' && retryafter_hdr=0
        ;;
    esac
  done
fi
report "burst of 50 free-tier requests produces at least one 429" "$saw429"
report "429 responses carry RateLimit-* headers" "$ratelimit_hdr"
report "429 responses carry Retry-After" "$retryafter_hdr"

# --- 4. invalid key --------------------------------------------------------
code="$(http_code -H "X-API-Key: gk_definitely_not_a_valid_key" "$SMOKE_URL/demo/hello")"
report "invalid API key returns 401" $([ "$code" = "401" ]; echo $?)

# --- 5. unknown route ------------------------------------------------------
code="$(http_code -H "X-API-Key: $PRO_KEY" "$SMOKE_URL/demo/this-route-does-not-exist")"
report "unknown route returns 404" $([ "$code" = "404" ]; echo $?)

# --- 6. dry-run with invalid CEL ------------------------------------------
dry_run() {
  curl -s -X POST -H 'Content-Type: application/json' -H "X-API-Key: $PRO_KEY" \
    -d '{"expression":"1 ++"}' "$SMOKE_URL/api/policies/dry-run"
}
code="$(http_code -X POST -H 'Content-Type: application/json' -H "X-API-Key: $PRO_KEY" \
  -d '{"expression":"1 ++"}' "$SMOKE_URL/api/policies/dry-run")"
body="$(dry_run)"
ok=1
case "$code" in
  4*|200)
    if printf '%s' "$body" | grep -q '{' \
       && printf '%s' "$body" | grep -qi 'error\|invalid\|"valid"[[:space:]]*:[[:space:]]*false'; then
      ok=0
    fi
    ;;
esac
report "POST /api/policies/dry-run with invalid CEL returns a JSON error" "$ok"

# --- 7. sandbox publish increments policy version --------------------------
current_version() {
  curl -s -H "X-API-Key: $PRO_KEY" "$SMOKE_URL/api/policies/current" \
    | grep -o '"version"[[:space:]]*:[[:space:]]*[0-9][0-9]*' \
    | grep -o '[0-9][0-9]*$' | head -n 1
}
before="$(current_version)"
publish_code="$(http_code -X POST -H 'Content-Type: application/json' -H "X-API-Key: $SANDBOX_KEY" \
  -d '{"expression":"1 + 1 == 2"}' "$SMOKE_URL/api/sandbox/policies")"
if [ "$publish_code" != "200" ] && [ "$publish_code" != "201" ] && [ "$publish_code" != "204" ]; then
  publish_code="$(http_code -X POST -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
    -d '{"expression":"1 + 1 == 2"}' "$SMOKE_URL/api/sandbox/policies")"
fi
after="$(current_version)"
ok=1
if [ -n "$before" ] && [ -n "$after" ]; then
  case "$publish_code" in 200|201|204)
    [ "$after" -gt "$before" ] && ok=0
    ;;
  esac
fi
report "POST /api/sandbox/policies increments policy version ($before -> $after)" "$ok"

# --- 8. admin endpoint without key -----------------------------------------
code="$(http_code -X POST -H 'Content-Type: application/json' \
  -d '{"expression":"1 + 1 == 2"}' "$SMOKE_URL/api/admin/policies")"
report "admin endpoint without X-Admin-Key returns 401" $([ "$code" = "401" ]; echo $?)

# --- 9. prometheus not public ------------------------------------------------
code="$(http_code "$SMOKE_URL/actuator/prometheus")"
ok=1
case "$code" in 401|403|404) ok=0 ;; esac
report "GET /actuator/prometheus is not publicly exposed (got $code)" "$ok"

# --- 10. LLM streaming -------------------------------------------------------
stream="$(curl -s -N -X POST -H 'Content-Type: application/json' -H "X-API-Key: $PRO_KEY" \
  -d '{"model":"gpt-demo","messages":[{"role":"user","content":"Say hello in one word."}],"stream":true}' \
  "$SMOKE_URL/llm/v1/chat/completions")"
frames="$(printf '%s\n' "$stream" | grep -c '^data:')"
report "LLM stream emits multiple data frames" $([ "$frames" -ge 2 ]; echo $?)
report "LLM stream ends with data: [DONE]" $(printf '%s' "$stream" | tail -c 512 | grep -q '\[DONE\]'; echo $?)

# --- summary ------------------------------------------------------------------
echo ""
echo "smoke: $PASS passed, $FAIL failed (url: $SMOKE_URL)"
[ "$FAIL" -eq 0 ]