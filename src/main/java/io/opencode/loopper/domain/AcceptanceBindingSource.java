package io.opencode.loopper.domain;

/** Stable owner of the acceptance binding recorded for one compilation. */
public enum AcceptanceBindingSource implements DescribedEnum {
    UNDECIDED("尚未决定验收绑定路径"),
    SERVER_STAGE_HINTS("服务端直接编译"),
    AI_DISAMBIGUATION_V6("规范工程师辅助消歧"),
    LEGACY_UNKNOWN("历史编译");

    private final String description;

    AcceptanceBindingSource(String description) { this.description = description; }

    @Override public String description() { return description; }
}
