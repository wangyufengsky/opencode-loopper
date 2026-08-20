package io.opencode.loopper.domain;

/** Independent lifecycle of one compilation attempt for a frozen design revision. */
public enum LoopSpecCompilationState implements DescribedEnum {
    PENDING_HANDOFF("等待连接规范工程师"),
    RUNNING("规范工程师正在工作"),
    DESIGN_INCOMPLETE("设计稿缺少可编译的业务语义"),
    COMPLETED("LoopSpec 编译并校验通过"),
    SESSION_ERROR("规范编译会话失败");

    private final String description;
    LoopSpecCompilationState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
