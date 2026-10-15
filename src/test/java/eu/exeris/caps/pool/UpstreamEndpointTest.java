package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.UpstreamEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamEndpointTest {

    @Test
    @DisplayName("Parse standard HTTP URI into endpoint")
    void testStandardHttpUri() {
        UpstreamEndpoint ep = UpstreamEndpoint.of("http://mock-service:9000");
        assertThat(ep.host()).isEqualTo("mock-service");
        assertThat(ep.port()).isEqualTo(9000);
        assertThat(ep.weight()).isEqualTo(1);
        assertThat(ep.authority()).isEqualTo("mock-service:9000");
        assertThat(ep.baseUri()).isEqualTo("http://mock-service:9000");
    }

    @Test
    @DisplayName("Parse HTTPS URI with default port")
    void testHttpsDefaultPort() {
        UpstreamEndpoint ep = UpstreamEndpoint.of("https://api.example.com");
        assertThat(ep.host()).isEqualTo("api.example.com");
        assertThat(ep.port()).isEqualTo(443);
        assertThat(ep.authority()).isEqualTo("api.example.com:443");
    }

    @Test
    @DisplayName("Endpoint with custom weight")
    void testCustomWeight() {
        UpstreamEndpoint ep = UpstreamEndpoint.of("http://node-1:8080/", 5);
        assertThat(ep.baseUri()).isEqualTo("http://node-1:8080");
        assertThat(ep.weight()).isEqualTo(5);
    }

    @Test
    @DisplayName("Reject invalid ports and weights")
    void testInvalidParameters() {
        assertThatThrownBy(() -> UpstreamEndpoint.of("id", "host", 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstreamEndpoint.of("id", "host", 8080, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
