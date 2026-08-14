package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.domain.SessionFailure;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Cached read-only discovery of native agents and observed structured-output support. */
@Service
public class OpenCodeCapabilityService {
    private static final Duration DISCOVERY_TTL = Duration.ofSeconds(30);
    private final OpenCodeClient openCode;
    private final OpenCodeCapabilityRegistry registry;
    private final ConcurrentHashMap<Key, AgentDiscovery> agents = new ConcurrentHashMap<>();

    public OpenCodeCapabilityService(OpenCodeClient openCode, OpenCodeCapabilityRegistry registry) {
        this.openCode = openCode;
        this.registry = registry;
    }

    public RuntimeCapabilities capabilities(OpenCodeRuntimeManager.RuntimeSnapshot runtime) {
        Instant checkedAt = Instant.now();
        OpenCodeClient.OpenCodeModel model = model(runtime.model());
        URI endpoint = endpoint(runtime.endpoint());
        registry.observeRuntime(endpoint, runtime.version());
        if (!"AVAILABLE".equalsIgnoreCase(runtime.status())) {
            return new RuntimeCapabilities(OpenCodeClient.CapabilityState.UNKNOWN.name(), List.of(), false,
                    OpenCodeClient.CapabilityState.UNKNOWN.name(), OpenCodeClient.CapabilityState.UNKNOWN.name(),
                    ModelResponseMode.JSON_SCHEMA.name(), "TRUSTED_ALLOWED", checkedAt.toString(),
                    "OpenCode runtime is offline; capabilities have not been probed");
        }
        Key key = new Key(runtime.endpoint(), runtime.version(), blank(model.providerId()), blank(model.modelId()));
        AgentDiscovery discovery = agents.compute(key, (ignored, old) -> old != null
                && Duration.between(old.checkedAt(), checkedAt).compareTo(DISCOVERY_TTL) < 0
                ? old : discoverAgents(checkedAt));
        OpenCodeClient.StructuredOutputCapability structured = openCode.structuredOutputCapability(model);
        boolean nativePlan = discovery.agents().stream().anyMatch(agent -> "plan".equalsIgnoreCase(agent.name()));
        ModelResponseMode defaultMode = structured.transport() == OpenCodeClient.CapabilityState.UNAVAILABLE
                || structured.selectedModel() == OpenCodeClient.CapabilityState.UNAVAILABLE
                ? ModelResponseMode.TEXT_MARKER : ModelResponseMode.JSON_SCHEMA;
        String detail = first(discovery.detail(), structured.detail());
        return new RuntimeCapabilities(discovery.state().name(), discovery.agents(), nativePlan,
                structured.transport().name(), structured.selectedModel().name(), defaultMode.name(),
                "TRUSTED_ALLOWED", checkedAt.toString(), detail);
    }

    private AgentDiscovery discoverAgents(Instant checkedAt) {
        try {
            return new AgentDiscovery(OpenCodeClient.CapabilityState.AVAILABLE, openCode.agents(), null, checkedAt);
        } catch (SessionFailure failure) {
            return new AgentDiscovery(OpenCodeClient.CapabilityState.UNKNOWN, List.of(), bounded(failure.getMessage()), checkedAt);
        } catch (RuntimeException failure) {
            return new AgentDiscovery(OpenCodeClient.CapabilityState.UNKNOWN, List.of(), bounded(failure.getMessage()), checkedAt);
        }
    }

    private OpenCodeClient.OpenCodeModel model(String configured) {
        if (configured == null || configured.isBlank()) return new OpenCodeClient.OpenCodeModel(null, null, null);
        String value = configured.trim();
        int separator = value.indexOf('/');
        return separator <= 0 || separator == value.length() - 1
                ? new OpenCodeClient.OpenCodeModel(null, value, null)
                : new OpenCodeClient.OpenCodeModel(value.substring(0, separator), value.substring(separator + 1), null);
    }

    private URI endpoint(String value) {
        try { return value == null || value.isBlank() ? null : URI.create(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String first(String left, String right) {
        if (left != null && !left.isBlank()) return left;
        return right == null || right.isBlank() ? null : right;
    }
    private static String blank(String value) { return value == null ? "" : value.trim(); }
    private static String bounded(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 1_000 ? normalized : normalized.substring(0, 1_000);
    }

    public record RuntimeCapabilities(String agentDiscovery, List<OpenCodeClient.AgentInfo> agents,
                                      boolean nativePlanAgent, String structuredOutputTransport,
                                      String selectedModelStructuredOutput, String defaultResponseMode,
                                      String extensionPolicy, String checkedAt, String detail) {
        public RuntimeCapabilities { agents = agents == null ? List.of() : List.copyOf(agents); }
    }
    private record Key(String endpoint, String version, String provider, String model) { }
    private record AgentDiscovery(OpenCodeClient.CapabilityState state, List<OpenCodeClient.AgentInfo> agents,
                                  String detail, Instant checkedAt) { }
}
