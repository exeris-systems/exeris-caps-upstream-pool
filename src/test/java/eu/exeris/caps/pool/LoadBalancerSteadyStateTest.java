package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.LoadBalancingAlgorithm;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamHost;
import eu.exeris.caps.pool.api.UpstreamPools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Selection: lock-free and allocation-free in steady state, and each algorithm as documented")
class LoadBalancerSteadyStateTest {

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

    @ParameterizedTest
    @EnumSource(LoadBalancingAlgorithm.class)
    @DisplayName("in fail-open mode selection never offers a draining host")
    void failOpenNeverOffersADrainingHost(LoadBalancingAlgorithm algorithm) {
        UpstreamCluster cluster = cluster(algorithm);
        cluster.allHosts().get(0).setStatus(EndpointHealthStatus.DRAINING);
        for (int i = 1; i < 4; i++) {
            cluster.allHosts().get(i).markUnhealthy(Duration.ofMinutes(5).toNanos());
        }
        for (int i = 0; i < 100; i++) {
            assertThat(cluster.selectHost()).isNotSameAs(cluster.allHosts().get(0));
            assertThat(cluster.selectHost(i)).isNotSameAs(cluster.allHosts().get(0));
        }
    }

    private static final List<String> SELECTION_PATH_CLASSES = List.of(
            "eu.exeris.caps.pool.internal.Candidates",
            "eu.exeris.caps.pool.internal.FastRoundRobinLoadBalancer",
            "eu.exeris.caps.pool.internal.LeastConnectionsLoadBalancer",
            "eu.exeris.caps.pool.internal.RandomLoadBalancer",
            "eu.exeris.caps.pool.internal.HashLoadBalancer",
            "eu.exeris.caps.pool.internal.SmoothWeightedRoundRobinLoadBalancer",
            "eu.exeris.caps.pool.internal.SmoothWeightedRoundRobinLoadBalancer$Schedule",
            "eu.exeris.caps.pool.internal.AtomicUpstreamHost",
            "eu.exeris.caps.pool.internal.EjectionBudget");

    @Test
    @DisplayName("the selection path has no monitor, no synchronized method and no java.util.concurrent.locks reference")
    void selectionPathTakesNoLock() throws IOException {
        for (String name : SELECTION_PATH_CLASSES) {
            ClassModel model = ClassFile.of().parse(read(name));
            model.methods().forEach(method -> {
                assertThat(Modifier.isSynchronized(method.flags().flagsMask()))
                        .as("%s.%s is synchronized", name, method.methodName().stringValue()).isFalse();
                method.code().ifPresent(code -> code.elementList().forEach(element -> {
                    assertThat(element).as("%s.%s takes a monitor", name, method.methodName().stringValue())
                            .isNotInstanceOf(MonitorInstruction.class);
                    if (element instanceof InvokeInstruction invoke) {
                        assertThat(invoke.owner().asInternalName())
                                .as("%s.%s calls a lock", name, method.methodName().stringValue())
                                .doesNotStartWith("java/util/concurrent/locks/");
                    }
                }));
            });
        }
    }

    private static byte[] read(String className) throws IOException {
        try (InputStream in = LoadBalancerSteadyStateTest.class.getClassLoader()
                .getResourceAsStream(className.replace('.', '/') + ".class")) {
            assertThat(in).as(className).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("concurrent weighted selections keep the configured proportions exactly")
    void concurrentWeightedSelectionsKeepProportions() throws Exception {
        UpstreamCluster cluster = cluster(LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN);
        int threads = 8;
        int perThread = 11_000; // 88000 selections = 8000 cycles of 11 slots
        Map<String, Integer>[] counts = new Map[threads];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Map<String, Integer> local = new HashMap<>();
            counts[t] = local;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    local.merge(cluster.selectHost().endpoint().authority(), 1, Integer::sum);
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        Map<String, Integer> total = new HashMap<>();
        for (Map<String, Integer> m : counts) {
            m.forEach((k, v) -> total.merge(k, v, Integer::sum));
        }
        assertThat(total).containsEntry("a:9001", 40_000).containsEntry("b:9002", 24_000)
                .containsEntry("c:9003", 8_000).containsEntry("d:9004", 16_000);
    }

    @Test
    @DisplayName("smooth weighted round-robin spreads a heavy host through the cycle (5/1/1 gives a a b a c a a)")
    void weightedSelectionIsSmooth() {
        UpstreamCluster cluster = UpstreamPools.cluster("c")
                .algorithm(LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN)
                .addEndpoint("http://a:9001", 5)
                .addEndpoint("http://b:9002", 1)
                .addEndpoint("http://c:9003", 1)
                .build();
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            order.append(cluster.selectHost().endpoint().host());
        }
        assertThat(order.toString()).isEqualTo("aabacaa");
    }

    @Test
    @DisplayName("weighted selection follows availability: an ejected host leaves the cycle and returns with it")
    void weightedSelectionFollowsAvailability() {
        UpstreamCluster cluster = cluster(LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN);
        UpstreamHost a = cluster.allHosts().get(0);
        a.setStatus(EndpointHealthStatus.UNHEALTHY);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            counts.merge(cluster.selectHost().endpoint().host(), 1, Integer::sum);
        }
        assertThat(counts).doesNotContainKey("a").containsEntry("b", 30).containsEntry("c", 10).containsEntry("d", 20);

        a.markHealthy();
        counts.clear();
        for (int i = 0; i < 110; i++) {
            counts.merge(cluster.selectHost().endpoint().host(), 1, Integer::sum);
        }
        assertThat(counts).containsEntry("a", 50).containsEntry("b", 30).containsEntry("c", 10).containsEntry("d", 20);
    }

    @Test
    @DisplayName("very large weights are scaled to a bounded cycle and stay proportional")
    void largeWeightsAreScaled() {
        UpstreamCluster cluster = UpstreamPools.cluster("c")
                .algorithm(LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN)
                .addEndpoint("http://a:9001", 2_000_000_000)
                .addEndpoint("http://b:9002", 1_000_000_000)
                .build();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 3_000; i++) {
            counts.merge(cluster.selectHost().endpoint().host(), 1, Integer::sum);
        }
        assertThat(counts).containsEntry("a", 2_000).containsEntry("b", 1_000);
    }

    @Test
    @DisplayName("RANDOM reaches every available host, never an ejected one, and is not a rotation")
    void randomSelection() {
        UpstreamCluster cluster = cluster(LoadBalancingAlgorithm.RANDOM);
        cluster.allHosts().get(2).markUnhealthy(Duration.ofMinutes(5).toNanos());
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder sequence = new StringBuilder();
        for (int i = 0; i < 3_000; i++) {
            String host = cluster.selectHost().endpoint().host();
            counts.merge(host, 1, Integer::sum);
            if (i < 12) {
                sequence.append(host);
            }
        }
        assertThat(counts).containsOnlyKeys("a", "b", "d");
        assertThat(counts.values()).allMatch(n -> n > 700);
        assertThat(sequence.toString()).isNotEqualTo("abdabdabdabd");
    }

    @Test
    @DisplayName("IP_HASH sends the same key to the same host, spreads keys, and moves only the ejected host's keys")
    void ipHashIsAffineAndStable() {
        UpstreamCluster cluster = cluster(LoadBalancingAlgorithm.IP_HASH);
        int keys = 4_000;
        UpstreamHost[] before = new UpstreamHost[keys];
        Map<UpstreamHost, Integer> spread = new HashMap<>();
        for (int k = 0; k < keys; k++) {
            long key = UpstreamPools.addressKey(new byte[]{10, 0, (byte) (k >> 8), (byte) k});
            before[k] = cluster.selectHost(key);
            assertThat(cluster.selectHost(key)).isSameAs(before[k]);
            spread.merge(before[k], 1, Integer::sum);
        }
        assertThat(spread).hasSize(4);
        assertThat(spread.values()).allMatch(n -> n > 600);

        UpstreamHost gone = cluster.allHosts().get(1);
        gone.markUnhealthy(Duration.ofMinutes(5).toNanos());
        for (int k = 0; k < keys; k++) {
            long key = UpstreamPools.addressKey(new byte[]{10, 0, (byte) (k >> 8), (byte) k});
            UpstreamHost now = cluster.selectHost(key);
            assertThat(now).isNotSameAs(gone);
            if (before[k] != gone) {
                assertThat(now).as("key %d kept its host", k).isSameAs(before[k]);
            }
        }
    }

    @Test
    @DisplayName("IP_HASH without a key rotates over the available hosts")
    void ipHashWithoutKeyRotates() {
        UpstreamCluster cluster = cluster(LoadBalancingAlgorithm.IP_HASH);
        Map<UpstreamHost, Integer> counts = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            counts.merge(cluster.selectHost(), 1, Integer::sum);
        }
        assertThat(counts.values()).containsOnly(100);
    }

    @Test
    @DisplayName("addressKey is deterministic and distinguishes addresses")
    void addressKey() {
        assertThat(UpstreamPools.addressKey(new byte[]{10, 0, 0, 1})).isEqualTo(UpstreamPools.addressKey(new byte[]{10, 0, 0, 1}));
        assertThat(UpstreamPools.addressKey(new byte[]{10, 0, 0, 1})).isNotEqualTo(UpstreamPools.addressKey(new byte[]{10, 0, 0, 2}));
    }
}
