package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.ActiveHealthCheckConfig;
import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamEndpoint;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.caps.pool.api.UpstreamPools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Upstream pool: endpoint validation, draining and cluster replacement")
class UpstreamPoolSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "backend:8080", "ftp://backend:21", "file:///etc/passwd", "http:///x", "/relative",
            "http://user@backend:8080", "http://backend:8080?x=1", "http://backend:8080#f", "backend"
    })
    @DisplayName("a base URI without an http or https scheme and a host is refused rather than defaulted to localhost")
    void endpointRequiresSchemeAndHost(String uri) {
        assertThatThrownBy(() -> UpstreamEndpoint.of(uri)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("http and https endpoints keep their host, port and path")
    void validEndpointsAreAccepted() {
        UpstreamEndpoint a = UpstreamEndpoint.of("https://api.example.test/base/");
        assertThat(a.host()).isEqualTo("api.example.test");
        assertThat(a.port()).isEqualTo(443);
        assertThat(a.baseUri()).isEqualTo("https://api.example.test/base");
        assertThat(UpstreamEndpoint.of("http://[::1]:9000").port()).isEqualTo(9000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/b", "a@b", "a b", "a?b", "a#b", "", "a\\b"})
    @DisplayName("a host that could reshape a URI built from it is refused")
    void hostIsPlain(String host) {
        assertThatThrownBy(() -> UpstreamEndpoint.of("id", host, 8080, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"health", "@evil.test/x", "//evil.test/x", "/h?x=1", "/h#f", "/a b", ""})
    @DisplayName("a health check path must be an absolute path that cannot change the probed authority")
    void healthCheckPathIsOriginForm(String path) {
        assertThatThrownBy(() -> ActiveHealthCheckConfig.enabled(Duration.ofSeconds(1), Duration.ofSeconds(1), path))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = LoadBalancingAlgorithm.class, names = {"ROUND_ROBIN", "LEAST_CONNECTIONS", "WEIGHTED_ROUND_ROBIN", "IP_HASH"})
    @DisplayName("when no host is available, selection stays open but never offers a draining host")
    void failOpenSkipsDrainingHosts(LoadBalancingAlgorithm algorithm) {
        UpstreamCluster cluster = UpstreamPools.cluster("c")
                .addEndpoint("http://a:9001")
                .addEndpoint("http://b:9002")
                .addEndpoint("http://c:9003")
                .algorithm(algorithm)
                .build();
        UpstreamHost a = cluster.allHosts().get(0);
        UpstreamHost b = cluster.allHosts().get(1);
        UpstreamHost c = cluster.allHosts().get(2);
        a.setStatus(EndpointHealthStatus.DRAINING);
        b.markUnhealthy(Duration.ofMinutes(5).toNanos());
        c.markUnhealthy(Duration.ofMinutes(5).toNanos());

        for (int i = 0; i < 20; i++) {
            assertThat(cluster.selectHost()).isNotSameAs(a).isNotNull();
            assertThat(cluster.selectHost(i)).isNotSameAs(a).isNotNull();
        }
        cluster.shutdown();
    }

    @Test
    @DisplayName("with every host draining nothing is selected")
    void allDrainingSelectsNothing() {
        UpstreamCluster cluster = UpstreamPools.cluster("c").addEndpoint("http://a:9001").build();
        cluster.allHosts().get(0).setStatus(EndpointHealthStatus.DRAINING);

        assertThat(cluster.selectHost()).isNull();
        assertThat(cluster.selectHost(7)).isNull();
    }

    @Test
    @DisplayName("passive ejection does not move a draining host out of DRAINING, and a success does not revive it")
    void passiveEjectionLeavesDrainingAlone() {
        UpstreamHost host = UpstreamPools.host(UpstreamEndpoint.of("http://a:9001"), PassiveHealthCheckConfig.of(2, Duration.ofSeconds(30)));
        host.setStatus(EndpointHealthStatus.DRAINING);

        host.recordFailure(503, false);
        host.recordFailure(503, false);
        host.recordSuccess();

        assertThat(host.status()).isEqualTo(EndpointHealthStatus.DRAINING);
        assertThat(host.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("registering a cluster under a name already in use shuts the replaced cluster down")
    void replacingAClusterShutsTheOldOneDown() {
        UpstreamPoolService service = UpstreamPools.newService();
        CountingCluster first = new CountingCluster("api");
        CountingCluster second = new CountingCluster("api");

        service.registerCluster(first);
        service.registerCluster(first);
        assertThat(first.shutdowns.get()).isZero();
        service.registerCluster(second);

        assertThat(first.shutdowns.get()).isEqualTo(1);
        assertThat(second.shutdowns.get()).isZero();
        assertThat(service.getCluster("api")).containsSame(second);
        service.close();
        assertThat(second.shutdowns.get()).isEqualTo(1);
    }

    private static final class CountingCluster implements UpstreamCluster {
        private final String name;
        final AtomicInteger shutdowns = new AtomicInteger();

        CountingCluster(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public List<UpstreamHost> allHosts() { return List.of(); }
        @Override public List<UpstreamHost> healthyHosts() { return List.of(); }
        @Override public int totalHostCount() { return 0; }
        @Override public int healthyHostCount() { return 0; }
        @Override public LoadBalancingAlgorithm algorithm() { return LoadBalancingAlgorithm.ROUND_ROBIN; }
        @Override public PassiveHealthCheckConfig passiveConfig() { return PassiveHealthCheckConfig.DISABLED; }
        @Override public ActiveHealthCheckConfig activeConfig() { return ActiveHealthCheckConfig.DISABLED; }
        @Override public UpstreamHost selectHost() { return null; }
        @Override public UpstreamHost selectHost(long hashKey) { return null; }
        @Override public void recordResult(UpstreamHost host, int statusCode, boolean transportError) { }
        @Override public void shutdown() { shutdowns.incrementAndGet(); }
    }
}
