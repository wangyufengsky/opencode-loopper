package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;

/** Explicit rollout, scope, owner, and fallback contract for each closed candidate kind. */
final class MachineCandidateProtocolPolicy {
    private MachineCandidateProtocolPolicy() { }

    static Contract contract(MachineCandidateKind kind) {
        return switch (kind) {
            case DECOMPOSITION_PLAN_V2 -> designer(
                    MachineCandidateSubmission.CandidateOwnerType.TASK_DECOMPOSITION, false, true);
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> designer(
                    MachineCandidateSubmission.CandidateOwnerType.LOOP_SPEC_COMPILATION, false, true);
            case PACKAGE_DESIGN_V1 -> designer(
                    MachineCandidateSubmission.CandidateOwnerType.DESIGN_WORK_PACKAGE, true, true);
            case ROLLING_PACKAGE_PLAN_V1 -> task(
                    MachineCandidateSubmission.CandidateOwnerType.TASK_PACKAGE_PLAN_REVISION);
            case REVIEWER_REPORT_V1 -> designer(
                    MachineCandidateSubmission.CandidateOwnerType.ANALYSIS_REPORT, false, false);
            case PROJECT_CONVENTION_V1 -> project(
                    MachineCandidateSubmission.CandidateOwnerType.PROJECT_CONVENTION_DRAFT);
            case JUDGE_DECISION_V1 -> task(MachineCandidateSubmission.CandidateOwnerType.JUDGE_RUN);
        };
    }

    private static Contract designer(MachineCandidateSubmission.CandidateOwnerType owner,
                                     boolean fallbackAllowed, boolean integrated) {
        return new Contract(MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION,
                owner, fallbackAllowed, integrated);
    }

    private static Contract task(MachineCandidateSubmission.CandidateOwnerType owner) {
        return new Contract(MachineCandidateSubmission.CandidateScopeType.TASK, owner, false, false);
    }

    private static Contract project(MachineCandidateSubmission.CandidateOwnerType owner) {
        return new Contract(MachineCandidateSubmission.CandidateScopeType.PROJECT, owner, false, false);
    }

    record Contract(MachineCandidateSubmission.CandidateScopeType scopeType,
                    MachineCandidateSubmission.CandidateOwnerType ownerType,
                    boolean fallbackAllowed, boolean integrated) { }
}
