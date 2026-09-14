package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Only a proven direct-package capacity failure may automatically reopen into the existing rolling workflow. */
@Service
public final class DocumentDevelopmentPromotion {
    private final DocumentTemplateAdmission admission;
    private final DocumentDevelopmentMapper bindings;
    private final LoopperMapper mapper;
    private final DesignerSessionService designers;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public DocumentDevelopmentPromotion(DocumentTemplateAdmission admission, DocumentDevelopmentMapper bindings,
            LoopperMapper mapper, DesignerSessionService designers, LifecycleTransitionService lifecycle,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.admission = admission; this.bindings = bindings; this.mapper = mapper; this.designers = designers;
        this.lifecycle = lifecycle; this.json = json; this.transactions = new TransactionTemplate(manager);
    }
    public boolean pending(String runId) {
        return bindings.promotion(runId).filter(intent -> bindings.design(intent.targetRevisionId()).isEmpty()).isPresent();
    }
    public void advance(String runId) {
        var intent = transactions.execute(status -> prepare(runId));
        var session = designers.get(intent.designerId());
        var current = mapper.findCurrentDesignRequirementRevision(session.id());
        if (current.isPresent() && current.get().id().equals(intent.targetRevisionId())) return;
        if (current.isPresent()) {
            if (!current.get().id().equals(intent.sourceRevisionId())) throw changed();
            // Existing owner replacement protocol proves every old role stopped before invalidating its design.
            designers.reopenRequirement(session.id(), session.discussionRevision());
        }
        transactions.executeWithoutResult(status -> freeze(intent));
    }
    private DocumentDevelopmentMapper.Promotion prepare(String id) {
        var run = requireRun(id);
        var existing = bindings.promotion(id);
        if (existing.isPresent()) return existing.get();
        var session = designers.get(run.designerId());
        var profile = mapper.findCurrentDesignerTaskProfile(session.id()).orElseThrow(DocumentDevelopmentPromotion::changed);
        var revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow(DocumentDevelopmentPromotion::changed);
        var workPackage = mapper.findLatestDesignWorkPackage(session.id(), "WP-1").orElseThrow(DocumentDevelopmentPromotion::changed);
        if (!session.state().equals("WAITING_INPUT") || !profile.workflowTemplate().equals("DIRECT_SOFTWARE_DESIGN")
                || !workPackage.requirementRevisionId().equals(revision.id()) || !"LARGE_TASK_MODE_REQUIRED".equals(workPackage.lastErrorCode())
                || bindings.design(revision.id()).isEmpty()) throw changed();
        var intent = new DocumentDevelopmentMapper.Promotion(revision.id(), id, session.id(), UUID.randomUUID().toString(),
                json.writeValueAsString(profile), Instant.now().toString());
        if (bindings.insertPromotion(intent) != 1) throw changed();
        return intent;
    }
    private void freeze(DocumentDevelopmentMapper.Promotion intent) {
        var run = requireRun(intent.runId()); var session = designers.get(intent.designerId());
        var existing = mapper.findCurrentDesignRequirementRevision(session.id());
        if (existing.isPresent()) { if (!existing.get().id().equals(intent.targetRevisionId())) throw changed(); return; }
        if (!session.state().equals("REVIEWING") || mapper.findCurrentDesignerTaskProfile(session.id()).isPresent()) throw changed();
        var old = mapper.findDesignRequirementRevision(intent.sourceRevisionId()).orElseThrow(DocumentDevelopmentPromotion::changed);
        var origin = bindings.design(old.id()).orElseThrow(DocumentDevelopmentPromotion::changed);
        if (!origin.runId().equals(run.id()) || origin.documentRevision() != run.basisRevision()) throw changed();
        var draft = mapper.findDraft(session.loopDraftId()).orElseThrow(DocumentDevelopmentPromotion::changed);
        if (draft.version() != old.sourceDraftVersion()) throw changed();
        String now = Instant.now().toString();
        int revisionNumber = mapper.listDesignRequirementRevisions(session.id()).stream().mapToInt(DesignRequirementRevisionRow::revision).max().orElseThrow() + 1;
        var revision = new DesignRequirementRevisionRow(intent.targetRevisionId(), session.id(), revisionNumber, old.sourceMessageId(),
                old.requirementText(), old.requirementSegmentsJson(), old.sourceDraftVersion(), "ACTIVE", old.modelCallsUsed(),
                old.maxModelCalls(), now, now, 0);
        lifecycle.create(new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, revision.id(),
                LifecycleScopeType.PROJECT, run.projectId()), revision.state(), Map.of("templateRun", run.id(), "reason", "SINGLE_PACKAGE_CAPACITY"),
                () -> mapper.insertDesignRequirementRevision(revision), DocumentDevelopmentPromotion::changed);
        var profile = largeProfile(json.readValue(intent.profileJson(), DesignerTaskProfileRow.class), revision.id(), now);
        if (mapper.insertDesignerTaskProfile(profile) != 1 || bindings.insertDesign(new DocumentDevelopmentMapper.Design(revision.id(),
                run.id(), origin.documentRevision(), origin.manifestSha256(), now)) != 1) throw changed();
        designers.updateDesignerProjection(session, DesignerSessionState.PENDING_HANDOFF, DesignWorkflowPhase.DECOMPOSING,
                null, "PENDING", session.designRevision(), session.redesignCount(), revisionNumber, null);
    }
    private DesignerTaskProfileRow largeProfile(DesignerTaskProfileRow previous, String revision, String now) {
        var evidence = new ArrayList<>(List.of(json.readValue(previous.evidenceJson(), String[].class)));
        evidence.add("template-auto-capacity-promotion=FULL_PACKAGE_DESIGN");
        return new DesignerTaskProfileRow(UUID.randomUUID().toString(), previous.designerSessionId(), revision, "FROZEN", "SOFTWARE_CHANGE",
                "FULL_PACKAGE_DESIGN", "WRITE_CODE", previous.artifactKindsJson(), previous.technologiesJson(), "REQUIRED", previous.executionStrategy(),
                previous.rolePackId(), previous.rolePackVersion(), previous.confidence(), json.writeValueAsString(evidence), "TEMPLATE_DECLARED", 0,
                now, now, 0, previous.projectStackProfileId(), previous.componentKeysJson(), previous.stackFingerprint());
    }
    private DocumentTemplateRunRow requireRun(String id) {
        var run = admission.require(id); var contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        if (!run.templateId().equals("REQUIREMENT_DEVELOPMENT") || !run.state().equals("DESIGNING")
                || run.taskId() != null || run.designerId() == null || !contract.autoDevelopment()) throw changed();
        return run;
    }
    private static ConflictException changed() {
        return new ConflictException("DOCUMENT_LARGE_MODE_NOT_ALLOWED", "只有冻结需求范围内已证明的单包容量不足才能自动切换大型流程；设计身份或范围已变化");
    }
}
