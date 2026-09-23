package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.SourceTemplateMapper;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"})
class SourceTemplateAdmissionIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired ProjectService projects;
    @Autowired SourceTemplateService service;
    @Autowired SourceTemplateAdmission admission;
    @Autowired SourceTemplatePreparation preparation;
    @Autowired SourceTemplateReadService reads;
    @Autowired SourceTemplateMapper mapper;
    @Autowired LoopperProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskReadService tasks;
    @Autowired TaskService executionTasks;
    @Autowired SourceTemplateControl controls;
    @TempDir Path temporary;
    private String projectId;
    private Path root;
    @BeforeEach void setup() throws Exception {
        flyway.clean(); flyway.migrate(); properties.getOpenCode().setModel("fake/test-model");
        root = Files.createDirectory(temporary.resolve("source")).toRealPath();
        Files.writeString(root.resolve("Service.java"), "class Service { int value() { return 3; } }");
        projectId = projects.create("源码项目", root.toString(), "source fixture").id();
    }
    @Test void confirmationAndPreviewHaveNoExecutionSideEffectsAndReplaySurvivesDefaultsChanging() {
        var request = request("Service.java");
        assertThat(service.preview(request).targetCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_template_run", Integer.class)).isZero();
        var row = service.create(request);
        assertThat(row.state()).isEqualTo("PENDING_START");
        properties.getOpenCode().setModel("changed/new-model");
        assertThat(service.create(request)).isEqualTo(row);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM designer_session", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_lease", Integer.class)).isZero();
        var changed = new SourceTemplateRequests.Create(request.requestKey(), request.templateId(), request.templateVersion(),
                projectId, ".", null, null, "");
        assertThatThrownBy(() -> service.create(changed)).isInstanceOf(ConflictException.class);
    }
    @Test void startIsIdempotentAndFrozenBytesIgnoreLaterSourceEdits() throws Exception {
        var row = service.create(request("."));
        var command = new SourceTemplateRequests.Command(UUID.randomUUID().toString(), row.version());
        admission.start(row.id(), command); admission.start(row.id(), command);
        var frozen = preparation.freeze(row.id());
        assertThat(reads.overview(row.id()).snapshot().ready()).isTrue();
        Files.writeString(root.resolve("Service.java"), "changed after capture");
        assertThat(preparation.freeze(row.id())).isEqualTo(frozen);
        assertThat(reads.source(row.id(), "Service.java", 1, 100).content()).contains("return 3");
        assertThatThrownBy(() -> reads.source(row.id(), "../Service.java", 1, 100)).isInstanceOf(NotFoundException.class);
        assertThat(reads.coverage(row.id(), null, 10).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM state_transition_event WHERE machine_type='SOURCE_TEMPLATE_RUN'", Integer.class)).isEqualTo(2);
    }
    @Test void activeWriterPreventsCaptureAndEmptyScopeCannotClaimSuccessfulTesting() throws Exception {
        var row = service.create(request("."));
        admission.start(row.id(), new SourceTemplateRequests.Command(UUID.randomUUID().toString(), row.version()));
        jdbc.update("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('writer',?,'writer','RUNNING','now','now')", projectId);
        jdbc.update("""
            INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,holder_task_id,state,heartbeat_at)
            VALUES(?,'fp','DIRECT','writer','HELD','now')
            """, root.toString());
        assertThatThrownBy(() -> preparation.freeze(row.id())).isInstanceOf(ConflictException.class).hasMessageContaining("活动写入");
        assertThat(mapper.files(row.id())).isEmpty();
        jdbc.update("UPDATE workspace_lease SET state='RELEASED',holder_task_id=NULL");
        Files.delete(root.resolve("Service.java"));
        assertThatThrownBy(() -> preparation.freeze(row.id())).isInstanceOf(BadRequestException.class).hasMessageContaining("没有可处理");
    }
    private SourceTemplateRequests.Create request(String path) {
        return new SourceTemplateRequests.Create(UUID.randomUUID().toString(), "DETAILED_DESIGN_WRITING",
                SourceTemplateDefinition.DETAILED_DESIGN_WRITING.version(), projectId, path, null, null, "");
    }
    @Test void historyFiltersAndArchiveRetainOneRowAndLinkedTaskDisposition() {
        var row = service.create(request("."));
        var pending = tasks.summaries(projectId, java.util.List.of("PENDING_START"), null, "ACTIVE", null, "newest", null, 1, "TEMPLATE");
        assertThat(pending.items()).singleElement().satisfies(item -> assertThat(item.sourceRunId()).isEqualTo(row.id()));
        jdbc.update("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('linked',?,'test result','AWAITING_DECISION','now','now')", projectId);
        jdbc.update("UPDATE source_template_run SET state='COMPLETED',task_id='linked' WHERE id=?", row.id());
        var list = tasks.summaries(projectId, java.util.List.of(), null, "ALL", null, "newest", null, 100, "TEMPLATE");
        assertThat(list.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceRunId()).isEqualTo(row.id()); assertThat(item.linkedTaskId()).isEqualTo("linked");
            assertThat(item.status()).isEqualTo("AWAITING_DECISION"); assertThat(item.documentRunId()).isNull();
        });
        assertThat(list.facets()).containsEntry("TOTAL", 1L);
        var command = new SourceTemplateControl.Command(UUID.randomUUID().toString(), row.version(), null);
        assertThatThrownBy(() -> controls.command(row.id(), "archive", command)).isInstanceOf(BadRequestException.class);
        jdbc.update("UPDATE task SET state='COMPLETED' WHERE id='linked'");
        jdbc.update("INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,holder_task_id,state,heartbeat_at) VALUES('/source-owned','fp','DIRECT','linked','HELD','now')");
        assertThatThrownBy(() -> controls.command(row.id(), "archive", command)).isInstanceOf(BadRequestException.class);
        jdbc.update("UPDATE workspace_lease SET state='RELEASED',holder_task_id=NULL WHERE canonical_root='/source-owned'");
        var archived = controls.command(row.id(), "archive", command);
        assertThat(controls.command(row.id(), "archive", command).version()).isEqualTo(archived.version());
        assertThat(tasks.summaries(projectId, java.util.List.of(), null, "ACTIVE", null, "newest", null, 100, "TEMPLATE").items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_archive WHERE task_id='linked'", Integer.class)).isEqualTo(1);
        var restored = controls.command(row.id(), "unarchive", new SourceTemplateControl.Command(UUID.randomUUID().toString(), archived.version(), null));
        assertThat(restored.archived()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_archive WHERE task_id='linked'", Integer.class)).isZero();
        jdbc.update("UPDATE source_template_run SET state='REPORTING' WHERE id=?", row.id());
        assertThatThrownBy(() -> executionTasks.archive("linked")).isInstanceOf(BadRequestException.class).hasMessageContaining("源码模板尚未收束");
        jdbc.update("UPDATE source_template_run SET state='COMPLETED' WHERE id=?", row.id());
        executionTasks.archive("linked");
        assertThat(admission.require(row.id()).archived()).isEqualTo(1);
        assertThat(tasks.summaries(projectId, java.util.List.of(), null, "ACTIVE", null, "newest", null, 100, "TEMPLATE").items()).isEmpty();
        executionTasks.restoreArchive("linked");
        assertThat(admission.require(row.id()).archived()).isZero();
    }
}
