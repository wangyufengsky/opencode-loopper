package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowKnowledgeAccessTest {
    @Test void metadataUsesFrozenRoleCapabilitiesAndCannotAcquireCurrentDefaults() {
        var mapper = mock(WorkflowKnowledgeMapper.class);
        var roles = mock(RoleConfigurationMapper.class);
        var binding = mock(WorkflowKnowledgeBindings.class);
        var json = new ObjectMapper();
        var access = new WorkflowKnowledgeAccess(mapper, roles, binding, mock(MachineCandidateSubmission.class), List.of(), json);
        var snapshot = mock(RoleConfigurationMapper.SessionSnapshot.class);
        when(snapshot.revisionId()).thenReturn("frozen");
        when(snapshot.adapterProfile()).thenReturn("GENERAL_READ_ONLY");
        when(roles.sessionSnapshot("session")).thenReturn(snapshot);
        var revision = mock(RoleConfigurationMapper.Revision.class);
        when(roles.revision("frozen")).thenReturn(revision);
        var scope = new AssistScopeService.Scope("session", "owner", "project", null, null, null, null,
                "GENERAL_READ_ONLY", java.nio.file.Path.of("/scratch"), List.of(), List.of());
        when(revision.manifestJson()).thenReturn("{\"capabilities\":[\"PROJECT_KNOWLEDGE\"]}");
        assertThat(access.evidenceOnly(scope)).isTrue();
        when(revision.manifestJson()).thenReturn("{}");
        assertThat(access.evidenceOnly(scope)).isFalse();
        when(revision.manifestJson()).thenReturn("{\"capabilities\":[]}");
        assertThatThrownBy(() -> access.require(scope)).isInstanceOf(AssistFailure.class);
        verify(roles, never()).binding(anyString());
    }

    @Test void specializedCandidateRequiresActiveOwnerGuardAndCannotSwapProjectOrReadBusinessSql() {
        var mapper=mock(WorkflowKnowledgeMapper.class);var roles=mock(RoleConfigurationMapper.class);
        var bindings=mock(WorkflowKnowledgeBindings.class);var submissions=mock(MachineCandidateSubmission.class);
        var guard=mock(CandidateRunGuard.class);
        var access=new WorkflowKnowledgeAccess(mapper,roles,bindings,submissions,List.of(guard),new ObjectMapper());
        var session=new AssistMapper.Session("session","generation","/scratch","SOURCE_DESIGN_REVIEW_NO_TOOLS","[]",
                "[\"search_project_knowledge\",\"query_database_readonly\"]","t");
        var role=mock(RoleConfigurationMapper.SessionSnapshot.class);
        when(role.ownerType()).thenReturn("SOURCE_TEMPLATE_MODEL_RUN");when(role.ownerId()).thenReturn("model");
        when(roles.sessionSnapshot("session")).thenReturn(role);
        when(mapper.binding("SOURCE_TEMPLATE_MODEL_RUN","model")).thenReturn(new WorkflowKnowledgeMapper.Binding(
                "SOURCE_TEMPLATE_MODEL_RUN","model","project","[]","t"));
        when(mapper.candidate("session")).thenReturn("run");
        var run=mock(MachineCandidateSubmission.RunSnapshot.class);
        when(run.externalSessionId()).thenReturn("session");when(run.state()).thenReturn(MachineCandidateRunState.OPEN);
        when(run.scope()).thenReturn(new MachineCandidateSubmission.CandidateScope(MachineCandidateSubmission.CandidateScopeType.PROJECT,"project"));
        when(submissions.find("run")).thenReturn(Optional.of(run));
        var scope=access.specialized(session).orElseThrow();
        assertThat(scope.projectId()).isEqualTo("project");
        assertThat(scope.tools()).containsExactly("search_project_knowledge");
        assertThat(scope.connections()).isEmpty();
        verify(guard).validate(run,MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        when(run.scope()).thenReturn(new MachineCandidateSubmission.CandidateScope(MachineCandidateSubmission.CandidateScopeType.PROJECT,"other"));
        assertThatThrownBy(()->access.specialized(session)).isInstanceOf(AssistFailure.class);
        when(run.scope()).thenReturn(new MachineCandidateSubmission.CandidateScope(MachineCandidateSubmission.CandidateScopeType.PROJECT,"project"));
        doThrow(new ConflictException("STALE","旧批次已失效")).when(guard).validate(run,MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        assertThatThrownBy(()->access.specialized(session)).isInstanceOf(ConflictException.class);
        when(run.state()).thenReturn(MachineCandidateRunState.CLOSED);
        assertThatThrownBy(()->access.specialized(session)).isInstanceOf(AssistFailure.class);
    }
}
