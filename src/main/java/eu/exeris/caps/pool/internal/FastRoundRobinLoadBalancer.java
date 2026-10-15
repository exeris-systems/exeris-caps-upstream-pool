package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, lock-free Round-Robin load balancer with zero-allocation host selection.
 *
 * @since 0.1.0
 */
public final class FastRoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> healthy = cluster.healthyHosts();
        int healthySize = healthy.size();
        if (healthySize > 0) {
            long seq = sequence.getAndIncrement();
            int idx = (int) Math.floorMod(seq, healthySize);
            return healthy.get(idx);
        }

        // Fail-open: if all hosts are ejected or unavailable, fall back to all hosts
        List<UpstreamHost> all = FailOpen.candidates(cluster);
        int allSize = all.size();
        if (allSize > 0) {
            long seq = sequence.getAndIncrement();
            int idx = (int) Math.floorMod(seq, allSize);
            return all.get(idx);
        }

        return null;
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        List<UpstreamHost> healthy = cluster.healthyHosts();
        int healthySize = healthy.size();
        if (healthySize > 0) {
            int idx = (int) Math.floorMod(hashKey, healthySize);
            return healthy.get(idx);
        }

        List<UpstreamHost> all = FailOpen.candidates(cluster);
        int allSize = all.size();
        if (allSize > 0) {
            int idx = (int) Math.floorMod(hashKey, allSize);
            return all.get(idx);
        }

        return null;
    }
}
