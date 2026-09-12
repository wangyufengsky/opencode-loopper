package io.opencode.loopper.template;

import java.util.List;

/** Versioned, server-owned public tasks. Changing executable semantics requires a new version. */
public enum TemplateTaskDefinition {
    CODE_REVIEW("代码审查", "审查所选分支和日期范围内的 Git 提交，生成 Markdown 代码审查报告"),
    CONTRIBUTION_REPORT("项目人员贡献周报", "分析代码贡献，生成贡献排名、项目总报告和个人周报");

    public static final String VERSION = "3";
    public static final int CONTENT_REPAIR_LIMIT = 2;
    private final String title;
    private final String description;

    TemplateTaskDefinition(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String title() { return title; }
    public View view() {
        return new View(name(), VERSION, title, description, CONTENT_REPAIR_LIMIT,
                List.of("采集 Git 证据", "分析与生成报告"),
                this == CONTRIBUTION_REPORT ? ContributionScore.VERSION : null,
                this == CODE_REVIEW ? "lucide:scan-search" : "lucide:chart-no-axes-combined",
                this == CODE_REVIEW ? "代码质量" : "项目洞察");
    }

    public record View(String id, String version, String title, String description,
                       int contentRepairLimit, List<String> stages, String scoringVersion, String icon, String category) { }
}
