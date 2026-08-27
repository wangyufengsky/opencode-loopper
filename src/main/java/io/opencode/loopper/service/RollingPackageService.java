package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.PackagePlanRevisionState;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskExecutionMode;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.TaskWorkspacePolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.PackageFactSnapshotRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskSpecRevisionRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.persistence.WorkPackageRoleProfileRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.file.Path;
import java.util.Set;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the append-only rolling-package aggregate. It deliberately does not own
 * OpenCode execution; TaskService delegates only package boundaries here.
 */
@Service
public class RollingPackageService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final RollingPackageCodec codec;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final LoopperProperties properties;
    private final TaskWorkspaceCheckpointService checkpoints;
    private final RollingPackageCheckpointService checkpointSaga;
    private final TaskEventService events;
    private final ObjectProvider<DesignerSessionService> designers;
    private final TransactionTemplate transactions;
    private final RollingPackageCommandPolicy commandPolicy;
    private final RollingPackageCommandContextService commandContexts;
    public RollingPackageService(LoopperMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json,
                                 ProjectService projects, GitWorktreeManager worktrees,
                                 LoopperProperties properties,
                                 TaskWorkspaceCheckpointService checkpoints,
                                 RollingPackageCheckpointService checkpointSaga, TaskEventService events,
                                 ObjectProvider<DesignerSessionService> designers,
                                 RollingPackageCommandPolicy commandPolicy,
                                 RollingPackageCommandContextService commandContexts,
                                 PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.codec = new RollingPackageCodec(json);
        this.projects = projects;
        this.worktrees = worktrees;
        this.properties = properties;
        this.checkpoints = checkpoints;
        this.checkpointSaga = checkpointSaga;
        this.events = events;
        this.designers = designers;
        this.commandPolicy = commandPolicy;
        this.commandContexts = commandContexts;
        this.transactions = new TransactionTemplate(transactionManager);
    }
    public boolean eligible(String designerSessionId) {
        if (!properties.isRollingPackagesEnabled()) return false;
        var profile = mapper.findCurrentDesignerTaskProfile(designerSessionId).orElse(null);
        return profile != null
                && "FROZEN".equals(profile.state())
                && TaskIntent.SOFTWARE_CHANGE.name().equals(profile.intent())
                && WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(profile.workflowTemplate());
    }

    /** Resolves the only repository view a rolling package Designer/Compiler may read. */
    public ProjectRow designProject(DesignerSessionRow session) {
        ProjectRow registered = projects.get(session.projectId());
        if (session.taskId() == null || !eligible(session.id())) return registered;
        TaskRow task = mapper.findTask(session.taskId()).orElseThrow(() ->
                new ConflictException("ROLLING_TASK_MISSING", "滚动设计绑定的任务不存在"));
        PackageFactSnapshotRow fact = mapper.listPackageFactSnapshots(task.id()).stream()
                .reduce((left, right) -> right).orElseThrow(() ->
                        new ConflictException("PACKAGE_FACT_REQUIRED", "下一工作包设计缺少上一成功事实快照"));
        TaskWorkspaceCheckpointRow checkpoint = mapper.findTaskWorkspaceCheckpoint(fact.checkpointId())
                .orElseThrow(() -> new ConflictException("PACKAGE_CHECKPOINT_MISSING", "工作包事实引用的快照不存在"));
        Path root = checkpoints.designSnapshot(task, checkpoint);
        return new ProjectRow(registered.id(), registered.name(), root.toString(), registered.description(),
                registered.createdAt(), registered.updatedAt(), registered.managed(), registered.version());
    }

    /** Package approval is idempotent: package 1 creates the Task, later packages append its contract. */
    public TaskRow approvePackage(DesignerSessionRow session, DesignWorkPackageRow workPackage,
                                  LoopSpecCompilationRow compilation) {
        if (!eligible(session.id())) return null;
        DesignerSessionRow current = mapper.findDesignerSession(session.id()).orElseThrow();
        if (current.taskId() == null) return createTask(current, workPackage, compilation);
        TaskRow task = mapper.findTask(current.taskId())
                .orElseThrow(() -> new ConflictException("ROLLING_TASK_MISSING", "滚动设计绑定的任务不存在"));
        TaskPackageRunRow run = runForPackage(task.id(), workPackage.id());
        if (TaskPackageRunState.EXECUTION_READY.name().equals(run.state())) return task;
        if (!TaskPackageRunState.DESIGN_REVIEW.name().equals(run.state())) {
            throw new ConflictException("PACKAGE_DESIGN_NOT_REVIEWABLE", "当前工作包不在设计确认状态");
        }
        List<LoopSpec.StageSpec> packageStages = codec.compiledStages(compilation);
        transactions.executeWithoutResult(ignored -> {
            TaskSpecRevisionRow latest = mapper.latestTaskSpecRevision(task.id()).orElseThrow();
            LoopSpec prior = acceptedSpecBase(task);
            List<LoopSpec.StageSpec> cumulative = new ArrayList<>(prior.stages());
            cumulative.addAll(packageStages);
            LoopSpec next = codec.copyWithStages(prior, cumulative);
            appendSpecAndStages(task, run, workPackage, next, packageStages, latest.revision() + 1);
            updateRun(run, TaskPackageRunState.EXECUTION_READY, LifecycleEvent.APPROVE_PACKAGE_DESIGN,
                    run.discussionRevision(), workPackage.designRevision(), workPackage.designRevision(), null);
        });
        events.emit(task.id(), "package.design_approved", Map.of("packageKey", run.packageKey(),
                "taskSpecRevision", mapper.latestTaskSpecRevision(task.id()).orElseThrow().revision()));
        return mapper.findTask(task.id()).orElse(task);
    }

    private TaskRow createTask(DesignerSessionRow session, DesignWorkPackageRow first,
                               LoopSpecCompilationRow compilation) {
        if (first.ordinal() != 0) {
            throw new ConflictException("ROLLING_FIRST_PACKAGE_REQUIRED", "滚动任务必须先确认第一个工作包");
        }
        var project = projects.get(session.projectId());
        boolean git = worktrees.inspect(Path.of(project.rootPath())).isolatedWorktree();
        String policy = git ? TaskWorkspacePolicy.RELEASE_BETWEEN_PACKAGES.name()
                : TaskWorkspacePolicy.PINNED_DIRECT.name();
        List<LoopSpec.StageSpec> packageStages = codec.compiledStages(compilation);
        TaskRow created = transactions.execute(ignored -> {
            DesignerSessionRow locked = mapper.findDesignerSession(session.id()).orElseThrow();
            if (locked.taskId() != null) return mapper.findTask(locked.taskId()).orElseThrow();
            String now = now();
            var draft = mapper.findDraft(locked.loopDraftId()).orElseThrow();
            var profile = mapper.findCurrentDesignerTaskProfile(locked.id()).orElseThrow();
            String taskId = UUID.randomUUID().toString();
            TaskRow task = new TaskRow(taskId, project.id(), draft.id(), codec.boundedTitle(draft.goal()),
                    TaskState.PENDING_START.name(), null, null, null, null, now, now, 0,
                    profile.id(), profile.rolePackId(), profile.rolePackVersion(),
                    TaskExecutionMode.ROLLING_PACKAGES.name(), policy);
            lifecycle.create(taskSubject(task), task.state(), Map.of("source", "ROLLING_PACKAGE_1"),
                    () -> mapper.insertTask(task),
                    () -> new ConflictException("ROLLING_TASK_CREATE_CONFLICT", "滚动任务被并发创建"));
            var requirement = mapper.findCurrentDesignRequirementRevision(locked.id()).orElseThrow();
            List<DesignWorkPackageRow> packages = mapper.listDesignWorkPackages(requirement.id());
            TaskPackagePlanRevisionRow plan = new TaskPackagePlanRevisionRow(UUID.randomUUID().toString(),
                    task.id(), locked.id(), requirement.id(), 1, PackagePlanRevisionState.ACTIVE.name(),
                    codec.planJson(packages), "{}", now, now, null, 0);
            lifecycle.create(new LifecycleTransitionService.Subject(LifecycleMachineType.PACKAGE_PLAN_REVISION,
                            plan.id(), LifecycleScopeType.TASK, task.id()), plan.state(),
                    Map.of("origin", "INITIAL", "revision", 1),
                    () -> mapper.insertTaskPackagePlanRevision(plan),
                    () -> new ConflictException("ROLLING_PLAN_CREATE_CONFLICT", "初始滚动计划被并发创建"));
            TaskPackageRunRow firstRun = null;
            for (DesignWorkPackageRow row : packages) {
                boolean selected = row.id().equals(first.id());
                TaskPackageRunRow run = new TaskPackageRunRow(UUID.randomUUID().toString(), task.id(), plan.id(),
                        row.id(), row.packageId(), row.ordinal(), row.title(),
                        selected ? TaskPackageRunState.EXECUTION_READY.name() : TaskPackageRunState.PLANNED.name(),
                        null, selected ? locked.discussionRevision() : 0,
                        selected ? row.designRevision() : 0,
                        selected ? row.approvedDesignRevision() : null, null, now, now, 0);
                lifecycle.create(packageSubject(run), run.state(), Map.of("packageKey", run.packageKey()),
                        () -> mapper.insertTaskPackageRun(run),
                        () -> new ConflictException("PACKAGE_RUN_CREATE_CONFLICT", "工作包运行记录被并发创建"));
                if (selected) firstRun = run;
            }
            if (firstRun == null) throw new ConflictException("ROLLING_PACKAGE_PLAN_INVALID", "首包未进入滚动计划");
            LoopSpec base = codec.parseSpec(draft.specJson());
            LoopSpec firstSpec = codec.copyWithStages(base, packageStages);
            appendSpecAndStages(task, firstRun, first, firstSpec, packageStages, 1);
            if (mapper.bindDesignerSessionTask(locked.id(), task.id(), now, locked.version()) != 1) {
                throw new ConflictException("ROLLING_DESIGNER_BIND_CONFLICT", "设计会话已被并发更新");
            }
            return task;
        });
        if (created == null) throw new ConflictException("ROLLING_TASK_CREATE_FAILED", "滚动任务事务未完成");
        events.emit(created.id(), "task.pending_start", Map.of("state", created.state(),
                "executionMode", created.executionMode(), "workspacePolicy", created.workspacePolicy()));
        return created;
    }

    public void markDesignReview(String designerSessionId, String designWorkPackageId,
                                 int discussionRevision, int designRevision) {
        DesignerSessionRow session = mapper.findDesignerSession(designerSessionId).orElseThrow();
        if (session.taskId() == null || !eligible(designerSessionId)) return;
        TaskPackageRunRow run = runForPackage(session.taskId(), designWorkPackageId);
        if (!TaskPackageRunState.DESIGNING.name().equals(run.state())) return;
        transactions.executeWithoutResult(ignored -> {
            updateRun(run, TaskPackageRunState.DESIGN_REVIEW, LifecycleEvent.REQUEST_PACKAGE_REVIEW,
                    discussionRevision, designRevision, null, "PACKAGE_DESIGN_APPROVAL_REQUIRED");
            TaskRow task = mapper.findTask(session.taskId()).orElseThrow();
            updateTask(task, TaskState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    Map.of("reason", "PACKAGE_DESIGN_APPROVAL_REQUIRED", "packageKey", run.packageKey()));
        });
        events.emit(session.taskId(), "package.design_review_required", Map.of("packageKey", run.packageKey()));
    }

    /** Validates a package start without changing the child aggregate. */
    public ExecutionRequest executionRequest(String taskId, String packageRunId, long expectedTaskVersion,
                                             long expectedPackageVersion) {
        TaskRow task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        if (!TaskExecutionMode.ROLLING_PACKAGES.name().equals(task.executionMode())) {
            throw new ConflictException("ROLLING_TASK_REQUIRED", "该任务不是逐包闭环任务");
        }
        if (task.version() != expectedTaskVersion) {
            throw new ConflictException("TASK_VERSION_CONFLICT", "任务状态已更新，请刷新后重试");
        }
        TaskPackageRunRow run = mapper.currentTaskPackageRun(taskId).filter(item -> item.id().equals(packageRunId))
                .orElseThrow(() -> new ConflictException("PACKAGE_RUN_MISSING", "请求的工作包不是当前工作包"));
        if (run.version() != expectedPackageVersion) {
            throw new ConflictException("PACKAGE_RUN_VERSION_CONFLICT", "工作包状态已更新，请刷新后重试");
        }
        RollingPackageCommandPolicy.Context context = policyContext(task, run);
        commandPolicy.require(RollingPackageCommandPolicy.Command.START, context);
        return new ExecutionRequest(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion,
                commandPolicy.startDisposition(context));
    }

    /** Joins Task admission so Run, Task, Queue, and Lease commit or roll back together. */
    public TaskPackageRunRow requestExecutionInTransaction(ExecutionRequest request) {
        ExecutionRequest current = executionRequest(request.taskId(), request.packageRunId(),
                request.expectedTaskVersion(), request.expectedPackageVersion());
        TaskPackageRunRow run = mapper.findTaskPackageRun(request.packageRunId()).orElseThrow();
        if (current.disposition() == RollingPackageCommandPolicy.StartDisposition.IDEMPOTENT) return run;
        updateRun(run, TaskPackageRunState.QUEUED, LifecycleEvent.REQUEST_PACKAGE_EXECUTION,
                run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(), null);
        return mapper.findTaskPackageRun(run.id()).orElseThrow();
    }

    public void discuss(String taskId, String packageRunId, long expectedTaskVersion, long expectedPackageVersion,
                        int expectedDiscussionRevision, int expectedDesignRevision, String content) {
        CommandContext context = command(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion,
                RollingPackageCommandPolicy.Command.DISCUSS);
        designers.getObject().appendPackageMessage(context.session().id(), context.run().packageKey(), content,
                expectedDiscussionRevision, expectedDesignRevision);
        beginDesign(context);
    }

    public void approveDesign(String taskId, String packageRunId, long expectedTaskVersion,
                              long expectedPackageVersion, int expectedDiscussionRevision,
                              int expectedDesignRevision) {
        CommandContext context = command(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion,
                RollingPackageCommandPolicy.Command.APPROVE_DESIGN);
        designers.getObject().approvePackage(context.session().id(), context.run().packageKey(),
                expectedDiscussionRevision, expectedDesignRevision);
    }

    public void redesign(String taskId, String packageRunId, long expectedTaskVersion,
                         long expectedPackageVersion) {
        CommandContext context = command(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion,
                RollingPackageCommandPolicy.Command.REDESIGN);
        designers.getObject().requestPackageRedesign(context.session().id(), context.run().packageKey());
        TaskPackageRunRow run = context.run();
        if ("PACKAGE_EXECUTION_FAILED".equals(run.waitingReasonCode())) {
            String successfulCheckpoint = facts(taskId).stream().reduce((left, right) -> right)
                    .map(PackageFactSnapshotRow::checkpointId).orElseThrow(() ->
                            new ConflictException("PACKAGE_FACT_REQUIRED", "重新设计必须回到上一成功事实点"));
            run = setResumeCheckpoint(run, successfulCheckpoint);
        }
        beginDesign(new CommandContext(context.task(), run, context.session()));
    }

    public void retryCheckpointRelease(String taskId, String packageRunId, long expectedTaskVersion,
                                       long expectedPackageVersion) {
        CommandContext context = command(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion,
                RollingPackageCommandPolicy.Command.RETRY);
        checkpointSaga.retryLeaseRelease(context.task(), context.run());
    }

    public TaskPackageRunRow prepareFailedCandidateRetry(String taskId, String packageRunId,
                                                         long expectedTaskVersion,
                                                         long expectedPackageVersion) {
        CommandContext context = command(taskId, packageRunId, expectedTaskVersion, expectedPackageVersion,
                RollingPackageCommandPolicy.Command.RETRY);
        TaskPackageRunRow run = context.run();
        if (!TaskPackageRunState.WAITING_INPUT.name().equals(run.state())
                || !"PACKAGE_EXECUTION_FAILED".equals(run.waitingReasonCode())
                || run.resumeCheckpointId() == null) {
            throw new ConflictException("PACKAGE_FAILURE_CANDIDATE_UNAVAILABLE", "失败候选 Checkpoint 不可继续");
        }
        updateRun(run, TaskPackageRunState.EXECUTION_READY, LifecycleEvent.APPROVE_PACKAGE_DESIGN,
                run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(), null);
        return mapper.findTaskPackageRun(run.id()).orElseThrow();
    }

    private CommandContext command(String taskId, String packageRunId, long expectedTaskVersion,
                                   long expectedPackageVersion, RollingPackageCommandPolicy.Command command) {
        TaskRow task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        TaskPackageRunRow run = mapper.findTaskPackageRun(packageRunId).orElseThrow(() ->
                new NotFoundException("Package run not found: " + packageRunId));
        if (!task.id().equals(run.taskId()) || task.version() != expectedTaskVersion
                || run.version() != expectedPackageVersion) {
            throw new ConflictException("PACKAGE_COMMAND_VERSION_CONFLICT", "任务或工作包已更新，请刷新后重试");
        }
        DesignerSessionRow session = mapper.findDesignerSessionByTask(taskId).orElseThrow(() ->
                new ConflictException("ROLLING_DESIGNER_MISSING", "滚动任务没有绑定设计会话"));
        commandPolicy.require(command, policyContext(task, run));
        return new CommandContext(task, run, session);
    }

    RollingPackageCommandPolicy.Context policyContext(TaskRow task, TaskPackageRunRow run) {
        return commandContexts.context(task, run);
    }

    private void beginDesign(CommandContext context) {
        TaskPackageRunRow current = mapper.findTaskPackageRun(context.run().id()).orElseThrow();
        if (!TaskPackageRunState.DESIGNING.name().equals(current.state())) {
            updateRun(current, TaskPackageRunState.DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN,
                    current.discussionRevision(), current.designRevision(), null, null);
        }
        TaskRow task = mapper.findTask(context.task().id()).orElseThrow();
        if (TaskState.WAITING_INPUT.name().equals(task.state())) {
            updateTask(task, TaskState.PACKAGE_DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN,
                    Map.of("packageKey", current.packageKey()));
        }
    }

    public void executionStarted(String taskId) {
        if (!rolling(taskId)) return;
        mapper.currentTaskPackageRun(taskId).ifPresent(run -> {
            if (TaskPackageRunState.QUEUED.name().equals(run.state())) {
                updateRun(run, TaskPackageRunState.RUNNING, LifecycleEvent.START,
                        run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(), null);
            }
        });
    }

    public void verificationStarted(String taskId) {
        if (!rolling(taskId)) return;
        mapper.currentTaskPackageRun(taskId).ifPresent(run -> {
            if (TaskPackageRunState.RUNNING.name().equals(run.state())) {
                updateRun(run, TaskPackageRunState.VERIFYING, LifecycleEvent.BEGIN_VERIFICATION,
                        run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(), null);
            }
        });
    }

    /** Returns true when all packages are frozen and final Judges must start now. */
    public boolean checkpointSuccessfulPackage(TaskRow task, String packageRunId, String attemptId,
                                               FactEvidence evidence) {
        return checkpointSaga.complete(task, packageRunId, attemptId, evidence);
    }

    public void packageFailed(TaskRow task, String code, String message, boolean writersStopped) {
        checkpointSaga.fail(task, code, message, writersStopped);
    }

    public void afterLeaseReconciliation(String taskId) {
        if (rolling(taskId)) checkpointSaga.afterLeaseReconciliation(taskId);
    }

    public void recoverDesignDispatch(String taskId, String packageRunId) {
        if (rolling(taskId)) checkpointSaga.recoverDesignDispatch(taskId, packageRunId);
    }

    public LoopSpec latestSpec(TaskRow task) {
        if (TaskExecutionMode.ROLLING_PACKAGES.name().equals(task.executionMode())) {
            return mapper.latestTaskSpecRevision(task.id()).map(row -> codec.parseSpec(row.specJson()))
                    .orElseThrow(() -> new ConflictException("TASK_SPEC_REVISION_MISSING", "滚动任务缺少累计执行规范"));
        }
        var draft = mapper.findDraft(task.loopDraftId()).orElseThrow();
        return codec.parseSpec(draft.specJson());
    }

    public List<TaskPackageRunRow> runs(String taskId) { return mapper.listTaskPackageRuns(taskId); }
    public List<PackageFactSnapshotRow> facts(String taskId) { return mapper.listPackageFactSnapshots(taskId); }

    private LoopSpec acceptedSpecBase(TaskRow task) {
        PackageFactSnapshotRow fact = facts(task.id()).stream().reduce((left, right) -> right).orElse(null);
        if (fact != null) return mapper.listTaskSpecRevisions(task.id()).stream()
                .filter(revision -> fact.taskSpecSha256().equals(revision.specSha256())).findFirst()
                .map(revision -> codec.parseSpec(revision.specJson())).orElseThrow(() ->
                        new ConflictException("PACKAGE_FACT_TASK_SPEC_MISSING", "上一成功事实引用的累计执行规范不存在"));
        LoopDraftRow draft = mapper.findDraft(task.loopDraftId()).orElseThrow();
        return codec.copyWithStages(codec.parseSpec(draft.specJson()), List.of());
    }

    public void supersedeRun(TaskPackageRunRow run) {
        if (!TaskPackageRunState.valueOf(run.state()).terminal()) {
            updateRun(run, TaskPackageRunState.SUPERSEDED, LifecycleEvent.SUPERSEDE_PACKAGE,
                    run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(), null);
        }
    }

    public void cancelRuns(String taskId) {
        mapper.listTaskPackageRuns(taskId).stream().filter(run -> !TaskPackageRunState.valueOf(run.state()).terminal())
                .forEach(run -> updateRun(run, TaskPackageRunState.CANCELLED, LifecycleEvent.CANCEL,
                        run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(), null));
    }

    void beginPlannedDesignInTransaction(String taskId, String packageRunId,
                                         RollingPackageCommandPolicy.Command command) {
        TaskRow task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        TaskPackageRunRow run = mapper.findTaskPackageRun(packageRunId).orElseThrow(() ->
                new NotFoundException("Package run not found: " + packageRunId));
        if (!taskId.equals(run.taskId()) || !TaskPackageRunState.PLANNED.name().equals(run.state())) {
            throw new ConflictException("PACKAGE_PLAN_START_CONFLICT", "新计划的首个工作包已变化");
        }
        commandPolicy.require(command, policyContext(task, run));
        updateRun(run, TaskPackageRunState.DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN,
                run.discussionRevision(), run.designRevision(), null, null);
        if (TaskState.WAITING_INPUT.name().equals(task.state())) {
            updateTask(task, TaskState.PACKAGE_DESIGNING, LifecycleEvent.BEGIN_PACKAGE_DESIGN,
                    Map.of("packageKey", run.packageKey(), "source", "PLAN_REVISION"));
        }
    }

    void dispatchPlannedDesign(String taskId, String packageRunId, String checkpointId) {
        TaskRow task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        TaskPackageRunRow run = mapper.findTaskPackageRun(packageRunId).orElseThrow(() ->
                new NotFoundException("Package run not found: " + packageRunId));
        if (!TaskPackageRunState.DESIGNING.name().equals(run.state())
                || !TaskState.PACKAGE_DESIGNING.name().equals(task.state())) {
            throw new ConflictException("PACKAGE_PLAN_START_CONFLICT", "新计划的设计状态已变化");
        }
        TaskWorkspaceCheckpointRow checkpoint = mapper.findTaskWorkspaceCheckpoint(checkpointId).orElseThrow(() ->
                new ConflictException("PACKAGE_CHECKPOINT_MISSING", "重规划事实快照已不存在"));
        try {
            Path root = checkpoints.designSnapshot(task, checkpoint);
            dispatchDesign(taskId, run.designWorkPackageId(), root);
        }
        catch (RuntimeException failure) {
            TaskPackageRunRow blocked = mapper.findTaskPackageRun(run.id()).orElse(run);
            updateRun(blocked, TaskPackageRunState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT,
                    blocked.discussionRevision(), blocked.designRevision(), blocked.acceptedDesignRevision(),
                    "PACKAGE_CHECKPOINT_BLOCKED");
            TaskRow latest = mapper.findTask(taskId).orElse(task);
            if (!TaskState.WAITING_INPUT.name().equals(latest.state())) updateTask(latest, TaskState.WAITING_INPUT,
                    LifecycleEvent.REQUIRE_INPUT, Map.of("reason", "PACKAGE_CHECKPOINT_BLOCKED"));
            events.emit(taskId, "package.design_snapshot_blocked", Map.of("packageKey", run.packageKey(),
                    "code", "PACKAGE_CHECKPOINT_BLOCKED"));
        }
    }

    private void dispatchDesign(String taskId, String designWorkPackageId, Path expectedRoot) {
        DesignerSessionRow session = mapper.findDesignerSessionByTask(taskId).orElseThrow(() ->
                new ConflictException("ROLLING_DESIGNER_MISSING", "滚动任务没有绑定设计会话"));
        if (!taskId.equals(session.taskId())) {
            throw new ConflictException("ROLLING_DESIGNER_TASK_MISMATCH", "设计会话与任务绑定不一致");
        }
        ProjectRow project = designProject(session);
        if (!Path.of(project.rootPath()).toAbsolutePath().normalize()
                .equals(expectedRoot.toAbsolutePath().normalize())) {
            throw new ConflictException("PACKAGE_DESIGN_SNAPSHOT_MISMATCH", "工作包设计快照与事实引用不一致");
        }
        DesignWorkPackageRow workPackage = mapper.findDesignWorkPackage(designWorkPackageId).orElseThrow(() ->
                new ConflictException("ROLLING_PACKAGE_MISSING", "滚动工作包已不存在"));
        String prompt = "这是逐包闭环任务的下一工作包。只能把下列事实层和当前只读快照作为现状；"
                + "AI 导航摘要仅帮助定位，不属于机器证据。不得假设初始仓库仍是当前状态。"
                + codec.factContext(facts(taskId));
        designers.getObject().dispatchPackageDesigner(session, workPackage, prompt, false);
    }

    private void appendSpecAndStages(TaskRow task, TaskPackageRunRow run, DesignWorkPackageRow workPackage,
                                     LoopSpec cumulative, List<LoopSpec.StageSpec> appended, int revision) {
        String serialized = codec.write(cumulative);
        mapper.insertTaskSpecRevision(new TaskSpecRevisionRow(UUID.randomUUID().toString(), task.id(), revision,
                run.id(), serialized, codec.sha256(serialized), cumulative.stages().size(), now()));
        int ordinal = mapper.listStages(task.id()).size();
        WorkPackageRoleProfileRow role = mapper.findWorkPackageRoleProfile(workPackage.id()).orElse(null);
        for (LoopSpec.StageSpec stage : appended) {
            StageRow row = new StageRow(UUID.randomUUID().toString(), task.id(), ordinal++, stage.objective(),
                    codec.write(stage.allowedPaths()), codec.write(stage.forbiddenPaths()), codec.write(stage.deliverables()),
                    codec.write(stage.verifiers()), StageState.PENDING.name(), now(), now(), 0,
                    workPackage.packageId(),
                    (stage.stageKind() == null ? StageKind.LEGACY_SOFTWARE : stage.stageKind()).name(),
                    (stage.executionStrategy() == null ? ExecutionStrategy.OPEN_CODE_IMPLEMENTATION
                            : stage.executionStrategy()).name(), stage.artifactPlanId(),
                    role == null ? task.rolePackId() : role.rolePackId(),
                    role == null ? task.rolePackVersion() : role.rolePackVersion(),
                    role == null ? "REQUIRED" : role.testPolicy(),
                    role == null ? "[]" : role.technologiesJson(),
                    role == null ? null : role.projectStackProfileId(),
                    role == null ? "[]" : role.componentKeysJson(),
                    role == null ? null : role.stackFingerprint(), run.id());
            lifecycle.create(stageSubject(row), row.state(), Map.of("packageRunId", run.id()),
                    () -> mapper.insertStage(row),
                    () -> new ConflictException("STAGE_CREATE_CONFLICT", "工作包 Stage 被并发创建"));
        }
    }

    private TaskPackageRunRow runForPackage(String taskId, String designWorkPackageId) {
        return mapper.listTaskPackageRuns(taskId).stream()
                .filter(row -> row.designWorkPackageId().equals(designWorkPackageId)).findFirst()
                .orElseThrow(() -> new ConflictException("PACKAGE_RUN_MISSING", "工作包没有对应的执行记录"));
    }

    private void updateRun(TaskPackageRunRow from, TaskPackageRunState state, LifecycleEvent event,
                           int discussionRevision, int designRevision, Integer acceptedDesignRevision,
                           String waitingReason) {
        TaskPackageRunRow to = new TaskPackageRunRow(from.id(), from.taskId(), from.planRevisionId(),
                from.designWorkPackageId(), from.packageKey(), from.ordinal(), from.title(), state.name(),
                from.correctionOfPackageRunId(), discussionRevision, designRevision, acceptedDesignRevision,
                waitingReason, from.createdAt(), now(), from.version(), from.resumeCheckpointId());
        lifecycle.transition(packageSubject(from), from.state(), to.state(), event, waitingReason,
                Map.of("packageKey", from.packageKey()), () -> mapper.updateTaskPackageRun(to),
                () -> new ConflictException("PACKAGE_RUN_VERSION_CONFLICT", "工作包状态已被并发更新"));
    }

    private TaskPackageRunRow setResumeCheckpoint(TaskPackageRunRow run, String checkpointId) {
        if (checkpointId != null && checkpointId.equals(run.resumeCheckpointId())) return run;
        if (mapper.updateTaskPackageRunResumeCheckpoint(run.id(), checkpointId, now(), run.version()) != 1) {
            throw new ConflictException("PACKAGE_RUN_VERSION_CONFLICT", "工作包恢复事实点已更新");
        }
        return mapper.findTaskPackageRun(run.id()).orElseThrow();
    }

    private void updateTask(TaskRow from, TaskState state, LifecycleEvent event, Map<String, ?> metadata) {
        TaskRow to = new TaskRow(from.id(), from.projectId(), from.loopDraftId(), from.title(), state.name(),
                from.worktreePath(), from.branchName(), from.sourceBranch(), from.baselineCommit(), from.createdAt(),
                now(), from.version(), from.taskProfileId(), from.rolePackId(), from.rolePackVersion(),
                from.executionMode(), from.workspacePolicy());
        lifecycle.transition(taskSubject(from), from.state(), to.state(), event, null, metadata,
                () -> mapper.updateTaskState(to),
                () -> new ConflictException("TASK_VERSION_CONFLICT", "任务状态已被并发更新"));
    }

    public boolean rolling(String taskId) {
        return mapper.findTask(taskId).map(row -> TaskExecutionMode.ROLLING_PACKAGES.name().equals(row.executionMode()))
                .orElse(false);
    }

    private LifecycleTransitionService.Subject taskSubject(TaskRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK, row.id(),
                LifecycleScopeType.TASK, row.id());
    }

    private LifecycleTransitionService.Subject stageSubject(StageRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.STAGE, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private LifecycleTransitionService.Subject packageSubject(TaskPackageRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.TASK_PACKAGE_RUN, row.id(),
                LifecycleScopeType.TASK, row.taskId());
    }

    private String now() { return Instant.now().toString(); }

    public record FactEvidence(String inputTree, String diffSha256, String evidenceSha256,
                               String diffArtifactId, String evidenceArtifactId,
                               List<String> acceptedStageIds, String navigationSummary) { }
    public record ExecutionRequest(String taskId, String packageRunId, long expectedTaskVersion,
                                   long expectedPackageVersion,
                                   RollingPackageCommandPolicy.StartDisposition disposition) { }
    private record CommandContext(TaskRow task, TaskPackageRunRow run, DesignerSessionRow session) { }
}
