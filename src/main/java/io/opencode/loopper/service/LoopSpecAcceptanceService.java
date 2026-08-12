package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.verification.ProcessCommandPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Authoritative v2 acceptance coverage analysis shared by every transport boundary. */
@Service
public class LoopSpecAcceptanceService {
    private static final String RUNTIME_URL_PREFIX = "http://127.0.0.1:{{LOOPPER_PORT}}";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");
    private static final Set<String> ALLOWED_RUNTIME_PLACEHOLDERS = Set.of("LOOPPER_PORT", "LOOPPER_TEMP");

    public Assessment assess(LoopSpec spec, List<String> baseErrors, boolean allowPersistedLegacy) {
        List<String> errors = new ArrayList<>(baseErrors == null ? List.of() : baseErrors);
        if (spec == null) return new Assessment(false, null, false, List.of("LoopSpec is required"), List.of());
        boolean legacy = "v1".equals(spec.schemaVersion());
        if (legacy) {
            if (!allowPersistedLegacy) errors.add("schemaVersion: new LoopSpecs must use v2");
            return new Assessment(errors.isEmpty(), spec.schemaVersion(), true, List.copyOf(errors), legacyStages(spec));
        }
        if (!"v2".equals(spec.schemaVersion())) {
            errors.add("schemaVersion: only persisted v1 and new v2 are supported");
            return new Assessment(false, spec.schemaVersion(), false, List.copyOf(errors), List.of());
        }

        List<StageAssessment> stages = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            LoopSpec.StageSpec stage = spec.stages().get(stageIndex);
            String stagePath = "stages[" + stageIndex + "]";
            Map<String, LoopSpec.AcceptanceCriterion> criteria = new LinkedHashMap<>();
            for (int criterionIndex = 0; criterionIndex < stage.acceptanceCriteria().size(); criterionIndex++) {
                LoopSpec.AcceptanceCriterion criterion = stage.acceptanceCriteria().get(criterionIndex);
                String path = stagePath + ".acceptanceCriteria[" + criterionIndex + "]";
                if (blank(criterion.id())) continue;
                if (criteria.putIfAbsent(criterion.id(), criterion) != null) {
                    errors.add(path + ".id: acceptance criterion id must be unique within the stage");
                }
            }
            if (stage.acceptanceCriteria().isEmpty()) {
                errors.add(stagePath + ".acceptanceCriteria: v2 requires at least one observable acceptance criterion");
            }

            boolean runtimeValid = validateRuntime(stage, stagePath, errors);
            Map<String, List<Integer>> machineCoverage = new LinkedHashMap<>();
            criteria.keySet().forEach(id -> machineCoverage.put(id, new ArrayList<>()));
            List<VerifierAssessment> verifierAssessments = new ArrayList<>();
            boolean hasBlockingDeterministicVerifier = false;
            for (int verifierIndex = 0; verifierIndex < stage.verifiers().size(); verifierIndex++) {
                LoopSpec.VerifierSpec verifier = stage.verifiers().get(verifierIndex);
                String path = stagePath + ".verifiers[" + verifierIndex + "]";
                String verifierType = text(verifier.type());
                Classification classification = classify(verifier);
                List<String> reasons = new ArrayList<>(classification.reasons());
                boolean processValid = validateProcess(verifier, classification, path, errors, reasons);
                boolean runtimeBound = validateRuntimeBinding(stage, verifier, path, errors, reasons);
                boolean runtimeDependent = Set.of("HTTP_STATUS", "JSON_PATH", "BROWSER").contains(verifierType);
                boolean runtimeReady = !runtimeDependent || (runtimeValid && runtimeBound);
                boolean behavior = classification.category() == Category.BEHAVIOR
                        && classification.valid() && processValid && runtimeReady;
                boolean blocking = classification.valid() && processValid
                        && classification.category() != Category.ADVISORY
                        && runtimeReady;
                if (blocking) {
                    hasBlockingDeterministicVerifier = true;
                }
                if (behavior && verifier.criterionIds().isEmpty()) {
                    errors.add(path + ".criterionIds: behavior verifier must cover at least one acceptance criterion");
                }
                Set<String> seenIds = new HashSet<>();
                for (int idIndex = 0; idIndex < verifier.criterionIds().size(); idIndex++) {
                    String criterionId = verifier.criterionIds().get(idIndex);
                    if (!seenIds.add(criterionId)) {
                        errors.add(path + ".criterionIds[" + idIndex + "]: duplicate criterion id " + criterionId);
                    } else if (!criteria.containsKey(criterionId)) {
                        errors.add(path + ".criterionIds[" + idIndex + "]: unknown acceptance criterion " + criterionId);
                    } else if (behavior) {
                        machineCoverage.get(criterionId).add(verifierIndex);
                    }
                }
                verifierAssessments.add(new VerifierAssessment(verifierIndex, verifierType,
                        classification.category(), blocking,
                        verifier.criterionIds(), String.join("; ", reasons)));
            }
            if (!hasBlockingDeterministicVerifier) {
                errors.add(stagePath + ".verifiers: v2 requires at least one blocking deterministic verifier even when criteria use JUDGE review");
            }

            List<CriterionAssessment> criterionAssessments = new ArrayList<>();
            for (LoopSpec.AcceptanceCriterion criterion : stage.acceptanceCriteria()) {
                List<Integer> verifierIndexes = machineCoverage.getOrDefault(criterion.id(), List.of());
                boolean machineCovered = !verifierIndexes.isEmpty();
                String mode = text(criterion.verificationMode());
                boolean modeValid = Set.of("MACHINE", "JUDGE", "BOTH").contains(mode);
                if (!modeValid) errors.add(stagePath + ".acceptanceCriteria[" + criterion.id() + "].verificationMode: must be MACHINE, JUDGE, or BOTH");
                boolean judgePlanned = Set.of("JUDGE", "BOTH").contains(mode) && !blank(criterion.judgeRubric());
                if (Set.of("JUDGE", "BOTH").contains(mode) && blank(criterion.judgeRubric())) {
                    errors.add(stagePath + ".acceptanceCriteria[" + criterion.id() + "].judgeRubric: " + mode + " requires an explicit AI review rubric");
                }
                if ("JUDGE".equals(mode) && blank(criterion.judgeOnlyReason())) {
                    errors.add(stagePath + ".acceptanceCriteria[" + criterion.id() + "].judgeOnlyReason: JUDGE requires a reason why deterministic behavior evidence is not reliable");
                }
                if ("JUDGE".equals(mode) && machineCovered) {
                    errors.add(stagePath + ".acceptanceCriteria[" + criterion.id() + "].verificationMode: machine evidence is already mapped; use BOTH instead of JUDGE");
                }
                if (Set.of("MACHINE", "BOTH").contains(mode) && !machineCovered && !blank(criterion.id())) {
                    errors.add(stagePath + ".acceptanceCriteria[" + criterion.id() + "]: no valid BEHAVIOR verifier provides required machine coverage");
                }
                boolean overallPlanned = modeValid && switch (mode) {
                    case "MACHINE" -> machineCovered;
                    case "JUDGE" -> judgePlanned && !blank(criterion.judgeOnlyReason());
                    case "BOTH" -> machineCovered && judgePlanned;
                    default -> false;
                };
                criterionAssessments.add(new CriterionAssessment(criterion.id(), criterion.description(), mode,
                        machineCovered, machineCovered, judgePlanned, overallPlanned, criterion.judgeRubric(),
                        criterion.judgeOnlyReason(), List.copyOf(verifierIndexes)));
            }
            stages.add(new StageAssessment(stageIndex, List.copyOf(criterionAssessments),
                    List.copyOf(verifierAssessments)));
        }
        int judgeContractBytes = JudgePromptPolicy.utf8Bytes(JudgePromptPolicy.contract(spec));
        if (judgeContractBytes > JudgePromptPolicy.MAX_CONTRACT_UTF8_BYTES) {
            errors.add("judgeContract: confirmed goal, context, and all JUDGE/BOTH criteria use "
                    + judgeContractBytes + " UTF-8 bytes; maximum is "
                    + JudgePromptPolicy.MAX_CONTRACT_UTF8_BYTES);
        }
        return new Assessment(errors.isEmpty(), spec.schemaVersion(), false, List.copyOf(errors), List.copyOf(stages));
    }

    public Classification classify(LoopSpec.VerifierSpec verifier) {
        String type = verifier.type() == null ? "" : verifier.type();
        return switch (type) {
            case "PROCESS" -> classifyProcess(verifier);
            case "HTTP_STATUS", "JSON_PATH", "BROWSER", "DATABASE_QUERY", "FILE_CONTENT", "FILE_HASH" ->
                    new Classification(Category.BEHAVIOR, true, List.of("direct observable behavior evidence"));
            case "GIT_DIFF" -> new Classification(Category.SCOPE, true, List.of("change scope only"));
            case "FILE_NOT_EXISTS" -> new Classification(Category.SAFETY, true, List.of("safety invariant only"));
            case "JUNIT_XML" -> new Classification(Category.REPORT, true, List.of("structured report evidence only"));
            case "FILE_EXISTS" -> new Classification(Category.ADVISORY, true, List.of("legacy non-blocking audit hint"));
            default -> new Classification(Category.ADVISORY, false, List.of("unsupported verifier type"));
        };
    }

    private Classification classifyProcess(LoopSpec.VerifierSpec verifier) {
        String purpose = text(verifier.processPurpose());
        if ("TEST".equals(purpose)) {
            ProcessCommandPolicy.TestCommandAssessment test = ProcessCommandPolicy.assessTestCommand(verifier.command());
            return new Classification(test.recognized() && !test.skipped() ? Category.BEHAVIOR : Category.BUILD,
                    test.recognized() && !test.skipped(), List.of(test.reason()));
        }
        if ("SELF_CHECK".equals(purpose)) {
            boolean valid = !blank(verifier.outputContains());
            return new Classification(valid ? Category.BEHAVIOR : Category.BUILD, valid,
                    List.of(valid ? "self-check has an explicit success marker" : "self-check lacks outputContains"));
        }
        return new Classification(Category.BUILD, "BUILD".equals(purpose),
                List.of("compile/build/static-quality command"));
    }

    private boolean validateProcess(LoopSpec.VerifierSpec verifier, Classification classification,
                                    String path, List<String> errors, List<String> reasons) {
        if (!"PROCESS".equals(text(verifier.type()))) return true;
        boolean valid = true;
        String purpose = text(verifier.processPurpose());
        String commandError = ProcessCommandPolicy.directCommandError(verifier.command());
        if (commandError != null) {
            errors.add(path + ".command: " + commandError);
            valid = false;
        }
        if (!Set.of("BUILD", "TEST", "SELF_CHECK").contains(purpose)) {
            errors.add(path + ".processPurpose: v2 PROCESS requires BUILD, TEST, or SELF_CHECK");
            valid = false;
        }
        if ("TEST".equals(purpose)) {
            if (verifier.testTargets().isEmpty()) {
                errors.add(path + ".testTargets: TEST requires explicit test targets; planned tests may be new deliverables in this stage");
                valid = false;
            }
            ProcessCommandPolicy.TestCommandAssessment test = ProcessCommandPolicy.assessTestCommand(verifier.command());
            if (!test.recognized()) {
                errors.add(path + ".command: TEST requires a recognized Maven, Gradle, or npm test invocation");
                valid = false;
            }
            if (test.skipped()) {
                errors.add(path + ".command: TEST must not disable or skip tests, or ignore missing target tests");
                valid = false;
            }
        }
        if ("SELF_CHECK".equals(purpose) && blank(verifier.outputContains())) {
            errors.add(path + ".outputContains: SELF_CHECK requires an explicit success marker");
            valid = false;
        }
        if ("SELF_CHECK".equals(purpose) && sourceTextSearch(verifier.command())) {
            errors.add(path + ".command: source-text search cannot prove runtime behavior; use a focused test or native behavior verifier");
            valid = false;
        }
        if (!classification.valid() && reasons.isEmpty()) reasons.add("invalid PROCESS acceptance contract");
        return valid;
    }

    private boolean validateRuntime(LoopSpec.StageSpec stage, String path, List<String> errors) {
        LoopSpec.VerificationRuntime runtime = stage.verificationRuntime();
        if (runtime == null) return true;
        boolean valid = true;
        if (runtime.startCommand().isEmpty()) {
            errors.add(path + ".verificationRuntime.startCommand: managed runtime requires a direct argv command");
            return false;
        }
        String commandError = ProcessCommandPolicy.directCommandError(runtime.startCommand());
        if (commandError != null) {
            errors.add(path + ".verificationRuntime.startCommand: " + commandError);
            valid = false;
        }
        boolean hasPort = false;
        for (int index = 0; index < runtime.startCommand().size(); index++) {
            String argument = runtime.startCommand().get(index);
            if (argument.contains("{{LOOPPER_PORT}}")) hasPort = true;
            Matcher matcher = PLACEHOLDER.matcher(argument);
            while (matcher.find()) {
                if (!ALLOWED_RUNTIME_PLACEHOLDERS.contains(matcher.group(1))) {
                    errors.add(path + ".verificationRuntime.startCommand[" + index
                            + "]: unsupported placeholder {{" + matcher.group(1) + "}}");
                    valid = false;
                }
            }
        }
        if (!hasPort) {
            errors.add(path + ".verificationRuntime.startCommand: command must consume {{LOOPPER_PORT}}");
            valid = false;
        }
        if (runtime.readiness() == null) {
            errors.add(path + ".verificationRuntime.readiness: managed runtime requires readiness probing");
            valid = false;
        } else if (blank(runtime.readiness().path()) || !runtime.readiness().path().startsWith("/")
                || runtime.readiness().path().contains("://") || runtime.readiness().path().contains("..")) {
            errors.add(path + ".verificationRuntime.readiness.path: readiness must be a safe relative HTTP path beginning with /");
            valid = false;
        }
        return valid;
    }

    private boolean validateRuntimeBinding(LoopSpec.StageSpec stage, LoopSpec.VerifierSpec verifier,
                                           String path, List<String> errors, List<String> reasons) {
        if (!Set.of("HTTP_STATUS", "JSON_PATH", "BROWSER").contains(text(verifier.type()))) return true;
        boolean valid = stage.verificationRuntime() != null && verifier.url() != null
                && (verifier.url().equals(RUNTIME_URL_PREFIX) || verifier.url().startsWith(RUNTIME_URL_PREFIX + "/"));
        if (!valid) {
            if (!verifier.criterionIds().isEmpty()) {
                errors.add(path + ".url: network verifier mapped to acceptance criteria must use "
                        + RUNTIME_URL_PREFIX + " and the stage managed runtime");
            }
            reasons.add("not bound to this stage managed runtime");
        }
        return valid;
    }

    private boolean sourceTextSearch(List<String> command) {
        if (command == null || command.isEmpty()) return false;
        return Set.of("rg", "rg.exe", "grep", "grep.exe", "egrep", "fgrep", "findstr", "findstr.exe")
                .contains(baseName(command.getFirst()));
    }

    private List<StageAssessment> legacyStages(LoopSpec spec) {
        List<StageAssessment> stages = new ArrayList<>();
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            List<VerifierAssessment> verifiers = new ArrayList<>();
            for (int verifierIndex = 0; verifierIndex < spec.stages().get(stageIndex).verifiers().size(); verifierIndex++) {
                LoopSpec.VerifierSpec verifier = spec.stages().get(stageIndex).verifiers().get(verifierIndex);
                Classification classification = classify(verifier);
                verifiers.add(new VerifierAssessment(verifierIndex, verifier.type(), classification.category(),
                        !"FILE_EXISTS".equals(verifier.type()), verifier.criterionIds(), "legacy v1 contract"));
            }
            stages.add(new StageAssessment(stageIndex, List.of(), List.copyOf(verifiers)));
        }
        return List.copyOf(stages);
    }

    private String baseName(String executable) {
        if (executable == null) return "";
        String normalized = executable.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String text(String value) { return value == null ? "" : value; }

    public enum Category { BUILD, BEHAVIOR, SCOPE, SAFETY, REPORT, ADVISORY }
    public record Classification(Category category, boolean valid, List<String> reasons) { }
    public record Assessment(boolean valid, String schemaVersion, boolean legacy, List<String> errors,
                             List<StageAssessment> stageAssessments) { }
    public record StageAssessment(int stageIndex, List<CriterionAssessment> criteria,
                                  List<VerifierAssessment> verifiers) { }
    public record CriterionAssessment(String id, String description, String verificationMode,
                                      boolean covered,
                                      boolean machineCovered, boolean judgePlanned, boolean overallPlanned,
                                      String judgeRubric, String judgeOnlyReason,
                                      List<Integer> verifierIndexes) { }
    public record VerifierAssessment(int index, String type, Category category, boolean blocking,
                                     List<String> criterionIds, String reason) { }
}
