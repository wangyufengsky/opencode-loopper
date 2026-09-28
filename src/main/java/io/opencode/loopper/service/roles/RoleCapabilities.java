package io.opencode.loopper.service.roles;

import java.util.*;
import io.opencode.loopper.runtime.OpenCodeClient.SessionPermissionRule;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

/** Explicit adapter ceilings; unknown adapters and new role declarations fail closed. */
public final class RoleCapabilities {
    public enum Capability {
        PROJECT_KNOWLEDGE, KNOWLEDGE_CONVERSATION, KNOWLEDGE_DATABASE_METADATA, DATABASE_READ,
        DOCUMENT_READ, DOCUMENT_WRITE, TASK_EVIDENCE, GITLAB_READ, NATIVE_TOOLS, EXTERNAL_MCP
    }
    public static final Set<String> NATIVE_TOOLS = Set.of("read", "glob", "grep", "question", "todowrite",
            "todoread", "bash", "edit", "write", "patch", "apply_patch", "webfetch", "task", "skill");
    private static final Map<String, Set<Capability>> PROFILES = load();
    private RoleCapabilities() { }
    public static Set<Capability> profile(String name) { return name == null ? Set.of() : PROFILES.getOrDefault(name, Set.of()); }
    public static Set<String> profiles() { return PROFILES.keySet(); }
    public static boolean has(String profile, Capability capability) { return profile(profile).contains(capability); }
    public static Set<Capability> effective(String profile, List<Capability> declared) {
        var result = new HashSet<>(profile(profile));
        if (declared != null) result.retainAll(declared); // null is a persisted pre-capability revision only.
        return Set.copyOf(result);
    }
    public static Capability assist(String tool) {
        return switch (tool) {
            case "list_database_connections", "inspect_database_schema", "query_database_readonly" -> Capability.DATABASE_READ;
            case "inspect_document", "read_document" -> Capability.DOCUMENT_READ;
            case "generate_word" -> Capability.DOCUMENT_WRITE;
            case "get_execution_context", "get_failure_evidence", "read_task_evidence", "list_test_failures", "read_test_failure", "search_evidence" -> Capability.TASK_EVIDENCE;
            case "gitlab_project_context", "gitlab_list_issues", "gitlab_list_merge_requests", "gitlab_list_pipelines",
                 "gitlab_read_issue", "gitlab_read_merge_request", "gitlab_read_merge_request_diff", "gitlab_list_pipeline_jobs", "gitlab_read_job_log" -> Capability.GITLAB_READ;
            case "inspect_knowledge_project", "list_knowledge_evidence", "read_knowledge_sources", "find_knowledge_symbol",
                 "search_knowledge_git_content", "search_knowledge_git_patches", "compare_knowledge_git_versions", "search_project_knowledge", "list_knowledge_sources", "browse_knowledge_source", "search_knowledge",
                 "read_knowledge_source", "read_knowledge_evidence", "inspect_knowledge_git", "list_knowledge_git_authors",
                 "search_knowledge_git_commits", "read_knowledge_git_commit", "read_knowledge_git_file", "blame_knowledge_git_lines" -> Capability.PROJECT_KNOWLEDGE;
            default -> null;
        };
    }
    public static boolean allowsAssist(Set<Capability> capabilities, String tool) {
        Capability capability = assist(tool);
        if (capability == Capability.PROJECT_KNOWLEDGE && capabilities.contains(Capability.KNOWLEDGE_CONVERSATION))
            return true;
        return capability != null && capabilities.contains(capability);
    }
    public static boolean allows(Set<Capability> capabilities, String name, String internalServer) {
        if ("*".equals(name)) return false;
        if (NATIVE_TOOLS.contains(name)) return capabilities.contains(Capability.NATIVE_TOOLS);
        String internal = internalServer == null ? "" : internalServer.replaceAll("[^a-zA-Z0-9_-]", "_") + "_";
        if (name.startsWith("@loopper-assist/")) return allowsAssist(capabilities, name.substring(16));
        if (!internal.isEmpty() && name.startsWith(internal + "assist_"))
            return allowsAssist(capabilities, name.substring((internal + "assist_").length()));
        // Private workflow protocol/read tools and guarded accounting are server-owned, never role-granted.
        if (name.startsWith("@loopper-internal/") || !internal.isEmpty() && name.startsWith(internal)
                || name.equals("aicoding_*") || name.equals(io.opencode.loopper.runtime.KnowledgeSessionPolicy.MARKER)) return true;
        return capabilities.contains(Capability.EXTERNAL_MCP);
    }
    public static List<SessionPermissionRule> narrow(List<SessionPermissionRule> rules, String profile,
            List<Capability> declared, String internal) {
        if (declared == null) return rules;
        var capabilities = effective(profile, declared);
        var result = new ArrayList<SessionPermissionRule>();
        if ("IMPLEMENTATION".equals(profile) && (!capabilities.contains(Capability.NATIVE_TOOLS)
                || !capabilities.contains(Capability.EXTERNAL_MCP))) {
            result.add(new SessionPermissionRule("*", "*", "deny"));
            boolean implicitNative = rules.stream().noneMatch(rule -> rule.permission().equals("*")
                    && rule.pattern().equals("*") && !rule.action().equals("allow"));
            if (capabilities.contains(Capability.NATIVE_TOOLS) && implicitNative) {
                NATIVE_TOOLS.stream().sorted()
                        .forEach(name -> result.add(new SessionPermissionRule(name, "*", "allow")));
                result.add(new SessionPermissionRule("read", ".env", "deny"));
                result.add(new SessionPermissionRule("read", ".env.*", "deny"));
                result.add(new SessionPermissionRule("read", ".env.example", "allow"));
            }
        }
        for (var rule : rules) result.add(!"deny".equals(rule.action()) && !allows(capabilities, rule.permission(), internal)
                ? new SessionPermissionRule(rule.permission(), rule.pattern(), "deny") : rule);

        return List.copyOf(result);
    }
    private static LoaderOptions loaderOptions() {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        return options;
    }
    private static Map<String, Set<Capability>> load() {
        try (var stream = RoleCapabilities.class.getClassLoader().getResourceAsStream("roles/capabilities.json")) {
            if (stream == null) throw new IllegalStateException("Missing role capability ceilings");
            Object value = new Yaml(new SafeConstructor(loaderOptions())).load(stream);
            if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid role capability ceilings");
            var result = new LinkedHashMap<String, Set<Capability>>();
            map.forEach((key, raw) -> {
                var capabilities = EnumSet.noneOf(Capability.class);
                for (Object item : (List<?>) raw) capabilities.add(Capability.valueOf(item.toString()));
                result.put(key.toString(), Set.copyOf(capabilities));
            });
            var expected = new HashSet<>(Arrays.stream(io.opencode.loopper.runtime.OpenCodeClient.SessionProfile.values())
                    .map(Enum::name).toList());
            expected.add("ACCOUNTING_COMMAND");
            if (!result.keySet().equals(expected)) throw new IllegalStateException("Incomplete role capability ceilings");
            return Map.copyOf(result);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Cannot load role capabilities", invalid); }
    }
}
