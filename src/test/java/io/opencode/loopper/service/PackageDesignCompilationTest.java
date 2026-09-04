package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PackageDesignCompilationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PackageDesignCompilation compilation =
            new DeterministicPackageDesignCompilation(json);

    @Test
    void compilesReadyCandidateThroughAuthoritativeAcceptanceKernel() {
        PackageDesignCompilation.Result result = compilation.compileCandidate(input(), readyCandidate());

        assertThat(result.outcome()).isEqualTo(PackageDesignCompilation.Outcome.ACCEPTED);
        assertThat(result.problems()).isEmpty();
        assertThat(result.canonicalCandidateJson()).contains("\"contractVersion\":\"PACKAGE_DESIGN_V1\"");
        assertThat(result.canonicalMarkdown())
                .contains("## 验收场景", "未注册事件被安全忽略", "## 阶段与依赖", "事件分发测试");
        assertThat(result.compiledPlan()).isNotNull();
        assertThat(result.compiledPlan().status()).isEqualTo("COMPILED");
        assertThat(result.compiledPlan().stages()).hasSize(1);
        assertThat(result.compiledResultJson()).isEqualTo(json.writeValueAsString(result.compiledPlan()));
    }

    @Test
    void structuredAndMarkdownAdaptersCompileTheSameCanonicalPlan() {
        PackageDesignCompilation.Result structured = compilation.compileCandidate(input(), readyCandidate());
        PackageDesignCompilation.Result markdown = compilation.compileMarkdown(input(), structured.canonicalMarkdown());

        assertThat(markdown.outcome())
                .as("markdown compilation problems: %s", markdown.problems())
                .isEqualTo(PackageDesignCompilation.Outcome.ACCEPTED);
        assertThat(markdown.canonicalMarkdown()).isEqualTo(structured.canonicalMarkdown());
        assertThat(markdown.compiledPlan()).isEqualTo(structured.compiledPlan());
        assertThat(markdown.compiledResultJson()).isEqualTo(structured.compiledResultJson());
    }

    @Test
    void mechanicalReferenceProblemIsStaticRetryableAndFallbackEligible() {
        String candidate = readyCandidate().replace("\"SC-1\", \"DEL-1\"", "\"SC-1\", \"UNKNOWN\"");

        PackageDesignCompilation.Result result = compilation.compileCandidate(input(), candidate);

        assertThat(result.outcome()).isEqualTo(PackageDesignCompilation.Outcome.REJECTED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PACKAGE_DESIGN_REFERENCE_INVALID");
            assertThat(problem.pointer()).isEqualTo("/stages/0/includes/1");
            assertThat(problem.staticDetail()).contains("UNKNOWN", "不是候选中已声明");
            assertThat(problem.fallbackEligible()).isTrue();
        });
    }

    @Test
    void serverOwnedExecutionFieldsFailClosedWithoutEchoingCandidateValues() {
        String candidate = readyCandidate().replace("\"key\": \"STAGE-1\"",
                "\"allowedPaths\": [\"private/secret/**\"], \"key\": \"STAGE-1\"");

        PackageDesignCompilation.Result result = compilation.compileCandidate(input(), candidate);

        assertThat(result.outcome()).isEqualTo(PackageDesignCompilation.Outcome.NEEDS_INPUT);
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PACKAGE_DESIGN_SECURITY_BOUNDARY");
            assertThat(problem.problemClass()).isEqualTo(PackageDesignCompilation.ProblemClass.SECURITY);
            assertThat(problem.staticDetail()).doesNotContain("private", "secret");
            assertThat(problem.fallbackEligible()).isFalse();
        });
    }

    @Test
    void explicitSemanticGapStopsAtNeedsInput() {
        PackageDesignCompilation.Result result = compilation.compileCandidate(input(), """
                {
                  "contractVersion":"PACKAGE_DESIGN_V1",
                  "outcome":"NEEDS_INPUT",
                  "requirements":[],
                  "scenarios":[],
                  "deliverables":[],
                  "reviews":[],
                  "stages":[],
                  "gapCodes":["MISSING_EXCEPTION_SEMANTICS"]
                }
                """);

        assertThat(result.outcome()).isEqualTo(PackageDesignCompilation.Outcome.NEEDS_INPUT);
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("MISSING_EXCEPTION_SEMANTICS");
            assertThat(problem.problemClass()).isEqualTo(PackageDesignCompilation.ProblemClass.HUMAN_REQUIRED);
            assertThat(problem.fallbackEligible()).isFalse();
        });
    }

    @Test
    void incompleteStageCoverageReturnsAStaticCorrectionWithoutFallback() {
        String candidate = readyCandidate().replace("\"SC-1\", \"DEL-1\"", "\"SC-1\"");

        PackageDesignCompilation.Result result = compilation.compileCandidate(input(), candidate);

        assertThat(result.outcome()).isEqualTo(PackageDesignCompilation.Outcome.REJECTED);
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PACKAGE_DESIGN_COVERAGE_INCOMPLETE");
            assertThat(problem.problemClass()).isEqualTo(PackageDesignCompilation.ProblemClass.CORRECTABLE);
            assertThat(problem.pointer()).isEqualTo("/deliverables/0/key");
            assertThat(problem.staticDetail()).contains("DEL-1", "stages[].includes");
            assertThat(problem.fallbackEligible()).isFalse();
        });
    }

    @Test
    void stageDependencyCycleIsMechanicalAndRetryable() {
        String candidate = readyCandidate().replace("\"dependencies\": []", "\"dependencies\": [\"STAGE-1\"]");

        PackageDesignCompilation.Result result = compilation.compileCandidate(input(), candidate);

        assertThat(result.outcome()).isEqualTo(PackageDesignCompilation.Outcome.REJECTED);
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PACKAGE_DESIGN_STAGE_DEPENDENCY_INVALID");
            assertThat(problem.pointer()).isEqualTo("/stages");
            assertThat(problem.fallbackEligible()).isTrue();
        });
    }

    private String readyCandidate() {
        return """
                {
                  "contractVersion": "PACKAGE_DESIGN_V1",
                  "outcome": "READY",
                  "requirements": [
                    {"key": "REQ-1", "statement": "事件分发必须安全处理未注册事件"}
                  ],
                  "scenarios": [
                    {
                      "key": "SC-1",
                      "title": "未注册事件被安全忽略",
                      "precondition": "事件类型尚未注册",
                      "action": "发布该事件",
                      "observableResult": "发布调用正常返回且没有处理器被调用",
                      "invariant": "既有已注册事件分发不变",
                      "requirementRefs": ["REQ-1"]
                    }
                  ],
                  "deliverables": [
                    {
                      "key": "DEL-1",
                      "kind": "DELIVERABLE",
                      "target": "src/test/java/example/EventBusTest.java",
                      "description": "新增 EventBusTest 聚焦验证未注册事件分支",
                      "requirementRefs": ["REQ-1"]
                    }
                  ],
                  "reviews": [],
                  "stages": [
                    {
                      "key": "STAGE-1",
                      "title": "事件分发测试",
                      "objective": "实现并验证未注册事件分支",
                      "includes": ["SC-1", "DEL-1"],
                      "dependencies": []
                    }
                  ],
                  "gapCodes": []
                }
                """;
    }

    private PackageDesignCompilation.Input input() {
        return new PackageDesignCompilation.Input(workPackage(), "新增事件分发安全分支",
                new WorkPackageRoleService.View("software-java", RolePackRegistry.VERSION,
                        ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED, List.of("java")),
                List.of("src/test/java/example/EventBusTest.java"), List.of(), List.of("EventBusTest"), 6, true);
    }

    private DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow("package-row", "designer", "requirement", "decomposition",
                "WP-1", 0, "事件分发", "实现事件分发", "[]", "[]", "[]", "[]", "[]", "[]",
                "DESIGNING", null, null, null, 1, 0, 0, null, null, null, null, null, 0, null, null,
                "now", "now", 0);
    }
}
