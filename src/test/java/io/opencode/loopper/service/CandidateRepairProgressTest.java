package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.persistence.CandidateSubmissionAttemptRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CandidateRepairProgressTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void issueIdentityFollowsEntityKeyAcrossArrayReorderingAndReportsNewErrors() {
        var before = analyze("{\"scenarios\":[{\"key\":\"SC-1\"},{\"key\":\"SC-2\"}]}",
                List.of(problem("/scenarios/0/observableResult")), List.of(), true);
        var after = analyze("{\"scenarios\":[{\"key\":\"SC-2\"},{\"key\":\"SC-1\"}]}",
                List.of(problem("/scenarios/1/observableResult"), problem("/scenarios/0/action")),
                List.of(attempt(before, true)), true);
        assertThat(after.remaining()).containsExactly(before.issues().getFirst().id());
        assertThat(after.introduced()).hasSize(1);
        assertThat(after.resolved()).isEmpty();
        assertThat(after.repeatedCandidate()).isFalse();
        var fixed = analyze("{\"scenarios\":[{\"key\":\"SC-2\",\"action\":\"run\"}]}", List.of(),
                List.of(attempt(after, true)), true);
        assertThat(fixed.resolved()).hasSize(2);
    }

    @Test void detectsOscillationButDoesNotClaimProgressFromTruncatedDiagnostics() {
        var first = analyze("{\"a\":1,\"b\":2}", List.of(problem("/a")), List.of(), true);
        var next = analyze("{\"a\":2}", List.of(problem("/a")), List.of(attempt(first, true)), true);
        var repeat = analyze("{ \"b\":2, \"a\":1 }", List.of(problem("/a")),
                List.of(attempt(next, true), attempt(first, true)), true);
        assertThat(repeat.repeatedCandidate()).isTrue();
        var incomplete = analyze("{\"a\":3}", List.of(), List.of(attempt(first, false)), true);
        assertThat(incomplete.comparisonComplete()).isFalse();
        assertThat(incomplete.resolved()).isEmpty();
    }

    @Test void progressReservesSpaceForTheExistingSeventyTwoKibProblemEnvelope() {
        var problems = java.util.stream.IntStream.range(0, 64)
                .mapToObj(index -> problem("/" + "p".repeat(240) + index)).toList();
        var previous = analyze("{}", problems, List.of(), true);
        var changed = problems.stream().map(item -> problem(item.pointer() + "x")).toList();
        var current = analyze("{\"revision\":2}", changed, List.of(attempt(previous, true)), true);
        assertThat(current.resolved()).hasSize(64);
        assertThat(current.introduced()).hasSize(64);
        assertThat(json.writeValueAsBytes(current)).hasSizeLessThan(20 * 1024);
        assertThat(current.issues().getLast().problemIndex()).isEqualTo(63);
    }

    private CandidateRepairProgress.Progress analyze(String candidate, List<MachineCandidateSubmission.Problem> problems,
            List<CandidateSubmissionAttemptRow> history, boolean complete) {
        return CandidateRepairProgress.analyze(json, candidate, "raw-hash", problems, complete, history);
    }

    private MachineCandidateSubmission.Problem problem(String pointer) {
        return new MachineCandidateSubmission.Problem("MISSING_OBSERVABLE_OUTCOME", pointer, "Provide observable output");
    }

    private CandidateSubmissionAttemptRow attempt(CandidateRepairProgress.Progress progress, boolean complete) {
        return new CandidateSubmissionAttemptRow("id", "run", 1, "key", "sha", "REJECTED", true, "[]",
                json.writeValueAsString(Map.of("repairProgress", progress, "diagnosticsComplete", complete)), null, "now");
    }
}
