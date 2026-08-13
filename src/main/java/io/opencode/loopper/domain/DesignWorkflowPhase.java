package io.opencode.loopper.domain;

/** Visible phase of the read-only Designer -> Compiler -> Validator workflow. */
public enum DesignWorkflowPhase implements DescribedEnum {
    DESIGNING("设计师正在生成完整设计稿"),
    COMPILING("规范编译器正在生成 LoopSpec"),
    VALIDATING("确定性校验器正在验证 LoopSpec"),
    REDESIGNING("设计师正在根据设计缺口重新设计"),
    COMPLETED("设计与 LoopSpec 已完成"),
    FAILED("自动设计工作流已停止");

    private final String description;
    DesignWorkflowPhase(String description) { this.description = description; }
    @Override public String description() { return description; }
}
