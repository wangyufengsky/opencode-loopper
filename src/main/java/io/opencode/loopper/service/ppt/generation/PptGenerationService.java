package io.opencode.loopper.service.ppt.generation;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptGenerationRows.Generation;
import io.opencode.loopper.service.assist.AssistRedaction;
import io.opencode.loopper.service.ppt.*;
import io.opencode.loopper.service.ppt.agent.PptAgentService;
import io.opencode.loopper.service.ppt.agent.PptDiscussionTranscript;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Explicit user commands authorize a bounded automatic workflow, never generic code tasks. */
@Service
public class PptGenerationService {
    private final PptGenerationMapper mapper;
    private final PptGenerationPersistence persistence;
    private final PptGenerationCoordinator coordinator;
    private final PptDocuments documents;
    private final PptAgentService agent;
    private final PptAgentMapper agents;
    private final PptDiscussionTranscript discussion;
    private final ObjectMapper json;
    public PptGenerationService(PptGenerationMapper mapper,PptGenerationPersistence persistence,PptGenerationCoordinator coordinator,
            PptDocuments documents,PptAgentService agent,PptAgentMapper agents,PptDiscussionTranscript discussion,ObjectMapper json) {
        this.mapper=mapper;this.persistence=persistence;this.coordinator=coordinator;this.documents=documents;
        this.agent=agent;this.agents=agents;this.discussion=discussion;this.json=json;
    }
    public record Generate(String idempotencyKey,long expectedRevision,String prompt) { }
    public record Confirm(String idempotencyKey,long expectedRevision) { }
    public record Resume(String idempotencyKey,long expectedRevision,String adjustment) {
        public Resume(String idempotencyKey,long expectedRevision) { this(idempotencyKey,expectedRevision,null); }
    }
    public record View(String id,String documentId,String state,String step,String detail,long revision,long version,
            String runId,String jobId,boolean canResume,String createdAt,String updatedAt,boolean requirementsConfirmed,
            PptGenerationPersistence.RecoveryView recovery) { }
    public View generate(String document,Generate input) {
        if(input==null)throw PptSupport.bad("PPT_GENERATION_INPUT","请输入制作要求");
        validate(input.idempotencyKey(),input.expectedRevision(),input.prompt());String sha=PptSupport.digest(input,json);
        var replay=persistence.replay(document,input.idempotencyKey(),sha,"GENERATE");if(replay!=null)return view(replay);
        var row=persistence.begin(row(document,input.idempotencyKey(),sha,input.prompt(),input.expectedRevision(),"CREATE",json.writeValueAsString(Map.of("kind","DOCUMENT")),false));
        coordinator.tick(row.id());return view(persistence.require(row.id()));
    }
    public View confirmRequirements(String document,Confirm input) {
        if(input==null)throw PptSupport.bad("PPT_GENERATION_INPUT","确认请求无效");
        PptSupport.key(input.idempotencyKey());if(input.expectedRevision()<0)throw PptSupport.bad("PPT_GENERATION_INPUT","作品版本无效");
        var snapshot=discussion.freeze(document);
        var identity=Map.of("idempotencyKey",input.idempotencyKey(),"expectedRevision",input.expectedRevision(),"transcript",snapshot.text());
        String sha=PptSupport.digest(identity,json);
        var replay=persistence.replay(document,input.idempotencyKey(),sha,"GENERATE");if(replay!=null)return view(replay);
        var doc=documents.get(document);
        if(!doc.phase().equals("BRIEFING")||doc.revision()!=input.expectedRevision())
            throw PptSupport.conflict("作品阶段或版本已变化，请刷新讨论后再确认");
        var row=row(document,input.idempotencyKey(),sha,snapshot.text(),input.expectedRevision(),"CREATE",
                json.writeValueAsString(Map.of("kind","DOCUMENT")),true);
        row=persistence.beginConfirmed(row,snapshot);
        coordinator.tick(row.id());return view(persistence.require(row.id()));
    }
    public View status(String document) { documents.get(document);return mapper.latest(document).map(this::view).orElse(null); }
    public View resume(String document,Resume input) {
        if(input==null)throw PptSupport.bad("PPT_GENERATION_INPUT","继续请求无效");PptSupport.key(input.idempotencyKey());
        if(input.expectedRevision()<0)throw PptSupport.bad("PPT_GENERATION_INPUT","作品版本无效");
        String sha=PptSupport.digest(input.adjustment()==null?Map.of("idempotencyKey",input.idempotencyKey(),"expectedRevision",input.expectedRevision()):input,json);var replay=persistence.replay(document,input.idempotencyKey(),sha,"RESUME");
        if(replay!=null)return view(replay);
        var row=mapper.latest(document).orElseThrow(()->PptSupport.bad("PPT_GENERATION_MISSING","没有可以继续的自动生成请求"));
        if(input.adjustment()!=null&&!input.adjustment().isBlank()) {
            validate(input.idempotencyKey(),input.expectedRevision(),input.adjustment());
            String prompt=row.prompt()+"\n\n用户最新调整（优先于原要求中相冲突部分）：\n"+input.adjustment();
            if(prompt.length()>PptDiscussionTranscript.MAX_CHARACTERS)throw PptSupport.bad("PPT_DISCUSSION_TOO_LONG","要求超过 100 万字，请拆分为新作品；原记录保留");
            var desired=row(document,input.idempotencyKey(),sha,prompt,input.expectedRevision(),row.mode(),row.scopeJson(),row.requirementsConfirmed());
            row=persistence.adjusted(row,desired,sha);
        }else row=persistence.resume(row,input.idempotencyKey(),sha,input.expectedRevision());
        coordinator.tick(row.id());return view(persistence.require(row.id()));
    }
    /** HTTP messages use the same endpoint and response shape for manual history and automatic revisions. */
    public PptAgentService.Message send(String document,PptAgentService.Send input) {
        if(input==null)throw PptSupport.bad("PPT_GENERATION_INPUT","请输入修改意见");
        validate(input.idempotencyKey(),input.expectedRevision(),input.text());var scope=agent.validateScope(input.scope());
        var normalized=new PptAgentService.Send(input.idempotencyKey(),input.text(),input.expectedRevision(),scope);
        String sha=PptSupport.digest(normalized,json);
        var existing=persistence.replay(document,input.idempotencyKey(),sha,"REVISE");
        if(existing!=null)return revisionMessage(existing);
        // Frozen pre-upgrade/manual requests remain exact replays even after the document phase changes.
        if(agents.replay(document,input.idempotencyKey()).isPresent())return agent.send(document,input);
        var doc=documents.get(document);
        if(!Set.of("REVIEW","EXPORTED").contains(doc.phase()))return agent.send(document,input);
        var row=persistence.begin(row(document,input.idempotencyKey(),sha,input.text(),input.expectedRevision(),"REVISE",json.writeValueAsString(scope),false));
        return revisionMessage(row);
    }
    private PptAgentService.Message revisionMessage(Generation row) {
        String originalKey=PptGenerationPersistence.agentKey(row.id(),0,"PRODUCING");
        var original=agents.replay(row.documentId(),originalKey);
        if(original.isPresent())return agent.message(original.get());
        if(PptGenerationState.valueOf(row.state()).terminal()||row.state().equals("STOPPING"))throw PptSupport.conflict("该修改请求已停止，请使用继续生成恢复");
        return agent.message(coordinator.dispatch(row));
    }
    private Generation row(String document,String key,String sha,String prompt,long revision,String mode,String scope,boolean requirementsConfirmed) {
        String id=UUID.randomUUID().toString(),now=Instant.now().toString(),step=mode.equals("CREATE")?"PLANNING":"PRODUCING";
        return new Generation(id,document,key,sha,prompt,mode,scope,revision,revision,step,step,0,
                PptGenerationPersistence.agentKey(id,0,step),null,null,null,null,"",0,now,now,requirementsConfirmed);
    }
    private View view(Generation row) {
        var doc=documents.get(row.documentId());
        return new View(row.id(),row.documentId(),row.state(),row.step(),row.detail(),doc.revision(),row.version(),row.runId(),row.jobId(),
                Set.of("STOPPED","FAILED").contains(row.state())&&!doc.archived()&&agents.activeCount(row.documentId())==0,
                row.createdAt(),row.updatedAt(),row.requirementsConfirmed(),persistence.recoveryView(row));
    }
    private static void validate(String key,long revision,String text) {
        PptSupport.key(key);
        if(revision<0||text==null||text.isBlank()||text.length()>24000||!text.equals(AssistRedaction.text(text)))
            throw PptSupport.bad("PPT_GENERATION_INPUT","请输入不超过 24000 字且不含内部凭证的制作要求");
    }
}
