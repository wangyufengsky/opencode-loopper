package io.opencode.loopper.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.service.ConflictException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LifecycleTransitionServiceIntegrationTest {
    @Autowired private LifecycleTransitionService lifecycle;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void rollsBackStateMutationWhenAuditInsertFails() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String projectId = "fsm-project-" + suffix;
        String taskId = "fsm-task-" + suffix;
        String trigger = "fail_state_transition_audit_" + suffix;
        seedReadyTask(projectId, taskId);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON state_transition_event "
                + "BEGIN SELECT RAISE(ABORT, 'audit blocked'); END");
        try {
            assertThatThrownBy(() -> lifecycle.transition(subject(taskId), "READY", "RUNNING", null, Map.of(),
                    () -> jdbc.update("UPDATE task SET state='RUNNING',version=version+1 "
                            + "WHERE id=? AND state='READY' AND version=0", taskId),
                    () -> new ConflictException("TASK_VERSION_CONFLICT", "task changed")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("audit blocked");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, taskId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT version FROM task WHERE id=?", Long.class, taskId)).isZero();
        assertThat(auditCount(taskId)).isZero();
    }

    @Test
    void casConflictAndInvalidMetadataCannotCreateAuditOrMutateState() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String projectId = "fsm-project-" + suffix;
        String taskId = "fsm-task-" + suffix;
        seedReadyTask(projectId, taskId);

        assertThatThrownBy(() -> lifecycle.transition(subject(taskId), "READY", "RUNNING", null, Map.of(),
                () -> 0, () -> new ConflictException("TASK_VERSION_CONFLICT", "task changed")))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_VERSION_CONFLICT"));

        AtomicBoolean mutated = new AtomicBoolean();
        assertThatThrownBy(() -> lifecycle.transition(subject(taskId), "READY", "RUNNING", null,
                Map.of("summary", "字".repeat(17_000)),
                () -> { mutated.set(true); return 1; },
                () -> new ConflictException("TASK_VERSION_CONFLICT", "task changed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 KiB");

        assertThat(mutated).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, taskId)).isEqualTo("READY");
        assertThat(auditCount(taskId)).isZero();
    }

    private void seedReadyTask(String projectId, String taskId) {
        String now = "2026-08-10T00:00:00Z";
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES(?,?,?,?,?)",
                projectId, "FSM", "/tmp/" + projectId, now, now);
        jdbc.update("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                taskId, projectId, "FSM", "READY", now, now);
    }

    private long auditCount(String taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM state_transition_event WHERE entity_id=?", Long.class, taskId);
    }

    private LifecycleTransitionService.Subject subject(String taskId) {
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.TASK, taskId, LifecycleScopeType.TASK, taskId);
    }
}
