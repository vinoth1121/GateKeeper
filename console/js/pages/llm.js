'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['llm'] = {
  init(container) {
    container.replaceChildren();

    const field = (label, control) =>
      GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, label), control);

    const promptInput = GK.el('textarea', {
      rows: 3,
      style: 'font-family: var(--font-mono); font-size: 0.9rem; background: #07090c; border: 1px solid var(--edge); border-radius: 6px; padding: 0.75rem; color: #a78bfa;'
    });
    promptInput.value = 'Explain how distributed token leasing works in GateKeeper';

    const maxTokensInput = GK.el('input', { type: 'number', min: '1', value: '120' });
    const streamToggle = GK.el('input', { type: 'checkbox' });
    streamToggle.checked = true;

    // Preset prompts
    const prompt1 = GK.el('button', { class: 'btn-fast', type: 'button' }, '💡 Token Leasing');
    prompt1.addEventListener('click', () => { promptInput.value = 'Explain how distributed token leasing works in GateKeeper'; });

    const prompt2 = GK.el('button', { class: 'btn-fast', type: 'button' }, '🧵 Virtual Threads');
    prompt2.addEventListener('click', () => { promptInput.value = 'Why does Java 21 Virtual Threads scale to 50,000 concurrent keep-alive connections?'; });

    const prompt3 = GK.el('button', { class: 'btn-fast', type: 'button' }, '🛡️ Circuit Breakers');
    prompt3.addEventListener('click', () => { promptInput.value = 'How does Resilience4j protect against Redis timeout cascades?'; });

    const sendBtn = GK.el('button', { class: 'btn btn-primary' }, '✨ Stream Completion');
    const statusLine = GK.el('p', { class: 'muted', style: 'margin: 0.5rem 0;' });
    const metricsBar = GK.el('div', { class: 'stat-card-grid', style: 'margin: 0.75rem 0; display: none;' });
    const errorBox = GK.el('div');

    const outputBox = GK.el('div', {
      class: 'response-drawer',
      style: 'min-height: 120px; font-family: var(--font-mono); font-size: 0.9rem; line-height: 1.6; white-space: pre-wrap; color: var(--text); background: #07090c; border: 1px solid var(--edge);'
    });
    outputBox.textContent = 'LLM completions and streaming Server-Sent Events will appear here...';

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('div', { style: 'display: flex; justify-content: space-between; align-items: center;' },
        GK.el('h2', {}, 'Streaming LLM Meter & Token Reconciliation'),
        GK.el('span', { class: 'brand-badge' }, 'SSE STREAMING PROXY')
      ),
      GK.el('p', { class: 'panel-note' }, 'Proxies LLM completions, performs up-front token budget reservation in Redis, and reconciles exact prompt + completion tokens on stream termination.'),
      GK.el('div', { class: 'btn-group-fast', style: 'margin-bottom: 0.5rem;' },
        GK.el('span', { style: 'font-size: 0.8rem; color: var(--dim); align-self: center; margin-right: 0.25rem;' }, 'Prompts:'),
        prompt1, prompt2, prompt3
      ),
      field('User Prompt', promptInput),
      GK.el('div', { class: 'form-row' },
        field('Max Tokens Budget', maxTokensInput),
        GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, 'SSE Streaming'), streamToggle)),
      GK.el('div', { class: 'btn-group-fast', style: 'margin-top: 0.5rem;' }, sendBtn),
      statusLine,
      metricsBar,
      errorBox,
      GK.el('div', { style: 'margin-top: 0.75rem;' }, outputBox)
    ));

    sendBtn.addEventListener('click', async () => {
      const prompt = promptInput.value;
      let maxTokens = parseInt(maxTokensInput.value, 10);
      if (!Number.isFinite(maxTokens) || maxTokens < 1) maxTokens = 120;
      const stream = streamToggle.checked;

      sendBtn.disabled = true;
      statusLine.textContent = stream ? 'Connecting to upstream SSE stream...' : 'Waiting for completion...';
      errorBox.replaceChildren();
      outputBox.textContent = '';
      metricsBar.style.display = 'none';
      metricsBar.replaceChildren();

      const tStart = performance.now();
      let ttftMs = null;

      try {
        const res = await GK.api('/llm/v1/chat/completions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: 'Bearer gk_demo_pro_tenant', 'X-API-Key': 'gk_demo_pro_tenant' },
          body: JSON.stringify({
            model: 'mock-gpt',
            messages: [{ role: 'user', content: prompt }],
            max_tokens: maxTokens,
            stream
          })
        });

        if (res.status === 429) {
          const text = await res.text();
          const retryAfter = res.headers.get('Retry-After');
          errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
            `Rate limited by LLM token budget. Retry-After: ${retryAfter || '1'}s. Details: ${text}`));
          statusLine.textContent = '';
          return;
        }

        if (!res.ok) {
          errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
            `Gateway returned HTTP ${res.status} for LLM completion.`));
          statusLine.textContent = '';
          return;
        }

        if (!stream) {
          const json = await res.json();
          const totalMs = (performance.now() - tStart).toFixed(1);
          outputBox.textContent = (json.choices && json.choices[0] && json.choices[0].message)
            ? json.choices[0].message.content
            : JSON.stringify(json, null, 2);

          const usage = json.usage || { prompt_tokens: Math.ceil(prompt.length / 4), completion_tokens: 35 };
          statusLine.textContent = '';
          renderMetrics(usage, null, totalMs);
          return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let usage = null;
        let stop = false;

        const handlePayload = (payload) => {
          if (payload.trim() === '[DONE]') return true;
          let event = null;
          try { event = JSON.parse(payload); } catch (e) { return false; }
          const choice = event && event.choices && event.choices[0];
          const delta = choice ? choice.delta : null;
          if (delta && typeof delta.content === 'string' && delta.content) {
            if (ttftMs === null) {
              ttftMs = (performance.now() - tStart).toFixed(1);
            }
            outputBox.textContent += delta.content;
          }
          if (event && event.usage) {
            usage = { prompt_tokens: event.usage.prompt_tokens, completion_tokens: event.usage.completion_tokens };
          }
          return false;
        };

        while (!stop) {
          const chunk = await reader.read();
          if (chunk.done) break;
          buffer += decoder.decode(chunk.value, { stream: true });
          let nl;
          while ((nl = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, nl);
            buffer = buffer.slice(nl + 1);
            if (line.startsWith('data: ')) {
              if (handlePayload(line.slice(6))) { stop = true; break; }
            }
          }
        }
        if (!stop && buffer.startsWith('data: ')) handlePayload(buffer.slice(6));

        const totalMs = (performance.now() - tStart).toFixed(1);
        statusLine.textContent = '';
        renderMetrics(usage || { prompt_tokens: Math.ceil(prompt.length / 4), completion_tokens: 42 }, ttftMs, totalMs);
      } catch (err) {
        statusLine.textContent = '';
        errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Error during completion stream: ' + err.message));
      } finally {
        sendBtn.disabled = false;
      }
    });

    function renderMetrics(usage, ttft, total) {
      metricsBar.style.display = 'grid';
      metricsBar.replaceChildren(
        GK.el('div', { class: 'stat-card' },
          GK.el('div', { class: 'stat-card-label' }, 'Prompt Tokens'),
          GK.el('div', { class: 'stat-card-value', style: 'color: var(--cyan);' }, String(usage.prompt_tokens))
        ),
        GK.el('div', { class: 'stat-card' },
          GK.el('div', { class: 'stat-card-label' }, 'Completion Tokens'),
          GK.el('div', { class: 'stat-card-value', style: 'color: var(--violet);' }, String(usage.completion_tokens))
        ),
        GK.el('div', { class: 'stat-card' },
          GK.el('div', { class: 'stat-card-label' }, 'Time to First Token (TTFT)'),
          GK.el('div', { class: 'stat-card-value', style: 'color: var(--green);' }, ttft ? `${ttft} ms` : '—')
        ),
        GK.el('div', { class: 'stat-card' },
          GK.el('div', { class: 'stat-card-label' }, 'Total Stream Time'),
          GK.el('div', { class: 'stat-card-value' }, `${total} ms`)
        )
      );
    }
  }
};
