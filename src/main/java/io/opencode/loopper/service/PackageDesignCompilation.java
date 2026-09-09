package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.util.List;
import java.util.Objects;

/** Deterministic boundary for PACKAGE_DESIGN_V1 and its Markdown compatibility adapter. */
public interface PackageDesignCompilation {
    Result compileCandidate(Input input, String candidateJson);
    Result compileMarkdown(Input input, String markdown);

    record Input(
            DesignWorkPackageRow workPackage,
            String requirementText,
            WorkPackageRoleService.View role,
            List<String> scopeIn,
            List<String> scopeOut,
            List<String> deliverables,
            int stageLimit,
            boolean directSoftwareMode,
            String semanticContractVersion,
            PackageDesignEvidencePreparation.Snapshot repositoryEvidence,
            java.util.Map<String, String> confirmedDecisions) {
        public Input(DesignWorkPackageRow workPackage, String requirementText, WorkPackageRoleService.View role,
                List<String> scopeIn, List<String> scopeOut, List<String> deliverables, int stageLimit,
                boolean directSoftwareMode, String semanticContractVersion) {
            this(workPackage, requirementText, role, scopeIn, scopeOut, deliverables, stageLimit, directSoftwareMode, semanticContractVersion, null, java.util.Map.of());
        }
        public Input(DesignWorkPackageRow workPackage, String requirementText, WorkPackageRoleService.View role,
                List<String> scopeIn, List<String> scopeOut, List<String> deliverables, int stageLimit, boolean directSoftwareMode) {
            this(workPackage, requirementText, role, scopeIn, scopeOut, deliverables, stageLimit, directSoftwareMode, "PACKAGE_DESIGN_V1", null, java.util.Map.of());
        }
        Input withContract(String version) {
            return new Input(workPackage, requirementText, role, scopeIn, scopeOut, deliverables, stageLimit, directSoftwareMode, version, repositoryEvidence, confirmedDecisions);
        }
        Input withEvidence(PackageDesignEvidencePreparation.Snapshot value) {
            return new Input(workPackage, requirementText, role, scopeIn, scopeOut, deliverables, stageLimit, directSoftwareMode, semanticContractVersion, value, confirmedDecisions);
        }
        Input withDecisions(java.util.Map<String, String> decisions) {
            return new Input(workPackage, requirementText, role, scopeIn, scopeOut, deliverables, stageLimit, directSoftwareMode,
                    semanticContractVersion, repositoryEvidence, decisions);
        }
        public Input {
            confirmedDecisions = confirmedDecisions == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(confirmedDecisions));
            Objects.requireNonNull(workPackage, "workPackage");
            Objects.requireNonNull(role, "role");
            scopeIn = scopeIn == null ? List.of() : List.copyOf(scopeIn);
            scopeOut = scopeOut == null ? List.of() : List.copyOf(scopeOut);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
            if (stageLimit < 1 || stageLimit > 6) {
                throw new IllegalArgumentException("stageLimit must be between 1 and 6");
            }
        }
    }

    enum Outcome { ACCEPTED, REJECTED, NEEDS_INPUT }
    enum ProblemClass { MECHANICAL, CORRECTABLE, HUMAN_REQUIRED, SECURITY, SYSTEM }

    record Problem(
            String code,
            String pointer,
            String staticDetail,
            List<String> allowedValues,
            ProblemClass problemClass,
            boolean fallbackEligible,
            String expected,
            String actual,
            String repairHint) {
        public Problem {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            Objects.requireNonNull(problemClass, "problemClass");
        }

        Problem(String code, String pointer, String staticDetail, List<String> allowedValues,
                ProblemClass problemClass, boolean fallbackEligible) {
            this(code, pointer, staticDetail, allowedValues, problemClass, fallbackEligible,
                    null, null, null);
        }

        MachineCandidateSubmission.Problem submissionProblem() {
            return new MachineCandidateSubmission.Problem(code, pointer, staticDetail, allowedValues,
                    problemClass == ProblemClass.SYSTEM ? "compiledPlan" : "candidate",
                    problemClass == ProblemClass.SYSTEM ? MachineCandidateSubmission.ProblemCategory.INTERNAL : null,
                    expected, actual, repairHint);
        }
    }

    record Result(
            Outcome outcome,
            String canonicalCandidateJson,
            String canonicalMarkdown,
            DesignerSemanticContracts.PackageCompilationPlanEnvelope compiledPlan,
            String compiledResultJson,
            List<Problem> problems) {
        public Result {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean accepted() { return outcome == Outcome.ACCEPTED; }
        public boolean retryable() {
            return outcome == Outcome.REJECTED && !problems.isEmpty()
                    && problems.stream().allMatch(problem -> problem.problemClass() == ProblemClass.MECHANICAL
                            || problem.problemClass() == ProblemClass.CORRECTABLE);
        }
    }
}
