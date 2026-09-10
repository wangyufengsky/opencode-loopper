package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(classes = LoopperApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"
})
class TaskStoryAccountingHandoffIntegrationTest {
    private static final Path DATA = temporaryData();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", () -> DATA.toString());
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    private static Path temporaryData() {
        try { return Files.createTempDirectory("loopper-task-story-handoff-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @Autowired ProjectService projects;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired StoryBindingService bindings;
    @Autowired StoryAccountingCoordinator accounting;
    @Autowired LoopperMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecoveryService recoveries;
    @LocalServerPort int port;
    @MockitoSpyBean OpenCodeClient openCode;
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"direct,success", "git,success", "git,complete-failure", "git,cancel-task", "git,cancel-statistics", "git,rework", "git,locked-file"})
    void firstStartWaitsForDesignerCompleteBeforePreparingWorkspace(String workspace, String outcome) throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture");
        boolean lockedFile = outcome.equals("locked-file");
        if (lockedFile) assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"),
                "Real unreadable-file fault injection requires POSIX permissions; ordering tests run on every platform");
        if (lockedFile) assumeTrue(!"root".equals(System.getProperty("user.name")),
                "The root user bypasses unreadable-file permissions");
        Path accountingFile = root.resolve(".accounting-runtime");
        if (workspace.equals("git")) {
            git("init", "--initial-branch=main");
            git("add", "README.md");
            git("-c", "user.name=Loopper Test", "-c", "user.email=loopper@example.invalid", "commit", "-m", "fixture");
            if (lockedFile) Files.writeString(root.resolve(".git/info/exclude"), ".accounting-runtime\n");
        }
        String projectId = projects.create("handoff fixture", root.toString()).id();
        var spec = new LoopSpec("v1", projectId, "Verify README", null,
                List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        var draft = drafts.create(spec);
        String designerId = UUID.randomUUID().toString(), oldRemoteId = "designer-" + designerId;
        String now = Instant.now().toString();
        mapper.insertDesignerSession(new DesignerSessionRow(designerId, projectId, "RUNNING", "READ_ONLY", now, now, 0,
                null, "RUNNING", draft.id(), "DISCUSSING_REQUIREMENT", 0, 0));
        bindings.attachDesigner(designerId, new StoryBindingConfiguration(true, "SYS-001", "000123"));
        jdbc.update("UPDATE designer_session SET external_session_id=? WHERE id=?", oldRemoteId, designerId);
        mapper.enableDesignerConversations(designerId);
        String conversationId = UUID.randomUUID().toString();
        mapper.insertDesignerConversation(new io.opencode.loopper.persistence.DesignerConversationRow(
                conversationId, designerId, "REQUIREMENT", 1, null, null, null, root.toString(),
                "GENERAL_READ_ONLY", "{}", "CREATING", null, now, now, 0));
        mapper.bindDesignerConversation(conversationId, oldRemoteId, null, null);
        var calls = new CopyOnWriteArrayList<String>();
        accounting.beforeBusinessPrompt(new OpenCodeClient.OpenCodeSession(oldRemoteId, root), request -> {
            calls.add("designer:" + request.arguments());
            return new OpenCodeClient.CommandResult("design-run", "started");
        });
        ApprovedDesignerFixture.prepare(mapper, jdbc, mapper.findDesignerSession(designerId).orElseThrow(), draft);
        assertThat(mapper.storyAccountingOwnerActive(oldRemoteId)).isTrue();
        TaskRow task = drafts.confirm(draft.id(), "handoff task");
        assertThat(mapper.designerConversationForRemote(oldRemoteId).orElseThrow().state()).isEqualTo("RETIRED");
        assertThat(mapper.storyAccountingOwnerActive(oldRemoteId)).isFalse();
        var completing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        accounting.installTransport((remote, request) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.add("designer:" + request.arguments());
            try {
                if (lockedFile) {
                    // Before the fix, the private baseline encountered this file despite the
                    // source ignore rule; hold the completion boundary with real unreadable state.
                    Files.writeString(accountingFile, "temporary accounting state");
                    Files.setPosixFilePermissions(accountingFile, java.util.Set.of());
                }
                completing.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            finally {
                if (lockedFile) {
                    try {
                        Files.setPosixFilePermissions(accountingFile, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                        Files.delete(accountingFile);
                    }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
            }
            if (outcome.equals("complete-failure")) throw new io.opencode.loopper.domain.SessionFailure("PLUGIN_FAILED", "test completion failure");
            return new OpenCodeClient.CommandResult("design-run", "completed");
        });
        // Exercise the production HTTP client's accounting-before-prompt boundary with a local transport.
        doAnswer(invocation -> {
            OpenCodeClient.OpenCodeSession remote = invocation.getArgument(0);
            accounting.beforeBusinessPrompt(remote, request -> {
                calls.add("implementation:" + request.arguments());
                return new OpenCodeClient.CommandResult("implementation-run", "started");
            });
            return invocation.callRealMethod();
        }).when(openCode).promptAsync(any(OpenCodeClient.OpenCodeSession.class), any(OpenCodeClient.PromptRequest.class));
        if (lockedFile) {
            accounting.completeRetiredSessions();
            assertThat(completing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.isReadable(accountingFile)).isFalse();
        }
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = workers.submit(() -> postTask(task.id(), "start"));
            try {
                assertThat(completing.await(5, TimeUnit.SECONDS)).isTrue();
                if (lockedFile) {
                    assertThatThrownBy(() -> {
                        var premature = start.get(2, TimeUnit.SECONDS);
                        throw new AssertionError("Task started before complete: " + premature.state() + "; " + tasks.errors(task.id()));
                    }).isInstanceOf(java.util.concurrent.TimeoutException.class);
                }
                assertThat(start.isDone()).isFalse();
                assertThat(tasks.get(task.id()).worktreePath()).as("workspace must wait for designer statistics").isNull();
                assertThat(mapper.listAttempts(task.id())).isEmpty();
                assertThat(mapper.findStageWorkspaceBaseline(mapper.listStages(task.id()).getFirst().id())).isEmpty();
                assertThat(mapper.findTaskQueue(task.id())).isEmpty();
                assertThat(mapper.activeTaskExecutionCycle(task.id())).isEmpty();
                if (outcome.equals("cancel-task")) {
                    assertThat(postTask(task.id(), "cancel").state()).isEqualTo("CANCELLED");
                    release.countDown();
                    assertThat(start.get(10, TimeUnit.SECONDS).state()).isEqualTo("CANCELLED");
                    assertThat(tasks.get(task.id()).worktreePath()).isNull();
                    assertThat(mapper.listAttempts(task.id())).isEmpty();
                    assertThat(mapper.findTaskQueue(task.id())).isEmpty();
                    assertThat(calls).containsExactly("designer:start SYS-001 000123", "designer:complete");
                    return;
                }
                if (outcome.equals("cancel-statistics")) {
                    var session = mapper.findStoryAccountingSession(oldRemoteId).orElseThrow();
                    accounting.cancel(mapper.findStoryAccountingCall(session.id(), "COMPLETE").orElseThrow().id());
                }
                release.countDown();
                assertThat(start.get(10, TimeUnit.SECONDS).state()).isEqualTo("RUNNING");
                assertThat(mapper.listAttempts(task.id())).hasSize(1);
                assertThat(calls).containsExactly("designer:start SYS-001 000123", "designer:complete",
                        "implementation:start SYS-001 000123");
                assertThat(tasks.errors(task.id())).isEmpty();
                if (outcome.equals("rework")) {
                    assertThat(tasks.cancel(task.id()).state()).isEqualTo("CANCELLED");
                    var child = recoveries.create(task.id(), io.opencode.loopper.domain.RecoveryMode.REWORK_ALL_STAGES);
                    assertThat(mapper.findTaskStoryBinding(child.taskId())).isEqualTo(mapper.findTaskStoryBinding(task.id()));
                    assertThat(postTask(child.taskId(), "start").state()).isEqualTo("RUNNING");
                    assertThat(mapper.listAttempts(child.taskId())).hasSize(1);
                    assertThat(calls.stream().filter(call -> call.equals("implementation:start SYS-001 000123"))).hasSize(2);
                }
            } finally { release.countDown(); }
        } finally { accounting.installTransport(null); }
    }

    private TaskRow postTask(String taskId, String action) throws Exception {
        String origin = "http://127.0.0.1:" + port;
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/api/tasks/" + taskId + "/" + action))
                .header("X-Loopper-Local-UI", "1").header("Origin", origin)
                .timeout(Duration.ofSeconds(15)).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        return tasks.get(taskId);
    }

    private void git(String... arguments) {
        var argv = new java.util.ArrayList<String>();
        argv.add("git");
        argv.addAll(List.of(arguments));
        var result = new SafeProcessRunner().run(root, argv, Duration.ofSeconds(5));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }
}
