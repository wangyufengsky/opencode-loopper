package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageDesignCompilation.ProblemClass.MECHANICAL;
import static io.opencode.loopper.service.PackageDesignCompilation.ProblemClass.CORRECTABLE;
import static io.opencode.loopper.service.PackageDesignCompilation.ProblemClass.HUMAN_REQUIRED;
import static io.opencode.loopper.service.PackageDesignCompilation.ProblemClass.SECURITY;

import io.opencode.loopper.service.DesignerSemanticContracts.DesignGapCode;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict codec and semantic firewall for the model-owned PACKAGE_DESIGN_V1 document. */
final class PackageDesignCandidateCodec {
    static final String CONTRACT_VERSION = "PACKAGE_DESIGN_V1";
    private static final int MAX_PROBLEMS = 64;
    private static final Set<String> OUTCOMES = Set.of("READY", "NEEDS_INPUT");
    private static final Set<String> DELIVERABLE_KINDS = Set.of("SCOPE", "DELIVERABLE");
    private static final Set<String> ROOT_FIELDS = Set.of("contractVersion", "outcome", "requirements",
            "scenarios", "deliverables", "reviews", "stages", "gapCodes");
    private static final Set<String> REQUIREMENT_FIELDS = Set.of("key", "statement");
    private static final Set<String> SCENARIO_FIELDS = Set.of("key", "title", "precondition", "action",
            "observableResult", "invariant", "requirementRefs");
    private static final Set<String> DELIVERABLE_FIELDS = Set.of("key", "kind", "target", "description",
            "requirementRefs");
    private static final Set<String> REVIEW_FIELDS = Set.of("key", "title", "criteria", "humanOnlyReason",
            "requirementRefs");
    private static final Set<String> STAGE_FIELDS = Set.of("key", "title", "objective", "includes", "dependencies");
    private static final Map<String, String> FORBIDDEN_FIELDS = forbiddenFields();
    private final ObjectMapper json;

    PackageDesignCandidateCodec(ObjectMapper json) { this.json = json; }

    Decoded decode(String candidateJson, int stageLimit) {
        JsonNode root;
        try { root = json.readTree(candidateJson); }
        catch (JacksonException invalid) {
            return Decoded.failed(problem("PACKAGE_DESIGN_CONTRACT_INVALID", "/candidate",
                    "候选必须是 PACKAGE_DESIGN_V1 JSON object", List.of(), MECHANICAL, true));
        }
        if (root == null || !root.isObject()) {
            return Decoded.failed(problem("PACKAGE_DESIGN_CONTRACT_INVALID", "/candidate",
                    "候选必须是 PACKAGE_DESIGN_V1 JSON object", List.of(), MECHANICAL, true));
        }
        PackageDesignCompilation.Problem boundary = securityBoundary(root);
        if (boundary != null) return Decoded.failed(boundary);
        if (!closedShape(root)) {
            return Decoded.failed(problem("PACKAGE_DESIGN_CONTRACT_INVALID", "/candidate",
                    "候选字段不符合 PACKAGE_DESIGN_V1 闭集合同", List.copyOf(ROOT_FIELDS), MECHANICAL, true));
        }
        PackageDesignCandidateDocument candidate;
        try { candidate = json.treeToValue(root, PackageDesignCandidateDocument.class); }
        catch (JacksonException invalid) {
            return Decoded.failed(problem("PACKAGE_DESIGN_CONTRACT_INVALID", "/candidate",
                    "候选字段类型不符合 PACKAGE_DESIGN_V1 合同", List.of(), MECHANICAL, true));
        }
        return validate(normalize(candidate), stageLimit);
    }

    private boolean closedShape(JsonNode root) {
        if (unknownFields(root, ROOT_FIELDS)) return false;
        return !unknownArrayFields(root.path("requirements"), REQUIREMENT_FIELDS)
                && !unknownArrayFields(root.path("scenarios"), SCENARIO_FIELDS)
                && !unknownArrayFields(root.path("deliverables"), DELIVERABLE_FIELDS)
                && !unknownArrayFields(root.path("reviews"), REVIEW_FIELDS)
                && !unknownArrayFields(root.path("stages"), STAGE_FIELDS);
    }

    private boolean unknownArrayFields(JsonNode array, Set<String> allowed) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (item.isObject() && unknownFields(item, allowed)) return true;
        return false;
    }

    private boolean unknownFields(JsonNode object, Set<String> allowed) {
        return object.properties().stream().map(Map.Entry::getKey).anyMatch(field -> !allowed.contains(field));
    }

    String canonicalJson(PackageDesignCandidateDocument candidate) {
        try { return json.writeValueAsString(candidate); }
        catch (JacksonException impossible) { throw new IllegalStateException("Unable to encode package design", impossible); }
    }

    private Decoded validate(PackageDesignCandidateDocument value, int stageLimit) {
        Problems problems = new Problems();
        if (value == null) return Decoded.failed(problem("PACKAGE_DESIGN_CONTRACT_INVALID", "/candidate",
                "候选必须是 PACKAGE_DESIGN_V1 JSON object", List.of(), MECHANICAL, true));
        require(CONTRACT_VERSION.equals(value.contractVersion()), problems, "PACKAGE_DESIGN_CONTRACT_VERSION_INVALID",
                "/contractVersion", "候选合同版本不受支持", List.of(CONTRACT_VERSION));
        require(OUTCOMES.contains(value.outcome()), problems, "PACKAGE_DESIGN_OUTCOME_INVALID", "/outcome",
                "候选结果必须使用闭集值", List.copyOf(OUTCOMES));
        requiredCollections(value, problems);
        if (!problems.empty()) return new Decoded(value, problems.values());
        validateKeysAndFields(value, problems);
        validateGapCodes(value, problems);
        if ("READY".equals(value.outcome())) validateReady(value, stageLimit, problems);
        if (!problems.empty()) return new Decoded(value, problems.values());
        return new Decoded(topological(value), List.of());
    }

    private void requiredCollections(PackageDesignCandidateDocument value, Problems problems) {
        require(value.requirements() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED", "/requirements",
                "候选缺少完整替换所需集合", List.of());
        require(value.scenarios() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED", "/scenarios",
                "候选缺少完整替换所需集合", List.of());
        require(value.deliverables() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED", "/deliverables",
                "候选缺少完整替换所需集合", List.of());
        require(value.reviews() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED", "/reviews",
                "候选缺少完整替换所需集合", List.of());
        require(value.stages() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED", "/stages",
                "候选缺少完整替换所需集合", List.of());
        require(value.gapCodes() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED", "/gapCodes",
                "候选缺少完整替换所需集合", List.of());
    }

    private void validateKeysAndFields(PackageDesignCandidateDocument value, Problems problems) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < value.requirements().size(); i++) {
            var item = value.requirements().get(i);
            item(item, item == null ? null : item.key(), item == null ? null : item.statement(),
                    "statement", "/requirements/" + i, keys, problems);
        }
        for (int i = 0; i < value.scenarios().size(); i++) {
            var item = value.scenarios().get(i);
            item(item, item == null ? null : item.key(), item == null ? null : item.title(),
                    "title", "/scenarios/" + i, keys, problems);
            if (item != null) {
                requiredText(item.precondition(), "/scenarios/" + i + "/precondition", "前置条件", problems);
                requiredText(item.action(), "/scenarios/" + i + "/action", "操作", problems);
                requiredText(item.observableResult(), "/scenarios/" + i + "/observableResult",
                        "可观察结果", problems);
                requiredText(item.invariant(), "/scenarios/" + i + "/invariant", "不变量", problems);
            }
        }
        for (int i = 0; i < value.deliverables().size(); i++) {
            var item = value.deliverables().get(i);
            item(item, item == null ? null : item.key(), item == null ? null : item.target(),
                    "target", "/deliverables/" + i, keys, problems);
            if (item != null) require(DELIVERABLE_KINDS.contains(item.kind()), problems,
                    "PACKAGE_DESIGN_DELIVERABLE_KIND_INVALID", "/deliverables/" + i + "/kind",
                    "交付类型必须使用闭集值", List.copyOf(DELIVERABLE_KINDS));
        }
        for (int i = 0; i < value.reviews().size(); i++) {
            var item = value.reviews().get(i);
            item(item, item == null ? null : item.key(), item == null ? null : item.title(),
                    "title", "/reviews/" + i, keys, problems);
            if (item != null) {
                requiredText(item.criteria(), "/reviews/" + i + "/criteria", "人工判断标准", problems);
                requiredText(item.humanOnlyReason(), "/reviews/" + i + "/humanOnlyReason",
                        "仅人工原因", problems);
            }
        }
        for (int i = 0; i < value.stages().size(); i++) {
            var item = value.stages().get(i);
            item(item, item == null ? null : item.key(), item == null ? null : item.title(),
                    "title", "/stages/" + i, keys, problems);
            if (item != null) {
                requiredText(item.objective(), "/stages/" + i + "/objective", "阶段目标", problems);
                require(item.includes() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED",
                        "/stages/" + i + "/includes", "阶段包含项必须是数组", List.of());
                require(item.dependencies() != null, problems, "PACKAGE_DESIGN_FIELD_REQUIRED",
                        "/stages/" + i + "/dependencies", "阶段依赖必须是数组", List.of());
            }
        }
    }

    private void validateGapCodes(PackageDesignCandidateDocument value, Problems problems) {
        Set<String> allowed = java.util.Arrays.stream(DesignGapCode.values()).map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (int i = 0; i < value.gapCodes().size(); i++) {
            require(allowed.contains(value.gapCodes().get(i)), problems, "PACKAGE_DESIGN_GAP_CODE_INVALID",
                    "/gapCodes/" + i, "设计缺口必须使用服务端闭集值", List.copyOf(allowed));
        }
        if ("READY".equals(value.outcome())) require(value.gapCodes().isEmpty(), problems,
                "PACKAGE_DESIGN_READY_GAPS_INVALID", "/gapCodes", "READY 候选不能同时声明设计缺口", List.of());
        if ("NEEDS_INPUT".equals(value.outcome())) require(!value.gapCodes().isEmpty(), problems,
                "PACKAGE_DESIGN_NEEDS_INPUT_GAP_REQUIRED", "/gapCodes",
                "NEEDS_INPUT 候选必须声明至少一个闭集设计缺口", List.copyOf(allowed));
        if ("NEEDS_INPUT".equals(value.outcome())) {
            for (int index = 0; index < value.gapCodes().size(); index++) {
                String code = value.gapCodes().get(index);
                if (!allowed.contains(code)) continue;
                boolean correctable = candidateCorrectableGap(code);
                String pointer = "/gapCodes/" + index;
                String detail = correctable
                        ? code + " 属于候选自身可修正问题，不能作为人工输入出口；"
                                + "请修正完整候选并重新提交 READY"
                        : humanGapDetail(code);
                problems.add(new PackageDesignCompilation.Problem(code, pointer, detail, List.copyOf(allowed),
                        correctable ? CORRECTABLE : HUMAN_REQUIRED, false,
                        correctable ? "outcome READY with gapCodes [] after the candidate-owned issue is repaired"
                                : "a genuine user-owned design decision identified by " + code,
                        "outcome NEEDS_INPUT with " + pointer + "=\"" + code + "\"",
                        correctable
                                ? "Set /outcome to READY, set /gapCodes to [], repair the fields named by "
                                        + code + ", and resubmit the complete candidate"
                                : "Stop and ask the user for the missing design information described by " + code));
            }
        }
    }

    private static boolean candidateCorrectableGap(String code) {
        return Set.of("AMBIGUOUS_ACCEPTANCE_INTENT", "VERIFICATION_CAPABILITY_UNAVAILABLE",
                "REQUIRED_MUTATION_PATH_UNASSIGNED").contains(code);
    }

    private static String humanGapDetail(String code) {
        return switch (code) {
            case "MISSING_OBSERVABLE_OUTCOME" ->
                    "用户尚未给出可观察结果，候选无法凭空决定成功时应观察到什么";
            case "MISSING_EXCEPTION_SEMANTICS" ->
                    "用户尚未给出异常语义，候选无法凭空决定失败、回退或异常传播行为";
            case "MISSING_SCOPE" -> "用户尚未给出交付范围，候选无法确定要修改或交付的对象";
            case "MISSING_ACCEPTANCE_INTENT" -> "用户尚未给出可验证的验收意图";
            case "LARGE_TASK_MODE_REQUIRED" -> "当前语义只能通过用户确认切换为大型任务模式继续";
            case "REQUIRED_MUTATION_PATH_FORBIDDEN" ->
                    "必需修改路径与冻结禁止范围冲突，必须由用户调整范围或需求";
            default -> "候选明确声明存在需要用户决定的设计缺口：" + code;
        };
    }

    private void validateReady(PackageDesignCandidateDocument value, int stageLimit, Problems problems) {
        semantic(!value.requirements().isEmpty(), problems, "PACKAGE_DESIGN_REQUIREMENTS_REQUIRED", "/requirements",
                "READY 候选必须包含需求");
        semantic(!value.scenarios().isEmpty(), problems, "PACKAGE_DESIGN_SCENARIOS_REQUIRED", "/scenarios",
                "READY 候选必须包含可观察场景");
        semantic(!value.deliverables().isEmpty(), problems, "PACKAGE_DESIGN_DELIVERABLES_REQUIRED", "/deliverables",
                "READY 候选必须包含交付项");
        require(value.stages().size() >= 1 && value.stages().size() <= stageLimit, problems,
                "PACKAGE_DESIGN_STAGE_COUNT_INVALID", "/stages", "阶段数量超出当前工作包闭集",
                java.util.stream.IntStream.rangeClosed(1, stageLimit).mapToObj(String::valueOf).toList());
        if (!problems.empty()) return;
        Map<String, PackageDesignCandidateDocument.Requirement> requirements = indexRequirements(value);
        Map<String, String> facts = factTitles(value);
        Set<String> coveredRequirements = new LinkedHashSet<>();
        validateRequirementRefs(value, requirements.keySet(), coveredRequirements, problems);
        Set<String> included = new LinkedHashSet<>();
        Set<String> acceptance = value.scenarios().stream().map(item -> key(item.key()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        acceptance.addAll(value.reviews().stream().map(item -> key(item.key())).toList());
        Set<String> stageKeys = value.stages().stream().map(item -> key(item.key()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (int i = 0; i < value.stages().size(); i++) {
            var stage = value.stages().get(i);
            for (int item = 0; item < stage.includes().size(); item++) {
                String ref = stage.includes().get(item);
                require(facts.containsKey(key(ref)), problems, "PACKAGE_DESIGN_REFERENCE_INVALID",
                        "/stages/" + i + "/includes/" + item,
                        "阶段包含项“" + ref + "”不是候选中已声明的场景、交付项或评审 key",
                        List.copyOf(facts.keySet()));
                included.add(key(ref));
            }
            semantic(stage.includes().stream().map(PackageDesignCandidateCodec::key).anyMatch(acceptance::contains),
                    problems, "PACKAGE_DESIGN_STAGE_ACCEPTANCE_REQUIRED", "/stages/" + i + "/includes",
                    "每个阶段必须包含至少一个验收场景或人工评审");
            for (int item = 0; item < stage.dependencies().size(); item++) {
                String dependency = stage.dependencies().get(item);
                require(stageKeys.contains(key(dependency)), problems,
                        "PACKAGE_DESIGN_REFERENCE_INVALID", "/stages/" + i + "/dependencies/" + item,
                        "阶段依赖“" + dependency + "”不是候选中已声明的 stage key",
                        List.copyOf(stageKeys));
            }
        }
        if (!problems.has(MECHANICAL)) {
            for (int index = 0; index < value.requirements().size(); index++) {
                var requirement = value.requirements().get(index);
                semantic(coveredRequirements.contains(key(requirement.key())), problems,
                        "PACKAGE_DESIGN_COVERAGE_INCOMPLETE", "/requirements/" + index + "/key",
                        "需求 " + requirement.key() + " 没有被任何 scenario、deliverable 或 review 引用");
            }
            addUnassignedFactProblems(value, included, problems);
            require(acyclic(value.stages()), problems, "PACKAGE_DESIGN_STAGE_DEPENDENCY_INVALID", "/stages",
                    "阶段依赖必须形成有向无环图", List.of());
        }
    }

    private void validateRequirementRefs(PackageDesignCandidateDocument value, Set<String> requirementKeys,
                                         Set<String> covered, Problems problems) {
        for (int index = 0; index < value.scenarios().size(); index++) validateRequirementRefs(
                value.scenarios().get(index).requirementRefs(), "/scenarios/" + index + "/requirementRefs",
                requirementKeys, covered, problems);
        for (int index = 0; index < value.deliverables().size(); index++) validateRequirementRefs(
                value.deliverables().get(index).requirementRefs(), "/deliverables/" + index + "/requirementRefs",
                requirementKeys, covered, problems);
        for (int index = 0; index < value.reviews().size(); index++) validateRequirementRefs(
                value.reviews().get(index).requirementRefs(), "/reviews/" + index + "/requirementRefs",
                requirementKeys, covered, problems);
    }

    private void validateRequirementRefs(List<String> values, String pointer, Set<String> requirementKeys,
                                         Set<String> covered, Problems problems) {
        if (values == null) {
            problems.add(problem("PACKAGE_DESIGN_FIELD_REQUIRED", pointer,
                    "候选引用集合必须是数组", List.of(), MECHANICAL, true));
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            String ref = values.get(index);
            require(requirementKeys.contains(key(ref)), problems, "PACKAGE_DESIGN_REFERENCE_INVALID",
                    pointer + "/" + index, "需求引用“" + ref + "”不是已声明的 requirement key",
                    List.copyOf(requirementKeys));
            if (requirementKeys.contains(key(ref))) covered.add(key(ref));
        }
    }

    private void addUnassignedFactProblems(PackageDesignCandidateDocument value, Set<String> included,
                                           Problems problems) {
        for (int index = 0; index < value.scenarios().size(); index++) {
            var fact = value.scenarios().get(index);
            semantic(included.contains(key(fact.key())), problems, "PACKAGE_DESIGN_COVERAGE_INCOMPLETE",
                    "/scenarios/" + index + "/key",
                    "场景 " + fact.key() + " 没有出现在任何 stages[].includes 中");
        }
        for (int index = 0; index < value.deliverables().size(); index++) {
            var fact = value.deliverables().get(index);
            semantic(included.contains(key(fact.key())), problems, "PACKAGE_DESIGN_COVERAGE_INCOMPLETE",
                    "/deliverables/" + index + "/key",
                    "交付项 " + fact.key() + " 没有出现在任何 stages[].includes 中");
        }
        for (int index = 0; index < value.reviews().size(); index++) {
            var fact = value.reviews().get(index);
            semantic(included.contains(key(fact.key())), problems, "PACKAGE_DESIGN_COVERAGE_INCOMPLETE",
                    "/reviews/" + index + "/key",
                    "评审 " + fact.key() + " 没有出现在任何 stages[].includes 中");
        }
    }

    private PackageDesignCandidateDocument topological(PackageDesignCandidateDocument value) {
        if (!"READY".equals(value.outcome())) return value;
        Map<String, PackageDesignCandidateDocument.Stage> remaining = new LinkedHashMap<>();
        value.stages().forEach(stage -> remaining.put(key(stage.key()), stage));
        List<PackageDesignCandidateDocument.Stage> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<String> ready = remaining.entrySet().stream().filter(entry -> entry.getValue().dependencies().stream()
                    .map(PackageDesignCandidateCodec::key).allMatch(dependency -> ordered.stream()
                            .map(stage -> key(stage.key())).anyMatch(dependency::equals)))
                    .map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) return value;
            ready.forEach(key -> ordered.add(remaining.remove(key)));
        }
        return new PackageDesignCandidateDocument(value.contractVersion(), value.outcome(), value.requirements(),
                value.scenarios(), value.deliverables(), value.reviews(), List.copyOf(ordered), value.gapCodes());
    }

    private boolean acyclic(List<PackageDesignCandidateDocument.Stage> stages) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        stages.forEach(stage -> indegree.put(key(stage.key()), 0));
        for (var stage : stages) for (String dependency : stage.dependencies()) {
            indegree.computeIfPresent(key(stage.key()), (ignored, count) -> count + 1);
            outgoing.computeIfAbsent(key(dependency), ignored -> new ArrayList<>()).add(key(stage.key()));
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((key, count) -> { if (count == 0) queue.add(key); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst(); visited++;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                int count = indegree.computeIfPresent(next, (ignored, value) -> value - 1);
                if (count == 0) queue.add(next);
            }
        }
        return visited == stages.size();
    }

    private PackageDesignCandidateDocument normalize(PackageDesignCandidateDocument value) {
        if (value == null) return null;
        return new PackageDesignCandidateDocument(upper(value.contractVersion()), upper(value.outcome()),
                map(value.requirements(), item -> item == null ? null : new PackageDesignCandidateDocument.Requirement(
                        clean(item.key()), clean(item.statement()))),
                map(value.scenarios(), item -> item == null ? null : new PackageDesignCandidateDocument.Scenario(
                        clean(item.key()), clean(item.title()), clean(item.precondition()), clean(item.action()),
                        clean(item.observableResult()), clean(item.invariant()), strings(item.requirementRefs()))),
                map(value.deliverables(), item -> item == null ? null : new PackageDesignCandidateDocument.Deliverable(
                        clean(item.key()), upper(item.kind()), clean(item.target()), clean(item.description()),
                        strings(item.requirementRefs()))),
                map(value.reviews(), item -> item == null ? null : new PackageDesignCandidateDocument.Review(
                        clean(item.key()), clean(item.title()), clean(item.criteria()), clean(item.humanOnlyReason()),
                        strings(item.requirementRefs()))),
                map(value.stages(), item -> item == null ? null : new PackageDesignCandidateDocument.Stage(
                        clean(item.key()), clean(item.title()), clean(item.objective()), strings(item.includes()),
                        strings(item.dependencies()))),
                value.gapCodes() == null ? null : value.gapCodes().stream().map(PackageDesignCandidateCodec::upper).toList());
    }

    private PackageDesignCompilation.Problem securityBoundary(JsonNode root) {
        ArrayDeque<JsonNode> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            JsonNode node = queue.removeFirst();
            if (node.isObject()) for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String canonical = FORBIDDEN_FIELDS.get(key(entry.getKey()));
                if (canonical != null) return problem("PACKAGE_DESIGN_SECURITY_BOUNDARY", "/" + canonical,
                        "候选不得提供服务端拥有的执行、权限、路径白名单、验证器或稳定 ID", List.of(), SECURITY, false);
                queue.add(entry.getValue());
            } else if (node.isArray()) node.forEach(queue::add);
        }
        return null;
    }

    private static Map<String, String> forbiddenFields() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : List.of("id", "stableId", "workPackageId", "stageId", "requirementId", "criterionId",
                "criterionIds", "command", "commands", "argv", "shell", "testCommand", "testTargets",
                "verifier", "verifiers", "allowedPaths", "forbiddenPaths", "responsiblePaths", "permissions",
                "permission", "implementationKind", "verificationRuntime")) values.put(key(field), field);
        return Map.copyOf(values);
    }

    private static Map<String, PackageDesignCandidateDocument.Requirement> indexRequirements(
            PackageDesignCandidateDocument value) {
        Map<String, PackageDesignCandidateDocument.Requirement> result = new LinkedHashMap<>();
        value.requirements().forEach(item -> result.put(key(item.key()), item)); return result;
    }

    private static Map<String, String> factTitles(PackageDesignCandidateDocument value) {
        Map<String, String> result = new LinkedHashMap<>();
        value.scenarios().forEach(item -> result.put(key(item.key()), item.title()));
        value.deliverables().forEach(item -> result.put(key(item.key()), item.target()));
        value.reviews().forEach(item -> result.put(key(item.key()), item.title()));
        return result;
    }

    private static void item(Object item, String key, String text, String textField,
                             String pointer, Set<String> keys, Problems problems) {
        if (item == null) {
            require(false, problems, "PACKAGE_DESIGN_ITEM_REQUIRED", pointer, "候选数组项必须是对象", List.of());
            return;
        }
        require(nonblank(key), problems, "PACKAGE_DESIGN_FIELD_REQUIRED", pointer + "/key",
                "候选条目 key 不能为空", List.of());
        require(nonblank(text), problems, "PACKAGE_DESIGN_FIELD_REQUIRED", pointer + "/" + textField,
                "候选条目 " + textField + " 不能为空", List.of());
        if (nonblank(key)) require(keys.add(key(key)), problems, "PACKAGE_DESIGN_KEY_DUPLICATE",
                pointer + "/key", "候选 key 必须在完整替换文档内唯一", List.of());
    }

    private static void requiredText(String value, String pointer, String label, Problems problems) {
        require(nonblank(value), problems, "PACKAGE_DESIGN_FIELD_REQUIRED", pointer,
                label + "不能为空", List.of());
    }

    private static void require(boolean condition, Problems problems, String code, String pointer,
                                String detail, List<String> allowed) {
        if (!condition) problems.add(problem(code, pointer, detail, allowed, MECHANICAL, true));
    }

    private static void semantic(boolean condition, Problems problems, String code, String pointer, String detail) {
        if (!condition) problems.add(problem(code, pointer, detail, List.of(), CORRECTABLE, false));
    }

    private static PackageDesignCompilation.Problem problem(String code, String pointer, String detail,
                                                             List<String> allowed,
                                                             PackageDesignCompilation.ProblemClass type,
                                                             boolean fallback) {
        return new PackageDesignCompilation.Problem(code, pointer, detail, allowed, type, fallback);
    }

    private static String key(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }
    private static String clean(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .replaceAll("\\s+", " ");
    }
    private static String upper(String value) { return clean(value).toUpperCase(Locale.ROOT); }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static List<String> strings(List<String> values) {
        return values == null ? null : values.stream().map(PackageDesignCandidateCodec::clean).toList();
    }
    private static <T, R> List<R> map(List<T> values, java.util.function.Function<T, R> mapper) {
        return values == null ? null : values.stream().map(mapper).toList();
    }

    record Decoded(PackageDesignCandidateDocument candidate, List<PackageDesignCompilation.Problem> problems) {
        static Decoded failed(PackageDesignCompilation.Problem problem) { return new Decoded(null, List.of(problem)); }
        boolean valid() { return problems.isEmpty(); }
    }

    private static final class Problems {
        private final List<PackageDesignCompilation.Problem> values = new ArrayList<>();
        void add(PackageDesignCompilation.Problem problem) { if (values.size() < MAX_PROBLEMS) values.add(problem); }
        boolean empty() { return values.isEmpty(); }
        boolean has(PackageDesignCompilation.ProblemClass type) {
            return values.stream().anyMatch(problem -> problem.problemClass() == type);
        }
        List<PackageDesignCompilation.Problem> values() { return List.copyOf(values); }
    }
}
