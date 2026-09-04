package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strictly reconstructs frozen launch plans without replacing corrupt persisted facts. */
@Component
final class AcceptanceCandidateInternalLaunchPlanCodec {
    static final String CONTRACT = "ACCEPTANCE_CLOSED_CHOICE_V7";
    static final String PROFILE = "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS";
    private static final Set<String> ROUTE_FIELDS = Set.of(
            "contractVersion", "serverResolved", "compilerRequired", "resolution");
    private static final Set<String> RESOLUTION_FIELDS = Set.of(
            "outcome", "stageCandidates", "factAssignmentCandidates",
            "ambiguousCapabilityFactIndexes", "tiedCapabilityIndexesByFact",
            "optimalTieChoiceSets", "trueCapabilityTieCount");
    private static final Set<String> STAGE_FIELDS = Set.of(
            "stageIndex", "title", "objective", "lockedFactIndexes");
    private static final Set<String> FACT_FIELDS = Set.of("factIndex", "allowedStageIndexes");

    private final ObjectMapper json;
    private final ObjectMapper strictJson;

    AcceptanceCandidateInternalLaunchPlanCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
        this.strictJson = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    }

    OpenCodeClient.SessionCreationPlan decode(AcceptanceCandidateInternalLaunchRow row) {
        try {
            requireRowIdentity(row);
            List<OpenCodeClient.SessionPermissionRule> permissionPolicy = permissionPolicy(
                    row.permissionPolicyJson());
            if (actualToolName(permissionPolicy, row.internalMcpServer(), true) == null) throw invalid();
            OpenCodeClient.OpenCodeModel model = model(row.modelProviderId(), row.modelId(), row.thinking());
            OpenCodeClient.SessionCreationPlan plan = OpenCodeClient.SessionCreationPlan.fromPersisted(
                    Path.of(row.canonicalDirectory()), row.exactTitle(), row.runtimeGenerationId(), row.managed(),
                    row.internalMcpServer(), row.endpointFingerprint(), model,
                    OpenCodeClient.SessionProfile.valueOf(row.profile()), permissionPolicy,
                    row.permissionPolicyDigest(), row.creationCredential(), row.createRequestSha256());
            if (!expectedTitle(row).equals(plan.exactTitle())) throw invalid();
            return plan;
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    String encodePermissionPolicy(OpenCodeClient.SessionCreationPlan plan) {
        try {
            return json.writeValueAsString(plan.permissionPolicy());
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    void validatePreparedPlan(String baseTitle, OpenCodeClient.OpenCodeModel model,
            OpenCodeClient.SessionCreationPlan plan) {
        try {
            if (plan == null || plan.profile()
                    != OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS
                    || !plan.managed() || !Objects.equals(model, plan.model())
                    || !OpenCodeClient.recoveryTitle(baseTitle, plan.creationCredential()).equals(plan.exactTitle())
                    || actualToolName(plan.permissionPolicy(), plan.internalMcpServer(), false) == null
                    || !OpenCodeClient.permissionPolicyDigest(plan.permissionPolicy())
                            .equals(plan.permissionPolicyDigest())
                    || !OpenCodeClient.sessionCreationRequestSha256(plan).equals(plan.createRequestSha256())) {
                throw invalid();
            }
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    String actualToolName(AcceptanceCandidateInternalLaunchRow row) {
        OpenCodeClient.SessionCreationPlan plan = decode(row);
        return actualToolName(plan.permissionPolicy(), plan.internalMcpServer(), true);
    }

    void validatePlanningObject(String value) {
        try {
            JsonNode root = parse(value);
            if (root == null || !root.isObject()) throw invalidPlanning();
        } catch (RuntimeException invalid) {
            throw invalidPlanning();
        }
    }

    void validateRoutePlan(String value) {
        try {
            JsonNode root = parse(value);
            requireFields(root, ROUTE_FIELDS);
            if (!CONTRACT.equals(text(root, "contractVersion"))
                    || !strictBoolean(root.get("serverResolved"), false)
                    || !strictBoolean(root.get("compilerRequired"), true)) throw invalidRoute();
            validateResolution(root.get("resolution"));
        } catch (RuntimeException invalid) {
            throw invalidRoute();
        }
    }

    private void validateResolution(JsonNode resolution) {
        requireFields(resolution, RESOLUTION_FIELDS);
        if (!"NEEDS_COMPILER".equals(text(resolution, "outcome"))) throw invalidRoute();
        List<Integer> stageIndexes = validateStages(resolution.get("stageCandidates"));
        validateFactCandidates(resolution.get("factAssignmentCandidates"), stageIndexes);
        List<Integer> ambiguous = integerArray(
                resolution.get("ambiguousCapabilityFactIndexes"), true);
        Map<Integer, List<Integer>> tied = tiedCapabilities(
                resolution.get("tiedCapabilityIndexesByFact"), ambiguous);
        List<List<Integer>> optima = nestedIntegerArray(
                resolution.get("optimalTieChoiceSets"), true);
        int tieCount = integer(resolution.get("trueCapabilityTieCount"));
        if (tieCount < 2 || tieCount > 32 || tieCount != optima.size()) throw invalidRoute();
        Set<Integer> allowedCapabilities = tied.values().stream()
                .flatMap(List::stream).collect(java.util.stream.Collectors.toSet());
        Set<List<Integer>> uniqueOptima = new LinkedHashSet<>();
        for (List<Integer> optimum : optima) {
            if (optimum.isEmpty() || !allowedCapabilities.containsAll(optimum)
                    || !uniqueOptima.add(optimum)) throw invalidRoute();
        }
    }

    private List<Integer> validateStages(JsonNode stages) {
        if (stages == null || !stages.isArray() || stages.isEmpty()) throw invalidRoute();
        List<Integer> indexes = new ArrayList<>();
        for (int expected = 0; expected < stages.size(); expected++) {
            JsonNode stage = stages.get(expected);
            requireFields(stage, STAGE_FIELDS);
            int index = integer(stage.get("stageIndex"));
            if (index != expected || blank(text(stage, "title")) || blank(text(stage, "objective"))) {
                throw invalidRoute();
            }
            integerArray(stage.get("lockedFactIndexes"), false);
            indexes.add(index);
        }
        return List.copyOf(indexes);
    }

    private void validateFactCandidates(JsonNode candidates, List<Integer> stageIndexes) {
        if (candidates == null || !candidates.isArray()) throw invalidRoute();
        Set<Integer> facts = new LinkedHashSet<>();
        for (JsonNode candidate : candidates) {
            requireFields(candidate, FACT_FIELDS);
            int fact = integer(candidate.get("factIndex"));
            List<Integer> allowed = integerArray(candidate.get("allowedStageIndexes"), true);
            if (!facts.add(fact) || !allowed.equals(stageIndexes)) throw invalidRoute();
        }
    }

    private Map<Integer, List<Integer>> tiedCapabilities(JsonNode node, List<Integer> ambiguous) {
        if (node == null || !node.isObject()) throw invalidRoute();
        java.util.LinkedHashMap<Integer, List<Integer>> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getKey().matches("0|[1-9][0-9]*")) throw invalidRoute();
            int key;
            try { key = Integer.parseInt(entry.getKey()); }
            catch (NumberFormatException invalid) { throw invalidRoute(); }
            List<Integer> capabilities = integerArray(entry.getValue(), true);
            if (capabilities.size() < 2 || values.putIfAbsent(key, capabilities) != null) {
                throw invalidRoute();
            }
        }
        if (!values.keySet().equals(new LinkedHashSet<>(ambiguous))) throw invalidRoute();
        return java.util.Collections.unmodifiableMap(values);
    }

    private List<List<Integer>> nestedIntegerArray(JsonNode node, boolean nonempty) {
        if (node == null || !node.isArray() || nonempty && node.isEmpty()) throw invalidRoute();
        List<List<Integer>> values = new ArrayList<>();
        for (JsonNode item : node) values.add(integerArray(item, true));
        return List.copyOf(values);
    }

    private List<Integer> integerArray(JsonNode node, boolean nonempty) {
        if (node == null || !node.isArray() || nonempty && node.isEmpty()) throw invalidRoute();
        List<Integer> values = new ArrayList<>();
        Set<Integer> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            int value = integer(item);
            if (!unique.add(value)) throw invalidRoute();
            values.add(value);
        }
        return List.copyOf(values);
    }

    private List<OpenCodeClient.SessionPermissionRule> permissionPolicy(String value) {
        JsonNode root = parse(value);
        if (root == null || !root.isArray()) throw invalid();
        List<OpenCodeClient.SessionPermissionRule> rules = new ArrayList<>();
        for (JsonNode rule : root) {
            requireFields(rule, Set.of("permission", "pattern", "action"));
            rules.add(new OpenCodeClient.SessionPermissionRule(
                    text(rule, "permission"), text(rule, "pattern"), text(rule, "action")));
        }
        return List.copyOf(rules);
    }

    private String actualToolName(List<OpenCodeClient.SessionPermissionRule> policy,
            String internalMcpServer, boolean allowLegacy) {
        if (blank(internalMcpServer)) throw invalid();
        String prefix = internalMcpServer.replaceAll("[^a-zA-Z0-9_-]", "_") + "_";
        String roleTool = prefix + InternalMcpContractCatalog.toolName(
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7);
        String legacyTool = prefix + InternalMcpContractCatalog.legacyToolName();
        if (policy.size() != 3
                || !policy.contains(new OpenCodeClient.SessionPermissionRule("*", "*", "deny"))
                || !policy.contains(new OpenCodeClient.SessionPermissionRule(
                        "external_directory", "*", "deny"))) return null;
        List<String> candidateTools = policy.stream()
                .filter(rule -> "allow".equals(rule.action()) && "*".equals(rule.pattern()))
                .map(OpenCodeClient.SessionPermissionRule::permission)
                .filter(permission -> InternalMcpContractCatalog.toolNames().stream()
                        .map(prefix::concat).anyMatch(permission::equals))
                .toList();
        if (candidateTools.size() != 1) return null;
        String actual = candidateTools.getFirst();
        return actual.equals(roleTool) || allowLegacy && actual.equals(legacyTool) ? actual : null;
    }

    private OpenCodeClient.OpenCodeModel model(String provider, String modelId, Boolean thinking) {
        if ((provider == null) != (modelId == null)) throw invalid();
        if (provider == null) {
            if (thinking != null) throw invalid();
            return null;
        }
        if (blank(provider) || blank(modelId)) throw invalid();
        return new OpenCodeClient.OpenCodeModel(provider, modelId, thinking);
    }

    private void requireRowIdentity(AcceptanceCandidateInternalLaunchRow row) {
        if (row == null || !CONTRACT.equals(row.contractVersion()) || !CONTRACT.equals(row.workflowStep())
                || !PROFILE.equals(row.profile()) || !row.managed()
                || !"LOCAL_REQUEST_ATTESTED".equals(row.attestationType())) throw invalid();
    }

    private String expectedTitle(AcceptanceCandidateInternalLaunchRow row) {
        return OpenCodeClient.recoveryTitle(
                AcceptanceCandidateInternalLaunchPreparer.baseTitle(
                        row.workPackageId(), row.candidateRunId(), row.id()), row.creationCredential());
    }

    private JsonNode parse(String value) {
        try {
            if (value == null || value.isBlank()) throw invalid();
            return strictJson.readTree(value);
        } catch (ConflictException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalidRoute();
        Set<String> actual = new LinkedHashSet<>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(expected)) throw invalidRoute();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual()) throw invalidRoute();
        return value.textValue();
    }

    private static int integer(JsonNode value) {
        if (value == null || !value.isInt() || value.intValue() < 0) throw invalidRoute();
        return value.intValue();
    }

    private static boolean strictBoolean(JsonNode value, boolean expected) {
        return value != null && value.isBoolean() && value.booleanValue() == expected;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ConflictException invalid() {
        return new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_PLAN_INVALID",
                "验收候选 internal launch 的冻结 create plan 无法验证");
    }

    private static ConflictException invalidPlanning() {
        return new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_PLANNING_STALE",
                "验收候选 internal launch 的冻结 planning 已变化");
    }

    private static ConflictException invalidRoute() {
        return new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID",
                "验收候选 internal launch 的 closed-choice route 无法验证");
    }
}
