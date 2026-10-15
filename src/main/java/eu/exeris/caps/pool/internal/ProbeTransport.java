package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.UpstreamEndpoint;

import java.io.IOException;

/**
 * The wire used by the active health prober, replaceable so a probe round can run without a network.
 *
 * @since 0.1.0
 */
public interface ProbeTransport extends AutoCloseable {

    /**
     * Sends one health probe to an endpoint.
     *
     * @param endpoint       the endpoint to probe
     * @param path           the absolute health check path, appended to the endpoint's base URI
     * @param timeoutMillis  how long to wait for the response
     * @return the HTTP status code of the response
     * @throws IOException          if the endpoint is unreachable, resets the connection or times out
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    int probe(UpstreamEndpoint endpoint, String path, long timeoutMillis) throws IOException, InterruptedException;

    /** Releases the transport's resources. */
    @Override
    default void close() {
    }
}
