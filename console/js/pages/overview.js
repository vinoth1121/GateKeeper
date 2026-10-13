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
    if (state === 'CLOSED' || state === 0) return 'lamp-green';
    if (state === 'HALF_OPEN' || state === 1) return 'lamp-amber';
    if (state === 'OPEN' || state === 2) return 'lamp-red';
    return 'lamp-off';
  }

  window.GKPages['overview'] = {
    init(container) {
      const GK = window.GK;
      if (!GK || !container) return;

      const fields = [
        { id: 'rps', label: 'requests per second', get: (s) => GK.fmt(Number(s.rps || 0)) },
        { id: 'allowed', label: 'allowed', get: (s) => GK.fmt(s.allowed) },
        { id: 'denied', label: 'denied', get: (s) => GK.fmt(s.denied) },
        { id: 'p95', label: 'overhead p95 ms', get: (s) => GK.fmt(s.overheadP95Millis) },
        { id: 'p99', label: 'overhead p99 ms', get: (s) => GK.fmt(s.overheadP99Millis) },
        {
          id: 'policy',
          label: 'policy version',
          get: (s) => (s.policyVersion == null ? '—' : String(s.policyVersion)),
        },
        { id: 'hit', label: 'lease hit ratio %', get: (s) => percent(s.localHitRatio) },
        { id: 'nodes', label: 'node count', get: (s) => GK.fmt(s.nodeCount) },
      ];

      const grid = GK.el('div', { class: 'stat-grid' });
      const values = {};
      for (const f of fields) {
        const value = GK.el('div', { class: 'stat-value', text: '—' });
        values[f.id] = value;
        grid.append(
          GK.el(
            'div',
            { class: 'stat' },
            GK.el('div', { class: 'stat-label', text: f.label }),
            value
          )
        );
      }

      const lamp = GK.el('span', { class: 'lamp lamp-off' });
      const breakerLabel = GK.el('span', { text: '—' });
      grid.append(
        GK.el(
          'div',
          { class: 'stat' },
          GK.el('div', { class: 'stat-label', text: 'breaker state' }),
          GK.el('div', { class: 'stat-value' }, lamp, breakerLabel)
        )
      );

      container.append(
        GK.el(
          'div',
          { class: 'panel' },
          GK.el('h2', { class: 'panel-title', text: 'Overview' }),
          GK.el('p', {
            class: 'panel-note',
            text: 'Live gateway metrics from the public stats endpoint.',
          }),
          grid
        )
      );

      const unsubscribe = GK.onStats((stats) => {
        if (!grid.isConnected) {
          unsubscribe();
          return;
        }
        const s = stats || {};
        for (const f of fields) values[f.id].textContent = f.get(s);
        lamp.className = 'lamp ' + breakerClass(s.breakerState);
        breakerLabel.textContent = s.breakerState == null ? '—' : String(s.breakerState);
      });
    },
  };
})();
