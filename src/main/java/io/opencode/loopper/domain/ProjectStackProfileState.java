package io.opencode.loopper.domain;

/** Stable project stack-analysis states. An absent row is projected as UNANALYZED. */
public enum ProjectStackProfileState {
    UNANALYZED("待分析"),
    READY("分析完成"),
    PARTIAL("证据不完整"),
    FAILED("分析失败");

    private final String description;

    ProjectStackProfileState(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
