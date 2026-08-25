package io.opencode.loopper.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the fail-closed OpenCode permission profile independently of HTTP transport. */
final class OpenCodePermissionPolicy {
    private OpenCodePermissionPolicy() { }

    static List<Map<String, String>> rules(OpenCodeClient.SessionProfile profile) {
        return rules(profile, List.of());
    }

    static List<Map<String, String>> rules(OpenCodeClient.SessionProfile profile, List<String> mcpServers) {
        if (profile != OpenCodeClient.SessionProfile.IMPLEMENTATION) {
            List<Map<String, String>> rules = new ArrayList<>();
            rules.add(rule("*", "*", "deny"));
            if (isNoTools(profile)) {
                rules.add(rule("external_directory", "*", "deny"));
                // Router receives a bounded server snapshot and must remain a true zero-tool classifier.
                // Other no-built-in roles may still use explicitly configured MCP evidence sources.
                if (profile != OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS) allowMcp(rules, mcpServers);
                return List.copyOf(rules);
            }
            rules.add(rule("read", "*", "allow"));
            rules.add(rule("glob", "*", "allow"));
            rules.add(rule("grep", "*", "allow"));
            if (profile == OpenCodeClient.SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY) {
                rules.add(rule("question", "*", "allow"));
            }
            rules.add(rule("read", ".env", "deny"));
            rules.add(rule("read", ".env.*", "deny"));
            rules.add(rule("read", ".env.example", "allow"));
            rules.add(rule("external_directory", "*", "deny"));
            allowMcp(rules, mcpServers);
            return List.copyOf(rules);
        }
        List<Map<String, String>> rules = new ArrayList<>(List.of(
                rule("external_directory", "*", "deny"),
                rule("bash", "*git*commit*", "deny"),
                rule("bash", "*git*commit-tree*", "deny"),
                rule("bash", "*git*update-ref*", "deny"),
                rule("bash", "*git*symbolic-ref*", "deny"),
                rule("bash", "*git*push*", "deny"),
                rule("bash", "*git*branch*", "deny"),
                rule("bash", "*git*checkout*", "deny"),
                rule("bash", "*git*switch*", "deny"),
                rule("bash", "*git*merge*", "deny"),
                rule("bash", "*git*rebase*", "deny"),
                rule("bash", "*git*cherry-pick*", "deny"),
                rule("bash", "*git*tag*", "deny"),
                rule("bash", "*git*stash*", "deny"),
                rule("bash", "*git*worktree*", "deny"),
                rule("bash", "*git*fetch*", "deny"),
                rule("bash", "*git*pull*", "deny"),
                rule("bash", "git reset --hard*", "deny"),
                rule("bash", "rm -rf*", "deny"),
                rule("bash", "rm *", "deny"),
                rule("bash", "unlink *", "deny"),
                rule("bash", "rmdir *", "deny"),
                rule("bash", "*systemctl*", "deny"),
                rule("bash", "*launchctl*", "deny"),
                rule("bash", "*brew*services*", "deny"),
                rule("bash", "*service*start*", "deny"),
                rule("bash", "*service*stop*", "deny"),
                rule("bash", "*service*restart*", "deny"),
                rule("todowrite", "*", "allow")));
        allowMcp(rules, mcpServers);
        return List.copyOf(rules);
    }

    private static boolean isNoTools(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.MACHINE_FINALIZER_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS;
    }

    private static void allowMcp(List<Map<String, String>> rules, List<String> servers) {
        if (servers == null) return;
        servers.stream().filter(java.util.Objects::nonNull).map(OpenCodePermissionPolicy::sanitize)
                .filter(value -> !value.isBlank()).distinct()
                .forEach(server -> rules.add(rule(server + "_*", "*", "allow")));
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static Map<String, String> rule(String permission, String pattern, String action) {
        return Map.of("permission", permission, "pattern", pattern, "action", action);
    }
}
