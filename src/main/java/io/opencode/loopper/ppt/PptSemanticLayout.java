package io.opencode.loopper.ppt;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static io.opencode.loopper.ppt.PptModel.*;

/** Builds editable title/content columns from semantic blocks, then measures before accepting any page. */
final class PptSemanticLayout {
    private PptSemanticLayout() { }
    static JsonNode compile(ObjectNode deck,JsonNode op,ObjectMapper json,PptFonts fonts) {
        for(String key:op.propertyNames())if(!Set.of("op","slideId","title","section","notes","blocks","index","clientRef").contains(key))
            throw invalid("语义页面包含未知字段："+key);
        String id=op.path("slideId").asText(),title=op.path("title").asText();var blocks=op.path("blocks");
        if(!id.matches("[A-Za-z0-9_-]{1,80}")||title.isBlank()||!blocks.isArray()||blocks.isEmpty()||blocks.size()>3)
            throw invalid("请提供稳定 slideId、标题和 1–3 个内容块");
        double width=deck.path("width").asDouble(),height=deck.path("height").asDouble();
        var slide=json.createObjectNode().put("id",id).put("title",title).put("section",op.path("section").asText()).put("notes",op.path("notes").asText());
        var elements=slide.putArray("elements");elements.add(text(json,id+"_title",48,36,width-96,84,title,32,true));
        double gap=24,column=(width-96-gap*(blocks.size()-1))/blocks.size();int index=0;
        for(var block:blocks) {
            for(String key:block.propertyNames())if(!Set.of("type","title","text","rows","chart").contains(key))throw invalid("内容块包含未知字段："+key);
            String blockId=id+"_content_"+(++index),type=block.path("type").asText("text");
            if(!Set.of("text","table","chart").contains(type))throw invalid("内容块仅支持 text、table、chart");
            double x=48+(index-1)*(column+gap),y=144;
            if(!block.path("title").asText().isBlank()) {
                elements.add(text(json,blockId+"_heading",x,y,column,60,block.path("title").asText(),24,true));y+=72;
            }
            var element=json.createObjectNode().put("id",blockId).put("type",type).put("x",x).put("y",y)
                    .put("width",column).put("height",height-y-48).put("fontSize",type.equals("table")?18:22);
            String field=type.equals("table")?"rows":type.equals("chart")?"chart":"text";
            if(!block.hasNonNull(field)||type.equals("text")&&(!block.path(field).isTextual()||block.path(field).asText().isBlank()))throw invalid("内容块缺少 "+field);
            element.set(field,block.get(field));elements.add(element);
        }
        var sample=new Deck(deck.path("title").asText(),width,height,deck.path("theme").asText(),List.of(json.treeToValue(slide,Slide.class)));
        var issues=new PptLayoutChecks(fonts).validate(sample).issues().stream().filter(i->i.severity().equals("ERROR")).toList();
        if(!issues.isEmpty())throw new PptFailure("PPT_SEMANTIC_DENSITY","此页内容无法按当前版式完整显示，请拆页或减少同页内容，原文未截断："
                +issues.stream().limit(5).map(i->i.elementId()+" "+i.message()).collect(java.util.stream.Collectors.joining("；")));
        var result=json.createObjectNode().put("op","create_slide");result.set("slide",slide);
        for(String field:List.of("index","clientRef"))if(op.has(field))result.set(field,op.get(field));return result;
    }
    private static ObjectNode text(ObjectMapper json,String id,double x,double y,double width,double height,String text,int size,boolean bold) {
        return json.createObjectNode().put("id",id).put("type","text").put("x",x).put("y",y).put("width",width).put("height",height)
                .put("text",text).put("fontSize",size).put("bold",bold);
    }
    private static PptFailure invalid(String message) { return new PptFailure("PPT_INVALID_OPERATION",message); }
}
