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
        var binding = mapper.findTaskStoryBinding(task.id()).orElseThrow();
        assertThat(binding.systemCode()).isEqualTo("001");
        assertThat(binding.storyCode()).isEqualTo("0002");
        assertThat(templates.findRun(task.id()).orElseThrow().contractJson()).contains("test-model", "CONTRIBUTION_SCORE_V1", "Asia/Shanghai",
                "REPORT_LAYOUT_V2", "CODE_REVIEW_V2", "CONTRIBUTION_REPORT_V2", "PERSONAL_CONTRIBUTION_V2", "sha256");
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

    private TemplateTaskService.Request request(String template, String start, String end) {
        return new TemplateTaskService.Request(UUID.randomUUID().toString(), template, io.opencode.loopper.template.TemplateTaskDefinition.VERSION, projectId,
                "local:refs/heads/main", start, end, new StoryBindingConfiguration(true, "001", "0002"));
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
