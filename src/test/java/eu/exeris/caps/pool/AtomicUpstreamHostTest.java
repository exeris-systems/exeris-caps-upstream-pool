package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.EndpointHealthStatus;
import eu.exeris.caps.pool.api.PassiveHealthCheckConfig;
import eu.exeris.caps.pool.api.UpstreamEndpoint;
import eu.exeris.caps.pool.internal.AtomicUpstreamHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicUpstreamHostTest {

    @Test
    @DisplayName("Track active connections and total requests atomically")
    void testConnectionTracking() {
        UpstreamEndpoint ep = UpstreamEndpoint.of("http://localhost:9001");
        AtomicUpstreamHost host = new AtomicUpstreamHost(ep, PassiveHealthCheckConfig.DISABLED);

        assertThat(host.activeConnections()).isEqualTo(0);
        assertThat(host.totalRequests()).isEqualTo(0);

        host.incrementConnections();
        host.incrementConnections();
        assertThat(host.activeConnections()).isEqualTo(2);
        assertThat(host.totalRequests()).isEqualTo(2);

        host.decrementConnections();
        assertThat(host.activeConnections()).isEqualTo(1);
        assertThat(host.totalRequests()).isEqualTo(2);

        host.decrementConnections();
        assertThat(host.activeConnections()).isEqualTo(0);
    }

    @Test
    @DisplayName("Passive outlier detection ejects host after threshold consecutive 5xx errors")
    void testPassiveEjection() {
        UpstreamEndpoint ep = UpstreamEndpoint.of("http://localhost:9001");
        PassiveHealthCheckConfig config = PassiveHealthCheckConfig.of(3, Duration.ofMillis(50));
        AtomicUpstreamHost host = new AtomicUpstreamHost(ep, config);

        assertThat(host.isAvailable()).isTrue();
        assertThat(host.status()).isEqualTo(EndpointHealthStatus.HEALTHY);

        // 2 consecutive 500 errors -> still healthy
        host.recordFailure(500, false);
        host.recordFailure(502, false);
        assertThat(host.consecutiveFailures()).isEqualTo(2);
        assertThat(host.isAvailable()).isTrue();

        // 1 success resets consecutive failures
        host.recordSuccess();
        assertThat(host.consecutiveFailures()).isEqualTo(0);
        assertThat(host.isAvailable()).isTrue();

        // 3 consecutive 503 errors -> triggers ejection
        host.recordFailure(503, false);
        host.recordFailure(500, false);
        host.recordFailure(504, false);
        assertThat(host.consecutiveFailures()).isEqualTo(3);
        assertThat(host.status()).isEqualTo(EndpointHealthStatus.UNHEALTHY);
        assertThat(host.isAvailable()).isFalse();

        // Wait for quarantine duration (50ms) to expire
        try {
            Thread.sleep(70);
        } catch (InterruptedException ignored) {}

        // Window expired: probationary request is allowed
        assertThat(host.isAvailable()).isTrue();

        // If probe succeeds, status is reinstated to HEALTHY
        host.recordSuccess();
        assertThat(host.status()).isEqualTo(EndpointHealthStatus.HEALTHY);
        assertThat(host.consecutiveFailures()).isEqualTo(0);
    }
}
