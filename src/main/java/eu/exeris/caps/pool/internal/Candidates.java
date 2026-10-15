package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.List;

/**
 * Index-based candidate scans over a cluster's immutable host list.
 *
 * <p>A host is a candidate when it is available. When no host is available, selection stays open for
 * hosts that were ejected or failed a probe, since traffic to them is the only way to learn they
 * recovered; a draining host is never a candidate, because it has been taken out of service on purpose.
 * Every method reads the list by index and allocates nothing.
 */
final class Candidates {

    private Candidates() {
    }

    /** Returns whether at least one host is available for new traffic. */
    static boolean anyAvailable(List<UpstreamHost> hosts) {
        for (int i = 0, n = hosts.size(); i < n; i++) {
            if (hosts.get(i).isAvailable()) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether the host is a candidate, given whether any host in the list is available. */
    static boolean isCandidate(UpstreamHost host, boolean anyAvailable) {
        return anyAvailable ? host.isAvailable() : host.status() != EndpointHealthStatus.DRAINING;
    }

    /** Returns the number of candidates. */
    static int count(List<UpstreamHost> hosts, boolean anyAvailable) {
        int count = 0;
        for (int i = 0, n = hosts.size(); i < n; i++) {
            if (isCandidate(hosts.get(i), anyAvailable)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the candidate at zero-based position {@code ordinal} among the candidates, or the first
     * candidate when the set changed under the scan, or {@code null} when there is none.
     */
    static UpstreamHost nth(List<UpstreamHost> hosts, boolean anyAvailable, int ordinal) {
        UpstreamHost first = null;
        int seen = 0;
        for (int i = 0, n = hosts.size(); i < n; i++) {
            UpstreamHost host = hosts.get(i);
            if (isCandidate(host, anyAvailable)) {
                if (seen == ordinal) {
                    return host;
                }
                if (first == null) {
                    first = host;
                }
                seen++;
            }
        }
        return first;
    }
}
