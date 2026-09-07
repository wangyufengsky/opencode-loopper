package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageDesignCompilation.*;
import static io.opencode.loopper.service.PackageBehaviorContract.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Connects the bounded solver to the existing compiler and its persisted repair diagnostics. */
final class PackageBehaviorCompilation {
    private final ObjectMapper json;
    PackageBehaviorCompilation(ObjectMapper json) { this.json = json; }

    List<Problem> prepare(Input input, ObjectNode candidate) {
        if (input.behaviorContract() == null) return candidate.has("behaviorBranches")
                ? List.of(problem("PACKAGE_BEHAVIOR_NOT_FROZEN", "/behaviorBranches", "本运行没有冻结行为义务，不接受候选自行添加的证明", ProblemClass.CORRECTABLE, "remove behaviorBranches", "unfrozen model", "沿用当前冻结 V2 合同，不能自行升级运行")) : List.of();
        if (!"READY".equals(candidate.path("outcome").asText())) return List.of();
        List<Branch> branches;
        try { branches = json.convertValue(candidate.path("behaviorBranches"), new tools.jackson.core.type.TypeReference<List<Branch>>() { }); }
        catch (RuntimeException malformed) { return List.of(problem("CANDIDATE_BEHAVIOR_INVALID", "/behaviorBranches", "按冻结义务提交完整结构化分支", ProblemClass.CORRECTABLE, "behaviorBranches array", "missing or malformed", "复制冻结变量、事件和义务键；表达式使用有界语法")); }
        Set<String> scenarios = new LinkedHashSet<>(); candidate.path("scenarios").forEach(s -> scenarios.add(s.path("key").asText()));
        var report = new PackageBehaviorChecker(new PackageBehaviorSatSolver()).check(input.behaviorContract(), branches,
                PackageRequirementSources.index(input.requirementText()).keySet(), scenarios);
        if (!report.passed()) {
            var result = new ArrayList<Problem>();
            for (var finding : report.findings()) {
                int index = -1;
                if (branches != null) for (int i = 0; i < branches.size(); i++) if (branches.get(i) != null && finding.scenarios().contains(branches.get(i).scenarioKey())) { index = i; break; }
                String pointer = "/behaviorBranches" + (index < 0 ? "" : "/" + index);
                result.add(problem(finding.code(), pointer, finding.issueId() + "；来源=" + finding.sourceRefs() + "；场景=" + finding.scenarios(),
                        finding.code().startsWith("FROZEN_") ? ProblemClass.HUMAN_REQUIRED : ProblemClass.CORRECTABLE, finding.expected(),
                        "counterexample=" + json.writeValueAsString(new TreeMap<>(finding.counterexample())) + "; " + finding.actual(), finding.repair()));
            }
            if (report.findings().size() >= 32) result.add(problem("SEMANTIC_DIAGNOSTICS_LIMIT", "/behaviorBranches", "本回执最多32项语义问题，仍有检查可能未报告", ProblemClass.CORRECTABLE, "repair then recheck all obligations", "bounded diagnostics", "优先修复已报告根因并完整重提，不要删除未报告分支"));
            return List.copyOf(result);
        }
        var rendered = new LinkedHashMap<String, Branch>(); branches.forEach(b -> rendered.put(b.scenarioKey(), b));
        for (var node : candidate.path("scenarios")) {
            var scenario = (ObjectNode) node; var b = rendered.get(scenario.path("key").asText());
            String precondition = render(b.when());
            String observable = renderEffects(b.effects());
            String invariant = input.behaviorContract().invariants().isEmpty() ? "保持冻结原文约束" : input.behaviorContract().invariants().stream()
                    .map(i -> i.key() + ": " + render(i.predicate())).collect(java.util.stream.Collectors.joining("；"));
            if (java.util.stream.Stream.of(precondition, observable, invariant).anyMatch(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2000))
                return List.of(problem("SEMANTIC_RENDER_LIMIT", "/behaviorBranches", "展开后的完整场景超过2000字节，不截断逻辑", ProblemClass.CORRECTABLE, "bounded full expression", "expanded contract too large", "拆分独立场景或简化等价条件，保留每个适用分支"));
            scenario.put("precondition", precondition); scenario.put("action", "触发事件 " + b.event());
            scenario.put("observableResult", observable); scenario.put("invariant", invariant);
        }
        return List.of();
    }
    static String context(Input input, ObjectMapper json) {
        if (input.behaviorContract() == null) return "";
        return "\n有界行为合同 PACKAGE_BEHAVIOR_V1：场景前置/动作/结果由服务端从分支生成。证明仅覆盖冻结有限域，来源复核仍是模型证据。"
                + "未建模来源=" + input.behaviorContract().unmodeledSources() + "\n冻结义务=" + json.writeValueAsString(input.behaviorContract());
    }
    static String render(Expr e) {
        return switch (e.op()) {
            case "true" -> "所有声明域内输入";
            case "false" -> "无适用输入";
            case "eq" -> e.variable() + " = " + e.value();
            case "ne" -> e.variable() + " ≠ " + e.value();
            case "not" -> "非（" + render(e.args().getFirst()) + "）";
            default -> "（" + e.args().stream().map(PackageBehaviorCompilation::render).collect(java.util.stream.Collectors.joining("all".equals(e.op()) ? " 且 " : " 或 ")) + "）";
        };
    }
    static String renderEffects(Map<String, String> effects) {
        return new TreeMap<>(effects).entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).collect(java.util.stream.Collectors.joining("；"));
    }
    private static Problem problem(String code, String pointer, String detail, ProblemClass kind, String expected, String actual, String repair) {
        return new Problem(code, pointer, detail, List.of(), kind, false, expected, actual, repair);
    }
}
