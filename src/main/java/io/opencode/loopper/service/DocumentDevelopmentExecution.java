package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Executes only the template's pre-authorized normal actions through the existing Task/rolling command boundaries. */
@Service
public final class DocumentDevelopmentExecution {
    private final TaskService tasks;
    private final RollingPackageService rolling;
    private final TaskReadService reads;
    private final LoopperMapper domain;
    private final DocumentDevelopmentEvidence evidence;
    public DocumentDevelopmentExecution(TaskService tasks, RollingPackageService rolling, TaskReadService reads,
            LoopperMapper domain, DocumentDevelopmentEvidence evidence) {
        this.tasks = tasks; this.rolling = rolling; this.reads = reads; this.domain = domain; this.evidence = evidence;
    }
    public DocumentDevelopmentPlanner.Step advance(DocumentTemplateRunRow run) {
        var task = tasks.get(run.taskId());
        if (!task.projectId().equals(run.projectId()) || "TEMPLATE_REPORT".equals(task.executionMode()))
            throw new ConflictException("DOCUMENT_TASK_SOURCE_CHANGED", "关联执行任务不属于本次需求开发");
        if (Set.of("AWAITING_DECISION", "COMPLETED").contains(task.state())) {
            evidence.freeze(run); return DocumentDevelopmentPlanner.Step.progress();
        }
        var overview = reads.overview(task.id()); var current = overview.currentPackage(); var capabilities = overview.packageCapabilities();
        if (current != null && capabilities != null) {
            var pack = domain.findTaskPackageRun(current.id()).orElseThrow();
            if (capabilities.canApproveDesign()) {
                rolling.approveDocumentDesign(task.id(), pack.id(), task.version(), pack.version(), pack.discussionRevision(), pack.designRevision());
                return DocumentDevelopmentPlanner.Step.progress();
            }
            if (capabilities.canStartPackage()) {
                tasks.startRollingPackage(task.id(), pack.id(), task.version(), pack.version());
                return DocumentDevelopmentPlanner.Step.progress();
            }
            if (capabilities.canResumeDesign()) {
                rolling.resumeDesign(task.id(), pack.id(), task.version(), pack.version());
                return DocumentDevelopmentPlanner.Step.progress();
            }
        } else if (Set.of("PENDING_START", "READY").contains(task.state())) {
            tasks.start(task.id(), "AUTOMATION"); return DocumentDevelopmentPlanner.Step.progress();
        }
        boolean designWaiting = run.designerId() != null && domain.findDesignerSession(run.designerId())
                .map(DocumentDevelopmentPlanner::waiting).orElse(false);
        if (designWaiting || Set.of("WAITING_INPUT", "PAUSED", "STOPPING", "CANCELLED", "SUPERSEDED", "FAILED").contains(task.state()))
            return new DocumentDevelopmentPlanner.Step(true, "关联执行需要处理后继续。请在执行详情处理业务待决、脏文件、验证失败或停止证明；模板不会自动采用答案或认定失败通过。");
        return DocumentDevelopmentPlanner.Step.progress();
    }
}
