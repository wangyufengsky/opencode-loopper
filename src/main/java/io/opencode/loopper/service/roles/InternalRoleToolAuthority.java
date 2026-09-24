package io.opencode.loopper.service.roles;

import io.opencode.loopper.runtime.ConfiguredRoleRuntime;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.service.ConflictException;
import org.springframework.stereotype.Component;

/** Uses the active private-MCP generation after a handler has proved its owner or signed scope. */
@Component
public final class InternalRoleToolAuthority {
    private final ConfiguredRoleRuntime roles;
    private final InternalMcpRuntimeAccess runtime;

    public InternalRoleToolAuthority(ConfiguredRoleRuntime roles, InternalMcpRuntimeAccess runtime) {
        this.roles = roles;
        this.runtime = runtime;
    }

    public void require(String externalSessionId, String toolName) {
        String server = runtime.current().orElseThrow(() -> new ConflictException(
                "ROLE_INTERNAL_MCP_UNAVAILABLE", "当前内部 MCP 运行代际不可用")).serverName();
        roles.requireInternalTool(externalSessionId, server, toolName);
    }
}
