package io.opencode.loopper.template;

import java.util.List;

/** Source input is an explicit origin, independent of uploaded documents and historical Git reports. */
public enum SourceTemplateDefinition {
    UNIT_TEST_DEVELOPMENT("1", "单元测试开发", "补齐指定源码路径下的单元测试，并运行测试与验收", "代码质量", "lucide:test-tube-diagonal"),
    DETAILED_DESIGN_WRITING("1", "详细设计编写", "根据指定路径的现有代码编写详细设计、流程图和源码覆盖清单", "技术文档", "lucide:file-pen-line");

    private final String version;
    private final String title;
    private final String description;
    private final String category;
    private final String icon;
    SourceTemplateDefinition(String version, String title, String description, String category, String icon) {
        this.version = version; this.title = title; this.description = description; this.category = category; this.icon = icon;
    }
    public String version() { return version; }
    public String title() { return title; }
    public boolean development() { return this == UNIT_TEST_DEVELOPMENT; }
    public TemplateCatalogEntry view() {
        return new TemplateCatalogEntry(name(), version, title, description, 0,
                development() ? List.of("冻结源码范围", "设计与编写测试", "运行测试与验收")
                        : List.of("冻结源码范围", "编写详细设计", "独立复核与文档"),
                null, icon, category,
                new DocumentTemplateDefinition.Inputs(false, false, false, List.of(), 0, 0, 0,
                        true, development(), !development()), "SOURCE_TEMPLATE", List.of());
    }
    public static SourceTemplateDefinition require(String value) {
        return valueOf(value);
    }
}
