package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.service.DesignerSemanticContracts.GlobalConstraint;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Canonicalizes frozen decomposition constraints into an idempotent Review Gate context section. */
final class DesignerAggregateContext {
    private static final String HEADING = "全局约束（来源可追踪）：";

    private DesignerAggregateContext() { }

    static String initialGoal(List<DesignerMessageRow> messages, String fallback) {
        return messages.stream().filter(message -> DesignerActor.USER.name().equals(message.actor()))
                .filter(message -> message.workPackageId() == null || message.workPackageId().isBlank())
                .map(DesignerMessageRow::content).filter(content -> content != null && !content.isBlank())
                .findFirst().orElse(fallback);
    }

    static String merge(ObjectMapper json, String original, String constraintsJson) {
        List<GlobalConstraint> constraints;
        try { constraints = json.readValue(constraintsJson, new TypeReference<List<GlobalConstraint>>() { }); }
        catch (JacksonException invalid) { throw new ConflictException("DECOMPOSITION_CONTEXT_INVALID",
                "Frozen global constraints are unreadable"); }
        String base = original == null ? "" : original.trim();
        if (constraints.isEmpty()) return base;
        String tracked = constraints.stream().map(item -> "- " + item.text() + " ["
                + String.join(",", item.requirementRefs()) + "]").collect(Collectors.joining("\n"));
        String section = HEADING + "\n" + tracked;
        String withoutDuplicate = base.replace(section, "").trim();
        return withoutDuplicate.isBlank() ? section : withoutDuplicate + "\n\n" + section;
    }
}
