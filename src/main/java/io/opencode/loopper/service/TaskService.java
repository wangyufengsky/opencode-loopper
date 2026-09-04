package io.opencode.loopper.service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.ErrorLayer;
import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.ExecutionCycleKind;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.WorkspaceCheckpointState;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.domain.RetryCause;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.TodoCapability;
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
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.TaskRetryScheduleRow;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeStructuredSchemas;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.ArtifactMaterializationService;
import io.opencode.loopper.verification.VerifierOutcome;
import io.opencode.loopper.verification.JavaUnitTestGatePolicy;
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
 * Execution facade and owner of ordinary Task orchestration. Cancellation state closure and
 * writer termination proof are delegated to narrow collaborators; TaskFailure still becomes a
 * failed execution cycle only here, while SessionFailure closes its attempt before a fresh Session.
 */
@Service
public class TaskService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskService.class);
    private static final String LOCAL_SOURCE_SYNC_ARTIFACT_KIND = "LOCAL_SOURCE_SYNC";
    private static final String ATTEMPT_HANDOFF_ARTIFACT_KIND = "ATTEMPT_HANDOFF";
    private static final String LOOP_STAGNATION_OVERRIDE_ARTIFACT_KIND = "LOOP_STAGNATION_OVERRIDE";
    private static final String RETRY_SCHEDULED = "SCHEDULED";
    private static final String RETRY_PAUSED = "PAUSED";
    private static final String RETRY_CLAIMED = "CLAIMED";
    private static final String RETRY_CANCELLED = "CANCELLED";
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final DirectWorkspaceLeaseCoordinator directLeases;
    private final WorkspaceLeaseReconciliationService leaseReconciliation;
    private final OpenCodeClient openCode;
    private final VerifierEngine verifiers;
    private final ArtifactMaterializationService artifactMaterializer;
    private final AttemptHandoffService attemptHandoffs;
    private final BinaryArtifactPersistenceService binaryArtifacts;
    private final ManagedVerificationRuntimeService managedVerifierRuntimes;
    private final StageWorkspaceBaselineService stageWorkspaceBaselines;
    private final GitDiffScopeApprovalService gitDiffScopeApprovals;
    private final JavaChangeGateService javaChangeGate;
    private final UsageInsightsService usageInsights;
    private final TaskEventService events;
    private final TaskExecutionCycleService executionCycles;
    private final TaskWorkspaceCheckpointService workspaceCheckpoints;
    private final AiOutputAuditService aiOutputAudit;
    private final LoopperProperties defaults;
    private final TransactionTemplate transactions;
    private final TaskRetryPolicy retryPolicy;
    private final JudgeDecisionCandidateWorkflow judgeCandidates;
    private final JudgeReviewBatchService judgeBatches;
    private final LegacyJudgeTransport legacyJudgeTransport;
    private final LegacyJudgeCompletionService legacyJudgeCompletion;
    private final TaskExecutionPromptFactory executionPrompts;
    private final TaskStateStore taskStates;
    private final TaskEvidenceService taskEvidence;
    private final TaskCancellationCoordinator cancellations; private final TaskTerminalConsistencyService terminalConsistency;
    private final TaskWriterTerminationService writerTermination; private final RollingPackageTaskHooks rollingPackages;
    private final DesignerAttachmentContext attachmentContext;
    public TaskService(LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json, ProjectService projects,
                       GitWorktreeManager worktrees, DirectWorkspaceLeaseCoordinator directLeases,
                       WorkspaceLeaseReconciliationService leaseReconciliation,
                       OpenCodeClient openCode, VerifierEngine verifiers,
                       ArtifactMaterializationService artifactMaterializer,
                       AttemptHandoffService attemptHandoffs,
                       BinaryArtifactPersistenceService binaryArtifacts,
                       ManagedVerificationRuntimeService managedVerifierRuntimes,
                       StageWorkspaceBaselineService stageWorkspaceBaselines,
                       GitDiffScopeApprovalService gitDiffScopeApprovals,
                       JavaChangeGateService javaChangeGate,
                       UsageInsightsService usageInsights,
                       TaskEventService events,
                       AiOutputAuditService aiOutputAudit,
                       RolePromptComposer rolePrompts,
                       TaskExecutionCycleService executionCycles,
                       TaskWorkspaceCheckpointService workspaceCheckpoints,
                       TaskEvidenceService taskEvidence, RollingPackageTaskHooks rollingPackages,
                       JudgeDecisionCandidateWorkflow judgeCandidates,
                       JudgeReviewBatchService judgeBatches,
                       LegacyJudgeTransport legacyJudgeTransport,
                       LegacyJudgeCompletionService legacyJudgeCompletion,
                       DesignerTerminationService designerTermination, TaskTerminalConsistencyService terminalConsistency, DesignerAttachmentContext attachmentContext,
                       LoopperProperties defaults,
                       PlatformTransactionManager transactionManager) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.json = json; this.projects = projects;
        this.worktrees = worktrees; this.directLeases = directLeases; this.openCode = openCode;
        this.leaseReconciliation = leaseReconciliation;
        this.verifiers = verifiers; this.artifactMaterializer = artifactMaterializer;
        this.attemptHandoffs = attemptHandoffs; this.binaryArtifacts = binaryArtifacts;
        this.managedVerifierRuntimes = managedVerifierRuntimes;
        this.stageWorkspaceBaselines = stageWorkspaceBaselines;
        this.gitDiffScopeApprovals = gitDiffScopeApprovals;
        this.javaChangeGate = javaChangeGate;
        this.usageInsights = usageInsights; this.events = events;
        this.executionCycles = executionCycles; this.workspaceCheckpoints = workspaceCheckpoints;
        this.rollingPackages = rollingPackages; this.aiOutputAudit = aiOutputAudit; this.attachmentContext = attachmentContext;
        this.defaults = defaults;
        this.transactions = new TransactionTemplate(transactionManager);
        this.retryPolicy = new TaskRetryPolicy(defaults);
        this.judgeCandidates = judgeCandidates; this.judgeBatches = judgeBatches;
        this.legacyJudgeTransport = legacyJudgeTransport; this.legacyJudgeCompletion = legacyJudgeCompletion;
        this.executionPrompts = new TaskExecutionPromptFactory(mapper, json, rolePrompts);
        this.taskStates = new TaskStateStore(mapper, lifecycle);
        this.taskEvidence = taskEvidence; this.terminalConsistency = terminalConsistency;
        this.writerTermination = new TaskWriterTerminationService(mapper, taskStates, lifecycle, openCode,
                usageInsights, directLeases, projects, defaults, events, json,
                judgeCandidates, judgeBatches);
        this.cancellations = new TaskCancellationCoordinator(mapper, taskStates, managedVerifierRuntimes,
                events, executionCycles, writerTermination, designerTermination,
                rollingPackages, transactionManager);
    }
    public TaskRow createFromDraft(LoopDraftRow draft, String title) {
        return createFromDraft(draft, title, "MANUAL");
    }
    public TaskRow createFromDraft(LoopDraftRow draft, String title, String admissionSource) {
        return createFromDraft(draft, title, admissionSource, null);
    }
    public TaskRow createFromDraft(LoopDraftRow draft, String title, String admissionSource, String isolatedBaseline) {
        return createFromDraft(draft, title, admissionSource, isolatedBaseline, false);
    }
    public TaskRow createAndConfirmFromDraft(LoopDraftRow draft, String title, String admissionSource,
                                             String isolatedBaseline) {
        return createFromDraft(draft, title, admissionSource, isolatedBaseline, true);
    }
    private TaskRow createFromDraft(LoopDraftRow draft, String title, String admissionSource,
                                    String isolatedBaseline, boolean confirmDraft) {
        LoopSpec spec = readSpec(draft);
        ProjectRow project = projects.get(draft.projectId());
        Path projectRoot = Path.of(project.rootPath());
        boolean gitSourceBranch = worktrees.inspect(projectRoot).isolatedWorktree();
        if (isolatedBaseline != null && !gitSourceBranch) {
            throw new BadRequestException("REWORK_REPOSITORY_REQUIRED", "新分支重做需要可用的 Git 仓库根目录");
        }
        String source = normalizedAdmissionSource(admissionSource);
        DesignerAttachmentContext.PreparedFreeze preparedAttachments = mapper.findLatestDesignerSessionByDraft(draft.id())
                .map(session -> attachmentContext.prepareFreeze(session.id())).orElse(null);
        TaskCreation creation = transactions.execute(status -> persistTaskCreation(
                draft, spec, project, title, source, isolatedBaseline, confirmDraft, preparedAttachments));
        if (creation == null) {
            throw new ConflictException("TASK_CREATE_TRANSACTION_FAILED", "Task creation transaction did not complete");
        }
        TaskRow pending = get(creation.taskId());
        if (!creation.existing()) {
            events.emit(creation.taskId(), "task.pending_start",
                    Map.of("state", TaskState.PENDING_START.name()));
        }
        return pending;
    }

    private TaskCreation persistTaskCreation(LoopDraftRow inputDraft, LoopSpec spec, ProjectRow project,
                                             String title, String admissionSource, String isolatedBaseline,
                                             boolean confirmDraft,
                                             DesignerAttachmentContext.PreparedFreeze preparedAttachments) {
        LoopDraftRow draft = mapper.findDraft(inputDraft.id())
                .orElseThrow(() -> new NotFoundException("Loop draft not found: " + inputDraft.id()));
        if (draft.version() != inputDraft.version()) {
            throw new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently");
        }
        TaskRow existing = mapper.findTaskByDraft(draft.id()).orElse(null);
        if (existing != null) {
            if (confirmDraft) confirmDraft(draft);
            return new TaskCreation(existing.id(), true);
        }
        String timestamp = now();
        String taskId = UUID.randomUUID().toString();
        io.opencode.loopper.persistence.DesignerTaskProfileRow profile = mapper.findFrozenTaskProfileByDraft(draft.id()).orElse(null);
        TaskRow task = new TaskRow(taskId, project.id(), draft.id(), normalizedTitle(title, draft.goal()),
                TaskState.PENDING_START.name(), null, null, null, isolatedBaseline, timestamp, timestamp, 0,
                profile == null ? null : profile.id(), profile == null ? null : profile.rolePackId(),
                profile == null ? null : profile.rolePackVersion());
        lifecycle.create(taskStates.subject(LifecycleMachineType.TASK, task.id(), task.id()), task.state(),
                Map.of("source", admissionSource), () -> mapper.insertTask(task),
                () -> new ConflictException("TASK_CREATE_CONFLICT", "Task could not be created"));
        if (preparedAttachments != null) {
            attachmentContext.freezePrepared(new DesignerAttachmentContext.FreezeForTask(
                    task.id(), preparedAttachments.designerSessionId(), null), preparedAttachments);
        }
        taskEvidence.persistConfirmedDesignContext(task, draft);
        int ordinal = 0;
        for (LoopSpec.StageSpec stage : spec.stages()) {
            ExecutionRoleSnapshot executionRole = executionRole(draft, stage, profile);
            StageRow stageRow = new StageRow(UUID.randomUUID().toString(), taskId, ordinal++, stage.objective(),
                    write(stage.allowedPaths()), write(stage.forbiddenPaths()), write(stage.deliverables()), write(stage.verifiers()),
                    StageState.PENDING.name(), timestamp, timestamp, 0, stage.workPackageId(),
                    (stage.stageKind() == null ? io.opencode.loopper.domain.StageKind.LEGACY_SOFTWARE : stage.stageKind()).name(),
                    (stage.executionStrategy() == null
                            ? ExecutionStrategy.OPEN_CODE_IMPLEMENTATION : stage.executionStrategy()).name(),
                    stage.artifactPlanId(), executionRole.rolePackId(), executionRole.rolePackVersion(),
                    executionRole.testPolicy(), executionRole.technologiesJson(), executionRole.projectStackProfileId(),
                    executionRole.componentKeysJson(),
                    executionRole.stackFingerprint());
            lifecycle.create(taskStates.subject(LifecycleMachineType.STAGE, stageRow.id(), taskId), stageRow.state(), Map.of(),
                    () -> mapper.insertStage(stageRow),
                    () -> new ConflictException("STAGE_CREATE_CONFLICT", "Stage could not be created"));
        }
        if (confirmDraft) confirmDraft(draft);
        return new TaskCreation(taskId, false);
    }
    private ExecutionRoleSnapshot executionRole(LoopDraftRow draft, LoopSpec.StageSpec stage,
                                                  io.opencode.loopper.persistence.DesignerTaskProfileRow profile) {
        io.opencode.loopper.persistence.WorkPackageRoleProfileRow packageRole = null;
        if (stage.workPackageId() != null) {
            packageRole = mapper.findLatestDesignerSessionByDraft(draft.id())
                    .flatMap(session -> mapper.findLatestDesignWorkPackage(session.id(), stage.workPackageId()))
                    .flatMap(workPackage -> mapper.findWorkPackageRoleProfile(workPackage.id()))
                    .orElse(null);
        }
        if (packageRole != null) return new ExecutionRoleSnapshot(
                    packageRole.rolePackId(), packageRole.rolePackVersion(), packageRole.testPolicy(),
                    packageRole.technologiesJson(), packageRole.projectStackProfileId(),
                    packageRole.componentKeysJson(), packageRole.stackFingerprint());
        if (profile != null) return new ExecutionRoleSnapshot(
                    profile.rolePackId(), profile.rolePackVersion(), profile.testPolicy(),
                    profile.technologiesJson(), profile.projectStackProfileId(),
                    profile.componentKeysJson(), profile.stackFingerprint());
        return new ExecutionRoleSnapshot("software-java", "legacy", "REQUIRED", "[]", null, "[]", null);
    }
    private void confirmDraft(LoopDraftRow draft) {
        if (LoopDraftStatus.CONFIRMED.name().equals(draft.status())) return;
        LoopDraftRow confirmed = new LoopDraftRow(draft.id(), draft.projectId(), draft.goal(), draft.specJson(),
                LoopDraftStatus.CONFIRMED.name(), draft.createdAt(), now(), draft.version());
        LifecycleTransitionService.Subject draftSubject = new LifecycleTransitionService.Subject(
                LifecycleMachineType.LOOP_DRAFT, confirmed.id(), LifecycleScopeType.PROJECT, confirmed.projectId());
        lifecycle.transition(draftSubject, draft.status(), confirmed.status(), null, Map.of(),
                () -> mapper.updateDraft(confirmed),
                () -> new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently"));
    }
    private String normalizedAdmissionSource(String admissionSource) {
        return switch (admissionSource == null ? "MANUAL" : admissionSource) {
            case "AUTOMATION", "RECOVERY", "MANUAL", "PACKAGE" ->
                    admissionSource == null ? "MANUAL" : admissionSource;
            default -> throw new BadRequestException("TASK_ADMISSION_SOURCE_INVALID", "Unknown task admission source");
        };
    }
    public TaskRow get(String id) { return mapper.findTask(id).orElseThrow(() -> new NotFoundException("Task not found: " + id)); }
    public List<TaskRow> list() { return mapper.listTasks(); }
    public TaskRetryScheduleRow retrySchedule(String taskId) {
        get(taskId);
        return mapper.findActiveTaskRetrySchedule(taskId).orElse(null);
    }
    /** User-authorized continuation of the same Task with a fresh cycle and fresh budgets. */
    public TaskRow continueExecution(String taskId, String requestedStageId, String supplementalPrompt) {
        TaskRow task = get(taskId);
        if (!TaskState.AWAITING_DECISION.name().equals(task.state())) {
            throw new ConflictException("TASK_DECISION_NOT_AVAILABLE", "Task is not waiting for a user decision");
        }
        TaskExecutionCycleRow previous = executionCycles.latest(taskId);
        if (previous == null || ExecutionCycleState.RUNNING.name().equals(previous.state())) {
            throw new ConflictException("TASK_EXECUTION_CYCLE_RESULT_MISSING", "Task has no completed execution cycle to continue");
        }
        io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint = workspaceCheckpoints.latest(taskId);
        if (checkpoint == null || !WorkspaceCheckpointState.READY.name().equals(checkpoint.state())) {
            throw new ConflictException("TASK_CONTINUATION_CHECKPOINT_UNSAFE",
                    checkpoint == null ? "Task changes were not frozen safely"
                            : safeMessage(checkpoint.blockerCode() + ": " + checkpoint.blockerMessage()));
        }
        List<StageRow> stages = mapper.listStages(taskId);
        StageRow startStage;
        ExecutionCycleKind kind;
        String prompt;
        if (ExecutionCycleState.SUCCEEDED.name().equals(previous.state())) {
            if (requestedStageId == null || requestedStageId.isBlank()) {
                throw new BadRequestException("TASK_CONTINUATION_STAGE_REQUIRED", "成功后继续优化必须选择一个已有 Stage");
            }
            if (supplementalPrompt == null || supplementalPrompt.isBlank()) {
                throw new BadRequestException("TASK_CONTINUATION_PROMPT_REQUIRED", "成功后继续优化必须填写补充需求");
            }
            startStage = stages.stream().filter(row -> requestedStageId.equals(row.id())).findFirst()
                    .orElseThrow(() -> new BadRequestException("TASK_CONTINUATION_STAGE_INVALID", "Selected Stage does not belong to the Task"));
            kind = ExecutionCycleKind.CONTINUE_SUCCESS;
            prompt = supplementalPrompt.strip();
        } else {
            startStage = stages.stream().filter(row -> StageState.FAILED.name().equals(row.state())).findFirst()
                    .or(() -> stages.stream().filter(row -> !StageState.SUCCEEDED.name().equals(row.state())).findFirst())
                    .orElseThrow(() -> new ConflictException("TASK_FAILED_STAGE_MISSING", "No failed or interrupted Stage is available"));
            kind = ExecutionCycleKind.CONTINUE_FAILED;
            prompt = supplementalPrompt == null || supplementalPrompt.isBlank()
                    ? "Continue the failed Stage from the frozen workspace checkpoint." : supplementalPrompt.strip();
        }
        for (StageRow row : stages) {
            if (row.ordinal() < startStage.ordinal() || StageState.PENDING.name().equals(row.state())) continue;
            taskStates.updateStage(taskStates.stageState(row, StageState.PENDING), LifecycleEvent.RECOVER);
        }
        executionCycles.create(task, kind, startStage, prompt, cycleBudgetSnapshot(spec(task)));
        events.emit(taskId, "task.execution_cycle_authorized", Map.of("kind", kind.name(),
                "startStageId", startStage.id(), "startStageOrdinal", startStage.ordinal(),
                "previousCycleId", previous.id(), "previousResult", previous.state()));
        return requestTaskStart(get(taskId), "MANUAL");
    }
    /** Explicit no-change acceptance is the success confirmation boundary. */
    public TaskRow acceptResult(String taskId) {
        TaskRow task = requireSuccessfulDecision(taskId);
        io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint = workspaceCheckpoints.latest(taskId);
        if (checkpoint == null || !WorkspaceCheckpointState.READY.name().equals(checkpoint.state())) {
            throw new ConflictException("TASK_ACCEPT_CHECKPOINT_UNSAFE", "Task workspace checkpoint is not ready");
        }
        try {
            if (json.readTree(checkpoint.manifestJson()).size() != 0) {
                throw new ConflictException("TASK_ACCEPT_HAS_CHANGES", "Task produced file changes; publish or continue instead of accepting a no-change result");
            }
        } catch (ConflictException conflict) {
            throw conflict;
        } catch (Exception unreadable) {
            throw new ConflictException("TASK_ACCEPT_MANIFEST_INVALID", "Task checkpoint manifest cannot be verified");
        }
        TaskRow completed = terminalConsistency.complete(task, LifecycleEvent.ACCEPT_RESULT,
                Map.of("cycleId", executionCycles.latest(taskId).id(), "confirmation", "NO_CHANGES_ACCEPTED"));
        events.emit(taskId, "task.completed", Map.of("confirmation", "NO_CHANGES_ACCEPTED"));
        return completed;
    }

    /** Publication calls this only after a durable local commit or confirmed push. */
    public TaskRow confirmPublishedResult(String taskId, String confirmation, String commitSha) {
        TaskRow task = get(taskId);
        if (TaskState.COMPLETED.name().equals(task.state())) return task;
        requireSuccessfulDecision(taskId);
        TaskRow completed = terminalConsistency.complete(task, LifecycleEvent.COMPLETE,
                Map.of("cycleId", executionCycles.latest(taskId).id(), "confirmation", confirmation,
                        "commitSha", commitSha == null ? "" : commitSha));
        events.emit(taskId, "task.completed", Map.of("confirmation", confirmation,
                "commitSha", commitSha == null ? "" : commitSha));
        return completed;
    }

    public TaskRow supersede(String taskId, String childTaskId, String mode) {
        TaskRow task = get(taskId);
        if (!TaskState.AWAITING_DECISION.name().equals(task.state())) {
            throw new ConflictException("TASK_DECISION_NOT_AVAILABLE", "Only a Task awaiting disposition can be superseded");
        }
        TaskRow superseded = terminalConsistency.supersede(task,
                Map.of("successorTaskId", childTaskId, "mode", mode));
        events.emit(taskId, "task.superseded", Map.of("successorTaskId", childTaskId, "mode", mode));
        return superseded;
    }

    private TaskRow requireSuccessfulDecision(String taskId) {
        TaskRow task = get(taskId);
        TaskExecutionCycleRow cycle = executionCycles.latest(taskId);
        if (!TaskState.AWAITING_DECISION.name().equals(task.state()) || cycle == null
                || !ExecutionCycleState.SUCCEEDED.name().equals(cycle.state())) {
            throw new ConflictException("TASK_SUCCESS_DECISION_REQUIRED", "Task does not have a successful cycle awaiting confirmation");
        }
        return task;
    }
    public synchronized void startDueRetries() {
        for (TaskRetryScheduleRow candidate : mapper.listDueTaskRetrySchedules(now(), 32)) {
            try {
                startDueRetry(candidate.id());
            } catch (RuntimeException ignoredConcurrentTransition) {
                // Another monitor tick or an operator action may have claimed/cancelled the same schedule.
            }
        }
    }

    private void startDueRetry(String retryId) {
        TaskRetryScheduleRow candidate = mapper.findTaskRetrySchedule(retryId).orElse(null);
        if (candidate == null || !RETRY_SCHEDULED.equals(candidate.state())) return;
        TaskRow task = get(candidate.taskId());
        if (!TaskState.RETRY_WAIT.name().equals(task.state())) {
            taskStates.updateRetrySchedule(taskStates.retryState(candidate, RETRY_CANCELLED, candidate.dueAt(), null));
            return;
        }
        TaskRetryScheduleRow claimed = taskStates.retryState(candidate, RETRY_CLAIMED, candidate.dueAt(), null);
        transactions.executeWithoutResult(status -> {
            if (mapper.updateTaskRetrySchedule(claimed) != 1) {
                throw new ConflictException("RETRY_SCHEDULE_CONFLICT", "Retry schedule was claimed concurrently");
            }
            taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.RUNNING), LifecycleEvent.RETRY, taskStates.retryAudit(candidate));
        });
        StageRow stage = mapper.findStage(candidate.stageId())
                .orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Retry stage disappeared"));
        if (StageState.PAUSED.name().equals(stage.state())) {
            taskStates.updateStage(taskStates.stageState(stage, StageState.RUNNING));
            stage = mapper.findStage(stage.id()).orElse(stage);
        }
        events.emit(task.id(), "task.retry_started", Map.of("retryCause", candidate.cause(),
                "retryOrdinal", candidate.ordinal(), "retryDelaySeconds", candidate.delaySeconds()));
        try {
            startNewAttempt(get(task.id()), stage, candidate.prompt());
        } catch (RuntimeException failure) {
            failTask(get(task.id()), "RETRY_START_FAILED", safeMessage(failure), stage, null, null);
        }
    }
    public boolean archived(String id) { get(id); return mapper.isTaskArchived(id); }
    public TaskRow archive(String id) {
        TaskRow task = get(id);
        if (!TaskState.valueOf(task.state()).terminal()) {
            throw new BadRequestException("TASK_NOT_ARCHIVABLE", "只有已经用户确认终结的任务可以归档");
        }
        WorkspaceLeaseReconciliationService.Result result = reconcileTerminalLease(task,
                WorkspaceLeaseReconciliationService.TRIGGER_ARCHIVE, "TASK_ARCHIVE");
        continueAfterLeaseReconciliation(result);
        if (result.blocked() || leaseReconciliation.ownsActiveLease(id)) {
            String detail = result.blocked()
                    ? result.blockerCode() + "：" + result.blockerMessage()
                    : "任务仍是 ADMITTED 队列项或活动项目写租约 holder";
            throw new ConflictException("TASK_ARCHIVE_WORKSPACE_LEASE_ACTIVE",
                    "任务仍占用项目写租约，释放完成前不能归档。" + detail);
        }
        TaskRow archived = transactions.execute(status -> {
            TaskRow current = get(id);
            if (!TaskState.valueOf(current.state()).terminal()) {
                throw new BadRequestException("TASK_NOT_ARCHIVABLE", "只有已经用户确认终结的任务可以归档");
            }
            if (leaseReconciliation.ownsActiveLease(id)) {
                throw new ConflictException("TASK_ARCHIVE_WORKSPACE_LEASE_ACTIVE",
                        "任务在归档提交前重新获得了活动项目写租约，已停止归档");
            }
            mapper.archiveTask(id, now());
            return current;
        });
        if (archived == null) throw new ConflictException("TASK_ARCHIVE_FAILED", "任务归档事务未完成");
        return archived;
    }
    @Transactional
    public TaskRow restoreArchive(String id) {
        TaskRow task = get(id);
        mapper.restoreTask(id);
        return task;
    }
    public void deleteArchived(String id) {
        TaskRow task = get(id);
        if (!TaskState.valueOf(task.state()).terminal()) {
            throw new BadRequestException("TASK_NOT_DELETABLE", "只有已经用户确认终结的任务可以删除");
        }
        if (!mapper.isTaskArchived(id)) {
            throw new BadRequestException("TASK_NOT_ARCHIVED", "请先归档任务，再从已归档列表永久删除");
        }
        if (!mapper.childTasks(id).isEmpty()) {
            throw new BadRequestException("TASK_HAS_RECOVERY_CHILDREN", "该任务仍有重做或恢复子任务，请先删除子任务");
        }
        if (leaseReconciliation.ownsActiveLease(id)) {
            throw new ConflictException("TASK_DELETE_WORKSPACE_LEASE_ACTIVE",
                    "任务仍是 ADMITTED 队列项或活动项目写租约 holder，释放完成前不能永久删除");
        }
        transactions.executeWithoutResult(status -> deleteArchivedState(id));
    }

    private void deleteArchivedState(String id) {
        TaskRow task = get(id);
        if (!TaskState.valueOf(task.state()).terminal() || !mapper.isTaskArchived(id)) {
            throw new ConflictException("TASK_DELETE_STATE_CHANGED", "任务状态或归档状态已变化，请刷新后重试");
        }
        if (leaseReconciliation.ownsActiveLease(id)) {
            throw new ConflictException("TASK_DELETE_WORKSPACE_LEASE_ACTIVE",
                    "任务在删除提交前重新成为活动项目写租约 holder，已停止删除");
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
        rollingPackages.deleteEvidenceBeforeAttempts(id);
        mapper.detachWorkspaceLeaseWriterSessions(id);
        mapper.deleteExecutionSessionsForTask(id);
        mapper.deleteAttemptsForTask(id);
        mapper.deleteTaskLineageForChild(id);
        mapper.deleteTaskRetrySchedulesForTask(id);
        mapper.deleteStagesForTask(id);
        rollingPackages.deletePlanAfterStages(id);
        mapper.deleteTaskQueueEntry(id);
        mapper.deleteTaskArchiveEntry(id);
        mapper.detachAutomationRunsFromTask(id);
        mapper.deleteStateTransitionsForScope(LifecycleScopeType.TASK.name(), id);
        if (mapper.deleteTask(id) != 1) {
            throw new NotFoundException("Task not found: " + id);
        }
        if (draftId != null && !draftId.isBlank()) {
            mapper.deleteDesignDiscussionRevisionsByDraft(draftId);
            mapper.deleteLoopSpecCompilationsByDraft(draftId);
            mapper.deleteDesignWorkPackagesByDraft(draftId);
            mapper.deleteTaskDecompositionsByDraft(draftId);
            mapper.deleteDesignRequirementRevisionsByDraft(draftId);
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
        taskEvidence.persist(task, null, null, LOCAL_SOURCE_SYNC_ARTIFACT_KIND, "local-source-sync.txt", "text/plain",
                commitSha, Map.of("source", "task-publication", "mode", mode));
    }

    /** Restores the Task start branch and releases the registered checkout after the Task commit is durable. */
    public void releaseWorkspaceAfterTaskCommit(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.AWAITING_DECISION.name().equals(task.state())) requireSuccessfulDecision(taskId);
        else if (!TaskState.SUCCEEDED.name().equals(task.state()) && !TaskState.COMPLETED.name().equals(task.state()))
            throw new ConflictException("TASK_PUBLICATION_LEASE_NOT_RELEASABLE", "只有成功待确认或已完成 Task 才能在发布后释放工作区");
        if (!isAdmittedInPlace(task)) return;
        if (!GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())) {
            worktrees.restoreSourceBranch(inPlaceRoot(task), task.branchName(), task.sourceBranch());
        }
        settleTerminalInPlaceLease(task, !writerTermination.hasUnconfirmedWriter(task.id()), "TASK_COMMITTED");
    }

    /** Reacquires the FIFO lease and restores a frozen successful cycle before a publication write. */
    public TaskRow preparePublicationWorkspace(String taskId) {
        TaskRow task = requireSuccessfulDecision(taskId);
        io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint = workspaceCheckpoints.latest(taskId);
        if (checkpoint == null) {
            throw new ConflictException("TASK_PUBLICATION_CHECKPOINT_MISSING", "Successful Task has no frozen workspace checkpoint");
        }
        Path root = inPlaceRoot(task);
        if (WorkspaceCheckpointState.RESTORED.name().equals(checkpoint.state())) {
            directLeases.requireWritableLease(root, task.id());
            return task;
        }
        if (!WorkspaceCheckpointState.READY.name().equals(checkpoint.state())) {
            throw new ConflictException("TASK_PUBLICATION_CHECKPOINT_UNSAFE",
                    safeMessage(checkpoint.blockerCode() + ": " + checkpoint.blockerMessage()));
        }
        DirectWorkspaceLeaseCoordinator.Admission admission = directLeases.acquireOrEnqueue(
                DirectWorkspaceLeaseCoordinator.identify(root), task.id(), "PUBLICATION", null);
        if (TaskQueueState.QUEUED.name().equals(admission.state())) {
            throw new ConflictException("TASK_PUBLICATION_QUEUED", "项目工作区正被其他任务使用，发布已按 FIFO 排队，请稍后重试");
        }
        workspaceCheckpoints.restore(task, checkpoint);
        return get(taskId);
    }

    public FeatureContracts.QueueStatusDto queueStatus(String taskId) {
        TaskRow task = get(taskId);
        TaskQueueRow queue = mapper.findTaskQueue(taskId).orElse(null);
        if (queue == null) {
            return new FeatureContracts.QueueStatusDto(task.id(), TaskQueueState.FINISHED.name(), null,
                    "NOT_REQUIRED", null, null, null, null, null, null, false);
        }
        var lease = mapper.findWorkspaceLease(queue.canonicalRoot()).orElse(null);
        String leaseState = lease == null ? WorkspaceLeaseState.RELEASED.name() : lease.state();
        Long position = TaskQueueState.QUEUED.name().equals(queue.state()) ? queuePosition(taskId) : null;
        TaskRow holder = lease == null || lease.holderTaskId() == null ? null
                : mapper.findTask(lease.holderTaskId()).orElse(null);
        Boolean holderArchived = holder == null ? null : mapper.isTaskArchived(holder.id());
        boolean reconcileAvailable = TaskQueueState.QUEUED.name().equals(queue.state()) && holder != null
                && TaskState.valueOf(holder.state()).terminal();
        return new FeatureContracts.QueueStatusDto(task.id(), queue.state(), position, leaseState,
                queue.rootFingerprint(), holder == null ? null : holder.id(), holder == null ? null : holder.title(),
                holder == null ? null : holder.state(), holderArchived, lease == null ? null : lease.releaseReason(),
                reconcileAvailable);
    }

    /** Local-UI action: locate the holder from the waiter's root and retry the same safe reconciliation. */
    public FeatureContracts.QueueStatusDto reconcileQueue(String taskId) {
        get(taskId);
        TaskQueueRow queue = mapper.findTaskQueue(taskId)
                .orElseThrow(() -> new ConflictException("TASK_QUEUE_RECONCILIATION_NOT_AVAILABLE",
                        "任务没有持久化队列记录，不能重新检查写租约"));
        if (!TaskQueueState.QUEUED.name().equals(queue.state())) return queueStatus(taskId);
        var lease = mapper.findWorkspaceLease(queue.canonicalRoot())
                .orElseThrow(() -> new ConflictException("TASK_QUEUE_LEASE_INVARIANT_VIOLATION",
                        "排队任务对应的项目写租约不存在"));
        if (lease.holderTaskId() == null) {
            throw new ConflictException("TASK_QUEUE_LEASE_INVARIANT_VIOLATION",
                    "活动项目写租约缺少 holder，拒绝猜测或强制转移");
        }
        WorkspaceLeaseReconciliationService.Result result = leaseReconciliation.reconcileHolder(
                lease.holderTaskId(), WorkspaceLeaseReconciliationService.TRIGGER_MANUAL, "MANUAL_QUEUE_RECONCILIATION");
        if (result.blocked() && "SESSION_WRITER_UNCONFIRMED".equals(result.blockerCode())) {
            TaskRow holder = mapper.findTask(lease.holderTaskId()).orElse(null);
            if (holder != null && TaskState.valueOf(holder.state()).terminal()) {
                managedVerifierRuntimes.stopTask(holder.id(), "manual-queue-reconciliation");
                managedVerifierRuntimes.confirmTaskStopped(holder.id());
                writerTermination.retryUnconfirmedSessions(holder, "manual-queue-reconciliation");
                result = leaseReconciliation.reconcileHolder(holder.id(),
                        WorkspaceLeaseReconciliationService.TRIGGER_MANUAL,
                        "MANUAL_QUEUE_RECONCILIATION_AFTER_WRITER_CONFIRMATION");
            }
        }
        continueAfterLeaseReconciliation(result);
        if (result.blocked()) throw new ConflictException(result.blockerCode(), result.blockerMessage());
        return queueStatus(taskId);
    }

    /** Scheduled liveness repair for terminal holders which are actually blocking a waiter. */
    public void reconcileTerminalWorkspaceLeasesWithWaiters() {
        for (String holderTaskId : leaseReconciliation.terminalHolderIdsWithQueuedWaiters()) {
            try {
                continueAfterLeaseReconciliation(leaseReconciliation.reconcileHolder(holderTaskId,
                        WorkspaceLeaseReconciliationService.TRIGGER_AUTO, "TERMINAL_HOLDER_WITH_WAITER"));
            } catch (RuntimeException reconciliationFailure) {
                // Manual reconciliation, restart recovery, cancellation or archive may win the same idempotent race.
                log.warn("Workspace lease reconciliation for terminal holder {} did not complete: {}",
                        holderTaskId, reconciliationFailure.getMessage());
            }
        }
    }

    /** User-facing goal retained with the confirmed LoopSpec for publication metadata. */
    public String goal(String taskId) { return spec(get(taskId)).goal(); }

    public VerifierEngine.DiffPreview diffPreview(String taskId, String path) {
        return taskEvidence.previewDiff(get(taskId), path, workspaceCheckpoints.latest(taskId));
    }
    /** Applies time budgets before a monitor interprets an OpenCode status transition. */
    public void enforceTimeouts(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.RUNNING.name().equals(task.state())
                && !TaskState.VERIFYING.name().equals(task.state())
                && !TaskState.JUDGING.name().equals(task.state())) return;
        LoopSpec spec = spec(task);
        TaskExecutionCycleRow activeCycle = executionCycles.active(task.id());
        Instant cycleStarted = activeCycle == null ? Instant.parse(task.createdAt()) : Instant.parse(activeCycle.startedAt());
        if (cycleStarted.plusSeconds(effectiveMaxDurationSeconds(spec)).isBefore(StoryAccountingClock.taskNow(mapper, taskId, cycleStarted.toString()))) {
            if (TaskState.VERIFYING.name().equals(task.state())) {
                VerifierOutcome runtimeStop = managedVerifierRuntimes.stopTask(taskId, "task-duration-exhausted");
                if (runtimeStop != null && runtimeStop.state() == VerificationState.ERROR) {
                    failTaskForManagedRuntime(taskId, runtimeStop);
                    return;
                }
            }
            failTask(task, "TASK_DURATION_EXHAUSTED", "Task exceeded its maximum duration", null, null, null);
            return;
        }
        for (AttemptRow attempt : mapper.listAttempts(taskId)) {
            if (AttemptState.RUNNING.name().equals(attempt.state())
                    && Instant.parse(attempt.createdAt()).plusSeconds(effectiveAttemptTimeoutSeconds(spec)).isBefore(StoryAccountingClock.taskNow(mapper, taskId, attempt.createdAt()))) {
                sessionFailed(taskId, attempt.id(), "SESSION_TIMEOUT", "Attempt exceeded its session timeout");
            }
        }
    }
    public TaskRow start(String taskId) { return start(taskId, "MANUAL"); }
    public TaskRow startRollingPackage(String taskId, String packageRunId, long expectedTaskVersion, long expectedPackageVersion) {
        var request = rollingPackages.executionRequest(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion);
        return request.disposition() == RollingPackageCommandPolicy.StartDisposition.IDEMPOTENT ? get(taskId) : requestTaskStart(get(taskId), "PACKAGE", request);
    }
    public TaskRow retryRollingPackageCandidate(String taskId, String packageRunId, long expectedTaskVersion, long expectedPackageVersion) {
        var run = rollingPackages.prepareRetry(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion);
        return startRollingPackage(taskId, run.id(), get(taskId).version(), run.version());
    }
    TaskRow start(String taskId, String admissionSource) {
        TaskRow task = get(taskId);
        if (TaskState.valueOf(task.state()).terminal() || TaskState.AWAITING_DECISION.name().equals(task.state())) {
            throw new ConflictException("TASK_TERMINAL", "Cannot start a terminal task");
        }
        if (TaskState.PAUSED.name().equals(task.state())) return resume(taskId);
        if (TaskState.PENDING_START.name().equals(task.state())
                || (rollingPackages.applies(task.id()) && TaskState.WAITING_INPUT.name().equals(task.state()))) {
            return requestTaskStart(task, admissionSource);
        }
        if (TaskState.READY.name().equals(task.state())) {
            return startPreparedTask(taskId);
        }
        if (List.of(TaskState.QUEUED.name(), TaskState.PREPARING.name(), TaskState.RUNNING.name(),
                TaskState.VERIFYING.name(), TaskState.RETRY_WAIT.name(), TaskState.JUDGING.name())
                .contains(task.state())) {
            return task;
        }
        throw new ConflictException("TASK_ALREADY_ACTIVE", "Task is already active");
    }
    private TaskRow requestTaskStart(TaskRow task, String admissionSource) { return requestTaskStart(task, admissionSource, null); }
    private TaskRow requestTaskStart(TaskRow task, String admissionSource,
                                     RollingPackageService.ExecutionRequest packageRequest) {
        try {
            rollingPackages.ensureExecutionCycle(task, cycleBudgetSnapshot(spec(task)));
            ProjectRow project = projects.get(task.projectId());
            Path projectRoot = Path.of(project.rootPath());
            if (task.baselineCommit() != null && !worktrees.inspect(projectRoot).isolatedWorktree()
                    && !(rollingPackages.applies(task.id()) && GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName()))) {
                throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires a Git source branch");
            }
            DirectWorkspaceLeaseCoordinator.WorkspaceIdentity workspace =
                    DirectWorkspaceLeaseCoordinator.identify(projectRoot);
            String source = normalizedAdmissionSource(admissionSource);
            DirectWorkspaceLeaseCoordinator.Admission admission = transactions.execute(status -> {
                TaskRow current = get(task.id());
                if (!TaskState.PENDING_START.name().equals(current.state())
                        && !TaskState.AWAITING_DECISION.name().equals(current.state())
                        && !(rollingPackages.applies(current.id())
                        && TaskState.WAITING_INPUT.name().equals(current.state()))) {
                    throw new ConflictException("TASK_START_REQUEST_CONFLICT",
                            "Task no longer waits for an execution request");
                }
                if (packageRequest != null) rollingPackages.requestExecutionInTransaction(packageRequest);
                DirectWorkspaceLeaseCoordinator.Admission acquired = directLeases.acquireOrEnqueueInTransaction(
                        workspace, current.id(), source, null);
                taskStates.updateTask(taskStates.taskState(current, TaskState.QUEUED), LifecycleEvent.REQUEST_START);
                return acquired;
            });
            if (admission == null) {
                throw new TaskFailure("TASK_START_ADMISSION_FAILED", "Task start admission did not complete");
            }
            events.emit(task.id(), "task.start_requested", Map.of("state", TaskState.QUEUED.name(),
                    "source", source));
            if (TaskQueueState.QUEUED.name().equals(admission.state())) {
                events.emit(task.id(), "task.queued", Map.of("state", TaskState.QUEUED.name(),
                        "queuePosition", queuePosition(task.id()), "leaseState", admission.leaseState()));
                return get(task.id());
            }
            return prepareAdmittedTaskAndContinue(task.id());
        } catch (TaskFailure failure) {
            failTask(get(task.id()), failure.code(), failure.getMessage(), null, null, null);
            return get(task.id());
        }
    }
    private TaskRow startPreparedTask(String taskId) {
        TaskRow task = get(taskId);
        boolean verificationOnly = isVerificationOnlyRecovery(taskId);
        try {
            ProjectRow project = projects.get(task.projectId());
            worktrees.requireExecutionWorkspace(Path.of(requireWorktree(task)), Path.of(project.rootPath()),
                    task.branchName(), task.baselineCommit());
            requireInPlaceWritable(task, project);
            if (verificationOnly) {
                taskStates.updateTask(taskStates.taskState(task, TaskState.RUNNING));
                StageRow stage = mapper.listStages(task.id()).stream()
                        .filter(s -> StageState.PENDING.name().equals(s.state()) || StageState.PAUSED.name().equals(s.state()))
                        .findFirst().orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Task has no runnable stage"));
                startVerificationOnlyAttempt(get(task.id()), stage);
                return verify(taskId);
            }
            taskStates.updateTask(taskStates.taskState(task, TaskState.RUNNING));
            rollingPackages.executionStarted(task.id());
            StageRow stage = mapper.listStages(task.id()).stream()
                    .filter(s -> StageState.PENDING.name().equals(s.state()) || StageState.PAUSED.name().equals(s.state()))
                    .findFirst().orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Task has no runnable stage"));
            if (!blank(stage.workPackageId())) {
                events.emit(task.id(), "work_package.started", Map.of("workPackageId", stage.workPackageId(),
                        "state", "RUNNING"));
            }
            TaskExecutionCycleRow cycle = requireActiveCycle(get(task.id()));
            String cyclePrompt = cycle.supplementalPrompt() == null || cycle.supplementalPrompt().isBlank()
                    ? "Start stage: " + stage.objective() : cycle.supplementalPrompt();
            if (serverExecuted(stage)) {
                startServerArtifactAttempt(get(task.id()), stage);
                return verify(taskId);
            }
            if (!openCode.healthy()) throw new TaskFailure("OPENCODE_UNAVAILABLE", "No compatible OpenCode runtime is available");
            startNewAttempt(get(task.id()), stage, cyclePrompt);
        } catch (TaskFailure failure) {
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
        }
        return get(taskId);
    }

    /** Invoked by an OpenCode transport callback or a test harness. It never sets the task FAILED directly. */
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
            remainingTaskDuration(initial, spec(initial));
            StageRow stage = mapper.listStages(taskId).stream().filter(s -> StageState.RUNNING.name().equals(s.state())).findFirst()
                    .orElseThrow(() -> new TaskFailure("STAGE_NOT_RUNNING", "No running stage is available for verification"));
            AttemptRow attempt = mapper.latestAttempt(stage.id()).orElseThrow(() -> new TaskFailure("ATTEMPT_MISSING", "No attempt is available for verification"));
            ExecutionSessionRow implementationSession = mapper.latestSessionForAttempt(attempt.id()).orElse(null);
            boolean serverExecution = serverExecuted(stage);
            if (!verificationOnly && !serverExecution) {
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
            String verificationBaseline = verificationOnly
                    ? initial.baselineCommit()
                    : stageWorkspaceBaselines.requireBaseline(initial, stage);
            transactions.executeWithoutResult(status -> enterVerification(initial, implementationSession));
            LoopSpec spec = spec(initial);
            List<LoopSpec.VerifierSpec> verifierSpecs = read(stage.verifiersJson(), new TypeReference<>() {});
            List<PendingVerification> pending = new ArrayList<>();
            LoopSpec.StageSpec stageContract = spec.stages().get(stage.ordinal());
            ManagedVerificationRuntimeService.Lease managedRuntime = null;
            try {
                if ("v2".equals(spec.schemaVersion()) && stageContract.verificationRuntime() != null) {
                    remainingTaskDuration(initial, spec);
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
                            outcome = verifiers.verify(Path.of(requireWorktree(initial)), verificationBaseline, bound,
                                    boundedVerifierTimeout(initial, spec));
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
            if ("v2".equals(spec.schemaVersion()) && !serverExecution) {
                PendingVerification javaGateResult = javaGateVerification(initial, stage, stageContract,
                        verifierSpecs, pending);
                if (javaGateResult != null) pending.add(javaGateResult);
            }
            GitDiffScopeApprovalService.PendingRequest scopeApproval = applyGitDiffScopeDecisions(
                    initial, stage, attempt, verificationBaseline, pending, boundedVerifierTimeout(initial, spec));
            if (scopeApproval != null) {
                GitDiffScopeApprovalService.PendingRequest pendingApproval = scopeApproval;
                transactions.executeWithoutResult(status -> waitForGitDiffScopeApproval(
                        initial.id(), stage.id(), attempt.id(), pendingApproval));
                return get(taskId);
            }
            AttemptHandoffService.Capture handoff = null;
            PendingVerification failedPreview = pending.stream()
                    .filter(result -> result.outcome().state() != VerificationState.PASS)
                    .reduce((left, right) -> right).orElse(null);
            if (!verificationOnly && !serverExecution && failedPreview != null) {
                handoff = attemptHandoffs.capture(Path.of(requireWorktree(initial)), verificationBaseline,
                        stage.id(), attempt.id(), attempt.ordinal(), pending.stream()
                                .map(result -> new AttemptHandoffService.VerificationFact(result.outcome().type(),
                                        result.outcome().state().name(), result.outcome().summary())).toList(),
                        failedPreview.outcome().summary(), boundedVerifierTimeout(initial, spec));
            }
            remainingTaskDuration(initial, spec);
            AttemptHandoffService.Capture capturedHandoff = handoff;
            VerificationContinuation continuation = transactions.execute(status -> finishVerification(
                    initial.id(), stage.id(), attempt.id(), implementationSession == null ? null : implementationSession.id(),
                    pending, capturedHandoff, verificationOnly, serverExecution, spec));
            continueAfterVerification(continuation);
        } catch (TaskFailure failure) {
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
        }
        return get(taskId);
    }

    private GitDiffScopeApprovalService.PendingRequest applyGitDiffScopeDecisions(
            TaskRow task, StageRow stage, AttemptRow attempt, String baseline,
            List<PendingVerification> pending, Duration timeout) {
        for (int index = 0; index < pending.size(); index++) {
            PendingVerification candidate = pending.get(index);
            GitDiffScopeApprovalService.Assessment assessment = gitDiffScopeApprovals.assess(
                    task, stage, attempt.id(), baseline, candidate.outcome(), timeout);
            if (assessment.pending() != null) return assessment.pending();
            if (assessment.outcome() != candidate.outcome()) {
                pending.set(index, new PendingVerification(candidate.id(), candidate.index(), assessment.outcome()));
            }
        }
        return null;
    }

    private void waitForGitDiffScopeApproval(String taskId, String stageId, String attemptId,
                                             GitDiffScopeApprovalService.PendingRequest request) {
        TaskRow task = get(taskId);
        StageRow stage = mapper.findStage(stageId)
                .orElseThrow(() -> new ConflictException("GIT_DIFF_SCOPE_STAGE_MISSING",
                        "The approval Stage is no longer available"));
        AttemptRow attempt = mapper.findAttempt(attemptId)
                .orElseThrow(() -> new ConflictException("GIT_DIFF_SCOPE_ATTEMPT_MISSING",
                        "The approval Attempt is no longer available"));
        if (!TaskState.VERIFYING.name().equals(task.state())
                || !StageState.RUNNING.name().equals(stage.state())
                || !AttemptState.RUNNING.name().equals(attempt.state())) {
            throw new ConflictException("TASK_VERIFICATION_INTERRUPTED",
                    "Task changed before the scope approval request could be persisted");
        }
        recordError(task, stage, attempt, null, ErrorLayer.VERIFICATION,
                GitDiffScopeApprovalService.REQUIRED,
                "Existing files outside the configured allow-list require a local diff decision",
                true, request.evidence());
        taskStates.updateTask(taskStates.taskState(task, TaskState.WAITING_INPUT), LifecycleEvent.REQUIRE_INPUT,
                GitDiffScopeApprovalService.REQUIRED,
                Map.of("scopeApprovalRequestId", request.requestId(), "fileCount", request.files().size()));
        events.emit(taskId, "git_diff.scope_approval_required", Map.of(
                "requestId", request.requestId(), "fileCount", request.files().size(),
                "state", TaskState.WAITING_INPUT.name()));
    }

    private PendingVerification javaGateVerification(TaskRow task, StageRow stage,
                                                      LoopSpec.StageSpec stageContract,
                                                      List<LoopSpec.VerifierSpec> verifierSpecs,
                                                      List<PendingVerification> completed) {
        JavaChangeGateService.ChangeSet changes = javaChangeGate.changesSinceStageStart(task, stage);
        if (!changes.changed()) return null;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("changedProductionJavaPaths", changes.changedPaths());
        evidence.put("stageBaselineSha256", changes.beforeSha256());
        evidence.put("currentSnapshotSha256", changes.afterSha256());
        evidence.put("declaredImplementationKind",
                stageContract.implementationKind() == null ? "MISSING" : stageContract.implementationKind().name());
        Map<Integer, VerificationState> completedStates = completed.stream()
                .collect(java.util.stream.Collectors.toMap(PendingVerification::index,
                        result -> result.outcome().state(), (first, second) -> second));
        JavaUnitTestGatePolicy.Decision decision = JavaUnitTestGatePolicy.evaluate(
                stageContract.implementationKind(), verifierSpecs, completedStates);
        evidence.put("focusedJavaTestVerifierIndexes", decision.focusedVerifierIndexes());
        evidence.put("passedFocusedJavaTestVerifierIndexes", decision.passedVerifierIndexes());
        evidence.put("code", decision.code());
        if ("JAVA_CHANGE_CLASSIFICATION_MISMATCH".equals(decision.code())) {
            return new PendingVerification(UUID.randomUUID().toString(), -2,
                    new VerifierOutcome("JAVA_UNIT_TEST_GATE", VerificationState.FAIL,
                            "JAVA_CHANGE_CLASSIFICATION_MISMATCH: production Java changed in a "
                                    + evidence.get("declaredImplementationKind") + " stage", evidence));
        }
        if (!decision.passed()) {
            return new PendingVerification(UUID.randomUUID().toString(), -2,
                    new VerifierOutcome("JAVA_UNIT_TEST_GATE", VerificationState.FAIL,
                            "JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED: changed production Java has no passing focused Maven/Gradle unit test",
                            evidence));
        }
        return new PendingVerification(UUID.randomUUID().toString(), -2,
                new VerifierOutcome("JAVA_UNIT_TEST_GATE", VerificationState.PASS,
                        "Changed production Java is covered by a passing focused Maven/Gradle unit test", evidence));
    }

    private void enterVerification(TaskRow initial, ExecutionSessionRow implementationSession) {
        TaskRow current = get(initial.id());
        if (!TaskState.RUNNING.name().equals(current.state()) || current.version() != initial.version()) {
            throw new ConflictException("TASK_VERSION_CONFLICT", "Task changed before verification could start");
        }
        taskStates.updateTask(taskStates.taskState(current, TaskState.VERIFYING));
        rollingPackages.verificationStarted(current.id());
        if (implementationSession != null) {
            ExecutionSessionRow currentSession = mapper.findSession(implementationSession.id()).orElse(implementationSession);
            if (SessionState.RUNNING.name().equals(currentSession.state())) {
                taskStates.updateSession(taskStates.sessionState(currentSession, SessionState.COMPLETED));
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
                                                          boolean verificationOnly, boolean serverExecution,
                                                          LoopSpec spec) {
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
        String failureCode = String.valueOf(failed.outcome().evidence().getOrDefault("code", "VERIFICATION_FAILED"));
        if (verificationOnly || serverExecution) {
            taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.VERIFICATION_FAILED, failureCode, failure));
            recordError(task, stage, attempt, null, ErrorLayer.VERIFICATION,
                    failureCode, failure, true, Map.of("verifyOnly", verificationOnly, "serverExecution", serverExecution));
            String code = serverExecution ? "SERVER_ARTIFACT_VERIFICATION_FAILED" : "VERIFY_ONLY_VERIFICATION_FAILED";
            String detail = serverExecution
                    ? "服务端制品生成后的原生验证失败；不会创建可写 OpenCode 修复会话"
                    : "VERIFY_ONLY 恢复任务的原生验证失败；不会创建可写 OpenCode 修复会话";
            failTask(get(taskId), code, detail, stage, attempt, null);
            return VerificationContinuation.none(taskId);
        }
        int stagnationCount = persistAttemptHandoff(task, stage, attempt, handoff);
        return retryAfterVerificationFailureState(task, stage, attempt, failureCode, failure, spec, handoff, stagnationCount);
    }
    private void continueAfterVerification(VerificationContinuation continuation) {
        if (continuation == null || continuation.action() == VerificationAction.NONE) return;
        TaskRow task = get(continuation.taskId());
        StageRow stage = mapper.findStage(continuation.stageId())
                .orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Continuation stage disappeared"));
        if (continuation.action() == VerificationAction.FINAL_REVIEW) {
            AttemptRow attempt = mapper.findAttempt(continuation.attemptId())
                    .orElseThrow(() -> new TaskFailure("ATTEMPT_MISSING", "Final verification attempt disappeared"));
            taskEvidence.captureFinalEvidence(task, attempt);
            launchRequiredJudges(task, attempt);
            return;
        }
        if (continuation.action() == VerificationAction.PACKAGE_CHECKPOINT) {
            var outcome = rollingPackages.checkpoint(task, continuation.packageRunId(), continuation.attemptId());
            if (outcome.finalReview()) {
                TaskRow judging = get(task.id());
                taskEvidence.captureFinalEvidence(judging, outcome.attempt());
                launchRequiredJudges(judging, outcome.attempt());
            }
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
        return pauseState(taskId);
    }

    private TaskRow pauseState(String taskId) {
        TaskRow task = get(taskId);
        if (TaskState.RUNNING.name().equals(task.state()) || TaskState.VERIFYING.name().equals(task.state()) || TaskState.RETRY_WAIT.name().equals(task.state())) {
            boolean retryWaiting = TaskState.RETRY_WAIT.name().equals(task.state());
            boolean allWritersStopped = true;
            for (ExecutionSessionRow session : mapper.activeSessions(task.id())) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("pause", true);
                boolean stopped = writerTermination.confirmStopped(task, session, evidence);
                allWritersStopped &= stopped;
                AttemptRow attempt = mapper.findAttempt(session.attemptId()).orElse(null);
                StageRow sessionStage = attempt == null ? null : mapper.findStage(attempt.stageId()).orElse(null);
                taskStates.updateSession(taskStates.sessionState(session, stopped ? SessionState.ABORTED : SessionState.DISCONNECTED));
                if (attempt != null && AttemptState.RUNNING.name().equals(attempt.state())) {
                    taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.SESSION_ERROR, "PAUSED", "Task paused after stopping its writer Session"));
                }
                if (!stopped) {
                    writerTermination.recordUnconfirmed(task, sessionStage, attempt, session,
                            "Task pause could not confirm the previous mutating Session stopped", evidence);
                }
            }
            // Verification runs without an active writer Session. If pause wins
            // while the verifier worker is outside SQLite, close that Attempt so
            // resume cannot create a second RUNNING Attempt beside it.
            for (AttemptRow attempt : mapper.listAttempts(task.id())) {
                if (AttemptState.RUNNING.name().equals(attempt.state())) {
                    taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.SESSION_ERROR, "PAUSED",
                            "Task paused while its current work was still in flight"));
                }
            }
            allWritersStopped &= !writerTermination.hasUnconfirmedWriter(task.id());
            for (StageRow stage : mapper.listStages(taskId)) {
                if (StageState.RUNNING.name().equals(stage.state())) taskStates.updateStage(taskStates.stageState(stage, StageState.PAUSED));
            }
            if (retryWaiting) {
                mapper.findActiveTaskRetrySchedule(taskId).ifPresent(retry -> {
                    long remainingMillis = Math.max(0,
                            Duration.between(Instant.now(), Instant.parse(retry.dueAt())).toMillis());
                    long remaining = (remainingMillis + 999) / 1_000;
                    taskStates.updateRetrySchedule(taskStates.retryState(retry, RETRY_PAUSED, retry.dueAt(), Math.toIntExact(remaining)));
                });
            }
            taskStates.updateTask(taskStates.taskState(task, TaskState.PAUSED));
            if (isAdmittedInPlace(task) && allWritersStopped) {
                directLeases.retainAfterWriterStopped(inPlaceRoot(task), task.id(), "TASK_PAUSED_WRITER_STOPPED");
            }
            events.emit(taskId, "task.paused", Map.of("state", TaskState.PAUSED.name(),
                    "writerTerminationConfirmed", allWritersStopped));
        }
        return get(taskId);
    }

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
        TaskRetryScheduleRow pausedRetry = mapper.findActiveTaskRetrySchedule(taskId)
                .filter(retry -> RETRY_PAUSED.equals(retry.state())).orElse(null);
        if (pausedRetry != null) {
            int remaining = Math.max(0, pausedRetry.remainingSeconds() == null ? 0 : pausedRetry.remainingSeconds());
            String dueAt = Instant.now().plusSeconds(remaining).toString();
            TaskRetryScheduleRow resumed = taskStates.retryState(pausedRetry, RETRY_SCHEDULED, dueAt, null);
            transactions.executeWithoutResult(status -> {
                if (mapper.updateTaskRetrySchedule(resumed) != 1) {
                    throw new ConflictException("RETRY_SCHEDULE_CONFLICT", "Paused retry changed concurrently");
                }
                taskStates.updateTask(taskStates.taskState(get(taskId), TaskState.RETRY_WAIT), LifecycleEvent.RESUME_RETRY,
                        taskStates.retryAudit(resumed));
                StageRow currentStage = mapper.findStage(stage.id()).orElse(stage);
                taskStates.updateStage(taskStates.stageState(currentStage, StageState.RUNNING));
            });
            events.emit(taskId, "task.retry_resumed", Map.of("state", TaskState.RETRY_WAIT.name(),
                    "retryCause", resumed.cause(), "retryDueAt", resumed.dueAt(),
                    "retryDelaySeconds", remaining));
            return get(taskId);
        }
        taskStates.updateTask(taskStates.taskState(task, TaskState.RUNNING));
        taskStates.updateStage(taskStates.stageState(stage, StageState.RUNNING));
        if (mapper.activeSessions(taskId).isEmpty() && !isVerificationOnlyRecovery(taskId)) {
            startNewAttempt(get(taskId), mapper.findStage(stage.id()).orElse(stage), "Resume stage: " + stage.objective());
        }
        events.emit(taskId, "task.resumed", Map.of("state", TaskState.RUNNING.name()));
        return get(taskId);
    }
    public TaskRow cancel(String taskId) { return settleCancelledLease(cancellations.cancel(taskId)); }
    public TaskRow cancelDecision(String taskId) { return settleCancelledLease(cancellations.cancelDecision(taskId)); }
    public TaskRow continueCancellation(String taskId) { return settleCancelledLease(cancellations.continueCancellation(taskId)); }
    private TaskRow settleCancelledLease(TaskRow task) {
        if (!TaskState.CANCELLED.name().equals(task.state())) return get(task.id());
        settleTerminalInPlaceLease(task, true, "TASK_CANCELLED");
        return get(task.id());
    }
    public void recoverAfterRestart() {
        ManagedVerificationRuntimeService.RecoveryResult runtimeRecovery = managedVerifierRuntimes.recoverActive();
        workspaceCheckpoints.recoverIncomplete();
        for (var recovered : rollingPackages.recoverIncomplete()) {
            if (!recovered.finalReview()) continue;
            TaskRow task = get(recovered.attempt().taskId());
            taskEvidence.captureFinalEvidence(task, recovered.attempt());
            launchRequiredJudges(task, recovered.attempt());
        }
        recoverCompletedCycleHandoffs();
        Map<String, String> interruptedStates = mapper.listRecoverableTasks().stream()
                .collect(java.util.stream.Collectors.toMap(TaskRow::id, TaskRow::state,
                        (left, right) -> left, LinkedHashMap::new));
        // Reconcile verifier writers before any terminal lease can be released.
        // A PID-identity mismatch is persisted as DISCONNECTED and therefore
        // participates in hasUnconfirmedWriter during lease rehydration.
        rehydrateDirectLeases();
        for (Map.Entry<String, String> interrupted : interruptedStates.entrySet()) {
            TaskRow task = mapper.findTask(interrupted.getKey()).orElse(null);
            if (task == null || !interrupted.getValue().equals(task.state())) continue;
            if (TaskState.STOPPING.name().equals(task.state())) {
                try { continueCancellation(task.id()); }
                catch (RuntimeException ignoredConcurrentCancellation) { }
                continue;
            }
            if (TaskState.PACKAGE_DESIGNING.name().equals(task.state())) continue;
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
                        taskEvidence.captureFinalEvidence(current, finalAttempt);
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
                boolean remoteTerminationConfirmed = writerTermination.confirmStopped(task, session, recoveryEvidence);
                taskStates.updateSession(taskStates.sessionState(session, SessionState.DISCONNECTED));
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
                    taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.SESSION_ERROR, "RUNTIME_RESTART",
                            "Application restart disconnected the previous session"));
                }
                recordError(task, stage, attempt, session, ErrorLayer.SESSION, "RUNTIME_RESTART",
                        "Application restart disconnected the previous session", true,
                        recoveryEvidence);
                if (!remoteTerminationConfirmed) {
                    writerTermination.recordUnconfirmed(task, stage, attempt, session,
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
                taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.SESSION_ERROR, "RUNTIME_RESTART",
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
            if (mapper.countSessionErrorsForStage(stage.id()) >= retryPolicy.sessionErrorLimit(spec)) {
                failTask(get(task.id()), "SESSION_RETRY_EXHAUSTED",
                        "Application restart exhausted the configured session retry limit", stage, null, null);
                continue;
            }
            String recoveryPrompt = "Application restart disconnected the previous session. "
                    + "Continue the same stage in a fresh session.";
            TaskRetryScheduleRow retry;
            if (TaskState.RETRY_WAIT.name().equals(interrupted.getValue())) {
                retry = ensureLegacyRetrySchedule(get(task.id()), stage, recoveryPrompt);
            } else {
                retry = scheduleRetry(get(task.id()), stage, RetryCause.SESSION, recoveryPrompt);
            }
            events.emit(task.id(), "task.recovered", Map.of("state", TaskState.RETRY_WAIT.name(),
                    "reason", "restart", "recovery", "scheduled_fresh_session",
                    "retryDueAt", retry.dueAt(), "retryDelaySeconds", retry.delaySeconds()));
        }
    }

    /**
     * Closes the small crash window between ending an execution cycle and projecting the Task as
     * AWAITING_DECISION. A terminal cycle is immutable evidence; restart must not invent another
     * retry cycle or reopen a writer merely because the final Task projection was not committed.
     */
    private void recoverCompletedCycleHandoffs() {
        for (TaskRow candidate : mapper.listRecoverableTasks()) {
            if (TaskState.STOPPING.name().equals(candidate.state())) continue;
            if (rollingPackages.applies(candidate.id())) continue;
            TaskExecutionCycleRow cycle = executionCycles.latest(candidate.id());
            if (cycle == null || !ExecutionCycleState.valueOf(cycle.state()).terminal()) continue;
            TaskRow task = mapper.findTask(candidate.id()).orElse(candidate);
            try {
                LifecycleEvent event = ExecutionCycleState.SUCCEEDED.name().equals(cycle.state())
                        ? LifecycleEvent.APPROVE : LifecycleEvent.FAIL;
                taskStates.updateTask(taskStates.taskState(task, TaskState.AWAITING_DECISION), event,
                        Map.of("cycleId", cycle.id(), "cycleOrdinal", cycle.ordinal(),
                                "cycleResult", cycle.state(), "recoveredAfterRestart", true));
                io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint = workspaceCheckpoints.latest(task.id());
                events.emit(task.id(), "task.awaiting_decision", Map.of(
                        "state", TaskState.AWAITING_DECISION.name(), "cycleId", cycle.id(),
                        "cycleOrdinal", cycle.ordinal(), "cycleResult", cycle.state(),
                        "checkpointState", checkpoint == null ? "NOT_CAPTURED" : checkpoint.state(),
                        "recoveredAfterRestart", true));
                if (checkpoint != null && WorkspaceCheckpointState.READY.name().equals(checkpoint.state())) {
                    settleTerminalInPlaceLease(get(task.id()), true,
                            "TASK_AWAITING_DECISION_CHECKPOINTED_AFTER_RESTART");
                }
            } catch (RuntimeException projectionFailure) {
                events.emit(task.id(), "task.recovery_blocked", Map.of(
                        "code", "TASK_DECISION_PROJECTION_RECOVERY_FAILED",
                        "message", safeMessage(projectionFailure), "cycleId", cycle.id()));
            }
        }
    }

    private void startNewAttempt(TaskRow task, StageRow inputStage, String prompt) {
        TaskRow freshTask = get(task.id());
        LoopSpec spec = spec(freshTask);
        StageRow stage = mapper.findStage(inputStage.id()).orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Stage disappeared"));
        try {
            stageWorkspaceBaselines.captureIfAbsent(freshTask, stage);
            javaChangeGate.captureIfAbsent(freshTask, stage);
        } catch (TaskFailure failure) {
            failTask(freshTask, failure.code(), failure.getMessage(), stage, null, null);
            return;
        }
        AttemptCapacity capacity = attemptCapacity(freshTask, stage, spec);
        if (!capacity.available()) {
            failTask(freshTask, capacity.code(), capacity.message(), stage, null, null);
            return;
        }
        if (!TaskState.RUNNING.name().equals(get(freshTask.id()).state())) return;
        if (!StageState.RUNNING.name().equals(stage.state())) taskStates.updateStage(taskStates.stageState(stage, StageState.RUNNING));
        if (blockModelCallForBudget(freshTask, stage, null)) return;
        Path worktree;
        String boundedPrompt;
        TodoCapability todoCapability;
        try {
            worktree = Path.of(requireWorktree(freshTask));
            ProjectRow project = projects.get(freshTask.projectId());
            worktrees.requireExecutionWorkspace(worktree, Path.of(project.rootPath()),
                    freshTask.branchName(), freshTask.baselineCommit());
            boundedPrompt = executionPrompts.prompt(freshTask, spec, stage, worktree, prompt);
            OpenCodeClient.ToolCapabilityProbe todoProbe;
            try { todoProbe = openCode.toolCapabilities(worktree); }
            catch (RuntimeException ignoredProbeFailure) {
                todoProbe = new OpenCodeClient.ToolCapabilityProbe(OpenCodeClient.CapabilityState.UNKNOWN,
                        List.of(), "Tool discovery failed");
            }
            todoCapability = todoProbe.state() == OpenCodeClient.CapabilityState.AVAILABLE
                    ? todoProbe.contains("todowrite") ? TodoCapability.AVAILABLE : TodoCapability.UNAVAILABLE
                    : TodoCapability.UNKNOWN;
            if (todoCapability == TodoCapability.AVAILABLE) boundedPrompt += executionPrompts.todoInstructions();
        } catch (TaskFailure failure) {
            failTask(freshTask, failure.code(), failure.getMessage(), stage, null, null);
            return;
        } catch (RuntimeException failure) {
            failTask(freshTask, "SESSION_PREPARATION_FAILED", safeMessage(failure), stage, null, null);
            return;
        }
        int ordinal = mapper.countAttemptsForStage(stage.id()) + 1;
        TaskExecutionCycleRow cycle = requireActiveCycle(freshTask);
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), cycle.id(),
                ordinal, AttemptState.RUNNING.name(), null, null, now(), null, 0);
        taskStates.createAttempt(attempt);
        ExecutionSessionRow session = new ExecutionSessionRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), attempt.id(), null,
                SessionState.CREATING.name(), now(), null, 0, todoCapability.name());
        taskStates.createSession(session);
        OpenCodeClient.OpenCodeSession remote;
        try {
            remote = openCode.createSession(worktree, freshTask.title(), model(spec));
            ExecutionSessionRow running = new ExecutionSessionRow(session.id(), session.taskId(), session.stageId(), session.attemptId(), remote.id(),
                    SessionState.RUNNING.name(), session.createdAt(), null, session.version(), session.todoCapability());
            taskStates.updateSession(running);
            if (isAdmittedInPlace(freshTask)) {
                // V12 deliberately references the durable local execution_session
                // row. Provider ids remain on that row and may change across retry.
                directLeases.heartbeat(inPlaceRoot(freshTask), freshTask.id(), running.id());
            }
            openCode.promptAsync(remote, attachmentContext.withContext(
                    DesignerAttachmentContext.ContextUse.task(freshTask.id(), stage.workPackageId()),
                    OpenCodeClient.PromptRequest.text(boundedPrompt)));
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
        javaChangeGate.captureIfAbsent(freshTask, stage);
        AttemptCapacity capacity = attemptCapacity(freshTask, stage, spec);
        if (!capacity.available()) {
            failTask(freshTask, capacity.code(), capacity.message(), stage, null, null);
            return;
        }
        if (!TaskState.RUNNING.name().equals(get(freshTask.id()).state())) return;
        if (!StageState.RUNNING.name().equals(stage.state())) taskStates.updateStage(taskStates.stageState(stage, StageState.RUNNING));
        TaskExecutionCycleRow cycle = requireActiveCycle(freshTask);
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), cycle.id(),
                mapper.countAttemptsForStage(stage.id()) + 1, AttemptState.RUNNING.name(), null,
                "VERIFY_ONLY native verification; no writable OpenCode Session", now(), null, 0);
        taskStates.createAttempt(attempt);
        events.emit(freshTask.id(), "recovery.verify_only.started", Map.of("attemptId", attempt.id(),
                "stageId", stage.id(), "writableSession", false));
    }

    private void startServerArtifactAttempt(TaskRow task, StageRow inputStage) {
        TaskRow freshTask = get(task.id());
        StageRow stage = mapper.findStage(inputStage.id())
                .orElseThrow(() -> new TaskFailure("STAGE_MISSING", "Stage disappeared"));
        if (stage.artifactPlanId() == null || stage.artifactPlanId().isBlank())
            throw new TaskFailure("ARTIFACT_PLAN_MISSING", "Server-executed stage requires a frozen artifactPlanId");
        stageWorkspaceBaselines.captureIfAbsent(freshTask, stage);
        if (!StageState.RUNNING.name().equals(stage.state())) taskStates.updateStage(taskStates.stageState(stage, StageState.RUNNING));
        TaskExecutionCycleRow cycle = requireActiveCycle(freshTask);
        AttemptRow attempt = new AttemptRow(UUID.randomUUID().toString(), freshTask.id(), stage.id(), cycle.id(),
                mapper.countAttemptsForStage(stage.id()) + 1, AttemptState.RUNNING.name(), null,
                "Server-owned artifact materialization; no writable OpenCode Session", now(), null, 0);
        taskStates.createAttempt(attempt);
        ArtifactMaterializationService.Result result = artifactMaterializer.materialize(
                Path.of(requireWorktree(freshTask)), stage.artifactPlanId());
        taskEvidence.persist(freshTask, attempt.id(), null, "SERVER_MATERIALIZATION", result.path(),
                "application/json", write(result), Map.of("executionStrategy", stage.executionStrategy(),
                        "artifactPlanId", stage.artifactPlanId(), "sha256", result.sha256()));
        events.emit(freshTask.id(), "artifact.materialized", Map.of("attemptId", attempt.id(),
                "stageId", stage.id(), "path", result.path(), "sha256", result.sha256(),
                "writableSession", false));
    }

    private boolean serverExecuted(StageRow stage) {
        return Set.of("SERVER_DOCUMENT_MATERIALIZATION", "SERVER_TABULAR_CONVERSION")
                .contains(stage.executionStrategy());
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
        boolean stopped = writerTermination.confirmStopped(currentTask, session, recoveryEvidence);
        if (session != null) taskStates.updateSession(taskStates.sessionState(session, stopped ? SessionState.FAILED : SessionState.DISCONNECTED));
        if (AttemptState.RUNNING.name().equals(attempt.state())) taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.SESSION_ERROR, failure.code(), failure.getMessage()));
        recordError(task, stage, attempt, session, ErrorLayer.SESSION, failure.code(), failure.getMessage(), stopped, recoveryEvidence);
        if (!stopped) {
            writerTermination.recordUnconfirmed(currentTask, stage, attempt, session,
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
        if (mapper.countSessionErrorsForStage(stage.id()) >= retryPolicy.sessionErrorLimit(spec)) {
            failTask(get(task.id()), "SESSION_RETRY_EXHAUSTED", "Session error retry limit reached: " + failure.getMessage(), stage, attempt, session);
            return;
        }
        AttemptCapacity capacity = attemptCapacity(get(task.id()), stage, spec);
        if (!capacity.available()) {
            failTask(get(task.id()), capacity.code(), capacity.message(), stage, attempt, session);
            return;
        }
        RetryCause retryCause = retryPolicy.rateLimited(failure.code(), failure.getMessage())
                ? RetryCause.RATE_LIMIT : RetryCause.SESSION;
        String prompt = "Previous session failed: " + failure.getMessage() + ". Continue the same stage.";
        TaskRetryScheduleRow retry = scheduleRetry(get(task.id()), stage, retryCause, prompt);
        events.emit(task.id(), "session.failed", Map.of("attemptId", attempt.id(), "code", failure.code(),
                "recovery", "scheduled_new_session", "retryCause", retry.cause(),
                "retryOrdinal", retry.ordinal(), "retryDueAt", retry.dueAt(),
                "retryDelaySeconds", retry.delaySeconds()));
    }

    private TaskRetryScheduleRow scheduleRetry(TaskRow task, StageRow stage, RetryCause cause, String prompt) {
        TaskRetryScheduleRow retry = newRetrySchedule(task, stage, cause, prompt);
        transactions.executeWithoutResult(status -> {
            if (mapper.insertTaskRetrySchedule(retry) != 1) {
                throw new ConflictException("RETRY_SCHEDULE_CONFLICT", "Retry schedule could not be persisted");
            }
            taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.RETRY_WAIT), LifecycleEvent.SCHEDULE_RETRY,
                    taskStates.retryAudit(retry));
        });
        events.emit(task.id(), "task.retry_scheduled", Map.of("retryCause", retry.cause(),
                "retryOrdinal", retry.ordinal(), "retryDueAt", retry.dueAt(),
                "retryDelaySeconds", retry.delaySeconds()));
        return retry;
    }

    private TaskRetryScheduleRow ensureLegacyRetrySchedule(TaskRow task, StageRow stage, String prompt) {
        TaskRetryScheduleRow existing = mapper.findActiveTaskRetrySchedule(task.id()).orElse(null);
        if (existing != null) return existing;
        TaskRetryScheduleRow retry = newRetrySchedule(task, stage, RetryCause.SESSION, prompt);
        if (mapper.insertTaskRetrySchedule(retry) != 1) {
            throw new ConflictException("RETRY_SCHEDULE_CONFLICT", "Legacy retry schedule could not be persisted");
        }
        events.emit(task.id(), "task.retry_schedule_recovered", Map.of("retryCause", retry.cause(),
                "retryOrdinal", retry.ordinal(), "retryDueAt", retry.dueAt(),
                "retryDelaySeconds", retry.delaySeconds()));
        return retry;
    }

    private TaskRetryScheduleRow newRetrySchedule(TaskRow task, StageRow stage, RetryCause cause, String prompt) {
        int ordinal = mapper.countTaskRetrySchedules(task.id(), stage.id(), cause.name()) + 1;
        int delaySeconds = retryPolicy.delaySeconds(cause, ordinal);
        Instant created = Instant.now();
        return new TaskRetryScheduleRow(UUID.randomUUID().toString(), task.id(), stage.id(), cause.name(), ordinal,
                delaySeconds, created.plusSeconds(delaySeconds).toString(), null, safeMessage(prompt),
                RETRY_SCHEDULED, created.toString(), created.toString(), 0);
    }

    /**
     * Retries only the remote abort obligation for a terminal Task. It never
     * changes the Task/Attempt state and therefore cannot create another writer.
     * The error log is the persistent retry counter and survives application
     * restarts; the monitor stops after the configured bound.
     */
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
        boolean stopped = writerTermination.confirmStopped(task, session, evidence);
        if (stopped) {
            taskStates.updateSession(taskStates.sessionState(session, SessionState.ABORTED));
            recordError(task, stage, attempt, session, ErrorLayer.SESSION,
                    "SESSION_ABORT_CLEANUP_CONFIRMED",
                    "The remote Session was confirmed stopped by bounded cleanup", false, evidence);
            events.emit(task.id(), "session.cleanup_confirmed",
                    Map.of("sessionId", session.id(), "attempt", attemptNumber));
            if (TaskState.STOPPING.name().equals(task.state())) {
                continueCancellation(task.id());
                return;
            }
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
                                                                          AttemptRow attempt, String failureCode,
                                                                          String message,
                                                                          LoopSpec spec,
                                                                          AttemptHandoffService.Capture handoff,
                                                                          int stagnationCount) {
        taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.VERIFICATION_FAILED, failureCode, message));
        recordError(task, stage, attempt, mapper.latestSessionForAttempt(attempt.id()).orElse(null), ErrorLayer.VERIFICATION,
                failureCode, message, true, Map.of());
        AttemptCapacity capacity = attemptCapacity(get(task.id()), stage, spec);
        if (!capacity.available()) {
            failTask(get(task.id()), capacity.code(), capacity.message(), stage, attempt, null);
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
        String prompt = handoff == null
                ? "Verification failed: " + message + ". Fix the evidence and retry the current stage."
                : attemptHandoffs.retryPrompt(handoff, spec.nextAttemptPromptTemplate());
        TaskRetryScheduleRow retry = scheduleRetry(get(task.id()), stage, RetryCause.VERIFICATION, prompt);
        events.emit(task.id(), "verification.failed", Map.of("attemptId", attempt.id(),
                "recovery", "scheduled_next_attempt", "summary", message, "retryCause", retry.cause(),
                "retryOrdinal", retry.ordinal(), "retryDueAt", retry.dueAt(),
                "retryDelaySeconds", retry.delaySeconds()));
        return VerificationContinuation.none(task.id());
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
        taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.WAITING_INPUT),
                LifecycleEvent.REQUIRE_INPUT, code, Map.of());
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
        taskEvidence.persist(task, attempt.id(), null, ATTEMPT_HANDOFF_ARTIFACT_KIND,
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
        if (!mapper.activeSessions(task.id()).isEmpty() || writerTermination.hasUnconfirmedWriter(task.id())) {
            throw new ConflictException("SESSION_WRITER_ACTIVE", "A fresh retry cannot overlap an existing or unconfirmed writer");
        }
        StageRow stage = mapper.listStages(task.id()).stream()
                .filter(row -> StageState.RUNNING.name().equals(row.state())).findFirst()
                .orElseThrow(() -> new ConflictException("STAGE_NOT_RUNNING", "The waiting task has no active stage to retry"));
        LoopSpec spec = spec(task);
        AttemptCapacity capacity = attemptCapacity(task, stage, spec);
        if (!capacity.available()) {
            throw new ConflictException(capacity.code(), capacity.message());
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
        taskEvidence.persist(task, null, null, LOOP_STAGNATION_OVERRIDE_ARTIFACT_KIND,
                "loop-stagnation-override.json", "application/json",
                write(Map.of("stageId", stage.id(), "source", "LOCAL_UI", "approvedAt", now())),
                Map.of("stageId", stage.id(), "source", "LOCAL_UI"));
        taskStates.updateTask(taskStates.taskState(task, TaskState.RUNNING));
        events.emit(task.id(), "task.loop_retry_requested", Map.of("state", TaskState.RUNNING.name(),
                "stageId", stage.id(), "source", "LOCAL_UI", "freshSession", true));
        return new LoopRetryPreparation(mapper.findStage(stage.id()).orElse(stage), prompt);
    }

    public LoopRetryStatus loopRetryStatus(String taskId) {
        return loopRetryStatus(get(taskId));
    }

    private LoopRetryStatus loopRetryStatus(TaskRow task) {
        String code = TaskWaitingInputPolicy.reasonCode(task, mapper);
        return new LoopRetryStatus(code, TaskWaitingInputPolicy.loopRetryAvailable(code));
    }

    public record LoopRetryStatus(String waitingReasonCode, boolean loopRetryAvailable) { }
    public record WorkspaceDirtyResolution(TaskRow task, GitWorktreeManager.DirtyWorkspace workspace) { }
    private record LoopRetryPreparation(StageRow stage, String prompt) { }
    private record AttemptCapacity(boolean available, String code, String message) {
        static AttemptCapacity allowed() { return new AttemptCapacity(true, null, null); }
    }

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

        taskStates.updateTask(taskStates.taskState(get(taskId), TaskState.PREPARING), LifecycleEvent.RETRY_PREPARATION);
        events.emit(taskId, "workspace.cleanup_confirmed",
                Map.of("state", TaskState.PREPARING.name(), "source", "LOCAL_UI"));
        TaskRow continued = prepareAdmittedTaskAndContinue(taskId);
        return new WorkspaceDirtyResolution(continued, worktrees.inspectDirtyWorkspace(root));
    }

    /** Cancels the Task while preserving the user's dirty source workspace byte-for-byte. */
    public TaskRow cancelDirtyWorkspace(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.valueOf(task.state()).cancellationAvailable()) {
            throw new ConflictException("SOURCE_BRANCH_WORKSPACE_NOT_ACTIONABLE",
                    "This task no longer accepts the standard cancellation command");
        }
        LoopRetryStatus wait = loopRetryStatus(task);
        if (TaskWaitingInputPolicy.currentDirtyWorkspaceWait(task, wait.waitingReasonCode())) {
            recordError(task, null, null, null, ErrorLayer.TASK, "SOURCE_BRANCH_WORKSPACE_CANCELLED",
                    "User cancelled source-workspace cleanup before the Task branch was created", false,
                    Map.of("localFilesPreserved", true, "resolution", TaskState.CANCELLED.name()));
        }
        return cancel(taskId);
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
        taskStates.updateTask(taskStates.taskState(task, TaskState.WAITING_INPUT), LifecycleEvent.REQUIRE_INPUT,
                "SOURCE_BRANCH_WORKSPACE_DIRTY", Map.of());
        events.emit(task.id(), "task.workspace_cleanup_required",
                Map.of("state", TaskState.WAITING_INPUT.name(), "fileCount", workspace.files().size()));
        return get(task.id());
    }

    private VerificationContinuation completeStageState(TaskRow task, StageRow stage, AttemptRow attempt) {
        taskStates.updateAttempt(taskStates.finishAttempt(attempt, AttemptState.SUCCEEDED, null, "所有确定性验证均已通过"));
        taskStates.updateStage(taskStates.stageState(stage, StageState.SUCCEEDED));
        StageRow next = mapper.listStages(task.id()).stream().filter(s -> StageState.PENDING.name().equals(s.state())).findFirst().orElse(null);
        boolean packageCompleted = !blank(stage.workPackageId())
                && (next == null || !stage.workPackageId().equals(next.workPackageId()));
        if (packageCompleted) {
            events.emit(task.id(), "work_package.succeeded", Map.of("workPackageId", stage.workPackageId(),
                    "state", "SUCCEEDED"));
        }
        if (rollingPackages.applies(task.id()) && packageCompleted) {
            return VerificationContinuation.packageCheckpoint(task.id(), attempt.id(), stage.id(),
                    stage.packageRunId());
        }
        if (next == null) {
            taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.JUDGING));
            return VerificationContinuation.finalReview(task.id(), attempt.id(), stage.id());
        } else {
            taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.RUNNING));
            if (!blank(next.workPackageId()) && !next.workPackageId().equals(stage.workPackageId())) {
                events.emit(task.id(), "work_package.started", Map.of("workPackageId", next.workPackageId(),
                        "state", "RUNNING"));
            }
            return VerificationContinuation.nextStage(task.id(), next.id(),
                    "Start next stage: " + next.objective());
        }
    }

    private AttemptCapacity attemptCapacity(TaskRow task, StageRow stage, LoopSpec spec) {
        TaskExecutionCycleRow cycle = requireActiveCycle(task);
        int stageLimit = Math.min(spec.limits().maxStageAttempts(), defaults.getMaxStageAttempts());
        int taskLimit = Math.min(spec.limits().maxTaskAttempts(), defaults.getMaxTaskAttempts());
        if (mapper.countAttemptsForCycleStage(cycle.id(), stage.id()) >= stageLimit) {
            return new AttemptCapacity(false, "ATTEMPT_LIMIT_EXHAUSTED",
                    "The configured Stage attempt limit was reached");
        }
        if (mapper.countAttemptsForCycle(cycle.id()) >= taskLimit) {
            return new AttemptCapacity(false, "ATTEMPT_LIMIT_EXHAUSTED",
                    "The configured Task attempt limit was reached");
        }
        if (blank(stage.workPackageId())) return AttemptCapacity.allowed();
        List<StageRow> packageStages = mapper.listStages(task.id()).stream()
                .filter(candidate -> stage.workPackageId().equals(candidate.workPackageId())).toList();
        int packageLimit = Math.min(packageStages.size() * stageLimit,
                packageStages.size() + 2);
        int used = mapper.countAttemptsForCycleWorkPackage(cycle.id(), stage.workPackageId());
        int otherUnstarted = (int) packageStages.stream()
                .filter(candidate -> !candidate.id().equals(stage.id()))
                .filter(candidate -> mapper.countAttemptsForCycleStage(cycle.id(), candidate.id()) == 0).count();
        if (used >= packageLimit || used >= packageLimit - otherUnstarted) {
            return new AttemptCapacity(false, "WORK_PACKAGE_ATTEMPT_LIMIT_EXHAUSTED",
                    "Work package " + stage.workPackageId() + " exhausted its independent attempt pool of "
                            + packageLimit + " while reserving one first attempt for every unstarted Stage");
        }
        return AttemptCapacity.allowed();
    }

    /**
     * Polls only final-review sessions. Provider RETRY is an in-progress remote projection and
     * keeps the same judge row; a true terminal transport/model problem is recorded as a SESSION
     * error and retried with a fresh judge row. Neither path enters normal execution recovery.
     */
    public void pollJudges(String taskId) {
        TaskRow task = get(taskId);
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        enforceTimeouts(taskId);
        if (!TaskState.JUDGING.name().equals(get(taskId).state())) return;
        for (JudgeRunRow judge : mapper.activeJudgeRuns(taskId)) {
            if (!TaskState.JUDGING.name().equals(get(taskId).state())) break;
            pollJudge(get(taskId), judge);
        }
        if (TaskState.JUDGING.name().equals(get(taskId).state())) recoverCandidateJudgeFailures(get(taskId));
        if (TaskState.JUDGING.name().equals(get(taskId).state())) evaluateJudgeDecision(get(taskId));
    }
    private void launchRequiredJudges(TaskRow task, AttemptRow finalAttempt) {
        JudgeReviewBatchRow activeBatch = judgeBatches.findRunning(task.id()).orElse(null);
        List<String> pendingRoles = List.of("REQUIREMENT", "RISK").stream()
                .filter(role -> activeBatch == null
                        || mapper.latestJudgeRunForBatchRole(activeBatch.id(), role).isEmpty())
                .toList();
        launchJudges(task, finalAttempt, pendingRoles, false);
        if (TaskState.JUDGING.name().equals(get(task.id()).state())) {
            events.emit(task.id(), "task.judging", Map.of("state", TaskState.JUDGING.name(), "judges", List.of("REQUIREMENT", "RISK")));
        }
    }

    /**
     * Explicit local-UI review is the recovery path for missing, rejected, malformed, or
     * retry-exhausted Judge runs. It authorizes exactly one fresh pair of read-only sessions;
     * later transport failures still return to WAITING_INPUT instead of looping forever.
     */
    public TaskRow retryJudges(String taskId) {
        TaskRow task = get(taskId);
        if (mapper.findTaskPublication(taskId).map(io.opencode.loopper.persistence.TaskPublicationRow::state)
                .filter(io.opencode.loopper.domain.TaskPublicationState.MERGED.name()::equals).isPresent()) {
            throw new ConflictException("TASK_PUBLICATION_MERGED", "任务已经合并，不能重新打开原任务评审；请使用新分支重做");
        }
        if (!TaskState.WAITING_INPUT.name().equals(task.state())) {
            throw new ConflictException("JUDGE_REVIEW_NOT_ACTIONABLE",
                    "只有等待评审处理的当前任务可以重新发起双评审");
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
        JudgeReviewBatchRow latestBatch = judgeBatches.latest(task.id()).orElse(null);
        if (latestBatch != null && approved(mapper.latestJudgeRunForBatchRole(latestBatch.id(), "REQUIREMENT").orElse(null))
                && approved(mapper.latestJudgeRunForBatchRole(latestBatch.id(), "RISK").orElse(null))) {
            throw new ConflictException("JUDGE_REVIEW_ALREADY_APPROVED", "需求与风险双评审已经通过");
        }

        taskStates.updateTask(taskStates.taskState(task, TaskState.JUDGING));
        events.emit(task.id(), "task.judge_retry_requested", Map.of(
                "state", TaskState.JUDGING.name(), "source", "LOCAL_UI", "judges", List.of("REQUIREMENT", "RISK")));
        launchJudges(get(task.id()), finalAttempt, List.of("REQUIREMENT", "RISK"), true);
        return get(task.id());
    }

    private boolean approved(JudgeRunRow judge) {
        return judge != null && JudgeRunState.COMPLETED.name().equals(judge.state()) && "PASS".equals(judge.verdict());
    }

    private void launchJudges(TaskRow inputTask, AttemptRow finalAttempt, List<String> roles,
                              boolean explicitLocalRetry) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state()) || roles.isEmpty()) return;
        if (!explicitLocalRetry && blockModelCallForBudget(task, null, finalAttempt)) return;
        JudgeReviewBatchRow batch = explicitLocalRetry ? judgeBatches.create(task, finalAttempt)
                : judgeBatches.findRunning(task.id()).orElseGet(() -> judgeBatches.create(task, finalAttempt));
        LoopSpec spec = spec(task);
        Map<String, TaskEvidenceService.JudgeCandidateSource> sources = new LinkedHashMap<>();
        for (String role : roles) {
            if (!explicitLocalRetry && mapper.countJudgeSessionErrorsForBatchRole(batch.id(), role) >= retryPolicy.sessionErrorLimit(spec)) {
                waitForJudgeInput(task, finalAttempt, null, "JUDGE_SESSION_RETRY_EXHAUSTED",
                        role + " Judge exhausted its configured session retry limit");
                return;
            }
            try {
                sources.put(role, taskEvidence.judgeCandidateSource(task, finalAttempt, role, spec(task)));
            } catch (TaskFailure failure) {
                if (!"JUDGE_PROMPT_BUDGET_EXCEEDED".equals(failure.code())) throw failure;
                waitForJudgeInput(task, finalAttempt, null, failure.code(), failure.getMessage());
                return;
            }
        }
        for (String role : roles) {
            if (!TaskState.JUDGING.name().equals(get(task.id()).state())) break;
            launchJudge(get(task.id()), finalAttempt, batch, role, explicitLocalRetry, false, sources.get(role));
        }
    }

    private void launchJudge(TaskRow inputTask, AttemptRow finalAttempt, JudgeReviewBatchRow batch,
                             String role, boolean explicitLocalRetry, boolean forceLegacy, TaskEvidenceService.JudgeCandidateSource source) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        LoopSpec spec = spec(task);
        boolean candidate = defaults.getInternalCandidate().isJudgeDecisionV1Enabled() && !forceLegacy;
        ModelResponseMode responseMode = candidate ? null : legacyJudgeTransport.responseMode(
                task, role, judgeModel(spec, ModelResponseMode.JSON_SCHEMA));
        JudgeRunRow judge = new JudgeRunRow(UUID.randomUUID().toString(), task.id(), finalAttempt.id(), role,
                mapper.nextJudgeOrdinal(task.id(), role), null, JudgeRunState.CREATING.name(), null, null, null,
                now(), null, 0, candidate ? JudgeDecisionCandidateWorkflow.RESPONSE_MODE : responseMode.name(), !candidate
                && responseMode == ModelResponseMode.JSON_SCHEMA ? OpenCodeStructuredSchemas.JUDGE_DECISION_V1 : null,
                batch.id(), (long) batch.generation());
        lifecycle.create(taskStates.subject(LifecycleMachineType.JUDGE_RUN, judge.id(), judge.taskId()), judge.state(),
                Map.of("role", judge.role()), () -> mapper.insertJudgeRun(judge),
                () -> new ConflictException("JUDGE_CREATE_CONFLICT", "Judge run could not be created"));
        taskEvidence.persist(task, finalAttempt.id(), judge.id(), "JUDGE_LOG_METADATA", role.toLowerCase() + "-judge-start.json",
                "application/json", write(Map.of("role", role, "state", JudgeRunState.CREATING.name(), "readOnly", true)),
                Map.of("source", "judge-session", "readOnly", true));
        if (candidate) {
            JudgeDecisionCandidateWorkflow.Result result = judgeCandidates.advance(candidateContext(task, finalAttempt, batch, judge));
            if (result.action() == JudgeDecisionCandidateWorkflow.Action.LEGACY_FALLBACK) {
                launchJudge(get(task.id()), finalAttempt, batch, role, explicitLocalRetry, true, source);
            } else {
                handleCandidateJudgeResult(task, result);
                JudgeRunRow current = mapper.findJudgeRun(judge.id()).orElse(judge);
                if (JudgeRunState.RUNNING.name().equals(current.state())) {
                    events.emit(task.id(), "judge.started", Map.of(
                            "judgeRunId", current.id(), "role", role, "externalSessionId", current.externalSessionId(),
                            "readOnly", true, "responseMode", JudgeDecisionCandidateWorkflow.RESPONSE_MODE));
                }
            }
            return;
        }
        OpenCodeClient.OpenCodeSession remote;
        try {
            TaskEvidenceService.FrozenJudgeSource frozen = taskEvidence.freezeLegacyJudgeSource(task, finalAttempt, judge, source);
            Path worktree = Path.of(requireWorktree(task));
            remote = openCode.createSession(worktree, roleTitle(role), judgeModel(spec, responseMode),
                    OpenCodeClient.SessionProfile.JUDGE_READ_ONLY);
            JudgeRunRow running = judgeState(judge, remote.id(), JudgeRunState.RUNNING, null, null, null, null);
            updateJudge(running);
            OpenCodeClient.PromptRequest request;
            if (responseMode == ModelResponseMode.JSON_SCHEMA) {
                request = new OpenCodeClient.PromptRequest(frozen.source().prompt(), null, null,
                        OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.JUDGE_DECISION_V1));
            } else {
                request = OpenCodeClient.PromptRequest.text(frozen.source().prompt());
            }
            openCode.promptAsync(remote, attachmentContext.withContext(
                    DesignerAttachmentContext.ContextUse.taskAllPackages(task.id()), request));
        } catch (SessionFailure failure) {
            handleJudgeSessionFailure(task, judge, failure);
            return;
        } catch (RuntimeException exception) {
            handleJudgeSessionFailure(task, judge, new SessionFailure("JUDGE_SESSION_RUNTIME_ERROR", safeMessage(exception)));
            return;
        }
        events.emit(task.id(), "judge.started", Map.of("judgeRunId", judge.id(), "role", role, "externalSessionId", remote.id(), "readOnly", true));
    }
    private JudgeDecisionCandidateWorkflow.Context candidateContext(TaskRow task, AttemptRow attempt, JudgeReviewBatchRow batch, JudgeRunRow judge) {
        LoopSpec frozen = spec(task);
        return new JudgeDecisionCandidateWorkflow.Context(judge, batch, Path.of(requireWorktree(task)),
                judgeModel(frozen, ModelResponseMode.TEXT_MARKER), taskEvidence.judgeCandidateSource(task, attempt, judge.role(), frozen),
                Instant.parse(judge.createdAt()).plusSeconds(frozen.limits().attemptTimeoutSeconds()));
    }
    private void handleCandidateJudgeResult(TaskRow inputTask, JudgeDecisionCandidateWorkflow.Result result) {
        if (result == null || result.judge() == null) return;
        TaskRow task = get(inputTask.id());
        JudgeRunRow judge = result.judge();
        if (result.action() == JudgeDecisionCandidateWorkflow.Action.COMPLETED) {
            usageInsights.collectTerminalJudgeUsage(task.id(), judge.id());
            taskEvidence.persist(task, judge.attemptId(), judge.id(), "JUDGE_RESULT", judge.role().toLowerCase()
                    + "-judge-result.json", "application/json", judge.rawOutput() == null ? "" : judge.rawOutput(),
                    Map.of("role", judge.role(), "verdict", judge.verdict(), "reason", judge.reason(),
                            "state", judge.state(), "source", "INTERNAL_MCP"));
            events.emit(task.id(), "judge.completed", Map.of("judgeRunId", judge.id(),
                    "role", judge.role(), "verdict", judge.verdict()));
        } else if (result.action() == JudgeDecisionCandidateWorkflow.Action.SESSION_ERROR) {
            usageInsights.collectTerminalJudgeUsage(task.id(), judge.id());
            String reason = judge.reason() == null ? "JUDGE_CANDIDATE_FAILED" : judge.reason();
            String code = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
            AttemptRow attempt = mapper.findAttempt(judge.attemptId()).orElse(null);
            recordError(task, null, attempt, null, ErrorLayer.SESSION, code, reason, true, Map.of(
                    "judgeRunId", judge.id(), "judgeRole", judge.role(), "judgeSession", true, "responseMode", "INTERNAL_MCP"));
            events.emit(task.id(), "judge.session_failed", Map.of("judgeRunId", judge.id(), "role",
                    judge.role(), "code", code, "recovery", "fresh_read_only_session"));
        } else if (result.action() == JudgeDecisionCandidateWorkflow.Action.WAITING_INPUT) {
            waitForJudgeInput(task, mapper.findAttempt(judge.attemptId()).orElse(null), judge,
                    "JUDGE_CANDIDATE_WAITING_INPUT", judge.reason() == null
                            ? "Judge candidate requires local input" : judge.reason());
        }
    }
    private void recoverCandidateJudgeFailures(TaskRow task) {
        for (String role : List.of("REQUIREMENT", "RISK")) {
            JudgeRunRow failed = mapper.latestJudgeRun(task.id(), role).orElse(null);
            if (failed == null || !JudgeDecisionCandidateWorkflow.RESPONSE_MODE.equals(failed.responseMode())
                    || !JudgeRunState.SESSION_ERROR.name().equals(failed.state())) continue;
            AttemptRow attempt = mapper.findAttempt(failed.attemptId()).orElse(null);
            if (attempt == null) continue;
            if (mapper.countJudgeSessionErrorsForBatchRole(failed.reviewBatchId(), role) >= retryPolicy.sessionErrorLimit(spec(task))) {
                waitForJudgeInput(task, attempt, failed, "JUDGE_SESSION_RETRY_EXHAUSTED",
                        role + " Judge exhausted its configured session retry limit: "
                                + safeMessage(failed.reason()));
                return;
            }
            launchJudges(get(task.id()), attempt, List.of(role), false);
        }
    }
    private void pollJudge(TaskRow inputTask, JudgeRunRow inputJudge) {
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (judgeCandidates.owns(judge)) {
            AttemptRow attempt = mapper.findAttempt(judge.attemptId()).orElseThrow(() -> new ConflictException(
                    "JUDGE_FINAL_ATTEMPT_MISSING", "Judge final attempt is missing"));
            JudgeReviewBatchRow batch = judgeBatches.require(judge.reviewBatchId());
            JudgeDecisionCandidateWorkflow.Result result = judgeCandidates.advance(candidateContext(
                    get(inputTask.id()), attempt, batch, judge));
            if (result.action() == JudgeDecisionCandidateWorkflow.Action.LEGACY_FALLBACK) {
                launchJudge(get(inputTask.id()), attempt, batch, judge.role(), false, true,
                        taskEvidence.judgeCandidateSource(get(inputTask.id()), attempt, judge.role(), spec(inputTask)));
            } else {
                handleCandidateJudgeResult(inputTask, result);
            }
            return;
        }
        if (!JudgeRunState.RUNNING.name().equals(judge.state()) || judge.externalSessionId() == null) return;
        try {
            long timeoutSeconds = spec(inputTask).limits().attemptTimeoutSeconds();
            if (Instant.parse(judge.createdAt()).plusSeconds(timeoutSeconds).isBefore(StoryAccountingClock.sessionNow(mapper, judge.externalSessionId(), judge.createdAt()))) {
                handleJudgeSessionFailure(inputTask, judge, new SessionFailure("JUDGE_TIMEOUT", "Judge exceeded its configured session timeout"));
                return;
            }
            OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(judge.externalSessionId(), Path.of(requireWorktree(inputTask)));
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.retrying()) return;
            if (status.failed()) {
                String message = status.detail() == null || status.detail().isBlank()
                        ? "OpenCode judge session ended in " + status.state() : status.detail();
                handleJudgeSessionFailure(inputTask, judge, new SessionFailure("JUDGE_SESSION_" + status.state(), message));
                return;
            }
            if (status.completed()) legacyJudgeCompletion.complete(
                    inputTask, judge, legacyJudgeTransport.output(judge, remote));
        } catch (SessionFailure failure) {
            if (recoverJudgeToolLoop(inputTask, judge,
                    new OpenCodeClient.OpenCodeSession(judge.externalSessionId(), Path.of(requireWorktree(inputTask))),
                    failure)) return;
            handleJudgeSessionFailure(inputTask, judge, failure);
        } catch (RuntimeException exception) {
            // Optimistic-lock races with cancel/pause are not reclassified as an execution fault.
            if (TaskState.JUDGING.name().equals(get(inputTask.id()).state())) {
                handleJudgeSessionFailure(inputTask, judge, new SessionFailure("JUDGE_STATUS_RUNTIME_ERROR", safeMessage(exception)));
            }
        }
    }

    private boolean recoverJudgeToolLoop(TaskRow inputTask, JudgeRunRow judge,
                                         OpenCodeClient.OpenCodeSession failedRemote,
                                         SessionFailure failure) {
        if (!"OPENCODE_MACHINE_TOOL_LOOP".equals(failure.code())) return false;
        TaskRow task = get(inputTask.id());
        AttemptRow attempt = mapper.findAttempt(judge.attemptId()).orElse(null);
        if (attempt == null || blockModelCallForBudget(task, null, attempt)) return true;
        return legacyJudgeTransport.recoverToolLoop(task, judge, failedRemote, failure,
                judgeModel(spec(task), judge.responseMode()));
    }

    private void handleJudgeSessionFailure(TaskRow inputTask, JudgeRunRow inputJudge, SessionFailure failure) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        JudgeRunRow judge = mapper.findJudgeRun(inputJudge.id()).orElse(inputJudge);
        if (!JudgeRunState.CREATING.name().equals(judge.state())
                && !JudgeRunState.RUNNING.name().equals(judge.state())) return;
        JudgeRunRow failed = judgeState(judge, judge.externalSessionId(), JudgeRunState.SESSION_ERROR,
                null, failure.code() + ": " + safeMessage(failure.getMessage()), null, now());
        updateJudge(failed);
        usageInsights.collectTerminalJudgeUsage(task.id(), failed.id());
        taskEvidence.persist(task, judge.attemptId(), judge.id(), "JUDGE_LOG_METADATA", judge.role().toLowerCase() + "-judge-session-error.json",
                "application/json", write(Map.of("role", judge.role(), "code", failure.code(),
                        "message", safeMessage(failure.getMessage()), "state", JudgeRunState.SESSION_ERROR.name())),
                Map.of("source", "judge-session", "retryable", true));
        AttemptRow attempt = mapper.findAttempt(judge.attemptId()).orElse(null);
        recordError(task, null, attempt, null, ErrorLayer.SESSION, failure.code(), failure.getMessage(), true,
                Map.of("judgeRunId", judge.id(), "judgeRole", judge.role(), "judgeSession", true));
        LoopSpec spec = spec(task);
        if (judge.reviewBatchId() != null && mapper.countJudgeSessionErrorsForBatchRole(judge.reviewBatchId(), judge.role()) >= retryPolicy.sessionErrorLimit(spec)) {
            waitForJudgeInput(task, attempt, judge, "JUDGE_SESSION_RETRY_EXHAUSTED",
                    judge.role() + " Judge exhausted its configured session retry limit: " + safeMessage(failure.getMessage()));
            return;
        }
        events.emit(task.id(), "judge.session_failed", Map.of("judgeRunId", judge.id(), "role", judge.role(), "code", failure.code(), "recovery", "fresh_read_only_session"));
        if (attempt != null) launchJudges(task, attempt, List.of(judge.role()), false);
    }

    private void evaluateJudgeDecision(TaskRow task) {
        JudgeReviewBatchRow batch = judgeBatches.findRunning(task.id()).orElse(null);
        if (batch == null) return;
        JudgeRunRow requirement = mapper.latestJudgeRunForBatchRole(
                batch.id(), "REQUIREMENT").orElse(null);
        JudgeRunRow risk = mapper.latestJudgeRunForBatchRole(batch.id(), "RISK").orElse(null);
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
        usageInsights.collectTaskUsage(task.id());
        judgeBatches.transition(batch.id(), io.opencode.loopper.domain.JudgeReviewBatchState.COMPLETED);
        finishCycleAndAwaitDecision(get(task.id()), ExecutionCycleState.SUCCEEDED, null,
                "Requirement and Risk judges passed", !writerTermination.hasUnconfirmedWriter(task.id()));
    }

    private void waitForJudgeInput(TaskRow inputTask, AttemptRow attempt, JudgeRunRow judge, String code, String message) {
        TaskRow task = get(inputTask.id());
        if (!TaskState.JUDGING.name().equals(task.state())) return;
        if (!writerTermination.stopJudges(task)) {
            events.emit(task.id(), "task.judge_cleanup_pending", Map.of(
                    "state", task.state(), "code", code, "message", safeMessage(message)));
            return;
        }
        judgeBatches.findRunning(task.id()).ifPresent(batch -> judgeBatches.transition(
                batch.id(), io.opencode.loopper.domain.JudgeReviewBatchState.WAITING_INPUT));
        recordError(task, null, attempt, null, ErrorLayer.VERIFICATION, code, message, false,
                Map.of("judgeRunId", judge == null ? "" : judge.id(), "judgeRole", judge == null ? "" : judge.role(),
                        "resolution", TaskState.WAITING_INPUT.name()));
        taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.WAITING_INPUT),
                LifecycleEvent.REQUIRE_INPUT, code, Map.of());
        events.emit(task.id(), "task.judge_waiting_input", Map.of("state", TaskState.WAITING_INPUT.name(), "code", code, "message", safeMessage(message)));
    }

    /** Commits the soft-budget decision before an auxiliary provider model call such as Session summarize. */
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
            taskStates.updateTask(taskStates.taskState(task, TaskState.WAITING_INPUT),
                    LifecycleEvent.REQUIRE_INPUT, decision.code(), Map.of("operation", operation));
            nextState = TaskState.WAITING_INPUT.name();
        }
        events.emit(task.id(), "task.budget_waiting_input", Map.of("state", nextState,
                "code", decision.code(), "message", decision.message(), "nextCallBlocked", true,
                "operation", operation));
        return decision;
    }

    private String roleTitle(String role) { return "REQUIREMENT".equals(role) ? "Requirement Judge" : "Risk Judge"; }
    private JudgeRunRow judgeState(JudgeRunRow row, String externalSessionId, JudgeRunState state, String verdict, String reason,
                                   String rawOutput, String endedAt) {
        return new JudgeRunRow(row.id(), row.taskId(), row.attemptId(), row.role(), row.ordinal(), externalSessionId, state.name(),
                verdict, safeNullable(reason), rawOutput, row.createdAt(), endedAt, row.version(),
                row.responseMode(), row.responseSchemaId(), row.reviewBatchId(), row.sourceRevision());
    }
    private void updateJudge(JudgeRunRow row) {
        JudgeRunRow current = mapper.findJudgeRun(row.id()).orElseThrow(() -> new NotFoundException("Judge run not found: " + row.id()));
        if (current.state().equals(row.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateJudgeRun(row),
                    () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
        } else {
            lifecycle.transition(taskStates.subject(LifecycleMachineType.JUDGE_RUN, row.id(), row.taskId()), current.state(), row.state(),
                    null, Map.of("role", row.role()), () -> mapper.updateJudgeRun(row),
                    () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
        }
    }
    private String safeNullable(String value) { return value == null ? null : safeMessage(value); }
    private record TaskCreation(String taskId, boolean existing) { }
    private record ExecutionRoleSnapshot(String rolePackId, String rolePackVersion, String testPolicy,
            String technologiesJson, String projectStackProfileId, String componentKeysJson, String stackFingerprint) { }
    private record PendingVerification(String id, int index, VerifierOutcome outcome) { }
    private void failTask(TaskRow task, String code, String message, StageRow stage, AttemptRow attempt, ExecutionSessionRow session) {
        TaskRow current = mapper.findTask(task.id()).orElse(task);
        if (TaskState.valueOf(current.state()).terminal()) return;
        taskStates.cancelRetrySchedule(current.id(), RETRY_CANCELLED);
        boolean writersStopped = writerTermination.stopSessions(current);
        writersStopped = writerTermination.stopJudgesForTaskFailure(current) && writersStopped;
        // The task-fatal boundary closes every active attempt. Some failures are
        // discovered before a caller has an AttemptRow reference (for example a
        // malformed persisted verifier), but no child may remain RUNNING after
        // its parent Task has exited.
        for (AttemptRow active : mapper.listAttempts(current.id())) {
            if (AttemptState.RUNNING.name().equals(active.state())) {
                taskStates.updateAttempt(taskStates.finishAttempt(active, AttemptState.TASK_ERROR, code, message));
            }
        }
        for (StageRow active : mapper.listStages(current.id())) {
            if (StageState.RUNNING.name().equals(active.state()) || StageState.PAUSED.name().equals(active.state())) {
                taskStates.updateStage(taskStates.stageState(active, StageState.FAILED));
            }
        }
        recordError(current, stage, attempt, session, ErrorLayer.TASK, code, message, false, Map.of());
        if (rollingPackages.applies(current.id())) {
            rollingPackages.fail(get(current.id()), code, message,
                    writersStopped && !writerTermination.hasUnconfirmedWriter(current.id()));
            return;
        }
        finishCycleAndAwaitDecision(get(current.id()), ExecutionCycleState.FAILED, code, message,
                writersStopped && !writerTermination.hasUnconfirmedWriter(current.id()));
    }
    private void finishCycleAndAwaitDecision(TaskRow task, ExecutionCycleState result, String code,
                                             String message, boolean writersStopped) {
        TaskExecutionCycleRow active = executionCycles.active(task.id());
        if (active == null) active = executionCycles.ensureInitial(task, cycleBudgetSnapshot(spec(task)));
        TaskExecutionCycleRow ended = executionCycles.finish(task.id(), result, code, message);
        io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint = null;
        if (writersStopped) checkpoint = workspaceCheckpoints.freeze(get(task.id()), ended);
        LifecycleEvent event = result == ExecutionCycleState.SUCCEEDED ? LifecycleEvent.APPROVE : LifecycleEvent.FAIL;
        taskStates.updateTask(taskStates.taskState(get(task.id()), TaskState.AWAITING_DECISION), event,
                Map.of("cycleId", ended.id(), "cycleOrdinal", ended.ordinal(), "cycleResult", result.name()));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("state", TaskState.AWAITING_DECISION.name());
        evidence.put("cycleId", ended.id());
        evidence.put("cycleOrdinal", ended.ordinal());
        evidence.put("cycleResult", result.name());
        evidence.put("checkpointState", checkpoint == null ? "NOT_CAPTURED" : checkpoint.state());
        if (code != null) evidence.put("code", code);
        events.emit(task.id(), "task.awaiting_decision", evidence);
        if (checkpoint != null && io.opencode.loopper.domain.WorkspaceCheckpointState.READY.name().equals(checkpoint.state())) {
            settleTerminalInPlaceLease(get(task.id()), true, "TASK_AWAITING_DECISION_CHECKPOINTED");
        }
    }
    public TaskExecutionCycleRow latestExecutionCycle(String taskId) {
        get(taskId);
        return executionCycles.latest(taskId);
    }
    public List<TaskExecutionCycleRow> executionCycles(String taskId) {
        get(taskId);
        return executionCycles.list(taskId);
    }
    public io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow latestWorkspaceCheckpoint(String taskId) {
        get(taskId);
        return workspaceCheckpoints.latest(taskId);
    }
    private TaskExecutionCycleRow requireActiveCycle(TaskRow task) {
        TaskExecutionCycleRow cycle = executionCycles.active(task.id());
        if (cycle == null) cycle = executionCycles.ensureInitial(task, cycleBudgetSnapshot(spec(task)));
        return cycle;
    }
    private String cycleBudgetSnapshot(LoopSpec spec) {
        return write(Map.of(
                "limits", spec.limits(),
                "budget", spec.budget() == null ? Map.of() : spec.budget(),
                "effectiveMaxStageAttempts", Math.min(spec.limits().maxStageAttempts(), defaults.getMaxStageAttempts()),
                "effectiveMaxTaskAttempts", Math.min(spec.limits().maxTaskAttempts(), defaults.getMaxTaskAttempts()),
                "effectiveMaxDurationSeconds", effectiveMaxDurationSeconds(spec)));
    }
    private void failTaskForManagedRuntime(String taskId, VerifierOutcome outcome) {
        TaskRow task = get(taskId);
        String code = String.valueOf(outcome.evidence().getOrDefault("code", "VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED"));
        failTask(task, code, outcome.summary(), null, null, null);
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
            taskStates.updateTask(taskStates.taskState(task, TaskState.PREPARING));
            task = get(taskId);
        }
        io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint = rollingPackages.applies(task.id())
                ? rollingPackages.resumeCheckpoint(task) : workspaceCheckpoints.latest(task.id());
        GitWorktreeManager.Worktree worktree;
        if (checkpoint != null && Set.of(WorkspaceCheckpointState.READY.name(),
                WorkspaceCheckpointState.RESTORED.name()).contains(checkpoint.state())) {
            workspaceCheckpoints.restore(task, checkpoint);
            worktree = new GitWorktreeManager.Worktree(root, task.branchName(), task.baselineCommit(), task.sourceBranch());
        } else {
            boolean gitSourceBranch = worktrees.inspect(root).isolatedWorktree();
            worktree = gitSourceBranch
                    ? worktrees.checkoutSourceBranch(root, task.id(), task.title(), task.baselineCommit())
                    : worktrees.create(root, task.id());
            var lineage = mapper.findTaskLineage(task.id()).orElse(null);
            if (lineage != null && ("INHERIT_CHANGES".equals(lineage.recoveryMode())
                    || "VERIFY_ONLY".equals(lineage.recoveryMode()))) {
                var parentCheckpoint = mapper.latestTaskWorkspaceCheckpoint(lineage.parentTaskId())
                        .filter(row -> WorkspaceCheckpointState.READY.name().equals(row.state())).orElse(null);
                if (parentCheckpoint == null && "INHERIT_CHANGES".equals(lineage.recoveryMode())) {
                    throw new TaskFailure("RECOVERY_CHECKPOINT_MISSING",
                            "Parent Task checkpoint is not available for the derived Task");
                }
                if (parentCheckpoint != null) {
                    worktrees.materializeCheckpointTree(root, worktree.branch(), parentCheckpoint.checkpointTree());
                    events.emit(task.id(), "recovery.checkpoint_seeded", Map.of("parentTaskId", lineage.parentTaskId(),
                            "checkpointTree", parentCheckpoint.checkpointTree()));
                }
            }
        }
        if (task.baselineCommit() != null && GitWorktreeManager.DIRECT_BRANCH.equals(worktree.branch()) && !rollingPackages.applies(task.id())) {
            throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires a Git source branch");
        }
        return persistPreparedTask(task.id(), worktree);
    }

    private TaskRow prepareAdmittedTaskAndContinue(String taskId) {
        try {
            TaskRow prepared = prepareAdmittedInPlaceTask(taskId);
            return startPreparedTask(prepared.id());
        } catch (TaskFailure failure) {
            if ("SOURCE_BRANCH_WORKSPACE_DIRTY".equals(failure.code())) {
                return waitForDirtyWorkspace(get(taskId), failure.getMessage());
            }
            failTask(get(taskId), failure.code(), failure.getMessage(), null, null, null);
            return get(taskId);
        }
    }

    private TaskRow persistPreparedTask(String taskId, GitWorktreeManager.Worktree worktree) {
        TaskRow task = get(taskId);
        TaskRow prepared = new TaskRow(task.id(), task.projectId(), task.loopDraftId(), task.title(), TaskState.READY.name(),
                worktree.path().toString(), worktree.branch(), worktree.sourceBranch(), worktree.baselineCommit(),
                task.createdAt(), now(), task.version(), task.taskProfileId(), task.rolePackId(), task.rolePackVersion(),
                task.executionMode(), task.workspacePolicy());
        lifecycle.transition(taskStates.subject(LifecycleMachineType.TASK, prepared.id(), prepared.id()), task.state(), prepared.state(),
                null, Map.of("workspaceMode", worktree.branch()), () -> mapper.prepareTask(prepared),
                () -> new ConflictException("TASK_VERSION_CONFLICT", "Task was updated concurrently"));
        TaskRow ready = get(taskId);
        events.emit(taskId, "task.ready", Map.of("state", ready.state(), "branch", worktree.branch(),
                "worktreePath", worktree.path().toString()));
        return ready;
    }

    private void settleTerminalInPlaceLease(TaskRow task, boolean writerTerminationConfirmed, String reason) {
        if (!isAdmittedInPlace(task)) { mapper.findTaskQueue(task.id()).filter(row -> TaskQueueState.QUEUED.name().equals(row.state())).ifPresent(row -> directLeases.cancelQueued(task.id())); return; }
        // The persisted Session/runtime evidence remains authoritative. The boolean is retained
        // at this call boundary so cancellation and cleanup cannot accidentally discard their
        // immediate termination result before that evidence has been written.
        if (!writerTerminationConfirmed && !writerTermination.hasUnconfirmedWriter(task.id())) {
            mapper.listSessions(task.id()).stream().filter(session -> session.externalSessionId() != null)
                    .findFirst().ifPresent(session -> directLeases.retainBlocked(
                            mapper.findTaskQueue(task.id()).orElseThrow().canonicalRoot(), task.id(), true, session.id(),
                            "SESSION_WRITER_UNCONFIRMED"));
        }
        continueAfterLeaseReconciliation(reconcileTerminalLease(task,
                WorkspaceLeaseReconciliationService.TRIGGER_AUTO, reason));
    }

    private WorkspaceLeaseReconciliationService.Result reconcileTerminalLease(
            TaskRow task, String trigger, String reason) {
        if (!isAdmittedInPlace(task)) {
            return new WorkspaceLeaseReconciliationService.Result(task.id(), false, true,
                    null, null, null);
        }
        return leaseReconciliation.reconcileHolder(task.id(), trigger, reason);
    }

    public void continueAfterLeaseReconciliation(WorkspaceLeaseReconciliationService.Result result) {
        if (result == null) return;
        if (result.released() || result.alreadySettled()) {
            rollingPackages.afterLeaseReconciliation(result.holderTaskId());
        }
        if (!result.released() || result.admittedNext() == null) return;
        String nextTaskId = result.admittedNext().taskId();
        events.emit(nextTaskId, "task.admitted", Map.of("state", TaskState.QUEUED.name(),
                "queuePosition", result.admittedNext().position()));
        prepareAdmittedTaskAndContinue(nextTaskId);
    }

    private void rehydrateDirectLeases() {
        for (DirectWorkspaceLeaseCoordinator.BlockingLease lease : directLeases.blockingLeases()) {
            if (lease.holderTaskId() == null) continue;
            TaskRow task = mapper.findTask(lease.holderTaskId()).orElse(null);
            if (task == null || !isAdmittedInPlace(task)) continue;
            if (TaskState.valueOf(task.state()).terminal()
                    || TaskState.AWAITING_DECISION.name().equals(task.state())) {
                rehydrateTerminalLease(task, lease);
                continue;
            }
            if (!lease.rootAvailable() || !lease.fingerprintMatches()) continue;
            if (TaskState.QUEUED.name().equals(task.state()) || TaskState.PREPARING.name().equals(task.state())) {
                prepareAdmittedTaskAndContinue(task.id());
            } else if (TaskState.READY.name().equals(task.state())) {
                startPreparedTask(task.id());
            }
        }
    }

    private void rehydrateTerminalLease(TaskRow task, DirectWorkspaceLeaseCoordinator.BlockingLease lease) {
        if (lease.writerSessionId() == null) {
            continueAfterLeaseReconciliation(reconcileTerminalLease(task,
                    WorkspaceLeaseReconciliationService.TRIGGER_RESTART, "RESTART_TERMINAL_TASK"));
            return;
        }
        ExecutionSessionRow writer = mapper.listSessions(task.id()).stream()
                .filter(session -> lease.writerSessionId().equals(session.id())).findFirst().orElse(null);
        if (writer == null) {
            continueAfterLeaseReconciliation(reconcileTerminalLease(task,
                    WorkspaceLeaseReconciliationService.TRIGGER_RESTART, "RESTART_WRITER_RECORD_MISSING"));
            return;
        }
        writerTermination.retryUnconfirmedSessions(task, "restart-terminal-lease-rehydrate");
        continueAfterLeaseReconciliation(reconcileTerminalLease(task,
                WorkspaceLeaseReconciliationService.TRIGGER_RESTART, "RESTART_WRITER_TERMINATION_RECHECKED"));
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
        return rollingPackages.latestSpec(task);
    }
    private void recordError(TaskRow task, StageRow stage, AttemptRow attempt, ExecutionSessionRow session, ErrorLayer layer,
                             String code, String message, boolean retryable, Map<String, ?> evidence) {
        mapper.insertError(new ErrorEventRow(UUID.randomUUID().toString(), task.id(), stage == null ? null : stage.id(),
                attempt == null ? null : attempt.id(), session == null ? null : session.id(), layer.name(), code,
                safeMessage(message), retryable, write(evidence), now()));
    }
    private String requireWorktree(TaskRow task) { if (task.worktreePath() == null || task.worktreePath().isBlank()) throw new TaskFailure("WORKTREE_MISSING", "Task has no prepared execution workspace"); return task.worktreePath(); }
    private String normalizedTitle(String title, String goal) { return title == null || title.isBlank() ? goal.substring(0, Math.min(goal.length(), 120)) : title.trim(); }
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
    private OpenCodeClient.OpenCodeModel judgeModel(LoopSpec spec, ModelResponseMode mode) {
        OpenCodeClient.OpenCodeModel selected = model(spec);
        return selected == null ? null
                : new OpenCodeClient.OpenCodeModel(selected.providerId(), selected.modelId(),
                mode == ModelResponseMode.JSON_SCHEMA ? Boolean.FALSE : selected.thinking());
    }
    private OpenCodeClient.OpenCodeModel judgeModel(LoopSpec spec, String persistedMode) {
        return judgeModel(spec, ModelResponseMode.JSON_SCHEMA.name().equals(persistedMode)
                ? ModelResponseMode.JSON_SCHEMA : ModelResponseMode.TEXT_MARKER);
    }
    private String now() { return Instant.now().toString(); }
    private Duration remainingTaskDuration(TaskRow task, LoopSpec spec) {
        Instant deadline;
        try {
            TaskExecutionCycleRow cycle = executionCycles.active(task.id());
            deadline = Instant.parse(cycle == null ? task.createdAt() : cycle.startedAt())
                    .plusSeconds(effectiveMaxDurationSeconds(spec));
        } catch (RuntimeException invalid) {
            throw new TaskFailure("TASK_DURATION_INVALID", "Task duration deadline cannot be evaluated safely");
        }
        Duration remaining = Duration.between(StoryAccountingClock.taskNow(mapper, task.id(), deadline.minusSeconds(effectiveMaxDurationSeconds(spec)).toString()), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new TaskFailure("TASK_DURATION_EXHAUSTED", "Task exceeded its maximum duration");
        }
        return remaining;
    }
    private Duration boundedVerifierTimeout(TaskRow task, LoopSpec spec) {
        Duration configured = Duration.ofSeconds(Math.min(spec.limits().verifierTimeoutSeconds(),
                defaults.getVerifierTimeout().toSeconds()));
        Duration remaining = remainingTaskDuration(task, spec);
        return configured.compareTo(remaining) <= 0 ? configured : remaining;
    }
    private long effectiveMaxDurationSeconds(LoopSpec spec) { return Math.min(spec.limits().maxDurationSeconds(), defaults.getMaxDuration().toSeconds()); }
    private long effectiveAttemptTimeoutSeconds(LoopSpec spec) { return Math.min(spec.limits().attemptTimeoutSeconds(), defaults.getAttemptTimeout().toSeconds()); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safeMessage(Throwable t) { return safeMessage(t.getMessage()); }
    private String safeMessage(String value) { return value == null ? "Unknown error" : value.substring(0, Math.min(value.length(), 4000)); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JacksonException e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, TypeReference<T> type) { try { return json.readValue(value, type); } catch (JacksonException e) { throw new TaskFailure("LOOPSPEC_INVALID", "Unable to parse stage verifier configuration"); } }
    private LoopSpec readSpec(LoopDraftRow row) {
        try { return json.readValue(row.specJson(), LoopSpec.class); }
        catch (JacksonException e) { throw new TaskFailure("LOOPSPEC_INVALID", "Unable to parse task LoopSpec"); }
    }
}
