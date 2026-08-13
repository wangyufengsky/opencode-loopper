package io.opencode.loopper.domain;

/** Declared implementation class used by the Java unit-test acceptance gate. */
public enum ImplementationKind implements DescribedEnum {
    JAVA_PRODUCTION("新增或修改生产 Java 代码"),
    JAVA_TEST_ONLY("需求仅新增或修改 Java 测试"),
    NON_JAVA("不涉及生产 Java 代码");

    private final String description;
    ImplementationKind(String description) { this.description = description; }
    @Override public String description() { return description; }
}
