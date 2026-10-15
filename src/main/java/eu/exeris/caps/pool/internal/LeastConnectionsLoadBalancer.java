package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Least-connections {@link LoadBalancer}: selects the available host with the fewest in-flight requests.
 * Ties are broken by a rotating scan start.
 *
 * @implSpec Thread-safe. One {@link AtomicLong} increment sets the scan start and the hosts are read by
 *           index, so a selection takes no lock and allocates nothing. The in-flight counts are read
 *           without a snapshot: two concurrent selections may pick the same host. The {@code hashKey} of
 *           {@link #select(UpstreamCluster, long)} is ignored.
 * @since 0.1.0
 */
public final class LeastConnectionsLoadBalancer implements LoadBalancer {

    private final AtomicLong tieBreaker = new AtomicLong(0);

    @Override
    public UpstreamHost select(UpstreamCluster cluster) {
        List<UpstreamHost> all = cluster.allHosts();
        int size = all.size();
        if (size == 0) {
            return null;
        }
        boolean anyAvailable = Candidates.anyAvailable(all);
        int start = Math.floorMod(tieBreaker.getAndIncrement(), size);
        UpstreamHost best = null;
        int minConnections = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            int idx = start + i;
            if (idx >= size) {
                idx -= size;
            }
            UpstreamHost host = all.get(idx);
            if (!Candidates.isCandidate(host, anyAvailable)) {
                continue;
            }
            int conns = host.activeConnections();
            if (best == null || conns < minConnections) {
                best = host;
                minConnections = conns;
                if (conns == 0) {
                    break;
                }
            }
        }
        return best;
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        return select(cluster);
    }
}
