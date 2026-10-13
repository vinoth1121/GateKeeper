'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['benchmarks'] = {
  init(container) {
    container.replaceChildren();

    const body = GK.el('div');
    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('h2', {}, 'Benchmarks'),
      GK.el('p', {}, 'Throughput and overhead numbers captured from real k6 load runs against the gateway.'),
      body));

    body.replaceChildren(GK.el('div', { class: 'skel' }));

    GK.api('/api/benchmarks').then(async (res) => {
      const text = await res.text();
      if (res.status === 404 || text.toLowerCase().indexOf('not generated') !== -1) {
        body.replaceChildren(GK.el('p', {},
          'Benchmarks not generated yet. Run scripts/run_load.ps1 to produce them from real k6 runs.'));
        return;
      }
      const pre = GK.el('pre', { class: 'bench', style: 'max-height: 70vh; overflow: auto;' });
      pre.textContent = text;
      body.replaceChildren(pre);
    }).catch(() => {
      body.replaceChildren(GK.el('div', { class: 'panel err' },
        'Could not load benchmarks from the gateway. Check that it is running and try again.'));
    });
  }
};
