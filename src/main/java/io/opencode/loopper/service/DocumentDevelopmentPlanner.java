package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Template-specific automatic actions; ordinary Designer auto-mode and its rolling approval policy stay unchanged. */
@Service
public final class DocumentDevelopmentPlanner {
    private final DocumentDevelopmentBootstrap bootstrap;
    private final DocumentDevelopmentDesign design;
    private final DocumentDevelopmentPromotion promotion;
    private final DocumentDevelopmentLink links;
    private final DesignerSessionService designers;
    private final LoopDraftService drafts;
    private final LoopperMapper mapper;
    public DocumentDevelopmentPlanner(DocumentDevelopmentBootstrap bootstrap, DocumentDevelopmentDesign design,
            DocumentDevelopmentPromotion promotion, DocumentDevelopmentLink links, DesignerSessionService designers,
            LoopDraftService drafts, LoopperMapper mapper) {
        this.bootstrap = bootstrap; this.design = design; this.promotion = promotion; this.links = links;
        this.designers = designers; this.drafts = drafts; this.mapper = mapper;
    }
    public Step advance(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        if (run.designerId() == null) { bootstrap.create(run, contract); return Step.progress(); }
        var session = designers.get(run.designerId());
        var task = session.taskId() == null ? mapper.findTaskByDraft(session.loopDraftId()).orElse(null)
                : mapper.findTask(session.taskId()).orElseThrow();
        if (task != null) { links.attach(run.id(), task.id()); return Step.progress(); }
        if (promotion.pending(run.id())) { promotion.advance(run.id()); return Step.progress(); }
        var profile = mapper.findCurrentDesignerTaskProfile(session.id()).orElseThrow();
        var revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow();
        var packages = mapper.listDesignWorkPackages(revision.id());
        var active = packages.stream().filter(item -> item.packageId().equals(session.activeWorkPackageId())).findFirst().orElse(null);
        if (active != null && session.state().equals("WAITING_INPUT") && "LARGE_TASK_MODE_REQUIRED".equals(active.lastErrorCode())
                && profile.workflowTemplate().equals("DIRECT_SOFTWARE_DESIGN")) {
            promotion.advance(run.id()); return Step.progress();
        }
        if (waiting(session))
            return new Step(true, "设计需要补充或恢复。请打开关联设计，处理业务问题或运行阻塞后继续。");
        if (active != null && session.workflowPhase().equals("REVIEWING_PACKAGE") && active.state().equals("REVIEWING")) {
            designers.approvePackageAutomatically(session.id(), active.packageId(), session.discussionRevision(), active.designRevision());
            return Step.progress();
        }
        if (session.workflowPhase().equals("FINAL_REVIEW") && designers.finalConfirmationEligible(session.id())) {
            var confirmed = drafts.confirm(session.loopDraftId(), run.title(), "AUTOMATION");
            links.attach(run.id(), confirmed.id()); return Step.progress();
        }
        if (packages.isEmpty() || packages.getFirst().state().equals("PENDING")) {
            if (profile.workflowTemplate().equals("DIRECT_SOFTWARE_DESIGN")) design.startSingle(session.id());
            else if (profile.workflowTemplate().equals("FULL_PACKAGE_DESIGN")) design.startLarge(session.id());
        }
        return Step.progress();
    }
    static boolean waiting(DesignerSessionRow session) {
        return Set.of("WAITING_INPUT", "SESSION_ERROR", "CANCELLED", "STOPPING").contains(session.state())
                || "QUESTIONING_PACKAGE".equals(session.workflowPhase()) || "WAITING_INPUT".equals(session.externalSessionState());
    }
    public record Step(boolean waiting, String message) {
        static Step progress() { return new Step(false, null); }
    }
}
