package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewerReportCandidateWorkflowTest {

    @Test
    void recoveryUsesThePersistedLaunchAfterTheOwnerVersionAdvanced() {
        Fixture fixture = new Fixture();
        AnalysisReportRow report = fixture.report(1, 7);
        GenericCandidateInternalLaunchRow launch = fixture.launch("COMPLETED", 0, 7);
        when(fixture.mapper.findAnalysisReport("designer-1", "report-1")).thenReturn(Optional.of(report));
        when(fixture.mapper.findGenericCandidateInternalLaunchForAnalysisReport("report-1"))
                .thenReturn(Optional.of(launch));
        when(fixture.mapper.findGenericCandidateInternalLaunch("launch-original"))
                .thenReturn(Optional.of(launch));
        when(fixture.intents.findForLaunch("launch-original")).thenReturn(Optional.empty());

        ReviewerReportCandidateWorkflow.Result result = fixture.workflow.advance(fixture.context(report, 7));

        assertThat(result.action()).isEqualTo(ReviewerReportCandidateWorkflow.Action.DISCONNECTED);
        verify(fixture.preparer, never()).prepare(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recoveryRejectsSourceRevisionDriftInsteadOfCreatingAReplacementLaunch() {
        Fixture fixture = new Fixture();
        AnalysisReportRow report = fixture.report(1, 8);
        GenericCandidateInternalLaunchRow launch = fixture.launch("PREPARED", 0, 7);
        when(fixture.mapper.findAnalysisReport("designer-1", "report-1")).thenReturn(Optional.of(report));
        when(fixture.mapper.findGenericCandidateInternalLaunchForAnalysisReport("report-1"))
                .thenReturn(Optional.of(launch));

        GenericCandidateInternalTerminationIntentRow intent =
                mock(GenericCandidateInternalTerminationIntentRow.class);
        when(intent.id()).thenReturn("intent-1");
        when(fixture.terminationPreparer.prepare(org.mockito.ArgumentMatchers.any()))
                .thenReturn(intent);
        when(fixture.terminationCoordinator.advance("intent-1")).thenReturn(
                new GenericCandidateInternalTerminationCoordinator.Result(
                        GenericCandidateInternalTerminationCoordinator.Status.DISCONNECTED,
                        intent, "STOP_UNCONFIRMED"));

        assertThat(fixture.workflow.advance(fixture.context(report, 8)).action())
                .isEqualTo(ReviewerReportCandidateWorkflow.Action.DISCONNECTED);
        verify(fixture.preparer, never()).prepare(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onlyAProvenCapabilityReplacementHandsOwnershipBackToLegacy() {
        Fixture fixture = new Fixture();
        AnalysisReportRow report = fixture.report(2, 7);
        when(report.responseMode()).thenReturn("JSON_SCHEMA");
        GenericCandidateInternalLaunchRow launch = fixture.launch("STALE", 0, 7);
        GenericCandidateInternalTerminationIntentRow intent =
                mock(GenericCandidateInternalTerminationIntentRow.class);
        when(intent.state()).thenReturn("COMPLETED");
        when(intent.intentKind()).thenReturn("OWNER_REPLACEMENT");
        when(intent.reasonCode()).thenReturn("REVIEWER_CANDIDATE_CAPABILITY_UNAVAILABLE");
        when(fixture.mapper.findGenericCandidateInternalLaunchForAnalysisReport("report-1"))
                .thenReturn(Optional.of(launch));
        when(fixture.intents.findForLaunch("launch-original")).thenReturn(Optional.of(intent));

        assertThat(fixture.workflow.owns(report)).isFalse();

        when(intent.reasonCode()).thenReturn("REVIEWER_CANDIDATE_ZERO_SUBMISSION");
        assertThat(fixture.workflow.owns(report)).isTrue();
    }

    @Test
    void localPrepareFailureClosesTheReportWithoutPretendingAWriterExists() {
        Fixture fixture = new Fixture();
        AnalysisReportRow report = fixture.report(0, 7);
        when(report.state()).thenReturn("RUNNING");
        when(fixture.mapper.findAnalysisReport("designer-1", "report-1")).thenReturn(Optional.of(report));
        when(fixture.mapper.findGenericCandidateInternalLaunchForAnalysisReport("report-1"))
                .thenReturn(Optional.empty());
        when(fixture.preparer.prepare(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("invalid local plan"));
        when(fixture.mapper.updateAnalysisReport(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        ReviewerReportCandidateWorkflow.Result result = fixture.workflow.advance(fixture.context(report, 7));

        assertThat(result.action()).isEqualTo(ReviewerReportCandidateWorkflow.Action.FAILED);
        verify(fixture.mapper).updateAnalysisReport(org.mockito.ArgumentMatchers.argThat(row ->
                "FAILED".equals(row.state()) && row.externalSessionId() == null
                        && "NO_REMOTE_CREATED".equals(row.externalSessionState())));
    }

    private static final class Fixture {
        final LoopperMapper mapper = mock(LoopperMapper.class);
        final GenericCandidateInternalLaunchPreparer preparer = mock(GenericCandidateInternalLaunchPreparer.class);
        final GenericCandidateInternalTerminationPreparer terminationPreparer =
                mock(GenericCandidateInternalTerminationPreparer.class);
        final GenericCandidateInternalTerminationCoordinator terminationCoordinator =
                mock(GenericCandidateInternalTerminationCoordinator.class);
        final GenericCandidateInternalTerminationIntentStore intents =
                mock(GenericCandidateInternalTerminationIntentStore.class);
        final ReviewerReportCandidateWorkflow workflow = new ReviewerReportCandidateWorkflow(
                mapper, preparer, mock(GenericCandidateInternalLaunchCoordinator.class),
                terminationPreparer, terminationCoordinator, intents,
                mock(MachineCandidateSubmission.class), mock(CandidatePromptDispatchService.class),
                mock(DesignerAttachmentContext.class), mock(tools.jackson.databind.ObjectMapper.class),
                mock(ReviewerReportSourceManifestCapture.class),
                mock(ReviewerReportSourceSnapshotStore.class),
                mock(ReviewerReportCandidateSettlementService.class), mock(OpenCodeClient.class));

        AnalysisReportRow report(long version, int sourceRevision) {
            AnalysisReportRow row = mock(AnalysisReportRow.class);
            when(row.id()).thenReturn("report-1");
            when(row.designerSessionId()).thenReturn("designer-1");
            when(row.version()).thenReturn(version);
            when(row.sourceRequirementRevision()).thenReturn(sourceRevision);
            return row;
        }

        GenericCandidateInternalLaunchRow launch(String state, long ownerVersion, long sourceRevision) {
            GenericCandidateInternalLaunchRow row = mock(GenericCandidateInternalLaunchRow.class);
            when(row.id()).thenReturn("launch-original");
            when(row.candidateKind()).thenReturn("REVIEWER_REPORT_V1");
            when(row.workflowStep()).thenReturn("REVIEWER_REPORT_V1");
            when(row.contractVersion()).thenReturn("REVIEWER_REPORT_V1");
            when(row.ownerType()).thenReturn("ANALYSIS_REPORT");
            when(row.ownerId()).thenReturn("report-1");
            when(row.analysisReportId()).thenReturn("report-1");
            when(row.designerSessionId()).thenReturn("designer-1");
            when(row.profile()).thenReturn("REVIEWER_CANDIDATE_READ_ONLY");
            when(row.state()).thenReturn(state);
            when(row.preparedOwnerVersion()).thenReturn(ownerVersion);
            when(row.sourceRevision()).thenReturn(sourceRevision);
            return row;
        }

        ReviewerReportCandidateWorkflow.Context context(AnalysisReportRow report, long sourceRevision) {
            return new ReviewerReportCandidateWorkflow.Context(report, Path.of("/tmp"), sourceRevision,
                    null, "role", "requirement", false);
        }
    }
}
