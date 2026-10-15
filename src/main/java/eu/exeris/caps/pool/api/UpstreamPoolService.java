package eu.exeris.caps.pool.api;

import java.util.Collection;
import java.util.Optional;

/**
 * Service registry and lifecycle manager for named upstream clusters.
 *
 * @since 0.1.0
 */
public interface UpstreamPoolService extends AutoCloseable {

    /**
     * Looks up an upstream cluster by name.
     *
     * @param name cluster identifier
     * @return an Optional containing the cluster if registered
     */
    Optional<UpstreamCluster> getCluster(String name);

    /**
     * Registers a new or updated upstream cluster.
     *
     * @param cluster the cluster to register
     */
    void registerCluster(UpstreamCluster cluster);

    /**
     * Deregisters and shuts down an upstream cluster.
     *
     * @param name cluster identifier
     */
    void deregisterCluster(String name);

    /**
     * Returns a read-only live view of the registered clusters.
     *
     * @return all currently registered upstream clusters
     */
    Collection<UpstreamCluster> clusters();

    @Override
    void close();
}
