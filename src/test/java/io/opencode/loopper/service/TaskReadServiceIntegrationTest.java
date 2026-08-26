package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.api.AutomationController;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
@Import(TaskReadServiceIntegrationTest.CountingConfiguration.class)
class TaskReadServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskReadService reads;
    @Autowired private DesignerReadService designerReads;
    @Autowired private ProjectReadService projectReads;
    @Autowired private InsightReadService insightReads;
    @Autowired private AutomationController automationController;
    @Autowired private ObjectMapper json;
    @Autowired private QueryCounter queries;

    @BeforeEach
    void setup() {
        flyway.clean();
        flyway.migrate();
        jdbc.update("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES(?,?,?,?,?)",
                "p", "Project", "/tmp/read-model-project", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        jdbc.update("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                "task-a", "p", "Alpha", "RUNNING", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
        jdbc.update("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                "task-b", "p", "Beta", "WAITING_INPUT", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
        jdbc.update("INSERT INTO stage(id,task_id,ordinal,objective,allowed_paths_json,forbidden_paths_json,deliverables_json,verifiers_json,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "stage-a", "task-a", 0, "Read model", "[]", "[]", "[]", "[]", "SUCCEEDED",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        jdbc.update("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,summary,created_at,ended_at) VALUES(?,?,?,?,?,?,?,?)",
                "attempt-a", "task-a", "stage-a", 1, "VERIFIED", "done",
                "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z");
        jdbc.update("INSERT INTO verification_result(id,attempt_id,verifier_index,type,state,summary,evidence_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                "verification-a", "attempt-a", 0, "PROCESS", "PASS", "passed",
                "{\"argv\":[\"mvn\",\"test\"],\"output\":\"" + "x".repeat(32_000) + "\"}",
                "2026-01-01T00:01:00Z");
        jdbc.update("INSERT INTO task_artifact(id,task_id,attempt_id,kind,name,content_type,content,metadata_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "artifact-a", "task-a", "attempt-a", "LOG", "attempt-handoff-1", "text/plain",
                "y".repeat(32_000), "{}", "2026-01-01T00:01:00Z");
        queries.reset();
    }

    @Test
    void keysetCursorIsStableWhenUpdatedTimesMatch() {
        var first = reads.summaries(null, List.of(), "ACTIVE", null, "newest", null, 1);
        assertThat(first.items()).extracting(TaskReadService.TaskSummary::id).containsExactly("task-b");
        assertThat(first.nextCursor()).isNotBlank();

        var second = reads.summaries(null, List.of(), "ACTIVE", null, "newest", first.nextCursor(), 1);
        assertThat(second.items()).extracting(TaskReadService.TaskSummary::id).containsExactly("task-a");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void overviewAndAuditExcludeLargeBodiesUntilScopedContentRequest() {
        var overview = reads.overview("task-a");
        var audit = reads.audit("task-a");

        assertThat(overview.attemptCount()).isEqualTo(1);
        assertThat(audit.attempts()).hasSize(1);
        assertThat(audit.attempts().getFirst().verifications().getFirst().evidenceSummary().has("output")).isFalse();
        assertThat(audit.artifacts().getFirst().contentBytes()).isEqualTo(32_000);
        assertThat(reads.verificationEvidence("task-a", "verification-a").content()).contains("x".repeat(100));
        assertThat(reads.artifactContent("task-a", "artifact-a").content()).hasSize(32_000);
        assertThatThrownBy(() -> reads.verificationEvidence("task-b", "verification-a"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void overviewProjectsTheAuthoritativeCancellationCapabilityForEveryTaskState() {
        assertThat(reads.overview("task-a").cancellationAvailable()).isTrue();

        jdbc.update("UPDATE task SET state=? WHERE id=?", "QUEUED", "task-a");
        assertThat(reads.overview("task-a").cancellationAvailable()).isTrue();

        jdbc.update("UPDATE task SET state=? WHERE id=?", "AWAITING_DECISION", "task-a");
        assertThat(reads.overview("task-a").cancellationAvailable()).isFalse();

        jdbc.update("UPDATE task SET state=? WHERE id=?", "CANCELLED", "task-a");
        assertThat(reads.overview("task-a").cancellationAvailable()).isFalse();
    }

    @Test
    void rollingOverviewProjectsPackageStateAndFailsClosedCapabilitiesFromPersistedFacts() {
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                "rolling-draft", "p", "Rolling", "{}", "CONFIRMED", "now", "now");
        jdbc.update("UPDATE task SET loop_draft_id=?,state='WAITING_INPUT',execution_mode='ROLLING_PACKAGES',workspace_policy='RELEASE_BETWEEN_PACKAGES' WHERE id='task-a'",
                "rolling-draft");
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,workflow_phase,task_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "rolling-designer", "p", "REVIEWING", "READ_ONLY", "rolling-draft", "REVIEWING_PACKAGE",
                "task-a", "now", "now");
        jdbc.update("INSERT INTO designer_message(id,designer_session_id,ordinal,role,content,delivery_state,created_at) VALUES(?,?,?,?,?,?,?)",
                "rolling-message", "rolling-designer", 1, "ASSISTANT", "design", "PERSISTED", "now");
        jdbc.update("INSERT INTO design_requirement_revision(id,designer_session_id,revision,source_message_id,requirement_text,requirement_segments_json,source_draft_version,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "rolling-requirement", "rolling-designer", 1, "rolling-message", "requirement", "[]", 0,
                "COMPLETED", "now", "now");
        jdbc.update("INSERT INTO task_decomposition(id,designer_session_id,requirement_revision_id,state,result_type,source_draft_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                "rolling-decomposition", "rolling-designer", "rolling-requirement", "COMPLETED", "DECOMPOSED",
                0, "now", "now");
        jdbc.update("INSERT INTO design_work_package(id,designer_session_id,requirement_revision_id,decomposition_id,package_id,ordinal,title,objective,scope_in_json,scope_out_json,dependencies_json,deliverables_json,acceptance_intent_json,requirement_refs_json,state,design_message_id,design_revision,approved_design_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "rolling-package", "rolling-designer", "rolling-requirement", "rolling-decomposition", "WP-1", 0,
                "基础能力", "实现基础能力", "[]", "[]", "[]", "[]", "[]", "[]", "APPROVED",
                "rolling-message", 2, 2, "now", "now");
        jdbc.update("INSERT INTO task_package_plan_revision(id,task_id,designer_session_id,requirement_revision_id,revision,state,plan_json,impact_json,created_at,approved_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "rolling-plan", "task-a", "rolling-designer", "rolling-requirement", 1, "ACTIVE", "[]", "{}", "now", "now");
        jdbc.update("INSERT INTO task_package_run(id,task_id,plan_revision_id,design_work_package_id,package_key,ordinal,title,state,discussion_revision,design_revision,accepted_design_revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "rolling-run", "task-a", "rolling-plan", "rolling-package", "WP-1", 0, "基础能力",
                "EXECUTION_READY", 3, 2, 2, "now", "now");

        var ready = reads.overview("task-a");
        assertThat(ready.executionMode()).isEqualTo("ROLLING_PACKAGES");
        assertThat(ready.currentPackage()).extracting(TaskReadService.CurrentPackage::id).isEqualTo("rolling-run");
        assertThat(ready.plannedPackageCount()).isEqualTo(1);
        assertThat(ready.packageCapabilities()).satisfies(capabilities -> {
            assertThat(capabilities.canStartPackage()).isTrue();
            assertThat(capabilities.canApproveDesign()).isFalse();
            assertThat(capabilities.canReplanRemaining()).isFalse();
        });

        jdbc.update("UPDATE task_package_run SET state='WAITING_INPUT',waiting_reason_code='PACKAGE_CHECKPOINT_BLOCKED' WHERE id='rolling-run'");
        var blocked = reads.overview("task-a").packageCapabilities();
        assertThat(blocked.canRetryPackage()).isTrue();
        assertThat(blocked.canRedesignPackage()).isFalse();
    }

    @Test
    void overviewAndSummarySelectOneRetryPlanWhenClaimedHistoryOverlapsAnActivePlan() {
        jdbc.update("""
                INSERT INTO task_retry_schedule(id,task_id,stage_id,cause,ordinal,delay_seconds,due_at,
                  remaining_seconds,prompt,state,created_at,updated_at,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "retry-claimed", "task-a", "stage-a", "SESSION", 1, 10,
                "2026-01-02T00:00:00Z", null, "claimed", "CLAIMED",
                "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", 0);
        jdbc.update("""
                INSERT INTO task_retry_schedule(id,task_id,stage_id,cause,ordinal,delay_seconds,due_at,
                  remaining_seconds,prompt,state,created_at,updated_at,version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "retry-scheduled", "task-a", "stage-a", "VERIFICATION", 2, 20,
                "2026-01-03T00:00:00Z", null, "scheduled", "SCHEDULED",
                "2026-01-02T00:00:00Z", "2026-01-02T00:01:00Z", 0);

        assertThat(reads.overview("task-a")).satisfies(overview -> {
            assertThat(overview.retryCause()).isEqualTo("VERIFICATION");
            assertThat(overview.retryOrdinal()).isEqualTo(2);
        });
        assertThat(reads.summaries(null, List.of(), "ACTIVE", null, "newest", null, 50).items())
                .extracting(TaskReadService.TaskSummary::id)
                .containsExactly("task-b", "task-a");
    }

    @Test
    void readModelsStayWithinFixedQueryBudgets() {
        queries.reset();
        reads.summaries(null, List.of(), "ACTIVE", null, "newest", null, 50);
        assertThat(queries.count()).isEqualTo(2);

        queries.reset();
        reads.overview("task-a");
        assertThat(queries.count()).isEqualTo(4);

        queries.reset();
        reads.audit("task-a");
        assertThat(queries.count()).isEqualTo(3);

        queries.reset();
        designerReads.history(null, null, "ALL", null, "newest", null, 50);
        assertThat(queries.count()).isEqualTo(1);

        queries.reset();
        projectReads.summaries(false);
        assertThat(queries.count()).isEqualTo(1);

        queries.reset();
        insightReads.page(null, 50);
        assertThat(queries.count()).isEqualTo(4);

        queries.reset();
        automationController.workspace();
        assertThat(queries.count()).isEqualTo(4);
    }

    @Test
    void boundedResponsesStaySmallAtOneThousandTasksAndUseCursorIndex() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 2; index < 1_000; index++) {
            rows.add(new Object[]{"task-" + index, "p", "Task " + index, "COMPLETED",
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"});
        }
        jdbc.batchUpdate("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES(?,?,?,?,?,?)", rows);

        List<Object[]> attempts = new ArrayList<>();
        for (int index = 2; index <= 12; index++) {
            attempts.add(new Object[]{"attempt-" + index, "task-a", "stage-a", index, "VERIFIED", "done",
                    "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z"});
        }
        jdbc.batchUpdate("INSERT INTO attempt(id,task_id,stage_id,ordinal,state,summary,created_at,ended_at) VALUES(?,?,?,?,?,?,?,?)", attempts);
        List<Object[]> verifications = new ArrayList<>();
        for (int index = 1; index < 48; index++) {
            String attemptId = index < 4 ? "attempt-a" : "attempt-" + (2 + (index - 4) / 4);
            verifications.add(new Object[]{"verification-" + index, attemptId, index % 4, "PROCESS", "PASS",
                    "passed", "{\"output\":\"" + "x".repeat(8_000) + "\"}", "2026-01-01T00:01:00Z"});
        }
        jdbc.batchUpdate("INSERT INTO verification_result(id,attempt_id,verifier_index,type,state,summary,evidence_json,created_at) VALUES(?,?,?,?,?,?,?,?)", verifications);
        List<Object[]> artifacts = new ArrayList<>();
        for (int index = 1; index < 100; index++) {
            artifacts.add(new Object[]{"artifact-" + index, "task-a", "attempt-a", "LOG", "artifact-" + index,
                    "text/plain", "y".repeat(8_000), "{}", "2026-01-01T00:01:00Z"});
        }
        jdbc.batchUpdate("INSERT INTO task_artifact(id,task_id,attempt_id,kind,name,content_type,content,metadata_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)", artifacts);

        List<Object[]> drafts = new ArrayList<>();
        List<Object[]> sessions = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            String suffix = Integer.toString(index);
            drafts.add(new Object[]{"scale-draft-" + suffix, "p", "Design " + suffix, "{}", "DRAFT_READY",
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"});
            sessions.add(new Object[]{"scale-session-" + suffix, "p", "RUNNING", "READ_ONLY", "scale-draft-" + suffix,
                    "DISCUSSING_REQUIREMENT", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"});
        }
        jdbc.batchUpdate("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)", drafts);
        jdbc.batchUpdate("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,workflow_phase,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", sessions);

        byte[] summaries = json.writeValueAsBytes(reads.summaries(null, List.of(), "ALL", null, "newest", null, 50));
        byte[] overview = json.writeValueAsBytes(reads.overview("task-a"));
        byte[] audit = json.writeValueAsBytes(reads.audit("task-a"));
        assertThat(summaries.length).isLessThan(128 * 1024);
        assertThat(overview.length).isLessThan(64 * 1024);
        assertThat(audit.length).isLessThan(128 * 1024);
        assertThat(new String(summaries)).doesNotContain("evidenceJson", "rawOutput", "content");
        assertThat(new String(audit)).doesNotContain("\"output\":", "x".repeat(100), "y".repeat(100));
        assertThat(designerReads.history("p", null, "ACTIVE", null, "newest", null, 50).items()).hasSize(50);

        List<String> plan = jdbc.query("EXPLAIN QUERY PLAN SELECT id FROM task ORDER BY updated_at DESC,id DESC LIMIT 50",
                (result, row) -> result.getString("detail"));
        assertThat(plan).anySatisfy(detail -> assertThat(detail).contains("idx_task_updated_id"));
    }

    @Test
    void rejectsInvalidCursorAndUnboundedPageLength() {
        assertThatThrownBy(() -> reads.summaries(null, List.of(), "ACTIVE", null, "newest", "not-a-cursor", 50))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reads.summaries(null, List.of(), "ACTIVE", null, "newest", null, 101))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void designerHistoryUsesLatestSessionAndProjectCountersAreBatched() {
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                "draft-a", "p", "Design A", "{}", "DRAFT_READY", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        jdbc.update("INSERT INTO loop_draft(id,project_id,goal,spec_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                "draft-b", "p", "Design B", "{}", "DRAFT_READY", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        insertDesigner("designer-old", "draft-a", "2026-01-01T00:00:00Z");
        insertDesigner("designer-a", "draft-a", "2026-01-02T00:00:00Z");
        insertDesigner("designer-b", "draft-b", "2026-01-02T00:00:00Z");

        var first = designerReads.history("p", "PROCESSING", "ACTIVE", "Design", "newest", null, 1);
        var second = designerReads.history("p", "PROCESSING", "ACTIVE", "Design", "newest", first.nextCursor(), 1);

        assertThat(first.items()).hasSize(1);
        assertThat(second.items()).hasSize(1);
        assertThat(List.of(first.items().getFirst().id(), second.items().getFirst().id()))
                .containsExactly("designer-b", "designer-a");
        assertThat(projectReads.summaries(false)).singleElement().satisfies(project -> {
            assertThat(project.taskCount()).isEqualTo(2);
            assertThat(project.openDesignerSessionCount()).isEqualTo(2);
        });
    }

    private void insertDesigner(String id, String draftId, String updatedAt) {
        jdbc.update("INSERT INTO designer_session(id,project_id,state,access_mode,loop_draft_id,workflow_phase,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                id, "p", "RUNNING", "READ_ONLY", draftId, "DISCUSSING_REQUIREMENT", updatedAt, updatedAt);
    }

    @TestConfiguration
    static class CountingConfiguration {
        @Bean QueryCounter queryCounter() { return new QueryCounter(); }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class QueryCounter implements Interceptor {
        private final AtomicInteger count = new AtomicInteger();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            count.incrementAndGet();
            return invocation.proceed();
        }
        void reset() { count.set(0); }
        int count() { return count.get(); }
    }
}
