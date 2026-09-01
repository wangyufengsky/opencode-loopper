package io.opencode.loopper.domain;

/** Durable side-effect lifecycle for deterministic initial and correction candidate prompts. */
public enum CandidatePromptDispatchState implements DescribedEnum {
    PROMPTING("提示已预留"), ACKNOWLEDGED("提示已确认"), STOPPING("正在停止远端"),
    STOPPED("远端已确认停止"), DISCONNECTED("提示结果未知"), CANCELLED("未派发即取消");

    private final String description;
    CandidatePromptDispatchState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
