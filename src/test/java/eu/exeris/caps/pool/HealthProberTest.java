package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.ActiveHealthCheckConfig;
import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamEndpoint;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.internal.AtomicUpstreamHost;
import eu.exeris.caps.pool.internal.DefaultUpstreamCluster;
import eu.exeris.caps.pool.internal.EjectionBudget;
import eu.exeris.caps.pool.internal.HealthProber;
import eu.exeris.caps.pool.internal.FastRoundRobinLoadBalancer;
import eu.exeris.caps.pool.internal.ProbeTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Active prober: thresholds, independence from passive checking, and the ejection budget")
class HealthProberTest {

    /** Scripted transport: a status per authority, or an IOException for {@code -1}. No network. */
    private static final class ScriptedTransport implements ProbeTransport {
        final Map<String, Integer> status = new HashMap<>();
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public int probe(UpstreamEndpoint endpoint, String path, long timeoutMillis) throws IOException {
            probes.incrementAndGet();
            int code = status.getOrDefault(endpoint.authority(), 200);
            if (code < 0) {
                throw new IOException("refused");
            }
            return code;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final ActiveHealthCheckConfig ACTIVE =
            new ActiveHealthCheckConfig(true, 1000, 500, "/health", 2, 3);

    private static List<UpstreamHost> hosts(PassiveHealthCheckConfig passive, int count) {
        List<UpstreamHost> hosts = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            hosts.add(new AtomicUpstreamHost(UpstreamEndpoint.of("http://h" + i + ":900" + i), passive));
        }
        return hosts;
    }

    private static HealthProber prober(List<UpstreamHost> hosts, PassiveHealthCheckConfig passive,
                                       ProbeTransport transport) {
        return new HealthProber(hosts, ACTIVE, transport,
                new EjectionBudget(hosts.toArray(new UpstreamHost[0]), passive.maxEjectionPercent()));
    }

    @Test
    @DisplayName("probe failures eject a host with passive checking disabled, only at unhealthyThreshold")
    void activeEjectsWithoutPassive() {
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(PassiveHealthCheckConfig.DISABLED, 2);
        HealthProber prober = prober(hosts, PassiveHealthCheckConfig.DISABLED, transport);
        transport.status.put("h1:9001", 503);

        prober.probeRound();
        prober.probeRound();
        assertThat(hosts.get(0).isAvailable()).as("2 failures < unhealthyThreshold 3").isTrue();
        prober.probeRound();

        assertThat(hosts.get(0).status()).isEqualTo(EndpointHealthStatus.UNHEALTHY);
        assertThat(hosts.get(0).isAvailable()).isFalse();
        assertThat(hosts.get(1).isAvailable()).isTrue();
        assertThat(hosts.get(0).totalFailures()).as("probes are not request statistics").isZero();
    }

    @Test
    @DisplayName("an unreachable host (I/O error) is ejected the same way")
    void ioErrorCountsAsFailure() {
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(PassiveHealthCheckConfig.DISABLED, 2);
        HealthProber prober = prober(hosts, PassiveHealthCheckConfig.DISABLED, transport);
        transport.status.put("h2:9002", -1);

        for (int i = 0; i < 3; i++) {
            prober.probeRound();
        }

        assertThat(hosts.get(1).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("a success between failures resets the run, so the threshold counts consecutive failures")
    void failuresMustBeConsecutive() {
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(PassiveHealthCheckConfig.DISABLED, 2);
        HealthProber prober = prober(hosts, PassiveHealthCheckConfig.DISABLED, transport);

        for (int round = 0; round < 3; round++) {
            transport.status.put("h1:9001", 500);
            prober.probeRound();
            prober.probeRound();
            transport.status.put("h1:9001", 200);
            prober.probeRound();
        }

        assertThat(hosts.get(0).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("healthyThreshold consecutive successes reinstate an ejected host, no sooner")
    void recoveryNeedsHealthyThreshold() {
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(PassiveHealthCheckConfig.DISABLED, 2);
        HealthProber prober = prober(hosts, PassiveHealthCheckConfig.DISABLED, transport);
        transport.status.put("h1:9001", 500);
        for (int i = 0; i < 3; i++) {
            prober.probeRound();
        }
        assertThat(hosts.get(0).isAvailable()).isFalse();

        transport.status.put("h1:9001", 204);
        prober.probeRound();
        assertThat(hosts.get(0).isAvailable()).as("1 success < healthyThreshold 2").isFalse();
        transport.status.put("h1:9001", 500);
        prober.probeRound();
        transport.status.put("h1:9001", 200);
        prober.probeRound();
        assertThat(hosts.get(0).isAvailable()).as("the failure reset the success run").isFalse();
        prober.probeRound();

        assertThat(hosts.get(0).status()).isEqualTo(EndpointHealthStatus.HEALTHY);
        assertThat(hosts.get(0).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("a draining host is neither probed nor revived")
    void drainingHostIsLeftAlone() {
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(PassiveHealthCheckConfig.DISABLED, 2);
        hosts.get(0).setStatus(EndpointHealthStatus.DRAINING);
        HealthProber prober = prober(hosts, PassiveHealthCheckConfig.DISABLED, transport);

        prober.probeRound();
        prober.probeRound();

        assertThat(transport.probes.get()).isEqualTo(2);
        assertThat(hosts.get(0).status()).isEqualTo(EndpointHealthStatus.DRAINING);
    }

    @Test
    @DisplayName("maxEjectionPercent bounds ejections; the budget refuses the next one and retries it later")
    void budgetBoundsActiveEjections() {
        PassiveHealthCheckConfig passive = PassiveHealthCheckConfig.of(3, java.time.Duration.ofSeconds(5), 50);
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(passive, 4);
        HealthProber prober = prober(hosts, passive, transport);
        for (UpstreamHost h : hosts) {
            transport.status.put(h.endpoint().authority(), 500);
        }

        for (int i = 0; i < 3; i++) {
            prober.probeRound();
        }
        long ejected = hosts.stream().filter(h -> !h.isAvailable()).count();
        assertThat(ejected).as("50% of 4 hosts").isEqualTo(2);

        // One ejected host recovers: the budget has room again, and a still-failing host is ejected then
        UpstreamHost ejectedHost = hosts.stream().filter(h -> !h.isAvailable()).findFirst().orElseThrow();
        transport.status.put(ejectedHost.endpoint().authority(), 200);
        prober.probeRound();
        prober.probeRound();
        assertThat(ejectedHost.isAvailable()).isTrue();
        assertThat(hosts.stream().filter(h -> !h.isAvailable()).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the last available host is never ejected, even at 100 percent")
    void lastHostIsNeverEjected() {
        ScriptedTransport transport = new ScriptedTransport();
        List<UpstreamHost> hosts = hosts(PassiveHealthCheckConfig.DISABLED, 3);
        HealthProber prober = prober(hosts, PassiveHealthCheckConfig.DISABLED, transport);
        for (UpstreamHost h : hosts) {
            transport.status.put(h.endpoint().authority(), 500);
        }

        for (int i = 0; i < 10; i++) {
            prober.probeRound();
        }

        assertThat(hosts.stream().filter(UpstreamHost::isAvailable).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("passive ejections draw on the same budget as the prober")
    void passiveEjectionsShareTheBudget() {
        PassiveHealthCheckConfig passive = PassiveHealthCheckConfig.of(2, java.time.Duration.ofMinutes(5), 50);
        DefaultUpstreamCluster cluster = new DefaultUpstreamCluster("c", hosts(passive, 4),
                LoadBalancingAlgorithm.ROUND_ROBIN, new FastRoundRobinLoadBalancer(), passive,
                ActiveHealthCheckConfig.DISABLED);

        for (UpstreamHost h : cluster.allHosts()) {
            cluster.recordResult(h, 503, false);
            cluster.recordResult(h, 503, false);
        }

        assertThat(cluster.healthyHostCount()).isEqualTo(2);
        UpstreamHost refused = cluster.allHosts().stream().filter(UpstreamHost::isAvailable).findFirst().orElseThrow();
        assertThat(refused.consecutiveFailures()).as("refused host keeps counting").isEqualTo(2);
    }

    @Test
    @DisplayName("a cluster probes through the injected transport on probeNow() and shutdown stops the prober")
    void clusterRunsAndStopsItsProber() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.status.put("h1:9001", 500);
        ActiveHealthCheckConfig hourly = new ActiveHealthCheckConfig(true, 3_600_000, 500, "/health", 1, 1);
        DefaultUpstreamCluster cluster = new DefaultUpstreamCluster("c", hosts(PassiveHealthCheckConfig.DISABLED, 2),
                LoadBalancingAlgorithm.ROUND_ROBIN, new FastRoundRobinLoadBalancer(),
                PassiveHealthCheckConfig.DISABLED, hourly, transport);
        assertThat(cluster.isProbing()).isTrue();

        cluster.probeNow();

        assertThat(cluster.allHosts().get(0).isAvailable()).isFalse();
        assertThat(cluster.selectHost()).isSameAs(cluster.allHosts().get(1));
        cluster.shutdown();
        assertThat(cluster.isProbing()).isFalse();
        assertThat(transport.closes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an injected clock drives the passive window: a late success does not cut it short")
    void lateSuccessDoesNotLiftWindow() {
        AtomicLong now = new AtomicLong(1_000);
        AtomicUpstreamHost host = new AtomicUpstreamHost(UpstreamEndpoint.of("http://h1:9001"),
                PassiveHealthCheckConfig.of(1, java.time.Duration.ofNanos(500)), now::get);

        host.recordFailure(500, false);
        assertThat(host.isAvailable()).isFalse();
        host.recordSuccess();
        assertThat(host.isAvailable()).as("window open: a late success is not a reinstatement").isFalse();

        now.set(1_500);
        assertThat(host.isAvailable()).as("window elapsed: probation").isTrue();
        host.recordSuccess();
        assertThat(host.status()).isEqualTo(EndpointHealthStatus.HEALTHY);
    }
}
