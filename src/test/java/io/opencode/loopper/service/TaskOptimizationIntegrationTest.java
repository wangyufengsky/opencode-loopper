package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.InsightFilter;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h",
        "loopper.startup-recovery.enabled=false"})
class TaskOptimizationIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired ProjectService projects;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired TaskJudgeApprovalService approvals;
    @Autowired TaskPublicationService publication;
    @Autowired InsightReadService insights;
    @Autowired LoopperMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoopperProperties properties;
    @Autowired OpenCodeClient openCode;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TaskWorkspaceCheckpointService checkpoints;
    @TempDir Path temp;

    @BeforeEach void setup() {
        flyway.clean(); flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
        properties.getInternalCandidate().setJudgeDecisionV1Enabled(false);
    }

    @Test void humanAcceptancePreservesBlockedVerdictsAndAllowsLocalPublication() throws Exception {
        TaskRow task = blockedTask("human-local");
        var original = tasks.judges(task.id());
        var view = approvals.view(task.id());
        assertThat(view.available()).isTrue();
        var request = request(view);
        tasks.continueAfterLeaseReconciliation(approvals.approve(task.id(), request));
        assertThat(tasks.get(task.id()).state()).isEqualTo("AWAITING_DECISION");
        assertThat(tasks.latestExecutionCycle(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.judges(task.id())).isEqualTo(original);
        assertThat(approvals.view(task.id()).approved()).isTrue();
        approvals.approve(task.id(), request); // Exact retry is idempotent after checkpoint and lease release.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_judge_approval", Integer.class)).isEqualTo(1);
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(insights.page(InsightFilter.all(), null, 50).items()).singleElement().satisfies(item -> {
            assertThat(item.quality().state()).isEqualTo("PASS");
            assertThat(item.quality().humanApproved()).isTrue();
            assertThat(item.quality().requirementJudgePassed()).isFalse();
        });
        assertThat(publication.commitAndPush(task.id(), "#3032_人工认定后本地提交").state()).isEqualTo("SYNCED_LOCAL");
        assertThat(tasks.get(task.id()).state()).isEqualTo("COMPLETED");
        assertThat(tasks.judges(task.id())).isEqualTo(original);
    }

    @Test void humanAcceptanceAllowsPushToAnExplicitlyConfiguredRemote() throws Exception {
        TaskRow task = blockedTask("human-remote");
        Path bare = temp.resolve("remote.git");
        git(temp, "init", "--bare", bare.toString());
        Path root = Path.of(task.worktreePath());
        git(root, "remote", "add", "origin", bare.toString());
        var view = approvals.view(task.id());
        approvals.approve(task.id(), request(view));
        assertThat(publication.commitAndPush(task.id(), "#3032_人工认定后推送").state()).isEqualTo("PUSHED");
        assertThat(git(temp, "--git-dir=" + bare, "show", "refs/heads/" + task.branchName() + ":feature.txt"))
                .isEqualTo("reviewed change\n");
    }

    @Test void staleBatchRunningWriterAndFailedExecutionCannotBeApproved() throws Exception {
        TaskRow task = blockedTask("approval-guards");
        var view = approvals.view(task.id());
        assertThatThrownBy(() -> approvals.approve(task.id(), new TaskJudgeApprovalService.Request(
                view.taskVersion() - 1, view.cycleId(), view.cycleVersion(), view.reviewBatchId())))
                .isInstanceOf(ConflictException.class);
        var session = mapper.listSessions(task.id()).getFirst();
        jdbc.update("UPDATE execution_session SET state='DISCONNECTED' WHERE id=?", session.id());
        assertThat(approvals.view(task.id()).available()).isFalse();
        assertThatThrownBy(() -> approvals.approve(task.id(), request(view))).isInstanceOf(ConflictException.class);
        jdbc.update("UPDATE execution_session SET state='COMPLETED' WHERE id=?", session.id());
        jdbc.update("UPDATE stage SET state='FAILED' WHERE task_id=?", task.id());
        assertThat(approvals.view(task.id()).available()).isFalse();
        jdbc.update("UPDATE stage SET state='SUCCEEDED' WHERE task_id=?", task.id());
        tasks.retryJudges(task.id());
        assertThatThrownBy(() -> approvals.approve(task.id(), request(view))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_judge_approval", Integer.class)).isZero();
    }

    @Test void interruptedCheckpointHandoffResumesAfterRestartWithoutRepeatingApproval() throws Exception {
        TaskRow task = blockedTask("approval-restart");
        var view = approvals.view(task.id());
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated interruption"))
                .doThrow(new IllegalStateException("workspace temporarily unavailable after restart"))
                .doCallRealMethod().when(checkpoints).freeze(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> approvals.approve(task.id(), request(view))).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.get(task.id()).state()).isEqualTo("AWAITING_DECISION");
        assertThat(approvals.view(task.id()).approved()).isTrue();
        assertThat(approvals.recoverHandoffs()).isEmpty();
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isPresent();
        approvals.recoverHandoffs().forEach(tasks::continueAfterLeaseReconciliation);
        assertThat(mapper.latestTaskWorkspaceCheckpoint(task.id()).orElseThrow().state()).isEqualTo("READY");
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_judge_approval", Integer.class)).isEqualTo(1);
    }

    @Test void cancelledRunningTaskPreservesDirtyFilesAndReturnsToMainInsteadOfStartingFeatureBranch() throws Exception {
        Path root = repository("cancel-main");
        git(root, "switch", "-c", "feature/source");
        TaskRow task = start(root, "cancel-main");
        Files.writeString(root.resolve("README.md"), "tracked work\n");
        Files.writeString(root.resolve("new.txt"), "untracked work\n");
        assertThat(tasks.cancel(task.id()).state()).isEqualTo("CANCELLED");
        assertThat(git(root, "branch", "--show-current").strip()).isEqualTo("main");
        assertThat(git(root, "status", "--porcelain")).isEmpty();
        var snapshot = mapper.latestTaskWorkspaceCheckpoint(task.id()).orElseThrow();
        assertThat(snapshot.state()).isEqualTo("READY");
        assertThat(git(root, "show", snapshot.checkpointRef() + ":README.md")).isEqualTo("tracked work\n");
        assertThat(git(root, "show", snapshot.checkpointRef() + ":new.txt")).isEqualTo("untracked work\n");
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(task.id())).isEmpty();
    }

    @Test void cancellingQueuedTaskDoesNotSwitchTheCurrentHoldersBranch() throws Exception {
        Path root = repository("cancel-queued");
        TaskRow holder = start(root, "cancel-queued");
        TaskRow queued = drafts.confirm(drafts.create(spec(holder.projectId())).id(), "queued");
        tasks.start(queued.id());
        assertThat(tasks.cancel(queued.id()).state()).isEqualTo("CANCELLED");
        assertThat(git(root, "branch", "--show-current").strip()).isEqualTo(holder.branchName());
        assertThat(mapper.findActiveWorkspaceLeaseByHolder(holder.id())).isPresent();
    }

    private TaskRow blockedTask(String name) throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("{\"verdict\":\"BLOCKED\",\"reason\":\"Reference finding\"}");
        TaskRow task = start(repository(name), name);
        Files.writeString(Path.of(task.worktreePath()).resolve("feature.txt"), "reviewed change\n");
        tasks.verify(task.id()); tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        return tasks.get(task.id());
    }
    private TaskJudgeApprovalService.Request request(TaskJudgeApprovalService.View view) {
        return new TaskJudgeApprovalService.Request(view.taskVersion(), view.cycleId(), view.cycleVersion(), view.reviewBatchId());
    }
    private TaskRow start(Path root, String name) {
        var project = projects.create(name, root.toString());
        return tasks.start(drafts.confirm(drafts.create(spec(project.id())).id(), name).id());
    }
    private LoopSpec spec(String projectId) {
        return new LoopSpec("v1", projectId, "Verify README", null,
                List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
    }
    private Path repository(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.writeString(root.resolve("README.md"), "fixture\n");
        git(root, "init", "-b", "main"); git(root, "config", "user.name", "test");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "add", "README.md"); git(root, "commit", "-m", "initial");
        return root;
    }
    private String git(Path root, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero(); return output;
    }
}
