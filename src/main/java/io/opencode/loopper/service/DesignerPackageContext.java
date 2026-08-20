package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Provides bounded, persisted context snapshots shared by package Designer and Compiler prompts. */
final class DesignerPackageContext {
    private static final Pattern TEST_EVIDENCE = Pattern.compile(
            "(?i)(?:-D(?:it\\.)?test\\s*=|--tests(?:\\s|=)|[A-Za-z_$][A-Za-z0-9_.$]*(?:Test|Tests)(?:\\.java)?)");
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    DesignerPackageContext(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    String prerequisites(String requirementRevisionId, DesignWorkPackageRow workPackage) {
        Set<String> dependencyIds = new LinkedHashSet<>(strings(workPackage.dependenciesJson()));
        if (dependencyIds.isEmpty()) return "[]";
        List<Map<String, Object>> contracts = mapper.listDesignWorkPackages(requirementRevisionId).stream()
                .filter(item -> dependencyIds.contains(item.packageId()))
                .map(item -> {
                    Map<String, Object> contract = new LinkedHashMap<>();
                    contract.put("workPackageId", item.packageId());
                    contract.put("state", item.state());
                    contract.put("objective", item.objective());
                    contract.put("compilerSummary", blank(item.compilerSummary()) ? "" : item.compilerSummary());
                    contract.put("handoffSummary", blank(item.handoffSummary()) ? "" : item.handoffSummary());
                    return contract;
                })
                .toList();
        return write(contracts);
    }

    String previousDesign(DesignWorkPackageRow workPackage) {
        if (blank(workPackage.designMessageId())) return "（首次设计）";
        return mapper.findDesignerMessage(workPackage.designMessageId()).map(DesignerMessageRow::content)
                .orElse("请基于已经持久化的上下文继续本轮讨论。");
    }

    String decisions(DesignerSessionRow session, DesignWorkPackageRow workPackage) {
        DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(
                session.id(), workPackage.packageId()).orElse(null);
        return discussion == null ? "[]" : discussion.decisionLogJson();
    }

    String packageScope(DesignWorkPackageRow workPackage) {
        return write(Map.of(
                "title", workPackage.title(),
                "objective", workPackage.objective(),
                "scopeIn", strings(workPackage.scopeInJson()),
                "scopeOut", strings(workPackage.scopeOutJson()),
                "deliverables", strings(workPackage.deliverablesJson()),
                "acceptanceIntent", strings(workPackage.acceptanceIntentJson()),
                "requirementRefs", strings(workPackage.requirementRefsJson())));
    }

    String declaredTestEvidence(String design) {
        if (blank(design)) return "[]";
        return write(design.lines().map(String::trim).filter(line -> !line.isEmpty())
                .filter(line -> TEST_EVIDENCE.matcher(line).find())
                .map(line -> line.substring(0, Math.min(line.length(), 512))).distinct().limit(24).toList());
    }

    private List<String> strings(String value) {
        if (blank(value)) return List.of();
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen work-package context is unreadable", failure);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize work-package context", failure);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
