package io.opencode.loopper.service;

import io.opencode.loopper.domain.DescribedEnum;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateOutcome;
import io.opencode.loopper.domain.MachineCandidateRunState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deep module interface for one bounded machine-candidate correction loop.
 * Policy evaluation is deterministic and occurs outside the short persistence transaction.
 */
public interface MachineCandidateSubmission {
    RunSnapshot open(OpenCommand command);
    SubmissionResult submit(SubmitCommand command);
    RunSnapshot close(CloseCommand command);
    Optional<RunSnapshot> find(String runId);
    Optional<SubmissionResult> terminal(String runId);

    record OpenCommand(
            String runId, String designerSessionId, CandidateOwner owner, MachineCandidateKind candidateKind,
            String workflowStep, long sourceRevision, long ownerVersion, SubmissionChannel submissionChannel,
            String contractVersion, String runtimeGenerationId, String externalSessionId, int maxAttempts) { }

    record SubmitCommand(String runId, String idempotencyKey, String candidateJson,
                         long expectedSubmissionRevision, SubmissionChannel submissionChannel) { }
    record CloseCommand(String runId, long expectedVersion) { }

    record CandidateOwner(String taskDecompositionId, String loopSpecCompilationId) {
        public CandidateOwner {
            boolean decomposition = taskDecompositionId != null && !taskDecompositionId.isBlank();
            boolean compilation = loopSpecCompilationId != null && !loopSpecCompilationId.isBlank();
            if (decomposition == compilation) {
                throw new IllegalArgumentException("Candidate owner must identify exactly one machine role run");
            }
        }

        public static CandidateOwner taskDecomposition(String id) { return new CandidateOwner(id, null); }
        public static CandidateOwner loopSpecCompilation(String id) { return new CandidateOwner(null, id); }
    }

    record RunSnapshot(
            String runId, String designerSessionId, CandidateOwner owner, MachineCandidateKind candidateKind,
            String workflowStep, long sourceRevision, long ownerVersion, SubmissionChannel submissionChannel,
            String contractVersion, String runtimeGenerationId, String externalSessionId,
            MachineCandidateRunState state, int maxAttempts, int attemptsUsed, String terminalAttemptId, long version) { }

    enum SubmissionChannel implements DescribedEnum {
        INTERNAL_MCP("内部 MCP"),
        IN_PROCESS_LEGACY("进程内兼容调用");

        private final String description;
        SubmissionChannel(String description) { this.description = description; }
        @Override public String description() { return description; }
    }

    record Problem(String code, String pointer, String detail, List<String> allowedValues) {
        public Problem {
            allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
        }

        public Problem(String code, String pointer, String detail) {
            this(code, pointer, detail, List.of());
        }
    }

    record SubmissionResult(
            String runId, MachineCandidateOutcome outcome, MachineCandidateRunState runState,
            int attemptOrdinal, int remainingAttempts, boolean retryable, List<Problem> problems,
            String canonicalResultSha256, long submissionRevision, String responseJson) {
        public SubmissionResult {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        }
    }
}
