package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.SessionUsageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UsageInsightsServiceTest {
    @Test
    void usesStableSessionMessageKeyAndKeepsProviderUnknownValuesNull() {
        LoopperMapper mapper = mock(LoopperMapper.class); FakeOpenCodeClient openCode = new FakeOpenCodeClient();
        TaskRow task = new TaskRow("task", "project", "draft", "T", "RUNNING", Path.of(".").toAbsolutePath().toString(), "b", "c", "2026-08-05T00:00:00Z", "2026-08-05T00:00:01Z", 0);
        ExecutionSessionRow session = new ExecutionSessionRow("session", "task", "stage", "attempt", "remote", "COMPLETED", task.createdAt(), task.updatedAt(), 0);
        when(mapper.findTask("task")).thenReturn(java.util.Optional.of(task)); when(mapper.listSessions("task")).thenReturn(List.of(session));
        openCode.setSessionUsage("remote", List.of(new OpenCodeClient.UsageRecord("message", null, null, null, null, null, null, null, false)));
        UsageInsightsService service = new UsageInsightsService(mapper, openCode);
        service.collectTaskUsage("task"); service.collectTaskUsage("task");
        ArgumentCaptor<SessionUsageRow> rows = ArgumentCaptor.forClass(SessionUsageRow.class);
        verify(mapper, times(2)).insertSessionUsage(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> { assertThat(row.idempotencyKey()).isEqualTo("usage:session:session:message"); assertThat(row.totalTokens()).isNull(); assertThat(row.costAmount()).isNull(); assertThat(row.reliable()).isFalse(); });
    }

    @Test
    void blocksOnlyReliableKnownUsageAtTheExactSoftLimitAndDoesNotMixCurrencies() {
        LoopperMapper mapper = mock(LoopperMapper.class); FakeOpenCodeClient openCode = new FakeOpenCodeClient();
        TaskRow task = new TaskRow("task", "project", "draft", "T", "RUNNING", Path.of(".").toAbsolutePath().toString(), "b", "c", "2026-08-05T00:00:00Z", "2026-08-05T00:00:01Z", 0);
        when(mapper.findTask("task")).thenReturn(java.util.Optional.of(task)); when(mapper.listSessions("task")).thenReturn(List.of());
        when(mapper.listTaskUsage("task")).thenReturn(List.of(new SessionUsageRow("u", "task", "s", null, "m", "k", null, null, 4L, 6L, 10L, "3.00", "CNY", true, task.updatedAt())));
        UsageInsightsService service = new UsageInsightsService(mapper, openCode);
        LoopSpec spec = new LoopSpec("v1", "project", "goal", null, List.of(), null, null, null, null, new LoopSpec.BudgetSpec(10L, "3.00", "USD"));
        assertThat(service.budget(task, spec).blocked()).isTrue();
        when(mapper.listTaskUsage("task")).thenReturn(List.of(new SessionUsageRow("u", "task", "s", null, "m", "k", null, null, null, null, null, null, null, false, task.updatedAt())));
        assertThat(service.budget(task, spec).blocked()).isFalse();
    }

    @Test
    void persistsJudgeUsageAgainstTheJudgeRunAndDerivesTotalFromReliableComponents() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        FakeOpenCodeClient openCode = new FakeOpenCodeClient();
        TaskRow task = new TaskRow("task", "project", "draft", "T", "JUDGING", Path.of(".").toAbsolutePath().toString(),
                "b", "c", "2026-08-05T00:00:00Z", "2026-08-05T00:00:01Z", 0);
        JudgeRunRow judge = new JudgeRunRow("judge", "task", "attempt", "REQUIREMENT", 1, "judge-remote",
                "COMPLETED", "PASS", "ok", "{}", task.createdAt(), task.updatedAt(), 0);
        when(mapper.findTask("task")).thenReturn(java.util.Optional.of(task));
        when(mapper.listSessions("task")).thenReturn(List.of());
        when(mapper.listJudgeRuns("task")).thenReturn(List.of(judge));
        openCode.setSessionUsage("judge-remote", List.of(new OpenCodeClient.UsageRecord(
                "judge-message", "provider", "model", 4L, 6L, null, null, null, true)));

        new UsageInsightsService(mapper, openCode).collectTaskUsage("task");

        ArgumentCaptor<SessionUsageRow> row = ArgumentCaptor.forClass(SessionUsageRow.class);
        verify(mapper).insertSessionUsage(row.capture());
        assertThat(row.getValue().executionSessionId()).isNull();
        assertThat(row.getValue().judgeRunId()).isEqualTo("judge");
        assertThat(row.getValue().idempotencyKey()).isEqualTo("usage:judge:judge:judge-message");
        assertThat(row.getValue().totalTokens()).isEqualTo(10L);
        assertThat(row.getValue().reliable()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void forkSnapshotsDoNotInflateTaskRetryCount() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        TaskRow task = new TaskRow("task", "project", null, "T", "SUCCEEDED", "/tmp/project", "feature/fork",
                "baseline", "2026-08-05T00:00:00Z", "2026-08-05T00:01:00Z", 0);
        AttemptRow execution = new AttemptRow("attempt", task.id(), "stage", 1, "SUCCEEDED", null, "done",
                task.createdAt(), task.updatedAt(), 0);
        AttemptRow fork = new AttemptRow("fork-attempt", task.id(), "stage", 2, "SUCCEEDED", "SESSION_FORK_SNAPSHOT",
                "snapshot", task.updatedAt(), task.updatedAt(), 0);
        when(mapper.listTasks()).thenReturn(List.of(task));
        when(mapper.listTaskUsage(task.id())).thenReturn(List.of());
        when(mapper.listAllUsage()).thenReturn(List.of());
        when(mapper.listAttempts(task.id())).thenReturn(List.of(execution, fork));
        when(mapper.listVerifications(execution.id())).thenReturn(List.of());
        when(mapper.listVerifications(fork.id())).thenReturn(List.of());
        when(mapper.listJudgeRuns(task.id())).thenReturn(List.of());

        Map<String, Object> insight = ((List<Map<String, Object>>) new UsageInsightsService(mapper, new FakeOpenCodeClient())
                .insights().get("tasks")).getFirst();

        assertThat(insight.get("retryCount")).isEqualTo(0L);
    }
}
