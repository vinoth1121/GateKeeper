(function () {
  'use strict';

  const STATS_URL = '/api/public/stats';
  const POLL_INTERVAL_MILLIS = 1000;
  const REQUEST_TIMEOUT_MILLIS = 3000;
  const WAKE_FAILURE_THRESHOLD = 5;
  const THEME_STORAGE_KEY = 'gk-theme';
  const ROUTES = [
    'overview',
    'playground',
    'policies',
    'congestion',
    'redis-failure',
    'llm',
    'benchmarks',
  ];

  let subscribers = [];
  let consecutiveFailures = 0;

  function esc(value) {
    const map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&quot;', "'": '&#39;' };
    return String(value).replace(/[&<>"']/g, (ch) => map[ch]);
  }

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    const a = attrs || {};
    for (const key of Object.keys(a)) {
      const value = a[key];
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key === 'html') node.innerHTML = value;
      else if (key.startsWith('on') && typeof value === 'function') {
        node.addEventListener(key.slice(2), value);
      } else if (value === true) node.setAttribute(key, '');
      else if (value !== false && value !== null && value !== undefined) {
        node.setAttribute(key, String(value));
      }
    }
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false) continue;
      if (child instanceof Node) node.append(child);
      else node.append(document.createTextNode(String(child)));
    }
    return node;
  }

  function fmt(value) {
    return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString() : '—';
  }

  function api(path, opts) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MILLIS);
    return fetch(path, Object.assign({}, opts || {}, { signal: controller.signal })).finally(() =>
      clearTimeout(timer)
    );
  }

  const GK = {
    api,
    getStats() {
      return api(STATS_URL);
    },
    onStats(callback) {
      subscribers.push(callback);
      return function unsubscribe() {
        subscribers = subscribers.filter((entry) => entry !== callback);
      };
    },
    el,
    esc,
    fmt,
    nav(hash) {
      location.hash = hash;
    },
  };

  window.GK = GK;

  function currentRoute() {
    const hash = (location.hash || '').replace(/^#\/?/, '');
    return ROUTES.indexOf(hash) !== -1 ? hash : 'overview';
  }

  function render() {
    const route = currentRoute();
    const mount = document.getElementById('page-content');
    if (!mount) return;
    mount.innerHTML = '';
    const page = window.GKPages ? window.GKPages[route] : undefined;
    if (page && typeof page.init === 'function') {
      page.init(mount);
    } else {
      mount.append(
        el(
          'div',
          { class: 'panel' },
          el('h2', { class: 'panel-title', text: 'Page not found' }),
          el('p', { class: 'panel-note', text: 'No module is registered for this route.' })
        )
      );
    }
    for (const link of document.querySelectorAll('.main-nav a')) {
      const active = link.getAttribute('href') === '#/' + route;
      link.classList.toggle('active', active);
      if (active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
  }

  let trackDiagramInstance = null;

  function updateTrackDiagram(stats) {
    if (!trackDiagramInstance && window.GKTrackDiagram) {
      const container = document.getElementById('track-diagram');
      if (container) {
        trackDiagramInstance = new window.GKTrackDiagram(container);
      }
    }
    if (trackDiagramInstance) {
      trackDiagramInstance.update(stats);
    }
  }

  function poll() {
    GK.getStats()
      .then((response) => {
        if (!response.ok) throw new Error('stats request failed with status ' + response.status);
        return response.json();
      })
      .then((stats) => {
        consecutiveFailures = 0;
        const banner = document.getElementById('waking-banner');
        if (banner) banner.hidden = true;
        updateTrackDiagram(stats || {});
        for (const callback of subscribers.slice()) {
          try {
            callback(stats || {});
          } catch (err) {
            if (window.console && console.error) console.error('stats subscriber failed', err);
          }
        }
      })
      .catch(() => {
        consecutiveFailures += 1;
        if (consecutiveFailures >= WAKE_FAILURE_THRESHOLD) {
          const banner = document.getElementById('waking-banner');
          if (banner) banner.hidden = false;
        }
      });
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
  }

  function readStoredTheme() {
    try {
      const stored = localStorage.getItem(THEME_STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') return stored;
    } catch {}
    return null;
  }

  function initialTheme() {
    const stored = readStoredTheme();
    if (stored) return stored;
    const prefersLight =
      window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
    return prefersLight ? 'light' : 'dark';
  }

  function initThemeToggle() {
    applyTheme(initialTheme());
    const toggle = document.getElementById('theme-toggle');
    if (!toggle) return;
    toggle.addEventListener('click', () => {
      const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      applyTheme(next);
      try {
        localStorage.setItem(THEME_STORAGE_KEY, next);
      } catch {}
    });
  }

  function start() {
    initThemeToggle();
    render();
    poll();
    setInterval(poll, POLL_INTERVAL_MILLIS);
  }

  window.addEventListener('hashchange', render);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
