package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.UpstreamHost;

/**
 * Cluster-level ejection budget shared by passive outlier detection and active probing.
 *
 * <p>An ejection is refused when it would exceed either limit:
 * <ul>
 *   <li>at most {@code max(1, floor(hostCount * maxEjectionPercent / 100))} hosts are ejected at a time;</li>
 *   <li>at least one host that is neither ejected nor draining always remains, so a cluster of one host
 *       never ejects it, whatever the percentage.</li>
 * </ul>
 * A host counts as ejected while its status is {@link EndpointHealthStatus#UNHEALTHY} and it is not yet
 * available; a host on probation after its ejection window is available and does not count. A refused
 * ejection leaves the host in rotation; the failure counters keep running, so the ejection is retried
 * on the next failure or probe. Explicit operator calls ({@link UpstreamHost#markUnhealthy(long)},
 * {@link UpstreamHost#setStatus(EndpointHealthStatus)}) are not subject to the budget.
 *
 * <p>Thread-safe and lock-free. The decision is a scan of the host array and allocates nothing. Two
 * ejections racing for the last slot are both checked again after they apply, and a pair that overshoots
 * is rolled back, so the limits hold once both calls return.
 *
 * @since 0.1.0
 */
public final class EjectionBudget {

    private final UpstreamHost[] hosts;
    private final int maxEjected;

    /**
     * Creates a budget over the hosts of one cluster.
     *
     * @param hosts               the cluster's hosts
     * @param maxEjectionPercent  the share of hosts that may be ejected at once, 1 to 100
     */
    public EjectionBudget(UpstreamHost[] hosts, int maxEjectionPercent) {
        this.hosts = hosts.clone();
        this.maxEjected = Math.max(1, (int) ((long) hosts.length * maxEjectionPercent / 100));
    }

    /** Returns the number of hosts that may be ejected at the same time. */
    public int maxEjected() {
        return maxEjected;
    }

    /**
     * Ejects the host if the budget allows it.
     *
     * @param host           the host to eject
     * @param durationNanos  the quarantine window, or {@code 0} to keep the host out until it is reinstated
     * @return {@code true} if the host is ejected on return, {@code false} if the budget refused it
     */
    public boolean tryEject(UpstreamHost host, long durationNanos) {
        if (host.status() == EndpointHealthStatus.DRAINING) {
            return false;
        }
        boolean wasEjected = isEjected(host);
        if (!wasEjected && !admits(host)) {
            return false;
        }
        eject(host, durationNanos);
        if (wasEjected || withinLimits()) {
            return true;
        }
        host.markHealthy();
        return false;
    }

    private static void eject(UpstreamHost host, long durationNanos) {
        if (durationNanos > 0) {
            host.markUnhealthy(durationNanos);
        } else {
            host.setStatus(EndpointHealthStatus.UNHEALTHY);
        }
    }

    private static boolean isEjected(UpstreamHost host) {
        return host.status() == EndpointHealthStatus.UNHEALTHY && !host.isAvailable();
    }

    /** Whether ejecting {@code candidate}, which is not ejected yet, stays inside both limits. */
    private boolean admits(UpstreamHost candidate) {
        int ejected = 0;
        int remaining = 0;
        for (UpstreamHost host : hosts) {
            if (host == candidate) {
                continue;
            }
            if (isEjected(host)) {
                ejected++;
            } else if (host.status() != EndpointHealthStatus.DRAINING) {
                remaining++;
            }
        }
        return ejected + 1 <= maxEjected && remaining >= 1;
    }

    private boolean withinLimits() {
        int ejected = 0;
        int remaining = 0;
        for (UpstreamHost host : hosts) {
            if (isEjected(host)) {
                ejected++;
            } else if (host.status() != EndpointHealthStatus.DRAINING) {
                remaining++;
            }
        }
        return ejected <= maxEjected && remaining >= 1;
    }
}
