package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.AssistMapper;
import io.opencode.loopper.service.TaskEventService;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Auxiliary dispatch cannot advance Task/Attempt state. Failures are tool diagnostics, not task failures. */
@Service
public class AssistToolService {
    private final AssistScopeService scopes;private final DatabaseQueryService databases;private final AssistDocumentService documents;
    private final AssistWordService words;private final AssistEvidenceService evidence;private final AssistMapper mapper;
    private final TaskEventService events;private final ObjectMapper json;
    public AssistToolService(AssistScopeService scopes,DatabaseQueryService databases,AssistDocumentService documents,AssistWordService words,
                             AssistEvidenceService evidence,AssistMapper mapper,TaskEventService events,ObjectMapper json) {
        this.scopes=scopes;this.databases=databases;this.documents=documents;this.words=words;this.evidence=evidence;this.mapper=mapper;this.events=events;this.json=json;
    }
    public record Result(Map<String,Object> content,boolean error) { }
    public Result call(String name,Map<String,Object> arguments) {
        AssistScopeService.Scope scope=null;String id=null;
        try {
            if(arguments==null)throw new AssistFailure("ASSIST_INPUT_INVALID","工具参数必须是对象");
            validate(name,arguments);scope=scopes.authorize(string(arguments,"scope"),name);id=UUID.randomUUID().toString();
            mapper.startCall(id,scope.ownerKey(),scope.externalSessionId(),name,Instant.now().toString());event(scope,name,id,"RUNNING");
            Map<String,Object> output=execute(scope,name,arguments);scopes.authorize(string(arguments,"scope"),name);
            var result=new LinkedHashMap<>(output);result.put("reference","call:"+id);result.put("collectedAt",Instant.now().toString());
            String encoded=AssistRedaction.text(json.writeValueAsString(result));
            if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)throw new AssistFailure("ASSIST_RESULT_LIMIT","结果超过 1 MiB，请缩小查询或读取范围");
            mapper.finishCall(id,"SUCCEEDED",encoded,Instant.now().toString());event(scope,name,id,"SUCCEEDED");
            return new Result(json.readValue(encoded,new tools.jackson.core.type.TypeReference<>(){}),false);
        }catch(AssistFailure failure){return failed(scope,name,id,failure);}
        catch(RuntimeException failure){return failed(scope,name,id,new AssistFailure("ASSIST_UNAVAILABLE","辅助操作暂不可用，请检查运行环境和已登记调用状态","STOP_AND_INSPECT"));}
    }
    private Result failed(AssistScopeService.Scope scope,String tool,String id,AssistFailure failure) {
        var result=new LinkedHashMap<String,Object>();result.put("code",failure.code());result.put("detail",failure.getMessage());result.put("action",failure.action());
        result.put("problemPath","/arguments");result.put("taskStateChanged",false);
        if(id!=null){result.put("reference","call:"+id);mapper.finishCall(id,"FAILED",json.writeValueAsString(result),Instant.now().toString());event(scope,tool,id,"FAILED");}
        return new Result(result,true);
    }
    private Map<String,Object> execute(AssistScopeService.Scope scope,String name,Map<String,Object> args) {
        return switch(name) {
            case "list_database_connections" -> Map.of("connections",scope.connections().stream().map(c->Map.of("id",c.id(),"name",c.name(),"type",c.config().type(),"schemas",c.config().schemas(),"version",c.version(),"availability","FROZEN_CONFIGURATION")).toList());
            case "inspect_database_schema" -> databases.inspect(connection(scope,args),string(args,"schema"),string(args,"table"),string(args,"kind"),number(args,"offset"));
            case "query_database_readonly" -> databases.query(connection(scope,args),string(args,"sql"));
            case "inspect_document" -> documents.inspect(documents.load(scope,string(args,"source"),null),number(args,"offset"));
            case "read_document" -> documents.read(documents.load(scope,string(args,"source"),string(args,"expectedSha")),number(args,"section"));
            case "generate_word" -> words.generate(scope,string(args,"source"),string(args,"target"),string(args,"expectedSha"),string(args,"idempotencyKey"));
            case "get_execution_context" -> evidence.context(scope,string(args,"cursor"));
            case "get_failure_evidence" -> evidence.failure(scope,string(args,"attemptId"));
            case "read_task_evidence" -> evidence.read(scope,string(args,"reference"),number(args,"offset"));
            default -> throw new AssistFailure("ASSIST_TOOL_UNKNOWN","工具不存在，请刷新工具目录");
        };
    }
    private static void validate(String tool,Map<String,Object> args) {
        var definition=AssistToolCatalog.tools().stream().filter(t->t.name().equals(tool)).findFirst().orElseThrow(()->new AssistFailure("ASSIST_TOOL_UNKNOWN","工具不存在"));
        var properties=(Map<?,?>)definition.schema().get("properties");
        for(String key:args.keySet())if(!properties.containsKey(key))throw new AssistFailure("ASSIST_INPUT_INVALID","未知参数 /"+key+"；请按工具 schema 修正");
        for(Object key:(List<?>)definition.schema().get("required"))if(args.get(key)==null)throw new AssistFailure("ASSIST_INPUT_INVALID","缺少参数 /"+key);
        for(var entry:args.entrySet()) {
            String type=Objects.toString(((Map<?,?>)properties.get(entry.getKey())).get("type"));Object value=entry.getValue();
            if("string".equals(type)&&(!(value instanceof String text)||text.length()>32768)
                    ||"integer".equals(type)&&(!(value instanceof Number n)||n.doubleValue()!=n.intValue()))throw new AssistFailure("ASSIST_INPUT_INVALID","参数 /"+entry.getKey()+" 的类型或长度不符合 schema");
        }
    }
    private DatabaseConnectionService.Bound connection(AssistScopeService.Scope scope,Map<String,Object> args) {
        String id=string(args,"connectionId");return scope.connections().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new AssistFailure("DATABASE_SCOPE_DENIED","连接未包含在当前项目冻结授权中，请通过任务配置授权","REAUTHORIZE"));
    }
    private void event(AssistScopeService.Scope scope,String tool,String id,String state){
        if(scope!=null&&scope.taskId()!=null) {
            if(!"RUNNING".equals(state)&&!scope.profile().contains("JUDGE")&&!scope.profile().contains("REVIEWER"))mapper.callArtifact(UUID.randomUUID().toString(),scope.taskId(),scope.attemptId(),"辅助工具："+tool,
                    mapper.callResult(scope.ownerKey(),id),Instant.now().toString());
            events.emit(scope.taskId(),"artifact.assist",Map.of("tool",tool,"reference","call:"+id,"state",state));
        }
    }
    private static String string(Map<String,Object> args,String name){return args.get(name) instanceof String text?text:null;}
    private static int number(Map<String,Object> args,String name){return args.get(name) instanceof Number n?n.intValue():0;}
}
