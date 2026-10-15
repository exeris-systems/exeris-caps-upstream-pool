package eu.exeris.caps.pool.api;

/**
 * Represents a live upstream host within an {@link UpstreamCluster}, tracking its health,
 * active connection concurrency, request statistics, and outlier detection state.
 *
 * @since 0.1.0
 */
public interface UpstreamHost {

    /**
     * @return the immutable network endpoint descriptor
     */
    UpstreamEndpoint endpoint();

    /**
     * @return current lifecycle health status
     */
    EndpointHealthStatus status();

    /**
     * Checks if the host is available for new traffic.
     *
     * <p>If a host was previously ejected due to passive outlier detection and its quarantine window
     * has expired, this call may opportunistically allow probe traffic to re-evaluate health.
     *
     * @return {@code true} if the host is eligible to receive requests
     */
    boolean isAvailable();

    /**
     * @return the number of currently active in-flight connections/requests
     */
    int activeConnections();

    /**
     * @return total requests routed to this host
     */
    long totalRequests();

    /**
     * @return total failed requests
     */
    long totalFailures();

    /**
     * @return current count of consecutive 5xx or transport failures
     */
    long consecutiveFailures();

    /**
     * Atomically increments the in-flight active connection count.
     */
    void incrementConnections();

    /**
     * Atomically decrements the in-flight active connection count.
     */
    void decrementConnections();

    /**
     * Records a successful response (HTTP status < 500).
     * Resets consecutive failures to zero and reinstates host if probationary.
     */
    void recordSuccess();

    /**
     * Records a failed request (HTTP status >= 500 or network I/O transport error).
     *
     * @param statusCode     the HTTP response status code, or 0 if transport error
     * @param transportError {@code true} if connection refused, reset, or timed out
     */
    void recordFailure(int statusCode, boolean transportError);

    /**
     * Explicitly marks this host as unhealthy and ejects it for the given quarantine duration.
     *
     * @param durationNanos quarantine duration in nanoseconds
     */
    void markUnhealthy(long durationNanos);

    /**
     * Marks this host as healthy and resets failure counters.
     */
    void markHealthy();

    /**
     * Sets the explicit health status.
     *
     * @param status new status
     */
    void setStatus(EndpointHealthStatus status);
}
