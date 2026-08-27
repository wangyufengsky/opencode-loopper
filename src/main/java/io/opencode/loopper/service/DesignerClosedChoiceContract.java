package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.CompactAcceptanceDisambiguationPlan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owns the current-v7 minimal prompt projection and fail-closed output compatibility boundary. */
final class DesignerClosedChoiceContract {
    private static final Pattern PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_COMPILATION_PLAN_JSON_START\\s*-->(.*?)"
                    + "<!--\\s*LOOPSPEC_COMPILATION_PLAN_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "stage", "stages", "grouphints", "command", "commands", "path", "paths",
            "allowedpaths", "forbiddenpaths", "testtargets", "verifier", "verifiers", "evidence",
            "criteria", "acceptancecriteria", "dependencies", "dependson", "dependsonhintindexes",
            "factindexes", "workpackageid", "outcome", "status", "designgaps", "scopein", "scopeout",
            "deliverables", "mutationobligations", "obligations", "forbiddeletes", "requirechanges",
            "verificationruntime", "implementationkind", "security", "permissions", "safety");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "summary", "factassignments", "capabilitypreferences", "handoffsummary");
    private static final Set<String> FACT_ASSIGNMENT_FIELDS = Set.of("factindex", "stageindex");
    private static final Set<String> CAPABILITY_PREFERENCE_FIELDS = Set.of("factindex", "capabilityindexes");
    private static final Set<String> SELECTION_FIELDS = Set.of(
            "factassignments", "capabilitypreferences", "factindex", "stageindex", "capabilityindexes");

    private final ObjectMapper json;
    private final AiOutputExtractor outputExtractor;

    DesignerClosedChoiceContract(ObjectMapper json, AiOutputExtractor outputExtractor) {
        this.json = json;
        this.outputExtractor = outputExtractor;
    }

    AiOutputExtractor.ExtractionResult<CompactAcceptanceDisambiguationPlan> parse(String output) {
        return outputExtractor.extractJson(output, PAYLOAD, "ACCEPTANCE_CLOSED_CHOICE_OUTPUT",
                CompactAcceptanceDisambiguationPlan.class,
                CompactAcceptanceDisambiguationPlan::normalized, null,
                DesignerClosedChoiceContract::validateTree,
                AiOutputExtractor.CandidatePolicy.STRICT_CLOSED_CHOICE);
    }

    String facts(DesignerAcceptancePlanning.Catalog catalog,
                 DesignerAcceptanceFastPathResolver.Resolution resolution) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>(resolution.unresolvedFactIndexes());
        indexes.addAll(resolution.ambiguousCapabilityFactIndexes());
        List<Map<String, Object>> values = catalog.facts().stream().filter(fact -> indexes.contains(fact.index()))
                .map(fact -> Map.<String, Object>of(
                        "index", fact.index(), "kind", fact.kind().name(),
                        "title", fact.title(), "acceptanceText", fact.acceptanceText()))
                .toList();
        return write(Map.of("contractVersion", DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "facts", values));
    }

    String capabilities(DesignerAcceptancePlanning.CapabilityCatalog catalog,
                        DesignerAcceptanceFastPathResolver.Resolution resolution) {
        LinkedHashSet<Integer> indexes = resolution.tiedCapabilityIndexesByFact().values().stream()
                .flatMap(List::stream).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> values = catalog.capabilities().stream()
                .filter(capability -> indexes.contains(capability.index()))
                .map(capability -> Map.<String, Object>of(
                        "index", capability.index(), "kind", capability.kind(), "label", capability.label(),
                        "coversFactIndexes", capability.coversFactIndexes(),
                        "deterministic", capability.deterministic(), "strength", capability.strength()))
                .toList();
        return write(Map.of("contractVersion", DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                "capabilities", values));
    }

    String resolution(DesignerAcceptanceFastPathResolver.Resolution resolution) {
        List<Map<String, Object>> stages = IntStream.range(0, resolution.groupHints().size())
                .mapToObj(index -> {
                    DesignerSemanticContracts.AcceptanceGroupHint stage = resolution.groupHints().get(index);
                    LinkedHashMap<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("stageIndex", index);
                    candidate.put("title", stage.title());
                    candidate.put("objective", stage.objective());
                    candidate.put("lockedFactIndexes", stage.factIndexes());
                    return java.util.Collections.unmodifiableMap(candidate);
                }).toList();
        List<Map<String, Object>> factCandidates = resolution.unresolvedFactIndexes().stream()
                .map(factIndex -> {
                    LinkedHashMap<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("factIndex", factIndex);
                    candidate.put("allowedStageIndexes", IntStream.range(0, stages.size()).boxed().toList());
                    return java.util.Collections.unmodifiableMap(candidate);
                }).toList();
        LinkedHashMap<String, Object> projection = new LinkedHashMap<>();
        projection.put("outcome", resolution.outcome());
        projection.put("stageCandidates", stages);
        projection.put("factAssignmentCandidates", factCandidates);
        projection.put("ambiguousCapabilityFactIndexes", resolution.ambiguousCapabilityFactIndexes());
        projection.put("tiedCapabilityIndexesByFact", resolution.tiedCapabilityIndexesByFact());
        projection.put("optimalTieChoiceSets", resolution.optimalTieChoiceSets());
        projection.put("trueCapabilityTieCount", resolution.trueCapabilityTieCount());
        return write(projection);
    }

    private static void validateTree(JsonNode node) {
        validateTree(node, ObjectContext.ROOT);
    }

    private static void validateTree(JsonNode node, ObjectContext context) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(item -> validateTree(item, context));
            return;
        }
        if (!node.isObject()) return;
        Set<String> fields = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String field = normalized(entry.getKey());
            if (!fields.add(field)) {
                throw new BadRequestException("AMBIGUOUS_ACCEPTANCE_INTENT",
                        "Closed choice output contains conflicting field aliases: " + entry.getKey());
            }
            if (!allowed(context, field) && (SELECTION_FIELDS.contains(field) || forbidden(field))) {
                throw new BadRequestException("AMBIGUOUS_ACCEPTANCE_INTENT",
                        "Closed choice output cannot contain execution or topology field: " + entry.getKey());
            }
            ObjectContext child = switch (field) {
                case "factassignments" -> ObjectContext.FACT_ASSIGNMENT;
                case "capabilitypreferences" -> ObjectContext.CAPABILITY_PREFERENCE;
                default -> ObjectContext.UNKNOWN;
            };
            validateTree(entry.getValue(), child);
        }
    }

    private static boolean allowed(ObjectContext context, String field) {
        return switch (context) {
            case ROOT -> ROOT_FIELDS.contains(field);
            case FACT_ASSIGNMENT -> FACT_ASSIGNMENT_FIELDS.contains(field);
            case CAPABILITY_PREFERENCE -> CAPABILITY_PREFERENCE_FIELDS.contains(field);
            case UNKNOWN -> false;
        };
    }

    private static boolean forbidden(String field) {
        return FORBIDDEN_FIELDS.contains(field) || field.startsWith("stage")
                || field.contains("command") || field.contains("path") || field.contains("testtarget")
                || field.contains("topology") || field.contains("verifier") || field.contains("criterion")
                || field.contains("dependency") || field.contains("permission") || field.contains("security")
                || field.contains("safety") || field.contains("obligation")
                || Set.of("cmd", "argv", "shell", "script").contains(field);
    }

    private static String normalized(String field) {
        return field.replaceAll("[-_\\s]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("Failed to encode closed-choice contract", invalid);
        }
    }

    private enum ObjectContext { ROOT, FACT_ASSIGNMENT, CAPABILITY_PREFERENCE, UNKNOWN }
}
