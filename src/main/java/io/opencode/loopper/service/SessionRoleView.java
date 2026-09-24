package io.opencode.loopper.service;

import io.opencode.loopper.runtime.OpenCodeClient.SessionPermissionRule;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads only after the calling workflow has checked its own Session ownership. Never exposes Prompt bodies. */
@Component
public final class SessionRoleView {
    private final RoleConfigurationService roles;
    public SessionRoleView(RoleConfigurationService roles) { this.roles = roles; }
    public record Summary(boolean configured, String roleId, String revisionId, String revisionSha256,
                          String slot, String adapterProfile, String adapterVersion, List<SessionPermissionRule> permissions,
                          String permissionSha256) { }
    public Summary read(String type, String ownerId, String externalSessionId) {
        if (externalSessionId == null) return legacy();
        var snapshot = roles.sessionSnapshot(externalSessionId).orElse(null);
        if (snapshot == null) return legacy();
        if (!type.equals(snapshot.context().owner().type()) || !ownerId.equals(snapshot.context().owner().id()))
            throw new ConflictException("ROLE_SESSION_OWNER_MISMATCH", "角色快照不属于当前流程");
        var role = roles.resolveFrozen(snapshot.context().owner(), snapshot.context().slot())
                .orElseThrow(() -> new ConflictException("ROLE_SNAPSHOT_MISSING", "会话冻结角色版本缺失"));
        return new Summary(true, role.roleId(), snapshot.revisionId(), snapshot.revisionSha256(),
                snapshot.context().slot(), snapshot.adapterProfile(), snapshot.adapterVersion(), snapshot.permissionPolicy(), snapshot.permissionPolicySha256());
    }
    private static Summary legacy() { return new Summary(false, null, null, null, null, null, null, List.of(), null); }
}
