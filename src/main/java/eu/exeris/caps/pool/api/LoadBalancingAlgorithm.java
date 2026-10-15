package eu.exeris.caps.pool.api;

/**
 * Supported load-balancing algorithms for upstream pools.
 *
 * @since 0.1.0
 */
public enum LoadBalancingAlgorithm {
    /** Lock-free round-robin distribution using atomic sequence modulo. */
    ROUND_ROBIN,
    /** Dynamic least-connections selection tracking in-flight requests. */
    LEAST_CONNECTIONS,
    /** Smooth weighted round-robin distribution according to configured host weights. */
    WEIGHTED_ROUND_ROBIN,
    /** Consistent or fast hash-based routing based on request key (IP, user, tenant). */
    IP_HASH,
    /** Pseudo-random host selection. */
    RANDOM
}
