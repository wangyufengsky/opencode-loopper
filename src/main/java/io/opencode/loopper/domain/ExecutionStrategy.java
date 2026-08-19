package io.opencode.loopper.domain;

public enum ExecutionStrategy implements DescribedEnum {
    OPEN_CODE_IMPLEMENTATION("OpenCode 可写实施"),
    SERVER_DOCUMENT_MATERIALIZATION("服务端文档生成"),
    SERVER_TABULAR_CONVERSION("服务端表格转换"),
    READ_ONLY_REPORT("只读报告");

    private final String description;
    ExecutionStrategy(String description) { this.description = description; }
    @Override public String description() { return description; }
}
