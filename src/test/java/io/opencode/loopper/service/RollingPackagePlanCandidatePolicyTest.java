package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.RollingPackagePlanAcceptedResultRow;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class RollingPackagePlanCandidatePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RollingPackagePlanCompilation compilation =
            new DeterministicRollingPackagePlanCompilation(json);
    private final RollingPackagePlanCompilationInputLoader inputs = ignored -> input();
    private final RollingPackagePlanCandidatePolicy policy =
            new RollingPackagePlanCandidatePolicy(inputs, compilation);

    @Test
    void retriesOnlyClosedMechanicalCorrectionsWithoutLegacyFallback() {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidate()
                .replace("\"WP-2\"],\"dependencies\"", "\"WP-X\"],\"dependencies\""));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.fallbackEligible()).isFalse();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ROLLING_PACKAGE_SOURCE_INVALID");
            assertThat(problem.allowedValues()).containsExactly("WP-2", "WP-3");
        });
    }

    @Test
    void authorityFieldsFailClosedWithoutRetryOrFallback() {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidate()
                .replace("\"requirementRefs\":[\"RQ-2\"]",
                        "\"requirementRefs\":[\"RQ-2\"],\"command\":\"unsafe-private-value\""));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.fallbackEligible()).isFalse();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ROLLING_PACKAGE_AUTHORITY_FIELD_FORBIDDEN");
            assertThat(problem.detail()).doesNotContain("unsafe-private-value");
        });
    }

    @Test
    void acceptedWriterRecompilesAndPersistsImmutableCanonicalPlanAndImpact() throws Exception {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidate());
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        when(mapper.insertRollingPackagePlanAcceptedResult(any())).thenReturn(1);
        RollingPackagePlanAcceptedCandidateWriter writer =
                new RollingPackagePlanAcceptedCandidateWriter(mapper, inputs, compilation);

        writer.write(context(), decision.canonicalCandidateJson(), sha256(decision.canonicalCandidateJson()));

        ArgumentCaptor<RollingPackagePlanAcceptedResultRow> row =
                ArgumentCaptor.forClass(RollingPackagePlanAcceptedResultRow.class);
        verify(mapper).insertRollingPackagePlanAcceptedResult(row.capture());
        assertThat(row.getValue()).satisfies(result -> {
            assertThat(result.candidateRunId()).isEqualTo("rolling-run");
            assertThat(result.taskPackagePlanRevisionId()).isEqualTo("plan-revision");
            assertThat(result.sourceRevision()).isEqualTo(4);
            assertThat(result.ownerVersion()).isEqualTo(1);
            assertThat(result.contractVersion()).isEqualTo("ROLLING_PACKAGE_PLAN_V1");
            assertThat(result.canonicalCandidateJson()).isEqualTo(decision.canonicalCandidateJson());
            assertThat(json.readTree(result.canonicalPlanJson())).hasSize(1);
            assertThat(json.readTree(result.impactJson()).path("before")).hasSize(2);
            assertThat(result.settledPlanRevisionId()).isNull();
            assertThat(result.version()).isZero();
        });
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("rolling-run",
                MachineCandidateSubmission.CandidateScope.task("task-1"),
                MachineCandidateSubmission.CandidateOwnerRef.taskPackagePlanRevision("plan-revision"),
                MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1,
                RollingPackagePlanCandidatePolicy.WORKFLOW_STEP, 4, 1,
                RollingPackagePlanCandidatePolicy.CONTRACT_VERSION,
                RollingPackagePlanCandidatePolicy.MAX_ATTEMPTS, 0);
    }

    private RollingPackagePlanCompilation.Input input() {
        return new RollingPackagePlanCompilation.Input(List.of(
                new RollingPackagePlanCompilation.CurrentPackage("run-2", "WP-2", List.of("WP-1")),
                new RollingPackagePlanCompilation.CurrentPackage("run-3", "WP-3", List.of("WP-2"))),
                List.of("WP-1"), List.of("RQ-2", "RQ-3"));
    }

    private String candidate() {
        return """
                {"packages":[{"packageKey":"WP-2A","title":"拆分入口","objective":"实现入口",
                "replaces":["WP-2"],"dependencies":["WP-1"],"requirementRefs":["RQ-2"]}]}
                """;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
