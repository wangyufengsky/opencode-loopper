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
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.WorkspaceLeaseState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
import io.opencode.loopper.verification.BinaryArtifactPersistenceService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sole owner of execution-state transitions. In particular, only this class turns a
 * TaskFailure into FAILED; SessionFailure always closes its attempt then starts a fresh session.
 */
@Service
public class TaskService {
    private static final String DESIGN_CONTEXT_ARTIFACT_KIND = "DESIGN_CONTEXT";
    private static final String LOCAL_SOURCE_SYNC_ARTIFACT_KIND = "LOCAL_SOURCE_SYNC";
    private static final String ATTEMPT_HANDOFF_ARTIFACT_KIND = "ATTEMPT_HANDOFF";
    private static final String LOOP_STAGNATION_OVERRIDE_ARTIFACT_KIND = "LOOP_STAGNATION_OVERRIDE";
    private static final int MAX_EXECUTION_DESIGN_CONTEXT_CHARS = 12_000;
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final DirectWorkspaceLeaseCoordinator directLeases;
    private final OpenCodeClient openCode;
    private final VerifierEngine verifiers;
    private final AttemptHandoffService attemptHandoffs;
    private final BinaryArtifactPersistenceService binaryArtifacts;
    private final ManagedVerificationRuntimeService managedVerifierRuntimes;
    private final UsageInsightsService usageInsights;
    private final TaskEventService events;
    private final LoopperProperties defaults;
    private final TransactionTemplate transactions;

    public TaskService(LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json, ProjectService projects,
                       GitWorktreeManager worktrees, DirectWorkspaceLeaseCoordinator directLeases,
                       OpenCodeClient openCode, VerifierEngine verifiers,
                       AttemptHandoffService attemptHandoffs,
                       BinaryArtifactPersistenceService binaryArtifacts,
                       ManagedVerificationRuntimeService managedVerifierRuntimes,
                       UsageInsightsService usageInsights,
                       TaskEventService events, LoopperProperties defaults,
                       PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.json = json; this.projects = projects;
        this.worktrees = worktrees; this.directLeases = directLeases; this.openCode = openCode;
        this.verifiers = verifiers; this.attemptHandoffs = attemptHandoffs; this.binaryArtifacts = binaryArtifacts;
        this.managedVerifierRuntimes = managedVerifierRuntimes;
        this.usageInsights = usageInsights; this.events = events; this.defaults = defaults;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public TaskRow createFromDraft(LoopDraftRow draft, String title) {
        return createFromDraft(draft, title, "MANUAL");
    }
    @Transactional
    public TaskRow createFromDraft(LoopDraftRow draft, String title, String admissionSource) {
        return createFromDraft(draft, title, admissionSource, null);
    }
    @Transactional
    public TaskRow createFromDraft(LoopDraftRow draft, String title, String admissionSource, String isolatedBaseline) {
        var existing = mapper.findTaskByDraft(draft.id());
        if (existing.isPresent()) return existing.get();
        LoopSpec spec = readSpec(draft);
        ProjectRow project = projects.get(draft.projectId());
        Path projectRoot = Path.of(project.rootPath());
        boolean gitSourceBranch = worktrees.inspect(projectRoot).isolatedWorktree();
        if (isolatedBaseline != null && !gitSourceBranch) {
            throw new BadRequestException("REWORK_REPOSITORY_REQUIRED", "新分支重做需要可用的 Git 仓库根目录");
        }
        String now = now();
        String taskId = UUID.randomUUID().toString();
        TaskRow task = new TaskRow(taskId, project.id(), draft.id(), normalizedTitle(title, draft.goal()),
                TaskState.QUEUED.name(), null, null, null, isolatedBaseline, now, now, 0);
        lifecycle.create(subject(LifecycleMachineType.TASK, task.id(), task.id()), task.state(),
                Map.of("source", normalizedAdmissionSource(admissionSource)), () -> mapper.insertTask(task),
                () -> new ConflictException("TASK_CREATE_CONFLICT", "Task could not be created"));
        persistConfirmedDesignContext(task, draft);
        int ordinal = 0;
        for (LoopSpec.StageSpec stage : spec.stages()) {
            StageRow stageRow = new StageRow(UUID.randomUUID().toString(), taskId, ordinal++, stage.objective(),
                    write(stage.allowedPaths()), write(stage.forbiddenPaths()), write(stage.deliverables()), write(stage.verifiers()),
                    StageState.PENDING.name(), now, now, 0);
            lifecycle.create(subject(LifecycleMachineType.STAGE, stageRow.id(), taskId), stageRow.state(), Map.of(),
                    () -> mapper.insertStage(stageRow),
                    () -> new ConflictException("STAGE_CREATE_CONFLICT", "Stage could not be created"));
        }
        try {
            DirectWorkspaceLeaseCoordinator.Admission admission = directLeases.acquireOrEnqueue(
                    projectRoot, taskId, normalizedAdmissionSource(admissionSource), null);
            if (TaskQueueState.QUEUED.name().equals(admission.state())) {
                events.emit(taskId, "task.queued", Map.of("state", TaskState.QUEUED.name(),
                        "queuePosition", queuePosition(taskId), "leaseState", admission.leaseState()));
                return get(taskId);
            }
            return prepareAdmittedInPlaceTask(taskId);
        } catch (TaskFailure failure) {
            if ("SOURCE_BRANCH_WORKSPACE_DIRTY".equals(failure.code())) {
                return waitForDirtyWorkspace(get(taskId), failure.getMessage());
            }
            if (isolatedBaseline != null) {
                throw new ConflictException(failure.code(), failure.getMessage());
            }
            failTask(task, failure.code(), failure.getMessage(), null, null, null);
            return get(taskId);
        }
    }

    private String normalizedAdmissionSource(String admissionSource) {
        return switch (admissionSource == null ? "MANUAL" : admissionSource) {
            case "AUTOMATION", "RECOVERY", "MANUAL" -> admissionSource == null ? "MANUAL" : admissionSource;
            default -> throw new BadRequestException("TASK_ADMISSION_SOURCE_INVALID", "Unknown task admission source");
        };
    }

    public TaskRow get(String id) { return mapper.findTask(id).orElseThrow(() -> new NotFoundException("Task not found: " + id)); }
    public List<TaskRow> list() { return mapper.listTasks(); }
    public boolean archived(String id) { get(id); return mapper.isTaskArchived(id); }
    @Transactional
    public TaskRow archive(String id) {
        TaskRow task = get(id);
        if (!List.of(TaskState.SUCCEEDED.name(), TaskState.FAILED.name(), TaskState.CANCELLED.name()).contains(task.state())) {
            throw new BadRequestException("TASK_NOT_ARCHIVABLE", "只有已成功、已失败或已取消的任务可以归档");
        }
        mapper.archiveTask(id, now());
        return task;
    }
    @Transactional
    public TaskRow restoreArchive(String id) {
        TaskRow task = get(id);
        mapper.restoreTask(id);
        return task;
    }
    @Transactional
    public void deleteArchived(String id) {
        TaskRow task = get(id);
        if (!List.of(TaskState.SUCCEEDED.name(), TaskState.FAILED.name(), TaskState.CANCELLED.name()).contains(task.state())) {
            throw new BadRequestException("TASK_NOT_DELETABLE", "只有已成功、已失败或已取消的任务可以删除");
        }
        if (!mapper.isTaskArchived(id)) {
            throw new BadRequestException("TASK_NOT_ARCHIVED", "请先归档任务，再从已归档列表永久删除");
        }
        if (!mapper.childTasks(id).isEmpty()) {
            throw new BadRequestException("TASK_HAS_RECOVERY_CHILDREN", "该任务仍有重做或恢复子任务，请先删除子任务");
        }
        String draftId = task.loopDraftId();
        mapper.deleteLocalSyncConflictFilesForTask(id);
        mapper.deleteLocalSyncConflictSessionsForTask(id);
        mapper.deleteTaskPublicationForTask(id);
        mapper.deleteSessionTodosForTask(id);
        mapper.deleteSessionCheckpointsForTask(id);
        mapper.deleteSessionUsageForTask(id);
        mapper.deleteBinaryArtifactsForTask(id);
        mapper.deleteTaskArtifactsForTask(id);
        mapper.deleteVerificationResultsForTask(id);
        mapper.deleteVerifierRuntimesForTask(id);
        mapper.deleteInteractionsForTask(id);
        mapper.deleteErrorsForTask(id);
        mapper.deleteEventsForTask(id);
        mapper.deleteJudgeRunsForTask(id);
        mapper.detachWorkspaceLeaseHolder(id);
        mapper.detachWorkspaceLeaseWriterSessions(id);
        mapper.deleteExecutionSessionsForTask(id);
        mapper.deleteAttemptsForTask(id);
        mapper.deleteTaskLineageForChild(id);
        mapper.deleteStagesForTask(id);
        mapper.deleteTaskQueueEntry(id);
        mapper.deleteTaskArchiveEntry(id);
        mapper.detachAutomationRunsFromTask(id);
        mapper.deleteStateTransitionsForScope(LifecycleScopeType.TASK.name(), id);
        if (mapper.deleteTask(id) != 1) {
            throw new NotFoundException("Task not found: " + id);
        }
        if (draftId != null && !draftId.isBlank()) {
            mapper.deleteDesignerMessagesByDraft(draftId);
            mapper.deleteDesignerInteractionsByDraft(draftId);
            mapper.deleteDesignerSessionsByDraft(draftId);
            mapper.detachAutomationRunsFromDraft(draftId);
            mapper.deleteDraft(draftId);
        }
    }
    public List<StageRow> stages(String taskId) { get(taskId); return mapper.listStages(taskId); }
    public List<AttemptRow> attempts(String taskId) { get(taskId); return mapper.listAttempts(taskId); }
    public List<ErrorEventRow> errors(String taskId) { get(taskId); return mapper.listErrors(taskId); }
    public List<VerificationResultRow> verifications(String attemptId) { return mapper.listVerifications(attemptId); }
    /** Append-only final-review history, including retries and raw model conclusions. */
    public List<JudgeRunRow> judges(String taskId) { get(taskId); return mapper.listJudgeRuns(taskId); }
    /** Immutable diff, verifier, and judge evidence retained independently of the worktree. */
    public List<TaskArtifactRow> artifacts(String taskId) { get(taskId); return mapper.listTaskArtifacts(taskId); }

    public boolean hasLocalSourceSync(String taskId, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) return false;
        return artifacts(taskId).stream().anyMatch(artifact -> LOCAL_SOURCE_SYNC_ARTIFACT_KIND.equals(artifact.kind())
                && commitSha.equals(artifact.content()));
    }

    @Transactional
    public void recordLocalSourceSync(String taskId, String commitSha, String mode) {
        TaskRow task = get(taskId);
        if (hasLocalSourceSync(taskId, commitSha)) return;
        persistArtifact(task, null, null, LOCAL_SOURCE_SYNC_ARTIFACT_KIND, "local-source-sync.txt", "text/plain",
                commitSha, Map.of("source", "task-publication", "mode", mode));
    }

    /** Restores the Task start branch and releases the registered checkout after the Task commit is durable. */
    public void releaseWorkspaceAfterTaskCommit(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.SUCCEEDED.name().equals(task.state())) {
            throw new ConflictException("TASK_PUBLICATION_LEASE_NOT_RELEASABLE",
                    "Only a succeeded Task can release its workspace after publication");
        }
        if (!isAdmittedInPlace(task)) return;
        if (!GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            worktrees.restoreSourceBranch(inPlaceRoot(task), task.branchName(), task.sourceBranch());
        }
        settleTerminalInPlaceLease(task, !hasUnconfirmedWriter(task.id()), "TASK_COMMITTED");
    }

    public FeatureContracts.QueueStatusDto queueStatus(String taskId) {
        TaskRow task = get(taskId);
        TaskQueueRow queue = mapper.findTaskQueue(taskId).orElse(null);
        if (queue == null) {
            return new FeatureContracts.QueueStatusDto(task.id(), TaskQueueState.FINISHED.name(), null, "NOT_REQUIRED", null);
        }
        String leaseState = mapper.findWorkspaceLease(queue.canonicalRoot()).map(row -> row.state())
                .orElse(WorkspaceLeaseState.RELEASED.name());
        Long position = TaskQueueState.QUEUED.name().equals(queue.state()) ? queuePosition(taskId) : null;
        return new FeatureContracts.QueueStatusDto(task.id(), queue.state(), position, leaseState, queue.rootFingerprint());
    }

    /** User-facing goal retained with the confirmed LoopSpec for publication metadata. */
    public String goal(String taskId) { return spec(get(taskId)).goal(); }

    public VerifierEngine.DiffPreview diffPreview(String taskId, String path) {
        TaskRow task = get(taskId);
        if (path == null || path.isBlank()) {
            throw new BadRequestException("DIFF_PATH_INVALID", "Diff preview requires a file path");
        }
        boolean verified = false;
        boolean untracked = false;
        TaskArtifactRow snapshot = mapper.listTaskArtifacts(taskId).stream()
                .filter(artifact -> "GIT_DIFF".equals(artifact.kind()))
                .findFirst().orElse(null);
        if (snapshot != null) {
            try {
                var evidence = json.readTree(snapshot.metadataJson());
                if (evidence.path("changedPaths").isArray()) {
                    for (var item : evidence.path("changedPaths")) {
                        if (path.equals(item.asText())) { verified = true; break; }
                    }
                    if (verified && evidence.path("untrackedPaths").isArray()) {
                        for (var item : evidence.path("untrackedPaths")) {
                            if (path.equals(item.asText())) { untracked = true; break; }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Historical session-diff artifacts did not contain structured path metadata.
            }
        }
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
            return verifiers.previewDiff(Path.of(task.worktreePath()), task.baselineCommit(), task.branchName(),
                    path, untracked, Duration.ofSeconds(10));
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
        boolean verificationOnly = isVerificationOnlyRecovery(taskId);
        if (TaskState.FAILED.name().equals(task.state()) || TaskState.CANCELLED.name().equals(task.state()) || TaskState.SUCCEEDED.name().equals(task.state())) {
            throw new ConflictException("TASK_TERMINAL", "Cannot start a terminal task");
        }
        if (TaskState.PAUSED.name().equals(task.state())) return resume(taskId);
        if (!TaskState.READY.name().equals(task.state())) throw new ConflictException("TASK_ALREADY_ACTIVE", "Task is already active");
        try {
            ProjectRow project = projects.get(task.projectId());
            worktrees.requireExecutionWorkspace(Path.of(requireWorktree(task)), Path.of(project.rootPath()),
                    task.branchName(), task.baselineCommit());
            requireInPlaceWritable(task, project);
            if (verificationOnly) {
                updateTask(state(task, TaskState.RUNNING));
                StageRow stage = mapper.listStages(task.id()).stream()
                        .filter(s -> StageState.PENDING.name().equals(s.state()) || StageState.PAUSED.name().equals(s.state()))
                        .findFirst().orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Task has no runnable stage"));
                startVerificationOnlyAttempt(get(task.id()), stage);
                return verify(taskId);
            }
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

    public TaskRow verify(String taskId) {
        TaskRow initial = get(taskId);
        boolean verificationOnly = isVerificationOnlyRecovery(taskId);
        if (!TaskState.RUNNING.name().equals(initial.state())) {
            throw new ConflictException("TASK_NOT_RUNNING", "Only a running task can be verified");
        }
        try {
            StageRow stage = mapper.listStages(taskId).stream().filter(s -> StageState.RUNNING.name().equals(s.state())).findFirst()
                    .orElseThrow(() -> new TaskFailure("STAGE_NOT_RUNNING", "No running stage is available for verification"));
            AttemptRow attempt = mapper.latestAttempt(stage.id()).orElseThrow(() -> new TaskFailure("ATTEMPT_MISSING", "No attempt is available for verification"));
            ExecutionSessionRow implementationSession = mapper.latestSessionForAttempt(attempt.id()).orElse(null);
            if (!verificationOnly) {
                if (implementationSession == null) throw new TaskFailure("SESSION_MISSING", "No implementation Session is available for verification");
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
            }
            ProjectRow project = projects.get(initial.projectId());
            worktrees.requireExecutionWorkspace(Path.of(requireWorktree(initial)), Path.of(project.rootPath()),
                    initial.branchName(), initial.baselineCommit());
            transactions.executeWithoutResult(status -> enterVerification(initial, implementationSession));
            LoopSpec spec = spec(initial);
            List<LoopSpec.VerifierSpec> verifierSpecs = read(stage.verifiersJson(), new TypeReference<>() {});
            List<PendingVerification> pending = new ArrayList<>();
            Duration timeout = Duration.ofSeconds(spec.limits().verifierTimeoutSeconds());
            LoopSpec.StageSpec stageContract = spec.stages().get(stage.ordinal());
            ManagedVerificationRuntimeService.Lease managedRuntime = null;
            try {
                if ("v2".equals(spec.schemaVersion()) && stageContract.verificationRuntime() != null) {
                    ManagedVerificationRuntimeService.StartResult start = managedVerifierRuntimes.start(
                            initial.id(), stage.id(), attempt.id(), Path.of(requireWorktree(initial)),
                            stageContract.verificationRuntime());
                    managedRuntime = start.lease();
                    if (start.failure() != null) {
                        if (start.failure().state() == VerificationState.ERROR) {
                            throw new TaskFailure(String.valueOf(start.failure().evidence().getOrDefault("code",
                                    "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED")), start.failure().summary());
                        }
                        pending.add(new PendingVerification(UUID.randomUUID().toString(), -1, start.failure()));
                    }
                }
                if (pending.isEmpty()) {
                    for (int i = 0; i < verifierSpecs.size(); i++) {
                        VerifierOutcome outcome;
                        try {
                            LoopSpec.VerifierSpec bound = managedVerifierRuntimes.bind(verifierSpecs.get(i), managedRuntime);
                            outcome = verifiers.verify(Path.of(requireWorktree(initial)), initial.baselineCommit(), bound, timeout);
                        } catch (TaskFailure knownFailure) {
                            throw knownFailure;
                        } catch (RuntimeException unexpectedFailure) {
                            throw new TaskFailure("VERIFIER_RUNTIME_ERROR",
                                    "Verifier could not be evaluated safely: " + safeMessage(unexpectedFailure));
                        }
                        pending.add(new PendingVerification(UUID.randomUUID().toString(), i, outcome));
                    }
                }
            } finally {
                if (managedRuntime != null) {
                    VerifierOutcome runtimeOutcome = managedVerifierRuntimes.stop(managedRuntime, "stage-verification-complete").outcome();
                    pending.add(new PendingVerification(UUID.randomUUID().toString(), -1, runtimeOutcome));
                    if (runtimeOutcome.state() == VerificationState.ERROR) {
                        throw new TaskFailure(String.valueOf(runtimeOutcome.evidence().getOrDefault("code",
                                "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED")), runtimeOutcome.summary());
                    }
                }
            }
            AttemptHandoffService.Capture handoff = null;
            PendingVerification failedPreview = pending.stream()
                    .filter(result -> result.outcome().state() != VerificationState.PASS)
                    .reduce((left, right) -> right).orElse(null);
            if (!verificationOnly && failedPreview != null) {
                handoff = attemptHandoffs.capture(Path.of(requireWorktree(initial)), initial.baselineCommit(),
                        stage.id(), attempt.id(), attempt.ordinal(), pending.stream()
                                .map(result -> new AttemptHandoffService.VerificationFact(result.outcome().type(),
                                        result.outcome().state().name(), result.outcome().summary())).toList(),
                        failedPreview.outcome().summary(), timeout);
            }
            AttemptHandoffService.Capture capturedHandoff = handoff;
            VerificationContinuation continuation = transactions.execute(status -> finishVerification(
                    initial.id(), stage.id(), attempt.id(), implementationSession == null ? null : implementationSession.id(),
                    pending, capturedHandoff, verificationOnly, spec));
            continueAfterVerification(continuation);
        } catch (TaskFailure failure) {
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
        }
        return get(taskId);
    }

    private void enterVerification(TaskRow initial, ExecutionSessionRow implementationSession) {
        TaskRow current = get(initial.id());
        if (!TaskState.RUNNING.name().equals(current.state()) || current.version() != initial.version()) {
            throw new ConflictException("TASK_VERSION_CONFLICT", "Task changed before verification could start");
        }
        updateTask(state(current, TaskState.VERIFYING));
        if (implementationSession != null) {
            ExecutionSessionRow currentSession = mapper.findSession(implementationSession.id()).orElse(implementationSession);
            if (SessionState.RUNNING.name().equals(currentSession.state())) {
                updateSession(sessionState(currentSession, SessionState.COMPLETED));
            }
        }
        if (isAdmittedInPlace(current)) {
            directLeases.retainAfterWriterStopped(inPlaceRoot(current), current.id(), "IMPLEMENTATION_SESSION_COMPLETED");
        }
    }

    private VerificationContinuation finishVerification(String taskId, String stageId, String attemptId,
                                                          String implementationSessionId,
                                                          List<PendingVerification> pending,
                                                          AttemptHandoffService.Capture handoff,
                                                          boolean verificationOnly, LoopSpec spec) {
        TaskRow task = get(taskId);
        if (!TaskState.VERIFYING.name().equals(task.state())) {
            throw new ConflictException("TASK_VERIFICATION_INTERRUPTED",
                    "Task changed while deterministic verification was running");
        }
        StageRow stage = mapper.findStage(stageId)
                .orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Verification stage disappeared"));
        AttemptRow attempt = mapper.findAttempt(attemptId)
                .orElseThrow(() -> new TaskFailure("ATTEMPT_MISSING", "Verification attempt disappeared"));
        if (!StageState.RUNNING.name().equals(stage.state()) || !AttemptState.RUNNING.name().equals(attempt.state())) {
            throw new ConflictException("TASK_VERIFICATION_INTERRUPTED",
                    "Stage or attempt changed while deterministic verification was running");
        }
        for (PendingVerification result : pending) {
            VerifierOutcome outcome = result.outcome();
            mapper.insertVerification(new VerificationResultRow(result.id(), attempt.id(), result.index(), outcome.type(),
                    outcome.state().name(), outcome.summary(), write(outcome.evidence()), now()));
            binaryArtifacts.persistBrowserArtifacts(task.id(), attempt.id(), implementationSessionId, result.id(), outcome);
        }
        PendingVerification failed = pending.stream()
                .filter(result -> result.outcome().state() != VerificationState.PASS).reduce((left, right) -> right).orElse(null);
        if (failed == null) return completeStageState(task, stage, attempt);
        String failure = failed.outcome().summary();
        if (verificationOnly) {
            updateAttempt(finish(attempt, AttemptState.VERIFICATION_FAILED, "VERIFICATION_FAILED", failure));
            recordError(task, stage, attempt, null, ErrorLayer.VERIFICATION,
                    "VERIFICATION_FAILED", failure, true, Map.of("verifyOnly", true));
            failTask(get(taskId), "VERIFY_ONLY_VERIFICATION_FAILED",
                    "VERIFY_ONLY 恢复任务的原生验证失败；不会创建可写 OpenCode 修复会话", stage, attempt, null);
            return VerificationContinuation.none(taskId);
        }
        int stagnationCount = persistAttemptHandoff(task, stage, attempt, handoff);
        return retryAfterVerificationFailureState(task, stage, attempt, failure, spec, handoff, stagnationCount);
    }

    private void continueAfterVerification(VerificationContinuation continuation) {
        if (continuation == null || continuation.action() == VerificationAction.NONE) return;
        TaskRow task = get(continuation.taskId());
        StageRow stage = mapper.findStage(continuation.stageId())
                .orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Continuation stage disappeared"));
        if (continuation.action() == VerificationAction.FINAL_REVIEW) {
            AttemptRow attempt = mapper.findAttempt(continuation.attemptId())
                    .orElseThrow(() -> new TaskFailure("ATTEMPT_MISSING", "Final verification attempt disappeared"));
            captureFinalEvidence(task, attempt);
            launchRequiredJudges(task, attempt);
            return;
        }
        startNewAttempt(task, stage, continuation.prompt());
    }

    public TaskRow pause(String taskId) {
        VerifierOutcome runtimeStop = managedVerifierRuntimes.stopTask(taskId, "task-paused");
        if (runtimeStop != null && runtimeStop.state() == VerificationState.ERROR) {
            failTaskForManagedRuntime(taskId, runtimeStop);
            return get(taskId);
        }
        return transactions.execute(status -> pauseState(taskId));
    }

    private TaskRow pauseState(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.RUNNING.name().equals(task.state()) || TaskState.VERIFYING.name().equals(task.state()) || TaskState.RETRY_WAIT.name().equals(task.state())) {
            boolean allWritersStopped = true;
            for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("pause", true);
                boolean stopped = confirmStoppedBeforeRetry(task, session, evidence);
                allWritersStopped &= stopped;
                AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
                StageRow sessionStage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
                updateSession(sessionState(session, stopped ? SessionState.ABORTED : SessionState.DISCONNECTED));
                if (attempt != null && AttemptState.RUNNING.name().equals(attempt.state())) {
                    updateAttempt(finish(attempt, AttemptState.SESSION_ERROR, "PAUSED", "Task paused after stopping its writer Session"));
                }
                if (!stopped) {
                    recordAbortUnconfirmed(task, sessionStage, attempt, session,
                            "Task pause could not confirm the previous mutating Session stopped", evidence);
                }
            }
            // Verification runs without an active writer Session. If pause wins
            // while the verifier worker is outside SQLite, close that Attempt so
            // resume cannot create a second RUNNING Attempt beside it.
            for (AttemptRow attempt : mapper.listAttempts(task.id())) {
                if (AttemptState.RUNNING.name().equals(attempt.state())) {
                    updateAttempt(finish(attempt, AttemptState.SESSION_ERROR, "PAUSED",
                            "Task paused while its current work was still in flight"));
                }
            }
            allWritersStopped &= !hasUnconfirmedWriter(task.id());
            for (StageRow stage : mapper.listStages(taskId)) {
                if (StageState.RUNNING.name().equals(stage.state())) updateStage(stageState(stage, StageState.PAUSED));
            }
            updateTask(state(task, TaskState.PAUSED));
            if (isAdmittedInPlace(task) && allWritersStopped) {
                directLeases.retainAfterWriterStopped(inPlaceRoot(task), task.id(), "TASK_PAUSED_WRITER_STOPPED");
            }
            events.emit(taskId, "task.paused", Map.of("state", TaskState.PAUSED.name(),
                    "writerTerminationConfirmed", allWritersStopped));
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
        if (isAdmittedInPlace(task)) {
            try { directLeases.requireWritableLease(inPlaceRoot(task), task.id()); }
            catch (TaskFailure failure) { throw new ConflictException(failure.code(), failure.getMessage()); }
        }
        updateTask(state(task, TaskState.RUNNING));
        updateStage(stageState(stage, StageState.RUNNING));
        if (mapper.activeSessions(taskId).isEmpty() && !isVerificationOnlyRecovery(taskId)) {
            startNewAttempt(get(taskId), mapper.findStage(stage.id()).orElse(stage), "Resume stage: " + stage.objective());
        }
        events.emit(taskId, "task.resumed", Map.of("state", TaskState.RUNNING.name()));
        return get(taskId);
    }

    public TaskRow cancel(String taskId) {
        VerifierOutcome runtimeStop = managedVerifierRuntimes.stopTask(taskId, "task-cancelled");
        if (runtimeStop != null && runtimeStop.state() == VerificationState.ERROR) {
            failTaskForManagedRuntime(taskId, runtimeStop);
            return get(taskId);
        }
        return transactions.execute(status -> cancelState(taskId));
    }

    private TaskRow cancelState(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.valueOf(task.state()).terminal()) return task;
        if (TaskState.QUEUED.name().equals(task.state())) {
            if (mapper.findTaskQueue(task.id()).isPresent()) directLeases.cancelQueued(task.id());
            updateTask(state(task, TaskState.CANCELLED));
            events.emit(taskId, "task.cancelled", Map.of("state", TaskState.CANCELLED.name(), "queueCancelled", true));
            return get(taskId);
        }
        boolean writersStopped = abortSessions(task);
        abortJudgeSessions(task);
        for (AttemptRow attempt : mapper.listAttempts(taskId)) {
            if (AttemptState.RUNNING.name().equals(attempt.state())) updateAttempt(finish(attempt, AttemptState.CANCELLED, "CANCELLED", "Task cancelled"));
        }
        updateTask(state(get(taskId), TaskState.CANCELLED));
        events.emit(taskId, "task.cancelled", Map.of("state", TaskState.CANCELLED.name()));
        settleTerminalInPlaceLease(get(taskId), writersStopped, "TASK_CANCELLED");
        return get(taskId);
    }

    public void recoverAfterRestart() {
        ManagedVerificationRuntimeService.RecoveryResult runtimeRecovery = managedVerifierRuntimes.recoverActive();
        // Reconcile verifier writers before any terminal lease can be released.
        // A PID-identity mismatch is persisted as DISCONNECTED and therefore
        // participates in hasUnconfirmedWriter during lease rehydration.
        rehydrateDirectLeases();
        for (TaskRow task : mapper.listRecoverableTasks()) {
            if (runtimeRecovery.blockedTaskIds().contains(task.id())) {
                failTask(task, "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED",
                        "Application restart could not prove that the previous managed verifier runtime stopped; refusing overlapping writes",
                        null, null, null);
                continue;
            }
            if (TaskState.PREPARING.name().equals(task.state())) {
                // Preparation has no resumable Session contract or confirmed managed
                // worktree. Treat this as task-level state corruption, not a retryable
                // Session fault.
                failTask(task, "PREPARATION_INTERRUPTED",
                        "Application restart interrupted task preparation before an execution workspace was recorded",
                        null, null, null);
                continue;
            }
            if (isVerificationOnlyRecovery(task.id())) {
                failTask(task, "VERIFY_ONLY_RESTART_INTERRUPTED",
                        "验证型恢复任务在原生验证完成前被应用重启中断；不会创建可写 OpenCode 会话", null, null, null);
                continue;
            }
            if (TaskState.JUDGING.name().equals(task.state())) {
                // Judge transports are session-scoped too.  Retain the broken row and create a
                // bounded fresh read-only session rather than turning a restart into TASK_ERROR.
                for (JudgeRunRow judge : mapper.activeJudgeRuns(task.id())) {
                    handleJudgeSessionFailure(task, judge, new SessionFailure("JUDGE_RUNTIME_RESTART", "Application restart disconnected the previous judge session"));
                }
                TaskRow current = get(task.id());
                if (TaskState.JUDGING.name().equals(current.state())) {
                    StageRow finalStage = mapper.listStages(task.id()).stream()
                            .max(java.util.Comparator.comparingInt(StageRow::ordinal))
                            .orElse(null);
                    AttemptRow finalAttempt = finalStage == null ? null : mapper.latestAttempt(finalStage.id()).orElse(null);
                    if (finalStage == null || finalAttempt == null) {
                        failTask(current, "JUDGE_FINAL_ATTEMPT_MISSING",
                                "Application restart found no final attempt to review", finalStage, finalAttempt, null);
                    } else {
                        captureFinalEvidence(current, finalAttempt);
                        launchRequiredJudges(current, finalAttempt);
                    }
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
                boolean remoteTerminationConfirmed = confirmStoppedBeforeRetry(task, session, recoveryEvidence);
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
                    .filter(candidate -> StageState.RUNNING.name().equals(candidate.state())
                            || StageState.PENDING.name().equals(candidate.state()))
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
                updateTask(state(get(task.id()), TaskState.RUNNING), LifecycleEvent.RECOVER);
            }
            startNewAttempt(get(task.id()), stage,
                    "Application restart disconnected the previous session. Continue the same stage in a fresh session.");
            TaskRow recovered = get(task.id());
            events.emit(task.id(), "task.recovered", Map.of("state", recovered.state(), "reason", "restart",
                    "recovery", "fresh_session"));
        }
    }

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
        if (blockModelCallForBudget(freshTask, stage, null)) return;
        int ordinal = mapper.countAttemptsForStage(stage.id()) + 1;
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), ordinal, AttemptState.RUNNING.name(), null, null, now(), null, 0);
        createAttempt(attempt);
        ExecutionSessionRow session = new ExecutionSessionRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), attempt.id(), null,
                SessionState.CREATING.name(), now(), null, 0);
        createSession(session);
        OpenCodeClient.OpenCodeSession remote;
        try {
            Path worktree = Path.of(requireWorktree(freshTask));
            ProjectRow project = projects.get(freshTask.projectId());
            worktrees.requireExecutionWorkspace(worktree, Path.of(project.rootPath()),
                    freshTask.branchName(), freshTask.baselineCommit());
            remote = openCode.createSession(worktree, freshTask.title(), model(spec));
            ExecutionSessionRow running = new ExecutionSessionRow(session.id(), session.taskId(), session.stageId(), session.attemptId(), remote.id(),
                    SessionState.RUNNING.name(), session.createdAt(), null, session.version());
            updateSession(running);
            if (isAdmittedInPlace(freshTask)) {
                // V12 deliberately references the durable local execution_session
                // row. Provider ids remain on that row and may change across retry.
                directLeases.heartbeat(inPlaceRoot(freshTask), freshTask.id(), running.id());
            }
            openCode.promptAsync(remote, promptWithBoundaries(freshTask, spec, stage, worktree, prompt));
        } catch (SessionFailure failure) {
            handleSessionFailure(freshTask, stage, attempt, session, failure);
            return;
        } catch (RuntimeException exception) {
            handleSessionFailure(freshTask, stage, attempt, session,
                    new SessionFailure("SESSION_RUNTIME_ERROR", safeMessage(exception)));
            return;
        }
        events.emit(freshTask.id(), "session.started", Map.of("attemptId", attempt.id(), "sessionId", session.id(), "externalSessionId", remote.id(), "stageId", stage.id()));
    }

    /** Starts a deterministic verifier attempt without creating an OpenCode implementation Session. */
    private void startVerificationOnlyAttempt(TaskRow task, StageRow inputStage) {
        TaskRow freshTask = get(task.id());
        LoopSpec spec = spec(freshTask);
        StageRow stage = mapper.findStage(inputStage.id()).orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Stage disappeared"));
        if (mapper.countAttemptsForTask(freshTask.id()) >= spec.limits().maxTaskAttempts()
                || mapper.countAttemptsForStage(stage.id()) >= spec.limits().maxStageAttempts()) {
            failTask(freshTask, "ATTEMPT_LIMIT_EXHAUSTED", "The configured verification attempt limit was reached", stage, null, null);
            return;
        }
        if (!TaskState.RUNNING.name().equals(get(freshTask.id()).state())) return;
        if (!StageState.RUNNING.name().equals(stage.state())) updateStage(stageState(stage, StageState.RUNNING));
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(),
                mapper.countAttemptsForStage(stage.id()) + 1, AttemptState.RUNNING.name(), null,
                "VERIFY_ONLY native verification; no writable OpenCode Session", now(), null, 0);
        createAttempt(attempt);
        events.emit(freshTask.id(), "recovery.verify_only.started", Map.of("attemptId", attempt.id(),
                "stageId", stage.id(), "writableSession", false));
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
        if (isAdmittedInPlace(currentTask)) {
            directLeases.retainAfterWriterStopped(inPlaceRoot(currentTask), currentTask.id(), "SESSION_RETRY_WRITER_STOPPED");
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
     * Session is positively stopped. An abort response is only a request; a
     * separate terminal status observation is always required.
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
        } catch (RuntimeException abortFailure) {
            evidence.put("abortSucceeded", false);
            evidence.put("abortErrorCode", abortFailure instanceof SessionFailure sessionFailure
                    ? sessionFailure.code() : "SESSION_ABORT_FAILED");
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
            if (isAdmittedInPlace(task)) {
                if (TaskState.valueOf(task.state()).terminal()) {
                    settleTerminalInPlaceLease(task, true, "SESSION_ABORT_CLEANUP_CONFIRMED");
                } else {
                    directLeases.retainAfterWriterStopped(inPlaceRoot(task), task.id(), "SESSION_ABORT_CLEANUP_CONFIRMED");
                }
            }
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

    private VerificationContinuation retryAfterVerificationFailureState(TaskRow task, StageRow stage,
                                                                          AttemptRow attempt, String message,
                                                                          LoopSpec spec,
                                                                          AttemptHandoffService.Capture handoff,
                                                                          int stagnationCount) {
        updateAttempt(finish(attempt, AttemptState.VERIFICATION_FAILED, "VERIFICATION_FAILED", message));
        recordError(task, stage, attempt, mapper.latestSessionForAttempt(attempt.id()).orElse(null), ErrorLayer.VERIFICATION,
                "VERIFICATION_FAILED", message, true, Map.of());
        if (mapper.countAttemptsForTask(task.id()) >= spec.limits().maxTaskAttempts() || mapper.countAttemptsForStage(stage.id()) >= spec.limits().maxStageAttempts()) {
            failTask(get(task.id()), "ATTEMPT_LIMIT_EXHAUSTED", "Verifier failures exhausted configured attempts", stage, attempt, null);
            return VerificationContinuation.none(task.id());
        }
        if (!Boolean.TRUE.equals(spec.sessionPolicy().createFreshOnVerifierFailure())) {
            return waitForLoopInput(task, stage, attempt, "LOOP_FRESH_SESSION_REQUIRED",
                    "LoopSpec disabled automatic fresh-session recovery; Loopper will not reuse a completed mutating Session. Confirm one explicit fresh retry to continue.",
                    stagnationCount, handoff);
        }
        if (handoff != null && handoff.comparableForStagnation()
                && stagnationCount >= spec.limits().stagnationLimit()) {
            return waitForLoopInput(task, stage, attempt, "LOOP_STAGNATION_DETECTED",
                    "The verifier failure and reliable workspace fingerprint remained unchanged for "
                            + stagnationCount + " consecutive Attempts. Loopper stopped before starting another model Session.",
                    stagnationCount, handoff);
        }
        updateTask(state(get(task.id()), TaskState.RETRY_WAIT));
        events.emit(task.id(), "verification.failed", Map.of("attemptId", attempt.id(), "recovery", "next_attempt", "summary", message));
        updateTask(state(get(task.id()), TaskState.RUNNING));
        return VerificationContinuation.retry(task.id(), stage.id(),
                handoff == null
                        ? "Verification failed: " + message + ". Fix the evidence and retry the current stage."
                        : attemptHandoffs.retryPrompt(handoff, spec.nextAttemptPromptTemplate()));
    }

    private VerificationContinuation waitForLoopInput(TaskRow task, StageRow stage, AttemptRow attempt,
                                                       String code, String message, int stagnationCount,
                                                       AttemptHandoffService.Capture handoff) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("resolution", TaskState.WAITING_INPUT.name());
        evidence.put("explicitRetryAvailable", true);
        evidence.put("stagnationCount", stagnationCount);
        if (handoff != null && handoff.stagnationFingerprint() != null) {
            evidence.put("stagnationFingerprint", handoff.stagnationFingerprint());
        }
        recordError(task, stage, attempt, null, ErrorLayer.VERIFICATION, code, message, true, evidence);
        updateTask(state(get(task.id()), TaskState.WAITING_INPUT));
        events.emit(task.id(), "task.loop_waiting_input", Map.of("state", TaskState.WAITING_INPUT.name(),
                "code", code, "message", safeMessage(message), "stagnationCount", stagnationCount));
        return VerificationContinuation.none(task.id());
    }

    private int persistAttemptHandoff(TaskRow task, StageRow stage, AttemptRow attempt,
                                      AttemptHandoffService.Capture handoff) {
        if (handoff == null) return 0;
        int stagnationCount = consecutiveStagnationCount(task.id(), stage.id(), handoff);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", handoff.schemaVersion());
        content.put("taskId", task.id());
        content.put("stageId", stage.id());
        content.put("attemptId", attempt.id());
        content.put("attemptOrdinal", attempt.ordinal());
        content.put("failureSummary", handoff.failureSummary());
        content.put("verifications", handoff.verifications());
        content.put("changedPaths", handoff.changedPaths());
        content.put("changedPathCount", handoff.changedPathCount());
        content.put("workspaceSha256", handoff.workspaceSha256());
        content.put("workspaceReliable", handoff.workspaceReliable());
        content.put("workspaceUnavailableReason", handoff.workspaceUnavailableReason());
        content.put("stagnationFingerprint", handoff.stagnationFingerprint());
        content.put("consecutiveStagnationCount", stagnationCount);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic-verifier-and-workspace");
        metadata.put("stageId", stage.id());
        metadata.put("attemptOrdinal", attempt.ordinal());
        metadata.put("workspaceReliable", handoff.workspaceReliable());
        metadata.put("stagnationComparable", handoff.comparableForStagnation());
        metadata.put("stagnationFingerprint", handoff.stagnationFingerprint());
        metadata.put("consecutiveStagnationCount", stagnationCount);
        persistArtifact(task, attempt.id(), null, ATTEMPT_HANDOFF_ARTIFACT_KIND,
                "attempt-handoff-" + attempt.ordinal() + ".json", "application/json", write(content), metadata);
        return stagnationCount;
    }

    private int consecutiveStagnationCount(String taskId, String stageId,
                                           AttemptHandoffService.Capture current) {
        if (!current.comparableForStagnation()) return 0;
        int count = 1;
        for (TaskArtifactRow artifact : mapper.listTaskArtifacts(taskId)) {
            if (LOOP_STAGNATION_OVERRIDE_ARTIFACT_KIND.equals(artifact.kind())) {
                if (metadataText(artifact, "stageId").equals(stageId)) break;
                continue;
            }
            if (!ATTEMPT_HANDOFF_ARTIFACT_KIND.equals(artifact.kind())) continue;
            if (!metadataText(artifact, "stageId").equals(stageId)) continue;
            if (!metadataBoolean(artifact, "stagnationComparable")) break;
            if (!current.stagnationFingerprint().equals(metadataText(artifact, "stagnationFingerprint"))) break;
            count++;
        }
        return count;
    }

    private String metadataText(TaskArtifactRow artifact, String field) {
        try { return json.readTree(artifact.metadataJson()).path(field).asText(""); }
        catch (Exception unreadable) { return ""; }
    }

    private boolean metadataBoolean(TaskArtifactRow artifact, String field) {
        try { return json.readTree(artifact.metadataJson()).path(field).asBoolean(false); }
        catch (Exception unreadable) { return false; }
    }

    /** Explicit local confirmation authorizes exactly one fresh retry after loop noise protection stopped automation. */
    public TaskRow retryWaitingLoop(String taskId) {
        LoopRetryPreparation preparation = transactions.execute(status -> prepareWaitingLoopRetry(taskId));
        if (preparation == null) throw new ConflictException("LOOP_RETRY_PREPARATION_FAILED", "Unable to prepare the explicit loop retry");
        startNewAttempt(get(taskId), preparation.stage(), preparation.prompt());
        return get(taskId);
    }

    private LoopRetryPreparation prepareWaitingLoopRetry(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.WAITING_INPUT.name().equals(task.state())) {
            throw new ConflictException("LOOP_RETRY_NOT_WAITING", "Only a task waiting on loop noise protection can be retried");
        }
        LoopRetryStatus retryStatus = loopRetryStatus(task);
        if (!retryStatus.loopRetryAvailable()) {
            throw new ConflictException("LOOP_RETRY_NOT_ACTIONABLE", "This waiting task requires a different explicit resolution");
        }
        if (!mapper.activeSessions(task.id()).isEmpty() || hasUnconfirmedWriter(task.id())) {
            throw new ConflictException("SESSION_WRITER_ACTIVE", "A fresh retry cannot overlap an existing or unconfirmed writer");
        }
        StageRow stage = mapper.listStages(task.id()).stream()
                .filter(row -> StageState.RUNNING.name().equals(row.state())).findFirst()
                .orElseThrow(() -> new ConflictException("STAGE_NOT_RUNNING", "The waiting task has no active stage to retry"));
        LoopSpec spec = spec(task);
        if (mapper.countAttemptsForTask(task.id()) >= spec.limits().maxTaskAttempts()
                || mapper.countAttemptsForStage(stage.id()) >= spec.limits().maxStageAttempts()) {
            throw new ConflictException("ATTEMPT_LIMIT_EXHAUSTED", "The configured attempt limit was reached");
        }
        if (isAdmittedInPlace(task)) {
            try { directLeases.requireWritableLease(inPlaceRoot(task), task.id()); }
            catch (TaskFailure failure) { throw new ConflictException(failure.code(), failure.getMessage()); }
        }
        TaskArtifactRow handoffArtifact = mapper.listTaskArtifacts(task.id()).stream()
                .filter(artifact -> ATTEMPT_HANDOFF_ARTIFACT_KIND.equals(artifact.kind()))
                .filter(artifact -> stage.id().equals(metadataText(artifact, "stageId")))
                .findFirst()
                .orElseThrow(() -> new ConflictException("ATTEMPT_HANDOFF_MISSING",
                        "The latest Attempt handoff required for this retry is missing"));
        AttemptHandoffService.Capture handoff;
        try {
            handoff = json.readValue(handoffArtifact.content(), AttemptHandoffService.Capture.class);
        } catch (Exception unreadable) {
            throw new ConflictException("ATTEMPT_HANDOFF_INVALID",
                    "The latest Attempt handoff required for this retry is unreadable");
        }
        if (!stage.id().equals(handoff.stageId()) || handoff.attemptId() == null || handoff.attemptId().isBlank()) {
            throw new ConflictException("ATTEMPT_HANDOFF_INVALID",
                    "The latest Attempt handoff does not belong to the active stage");
        }
        String prompt = attemptHandoffs.explicitRetryPrompt(handoff, spec.nextAttemptPromptTemplate());
        persistArtifact(task, null, null, LOOP_STAGNATION_OVERRIDE_ARTIFACT_KIND,
                "loop-stagnation-override.json", "application/json",
                write(Map.of("stageId", stage.id(), "source", "LOCAL_UI", "approvedAt", now())),
                Map.of("stageId", stage.id(), "source", "LOCAL_UI"));
        updateTask(state(task, TaskState.RUNNING));
        events.emit(task.id(), "task.loop_retry_requested", Map.of("state", TaskState.RUNNING.name(),
                "stageId", stage.id(), "source", "LOCAL_UI", "freshSession", true));
        return new LoopRetryPreparation(mapper.findStage(stage.id()).orElse(stage), prompt);
    }

    public LoopRetryStatus loopRetryStatus(String taskId) {
        return loopRetryStatus(get(taskId));
    }

    private LoopRetryStatus loopRetryStatus(TaskRow task) {
        if (!TaskState.WAITING_INPUT.name().equals(task.state())) return new LoopRetryStatus(null, false);
        ErrorEventRow waitingReason = mapper.listErrors(task.id()).stream()
                .filter(error -> TaskState.WAITING_INPUT.name().equals(errorEvidenceText(error, "resolution")))
                .findFirst().orElse(null);
        String code = waitingReason == null ? null : waitingReason.code();
        boolean available = "LOOP_STAGNATION_DETECTED".equals(code)
                || "LOOP_FRESH_SESSION_REQUIRED".equals(code);
        return new LoopRetryStatus(code, available);
    }

    private String errorEvidenceText(ErrorEventRow error, String field) {
        try { return json.readTree(error.evidenceJson()).path(field).asText(""); }
        catch (Exception unreadable) { return ""; }
    }

    public record LoopRetryStatus(String waitingReasonCode, boolean loopRetryAvailable) { }
    public record WorkspaceDirtyResolution(TaskRow task, GitWorktreeManager.DirtyWorkspace workspace) { }
    private record LoopRetryPreparation(StageRow stage, String prompt) { }

    public GitWorktreeManager.DirtyWorkspace workspaceDirtyStatus(String taskId) {
        TaskRow task = requireWorkspaceDirtyWait(taskId);
        return worktrees.inspectDirtyWorkspace(inPlaceRoot(task));
    }

    /** Local-UI boundary for applying a complete, snapshot-bound per-file cleanup decision. */
    public WorkspaceDirtyResolution resolveDirtyWorkspace(
            String taskId, String expectedSnapshot,
            List<GitWorktreeManager.DirtyFileResolution> resolutions, String commitMessage) {
        TaskRow waiting = requireWorkspaceDirtyWait(taskId);
        Path root = inPlaceRoot(waiting);
        directLeases.requireWritableLease(root, waiting.id());
        GitWorktreeManager.DirtyWorkspace workspace;
        try {
            workspace = worktrees.resolveDirtyWorkspace(root, expectedSnapshot, resolutions, commitMessage);
        } catch (TaskFailure failure) {
            throw new ConflictException(failure.code(), failure.getMessage());
        }
        if (!workspace.clean()) return new WorkspaceDirtyResolution(get(taskId), workspace);

        updateTask(state(get(taskId), TaskState.PREPARING), LifecycleEvent.RETRY_PREPARATION);
        events.emit(taskId, "workspace.cleanup_confirmed",
                Map.of("state", TaskState.PREPARING.name(), "source", "LOCAL_UI"));
        try {
            TaskRow prepared = prepareAdmittedInPlaceTask(taskId);
            return new WorkspaceDirtyResolution(prepared, worktrees.inspectDirtyWorkspace(root));
        } catch (TaskFailure failure) {
            if ("SOURCE_BRANCH_WORKSPACE_DIRTY".equals(failure.code())) {
                TaskRow paused = waitForDirtyWorkspace(get(taskId), failure.getMessage());
                return new WorkspaceDirtyResolution(paused, worktrees.inspectDirtyWorkspace(root));
            }
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
            return new WorkspaceDirtyResolution(get(taskId), worktrees.inspectDirtyWorkspace(root));
        }
    }

    /** Explicit dialog cancellation is a preparation failure, not a rollback or ordinary task cancellation. */
    public TaskRow failDirtyWorkspace(String taskId) {
        TaskRow task = requireWorkspaceDirtyWait(taskId);
        failTask(task, "SOURCE_BRANCH_WORKSPACE_CANCELLED",
                "User cancelled source-workspace cleanup before the Task branch was created", null, null, null);
        return get(taskId);
    }

    private TaskRow requireWorkspaceDirtyWait(String taskId) {
        TaskRow task = get(taskId);
        LoopRetryStatus wait = loopRetryStatus(task);
        if (!TaskState.WAITING_INPUT.name().equals(task.state())
                || !"SOURCE_BRANCH_WORKSPACE_DIRTY".equals(wait.waitingReasonCode())) {
            throw new ConflictException("SOURCE_BRANCH_WORKSPACE_NOT_ACTIONABLE",
                    "This task is not waiting for source-workspace cleanup");
        }
        if (task.branchName() != null || task.worktreePath() != null) {
            throw new ConflictException("SOURCE_BRANCH_WORKSPACE_ALREADY_PREPARED",
                    "The task execution workspace has already been prepared");
        }
        return task;
    }

    private TaskRow waitForDirtyWorkspace(TaskRow task, String message) {
        GitWorktreeManager.DirtyWorkspace workspace = worktrees.inspectDirtyWorkspace(inPlaceRoot(task));
        List<Map<String, Object>> files = workspace.files().stream().map(file -> {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("path", file.path());
            if (file.originalPath() != null) evidence.put("originalPath", file.originalPath());
            evidence.put("indexStatus", file.indexStatus());
            evidence.put("workTreeStatus", file.workTreeStatus());
            evidence.put("untracked", file.untracked());
            return evidence;
        }).toList();
        recordError(task, null, null, null, ErrorLayer.TASK, "SOURCE_BRANCH_WORKSPACE_DIRTY",
                message, true, Map.of("resolution", TaskState.WAITING_INPUT.name(),
                        "snapshotId", workspace.snapshotId(), "files", files));
        updateTask(state(task, TaskState.WAITING_INPUT), LifecycleEvent.REQUIRE_INPUT);
        events.emit(task.id(), "task.workspace_cleanup_required",
                Map.of("state", TaskState.WAITING_INPUT.name(), "fileCount", workspace.files().size()));
        return get(task.id());
    }

    private VerificationContinuation completeStageState(TaskRow task, StageRow stage, AttemptRow attempt) {
        updateAttempt(finish(attempt, AttemptState.SUCCEEDED, null, "所有确定性验证均已通过"));
        updateStage(stageState(stage, StageState.SUCCEEDED));
        StageRow next = mapper.listStages(task.id()).stream().filter(s -> StageState.PENDING.name().equals(s.state())).findFirst().orElse(null);
        if (next == null) {
            updateTask(state(get(task.id()), TaskState.JUDGING));
            return VerificationContinuation.finalReview(task.id(), attempt.id(), stage.id());
        } else {
            updateTask(state(get(task.id()), TaskState.RUNNING));
            return VerificationContinuation.nextStage(task.id(), next.id(),
                    "Start next stage: " + next.objective());
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
            if (mapper.latestJudgeRun(task.id(), role).isEmpty()) launchJudge(task, finalAttempt, role, false);
        }
        if (TaskState.JUDGING.name().equals(get(task.id()).state())) {
            events.emit(task.id(), "task.judging", Map.of("state", TaskState.JUDGING.name(), "judges", List.of("REQUIREMENT", "RISK")));
        }
    }

    /**
     * Explicit local-UI review is the recovery path for missing, rejected, malformed, or
     * retry-exhausted Judge runs. It authorizes exactly one fresh pair of read-only sessions;
     * later transport failures still return to WAITING_INPUT instead of looping forever.
     */
    @Transactional
    public TaskRow retryJudges(String taskId) {
        TaskRow task = get(taskId);
        if (mapper.findTaskPublication(taskId).map(io.opencode.loopper.persistence.TaskPublicationRow::state)
                .filter(io.opencode.loopper.domain.TaskPublicationState.MERGED.name()::equals).isPresent()) {
            throw new ConflictException("TASK_PUBLICATION_MERGED", "任务已经合并，不能重新打开原任务评审；请使用新分支重做");
        }
        if (!TaskState.WAITING_INPUT.name().equals(task.state()) && !TaskState.SUCCEEDED.name().equals(task.state())) {
            throw new ConflictException("JUDGE_REVIEW_NOT_ACTIONABLE",
                    "只有等待评审处理或缺少最终评审的已完成任务可以重新发起双评审");
        }
        if (!mapper.activeJudgeRuns(task.id()).isEmpty()) {
            throw new ConflictException("JUDGE_REVIEW_ALREADY_RUNNING", "双评审仍在运行，无需重复启动");
        }
        StageRow finalStage = mapper.listStages(task.id()).stream()
                .max(java.util.Comparator.comparingInt(StageRow::ordinal))
                .orElseThrow(() -> new ConflictException("JUDGE_FINAL_STAGE_MISSING", "任务没有可评审的最终阶段"));
        AttemptRow finalAttempt = mapper.latestAttempt(finalStage.id())
                .orElseThrow(() -> new ConflictException("JUDGE_FINAL_ATTEMPT_MISSING", "最终阶段没有可评审的执行记录"));
        if (!StageState.SUCCEEDED.name().equals(finalStage.state())
                || !AttemptState.SUCCEEDED.name().equals(finalAttempt.state())) {
            throw new ConflictException("JUDGE_DETERMINISTIC_ACCEPTANCE_REQUIRED",
                    "只有最终阶段确定性验收通过后才能启动双评审");
        }
        JudgeRunRow requirement = mapper.latestJudgeRun(task.id(), "REQUIREMENT").orElse(null);
        JudgeRunRow risk = mapper.latestJudgeRun(task.id(), "RISK").orElse(null);
        if (approved(requirement) && approved(risk)) {
            throw new ConflictException("JUDGE_REVIEW_ALREADY_APPROVED", "需求与风险双评审已经通过");
        }

        updateTask(state(task, TaskState.JUDGING));
        events.emit(task.id(), "task.judge_retry_requested", Map.of(
                "state", TaskState.JUDGING.name(), "source", "LOCAL_UI", "judges", List.of("REQUIREMENT", "RISK")));
        for (String role : List.of("REQUIREMENT", "RISK")) {
            if (!TaskState.JUDGING.name().equals(get(task.id()).state())) break;
            launchJudge(get(task.id()), finalAttempt, role, true);
        }
        return get(task.id());
    }

    private boolean approved(JudgeRunRow judge) {
        return judge != null && JudgeRunState.COMPLETED.name().equals(judge.state()) && "PASS".equals(judge.verdict());
    }

    private void launchJudge(TaskRow inputTask, AttemptRow finalAttempt, String role, boolean explicitLocalRetry) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        LoopSpec spec = spec(task);
        if (!explicitLocalRetry && mapper.countJudgeSessionErrors(task.id(), role) >= spec.limits().sessionErrorLimit()) {
            waitForJudgeInput(task, finalAttempt, null, "JUDGE_SESSION_RETRY_EXHAUSTED",
                    role + " Judge exhausted its configured session retry limit");
            return;
        }
        if (!explicitLocalRetry && blockModelCallForBudget(task, null, finalAttempt)) return;
        JudgeRunRow judge = new JudgeRunRow(UUID.randomUUID().toString(), task.id(), finalAttempt.id(), role,
                mapper.nextJudgeOrdinal(task.id(), role), null, JudgeRunState.CREATING.name(), null, null, null, now(), null, 0);
        lifecycle.create(subject(LifecycleMachineType.JUDGE_RUN, judge.id(), judge.taskId()), judge.state(),
                Map.of("role", judge.role()), () -> mapper.insertJudgeRun(judge),
                () -> new ConflictException("JUDGE_CREATE_CONFLICT", "Judge run could not be created"));
        persistArtifact(task, finalAttempt.id(), judge.id(), "JUDGE_LOG_METADATA", role.toLowerCase() + "-judge-start.json",
                "application/json", write(Map.of("role", role, "state", JudgeRunState.CREATING.name(), "readOnly", true)),
                Map.of("source", "judge-session", "readOnly", true));
        OpenCodeClient.OpenCodeSession remote;
        try {
            Path worktree = Path.of(requireWorktree(task));
            remote = openCode.createReadOnlySession(worktree, roleTitle(role), model(spec));
            JudgeRunRow running = judgeState(judge, remote.id(), JudgeRunState.RUNNING, null, null, null, null);
            updateJudge(running);
            openCode.promptAsync(remote, judgePrompt(task, finalAttempt, role));
        } catch (SessionFailure failure) {
            handleJudgeSessionFailure(task, judge, failure);
            return;
        } catch (RuntimeException exception) {
            handleJudgeSessionFailure(task, judge, new SessionFailure("JUDGE_SESSION_RUNTIME_ERROR", safeMessage(exception)));
            return;
        }
        events.emit(task.id(), "judge.started", Map.of("judgeRunId", judge.id(), "role", role, "externalSessionId", remote.id(), "readOnly", true));
    }

    private void pollJudge(TaskRow inputTask, JudgeRunRow inputJudge) {
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (!JudgeRunState.RUNNING.name().equals(judge.state()) || judge.externalSessionId() == null) return;
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
        if (!JudgeRunState.RUNNING.name().equals(judge.state())) return;
        JudgeDecision decision = parseJudgeDecision(rawOutput);
        String verdict = decision.verdict();
        String reason = decision.reason();
        if (verdict == null) {
            verdict = "UNPARSEABLE";
            reason = decision.parseError();
        }
        JudgeRunRow completed = judgeState(judge, judge.externalSessionId(), JudgeRunState.COMPLETED,
                verdict, reason, rawOutput, now());
        updateJudge(completed);
        usageInsights.collectTerminalJudgeUsage(inputTask.id(), completed.id());
        persistArtifact(inputTask, judge.attemptId(), judge.id(), "JUDGE_RESULT", judge.role().toLowerCase() + "-judge-result.txt",
                "text/plain", rawOutput == null ? "" : rawOutput,
                Map.of("role", judge.role(), "verdict", verdict, "reason", reason,
                        "state", JudgeRunState.COMPLETED.name()));
        events.emit(inputTask.id(), "judge.completed", Map.of("judgeRunId", judge.id(), "role", judge.role(), "verdict", verdict));
    }

    private void handleJudgeSessionFailure(TaskRow inputTask, JudgeRunRow inputJudge, SessionFailure failure) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (!JudgeRunState.CREATING.name().equals(judge.state())
                && !JudgeRunState.RUNNING.name().equals(judge.state())) return;
        JudgeRunRow failed = judgeState(judge, judge.externalSessionId(), JudgeRunState.SESSION_ERROR,
                null, safeMessage(failure.getMessage()), null, now());
        updateJudge(failed);
        usageInsights.collectTerminalJudgeUsage(task.id(), failed.id());
        persistArtifact(task, judge.attemptId(), judge.id(), "JUDGE_LOG_METADATA", judge.role().toLowerCase() + "-judge-session-error.json",
                "application/json", write(Map.of("role", judge.role(), "code", failure.code(),
                        "message", safeMessage(failure.getMessage()), "state", JudgeRunState.SESSION_ERROR.name())),
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
        if (attempt != null) launchJudge(task, attempt, judge.role(), false);
    }

    private void evaluateJudgeDecision(TaskRow task) {
        JudgeRunRow requirement = mapper.latestJudgeRun(task.id(), "REQUIREMENT").orElse(null);
        JudgeRunRow risk = mapper.latestJudgeRun(task.id(), "RISK").orElse(null);
        if (requirement == null || risk == null
                || !JudgeRunState.COMPLETED.name().equals(requirement.state())
                || !JudgeRunState.COMPLETED.name().equals(risk.state())) return;
        if (!"PASS".equals(requirement.verdict()) || !"PASS".equals(risk.verdict())) {
            String code = !requirement.verdict().equals(risk.verdict()) ? "JUDGE_CONFLICT" : "JUDGE_REVIEW_NOT_APPROVED";
            String message = "Requirement Judge=" + requirement.verdict() + ": " + safeMessage(requirement.reason())
                    + " | Risk Judge=" + risk.verdict() + ": " + safeMessage(risk.reason());
            AttemptRow attempt = mapper.findAttempt(requirement.attemptId()).orElse(null);
            waitForJudgeInput(task, attempt, null, code, message);
            return;
        }
        // Reconcile both final-review sessions before the task becomes terminal. This also
        // repairs a prior terminal Judge row after a restart or an optimistic-lock retry;
        // session_usage uses the judge-run/message idempotency key, so repeated polling is safe.
        usageInsights.collectTaskUsage(task.id());
        updateTask(state(get(task.id()), TaskState.SUCCEEDED));
        events.emit(task.id(), "task.succeeded", Map.of("state", TaskState.SUCCEEDED.name(), "judges", List.of("REQUIREMENT", "RISK")));
        settleTerminalInPlaceLease(get(task.id()), !hasUnconfirmedWriter(task.id()), "TASK_SUCCEEDED");
    }

    private void waitForJudgeInput(TaskRow inputTask, AttemptRow attempt, JudgeRunRow judge, String code, String message) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        abortJudgeSessions(task);
        // Final review is a verification outcome, not a field-validation issue and not a
        // terminal task fault.  Keeping it visible in the existing verification panel makes
        // the WAITING_INPUT reason discoverable without violating task/session error layering.
        recordError(task, null, attempt, null, ErrorLayer.VERIFICATION, code, message, false,
                Map.of("judgeRunId", judge == null ? "" : judge.id(), "judgeRole", judge == null ? "" : judge.role(),
                        "resolution", TaskState.WAITING_INPUT.name()));
        updateTask(state(get(task.id()), TaskState.WAITING_INPUT));
        events.emit(task.id(), "task.judge_waiting_input", Map.of("state", TaskState.WAITING_INPUT.name(), "code", code, "message", safeMessage(message)));
    }

    /** Commits the soft-budget decision before an auxiliary provider model call such as Session summarize. */
    @Transactional
    public UsageInsightsService.BudgetDecision guardNextModelCall(String taskId, String operation) {
        TaskRow task = get(taskId);
        if (task.loopDraftId() == null) {
            usageInsights.collectTaskUsage(taskId);
            return new UsageInsightsService.BudgetDecision(false, null, null, usageInsights.usage(taskId));
        }
        return enforceBudgetBeforeModelCall(task, null, null, operation == null ? "MODEL_CALL" : operation);
    }

    /** Gate before createSession/createReadOnlySession so no external session or judge row is created past a soft budget. */
    private boolean blockModelCallForBudget(TaskRow inputTask, StageRow stage, AttemptRow attempt) {
        return enforceBudgetBeforeModelCall(get(inputTask.id()), stage, attempt, "TASK_LOOP").blocked();
    }

    private UsageInsightsService.BudgetDecision enforceBudgetBeforeModelCall(TaskRow task, StageRow stage,
                                                                              AttemptRow attempt, String operation) {
        UsageInsightsService.BudgetDecision decision = usageInsights.budget(task, spec(task));
        if (!decision.blocked()) return decision;
        recordError(task, stage, attempt, null, ErrorLayer.TASK, decision.code(), decision.message(), false,
                Map.of("usage", decision.usage(), "resolution", TaskState.WAITING_INPUT.name(), "nextCallBlocked", true,
                        "operation", operation));
        String nextState = task.state();
        if (!TaskState.valueOf(task.state()).terminal()) {
            updateTask(state(task, TaskState.WAITING_INPUT));
            nextState = TaskState.WAITING_INPUT.name();
        }
        events.emit(task.id(), "task.budget_waiting_input", Map.of("state", nextState,
                "code", decision.code(), "message", decision.message(), "nextCallBlocked", true,
                "operation", operation));
        return decision;
    }

    private void captureFinalEvidence(TaskRow task, AttemptRow attempt) {
        List<VerificationResultRow> verificationRows = mapper.listVerifications(attempt.id());
        if (mapper.findFirstTaskArtifactByKind(task.id(), "VERIFICATION_SUMMARY").isEmpty()) {
            persistArtifact(task, attempt.id(), null, "VERIFICATION_SUMMARY", "verification-summary.json", "application/json",
                    write(Map.of("attemptId", attempt.id(), "allPassed", verificationRows.stream().allMatch(row -> VerificationState.PASS.name().equals(row.state())), "results", verificationRows)),
                    Map.of("source", "deterministic-verifier", "count", verificationRows.size()));
        }
        boolean alreadyCaptured = mapper.listTaskArtifacts(task.id()).stream()
                .anyMatch(artifact -> "GIT_DIFF".equals(artifact.kind()) && attempt.id().equals(artifact.attemptId()));
        if (alreadyCaptured) return;
        VerifierOutcome snapshot = verifiers.verify(Path.of(requireWorktree(task)), task.baselineCommit(),
                new LoopSpec.VerifierSpec("GIT_DIFF", null, null, false, List.of(), List.of(), false),
                Duration.ofSeconds(10));
        if (snapshot.state() != VerificationState.PASS) {
            throw new TaskFailure("TASK_DIFF_CAPTURE_FAILED", snapshot.summary());
        }
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot.evidence());
        metadata.put("source", "deterministic-task-baseline-diff");
        metadata.put("taskBranch", task.branchName());
        metadata.put("attemptId", attempt.id());
        persistArtifact(task, attempt.id(), null, "GIT_DIFF", "task-diff.json", "application/json",
                write(metadata), metadata);
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

    /** Freezes the last validator-accepted Designer Markdown when the Task is created. */
    private void persistConfirmedDesignContext(TaskRow task, LoopDraftRow draft) {
        mapper.findLatestPersistedDesignerMessageByDraft(draft.id()).ifPresent(message ->
                persistArtifact(task, null, null, DESIGN_CONTEXT_ARTIFACT_KIND, "confirmed-designer-design.md",
                        "text/markdown", message.content(), Map.of(
                                "draftId", draft.id(),
                                "designerSessionId", message.designerSessionId(),
                                "designerMessageId", message.id(),
                                "deliveryState", message.deliveryState())));
    }

    private void abortJudgeSessions(TaskRow task) {
        if (task.worktreePath() == null) return;
        Path worktree = Path.of(task.worktreePath());
        for (JudgeRunRow judge : mapper.activeJudgeRuns(task.id())) {
            if (judge.externalSessionId() != null) {
                try { openCode.abort(new OpenCodeClient.OpenCodeSession(judge.externalSessionId(), worktree)); }
                catch (SessionFailure ignored) { /* terminal task decision and stored judge evidence remain authoritative */ }
            }
            JudgeRunRow aborted = judgeState(judge, judge.externalSessionId(), JudgeRunState.ABORTED,
                    judge.verdict(), judge.reason(), judge.rawOutput(), now());
            updateJudge(aborted);
            usageInsights.collectTerminalJudgeUsage(task.id(), aborted.id());
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
                + focus + "\n必须逐项评审下面列出的 AI 验收合同；MACHINE 条件由确定性验证负责，不要把计划中的 Judge 评审误写成已由机器证明。\n"
                + "已确认目标：" + loopSpec.goal() + "\n上下文：" + loopSpec.context()
                + "\n跨阶段 AI 验收合同：\n" + judgeCriteria(loopSpec)
                + "\n最终阶段目标：\n- " + objectives + "\n确定性验证摘要：\n" + verification
                + "\n已持久化的 Git 差异证据：\n" + diff + "\n尝试记录：" + attempt.id()
                + "\n仅返回一个 JSON 对象，不得附加说明或代码围栏："
                + "{\"verdict\":\"PASS|REVISE|BLOCKED\",\"reason\":\"简洁、基于证据的中文 Markdown\"}。"
                + "`verdict` 必须保留上述英文协议值；`reason` 必须使用简体中文。"
                + "在 `reason` 中先写一句结论，再写 `## 证据` 标题和编号列表；命令与文件路径使用行内代码。"
                + "若结论不是 PASS，再增加 `## 必须处理` 标题和编号列表。"
                + "不要使用围栏代码块，并将 `reason` 内的每个换行正确转义为 JSON 字符串。";
    }

    private String judgeCriteria(LoopSpec loopSpec) {
        StringBuilder result = new StringBuilder();
        for (int stageIndex = 0; stageIndex < loopSpec.stages().size(); stageIndex++) {
            LoopSpec.StageSpec stage = loopSpec.stages().get(stageIndex);
            for (LoopSpec.AcceptanceCriterion criterion : stage.acceptanceCriteria()) {
                if (!Set.of("JUDGE", "BOTH").contains(criterion.verificationMode())) continue;
                result.append("- 阶段 ").append(stageIndex + 1).append("（")
                        .append(stage.objective()).append("） ").append(criterion.id())
                        .append(" [").append(criterion.verificationMode()).append("]: ")
                        .append(criterion.description()).append("\n  评审准则：")
                        .append(criterion.judgeRubric());
                if ("JUDGE".equals(criterion.verificationMode())) {
                    result.append("\n  仅 AI 评审原因：").append(criterion.judgeOnlyReason());
                }
                result.append('\n');
            }
        }
        return result.isEmpty()
                ? "- 此草案没有显式 JUDGE/BOTH 条件；按兼容规则评审整体需求与风险。"
                : result.toString().stripTrailing();
    }

    private JudgeRunRow judgeState(JudgeRunRow row, String externalSessionId, JudgeRunState state, String verdict, String reason,
                                   String rawOutput, String endedAt) {
        return new JudgeRunRow(row.id(), row.taskId(), row.attemptId(), row.role(), row.ordinal(), externalSessionId, state.name(),
                verdict, safeNullable(reason), rawOutput, row.createdAt(), endedAt, row.version());
    }
    private void updateJudge(JudgeRunRow row) {
        JudgeRunRow current = mapper.findJudgeRun(row.id()).orElseThrow(() -> new NotFoundException("Judge run not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.JUDGE_RUN, row.id(), row.taskId()), current.state(), row.state(),
                null, Map.of("role", row.role()), () -> mapper.updateJudgeRun(row),
                () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
    }
    private String safeNullable(String value) { return value == null ? null : safeMessage(value); }
    private record PendingVerification(String id, int index, VerifierOutcome outcome) { }
    private enum VerificationAction { NONE, RETRY_STAGE, NEXT_STAGE, FINAL_REVIEW }
    private record VerificationContinuation(VerificationAction action, String taskId, String stageId,
                                            String attemptId, String prompt) {
        private static VerificationContinuation none(String taskId) {
            return new VerificationContinuation(VerificationAction.NONE, taskId, null, null, null);
        }
        private static VerificationContinuation retry(String taskId, String stageId, String prompt) {
            return new VerificationContinuation(VerificationAction.RETRY_STAGE, taskId, stageId, null, prompt);
        }
        private static VerificationContinuation nextStage(String taskId, String stageId, String prompt) {
            return new VerificationContinuation(VerificationAction.NEXT_STAGE, taskId, stageId, null, prompt);
        }
        private static VerificationContinuation finalReview(String taskId, String attemptId, String stageId) {
            return new VerificationContinuation(VerificationAction.FINAL_REVIEW, taskId, stageId, attemptId, null);
        }
    }
    private record JudgeDecision(String verdict, String reason, String parseError) { }

    private void failTask(TaskRow task, String code, String message, StageRow stage, AttemptRow attempt, ExecutionSessionRow session) {
        TaskRow current = mapper.findTask(task.id()).orElse(task);
        if (TaskState.valueOf(current.state()).terminal()) return;
        boolean writersStopped = abortSessions(current);
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
        for (StageRow active : mapper.listStages(current.id())) {
            if (StageState.RUNNING.name().equals(active.state()) || StageState.PAUSED.name().equals(active.state())) {
                updateStage(stageState(active, StageState.FAILED));
            }
        }
        recordError(current, stage, attempt, session, ErrorLayer.TASK, code, message, false, Map.of());
        updateTask(state(get(current.id()), TaskState.FAILED));
        events.emit(current.id(), "task.failed", Map.of("state", TaskState.FAILED.name(), "code", code, "message", message));
        settleTerminalInPlaceLease(get(current.id()), writersStopped && !hasUnconfirmedWriter(current.id()), code);
    }

    private void failTaskForManagedRuntime(String taskId, VerifierOutcome outcome) {
        TaskRow task = get(taskId);
        String code = String.valueOf(outcome.evidence().getOrDefault("code", "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED"));
        failTask(task, code, outcome.summary(), null, null, null);
    }

    private boolean abortSessions(TaskRow task) {
        boolean allStopped = true;
        for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("cleanup", "terminal_task");
            boolean stopped = confirmStoppedBeforeRetry(task, session, evidence);
            AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
            StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
            if (stopped) {
                updateSession(sessionState(session, SessionState.ABORTED));
            } else {
                allStopped = false;
                updateSession(sessionState(session, SessionState.DISCONNECTED));
                recordAbortUnconfirmed(task, stage, attempt, session,
                        "Task became terminal before its mutating Session could be confirmed stopped",
                        evidence);
            }
        }
        return allStopped && !hasUnconfirmedWriter(task.id());
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
        if (isAdmittedInPlace(task)) {
            directLeases.markWriterUnconfirmed(inPlaceRoot(task), task.id(), session.id(), message);
        }
        events.emit(task.id(), "session.cleanup_pending", Map.of("sessionId", session.id()));
    }

    private TaskRow prepareAdmittedInPlaceTask(String taskId) {
        TaskRow task = get(taskId);
        TaskQueueRow queue = mapper.findTaskQueue(taskId)
                .orElseThrow(() -> new TaskFailure("DIRECT_QUEUE_MISSING", "Admitted in-place task has no queue record"));
        if (!TaskQueueState.ADMITTED.name().equals(queue.state())) {
            throw new TaskFailure("DIRECT_QUEUE_NOT_ADMITTED", "In-place task cannot prepare before FIFO admission");
        }
        Path root = inPlaceRoot(task);
        directLeases.requireWritableLease(root, task.id());
        if (TaskState.QUEUED.name().equals(task.state())) {
            updateTask(state(task, TaskState.PREPARING));
            task = get(taskId);
        }
        boolean gitSourceBranch = worktrees.inspect(root).isolatedWorktree();
        GitWorktreeManager.Worktree worktree = gitSourceBranch
                ? worktrees.checkoutSourceBranch(root, task.id(), task.title(), task.baselineCommit())
                : worktrees.create(root, task.id());
        if (task.baselineCommit() != null && GitWorktreeManager.DIRECT_BRANCH.equals(worktree.branch())) {
            throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires a Git source branch");
        }
        return persistPreparedTask(task.id(), worktree);
    }

    private TaskRow persistPreparedTask(String taskId, GitWorktreeManager.Worktree worktree) {
        TaskRow task = get(taskId);
        TaskRow prepared = new TaskRow(task.id(), task.projectId(), task.loopDraftId(), task.title(), TaskState.READY.name(),
                worktree.path().toString(), worktree.branch(), worktree.sourceBranch(), worktree.baselineCommit(),
                task.createdAt(), now(), task.version());
        lifecycle.transition(subject(LifecycleMachineType.TASK, prepared.id(), prepared.id()), task.state(), prepared.state(),
                null, Map.of("workspaceMode", worktree.branch()), () -> mapper.prepareTask(prepared),
                () -> new ConflictException("TASK_VERSION_CONFLICT", "Task was updated concurrently"));
        TaskRow ready = get(taskId);
        events.emit(taskId, "task.ready", Map.of("state", ready.state(), "branch", worktree.branch(),
                "worktreePath", worktree.path().toString()));
        return ready;
    }

    private void settleTerminalInPlaceLease(TaskRow task, boolean writerTerminationConfirmed, String reason) {
        if (!isAdmittedInPlace(task)) return;
        DirectWorkspaceLeaseCoordinator.Release release;
        try {
            if (!writerTerminationConfirmed || hasUnconfirmedWriter(task.id())) {
                mapper.listSessions(task.id()).stream().filter(session -> session.externalSessionId() != null)
                        .findFirst().ifPresent(session -> directLeases.markWriterUnconfirmed(
                                inPlaceRoot(task), task.id(), session.id(), reason));
                return;
            }
            if (task.branchName() == null) {
                release = directLeases.releaseAfterWriterStopped(inPlaceRoot(task), task.id(), reason);
            } else if (!GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
                if (worktrees.sourceCheckoutHasChanges(inPlaceRoot(task))) {
                    directLeases.retainAfterWriterStopped(inPlaceRoot(task), task.id(), reason);
                    events.emit(task.id(), "workspace.lease_retained",
                            Map.of("state", WorkspaceLeaseState.HELD.name(), "reason", reason,
                                    "waitingFor", "TASK_BRANCH_PUBLICATION_OR_CLEANUP"));
                    return;
                }
                // Centralize the clean-checkout restoration so restart recovery and non-publication
                // terminal paths cannot admit the next Task while the old Task branch is still checked out.
                worktrees.restoreSourceBranch(inPlaceRoot(task), task.branchName(), task.sourceBranch());
                release = directLeases.releaseAfterWriterStopped(inPlaceRoot(task), task.id(), reason);
            } else {
                release = directLeases.releaseAfterWriterStopped(inPlaceRoot(task), task.id(), reason);
            }
        } catch (TaskFailure leaseFailure) {
            recordError(task, null, null, null, ErrorLayer.TASK, leaseFailure.code(), leaseFailure.getMessage(), false,
                    Map.of("leaseRetained", true));
            return;
        }
        events.emit(task.id(), "workspace.lease_released",
                Map.of("state", WorkspaceLeaseState.RELEASED.name(), "reason", reason));
        if (release.admittedNext() == null) return;
        String nextTaskId = release.admittedNext().taskId();
        events.emit(nextTaskId, "task.admitted", Map.of("state", TaskState.QUEUED.name(),
                "queuePosition", release.admittedNext().position()));
        try {
            prepareAdmittedInPlaceTask(nextTaskId);
        } catch (TaskFailure failure) {
            if ("SOURCE_BRANCH_WORKSPACE_DIRTY".equals(failure.code())) {
                waitForDirtyWorkspace(get(nextTaskId), failure.getMessage());
            } else {
                failTask(get(nextTaskId), failure.code(), failure.getMessage(), null, null, null);
            }
        }
    }

    private void rehydrateDirectLeases() {
        for (DirectWorkspaceLeaseCoordinator.BlockingLease lease : directLeases.blockingLeases()) {
            if (!lease.rootAvailable() || !lease.fingerprintMatches() || lease.holderTaskId() == null) continue;
            TaskRow task = mapper.findTask(lease.holderTaskId()).orElse(null);
            if (task == null || !isAdmittedInPlace(task)) continue;
            if (TaskState.QUEUED.name().equals(task.state()) || TaskState.PREPARING.name().equals(task.state())) {
                try { prepareAdmittedInPlaceTask(task.id()); }
                catch (TaskFailure failure) { failTask(task, failure.code(), failure.getMessage(), null, null, null); }
                continue;
            }
            if (!TaskState.valueOf(task.state()).terminal()) continue;
            if (lease.writerSessionId() == null) {
                settleTerminalInPlaceLease(task, !hasUnconfirmedWriter(task.id()), "RESTART_TERMINAL_TASK");
                continue;
            }
            ExecutionSessionRow writer = mapper.listSessions(task.id()).stream()
                    .filter(session -> lease.writerSessionId().equals(session.id())).findFirst().orElse(null);
            if (writer == null || SessionState.DISCONNECTED.name().equals(writer.state())) continue;
            if (List.of(SessionState.COMPLETED.name(), SessionState.FAILED.name(), SessionState.TIMED_OUT.name(),
                    SessionState.ABORTED.name()).contains(writer.state())) {
                settleTerminalInPlaceLease(task, !hasUnconfirmedWriter(task.id()), "RESTART_WRITER_ALREADY_TERMINAL");
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("recovery", "lease_rehydrate");
            boolean stopped = confirmStoppedBeforeRetry(task, writer, evidence);
            if (stopped) {
                updateSession(sessionState(writer, SessionState.ABORTED));
                settleTerminalInPlaceLease(task, true, "RESTART_WRITER_TERMINAL_CONFIRMED");
            } else {
                updateSession(sessionState(writer, SessionState.DISCONNECTED));
                AttemptRow attempt = mapper.findAttempt(writer.attemptId()).orElse(null);
                StageRow stage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
                recordAbortUnconfirmed(task, stage, attempt, writer,
                        "Restart could not confirm the terminal task's Direct writer stopped", evidence);
            }
        }
    }

    private void requireInPlaceWritable(TaskRow task, ProjectRow project) {
        if (mapper.findTaskQueue(task.id()).isEmpty()) return;
        directLeases.requireWritableLease(Path.of(project.rootPath()), task.id());
    }

    private boolean isAdmittedInPlace(TaskRow task) {
        return mapper.findTaskQueue(task.id())
                .map(row -> TaskQueueState.ADMITTED.name().equals(row.state())).orElse(false);
    }

    private boolean isVerificationOnlyRecovery(String taskId) {
        return mapper.findTaskLineage(taskId)
                .map(lineage -> "VERIFY_ONLY".equals(lineage.recoveryMode())).orElse(false);
    }

    private boolean hasUnconfirmedWriter(String taskId) {
        if (mapper.listVerifierRuntimes(taskId).stream()
                .anyMatch(runtime -> List.of("STARTING", "RUNNING", "STOPPING", "DISCONNECTED")
                        .contains(runtime.state()))) return true;
        java.util.Set<String> confirmed = mapper.listErrors(taskId).stream()
                .filter(error -> "SESSION_ABORT_CLEANUP_CONFIRMED".equals(error.code()) && error.sessionId() != null)
                .map(ErrorEventRow::sessionId).collect(java.util.stream.Collectors.toSet());
        return mapper.listErrors(taskId).stream()
                .anyMatch(error -> "SESSION_ABORT_UNCONFIRMED".equals(error.code())
                        && error.sessionId() != null && !confirmed.contains(error.sessionId()));
    }

    private Path inPlaceRoot(TaskRow task) { return Path.of(projects.get(task.projectId()).rootPath()); }

    private long queuePosition(String taskId) {
        TaskQueueRow target = mapper.findTaskQueue(taskId)
                .orElseThrow(() -> new NotFoundException("Task queue entry not found: " + taskId));
        long position = 0;
        for (TaskQueueRow row : mapper.listTaskQueue(target.canonicalRoot())) {
            if (!TaskQueueState.QUEUED.name().equals(row.state())) continue;
            position++;
            if (taskId.equals(row.taskId())) return position;
        }
        return 0;
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
    private TaskRow state(TaskRow row, TaskState state) { return new TaskRow(row.id(), row.projectId(), row.loopDraftId(), row.title(), state.name(), row.worktreePath(), row.branchName(), row.sourceBranch(), row.baselineCommit(), row.createdAt(), now(), row.version()); }
    private StageRow stageState(StageRow row, StageState state) { return new StageRow(row.id(), row.taskId(), row.ordinal(), row.objective(), row.allowedPathsJson(), row.forbiddenPathsJson(), row.deliverablesJson(), row.verifiersJson(), state.name(), row.createdAt(), now(), row.version()); }
    private AttemptRow finish(AttemptRow row, AttemptState state, String failureKind, String summary) { return new AttemptRow(row.id(), row.taskId(), row.stageId(), row.ordinal(), state.name(), failureKind, safeMessage(summary), row.createdAt(), now(), row.version()); }
    private ExecutionSessionRow sessionState(ExecutionSessionRow row, SessionState state) { return new ExecutionSessionRow(row.id(), row.taskId(), row.stageId(), row.attemptId(), row.externalSessionId(), state.name(), row.createdAt(), now(), row.version()); }
    private void updateTask(TaskRow row) { updateTask(row, null); }
    private void updateTask(TaskRow row, LifecycleEvent event) {
        TaskRow current = mapper.findTask(row.id()).orElseThrow(() -> new NotFoundException("Task not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.TASK, row.id(), row.id()), current.state(), row.state(), event,
                null, Map.of(), () -> mapper.updateTaskState(row),
                () -> new ConflictException("TASK_VERSION_CONFLICT", "Task was updated concurrently"));
    }
    private void updateStage(StageRow row) {
        StageRow current = mapper.findStage(row.id()).orElseThrow(() -> new NotFoundException("Stage not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.STAGE, row.id(), row.taskId()), current.state(), row.state(),
                null, Map.of(), () -> mapper.updateStageState(row),
                () -> new ConflictException("STAGE_VERSION_CONFLICT", "Stage was updated concurrently"));
    }
    private void updateAttempt(AttemptRow row) {
        AttemptRow current = mapper.findAttempt(row.id()).orElseThrow(() -> new NotFoundException("Attempt not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.ATTEMPT, row.id(), row.taskId()), current.state(), row.state(),
                row.failureKind(), Map.of(), () -> mapper.finishAttempt(row),
                () -> new ConflictException("ATTEMPT_VERSION_CONFLICT", "Attempt was updated concurrently"));
    }
    private void updateSession(ExecutionSessionRow row) {
        ExecutionSessionRow current = mapper.findSession(row.id()).orElseThrow(() -> new NotFoundException("Session not found: " + row.id()));
        lifecycle.transition(subject(LifecycleMachineType.EXECUTION_SESSION, row.id(), row.taskId()), current.state(), row.state(),
                null, Map.of(), () -> mapper.updateSessionState(row),
                () -> new ConflictException("SESSION_VERSION_CONFLICT", "Session was updated concurrently"));
    }
    private void createAttempt(AttemptRow row) {
        lifecycle.create(subject(LifecycleMachineType.ATTEMPT, row.id(), row.taskId()), row.state(), Map.of(),
                () -> mapper.insertAttempt(row),
                () -> new ConflictException("ATTEMPT_CREATE_CONFLICT", "Attempt could not be created"));
    }
    private void createSession(ExecutionSessionRow row) {
        lifecycle.create(subject(LifecycleMachineType.EXECUTION_SESSION, row.id(), row.taskId()), row.state(), Map.of(),
                () -> mapper.insertSession(row),
                () -> new ConflictException("SESSION_CREATE_CONFLICT", "Session could not be created"));
    }
    private LifecycleTransitionService.Subject subject(LifecycleMachineType machine, String entityId, String taskId) {
        return new LifecycleTransitionService.Subject(machine, entityId, LifecycleScopeType.TASK, taskId);
    }
    private String requireWorktree(TaskRow task) { if (task.worktreePath() == null || task.worktreePath().isBlank()) throw new TaskFailure("WORKTREE_MISSING", "Task has no prepared execution workspace"); return task.worktreePath(); }
    private String normalizedTitle(String title, String goal) { return title == null || title.isBlank() ? goal.substring(0, Math.min(goal.length(), 120)) : title.trim(); }
    private String promptWithBoundaries(TaskRow task, LoopSpec spec, StageRow stage, Path executionWorkspace, String recovery) {
        String designContext = executionDesignContext(task.id());
        return "Authoritative execution workspace: " + executionWorkspace
                + "\nWorkspace branch: " + task.branchName()
                + "\nAll reads, writes, AgentBridge tool calls, searches, and commands must target this checkout and its current Task branch."
                + "\nDo not switch branches, create another worktree, or write outside this workspace."
                + "\nGoal: " + spec.goal() + "\nContext: " + spec.context() + "\nStage: " + stage.objective()
                + "\nAllowed paths: " + stage.allowedPathsJson() + "\nForbidden paths: " + stage.forbiddenPathsJson()
                + "\nDeliverables: " + stage.deliverablesJson() + "\nVerifier contract: " + stage.verifiersJson()
                + (designContext.isBlank() ? "" : "\nConfirmed Designer design snapshot (read-only context frozen at Task confirmation):"
                + "\nUse this snapshot to preserve architecture, implementation decisions, risks, and acceptance rationale. "
                + "If it conflicts with Goal, Context, Stage, path rules, Deliverables, or Verifier contract, the structured LoopSpec and Verifier contract are authoritative."
                + "\n----- BEGIN CONFIRMED DESIGN -----\n" + designContext + "\n----- END CONFIRMED DESIGN -----")
                + "\nLanguage requirement: 使用简体中文撰写面向用户的进度说明、结论、评审和最终总结。"
                + "代码、命令、路径、标识符、JSON 字段名、协议枚举值以及要求精确匹配的字面量保持原样；"
                + "仅当用户目标明确要求其他语言时才切换语言。\n" + recovery;
    }

    private String executionDesignContext(String taskId) {
        String content = mapper.findFirstTaskArtifactByKind(taskId, DESIGN_CONTEXT_ARTIFACT_KIND)
                .map(TaskArtifactRow::content).orElse("");
        if (content.length() <= MAX_EXECUTION_DESIGN_CONTEXT_CHARS) return content;
        return content.substring(0, MAX_EXECUTION_DESIGN_CONTEXT_CHARS)
                + "\n… confirmed design context truncated for this execution prompt; the complete snapshot remains persisted on the Task …";
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
