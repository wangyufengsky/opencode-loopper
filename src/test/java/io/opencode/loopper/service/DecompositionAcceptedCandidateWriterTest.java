package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DecompositionAcceptedCandidateWriterTest {
    @Test
    void atomicallyProjectsAcceptedCanonicalPlanIntoAuthoritativeDecompositionFields() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findTaskDecomposition("dec")).thenReturn(Optional.of(owner()));
        when(mapper.updateTaskDecomposition(any())).thenReturn(1);
        DecompositionAcceptedCandidateWriter writer = new DecompositionAcceptedCandidateWriter(mapper);
        CandidatePolicy.Context context = new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 4,
                "DECOMPOSITION_PLAN_V2", 5, 0);
        String canonical = "{\"status\":\"DIRECT_DESIGN\"}";

        writer.write(context, canonical, "a".repeat(64));

        ArgumentCaptor<TaskDecompositionRow> update = ArgumentCaptor.forClass(TaskDecompositionRow.class);
        verify(mapper).updateTaskDecomposition(update.capture());
        assertThat(update.getValue()).satisfies(row -> {
            assertThat(row.planningJson()).isEqualTo(canonical);
            assertThat(row.semanticPlanJson()).isEqualTo(canonical);
            assertThat(row.serverCompiled()).isTrue();
            assertThat(row.workflowStep()).isEqualTo("SERVER_COMPILING");
            assertThat(row.version()).isEqualTo(4);
            assertThat(row.externalSessionId()).isEqualTo("remote");
        });
    }

    private TaskDecompositionRow owner() {
        return new TaskDecompositionRow("dec", "session", "rev", "RUNNING", null, null, null, null,
                "remote", "RUNNING", 1, 2, 3, "OLD", "old", "created", "updated", 4,
                "PLANNING", null, 0, "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false);
    }
}
