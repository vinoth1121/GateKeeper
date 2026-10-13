'use strict';

window.GKPages = window.GKPages || {};

let policiesUnsubscribe = null;

window.GKPages['policies'] = {
  init(container) {
    container.replaceChildren();

    if (typeof policiesUnsubscribe === 'function') {
      try { policiesUnsubscribe(); } catch (e) { /* detached */ }
    }

    const field = (label, control) =>
      GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, label), control);

    const whenInput = GK.el('textarea', {
      rows: 4,
      style: 'font-family: var(--font-mono); font-size: 0.9rem; background: #07090c; border: 1px solid var(--edge); border-radius: 6px; padding: 0.75rem; color: #38bdf8;'
    });
    whenInput.value = 'request.path == "/demo/hello"';

    const capacityInput = GK.el('input', { type: 'number', min: '1', value: '20' });
    const refillInput = GK.el('input', { type: 'number', min: '0', value: '10' });

    // Preset CEL snippets
    const snippet1 = GK.el('button', { class: 'btn-fast', type: 'button' }, 'Path match: /demo/hello');
    snippet1.addEventListener('click', () => { whenInput.value = 'request.path == "/demo/hello"'; });

    const snippet2 = GK.el('button', { class: 'btn-fast', type: 'button' }, 'Tier check: tenant.tier == "FREE"');
    snippet2.addEventListener('click', () => { whenInput.value = 'tenant.tier == "FREE"'; });

    const snippet3 = GK.el('button', { class: 'btn-fast', type: 'button' }, 'Method + Header check');
    snippet3.addEventListener('click', () => { whenInput.value = 'request.method == "GET" && tenant.id == "demo-sandbox"'; });

    const validateBtn = GK.el('button', { class: 'btn-fast' }, '🧪 Dry-Run Validate');
    const publishBtn = GK.el('button', { class: 'btn btn-primary' }, '🚀 Publish to Sandbox');

    const versionBadge = GK.el('span', { class: 'brand-badge' }, 'VERSION: —');
    const dryRunBox = GK.el('div', { style: 'margin-top: 0.5rem;' });
    const publishBox = GK.el('div', { style: 'margin-top: 0.5rem;' });

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
        GK.el('h2', {}, 'Google CEL Policy Studio & Sandbox Publisher'),
        versionBadge
      ),
      GK.el('p', { class: 'panel-note' }, 'Author rules using Google Common Expression Language (CEL). Rules are compiled, type-checked at publish time, and evaluated at the edge with zero subshell overhead.'),
      GK.el('div', { class: 'btn-group-fast', style: 'margin-bottom: 0.5rem;' },
        GK.el('span', { style: 'font-size: 0.8rem; color: var(--dim); align-self: center; margin-right: 0.25rem;' }, 'Templates:'),
        snippet1, snippet2, snippet3
      ),
      field('CEL Match Expression (when)', whenInput),
      GK.el('div', { class: 'form-row' },
        field('Token Bucket Capacity', capacityInput),
        field('Refill Rate (Tokens/Sec)', refillInput)),
      GK.el('div', { class: 'btn-group-fast', style: 'margin-top: 0.75rem;' }, validateBtn, publishBtn),
      dryRunBox,
      publishBox
    ));

    const readLimit = () => {
      let capacity = parseInt(capacityInput.value, 10);
      let refillPerSec = parseInt(refillInput.value, 10);
      if (!Number.isFinite(capacity) || capacity < 1) capacity = 20;
      if (!Number.isFinite(refillPerSec) || refillPerSec < 0) refillPerSec = 10;
      return { algorithm: 'token_bucket', capacity, refillPerSec };
    };

    const renderVersion = () => {
      const stats = GK.getStats();
      const version = stats ? (stats.policyVersion != null ? stats.policyVersion : null) : null;
      versionBadge.textContent = 'POLICY VERSION: ' + (version == null ? '—' : `v${version}`);
    };
    renderVersion();
    policiesUnsubscribe = GK.onStats(renderVersion);

    const describeError = (json, text) => {
      if (json) {
        let message = 'Validation error.';
        const candidate = json.error || json.message || json.detail;
        if (candidate) message = String(candidate);
        if (json.position != null) message += ` (at position ${json.position})`;
        return message;
      }
      if (text) return text.slice(0, 300);
      return 'The gateway returned an error.';
    };

    const postJson = (path, body) => GK.api(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    validateBtn.addEventListener('click', async () => {
      validateBtn.disabled = true;
      dryRunBox.replaceChildren(GK.el('p', { class: 'muted' }, 'Compiling and validating CEL expression...'));
      try {
        const res = await postJson('/api/policies/dry-run', {
          when: whenInput.value,
          expression: whenInput.value,
          limit: readLimit(),
          request: { path: '/demo/hello', method: 'GET', headers: {}, ip: '203.0.113.9' },
          tenant: { id: 'demo-sandbox', tier: 'FREE', tags: [] },
          keyId: 'demo'
        });
        const text = await res.text();
        let json = null;
        try { json = JSON.parse(text); } catch (e) { json = null; }
        if (res.ok) {
          const matched = Boolean(json && (json.matched === true || json.valid === true));
          dryRunBox.replaceChildren(GK.el('div', {
            class: 'panel ok',
            style: 'background: rgba(16, 185, 129, 0.1); border-color: rgba(16, 185, 129, 0.3); color: var(--green);'
          }, `✔ Expression Valid! CEL compiled cleanly. Matched: ${matched}`));
        } else {
          dryRunBox.replaceChildren(GK.el('div', {
            class: 'panel err',
            style: 'background: rgba(239, 68, 68, 0.1); border-color: rgba(239, 68, 68, 0.3); color: var(--red);'
          }, '✖ Compile error: ' + GK.esc(describeError(json, text))));
        }
      } catch (err) {
        dryRunBox.replaceChildren(GK.el('div', { class: 'panel err' }, 'Could not reach the gateway: ' + err.message));
      } finally {
        validateBtn.disabled = false;
      }
    });

    publishBtn.addEventListener('click', async () => {
      publishBtn.disabled = true;
      publishBox.replaceChildren(GK.el('p', { class: 'muted' }, 'Publishing policy to sandbox environment...'));
      try {
        const res = await postJson('/api/sandbox/policies', {
          when: whenInput.value,
          expression: whenInput.value,
          limit: readLimit()
        });
        const text = await res.text();
        let json = null;
        try { json = JSON.parse(text); } catch (e) { json = null; }
        if (res.ok) {
          let version = json ? (json.version != null ? json.version : json.policyVersion) : null;
          publishBox.replaceChildren(GK.el('div', {
            class: 'panel ok',
            style: 'background: rgba(16, 185, 129, 0.1); border-color: rgba(16, 185, 129, 0.3); color: var(--green);'
          }, `🚀 Published successfully! New active sandbox policy version: v${version || 'new'}`));
          renderVersion();
        } else {
          publishBox.replaceChildren(GK.el('div', {
            class: 'panel err',
            style: 'background: rgba(239, 68, 68, 0.1); border-color: rgba(239, 68, 68, 0.3); color: var(--red);'
          }, '✖ Publish failed: ' + GK.esc(describeError(json, text))));
        }
      } catch (err) {
        publishBox.replaceChildren(GK.el('div', { class: 'panel err' }, 'Could not reach the gateway: ' + err.message));
      } finally {
        publishBtn.disabled = false;
      }
    });
  }
};
