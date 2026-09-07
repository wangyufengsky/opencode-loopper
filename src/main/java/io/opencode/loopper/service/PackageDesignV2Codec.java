package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageDesignCompilation.ProblemClass.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates source/graph structure before calling the unchanged V1 semantic field validator. */
final class PackageDesignV2Codec {
    record Decoded(PackageDesignV2Document document, PackageDesignCandidateDocument base,
                   PackageSemanticRelations.Graph graph, List<PackageDesignCompilation.Problem> problems) {
        boolean valid() { return problems.isEmpty(); }
    }
    private static final Set<String> FIELDS = Set.of("contractVersion", "outcome", "requirements", "scenarios", "deliverables",
            "reviews", "stages", "gapCodes", "sourceBindings", "relations", "gapClaims");
    private final ObjectMapper json;
    PackageDesignV2Codec(ObjectMapper json) { this.json = json; }

    Decoded decode(PackageDesignCompilation.Input input, String candidate) {
        try {
            var raw = json.readTree(candidate);
            var denied = raw == null ? null : PackageDesignCandidateCodec.securityBoundary(raw);
            if (denied != null) return new Decoded(null, null, null, List.of(denied));
        } catch (RuntimeException invalid) { /* Schema supplies bounded parse diagnostics. */ }
        var shape = CandidateShapeValidator.validate(json,
                io.opencode.loopper.runtime.InternalMcpContractCatalog.packageDesignV2InputSchema(), candidate);
        if (!shape.problems().isEmpty()) return new Decoded(null, null, null, shape.problems().stream()
                .map(item -> problem(item.code(), item.pointer(), item.detail())).toList());
        var root = json.readTree(candidate);
        if (!(root instanceof ObjectNode object)) return failed("PACKAGE_V2_OBJECT_REQUIRED", "/candidate", "提交完整 V2 对象");
        var authority = PackageDesignCandidateCodec.securityBoundary(root);
        if (authority != null) return new Decoded(null, null, null, List.of(authority));
        for (var field : object.properties()) if (!FIELDS.contains(field.getKey())) return failed("PACKAGE_V2_FIELD_UNKNOWN", "/" + field.getKey(), "该字段不属于 V2 语义合同");
        for (String field : FIELDS) if (!object.hasNonNull(field)) return failed("PACKAGE_V2_FIELD_REQUIRED", "/" + field, "字段必须存在；无内容的集合使用空数组");
        PackageDesignV2Document value;
        try { value = json.treeToValue(root, PackageDesignV2Document.class); }
        catch (RuntimeException malformed) { return failed("PACKAGE_V2_SHAPE_INVALID", "/candidate", "字段类型与 V2 Schema 不一致"); }
        if (!PackageDesignV2Document.VERSION.equals(value.contractVersion())) return failed("PACKAGE_V2_VERSION_INVALID", "/contractVersion", "当前运行只接受 PACKAGE_DESIGN_V2");
        var lowered = object.deepCopy();
        lowered.remove("sourceBindings"); lowered.remove("relations"); lowered.remove("gapClaims");
        lowered.put("contractVersion", "PACKAGE_DESIGN_V1");
        var base = new PackageDesignCandidateCodec(json).decode(json.writeValueAsString(lowered), input.stageLimit());
        List<PackageDesignCompilation.Problem> problems = new ArrayList<>(base.problems().stream()
                .filter(problem -> !(problem.problemClass() != SECURITY && problem.pointer() != null && problem.pointer().matches("/gapCodes/[0-9]+") && recognizedGap(problem.code()))).toList());
        if (!problems.isEmpty() || base.candidate() == null) return new Decoded(value, base.candidate(), null, List.copyOf(problems));
        Set<String> originalSources = PackageRequirementSources.index(input.requirementText()).keySet();
        Set<String> sources = new HashSet<>(originalSources);
        if (input.repositoryEvidence() != null) input.repositoryEvidence().files().forEach(file -> sources.add(file.sourceRef()));
        validateSources(value, sources, originalSources, problems);
        validateClaims(value, sources, problems);
        Set<String> scenarios = new HashSet<>();
        value.scenarios().forEach(item -> scenarios.add(item.key()));
        var graph = new PackageSemanticRelations().validate(value.relations(), scenarios, sources);
        graph.issues().forEach(issue -> problems.add(problem(issue.code(), issue.pointer(), issue.detail())));
        return new Decoded(value, base.candidate(), graph, List.copyOf(problems));
    }

    private void validateSources(PackageDesignV2Document value, Set<String> sources, Set<String> originalSources, List<PackageDesignCompilation.Problem> problems) {
        Set<String> keys = candidateKeys(value);
        Set<String> boundCandidates = new HashSet<>(), boundSources = new HashSet<>(), bindingKeys = new HashSet<>();
        if (value.sourceBindings().size() > 128) { problems.add(problem("PACKAGE_SOURCE_LIMIT", "/sourceBindings", "最多128组来源绑定，请合并引用相同来源的事实")); return; }
        for (int i = 0; i < value.sourceBindings().size(); i++) {
            var binding = value.sourceBindings().get(i);
            if (binding == null || binding.key() == null || binding.key().isBlank() || !bindingKeys.add(binding.key())
                    || !references(binding.candidateRefs(), keys) || !references(binding.sourceRefs(), sources)) {
                problems.add(problem("PACKAGE_SOURCE_REFERENCE_INVALID", "/sourceBindings/" + i, "绑定需要唯一局部 key、已声明的候选引用和当前冻结来源引用"));
                continue;
            }
            boundCandidates.addAll(binding.candidateRefs()); boundSources.addAll(binding.sourceRefs());
        }
        if ("READY".equals(value.outcome())) {
            List<String> required = new ArrayList<>();
            value.requirements().forEach(item -> required.add(item.key())); value.scenarios().forEach(item -> required.add(item.key()));
            for (String key : required) if (!boundCandidates.contains(key)) problems.add(problem("PACKAGE_SOURCE_COVERAGE", "/sourceBindings", "需求或场景 " + key + " 尚未绑定原始来源"));
            for (String ref : originalSources) if (!boundSources.contains(ref)) problems.add(problem("PACKAGE_SOURCE_COVERAGE", "/sourceBindings", "原始来源 " + ref + " 尚未保留在候选中；来源引用不替代语义验收"));
        }
    }

    private void validateClaims(PackageDesignV2Document value, Set<String> sources, List<PackageDesignCompilation.Problem> problems) {
        if (value.gapClaims().size() > 16) { problems.add(problem("PACKAGE_GAP_CLAIM_LIMIT", "/gapClaims", "最多16条有依据的缺口声明")); return; }
        Set<String> codes = new HashSet<>(), keys = new HashSet<>();
        for (int i = 0; i < value.gapClaims().size(); i++) {
            var claim = value.gapClaims().get(i);
            if (claim == null || claim.key() == null || claim.key().isBlank() || !keys.add(claim.key())
                    || !recognizedGap(claim.code()) || !references(claim.sourceRefs(), sources)
                    || claim.question() == null || claim.question().isBlank() || claim.alternatives() == null
                    || claim.alternatives().size() > 4 || new HashSet<>(claim.alternatives()).size() != claim.alternatives().size() || claim.alternatives().stream().anyMatch(item -> item == null || item.isBlank())) {
                problems.add(problem("PACKAGE_GAP_CLAIM_INVALID", "/gapClaims/" + i, "缺口声明需要局部 key、闭集 code、原始来源、明确问题和至多4个不同选择；仓库未知事实不能伪装为用户决定"));
                continue;
            }
            codes.add(claim.code());
        }
        if (!codes.equals(new HashSet<>(value.gapCodes()))) problems.add(problem("PACKAGE_GAP_CLAIM_CODES", "/gapClaims", "gapCodes 必须与有依据的 gapClaims 一一对应；READY 使用两个空数组"));
    }

    private Set<String> candidateKeys(PackageDesignV2Document value) {
        Set<String> keys = new HashSet<>();
        value.requirements().forEach(item -> keys.add(item.key())); value.scenarios().forEach(item -> keys.add(item.key()));
        value.deliverables().forEach(item -> keys.add(item.key())); value.reviews().forEach(item -> keys.add(item.key()));
        value.stages().forEach(item -> keys.add(item.key()));
        return keys;
    }
    private static boolean references(List<String> refs, Set<String> known) {
        return refs != null && !refs.isEmpty() && refs.size() <= 128 && refs.stream().allMatch(ref -> ref != null && known.contains(ref));
    }
    private static boolean recognizedGap(String code) {
        return java.util.Arrays.stream(DesignerSemanticContracts.DesignGapCode.values()).anyMatch(item -> item.name().equals(code));
    }
    private Decoded failed(String code, String pointer, String detail) { return new Decoded(null, null, null, List.of(problem(code, pointer, detail))); }
    private static PackageDesignCompilation.Problem problem(String code, String pointer, String detail) {
        return new PackageDesignCompilation.Problem(code, pointer, detail, List.of(), MECHANICAL, false,
                "完整、有界、引用有效的 V2 语义合同", "当前字段不满足合同", "按指针修正并重提完整对象；保留未受影响的需求和分支");
    }
}
