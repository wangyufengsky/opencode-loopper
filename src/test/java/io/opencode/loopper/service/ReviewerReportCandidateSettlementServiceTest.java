package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewerReportCandidateSettlementServiceTest {

    @Test
    void ownerCancellationWinsOverAnAcceptedCandidate() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
        CandidatePromptDispatchService prompts = mock(CandidatePromptDispatchService.class);
        ReviewerReportAcceptedResultStore accepted = mock(ReviewerReportAcceptedResultStore.class);
        GenericCandidateInternalTerminationIntentStore intents =
                mock(GenericCandidateInternalTerminationIntentStore.class);
        ReviewerReportCandidateSettlementService service = new ReviewerReportCandidateSettlementService(
                mapper, submissions, prompts, accepted, mock(ReviewerReportCandidateCodec.class), intents);
        AnalysisReportRow report = mock(AnalysisReportRow.class);
        when(report.id()).thenReturn("report-1");
        when(report.designerSessionId()).thenReturn("designer-1");
        when(report.state()).thenReturn("RUNNING");
        when(report.version()).thenReturn(1L);
        when(report.externalSessionId()).thenReturn("remote-1");
        when(report.title()).thenReturn("Report");
        when(report.markdown()).thenReturn("");
        when(report.evidenceJson()).thenReturn("[]");
        when(report.findingsJson()).thenReturn("[]");
        when(mapper.findAnalysisReport("designer-1", "report-1")).thenReturn(Optional.of(report));
        when(mapper.updateAnalysisReport(any())).thenReturn(1);
        GenericCandidateInternalLaunchRow launch = mock(GenericCandidateInternalLaunchRow.class);
        when(launch.id()).thenReturn("launch-1");
        when(launch.candidateRunId()).thenReturn("run-1");
        when(launch.analysisReportId()).thenReturn("report-1");
        when(launch.state()).thenReturn("CANCELLED");
        when(launch.externalSessionId()).thenReturn("remote-1");
        when(launch.terminationProof()).thenReturn("ABORT_ACKNOWLEDGED");
        GenericCandidateInternalTerminationIntentRow intent = mock(
                GenericCandidateInternalTerminationIntentRow.class);
        when(intent.id()).thenReturn("intent-1");
        when(intent.launchId()).thenReturn("launch-1");
        when(intent.candidateRunId()).thenReturn("run-1");
        when(intent.state()).thenReturn("READY");
        when(intent.intentKind()).thenReturn("PROTOCOL_FAILURE");
        when(intent.ownerCancelRequested()).thenReturn(true);
        when(intents.require("intent-1")).thenReturn(intent);
        MachineCandidateSubmission.RunSnapshot run = new MachineCandidateSubmission.RunSnapshot(
                "run-1", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.analysisReport("report-1"),
                MachineCandidateKind.REVIEWER_REPORT_V1, "REVIEWER_REPORT_V1", 7, 1,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "REVIEWER_REPORT_V1", "generation-1", "remote-1",
                MachineCandidateRunState.ACCEPTED, 3, 1, null, 1);
        when(submissions.find("run-1")).thenReturn(Optional.of(run));
        when(prompts.settleForRun(eq("run-1"), eq("ABORT_ACKNOWLEDGED"), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return true;
                });

        assertThat(service.settle(report, launch, intent,
                "DESIGNER_CANCELLED", "Designer session was cancelled")).isTrue();

        ArgumentCaptor<AnalysisReportRow> update = ArgumentCaptor.forClass(AnalysisReportRow.class);
        verify(mapper).updateAnalysisReport(update.capture());
        assertThat(update.getValue().state()).isEqualTo("FAILED");
        assertThat(update.getValue().errorCode()).isEqualTo("DESIGNER_CANCELLED");
        verify(accepted, never()).find("run-1");
        verify(intents).complete(intent);
    }
}
