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
        var result=new ArrayList<Tool>(List.of(
                tool("list_database_connections","列出当前项目授权的只读数据库连接",false,Map.of()),
                tool("inspect_database_schema","浏览允许的表、字段、索引和外键；先选择连接返回的 schema",false,Map.of("connectionId","string","schema","string","table","string","kind","string","offset","integer")),
                tool("query_database_readonly","单条只读 SELECT／WITH；失败按返回指引修正，未知结果不可自动重发",false,Map.of("connectionId","string","sql","string")),
                tool("inspect_document","读取文档目录及 SHA；source 为 workspace:相对路径 或 attachment:附件ID",false,Map.of("source","string","offset","integer")),
                tool("read_document","按 section（从 0 开始）读取文档；expectedSha 用于拒绝源文件变化",false,Map.of("source","string","section","integer","expectedSha","string")),
                tool("generate_word","仅为冻结阶段明确要求的 DOCX 生成 Word；source 为 Markdown 引用，target 为授权相对路径",true,Map.of("source","string","target","string","idempotencyKey","string","expectedSha","string")),
                tool("get_execution_context","读取当前阶段权威合同、附件与证据目录；大正文另行读取",false,Map.of("cursor","string")),
                tool("get_failure_evidence","读取本阶段最近完成尝试或指定 attemptId 的验证事实",false,Map.of("attemptId","string")),
                tool("read_task_evidence","读取本任务 evidence:、verification: 或 call: 前缀的证据ID；offset 为字符游标",false,Map.of("reference","string","offset","integer"))));
        result.addAll(knowledgeTools()); result.addAll(gitTools()); result.addAll(batchTools()); return List.copyOf(result);
    }
    public static boolean knowledgeTool(String name) { return name.contains("knowledge"); }
    private static List<Tool> knowledgeTools() {
        return List.of(
            projectKnowledgeSearch(),
            tool("list_knowledge_sources", "列出当前知识会话冻结授权的代码、文档和数据库", false, Map.of()),
            tool("browse_knowledge_source", "分页浏览资料目录；path 为来源内相对路径", false, Map.of("sourceId","string","path","string","query","string","cursor","string")),
            tool("search_knowledge", "有界关键词检索；中文短词按字面匹配，分页及不完整范围见结果", false, Map.of("sourceId","string","path","string","query","string","cursor","string")),
            tool("read_knowledge_source", "读取代码行片段或文档 section；section=-1 查看目录，可按检索返回的 textOffset 跨段读取，返回真实证据 citationId", false, Map.of("sourceId","string","path","string","section","integer","startLine","integer","expectedSha","string","offset","integer","endLine","integer","textOffset","integer")));
    }
    private static Tool projectKnowledgeSearch() {
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("scope", Map.of("type", "string", "description", "当前会话作用域凭证"));
        properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200));
        properties.put("mode", Map.of("type", "string", "enum", List.of("AUTO", "EXACT", "PHRASE", "FIELD")));
        properties.put("terms", Map.of("type", "array", "maxItems", 8, "items", Map.of("type", "string", "minLength", 1, "maxLength", 100), "description", "显式扩展词，只用于概念线索，不证明语义等价"));
        properties.put("sourceIds", Map.of("type", "array", "minItems", 1, "maxItems", 100, "uniqueItems", true, "items", Map.of("type", "string"), "description", "省略时查询本会话全部冻结来源；不能访问会话外来源"));
        properties.put("path", Map.of("type", "string", "description", "可选来源内相对目录；限定目录时跳过数据库"));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 30));
        properties.put("cursor", Map.of("type", "string", "description", "下一页游标；保持原查询、来源和数量；5 分钟有效"));
        return new Tool("search_project_knowledge", "统一检索授权代码、文档和数据库表字段注释；支持原句、字段命名与显式扩展词。检查 coverage 和 nextCursor，按 read 参数读取原文后引用。Git 历史需专用工具。", false,
                Map.of("type", "object", "properties", properties, "required", List.of("scope", "query"), "additionalProperties", false));
    }
    private static List<Tool> gitTools() {
        var common = Map.of("sourceId","string","ref","string","path","string","author","string","query","string","since","string","until","string","timeField","string","cursor","string");
        return List.of(
            tool("inspect_knowledge_git", "只读查看项目 Git 仓库及分支；仅本地记录，不同步远端", false, Map.of("sourceId","string")),
            tool("list_knowledge_git_authors", "分页查询项目范围的作者姓名和邮箱，同名时请向用户澄清", false, common),
            tool("search_knowledge_git_commits", "查询作者、日期和路径的提交。since/until 必须含时区且结束不包含；timeField 默认 author；分页必须保留原筛选", false, common),
            tool("read_knowledge_git_commit", "读取已查询完整 commit SHA 的项目内差异；按 startLine/endLine 分段", false, Map.of("sourceId","string","commit","string","startLine","integer","endLine","integer")),
            tool("read_knowledge_git_file", "读取 commit SHA 中项目内文件；startLine/endLine 为原始行号", false, Map.of("sourceId","string","commit","string","path","string","startLine","integer","endLine","integer")),
            tool("blame_knowledge_git_lines", "查询指定提交文件行的最后修改者，不等同于问题引入者", false, Map.of("sourceId","string","commit","string","path","string","startLine","integer","endLine","integer")));
    }
    private static List<Tool> batchTools() {
        var tools=new ArrayList<Tool>();
        Map<String,String> paging=Map.of("page","integer","limit","integer","state","string","search","string","ref","string","reference","string","offset","integer");
        for(String name:List.of("gitlab_project_context","gitlab_list_issues","gitlab_list_merge_requests","gitlab_list_pipelines"))
            tools.add(tool(name,"只读查询当前冻结 GitLab 仓库；列表分页，正文通过 snapshotReference 分段读取",false,paging));
        for(String name:List.of("gitlab_read_issue","gitlab_read_merge_request","gitlab_read_merge_request_diff"))
            tools.add(tool(name,"读取 Issue／MR 正文、讨论或差异；iid 来自列表，section 为 detail 或 discussions",false,
                    Map.of("iid","integer","section","string","page","integer","limit","integer","reference","string","offset","integer")));
        tools.add(tool("gitlab_list_pipeline_jobs","按 pipelineId 分页读取 Job 状态",false,Map.of("pipelineId","integer","page","integer","limit","integer","reference","string","offset","integer")));
        tools.add(tool("gitlab_read_job_log","按 jobId 保存有界日志前缀快照；用 snapshotReference 读取后续分段，截断不表示完整",false,Map.of("jobId","integer","reference","string","offset","integer")));
        tools.add(tool("list_test_failures","分页列出已保存报告中的失败用例；无用例不能推断测试通过",false,Map.of("attemptId","string","cursor","string")));
        tools.add(tool("read_test_failure","按 failureId 读取失败断言、异常栈和来源",false,Map.of("failureId","string")));
        tools.add(tool("search_evidence","在当前授权快照中按字面关键词搜索，范围不完整时明确返回",false,Map.of("query","string","attemptId","string","cursor","string")));
        return tools;
    }
    public static boolean batchTool(String name) { return name.startsWith("gitlab_") || List.of("list_test_failures","read_test_failure","search_evidence").contains(name); }
    private static Tool tool(String name,String description,boolean writes,Map<String,String> inputs) {
        Map<String,Object> properties=new LinkedHashMap<>(); properties.put("scope",Map.of("type","string","description","使用当前系统提示签发的作用域凭证"));
        inputs.forEach((key,type)->properties.put(key,Map.of("type",type)));
        List<String> required=new ArrayList<>(List.of("scope"));
        for(String key:List.of("connectionId","sql","schema","source","sourceId","idempotencyKey"))if(inputs.containsKey(key))required.add(key);
        return new Tool(name,description,writes,Map.of("type","object","properties",properties,"required",required,"additionalProperties",false));
    }
    public static List<String> allowed(String profile) {
        if(profile==null || profile.contains("NO_TOOLS")&&!profile.startsWith("TEMPLATE_ANALYSIS") || profile.equals("PROJECT_CONVENTION_CANDIDATE_READ_ONLY")) return List.of();
        if (profile.startsWith("KNOWLEDGE_")) return tools().stream().map(Tool::name).filter(n -> knowledgeTool(n) || List.of("list_database_connections", "inspect_database_schema", "query_database_readonly").contains(n)).toList();
        boolean review=profile.contains("JUDGE") || profile.contains("REVIEWER");
        return tools().stream().filter(t->!knowledgeTool(t.name()) && (!t.writes() || profile.equals("IMPLEMENTATION"))
                && (!review || t.name().contains("evidence") || t.name().equals("get_execution_context") || t.name().equals("list_test_failures") || t.name().equals("read_test_failure")))
                .map(Tool::name).toList();
    }
}
