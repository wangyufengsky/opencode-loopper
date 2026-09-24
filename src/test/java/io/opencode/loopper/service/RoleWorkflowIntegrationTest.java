package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.roles.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = LoopperApplication.class, properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class RoleWorkflowIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired RoleConfigurationService roles;
    @Autowired RolePublishingService publishing;
    @Autowired RoleReadService reads;
    @Autowired RoleArchive archives;
    @Autowired TaskSemanticRouter router;
    @Autowired OpenCodeClient client;
    @TempDir Path directory;

    @BeforeEach void reset() {
        flyway.clean(); flyway.migrate(); publishing.seedBuiltin();
        ((FakeOpenCodeClient) client).reset();
    }

    @Test void importedRoleChangesRealRouterRequestsWhileOlderOwnersAndUserFactsRemainFrozen() throws Exception {
        var oldOwner = new RoleConfigurationService.OwnerRef("DESIGNER_SESSION", UUID.randomUUID().toString());
        roles.freezeOwner(oldOwner, null);
        String original = RolePromptResources.read("prompt.v1.TaskSemanticRouter.prompt.segment0");
        String requirement = "用户引用的原文：" + original + "\n请检查 Java 文件。";
        byte[] exported = reads.export("builtin.router", null);
        var parsed = archives.parse(rewrite(exported, (name, text) -> name.equals("manifest.yaml")
                ? text.replace("builtin.router", "custom.router") : name.endsWith("prompt.v1.TaskSemanticRouter.prompt.segment0.md")
                ? "这是导入配置的路由角色。\n" : text));
        var validation = publishing.validate(parsed);
        assertThat(validation.diagnostics()).isEmpty();
        publishing.publish(parsed, new RolePublishingService.PublishRequest(parsed.sourceSha256(), "router-role-integration", validation.activations()));
        var newOwner = new RoleConfigurationService.OwnerRef("DESIGNER_SESSION", UUID.randomUUID().toString());
        roles.freezeOwner(newOwner, null);
        // Re-initializing after a restart must preserve an administrator's binding.
        publishing.seedBuiltin();
        var oldRun = router.start(oldOwner.id(), directory, requirement);
        var newRun = router.start(newOwner.id(), directory, requirement);
        assertThat(oldRun.errorCode()).isNull(); assertThat(newRun.errorCode()).isNull();
        var fake = (FakeOpenCodeClient) client;
        assertThat(fake.promptForSession(oldRun.externalSessionId())).startsWith(original).contains(requirement);
        assertThat(fake.promptForSession(newRun.externalSessionId())).startsWith("这是导入配置的路由角色。\n").contains(requirement);
        var oldSnapshot = roles.sessionSnapshot(oldRun.externalSessionId()).orElseThrow();
        var newSnapshot = roles.sessionSnapshot(newRun.externalSessionId()).orElseThrow();
        assertThat(newSnapshot.revisionId()).isNotEqualTo(oldSnapshot.revisionId());
        assertThat(newSnapshot.permissionPolicy()).isEqualTo(oldSnapshot.permissionPolicy());
        assertThat(newSnapshot.permissionPolicy()).noneMatch(rule -> "allow".equals(rule.action()));
    }

    @Test void narrowingAReadOnlyRoleChangesNewSessionPolicyWithoutGrantingOtherRolesTools() throws Exception {
        byte[] exported = reads.export("builtin.general", null);
        var parsed = archives.parse(rewrite(exported, (name, text) -> !name.equals("manifest.yaml") ? text
                : text.replace("builtin.general", "custom.reader").replace("permissionMode: BASELINE", "permissionMode: INTERSECT")
                .replace("nativeTools: []", "nativeTools: [read]")));
        var validation = publishing.validate(parsed);
        assertThat(validation.diagnostics()).isEmpty();
        publishing.publish(parsed, new RolePublishingService.PublishRequest(parsed.sourceSha256(), "reader-role-integration", validation.activations()));
        var owner = new RoleConfigurationService.OwnerRef("TASK", UUID.randomUUID().toString());
        roles.freezeOwner(owner, null);
        var session = client.createRoleSession(directory, "Configured reader", null, OpenCodeClient.SessionProfile.GENERAL_READ_ONLY,
                new OpenCodeClient.RoleContext(owner.type(), owner.id(), "GENERAL_READ_ONLY"));
        var policy = roles.sessionSnapshot(session.id()).orElseThrow().permissionPolicy();
        assertThat(policy).anyMatch(rule -> rule.permission().equals("read") && rule.action().equals("allow"));
        assertThat(policy).noneMatch(rule -> Set.of("glob", "grep", "bash", "edit").contains(rule.permission()) && rule.action().equals("allow"));
        assertThat(policy).anyMatch(rule -> rule.permission().equals("read") && rule.pattern().equals(".env") && rule.action().equals("deny"));
        var fork = client.forkSession(session, "message-before-fork");
        var copied = roles.sessionSnapshot(fork.id()).orElseThrow();
        assertThat(copied.permissionPolicy()).isEqualTo(policy);
        assertThat(copied.revisionId()).isEqualTo(roles.sessionSnapshot(session.id()).orElseThrow().revisionId());
    }

    @Test void everyBuiltinProfileKeepsItsOrderedAdapterPermissions() {
        var owner = new RoleConfigurationService.OwnerRef("TASK", UUID.randomUUID().toString());
        roles.freezeOwner(owner, null);
        for (var profile : OpenCodeClient.SessionProfile.values()) {
            var baseline = io.opencode.loopper.runtime.OpenCodePermissionPolicy.previewRules(profile, List.of(), "loopper-internal-equivalence");
            var role = roles.resolveFrozen(owner, profile.name()).orElseThrow();
            assertThat(roles.compileNarrowedPermissions(role, baseline, Set.of(), "loopper-internal-equivalence"))
                    .as("ordered default permissions for %s", profile).isEqualTo(baseline);
        }
    }

    private byte[] rewrite(byte[] source, java.util.function.BiFunction<String, String, String> rewrite) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(source)); var result = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.putNextEntry(new ZipEntry(entry.getName()));
                result.write(rewrite.apply(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                result.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
