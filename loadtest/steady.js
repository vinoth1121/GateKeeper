// Sustained throughput: 5000 rps against /demo/hello for 60s with the PRO key.
// Measures steady-state latency and error rate at the PRO tier's limit.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, KEYS, authHeaders, defaultThresholds } from './lib.js';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: 5000,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 3000,
      maxVUs: 3000,
    },
  },
  thresholds: Object.assign({}, defaultThresholds, {
    http_req_duration: ['p(99)<1000'],
  }),
};

export default function () {
  const res = http.get(`${BASE_URL}/demo/hello`, authHeaders(KEYS.pro));
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return { 'results/steady.json': JSON.stringify(data, null, 2) };
}