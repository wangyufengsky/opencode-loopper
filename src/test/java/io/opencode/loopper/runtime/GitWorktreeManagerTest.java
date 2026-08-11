package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitWorktreeManagerTest {
    private final GitWorktreeManager manager = new GitWorktreeManager(null, null, null);

    @Test
    void repairsLockSuffixExposedOnlyAfterUtf8Truncation() throws Exception {
        String branch = manager.branchNameForTask("a".repeat(175) + ".lock" + "suffix", "task-1", 1);

        assertThat(branch).endsWith("-lock").doesNotEndWith(".lock");
        assertValidBranch(branch);
    }

    @Test
    void preservesOccurrenceSuffixAndByteLimitForLongMultibyteNames() throws Exception {
        String branch = manager.branchNameForTask("超长任务名称".repeat(40) + ".lock-tail", "task-2", 2);
        String leaf = branch.substring("loopper/".length());

        assertThat(branch).endsWith("(第2次)");
        assertThat(leaf.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(180);
        assertValidBranch(branch);
    }

    private void assertValidBranch(String branch) throws Exception {
        Process process = new ProcessBuilder(List.of("git", "check-ref-format", "--branch", branch)).start();
        assertThat(process.waitFor()).as(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
    }
}
