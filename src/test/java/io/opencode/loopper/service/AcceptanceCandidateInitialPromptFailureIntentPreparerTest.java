package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.AcceptanceCandidateInitialPromptFailureReason;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AcceptanceCandidateInitialPromptFailureIntentPreparerTest {
    private final AcceptanceCandidateInternalLaunchStore launches =
            mock(AcceptanceCandidateInternalLaunchStore.class);
    private final AcceptanceCandidateInternalTerminationIntentStore intents =
            mock(AcceptanceCandidateInternalTerminationIntentStore.class);
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final AcceptanceCandidateInitialPromptFailureIntentPreparer preparer =
            new AcceptanceCandidateInitialPromptFailureIntentPreparer(launches, intents, mapper);

    @Test
    void freezesExactSettledOwnerAnchorsAndDelegatesIdempotentPersistence() {
        AcceptanceCandidateInternalLaunchRow launch = mock(AcceptanceCandidateInternalLaunchRow.class);
        when(launch.id()).thenReturn("launch");
        when(launch.compilationId()).thenReturn("compilation");
        when(launch.designerSessionId()).thenReturn("designer");
        when(launch.candidateRunId()).thenReturn("run");
        when(launch.state()).thenReturn("SETTLED");
        DesignerSessionRow designer = new DesignerSessionRow(
                "designer", "project", "RUNNING", "READ_ONLY", "created", "updated", 7,
                null, null, "draft", "COMPILING", 2, 0, 3, "WP-1", "WP-1", 9, "NONE", null);
        DesignRequirementRevisionRow revision = new DesignRequirementRevisionRow(
                "revision", "designer", 3, "message", "requirement", "[]", 0,
                "ACTIVE", 4, 8, "created", "updated", 1);
        when(intents.findActiveForCompilation("compilation")).thenReturn(Optional.empty());
        when(launches.findForCompilation("compilation")).thenReturn(Optional.of(launch));
        when(mapper.findDesignerSession("designer")).thenReturn(Optional.of(designer));
        when(mapper.findCurrentDesignRequirementRevision("designer")).thenReturn(Optional.of(revision));
        when(intents.createIdempotent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AcceptanceCandidateInternalTerminationIntentRow created = preparer.prepare(
                new AcceptanceCandidateInitialPromptFailureIntentPreparer.PrepareCommand(
                        "compilation", AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN));

        ArgumentCaptor<AcceptanceCandidateInternalTerminationIntentRow> row =
                ArgumentCaptor.forClass(AcceptanceCandidateInternalTerminationIntentRow.class);
        verify(intents).createIdempotent(row.capture());
        assertThat(created).isEqualTo(row.getValue());
        assertThat(created.id()).isEqualTo(
                AcceptanceCandidateInitialPromptFailureIntentPreparer.intentId("launch"));
        assertThat(created.kind()).isEqualTo("INITIAL_PROMPT_FAILURE");
        assertThat(created.targetState()).isEqualTo("FAILED_STOPPED");
        assertThat(created.archiveWhenComplete()).isFalse();
        assertThat(created.reasonCode()).isEqualTo("RESULT_UNKNOWN");
        assertThat(created.state()).isEqualTo("REQUESTED");
        assertThat(created.anchorDesignerVersion()).isEqualTo(7);
        assertThat(created.anchorRequirementRevisionId()).isEqualTo("revision");
        assertThat(created.anchorDiscussionRevision()).isEqualTo(9);
    }

    @Test
    void exactActiveFailureIsReplayableButDifferentReasonOrUserIntentWins() {
        AcceptanceCandidateInternalTerminationIntentRow active = intent("INITIAL_PROMPT_FAILURE", "RESULT_UNKNOWN");
        when(intents.findActiveForCompilation("compilation")).thenReturn(Optional.of(active));

        assertThat(preparer.prepare(new AcceptanceCandidateInitialPromptFailureIntentPreparer.PrepareCommand(
                "compilation", AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN))).isSameAs(active);
        assertThatThrownBy(() -> preparer.prepare(
                new AcceptanceCandidateInitialPromptFailureIntentPreparer.PrepareCommand(
                        "compilation", AcceptanceCandidateInitialPromptFailureReason.LOOKUP_UNSUPPORTED)))
                .isInstanceOf(ConflictException.class);
        when(intents.findActiveForCompilation("compilation"))
                .thenReturn(Optional.of(intent("DESIGNER_CANCEL", null)));
        assertThatThrownBy(() -> preparer.prepare(
                new AcceptanceCandidateInitialPromptFailureIntentPreparer.PrepareCommand(
                        "compilation", AcceptanceCandidateInitialPromptFailureReason.RESULT_UNKNOWN)))
                .isInstanceOf(ConflictException.class);
        verify(launches, never()).findForCompilation(any());
        verify(intents, never()).createIdempotent(any());
    }

    private AcceptanceCandidateInternalTerminationIntentRow intent(String kind, String reason) {
        return new AcceptanceCandidateInternalTerminationIntentRow(
                "intent", "launch", "designer", "compilation", "run", kind,
                "INITIAL_PROMPT_FAILURE".equals(kind) ? "FAILED_STOPPED" : "CANCELLED",
                false, reason, "INITIAL_PROMPT_FAILURE".equals(kind) ? "NONE" : "DESIGNER_CANCEL",
                "REQUESTED", 7, "revision", 9,
                null, null, null, null, "created", "updated", 0);
    }
}
