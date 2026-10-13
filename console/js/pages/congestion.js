'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['congestion'] = {
  init(container) {
    container.replaceChildren();

    const latency = GK.el('input', { type: 'range', min: '0', max: '2000', step: '100', value: '0' });
    const latencyValue = GK.el('strong', { style: 'font-variant-numeric: tabular-nums; font-size: 1.1rem; color: var(--amber);' }, '0 ms');
    latency.addEventListener('input', () => {
      latencyValue.textContent = latency.value + ' ms';
    });

    const setLatency = (ms) => {
      latency.value = String(ms);
      latencyValue.textContent = ms + ' ms';
    };

    const preset0 = GK.el('button', { class: 'btn-fast', type: 'button' }, '0 ms (Healthy)');
    preset0.addEventListener('click', () => setLatency(0));

    const preset250 = GK.el('button', { class: 'btn-fast', type: 'button' }, '250 ms (Mild Load)');
    preset250.addEventListener('click', () => setLatency(250));

    const preset1000 = GK.el('button', { class: 'btn-fast btn-fast-danger', type: 'button' }, '1000 ms (Severe Congestion)');
    preset1000.addEventListener('click', () => setLatency(1000));

    const applyBtn = GK.el('button', { class: 'btn-fast' }, '⚡ Inject Latency to Mock Upstream');
    const burstBtn = GK.el('button', { class: 'btn btn-primary' }, '🚀 Trigger Multi-Priority Burst (P0, P1, P2)');
    const statusLine = GK.el('p', { class: 'muted', style: 'margin: 0.5rem 0;' });
    const errorBox = GK.el('div');
    const results = GK.el('div', { style: 'margin-top: 1rem;' });

    // Tier Cards
    const tierP0 = GK.el('div', { class: 'stat-card', style: 'border-left: 4px solid var(--green);' },
      GK.el('div', { class: 'stat-card-label' }, 'PRIORITY P0 (CRITICAL)'),
      GK.el('div', { class: 'stat-card-value', style: 'color: var(--green);' }, 'Protected'),
      GK.el('div', { class: 'stat-card-sub' }, 'Never shed. Reserved for health checks & critical paths.')
    );

    const tierP1 = GK.el('div', { class: 'stat-card', style: 'border-left: 4px solid var(--amber);' },
      GK.el('div', { class: 'stat-card-label' }, 'PRIORITY P1 (STANDARD)'),
      GK.el('div', { class: 'stat-card-value', style: 'color: var(--amber);' }, 'Gradient Shed'),
      GK.el('div', { class: 'stat-card-sub' }, 'Shed when RTT gradient exceeds tolerance threshold.')
    );

    const tierP2 = GK.el('div', { class: 'stat-card', style: 'border-left: 4px solid var(--red);' },
      GK.el('div', { class: 'stat-card-label' }, 'PRIORITY P2 (BACKGROUND)'),
      GK.el('div', { class: 'stat-card-value', style: 'color: var(--red);' }, 'First to Shed'),
      GK.el('div', { class: 'stat-card-sub' }, 'Immediately shed at the first sign of upstream queueing.')
    );

    const tierGrid = GK.el('div', { class: 'stat-card-grid', style: 'margin: 1rem 0;' },
      tierP0, tierP1, tierP2
    );

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
        GK.el('h2', {}, 'Adaptive Congestion Control & Priority Shedding'),
        GK.el('span', { class: 'brand-badge' }, 'GRADIENT ALGORITHM')
      ),
      GK.el('p', { class: 'panel-note' }, 'GateKeeper monitors upstream Round-Trip-Time (RTT) and dynamically sheds lower-priority traffic (P2 first, then P1) when upstream latency climbs, keeping P0 critical traffic protected.'),
      tierGrid,
      GK.el('div', { class: 'form-row', style: 'align-items: center; margin: 1rem 0;' },
        GK.el('span', { class: 'field-label' }, 'Simulate Upstream RTT Delay:'),
        latency,
        latencyValue
      ),
      GK.el('div', { class: 'btn-group-fast', style: 'margin-bottom: 0.75rem;' },
        preset0, preset250, preset1000
      ),
      GK.el('div', { class: 'btn-group-fast' }, applyBtn, burstBtn),
      statusLine,
      errorBox,
      results
    ));

    const showError = (text) => {
      errorBox.replaceChildren(GK.el('div', { class: 'panel err' }, text));
    };

    applyBtn.addEventListener('click', async () => {
      applyBtn.disabled = true;
      errorBox.replaceChildren();
      const latencyMs = parseInt(latency.value, 10);
      statusLine.textContent = `Injecting ${latencyMs} ms latency into mock upstream...`;
      try {
        const res = await GK.api('/api/chaos/upstream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ latencyMs })
        });
        if (res.ok) {
          statusLine.textContent = `✔ Upstream latency configured to ${latencyMs} ms.`;
        } else {
          statusLine.textContent = '';
          showError(`Gateway rejected upstream chaos request (HTTP ${res.status}).`);
        }
      } catch (err) {
        statusLine.textContent = '';
        showError('Could not reach the gateway: ' + err.message);
      } finally {
        applyBtn.disabled = false;
      }
    });

    burstBtn.addEventListener('click', async () => {
      burstBtn.disabled = true;
      applyBtn.disabled = true;
      errorBox.replaceChildren();
      results.replaceChildren(GK.el('p', { class: 'muted' }, 'Dispatching parallel bursts across P0, P1, and P2 tiers...'));
      statusLine.textContent = 'Bursts in flight...';

      const runBurst = async (priority) => {
        const headers = { Authorization: 'Bearer gk_demo_pro_tenant', 'X-GateKeeper-Priority': priority, 'X-API-Key': 'gk_demo_pro_tenant' };
        const replies = await Promise.allSettled(
          Array.from({ length: 10 }, () => GK.api('/demo/hello', { headers })));
        const counts = { ok200: 0, limited429: 0, shed503: 0, other: 0, failed: 0 };
        for (const reply of replies) {
          if (reply.status === 'rejected') { counts.failed += 1; continue; }
          const s = reply.value.status;
          if (s === 200) counts.ok200 += 1;
          else if (s === 429) counts.limited429 += 1;
          else if (s === 503) counts.shed503 += 1;
          else counts.other += 1;
        }
        return counts;
      };

      try {
        const all = await Promise.all([runBurst('P0'), runBurst('P1'), runBurst('P2')]);
        results.replaceChildren();
        ['P0', 'P1', 'P2'].forEach((priority, i) => {
          const counts = all[i];
          const bar = GK.el('div', { class: 'stat-card', style: 'margin-bottom: 0.5rem; display: flex; justify-content: space-between; align-items: center;' },
            GK.el('div', { style: 'display: flex; align-items: center; gap: 0.5rem;' },
              GK.el('span', { class: 'brand-badge' }, priority),
              GK.el('span', { style: 'font-weight: 600;' }, priority === 'P0' ? 'Critical Traffic' : (priority === 'P1' ? 'Standard Traffic' : 'Background Traffic'))
            ),
            GK.el('div', { style: 'display: flex; gap: 1rem; font-variant-numeric: tabular-nums; font-size: 0.9rem;' },
              GK.el('span', { style: 'color: var(--green);' }, `✔ ${counts.ok200} Allowed (200)`),
              GK.el('span', { style: counts.limited429 > 0 ? 'color: var(--amber);' : 'color: var(--dim);' }, `⚠️ ${counts.limited429} Rate-Limited (429)`),
              GK.el('span', { style: counts.shed503 > 0 ? 'color: var(--red); font-weight: 700;' : 'color: var(--dim);' }, `✖ ${counts.shed503} Shed (503)`)
            )
          );
          results.appendChild(bar);
        });
        statusLine.textContent = 'Multi-priority burst test complete. Triage verified.';
      } catch (err) {
        statusLine.textContent = '';
        showError('Could not reach the gateway: ' + err.message);
      } finally {
        burstBtn.disabled = false;
        applyBtn.disabled = false;
      }
    });
  }
};
