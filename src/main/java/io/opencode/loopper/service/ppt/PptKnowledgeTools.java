package io.opencode.loopper.service.ppt;

import io.opencode.loopper.service.assist.*;
import io.opencode.loopper.service.knowledge.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adapts shared read capabilities to PPT authorization, without borrowing a knowledge session. */
@Service
public class PptKnowledgeTools {
    public static final List<String> TOOLS=io.opencode.loopper.runtime.PptAgentProfile.KNOWLEDGE_TOOLS;
    private final PptKnowledge knowledge;
    private final KnowledgeReader reader;
    private final KnowledgeSearchService search;
    private final KnowledgeGit git;
    private final DatabaseQueryService databases;
    private final PptKnowledgeEvidence evidence;
    private final ObjectMapper json;
    public PptKnowledgeTools(PptKnowledge knowledge,KnowledgeReader reader,KnowledgeSearchService search,KnowledgeGit git,
            DatabaseQueryService databases,PptKnowledgeEvidence evidence,ObjectMapper json) {
        this.knowledge=knowledge;this.reader=reader;this.search=search;this.git=git;this.databases=databases;this.evidence=evidence;this.json=json;
    }
    public PptKnowledge.View context(String document) { return knowledge.view(document); }
    public Object invoke(String document,String tool,JsonNode args,Runnable guard) {
        guard.run();
        if(tool.equals("ppt_list_knowledge_sources"))return knowledge.view(document);
        var selection=knowledge.selection(document);String run=text(args,"agentRunId");
        if(run==null||run.isBlank())throw PptSupport.bad("PPT_KNOWLEDGE_SCOPE","项目资料必须通过当前 PPT 助手读取");
        if(tool.equals("ppt_read_knowledge_source")&&text(args,"evidenceId")!=null)return evidence.read(document,text(args,"evidenceId"));
        Map<String,Object> input=json.convertValue(args,new TypeReference<>(){});input.remove("agentScope");input.remove("agentRunId");
        if(tool.equals("ppt_search_project_knowledge"))return adaptSearch(search.search("ppt:"+document+":"+run,selection,KnowledgeSearchContracts.Request.from(input)));
        if(Set.of("ppt_query_knowledge_database","ppt_inspect_knowledge_database").contains(tool))
            return evidence.capture(document,run,database(selection,tool,args),guard);
        var source=selection.sources().stream().filter(s->s.id().equals(text(args,"sourceId"))).findFirst()
                .orElseThrow(()->PptSupport.bad("PPT_KNOWLEDGE_SOURCE_DENIED","资料不属于此作品已开放的项目来源"));
        if(tool.equals("ppt_browse_knowledge_source"))return reader.browse(source,text(args,"path"),text(args,"query"),text(args,"cursor"));
        Map<String,Object> result;
        if(tool.equals("ppt_read_knowledge_source"))result=reader.readRange(source,text(args,"path"),number(args,"section",-1),number(args,"startLine",1),
                number(args,"endLine",0),text(args,"expectedSha"),number(args,"offset",0));
        else if(tool.equals("ppt_read_knowledge_git"))result=git.call("ppt:"+document+":"+run,source,gitTool(text(args,"operation")),gitArguments(input));
        else throw PptSupport.bad("PPT_KNOWLEDGE_TOOL","不支持的项目资料操作");
        if(tool.equals("ppt_read_knowledge_source")&&Objects.toString(result.get("text"),"").isEmpty())return result;
        return evidence.capture(document,run,result,guard);
    }
    private Map<String,Object> database(KnowledgeSources.Selection selection,String tool,JsonNode args) {
        var connection=selection.connections().stream().filter(c->c.id().equals(text(args,"connectionId"))).findFirst()
                .orElseThrow(()->PptSupport.bad("PPT_DATABASE_DENIED","数据库不属于此作品已开放的项目来源"));
        boolean query=tool.equals("ppt_query_knowledge_database");
        var result=new LinkedHashMap<>(query?databases.query(connection,text(args,"sql")):
                databases.inspect(connection,text(args,"schema"),text(args,"table"),text(args,"kind"),number(args,"offset",0)));
        result.put("kind","DATABASE");result.put("sourceId","database:"+connection.id());result.put("name",connection.name());
        result.put("location",query?"只读查询":Objects.toString(text(args,"schema"),"")+"."+Objects.toString(text(args,"table"),"结构"));
        result.put("sql",query?text(args,"sql"):"");result.put("configurationVersion",connection.version());
        result.put("sha256",AssistFiles.sha(json.writeValueAsBytes(result)));return result;
    }
    private String gitTool(String operation) {
        return switch(Objects.toString(operation,"")) {
            case "inspect"->"inspect_knowledge_git";case "commits"->"search_knowledge_git_commits";case "authors"->"list_knowledge_git_authors";
            case "commit"->"read_knowledge_git_commit";case "file"->"read_knowledge_git_file";case "blame"->"blame_knowledge_git_lines";
            default->throw PptSupport.bad("PPT_GIT_OPERATION","请选择 inspect、commits、authors、commit、file 或 blame");
        };
    }
    private Map<String,Object> gitArguments(Map<String,Object> args) { var result=new LinkedHashMap<>(args);result.remove("operation");return result; }
    private JsonNode adaptSearch(Map<String,Object> result) {
        var node=json.valueToTree(result);
        for(var match:node.path("matches")) {
            var read=match.get("read");if(read instanceof tools.jackson.databind.node.ObjectNode object)
                object.put("tool",read.path("tool").asText().equals("inspect_database_schema")?"ppt_inspect_knowledge_database":"ppt_read_knowledge_source");
        }
        return node;
    }
    private static String text(JsonNode args,String key) {
        var value=args.get(key);if(value==null||value.isNull())return null;
        if(!value.isTextual())throw PptSupport.bad("PPT_KNOWLEDGE_ARGUMENT","参数 "+key+" 必须是文字");return value.asText();
    }
    private static int number(JsonNode args,String key,int fallback) {
        var value=args.get(key);if(value==null||value.isNull())return fallback;
        if(!value.isIntegralNumber()||!value.canConvertToInt())throw PptSupport.bad("PPT_KNOWLEDGE_ARGUMENT","参数 "+key+" 必须是整数");return value.asInt();
    }
}
