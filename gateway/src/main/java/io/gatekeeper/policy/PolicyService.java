package io.gatekeeper.policy;

/**
 * Service contract for policy resolution, version tracking, and policy compilation.
 */
public interface PolicyService {

    /** Returns the current active policy version. */
    long currentVersion();

    /** Resolves the matching policy rule for the given request context. */
    ResolvedPolicy resolve(RequestContext context);

    /** Returns the fallback default rule. */
    RuleDefinition defaultRule();
}
