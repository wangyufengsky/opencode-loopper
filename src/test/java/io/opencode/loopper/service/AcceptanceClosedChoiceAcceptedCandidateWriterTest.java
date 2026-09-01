package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AcceptanceClosedChoiceAcceptedCandidateWriterTest {
    @Test
    void atomicallyProjectsTheCanonicalBindingIntoBothFrozenAcceptanceOwners() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findLoopSpecCompilation("cmp")).thenReturn(Optional.of(compilation()));
        when(mapper.findDesignAcceptancePlanning("cmp")).thenReturn(Optional.of(planning()));
        when(mapper.updateLoopSpecCompilation(any())).thenReturn(1);
        when(mapper.updateDesignAcceptancePlanning(any())).thenReturn(1);
        AcceptanceClosedChoiceAcceptedCandidateWriter writer =
                new AcceptanceClosedChoiceAcceptedCandidateWriter(mapper, new ObjectMapper());
        String canonical = """
                {"summary":"选择 A","groupHints":[{"title":"实现","objective":"实现行为",
                 "factIndexes":[0],"dependsOnHintIndexes":[]}],
                 "capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[0]}],
                 "handoffSummary":"保持冻结拓扑"}
                """;

        writer.write(context(), canonical, "b".repeat(64));

        ArgumentCaptor<DesignAcceptancePlanningRow> planningUpdate =
                ArgumentCaptor.forClass(DesignAcceptancePlanningRow.class);
        verify(mapper).updateDesignAcceptancePlanning(planningUpdate.capture());
        assertThat(planningUpdate.getValue()).satisfies(row -> {
            assertThat(row.state()).isEqualTo("BOUND");
            assertThat(row.bindingSource()).isEqualTo(AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name());
            assertThat(row.bindingJson()).isEqualTo(canonical);
            assertThat(row.diagnosticsJson()).contains(
                    "ACCEPTANCE_CLOSED_CHOICE_V7", "candidateRunId", "canonicalResultSha256", "b".repeat(64));
            assertThat(row.errorCode()).isNull();
            assertThat(row.errorDetail()).isNull();
            assertThat(row.version()).isEqualTo(2);
        });

        ArgumentCaptor<LoopSpecCompilationRow> compilationUpdate =
                ArgumentCaptor.forClass(LoopSpecCompilationRow.class);
        verify(mapper).updateLoopSpecCompilation(compilationUpdate.capture());
        assertThat(compilationUpdate.getValue()).satisfies(row -> {
            assertThat(row.semanticPlanJson()).isEqualTo(canonical);
            assertThat(row.planningJson()).isNull();
            assertThat(row.workflowStep()).isEqualTo("PLANNING");
            assertThat(row.serverCompiled()).isFalse();
            assertThat(row.version()).isEqualTo(4);
        });
    }

    @Test
    void writesAfterTheSinglePersistedDisconnectedCheckpoint() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findLoopSpecCompilation("cmp")).thenReturn(Optional.of(disconnectedCompilation()));
        when(mapper.findDesignAcceptancePlanning("cmp")).thenReturn(Optional.of(planning()));
        when(mapper.updateLoopSpecCompilation(any())).thenReturn(1);
        when(mapper.updateDesignAcceptancePlanning(any())).thenReturn(1);
        AcceptanceClosedChoiceAcceptedCandidateWriter writer =
                new AcceptanceClosedChoiceAcceptedCandidateWriter(mapper, new ObjectMapper());

        writer.write(context(), "{\"capabilityPreferences\":[]}", "b".repeat(64));

        ArgumentCaptor<LoopSpecCompilationRow> update = ArgumentCaptor.forClass(LoopSpecCompilationRow.class);
        verify(mapper).updateLoopSpecCompilation(update.capture());
        assertThat(update.getValue().version()).isEqualTo(5);
        assertThat(update.getValue().externalSessionState()).isEqualTo("DISCONNECTED");
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("candidate-run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                "ACCEPTANCE_CLOSED_CHOICE_V7", 3, 4,
                "ACCEPTANCE_CLOSED_CHOICE_V7", 2, 0);
    }

    private DesignAcceptancePlanningRow planning() {
        return new DesignAcceptancePlanningRow("cmp", "session", "WP-1", 3,
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "a".repeat(64),
                "EXTRACTED", AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name(), "{}", "{}", "{}",
                "{\"solver\":\"V7\"}", "OLD", "old", "created", "updated", 2);
    }

    private LoopSpecCompilationRow compilation() {
        return new LoopSpecCompilationRow("cmp", "session", 3, "RUNNING", "remote", "RUNNING",
                0, "message", 1, "OLD", "old", "created", "updated", 4,
                "WP-1", 0, null, "PLANNING", null, 0,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false);
    }

    private LoopSpecCompilationRow disconnectedCompilation() {
        return new LoopSpecCompilationRow("cmp", "session", 3, "RUNNING", "remote", "DISCONNECTED",
                0, "message", 1, "OPENCODE_ACCEPTANCE_CANDIDATE_STATUS_UNCONFIRMED", "transport",
                "created", "updated", 5, "WP-1", 0, null, "PLANNING", null, 0,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false);
    }
}
