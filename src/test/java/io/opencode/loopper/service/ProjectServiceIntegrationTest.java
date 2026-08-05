package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake",
        "loopper.monitor-delay=1h",
        "loopper.designer-monitor-delay=1h"})
class ProjectServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopperMapper mapper;
    @TempDir Path temp;

    @BeforeEach
    void reset() {
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void cancellingManagementPreservesHistoryAndReregisteringRestoresTheSameProject() throws Exception {
        Path root = Files.createDirectory(temp.resolve("managed-project"));
        ProjectRow project = projects.create("Before", root.toString());
        String now = "2026-08-05T00:00:00Z";
        mapper.insertDraft(new LoopDraftRow("draft-1", project.id(), "goal", "{}", "CONFIRMED", now, now, 0));
        mapper.insertTask(new TaskRow("task-1", project.id(), "draft-1", "Historical task", "SUCCEEDED",
                root.toString(), "DIRECT", null, now, now, 0));

        projects.cancelManagement(project.id());

        assertThat(projects.list()).isEmpty();
        assertThat(mapper.findProject(project.id())).isPresent().get().extracting(ProjectRow::managed).isEqualTo(0);
        assertThat(mapper.findTask("task-1")).isPresent();
        assertThat(mapper.findDraft("draft-1")).isPresent();

        ProjectRow restored = projects.create("After", root.toString());
        assertThat(restored.id()).isEqualTo(project.id());
        assertThat(restored.name()).isEqualTo("After");
        assertThat(restored.managed()).isEqualTo(1);
        assertThat(projects.list()).extracting(ProjectRow::id).containsExactly(project.id());
        assertThat(mapper.findTask("task-1")).isPresent();
    }
}
