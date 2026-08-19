package io.opencode.loopper.domain;

/** Stable semantic intent selected before workflow construction. */
public enum TaskIntent implements DescribedEnum {
    SOFTWARE_CHANGE("软件变更"), DOCUMENT_AUTHORING("文档编写"), DATA_CONVERSION("数据转换"),
    READ_ONLY_REVIEW("只读评审"), RESEARCH("调研报告"), CONFIGURATION("配置变更"),
    LOCAL_MAINTENANCE("本地日常维护"), LEGACY_SOFTWARE("历史软件任务");

    private final String description;
    TaskIntent(String description) { this.description = description; }
    @Override public String description() { return description; }
}
