package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import java.util.Objects;

/**
 * Bounded candidate validation seam. Implementations may read frozen owner/revision facts from SQLite outside
 * the commit transaction, but must not perform network, model, process, or filesystem I/O.
 */
public interface CandidatePolicy {
    boolean supports(MachineCandidateKind kind);
    Decision evaluate(Context context, String candidateJson);

    record Context(
            String runId, MachineCandidateSubmission.CandidateScope scope,
            MachineCandidateSubmission.CandidateOwnerRef owner,
            MachineCandidateKind candidateKind, String workflowStep, long sourceRevision, long ownerVersion,
            String contractVersion, int maxAttempts, int attemptsUsed) { }

    record Decision(boolean accepted, String canonicalCandidateJson, boolean retryable, boolean fallbackEligible,
                    List<MachineCandidateSubmission.Problem> problems, boolean diagnosticsComplete) {
        public Decision {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        }

        public Decision(boolean accepted, String canonicalCandidateJson, boolean retryable,
                boolean fallbackEligible, List<MachineCandidateSubmission.Problem> problems) {
            this(accepted, canonicalCandidateJson, retryable, fallbackEligible, problems, true);
        }

        public static Decision accepted(String canonicalCandidateJson) {
            return new Decision(true, Objects.requireNonNull(canonicalCandidateJson), false, false, List.of(), true);
        }

        public static Decision rejected(boolean retryable, List<MachineCandidateSubmission.Problem> problems) {
            return rejected(retryable, false, problems);
        }

        public static Decision rejected(boolean retryable, boolean fallbackEligible,
                                        List<MachineCandidateSubmission.Problem> problems) {
            return new Decision(false, null, retryable, fallbackEligible, problems, true);
        }

        public static Decision rejected(boolean retryable, boolean fallbackEligible,
                List<MachineCandidateSubmission.Problem> problems, boolean diagnosticsComplete) {
            return new Decision(false, null, retryable, fallbackEligible, problems, diagnosticsComplete);
        }
    }
}
