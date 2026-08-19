package io.opencode.loopper.domain;

public enum StageKind implements DescribedEnum {
    SOFTWARE_IMPLEMENTATION("软件实施"), DOCUMENT_MATERIALIZATION("文档生成"),
    TABULAR_CONVERSION("表格转换"), READ_ONLY_ANALYSIS("只读分析"),
    LOCAL_MAINTENANCE("本地维护"), LEGACY_SOFTWARE("历史软件实施");

    private final String description;
    StageKind(String description) { this.description = description; }
    @Override public String description() { return description; }
}
