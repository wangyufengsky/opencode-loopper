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
public final class SourceDevelopmentPromotion {
    private final SourceTemplateAdmission admission;
    private final SourceDevelopmentPromotionMapper bindings;
    private final LoopperMapper mapper;
    private final DesignerSessionService designers;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public SourceDevelopmentPromotion(SourceTemplateAdmission admission, SourceDevelopmentPromotionMapper bindings,
            LoopperMapper mapper, DesignerSessionService designers, LifecycleTransitionService lifecycle,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.admission = admission; this.bindings = bindings; this.mapper = mapper; this.designers = designers;
        this.lifecycle = lifecycle; this.json = json; this.transactions = new TransactionTemplate(manager);
    }
    public boolean pending(String runId) {
        return bindings.promotion(runId).filter(intent -> mapper.sourceDevelopmentDesign(intent.targetRevisionId(), intent.designerId()).isEmpty()).isPresent();
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
    private SourceDevelopmentPromotionMapper.Promotion prepare(String id) {
        var run = requireRun(id);
        var existing = bindings.promotion(id);
        if (existing.isPresent()) return existing.get();
        var session = designers.get(run.designerId());
        var profile = mapper.findCurrentDesignerTaskProfile(session.id()).orElseThrow(SourceDevelopmentPromotion::changed);
        var revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElseThrow(SourceDevelopmentPromotion::changed);
        var workPackage = mapper.findLatestDesignWorkPackage(session.id(), "WP-1").orElseThrow(SourceDevelopmentPromotion::changed);
        if (!session.state().equals("WAITING_INPUT") || !profile.workflowTemplate().equals("DIRECT_SOFTWARE_DESIGN")
                || !workPackage.requirementRevisionId().equals(revision.id()) || !"LARGE_TASK_MODE_REQUIRED".equals(workPackage.lastErrorCode())
                || mapper.sourceDevelopmentDesign(revision.id(), session.id()).isEmpty()) throw changed();
        var intent = new SourceDevelopmentPromotionMapper.Promotion(revision.id(), id, session.id(), UUID.randomUUID().toString(),
                json.writeValueAsString(profile), Instant.now().toString());
        if (bindings.insertPromotion(intent) != 1) throw changed();
        return intent;
    }
    private void freeze(SourceDevelopmentPromotionMapper.Promotion intent) {
        var run = requireRun(intent.runId()); var session = designers.get(intent.designerId());
        var existing = mapper.findCurrentDesignRequirementRevision(session.id());
        if (existing.isPresent()) { if (!existing.get().id().equals(intent.targetRevisionId())) throw changed(); return; }
        if (!session.state().equals("REVIEWING") || mapper.findCurrentDesignerTaskProfile(session.id()).isPresent()) throw changed();
        var old = mapper.findDesignRequirementRevision(intent.sourceRevisionId()).orElseThrow(SourceDevelopmentPromotion::changed);
        var origin = mapper.sourceDevelopmentDesign(old.id(), session.id()).orElseThrow(SourceDevelopmentPromotion::changed);
        if (!origin.runId().equals(run.id()) || !origin.manifestSha256().equals(json.readValue(run.snapshotJson(), io.opencode.loopper.template.SourceSnapshot.class).manifestSha256())) throw changed();
        var draft = mapper.findDraft(session.loopDraftId()).orElseThrow(SourceDevelopmentPromotion::changed);
        if (draft.version() != old.sourceDraftVersion()) throw changed();
        String now = Instant.now().toString();
        int revisionNumber = mapper.listDesignRequirementRevisions(session.id()).stream().mapToInt(DesignRequirementRevisionRow::revision).max().orElseThrow() + 1;
        var revision = new DesignRequirementRevisionRow(intent.targetRevisionId(), session.id(), revisionNumber, old.sourceMessageId(),
                old.requirementText(), old.requirementSegmentsJson(), old.sourceDraftVersion(), "ACTIVE", old.modelCallsUsed(),
                old.maxModelCalls(), now, now, 0);
        lifecycle.create(new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, revision.id(),
                LifecycleScopeType.PROJECT, run.projectId()), revision.state(), Map.of("templateRun", run.id(), "reason", "SINGLE_PACKAGE_CAPACITY"),
                () -> mapper.insertDesignRequirementRevision(revision), SourceDevelopmentPromotion::changed);
        var profile = largeProfile(json.readValue(intent.profileJson(), DesignerTaskProfileRow.class), revision.id(), now);
        if (mapper.insertDesignerTaskProfile(profile) != 1 || mapper.insertSourceDevelopmentDesign(new SourceDevelopmentContextMapper.SourceBinding(revision.id(),
                run.id(), origin.manifestSha256(), now)) != 1) throw changed();
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
    private SourceTemplateRunRow requireRun(String id) {
        var run = admission.require(id);
        if (!run.templateId().equals("UNIT_TEST_DEVELOPMENT") || !run.state().equals("DESIGNING")
                || run.taskId() != null || run.designerId() == null) throw changed();
        return run;
    }
    private static ConflictException changed() {
        return new ConflictException("SOURCE_LARGE_MODE_NOT_ALLOWED", "只有冻结源码范围内已证明的单包容量不足才能自动切换大型流程；设计身份或范围已变化");
    }
}
