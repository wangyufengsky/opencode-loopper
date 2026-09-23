package io.opencode.loopper.service;

import io.opencode.loopper.domain.SourceTemplateState;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceDevelopmentLink {
    private final SourceTemplateAdmission admission;
    private final LoopperMapper domain;
    public SourceDevelopmentLink(SourceTemplateAdmission admission, LoopperMapper domain) { this.admission = admission; this.domain = domain; }
    @Transactional
    public void attach(String id, String taskId) {
        var run = admission.require(id);
        if (!run.templateId().equals("UNIT_TEST_DEVELOPMENT") || run.designerId() == null) throw SourceTemplateAdmission.conflict();
        if (run.taskId() != null) { if (!run.taskId().equals(taskId)) throw SourceTemplateAdmission.conflict(); return; }
        var designer = domain.findDesignerSession(run.designerId()).orElseThrow();
        var task = domain.findTask(taskId).orElseThrow();
        if (!run.state().equals("DESIGNING") || !run.projectId().equals(task.projectId())
                || !Objects.equals(designer.loopDraftId(), task.loopDraftId()) || "TEMPLATE_REPORT".equals(task.executionMode()))
            throw SourceTemplateAdmission.conflict();
        if (domain.linkSourceTask(run.id(), run.version(), designer.id(), task.id(), Instant.now().toString()) != 1
                || domain.freezeSourceDevelopmentTask(task.id()) != 1) throw SourceTemplateAdmission.conflict();
        for (var plan : domain.listTaskPackagePlanRevisions(task.id())) domain.freezeSourceDevelopmentPlan(plan.id());
        admission.transition(admission.require(id), SourceTemplateState.EXECUTING, null, null, null);
    }
}
