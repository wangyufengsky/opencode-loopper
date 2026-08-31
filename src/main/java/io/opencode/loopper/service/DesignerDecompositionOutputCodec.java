package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses and validates both compact and historical Decomposer output against one frozen requirement snapshot.
 * This is the common server authority used by candidate and legacy JSON transports.
 */
final class DesignerDecompositionOutputCodec {
    private static final int MAX_WORK_PACKAGES = 6;
    private static final Pattern COMPACT_OUTCOME = Pattern.compile(
            "\\\"outcome\\\"\\s*:", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper json;
    private final AiOutputExtractor outputExtractor;
    private final DesignerDecompositionCandidateCompiler candidateCompiler;
    private final DesignerPackagePlanCompiler packagePlanCompiler;

    DesignerDecompositionOutputCodec(ObjectMapper json, AiOutputExtractor outputExtractor,
                                     DesignerDecompositionCandidateCompiler candidateCompiler,
                                     DesignerPackagePlanCompiler packagePlanCompiler) {
        this.json = json;
        this.outputExtractor = outputExtractor;
        this.candidateCompiler = candidateCompiler;
        this.packagePlanCompiler = packagePlanCompiler;
    }

    /** Compatibility parser for a V22 decomposition already active during a V23 upgrade. */
    AiOutputExtractor.ExtractionResult<DecompositionEnvelope> parseFinal(
            String output, DesignRequirementRevisionRow revision, String planningJson) {
        return outputExtractor.extractJson(output, DECOMPOSITION_PAYLOAD, "DECOMPOSER_OUTPUT",
                DecompositionEnvelope.class, DecompositionEnvelope::normalized, envelope -> {
                    if (envelope == null || blank(envelope.status())) {
                        throw new BadRequestException("DECOMPOSER_STATUS_MISSING", "Decomposer status is required");
                    }
                    if (!blank(planningJson)) validateAgainstPlan(readPlan(planningJson), envelope);
                    validate(envelope, revision);
                });
    }

    AiOutputExtractor.ExtractionResult<DecompositionPlanEnvelope> parsePlan(
            String output, DesignRequirementRevisionRow revision) {
        if (output != null && COMPACT_OUTCOME.matcher(output).find()) {
            AiOutputExtractor.ExtractionResult<CompactDecompositionPlan> compact = outputExtractor.extractJson(
                    output, DECOMPOSITION_PLAN_PAYLOAD, "DECOMPOSER_PLAN_OUTPUT",
                    CompactDecompositionPlan.class, CompactDecompositionPlan::normalized, value -> {
                        if (value == null || blank(value.outcome())) {
                            throw new BadRequestException("DECOMPOSER_PLAN_OUTCOME_MISSING",
                                    "Decomposer semantic outcome is required");
                        }
                    });
            DesignerDecompositionCandidateCompiler.Compilation compilation = candidateCompiler.compile(
                    write(compact.value()), revision);
            if (!compilation.accepted()) {
                MachineCandidateSubmission.Problem problem = compilation.problems().getFirst();
                throw new BadRequestException(problem.code(), problem.detail());
            }
            DecompositionPlanEnvelope compiled = readPlan(compilation.canonicalJson());
            validatePlan(compiled, revision);
            List<String> notes = new ArrayList<>(compact.normalizations());
            notes.add("STATUS_DERIVED");
            notes.add("IDS_AND_REFERENCES_DERIVED");
            if ("READY".equals(compact.value().outcome()) && !compact.value().designGaps().isEmpty()) {
                notes.add("ADVISORY_GAPS_IGNORED");
            }
            return new AiOutputExtractor.ExtractionResult<>(
                    compiled, compact.source(), List.copyOf(notes), write(compiled));
        }
        return outputExtractor.extractJson(output, DECOMPOSITION_PLAN_PAYLOAD, "DECOMPOSER_PLAN_OUTPUT",
                DecompositionPlanEnvelope.class,
                envelope -> canonicalize(envelope.normalized(), revision), envelope -> {
                    if (envelope == null || blank(envelope.status())) {
                        throw new BadRequestException("DECOMPOSER_PLAN_STATUS_MISSING",
                                "Decomposer planning status is required");
                    }
                    validatePlan(envelope, revision);
                });
    }

    void validatePlan(DecompositionPlanEnvelope plan, DesignRequirementRevisionRow revision) {
        validate(plan.toEnvelope(), revision);
        if (!Set.of("DIRECT_DESIGN", "DECOMPOSED").contains(plan.status())) return;
        List<RequirementSegment> segments = requirementSegments(revision.requirementSegmentsJson());
        Set<String> validRefs = segments.stream().map(RequirementSegment::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> mappedRefs = new LinkedHashSet<>();
        Set<String> mappingKeys = new HashSet<>();
        for (RequirementCoverageMapping mapping : plan.coverageMappings()) {
            if (mapping == null || !validRefs.contains(mapping.requirementRef()) || blank(mapping.targetType())
                    || blank(mapping.targetId()) || blank(mapping.rationale())) {
                throw new BadRequestException("DECOMPOSITION_COVERAGE_MAPPING_INVALID",
                        "Every coverage mapping needs a known requirement ref, target, and rationale");
            }
            String type = mapping.targetType().toUpperCase();
            boolean targetMatches;
            if ("GLOBAL_CONSTRAINT".equals(type) && mapping.targetId().matches("GC-[1-9][0-9]*")) {
                int index = Integer.parseInt(mapping.targetId().substring(3)) - 1;
                targetMatches = index >= 0 && index < plan.globalConstraints().size()
                        && plan.globalConstraints().get(index).requirementRefs().contains(mapping.requirementRef());
            } else if ("WORK_PACKAGE".equals(type)) {
                targetMatches = plan.workPackages().stream().anyMatch(item -> mapping.targetId().equals(item.id())
                        && item.requirementRefs().contains(mapping.requirementRef()));
            } else {
                targetMatches = false;
            }
            if (!targetMatches) {
                throw new BadRequestException("DECOMPOSITION_COVERAGE_MAPPING_MISMATCH",
                        "Coverage mapping target does not carry " + mapping.requirementRef() + ": "
                                + mapping.targetId());
            }
            String key = mapping.requirementRef() + ":" + type + ":" + mapping.targetId();
            if (!mappingKeys.add(key)) {
                throw new BadRequestException("DECOMPOSITION_COVERAGE_MAPPING_DUPLICATE",
                        "Coverage mapping is duplicated: " + key);
            }
            mappedRefs.add(mapping.requirementRef());
        }
        if (!mappedRefs.containsAll(validRefs)) {
            Set<String> missing = new LinkedHashSet<>(validRefs);
            missing.removeAll(mappedRefs);
            throw new BadRequestException("DECOMPOSITION_PLAN_COVERAGE_INCOMPLETE",
                    "Planning evidence does not explain requirement coverage: " + missing);
        }
        Set<String> expectedDependencies = new LinkedHashSet<>();
        for (DecomposedWorkPackage workPackage : plan.workPackages()) {
            for (String dependency : workPackage.dependencies()) {
                expectedDependencies.add(workPackage.id() + ":" + dependency);
            }
        }
        Set<String> explainedDependencies = new LinkedHashSet<>();
        for (DependencyEvidence evidence : plan.dependencyEvidence()) {
            if (evidence == null || blank(evidence.workPackageId()) || blank(evidence.dependsOn())
                    || blank(evidence.rationale())) {
                throw new BadRequestException("DECOMPOSITION_DEPENDENCY_EVIDENCE_INVALID",
                        "Every dependency evidence entry needs package ids and a rationale");
            }
            String key = evidence.workPackageId() + ":" + evidence.dependsOn();
            if (!expectedDependencies.contains(key) || !explainedDependencies.add(key)) {
                throw new BadRequestException("DECOMPOSITION_DEPENDENCY_EVIDENCE_MISMATCH",
                        "Dependency evidence is missing, extra, or duplicated: " + key);
            }
        }
        if (!explainedDependencies.equals(expectedDependencies)) {
            throw new BadRequestException("DECOMPOSITION_DEPENDENCY_EVIDENCE_INCOMPLETE",
                    "Every planned dependency needs one concrete rationale");
        }
    }

    List<String> requirementIds(String source) {
        return requirementSegments(source).stream().map(RequirementSegment::id).toList();
    }

    private List<RequirementSegment> requirementSegments(String source) {
        try {
            return json.readValue(source, new TypeReference<>() { });
        } catch (JacksonException invalid) {
            throw new ConflictException("REQUIREMENT_SEGMENTS_INVALID",
                    "Frozen requirement segments are unreadable");
        }
    }

    private void validate(DecompositionEnvelope envelope, DesignRequirementRevisionRow revision) {
        Set<String> statuses = Set.of("DIRECT_DESIGN", "DECOMPOSED", "NEEDS_INPUT", "MULTI_TASK_REQUIRED");
        if (!statuses.contains(envelope.status())) {
            throw new BadRequestException("DECOMPOSER_STATUS_INVALID",
                    "Status must be DIRECT_DESIGN, DECOMPOSED, NEEDS_INPUT, or MULTI_TASK_REQUIRED");
        }
        if ("NEEDS_INPUT".equals(envelope.status())) {
            if (envelope.designGaps().isEmpty()) {
                throw new BadRequestException("DECOMPOSITION_GAPS_REQUIRED",
                        "NEEDS_INPUT requires concrete closed-set design gaps");
            }
            packagePlanCompiler.validateDesignGaps(envelope.designGaps());
            return;
        }
        if ("MULTI_TASK_REQUIRED".equals(envelope.status())) {
            if (blank(envelope.reason())) {
                throw new BadRequestException("MULTI_TASK_REASON_REQUIRED",
                        "MULTI_TASK_REQUIRED requires a concrete boundary reason");
            }
            return;
        }
        if (blank(envelope.normalizedGoal())) {
            throw new BadRequestException("DECOMPOSITION_GOAL_REQUIRED", "A normalized overall goal is required");
        }
        if (envelope.normalizedGoal().length() > 12_000) {
            throw new BadRequestException("DECOMPOSITION_GOAL_TOO_LONG",
                    "The normalized goal exceeds the LoopSpec limit");
        }
        int count = envelope.workPackages().size();
        if ("DIRECT_DESIGN".equals(envelope.status()) && count != 1) {
            throw new BadRequestException("DIRECT_DESIGN_PACKAGE_COUNT_INVALID",
                    "DIRECT_DESIGN must produce exactly one work package");
        }
        if ("DECOMPOSED".equals(envelope.status()) && (count < 2 || count > MAX_WORK_PACKAGES)) {
            throw new BadRequestException("DECOMPOSED_PACKAGE_COUNT_INVALID",
                    "DECOMPOSED must produce 2-6 work packages");
        }
        List<RequirementSegment> segments = requirementSegments(revision.requirementSegmentsJson());
        Set<String> validRefs = segments.stream().map(RequirementSegment::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> covered = new LinkedHashSet<>();
        for (GlobalConstraint constraint : envelope.globalConstraints()) {
            if (constraint == null || blank(constraint.text())) {
                throw new BadRequestException("GLOBAL_CONSTRAINT_INVALID",
                        "Each global constraint needs non-empty text");
            }
            validateRequirementRefs(constraint.requirementRefs(), validRefs, covered);
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> mechanicalTitles = Set.of(
                "前端", "后端", "数据库", "测试", "frontend", "backend", "database", "tests");
        for (int index = 0; index < count; index++) {
            DecomposedWorkPackage workPackage = envelope.workPackages().get(index);
            String expectedId = "WP-" + (index + 1);
            if (workPackage == null || !expectedId.equals(workPackage.id())) {
                throw new BadRequestException("WORK_PACKAGE_ID_INVALID",
                        "Work package ids must be stable and ordered as WP-1..WP-n");
            }
            if (!ids.add(workPackage.id()) || blank(workPackage.title()) || blank(workPackage.objective())
                    || workPackage.deliverables().isEmpty() || workPackage.acceptanceIntent().isEmpty()) {
                throw new BadRequestException("WORK_PACKAGE_INCOMPLETE",
                        expectedId + " requires title, objective, deliverables, and acceptance intent");
            }
            if (mechanicalTitles.contains(workPackage.title().trim().toLowerCase())) {
                throw new BadRequestException("MECHANICAL_LAYER_SPLIT_FORBIDDEN",
                        "Work packages must be vertical business capabilities, not isolated technical layers");
            }
            validateRequirementRefs(workPackage.requirementRefs(), validRefs, covered);
            Set<String> earlier = envelope.workPackages().subList(0, index).stream()
                    .map(DecomposedWorkPackage::id).collect(java.util.stream.Collectors.toSet());
            if (!earlier.containsAll(workPackage.dependencies())) {
                throw new BadRequestException("WORK_PACKAGE_DEPENDENCY_INVALID",
                        expectedId + " may depend only on unique earlier work packages");
            }
            if (new HashSet<>(workPackage.dependencies()).size() != workPackage.dependencies().size()) {
                throw new BadRequestException("WORK_PACKAGE_DEPENDENCY_DUPLICATE",
                        expectedId + " contains duplicate dependencies");
            }
        }
        Set<String> missing = new LinkedHashSet<>(validRefs);
        missing.removeAll(covered);
        if (!missing.isEmpty()) {
            throw new BadRequestException("REQUIREMENT_SEGMENT_UNCOVERED",
                    "Every requirement segment must map to a global constraint or work package: " + missing);
        }
    }

    private DecompositionPlanEnvelope canonicalize(DecompositionPlanEnvelope plan,
                                                    DesignRequirementRevisionRow revision) {
        if (plan == null || !Set.of("DIRECT_DESIGN", "DECOMPOSED").contains(plan.status())) return plan;
        List<RequirementCoverageMapping> mappings = new ArrayList<>();
        Set<String> mappingKeys = new LinkedHashSet<>();
        for (RequirementCoverageMapping mapping : plan.coverageMappings()) {
            if (mapping == null) {
                mappings.add(null);
                continue;
            }
            String key = mapping.requirementRef() + ":" + mapping.targetType() + ":" + mapping.targetId();
            if (mappingKeys.add(key)) mappings.add(mapping);
        }
        List<String> requirementOrder = requirementSegments(revision.requirementSegmentsJson()).stream()
                .map(RequirementSegment::id).toList();
        Map<String, LinkedHashSet<String>> refsByTarget = new LinkedHashMap<>();
        for (RequirementCoverageMapping mapping : mappings) {
            if (mapping == null || blank(mapping.targetType()) || blank(mapping.targetId())
                    || blank(mapping.requirementRef())) continue;
            String target = mapping.targetType().toUpperCase() + ":" + mapping.targetId();
            refsByTarget.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(mapping.requirementRef());
        }
        List<GlobalConstraint> constraints = new ArrayList<>();
        for (int index = 0; index < plan.globalConstraints().size(); index++) {
            GlobalConstraint constraint = plan.globalConstraints().get(index);
            if (constraint == null) {
                constraints.add(null);
                continue;
            }
            Set<String> refs = refsByTarget.getOrDefault(
                    "GLOBAL_CONSTRAINT:GC-" + (index + 1), new LinkedHashSet<>());
            constraints.add(new GlobalConstraint(constraint.text(), orderedRefs(requirementOrder, refs)));
        }
        List<DecomposedWorkPackage> packages = new ArrayList<>();
        for (DecomposedWorkPackage workPackage : plan.workPackages()) {
            if (workPackage == null) {
                packages.add(null);
                continue;
            }
            Set<String> refs = refsByTarget.getOrDefault(
                    "WORK_PACKAGE:" + workPackage.id(), new LinkedHashSet<>());
            packages.add(new DecomposedWorkPackage(workPackage.id(), workPackage.title(), workPackage.objective(),
                    workPackage.scopeIn(), workPackage.scopeOut(), workPackage.dependencies(),
                    workPackage.deliverables(), workPackage.acceptanceIntent(), orderedRefs(requirementOrder, refs)));
        }
        return new DecompositionPlanEnvelope(plan.status(), plan.normalizedGoal(), constraints, packages,
                mappings, plan.dependencyEvidence(), plan.designGaps(), plan.reason()).normalized();
    }

    private void validateAgainstPlan(DecompositionPlanEnvelope plan, DecompositionEnvelope envelope) {
        if (!plan.toEnvelope().equals(envelope)) {
            throw new BadRequestException("DECOMPOSITION_PLAN_DRIFT",
                    "Final decomposition JSON must preserve the frozen planning and coverage decisions exactly");
        }
    }

    DecompositionPlanEnvelope readPlan(String payload) {
        try {
            return json.readValue(payload, DecompositionPlanEnvelope.class).normalized();
        } catch (JacksonException failure) {
            throw new ConflictException("DECOMPOSER_PLAN_INVALID", "Frozen decomposition planning is unreadable");
        } catch (RuntimeException failure) {
            throw new ConflictException("DECOMPOSER_PLAN_INVALID", "Frozen decomposition planning is invalid");
        }
    }

    private void validateRequirementRefs(List<String> refs, Set<String> valid, Set<String> covered) {
        for (String ref : refs) {
            if (!valid.contains(ref)) {
                throw new BadRequestException("REQUIREMENT_REFERENCE_INVALID",
                        "Unknown requirement segment reference: " + ref);
            }
            covered.add(ref);
        }
    }

    private List<String> orderedRefs(List<String> requirementOrder, Set<String> refs) {
        List<String> result = new ArrayList<>();
        for (String requirement : requirementOrder) if (refs.contains(requirement)) result.add(requirement);
        for (String requirement : refs) if (!result.contains(requirement)) result.add(requirement);
        return List.copyOf(result);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Unable to serialize Decomposer output", failure);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record RequirementSegment(String id, String text) { }
}
