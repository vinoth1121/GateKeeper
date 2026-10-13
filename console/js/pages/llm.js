'use strict';

window.GKPages = window.GKPages || {};

window.GKPages['llm'] = {
  init(container) {
    container.replaceChildren();

    const field = (label, control) =>
      GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, label), control);

    const promptInput = GK.el('textarea', { rows: 4 });
    promptInput.value = 'Explain rate limiting in one paragraph';
    const maxTokensInput = GK.el('input', { type: 'number', min: '1', value: '120' });
    const streamToggle = GK.el('input', { type: 'checkbox' });
    streamToggle.checked = true;

    const sendBtn = GK.el('button', { class: 'btn btn-primary' }, 'Send');
    const statusLine = GK.el('p', { class: 'muted' });
    const errorBox = GK.el('div');
    const outputBox = GK.el('div');
    const tokenBox = GK.el('div');

    container.appendChild(GK.el('div', { class: 'panel' },
      GK.el('h2', {}, 'LLM metering'),
      GK.el('p', {}, 'Send a chat completion through the gateway. The gateway estimates tokens up front, then reconciles the actual usage.'),
      field('Prompt', promptInput),
      GK.el('div', { class: 'form-row' },
        field('Max tokens', maxTokensInput),
        GK.el('label', { class: 'field' }, GK.el('span', { class: 'field-label' }, 'Stream'), streamToggle)),
      sendBtn,
      statusLine,
      errorBox,
      outputBox,
      tokenBox,
      GK.el('p', { class: 'muted' }, 'The gateway reconciles estimated vs actual in Redis \u2014 see the Grafana LLM dashboard.')));

    sendBtn.addEventListener('click', async () => {
      const prompt = promptInput.value;
      let maxTokens = parseInt(maxTokensInput.value, 10);
      if (!Number.isFinite(maxTokens) || maxTokens < 1) maxTokens = 120;
      const stream = streamToggle.checked;

      sendBtn.disabled = true;
      statusLine.textContent = stream ? 'Streaming completion...' : 'Waiting for the completion...';
      errorBox.replaceChildren();
      outputBox.replaceChildren();
      tokenBox.replaceChildren();

      const renderTokens = (usage) => {
        const estimate = Math.ceil(prompt.length / 4) + maxTokens;
        const actual = usage ? (usage.prompt_tokens + usage.completion_tokens) : null;
        tokenBox.replaceChildren(GK.el('div', { class: 'token-row' },
          GK.el('strong', {}, 'Estimated vs actual tokens'),
          GK.el('span', {}, 'client-side estimate: ' + estimate + ' tokens'),
          GK.el('span', {}, 'actual: ' + (actual == null ? 'unavailable' : actual + ' tokens'))));
      };

      try {
        const res = await GK.api('/llm/v1/chat/completions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: 'Bearer gk_demo_pro_tenant' },
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
          let detail = text;
          try { detail = JSON.stringify(JSON.parse(text), null, 2); } catch (e) { detail = text; }
          const errorPanel = GK.el('div', { class: 'panel err' },
            GK.el('p', {}, 'Rate limited by the LLM token budget (requests per minute or tokens per minute).'));
          const detailPre = GK.el('pre', {});
          detailPre.textContent = detail;
          errorPanel.appendChild(detailPre);
          if (retryAfter) {
            errorPanel.appendChild(GK.el('p', {}, 'Retry-After: ' + retryAfter + ' seconds'));
          }
          errorBox.replaceChildren(errorPanel);
          statusLine.textContent = '';
          return;
        }

        if (!res.ok) {
          errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
            'The gateway returned HTTP ' + res.status + ' for the completion request.'));
          statusLine.textContent = '';
          return;
        }

        if (!stream) {
          const json = await res.json();
          const pre = GK.el('pre', { class: 'llm-json' });
          pre.textContent = JSON.stringify(json, null, 2);
          outputBox.replaceChildren(pre);
          const usage = json && json.usage
            ? { prompt_tokens: json.usage.prompt_tokens, completion_tokens: json.usage.completion_tokens }
            : null;
          renderTokens(usage);
          statusLine.textContent = '';
          return;
        }

        const pre = GK.el('pre', { class: 'llm-stream' });
        outputBox.replaceChildren(pre);

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
            pre.appendChild(document.createTextNode(delta.content));
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

        statusLine.textContent = '';
        renderTokens(usage);
      } catch (err) {
        statusLine.textContent = '';
        errorBox.replaceChildren(GK.el('div', { class: 'panel err' },
          'Could not reach the gateway for the completion request. Check that it is running and try again.'));
      } finally {
        sendBtn.disabled = false;
      }
    });
  }
};
