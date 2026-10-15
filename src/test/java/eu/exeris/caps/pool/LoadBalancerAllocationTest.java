package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamPools;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Contract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Measures the steady-state allocation of host selection with the kernel TCK's {@link JfrAllocationMonitor}:
 * JFR allocation events for {@code eu.exeris.*} types plus the exact per-thread allocated-bytes delta, which
 * under {@link Contract#zero()} must stay below one byte per iteration. The clusters are built before the
 * measurement; only the two selection entry points run inside it.
 */
@DisplayName("Selection allocation")
class LoadBalancerAllocationTest {

    private static final String SUBSYSTEM = "UpstreamPool";

    private static UpstreamCluster cluster(LoadBalancingAlgorithm algorithm) {
        return UpstreamPools.cluster("c")
                .algorithm(algorithm)
                .passiveHealthCheck(PassiveHealthCheckConfig.of(3, Duration.ofMinutes(5)))
                .addEndpoint("http://a:9001", 5)
                .addEndpoint("http://b:9002", 3)
                .addEndpoint("http://c:9003", 1)
                .addEndpoint("http://d:9004", 2)
                .build();
    }

    private static void assertZeroAllocation(String what, UpstreamCluster cluster) throws IOException {
        Config config = Config.ofDefaults(SUBSYSTEM, "LoadBalancerAllocationTest", Contract.zero());
        long[] sink = new long[1];
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            long acc = 0;
            for (int i = 0; i < iterations; i++) {
                acc += cluster.selectHost().endpoint().port() + cluster.selectHost(i).endpoint().port();
            }
            sink[0] = acc;
        });
        assertThat(sink[0]).as("workload ran").isPositive();
        JfrAllocationMonitor.assertContract(config, result, what);
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingAlgorithm.class)
    @DisplayName("selection allocates nothing with every host available")
    void noAllocationWhenAllAvailable(LoadBalancingAlgorithm algorithm) throws IOException {
        assertZeroAllocation(algorithm + ", all available", cluster(algorithm));
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingAlgorithm.class)
    @DisplayName("selection allocates nothing while a host is ejected")
    void noAllocationWhileAHostIsEjected(LoadBalancingAlgorithm algorithm) throws IOException {
        UpstreamCluster cluster = cluster(algorithm);
        cluster.allHosts().get(1).markUnhealthy(Duration.ofMinutes(5).toNanos());
        assertZeroAllocation(algorithm + ", one ejected", cluster);
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingAlgorithm.class)
    @DisplayName("selection allocates nothing in fail-open mode")
    void noAllocationWhenFailingOpen(LoadBalancingAlgorithm algorithm) throws IOException {
        UpstreamCluster cluster = cluster(algorithm);
        cluster.allHosts().get(0).setStatus(EndpointHealthStatus.DRAINING);
        for (int i = 1; i < 4; i++) {
            cluster.allHosts().get(i).markUnhealthy(Duration.ofMinutes(5).toNanos());
        }
        assertZeroAllocation(algorithm + ", fail-open", cluster);
    }

    @Test
    @DisplayName("the measurement fails for a workload that allocates, so a pass above is not vacuous")
    void measurementDetectsAllocation() throws IOException {
        Config config = Config.ofDefaults(SUBSYSTEM, "LoadBalancerAllocationTest", Contract.zero());
        Object[] sink = new Object[1];
        JfrAllocationMonitor.Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                sink[0] = new StringBuilder("q=").append(i).toString();
            }
        });
        assertThatThrownBy(() -> JfrAllocationMonitor.assertContract(config, result, "control"))
                .isInstanceOf(AssertionError.class);
    }
}
