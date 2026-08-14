package io.opencode.loopper.service;

import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskLineageRow;
import io.opencode.loopper.persistence.TaskQueueRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Creates a new, traceable recovery task; it never mutates a terminal parent in place. */
@Service
public class RecoveryService {
    private final LoopperMapper mapper;
    private final LoopDraftService drafts;
    private final ProjectService projects;
    private final RecoveryPersistence persistence;

    public RecoveryService(LoopperMapper mapper, LoopDraftService drafts, ProjectService projects,
                           RecoveryPersistence persistence) {
        this.mapper = mapper;
        this.drafts = drafts;
        this.projects = projects;
        this.persistence = persistence;
    }

    public FeatureContracts.RecoveryDto create(String parentTaskId, RecoveryMode requestedMode) {
        RecoveryMode mode = requestedMode == null ? RecoveryMode.FROM_FAILED_STAGE : requestedMode;
        TaskRow parent = mapper.findTask(parentTaskId)
                .orElseThrow(() -> new NotFoundException("Task not found: " + parentTaskId));
        if (mode == RecoveryMode.REWORK_ALL_STAGES) requireReworkableParent(parent);
        else requireRecoverableParent(parent);
        LoopDraftRow parentDraft = mapper.findDraft(parent.loopDraftId())
                .orElseThrow(() -> new ConflictException("RECOVERY_CONTRACT_MISSING", "Recovery requires the parent LoopSpec draft"));
        ProjectRow project = projects.get(parent.projectId());
        String fingerprint = workspaceFingerprint(parent, project);
        List<StageRow> parentStages = mapper.listStages(parent.id());
        StageRow recoveryPoint = mode == RecoveryMode.REWORK_ALL_STAGES ? null : recoveryPoint(parentStages);
        LoopSpec parentSpec = drafts.spec(parentDraft);
        List<LoopSpec.StageSpec> stages = mode == RecoveryMode.REWORK_ALL_STAGES
                ? List.copyOf(parentSpec.stages()) : copyStages(parentSpec, recoveryPoint, mode);
        String modeContext = recoveryContext(parentSpec.context(), parent.id(), mode, recoveryPoint);
        LoopSpec childSpec = new LoopSpec(parentSpec.schemaVersion(), parent.projectId(), parentSpec.goal(), modeContext,
                stages, parentSpec.limits(), parentSpec.model(), parentSpec.sessionPolicy(),
                parentSpec.nextAttemptPromptTemplate(), parentSpec.budget());

        LoopDraftRow childDraft = drafts.create(childSpec);
        TaskRow child = mode == RecoveryMode.REWORK_ALL_STAGES
                ? drafts.confirmAtBaseline(childDraft.id(), recoveryTitle(parent.title(), mode), "RECOVERY", parent.baselineCommit())
                : drafts.confirm(childDraft.id(), recoveryTitle(parent.title(), mode), "RECOVERY");
        persistence.link(new TaskLineageRow(child.id(), parent.id(), mode.name(),
                recoveryPoint == null ? null : recoveryPoint.id(), fingerprint, Instant.now().toString()));
        return new FeatureContracts.RecoveryDto(child.id(), parent.id(), mode,
                recoveryPoint == null ? null : recoveryPoint.id(), fingerprint, mode != RecoveryMode.VERIFY_ONLY);
    }

    public List<FeatureContracts.RecoveryDto> list(String parentTaskId) {
        mapper.findTask(parentTaskId).orElseThrow(() -> new NotFoundException("Task not found: " + parentTaskId));
        return mapper.childTasks(parentTaskId).stream()
                .map(lineage -> recoveryDto(lineage)).toList();
    }

    private FeatureContracts.RecoveryDto recoveryDto(TaskLineageRow lineage) {
        RecoveryMode mode;
        try {
            mode = RecoveryMode.valueOf(lineage.recoveryMode());
        } catch (RuntimeException invalidMode) {
            throw new ConflictException("RECOVERY_LINEAGE_INVALID", "Stored task lineage has an unknown recovery mode");
        }
        return new FeatureContracts.RecoveryDto(lineage.childTaskId(), lineage.parentTaskId(), mode,
                lineage.parentStageId(), lineage.workspaceFingerprint(), mode != RecoveryMode.VERIFY_ONLY);
    }

    private void requireRecoverableParent(TaskRow parent) {
        if (!TaskState.FAILED.name().equals(parent.state()) && !TaskState.CANCELLED.name().equals(parent.state())) {
            throw new ConflictException("RECOVERY_PARENT_NOT_TERMINAL",
                    "只有已失败或已取消的任务可以创建恢复草稿");
        }
        if (parent.loopDraftId() == null || parent.loopDraftId().isBlank()) {
            throw new ConflictException("RECOVERY_CONTRACT_MISSING", "Recovery requires a parent LoopSpec");
        }
    }

    private void requireReworkableParent(TaskRow parent) {
        if (!List.of(TaskState.WAITING_INPUT.name(), TaskState.SUCCEEDED.name(), TaskState.FAILED.name(),
                TaskState.CANCELLED.name()).contains(parent.state())) {
            throw new ConflictException("REWORK_PARENT_ACTIVE", "只有等待输入或已结束的任务可以新分支重做");
        }
        if (parent.loopDraftId() == null || parent.loopDraftId().isBlank()) {
            throw new ConflictException("RECOVERY_CONTRACT_MISSING", "Rework requires a parent LoopSpec");
        }
        if (GitWorktreeManager.DIRECT_BRANCH.equals(parent.branchName())
                || parent.baselineCommit() == null || parent.baselineCommit().isBlank()) {
            throw new ConflictException("REWORK_ISOLATED_BASELINE_REQUIRED", "重做需要父任务的 Git 任务分支和基线提交");
        }
    }

    private String workspaceFingerprint(TaskRow parent, ProjectRow project) {
        if (!GitWorktreeManager.DIRECT_BRANCH.equals(parent.branchName())) {
            return parent.baselineCommit() == null ? "" : parent.baselineCommit();
        }
        TaskQueueRow recorded = mapper.findTaskQueue(parent.id())
                .orElseThrow(() -> new ConflictException("RECOVERY_WORKSPACE_FINGERPRINT_MISSING",
                        "Direct recovery requires the parent workspace fingerprint"));
        DirectWorkspaceLeaseCoordinator.WorkspaceIdentity current;
        try {
            current = DirectWorkspaceLeaseCoordinator.identify(Path.of(project.rootPath()));
        } catch (RuntimeException unavailable) {
            throw new ConflictException("RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH",
                    "Direct workspace is unavailable or no longer matches the failed task");
        }
        if (!recorded.canonicalRoot().equals(current.canonicalRoot())
                || !recorded.rootFingerprint().equals(current.rootFingerprint())) {
            throw new ConflictException("RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH",
                    "Direct workspace fingerprint changed after the parent task ended; recovery is blocked");
        }
        return current.rootFingerprint();
    }

    private StageRow recoveryPoint(List<StageRow> stages) {
        if (stages.isEmpty()) throw new ConflictException("RECOVERY_STAGE_MISSING", "The parent task has no stages to recover");
        return stages.stream().filter(stage -> StageState.FAILED.name().equals(stage.state())).findFirst()
                .or(() -> stages.stream().filter(stage -> StageState.RUNNING.name().equals(stage.state())
                        || StageState.PAUSED.name().equals(stage.state())).findFirst())
                .or(() -> stages.stream().filter(stage -> !StageState.SUCCEEDED.name().equals(stage.state())).findFirst())
                .orElse(stages.getLast());
    }

    private List<LoopSpec.StageSpec> copyStages(LoopSpec parentSpec, StageRow recoveryPoint, RecoveryMode mode) {
        int start = mode == RecoveryMode.ALL_STAGES ? 0 : recoveryPoint.ordinal();
        if (start < 0 || start >= parentSpec.stages().size()) {
            throw new ConflictException("RECOVERY_STAGE_MISMATCH", "The persisted parent stage does not match its LoopSpec");
        }
        if (mode == RecoveryMode.VERIFY_ONLY) return List.of(parentSpec.stages().get(start));
        return List.copyOf(parentSpec.stages().subList(start, parentSpec.stages().size()));
    }

    private String recoveryContext(String existing, String parentTaskId, RecoveryMode mode, StageRow point) {
        String boundary = mode == RecoveryMode.REWORK_ALL_STAGES
                ? "这是新分支重做任务：从父任务创建时的基线重新执行全部阶段，父任务、父分支和历史证据保持不变。"
                : mode == RecoveryMode.VERIFY_ONLY
                ? "这是只读验证型恢复草稿：不得创建 OpenCode 可写执行会话，也不得在原目录回滚。"
                : "这是派生恢复草稿：不得对父任务或原目录执行 in-place revert。";
        return (existing == null || existing.isBlank() ? "" : existing.trim() + "\n\n")
                + "Recovery parent: " + parentTaskId + "\nRecovery mode: " + mode.name()
                + "\nRecovery stage: " + (point == null ? "none" : point.ordinal() + 1)
                + "\n" + boundary;
    }

    private String recoveryTitle(String title, RecoveryMode mode) {
        String base = title == null || title.isBlank() ? "任务" : title.trim();
        String candidate = mode == RecoveryMode.REWORK_ALL_STAGES ? base + " · 重做" : base + " · 恢复 " + mode.name();
        return candidate.substring(0, Math.min(candidate.length(), 180));
    }
}
