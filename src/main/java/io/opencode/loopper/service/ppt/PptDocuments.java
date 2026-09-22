package io.opencode.loopper.service.ppt;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.PptMapper;
import io.opencode.loopper.persistence.PptRows.*;
import io.opencode.loopper.ppt.*;
import io.opencode.loopper.ppt.PptModel.*;
import io.opencode.loopper.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class PptDocuments {
    private final PptMapper mapper;
    private final PptPersistence persistence;
    private final PptEngine engine;
    private final ObjectMapper json;
    private final LoopperProperties properties;
    private final ProjectService projects;
    private final SettingsService settings;
    private final PptEvents events;
    public PptDocuments(PptMapper mapper,PptPersistence persistence,PptEngine engine,ObjectMapper json,
            LoopperProperties properties,ProjectService projects,SettingsService settings,PptEvents events) {
        this.mapper=mapper;this.persistence=persistence;this.engine=engine;this.json=json;
        this.properties=properties;this.projects=projects;this.settings=settings;this.events=events;
    }
    public record Create(String id,String title,String projectId,String model) { }
    public record View(String id,String title,String projectId,String model,String phase,long revision,long version,
            boolean archived,String createdAt,String updatedAt) { }
    public record Edit(String idempotencyKey,long expectedRevision,List<JsonNode> operations) { }
    public record PlanEdit(String idempotencyKey,long expectedRevision,JsonNode plan) { }
    public record Action(String idempotencyKey,long expectedRevision,Boolean archived,Long targetRevision,Boolean useAgent) {
        public Action(String key,long revision,Boolean archived,Long targetRevision){this(key,revision,archived,targetRevision,null);}
    }
    public record RevisionView(long revision,String reason,String createdAt) { }

    public View create(Create input) {
        if(input==null||input.id()==null||!input.id().matches("[a-fA-F0-9-]{36}")||input.title()==null||input.title().isBlank()||input.title().length()>120)
            throw PptSupport.bad("PPT_INPUT_INVALID","请填写不超过 120 字的作品名称");
        String digest=PptSupport.digest(input,json);
        var old=mapper.document(input.id());
        if(old.isPresent()) {
            if(!old.get().createDigest().equals(digest)) throw PptSupport.conflict("创建标识已用于其他作品");
            return view(old.get());
        }
        String project=input.projectId()==null||input.projectId().isBlank()?null:input.projectId();
        if(project!=null) projects.get(project);
        String model=input.model()==null||input.model().isBlank()?Objects.toString(properties.getOpenCode().getModel(),""):input.model();
        if(!model.isBlank()&&!model.equals(properties.getOpenCode().getModel())&&!"fake".equals(properties.getOpenCode().getMode())
                &&settings.models().stream().noneMatch(m->m.id().equals(model))) throw PptSupport.bad("PPT_MODEL_UNAVAILABLE","所选模型不可用，请刷新模型列表");
        String now=Instant.now().toString();
        var row=new Document(input.id(),input.title().strip(),project,model,"BRIEFING",0,0,false,digest,now,now);
        var plan=json.createObjectNode();plan.putObject("brief").put("purpose",input.title()).put("audience","").put("duration","").put("pageCount",12).put("requirements","");
        plan.putArray("directions");plan.put("selectedDirectionId","");plan.putArray("slides");plan.put("theme","business");
        persistence.create(row,new Revision(row.id(),0,json.writeValueAsString(Deck.empty(row.title())),json.writeValueAsString(plan),"创建作品",now));
        return view(row);
    }
    public Document require(String id) { return mapper.document(id).orElseThrow(()->new NotFoundException("PPT 作品不存在")); }
    public View get(String id) { return view(require(id)); }
    public Revision snapshot(String id,Long revision) {
        var row=require(id);
        return mapper.revision(id,revision==null?row.revision():revision).orElseThrow(()->new NotFoundException("PPT 版本不存在"));
    }
    public Deck deck(String id,Long revision) { return json.readValue(snapshot(id,revision).deckJson(),Deck.class); }
    public Map<String,Object> plan(String id) {
        var snapshot=snapshot(id,null);return Map.of("revision",snapshot.revision(),"plan",json.readTree(snapshot.planJson()));
    }
    public CursorPage<View> list(String archive,String query,String phase,String cursor,Integer count) {
        if(!Set.of("active","archived","all").contains(archive)||query.length()>200
                ||!Set.of("","BRIEFING","DIRECTION","DESIGN","PRODUCING","REVIEW","EXPORTED").contains(phase)) throw PptSupport.bad("PPT_FILTER_INVALID","作品筛选条件无效");
        var page=PageCursor.decode(cursor);int limit=PageCursor.limit(count);
        var rows=mapper.list(archive,query,phase,page==null?"9999":page.value(),page==null?"~":page.id(),limit+1);
        var visible=rows.stream().limit(limit).toList();
        String next=rows.size()>limit?new PageCursor(visible.getLast().updatedAt(),visible.getLast().id()).encode():null;
        return new CursorPage<>(visible.stream().map(this::view).toList(),next);
    }
    public CursorPage<RevisionView> revisions(String id,String cursor,Integer count) {
        require(id);var page=PageCursor.decode(cursor);int limit=PageCursor.limit(count);long before=Long.MAX_VALUE;
        if(page!=null) try { before=Long.parseLong(page.value()); } catch(NumberFormatException e){ throw PptSupport.bad("PPT_CURSOR_INVALID","版本游标无效"); }
        var rows=mapper.revisions(id,before,limit+1);var visible=rows.stream().limit(limit).toList();
        return new CursorPage<>(visible.stream().map(r->new RevisionView(r.revision(),r.reason(),r.createdAt())).toList(),
                rows.size()>limit?new PageCursor(Long.toString(visible.getLast().revision()),id).encode():null);
    }
    public JsonNode edit(String id,Edit input,boolean agent,Runnable guard) {
        if(input==null||input.operations()==null||json.writeValueAsString(input.operations()).length()>500000)throw PptSupport.bad("PPT_BATCH_TOO_LARGE","操作批次无效或超过 50 万字符，请分批逐页制作");
        PptSupport.key(input.idempotencyKey());String digest=digest("operations",input);
        var replay=replay(id,input.idempotencyKey(),digest);if(replay!=null)return replay;
        var base=editable(id,input.expectedRevision());var old=snapshot(id,base.revision());
        OperationResult output=engine.applyOperations(json.readValue(old.deckJson(),Deck.class),input.operations(),agent);
        if(agent&&"DESIGN".equals(base.phase())&&output.deck().slides().size()>2) {
            var existing=json.readValue(old.deckJson(),Deck.class).slides().stream().map(Slide::id).collect(java.util.stream.Collectors.toSet());
            if(output.deck().slides().stream().anyMatch(s->!existing.contains(s.id())))throw PptSupport.bad("PPT_SAMPLE_LIMIT","方案确认前最多制作 2 页样页，请等待用户确认方案并开始制作");
        }
        validateAssets(id,output.deck());
        JsonNode result=json.valueToTree(Map.of("revision",base.revision()+1,"deck",output.deck(),"createdIds",output.createdIds()));
        var next=new Revision(id,base.revision()+1,json.writeValueAsString(output.deck()),old.planJson(),agent?"PPT 助手编辑":"编辑页面",Instant.now().toString());
        var saved=persistence.edit(base,next,output.deck().title(),input.idempotencyKey(),digest,result,()->{if(!agent)persistence.assertManualAdmission(id);guard.run();});events.publish(id,"deck");return saved;
    }
    public JsonNode savePlan(String id,PlanEdit input,boolean agent,Runnable guard) {
        PptSupport.key(input.idempotencyKey());String digest=digest("plan",input);
        var replay=replay(id,input.idempotencyKey(),digest);if(replay!=null)return replay;
        var base=editable(id,input.expectedRevision());
        if(!Set.of("BRIEFING","DIRECTION","DESIGN").contains(base.phase())) throw PptSupport.bad("PPT_PLAN_FROZEN","制作方案已经确认，请先重新打开设计");
        validatePlan(id,input.plan(),false);var old=snapshot(id,base.revision());
        if("DESIGN".equals(base.phase())) {
            var frozen=json.readTree(old.planJson());String selected=frozen.path("selectedDirectionId").asText();
            if(!selected.equals(input.plan().path("selectedDirectionId").asText())||!selectedDirection(frozen,selected).equals(selectedDirection(input.plan(),selected)))
                throw PptSupport.bad("PPT_DIRECTION_FROZEN","整体方向已经确认，修改方向前请重新打开设计");
        }
        JsonNode result=json.valueToTree(Map.of("revision",base.revision()+1,"plan",input.plan()));
        var next=new Revision(id,base.revision()+1,old.deckJson(),json.writeValueAsString(input.plan()),agent?"助手设计方案":"编辑设计方案",Instant.now().toString());
        var saved=persistence.edit(base,next,base.title(),input.idempotencyKey(),digest,result,()->{if(!agent)persistence.assertManualAdmission(id);guard.run();});
        events.publish(id,"plan");return saved;
    }
    public View action(String id,String action,Action input,Runnable guard) {
        PptSupport.key(input.idempotencyKey());String digest=digest(action,input);
        if(replay(id,input.idempotencyKey(),digest)!=null)return get(id);
        var base=editable(id,input.expectedRevision(),"archive".equals(action));var snapshot=snapshot(id,base.revision());
        String phase=null;Boolean archived=null;
        switch(action) {
            case "finish-planning" -> {
                if("BRIEFING".equals(base.phase())&&!json.readTree(snapshot.planJson()).path("directions").isEmpty()) phase="DIRECTION";
            }
            case "confirm-direction" -> {
                if(!"DIRECTION".equals(base.phase()))throw PptSupport.bad("PPT_PHASE_INVALID","请先生成整体方向");
                var plan=json.readTree(snapshot.planJson());String selected=plan.path("selectedDirectionId").asText();
                if(selected.isBlank()||!hasId(plan.path("directions"),selected))throw PptSupport.bad("PPT_DIRECTION_REQUIRED","请选择一个整体方向");
                phase="DESIGN";
            }
            case "start-production" -> {
                if(!"DESIGN".equals(base.phase()))throw PptSupport.bad("PPT_PHASE_INVALID","请先确认整体方向并完成页面设计");
                validatePlan(id,json.readTree(snapshot.planJson()),true);phase="PRODUCING";
            }
            case "finish-production" -> {
                if(!"PRODUCING".equals(base.phase()))throw PptSupport.bad("PPT_PHASE_INVALID","当前不在制作阶段");
                requireProductionComplete(id,snapshot,true);phase="REVIEW";
            }
            case "reopen" -> phase="BRIEFING";
            case "archive" -> archived=Boolean.TRUE.equals(input.archived());
            case "restore" -> { restore(base,input,digest,guard);return get(id); }
            default -> throw PptSupport.bad("PPT_ACTION_INVALID","不支持的作品操作");
        }
        persistence.action(base,phase,archived,input.idempotencyKey(),digest,json.valueToTree(Map.of("accepted",true)),guard);
        events.publish(id,"document");return get(id);
    }
    private void restore(Document base,Action input,String digest,Runnable guard) {
        if(input.targetRevision()==null)throw PptSupport.bad("PPT_REVISION_REQUIRED","请选择要恢复的版本");
        var target=snapshot(base.id(),input.targetRevision());var current=deck(base.id(),base.revision());var restored=json.readValue(target.deckJson(),Deck.class);
        for(var slide:current.slides()) if(slide.locked()&&!restored.slides().contains(slide))throw PptSupport.bad("PPT_LOCKED","请先解锁要恢复的页面");
        for(var slide:current.slides()) for(var element:slide.elements()) if(element.locked()&&restored.slides().stream().filter(s->Objects.equals(s.id(),slide.id())).noneMatch(s->s.elements().contains(element)))throw PptSupport.bad("PPT_LOCKED","请先解锁要恢复的对象");
        var next=new Revision(base.id(),base.revision()+1,target.deckJson(),target.planJson(),"恢复版本 "+target.revision(),Instant.now().toString());
        persistence.edit(base,next,restored.title(),input.idempotencyKey(),digest,json.valueToTree(Map.of("revision",next.revision())),guard);events.publish(base.id(),"deck");
    }
    public void requireProductionComplete(String id,Revision snapshot) {
        requireProductionComplete(id,snapshot,false);
    }
    private void requireProductionComplete(String id,Revision snapshot,boolean requirePlannedPages) {
        var deck=json.readValue(snapshot.deckJson(),Deck.class);var plan=json.readTree(snapshot.planJson());
        if(deck.slides().isEmpty())throw PptSupport.bad("PPT_EMPTY","尚未生成页面");
        // The production gate requires the plan; later exports validate their frozen edited deck.
        if(requirePlannedPages)for(var slide:plan.path("slides")) if(deck.slides().stream().noneMatch(s->Objects.equals(s.id(),slide.path("id").asText())&&!s.elements().isEmpty()))
            throw PptSupport.bad("PPT_PAGE_MISSING","部分设计页面尚未制作，请继续生成缺少的页面");
        if(deck.slides().stream().anyMatch(s->s.elements().isEmpty()))throw PptSupport.bad("PPT_EMPTY_PAGE","存在没有内容的页面，请补充内容或明确删除该页");
        validateAssets(id,deck);
        var check=engine.validate(deck);if(!check.valid())throw PptSupport.bad("PPT_LAYOUT_INVALID","页面存在阻断问题，请读取检查结果后修正");
    }
    public void validateAssets(String id,Deck deck) {
        Set<String> valid=new HashSet<>();for(var r:mapper.resources(id))if("IMAGE".equals(r.kind())&&"READY".equals(r.state()))valid.add(r.id());
        for(var s:deck.slides())for(var e:s.elements())if(e.assetId()!=null&&!e.assetId().isBlank()&&!valid.contains(e.assetId()))throw PptSupport.bad("PPT_ASSET_INVALID","图片素材不属于当前作品或尚未保存");
    }
    private void validatePlan(String id,JsonNode plan,boolean complete) {
        if(plan==null||!plan.isObject()||json.writeValueAsString(plan).length()>200000)throw PptSupport.bad("PPT_PLAN_INVALID","设计方案格式无效或过大");
        if(!plan.path("brief").isObject()||!plan.path("directions").isArray()||plan.path("directions").size()>10)throw PptSupport.bad("PPT_PLAN_INVALID","方案必须包含制作要求与最多 10 项方向");
        Set<String> directionIds=new HashSet<>();
        for(var direction:plan.path("directions"))if(!direction.isObject()||!direction.path("id").asText().matches("[A-Za-z0-9_-]{1,100}")
                ||!directionIds.add(direction.path("id").asText())||direction.path("title").asText().isBlank())throw PptSupport.bad("PPT_PLAN_INVALID","每个方向需要唯一标识与名称");
        if(!plan.path("selectedDirectionId").asText().isBlank()&&!directionIds.contains(plan.path("selectedDirectionId").asText()))throw PptSupport.bad("PPT_PLAN_INVALID","所选方向不存在");
        var slides=plan.path("slides");if(!slides.isArray()||slides.size()>100)throw PptSupport.bad("PPT_PLAN_INVALID","页面设计须为列表且不超过 100 页");
        Set<String> ids=new HashSet<>(),sources=new HashSet<>();mapper.resources(id).stream().filter(r->"READY".equals(r.state())).forEach(r->sources.add(r.id()));
        for(var slide:slides) {
            String key=slide.path("id").asText();if(!key.matches("[A-Za-z0-9_-]{1,100}")||!ids.add(key)||slide.path("title").asText().isBlank())throw PptSupport.bad("PPT_PLAN_INVALID","每页须有唯一标识和标题");
            for(var source:slide.path("sourceIds"))if(!sources.contains(source.asText()))throw PptSupport.bad("PPT_SOURCE_INVALID","设计方案引用了不可用资料");
        }
        if(complete&&(slides.isEmpty()||!hasId(plan.path("directions"),plan.path("selectedDirectionId").asText())))throw PptSupport.bad("PPT_PLAN_INCOMPLETE","请先选择方向并设计每页内容");
    }
    private boolean hasId(JsonNode list,String id) { for(var item:list)if(item.path("id").asText().equals(id))return true;return false; }
    private JsonNode selectedDirection(JsonNode plan,String id){for(var item:plan.path("directions"))if(item.path("id").asText().equals(id))return item;return json.nullNode();}
    private Document editable(String id,long revision) { return editable(id,revision,false); }
    private Document editable(String id,long revision,boolean allowArchive) {
        var row=require(id);if(row.archived()&&!allowArchive)throw PptSupport.bad("PPT_ARCHIVED","请先恢复归档作品");
        if(row.revision()!=revision)throw PptSupport.conflict("作品已修改，请读取最新版本后重试");return row;
    }
    private JsonNode replay(String id,String key,String digest) { return mapper.receipt(id,key).map(r->persistence.replay(r,digest)).orElse(null); }
    private String digest(String operation,Object input) { return PptSupport.hash(operation+":"+PptSupport.digest(input,json)); }
    private View view(Document d) { return new View(d.id(),d.title(),d.projectId(),d.model(),d.phase(),d.revision(),d.version(),d.archived(),d.createdAt(),d.updatedAt()); }
}
