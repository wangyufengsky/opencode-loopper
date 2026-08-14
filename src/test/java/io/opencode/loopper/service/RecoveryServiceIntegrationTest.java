package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class RecoveryServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private RecoveryService recoveries;
    @Autowired private io.opencode.loopper.persistence.LoopperMapper mapper;
    @Autowired private OpenCodeClient openCode;
    @Autowired private DataSource dataSource;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
    }

    @Test
    void onlyTerminalParentsCreateAStageDerivedLineage() throws Exception {
        ProjectRow project = projects.create("recovery-parent", gitProject());
        TaskRow parent = drafts.confirm(drafts.create(twoStageSpec(project.id())).id(), "parent failure");
        assertThatThrownBy(() -> recoveries.create(parent.id(), RecoveryMode.FROM_FAILED_STAGE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已失败或已取消");

        StageRow first = tasks.stages(parent.id()).getFirst();
        mapper.updateStageState(new StageRow(first.id(), first.taskId(), first.ordinal(), first.objective(), first.allowedPathsJson(),
                first.forbiddenPathsJson(), first.deliverablesJson(), first.verifiersJson(), "SUCCEEDED", first.createdAt(), first.updatedAt(), first.version()));
        tasks.cancel(parent.id());

        FeatureContracts.RecoveryDto created = recoveries.create(parent.id(), RecoveryMode.FROM_FAILED_STAGE);

        assertThat(created.parentTaskId()).isEqualTo(parent.id());
        assertThat(created.mode()).isEqualTo(RecoveryMode.FROM_FAILED_STAGE);
        assertThat(created.parentStageId()).isEqualTo(tasks.stages(parent.id()).get(1).id());
        assertThat(created.writableSession()).isTrue();
        assertThat(tasks.stages(created.taskId())).extracting(StageRow::objective).containsExactly("验证第二阶段");
        assertThat(recoveries.list(parent.id())).containsExactly(created);
    }

    @Test
    void verifyOnlyRunsNativeVerificationWithoutCreatingAnExecutionSession() throws Exception {
        ProjectRow project = projects.create("verify-only", gitProject());
        TaskRow parent = drafts.confirm(drafts.create(singleStageSpec(project.id(), "README.md")).id(), "cancelled parent");
        tasks.cancel(parent.id());

        FeatureContracts.RecoveryDto created = recoveries.create(parent.id(), RecoveryMode.VERIFY_ONLY);
        TaskRow afterStart = tasks.start(created.taskId());

        assertThat(created.writableSession()).isFalse();
        assertThat(mapper.listSessions(created.taskId())).isEmpty();
        assertThat(tasks.attempts(created.taskId())).hasSize(1);
        assertThat(afterStart.state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(created.taskId())).allSatisfy(judge -> assertThat(judge.externalSessionId()).isNotBlank());

        tasks.pollJudges(created.taskId());
        assertThat(tasks.get(created.taskId()).state()).isEqualTo("SUCCEEDED");
        assertThat(mapper.listSessions(created.taskId())).isEmpty();
    }

    @Test
    void verifyOnlyFailureNeverFallsBackToAWritableRepairSession() throws Exception {
        ProjectRow project = projects.create("verify-only-failure", gitProject());
        TaskRow parent = drafts.confirm(drafts.create(failingVerifyOnlySpec(project.id())).id(), "cancelled parent");
        tasks.cancel(parent.id());
        FeatureContracts.RecoveryDto created = recoveries.create(parent.id(), RecoveryMode.VERIFY_ONLY);

        TaskRow failed = tasks.start(created.taskId());

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(mapper.listSessions(created.taskId())).isEmpty();
        assertThat(tasks.errors(created.taskId())).anyMatch(error -> "VERIFY_ONLY_VERIFICATION_FAILED".equals(error.code()));
    }

    @Test
    void verifyOnlyGitDiffWaitsWhenTheRegisteredCheckoutChangesBeforeExecution() throws Exception {
        Path root = Path.of(gitProject()).toRealPath();
        ProjectRow project = projects.create("verify-only-task-diff", root.toString());
        LoopSpec.VerifierSpec diff = new LoopSpec.VerifierSpec(
                "GIT_DIFF", null, null, true, List.of("proof.txt"), List.of(), true);
        LoopSpec.VerifierSpec content = new LoopSpec.VerifierSpec(
                "FILE_CONTENT", null, "proof.txt", null, List.of(), List.of(), false, null,
                null, null, null, null, null, "EXACT", "proof", null, null, null, List.of());
        LoopSpec parentSpec = new LoopSpec("v1", project.id(), "验证父任务基线差异", null,
                List.of(new LoopSpec.StageSpec("验证 proof.txt", List.of("proof.txt"), List.of(),
                        List.of("proof.txt"), List.of(diff, content))), null, null, null, null);
        TaskRow parent = drafts.confirm(drafts.create(parentSpec).id(), "verify-only task baseline parent");
        tasks.cancel(parent.id());

        FeatureContracts.RecoveryDto created = recoveries.create(parent.id(), RecoveryMode.VERIFY_ONLY);
        Files.writeString(root.resolve("proof.txt"), "proof");
        TaskRow afterStart = tasks.start(created.taskId());
        StageRow childStage = tasks.stages(created.taskId()).getFirst();

        assertThat(afterStart.state()).isEqualTo("WAITING_INPUT");
        assertThat(mapper.findStageWorkspaceBaseline(childStage.id())).isEmpty();
        assertThat(mapper.listSessions(created.taskId())).isEmpty();
        assertThat(tasks.attempts(created.taskId())).isEmpty();
        assertThat(tasks.errors(created.taskId())).anyMatch(error ->
                "SOURCE_BRANCH_WORKSPACE_DIRTY".equals(error.code()));
    }

    @Test
    void directWorkspaceReplacementFailsBeforeCreatingAnyRecoveryRows() throws Exception {
        Path directRoot = Files.createDirectory(temp.resolve("direct-root"));
        Files.writeString(directRoot.resolve("README.md"), "original identity");
        ProjectRow project = projects.create("direct-fingerprint", directRoot.toString());
        TaskRow parent = drafts.confirm(drafts.create(singleStageSpec(project.id(), "README.md")).id(), "direct parent");
        tasks.start(parent.id());
        tasks.cancel(parent.id());
        long draftsBefore = countRows("loop_draft");
        long tasksBefore = countRows("task");

        Files.move(directRoot, temp.resolve("replaced-direct-root"));
        Files.createDirectory(directRoot);
        Files.writeString(directRoot.resolve("README.md"), "replacement identity");

        assertThatThrownBy(() -> recoveries.create(parent.id(), RecoveryMode.FROM_FAILED_STAGE))
                .isInstanceOfSatisfying(ConflictException.class, conflict ->
                        assertThat(conflict.code()).isEqualTo("RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH"));
        assertThat(countRows("loop_draft")).isEqualTo(draftsBefore);
        assertThat(countRows("task")).isEqualTo(tasksBefore);
        assertThat(mapper.childTasks(parent.id())).isEmpty();
    }

    @Test
    void reworkCreatesANewBranchFromParentBaseline() throws Exception {
        Path root = Path.of(gitProject());
        ProjectRow project = projects.create("rework-parent", root.toString());
        TaskRow ready = drafts.confirm(drafts.create(twoStageSpec(project.id())).id(), "parent implementation");
        TaskRow running = tasks.start(ready.id());
        var session = mapper.activeSessions(running.id()).getFirst();
        assertThat(mapper.updateSessionState(new io.opencode.loopper.persistence.ExecutionSessionRow(
                session.id(), session.taskId(), session.stageId(), session.attemptId(), session.externalSessionId(),
                "COMPLETED", session.createdAt(), Instant.now().toString(), session.version(), session.todoCapability())))
                .isEqualTo(1);
        TaskRow succeeded = new TaskRow(running.id(), running.projectId(), running.loopDraftId(), running.title(), "SUCCEEDED",
                running.worktreePath(), running.branchName(), running.sourceBranch(), running.baselineCommit(),
                running.createdAt(), Instant.now().toString(), running.version());
        assertThat(mapper.updateTaskState(succeeded)).isEqualTo(1);
        String parentBaseline = running.baselineCommit();
        Files.writeString(root.resolve("later.txt"), "source advanced after parent\n");
        run(root, "git", "add", "later.txt");
        run(root, "git", "commit", "-m", "advance source branch");
        String currentSourceHead = run(root, "git", "rev-parse", "HEAD").strip();
        assertThat(currentSourceHead).isNotEqualTo(parentBaseline);

        FeatureContracts.RecoveryDto created = recoveries.create(running.id(), RecoveryMode.REWORK_ALL_STAGES);
        TaskRow pendingChild = tasks.get(created.taskId());

        assertThat(created.mode()).isEqualTo(RecoveryMode.REWORK_ALL_STAGES);
        assertThat(created.parentStageId()).isNull();
        assertThat(created.workspaceFingerprint()).isEqualTo(parentBaseline);
        assertThat(pendingChild.state()).isEqualTo("PENDING_START");
        assertThat(pendingChild.branchName()).isNull();
        assertThat(mapper.findTaskQueue(pendingChild.id())).isEmpty();

        // A terminal task keeps the registered-checkout writer lease until its
        // clean branch has reached a durable publication/cleanup boundary.
        tasks.releaseWorkspaceAfterTaskCommit(running.id());
        assertThat(tasks.get(created.taskId()).state()).isEqualTo("PENDING_START");
        TaskRow child = tasks.start(created.taskId());

        assertThat(child.state()).isEqualTo("RUNNING");
        assertThat(child.branchName()).startsWith("loopper/").isNotEqualTo(running.branchName());
        assertThat(child.baselineCommit()).isEqualTo(parentBaseline);
        assertThat(run(Path.of(child.worktreePath()), "git", "rev-parse", "HEAD").strip()).isEqualTo(parentBaseline);
        assertThat(Files.exists(Path.of(child.worktreePath()).resolve("later.txt"))).isFalse();
        assertThat(tasks.stages(child.id())).extracting(StageRow::objective)
                .containsExactly("验证第一阶段", "验证第二阶段");
        assertThat(tasks.get(running.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(recoveries.list(running.id())).containsExactly(created);
    }

    private LoopSpec twoStageSpec(String projectId) {
        return new LoopSpec("v1", projectId, "恢复两个阶段", null, List.of(
                stage("验证第一阶段", "README.md"), stage("验证第二阶段", "README.md")), null, null, null, null);
    }

    private LoopSpec singleStageSpec(String projectId, String requiredPath) {
        return new LoopSpec("v1", projectId, "只读验证", null, List.of(stage("仅验证现有证据", requiredPath)), null, null, null, null);
    }

    private LoopSpec failingVerifyOnlySpec(String projectId) {
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("验证失败但不能写入修复", null, null, null,
                List.of(new LoopSpec.VerifierSpec("FILE_NOT_EXISTS", null, "README.md", null, null, null, null)));
        return new LoopSpec("v1", projectId, "只读验证失败", null, List.of(stage), null, null, null, null);
    }

    private LoopSpec.StageSpec stage(String objective, String requiredPath) {
        return new LoopSpec.StageSpec(objective, null, null, null,
                List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, requiredPath, null, null, null, null)));
    }

    private String gitProject() throws Exception {
        Path root = Files.createDirectory(temp.resolve("git-" + System.nanoTime()));
        Files.writeString(root.resolve("README.md"), "fixture");
        run(root, "git", "init");
        run(root, "git", "config", "user.email", "test@example.invalid");
        run(root, "git", "config", "user.name", "test");
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "initial");
        return root.toString();
    }

    private String run(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }

    private long countRows(String table) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.getLong(1);
        }
    }
}
