const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

async function capture() {
  const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
  const outDir = path.resolve(__dirname, '../docs/screenshots');
  if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

  const tempDir = path.resolve(process.env.TEMP || 'C:\\Temp', 'chrome_cdp_' + Date.now());

  const chrome = spawn(chromePath, [
    '--headless=new',
    '--disable-gpu',
    '--remote-debugging-port=9222',
    `--user-data-dir=${tempDir}`,
    '--window-size=1440,900',
    'about:blank'
  ]);

  let wsUrl = null;
  for (let i = 0; i < 30; i++) {
    await new Promise(r => setTimeout(r, 300));
    try {
      const res = await fetch('http://127.0.0.1:9222/json/version');
      const data = await res.json();
      wsUrl = data.webSocketDebuggerUrl;
      if (wsUrl) break;
    } catch (e) {}
  }

  if (!wsUrl) {
    chrome.kill();
    throw new Error('Chrome remote debugging did not respond');
  }

  console.log('Connected to Chrome CDP:', wsUrl);
  const ws = new WebSocket(wsUrl);

  let reqId = 1;
  const pending = new Map();
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg.result);
      pending.delete(msg.id);
    }
  };

  await new Promise(r => ws.onopen = r);

  function send(method, params = {}) {
    return new Promise((resolve) => {
      const id = reqId++;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, method, params }));
    });
  }

  const { targetId } = await send('Target.createTarget', { url: 'http://localhost:8080/console/' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });

  function sendSession(method, params = {}) {
    return new Promise((resolve) => {
      const id = reqId++;
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, sessionId, method, params }));
    });
  }

  await sendSession('Page.enable');

  console.log('Waiting for console metrics and charts to render...');
  await new Promise(r => setTimeout(r, 4000));

  const snap1 = await sendSession('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(outDir, 'console-dashboard.png'), Buffer.from(snap1.data, 'base64'));
  console.log('Saved docs/screenshots/console-dashboard.png');

  // Click on playground tab
  await sendSession('Runtime.evaluate', {
    expression: `
      const tab = Array.from(document.querySelectorAll('a, button, nav a')).find(el => el.textContent.includes('Playground'));
      if (tab) tab.click();
    `
  });
  await new Promise(r => setTimeout(r, 2000));

  const snap2 = await sendSession('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(outDir, 'token-bucket-playground.png'), Buffer.from(snap2.data, 'base64'));
  console.log('Saved docs/screenshots/token-bucket-playground.png');

  // Trigger Send Requests
  await sendSession('Runtime.evaluate', {
    expression: `
      const btn = Array.from(document.querySelectorAll('button')).find(el => el.textContent.includes('Send Requests'));
      if (btn) btn.click();
    `
  });
  await new Promise(r => setTimeout(r, 2000));

  const snap3 = await sendSession('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(outDir, 'console-live-requests.png'), Buffer.from(snap3.data, 'base64'));
  console.log('Saved docs/screenshots/console-live-requests.png');

  ws.close();
  chrome.kill();
  console.log('Capture completed successfully!');
}

capture().catch(err => {
  console.error(err);
  process.exit(1);
});
