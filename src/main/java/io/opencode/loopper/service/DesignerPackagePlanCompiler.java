package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.verification.ProcessCommandPolicy;
import io.opencode.loopper.verification.TestFrameworkPolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministically turns compact Compiler semantics into an executable package plan. */
final class DesignerPackagePlanCompiler {
    private static final Set<DesignGapCode> ALLOWED_DESIGN_GAPS = EnumSet.allOf(DesignGapCode.class);
    private static final Set<String> COVERABLE_EVIDENCE = Set.of(
            "FOCUSED_TEST", "SELF_CHECK", "HTTP_STATUS", "JSON_PATH", "BROWSER", "DATABASE_QUERY",
            "FILE_CONTENT", "FILE_HASH", "DOCUMENT_STRUCTURE", "TABULAR_DATA");
    private static final Set<String> SUPPLEMENTAL_EVIDENCE = Set.of(
            "FULL_TEST", "BUILD", "GIT_DIFF", "FILE_NOT_EXISTS", "JUNIT_XML");

    private final DesignerEvidenceIndexer evidenceIndexer;

    DesignerPackagePlanCompiler(DesignerEvidenceIndexer evidenceIndexer) {
        this.evidenceIndexer = evidenceIndexer;
    }

    Result compile(DesignWorkPackageRow workPackage, String design, CompactPackageCompilationPlan input,
                   int stageLimit, boolean directSoftwareMode) {
        Normalization normalization = normalize(input);
        CompactPackageCompilationPlan compact = normalization.plan();
        if ("DESIGN_INCOMPLETE".equals(compact.outcome())) {
            PackageCompilationPlanEnvelope incomplete = new PackageCompilationPlanEnvelope(
                    2, "DESIGN_INCOMPLETE", compact.summary(), List.of(), List.of(), compact.handoffSummary(),
                    validateDesignGaps(compact.designGaps())).normalized();
            return new Result(incomplete, normalization.normalizations());
        }
        if (!"COMPILED".equals(compact.outcome())) {
            throw new BadRequestException("COMPILER_PLAN_OUTCOME_INVALID",
                    "Compiler semantic outcome must be COMPILED or DESIGN_INCOMPLETE");
        }
        if (compact.stages().size() > stageLimit && directSoftwareMode) {
            throw new BadRequestException("LARGE_TASK_MODE_REQUIRED",
                    "当前设计无法安全容纳在一个 1–6 Stage 工作包中，请显式改用大型任务模式");
        }
        if (compact.stages().isEmpty() || compact.stages().size() > stageLimit) {
            throw new BadRequestException("COMPILER_PLAN_STAGE_COUNT_INVALID",
                    "Compiler semantic planning must contain 1-" + stageLimit + " stages");
        }

        DesignerEvidenceIndexer.Index sourceIndex = evidenceIndexer.index(design);
        validateSemantics(compact, sourceIndex);
        List<PlannedStage> plannedStages = new ArrayList<>();
        List<AcceptanceEvidenceMapping> mappings = new ArrayList<>();
        int criterionOrdinal = 0;
        for (int stageIndex = 0; stageIndex < compact.stages().size(); stageIndex++) {
            int currentStageIndex = stageIndex;
            CompactStage stage = compact.stages().get(stageIndex);
            requireCompleteStage(stage);
            List<String> criterionIds = new ArrayList<>();
            for (int criterionIndex = 0; criterionIndex < stage.criteria().size(); criterionIndex++) {
                int currentCriterion = criterionIndex;
                CompactCriterion criterion = stage.criteria().get(criterionIndex);
                if (criterion == null || blank(criterion.description())) {
                    throw new BadRequestException("COMPILER_PLAN_CRITERION_INVALID",
                            "Every semantic criterion needs an observable description");
                }
                List<String> excerpts = sourceIndex.resolve(criterion.sourceRefs());
                String criterionId = AiSemanticContractCompiler.acceptanceId(
                        workPackage.packageId(), ++criterionOrdinal);
                criterionIds.add(criterionId);
                List<CompactEvidence> covering = stage.evidence().stream()
                        .filter(item -> item != null && item.covers().contains(currentCriterion)).toList();
                boolean machine = !covering.isEmpty();
                String mode = AiSemanticContractCompiler.verificationMode(
                        machine, criterion.judgeRubric(), criterion.judgeOnlyReason());
                List<CompactEvidence> focused = covering.stream()
                        .filter(item -> "FOCUSED_TEST".equals(item.kind())).toList();
                if (focused.size() > 1) {
                    throw new BadRequestException("COMPILER_PLAN_TEST_EVIDENCE_AMBIGUOUS",
                            "One criterion cannot derive a unique focused test from multiple candidates");
                }
                List<String> testCommand = focused.isEmpty() ? List.of()
                        : canonicalTestCommand(focused.getFirst().command());
                List<String> testTargets = focused.isEmpty() ? List.of()
                        : stage.implementationKind() == ImplementationKind.JAVA_PRODUCTION
                        ? ProcessCommandPolicy.explicitFocusedJavaTestTargets(testCommand)
                        : TestFrameworkPolicy.explicitTargets(testCommand);
                String strategy = covering.stream().map(CompactEvidence::kind).distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
                mappings.add(new AcceptanceEvidenceMapping(stageIndex, criterionId, criterion.description(),
                        excerpts.getFirst(), mode, criterion.judgeRubric(), criterion.judgeOnlyReason(), strategy,
                        testCommand, testTargets, excerpts));
            }
            List<LoopSpec.VerifierSpec> verifiers = stage.evidence().stream()
                    .map(evidence -> compileEvidence(currentStageIndex, stage, evidence, criterionIds)).toList();
            plannedStages.add(new PlannedStage(stage.objective(), stage.allowedPaths(), stage.forbiddenPaths(),
                    stage.deliverables(), verifiers, stage.verificationRuntime(), stage.implementationKind(),
                    workPackage.packageId()));
        }
        PackageCompilationPlanEnvelope plan = new PackageCompilationPlanEnvelope(
                2, "COMPILED", compact.summary(), plannedStages, mappings,
                compact.handoffSummary(), List.of()).normalized();
        return new Result(plan, normalization.normalizations());
    }

    List<DesignGap> validateDesignGaps(List<DesignGap> input) {
        if (input == null || input.isEmpty()) {
            throw new BadRequestException("DESIGN_GAPS_REQUIRED",
                    "DESIGN_INCOMPLETE requires at least one concrete design gap");
        }
        List<DesignGap> result = new ArrayList<>();
        for (DesignGap gap : input) {
            if (gap == null || gap.code() == null || !ALLOWED_DESIGN_GAPS.contains(gap.code())
                    || blank(gap.detail())) {
                throw new BadRequestException("DESIGN_GAP_INVALID",
                        "Design gaps must use a closed semantic gap code and a concrete detail");
            }
            result.add(new DesignGap(gap.code(), bounded(gap.detail().trim(), 1_000)));
        }
        return List.copyOf(result);
    }

    private Normalization normalize(CompactPackageCompilationPlan input) {
        CompactPackageCompilationPlan compact = input.normalized();
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        List<CompactStage> stages = new ArrayList<>();
        for (CompactStage stage : compact.stages()) {
            if (stage == null) {
                stages.add(null);
                continue;
            }
            Set<Integer> focusedIndexes = new LinkedHashSet<>();
            for (CompactEvidence evidence : stage.evidence()) {
                if (evidence != null && "FOCUSED_TEST".equals(evidence.kind())) {
                    focusedIndexes.addAll(evidence.covers());
                }
            }
            int[] remap = new int[stage.criteria().size()];
            java.util.Arrays.fill(remap, -1);
            List<CompactCriterion> criteria = normalizeCriteria(stage, focusedIndexes, remap, notes);
            List<CompactEvidence> evidenceItems = normalizeEvidence(stage, remap, notes);
            deriveUniqueFocusedTestCoverage(stage, criteria, evidenceItems, notes);
            stages.add(new CompactStage(stage.objective(), stage.implementationKind(), stage.allowedPaths(),
                    stage.forbiddenPaths(), stage.deliverables(), criteria, evidenceItems,
                    stage.verificationRuntime()));
        }
        CompactPackageCompilationPlan normalized = new CompactPackageCompilationPlan(
                compact.outcome(), compact.summary(), stages, compact.handoffSummary(), compact.designGaps())
                .normalized();
        return new Normalization(normalized, List.copyOf(notes));
    }

    private List<CompactCriterion> normalizeCriteria(CompactStage stage, Set<Integer> focusedIndexes,
                                                     int[] remap, Set<String> notes) {
        List<CompactCriterion> criteria = new ArrayList<>();
        for (int index = 0; index < stage.criteria().size(); index++) {
            CompactCriterion criterion = stage.criteria().get(index);
            if (!focusedIndexes.contains(index) && criterion != null
                    && AiSemanticContractCompiler.isEngineeringMetaCriterion(criterion.description())) {
                notes.add("ENGINEERING_META_CRITERIA_SUPPLEMENTALIZED");
                continue;
            }
            remap[index] = criteria.size();
            criteria.add(criterion);
        }
        return criteria;
    }

    private List<CompactEvidence> normalizeEvidence(CompactStage stage, int[] remap, Set<String> notes) {
        List<CompactEvidence> evidenceItems = new ArrayList<>();
        for (CompactEvidence evidence : stage.evidence()) {
            if (evidence == null) {
                evidenceItems.add(null);
                continue;
            }
            List<Integer> remappedCovers = new ArrayList<>();
            boolean removedCover = false;
            for (Integer original : evidence.covers()) {
                if (original != null && original >= 0 && original < remap.length && remap[original] >= 0) {
                    if (!remappedCovers.contains(remap[original])) remappedCovers.add(remap[original]);
                } else if (original != null && original >= 0 && original < remap.length) {
                    removedCover = true;
                } else {
                    remappedCovers.add(original);
                }
            }
            if (removedCover) notes.add("META_EVIDENCE_COVERAGE_REMOVED");
            if (removedCover && remappedCovers.isEmpty() && "SELF_CHECK".equals(evidence.kind())
                    && ProcessCommandPolicy.isSourceTextSearch(evidence.command())) {
                notes.add("UNEXECUTABLE_META_SELF_CHECK_DROPPED");
                continue;
            }
            evidenceItems.add(evidence.withCovers(remappedCovers));
        }
        return evidenceItems;
    }

    private void deriveUniqueFocusedTestCoverage(CompactStage stage, List<CompactCriterion> criteria,
                                                 List<CompactEvidence> evidenceItems, Set<String> notes) {
        if (stage.implementationKind() != ImplementationKind.JAVA_PRODUCTION) return;
        List<Integer> focusedEvidence = new ArrayList<>();
        for (int index = 0; index < evidenceItems.size(); index++) {
            CompactEvidence evidence = evidenceItems.get(index);
            if (evidence != null && "FOCUSED_TEST".equals(evidence.kind())) focusedEvidence.add(index);
        }
        if (focusedEvidence.size() != 1) return;
        int focusedIndex = focusedEvidence.getFirst();
        CompactEvidence focused = evidenceItems.get(focusedIndex);
        List<Integer> covers = new ArrayList<>(focused.covers());
        boolean changed = false;
        for (int criterionIndex = 0; criterionIndex < criteria.size(); criterionIndex++) {
            if (covers.contains(criterionIndex)) continue;
            int currentCriterionIndex = criterionIndex;
            CompactCriterion criterion = criteria.get(criterionIndex);
            boolean hasOtherMachineEvidence = evidenceItems.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(item -> !"FOCUSED_TEST".equals(item.kind()))
                    .anyMatch(item -> item.covers().contains(currentCriterionIndex));
            boolean explicitJudgeOnly = criterion != null && !blank(criterion.judgeRubric())
                    && !blank(criterion.judgeOnlyReason()) && !hasOtherMachineEvidence;
            if (!explicitJudgeOnly) {
                covers.add(criterionIndex);
                changed = true;
            }
        }
        if (changed) {
            evidenceItems.set(focusedIndex, focused.withCovers(covers));
            notes.add("UNIQUE_FOCUSED_TEST_COVERAGE_DERIVED");
        }
    }

    private void validateSemantics(CompactPackageCompilationPlan compact,
                                   DesignerEvidenceIndexer.Index sourceIndex) {
        List<SemanticIssue> issues = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < compact.stages().size(); stageIndex++) {
            CompactStage stage = compact.stages().get(stageIndex);
            String stagePath = "/stages/" + stageIndex;
            if (stage == null) {
                issues.add(new SemanticIssue("COMPILER_PLAN_STAGE_INVALID", stagePath, "stage must be an object"));
                continue;
            }
            validateStageEvidence(stage, stagePath, issues);
            validateStageCriteria(stage, stagePath, sourceIndex, issues);
        }
        throwIfInvalid(issues);
    }

    private void validateStageEvidence(CompactStage stage, String stagePath, List<SemanticIssue> issues) {
        for (int evidenceIndex = 0; evidenceIndex < stage.evidence().size(); evidenceIndex++) {
            CompactEvidence evidence = stage.evidence().get(evidenceIndex);
            String evidencePath = stagePath + "/evidence/" + evidenceIndex;
            if (evidence == null || blank(evidence.kind())) {
                issues.add(new SemanticIssue("COMPILER_PLAN_EVIDENCE_KIND_REQUIRED", evidencePath,
                        "evidence kind is required"));
                continue;
            }
            if (!COVERABLE_EVIDENCE.contains(evidence.kind()) && !SUPPLEMENTAL_EVIDENCE.contains(evidence.kind())) {
                issues.add(new SemanticIssue("COMPILER_PLAN_EVIDENCE_KIND_INVALID", evidencePath + "/kind",
                        "unsupported evidence kind: " + evidence.kind()));
            }
            if (SUPPLEMENTAL_EVIDENCE.contains(evidence.kind()) && !evidence.covers().isEmpty()) {
                issues.add(new SemanticIssue("COMPILER_PLAN_EVIDENCE_COVERAGE_INVALID", evidencePath + "/covers",
                        evidence.kind() + " is supplemental and cannot cover criteria"));
            }
            for (Integer criterionIndex : evidence.covers()) {
                if (criterionIndex == null || criterionIndex < 0 || criterionIndex >= stage.criteria().size()) {
                    issues.add(new SemanticIssue("COMPILER_PLAN_EVIDENCE_COVERAGE_INVALID",
                            evidencePath + "/covers", "unknown criterion index: " + criterionIndex));
                }
            }
            if ("SELF_CHECK".equals(evidence.kind())) validateSelfCheck(evidence, evidencePath, issues);
        }
    }

    private void validateSelfCheck(CompactEvidence evidence, String evidencePath, List<SemanticIssue> issues) {
        if (blank(evidence.successMarker())) {
            issues.add(new SemanticIssue("COMPILER_PLAN_SELF_CHECK_MARKER_REQUIRED",
                    evidencePath + "/successMarker", "SELF_CHECK requires an explicit success marker"));
        }
        String commandError = ProcessCommandPolicy.directCommandError(evidence.command());
        if (commandError != null) {
            issues.add(new SemanticIssue("COMPILER_PLAN_SELF_CHECK_COMMAND_INVALID",
                    evidencePath + "/command", commandError));
        } else if (ProcessCommandPolicy.isSourceTextSearch(evidence.command())) {
            issues.add(new SemanticIssue("COMPILER_PLAN_SELF_CHECK_COMMAND_INVALID", evidencePath + "/command",
                    "source-text search cannot emit trustworthy positive runtime evidence"));
        }
    }

    private void validateStageCriteria(CompactStage stage, String stagePath,
                                       DesignerEvidenceIndexer.Index sourceIndex, List<SemanticIssue> issues) {
        for (int criterionIndex = 0; criterionIndex < stage.criteria().size(); criterionIndex++) {
            CompactCriterion criterion = stage.criteria().get(criterionIndex);
            String criterionPath = stagePath + "/criteria/" + criterionIndex;
            if (criterion == null || blank(criterion.description())) {
                issues.add(new SemanticIssue("COMPILER_PLAN_CRITERION_INVALID", criterionPath,
                        "criterion needs an observable description"));
                continue;
            }
            try {
                sourceIndex.resolve(criterion.sourceRefs());
            } catch (BadRequestException invalid) {
                issues.add(new SemanticIssue(invalid.code(), criterionPath + "/sourceRefs",
                        safeMessage(invalid.getMessage())));
            }
            List<Integer> covering = evidenceIndexes(stage, criterionIndex, null);
            List<Integer> focused = evidenceIndexes(stage, criterionIndex, "FOCUSED_TEST");
            boolean machine = !covering.isEmpty();
            boolean judge = !blank(criterion.judgeRubric());
            if (!machine && !judge) {
                issues.add(new SemanticIssue("COMPILER_PLAN_CRITERION_UNCOVERED", criterionPath,
                        "criterion needs focused/native machine evidence or a judgeRubric"));
            } else if (!machine && blank(criterion.judgeOnlyReason())) {
                issues.add(new SemanticIssue("COMPILER_PLAN_JUDGE_REASON_REQUIRED",
                        criterionPath + "/judgeOnlyReason", "Judge-only criterion needs judgeOnlyReason"));
            }
            if (focused.size() > 1) {
                issues.add(new SemanticIssue("COMPILER_PLAN_TEST_EVIDENCE_AMBIGUOUS", criterionPath,
                        "criterion is covered by multiple focused tests"));
            }
            if (stage.implementationKind() == ImplementationKind.JAVA_PRODUCTION && machine && focused.isEmpty()) {
                issues.add(new SemanticIssue("COMPILER_PLAN_JAVA_TEST_EVIDENCE_REQUIRED", criterionPath,
                        "JAVA_PRODUCTION machine criterion needs one focused Maven/Gradle test"));
            }
            if (focused.size() == 1) validateFocusedCommand(stage, stagePath, focused.getFirst(), issues);
        }
    }

    private List<Integer> evidenceIndexes(CompactStage stage, int criterionIndex, String requiredKind) {
        List<Integer> indexes = new ArrayList<>();
        for (int evidenceIndex = 0; evidenceIndex < stage.evidence().size(); evidenceIndex++) {
            CompactEvidence evidence = stage.evidence().get(evidenceIndex);
            if (evidence == null || !COVERABLE_EVIDENCE.contains(evidence.kind())
                    || !evidence.covers().contains(criterionIndex)) continue;
            if (requiredKind == null || requiredKind.equals(evidence.kind())) indexes.add(evidenceIndex);
        }
        return indexes;
    }

    private void validateFocusedCommand(CompactStage stage, String stagePath, int evidenceIndex,
                                        List<SemanticIssue> issues) {
        try {
            List<String> command = canonicalTestCommand(stage.evidence().get(evidenceIndex).command());
            List<String> targets = stage.implementationKind() == ImplementationKind.JAVA_PRODUCTION
                    ? ProcessCommandPolicy.explicitFocusedJavaTestTargets(command)
                    : TestFrameworkPolicy.explicitTargets(command);
            if (targets.isEmpty()) {
                issues.add(new SemanticIssue("COMPILER_PLAN_JAVA_TEST_EVIDENCE_REQUIRED",
                        stagePath + "/evidence/" + evidenceIndex + "/command",
                        "focused test command needs an explicit target for its recognized framework"));
            }
        } catch (BadRequestException invalid) {
            issues.add(new SemanticIssue(invalid.code(), stagePath + "/evidence/" + evidenceIndex + "/command",
                    safeMessage(invalid.getMessage())));
        }
    }

    private LoopSpec.VerifierSpec compileEvidence(int stageIndex, CompactStage stage,
                                                  CompactEvidence evidence, List<String> criterionIds) {
        if (evidence == null || blank(evidence.kind())) {
            throw new BadRequestException("COMPILER_PLAN_EVIDENCE_KIND_REQUIRED",
                    "Evidence kind is required at stage " + stageIndex);
        }
        if (!COVERABLE_EVIDENCE.contains(evidence.kind()) && !SUPPLEMENTAL_EVIDENCE.contains(evidence.kind())) {
            throw new BadRequestException("COMPILER_PLAN_EVIDENCE_KIND_INVALID",
                    "Unsupported evidence kind: " + evidence.kind());
        }
        if (SUPPLEMENTAL_EVIDENCE.contains(evidence.kind()) && !evidence.covers().isEmpty()) {
            throw new BadRequestException("COMPILER_PLAN_EVIDENCE_COVERAGE_INVALID",
                    evidence.kind() + " is supplemental and cannot cover business criteria");
        }
        LinkedHashSet<String> covers = new LinkedHashSet<>();
        for (Integer index : evidence.covers()) {
            if (index == null || index < 0 || index >= criterionIds.size()) {
                throw new BadRequestException("COMPILER_PLAN_EVIDENCE_COVERAGE_INVALID",
                        "Evidence covers an unknown criterion index at stage " + stageIndex);
            }
            covers.add(criterionIds.get(index));
        }
        String type = switch (evidence.kind()) {
            case "FOCUSED_TEST", "FULL_TEST", "BUILD", "SELF_CHECK" -> "PROCESS";
            default -> evidence.kind();
        };
        String purpose = switch (evidence.kind()) {
            case "FOCUSED_TEST", "FULL_TEST" -> "TEST";
            case "BUILD" -> "BUILD";
            case "SELF_CHECK" -> "SELF_CHECK";
            default -> null;
        };
        if ("SELF_CHECK".equals(evidence.kind())) requireValidSelfCheck(evidence);
        List<String> command = "PROCESS".equals(type) ? canonicalTestCommand(evidence.command()) : evidence.command();
        List<String> targets = "FOCUSED_TEST".equals(evidence.kind())
                ? (stage.implementationKind() == ImplementationKind.JAVA_PRODUCTION
                ? ProcessCommandPolicy.explicitFocusedJavaTestTargets(command)
                : TestFrameworkPolicy.explicitTargets(command)) : List.of();
        String output = "SELF_CHECK".equals(evidence.kind()) ? evidence.successMarker() : null;
        List<String> allowed = evidence.allowedPaths().isEmpty() && "GIT_DIFF".equals(type)
                ? stage.allowedPaths() : evidence.allowedPaths();
        List<String> forbidden = evidence.forbiddenPaths().isEmpty() && "GIT_DIFF".equals(type)
                ? stage.forbiddenPaths() : evidence.forbiddenPaths();
        return new LoopSpec.VerifierSpec(type, command, evidence.path(), evidence.requireChanges(), allowed,
                forbidden, evidence.forbidDeletes(), output, evidence.url(), evidence.httpMethod(),
                evidence.expectedStatus(), evidence.jsonPath(), evidence.expectedValue(), evidence.matchMode(),
                evidence.expectedContent(), evidence.expectedSha256(), evidence.sql(), evidence.expectedRowCount(),
                evidence.assertions(), List.copyOf(covers), purpose, targets,
                evidence.documentAssertions(), evidence.tabularAssertions());
    }

    private void requireValidSelfCheck(CompactEvidence evidence) {
        String commandError = ProcessCommandPolicy.directCommandError(evidence.command());
        if (commandError != null || ProcessCommandPolicy.isSourceTextSearch(evidence.command())) {
            throw new BadRequestException("COMPILER_PLAN_SELF_CHECK_COMMAND_INVALID",
                    commandError == null ? "SELF_CHECK source-text search cannot prove runtime behavior" : commandError);
        }
        if (blank(evidence.successMarker())) {
            throw new BadRequestException("COMPILER_PLAN_SELF_CHECK_MARKER_REQUIRED",
                    "SELF_CHECK requires an explicit success marker");
        }
    }

    private void requireCompleteStage(CompactStage stage) {
        if (stage == null || blank(stage.objective()) || stage.implementationKind() == null
                || stage.deliverables().isEmpty() || stage.criteria().isEmpty()) {
            throw new BadRequestException("COMPILER_PLAN_STAGE_INVALID",
                    "Every semantic stage needs objective, implementationKind, deliverables, and criteria");
        }
    }

    private void throwIfInvalid(List<SemanticIssue> issues) {
        if (issues.isEmpty()) return;
        LinkedHashMap<String, SemanticIssue> unique = new LinkedHashMap<>();
        for (SemanticIssue issue : issues) {
            unique.putIfAbsent(issue.code() + "|" + issue.path() + "|" + issue.detail(), issue);
        }
        List<SemanticIssue> result = List.copyOf(unique.values());
        if (result.size() == 1) {
            SemanticIssue issue = result.getFirst();
            throw new BadRequestException(issue.code(), issue.path() + ": " + issue.detail());
        }
        String detail = result.stream().map(issue -> "[" + issue.code() + "] " + issue.path() + ": "
                + issue.detail()).collect(java.util.stream.Collectors.joining("; "));
        throw new BadRequestException("COMPILER_PLAN_SEMANTIC_INVALID",
                result.size() + " semantic issues: " + bounded(detail, 8_000));
    }

    private List<String> canonicalTestCommand(List<String> command) {
        if (command == null || command.isEmpty()) return List.of();
        ProcessCommandPolicy.Normalization normalization = ProcessCommandPolicy.normalizeMavenCommand(command);
        return normalization.failure() == null ? normalization.command() : List.copyOf(command);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeMessage(String message) {
        return blank(message) ? "OpenCode read-only workflow failed"
                : bounded(message.replaceAll("[\\r\\n]+", " ").trim(), 500);
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    record Result(PackageCompilationPlanEnvelope plan, List<String> normalizations) { }
    private record Normalization(CompactPackageCompilationPlan plan, List<String> normalizations) { }
    private record SemanticIssue(String code, String path, String detail) { }
}
