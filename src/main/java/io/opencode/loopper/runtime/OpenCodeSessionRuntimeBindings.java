package io.opencode.loopper.runtime;

import java.util.Optional;

/**
 * Durable, non-secret identity for a remote OpenCode Session. Implementations
 * must persist a new binding before the Session is exposed to an owner row.
 */
public interface OpenCodeSessionRuntimeBindings {
    void register(Binding binding);

    Optional<Binding> find(String externalSessionId);

    enum OwnershipMode { MANAGED, EXTERNAL, LEGACY_UNKNOWN }

    record Binding(String externalSessionId, String runtimeGenerationId,
                   OwnershipMode ownershipMode, String endpointFingerprint,
                   String internalMcpServer) { }

    /** Compatibility seam for isolated adapter tests that do not use persistence. */
    static OpenCodeSessionRuntimeBindings untracked() {
        return new OpenCodeSessionRuntimeBindings() {
            @Override public void register(Binding binding) { }
            @Override public Optional<Binding> find(String externalSessionId) { return Optional.empty(); }
        };
    }
}
