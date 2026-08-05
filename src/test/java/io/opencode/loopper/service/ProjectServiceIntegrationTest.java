package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
        ProjectRow project = projects.create("Before", root.toString(), "Original description");
        String now = "2026-08-05T00:00:00Z";
        mapper.insertDraft(new LoopDraftRow("draft-1", project.id(), "goal", "{}", "CONFIRMED", now, now, 0));
        mapper.insertTask(new TaskRow("task-1", project.id(), "draft-1", "Historical task", "SUCCEEDED",
                root.toString(), "DIRECT", null, now, now, 0));

        projects.cancelManagement(project.id());

        assertThat(projects.list()).isEmpty();
        assertThat(mapper.findProject(project.id())).isPresent().get().extracting(ProjectRow::managed).isEqualTo(0);
        assertThat(mapper.findTask("task-1")).isPresent();
        assertThat(mapper.findDraft("draft-1")).isPresent();

        ProjectRow restored = projects.create("After", root.toString(), "Restored description");
        assertThat(restored.id()).isEqualTo(project.id());
        assertThat(restored.name()).isEqualTo("After");
        assertThat(restored.description()).isEqualTo("Restored description");
        assertThat(restored.managed()).isEqualTo(1);
        assertThat(projects.list()).extracting(ProjectRow::id).containsExactly(project.id());
        assertThat(mapper.findTask("task-1")).isPresent();
    }

    @Test
    void reportsTheActualGitExecutionModeAndUnavailableRoots() throws Exception {
        Path gitRoot = Files.createDirectory(temp.resolve("git-project"));
        git(gitRoot, "init");
        git(gitRoot, "config", "user.email", "loopper@example.test");
        git(gitRoot, "config", "user.name", "Loopper Test");
        Files.writeString(gitRoot.resolve("README.md"), "fixture\n");
        git(gitRoot, "add", "README.md");
        git(gitRoot, "commit", "-m", "fixture");
        ProjectRow gitProject = projects.create("Git", gitRoot.toString(), "Uses an isolated worktree");

        var gitInspection = projects.inspect(gitProject);

        assertThat(gitInspection.pathAvailable()).isTrue();
        assertThat(gitInspection.isolatedWorktree()).isTrue();
        assertThat(gitInspection.branch()).isNotBlank();

        Path plainRoot = Files.createDirectory(temp.resolve("plain-project"));
        ProjectRow plainProject = projects.create("Plain", plainRoot.toString(), "Uses direct mode");
        var directInspection = projects.inspect(plainProject);
        assertThat(directInspection.pathAvailable()).isTrue();
        assertThat(directInspection.isolatedWorktree()).isFalse();
        assertThat(directInspection.branch()).isNull();

        Files.delete(plainRoot);
        assertThat(projects.inspect(plainProject).pathAvailable()).isFalse();
    }

    private static void git(Path root, String... arguments) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
