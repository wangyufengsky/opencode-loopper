package io.opencode.loopper.api;

import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.WorkspaceCheckpointState;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.RecoveryService;
import io.opencode.loopper.service.TaskService;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Local-user disposition boundary between an execution result and a final Task state. */
@RestController
@RequestMapping("/api/tasks/{taskId}/decision")
public class TaskDecisionController {
    private final TaskService tasks;
    private final RecoveryService recovery;
    private final ObjectMapper json;

    public TaskDecisionController(TaskService tasks, RecoveryService recovery, ObjectMapper json) {
        this.tasks = tasks;
        this.recovery = recovery;
        this.json = json;
    }

    @GetMapping
    public DecisionDto get(@PathVariable String taskId) {
        return dto(tasks.get(taskId));
    }

    @PostMapping("/continue")
    public DecisionDto continueTask(@PathVariable String taskId,
                                    @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
                                    @RequestBody ContinueRequest request) {
        requireLocalUi(localUi);
        requireRequest(request);
        requireVersions(taskId, request.expectedTaskVersion(), request.expectedCycleVersion());
        tasks.continueExecution(taskId, request.stageId(), request.supplementalRequirement());
        return dto(tasks.get(taskId));
    }

    @PostMapping("/derive")
    public FeatureContracts.RecoveryDto derive(@PathVariable String taskId,
                                               @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
                                               @RequestBody DeriveRequest request) {
        requireLocalUi(localUi);
        requireRequest(request);
        requireVersions(taskId, request.expectedTaskVersion(), request.expectedCycleVersion());
        if (request.mode() != RecoveryMode.INHERIT_CHANGES && request.mode() != RecoveryMode.REWORK_ALL_STAGES) {
            throw new BadRequestException("TASK_DECISION_DERIVE_MODE_INVALID",
                    "Derived Task mode must be INHERIT_CHANGES or REWORK_ALL_STAGES");
        }
        return recovery.create(taskId, request.mode());
    }

    @PostMapping("/audit")
    public FeatureContracts.RecoveryDto audit(@PathVariable String taskId,
                                              @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
                                              @RequestBody VersionRequest request) {
        requireLocalUi(localUi);
        requireRequest(request);
        requireVersions(taskId, request.expectedTaskVersion(), request.expectedCycleVersion());
        return recovery.create(taskId, RecoveryMode.VERIFY_ONLY);
    }

    @PostMapping("/accept")
    public DecisionDto accept(@PathVariable String taskId,
                              @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
                              @RequestBody VersionRequest request) {
        requireLocalUi(localUi);
        requireRequest(request);
        requireVersions(taskId, request.expectedTaskVersion(), request.expectedCycleVersion());
        return dto(tasks.acceptResult(taskId));
    }

    @PostMapping("/cancel")
    public DecisionDto cancel(@PathVariable String taskId,
                              @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
                              @RequestBody VersionRequest request) {
        requireLocalUi(localUi);
        requireRequest(request);
        requireVersions(taskId, request.expectedTaskVersion(), request.expectedCycleVersion());
        return dto(tasks.cancel(taskId));
    }

    private DecisionDto dto(TaskRow task) {
        TaskExecutionCycleRow cycle = tasks.latestExecutionCycle(task.id());
        TaskWorkspaceCheckpointRow checkpoint = tasks.latestWorkspaceCheckpoint(task.id());
        List<StageChoiceDto> stages = tasks.stages(task.id()).stream()
                .map(row -> new StageChoiceDto(row.id(), row.ordinal(), row.objective(), row.state())).toList();
        return new DecisionDto(task.id(), task.state(), task.version(), cycleDto(cycle), checkpointDto(checkpoint),
                actions(task, cycle, checkpoint), stages);
    }

    private List<String> actions(TaskRow task, TaskExecutionCycleRow cycle,
                                 TaskWorkspaceCheckpointRow checkpoint) {
        if (!TaskState.AWAITING_DECISION.name().equals(task.state()) || cycle == null) return List.of();
        List<String> actions = new ArrayList<>();
        boolean checkpointReady = checkpoint != null && WorkspaceCheckpointState.READY.name().equals(checkpoint.state());
        if (checkpointReady) {
            actions.add("CONTINUE_CURRENT_TASK");
            actions.add("DERIVE_INHERIT_CHANGES");
            actions.add("READ_ONLY_AUDIT");
            if (ExecutionCycleState.SUCCEEDED.name().equals(cycle.state())) {
                actions.add("PUBLISH");
                if (manifestEmpty(checkpoint)) actions.add("ACCEPT_RESULT");
            }
        }
        if (!GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())
                && task.baselineCommit() != null && !task.baselineCommit().isBlank()) {
            actions.add("DERIVE_REWORK_ALL");
        }
        actions.add("CANCEL");
        return List.copyOf(actions);
    }

    private boolean manifestEmpty(TaskWorkspaceCheckpointRow checkpoint) {
        String manifest = checkpoint.manifestJson();
        return manifest != null && manifest.strip().matches("\\[\\s*\\]");
    }

    private CycleDto cycleDto(TaskExecutionCycleRow row) {
        return row == null ? null : new CycleDto(row.id(), row.ordinal(), row.kind(), row.state(),
                row.startStageId(), row.startStageOrdinal(), row.failureCode(), row.failureMessage(),
                row.authorizedAt(), row.startedAt(), row.endedAt(), row.version());
    }

    private CheckpointDto checkpointDto(TaskWorkspaceCheckpointRow row) {
        return row == null ? null : new CheckpointDto(row.id(), row.state(), row.snapshotId(), row.checkpointTree(),
                manifestCount(row), row.blockerCode(), row.blockerMessage(), row.updatedAt(), row.version());
    }

    private int manifestCount(TaskWorkspaceCheckpointRow row) {
        try {
            return json.readTree(row.manifestJson()).size();
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    private void requireVersions(String taskId, long expectedTaskVersion, long expectedCycleVersion) {
        TaskRow task = tasks.get(taskId);
        TaskExecutionCycleRow cycle = tasks.latestExecutionCycle(taskId);
        if (task.version() != expectedTaskVersion || cycle == null || cycle.version() != expectedCycleVersion) {
            throw new ConflictException("TASK_DECISION_STALE", "Task result changed; refresh before choosing an action");
        }
    }

    private void requireLocalUi(String localUi) {
        if (!"1".equals(localUi)) {
            throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED",
                    "This operation is available only to the local Loopper UI");
        }
    }

    private void requireRequest(Object request) {
        if (request == null) throw new BadRequestException("TASK_DECISION_REQUEST_REQUIRED", "Decision request is required");
    }

    public record DecisionDto(String taskId, String taskState, long taskVersion, CycleDto cycle,
                              CheckpointDto checkpoint, List<String> availableActions,
                              List<StageChoiceDto> stages) { }
    public record CycleDto(String id, int ordinal, String kind, String result, String startStageId,
                           Integer startStageOrdinal, String failureCode, String failureMessage,
                           String authorizedAt, String startedAt, String endedAt, long version) { }
    public record CheckpointDto(String id, String state, String snapshotId, String checkpointTree,
                                int changedFileCount, String blockerCode, String blockerMessage,
                                String updatedAt, long version) { }
    public record StageChoiceDto(String id, int ordinal, String objective, String state) { }
    public record VersionRequest(long expectedTaskVersion, long expectedCycleVersion) { }
    public record ContinueRequest(long expectedTaskVersion, long expectedCycleVersion,
                                  String stageId, String supplementalRequirement) { }
    public record DeriveRequest(long expectedTaskVersion, long expectedCycleVersion, RecoveryMode mode) { }
}
