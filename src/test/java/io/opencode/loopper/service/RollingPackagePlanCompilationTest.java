package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RollingPackagePlanCompilationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RollingPackagePlanCompilation compilation =
            new DeterministicRollingPackagePlanCompilation(json);

    @Test
    void candidateAndManualAdaptersShareCanonicalPlanAndImpactCore() throws Exception {
        RollingPackagePlanCompilation.Result candidate = compilation.compileCandidate(input(), """
                {
                  "packages": [
                    {
                      "packageKey": "WP-2A",
                      "title": "拆分入口",
                      "objective": "实现入口",
                      "replaces": ["WP-2"],
                      "dependencies": ["WP-1"],
                      "requirementRefs": ["RQ-2"]
                    },
                    {
                      "packageKey": "WP-2B",
                      "title": "拆分收口",
                      "objective": "完成收口",
                      "replaces": ["WP-2"],
                      "dependencies": ["WP-2A"],
                      "requirementRefs": ["RQ-2"]
                    },
                    {
                      "packageKey": "WP-3X",
                      "title": "合并验证",
                      "objective": "合并剩余验证",
                      "replaces": ["WP-2", "WP-3"],
                      "dependencies": ["WP-2B"],
                      "requirementRefs": ["RQ-2", "RQ-3"]
                    }
                  ]
                }
                """);

        List<RollingPackagePlanService.PlanPackage> manualPackages = List.of(
                plan("WP-2A", "拆分入口", "实现入口", List.of("run-2"), List.of("WP-1"), List.of("RQ-2")),
                plan("WP-2B", "拆分收口", "完成收口", List.of("run-2"), List.of("WP-2A"), List.of("RQ-2")),
                plan("WP-3X", "合并验证", "合并剩余验证", List.of("run-2", "run-3"),
                        List.of("WP-2B"), List.of("RQ-2", "RQ-3")));
        RollingPackagePlanCompilation.Result manual = compilation.compilePlan(input(), manualPackages);

        assertThat(candidate.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.ACCEPTED);
        assertThat(candidate.problems()).isEmpty();
        assertThat(candidate.planPackages()).isEqualTo(manualPackages);
        assertThat(candidate.canonicalPlanJson()).isEqualTo(manual.canonicalPlanJson());
        assertThat(candidate.impact()).isEqualTo(manual.impact());
        assertThat(candidate.canonicalImpactJson()).isEqualTo(manual.canonicalImpactJson());
        assertThat(json.readTree(candidate.canonicalCandidateJson()).path("packages")).hasSize(3);
        assertThat(candidate.impact().before()).containsExactly("WP-2", "WP-3");
        assertThat(candidate.impact().after()).containsExactly("WP-2A", "WP-2B", "WP-3X");
        assertThat(candidate.impact().added()).containsExactly("WP-2A", "WP-2B", "WP-3X");
        assertThat(candidate.impact().removed()).containsExactly("WP-2", "WP-3");
        assertThat(candidate.impact().reordered()).isEmpty();
        assertThat(candidate.impact().dependencyChanges()).isEmpty();
        assertThat(candidate.impact().split()).containsExactly(
                new RollingPackagePlanCompilation.Split("WP-2", List.of("WP-2A", "WP-2B", "WP-3X")));
        assertThat(candidate.impact().merged()).containsExactly(
                new RollingPackagePlanCompilation.Merge("WP-3X", List.of("WP-2", "WP-3")));
    }

    @Test
    void preservesReorderAndDependencyChangeSemantics() {
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(input(), """
                {"packages":[
                  {"packageKey":"WP-3","title":"验证","objective":"先验证","replaces":["WP-3"],
                   "dependencies":["WP-1"],"requirementRefs":["RQ-3"]},
                  {"packageKey":"WP-2","title":"实现","objective":"后实现","replaces":["WP-2"],
                   "dependencies":["WP-1","WP-3"],"requirementRefs":["RQ-2"]}
                ]}
                """);

        assertThat(result.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.ACCEPTED);
        assertThat(result.impact().reordered()).containsExactly("WP-3", "WP-2");
        assertThat(result.impact().dependencyChanges()).containsExactly(
                new RollingPackagePlanCompilation.DependencyChange(
                        "WP-3", List.of("WP-2"), List.of("WP-1")),
                new RollingPackagePlanCompilation.DependencyChange(
                        "WP-2", List.of("WP-1"), List.of("WP-1", "WP-3")));
    }

    @Test
    void manualAdapterDefaultsObjectiveAndPreservesServerOwnedCorrectionLink() throws Exception {
        RollingPackagePlanService.PlanPackage correction = new RollingPackagePlanService.PlanPackage(
                "FIX-1", "修正已冻结包", null, null, List.of(), "frozen-run-1",
                List.of("WP-1"), List.of());

        RollingPackagePlanCompilation.Result result = compilation.compilePlan(input(), List.of(correction));

        assertThat(result.accepted()).isTrue();
        assertThat(result.planPackages()).singleElement().satisfies(item -> {
            assertThat(item.objective()).isEqualTo("修正已冻结包");
            assertThat(item.correctionOfPackageRunId()).isEqualTo("frozen-run-1");
        });
        assertThat(json.readTree(result.canonicalPlanJson()).get(0)
                .path("correctionOfPackageRunId").asText()).isEqualTo("frozen-run-1");
    }

    @Test
    void closedSetSelectionErrorsAreMechanicalAndRetryable() {
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(input(), """
                {"packages":[
                  {"packageKey":"WP-2","title":"实现","objective":"实现","replaces":["WP-X"],
                   "dependencies":["WP-2"],"requirementRefs":["RQ-X"]}
                ]}
                """);

        assertThat(result.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.REJECTED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).extracting(RollingPackagePlanCompilation.Problem::code)
                .containsExactlyInAnyOrder("ROLLING_PACKAGE_SOURCE_INVALID",
                        "ROLLING_PACKAGE_DEPENDENCY_INVALID", "ROLLING_PACKAGE_REQUIREMENT_REF_INVALID");
        assertThat(result.problems()).extracting(RollingPackagePlanCompilation.Problem::pointer)
                .containsExactlyInAnyOrder("/packages/0/replaces/0", "/packages/0/dependencies/0",
                        "/packages/0/requirementRefs/0");
        assertThat(result.problems()).allSatisfy(problem ->
                assertThat(problem.problemClass()).isEqualTo(RollingPackagePlanCompilation.ProblemClass.MECHANICAL));
    }

    @Test
    void forwardDependencyIsRejectedSoTheAuthoritativePlanCannotContainACycle() {
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(input(), """
                {"packages":[
                  {"packageKey":"WP-2A","title":"入口","objective":"实现入口","replaces":["WP-2"],
                   "dependencies":["WP-2B"],"requirementRefs":["RQ-2"]},
                  {"packageKey":"WP-2B","title":"收口","objective":"完成收口","replaces":["WP-3"],
                   "dependencies":["WP-2A"],"requirementRefs":["RQ-3"]}
                ]}
                """);

        assertThat(result.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.REJECTED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ROLLING_PACKAGE_DEPENDENCY_INVALID");
            assertThat(problem.pointer()).isEqualTo("/packages/0/dependencies/0");
            assertThat(problem.allowedValues()).containsExactly("WP-1");
        });
    }

    @Test
    void missingRequiredFieldAndBenignUnknownFieldAreMechanical() {
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(input(), """
                {"packages":[
                  {"packageKey":"WP-2","title":"实现","objective":"实现","replaces":["WP-2"],
                   "dependencies":["WP-1"],"note":"不要保留"}
                ]}
                """);

        assertThat(result.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.REJECTED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).extracting(RollingPackagePlanCompilation.Problem::code)
                .contains("ROLLING_PACKAGE_FIELD_UNKNOWN", "ROLLING_PACKAGE_FIELD_REQUIRED");
    }

    @Test
    void authorityFieldFailsClosedWithoutEchoingCandidateValue() {
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(input(), """
                {"packages":[
                  {"packageKey":"WP-2","title":"实现","objective":"实现","replaces":["WP-2"],
                   "dependencies":["WP-1"],"requirementRefs":["RQ-2"],
                   "checkpointId":"private-checkpoint-value","pathRule":"private/**",
                   "command":["bash","-c","unsafe"]}
                ]}
                """);

        assertThat(result.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.NEEDS_INPUT);
        assertThat(result.retryable()).isFalse();
        assertThat(result.canonicalCandidateJson()).isNull();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ROLLING_PACKAGE_AUTHORITY_FIELD_FORBIDDEN");
            assertThat(problem.problemClass()).isEqualTo(RollingPackagePlanCompilation.ProblemClass.SECURITY);
            assertThat(problem.staticDetail()).doesNotContain("private-checkpoint-value", "bash", "unsafe");
        });
    }

    @Test
    void nonObjectAndOverlargePayloadsReturnBoundedCorrections() {
        RollingPackagePlanCompilation.Result array = compilation.compileCandidate(input(), "[]");
        RollingPackagePlanCompilation.Result overlarge = compilation.compileCandidate(input(),
                "{\"packages\":[],\"padding\":\"" + "x".repeat(70_000) + "\"}");

        assertThat(array.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.REJECTED);
        assertThat(array.retryable()).isTrue();
        assertThat(array.problems()).extracting(RollingPackagePlanCompilation.Problem::code)
                .containsExactly("ROLLING_PACKAGE_ROOT_INVALID");
        assertThat(overlarge.outcome()).isEqualTo(RollingPackagePlanCompilation.Outcome.REJECTED);
        assertThat(overlarge.retryable()).isTrue();
        assertThat(overlarge.problems()).extracting(RollingPackagePlanCompilation.Problem::code)
                .containsExactly("ROLLING_PACKAGE_CANDIDATE_TOO_LARGE");
    }

    private RollingPackagePlanCompilation.Input input() {
        return new RollingPackagePlanCompilation.Input(List.of(
                new RollingPackagePlanCompilation.CurrentPackage("run-2", "WP-2", List.of("WP-1")),
                new RollingPackagePlanCompilation.CurrentPackage("run-3", "WP-3", List.of("WP-2"))),
                List.of("WP-1"), List.of("RQ-1", "RQ-2", "RQ-3"));
    }

    private RollingPackagePlanService.PlanPackage plan(String key, String title, String objective,
                                                        List<String> sourceRunIds, List<String> dependencies,
                                                        List<String> requirementRefs) {
        return new RollingPackagePlanService.PlanPackage(key, title, objective,
                sourceRunIds.isEmpty() ? null : sourceRunIds.getFirst(), sourceRunIds, null,
                dependencies, requirementRefs);
    }
}
