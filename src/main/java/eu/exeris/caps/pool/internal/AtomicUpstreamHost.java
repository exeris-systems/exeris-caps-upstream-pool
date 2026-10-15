package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamEndpoint;
import eu.exeris.caps.pool.api.UpstreamHost;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lock-free implementation of {@link UpstreamHost}.
 *
 * <p>Maintains in-flight active connection count, total request/failure metrics,
 * and passive outlier detection ejection window using primitive atomics and volatile reads. No method
 * takes a lock, and none allocates, except {@link #toString()}, which formats a string and is not on the selection path.
 *
 * <p>An ejection is either timed (a quarantine window, after which the host is available again on
 * probation and is reinstated by its next success) or untimed (set by {@link #setStatus} or an active
 * probe, lifted only by {@link #markHealthy()}, a healthy {@link #setStatus} or a probe). A success
 * recorded while a timed window is still open does not lift it.
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
    private volatile EjectionBudget budget;
    private final LongSupplier nanoClock;

    public AtomicUpstreamHost(UpstreamEndpoint endpoint, PassiveHealthCheckConfig passiveConfig) {
        this(endpoint, passiveConfig, System::nanoTime);
    }

    /**
     * Creates a host that reads time from the given clock, so ejection windows can be driven without waiting.
     *
     * @param nanoClock a monotonic nanosecond clock
     */
    public AtomicUpstreamHost(UpstreamEndpoint endpoint, PassiveHealthCheckConfig passiveConfig, LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.passiveConfig = passiveConfig != null ? passiveConfig : PassiveHealthCheckConfig.DISABLED;
    }

    /** Subjects this host's passive ejections to the cluster's budget. */
    void attachBudget(EjectionBudget budget) {
        this.budget = budget;
    }

    /** An ejection without a window (probe or explicit status) is lifted by its owner, not re-timed by a failure. */
    private boolean isHeldWithoutWindow() {
        return status == EndpointHealthStatus.UNHEALTHY && ejectionUntilNanos == 0L;
    }

    private boolean windowElapsed(long until) {
        return until != 0 && nanoClock.getAsLong() - until >= 0;
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
            // Probationary trial once a timed window has elapsed: a request re-evaluates the host
            return windowElapsed(ejectionUntilNanos);
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
        if (status == EndpointHealthStatus.UNHEALTHY && windowElapsed(ejectionUntilNanos)) {
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
                    && status != EndpointHealthStatus.DRAINING && !isHeldWithoutWindow()) {
                // A draining host stays out of service; passive ejection must not turn it into
                // an ejected one that a later success would return to HEALTHY
                EjectionBudget cap = budget;
                if (cap == null) {
                    markUnhealthy(passiveConfig.ejectionDurationNanos());
                } else {
                    cap.tryEject(this, passiveConfig.ejectionDurationNanos());
                }
            }
        }
    }

    @Override
    public void markUnhealthy(long durationNanos) {
        this.status = EndpointHealthStatus.UNHEALTHY;
        long until = nanoClock.getAsLong() + durationNanos;
        this.ejectionUntilNanos = until == 0 ? 1 : until;
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
        }
        // An explicit status has no quarantine window: it holds until it is set again
        this.ejectionUntilNanos = 0L;
    }

    @Override
    public String toString() {
        return "UpstreamHost[" + endpoint.authority() + ", status=" + status + ", conns=" + activeConnections.get() + "]";
    }
}
