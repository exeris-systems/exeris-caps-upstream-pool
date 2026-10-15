package eu.exeris.caps.pool.api;

import eu.exeris.caps.pool.internal.AtomicUpstreamHost;
import eu.exeris.caps.pool.internal.DefaultUpstreamCluster;
import eu.exeris.caps.pool.internal.DefaultUpstreamPoolService;
import eu.exeris.caps.pool.internal.FastRoundRobinLoadBalancer;
import eu.exeris.caps.pool.internal.HashLoadBalancer;
import eu.exeris.caps.pool.internal.LeastConnectionsLoadBalancer;
import eu.exeris.caps.pool.internal.RandomLoadBalancer;
import eu.exeris.caps.pool.internal.SmoothWeightedRoundRobinLoadBalancer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Static factories and builders for creating upstream clusters, hosts, load balancers, and services.
 *
 * @since 0.1.0
 */
public final class UpstreamPools {

    private UpstreamPools() {
    }

    /**
     * Creates a new empty {@link UpstreamPoolService}.
     */
    public static UpstreamPoolService newService() {
        return new DefaultUpstreamPoolService();
    }

    /**
     * Folds a client address into the 64-bit key that {@link LoadBalancingAlgorithm#IP_HASH} selects by.
     *
     * <p>The caller supplies the address bytes (4 for IPv4, 16 for IPv6), taken from the connection's peer
     * address and never from request headers. The same bytes always give the same key. The fold is FNV-1a
     * and allocates nothing.
     *
     * @param address the raw client address bytes
     * @return the key to pass to {@link UpstreamCluster#selectHost(long)}
     */
    public static long addressKey(byte[] address) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : address) {
            hash = (hash ^ (b & 0xffL)) * 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Starts building a new {@link UpstreamCluster}.
     *
     * @param name unique cluster name
     * @return a new ClusterBuilder instance
     */
    public static ClusterBuilder cluster(String name) {
        return new ClusterBuilder(name);
    }

    /**
     * Creates an atomic {@link UpstreamHost} for an endpoint.
     */
    public static UpstreamHost host(UpstreamEndpoint endpoint, PassiveHealthCheckConfig passiveConfig) {
        return new AtomicUpstreamHost(endpoint, passiveConfig);
    }

    /**
     * Fluent builder for {@link UpstreamCluster}.
     */
    public static final class ClusterBuilder {
        private final String name;
        private final List<UpstreamEndpoint> endpoints = new ArrayList<>();
        private LoadBalancingAlgorithm algorithm = LoadBalancingAlgorithm.ROUND_ROBIN;
        private LoadBalancer customLoadBalancer;
        private PassiveHealthCheckConfig passiveConfig = PassiveHealthCheckConfig.DEFAULT;
        private ActiveHealthCheckConfig activeConfig = ActiveHealthCheckConfig.DISABLED;

        private ClusterBuilder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public ClusterBuilder addEndpoint(UpstreamEndpoint endpoint) {
            endpoints.add(Objects.requireNonNull(endpoint, "endpoint"));
            return this;
        }

        public ClusterBuilder addEndpoint(String baseUri) {
            return addEndpoint(UpstreamEndpoint.of(baseUri));
        }

        public ClusterBuilder addEndpoint(String baseUri, int weight) {
            return addEndpoint(UpstreamEndpoint.of(baseUri, weight));
        }

        public ClusterBuilder endpoints(List<UpstreamEndpoint> endpoints) {
            this.endpoints.clear();
            this.endpoints.addAll(endpoints);
            return this;
        }

        public ClusterBuilder algorithm(LoadBalancingAlgorithm algorithm) {
            this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
            return this;
        }

        public ClusterBuilder loadBalancer(LoadBalancer customLoadBalancer) {
            this.customLoadBalancer = customLoadBalancer;
            return this;
        }

        public ClusterBuilder passiveHealthCheck(PassiveHealthCheckConfig passiveConfig) {
            this.passiveConfig = passiveConfig;
            return this;
        }

        public ClusterBuilder activeHealthCheck(ActiveHealthCheckConfig activeConfig) {
            this.activeConfig = activeConfig;
            return this;
        }

        public UpstreamCluster build() {
            if (endpoints.isEmpty()) {
                throw new IllegalStateException("Cluster must have at least one endpoint");
            }

            List<UpstreamHost> hosts = new ArrayList<>(endpoints.size());
            for (UpstreamEndpoint ep : endpoints) {
                hosts.add(new AtomicUpstreamHost(ep, passiveConfig));
            }

            LoadBalancer lb = customLoadBalancer;
            if (lb == null) {
                lb = switch (algorithm) {
                    case ROUND_ROBIN -> new FastRoundRobinLoadBalancer();
                    case IP_HASH -> new HashLoadBalancer();
                    case RANDOM -> new RandomLoadBalancer();
                    case LEAST_CONNECTIONS -> new LeastConnectionsLoadBalancer();
                    case WEIGHTED_ROUND_ROBIN -> new SmoothWeightedRoundRobinLoadBalancer();
                };
            }

            return new DefaultUpstreamCluster(name, hosts, algorithm, lb, passiveConfig, activeConfig);
        }
    }
}
