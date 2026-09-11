package io.opencode.loopper.domain;

/** Report evidence batches are independent of Task, Attempt and remote Session success. */
public enum TemplateBatchState implements DescribedEnum {
    PREPARED("证据已准备"), CREATING("正在创建分析会话"), PROMPT_READY("分析请求已冻结"),
    DISPATCHING("正在发送分析请求"), RUNNING("正在分析"), VALIDATED("分析候选已验证"),
    STOPPING("正在确认分析停止"), STOPPED("分析已停止"), FAILED("分析候选未通过");

    private final String description;
    TemplateBatchState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == VALIDATED || this == STOPPED || this == FAILED; }
}
