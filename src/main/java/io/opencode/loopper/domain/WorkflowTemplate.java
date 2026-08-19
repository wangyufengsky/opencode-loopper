package io.opencode.loopper.domain;

public enum WorkflowTemplate implements DescribedEnum {
    FULL_PACKAGE_DESIGN("完整分包设计"), DIRECT_ARTIFACT("直接制品"),
    PACKAGED_ARTIFACT("分包制品"), READ_ONLY_REPORT("只读报告"),
    LOCAL_MAINTENANCE("本地维护");

    private final String description;
    WorkflowTemplate(String description) { this.description = description; }
    @Override public String description() { return description; }
}
