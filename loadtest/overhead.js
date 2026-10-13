// Gateway overhead: same 300 rps for 30s direct to the mock upstream (scenario
// 'direct', DIRECT_URL /hello) and through the gateway (scenario 'via',
// BASE_URL /demo/hello). handleSummary computes the p50/p95/p99 delta and
// writes results/overhead.json.
//
// Note: 'direct' must reach the mock upstream from wherever k6 runs. With
// infra/docker-compose.yml (mock-upstream has no published host port),
// scripts/run_load.ps1 executes this script with the grafana/k6 image inside
// the compose network, pointing DIRECT_URL at http://mock-upstream:8081.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, DIRECT_URL, KEYS, authHeaders, defaultThresholds } from './lib.js';

const directDuration = new Trend('overhead_direct_duration', true);
const viaDuration = new Trend('overhead_via_duration', true);

export const options = {
  scenarios: {
    direct: {
      executor: 'constant-arrival-rate',
      exec: 'direct',
      rate: 300,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
    },
    via: {
      executor: 'constant-arrival-rate',
      exec: 'via',
      rate: 300,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: defaultThresholds,
};

export function direct() {
  const res = http.get(`${DIRECT_URL}/hello`);
  check(res, { 'direct 200': (r) => r.status === 200 });
  directDuration.add(res.timings.duration);
}

export function via() {
  const res = http.get(`${BASE_URL}/demo/hello`, authHeaders(KEYS.pro));
  check(res, { 'via 200': (r) => r.status === 200 });
  viaDuration.add(res.timings.duration);
}

export function handleSummary(data) {
  const p = (metric, field) =>
    data.metrics[metric] ? data.metrics[metric].values[field] : null;
  const direct = { p50: p('overhead_direct_duration', 'med'), p95: p('overhead_direct_duration', 'p(95)'), p99: p('overhead_direct_duration', 'p(99)') };
  const via = { p50: p('overhead_via_duration', 'med'), p95: p('overhead_via_duration', 'p(95)'), p99: p('overhead_via_duration', 'p(99)') };
  const delta = {};
  for (const k of Object.keys(direct)) {
    delta[k] = direct[k] !== null && via[k] !== null ? via[k] - direct[k] : null;
  }
  const out = {
    generated: new Date().toISOString(),
    scenarios: { direct: '300 rps -> DIRECT_URL/hello', via: '300 rps -> BASE_URL/demo/hello' },
    duration_s: 30,
    direct_ms: direct,
    via_ms: via,
    delta_ms: delta,
  };
  const text = [
    'Gateway overhead (via - direct):',
    `  p50: ${delta.p50 !== null ? delta.p50.toFixed(2) + ' ms' : 'n/a'}`,
    `  p95: ${delta.p95 !== null ? delta.p95.toFixed(2) + ' ms' : 'n/a'}`,
    `  p99: ${delta.p99 !== null ? delta.p99.toFixed(2) + ' ms' : 'n/a'}`,
  ].join('\n');
  return {
    'results/overhead.json': JSON.stringify(out, null, 2),
    stdout: text + '\n',
  };
}