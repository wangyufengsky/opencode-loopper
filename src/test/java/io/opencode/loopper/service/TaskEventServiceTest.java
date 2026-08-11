package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskEventRow;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskEventServiceTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final TaskEventHub hub = mock(TaskEventHub.class);
    private final TaskEventService events = new TaskEventService(mapper, JsonMapper.builder().build(), hub);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void publishesImmediatelyWithoutAnActiveTransaction() {
        when(mapper.maxEventSequence("task-1")).thenReturn(4L);

        TaskEventRow event = events.emit("task-1", "task.updated", Map.of("state", "RUNNING"));

        assertThat(event.sequence()).isEqualTo(5L);
        verify(mapper).insertTaskEvent(event);
        verify(hub).publish(event);
    }

    @Test
    void defersLiveDeliveryUntilTheAuthoritativeTransactionCommits() {
        when(mapper.maxEventSequence("task-1")).thenReturn(9L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        TaskEventRow event = events.emit("task-1", "session.started", Map.of("sessionId", "session-1"));

        verify(mapper).insertTaskEvent(event);
        verifyNoInteractions(hub);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.afterCommit();

        verify(hub).publish(event);
    }
}
