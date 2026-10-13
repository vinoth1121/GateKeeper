package io.gatekeeper.adaptive;

/**
 * Outcome of an admission check against the adaptive limiter and the priority
 * shedder. When {@code allowed} is false, {@code statusWhenShed} carries the
 * HTTP status the web layer must return (503 for P1, 429 for P2) together with
 * a Retry-After hint in seconds.
 */
public record ShedDecision(boolean allowed, int statusWhenShed, long retryAfterSeconds) {

    public static ShedDecision admitted() {
        return new ShedDecision(true, 0, 0L);
    }
}
