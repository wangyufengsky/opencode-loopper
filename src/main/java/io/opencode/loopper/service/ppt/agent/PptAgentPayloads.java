package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import java.util.*;
import tools.jackson.databind.*;

/** New runs use bounded model messages and receipts; original requirements and old receipts remain intact. */
final class PptAgentPayloads {
    static final String PROTOCOL="BOUNDED_CONTEXT_V1";
    static final int INLINE=24000;
    private PptAgentPayloads() { }
    static String prompt(String text,String source) {
        if(text.length()<=INLINE)return text;
        return "完整"+(source.equals("discussion")?"此前讨论":"制作要求")+"已保存（"+text.length()+" 字）。"
                +"请先调用 ppt_get_context，args={source:\""+source+"\",offset:0,limit:12000}，"
                +"按 nextOffset 读完所有分段后再进行回复或制作，不要忽略未读取的要求。";
    }
    static Object read(Run run,JsonNode args,PptAgentMapper mapper,ObjectMapper json) {
        String source=args.path("source").asText();
        String text=switch(source) {
            case "requirements" -> run.userText();
            case "discussion" -> PptDiscussionTranscript.before(mapper,run,json);
            default -> throw PptAgentService.bad("source 只支持 requirements 或 discussion");
        };
        int offset=args.path("offset").asInt(0),limit=args.path("limit").asInt(12000);
        if(offset<0||offset>text.length()||limit<1||limit>12000)throw PptAgentService.bad("读取范围无效，请按 nextOffset 继续，limit 不超过 12000");
        int end=Math.min(text.length(),offset+limit);
        var result=new LinkedHashMap<String,Object>();
        result.put("source",source);result.put("sha256",PptAgentService.hash(text));result.put("total",text.length());
        result.put("offset",offset);result.put("text",text.substring(offset,end));result.put("nextOffset",end==text.length()?null:end);
        return result;
    }
    static JsonNode compact(String tool,JsonNode args,JsonNode result,ObjectMapper json) {
        if(!Set.of("ppt_apply_operations","ppt_submit_plan").contains(tool))return result;
        var receipt=json.createObjectNode();receipt.set("revision",result.path("revision"));receipt.put("saved",true);
        if(result.has("createdIds"))receipt.set("createdIds",result.get("createdIds"));
        if(tool.equals("ppt_apply_operations")) {
            Set<String> pages=new LinkedHashSet<>();
            for(var op:args.path("operations")) {
                if(op.hasNonNull("slideId"))pages.add(op.path("slideId").asText());
                if(op.path("slide").hasNonNull("id"))pages.add(op.path("slide").path("id").asText());
            }
            receipt.set("affectedPages",json.valueToTree(pages));
            receipt.put("pageCount",result.path("deck").path("slides").size());
        }
        receipt.put("next","使用 ppt_get_context 读取需要的页面；用返回的 revision 继续写入并检查布局");
        return receipt;
    }
}
