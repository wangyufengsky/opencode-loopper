package io.opencode.loopper.lifecycle;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StateTransitionEventRow;
import io.opencode.loopper.service.ConflictException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validates a transition and commits its versioned mutation and audit event atomically. */
@Service
public final class LifecycleTransitionService {
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "token", "prompt", "content", "permission", "path", "secret", "raw");
    private final LifecycleRegistry registry;
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public LifecycleTransitionService(LifecycleRegistry registry, LoopperMapper mapper, ObjectMapper json,
                                      PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.mapper = mapper;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void create(Subject subject, String initialState, Map<String, ?> metadata,
                       IntSupplier mutation, Supplier<? extends RuntimeException> conflict) {
        registry.requireState(subject.machineType(), subject.entityId(), initialState);
        execute(subject, null, initialState, LifecycleEvent.CREATED, null, metadata, mutation, conflict, true);
    }

    public void transition(Subject subject, String fromState, String toState, String reasonCode,
                           Map<String, ?> metadata, IntSupplier mutation,
                           Supplier<? extends RuntimeException> conflict) {
        transition(subject, fromState, toState, null, reasonCode, metadata, mutation, conflict);
    }

    public void transition(Subject subject, String fromState, String toState, LifecycleEvent explicitEvent,
                           String reasonCode, Map<String, ?> metadata, IntSupplier mutation,
                           Supplier<? extends RuntimeException> conflict) {
        LifecycleRegistry.ResolvedTransition resolved;
        try {
            resolved = registry.resolve(subject.machineType(), subject.entityId(), fromState, toState, explicitEvent);
        } catch (InvalidStateTransitionException invalid) {
            throw new ConflictException("STATE_TRANSITION_INVALID", invalid.getMessage());
        }
        execute(subject, resolved.fromState(), resolved.toState(), resolved.event(), reasonCode,
                metadata, mutation, conflict, false);
    }

    /** Executes an optimistic-lock mutation that deliberately does not change lifecycle state or write transition audit. */
    public void mutateWithoutTransition(IntSupplier mutation, Supplier<? extends RuntimeException> conflict) {
        transactions.executeWithoutResult(status -> {
            if (mutation.getAsInt() != 1) throw conflict.get();
        });
    }

    private void execute(Subject subject, String fromState, String toState, LifecycleEvent event,
                         String reasonCode, Map<String, ?> metadata, IntSupplier mutation,
                         Supplier<? extends RuntimeException> conflict, boolean creation) {
        String metadataJson = metadata(metadata);
        transactions.executeWithoutResult(status -> {
            if (mutation.getAsInt() != 1) throw conflict.get();
            StateTransitionEventRow row = new StateTransitionEventRow(0, UUID.randomUUID().toString(),
                    subject.machineType().name(), subject.entityId(), subject.scopeType().name(), subject.scopeId(),
                    event.name(), fromState, toState, blankToNull(reasonCode), metadataJson, Instant.now().toString());
            if (mapper.insertStateTransitionEvent(row) != 1) {
                throw new IllegalStateException((creation ? "Creation" : "Transition") + " audit was not persisted");
            }
        });
    }

    private String metadata(Map<String, ?> value) {
        Map<String, ?> safe = value == null ? Map.of() : value;
        try {
            JsonNode tree = json.valueToTree(safe);
            rejectSensitiveKeys(tree);
            String encoded = json.writeValueAsString(tree);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("Lifecycle audit metadata exceeds 16 KiB");
            }
            return encoded;
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("Lifecycle audit metadata cannot be serialized", failure);
        }
    }

    private void rejectSensitiveKeys(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            var fields = node.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT);
                if (SENSITIVE_KEY_PARTS.stream().anyMatch(key::contains)) {
                    throw new IllegalArgumentException("Sensitive lifecycle metadata key is not allowed: " + field.getKey());
                }
                rejectSensitiveKeys(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::rejectSensitiveKeys);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record Subject(LifecycleMachineType machineType, String entityId,
                          LifecycleScopeType scopeType, String scopeId) {
        public Subject {
            if (machineType == null || scopeType == null || entityId == null || entityId.isBlank()
                    || scopeId == null || scopeId.isBlank()) {
                throw new IllegalArgumentException("Lifecycle transition subject is incomplete");
            }
        }
    }
}
