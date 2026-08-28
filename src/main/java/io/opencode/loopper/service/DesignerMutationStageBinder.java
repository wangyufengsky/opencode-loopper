package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;

import io.opencode.loopper.verification.VerifierPathPolicy;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Assigns frozen mutation obligations only when Stage ownership is deterministically provable. */
final class DesignerMutationStageBinder {
    private final DesignerAcceptancePathPolicy pathPolicy = new DesignerAcceptancePathPolicy();

    Resolution bind(Catalog catalog, List<StageInput> stages) {
        List<DesignerAcceptanceStagePathPlanner.Selection> inputSelections = stages.stream()
                .map(StageInput::selection).toList();
        if (!CONTRACT_VERSION_V7.equals(catalog.contractVersion())) {
            return new Resolution(inputSelections, List.of(), List.of(), List.of());
        }
        List<DesignerAcceptanceStagePathPlanner.Selection> selections = new ArrayList<>(inputSelections);
        List<Assignment> assignments = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        LinkedHashSet<String> normalizations = new LinkedHashSet<>();
        for (MutationObligation obligation : catalog.mutationObligations()) {
            if (obligation.operation() == MutationOperation.DELETE_REQUEST
                    || obligation.operation() == MutationOperation.MOVE_SOURCE) continue;
            List<Integer> declaredOwners = declaredResponsibleOwners(obligation, stages);
            if (declaredOwners.size() == 1) {
                assignments.add(assignment(obligation, declaredOwners,
                        BindingSource.EXPLICIT_RESPONSIBLE_PATH, stages));
                normalizations.add("MUTATION_PATH_EXPLICIT_RESPONSIBILITY_BOUND");
                continue;
            }
            if (declaredOwners.size() > 1) {
                unresolved.add(new Unresolved(obligation.index(), declaredOwners,
                        "必改路径在负责路径列中被多个阶段声明，需要保留唯一责任："
                                + stageNames(stages, declaredOwners)));
                normalizations.add("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
                continue;
            }
            List<Integer> direct = directFactOwners(catalog, obligation, stages, selections);
            if (direct.size() == 1) {
                assignments.add(assignment(obligation, direct, BindingSource.EXACT_FACT_REFERENCE, stages));
                normalizations.add("MUTATION_PATH_EXACT_FACT_REFERENCE_BOUND");
                continue;
            }
            if (direct.size() > 1) {
                unresolved.add(new Unresolved(obligation.index(), direct,
                        "必改路径被多个阶段精确引用，需要保留唯一归属：" + stageNames(stages, direct)));
                normalizations.add("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
                continue;
            }
            List<Integer> candidates = coveringStages(obligation, selections);
            if (candidates.size() == 1) {
                assignments.add(assignment(obligation, candidates, BindingSource.UNIQUE_PATH_COVERAGE, stages));
                normalizations.add("MUTATION_PATH_UNIQUE_PATH_COVERAGE_BOUND");
                continue;
            }
            if (candidates.size() > 1) {
                unresolved.add(new Unresolved(obligation.index(), candidates,
                        "必改路径可由多个阶段承接，需要在阶段表中明确引用："
                                + stageNames(stages, candidates)));
                normalizations.add("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
                continue;
            }
            List<Integer> symbolOwners = uniqueDeliverableSymbolOwners(obligation, stages);
            if (symbolOwners.size() == 1) {
                int stageIndex = symbolOwners.getFirst();
                DesignerAcceptanceStagePathPlanner.Selection selected = selections.get(stageIndex);
                LinkedHashSet<String> paths = new LinkedHashSet<>(selected.paths());
                LinkedHashSet<String> justified = new LinkedHashSet<>(selected.justifiedPaths());
                paths.add(obligation.pathRule());
                justified.add(obligation.pathRule());
                selections.set(stageIndex, new DesignerAcceptanceStagePathPlanner.Selection(
                        List.copyOf(paths), List.copyOf(justified)));
                assignments.add(assignment(obligation, symbolOwners,
                        BindingSource.UNIQUE_DELIVERABLE_SYMBOL, stages));
                normalizations.add("MUTATION_PATH_UNIQUE_DELIVERABLE_SYMBOL_BOUND");
                continue;
            }
            if (symbolOwners.size() > 1) {
                unresolved.add(new Unresolved(obligation.index(), symbolOwners,
                        "必改路径的交付符号同时出现在多个阶段，需要在负责路径列中明确唯一归属："
                                + stageNames(stages, symbolOwners)));
                normalizations.add("MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED");
                continue;
            }
            if (selections.size() == 1 && obligation.pathKind() == MutationPathKind.EXACT_PATH) {
                DesignerAcceptanceStagePathPlanner.Selection selected = selections.getFirst();
                LinkedHashSet<String> paths = new LinkedHashSet<>(selected.paths());
                LinkedHashSet<String> justified = new LinkedHashSet<>(selected.justifiedPaths());
                paths.add(obligation.pathRule());
                justified.add(obligation.pathRule());
                selections.set(0, new DesignerAcceptanceStagePathPlanner.Selection(
                        List.copyOf(paths), List.copyOf(justified)));
                assignments.add(assignment(obligation, List.of(0), BindingSource.SINGLE_STAGE, stages));
                normalizations.add("MUTATION_PATH_SINGLE_STAGE_BOUND");
                continue;
            }
            unresolved.add(new Unresolved(obligation.index(), List.of(),
                    "必改路径没有可证明的阶段归属"));
        }
        return new Resolution(List.copyOf(selections), List.copyOf(assignments), List.copyOf(unresolved),
                List.copyOf(normalizations));
    }

    private List<Integer> directFactOwners(Catalog catalog, MutationObligation obligation,
                                           List<StageInput> stages,
                                           List<DesignerAcceptanceStagePathPlanner.Selection> selections) {
        LinkedHashSet<Integer> owners = new LinkedHashSet<>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            boolean exactSource = stages.get(stageIndex).materialFactIndexes().stream()
                    .map(index -> fact(catalog, index))
                    .anyMatch(fact -> Objects.equals(obligation.sourceRef(), fact.sourceRef())
                            && pathPolicy.mutationSemantics(fact)
                            == DesignerAcceptancePathPolicy.MutationFactSemantics.WRITE);
            if (exactSource && covers(obligation, selections.get(stageIndex).justifiedPaths())) {
                owners.add(stageIndex);
            }
        }
        return List.copyOf(owners);
    }

    private static List<Integer> coveringStages(MutationObligation obligation,
                                                List<DesignerAcceptanceStagePathPlanner.Selection> selections) {
        List<Integer> candidates = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < selections.size(); stageIndex++) {
            if (covers(obligation, selections.get(stageIndex).justifiedPaths())) candidates.add(stageIndex);
        }
        return List.copyOf(candidates);
    }

    private static List<Integer> declaredResponsibleOwners(MutationObligation obligation,
                                                           List<StageInput> stages) {
        List<Integer> owners = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            if (covers(obligation, stages.get(stageIndex).responsiblePaths())) owners.add(stageIndex);
        }
        return List.copyOf(owners);
    }

    private static List<Integer> uniqueDeliverableSymbolOwners(MutationObligation obligation,
                                                                List<StageInput> stages) {
        if (obligation.pathKind() != MutationPathKind.EXACT_PATH) return List.of();
        List<String> aliases = exactAliases(obligation.pathRule());
        if (aliases.isEmpty()) return List.of();
        List<Integer> owners = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            String source = normalize(stages.get(stageIndex).ownershipText());
            if (aliases.stream().anyMatch(alias -> exactToken(source, alias))) owners.add(stageIndex);
        }
        return List.copyOf(owners);
    }

    private static List<String> exactAliases(String path) {
        String normalized = normalize(path == null ? "" : path.replace('\\', '/'));
        if (normalized.isBlank()) return List.of();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(normalized);
        String[] segments = normalized.split("/");
        String fileName = segments.length == 0 ? normalized : segments[segments.length - 1];
        aliases.add(fileName);
        if (segments.length >= 2) aliases.add(segments[segments.length - 2] + "/" + fileName);
        int extension = fileName.lastIndexOf('.');
        if (extension > 0) aliases.add(fileName.substring(0, extension));
        return aliases.stream().filter(value -> value.length() >= 3).toList();
    }

    private static boolean exactToken(String source, String alias) {
        if (source.isBlank() || alias.isBlank()) return false;
        Pattern token = Pattern.compile("(?<![\\p{L}\\p{N}_$])" + Pattern.quote(alias)
                + "(?![\\p{L}\\p{N}_$])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return token.matcher(source).find();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private static boolean covers(MutationObligation obligation, List<String> rules) {
        VerifierPathPolicy.RuleRelations relations = VerifierPathPolicy.boundedRuleRelations();
        try {
            return rules.stream().anyMatch(rule -> obligation.pathKind() == MutationPathKind.EXACT_PATH
                    ? relations.allowedRuleCoversExactPath(obligation.pathRule(), rule)
                    : relations.allowedRuleCovers(obligation.pathRule(), rule));
        } catch (RuntimeException invalidOrExhausted) {
            return false;
        }
    }

    private static Fact fact(Catalog catalog, int index) {
        return catalog.facts().stream().filter(fact -> fact.index() == index).findFirst()
                .orElseThrow(() -> new BadRequestException("ACCEPTANCE_FACT_INDEX_INVALID",
                        "Unknown acceptance fact index " + index));
    }

    private static Assignment assignment(MutationObligation obligation, List<Integer> stageIndexes,
                                         BindingSource source, List<StageInput> stages) {
        return new Assignment(obligation.index(), stageIndexes, source,
                stageIndexes.stream().map(index -> stages.get(index).title()).toList());
    }

    private static String stageNames(List<StageInput> stages, List<Integer> indexes) {
        return indexes.stream().map(index -> index >= 0 && index < stages.size()
                        ? stages.get(index).title() : "未知阶段")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    enum BindingSource { EXPLICIT_RESPONSIBLE_PATH, EXACT_FACT_REFERENCE, UNIQUE_PATH_COVERAGE,
        UNIQUE_DELIVERABLE_SYMBOL, SINGLE_STAGE }

    record StageInput(String title, String ownershipText, List<Integer> materialFactIndexes,
                      DesignerAcceptanceStagePathPlanner.Selection selection, List<String> responsiblePaths) {
        StageInput(String title, List<Integer> materialFactIndexes,
                   DesignerAcceptanceStagePathPlanner.Selection selection) {
            this(title, title, materialFactIndexes, selection, List.of());
        }

        StageInput(String title, String ownershipText, List<Integer> materialFactIndexes,
                   DesignerAcceptanceStagePathPlanner.Selection selection) {
            this(title, ownershipText, materialFactIndexes, selection, List.of());
        }

        StageInput {
            title = title == null || title.isBlank() ? "未命名阶段" : title;
            ownershipText = ownershipText == null || ownershipText.isBlank()
                    ? title : title + "\n" + ownershipText;
            materialFactIndexes = materialFactIndexes == null ? List.of() : List.copyOf(materialFactIndexes);
            if (selection == null) {
                selection = new DesignerAcceptanceStagePathPlanner.Selection(List.of(), List.of());
            }
            responsiblePaths = responsiblePaths == null ? List.of() : List.copyOf(responsiblePaths);
        }
    }

    record Assignment(int obligationIndex, List<Integer> stageIndexes, BindingSource source,
                      List<String> stageNames) {
        Assignment {
            stageIndexes = stageIndexes == null ? List.of() : List.copyOf(stageIndexes);
            stageNames = stageNames == null ? List.of() : List.copyOf(stageNames);
        }
    }

    record Unresolved(int obligationIndex, List<Integer> candidateStageIndexes, String reason) {
        Unresolved {
            candidateStageIndexes = candidateStageIndexes == null ? List.of() : List.copyOf(candidateStageIndexes);
        }
    }

    record Resolution(List<DesignerAcceptanceStagePathPlanner.Selection> selections,
                      List<Assignment> assignments, List<Unresolved> unresolved,
                      List<String> normalizations) {
        Resolution {
            selections = selections == null ? List.of() : List.copyOf(selections);
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
        }
    }
}
