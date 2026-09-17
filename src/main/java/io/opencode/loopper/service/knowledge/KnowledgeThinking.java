package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.runtime.OpenCodeClient.SessionTranscript;
import io.opencode.loopper.service.assist.AssistRedaction;

/** Bounded projection of provider-exposed thinking only; never tools, prompts or inferred reasoning. */
final class KnowledgeThinking {
    private static final int LIMIT = 64000;
    private KnowledgeThinking() { }
    static String text(SessionTranscript transcript) {
        StringBuilder result = new StringBuilder();
        for (var part : transcript.parts()) {
            if (!"THINKING".equals(part.type()) || part.content() == null || part.content().isBlank()) continue;
            String content = AssistRedaction.text(part.content());
            if (!result.isEmpty()) result.append("\n\n");
            int remaining = LIMIT - result.length();
            if (content.length() > remaining) {
                result.append(content, 0, Math.max(0, remaining)).append("\n\n（思考内容较长，仅保留前 64000 字符）");
                break;
            }
            result.append(content);
        }
        return result.toString();
    }
}
