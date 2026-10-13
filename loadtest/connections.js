// Connection-concurrency stress: N concurrent keep-alive connections.
//
// k6 does not let a VU hold a raw socket open indefinitely: each VU keeps a
// pooled keep-alive connection and reuses it between iterations. To approximate
// CONNECTIONS simultaneously open connections we therefore run CONNECTIONS VUs,
// each issuing one short request and then sleeping 10s — during the sleep the
// VU's keep-alive connection stays resident in the pool, and the short requests
// themselves keep the path hot.
//
// OS constraints to be aware of before running this at high CONNECTIONS:
//
//   Windows:
//     - Default ephemeral port range is 49152-65535 (~16k ports). Every
//       connection needs a unique local port per (src IP, dst IP, dst port)
//       tuple, so a single host cannot open 50k connections to one endpoint
//       without tuning (netsh int ipv4 show dynamicport / set dynamicport)
//       or using multiple source IPs.
//     - TIME_WAIT sockets from prior runs consume the same range; wait or
//       reduce CONNECTIONS between runs.
//     - 50k VUs is heavy on its own — reduce CONNECTIONS on dev laptops
//       (e.g. CONNECTIONS=2000).
//
//   Linux:
//     - Raise the file-descriptor limit first: ulimit -n 1048576 (soft default
//       is often 1024).
//     - Check the port range / backlog: net.ipv4.ip_local_port_range,
//       net.core.somaxconn, and the conntrack table size.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, KEYS, authHeaders, defaultThresholds } from './lib.js';

const CONNECTIONS = Number(__ENV.CONNECTIONS || '50000');
const DURATION = __ENV.DURATION || '60s';

export const options = {
  scenarios: {
    connections: {
      executor: 'constant-vus',
      vus: CONNECTIONS,
      duration: DURATION,
    },
  },
  thresholds: defaultThresholds,
};

export default function () {
  const res = http.get(`${BASE_URL}/demo/hello`, authHeaders(KEYS.pro));
  check(res, { 'status 2xx': (r) => r.status >= 200 && r.status < 300 });
  sleep(10);
}

export function handleSummary(data) {
  return { 'results/connections.json': JSON.stringify(data, null, 2) };
}