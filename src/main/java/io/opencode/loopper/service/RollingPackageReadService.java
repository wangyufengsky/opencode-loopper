package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageFactSnapshotRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Bounded task-workbench projection; large design and evidence bodies are loaded separately. */
@Service
public class RollingPackageReadService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    public RollingPackageReadService(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public Workbench workbench(String taskId) {
        var task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        if (!"ROLLING_PACKAGES".equals(task.executionMode())) {
            throw new ConflictException("ROLLING_TASK_REQUIRED", "该任务不使用逐包闭环工作台");
        }
        var plan = mapper.activeTaskPackagePlanRevision(taskId).orElseThrow(() ->
                new ConflictException("PACKAGE_PLAN_MISSING", "滚动任务缺少活动拆包计划"));
        var runs = mapper.listTaskPackageRuns(taskId);
        List<PackageView> packages = Stream.concat(
                runs.stream().filter(run -> "FACT_FROZEN".equals(run.state())),
                runs.stream().filter(run -> plan.id().equals(run.planRevisionId()))
                        .filter(run -> !List.of("FACT_FROZEN", "SUPERSEDED", "CANCELLED").contains(run.state())))
                .map(this::packageView).toList();
        int frozen = (int) packages.stream().filter(item -> "FACT_FROZEN".equals(item.state())).count();
        return new Workbench(task.id(), task.title(), task.state(), task.version(), task.executionMode(),
                task.workspacePolicy(), plan.id(), plan.revision(), packages.size(), frozen, packages);
    }

    public PackageDetail packageDetail(String taskId, String runId) {
        TaskPackageRunRow run = requireRun(taskId, runId);
        var workPackage = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElseThrow(() ->
                new ConflictException("DESIGN_WORK_PACKAGE_MISSING", "工作包设计来源不存在"));
        var design = workPackage.designMessageId() == null ? null
                : mapper.findDesignerMessage(workPackage.designMessageId()).orElse(null);
        var fact = mapper.findPackageFactSnapshot(run.id()).orElse(null);
        return new PackageDetail(packageView(run), workPackage.objective(), workPackage.deliverablesJson(),
                workPackage.acceptanceIntentJson(), workPackage.compilerSummary(), workPackage.handoffSummary(),
                design == null ? null : design.content(), fact == null ? null : factView(fact));
    }

    public FactView fact(String taskId, String runId) {
        requireRun(taskId, runId);
        return mapper.findPackageFactSnapshot(runId).map(this::factView).orElseThrow(() ->
                new NotFoundException("Package fact not found: " + runId));
    }

    private TaskPackageRunRow requireRun(String taskId, String runId) {
        TaskPackageRunRow run = mapper.findTaskPackageRun(runId).orElseThrow(() ->
                new NotFoundException("Package run not found: " + runId));
        if (!taskId.equals(run.taskId())) throw new ConflictException("PACKAGE_TASK_MISMATCH", "工作包不属于该任务");
        return run;
    }

    private PackageView packageView(TaskPackageRunRow run) {
        var workPackage = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElse(null);
        return new PackageView(run.id(), run.packageKey(), run.ordinal(), run.title(), run.state(), run.version(),
                run.discussionRevision(), run.designRevision(), run.acceptedDesignRevision(),
                run.waitingReasonCode(), run.correctionOfPackageRunId(),
                workPackage == null ? List.of() : readStrings(workPackage.dependenciesJson()));
    }

    private List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.copyOf(json.readValue(value, new TypeReference<ArrayList<String>>() { })); }
        catch (JacksonException failure) {
            throw new ConflictException("PACKAGE_DEPENDENCIES_INVALID", "工作包依赖快照无法读取");
        }
    }

    private FactView factView(PackageFactSnapshotRow fact) {
        return new FactView(fact.id(), fact.packageRunId(), fact.checkpointId(), fact.successfulAttemptId(),
                fact.provenJson(), fact.acceptedContractJson(), fact.navigationSummary(), fact.createdAt());
    }

    public record Workbench(String taskId, String title, String taskState, long taskVersion,
                            String executionMode, String workspacePolicy, String planRevisionId,
                            int planRevision, int plannedPackageCount, int frozenPackageCount,
                            List<PackageView> packages) { }
    public record PackageView(String id, String packageKey, int ordinal, String title, String state,
                              long version, int discussionRevision, int designRevision,
                              Integer acceptedDesignRevision, String waitingReasonCode,
                              String correctionOfPackageRunId, List<String> dependencies) { }
    public record PackageDetail(PackageView packageRun, String objective, String deliverablesJson,
                                String acceptanceIntentJson, String compilerSummary, String handoffSummary,
                                String designMarkdown, FactView fact) { }
    public record FactView(String id, String packageRunId, String checkpointId, String successfulAttemptId,
                           String provenJson, String acceptedContractJson, String navigationSummary,
                           String createdAt) { }
}
