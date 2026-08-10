package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StateTransitionEventRow;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public final class StateTransitionQueryService {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    public StateTransitionQueryService(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public TransitionPage query(String machineType, String entityId, String scopeType, String scopeId,
                                Long afterSequence, Integer requestedLimit) {
        boolean entitySelector = present(machineType) || present(entityId);
        boolean scopeSelector = present(scopeType) || present(scopeId);
        if (entitySelector == scopeSelector || (entitySelector && (!present(machineType) || !present(entityId)))
                || (scopeSelector && (!present(scopeType) || !present(scopeId)))) {
            throw invalidQuery();
        }
        long after = afterSequence == null ? 0 : afterSequence;
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (after < 0 || limit < 1 || limit > MAX_LIMIT) throw invalidQuery();

        List<StateTransitionEventRow> rows;
        if (entitySelector) {
            LifecycleMachineType machine = enumValue(LifecycleMachineType.class, machineType);
            rows = mapper.listStateTransitionsForEntity(machine.name(), entityId.trim(), after, limit + 1);
        } else {
            LifecycleScopeType scope = enumValue(LifecycleScopeType.class, scopeType);
            rows = mapper.listStateTransitionsForScope(scope.name(), scopeId.trim(), after, limit + 1);
        }
        boolean hasMore = rows.size() > limit;
        List<StateTransitionDto> items = new ArrayList<>(Math.min(rows.size(), limit));
        for (StateTransitionEventRow row : rows.subList(0, Math.min(rows.size(), limit))) items.add(dto(row));
        Long next = hasMore && !items.isEmpty() ? items.getLast().sequence() : null;
        return new TransitionPage(List.copyOf(items), next, hasMore);
    }

    private StateTransitionDto dto(StateTransitionEventRow row) {
        try {
            return new StateTransitionDto(row.sequence(), row.id(), row.machineType(), row.entityId(),
                    row.scopeType(), row.scopeId(), row.event(), row.fromState(), row.toState(), row.reasonCode(),
                    json.readTree(row.metadataJson()), row.occurredAt());
        } catch (JacksonException unreadable) {
            throw new IllegalStateException("Stored lifecycle audit metadata is invalid", unreadable);
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static BadRequestException invalidQuery() {
        return new BadRequestException("STATE_TRANSITION_QUERY_INVALID",
                "Provide exactly one complete selector: machineType/entityId or scopeType/scopeId");
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return Enum.valueOf(type, value.trim()); }
        catch (RuntimeException invalid) { throw invalidQuery(); }
    }

    public record TransitionPage(List<StateTransitionDto> items, Long nextAfterSequence, boolean hasMore) { }
    public record StateTransitionDto(long sequence, String id, String machineType, String entityId,
                                     String scopeType, String scopeId, String event, String fromState,
                                     String toState, String reasonCode, JsonNode metadata, String occurredAt) { }
}
