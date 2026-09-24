package io.opencode.loopper.service;

import io.opencode.loopper.runtime.ConfiguredRoleRuntime;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Workflow seam: explicit owner identity, no title parsing and no implicit thread context. */
@Component
public final class RoleSessions {
    private final ConfiguredRoleRuntime runtime;
    private final io.opencode.loopper.persistence.LoopperMapper owners;
    public RoleSessions(ConfiguredRoleRuntime runtime, io.opencode.loopper.persistence.LoopperMapper owners) { this.runtime = runtime; this.owners = owners; }
    public static void freeze(RoleSessions roles, String type, String id, String parentType, String parentId) {
        if (roles != null) roles.runtime.freezeOwner(type, id, parentType, parentId);
    }
    public static void freezeTask(RoleSessions roles, String taskId, String draftId, String designerId) {
        if (roles == null) return;
        boolean inheritedDraft = roles.runtime.hasOwnerSnapshot("LOOP_DRAFT", draftId);
        roles.runtime.freezeOwner("TASK", taskId, inheritedDraft ? "LOOP_DRAFT" : "DESIGNER_SESSION",
                inheritedDraft ? draftId : designerId);
    }
    public static OpenCodeSession create(RoleSessions roles, OpenCodeClient client, String type, String id,
            String purpose, Path directory, String title, OpenCodeModel model, SessionProfile profile) {
        if (roles == null || id == null || !client.supportsRoleConfiguration()) return client.createSession(directory, title, model, profile);
        return client.createRoleSession(directory, title, model, profile, roles.runtime.context(type, id, profile, purpose));
    }
    public static OpenCodeSession implementation(RoleSessions roles, OpenCodeClient client, String taskId,
            Path directory, String title, OpenCodeModel model) {
        if (roles == null || !client.supportsRoleConfiguration()) return client.createSession(directory, title, model);
        return create(roles, client, "TASK", taskId, null, directory, title, model, SessionProfile.IMPLEMENTATION);
    }
    public static OpenCodeSession readOnly(RoleSessions roles, OpenCodeClient client, String type, String id, String purpose,
            Path directory, String title, OpenCodeModel model) {
        if (roles == null || !client.supportsRoleConfiguration()) return client.createReadOnlySession(directory, title, model);
        return create(roles, client, type, id, purpose, directory, title, model, SessionProfile.GENERAL_READ_ONLY);
    }
    public static SessionCreationPlan prepare(RoleSessions roles, SessionCreationPlan plan, String type, String id, String purpose) {
        if (roles == null || id == null) return plan;
        return roles.runtime.prepare(plan, roles.runtime.context(type, id, plan.profile(), purpose));
    }
    public static <T> T render(RoleSessions roles, String type, String id, SessionProfile profile, String purpose,
                                java.util.function.Supplier<T> render) {
        return roles == null ? render.get() : roles.runtime.render(type, id, profile, purpose, render);
    }
    public static <T> T renderSession(RoleSessions roles, String sessionId, java.util.function.Supplier<T> render) {
        return roles == null ? render.get() : roles.runtime.renderSession(sessionId, render);
    }
    static SessionCreationPlan prepareCandidate(RoleSessions roles, SessionCreationPlan plan,
            String scopeType, String scopeId, String ownerType, String ownerId) {
        if (roles == null) return plan;
        String purpose = "JUDGE_RUN".equals(ownerType)
                ? roles.owners.findJudgeRun(ownerId).orElseThrow(() -> new ConflictException("ROLE_JUDGE_MISSING", "评审角色身份已不存在")).role() : null;
        return "PROJECT".equals(scopeType) ? prepare(roles, plan, ownerType, ownerId, purpose)
                : prepare(roles, plan, scopeType, scopeId, purpose);
    }
}
