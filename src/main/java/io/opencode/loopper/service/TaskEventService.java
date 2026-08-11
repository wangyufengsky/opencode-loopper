package io.opencode.loopper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskEventRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TaskEventService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final TaskEventHub hub;
    public TaskEventService(LoopperMapper mapper, ObjectMapper json, TaskEventHub hub) { this.mapper = mapper; this.json = json; this.hub = hub; }
    public synchronized TaskEventRow emit(String taskId, String type, Map<String, ?> payload) {
        try {
            TaskEventRow event = new TaskEventRow(UUID.randomUUID().toString(), taskId, mapper.maxEventSequence(taskId) + 1,
                    type, json.writeValueAsString(payload), Instant.now().toString());
            mapper.insertTaskEvent(event);
            publishAfterCommit(event);
            return event;
        } catch (JacksonException e) { throw new IllegalStateException("Unable to serialize task event", e); }
    }

    private void publishAfterCommit(TaskEventRow event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { hub.publish(event); }
            });
            return;
        }
        hub.publish(event);
    }
}
