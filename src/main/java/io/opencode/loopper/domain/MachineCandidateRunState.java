package io.opencode.loopper.domain;

/** Independent lifecycle of one bounded authoritative candidate submission run. */
public enum MachineCandidateRunState implements DescribedEnum {
    OPEN("候选提交已开放"),
    ACCEPTED("候选已接受"),
    WAITING_INPUT("候选提交等待人工输入"),
    FALLBACK_REQUIRED("候选提交需要受控回退"),
    CLOSED("候选提交已关闭");

    private final String description;
    MachineCandidateRunState(String description) { this.description = description; }
    @Override public String description() { return description; }
    public boolean terminal() { return this != OPEN; }
}
