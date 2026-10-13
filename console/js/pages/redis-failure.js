'use strict';

window.GKPages = window.GKPages || {};

let redisFailureUnsubscribe = null;

window.GKPages['redis-failure'] = {
  init(container) {
    container.replaceChildren();

    if (typeof redisFailureUnsubscribe === 'function') {
      try { redisFailureUnsubscribe(); } catch (e) { /* already detached */ }
    }

    const breakerLamp = GK.el('span', { class: 'lamp lamp-off' });
    const breakerText = GK.el('span', {}, 'Circuit breaker: waiting for stats...');
    const ratioText = GK.el('span', {}, 'Local hit ratio: -');

    const latencyBtn = GK.el('button', { class: 'btn' }, 'Inject 300ms latency');
    const outageBtn = GK.el('button', { class: 'btn' }, 'Outage');
    const clearBtn = GK.el('button', { class: 'btn btn-primary' }, 'Clear');
    const fireBtn = GK.el('button', { class: 'btn' }, 'Fire 20 requests');

    const faultStatus = GK.el('p', { class: 'muted' });
    const errorBox = GK.el('div');
    const burstStatus = GK.el('p', {});

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('h2', {}, 'Simulated Redis failure (demo) \u2014 real chaos runs used Toxiproxy locally'),
      GK.el('p', {}, 'Break the gateway\u2019s Redis connection and watch the circuit breaker open while local leases keep the gateway serving.'),
      GK.el('div', { class: 'form-row' }, latencyBtn, outageBtn, clearBtn),
      faultStatus,
      errorBox,
      GK.el('div', { class: 'live-rows' },
        GK.el('div', { class: 'live-row' }, breakerLamp, breakerText),
        GK.el('div', { class: 'live-row' }, ratioText)),
      fireBtn,
      burstStatus));

    const lampClass = (raw) => {
      const state = String(raw || '').toUpperCase();
      if (state === 'CLOSED') return 'lamp-green';
      if (state === 'HALF_OPEN' || state === 'HALF-OPEN') return 'lamp-amber';
      if (state === 'OPEN') return 'lamp-red';
      return 'lamp-off';
    };

    const render = (stats) => {
      if (!stats) return;
      const raw = stats.breakerState != null ? stats.breakerState : stats.circuitBreakerState;
      const state = String(raw || 'unknown').toLowerCase();
      breakerLamp.className = 'lamp ' + lampClass(raw);
      breakerText.textContent = 'Circuit breaker: ' + state.replace(/_/g, ' ');
      if (typeof stats.localHitRatio === 'number') {
        ratioText.textContent = 'Local hit ratio: ' + Math.round(stats.localHitRatio * 100) + '% (' + GK.fmt(stats.localHitRatio) + ')';
      } else {
        ratioText.textContent = 'Local hit ratio: -';
      }
    };

    render(GK.getStats());
    redisFailureUnsubscribe = GK.onStats(render);

    const applyFault = async (body, message) => {
      latencyBtn.disabled = true;
      outageBtn.disabled = true;
      clearBtn.disabled = true;
      faultStatus.textContent = 'Applying ' + message + '...';
      errorBox.replaceChildren();
      try {
        const res = await GK.api('/api/chaos/redis', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });
        if (res.ok) {
          faultStatus.textContent = message + ' is now active.';
        } else {
          faultStatus.textContent = '';
          errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
            'The gateway rejected the chaos request (HTTP ' + res.status + ').'));
        }
      } catch (err) {
        faultStatus.textContent = '';
        errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Could not reach the gateway. Check that it is running and try again.'));
      } finally {
        latencyBtn.disabled = false;
        outageBtn.disabled = false;
        clearBtn.disabled = false;
      }
    };

    latencyBtn.addEventListener('click', () => applyFault({ mode: 'latency', latencyMs: 300 }, '300ms Redis latency'));
    outageBtn.addEventListener('click', () => applyFault({ mode: 'outage' }, 'Redis outage'));
    clearBtn.addEventListener('click', () => applyFault({ mode: 'normal' }, 'Normal Redis operation'));

    fireBtn.addEventListener('click', async () => {
      fireBtn.disabled = true;
      errorBox.replaceChildren();
      burstStatus.textContent = 'Sending 20 requests with the free tenant key...';
      const mix = {};
      let sent = 0;
      try {
        for (let i = 0; i < 20; i++) {
          const res = await GK.api('/demo/hello', { headers: { Authorization: 'Bearer gk_demo_free_tenant' } });
          sent += 1;
          const key = String(res.status);
          mix[key] = (mix[key] || 0) + 1;
          burstStatus.textContent = 'Sent ' + sent + ' of 20 requests...';
        }
        const parts = Object.keys(mix).sort().map((k) => k + ' \u00d7 ' + mix[k]);
        burstStatus.textContent = sent + ' requests sent: ' + parts.join(', ') + '.';
      } catch (err) {
        const parts = Object.keys(mix).sort().map((k) => k + ' \u00d7 ' + mix[k]);
        burstStatus.textContent = parts.length
          ? 'Interrupted after ' + sent + ' requests: ' + parts.join(', ') + '.'
          : '';
        errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Could not reach the gateway. Check that it is running and try again.'));
      } finally {
        fireBtn.disabled = false;
      }
    });
  }
};
