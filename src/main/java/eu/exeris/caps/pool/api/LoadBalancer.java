package eu.exeris.caps.pool.api;

/**
 * Strategy interface for load-balancing host selection across an {@link UpstreamCluster}.
 *
 * <p>Implementations must adhere strictly to zero-allocation and lock-free execution
 * on the hot request path.
 *
 * @since 0.1.0
 */
public interface LoadBalancer {

    /**
     * Selects an available host from the cluster.
     *
     * @param cluster the upstream cluster
     * @return selected host, or {@code null} if no hosts are available
     */
    UpstreamHost select(UpstreamCluster cluster);

    /**
     * Selects an available host from the cluster using a hash key.
     *
     * @param cluster the upstream cluster
     * @param hashKey 64-bit integer key
     * @return selected host, or {@code null} if no hosts are available
     */
    UpstreamHost select(UpstreamCluster cluster, long hashKey);
}
