package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DesignerConfirmationGateTest {
    @Test void frozenPlanFromAnotherDesignCannotAuthorizeCreation() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        DesignerSessionRow session = mock(DesignerSessionRow.class);
        DesignerTaskProfileRow profile = mock(DesignerTaskProfileRow.class);
        ArtifactPlanRow plan = mock(ArtifactPlanRow.class);
        when(session.id()).thenReturn("designer");
        when(session.currentRequirementRevision()).thenReturn(null);
        when(session.workflowPhase()).thenReturn("FINAL_REVIEW");
        when(session.state()).thenReturn("REVIEWING");
        when(mapper.findCurrentDesignerTaskProfile("designer")).thenReturn(Optional.of(profile));
        when(profile.id()).thenReturn("profile");
        when(profile.state()).thenReturn("FROZEN");
        when(profile.intent()).thenReturn("DATA_CONVERSION");
        when(profile.workflowTemplate()).thenReturn("DIRECT_ARTIFACT");
        when(profile.executionStrategy()).thenReturn("SERVER_TABULAR_CONVERSION");
        when(mapper.findArtifactPlan("plan")).thenReturn(Optional.of(plan));
        when(plan.state()).thenReturn("FROZEN");
        when(plan.taskProfileId()).thenReturn("profile");
        when(plan.designerSessionId()).thenReturn("another-designer");
        LoopSpec spec = JsonMapper.builder().build().readValue("""
                {"schemaVersion":"v2","projectId":"project","goal":"转换表格","stages":[
                {"objective":"转换表格","stageKind":"TABULAR_CONVERSION","executionStrategy":"SERVER_TABULAR_CONVERSION",
                "artifactPlanId":"plan"}]}
                """, LoopSpec.class);
        assertThat(DesignerConfirmationGate.assess(mapper, session, spec).eligible()).isFalse();
        when(plan.designerSessionId()).thenReturn("designer");
        assertThat(DesignerConfirmationGate.assess(mapper, session, spec).eligible()).isTrue();
        when(session.state()).thenReturn("STOPPING");
        assertThat(DesignerConfirmationGate.assess(mapper, session, spec).eligible()).isFalse();
    }
}
