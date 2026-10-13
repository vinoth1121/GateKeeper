'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['playground'] = {
  init(container) {
    container.replaceChildren();

    const keySelect = GK.el('select', { class: 'key-select' },
      GK.el('option', { value: 'gk_demo_free_tenant' }, 'Free tenant (gk_demo_free_tenant)'),
      GK.el('option', { value: 'gk_demo_pro_tenant' }, 'Pro tenant (gk_demo_pro_tenant)'),
      GK.el('option', { value: 'gk_demo_ent_tenant' }, 'Enterprise tenant (gk_demo_ent_tenant)'));

    const pathInput = GK.el('input', { type: 'text', value: '/demo/hello' });

    const prioritySelect = GK.el('select', { class: 'priority-select' },
      GK.el('option', { value: 'None' }, 'None'),
      GK.el('option', { value: 'P0' }, 'P0'),
      GK.el('option', { value: 'P1' }, 'P1'),
      GK.el('option', { value: 'P2' }, 'P2'));

    const countInput = GK.el('input', { type: 'number', min: '1', max: '50', value: '5' });

    const sendBtn = GK.el('button', { class: 'btn btn-primary' }, 'Send requests');
    const progress = GK.el('p', { class: 'muted' });
    const errorBox = GK.el('div');
    const summary = GK.el('p', {});

    const field = (label, control) =>
      GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, label), control);

    const tbody = GK.el('tbody');
    const table = GK.el('table', { class: 'results-table' },
      GK.el('thead', {},
        GK.el('tr', {},
          GK.el('th', {}, '#'),
          GK.el('th', {}, 'Status'),
          GK.el('th', {}, 'RateLimit-Limit'),
          GK.el('th', {}, 'RateLimit-Remaining'),
          GK.el('th', {}, 'RateLimit-Reset'),
          GK.el('th', {}, 'Retry-After'),
          GK.el('th', {}, 'X-GateKeeper-Decision'),
          GK.el('th', {}, 'X-GateKeeper-Rule'))),
      tbody);

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('h2', {}, 'Playground'),
      GK.el('p', {}, 'Fire sequential requests through the gateway and watch the rate limiter respond.'),
      GK.el('div', { class: 'form-row' },
        field('API key', keySelect),
        field('Path', pathInput),
        field('Priority', prioritySelect),
        field('Count (1-50)', countInput)),
      sendBtn,
      progress,
      errorBox,
      table,
      summary));

    const headerOrDash = (res, name) => res.headers.get(name) || '-';

    sendBtn.addEventListener('click', async () => {
      const key = keySelect.value;
      const path = pathInput.value.trim() || '/demo/hello';
      const priority = prioritySelect.value;
      let count = parseInt(countInput.value, 10);
      if (!Number.isFinite(count)) count = 5;
      count = Math.min(50, Math.max(1, count));

      const headers = { Authorization: 'Bearer ' + key };
      if (priority !== 'None') headers['X-GateKeeper-Priority'] = priority;

      sendBtn.disabled = true;
      tbody.replaceChildren();
      summary.textContent = '';
      errorBox.replaceChildren();
      let allowed = 0;
      let denied = 0;

      try {
        for (let i = 1; i <= count; i++) {
          progress.textContent = 'Sending request ' + i + ' of ' + count + '...';
          const res = await GK.api(path, { headers });
          const status = res.status;
          if (status < 400) allowed += 1; else denied += 1;
          const statusClass = status < 300 ? 'ok' : (status < 500 ? 'warn' : 'err');
          tbody.appendChild(GK.el('tr', {},
            GK.el('td', {}, String(i)),
            GK.el('td', { class: statusClass }, String(status)),
            GK.el('td', {}, headerOrDash(res, 'RateLimit-Limit')),
            GK.el('td', {}, headerOrDash(res, 'RateLimit-Remaining')),
            GK.el('td', {}, headerOrDash(res, 'RateLimit-Reset')),
            GK.el('td', {}, headerOrDash(res, 'Retry-After')),
            GK.el('td', {}, headerOrDash(res, 'X-GateKeeper-Decision')),
            GK.el('td', {}, headerOrDash(res, 'X-GateKeeper-Rule'))));
        }
        progress.textContent = '';
        summary.textContent = count + ' request' + (count === 1 ? '' : 's') + ' sent: ' + allowed + ' allowed, ' + denied + ' denied.';
      } catch (err) {
        progress.textContent = '';
        summary.textContent = '';
        errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Could not reach the gateway at ' + GK.esc(path) + '. Check that the gateway is running and try again.'));
      } finally {
        sendBtn.disabled = false;
      }
    });
  }
};
