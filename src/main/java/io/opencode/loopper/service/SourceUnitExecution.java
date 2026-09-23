package io.opencode.loopper.service;

import io.opencode.loopper.domain.SourceTemplateState;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SourceTemplateContract;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Unit-test intake projects existing Designer/Task progress and never manufactures an execution verdict. */
@Service
public final class SourceUnitExecution implements SourceExecutionFlow {
    private final SourceTemplatePreparation preparation;
    private final SourceTestProfileService profiles;
    private final SourceUnitScopeGuard guard;
    private final SourceTemplateAdmission admission;
    private final SourceDevelopmentPlanner planner;
    private final TaskService tasks;
    private final TaskReadService reads;
    private final RollingPackageService rolling;
    private final LoopperMapper domain;
    private final SourceUnitEvidence evidence;
    private final DesignerTerminationService termination;
    private final RollingPackagePlanGenerationService generations;
    public SourceUnitExecution(SourceTemplatePreparation preparation, SourceTestProfileService profiles, SourceUnitScopeGuard guard,
            SourceTemplateAdmission admission, SourceDevelopmentPlanner planner, TaskService tasks, TaskReadService reads,
            RollingPackageService rolling, LoopperMapper domain, SourceUnitEvidence evidence,
            DesignerTerminationService termination, RollingPackagePlanGenerationService generations) {
        this.preparation = preparation; this.profiles = profiles; this.guard = guard; this.admission = admission;
        this.planner = planner; this.tasks = tasks; this.reads = reads; this.rolling = rolling; this.domain = domain;
        this.evidence = evidence; this.termination = termination; this.generations = generations;
    }
    @Override public boolean supports(String template) { return template.equals("UNIT_TEST_DEVELOPMENT"); }
    @Override public void advance(SourceTemplateRunRow run, SourceTemplateContract contract) {
        if (run.state().equals("PREPARING")) {
            var frozen = preparation.freeze(run.id()); profiles.prepare(frozen); guard.freeze(frozen);
            admission.transition(admission.require(run.id()), SourceTemplateState.DESIGNING, null, null, null);
            return;
        }
        try {
            var step = switch (run.state()) {
                case "DESIGNING" -> planner.advance(run, contract);
                case "EXECUTING" -> execution(run);
                case "REPORTING" -> { evidence.complete(run); yield DocumentDevelopmentPlanner.Step.progress(); }
                default -> throw SourceTemplateAdmission.conflict();
            };
            if (step.waiting()) waitForOwner(run, "SOURCE_DEVELOPMENT_WAIT", step.message());
        } catch (RuntimeException failure) {
            String code = failure instanceof BadRequestException known ? known.code()
                    : failure instanceof ConflictException known ? known.code()
                    : failure instanceof io.opencode.loopper.domain.TaskFailure known ? known.code() : "SOURCE_DEVELOPMENT_INTERRUPTED";
            String message = failure instanceof BadRequestException || failure instanceof ConflictException || failure instanceof io.opencode.loopper.domain.TaskFailure
                    ? failure.getMessage() : "单测开发协调未完成，请检查关联设计或执行的状态与恢复入口";
            waitForOwner(run, code, message);
        }
    }
    private DocumentDevelopmentPlanner.Step execution(SourceTemplateRunRow run) {
        var task = tasks.get(run.taskId());
        if (!task.projectId().equals(run.projectId())) throw SourceTemplateAdmission.conflict();
        if (Set.of("AWAITING_DECISION", "COMPLETED").contains(task.state())) {
            evidence.freeze(run); return DocumentDevelopmentPlanner.Step.progress();
        }
        var overview = reads.overview(task.id()); var current = overview.currentPackage(); var capabilities = overview.packageCapabilities();
        if (current != null && capabilities != null) {
            var pack = domain.findTaskPackageRun(current.id()).orElseThrow();
            if (capabilities.canApproveDesign()) rolling.approveDocumentDesign(task.id(), pack.id(), task.version(), pack.version(), pack.discussionRevision(), pack.designRevision());
            else if (capabilities.canStartPackage()) tasks.startRollingPackage(task.id(), pack.id(), task.version(), pack.version());
            else if (capabilities.canResumeDesign()) rolling.resumeDesign(task.id(), pack.id(), task.version(), pack.version());
            else if (Set.of("WAITING_INPUT", "PAUSED", "STOPPING", "CANCELLED", "FAILED").contains(task.state())) return waiting();
        } else if (Set.of("PENDING_START", "READY").contains(task.state())) tasks.start(task.id(), "AUTOMATION");
        else if (Set.of("WAITING_INPUT", "PAUSED", "STOPPING", "CANCELLED", "SUPERSEDED", "FAILED").contains(task.state())) return waiting();
        return DocumentDevelopmentPlanner.Step.progress();
    }
    private static DocumentDevelopmentPlanner.Step waiting() {
        return new DocumentDevelopmentPlanner.Step(true, "关联执行需要处理，请打开执行详情查看脏文件、测试失败、配置问题或停止证明；模板不会自动认定通过");
    }
    private void waitForOwner(SourceTemplateRunRow observed, String code, String message) {
        var current = admission.require(observed.id());
        if (current.version() == observed.version() && Set.of("DESIGNING", "EXECUTING", "REPORTING").contains(current.state()))
            admission.transition(current, SourceTemplateState.WAITING_INPUT, current.state(), code, message);
    }
    @Override public boolean stop(SourceTemplateRunRow run) {
        boolean cancel = "CANCELLED".equals(run.stopTarget());
        if (run.taskId() != null) {
            if (!generations.stopDocumentTask(run.taskId())) return false;
            var task = tasks.get(run.taskId());
            if (cancel) {
                if (task.state().equals("STOPPING")) task = tasks.continueCancellation(task.id());
                else if (task.state().equals("AWAITING_DECISION")) task = tasks.cancelDecision(task.id());
                else if (!Set.of("CANCELLED", "COMPLETED", "SUPERSEDED", "FAILED", "SUCCEEDED").contains(task.state())) task = tasks.cancel(task.id());
                if (!Set.of("CANCELLED", "COMPLETED", "SUPERSEDED", "FAILED", "SUCCEEDED").contains(task.state())) return false;
            } else {
                task = tasks.pause(task.id());
                if (!Set.of("PAUSED", "WAITING_INPUT", "AWAITING_DECISION", "CANCELLED", "COMPLETED").contains(task.state())) return false;
            }
            if (!tasks.writersStopped(task.id())) return false;
        }
        // Recoverable owner waits do not cancel or archive its design. Unexpected stop remains blocked.
        return run.designerId() == null || cancel && termination.stop(run.designerId(), false).complete();
    }
}
