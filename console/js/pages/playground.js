'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['playground'] = {
  init(container) {
    container.replaceChildren();

    const keySelect = GK.el('select', { class: 'key-select' },
      GK.el('option', { value: 'gk_demo_free_tenant' }, 'Free tenant (gk_demo_free_tenant) - 20 cap, 1/s refill'),
      GK.el('option', { value: 'gk_demo_pro_tenant' }, 'Pro tenant (gk_demo_pro_tenant) - 100 cap'),
      GK.el('option', { value: 'gk_demo_sandbox' }, 'Sandbox tenant (gk_demo_sandbox)'),
      GK.el('option', { value: 'gk_invalid_test_key' }, 'Invalid Key (Tests 401 response)'));

    const pathInput = GK.el('input', { type: 'text', value: '/demo/hello', style: 'font-family: var(--font-mono);' });

    const prioritySelect = GK.el('select', { class: 'priority-select' },
      GK.el('option', { value: 'None' }, 'None'),
      GK.el('option', { value: 'P0' }, 'P0 (Critical)'),
      GK.el('option', { value: 'P1' }, 'P1 (Standard)'),
      GK.el('option', { value: 'P2' }, 'P2 (Low / Shed First)'));

    const countInput = GK.el('input', { type: 'number', min: '1', max: '50', value: '10' });

    const sendBtn = GK.el('button', { class: 'btn btn-primary' }, '🚀 Send Requests');
    const burstFreeBtn = GK.el('button', { class: 'btn-fast' }, '⚡ 1-Click Free Tier Burst (50 reqs)');
    const test401Btn = GK.el('button', { class: 'btn-fast' }, '🛡️ Test Invalid Key (401)');

    const progress = GK.el('p', { class: 'muted', style: 'margin: 0.5rem 0;' });
    const summary = GK.el('div', { style: 'font-weight: 600; margin: 0.5rem 0; font-variant-numeric: tabular-nums;' });
    const errorBox = GK.el('div');

    const field = (label, control) =>
      GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, label), control);

    // Mount container for visual Token Bucket
    const bucketContainer = GK.el('div');

    const tbody = GK.el('tbody');
    const table = GK.el('table', { class: 'results-table' },
      GK.el('thead', {},
        GK.el('tr', {},
          GK.el('th', {}, '#'),
          GK.el('th', {}, 'Status'),
          GK.el('th', {}, 'Latency'),
          GK.el('th', {}, 'RateLimit-Limit'),
          GK.el('th', {}, 'RateLimit-Remaining'),
          GK.el('th', {}, 'RateLimit-Reset'),
          GK.el('th', {}, 'Retry-After'),
          GK.el('th', {}, 'Decision'),
          GK.el('th', {}, 'Rule'))),
      tbody);

    const jsonDrawer = GK.el('pre', { class: 'response-drawer', style: 'display: none;' });

    const playgroundLayout = GK.el('div', { style: 'display: grid; grid-template-columns: 2fr 1fr; gap: 1rem; align-items: start;' },
      GK.el('div', {},
        GK.el('div', { class: 'form-row' },
          field('Tenant & API Key', keySelect),
          field('Path', pathInput),
          field('Priority', prioritySelect),
          field('Request Count', countInput)),
        GK.el('div', { style: 'display: flex; gap: 0.5rem; align-items: center; margin-top: 0.5rem;' },
          sendBtn
        ),
        GK.el('div', { class: 'btn-group-fast' },
          burstFreeBtn,
          test401Btn
        ),
        progress,
        summary,
        errorBox,
        GK.el('div', { style: 'overflow-x: auto; margin-top: 0.75rem;' }, table),
        jsonDrawer
      ),
      bucketContainer
    );

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
        GK.el('h2', {}, 'API Gateway Playground & Token Bucket Simulator'),
        GK.el('span', { class: 'brand-badge' }, 'INTERACTIVE TESTBED')
      ),
      GK.el('p', { class: 'panel-note' }, 'Fire live requests through the gateway and observe token consumption, burst limits, and 429 throttling in real time.'),
      playgroundLayout
    ));

    // Initialize Token Bucket simulator
    let bucketWidget = null;
    if (window.GKTokenBucketWidget) {
      bucketWidget = new window.GKTokenBucketWidget(bucketContainer, {
        capacity: 20,
        currentTokens: 20,
        refillPerSec: 1.0
      });
    }

    const headerOrDash = (res, name) => res.headers.get(name) || '—';

    async function executeBatch(key, path, priority, count) {
      sendBtn.disabled = true;
      burstFreeBtn.disabled = true;
      test401Btn.disabled = true;
      tbody.replaceChildren();
      summary.textContent = '';
      errorBox.replaceChildren();
      jsonDrawer.style.display = 'none';

      let allowed = 0;
      let denied = 0;
      let lastBody = '';

      const headers = { Authorization: 'Bearer ' + key, 'X-API-Key': key };
      if (priority !== 'None') headers['X-GateKeeper-Priority'] = priority;

      try {
        for (let i = 1; i <= count; i++) {
          progress.textContent = `Dispatching request ${i} of ${count}...`;
          const t0 = performance.now();
          const res = await GK.api(path, { headers });
          const latencyMs = (performance.now() - t0).toFixed(1);
          const status = res.status;

          try {
            lastBody = await res.text();
          } catch {
            lastBody = '';
          }

          if (status < 400) allowed += 1; else denied += 1;
          const statusClass = status < 300 ? 'ok' : (status < 500 ? 'warn' : 'err');

          // Sync token bucket widget
          if (bucketWidget) {
            const limit = res.headers.get('RateLimit-Limit');
            const remaining = res.headers.get('RateLimit-Remaining');
            const retryAfter = res.headers.get('Retry-After');
            bucketWidget.syncHeaders(remaining, limit, retryAfter);
          }

          tbody.appendChild(GK.el('tr', {},
            GK.el('td', {}, String(i)),
            GK.el('td', { class: statusClass, style: 'font-weight: 700;' }, String(status)),
            GK.el('td', { style: 'font-variant-numeric: tabular-nums;' }, `${latencyMs} ms`),
            GK.el('td', {}, headerOrDash(res, 'RateLimit-Limit')),
            GK.el('td', {}, headerOrDash(res, 'RateLimit-Remaining')),
            GK.el('td', {}, headerOrDash(res, 'RateLimit-Reset')),
            GK.el('td', { style: status === 429 ? 'color: var(--red); font-weight: 700;' : '' }, headerOrDash(res, 'Retry-After')),
            GK.el('td', {}, headerOrDash(res, 'X-GateKeeper-Decision')),
            GK.el('td', {}, headerOrDash(res, 'X-GateKeeper-Rule'))));
        }

        progress.textContent = '';
        summary.innerHTML = `<span style="color: var(--green);">✔ ${allowed} Allowed</span> &nbsp;|&nbsp; <span style="color: ${denied > 0 ? 'var(--red)' : 'var(--dim)'};">✖ ${denied} Denied / 429</span> (Total: ${count} requests)`;

        if (lastBody) {
          jsonDrawer.style.display = 'block';
          jsonDrawer.textContent = `Latest Response Body:\n${lastBody}`;
        }
      } catch (err) {
        progress.textContent = '';
        errorBox.appendChild(GK.el('div', { class: 'err', style: 'margin: 0.5rem 0;' }, 'Request failed: ' + err.message));
      } finally {
        sendBtn.disabled = false;
        burstFreeBtn.disabled = false;
        test401Btn.disabled = false;
      }
    }

    sendBtn.addEventListener('click', () => {
      const key = keySelect.value;
      const path = pathInput.value.trim() || '/demo/hello';
      const priority = prioritySelect.value;
      let count = parseInt(countInput.value, 10);
      if (!Number.isFinite(count)) count = 5;
      count = Math.min(50, Math.max(1, count));
      executeBatch(key, path, priority, count);
    });

    burstFreeBtn.addEventListener('click', () => {
      keySelect.value = 'gk_demo_free_tenant';
      countInput.value = '50';
      executeBatch('gk_demo_free_tenant', '/demo/hello', 'None', 50);
    });

    test401Btn.addEventListener('click', () => {
      keySelect.value = 'gk_invalid_test_key';
      countInput.value = '1';
      executeBatch('gk_invalid_test_key', '/demo/hello', 'None', 1);
    });
  }
};
