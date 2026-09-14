package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Synthetic frozen facts plus real production compilers; no HTTP owner settlement or writable workflow. */
public final class AllRoleModelProbe {
    private final ObjectMapper json = new ObjectMapper();
    private final MachineCandidateKind kind;
    AllRoleModelProbe(MachineCandidateKind kind) { this.kind = kind; }

    public static void main(String[] args) throws Exception {
        var probe = new AllRoleModelProbe(MachineCandidateKind.valueOf(args[0]));
        if (args.length > 1 && args[1].equals("schema")) {
            System.out.println(probe.json.writeValueAsString(InternalMcpContractCatalog.inputSchema(probe.kind)));
            return;
        }
        if (args.length > 1 && args[1].equals("prompt")) {
            System.out.println(probe.instructions());
            return;
        }
        List<CandidateSubmissionAttemptRow> recent = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            for (String candidate; (candidate = reader.readLine()) != null;) {
                try {
                    String submitted = candidate;
                    var decision = probe.evaluate(submitted);
                    var bounded = CandidateDiagnosticEnricher.bound(decision.problems());
                    var progress = CandidateRepairProgress.analyze(probe.json, candidate, "invalid-json",
                            bounded.problems(), bounded.complete() && decision.diagnosticsComplete(), recent);
                    String outcome = decision.accepted() ? "ACCEPTED" : decision.retryable() ? "REJECTED" : "WAITING_INPUT";
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("outcome", outcome);
                    response.put("retryable", decision.retryable());
                    response.put("diagnosticVersion", "CANDIDATE_DIAGNOSTIC_V2");
                    response.put("diagnosticsComplete", bounded.complete() && decision.diagnosticsComplete());
                    response.put("problems", bounded.problems());
                    response.put("repairProgress", progress);
                    response.put("action", decision.accepted() ? "STOP_ACCEPTED" : decision.retryable() ? "FIX_AND_RESUBMIT" : "STOP_AND_WAIT_FOR_INPUT");
                    if (decision.accepted()) response.put("canonicalCandidate", probe.json.readTree(decision.canonicalCandidateJson()));
                    String serialized = probe.json.writeValueAsString(response);
                    recent.addFirst(new CandidateSubmissionAttemptRow("probe", "probe", recent.size()+1, "probe", "a".repeat(64),
                            outcome, decision.retryable(), "[]", serialized, null, "now"));
                    if (recent.size() > 3) recent.removeLast();
                    System.out.println(serialized);
                } catch (RuntimeException invalid) {
                    System.out.println(probe.json.writeValueAsString(Map.of("outcome", "INTERNAL_ERROR", "exception", invalid.getClass().getSimpleName())));
                }
            }
        }
    }

    CandidatePolicy.Decision evaluate(String candidate) {
        if (kind != MachineCandidateKind.PACKAGE_DESIGN_V1) {
            return CandidateDiagnosticStages.evaluate(json, kind, candidate, () -> compile(candidate));
        }
        var policy = new CandidatePolicy() {
            public boolean supports(MachineCandidateKind value) { return value == kind; }
            public Decision evaluate(Context context, String value) { return compile(value); }
        };
        return PackageDesignCandidateEvaluation.evaluate(json, policy,
                new CandidatePolicy.Context("probe", null, null, kind, "probe", 1, 1, kind.name(), 4, 0), candidate);
    }

    String instructions() {
        return switch (kind) {
            case DOCUMENT_REQUIREMENTS_V1, DOCUMENT_REQUIREMENT_REVIEW_V1,
                    REQUIREMENT_CODE_ASSESSMENT_V1, REQUIREMENT_ASSESSMENT_REVIEW_V1 ->
                    throw new IllegalArgumentException("Document template roles require the scoped document fixture");
            case DECOMPOSITION_PLAN_V2 -> "冻结需求 RQ-1:保持 loopback-only；RQ-2:校验候选；RQ-3:仅持久化接受结果。规划两个纵向业务包，覆盖全部 RQ，依赖使用零基前序索引。";
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> "冻结闭集：factIndex=0 已分配到 stageIndex=0，factAssignments 应为空；两个完整同分选择分别为 capabilityIndexes:[0] 或 [1]。为 factIndex=0 选择其中一个完整最优集合。";
            case PACKAGE_DESIGN_V1 -> "冻结需求：为 EventBus 未注册事件补充单元测试，publish 正常返回且无处理器调用，既有行为不变。Java REQUIRED；交付 src/test/java/example/EventBusTest.java，聚焦目标 EventBusTest。一个阶段，覆盖全部需求；reviews 无主观项时为空。只提交语义字段。";
            case ROLLING_PACKAGE_PLAN_V1 -> "冻结已保留包 WP-1。未完成包 WP-2(依赖 WP-1)、WP-3(依赖 WP-2)，对应需求 RQ-2、RQ-3；全部允许 RQ-1,RQ-2,RQ-3。保留两包目标，提交完整剩余计划，replaces 指向被替换的现有包 key，依赖仅指向保留包或提案中更早包。";
            case REVIEWER_REPORT_V1 -> "冻结审查文件 src/example.java，内容为单行 class Example {}，共 1 行。对该夹具审查，未发现真实缺陷时 findings=[]；summary 必须非空，limitations 必须是字符串数组。不得虚构缺陷。";
            case PROJECT_CONVENTION_V1 -> "冻结公约目录：componentKeys=[java-root]，Java/Maven/JUnit 根组件；commandIds=[java-root:test]；pathIds=[java-root:root,java-root:manifest:pom.xml]。选择该组件和相关已知 ID，保留唯一性。";
            case JUDGE_DECISION_V1 -> "冻结 role=RISK，唯一 evidenceId=risk，证据内容为：差异仍存在未解决风险。请提交 BLOCKED 判定并用简短单行中文说明；不得为被接受而改判 PASS。";
        };
    }

    CandidatePolicy.Decision compile(String candidate) {
        return switch (kind) {
            case DOCUMENT_REQUIREMENTS_V1, DOCUMENT_REQUIREMENT_REVIEW_V1,
                    REQUIREMENT_CODE_ASSESSMENT_V1, REQUIREMENT_ASSESSMENT_REVIEW_V1 ->
                    throw new IllegalArgumentException("Document template roles require the scoped document fixture");
            case DECOMPOSITION_PLAN_V2 -> {
                var revision = new DesignRequirementRevisionRow("rev", "session", 3, "message", instructions(),
                        "[{\"id\":\"RQ-1\",\"text\":\"remain loopback-only\"},{\"id\":\"RQ-2\",\"text\":\"validate candidates\"},{\"id\":\"RQ-3\",\"text\":\"persist accepted only\"}]",
                        7, "ACTIVE", 1, 8, "now", "now", 0);
                var result = new DesignerDecompositionCandidateCompiler(json).compile(candidate, revision);
                yield !result.accepted() ? CandidatePolicy.Decision.rejected(true, result.problems())
                        : !result.boundaryProblems().isEmpty() ? CandidatePolicy.Decision.rejected(false, result.boundaryProblems())
                        : CandidatePolicy.Decision.accepted(result.canonicalJson());
            }
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> acceptance(candidate);
            case PACKAGE_DESIGN_V1 -> packageDesign(candidate);
            case ROLLING_PACKAGE_PLAN_V1 -> {
                var input = new RollingPackagePlanCompilation.Input(List.of(
                        new RollingPackagePlanCompilation.CurrentPackage("run-2", "WP-2", List.of("WP-1")),
                        new RollingPackagePlanCompilation.CurrentPackage("run-3", "WP-3", List.of("WP-2"))),
                        List.of("WP-1"), List.of("RQ-1", "RQ-2", "RQ-3"));
                var result = new DeterministicRollingPackagePlanCompilation(json).compileCandidate(input, candidate);
                yield result.accepted() ? CandidatePolicy.Decision.accepted(result.canonicalCandidateJson())
                        : CandidatePolicy.Decision.rejected(result.retryable(), result.problems().stream().map(p ->
                        new MachineCandidateSubmission.Problem(p.code(), p.pointer(), p.staticDetail(), p.allowedValues())).toList());
            }
            case REVIEWER_REPORT_V1 -> {
                var decoded = new ReviewerReportCandidateCodec(json).decodeCandidate(candidate);
                if (!decoded.valid()) yield CandidatePolicy.Decision.rejected(!decoded.security(), List.of(decoded.problem()));
                var result = new DeterministicReviewerReportCompilation(json).compile(new ReviewerReportCompilation.Input(decoded.candidate(),
                        List.of(new ReviewerReportCompilation.SourceFile("src/example.java", 16, 1, "a".repeat(64)))));
                yield result.accepted() ? CandidatePolicy.Decision.accepted(result.canonicalCandidateJson())
                        : CandidatePolicy.Decision.rejected(result.retryable(), result.problems().stream().map(ReviewerReportCompilation.Problem::submissionProblem).toList());
            }
            case PROJECT_CONVENTION_V1 -> {
                var catalog = new ProjectConventionCompilation.EvidenceCatalog("a".repeat(64), List.of(
                        new ProjectConventionCompilation.ComponentEvidence("java-root", ".", List.of("java"), List.of("maven"), List.of("junit"))), List.of(
                        new ProjectConventionCompilation.CommandEvidence("java-root:test", "java-root", List.of("mvn", "test"))), List.of(
                        new ProjectConventionCompilation.PathEvidence("java-root:root", "java-root", ".", ProjectConventionCompilation.PathKind.COMPONENT_ROOT),
                        new ProjectConventionCompilation.PathEvidence("java-root:manifest:pom.xml", "java-root", "pom.xml", ProjectConventionCompilation.PathKind.MANIFEST)));
                var result = new DeterministicProjectConventionCompilation(json, new ProjectConventionDocumentStore()).compileCandidate(new ProjectConventionCompilation.Input("# Human rule\n", catalog), candidate);
                yield result.accepted() ? CandidatePolicy.Decision.accepted(result.canonicalCandidateJson())
                        : CandidatePolicy.Decision.rejected(result.retryable(), result.problems().stream().map(p ->
                        new MachineCandidateSubmission.Problem(p.code(), p.pointer(), p.staticDetail(), p.allowedValues())).toList());
            }
            case JUDGE_DECISION_V1 -> {
                var input = new JudgeDecisionCompilation.Input("RISK", new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                        new JudgeDecisionCompilation.EvidenceItem("risk", "DIFF", "Unresolved risk", "a".repeat(64)))));
                var result = new DeterministicJudgeDecisionCompilation(json).compileCandidate(input, candidate);
                yield result.accepted() ? CandidatePolicy.Decision.accepted(result.canonicalCandidateJson())
                        : CandidatePolicy.Decision.rejected(result.retryable(), result.problems().stream().map(JudgeDecisionCompilation.Problem::submissionProblem).toList());
            }
        };
    }

    private CandidatePolicy.Decision acceptance(String candidate) {
        var contract = new DesignerClosedChoiceContract(json, new AiOutputExtractor(json));
        if (contract.inspectCandidateBoundary(candidate) == DesignerClosedChoiceContract.CandidateBoundary.SECURITY_BOUNDARY) {
            return CandidatePolicy.Decision.rejected(false, List.of(new MachineCandidateSubmission.Problem("ACCEPTANCE_CANDIDATE_SECURITY_BOUNDARY", "/candidate", "Forbidden authority field")));
        }
        var fact = new DesignerAcceptancePlanning.Fact(0, DesignerAcceptancePlanning.FactKind.SCENARIO,
                "成功", "输入合法", "执行", "返回成功", "不写外部系统", null, "DS-L001", "成功", "a".repeat(64));
        var stage = new DesignerAcceptancePlanning.StageHint("实现", "实现行为", List.of("成功"), List.of(), List.of(), List.of());
        var facts = new DesignerAcceptancePlanning.Catalog(DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                "WP-1", 3, "a".repeat(64), true, List.of(fact), List.of(stage), List.of());
        var capabilities = new DesignerAcceptancePlanning.CapabilityCatalog(DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                java.util.stream.IntStream.range(0,2).mapToObj(index -> new DesignerAcceptancePlanning.Capability(index,
                        "FOCUSED_TEST", "测试 " + index, List.of("mvn", "-Dtest=Flow" + index + "Test", "test"),
                        List.of(0), List.of("Flow" + index + "Test"), true, false, 100)).toList(), List.of());
        var resolver = new DesignerAcceptanceFastPathResolver();
        try {
            var binding = resolver.merge(resolver.resolve(facts, capabilities), contract.parse(candidate).value(), facts, capabilities);
            return CandidatePolicy.Decision.accepted(json.writeValueAsString(binding));
        } catch (BadRequestException invalid) {
            return CandidatePolicy.Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem(
                    "ACCEPTANCE_CANDIDATE_SELECTION_INVALID", "/capabilityPreferences", invalid.getMessage(),
                    List.of("[{\"factIndex\":0,\"capabilityIndexes\":[0]}]", "[{\"factIndex\":0,\"capabilityIndexes\":[1]}]"))));
        }
    }

    private CandidatePolicy.Decision packageDesign(String candidate) {
        var workPackage = new DesignWorkPackageRow("package-row", "designer", "requirement", "decomposition",
                "WP-1", 0, "事件分发", "实现事件分发", "[]", "[]", "[]", "[]", "[]", "[]",
                "DESIGNING", null, null, null, 1, 0, 0, null, null, null, null, null, 0, null, null, "now", "now", 0);
        var input = new PackageDesignCompilation.Input(workPackage,
                "为 EventBus 的未注册事件补充单元测试：publish 正常返回且不调用处理器，既有分发行为不变。",
                new WorkPackageRoleService.View("software-java", "2026-08-dynamic-v7", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION,
                        TestPolicy.REQUIRED, List.of("java")), List.of("src/test/java/example/EventBusTest.java"), List.of(), List.of("EventBusTest"), 6, true);
        var result = new DeterministicPackageDesignCompilation(json).compileCandidate(input, candidate);
        return result.accepted() ? CandidatePolicy.Decision.accepted(result.canonicalCandidateJson())
                : CandidatePolicy.Decision.rejected(result.retryable(), result.problems().stream().map(PackageDesignCompilation.Problem::submissionProblem).toList());
    }
}
