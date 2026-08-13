package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageJavaBaselineRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceBaselineManager;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaChangeGateServiceTest {
    @TempDir Path temp;
    private final Map<String, StageJavaBaselineRow> rows = new ConcurrentHashMap<>();
    private LoopperMapper mapper;
    private SafeProcessRunner runner;
    private LoopperProperties properties;
    private JavaChangeGateService gate;
    private DirectWorkspaceBaselineManager directBaselines;

    @BeforeEach
    void setUp() {
        mapper = mock(LoopperMapper.class);
        when(mapper.findStageJavaBaseline(anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(mapper.insertStageJavaBaseline(any())).thenAnswer(call -> {
            StageJavaBaselineRow row = call.getArgument(0);
            rows.putIfAbsent(row.stageId(), row);
            return 1;
        });
        runner = new SafeProcessRunner();
        properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("loopper-data"));
        directBaselines = new DirectWorkspaceBaselineManager(runner, properties);
        gate = new JavaChangeGateService(mapper, runner, directBaselines, new ObjectMapper());
    }

    @Test
    void gitDetectsAddedModifiedAndRenamedProductionJavaButIgnoresTestsBuildAndDeletes() throws Exception {
        Path root = javaFixture(temp.resolve("git-project"));
        run(root, "git", "init");
        run(root, "git", "config", "user.email", "test@example.invalid");
        run(root, "git", "config", "user.name", "test");
        run(root, "git", "add", ".");
        run(root, "git", "commit", "-m", "baseline");
        String baseline = output(root, "git", "rev-parse", "HEAD").trim();
        TaskRow task = task("git-task", root, baseline);

        StageRow changedStage = stage("git-changed", task.id());
        gate.captureIfAbsent(task, changedStage);
        Files.writeString(root.resolve("src/main/java/example/App.java"), "class App { int value = 2; }\n");
        Files.writeString(root.resolve("src/main/java/example/Added.java"), "class Added {}\n");
        Files.writeString(root.resolve("src/test/java/example/AppTest.java"), "class AppTest { int changed = 1; }\n");
        Files.createDirectories(root.resolve("target/generated"));
        Files.writeString(root.resolve("target/generated/Generated.java"), "class Generated {}\n");
        assertThat(gate.changesSinceStageStart(task, changedStage).changedPaths())
                .containsExactlyInAnyOrder("src/main/java/example/Added.java", "src/main/java/example/App.java");

        Files.writeString(root.resolve("src/main/java/example/App.java"), "class App {}\n");
        Files.delete(root.resolve("src/main/java/example/Added.java"));
        StageRow deleteStage = stage("git-delete", task.id());
        gate.captureIfAbsent(task, deleteStage);
        Files.delete(root.resolve("src/main/java/example/App.java"));
        assertThat(gate.changesSinceStageStart(task, deleteStage).changed()).isFalse();

        Files.writeString(root.resolve("src/main/java/example/App.java"), "class App {}\n");
        StageRow renameStage = stage("git-rename", task.id());
        gate.captureIfAbsent(task, renameStage);
        Files.move(root.resolve("src/main/java/example/App.java"),
                root.resolve("src/main/java/example/RenamedApp.java"));
        assertThat(gate.changesSinceStageStart(task, renameStage).changedPaths())
                .containsExactly("src/main/java/example/RenamedApp.java");
    }

    @Test
    void directWorkspaceUsesTheSameAddModifyRenameAndDeleteSemantics() throws Exception {
        Path root = javaFixture(temp.resolve("direct-project"));
        String baseline = directBaselines.capture(root, "direct-task");
        TaskRow task = task("direct-task", root, baseline);

        StageRow add = stage("direct-add", task.id());
        gate.captureIfAbsent(task, add);
        Path added = root.resolve("src/main/java/example/Added.java");
        Files.writeString(added, "class Added {}\n");
        assertThat(gate.changesSinceStageStart(task, add).changedPaths())
                .containsExactly("src/main/java/example/Added.java");

        StageRow modify = stage("direct-modify", task.id());
        gate.captureIfAbsent(task, modify);
        Files.writeString(added, "class Added { int value = 2; }\n");
        assertThat(gate.changesSinceStageStart(task, modify).changedPaths())
                .containsExactly("src/main/java/example/Added.java");

        StageRow rename = stage("direct-rename", task.id());
        gate.captureIfAbsent(task, rename);
        Path renamed = root.resolve("src/main/java/example/Renamed.java");
        Files.move(added, renamed);
        assertThat(gate.changesSinceStageStart(task, rename).changedPaths())
                .containsExactly("src/main/java/example/Renamed.java");

        StageRow delete = stage("direct-delete", task.id());
        gate.captureIfAbsent(task, delete);
        Files.delete(renamed);
        assertThat(gate.changesSinceStageStart(task, delete).changed()).isFalse();
    }

    private Path javaFixture(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/example"));
        Files.createDirectories(root.resolve("src/test/java/example"));
        Files.writeString(root.resolve("src/main/java/example/App.java"), "class App {}\n");
        Files.writeString(root.resolve("src/test/java/example/AppTest.java"), "class AppTest {}\n");
        return root;
    }

    private TaskRow task(String id, Path root, String baseline) {
        return new TaskRow(id, "project", "draft", "task", "RUNNING", root.toString(),
                null, null, baseline, "now", "now", 0);
    }

    private StageRow stage(String id, String taskId) {
        return new StageRow(id, taskId, 1, "stage", "[]", "[]", "[]", "[]",
                "RUNNING", "now", "now", 0);
    }

    private void run(Path root, String... command) throws Exception {
        output(root, command);
    }

    private String output(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(result);
        return result;
    }
}
