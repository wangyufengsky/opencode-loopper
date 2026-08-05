package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
class TaskServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private LoopDraftService drafts;
    @Autowired private TaskService tasks;
    @Autowired private TaskMonitor monitor;
    @Autowired private OpenCodeClient openCode;
    @Autowired private LoopperMapper mapper;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean(); flyway.migrate();
        ((FakeOpenCodeClient) openCode).reset();
    }

    @Test
    void sessionFailureCreatesNewAttemptAndDoesNotFailTask() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        LoopDraftRow draft = drafts.create(spec(project.id()));
        TaskRow task = drafts.confirm(draft.id(), "session recovery");
        assertThat(task.state()).isEqualTo("READY");
        TaskRow running = tasks.start(task.id());
        AttemptRow first = tasks.attempts(task.id()).getFirst();
        TaskRow recovered = tasks.sessionFailed(task.id(), first.id(), "NETWORK", "temporary transport failure");
        assertThat(recovered.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name()));
    }

    @Test
    void confirmationRejectsDesignerContractThatOnlyChecksGitDiff() throws Exception {
        ProjectRow project = projects.create("weak-designer-acceptance", gitProject());
        LoopSpec weak = new LoopSpec("v1", project.id(), "Compile and print PASS", null,
                List.of(new LoopSpec.StageSpec("Implement and verify", List.of("src/**"), List.of("data/**"), List.of("source"),
                        List.of(new LoopSpec.VerifierSpec("GIT_DIFF", null, null, true,
                                List.of("src/**"), List.of("data/**"), true)))), null, null, null, null);
        LoopDraftRow draft = drafts.create(weak);

        assertThatThrownBy(() -> drafts.confirm(draft.id(), "must be executable"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("GIT_DIFF only checks change scope");
    }

    @Test
    void providerRetryStatusFlowsThroughMonitorAsSessionErrorAndContinuesTaskLoop() throws Exception {
        ProjectRow project = projects.create("provider-retry", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "provider retry recovery");
        tasks.start(task.id());
        AttemptRow first = tasks.attempts(task.id()).getFirst();
        ExecutionSessionRow active = mapper.activeSessions(task.id()).getFirst();
        ((FakeOpenCodeClient) openCode).setSessionStatus(active.externalSessionId(), "RETRY", "Free usage exceeded");

        monitor.poll();

        assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.attempts(task.id()).stream().filter(attempt -> attempt.id().equals(first.id())).findFirst().orElseThrow().state())
                .isEqualTo("SESSION_ERROR");
        assertThat(tasks.attempts(task.id())).anyMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name())
                && error.code().equals("OPENCODE_SESSION_RETRY") && error.message().contains("Free usage exceeded"));
    }

    @Test
    void sessionFailureBecomesTaskFatalWhenOldWriterCannotBeConfirmedStopped() throws Exception {
        ProjectRow project = projects.create("unsafe-session-retry", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "unsafe session retry");
        tasks.start(task.id());
        AttemptRow first = tasks.attempts(task.id()).getFirst();
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(externalSessionId, "RUNNING");
        fake.failNextAborts(1);

        TaskRow failed = tasks.sessionFailed(task.id(), first.id(), "NETWORK", "transport state is unknown");

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(1).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.TASK.name())
                && error.code().equals("SESSION_ABORT_UNCONFIRMED"));
    }

    @Test
    void taskDurationFailureKeepsUnconfirmedWriterVisibleUntilCleanupConfirmsAbort() throws Exception {
        ProjectRow project = projects.create("task-timeout-cleanup", gitProject());
        LoopSpec shortTask = new LoopSpec("v1", project.id(), "Bound task cleanup", null,
                List.of(new LoopSpec.StageSpec("Keep the writer bounded", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                new LoopSpec.Limits(3, 12, 3, 2, 1L, 30L, 30L), null, null, null);
        TaskRow task = drafts.confirm(drafts.create(shortTask).id(), "task timeout cleanup");
        tasks.start(task.id());
        ExecutionSessionRow active = mapper.activeSessions(task.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(active.externalSessionId(), "RUNNING");
        fake.failNextAborts(1);

        long waitMillis = Duration.between(Instant.now(), Instant.parse(task.createdAt()).plusSeconds(1).plusMillis(25)).toMillis();
        if (waitMillis > 0) Thread.sleep(waitMillis);
        tasks.enforceTimeouts(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("FAILED");
        assertThat(mapper.findSession(active.id()).orElseThrow().state()).isEqualTo("DISCONNECTED");
        assertThat(tasks.attempts(task.id())).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name())
                && error.code().equals("SESSION_ABORT_UNCONFIRMED") && error.retryable());

        tasks.retrySessionCleanup(active.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("FAILED");
        assertThat(mapper.findSession(active.id()).orElseThrow().state()).isEqualTo("ABORTED");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("SESSION_ABORT_CLEANUP_CONFIRMED"));
    }

    @Test
    void terminalTaskAbortCleanupIsBoundedAndNeverClaimsAnUnconfirmedWriterWasAborted() throws Exception {
        ProjectRow project = projects.create("bounded-abort-cleanup", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "bounded abort cleanup");
        tasks.start(task.id());
        ExecutionSessionRow active = mapper.activeSessions(task.id()).getFirst();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(active.externalSessionId(), "RUNNING");
        fake.failNextAborts(10);

        tasks.cancel(task.id());
        tasks.retrySessionCleanup(active.id());
        tasks.retrySessionCleanup(active.id());
        tasks.retrySessionCleanup(active.id());
        tasks.retrySessionCleanup(active.id()); // no-op after the persisted limit

        assertThat(tasks.get(task.id()).state()).isEqualTo("CANCELLED");
        assertThat(mapper.findSession(active.id()).orElseThrow().state()).isEqualTo("DISCONNECTED");
        assertThat(mapper.sessionsPendingAbortCleanup()).isEmpty();
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("SESSION_ABORT_CLEANUP_EXHAUSTED")
                && !error.retryable());
        assertThat(openCode.sessionStatus(new OpenCodeClient.OpenCodeSession(active.externalSessionId(), Path.of(task.worktreePath()))).state())
                .isEqualTo("RUNNING");
    }

    @Test
    void stagePathGuidanceDoesNotCreateAnImplicitGitDiffVerifier() throws Exception {
        ProjectRow project = projects.create("path-policy", gitProject());
        LoopSpec restricted = new LoopSpec("v1", project.id(), "Keep changes in README", null,
                List.of(new LoopSpec.StageSpec("Edit only README", List.of("README.md"), List.of("outside.txt"), null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(restricted).id(), "path policy");
        tasks.start(task.id());
        Files.writeString(Path.of(task.worktreePath()).resolve("outside.txt"), "out of scope");

        TaskRow judging = tasks.verify(task.id());

        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id()))
                .extracting(result -> result.type()).containsExactly("FILE_EXISTS");
        assertThat(tasks.errors(task.id())).noneMatch(error -> error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void malformedVerifierPathFailsTaskAndPersistsTaskErrorInsteadOfStallingMonitor() throws Exception {
        ProjectRow project = projects.create("invalid-verifier-path", gitProject());
        LoopSpec invalid = new LoopSpec("v1", project.id(), "Reject malformed verifier path", null,
                List.of(new LoopSpec.StageSpec("Check malformed path", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "bad\u0000path", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(invalid).id(), "invalid verifier path");
        tasks.start(task.id());

        TaskRow failed = tasks.verify(task.id());

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo("TASK_ERROR");
            assertThat(attempt.failureKind()).isEqualTo("VERIFIER_PATH_INVALID");
        });
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.TASK.name())
                && error.code().equals("VERIFIER_PATH_INVALID"));
    }

    @Test
    void verificationRefusesToRaceAStillRunningImplementationSession() throws Exception {
        ProjectRow project = projects.create("running-session-gate", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "running session gate");
        tasks.start(task.id());
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        ((FakeOpenCodeClient) openCode).setSessionState(externalSessionId, "RUNNING");

        assertThatThrownBy(() -> tasks.verify(task.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("while the implementation Session is RUNNING");

        assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(1).allMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(mapper.activeSessions(task.id())).hasSize(1);
    }

    @Test
    void projectWithoutGitRunsInRegisteredDirectoryAndKeepsDiffPolicy() throws Exception {
        Path plainDirectory = Files.createDirectory(temp.resolve("not-a-repository"));
        Files.writeString(plainDirectory.resolve("README.md"), "fixture");
        ProjectRow project = projects.create("plain", plainDirectory.toString());
        LoopSpec directSpec = new LoopSpec("v1", project.id(), "Create a source file", null,
                List.of(new LoopSpec.StageSpec("Implement directly", List.of("src/**"), List.of("data/**"), List.of("src/App.java"),
                        List.of(
                                new LoopSpec.VerifierSpec("GIT_DIFF", null, null, true,
                                        List.of("src/**"), List.of("data/**"), true),
                                new LoopSpec.VerifierSpec("FILE_EXISTS", null, "src/App.java", null, null, null, null)))),
                null, null, null, null);
        TaskRow task = drafts.confirm(drafts.create(directSpec).id(), "run directly");

        assertThat(task.state()).isEqualTo("READY");
        assertThat(task.branchName()).isEqualTo("DIRECT");
        assertThat(task.worktreePath()).isEqualTo(plainDirectory.toRealPath().toString());
        assertThat(task.baselineCommit()).startsWith("direct:" + task.id() + ":");

        Files.createDirectories(plainDirectory.resolve("src"));
        Files.writeString(plainDirectory.resolve("src/App.java"), "class App {}");
        tasks.start(task.id());
        tasks.verify(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("JUDGING");
        assertThat(tasks.verifications(tasks.attempts(task.id()).getFirst().id()))
                .allMatch(result -> result.state().equals("PASS"));
    }

    @Test
    void projectWithUnbornGitRepositoryAlsoRunsDirectly() throws Exception {
        Path projectRoot = Files.createDirectory(temp.resolve("unborn-repository"));
        Files.writeString(projectRoot.resolve("README.md"), "fixture");
        run(projectRoot, "git", "init");
        run(projectRoot, "git", "add", "README.md");
        ProjectRow project = projects.create("unborn", projectRoot.toString());

        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "no head yet");

        assertThat(task.state()).isEqualTo("READY");
        assertThat(task.branchName()).isEqualTo("DIRECT");
        assertThat(task.worktreePath()).isEqualTo(projectRoot.toRealPath().toString());
    }

    @Test
    void pauseAndResumeKeepExistingSessionInsteadOfCreatingDuplicate() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "pause");
        tasks.start(task.id());
        tasks.pause(task.id());
        TaskRow resumed = tasks.resume(task.id());
        assertThat(resumed.state()).isEqualTo("RUNNING");
        assertThat(tasks.attempts(task.id())).hasSize(1);
        assertThat(tasks.stages(task.id())).allMatch(stage -> !stage.state().equals("PAUSED"));
    }

    @Test
    void restartRecoveryAbortsOldSessionAndContinuesWithFreshSession() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "restart");
        tasks.start(task.id());
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        tasks.recoverAfterRestart();
        assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        assertThat(openCode.sessionStatus(new OpenCodeClient.OpenCodeSession(externalSessionId, Path.of(task.worktreePath()))).state())
                .isEqualTo("ABORTED");
        assertThat(tasks.attempts(task.id())).hasSize(2);
        assertThat(tasks.attempts(task.id())).anySatisfy(attempt -> assertThat(attempt.state()).isEqualTo("SESSION_ERROR"));
        assertThat(tasks.attempts(task.id())).anySatisfy(attempt -> assertThat(attempt.state()).isEqualTo("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("RUNTIME_RESTART"));
    }

    @Test
    void restartRecoveryFailsTaskWhenOldMutatingSessionCannotBeConfirmedStopped() throws Exception {
        ProjectRow project = projects.create("unsafe-restart", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "unsafe restart");
        tasks.start(task.id());
        String externalSessionId = mapper.activeSessions(task.id()).getFirst().externalSessionId();
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        fake.setSessionState(externalSessionId, "RUNNING");
        fake.failNextAborts(1);

        tasks.recoverAfterRestart();

        assertThat(tasks.get(task.id()).state()).isEqualTo("FAILED");
        assertThat(tasks.attempts(task.id())).hasSize(1).noneMatch(attempt -> attempt.state().equals("RUNNING"));
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.TASK.name())
                && error.code().equals("SESSION_ABORT_UNCONFIRMED"));
    }

    @Test
    void finalDeterministicPassRequiresTwoReadOnlyJudgesAndRetainsEvidence() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "two judges");
        tasks.start(task.id());

        TaskRow judging = tasks.verify(task.id());
        assertThat(judging.state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(task.id())).hasSize(2).allSatisfy(judge -> {
            assertThat(judge.state()).isEqualTo("RUNNING");
            assertThat(((FakeOpenCodeClient) openCode).isReadOnlySession(judge.externalSessionId())).isTrue();
        });
        assertThat(tasks.artifacts(task.id())).extracting(artifact -> artifact.kind())
                .contains("GIT_DIFF", "VERIFICATION_SUMMARY", "JUDGE_LOG_METADATA");

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.judges(task.id())).allSatisfy(judge -> {
            assertThat(judge.verdict()).isEqualTo("PASS");
            assertThat(judge.rawOutput()).contains("PASS");
        });
        assertThat(tasks.artifacts(task.id())).extracting(artifact -> artifact.kind()).contains("JUDGE_RESULT");

        int attemptsAfterSuccess = tasks.attempts(task.id()).size();
        int errorsAfterSuccess = tasks.errors(task.id()).size();
        String historicalAttemptId = tasks.attempts(task.id()).getFirst().id();
        assertThatThrownBy(() -> tasks.sessionFailed(task.id(), historicalAttemptId, "LATE_CALLBACK", "stale transport callback"))
                .isInstanceOf(ConflictException.class);
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(tasks.attempts(task.id())).hasSize(attemptsAfterSuccess);
        assertThat(tasks.errors(task.id())).hasSize(errorsAfterSuccess);
    }

    @Test
    void blockedOrUnparseableJudgeWaitsForInputInsteadOfPretendingSuccessOrFailingTask() throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("{\"verdict\":\"BLOCKED\",\"reason\":\"Missing release evidence\"}");
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "blocked judge");
        tasks.start(task.id());
        tasks.verify(task.id());

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.get(task.id()).state()).isNotEqualTo("FAILED");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_REVIEW_NOT_APPROVED") || error.code().equals("JUDGE_CONFLICT"));
    }

    @Test
    void reviseAndUnparseableJudgeResponsesBothStopAtWaitingInput() throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("{\"verdict\":\"REVISE\",\"reason\":\"A release note is still required\"}");
        ProjectRow reviseProject = projects.create("revise", gitProject());
        TaskRow revise = drafts.confirm(drafts.create(spec(reviseProject.id())).id(), "revise judge");
        tasks.start(revise.id()); tasks.verify(revise.id()); tasks.pollJudges(revise.id());
        assertThat(tasks.get(revise.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(revise.id())).allSatisfy(judge -> assertThat(judge.verdict()).isEqualTo("REVISE"));

        ((FakeOpenCodeClient) openCode).setJudgeOutput("not a JSON decision");
        ProjectRow malformedProject = projects.create("malformed", gitProject());
        TaskRow malformed = drafts.confirm(drafts.create(spec(malformedProject.id())).id(), "unparseable judge");
        tasks.start(malformed.id()); tasks.verify(malformed.id()); tasks.pollJudges(malformed.id());
        assertThat(tasks.get(malformed.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.judges(malformed.id())).allSatisfy(judge -> assertThat(judge.verdict()).isEqualTo("UNPARSEABLE"));
    }

    @Test
    void conflictingJudgeVerdictsWaitForHumanResolution() throws Exception {
        ((FakeOpenCodeClient) openCode).setJudgeOutput("REQUIREMENT", "{\"verdict\":\"PASS\",\"reason\":\"Requirements are met\"}");
        ((FakeOpenCodeClient) openCode).setJudgeOutput("RISK", "{\"verdict\":\"BLOCKED\",\"reason\":\"Security evidence is missing\"}");
        ProjectRow project = projects.create("conflict", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "conflicting judges");
        tasks.start(task.id()); tasks.verify(task.id()); tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_CONFLICT") && error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    @Test
    void judgeRetryStatusStartsFreshReadOnlySessionWithProviderDetailWithoutFailingTask() throws Exception {
        ProjectRow project = projects.create("fixture", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "judge transport recovery");
        tasks.start(task.id());
        tasks.verify(task.id());
        var retryingJudge = tasks.judges(task.id()).getFirst();
        ((FakeOpenCodeClient) openCode).setSessionStatus(retryingJudge.externalSessionId(), "RETRY", "Free usage exceeded");

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("JUDGING");
        assertThat(tasks.judges(task.id())).hasSize(3);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.layer().equals(ErrorLayer.SESSION.name())
                && error.code().equals("JUDGE_SESSION_RETRY") && error.message().contains("Free usage exceeded"));

        tasks.pollJudges(task.id());
        assertThat(tasks.get(task.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test
    void exhaustedJudgeSessionRetriesWaitForInputInsteadOfCreatingAnInfiniteLoopOrFailingTask() throws Exception {
        ((FakeOpenCodeClient) openCode).failNextReadOnlySessions("REQUIREMENT", 3);
        ProjectRow project = projects.create("exhaustion", gitProject());
        TaskRow task = drafts.confirm(drafts.create(spec(project.id())).id(), "judge retry exhaustion");
        tasks.start(task.id()); tasks.verify(task.id());

        tasks.pollJudges(task.id());
        tasks.pollJudges(task.id());
        tasks.pollJudges(task.id());

        assertThat(tasks.get(task.id()).state()).isEqualTo("WAITING_INPUT");
        assertThat(tasks.get(task.id()).state()).isNotEqualTo("FAILED");
        assertThat(tasks.judges(task.id()).stream().filter(judge -> judge.role().equals("REQUIREMENT") && judge.state().equals("SESSION_ERROR"))).hasSize(3);
        assertThat(tasks.errors(task.id())).anyMatch(error -> error.code().equals("JUDGE_SESSION_RETRY_EXHAUSTED") && error.layer().equals(ErrorLayer.VERIFICATION.name()));
    }

    private LoopSpec spec(String projectId) {
        return new LoopSpec("v1", projectId, "Verify README", null, List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))), null, null, null, null);
    }
    private String gitProject() throws Exception {
        Path root = Files.createDirectory(temp.resolve("git-" + System.nanoTime()));
        Files.writeString(root.resolve("README.md"), "fixture");
        run(root, "git", "init"); run(root, "git", "config", "user.email", "test@example.invalid"); run(root, "git", "config", "user.name", "test");
        run(root, "git", "add", "README.md"); run(root, "git", "commit", "-m", "initial");
        return root.toString();
    }
    private void run(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new AssertionError(output);
    }
}
