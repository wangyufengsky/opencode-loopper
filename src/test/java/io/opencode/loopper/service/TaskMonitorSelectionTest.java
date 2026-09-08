package io.opencode.loopper.service;

import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskMonitorSelectionTest {
    @Test void retainsIndependentWriterCleanupWithoutLoadingHistoricalTasks() {
        var mapper = mock(LoopperMapper.class);
        var tasks = mock(TaskService.class);
        var cleanup = mock(ExecutionSessionRow.class);
        when(cleanup.id()).thenReturn("terminal-task-writer");
        when(mapper.sessionsPendingAbortCleanup()).thenReturn(List.of(cleanup));
        var monitor = new TaskMonitor(mapper, mock(OpenCodeClient.class), tasks,
                mock(TaskVerificationDispatcher.class), mock(ImplementationTodoSynchronizer.class));
        monitor.poll();
        verify(tasks).retrySessionCleanup("terminal-task-writer");
        verify(mapper).tasksForMonitoring();
        verify(mapper, never()).listTasks();
    }
}
