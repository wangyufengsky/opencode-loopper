package io.opencode.loopper.runtime;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Creates bounded JDK HTTP transports for both OpenCode requests and health probes. */
final class OpenCodeHttpTransport {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private OpenCodeHttpTransport() { }

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
}
