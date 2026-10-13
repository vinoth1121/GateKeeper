#!/usr/bin/env bash
# Manage the 'redis' toxiproxy proxy (the gateway reaches Redis through
# redis://toxiproxy:26379) so Redis faults can be injected from the host.
#
# Usage:
#   scripts/toxiproxy_setup.sh setup           create the redis proxy (idempotent)
#   scripts/toxiproxy_setup.sh latency 100    add +100ms downstream latency
#   scripts/toxiproxy_setup.sh timeout         hang Redis responses (timeout toxic)
#   scripts/toxiproxy_setup.sh clear           remove all toxics (recreate proxy)
#   scripts/toxiproxy_setup.sh list            show current proxies as JSON
set -eu

API="${TOXIPROXY_URL:-http://localhost:8474}"
PROXY="redis"
LISTEN="0.0.0.0:26379"
UPSTREAM="redis:6379"

ensure_proxy() {
  if ! curl -sf "$API/proxies/$PROXY" >/dev/null 2>&1; then
    curl -s -X POST -H 'Content-Type: application/json' \
      -d "{\"name\":\"$PROXY\",\"listen\":\"$LISTEN\",\"upstream\":\"$UPSTREAM\"}" \
      "$API/proxies" >/dev/null
    echo "proxy '$PROXY' created ($LISTEN -> $UPSTREAM)"
  fi
}

action="${1:-setup}"

case "$action" in
  setup)
    ensure_proxy
    echo "toxiproxy '$PROXY' ready"
    ;;
  latency)
    ms="${2:?usage: toxiproxy_setup.sh latency <ms>}"
    ensure_proxy
    curl -s -X DELETE "$API/proxies/$PROXY/toxics/latency_downstream" >/dev/null 2>&1 || true
    curl -s -X POST -H 'Content-Type: application/json' \
      -d "{\"name\":\"latency_downstream\",\"type\":\"latency\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"latency\":$ms}}" \
      "$API/proxies/$PROXY/toxics" >/dev/null
    echo "latency toxic added: +${ms}ms on downstream traffic"
    ;;
  timeout)
    ensure_proxy
    curl -s -X DELETE "$API/proxies/$PROXY/toxics/timeout_downstream" >/dev/null 2>&1 || true
    curl -s -X POST -H 'Content-Type: application/json' \
      -d "{\"name\":\"timeout_downstream\",\"type\":\"timeout\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"timeout\":0}}" \
      "$API/proxies/$PROXY/toxics" >/dev/null
    echo "timeout toxic added: Redis responses now hang"
    ;;
  clear)
    curl -s -X DELETE "$API/proxies/$PROXY" >/dev/null 2>&1 || true
    ensure_proxy
    echo "all toxics cleared (proxy recreated clean)"
    ;;
  list)
    curl -s "$API/proxies"
    echo
    ;;
  *)
    echo "usage: $0 setup|latency <ms>|timeout|clear|list" >&2
    exit 2
    ;;
esac