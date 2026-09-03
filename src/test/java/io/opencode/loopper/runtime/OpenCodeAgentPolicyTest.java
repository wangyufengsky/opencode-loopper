package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpenCodeAgentPolicyTest {
    @Test
    void legacySessionWithoutProfileRetainsItsExplicitBoundedAgent() {
        var request = new OpenCodeClient.PromptRequest("continue rolling plan", null,
                OpenCodeClient.STRUCTURED_AGENT, new OpenCodeClient.ResponseFormat.Text());
        for (boolean managed : new boolean[] {true, false}) {
            assertThat(OpenCodePromptBody.encode(request, null, managed, null, List.of()))
                    .containsEntry("agent", OpenCodeClient.STRUCTURED_AGENT);
        }
    }

    @Test
    void externalReviewerDoesNotReceiveAStaleManagedAgentAndCustomAgentsRemainExplicit() {
        var request = new OpenCodeClient.PromptRequest("review", null, OpenCodeClient.STRUCTURED_AGENT,
                OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.REVIEWER_REPORT_V1));
        var body = OpenCodePromptBody.encode(request, OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY,
                false, null, List.of());
        assertThat(body).doesNotContainKey("agent").containsKey("format");
        var custom = new OpenCodeClient.PromptRequest("review", null, "user-reviewer", new OpenCodeClient.ResponseFormat.Text());
        assertThat(OpenCodePromptBody.encode(custom, OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY,
                true, null, List.of())).containsEntry("agent", "user-reviewer");
    }
}
