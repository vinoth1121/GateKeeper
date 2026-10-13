/**
 * GateKeeper Real-Time Canvas Sparkline / Micro-Chart
 * Zero-dependency, lightweight, high-performance canvas chart for live telemetry.
 */
(function () {
  'use strict';

  class Sparkline {
    constructor(canvas, options) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.options = Object.assign(
        {
          maxPoints: 30,
          colorA: '#10b981', // Emerald / Allowed
          colorB: '#ef4444', // Red / Denied
          fillGradient: true,
          labelA: 'Allowed',
          labelB: 'Denied',
        },
        options || {}
      );
      this.seriesA = [];
      this.seriesB = [];
      this.resize();
      window.addEventListener('resize', () => this.resize());
    }

    resize() {
      const rect = this.canvas.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      this.canvas.width = (rect.width || 300) * dpr;
      this.canvas.height = (rect.height || 90) * dpr;
      this.ctx.scale(dpr, dpr);
      this.width = rect.width || 300;
      this.height = rect.height || 90;
      this.render();
    }

    push(valA, valB = 0) {
      this.seriesA.push(Number(valA) || 0);
      if (this.seriesA.length > this.options.maxPoints) this.seriesA.shift();

      if (valB !== null && valB !== undefined) {
        this.seriesB.push(Number(valB) || 0);
        if (this.seriesB.length > this.options.maxPoints) this.seriesB.shift();
      }
      this.render();
    }

    render() {
      const { ctx, width, height } = this;
      if (!ctx || width === 0 || height === 0) return;

      ctx.clearRect(0, 0, width, height);

      // Draw subtle grid lines
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, height * 0.5);
      ctx.lineTo(width, height * 0.5);
      ctx.stroke();

      if (this.seriesA.length < 2) return;

      const maxVal = Math.max(
        5,
        ...this.seriesA,
        ...(this.seriesB.length ? this.seriesB : [0])
      );

      // Render Series A
      this.drawSeries(this.seriesA, this.options.colorA, maxVal, true);

      // Render Series B if present
      if (this.seriesB.length > 1) {
        this.drawSeries(this.seriesB, this.options.colorB, maxVal, false);
      }
    }

    drawSeries(data, color, maxVal, withFill) {
      const { ctx, width, height } = this;
      const step = width / (this.options.maxPoints - 1);
      const points = data.map((val, i) => {
        const x = (this.options.maxPoints - data.length + i) * step;
        const y = height - (val / maxVal) * (height - 12) - 6;
        return { x, y };
      });

      if (withFill && this.options.fillGradient) {
        const grad = ctx.createLinearGradient(0, 0, 0, height);
        grad.addColorStop(0, color.replace(')', ', 0.35)').replace('rgb', 'rgba'));
        grad.addColorStop(1, 'rgba(0, 0, 0, 0.0)');

        ctx.fillStyle = grad;
        ctx.beginPath();
        ctx.moveTo(points[0].x, height);
        for (const p of points) ctx.lineTo(p.x, p.y);
        ctx.lineTo(points[points.length - 1].x, height);
        ctx.closePath();
        ctx.fill();
      }

      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (let i = 1; i < points.length; i++) {
        ctx.lineTo(points[i].x, points[i].y);
      }
      ctx.stroke();

      // Draw end pulse dot
      const last = points[points.length - 1];
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(last.x, last.y, 3.5, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  window.GKSparkline = Sparkline;
})();
