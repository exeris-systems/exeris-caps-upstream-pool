package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmoothWeightedRoundRobinLoadBalancerTest {

    @Test
    @DisplayName("Smooth weighted round-robin distributes in proportion to weights")
    void testWeightedDistribution() {
        UpstreamCluster cluster = UpstreamPools.cluster("test-cluster")
                .algorithm(LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN)
                .addEndpoint("http://node-a:9001", 5) // weight 5
                .addEndpoint("http://node-b:9002", 1) // weight 1
                .build();

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            UpstreamHost selected = cluster.selectHost();
            assertThat(selected).isNotNull();
            counts.merge(selected.endpoint().authority(), 1, Integer::sum);
        }

        assertThat(counts.get("node-a:9001")).isEqualTo(50);
        assertThat(counts.get("node-b:9002")).isEqualTo(10);
    }
}
