package io.opencode.loopper.service;

import static io.opencode.loopper.service.RollingPackagePlanCompilation.Outcome.ACCEPTED;
import static io.opencode.loopper.service.RollingPackagePlanCompilation.Outcome.NEEDS_INPUT;
import static io.opencode.loopper.service.RollingPackagePlanCompilation.Outcome.REJECTED;
import static io.opencode.loopper.service.RollingPackagePlanCompilation.ProblemClass.MECHANICAL;
import static io.opencode.loopper.service.RollingPackagePlanCompilation.ProblemClass.SECURITY;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict candidate firewall and single normalize/impact authority for rolling package plans. */
public final class DeterministicRollingPackagePlanCompilation implements RollingPackagePlanCompilation {
    private static final int MAX_CANDIDATE_BYTES = 64 * 1024;
    private static final int MAX_PROBLEMS = 16;
    private static final Set<String> ROOT_FIELDS = Set.of("packages");
    private static final Set<String> PACKAGE_FIELDS = Set.of("packageKey", "title", "objective", "replaces",
            "dependencies", "requirementRefs");
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
            "id", "stableid", "stableids", "runid", "packagerunid", "packageid", "sourceid",
            "sourcepackagerunid", "sourcepackagerunids", "taskid", "stageid", "workpackageid",
            "requirementid", "revision", "revisionid", "planrevision", "checkpoint", "checkpointid",
            "path", "paths", "allowedpaths", "forbiddenpaths", "command", "commands", "testcommand",
            "testtargets", "status", "impact", "ordinal", "version", "permission", "permissions", "safety",
            "verifier", "verifiers", "correctionof", "correctionofpackagerunid");

    private final ObjectMapper json;

    public DeterministicRollingPackagePlanCompilation(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Result compileCandidate(Input input, String candidateJson) {
        validateInput(input);
        if (candidateJson == null || utf8(candidateJson) > MAX_CANDIDATE_BYTES) {
            return closed("ROLLING_PACKAGE_CANDIDATE_TOO_LARGE", "/candidate",
                    "候选超过 ROLLING_PACKAGE_PLAN_V1 有界输入限制");
        }
        JsonNode root;
        try {
            root = json.readTree(candidateJson);
        } catch (JacksonException invalid) {
            return rejected(problem("ROLLING_PACKAGE_JSON_INVALID", "/candidate",
                    "候选必须是标准 JSON object", List.of(), MECHANICAL));
        }
        if (root == null || !root.isObject()) {
            return closed("ROLLING_PACKAGE_ROOT_INVALID", "/candidate",
                    "候选根必须是 ROLLING_PACKAGE_PLAN_V1 JSON object");
        }
        if (containsAuthorityField(root)) {
            return closed("ROLLING_PACKAGE_AUTHORITY_FIELD_FORBIDDEN", "/candidate",
                    "候选触及服务端拥有的身份、安全或执行字段");
        }
        Problems problems = new Problems();
        unknownFields(root, ROOT_FIELDS, "/candidate", problems);
        JsonNode packages = root.get("packages");
        if (packages == null) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_REQUIRED", "/packages",
                    "候选缺少必需字段", List.of("packages"), MECHANICAL));
        } else if (!packages.isArray()) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_TYPE_INVALID", "/packages",
                    "packages 必须是数组", List.of(), MECHANICAL));
        } else if (packages.isEmpty() || packages.size() > 6) {
            problems.add(problem("ROLLING_PACKAGE_SIZE_INVALID", "/packages",
                    "候选必须包含 1–6 个工作包", List.of("1", "2", "3", "4", "5", "6"), MECHANICAL));
        }
        if (!problems.empty() || packages == null || !packages.isArray()) return rejected(problems.values());

        List<CandidatePackage> decoded = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) {
            JsonNode item = packages.get(index);
            String pointer = "/packages/" + index;
            if (!item.isObject()) {
                problems.add(problem("ROLLING_PACKAGE_ITEM_INVALID", pointer,
                        "工作包必须是 JSON object", List.of(), MECHANICAL));
                continue;
            }
            unknownFields(item, PACKAGE_FIELDS, pointer, problems);
            CandidatePackage value = decodePackage(item, pointer, problems);
            if (value != null) decoded.add(value);
        }
        if (!problems.empty()) return rejected(problems.values());
        return compileNormalized(input, decoded, true);
    }

    @Override
    public Result compilePlan(Input input, List<RollingPackagePlanService.PlanPackage> packages) {
        validateInput(input);
        Problems problems = new Problems();
        if (packages == null || packages.isEmpty() || packages.size() > 6) {
            problems.add(problem("ROLLING_PACKAGE_SIZE_INVALID", "/packages",
                    "计划必须包含 1–6 个工作包", List.of("1", "2", "3", "4", "5", "6"), MECHANICAL));
            return rejected(problems.values());
        }
        Map<String, String> currentKeyByRun = new LinkedHashMap<>();
        input.currentPackages().forEach(item -> currentKeyByRun.put(item.runId(), item.packageKey()));
        List<CandidatePackage> adapted = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) {
            RollingPackagePlanService.PlanPackage item = packages.get(index);
            String pointer = "/packages/" + index;
            if (item == null) {
                problems.add(problem("ROLLING_PACKAGE_ITEM_INVALID", pointer,
                        "工作包必须存在", List.of(), MECHANICAL));
                continue;
            }
            List<String> replaces = new ArrayList<>();
            for (String runId : sourceIds(item)) {
                String key = currentKeyByRun.get(runId);
                if (key == null) {
                    problems.add(problem("ROLLING_PACKAGE_SOURCE_INVALID", pointer + "/replaces",
                            "替换来源必须引用当前未终态工作包", List.copyOf(currentKeyByRun.values()), MECHANICAL));
                } else if (!replaces.contains(key)) {
                    replaces.add(key);
                }
            }
            String normalizedTitle = normalize(item.title());
            CandidatePackage value = new CandidatePackage(normalize(item.packageKey()), normalizedTitle,
                    item.objective() == null ? normalizedTitle : normalize(item.objective()),
                    List.copyOf(replaces), copy(item.dependencies()),
                    copy(item.requirementRefs()));
            validateFields(value, pointer, problems);
            adapted.add(value);
        }
        if (!problems.empty()) return rejected(problems.values());
        Result compiled = compileNormalized(input, adapted, false);
        if (!compiled.accepted()) return compiled;
        List<RollingPackagePlanService.PlanPackage> authoritative = new ArrayList<>();
        for (int index = 0; index < compiled.planPackages().size(); index++) {
            RollingPackagePlanService.PlanPackage normalized = compiled.planPackages().get(index);
            authoritative.add(new RollingPackagePlanService.PlanPackage(
                    normalized.packageKey(), normalized.title(), normalized.objective(),
                    normalized.sourcePackageRunId(), normalized.sourcePackageRunIds(),
                    packages.get(index).correctionOfPackageRunId(), normalized.dependencies(),
                    normalized.requirementRefs()));
        }
        List<RollingPackagePlanService.PlanPackage> plan = List.copyOf(authoritative);
        return new Result(ACCEPTED, null, plan, write(plan), compiled.impact(),
                compiled.canonicalImpactJson(), List.of());
    }

    private CandidatePackage decodePackage(JsonNode item, String pointer, Problems problems) {
        String packageKey = requiredText(item, "packageKey", pointer, 40, problems);
        String title = requiredText(item, "title", pointer, 240, problems);
        String objective = requiredText(item, "objective", pointer, 2_000, problems);
        List<String> replaces = requiredStrings(item, "replaces", pointer, problems);
        List<String> dependencies = requiredStrings(item, "dependencies", pointer, problems);
        List<String> requirementRefs = requiredStrings(item, "requirementRefs", pointer, problems);
        if (packageKey == null || title == null || objective == null || replaces == null
                || dependencies == null || requirementRefs == null) return null;
        CandidatePackage value = new CandidatePackage(packageKey, title, objective, replaces, dependencies,
                requirementRefs);
        validateFields(value, pointer, problems);
        return value;
    }

    private Result compileNormalized(Input input, List<CandidatePackage> packages, boolean candidateEntry) {
        Problems problems = new Problems();
        Set<String> proposalKeys = new LinkedHashSet<>();
        for (int index = 0; index < packages.size(); index++) {
            CandidatePackage item = packages.get(index);
            if (!proposalKeys.add(item.packageKey())) {
                problems.add(problem("ROLLING_PACKAGE_KEY_DUPLICATE", "/packages/" + index + "/packageKey",
                        "工作包编号必须唯一", List.of(), MECHANICAL));
            }
        }
        Set<String> currentKeys = keys(input.currentPackages().stream().map(CurrentPackage::packageKey).toList());
        Set<String> allowedDependencies = keys(input.frozenPackageKeys());
        Set<String> allowedRequirements = keys(input.allowedRequirementRefs());
        for (int index = 0; index < packages.size(); index++) {
            CandidatePackage item = packages.get(index);
            String pointer = "/packages/" + index;
            validateClosedList(item.replaces(), currentKeys, "ROLLING_PACKAGE_SOURCE_INVALID",
                    pointer + "/replaces", "替换来源必须引用当前未终态工作包", problems);
            validateClosedList(item.requirementRefs(), allowedRequirements,
                    "ROLLING_PACKAGE_REQUIREMENT_REF_INVALID", pointer + "/requirementRefs",
                    "需求引用必须来自冻结需求闭集", problems);
            for (String dependency : item.dependencies()) {
                if (item.packageKey().equals(dependency) || !allowedDependencies.contains(dependency)) {
                    problems.add(problem("ROLLING_PACKAGE_DEPENDENCY_INVALID", pointer + "/dependencies",
                            "依赖必须引用已冻结包或当前提案中的其他包且不能自依赖",
                            List.copyOf(allowedDependencies), MECHANICAL));
                    break;
                }
            }
            allowedDependencies.add(item.packageKey());
        }
        if (!problems.empty()) return rejected(problems.values());

        Map<String, String> runByKey = new LinkedHashMap<>();
        input.currentPackages().forEach(item -> runByKey.put(item.packageKey(), item.runId()));
        List<RollingPackagePlanService.PlanPackage> plan = packages.stream().map(item -> {
            List<String> sources = item.replaces().stream().map(runByKey::get).toList();
            return new RollingPackagePlanService.PlanPackage(item.packageKey(), item.title(), item.objective(),
                    sources.isEmpty() ? null : sources.getFirst(), sources, null,
                    item.dependencies(), item.requirementRefs());
        }).toList();
        Impact impact = impact(input, plan);
        String canonicalCandidate = candidateEntry ? write(new CandidateEnvelope(List.copyOf(packages))) : null;
        return new Result(ACCEPTED, canonicalCandidate, plan, write(plan), impact, write(impact), List.of());
    }

    private Impact impact(Input input, List<RollingPackagePlanService.PlanPackage> proposed) {
        List<String> before = input.currentPackages().stream().map(CurrentPackage::packageKey).toList();
        List<String> after = proposed.stream().map(RollingPackagePlanService.PlanPackage::packageKey).toList();
        List<String> added = after.stream().filter(key -> !before.contains(key)).toList();
        List<String> removed = before.stream().filter(key -> !after.contains(key)).toList();
        List<String> beforeCommon = before.stream().filter(after::contains).toList();
        List<String> afterCommon = after.stream().filter(before::contains).toList();
        List<String> reordered = beforeCommon.equals(afterCommon) ? List.of() : after;

        Map<String, List<String>> beforeDependencies = new LinkedHashMap<>();
        input.currentPackages().forEach(item -> beforeDependencies.put(item.packageKey(), item.dependencies()));
        List<DependencyChange> dependencyChanges = proposed.stream()
                .filter(item -> beforeDependencies.containsKey(item.packageKey()))
                .filter(item -> !beforeDependencies.get(item.packageKey()).equals(item.dependencies()))
                .map(item -> new DependencyChange(item.packageKey(), beforeDependencies.get(item.packageKey()),
                        item.dependencies())).toList();

        Map<String, String> sourceKeys = new LinkedHashMap<>();
        input.currentPackages().forEach(item -> sourceKeys.put(item.runId(), item.packageKey()));
        Map<String, List<String>> splitTargets = new LinkedHashMap<>();
        proposed.forEach(item -> sourceIds(item).forEach(source ->
                splitTargets.computeIfAbsent(source, ignored -> new ArrayList<>()).add(item.packageKey())));
        List<Split> split = splitTargets.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new Split(sourceKeys.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()))
                .toList();
        List<Merge> merged = proposed.stream().filter(item -> sourceIds(item).size() > 1)
                .map(item -> new Merge(item.packageKey(), sourceIds(item).stream()
                        .map(source -> sourceKeys.getOrDefault(source, source)).toList())).toList();
        return new Impact(before, after, added, removed, reordered, dependencyChanges, split, merged);
    }

    private void validateFields(CandidatePackage value, String pointer, Problems problems) {
        if (value.packageKey() == null || !value.packageKey().matches("[A-Za-z0-9_-]{1,40}")) {
            problems.add(problem("ROLLING_PACKAGE_KEY_INVALID", pointer + "/packageKey",
                    "工作包编号必须匹配闭集格式", List.of(), MECHANICAL));
        }
        if (blank(value.title()) || utf8(value.title()) > 240) {
            problems.add(problem("ROLLING_PACKAGE_TITLE_INVALID", pointer + "/title",
                    "工作包标题必须非空且有界", List.of(), MECHANICAL));
        }
        if (blank(value.objective()) || utf8(value.objective()) > 2_000) {
            problems.add(problem("ROLLING_PACKAGE_OBJECTIVE_INVALID", pointer + "/objective",
                    "工作包目标必须非空且有界", List.of(), MECHANICAL));
        }
        unique(value.replaces(), pointer + "/replaces", problems);
        unique(value.dependencies(), pointer + "/dependencies", problems);
        unique(value.requirementRefs(), pointer + "/requirementRefs", problems);
    }

    private String requiredText(JsonNode item, String field, String pointer, int maxBytes, Problems problems) {
        JsonNode value = item.get(field);
        if (value == null) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_REQUIRED", pointer + "/" + field,
                    "工作包缺少必需字段", List.of(field), MECHANICAL));
            return null;
        }
        if (!value.isString()) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_TYPE_INVALID", pointer + "/" + field,
                    "工作包字段类型无效", List.of(), MECHANICAL));
            return null;
        }
        String normalized = normalize(value.stringValue());
        if (blank(normalized) || utf8(normalized) > maxBytes) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_VALUE_INVALID", pointer + "/" + field,
                    "工作包文本字段必须非空且有界", List.of(), MECHANICAL));
        }
        return normalized;
    }

    private List<String> requiredStrings(JsonNode item, String field, String pointer, Problems problems) {
        JsonNode value = item.get(field);
        if (value == null) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_REQUIRED", pointer + "/" + field,
                    "工作包缺少必需字段", List.of(field), MECHANICAL));
            return null;
        }
        if (!value.isArray()) {
            problems.add(problem("ROLLING_PACKAGE_FIELD_TYPE_INVALID", pointer + "/" + field,
                    "工作包集合字段必须是字符串数组", List.of(), MECHANICAL));
            return null;
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode entry = value.get(index);
            if (!entry.isString() || blank(entry.stringValue()) || utf8(entry.stringValue()) > 240) {
                problems.add(problem("ROLLING_PACKAGE_FIELD_VALUE_INVALID",
                        pointer + "/" + field + "/" + index,
                        "集合项必须是非空有界字符串", List.of(), MECHANICAL));
            } else {
                result.add(normalize(entry.stringValue()));
            }
        }
        return List.copyOf(result);
    }

    private void validateClosedList(List<String> actual, Set<String> allowed, String code, String pointer,
                                    String detail, Problems problems) {
        if (actual.stream().anyMatch(value -> !allowed.contains(value))) {
            problems.add(problem(code, pointer, detail, List.copyOf(allowed), MECHANICAL));
        }
    }

    private void unique(List<String> values, String pointer, Problems problems) {
        if (new HashSet<>(values).size() != values.size()) {
            problems.add(problem("ROLLING_PACKAGE_LIST_DUPLICATE", pointer,
                    "集合项不能重复", List.of(), MECHANICAL));
        }
    }

    private void unknownFields(JsonNode object, Set<String> allowed, String pointer, Problems problems) {
        object.properties().stream().map(Map.Entry::getKey).filter(field -> !allowed.contains(field)).forEach(field ->
                problems.add(problem("ROLLING_PACKAGE_FIELD_UNKNOWN", pointer + "/" + field,
                        "候选包含闭集合同外字段", List.copyOf(allowed), MECHANICAL)));
    }

    private boolean containsAuthorityField(JsonNode root) {
        if (root.isObject()) {
            for (Map.Entry<String, JsonNode> property : root.properties()) {
                String field = property.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                if (authorityField(field) || containsAuthorityField(property.getValue())) return true;
            }
        } else if (root.isArray()) {
            for (JsonNode item : root) if (containsAuthorityField(item)) return true;
        }
        return false;
    }

    private boolean authorityField(String field) {
        return AUTHORITY_FIELDS.contains(field) || field.endsWith("id") || field.endsWith("ids")
                || List.of("checkpoint", "path", "command", "testtarget", "status", "impact", "permission",
                "safety", "verifier", "ordinal", "version").stream().anyMatch(field::contains);
    }

    private List<String> sourceIds(RollingPackagePlanService.PlanPackage item) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (item.sourcePackageRunIds() != null) result.addAll(item.sourcePackageRunIds());
        if (item.sourcePackageRunId() != null) result.add(item.sourcePackageRunId());
        return List.copyOf(result);
    }

    private void validateInput(Input input) {
        if (input == null) throw new IllegalArgumentException("input is required");
        Set<String> runIds = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (CurrentPackage item : input.currentPackages()) {
            if (blank(item.runId()) || blank(item.packageKey()) || !runIds.add(item.runId())
                    || !keys.add(item.packageKey())) {
                throw new IllegalArgumentException("current package runId/packageKey must be nonblank and unique");
            }
        }
        if (keys(input.frozenPackageKeys()).size() != input.frozenPackageKeys().size()
                || keys(input.allowedRequirementRefs()).size() != input.allowedRequirementRefs().size()) {
            throw new IllegalArgumentException("frozen package keys and requirement refs must be unique");
        }
    }

    private Set<String> keys(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (blank(value)) throw new IllegalArgumentException("closed-set values must be nonblank");
            result.add(value);
        }
        return result;
    }

    private Result rejected(Problem problem) { return rejected(List.of(problem)); }
    private Result rejected(List<Problem> problems) {
        return new Result(REJECTED, null, List.of(), null, null, null, problems);
    }
    private Result closed(String code, String pointer, String detail) {
        return new Result(NEEDS_INPUT, null, List.of(), null, null, null,
                List.of(problem(code, pointer, detail, List.of(), SECURITY)));
    }
    private Problem problem(String code, String pointer, String detail, List<String> allowed, ProblemClass type) {
        return new Problem(code, pointer, detail, allowed, type);
    }
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException impossible) {
            throw new IllegalStateException("Unable to encode rolling package plan", impossible);
        }
    }

    private static String normalize(String value) { return value == null ? null : value.strip(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int utf8(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static List<String> copy(List<String> value) {
        return value == null ? List.of() : value.stream().map(DeterministicRollingPackagePlanCompilation::normalize).toList();
    }

    private record CandidateEnvelope(List<CandidatePackage> packages) { }
    private record CandidatePackage(String packageKey, String title, String objective, List<String> replaces,
                                    List<String> dependencies, List<String> requirementRefs) { }

    private static final class Problems {
        private final List<Problem> values = new ArrayList<>();
        void add(Problem problem) { if (values.size() < MAX_PROBLEMS) values.add(problem); }
        boolean empty() { return values.isEmpty(); }
        List<Problem> values() { return List.copyOf(values); }
    }
}
