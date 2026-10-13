'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['congestion'] = {
  init(container) {
    container.replaceChildren();

    const latency = GK.el('input', { type: 'range', min: '0', max: '2000', step: '100', value: '0' });
    const latencyValue = GK.el('strong', {}, '0 ms');
    latency.addEventListener('input', () => {
      latencyValue.textContent = latency.value + ' ms';
    });

    const applyBtn = GK.el('button', { class: 'btn' }, 'Apply upstream latency');
    const burstBtn = GK.el('button', { class: 'btn btn-primary' }, 'Run priority bursts');
    const statusLine = GK.el('p', { class: 'muted' });
    const errorBox = GK.el('div');
    const results = GK.el('div');

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('h2', {}, 'Congestion and priority shedding'),
      GK.el('p', {}, 'Slow the mock upstream, then fire parallel bursts at P0, P1 and P2 to watch the adaptive shedder triage traffic.'),
      GK.el('label', { class: 'field' },
        GK.el('span', { class: 'field-label' }, 'Upstream latency'),
        GK.el('div', { class: 'form-row' }, latency, latencyValue)),
      GK.el('div', { class: 'form-row' }, applyBtn, burstBtn),
      statusLine,
      errorBox,
      results,
      GK.el('p', { class: 'muted' }, 'Latency is injected into the mock upstream. P2 is shed first, then P1; P0 keeps passing.')));

    const showError = (text) => {
      errorBox.replaceChildren(GK.el('div', { class: 'panel err' }, text));
    };

    applyBtn.addEventListener('click', async () => {
      applyBtn.disabled = true;
      errorBox.replaceChildren();
      const latencyMs = parseInt(latency.value, 10);
      statusLine.textContent = 'Injecting ' + latencyMs + ' ms of upstream latency...';
      try {
        const res = await GK.api('/api/chaos/upstream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ latencyMs })
        });
        if (res.ok) {
          statusLine.textContent = 'Injected ' + latencyMs + ' ms of latency into the mock upstream.';
        } else {
          statusLine.textContent = '';
          showError('The gateway rejected the chaos request (HTTP ' + res.status + ').');
        }
      } catch (err) {
        statusLine.textContent = '';
        showError('Could not reach the gateway. Check that it is running and try again.');
      } finally {
        applyBtn.disabled = false;
      }
    });

    burstBtn.addEventListener('click', async () => {
      burstBtn.disabled = true;
      applyBtn.disabled = true;
      errorBox.replaceChildren();
      results.replaceChildren(GK.el('p', { class: 'muted' }, 'Firing 3 bursts of 10 requests...'));
      statusLine.textContent = 'Bursts in flight...';

      const runBurst = async (priority) => {
        const headers = { Authorization: 'Bearer gk_demo_pro_tenant', 'X-GateKeeper-Priority': priority };
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
          const rowChildren = [
            GK.el('span', { class: 'burst-label' }, priority),
            GK.el('span', { class: 'lamp lamp-green' }),
            GK.el('span', {}, counts.ok200 + ' \u00d7 200'),
            GK.el('span', { class: 'lamp lamp-amber' }),
            GK.el('span', {}, counts.limited429 + ' \u00d7 429'),
            GK.el('span', { class: 'lamp lamp-red' }),
            GK.el('span', {}, counts.shed503 + ' \u00d7 503')
          ];
          const extras = [];
          if (counts.other) extras.push(counts.other + ' other');
          if (counts.failed) extras.push(counts.failed + ' failed');
          if (extras.length) {
            rowChildren.push(GK.el('span', { class: 'muted' }, ' (' + extras.join(', ') + ')'));
          }
          results.appendChild(GK.el('div', { class: 'burst-row' }, ...rowChildren));
        });
        statusLine.textContent = 'Bursts complete. The shedder dropped low-priority traffic first.';
      } catch (err) {
        statusLine.textContent = '';
        showError('Could not reach the gateway. Check that it is running and try again.');
      } finally {
        burstBtn.disabled = false;
        applyBtn.disabled = false;
      }
    });
  }
};
