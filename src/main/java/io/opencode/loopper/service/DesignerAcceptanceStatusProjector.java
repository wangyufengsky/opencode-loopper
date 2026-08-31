package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects bounded, human-facing acceptance status from the frozen planning snapshot. */
final class DesignerAcceptanceStatusProjector {
    private final ObjectMapper json;

    DesignerAcceptanceStatusProjector(ObjectMapper json) {
        this.json = json;
    }

    AcceptancePlanningStatus project(DesignAcceptancePlanningRow row) {
        try {
            DesignerAcceptancePlanning.Catalog facts = json.readValue(
                    row.factsJson(), DesignerAcceptancePlanning.Catalog.class);
            DesignerAcceptancePlanning.CapabilityCatalog capabilities = json.readValue(
                    row.capabilitiesJson(), DesignerAcceptancePlanning.CapabilityCatalog.class);
            List<AcceptancePlanningStatus.Scenario> scenarios = facts.facts().stream()
                    .filter(fact -> fact.kind() == DesignerAcceptancePlanning.FactKind.SCENARIO
                            || fact.kind() == DesignerAcceptancePlanning.FactKind.REVIEW)
                    .map(fact -> scenario(fact, capabilities)).toList();
            LinkedHashSet<String> issues = new LinkedHashSet<>(facts.issues());
            issues.addAll(facts.mutationIssues());
            issues.addAll(capabilities.issues());
            if (!blank(row.errorCode())) issues.add(row.errorCode());
            MutationStatus mutation = mutationStatus(row.diagnosticsJson());
            return new AcceptancePlanningStatus(row.state(), row.bindingSource(),
                    routingReasons(row.diagnosticsJson()), facts.facts().size(), scenarios.size(),
                    count(scenarios, "AUTOMATED"), count(scenarios, "BOTH"), count(scenarios, "JUDGE"),
                    count(scenarios, "UNRESOLVED"), scenarios, List.copyOf(issues),
                    mutation.obligationCount(), mutation.resolvedCount(), mutation.unresolvedCount(),
                    mutation.pathConservation(), mutation.reasons());
        } catch (RuntimeException invalid) {
            return unreadable();
        }
    }

    private AcceptancePlanningStatus.Scenario scenario(
            DesignerAcceptancePlanning.Fact fact,
            DesignerAcceptancePlanning.CapabilityCatalog catalog) {
        List<String> labels = catalog.capabilities().stream()
                .filter(capability -> capability.coversFactIndexes().contains(fact.index()))
                .map(DesignerAcceptancePlanning.Capability::label).toList();
        String coverage = fact.kind() == DesignerAcceptancePlanning.FactKind.REVIEW ? "JUDGE"
                : labels.isEmpty() ? "UNRESOLVED" : "AUTOMATED";
        return new AcceptancePlanningStatus.Scenario(fact.title(), coverage, labels);
    }

    private MutationStatus mutationStatus(String diagnosticsJson) {
        if (blank(diagnosticsJson)) return MutationStatus.empty();
        try {
            JsonNode root = json.readTree(diagnosticsJson);
            List<String> reasons = new ArrayList<>();
            JsonNode reasonNode = root.get("mutationBindingReasons");
            if (reasonNode != null && reasonNode.isArray()) {
                reasonNode.forEach(item -> reasons.add(item.asText()));
            }
            return new MutationStatus(integer(root, "mutationObligationCount"),
                    integer(root, "resolvedMutationObligationCount"),
                    integer(root, "unresolvedMutationObligationCount"),
                    text(root, "pathConservation", "NOT_EVALUATED"), List.copyOf(reasons));
        } catch (JacksonException invalid) {
            return MutationStatus.empty();
        }
    }

    private List<String> routingReasons(String diagnosticsJson) {
        if (blank(diagnosticsJson)) return List.of();
        try {
            JsonNode node = json.readTree(diagnosticsJson).get("routingReasons");
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(routingReason(item.asText())));
            return List.copyOf(values);
        } catch (JacksonException invalid) {
            return List.of();
        }
    }

    private static AcceptancePlanningStatus unreadable() {
        return new AcceptancePlanningStatus("FAILED", AcceptanceBindingSource.LEGACY_UNKNOWN.name(), List.of(),
                0, 0, 0, 0, 0, 0, List.of(),
                List.of("验收意图快照不可读"), 0, 0, 0, "NOT_EVALUATED", List.of());
    }

    private static String routingReason(String value) {
        if (value == null) return "验收绑定原因不可读";
        if (value.startsWith("UNKNOWN_FACT_REFERENCE:"))
            return "阶段表引用“" + value.substring(value.indexOf(':') + 1) + "”无法对应前文标题";
        if (value.startsWith("AMBIGUOUS_FACT_REFERENCE:"))
            return "阶段表引用“" + value.substring(value.indexOf(':') + 1) + "”对应多个同名事实";
        if (value.startsWith("UNRESOLVED_FACTS:")) return "存在尚未归属阶段的验收事实";
        if (value.startsWith("AMBIGUOUS_CAPABILITY:")) return "存在多个可行验证能力，需要辅助选择";
        return switch (value) {
            case "ACCEPTANCE_STAGE_COUNT_INVALID" -> "阶段数量必须为 1–6 个";
            case "ACCEPTANCE_STAGE_TITLE_MISSING" -> "阶段名称不能为空";
            case "ACCEPTANCE_STAGE_TITLE_DUPLICATE" -> "阶段名称必须唯一";
            case "ACCEPTANCE_STAGE_DEPENDENCY_UNKNOWN" -> "前置阶段引用不存在";
            case "ACCEPTANCE_STAGE_DEPENDENCY_NOT_PRIOR" -> "前置阶段只能引用更早阶段";
            case "ACCEPTANCE_FACT_ASSIGNED_MORE_THAN_ONCE" -> "验收场景或评审项不能归属多个阶段";
            case "VERIFICATION_CAPABILITY_UNAVAILABLE" -> "验收场景缺少可执行验证能力";
            default -> "验收绑定需要补充设计";
        };
    }

    private static int integer(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isIntegralNumber() ? Math.max(0, value.asInt()) : 0;
    }

    private static String text(JsonNode root, String field, String fallback) {
        JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static int count(List<AcceptancePlanningStatus.Scenario> scenarios, String coverage) {
        return (int) scenarios.stream().filter(item -> coverage.equals(item.coverage())).count();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record MutationStatus(int obligationCount, int resolvedCount, int unresolvedCount,
                                  String pathConservation, List<String> reasons) {
        static MutationStatus empty() {
            return new MutationStatus(0, 0, 0, "NOT_EVALUATED", List.of());
        }
    }
}
