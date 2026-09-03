package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenCodeDesignMessageFilterTest {
    @Test void filteringCurrentOutputRetainsUsageFromAllBusinessRounds() {
        var messages = new ObjectMapper().readTree("""
            [{"info":{"id":"old","role":"assistant","parentID":"before","tokens":{"input":10,"output":5}},"parts":[{"type":"text","text":"old output"}]},
             {"info":{"id":"new","role":"assistant","parentID":"now","tokens":{"input":20,"output":7}},"parts":[{"type":"text","text":"current output"}]}]
            """);
        var transcript = OpenCodeDesignMessageFilter.transcript(messages, "now", new OpenCodeResponseParser());
        assertThat(transcript.usage()).hasSize(2);
        assertThat(transcript.parts()).hasSize(1);
    }

    @Test void lateQuestionFromPreviousTurnDoesNotEnterCurrentDiscussion() {
        var json = new ObjectMapper();
        var messages = json.readTree("[{\"info\":{\"id\":\"assistant-now\"}}]");
        var questions = json.readTree("""
            [{"id":"current","tool":{"messageID":"assistant-now"}},
             {"id":"late","tool":{"messageID":"assistant-old"}},
             {"id":"unknown"}]
            """);
        var selected = OpenCodeDesignMessageFilter.interactions(questions, messages, "now");
        assertThat(selected.size()).isEqualTo(1);
        assertThat(selected.get(0).path("id").asText()).isEqualTo("current");
    }
    @Test void isolatesCurrentTurnFromOldRepliesAndLateAccountingErrors() {
        var messages = new ObjectMapper().readTree("""
            [{"info":{"id":"old","role":"user"}},
             {"info":{"id":"now","role":"user"}},
             {"info":{"id":"late-old","role":"assistant","parentID":"old"}},
             {"info":{"id":"current","role":"assistant","parentID":"now"}},
             {"info":{"id":"accounting","role":"assistant","parentID":"msg_loopper_aicoding_x"}}]
            """);
        var filtered = OpenCodeDesignMessageFilter.filter(messages, "now");
        assertThat(filtered.size()).isEqualTo(2);
        assertThat(filtered.get(1).path("info").path("id").asText()).isEqualTo("current");
        assertThat(OpenCodeDesignMessageFilter.filter(messages, "not-received").size()).isZero();
        assertThat(OpenCodeDesignMessageFilter.filter(messages, null)).isSameAs(messages);
    }
}
