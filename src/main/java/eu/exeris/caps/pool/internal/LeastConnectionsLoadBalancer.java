package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free Least-Connections load balancer with zero-allocation host selection.
 *
 * <p>Selects the healthy upstream host with the fewest currently active in-flight connections.
 * Ties are broken using round-robin distribution.
 *
 * @since 0.1.0
 */
public final class LeastConnectionsLoadBalancer implements LoadBalancer {

    private final AtomicLong tieBreaker = new AtomicLong(0);

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

        // Start scanning from an offset to evenly distribute among ties
        int startOffset = (int) Math.floorMod(tieBreaker.getAndIncrement(), size);
        UpstreamHost bestHost = null;
        int minConnections = Integer.MAX_VALUE;

        for (int i = 0; i < size; i++) {
            int idx = (startOffset + i) % size;
            UpstreamHost host = candidates.get(idx);
            int conns = host.activeConnections();
            if (conns < minConnections) {
                minConnections = conns;
                bestHost = host;
                if (minConnections == 0) {
                    // Cannot get lower than 0
                    break;
                }
            }
        }

        return bestHost != null ? bestHost : candidates.get(0);
    }

    @Override
    public UpstreamHost select(UpstreamCluster cluster, long hashKey) {
        return select(cluster);
    }
}
