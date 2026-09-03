package io.opencode.loopper.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Creates bounded JDK HTTP transports for both OpenCode requests and health probes. */
final class OpenCodeHttpTransport {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final RestClient.Builder source;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    OpenCodeHttpTransport(RestClient.Builder source, Duration connectTimeout, Duration requestTimeout) {
        this.source = source;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
    }

    RestClient client(OpenCodeConnectionDetails connection) {
        return authenticated(bounded(source, connectTimeout, requestTimeout), connection);
    }

    /** Statistics rounds finish at the provider or by explicit user cancellation, never a read deadline. */
    RestClient commandClient(OpenCodeConnectionDetails connection) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(boundedTimeoutMillis(connectTimeout, DEFAULT_CONNECT_TIMEOUT));
        factory.setReadTimeout(0);
        return authenticated(source.clone().requestFactory(factory), connection);
    }

    private RestClient authenticated(RestClient.Builder builder, OpenCodeConnectionDetails connection) {
        RestClient.Builder spec = builder.baseUrl(connection.baseUrl().toString());
        if (connection.username() != null && !connection.username().isBlank()) {
            spec.defaultHeaders(headers -> headers.setBasicAuth(connection.username(),
                    connection.password() == null ? "" : connection.password()));
        }
        return spec.build();
    }

    static RestClient.Builder bounded(RestClient.Builder source, Duration connectTimeout, Duration requestTimeout) {
        // OpenCode 1.18.x is served by Bun. Its successful JSON POST responses can
        // remain incomplete from java.net.http.HttpClient's point of view even
        // after the server has created the session, causing a false timeout. The
        // HttpURLConnection-backed Spring adapter interoperates with the same
        // endpoint while retaining explicit connect and read bounds.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(boundedTimeoutMillis(connectTimeout, DEFAULT_CONNECT_TIMEOUT));
        requestFactory.setReadTimeout(boundedTimeoutMillis(requestTimeout, DEFAULT_REQUEST_TIMEOUT));
        return source.clone().requestFactory(requestFactory);
    }

    /** HttpURLConnection uses zero as "infinite" and accepts only an int millisecond bound. */
    static int boundedTimeoutMillis(Duration value, Duration fallback) {
        Duration selected = value == null || value.isZero() || value.isNegative() ? fallback : value;
        try {
            long millis = selected.toMillis();
            if (millis < 1) return 1;
            return (int) Math.min(millis, Integer.MAX_VALUE);
        } catch (ArithmeticException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    static URI sessionUri(UriBuilder uri, String path, OpenCodeClient.OpenCodeSession session) {
        return directoryUri(uri, path, session.worktree(), Map.of("id", session.id()));
    }

    static URI directoryUri(UriBuilder uri, String path, Path directory) {
        return directoryUri(uri, path, directory, Map.of());
    }

    static URI directoryUri(UriBuilder uri, String path, Path directory,
                            Map<String, ?> pathVariables) {
        Map<String, Object> variables = new LinkedHashMap<>(pathVariables);
        variables.put("directory", directory.toString());
        return uri.path(path).queryParam("directory", "{directory}").build(variables);
    }
}
