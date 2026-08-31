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

    CandidateBoundary inspectCandidateBoundary(String candidateJson) {
        try {
            JsonNode node = json.readTree(candidateJson);
            if (node == null || !node.isObject()) return CandidateBoundary.CONTRACT_INVALID;
            validateBoundaryTree(node, ObjectContext.ROOT);
            return validCandidateShape(node) ? CandidateBoundary.SAFE : CandidateBoundary.CONTRACT_INVALID;
        } catch (BoundaryViolation violation) {
            return violation.boundary();
        } catch (RuntimeException invalid) {
            return CandidateBoundary.CONTRACT_INVALID;
        }
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
                        "index", capability.index(), "kind", capability.kind(),
                        "label", "closed-choice-" + capability.index(),
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
        try {
            validateBoundaryTree(node, ObjectContext.ROOT);
        } catch (BoundaryViolation violation) {
            throw new BadRequestException("AMBIGUOUS_ACCEPTANCE_INTENT",
                    "Closed choice output cannot contain execution or topology fields");
        }
    }

    private static void validateBoundaryTree(JsonNode node, ObjectContext context) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(item -> validateBoundaryTree(item, context));
            return;
        }
        if (!node.isObject()) return;
        Set<String> fields = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String field = normalized(entry.getKey());
            if (!fields.add(field)) {
                throw new BoundaryViolation(CandidateBoundary.CONTRACT_INVALID);
            }
            if (!allowed(context, field) && forbidden(field)) {
                throw new BoundaryViolation(CandidateBoundary.SECURITY_BOUNDARY);
            }
            if (!allowed(context, field) && SELECTION_FIELDS.contains(field)) {
                throw new BoundaryViolation(CandidateBoundary.CONTRACT_INVALID);
            }
            ObjectContext child = switch (field) {
                case "factassignments" -> ObjectContext.FACT_ASSIGNMENT;
                case "capabilitypreferences" -> ObjectContext.CAPABILITY_PREFERENCE;
                default -> ObjectContext.UNKNOWN;
            };
            validateBoundaryTree(entry.getValue(), child);
        }
    }

    private static boolean validCandidateShape(JsonNode root) {
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String field = normalized(entry.getKey());
            JsonNode value = entry.getValue();
            if (!ROOT_FIELDS.contains(field)) return false;
            if ((field.equals("summary") || field.equals("handoffsummary"))
                    && !value.isNull() && !value.isTextual()) return false;
            if (field.equals("factassignments") && !validObjectArray(value, FACT_ASSIGNMENT_FIELDS,
                    Set.of("factindex", "stageindex"), Set.of())) return false;
            if (field.equals("capabilitypreferences") && !validObjectArray(value,
                    CAPABILITY_PREFERENCE_FIELDS, Set.of("factindex"), Set.of("capabilityindexes"))) return false;
        }
        return true;
    }

    private static boolean validObjectArray(JsonNode value, Set<String> allowed, Set<String> integerFields,
                                            Set<String> integerArrayFields) {
        if (!value.isArray()) return false;
        for (JsonNode item : value) {
            if (!item.isObject()) return false;
            Set<String> seen = new LinkedHashSet<>();
            for (Map.Entry<String, JsonNode> entry : item.properties()) {
                String field = normalized(entry.getKey());
                if (!allowed.contains(field) || !seen.add(field)) return false;
                JsonNode fieldValue = entry.getValue();
                if (integerFields.contains(field) && !fieldValue.isInt()) return false;
                if (integerArrayFields.contains(field)) {
                    if (!fieldValue.isArray()) return false;
                    for (JsonNode index : fieldValue) if (!index.isInt()) return false;
                }
            }
            if (!seen.containsAll(integerFields) || !seen.containsAll(integerArrayFields)) return false;
        }
        return true;
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

    enum CandidateBoundary { SAFE, CONTRACT_INVALID, SECURITY_BOUNDARY }
    private enum ObjectContext { ROOT, FACT_ASSIGNMENT, CAPABILITY_PREFERENCE, UNKNOWN }
    private static final class BoundaryViolation extends RuntimeException {
        private final CandidateBoundary boundary;
        private BoundaryViolation(CandidateBoundary boundary) { this.boundary = boundary; }
        private CandidateBoundary boundary() { return boundary; }
    }
}
