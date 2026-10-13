'use strict';

window.GKPages = window.GKPages || {};

let policiesUnsubscribe = null;

window.GKPages['policies'] = {
  init(container) {
    container.replaceChildren();

    if (typeof policiesUnsubscribe === 'function') {
      try { policiesUnsubscribe(); } catch (e) { /* already detached */ }
    }

    const field = (label, control) =>
      GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, label), control);

    const whenInput = GK.el('textarea', { rows: 4 });
    whenInput.value = 'request.path == "/demo/hello"';
    const capacityInput = GK.el('input', { type: 'number', min: '1', value: '20' });
    const refillInput = GK.el('input', { type: 'number', min: '0', value: '10' });

    const validateBtn = GK.el('button', { class: 'btn' }, 'Validate with dry run');
    const publishBtn = GK.el('button', { class: 'btn btn-primary' }, 'Publish to sandbox');

    const versionLine = GK.el('p', {}, 'Current sandbox policy version: -');
    const dryRunBox = GK.el('div');
    const publishBox = GK.el('div');

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('h2', {}, 'Policies'),
      GK.el('p', {}, 'Write a CEL condition and a token bucket limit, dry-run it against a sample request, then publish it to the sandbox.'),
      field('CEL condition (when)', whenInput),
      GK.el('div', { class: 'form-row' },
        field('Capacity', capacityInput),
        field('Refill per second', refillInput)),
      GK.el('div', { class: 'form-row' }, validateBtn, publishBtn),
      versionLine,
      dryRunBox,
      publishBox,
      GK.el('p', { class: 'muted' }, 'Version history and rollback need the admin key on a real deployment.')));

    const readLimit = () => {
      let capacity = parseInt(capacityInput.value, 10);
      let refillPerSec = parseInt(refillInput.value, 10);
      if (!Number.isFinite(capacity) || capacity < 1) capacity = 20;
      if (!Number.isFinite(refillPerSec) || refillPerSec < 0) refillPerSec = 10;
      return { algorithm: 'token_bucket', capacity, refillPerSec };
    };

    const renderVersion = () => {
      const stats = GK.getStats();
      const version = stats ? stats.policyVersion : null;
      versionLine.textContent = 'Current sandbox policy version: ' + (version == null ? '-' : String(version));
    };
    renderVersion();
    policiesUnsubscribe = GK.onStats(renderVersion);

    const describeError = (json, text) => {
      if (json) {
        let message = 'The gateway returned an error.';
        const candidate = json.error || json.message || json.detail;
        if (candidate) message = String(candidate);
        if (json.position != null) message += ' (error at position ' + json.position + ')';
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
      dryRunBox.replaceChildren(GK.el('p', { class: 'muted' }, 'Running dry run...'));
      try {
        const res = await postJson('/api/policies/dry-run', {
          when: whenInput.value,
          limit: readLimit(),
          request: { path: '/demo/hello', method: 'GET', headers: {}, ip: '203.0.113.9' },
          tenant: { id: 'demo-sandbox', tier: 'FREE', tags: [] },
          keyId: 'demo'
        });
        const text = await res.text();
        let json = null;
        try { json = JSON.parse(text); } catch (e) { json = null; }
        if (res.ok) {
          const matched = Boolean(json && json.matched === true);
          dryRunBox.replaceChildren(GK.el('div', { class: matched ? 'panel ok' : 'panel' },
            matched
              ? 'Matched: true. The sample request hits this policy and would be rate limited.'
              : 'Matched: false. The sample request does not hit this policy.'));
        } else {
          dryRunBox.replaceChildren(GK.el('div', { class: 'panel err' },
            'Dry run failed: ' + GK.esc(describeError(json, text))));
        }
      } catch (err) {
        dryRunBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Could not reach the gateway. Check that it is running and try again.'));
      } finally {
        validateBtn.disabled = false;
      }
    });

    publishBtn.addEventListener('click', async () => {
      publishBtn.disabled = true;
      publishBox.replaceChildren(GK.el('p', { class: 'muted' }, 'Publishing to the sandbox...'));
      try {
        const res = await postJson('/api/sandbox/policies', {
          when: whenInput.value,
          limit: readLimit()
        });
        const text = await res.text();
        let json = null;
        try { json = JSON.parse(text); } catch (e) { json = null; }
        if (res.ok) {
          let version = null;
          if (json) {
            if (json.version != null) version = json.version;
            else if (json.policyVersion != null) version = json.policyVersion;
          }
          publishBox.replaceChildren(GK.el('div', { class: 'panel ok' },
            version != null ? 'Published as version ' + String(version) + '.' : 'Published to the sandbox.'));
          renderVersion();
        } else {
          publishBox.replaceChildren(GK.el('div', { class: 'panel err' },
            'Publish failed: ' + GK.esc(describeError(json, text))));
        }
      } catch (err) {
        publishBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Could not reach the gateway. Check that it is running and try again.'));
      } finally {
        publishBtn.disabled = false;
      }
    });
  }
};
