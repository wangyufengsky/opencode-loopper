package io.opencode.loopper.service;

/**
 * Revalidates the requested channel plus frozen runtime generation, external Session, owner version,
 * and source revision before a submission.
 * Implementations must be bounded and database/in-memory only because validation is repeated inside the short commit.
 */
public interface CandidateRunGuard {
    void validate(MachineCandidateSubmission.RunSnapshot run,
                  MachineCandidateSubmission.SubmissionChannel submissionChannel);
}
