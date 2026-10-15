package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamEndpoint;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free, zero-allocation implementation of {@link UpstreamHost}.
 *
 * <p>Maintains in-flight active connection count, total request/failure metrics,
 * and passive outlier detection ejection window using primitive atomics and volatile reads.
 *
 * @since 0.1.0
 */
public final class AtomicUpstreamHost implements UpstreamHost {

    private final UpstreamEndpoint endpoint;
    private final PassiveHealthCheckConfig passiveConfig;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    private volatile EndpointHealthStatus status = EndpointHealthStatus.HEALTHY;
    private volatile long ejectionUntilNanos = 0L;

    public AtomicUpstreamHost(UpstreamEndpoint endpoint, PassiveHealthCheckConfig passiveConfig) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.passiveConfig = passiveConfig != null ? passiveConfig : PassiveHealthCheckConfig.DISABLED;
    }

    @Override
    public UpstreamEndpoint endpoint() {
        return endpoint;
    }

    @Override
    public EndpointHealthStatus status() {
        return status;
    }

    @Override
    public boolean isAvailable() {
        EndpointHealthStatus current = status;
        if (current == EndpointHealthStatus.DRAINING) {
            return false;
        }
        if (current == EndpointHealthStatus.UNHEALTHY) {
            long until = ejectionUntilNanos;
            if (until > 0 && System.nanoTime() >= until) {
                // Probationary trial: allow request to re-evaluate host health
                return true;
            }
            return false;
        }
        return true;
    }

    @Override
    public int activeConnections() {
        int val = activeConnections.get();
        return Math.max(0, val);
    }

    @Override
    public long totalRequests() {
        return totalRequests.get();
    }

    @Override
    public long totalFailures() {
        return totalFailures.get();
    }

    @Override
    public long consecutiveFailures() {
        return consecutiveFailures.get();
    }

    @Override
    public void incrementConnections() {
        activeConnections.incrementAndGet();
        totalRequests.incrementAndGet();
    }

    @Override
    public void decrementConnections() {
        activeConnections.decrementAndGet();
    }

    @Override
    public void recordSuccess() {
        consecutiveFailures.set(0);
        if (status == EndpointHealthStatus.UNHEALTHY) {
            status = EndpointHealthStatus.HEALTHY;
            ejectionUntilNanos = 0L;
        }
    }

    @Override
    public void recordFailure(int statusCode, boolean transportError) {
        totalFailures.incrementAndGet();
        if (transportError || statusCode >= 500) {
            long fails = consecutiveFailures.incrementAndGet();
            if (passiveConfig.enabled() && fails >= passiveConfig.consecutive5xxThreshold()
                    && status != EndpointHealthStatus.DRAINING) {
                // A draining host stays out of service; passive ejection must not turn it into
                // an ejected one that a later success would return to HEALTHY
                markUnhealthy(passiveConfig.ejectionDurationNanos());
            }
        }
    }

    @Override
    public void markUnhealthy(long durationNanos) {
        this.status = EndpointHealthStatus.UNHEALTHY;
        this.ejectionUntilNanos = System.nanoTime() + durationNanos;
    }

    @Override
    public void markHealthy() {
        this.status = EndpointHealthStatus.HEALTHY;
        this.consecutiveFailures.set(0);
        this.ejectionUntilNanos = 0L;
    }

    @Override
    public void setStatus(EndpointHealthStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        if (status == EndpointHealthStatus.HEALTHY) {
            this.consecutiveFailures.set(0);
            this.ejectionUntilNanos = 0L;
        }
    }

    @Override
    public String toString() {
        return "UpstreamHost[" + endpoint.authority() + ", status=" + status + ", conns=" + activeConnections.get() + "]";
    }
}
