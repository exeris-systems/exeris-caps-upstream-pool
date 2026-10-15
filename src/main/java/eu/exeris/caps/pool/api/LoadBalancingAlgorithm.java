package eu.exeris.caps.pool.api;

/**
 * Supported load-balancing algorithms for upstream pools.
 *
 * @since 0.1.0
 */
public enum LoadBalancingAlgorithm {
    /** Rotates over the available hosts; the key of {@code selectHost(long)} is ignored. */
    ROUND_ROBIN,
    /** Selects the available host with the fewest in-flight requests; the key is ignored. */
    LEAST_CONNECTIONS,
    /** Rotates over the available hosts in proportion to their endpoint weights, spread smoothly; the key is ignored. */
    WEIGHTED_ROUND_ROBIN,
    /**
     * Selects by the caller-supplied key of {@code selectHost(long)}: the same key reaches the same host
     * while that host is available, and when a host leaves only the keys that mapped to it move. The pool
     * never reads a client address itself; see {@link UpstreamPools#addressKey(byte[])}. Without a key,
     * {@code selectHost()} rotates over the available hosts.
     */
    IP_HASH,
    /** Selects uniformly at random among the available hosts; the key is ignored. */
    RANDOM
}
