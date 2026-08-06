package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.domain.InteractionAction;
import io.opencode.loopper.domain.InteractionKind;
import io.opencode.loopper.domain.InteractionState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h"
})
class InteractionServiceIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private LoopperMapper mapper;
    @Autowired private InteractionService interactions;
    @Autowired private OpenCodeClient openCode;
    @TempDir Path temporaryDirectory;

    private FakeOpenCodeClient fake;

    @BeforeEach
    void reset() throws Exception {
        flyway.clean();
        flyway.migrate();
        fake = (FakeOpenCodeClient) openCode;
        fake.reset();
        insertActiveTaskSession(temporaryDirectory);
    }

    @Test
    void persistsAndResolvesQuestionAndPermissionWithOptimisticVersions() {
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "permission-1", "remote-1", "bash", List.of("git status"), Map.of(), "Inspect repository"));

        FeatureContracts.InteractionDto permission = interactions.listOpen().getFirst();
        assertThat(permission.kind()).isEqualTo(InteractionKind.PERMISSION);
        assertThat(permission.state()).isEqualTo(InteractionState.PENDING);
        assertThat(permission.version()).isZero();

        FeatureContracts.InteractionDto resolved = interactions.resolve(permission.id(),
                new FeatureContracts.ResolveInteractionRequest(InteractionAction.ONCE, List.of(), null, permission.version()));
        assertThat(resolved.state()).isEqualTo(InteractionState.RESOLVED);
        assertThat(fake.permissionReplyForRequest("permission-1").reply())
                .isEqualTo(OpenCodeClient.PermissionReply.ONCE);
        assertThatThrownBy(() -> interactions.resolve(permission.id(),
                new FeatureContracts.ResolveInteractionRequest(InteractionAction.ONCE, List.of(), null, permission.version())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already resolved or changed");

        fake.setPendingQuestion("remote-1", new OpenCodeClient.PendingQuestion(
                "question-1", "remote-1", List.of(new OpenCodeClient.QuestionPrompt(
                "Continue?", "Decision", List.of(new OpenCodeClient.QuestionOption("Yes", "Proceed")), false, false))));
        FeatureContracts.InteractionDto question = interactions.listOpen().stream()
                .filter(item -> item.kind() == InteractionKind.QUESTION).findFirst().orElseThrow();
        interactions.resolve(question.id(), new FeatureContracts.ResolveInteractionRequest(
                InteractionAction.REPLY, List.of(List.of("Yes")), null, question.version()));
        assertThat(fake.answersForQuestion("question-1")).containsExactly(List.of("Yes"));
    }

    @Test
    void hardDeniesDangerousPermissionAndKeepsItNonOverridableUntilProviderClearsIt() {
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "permission-danger", "remote-1", "bash", List.of("git push origin main"), Map.of(), "Publish"));

        FeatureContracts.InteractionDto denied = interactions.listOpen().getFirst();
        assertThat(denied.state()).isEqualTo(InteractionState.HARD_DENIED);
        assertThat(denied.payload().path("hardDenied").asBoolean()).isTrue();
        assertThat(fake.permissionReplyForRequest("permission-danger").reply())
                .isEqualTo(OpenCodeClient.PermissionReply.REJECT);
        assertThatThrownBy(() -> interactions.resolve(denied.id(),
                new FeatureContracts.ResolveInteractionRequest(InteractionAction.SESSION, List.of(), null, denied.version())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("non-overridable");

        fake.setPendingPermissions("remote-1", List.of());
        assertThat(interactions.listOpen()).isEmpty();
        assertThat(mapper.findInteraction(denied.id()).orElseThrow().state()).isEqualTo("STALE");
    }

    @Test
    void hardDenyCannotBeBypassedWithGitOptionsOrEquivalentRecursiveDeleteFlags() {
        List<String> dangerous = List.of(
                "git -C /repo push origin main",
                "git --git-dir=/repo/.git push origin main",
                "/usr/bin/git reset --hard HEAD~1",
                "rm --recursive --force ./output",
                "rm -r ./output",
                "find ./output -type f -delete",
                "Remove-Item ./output -Recurse -Force",
                "cmd /c rmdir /s /q C:\\temp\\output",
                "cmd /c del /s /q C:\\temp\\output\\*",
                "external_directory /Users/shared/output");
        for (int index = 0; index < dangerous.size(); index++) {
            String requestId = "danger-" + index;
            fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                    requestId, "remote-1", "bash", List.of(dangerous.get(index)), Map.of(), "Dangerous command"));

            FeatureContracts.InteractionDto denied = interactions.listOpen().stream()
                    .filter(item -> requestId.equals(item.externalRequestId())).findFirst().orElseThrow();

            assertThat(denied.state()).as(dangerous.get(index)).isEqualTo(InteractionState.HARD_DENIED);
            assertThat(fake.permissionReplyForRequest(requestId).reply()).isEqualTo(OpenCodeClient.PermissionReply.REJECT);
        }
    }

    @Test
    void hardDeniesProviderBashRequestsThatEscapeTheTaskWorkspaceAndCannotBeOverridden() throws Exception {
        String workspace = Path.of(mapper.findTask("task-1").orElseThrow().worktreePath()).toRealPath().toString();
        List<OpenCodeClient.PendingPermission> escaped = List.of(
                new OpenCodeClient.PendingPermission("outside-ls", "remote-1", "bash",
                        List.of("ls -la /private/tmp/loopper-final-outside && find /outside -type f"), Map.of(), "Outside"),
                new OpenCodeClient.PendingPermission("outside-parent", "remote-1", "bash",
                        List.of("find ../outside -type f"), Map.of("cwd", "../outside"), "Parent"),
                new OpenCodeClient.PendingPermission("outside-metadata", "remote-1", "bash",
                        List.of("pwd"), Map.of("paths", List.of(workspace + "/inside", "/private/tmp/loopper-final-outside")), "Metadata"));
        for (OpenCodeClient.PendingPermission permission : escaped) {
            fake.setPendingPermission("remote-1", permission);
            FeatureContracts.InteractionDto denied = interactions.listOpen().stream()
                    .filter(item -> permission.id().equals(item.externalRequestId())).findFirst().orElseThrow();
            assertThat(denied.state()).isEqualTo(InteractionState.HARD_DENIED);
            assertThat(denied.payload().path("hardDenyReason").asText()).contains("工作区外");
            assertThat(fake.permissionReplyForRequest(permission.id()).reply()).isEqualTo(OpenCodeClient.PermissionReply.REJECT);
            assertThatThrownBy(() -> interactions.resolve(denied.id(), new FeatureContracts.ResolveInteractionRequest(
                    InteractionAction.ONCE, List.of(), null, denied.version())))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("non-overridable");
        }
    }

    @Test
    void allowsWorkspacePathsAndAbsoluteSystemExecutables() throws Exception {
        String workspace = Path.of(mapper.findTask("task-1").orElseThrow().worktreePath()).toRealPath().toString();
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "inside-and-git", "remote-1", "bash", List.of(
                "/usr/bin/git status", "find ./src -type f", "ls -la " + workspace + "/new-directory",
                "echo x >'" + workspace + "/safe-output'"),
                Map.of("cwd", workspace), "Inspect workspace"));

        FeatureContracts.InteractionDto allowed = interactions.listOpen().stream()
                .filter(item -> "inside-and-git".equals(item.externalRequestId())).findFirst().orElseThrow();
        assertThat(allowed.state()).isEqualTo(InteractionState.PENDING);
        interactions.resolve(allowed.id(), new FeatureContracts.ResolveInteractionRequest(
                InteractionAction.ONCE, List.of(), "仅工作区", allowed.version()));
        assertThat(fake.permissionReplyForRequest("inside-and-git").reply()).isEqualTo(OpenCodeClient.PermissionReply.ONCE);
    }

    @Test
    void onlyExemptsTrustedAbsoluteExecutablesAtTheStartOfEachCommandSegment() {
        fake.setPendingPermissions("remote-1", List.of(
                new OpenCodeClient.PendingPermission("system-command", "remote-1", "bash",
                        List.of("/usr/bin/git status; /bin/echo ok | /usr/bin/git status"), Map.of(), "Commands"),
                new OpenCodeClient.PendingPermission("system-path-argument", "remote-1", "bash",
                        List.of("cat /usr/bin/git"), Map.of(), "Read executable"),
                new OpenCodeClient.PendingPermission("system-path-read", "remote-1", "read",
                        List.of("/usr/bin/git"), Map.of(), "Read executable as data"),
                new OpenCodeClient.PendingPermission("redirect-path", "remote-1", "bash",
                        List.of("echo x >/private/tmp/escaped", "cat </private/tmp/input", "echo x 2>/private/tmp/error"),
                        Map.of(), "Redirect outside")));

        List<FeatureContracts.InteractionDto> open = interactions.listOpen();
        FeatureContracts.InteractionDto command = open.stream()
                .filter(item -> "system-command".equals(item.externalRequestId())).findFirst().orElseThrow();
        FeatureContracts.InteractionDto argument = open.stream()
                .filter(item -> "system-path-argument".equals(item.externalRequestId())).findFirst().orElseThrow();
        FeatureContracts.InteractionDto read = open.stream()
                .filter(item -> "system-path-read".equals(item.externalRequestId())).findFirst().orElseThrow();
        FeatureContracts.InteractionDto redirect = open.stream()
                .filter(item -> "redirect-path".equals(item.externalRequestId())).findFirst().orElseThrow();
        assertThat(command.state()).isEqualTo(InteractionState.PENDING);
        assertThat(argument.state()).isEqualTo(InteractionState.HARD_DENIED);
        assertThat(read.state()).isEqualTo(InteractionState.HARD_DENIED);
        assertThat(redirect.state()).isEqualTo(InteractionState.HARD_DENIED);
        assertThat(fake.permissionReplyForRequest("system-path-argument").reply())
                .isEqualTo(OpenCodeClient.PermissionReply.REJECT);
        assertThat(fake.permissionReplyForRequest("system-path-read").reply())
                .isEqualTo(OpenCodeClient.PermissionReply.REJECT);
        assertThat(fake.permissionReplyForRequest("redirect-path").reply())
                .isEqualTo(OpenCodeClient.PermissionReply.REJECT);
    }

    @Test
    void promotesAReusedProviderRequestFromSafeToHardDeniedWithoutEverDowngradingOrReopeningIt() {
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "reused-request", "remote-1", "bash", List.of("git status"), Map.of(), "Safe"));
        FeatureContracts.InteractionDto safe = interactions.listOpen().stream()
                .filter(item -> "reused-request".equals(item.externalRequestId())).findFirst().orElseThrow();
        assertThat(safe.state()).isEqualTo(InteractionState.PENDING);

        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "reused-request", "remote-1", "bash", List.of("find /private/tmp/escaped -type f"), Map.of(), "Unsafe"));
        FeatureContracts.InteractionDto denied = interactions.listOpen().stream()
                .filter(item -> "reused-request".equals(item.externalRequestId())).findFirst().orElseThrow();
        assertThat(denied.state()).isEqualTo(InteractionState.HARD_DENIED);
        assertThat(denied.version()).isGreaterThan(safe.version());
        assertThat(denied.payload().path("patterns").get(0).asText()).contains("/private/tmp/escaped");
        assertThat(fake.permissionReplyForRequest("reused-request").reply()).isEqualTo(OpenCodeClient.PermissionReply.REJECT);
        assertThatThrownBy(() -> interactions.resolve(safe.id(), new FeatureContracts.ResolveInteractionRequest(
                InteractionAction.ONCE, List.of(), null, safe.version())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("non-overridable");

        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "reused-request", "remote-1", "bash", List.of("git status"), Map.of(), "Safe again"));
        FeatureContracts.InteractionDto stillDenied = interactions.listOpen().stream()
                .filter(item -> "reused-request".equals(item.externalRequestId())).findFirst().orElseThrow();
        assertThat(stillDenied.state()).isEqualTo(InteractionState.HARD_DENIED);
        assertThat(stillDenied.version()).isEqualTo(denied.version());

        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "resolved-request", "remote-1", "bash", List.of("git status"), Map.of(), "Safe"));
        FeatureContracts.InteractionDto resolved = interactions.listOpen().stream()
                .filter(item -> "resolved-request".equals(item.externalRequestId())).findFirst().orElseThrow();
        interactions.resolve(resolved.id(), new FeatureContracts.ResolveInteractionRequest(
                InteractionAction.ONCE, List.of(), null, resolved.version()));
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "resolved-request", "remote-1", "bash", List.of("ls /private/tmp/escaped"), Map.of(), "Unsafe later"));
        interactions.listOpen();
        assertThat(mapper.findInteraction(resolved.id()).orElseThrow().state()).isEqualTo("RESOLVED");
        assertThat(fake.permissionReplyForRequest("resolved-request").reply()).isEqualTo(OpenCodeClient.PermissionReply.REJECT);
    }

    @Test
    void rechecksLivePermissionBeforeGrantAndPromotesResolvingRowsWithoutReleasingThem() {
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "toctou-request", "remote-1", "bash", List.of("git status"), Map.of(), "Initially safe"));
        FeatureContracts.InteractionDto safe = interactions.listOpen().stream()
                .filter(item -> "toctou-request".equals(item.externalRequestId())).findFirst().orElseThrow();

        // Do not refresh first: this simulates a provider changing the payload between Inbox rendering and ONCE.
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "toctou-request", "remote-1", "bash", List.of("ls /private/tmp/changed-after-render"), Map.of(), "Changed"));
        assertThatThrownBy(() -> interactions.resolve(safe.id(), new FeatureContracts.ResolveInteractionRequest(
                InteractionAction.ONCE, List.of(), null, safe.version())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("non-overridable");
        var denied = mapper.findInteraction(safe.id()).orElseThrow();
        assertThat(denied.state()).isEqualTo("HARD_DENIED");
        assertThat(denied.version()).isGreaterThan(safe.version() + 1); // claim + hard-deny promotion
        assertThat(fake.permissionReplyForRequest("toctou-request").reply()).isEqualTo(OpenCodeClient.PermissionReply.REJECT);

        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "resolving-promotion", "remote-1", "bash", List.of("git status"), Map.of(), "Initially safe"));
        FeatureContracts.InteractionDto pending = interactions.listOpen().stream()
                .filter(item -> "resolving-promotion".equals(item.externalRequestId())).findFirst().orElseThrow();
        assertThat(mapper.claimInteraction(pending.id(), pending.version(), Instant.now().toString())).isEqualTo(1);
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "resolving-promotion", "remote-1", "bash", List.of("find /private/tmp/changed-while-claimed"), Map.of(), "Changed"));
        interactions.listOpen();
        var promoted = mapper.findInteraction(pending.id()).orElseThrow();
        assertThat(promoted.state()).isEqualTo("HARD_DENIED");
        assertThat(promoted.version()).isGreaterThan(pending.version() + 1);
        assertThat(fake.permissionReplyForRequest("resolving-promotion").reply()).isEqualTo(OpenCodeClient.PermissionReply.REJECT);
    }

    @Test
    void terminalSessionCannotLeaveAnActionableLookingInboxItem() {
        fake.setPendingPermission("remote-1", new OpenCodeClient.PendingPermission(
                "permission-before-terminal", "remote-1", "bash", List.of("git status"), Map.of(), "Inspect"));
        FeatureContracts.InteractionDto pending = interactions.listOpen().getFirst();

        ExecutionSessionRow session = mapper.findSession("session-1").orElseThrow();
        assertThat(mapper.updateSessionState(new ExecutionSessionRow(session.id(), session.taskId(), session.stageId(),
                session.attemptId(), session.externalSessionId(), "FAILED", session.createdAt(),
                Instant.now().toString(), session.version()))).isEqualTo(1);

        assertThat(interactions.listOpen()).isEmpty();
        assertThat(mapper.findInteraction(pending.id()).orElseThrow().state()).isEqualTo("STALE");
    }

    private void insertActiveTaskSession(Path root) throws Exception {
        String now = Instant.now().toString();
        String path = Files.createDirectory(root.resolve("project")).toRealPath().toString();
        mapper.insertProject(new ProjectRow("project-1", "Project", path, "", now, now, 1, 0));
        mapper.insertTask(new TaskRow("task-1", "project-1", null, "Task", "RUNNING", path,
                "DIRECT", "direct:test", now, now, 0));
        mapper.insertStage(new StageRow("stage-1", "task-1", 0, "Stage", "[]", "[]", "[]", "[]",
                "RUNNING", now, now, 0));
        mapper.insertAttempt(new AttemptRow("attempt-1", "task-1", "stage-1", 1, "RUNNING",
                null, null, now, null, 0));
        mapper.insertSession(new ExecutionSessionRow("session-1", "task-1", "stage-1", "attempt-1",
                "remote-1", "RUNNING", now, null, 0));
    }
}
