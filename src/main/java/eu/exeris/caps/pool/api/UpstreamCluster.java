package eu.exeris.caps.pool.api;

import java.util.List;

/**
 * A named cluster of upstream hosts with an assigned load-balancing algorithm,
 * active health probing, and passive outlier detection.
 *
 * @since 0.1.0
 */
public interface UpstreamCluster {

    /**
     * Returns the unique name of the cluster.
     *
     * @return the cluster name
     */
    String name();

    /**
     * Returns every configured host, whatever its health. The list is immutable and fixed at construction,
     * so it can be read by index on the request path without allocating.
     *
     * @return all configured hosts in the cluster
     */
    List<UpstreamHost> allHosts();

    /**
     * Returns a snapshot of the hosts that are available for new traffic at the moment of the call.
     * The list may be a new object, so selection code reads {@link #allHosts()} instead; this is for
     * inspection and reporting.
     *
     * @return the available hosts
     */
    List<UpstreamHost> healthyHosts();

    /**
     * Returns the number of configured hosts.
     *
     * @return the host count
     */
    int totalHostCount();

    /**
     * Counts the hosts that are available for new traffic at the moment of the call.
     *
     * @return the number of available hosts
     */
    int healthyHostCount();

    /**
     * Returns the algorithm this cluster was configured with.
     *
     * @return the load-balancing algorithm
     */
    LoadBalancingAlgorithm algorithm();

    /**
     * Returns the passive outlier detection settings, including the cluster's ejection budget
     * ({@link PassiveHealthCheckConfig#maxEjectionPercent()}).
     *
     * @return the passive health check configuration
     */
    PassiveHealthCheckConfig passiveConfig();

    /**
     * Returns the active probing settings.
     *
     * @return the active health check configuration
     */
    ActiveHealthCheckConfig activeConfig();

    /**
     * Selects an available upstream host using the cluster's load-balancing algorithm.
     *
     * <p>This method executes on the request path. With the balancers that {@link UpstreamPools} builds it
     * takes no lock and allocates nothing in steady state, including while a host is ejected.
     *
     * @return the chosen {@link UpstreamHost}, or {@code null} if no hosts are available
     */
    UpstreamHost selectHost();

    /**
     * Selects an available upstream host by a caller-supplied key (for example a client address folded by
     * {@link UpstreamPools#addressKey(byte[])}). Only {@link LoadBalancingAlgorithm#IP_HASH} uses the key;
     * the other algorithms ignore it.
     *
     * @param hashKey 64-bit integer hash key
     * @return the chosen {@link UpstreamHost}, or {@code null} if no hosts are available
     */
    UpstreamHost selectHost(long hashKey);

    /**
     * Records the outcome of a proxied request for passive outlier detection. A status of 500 or above,
     * or a transport failure, counts as a failure; anything else counts as a success. A caller that could
     * not reach the host at all must report {@code transportError = true}, or the host is never ejected for it.
     *
     * @param host           the upstream host that served the request
     * @param statusCode     the HTTP response status code, or 0 if transport error
     * @param transportError {@code true} if transport/connection failed
     */
    void recordResult(UpstreamHost host, int statusCode, boolean transportError);

    /**
     * Stops the active health check prober of this cluster, if one is running, and releases its transport.
     */
    void shutdown();
}
