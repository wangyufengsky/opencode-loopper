package io.opencode.loopper.domain;

public enum LoopDraftStatus implements DescribedEnum {
    DRAFTING("正在编写草稿"), DRAFT_READY("草稿可确认"), CONFIRMED("草稿已确认"),
    HANDOFF_FAILED("设计交接失败");

    private final String description;
    LoopDraftStatus(String description) { this.description = description; }
    @Override public String description() { return description; }
}
