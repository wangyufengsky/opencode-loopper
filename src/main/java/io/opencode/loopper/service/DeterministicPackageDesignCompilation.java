package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.CONTRACT_VERSION_V7;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.FactKind.DELIVERABLE;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.FactKind.REVIEW;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.FactKind.SCENARIO;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.FactKind.SCOPE;
import static io.opencode.loopper.service.PackageDesignCompilation.Outcome.ACCEPTED;
import static io.opencode.loopper.service.PackageDesignCompilation.Outcome.NEEDS_INPUT;
import static io.opencode.loopper.service.PackageDesignCompilation.Outcome.REJECTED;

import io.opencode.loopper.service.DesignerAcceptancePlanning.Catalog;
import io.opencode.loopper.service.DesignerAcceptancePlanning.Fact;
import io.opencode.loopper.service.DesignerAcceptancePlanning.StageHint;
import io.opencode.loopper.service.DesignerSemanticContracts.CompactAcceptanceBindingPlan;
import io.opencode.loopper.service.DesignerSemanticContracts.DesignGap;
import io.opencode.loopper.service.DesignerSemanticContracts.DesignGapCode;
import io.opencode.loopper.service.DesignerSemanticContracts.PackageCompilationPlanEnvelope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Normalizes package design once, then delegates all executable lowering to the existing acceptance kernel. */
public final class DeterministicPackageDesignCompilation implements PackageDesignCompilation {
    private final ObjectMapper json;
    private final PackageDesignCandidateCodec codec;
    private final PackageDesignMarkdownRenderer markdownRenderer = new PackageDesignMarkdownRenderer();
    private final DesignerDesignFactExtractor factExtractor;
    private final DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();
    private final DesignerVerificationCapabilityRegistry capabilityRegistry =
            new DesignerVerificationCapabilityRegistry();
    private final DesignerAcceptanceFastPathResolver resolver = new DesignerAcceptanceFastPathResolver();
    private final DesignerAcceptancePlanCompiler planCompiler;

    public DeterministicPackageDesignCompilation(ObjectMapper json) {
        this.json = json;
        this.codec = new PackageDesignCandidateCodec(json);
        DesignerEvidenceIndexer evidenceIndexer = new DesignerEvidenceIndexer();
        this.factExtractor = new DesignerDesignFactExtractor(evidenceIndexer);
        this.planCompiler = new DesignerAcceptancePlanCompiler(
                new DesignerPackagePlanCompiler(evidenceIndexer));
    }

    @Override
    public Result compileCandidate(Input input, String candidateJson) {
        PackageDesignCandidateCodec.Decoded decoded = codec.decode(candidateJson, input.stageLimit());
        if (!decoded.valid()) return rejected(decoded.candidate(), decoded.problems());
        PackageDesignCandidateDocument candidate = decoded.candidate();
        String canonicalJson = codec.canonicalJson(candidate);
        if ("NEEDS_INPUT".equals(candidate.outcome())) {
            return new Result(NEEDS_INPUT, canonicalJson, null, null, null, decoded.problems());
        }
        String markdown = markdownRenderer.render(candidate);
        return compileCanonical(input, candidate, canonicalJson, markdown);
    }

    @Override
    public Result compileMarkdown(Input input, String markdown) {
        String canonicalMarkdown = canonicalMarkdown(markdown);
        Catalog facts;
        try {
            facts = factExtractor.extract(input.workPackage().packageId(), input.workPackage().designRevision(),
                    canonicalMarkdown, CONTRACT_VERSION_V7);
        } catch (BadRequestException invalid) {
            return needsInput(problem(invalid.code(), "/markdown", classification(invalid.code())));
        }
        PackageDesignCandidateDocument adapter = markdownAdapter(facts);
        PackageDesignCandidateCodec.Decoded decoded = codec.decode(codec.canonicalJson(adapter), input.stageLimit());
        if (!decoded.valid()) return rejected(decoded.candidate(), decoded.problems());
        return compileCanonical(input, decoded.candidate(), codec.canonicalJson(decoded.candidate()), canonicalMarkdown);
    }

    private Result compileCanonical(Input input, PackageDesignCandidateDocument candidate,
                                    String canonicalJson, String markdown) {
        try {
            Catalog base = factExtractor.extract(input.workPackage().packageId(),
                    input.workPackage().designRevision(), markdown, CONTRACT_VERSION_V7);
            Catalog facts = mutationExtractor.extract(base, input.requirementText(), input.scopeIn(),
                    input.scopeOut(), input.deliverables());
            var capabilities = capabilityRegistry.build(facts, input.role(), markdown);
            DesignerAcceptanceFastPathResolver.Resolution resolution = resolver.resolve(facts, capabilities);
            if (resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE) {
                return gaps(candidate, canonicalJson, markdown, resolution.designGaps());
            }
            if (resolution.outcome() != DesignerAcceptanceFastPathResolver.Outcome.RESOLVED) {
                return needsInput(candidate, canonicalJson, markdown, problem(
                        "AMBIGUOUS_ACCEPTANCE_INTENT", "/stages",
                        PackageDesignCompilation.ProblemClass.SEMANTIC));
            }
            CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan(
                    "服务端按 PACKAGE_DESIGN_V1 阶段关系直接编译", resolution.groupHints(), List.of(), null)
                    .normalized();
            DesignerAcceptancePlanCompiler.Result compiled = planCompiler.compile(
                    input.workPackage(), markdown, facts, capabilities, binding, input.role(), input.scopeIn(),
                    input.scopeOut(), input.deliverables(), input.stageLimit(), input.directSoftwareMode());
            if (!"COMPILED".equals(compiled.plan().status())) {
                return gaps(candidate, canonicalJson, markdown, compiled.plan().designGaps());
            }
            return new Result(ACCEPTED, canonicalJson, markdown, compiled.plan(), write(compiled.plan()), List.of());
        } catch (BadRequestException invalid) {
            return needsInput(candidate, canonicalJson, markdown,
                    problem(invalid.code(), "/candidate", classification(invalid.code())));
        }
    }

    private Result gaps(PackageDesignCandidateDocument candidate, String canonicalJson, String markdown,
                        List<DesignGap> gaps) {
        List<Problem> problems = gaps == null ? List.of() : gaps.stream().limit(16)
                .map(gap -> problem(gap.code().name(), "/stages", classification(gap.code().name()))).toList();
        if (problems.isEmpty()) problems = List.of(problem("PACKAGE_DESIGN_INCOMPLETE", "/candidate",
                PackageDesignCompilation.ProblemClass.SEMANTIC));
        return new Result(NEEDS_INPUT, canonicalJson, markdown, null, null, problems);
    }

    private Result rejected(PackageDesignCandidateDocument candidate, List<Problem> problems) {
        String canonical = candidate == null ? null : codec.canonicalJson(candidate);
        Outcome outcome = problems.stream().anyMatch(problem ->
                problem.problemClass() != PackageDesignCompilation.ProblemClass.MECHANICAL)
                ? NEEDS_INPUT : REJECTED;
        return new Result(outcome, canonical, null, null, null, problems);
    }

    private Result needsInput(Problem problem) {
        return new Result(NEEDS_INPUT, null, null, null, null, List.of(problem));
    }

    private Result needsInput(PackageDesignCandidateDocument candidate, String canonicalJson,
                              String markdown, Problem problem) {
        return new Result(NEEDS_INPUT, canonicalJson, markdown, null, null, List.of(problem));
    }

    private Problem problem(String code, String pointer, ProblemClass type) {
        List<String> allowed = code.endsWith("GAP_CODE_INVALID")
                ? java.util.Arrays.stream(DesignGapCode.values()).map(Enum::name).toList() : List.of();
        String detail = type == ProblemClass.SECURITY
                ? "候选触及服务端拥有的安全或执行边界"
                : "候选存在需要修正或补充的确定性设计问题";
        return new Problem(safeCode(code), pointer, detail, allowed, type, type == ProblemClass.MECHANICAL);
    }

    private static ProblemClass classification(String code) {
        String value = safeCode(code);
        if (value.contains("PATH") || value.contains("FORBIDDEN") || value.contains("DELETE")
                || value.contains("MOVE") || value.contains("SECURITY") || value.contains("PERMISSION")) {
            return ProblemClass.SECURITY;
        }
        return ProblemClass.SEMANTIC;
    }

    private PackageDesignCandidateDocument markdownAdapter(Catalog facts) {
        List<Fact> acceptance = facts.facts().stream().filter(fact -> fact.kind() == SCENARIO || fact.kind() == REVIEW)
                .toList();
        Map<Integer, String> requirementKeys = new LinkedHashMap<>();
        List<PackageDesignCandidateDocument.Requirement> requirements = new ArrayList<>();
        for (int index = 0; index < acceptance.size(); index++) {
            String key = "REQ-" + (index + 1);
            requirementKeys.put(acceptance.get(index).index(), key);
            requirements.add(new PackageDesignCandidateDocument.Requirement(key, acceptance.get(index).acceptanceText()));
        }
        List<PackageDesignCandidateDocument.Scenario> scenarios = new ArrayList<>();
        List<PackageDesignCandidateDocument.Review> reviews = new ArrayList<>();
        Map<Integer, String> factKeys = new LinkedHashMap<>();
        for (Fact fact : acceptance) {
            String requirementRef = requirementKeys.get(fact.index());
            if (fact.kind() == SCENARIO) {
                String key = "SC-" + (scenarios.size() + 1); factKeys.put(fact.index(), key);
                scenarios.add(new PackageDesignCandidateDocument.Scenario(key, fact.title(), fact.condition(),
                        fact.action(), fact.expected(), fact.invariant(), List.of(requirementRef)));
            } else {
                String key = "REV-" + (reviews.size() + 1); factKeys.put(fact.index(), key);
                String[] parts = splitReview(fact.detail());
                reviews.add(new PackageDesignCandidateDocument.Review(key, fact.title(), parts[0], parts[1],
                        List.of(requirementRef)));
            }
        }
        List<PackageDesignCandidateDocument.Deliverable> deliverables = new ArrayList<>();
        for (Fact fact : facts.facts().stream().filter(item -> item.kind() == SCOPE || item.kind() == DELIVERABLE).toList()) {
            String key = "DEL-" + (deliverables.size() + 1); factKeys.put(fact.index(), key);
            deliverables.add(new PackageDesignCandidateDocument.Deliverable(key, fact.kind().name(), fact.title(),
                    fact.detail(), List.copyOf(requirementKeys.values())));
        }
        List<PackageDesignCandidateDocument.Stage> stages = adapterStages(facts, factKeys);
        return new PackageDesignCandidateDocument(PackageDesignCandidateCodec.CONTRACT_VERSION, "READY",
                List.copyOf(requirements), List.copyOf(scenarios), List.copyOf(deliverables), List.copyOf(reviews),
                stages, List.of());
    }

    private List<PackageDesignCandidateDocument.Stage> adapterStages(Catalog facts, Map<Integer, String> factKeys) {
        Map<String, Integer> factByTitle = new LinkedHashMap<>();
        facts.facts().forEach(fact -> factByTitle.putIfAbsent(symbol(fact.title()), fact.index()));
        Map<String, String> stageKeyByTitle = new LinkedHashMap<>();
        for (int index = 0; index < facts.stageHints().size(); index++) {
            stageKeyByTitle.put(symbol(facts.stageHints().get(index).title()), "STAGE-" + (index + 1));
        }
        List<PackageDesignCandidateDocument.Stage> result = new ArrayList<>();
        for (int index = 0; index < facts.stageHints().size(); index++) {
            StageHint hint = facts.stageHints().get(index);
            LinkedHashSet<String> includes = new LinkedHashSet<>();
            hint.includedReferences().stream().map(reference -> factByTitle.get(symbol(reference)))
                    .filter(java.util.Objects::nonNull).map(factKeys::get).filter(java.util.Objects::nonNull)
                    .forEach(includes::add);
            if (facts.stageHints().size() == 1) factKeys.values().forEach(includes::add);
            List<String> dependencies = hint.dependencyReferences().stream()
                    .map(reference -> stageKeyByTitle.get(symbol(reference))).filter(java.util.Objects::nonNull).toList();
            result.add(new PackageDesignCandidateDocument.Stage("STAGE-" + (index + 1), hint.title(),
                    hint.objective(), List.copyOf(includes), dependencies));
        }
        return List.copyOf(result);
    }

    private static String[] splitReview(String detail) {
        String value = detail == null ? "" : detail;
        int marker = value.indexOf("；仅人工原因：");
        return marker < 0 ? new String[] { value, "需要人工语义判断" }
                : new String[] { value.substring(0, marker), value.substring(marker + 7) };
    }

    private String write(PackageCompilationPlanEnvelope plan) {
        try { return json.writeValueAsString(plan); }
        catch (JacksonException impossible) { throw new IllegalStateException("Unable to encode package plan", impossible); }
    }

    private static String canonicalMarkdown(String markdown) {
        String value = markdown == null ? "" : markdown.replace("\r\n", "\n").strip();
        return value + "\n";
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]+") ? code : "PACKAGE_DESIGN_INVALID";
    }

    private static String symbol(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
