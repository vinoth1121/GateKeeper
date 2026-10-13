/**
 * GateKeeper Visual Token Bucket Simulator Component
 * Provides real-time interactive animated visualization of token consumption,
 * liquid capacity level, refill rate, and throttled spillover state.
 */
(function () {
  'use strict';

  class TokenBucketWidget {
    constructor(container, options) {
      this.container = container;
      this.options = Object.assign(
        {
          capacity: 20,
          currentTokens: 20,
          refillPerSec: 1.0,
        },
        options || {}
      );

      this.capacity = this.options.capacity;
      this.tokens = this.options.currentTokens;
      this.refillRate = this.options.refillPerSec;
      this.lastRefillTime = Date.now();
      this.retryAfterTimer = null;

      this.renderDOM();
      this.startRefillLoop();
    }

    renderDOM() {
      const GK = window.GK;
      this.container.innerHTML = '';

      this.liquid = GK.el('div', { class: 'bucket-liquid' },
        GK.el('div', { class: 'bucket-wave' })
      );

      this.tokenCountEl = GK.el('div', { class: 'bucket-token-count', text: String(Math.floor(this.tokens)) });
      this.capacityLabelEl = GK.el('div', { class: 'bucket-capacity-label', text: `CAPACITY ${this.capacity}` });

      const overlay = GK.el('div', { class: 'bucket-gauge-overlay' },
        this.tokenCountEl,
        this.capacityLabelEl
      );

      this.cylinder = GK.el('div', { class: 'bucket-cylinder-wrap' },
        this.liquid,
        overlay
      );

      this.statusBadge = GK.el('div', {
        class: 'bucket-spillover-badge normal',
        text: '● Tokens Available'
      });

      this.rateInfo = GK.el('div', {
        style: 'font-size: 0.75rem; color: var(--dim); margin-top: 0.4rem; font-variant-numeric: tabular-nums;',
        text: `Refill rate: +${this.refillRate}/sec`
      });

      const wrapper = GK.el('div', { class: 'token-bucket-widget' },
        GK.el('div', {
          style: 'font-size: 0.8rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 0.75rem; color: var(--dim);',
          text: 'Rate Limiter Token Bucket'
        }),
        this.cylinder,
        this.statusBadge,
        this.rateInfo
      );

      this.container.appendChild(wrapper);
      this.updateVisuals();
    }

    consume(cost = 1) {
      if (this.tokens >= cost) {
        this.tokens = Math.max(0, this.tokens - cost);
        this.updateVisuals();
        return true;
      }
      this.triggerThrottle(1);
      return false;
    }

    syncHeaders(remaining, limit, retryAfter) {
      if (limit != null && !isNaN(Number(limit))) {
        this.capacity = Number(limit);
        this.capacityLabelEl.textContent = `CAPACITY ${this.capacity}`;
      }
      if (remaining != null && !isNaN(Number(remaining))) {
        this.tokens = Number(remaining);
      }
      if (retryAfter != null && Number(retryAfter) > 0) {
        this.triggerThrottle(Number(retryAfter));
      } else {
        this.clearThrottle();
      }
      this.updateVisuals();
    }

    triggerThrottle(retrySecs = 1) {
      this.statusBadge.className = 'bucket-spillover-badge throttled';
      this.statusBadge.textContent = `⚠️ Throttled (429) — Retry in ${retrySecs}s`;

      if (this.retryAfterTimer) clearInterval(this.retryAfterTimer);
      let remaining = retrySecs;
      this.retryAfterTimer = setInterval(() => {
        remaining -= 1;
        if (remaining <= 0) {
          this.clearThrottle();
          clearInterval(this.retryAfterTimer);
        } else {
          this.statusBadge.textContent = `⚠️ Throttled (429) — Retry in ${remaining}s`;
        }
      }, 1000);
    }

    clearThrottle() {
      if (this.retryAfterTimer) clearInterval(this.retryAfterTimer);
      this.statusBadge.className = 'bucket-spillover-badge normal';
      this.statusBadge.textContent = '● Tokens Available';
    }

    updateVisuals() {
      const pct = Math.min(100, Math.max(0, (this.tokens / this.capacity) * 100));
      this.liquid.style.height = `${pct}%`;

      if (this.tokens <= 0) {
        this.liquid.className = 'bucket-liquid empty';
      } else if (pct < 25) {
        this.liquid.className = 'bucket-liquid low';
      } else {
        this.liquid.className = 'bucket-liquid';
      }

      this.tokenCountEl.textContent = String(Math.floor(this.tokens));
    }

    startRefillLoop() {
      this.refillInterval = setInterval(() => {
        const now = Date.now();
        const delta = (now - this.lastRefillTime) / 1000;
        this.lastRefillTime = now;

        if (this.tokens < this.capacity) {
          this.tokens = Math.min(this.capacity, this.tokens + (this.refillRate * delta));
          this.updateVisuals();
        }
      }, 250);
    }

    destroy() {
      if (this.refillInterval) clearInterval(this.refillInterval);
      if (this.retryAfterTimer) clearInterval(this.retryAfterTimer);
    }
  }

  window.GKTokenBucketWidget = TokenBucketWidget;
})();
