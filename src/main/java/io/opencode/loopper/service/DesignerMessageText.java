package io.opencode.loopper.service;

/** Bounded user discussion text normalization shared by design entry points. */
final class DesignerMessageText {
    private DesignerMessageText() { }
    static String normalize(String content, int maximum) {
        if (content == null || content.isBlank()) throw new BadRequestException("DESIGNER_MESSAGE_REQUIRED",
                "Designer message content is required");
        String normalized = content.trim();
        if (normalized.length() > maximum) throw new BadRequestException("DESIGNER_MESSAGE_TOO_LONG",
                "Designer message must be at most " + maximum + " characters");
        return normalized;
    }

}
