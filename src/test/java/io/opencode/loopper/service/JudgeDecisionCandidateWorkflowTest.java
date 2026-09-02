package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JudgeDecisionCandidateWorkflowTest {

    @Test
    void onlyManagedRuntimeAbsenceBeforeDispatchMayReturnOwnershipToFreshLegacy() {
        Fixture fixture = new Fixture();
        when(fixture.preparer.prepare(any())).thenThrow(
                new SessionFailure("CANDIDATE_MANAGED_RUNTIME_REQUIRED", "managed runtime absent"));
        JudgeRunRow aborted = fixture.judge("ABORTED");
        when(fixture.settlements.failBeforeRemote(any(), eq("CANDIDATE_MANAGED_RUNTIME_REQUIRED"),
                any(), eq(true))).thenReturn(
                JudgeDecisionCandidateSettlementService.Outcome.aborted(aborted));

        JudgeDecisionCandidateWorkflow.Result result = fixture.workflow.advance(fixture.context());

        assertThat(result.action()).isEqualTo(JudgeDecisionCandidateWorkflow.Action.LEGACY_FALLBACK);
        assertThat(result.judge()).isEqualTo(aborted);
    }

    @Test
    void transportFailureBeforeDispatchFailsClosedInsteadOfStartingLegacy() {
        Fixture fixture = new Fixture();
        when(fixture.preparer.prepare(any())).thenThrow(
                new SessionFailure("OPENCODE_TRANSPORT", "connection lost"));
        JudgeRunRow failed = fixture.judge("SESSION_ERROR");
        when(fixture.settlements.failBeforeRemote(any(), eq("OPENCODE_TRANSPORT"),
                any(), eq(false))).thenReturn(
                JudgeDecisionCandidateSettlementService.Outcome.failed(failed));

        JudgeDecisionCandidateWorkflow.Result result = fixture.workflow.advance(fixture.context());

        assertThat(result.action()).isEqualTo(JudgeDecisionCandidateWorkflow.Action.SESSION_ERROR);
        assertThat(result.judge()).isEqualTo(failed);
    }

    @Test
    void cancellationBeforeRemoteCreationStillPersistsAnAbortedJudgeOwner() {
        Fixture fixture = new Fixture();
        JudgeRunRow aborted = fixture.judge("ABORTED");
        when(fixture.settlements.failBeforeRemote(any(), eq("JUDGE_CANCELLED"), any(), eq(true)))
                .thenReturn(JudgeDecisionCandidateSettlementService.Outcome.aborted(aborted));

        JudgeDecisionCandidateWorkflow.Result result = fixture.workflow.cancel(fixture.judge("CREATING"));

        assertThat(result.action()).isEqualTo(JudgeDecisionCandidateWorkflow.Action.ABORTED);
        assertThat(result.judge()).isEqualTo(aborted);
        verify(fixture.settlements).failBeforeRemote(any(), eq("JUDGE_CANCELLED"), any(), eq(true));
    }

    private static final class Fixture {
        final LoopperMapper mapper = mock(LoopperMapper.class);
        final GenericCandidateInternalLaunchPreparer preparer =
                mock(GenericCandidateInternalLaunchPreparer.class);
        final JudgeDecisionCandidateSourceSnapshotStore snapshots =
                mock(JudgeDecisionCandidateSourceSnapshotStore.class);
        final JudgeDecisionCandidateSettlementService settlements =
                mock(JudgeDecisionCandidateSettlementService.class);
        final JudgeDecisionCandidateWorkflow workflow = new JudgeDecisionCandidateWorkflow(
                mapper, preparer, mock(GenericCandidateInternalLaunchCoordinator.class),
                mock(GenericCandidateInternalTerminationPreparer.class),
                mock(GenericCandidateInternalTerminationCoordinator.class),
                mock(GenericCandidateInternalTerminationIntentStore.class),
                mock(MachineCandidateSubmission.class), mock(CandidatePromptDispatchService.class),
                snapshots, settlements, new JudgeDecisionCandidateCodec(new tools.jackson.databind.ObjectMapper()),
                mock(OpenCodeClient.class));

        Fixture() {
            when(mapper.findJudgeRun("judge")).thenAnswer(call -> Optional.of(judge("CREATING")));
            when(mapper.findGenericCandidateInternalLaunchForJudgeRun("judge"))
                    .thenReturn(Optional.empty());
            when(mapper.findJudgeCandidateSourceSnapshot(any())).thenReturn(Optional.empty());
        }

        JudgeDecisionCandidateWorkflow.Context context() {
            return new JudgeDecisionCandidateWorkflow.Context(judge("CREATING"), batch(), Path.of("/tmp"),
                    new OpenCodeClient.OpenCodeModel("provider", "model", false), source(),
                    Instant.now().plusSeconds(60));
        }

        JudgeRunRow judge(String state) {
            return new JudgeRunRow("judge", "task", "attempt", "REQUIREMENT", 1,
                    null, state, null, state.equals("SESSION_ERROR") ? "failure" : null,
                    null, "2026-09-02T00:00:00Z", state.equals("CREATING") ? null : "ended",
                    0, "INTERNAL_MCP", null, "batch", 1L);
        }

        JudgeReviewBatchRow batch() {
            return new JudgeReviewBatchRow("batch", "task", "cycle", "attempt", 1,
                    "RUNNING", "now", "now", null, 0);
        }

        TaskEvidenceService.JudgeCandidateSource source() {
            return new TaskEvidenceService.JudgeCandidateSource("Frozen prompt",
                    new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                            new JudgeDecisionCompilation.EvidenceItem(
                                    "loop-spec", "LOOP_SPEC", "Confirmed LoopSpec", "a".repeat(64)))));
        }
    }
}
