package io.opencode.loopper.api;

import io.opencode.loopper.service.RollingPackageReadService;
import io.opencode.loopper.service.RollingPackageService;
import io.opencode.loopper.service.RollingPackagePlanService;
import io.opencode.loopper.service.RollingPackagePlanGenerationService;
import io.opencode.loopper.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/tasks/{taskId}/packages")
public class RollingPackageController {
    private final RollingPackageReadService reads;
    private final RollingPackageService packages;
    private final TaskService tasks;
    private final RollingPackagePlanService plans;
    private final RollingPackagePlanGenerationService planGenerations;

    public RollingPackageController(RollingPackageReadService reads, RollingPackageService packages,
                                    TaskService tasks, RollingPackagePlanService plans,
                                    RollingPackagePlanGenerationService planGenerations) {
        this.reads = reads;
        this.packages = packages;
        this.tasks = tasks;
        this.plans = plans;
        this.planGenerations = planGenerations;
    }

    @GetMapping public RollingPackageReadService.Workbench workbench(@PathVariable String taskId) {
        return reads.workbench(taskId);
    }

    @GetMapping("/{runId}")
    public RollingPackageReadService.PackageDetail packageDetail(@PathVariable String taskId,
                                                                 @PathVariable String runId) {
        return reads.packageDetail(taskId, runId);
    }

    @GetMapping("/{runId}/fact")
    public RollingPackageReadService.FactView fact(@PathVariable String taskId, @PathVariable String runId) {
        return reads.fact(taskId, runId);
    }

    @PostMapping("/{runId}/start")
    public ResponseEntity<Void> start(@PathVariable String taskId, @PathVariable String runId,
                                      @Valid @RequestBody VersionRequest request) {
        requireVersions(taskId, runId, request);
        tasks.startRollingPackage(taskId, runId, request.expectedTaskVersion(), request.expectedPackageVersion());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{runId}/messages")
    public ResponseEntity<Void> discuss(@PathVariable String taskId, @PathVariable String runId,
                                        @Valid @RequestBody MessageRequest request) {
        requireVersions(taskId, runId, request);
        packages.discuss(taskId, runId, request.expectedTaskVersion(), request.expectedPackageVersion(),
                request.expectedDiscussionRevision(), request.expectedDesignRevision(), request.content());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{runId}/approve-design")
    public ResponseEntity<Void> approve(@PathVariable String taskId, @PathVariable String runId,
                                        @Valid @RequestBody DesignRevisionRequest request) {
        requireVersions(taskId, runId, request);
        packages.approveDesign(taskId, runId, request.expectedTaskVersion(), request.expectedPackageVersion(),
                request.expectedDiscussionRevision(), request.expectedDesignRevision());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runId}/redesign")
    public ResponseEntity<Void> redesign(@PathVariable String taskId, @PathVariable String runId,
                                         @Valid @RequestBody VersionRequest request) {
        requireVersions(taskId, runId, request);
        packages.redesign(taskId, runId, request.expectedTaskVersion(), request.expectedPackageVersion());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{runId}/resume-design")
    public ResponseEntity<Void> resumeDesign(@PathVariable String taskId, @PathVariable String runId,
                                             @Valid @RequestBody VersionRequest request) {
        requireVersions(taskId, runId, request);
        packages.resumeDesign(taskId, runId, request.expectedTaskVersion(), request.expectedPackageVersion());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{runId}/retry-checkpoint")
    public ResponseEntity<Void> retryCheckpoint(@PathVariable String taskId, @PathVariable String runId,
                                                @Valid @RequestBody VersionRequest request) {
        requireVersions(taskId, runId, request);
        packages.retryCheckpointRelease(taskId, runId, request.expectedTaskVersion(),
                request.expectedPackageVersion());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{runId}/continue-failure")
    public ResponseEntity<Void> continueFailure(@PathVariable String taskId, @PathVariable String runId,
                                                @Valid @RequestBody FailureActionRequest request) {
        requireVersions(taskId, runId, request);
        switch (request.action()) {
            case "CONTINUE_CANDIDATE" -> tasks.retryRollingPackageCandidate(taskId, runId,
                    request.expectedTaskVersion(), request.expectedPackageVersion());
            case "REDESIGN_FROM_PREVIOUS" -> packages.redesign(taskId, runId,
                    request.expectedTaskVersion(), request.expectedPackageVersion());
            case "ABANDON_TASK" -> tasks.cancel(taskId);
            default -> throw new io.opencode.loopper.service.BadRequestException(
                    "PACKAGE_FAILURE_ACTION_INVALID", "未知的失败工作包处置动作");
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/plan-revisions")
    public List<RollingPackagePlanService.Proposal> planRevisions(@PathVariable String taskId) {
        return plans.proposals(taskId);
    }

    @PostMapping("/plan-revisions")
    public RollingPackagePlanService.Proposal proposePlan(@PathVariable String taskId,
                                                          @Valid @RequestBody PlanProposalRequest request) {
        requireVersions(taskId, request.expectedPackageRunId(), request);
        return plans.propose(taskId, request.expectedTaskVersion(), request.packages());
    }

    @PostMapping("/plan-revisions/suggest")
    public RollingPackagePlanService.Proposal suggestPlan(@PathVariable String taskId,
                                                          @Valid @RequestBody SuggestPlanRequest request) {
        requireVersions(taskId, request.expectedPackageRunId(), request);
        return planGenerations.suggest(taskId, request.expectedTaskVersion(), request.expectedPackageRunId(),
                request.expectedPackageVersion());
    }

    @PostMapping("/plan-revisions/{proposalId}/confirm")
    public RollingPackagePlanService.Proposal confirmPlan(@PathVariable String taskId,
                                                          @PathVariable String proposalId,
                                                          @Valid @RequestBody ConfirmPlanRequest request) {
        requireVersions(taskId, request.expectedPackageRunId(), request);
        return plans.confirm(taskId, proposalId, request.expectedTaskVersion(), request.expectedProposalVersion());
    }

    @PostMapping("/corrections")
    public RollingPackagePlanService.Proposal correction(@PathVariable String taskId,
                                                         @Valid @RequestBody CorrectionRequest request) {
        requireVersions(taskId, request.correctionOfPackageRunId(), request);
        return plans.correction(taskId, request.expectedTaskVersion(), request.correctionOfPackageRunId(),
                request.title(), request.objective());
    }

    private void requireVersions(String taskId, String runId, PackageVersionRequest request) {
        var workbench = reads.workbench(taskId);
        var run = workbench.packages().stream().filter(item -> runId.equals(item.id())).findFirst()
                .orElseThrow(() -> new io.opencode.loopper.service.ConflictException(
                        "PACKAGE_TASK_MISMATCH", "工作包不属于该任务或已不在当前事实/计划中"));
        if (workbench.taskVersion() != request.expectedTaskVersion()
                || run.version() != request.expectedPackageVersion()
                || run.discussionRevision() != request.expectedDiscussionRevision()
                || run.designRevision() != request.expectedDesignRevision()) {
            throw new io.opencode.loopper.service.ConflictException(
                    "PACKAGE_COMMAND_VERSION_CONFLICT", "任务、工作包或设计修订已更新，请刷新后重试");
        }
    }

    private interface PackageVersionRequest {
        long expectedTaskVersion();
        long expectedPackageVersion();
        int expectedDiscussionRevision();
        int expectedDesignRevision();
    }
    public record VersionRequest(long expectedTaskVersion, long expectedPackageVersion,
                                 int expectedDiscussionRevision, int expectedDesignRevision)
            implements PackageVersionRequest { }
    public record DesignRevisionRequest(long expectedTaskVersion, long expectedPackageVersion,
                                        int expectedDiscussionRevision, int expectedDesignRevision)
            implements PackageVersionRequest { }
    public record MessageRequest(long expectedTaskVersion, long expectedPackageVersion,
                                 int expectedDiscussionRevision, int expectedDesignRevision,
                                 @NotBlank @Size(max = 12_000) String content) implements PackageVersionRequest { }
    public record FailureActionRequest(long expectedTaskVersion, long expectedPackageVersion,
                                       int expectedDiscussionRevision, int expectedDesignRevision,
                                       @NotBlank String action) implements PackageVersionRequest { }
    public record PlanProposalRequest(long expectedTaskVersion, @NotBlank String expectedPackageRunId,
                                      long expectedPackageVersion, int expectedDiscussionRevision,
                                      int expectedDesignRevision,
                                      List<RollingPackagePlanService.PlanPackage> packages)
            implements PackageVersionRequest { }
    public record SuggestPlanRequest(long expectedTaskVersion, @NotBlank String expectedPackageRunId,
                                     long expectedPackageVersion, int expectedDiscussionRevision,
                                     int expectedDesignRevision) implements PackageVersionRequest { }
    public record ConfirmPlanRequest(long expectedTaskVersion, @NotBlank String expectedPackageRunId,
                                     long expectedPackageVersion, int expectedDiscussionRevision,
                                     int expectedDesignRevision, long expectedProposalVersion)
            implements PackageVersionRequest { }
    public record CorrectionRequest(long expectedTaskVersion, @NotBlank String correctionOfPackageRunId,
                                    long expectedPackageVersion, int expectedDiscussionRevision,
                                    int expectedDesignRevision, @Size(max = 120) String title,
                                    @Size(max = 2_000) String objective) implements PackageVersionRequest { }
}
