package io.opencode.loopper.template;

import java.util.List;

/** Public input capabilities are separate from each generation's frozen executable contract. */
public record TemplateCatalogEntry(String id, String version, String title, String description,
        int contentRepairLimit, List<String> stages, String scoringVersion, String icon, String category,
        DocumentTemplateDefinition.Inputs inputs, String workflow, List<String> reviewModes) {
    public static TemplateCatalogEntry report(TemplateTaskDefinition definition) {
        var view = definition.view();
        if (definition == TemplateTaskDefinition.SNAPSHOT_CODE_REVIEW)
            return new TemplateCatalogEntry(view.id(), view.version(), view.title(), view.description(), view.contentRepairLimit(),
                    List.of("冻结证据", "规划范围", "功能分析", "独立复核与报告"), null, "lucide:scan-search", "代码质量",
                    new DocumentTemplateDefinition.Inputs(false, true, true, List.of(), 0, 0, 0), "SNAPSHOT_CODE_REVIEW", List.of("DATE_INCREMENTAL", "FULL"));
        return new TemplateCatalogEntry(view.id(), view.version(), view.title(), view.description(), view.contentRepairLimit(),
                view.stages(), view.scoringVersion(), view.icon(), view.category(),
                new DocumentTemplateDefinition.Inputs(false, true, true, List.of(), 0, 0, 0), "GIT_HISTORY_REPORT", List.of());
    }
    public static TemplateCatalogEntry document(DocumentTemplateDefinition definition) {
        var view = definition.view();
        return new TemplateCatalogEntry(view.id(), view.version(), view.title(), view.description(), 0,
                definition.review() ? List.of("整理文档需求", "静态代码评审", "独立复核与报告")
                        : List.of("整理文档需求", "设计与开发", "测试验收与报告"),
                null, view.icon(), view.category(), view.inputs(), view.workflow(), List.of());
    }
}
