package io.opencode.loopper.service;

import java.util.List;
import java.util.Objects;

/** Single deterministic authority shared by legacy and MCP REVIEWER_REPORT_V1 entries. */
public interface ReviewerReportCompilation {
    Result compile(Input input);

    record Input(Candidate candidate, List<SourceFile> sourceFiles) {
        public Input {
            Objects.requireNonNull(candidate, "candidate");
            sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
            sourceFiles.forEach(item -> Objects.requireNonNull(item, "sourceFiles item"));
        }
    }

    record Candidate(String title, String summary, List<Finding> findings, List<String> limitations) {
        public Candidate {
            findings = findings == null ? List.of() : List.copyOf(findings);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    record Finding(String severity, String title, String detail, String path, int line,
                   String recommendation) { }

    record SourceFile(String path, long sizeBytes, long lineCount, String sha256) { }

    record Evidence(String path, int line, String sha256) { }

    enum ProblemClass { MECHANICAL, SECURITY }

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

    record Result(String canonicalCandidateJson, String canonicalFindingsJson, String markdown,
                  List<Evidence> evidence, String contentSha256, String sourceSnapshotSha256,
                  String canonicalResultSha256, List<Problem> problems) {
        public Result {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean accepted() { return problems.isEmpty(); }

        public boolean retryable() {
            return !accepted() && problems.stream().allMatch(problem -> problem.problemClass() == ProblemClass.MECHANICAL);
        }
    }
}
