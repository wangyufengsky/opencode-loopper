package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.SourceTemplateContract;
import org.springframework.stereotype.Service;

/** Only source-template authorization advances these ordinary design/confirmation actions. */
@Service
public final class SourceDevelopmentPlanner {
    private final SourceDevelopmentBootstrap bootstrap;
    private final DocumentDevelopmentDesign design;
    private final SourceDevelopmentPromotion promotion;
    private final SourceDevelopmentLink links;
    private final DesignerSessionService designers;
    private final LoopDraftService drafts;
    private final LoopperMapper domain;
    public SourceDevelopmentPlanner(SourceDevelopmentBootstrap bootstrap, DocumentDevelopmentDesign design, SourceDevelopmentPromotion promotion, SourceDevelopmentLink links,
            DesignerSessionService designers, LoopDraftService drafts, LoopperMapper domain) {
        this.bootstrap = bootstrap; this.design = design; this.promotion = promotion; this.links = links; this.designers = designers; this.drafts = drafts; this.domain = domain;
    }
    public DocumentDevelopmentPlanner.Step advance(SourceTemplateRunRow run, SourceTemplateContract contract) {
        if (run.designerId() == null) { bootstrap.create(run, contract); return DocumentDevelopmentPlanner.Step.progress(); }
        var session = designers.get(run.designerId());
        var task = session.taskId() == null ? domain.findTaskByDraft(session.loopDraftId()).orElse(null) : domain.findTask(session.taskId()).orElseThrow();
        if (task != null) { links.attach(run.id(), task.id()); return DocumentDevelopmentPlanner.Step.progress(); }
        if (promotion.pending(run.id())) { promotion.advance(run.id()); return DocumentDevelopmentPlanner.Step.progress(); }
        var profile = domain.findCurrentDesignerTaskProfile(session.id()).orElseThrow();
        var revision = domain.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        var packages = domain.listDesignWorkPackages(revision.id());
        var active = packages.stream().filter(p -> p.packageId().equals(session.activeWorkPackageId())).findFirst().orElse(null);
        if (active != null && session.state().equals("WAITING_INPUT") && "LARGE_TASK_MODE_REQUIRED".equals(active.lastErrorCode())
                && profile.workflowTemplate().equals("DIRECT_SOFTWARE_DESIGN")) {
            promotion.advance(run.id()); return DocumentDevelopmentPlanner.Step.progress();
        }
        if (DocumentDevelopmentPlanner.waiting(session))
            return new DocumentDevelopmentPlanner.Step(true, "设计需要处理或恢复，请打开关联设计查看问题与失败证据");
        if (active != null && session.workflowPhase().equals("REVIEWING_PACKAGE") && active.state().equals("REVIEWING")) {
            designers.approvePackageAutomatically(session.id(), active.packageId(), session.discussionRevision(), active.designRevision());
        } else if (session.workflowPhase().equals("FINAL_REVIEW") && designers.finalConfirmationEligible(session.id())) {
            var confirmed = drafts.confirm(session.loopDraftId(), run.title(), "AUTOMATION");
            links.attach(run.id(), confirmed.id());
        } else if (packages.isEmpty() || packages.getFirst().state().equals("PENDING")) {
            if (profile.workflowTemplate().equals("FULL_PACKAGE_DESIGN")) design.startLarge(session.id());
            else design.startSingle(session.id());
        }
        return DocumentDevelopmentPlanner.Step.progress();
    }
}
