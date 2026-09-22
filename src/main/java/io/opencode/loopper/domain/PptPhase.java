package io.opencode.loopper.domain;

public enum PptPhase implements DescribedEnum {
    BRIEFING("明确需求"), DIRECTION("选择整体方向"), DESIGN("设计章节与页面"),
    PRODUCING("制作页面"), REVIEW("预览与修改"), EXPORTED("已有导出版本");
    private final String description;
    PptPhase(String description) { this.description = description; }
    @Override public String description() { return description; }
}
