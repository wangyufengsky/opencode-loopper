package io.opencode.loopper.runtime;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory observations keyed by runtime endpoint/version and selected provider/model. */
public final class OpenCodeCapabilityRegistry {
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final Map<Key, Observation> observations = new ConcurrentHashMap<>();

    public void observeRuntime(URI endpoint, String version) {
        if (endpoint == null) return;
        String endpointKey = endpoint.toString();
        String normalized = blank(version);
        String previous = versions.put(endpointKey, normalized);
        if (previous == null && !normalized.isEmpty()) {
            observations.forEach((key, observation) -> {
                if (key.endpoint().equals(endpointKey) && key.version().isEmpty()
                        && observations.remove(key, observation)) {
                    observations.putIfAbsent(new Key(key.endpoint(), normalized, key.provider(), key.model()), observation);
                }
            });
        } else if (previous != null && !previous.equals(normalized)) {
            observations.keySet().removeIf(key -> key.endpoint().equals(endpointKey));
        }
    }

    public void structured(URI endpoint, OpenCodeClient.OpenCodeModel model) {
        update(endpoint, model, OpenCodeClient.CapabilityState.AVAILABLE,
                OpenCodeClient.CapabilityState.AVAILABLE, null);
    }

    public void transportUnsupported(URI endpoint, OpenCodeClient.OpenCodeModel model, String detail) {
        update(endpoint, model, OpenCodeClient.CapabilityState.UNAVAILABLE,
                OpenCodeClient.CapabilityState.UNKNOWN, detail);
    }

    public void modelUnsupported(URI endpoint, OpenCodeClient.OpenCodeModel model, String detail) {
        update(endpoint, model, OpenCodeClient.CapabilityState.AVAILABLE,
                OpenCodeClient.CapabilityState.UNAVAILABLE, detail);
    }

    public OpenCodeClient.StructuredOutputCapability capability(URI endpoint, OpenCodeClient.OpenCodeModel model) {
        String endpointKey = endpoint == null ? "" : endpoint.toString();
        String version = versions.getOrDefault(endpointKey, "");
        if (knownStoredSchemaDecodeFailure(version)) {
            return new OpenCodeClient.StructuredOutputCapability(OpenCodeClient.CapabilityState.UNAVAILABLE,
                    OpenCodeClient.CapabilityState.UNKNOWN,
                    "OpenCode " + version + " cannot decode messages that persist format=json_schema; using marker compatibility mode");
        }
        Observation value = observations.get(key(endpoint, model));
        return value == null
                ? new OpenCodeClient.StructuredOutputCapability(OpenCodeClient.CapabilityState.UNKNOWN,
                OpenCodeClient.CapabilityState.UNKNOWN, null)
                : new OpenCodeClient.StructuredOutputCapability(value.transport(), value.model(), value.detail());
    }

    private void update(URI endpoint, OpenCodeClient.OpenCodeModel model,
                        OpenCodeClient.CapabilityState transport,
                        OpenCodeClient.CapabilityState selectedModel, String detail) {
        Key key = key(endpoint, model);
        observations.compute(key, (ignored, old) -> new Observation(
                transport == null && old != null ? old.transport() : transport,
                selectedModel == null && old != null ? old.model() : selectedModel,
                detail == null && old != null ? old.detail() : bounded(detail)));
    }

    private Key key(URI endpoint, OpenCodeClient.OpenCodeModel model) {
        String endpointKey = endpoint == null ? "" : endpoint.toString();
        return new Key(endpointKey, versions.getOrDefault(endpointKey, ""),
                model == null ? "" : blank(model.providerId()), model == null ? "" : blank(model.modelId()));
    }

    private static String blank(String value) { return value == null ? "" : value.trim(); }
    private static boolean knownStoredSchemaDecodeFailure(String version) {
        if (version == null) return false;
        String[] parts = version.trim().split("[.-]", 4);
        if (parts.length < 3) return false;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            return major == 1 && minor == 18 && patch >= 12 && patch <= 18;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private record Key(String endpoint, String version, String provider, String model) { }
    private record Observation(OpenCodeClient.CapabilityState transport,
                               OpenCodeClient.CapabilityState model, String detail) { }
}
