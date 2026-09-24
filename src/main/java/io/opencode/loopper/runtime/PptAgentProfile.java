package io.opencode.loopper.runtime;

import java.util.List;
import io.opencode.loopper.service.roles.RolePromptResources;

/** Closed PPT role contract, shared by permission compilation and MCP registration. */
public final class PptAgentProfile {
    private PptAgentProfile() { }
    public static final String AGENT = "loopper-ppt";
    public static final List<String> KNOWLEDGE_TOOLS = List.of("ppt_list_knowledge_sources", "ppt_search_project_knowledge", "ppt_browse_knowledge_source",
            "ppt_read_knowledge_source", "ppt_query_knowledge_database", "ppt_inspect_knowledge_database", "ppt_read_knowledge_git");
    public static final List<String> TOOLS = java.util.stream.Stream.concat(List.of("ppt_get_context", "ppt_read_source", "ppt_get_capabilities",
            "ppt_request_input", "ppt_submit_plan", "ppt_apply_operations", "ppt_measure_text", "ppt_check_layout",
            "ppt_render_preview", "ppt_get_job", "ppt_export").stream(), KNOWLEDGE_TOOLS.stream()).toList();
    public static final List<String> DISCUSSION_TOOLS = java.util.stream.Stream.concat(List.of("ppt_get_context", "ppt_read_source", "ppt_get_capabilities").stream(), KNOWLEDGE_TOOLS.stream()).toList();
    public static final String KNOWLEDGE_PROMPT=RolePromptResources.read("ppt.knowledge");
    public static final String BASE_PROMPT=RolePromptResources.read("ppt.base");
    public static final String PROMPT = RolePromptResources.read("ppt.manual");
    public static final String DISCUSSION_PROMPT = RolePromptResources.read("ppt.discussion");
    public static final String AUTOMATIC_PROMPT=RolePromptResources.read("ppt.automatic");
}
