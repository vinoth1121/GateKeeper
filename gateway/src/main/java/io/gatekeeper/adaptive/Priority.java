package io.gatekeeper.adaptive;

/**
 * Request priority classes used by the adaptive gateway. The web layer
 * resolves the priority (including any trusted X-GateKeeper-Priority header
 * override) and hands it to {@link AdaptiveGateway}; the enum only classifies.
 */
public enum Priority {
    /** Critical traffic: never shed, may wait up to p0WaitMs for a slot. */
    P0,
    /** Standard traffic: shed with 503 + Retry-After above the P1 threshold. */
    P1,
    /** Droppable traffic: shed with 429 + Retry-After above the P2 threshold. */
    P2
}
