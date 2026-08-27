package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.verification.VerifierPathPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Proves that every frozen mutation obligation is enforced by the compiled Stage path contract. */
final class MutationConservationPolicy {
    Evaluation evaluate(Catalog catalog, List<CompactStage> stages,
                        List<List<String>> justifiedMutationPaths) {
        return evaluate(catalog, stages, obligation ->
                new Ownership(owners(obligation, stages, justifiedMutationPaths,
                        VerifierPathPolicy.boundedRuleRelations()), null, null, List.of()));
    }

    Evaluation evaluate(Catalog catalog, List<CompactStage> stages,
                        DesignerMutationStageBinder.Resolution bindings) {
        return evaluate(catalog, stages, obligation -> {
            DesignerMutationStageBinder.Unresolved unresolved = bindings.unresolved().stream()
                    .filter(item -> item.obligationIndex() == obligation.index()).findFirst().orElse(null);
            if (unresolved != null) return new Ownership(List.of(), unresolved.reason(), null, List.of());
            DesignerMutationStageBinder.Assignment assignment = bindings.assignments().stream()
                    .filter(item -> item.obligationIndex() == obligation.index())
                    .findFirst().orElse(null);
            return assignment == null ? new Ownership(List.of(), null, null, List.of())
                    : new Ownership(assignment.stageIndexes(), null, assignment.source(), assignment.stageNames());
        });
    }

    private Evaluation evaluate(Catalog catalog, List<CompactStage> stages, OwnershipResolver ownershipResolver) {
        if (catalog.mutationObligations().isEmpty()) return Evaluation.conservedEmpty();
        VerifierPathPolicy.RuleRelations relations = VerifierPathPolicy.boundedRuleRelations();
        List<Unresolved> unresolved = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        int resolved = 0;
        for (MutationObligation obligation : catalog.mutationObligations()) {
            if (obligation.operation() == MutationOperation.DELETE_REQUEST
                    || obligation.operation() == MutationOperation.MOVE_SOURCE) {
                unresolved.add(new Unresolved(obligation.index(), obligation.pathRule(),
                        DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN,
                        "删除或移动源端不能由验收编译器自动授权"));
                continue;
            }
            Ownership ownership = ownershipResolver.resolve(obligation);
            List<Integer> owners = ownership.stageIndexes();
            boolean forbidden = stages.stream().flatMap(stage -> stage.forbiddenPaths().stream())
                    .anyMatch(rule -> overlaps(obligation, rule, relations));
            if (forbidden) {
                unresolved.add(new Unresolved(obligation.index(), obligation.pathRule(),
                        DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN,
                        "必改路径同时被禁止路径规则覆盖"));
            } else if (owners.isEmpty()) {
                unresolved.add(new Unresolved(obligation.index(), obligation.pathRule(),
                        DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED,
                        ownership.unresolvedReason() == null
                                ? "必改路径没有进入任何阶段的允许路径合同"
                                : ownership.unresolvedReason()));
            } else if (!ownersHavePathCoverage(obligation, stages, owners, relations)) {
                unresolved.add(new Unresolved(obligation.index(), obligation.pathRule(),
                        DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED,
                        "已绑定阶段没有覆盖必改路径的允许规则"));
            } else if (!evidenceContractsMatch(stages, owners)) {
                unresolved.add(new Unresolved(obligation.index(), obligation.pathRule(),
                        DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED,
                        "阶段、聚焦测试和 GIT_DIFF 的路径合同不一致"));
            } else {
                resolved++;
                if (ownership.bindingSource() != null) {
                    bindings.add(new Binding(obligation.pathRule(), ownership.bindingSource(),
                            ownership.stageNames()));
                }
            }
        }
        return new Evaluation(catalog.mutationObligations().size(), resolved,
                List.copyOf(unresolved), unresolved.isEmpty() ? "CONSERVED" : "BLOCKED",
                List.copyOf(bindings));
    }

    private static boolean ownersHavePathCoverage(MutationObligation obligation, List<CompactStage> stages,
                                                  List<Integer> owners,
                                                  VerifierPathPolicy.RuleRelations relations) {
        return owners.stream().allMatch(owner -> owner != null && owner >= 0 && owner < stages.size()
                && stages.get(owner).allowedPaths().stream().anyMatch(rule -> covers(obligation, rule, relations)));
    }

    private static List<Integer> owners(MutationObligation obligation, List<CompactStage> stages,
                                        List<List<String>> justifiedMutationPaths,
                                        VerifierPathPolicy.RuleRelations relations) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            List<String> eligible = index < justifiedMutationPaths.size()
                    ? justifiedMutationPaths.get(index) : List.of();
            if (eligible.stream().anyMatch(rule -> covers(obligation, rule, relations))) result.add(index);
        }
        return List.copyOf(result);
    }

    private static boolean evidenceContractsMatch(List<CompactStage> stages, List<Integer> owners) {
        for (Integer owner : owners) {
            CompactStage stage = stages.get(owner);
            boolean gitDiff = false;
            for (CompactEvidence evidence : stage.evidence()) {
                if (!"GIT_DIFF".equals(evidence.kind()) && !"FOCUSED_TEST".equals(evidence.kind())) continue;
                if (!sameRules(stage.allowedPaths(), evidence.allowedPaths())
                        || !sameRules(stage.forbiddenPaths(), evidence.forbiddenPaths())) return false;
                if ("GIT_DIFF".equals(evidence.kind())) gitDiff = true;
            }
            if (!gitDiff) return false;
        }
        return true;
    }

    private static boolean sameRules(List<String> left, List<String> right) {
        LinkedHashSet<String> normalizedLeft = left.stream().map(VerifierPathPolicy::normalizePathRule)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> normalizedRight = right.stream().map(VerifierPathPolicy::normalizePathRule)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return normalizedLeft.equals(normalizedRight);
    }

    private static boolean covers(MutationObligation obligation, String allowed,
                                  VerifierPathPolicy.RuleRelations relations) {
        try {
            if (obligation.pathKind() == MutationPathKind.EXACT_PATH) {
                return relations.allowedRuleCoversExactPath(obligation.pathRule(), allowed);
            }
            return relations.allowedRuleCovers(obligation.pathRule(), allowed);
        } catch (RuntimeException invalidOrExhausted) {
            return false;
        }
    }

    private static boolean overlaps(MutationObligation obligation, String rule,
                                    VerifierPathPolicy.RuleRelations relations) {
        try {
            return obligation.pathKind() == MutationPathKind.EXACT_PATH
                    ? relations.ruleMatchesExactPath(obligation.pathRule(), rule)
                    : relations.rulesMayOverlap(obligation.pathRule(), rule);
        } catch (RuntimeException invalidOrExhausted) {
            return true;
        }
    }

    record Evaluation(int obligationCount, int resolvedCount, List<Unresolved> unresolved,
                      String pathConservation, List<Binding> bindings) {
        Evaluation {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }

        static Evaluation conservedEmpty() { return new Evaluation(0, 0, List.of(), "CONSERVED", List.of()); }
        static Evaluation notEvaluated(Catalog catalog) {
            int count = catalog == null ? 0 : catalog.mutationObligations().size();
            return new Evaluation(count, 0, List.of(), "NOT_EVALUATED", List.of());
        }
        int unresolvedCount() { return Math.max(0, obligationCount - resolvedCount); }
        boolean passed() {
            return "CONSERVED".equals(pathConservation);
        }
    }

    record Unresolved(int obligationIndex, String pathRule, DesignGapCode code, String reason) { }
    record Binding(String pathRule, DesignerMutationStageBinder.BindingSource source, List<String> stageNames) {
        Binding {
            stageNames = stageNames == null ? List.of() : List.copyOf(stageNames);
        }
    }

    private record Ownership(List<Integer> stageIndexes, String unresolvedReason,
                             DesignerMutationStageBinder.BindingSource bindingSource,
                             List<String> stageNames) { }

    @FunctionalInterface
    private interface OwnershipResolver {
        Ownership resolve(MutationObligation obligation);
    }
}
