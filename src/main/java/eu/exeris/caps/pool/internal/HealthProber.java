package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.ActiveHealthCheckConfig;
import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Active health prober: probes every host of a cluster and moves a host in or out of rotation on its own
 * evidence, independently of passive outlier detection.
 *
 * <p>A host with {@code unhealthyThreshold} consecutive failed probes is ejected, through the cluster's
 * {@link EjectionBudget}, and stays out until {@code healthyThreshold} consecutive probes succeed. A probe
 * succeeds on a 2xx status; any other status, an I/O error or a timeout fails it. A draining host is not
 * probed. Probe results are kept apart from request statistics: a probe never changes a host's request or
 * failure counters.
 *
 * <p>One round runs on the calling thread and touches no clock; {@link DefaultUpstreamCluster} calls
 * {@link #probeRound()} from its scheduler. Not thread-safe: rounds must not overlap.
 *
 * @since 0.1.0
 */
public final class HealthProber {

    private final List<UpstreamHost> hosts;
    private final ActiveHealthCheckConfig config;
    private final ProbeTransport transport;
    private final EjectionBudget budget;
    private final int[] consecutiveFailures;
    private final int[] consecutiveSuccesses;

    /**
     * Creates a prober.
     *
     * @param hosts      the hosts to probe
     * @param config     thresholds, path and timeout
     * @param transport  how a probe is sent
     * @param budget     the cluster's ejection budget
     */
    public HealthProber(List<UpstreamHost> hosts, ActiveHealthCheckConfig config, ProbeTransport transport,
                        EjectionBudget budget) {
        this.hosts = List.copyOf(hosts);
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.consecutiveFailures = new int[this.hosts.size()];
        this.consecutiveSuccesses = new int[this.hosts.size()];
    }

    /** Probes each host once and applies the outcome. */
    public void probeRound() {
        for (int i = 0; i < hosts.size(); i++) {
            UpstreamHost host = hosts.get(i);
            if (host.status() == EndpointHealthStatus.DRAINING) {
                consecutiveFailures[i] = 0;
                consecutiveSuccesses[i] = 0;
                continue;
            }
            boolean ok;
            try {
                int status = transport.probe(host.endpoint(), config.healthCheckPath(), config.timeoutMillis());
                ok = status >= 200 && status < 300;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            apply(i, host, ok);
        }
    }

    private void apply(int index, UpstreamHost host, boolean ok) {
        if (ok) {
            consecutiveFailures[index] = 0;
            if (host.status() == EndpointHealthStatus.UNHEALTHY) {
                if (++consecutiveSuccesses[index] >= config.healthyThreshold()) {
                    host.markHealthy();
                    consecutiveSuccesses[index] = 0;
                }
            } else {
                consecutiveSuccesses[index] = 0;
            }
            return;
        }
        consecutiveSuccesses[index] = 0;
        if (consecutiveFailures[index] < Integer.MAX_VALUE) {
            consecutiveFailures[index]++;
        }
        if (consecutiveFailures[index] >= config.unhealthyThreshold() && host.isAvailable()) {
            budget.tryEject(host, 0L);
        }
    }
}
