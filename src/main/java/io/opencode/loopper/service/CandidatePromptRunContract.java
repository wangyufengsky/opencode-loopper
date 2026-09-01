package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;

/** Closed INITIAL/CORRECTION prompt contract shared by the coordinator and persistence gate. */
final class CandidatePromptRunContract {
    private CandidatePromptRunContract() { }

    static void validateInternal(
            MachineCandidateSubmission.RunSnapshot run, CandidateLaunchRef launch) {
        if (run == null || launch == null) {
            throw new IllegalArgumentException("INTERNAL_MCP candidate prompt requires a typed launch");
        }
        if (run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) {
            throw new IllegalArgumentException("Typed candidate launch requires INTERNAL_MCP");
        }
        CandidateLaunchRef.Protocol expected = expectedProtocol(run.candidateKind());
        if (launch.protocol() != expected) {
            throw new IllegalArgumentException(run.candidateKind() + " requires " + expected + " launch");
        }
        MachineCandidateProtocolPolicy.Contract policy = MachineCandidateProtocolPolicy.contract(run.candidateKind());
        if (run.scope() == null || run.scope().type() != policy.scopeType()
                || run.owner() == null || run.owner().type() != policy.ownerType()
                || !run.candidateKind().name().equals(run.workflowStep())
                || !run.candidateKind().name().equals(run.contractVersion())
                || run.maxAttempts() != run.candidateKind().maximumAttempts()) {
            throw new IllegalArgumentException(
                    "Candidate prompt run kind, scope, owner, workflow, contract, or budget is stale");
        }
    }

    static CandidateLaunchRef.Protocol expectedProtocol(MachineCandidateKind kind) {
        if (kind == null) throw new IllegalArgumentException("Candidate kind is required");
        return switch (kind) {
            case ACCEPTANCE_CLOSED_CHOICE_V7 -> CandidateLaunchRef.Protocol.ACCEPTANCE_V55;
            case REVIEWER_REPORT_V1, PROJECT_CONVENTION_V1, JUDGE_DECISION_V1 ->
                    CandidateLaunchRef.Protocol.GENERIC_V1;
            default -> throw new IllegalArgumentException(
                    "Candidate kind has no durable prompt launch protocol: " + kind);
        };
    }
}
