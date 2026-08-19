package io.opencode.loopper.domain;

public enum ArtifactKind implements DescribedEnum {
    SOURCE_CODE("源代码"), PYTHON_SCRIPT("Python 脚本"), MARKDOWN("Markdown 文档"),
    DOCX("DOCX 文档"), XLSX("XLSX 工作簿"), CSV("CSV 数据"), TSV("TSV 数据"),
    CONFIGURATION("配置文件"), ANALYSIS_REPORT("分析报告"), OTHER("其他制品");

    private final String description;
    ArtifactKind(String description) { this.description = description; }
    @Override public String description() { return description; }
}
