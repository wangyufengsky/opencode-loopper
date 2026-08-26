package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns positive termination proof and persistent cleanup evidence for Task writers. */
final class TaskWriterTerminationService {
    private final LoopperMapper mapper;
    private final TaskStateStore states;
    private final io.opencode.loopper.lifecycle.LifecycleTransitionService lifecycle;
    private final OpenCodeClient openCode;
    private final UsageInsightsService usage;
    private final DirectWorkspaceLeaseCoordinator leases;
    private final ProjectService projects;
    private final LoopperProperties defaults;
    private final TaskEventService events;
    private final ObjectMapper json;

    TaskWriterTerminationService(LoopperMapper mapper, TaskStateStore states,
                                 io.opencode.loopper.lifecycle.LifecycleTransitionService lifecycle,
                                 OpenCodeClient openCode, UsageInsightsService usage,
                                 DirectWorkspaceLeaseCoordinator leases, ProjectService projects,
                                 LoopperProperties defaults, TaskEventService events, ObjectMapper json) {
        this.mapper = mapper;
        this.states = states;
        this.lifecycle = lifecycle;
        this.openCode = openCode;
        this.usage = usage;
        this.leases = leases;
        this.projects = projects;
        this.defaults = defaults;
        this.events = events;
        this.json = json;
    }

    boolean confirmStopped(TaskRow task, ExecutionSessionRow session, Map<String, Object> evidence) {
        if (session == null || session.externalSessionId() == null) {
            evidence.put("abortRequested", false);
            return true;
        }
        evidence.put("abortRequested", true);
        if (task.worktreePath() == null) {
            evidence.put("abortSucceeded", false);
            evidence.put("abortError", "Task worktree path is unavailable");
            return false;
        }
        OpenCodeClient.OpenCodeSession remote;
        try {
            remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath()));
        } catch (RuntimeException invalidWorktree) {
            evidence.put("abortSucceeded", false);
            evidence.put("abortError", safeMessage(invalidWorktree));
            return false;
        }
        try {
            OpenCodeClient.AbortConfirmation confirmation = openCode.abortWithConfirmation(remote);
            evidence.put("abortSucceeded", true);
            evidence.put("abortConfirmation", confirmation.name());
            evidence.put("writerTerminationConfirmed", true);
            return true;
        } catch (RuntimeException abortFailure) {
            evidence.put("abortSucceeded", false);
            evidence.put("abortErrorCode", abortFailure instanceof SessionFailure failure
                    ? failure.code() : "SESSION_ABORT_FAILED");
            evidence.put("abortError", safeMessage(abortFailure));
        }
        try {
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            evidence.put("postAbortState", safeMessage(status.state()));
            boolean terminal = status.completed() || status.failed();
            evidence.put("writerTerminationConfirmed", terminal);
            return terminal;
        } catch (RuntimeException statusFailure) {
            evidence.put("postAbortStatusError", safeMessage(statusFailure));
            return false;
        }
    }

    boolean stopSessions(TaskRow task) {
        boolean allStopped = true;
        for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("cleanup", "terminal_task");
            boolean stopped = confirmStopped(task, session, evidence);
            AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
            StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
            if (stopped) {
                states.updateSession(states.sessionState(session, SessionState.ABORTED));
            } else {
                allStopped = false;
                states.updateSession(states.sessionState(session, SessionState.DISCONNECTED));
                recordUnconfirmed(task, stage, attempt, session,
                        "Task cancellation or terminal cleanup could not yet confirm its mutating Session stopped",
                        evidence);
            }
        }
        return allStopped && !hasUnconfirmedWriter(task.id());
    }

    boolean stopJudges(TaskRow task) {
        List<JudgeRunRow> active = mapper.activeJudgeRuns(task.id());
        if (active.isEmpty()) return true;
        if (task.worktreePath() == null) return false;
        Path worktree = Path.of(task.worktreePath());
        boolean allStopped = true;
        for (JudgeRunRow judge : active) {
            boolean stopped = judge.externalSessionId() == null;
            if (judge.externalSessionId() != null) {
                OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(judge.externalSessionId(), worktree);
                try {
                    openCode.abortWithConfirmation(remote);
                    stopped = true;
                } catch (SessionFailure ignoredAbortFailure) {
                    try {
                        OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
                        stopped = status.completed() || status.failed();
                    } catch (SessionFailure ignoredStatusFailure) {
                        stopped = false;
                    }
                }
            }
            if (!stopped) {
                allStopped = false;
                continue;
            }
            JudgeRunRow aborted = new JudgeRunRow(judge.id(), judge.taskId(), judge.attemptId(), judge.role(),
                    judge.ordinal(), judge.externalSessionId(), JudgeRunState.ABORTED.name(), judge.verdict(),
                    safeNullable(judge.reason()), judge.rawOutput(), judge.createdAt(), now(), judge.version(),
                    judge.responseMode(), judge.responseSchemaId());
            updateJudge(aborted);
            usage.collectTerminalJudgeUsage(task.id(), aborted.id());
        }
        return allStopped;
    }

    void retryDisconnectedSessions(TaskRow task) {
        retryUnconfirmedSessions(task, "explicit-cancellation-retry");
    }

    /**
     * Explicitly re-probes every locally active or historically unconfirmed writer.
     * A successful remote terminal observation is persisted even when the local
     * Session row was already terminal, because lease reconciliation consumes the
     * positive cleanup evidence rather than inferring safety from local state alone.
     */
    boolean retryUnconfirmedSessions(TaskRow task, String cleanupReason) {
        Set<String> unresolved = unresolvedSessionIds(task.id());
        boolean allStopped = true;
        for (ExecutionSessionRow session : mapper.listSessions(task.id())) {
            SessionState state;
            try { state = SessionState.valueOf(session.state()); }
            catch (RuntimeException unknownState) { allStopped = false; continue; }
            boolean confirmationRequired = unresolved.contains(session.id());
            if (state.terminal() && !confirmationRequired) continue;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("cleanup", cleanupReason);
            if (confirmStopped(task, session, evidence)) {
                if (!state.terminal()) {
                    states.updateSession(states.sessionState(session, SessionState.ABORTED));
                }
                recordConfirmed(task, session, "The remote Session was confirmed stopped", evidence);
                continue;
            }
            allStopped = false;
            if (!state.terminal() && state != SessionState.DISCONNECTED) {
                states.updateSession(states.sessionState(session, SessionState.DISCONNECTED));
            }
            if (!confirmationRequired) {
                AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
                StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
                recordUnconfirmed(task, stage, attempt, session,
                        "Explicit cleanup could not confirm the mutating Session stopped", evidence);
            }
        }
        return allStopped && !hasUnconfirmedWriter(task.id());
    }

    void recordUnconfirmed(TaskRow task, StageRow stage, AttemptRow attempt,
                           ExecutionSessionRow session, String message, Map<String, Object> evidence) {
        if (session == null) return;
        Map<String, Object> persistentEvidence = new LinkedHashMap<>(evidence);
        persistentEvidence.put("cleanupScheduled", true);
        persistentEvidence.put("cleanupLimit", Math.max(1, defaults.getAbortCleanupAttempts()));
        recordError(task, stage, attempt, session, "SESSION_ABORT_UNCONFIRMED", message, true, persistentEvidence);
        if (isAdmittedInPlace(task)) {
            leases.markWriterUnconfirmed(inPlaceRoot(task), task.id(), session.id(), message);
        }
        events.emit(task.id(), "session.cleanup_pending", Map.of("sessionId", session.id()));
    }

    void recordConfirmed(TaskRow task, ExecutionSessionRow session, String message, Map<String, Object> evidence) {
        AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
        StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
        recordError(task, stage, attempt, session, "SESSION_ABORT_CLEANUP_CONFIRMED", message, false, evidence);
    }

    boolean hasUnconfirmedWriter(String taskId) {
        if (mapper.listVerifierRuntimes(taskId).stream()
                .anyMatch(runtime -> List.of("STARTING", "RUNNING", "STOPPING", "DISCONNECTED")
                        .contains(runtime.state()))) return true;
        return !unresolvedSessionIds(taskId).isEmpty();
    }

    private Set<String> unresolvedSessionIds(String taskId) {
        Set<String> confirmed = mapper.listErrors(taskId).stream()
                .filter(error -> "SESSION_ABORT_CLEANUP_CONFIRMED".equals(error.code()) && error.sessionId() != null)
                .map(ErrorEventRow::sessionId).collect(java.util.stream.Collectors.toSet());
        return mapper.listErrors(taskId).stream()
                .filter(error -> "SESSION_ABORT_UNCONFIRMED".equals(error.code())
                        && error.sessionId() != null && !confirmed.contains(error.sessionId()))
                .map(ErrorEventRow::sessionId).collect(java.util.stream.Collectors.toSet());
    }

    private void updateJudge(JudgeRunRow row) {
        JudgeRunRow current = mapper.findJudgeRun(row.id())
                .orElseThrow(() -> new NotFoundException("Judge run not found: " + row.id()));
        if (current.state().equals(row.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateJudgeRun(row),
                    () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
        } else {
            lifecycle.transition(states.subject(LifecycleMachineType.JUDGE_RUN, row.id(), row.taskId()),
                    current.state(), row.state(), null, Map.of("role", row.role()),
                    () -> mapper.updateJudgeRun(row),
                    () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
        }
    }

    private void recordError(TaskRow task, StageRow stage, AttemptRow attempt, ExecutionSessionRow session,
                             String code, String message, boolean retryable, Map<String, ?> evidence) {
        mapper.insertError(new ErrorEventRow(UUID.randomUUID().toString(), task.id(), stage == null ? null : stage.id(),
                attempt == null ? null : attempt.id(), session == null ? null : session.id(),
                ErrorLayer.SESSION.name(), code, safeMessage(message), retryable, write(evidence), now()));
    }

    private boolean isAdmittedInPlace(TaskRow task) {
        return mapper.findTaskQueue(task.id())
                .map(row -> TaskQueueState.ADMITTED.name().equals(row.state())).orElse(false);
    }

    private Path inPlaceRoot(TaskRow task) { return Path.of(projects.get(task.projectId()).rootPath()); }
    private String safeNullable(String value) { return value == null ? null : safeMessage(value); }
    private static String safeMessage(Throwable failure) { return safeMessage(failure.getMessage()); }
    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return "Unknown writer termination failure";
        return value.substring(0, Math.min(value.length(), 4000));
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException(failure); }
    }
    private static String now() { return Instant.now().toString(); }
}
