package io.opencode.loopper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sole owner of execution-state transitions. In particular, only this class turns a
 * TaskFailure into FAILED; SessionFailure always closes its attempt then starts a fresh session.
 */
@Service
public class TaskService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final OpenCodeClient openCode;
    private final VerifierEngine verifiers;
    private final TaskEventService events;
    private final LoopperProperties defaults;

    public TaskService(LoopperMapper mapper, ObjectMapper json, ProjectService projects,
                       GitWorktreeManager worktrees, OpenCodeClient openCode, VerifierEngine verifiers,
                       TaskEventService events, LoopperProperties defaults) {
        this.mapper = mapper; this.json = json; this.projects = projects;
        this.worktrees = worktrees; this.openCode = openCode; this.verifiers = verifiers; this.events = events; this.defaults = defaults;
    }

    @Transactional
    public TaskRow createFromDraft(LoopDraftRow draft, String title) {
        var existing = mapper.findTaskByDraft(draft.id());
        if (existing.isPresent()) return existing.get();
        LoopSpec spec = readSpec(draft);
        ProjectRow project = projects.get(draft.projectId());
        String now = now();
        String taskId = UUID.randomUUID().toString();
        TaskRow task = new TaskRow(taskId, project.id(), draft.id(), normalizedTitle(title, draft.goal()), TaskState.PREPARING.name(),
                null, null, null, now, now, 0);
        mapper.insertTask(task);
        int ordinal = 0;
        for (LoopSpec.StageSpec stage : spec.stages()) {
            mapper.insertStage(new StageRow(UUID.randomUUID().toString(), taskId, ordinal++, stage.objective(),
                    write(stage.allowedPaths()), write(stage.forbiddenPaths()), write(stage.deliverables()), write(stage.verifiers()),
                    StageState.PENDING.name(), now, now, 0));
        }
        try {
            GitWorktreeManager.Worktree worktree = worktrees.create(Path.of(project.rootPath()), taskId);
            TaskRow prepared = new TaskRow(task.id(), task.projectId(), task.loopDraftId(), task.title(), TaskState.READY.name(),
                    worktree.path().toString(), worktree.branch(), worktree.baselineCommit(), task.createdAt(), now(), task.version());
            if (mapper.prepareTask(prepared) != 1) throw new ConflictException("TASK_VERSION_CONFLICT", "Task was updated concurrently");
            TaskRow ready = get(taskId);
            events.emit(taskId, "task.ready", Map.of("state", ready.state(), "branch", worktree.branch(), "worktreePath", worktree.path().toString()));
            return ready;
        } catch (TaskFailure failure) {
            failTask(task, failure.code(), failure.getMessage(), null, null, null);
            return get(taskId);
        }
    }

    public TaskRow get(String id) { return mapper.findTask(id).orElseThrow(() -> new NotFoundException("Task not found: " + id)); }
    public List<TaskRow> list() { return mapper.listTasks(); }
    public List<StageRow> stages(String taskId) { get(taskId); return mapper.listStages(taskId); }
    public List<AttemptRow> attempts(String taskId) { get(taskId); return mapper.listAttempts(taskId); }
    public List<ErrorEventRow> errors(String taskId) { get(taskId); return mapper.listErrors(taskId); }
    public List<VerificationResultRow> verifications(String attemptId) { return mapper.listVerifications(attemptId); }
    /** Append-only final-review history, including retries and raw model conclusions. */
    public List<JudgeRunRow> judges(String taskId) { get(taskId); return mapper.listJudgeRuns(taskId); }
    /** Immutable diff, verifier, and judge evidence retained independently of the worktree. */
    public List<TaskArtifactRow> artifacts(String taskId) { get(taskId); return mapper.listTaskArtifacts(taskId); }

    public VerifierEngine.DiffPreview diffPreview(String taskId, String path) {
        TaskRow task = get(taskId);
        if (path == null || path.isBlank()) {
            throw new BadRequestException("DIFF_PATH_INVALID", "Diff preview requires a file path");
        }
        boolean verified = false;
        boolean untracked = false;
        List<AttemptRow> attempts = mapper.listAttempts(taskId);
        for (int attemptIndex = attempts.size() - 1; attemptIndex >= 0 && !verified; attemptIndex--) {
            for (VerificationResultRow row : mapper.listVerifications(attempts.get(attemptIndex).id())) {
                if (!"GIT_DIFF".equalsIgnoreCase(row.type())) continue;
                try {
                    var evidence = json.readTree(row.evidenceJson());
                    if (evidence.path("changedPaths").isArray()) {
                        for (var item : evidence.path("changedPaths")) {
                            if (path.equals(item.asText())) { verified = true; break; }
                        }
                    }
                    if (verified && evidence.path("untrackedPaths").isArray()) {
                        for (var item : evidence.path("untrackedPaths")) {
                            if (path.equals(item.asText())) { untracked = true; break; }
                        }
                    }
                } catch (Exception ignored) {
                    // Unreadable historical evidence cannot authorize a file preview.
                }
            }
        }
        if (!verified) {
            throw new BadRequestException("DIFF_PATH_NOT_VERIFIED", "The requested file is not present in persisted GIT_DIFF evidence");
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new BadRequestException("WORKTREE_UNAVAILABLE", "Task worktree is unavailable");
        }
        try {
            return verifiers.previewDiff(Path.of(task.worktreePath()), task.baselineCommit(), path, untracked, Duration.ofSeconds(10));
        } catch (TaskFailure failure) {
            throw new BadRequestException(failure.code(), failure.getMessage());
        }
    }

    /** Applies time budgets before a monitor interprets an OpenCode status transition. */
    @Transactional
    public void enforceTimeouts(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.RUNNING.name().equals(task.state()) && !TaskState.JUDGING.name().equals(task.state())) return;
        LoopSpec spec = spec(task);
        Instant now = Instant.now();
        if (Instant.parse(task.createdAt()).plusSeconds(spec.limits().maxDurationSeconds()).isBefore(now)) {
            failTask(task, "TASK_DURATION_EXHAUSTED", "Task exceeded its maximum duration", null, null, null);
            return;
        }
        for (AttemptRow attempt : mapper.listAttempts(taskId)) {
            if (AttemptState.RUNNING.name().equals(attempt.state()) && Instant.parse(attempt.createdAt()).plusSeconds(spec.limits().attemptTimeoutSeconds()).isBefore(now)) {
                sessionFailed(taskId, attempt.id(), "SESSION_TIMEOUT", "Attempt exceeded its session timeout");
            }
        }
    }

    @Transactional
    public TaskRow start(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.FAILED.name().equals(task.state()) || TaskState.CANCELLED.name().equals(task.state()) || TaskState.SUCCEEDED.name().equals(task.state())) {
            throw new ConflictException("TASK_TERMINAL", "Cannot start a terminal task");
        }
        if (TaskState.PAUSED.name().equals(task.state())) return resume(taskId);
        if (!TaskState.READY.name().equals(task.state())) throw new ConflictException("TASK_ALREADY_ACTIVE", "Task is already active");
        try {
            ProjectRow project = projects.get(task.projectId());
            worktrees.requireExecutionWorkspace(Path.of(requireWorktree(task)), Path.of(project.rootPath()),
                    task.branchName(), task.baselineCommit());
            if (!openCode.healthy()) throw new TaskFailure("OPENCODE_UNAVAILABLE", "No compatible OpenCode runtime is available");
            updateTask(state(task, TaskState.RUNNING));
            StageRow stage = mapper.listStages(task.id()).stream()
                    .filter(s -> StageState.PENDING.name().equals(s.state()) || StageState.PAUSED.name().equals(s.state()))
                    .findFirst().orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Task has no runnable stage"));
            startNewAttempt(get(task.id()), stage, "Start stage: " + stage.objective());
        } catch (TaskFailure failure) {
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
        }
        return get(taskId);
    }

    /** Invoked by an OpenCode transport callback or a test harness. It never sets the task FAILED directly. */
    @Transactional
    public TaskRow sessionFailed(String taskId, String attemptId, String code, String message) {
        TaskRow task = get(taskId);
        AttemptRow attempt = mapper.findAttempt(attemptId).orElseThrow(() -> new NotFoundException("Attempt not found: " + attemptId));
        if (!task.id().equals(attempt.taskId())) throw new BadRequestException("ATTEMPT_TASK_MISMATCH", "Attempt does not belong to task");
        if (!TaskState.RUNNING.name().equals(task.state())) {
            throw new ConflictException("TASK_NOT_RUNNING", "A Session failure can be applied only to the currently running task");
        }
        if (!AttemptState.RUNNING.name().equals(attempt.state())) {
            throw new ConflictException("ATTEMPT_NOT_RUNNING", "A Session failure can be applied only to the currently running attempt");
        }
        StageRow stage = mapper.findStage(attempt.stageId()).orElseThrow(() -> new NotFoundException("Stage not found"));
        ExecutionSessionRow session = mapper.latestSessionForAttempt(attemptId)
                .filter(candidate -> SessionState.CREATING.name().equals(candidate.state()) || SessionState.RUNNING.name().equals(candidate.state()))
                .orElseThrow(() -> new ConflictException("SESSION_NOT_ACTIVE", "The attempt has no active Session to fail"));
        handleSessionFailure(task, stage, attempt, session, new SessionFailure(code, message));
        return get(taskId);
    }

    @Transactional
    public TaskRow verify(String taskId) {
        TaskRow initial = get(taskId);
        if (!TaskState.RUNNING.name().equals(initial.state()) && !TaskState.VERIFYING.name().equals(initial.state())) {
            throw new ConflictException("TASK_NOT_RUNNING", "Only a running task can be verified");
        }
        try {
            StageRow stage = mapper.listStages(taskId).stream().filter(s -> StageState.RUNNING.name().equals(s.state())).findFirst()
                    .orElseThrow(() -> new TaskFailure("STAGE_NOT_RUNNING", "No running stage is available for verification"));
            AttemptRow attempt = mapper.latestAttempt(stage.id()).orElseThrow(() -> new TaskFailure("ATTEMPT_MISSING", "No attempt is available for verification"));
            ExecutionSessionRow implementationSession = mapper.latestSessionForAttempt(attempt.id())
                    .orElseThrow(() -> new TaskFailure("SESSION_MISSING", "No implementation Session is available for verification"));
            if (implementationSession.externalSessionId() == null) {
                throw new ConflictException("SESSION_NOT_COMPLETED", "Verification requires a completed external Session");
            }
            OpenCodeClient.SessionStatus remoteStatus;
            try {
                remoteStatus = openCode.sessionStatus(new OpenCodeClient.OpenCodeSession(
                        implementationSession.externalSessionId(), Path.of(requireWorktree(initial))));
            } catch (SessionFailure unavailableStatus) {
                throw new ConflictException("SESSION_STATUS_UNAVAILABLE",
                        "Verification cannot start until the implementation Session terminal state is confirmed");
            }
            if (!remoteStatus.completed()) {
                throw new ConflictException("SESSION_NOT_COMPLETED",
                        "Verification cannot run while the implementation Session is " + safeMessage(remoteStatus.state()));
            }
            updateTask(state(initial, TaskState.VERIFYING));
            if (SessionState.RUNNING.name().equals(implementationSession.state())) {
                updateSession(sessionState(implementationSession, SessionState.COMPLETED));
            }
            LoopSpec spec = spec(initial);
            List<LoopSpec.VerifierSpec> verifierSpecs = read(stage.verifiersJson(), new TypeReference<>() {});
            boolean passed = true;
            String failure = "";
            Duration timeout = Duration.ofSeconds(spec.limits().verifierTimeoutSeconds());
            for (int i = 0; i < verifierSpecs.size(); i++) {
                VerifierOutcome outcome;
                try {
                    outcome = verifiers.verify(Path.of(requireWorktree(initial)), initial.baselineCommit(), verifierSpecs.get(i), timeout);
                } catch (TaskFailure knownFailure) {
                    throw knownFailure;
                } catch (RuntimeException unexpectedFailure) {
                    // Never strand a Task in RUNNING when a verifier cannot be
                    // evaluated. Unknown verifier faults cross the task-fatal boundary.
                    throw new TaskFailure("VERIFIER_RUNTIME_ERROR",
                            "Verifier could not be evaluated safely: " + safeMessage(unexpectedFailure));
                }
                mapper.insertVerification(new VerificationResultRow(UUID.randomUUID().toString(), attempt.id(), i, outcome.type(), outcome.state().name(),
                        outcome.summary(), write(outcome.evidence()), now()));
                if (outcome.state() != VerificationState.PASS) { passed = false; failure = outcome.summary(); }
            }
            if (passed) completeStage(initial, stage, attempt);
            else retryAfterVerificationFailure(initial, stage, attempt, failure, spec);
        } catch (TaskFailure failure) {
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
        }
        return get(taskId);
    }

    @Transactional
    public TaskRow pause(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.RUNNING.name().equals(task.state()) || TaskState.VERIFYING.name().equals(task.state()) || TaskState.RETRY_WAIT.name().equals(task.state())) {
            for (StageRow stage : mapper.listStages(taskId)) {
                if (StageState.RUNNING.name().equals(stage.state())) updateStage(stageState(stage, StageState.PAUSED));
            }
            updateTask(state(task, TaskState.PAUSED));
            events.emit(taskId, "task.paused", Map.of("state", TaskState.PAUSED.name()));
        }
        return get(taskId);
    }

    @Transactional
    public TaskRow resume(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.PAUSED.name().equals(task.state()) && !TaskState.WAITING_INPUT.name().equals(task.state())) throw new ConflictException("TASK_NOT_PAUSED", "Task is not paused");
        if (TaskState.WAITING_INPUT.name().equals(task.state())) throw new ConflictException("TASK_WAITING_INPUT", "A waiting task needs an explicit revised LoopSpec or judge decision");
        StageRow stage = mapper.listStages(taskId).stream().filter(s -> StageState.PAUSED.name().equals(s.state()) || StageState.PENDING.name().equals(s.state())).findFirst()
                .orElseThrow(() -> new ConflictException("STAGE_NOT_PAUSED", "Task has no paused stage"));
        updateTask(state(task, TaskState.RUNNING));
        updateStage(stageState(stage, StageState.RUNNING));
        if (mapper.activeSessions(taskId).isEmpty()) startNewAttempt(get(taskId), mapper.findStage(stage.id()).orElse(stage), "Resume stage: " + stage.objective());
        events.emit(taskId, "task.resumed", Map.of("state", TaskState.RUNNING.name()));
        return get(taskId);
    }

    @Transactional
    public TaskRow cancel(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.valueOf(task.state()).terminal()) return task;
        abortSessions(task);
        abortJudgeSessions(task);
        for (AttemptRow attempt : mapper.listAttempts(taskId)) {
            if (AttemptState.RUNNING.name().equals(attempt.state())) updateAttempt(finish(attempt, AttemptState.CANCELLED, "CANCELLED", "Task cancelled"));
        }
        updateTask(state(get(taskId), TaskState.CANCELLED));
        events.emit(taskId, "task.cancelled", Map.of("state", TaskState.CANCELLED.name()));
        return get(taskId);
    }

    @Transactional
    public void recoverAfterRestart() {
        for (TaskRow task : mapper.listRecoverableTasks()) {
            if (TaskState.PREPARING.name().equals(task.state())) {
                // Preparation has no resumable Session contract or confirmed managed
                // worktree. Treat this as task-level state corruption, not a retryable
                // Session fault.
                failTask(task, "PREPARATION_INTERRUPTED",
                        "Application restart interrupted task preparation before a managed worktree was recorded",
                        null, null, null);
                continue;
            }
            if (TaskState.JUDGING.name().equals(task.state())) {
                // Judge transports are session-scoped too.  Retain the broken row and create a
                // bounded fresh read-only session rather than turning a restart into TASK_ERROR.
                for (JudgeRunRow judge : mapper.activeJudgeRuns(task.id())) {
                    handleJudgeSessionFailure(task, judge, new SessionFailure("JUDGE_RUNTIME_RESTART", "Application restart disconnected the previous judge session"));
                }
                continue;
            }
            boolean continuationSafe = true;
            StageRow unsafeStage = null;
            AttemptRow unsafeAttempt = null;
            ExecutionSessionRow unsafeSession = null;
            for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
                Map<String, Object> recoveryEvidence = new LinkedHashMap<>();
                recoveryEvidence.put("recovery", "fresh_session");
                recoveryEvidence.put("abortRequested", session.externalSessionId() != null);
                boolean remoteTerminationConfirmed = session.externalSessionId() == null;
                if (session.externalSessionId() != null && task.worktreePath() != null) {
                    OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                            session.externalSessionId(), Path.of(task.worktreePath()));
                    try {
                        openCode.abort(remote);
                        recoveryEvidence.put("abortSucceeded", true);
                        remoteTerminationConfirmed = true;
                    } catch (RuntimeException abortFailure) {
                        recoveryEvidence.put("abortSucceeded", false);
                        recoveryEvidence.put("abortErrorCode", abortFailure instanceof SessionFailure sessionFailure
                                ? sessionFailure.code() : "SESSION_ABORT_FAILED");
                        recoveryEvidence.put("abortError", safeMessage(abortFailure));
                        // A failed abort request is not proof that the old mutating
                        // Session stopped. Continue only if a separate status read
                        // positively observes a terminal state.
                        try {
                            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
                            recoveryEvidence.put("postAbortState", safeMessage(status.state()));
                            remoteTerminationConfirmed = status.completed() || status.failed();
                        } catch (RuntimeException statusFailure) {
                            recoveryEvidence.put("postAbortStatusError", safeMessage(statusFailure));
                        }
                    }
                }
                updateSession(sessionState(session, SessionState.DISCONNECTED));
                AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
                StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
                if (!remoteTerminationConfirmed) {
                    continuationSafe = false;
                    unsafeStage = stage;
                    unsafeAttempt = attempt;
                    unsafeSession = session;
                    recoveryEvidence.put("continuationBlocked", true);
                }
                if (attempt != null && AttemptState.RUNNING.name().equals(attempt.state())) {
                    updateAttempt(finish(attempt, AttemptState.SESSION_ERROR, "RUNTIME_RESTART",
                            "Application restart disconnected the previous session"));
                }
                recordError(task, stage, attempt, session, ErrorLayer.SESSION, "RUNTIME_RESTART",
                        "Application restart disconnected the previous session", true,
                        recoveryEvidence);
                if (!remoteTerminationConfirmed) {
                    recordAbortUnconfirmed(task, stage, attempt, session,
                            "The previous mutating Session could not be confirmed stopped after restart",
                            recoveryEvidence);
                }
            }
            // A crash can occur after the remote Session completed but before a verifier
            // transaction finished. Such an Attempt has no active Session row to visit above,
            // yet it still must not remain RUNNING across process lifetimes.
            for (AttemptRow attempt : mapper.listAttempts(task.id())) {
                if (!AttemptState.RUNNING.name().equals(attempt.state())) continue;
                StageRow stage = mapper.findStage(attempt.stageId()).orElse(null);
                ExecutionSessionRow session = mapper.latestSessionForAttempt(attempt.id()).orElse(null);
                updateAttempt(finish(attempt, AttemptState.SESSION_ERROR, "RUNTIME_RESTART",
                        "Application restart interrupted the in-flight attempt"));
                recordError(task, stage, attempt, session, ErrorLayer.SESSION, "RUNTIME_RESTART",
                        "Application restart interrupted the in-flight attempt", true,
                        Map.of("recovery", "fresh_session"));
            }

            if (!continuationSafe) {
                failTask(get(task.id()), "SESSION_ABORT_UNCONFIRMED",
                        "The previous mutating Session could not be confirmed stopped after restart; refusing to create an overlapping Session",
                        unsafeStage, unsafeAttempt, unsafeSession);
                continue;
            }

            StageRow stage = mapper.listStages(task.id()).stream()
                    .filter(candidate -> StageState.RUNNING.name().equals(candidate.state()))
                    .findFirst().orElse(null);
            if (stage == null) {
                failTask(get(task.id()), "RECOVERY_STAGE_MISSING",
                        "Application restart found no active stage to continue", null, null, null);
                continue;
            }
            LoopSpec spec = spec(get(task.id()));
            if (mapper.countSessionErrorsForStage(stage.id()) >= spec.limits().sessionErrorLimit()) {
                failTask(get(task.id()), "SESSION_RETRY_EXHAUSTED",
                        "Application restart exhausted the configured session retry limit", stage, null, null);
                continue;
            }

            // A restart-disconnected Session is the same retryable boundary as any
            // transport loss: preserve its evidence, then continue the loop with a
            // fresh Attempt/Session while budgets remain.
            if (!TaskState.RUNNING.name().equals(get(task.id()).state())) {
                updateTask(state(get(task.id()), TaskState.RUNNING));
            }
            startNewAttempt(get(task.id()), stage,
                    "Application restart disconnected the previous session. Continue the same stage in a fresh session.");
            TaskRow recovered = get(task.id());
            events.emit(task.id(), "task.recovered", Map.of("state", recovered.state(), "reason", "restart",
                    "recovery", "fresh_session"));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { recoverAfterRestart(); }

    private void startNewAttempt(TaskRow task, StageRow inputStage, String prompt) {
        TaskRow freshTask = get(task.id());
        LoopSpec spec = spec(freshTask);
        StageRow stage = mapper.findStage(inputStage.id()).orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Stage disappeared"));
        if (mapper.countAttemptsForTask(freshTask.id()) >= spec.limits().maxTaskAttempts() || mapper.countAttemptsForStage(stage.id()) >= spec.limits().maxStageAttempts()) {
            failTask(freshTask, "ATTEMPT_LIMIT_EXHAUSTED", "The configured attempt limit was reached", stage, null, null);
            return;
        }
        if (!TaskState.RUNNING.name().equals(get(freshTask.id()).state())) return;
        if (!StageState.RUNNING.name().equals(stage.state())) updateStage(stageState(stage, StageState.RUNNING));
        int ordinal = mapper.countAttemptsForStage(stage.id()) + 1;
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), ordinal, AttemptState.RUNNING.name(), null, null, now(), null, 0);
        mapper.insertAttempt(attempt);
        ExecutionSessionRow session = new ExecutionSessionRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), attempt.id(), null,
                SessionState.CREATING.name(), now(), null, 0);
        mapper.insertSession(session);
        try {
            Path worktree = Path.of(requireWorktree(freshTask));
            OpenCodeClient.OpenCodeSession remote = openCode.createSession(worktree, freshTask.title(), model(spec));
            ExecutionSessionRow running = new ExecutionSessionRow(session.id(), session.taskId(), session.stageId(), session.attemptId(), remote.id(),
                    SessionState.RUNNING.name(), session.createdAt(), null, session.version());
            updateSession(running);
            openCode.promptAsync(remote, promptWithBoundaries(spec, stage, prompt));
            events.emit(freshTask.id(), "session.started", Map.of("attemptId", attempt.id(), "sessionId", session.id(), "externalSessionId", remote.id(), "stageId", stage.id()));
        } catch (SessionFailure failure) {
            handleSessionFailure(freshTask, stage, attempt, session, failure);
        } catch (RuntimeException exception) {
            handleSessionFailure(freshTask, stage, attempt, session,
                    new SessionFailure("SESSION_RUNTIME_ERROR", safeMessage(exception)));
        }
    }

    private void handleSessionFailure(TaskRow task, StageRow stage, AttemptRow inputAttempt, ExecutionSessionRow inputSession, SessionFailure failure) {
        TaskRow currentTask = get(task.id());
        if (!TaskState.RUNNING.name().equals(currentTask.state())) return;
        AttemptRow attempt = mapper.findAttempt(inputAttempt.id()).orElse(inputAttempt);
        if (!AttemptState.RUNNING.name().equals(attempt.state())) return;
        ExecutionSessionRow session = inputSession == null ? null : mapper.findSession(inputSession.id()).orElse(inputSession);
        if (session != null && !SessionState.CREATING.name().equals(session.state()) && !SessionState.RUNNING.name().equals(session.state())) return;
        Map<String, Object> recoveryEvidence = new LinkedHashMap<>();
        recoveryEvidence.put("recovery", "fresh_session");
        recoveryEvidence.put("originalFailureCode", failure.code());
        boolean stopped = confirmStoppedBeforeRetry(currentTask, session, recoveryEvidence);
        if (session != null) updateSession(sessionState(session, stopped ? SessionState.FAILED : SessionState.DISCONNECTED));
        if (AttemptState.RUNNING.name().equals(attempt.state())) updateAttempt(finish(attempt, AttemptState.SESSION_ERROR, failure.code(), failure.getMessage()));
        recordError(task, stage, attempt, session, ErrorLayer.SESSION, failure.code(), failure.getMessage(), stopped, recoveryEvidence);
        if (!stopped) {
            recordAbortUnconfirmed(currentTask, stage, attempt, session,
                    "The failed mutating Session could not be confirmed stopped",
                    recoveryEvidence);
            failTask(get(task.id()), "SESSION_ABORT_UNCONFIRMED",
                    "The failed mutating Session could not be confirmed stopped; refusing to create an overlapping Session",
                    stage, attempt, session);
            return;
        }
        LoopSpec spec = spec(currentTask);
        if (mapper.countSessionErrorsForStage(stage.id()) >= spec.limits().sessionErrorLimit()) {
            failTask(get(task.id()), "SESSION_RETRY_EXHAUSTED", "Session error retry limit reached: " + failure.getMessage(), stage, attempt, session);
            return;
        }
        if (mapper.countAttemptsForTask(task.id()) >= spec.limits().maxTaskAttempts() || mapper.countAttemptsForStage(stage.id()) >= spec.limits().maxStageAttempts()) {
            failTask(get(task.id()), "ATTEMPT_LIMIT_EXHAUSTED", "Task cannot create another recovery attempt", stage, attempt, session);
            return;
        }
        updateTask(state(get(task.id()), TaskState.RETRY_WAIT));
        events.emit(task.id(), "session.failed", Map.of("attemptId", attempt.id(), "code", failure.code(), "recovery", "new_session"));
        updateTask(state(get(task.id()), TaskState.RUNNING));
        startNewAttempt(get(task.id()), stage, "Previous session failed: " + failure.getMessage() + ". Continue the same stage.");
    }

    /**
     * A retry may write the same worktree only after the previous mutating
     * Session is positively stopped. An abort response is sufficient; if that
     * transport fails, a separate terminal status observation is required.
     */
    private boolean confirmStoppedBeforeRetry(TaskRow task, ExecutionSessionRow session, Map<String, Object> evidence) {
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
            openCode.abort(remote);
            evidence.put("abortSucceeded", true);
            return true;
        } catch (RuntimeException abortFailure) {
            evidence.put("abortSucceeded", false);
            evidence.put("abortErrorCode", abortFailure instanceof SessionFailure sessionFailure
                    ? sessionFailure.code() : "SESSION_ABORT_FAILED");
            evidence.put("abortError", safeMessage(abortFailure));
        }
        try {
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            evidence.put("postAbortState", safeMessage(status.state()));
            return status.completed() || status.failed();
        } catch (RuntimeException statusFailure) {
            evidence.put("postAbortStatusError", safeMessage(statusFailure));
            return false;
        }
    }

    /**
     * Retries only the remote abort obligation for a terminal Task. It never
     * changes the Task/Attempt state and therefore cannot create another writer.
     * The error log is the persistent retry counter and survives application
     * restarts; the monitor stops after the configured bound.
     */
    @Transactional
    public void retrySessionCleanup(String sessionId) {
        ExecutionSessionRow session = mapper.findSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));
        if (!SessionState.DISCONNECTED.name().equals(session.state())) return;
        int limit = Math.max(1, defaults.getAbortCleanupAttempts());
        int completedAttempts = mapper.countAbortCleanupAttempts(session.id());
        if (completedAttempts >= limit) return;

        TaskRow task = get(session.taskId());
        AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
        StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
        int attemptNumber = completedAttempts + 1;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("cleanupAttempt", attemptNumber);
        evidence.put("cleanupLimit", limit);
        boolean stopped = confirmStoppedBeforeRetry(task, session, evidence);
        if (stopped) {
            updateSession(sessionState(session, SessionState.ABORTED));
            recordError(task, stage, attempt, session, ErrorLayer.SESSION,
                    "SESSION_ABORT_CLEANUP_CONFIRMED",
                    "The remote Session was confirmed stopped by bounded cleanup", false, evidence);
            events.emit(task.id(), "session.cleanup_confirmed",
                    Map.of("sessionId", session.id(), "attempt", attemptNumber));
            return;
        }

        boolean exhausted = attemptNumber >= limit;
        recordError(task, stage, attempt, session, ErrorLayer.SESSION,
                exhausted ? "SESSION_ABORT_CLEANUP_EXHAUSTED" : "SESSION_ABORT_CLEANUP_RETRY",
                exhausted
                        ? "The remote Session could not be confirmed stopped within the cleanup limit"
                        : "The remote Session is still unconfirmed; cleanup will retry",
                !exhausted, evidence);
        events.emit(task.id(), exhausted ? "session.cleanup_exhausted" : "session.cleanup_retry",
                Map.of("sessionId", session.id(), "attempt", attemptNumber, "limit", limit));
    }

    private void retryAfterVerificationFailure(TaskRow task, StageRow stage, AttemptRow attempt, String message, LoopSpec spec) {
        updateAttempt(finish(attempt, AttemptState.VERIFICATION_FAILED, "VERIFICATION_FAILED", message));
        recordError(task, stage, attempt, mapper.latestSessionForAttempt(attempt.id()).orElse(null), ErrorLayer.VERIFICATION,
                "VERIFICATION_FAILED", message, true, Map.of());
        if (mapper.countAttemptsForTask(task.id()) >= spec.limits().maxTaskAttempts() || mapper.countAttemptsForStage(stage.id()) >= spec.limits().maxStageAttempts()) {
            failTask(get(task.id()), "ATTEMPT_LIMIT_EXHAUSTED", "Verifier failures exhausted configured attempts", stage, attempt, null);
            return;
        }
        updateTask(state(get(task.id()), TaskState.RETRY_WAIT));
        events.emit(task.id(), "verification.failed", Map.of("attemptId", attempt.id(), "recovery", "next_attempt", "summary", message));
        updateTask(state(get(task.id()), TaskState.RUNNING));
        startNewAttempt(get(task.id()), stage, "Verification failed: " + message + ". Fix the evidence and retry the current stage.");
    }

    private void completeStage(TaskRow task, StageRow stage, AttemptRow attempt) {
        updateAttempt(finish(attempt, AttemptState.SUCCEEDED, null, "所有确定性验证均已通过"));
        updateStage(stageState(stage, StageState.SUCCEEDED));
        StageRow next = mapper.listStages(task.id()).stream().filter(s -> StageState.PENDING.name().equals(s.state())).findFirst().orElse(null);
        if (next == null) {
            updateTask(state(get(task.id()), TaskState.JUDGING));
            captureFinalEvidence(get(task.id()), attempt);
            launchRequiredJudges(get(task.id()), attempt);
        } else {
            updateTask(state(get(task.id()), TaskState.RUNNING));
            startNewAttempt(get(task.id()), next, "Start next stage: " + next.objective());
        }
    }

    /**
     * Polls only final-review sessions.  A judge transport/model problem is recorded as a
     * SESSION error and retried with a fresh judge row; it never enters the normal execution
     * attempt recovery path and never turns the task FAILED by itself.
     */
    @Transactional
    public void pollJudges(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        enforceTimeouts(taskId);
        if (!TaskState.JUDGING.name().equals(get(taskId).state())) return;
        for (JudgeRunRow judge : mapper.activeJudgeRuns(taskId)) {
            if (!TaskState.JUDGING.name().equals(get(taskId).state())) break;
            pollJudge(get(taskId), judge);
        }
        if (TaskState.JUDGING.name().equals(get(taskId).state())) evaluateJudgeDecision(get(taskId));
    }

    private void launchRequiredJudges(TaskRow task, AttemptRow finalAttempt) {
        for (String role : List.of("REQUIREMENT", "RISK")) {
            if (mapper.latestJudgeRun(task.id(), role).isEmpty()) launchJudge(task, finalAttempt, role);
        }
        if (TaskState.JUDGING.name().equals(get(task.id()).state())) {
            events.emit(task.id(), "task.judging", Map.of("state", TaskState.JUDGING.name(), "judges", List.of("REQUIREMENT", "RISK")));
        }
    }

    private void launchJudge(TaskRow inputTask, AttemptRow finalAttempt, String role) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        LoopSpec spec = spec(task);
        if (mapper.countJudgeSessionErrors(task.id(), role) >= spec.limits().sessionErrorLimit()) {
            waitForJudgeInput(task, finalAttempt, null, "JUDGE_SESSION_RETRY_EXHAUSTED",
                    role + " Judge exhausted its configured session retry limit");
            return;
        }
        JudgeRunRow judge = new JudgeRunRow(UUID.randomUUID().toString(), task.id(), finalAttempt.id(), role,
                mapper.nextJudgeOrdinal(task.id(), role), null, "CREATING", null, null, null, now(), null, 0);
        mapper.insertJudgeRun(judge);
        persistArtifact(task, finalAttempt.id(), judge.id(), "JUDGE_LOG_METADATA", role.toLowerCase() + "-judge-start.json",
                "application/json", write(Map.of("role", role, "state", "CREATING", "readOnly", true)),
                Map.of("source", "judge-session", "readOnly", true));
        try {
            Path worktree = Path.of(requireWorktree(task));
            OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(worktree, roleTitle(role), model(spec));
            JudgeRunRow running = judgeState(judge, remote.id(), "RUNNING", null, null, null, null);
            updateJudge(running);
            openCode.promptAsync(remote, judgePrompt(task, finalAttempt, role));
            events.emit(task.id(), "judge.started", Map.of("judgeRunId", judge.id(), "role", role, "externalSessionId", remote.id(), "readOnly", true));
        } catch (SessionFailure failure) {
            handleJudgeSessionFailure(task, judge, failure);
        } catch (RuntimeException exception) {
            handleJudgeSessionFailure(task, judge, new SessionFailure("JUDGE_SESSION_RUNTIME_ERROR", safeMessage(exception)));
        }
    }

    private void pollJudge(TaskRow inputTask, JudgeRunRow inputJudge) {
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (!"RUNNING".equals(judge.state()) || judge.externalSessionId() == null) return;
        try {
            long timeoutSeconds = spec(inputTask).limits().attemptTimeoutSeconds();
            if (Instant.parse(judge.createdAt()).plusSeconds(timeoutSeconds).isBefore(Instant.now())) {
                handleJudgeSessionFailure(inputTask, judge, new SessionFailure("JUDGE_TIMEOUT", "Judge exceeded its configured session timeout"));
                return;
            }
            OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(judge.externalSessionId(), Path.of(requireWorktree(inputTask)));
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                String message = status.detail() == null || status.detail().isBlank()
                        ? "OpenCode judge session ended in " + status.state() : status.detail();
                handleJudgeSessionFailure(inputTask, judge, new SessionFailure("JUDGE_SESSION_" + status.state(), message));
                return;
            }
            if (status.completed()) completeJudge(inputTask, judge, openCode.sessionOutput(remote));
        } catch (SessionFailure failure) {
            handleJudgeSessionFailure(inputTask, judge, failure);
        } catch (RuntimeException exception) {
            // Optimistic-lock races with cancel/pause are not reclassified as an execution fault.
            if (TaskState.JUDGING.name().equals(get(inputTask.id()).state())) {
                handleJudgeSessionFailure(inputTask, judge, new SessionFailure("JUDGE_STATUS_RUNTIME_ERROR", safeMessage(exception)));
            }
        }
    }

    private void completeJudge(TaskRow inputTask, JudgeRunRow inputJudge, String rawOutput) {
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (!"RUNNING".equals(judge.state())) return;
        JudgeDecision decision = parseJudgeDecision(rawOutput);
        String verdict = decision.verdict();
        String reason = decision.reason();
        if (verdict == null) {
            verdict = "UNPARSEABLE";
            reason = decision.parseError();
        }
        JudgeRunRow completed = judgeState(judge, judge.externalSessionId(), "COMPLETED", verdict, reason, rawOutput, now());
        updateJudge(completed);
        persistArtifact(inputTask, judge.attemptId(), judge.id(), "JUDGE_RESULT", judge.role().toLowerCase() + "-judge-result.txt",
                "text/plain", rawOutput == null ? "" : rawOutput,
                Map.of("role", judge.role(), "verdict", verdict, "reason", reason, "state", "COMPLETED"));
        events.emit(inputTask.id(), "judge.completed", Map.of("judgeRunId", judge.id(), "role", judge.role(), "verdict", verdict));
    }

    private void handleJudgeSessionFailure(TaskRow inputTask, JudgeRunRow inputJudge, SessionFailure failure) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (!"CREATING".equals(judge.state()) && !"RUNNING".equals(judge.state())) return;
        JudgeRunRow failed = judgeState(judge, judge.externalSessionId(), "SESSION_ERROR", null, safeMessage(failure.getMessage()), null, now());
        updateJudge(failed);
        persistArtifact(task, judge.attemptId(), judge.id(), "JUDGE_LOG_METADATA", judge.role().toLowerCase() + "-judge-session-error.json",
                "application/json", write(Map.of("role", judge.role(), "code", failure.code(), "message", safeMessage(failure.getMessage()), "state", "SESSION_ERROR")),
                Map.of("source", "judge-session", "retryable", true));
        AttemptRow attempt = mapper.findAttempt(judge.attemptId()).orElse(null);
        recordError(task, null, attempt, null, ErrorLayer.SESSION, failure.code(), failure.getMessage(), true,
                Map.of("judgeRunId", judge.id(), "judgeRole", judge.role(), "judgeSession", true));
        LoopSpec spec = spec(task);
        if (mapper.countJudgeSessionErrors(task.id(), judge.role()) >= spec.limits().sessionErrorLimit()) {
            waitForJudgeInput(task, attempt, judge, "JUDGE_SESSION_RETRY_EXHAUSTED",
                    judge.role() + " Judge exhausted its configured session retry limit: " + safeMessage(failure.getMessage()));
            return;
        }
        events.emit(task.id(), "judge.session_failed", Map.of("judgeRunId", judge.id(), "role", judge.role(), "code", failure.code(), "recovery", "fresh_read_only_session"));
        if (attempt != null) launchJudge(task, attempt, judge.role());
    }

    private void evaluateJudgeDecision(TaskRow task) {
        JudgeRunRow requirement = mapper.latestJudgeRun(task.id(), "REQUIREMENT").orElse(null);
        JudgeRunRow risk = mapper.latestJudgeRun(task.id(), "RISK").orElse(null);
        if (requirement == null || risk == null || !"COMPLETED".equals(requirement.state()) || !"COMPLETED".equals(risk.state())) return;
        if (!"PASS".equals(requirement.verdict()) || !"PASS".equals(risk.verdict())) {
            String code = !requirement.verdict().equals(risk.verdict()) ? "JUDGE_CONFLICT" : "JUDGE_REVIEW_NOT_APPROVED";
            String message = "Requirement Judge=" + requirement.verdict() + ": " + safeMessage(requirement.reason())
                    + " | Risk Judge=" + risk.verdict() + ": " + safeMessage(risk.reason());
            AttemptRow attempt = mapper.findAttempt(requirement.attemptId()).orElse(null);
            waitForJudgeInput(task, attempt, null, code, message);
            return;
        }
        updateTask(state(get(task.id()), TaskState.SUCCEEDED));
        events.emit(task.id(), "task.succeeded", Map.of("state", TaskState.SUCCEEDED.name(), "judges", List.of("REQUIREMENT", "RISK")));
    }

    private void waitForJudgeInput(TaskRow inputTask, AttemptRow attempt, JudgeRunRow judge, String code, String message) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        abortJudgeSessions(task);
        // Final review is a verification outcome, not a field-validation issue and not a
        // terminal task fault.  Keeping it visible in the existing verification panel makes
        // the WAITING_INPUT reason discoverable without violating task/session error layering.
        recordError(task, null, attempt, null, ErrorLayer.VERIFICATION, code, message, false,
                Map.of("judgeRunId", judge == null ? "" : judge.id(), "judgeRole", judge == null ? "" : judge.role(), "resolution", "WAITING_INPUT"));
        updateTask(state(get(task.id()), TaskState.WAITING_INPUT));
        events.emit(task.id(), "task.judge_waiting_input", Map.of("state", TaskState.WAITING_INPUT.name(), "code", code, "message", safeMessage(message)));
    }

    private void captureFinalEvidence(TaskRow task, AttemptRow attempt) {
        List<VerificationResultRow> verificationRows = mapper.listVerifications(attempt.id());
        persistArtifact(task, attempt.id(), null, "VERIFICATION_SUMMARY", "verification-summary.json", "application/json",
                write(Map.of("attemptId", attempt.id(), "allPassed", verificationRows.stream().allMatch(row -> VerificationState.PASS.name().equals(row.state())), "results", verificationRows)),
                Map.of("source", "deterministic-verifier", "count", verificationRows.size()));
        String diff;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "opencode-session-diff");
        try {
            ExecutionSessionRow session = mapper.latestSessionForAttempt(attempt.id()).orElse(null);
            if (session == null || session.externalSessionId() == null) throw new SessionFailure("DIFF_SESSION_MISSING", "No implementation session is available for diff capture");
            diff = openCode.diff(new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(requireWorktree(task))));
            metadata.put("available", true);
        } catch (SessionFailure failure) {
            diff = write(Map.of("available", false, "code", failure.code(), "message", safeMessage(failure.getMessage())));
            metadata.put("available", false);
            metadata.put("code", failure.code());
        } catch (RuntimeException exception) {
            diff = write(Map.of("available", false, "code", "DIFF_CAPTURE_FAILED", "message", safeMessage(exception)));
            metadata.put("available", false);
            metadata.put("code", "DIFF_CAPTURE_FAILED");
        }
        persistArtifact(task, attempt.id(), null, "GIT_DIFF", "worktree.diff", "text/plain", diff, metadata);
    }

    private JudgeDecision parseJudgeDecision(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return new JudgeDecision(null, null, "Judge returned no assistant text");
        try {
            String candidate = rawOutput.trim();
            int first = candidate.indexOf('{');
            int last = candidate.lastIndexOf('}');
            if (first >= 0 && last >= first) candidate = candidate.substring(first, last + 1);
            var node = json.readTree(candidate);
            String verdict = node.path("verdict").asText("").trim().toUpperCase();
            String reason = node.path("reason").asText("").trim();
            if (!("PASS".equals(verdict) || "REVISE".equals(verdict) || "BLOCKED".equals(verdict))) {
                return new JudgeDecision(null, null, "Judge verdict must be exactly PASS, REVISE, or BLOCKED");
            }
            if (reason.isBlank()) return new JudgeDecision(null, null, "Judge response requires a non-empty reason");
            return new JudgeDecision(verdict, reason, null);
        } catch (JacksonException exception) {
            return new JudgeDecision(null, null, "Judge result is not parseable JSON: " + safeMessage(exception.getOriginalMessage()));
        }
    }

    private void persistArtifact(TaskRow task, String attemptId, String judgeRunId, String kind, String name, String contentType,
                                 String content, Map<String, ?> metadata) {
        mapper.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(), task.id(), attemptId, judgeRunId, kind, name,
                contentType, content == null ? "" : content, write(metadata), now()));
    }

    private void abortJudgeSessions(TaskRow task) {
        if (task.worktreePath() == null) return;
        Path worktree = Path.of(task.worktreePath());
        for (JudgeRunRow judge : mapper.activeJudgeRuns(task.id())) {
            if (judge.externalSessionId() != null) {
                try { openCode.abort(new OpenCodeClient.OpenCodeSession(judge.externalSessionId(), worktree)); }
                catch (SessionFailure ignored) { /* terminal task decision and stored judge evidence remain authoritative */ }
            }
            updateJudge(judgeState(judge, judge.externalSessionId(), "ABORTED", judge.verdict(), judge.reason(), judge.rawOutput(), now()));
        }
    }

    private String roleTitle(String role) { return "REQUIREMENT".equals(role) ? "Requirement Judge" : "Risk Judge"; }
    private String judgePrompt(TaskRow task, AttemptRow attempt, String role) {
        LoopSpec loopSpec = spec(task);
        String focus = "REQUIREMENT".equals(role)
                ? "判断交付结果是否满足已确认目标、最终阶段目标和确定性验证证据。"
                : "检查回归、越界或不安全变更、证据缺失，以及任何导致交付不安全的风险。";
        String objectives = mapper.listStages(task.id()).stream().filter(stage -> StageState.SUCCEEDED.name().equals(stage.state()))
                .max(java.util.Comparator.comparingInt(StageRow::ordinal)).map(StageRow::objective).orElse("(no completed final stage)");
        String verification = mapper.listTaskArtifacts(task.id()).stream()
                .filter(artifact -> attempt.id().equals(artifact.attemptId()) && "VERIFICATION_SUMMARY".equals(artifact.kind()))
                .map(TaskArtifactRow::content).findFirst().orElse("No verification summary was persisted.");
        String diff = mapper.listTaskArtifacts(task.id()).stream()
                .filter(artifact -> attempt.id().equals(artifact.attemptId()) && "GIT_DIFF".equals(artifact.kind()))
                .map(TaskArtifactRow::content).findFirst().orElse("No diff artifact was persisted.");
        String reviewer = "REQUIREMENT".equals(role) ? "需求评审员" : "风险评审员";
        return "你是" + reviewer + "。这是严格的只读评审：不得编辑文件、运行终端命令或委派任务。\n"
                + focus + "\n已确认目标：" + loopSpec.goal() + "\n上下文：" + loopSpec.context()
                + "\n最终阶段目标：\n- " + objectives + "\n确定性验证摘要：\n" + verification
                + "\n已持久化的 Git 差异证据：\n" + diff + "\n尝试记录：" + attempt.id()
                + "\n仅返回一个 JSON 对象，不得附加说明或代码围栏："
                + "{\"verdict\":\"PASS|REVISE|BLOCKED\",\"reason\":\"简洁、基于证据的中文 Markdown\"}。"
                + "`verdict` 必须保留上述英文协议值；`reason` 必须使用简体中文。"
                + "在 `reason` 中先写一句结论，再写 `## 证据` 标题和编号列表；命令与文件路径使用行内代码。"
                + "若结论不是 PASS，再增加 `## 必须处理` 标题和编号列表。"
                + "不要使用围栏代码块，并将 `reason` 内的每个换行正确转义为 JSON 字符串。";
    }

    private JudgeRunRow judgeState(JudgeRunRow row, String externalSessionId, String state, String verdict, String reason,
                                   String rawOutput, String endedAt) {
        return new JudgeRunRow(row.id(), row.taskId(), row.attemptId(), row.role(), row.ordinal(), externalSessionId, state,
                verdict, safeNullable(reason), rawOutput, row.createdAt(), endedAt, row.version());
    }
    private void updateJudge(JudgeRunRow row) { if (mapper.updateJudgeRun(row) != 1) throw new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"); }
    private String safeNullable(String value) { return value == null ? null : safeMessage(value); }
    private record JudgeDecision(String verdict, String reason, String parseError) { }

    private void failTask(TaskRow task, String code, String message, StageRow stage, AttemptRow attempt, ExecutionSessionRow session) {
        TaskRow current = mapper.findTask(task.id()).orElse(task);
        if (TaskState.valueOf(current.state()).terminal()) return;
        abortSessions(current);
        abortJudgeSessions(current);
        // The task-fatal boundary closes every active attempt. Some failures are
        // discovered before a caller has an AttemptRow reference (for example a
        // malformed persisted verifier), but no child may remain RUNNING after
        // its parent Task has exited.
        for (AttemptRow active : mapper.listAttempts(current.id())) {
            if (AttemptState.RUNNING.name().equals(active.state())) {
                updateAttempt(finish(active, AttemptState.TASK_ERROR, code, message));
            }
        }
        recordError(current, stage, attempt, session, ErrorLayer.TASK, code, message, false, Map.of());
        updateTask(state(get(current.id()), TaskState.FAILED));
        events.emit(current.id(), "task.failed", Map.of("state", TaskState.FAILED.name(), "code", code, "message", message));
    }

    private void abortSessions(TaskRow task) {
        for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("cleanup", "terminal_task");
            boolean stopped = confirmStoppedBeforeRetry(task, session, evidence);
            AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
            StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
            if (stopped) {
                updateSession(sessionState(session, SessionState.ABORTED));
            } else {
                updateSession(sessionState(session, SessionState.DISCONNECTED));
                recordAbortUnconfirmed(task, stage, attempt, session,
                        "Task became terminal before its mutating Session could be confirmed stopped",
                        evidence);
            }
        }
    }

    private void recordAbortUnconfirmed(TaskRow task, StageRow stage, AttemptRow attempt,
                                        ExecutionSessionRow session, String message,
                                        Map<String, Object> evidence) {
        if (session == null) return;
        Map<String, Object> persistentEvidence = new LinkedHashMap<>(evidence);
        persistentEvidence.put("cleanupScheduled", true);
        persistentEvidence.put("cleanupLimit", Math.max(1, defaults.getAbortCleanupAttempts()));
        recordError(task, stage, attempt, session, ErrorLayer.SESSION,
                "SESSION_ABORT_UNCONFIRMED", message, true, persistentEvidence);
        events.emit(task.id(), "session.cleanup_pending", Map.of("sessionId", session.id()));
    }

    private LoopSpec spec(TaskRow task) {
        if (task.loopDraftId() == null) throw new TaskFailure("TASK_CONTRACT_MISSING", "Task has no confirmed LoopSpec");
        return readSpec(mapper.findDraft(task.loopDraftId()).orElseThrow(() -> new TaskFailure("TASK_CONTRACT_MISSING", "LoopSpec draft is missing")));
    }
    private void recordError(TaskRow task, StageRow stage, AttemptRow attempt, ExecutionSessionRow session, ErrorLayer layer,
                             String code, String message, boolean retryable, Map<String, ?> evidence) {
        mapper.insertError(new ErrorEventRow(UUID.randomUUID().toString(), task.id(), stage == null ? null : stage.id(),
                attempt == null ? null : attempt.id(), session == null ? null : session.id(), layer.name(), code,
                safeMessage(message), retryable, write(evidence), now()));
    }
    private TaskRow state(TaskRow row, TaskState state) { return new TaskRow(row.id(), row.projectId(), row.loopDraftId(), row.title(), state.name(), row.worktreePath(), row.branchName(), row.baselineCommit(), row.createdAt(), now(), row.version()); }
    private StageRow stageState(StageRow row, StageState state) { return new StageRow(row.id(), row.taskId(), row.ordinal(), row.objective(), row.allowedPathsJson(), row.forbiddenPathsJson(), row.deliverablesJson(), row.verifiersJson(), state.name(), row.createdAt(), now(), row.version()); }
    private AttemptRow finish(AttemptRow row, AttemptState state, String failureKind, String summary) { return new AttemptRow(row.id(), row.taskId(), row.stageId(), row.ordinal(), state.name(), failureKind, safeMessage(summary), row.createdAt(), now(), row.version()); }
    private ExecutionSessionRow sessionState(ExecutionSessionRow row, SessionState state) { return new ExecutionSessionRow(row.id(), row.taskId(), row.stageId(), row.attemptId(), row.externalSessionId(), state.name(), row.createdAt(), now(), row.version()); }
    private void updateTask(TaskRow row) { if (mapper.updateTaskState(row) != 1) throw new ConflictException("TASK_VERSION_CONFLICT", "Task was updated concurrently"); }
    private void updateStage(StageRow row) { if (mapper.updateStageState(row) != 1) throw new ConflictException("STAGE_VERSION_CONFLICT", "Stage was updated concurrently"); }
    private void updateAttempt(AttemptRow row) { if (mapper.finishAttempt(row) != 1) throw new ConflictException("ATTEMPT_VERSION_CONFLICT", "Attempt was updated concurrently"); }
    private void updateSession(ExecutionSessionRow row) { if (mapper.updateSessionState(row) != 1) throw new ConflictException("SESSION_VERSION_CONFLICT", "Session was updated concurrently"); }
    private String requireWorktree(TaskRow task) { if (task.worktreePath() == null || task.worktreePath().isBlank()) throw new TaskFailure("WORKTREE_MISSING", "Task has no prepared execution workspace"); return task.worktreePath(); }
    private String normalizedTitle(String title, String goal) { return title == null || title.isBlank() ? goal.substring(0, Math.min(goal.length(), 120)) : title.trim(); }
    private String promptWithBoundaries(LoopSpec spec, StageRow stage, String recovery) {
        return "Goal: " + spec.goal() + "\nStage: " + stage.objective()
                + "\nAllowed paths: " + stage.allowedPathsJson() + "\nForbidden paths: " + stage.forbiddenPathsJson()
                + "\nLanguage requirement: 使用简体中文撰写面向用户的进度说明、结论、评审和最终总结。"
                + "代码、命令、路径、标识符、JSON 字段名、协议枚举值以及要求精确匹配的字面量保持原样；"
                + "仅当用户目标明确要求其他语言时才切换语言。\n" + recovery;
    }
    private OpenCodeClient.OpenCodeModel model(LoopSpec spec) {
        if (spec.model() != null && spec.model().providerId() != null && spec.model().modelId() != null) {
            return new OpenCodeClient.OpenCodeModel(spec.model().providerId(), spec.model().modelId(), spec.model().thinking());
        }
        String configured = defaults.getOpenCode().getModel();
        if (configured == null) return null;
        int separator = configured.indexOf('/');
        if (separator <= 0 || separator >= configured.length() - 1) return null;
        return new OpenCodeClient.OpenCodeModel(configured.substring(0, separator), configured.substring(separator + 1), null);
    }
    private String now() { return Instant.now().toString(); }
    private String safeMessage(Throwable t) { return safeMessage(t.getMessage()); }
    private String safeMessage(String value) { return value == null ? "Unknown error" : value.substring(0, Math.min(value.length(), 4000)); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JacksonException e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, TypeReference<T> type) { try { return json.readValue(value, type); } catch (JacksonException e) { throw new TaskFailure("LOOPSPEC_INVALID", "Unable to parse stage verifier configuration"); } }
    private LoopSpec readSpec(LoopDraftRow row) {
        try { return json.readValue(row.specJson(), LoopSpec.class); }
        catch (JacksonException e) { throw new TaskFailure("LOOPSPEC_INVALID", "Unable to parse task LoopSpec"); }
    }
}
