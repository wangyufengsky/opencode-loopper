package io.opencode.loopper.domain;

/** Result of one unique candidate submission attempt. */
public enum MachineCandidateOutcome implements DescribedEnum {
    REJECTED("候选被拒绝，可在当前运行内修正"),
    WAITING_INPUT("候选预算耗尽，需要人工输入"),
    FALLBACK_REQUIRED("候选预算耗尽，需要使用受控回退"),
    ACCEPTED("候选已由服务端接受并冻结");

    private final String description;
    MachineCandidateOutcome(String description) { this.description = description; }
    @Override public String description() { return description; }
}
