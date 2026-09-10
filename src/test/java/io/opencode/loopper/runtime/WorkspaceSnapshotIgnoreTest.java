package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.verification.VerifierEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkspaceSnapshotIgnoreTest {
    @TempDir Path directory;
    private final SafeProcessRunner runner = new SafeProcessRunner();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @CsvSource({"stage,gitignore", "stage,info", "stage,config", "stage,case", "direct,gitignore",
            "direct,info", "direct,config", "direct,case", "stage,nested", "direct,nested"})
    void followsSourceGitIgnoresWithoutHidingTrackedFiles(String kind, String rules) throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        git(root, "init", "-q");
        Files.writeString(root.resolve("tracked.txt"), "before\n");
        git(root, "add", "tracked.txt");
        String patterns = ".aicoding/\ntracked.txt\n";
        if (rules.equals("nested")) {
            Files.writeString(root.resolve(".gitignore"), "tracked.txt\n");
            Path nested = Files.createDirectories(root.resolve(".aicoding"));
            Files.writeString(nested.resolve(".gitignore"), "*\n!.gitignore\n");
            git(root, "add", ".gitignore", ".aicoding/.gitignore");
        } else if (rules.equals("info")) Files.writeString(root.resolve(".git/info/exclude"), patterns);
        else if (rules.equals("config")) {
            Path excludes = Files.writeString(directory.resolve("source-excludes"), patterns);
            git(root, "config", "core.excludesFile", excludes.toString());
        } else {
            Files.writeString(root.resolve(".gitignore"), rules.equals("case") ? patterns.toUpperCase() : patterns);
            if (rules.equals("case")) git(root, "config", "core.ignoreCase", "true");
            git(root, "add", ".gitignore");
        }
        Path runtime = Files.createDirectories(root.resolve(".aicoding/runtime"));
        Files.writeString(runtime.resolve("session.json"), "before\n");
        assertThat(git(root, "ls-files", "--others", "--exclude-standard")).isEmpty();
        byte[] sourceIndex = Files.readAllBytes(root.resolve(".git/index"));
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(directory.resolve("data"));
        StageWorkspaceBaselineManager stages = new StageWorkspaceBaselineManager(runner, properties);
        DirectWorkspaceBaselineManager direct = new DirectWorkspaceBaselineManager(runner, properties);
        String baseline = kind.equals("stage") ? stages.capture(root, "task", "stage") : direct.capture(root, "task");
        VerifierEngine engine = new VerifierEngine(runner, direct, stages, null);
        VerifierSpec diff = new VerifierSpec("GIT_DIFF", null, null, true, List.of("src/**"), List.of(), false);
        Files.writeString(runtime.resolve("session.json"), "complete\n");
        Files.writeString(runtime.resolve("new-session.json"), "start\n");
        Files.writeString(root.resolve("tracked.txt"), "after\n");
        var outcome = engine.verify(root, baseline, diff, TIMEOUT);
        assertThat(outcome.evidence()).containsEntry("changedPaths", List.of("tracked.txt"))
                .containsEntry("approvalRequiredPaths", List.of("tracked.txt"));
        String patch = engine.previewDiff(root, baseline, "tracked.txt", false, TIMEOUT).patch();
        assertThat(patch).contains("-before", "+after");
        Files.writeString(runtime.resolve("session.json"), "updated again\n");
        assertThat(engine.previewDiff(root, baseline, "tracked.txt", false, TIMEOUT).patch()).isEqualTo(patch);
        assertThat(engine.verify(root, baseline, diff, TIMEOUT).evidence()).isEqualTo(outcome.evidence());
        assertThat(Files.readAllBytes(root.resolve(".git/index"))).isEqualTo(sourceIndex);
    }

    private String git(Path root, String... args) {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessResult result = runner.run(root, command, TIMEOUT);
        assertThat(result.exitCode()).describedAs(result.output()).isZero();
        return result.output();
    }
}
