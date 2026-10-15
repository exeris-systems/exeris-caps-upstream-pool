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
     * @return the unique name of the cluster
     */
    String name();

    /**
     * @return all configured hosts in the cluster
     */
    List<UpstreamHost> allHosts();

    /**
     * @return currently healthy and available hosts
     */
    List<UpstreamHost> healthyHosts();

    /**
     * @return total count of configured hosts
     */
    int totalHostCount();

    /**
     * @return count of currently healthy and available hosts
     */
    int healthyHostCount();

    /**
     * @return the configured load-balancing algorithm
     */
    LoadBalancingAlgorithm algorithm();

    /**
     * @return the passive health check configuration
     */
    PassiveHealthCheckConfig passiveConfig();

    /**
     * @return the active health check configuration
     */
    ActiveHealthCheckConfig activeConfig();

    /**
     * Selects an available upstream host using the cluster's load-balancing algorithm.
     *
     * <p>This method executes on the hot request path and is guaranteed to be zero-allocation
     * under steady state.
     *
     * @return the chosen {@link UpstreamHost}, or {@code null} if no hosts are available
     */
    UpstreamHost selectHost();

    /**
     * Selects an available upstream host using a hash key (e.g. client IP, tenant, or token hash).
     *
     * @param hashKey 64-bit integer hash key
     * @return the chosen {@link UpstreamHost}, or {@code null} if no hosts are available
     */
    UpstreamHost selectHost(long hashKey);

    /**
     * Records the execution outcome of a proxied request to update active connections
     * and passive outlier detection metrics.
     *
     * @param host           the upstream host that served the request
     * @param statusCode     the HTTP response status code, or 0 if transport error
     * @param transportError {@code true} if transport/connection failed
     */
    void recordResult(UpstreamHost host, int statusCode, boolean transportError);

    /**
     * Drains and shuts down any active health check schedulers for this cluster.
     */
    void shutdown();
}
