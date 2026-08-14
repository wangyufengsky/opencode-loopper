package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns only the OpenCode process it starts.  In auto mode a healthy loopback
 * endpoint configured by the operator is reused first; it is never killed by
 * restart or application shutdown.
 */
public final class OpenCodeRuntimeManager implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);
    private static final Duration STARTUP_PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Object monitor = new Object();
    private final LoopperProperties properties;
    private final ProcessStarter processStarter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile Connection connection;
    private volatile ManagedProcess owned;
    private volatile String lastStartFailure;
    private volatile URI lastAttemptedEndpoint;

    public OpenCodeRuntimeManager(LoopperProperties properties) {
        this(properties, OpenCodeRuntimeManager::startProcess, Clock.systemUTC());
    }

    OpenCodeRuntimeManager(LoopperProperties properties, ProcessStarter processStarter, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the connection for the HTTP adapter. A failed local launch leaves
     * the app up but fails closed on an unused loopback endpoint. A failed
     * launch is retried only through the explicit local-UI start action.
     */
    public Connection connectionForClient() {
        synchronized (monitor) {
            if (mode() == Mode.FAKE) return configuredConnection();
            if (mode() == Mode.HTTP && !isSafeLoopback(configuredConnection().endpoint())) {
                throw new IllegalStateException("loopper.opencode.base-url must use a loopback host in http mode");
            }
            if (mode() == Mode.AUTO) ensureAutoStarted(false);
            return connection == null ? configuredConnection() : connection;
        }
    }

    public RuntimeSnapshot status() {
        synchronized (monitor) {
            if (mode() == Mode.FAKE) {
                return new RuntimeSnapshot("AVAILABLE", "fake", false, null, configuredConnection().endpoint().toString(), model(), now(), null);
            }
            if (mode() == Mode.HTTP && !isSafeLoopback(configuredConnection().endpoint())) {
                return new RuntimeSnapshot("OFFLINE", null, false, null, "loopback-host-required", model(), now(), null);
            }
            if (mode() == Mode.AUTO) ensureAutoStarted(false);
            if (mode() == Mode.AUTO && lastStartFailure != null && owned == null) {
                return new RuntimeSnapshot("OFFLINE", null, false, null,
                        lastAttemptedEndpoint == null ? null : lastAttemptedEndpoint.toString(), model(), now(), lastStartFailure);
            }
            Connection target = connection == null ? configuredConnection() : connection;
            Health health = probe(target);
            ManagedProcess local = owned;
            boolean processAlive = local == null || local.process().isAlive();
            boolean managed = local != null && processAlive;
            Long pid = managed ? local.process().pid() : null;
            String status = health.healthy() && processAlive ? "AVAILABLE" : "OFFLINE";
            return new RuntimeSnapshot(status, health.version(), managed, pid, target.endpoint().toString(), model(), now(), null);
        }
    }

    /** Restart is deliberately limited to a currently-owned auto process. */
    public RuntimeSnapshot restartOwned() {
        synchronized (monitor) {
            if (mode() != Mode.AUTO) throw new IllegalStateException("Only auto mode can restart a managed OpenCode runtime");
            if (owned == null) throw new IllegalStateException("The current OpenCode runtime is external and cannot be restarted here");
            stopOwned();
            connection = null;
            lastStartFailure = null;
            ensureAutoStarted(true);
            return status();
        }
    }

    /** Explicit local-UI recovery after a managed auto launch failed. */
    public RuntimeSnapshot startAndCheck() {
        synchronized (monitor) {
            if (mode() != Mode.AUTO) {
                throw new IllegalStateException("Only auto mode can start a managed OpenCode runtime");
            }
            if (owned != null && owned.process().isAlive()) return status();
            connection = null;
            lastStartFailure = null;
            lastAttemptedEndpoint = null;
            ensureAutoStarted(true);
            return status();
        }
    }

    public boolean manuallyStartable() { return mode() == Mode.AUTO; }

    public boolean restartable() {
        ManagedProcess local = owned;
        return mode() == Mode.AUTO && local != null && local.process().isAlive();
    }

    /** Exposed for a focused test and for support diagnostics without credentials. */
    String lastStartFailure() { return lastStartFailure; }

    private void ensureAutoStarted(boolean force) {
        if (owned != null && owned.process().isAlive()) {
            connection = owned.connection();
            return;
        }
        if (owned != null) owned = null;
        if (!force && lastStartFailure != null) return;
        if (!force && connection != null && !connection.managed() && probe(connection).healthy()) return;

        Connection configured = configuredConnection();
        if (isSafeLoopback(configured.endpoint()) && probe(configured).healthy()) {
            connection = configured.asExternal();
            lastStartFailure = null;
            lastAttemptedEndpoint = null;
            return;
        }
        try {
            lastAttemptedEndpoint = null;
            startOwned();
            lastStartFailure = null;
        } catch (RuntimeException e) {
            // Do not include command output or generated credentials in status/API data.
            lastStartFailure = safeFailure(e);
            connection = unavailableLoopbackConnection();
        }
    }

    private void startOwned() {
        Path executable = findExecutable();
        int port = reserveLoopbackPort();
        URI endpoint = URI.create("http://127.0.0.1:" + port);
        lastAttemptedEndpoint = endpoint;
        String username = "loopper";
        String password = randomSecret();
        Connection candidate = new Connection(endpoint, username, password, true);
        List<String> command = List.of(executable.toString(), "serve", "--hostname", "127.0.0.1", "--port", Integer.toString(port));
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OPENCODE_SERVER_USERNAME", username);
        environment.put("OPENCODE_SERVER_PASSWORD", password);
        environment.put("OPENCODE_CONFIG_CONTENT", managedConfig());
        Process process;
        try {
            process = processStarter.start(command, environment);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start local OpenCode executable", e);
        }
        ManagedProcess started = new ManagedProcess(process, candidate);
        try {
            Instant deadline = now().plus(properties.getOpenCode().getStartupTimeout());
            while (now().isBefore(deadline)) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("Managed OpenCode exited with code " + process.exitValue() + " before it became healthy");
                }
                if (probe(candidate, startupProbeTimeout(deadline)).healthy()) {
                    owned = started;
                    connection = candidate;
                    return;
                }
                sleepBriefly();
            }
            throw new IllegalStateException("Managed OpenCode did not become healthy before startup-timeout");
        } finally {
            if (owned != started) terminate(process);
        }
    }

    private String managedConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("permission", Map.of(
                "external_directory", "deny",
                "bash", Map.of(
                        "git commit", "deny",
                        "git commit *", "deny",
                        "git push", "deny",
                        "git push *", "deny",
                        "git reset --hard*", "deny",
                        "rm -rf*", "deny")));
        config.put("agent", Map.of(OpenCodeClient.STRUCTURED_AGENT, Map.of(
                "description", "Bounded read-only Loopper role for machine-response workflows",
                "mode", "primary",
                "steps", OpenCodeClient.STRUCTURED_AGENT_STEPS,
                "temperature", OpenCodeClient.STRUCTURED_AGENT_TEMPERATURE,
                "prompt", OpenCodeClient.STRUCTURED_AGENT_PROMPT)));
        String configured = properties.getOpenCode().getModel();
        if (!blank(configured)) {
            int separator = configured.indexOf('/');
            if (separator > 0 && separator < configured.length() - 1) {
                String provider = configured.substring(0, separator).trim();
                String model = configured.substring(separator + 1).trim();
                if ("deepseek".equalsIgnoreCase(provider) && !model.isEmpty()) {
                    config.put("provider", Map.of(provider, Map.of("models", Map.of(model,
                            Map.of("variants", Map.of(OpenCodeClient.STRUCTURED_NO_THINKING_VARIANT,
                                    Map.of("thinking", Map.of("type", "disabled"))))))));
                }
            }
        }
        try {
            return JSON.writeValueAsString(config);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Unable to build managed OpenCode configuration", failure);
        }
    }

    private Path findExecutable() {
        List<String> requested = new ArrayList<>();
        if (!blank(properties.getOpenCode().getExecutable())) requested.add(properties.getOpenCode().getExecutable().trim());
        String environmentOverride = System.getenv("OPENCODE_EXECUTABLE");
        if (!blank(environmentOverride)) requested.add(environmentOverride.trim());
        for (String item : requested) {
            Path match = resolveExplicitExecutable(item);
            if (match != null) return match;
        }
        for (String segment : System.getenv().getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (segment.isBlank()) continue;
            for (String name : executableNames()) {
                Path candidate = Path.of(segment, name);
                if (isExecutable(candidate)) return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("OpenCode executable was not found in OPENCODE_EXECUTABLE or PATH");
    }

    private Path resolveExplicitExecutable(String value) {
        Path candidate = Path.of(value);
        if (candidate.getNameCount() > 1 || value.contains("/") || value.contains("\\")) {
            return isExecutable(candidate) ? candidate.toAbsolutePath().normalize() : null;
        }
        for (String segment : System.getenv().getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path inPath = Path.of(segment, value);
            if (isExecutable(inPath)) return inPath.toAbsolutePath().normalize();
        }
        return isExecutable(candidate) ? candidate.toAbsolutePath().normalize() : null;
    }

    private static boolean isExecutable(Path candidate) {
        return Files.isRegularFile(candidate) && (Files.isExecutable(candidate) || isWindows());
    }

    private static List<String> executableNames() {
        return isWindows() ? List.of("opencode.exe", "opencode.cmd", "opencode.bat", "opencode") : List.of("opencode");
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    private static int reserveLoopbackPort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to reserve a loopback port for OpenCode", e);
        }
    }

    private Health probe(Connection target) {
        return probe(target, properties.getOpenCode().getConnectTimeout(), properties.getOpenCode().getRequestTimeout());
    }

    private Health probe(Connection target, Duration timeout) {
        return probe(target, timeout, timeout);
    }

    private Health probe(Connection target, Duration connectTimeout, Duration requestTimeout) {
        try {
            RestClient.Builder builder = OpenCodeHttpTransport.bounded(RestClient.builder(), connectTimeout, requestTimeout)
                    .baseUrl(target.endpoint().toString());
            if (!blank(target.username())) builder.defaultHeaders(headers -> headers.setBasicAuth(target.username(), target.password()));
            var body = builder.build().get().uri("/global/health").accept(MediaType.APPLICATION_JSON).retrieve().body(tools.jackson.databind.JsonNode.class);
            boolean healthy = body != null && body.path("healthy").asBoolean(false);
            return new Health(healthy, body == null ? null : body.path("version").asText(null));
        } catch (RuntimeException ignored) {
            return new Health(false, null);
        }
    }

    private Duration startupProbeTimeout(Instant deadline) {
        Duration remaining = Duration.between(now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) return Duration.ofMillis(1);
        return remaining.compareTo(STARTUP_PROBE_TIMEOUT) < 0 ? remaining : STARTUP_PROBE_TIMEOUT;
    }

    private Connection configuredConnection() {
        var openCode = properties.getOpenCode();
        return new Connection(openCode.getBaseUrl(), openCode.getUsername(), openCode.getPassword(), false);
    }

    private static Connection unavailableLoopbackConnection() {
        // A deliberately-unused local endpoint preserves fail-closed behavior if
        // auto startup fails after a remote URL was accidentally configured.
        return new Connection(URI.create("http://127.0.0.1:9"), "", "", false);
    }

    private static boolean isSafeLoopback(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("127.0.0.1") || value.equals("::1") || value.equals("[::1]");
    }

    private void stopOwned() {
        ManagedProcess local = owned;
        owned = null;
        if (local != null) terminate(local.process());
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static Process startProcess(List<String> command, Map<String, String> environment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(environment);
        return builder.start();
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void sleepBriefly() {
        try { Thread.sleep(POLL_INTERVAL); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while starting OpenCode", interrupted); }
    }

    private Mode mode() {
        String value = properties.getOpenCode().getMode();
        if ("fake".equalsIgnoreCase(value)) return Mode.FAKE;
        if ("http".equalsIgnoreCase(value)) return Mode.HTTP;
        return Mode.AUTO;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safeFailure(RuntimeException exception) { return exception.getMessage() == null ? "OpenCode runtime startup failed" : exception.getMessage(); }
    private Instant now() { return clock.instant(); }
    private String model() { return properties.getOpenCode().getModel(); }

    @Override @PreDestroy public void close() {
        synchronized (monitor) { stopOwned(); }
    }

    enum Mode { AUTO, HTTP, FAKE }
    @FunctionalInterface interface ProcessStarter { Process start(List<String> command, Map<String, String> environment) throws IOException; }
    record Health(boolean healthy, String version) { }
    record ManagedProcess(Process process, Connection connection) { }
    public record Connection(URI endpoint, String username, String password, boolean managed) {
        Connection asExternal() { return new Connection(endpoint, username, password, false); }
    }
    public record RuntimeSnapshot(String status, String version, boolean managed, Long pid, String endpoint,
                                  String model, Instant checkedAt, String startupFailure) { }
}
