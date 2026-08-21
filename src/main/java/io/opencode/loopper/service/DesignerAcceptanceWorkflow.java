package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns the v4 DesignFact snapshot, model binding boundary, deterministic solver, and read model. */
final class DesignerAcceptanceWorkflow {
    private static final Pattern PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_COMPILATION_PLAN_JSON_START\\s*-->(.*?)"
                    + "<!--\\s*LOOPSPEC_COMPILATION_PLAN_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern V3_STAGES = Pattern.compile("\\\"stages\\\"\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern V4_GROUPS = Pattern.compile("\\\"groupHints\\\"\\s*:", Pattern.CASE_INSENSITIVE);
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final AiOutputExtractor outputExtractor;
    private final LifecycleTransitionService lifecycle;
    private final DesignerDesignFactExtractor factExtractor;
    private final DesignerVerificationCapabilityRegistry capabilityRegistry;
    private final DesignerAcceptancePlanCompiler planCompiler;

    DesignerAcceptanceWorkflow(LoopperMapper mapper, ObjectMapper json, AiOutputExtractor outputExtractor,
                               LifecycleTransitionService lifecycle,
                               DesignerEvidenceIndexer evidenceIndexer,
                               DesignerPackagePlanCompiler packagePlanCompiler) {
        this.mapper = mapper;
        this.json = json;
        this.outputExtractor = outputExtractor;
        this.lifecycle = lifecycle;
        this.factExtractor = new DesignerDesignFactExtractor(evidenceIndexer);
        this.capabilityRegistry = new DesignerVerificationCapabilityRegistry();
        this.planCompiler = new DesignerAcceptancePlanCompiler(packagePlanCompiler);
    }

    boolean applies(WorkPackageRoleService.View role) {
        return role != null && RolePackRegistry.VERSION.equals(role.rolePackVersion())
                && role.rolePackId() != null && role.rolePackId().startsWith("software-");
    }

    void freeze(LoopSpecCompilationRow compilation, DesignWorkPackageRow workPackage, String design,
                WorkPackageRoleService.View role, String now) {
        DesignerAcceptancePlanning.Catalog facts = factExtractor.extract(
                workPackage.packageId(), workPackage.designRevision(), design);
        DesignerAcceptancePlanning.CapabilityCatalog capabilities = capabilityRegistry.build(facts, role, design);
        DesignAcceptancePlanningRow row = new DesignAcceptancePlanningRow(compilation.id(),
                compilation.designerSessionId(), workPackage.packageId(), workPackage.designRevision(),
                facts.contractVersion(), facts.designSha256(), "EXTRACTED", write(facts), write(capabilities),
                null, null, null, null, now, now, 0);
        if (mapper.insertDesignAcceptancePlanning(row) != 1) {
            throw new ConflictException("DESIGN_ACCEPTANCE_PLANNING_CREATE_CONFLICT", "验收意图快照无法冻结");
        }
    }

    Optional<DesignAcceptancePlanningRow> find(String compilationId) {
        return mapper.findDesignAcceptancePlanning(compilationId);
    }

    boolean present(String compilationId) {
        return find(compilationId).isPresent();
    }

    String prompt(String compilationId, String workPackageId, int stageLimit, String priorError) {
        DesignAcceptancePlanningRow row = find(compilationId).orElseThrow(() ->
                new ConflictException("DESIGN_ACCEPTANCE_PLANNING_MISSING", "验收意图快照缺失"));
        return DesignerCompilerPromptContracts.acceptanceBinding(workPackageId, row.factsJson(),
                row.capabilitiesJson(), stageLimit, priorError);
    }

    AiOutputExtractor.ExtractionResult<CompactAcceptanceBindingPlan> parse(String output, int stageLimit) {
        return outputExtractor.extractJson(output, PAYLOAD, "ACCEPTANCE_BINDING_OUTPUT",
                CompactAcceptanceBindingPlan.class, CompactAcceptanceBindingPlan::normalized, value -> {
                    if (value == null || blank(value.outcome())) {
                        throw new BadRequestException("ACCEPTANCE_BINDING_OUTPUT_CONTRACT_MISMATCH",
                                "Acceptance binding requires outcome=COMPILED or DESIGN_INCOMPLETE");
                    }
                    if ("COMPILED".equals(value.outcome()) && value.groupHints().size() > stageLimit) {
                        throw new BadRequestException("ACCEPTANCE_BINDING_GROUP_LIMIT_EXCEEDED",
                                "Acceptance binding exceeds the package Stage limit");
                    }
                });
    }

    BoundResult bind(DesignAcceptancePlanningRow row, DesignWorkPackageRow workPackage, String design,
                     String output, WorkPackageRoleService.View role, List<String> scopeIn,
                     List<String> scopeOut, List<String> deliverables, int stageLimit,
                     boolean directSoftwareMode) {
        AiOutputExtractor.ExtractionResult<CompactAcceptanceBindingPlan> extracted = parse(output, stageLimit);
        DesignerAcceptancePlanCompiler.Result compiled = planCompiler.compile(workPackage, design, facts(row),
                capabilities(row), extracted.value(), role, scopeIn, scopeOut, deliverables, stageLimit,
                directSoftwareMode);
        update(row, "COMPILED", extracted.canonicalJson(),
                write(Map.of("solver", compiled.diagnostics(), "scenarios", compiled.scenarios())), null, null);
        List<String> notes = new ArrayList<>(extracted.normalizations());
        notes.add("DESIGN_FACTS_BOUND");
        notes.add("CAPABILITY_SET_COVER_SOLVED");
        notes.add("EARS_CRITERIA_LOWERED");
        notes.addAll(compiled.normalizations());
        AiOutputExtractor.ExtractionResult<PackageCompilationPlanEnvelope> normalized =
                new AiOutputExtractor.ExtractionResult<>(compiled.plan(), extracted.source(), List.copyOf(notes),
                        write(compiled.plan()));
        return new BoundResult(compiled, normalized);
    }

    void markCompatibility(DesignAcceptancePlanningRow row, String output) {
        update(row, "COMPILED", output, write(Map.of("algorithm", "V3_COMPATIBILITY",
                "normalizations", List.of("LEGACY_V3_MODEL_OUTPUT_COMPATIBILITY"))), null, null);
    }

    void markFailed(String compilationId, String output, String errorCode, String errorDetail) {
        find(compilationId).ifPresent(row -> update(row, "FAILED", output, row.diagnosticsJson(),
                errorCode, errorDetail));
    }

    boolean legacyV3Output(String output) {
        return output != null && V3_STAGES.matcher(output).find() && !V4_GROUPS.matcher(output).find();
    }

    LoopSpecCompilationRow captureSemantic(LoopSpecCompilationRow row, String output, int stageLimit) {
        try {
            String canonical;
            if (present(row.id()) && !legacyV3Output(output)) {
                canonical = parse(output, stageLimit).canonicalJson();
            } else {
                AiOutputExtractor.ExtractionResult<CompactPackageCompilationPlan> extracted =
                        outputExtractor.extractJson(output, PAYLOAD, "COMPILER_PLAN_OUTPUT",
                                CompactPackageCompilationPlan.class, CompactPackageCompilationPlan::normalized, null);
                CompactPackageCompilationPlan semantic = extracted.value();
                boolean legal = semantic != null && ("COMPILED".equals(semantic.outcome())
                        || "DESIGN_INCOMPLETE".equals(semantic.outcome()));
                if (!legal || ("COMPILED".equals(semantic.outcome()) && semantic.stages().isEmpty())) {
                    return findCompilation(row);
                }
                canonical = extracted.canonicalJson();
            }
            LoopSpecCompilationRow updated = semanticRow(row, canonical, row.formatRepairCount(),
                    row.semanticRepairCount(), row.planningRepairCount());
            mutateCompilation(updated);
            return findCompilation(row);
        } catch (RuntimeException ignored) {
            return findCompilation(row);
        }
    }

    LoopSpecCompilationRow updateRepairCounts(LoopSpecCompilationRow row, int formatRepairs,
                                               int semanticRepairs) {
        LoopSpecCompilationRow updated = semanticRow(row, row.semanticPlanJson(), formatRepairs, semanticRepairs,
                formatRepairs + semanticRepairs);
        mutateCompilation(updated);
        return findCompilation(row);
    }

    AcceptancePlanningStatus status(LoopSpecCompilationRow compilation) {
        if (compilation == null) return null;
        DesignAcceptancePlanningRow row = find(compilation.id()).orElse(null);
        if (row == null) return null;
        try {
            DesignerAcceptancePlanning.Catalog facts = facts(row);
            DesignerAcceptancePlanning.CapabilityCatalog capabilities = capabilities(row);
            List<AcceptancePlanningStatus.Scenario> scenarios = facts.facts().stream()
                    .filter(fact -> fact.kind() == DesignerAcceptancePlanning.FactKind.SCENARIO
                            || fact.kind() == DesignerAcceptancePlanning.FactKind.REVIEW)
                    .map(fact -> scenario(fact, capabilities)).toList();
            LinkedHashSet<String> issues = new LinkedHashSet<>(facts.issues());
            issues.addAll(capabilities.issues());
            if (!blank(row.errorCode())) issues.add(row.errorCode());
            return new AcceptancePlanningStatus(row.state(), facts.facts().size(), scenarios.size(),
                    count(scenarios, "AUTOMATED"), count(scenarios, "BOTH"), count(scenarios, "JUDGE"),
                    count(scenarios, "UNRESOLVED"), scenarios, List.copyOf(issues));
        } catch (RuntimeException invalid) {
            return new AcceptancePlanningStatus("FAILED", 0, 0, 0, 0, 0, 0, List.of(),
                    List.of("验收意图快照不可读"));
        }
    }

    private AcceptancePlanningStatus.Scenario scenario(DesignerAcceptancePlanning.Fact fact,
                                                         DesignerAcceptancePlanning.CapabilityCatalog catalog) {
        List<String> labels = catalog.capabilities().stream()
                .filter(capability -> capability.coversFactIndexes().contains(fact.index()))
                .map(DesignerAcceptancePlanning.Capability::label).toList();
        String coverage = fact.kind() == DesignerAcceptancePlanning.FactKind.REVIEW ? "JUDGE"
                : labels.isEmpty() ? "UNRESOLVED" : "AUTOMATED";
        return new AcceptancePlanningStatus.Scenario(fact.title(), coverage, labels);
    }

    private DesignerAcceptancePlanning.Catalog facts(DesignAcceptancePlanningRow row) {
        try { return json.readValue(row.factsJson(), DesignerAcceptancePlanning.Catalog.class); }
        catch (JacksonException failure) {
            throw new ConflictException("DESIGN_ACCEPTANCE_FACTS_INVALID", "冻结的验收事实无法读取");
        }
    }

    private DesignerAcceptancePlanning.CapabilityCatalog capabilities(DesignAcceptancePlanningRow row) {
        try { return json.readValue(row.capabilitiesJson(), DesignerAcceptancePlanning.CapabilityCatalog.class); }
        catch (JacksonException failure) {
            throw new ConflictException("DESIGN_ACCEPTANCE_CAPABILITIES_INVALID", "冻结的验收能力无法读取");
        }
    }

    private void update(DesignAcceptancePlanningRow row, String state, String bindingJson,
                        String diagnosticsJson, String errorCode, String errorDetail) {
        DesignAcceptancePlanningRow updated = new DesignAcceptancePlanningRow(row.compilationId(),
                row.designerSessionId(), row.workPackageId(), row.designRevision(), row.contractVersion(),
                row.designSha256(), state, row.factsJson(), row.capabilitiesJson(), bindingJson, diagnosticsJson,
                errorCode, errorDetail, row.createdAt(), Instant.now().toString(), row.version());
        if (mapper.updateDesignAcceptancePlanning(updated) != 1) {
            throw new ConflictException("DESIGN_ACCEPTANCE_PLANNING_VERSION_CONFLICT", "验收意图规划已被并发更新");
        }
    }

    private LoopSpecCompilationRow semanticRow(LoopSpecCompilationRow row, String semanticJson,
                                               int formatRepairs, int semanticRepairs, int planningRepairs) {
        return new LoopSpecCompilationRow(row.id(), row.designerSessionId(), row.designRevision(), row.state(),
                row.externalSessionId(), row.externalSessionState(), row.repairCount(), row.sourceDesignMessageId(),
                row.sourceDraftVersion(), row.lastErrorCode(), row.lastErrorDetail(), row.createdAt(),
                Instant.now().toString(), row.version(), row.workPackageId(), row.transportRetryCount(),
                row.compiledPackageJson(), row.workflowStep(), row.planningJson(), planningRepairs,
                row.planningResponseMode(), row.planningResponseSchemaId(), row.planningFormatFallbackUsed(),
                row.finalResponseMode(), row.finalResponseSchemaId(), row.finalFormatFallbackUsed(), semanticJson,
                formatRepairs, semanticRepairs, row.serverCompiled());
    }

    private void mutateCompilation(LoopSpecCompilationRow row) {
        lifecycle.mutateWithoutTransition(() -> mapper.updateLoopSpecCompilation(row),
                () -> new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                        "LoopSpec compilation was updated concurrently"));
    }

    private LoopSpecCompilationRow findCompilation(LoopSpecCompilationRow fallback) {
        return mapper.findLoopSpecCompilation(fallback.id()).orElse(fallback);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("Unable to serialize acceptance plan", failure); }
    }

    private static int count(List<AcceptancePlanningStatus.Scenario> scenarios, String coverage) {
        return (int) scenarios.stream().filter(item -> coverage.equals(item.coverage())).count();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    record BoundResult(DesignerAcceptancePlanCompiler.Result compiled,
                       AiOutputExtractor.ExtractionResult<PackageCompilationPlanEnvelope> normalized) { }
}
