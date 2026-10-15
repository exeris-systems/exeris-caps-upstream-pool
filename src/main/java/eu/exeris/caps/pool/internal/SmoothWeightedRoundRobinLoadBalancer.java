package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smooth weighted round-robin load balancer implementing the NGINX smooth weighting algorithm.
 *
 * @since 0.1.0
 */
public final class SmoothWeightedRoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> currentWeights = new ConcurrentHashMap<>();

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> candidates = cluster.healthyHosts();
        if (candidates.isEmpty()) {
            candidates = FailOpen.candidates(cluster);
        }
        int size = candidates.size();
        if (size == 0) {
            return null;
        }
        if (size == 1) {
            return candidates.get(0);
        }

        int totalWeight = 0;
        UpstreamHost best = null;
        int maxWeight = Integer.MIN_VALUE;

        // Synchronize on the cluster or use CAS to avoid state corruption
        synchronized (this) {
            for (int i = 0; i < size; i++) {
                UpstreamHost host = candidates.get(i);
                int weight = host.endpoint().weight();
                totalWeight += weight;

                AtomicInteger curWeight = currentWeights.computeIfAbsent(
                        host.endpoint().authority(), k -> new AtomicInteger(0)
                );
                int updated = curWeight.addAndGet(weight);

                if (updated > maxWeight) {
                    maxWeight = updated;
                    best = host;
                }
            }

            if (best != null) {
                AtomicInteger bestWeight = currentWeights.get(best.endpoint().authority());
                if (bestWeight != null) {
                    bestWeight.addAndGet(-totalWeight);
                }
            }
        }

        return best != null ? best : candidates.get(0);
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        return select(cluster);
    }
}
