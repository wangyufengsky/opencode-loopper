package io.opencode.loopper.domain;

/** Persisted transport selected for one bounded model response. */
public enum ModelResponseMode implements DescribedEnum {
    JSON_SCHEMA("OpenCode JSON Schema 结构化输出"),
    TEXT_MARKER("兼容 marker 文本输出");

    private final String description;

    ModelResponseMode(String description) { this.description = description; }

    @Override public String description() { return description; }
}
