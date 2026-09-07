package io.opencode.loopper.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Evidence-based gap classification. Evidence is supplied by trusted snapshot/constraint adapters, never the candidate. */
final class PackageDesignGapAssessment {
    static final String VERSION = "PACKAGE_GAP_ASSESSMENT_V1";
    enum Category {
        CANDIDATE_EXPRESSION("候选表达待修正"), REPOSITORY_UNCONFIRMED("仓库事实待确认"),
        VERIFICATION_TO_BUILD("需要建设验证"), BUSINESS_DECISION("业务选择待确认"),
        PROVEN_CONFLICT("已证实约束冲突"), UNCONFIRMED("尚未确认");
        private final String description;
        Category(String description) { this.description = description; }
        String description() { return description; }
    }
    enum Action {
        REPAIR_CANDIDATE("修正完整候选"), COLLECT_EVIDENCE("补充只读证据"), PLAN_TEST("规划必要测试"),
        ASK_USER("确认业务选择"), STOP_CONFLICT("解决已证实冲突"), CLARIFY_CLAIM("补充缺口依据");
        private final String description;
        Action(String description) { this.description = description; }
        String description() { return description; }
    }
    enum EvidenceKind { CANDIDATE_FIELD, REPOSITORY_UNKNOWN, PLANNED_TEST, USER_DECISION, CONSTRAINT_CONFLICT }
    record Evidence(EvidenceKind kind, List<String> sourceRefs, String detail) {
        Evidence {
            Objects.requireNonNull(kind);
            sourceRefs = List.copyOf(sourceRefs);
            if (sourceRefs.isEmpty() || sourceRefs.stream().anyMatch(ref -> ref == null || ref.isBlank())
                    || detail == null || detail.isBlank()) throw new IllegalArgumentException("Gap evidence requires sources and detail");
        }
    }
    record Assessment(String version, String reportedCode, Category category, Action action,
                      List<String> sourceRefs, String detail, boolean retryable) { }

    private static final Set<String> REPAIRABLE = Set.of("AMBIGUOUS_ACCEPTANCE_INTENT",
            "REQUIRED_MUTATION_PATH_UNASSIGNED", "PACKAGE_DESIGN_COVERAGE_INCOMPLETE",
            "PACKAGE_DESIGN_REFERENCE_INVALID");

    Assessment assess(String reportedCode, List<Evidence> evidence) {
        Objects.requireNonNull(reportedCode);
        // A proof of a conflict always wins over a concurrently repairable expression or missing test.
        for (EvidenceKind kind : List.of(EvidenceKind.CONSTRAINT_CONFLICT, EvidenceKind.USER_DECISION,
                EvidenceKind.CANDIDATE_FIELD, EvidenceKind.REPOSITORY_UNKNOWN, EvidenceKind.PLANNED_TEST)) {
            var matches = evidence.stream().filter(item -> item.kind() == kind).toList();
            if (!matches.isEmpty()) return fromEvidence(reportedCode, kind, matches);
        }
        if (REPAIRABLE.contains(reportedCode)) return result(reportedCode, Category.CANDIDATE_EXPRESSION,
                Action.REPAIR_CANDIDATE, List.of(), "修正候选中的引用、覆盖或归属，保留其余已知需求", true);
        return result(reportedCode, Category.UNCONFIRMED, Action.CLARIFY_CLAIM, List.of(),
                "该缺口目前只有模型声明，尚未证实；请指出冻结需求来源、未定事项及不同行为，或修正完整候选", true);
    }

    private Assessment fromEvidence(String code, EvidenceKind kind, List<Evidence> evidence) {
        List<String> refs = evidence.stream().flatMap(item -> item.sourceRefs().stream()).distinct().toList();
        String detail = evidence.stream().map(Evidence::detail).distinct().collect(java.util.stream.Collectors.joining("；"));
        return switch (kind) {
            case CONSTRAINT_CONFLICT -> result(code, Category.PROVEN_CONFLICT, Action.STOP_CONFLICT, refs, detail, false);
            case USER_DECISION -> result(code, Category.BUSINESS_DECISION, Action.ASK_USER, refs, detail, false);
            case CANDIDATE_FIELD -> result(code, Category.CANDIDATE_EXPRESSION, Action.REPAIR_CANDIDATE, refs, detail, true);
            case REPOSITORY_UNKNOWN -> result(code, Category.REPOSITORY_UNCONFIRMED, Action.COLLECT_EVIDENCE, refs, detail, true);
            case PLANNED_TEST -> result(code, Category.VERIFICATION_TO_BUILD, Action.PLAN_TEST, refs, detail, true);
        };
    }

    private Assessment result(String code, Category category, Action action, List<String> refs, String detail, boolean retryable) {
        return new Assessment(VERSION, code, category, action, refs, detail, retryable);
    }
}
