package io.opencode.loopper.domain;

/** Visible phase of the read-only Designer -> Compiler -> Validator workflow. */
public enum DesignWorkflowPhase implements DescribedEnum {
    DISCUSSING_REQUIREMENT("正在讨论和澄清整体需求"),
    DECOMPOSING("任务拆解器正在分析需求"),
    VALIDATING_DECOMPOSITION("服务端正在校验拆解计划"),
    DESIGNING("设计师正在生成完整设计稿"),
    COMPILING("规范编译器正在生成 LoopSpec"),
    VALIDATING("确定性校验器正在验证 LoopSpec"),
    REDESIGNING("设计师正在根据设计缺口重新设计"),
    QUESTIONING_PACKAGE("设计师正在澄清当前工作包"),
    REVIEWING_PACKAGE("当前工作包等待人工确认"),
    AGGREGATING("服务端正在聚合完整 LoopSpec"),
    FINAL_REVIEW("完整设计等待总体确认"),
    COMPLETED("设计与 LoopSpec 已完成"),
    FAILED("自动设计工作流已停止");

    private final String description;
    DesignWorkflowPhase(String description) { this.description = description; }
    @Override public String description() { return description; }
}
