/**
 * GateKeeper Interactive Signal Box Pipeline Track Diagram
 * Renders an animated SVG topology representing client traffic, gateway edge,
 * distributed Redis cluster via Toxiproxy, and mock upstream with dynamic circuit breaker state.
 */
(function () {
  'use strict';

  class TrackDiagram {
    constructor(container) {
      this.container = container;
      this.render();
    }

    render() {
      const GK = window.GK;
      this.container.innerHTML = '';

      const markup = `
      <div class="track-header">
        <span>Signal Interlocking Track &mdash; Real-Time Data Plane</span>
        <span id="track-status-pill" class="brand-badge live-dot">ALL SIGNALS GREEN</span>
      </div>
      <div class="track-pipeline">
        <!-- Client Node -->
        <div class="track-node" id="node-client">
          <div class="track-node-icon" style="color: var(--green);">💻</div>
          <div class="track-node-title">Client</div>
          <div class="track-node-sub"><span class="lamp lamp-green"></span> Active</div>
        </div>

        <!-- Wire: Client -> Gateway -->
        <div class="track-connector">
          <svg viewBox="0 0 32 18">
            <line class="wire-line active" x1="0" y1="9" x2="32" y2="9" />
          </svg>
        </div>

        <!-- Gateway Node -->
        <div class="track-node" id="node-gateway">
          <div class="track-node-icon" style="color: var(--cyan);">⚡</div>
          <div class="track-node-title">Gateway Edge</div>
          <div class="track-node-sub"><span id="node-gateway-rps">0.0</span> rps</div>
        </div>

        <!-- Wire: Gateway -> Redis -->
        <div class="track-connector" id="connector-redis">
          <svg viewBox="0 0 32 18">
            <line class="wire-line active" id="wire-redis" x1="0" y1="9" x2="32" y2="9" />
          </svg>
        </div>

        <!-- Redis Node -->
        <div class="track-node" id="node-redis">
          <div class="track-node-icon" id="icon-redis" style="color: var(--green);">🗄️</div>
          <div class="track-node-title">Redis Guard</div>
          <div class="track-node-sub" id="sub-redis"><span class="lamp lamp-green"></span> CLOSED</div>
        </div>

        <!-- Wire: Gateway -> Upstream -->
        <div class="track-connector">
          <svg viewBox="0 0 32 18">
            <line class="wire-line active" x1="0" y1="9" x2="32" y2="9" />
          </svg>
        </div>

        <!-- Upstream Node -->
        <div class="track-node" id="node-upstream">
          <div class="track-node-icon" style="color: var(--violet);">🌐</div>
          <div class="track-node-title">Upstream</div>
          <div class="track-node-sub"><span class="lamp lamp-green"></span> UP</div>
        </div>
      </div>
      `;

      this.container.className = 'track-container';
      this.container.innerHTML = markup;

      this.statusPill = this.container.querySelector('#track-status-pill');
      this.gatewayRps = this.container.querySelector('#node-gateway-rps');
      this.wireRedis = this.container.querySelector('#wire-redis');
      this.iconRedis = this.container.querySelector('#icon-redis');
      this.subRedis = this.container.querySelector('#sub-redis');
    }

    update(stats) {
      if (!stats) return;

      const rps = Number(stats.requestsPerSecond || stats.rps || 0);
      if (this.gatewayRps) {
        this.gatewayRps.textContent = rps.toFixed(1);
      }

      const rawBreaker = stats.circuitBreakerState || stats.breakerState || 'closed';
      const breaker = String(rawBreaker).toLowerCase();

      if (breaker === 'open' || rawBreaker === 2) {
        if (this.wireRedis) this.wireRedis.className = 'wire-line broken';
        if (this.iconRedis) this.iconRedis.style.color = 'var(--red)';
        if (this.subRedis) this.subRedis.innerHTML = '<span class="lamp lamp-red"></span> OPEN (FAIL-OPEN)';
        if (this.statusPill) {
          this.statusPill.className = 'brand-badge';
          this.statusPill.style.background = 'rgba(239, 68, 68, 0.15)';
          this.statusPill.style.color = 'var(--red)';
          this.statusPill.style.borderColor = 'rgba(239, 68, 68, 0.4)';
          this.statusPill.textContent = '⚡ REDIS OUTAGE: FAIL-OPEN ACTIVE';
        }
      } else if (breaker === 'half_open' || rawBreaker === 1) {
        if (this.wireRedis) this.wireRedis.className = 'wire-line';
        if (this.iconRedis) this.iconRedis.style.color = 'var(--amber)';
        if (this.subRedis) this.subRedis.innerHTML = '<span class="lamp lamp-amber"></span> HALF-OPEN';
        if (this.statusPill) {
          this.statusPill.className = 'brand-badge';
          this.statusPill.style.background = 'rgba(245, 158, 11, 0.15)';
          this.statusPill.style.color = 'var(--amber)';
          this.statusPill.style.borderColor = 'rgba(245, 158, 11, 0.4)';
          this.statusPill.textContent = 'PROBING REDIS RECOVERY';
        }
      } else {
        if (this.wireRedis) this.wireRedis.className = 'wire-line active';
        if (this.iconRedis) this.iconRedis.style.color = 'var(--green)';
        if (this.subRedis) this.subRedis.innerHTML = '<span class="lamp lamp-green"></span> CLOSED';
        if (this.statusPill) {
          this.statusPill.className = 'brand-badge live-dot';
          this.statusPill.style.background = 'rgba(16, 185, 129, 0.12)';
          this.statusPill.style.color = 'var(--green)';
          this.statusPill.style.borderColor = 'rgba(16, 185, 129, 0.3)';
          this.statusPill.textContent = 'ALL SIGNALS GREEN';
        }
      }
    }
  }

  window.GKTrackDiagram = TrackDiagram;
})();
