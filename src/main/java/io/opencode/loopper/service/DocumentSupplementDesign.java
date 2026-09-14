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

/** Replaces only a not-yet-executed design after the existing owner replacement proves its sessions stopped. */
@Service
public final class DocumentSupplementDesign {
    private final DocumentSupplementMapper supplements;
    private final DocumentRequirementMapper requirements;
    private final DocumentDevelopmentMapper bindings;
    private final LoopperMapper domain;
    private final DesignerSessionService designers;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public DocumentSupplementDesign(DocumentSupplementMapper supplements,DocumentRequirementMapper requirements,
            DocumentDevelopmentMapper bindings,LoopperMapper domain,DesignerSessionService designers,
            LifecycleTransitionService lifecycle,ObjectMapper json,PlatformTransactionManager manager) {
        this.supplements=supplements;this.requirements=requirements;this.bindings=bindings;this.domain=domain;
        this.designers=designers;this.lifecycle=lifecycle;this.json=json;this.transactions=new TransactionTemplate(manager);
    }
    public void advance(DocumentTemplateRunRow run,DocumentSupplementMapper.Supplement pending) {
        if(run.designerId()==null) return;
        var intent=supplements.design(run.id(),pending.requestKey()).orElseThrow(DocumentSupplementDesign::changed);
        if(!intent.designerId().equals(run.designerId()) || run.taskId()!=null) throw changed();
        reopen(intent);
        transactions.executeWithoutResult(ignored->freeze(run,intent));
    }
    public boolean stopForAnalysis(DocumentTemplateRunRow run) {
        var pending=supplements.pending(run.id()).orElse(null);
        if(pending==null || run.taskId()!=null) return false;
        var intent=supplements.design(run.id(),pending.requestKey()).orElse(null);
        if(intent==null || !intent.designerId().equals(run.designerId())) return false;
        reopen(intent); return true;
    }
    private void reopen(DocumentSupplementMapper.Design intent) {
        var current=domain.findCurrentDesignRequirementRevision(intent.designerId()).orElse(null);
        if(current!=null && current.id().equals(intent.targetRevisionId())) return;
        if(current!=null) {
            var owner=designers.get(intent.designerId());
            if(!current.id().equals(intent.sourceRevisionId()) || owner.taskId()!=null
                    || owner.discussionRevision()!=intent.discussionRevision()) throw changed();
            var profile=domain.findCurrentDesignerTaskProfile(owner.id()).orElseThrow(DocumentSupplementDesign::changed);
            if(!json.writeValueAsString(profile).equals(intent.profileJson())) throw changed();
            designers.reopenRequirement(owner.id(),owner.discussionRevision());
        }
    }
    private void freeze(DocumentTemplateRunRow run,DocumentSupplementMapper.Design intent) {
        if(!supplements.designInputCurrent(run.id(),run.version())) throw changed();
        var owner=designers.get(intent.designerId());
        var current=domain.findCurrentDesignRequirementRevision(owner.id()).orElse(null);
        if(current!=null) { if(!current.id().equals(intent.targetRevisionId())) throw changed(); return; }
        if(!owner.state().equals("REVIEWING") || owner.taskId()!=null || domain.findCurrentDesignerTaskProfile(owner.id()).isPresent()) throw changed();
        var old=domain.findDesignRequirementRevision(intent.sourceRevisionId()).orElseThrow(DocumentSupplementDesign::changed);
        var draft=domain.findDraft(owner.loopDraftId()).orElseThrow(DocumentSupplementDesign::changed);
        if(draft.version()!=old.sourceDraftVersion()) throw changed();
        var source=requirements.revision(run.id(),run.requirementRevision()).orElseThrow(DocumentSupplementDesign::changed);
        var segments=new ArrayList<DesignerSessionService.RequirementSegment>(); int after=-1;
        while(true) {
            var page=requirements.page(run.id(),source.revision(),after,100);
            for(var item:page) {
                if(!json.readTree(item.issuesJson()).isEmpty()) throw changed();
                segments.add(new DesignerSessionService.RequirementSegment(item.requirementKey(),item.title()));
            }
            if(page.size()<100) break;
            after=page.getLast().ordinal();
        }
        if(segments.isEmpty()) throw changed();
        String now=Instant.now().toString();
        int number=domain.listDesignRequirementRevisions(owner.id()).stream().mapToInt(DesignRequirementRevisionRow::revision).max().orElseThrow()+1;
        var revision=new DesignRequirementRevisionRow(intent.targetRevisionId(),owner.id(),number,old.sourceMessageId(),
                DocumentRequirementContext.index(run,source.manifestSha256(),segments.size()),json.writeValueAsString(segments),
                draft.version(),"ACTIVE",old.modelCallsUsed(),old.maxModelCalls(),now,now,0);
        lifecycle.create(new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION,revision.id(),
                LifecycleScopeType.PROJECT,run.projectId()),revision.state(),Map.of("templateRun",run.id(),"reason","SUPPLEMENT_REVIEWED"),
                ()->domain.insertDesignRequirementRevision(revision),DocumentSupplementDesign::changed);
        var p=json.readValue(intent.profileJson(),DesignerTaskProfileRow.class);
        var profile=new DesignerTaskProfileRow(UUID.randomUUID().toString(),owner.id(),revision.id(),"FROZEN",p.intent(),
                p.workflowTemplate(),p.mutationMode(),p.artifactKindsJson(),p.technologiesJson(),p.testPolicy(),p.executionStrategy(),
                p.rolePackId(),p.rolePackVersion(),p.confidence(),p.evidenceJson(),p.resolutionSource(),p.decisionRequired(),now,now,0,
                p.projectStackProfileId(),p.componentKeysJson(),p.stackFingerprint());
        if(domain.insertDesignerTaskProfile(profile)!=1 || bindings.insertDesign(new DocumentDevelopmentMapper.Design(revision.id(),
                run.id(),source.revision(),source.manifestSha256(),now))!=1) throw changed();
        designers.updateDesignerProjection(owner,DesignerSessionState.PENDING_HANDOFF,DesignWorkflowPhase.DECOMPOSING,
                null,"PENDING",owner.designRevision(),owner.redesignCount(),number,null);
    }
    private static ConflictException changed() { return new ConflictException("DOCUMENT_SUPPLEMENT_DESIGN_CHANGED",
            "未执行设计或需求版本已经变化，请检查设计后恢复；原设计和停止证明保持不变"); }
}
