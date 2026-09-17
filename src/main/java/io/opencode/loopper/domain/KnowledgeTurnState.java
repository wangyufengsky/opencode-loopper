package io.opencode.loopper.domain;
public enum KnowledgeTurnState implements DescribedEnum {
    PREPARED("准备发送"), CREATING("建立会话"), CREATE_UNKNOWN("核对创建"), SENDING("发送问题"), UNKNOWN("核对发送"),
    RUNNING("生成回答"), STOPPING("停止确认"), COMPLETED("回答完成"), STOPPED("已停止"), FAILED("回答失败");
    private final String description;
    KnowledgeTurnState(String description) { this.description = description; }
    public String description() { return description; }
    public boolean terminal() { return this == COMPLETED || this == STOPPED || this == FAILED; }
}
