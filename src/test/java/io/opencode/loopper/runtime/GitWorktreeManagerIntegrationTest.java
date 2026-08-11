package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWorktreeManagerIntegrationTest {
    @TempDir Path temp;

    @Test
    void fetchesLinearRemoteAdvanceWithoutMovingOrDirtyingTheRegisteredBranch() throws Exception {
        Path remote = temp.resolve("remote.git");
        run(temp, "git", "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("seed");
        run(temp, "git", "init", "--initial-branch=main", seed.toString());
        configureIdentity(seed);
        Files.writeString(seed.resolve("README.md"), "initial\n");
        run(seed, "git", "add", "README.md");
        run(seed, "git", "commit", "-m", "initial");
        run(seed, "git", "remote", "add", "origin", remote.toString());
        run(seed, "git", "push", "-u", "origin", "main");

        Path project = temp.resolve("project");
        Path updater = temp.resolve("updater");
        run(temp, "git", "clone", remote.toString(), project.toString());
        run(temp, "git", "clone", remote.toString(), updater.toString());
        configureIdentity(updater);
        Files.writeString(updater.resolve("README.md"), "remote advance\n");
        run(updater, "git", "add", "README.md");
        run(updater, "git", "commit", "-m", "remote advance");
        run(updater, "git", "push", "origin", "main");
        String originalHead = run(project, "git", "rev-parse", "HEAD").strip();
        String remoteHead = run(remote, "git", "rev-parse", "refs/heads/main").strip();

        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("loopper-data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        GitWorktreeManager.Worktree task = manager.create(project, "task-1", "同步远端基线", null);

        assertThat(task.baselineCommit()).isEqualTo(remoteHead);
        assertThat(Files.readString(task.path().resolve("README.md"))).isEqualTo("remote advance\n");
        assertThat(Files.readString(project.resolve("README.md"))).isEqualTo("initial\n");
        assertThat(run(project, "git", "rev-parse", "HEAD").strip()).isEqualTo(originalHead);
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
        assertThat(runAllowFailure(remote, "git", "show-ref", "--verify", "--quiet", "refs/heads/" + task.branch()))
                .contains("exit=1");

        Files.writeString(task.path().resolve("task-only.txt"), "isolated\n");
        assertThat(Files.exists(project.resolve("task-only.txt"))).isFalse();
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
    }

    @Test
    void rejectsManagedWorktreeNestedInsideTheRegisteredProject() throws Exception {
        Path project = temp.resolve("overlap-project");
        run(temp, "git", "init", "--initial-branch=main", project.toString());
        configureIdentity(project);
        Files.writeString(project.resolve("README.md"), "initial\n");
        run(project, "git", "add", "README.md");
        run(project, "git", "commit", "-m", "initial");
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(project.resolve("data"));
        GitWorktreeManager manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);

        assertThatThrownBy(() -> manager.create(project, "task-overlap", "overlap", null))
                .isInstanceOf(TaskFailure.class)
                .hasMessageContaining("outside the registered project root");
        assertThat(run(project, "git", "status", "--porcelain")).isBlank();
    }

    @Test
    void givesLargeWindowsCheckoutsASeparateBoundedTimeoutAndEnablesLongPaths() throws Exception {
        Path project = initializedProject("slow-checkout-project");
        RecordingRunner runner = new RecordingRunner(new ProcessResult(-1, "Updating files: 28%", true, false));
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("slow-checkout-data"));
        GitWorktreeManager manager = new GitWorktreeManager(runner, properties, null);

        assertThatThrownBy(() -> manager.create(project, "task-slow", "Windows large checkout", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("WORKTREE_CREATE_FAILED");
                    assertThat(failure).hasMessageContaining("10-minute safety limit");
                });
        assertThat(runner.worktreeTimeout).isEqualTo(GitWorktreeManager.WORKTREE_CREATE_TIMEOUT);
        assertThat(runner.worktreeCommand).containsSubsequence("git", "-c", "core.longpaths=true", "worktree", "add", "--quiet");
    }

    @Test
    void reportsTheFatalTailInsteadOfCheckoutProgressNoise() throws Exception {
        Path project = initializedProject("failed-checkout-project");
        String output = "Updating files: 28%\r".repeat(300) + "fatal: cannot create directory: Filename too long";
        RecordingRunner runner = new RecordingRunner(new ProcessResult(128, output, false, false));
        LoopperProperties properties = new LoopperProperties();
        properties.setDataDir(temp.resolve("failed-checkout-data"));
        GitWorktreeManager manager = new GitWorktreeManager(runner, properties, null);

        assertThatThrownBy(() -> manager.create(project, "task-failed", "Windows failed checkout", null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("WORKTREE_CREATE_FAILED");
                    assertThat(failure).hasMessageContaining("fatal: cannot create directory: Filename too long");
                });
    }

    private Path initializedProject(String name) throws Exception {
        Path project = temp.resolve(name);
        run(temp, "git", "init", "--initial-branch=main", project.toString());
        configureIdentity(project);
        Files.writeString(project.resolve("README.md"), "initial\n");
        run(project, "git", "add", "README.md");
        run(project, "git", "commit", "-m", "initial");
        return project;
    }

    private static final class RecordingRunner extends SafeProcessRunner {
        private final ProcessResult worktreeResult;
        private List<String> worktreeCommand;
        private Duration worktreeTimeout;

        private RecordingRunner(ProcessResult worktreeResult) {
            this.worktreeResult = worktreeResult;
        }

        @Override
        public ProcessResult run(Path directory, List<String> argv, Duration timeout, Map<String, String> environment) {
            if (argv.contains("worktree") && argv.contains("add")) {
                worktreeCommand = List.copyOf(argv);
                worktreeTimeout = timeout;
                return worktreeResult;
            }
            return super.run(directory, argv, timeout, environment);
        }
    }

    private void configureIdentity(Path repository) throws Exception {
        run(repository, "git", "config", "user.email", "test@example.invalid");
        run(repository, "git", "config", "user.name", "test");
    }

    private String run(Path directory, String... argv) throws Exception {
        return run(directory, false, argv);
    }

    private String runAllowFailure(Path directory, String... argv) throws Exception {
        return run(directory, true, argv);
    }

    private String run(Path directory, boolean allowFailure, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0 && !allowFailure) throw new AssertionError(String.join(" ", argv) + "\n" + output);
        return allowFailure ? "exit=" + exit + "\n" + output : output;
    }
}
