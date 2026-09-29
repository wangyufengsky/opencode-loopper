package io.opencode.loopper.service.roles;

import static org.assertj.core.api.Assertions.*;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.ConflictException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = LoopperApplication.class,
        properties = {"loopper.opencode.mode=fake", "loopper.monitor-delay=1h"})
class RoleWorkInstructionsIntegrationTest {
    private static final String SLOT = "GENERAL_READ_ONLY", ROLE = "custom.node-designer";
    @Autowired private Flyway flyway;
    @Autowired private RolePublishingService publishing;
    @Autowired private RoleConfigurationService roles;
    @Autowired private RoleReadService reads;
    @Autowired private RoleArchive archive;
    @Autowired private RoleConfigurationMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private tools.jackson.databind.ObjectMapper json;

    @BeforeEach void seed() {
        flyway.clean(); flyway.migrate(); publishing.seedBuiltin();
    }

    @Test void professionalInstructionsRoundTripAndChangeOnlyNewlySelectedWork() {
        String first = publish("设计方案并列出候选阶段。${example} 保持原文。", "a");
        var owner = new RoleConfigurationService.OwnerRef("WORKFLOW_NODE", "node-one");
        var selected = roles.freezeSelection(owner, SLOT, ROLE, first);
        assertThat(selected.workInstructions()).isEqualTo("设计方案并列出候选阶段。${example} 保持原文。");
        assertThat(mapper.ownerSnapshot(owner.type(), owner.id()).bindingCount()).isEqualTo(1);
        assertThat(roles.freezeSelection(owner, SLOT, ROLE, first)).isEqualTo(selected);
        var snapshot = roles.freezeSession("node-session", new RoleConfigurationService.RoleContext(owner, SLOT),
                OpenCodeClient.SessionProfile.GENERAL_READ_ONLY,
                List.of(new OpenCodeClient.SessionPermissionRule("*", "*", "deny")), null);

        String second = publish("先明确限制条件，再交付设计和候选计划。", "b");
        assertThat(second).isNotEqualTo(first);
        assertThat(roles.resolveActive(SLOT).orElseThrow().revisionId()).isEqualTo(second);
        assertThat(roles.resolveFrozen(owner, SLOT).orElseThrow()).isEqualTo(selected);
        assertThat(roles.sessionSnapshot("node-session").orElseThrow()).isEqualTo(snapshot);
        assertThat(reads.compare(ROLE, first, second).changes()).extracting(RolePublishingService.Change::path)
                .contains("/roles/" + ROLE + "/workInstructions");

        var exported = archive.parse(reads.export(ROLE, first));
        assertThat(exported.manifest().roles().getFirst().workInstructions()).isEqualTo(selected.workInstructions());
        var validation = publishing.validate(exported);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.roles().getFirst().contentSha256()).isEqualTo(selected.contentSha256());
        var replay = publishing.publish(exported, new RolePublishingService.PublishRequest(exported.sourceSha256(),
                "instructions-roundtrip", validation.activations()));
        assertThat(replay.roles().getFirst().revisionId()).isEqualTo(first);
        assertThat(roles.resolveFrozen(owner, SLOT).orElseThrow().revisionId()).isEqualTo(first);
    }

    @Test void explicitRevisionDoesNotResolveToActiveOrLatestAndCannotBeChangedUnderExistingOwner() {
        String first = publish("初版专业说明", "a"), second = publish("新版专业说明", "b");
        var owner = new RoleConfigurationService.OwnerRef("WORKFLOW_NODE", "selected-old");
        assertThat(roles.freezeSelection(owner, SLOT, ROLE, first).revisionId()).isEqualTo(first);
        assertThatThrownBy(() -> roles.freezeSelection(owner, SLOT, ROLE, second))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已冻结不同角色");
        var child = new RoleConfigurationService.OwnerRef("WORKFLOW_ATTEMPT", "attempt-one");
        roles.freezeOwner(child, owner);
        assertThat(roles.resolveFrozen(child, SLOT).orElseThrow().revisionId()).isEqualTo(first);
        assertThatThrownBy(() -> roles.freezeSelection(child, SLOT, ROLE, first))
                .isInstanceOf(ConflictException.class);
        assertThat(mapper.ownerBindings(owner.type(), owner.id())).singleElement()
                .extracting(RoleConfigurationMapper.OwnerBinding::revisionId).isEqualTo(first);
    }

    @Test void wrongRoleSlotOrMissingVersionCannotLeavePartialSelection() {
        String revision = publish("专业说明", "a");
        var owner = new RoleConfigurationService.OwnerRef("WORKFLOW_NODE", "invalid-selection");
        assertThatThrownBy(() -> roles.freezeSelection(owner, SLOT, "builtin.general", revision))
                .isInstanceOf(ConflictException.class).hasMessageContaining("不属于");
        assertThatThrownBy(() -> roles.freezeSelection(owner, "IMPLEMENTATION", ROLE, revision))
                .isInstanceOf(ConflictException.class).hasMessageContaining("不支持");
        assertThatThrownBy(() -> roles.freezeSelection(owner, SLOT, ROLE, "missing"))
                .isInstanceOf(ConflictException.class);
        assertThat(mapper.ownerSnapshot(owner.type(), owner.id())).isNull();
        assertThat(mapper.ownerBindings(owner.type(), owner.id())).isEmpty();
        jdbc.execute("CREATE TRIGGER reject_selected_snapshot BEFORE INSERT ON role_owner_snapshot "
                + "WHEN NEW.owner_id='invalid-selection' BEGIN SELECT RAISE(ABORT,'test snapshot failure'); END");
        assertThatThrownBy(() -> roles.freezeSelection(owner, SLOT, ROLE, revision)).isInstanceOf(RuntimeException.class);
        assertThat(mapper.ownerBindings(owner.type(), owner.id())).isEmpty();
        assertThat(mapper.ownerSnapshot(owner.type(), owner.id())).isNull();
        jdbc.execute("DROP TRIGGER reject_selected_snapshot");
    }

    @Test void absentProfessionalInstructionsPreserveLegacyManifestAndSemanticHash() {
        var current = mapper.latest("builtin.router");
        assertThat(current.manifestJson()).doesNotContain("workInstructions");
        var legacy = json.readValue(current.manifestJson(), RoleManifest.Role.class);
        assertThat(legacy.workInstructions()).isNull();
        assertThat(json.writeValueAsString(legacy)).doesNotContain("workInstructions");
        var parsed = archive.parse(reads.export(current.roleId(), current.revisionId()));
        assertThat(publishing.validate(parsed).roles().getFirst().contentSha256()).isEqualTo(current.contentSha256());
        assertThat(roles.resolveRevision(current.revisionId(), "ROUTER_NO_TOOLS").workInstructions()).isNull();
    }

    private String publish(String instructions, String identity) {
        var role = new RoleManifest.Role(ROLE, "节点设计师", "设计与规划", "general", "通用",
                List.of(SLOT), "INTERSECT", List.of(), List.of(), List.of(), "INHERIT_WORKFLOW",
                "WORKFLOW_ADAPTER", Map.of(), List.of(), instructions);
        var parsed = new RoleArchive.Parsed(identity.repeat(64),
                new RoleManifest.Document(2, List.of(), List.of(role)), Map.of());
        var validation = publishing.validate(parsed);
        assertThat(validation.diagnostics()).isEmpty();
        return publishing.publish(parsed, new RolePublishingService.PublishRequest(parsed.sourceSha256(),
                "work-instructions-" + identity, validation.activations())).roles().getFirst().revisionId();
    }
}
