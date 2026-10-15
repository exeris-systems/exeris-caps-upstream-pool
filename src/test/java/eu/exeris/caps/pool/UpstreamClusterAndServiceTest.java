package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.caps.pool.api.UpstreamPools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamClusterAndServiceTest {

    @Test
    @DisplayName("Register, lookup, and deregister cluster in service")
    void testServiceRegistry() {
        UpstreamPoolService service = UpstreamPools.newService();

        UpstreamCluster cluster = UpstreamPools.cluster("auth-cluster")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .addEndpoint("http://auth-1:8083")
                .addEndpoint("http://auth-2:8083")
                .build();

        service.registerCluster(cluster);
        assertThat(service.getCluster("auth-cluster")).contains(cluster);
        assertThat(service.clusters()).contains(cluster);

        service.deregisterCluster("auth-cluster");
        assertThat(service.getCluster("auth-cluster")).isEmpty();

        service.close();
    }

    @Test
    @DisplayName("Cluster records request results and triggers outlier detection")
    void testRecordResult() {
        UpstreamCluster cluster = UpstreamPools.cluster("bench-cluster")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .passiveHealthCheck(PassiveHealthCheckConfig.of(2, Duration.ofSeconds(10)))
                .addEndpoint("http://srv-1:9000")
                .addEndpoint("http://srv-2:9000")
                .build();

        UpstreamHost h1 = cluster.allHosts().get(0);
        UpstreamHost h2 = cluster.allHosts().get(1);

        cluster.recordResult(h1, 200, false);
        assertThat(h1.isAvailable()).isTrue();

        // 2 consecutive 500 errors on h1 triggers ejection
        cluster.recordResult(h1, 500, false);
        cluster.recordResult(h1, 503, false);
        assertThat(h1.isAvailable()).isFalse();
        assertThat(cluster.healthyHostCount()).isEqualTo(1);

        // Subsequent selections only return h2
        UpstreamHost next = cluster.selectHost();
        assertThat(next).isSameAs(h2);
    }
}
