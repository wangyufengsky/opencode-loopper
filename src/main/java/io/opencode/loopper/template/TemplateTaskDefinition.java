package io.opencode.loopper.template;

import java.util.List;

/** Versioned, server-owned public tasks. Changing executable semantics requires a new version. */
public enum TemplateTaskDefinition {
    CODE_REVIEW("历史提交审查", "审查历史 Git 提交，追溯作者、提交者与共同作者；不判断问题在当前版本是否仍存在"),
    SNAPSHOT_CODE_REVIEW("代码审查", "轻量审查冻结目标版本，支持日期增量和全面审查，仅复核发现的问题"),
    CONTRIBUTION_REPORT("项目人员贡献周报", "分析代码贡献，生成贡献排名、项目总报告和个人周报");

    public static final String VERSION = "10";
    public static final int CONTENT_REPAIR_LIMIT = 2;
    private final String title;
    private final String description;

    TemplateTaskDefinition(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String title() { return title; }

    /** Unknown and historical versions retain the stronger frozen acceptance requirement. */
    public static boolean requiresDualReview(String version) { return !List.of("7", "8", "9", "10").contains(version == null ? "" : version); }
    public View view() {
        return new View(name(), this == SNAPSHOT_CODE_REVIEW ? "3" : VERSION, title, description, CONTENT_REPAIR_LIMIT,
                this == SNAPSHOT_CODE_REVIEW ? List.of("准备范围", "代码分析", "问题复核与报告") : List.of("采集 Git 证据", "分析与生成报告"),
                this == CONTRIBUTION_REPORT ? ContributionScore.VERSION : null,
                this != CONTRIBUTION_REPORT ? "lucide:scan-search" : "lucide:chart-no-axes-combined",
                this != CONTRIBUTION_REPORT ? "代码质量" : "项目洞察");
    }

    public record View(String id, String version, String title, String description,
                       int contentRepairLimit, List<String> stages, String scoringVersion, String icon, String category) { }
}
