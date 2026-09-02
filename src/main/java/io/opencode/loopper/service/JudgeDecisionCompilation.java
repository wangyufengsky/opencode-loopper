package io.opencode.loopper.service;

import java.util.List;
import java.util.Objects;

/** Single deterministic authority for legacy and MCP Judge decisions. */
public interface JudgeDecisionCompilation {
    String CONTRACT_VERSION = "JUDGE_DECISION_V1";

    Result compileCandidate(Input input, String candidateJson);

    Result compile(Input input, Candidate candidate);

    record Input(String role, EvidenceCatalog evidenceCatalog) {
        public Input { Objects.requireNonNull(evidenceCatalog, "evidenceCatalog"); }
    }

    record EvidenceCatalog(List<EvidenceItem> items) {
        public EvidenceCatalog { items = items == null ? List.of() : List.copyOf(items); }
    }

    record EvidenceItem(String id, String kind, String label, String sourceSha256) { }

    record Candidate(String contractVersion, String role, String verdict, String reason,
                     List<String> evidenceIds) {
        public Candidate { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); }
    }

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

    record Result(Candidate candidate, String canonicalCandidateJson, String deterministicReason,
                  List<EvidenceItem> selectedEvidence, String canonicalEvidenceJson,
                  String canonicalResultSha256, List<Problem> problems) {
        public Result {
            selectedEvidence = selectedEvidence == null ? List.of() : List.copyOf(selectedEvidence);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
        public boolean accepted() { return problems.isEmpty(); }
        public boolean retryable() {
            return !accepted() && problems.stream()
                    .allMatch(problem -> problem.problemClass() == ProblemClass.MECHANICAL);
        }
    }
}
