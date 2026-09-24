package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import io.opencode.loopper.service.roles.RolePromptResources;
import java.util.*;
import org.springframework.stereotype.Component;
import static io.opencode.loopper.runtime.OpenCodeClient.*;

/** Connects explicit workflow ownership to frozen transport policy and prompt content. */
@Component
public final class ConfiguredRoleRuntime {
    private final RoleConfigurationService roles;
    public ConfiguredRoleRuntime(RoleConfigurationService roles) { this.roles = roles; }

    public void freezeOwner(String type, String id, String parentType, String parentId) {
        roles.freezeOwner(new RoleConfigurationService.OwnerRef(type, id), parentId == null ? null
                : new RoleConfigurationService.OwnerRef(parentType, parentId));
    }
    public boolean hasOwnerSnapshot(String type, String id) {
        return roles.hasOwnerSnapshot(new RoleConfigurationService.OwnerRef(type, id));
    }
    public RoleContext context(String type, String id, SessionProfile profile, String purpose) {
        return new RoleContext(type, id, roles.slotFor(profile, purpose));
    }
    public List<SessionPermissionRule> permissions(RoleContext context, SessionProfile profile,
            List<SessionPermissionRule> baseline, String internalServer) {
        if (context == null) return baseline;
        return roles.resolveFrozen(owner(context), context.slot()).map(role -> {
            if (!role.adapterProfile().equals(profile.name())) throw mismatch();
            Set<String> exact = new HashSet<>();
            baseline.stream().filter(r -> "allow".equals(r.action()) && !r.permission().contains("*") && !RoleConfigurationService.NATIVE_TOOLS.contains(r.permission()))
                    .forEach(r -> exact.add(r.permission()));
            return roles.compileNarrowedPermissions(role, baseline, exact, internalServer);
        }).orElse(baseline);
    }
    public SessionCreationPlan prepare(SessionCreationPlan plan, RoleContext context) {
        var permissions = permissions(context, plan.profile(), plan.permissionPolicy(), plan.internalMcpServer());
        String digest = permissionPolicyDigest(permissions);
        String requestSha = sessionCreationRequestSha256(plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model(), plan.profile(), digest, plan.creationCredential());
        var prepared = new SessionCreationPlan(plan.canonicalDirectory(), plan.exactTitle(), plan.runtimeGenerationId(),
                plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(), plan.model(), plan.profile(),
                permissions, digest, plan.creationCredential(), requestSha);
        remember(creationKey(plan), context, plan.profile(), permissions);
        return prepared;
    }
    public void remember(String sessionId, RoleContext context, SessionProfile profile, List<SessionPermissionRule> permissions) {
        if (context != null && roles.resolveFrozen(owner(context), context.slot()).isPresent())
            roles.freezeSession(sessionId, new RoleConfigurationService.RoleContext(owner(context), context.slot()),
                    profile, permissions, null);
    }
    public void created(String sessionId, SessionCreationPlan plan) {
        if (roles.sessionSnapshot(creationKey(plan)).isPresent()) roles.copySessionSnapshot(creationKey(plan), sessionId);
    }
    public void forked(String parentSessionId, String childSessionId) {
        if (roles.sessionSnapshot(parentSessionId).isPresent()) roles.copySessionSnapshot(parentSessionId, childSessionId);
    }
    public boolean frozenPolicy(SessionCreationPlan plan) {
        return roles.sessionSnapshot(creationKey(plan)).filter(s -> s.adapterProfile().equals(plan.profile().name())
                && s.permissionPolicy().equals(plan.permissionPolicy())).isPresent();
    }
    public void recordPrompt(String sessionId, PromptRequest business, PromptRequest effective) {
        if (business == null || roles.sessionSnapshot(sessionId).isEmpty()) return;
        String businessSha = promptRequestSha256(business);
        roles.recordPromptIdentity(sessionId, business.messageId() == null ? businessSha : business.messageId(),
                businessSha, promptRequestSha256(effective));
    }
    public List<String> allowedInternalTools(String sessionId, String server, List<String> tools) {
        var frozen = roles.sessionSnapshot(sessionId);
        if (frozen.isEmpty()) return tools;
        return tools.stream().filter(tool -> allowed(frozen.get().permissionPolicy(), server + "_" + tool)).toList();
    }
    /** Rechecks a direct MCP request against the immutable Session policy after owner/scope authorization. */
    public void requireInternalTool(String sessionId, String server, String tool) {
        if (sessionId == null || sessionId.isBlank() || server == null || server.isBlank()
                || tool == null || !tool.matches("[a-z][a-z0-9_]{0,127}"))
            throw new ConflictException("ROLE_INTERNAL_MCP_DENIED", "当前会话没有此 MCP 工具的冻结许可");
        var frozen = roles.sessionSnapshot(sessionId);
        if (frozen.isPresent() && !allowed(frozen.get().permissionPolicy(), server + "_" + tool))
            throw new ConflictException("ROLE_INTERNAL_MCP_DENIED", "当前会话没有此 MCP 工具的冻结许可");
    }
    static boolean allowed(List<SessionPermissionRule> policy, String name) {
        String action = "deny";
        for (var rule : policy) {
            String permission = rule.permission();
            if (permission.equals(name) || permission.equals("*")
                    || permission.endsWith("_*") && name.startsWith(permission.substring(0, permission.length() - 1)))
                action = rule.action();
        }
        return "allow".equals(action);
    }
    public PromptRequest prompt(String sessionId, PromptRequest request) {
        if (request == null) return null;
        var snapshot = roles.sessionSnapshot(sessionId);
        if (snapshot.isEmpty()) return request;
        var frozen = snapshot.get();
        var role = roles.resolveFrozen(frozen.context().owner(), frozen.context().slot()).orElseThrow(ConfiguredRoleRuntime::mismatch);
        if (!role.revisionId().equals(frozen.revisionId()) || !role.contentSha256().equals(frozen.revisionSha256())) throw mismatch();
        return request;
    }
    public <T> T render(String type, String id, SessionProfile profile, String purpose, java.util.function.Supplier<T> render) {
        if (id == null) return render.get();
        var context = context(type, id, profile, purpose);
        var role = roles.resolveFrozen(owner(context), context.slot());
        return role.isEmpty() ? render.get() : RolePromptResources.withFragments(role.get().fragments(), render);
    }
    public <T> T renderSession(String sessionId, java.util.function.Supplier<T> render) {
        var snapshot = roles.sessionSnapshot(sessionId);
        if (snapshot.isEmpty()) return render.get();
        var frozen = snapshot.get();
        var role = roles.resolveFrozen(frozen.context().owner(), frozen.context().slot()).orElseThrow(ConfiguredRoleRuntime::mismatch);
        if (!role.revisionId().equals(frozen.revisionId())) throw mismatch();
        return RolePromptResources.withFragments(role.fragments(), render);
    }
    private static String creationKey(SessionCreationPlan plan) { return "creation:" + plan.creationCredential(); }
    private static RoleConfigurationService.OwnerRef owner(RoleContext context) {
        return new RoleConfigurationService.OwnerRef(context.ownerType(), context.ownerId());
    }
    private static SessionFailure mismatch() { return new SessionFailure("ROLE_SNAPSHOT_MISMATCH", "角色执行快照与冻结配置不一致，请保留现场并检查配置版本"); }
}
