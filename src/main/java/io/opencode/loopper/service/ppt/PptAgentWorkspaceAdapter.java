package io.opencode.loopper.service.ppt;

import io.opencode.loopper.service.ppt.agent.PptAgentWorkspace;
import io.opencode.loopper.ppt.*;
import io.opencode.loopper.ppt.PptModel.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared UI/MCP business services. The caller's frozen scope is enforced before any commit. */
@Service
public class PptAgentWorkspaceAdapter implements PptAgentWorkspace {
    private final PptDocuments documents;
    private final PptResources resources;
    private final PptStorage storage;
    private final PptJobs jobs;
    private final PptEngine engine;
    private final ObjectMapper json;
    private final PptChecks checks;
    private final PptKnowledgeTools knowledge;
    public PptAgentWorkspaceAdapter(PptDocuments documents,PptResources resources,PptStorage storage,PptJobs jobs,PptEngine engine,ObjectMapper json,PptChecks checks,PptKnowledgeTools knowledge) {
        this.documents=documents;this.resources=resources;this.storage=storage;this.jobs=jobs;this.engine=engine;this.json=json;this.checks=checks;this.knowledge=knowledge;
    }
    @Override public Workspace workspace(String id) {
        var doc=documents.get(id);if(doc.archived())throw PptSupport.bad("PPT_ARCHIVED","请先恢复归档作品");
        var context=json.valueToTree(context(id,json.createObjectNode()));
        return new Workspace(id,doc.phase(),doc.revision(),doc.model(),storage.path(id,"session"),context);
    }
    @Override public Object invoke(String id,String tool,JsonNode args,Runnable revalidate) {
        revalidate.run();
        if(PptKnowledgeTools.TOOLS.contains(tool))return knowledge.invoke(id,tool,args,revalidate);
        return switch(tool) {
            case "ppt_get_context" -> context(id,args);
            case "ppt_get_capabilities" -> PptToolContracts.capabilities(engine,json);
            case "ppt_read_source" -> resources.read(id,required(args,"sourceId"),required(args,"sectionId"),args.path("offset").asInt(0),args.path("limit").asInt(12000));
            case "ppt_submit_plan" -> submit(id,args,revalidate);
            case "ppt_apply_operations" -> operations(id,args,revalidate);
            case "ppt_measure_text" -> engine.measureText(documents.deck(id,revision(args)),element(id,args));
            case "ppt_check_layout" -> checks.check(id,revision(args));
            case "ppt_render_preview" -> jobs.create(id,new PptJobs.Create("PREVIEW",requiredRevision(args),text(args,"slideId"),required(args,"idempotencyKey")),revalidate);
            case "ppt_export" -> jobs.create(id,new PptJobs.Create("EXPORT",requiredRevision(args),null,required(args,"idempotencyKey")),revalidate);
            case "ppt_get_job" -> jobs.get(id,required(args,"jobId"));
            case "finish_production", "finish_planning" -> documents.action(id,tool.replace('_','-'),
                    new PptDocuments.Action("system_"+tool+"_"+requiredRevision(args),requiredRevision(args),null,null),revalidate);
            default -> throw PptSupport.bad("PPT_TOOL_INVALID","不支持的 PPT 操作");
        };
    }
    private Object context(String id,JsonNode args) {
        var doc=documents.get(id);Long revision=revision(args);var deck=documents.deck(id,revision);
        if(args.hasNonNull("slideId")) {
            var slide=deck.slides().stream().filter(s->s.id().equals(args.path("slideId").asText())).findFirst().orElseThrow(()->PptSupport.bad("PPT_SLIDE_MISSING","页面不存在"));
            return Map.of("document",doc,"slide",slide,"theme",deck.theme(),"width",deck.width(),"height",deck.height());
        }
        return Map.of("document",doc,"plan",documents.plan(id),"pages",deck.slides().stream().map(s->Map.of("id",s.id(),"title",s.title(),"section",s.section(),"locked",s.locked(),"elements",s.elements().size())).toList(),
                "resources",resources.list(id),"knowledge",knowledge.context(id),"theme",deck.theme(),"width",deck.width(),"height",deck.height());
    }
    private Object submit(String id,JsonNode args,Runnable guard) {
        if(!"DOCUMENT".equals(args.path("agentScope").path("kind").asText("DOCUMENT")))throw PptSupport.bad("PPT_SCOPE_DENIED","整体方案修改需要选择整份作品范围");
        return documents.savePlan(id,new PptDocuments.PlanEdit(required(args,"idempotencyKey"),requiredRevision(args),args.get("plan")),true,guard);
    }
    private Object operations(String id,JsonNode args,Runnable guard) {
        var raw=args.get("operations");if(raw==null||!raw.isArray())throw PptSupport.bad("PPT_OPERATIONS_REQUIRED","operations 必须是操作列表");
        List<JsonNode> list=new ArrayList<>();raw.forEach(list::add);
        var old=documents.deck(id,requiredRevision(args));
        if(!"DOCUMENT".equals(args.path("agentScope").path("kind").asText("DOCUMENT")))scope(old,engine.applyOperations(old,list,true).deck(),args.path("agentScope"));
        return documents.edit(id,new PptDocuments.Edit(required(args,"idempotencyKey"),requiredRevision(args),list),true,guard);
    }
    private void scope(Deck before,Deck after,JsonNode scope) {
        if(!Objects.equals(before.title(),after.title())||!Objects.equals(before.theme(),after.theme())||!Objects.equals(before.width(),after.width())||!Objects.equals(before.height(),after.height()))throw scopeDenied();
        String kind=scope.path("kind").asText(),slideId=scope.path("slideId").asText(),section=scope.path("section").asText();
        var allowed=new HashSet<String>();for(var slide:before.slides())if(kind.equals("SECTION")?slide.section().equals(section):slide.id().equals(slideId))allowed.add(slide.id());
        if(allowed.isEmpty())throw scopeDenied();
        if(!before.slides().stream().filter(s->!allowed.contains(s.id())).toList().equals(after.slides().stream().filter(s->!allowed.contains(s.id())).toList()))throw scopeDenied();
        if(kind.equals("ELEMENT")) {
            var original=before.slides().stream().filter(s->s.id().equals(slideId)).findFirst().orElseThrow();
            var changed=after.slides().stream().filter(s->s.id().equals(slideId)).findFirst().orElseThrow(PptAgentWorkspaceAdapter::scopeDenied);
            String element=scope.path("elementId").asText();
            if(original.elements().stream().noneMatch(e->e.id().equals(element))||!original.title().equals(changed.title())||!original.notes().equals(changed.notes())
                    ||!original.section().equals(changed.section())||!Objects.equals(original.locked(),changed.locked())||!Objects.equals(original.theme(),changed.theme())
                    ||!original.elements().stream().filter(e->!e.id().equals(element)).toList().equals(changed.elements().stream().filter(e->!e.id().equals(element)).toList()))throw scopeDenied();
        }
    }
    private Element element(String id,JsonNode args) {
        if(args.hasNonNull("element"))return json.treeToValue(args.get("element"),Element.class);
        return documents.deck(id,revision(args)).slides().stream().filter(s->s.id().equals(args.path("slideId").asText())).flatMap(s->s.elements().stream())
                .filter(e->e.id().equals(args.path("elementId").asText())).findFirst().orElseThrow(()->PptSupport.bad("PPT_ELEMENT_MISSING","对象不存在"));
    }
    private static RuntimeException scopeDenied(){return PptSupport.bad("PPT_SCOPE_DENIED","操作超出用户本次选择的章节、页面或对象范围");}
    private static String required(JsonNode args,String name){String value=text(args,name);if(value==null||value.isBlank())throw PptSupport.bad("PPT_ARGUMENT_REQUIRED","缺少参数："+name);return value;}
    private static String text(JsonNode args,String name){return args.hasNonNull(name)?args.path(name).asText():null;}
    private static Long revision(JsonNode args){return args.hasNonNull("revision")?args.path("revision").asLong():null;}
    private static long requiredRevision(JsonNode args){var value=args.has("expectedRevision")?args.get("expectedRevision"):args.get("revision");if(value==null||!value.isIntegralNumber()||value.asLong()<0)throw PptSupport.bad("PPT_REVISION_REQUIRED","请提供明确的作品版本");return value.asLong();}
}
