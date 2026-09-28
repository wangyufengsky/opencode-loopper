package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import org.junit.jupiter.api.Test;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import io.opencode.loopper.runtime.OpenCodeClient.SessionProfile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateReuseContextTest {
    @Test void rolesMustBeFrozenAndLiveSourceAuthorizationDisablesReuse() {
        var roles = mock(RoleConfigurationMapper.class); var knowledge = mock(WorkflowKnowledgeMapper.class);
        var configuration = mock(RoleConfigurationService.class);
        var resolved = mock(RoleConfigurationService.ResolvedRole.class);
        when(configuration.resolveFrozen(any(), anyString())).thenReturn(java.util.Optional.of(resolved));
        when(resolved.capabilities()).thenReturn(null);
        var context = new TemplateReuseContext(roles, knowledge, configuration);
        assertThat(context.key("task", SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)).isNull();
        when(roles.ownerSnapshot("TASK", "task")).thenReturn(new RoleConfigurationMapper.OwnerSnapshot("TASK", "task", null, null, 0, "legacy", "now"));
        assertThat(context.key("task", SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)).isNull();
        when(roles.ownerSnapshot("TASK", "task")).thenReturn(new RoleConfigurationMapper.OwnerSnapshot("TASK", "task", null, null, 52, "role-v1", "now"));
        assertThat(context.key("task", SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)).isEqualTo("role-v1");
        when(knowledge.binding("TASK", "task")).thenReturn(new WorkflowKnowledgeMapper.Binding("TASK", "task", "project", "[]", "now"));
        assertThat(context.key("task", SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)).isEqualTo("role-v1");
        when(knowledge.binding("TASK", "task")).thenReturn(new WorkflowKnowledgeMapper.Binding("TASK", "task", "project", "[{\"path\":\"/project\"}]", "now"));
        assertThat(context.key("task", SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)).isNull();
        when(knowledge.binding("TASK", "task")).thenReturn(null);
        when(roles.ownerSnapshot("TASK", "task")).thenReturn(new RoleConfigurationMapper.OwnerSnapshot("TASK", "task", null, null, 52, "role-v2", "now"));
        assertThat(context.key("task", SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)).isEqualTo("role-v2");
        assertThat(context.key("task", SessionProfile.TEMPLATE_ANALYSIS_NO_TOOLS)).isNull();
        when(resolved.capabilities()).thenReturn(java.util.List.of());
        assertThat(context.key("task", SessionProfile.TEMPLATE_ANALYSIS_NO_TOOLS)).isEqualTo("role-v2");
    }
}
