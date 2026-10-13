// Load ramp: 500 -> 20000 rps to find where the limiter starts shedding.
// Writes the raw k6 summary to results/ramp.json.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, KEYS, authHeaders } from './lib.js';

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 500,
      timeUnit: '1s',
      preAllocatedVUs: 3000,
      maxVUs: 20000,
      stages: [
        { target: 500, duration: '10s' },
        { target: 5000, duration: '30s' },
        { target: 20000, duration: '60s' },
        { target: 20000, duration: '20s' },
      ],
    },
  },
  // 429s are the system working as designed under overload; only crashes count.
  thresholds: {
    http_req_failed: ['rate<0.25'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/demo/hello`, authHeaders(KEYS.pro));
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return { 'results/ramp.json': JSON.stringify(data, null, 2) };
}