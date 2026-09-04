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
        return compileCanonical(input, canonicalJson, markdown, true);
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
        return compileCanonical(input, codec.canonicalJson(decoded.candidate()), canonicalMarkdown, false);
    }

    private Result compileCanonical(Input input, String canonicalJson, String markdown,
                                    boolean correctionAllowed) {
        try {
            Catalog base = factExtractor.extract(input.workPackage().packageId(),
                    input.workPackage().designRevision(), markdown, CONTRACT_VERSION_V7);
            Catalog facts = mutationExtractor.extract(base, input.requirementText(), input.scopeIn(),
                    input.scopeOut(), input.deliverables());
            var capabilities = capabilityRegistry.build(facts, input.role(), markdown);
            DesignerAcceptanceFastPathResolver.Resolution resolution = resolver.resolve(facts, capabilities);
            if (resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE) {
                return gaps(canonicalJson, markdown, resolution.designGaps(), correctionAllowed);
            }
            if (resolution.outcome() != DesignerAcceptanceFastPathResolver.Outcome.RESOLVED) {
                return correction(canonicalJson, markdown, ambiguityProblems(resolution), correctionAllowed);
            }
            CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan(
                    "服务端按 PACKAGE_DESIGN_V1 阶段关系直接编译", resolution.groupHints(), List.of(), null)
                    .normalized();
            DesignerAcceptancePlanCompiler.Result compiled = planCompiler.compile(
                    input.workPackage(), markdown, facts, capabilities, binding, input.role(), input.scopeIn(),
                    input.scopeOut(), input.deliverables(), input.stageLimit(), input.directSoftwareMode());
            if (!"COMPILED".equals(compiled.plan().status())) {
                return gaps(canonicalJson, markdown, compiled.plan().designGaps(), correctionAllowed);
            }
            return new Result(ACCEPTED, canonicalJson, markdown, compiled.plan(), write(compiled.plan()), List.of());
        } catch (BadRequestException invalid) {
            ProblemClass type = classification(invalid.code());
            Problem problem = problem(invalid.code(), "/candidate", type);
            return correction(canonicalJson, markdown, List.of(problem),
                    correctionAllowed && correctable(type));
        }
    }

    private Result gaps(String canonicalJson, String markdown, List<DesignGap> gaps, boolean correctionAllowed) {
        List<Problem> problems = gaps == null ? List.of() : gaps.stream().limit(16)
                .map(gap -> {
                    ProblemClass type = classification(gap.code().name());
                    return problem(gap.code().name(), "/stages", type);
                }).toList();
        if (problems.isEmpty()) problems = List.of(problem("PACKAGE_DESIGN_INCOMPLETE", "/candidate",
                PackageDesignCompilation.ProblemClass.CORRECTABLE));
        return correction(canonicalJson, markdown, problems, correctionAllowed);
    }

    private Result rejected(PackageDesignCandidateDocument candidate, List<Problem> problems) {
        String canonical = candidate == null ? null : codec.canonicalJson(candidate);
        Outcome outcome = problems.stream().anyMatch(problem -> !correctable(problem.problemClass()))
                ? NEEDS_INPUT : REJECTED;
        return new Result(outcome, canonical, null, null, null, problems);
    }

    private Result correction(String canonicalJson, String markdown, List<Problem> problems,
                              boolean correctionAllowed) {
        boolean allCorrectable = problems.stream().allMatch(problem -> correctable(problem.problemClass()));
        Outcome outcome = correctionAllowed && allCorrectable ? REJECTED : NEEDS_INPUT;
        return new Result(outcome, canonicalJson, markdown, null, null, problems);
    }

    private Result needsInput(Problem problem) {
        return new Result(NEEDS_INPUT, null, null, null, null, List.of(problem));
    }

    private Problem problem(String code, String pointer, ProblemClass type) {
        return problem(code, pointer, null, type);
    }

    private Problem problem(String code, String pointer, String suppliedDetail, ProblemClass type) {
        List<String> allowed = code.endsWith("GAP_CODE_INVALID")
                ? java.util.Arrays.stream(DesignGapCode.values()).map(Enum::name).toList() : List.of();
        String detail = suppliedDetail == null || suppliedDetail.isBlank()
                ? detail(safeCode(code), type) : suppliedDetail;
        return new Problem(safeCode(code), pointer, detail, allowed, type, type == ProblemClass.MECHANICAL);
    }

    private static ProblemClass classification(String code) {
        String value = safeCode(code);
        if (value.contains("PATH") || value.contains("FORBIDDEN") || value.contains("DELETE")
                || value.contains("MOVE") || value.contains("SECURITY") || value.contains("PERMISSION")) {
            return ProblemClass.SECURITY;
        }
        if ("LARGE_TASK_MODE_REQUIRED".equals(value)) return ProblemClass.HUMAN_REQUIRED;
        return ProblemClass.CORRECTABLE;
    }

    private List<Problem> ambiguityProblems(DesignerAcceptanceFastPathResolver.Resolution resolution) {
        List<Problem> result = new ArrayList<>();
        if (!resolution.unresolvedFactIndexes().isEmpty()) {
            result.add(problem("AMBIGUOUS_ACCEPTANCE_INTENT", "/stages",
                    "至少一个验收事实尚未归属阶段；请确保每个 scenario/review key 在且仅在一个 stages[].includes 中出现",
                    ProblemClass.CORRECTABLE));
        }
        if (!resolution.ambiguousCapabilityFactIndexes().isEmpty()) {
            result.add(problem("AMBIGUOUS_ACCEPTANCE_INTENT", "/deliverables",
                    "至少一个验收事实同时匹配多个验证能力；请把交付目标和验证方式描述到可唯一判定",
                    ProblemClass.CORRECTABLE));
        }
        if (result.isEmpty()) result.add(problem("AMBIGUOUS_ACCEPTANCE_INTENT", "/stages",
                ProblemClass.CORRECTABLE));
        return List.copyOf(result.stream().limit(16).toList());
    }

    private static boolean correctable(ProblemClass type) {
        return type == ProblemClass.MECHANICAL || type == ProblemClass.CORRECTABLE;
    }

    private static String detail(String code, ProblemClass type) {
        if (type == ProblemClass.SECURITY) return "候选触及服务端拥有的安全或执行边界";
        if (type == ProblemClass.HUMAN_REQUIRED) return "候选包含需要用户决策的确定性设计问题";
        return switch (code) {
            case "VERIFICATION_CAPABILITY_UNAVAILABLE" ->
                    "至少一个验收场景没有可执行验证能力；请在 deliverables 中补充明确测试目标和验证方式";
            case "AMBIGUOUS_ACCEPTANCE_INTENT" ->
                    "验收事实的阶段归属或验证方式不唯一；请修正 stages[].includes 或 deliverables";
            case "MISSING_OBSERVABLE_OUTCOME" -> "验收场景缺少可观察结果；请补全 observableResult";
            case "MISSING_EXCEPTION_SEMANTICS" -> "验收场景缺少异常语义；请补全异常时的可观察结果";
            case "MISSING_SCOPE" -> "候选缺少明确交付范围；请补全 deliverables";
            case "MISSING_ACCEPTANCE_INTENT" -> "候选缺少可验证验收场景；请补全 scenarios";
            default -> "候选存在可由模型修正的确定性设计问题";
        };
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
