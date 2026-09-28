package io.opencode.loopper.service.roles;

import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.assist.AssistToolCatalog;
import io.opencode.loopper.service.assist.AssistToolPolicyService;
import java.util.*;

/** Pure projection: declarations and policy estimates never prove a live session grant. */
final class RoleToolPreview {
    private RoleToolPreview() { }
    static List<RoleReadService.PreviewTool> tools(RoleConfigurationService.ResolvedRole role,
            List<OpenCodeClient.SessionPermissionRule> baseline, List<OpenCodeClient.SessionPermissionRule> compiled,
            List<AssistToolPolicyService.View> settings, String internal, String runtimeStatus) {
        var candidates = new LinkedHashMap<String, RoleReadService.PreviewTool>();
        var profile = OpenCodeClient.SessionProfile.valueOf(role.adapterProfile());
        RoleReadService.systemRequiredTools(profile).forEach(tool -> candidates.put(tool.name(), tool));
        var names = new LinkedHashSet<>(role.nativeTools());
        names.addAll(role.mcpTools());
        for (var rule : baseline) {
            String name = stable(rule.permission(), internal);
            if ("allow".equals(rule.action()) && !name.contains("*")
                    && ("BASELINE".equals(role.permissionMode()) || allowed(compiled, rule.permission(), false))) names.add(name);
        }
        if ("BASELINE".equals(role.permissionMode())) {
            if (profile == OpenCodeClient.SessionProfile.IMPLEMENTATION) names.addAll(RoleConfigurationService.NATIVE_TOOLS);
            AssistToolCatalog.allowed(profile.name()).forEach(name -> names.add("@loopper-assist/" + name));
        }
        names.remove(io.opencode.loopper.runtime.KnowledgeSessionPolicy.MARKER);
        for (String name : names) candidates.putIfAbsent(name, new RoleReadService.PreviewTool(name,
                server(name), RoleConfigurationService.NATIVE_TOOLS.contains(name) ? "NATIVE_POLICY"
                : role.mcpTools().contains(name) ? "ROLE_DECLARATION" : "BUNDLED_POLICY",
                role.requiredMcpTools().contains(name), false));
        var capabilities = RoleCapabilities.effective(role.adapterProfile(), role.capabilities());
        return candidates.values().stream().map(tool -> {
            String status;
            String reason;
            var setting = tool.server().equals("@loopper-assist") ? settings.stream()
                    .filter(item -> tool.name().equals("@loopper-assist/" + item.name())).findFirst().orElse(null) : null;
            if (!RoleCapabilities.allows(capabilities, tool.name(), internal)
                    || tool.server().equals("@loopper-assist") && !AssistToolCatalog.allowed(profile.name()).contains(tool.name().substring(16))) {
                status = "ROLE_DISABLED"; reason = "角色能力或阶段上限未授权此工具。";
            } else if (setting != null && !setting.enabled()) {
                status = "POLICY_DISABLED"; reason = setting.source().equals("PROJECT") ? "所选项目已关闭此工具。" : "全局策略已关闭此工具。";
            } else if (tool.server().equals("exact")) {
                status = "DISCOVERY_REQUIRED"; reason = "需在会话中发现精确工具并核对来源策略。";
            } else if (compiled.isEmpty()) {
                status = "CONFIGURATION_BLOCKED"; reason = "角色必需条件未满足，请先处理配置提示。";
            } else if (!allowed(compiled, actual(tool.name(), internal), profile == OpenCodeClient.SessionProfile.IMPLEMENTATION)) {
                status = "ROLE_DISABLED"; reason = "角色工具清单或阶段权限未授权此工具。";
            } else if (!tool.server().equals("native") && !"CONNECTED".equals(runtimeStatus)) {
                status = "RUNTIME_UNAVAILABLE"; reason = "运行环境尚未就绪，需恢复连接后重新核定。";
            } else if (tool.server().equals("@loopper-assist")) {
                status = "DISCOVERY_REQUIRED"; reason = "策略已开启，仍需核对辅助服务工具发现和任务作用域。";
            } else {
                status = "SCOPE_REQUIRED"; reason = "配置允许，调用前仍需核对会话、任务作用域和冻结权限。";
            }
            return new RoleReadService.PreviewTool(tool.name(), tool.server(), tool.source(), tool.required(), false, status, reason);
        }).toList();
    }
    private static String server(String name) {
        return RoleConfigurationService.NATIVE_TOOLS.contains(name) ? "native" : name.startsWith("@loopper-assist/")
                ? "@loopper-assist" : name.startsWith("@loopper-internal/") ? "@loopper-internal" : "exact";
    }
    private static String stable(String name, String internal) {
        if (name.startsWith(internal + "_assist_")) return "@loopper-assist/" + name.substring((internal + "_assist_").length());
        return name.startsWith(internal + "_") ? "@loopper-internal/" + name.substring((internal + "_").length()) : name;
    }
    private static String actual(String name, String internal) {
        return name.replace("@loopper-assist/", internal + "_assist_").replace("@loopper-internal/", internal + "_");
    }
    private static boolean allowed(List<OpenCodeClient.SessionPermissionRule> rules, String name, boolean implicit) {
        boolean allowed = implicit && RoleConfigurationService.NATIVE_TOOLS.contains(name);
        for (var rule : rules) if (rule.pattern().equals("*") && (rule.permission().equals("*") || rule.permission().equals(name)
                || rule.permission().endsWith("_*") && name.startsWith(rule.permission().substring(0, rule.permission().length() - 1))))
            allowed = rule.action().equals("allow");
        return allowed;
    }
}
