package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.service.ppt.PptSupport;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Deterministic auto-planning acceptance, also returned to the same model session for repair. */
public final class PptAutomaticPlan {
    private PptAutomaticPlan() { }
    public static void validate(JsonNode plan) {
        var issues=new ArrayList<String>();
        if(plan==null||!plan.isObject())throw PptSupport.bad("PPT_PLAN_INCOMPLETE","plan 必须是完整方案对象");
        required(plan.path("brief"),"purpose","brief",issues);required(plan.path("brief"),"audience","brief",issues);
        var slides=plan.path("slides");
        if(!slides.isArray()||slides.isEmpty())issues.add("slides 至少包含一页");
        if(!plan.path("brief").path("pageCount").isIntegralNumber()||plan.path("brief").path("pageCount").asInt()!=slides.size())
            issues.add("brief.pageCount 必须与 slides 数量一致");
        String selected=plan.path("selectedDirectionId").asText();boolean found=false;
        for(var direction:plan.path("directions"))if(direction.path("id").asText().equals(selected)&&!selected.isBlank())found=true;
        if(!found)issues.add("selectedDirectionId 必须选择 directions 中已有的真实方向 id");
        required(plan.path("narrative"),"story","narrative",issues);
        required(plan.path("visualRules"),"style","visualRules",issues);
        if(!plan.path("assets").isObject())issues.add("assets 必须保存素材规则对象");
        required(plan.path("delivery"),"fileName","delivery",issues);
        int index=0;
        for(var slide:slides) {
            String pointer="slides["+(index++)+"]";
            for(String name:List.of("id","title","message","content"))required(slide,name,pointer,issues);
            if(!slide.path("sourceIds").isArray())issues.add(pointer+".sourceIds 必须是列表，无引用时使用 []");
            if(!slide.path("notes").isTextual())issues.add(pointer+".notes 必须是文字");
        }
        if(!issues.isEmpty())throw PptSupport.bad("PPT_PLAN_INCOMPLETE",String.join("；",issues.stream().limit(20).toList())+"。请补齐后在同一会话重新提交完整 plan");
    }
    private static void required(JsonNode object,String key,String pointer,List<String> issues) {
        if(!object.path(key).isTextual()||object.path(key).asText().isBlank())issues.add(pointer+"."+key+" 必须是非空文字");
    }
}
