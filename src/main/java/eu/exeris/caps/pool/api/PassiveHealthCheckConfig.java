package eu.exeris.caps.pool.api;

import java.time.Duration;

/**
 * Configuration for passive health checking (outlier detection / circuit ejection).
 *
 * <p>When an upstream host produces {@code consecutive5xxThreshold} consecutive failures
 * (5xx responses or transport I/O errors), it is automatically ejected from the active
 * healthy pool for {@code ejectionDuration}.
 *
 * @since 0.1.0
 */
public record PassiveHealthCheckConfig(
        boolean enabled,
        int consecutive5xxThreshold,
        long ejectionDurationNanos,
        int maxEjectionPercent
) {

    public static final PassiveHealthCheckConfig DISABLED = new PassiveHealthCheckConfig(false, 3, 5_000_000_000L, 100);
    public static final PassiveHealthCheckConfig DEFAULT = new PassiveHealthCheckConfig(true, 3, 5_000_000_000L, 100);

    public PassiveHealthCheckConfig {
        if (consecutive5xxThreshold <= 0) {
            throw new IllegalArgumentException("consecutive5xxThreshold must be > 0");
        }
        if (ejectionDurationNanos <= 0) {
            throw new IllegalArgumentException("ejectionDurationNanos must be > 0");
        }
        if (maxEjectionPercent <= 0 || maxEjectionPercent > 100) {
            throw new IllegalArgumentException("maxEjectionPercent must be between 1 and 100");
        }
    }

    public static PassiveHealthCheckConfig of(int threshold, Duration ejectionDuration) {
        return new PassiveHealthCheckConfig(true, threshold, ejectionDuration.toNanos(), 100);
    }

    public static PassiveHealthCheckConfig of(int threshold, Duration ejectionDuration, int maxEjectionPercent) {
        return new PassiveHealthCheckConfig(true, threshold, ejectionDuration.toNanos(), maxEjectionPercent);
    }
}
