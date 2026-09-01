package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.PackagePlanRevisionState;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.RollingPackagePlanAcceptedResultRow;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Owns proposed/approved revisions of only the not-yet-executed package suffix. */
@Service
public class RollingPackagePlanService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final RollingPackageService rolling;
    private final LifecycleTransitionService lifecycle;
    private final TaskWorkspaceCheckpointService checkpoints;
    private final WorkPackageRoleService roles;
    private final TaskEventService events;
    private final TransactionTemplate transactions;
    private final RollingPackageCommandPolicy commandPolicy;
    private final RollingPackagePlanCompilation compilation;
    private final RollingPackagePlanCompilationInputLoader compilationInputs;

    public RollingPackagePlanService(LoopperMapper mapper, ObjectMapper json, RollingPackageService rolling,
                                     LifecycleTransitionService lifecycle, TaskWorkspaceCheckpointService checkpoints,
                                     WorkPackageRoleService roles, TaskEventService events,
                                     RollingPackageCommandPolicy commandPolicy,
                                     RollingPackagePlanCompilation compilation,
                                     RollingPackagePlanCompilationInputLoader compilationInputs,
                                     PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.json = json;
        this.rolling = rolling;
        this.lifecycle = lifecycle;
        this.checkpoints = checkpoints;
        this.roles = roles;
        this.events = events;
        this.commandPolicy = commandPolicy;
        this.compilation = compilation;
        this.compilationInputs = compilationInputs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Proposal propose(String taskId, long expectedTaskVersion, List<PlanPackage> packages) {
        return propose(taskId, expectedTaskVersion, packages, false, "USER");
    }

    private Proposal propose(String taskId, long expectedTaskVersion, List<PlanPackage> packages,
                             boolean allowCompletedSuffix, String origin) {
        RollingPackageCommandPolicy.Command command = "CORRECTION".equals(origin)
                ? RollingPackageCommandPolicy.Command.ADD_CORRECTION
                : RollingPackageCommandPolicy.Command.REPLAN;
        Context context = safeContext(taskId, expectedTaskVersion, allowCompletedSuffix, command);
        RollingPackagePlanCompilation.Result compiled = compilePlan(taskId, packages);
        int revision = mapper.listTaskPackagePlanRevisions(taskId).stream()
                .mapToInt(TaskPackagePlanRevisionRow::revision).max().orElse(0) + 1;
        String now = now();
        TaskPackagePlanRevisionRow proposal = new TaskPackagePlanRevisionRow(UUID.randomUUID().toString(), taskId,
                context.session().id(), context.active().requirementRevisionId(), revision,
                PackagePlanRevisionState.PROPOSED.name(), origin, compiled.canonicalPlanJson(),
                compiled.canonicalImpactJson(),
                null, null, null, null, context.checkpoint().id(), context.task().version(),
                context.current().id(), context.current().version(), now, now, null, null, 0);
        lifecycle.create(planSubject(proposal), proposal.state(), Map.of("origin", origin, "revision", revision),
                () -> mapper.insertTaskPackagePlanRevision(proposal),
                () -> new ConflictException("PACKAGE_PLAN_PROPOSAL_CONFLICT", "剩余拆包提案被并发创建"));
        events.emit(taskId, "package.plan_proposed", Map.of("revision", revision, "impact", compiled.impact()));
        return proposal(proposal);
    }

    public Proposal correction(String taskId, long expectedTaskVersion, String correctionOfPackageRunId,
                               String title, String objective) {
        Context context = safeContext(taskId, expectedTaskVersion, true,
                RollingPackageCommandPolicy.Command.ADD_CORRECTION);
        TaskPackageRunRow frozen = mapper.findTaskPackageRun(correctionOfPackageRunId).orElseThrow(() ->
                new NotFoundException("Package run not found: " + correctionOfPackageRunId));
        if (!taskId.equals(frozen.taskId()) || !TaskPackageRunState.FACT_FROZEN.name().equals(frozen.state())) {
            throw new ConflictException("CORRECTION_FACT_REQUIRED", "修正包只能引用已冻结事实包");
        }
        int correctionNumber = (int) mapper.listTaskPackageRuns(taskId).stream()
                .filter(run -> run.correctionOfPackageRunId() != null).count() + 1;
        List<PlanPackage> packages = new ArrayList<>();
        packages.add(new PlanPackage("FIX-" + correctionNumber,
                title == null || title.isBlank() ? "修正 " + frozen.title() : title.strip(),
                objective == null || objective.isBlank() ? "修正已冻结工作包 " + frozen.packageKey() : objective.strip(),
                null, List.of(), frozen.id(), List.of(frozen.packageKey()), List.of()));
        mapper.listTaskPackageRuns(taskId).stream()
                .filter(run -> context.active().id().equals(run.planRevisionId()))
                .filter(run -> Set.of(TaskPackageRunState.PLANNED.name(), TaskPackageRunState.DESIGNING.name(),
                        TaskPackageRunState.DESIGN_REVIEW.name(), TaskPackageRunState.EXECUTION_READY.name(),
                        TaskPackageRunState.WAITING_INPUT.name()).contains(run.state()))
                .forEach(run -> packages.add(new PlanPackage(run.packageKey(), run.title(),
                        mapper.findDesignWorkPackage(run.designWorkPackageId()).map(DesignWorkPackageRow::objective)
                                .orElse(run.title()), run.id(), List.of(run.id()), null,
                        dependencies(run), List.of())));
        return propose(taskId, expectedTaskVersion, packages, true, "CORRECTION");
    }

    public Proposal confirm(String taskId, String proposalId, long expectedTaskVersion,
                            long expectedProposalVersion) {
        TaskPackagePlanRevisionRow proposed = mapper.findTaskPackagePlanRevision(proposalId).orElseThrow(() ->
                new NotFoundException("Package plan proposal not found: " + proposalId));
        if (!taskId.equals(proposed.taskId()) || proposed.version() != expectedProposalVersion
                || !PackagePlanRevisionState.PROPOSED.name().equals(proposed.state())) {
            throw new ConflictException("PACKAGE_PLAN_PROPOSAL_STALE", "剩余拆包提案已更新，请刷新后重试");
        }
        RollingPackageCommandPolicy.Command command = "CORRECTION".equals(proposed.origin())
                ? RollingPackageCommandPolicy.Command.ADD_CORRECTION
                : RollingPackageCommandPolicy.Command.REPLAN;
        Context context = safeContext(taskId, expectedTaskVersion, true, command);
        List<PlanPackage> packages = readPackages(proposed.planJson());
        DispatchAnchor dispatch = transactions.execute(ignored -> {
            String now = now();
            TaskPackagePlanRevisionRow old = mapper.activeTaskPackagePlanRevision(taskId).orElseThrow();
            TaskPackagePlanRevisionRow superseded = copy(old, PackagePlanRevisionState.SUPERSEDED,
                    old.planJson(), old.impactJson(), old.externalSessionId(), old.externalSessionState(),
                    old.lastErrorCode(), old.lastErrorDetail(), now, old.approvedAt(), now);
            lifecycle.transition(planSubject(old), old.state(), superseded.state(), LifecycleEvent.SUPERSEDE,
                    null, Map.of("replacementRevision", proposed.revision()),
                    () -> mapper.updateTaskPackagePlanRevision(superseded),
                    () -> new ConflictException("PACKAGE_PLAN_VERSION_CONFLICT", "当前拆包计划已更新"));
            for (TaskPackageRunRow run : mapper.listTaskPackageRuns(taskId)) {
                if (old.id().equals(run.planRevisionId()) && !TaskPackageRunState.valueOf(run.state()).terminal()) {
                    supersedeDesign(context, run, now);
                    rolling.supersedeRun(run);
                }
            }
            TaskPackagePlanRevisionRow active = copy(proposed, PackagePlanRevisionState.ACTIVE,
                    proposed.planJson(), proposed.impactJson(), proposed.externalSessionId(),
                    proposed.externalSessionState(), null, null, now, now, null);
            lifecycle.transition(planSubject(proposed), proposed.state(), active.state(),
                    LifecycleEvent.APPROVE_PACKAGE_REPLAN, null, Map.of("revision", proposed.revision()),
                    () -> mapper.updateTaskPackagePlanRevision(active),
                    () -> new ConflictException("PACKAGE_PLAN_PROPOSAL_STALE", "剩余拆包提案已更新"));
            String first = null;
            String resumeCheckpointId = mapper.listPackageFactSnapshots(taskId).stream()
                    .reduce((left, right) -> right).map(io.opencode.loopper.persistence.PackageFactSnapshotRow::checkpointId)
                    .orElseThrow();
            for (int ordinal = 0; ordinal < packages.size(); ordinal++) {
                PlanPackage item = packages.get(ordinal);
                DesignWorkPackageRow source = source(context, item);
                DesignWorkPackageRow design = cloneDesign(context.session(), proposed, item, source, ordinal, now);
                lifecycle.create(new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_WORK_PACKAGE,
                                design.id(), LifecycleScopeType.PROJECT, context.session().projectId()), design.state(),
                        Map.of("packageId", design.packageId(), "planRevision", proposed.revision()),
                        () -> mapper.insertDesignWorkPackage(design),
                        () -> new ConflictException("PACKAGE_PLAN_DESIGN_CREATE_CONFLICT", "新计划工作包无法保存"));
                roles.assign(design);
                TaskPackageRunRow run = new TaskPackageRunRow(UUID.randomUUID().toString(), taskId, proposed.id(),
                        design.id(), item.packageKey(), ordinal, item.title(), TaskPackageRunState.PLANNED.name(),
                        item.correctionOfPackageRunId(), 0, 0, null, null, now, now, 0);
                run = new TaskPackageRunRow(run.id(), run.taskId(), run.planRevisionId(), run.designWorkPackageId(),
                        run.packageKey(), run.ordinal(), run.title(), run.state(), run.correctionOfPackageRunId(),
                        run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(),
                        run.waitingReasonCode(), run.createdAt(), run.updatedAt(), run.version(), resumeCheckpointId);
                TaskPackageRunRow createdRun = run;
                lifecycle.create(packageSubject(createdRun), createdRun.state(),
                        Map.of("packageKey", createdRun.packageKey(), "planRevision", proposed.revision()),
                        () -> mapper.insertTaskPackageRun(createdRun),
                        () -> new ConflictException("PACKAGE_PLAN_RUN_CREATE_CONFLICT", "新计划工作包运行记录无法保存"));
                if (first == null) first = run.id();
            }
            if (first == null) throw new ConflictException("PACKAGE_PLAN_EMPTY", "新计划没有可设计的工作包");
            rolling.beginPlannedDesignInTransaction(taskId, first, command);
            return new DispatchAnchor(first, resumeCheckpointId);
        });
        if (dispatch == null) throw new ConflictException("PACKAGE_PLAN_EMPTY", "新计划没有可设计的工作包");
        rolling.dispatchPlannedDesign(taskId, dispatch.packageRunId(), dispatch.checkpointId());
        events.emit(taskId, "package.plan_approved", Map.of("revision", proposed.revision(),
                "packageCount", packages.size()));
        return proposal(mapper.findTaskPackagePlanRevision(proposalId).orElseThrow());
    }

    public List<Proposal> proposals(String taskId) {
        mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        return mapper.listTaskPackagePlanRevisions(taskId).stream().map(this::proposal).toList();
    }

    Context safeContext(String taskId, long expectedTaskVersion, boolean allowCompletedSuffix,
                        RollingPackageCommandPolicy.Command command) {
        var task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        if (!rolling.rolling(taskId) || task.version() != expectedTaskVersion) {
            throw new ConflictException("PACKAGE_PLAN_TASK_VERSION_CONFLICT", "任务状态已更新，请刷新后重试");
        }
        DesignerSessionRow session = mapper.findDesignerSessionByTask(taskId).orElseThrow();
        var fact = mapper.listPackageFactSnapshots(taskId).stream().reduce((left, right) -> right).orElse(null);
        var checkpoint = fact == null ? null : mapper.findTaskWorkspaceCheckpoint(fact.checkpointId()).orElse(null);
        TaskPackageRunRow current = mapper.currentTaskPackageRun(taskId).orElse(null);
        boolean replannable = current != null && Set.of(TaskPackageRunState.PLANNED.name(),
                TaskPackageRunState.DESIGNING.name(), TaskPackageRunState.DESIGN_REVIEW.name(),
                TaskPackageRunState.EXECUTION_READY.name(), TaskPackageRunState.WAITING_INPUT.name())
                .contains(current.state());
        RollingPackageCommandPolicy.Context commandContext = rolling.policyContext(task, current);
        commandPolicy.require(command, commandContext);
        if (!commandContext.designerFree() || !commandContext.writerFree() || !commandContext.safeCheckpoint()
                || checkpoint == null || !Set.of("READY", "RESTORED").contains(checkpoint.state())
                || (!replannable && !allowCompletedSuffix)) {
            throw new ConflictException("PACKAGE_COMMAND_NOT_AVAILABLE", "只有 writer 已停止且当前成功事实点可验证时才能调整剩余拆包");
        }
        checkpoints.designSnapshot(task, checkpoint);
        TaskPackagePlanRevisionRow active = mapper.activeTaskPackagePlanRevision(taskId).orElseThrow();
        return new Context(task, session, active, checkpoint, current);
    }

    SuggestionAnchor beginSuggestion(String taskId, long expectedTaskVersion,
                                     String expectedPackageRunId, long expectedPackageVersion) {
        Context context = safeContext(taskId, expectedTaskVersion, false,
                RollingPackageCommandPolicy.Command.REPLAN);
        if (context.current() == null || !context.current().id().equals(expectedPackageRunId)
                || context.current().version() != expectedPackageVersion) {
            throw new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE", "当前工作包已更新，请刷新后重试");
        }
        if (mapper.listGeneratingTaskPackagePlanRevisions().stream().anyMatch(row -> taskId.equals(row.taskId()))) {
            throw new ConflictException("PACKAGE_PLAN_SUGGESTION_RUNNING", "已有剩余拆包建议正在生成");
        }
        int revision = mapper.listTaskPackagePlanRevisions(taskId).stream()
                .mapToInt(TaskPackagePlanRevisionRow::revision).max().orElse(0) + 1;
        String now = now();
        TaskPackagePlanRevisionRow row = new TaskPackagePlanRevisionRow(UUID.randomUUID().toString(), taskId,
                context.session().id(), context.active().requirementRevisionId(), revision,
                PackagePlanRevisionState.GENERATING.name(), "AI", "[]", "{}", null, "PENDING",
                null, null, context.checkpoint().id(), context.task().version(), context.current().id(),
                context.current().version(), now, now, null, null, 0);
        lifecycle.create(planSubject(row), row.state(), Map.of("origin", "AI", "revision", revision),
                () -> mapper.insertTaskPackagePlanRevision(row),
                () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_CONFLICT", "AI 拆包建议被并发创建"));
        events.emit(taskId, "package.plan_suggestion_started", Map.of("revision", revision));
        return new SuggestionAnchor(mapper.findTaskPackagePlanRevision(row.id()).orElse(row), context);
    }

    TaskPackagePlanRevisionRow attachSuggestionSession(TaskPackagePlanRevisionRow input, String sessionId,
                                                       String sessionState) {
        TaskPackagePlanRevisionRow updated = copy(input, PackagePlanRevisionState.GENERATING,
                input.planJson(), input.impactJson(), sessionId, sessionState, null, null,
                now(), null, null);
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPackagePlanRevision(updated),
                () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE", "AI 拆包建议已被并发更新"));
        return mapper.findTaskPackagePlanRevision(input.id()).orElse(updated);
    }

    TaskPackagePlanRevisionRow updateSuggestionState(TaskPackagePlanRevisionRow input, String sessionState) {
        TaskPackagePlanRevisionRow updated = copy(input, PackagePlanRevisionState.GENERATING,
                input.planJson(), input.impactJson(), input.externalSessionId(), sessionState,
                input.lastErrorCode(), input.lastErrorDetail(), now(), null, null);
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPackagePlanRevision(updated),
                () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE", "AI 拆包建议已被并发更新"));
        return mapper.findTaskPackagePlanRevision(input.id()).orElse(updated);
    }

    public Proposal completeSuggestion(TaskPackagePlanRevisionRow input, List<PlanPackage> packages) {
        suggestionContext(input);
        RollingPackagePlanCompilation.Result compiled = compilePlan(input.taskId(), packages);
        TaskPackagePlanRevisionRow proposed = copy(input, PackagePlanRevisionState.PROPOSED,
                compiled.canonicalPlanJson(), compiled.canonicalImpactJson(),
                input.externalSessionId(), "COMPLETED", null, null,
                now(), null, null);
        lifecycle.transition(planSubject(input), input.state(), proposed.state(),
                LifecycleEvent.COMPLETE_PACKAGE_REPLAN, null, Map.of("revision", input.revision()),
                () -> mapper.updateTaskPackagePlanRevision(proposed),
                () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE", "AI 拆包建议已被并发更新"));
        events.emit(input.taskId(), "package.plan_proposed", Map.of(
                "revision", input.revision(), "impact", compiled.impact(), "origin", "AI"));
        return proposal(mapper.findTaskPackagePlanRevision(input.id()).orElse(proposed));
    }

    public Proposal completeCandidateSuggestion(TaskPackagePlanRevisionRow input,
                                                RollingPackagePlanAcceptedResultRow accepted,
                                                String terminationProof) {
        if (!CandidateSessionTerminationProof.persisted(terminationProof)) {
            throw new ConflictException("ROLLING_PACKAGE_CANDIDATE_STOP_UNCONFIRMED",
                    "Rolling package candidate remote Session has no positive termination proof");
        }
        AtomicBoolean changed = new AtomicBoolean();
        TaskPackagePlanRevisionRow settled = transactions.execute(ignored -> {
            TaskPackagePlanRevisionRow current = mapper.findTaskPackagePlanRevision(input.id()).orElseThrow();
            RollingPackagePlanAcceptedResultRow result = mapper
                    .findRollingPackagePlanAcceptedResult(accepted.candidateRunId()).orElseThrow(() ->
                            new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_MISSING",
                                    "Accepted rolling package result no longer exists"));
            if (PackagePlanRevisionState.PROPOSED.name().equals(current.state())
                    && current.id().equals(result.settledPlanRevisionId())) return current;
            if (!PackagePlanRevisionState.GENERATING.name().equals(current.state())
                    || result.settledPlanRevisionId() != null
                    || !current.id().equals(result.taskPackagePlanRevisionId())
                    || result.sourceRevision() != current.revision()) {
                throw new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_STALE",
                        "Accepted rolling package result no longer matches its generating owner");
            }
            var run = mapper.findCandidateSubmissionRun(result.candidateRunId()).orElseThrow();
            if (!"ACCEPTED".equals(run.state()) || !"ROLLING_PACKAGE_PLAN_V1".equals(run.candidateKind())
                    || !"TASK_PACKAGE_PLAN_REVISION".equals(run.ownerType())
                    || !current.id().equals(run.ownerId()) || !current.taskId().equals(run.taskId())
                    || run.sourceRevision() != result.sourceRevision()
                    || run.ownerVersion() != result.ownerVersion()
                    || !java.util.Objects.equals(current.externalSessionId(), run.externalSessionId())) {
                throw new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_STALE",
                        "Accepted rolling package run no longer matches its frozen owner");
            }
            suggestionContext(current);
            RollingPackagePlanCompilation.Result compiled = compilation.compileCandidate(
                    compilationInputs.loadTask(current.taskId()), result.canonicalCandidateJson());
            if (!compiled.accepted()
                    || !result.canonicalCandidateJson().equals(compiled.canonicalCandidateJson())
                    || !result.canonicalPlanJson().equals(compiled.canonicalPlanJson())
                    || !result.impactJson().equals(compiled.canonicalImpactJson())) {
                throw new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_INVALID",
                        "Accepted rolling package result no longer compiles from frozen task facts");
            }
            String now = now();
            TaskPackagePlanRevisionRow proposed = copy(current, PackagePlanRevisionState.PROPOSED,
                    result.canonicalPlanJson(), result.impactJson(), current.externalSessionId(),
                    terminationProof, null, null, now, null, null);
            lifecycle.transition(planSubject(current), current.state(), proposed.state(),
                    LifecycleEvent.COMPLETE_PACKAGE_REPLAN, null, Map.of("revision", current.revision()),
                    () -> mapper.updateTaskPackagePlanRevision(proposed),
                    () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE",
                            "AI 拆包建议已被并发更新"));
            if (mapper.settleRollingPackagePlanAcceptedResult(result.candidateRunId(), result.version(),
                    current.id(), now) != 1) {
                throw new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_CONFLICT",
                        "Accepted rolling package result could not be settled");
            }
            changed.set(true);
            return mapper.findTaskPackagePlanRevision(current.id()).orElse(proposed);
        });
        if (settled == null) throw new ConflictException(
                "ROLLING_PACKAGE_ACCEPTED_RESULT_CONFLICT", "Accepted rolling package result was not settled");
        if (changed.get()) {
            events.emit(settled.taskId(), "package.plan_proposed", Map.of(
                    "revision", settled.revision(), "origin", "AI", "candidate", true));
        }
        return proposal(settled);
    }

    public Proposal failSuggestion(TaskPackagePlanRevisionRow input, String code, String detail) {
        return failSuggestion(input, code, detail, "FAILED");
    }

    public Proposal failSuggestion(TaskPackagePlanRevisionRow input, String code, String detail,
                                   String externalSessionState) {
        TaskPackagePlanRevisionRow current = mapper.findTaskPackagePlanRevision(input.id()).orElse(input);
        if (!PackagePlanRevisionState.GENERATING.name().equals(current.state())) return proposal(current);
        TaskPackagePlanRevisionRow failed = copy(current, PackagePlanRevisionState.FAILED,
                current.planJson(), current.impactJson(), current.externalSessionId(), externalSessionState, code,
                bounded(detail), now(), null, null);
        lifecycle.transition(planSubject(current), current.state(), failed.state(),
                LifecycleEvent.FAIL_PACKAGE_REPLAN, code, Map.of("revision", current.revision()),
                () -> mapper.updateTaskPackagePlanRevision(failed),
                () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE", "AI 拆包建议已被并发更新"));
        events.emit(input.taskId(), "package.plan_suggestion_failed", Map.of("revision", input.revision(), "code", code));
        return proposal(mapper.findTaskPackagePlanRevision(input.id()).orElse(failed));
    }

    public TaskPackagePlanRevisionRow disconnectSuggestion(TaskPackagePlanRevisionRow input,
                                                           String code, String detail) {
        TaskPackagePlanRevisionRow current = mapper.findTaskPackagePlanRevision(input.id()).orElse(input);
        if (!PackagePlanRevisionState.GENERATING.name().equals(current.state())) return current;
        TaskPackagePlanRevisionRow disconnected = copy(current, PackagePlanRevisionState.GENERATING,
                current.planJson(), current.impactJson(), current.externalSessionId(), "DISCONNECTED", code,
                bounded(detail), now(), null, null);
        lifecycle.mutateWithoutTransition(() -> mapper.updateTaskPackagePlanRevision(disconnected),
                () -> new ConflictException("PACKAGE_PLAN_SUGGESTION_STALE", "AI 拆包建议已被并发更新"));
        return mapper.findTaskPackagePlanRevision(input.id()).orElse(disconnected);
    }

    private Context suggestionContext(TaskPackagePlanRevisionRow row) {
        var task = mapper.findTask(row.taskId()).orElseThrow();
        var run = mapper.findTaskPackageRun(row.basePackageRunId()).orElseThrow();
        var checkpoint = mapper.findTaskWorkspaceCheckpoint(row.baseCheckpointId()).orElseThrow();
        if (task.version() != row.baseTaskVersion() || run.version() != row.basePackageVersion()
                || !Set.of("READY", "RESTORED").contains(checkpoint.state())) {
            throw new ConflictException("PACKAGE_PLAN_SUGGESTION_BASE_CHANGED", "AI 建议期间任务、工作包或事实点已变化");
        }
        checkpoints.designSnapshot(task, checkpoint);
        var session = mapper.findDesignerSession(row.designerSessionId()).orElseThrow();
        var active = mapper.activeTaskPackagePlanRevision(row.taskId()).orElseThrow();
        return new Context(task, session, active, checkpoint, run);
    }

    private void supersedeDesign(Context context, TaskPackageRunRow run, String now) {
        DesignWorkPackageRow row = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow();
        DesignWorkPackageRow superseded = new DesignWorkPackageRow(row.id(), row.designerSessionId(),
                row.requirementRevisionId(), row.decompositionId(), row.packageId(), row.ordinal(), row.title(),
                row.objective(), row.scopeInJson(), row.scopeOutJson(), row.dependenciesJson(), row.deliverablesJson(),
                row.acceptanceIntentJson(), row.requirementRefsJson(), DesignWorkPackageState.SUPERSEDED.name(),
                row.designerExternalSessionId(), row.designerExternalSessionState(), row.designMessageId(),
                row.designRevision(), row.redesignCount(), row.designerTransportRetryCount(), row.compilerSummary(),
                row.handoffSummary(), row.lastErrorCode(), row.lastErrorDetail(), row.approvedDesignRevision(),
                row.discussionRoundCount(), row.invalidatedByPackageId(), row.approvedAt(), row.createdAt(), now,
                row.version(), row.planRevision(), row.correctionOfPackageId(), now);
        var subject = new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_WORK_PACKAGE, row.id(),
                LifecycleScopeType.PROJECT, context.session().projectId());
        lifecycle.transition(subject, row.state(), superseded.state(), LifecycleEvent.SUPERSEDE_PACKAGE, null,
                Map.of("packageId", row.packageId(), "planRevision", row.planRevision()),
                () -> mapper.updateDesignWorkPackage(superseded),
                () -> new ConflictException("PACKAGE_PLAN_DESIGN_CONFLICT", "未执行工作包已被并发更新"));
    }

    private List<String> dependencies(TaskPackageRunRow run) {
        return mapper.findDesignWorkPackage(run.designWorkPackageId())
                .map(DesignWorkPackageRow::dependenciesJson).map(this::readStrings).orElse(List.of());
    }

    private List<String> sourceIds(PlanPackage item) {
        List<String> result = new ArrayList<>();
        if (item.sourcePackageRunIds() != null) result.addAll(item.sourcePackageRunIds());
        if (item.sourcePackageRunId() != null && !result.contains(item.sourcePackageRunId())) {
            result.add(item.sourcePackageRunId());
        }
        return List.copyOf(result);
    }

    private DesignWorkPackageRow source(Context context, PlanPackage item) {
        List<String> sourceIds = sourceIds(item);
        if (!sourceIds.isEmpty()) {
            for (String sourceId : sourceIds) {
                TaskPackageRunRow candidate = mapper.findTaskPackageRun(sourceId).orElseThrow(() ->
                        new BadRequestException("PACKAGE_PLAN_SOURCE_MISSING", "重规划引用的工作包不存在"));
                if (!context.task().id().equals(candidate.taskId())) throw new BadRequestException(
                        "PACKAGE_PLAN_SOURCE_MISMATCH", "重规划引用了其他任务的工作包");
            }
            TaskPackageRunRow run = mapper.findTaskPackageRun(sourceIds.getFirst()).orElseThrow(() ->
                    new BadRequestException("PACKAGE_PLAN_SOURCE_MISSING", "重规划引用的工作包不存在"));
            return mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow();
        }
        return mapper.listTaskPackageRuns(context.task().id()).stream()
                .map(run -> mapper.findDesignWorkPackage(run.designWorkPackageId()).orElse(null))
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow(() ->
                        new ConflictException("PACKAGE_PLAN_TEMPLATE_MISSING", "无法构造新工作包设计来源"));
    }

    private DesignWorkPackageRow cloneDesign(DesignerSessionRow session, TaskPackagePlanRevisionRow plan,
                                              PlanPackage item, DesignWorkPackageRow source, int ordinal, String now) {
        return new DesignWorkPackageRow(UUID.randomUUID().toString(), session.id(),
                plan.requirementRevisionId(), source.decompositionId(), item.packageKey(), ordinal, item.title(),
                item.objective(), source.scopeInJson(), source.scopeOutJson(), write(item.dependencies()),
                sourceIds(item).isEmpty() ? "[]" : source.deliverablesJson(),
                sourceIds(item).isEmpty() ? "[]" : source.acceptanceIntentJson(),
                write(item.requirementRefs()), DesignWorkPackageState.PENDING.name(), null, "PENDING", null,
                0, 0, 0, null, null, null, null, null, 0, null, null, now, now, 0,
                plan.revision(), item.correctionOfPackageRunId(), null);
    }

    private List<PlanPackage> readPackages(String value) {
        try { return List.copyOf(json.readValue(value, new TypeReference<List<PlanPackage>>() { })); }
        catch (JacksonException failure) { throw new ConflictException("PACKAGE_PLAN_JSON_INVALID", "拆包提案无法读取"); }
    }

    private RollingPackagePlanCompilation.Result compilePlan(String taskId, List<PlanPackage> packages) {
        RollingPackagePlanCompilation.Result result = compilation.compilePlan(
                compilationInputs.loadTask(taskId), packages);
        if (result.accepted()) return result;
        RollingPackagePlanCompilation.Problem problem = result.problems().isEmpty() ? null : result.problems().getFirst();
        throw new BadRequestException(problem == null ? "PACKAGE_PLAN_INVALID" : problem.code(),
                problem == null ? "剩余拆包提案无法编译" : problem.staticDetail());
    }

    private List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.copyOf(json.readValue(value, new TypeReference<ArrayList<String>>() { })); }
        catch (JacksonException failure) { throw new ConflictException("PACKAGE_PLAN_JSON_INVALID", "拆包提案无法读取"); }
    }

    private Proposal proposal(TaskPackagePlanRevisionRow row) {
        return new Proposal(row.id(), row.revision(), row.state(), row.version(), row.planJson(), row.impactJson(),
                row.origin(), row.externalSessionState(), row.lastErrorCode(), row.lastErrorDetail(),
                row.createdAt(), row.updatedAt(), row.approvedAt());
    }

    private TaskPackagePlanRevisionRow copy(TaskPackagePlanRevisionRow row, PackagePlanRevisionState state,
                                            String planJson, String impactJson, String externalSessionId,
                                            String externalSessionState, String errorCode, String errorDetail,
                                            String updatedAt, String approvedAt, String supersededAt) {
        return new TaskPackagePlanRevisionRow(row.id(), row.taskId(), row.designerSessionId(),
                row.requirementRevisionId(), row.revision(), state.name(), row.origin(), planJson, impactJson,
                externalSessionId, externalSessionState, errorCode, errorDetail, row.baseCheckpointId(),
                row.baseTaskVersion(), row.basePackageRunId(), row.basePackageVersion(), row.createdAt(),
                updatedAt, approvedAt, supersededAt, row.version());
    }

    private LifecycleTransitionService.Subject planSubject(TaskPackagePlanRevisionRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PACKAGE_PLAN_REVISION, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private LifecycleTransitionService.Subject packageSubject(TaskPackageRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_PACKAGE_RUN, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private String bounded(String detail) {
        if (detail == null || detail.isBlank()) return "AI 拆包建议生成失败";
        String stripped = detail.strip();
        return stripped.substring(0, Math.min(stripped.length(), 2_000));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException(failure); }
    }
    private String now() { return Instant.now().toString(); }

    public record PlanPackage(String packageKey, String title, String objective, String sourcePackageRunId,
                              List<String> sourcePackageRunIds, String correctionOfPackageRunId, List<String> dependencies,
                              List<String> requirementRefs) { }
    public record Proposal(String id, int revision, String state, long version, String planJson,
                           String impactJson, String origin, String externalSessionState,
                           String lastErrorCode, String lastErrorDetail, String createdAt,
                           String updatedAt, String approvedAt) { }
    record Context(io.opencode.loopper.persistence.TaskRow task, DesignerSessionRow session,
                   TaskPackagePlanRevisionRow active,
                   io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow checkpoint,
                   TaskPackageRunRow current) { }
    record SuggestionAnchor(TaskPackagePlanRevisionRow revision, Context context) { }
    private record DispatchAnchor(String packageRunId, String checkpointId) { }
}
