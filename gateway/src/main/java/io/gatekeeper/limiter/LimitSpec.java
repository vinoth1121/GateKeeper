package io.gatekeeper.limiter;

public record LimitSpec(
        String ruleId,
        Algorithm algorithm,
        long capacity,
        double refillPerSec,
        long windowMs,
        long max,
        long cost,
        boolean strict) {

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW }
}
