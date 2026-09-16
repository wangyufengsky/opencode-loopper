package io.opencode.loopper.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeActivityFingerprintTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OpenCodeResponseParser parser = new OpenCodeResponseParser();

    @Test void detectsNewPartsAfterTheDisplayLimitAndDoesNotExposeContent() {
        var messages = json.createArrayNode();
        var message = messages.addObject(); message.putObject("info").put("role", "assistant");
        var parts = message.putArray("parts");
        for (int i = 0; i < 200; i++) parts.addObject().put("id", "p" + i).put("type", "text").put("text", "part" + i);
        var before = parser.transcript(messages);
        parts.addObject().put("id", "last").put("type", "text").put("text", "new-content-not-for-diagnostics");
        var after = parser.transcript(messages);
        assertThat(after.parts()).isEqualTo(before.parts());
        assertThat(after.activityFingerprint()).hasSize(64).isNotEqualTo(before.activityFingerprint()).doesNotContain("new-content");
        assertThat(parser.transcript(messages).activityFingerprint()).isEqualTo(after.activityFingerprint());
    }

    @Test void detectsStreamingBeyondTextLimitAndKeepsExactRequestScope() {
        var messages = json.createArrayNode();
        var message = messages.addObject(); message.putObject("info").put("role", "assistant").put("parentID", "request");
        var text = message.putArray("parts").addObject().put("id", "p").put("type", "text").put("text", "a".repeat(50000));
        var before = OpenCodeDesignMessageFilter.transcript(messages, "request", parser);
        text.put("text", "a".repeat(50001));
        var after = OpenCodeDesignMessageFilter.transcript(messages, "request", parser);
        assertThat(after.parts()).isEqualTo(before.parts());
        assertThat(after.activityFingerprint()).isNotEqualTo(before.activityFingerprint());
        var unrelated = messages.addObject(); unrelated.putObject("info").put("role", "assistant").put("parentID", "other");
        unrelated.putArray("parts").addObject().put("type", "text").put("text", "unrelated-change");
        assertThat(OpenCodeDesignMessageFilter.transcript(messages, "request", parser).activityFingerprint()).isEqualTo(after.activityFingerprint());
    }
}
