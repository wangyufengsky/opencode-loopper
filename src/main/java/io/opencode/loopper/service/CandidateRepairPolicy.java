package io.opencode.loopper.service;

import java.util.List;
import java.util.Set;

/** Rejection severity does not decide whether the author may submit a corrected candidate. */
final class CandidateRepairPolicy {
    private static final Set<String> REPAIRABLE = Set.of(
            "PACKAGE_DESIGN_SECURITY_BOUNDARY", "PROJECT_CONVENTION_AUTHORITY_FIELD_FORBIDDEN",
            "ROLLING_PACKAGE_AUTHORITY_FIELD_FORBIDDEN", "JUDGE_DECISION_AUTHORITY_FIELD_FORBIDDEN",
            "JUDGE_DECISION_CONTRACT_VERSION_INVALID", "JUDGE_DECISION_ROLE_MISMATCH",
            "JUDGE_DECISION_REASON_CONTROL_INVALID", "REVIEWER_EVIDENCE_PATH_UNSAFE",
            "REVIEWER_AUTHORITY_FIELD_FORBIDDEN",
            "PACKAGE_COMPILED_SCOPE_EXPANSION",
            "JUDGE_DECISION_VERDICT_INVALID", "JUDGE_DECISION_REASON_INVALID",
            "JUDGE_DECISION_REASON_LINE_BREAK_INVALID", "JUDGE_DECISION_EVIDENCE_REQUIRED",
            "JUDGE_DECISION_EVIDENCE_INVALID", "REVIEWER_EVIDENCE_PATH_INVALID",
            "REVIEWER_EVIDENCE_PATH_MISSING", "REVIEWER_EVIDENCE_LINE_INVALID");

    private CandidateRepairPolicy() { }

    static boolean repairable(List<MachineCandidateSubmission.Problem> problems) {
        return !problems.isEmpty() && problems.stream().allMatch(problem -> REPAIRABLE.contains(problem.code()));
    }
}
