package io.opencode.loopper.service.assist;

import java.util.*;

/** One schema and role catalogue for MCP, UI policy and prompt composition. */
public final class AssistToolCatalog {
    public static final String SERVER="@loopper-assist";
    public static final String ENDPOINT="/api/assist-mcp-streamable";
    public record Tool(String name,String description,boolean writes,Map<String,Object> schema) { }
    private AssistToolCatalog() { }
    public static String serverName(String internal) {return internal+"_assist";}
    public static List<Tool> tools() {
        return List.of(
                tool("list_database_connections","列出当前项目授权的只读数据库连接",false,Map.of()),
                tool("inspect_database_schema","浏览允许的表、字段、索引和外键；先选择连接返回的 schema",false,Map.of("connectionId","string","schema","string","table","string","kind","string","offset","integer")),
                tool("query_database_readonly","单条只读 SELECT／WITH；失败按返回指引修正，未知结果不可自动重发",false,Map.of("connectionId","string","sql","string")),
                tool("inspect_document","读取文档目录及 SHA；source 为 workspace:相对路径 或 attachment:附件ID",false,Map.of("source","string","offset","integer")),
                tool("read_document","按 section（从 0 开始）读取文档；expectedSha 用于拒绝源文件变化",false,Map.of("source","string","section","integer","expectedSha","string")),
                tool("generate_word","仅为冻结阶段明确要求的 DOCX 生成 Word；source 为 Markdown 引用，target 为授权相对路径",true,Map.of("source","string","target","string","idempotencyKey","string","expectedSha","string")),
                tool("get_execution_context","读取当前阶段权威合同、附件与证据目录；大正文另行读取",false,Map.of("cursor","string")),
                tool("get_failure_evidence","读取本阶段最近完成尝试或指定 attemptId 的验证事实",false,Map.of("attemptId","string")),
                tool("read_task_evidence","读取本任务 evidence:、verification: 或 call: 前缀的证据ID；offset 为字符游标",false,Map.of("reference","string","offset","integer")));
    }
    private static Tool tool(String name,String description,boolean writes,Map<String,String> inputs) {
        Map<String,Object> properties=new LinkedHashMap<>(); properties.put("scope",Map.of("type","string","description","使用当前系统提示签发的作用域凭证"));
        inputs.forEach((key,type)->properties.put(key,Map.of("type",type)));
        List<String> required=new ArrayList<>(List.of("scope"));
        for(String key:List.of("connectionId","sql","schema","source","idempotencyKey"))if(inputs.containsKey(key))required.add(key);
        return new Tool(name,description,writes,Map.of("type","object","properties",properties,"required",required,"additionalProperties",false));
    }
    public static List<String> allowed(String profile) {
        if(profile==null || profile.contains("NO_TOOLS")&&!profile.startsWith("TEMPLATE_ANALYSIS") || profile.equals("PROJECT_CONVENTION_CANDIDATE_READ_ONLY")) return List.of();
        boolean review=profile.contains("JUDGE") || profile.contains("REVIEWER");
        return tools().stream().filter(t->(!t.writes() || profile.equals("IMPLEMENTATION"))
                && (!review || t.name().contains("evidence") || t.name().equals("get_execution_context")))
                .map(Tool::name).toList();
    }
}
