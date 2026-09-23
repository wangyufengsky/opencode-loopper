package io.opencode.loopper.service.ppt.generation;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.persistence.PptGenerationRows.Generation;
import io.opencode.loopper.service.ppt.*;
import io.opencode.loopper.service.ppt.agent.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Restarts replay durable identities; model I/O remains owned by the existing agent coordinator. */
@Service
public class PptGenerationCoordinator {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PptGenerationCoordinator.class);
    private final PptGenerationMapper mapper;
    private final PptGenerationPersistence persistence;
    private final PptAgentMapper agents;
    private final PptAgentService agent;
    private final PptDocuments documents;
    private final PptJobs jobs;
    private final ObjectMapper json;
    private final PptEvents events;
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    public PptGenerationCoordinator(PptGenerationMapper mapper,PptGenerationPersistence persistence,PptAgentMapper agents,
            PptAgentService agent,PptDocuments documents,PptJobs jobs,ObjectMapper json,PptEvents events) {
        this.mapper=mapper;this.persistence=persistence;this.agents=agents;this.agent=agent;
        this.documents=documents;this.jobs=jobs;this.json=json;this.events=events;
    }
    @Scheduled(fixedDelayString="${loopper.ppt-generation-delay:1000}")
    public void monitor() { mapper.activeRows().forEach(row->tick(row.id())); }
    public void tick(String id) {
        if(!running.add(id))return;
        long observedVersion=-1;
        try {
            var row=persistence.require(id);
            observedVersion=row.version();
            if(PptGenerationState.valueOf(row.state()).terminal())return;
            if(row.state().equals("STOPPING")){stop(row);return;}
            if(Set.of("PLANNING","PRODUCING").contains(row.step()))model(row);else output(row);
        }catch(RuntimeException failure) {
            var row=persistence.require(id);
            String safe=io.opencode.loopper.service.assist.AssistRedaction.text(Objects.toString(failure.getMessage(),""));
            safe=safe.replaceAll("[\\r\\n]"," ");if(safe.length()>300)safe=safe.substring(0,300);
            log.warn("PPT generation {} step={} failure={} detail={}",row.id(),row.step(),failure.getClass().getSimpleName(),safe);
            if(!PptGenerationState.valueOf(row.state()).terminal()&&!row.state().equals("STOPPING")) {
                String detail=failure instanceof io.opencode.loopper.service.BadRequestException||failure instanceof io.opencode.loopper.service.ConflictException
                        ?io.opencode.loopper.service.assist.AssistRedaction.text(failure.getMessage()):"自动生成暂未完成，已保存内容保留；请重试或检查运行环境";
                if(detail.length()>1200)detail=detail.substring(0,1200);
                persistence.state(row,agents.activeCount(row.documentId())==0?PptGenerationState.FAILED:PptGenerationState.valueOf(row.state()),detail);
            }
        }finally {
            running.remove(id);var latest=mapper.get(id);
            if(latest.isPresent()&&latest.get().version()!=observedVersion)events.publish(latest.get().documentId(),"generation");
        }
    }
    public Run dispatch(Generation row) {
        persistence.guard(row);
        var existing=agents.replay(row.documentId(),row.agentKey());
        if(existing.isPresent())return bind(row,existing.get());
        if(row.step().equals("PLANNING")&&!documents.get(row.documentId()).phase().equals("BRIEFING")) {
            documents.action(row.documentId(),"reopen",new PptDocuments.Action(key(row,"reopen"),row.dispatchRevision(),null,null),()->persistence.guard(row));
        }
        var auth=new PptAgentWorkflowGate.Authorization(row.id(),row.attempt(),row.step(),row.mode(),row.requirementsConfirmed());
        var input=new PptAgentService.Send(row.agentKey(),row.prompt(),row.dispatchRevision(),json.readTree(row.scopeJson()));
        var message=agent.sendAutomatic(row.documentId(),input,auth);
        return bind(persistence.require(row.id()),agents.run(message.id()).orElseThrow());
    }
    private Run bind(Generation row,Run run) { if(row.runId()==null)persistence.bindRun(row,run);return run; }
    private void model(Generation original) {
        var run=original.runId()==null?dispatch(original):agents.run(original.runId()).orElseThrow();
        var row=persistence.require(original.id());
        if(run.state().equals("WAITING_INPUT")) {persistence.state(row,PptGenerationState.WAITING_INPUT,"请回答 PPT 助手的关键问题后自动继续");return;}
        if(row.state().equals("WAITING_INPUT"))row=persistence.state(row,PptGenerationState.valueOf(row.step()),"已收到回答，继续生成");
        if(Set.of("STOPPED","FAILED").contains(run.state())) {
            if(run.stopProof()==null||run.stopProof().isBlank())return;
            if(run.state().equals("STOPPED")){persistence.cancel(row.documentId());stop(persistence.require(row.id()));}
            else persistence.state(row,PptGenerationState.FAILED,"模型本轮失败，保存的方案与页面保留，可以继续");
            return;
        }
        if(!run.state().equals("COMPLETED"))return;
        row=persistence.freeze(row,run);
        if(row.step().equals("PLANNING"))planningComplete(row,run);else productionComplete(row,run);
    }
    private void planningComplete(Generation row,Run run) {
        PptRequirements.requireConfirmed(run, agents.questions(run.id()), json);
        PptAutomaticPlan.validate(json.readTree(documents.snapshot(row.documentId(),row.outputRevision()).planJson()));
        for(String action:List.of("finish-planning","confirm-direction","start-production")) {
            String phase=documents.get(row.documentId()).phase();
            if(action.equals("finish-planning")&&!phase.equals("BRIEFING")||action.equals("confirm-direction")&&!phase.equals("DIRECTION")
                    ||action.equals("start-production")&&!phase.equals("DESIGN"))continue;
            documents.action(row.documentId(),action,new PptDocuments.Action(key(row,action),row.outputRevision(),null,null,false),
                    ()->{persistence.completed(row,run);persistence.guard(row);});
        }
        if(!documents.get(row.documentId()).phase().equals("PRODUCING"))throw PptSupport.conflict("作品阶段已被修改，请刷新后继续");
        persistence.advance(persistence.require(row.id()),PptGenerationState.PRODUCING);
    }
    private void productionComplete(Generation row,Run run) {
        documents.requireProductionComplete(row.documentId(),documents.snapshot(row.documentId(),row.outputRevision()));
        if(documents.get(row.documentId()).phase().equals("PRODUCING"))
            documents.action(row.documentId(),"finish-production",new PptDocuments.Action(key(row,"finish-production"),row.outputRevision(),null,null),
                    ()->{persistence.completed(row,run);persistence.guard(row);});
        if(!Set.of("REVIEW","EXPORTED").contains(documents.get(row.documentId()).phase()))throw PptSupport.conflict("作品阶段已变化");
        persistence.advance(persistence.require(row.id()),PptGenerationState.PREVIEW);
    }
    private void output(Generation row) {
        persistence.guard(row);persistence.noWriter(row.documentId());
        if(row.jobId()==null) {
            final var original=row;
            var job=jobs.create(row.documentId(),new PptJobs.Create(row.step(),row.outputRevision(),null,key(row,"output")),
                    ()->{persistence.guard(original);persistence.noWriter(original.documentId());},created->persistence.bindJob(original,created));
            row=persistence.require(row.id());
        }
        var job=jobs.get(row.documentId(),row.jobId());
        if(Set.of("FAILED","CANCELLED").contains(job.state())) {persistence.state(row,PptGenerationState.FAILED,"输出作业未完成，已成功页面保留；请继续生成");return;}
        if(!job.state().equals("COMPLETED"))return;
        if(job.revision()!=row.outputRevision()||job.artifacts().isEmpty())throw PptSupport.conflict("输出制品与冻结版本不匹配");
        // Hash reads are outside the final transaction; the job's immutable manifest is rechecked below.
        for(var artifact:job.artifacts())jobs.download(row.documentId(),artifact.id());
        if(row.step().equals("PREVIEW"))persistence.advance(row,PptGenerationState.EXPORT);
        else {
            for(var artifact:jobs.get(row.documentId(),row.previewJobId()).artifacts())jobs.download(row.documentId(),artifact.id());
            persistence.completeOutput(row,job.id());
        }
    }
    private void stop(Generation row) {
        if(agents.activeCount(row.documentId())!=0){agent.stop(row.documentId());return;}
        if(row.jobId()!=null)jobs.cancel(row.documentId(),row.jobId());
        persistence.state(persistence.require(row.id()),PptGenerationState.STOPPED,"已停止自动生成，方案、页面与历史文件保留");
    }
    private static String key(Generation row,String suffix) { return row.agentKey()+"_"+suffix; }
}
