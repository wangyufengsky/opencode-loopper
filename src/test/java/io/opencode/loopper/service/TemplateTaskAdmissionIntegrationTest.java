package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class TemplateTaskAdmissionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired TemplateTaskService service;
    @Autowired TemplateTaskMapper templates;
    @Autowired LoopperMapper mapper;
    @Autowired ProjectService projects;
    @Autowired GitEvidenceProcess git;
    @Autowired LoopperProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired TemplateTaskReadService reads;
    @Autowired StoryBindingService stories;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @TempDir Path temporary;
    private String projectId;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate();
        properties.getOpenCode().setModel("fake/test-model");
        Path source = Files.createDirectory(temporary.resolve("project"));
        git.read(source, "init", "-b", "main");
        git.read(source, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "--allow-empty", "-m", "root");
        projectId = projects.create("test-project", source.toString(), "test").id();
    }

    @Test void createsConfirmedPendingTaskWithoutDesignModelWorkspaceLeaseOrExecutionClock() {
        var task = service.create(request("CODE_REVIEW", "2026-09-05", "2026-09-11"), false);
        assertThat(task.state()).isEqualTo("PENDING_START");
        assertThat(task.executionMode()).isEqualTo("TEMPLATE_REPORT");
        assertThat(task.workspacePolicy()).isEqualTo("ISOLATED_REPORT");
        assertThat(task.worktreePath()).isNull();
        assertThat(mapper.findTaskQueue(task.id())).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(mapper.activeTaskExecutionCycle(task.id())).isEmpty();
        assertThat(mapper.listSessions(task.id())).isEmpty();
        assertThat(mapper.listStages(task.id())).hasSize(2).allSatisfy(stage -> assertThat(stage.state()).isEqualTo("PENDING"));
        assertThat(mapper.findDraft(task.loopDraftId()).orElseThrow().status()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isZero();
        assertThat(mapper.findTaskStoryBinding(task.id())).isEmpty();
        assertThat(templates.findRun(task.id()).orElseThrow().contractJson()).contains("test-model", "CONTRIBUTION_SCORE_V1", "Asia/Shanghai",
                "HISTORY_REPORT_LAYOUT_V1", "HISTORY_REVIEW_V1", "CONTRIBUTION_REPORT_V3", "PERSONAL_CONTRIBUTION_V3", "sha256");
    }

    @Test void rejectsNewStoryEnabledRequestsWithoutCreatingTasksOrBindings() {
        var base = request("CODE_REVIEW", "2026-09-05", "2026-09-11");
        var enabled = new TemplateTaskService.Request(base.requestKey(), base.templateId(), base.templateVersion(),
                base.projectId(), base.branchId(), base.startDate(), base.endDate(), new StoryBindingConfiguration(true, "001", "0002"));
        assertThatThrownBy(() -> service.create(enabled, false)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("模板任务暂不支持故事统计");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_binding", Integer.class)).isZero();
        var disabled = new TemplateTaskService.Request(base.requestKey(), base.templateId(), base.templateVersion(),
                base.projectId(), base.branchId(), base.startDate(), base.endDate(), StoryBindingConfiguration.disabled());
        assertThat(mapper.findTaskStoryBinding(service.create(disabled, false).id())).isEmpty();
    }

    @Test void legacyBoundRequestReplayPreservesOriginalTaskAndBinding() {
        var base = request("CODE_REVIEW", "2026-09-05", "2026-09-11");
        var task = service.create(base, false);
        var story = new StoryBindingConfiguration(true, "001", "0002");
        stories.attachTask(task.id(), story);
        var legacy = new TemplateTaskService.Request(base.requestKey(), base.templateId(), base.templateVersion(),
                base.projectId(), base.branchId(), base.startDate(), base.endDate(), story);
        var legacyJson = (tools.jackson.databind.node.ObjectNode) json.valueToTree(legacy);
        legacyJson.remove("documentPath");
        // Reconstruct the immutable request digest written before template statistics were disabled.
        jdbc.update("UPDATE template_task_run SET request_sha256=? WHERE task_id=?",
                TemplateGitEvidenceCollector.hash(json.writeValueAsString(legacyJson) + ":false"), task.id());
        assertThat(service.create(legacy, false).id()).isEqualTo(task.id());
        assertThat(mapper.findTaskStoryBinding(task.id()).orElseThrow().storyCode()).isEqualTo("0002");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
    }

    @Test void idempotentRetryKeepsFrozenConfigurationAndRejectsParameterReplacement() {
        var request = request("CONTRIBUTION_REPORT", "2026-09-05", "2026-09-11");
        var first = service.create(request, false);
        properties.getOpenCode().setModel("other/other-model");
        assertThat(service.create(request, false).id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
        assertThat(templates.findRun(first.id()).orElseThrow().contractJson()).contains("test-model").doesNotContain("other-model");
        var changed = new TemplateTaskService.Request(request.requestKey(), request.templateId(), request.templateVersion(),
                request.projectId(), request.branchId(), "2026-09-06", request.endDate(), request.story());
        assertThatThrownBy(() -> service.create(changed, false)).isInstanceOf(ConflictException.class).hasMessageContaining("不同参数");
    }

    @Test void invalidDatesVersionsAndBranchesCannotCreatePartialTasks() {
        assertThatThrownBy(() -> service.create(request("CODE_REVIEW", "2026-09-12", "2026-09-11"), false)).isInstanceOf(BadRequestException.class);
        var request = request("CODE_REVIEW", "2026-09-11", "2026-09-11");
        var stale = new TemplateTaskService.Request(request.requestKey(), request.templateId(), "old", projectId,
                request.branchId(), request.startDate(), request.endDate(), request.story());
        assertThatThrownBy(() -> service.create(stale, false)).isInstanceOf(ConflictException.class);
        var missing = new TemplateTaskService.Request(request.requestKey(), request.templateId(), io.opencode.loopper.template.TemplateTaskDefinition.VERSION, projectId,
                "local:refs/heads/missing", request.startDate(), request.endDate(), request.story());
        assertThatThrownBy(() -> service.create(missing, false)).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loop_draft", Integer.class)).isZero();
    }

    @Test void documentDefaultsAreVersionedAndFrozenWhileOverridesStayPerTask() {
        var project = projects.get(projectId);
        var changed = projects.updateDocumentPath(projectId, "docs/reports", project.version());
        String expected = Path.of(project.rootPath()).resolve("docs/reports").toString();
        assertThat(changed.documentPath()).isEqualTo(expected);
        assertThat(reads.projects("test", null, 10).items().getFirst().documentPath()).isEqualTo(expected);
        var request = request("CODE_REVIEW", "2026-09-11", "2026-09-11");
        var first = service.create(request, false);
        assertThat(frozenDocumentPath(first.id())).isEqualTo(expected);
        projects.updateDocumentPath(projectId, "docs/new", changed.version());
        assertThatThrownBy(() -> projects.updateDocumentPath(projectId, "docs/stale", changed.version()))
                .isInstanceOf(ConflictException.class);
        assertThat(service.create(request, false).id()).isEqualTo(first.id());
        assertThat(frozenDocumentPath(first.id())).isEqualTo(expected);
        var override = new TemplateTaskService.Request(UUID.randomUUID().toString(), request.templateId(), request.templateVersion(),
                projectId, request.branchId(), request.startDate(), request.endDate(), request.story(), "custom/output");
        var second = service.create(override, false);
        assertThat(frozenDocumentPath(second.id())).isEqualTo(Path.of(project.rootPath()).resolve("custom/output").toString());
        assertThat(projects.get(projectId).documentPath()).isEqualTo(Path.of(project.rootPath()).resolve("docs/new").toString());
        assertThat(Path.of(expected)).doesNotExist();
    }

    private String frozenDocumentPath(String taskId) {
        return json.readTree(templates.findRun(taskId).orElseThrow().contractJson()).path("documentPath").asText();
    }

    private TemplateTaskService.Request request(String template, String start, String end) {
        return new TemplateTaskService.Request(UUID.randomUUID().toString(), template, io.opencode.loopper.template.TemplateTaskDefinition.VERSION, projectId,
                "local:refs/heads/main", start, end, null);
    }

    @Test void projectsAndHistoryUseStableBoundedSqlPages() {
        var first = service.create(request("CODE_REVIEW", "2026-09-05", "2026-09-11"), false);
        var second = service.create(request("CONTRIBUTION_REPORT", "2026-09-05", "2026-09-11"), false);
        var page = reads.runs(projectId, null, 1);
        var next = reads.runs(projectId, page.nextCursor(), 1);
        assertThat(java.util.Set.of(page.items().getFirst().id(), next.items().getFirst().id())).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(next.nextCursor()).isNull();
        assertThat(reads.runs("missing", null, 50).items()).isEmpty();
        assertThat(reads.projects("TEST-PROJECT", null, 1).items()).extracting(io.opencode.loopper.persistence.TemplateTaskReadMapper.ProjectChoice::id).containsExactly(projectId);
        assertThat(reads.projects("absent", null, 50).items()).isEmpty();
        assertThatThrownBy(() -> reads.runs(projectId, "invalid", 1)).isInstanceOf(BadRequestException.class);
    }
}
