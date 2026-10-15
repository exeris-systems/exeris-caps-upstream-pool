package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Uniformly random {@link LoadBalancer} over the available hosts of a cluster.
 *
 * @implSpec Thread-safe. Randomness comes from {@link ThreadLocalRandom}, which holds no shared state, so
 *           a selection takes no lock and allocates nothing. The {@code hashKey} of
 *           {@link #select(UpstreamCluster, long)} is ignored.
 * @since 0.1.0
 */
public final class RandomLoadBalancer implements LoadBalancer {

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> all = cluster.allHosts();
        boolean anyAvailable = Candidates.anyAvailable(all);
        int count = Candidates.count(all, anyAvailable);
        if (count == 0) {
            return null;
        }
        return Candidates.nth(all, anyAvailable, ThreadLocalRandom.current().nextInt(count));
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        return select(cluster);
    }
}
