package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import org.junit.jupiter.api.Test;

class ReviewerReportCandidatePromptFactoryTest {
    private final ReviewerReportCandidatePromptFactory prompts = new ReviewerReportCandidatePromptFactory();

    @Test
    void freezesExactPrivateToolAndCandidateOnlyContract() {
        MachineCandidateSubmission.RunSnapshot run = new MachineCandidateSubmission.RunSnapshot(
                "run-1", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.analysisReport("report-1"),
                MachineCandidateKind.REVIEWER_REPORT_V1, "REVIEWER_REPORT_V1", 7, 1,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "REVIEWER_REPORT_V1", "generation-1", "remote-1",
                MachineCandidateRunState.OPEN, 3, 0, null, 0);

        String prompt = prompts.internal("Read-only role instructions", "/repo", "Review concurrency safety",
                run, "loopper_private_submit_candidate");

        assertThat(prompt.replaceAll("\\s+", " "))
                .contains("REVIEWER_REPORT_V1 PRIVATE SUBMISSION CONTRACT")
                .contains("loopper_private_submit_candidate")
                .contains("runId: run-1")
                .contains("expectedSubmissionRevision: 0")
                .contains("at most three times")
                .contains("final assistant text is ignored")
                .contains("title, summary, findings, and limitations")
                .contains("zero to 128 objects")
                .contains("limitations: an array of strings", "do not add contractVersion")
                .contains("\"limitations\":[\"Describe an actual review limitation\"]")
                .contains("managed relative path and exact line")
                .doesNotContain("JSON_SCHEMA")
                .doesNotContain("legacy payload");
        String example = prompt.lines().map(String::strip)
                .filter(line -> line.startsWith("{\"title\":"))
                .findFirst().orElseThrow();
        assertThat(new ReviewerReportCandidateCodec(new tools.jackson.databind.ObjectMapper())
                .decodeCandidate(example).valid()).isTrue();
    }
}
