package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class TaskPublicationServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private TaskPublicationService publication;
    @Autowired private LoopperMapper mapper;
    @Autowired private OpenCodeClient openCode;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
    }

    @Test
    void aiSuggestionCommitPushAndGitLabMergeRequestDraftFollowTheSuccessFlow() throws Exception {
        Repository fixture = repositoryWithRemote();
        ProjectRow project = projects.create("publish-fixture", fixture.project().toString());
        TaskRow task = succeededTask(project);
        TaskRow waiting = readyTask(project);
        assertThat(waiting.state()).isEqualTo("QUEUED");
        Files.writeString(Path.of(task.worktreePath()).resolve("feature.txt"), "verified change\n");
        ((FakeOpenCodeClient) openCode).setJudgeOutput("COMMIT", "完善任务提交与合并请求流程");

        assertThat(publication.status(task.id()).state()).isEqualTo("READY");
        assertThat(publication.generateCommitMessage(task.id()))
                .isEqualTo(new TaskPublicationService.CommitSuggestion("完善任务提交与合并请求流程", true));

        TaskPublicationService.PublicationStatus pushed = publication.commitAndPush(
                task.id(), "#3032_完善任务提交与合并请求流程");

        assertThat(pushed.state()).isEqualTo("PUSHED");
        assertThat(pushed.commitMessage()).isEqualTo("#3032_完善任务提交与合并请求流程");
        assertThat(run(fixture.remote(), "git", "rev-parse", "refs/heads/" + task.branchName()).strip())
                .isEqualTo(pushed.commitSha());
        assertThat(tasks.get(waiting.id()).state()).isEqualTo("READY");
        assertThat(run(fixture.project(), "git", "branch", "--show-current").strip())
                .isEqualTo(tasks.get(waiting.id()).branchName());
        assertThat(run(fixture.project(), "git", "rev-parse", "refs/heads/" + task.branchName()).strip())
                .isEqualTo(pushed.commitSha());

        run(Path.of(task.worktreePath()), "git", "remote", "set-url", "origin", "git@gitlab.example:group/project.git");
        TaskPublicationService.MergeRequestDraft mergeRequest = publication.mergeRequestDraft(
                task.id(), "main", pushed.commitMessage(), "任务已通过 Loopper 验收");

        assertThat(mergeRequest.provider()).isEqualTo("GITLAB");
        assertThat(mergeRequest.creationUrl())
                .startsWith("https://gitlab.example/group/project/-/merge_requests/new?")
                .contains("merge_request%5Bsource_branch%5D=loopper%2F")
                .contains("merge_request%5Btarget_branch%5D=main")
                .contains("%233032_%E5%AE%8C%E5%96%84");
    }

    @Test
    void malformedCommitMessageIsRejectedBeforeGitMutation() throws Exception {
        Repository fixture = repositoryWithRemote();
        ProjectRow project = projects.create("invalid-message", fixture.project().toString());
        TaskRow task = succeededTask(project);
        Files.writeString(Path.of(task.worktreePath()).resolve("feature.txt"), "verified change\n");

        assertThatThrownBy(() -> publication.commitAndPush(task.id(), "#123_缺少一位数字"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("4位数字");
        assertThat(run(Path.of(task.worktreePath()), "git", "status", "--porcelain"))
                .contains("feature.txt");
    }

    @Test
    void directExecutionTaskCannotPublish() throws Exception {
        Path root = temp.resolve("plain-project");
        Files.createDirectories(root);
        Files.writeString(root.resolve("README.md"), "plain\n");
        ProjectRow project = projects.create("plain", root.toString());
        TaskRow task = succeededTask(project);

        TaskPublicationService.PublicationStatus status = publication.status(task.id());

        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.reason()).contains("直接执行");
        assertThatThrownBy(() -> publication.commitAndPush(task.id(), "#3032_不应提交"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("直接执行");
    }

    @Test
    void repositoryWithoutRemoteKeepsCommitOnTaskBranchAndAdmitsNextTaskFromSourceBranch() throws Exception {
        Path projectRoot = repositoryWithoutRemote();
        ProjectRow project = projects.create("local-sync-clean", projectRoot.toString());
        TaskRow task = succeededTask(project);
        TaskRow waiting = readyTask(project);
        assertThat(waiting.state()).isEqualTo("QUEUED");
        Files.writeString(Path.of(task.worktreePath()).resolve("feature.txt"), "verified local change\n");

        TaskPublicationService.PublicationStatus ready = publication.status(task.id());
        assertThat(ready.state()).isEqualTo("READY");
        assertThat(ready.remoteName()).isNull();

        TaskPublicationService.PublicationStatus synced = publication.commitAndPush(
                task.id(), "#3032_同步无远端任务变更");

        assertThat(synced.state()).isEqualTo("SYNCED_LOCAL");
        assertThat(run(projectRoot, "git", "rev-parse", "refs/heads/" + task.branchName()).strip())
                .isEqualTo(synced.commitSha());
        assertThat(run(projectRoot, "git", "show", task.branchName() + ":feature.txt").strip())
                .isEqualTo("verified local change");
        assertThat(Files.exists(projectRoot.resolve("feature.txt"))).isFalse();
        assertThat(run(projectRoot, "git", "status", "--porcelain")).isBlank();
        assertThat(tasks.artifacts(task.id())).anyMatch(artifact -> "LOCAL_SOURCE_SYNC".equals(artifact.kind())
                && synced.commitSha().equals(artifact.content()));
        assertThat(tasks.queueStatus(task.id()).state()).isEqualTo("FINISHED");
        assertThat(tasks.get(waiting.id()).state()).isEqualTo("READY");
        assertThat(tasks.get(waiting.id()).worktreePath()).isEqualTo(projectRoot.toRealPath().toString());
        assertThat(run(projectRoot, "git", "branch", "--show-current").strip())
                .isEqualTo(tasks.get(waiting.id()).branchName());
    }

    @Test
    void repositoryWithoutRemoteRestoresSourceBranchWhenNoTaskIsWaiting() throws Exception {
        Path projectRoot = repositoryWithoutRemote();
        ProjectRow project = projects.create("local-sync-dirty", projectRoot.toString());
        TaskRow task = succeededTask(project);
        Files.writeString(projectRoot.resolve("README.md"), "user local change\n");
        Files.writeString(Path.of(task.worktreePath()).resolve("feature.txt"), "verified task change\n");
        String baseline = run(projectRoot, "git", "rev-parse", "HEAD").strip();

        assertThat(Path.of(task.worktreePath()).toRealPath()).isEqualTo(projectRoot.toRealPath());
        assertThat(run(projectRoot, "git", "branch", "--show-current").strip()).isEqualTo(task.branchName());

        TaskPublicationService.PublicationStatus synced = publication.commitAndPush(
                task.id(), "#3032_保留源目录已有改动");

        assertThat(synced.state()).isEqualTo("SYNCED_LOCAL");
        assertThat(run(projectRoot, "git", "branch", "--show-current").strip()).isEqualTo("main");
        assertThat(run(projectRoot, "git", "rev-parse", "HEAD").strip()).isEqualTo(baseline);
        assertThat(run(projectRoot, "git", "rev-parse", "refs/heads/" + task.branchName()).strip())
                .isEqualTo(synced.commitSha()).isNotEqualTo(baseline);
        assertThat(Files.readString(projectRoot.resolve("README.md"))).isEqualTo("fixture\n");
        assertThat(Files.exists(projectRoot.resolve("feature.txt"))).isFalse();
        assertThat(run(projectRoot, "git", "show", task.branchName() + ":README.md").strip())
                .isEqualTo("user local change");
        assertThat(run(projectRoot, "git", "show", task.branchName() + ":feature.txt").strip())
                .isEqualTo("verified task change");
        assertThat(run(projectRoot, "git", "status", "--short")).isBlank();
    }

    @Test
    void publicationRefusesToRunAfterTheRegisteredCheckoutLeavesItsTaskBranch() throws Exception {
        Path projectRoot = repositoryWithoutRemote();
        ProjectRow project = projects.create("local-sync-branch-mismatch", projectRoot.toString());
        TaskRow task = succeededTask(project);
        run(projectRoot, "git", "switch", "main");

        TaskPublicationService.PublicationStatus status = publication.status(task.id());
        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.reason()).contains("尚无可发布提交");
    }

    private TaskRow succeededTask(ProjectRow project) {
        TaskRow ready = readyTask(project);
        TaskRow succeeded = new TaskRow(ready.id(), ready.projectId(), ready.loopDraftId(), ready.title(), "SUCCEEDED",
                ready.worktreePath(), ready.branchName(), ready.sourceBranch(), ready.baselineCommit(),
                ready.createdAt(), Instant.now().toString(), ready.version());
        assertThat(mapper.updateTaskState(succeeded)).isEqualTo(1);
        return tasks.get(ready.id());
    }

    private TaskRow readyTask(ProjectRow project) {
        LoopDraftRow draft = drafts.create(new io.opencode.loopper.domain.LoopSpec(
                "v1", project.id(), "实现并验证任务发布流程", null,
                List.of(new io.opencode.loopper.domain.LoopSpec.StageSpec(
                        "实现发布流程", List.of(), List.of(), List.of("source"),
                        List.of(new io.opencode.loopper.domain.LoopSpec.VerifierSpec(
                                "PROCESS", List.of("git", "status", "--short"), null, null, null, null, null)))),
                null, null, null, null));
        return drafts.confirm(draft.id(), "任务发布流程");
    }

    private Repository repositoryWithRemote() throws Exception {
        Path remote = temp.resolve("remote.git");
        Files.createDirectories(remote);
        run(remote, "git", "init", "--bare");
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        run(project, "git", "init", "-b", "main");
        run(project, "git", "config", "user.email", "test@example.invalid");
        run(project, "git", "config", "user.name", "Loopper Test");
        Files.writeString(project.resolve("README.md"), "fixture\n");
        run(project, "git", "add", "README.md");
        run(project, "git", "commit", "-m", "initial");
        run(project, "git", "remote", "add", "origin", remote.toString());
        run(project, "git", "push", "-u", "origin", "main");
        return new Repository(project, remote);
    }

    private Path repositoryWithoutRemote() throws Exception {
        Path project = temp.resolve("local-project-" + System.nanoTime());
        Files.createDirectories(project);
        run(project, "git", "init", "-b", "main");
        run(project, "git", "config", "user.email", "test@example.invalid");
        run(project, "git", "config", "user.name", "Loopper Test");
        Files.writeString(project.resolve("README.md"), "fixture\n");
        run(project, "git", "add", "README.md");
        run(project, "git", "commit", "-m", "initial");
        return project;
    }

    private String run(Path directory, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(String.join(" ", argv) + "\n" + output).isZero();
        return output;
    }

    private record Repository(Path project, Path remote) { }
}
