// Shared helpers for the GateKeeper k6 load scripts (k6 v2.3.0).

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const DIRECT_URL = __ENV.DIRECT_URL || 'http://localhost:8081';

export const KEYS = Object.freeze({
  free: 'gk_demo_free_tenant',
  pro: 'gk_demo_pro_tenant',
  ent: 'gk_demo_ent_tenant',
});

export function authHeaders(key, extra) {
  const headers = { 'X-API-Key': key };
  if (extra) {
    Object.assign(headers, extra);
  }
  return headers;
}

// Case-insensitive response header lookup (k6 preserves the case the server sent).
export function header(response, name) {
  const lower = name.toLowerCase();
  const keys = Object.keys(response.headers);
  for (let i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === lower) {
      return response.headers[keys[i]];
    }
  }
  return undefined;
}

export const defaultThresholds = {
  http_req_failed: ['rate<0.1'],
};