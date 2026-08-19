package io.opencode.loopper.domain;

public enum TestPolicy implements DescribedEnum {
    REQUIRED("必须测试"), OPTIONAL("测试可选"), NOT_APPLICABLE("不适用测试");

    private final String description;
    TestPolicy(String description) { this.description = description; }
    @Override public String description() { return description; }
}
