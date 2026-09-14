package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task creation and Start stay separate; only an already confirmed Task can replace intake design progress. */
@Service
public class DocumentDevelopmentLink {
    private final DocumentTemplateAdmission admission;
    private final DocumentDevelopmentMapper bindings;
    private final LoopperMapper domain;
    public DocumentDevelopmentLink(DocumentTemplateAdmission admission, DocumentDevelopmentMapper bindings, LoopperMapper domain) {
        this.admission = admission; this.bindings = bindings; this.domain = domain;
    }
    @Transactional
    public DocumentTemplateRunRow attach(String runId, String taskId) {
        var run = admission.require(runId);
        if (!run.templateId().equals("REQUIREMENT_DEVELOPMENT") || run.designerId() == null) throw conflict();
        if (run.taskId() != null) {
            if (!run.taskId().equals(taskId)) throw conflict();
            return run;
        }
        var designer = domain.findDesignerSession(run.designerId()).orElseThrow(DocumentDevelopmentLink::conflict);
        var task = domain.findTask(taskId).orElseThrow(DocumentDevelopmentLink::conflict);
        if (!run.state().equals("DESIGNING") || !run.projectId().equals(task.projectId())
                || !java.util.Objects.equals(designer.loopDraftId(), task.loopDraftId())
                || "TEMPLATE_REPORT".equals(task.executionMode())) throw conflict();
        if (bindings.linkTask(run.id(), run.version(), designer.id(), task.id(), Instant.now().toString()) != 1) throw conflict();
        if (domain.freezeDocumentTaskSource(task.id()) != 1) throw conflict();
        return admission.transition(admission.require(run.id()), DocumentTemplateState.EXECUTING,
                LifecycleEvent.EXECUTE_DOCUMENT_REQUIREMENTS, null, null);
    }
    private static ConflictException conflict() {
        return new ConflictException("DOCUMENT_DEVELOPMENT_TASK_CONFLICT", "需求开发只能关联自身设计确认的执行任务，不能改变来源或借用报告执行策略");
    }
}
