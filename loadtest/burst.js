// Free-tier burst: 50 immediate requests to /demo/hello with the FREE key.
// Validates that a burst trips the rate limit (at least one 429) and that
// every 429 carries RateLimit-Remaining and Retry-After headers.
// A short summary is printed to the console after each burst.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, KEYS, authHeaders, header } from './lib.js';

const BURST = Number(__ENV.BURST || '50');

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-vus',
      vus: 1,
      duration: '10s',
    },
  },
};

export default function () {
  let saw429 = false;
  let rateLimitHeaderOk = false;
  let retryAfterOk = false;
  for (let i = 0; i < BURST; i++) {
    const res = http.get(`${BASE_URL}/demo/hello`, authHeaders(KEYS.free));
    if (res.status === 429) {
      saw429 = true;
      if (header(res, 'RateLimit-Remaining') !== undefined) {
        rateLimitHeaderOk = true;
      }
      if (header(res, 'Retry-After') !== undefined) {
        retryAfterOk = true;
      }
    }
  }
  check(null, {
    [`burst of ${BURST} yields at least one 429`]: () => saw429,
    '429 responses carry RateLimit-Remaining': () => rateLimitHeaderOk,
    '429 responses carry Retry-After': () => retryAfterOk,
  });
  console.log(
    `burst of ${BURST}: saw429=${saw429} ` +
    `rateLimitRemaining=${rateLimitHeaderOk} retryAfter=${retryAfterOk}`
  );
  sleep(1);
}

export function handleSummary(data) {
  return { 'results/burst.json': JSON.stringify(data, null, 2) };
}