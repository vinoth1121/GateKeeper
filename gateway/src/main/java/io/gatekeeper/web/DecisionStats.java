package io.gatekeeper.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

import io.gatekeeper.limiter.LimiterDecision;

/**
 * In-memory decision tallies maintained by the filter. These are the source
 * of truth for /api/admin/stats and /api/public/stats on purpose: the console
 * polls them directly and they must stay independent of the Prometheus meter
 * registry (low-cardinality, demo-friendly, raw tenant ids allowed here).
 *
 * The requests-per-second figure is an EWMA over counted decisions: a 10s
 * sliding window of decision events is measured and exponentially smoothed
 * (alpha 0.3) so the console curve does not stutter at low traffic.
 */
@Component
public class DecisionStats {

    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final double EWMA_ALPHA = 0.3;
    private static final int PRUNE_EVERY = 32;

    private final long startedAtMillis = System.currentTimeMillis();
    private final ConcurrentMap<String, LongAdder> decisions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> tenantCounts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Long> events = new ConcurrentLinkedDeque<>();
    private final LongAdder localDecisions = new LongAdder();
    private final LongAdder redisDecisions = new LongAdder();
    private volatile double ewmaRps = 0.0;
    private int eventCounter;

    public void count(String tier, String rule, String decision, String reason) {
        decisions.computeIfAbsent(tier + "|" + rule + "|" + decision + "|" + reason, k -> new LongAdder())
                .increment();
        long now = System.nanoTime();
        events.addLast(now);
        if (++eventCounter % PRUNE_EVERY == 0) {
            refreshRps(now);
        }
    }

    public void countTenant(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            tenantCounts.computeIfAbsent(tenantId, k -> new LongAdder()).increment();
        }
    }

    public void countSource(LimiterDecision.DecisionSource source) {
        if (source == LimiterDecision.DecisionSource.LOCAL) {
            localDecisions.increment();
        } else if (source == LimiterDecision.DecisionSource.REDIS) {
            redisDecisions.increment();
        }
    }

    public synchronized double requestsPerSecond() {
        refreshRps(System.nanoTime());
        return ewmaRps;
    }

    private void refreshRps(long nowNanos) {
        Long head;
        while ((head = events.peekFirst()) != null && nowNanos - head > WINDOW_NANOS) {
            events.pollFirst();
        }
        double instant = events.size() / 10.0;
        ewmaRps = ewmaRps + EWMA_ALPHA * (instant - ewmaRps);
    }

    public List<Map<String, Object>> decisionTotals() {
        List<Map<String, Object>> rows = new ArrayList<>(decisions.size());
        for (Map.Entry<String, LongAdder> e : decisions.entrySet()) {
            String[] parts = e.getKey().split("\\|", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tier", parts[0]);
            row.put("rule", parts[1]);
            row.put("decision", parts[2]);
            row.put("reason", parts[3]);
            row.put("count", e.getValue().sum());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("decision"))));
        return rows;
    }

    public long totalByDecision(String decision) {
        long total = 0;
        for (Map.Entry<String, LongAdder> e : decisions.entrySet()) {
            String[] parts = e.getKey().split("\\|", -1);
            if (decision.equals(parts[2])) {
                total += e.getValue().sum();
            }
        }
        return total;
    }

    public List<Map<String, Object>> topTenants(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>(tenantCounts.size());
        for (Map.Entry<String, LongAdder> e : tenantCounts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", e.getKey());
            row.put("requests", e.getValue().sum());
            rows.add(row);
        }
        rows.sort(Comparator.<Map<String, Object>>comparingLong(r -> (Long) r.get("requests")).reversed());
        if (rows.size() > limit) {
            return rows.subList(0, limit);
        }
        return rows;
    }

    /** Local lease hit ratio derived from limiter decision sources seen by the filter. */
    public double localHitRatio() {
        long local = localDecisions.sum();
        long redis = redisDecisions.sum();
        long total = local + redis;
        return total == 0 ? 0.0 : (double) local / total;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }
}
