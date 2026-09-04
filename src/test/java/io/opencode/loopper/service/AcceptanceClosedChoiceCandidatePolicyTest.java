package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class AcceptanceClosedChoiceCandidatePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private LoopperDesignerMapper mapper;
    private AcceptanceClosedChoiceCandidatePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(LoopperDesignerMapper.class);
        when(mapper.findLoopSpecCompilation("cmp")).thenReturn(Optional.of(compilation()));
        when(mapper.findDesignAcceptancePlanning("cmp")).thenReturn(Optional.of(planning(tiedCapabilities())));
        policy = new AcceptanceClosedChoiceCandidatePolicy(mapper, json);
    }

    @Test
    void acceptsOneCompleteEqualOptimumSetAndReturnsServerDerivedCanonicalBinding() {
        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"summary":"选择 A","factAssignments":[],
                 "capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[0]}],
                 "handoffSummary":"保持冻结拓扑"}
                """);

        assertThat(policy.supports(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7)).isTrue();
        assertThat(policy.supports(MachineCandidateKind.DECOMPOSITION_PLAN_V2)).isFalse();
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.canonicalCandidateJson())
                .contains("\"groupHints\"", "\"capabilityIndexes\":[0]")
                .doesNotContain("command", "testTargets", "allowedPaths");
    }

    @Test
    void acceptsTheSinglePersistedDisconnectedCheckpointForTheSameOpenRun() {
        when(mapper.findLoopSpecCompilation("cmp")).thenReturn(Optional.of(disconnectedCompilation()));

        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[],
                 "capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[0]}]}
                """);

        assertThat(decision.accepted()).isTrue();
    }

    @Test
    void onlyMechanicalClosedSetSelectionErrorsCanUseTheSecondAttempt() {
        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[],
                 "capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[99]}]}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_SELECTION_INVALID");
            assertThat(problem.pointer()).isEqualTo("/capabilityPreferences");
            assertThat(problem.allowedValues()).containsExactly("[0]", "[1]");
        });
    }

    @Test
    void safeIntegerPreferenceShorthandIsRejectedAsOneMechanicalCorrectionOpportunity() {
        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[],"capabilityPreferences":[0]}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_SELECTION_INVALID");
            assertThat(problem.pointer()).isEqualTo("/capabilityPreferences");
            assertThat(problem.detail()).contains("对象数组", "factIndex", "capabilityIndexes");
            assertThat(problem.allowedValues()).containsExactly(
                    "[{\"factIndex\":0,\"capabilityIndexes\":[0]}]",
                    "[{\"factIndex\":0,\"capabilityIndexes\":[1]}]");
        });
    }

    @Test
    void safeSingularCapabilityPreferenceIsRejectedAsOneMechanicalCorrectionOpportunity() {
        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[{"factIndex":0,"stageIndex":0}],
                 "capabilityPreferences":[{"factIndex":0,"capabilityIndex":0}]}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_SELECTION_INVALID");
            assertThat(problem.pointer()).isEqualTo("/capabilityPreferences");
            assertThat(problem.detail()).contains("capabilityIndex", "capabilityIndexes");
            assertThat(problem.allowedValues()).containsExactly(
                    "[{\"factIndex\":0,\"capabilityIndexes\":[0]}]",
                    "[{\"factIndex\":0,\"capabilityIndexes\":[1]}]");
        });
    }

    @Test
    void safeCapabilitySelectionWrittenIntoBothSelectionArraysGetsOneMechanicalCorrectionOpportunity() {
        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[{"factIndex":0,"capabilityIndex":0}],
                 "capabilityPreferences":[0]}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_SELECTION_INVALID");
            assertThat(problem.pointer()).isEqualTo("/capabilityPreferences");
            assertThat(problem.detail()).contains("factAssignments", "capabilityIndex", "capabilityPreferences");
            assertThat(problem.allowedValues()).containsExactly(
                    "[{\"factIndex\":0,\"capabilityIndexes\":[0]}]",
                    "[{\"factIndex\":0,\"capabilityIndexes\":[1]}]");
        });
    }

    @Test
    void safetyPathAndPermissionFieldsAreNeverRetryable() {
        for (String candidate : List.of(
                "{\"factAssignments\":[],\"capabilityPreferences\":[],\"allowedPaths\":[\"src/**\"]}",
                "{\"factAssignments\":[],\"capabilityPreferences\":[],\"permissions\":\"allow\"}")) {
            CandidatePolicy.Decision decision = policy.evaluate(context(0), candidate);
            assertThat(decision.accepted()).isFalse();
            assertThat(decision.retryable()).as(candidate).isFalse();
            assertThat(decision.problems()).singleElement().satisfies(problem ->
                    assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_SECURITY_BOUNDARY"));
        }
    }

    @Test
    void safeContractShapeErrorReturnsCorrectionToTheSameRun() {
        CandidatePolicy.Decision decision = policy.evaluate(context(0),
                "{\"factAssignments\":\"not-an-enumerated-choice\",\"capabilityPreferences\":[]}");

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_CONTRACT_INVALID");
            assertThat(problem.detail()).contains("ACCEPTANCE_CLOSED_CHOICE_V7");
        });
    }

    @Test
    void nonTieOrUnresolvedFactsAreNotAnEnumeratedRetrySurface() throws Exception {
        DesignerAcceptancePlanning.CapabilityCatalog unique = new DesignerAcceptancePlanning.CapabilityCatalog(
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                List.of(capability(0, 100), capability(1, 80)), List.of());
        when(mapper.findDesignAcceptancePlanning("cmp")).thenReturn(Optional.of(planning(unique)));

        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[],"capabilityPreferences":[]}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.problems()).singleElement().satisfies(problem ->
                assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_NOT_ENUMERABLE"));
    }

    @Test
    void frozenPathConservationIssuesAreNeverExposedAsARetrySurface() throws Exception {
        DesignerAcceptancePlanning.Catalog unsafePaths = new DesignerAcceptancePlanning.Catalog(
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "WP-1", 3, "a".repeat(64), true,
                facts().facts(), facts().stageHints(), List.of(),
                List.of("MUTATION_PATH_SCOPE_CONFLICT"), List.of());
        when(mapper.findDesignAcceptancePlanning("cmp")).thenReturn(Optional.of(
                planning(unsafePaths, tiedCapabilities())));

        CandidatePolicy.Decision decision = policy.evaluate(context(0), """
                {"factAssignments":[],
                 "capabilityPreferences":[{"factIndex":0,"capabilityIndexes":[0]}]}
                """);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.problems()).singleElement().satisfies(problem ->
                assertThat(problem.code()).isEqualTo("ACCEPTANCE_CANDIDATE_NOT_ENUMERABLE"));
    }

    private CandidatePolicy.Context context(int attemptsUsed) {
        return new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.designerSession("session"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("cmp"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                "ACCEPTANCE_CLOSED_CHOICE_V7", 3, 4,
                "ACCEPTANCE_CLOSED_CHOICE_V7", 2, attemptsUsed);
    }

    private LoopSpecCompilationRow compilation() {
        return new LoopSpecCompilationRow("cmp", "session", 3, "RUNNING", "remote", "RUNNING",
                0, "message", 1, null, null, "created", "updated", 4,
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

    private DesignAcceptancePlanningRow planning(
            DesignerAcceptancePlanning.CapabilityCatalog capabilities) throws JacksonException {
        return planning(facts(), capabilities);
    }

    private DesignAcceptancePlanningRow planning(
            DesignerAcceptancePlanning.Catalog facts,
            DesignerAcceptancePlanning.CapabilityCatalog capabilities) throws JacksonException {
        return new DesignAcceptancePlanningRow("cmp", "session", "WP-1", 3,
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "a".repeat(64), "EXTRACTED",
                AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name(),
                json.writeValueAsString(facts), json.writeValueAsString(capabilities), "{}", "{}",
                null, null, "created", "updated", 2);
    }

    private DesignerAcceptancePlanning.Catalog facts() {
        DesignerAcceptancePlanning.Fact fact = new DesignerAcceptancePlanning.Fact(
                0, DesignerAcceptancePlanning.FactKind.SCENARIO, "成功", "输入合法", "执行",
                "返回成功", "不写外部系统", null, "DS-L001", "成功", "a".repeat(64));
        DesignerAcceptancePlanning.StageHint stage = new DesignerAcceptancePlanning.StageHint(
                "实现", "实现行为", List.of("成功"), List.of(), List.of(), List.of());
        return new DesignerAcceptancePlanning.Catalog(DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                "WP-1", 3, "a".repeat(64), true, List.of(fact), List.of(stage), List.of());
    }

    private DesignerAcceptancePlanning.CapabilityCatalog tiedCapabilities() {
        return new DesignerAcceptancePlanning.CapabilityCatalog(
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                List.of(capability(0, 100), capability(1, 100)), List.of());
    }

    private DesignerAcceptancePlanning.Capability capability(int index, int strength) {
        return new DesignerAcceptancePlanning.Capability(index, "FOCUSED_TEST", "测试 " + index,
                List.of("mvn", "-Dtest=Flow" + index + "Test", "test"), List.of(0),
                List.of("Flow" + index + "Test"), true, false, strength);
    }
}
