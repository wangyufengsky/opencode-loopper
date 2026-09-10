package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitSourceBranchRestorerTest {
    @TempDir Path root;
    private GitWorktreeManager manager;
    private static final String TASK = "loopper/rework-fixture";

    @ParameterizedTest @ValueSource(strings = {"main", "master", "release"})
    void cancelledCheckoutAlreadyOnDevelopReturnsToDefaultWithoutChangingBranchHistory(String target) throws Exception {
        setup(target);
        String develop = git("rev-parse", "develop");
        String task = git("rev-parse", TASK);
        manager.restoreMainBranch(root, TASK, "develop");
        assertThat(git("branch", "--show-current")).isEqualTo(target);
        assertThat(git("rev-parse", "develop")).isEqualTo(develop);
        assertThat(git("rev-parse", TASK)).isEqualTo(task);
        assertThat(git("show", "develop:source-only.txt")).isEqualTo("saved source work");
        manager.restoreMainBranch(root, TASK, "develop");
        assertThat(git("branch", "--show-current")).isEqualTo(target);
    }

    @Test void normalCompletionStillReturnsToDevelop() throws Exception {
        setup("main"); git("switch", TASK);
        manager.restoreSourceBranch(root, TASK, "develop");
        assertThat(git("branch", "--show-current")).isEqualTo("develop");
        manager.restoreSourceBranch(root, TASK, "develop");
    }

    @Test void cancellationWithoutRecordedSourceDoesNotGuessDevelop() throws Exception {
        setup("main");
        assertFailure("TASK_SOURCE_BRANCH_RESTORE_MISMATCH", () -> manager.restoreMainBranch(root, TASK));
        assertThat(git("branch", "--show-current")).isEqualTo("develop");
    }

    @Test void dirtySourceIsNotSwitchedOrCleaned() throws Exception {
        setup("main"); Files.writeString(root.resolve("user-draft.txt"), "uncommitted\n");
        assertFailure("TASK_SOURCE_BRANCH_RESTORE_DIRTY", () -> manager.restoreMainBranch(root, TASK, "develop"));
        assertThat(Files.readString(root.resolve("user-draft.txt"))).isEqualTo("uncommitted\n");
        assertThat(git("branch", "--show-current")).isEqualTo("develop");
    }

    @ParameterizedTest @ValueSource(strings = {"another-user-branch", "DETACHED"})
    void unrelatedOrDetachedCheckoutIsNotAccepted(String branch) throws Exception {
        setup("main");
        if (branch.equals("DETACHED")) git("switch", "--detach"); else git("switch", "-c", branch);
        String before = git("rev-parse", "HEAD");
        assertFailure("TASK_SOURCE_BRANCH_RESTORE_MISMATCH", () -> manager.restoreMainBranch(root, TASK, "develop"));
        assertThat(git("rev-parse", "HEAD")).isEqualTo(before);
        assertThat(git("branch", "--show-current")).isEqualTo(branch.equals("DETACHED") ? "" : branch);
    }

    private void setup(String target) throws Exception {
        git("init", "--initial-branch=" + target);
        Files.writeString(root.resolve("README.md"), "fixture\n"); git("add", "."); commit();
        if (target.equals("release")) {
            git("update-ref", "refs/remotes/origin/release", "HEAD");
            git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/release");
        }
        git("switch", "-c", "develop");
        Files.writeString(root.resolve("source-only.txt"), "saved source work\n"); git("add", "."); commit();
        git("switch", "-c", TASK); git("switch", "develop");
        var properties = new LoopperProperties();
        manager = new GitWorktreeManager(new SafeProcessRunner(), properties, null);
    }
    private void commit() {
        git("-c", "user.name=Loopper Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture");
    }
    private static void assertFailure(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
    private String git(String... arguments) {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var result = new SafeProcessRunner().run(root, command, Duration.ofSeconds(5));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return result.output().strip();
    }
}
