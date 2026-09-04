package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DecompositionCandidatePolicyTest {
    @Test
    void loadsOnlyTheFrozenDatabaseFactsAndReturnsCompilerDecision() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        TaskDecompositionRow owner = owner();
        when(mapper.findTaskDecomposition("dec")).thenReturn(Optional.of(owner));
        when(mapper.findDesignRequirementRevision("rev")).thenReturn(Optional.of(revision()));
        DecompositionCandidatePolicy policy = new DecompositionCandidatePolicy(
                mapper, new DesignerDecompositionCandidateCompiler(new ObjectMapper()));
        CandidatePolicy.Context context = new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 4,
                "DECOMPOSITION_PLAN_V2", 5, 0);

        CandidatePolicy.Decision decision = policy.evaluate(context, """
                {"outcome":"READY","normalizedGoal":"goal","globalConstraints":[],
                 "workPackages":[{"title":"Vertical result","objective":"result","scopeIn":[],"scopeOut":[],
                 "deliverables":["x"],"acceptanceIntent":["y"],"dependsOn":[]}],
                 "coverage":[{"requirementRef":"RQ-1","targetType":"WORK_PACKAGE","targetIndex":0}],
                 "designGaps":[],"reason":null}
                """);

        assertThat(policy.supports(MachineCandidateKind.DECOMPOSITION_PLAN_V2)).isTrue();
        assertThat(policy.supports(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7)).isFalse();
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.canonicalCandidateJson()).contains("\"status\":\"DIRECT_DESIGN\"");
    }

    @Test
    void validNeedsInputCandidateTerminatesGenericLoopWithoutAcceptedWrite() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findTaskDecomposition("dec")).thenReturn(Optional.of(owner()));
        when(mapper.findDesignRequirementRevision("rev")).thenReturn(Optional.of(revision()));
        DecompositionCandidatePolicy policy = new DecompositionCandidatePolicy(
                mapper, new DesignerDecompositionCandidateCompiler(new ObjectMapper()));
        CandidatePolicy.Context context = new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 4,
                "DECOMPOSITION_PLAN_V2", 5, 0);

        CandidatePolicy.Decision decision = policy.evaluate(context, """
                {"outcome":"NEEDS_INPUT","normalizedGoal":null,"globalConstraints":[],"workPackages":[],
                 "coverage":[],"designGaps":[{"code":"MISSING_SCOPE","detail":"choose managed root"}],
                 "reason":null}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("DECOMPOSITION_NEEDS_INPUT");
            assertThat(problem.pointer()).isEqualTo("/designGaps/0/detail");
            assertThat(problem.detail()).contains("MISSING_SCOPE", "choose managed root");
            assertThat(problem.actual()).contains("MISSING_SCOPE", "choose managed root");
            assertThat(problem.repairHint()).contains("/designGaps/0/detail");
            assertThat(problem.allowedValues()).contains("MISSING_SCOPE");
        });
    }

    @Test
    void candidateOwnedGapCannotPretendToNeedHumanInput() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findTaskDecomposition("dec")).thenReturn(Optional.of(owner()));
        when(mapper.findDesignRequirementRevision("rev")).thenReturn(Optional.of(revision()));
        DecompositionCandidatePolicy policy = new DecompositionCandidatePolicy(
                mapper, new DesignerDecompositionCandidateCompiler(new ObjectMapper()));

        CandidatePolicy.Decision decision = policy.evaluate(context(), """
                {"outcome":"NEEDS_INPUT","normalizedGoal":null,"globalConstraints":[],"workPackages":[],
                 "coverage":[],"designGaps":[{"code":"AMBIGUOUS_ACCEPTANCE_INTENT",
                 "detail":"I assigned one acceptance fact to two stages"}],"reason":null}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT");
            assertThat(problem.pointer()).isEqualTo("/designGaps/0/code");
            assertThat(problem.detail()).contains("不能作为人工输入出口", "two stages");
            assertThat(problem.repairHint()).contains("READY", "/designGaps");
        });
    }

    @Test
    void rejectedProblemDetailsNeverEchoFrozenOrCandidateValues() {
        LoopperDesignerMapper mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findTaskDecomposition("dec")).thenReturn(Optional.of(owner()));
        DesignRequirementRevisionRow sensitiveRevision = new DesignRequirementRevisionRow(
                "rev", "session", 1, "message", "requirement",
                "[{\"id\":\"must-not-persist\",\"text\":\"observable result\"}]", 0,
                "ACTIVE", 0, 8, "now", "now", 0);
        when(mapper.findDesignRequirementRevision("rev")).thenReturn(Optional.of(sensitiveRevision));
        DecompositionCandidatePolicy policy = new DecompositionCandidatePolicy(
                mapper, new DesignerDecompositionCandidateCompiler(new ObjectMapper()));
        CandidatePolicy.Context context = new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 4,
                "DECOMPOSITION_PLAN_V2", 5, 0);

        CandidatePolicy.Decision decision = policy.evaluate(context, """
                {"outcome":"READY","normalizedGoal":"goal","globalConstraints":[],
                 "workPackages":[{"title":"Vertical result","objective":"result","scopeIn":[],"scopeOut":[],
                 "deliverables":["x"],"acceptanceIntent":["y"],"dependsOn":[]}],
                 "coverage":[],"designGaps":[],"reason":"candidate-secret"}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.problems()).extracting(MachineCandidateSubmission.Problem::detail)
                .allSatisfy(detail -> assertThat(detail)
                        .doesNotContain("must-not-persist", "candidate-secret"));
    }

    private TaskDecompositionRow owner() {
        return new TaskDecompositionRow("dec", "session", "rev", "RUNNING", null, null, null, null,
                "remote", "RUNNING", 0, 0, 0, null, null, "now", "now", 4,
                "PLANNING", null, 0, "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false);
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.taskDecomposition("dec"),
                MachineCandidateKind.DECOMPOSITION_PLAN_V2, "PLANNING", 1, 4,
                "DECOMPOSITION_PLAN_V2", 5, 0);
    }

    private DesignRequirementRevisionRow revision() {
        return new DesignRequirementRevisionRow("rev", "session", 1, "message", "requirement",
                "[{\"id\":\"RQ-1\",\"text\":\"observable result\"}]", 0,
                "ACTIVE", 0, 8, "now", "now", 0);
    }
}
