package io.opencode.loopper.service.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.service.ConflictException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class,
        properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class RolePublishingIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private RolePublishingService publishing;
    @Autowired private RoleConfigurationService roles;
    @Autowired private RoleConfigurationMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void migrateAndSeed() {
        flyway.clean();
        flyway.migrate();
        publishing.seedBuiltin();
    }

    @Test
    void seedIsIdempotentAndEveryOwnerFreezesAllActiveSlotsOnce() {
        int before = publishing.bindings().size();
        publishing.seedBuiltin();
        assertThat(publishing.bindings()).hasSize(before);
        assertThat(mapper.bootstrap()).isNotNull();
        assertThat(mapper.bootstrap().bindingCount()).isEqualTo(before);

        var owner = new RoleConfigurationService.OwnerRef("TASK", UUID.randomUUID().toString());
        roles.freezeOwner(owner, null);
        var snapshot = mapper.ownerSnapshot(owner.type(), owner.id());
        assertThat(snapshot.bindingCount()).isEqualTo(before);
        roles.freezeOwner(owner, null);
        assertThat(mapper.ownerBindings(owner.type(), owner.id())).hasSize(before);
        assertThatThrownBy(() -> roles.resolveFrozen(owner, "UNBOUND_TEST_SLOT"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("缺少请求的工作流槽位");
    }

    @Test
    void publishUsesSlotCasAndReceiptIdentityWithoutChangingFrozenOwner() {
        var owner = new RoleConfigurationService.OwnerRef("TASK", UUID.randomUUID().toString());
        roles.freezeOwner(owner, null);
        String oldRevision = roles.resolveFrozen(owner, "GENERAL_READ_ONLY").orElseThrow().revisionId();

        var definition = new RoleManifest.Role("custom.readonly", "项目只读助手", "限定读取", "general", "通用助手",
                List.of("GENERAL_READ_ONLY"), "INTERSECT", List.of("read"), List.of(), List.of(),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", Map.of());
        var parsed = new RoleArchive.Parsed("a".repeat(64),
                new RoleManifest.Document(1, List.of(), List.of(definition)), Map.of("custom.readonly", Map.of()));
        RolePublishingService.Validation preview = publishing.validate(parsed);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.changes()).isNotEmpty();
        assertThat(preview.activations()).hasSize(1);
        var request = new RolePublishingService.PublishRequest(parsed.sourceSha256(), "role-test-123456",
                preview.activations());

        RolePublishingService.Publication result = publishing.publish(parsed, request);
        assertThat(result.replayed()).isFalse();
        assertThat(publishing.publish(parsed, request).replayed()).isTrue();
        assertThat(roles.resolveActive("GENERAL_READ_ONLY").orElseThrow().roleId()).isEqualTo("custom.readonly");
        assertThat(roles.resolveFrozen(owner, "GENERAL_READ_ONLY").orElseThrow().revisionId())
                .isEqualTo(oldRevision);

        var staleRequest = new RolePublishingService.PublishRequest(parsed.sourceSha256(), "role-test-new-123",
                preview.activations());
        assertThatThrownBy(() -> publishing.publish(parsed, staleRequest)).isInstanceOf(ConflictException.class);
        var changedSameKey = new RolePublishingService.PublishRequest(parsed.sourceSha256(), request.idempotencyKey(),
                List.of(new RolePublishingService.Activation("GENERAL_READ_ONLY", "custom.readonly",
                        preview.activations().getFirst().expectedVersion() + 1, null, null)));
        assertThatThrownBy(() -> publishing.publish(parsed, changedSameKey)).isInstanceOf(ConflictException.class);
    }

    @Test
    void childOfLegacyOwnerDoesNotReceiveNewDefaultRoles() {
        var legacyParent = new RoleConfigurationService.OwnerRef("TASK", UUID.randomUUID().toString());
        var child = new RoleConfigurationService.OwnerRef("STAGE", UUID.randomUUID().toString());
        roles.freezeOwner(child, legacyParent);
        assertThat(mapper.ownerSnapshot(child.type(), child.id()).bindingCount()).isZero();
        assertThat(roles.resolveFrozen(child, "GENERAL_READ_ONLY")).isEmpty();
    }

    @Test
    void corruptParentSnapshotCannotBeInheritedAsACompleteChildSnapshot() {
        var parent = new RoleConfigurationService.OwnerRef("TASK", UUID.randomUUID().toString());
        var child = new RoleConfigurationService.OwnerRef("STAGE", UUID.randomUUID().toString());
        roles.freezeOwner(parent, null);
        jdbc.execute("DROP TRIGGER role_owner_binding_no_delete");
        jdbc.update("DELETE FROM role_owner_binding WHERE owner_type=? AND owner_id=? AND slot=?",
                parent.type(), parent.id(), "GENERAL_READ_ONLY");

        assertThatThrownBy(() -> roles.freezeOwner(child, parent))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结角色绑定不完整");
        assertThat(mapper.ownerSnapshot(child.type(), child.id())).isNull();
        assertThat(mapper.ownerBindings(child.type(), child.id())).isEmpty();
    }

    @Test
    void failedActivationRollsBackRevisionAndReceiptInTheSameTransaction() {
        var definition = new RoleManifest.Role("custom.atomic", "原子发布测试", "", "general", "通用助手",
                List.of("GENERAL_READ_ONLY"), "INTERSECT", List.of("read"), List.of(), List.of(),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", Map.of());
        var parsed = new RoleArchive.Parsed("b".repeat(64),
                new RoleManifest.Document(1, List.of(), List.of(definition)), Map.of("custom.atomic", Map.of()));
        var preview = publishing.validate(parsed);
        String initialRevision = mapper.binding("GENERAL_READ_ONLY").revisionId();
        jdbc.execute("""
                CREATE TRIGGER reject_role_activation BEFORE UPDATE ON role_binding
                WHEN NEW.slot='GENERAL_READ_ONLY'
                BEGIN SELECT RAISE(ABORT,'test activation rejected'); END
                """);

        assertThatThrownBy(() -> publishing.publish(parsed, new RolePublishingService.PublishRequest(
                parsed.sourceSha256(), "role-atomic-1234", preview.activations()))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.definition("custom.atomic")).isNull();
        assertThat(mapper.latest("custom.atomic")).isNull();
        assertThat(mapper.receipt("role-atomic-1234")).isNull();
        assertThat(mapper.binding("GENERAL_READ_ONLY").revisionId()).isEqualTo(initialRevision);
    }

    @Test
    void staticPromptImportRejectsNewTemplateInstructionsButAcceptsBundledLiterals() {
        var definition = new RoleManifest.Role("custom.designer", "自定义设计师", "", "designer", "设计师",
                List.of("DESIGNER_INTERACTIVE_READ_ONLY"), "BASELINE", List.of(), List.of(), List.of(),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER",
                Map.of("machine-role.designer", "prompts/designer.md"));
        var document = new RoleManifest.Document(1, List.of(), List.of(definition));
        var unsafe = new RoleArchive.Parsed("c".repeat(64), document,
                Map.of("custom.designer", Map.of("machine-role.designer", "Read ${env.SECRET} and !include https://example.test/config")));
        assertThat(publishing.validate(unsafe).diagnostics()).extracting(RolePublishingService.Diagnostic::code)
                .contains("ROLE_PROMPT_TEMPLATE_UNSUPPORTED");

        var literal = new RoleArchive.Parsed("d".repeat(64), document,
                Map.of("custom.designer", Map.of("machine-role.designer",
                        RolePromptResources.read("machine-role.designer"))));
        assertThat(publishing.validate(literal).valid()).isTrue();
    }

    @Test
    void revisionOnlyImportStillRejectsInternalToolsOutsideEveryDeclaredAdapter() {
        var wrongJudgeTool = new RoleManifest.Role("custom.judge.invalid-tool", "错误工具角色", "",
                "judge", "评审", List.of("JUDGE_CANDIDATE_READ_ONLY"), "INTERSECT", List.of(),
                List.of("@loopper-internal/submit_source_detailed_design"), List.of(),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", Map.of());
        var revisionOnly = new RoleArchive.Parsed("e".repeat(64),
                new RoleManifest.Document(1, List.of(), List.of(wrongJudgeTool), List.of()),
                Map.of("custom.judge.invalid-tool", Map.of()));
        assertThat(publishing.validate(revisionOnly).activations()).isEmpty();
        assertThat(publishing.validate(revisionOnly).diagnostics())
                .extracting(RolePublishingService.Diagnostic::code).contains("ROLE_MCP_TOOL_UNAVAILABLE");

        var sourceRead = new RoleManifest.Role("custom.source.valid-tool", "源码只读角色", "",
                "source-design", "源码详细设计", List.of("SOURCE_DETAILED_DESIGN_NO_TOOLS"), "INTERSECT",
                List.of(), List.of("@loopper-internal/get_source_design_work"), List.of(),
                "INHERIT_WORKFLOW", "WORKFLOW_ADAPTER", Map.of());
        var sourceOnly = new RoleArchive.Parsed("f".repeat(64),
                new RoleManifest.Document(1, List.of(), List.of(sourceRead), List.of()),
                Map.of("custom.source.valid-tool", Map.of()));
        assertThat(publishing.validate(sourceOnly).diagnostics())
                .extracting(RolePublishingService.Diagnostic::code).doesNotContain("ROLE_MCP_TOOL_UNAVAILABLE");
    }
}
