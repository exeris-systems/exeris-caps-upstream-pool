package eu.exeris.caps.pool.api;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable value representation of an upstream network endpoint.
 *
 * @since 0.1.0
 */
public record UpstreamEndpoint(
        String id,
        String host,
        int port,
        int weight,
        String baseUri,
        String authority
) {

    public UpstreamEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(authority, "authority");
        if (!isPlainHost(host)) {
            throw new IllegalArgumentException("Invalid host: " + host);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("Weight must be positive: " + weight);
        }
    }

    /**
     * Creates an endpoint from a base URI string (e.g. {@code "http://10.0.0.1:9000"}).
     *
     * @param baseUri the HTTP base URI
     * @return the resolved UpstreamEndpoint
     */
    public static UpstreamEndpoint of(String baseUri) {
        return of(baseUri, 1);
    }

    /**
     * Creates an endpoint from a base URI string and weight.
     *
     * @param baseUri the HTTP base URI
     * @param weight  the load-balancing weight (>= 1)
     * @return the resolved UpstreamEndpoint
     */
    public static UpstreamEndpoint of(String baseUri, int weight) {
        Objects.requireNonNull(baseUri, "baseUri");
        String clean = baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
        URI parsed = URI.create(clean);
        String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Upstream scheme must be http or https: " + baseUri);
        }
        if (parsed.getHost() == null || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Upstream URI needs a host and no user info, query or fragment: " + baseUri);
        }
        String host = parsed.getHost();
        int port = parsed.getPort();
        if (port <= 0) {
            port = "https".equalsIgnoreCase(parsed.getScheme()) ? 443 : 80;
        }
        String authority = (host.indexOf(':') >= 0 && !host.startsWith("["))
                ? "[" + host + "]:" + port
                : host + ":" + port;
        String id = authority;
        return new UpstreamEndpoint(id, host, port, weight, clean, authority);
    }

    /**
     * Creates an endpoint with explicit parameters.
     *
     * @param id     unique identifier
     * @param host   hostname or IP
     * @param port   network port
     * @param weight load-balancing weight
     * @return the UpstreamEndpoint
     */
    public static UpstreamEndpoint of(String id, String host, int port, int weight) {
        String authority = (host.indexOf(':') >= 0 && !host.startsWith("["))
                ? "[" + host + "]:" + port
                : host + ":" + port;
        String baseUri = "http://" + authority;
        return new UpstreamEndpoint(id, host, port, weight, baseUri, authority);
    }

    /** A host is a name or address literal; anything that could reshape a URI built from it is refused. */
    private static boolean isPlainHost(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '/' || c == '@' || c == '?' || c == '#' || c == '\\') {
                return false;
            }
        }
        return true;
    }
}
