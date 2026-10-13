(function () {
  'use strict';

  window.GKPages = window.GKPages || {};

  function percent(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    const pct = n >= 0 && n <= 1 ? n * 100 : n;
    return pct.toFixed(1) + '%';
  }

  function breakerClass(state) {
    const s = String(state || '').toUpperCase();
    if (s === 'CLOSED' || state === 0) return 'lamp-green';
    if (s === 'HALF_OPEN' || s === 'HALF-OPEN' || state === 1) return 'lamp-amber';
    if (s === 'OPEN' || state === 2) return 'lamp-red';
    return 'lamp-off';
  }

  window.GKPages['overview'] = {
    init(container) {
      const GK = window.GK;
      if (!GK || !container) return;

      container.innerHTML = '';

      const fields = [
        { id: 'rps', label: 'Throughput (RPS)', icon: '⚡', get: (s) => (Number(s.requestsPerSecond || s.rps || 0)).toFixed(2) },
        { id: 'allowed', label: 'Allowed Requests', icon: '🟢', get: (s) => GK.fmt(s.allowed) },
        { id: 'denied', label: 'Rate-Limited (429)', icon: '🔴', get: (s) => GK.fmt(s.denied) },
        { id: 'p95', label: 'Overhead P95 (ms)', icon: '⏱️', get: (s) => (Number(s.overheadP95Millis || 0)).toFixed(3) },
        { id: 'p99', label: 'Overhead P99 (ms)', icon: '📊', get: (s) => (Number(s.overheadP99Millis || 0)).toFixed(3) },
        { id: 'policy', label: 'Policy Version', icon: '📜', get: (s) => (s.policyVersion == null ? '—' : `v${s.policyVersion}`) },
        { id: 'hit', label: 'Local Lease Hit Ratio', icon: '🎯', get: (s) => percent(s.localHitRatio) },
        { id: 'nodes', label: 'Active Edge Nodes', icon: '🖥️', get: (s) => GK.fmt(s.nodeCount) },
      ];

      const grid = GK.el('div', { class: 'stat-card-grid' });
      const valueEls = {};

      for (const f of fields) {
        const valEl = GK.el('div', { class: 'stat-card-value', text: '—' });
        valueEls[f.id] = valEl;

        const card = GK.el('div', { class: 'stat-card' },
          GK.el('div', { class: 'stat-card-label' }, `${f.icon} ${f.label}`),
          valEl
        );
        grid.append(card);
      }

      // Circuit breaker card
      const lamp = GK.el('span', { class: 'lamp lamp-off' });
      const breakerLabel = GK.el('span', { text: '—' });
      const breakerCard = GK.el('div', { class: 'stat-card' },
        GK.el('div', { class: 'stat-card-label' }, '🛡️ Redis Circuit Breaker'),
        GK.el('div', { class: 'stat-card-value', style: 'align-items: center; gap: 0.5rem;' }, lamp, breakerLabel)
      );
      grid.append(breakerCard);

      // Sparkline Canvas Section: Real-Time Traffic & Decisions
      const trafficCanvas = GK.el('canvas', { class: 'sparkline-canvas' });
      const trafficCard = GK.el('div', { class: 'panel', style: 'margin-top: 1rem;' },
        GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
          GK.el('h3', { style: 'margin: 0; font-size: 1.1rem;' }, 'Live Telemetry & Rate-Limit Decisions'),
          GK.el('div', { style: 'display: flex; gap: 1rem; font-size: 0.8rem;' },
            GK.el('span', { style: 'color: var(--green);' }, '● Allowed (200 OK)'),
            GK.el('span', { style: 'color: var(--red);' }, '● Throttled (429)')
          )
        ),
        GK.el('div', { class: 'sparkline-wrap' }, trafficCanvas)
      );

      const mainPanel = GK.el('div', { class: 'panel' },
        GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;' },
          GK.el('h2', { class: 'panel-title', style: 'margin: 0;' }, 'Gateway Operations Overview'),
          GK.el('span', { class: 'brand-badge live-dot' }, 'STREAMING METRICS')
        ),
        GK.el('p', { class: 'panel-note' }, 'Real-time telemetry sourced directly from GateKeeper Gateway MVC on Virtual Threads.'),
        grid
      );

      container.append(mainPanel, trafficCard);

      let sparkline = null;
      if (window.GKSparkline) {
        sparkline = new window.GKSparkline(trafficCanvas, {
          colorA: '#10b981',
          colorB: '#ef4444',
          fillGradient: true,
        });
      }

      let lastAllowed = null;
      let lastDenied = null;

      const unsubscribe = GK.onStats((stats) => {
        if (!container.isConnected) {
          unsubscribe();
          return;
        }
        const s = stats || {};
        for (const f of fields) {
          if (valueEls[f.id]) valueEls[f.id].textContent = f.get(s);
        }

        const rawBreaker = s.circuitBreakerState || s.breakerState;
        lamp.className = 'lamp ' + breakerClass(rawBreaker);
        breakerLabel.textContent = rawBreaker == null ? '—' : String(rawBreaker).toUpperCase();

        if (sparkline) {
          const curAllowed = Number(s.allowed || 0);
          const curDenied = Number(s.denied || 0);

          let deltaAllowed = 0;
          let deltaDenied = 0;

          if (lastAllowed !== null) deltaAllowed = Math.max(0, curAllowed - lastAllowed);
          if (lastDenied !== null) deltaDenied = Math.max(0, curDenied - lastDenied);

          lastAllowed = curAllowed;
          lastDenied = curDenied;

          sparkline.push(deltaAllowed, deltaDenied);
        }
      });
    },
  };
})();
