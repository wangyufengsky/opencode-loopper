package io.opencode.loopper.service.roles;

import java.util.List;
import java.util.Map;

/** Declarative content only. Workflow authority belongs to the bound server slot. */
public final class RoleManifest {
    private RoleManifest() { }

    public record Slot(String slot, String adapterProfile, String displayName, String purpose) { }

    public record Role(String roleId, String displayName, String description,
                       String groupKey, String groupLabel, List<String> allowedSlots,
                       String permissionMode, List<String> nativeTools, List<String> mcpTools,
                       List<String> requiredMcpTools, String modelPolicy, String runtimePolicy,
                       Map<String, String> prompts, List<RoleCapabilities.Capability> capabilities,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                       String workInstructions) {
        public Role(String roleId, String displayName, String description, String groupKey, String groupLabel,
                    List<String> allowedSlots, String permissionMode, List<String> nativeTools, List<String> mcpTools,
                    List<String> requiredMcpTools, String modelPolicy, String runtimePolicy, Map<String, String> prompts,
                    List<RoleCapabilities.Capability> capabilities) {
            this(roleId, displayName, description, groupKey, groupLabel, allowedSlots, permissionMode,
                    nativeTools, mcpTools, requiredMcpTools, modelPolicy, runtimePolicy, prompts, capabilities, null);
        }
        public Role(String roleId, String displayName, String description, String groupKey, String groupLabel,
                    List<String> allowedSlots, String permissionMode, List<String> nativeTools, List<String> mcpTools,
                    List<String> requiredMcpTools, String modelPolicy, String runtimePolicy, Map<String, String> prompts) {
            this(roleId, displayName, description, groupKey, groupLabel, allowedSlots, permissionMode,
                    nativeTools, mcpTools, requiredMcpTools, modelPolicy, runtimePolicy, prompts, null);
        }
        public Role {
            if (workInstructions != null && (workInstructions.isBlank() || workInstructions.length() > 32_000))
                throw new IllegalArgumentException("角色专业说明必须为非空文本且不超过 32000 字符");
            capabilities = capabilities == null ? null : List.copyOf(capabilities);
            allowedSlots = allowedSlots == null ? List.of() : List.copyOf(allowedSlots);
            nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
            mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
            requiredMcpTools = requiredMcpTools == null ? List.of() : List.copyOf(requiredMcpTools);
            modelPolicy = modelPolicy == null ? "INHERIT_WORKFLOW" : modelPolicy;
            runtimePolicy = runtimePolicy == null ? "WORKFLOW_ADAPTER" : runtimePolicy;
            prompts = prompts == null ? Map.of() : Map.copyOf(prompts);
        }
    }

    public record Binding(String slot, String roleId) { }

    public record Document(int schemaVersion, List<Slot> slots, List<Role> roles, List<Binding> bindings) {
        public Document {
            slots = slots == null ? List.of() : List.copyOf(slots);
            roles = roles == null ? List.of() : List.copyOf(roles);
            bindings = bindings == null ? null : List.copyOf(bindings);
        }
        public Document(int schemaVersion, List<Slot> slots, List<Role> roles) {
            this(schemaVersion, slots, roles, null);
        }
    }
}
