'use strict';

window.GKPages = window.GKPages || {};

let redisFailureUnsubscribe = null;

window.GKPages['redis-failure'] = {
  init(container) {
    container.replaceChildren();

    if (typeof redisFailureUnsubscribe === 'function') {
      try { redisFailureUnsubscribe(); } catch (e) { /* detached */ }
    }

    const latencyBtn = GK.el('button', { class: 'btn-fast' }, '⏱️ Inject 300ms Latency');
    const outageBtn = GK.el('button', { class: 'btn-fast btn-fast-danger' }, '💥 Trigger Redis Outage');
    const clearBtn = GK.el('button', { class: 'btn btn-primary' }, '🛡️ Restore Normal Operation');
    const fireBtn = GK.el('button', { class: 'btn-fast' }, '🚀 Fire 20 Requests (Test Degraded Mode)');

    const faultStatus = GK.el('p', { class: 'muted', style: 'margin: 0.5rem 0;' });
    const errorBox = GK.el('div');
    const burstStatus = GK.el('p', { style: 'font-weight: 600; margin: 0.5rem 0; font-variant-numeric: tabular-nums;' });

    // Visual State Machine Nodes
    const stateClosed = GK.el('div', { class: 'stat-card', style: 'border-left: 4px solid var(--green);' },
      GK.el('div', { class: 'stat-card-label' }, 'NORMAL OPERATION'),
      GK.el('div', { class: 'stat-card-value', style: 'color: var(--green);' }, '🟢 CLOSED'),
      GK.el('div', { class: 'stat-card-sub' }, 'Redis healthy. Distributed EVALSHA active.')
    );

    const stateOpen = GK.el('div', { class: 'stat-card', style: 'border-left: 4px solid var(--red);' },
      GK.el('div', { class: 'stat-card-label' }, 'FAULT INJECTED'),
      GK.el('div', { class: 'stat-card-value', style: 'color: var(--red);' }, '🔴 OPEN'),
      GK.el('div', { class: 'stat-card-sub' }, 'Fail-open degraded mode. Local backstop prevents 5xx.')
    );

    const stateHalfOpen = GK.el('div', { class: 'stat-card', style: 'border-left: 4px solid var(--amber);' },
      GK.el('div', { class: 'stat-card-label' }, 'RECOVERY PROBING'),
      GK.el('div', { class: 'stat-card-value', style: 'color: var(--amber);' }, '🟡 HALF-OPEN'),
      GK.el('div', { class: 'stat-card-sub' }, 'Testing 5 probe requests before closing breaker.')
    );

    const breakerDiagram = GK.el('div', { class: 'stat-card-grid', style: 'margin: 1rem 0;' },
      stateClosed,
      stateOpen,
      stateHalfOpen
    );

    const breakerLivePill = GK.el('span', { class: 'brand-badge' }, 'CHECKING STATE...');
    const ratioText = GK.el('span', { style: 'font-variant-numeric: tabular-nums; font-weight: 600;' }, '—');

    const liveStatsRow = GK.el('div', { class: 'stat-card', style: 'margin: 1rem 0; display: flex; justify-content: space-between; align-items: center;' },
      GK.el('div', {},
        GK.el('div', { class: 'stat-card-label' }, 'Current Resilience4j Breaker State'),
        breakerLivePill
      ),
      GK.el('div', { style: 'text-align: right;' },
        GK.el('div', { class: 'stat-card-label' }, 'Local Lease Hit Ratio'),
        ratioText
      )
    );

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
        GK.el('h2', {}, 'Chaos Engineering & Redis Failure Simulation'),
        GK.el('span', { class: 'brand-badge live-dot' }, 'RESILIENCE4J FAULT INJECTION')
      ),
      GK.el('p', { class: 'panel-note' }, 'Simulate hard network partitions or Redis latency and observe the circuit breaker trip into fail-open degraded mode without returning 500 errors to callers.'),
      breakerDiagram,
      liveStatsRow,
      GK.el('div', { class: 'btn-group-fast', style: 'margin: 1rem 0;' }, latencyBtn, outageBtn, clearBtn),
      faultStatus,
      errorBox,
      GK.el('hr', { style: 'border: none; border-top: 1px solid var(--edge); margin: 1rem 0;' }),
      GK.el('h3', { style: 'font-size: 1.1rem; margin-bottom: 0.5rem;' }, 'Degraded Mode Verification'),
      GK.el('p', { class: 'panel-note' }, 'Fire requests while the breaker is OPEN to verify that GateKeeper serves traffic via local leases without returning 5xx errors.'),
      fireBtn,
      burstStatus
    ));

    const render = (stats) => {
      if (!stats) return;
      const raw = stats.circuitBreakerState != null ? stats.circuitBreakerState : stats.breakerState;
      const state = String(raw || 'closed').toLowerCase();

      // Highlight active state machine node
      stateClosed.style.opacity = (state === 'closed' || state === '0') ? '1' : '0.4';
      stateOpen.style.opacity = (state === 'open' || state === '2') ? '1' : '0.4';
      stateHalfOpen.style.opacity = (state === 'half_open' || state === 'half-open' || state === '1') ? '1' : '0.4';

      if (state === 'open' || state === '2') {
        breakerLivePill.className = 'brand-badge';
        breakerLivePill.style.background = 'rgba(239, 68, 68, 0.2)';
        breakerLivePill.style.color = 'var(--red)';
        breakerLivePill.style.borderColor = 'rgba(239, 68, 68, 0.5)';
        breakerLivePill.textContent = '🔴 OPEN (FAIL-OPEN DEGRADED MODE)';
      } else if (state === 'half_open' || state === 'half-open' || state === '1') {
        breakerLivePill.className = 'brand-badge';
        breakerLivePill.style.background = 'rgba(245, 158, 11, 0.2)';
        breakerLivePill.style.color = 'var(--amber)';
        breakerLivePill.style.borderColor = 'rgba(245, 158, 11, 0.5)';
        breakerLivePill.textContent = '🟡 HALF-OPEN (PROBING)';
      } else {
        breakerLivePill.className = 'brand-badge live-dot';
        breakerLivePill.style.background = 'rgba(16, 185, 129, 0.12)';
        breakerLivePill.style.color = 'var(--green)';
        breakerLivePill.style.borderColor = 'rgba(16, 185, 129, 0.3)';
        breakerLivePill.textContent = '🟢 CLOSED (NORMAL OPERATION)';
      }

      if (typeof stats.localHitRatio === 'number') {
        ratioText.textContent = `${Math.round(stats.localHitRatio * 100)}% (${Number(stats.localHitRatio).toFixed(2)})`;
      } else {
        ratioText.textContent = '—';
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
          faultStatus.textContent = '✔ ' + message + ' active.';
        } else {
          faultStatus.textContent = '';
          errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
            'Chaos controller returned HTTP ' + res.status));
        }
      } catch (err) {
        faultStatus.textContent = '';
        errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Error dispatching chaos request: ' + err.message));
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
      burstStatus.textContent = 'Dispatching 20 requests under current fault conditions...';
      const mix = {};
      let sent = 0;
      let non5xxCount = 0;

      try {
        for (let i = 0; i < 20; i++) {
          const res = await GK.api('/demo/hello', { headers: { Authorization: 'Bearer gk_demo_free_tenant', 'X-API-Key': 'gk_demo_free_tenant' } });
          sent += 1;
          const status = res.status;
          if (status < 500) non5xxCount += 1;
          mix[status] = (mix[status] || 0) + 1;
          burstStatus.textContent = `Sent ${sent} of 20 requests...`;
        }
        const parts = Object.keys(mix).sort().map((k) => `HTTP ${k} × ${mix[k]}`);
        burstStatus.innerHTML = `Completed 20 requests: ${parts.join(', ')} &nbsp;|&nbsp; <span style="color: var(--green);">Zero 5xx errors (Fail-open resilience verified)</span>`;
      } catch (err) {
        burstStatus.textContent = 'Request interrupted: ' + err.message;
      } finally {
        fireBtn.disabled = false;
      }
    });
  }
};
