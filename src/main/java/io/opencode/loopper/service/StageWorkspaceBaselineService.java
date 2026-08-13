package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.StageWorkspaceBaselineRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.StageWorkspaceBaselineManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists immutable Stage workspace baseline references around filesystem I/O. */
@Service
public class StageWorkspaceBaselineService {
    private static final Logger log = LoggerFactory.getLogger(StageWorkspaceBaselineService.class);
    private final LoopperMapper mapper;
    private final StageWorkspaceBaselineManager baselines;
    private final TransactionTemplate filesystemIo;

    public StageWorkspaceBaselineService(LoopperMapper mapper, StageWorkspaceBaselineManager baselines,
                                         PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.baselines = baselines;
        this.filesystemIo = new TransactionTemplate(transactionManager);
        this.filesystemIo.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** Performs Git/filesystem I/O before the small idempotent MyBatis insert. */
    public StageWorkspaceBaselineRow captureIfAbsent(TaskRow task, StageRow stage) {
        StageWorkspaceBaselineRow existing = mapper.findStageWorkspaceBaseline(stage.id()).orElse(null);
        if (existing != null) {
            requireMatching(existing, task, stage);
            outsideTransaction(() -> baselines.requireAvailable(existing.baselineRef()));
            return existing;
        }
        if (mapper.countAttemptsForStage(stage.id()) > 0) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_MISSING",
                    "Stage already has Attempts but no immutable workspace baseline; create a Recovery from the failed Stage");
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_CREATE_FAILED",
                    "Task execution directory is required before capturing a Stage workspace baseline");
        }
        String marker = outsideTransaction(() ->
                baselines.capture(Path.of(task.worktreePath()), task.id(), stage.id()));
        mapper.insertStageWorkspaceBaseline(new StageWorkspaceBaselineRow(
                stage.id(), task.id(), marker, Instant.now().toString()));
        StageWorkspaceBaselineRow persisted = mapper.findStageWorkspaceBaseline(stage.id()).orElseThrow(() ->
                new TaskFailure("STAGE_WORKSPACE_BASELINE_MISSING",
                        "Stage workspace baseline could not be persisted"));
        return requireMatching(persisted, task, stage);
    }

    public String requireBaseline(TaskRow task, StageRow stage) {
        StageWorkspaceBaselineRow row = mapper.findStageWorkspaceBaseline(stage.id()).orElseThrow(() ->
                new TaskFailure("STAGE_WORKSPACE_BASELINE_MISSING",
                        "Stage workspace baseline was not captured before its first writable Attempt"));
        requireMatching(row, task, stage);
        outsideTransaction(() -> baselines.requireAvailable(row.baselineRef()));
        return row.baselineRef();
    }

    /** Startup cleanup is best effort and never blocks lifecycle recovery. */
    public void cleanupOrphans() {
        Set<String> liveTaskIds = mapper.listTasks().stream().map(TaskRow::id).collect(Collectors.toUnmodifiableSet());
        try {
            int removed = outsideTransaction(() -> baselines.cleanupOrphans(liveTaskIds));
            if (removed > 0) log.info("Removed {} orphan Stage workspace baseline directories", removed);
        } catch (TaskFailure failure) {
            log.warn("Stage workspace baseline orphan cleanup skipped: {} ({})", failure.getMessage(), failure.code());
        }
    }

    private StageWorkspaceBaselineRow requireMatching(StageWorkspaceBaselineRow row, TaskRow task, StageRow stage) {
        if (!task.id().equals(row.taskId()) || !stage.id().equals(row.stageId())
                || !task.id().equals(stage.taskId())) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_INVALID",
                    "Stored Stage workspace baseline does not match its Task and Stage");
        }
        if (!row.baselineRef().startsWith(StageWorkspaceBaselineManager.PREFIX + task.id() + ":" + stage.id() + ":")) {
            throw new TaskFailure("STAGE_WORKSPACE_BASELINE_INVALID",
                    "Stored Stage workspace baseline reference does not match its Task and Stage");
        }
        return row;
    }

    private void outsideTransaction(Runnable operation) {
        filesystemIo.executeWithoutResult(status -> operation.run());
    }

    private <T> T outsideTransaction(java.util.function.Supplier<T> operation) {
        return filesystemIo.execute(status -> operation.get());
    }
}
