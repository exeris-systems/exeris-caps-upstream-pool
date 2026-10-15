package eu.exeris.caps.pool.api;

/**
 * Represents a live upstream host within an {@link UpstreamCluster}, tracking its health,
 * active connection concurrency, request statistics, and outlier detection state.
 *
 * @since 0.1.0
 */
public interface UpstreamHost {

    /**
     * Returns the network endpoint this host represents.
     *
     * @return the immutable endpoint descriptor
     */
    UpstreamEndpoint endpoint();

    /**
     * Returns the host's lifecycle status as last set. An ejected host reports
     * {@link EndpointHealthStatus#UNHEALTHY} even after its quarantine window has elapsed, until a
     * success or a probe reinstates it; {@link #isAvailable()} is the answer to "may it get traffic".
     *
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
     * Returns the number of requests currently in flight to this host.
     *
     * @return the in-flight count, never negative
     */
    int activeConnections();

    /**
     * Returns the number of requests routed to this host since creation.
     *
     * @return the request total
     */
    long totalRequests();

    /**
     * Returns the number of failed requests recorded for this host since creation.
     *
     * @return the failure total
     */
    long totalFailures();

    /**
     * Returns the length of the current run of failures (5xx or transport) with no success between.
     *
     * @return the consecutive failure count
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
     * Records a successful response (HTTP status below 500).
     * Resets consecutive failures to zero and reinstates a host whose quarantine window has elapsed.
     * A success recorded while the window is still open, or on a host ejected without a window, does not
     * reinstate it.
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
     * An explicit call is not subject to the cluster's ejection budget.
     *
     * @param durationNanos quarantine duration in nanoseconds
     */
    void markUnhealthy(long durationNanos);

    /**
     * Marks this host as healthy and resets failure counters.
     */
    void markHealthy();

    /**
     * Sets the explicit health status. An {@link EndpointHealthStatus#UNHEALTHY} set this way has no
     * quarantine window and holds until the status is set again, the host is marked healthy or a probe
     * reinstates it.
     *
     * @param status new status
     */
    void setStatus(EndpointHealthStatus status);
}
