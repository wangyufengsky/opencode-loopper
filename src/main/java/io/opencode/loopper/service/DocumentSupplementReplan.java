package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Reuses formal suffix planning. New source versions never mutate executed package designs. */
@Service
public final class DocumentSupplementReplan {
    private final DocumentSupplementMapper supplements;
    private final DocumentRequirementMapper requirements;
    private final LoopperMapper domain;
    private final RollingPackagePlanService plans;
    private final DocumentTemplateAdmission admission;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final DocumentSupplementDesign designs;
    public DocumentSupplementReplan(DocumentSupplementMapper supplements,DocumentRequirementMapper requirements,LoopperMapper domain,
            RollingPackagePlanService plans,DocumentTemplateAdmission admission,ObjectMapper json,PlatformTransactionManager manager,DocumentSupplementDesign designs) {
        this.supplements=supplements;this.requirements=requirements;this.domain=domain;this.plans=plans;
        this.admission=admission;this.json=json;this.transactions=new TransactionTemplate(manager);
        this.designs=designs;
    }
    public boolean advance(DocumentTemplateRunRow run) {
        var pending=supplements.pending(run.id()).orElse(null); if(pending==null) return false;
        if(pending.uploadReady()!=1 || run.basisRevision()<pending.targetRevision()) throw changed();
        if(!run.directDocuments() && !requirements.summaries(run.id(),run.basisRevision(),-1,1,true).isEmpty())
            throw new ConflictException("DOCUMENT_SUPPLEMENT_BUSINESS_INPUT","补充文档仍有业务歧义或冲突，请在需求清单中逐项回答后继续");
        if(run.taskId()==null) {
            designs.advance(run,pending);
            supplements.applied(run.id(),pending.requestKey(),Instant.now().toString()); return false;
        }
        var selected=pending.planId()==null?null:domain.findTaskPackagePlanRevision(pending.planId()).orElseThrow(DocumentSupplementReplan::changed);
        if(selected==null) {
            // A crash after durable proposal creation is recovered by its task, source and revision identity.
            var matching=domain.listTaskPackagePlanRevisions(run.taskId()).stream()
                    .filter(plan->plan.revision()>pending.planRevisionFloor() && !Set.of("FAILED","SUPERSEDED").contains(plan.state()) && domain.documentPlanSource(plan.id())
                            .map(source->source.documentRevision()==run.basisRevision()).orElse(false)).toList();
            if(matching.size()>1) throw changed();
            if(!matching.isEmpty()) selected=matching.getFirst();
            else {
                var task=domain.findTask(run.taskId()).orElseThrow(DocumentSupplementReplan::changed);
                if(task.version()!=pending.baseTaskVersion()) throw changed();
                selected=plans.beginSuggestion(task.id(),task.version(),pending.basePackageId(),pending.basePackageVersion()).revision();
            }
            if(supplements.plan(run.id(),pending.requestKey(),selected.id())!=1) throw changed();
        }
        if(!domain.documentPlanSourceCurrent(selected.id())) throw changed();
        if(selected.state().equals("GENERATING")) {
            if("DISCONNECTED".equals(selected.externalSessionState()))
                throw new ConflictException("DOCUMENT_SUPPLEMENT_TRANSPORT_WAIT","规划会话或送达状态尚未确认，请检查运行环境后从冻结输入恢复；不会重发未知请求");
            return true; // The existing persistent generation scheduler owns model I/O.
        }
        if(selected.state().equals("FAILED")) {
            int limit=json.readValue(run.contractJson(),DocumentTemplateService.Contract.class).maxStageAttempts();
            long used=domain.listTaskPackagePlanRevisions(run.taskId()).stream().filter(plan->plan.revision()>pending.planRevisionFloor()).count();
            if(used>=limit) throw new ConflictException("DOCUMENT_SUPPLEMENT_PLAN_FAILED","剩余计划生成预算已耗尽，已保存需求与失败记录，请保留证据后拆分任务");
            if(supplements.retryPlan(run.id(),pending.requestKey(),selected.id())!=1) throw changed();
            return true;
        }
        if(selected.state().equals("PROPOSED")) {
            var proposed=plans.proposals(run.taskId()).stream().filter(item->item.id().equals(pendingPlan(run.id()))).findFirst().orElseThrow(DocumentSupplementReplan::changed);
            var refs=new HashSet<String>();
            for(var pack:json.readValue(proposed.planJson(),RollingPackagePlanService.PlanPackage[].class)) refs.addAll(pack.requirementRefs());
            if(!refs.containsAll(domain.documentTaskRequirementRefs(run.taskId())))
                throw new ConflictException("DOCUMENT_SUPPLEMENT_COVERAGE","新版剩余计划尚未覆盖全部需求及最终回归，请在关联执行中调整计划");
            var task=domain.findTask(run.taskId()).orElseThrow(DocumentSupplementReplan::changed);
            plans.confirm(task.id(),selected.id(),task.version(),selected.version()); return true;
        }
        if(!selected.state().equals("ACTIVE")) throw changed();
        transactions.executeWithoutResult(ignored->{
            var current=admission.require(run.id()); if(current.version()!=run.version() || !current.state().equals("DESIGNING")) throw changed();
            if(supplements.applied(run.id(),pending.requestKey(),Instant.now().toString())!=1) throw changed();
            admission.transition(current,DocumentTemplateState.EXECUTING,LifecycleEvent.EXECUTE_DOCUMENT_REQUIREMENTS,null,null);
        });
        return true;
    }
    private String pendingPlan(String run) { return supplements.pending(run).orElseThrow(DocumentSupplementReplan::changed).planId(); }
    private static ConflictException changed() { return new ConflictException("DOCUMENT_SUPPLEMENT_BASE_CHANGED","补充文档分析期间关联任务或剩余计划已变化，请检查当前工作包和需求版本后恢复"); }
}
