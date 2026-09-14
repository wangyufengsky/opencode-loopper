package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class DocumentTemplateAdmissionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired DocumentTemplateService service;
    @Autowired DocumentTemplateReadService reads;
    @Autowired DocumentTemplateMapper mapper;
    @Autowired ProjectService projects;
    @Autowired LoopperProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentTemplateControl controls;
    @Autowired TaskReadService tasks;
    @TempDir Path temporary;
    private String projectId;

    @BeforeEach void prepare() throws Exception {
        flyway.clean(); flyway.migrate(); properties.getOpenCode().setModel("fake/test-model");
        projectId = projects.create("需求项目", Files.createDirectory(temporary.resolve("source")).toString(), "test").id();
    }
    @Test void uploadFreezesFilesWithoutCreatingDesignerTaskQueueOrLeaseAndExactRetryIsStable() {
        var request = request();
        var first = service.create(request, files("金额大于零"));
        var second = service.create(request, files("金额大于零"));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.state()).isEqualTo("DESIGNING");
        assertThat(second.sourceRevision()).isEqualTo(1);
        assertThat(second.requirementRevision()).isZero();
        assertThat(second.taskId()).isNull(); assertThat(second.designerId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease", Integer.class)).isZero();
        var view = reads.overview(first.id());
        assertThat(view.files()).hasSize(1);
        var file = view.files().getFirst();
        assertThat(reads.section(first.id(), file.id(), 0, file.sha256()).content()).contains("金额大于零");
        assertThatThrownBy(() -> service.create(request, files("金额可以为零"))).isInstanceOf(ConflictException.class);
        assertThat(mapper.files(first.id())).hasSize(1);
    }
    @Test void sectionReadChecksRunOwnershipAndFrozenIdentity() {
        var first = service.create(request(), files("审批必须鉴权"));
        var second = service.create(request(), files("支持分页"));
        var file = mapper.files(first.id()).getFirst();
        assertThatThrownBy(() -> reads.section(second.id(), file.id(), 0, file.sha256())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> reads.section(first.id(), file.id(), 0, "changed")).isInstanceOf(ConflictException.class);
    }
    @Test void invalidBatchCreatesNoRunOrPartialSections() {
        assertThatThrownBy(() -> service.create(request(), List.of(files("有效需求").getFirst(),
                new DocumentTemplateStorage.Incoming("old.doc", new byte[]{1})))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_template_run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_template_section", Integer.class)).isZero();
    }
    @Test void reportCompletionCannotHideAnUndisposedExecutionAndArchiveMirrorsBothOwners() {
        var run = service.create(request(), files("金额大于零"));
        jdbc.update("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES(?,?,?,'AWAITING_DECISION','now','now')",
                "linked", projectId, "开发结果");
        jdbc.update("UPDATE document_template_run SET state='COMPLETED',task_id='linked' WHERE id=?", run.id());
        assertThat(reads.overview(run.id()).taskState()).isEqualTo("AWAITING_DECISION");
        var list = tasks.summaries(projectId, List.of(), null, "ALL", null, "newest", null, 100, "TEMPLATE");
        assertThat(list.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(run.id()); assertThat(item.status()).isEqualTo("AWAITING_DECISION");
        });
        var command = new DocumentTemplateControl.Command(UUID.randomUUID().toString(), mapper.find(run.id()).orElseThrow().version());
        assertThatThrownBy(() -> controls.command(run.id(), "archive", command)).isInstanceOf(ConflictException.class);
        assertThat(mapper.find(run.id()).orElseThrow().archived()).isZero();
        jdbc.update("UPDATE task SET state='COMPLETED' WHERE id='linked'");
        jdbc.update("INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,holder_task_id,state,heartbeat_at) VALUES('/owned','fp','DIRECT','linked','HELD','now')");
        assertThatThrownBy(() -> controls.command(run.id(), "archive", command)).isInstanceOf(ConflictException.class);
        assertThat(mapper.find(run.id()).orElseThrow().archived()).isZero();
        jdbc.update("UPDATE workspace_lease SET state='RELEASED',holder_task_id=NULL WHERE canonical_root='/owned'");
        var archived = controls.command(run.id(), "archive", command);
        assertThat(archived.archived()).isEqualTo(1);
        assertThat(controls.command(run.id(), "archive", command).version()).isEqualTo(archived.version());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_archive WHERE task_id='linked'", Integer.class)).isEqualTo(1);
        var restored = controls.command(run.id(), "unarchive", new DocumentTemplateControl.Command(UUID.randomUUID().toString(), archived.version()));
        assertThat(restored.archived()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_archive WHERE task_id='linked'", Integer.class)).isZero();
    }
    @Test void executionRecoveryKeepsOwnerBudgetsAndDoesNotRestartFinishedDocumentAnalysis() {
        var run = service.create(request(), files("金额大于零"));
        jdbc.update("UPDATE document_template_run SET state='WAITING_INPUT',resume_state='EXECUTING' WHERE id=?", run.id());
        jdbc.update("""
            INSERT INTO document_template_model_run(id,run_id,candidate_kind,ordinal,generation,state,input_json,input_sha256,created_at,updated_at)
            VALUES('old-analysis',?,'DOCUMENT_REQUIREMENTS_V1',0,0,'STOPPED','{}','sha','2026-01-01T00:00:00Z','2026-02-01T00:00:00Z')
            """, run.id());
        var current = mapper.find(run.id()).orElseThrow();
        var resumed = controls.command(run.id(), "resume", new DocumentTemplateControl.Command(UUID.randomUUID().toString(), current.version()));
        assertThat(resumed.state()).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_template_model_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM document_template_model_run WHERE id='old-analysis'", String.class)).isEqualTo("STOPPED");
    }
    private DocumentTemplateService.Request request() {
        return new DocumentTemplateService.Request(UUID.randomUUID().toString(), "REQUIREMENT_DEVELOPMENT", "2", projectId, null);
    }
    private static List<DocumentTemplateStorage.Incoming> files(String text) {
        return List.of(new DocumentTemplateStorage.Incoming("requirements.md", ("# 需求\n" + text).getBytes(StandardCharsets.UTF_8)));
    }
}
