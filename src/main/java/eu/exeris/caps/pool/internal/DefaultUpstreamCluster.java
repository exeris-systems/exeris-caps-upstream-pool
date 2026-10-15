package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.ActiveHealthCheckConfig;
import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.LoadBalancer;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Default zero-allocation implementation of {@link UpstreamCluster}.
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

    private final ScheduledExecutorService proberExecutor;
    private final HttpClient proberClient;

    public DefaultUpstreamCluster(
            String name,
            List<UpstreamHost> hosts,
            LoadBalancingAlgorithm algorithm,
            LoadBalancer loadBalancer,
            PassiveHealthCheckConfig passiveConfig,
            ActiveHealthCheckConfig activeConfig
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.allHosts = List.copyOf(hosts);
        this.hostsArray = this.allHosts.toArray(new UpstreamHost[0]);
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        this.passiveConfig = passiveConfig != null ? passiveConfig : PassiveHealthCheckConfig.DISABLED;
        this.activeConfig = activeConfig != null ? activeConfig : ActiveHealthCheckConfig.DISABLED;

        if (this.activeConfig.enabled()) {
            this.proberExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "upstream-prober-" + name);
                t.setDaemon(true);
                return t;
            });
            this.proberClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(activeConfig.timeoutMillis()))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            startActiveProber();
        } else {
            this.proberExecutor = null;
            this.proberClient = null;
        }
    }

    private void startActiveProber() {
        if (proberExecutor == null) return;
        HttpClient client = proberClient;

        proberExecutor.scheduleWithFixedDelay(() -> {
            for (UpstreamHost host : hostsArray) {
                try {
                    String url = host.endpoint().baseUri() + activeConfig.healthCheckPath();
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMillis(activeConfig.timeoutMillis()))
                            .GET()
                            .build();
                    HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() == 200) {
                        host.recordSuccess();
                    } else {
                        host.recordFailure(resp.statusCode(), false);
                    }
                } catch (Exception e) {
                    host.recordFailure(0, true);
                }
            }
        }, activeConfig.checkIntervalMillis(), activeConfig.checkIntervalMillis(), TimeUnit.MILLISECONDS);
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
        // Fast path: if all are healthy, return allHosts without allocation
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
        if (proberClient != null) {
            proberClient.close();
        }
    }
}
