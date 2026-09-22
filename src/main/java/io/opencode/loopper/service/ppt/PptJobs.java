package io.opencode.loopper.service.ppt;

import io.opencode.loopper.persistence.PptMapper;
import io.opencode.loopper.persistence.PptRows.*;
import io.opencode.loopper.ppt.*;
import io.opencode.loopper.service.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Durable snapshot jobs; only the committed artifact manifest is presented as output. */
@Service
public class PptJobs {
    private final PptMapper mapper;
    private final PptDocuments documents;
    private final PptResources resources;
    private final PptStorage storage;
    private final PptEngine engine;
    private final PptJobPersistence persistence;
    private final ObjectMapper json;
    private final PptEvents events;
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService workers=Executors.newFixedThreadPool(2,Thread.ofVirtual().name("ppt-render-",0).factory());
    public PptJobs(PptMapper mapper,PptDocuments documents,PptResources resources,PptStorage storage,PptEngine engine,
            PptJobPersistence persistence,ObjectMapper json,PptEvents events) {
        this.mapper=mapper;this.documents=documents;this.resources=resources;this.storage=storage;this.engine=engine;
        this.persistence=persistence;this.json=json;this.events=events;
    }
    public record Create(String kind,long revision,String slideId,String idempotencyKey) { }
    public record ArtifactView(String id,String slideId,String name,String mediaType,String url) { }
    public record View(String id,String documentId,String kind,long revision,String slideId,String state,int completed,
            int total,String detail,List<ArtifactView> artifacts,String createdAt) { }
    public View create(String document,Create input,Runnable guard) {
        if(input==null||!Set.of("PREVIEW","EXPORT").contains(Objects.toString(input.kind(),"")))throw PptSupport.bad("PPT_JOB_INVALID","作业类型必须为预览或导出");
        PptSupport.key(input.idempotencyKey());String digest=PptSupport.digest(input,json);
        guard.run();var old=mapper.jobReceipt(document,input.idempotencyKey());
        if(old.isPresent()) {if(!old.get().digest().equals(digest))throw PptSupport.conflict("作业标识已经用于不同请求");return view(old.get());}
        var doc=documents.require(document);if(doc.archived())throw PptSupport.bad("PPT_ARCHIVED","请先恢复归档作品");
        var snapshot=documents.snapshot(document,input.revision());var deck=json.readValue(snapshot.deckJson(),PptModel.Deck.class);
        if(deck.slides().isEmpty())throw PptSupport.bad("PPT_EMPTY","请先制作页面");
        if(input.slideId()!=null&&deck.slides().stream().noneMatch(s->s.id().equals(input.slideId())))throw new NotFoundException("页面不属于指定版本");
        if("EXPORT".equals(input.kind())) {
            if(!Set.of("REVIEW","EXPORTED").contains(doc.phase())||doc.revision()!=input.revision())throw PptSupport.bad("PPT_EXPORT_PHASE","请先完成制作检查，再导出当前版本；历史导出请从版本制品下载");
            if(input.slideId()!=null)throw PptSupport.bad("PPT_EXPORT_SCOPE","正式导出包含整份作品");documents.requireProductionComplete(document,snapshot);
        }
        String now=Instant.now().toString();int total="EXPORT".equals(input.kind())||input.slideId()!=null?1:deck.slides().size();
        var row=new Job(UUID.randomUUID().toString(),document,input.kind(),input.revision(),input.slideId(),"PREPARED",0,total,"",input.idempotencyKey(),digest,now,now,0);
        row=persistence.create(row,doc.version(),guard);events.publish(document,"jobs");return view(row);
    }
    public List<View> list(String document){documents.require(document);
        var grouped=mapper.recentArtifacts(document).stream().collect(java.util.stream.Collectors.groupingBy(Artifact::jobId));
        return mapper.jobs(document).stream().map(job->view(job,grouped.getOrDefault(job.id(),List.of()))).toList();}
    public View get(String document,String job){documents.require(document);return view(require(document,job));}
    public Job require(String document,String job){return mapper.job(document,job).orElseThrow(()->new NotFoundException("作业不属于当前作品"));}
    public Artifact artifact(String document,String id){documents.require(document);return mapper.artifact(document,id).orElseThrow(()->new NotFoundException("制品不属于当前作品"));}
    public byte[] download(String document,String id){var row=artifact(document,id);return storage.verified(document,row.storageKey(),row.bytes(),row.sha256());}
    public View cancel(String document,String job){var row=require(document,job);if(Set.of("PREPARED","RUNNING").contains(row.state()))persistence.state(row,"CANCELLED",row.completed(),"已取消作业，已完成页面保留");events.publish(document,"jobs");return get(document,job);}
    public View retry(String document,String job){documents.require(document);var row=require(document,job);
        if("FAILED".equals(row.state()))persistence.state(row,"PREPARED",row.completed(),"继续原版本，已完成页面经校验后保留");
        else if(!Set.of("PREPARED","RUNNING","COMPLETED").contains(row.state()))throw PptSupport.bad("PPT_JOB_RETRY_INVALID","取消的作业请重新创建");
        events.publish(document,"jobs");return get(document,job);}
    @Scheduled(fixedDelayString="${loopper.ppt-job-delay:1000}")
    public void tick() {
        for(var job:mapper.activeJobs())if(running.size()<2&&running.add(job.id())) workers.submit(()->{try{execute(job);}finally{running.remove(job.id());}});
    }
    /** Same entry is used after restart; each saved page is hash-verified and skipped. */
    public void execute(Job input) {
        Job job=require(input.documentId(),input.id());
        try {
            if("PREPARED".equals(job.state()))job=persistence.state(job,"RUNNING",job.completed(),"");
            if(!"RUNNING".equals(job.state()))return;
            var deck=documents.deck(job.documentId(),job.revision());String document=job.documentId();
            if("EXPORT".equals(job.kind())) {
                var snapshot=documents.snapshot(document,job.revision());documents.requireProductionComplete(document,snapshot);
                var settings=json.readTree(snapshot.planJson()).path("delivery");
                var output=settings.path("includeNotes").asBoolean(true)?deck:new PptModel.Deck(deck.title(),deck.width(),deck.height(),deck.theme(),
                        deck.slides().stream().map(s->new PptModel.Slide(s.id(),s.title(),s.section(),"",s.locked(),s.elements(),s.theme())).toList());
                String name=settings.path("fileName").asText(deck.title()+".pptx");
                if(name.isBlank()||name.length()>180||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl))name="presentation.pptx";
                if(!name.toLowerCase(Locale.ROOT).endsWith(".pptx"))name+=".pptx";
                produce(job,null,name,"application/vnd.openxmlformats-officedocument.presentationml.presentation",1,
                        ()->engine.exportPptx(output,id->resources.asset(document,id)));
            }else {
                int count=0;
                for(var slide:deck.slides())if(job.slideId()==null||job.slideId().equals(slide.id())) {
                    produce(job,slide.id(),"slide-"+slide.id()+".png","image/png",++count,()->engine.renderPng(deck,slide.id(),1.5,id->resources.asset(document,id)));
                }
            }
            var current=require(job.documentId(),job.id());
            if("RUNNING".equals(current.state()))persistence.state(current,"COMPLETED",current.total(),"");
        }catch(RuntimeException failure) {
            var current=require(job.documentId(),job.id());
            if("RUNNING".equals(current.state()))persistence.state(current,"FAILED",current.completed(),failure instanceof PptFailure?failure.getMessage():"制作失败，内容已保留；请检查字体、素材和页面问题后仅重试此作业");
        }finally {events.publish(job.documentId(),"jobs");}
    }
    private void produce(Job job,String slide,String name,String type,int count,java.util.function.Supplier<byte[]> create) {
        if(!"RUNNING".equals(require(job.documentId(),job.id()).state()))throw PptSupport.conflict("作业已取消");
        String id=UUID.nameUUIDFromBytes((job.id()+":"+name).getBytes(StandardCharsets.UTF_8)).toString();
        var existing=mapper.artifact(job.documentId(),id);
        if(existing.isPresent()){download(job.documentId(),id);return;}
        byte[] bytes=create.get();String key="jobs/"+job.id()+"/"+PptSupport.hash(bytes)+("image/png".equals(type)?".png":".pptx");
        storage.write(job.documentId(),key,bytes);
        var artifact=new Artifact(id,job.documentId(),job.id(),job.revision(),slide,name,type,key,PptSupport.hash(bytes),bytes.length,Instant.now().toString());
        persistence.artifact(job,artifact,count);events.publish(job.documentId(),"jobs");
    }
    private View view(Job job) {
        return view(job,mapper.artifacts(job.documentId(),job.id()));
    }
    private View view(Job job,List<Artifact> rows) {
        var artifacts=rows.stream().map(a->new ArtifactView(a.id(),a.slideId(),a.name(),a.mediaType(),
                "/api/ppt/documents/"+a.documentId()+"/artifacts/"+a.id())).toList();
        return new View(job.id(),job.documentId(),job.kind(),job.revision(),job.slideId(),job.state(),job.completed(),job.total(),job.detail(),artifacts,job.createdAt());
    }
    @jakarta.annotation.PreDestroy void shutdown(){workers.shutdownNow();}
}
