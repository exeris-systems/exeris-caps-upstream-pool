package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FastRoundRobinLoadBalancerTest {

    @Test
    @DisplayName("Round-Robin cycles through endpoints in sequence")
    void testRoundRobinSequence() {
        UpstreamCluster cluster = UpstreamPools.cluster("test-cluster")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .addEndpoint("http://host-1:9001")
                .addEndpoint("http://host-2:9002")
                .addEndpoint("http://host-3:9003")
                .build();

        List<String> authorities = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UpstreamHost selected = cluster.selectHost();
            assertThat(selected).isNotNull();
            authorities.add(selected.endpoint().authority());
        }

        assertThat(authorities).containsExactly(
                "host-1:9001", "host-2:9002", "host-3:9003",
                "host-1:9001", "host-2:9002", "host-3:9003"
        );
    }

    @Test
    @DisplayName("Round-Robin skips unhealthy hosts")
    void testSkipsUnhealthy() {
        UpstreamCluster cluster = UpstreamPools.cluster("test-cluster")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .addEndpoint("http://host-1:9001")
                .addEndpoint("http://host-2:9002")
                .addEndpoint("http://host-3:9003")
                .build();

        // Mark host-2 unhealthy
        cluster.allHosts().get(1).markUnhealthy(1_000_000_000L);

        List<String> authorities = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UpstreamHost selected = cluster.selectHost();
            assertThat(selected).isNotNull();
            authorities.add(selected.endpoint().authority());
        }

        assertThat(authorities).containsExactly(
                "host-1:9001", "host-3:9003",
                "host-1:9001", "host-3:9003"
        );
    }

    @Test
    @DisplayName("Fail-open when all hosts are unhealthy")
    void testFailOpen() {
        UpstreamCluster cluster = UpstreamPools.cluster("test-cluster")
                .algorithm(LoadBalancingAlgorithm.ROUND_ROBIN)
                .addEndpoint("http://host-1:9001")
                .addEndpoint("http://host-2:9002")
                .build();

        // Mark all hosts unhealthy
        cluster.allHosts().forEach(h -> h.markUnhealthy(1_000_000_000L));
        assertThat(cluster.healthyHostCount()).isEqualTo(0);

        // Selection should still succeed rather than returning null
        UpstreamHost selected = cluster.selectHost();
        assertThat(selected).isNotNull();
    }
}
