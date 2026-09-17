package io.opencode.loopper.domain;
public enum KnowledgeConversationState implements DescribedEnum {
    IDLE("可提问"), RUNNING("回答中"), STOPPING("停止中"), DISCONNECTED("连接中断");
    private final String description;
    KnowledgeConversationState(String description) { this.description = description; }
    public String description() { return description; }
}
