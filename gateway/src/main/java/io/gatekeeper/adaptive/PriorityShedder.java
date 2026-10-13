package io.gatekeeper.adaptive;

/**
 * Priority shedding policy layered on top of the Gradient2 concurrency limit.
 *
 * P0 (critical) is never shed. P1 (standard) is shed with 503 + Retry-After
 * once inflight traffic exceeds the P1 fraction of the current limit, and P2
 * (droppable) is shed with 429 + Retry-After at the earlier, lower P2
 * threshold, so droppable traffic sheds first under congestion.
 */
public final class PriorityShedder {

    public static final int STATUS_P1_SHED = 503;
    public static final int STATUS_P2_SHED = 429;

    private final double shedThresholdP2;
    private final double shedThresholdP1;

    public PriorityShedder(double shedThresholdP2, double shedThresholdP1) {
        this.shedThresholdP2 = shedThresholdP2;
        this.shedThresholdP1 = shedThresholdP1;
    }

    /**
     * Pure decision over the current occupancy: no locks, no shared state, so
     * callers can evaluate this on virtual threads without coordination.
     */
    public ShedDecision check(int inflight, int limit, Priority priority) {
        if (priority == null) {
            return ShedDecision.admitted();
        }
        switch (priority) {
            case P0:
                return ShedDecision.admitted();
            case P1:
                if (inflight > shedThresholdP1 * limit) {
                    return new ShedDecision(false, STATUS_P1_SHED, retryAfterSeconds());
                }
                return ShedDecision.admitted();
            case P2:
            default:
                if (inflight > shedThresholdP2 * limit) {
                    return new ShedDecision(false, STATUS_P2_SHED, retryAfterSeconds());
                }
                return ShedDecision.admitted();
        }
    }

    public static int statusWhenShed(Priority priority) {
        return priority == Priority.P2 ? STATUS_P2_SHED : STATUS_P1_SHED;
    }

    public long retryAfterSeconds() {
        return 1L;
    }
}
