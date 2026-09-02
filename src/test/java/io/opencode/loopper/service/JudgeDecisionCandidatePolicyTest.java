package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.JudgeCandidateAcceptedResultRow;
import io.opencode.loopper.persistence.JudgeCandidateSourceSnapshotRow;
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperJudgeCandidateMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class JudgeDecisionCandidatePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JudgeDecisionCandidateCodec codec = new JudgeDecisionCandidateCodec(json);
    private final JudgeDecisionCompilation compilation = new DeterministicJudgeDecisionCompilation(json);
    private final JudgeDecisionCompilation.Input input =
            new JudgeDecisionCompilation.Input("REQUIREMENT", evidence());
    private final JudgeDecisionCompilationInputLoader inputs = ignored -> input;
    private final JudgeDecisionCandidatePolicy policy =
            new JudgeDecisionCandidatePolicy(inputs, compilation);

    @Test
    void retriesOnlyClosedMechanicalProblemsAndNeverFallsBack() {
        CandidatePolicy.Decision mechanical = policy.evaluate(context(), candidateJson()
                .replace("PASS", "MAYBE"));
        CandidatePolicy.Decision benignExtra = policy.evaluate(context(), candidateJson()
                .replace("\"reason\"", "\"summary\":\"explanation\",\"reason\""));
        CandidatePolicy.Decision security = policy.evaluate(context(), candidateJson()
                .replace("\"reason\"", "\"permission\":\"write\",\"reason\""));

        assertThat(mechanical.accepted()).isFalse();
        assertThat(mechanical.retryable()).isTrue();
        assertThat(mechanical.fallbackEligible()).isFalse();
        assertThat(mechanical.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("JUDGE_DECISION_VERDICT_INVALID");
            assertThat(problem.allowedValues()).containsExactly("PASS", "REVISE", "BLOCKED");
        });
        assertThat(benignExtra.accepted()).isFalse();
        assertThat(benignExtra.retryable()).isTrue();
        assertThat(benignExtra.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("JUDGE_DECISION_FIELD_INVALID");
            assertThat(problem.allowedValues()).containsExactly(
                    "contractVersion", "role", "verdict", "reason", "evidenceIds");
        });
        assertThat(security.accepted()).isFalse();
        assertThat(security.retryable()).isFalse();
        assertThat(security.fallbackEligible()).isFalse();
        assertThat(security.problems()).singleElement()
                .extracting(MachineCandidateSubmission.Problem::code)
                .isEqualTo("JUDGE_DECISION_AUTHORITY_FIELD_FORBIDDEN");

        for (String authorityField : List.of(
                "taskState", "testTargets", "stableId", "runtimeGeneration",
                "sourceContext", "ownerIdentity", "serverDecision")) {
            CandidatePolicy.Decision authority = policy.evaluate(context(), candidateJson()
                    .replace("\"reason\"", "\"" + authorityField + "\":\"forged\",\"reason\""));
            assertThat(authority.accepted()).isFalse();
            assertThat(authority.retryable()).as(authorityField).isFalse();
            assertThat(authority.problems()).as(authorityField).singleElement()
                    .extracting(MachineCandidateSubmission.Problem::code)
                    .isEqualTo("JUDGE_DECISION_AUTHORITY_FIELD_FORBIDDEN");
        }
    }

    @Test
    void retriesLineBreakFormattingButStopsOtherControlCharacters() {
        for (String lineBreak : List.of("\\n", "\\r", "\\t", "\\r\\n")) {
            CandidatePolicy.Decision decision = policy.evaluate(context(), candidateJson()
                    .replace("The frozen evidence satisfies the contract.",
                            "First line" + lineBreak + "Second line"));
            assertThat(decision.accepted()).isFalse();
            assertThat(decision.retryable()).isTrue();
            assertThat(decision.problems()).singleElement().satisfies(problem -> {
                assertThat(problem.code()).isEqualTo("JUDGE_DECISION_REASON_LINE_BREAK_INVALID");
                assertThat(problem.pointer()).isEqualTo("/reason");
            });
        }
        CandidatePolicy.Decision nul = policy.evaluate(context(), candidateJson()
                .replace("The frozen evidence satisfies the contract.", "Unsafe\\u0000reason"));
        CandidatePolicy.Decision bell = policy.evaluate(context(), candidateJson()
                .replace("The frozen evidence satisfies the contract.", "Unsafe\\u0007reason"));
        CandidatePolicy.Decision c1 = policy.evaluate(context(), candidateJson()
                .replace("The frozen evidence satisfies the contract.", "Unsafe\\u0085reason"));
        CandidatePolicy.Decision mixed = policy.evaluate(context(), candidateJson()
                .replace("The frozen evidence satisfies the contract.", "Line\\nUnsafe\\u0000reason"));
        CandidatePolicy.Decision oversizedWithNul = policy.evaluate(context(), candidateJson()
                .replace("The frozen evidence satisfies the contract.", "x".repeat(4_001) + "\\u0000"));

        for (CandidatePolicy.Decision security : List.of(nul, bell, c1, mixed, oversizedWithNul)) {
            assertThat(security.accepted()).isFalse();
            assertThat(security.retryable()).isFalse();
            assertThat(security.problems()).singleElement()
                    .extracting(MachineCandidateSubmission.Problem::code)
                    .isEqualTo("JUDGE_DECISION_REASON_CONTROL_INVALID");
        }
    }

    @Test
    void sourceSnapshotIsFrozenBeforeIoAndReplayMustBeByteExact() {
        LoopperJudgeCandidateMapper mapper = mock(LoopperJudgeCandidateMapper.class);
        AtomicReference<JudgeCandidateSourceSnapshotRow> stored = new AtomicReference<>();
        when(mapper.findJudgeCandidateSourceSnapshot("run"))
                .thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(mapper.insertJudgeCandidateSourceSnapshot(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        JudgeDecisionCandidateSourceSnapshotStore store =
                new JudgeDecisionCandidateSourceSnapshotStore(mapper, codec);

        JudgeCandidateSourceSnapshotRow row = store.freeze(
                context(), judge(), batch(), source("Frozen prompt"));

        assertThat(row.sourcePrompt()).isEqualTo("Frozen prompt");
        assertThat(row.sourcePromptSha256())
                .isEqualTo(JudgeDecisionCandidateSourceSnapshotStore.sha256("Frozen prompt"));
        assertThat(row.canonicalEvidenceJson()).isEqualTo(codec.canonical(evidence()));
        assertThat(row.evidenceSha256()).isEqualTo(
                JudgeDecisionCandidateSourceSnapshotStore.sha256(row.canonicalEvidenceJson()));
        assertThat(store.freeze(context(), judge(), batch(), source("Frozen prompt"))).isEqualTo(row);
        assertThatThrownBy(() -> store.freeze(context(), judge(), batch(), source("changed")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void inputLoaderRejectsEveryOwnerRevisionAndHashDrift() {
        LoopperJudgeCandidateMapper mapper = mock(LoopperJudgeCandidateMapper.class);
        JudgeCandidateSourceSnapshotRow snapshot = snapshot();
        when(mapper.findJudgeCandidateSourceSnapshot("run")).thenReturn(Optional.of(snapshot));
        JudgeDecisionCompilationInputLoader loader =
                new JudgeDecisionCompilationInputLoader.MapperLoader(mapper, codec);

        assertThat(loader.load(context())).isEqualTo(input);
        for (CandidatePolicy.Context drift : List.of(
                new CandidatePolicy.Context("run", MachineCandidateSubmission.CandidateScope.task("other"),
                        context().owner(), context().candidateKind(), context().workflowStep(), 1, 1,
                        context().contractVersion(), 2, 0),
                new CandidatePolicy.Context("run", context().scope(), context().owner(),
                        context().candidateKind(), context().workflowStep(), 2, 1,
                        context().contractVersion(), 2, 0),
                new CandidatePolicy.Context("run", context().scope(), context().owner(),
                        context().candidateKind(), context().workflowStep(), 1, 2,
                        context().contractVersion(), 2, 0))) {
            assertThatThrownBy(() -> loader.load(drift)).isInstanceOf(ConflictException.class);
        }
        JudgeCandidateSourceSnapshotRow corrupt = new JudgeCandidateSourceSnapshotRow(
                snapshot.candidateRunId(), snapshot.judgeRunId(), snapshot.taskId(),
                snapshot.executionCycleId(), snapshot.finalAttemptId(), snapshot.reviewBatchId(),
                snapshot.role(), snapshot.ordinal(), snapshot.sourceRevision(),
                snapshot.preparedOwnerVersion(), snapshot.contractVersion(), snapshot.sourcePrompt(),
                "f".repeat(64), snapshot.canonicalEvidenceJson(), snapshot.evidenceSha256(),
                snapshot.createdAt());
        when(mapper.findJudgeCandidateSourceSnapshot("run")).thenReturn(Optional.of(corrupt));
        assertThatThrownBy(() -> loader.load(context())).isInstanceOf(ConflictException.class);
    }

    @Test
    void acceptedWriterRecompilesAndRecoveryRejectsPersistedResultDrift() {
        LoopperJudgeCandidateMapper mapper = mock(LoopperJudgeCandidateMapper.class);
        when(mapper.findJudgeCandidateSourceSnapshot("run")).thenReturn(Optional.of(snapshot()));
        when(mapper.insertJudgeCandidateAcceptedResult(any())).thenReturn(1);
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidateJson());
        JudgeDecisionAcceptedCandidateWriter writer = new JudgeDecisionAcceptedCandidateWriter(
                mapper, codec, inputs, compilation);
        String payloadSha = JudgeDecisionCandidateSourceSnapshotStore.sha256(
                decision.canonicalCandidateJson());

        writer.write(context(), decision.canonicalCandidateJson(), payloadSha);

        ArgumentCaptor<JudgeCandidateAcceptedResultRow> captured =
                ArgumentCaptor.forClass(JudgeCandidateAcceptedResultRow.class);
        verify(mapper).insertJudgeCandidateAcceptedResult(captured.capture());
        JudgeCandidateAcceptedResultRow row = captured.getValue();
        assertThat(row.role()).isEqualTo("REQUIREMENT");
        assertThat(row.reviewBatchId()).isEqualTo("batch");
        assertThat(row.candidatePayloadSha256()).isEqualTo(payloadSha);
        assertThat(row.canonicalDecisionJson()).contains("\"evidence\":[", "loop-spec")
                .doesNotContain("\"evidence\":\"");
        assertThat(row.settledJudgeRunId()).isNull();

        when(mapper.findJudgeCandidateAcceptedResult("run")).thenReturn(Optional.of(row));
        JudgeDecisionAcceptedResultStore store = new JudgeDecisionAcceptedResultStore(
                mapper, codec, new JudgeDecisionCompilationInputLoader.MapperLoader(mapper, codec),
                compilation);
        assertThat(store.require("run").compiled().accepted()).isTrue();

        JudgeCandidateAcceptedResultRow corrupt = new JudgeCandidateAcceptedResultRow(
                row.candidateRunId(), row.judgeRunId(), row.reviewBatchId(), row.role(),
                row.sourceRevision(), row.ownerVersion(), row.contractVersion(),
                row.canonicalCandidateJson(), "0".repeat(64), row.canonicalDecisionJson(),
                row.canonicalResultSha256(), row.verdict(), row.reason(), row.evidenceJson(),
                row.settledJudgeRunId(), row.createdAt(), row.updatedAt(), row.version());
        when(mapper.findJudgeCandidateAcceptedResult("run")).thenReturn(Optional.of(corrupt));
        assertThatThrownBy(() -> store.require("run"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("deterministic compilation");
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("run", MachineCandidateSubmission.CandidateScope.task("task"),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun("judge"),
                MachineCandidateKind.JUDGE_DECISION_V1, JudgeDecisionCandidatePolicy.WORKFLOW_STEP,
                1, 1, JudgeDecisionCandidatePolicy.CONTRACT_VERSION,
                JudgeDecisionCandidatePolicy.MAX_ATTEMPTS, 0);
    }

    private JudgeRunRow judge() {
        return new JudgeRunRow("judge", "task", "attempt", "REQUIREMENT", 1,
                null, "CREATING", null, null, null, "now", null, 0,
                "INTERNAL_MCP", null, "batch", 1L);
    }

    private JudgeReviewBatchRow batch() {
        return new JudgeReviewBatchRow("batch", "task", "cycle", "attempt", 1,
                "RUNNING", "now", "now", null, 0);
    }

    private TaskEvidenceService.JudgeCandidateSource source(String prompt) {
        return new TaskEvidenceService.JudgeCandidateSource(prompt, evidence());
    }

    private JudgeCandidateSourceSnapshotRow snapshot() {
        String canonicalEvidence = codec.canonical(evidence());
        return new JudgeCandidateSourceSnapshotRow(
                "run", "judge", "task", "cycle", "attempt", "batch", "REQUIREMENT", 1,
                1, 0, "JUDGE_DECISION_V1", "Frozen prompt",
                JudgeDecisionCandidateSourceSnapshotStore.sha256("Frozen prompt"),
                canonicalEvidence, JudgeDecisionCandidateSourceSnapshotStore.sha256(canonicalEvidence),
                "now");
    }

    private String candidateJson() {
        return """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT",
                 "verdict":"PASS","reason":"The frozen evidence satisfies the contract.",
                 "evidenceIds":["loop-spec","verification-summary"]}
                """;
    }

    private static JudgeDecisionCompilation.EvidenceCatalog evidence() {
        return new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                new JudgeDecisionCompilation.EvidenceItem(
                        "loop-spec", "LOOP_SPEC", "Confirmed LoopSpec", "a".repeat(64)),
                new JudgeDecisionCompilation.EvidenceItem(
                        "verification-summary", "VERIFICATION_SUMMARY",
                        "Deterministic verification", "b".repeat(64))));
    }
}
