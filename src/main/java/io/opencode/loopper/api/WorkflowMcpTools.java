package io.opencode.loopper.api;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.service.workflow.WorkflowModelTools;
import io.opencode.loopper.service.*;
import io.opencode.loopper.domain.SessionFailure;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

final class WorkflowMcpTools {
    private WorkflowMcpTools() { }
    static List<McpServerFeatures.SyncToolSpecification> specifications(WorkflowModelTools service, ObjectMapper json) {
        return WorkflowModelProfile.TOOLS.stream().map(name->{
            var tool=McpSchema.Tool.builder(name,schema(name)).description(switch(name) {
                case WorkflowModelProfile.WORK -> "Use empty args for this node's task, fixed inputs, outputs and attempt version. Nodes declaring PLAN can also list preset summaries with {catalog:true,query,cursor,limit}, then read one exact preset and role version with {presetId,presetVersion}. Professional history and snapshot-review nodes read frozen analysis pages with {analysis:true,offset,limit} (limit up to 12000); test reviewers read cases with {testCases:true,cursor,limit} (limit up to 100). Follow work instructions. Queries never start work.";
                case WorkflowModelProfile.INPUT -> "Read one fixed input by name with offset and limit (maximum 12000 characters); continue until nextOffset is null.";
                case WorkflowModelProfile.FILES -> "List files of this node's named CODE or DOCUMENT input; professional snapshot nodes also require an exact version SHA. Cursor is bound to the immutable delivery. Read all pages until nextCursor is null.";
                case WorkflowModelProfile.FILE -> "Read UTF-8 text from one exact file of this node's named CODE or DOCUMENT input. Generic text uses offset/limit and nextOffset. Professional source/document nodes instead use sha256 (code review uses blobSha), startLine and lineCount (up to 200), continuing with nextLine. Snapshot review uses version, blobSha, startLine/lineCount, or query/afterLine for search (search is not reading evidence). Follow the node work instructions; binary files require download in the UI.";
                default -> "Submit one complete delivery {summary,outcome,outputs}. Output values are {kind,content}. Acceptance does not complete the workflow.";
            }).annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(!name.equals(WorkflowModelProfile.SUBMIT))
                    .idempotentHint(true).destructiveHint(false).openWorldHint(false).build()).build();
            return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange,request)->{
                try {
                    var response=Map.<String,Object>of("result",service.call(name,request.arguments()));
                    return McpSchema.CallToolResult.builder().structuredContent(response).addTextContent(json.writeValueAsString(response)).isError(false).build();
                } catch (BadRequestException e) { return error(json,e.code(),e.getMessage(),"FIX_AND_RESUBMIT"); }
                catch (NotFoundException e) { return error(json,"WORKFLOW_RESOURCE_NOT_FOUND",e.getMessage(),"READ_WORK_OR_INPUT_FILES"); }
                catch (ConflictException e) { return error(json,e.code(),e.getMessage(),"READ_WORK_OR_WAIT_FOR_RECOVERY"); }
                catch (SessionFailure e) { return error(json,e.code(),e.getMessage(),"STOP_AND_WAIT_FOR_RECOVERY"); }
                catch (RuntimeException e) { return error(json,"WORKFLOW_TOOL_FAILED","节点工具未完成，请保留原请求并等待恢复","STOP_AND_WAIT_FOR_RECOVERY"); }
            }).build();
        }).toList();
    }
    private static Map<String,Object> schema(String name) {
        var fields=new LinkedHashMap<String,Object>(); var required=new ArrayList<String>();
        if (name.equals(WorkflowModelProfile.WORK)) {
            fields.put("catalog",Map.of("type","boolean"));fields.put("query",Map.of("type","string","maxLength",200));fields.put("cursor",Map.of("type","string","maxLength",2048));
            fields.put("analysis",Map.of("type","boolean"));fields.put("testCases",Map.of("type","boolean"));fields.put("offset",Map.of("type","integer","minimum",0,"maximum",2097152));
            fields.put("limit",Map.of("type","integer","minimum",1,"maximum",12000));fields.put("presetId",Map.of("type","string","maxLength",80));
            fields.put("presetVersion",Map.of("type","integer","minimum",1));
        } else if (name.equals(WorkflowModelProfile.INPUT)) {
            fields.put("name",Map.of("type","string")); fields.put("offset",Map.of("type","integer","minimum",0));
            fields.put("limit",Map.of("type","integer","minimum",1,"maximum",12000)); required.add("name");
        } else if (name.equals(WorkflowModelProfile.FILES)) {
            fields.put("version",Map.of("type","string","maxLength",64));
            fields.put("name",Map.of("type","string"));required.add("name");fields.put("cursor",Map.of("type","string"));
            fields.put("limit",Map.of("type","integer","minimum",1,"maximum",100));
        } else if (name.equals(WorkflowModelProfile.FILE)) {
            fields.put("name",Map.of("type","string"));fields.put("path",Map.of("type","string"));required.addAll(List.of("name","path"));
            fields.put("offset",Map.of("type","integer","minimum",0));fields.put("limit",Map.of("type","integer","minimum",1,"maximum",12000));
            fields.put("version",Map.of("type","string","maxLength",64));fields.put("query",Map.of("type","string","maxLength",200));fields.put("afterLine",Map.of("type","integer","minimum",0));
            fields.put("sha256",Map.of("type","string","maxLength",64));fields.put("blobSha",Map.of("type","string","maxLength",64));
            fields.put("startLine",Map.of("type","integer","minimum",1));fields.put("lineCount",Map.of("type","integer","minimum",1,"maximum",200));
        } else if (name.equals(WorkflowModelProfile.SUBMIT)) {
            fields.put("requestKey",Map.of("type","string","minLength",16,"maxLength",100));
            fields.put("expectedAttemptVersion",Map.of("type","integer","minimum",0));
            fields.put("delivery",Map.of("type","object","additionalProperties",false,"required",List.of("summary","outputs"),
                    "properties",Map.of("summary",Map.of("type","string"),"outcome",Map.of("type",List.of("string","null")),
                            "outputs",Map.of("type","object","additionalProperties",Map.of("type","object","additionalProperties",false,
                                    "required",List.of("kind","content"),"properties",Map.of("kind",Map.of("type","string"),"content",Map.of()))))));
            required.addAll(List.of("requestKey","expectedAttemptVersion","delivery"));
        }
        return Map.of("type","object","additionalProperties",false,"required",List.of("attemptId","scope","args"),
                "properties",Map.of("attemptId",Map.of("type","string"),"scope",Map.of("type","string"),
                        "args",Map.of("type","object","additionalProperties",false,"properties",fields,"required",required)));
    }
    private static McpSchema.CallToolResult error(ObjectMapper json,String code,String detail,String action) {
        var result=Map.of("errorCode",code,"detail",detail,"action",action);
        return McpSchema.CallToolResult.builder().structuredContent(result).addTextContent(json.writeValueAsString(result)).isError(true).build();
    }
}
