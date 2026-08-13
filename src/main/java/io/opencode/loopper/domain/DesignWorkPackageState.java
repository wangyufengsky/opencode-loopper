package io.opencode.loopper.domain;

/** Persisted serial design/compile progress for one vertical work package. */
public enum DesignWorkPackageState implements DescribedEnum {
    PENDING("工作包等待设计"),
    DESIGNING("工作包正在设计"),
    COMPILING("工作包正在编译"),
    VALIDATING("工作包正在确定性校验"),
    COMPLETED("工作包设计与编译已完成"),
    WAITING_INPUT("工作包等待人工恢复"),
    FAILED("工作包自动流程已终止");

    private final String description;
    DesignWorkPackageState(String description) { this.description = description; }
    @Override public String description() { return description; }
}
