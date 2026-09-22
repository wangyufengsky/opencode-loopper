package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.domain.DescribedEnum;

public enum PptAgentState implements DescribedEnum {
    PREPARED("准备中"), CREATING("建立会话"), CREATE_UNKNOWN("会话待核对"),
    SENDING("发送中"), UNKNOWN("投递待核对"), RUNNING("制作中"), STOPPING("确认停止"),
    WAITING_INPUT("等待回答"), COMPLETED("已完成"), STOPPED("已停止"), FAILED("失败");
    private final String description;
    PptAgentState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this == COMPLETED || this == STOPPED || this == FAILED; }
}
