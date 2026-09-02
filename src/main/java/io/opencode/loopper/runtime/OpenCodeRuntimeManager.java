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
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns only the OpenCode process it starts. Managed mode always starts a
 * dedicated process; legacy auto mode may reuse a healthy loopback endpoint.
 */
public final class OpenCodeRuntimeManager implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);
    private static final Duration STARTUP_PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Object monitor = new Object();
    private final LoopperProperties properties;
    private final ProcessStarter processStarter;
    private final Clock clock;
    private final InternalMcpCredentialProvider internalMcpCredentials;
    private final InternalMcpRuntimeAccess internalMcpAccess;
    private final boolean requireInternalMcpReadiness;
    private final SecureRandom random = new SecureRandom();
    private volatile Connection connection;
    private volatile ManagedProcess owned;
    private volatile String lastStartFailure;
    private volatile URI lastAttemptedEndpoint;

    public OpenCodeRuntimeManager(LoopperProperties properties) {
        this(properties, OpenCodeRuntimeManager::startProcess, Clock.systemUTC());
    }

    OpenCodeRuntimeManager(LoopperProperties properties, ProcessStarter processStarter, Clock clock) {
        this(properties, processStarter, clock,
                new InternalMcpCredentialProvider(() -> 8080), new InternalMcpRuntimeAccess(), false);
    }

    public OpenCodeRuntimeManager(LoopperProperties properties,
                                  InternalMcpCredentialProvider internalMcpCredentials,
                                  InternalMcpRuntimeAccess internalMcpAccess) {
        this(properties, OpenCodeRuntimeManager::startProcess, Clock.systemUTC(),
                internalMcpCredentials, internalMcpAccess, true);
    }

    OpenCodeRuntimeManager(LoopperProperties properties, ProcessStarter processStarter, Clock clock,
                           InternalMcpCredentialProvider internalMcpCredentials,
                           InternalMcpRuntimeAccess internalMcpAccess) {
        this(properties, processStarter, clock, internalMcpCredentials, internalMcpAccess, true);
    }

    private OpenCodeRuntimeManager(LoopperProperties properties, ProcessStarter processStarter, Clock clock,
                                   InternalMcpCredentialProvider internalMcpCredentials,
                                   InternalMcpRuntimeAccess internalMcpAccess,
                                   boolean requireInternalMcpReadiness) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.internalMcpCredentials = Objects.requireNonNull(internalMcpCredentials, "internalMcpCredentials");
        this.internalMcpAccess = Objects.requireNonNull(internalMcpAccess, "internalMcpAccess");
        this.requireInternalMcpReadiness = requireInternalMcpReadiness;
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
            if (mode() == Mode.MANAGED) ensureManagedStarted(false);
            else if (mode() == Mode.AUTO) ensureAutoStarted(false);
            return connection == null ? configuredConnection() : connection;
        }
    }

    /**
     * Returns only the already-known non-secret runtime identity. This method deliberately
     * does not start, probe, refresh, or otherwise contact OpenCode.
     */
    public RuntimeIdentity currentIdentityNoIo() {
        synchronized (monitor) {
            Mode currentMode = mode();
            if (currentMode == Mode.HTTP) {
                Connection configured = configuredConnection();
                if (!isSafeLoopback(configured.endpoint())) {
                    throw new IllegalStateException("loopper.opencode.base-url must use a loopback host in http mode");
                }
                return identity(configured);
            }
            if (currentMode == Mode.FAKE) return identity(configuredConnection());
            Connection known = connection;
            if (known == null) {
                throw new IllegalStateException(
                        "OpenCode runtime identity is not available without starting or probing the runtime");
            }
            if (known.managed() && (owned == null || !owned.process().isAlive())) {
                throw new IllegalStateException("The known managed OpenCode runtime is no longer active");
            }
            if (known.managed() && (known.endpoint() == null || blank(known.generation())
                    || blank(known.internalMcpServer()))) {
                throw new IllegalStateException("The known managed OpenCode runtime identity is incomplete");
            }
            return identity(known);
        }
    }

    /**
     * Starts the default isolated generation after Loopper's HTTP port is
     * bound, so the injected private MCP endpoint is reachable during the
     * OpenCode handshake. Compatibility auto/http/fake modes stay lazy.
     */
    public void startManagedOnApplicationReady() {
        synchronized (monitor) {
            if (mode() == Mode.MANAGED) ensureManagedStarted(false);
        }
    }

    public RuntimeSnapshot status() {
        synchronized (monitor) {
            if (mode() == Mode.FAKE) {
                return new RuntimeSnapshot("AVAILABLE", "fake", false, null, configuredConnection().endpoint().toString(),
                        model(), now(), null, null, null, InternalMcpReadiness.inactive());
            }
            if (mode() == Mode.HTTP && !isSafeLoopback(configuredConnection().endpoint())) {
                return new RuntimeSnapshot("OFFLINE", null, false, null, "loopback-host-required", model(), now(), null,
                        null, null, InternalMcpReadiness.inactive());
            }
            if (mode() == Mode.MANAGED) ensureManagedStarted(false);
            else if (mode() == Mode.AUTO) ensureAutoStarted(false);
            if ((mode() == Mode.MANAGED || mode() == Mode.AUTO) && lastStartFailure != null && owned == null) {
                return new RuntimeSnapshot("OFFLINE", null, false, null,
                        lastAttemptedEndpoint == null ? null : lastAttemptedEndpoint.toString(), model(), now(), lastStartFailure,
                        null, null, internalMcpAccess.readiness());
            }
            Connection target = connection == null ? configuredConnection() : connection;
            Health health = probe(target);
            ManagedProcess local = owned;
            boolean processAlive = local == null || local.process().isAlive();
            boolean managed = local != null && processAlive;
            Long pid = managed ? local.process().pid() : null;
            String status = health.healthy() && processAlive ? "AVAILABLE" : "OFFLINE";
            return new RuntimeSnapshot(status, health.version(), managed, pid, target.endpoint().toString(), model(), now(), null,
                    target.generation(), target.internalMcpServer(), internalMcpAccess.readiness());
        }
    }

    /** Restart is deliberately limited to a currently-owned process. */
    public RuntimeSnapshot restartOwned() {
        synchronized (monitor) {
            if (!ownsRuntimeMode()) throw new IllegalStateException("Only managed or auto mode can restart a managed OpenCode runtime");
            if (owned == null) throw new IllegalStateException("The current OpenCode runtime is external and cannot be restarted here");
            stopOwned();
            connection = null;
            lastStartFailure = null;
            if (mode() == Mode.MANAGED) ensureManagedStarted(true);
            else ensureAutoStarted(true);
            return status();
        }
    }

    /** Explicit local-UI recovery after a managed auto launch failed. */
    public RuntimeSnapshot startAndCheck() {
        synchronized (monitor) {
            if (!ownsRuntimeMode()) {
                throw new IllegalStateException("Only managed or auto mode can start a managed OpenCode runtime");
            }
            if (owned != null && owned.process().isAlive()) return status();
            connection = null;
            lastStartFailure = null;
            lastAttemptedEndpoint = null;
            if (mode() == Mode.MANAGED) ensureManagedStarted(true);
            else ensureAutoStarted(true);
            return status();
        }
    }

    public boolean manuallyStartable() { return ownsRuntimeMode(); }

    public boolean restartable() {
        ManagedProcess local = owned;
        return ownsRuntimeMode() && local != null && local.process().isAlive();
    }

    /** Exposed for a focused test and for support diagnostics without credentials. */
    String lastStartFailure() { return lastStartFailure; }

    private void ensureAutoStarted(boolean force) {
        if (owned != null && owned.process().isAlive()) {
            connection = owned.connection();
            return;
        }
        if (owned != null) {
            internalMcpAccess.clear(owned.connection().generation());
            owned = null;
        }
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

    private void ensureManagedStarted(boolean force) {
        if (owned != null && owned.process().isAlive()) {
            connection = owned.connection();
            return;
        }
        if (owned != null) {
            internalMcpAccess.clear(owned.connection().generation());
            owned = null;
        }
        if (!force && lastStartFailure != null) return;
        try {
            lastAttemptedEndpoint = null;
            startOwned();
            lastStartFailure = null;
        } catch (RuntimeException e) {
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
        InternalMcpCredentialProvider.Credentials internal = internalMcpCredentials.issue();
        internalMcpAccess.activate(internal);
        Connection candidate = new Connection(endpoint, username, password, true,
                internal.generation(), internal.serverName());
        List<String> command = List.of(executable.toString(), "serve", "--hostname", "127.0.0.1", "--port", Integer.toString(port));
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OPENCODE_SERVER_USERNAME", username);
        environment.put("OPENCODE_SERVER_PASSWORD", password);
        environment.put("OPENCODE_ENABLE_QUESTION_TOOL", "true");
        environment.put("OPENCODE_CONFIG_CONTENT", managedConfig(internal));
        Process process;
        try {
            process = processStarter.start(command, environment);
        } catch (IOException e) {
            internalMcpAccess.clear(internal.generation());
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
            if (owned != started) {
                terminate(process);
                internalMcpAccess.clear(internal.generation());
            }
        }
    }

    private String managedConfig(InternalMcpCredentialProvider.Credentials internal) {
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
        config.put("agent", Map.of(
                OpenCodeClient.STRUCTURED_AGENT, Map.of(
                        "description", "Bounded read-only Loopper role for machine-response workflows",
                        "mode", "primary",
                        "steps", OpenCodeClient.STRUCTURED_AGENT_STEPS,
                        "temperature", OpenCodeClient.STRUCTURED_AGENT_TEMPERATURE,
                        "prompt", OpenCodeClient.STRUCTURED_AGENT_PROMPT),
                OpenCodeClient.ROUTER_AGENT, Map.of(
                        "description", "Single-shot Loopper task classifier without tools or design work",
                        "mode", "primary",
                        "steps", OpenCodeClient.ROUTER_AGENT_STEPS,
                        "temperature", OpenCodeClient.ROUTER_AGENT_TEMPERATURE,
                        "prompt", OpenCodeClient.ROUTER_AGENT_PROMPT)));
        config.put("mcp", Map.of(internal.serverName(), Map.of(
                "type", "remote",
                "url", internal.endpoint().toString(),
                "enabled", true,
                "oauth", false,
                "headers", Map.of("Authorization", "Bearer " + internal.bearerToken()),
                "timeout", 5_000)));
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
        return OpenCodeAccountingAgent.install(mergeManagedConfig(System.getenv("OPENCODE_CONFIG_CONTENT"), config), properties.getDataDir());
    }

    /**
     * OpenCode merges {@code OPENCODE_CONFIG_CONTENT} with its file-backed
     * configuration. Preserve an operator's inherited content too, while the
     * Loopper-owned overlay wins only for its private agents, safety rules and
     * randomly named internal MCP entry.
     */
    static String mergeManagedConfig(String inherited, Map<String, Object> overlay) {
        try {
            ObjectNode merged;
            if (blank(inherited)) {
                merged = JSON.createObjectNode();
            } else {
                var parsed = JSON.readTree(inherited);
                if (parsed == null || !parsed.isObject()) {
                    throw new IllegalStateException("Inherited OpenCode configuration must be a JSON object");
                }
                merged = (ObjectNode) parsed.deepCopy();
            }
            var overlayNode = JSON.valueToTree(overlay);
            if (!overlayNode.isObject()) throw new IllegalStateException("Managed OpenCode overlay must be an object");
            deepMerge(merged, (ObjectNode) overlayNode);
            return JSON.writeValueAsString(merged);
        } catch (JacksonException failure) {
            // Never include inherited JSON because it may contain credentials.
            throw new IllegalStateException("Unable to merge inherited OpenCode configuration", failure);
        }
    }

    private static void deepMerge(ObjectNode target, ObjectNode overlay) {
        overlay.propertyStream().forEach(entry -> {
            var current = target.get(entry.getKey());
            var incoming = entry.getValue();
            if (current != null && current.isObject() && incoming != null && incoming.isObject()) {
                deepMerge((ObjectNode) current, (ObjectNode) incoming);
            } else {
                target.set(entry.getKey(), incoming == null ? JSON.nullNode() : incoming.deepCopy());
            }
        });
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
            String version = body == null ? null : body.path("version").asText(null);
            if (!healthy || !requireInternalMcpReadiness || !target.managed() || blank(target.internalMcpServer())) {
                return new Health(healthy, version);
            }
            var mcp = builder.build().get().uri("/mcp").accept(MediaType.APPLICATION_JSON).retrieve()
                    .body(tools.jackson.databind.JsonNode.class);
            boolean connected = mcp != null
                    && "connected".equalsIgnoreCase(mcp.path(target.internalMcpServer()).path("status").asText());
            if (connected) internalMcpAccess.connected(target.generation());
            else internalMcpAccess.unavailable(target.generation(), "OpenCode did not connect the exact internal MCP server");
            return new Health(connected, version);
        } catch (RuntimeException ignored) {
            if (requireInternalMcpReadiness && target.managed() && !blank(target.generation())) {
                internalMcpAccess.unavailable(target.generation(), "OpenCode internal MCP readiness check failed");
            }
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
        return new Connection(openCode.getBaseUrl(), openCode.getUsername(), openCode.getPassword(), false, null, null);
    }

    private static Connection unavailableLoopbackConnection() {
        // A deliberately-unused local endpoint preserves fail-closed behavior if
        // auto startup fails after a remote URL was accidentally configured.
        return new Connection(URI.create("http://127.0.0.1:9"), "", "", false, null, null);
    }

    private static boolean isSafeLoopback(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("127.0.0.1") || value.equals("::1") || value.equals("[::1]");
    }

    private static RuntimeIdentity identity(Connection connection) {
        return new RuntimeIdentity(connection.endpoint(), connection.managed(),
                connection.generation(), connection.internalMcpServer());
    }

    private void stopOwned() {
        ManagedProcess local = owned;
        owned = null;
        if (local != null) {
            terminate(local.process());
            internalMcpAccess.clear(local.connection().generation());
        }
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
        if (value != null) {
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "fake" -> Mode.FAKE;
                case "http" -> Mode.HTTP;
                case "managed" -> Mode.MANAGED;
                case "auto" -> Mode.AUTO;
                default -> throw new IllegalStateException("Unsupported OpenCode mode: " + value);
            };
        }
        throw new IllegalStateException("Unsupported OpenCode mode: null");
    }

    private boolean ownsRuntimeMode() { return mode() == Mode.MANAGED || mode() == Mode.AUTO; }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safeFailure(RuntimeException exception) { return exception.getMessage() == null ? "OpenCode runtime startup failed" : exception.getMessage(); }
    private Instant now() { return clock.instant(); }
    private String model() { return properties.getOpenCode().getModel(); }

    @Override @PreDestroy public void close() {
        synchronized (monitor) { stopOwned(); }
    }

    enum Mode { MANAGED, AUTO, HTTP, FAKE }
    @FunctionalInterface interface ProcessStarter { Process start(List<String> command, Map<String, String> environment) throws IOException; }
    record Health(boolean healthy, String version) { }
    record ManagedProcess(Process process, Connection connection) { }
    public record Connection(URI endpoint, String username, String password, boolean managed,
                             String generation, String internalMcpServer) {
        public Connection(URI endpoint, String username, String password, boolean managed) {
            this(endpoint, username, password, managed, null, null);
        }
        Connection asExternal() { return new Connection(endpoint, username, password, false, null, null); }
    }
    /** Non-secret, already-observed identity used by local-only Session planning. */
    public record RuntimeIdentity(URI endpoint, boolean managed,
                                  String generation, String internalMcpServer) { }
    public record RuntimeSnapshot(String status, String version, boolean managed, Long pid, String endpoint,
                                  String model, Instant checkedAt, String startupFailure, String generation,
                                  String internalMcpServer, InternalMcpReadiness internalMcp) {
        public RuntimeSnapshot(String status, String version, boolean managed, Long pid, String endpoint,
                               String model, Instant checkedAt, String startupFailure) {
            this(status, version, managed, pid, endpoint, model, checkedAt, startupFailure,
                    null, null, InternalMcpReadiness.inactive());
        }
    }
}
