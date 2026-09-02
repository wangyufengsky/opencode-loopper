package io.opencode.loopper.service;

/** Creation-only story identity. Values remain strings, including leading zeroes. */
public record StoryBindingConfiguration(boolean enabled, String systemCode, String storyCode) {
    public static StoryBindingConfiguration disabled() { return new StoryBindingConfiguration(false, null, null); }
    StoryBindingConfiguration normalized() {
        if (!enabled) return disabled();
        return new StoryBindingConfiguration(true, normalize(systemCode, "系统编号"), normalize(storyCode, "故事编号"));
    }
    private static String normalize(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new BadRequestException("STORY_BINDING_CODE_REQUIRED", label + "不能为空");
        if (normalized.length() > 128) {
            throw new BadRequestException("STORY_BINDING_CODE_TOO_LONG", label + "不能超过 128 个字符");
        }
        if (normalized.chars().anyMatch(character -> Character.isISOControl(character)
                || Character.isWhitespace(character))) {
            throw new BadRequestException("STORY_BINDING_CODE_INVALID", label + "不能包含空白或控制字符");
        }
        return normalized;
    }
}
