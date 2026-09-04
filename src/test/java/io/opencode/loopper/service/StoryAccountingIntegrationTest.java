package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StoryAccountingCallRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.designer-monitor-delay=1h"
})
class StoryAccountingIntegrationTest {
    private static final Path DATA = temporaryDatabase();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("loopper.data-dir", () -> DATA.toString());
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("loopper.db")
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    private static Path temporaryDatabase() {
        try { return Files.createTempDirectory("loopper-story-accounting-test-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    @Autowired Flyway flyway;
    @Autowired LoopperMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired StoryBindingService bindings;
    @Autowired StoryAccountingCoordinator accounting;
    @Autowired StoryAccountingActivityService activity;
    @Autowired io.opencode.loopper.persistence.StoryAccountingActivityMapper activityMapper;
    @Autowired DesignerEventHub designerEvents;
    @Autowired DesignerSessionService designers;
    @Autowired DesignerAttachmentCommandService attachments;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired RecoveryService recoveries;
    @Autowired PlatformTransactionManager transactionManager;
    @TempDir Path root;
    private String projectId;

    @BeforeEach void reset() throws Exception {
        flyway.clean(); flyway.migrate();
        Files.writeString(root.resolve("README.md"), "fixture");
        projectId = projects.create("story fixture", root.toString()).id();
    }

    @Test void creationPathsPersistStringsBeforeRouterAndOrdinaryCreationRemainsUnbound() {
        var config = new StoryBindingConfiguration(true, "SYS-001", "000123");
        var ordinary = designers.create(projectId, null, "Explain this project");
        assertThat(bindings.configurationForDesigner(ordinary.id()).enabled()).isFalse();
        var enabled = designers.create(projectId, null, "Explain this project", config);
        assertThat(bindings.configurationForDesigner(enabled.id())).isEqualTo(config);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_accounting_session WHERE designer_session_id=?", Integer.class, enabled.id()))
                .isZero();
        assertThat(designers.get(enabled.id()).state()).isNotEqualTo("SESSION_ERROR");
        var attached = attachments.create(projectId, null, "Use this context", UUID.randomUUID().toString(),
                List.of(new DesignerAttachmentContext.IncomingFile("context.txt", "text/plain", new byte[]{65})), config);
        assertThat(bindings.configurationForDesigner(attached.id())).isEqualTo(config);
        assertThatThrownBy(() -> designers.create(projectId, null, "Invalid", new StoryBindingConfiguration(true, "", "1")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test void startsEachNewSessionOnceAndCompletesOnlyAfterOwnerRetires() {
        String designer = fixture("remote-1", true);
        List<String> calls = new CopyOnWriteArrayList<>();
        StoryAccountingCoordinator.CommandTransport transport = request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_accounting_call WHERE message_id=? AND state='PREPARED'",
                    Integer.class, request.messageId())).isEqualTo(1);
            calls.add(request.arguments());
            return new OpenCodeClient.CommandResult("plugin-actual-run", "ok");
        };
        accounting.beforeBusinessPrompt(remote("remote-1"), transport);
        accounting.beforeBusinessPrompt(remote("remote-1"), transport);
        accounting.afterTerminalStatus(remote("remote-1"), transport);
        assertThat(calls).containsExactly("start SYS-001 000123");
        jdbc.update("UPDATE designer_session SET external_session_id='remote-2' WHERE id=?", designer);
        accounting.afterTerminalStatus(remote("remote-1"), transport);
        accounting.beforeBusinessPrompt(remote("remote-2"), transport);
        jdbc.update("UPDATE designer_session SET state='COMPLETED',workflow_phase='COMPLETED' WHERE id=?", designer);
        accounting.afterTerminalStatus(remote("remote-2"), transport);
        accounting.beforeAbort(remote("remote-2"));
        assertThat(calls).containsExactly("start SYS-001 000123", "complete", "start SYS-001 000123", "complete");
        assertThat(mapper.findStoryAccountingSession("remote-2")).get()
                .extracting(row -> row.pluginRunId()).isEqualTo("plugin-actual-run");
    }

    @Test void manualCancellationReleasesBusinessWithoutDeadlineRetryOrLateOverwrite() throws Exception {
        String designer = fixture("cancel-statistics", true);
        AtomicInteger requests = new AtomicInteger();
        var received = new java.util.concurrent.CountDownLatch(1);
        var late = new java.util.concurrent.CountDownLatch(1);
        try (var ignored = new CoordinatorCloser(new StoryAccountingCoordinator(mapper, mock(TaskEventService.class),
                new TransactionTemplate(transactionManager)));
             var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var coordinator = ignored.value;
            var business = workers.submit(() -> coordinator.beforeBusinessPrompt(remote("cancel-statistics"), request -> {
                requests.incrementAndGet();
                received.countDown();
                try { late.await(); } catch (InterruptedException expected) { /* Simulate a late transport result. */ }
                return new OpenCodeClient.CommandResult("late-run", "late result");
            }));
            assertThat(received.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100); // A pending provider request stays blocked until explicitly cancelled.
            assertThat(business.isDone()).isFalse();
            var session = mapper.findStoryAccountingSession("cancel-statistics").orElseThrow();
            var call = mapper.findStoryAccountingCall(session.id(), "BEGIN").orElseThrow();
            assertThat(call.state()).isEqualTo("PREPARED");
            AtomicInteger aborts = new AtomicInteger();
            coordinator.installCancellation((remote, message) -> {
                assertThat(business.isDone()).isFalse();
                assertThat(message).isEqualTo(call.messageId());
                aborts.incrementAndGet();
                return true;
            });
            coordinator.cancel(call.id());
            business.get(2, java.util.concurrent.TimeUnit.SECONDS);
            late.countDown();
            coordinator.cancel(call.id());
            assertThat(aborts).hasValue(1);
            coordinator.beforeBusinessPrompt(remote("cancel-statistics"), request -> { throw new AssertionError("must not resend"); });
            assertThat(requests).hasValue(1);
            assertThat(mapper.findStoryAccountingCall(session.id(), "BEGIN")).get()
                    .extracting(StoryAccountingCallRow::state).isEqualTo("CANCELLED");
            assertThat(mapper.findStoryAccountingSession("cancel-statistics").orElseThrow().pluginRunId()).isNull();
            assertThat(designers.get(designer).state()).isEqualTo("RUNNING");
            assertThat(designers.messages(designer)).isEmpty();
            jdbc.update("UPDATE designer_session SET state='CANCELLED' WHERE id=?", designer);
            coordinator.afterTerminalStatus(remote("cancel-statistics"), request -> { throw new IllegalStateException("receiver rejected report"); });
            coordinator.afterTerminalStatus(remote("cancel-statistics"), request -> { throw new AssertionError("duplicate complete"); });
            assertThat(designers.messages(designer)).singleElement()
                    .extracting(message -> message.deliveryState()).isEqualTo("STORY_BINDING_FAILED");
        } finally { late.countDown(); }
    }

    @Test void persistsExactActivityAndDismissalWithoutReopeningHistoryOrReplacingCancelledOutput() {
        fixture("activity", true);
        accounting.beforeBusinessPrompt(remote("activity"), request -> {
            var session = mapper.findStoryAccountingSession("activity").orElseThrow();
            var call = mapper.findStoryAccountingCall(session.id(), "BEGIN").orElseThrow();
            activityMapper.saveParts(call.id(), """
                    [{"id":"output","type":"OUTPUT","label":"模型输出","content":"正在绑定 000123","status":"completed","startedAt":null}]
                    """);
            assertThat(activity.list()).anyMatch(view -> view.id().equals(call.id()));
            activity.dismiss(call.id()); // A running call cannot be hidden through the terminal acknowledgement.
            assertThat(activity.list()).anyMatch(view -> view.id().equals(call.id()));
            return new OpenCodeClient.CommandResult("run", "ok");
        });
        var session = mapper.findStoryAccountingSession("activity").orElseThrow();
        var call = mapper.findStoryAccountingCall(session.id(), "BEGIN").orElseThrow();
        assertThat(activity.get(call.id()).parts()).singleElement()
                .extracting(OpenCodeClient.SessionPart::content).isEqualTo("正在绑定 000123");
        assertThat(activityMapper.saveParts(call.id(), "[]")).isZero();
        activity.dismiss(call.id());
        activity.dismiss(call.id());
        assertThat(activity.list()).noneMatch(view -> view.id().equals(call.id()));
        assertThat(activity.get(call.id()).parts()).hasSize(1);
    }

    @Test void unknownCallsAreRecoveredOnceWithoutRedispatch() {
        String designer = fixture("restart", true);
        accounting.beforeBusinessPrompt(remote("restart"), request -> new OpenCodeClient.CommandResult("run", "ok"));
        jdbc.update("UPDATE story_accounting_call SET state='PREPARED',started_at='2000-01-01T00:00:00Z',finished_at=NULL");
        jdbc.update("UPDATE story_accounting_session SET state='BINDING'");
        accounting.recoverInterruptedCalls();
        accounting.recoverInterruptedCalls();
        accounting.beforeBusinessPrompt(remote("restart"), request -> { throw new AssertionError("unknown commands are not retried"); });
        assertThat(jdbc.queryForObject("SELECT state FROM story_accounting_call", String.class)).isEqualTo("UNKNOWN");
        assertThat(designers.messages(designer)).hasSize(1);
    }

    @Test void failedAccountingPublishesOneMessageRefreshWithoutChangingBusinessState() throws Exception {
        String designer = fixture("event", true);
        List<String> calls = new CopyOnWriteArrayList<>();
        List<DesignerEventHub.DesignerEvent> notifications = new CopyOnWriteArrayList<>();
        try (var subscription = designerEvents.subscribe(designer, notifications::add)) {
            accounting.beforeBusinessPrompt(remote("event"), request -> {
                calls.add(request.arguments());
                throw new IllegalStateException("statistics unavailable");
            });
            accounting.beforeBusinessPrompt(remote("event"), request -> { throw new AssertionError("duplicate"); });
        }
        assertThat(calls).containsExactly("start SYS-001 000123", "continue SYS-001 000123");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().type()).isEqualTo("STORY_BINDING_FAILED");
        assertThat(designers.get(designer).state()).isEqualTo("RUNNING");
        assertThat(designers.messages(designer)).hasSize(1);
    }

    @Test void failedStartFallsBackToContinueOnceWithoutReportingARecoveredFailure() {
        String designer = fixture("start-fallback", true);
        List<String> calls = new CopyOnWriteArrayList<>();
        accounting.beforeBusinessPrompt(remote("start-fallback"), request -> {
            calls.add(request.arguments());
            if (request.arguments().startsWith("start ")) {
                throw new io.opencode.loopper.domain.SessionFailure("PLUGIN_FAILED", "story already active");
            }
            return new OpenCodeClient.CommandResult("continued-run", "continued");
        });
        accounting.beforeBusinessPrompt(remote("start-fallback"), request -> {
            throw new AssertionError("an existing accounting Session must not rebind");
        });

        assertThat(calls).containsExactly("start SYS-001 000123", "continue SYS-001 000123");
        assertThat(activity.list().stream()
                .filter(call -> designer.equals(call.designerSessionId()))
                .map(call -> call.operation() + ":" + call.state()))
                .containsExactlyInAnyOrder("start:FAILED", "continue:SUCCEEDED");
        assertThat(designers.messages(designer)).isEmpty();
    }

    @Test void parallelPromptsShareOneBeginAndSeparateSessionsKeepDistinctMessageIds() throws Exception {
        String designer = fixture("parallel-1", true);
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StoryAccountingCoordinator.CommandTransport transport = request -> {
            sent.incrementAndGet(); started.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return new OpenCodeClient.CommandResult("run", "ok");
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> accounting.beforeBusinessPrompt(remote("parallel-1"), transport));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var duplicate = pool.submit(() -> accounting.beforeBusinessPrompt(remote("parallel-1"), transport));
            release.countDown(); first.get(); duplicate.get();
        }
        jdbc.update("UPDATE designer_session SET external_session_id='parallel-2' WHERE id=?", designer);
        accounting.beforeBusinessPrompt(remote("parallel-2"), transport);
        assertThat(sent.get()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT operation FROM story_accounting_call ORDER BY started_at", String.class))
                .containsExactly("start", "start");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT message_id) FROM story_accounting_call", Integer.class)).isEqualTo(2);
    }

    @Test void historicalUnownedUsageDoesNotBecomeADesignerOrConsumeTheFirstStart() {
        String designer = fixture("old-repair", true);
        jdbc.update("UPDATE designer_session SET external_session_id=NULL WHERE id=?", designer);
        accounting.beforeBusinessPrompt(remote("old-repair"), request -> { throw new AssertionError("unowned repair"); });
        assertThat(mapper.findStoryAccountingOwner("old-repair")).get()
                .extracting(io.opencode.loopper.persistence.StoryAccountingOwnerRow::role).isEqualTo("UNTRACKED");
        jdbc.update("UPDATE designer_session SET external_session_id='actual-designer' WHERE id=?", designer);
        List<String> commands = new java.util.ArrayList<>();
        accounting.beforeBusinessPrompt(remote("actual-designer"), request -> {
            commands.add(request.arguments()); return new OpenCodeClient.CommandResult("run", "ok");
        });
        assertThat(commands).containsExactly("start SYS-001 000123");
        assertThat(mapper.findStoryAccountingSession("old-repair")).isEmpty();
    }

    @Test void oldExcludedRoleRecordsKeepHistoryWithoutSubmittingComplete() {
        String designer = fixture("current-designer", true);
        var binding = mapper.findDesignerStoryBinding(designer).orElseThrow();
        String now = Instant.now().toString();
        int ordinal = 0;
        for (String role : List.of("ROUTER", "JUDGE", "REVIEWER", "DECOMPOSER", "COMPILER", "ROLLING_PACKAGE_PLAN_V1")) {
            mapper.insertStoryAccountingSession(new io.opencode.loopper.persistence.StoryAccountingSessionRow(
                    UUID.randomUUID().toString(), binding.id(), designer, null, "old-" + role,
                    null, root.toString(), role, ++ordinal, "continue", true, "ACTIVE", null, now, now));
            accounting.afterTerminalStatus(remote("old-" + role), request -> { throw new AssertionError(role); });
        }
        assertThat(mapper.listRetiredStoryAccountingSessions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_accounting_call", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_accounting_session", Integer.class)).isEqualTo(6);
    }

    @Test void disabledSessionsAndUnworkedForksProduceNoRecords() {
        fixture("disabled", false);
        accounting.beforeBusinessPrompt(remote("disabled"), request -> { throw new AssertionError("disabled"); });
        accounting.beforeAbort(remote("unused-fork"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_accounting_call", Integer.class)).isZero();
    }

    @Test void failureNotificationsAndBusinessMessagesAppendWithoutOrdinalCollisions() throws Exception {
        String designer = fixture("messages", true);
        var binding = mapper.findDesignerStoryBinding(designer).orElseThrow();
        for (int i = 0; i < 20; i++) {
            String now = Instant.now().toString();
            mapper.insertStoryAccountingSession(new io.opencode.loopper.persistence.StoryAccountingSessionRow(
                    UUID.randomUUID().toString(), binding.id(), designer, null, "notification-" + i,
                    null, root.toString(), "REQUIREMENT_DESIGNER", i + 1, "continue", true, "ACTIVE", null, now, now));
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 20; i++) {
                int sequence = i;
                jobs.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    designers.appendMessage(designer, io.opencode.loopper.domain.DesignerActor.USER,
                            "business " + sequence, "PERSISTED");
                }));
                jobs.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                    accounting.afterTerminalStatus(remote("notification-" + sequence),
                            request -> { throw new IllegalStateException("simulated rejection"); });
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(15, TimeUnit.SECONDS);
        }
        assertThat(designers.messages(designer)).hasSize(40);
        assertThat(designers.messages(designer).stream().map(message -> message.ordinal()).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 40).boxed().toList());
    }

    @Test void appendedMessagesInvalidatePreviouslyReadMessagesInTheSameTransaction() {
        String designer = fixture("message-cache", true);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(designers.messages(designer)).isEmpty();
            var message = designers.appendMessage(designer, io.opencode.loopper.domain.DesignerActor.USER,
                    "business message", "PERSISTED");
            assertThat(message.ordinal()).isEqualTo(1);
            assertThat(designers.messages(designer)).containsExactly(message);
        });
    }

    @Test void backgroundAccountingCannotInvalidateAnActiveBusinessWriteSnapshot() throws Exception {
        String designer = fixture("snapshot", true);
        CountDownLatch businessRead = new CountDownLatch(1), accountingRequested = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var business = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                long version = designers.get(designer).version();
                businessRead.countDown();
                try { accountingRequested.await(2, TimeUnit.SECONDS); Thread.sleep(100); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return jdbc.update("UPDATE designer_session SET version=version+1 WHERE id=? AND version=?", designer, version);
            }));
            assertThat(businessRead.await(2, TimeUnit.SECONDS)).isTrue();
            var statistics = pool.submit(() -> {
                accountingRequested.countDown();
                accounting.beforeBusinessPrompt(remote("snapshot"), request -> new OpenCodeClient.CommandResult("run", "ok"));
            });
            assertThat(business.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            statistics.get(5, TimeUnit.SECONDS);
        }
        assertThat(mapper.findStoryAccountingSession("snapshot")).isPresent();
    }

    @Test void taskConfirmationAndRecoveryInheritTheSameChain() {
        var spec = new LoopSpec("v1", projectId, "Verify README", null,
                List.of(new LoopSpec.StageSpec("Check README", null, null, null,
                        List.of(new LoopSpec.VerifierSpec("FILE_EXISTS", null, "README.md", null, null, null, null)))),
                null, null, null, null);
        var draft = drafts.create(spec);
        String designer = fixture("inherit", true);
        jdbc.update("UPDATE designer_session SET loop_draft_id=? WHERE id=?", draft.id(), designer);
        var task = drafts.confirm(draft.id(), "story binding fixture");
        assertThat(mapper.findTaskStoryBinding(task.id())).isEqualTo(mapper.findDesignerStoryBinding(designer));
        tasks.cancel(task.id());
        var recovered = recoveries.create(task.id(), RecoveryMode.VERIFY_ONLY);
        assertThat(mapper.findTaskStoryBinding(recovered.taskId())).isEqualTo(mapper.findTaskStoryBinding(task.id()));
    }

    @Test void nextStartWaitsForTheRetiredSessionsPendingComplete() throws Exception {
        String designer = fixture("handoff-old", true);
        CountDownLatch completing = new CountDownLatch(1), release = new CountDownLatch(1);
        var calls = new CopyOnWriteArrayList<String>();
        try (var closer = new CoordinatorCloser(new StoryAccountingCoordinator(mapper, mock(TaskEventService.class),
                new TransactionTemplate(transactionManager))); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var coordinator = closer.value;
            coordinator.installTransport((remote, request) -> {
                calls.add(remote.id() + ":" + request.arguments());
                completing.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new OpenCodeClient.CommandResult("run-old", "completed");
            });
            coordinator.beforeBusinessPrompt(remote("handoff-old"), request -> new OpenCodeClient.CommandResult("run-old", "started"));
            jdbc.update("UPDATE designer_session SET external_session_id='handoff-new' WHERE id=?", designer);
            var next = pool.submit(() -> coordinator.beforeBusinessPrompt(remote("handoff-new"), request -> {
                calls.add("next start"); return new OpenCodeClient.CommandResult("run-new", "started");
            }));
            try {
                assertThat(completing.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(next.isDone()).isFalse();
                assertThat(calls).containsExactly("handoff-old:complete");
                coordinator.completeRetiredSessions(); // Repeated collection must share the same call.
                release.countDown(); next.get(3, TimeUnit.SECONDS);
                assertThat(calls).containsExactly("handoff-old:complete", "next start");
            } finally { release.countDown(); }
        }
    }

    @Test void explicitRetriesKeepOldFailuresAndCannotInterruptBusinessOrBeSentTwice() throws Exception {
        String designer = fixture("retry-retired", true);
        try (var closer = new CoordinatorCloser(new StoryAccountingCoordinator(mapper, mock(TaskEventService.class),
                new TransactionTemplate(transactionManager)))) {
            var coordinator = closer.value;
            StoryAccountingCoordinator.CommandTransport failed = request -> {
                throw new io.opencode.loopper.domain.SessionFailure("PLUGIN_FAILED", "platform unavailable");
            };
            coordinator.installTransport((remote, request) -> new OpenCodeClient.CommandResult("actual-run", "receipt"));
            coordinator.beforeBusinessPrompt(remote("retry-retired"), failed);
            var session = mapper.findStoryAccountingSession("retry-retired").orElseThrow();
            var failedContinue = mapper.findStoryAccountingCall(session.id(), "BEGIN").orElseThrow();
            String failedStartId = jdbc.queryForObject("""
                    SELECT id FROM story_accounting_call
                    WHERE accounting_session_id=? AND phase='BEGIN' AND operation='start'
                    """, String.class, session.id());
            assertThat(failedContinue.operation()).isEqualTo("continue");
            assertThat(activity.get(failedContinue.id()).retryAvailable()).isFalse();
            assertThatThrownBy(() -> coordinator.retry(failedContinue.id())).isInstanceOf(ConflictException.class)
                    .hasMessageContaining("业务或提问");
            jdbc.update("UPDATE designer_session SET state='COMPLETED',workflow_phase='COMPLETED' WHERE id=?", designer);
            coordinator.afterTerminalStatus(remote("retry-retired"), failed);
            var complete = mapper.findStoryAccountingCall(session.id(), "COMPLETE").orElseThrow();
            assertThat(activity.get(failedContinue.id()).retryAvailable()).isTrue();
            String newContinue = coordinator.retry(failedContinue.id());
            awaitCall(newContinue);
            assertThat(mapper.findStoryAccountingCallById(failedContinue.id()).orElseThrow()).isEqualTo(failedContinue);
            assertThat(mapper.findStoryAccountingCallById(newContinue).orElseThrow().operation()).isEqualTo("continue");
            assertThatThrownBy(() -> coordinator.retry(failedContinue.id())).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> coordinator.retry(newContinue)).isInstanceOf(ConflictException.class);
            String newComplete = coordinator.retry(complete.id());
            awaitCall(newComplete);
            assertThat(mapper.findStoryAccountingCallById(complete.id()).orElseThrow()).isEqualTo(complete);
            assertThat(jdbc.queryForList("SELECT operation || ':' || state FROM story_accounting_call ORDER BY rowid", String.class))
                    .containsExactly("start:FAILED", "continue:FAILED", "complete:FAILED", "continue:SUCCEEDED", "complete:SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT message_id) FROM story_accounting_call", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForList("SELECT retry_of FROM story_accounting_call WHERE retry_of IS NOT NULL ORDER BY rowid", String.class))
                    .containsExactly(failedStartId, failedContinue.id(), complete.id());
            assertThat(designers.get(designer).state()).isEqualTo("COMPLETED");
            coordinator.completeRetiredSessions();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_accounting_call", Integer.class)).isEqualTo(5);
        }
    }

    private void awaitCall(String id) throws InterruptedException {
        for (int i = 0; i < 100 && mapper.findStoryAccountingCallById(id).orElseThrow().finishedAt() == null; i++) Thread.sleep(20);
        assertThat(mapper.findStoryAccountingCallById(id).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }

    private String fixture(String external, boolean enabled) {
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        mapper.insertDesignerSession(new DesignerSessionRow(id, projectId, "RUNNING", "READ_ONLY", now, now, 0,
                null, "RUNNING", null, "DISCUSSING_REQUIREMENT", 0, 0));
        bindings.attachDesigner(id, new StoryBindingConfiguration(enabled, "SYS-001", "000123"));
        jdbc.update("UPDATE designer_session SET external_session_id=? WHERE id=?", external, id);
        return id;
    }
    private OpenCodeClient.OpenCodeSession remote(String id) { return new OpenCodeClient.OpenCodeSession(id, root); }
    private record CoordinatorCloser(StoryAccountingCoordinator value) implements AutoCloseable {
        @Override public void close() { value.close(); }
    }
}
