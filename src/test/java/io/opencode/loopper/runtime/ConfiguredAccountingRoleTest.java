package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ConfiguredAccountingRoleTest {
    @TempDir Path directory;

    @Test
    void writesOneImmutableDescriptorForTheFrozenOwnerAndExactMessage() throws Exception {
        var roles = mock(RoleConfigurationService.class);
        var owner = new RoleConfigurationService.OwnerRef("TASK", "task-1");
        when(roles.sessionSnapshot("session-1")).thenReturn(Optional.of(new RoleConfigurationService.SessionSnapshot(
                "session-1", new RoleConfigurationService.RoleContext(owner, "IMPLEMENTATION"), "business-revision",
                "a".repeat(64), "IMPLEMENTATION", List.of(), "b".repeat(64), null)));
        when(roles.resolveFrozen(owner, "ACCOUNTING_COMMAND")).thenReturn(Optional.of(
                new RoleConfigurationService.ResolvedRole("accounting", "accounting-revision", "c".repeat(64),
                        "ACCOUNTING_COMMAND", "ACCOUNTING_COMMAND", Map.of("accounting.instructions", "Frozen static instruction"),
                        "BASELINE", List.of(), List.of(), List.of(), "INHERIT_WORKFLOW", "ACCOUNTING_COMMAND")));
        var support = new ConfiguredAccountingRole(roles, new ObjectMapper(), directory);
        var session = new OpenCodeClient.OpenCodeSession("session-1", directory);
        var request = new OpenCodeClient.CommandRequest("aicoding", "start SYS-001 000123",
                "msg_loopper_aicoding_" + "d".repeat(32) + "_role");
        var first = support.prepare(session, request);
        var second = support.prepare(session, request);
        assertThat(second).isEqualTo(first);
        assertThat(first.path().getFileName().toString()).contains(first.sha256());
        var descriptor = new ObjectMapper().readTree(Files.readString(first.path()));
        assertThat(descriptor.path("sessionID").asText()).isEqualTo("session-1");
        assertThat(descriptor.path("messageID").asText()).isEqualTo(request.messageId());
        assertThat(descriptor.path("revisionID").asText()).isEqualTo("accounting-revision");
        assertThat(descriptor.path("prompt").asText()).isEqualTo("Frozen static instruction");
        assertThat(Files.readString(first.path())).doesNotContain("SYS-001", "000123");
        Files.writeString(first.path(), "changed");
        assertThatThrownBy(() -> support.prepare(session, request)).hasMessageContaining("不同的角色修订");
    }

    @Test
    void historicalUnmarkedCallNeedsNoDescriptor() {
        var support = new ConfiguredAccountingRole(mock(RoleConfigurationService.class), new ObjectMapper(), directory);
        var request = new OpenCodeClient.CommandRequest("aicoding", "complete", "msg_loopper_aicoding_historical");
        assertThat(support.prepare(new OpenCodeClient.OpenCodeSession("session-1", directory), request)).isNull();
    }
}
