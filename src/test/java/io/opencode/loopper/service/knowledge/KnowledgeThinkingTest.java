package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.runtime.OpenCodeClient.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeThinkingTest {
    @Test void keepsOnlyBoundedProviderExposedThinking() {
        String result = KnowledgeThinking.text(new SessionTranscript(List.of(
                new SessionPart("a", "OUTPUT", "answer", "不是思考", null),
                new SessionPart("b", "THINKING", "thinking", "x".repeat(50000), null),
                new SessionPart("c", "THINKING", "thinking", "y".repeat(50000), null))));
        assertThat(result).startsWith("x".repeat(50000) + "\n\n").contains("仅保留前 64000 字符").hasSizeLessThan(64100);
        assertThat(result).doesNotContain("不是思考");
        assertThat(KnowledgeThinking.text(new SessionTranscript(List.of()))).isEmpty();
    }
}
