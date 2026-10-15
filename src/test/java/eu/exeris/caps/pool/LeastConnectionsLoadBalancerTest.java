package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeastConnectionsLoadBalancerTest {

    @Test
    @DisplayName("Select host with fewest active connections")
    void testSelectsLeastConnections() {
        UpstreamCluster cluster = UpstreamPools.cluster("test-cluster")
                .algorithm(LoadBalancingAlgorithm.LEAST_CONNECTIONS)
                .addEndpoint("http://host-1:9001")
                .addEndpoint("http://host-2:9002")
                .addEndpoint("http://host-3:9003")
                .build();

        UpstreamHost h1 = cluster.allHosts().get(0);
        UpstreamHost h2 = cluster.allHosts().get(1);
        UpstreamHost h3 = cluster.allHosts().get(2);

        h1.incrementConnections(); // h1 has 1 conn
        h1.incrementConnections(); // h1 has 2 conns
        h2.incrementConnections(); // h2 has 1 conn
        // h3 has 0 conns

        UpstreamHost selected = cluster.selectHost();
        assertThat(selected).isSameAs(h3);

        h3.incrementConnections(); // h3 now has 1 conn
        // h2 and h3 both have 1 conn, h1 has 2 conns
        selected = cluster.selectHost();
        assertThat(selected).isIn(h2, h3);
    }
}
