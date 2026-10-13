// Playwright script: logs into Grafana (admin/admin) and saves a PNG of each
// GateKeeper dashboard to docs/evidence/screenshots/.
//
// Prereqs:
//   docker compose -f infra/docker-compose.yml up -d grafana prometheus
//   npm i playwright && npx playwright install chromium
// Then:
//   node scripts/screenshots.mjs
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const GRAFANA_URL = process.env.GRAFANA_URL || 'http://localhost:3000';
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = resolve(HERE, '..', 'docs', 'evidence', 'screenshots');

const DASHBOARDS = [
  { uid: 'gk-overview', slug: 'gatekeeper-overview', file: 'gatekeeper-overview.png' },
  { uid: 'gk-limiter', slug: 'gatekeeper-limiter', file: 'gatekeeper-limiter.png' },
  { uid: 'gk-congestion', slug: 'gatekeeper-congestion', file: 'gatekeeper-congestion.png' },
  { uid: 'gk-llm', slug: 'gatekeeper-llm', file: 'gatekeeper-llm.png' },
  { uid: 'gk-policies', slug: 'gatekeeper-policies', file: 'gatekeeper-policies.png' },
];

mkdirSync(OUT_DIR, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });

await page.goto(`${GRAFANA_URL}/login`, { waitUntil: 'networkidle' });
await page.fill('input[name="user"]', 'admin');
await page.fill('input[name="password"]', 'admin');
await page.click('button[type="submit"]');
await page.waitForLoadState('networkidle');

for (const dashboard of DASHBOARDS) {
  await page.goto(`${GRAFANA_URL}/d/${dashboard.uid}/${dashboard.slug}`, { waitUntil: 'networkidle' });
  // give the panels ~10s to issue their Prometheus queries and render
  await page.waitForTimeout(10_000);
  const out = resolve(OUT_DIR, dashboard.file);
  await page.screenshot({ path: out, fullPage: true });
  console.log(`saved ${out}`);
}

await browser.close();
console.log('done');