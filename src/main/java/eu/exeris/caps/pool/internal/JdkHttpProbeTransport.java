package eu.exeris.caps.pool.internal;

import eu.exeris.caps.pool.api.UpstreamEndpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@link ProbeTransport} over the JDK HTTP client. Probing is off the request path, so it may allocate. */
final class JdkHttpProbeTransport implements ProbeTransport {

    private final HttpClient client;

    JdkHttpProbeTransport(long connectTimeoutMillis) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public int probe(UpstreamEndpoint endpoint, String path, long timeoutMillis) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUri() + path))
                .timeout(Duration.ofMillis(timeoutMillis))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Override
    public void close() {
        client.close();
    }
}
