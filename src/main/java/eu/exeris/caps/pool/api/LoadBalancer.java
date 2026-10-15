package eu.exeris.caps.pool.api;

/**
 * Strategy interface for load-balancing host selection across an {@link UpstreamCluster}.
 *
 * @implSpec Implementations must be thread-safe, because one balancer serves every request of its
 *           cluster concurrently. The implementations in this capability select without taking a lock and
 *           without allocating in steady state; they read {@link UpstreamCluster#allHosts()} by index and
 *           skip hosts that are not available. A custom implementation should do the same and must not
 *           call {@link UpstreamCluster#healthyHosts()}, which may allocate.
 * @apiNote When no host is available, the implementations here fall back to hosts that were ejected or
 *          failed a probe (traffic is how a recovery is noticed) and never offer a draining host.
 *          Which implementations use the {@code hashKey} is stated on each; those that do not, ignore it.
 *
 * @since 0.1.0
 */
public interface LoadBalancer {

    /**
     * Selects an available host from the cluster, without a key.
     *
     * @param cluster the upstream cluster
     * @return selected host, or {@code null} if no hosts are available
     */
    UpstreamHost select(UpstreamCluster cluster);

    /**
     * Selects an available host from the cluster by a caller-supplied key.
     *
     * <p>Only key-affine balancers, such as the one behind {@link LoadBalancingAlgorithm#IP_HASH}, use the
     * key; the others ignore it and behave as {@link #select(UpstreamCluster)}.
     *
     * @param cluster the upstream cluster
     * @param hashKey 64-bit key chosen by the caller
     * @return selected host, or {@code null} if no hosts are available
     */
    UpstreamHost select(UpstreamCluster cluster, long hashKey);
}
