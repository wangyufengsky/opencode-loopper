package io.opencode.loopper.service;

import java.util.List;
import java.util.Objects;

/** Deterministic dual-entry compilation boundary for ROLLING_PACKAGE_PLAN_V1. */
public interface RollingPackagePlanCompilation {
    Result compileCandidate(Input input, String candidateJson);

    Result compilePlan(Input input, List<RollingPackagePlanService.PlanPackage> packages);

    record Input(List<CurrentPackage> currentPackages, List<String> frozenPackageKeys,
                 List<String> allowedRequirementRefs) {
        public Input {
            currentPackages = currentPackages == null ? List.of() : List.copyOf(currentPackages);
            frozenPackageKeys = frozenPackageKeys == null ? List.of() : List.copyOf(frozenPackageKeys);
            allowedRequirementRefs = allowedRequirementRefs == null ? List.of() : List.copyOf(allowedRequirementRefs);
            currentPackages.forEach(item -> Objects.requireNonNull(item, "currentPackages item"));
        }
    }

    record CurrentPackage(String runId, String packageKey, List<String> dependencies) {
        public CurrentPackage {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(packageKey, "packageKey");
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    enum Outcome { ACCEPTED, REJECTED, NEEDS_INPUT }

    enum ProblemClass { MECHANICAL, SEMANTIC, SECURITY }

    record Problem(String code, String pointer, String staticDetail, List<String> allowedValues,
                   ProblemClass problemClass) {
        public Problem {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            Objects.requireNonNull(problemClass, "problemClass");
        }

        MachineCandidateSubmission.Problem submissionProblem() {
            return new MachineCandidateSubmission.Problem(code, pointer, staticDetail, allowedValues);
        }
    }

    record DependencyChange(String packageKey, List<String> before, List<String> after) {
        public DependencyChange {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    record Split(String source, List<String> targets) {
        public Split { targets = List.copyOf(targets); }
    }

    record Merge(String target, List<String> sources) {
        public Merge { sources = List.copyOf(sources); }
    }

    record Impact(List<String> before, List<String> after, List<String> added, List<String> removed,
                  List<String> reordered, List<DependencyChange> dependencyChanges,
                  List<Split> split, List<Merge> merged) {
        public Impact {
            before = List.copyOf(before);
            after = List.copyOf(after);
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            reordered = List.copyOf(reordered);
            dependencyChanges = List.copyOf(dependencyChanges);
            split = List.copyOf(split);
            merged = List.copyOf(merged);
        }
    }

    record Result(Outcome outcome, String canonicalCandidateJson,
                  List<RollingPackagePlanService.PlanPackage> planPackages,
                  String canonicalPlanJson, Impact impact, String canonicalImpactJson,
                  List<Problem> problems) {
        public Result {
            planPackages = planPackages == null ? List.of() : List.copyOf(planPackages);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean accepted() { return outcome == Outcome.ACCEPTED; }

        public boolean retryable() {
            return outcome == Outcome.REJECTED && !problems.isEmpty()
                    && problems.stream().allMatch(problem -> problem.problemClass() == ProblemClass.MECHANICAL);
        }
    }
}
