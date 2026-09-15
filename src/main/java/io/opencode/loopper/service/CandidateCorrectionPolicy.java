package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateKind;

/** Resolves configuration only when opening a new run; persisted limits own every subsequent decision. */
final class CandidateCorrectionPolicy {
    private CandidateCorrectionPolicy() { }

    static Integer limit(MachineCandidateSubmission.OpenCommand command, LoopperProperties properties) {
        if (command.correctionLimit() != null) return command.correctionLimit();
        if (command.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || command.candidateKind() == MachineCandidateKind.PACKAGE_DESIGN_V1) return null;
        Integer value = properties.getInternalCandidate().getCorrectionLimits().get(command.candidateKind());
        return value == null || value == 0 ? null : value;
    }

    static String prompt(MachineCandidateSubmission.RunSnapshot run) {
        return prompt(run.correctionLimit());
    }

    static String prompt(Integer correctionLimit) {
        return (correctionLimit == null ? "MCP submissions have no count limit."
                : "This run permits at most " + correctionLimit + " total submissions including the first.")
                + " Before composing or repairing parameters, call describe_submission_contract with this runId and pointer=empty string for the actual schema and revision. "
                + " Follow the returned action and submissionRevision. On REJECTED, repair all reported root problems; "
                + "preserve valid fields, entity keys, frozen references and the evidence-grounded conclusion. "
                + "Submit a complete replacement with a fresh idempotencyKey only after changing rejected content. "
                + "repairProgress reports resolved/remaining/introduced issue IDs and repeatedCandidate; "
                + "comparisonComplete=false means absence does not prove resolution. Correcting shape may reveal "
                + "later semantic errors. On ACCEPTED or WAITING_INPUT stop. Never alter frozen evidence or an "
                + "evidence-grounded conclusion merely to satisfy validation.";
    }
}
