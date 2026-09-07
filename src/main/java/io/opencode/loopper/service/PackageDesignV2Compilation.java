package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageDesignCompilation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** V2 validates semantic references, then delegates executable authority to the existing server compiler. */
final class PackageDesignV2Compilation {
    private final ObjectMapper json;
    PackageDesignV2Compilation(ObjectMapper json) { this.json = json; }

    Result compile(Input input, String candidateJson, BiFunction<Input, String, Result> lower) {
        var frozenInput = input.confirmedDecisions().isEmpty() ? input : new Input(input.workPackage(),
                input.requirementText() + "\n用户明确补充：\n" + String.join("\n", input.confirmedDecisions().values()), input.role(),
                input.scopeIn(), input.scopeOut(), input.deliverables(), input.stageLimit(), input.directSoftwareMode(), "PACKAGE_DESIGN_V2");
        var frozen = PackageDesignInputPreflight.problems(frozenInput);
        if (!frozen.isEmpty()) return rejected(Outcome.NEEDS_INPUT, null, frozen.stream().map(problem -> new Problem(
                "PACKAGE_GAP_PROVEN_CONFLICT", problem.pointer(), problem.staticDetail(),
                List.copyOf(PackageRequirementSources.index(input.requirementText()).keySet()), problem.problemClass(), false,
                problem.expected(), "冻结约束校验=" + problem.code() + "；" + problem.actual(),
                "处理动作=STOP_CONFLICT；保留双方冻结来源，通过本地反馈解决约束冲突：" + problem.staticDetail())).toList());
        var decoded = new PackageDesignV2Codec(json).decode(input, candidateJson);
        if (!decoded.valid()) return rejected(decoded.problems().stream().anyMatch(problem -> problem.problemClass() == ProblemClass.SECURITY)
                ? Outcome.NEEDS_INPUT : Outcome.REJECTED, null, decoded.problems());
        String canonical = json.writeValueAsString(decoded.document());
        var gaps = new PackageDesignSourceGapAssessment().problems(input, decoded.document());
        if (!gaps.isEmpty()) return rejected(gaps.stream().anyMatch(problem ->
                problem.problemClass() == ProblemClass.HUMAN_REQUIRED || problem.problemClass() == ProblemClass.SECURITY)
                ? Outcome.NEEDS_INPUT : Outcome.REJECTED, canonical, gaps);
        ObjectNode lowered = (ObjectNode) json.valueToTree(decoded.base());
        lowered.put("outcome", "READY"); lowered.set("gapCodes", json.createArrayNode());
        Result compiled = lower.apply(frozenInput, json.writeValueAsString(lowered));
        if (!compiled.accepted()) return new Result(compiled.outcome(), canonical, compiled.canonicalMarkdown(),
                null, null, noFallback(compiled.problems()));
        var plan = compiled.compiledPlan();
        String context = semanticContext(input, decoded, false);
        if (plan.evidenceMappings().stream().anyMatch(item -> join(item.judgeRubric(), context).length() > 4000))
            return rejected(Outcome.REJECTED, canonical, List.of(new Problem("PACKAGE_RELATION_CONTEXT_LIMIT", "/relations",
                    "关系与来源引用超出单条验收准则的4000字符限额；请缩短局部键或拆分独立关系，不得删除适用分支",
                    List.of(), ProblemClass.CORRECTABLE, false)));
        var mappings = plan.evidenceMappings().stream().map(item -> new DesignerSemanticContracts.AcceptanceEvidenceMapping(
                item.stageIndex(), item.criterionId(), item.description(), item.designerExcerpt(), item.verificationMode(),
                join(item.judgeRubric(), context), item.judgeOnlyReason(), item.verifierStrategy(), item.testCommand(),
                item.testTargets(), item.designerExcerpts())).toList();
        var enriched = new DesignerSemanticContracts.PackageCompilationPlanEnvelope(plan.contractVersion(), plan.status(),
                plan.summary(), plan.stages(), mappings, join(plan.handoffSummary(), semanticContext(input, decoded, true) + "\n冻结原文（整理不能覆盖）：\n" + input.requirementText()), plan.designGaps());
        return new Result(Outcome.ACCEPTED, canonical, compiled.canonicalMarkdown(), enriched,
                json.writeValueAsString(enriched), List.of());
    }

    private String semanticContext(Input input, PackageDesignV2Codec.Decoded decoded, boolean detailed) {
        var lines = new ArrayList<String>();
        lines.add("PACKAGE_DESIGN_V2：原始需求保持权威；来源绑定与关系是已校验引用的候选表达，不证明自然语言完整性。所有适用分支均须验收；跨阶段不变量贯穿全部关联阶段。");
        lines.add("原文 SHA-256=" + PackageDesignEvidencePreparation.hash(input.requirementText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (detailed) PackageRequirementSources.index(input.requirementText()).forEach((ref, source) ->
                lines.add(ref + " sha256=" + source.sha256()));
        if (detailed) input.confirmedDecisions().forEach((ref, decision) -> lines.add("用户明确补充 " + ref + "=" + decision));
        else if (!input.confirmedDecisions().isEmpty()) lines.add("用户补充来源=" + input.confirmedDecisions().keySet()
                + " sha256=" + PackageDesignEvidencePreparation.hash(json.writeValueAsString(input.confirmedDecisions()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (var relation : decoded.document().relations()) {
            lines.add("关系 " + relation.key() + " " + relation.operator() + " " + relation.operands()
                    + " 来源 " + relation.sourceRefs() + "；所有叶场景=" + decoded.graph().branchScenarios().get(relation.key()));
        }
        if (detailed) decoded.document().sourceBindings().forEach(binding -> lines.add("来源绑定 " + binding.candidateRefs() + " -> " + binding.sourceRefs()));
        return String.join("\n", lines);
    }

    private static List<Problem> noFallback(List<Problem> problems) {
        return problems.stream().map(p -> new Problem(p.code(), p.pointer(), p.staticDetail(), p.allowedValues(),
                p.problemClass(), false, p.expected(), p.actual(), p.repairHint())).toList();
    }

    private static String join(String first, String second) { return first == null || first.isBlank() ? second : first + "\n" + second; }
    private Result rejected(Outcome outcome, String canonical, List<Problem> problems) {
        return new Result(outcome, canonical, null, null, null, noFallback(problems).stream().limit(64).toList());
    }
}
