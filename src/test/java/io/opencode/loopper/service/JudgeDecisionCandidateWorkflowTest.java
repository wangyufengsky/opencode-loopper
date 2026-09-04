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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.JudgeCandidateSourceSnapshotRow;
import io.opencode.loopper.persistence.TaskDesignAttachmentRow;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;

class JudgeDecisionCandidateWorkflowTest {

    @ParameterizedTest
    @ValueSource(strings = {"REQUIREMENT", "RISK"})
    void actualCandidateDispatchCarriesAllFrozenTaskAttachmentsAndKeepsMessageIdentity(String role) {
        Fixture fixture = new Fixture();
        GenericCandidateInternalLaunchRow launch = mock(GenericCandidateInternalLaunchRow.class);
        when(launch.id()).thenReturn("launch");
        when(launch.candidateRunId()).thenReturn("run");
        when(launch.candidateKind()).thenReturn("JUDGE_DECISION_V1");
        when(launch.ownerId()).thenReturn("judge");
        when(launch.judgeRunId()).thenReturn("judge");
        when(launch.taskId()).thenReturn("task");
        when(launch.sourceRevision()).thenReturn(1L);
        when(launch.canonicalDirectory()).thenReturn("/tmp");
        when(launch.profile()).thenReturn("JUDGE_CANDIDATE_READ_ONLY");
        when(launch.state()).thenReturn("SETTLED");
        when(launch.internalMcpServer()).thenReturn("loopper-private");
        when(launch.externalSessionId()).thenReturn("remote");
        when(fixture.mapper.findGenericCandidateInternalLaunchForJudgeRun("judge"))
                .thenReturn(Optional.of(launch));
        when(fixture.mapper.findGenericCandidateInternalLaunch("launch")).thenReturn(Optional.of(launch));
        var run = new MachineCandidateSubmission.RunSnapshot("run",
                MachineCandidateSubmission.CandidateScope.task("task"),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun("judge"),
                MachineCandidateKind.JUDGE_DECISION_V1, "JUDGE_DECISION_V1", 1, 1,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, "JUDGE_DECISION_V1",
                "generation", "remote", MachineCandidateRunState.OPEN, 2, 0, null, 0);
        when(fixture.submissions.find("run")).thenReturn(Optional.of(run));
        String evidence = fixture.codec.canonical(fixture.source().evidenceCatalog());
        var snapshot = new JudgeCandidateSourceSnapshotRow("run", "judge", "task", "cycle", "attempt",
                "batch", role, 1, 1, 0, "JUDGE_DECISION_V1", "Frozen prompt", "a".repeat(64),
                evidence, JudgeDecisionCandidateSourceSnapshotStore.sha256(evidence), "now");
        when(fixture.mapper.findJudgeCandidateSourceSnapshot("run")).thenReturn(Optional.of(snapshot));
        var files = List.of(new OpenCodeClient.FilePart("requirement.txt", "text/plain",
                        Path.of("/tmp/requirement.txt").toUri(), "a".repeat(64)),
                new OpenCodeClient.FilePart("second-package.txt", "text/plain",
                        Path.of("/tmp/package.txt").toUri(), "b".repeat(64)));
        when(fixture.mapper.listTaskDesignAttachments("task")).thenReturn(List.of(
                fixture.attachment("requirement.txt", "REQUIREMENT", null, "a".repeat(64)),
                fixture.attachment("second-package.txt", "WP-2", "WP-2", "b".repeat(64))));
        when(fixture.store.resolve("requirement.txt", "a".repeat(64))).thenReturn(Path.of("/tmp/requirement.txt"));
        when(fixture.store.resolve("second-package.txt", "b".repeat(64))).thenReturn(Path.of("/tmp/package.txt"));
        when(fixture.dispatches.advanceInitial(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CandidatePromptDispatchService.Result(CandidatePromptDispatchService.Status.PENDING));

        fixture.workflow.advance(fixture.context());

        var request = ArgumentCaptor.forClass(OpenCodeClient.PromptRequest.class);
        verify(fixture.dispatches).advanceInitial(eq(run), any(), any(), request.capture(), any(), any(), any(), any());
        assertThat(request.getValue().files()).containsExactlyElementsOf(files);
        assertThat(request.getValue().messageId()).isEqualTo(CandidatePromptDispatchService.initialMessageId("run"));
        assertThat(request.getValue().text()).contains("untrusted supplemental reference material");
        verify(fixture.mapper).listTaskDesignAttachments("task");
        verify(fixture.mapper, org.mockito.Mockito.never()).listActiveDesignerAttachments(any());
    }

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
        final GenericCandidateInternalLaunchCoordinator launches =
                mock(GenericCandidateInternalLaunchCoordinator.class);
        final MachineCandidateSubmission submissions = mock(MachineCandidateSubmission.class);
        final CandidatePromptDispatchService dispatches = mock(CandidatePromptDispatchService.class);
        final DesignerAttachmentStore store = mock(DesignerAttachmentStore.class);
        final DesignerAttachmentContext attachments = new DesignerAttachmentContext(mapper, store,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        final JudgeDecisionCandidateCodec codec = new JudgeDecisionCandidateCodec(new tools.jackson.databind.ObjectMapper());
        final JudgeDecisionCandidateWorkflow workflow = new JudgeDecisionCandidateWorkflow(
                mapper, preparer, launches,
                mock(GenericCandidateInternalTerminationPreparer.class),
                mock(GenericCandidateInternalTerminationCoordinator.class),
                mock(GenericCandidateInternalTerminationIntentStore.class),
                submissions, dispatches, snapshots, settlements, codec,
                mock(OpenCodeClient.class), attachments);

        Fixture() {
            when(launches.actualToolName(any())).thenReturn("loopper-private_submit_judge_decision");
            when(mapper.findJudgeRun("judge")).thenAnswer(call -> Optional.of(judge("CREATING")));
            when(mapper.findGenericCandidateInternalLaunchForJudgeRun("judge"))
                    .thenReturn(Optional.empty());
            when(mapper.findJudgeCandidateSourceSnapshot(any())).thenReturn(Optional.empty());
        }

        TaskDesignAttachmentRow attachment(String filename, String scope, String packageId, String sha) {
            return new TaskDesignAttachmentRow(filename, "task", "source-attachment", "parent-task",
                    filename, scope, packageId, "text/plain", 10, sha, filename,
                    "UTF8", "1", null, null, null, null, "now");
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
