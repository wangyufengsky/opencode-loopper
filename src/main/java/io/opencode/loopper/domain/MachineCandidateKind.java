package io.opencode.loopper.domain;

/** Closed candidate contracts that may use the internal submission protocol. */
public enum MachineCandidateKind implements DescribedEnum {
    DECOMPOSITION_PLAN_V2("任务拆解计划候选", 5),
    ACCEPTANCE_CLOSED_CHOICE_V7("验收闭集选择候选", 2),
    PACKAGE_DESIGN_V1("工作包设计候选", 3),
    ROLLING_PACKAGE_PLAN_V1("滚动工作包计划候选", 3),
    REVIEWER_REPORT_V1("评审报告候选", 3),
    PROJECT_CONVENTION_V1("项目公约候选", 3),
    JUDGE_DECISION_V1("验收评审决定候选", 2),
    DOCUMENT_REQUIREMENTS_V1("需求提取候选", 3),
    DOCUMENT_REQUIREMENT_REVIEW_V1("需求原文复核候选", 3),
    REQUIREMENT_CODE_ASSESSMENT_V1("需求代码评审候选", 3),
    REQUIREMENT_ASSESSMENT_REVIEW_V1("需求代码评审复核候选", 3);

    private final String description;
    private final int maximumAttempts;

    MachineCandidateKind(String description, int maximumAttempts) {
        this.description = description;
        this.maximumAttempts = maximumAttempts;
    }

    @Override public String description() { return description; }
    /** Legacy repair ceiling and frozen protocol identity; not an INTERNAL_MCP submission quota. */
    public int maximumAttempts() { return maximumAttempts; }
}
