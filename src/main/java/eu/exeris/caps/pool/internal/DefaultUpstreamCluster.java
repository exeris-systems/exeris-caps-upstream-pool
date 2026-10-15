package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.ActiveHealthCheckConfig;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default implementation of {@link UpstreamCluster}.
 *
 * <p>Host selection ({@link #selectHost()}) delegates to the {@link LoadBalancer} and is lock-free and
 * allocation-free in steady state when the balancer is one of this package's. The cluster owns one
 * {@link EjectionBudget}, built from {@link PassiveHealthCheckConfig#maxEjectionPercent()}, which bounds
 * passive and active ejections of hosts created by {@code UpstreamPools}, and, when active checking is
 * enabled, one prober thread that {@link #shutdown()} stops.
 *
 * @since 0.1.0
 */
public final class DefaultUpstreamCluster implements UpstreamCluster {

    private final String name;
    private final List<UpstreamHost> allHosts;
    private final UpstreamHost[] hostsArray;
    private final LoadBalancingAlgorithm algorithm;
    private final LoadBalancer loadBalancer;
    private final PassiveHealthCheckConfig passiveConfig;
    private final ActiveHealthCheckConfig activeConfig;
    private final EjectionBudget ejectionBudget;

    private final ScheduledExecutorService proberExecutor;
    private final ProbeTransport proberTransport;
    private final HealthProber prober;
    private final ReentrantLock probeRound = new ReentrantLock();

    public DefaultUpstreamCluster(
            String name,
            List<UpstreamHost> hosts,
            LoadBalancingAlgorithm algorithm,
            LoadBalancer loadBalancer,
            PassiveHealthCheckConfig passiveConfig,
            ActiveHealthCheckConfig activeConfig
    ) {
        this(name, hosts, algorithm, loadBalancer, passiveConfig, activeConfig, null);
    }

    /**
     * Creates a cluster whose active prober sends its probes through the given transport.
     *
     * @param probeTransport the probe wire, or {@code null} for the JDK HTTP client
     */
    public DefaultUpstreamCluster(
            String name,
            List<UpstreamHost> hosts,
            LoadBalancingAlgorithm algorithm,
            LoadBalancer loadBalancer,
            PassiveHealthCheckConfig passiveConfig,
            ActiveHealthCheckConfig activeConfig,
            ProbeTransport probeTransport
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.allHosts = List.copyOf(hosts);
        this.hostsArray = this.allHosts.toArray(new UpstreamHost[0]);
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        this.passiveConfig = passiveConfig != null ? passiveConfig : PassiveHealthCheckConfig.DISABLED;
        this.activeConfig = activeConfig != null ? activeConfig : ActiveHealthCheckConfig.DISABLED;
        this.ejectionBudget = new EjectionBudget(hostsArray, this.passiveConfig.maxEjectionPercent());
        for (UpstreamHost host : hostsArray) {
            if (host instanceof AtomicUpstreamHost atomic) {
                atomic.attachBudget(ejectionBudget);
            }
        }

        if (this.activeConfig.enabled()) {
            this.proberTransport = probeTransport != null
                    ? probeTransport
                    : new JdkHttpProbeTransport(this.activeConfig.timeoutMillis());
            this.prober = new HealthProber(allHosts, this.activeConfig, proberTransport, ejectionBudget);
            this.proberExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upstream-prober-" + name);
                t.setDaemon(true);
                return t;
            });
            long interval = this.activeConfig.checkIntervalMillis();
            proberExecutor.scheduleWithFixedDelay(this::probeNow, interval, interval, TimeUnit.MILLISECONDS);
        } else {
            this.proberExecutor = null;
            this.proberTransport = null;
            this.prober = null;
        }
    }

    /**
     * Runs one probe round on the calling thread, as the scheduler does on each interval.
     * Does nothing when active health checking is disabled or a round is already running.
     * This is off the selection path, which never touches the round lock.
     */
    public void probeNow() {
        HealthProber active = prober;
        if (active != null && probeRound.tryLock()) {
            try {
                active.probeRound();
            } catch (RuntimeException e) {
                // A failed round must not cancel the schedule; the next interval probes again
            } finally {
                probeRound.unlock();
            }
        }
    }

    /** Returns whether the prober thread is running, which is until {@link #shutdown()}. */
    public boolean isProbing() {
        return proberExecutor != null && !proberExecutor.isShutdown();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<UpstreamHost> allHosts() {
        return allHosts;
    }

    @Override
    public List<UpstreamHost> healthyHosts() {
        // Off the selection path: returns allHosts itself when every host is available, else a new list
        int healthyCount = 0;
        for (UpstreamHost host : hostsArray) {
            if (host.isAvailable()) {
                healthyCount++;
            }
        }
        if (healthyCount == hostsArray.length) {
            return allHosts;
        }
        if (healthyCount == 0) {
            return Collections.emptyList();
        }

        List<UpstreamHost> result = new ArrayList<>(healthyCount);
        for (UpstreamHost host : hostsArray) {
            if (host.isAvailable()) {
                result.add(host);
            }
        }
        return result;
    }

    @Override
    public int totalHostCount() {
        return hostsArray.length;
    }

    @Override
    public int healthyHostCount() {
        int count = 0;
        for (UpstreamHost host : hostsArray) {
            if (host.isAvailable()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return algorithm;
    }

    @Override
    public PassiveHealthCheckConfig passiveConfig() {
        return passiveConfig;
    }

    @Override
    public ActiveHealthCheckConfig activeConfig() {
        return activeConfig;
    }

    @Override
    public UpstreamHost selectHost() {
        return loadBalancer.select(this);
    }

    @Override
    public UpstreamHost selectHost(long hashKey) {
        return loadBalancer.select(this, hashKey);
    }

    @Override
    public void recordResult(UpstreamHost host, int statusCode, boolean transportError) {
        if (host == null) return;
        if (transportError || statusCode >= 500) {
            host.recordFailure(statusCode, transportError);
        } else {
            host.recordSuccess();
        }
    }

    @Override
    public void shutdown() {
        if (proberExecutor != null) {
            proberExecutor.shutdownNow();
        }
        if (proberTransport != null) {
            proberTransport.close();
        }
    }
}
