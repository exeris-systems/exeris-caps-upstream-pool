package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.ArrayList;
import java.util.List;

/**
 * Candidate set used when no host is available. Selection stays open for hosts that were ejected or
 * failed a probe, since traffic to them is the only way to learn they recovered, but a draining host
 * is never offered: it has been taken out of service on purpose.
 */
final class FailOpen {

    private FailOpen() {
    }

    static List<UpstreamHost> candidates(UpstreamCluster cluster) {
        List<UpstreamHost> all = cluster.allHosts();
        boolean draining = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).status() == EndpointHealthStatus.DRAINING) {
                draining = true;
                break;
            }
        }
        if (!draining) {
            return all;
        }
        List<UpstreamHost> result = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            UpstreamHost host = all.get(i);
            if (host.status() != EndpointHealthStatus.DRAINING) {
                result.add(host);
            }
        }
        return result;
    }
}
