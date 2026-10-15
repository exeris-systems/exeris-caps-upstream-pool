package eu.exeris.caps.pool.api;

/**
 * Health and lifecycle status of an upstream host in a pool.
 *
 * @since 0.1.0
 */
public enum EndpointHealthStatus {
    /** Host is healthy and accepts new traffic. */
    HEALTHY,
    /** Host has failed health checks or was ejected by outlier detection. */
    UNHEALTHY,
    /** Host is draining existing connections and should not receive new requests. */
    DRAINING
}
