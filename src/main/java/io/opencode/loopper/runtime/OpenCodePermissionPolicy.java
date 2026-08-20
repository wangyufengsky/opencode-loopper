package io.opencode.loopper.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the fail-closed OpenCode permission profile independently of HTTP transport. */
final class OpenCodePermissionPolicy {
    private OpenCodePermissionPolicy() { }

    static List<Map<String, String>> rules(OpenCodeClient.SessionProfile profile) {
        if (profile != OpenCodeClient.SessionProfile.IMPLEMENTATION) {
            List<Map<String, String>> rules = new ArrayList<>();
            rules.add(rule("*", "*", "deny"));
            if (isNoTools(profile)) {
                rules.add(rule("external_directory", "*", "deny"));
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
            return List.copyOf(rules);
        }
        return List.of(
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
                rule("todowrite", "*", "allow"));
    }

    private static boolean isNoTools(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.MACHINE_FINALIZER_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS;
    }

    private static Map<String, String> rule(String permission, String pattern, String action) {
        return Map.of("permission", permission, "pattern", pattern, "action", action);
    }
}
