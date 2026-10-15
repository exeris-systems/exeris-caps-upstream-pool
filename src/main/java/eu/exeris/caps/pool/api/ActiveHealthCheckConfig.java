package eu.exeris.caps.pool.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for active periodic health checking (background probing).
 *
 * <p>Active probing decides on its own, whether or not passive checking is enabled: a host with
 * {@code unhealthyThreshold} consecutive failed probes is ejected (within the cluster's ejection budget,
 * see {@link PassiveHealthCheckConfig}) and returns after {@code healthyThreshold} consecutive successful
 * probes. A probe succeeds on a 2xx response; any other status, an I/O error or a timeout fails it.
 *
 * @since 0.1.0
 */
public record ActiveHealthCheckConfig(
        boolean enabled,
        long checkIntervalMillis,
        long timeoutMillis,
        String healthCheckPath,
        int healthyThreshold,
        int unhealthyThreshold
) {

    public static final ActiveHealthCheckConfig DISABLED = new ActiveHealthCheckConfig(
            false, 1000, 500, "/health", 2, 3
    );

    public ActiveHealthCheckConfig {
        Objects.requireNonNull(healthCheckPath, "healthCheckPath");
        if (!isOriginFormPath(healthCheckPath)) {
            throw new IllegalArgumentException(
                    "healthCheckPath must be an absolute path such as /health, without authority, query or fragment");
        }
        if (checkIntervalMillis <= 0) {
            throw new IllegalArgumentException("checkIntervalMillis must be > 0");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0");
        }
        if (healthyThreshold <= 0) {
            throw new IllegalArgumentException("healthyThreshold must be > 0");
        }
        if (unhealthyThreshold <= 0) {
            throw new IllegalArgumentException("unhealthyThreshold must be > 0");
        }
    }

    public static ActiveHealthCheckConfig enabled(Duration interval, Duration timeout, String path) {
        return new ActiveHealthCheckConfig(
                true, interval.toMillis(), timeout.toMillis(), path, 2, 3
        );
    }

    /** The path is appended to a host's base URI, so it must not be able to change that URI's authority. */
    private static boolean isOriginFormPath(String path) {
        if (path.isEmpty() || path.charAt(0) != '/' || path.startsWith("//")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '#' || c == '?' || c == '\\' || c == '@') {
                return false;
            }
        }
        return true;
    }
}
