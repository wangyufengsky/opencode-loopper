package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pure deterministic compiler for one compact {@code DECOMPOSITION_PLAN_V2} candidate. */
final class DesignerDecompositionCandidateCompiler {
    private static final int MAX_PROBLEMS = 64;
    private static final int MAX_GOAL_CHARS = 12_000;
    private static final Set<String> OUTCOMES = Set.of("READY", "NEEDS_INPUT", "MULTI_TASK_REQUIRED");
    private static final Set<String> TARGET_TYPES = Set.of("GLOBAL_CONSTRAINT", "WORK_PACKAGE");
    private static final Set<String> MECHANICAL_TITLES = Set.of(
            "前端", "后端", "数据库", "测试", "frontend", "backend", "database", "tests");

    private final ObjectMapper json;

    DesignerDecompositionCandidateCompiler(ObjectMapper json) {
        this.json = json;
    }

    Compilation compile(String candidateJson, DesignRequirementRevisionRow revision) {
        JsonNode root;
        try {
            root = json.readTree(candidateJson);
        } catch (JacksonException invalid) {
            return rejected(new MachineCandidateSubmission.Problem(
                    "CANDIDATE_JSON_INVALID", "", "Candidate must be one complete JSON object"));
        } catch (RuntimeException invalid) {
            return rejected(new MachineCandidateSubmission.Problem(
                    "CANDIDATE_JSON_INVALID", "", "Candidate must be one complete JSON object"));
        }
        if (root == null || !root.isObject()) {
            return rejected(new MachineCandidateSubmission.Problem(
                    "CANDIDATE_ROOT_INVALID", "", "Candidate root must be a JSON object"));
        }

        ProblemCollector problems = new ProblemCollector();
        String outcome = string(root.get("outcome"));
        if (blank(outcome) || !OUTCOMES.contains(outcome.trim().toUpperCase())) {
            problems.add("DECOMPOSER_PLAN_OUTCOME_INVALID", "/outcome",
                    "Outcome must be READY, NEEDS_INPUT, or MULTI_TASK_REQUIRED", List.copyOf(OUTCOMES));
        } else {
            outcome = outcome.trim().toUpperCase();
        }

        List<JsonNode> constraintNodes = array(root, "globalConstraints", problems);
        List<JsonNode> packageNodes = array(root, "workPackages", problems);
        List<JsonNode> coverageNodes = array(root, "coverage", problems);
        List<JsonNode> gapNodes = array(root, "designGaps", problems);
        List<RequirementSegment> requirements = requirements(revision);

        if ("NEEDS_INPUT".equals(outcome)) {
            List<DesignGap> gaps = designGaps(gapNodes, problems);
            if (gaps.isEmpty()) {
                problems.add("DECOMPOSITION_GAPS_REQUIRED", "/designGaps",
                        "NEEDS_INPUT requires at least one concrete closed-set design gap");
            }
            List<MachineCandidateSubmission.Problem> boundaries = new ArrayList<>();
            for (int index = 0; index < gaps.size(); index++) {
                DesignGap gap = gaps.get(index);
                String pointer = "/designGaps/" + index;
                if (candidateCorrectableGap(gap.code())) {
                    problems.add(new MachineCandidateSubmission.Problem(
                            gap.code().name(), pointer + "/code",
                            gap.code().name() + " 是候选可自行修正的问题，不能作为人工输入出口；具体声明："
                                    + gap.detail(),
                            java.util.Arrays.stream(DesignGapCode.values()).map(Enum::name).toList(),
                            "candidate", MachineCandidateSubmission.ProblemCategory.SEMANTIC,
                            "outcome READY after repairing the candidate-owned issue",
                            "outcome NEEDS_INPUT with " + pointer + ".code=\"" + gap.code().name() + "\"",
                            "Repair the field or relation described by " + pointer + "/detail, set /outcome to READY, "
                                    + "clear /designGaps, and resubmit the complete candidate"));
                } else {
                    boundaries.add(new MachineCandidateSubmission.Problem(
                            "DECOMPOSITION_NEEDS_INPUT", pointer + "/detail",
                            "设计缺口 " + gap.code().name() + " 需要用户决定：" + gap.detail(),
                            java.util.Arrays.stream(DesignGapCode.values()).map(Enum::name).toList(),
                            "candidate", MachineCandidateSubmission.ProblemCategory.SEMANTIC,
                            "a concrete user-owned decision for " + gap.code().name(),
                            gap.code().name() + ": " + gap.detail(),
                            "Stop candidate correction and ask the user exactly for the decision described at "
                                    + pointer + "/detail"));
                }
            }
            DecompositionPlanEnvelope plan = new DecompositionPlanEnvelope(
                    "NEEDS_INPUT", nullableString(root.get("normalizedGoal")),
                    List.of(), List.of(), List.of(), List.of(), gaps,
                    nullableString(root.get("reason"))).normalized();
            return finish(plan, problems, boundaries);
        }
        if ("MULTI_TASK_REQUIRED".equals(outcome)) {
            String reason = nullableString(root.get("reason"));
            if (blank(reason)) {
                problems.add("MULTI_TASK_REASON_REQUIRED", "/reason",
                        "MULTI_TASK_REQUIRED requires a concrete boundary reason");
            }
            DecompositionPlanEnvelope plan = new DecompositionPlanEnvelope("MULTI_TASK_REQUIRED",
                    nullableString(root.get("normalizedGoal")), List.of(), List.of(), List.of(), List.of(),
                    List.of(), reason).normalized();
            List<MachineCandidateSubmission.Problem> boundaries = blank(reason) ? List.of()
                    : List.of(new MachineCandidateSubmission.Problem(
                            "DECOMPOSITION_MULTI_TASK_REQUIRED", "/reason",
                            "Candidate identified a boundary that requires multiple independent tasks"));
            return finish(plan, problems, boundaries);
        }
        if (!"READY".equals(outcome)) return new Compilation(null, problems.list(), List.of());

        String goal = nullableString(root.get("normalizedGoal"));
        if (blank(goal)) {
            problems.add("DECOMPOSITION_GOAL_REQUIRED", "/normalizedGoal",
                    "READY requires a normalized observable goal");
        } else if (goal.length() > MAX_GOAL_CHARS) {
            problems.add("DECOMPOSITION_GOAL_TOO_LONG", "/normalizedGoal",
                    "Normalized goal exceeds 12000 characters");
        }

        int packageCount = packageNodes.size();
        String status = null;
        if (packageCount < 1 || packageCount > 6) {
            problems.add("WORK_PACKAGE_COUNT_INVALID", "/workPackages",
                    "READY decomposition must contain 1-6 semantic work packages");
        } else {
            status = AiSemanticContractCompiler.decompositionStatus(outcome, packageCount);
        }

        List<GlobalConstraint> constraints = constraints(constraintNodes, problems);
        PackageCompilation packages = packages(packageNodes, problems);
        CoverageCompilation coverage = coverage(coverageNodes, requirements, constraints.size(),
                packages.packages().size(), problems);
        List<GlobalConstraint> referencedConstraints = referenceConstraints(constraints, coverage.mappings(),
                requirements);
        List<DecomposedWorkPackage> referencedPackages = referencePackages(packages.packages(),
                coverage.mappings(), requirements);

        DecompositionPlanEnvelope plan = new DecompositionPlanEnvelope(status, goal, referencedConstraints,
                referencedPackages, coverage.mappings(), packages.dependencies(), List.of(), null).normalized();
        return finish(plan, problems, List.of());
    }

    private List<GlobalConstraint> constraints(List<JsonNode> nodes, ProblemCollector problems) {
        List<GlobalConstraint> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String pointer = "/globalConstraints/" + index;
            String text = null;
            if (node != null && node.isTextual()) {
                text = node.asText();
            } else if (node != null && node.isObject()) {
                text = string(node.get("text"));
                pointer += "/text";
            }
            if (blank(text)) {
                problems.add("GLOBAL_CONSTRAINT_INVALID", node != null && node.isObject() ? pointer
                        : "/globalConstraints/" + index, "Each global constraint needs non-empty text");
            }
            result.add(new GlobalConstraint(text, List.of()));
        }
        return List.copyOf(result);
    }

    private PackageCompilation packages(List<JsonNode> nodes, ProblemCollector problems) {
        List<DecomposedWorkPackage> result = new ArrayList<>();
        List<DependencyEvidence> evidence = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String base = "/workPackages/" + index;
            if (node == null || !node.isObject()) {
                problems.add("WORK_PACKAGE_INCOMPLETE", base, "Work package must be an object");
                result.add(new DecomposedWorkPackage(AiSemanticContractCompiler.workPackageId(index), null, null,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
                titles.add(null);
                continue;
            }
            String title = required(node, "title", base, problems);
            String objective = required(node, "objective", base, problems);
            List<String> scopeIn = strings(node, "scopeIn", base, false, problems);
            List<String> scopeOut = strings(node, "scopeOut", base, false, problems);
            List<String> deliverables = strings(node, "deliverables", base, false, problems);
            List<String> acceptance = strings(node, "acceptanceIntent", base, false, problems);
            if (deliverables.isEmpty()) {
                problems.add("WORK_PACKAGE_DELIVERABLES_REQUIRED", base + "/deliverables",
                        "Work package requires at least one deliverable");
            }
            if (acceptance.isEmpty()) {
                problems.add("WORK_PACKAGE_ACCEPTANCE_REQUIRED", base + "/acceptanceIntent",
                        "Work package requires at least one acceptance intent");
            }
            if (!blank(title) && MECHANICAL_TITLES.contains(title.trim().toLowerCase())) {
                problems.add("MECHANICAL_LAYER_SPLIT_FORBIDDEN", base + "/title",
                        "Work packages must be vertical business capabilities, not technical layers");
            }
            String packageId = AiSemanticContractCompiler.workPackageId(index);
            List<String> dependencies = dependencies(node.get("dependsOn"), index, packageId, titles,
                    base + "/dependsOn", evidence, problems);
            result.add(new DecomposedWorkPackage(packageId, title, objective, scopeIn, scopeOut, dependencies,
                    deliverables, acceptance, List.of()));
            titles.add(title);
        }
        return new PackageCompilation(List.copyOf(result), List.copyOf(evidence));
    }

    private List<String> dependencies(JsonNode node, int packageIndex, String packageId, List<String> titles,
                                      String pointer, List<DependencyEvidence> evidence,
                                      ProblemCollector problems) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            problems.add("WORK_PACKAGE_DEPENDENCY_INVALID", pointer, "dependsOn must be an array");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode dependency = node.get(index);
            String itemPointer = pointer + "/" + index;
            Integer target = null;
            String rationale = null;
            if (dependency != null && dependency.isIntegralNumber()) {
                target = dependency.asInt();
            } else if (dependency != null && dependency.isTextual()) {
                String reference = dependency.asText().trim();
                if (reference.matches("(?i)WP-[1-9][0-9]*")) {
                    target = Integer.parseInt(reference.substring(3)) - 1;
                } else if (reference.matches("[0-9]+")) {
                    target = Integer.parseInt(reference);
                } else {
                    List<Integer> matches = new ArrayList<>();
                    for (int previous = 0; previous < packageIndex; previous++) {
                        if (reference.equals(titles.get(previous))) matches.add(previous);
                    }
                    if (matches.size() == 1) target = matches.getFirst();
                }
            } else if (dependency != null && dependency.isObject()) {
                JsonNode targetNode = dependency.get("packageIndex");
                if (targetNode == null) targetNode = dependency.get("targetIndex");
                if (targetNode != null && targetNode.isIntegralNumber()) target = targetNode.asInt();
                JsonNode reasonNode = dependency.get("rationale");
                if (reasonNode != null && !reasonNode.isNull()) {
                    if (reasonNode.isTextual()) rationale = reasonNode.asText();
                    else problems.add("WORK_PACKAGE_DEPENDENCY_RATIONALE_INVALID", itemPointer + "/rationale",
                            "Dependency rationale must be text");
                }
            }
            if (target == null || target < 0 || target >= packageIndex) {
                problems.add("WORK_PACKAGE_DEPENDENCY_INVALID", itemPointer,
                        "Dependency must identify one earlier work package");
                continue;
            }
            String dependencyId = AiSemanticContractCompiler.workPackageId(target);
            if (result.contains(dependencyId)) {
                problems.add("WORK_PACKAGE_DEPENDENCY_DUPLICATE", itemPointer,
                        "Dependency duplicates an earlier work package dependency");
                continue;
            }
            result.add(dependencyId);
            evidence.add(new DependencyEvidence(packageId, dependencyId,
                    blank(rationale) ? packageId + " consumes the prerequisite delivered by " + dependencyId
                            : rationale.trim()));
        }
        return List.copyOf(result);
    }

    private CoverageCompilation coverage(List<JsonNode> nodes, List<RequirementSegment> requirements,
                                         int constraintCount, int packageCount, ProblemCollector problems) {
        Set<String> validRefs = requirements.stream().map(RequirementSegment::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> mapped = new LinkedHashSet<>();
        Set<String> keys = new HashSet<>();
        List<RequirementCoverageMapping> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String base = "/coverage/" + index;
            if (node == null || !node.isObject()) {
                problems.add("DECOMPOSITION_COVERAGE_MAPPING_INVALID", base,
                        "Coverage mapping must be an object");
                continue;
            }
            String ref = string(node.get("requirementRef"));
            String type = string(node.get("targetType"));
            type = blank(type) ? type : type.trim().toUpperCase();
            JsonNode targetNode = node.get("targetIndex");
            Integer targetIndex = targetNode != null && targetNode.isIntegralNumber() ? targetNode.asInt() : null;
            boolean valid = true;
            if (!validRefs.contains(ref)) {
                problems.add("REQUIREMENT_REFERENCE_INVALID", base + "/requirementRef",
                        "requirementRef must identify one frozen requirement segment");
                valid = false;
            }
            if (!TARGET_TYPES.contains(type)) {
                problems.add("DECOMPOSITION_COVERAGE_TARGET_TYPE_INVALID", base + "/targetType",
                        "Coverage targetType must be GLOBAL_CONSTRAINT or WORK_PACKAGE",
                        List.copyOf(TARGET_TYPES));
                valid = false;
            }
            int limit = "GLOBAL_CONSTRAINT".equals(type) ? constraintCount : packageCount;
            if (targetIndex == null || targetIndex < 0 || targetIndex >= limit) {
                problems.add("DECOMPOSITION_PLAN_COVERAGE_TARGET_INVALID", base + "/targetIndex",
                        "Coverage target index does not identify an existing target");
                valid = false;
            }
            String rationale = nullableString(node.get("rationale"));
            if (node.get("rationale") != null && !node.get("rationale").isNull()
                    && !node.get("rationale").isTextual()) {
                problems.add("DECOMPOSITION_COVERAGE_RATIONALE_INVALID", base + "/rationale",
                        "Coverage rationale must be text");
                valid = false;
            }
            if (!valid) continue;
            String targetId = "GLOBAL_CONSTRAINT".equals(type)
                    ? AiSemanticContractCompiler.globalConstraintId(targetIndex)
                    : AiSemanticContractCompiler.workPackageId(targetIndex);
            String key = ref + ":" + type + ":" + targetId;
            if (!keys.add(key)) {
                problems.add("DECOMPOSITION_COVERAGE_MAPPING_DUPLICATE", base,
                        "Coverage mapping duplicates an earlier mapping");
                continue;
            }
            mapped.add(ref);
            result.add(new RequirementCoverageMapping(ref, type, targetId,
                    blank(rationale) ? "Requirement is owned by " + targetId : rationale.trim()));
        }
        Set<String> missing = new LinkedHashSet<>(validRefs);
        missing.removeAll(mapped);
        if (!missing.isEmpty()) {
            problems.add("DECOMPOSITION_PLAN_COVERAGE_INCOMPLETE", "/coverage",
                    "Every frozen requirement segment needs one valid coverage mapping");
        }
        return new CoverageCompilation(List.copyOf(result));
    }

    private List<GlobalConstraint> referenceConstraints(List<GlobalConstraint> constraints,
                                                        List<RequirementCoverageMapping> mappings,
                                                        List<RequirementSegment> requirements) {
        Map<String, Set<String>> refs = refsByTarget(mappings);
        List<GlobalConstraint> result = new ArrayList<>();
        for (int index = 0; index < constraints.size(); index++) {
            GlobalConstraint item = constraints.get(index);
            result.add(new GlobalConstraint(item.text(), orderedRefs(requirements,
                    refs.getOrDefault("GLOBAL_CONSTRAINT:GC-" + (index + 1), Set.of()))));
        }
        return List.copyOf(result);
    }

    private List<DecomposedWorkPackage> referencePackages(List<DecomposedWorkPackage> packages,
                                                          List<RequirementCoverageMapping> mappings,
                                                          List<RequirementSegment> requirements) {
        Map<String, Set<String>> refs = refsByTarget(mappings);
        List<DecomposedWorkPackage> result = new ArrayList<>();
        for (DecomposedWorkPackage item : packages) {
            result.add(new DecomposedWorkPackage(item.id(), item.title(), item.objective(), item.scopeIn(),
                    item.scopeOut(), item.dependencies(), item.deliverables(), item.acceptanceIntent(),
                    orderedRefs(requirements, refs.getOrDefault("WORK_PACKAGE:" + item.id(), Set.of()))));
        }
        return List.copyOf(result);
    }

    private Map<String, Set<String>> refsByTarget(List<RequirementCoverageMapping> mappings) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (RequirementCoverageMapping mapping : mappings) {
            result.computeIfAbsent(mapping.targetType() + ":" + mapping.targetId(), ignored -> new LinkedHashSet<>())
                    .add(mapping.requirementRef());
        }
        return result;
    }

    private List<String> orderedRefs(List<RequirementSegment> requirements, Set<String> refs) {
        return requirements.stream().map(RequirementSegment::id).filter(refs::contains).toList();
    }

    private List<DesignGap> designGaps(List<JsonNode> nodes, ProblemCollector problems) {
        List<DesignGap> result = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String base = "/designGaps/" + index;
            if (node != null && node.isTextual()) {
                if (blank(node.asText())) {
                    problems.add("DECOMPOSITION_GAP_INVALID", base, "Design gap detail must be concrete");
                } else {
                    result.add(new DesignGap(DesignGapCode.MISSING_SCOPE, bounded(node.asText().trim(), 1_000)));
                }
                continue;
            }
            if (node == null || !node.isObject()) {
                problems.add("DECOMPOSITION_GAP_INVALID", base,
                        "Design gap must be text or an object with closed code and detail");
                continue;
            }
            String code = string(node.get("code"));
            String detail = string(node.get("detail"));
            DesignGapCode parsed = null;
            try {
                parsed = DesignGapCode.valueOf(code == null ? "" : code.trim().toUpperCase());
            } catch (IllegalArgumentException invalid) {
                problems.add("DECOMPOSITION_GAP_CODE_INVALID", base + "/code",
                        "Design gap code is outside the closed set",
                        java.util.Arrays.stream(DesignGapCode.values()).map(Enum::name).toList());
            }
            if (blank(detail)) {
                problems.add("DECOMPOSITION_GAP_DETAIL_REQUIRED", base + "/detail",
                        "Design gap needs a concrete detail");
            }
            if (parsed != null && !blank(detail)) result.add(new DesignGap(parsed, bounded(detail.trim(), 1_000)));
        }
        return List.copyOf(result);
    }

    private String required(JsonNode node, String field, String base, ProblemCollector problems) {
        String value = string(node.get(field));
        if (blank(value)) problems.add("WORK_PACKAGE_FIELD_REQUIRED", base + "/" + field,
                "Work package " + field + " is required");
        return value;
    }

    private List<String> strings(JsonNode parent, String field, String base, boolean required,
                                 ProblemCollector problems) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            if (required) problems.add("CANDIDATE_ARRAY_REQUIRED", base + "/" + field, field + " must be an array");
            return List.of();
        }
        if (!node.isArray()) {
            problems.add("CANDIDATE_ARRAY_INVALID", base + "/" + field, field + " must be an array");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            if (item == null || !item.isTextual() || blank(item.asText())) {
                problems.add("CANDIDATE_ARRAY_ITEM_INVALID", base + "/" + field + "/" + index,
                        field + " entries must be non-empty text");
            } else {
                result.add(item.asText());
            }
        }
        return List.copyOf(result);
    }

    private List<JsonNode> array(JsonNode root, String field, ProblemCollector problems) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            problems.add("CANDIDATE_ARRAY_REQUIRED", "/" + field, field + " must be a JSON array");
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return List.copyOf(result);
    }

    private List<RequirementSegment> requirements(DesignRequirementRevisionRow revision) {
        try {
            List<RequirementSegment> result = json.readValue(
                    revision.requirementSegmentsJson(), new TypeReference<List<RequirementSegment>>() { });
            if (result == null || result.stream().anyMatch(item -> item == null || blank(item.id()))) {
                throw new IllegalStateException("Frozen requirement segments are incomplete");
            }
            return List.copyOf(result);
        } catch (JacksonException invalid) {
            throw new ConflictException("REQUIREMENT_SEGMENTS_INVALID", "Frozen requirement segments are unreadable");
        }
    }

    private Compilation finish(DecompositionPlanEnvelope plan, ProblemCollector problems,
                               List<MachineCandidateSubmission.Problem> boundaryProblems) {
        if (!problems.list().isEmpty()) return new Compilation(null, problems.list(), List.of());
        try {
            return new Compilation(json.writeValueAsString(plan), List.of(), boundaryProblems);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("Unable to serialize canonical decomposition plan", invalid);
        }
    }

    private Compilation rejected(MachineCandidateSubmission.Problem problem) {
        return new Compilation(null, List.of(problem), List.of());
    }

    private static boolean candidateCorrectableGap(DesignGapCode code) {
        return code == DesignGapCode.AMBIGUOUS_ACCEPTANCE_INTENT
                || code == DesignGapCode.VERIFICATION_CAPABILITY_UNAVAILABLE
                || code == DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED;
    }

    private static String string(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String nullableString(JsonNode node) {
        return node == null || node.isNull() ? null : string(node);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String bounded(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String boundedUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + codePointBytes > maxBytes) break;
            bytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    record Compilation(String canonicalJson, List<MachineCandidateSubmission.Problem> problems,
                       List<MachineCandidateSubmission.Problem> boundaryProblems) {
        Compilation {
            problems = List.copyOf(problems);
            boundaryProblems = boundaryProblems == null ? List.of() : List.copyOf(boundaryProblems);
        }

        boolean accepted() {
            return canonicalJson != null && problems.isEmpty();
        }
    }

    private record RequirementSegment(String id, String text) { }
    private record PackageCompilation(List<DecomposedWorkPackage> packages,
                                      List<DependencyEvidence> dependencies) { }
    private record CoverageCompilation(List<RequirementCoverageMapping> mappings) { }

    private static final class ProblemCollector {
        private final List<MachineCandidateSubmission.Problem> problems = new ArrayList<>();

        void add(MachineCandidateSubmission.Problem problem) {
            if (problems.size() < MAX_PROBLEMS) problems.add(problem);
        }

        void add(String code, String pointer, String detail) {
            add(code, pointer, detail, List.of());
        }

        void add(String code, String pointer, String detail, List<String> allowedValues) {
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(new MachineCandidateSubmission.Problem(code,
                        boundedUtf8(pointer == null ? "" : pointer, 240), boundedUtf8(detail, 1_000), allowedValues));
            }
        }

        List<MachineCandidateSubmission.Problem> list() {
            return List.copyOf(problems);
        }
    }
}
