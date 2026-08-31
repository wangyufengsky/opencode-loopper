package io.opencode.loopper.persistence;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.runtime.OpenCodeSessionRuntimeBindings;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** MyBatis-backed durable runtime identity registry; it never stores credentials or endpoint URLs. */
@Service
public final class PersistentOpenCodeSessionRuntimeBindings implements OpenCodeSessionRuntimeBindings {
    private final LoopperMapper mapper;

    public PersistentOpenCodeSessionRuntimeBindings(LoopperMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public synchronized void register(Binding binding) {
        OpenCodeSessionRuntimeBindingRow desired = row(binding, Instant.now().toString());
        OpenCodeSessionRuntimeBindingRow existing = mapper
                .findOpenCodeSessionRuntimeBinding(binding.externalSessionId()).orElse(null);
        if (existing != null) {
            requireSame(existing, desired);
            return;
        }
        if (mapper.insertOpenCodeSessionRuntimeBinding(desired) != 1) {
            throw failure("OpenCode session runtime binding was not persisted");
        }
    }

    @Override
    public Optional<Binding> find(String externalSessionId) {
        return mapper.findOpenCodeSessionRuntimeBinding(externalSessionId).map(this::binding);
    }

    private OpenCodeSessionRuntimeBindingRow row(Binding binding, String createdAt) {
        return new OpenCodeSessionRuntimeBindingRow(binding.externalSessionId(), binding.runtimeGenerationId(),
                binding.ownershipMode().name(), binding.endpointFingerprint(), binding.internalMcpServer(), createdAt);
    }

    private Binding binding(OpenCodeSessionRuntimeBindingRow row) {
        OwnershipMode ownership;
        try {
            ownership = OwnershipMode.valueOf(row.ownershipMode());
        } catch (IllegalArgumentException invalid) {
            ownership = OwnershipMode.LEGACY_UNKNOWN;
        }
        return new Binding(row.externalSessionId(), row.runtimeGenerationId(), ownership,
                row.endpointFingerprint(), row.internalMcpServer());
    }

    private void requireSame(OpenCodeSessionRuntimeBindingRow existing,
                             OpenCodeSessionRuntimeBindingRow desired) {
        if (!existing.runtimeGenerationId().equals(desired.runtimeGenerationId())
                || !existing.ownershipMode().equals(desired.ownershipMode())
                || !existing.endpointFingerprint().equals(desired.endpointFingerprint())
                || !java.util.Objects.equals(existing.internalMcpServer(), desired.internalMcpServer())) {
            throw failure("OpenCode session id is already bound to a different runtime identity");
        }
    }

    private SessionFailure failure(String detail) {
        return new SessionFailure("OPENCODE_SESSION_RUNTIME_BINDING_FAILED", detail);
    }
}
