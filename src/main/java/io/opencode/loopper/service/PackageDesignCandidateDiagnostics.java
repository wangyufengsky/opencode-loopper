package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.FactKind.REVIEW;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.FactKind.SCENARIO;
import static io.opencode.loopper.service.PackageDesignCompilation.ProblemClass.CORRECTABLE;

import io.opencode.loopper.service.DesignerAcceptancePlanning.Capability;
import io.opencode.loopper.service.DesignerAcceptancePlanning.CapabilityCatalog;
import io.opencode.loopper.service.DesignerAcceptancePlanning.Catalog;
import io.opencode.loopper.service.DesignerAcceptancePlanning.Fact;
import io.opencode.loopper.service.DesignerAcceptanceFastPathResolver.Resolution;
import io.opencode.loopper.service.DesignerAcceptanceFastPathResolver.ResolutionIssue;
import io.opencode.loopper.service.DesignerSemanticContracts.DesignGap;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Compiles acceptance-planning facts back into candidate-local, directly repairable diagnostics. */
final class PackageDesignCandidateDiagnostics {
    private static final int MAX_PROBLEMS = 64;

    List<PackageDesignCompilation.Problem> resolutionProblems(
            PackageDesignCandidateDocument candidate, Catalog facts, CapabilityCatalog capabilities,
            Resolution resolution) {
        List<PackageDesignCompilation.Problem> problems = new ArrayList<>();
        for (ResolutionIssue issue : resolution.issues()) add(problems,
                resolutionProblem(candidate, facts, capabilities, issue));
        if (problems.isEmpty()) {
            for (Integer factIndex : resolution.unresolvedFactIndexes()) {
                Fact fact = fact(facts, factIndex);
                String factKey = factKey(candidate, fact);
                String pointer = factPointer(candidate, fact, "key");
                List<String> stages = candidate.stages().stream().map(PackageDesignCandidateDocument.Stage::key)
                        .toList();
                add(problems, problem("AMBIGUOUS_ACCEPTANCE_INTENT", pointer,
                        factDescription(candidate, fact) + "没有归属任何阶段；该事实必须且只能出现在一个 stages[].includes 中",
                        stages, "exactly one stage key containing " + factKey,
                        factKey + " is assigned to no stage; source " + source(fact),
                        "Add \"" + factKey + "\" to exactly one /stages/<index>/includes array, keep outcome READY, "
                                + "and resubmit the complete candidate"));
            }
            for (Integer factIndex : resolution.ambiguousCapabilityFactIndexes()) {
                Fact fact = fact(facts, factIndex);
                String pointer = factPointer(candidate, fact, fact.kind() == REVIEW ? "criteria" : "observableResult");
                List<String> labels = capabilityLabels(capabilities, resolution, factIndex);
                String factKey = factKey(candidate, fact);
                add(problems, problem("AMBIGUOUS_ACCEPTANCE_INTENT", pointer,
                        factDescription(candidate, fact) + "同时匹配多个等价验证能力：" + String.join("；", labels),
                        labels, "one unambiguous executable verification capability",
                        factKey + " matches multiple equal capabilities " + labels + "; source " + source(fact),
                        "Clarify /deliverables targets or descriptions so " + factKey
                                + " maps to one verification capability; keep outcome READY and resubmit"));
            }
        }
        return List.copyOf(problems);
    }

    List<PackageDesignCompilation.Problem> gapProblems(
            PackageDesignCandidateDocument candidate, Catalog facts, List<DesignGap> gaps) {
        List<PackageDesignCompilation.Problem> result = new ArrayList<>();
        if (gaps != null) for (DesignGap gap : gaps) {
            Fact related = facts.facts().stream()
                    .filter(fact -> gap.detail() != null && gap.detail().contains(fact.title()))
                    .findFirst().orElse(null);
            String pointer = related == null ? pointer(gap.code().name())
                    : factPointer(candidate, related, related.kind() == REVIEW ? "criteria" : "observableResult");
            String description = related == null ? gap.detail() : factDescription(candidate, related) + "：" + gap.detail();
            add(result, problem(gap.code().name(), pointer, description, List.of(), description,
                    related == null ? candidateValue(candidate, pointer) : source(related),
                    repair(gap.code().name(), pointer, factKey(candidate, related))));
        }
        if (result.isEmpty()) add(result, problem("PACKAGE_DESIGN_INCOMPLETE", "/candidate",
                "候选没有形成可编译的完整工作包设计", List.of(),
                "a complete PACKAGE_DESIGN_V1 candidate", "compiler returned no plan and no specific gap",
                "Complete the missing candidate fields and resubmit the entire candidate"));
        return List.copyOf(result);
    }

    private PackageDesignCompilation.Problem resolutionProblem(
            PackageDesignCandidateDocument candidate, Catalog facts, CapabilityCatalog capabilities,
            ResolutionIssue issue) {
        Fact fact = fact(facts, issue.factIndex());
        String factKey = factKey(candidate, fact);
        String code = issue.code();
        if ("VERIFICATION_CAPABILITY_UNAVAILABLE".equals(code) && fact != null) {
            String pointer = factPointer(candidate, fact, "observableResult");
            return problem(code, pointer,
                    factDescription(candidate, fact) + "的原句没有匹配到可执行验证能力：" + source(fact),
                    List.of(), "为 " + factKey + " 提供唯一、可执行的验证能力",
                    factKey + " 没有匹配到可执行验证能力；来源：" + source(fact),
                    "在 /deliverables 中补充能够证明 " + factKey
                            + " 的聚焦测试目标和验证说明；保持 outcome=READY，并重新提交完整候选");
        }
        if ("ACCEPTANCE_FACT_ASSIGNED_MORE_THAN_ONCE".equals(code) && fact != null) {
            List<String> locations = stageIncludePointers(candidate, factKey);
            String pointer = locations.isEmpty() ? stagePointer(issue.stageIndex(), "includes")
                    : locations.getLast();
            return problem(code, pointer,
                    factDescription(candidate, fact) + "同时归属于 " + stageNames(candidate, locations),
                    List.of(), factKey + " present in exactly one stages[].includes array",
                    factKey + " appears at " + locations + "; source " + source(fact),
                    "Choose the single owning stage and remove " + pointer
                            + " (or the other duplicate location), then resubmit the complete candidate");
        }
        if (code.contains("FACT_REFERENCE")) {
            String pointer = includedReferencePointer(candidate, issue.stageIndex(), issue.reference());
            List<String> allowed = candidateFactKeys(candidate);
            return problem(code, pointer, issue.detail(), allowed,
                    "one candidate-local fact key from allowedValues",
                    "reference \"" + issue.reference() + "\" at " + pointer + " does not identify exactly one fact",
                    "Replace " + pointer + " with one listed fact key and resubmit the complete candidate");
        }
        if (code.contains("STAGE_DEPENDENCY")) {
            String pointer = dependencyPointer(candidate, issue.stageIndex(), issue.reference());
            List<String> allowed = earlierStageKeys(candidate, issue.stageIndex());
            return problem(code, pointer, issue.detail(), allowed,
                    "one earlier stage key" + (allowed.isEmpty() ? " or no dependency" : " from allowedValues"),
                    "dependency \"" + issue.reference() + "\" at " + pointer + " is not an earlier stage",
                    allowed.isEmpty() ? "Remove " + pointer + " and resubmit the complete candidate"
                            : "Replace " + pointer + " with an earlier stage key from allowedValues, or remove it");
        }
        if (code.contains("STAGE_TITLE")) {
            String pointer = stagePointer(issue.stageIndex(), "title");
            return problem(code, pointer, issue.detail(), List.of(), "one non-empty unique stage title",
                    candidateValue(candidate, pointer), "Give " + pointer + " a unique title and resubmit");
        }
        if ("ACCEPTANCE_STAGE_WITHOUT_FACT".equals(code)) {
            String pointer = stagePointer(issue.stageIndex(), "includes");
            List<String> allowed = candidate.scenarios().stream().map(PackageDesignCandidateDocument.Scenario::key)
                    .toList();
            return problem(code, pointer, issue.detail(), allowed,
                    "at least one scenario or review key in this stage", candidateValue(candidate, pointer),
                    "Add the owning scenario or review key to " + pointer + " and resubmit");
        }
        if (code.contains("MUTATION_PATH_SCOPE")) {
            String evidence = facts.mutationObligations().stream()
                    .map(item -> item.sourceRef() + " \"" + item.sourceExcerpt() + "\"")
                    .collect(java.util.stream.Collectors.joining("; "));
            return problem(code, "/deliverables", issue.detail(), List.of(),
                    "one non-conflicting positive repository path scope",
                    evidence.isBlank() ? candidateValue(candidate, "/deliverables") : evidence,
                    "Align /deliverables with the frozen positive requirement path and remove contradictory scope");
        }
        String pointer = issue.stageIndex() == null ? pointer(code) : stagePointer(issue.stageIndex(), "includes");
        return problem(code, pointer, issue.detail(), List.of(), issue.detail(), candidateValue(candidate, pointer),
                repair(code, pointer, factKey));
    }

    private static PackageDesignCompilation.Problem problem(
            String code, String pointer, String detail, List<String> allowed,
            String expected, String actual, String repair) {
        return new PackageDesignCompilation.Problem(code, pointer, bounded(detail), allowed, CORRECTABLE, false,
                bounded(expected), bounded(actual), bounded(repair));
    }

    private static void add(List<PackageDesignCompilation.Problem> target,
                            PackageDesignCompilation.Problem problem) {
        if (target.size() < MAX_PROBLEMS) target.add(problem);
    }

    private static Fact fact(Catalog facts, Integer index) {
        if (index == null) return null;
        return facts.facts().stream().filter(item -> item.index() == index).findFirst().orElse(null);
    }

    private static String factDescription(PackageDesignCandidateDocument candidate, Fact fact) {
        String key = factKey(candidate, fact);
        return "验收事实 " + key + "（“" + (fact == null ? "未知" : fact.title()) + "”）";
    }

    private static String factKey(PackageDesignCandidateDocument candidate, Fact fact) {
        if (fact == null) return "UNKNOWN";
        if (fact.kind() == SCENARIO) return candidate.scenarios().stream()
                .filter(item -> same(item.title(), fact.title())).map(PackageDesignCandidateDocument.Scenario::key)
                .findFirst().orElse("FACT-" + fact.index());
        if (fact.kind() == REVIEW) return candidate.reviews().stream()
                .filter(item -> same(item.title(), fact.title())).map(PackageDesignCandidateDocument.Review::key)
                .findFirst().orElse("FACT-" + fact.index());
        return candidate.deliverables().stream().filter(item -> same(item.target(), fact.title()))
                .map(PackageDesignCandidateDocument.Deliverable::key).findFirst()
                .orElse("FACT-" + fact.index());
    }

    private static String factPointer(PackageDesignCandidateDocument candidate, Fact fact, String field) {
        if (fact == null) return "/candidate";
        if (fact.kind() == SCENARIO) for (int index = 0; index < candidate.scenarios().size(); index++) {
            if (same(candidate.scenarios().get(index).title(), fact.title())) return "/scenarios/" + index + "/" + field;
        }
        if (fact.kind() == REVIEW) for (int index = 0; index < candidate.reviews().size(); index++) {
            if (same(candidate.reviews().get(index).title(), fact.title())) return "/reviews/" + index + "/" + field;
        }
        for (int index = 0; index < candidate.deliverables().size(); index++) {
            if (same(candidate.deliverables().get(index).target(), fact.title())) {
                return "/deliverables/" + index + "/target";
            }
        }
        return "/candidate";
    }

    private static List<String> stageIncludePointers(PackageDesignCandidateDocument candidate, String factKey) {
        List<String> result = new ArrayList<>();
        for (int stage = 0; stage < candidate.stages().size(); stage++) {
            List<String> includes = candidate.stages().get(stage).includes();
            for (int item = 0; item < includes.size(); item++) {
                if (same(includes.get(item), factKey)) result.add("/stages/" + stage + "/includes/" + item);
            }
        }
        return List.copyOf(result);
    }

    private static String stageNames(PackageDesignCandidateDocument candidate, List<String> pointers) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String pointer : pointers) {
            String[] parts = pointer.split("/");
            int index = Integer.parseInt(parts[2]);
            var stage = candidate.stages().get(index);
            result.add(stage.key() + "（“" + stage.title() + "”）");
        }
        return String.join("、", result);
    }

    private static String includedReferencePointer(
            PackageDesignCandidateDocument candidate, Integer stageIndex, String renderedReference) {
        if (stageIndex == null || stageIndex < 0 || stageIndex >= candidate.stages().size()) return "/stages";
        var stage = candidate.stages().get(stageIndex);
        for (int index = 0; index < stage.includes().size(); index++) {
            String key = stage.includes().get(index);
            if (same(key, renderedReference) || same(factTitle(candidate, key), renderedReference)) {
                return "/stages/" + stageIndex + "/includes/" + index;
            }
        }
        return "/stages/" + stageIndex + "/includes";
    }

    private static String dependencyPointer(
            PackageDesignCandidateDocument candidate, Integer stageIndex, String renderedReference) {
        if (stageIndex == null || stageIndex < 0 || stageIndex >= candidate.stages().size()) return "/stages";
        var dependencies = candidate.stages().get(stageIndex).dependencies();
        for (int index = 0; index < dependencies.size(); index++) {
            String key = dependencies.get(index);
            if (same(key, renderedReference) || same(stageTitle(candidate, key), renderedReference)) {
                return "/stages/" + stageIndex + "/dependencies/" + index;
            }
        }
        return "/stages/" + stageIndex + "/dependencies";
    }

    private static List<String> earlierStageKeys(PackageDesignCandidateDocument candidate, Integer stageIndex) {
        if (stageIndex == null || stageIndex <= 0) return List.of();
        return candidate.stages().subList(0, Math.min(stageIndex, candidate.stages().size())).stream()
                .map(PackageDesignCandidateDocument.Stage::key).toList();
    }

    private static List<String> candidateFactKeys(PackageDesignCandidateDocument candidate) {
        List<String> result = new ArrayList<>();
        candidate.scenarios().forEach(item -> result.add(item.key()));
        candidate.deliverables().forEach(item -> result.add(item.key()));
        candidate.reviews().forEach(item -> result.add(item.key()));
        return List.copyOf(result);
    }

    private static List<String> capabilityLabels(
            CapabilityCatalog capabilities, Resolution resolution, int factIndex) {
        List<Integer> indexes = resolution.tiedCapabilityIndexesByFact().getOrDefault(factIndex, List.of());
        return capabilities.capabilities().stream().filter(item -> indexes.isEmpty()
                        ? item.coversFactIndexes().contains(factIndex) : indexes.contains(item.index()))
                .map(Capability::label).toList();
    }

    private static String factTitle(PackageDesignCandidateDocument candidate, String key) {
        return candidate.scenarios().stream().filter(item -> same(item.key(), key))
                .map(PackageDesignCandidateDocument.Scenario::title)
                .findFirst().orElseGet(() -> candidate.deliverables().stream().filter(item -> same(item.key(), key))
                        .map(PackageDesignCandidateDocument.Deliverable::target)
                        .findFirst().orElseGet(() -> candidate.reviews().stream().filter(item -> same(item.key(), key))
                                .map(PackageDesignCandidateDocument.Review::title).findFirst().orElse(key)));
    }

    private static String stageTitle(PackageDesignCandidateDocument candidate, String key) {
        return candidate.stages().stream().filter(item -> same(item.key(), key))
                .map(PackageDesignCandidateDocument.Stage::title).findFirst().orElse(key);
    }

    private static String stagePointer(Integer index, String field) {
        return index == null ? "/stages" : "/stages/" + index + "/" + field;
    }

    private static String pointer(String code) {
        if (code.contains("VERIFICATION")) return "/deliverables";
        if (code.contains("STAGE") || code.contains("ACCEPTANCE")) return "/stages";
        if (code.contains("MUTATION") || code.contains("SCOPE")) return "/deliverables";
        return "/candidate";
    }

    private static String repair(String code, String pointer, String factKey) {
        if (code.contains("VERIFICATION")) return "Add a focused verification target under /deliverables for "
                + factKey + "; keep outcome READY and resubmit";
        if (code.contains("STAGE")) return "Correct " + pointer + " and resubmit the complete candidate";
        return "Correct " + pointer + " using the reported fact and source sentence, then resubmit the complete candidate";
    }

    private static String source(Fact fact) {
        if (fact == null) return "unknown source";
        String excerpt = fact.sourceExcerpt() == null ? fact.acceptanceText() : fact.sourceExcerpt();
        return (fact.sourceRef() == null ? "candidate" : fact.sourceRef()) + " \"" + excerpt + "\"";
    }

    private static String candidateValue(PackageDesignCandidateDocument candidate, String pointer) {
        if (pointer.startsWith("/stages/")) {
            String[] parts = pointer.split("/");
            try {
                int index = Integer.parseInt(parts[2]);
                if (index >= 0 && index < candidate.stages().size()) return candidate.stages().get(index).toString();
            } catch (NumberFormatException ignored) { }
        }
        return pointer + " in submitted PACKAGE_DESIGN_V1 candidate";
    }

    private static boolean same(String left, String right) { return symbol(left).equals(symbol(right)); }
    private static String symbol(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static String bounded(String value) {
        String actual = value == null || value.isBlank() ? "未提供具体诊断" : value;
        return actual.length() <= 1_000 ? actual : actual.substring(0, 1_000);
    }
}
