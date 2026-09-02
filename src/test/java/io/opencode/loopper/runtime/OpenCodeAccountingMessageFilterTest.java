package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenCodeAccountingMessageFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String MESSAGES = """
        [
          {"info":{"id":"msg_loopper_aicoding_first","role":"user"}},
          {"info":{"id":"accounting-answer","role":"assistant","parentID":"msg_loopper_aicoding_first"},"parts":[{"type":"text","text":"runId=123"}]},
          {"info":{"id":"business","role":"user"}},
          {"info":{"id":"business-answer","role":"assistant","parentID":"business","time":{"completed":123}},"parts":[{"type":"text","text":"BUSINESS_ONLY"}]},
          {"info":{"id":"late","role":"assistant","parentID":"msg_loopper_aicoding_first","error":{"message":"stats failed"}}}
        ]
        """;

    @Test void excludesExactStatisticsParentsIncludingLateErrorsWithoutRelyingOnText() {
        var filtered = OpenCodeAccountingMessageFilter.filter(json.readTree(MESSAGES), Set.of());
        assertThat(filtered.size()).isEqualTo(2);
        assertThat(new OpenCodeResponseParser().assistantText(filtered.get(1))).isEqualTo("BUSINESS_ONLY");
        assertThat(new OpenCodeResponseParser().messageStatus(filtered).completed()).isTrue();
    }

    @Test void ignoresRuntimeFailureOrBusyProducedByLateStatistics() {
        for (String status : new String[]{"busy", "FAILED", "IDLE"}) {
            assertThat(OpenCodeAccountingMessageFilter.status(json.readTree(MESSAGES), Set.of(),
                    new OpenCodeClient.SessionStatus(status)).completed()).isTrue();
        }
    }

    @Test void preservesRealBusinessErrorsAndProviderRetry() {
        var raw = json.readTree(MESSAGES).deepCopy();
        ((tools.jackson.databind.node.ArrayNode) raw).remove(4);
        assertThat(OpenCodeAccountingMessageFilter.status(raw, Set.of(),
                new OpenCodeClient.SessionStatus("RETRY")).state()).isEqualTo("RETRY");
        var failed = json.readTree(MESSAGES.replace("\"time\":{\"completed\":123}",
                "\"error\":{\"message\":\"business failed\"}"));
        assertThat(OpenCodeAccountingMessageFilter.status(failed, Set.of(),
                new OpenCodeClient.SessionStatus("busy")).failed()).isTrue();
    }

    @Test void statisticsCompletionCannotCompleteABusinessPromptThatHasNoAnswer() {
        var raw = (tools.jackson.databind.node.ArrayNode) json.readTree(MESSAGES);
        raw.remove(3);
        assertThat(OpenCodeAccountingMessageFilter.status(raw, Set.of(),
                new OpenCodeClient.SessionStatus("IDLE")).state()).isEqualTo("RUNNING");
    }
}
