package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.UpstreamCluster;
import eu.exeris.caps.pool.api.UpstreamPoolService;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory thread-safe implementation of {@link UpstreamPoolService}.
 *
 * @since 0.1.0
 */
public final class DefaultUpstreamPoolService implements UpstreamPoolService {

    private final ConcurrentHashMap<String, UpstreamCluster> clusters = new ConcurrentHashMap<>();

    @Override
    public Optional<UpstreamCluster> getCluster(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(clusters.get(name));
    }

    @Override
    public void registerCluster(UpstreamCluster cluster) {
        if (cluster != null) {
            UpstreamCluster replaced = clusters.put(cluster.name(), cluster);
            if (replaced != null && replaced != cluster) {
                // The replaced cluster is unreachable from here on; its prober must not outlive it
                replaced.shutdown();
            }
        }
    }

    @Override
    public void deregisterCluster(String name) {
        if (name != null) {
            UpstreamCluster removed = clusters.remove(name);
            if (removed != null) {
                removed.shutdown();
            }
        }
    }

    @Override
    public Collection<UpstreamCluster> clusters() {
        return Collections.unmodifiableCollection(clusters.values());
    }

    @Override
    public void close() {
        for (UpstreamCluster cluster : clusters.values()) {
            cluster.shutdown();
        }
        clusters.clear();
    }
}
