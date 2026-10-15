package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round-robin {@link LoadBalancer} over the available hosts of a cluster.
 *
 * @implSpec Thread-safe. The rotation is one {@link AtomicLong} increment per selection; the available
 *           hosts are found by an index scan of {@link UpstreamCluster#allHosts()}, so a selection takes
 *           no lock and allocates nothing. The {@code hashKey} of {@link #select(UpstreamCluster, long)}
 *           is ignored.
 * @since 0.1.0
 */
public final class FastRoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> all = cluster.allHosts();
        boolean anyAvailable = Candidates.anyAvailable(all);
        int count = Candidates.count(all, anyAvailable);
        if (count == 0) {
            return null;
        }
        int ordinal = Math.floorMod(sequence.getAndIncrement(), count);
        return Candidates.nth(all, anyAvailable, ordinal);
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        return select(cluster);
    }
}
