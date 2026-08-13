package io.opencode.loopper.domain;

/** Persisted sub-step for read-only roles that plan before emitting the final machine JSON. */
public enum StructuredModelStep implements DescribedEnum {
    PLANNING("正在规划并建立证据映射"),
    GENERATING_JSON("正在根据已冻结规划生成结构化 JSON"),
    REPAIRING_JSON("正在根据确定性错误修复结构化 JSON"),
    FINAL_JSON("结构化 JSON 已生成");

    private final String description;

    StructuredModelStep(String description) { this.description = description; }

    @Override public String description() { return description; }
}
