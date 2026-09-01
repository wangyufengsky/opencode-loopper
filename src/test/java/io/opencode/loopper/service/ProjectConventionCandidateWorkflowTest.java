package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectConventionCandidateWorkflowTest {

    @Test
    void onlyAProvenPreDispatchCapabilityReplacementReturnsOwnershipToLegacy() {
        Fixture fixture = new Fixture();
        ProjectConventionDraftRow draft = fixture.draft("remote-legacy");
        GenericCandidateInternalLaunchRow launch = fixture.launch("STALE");
        GenericCandidateInternalTerminationIntentRow intent =
                mock(GenericCandidateInternalTerminationIntentRow.class);
        when(intent.state()).thenReturn("COMPLETED");
        when(intent.intentKind()).thenReturn("OWNER_REPLACEMENT");
        when(intent.reasonCode()).thenReturn(
                "PROJECT_CONVENTION_CANDIDATE_CAPABILITY_UNAVAILABLE");
        when(fixture.mapper.findGenericCandidateInternalLaunchForProjectConventionDraft("draft-1"))
                .thenReturn(Optional.of(launch));
        when(fixture.intents.findForLaunch("launch-1")).thenReturn(Optional.of(intent));

        assertThat(fixture.workflow.owns(draft)).isFalse();

        when(intent.reasonCode()).thenReturn("PROJECT_CONVENTION_CANDIDATE_ZERO_SUBMISSION");
        assertThat(fixture.workflow.owns(draft)).isTrue();
        when(intent.reasonCode()).thenReturn(
                "PROJECT_CONVENTION_CANDIDATE_CAPABILITY_UNAVAILABLE");
        when(intent.state()).thenReturn("READY");
        assertThat(fixture.workflow.owns(draft)).isTrue();
    }

    @Test
    void aPreLaunchCandidateOwnerStopsOwningOnlyAfterFreshLegacyAttachment() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.findGenericCandidateInternalLaunchForProjectConventionDraft("draft-1"))
                .thenReturn(Optional.empty());

        assertThat(fixture.workflow.owns(fixture.draft(null))).isTrue();
        assertThat(fixture.workflow.owns(fixture.draft("remote-legacy"))).isFalse();
    }

    private static final class Fixture {
        final LoopperMapper mapper = mock(LoopperMapper.class);
        final GenericCandidateInternalTerminationIntentStore intents =
                mock(GenericCandidateInternalTerminationIntentStore.class);
        final ProjectConventionCandidateWorkflow workflow = new ProjectConventionCandidateWorkflow(
                mapper, mock(LifecycleTransitionService.class),
                mock(GenericCandidateInternalLaunchPreparer.class),
                mock(GenericCandidateInternalLaunchCoordinator.class),
                mock(GenericCandidateInternalTerminationPreparer.class),
                mock(GenericCandidateInternalTerminationCoordinator.class), intents,
                mock(MachineCandidateSubmission.class), mock(CandidatePromptDispatchService.class),
                mock(ProjectConventionEvidenceCatalogCapture.class),
                mock(ProjectConventionCandidateSourceSnapshotStore.class),
                mock(ProjectConventionCompilationInputLoader.class),
                mock(ProjectConventionCandidateSettlementService.class), mock(OpenCodeClient.class));

        ProjectConventionDraftRow draft(String externalSessionId) {
            ProjectConventionDraftRow row = mock(ProjectConventionDraftRow.class);
            when(row.id()).thenReturn("draft-1");
            when(row.responseMode()).thenReturn("INTERNAL_MCP");
            when(row.externalSessionId()).thenReturn(externalSessionId);
            return row;
        }

        GenericCandidateInternalLaunchRow launch(String state) {
            GenericCandidateInternalLaunchRow row = mock(GenericCandidateInternalLaunchRow.class);
            when(row.id()).thenReturn("launch-1");
            when(row.state()).thenReturn(state);
            return row;
        }
    }
}
