package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns historical v5/v6 and current v7 DesignFact snapshots, binding boundaries, solver, and read model. */
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
    private final DesignerMutationObligationExtractor mutationExtractor =
            new DesignerMutationObligationExtractor();
    private final DesignerVerificationCapabilityRegistry capabilityRegistry;
    private final DesignerAcceptancePlanCompiler planCompiler;
    private final DesignerPackagePlanCompiler packagePlanCompiler;
    private final DesignerAcceptanceFastPathResolver fastPathResolver = new DesignerAcceptanceFastPathResolver();
    private final DesignerClosedChoiceContract closedChoiceContract;

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
        this.packagePlanCompiler = packagePlanCompiler;
        this.closedChoiceContract = new DesignerClosedChoiceContract(json, outputExtractor);
    }

    boolean applies(WorkPackageRoleService.View role) {
        return role != null && RolePackRegistry.supportsDeterministicAcceptance(role.rolePackVersion())
                && role.rolePackId() != null && role.rolePackId().startsWith("software-");
    }

    void preflight(DesignWorkPackageRow workPackage, String requirementText, String design,
                   List<String> scopeIn, List<String> scopeOut, List<String> deliverables,
                   WorkPackageRoleService.View role) {
        if (RolePackRegistry.VERSION.equals(role.rolePackVersion())) {
            extractFacts(workPackage, requirementText, design, scopeIn, scopeOut, deliverables, role);
        }
    }

    void freeze(LoopSpecCompilationRow compilation, DesignWorkPackageRow workPackage, String requirementText,
                String design, List<String> scopeIn, List<String> scopeOut, List<String> deliverables,
                WorkPackageRoleService.View role, String now) {
        DesignerAcceptancePlanning.Catalog facts = extractFacts(
                workPackage, requirementText, design, scopeIn, scopeOut, deliverables, role);
        DesignerAcceptancePlanning.CapabilityCatalog capabilities = capabilityRegistry.build(facts, role, design);
        DesignAcceptancePlanningRow row = new DesignAcceptancePlanningRow(compilation.id(),
                compilation.designerSessionId(), workPackage.packageId(), workPackage.designRevision(),
                facts.contractVersion(), facts.designSha256(), "EXTRACTED", AcceptanceBindingSource.UNDECIDED.name(),
                write(facts), write(capabilities),
                null, null, null, null, now, now, 0);
        if (mapper.insertDesignAcceptancePlanning(row) != 1) {
            throw new ConflictException("DESIGN_ACCEPTANCE_PLANNING_CREATE_CONFLICT", "验收意图快照无法冻结");
        }
    }

    private DesignerAcceptancePlanning.Catalog extractFacts(
            DesignWorkPackageRow workPackage, String requirementText, String design,
            List<String> scopeIn, List<String> scopeOut, List<String> deliverables,
            WorkPackageRoleService.View role) {
        String contractVersion = contractVersion(role);
        DesignerAcceptancePlanning.Catalog extracted = factExtractor.extract(
                workPackage.packageId(), workPackage.designRevision(), design, contractVersion);
        return DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(contractVersion)
                ? mutationExtractor.extract(extracted, requirementText, scopeIn, scopeOut, deliverables)
                : extracted;
    }

    static String contractVersion(WorkPackageRoleService.View role) {
        if (RolePackRegistry.VERSION.equals(role.rolePackVersion())) {
            return DesignerAcceptancePlanning.CONTRACT_VERSION_V7;
        }
        if (RolePackRegistry.ACCEPTANCE_V6_VERSION.equals(role.rolePackVersion())) {
            return DesignerAcceptancePlanning.CONTRACT_VERSION_V6;
        }
        return DesignerAcceptancePlanning.CONTRACT_VERSION_V5;
    }

    Optional<DesignAcceptancePlanningRow> find(String compilationId) {
        return mapper.findDesignAcceptancePlanning(compilationId);
    }

    boolean present(String compilationId) {
        return find(compilationId).isPresent();
    }

    boolean v6(String compilationId) {
        return find(compilationId).map(DesignerAcceptanceWorkflow::v6).orElse(false);
    }

    boolean v7(String compilationId) {
        return find(compilationId).map(row ->
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(row.contractVersion())).orElse(false);
    }

    boolean serverDirect(String compilationId) {
        return find(compilationId).map(row -> AcceptanceBindingSource.SERVER_STAGE_HINTS.name()
                .equals(row.bindingSource())).orElse(false);
    }

    RoutingResult route(String compilationId, boolean compilerAlways) {
        DesignAcceptancePlanningRow row = find(compilationId).orElseThrow(() ->
                new ConflictException("DESIGN_ACCEPTANCE_PLANNING_MISSING", "验收意图快照缺失"));
        if (!v6(row)) return new RoutingResult(null, false, true);
        DesignerAcceptanceFastPathResolver.Resolution resolution = fastPathResolver.resolve(facts(row), capabilities(row));
        boolean compilerRequired = resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER
                || compilerAlways && resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.RESOLVED;
        AcceptanceBindingSource source = compilerRequired
                ? AcceptanceBindingSource.AI_DISAMBIGUATION_V6 : AcceptanceBindingSource.SERVER_STAGE_HINTS;
        MutationConservationPolicy.Evaluation conservation =
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(row.contractVersion())
                        ? MutationConservationPolicy.Evaluation.notEvaluated(facts(row)) : null;
        update(row, row.state(), source.name(), write(resolution),
                routingDiagnostics(resolution, null, null, conservation, List.of()), null, null);
        return new RoutingResult(resolution,
                resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.RESOLVED,
                compilerRequired);
    }

    RoutingResult routeCurrent(String compilationId, boolean directSoftwareMode, String rolePackVersion) {
        return route(compilationId, !directSoftwareMode && !RolePackRegistry.VERSION.equals(rolePackVersion));
    }

    DesignGap targetedMutationGap(List<DesignGap> gaps) {
        return gaps.stream().filter(gap -> gap != null
                        && (gap.code() == DesignGapCode.REQUIRED_MUTATION_PATH_UNASSIGNED
                        || gap.code() == DesignGapCode.REQUIRED_MUTATION_PATH_FORBIDDEN))
                .findFirst().orElse(null);
    }

    String prompt(String compilationId, String workPackageId, int stageLimit, String priorError) {
        DesignAcceptancePlanningRow row = find(compilationId).orElseThrow(() ->
                new ConflictException("DESIGN_ACCEPTANCE_PLANNING_MISSING", "验收意图快照缺失"));
        if (v6(row)) {
            DesignerAcceptanceFastPathResolver.Resolution resolution = fastPathResolver.resolve(
                    facts(row), capabilities(row));
            return DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(row.contractVersion())
                    ? DesignerCompilerPromptContracts.acceptanceClosedChoice(workPackageId,
                            closedChoiceFacts(facts(row), resolution),
                            closedChoiceCapabilities(capabilities(row), resolution),
                            closedChoiceContract.resolution(resolution), priorError)
                    : DesignerCompilerPromptContracts.acceptanceDisambiguation(workPackageId, row.factsJson(),
                            row.capabilitiesJson(), write(resolution), priorError);
        }
        return DesignerCompilerPromptContracts.acceptanceBinding(workPackageId, row.factsJson(),
                row.capabilitiesJson(), stageLimit, priorError);
    }

    String closedChoiceFacts(DesignerAcceptancePlanning.Catalog catalog,
                             DesignerAcceptanceFastPathResolver.Resolution resolution) {
        return closedChoiceContract.facts(catalog, resolution);
    }

    String closedChoiceCapabilities(DesignerAcceptancePlanning.CapabilityCatalog catalog,
                                    DesignerAcceptanceFastPathResolver.Resolution resolution) {
        return closedChoiceContract.capabilities(catalog, resolution);
    }

    AiOutputExtractor.ExtractionResult<CompactAcceptanceBindingPlan> parse(String output, int stageLimit) {
        try {
            return outputExtractor.extractJson(output, PAYLOAD, "ACCEPTANCE_BINDING_OUTPUT",
                    CompactAcceptanceBindingPlan.class, CompactAcceptanceBindingPlan::normalized, value -> {
                        if (value != null && value.groupHints().size() > stageLimit) {
                            throw new BadRequestException("ACCEPTANCE_BINDING_GROUP_LIMIT_EXCEEDED",
                                    "Acceptance binding exceeds the package Stage limit");
                        }
                    });
        } catch (BadRequestException invalidAdvice) {
            CompactAcceptanceBindingPlan fallback = new CompactAcceptanceBindingPlan(
                    null, List.of(), List.of(), null).normalized();
            return new AiOutputExtractor.ExtractionResult<>(fallback,
                    AiOutputExtractor.CandidateSource.EMBEDDED,
                    List.of("OPTIONAL_ACCEPTANCE_ADVICE_DROPPED"), write(fallback));
        }
    }

    AiOutputExtractor.ExtractionResult<CompactAcceptanceDisambiguationPlan> parseV6(String output) {
        AiOutputExtractor.ExtractionResult<CompactAcceptanceDisambiguationPlan> extracted =
                outputExtractor.extractJson(output, PAYLOAD, "ACCEPTANCE_DISAMBIGUATION_OUTPUT",
                        CompactAcceptanceDisambiguationPlan.class,
                        CompactAcceptanceDisambiguationPlan::normalized, null);
        if (extracted.normalizations().stream().anyMatch(normalization -> Set.of(
                "UNKNOWN_FIELDS_IGNORED", "FIELD_NAME_NORMALIZED", "SINGLETON_COLLECTION_NORMALIZED",
                "NULL_COLLECTION_NORMALIZED", "CONTRACT_METADATA_DERIVED").contains(normalization))) {
            throw new BadRequestException("AMBIGUOUS_ACCEPTANCE_INTENT",
                    "V6 acceptance disambiguation must use the exact closed response shape");
        }
        return extracted;
    }

    AiOutputExtractor.ExtractionResult<CompactAcceptanceDisambiguationPlan> parseV7(String output) {
        return closedChoiceContract.parse(output);
    }

    BoundResult bind(DesignAcceptancePlanningRow row, DesignWorkPackageRow workPackage, String design,
                     String output, WorkPackageRoleService.View role, List<String> scopeIn,
                     List<String> scopeOut, List<String> deliverables, int stageLimit,
                     boolean directSoftwareMode) {
        AiOutputExtractor.ExtractionResult<CompactAcceptanceBindingPlan> extracted;
        if (v6(row)) {
            AiOutputExtractor.ExtractionResult<CompactAcceptanceDisambiguationPlan> disambiguation =
                    DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(row.contractVersion())
                            ? parseV7(output) : parseV6(output);
            DesignerAcceptanceFastPathResolver.Resolution resolution = fastPathResolver.resolve(
                    facts(row), capabilities(row));
            CompactAcceptanceBindingPlan merged = fastPathResolver.merge(resolution,
                    disambiguation.value(), facts(row), capabilities(row));
            extracted = new AiOutputExtractor.ExtractionResult<>(merged, disambiguation.source(),
                    disambiguation.normalizations(), write(merged));
        } else {
            extracted = parse(output, stageLimit);
        }
        return compile(row, workPackage, design, role, scopeIn, scopeOut, deliverables, stageLimit,
                directSoftwareMode, extracted);
    }

    BoundResult compileServer(DesignAcceptancePlanningRow row, DesignWorkPackageRow workPackage, String design,
                              WorkPackageRoleService.View role, List<String> scopeIn, List<String> scopeOut,
                              List<String> deliverables, int stageLimit, boolean directSoftwareMode) {
        DesignerAcceptanceFastPathResolver.Resolution resolution = fastPathResolver.resolve(
                facts(row), capabilities(row));
        if (resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE) {
            MutationConservationPolicy.Evaluation conservation =
                    MutationConservationPolicy.Evaluation.notEvaluated(facts(row));
            CompactPackageCompilationPlan incomplete = new CompactPackageCompilationPlan(
                    "DESIGN_INCOMPLETE", "服务端阶段解析发现设计不完整", List.of(), null,
                    resolution.designGaps());
            PackageCompilationPlanEnvelope plan = packagePlanCompiler.compile(
                    workPackage, design, incomplete, stageLimit, directSoftwareMode).plan();
            update(row, "BOUND", AcceptanceBindingSource.SERVER_STAGE_HINTS.name(), write(resolution),
                    routingDiagnostics(resolution, null, null, conservation, List.of()), "AMBIGUOUS_ACCEPTANCE_INTENT",
                    resolution.designGaps().getFirst().detail());
            return normalized(plan, List.of("SERVER_STAGE_HINTS_DESIGN_INCOMPLETE"));
        }
        if (resolution.outcome() != DesignerAcceptanceFastPathResolver.Outcome.RESOLVED) {
            throw new ConflictException("ACCEPTANCE_COMPILER_REQUIRED", "验收绑定仍需规范工程师消歧");
        }
        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan(
                "服务端按设计阶段表直接编译", resolution.groupHints(), List.of(), null).normalized();
        AiOutputExtractor.ExtractionResult<CompactAcceptanceBindingPlan> extracted =
                new AiOutputExtractor.ExtractionResult<>(binding, AiOutputExtractor.CandidateSource.STRUCTURED,
                        List.of("SERVER_STAGE_HINTS_RESOLVED"), write(binding));
        return compile(row, workPackage, design, role, scopeIn, scopeOut, deliverables, stageLimit,
                directSoftwareMode, extracted);
    }

    private BoundResult compile(DesignAcceptancePlanningRow row, DesignWorkPackageRow workPackage, String design,
                                WorkPackageRoleService.View role, List<String> scopeIn, List<String> scopeOut,
                                List<String> deliverables, int stageLimit, boolean directSoftwareMode,
                                AiOutputExtractor.ExtractionResult<CompactAcceptanceBindingPlan> extracted) {
        DesignerAcceptancePlanCompiler.Result compiled = planCompiler.compile(workPackage, design, facts(row),
                capabilities(row), extracted.value(), role, scopeIn, scopeOut, deliverables, stageLimit,
                directSoftwareMode);
        DesignerAcceptanceFastPathResolver.Resolution resolution = v6(row)
                ? fastPathResolver.resolve(facts(row), capabilities(row)) : null;
        update(row, planningState(compiled.plan().status()), row.bindingSource(), extracted.canonicalJson(),
                resolution == null
                        ? write(Map.of("solver", compiled.diagnostics(), "scenarios", compiled.scenarios()))
                        : routingDiagnostics(resolution, compiled.diagnostics(), compiled.scenarios(),
                        compiled.mutationConservation(), extracted.normalizations()), null, null);
        List<String> notes = new ArrayList<>(extracted.normalizations());
        notes.add("DESIGN_FACTS_BOUND");
        notes.add("CAPABILITY_SET_COVER_SOLVED");
        notes.add("EARS_CRITERIA_LOWERED");
        notes.addAll(compiled.normalizations());
        AiOutputExtractor.ExtractionResult<PackageCompilationPlanEnvelope> normalized =
                new AiOutputExtractor.ExtractionResult<>(compiled.plan(), extracted.source(), List.copyOf(notes),
                        write(compiled.plan()));
        return new BoundResult(compiled.plan(), normalized);
    }

    private BoundResult normalized(PackageCompilationPlanEnvelope plan, List<String> notes) {
        return new BoundResult(plan, new AiOutputExtractor.ExtractionResult<>(plan,
                AiOutputExtractor.CandidateSource.STRUCTURED, notes, write(plan)));
    }

    private String planningState(String compilationStatus) {
        return switch (compilationStatus) {
            case "COMPILED" -> "COMPILED";
            case "DESIGN_INCOMPLETE" -> "BOUND";
            default -> throw new IllegalStateException("Unsupported deterministic compilation status: "
                    + compilationStatus);
        };
    }

    void markCompatibility(DesignAcceptancePlanningRow row, String output) {
        update(row, "COMPILED", AcceptanceBindingSource.LEGACY_UNKNOWN.name(), output,
                write(Map.of("algorithm", "V3_COMPATIBILITY",
                "normalizations", List.of("LEGACY_V3_MODEL_OUTPUT_COMPATIBILITY"))), null, null);
    }

    void markFailed(String compilationId, String output, String errorCode, String errorDetail) {
        find(compilationId).ifPresent(row -> update(row, "FAILED", row.bindingSource(),
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(row.contractVersion())
                        ? row.bindingJson() : output,
                failureDiagnostics(row, errorCode, errorDetail), errorCode, errorDetail));
    }

    @SuppressWarnings("unchecked")
    private String failureDiagnostics(DesignAcceptancePlanningRow row, String errorCode, String errorDetail) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        try {
            if (!blank(row.diagnosticsJson())) {
                diagnostics.putAll(json.readValue(row.diagnosticsJson(), Map.class));
            }
        } catch (JacksonException ignored) {
            diagnostics.put("priorDiagnostics", row.diagnosticsJson());
        }
        diagnostics.put("compilerErrorCode", errorCode);
        diagnostics.put("compilerErrorDetail", errorDetail);
        return write(diagnostics);
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
            issues.addAll(facts.mutationIssues());
            issues.addAll(capabilities.issues());
            if (!blank(row.errorCode())) issues.add(row.errorCode());
            MutationStatus mutation = mutationStatus(row);
            return new AcceptancePlanningStatus(row.state(), row.bindingSource(), routingReasons(row),
                    facts.facts().size(), scenarios.size(),
                    count(scenarios, "AUTOMATED"), count(scenarios, "BOTH"), count(scenarios, "JUDGE"),
                    count(scenarios, "UNRESOLVED"), scenarios, List.copyOf(issues),
                    mutation.obligationCount(), mutation.resolvedCount(), mutation.unresolvedCount(),
                    mutation.pathConservation(), mutation.reasons());
        } catch (RuntimeException invalid) {
            return new AcceptancePlanningStatus("FAILED", AcceptanceBindingSource.LEGACY_UNKNOWN.name(), List.of(),
                    0, 0, 0, 0, 0, 0, List.of(),
                    List.of("验收意图快照不可读"), 0, 0, 0, "NOT_EVALUATED", List.of());
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

    private void update(DesignAcceptancePlanningRow row, String state, String bindingSource, String bindingJson,
                        String diagnosticsJson, String errorCode, String errorDetail) {
        DesignAcceptancePlanningRow updated = new DesignAcceptancePlanningRow(row.compilationId(),
                row.designerSessionId(), row.workPackageId(), row.designRevision(), row.contractVersion(),
                row.designSha256(), state, bindingSource, row.factsJson(), row.capabilitiesJson(), bindingJson, diagnosticsJson,
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

    private String routingDiagnostics(DesignerAcceptanceFastPathResolver.Resolution resolution,
                                      Object solver, Object scenarios,
                                      MutationConservationPolicy.Evaluation mutationConservation,
                                      List<String> safeNormalizations) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("fastPathDecision", resolution.outcome().name());
        values.put("routingReasons", resolution.routingReasons());
        values.put("unresolvedFactIndexes", resolution.unresolvedFactIndexes());
        values.put("ambiguousCapabilityFactIndexes", resolution.ambiguousCapabilityFactIndexes());
        values.put("trueCapabilityTieCount", resolution.trueCapabilityTieCount());
        if (resolution.routingReasons().contains("COMPILER_AVOIDED_UNIQUE_OPTIMUM")) {
            values.put("compilerAvoidedReason", "UNIQUE_OPTIMUM");
        }
        if (safeNormalizations != null && !safeNormalizations.isEmpty()) {
            values.put("safeNormalizations", List.copyOf(safeNormalizations));
        }
        if (solver != null) values.put("solver", solver);
        if (scenarios != null) values.put("scenarios", scenarios);
        if (mutationConservation != null) {
            values.put("mutationObligationCount", mutationConservation.obligationCount());
            values.put("resolvedMutationObligationCount", mutationConservation.resolvedCount());
            values.put("unresolvedMutationObligationCount", mutationConservation.unresolvedCount());
            values.put("pathConservation", mutationConservation.pathConservation());
            List<String> mutationReasons = new ArrayList<>();
            mutationConservation.bindings().stream().map(DesignerAcceptanceWorkflow::bindingReason)
                    .forEach(mutationReasons::add);
            mutationConservation.unresolved().stream().map(item -> item.pathRule() + "：" + item.reason())
                    .forEach(mutationReasons::add);
            values.put("mutationBindingReasons", List.copyOf(mutationReasons));
        }
        return write(values);
    }

    private MutationStatus mutationStatus(DesignAcceptancePlanningRow row) {
        if (blank(row.diagnosticsJson())) return MutationStatus.empty();
        try {
            tools.jackson.databind.JsonNode root = json.readTree(row.diagnosticsJson());
            List<String> reasons = new ArrayList<>();
            tools.jackson.databind.JsonNode reasonNode = root.get("mutationBindingReasons");
            if (reasonNode != null && reasonNode.isArray()) reasonNode.forEach(item -> reasons.add(item.asText()));
            return new MutationStatus(integer(root, "mutationObligationCount"),
                    integer(root, "resolvedMutationObligationCount"),
                    integer(root, "unresolvedMutationObligationCount"),
                    text(root, "pathConservation", "NOT_EVALUATED"), List.copyOf(reasons));
        } catch (JacksonException invalid) {
            return MutationStatus.empty();
        }
    }

    private static int integer(tools.jackson.databind.JsonNode root, String field) {
        tools.jackson.databind.JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isIntegralNumber() ? Math.max(0, value.asInt()) : 0;
    }

    private static String text(tools.jackson.databind.JsonNode root, String field, String fallback) {
        tools.jackson.databind.JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static String bindingReason(MutationConservationPolicy.Binding binding) {
        String source = switch (binding.source()) {
            case EXACT_FACT_REFERENCE -> "精确设计事实引用";
            case UNIQUE_PATH_COVERAGE -> "唯一阶段路径覆盖";
            case SINGLE_STAGE -> "单阶段精确路径补齐";
        };
        return binding.pathRule() + "：" + source + "，归属阶段："
                + String.join("、", binding.stageNames());
    }

    private List<String> routingReasons(DesignAcceptancePlanningRow row) {
        if (blank(row.diagnosticsJson())) return List.of();
        try {
            tools.jackson.databind.JsonNode node = json.readTree(row.diagnosticsJson()).get("routingReasons");
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(routingReason(item.asText())));
            return List.copyOf(values);
        } catch (JacksonException invalid) { return List.of(); }
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

    private static boolean v6(DesignAcceptancePlanningRow row) {
        return DesignerAcceptancePlanning.CONTRACT_VERSION_V6.equals(row.contractVersion())
                || DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(row.contractVersion());
    }

    private static int count(List<AcceptancePlanningStatus.Scenario> scenarios, String coverage) {
        return (int) scenarios.stream().filter(item -> coverage.equals(item.coverage())).count();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    record RoutingResult(DesignerAcceptanceFastPathResolver.Resolution resolution,
                         boolean serverResolved, boolean compilerRequired) { }

    record BoundResult(PackageCompilationPlanEnvelope plan,
                       AiOutputExtractor.ExtractionResult<PackageCompilationPlanEnvelope> normalized) { }

    private record MutationStatus(int obligationCount, int resolvedCount, int unresolvedCount,
                                  String pathConservation, List<String> reasons) {
        static MutationStatus empty() {
            return new MutationStatus(0, 0, 0, "NOT_EVALUATED", List.of());
        }
    }
}
